package app.dosekeeper.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Works out when doses are due. Pure functions with no Android dependencies, so every rule
 * from the spec is covered by plain JVM tests.
 */
object DoseCalculator {

    /** How far ahead to search for the next fixed dose before giving up. Covers sparse monthly rules. */
    private const val SEARCH_DAYS = 800L

    /**
     * The next fixed-time dose strictly after [after], in [zone]. Null when the schedule has ended.
     *
     * A time that doesn't exist on a spring-forward day (02:30) is moved forward by the length of
     * the gap (03:30). A time that happens twice on a fall-back day fires at the first occurrence.
     */
    fun nextFixed(schedule: Schedule.FixedTimes, after: Instant, zone: ZoneId): Instant? {
        val times = schedule.times.distinct().sorted()
        var date = maxOf(schedule.startDate, after.atZone(zone).toLocalDate().minusDays(1))
        val last = after.atZone(zone).toLocalDate().plusDays(SEARCH_DAYS)
        while (!date.isAfter(last)) {
            if (schedule.endDate != null && date.isAfter(schedule.endDate)) return null
            if (matches(schedule, date)) {
                for (time in times) {
                    val due = ZonedDateTime.ofLocal(LocalDateTime.of(date, time), zone, null).toInstant()
                    if (due.isAfter(after)) return due
                }
            }
            date = date.plusDays(1)
        }
        return null
    }

    /** All fixed-time doses that fall on [date] in [zone], in order. Used by the Today screen. */
    fun fixedDosesOn(schedule: Schedule.FixedTimes, date: LocalDate, zone: ZoneId): List<Instant> {
        if (date.isBefore(schedule.startDate)) return emptyList()
        if (schedule.endDate != null && date.isAfter(schedule.endDate)) return emptyList()
        if (!matches(schedule, date)) return emptyList()
        return schedule.times.distinct().sorted()
            .map { ZonedDateTime.ofLocal(LocalDateTime.of(date, it), zone, null).toInstant() }
    }

    /**
     * When the next interval dose is due. [anchor] is when the previous dose was taken, or for a
     * skipped or missed dose, when that dose was due. Null anchor means no dose yet, so the first
     * dose time applies. The result may be in the past, which means the dose is overdue.
     */
    fun nextInterval(schedule: Schedule.Interval, anchor: Instant?, zone: ZoneId): Instant {
        val due = anchor?.plus(schedule.every) ?: schedule.firstDose
        return pushOutOfQuietHours(due, schedule.quietHours, zone)
    }

    /** For "as needed" medicines, the earliest time the next dose is allowed, or null if any time. */
    fun notBefore(schedule: Schedule.AsNeeded, lastTaken: Instant?): Instant? {
        val gap = schedule.minGap ?: return null
        return lastTaken?.plus(gap)
    }

    /** A dose not answered within [window] of being due counts as missed. */
    fun isMissed(due: Instant, now: Instant, window: Duration = DEFAULT_MISSED_WINDOW): Boolean =
        !now.isBefore(due.plus(window))

    fun snoozeUntil(now: Instant, minutes: Int): Instant {
        require(minutes in SNOOZE_CHOICES) { "Snooze must be one of $SNOOZE_CHOICES" }
        return now.plus(minutes.toLong(), ChronoUnit.MINUTES)
    }

    val DEFAULT_MISSED_WINDOW: Duration = Duration.ofHours(2)
    val SNOOZE_CHOICES = listOf(5, 10, 15)

    internal fun pushOutOfQuietHours(due: Instant, quiet: QuietHours?, zone: ZoneId): Instant {
        if (quiet == null) return due
        val local = due.atZone(zone)
        if (!quiet.contains(local.toLocalTime())) return due
        // Quiet hours end later today, or tomorrow if they started before midnight.
        var endDate = local.toLocalDate()
        if (quiet.start > quiet.end && local.toLocalTime() >= quiet.start) endDate = endDate.plusDays(1)
        return ZonedDateTime.ofLocal(LocalDateTime.of(endDate, quiet.end), zone, null).toInstant()
    }

    private fun matches(schedule: Schedule.FixedTimes, date: LocalDate): Boolean =
        when (val rule = schedule.days) {
            DayRule.Daily -> true
            is DayRule.DaysOfWeek -> date.dayOfWeek in rule.days
            is DayRule.EveryNDays ->
                ChronoUnit.DAYS.between(schedule.startDate, date).let { it >= 0 && it % rule.n == 0L }
            is DayRule.DayOfMonth -> date.dayOfMonth == minOf(rule.day, date.lengthOfMonth())
        }
}
