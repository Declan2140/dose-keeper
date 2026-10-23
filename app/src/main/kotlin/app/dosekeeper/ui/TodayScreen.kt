package app.dosekeeper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.dosekeeper.app
import app.dosekeeper.core.DoseStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val heroDate = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())

@Composable
fun TodayScreen(onAddMedicine: () -> Unit, onOpenReliability: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val medicines by app.db.medicines().observeAll().collectAsState(initial = null)
    val profiles by app.db.profiles().observeAll().collectAsState(initial = emptyList())
    val since = remember { Instant.now().minus(Duration.ofDays(30)).toEpochMilli() }
    val logs by app.db.doseLogs().observeSince(since).collectAsState(initial = emptyList())
    var profileFilter by remember { mutableStateOf<Long?>(null) }
    var choosing by remember { mutableStateOf<TodayDose?>(null) }
    var unchecking by remember { mutableStateOf<TodayDose?>(null) }

    // Re-evaluate late doses every 30 seconds, and catch up on anything missed while closed.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        app.engine.refreshAll()
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    val meds = medicines
    val model = remember(meds, profiles, logs, profileFilter, now) {
        meds?.let { buildToday(it, profiles, logs, profileFilter, Instant.ofEpochMilli(now)) }
    }
    val canRing = remember(now) { app.alarms.canScheduleExact() && app.notifier.notificationsAllowed() }

    fun answer(dose: TodayDose, status: DoseStatus) = scope.launch { app.engine.answer(dose.medicine.id, status) }
    fun snooze(dose: TodayDose) = scope.launch { app.engine.snooze(dose.medicine.id) }
    fun undo(dose: TodayDose) = scope.launch { app.engine.undo(dose.medicine.id, dose.due.toEpochMilli()) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onAddMedicine, shape = CircleShape, containerColor = MaterialTheme.colorScheme.primary) {
                Icon(Icons.Filled.Add, "Add medicine")
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item(key = "hero") {
                Hero(model, bottles = model?.remaining.orEmpty().map { Bottle.of(it.medForm) })
            }
            if (!canRing) {
                item(key = "warn") {
                    Row(
                        Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp).fillMaxWidth()
                            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(16.dp))
                            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Reminders may not ring on this phone yet.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onOpenReliability) { Text("Fix") }
                    }
                }
            }
            if (profiles.size > 1) {
                item(key = "filter") {
                    Segmented(
                        options = listOf<Long?>(null) + profiles.map { it.id },
                        selected = profileFilter,
                        label = { id -> profiles.firstOrNull { it.id == id }?.name ?: "Everyone" },
                        onSelect = { profileFilter = it },
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp),
                    )
                }
            }
            if (model == null) return@LazyColumn

            if (model.doses.isEmpty() && model.asNeeded.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (meds.isNullOrEmpty()) "No medicines yet. Tap + to add your first one."
                        else "Nothing due today.",
                        color = Muted,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
                    )
                }
            }

            val late = model.doses.filter { it.overdue }
            if (late.isNotEmpty()) {
                item(key = "late-h") {
                    Row(Modifier.padding(start = 24.dp, top = 24.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(Overdue, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text("Late", style = MaterialTheme.typography.labelMedium, color = Overdue)
                    }
                }
                late.forEach { dose ->
                    item(key = "late-${dose.medicine.id}-${dose.due.toEpochMilli()}") {
                        LateCard(dose, onTaken = { answer(dose, DoseStatus.TAKEN) }, onSnooze = { snooze(dose) }, onSkip = { answer(dose, DoseStatus.SKIPPED) })
                    }
                }
            }

            val rest = model.doses.filter { !it.overdue }
            timeGroups(rest.filter { it.status == null }.sortedBy { it.due }, "open") { dose ->
                DoseRow(dose, onCircle = { answer(dose, DoseStatus.TAKEN) }, onClick = { choosing = dose })
            }
            timeGroups(rest.filter { it.status != null }.sortedBy { it.due }, "done") { dose ->
                DoseRow(dose, onCircle = { undo(dose) }, onClick = { unchecking = dose })
            }

            if (model.asNeeded.isNotEmpty()) {
                item(key = "an-h") { TimeHeading("As needed") }
                model.asNeeded.forEach { row ->
                    item(key = "an-${row.medicine.id}") {
                        AsNeededRowView(row, onLog = { scope.launch { app.engine.logAsNeeded(row.medicine.id) } })
                    }
                }
            }
        }
    }

    choosing?.let { dose ->
        AlertDialog(
            onDismissRequest = { choosing = null },
            title = { Text(dose.medicine.displayName) },
            text = { Text(listOfNotNull(dose.profile?.name, "due ${dose.due.localTime()}").joinToString(", ")) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { answer(dose, DoseStatus.TAKEN); choosing = null }) { Text("Taken") }
                    TextButton(onClick = { snooze(dose); choosing = null }) { Text("Snooze") }
                    TextButton(onClick = { answer(dose, DoseStatus.SKIPPED); choosing = null }) { Text("Skip") }
                    TextButton(onClick = { choosing = null }) { Text("Cancel") }
                }
            },
        )
    }

    unchecking?.let { dose ->
        AlertDialog(
            onDismissRequest = { unchecking = null },
            title = { Text(dose.medicine.displayName) },
            text = {
                Text(
                    listOfNotNull(dose.profile?.name, "due ${dose.due.localTime()}").joinToString(", ") +
                        if (dose.status == DoseStatus.TAKEN) ". Taken at ${dose.at?.localTime() ?: ""}." else ". Skipped.",
                )
            },
            confirmButton = {
                TextButton(onClick = { undo(dose); unchecking = null }) {
                    Text(if (dose.status == DoseStatus.TAKEN) "Mark as not taken" else "Undo skip")
                }
            },
            dismissButton = { TextButton(onClick = { unchecking = null }) { Text("Cancel") } },
        )
    }
}

/** Adds a time heading before each run of doses that share a time. */
private fun LazyListScope.timeGroups(doses: List<TodayDose>, keyPrefix: String, row: @Composable (TodayDose) -> Unit) {
    var last: String? = null
    for (dose in doses) {
        val heading = dose.due.localTime().let { if (dose.due.localDate() != LocalDate.now()) "${dose.due.localDate().friendly()}, $it" else it }
        if (heading != last) {
            last = heading
            item(key = "$keyPrefix-h-$heading") { TimeHeading(heading) }
        }
        item(key = "$keyPrefix-${dose.medicine.id}-${dose.due.toEpochMilli()}") { row(dose) }
    }
}

@Composable
private fun TimeHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 2.dp),
    )
}

@Composable
private fun Hero(model: TodayModel?, bottles: List<Bottle>) {
    CounterScene(bottles, Modifier.fillMaxWidth().height(206.dp).background(MaterialTheme.colorScheme.surfaceContainerLow), dark = MaterialTheme.colorScheme.background == Ink) {
        Column(Modifier.padding(start = 24.dp, top = 32.dp)) {
            Text(LocalDate.now().format(heroDate), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Today", style = MaterialTheme.typography.headlineLarge)
            if (model != null && model.scheduledCount > 0) {
                Spacer(Modifier.height(18.dp))
                val fraction = model.takenCount.toFloat() / model.scheduledCount
                Box(Modifier.width(136.dp).height(6.dp).background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(3.dp))) {
                    Box(Modifier.fillMaxWidth(fraction).height(6.dp).background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(3.dp)))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (model.takenCount == model.scheduledCount) "All taken" else "${model.takenCount} of ${model.scheduledCount} taken",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LateCard(dose: TodayDose, onTaken: () -> Unit, onSnooze: () -> Unit, onSkip: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.padding(horizontal = 24.dp, vertical = 4.dp).fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, shape)
            .border(1.dp, Overdue.copy(alpha = 0.25f), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProfileBadge(dose.profile)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(dose.medicine.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(dose.profile?.name, "due ${dose.due.friendlyDateTime().removePrefix("Today, ")}").joinToString(", ") +
                        (dose.snoozedUntil?.let { ", snoozed to ${it.localTime()}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onTaken, modifier = Modifier.weight(1.5f).height(48.dp)) { Text("Taken") }
            OutlinedButton(onClick = onSnooze, modifier = Modifier.weight(1f).height(48.dp), contentPadding = PaddingValues(0.dp)) { Text("Snooze") }
            OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f).height(48.dp), contentPadding = PaddingValues(0.dp)) { Text("Skip") }
        }
    }
}

@Composable
private fun DoseRow(dose: TodayDose, onCircle: () -> Unit, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val done = dose.status != null
    Column(Modifier.padding(horizontal = 24.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = dose.actionable || dose.undoable, onClick = onClick).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileBadge(dose.profile, dimmed = done)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    dose.medicine.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (done) scheme.onSurfaceVariant.copy(alpha = 0.7f) else scheme.onSurface,
                    textDecoration = if (dose.status == DoseStatus.TAKEN) TextDecoration.LineThrough else null,
                )
                val sub = when (dose.status) {
                    DoseStatus.TAKEN -> "Taken at ${dose.at?.localTime() ?: ""}"
                    DoseStatus.SKIPPED -> "Skipped"
                    DoseStatus.MISSED -> "Missed"
                    null -> listOfNotNull(dose.profile?.name, dose.medicine.medForm.label.lowercase()).joinToString(", ") +
                        (dose.snoozedUntil?.let { ", snoozed to ${it.localTime()}" } ?: "")
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = if (dose.status == DoseStatus.MISSED) Overdue else scheme.onSurfaceVariant)
            }
            when {
                dose.status == DoseStatus.TAKEN -> Box(
                    Modifier.size(44.dp).background(scheme.secondary, CircleShape)
                        .clickable(enabled = dose.undoable, onClickLabel = "Mark as not taken", onClick = onCircle),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Check, "Taken", tint = scheme.onSecondary, modifier = Modifier.size(22.dp))
                }
                dose.actionable -> IconButton(
                    onClick = onCircle,
                    modifier = Modifier.size(44.dp).border(1.5.dp, scheme.outline, CircleShape),
                ) { Spacer(Modifier.size(1.dp)) }
                dose.status == null -> Text("Later", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(color = scheme.outlineVariant)
    }
}

@Composable
private fun AsNeededRowView(row: AsNeededRow, onLog: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ProfileBadge(row.profile)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(row.medicine.displayName, style = MaterialTheme.typography.titleMedium)
                val sub = when {
                    row.notBefore != null -> "Not before ${row.notBefore.localTime()}"
                    row.lastTaken != null -> "Last taken ${row.lastTaken.friendlyDateTime()}"
                    else -> "Not taken yet"
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = if (row.notBefore != null) Overdue else scheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onLog) { Text("Took it") }
        }
        HorizontalDivider(color = scheme.outlineVariant)
    }
}
