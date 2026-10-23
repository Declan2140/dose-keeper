package app.dosekeeper.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.dosekeeper.app
import app.dosekeeper.core.DayRule
import app.dosekeeper.core.QuietHours
import app.dosekeeper.core.Schedule
import app.dosekeeper.core.ScheduleCodec
import app.dosekeeper.data.MedForm
import app.dosekeeper.data.MedStatus
import app.dosekeeper.data.Medicine
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

private enum class Kind(val label: String) { FIXED("Set times"), INTERVAL("Every few hours"), AS_NEEDED("As needed") }
private enum class Days(val label: String) { DAILY("Every day"), WEEKDAYS("Some weekdays"), EVERY_N("Every few days"), MONTHLY("Monthly") }

/** Editable form state, filled from an existing medicine or with defaults for a new one. */
private class Form {
    var name by mutableStateOf("")
    var strength by mutableStateOf("")
    var form by mutableStateOf(MedForm.PILL)
    var note by mutableStateOf("")
    var profileId by mutableStateOf<Long?>(null)
    var kind by mutableStateOf(Kind.FIXED)
    val times = mutableStateListOf(LocalTime.of(8, 0))
    var days by mutableStateOf(Days.DAILY)
    val weekdays = mutableStateListOf<DayOfWeek>()
    var everyN by mutableStateOf("2")
    var dayOfMonth by mutableStateOf("1")
    var startDate by mutableStateOf(LocalDate.now())
    var endDate by mutableStateOf<LocalDate?>(null)
    var intervalHours by mutableStateOf("6")
    var firstDose by mutableStateOf(LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0))
    var quiet by mutableStateOf(false)
    var quietStart by mutableStateOf(LocalTime.of(22, 0))
    var quietEnd by mutableStateOf(LocalTime.of(7, 0))
    var gapHours by mutableStateOf("")

    fun load(med: Medicine) {
        name = med.name; strength = med.strength; form = med.medForm; note = med.note; profileId = med.profileId
        when (val s = med.parsedSchedule) {
            is Schedule.FixedTimes -> {
                kind = Kind.FIXED
                times.clear(); times.addAll(s.times.sorted())
                startDate = s.startDate; endDate = s.endDate
                when (val d = s.days) {
                    DayRule.Daily -> days = Days.DAILY
                    is DayRule.DaysOfWeek -> { days = Days.WEEKDAYS; weekdays.clear(); weekdays.addAll(d.days) }
                    is DayRule.EveryNDays -> { days = Days.EVERY_N; everyN = d.n.toString() }
                    is DayRule.DayOfMonth -> { days = Days.MONTHLY; dayOfMonth = d.day.toString() }
                }
            }
            is Schedule.Interval -> {
                kind = Kind.INTERVAL
                intervalHours = formatHours(s.every)
                firstDose = s.firstDose.atZone(ZoneId.systemDefault()).toLocalDateTime()
                s.quietHours?.let { quiet = true; quietStart = it.start; quietEnd = it.end }
            }
            is Schedule.AsNeeded -> {
                kind = Kind.AS_NEEDED
                gapHours = s.minGap?.let(::formatHours) ?: ""
            }
        }
    }

    /** The schedule, or an error message saying what to fix. */
    fun schedule(): Result<Schedule> = runCatching {
        when (kind) {
            Kind.FIXED -> {
                require(times.isNotEmpty()) { "Add at least one time." }
                val rule = when (days) {
                    Days.DAILY -> DayRule.Daily
                    Days.WEEKDAYS -> { require(weekdays.isNotEmpty()) { "Pick at least one weekday." }; DayRule.DaysOfWeek(weekdays.toSet()) }
                    Days.EVERY_N -> DayRule.EveryNDays(everyN.toIntOrNull()?.takeIf { it in 1..365 } ?: error("Enter how many days apart, 1 to 365."))
                    Days.MONTHLY -> DayRule.DayOfMonth(dayOfMonth.toIntOrNull()?.takeIf { it in 1..31 } ?: error("Enter a day of the month, 1 to 31."))
                }
                endDate?.let { require(!it.isBefore(startDate)) { "The end date is before the start date." } }
                Schedule.FixedTimes(times.distinct().sorted(), rule, startDate, endDate)
            }
            Kind.INTERVAL -> {
                val hours = parseHours(intervalHours)?.takeIf { it >= Duration.ofMinutes(15) && it <= Duration.ofHours(168) }
                    ?: error("Enter the hours between doses, for example 6 or 1.5.")
                Schedule.Interval(hours, firstDose.atZone(ZoneId.systemDefault()).toInstant(), if (quiet && quietStart != quietEnd) QuietHours(quietStart, quietEnd) else null)
            }
            Kind.AS_NEEDED -> Schedule.AsNeeded(if (gapHours.isBlank()) null else parseHours(gapHours) ?: error("Enter the minimum hours between doses, or leave it empty."))
        }
    }
}

private fun parseHours(text: String): Duration? =
    text.replace(',', '.').trim().toDoubleOrNull()?.takeIf { it > 0 }?.let { Duration.ofMinutes((it * 60).toLong()) }

private fun formatHours(d: Duration): String {
    val m = d.toMinutes()
    return if (m % 60 == 0L) (m / 60).toString() else "%.2f".format(m / 60.0).trimEnd('0').trimEnd('.')
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditMedicineScreen(medicineId: Long, onDone: () -> Unit, onAddProfile: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val profiles by app.db.profiles().observeAll().collectAsState(initial = emptyList())
    val form = remember { Form() }
    var existing by remember { mutableStateOf<Medicine?>(null) }
    var loaded by remember { mutableStateOf(medicineId == 0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmArchive by remember { mutableStateOf(false) }

    LaunchedEffect(medicineId) {
        if (medicineId != 0L) {
            app.db.medicines().get(medicineId)?.let { existing = it; form.load(it) }
            loaded = true
        }
    }
    LaunchedEffect(profiles) {
        if (form.profileId == null && profiles.isNotEmpty()) form.profileId = profiles.first().id
    }
    if (!loaded) return
    val archived = existing?.medStatus == MedStatus.ARCHIVED

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Add medicine" else "Edit medicine") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
        ) {
            Field(
                form.name, { form.name = it }, label = { Text("Medicine name") }, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), enabled = !archived,
            )
            Spacer(Modifier.height(8.dp))
            Field(form.strength, { form.strength = it }, label = { Text("Strength or dose (optional)") }, placeholder = { Text("e.g. 250 mg, 2 tablets, 5 ml") }, modifier = Modifier.fillMaxWidth(), enabled = !archived)

            SectionTitle("Form")
            ChoiceChips(MedForm.entries, form.form, { it.label }, { form.form = it })

            SectionTitle("For")
            Segmented(profiles.map { it.id }, form.profileId, { id -> profiles.firstOrNull { it.id == id }?.name ?: "" }, { form.profileId = it })
            TextButton(onClick = onAddProfile) { Text("Add a person or pet") }

            SectionTitle("When")
            Segmented(Kind.entries, form.kind, { it.label }, { form.kind = it })
            Spacer(Modifier.height(8.dp))
            when (form.kind) {
                Kind.FIXED -> FixedEditor(form, context)
                Kind.INTERVAL -> IntervalEditor(form, context)
                Kind.AS_NEEDED -> {
                    Text("No reminders. Log each dose with one tap on the Today screen.", style = MaterialTheme.typography.bodySmall)
                    Field(
                        form.gapHours, { form.gapHours = it }, label = { Text("Minimum hours between doses (optional)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }

            SectionTitle("Note")
            Field(form.note, { form.note = it }, label = { Text("Note (optional)") }, placeholder = { Text("e.g. with food") }, modifier = Modifier.fillMaxWidth(), minLines = 2)

            val summary = form.schedule().getOrNull()?.describe()
            val who = profiles.firstOrNull { it.id == form.profileId }?.name
            if (summary != null && form.name.isNotBlank()) {
                Text(
                    listOfNotNull(who, listOf(form.name.trim(), form.strength.trim()).filter { it.isNotEmpty() }.joinToString(" ")).joinToString(": ") + ". " + summary + ".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 20.dp),
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    val name = form.name.trim()
                    val schedule = form.schedule()
                    val profileId = form.profileId
                    error = when {
                        name.isEmpty() -> "Enter the medicine's name."
                        profileId == null -> "Choose who it's for."
                        schedule.isFailure -> schedule.exceptionOrNull()?.message ?: "Check the schedule."
                        else -> null
                    }
                    if (error != null) return@Button
                    val encoded = ScheduleCodec.encode(schedule.getOrThrow())
                    val base = existing ?: Medicine(profileId = profileId!!, name = name, schedule = encoded)
                    val updated = base.copy(
                        name = name, strength = form.strength.trim(), form = form.form.name, note = form.note.trim(),
                        profileId = profileId!!, schedule = encoded,
                    )
                    scope.launch {
                        app.engine.save(updated, scheduleChanged = existing?.schedule != encoded)
                        onDone()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(58.dp),
            ) { Text("Save medicine") }

            existing?.let { med ->
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (med.medStatus != MedStatus.ARCHIVED) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                app.engine.setStatus(med.id, if (med.medStatus == MedStatus.PAUSED) MedStatus.ACTIVE else MedStatus.PAUSED)
                                onDone()
                            }
                        }, modifier = Modifier.weight(1f)) { Text(if (med.medStatus == MedStatus.PAUSED) "Resume" else "Pause") }
                        OutlinedButton(onClick = { confirmArchive = true }, modifier = Modifier.weight(1f)) { Text("Archive") }
                    }
                }
                if (archived) Text("Archived. Its history is kept, and it won't remind you again.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (confirmArchive) {
        AlertDialog(
            onDismissRequest = { confirmArchive = false },
            title = { Text("Archive ${existing?.name}?") },
            text = { Text("Reminders stop for good and it moves to the archived list. Its history is kept. To take it again later, add it as a new medicine.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmArchive = false
                    existing?.let { scope.launch { app.engine.setStatus(it.id, MedStatus.ARCHIVED); onDone() } }
                }) { Text("Archive") }
            },
            dismissButton = { TextButton(onClick = { confirmArchive = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FixedEditor(form: Form, context: Context) {
    Text("Times", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
        form.times.sorted().forEach { t ->
            InputChip(
                selected = false,
                onClick = { pickTime(context, t) { new -> form.times.remove(t); if (new !in form.times) form.times.add(new) } },
                label = { Text(t.short()) },
                trailingIcon = { IconButton(onClick = { form.times.remove(t) }) { Icon(Icons.Filled.Close, "Remove ${t.short()}") } },
            )
        }
        TextButton(onClick = { pickTime(context, LocalTime.of(12, 0)) { if (it !in form.times) form.times.add(it) } }) {
            Icon(Icons.Filled.Add, null); Text(" Add time")
        }
    }
    Spacer(Modifier.height(8.dp))
    ChoiceChips(Days.entries, form.days, { it.label }, { form.days = it })
    Spacer(Modifier.height(8.dp))
    when (form.days) {
        Days.DAILY -> Unit
        Days.WEEKDAYS -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DayOfWeek.entries.forEach { d ->
                FilterChip(selected = d in form.weekdays, onClick = { if (d in form.weekdays) form.weekdays.remove(d) else form.weekdays.add(d) }, label = { Text(d.short()) })
            }
        }
        Days.EVERY_N -> Field(form.everyN, { form.everyN = it }, label = { Text("Every how many days") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Days.MONTHLY -> Field(form.dayOfMonth, { form.dayOfMonth = it }, label = { Text("Day of the month (1 to 31)") }, supportingText = { Text("In shorter months, the 29th to 31st fall on the last day.") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Starts", Modifier.weight(1f))
        TextButton(onClick = { pickDate(context, form.startDate) { form.startDate = it } }) { Text(form.startDate.friendly()) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Ends", Modifier.weight(1f))
        TextButton(onClick = { pickDate(context, form.endDate ?: form.startDate.plusDays(7)) { form.endDate = it } }) { Text(form.endDate?.friendly() ?: "No end date") }
        if (form.endDate != null) IconButton(onClick = { form.endDate = null }) { Icon(Icons.Filled.Close, "Clear end date") }
    }
}

@Composable
private fun IntervalEditor(form: Form, context: Context) {
    Field(
        form.intervalHours, { form.intervalHours = it }, label = { Text("Hours between doses") },
        supportingText = { Text("Counted from when you actually take each dose.") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("First dose", Modifier.weight(1f))
        TextButton(onClick = {
            pickDate(context, form.firstDose.toLocalDate()) { d ->
                pickTime(context, form.firstDose.toLocalTime()) { t -> form.firstDose = LocalDateTime.of(d, t) }
            }
        }) { Text("${form.firstDose.toLocalDate().friendly()}, ${form.firstDose.toLocalTime().short()}") }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Pause overnight", Modifier.weight(1f))
        Switch(form.quiet, { form.quiet = it })
    }
    if (form.quiet) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("From", Modifier.weight(1f))
            TextButton(onClick = { pickTime(context, form.quietStart) { form.quietStart = it } }) { Text(form.quietStart.short()) }
            Text("to")
            TextButton(onClick = { pickTime(context, form.quietEnd) { form.quietEnd = it } }) { Text(form.quietEnd.short()) }
        }
        Text("A dose that falls in this window waits until it ends.", style = MaterialTheme.typography.bodySmall)
    }
}

private fun pickTime(context: Context, initial: LocalTime, onPick: (LocalTime) -> Unit) {
    TimePickerDialog(context, { _, h, m -> onPick(LocalTime.of(h, m)) }, initial.hour, initial.minute, DateFormat.is24HourFormat(context)).show()
}

private fun pickDate(context: Context, initial: LocalDate, onPick: (LocalDate) -> Unit) {
    DatePickerDialog(context, { _, y, m, d -> onPick(LocalDate.of(y, m + 1, d)) }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
}
