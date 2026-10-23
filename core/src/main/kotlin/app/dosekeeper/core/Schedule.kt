package app.dosekeeper.core

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** How a medicine repeats. Fixed schedules are local clock times; intervals are exact moments. */
sealed interface Schedule {

    /**
     * Doses at set local clock times on the days [days] allows, from [startDate] to [endDate] inclusive.
     * Stored as clock times so daylight saving and time zone changes never move a dose.
     */
    data class FixedTimes(
        val times: List<LocalTime>,
        val days: DayRule = DayRule.Daily,
        val startDate: LocalDate,
        val endDate: LocalDate? = null,
    ) : Schedule {
        init {
            require(times.isNotEmpty()) { "A fixed schedule needs at least one time" }
            require(endDate == null || !endDate.isBefore(startDate)) { "End date is before start date" }
        }
    }

    /**
     * The next dose is due [every] after the previous dose was actually taken.
     * [firstDose] is when the very first dose is due. Doses falling inside [quietHours] wait until it ends.
     */
    data class Interval(
        val every: Duration,
        val firstDose: Instant,
        val quietHours: QuietHours? = null,
    ) : Schedule {
        init {
            require(every >= Duration.ofMinutes(15)) { "Interval must be at least 15 minutes" }
        }
    }

    /** No reminders. The user logs doses; [minGap] is the shortest safe time between two doses. */
    data class AsNeeded(val minGap: Duration? = null) : Schedule
}

sealed interface DayRule {
    data object Daily : DayRule

    data class DaysOfWeek(val days: Set<DayOfWeek>) : DayRule {
        init {
            require(days.isNotEmpty()) { "Pick at least one weekday" }
        }
    }

    /** Every [n] days counting from the schedule's start date. */
    data class EveryNDays(val n: Int) : DayRule {
        init {
            require(n >= 1) { "n must be at least 1" }
        }
    }

    /** The same day each month. Days past the end of a short month fall on its last day. */
    data class DayOfMonth(val day: Int) : DayRule {
        init {
            require(day in 1..31) { "Day of month must be 1 to 31" }
        }
    }
}

/** A nightly pause, e.g. 22:00 to 07:00. May cross midnight. */
data class QuietHours(val start: LocalTime, val end: LocalTime) {
    init {
        require(start != end) { "Quiet hours need a start and end that differ" }
    }

    fun contains(time: LocalTime): Boolean =
        if (start < end) time >= start && time < end
        else time >= start || time < end
}
