package app.dosekeeper.core

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

class ScheduleCodecTest {

    private fun roundTrip(s: Schedule) = assertEquals(s, ScheduleCodec.decode(ScheduleCodec.encode(s)))

    @Test fun `fixed schedules round trip`() {
        val start = LocalDate.parse("2026-03-01")
        roundTrip(Schedule.FixedTimes(listOf(LocalTime.of(8, 0), LocalTime.of(20, 30)), DayRule.Daily, start))
        roundTrip(Schedule.FixedTimes(listOf(LocalTime.of(8, 0)), DayRule.DaysOfWeek(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)), start, LocalDate.parse("2026-04-01")))
        roundTrip(Schedule.FixedTimes(listOf(LocalTime.of(8, 0)), DayRule.EveryNDays(3), start))
        roundTrip(Schedule.FixedTimes(listOf(LocalTime.of(18, 0)), DayRule.DayOfMonth(1), start))
    }

    @Test fun `interval schedules round trip`() {
        val first = Instant.parse("2026-03-01T12:00:00Z")
        roundTrip(Schedule.Interval(Duration.ofHours(6), first))
        roundTrip(Schedule.Interval(Duration.ofMinutes(90), first, QuietHours(LocalTime.of(22, 0), LocalTime.of(7, 0))))
    }

    @Test fun `as needed round trips`() {
        roundTrip(Schedule.AsNeeded())
        roundTrip(Schedule.AsNeeded(Duration.ofHours(4)))
    }

    @Test fun `unknown keys are ignored`() {
        assertEquals(Schedule.AsNeeded(Duration.ofHours(4)), ScheduleCodec.decode("asneeded;gapMin=240;futureKey=1"))
    }
}
