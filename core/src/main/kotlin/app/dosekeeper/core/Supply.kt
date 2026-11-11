package app.dosekeeper.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.floor

/**
 * Refill tracking: how many doses are left and roughly when the supply runs out.
 * Pure functions, so the estimates are covered by plain JVM tests.
 */
object Supply {

    /** Warn when a scheduled medicine has this many days or fewer left, leaving time to order a refill. */
    const val LOW_DAYS = 7L

    /** "As needed" medicines have no schedule to project, so they warn on a dose count instead. */
    const val LOW_DOSES_AS_NEEDED = 5

    /** Stops the projection on very large supplies; anything past this is "plenty". */
    private const val MAX_DOSES = 3000

    /** What's left and when it runs out. [runsOut] and [daysLeft] are null when no estimate applies. */
    data class Outlook(val dosesLeft: Int, val runsOut: LocalDate?, val daysLeft: Long?, val low: Boolean)

    /** Whole doses that [left] still covers. Small rounding errors (0.1 + 0.2) don't cost a dose. */
    fun dosesLeft(left: Double, perDose: Double): Int {
        if (perDose <= 0.0) return 0
        return floor(left / perDose + 1e-6).toInt().coerceAtLeast(0)
    }

    /**
     * Projects the schedule forward from [nextDue] (the next dose the user will take) and finds the
     * first dose there won't be enough for. Its date is the run-out date. A schedule that ends before
     * then never runs out, so [Outlook.runsOut] is null.
     */
    fun outlook(
        schedule: Schedule,
        left: Double,
        perDose: Double,
        nextDue: Instant?,
        now: Instant,
        zone: ZoneId,
    ): Outlook {
        val doses = dosesLeft(left, perDose)
        val today = now.atZone(zone).toLocalDate()
        if (schedule is Schedule.AsNeeded) return Outlook(doses, null, null, low = doses <= LOW_DOSES_AS_NEEDED)
        if (doses == 0) return Outlook(0, today, 0, low = true)
        if (doses > MAX_DOSES) return Outlook(doses, null, null, low = false)

        val short = nthDose(schedule, doses + 1, nextDue, now, zone) ?: return Outlook(doses, null, null, low = false)
        val runsOut = short.atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(today, runsOut).coerceAtLeast(0)
        return Outlook(doses, runsOut, days, low = days <= LOW_DAYS)
    }

    /** The [n]th upcoming dose, counting [nextDue] as the first. Null if the schedule ends first. */
    internal fun nthDose(schedule: Schedule, n: Int, nextDue: Instant?, now: Instant, zone: ZoneId): Instant? {
        require(n >= 1)
        return when (schedule) {
            is Schedule.FixedTimes -> {
                var due = nextDue ?: DoseCalculator.nextFixed(schedule, now, zone) ?: return null
                repeat(n - 1) { due = DoseCalculator.nextFixed(schedule, due, zone) ?: return null }
                due
            }
            is Schedule.Interval -> {
                // Assumes each dose is taken on time; real intervals count from when it was taken.
                var due = nextDue ?: DoseCalculator.nextInterval(schedule, null, zone).let { if (it.isBefore(now)) now else it }
                repeat(n - 1) { due = DoseCalculator.nextInterval(schedule, due, zone) }
                due
            }
            is Schedule.AsNeeded -> null
        }
    }
}
