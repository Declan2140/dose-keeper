package app.dosekeeper.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.OutlinedButton
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
import app.dosekeeper.report.DoctorReport
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
    var unlockReason by remember { mutableStateOf("") }
    var askReport by remember { mutableStateOf(false) }

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
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("History", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = {
                    if (unlocked) askReport = true
                    else { unlockReason = "Doctor reports are part of the unlock."; showUnlock = true }
                }) { Text("Doctor report") }
            }
        }
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
                Card(Modifier.fillMaxWidth().padding(top = 12.dp).clickable { unlockReason = "Free history covers the last ${UnlockManager.FREE_HISTORY_DAYS} days."; showUnlock = true }) {
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
                            scope.launch { app.engine.editLog(log, status) }
                            editing = null
                        }) { Text("Mark ${status.label()}") }
                    }
                    if (log.dueAt == null) {
                        TextButton(onClick = {
                            scope.launch { app.engine.editLog(log, null) }
                            editing = null
                        }) { Text("Delete") }
                    }
                    TextButton(onClick = { editing = null }) { Text("Cancel") }
                }
            },
        )
    }
    if (showUnlock) UnlockDialog(unlockReason, onDismiss = { showUnlock = false })
    if (askReport) DoctorReportFlow(profiles, profileFilter, onDone = { askReport = false })
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

private enum class ReportRange(val label: String, val days: Long) {
    WEEK("Last 7 days", 7), MONTH("Last 30 days", 30), QUARTER("Last 90 days", 90)
}

/**
 * Doctor report (unlock feature): pick who and how far back, choose where to save the PDF,
 * then open or share it. Opening hands it to the phone's PDF viewer, which can also print.
 */
@Composable
private fun DoctorReportFlow(profiles: List<app.dosekeeper.data.Profile>, initialProfile: Long?, onDone: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    var profileId by remember { mutableStateOf(initialProfile ?: profiles.firstOrNull()?.id) }
    var range by remember { mutableStateOf(ReportRange.MONTH) }
    var saved by remember { mutableStateOf<android.net.Uri?>(null) }
    var savedName by remember { mutableStateOf("countertop-report.pdf") }
    var working by remember { mutableStateOf(false) }
    val profile = profiles.firstOrNull { it.id == profileId }
    val to = remember { java.time.LocalDate.now() }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val who = profile
        if (uri == null || who == null) return@rememberLauncherForActivityResult
        working = true
        scope.launch {
            runCatching { app.report.write(uri, who, to.minusDays(range.days - 1), to) }
                .onSuccess { saved = uri }
                .onFailure {
                    Toast.makeText(context, "Couldn't make the report: ${it.message}", Toast.LENGTH_LONG).show()
                    onDone()
                }
            working = false
        }
    }

    val done = saved
    if (done != null) {
        AlertDialog(
            onDismissRequest = onDone,
            title = { Text("Report saved") },
            text = { Text("Open it to check or print, or share it with your doctor, pharmacist or vet.") },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        // Share a copy under the report's own name: a saved document's link often ends
                        // in a bare number, which some apps would use as the file name.
                        scope.launch {
                            runCatching {
                                val uri = shareableCopy(context, done, savedName)
                                val share = Intent(Intent.ACTION_SEND).setType("application/pdf")
                                    .putExtra(Intent.EXTRA_STREAM, uri)
                                    .putExtra(Intent.EXTRA_SUBJECT, savedName.removeSuffix(".pdf"))
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                share.clipData = android.content.ClipData.newUri(context.contentResolver, savedName, uri)
                                context.startActivity(Intent.createChooser(share, "Share report"))
                            }.onFailure { Toast.makeText(context, "Couldn't share the report: ${it.message}", Toast.LENGTH_LONG).show() }
                        }
                    }) { Text("Share") }
                    TextButton(onClick = {
                        val view = Intent(Intent.ACTION_VIEW).setDataAndType(done, "application/pdf")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        runCatching { context.startActivity(view) }
                            .onFailure { Toast.makeText(context, "No app to open PDFs. Try Share instead.", Toast.LENGTH_LONG).show() }
                        // Stays open, so Share is still there after checking the report.
                    }) { Text("Open") }
                }
            },
            dismissButton = { TextButton(onClick = onDone) { Text("Close") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Doctor report") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("A PDF of the medicines, how many doses were taken, skipped or missed, and every dose in the period.", style = MaterialTheme.typography.bodySmall)
                if (profiles.size > 1) {
                    ChoiceChips(profiles.map { it.id }, profileId, { id -> profiles.firstOrNull { it.id == id }?.name ?: "" }, { profileId = it })
                }
                ChoiceChips(ReportRange.entries, range, { it.label }, { range = it })
            }
        },
        confirmButton = {
            TextButton(
                enabled = profile != null && !working,
                onClick = {
                    profile?.let {
                        savedName = DoctorReport.fileName(it, range.days, to)
                        saver.launch(savedName)
                    }
                },
            ) { Text(if (working) "Making…" else "Save PDF") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

/** Copies the saved report into the app's share folder under [name] and returns a link other apps can read. */
private suspend fun shareableCopy(context: android.content.Context, source: android.net.Uri, name: String): android.net.Uri =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val dir = java.io.File(context.cacheDir, "reports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = java.io.File(dir, name)
        context.contentResolver.openInputStream(source).use { input ->
            requireNotNull(input) { "The saved report can't be read" }
            file.outputStream().use { input.copyTo(it) }
        }
        androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
    }
