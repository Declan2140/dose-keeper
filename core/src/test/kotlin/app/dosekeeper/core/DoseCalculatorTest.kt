package app.dosekeeper.core

import java.time.DayOfWeek
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

class DoseCalculatorTest {

    private val newYork = ZoneId.of("America/New_York")
    private val london = ZoneId.of("Europe/London")

    private fun at(zone: ZoneId, text: String): Instant = LocalDateTime.parse(text).atZone(zone).toInstant()
    private fun local(zone: ZoneId, instant: Instant?): String = instant!!.atZone(zone).toLocalDateTime().toString()

    private fun fixed(vararg times: String, days: DayRule = DayRule.Daily, start: String = "2026-01-01", end: String? = null) =
        Schedule.FixedTimes(times.map(LocalTime::parse), days, LocalDate.parse(start), end?.let(LocalDate::parse))

    // Fixed times

    @Test fun `next fixed dose is later the same day`() {
        val s = fixed("08:00", "20:00")
        assertEquals("2026-03-02T20:00", local(newYork, DoseCalculator.nextFixed(s, at(newYork, "2026-03-02T08:00"), newYork)))
    }

    @Test fun `next fixed dose rolls to tomorrow`() {
        val s = fixed("20:00", "08:00")
        assertEquals("2026-03-03T08:00", local(newYork, DoseCalculator.nextFixed(s, at(newYork, "2026-03-02T21:00"), newYork)))
    }

    @Test fun `weekdays only skips the weekend`() {
        val s = fixed("08:00", days = DayRule.DaysOfWeek(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)))
        // Friday 2026-03-06 after the dose -> Monday 2026-03-09
        assertEquals("2026-03-09T08:00", local(newYork, DoseCalculator.nextFixed(s, at(newYork, "2026-03-06T09:00"), newYork)))
    }

    @Test fun `every third day counts from the start date`() {
        val s = fixed("09:00", days = DayRule.EveryNDays(3), start = "2026-03-01")
        assertEquals("2026-03-04T09:00", local(newYork, DoseCalculator.nextFixed(s, at(newYork, "2026-03-01T10:00"), newYork)))
        assertEquals("2026-03-07T09:00", local(newYork, DoseCalculator.nextFixed(s, at(newYork, "2026-03-05T10:00"), newYork)))
    }

    @Test fun `monthly on the 1st for the dog`() {
        val s = fixed("18:00", days = DayRule.DayOfMonth(1))
        assertEquals("2026-04-01T18:00", local(newYork, DoseCalculator.nextFixed(s, at(newYork, "2026-03-01T19:00"), newYork)))
    }

    @Test fun `monthly on the 31st falls on the last day of short months`() {
        val s = fixed("09:00", days = DayRule.DayOfMonth(31))
        assertEquals("2026-02-28T09:00", local(newYork, DoseCalculator.nextFixed(s, at(newYork, "2026-02-01T00:00"), newYork)))
        assertEquals("2026-04-30T09:00", local(newYork, DoseCalculator.nextFixed(s, at(newYork, "2026-04-01T00:00"), newYork)))
    }

    @Test fun `weekly on one weekday`() {
        val s = fixed("07:30", days = DayRule.DaysOfWeek(setOf(DayOfWeek.SUNDAY)))
        assertEquals("2026-03-08T07:30", local(newYork, DoseCalculator.nextFixed(s, at(newYork, "2026-03-02T00:00"), newYork)))
    }

    @Test fun `nothing before the start date`() {
        val s = fixed("08:00", start = "2026-05-10")
        assertEquals("2026-05-10T08:00", local(newYork, DoseCalculator.nextFixed(s, at(newYork, "2026-03-02T09:00"), newYork)))
    }

    @Test fun `no doses after the end date`() {
        val s = fixed("08:00", end = "2026-03-05")
        assertEquals("2026-03-05T08:00", local(newYork, DoseCalculator.nextFixed(s, at(newYork, "2026-03-04T09:00"), newYork)))
        assertNull(DoseCalculator.nextFixed(s, at(newYork, "2026-03-05T09:00"), newYork))
    }

    // Daylight saving (rule 1: a clock change never moves a fixed dose)

    @Test fun `spring forward keeps 8am at 8am local`() {
        // US clocks jump 02:00 -> 03:00 on 2026-03-08.
        val s = fixed("08:00")
        val due = DoseCalculator.nextFixed(s, at(newYork, "2026-03-07T09:00"), newYork)
        assertEquals("2026-03-08T08:00", local(newYork, due))
        // and the gap between the two 8am doses is only 23 hours
        assertEquals(Duration.ofHours(23), Duration.between(at(newYork, "2026-03-07T08:00"), due))
    }

    @Test fun `fall back keeps 8am at 8am local`() {
        // US clocks fall back 02:00 -> 01:00 on 2026-11-01.
        val s = fixed("08:00")
        val due = DoseCalculator.nextFixed(s, at(newYork, "2026-10-31T09:00"), newYork)
        assertEquals("2026-11-01T08:00", local(newYork, due))
        assertEquals(Duration.ofHours(25), Duration.between(at(newYork, "2026-10-31T08:00"), due))
    }

    @Test fun `dose inside the spring gap moves forward by the gap`() {
        val s = fixed("02:30")
        assertEquals("2026-03-08T03:30", local(newYork, DoseCalculator.nextFixed(s, at(newYork, "2026-03-07T03:00"), newYork)))
    }

    @Test fun `dose in the repeated fall hour fires once`() {
        val s = fixed("01:30")
        val first = DoseCalculator.nextFixed(s, at(newYork, "2026-10-31T02:00"), newYork)!!
        assertEquals("2026-11-01T01:30", local(newYork, first))
        // After the first 01:30 the next one is the following night, not an hour later.
        assertEquals("2026-11-02T01:30", local(newYork, DoseCalculator.nextFixed(s, first, newYork)))
    }

    @Test fun `UK clock changes behave the same`() {
        val s = fixed("08:00")
        assertEquals("2026-03-29T08:00", local(london, DoseCalculator.nextFixed(s, at(london, "2026-03-28T09:00"), london)))
        assertEquals("2026-10-25T08:00", local(london, DoseCalculator.nextFixed(s, at(london, "2026-10-24T09:00"), london)))
    }

    // Time zones (rule 3: fixed doses follow the new local time)

    @Test fun `fixed doses follow the new time zone`() {
        val s = fixed("08:00")
        val now = at(newYork, "2026-06-01T09:00")
        val tokyo = ZoneId.of("Asia/Tokyo")
        assertEquals("2026-06-02T08:00", local(tokyo, DoseCalculator.nextFixed(s, now, tokyo)))
    }

    @Test fun `doses on a day`() {
        val s = fixed("20:00", "08:00", days = DayRule.DaysOfWeek(setOf(DayOfWeek.MONDAY)))
        val doses = DoseCalculator.fixedDosesOn(s, LocalDate.parse("2026-03-02"), newYork).map { local(newYork, it) }
        assertEquals(listOf("2026-03-02T08:00", "2026-03-02T20:00"), doses)
        assertTrue(DoseCalculator.fixedDosesOn(s, LocalDate.parse("2026-03-03"), newYork).isEmpty())
    }

    // Intervals (rules 2 and 4)

    private fun interval(hours: Long, first: String, quiet: QuietHours? = null) =
        Schedule.Interval(Duration.ofHours(hours), at(newYork, first), quiet)

    @Test fun `first interval dose is the chosen start`() {
        val s = interval(6, "2026-03-02T12:00")
        assertEquals("2026-03-02T12:00", local(newYork, DoseCalculator.nextInterval(s, null, newYork)))
    }

    @Test fun `interval counts from when the dose was actually taken`() {
        val s = interval(6, "2026-03-02T12:00")
        val takenLate = at(newYork, "2026-03-02T13:20")
        assertEquals("2026-03-02T19:20", local(newYork, DoseCalculator.nextInterval(s, takenLate, newYork)))
    }

    @Test fun `six hours across spring forward is six real hours`() {
        val s = interval(6, "2026-03-07T23:00")
        val taken = at(newYork, "2026-03-07T23:00")
        val next = DoseCalculator.nextInterval(s, taken, newYork)
        assertEquals(Duration.ofHours(6), Duration.between(taken, next))
        assertEquals("2026-03-08T06:00", local(newYork, next)) // clock reads 06:00, not 05:00
    }

    @Test fun `six hours across fall back is six real hours`() {
        val s = interval(6, "2026-10-31T23:00")
        val taken = at(newYork, "2026-10-31T23:00")
        val next = DoseCalculator.nextInterval(s, taken, newYork)
        assertEquals(Duration.ofHours(6), Duration.between(taken, next))
        assertEquals("2026-11-01T04:00", local(newYork, next))
    }

    @Test fun `interval waits for quiet hours to end`() {
        val s = interval(6, "2026-03-02T12:00", QuietHours(LocalTime.of(22, 0), LocalTime.of(7, 0)))
        // taken 18:00 -> due 00:00, inside quiet hours -> 07:00 next morning
        assertEquals("2026-03-03T07:00", local(newYork, DoseCalculator.nextInterval(s, at(newYork, "2026-03-02T18:00"), newYork)))
        // taken 17:00 -> due 23:00, before midnight -> 07:00 next morning
        assertEquals("2026-03-03T07:00", local(newYork, DoseCalculator.nextInterval(s, at(newYork, "2026-03-02T17:00"), newYork)))
        // taken 10:00 -> due 16:00, outside quiet hours
        assertEquals("2026-03-02T16:00", local(newYork, DoseCalculator.nextInterval(s, at(newYork, "2026-03-02T10:00"), newYork)))
    }

    @Test fun `daytime quiet hours`() {
        val q = QuietHours(LocalTime.of(13, 0), LocalTime.of(15, 0))
        val s = interval(4, "2026-03-02T08:00", q)
        assertEquals("2026-03-02T15:00", local(newYork, DoseCalculator.nextInterval(s, at(newYork, "2026-03-02T10:00"), newYork)))
    }

    // As needed

    @Test fun `as needed shows the earliest next dose`() {
        val s = Schedule.AsNeeded(Duration.ofHours(4))
        assertEquals("2026-03-02T14:30", local(newYork, DoseCalculator.notBefore(s, at(newYork, "2026-03-02T10:30"))))
        assertNull(DoseCalculator.notBefore(s, null))
        assertNull(DoseCalculator.notBefore(Schedule.AsNeeded(), at(newYork, "2026-03-02T10:30")))
    }

    // Missed and snooze (rules 5 and 6)

    @Test fun `dose counts as missed after two hours`() {
        val due = at(newYork, "2026-03-02T08:00")
        assertFalse(DoseCalculator.isMissed(due, at(newYork, "2026-03-02T09:59")))
        assertTrue(DoseCalculator.isMissed(due, at(newYork, "2026-03-02T10:00")))
        assertFalse(DoseCalculator.isMissed(due, at(newYork, "2026-03-02T10:00"), Duration.ofHours(3)))
    }

    @Test fun `snooze lengths`() {
        val now = at(newYork, "2026-03-02T08:00")
        assertEquals("2026-03-02T08:10", local(newYork, DoseCalculator.snoozeUntil(now, 10)))
        kotlin.test.assertFailsWith<IllegalArgumentException> { DoseCalculator.snoozeUntil(now, 7) }
    }
}
