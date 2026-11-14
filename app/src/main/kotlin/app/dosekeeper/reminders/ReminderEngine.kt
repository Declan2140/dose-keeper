package app.dosekeeper.reminders

import androidx.room.withTransaction
import app.dosekeeper.core.DoseRecord
import app.dosekeeper.core.DoseStatus
import app.dosekeeper.core.DoseTracker
import app.dosekeeper.core.Supply
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

    /** Called after anything here may have changed what's due, so the home screen widget can redraw. */
    var onChanged: (() -> Unit)? = null

    /** Runs [block] with nothing else changing doses at the same time, then reports the change. */
    private suspend fun <T> change(block: suspend () -> T): T =
        try {
            lock.withLock { block() }
        } finally {
            onChanged?.invoke()
        }
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** Saves a new or edited medicine and sets its alarm. Returns its id. */
    suspend fun save(medicine: Medicine, scheduleChanged: Boolean): Long = change {
        val now = Instant.now()
        val existing = if (medicine.id != 0L) db.medicines().get(medicine.id) else null
        var med = medicine
        if (existing == null || scheduleChanged) {
            val state = if (existing == null) DoseTracker.start(med.parsedSchedule, now, zone)
            else DoseTracker.scheduleChanged(med.parsedSchedule, existing.tracker, now, zone)
            med = med.withTracker(state)
        }
        // A changed count means the user has just looked at it, so warn again only if it's still low later.
        if (existing != null && (existing.supplyLeft != med.supplyLeft || existing.perDose != med.perDose)) med = med.copy(lowSupplyNotified = false)
        val id = if (existing == null) db.medicines().insert(med.copy(id = 0)) else med.id.also { db.medicines().update(med) }
        syncAlarm(med.copy(id = id))
        checkSupply(med.copy(id = id), notify = false)
        id
    }

    /**
     * Records the medicine's current dose as taken or skipped. [onlyIfDueAt] makes it do nothing
     * unless that is still the current dose: the widget passes the dose it was showing, so a
     * second tap, or a tap on a widget that hasn't caught up yet, can never answer the next dose.
     */
    suspend fun answer(medicineId: Long, status: DoseStatus, onlyIfDueAt: Long? = null) = change {
        if (onlyIfDueAt != null && db.medicines().get(medicineId)?.nextDueAt != onlyIfDueAt) return@change
        adoptZoneChange()
        val med = db.medicines().get(medicineId) ?: return@change
        if (med.nextDueAt == null) return@change
        val now = Instant.now()
        val schedule = med.parsedSchedule
        val (caught, missed) = DoseTracker.catchUp(schedule, med.tracker, now, zone, settings.missedWindow)
        // The dose the user is answering may have just slipped past the missed window. Answering it
        // still counts; only doses before it are logged as missed.
        val answering = if (missed.isNotEmpty()) med.tracker.copy(nextDue = missed.last().due, snoozedUntil = null) else caught
        val (next, record) = DoseTracker.answer(schedule, answering, status, now, zone)
        var updated = med.withTracker(next)
        if (status == DoseStatus.TAKEN) updated = updated.used(1)
        db.withTransaction {
            log(medicineId, missed.dropLast(if (missed.isNotEmpty()) 1 else 0))
            log(medicineId, listOf(record))
            db.medicines().update(updated)
        }
        notifier.cancelDose(medicineId)
        syncAlarm(updated)
        checkSupply(updated)
    }

    /**
     * Unchecks a taken or skipped dose, making it the current dose again. Only the medicine's
     * latest dose can be unchecked, so the doses after it are still worked out in order.
     */
    suspend fun undo(medicineId: Long, dueAt: Long) = change {
        val med = db.medicines().get(medicineId) ?: return@change
        if (!med.isActive) return@change
        val log = db.doseLogs().lastScheduled(medicineId) ?: return@change
        if (log.dueAt != dueAt || log.doseStatus == DoseStatus.MISSED) return@change
        val schedule = runCatching { med.parsedSchedule }.getOrNull() ?: return@change
        var updated = med.withTracker(DoseTracker.undo(schedule, Instant.ofEpochMilli(dueAt), Instant.now(), settings.snoozeMinutes))
        if (log.doseStatus == DoseStatus.TAKEN) updated = updated.used(-1)
        db.withTransaction {
            db.doseLogs().delete(log)
            db.medicines().update(updated)
        }
        notifier.cancelDose(medicineId)
        syncAlarm(updated)
        checkSupply(updated)
    }

    suspend fun snooze(medicineId: Long, minutes: Int = settings.snoozeMinutes) = change {
        adoptZoneChange()
        val med = db.medicines().get(medicineId) ?: return@change
        if (med.nextDueAt == null) return@change
        val updated = med.withTracker(DoseTracker.snooze(med.tracker, Instant.now(), minutes))
        db.medicines().update(updated)
        notifier.cancelDose(medicineId)
        syncAlarm(updated)
    }

    suspend fun logAsNeeded(medicineId: Long) = change {
        val med = db.medicines().get(medicineId) ?: return@change
        val updated = med.used(1)
        db.withTransaction {
            log(medicineId, listOf(DoseTracker.logAsNeeded(Instant.now())))
            if (updated != med) db.medicines().update(updated)
        }
        checkSupply(updated)
    }

    /**
     * Changes a history entry's status, or deletes it when [status] is null, keeping the
     * supply count in step: a dose that becomes taken uses supply, one that stops being taken gives it back.
     */
    suspend fun editLog(entry: DoseLog, status: DoseStatus?) = change {
        val med = db.medicines().get(entry.medicineId)
        val wasTaken = entry.doseStatus == DoseStatus.TAKEN
        val nowTaken = status == DoseStatus.TAKEN
        val change = (if (nowTaken) 1 else 0) - (if (wasTaken) 1 else 0)
        val updated = med?.used(change)
        db.withTransaction {
            if (status == null) db.doseLogs().delete(entry)
            // Marked taken afterwards: the due time is the best guess at when, but never a time
            // that hasn't happened yet, which would put a false time in the doctor report.
            else db.doseLogs().update(entry.copy(status = status.name, actionAt = if (nowTaken) minOf(entry.dueAt ?: entry.actionAt, System.currentTimeMillis()) else entry.actionAt))
            if (updated != null && updated != med) db.medicines().update(updated)
        }
        updated?.let { checkSupply(it) }
    }

    /** Adds a refill of [amount] to the supply and remembers the size for next time. */
    suspend fun refill(medicineId: Long, amount: Double) = change {
        val med = db.medicines().get(medicineId) ?: return@change
        if (amount <= 0) return@change
        val updated = med.copy(supplyLeft = (med.supplyLeft ?: 0.0) + amount, refillSize = amount)
        db.medicines().update(updated)
        checkSupply(updated)
    }

    suspend fun setStatus(medicineId: Long, status: MedStatus) = change {
        val med = db.medicines().get(medicineId) ?: return@change
        if (med.medStatus == MedStatus.ARCHIVED) return@change // archived medicines never come back
        var updated = med.copy(status = status.name)
        if (status == MedStatus.ACTIVE && med.medStatus == MedStatus.PAUSED) {
            updated = updated.withTracker(DoseTracker.resume(med.parsedSchedule, med.tracker, Instant.now(), zone))
        }
        db.medicines().update(updated)
        notifier.cancelDose(medicineId)
        syncAlarm(updated)
        checkSupply(updated, notify = false)
    }

    /** An alarm went off: log anything missed, then ring if a dose is due. */
    suspend fun onAlarm(medicineId: Long) = change {
        if (medicineId == AlarmScheduler.TEST_ID) {
            notifier.showTest()
            return@change
        }
        adoptZoneChange()
        val med = db.medicines().get(medicineId) ?: return@change
        if (!med.isActive) {
            alarms.cancel(medicineId)
            return@change
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
    suspend fun refreshAll() = change { refreshLocked() }

    /**
     * If the phone has changed time zone since doses were last worked out, bring everything
     * across before acting, so nothing is answered against times from the old zone.
     */
    private suspend fun adoptZoneChange() {
        val last = settings.lastZone
        if (last != null && last != zone.id) refreshLocked()
    }

    private suspend fun refreshLocked() {
        val now = Instant.now()
        val zone = zone
        val previousZone = settings.lastZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: zone
        for (med in db.medicines().getAll()) {
            if (!med.isActive) {
                alarms.cancel(med.id)
                continue
            }
            val schedule = runCatching { med.parsedSchedule }.getOrNull() ?: continue
            val recomputed = DoseTracker.zoneChanged(schedule, med.tracker, previousZone, zone)
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
        if (settings.lastZone != zone.id) settings.lastZone = zone.id
    }

    /**
     * The phone was just unlocked: alarms that started ringing while it was locked said only
     * "Medication reminder". Put the medicine's name on them now, without ringing again.
     */
    suspend fun revealRinging() = change {
        if (!settings.hideNamesOnLockScreen || notifier.namesHidden()) return@change
        for (id in notifier.concealedDoseIds()) {
            val med = db.medicines().get(id) ?: continue
            val due = med.nextDueAt ?: continue
            if (med.isActive) notifier.showDose(med, db.profiles().get(med.profileId), Instant.ofEpochMilli(due), quiet = true)
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

    /** Supply after [doses] more doses were taken (negative gives them back). Untracked medicines are unchanged. */
    private fun Medicine.used(doses: Int): Medicine {
        val left = supplyLeft ?: return this
        if (doses == 0) return this
        return copy(supplyLeft = (left - doses * perDose).coerceAtLeast(0.0))
    }

    /**
     * Shows "running low" once when a medicine first drops into the warning range, and re-arms the
     * warning once it's back above it (after a refill). [notify] false only records the state.
     */
    private suspend fun checkSupply(med: Medicine, notify: Boolean = true) {
        val left = med.supplyLeft
        if (left == null || !med.isActive) {
            notifier.cancelLowSupply(med.id)
            return
        }
        val schedule = runCatching { med.parsedSchedule }.getOrNull() ?: return
        val outlook = Supply.outlook(schedule, left, med.perDose, med.nextDueAt?.let(Instant::ofEpochMilli), Instant.now(), zone)
        when {
            // Only a notice that was actually shown counts. Setting a count that's already low (in the
            // editor) leaves it to the next dose to send, instead of quietly using up the one notice.
            outlook.low && !med.lowSupplyNotified && notify -> {
                notifier.showLowSupply(med, outlook, db.profiles().get(med.profileId))
                db.medicines().update(med.copy(lowSupplyNotified = true))
            }
            !outlook.low && med.lowSupplyNotified -> {
                notifier.cancelLowSupply(med.id)
                db.medicines().update(med.copy(lowSupplyNotified = false))
            }
        }
    }

    private fun syncAlarm(med: Medicine) {
        val at = med.tracker.alarmAt
        if (med.isActive && at != null) alarms.set(med.id, at.toEpochMilli()) else alarms.cancel(med.id)
    }
}
