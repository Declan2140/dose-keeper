package app.dosekeeper.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import app.dosekeeper.ui.MainActivity

/**
 * Sets one exact alarm per medicine (its next dose). Uses [AlarmManager.setAlarmClock], which
 * fires on time even in Doze and is the call phone makers are least likely to delay.
 */
class AlarmScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    /** False when the user has turned off "Alarms and reminders" for the app. */
    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    fun set(medicineId: Long, atMillis: Long) {
        val operation = alarmIntent(medicineId)
        if (canScheduleExact()) {
            val show = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, show), operation)
        } else {
            // Without the permission this may arrive a few minutes late. The reliability checklist flags it.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
        }
    }

    fun cancel(medicineId: Long) {
        alarmManager.cancel(alarmIntent(medicineId))
    }

    /** Fires the sample reminder used by the "Test alarm" button. */
    fun setTest(atMillis: Long) = set(TEST_ID, atMillis)

    private fun alarmIntent(medicineId: Long): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_DOSE_DUE)
            .putExtra(EXTRA_MEDICINE_ID, medicineId)
        return PendingIntent.getBroadcast(
            context, requestCode(medicineId), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val EXTRA_MEDICINE_ID = "medicineId"
        const val TEST_ID = -1L

        fun requestCode(medicineId: Long): Int = medicineId.toInt()
    }
}
