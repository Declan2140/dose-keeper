package app.dosekeeper.ui

import app.dosekeeper.core.DoseCalculator
import app.dosekeeper.core.DoseStatus
import app.dosekeeper.core.Schedule
import app.dosekeeper.data.DoseLog
import app.dosekeeper.data.Medicine
import app.dosekeeper.data.Profile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One row on the Today screen. */
data class TodayDose(
    val medicine: Medicine,
    val profile: Profile?,
    val due: Instant,
    /** Null while still open. */
    val status: DoseStatus?,
    /** Only the medicine's current dose has Taken, Skip and Snooze. Later doses wait their turn. */
    val actionable: Boolean,
    val overdue: Boolean,
    val snoozedUntil: Instant?,
    /** When it was taken or skipped. */
    val at: Instant? = null,
)

data class AsNeededRow(val medicine: Medicine, val profile: Profile?, val lastTaken: Instant?, val notBefore: Instant?)

data class TodayModel(val doses: List<TodayDose>, val asNeeded: List<AsNeededRow>) {
    /** Medicines with a dose still to take today, soonest first. One bottle each on the counter. */
    val remaining: List<Medicine> =
        doses.filter { it.status == null }.sortedBy { it.due }.map { it.medicine }.distinctBy { it.id }

    val takenCount: Int = doses.count { it.status == DoseStatus.TAKEN }
    val scheduledCount: Int = doses.size
}

/**
 * Builds Today from stored state. Overdue doses go first, then everything in time order.
 * [logs] should cover at least today; older entries are used for "as needed" last-taken times.
 */
fun buildToday(
    medicines: List<Medicine>,
    profiles: List<Profile>,
    logs: List<DoseLog>,
    profileFilter: Long?,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): TodayModel {
    val byId = profiles.associateBy { it.id }
    val today = now.atZone(zone).toLocalDate()
    val dayStart = today.atStartOfDay(zone).toInstant()
    val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant()
    val logsByMed = logs.groupBy { it.medicineId }
    val doses = mutableListOf<TodayDose>()
    val asNeeded = mutableListOf<AsNeededRow>()

    for (med in medicines) {
        if (profileFilter != null && med.profileId != profileFilter) continue
        val profile = byId[med.profileId]
        val medLogs = logsByMed[med.id].orEmpty()
        val schedule = runCatching { med.parsedSchedule }.getOrNull() ?: continue

        if (schedule is Schedule.AsNeeded) {
            if (!med.isActive) continue
            val last = medLogs.filter { it.doseStatus == DoseStatus.TAKEN }.maxOfOrNull { it.actionAt }?.let(Instant::ofEpochMilli)
            asNeeded += AsNeededRow(med, profile, last, DoseCalculator.notBefore(schedule, last)?.takeIf { it.isAfter(now) })
            continue
        }

        // Everything already answered or missed today, active or not.
        val shown = mutableSetOf<Long>()
        for (log in medLogs) {
            val due = log.dueAt ?: continue
            if (due < dayStart.toEpochMilli() || due >= dayEnd.toEpochMilli()) continue
            shown += due
            doses += TodayDose(med, profile, Instant.ofEpochMilli(due), log.doseStatus, actionable = false, overdue = false, snoozedUntil = null, at = Instant.ofEpochMilli(log.actionAt))
        }
        if (!med.isActive) continue

        // The current dose, which may be left over from yesterday evening.
        val next = med.nextDueAt
        if (next != null && next < dayEnd.toEpochMilli() && next !in shown) {
            shown += next
            val due = Instant.ofEpochMilli(next)
            doses += TodayDose(med, profile, due, null, actionable = true, overdue = due.isBefore(now), snoozedUntil = med.snoozedUntil?.let(Instant::ofEpochMilli))
        }

        // Later fixed doses today, shown so the day can be seen at a glance.
        if (schedule is Schedule.FixedTimes && next != null) {
            for (due in DoseCalculator.fixedDosesOn(schedule, today, zone)) {
                val ms = due.toEpochMilli()
                if (ms in shown || ms <= next) continue
                doses += TodayDose(med, profile, due, null, actionable = false, overdue = false, snoozedUntil = null)
            }
        }
    }

    doses.sortWith(compareByDescending<TodayDose> { it.overdue }.thenBy { it.due }.thenBy { it.medicine.name.lowercase() })
    asNeeded.sortBy { it.medicine.name.lowercase() }
    return TodayModel(doses, asNeeded)
}
