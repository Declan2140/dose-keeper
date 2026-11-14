package app.dosekeeper

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import app.dosekeeper.backup.BackupManager
import app.dosekeeper.billing.UnlockManager
import app.dosekeeper.data.AppDatabase
import app.dosekeeper.data.Profile
import app.dosekeeper.data.Settings
import app.dosekeeper.reminders.AlarmScheduler
import app.dosekeeper.reminders.Notifier
import app.dosekeeper.reminders.ReminderEngine
import app.dosekeeper.reminders.WatchdogWorker
import app.dosekeeper.report.DoctorReport
import app.dosekeeper.widget.NextDoseWidget
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DoseKeeperApp : Application() {

    /** Lives as long as the process. Receivers hand work to it so it isn't cut off. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db by lazy { AppDatabase.build(this) }
    val settings by lazy { Settings(this) }
    val notifier by lazy { Notifier(this) }
    val alarms by lazy { AlarmScheduler(this) }
    val engine by lazy {
        ReminderEngine(db, settings, alarms, notifier).also { engine ->
            // Whatever changes a dose (the app, a notification button, an alarm, the widget itself)
            // redraws the home screen widget straight away.
            engine.onChanged = { appScope.launch { runCatching { NextDoseWidget().updateAll(this@DoseKeeperApp) } } }
        }
    }
    val backup by lazy { BackupManager(this, db, settings, engine) }
    val unlock by lazy { UnlockManager(this, settings, appScope) }
    val report by lazy { DoctorReport(this, db) }

    override fun onCreate() {
        super.onCreate()
        notifier.createChannels()
        WatchdogWorker.ensureScheduled(this)
        unlock.connect()
        // Unlocking the phone puts medicine names back on alarms that rang while it was locked.
        // Android only delivers this to a running app, which this is while an alarm is ringing.
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    appScope.launch { engine.revealRinging() }
                }
            },
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        appScope.launch {
            if (db.profiles().count() == 0) {
                db.profiles().insert(Profile(name = "Me", colorArgb = PROFILE_COLORS.first(), icon = "person"))
            }
            engine.refreshAll()
        }
    }

    companion object {
        /** Colors a profile can pick. Chosen to stay readable as text on white and as a dot. */
        val PROFILE_COLORS = listOf(
            0xFF1F6F5C.toInt(), 0xFF2B5DB8.toInt(), 0xFF9C3D9A.toInt(),
            0xFFC0392B.toInt(), 0xFFB86E00.toInt(), 0xFF4E6B1F.toInt(), 0xFF5D4037.toInt(),
        )
    }
}

val android.content.Context.app: DoseKeeperApp get() = applicationContext as DoseKeeperApp
