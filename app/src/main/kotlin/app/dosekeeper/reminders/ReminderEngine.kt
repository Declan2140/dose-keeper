package app.dosekeeper.reminders

import androidx.room.withTransaction
import app.dosekeeper.core.DoseRecord
import app.dosekeeper.core.DoseStatus
import app.dosekeeper.core.DoseTracker
import app.dosekeeper.data.AppDatabase
import app.dosekeeper.data.DoseLog
import app.dosekeeper.data.Medicine
import app.dosekeeper.data.MedStatus
import app.dosekeeper.data.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

/**
 * Applies [DoseTracker] to stored medicines and keeps alarms in step with the database.
 * Every change goes through here, one at a time, so an alarm firing while the user taps
 * Taken can't log a dose twice.
 */
class ReminderEngine(
    private val db: AppDatabase,
    private val settings: Settings,
    private val alarms: AlarmScheduler,
    private val notifier: Notifier,
) {
    private val lock = Mutex()
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** Saves a new or edited medicine and sets its alarm. Returns its id. */
    suspend fun save(medicine: Medicine, scheduleChanged: Boolean): Long = lock.withLock {
        val now = Instant.now()
        val existing = if (medicine.id != 0L) db.medicines().get(medicine.id) else null
        var med = medicine
        if (existing == null || scheduleChanged) {
            val state = if (existing == null) DoseTracker.start(med.parsedSchedule, now, zone)
            else DoseTracker.scheduleChanged(med.parsedSchedule, existing.tracker, now, zone)
            med = med.withTracker(state)
        }
        val id = if (existing == null) db.medicines().insert(med.copy(id = 0)) else med.id.also { db.medicines().update(med) }
        syncAlarm(med.copy(id = id))
        id
    }

    suspend fun answer(medicineId: Long, status: DoseStatus) = lock.withLock {
        val med = db.medicines().get(medicineId) ?: return@withLock
        if (med.nextDueAt == null) return@withLock
        val now = Instant.now()
        val schedule = med.parsedSchedule
        val (caught, missed) = DoseTracker.catchUp(schedule, med.tracker, now, zone, settings.missedWindow)
        // The dose the user is answering may have just slipped past the missed window. Answering it
        // still counts; only doses before it are logged as missed.
        val answering = if (missed.isNotEmpty()) med.tracker.copy(nextDue = missed.last().due, snoozedUntil = null) else caught
        val (next, record) = DoseTracker.answer(schedule, answering, status, now, zone)
        db.withTransaction {
            log(medicineId, missed.dropLast(if (missed.isNotEmpty()) 1 else 0))
            log(medicineId, listOf(record))
            db.medicines().update(med.withTracker(next))
        }
        notifier.cancelDose(medicineId)
        syncAlarm(med.withTracker(next))
    }

    /**
     * Unchecks a taken or skipped dose, making it the current dose again. Only the medicine's
     * latest dose can be unchecked, so the doses after it are still worked out in order.
     */
    suspend fun undo(medicineId: Long, dueAt: Long) = lock.withLock {
        val med = db.medicines().get(medicineId) ?: return@withLock
        if (!med.isActive) return@withLock
        val log = db.doseLogs().lastScheduled(medicineId) ?: return@withLock
        if (log.dueAt != dueAt || log.doseStatus == DoseStatus.MISSED) return@withLock
        val schedule = runCatching { med.parsedSchedule }.getOrNull() ?: return@withLock
        val updated = med.withTracker(DoseTracker.undo(schedule, Instant.ofEpochMilli(dueAt), Instant.now(), settings.snoozeMinutes))
        db.withTransaction {
            db.doseLogs().delete(log)
            db.medicines().update(updated)
        }
        notifier.cancelDose(medicineId)
        syncAlarm(updated)
    }

    suspend fun snooze(medicineId: Long, minutes: Int = settings.snoozeMinutes) = lock.withLock {
        val med = db.medicines().get(medicineId) ?: return@withLock
        if (med.nextDueAt == null) return@withLock
        val updated = med.withTracker(DoseTracker.snooze(med.tracker, Instant.now(), minutes))
        db.medicines().update(updated)
        notifier.cancelDose(medicineId)
        syncAlarm(updated)
    }

    suspend fun logAsNeeded(medicineId: Long) = lock.withLock {
        log(medicineId, listOf(DoseTracker.logAsNeeded(Instant.now())))
    }

    suspend fun setStatus(medicineId: Long, status: MedStatus) = lock.withLock {
        val med = db.medicines().get(medicineId) ?: return@withLock
        if (med.medStatus == MedStatus.ARCHIVED) return@withLock // archived medicines never come back
        var updated = med.copy(status = status.name)
        if (status == MedStatus.ACTIVE && med.medStatus == MedStatus.PAUSED) {
            updated = updated.withTracker(DoseTracker.resume(med.parsedSchedule, med.tracker, Instant.now(), zone))
        }
        db.medicines().update(updated)
        notifier.cancelDose(medicineId)
        syncAlarm(updated)
    }

    /** An alarm went off: log anything missed, then ring if a dose is due. */
    suspend fun onAlarm(medicineId: Long) = lock.withLock {
        if (medicineId == AlarmScheduler.TEST_ID) {
            notifier.showTest()
            return@withLock
        }
        val med = db.medicines().get(medicineId) ?: return@withLock
        if (!med.isActive) {
            alarms.cancel(medicineId)
            return@withLock
        }
        val now = Instant.now()
        val (caught, missed) = DoseTracker.catchUp(med.parsedSchedule, med.tracker, now, zone, settings.missedWindow)
        val fired = DoseTracker.alarmFired(caught, now, settings.snoozeMinutes)
        val updated = med.withTracker(fired.state)
        db.withTransaction {
            log(medicineId, missed)
            db.medicines().update(updated)
        }
        if (missed.isNotEmpty()) notifier.cancelDose(medicineId)
        if (fired.isDue) {
            notifier.showDose(updated, db.profiles().get(med.profileId), fired.state.nextDue!!)
        }
        syncAlarm(updated)
    }

    /**
     * Brings every medicine up to date and re-sets every alarm. Runs at app start, after a reboot,
     * update, clock or time zone change, a restore, and from the daily watchdog.
     */
    suspend fun refreshAll() = lock.withLock {
        val now = Instant.now()
        for (med in db.medicines().getAll()) {
            if (!med.isActive) {
                alarms.cancel(med.id)
                continue
            }
            val schedule = runCatching { med.parsedSchedule }.getOrNull() ?: continue
            val recomputed = DoseTracker.recompute(schedule, med.tracker, zone)
            val (caught, missed) = DoseTracker.catchUp(schedule, recomputed, now, zone, settings.missedWindow)
            val updated = med.withTracker(caught)
            if (missed.isNotEmpty() || updated != med) {
                db.withTransaction {
                    log(med.id, missed)
                    db.medicines().update(updated)
                }
            }
            if (missed.isNotEmpty()) notifier.cancelDose(med.id)
            syncAlarm(updated)
        }
    }

    fun cancelAllAlarms(medicines: List<Medicine>) {
        medicines.forEach {
            alarms.cancel(it.id)
            notifier.cancelDose(it.id)
        }
    }

    private suspend fun log(medicineId: Long, records: List<DoseRecord>) {
        if (records.isEmpty()) return
        db.doseLogs().insertAll(
            records.map { DoseLog(medicineId = medicineId, dueAt = it.due?.toEpochMilli(), status = it.status.name, actionAt = it.at.toEpochMilli()) },
        )
    }

    private fun syncAlarm(med: Medicine) {
        val at = med.tracker.alarmAt
        if (med.isActive && at != null) alarms.set(med.id, at.toEpochMilli()) else alarms.cancel(med.id)
    }
}
