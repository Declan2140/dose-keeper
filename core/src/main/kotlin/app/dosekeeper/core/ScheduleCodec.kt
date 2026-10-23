package app.dosekeeper.core

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Turns a [Schedule] into a short text form for the database and backups, and back.
 * The format is `type;key=value;key=value` and is versioned by its keys: unknown keys are
 * ignored and missing optional keys get defaults, so older backups keep loading.
 */
object ScheduleCodec {

    fun encode(schedule: Schedule): String = when (schedule) {
        is Schedule.FixedTimes -> buildList {
            add("fixed")
            add("times=" + schedule.times.joinToString(",") { it.toString() })
            add("days=" + encodeDays(schedule.days))
            add("start=" + schedule.startDate)
            schedule.endDate?.let { add("end=$it") }
        }.joinToString(";")

        is Schedule.Interval -> buildList {
            add("interval")
            add("everyMin=" + schedule.every.toMinutes())
            add("first=" + schedule.firstDose.toEpochMilli())
            schedule.quietHours?.let { add("quiet=${it.start}-${it.end}") }
        }.joinToString(";")

        is Schedule.AsNeeded -> buildList {
            add("asneeded")
            schedule.minGap?.let { add("gapMin=" + it.toMinutes()) }
        }.joinToString(";")
    }

    fun decode(text: String): Schedule {
        val parts = text.split(";")
        val fields = parts.drop(1).filter { "=" in it }.associate { it.substringBefore("=") to it.substringAfter("=") }
        return when (parts.first()) {
            "fixed" -> Schedule.FixedTimes(
                times = fields.getValue("times").split(",").map(LocalTime::parse),
                days = decodeDays(fields["days"] ?: "daily"),
                startDate = LocalDate.parse(fields.getValue("start")),
                endDate = fields["end"]?.let(LocalDate::parse),
            )

            "interval" -> Schedule.Interval(
                every = Duration.ofMinutes(fields.getValue("everyMin").toLong()),
                firstDose = Instant.ofEpochMilli(fields.getValue("first").toLong()),
                quietHours = fields["quiet"]?.let {
                    QuietHours(LocalTime.parse(it.substringBefore("-")), LocalTime.parse(it.substringAfter("-")))
                },
            )

            "asneeded" -> Schedule.AsNeeded(fields["gapMin"]?.let { Duration.ofMinutes(it.toLong()) })
            else -> throw IllegalArgumentException("Unknown schedule type: ${parts.first()}")
        }
    }

    private fun encodeDays(rule: DayRule): String = when (rule) {
        DayRule.Daily -> "daily"
        is DayRule.DaysOfWeek -> "dow:" + rule.days.sorted().joinToString(",") { it.value.toString() }
        is DayRule.EveryNDays -> "every:${rule.n}"
        is DayRule.DayOfMonth -> "dom:${rule.day}"
    }

    private fun decodeDays(text: String): DayRule {
        val value = text.substringAfter(":")
        return when (text.substringBefore(":")) {
            "daily" -> DayRule.Daily
            "dow" -> DayRule.DaysOfWeek(value.split(",").map { DayOfWeek.of(it.toInt()) }.toSet())
            "every" -> DayRule.EveryNDays(value.toInt())
            "dom" -> DayRule.DayOfMonth(value.toInt())
            else -> throw IllegalArgumentException("Unknown day rule: $text")
        }
    }
}
