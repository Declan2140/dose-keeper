package app.dosekeeper.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** What happened to one scheduled dose. */
enum class DoseStatus { TAKEN, SKIPPED, MISSED }

/** One entry for the dose history. [due] is null for "as needed" doses. */
data class DoseRecord(val due: Instant?, val status: DoseStatus, val at: Instant)

/**
 * Where a medicine stands between doses.
 *
 * [anchor] is what the next dose is counted from: for fixed schedules the due time of the last
 * dose that was dealt with, for intervals the moment the last dose was taken (or, if it was
 * skipped or missed, when it was due). [nextDue] is the one dose that has an alarm set.
 * [snoozedUntil] is set while that dose is being re-alerted.
 */
data class TrackerState(
    val anchor: Instant?,
    val nextDue: Instant?,
    val snoozedUntil: Instant? = null,
) {
    /** When the alarm for this medicine should go off next, or null for none. */
    val alarmAt: Instant? get() = snoozedUntil ?: nextDue
}

/**
 * The state machine behind every reminder. Only the next dose of each medicine is ever
 * scheduled: when it is answered or missed, the one after is worked out from here.
 */
object DoseTracker {

    /** Missed doses logged in one catch-up, so a phone left off for months can't stall. */
    private const val MAX_CATCH_UP = 1000

    /** A new (or resumed) medicine. Fixed doses already past today are not reminded. */
    fun start(schedule: Schedule, now: Instant, zone: ZoneId): TrackerState = when (schedule) {
        is Schedule.FixedTimes -> TrackerState(anchor = now, nextDue = DoseCalculator.nextFixed(schedule, now, zone))
        is Schedule.Interval -> TrackerState(anchor = null, nextDue = DoseCalculator.nextInterval(schedule, null, zone))
        is Schedule.AsNeeded -> TrackerState(anchor = null, nextDue = null)
    }

    /**
     * Works the next dose out again from the anchor. Called after a reboot, a clock or time zone
     * change, or a restore, so fixed doses land on the current zone's local time.
     */
    fun recompute(schedule: Schedule, state: TrackerState, zone: ZoneId): TrackerState {
        val next = when (schedule) {
            is Schedule.FixedTimes -> state.anchor?.let { DoseCalculator.nextFixed(schedule, it, zone) }
            is Schedule.Interval -> DoseCalculator.nextInterval(schedule, state.anchor, zone)
            is Schedule.AsNeeded -> null
        }
        // A snooze only makes sense for the dose it was set on.
        val snooze = state.snoozedUntil.takeIf { next == state.nextDue }
        return state.copy(nextDue = next, snoozedUntil = snooze)
    }

    /** The schedule was edited. Interval medicines keep counting from the last dose taken. */
    fun scheduleChanged(newSchedule: Schedule, state: TrackerState, now: Instant, zone: ZoneId): TrackerState =
        if (newSchedule is Schedule.Interval && state.anchor != null) recompute(newSchedule, state.copy(snoozedUntil = null), zone)
        else start(newSchedule, now, zone)

    /** A paused medicine is turned back on. Nothing during the pause counts as missed. */
    fun resume(schedule: Schedule, state: TrackerState, now: Instant, zone: ZoneId): TrackerState {
        if (schedule !is Schedule.Interval || state.anchor == null) return start(schedule, now, zone)
        val next = recompute(schedule, state.copy(snoozedUntil = null), zone)
        return if (next.nextDue != null && next.nextDue.isBefore(now)) recompute(schedule, TrackerState(now.minus(schedule.every), null), zone)
        else next
    }

    /** The user tapped Taken or Skip for the current dose. */
    fun answer(
        schedule: Schedule,
        state: TrackerState,
        status: DoseStatus,
        now: Instant,
        zone: ZoneId,
    ): Pair<TrackerState, DoseRecord> {
        require(status != DoseStatus.MISSED) { "Missed is decided by the clock, not the user" }
        val due = requireNotNull(state.nextDue) { "No dose is due" }
        val anchor = if (schedule is Schedule.Interval && status == DoseStatus.TAKEN) now else due
        val next = recompute(schedule, TrackerState(anchor, null), zone)
        return next to DoseRecord(due, status, now)
    }

    /** The user logged an "as needed" dose. */
    fun logAsNeeded(now: Instant): DoseRecord = DoseRecord(null, DoseStatus.TAKEN, now)

    /**
     * Marks every dose left unanswered past [window] as missed and moves on to the next one.
     * Runs whenever an alarm fires, the app opens, and from the daily watchdog.
     */
    fun catchUp(
        schedule: Schedule,
        state: TrackerState,
        now: Instant,
        zone: ZoneId,
        window: Duration = DoseCalculator.DEFAULT_MISSED_WINDOW,
    ): Pair<TrackerState, List<DoseRecord>> {
        var current = state
        val missed = mutableListOf<DoseRecord>()
        while (missed.size < MAX_CATCH_UP) {
            val due = current.nextDue ?: break
            if (!DoseCalculator.isMissed(due, now, window)) break
            missed += DoseRecord(due, DoseStatus.MISSED, due.plus(window))
            current = recompute(schedule, TrackerState(due, null), zone)
            if (current.nextDue != null && !current.nextDue!!.isAfter(due)) break // guard against a stuck schedule
        }
        return current to missed
    }

    /** Snooze the current dose. Also used to keep re-alerting when an alarm goes unanswered. */
    fun snooze(state: TrackerState, now: Instant, minutes: Int): TrackerState =
        state.copy(snoozedUntil = DoseCalculator.snoozeUntil(now, minutes))

    /**
     * An alarm went off. Returns the state with a follow-up alert set, so the reminder keeps
     * coming back until it is answered or counts as missed. [isDue] is false when the alarm
     * was stale (e.g. the dose was already answered), in which case nothing should be shown.
     */
    fun alarmFired(state: TrackerState, now: Instant, repeatMinutes: Int): Fired {
        val due = state.nextDue ?: return Fired(state, isDue = false)
        if (now.isBefore(due)) return Fired(state, isDue = false)
        return Fired(snooze(state, now, repeatMinutes), isDue = true)
    }

    data class Fired(val state: TrackerState, val isDue: Boolean)
}
