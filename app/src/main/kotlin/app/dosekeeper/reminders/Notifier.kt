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
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.dosekeeper.R
import app.dosekeeper.core.Supply
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
        val alarms = alarmChannel(CHANNEL_ALARMS, context.getString(R.string.channel_alarms), alarmSound)
        val status = NotificationChannel(CHANNEL_STATUS, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.channel_status_desc)
        }
        manager.createNotificationChannels(listOf(alarms, status))
    }

    private fun alarmChannel(id: String, name: String, sound: Uri?) =
        NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.channel_alarms_desc)
            setSound(
                sound,
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

    /**
     * The channel to ring a medicine's alarm on. Android fixes a channel's sound once it exists,
     * so each custom sound gets its own channel. If the chosen sound can't be opened any more
     * (deleted, or on removed storage) the normal alarm channel is used, so it never rings silent.
     */
    private fun channelFor(medicine: Medicine): String {
        val uri = medicine.soundUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return CHANNEL_ALARMS
        if (!soundPlayable(uri)) return CHANNEL_ALARMS
        val id = CHANNEL_ALARMS + "_" + Integer.toHexString(uri.toString().hashCode())
        if (manager.getNotificationChannel(id) == null) {
            val title = soundTitle(context, uri) ?: "custom sound"
            manager.createNotificationChannel(alarmChannel(id, "${context.getString(R.string.channel_alarms)} ($title)", uri))
        }
        return id
    }

    private fun soundPlayable(uri: Uri): Boolean =
        runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)

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
        val notification = NotificationCompat.Builder(context, channelFor(medicine))
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

    /** "Running low on Lisinopril: about 5 days left." Opens the app, where Today has a Refilled button. */
    fun showLowSupply(medicine: Medicine, outlook: Supply.Outlook, profile: Profile?) {
        val left = formatAmount(medicine.supplyLeft ?: 0.0) + " " + medicine.medForm.unit
        val text = when (val days = outlook.daysLeft) {
            null -> "$left left."
            0L -> "$left left. It runs out today."
            1L -> "$left left, about 1 day's worth."
            else -> "$left left, about $days days' worth."
        }
        val who = profile?.name?.let { " ($it)" } ?: ""
        showStatus(lowSupplyId(medicine.id), "Time to refill ${medicine.name}$who", "$text Order a refill soon.")
    }

    fun cancelLowSupply(medicineId: Long) = manager.cancel(lowSupplyId(medicineId))

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
        fun lowSupplyId(medicineId: Long): Int = 1_000_000 + medicineId.toInt()

        /** 30, 12.5, 0.25: no trailing zeros. */
        fun formatAmount(amount: Double): String =
            if (amount % 1.0 == 0.0) amount.toLong().toString() else "%.2f".format(amount).trimEnd('0').trimEnd('.')

        /** The sound's display name, like "Morning Glory", or null if it can't be read. */
        fun soundTitle(context: Context, uri: Uri): String? =
            runCatching { RingtoneManager.getRingtone(context, uri)?.getTitle(context) }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
