package app.dosekeeper.reminders

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.dosekeeper.R
import app.dosekeeper.data.Medicine
import app.dosekeeper.data.Profile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Shows the dose alarm notification, which opens the full-screen alarm when allowed. */
class Notifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val alarms = NotificationChannel(CHANNEL_ALARMS, context.getString(R.string.channel_alarms), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.channel_alarms_desc)
            setSound(
                alarmSound,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 400, 600, 400, 600)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        val status = NotificationChannel(CHANNEL_STATUS, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.channel_status_desc)
        }
        manager.createNotificationChannels(listOf(alarms, status))
    }

    fun notificationsAllowed(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun alarmChannelEnabled(): Boolean =
        manager.getNotificationChannel(CHANNEL_ALARMS)?.importance != NotificationManager.IMPORTANCE_NONE

    fun canUseFullScreen(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || manager.canUseFullScreenIntent()

    /**
     * Rings until answered: the notification is insistent (its sound loops), and the alarm also
     * re-fires every snooze interval in case the user silences it without answering.
     */
    fun showDose(medicine: Medicine, profile: Profile?, dueAt: Instant) {
        if (!hasPostPermission()) return
        val id = notificationId(medicine.id)
        val fullScreen = PendingIntent.getActivity(
            context, id, AlarmActivity.intent(context, medicine.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val who = profile?.name?.let { "$it · " } ?: ""
        val notification = NotificationCompat.Builder(context, CHANNEL_ALARMS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(medicine.displayName)
            .setContentText("${who}due ${formatTime(dueAt)}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOf("${who}due ${formatTime(dueAt)}", medicine.note).filter { it.isNotBlank() }.joinToString("\n")))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .addAction(0, "Taken", DoseActionReceiver.pending(context, medicine.id, DoseActionReceiver.ACTION_TAKEN))
            .addAction(0, "Snooze", DoseActionReceiver.pending(context, medicine.id, DoseActionReceiver.ACTION_SNOOZE))
            .addAction(0, "Skip", DoseActionReceiver.pending(context, medicine.id, DoseActionReceiver.ACTION_SKIP))
            .build()
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        manager.notify(id, notification)
    }

    fun showTest() {
        if (!hasPostPermission()) return
        val fullScreen = PendingIntent.getActivity(
            context, TEST_NOTIFICATION_ID, AlarmActivity.intent(context, AlarmScheduler.TEST_ID),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ALARMS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Test alarm")
            .setContentText("Your reminders are working. Tap Done to stop.")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .addAction(0, "Done", DoseActionReceiver.pending(context, AlarmScheduler.TEST_ID, DoseActionReceiver.ACTION_DISMISS_TEST))
            .build()
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        manager.notify(TEST_NOTIFICATION_ID, notification)
    }

    fun showStatus(id: Int, title: String, text: String) {
        if (!hasPostPermission()) return
        val open = PendingIntent.getActivity(
            context, id, Intent(context, app.dosekeeper.ui.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            id,
            NotificationCompat.Builder(context, CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun cancelDose(medicineId: Long) = manager.cancel(notificationId(medicineId))
    fun cancelTest() = manager.cancel(TEST_NOTIFICATION_ID)

    private fun hasPostPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun formatTime(instant: Instant): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(instant.atZone(ZoneId.systemDefault()))

    companion object {
        const val CHANNEL_ALARMS = "dose_alarms"
        const val CHANNEL_STATUS = "status"
        const val TEST_NOTIFICATION_ID = 999_999
        const val BACKUP_NOTIFICATION_ID = 999_998

        fun notificationId(medicineId: Long): Int = medicineId.toInt()
    }
}
