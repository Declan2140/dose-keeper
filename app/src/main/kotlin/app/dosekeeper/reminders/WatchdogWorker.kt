package app.dosekeeper.reminders

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.dosekeeper.app
import java.util.concurrent.TimeUnit

/**
 * Once a day: re-check that every upcoming dose has an alarm and repair any that are missing,
 * then run the weekly auto backup if one is due.
 */
class WatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.app
        app.engine.refreshAll()
        app.backup.autoBackupIfDue()
        return Result.success()
    }

    companion object {
        private const val NAME = "watchdog"

        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<WatchdogWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
