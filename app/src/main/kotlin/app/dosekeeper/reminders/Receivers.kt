package app.dosekeeper.reminders

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.dosekeeper.app
import app.dosekeeper.core.DoseStatus
import kotlinx.coroutines.launch

/** Runs [block] on the app scope while keeping the broadcast alive until it finishes. */
private fun BroadcastReceiver.runAsync(context: Context, block: suspend () -> Unit) {
    val pending = goAsync()
    context.app.appScope.launch {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DOSE_DUE) return
        val id = intent.getLongExtra(AlarmScheduler.EXTRA_MEDICINE_ID, 0L)
        if (id == 0L) return
        runAsync(context) { context.app.engine.onAlarm(id) }
    }

    companion object {
        const val ACTION_DOSE_DUE = "app.dosekeeper.DOSE_DUE"
    }
}

/** Taken, Snooze and Skip buttons on the alarm notification. */
class DoseActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(AlarmScheduler.EXTRA_MEDICINE_ID, 0L)
        val engine = context.app.engine
        runAsync(context) {
            when (intent.action) {
                ACTION_TAKEN -> engine.answer(id, DoseStatus.TAKEN)
                ACTION_SKIP -> engine.answer(id, DoseStatus.SKIPPED)
                ACTION_SNOOZE -> engine.snooze(id)
                ACTION_DISMISS_TEST -> context.app.notifier.cancelTest()
            }
        }
    }

    companion object {
        const val ACTION_TAKEN = "app.dosekeeper.TAKEN"
        const val ACTION_SKIP = "app.dosekeeper.SKIP"
        const val ACTION_SNOOZE = "app.dosekeeper.SNOOZE"
        const val ACTION_DISMISS_TEST = "app.dosekeeper.DISMISS_TEST"

        fun pending(context: Context, medicineId: Long, action: String): PendingIntent {
            val intent = Intent(context, DoseActionReceiver::class.java)
                .setAction(action)
                .putExtra(AlarmScheduler.EXTRA_MEDICINE_ID, medicineId)
            // Distinct request codes per medicine and action, so the buttons don't overwrite each other.
            val code = AlarmScheduler.requestCode(medicineId) * 8 + action.hashCode().and(7)
            return PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}

/** Reboot, app update, clock change, time zone change, or the exact-alarm permission being granted. */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WatchdogWorker.ensureScheduled(context)
        runAsync(context) { context.app.engine.refreshAll() }
    }
}
