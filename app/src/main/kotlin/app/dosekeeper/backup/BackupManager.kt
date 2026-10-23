package app.dosekeeper.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import app.dosekeeper.core.BackupCrypto
import app.dosekeeper.data.AppDatabase
import app.dosekeeper.data.DoseLog
import app.dosekeeper.data.Medicine
import app.dosekeeper.data.Profile
import app.dosekeeper.data.Settings
import app.dosekeeper.reminders.Notifier
import app.dosekeeper.reminders.ReminderEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * Backup to one JSON file the user picks (Downloads, Google Drive, anywhere the file picker
 * reaches), optionally password-protected, plus a weekly auto backup to a chosen folder.
 */
class BackupManager(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: Settings,
    private val engine: ReminderEngine,
) {
    class Summary(val profiles: Int, val medicines: Int, val doses: Int)

    suspend fun export(uri: Uri, password: String?): Summary = withContext(Dispatchers.IO) {
        val (json, summary) = buildJson()
        val bytes = wrap(json, password)
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
        summary
    }

    suspend fun restore(uri: Uri, password: String?): Summary = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        restoreBytes(bytes, password)
    }

    /** True when the file needs a password to open. */
    suspend fun isEncrypted(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        runCatching { JSONObject(String(bytes)).optString("format") == FORMAT_ENCRYPTED }.getOrDefault(false)
    }

    /** Weekly copy to the auto backup folder, keeping the newest [KEEP]. Never password-protected. */
    suspend fun autoBackupIfDue(force: Boolean = false) = withContext(Dispatchers.IO) {
        val folder = settings.autoBackupFolder?.let(Uri::parse) ?: return@withContext
        val due = System.currentTimeMillis() - settings.lastAutoBackupAt >= Duration.ofDays(7).toMillis()
        if (!force && !due) return@withContext
        try {
            val dir = DocumentFile.fromTreeUri(context, folder) ?: error("Backup folder is gone")
            val name = "dose-keeper-auto-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")) + ".json"
            val file = dir.createFile("application/json", name) ?: error("Couldn't write to the backup folder")
            val (json, _) = buildJson()
            context.contentResolver.openOutputStream(file.uri, "wt")!!.use { it.write(json.toString(2).toByteArray()) }
            dir.listFiles()
                .filter { it.name?.startsWith("dose-keeper-auto-") == true }
                .sortedByDescending { it.name }
                .drop(KEEP)
                .forEach { it.delete() }
            settings.lastAutoBackupAt = System.currentTimeMillis()
            settings.lastAutoBackupError = null
        } catch (e: Exception) {
            settings.lastAutoBackupError = e.message ?: e.javaClass.simpleName
            Notifier(context).showStatus(Notifier.BACKUP_NOTIFICATION_ID, "Auto backup failed", "Open Dose Keeper to pick the backup folder again.")
        }
    }

    private suspend fun buildJson(): Pair<JSONObject, Summary> {
        val profiles = db.profiles().getAll()
        val medicines = db.medicines().getAll()
        val doses = db.doseLogs().getAll()
        val json = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("settings", JSONObject().put("snoozeMinutes", settings.snoozeMinutes).put("missedWindowHours", settings.missedWindowHours))
            .put("profiles", JSONArray(profiles.map { it.toJson() }))
            .put("medicines", JSONArray(medicines.map { it.toJson() }))
            .put("doses", JSONArray(doses.map { it.toJson() }))
        return json to Summary(profiles.size, medicines.size, doses.size)
    }

    private fun wrap(json: JSONObject, password: String?): ByteArray {
        if (password.isNullOrEmpty()) return json.toString(2).toByteArray()
        val sealed = BackupCrypto.seal(json.toString().toByteArray(), password.toCharArray())
        val b64 = Base64.getEncoder()
        return JSONObject()
            .put("format", FORMAT_ENCRYPTED)
            .put("version", VERSION)
            .put("salt", b64.encodeToString(sealed.salt))
            .put("iv", b64.encodeToString(sealed.iv))
            .put("data", b64.encodeToString(sealed.data))
            .toString(2).toByteArray()
    }

    private suspend fun restoreBytes(bytes: ByteArray, password: String?): Summary {
        var json = JSONObject(String(bytes))
        if (json.optString("format") == FORMAT_ENCRYPTED) {
            requireNotNull(password) { "This backup needs its password" }
            val b64 = Base64.getDecoder()
            val sealed = BackupCrypto.Sealed(b64.decode(json.getString("salt")), b64.decode(json.getString("iv")), b64.decode(json.getString("data")))
            json = JSONObject(String(BackupCrypto.open(sealed, password.toCharArray())))
        }
        require(json.optString("format") == FORMAT) { "This isn't a Dose Keeper backup file" }
        require(json.optInt("version") <= VERSION) { "This backup is from a newer version of Dose Keeper. Update the app first." }

        val profiles = json.getJSONArray("profiles").objects().map(::profileFrom)
        val medicines = json.getJSONArray("medicines").objects().map(::medicineFrom)
        val doses = json.getJSONArray("doses").objects().map(::doseFrom)
        require(profiles.isNotEmpty()) { "The backup has no profiles" }

        engine.cancelAllAlarms(db.medicines().getAll())
        db.withTransaction {
            db.doseLogs().deleteAll()
            db.medicines().deleteAll()
            db.profiles().deleteAll()
            db.profiles().insertAll(profiles)
            db.medicines().insertAll(medicines)
            db.doseLogs().insertAll(doses)
        }
        json.optJSONObject("settings")?.let {
            settings.snoozeMinutes = it.optInt("snoozeMinutes", settings.snoozeMinutes)
            settings.missedWindowHours = it.optInt("missedWindowHours", settings.missedWindowHours)
        }
        engine.refreshAll()
        return Summary(profiles.size, medicines.size, doses.size)
    }

    companion object {
        const val FORMAT = "dose-keeper-backup"
        const val FORMAT_ENCRYPTED = "dose-keeper-backup-encrypted"
        const val VERSION = 1
        const val KEEP = 5
    }
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null
private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

private fun Profile.toJson() = JSONObject()
    .put("id", id).put("name", name).put("color", colorArgb).put("icon", icon).put("sortOrder", sortOrder)

private fun profileFrom(o: JSONObject) = Profile(
    id = o.getLong("id"), name = o.getString("name"), colorArgb = o.getInt("color"),
    icon = o.optString("icon", "person"), sortOrder = o.optInt("sortOrder", 0),
)

private fun Medicine.toJson() = JSONObject()
    .put("id", id).put("profileId", profileId).put("name", name).put("strength", strength)
    .put("form", form).put("note", note).put("schedule", schedule).put("status", status)
    .put("anchorAt", anchorAt ?: JSONObject.NULL).put("nextDueAt", nextDueAt ?: JSONObject.NULL)
    .put("soundUri", soundUri ?: JSONObject.NULL).put("createdAt", createdAt)

private fun medicineFrom(o: JSONObject) = Medicine(
    id = o.getLong("id"), profileId = o.getLong("profileId"), name = o.getString("name"),
    strength = o.optString("strength"), form = o.optString("form", "OTHER"), note = o.optString("note"),
    schedule = o.getString("schedule"), status = o.optString("status", "ACTIVE"),
    anchorAt = o.optLongOrNull("anchorAt"), nextDueAt = o.optLongOrNull("nextDueAt"), snoozedUntil = null,
    soundUri = o.optStringOrNull("soundUri"), createdAt = o.optLong("createdAt", System.currentTimeMillis()),
)

private fun DoseLog.toJson() = JSONObject()
    .put("id", id).put("medicineId", medicineId).put("dueAt", dueAt ?: JSONObject.NULL)
    .put("status", status).put("actionAt", actionAt)

private fun doseFrom(o: JSONObject) = DoseLog(
    id = o.getLong("id"), medicineId = o.getLong("medicineId"), dueAt = o.optLongOrNull("dueAt"),
    status = o.getString("status"), actionAt = o.getLong("actionAt"),
)
