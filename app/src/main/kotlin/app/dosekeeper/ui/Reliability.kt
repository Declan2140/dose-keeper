package app.dosekeeper.ui

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.dosekeeper.app

/** One line of the reliability checklist. */
data class Check(val title: String, val ok: Boolean, val explain: String, val fixLabel: String, val fix: () -> Unit)

/** Re-reads every setting each time the user comes back from the system settings screens. */
@Composable
fun rememberChecks(): List<Check> {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    return remember(tick) { buildChecks(context) { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) } }
}

private fun buildChecks(context: Context, requestNotifications: () -> Unit): List<Check> {
    val app = context.app
    val pkg = Uri.parse("package:${context.packageName}")
    fun open(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    val appSettings = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)) }
    val checks = mutableListOf<Check>()

    val notificationsOn = app.notifier.notificationsAllowed() && app.notifier.alarmChannelEnabled()
    checks += Check(
        "Notifications", notificationsOn,
        "Reminders arrive as notifications. Without them nothing can ring.",
        "Turn on",
    ) {
        val neverAsked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !app.notifier.notificationsAllowed() && !app.settings.onboardingDone
        if (neverAsked) requestNotifications()
        else open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        checks += Check(
            "Alarms and reminders", app.alarms.canScheduleExact(),
            "Lets reminders ring at the exact minute, even when the phone is asleep.",
            "Allow",
        ) { open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)) }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        checks += Check(
            "Full-screen alarm", app.notifier.canUseFullScreen(),
            "Shows the alarm over the lock screen, like an alarm clock. Without it you get a loud notification instead.",
            "Allow",
        ) { open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)) }
    }

    val power = context.getSystemService(PowerManager::class.java)
    checks += Check(
        "Battery: unrestricted", power.isIgnoringBatteryOptimizations(context.packageName),
        "Stops the phone from putting the app to sleep. On the app's settings page, open Battery and choose Unrestricted.",
        "Open settings", appSettings,
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val restricted = context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
        if (restricted) {
            checks += Check(
                "Background activity", false,
                "Background use is restricted for this app, so reminders can be blocked. Allow background activity in the app's battery settings.",
                "Open settings", appSettings,
            )
        }
    }
    return checks
}

@Composable
fun CheckRow(check: Check) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (check.ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
                contentDescription = if (check.ok) "OK" else "Needs fixing",
                tint = if (check.ok) MaterialTheme.colorScheme.secondary else Overdue,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(check.title, style = MaterialTheme.typography.titleSmall)
                Text(check.explain, style = MaterialTheme.typography.bodySmall)
            }
            if (!check.ok) {
                Spacer(Modifier.width(8.dp))
                Button(onClick = check.fix) { Text(check.fixLabel) }
            }
        }
    }
}

private data class MakerHelp(val name: String, val matches: (String) -> Boolean, val steps: List<String>)

private val MAKER_HELP = listOf(
    MakerHelp("Samsung", { it.contains("samsung") }, listOf(
        "Open Settings, then Apps, then Dose Keeper. Tap Battery and choose Unrestricted.",
        "Open Settings, then Battery (or Device care, then Battery), then Background usage limits.",
        "Tap Never sleeping apps, tap +, and add Dose Keeper.",
        "Check that Dose Keeper is not listed under Sleeping apps or Deep sleeping apps.",
        "Optional: turn off \"Put unused apps to sleep\" on the same screen.",
    )),
    MakerHelp("Xiaomi, Redmi, POCO", { it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") }, listOf(
        "Open Settings, then Apps, then Manage apps, then Dose Keeper.",
        "Turn on Autostart.",
        "Tap Battery saver and choose No restrictions.",
        "Open your recent apps, press and hold Dose Keeper, and tap the lock icon.",
    )),
    MakerHelp("OnePlus, OPPO, realme", { it.contains("oneplus") || it.contains("oppo") || it.contains("realme") }, listOf(
        "Open Settings, then Apps, then Dose Keeper, then Battery usage.",
        "Turn on Allow background activity (or choose Unrestricted).",
        "Open Settings, then Battery, then Battery optimization. Set Dose Keeper to Don't optimize.",
        "Open your recent apps, tap the menu on Dose Keeper, and choose Lock.",
    )),
)

@Composable
fun ReliabilityScreen() {
    val context = LocalContext.current
    val checks = rememberChecks()
    val maker = Build.MANUFACTURER.lowercase()
    var expanded by remember { mutableStateOf(MAKER_HELP.firstOrNull { it.matches(maker) }?.name) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val allOk = checks.all { it.ok }
        Text(
            if (allOk) "Everything is set. Reminders should ring on time." else "Fix the red items so reminders ring every time.",
            style = MaterialTheme.typography.bodyLarge,
        )
        checks.forEach { CheckRow(it) }

        OutlinedButton(onClick = {
            context.app.alarms.setTest(System.currentTimeMillis() + 10_000)
            Toast.makeText(context, "Test alarm in 10 seconds. Lock your phone to check it.", Toast.LENGTH_LONG).show()
        }, modifier = Modifier.fillMaxWidth()) { Text("Test alarm in 10 seconds") }

        SectionTitle("Phone maker settings")
        Text(
            "Some phones close background apps to save battery, even with the settings above. Follow the steps for your phone.",
            style = MaterialTheme.typography.bodySmall,
        )
        MAKER_HELP.forEach { help ->
            Card(Modifier.fillMaxWidth().clickable { expanded = if (expanded == help.name) null else help.name }) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(help.name + if (help.matches(maker)) " (your phone)" else "", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Icon(if (expanded == help.name) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                    }
                    if (expanded == help.name) {
                        help.steps.forEachIndexed { i, step -> Text("${i + 1}. $step", Modifier.padding(top = 8.dp)) }
                    }
                }
            }
        }
    }
}
