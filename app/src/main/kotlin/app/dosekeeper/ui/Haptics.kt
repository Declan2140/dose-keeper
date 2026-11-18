package app.dosekeeper.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Small buzzes for dose actions. They follow the phone's own touch feedback setting, so
 * anyone who turned vibration off for touches feels nothing.
 */
class Haptics(private val view: View) {
    /** A firm, satisfying tap when a dose is marked taken. */
    fun taken() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)

    /** A softer, different tap for skip, snooze and undo. */
    fun soft() = perform(HapticFeedbackConstants.CONTEXT_CLICK)

    /** A faint tick when a swipe passes the point where letting go will act. */
    fun tick() = perform(HapticFeedbackConstants.CLOCK_TICK)

    private fun perform(constant: Int) {
        view.performHapticFeedback(constant)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}
