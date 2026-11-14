package app.dosekeeper.widget

import app.dosekeeper.core.DayRule
import app.dosekeeper.core.DoseStatus
import app.dosekeeper.core.Schedule
import app.dosekeeper.core.ScheduleCodec
import app.dosekeeper.data.DoseLog
import app.dosekeeper.data.Medicine
import app.dosekeeper.data.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class WidgetViewTest {

    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now()
    private fun at(hour: Int, minute: Int = 0, day: LocalDate = today): Instant = day.atTime(hour, minute).atZone(zone).toInstant()
    private val noon = at(12)

    private val me = Profile(id = 1, name = "Me", colorArgb = 0, icon = "person")
    private val rex = Profile(id = 2, name = "Rex", colorArgb = 0, icon = "pet")

    private fun daily(id: Long, name: String, hour: Int, minute: Int, nextDue: Instant, profile: Profile = me) = Medicine(
        id = id, profileId = profile.id, name = name,
        schedule = ScheduleCodec.encode(Schedule.FixedTimes(listOf(LocalTime.of(hour, minute)), DayRule.Daily, today.minusDays(10))),
        nextDueAt = nextDue.toEpochMilli(),
    )

    @Test fun `a late dose can be taken from the widget`() {
        val view = buildWidgetView(listOf(daily(1, "Lisinopril", 11, 55, at(11, 55))), listOf(me), emptyList(), noon)
        view as WidgetView.Dose
        assertEquals(1L, view.medicineId)
        assertEquals(at(11, 55).toEpochMilli(), view.dueAt)
        assertTrue(view.late)
        assertTrue(view.canTake)
        assertNull(view.who)
        assertNull(view.lastTaken)
    }

    @Test fun `a dose hours away is shown without a Taken button`() {
        val view = buildWidgetView(listOf(daily(1, "Lisinopril", 15, 0, at(15))), listOf(me), emptyList(), noon)
        view as WidgetView.Dose
        assertFalse(view.late)
        assertFalse(view.canTake)
    }

    @Test fun `a dose within the hour can be taken`() {
        val view = buildWidgetView(listOf(daily(1, "Lisinopril", 12, 45, at(12, 45))), listOf(me), emptyList(), noon)
        assertTrue((view as WidgetView.Dose).canTake)
    }

    @Test fun `late doses come before later ones and name who they are for`() {
        val meds = listOf(daily(1, "Lisinopril", 12, 30, at(12, 30)), daily(2, "Carprofen", 11, 0, at(11), rex))
        val view = buildWidgetView(meds, listOf(me, rex), emptyList(), noon) as WidgetView.Dose
        assertEquals(2L, view.medicineId)
        assertEquals("Rex", view.who)
    }

    @Test fun `everything answered says all done and when the last dose was taken`() {
        val med = daily(1, "Lisinopril", 8, 0, at(8, 0, today.plusDays(1)))
        val log = DoseLog(medicineId = 1, dueAt = at(8).toEpochMilli(), status = DoseStatus.TAKEN.name, actionAt = at(8, 2).toEpochMilli())
        val view = buildWidgetView(listOf(med), listOf(me), listOf(log), noon)
        view as WidgetView.Clear
        assertEquals("All done for today", view.message)
        assertTrue(view.lastTaken!!.startsWith("Lisinopril, taken at "))
    }

    @Test fun `no medicines means nothing due`() {
        val view = buildWidgetView(emptyList(), listOf(me), emptyList(), noon)
        assertEquals("Nothing due today", (view as WidgetView.Clear).message)
    }
}
