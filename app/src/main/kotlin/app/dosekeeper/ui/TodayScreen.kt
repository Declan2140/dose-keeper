package app.dosekeeper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.dosekeeper.app
import app.dosekeeper.core.DoseStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

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

    // Re-evaluate overdue states every 30 seconds, and catch up on anything missed while closed.
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
    val canExact = remember(now) { app.alarms.canScheduleExact() && app.notifier.notificationsAllowed() }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAddMedicine, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add medicine") })
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text("Today", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            if (!canExact) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, null)
                            Spacer(Modifier.width(12.dp))
                            Text("Reminders may not ring. Fix the settings in the reliability checklist.", Modifier.weight(1f))
                            TextButton(onClick = onOpenReliability) { Text("Fix") }
                        }
                    }
                }
            }
            if (profiles.size > 1) {
                item {
                    ChoiceChips(
                        options = listOf<Long?>(null) + profiles.map { it.id },
                        selected = profileFilter,
                        label = { id -> profiles.firstOrNull { it.id == id }?.name ?: "Everyone" },
                        onSelect = { profileFilter = it },
                    )
                }
            }
            if (model == null) return@LazyColumn
            if (model.doses.isEmpty() && model.asNeeded.isEmpty()) {
                item {
                    Text(
                        if (meds.isNullOrEmpty()) "No medicines yet. Add one and its reminders will show up here."
                        else "Nothing due today.",
                        Modifier.padding(vertical = 32.dp),
                    )
                }
            }
            var lastTime: String? = null
            for (dose in model.doses) {
                val heading = if (dose.overdue) "Overdue" else dose.due.localTime()
                if (heading != lastTime) {
                    lastTime = heading
                    item(key = "h-$heading") {
                        Text(heading, style = MaterialTheme.typography.titleMedium, color = if (dose.overdue) Overdue else MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                item(key = "d-${dose.medicine.id}-${dose.due.toEpochMilli()}") {
                    DoseCard(
                        dose,
                        onTaken = { scope.launch { app.engine.answer(dose.medicine.id, DoseStatus.TAKEN) } },
                        onSkip = { scope.launch { app.engine.answer(dose.medicine.id, DoseStatus.SKIPPED) } },
                        onSnooze = { scope.launch { app.engine.snooze(dose.medicine.id) } },
                    )
                }
            }
            if (model.asNeeded.isNotEmpty()) {
                item { Text("As needed", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                items(model.asNeeded, key = { "a-${it.medicine.id}" }) { row ->
                    AsNeededCard(row, onLog = { scope.launch { app.engine.logAsNeeded(row.medicine.id) } })
                }
            }
        }
    }
}

@Composable
private fun DoseCard(dose: TodayDose, onTaken: () -> Unit, onSkip: () -> Unit, onSnooze: () -> Unit) {
    val container = when {
        dose.overdue -> MaterialTheme.colorScheme.errorContainer
        dose.status != null -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    Card(colors = CardDefaults.cardColors(containerColor = container), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileBadge(dose.profile)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(dose.medicine.displayName, style = MaterialTheme.typography.titleMedium)
                    val sub = buildList {
                        dose.profile?.let { add(it.name) }
                        add(dose.medicine.medForm.label)
                        if (dose.overdue) add("was due ${dose.due.friendlyDateTime()}")
                        dose.snoozedUntil?.let { add("snoozed to ${it.localTime()}") }
                    }.joinToString(" · ")
                    Text(sub, style = MaterialTheme.typography.bodySmall)
                }
                when (dose.status) {
                    DoseStatus.TAKEN -> StatusTag(Icons.Filled.CheckCircle, "Taken", MaterialTheme.colorScheme.primary)
                    DoseStatus.SKIPPED -> StatusTag(Icons.Filled.Close, "Skipped", MaterialTheme.colorScheme.onSurfaceVariant)
                    DoseStatus.MISSED -> StatusTag(Icons.Filled.Warning, "Missed", Overdue)
                    null -> if (!dose.actionable) Text("Later", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (dose.medicine.note.isNotBlank() && dose.status == null) {
                Text(dose.medicine.note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
            if (dose.actionable) {
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onTaken, modifier = Modifier.weight(1.4f)) { Text("Taken") }
                    OutlinedButton(onClick = onSnooze, modifier = Modifier.weight(1f), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text("Snooze") }
                    OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text("Skip") }
                }
            }
        }
    }
}

@Composable
private fun StatusTag(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color)
        Spacer(Modifier.width(4.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun AsNeededCard(row: AsNeededRow, onLog: () -> Unit) {
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ProfileBadge(row.profile)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(row.medicine.displayName, style = MaterialTheme.typography.titleMedium)
                val sub = when {
                    row.notBefore != null -> "Not before ${row.notBefore.localTime()}"
                    row.lastTaken != null -> "Last taken ${row.lastTaken.friendlyDateTime()}"
                    else -> "Not taken yet"
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = if (row.notBefore != null) Overdue else MaterialTheme.colorScheme.onSurface)
            }
            Button(onClick = onLog) { Text("Took it") }
        }
    }
}
