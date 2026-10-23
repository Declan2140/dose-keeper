package app.dosekeeper.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.dosekeeper.app
import app.dosekeeper.billing.UnlockManager
import app.dosekeeper.core.DoseStatus
import app.dosekeeper.data.DoseLog
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

@Composable
fun HistoryScreen() {
    val app = LocalContext.current.app
    val scope = rememberCoroutineScope()
    val unlocked by app.unlock.unlocked.collectAsState()
    val cutoff = remember(unlocked) {
        if (unlocked) 0L else Instant.now().minus(Duration.ofDays(UnlockManager.FREE_HISTORY_DAYS)).toEpochMilli()
    }
    val logs by app.db.doseLogs().observeSince(cutoff).collectAsState(initial = emptyList())
    val olderCount by app.db.doseLogs().observeCountBefore(cutoff).collectAsState(initial = 0)
    val medicines by app.db.medicines().observeAll().collectAsState(initial = emptyList())
    val profiles by app.db.profiles().observeAll().collectAsState(initial = emptyList())
    var profileFilter by rememberSaveable { mutableStateOf<Long?>(null) }
    var medicineFilter by rememberSaveable { mutableStateOf<Long?>(null) }
    var editing by remember { mutableStateOf<DoseLog?>(null) }
    var showUnlock by remember { mutableStateOf(false) }

    val medById = medicines.associateBy { it.id }
    val profileById = profiles.associateBy { it.id }
    val filtered = logs.filter { log ->
        val med = medById[log.medicineId]
        (profileFilter == null || med?.profileId == profileFilter) && (medicineFilter == null || log.medicineId == medicineFilter)
    }
    val byDay = filtered.groupBy { Instant.ofEpochMilli(it.dueAt ?: it.actionAt).localDate() }.toSortedMap(Comparator.reverseOrder())

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("History", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(bottom = 8.dp)) }
        if (profiles.size > 1) {
            item {
                Segmented(listOf<Long?>(null) + profiles.map { it.id }, profileFilter, { id -> profileById[id]?.name ?: "Everyone" }, {
                    profileFilter = it
                    medicineFilter = null
                })
            }
        }
        val medOptions = medicines.filter { profileFilter == null || it.profileId == profileFilter }
        if (medOptions.size > 1) {
            item {
                ChoiceChips(listOf<Long?>(null) + medOptions.map { it.id }, medicineFilter, { id -> medById[id]?.name ?: "All medicines" }, { medicineFilter = it })
            }
        }
        if (byDay.isEmpty()) item { Text("No doses recorded yet.", Modifier.padding(vertical = 24.dp)) }
        byDay.forEach { (day, dayLogs) ->
            item(key = "day-$day") {
                val taken = dayLogs.count { it.doseStatus == DoseStatus.TAKEN }
                Text("${day.friendly()}, $taken of ${dayLogs.size} taken", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 20.dp))
            }
            dayLogs.sortedByDescending { it.dueAt ?: it.actionAt }.forEach { log ->
                item(key = "log-${log.id}") {
                    val med = medById[log.medicineId]
                    HistoryRow(log, med?.displayName ?: "Deleted medicine", profileById[med?.profileId], onClick = { editing = log })
                }
            }
        }
        if (!unlocked && olderCount > 0) {
            item {
                Card(Modifier.fillMaxWidth().padding(top = 12.dp).clickable { showUnlock = true }) {
                    Text("$olderCount older entries are kept safely on your phone. The unlock shows your full history.", Modifier.padding(16.dp))
                }
            }
        }
    }

    editing?.let { log ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Change this entry") },
            text = { Text("${medById[log.medicineId]?.displayName ?: ""}, ${Instant.ofEpochMilli(log.dueAt ?: log.actionAt).friendlyDateTime()}") },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    DoseStatus.entries.filter { it != log.doseStatus }.forEach { status ->
                        TextButton(onClick = {
                            scope.launch { app.db.doseLogs().update(log.copy(status = status.name, actionAt = if (status == DoseStatus.TAKEN) log.dueAt ?: log.actionAt else log.actionAt)) }
                            editing = null
                        }) { Text("Mark ${status.label()}") }
                    }
                    if (log.dueAt == null) {
                        TextButton(onClick = {
                            scope.launch { app.db.doseLogs().delete(log) }
                            editing = null
                        }) { Text("Delete") }
                    }
                    TextButton(onClick = { editing = null }) { Text("Cancel") }
                }
            },
        )
    }
    if (showUnlock) UnlockDialog("Free history covers the last ${UnlockManager.FREE_HISTORY_DAYS} days.", onDismiss = { showUnlock = false })
}

private fun DoseStatus.label() = when (this) {
    DoseStatus.TAKEN -> "taken"
    DoseStatus.SKIPPED -> "skipped"
    DoseStatus.MISSED -> "missed"
}

@Composable
private fun HistoryRow(log: DoseLog, name: String, profile: app.dosekeeper.data.Profile?, onClick: () -> Unit) {
    val color = when (log.doseStatus) {
        DoseStatus.TAKEN -> MaterialTheme.colorScheme.secondary
        DoseStatus.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
        DoseStatus.MISSED -> Overdue
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ProfileBadge(profile, 36.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name)
            val time = log.dueAt?.let { "Due ${Instant.ofEpochMilli(it).localTime()}" } ?: "As needed"
            val at = if (log.doseStatus == DoseStatus.MISSED) "" else ", ${log.doseStatus.label()} at ${Instant.ofEpochMilli(log.actionAt).localTime()}"
            Text(time + at, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(log.doseStatus.label().replaceFirstChar { it.uppercase() }, color = color, style = MaterialTheme.typography.labelLarge)
    }
}
