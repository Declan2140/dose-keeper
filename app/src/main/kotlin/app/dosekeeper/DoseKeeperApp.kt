package app.dosekeeper

import android.app.Application
import app.dosekeeper.backup.BackupManager
import app.dosekeeper.billing.UnlockManager
import app.dosekeeper.data.AppDatabase
import app.dosekeeper.data.Profile
import app.dosekeeper.data.Settings
import app.dosekeeper.reminders.AlarmScheduler
import app.dosekeeper.reminders.Notifier
import app.dosekeeper.reminders.ReminderEngine
import app.dosekeeper.reminders.WatchdogWorker
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
    val engine by lazy { ReminderEngine(db, settings, alarms, notifier) }
    val backup by lazy { BackupManager(this, db, settings, engine) }
    val unlock by lazy { UnlockManager(this, settings, appScope) }

    override fun onCreate() {
        super.onCreate()
        notifier.createChannels()
        WatchdogWorker.ensureScheduled(this)
        unlock.connect()
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
