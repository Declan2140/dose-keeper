package app.dosekeeper.ui

import app.dosekeeper.core.DayRule
import app.dosekeeper.core.DoseStatus
import app.dosekeeper.core.Schedule
import app.dosekeeper.core.ScheduleCodec
import app.dosekeeper.data.DoseLog
import app.dosekeeper.data.Medicine
import app.dosekeeper.data.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class DayViewTest {

    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now()
    private fun at(hour: Int, minute: Int = 0, day: LocalDate = today): Instant = day.atTime(hour, minute).atZone(zone).toInstant()
    private val noon = at(12)
    private val me = Profile(id = 1, name = "Me", colorArgb = 0, icon = "person")

    private fun twiceDaily(id: Long, name: String, nextDue: Instant) = Medicine(
        id = id, profileId = me.id, name = name,
        schedule = ScheduleCodec.encode(Schedule.FixedTimes(listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), DayRule.Daily, today.minusDays(10))),
        nextDueAt = nextDue.toEpochMilli(),
    )

    private fun log(med: Long, due: Instant, status: DoseStatus, at: Instant = due) =
        DoseLog(medicineId = med, dueAt = due.toEpochMilli(), actionAt = at.toEpochMilli(), status = status.name)

    @Test fun `a medicine added after its morning dose still shows that dose today`() {
        val added = twiceDaily(1, "Amoxicillin", at(20)).copy(createdAt = at(10).toEpochMilli())
        val day = buildToday(listOf(added), listOf(me), emptyList(), null, noon, zone)
        assertEquals(listOf(at(8), at(20)), day.doses.map { it.due })
        val morning = day.doses.first()
        assertTrue(morning.beforeAdded && morning.actionable && !morning.overdue)
        assertTrue(!day.doses.last().beforeAdded)
    }

    @Test fun `a dose from before the medicine was added shows as done once answered`() {
        val added = twiceDaily(1, "Amoxicillin", at(20)).copy(createdAt = at(10).toEpochMilli())
        val logs = listOf(log(1, at(8), DoseStatus.TAKEN, at(10, 5)))
        val day = buildToday(listOf(added), listOf(me), logs, null, noon, zone)
        assertEquals(listOf(DoseStatus.TAKEN, null), day.doses.sortedBy { it.due }.map { it.status })
        assertEquals(1, day.doses.count { it.beforeAdded || it.status != null })
    }

    @Test fun `a past day shows what was recorded and nothing can be changed`() {
        val yesterday = today.minusDays(1)
        val logs = listOf(
            log(1, at(8, 0, yesterday), DoseStatus.TAKEN, at(8, 4, yesterday)),
            log(1, at(20, 0, yesterday), DoseStatus.SKIPPED),
            log(1, at(8, 0), DoseStatus.TAKEN),
        )
        val day = buildDay(listOf(twiceDaily(1, "Lisinopril", at(20))), listOf(me), logs, null, yesterday, noon)
        assertEquals(listOf(DoseStatus.TAKEN, DoseStatus.SKIPPED), day.doses.map { it.status })
        assertEquals(at(8, 4, yesterday), day.doses.first().at)
        assertTrue(day.doses.none { it.actionable || it.undoable })
        assertEquals(1, day.takenCount)
    }

    @Test fun `a past day leaves out a dose that is still open`() {
        val yesterday = today.minusDays(1)
        val day = buildDay(listOf(twiceDaily(1, "Lisinopril", at(20, 0, yesterday))), listOf(me), emptyList(), null, yesterday, noon)
        assertTrue(day.doses.isEmpty())
    }

    @Test fun `a coming day previews the fixed times`() {
        val tomorrow = today.plusDays(1)
        val day = buildDay(listOf(twiceDaily(1, "Lisinopril", at(20))), listOf(me), emptyList(), null, tomorrow, noon)
        assertEquals(listOf(at(8, 0, tomorrow), at(20, 0, tomorrow)), day.doses.map { it.due })
        assertTrue(day.doses.all { it.status == null && !it.actionable })
        assertEquals(1, day.remaining.size)
    }

    @Test fun `a medicine on a timer is listed without a time on a coming day`() {
        val every8 = Medicine(
            id = 2, profileId = me.id, name = "Amoxicillin",
            schedule = ScheduleCodec.encode(Schedule.Interval(Duration.ofHours(8), at(6))),
            nextDueAt = at(14).toEpochMilli(),
        )
        val day = buildDay(listOf(every8), listOf(me), emptyList(), null, today.plusDays(2), noon)
        assertTrue(day.doses.isEmpty())
        assertEquals(listOf("Amoxicillin"), day.timed.map { it.first.name })
        assertTrue(buildDay(listOf(every8), listOf(me), emptyList(), null, today.minusDays(1), noon).timed.isEmpty())
    }

    @Test fun `as needed and paused medicines stay off a coming day`() {
        val asNeeded = Medicine(id = 3, profileId = me.id, name = "Ibuprofen", schedule = ScheduleCodec.encode(Schedule.AsNeeded()))
        val paused = twiceDaily(4, "Paused", at(20)).copy(status = "PAUSED")
        val day = buildDay(listOf(asNeeded, paused), listOf(me), emptyList(), null, today.plusDays(1), noon)
        assertTrue(day.doses.isEmpty())
        assertTrue(day.timed.isEmpty())
    }
}
