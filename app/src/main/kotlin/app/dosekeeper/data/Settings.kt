package app.dosekeeper.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Duration

/** Small app settings, kept in SharedPreferences so Android's own backup includes them. */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _changes = MutableStateFlow(0)

    /** Bumps whenever a setting changes, so screens can recompose. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _changes.value++
    }

    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboardingDone", false)
        set(value) = edit { putBoolean("onboardingDone", value) }

    /** How long a snooze lasts, and how often an unanswered alarm repeats. */
    var snoozeMinutes: Int
        get() = prefs.getInt("snoozeMinutes", 10)
        set(value) = edit { putInt("snoozeMinutes", value) }

    var missedWindowHours: Int
        get() = prefs.getInt("missedWindowHours", 2)
        set(value) = edit { putInt("missedWindowHours", value) }

    val missedWindow: Duration get() = Duration.ofHours(missedWindowHours.toLong())

    /** Last known purchase state, so the app works offline. Google Play confirms it when reachable. */
    var unlockedCache: Boolean
        get() = prefs.getBoolean("unlocked", false)
        set(value) = edit { putBoolean("unlocked", value) }

    /** Debug builds only: pretend the unlock was bought, for testing without a Play account. */
    var debugUnlock: Boolean
        get() = prefs.getBoolean("debugUnlock", false)
        set(value) = edit { putBoolean("debugUnlock", value) }

    var autoBackupFolder: String?
        get() = prefs.getString("autoBackupFolder", null)
        set(value) = edit { putString("autoBackupFolder", value) }

    var lastAutoBackupAt: Long
        get() = prefs.getLong("lastAutoBackupAt", 0)
        set(value) = edit { putLong("lastAutoBackupAt", value) }

    var lastAutoBackupError: String?
        get() = prefs.getString("lastAutoBackupError", null)
        set(value) = edit { putString("lastAutoBackupError", value) }

    companion object {
        const val FILE = "settings"
    }
}
