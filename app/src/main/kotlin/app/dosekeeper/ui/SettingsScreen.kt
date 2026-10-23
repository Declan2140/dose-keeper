package app.dosekeeper.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.dosekeeper.BuildConfig
import app.dosekeeper.app
import app.dosekeeper.core.BackupCrypto
import app.dosekeeper.core.DoseCalculator
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

@Composable
fun SettingsScreen(onOpenProfiles: () -> Unit, onOpenReliability: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val settingsTick by app.settings.changes.collectAsState()
    val unlocked by app.unlock.unlocked.collectAsState()
    val price by app.unlock.price.collectAsState()
    val unlockMessage by app.unlock.message.collectAsState()
    val checks = rememberChecks()

    var passwordFor by remember { mutableStateOf<PasswordAsk?>(null) }
    var pendingExportPassword by remember { mutableStateOf<String?>(null) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val password = pendingExportPassword
        scope.launch {
            runCatching { app.backup.export(uri, password) }
                .onSuccess { toast("Backed up ${it.medicines} medicines and ${it.doses} history entries.") }
                .onFailure { toast("Backup failed: ${it.message}") }
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val encrypted = runCatching { app.backup.isEncrypted(uri) }.getOrDefault(false)
            passwordFor = PasswordAsk.Restore(uri, needsPassword = encrypted)
        }
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        app.settings.autoBackupFolder = uri.toString()
        scope.launch {
            app.backup.autoBackupIfDue(force = true)
            toast(app.settings.lastAutoBackupError?.let { "Auto backup failed: $it" } ?: "Auto backup is on. A copy is saved every week.")
        }
    }

    LaunchedEffect(unlockMessage) {
        unlockMessage?.let { toast(it); app.unlock.clearMessage() }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        SectionTitle("Reminders")
        val failing = checks.count { !it.ok }
        NavRow(
            "Reliability checklist",
            if (failing == 0) "All set" else "$failing ${if (failing == 1) "setting needs" else "settings need"} fixing",
            onOpenReliability,
        )
        NavRow("People and pets", "Who you're tracking medicines for", onOpenProfiles)

        // settingsTick makes these re-read after a change.
        val snooze = settingsTick.let { app.settings.snoozeMinutes }
        val window = settingsTick.let { app.settings.missedWindowHours }
        Text("Snooze length", Modifier.padding(top = 12.dp))
        ChoiceChips(DoseCalculator.SNOOZE_CHOICES, snooze, { "$it min" }, { app.settings.snoozeMinutes = it })
        Text("Unanswered alarms ring again after this long.", style = MaterialTheme.typography.bodySmall)
        Text("Count a dose as missed after", Modifier.padding(top = 12.dp))
        ChoiceChips(listOf(1, 2, 3, 4, 6), window, { "$it h" }, { app.settings.missedWindowHours = it })

        SectionTitle("Backup")
        Text("Your data stays on this phone. Save a backup file somewhere safe, such as Google Drive, so you can restore it on a new phone.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { passwordFor = PasswordAsk.Export }, Modifier.weight(1f)) { Text("Back up now") }
            OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }, Modifier.weight(1f)) { Text("Restore") }
        }
        val folder = settingsTick.let { app.settings.autoBackupFolder }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Weekly auto backup")
                val last = app.settings.lastAutoBackupAt.takeIf { it > 0 }?.let { "Last: ${Instant.ofEpochMilli(it).friendlyDateTime()}" }
                val err = app.settings.lastAutoBackupError
                Text(
                    when {
                        folder == null -> "Off. Keeps the last 5 copies in a folder you pick."
                        err != null -> "Failed: $err"
                        else -> last ?: "On"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (err != null) Overdue else MaterialTheme.colorScheme.onSurface,
                )
            }
            Switch(folder != null, { on ->
                if (on) folderLauncher.launch(null)
                else {
                    folder?.let { runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
                    app.settings.autoBackupFolder = null
                }
            })
        }

        SectionTitle("Unlock")
        if (unlocked) {
            Text("Unlocked. Thank you for supporting Dose Keeper!")
        } else {
            Text("One payment of ${price ?: "$5"}, no subscription. Unlocks unlimited people and pets, full history, custom alarm sounds, and future features like refills and PDF export.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { context.findActivity()?.let(app.unlock::buy) }, Modifier.weight(1f)) { Text("Unlock for ${price ?: "$5"}") }
                OutlinedButton(onClick = app.unlock::restore, Modifier.weight(1f)) { Text("Restore purchase") }
            }
        }
        if (BuildConfig.DEBUG) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Test build: pretend unlocked", Modifier.weight(1f))
                Switch(settingsTick.let { app.settings.debugUnlock }, { app.unlock.setDebugUnlock(it) })
            }
        }

        SectionTitle("About")
        Text(
            "Dose Keeper records and reminds only. It doesn't give medical advice. Always follow your doctor's, pharmacist's or vet's instructions.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("No account, no ads, no tracking. Nothing leaves your phone unless you save a backup.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }

    when (val ask = passwordFor) {
        null -> Unit
        PasswordAsk.Export -> PasswordDialog(
            title = "Protect the backup?",
            body = "Add a password if you'll store the file somewhere others can see. You'll need it to restore. Leave empty for no password.",
            confirm = "Choose where to save",
            onDismiss = { passwordFor = null },
            onConfirm = { pw ->
                passwordFor = null
                pendingExportPassword = pw.ifEmpty { null }
                exportLauncher.launch("dose-keeper-backup-${LocalDate.now()}.json")
            },
        )
        is PasswordAsk.Restore -> PasswordDialog(
            title = "Restore this backup?",
            body = "This replaces everything on this phone with the backup." + if (ask.needsPassword) " Enter the backup's password." else "",
            confirm = "Restore",
            showField = ask.needsPassword,
            onDismiss = { passwordFor = null },
            onConfirm = { pw ->
                passwordFor = null
                scope.launch {
                    runCatching { app.backup.restore(ask.uri, pw.ifEmpty { null }) }
                        .onSuccess { toast("Restored ${it.medicines} medicines and ${it.doses} history entries. Alarms are set.") }
                        .onFailure { toast(if (it is BackupCrypto.WrongPasswordException) "Wrong password." else "Restore failed: ${it.message}") }
                }
            },
        )
    }
}

private sealed interface PasswordAsk {
    data object Export : PasswordAsk
    data class Restore(val uri: Uri, val needsPassword: Boolean) : PasswordAsk
}

@Composable
private fun PasswordDialog(title: String, body: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit, showField: Boolean = true) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(body)
                if (showField) {
                    OutlinedTextField(
                        pw, { pw = it }, label = { Text("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(pw) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
}
