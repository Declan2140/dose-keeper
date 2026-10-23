package app.dosekeeper.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DoseTrackerTest {

    private val zone = ZoneId.of("America/Chicago")
    private fun at(text: String): Instant = LocalDateTime.parse(text).atZone(zone).toInstant()
    private fun local(i: Instant?) = i!!.atZone(zone).toLocalDateTime().toString()

    private val twiceDaily = Schedule.FixedTimes(listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), DayRule.Daily, LocalDate.parse("2026-01-01"))
    private val sixHourly = Schedule.Interval(Duration.ofHours(6), at("2026-03-02T12:00"))

    @Test fun `new fixed medicine starts at the next time today`() {
        val s = DoseTracker.start(twiceDaily, at("2026-03-02T09:00"), zone)
        assertEquals("2026-03-02T20:00", local(s.nextDue))
    }

    @Test fun `taking a fixed dose late does not move the next one`() {
        var s = DoseTracker.start(twiceDaily, at("2026-03-02T07:00"), zone)
        val (next, record) = DoseTracker.answer(twiceDaily, s, DoseStatus.TAKEN, at("2026-03-02T09:30"), zone)
        assertEquals("2026-03-02T08:00", local(record.due))
        assertEquals("2026-03-02T20:00", local(next.nextDue))
    }

    @Test fun `taking an interval dose late moves the next one`() {
        val s = DoseTracker.start(sixHourly, at("2026-03-02T11:00"), zone)
        assertEquals("2026-03-02T12:00", local(s.nextDue))
        val (next, _) = DoseTracker.answer(sixHourly, s, DoseStatus.TAKEN, at("2026-03-02T12:45"), zone)
        assertEquals("2026-03-02T18:45", local(next.nextDue))
    }

    @Test fun `skipping an interval dose counts from when it was due`() {
        val s = DoseTracker.start(sixHourly, at("2026-03-02T11:00"), zone)
        val (next, record) = DoseTracker.answer(sixHourly, s, DoseStatus.SKIPPED, at("2026-03-02T12:45"), zone)
        assertEquals(DoseStatus.SKIPPED, record.status)
        assertEquals("2026-03-02T18:00", local(next.nextDue))
    }

    @Test fun `unanswered doses become missed after two hours`() {
        val s = DoseTracker.start(twiceDaily, at("2026-03-02T07:00"), zone)
        val (notYet, none) = DoseTracker.catchUp(twiceDaily, s, at("2026-03-02T09:59"), zone)
        assertTrue(none.isEmpty())
        assertEquals(s, notYet)

        // Phone was off until the next evening: 08:00 and 20:00 on the 2nd, 08:00 on the 3rd are missed.
        val (after, missed) = DoseTracker.catchUp(twiceDaily, s, at("2026-03-03T19:00"), zone)
        assertEquals(listOf("2026-03-02T08:00", "2026-03-02T20:00", "2026-03-03T08:00"), missed.map { local(it.due) })
        assertTrue(missed.all { it.status == DoseStatus.MISSED })
        assertEquals("2026-03-03T20:00", local(after.nextDue))
    }

    @Test fun `unchecking a fixed dose brings it back and survives a recompute`() {
        val s = DoseTracker.start(twiceDaily, at("2026-03-02T07:00"), zone)
        val (_, record) = DoseTracker.answer(twiceDaily, s, DoseStatus.TAKEN, at("2026-03-02T08:05"), zone)
        val back = DoseTracker.undo(twiceDaily, record.due!!, at("2026-03-02T08:10"), 10)
        assertEquals("2026-03-02T08:00", local(back.nextDue))
        assertEquals("2026-03-02T08:20", local(back.snoozedUntil))
        assertEquals("2026-03-02T08:00", local(DoseTracker.recompute(twiceDaily, back, zone).nextDue))
    }

    @Test fun `unchecking an interval dose before it is due sets no snooze`() {
        val s = DoseTracker.start(sixHourly, at("2026-03-02T11:00"), zone)
        val (_, record) = DoseTracker.answer(sixHourly, s, DoseStatus.TAKEN, at("2026-03-02T11:30"), zone)
        val back = DoseTracker.undo(sixHourly, record.due!!, at("2026-03-02T11:35"), 10)
        assertEquals("2026-03-02T12:00", local(back.nextDue))
        assertNull(back.snoozedUntil)
        assertEquals("2026-03-02T12:00", local(DoseTracker.recompute(sixHourly, back, zone).nextDue))
    }

    @Test fun `alarm keeps coming back until answered`() {
        val s = DoseTracker.start(twiceDaily, at("2026-03-02T07:00"), zone)
        val fired = DoseTracker.alarmFired(s, at("2026-03-02T08:00"), 10)
        assertTrue(fired.isDue)
        assertEquals("2026-03-02T08:10", local(fired.state.alarmAt))
        val again = DoseTracker.alarmFired(fired.state, at("2026-03-02T08:10"), 10)
        assertEquals("2026-03-02T08:20", local(again.state.alarmAt))
        // answering clears the follow-up
        val (answered, _) = DoseTracker.answer(twiceDaily, again.state, DoseStatus.TAKEN, at("2026-03-02T08:12"), zone)
        assertNull(answered.snoozedUntil)
        assertEquals("2026-03-02T20:00", local(answered.alarmAt))
    }

    @Test fun `an early or stale alarm shows nothing`() {
        val s = DoseTracker.start(twiceDaily, at("2026-03-02T07:00"), zone)
        assertFalse(DoseTracker.alarmFired(s, at("2026-03-02T07:59"), 10).isDue)
    }

    @Test fun `time zone change recomputes fixed doses in local time`() {
        val s = DoseTracker.start(twiceDaily, at("2026-03-02T07:00"), zone)
        val (answered, _) = DoseTracker.answer(twiceDaily, s, DoseStatus.TAKEN, at("2026-03-02T08:00"), zone)
        val tokyo = ZoneId.of("Asia/Tokyo")
        val moved = DoseTracker.recompute(twiceDaily, answered, tokyo)
        assertEquals(LocalTime.of(8, 0), moved.nextDue!!.atZone(tokyo).toLocalTime())
    }

    @Test fun `recompute keeps a snooze on the same dose`() {
        val s = DoseTracker.snooze(DoseTracker.start(twiceDaily, at("2026-03-02T07:00"), zone), at("2026-03-02T08:00"), 15)
        assertEquals(s, DoseTracker.recompute(twiceDaily, s, zone))
    }

    @Test fun `resuming an interval medicine does not flood missed doses`() {
        val s = DoseTracker.start(sixHourly, at("2026-03-02T11:00"), zone)
        val (taken, _) = DoseTracker.answer(sixHourly, s, DoseStatus.TAKEN, at("2026-03-02T12:00"), zone)
        val resumed = DoseTracker.resume(sixHourly, taken, at("2026-03-10T09:00"), zone)
        assertEquals("2026-03-10T09:00", local(resumed.nextDue))
        assertTrue(DoseTracker.catchUp(sixHourly, resumed, at("2026-03-10T09:30"), zone).second.isEmpty())
    }

    @Test fun `editing an interval keeps counting from the last dose`() {
        val s = DoseTracker.start(sixHourly, at("2026-03-02T11:00"), zone)
        val (taken, _) = DoseTracker.answer(sixHourly, s, DoseStatus.TAKEN, at("2026-03-02T12:00"), zone)
        val eightHourly = sixHourly.copy(every = Duration.ofHours(8))
        assertEquals("2026-03-02T20:00", local(DoseTracker.scheduleChanged(eightHourly, taken, at("2026-03-02T13:00"), zone).nextDue))
    }

    @Test fun `as needed never has an alarm`() {
        assertNull(DoseTracker.start(Schedule.AsNeeded(), at("2026-03-02T11:00"), zone).alarmAt)
    }

    @Test fun `fixed schedule that ended stops`() {
        val ending = twiceDaily.copy(endDate = LocalDate.parse("2026-03-02"))
        val s = DoseTracker.start(ending, at("2026-03-02T19:00"), zone)
        val (after, _) = DoseTracker.answer(ending, s, DoseStatus.TAKEN, at("2026-03-02T20:00"), zone)
        assertNull(after.nextDue)
    }
}
