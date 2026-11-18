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

class SupplyTest {

    private val zone = ZoneId.of("America/Chicago")
    private fun at(text: String): Instant = LocalDateTime.parse(text).atZone(zone).toInstant()

    private fun daily(vararg times: String, end: String? = null) =
        Schedule.FixedTimes(times.map(LocalTime::parse), DayRule.Daily, LocalDate.parse("2026-01-01"), end?.let(LocalDate::parse))

    @Test fun `whole doses ignore rounding noise`() {
        assertEquals(3, Supply.dosesLeft(0.1 + 0.2, 0.1))
        assertEquals(2, Supply.dosesLeft(5.0, 2.0))
        assertEquals(0, Supply.dosesLeft(0.5, 1.0))
        assertEquals(0, Supply.dosesLeft(3.0, 0.0))
    }

    @Test fun `twice daily with 10 pills runs out on day six`() {
        // Next dose 8 PM on the 10th. 10 pills cover 8 PM 10th through 8 AM 15th; 8 PM 15th is short.
        val now = at("2026-11-10T12:00")
        val o = Supply.outlook(daily("08:00", "20:00"), 10.0, 1.0, at("2026-11-10T20:00"), now, zone)
        assertEquals(10, o.dosesLeft)
        assertEquals(LocalDate.parse("2026-11-15"), o.runsOut)
        assertEquals(5L, o.daysLeft)
        assertTrue(o.low)
    }

    @Test fun `plenty left is not low`() {
        val now = at("2026-11-10T07:00")
        val o = Supply.outlook(daily("08:00"), 30.0, 1.0, at("2026-11-10T08:00"), now, zone)
        assertEquals(LocalDate.parse("2026-12-10"), o.runsOut)
        assertEquals(30L, o.daysLeft)
        assertFalse(o.low)
    }

    @Test fun `two pills per dose halves the supply`() {
        val now = at("2026-11-10T07:00")
        val o = Supply.outlook(daily("08:00"), 20.0, 2.0, at("2026-11-10T08:00"), now, zone)
        assertEquals(10, o.dosesLeft)
        assertEquals(LocalDate.parse("2026-11-20"), o.runsOut)
    }

    @Test fun `empty runs out today`() {
        val now = at("2026-11-10T07:00")
        val o = Supply.outlook(daily("08:00"), 0.0, 1.0, at("2026-11-10T08:00"), now, zone)
        assertEquals(LocalDate.parse("2026-11-10"), o.runsOut)
        assertEquals(0L, o.daysLeft)
        assertTrue(o.low)
    }

    @Test fun `a course that ends first never runs out`() {
        val now = at("2026-11-10T07:00")
        val o = Supply.outlook(daily("08:00", end = "2026-11-12"), 10.0, 1.0, at("2026-11-10T08:00"), now, zone)
        assertNull(o.runsOut)
        assertFalse(o.low)
    }

    @Test fun `interval schedule projects from the next dose`() {
        val s = Schedule.Interval(Duration.ofHours(6), at("2026-11-01T08:00"))
        val now = at("2026-11-10T07:00")
        // 8 doses at 6 h apart from 8 AM on the 10th: the 9th dose is 8 AM on the 12th.
        val o = Supply.outlook(s, 8.0, 1.0, at("2026-11-10T08:00"), now, zone)
        assertEquals(LocalDate.parse("2026-11-12"), o.runsOut)
        assertEquals(2L, o.daysLeft)
    }

    @Test fun `as needed warns on a dose count`() {
        val now = at("2026-11-10T07:00")
        val few = Supply.outlook(Schedule.AsNeeded(), 4.0, 1.0, null, now, zone)
        assertNull(few.runsOut)
        assertTrue(few.low)
        assertFalse(Supply.outlook(Schedule.AsNeeded(), 20.0, 1.0, null, now, zone).low)
    }
}
