package app.dosekeeper.ui

import app.dosekeeper.core.DayRule
import app.dosekeeper.core.Schedule
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val dateFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

fun LocalTime.short(): String = timeFormat.format(this)
fun Instant.localTime(): String = timeFormat.format(atZone(ZoneId.systemDefault()))
fun Instant.localDate(): LocalDate = atZone(ZoneId.systemDefault()).toLocalDate()
fun LocalDate.friendly(): String {
    val today = LocalDate.now()
    return when (this) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        today.plusDays(1) -> "Tomorrow"
        else -> dateFormat.format(this)
    }
}

fun Instant.friendlyDateTime(): String = "${localDate().friendly()}, ${localTime()}"

fun DayOfWeek.short(): String = getDisplayName(TextStyle.SHORT, Locale.getDefault())

private fun ordinal(n: Int): String = n.toString() + when {
    n % 100 in 11..13 -> "th"
    n % 10 == 1 -> "st"
    n % 10 == 2 -> "nd"
    n % 10 == 3 -> "rd"
    else -> "th"
}

private fun hours(minutes: Long): String = when {
    minutes % 60 == 0L -> "${minutes / 60} hour" + if (minutes == 60L) "" else "s"
    else -> "${"%.2f".format(minutes / 60.0).trimEnd('0').trimEnd('.')} hours"
}

/** "8:00 AM and 8:00 PM, Mon to Fri" */
fun Schedule.describe(): String = when (this) {
    is Schedule.FixedTimes -> {
        val at = times.sorted().joinToString(", ") { it.short() }
        val on = when (val d = days) {
            DayRule.Daily -> "every day"
            is DayRule.DaysOfWeek -> when (d.days) {
                DayOfWeek.entries.toSet() -> "every day"
                setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY) -> "weekdays"
                else -> d.days.sorted().joinToString(", ") { it.short() }
            }
            is DayRule.EveryNDays -> if (d.n == 1) "every day" else "every ${d.n} days"
            is DayRule.DayOfMonth -> "on the ${ordinal(d.day)} of each month"
        }
        "$at, $on"
    }
    is Schedule.Interval -> "Every ${hours(every.toMinutes())}" + (quietHours?.let { ", paused ${it.start.short()} to ${it.end.short()}" } ?: "")
    is Schedule.AsNeeded -> "As needed" + (minGap?.let { ", at most every ${hours(it.toMinutes())}" } ?: "")
}
