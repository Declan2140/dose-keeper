package app.dosekeeper.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.merge

/**
 * The app's one set of movements, so everything feels like it belongs together.
 *
 * Two rules for anything animated:
 * - Move only scale, position and opacity, set inside `graphicsLayer { }`. Never animate a width,
 *   height, padding or color, because those make the screen measure or rebuild itself every frame.
 * - Use these springs rather than fixed timers. A spring picks up from wherever it is, so a second
 *   tap halfway through reverses smoothly instead of jumping.
 *
 * When the phone's "Remove animations" setting is on ([LocalReducedMotion]), nothing slides, grows
 * or bounces. Changes fade over [FADE_MS] instead.
 */
object Motion {
    const val FADE_MS = 150

    /** A finger going down on a card: quick, no bounce. */
    val Press: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 1500f)

    /** Letting go: back to full size with a small bounce. */
    val Release: SpringSpec<Float> = spring(dampingRatio = 0.6f, stiffness = 800f)

    /** The green circle filling in. */
    val Fill: SpringSpec<Float> = spring(dampingRatio = 0.5f, stiffness = 400f)

    /** The tick popping in, a little past full size and back. */
    val Pop: SpringSpec<Float> = spring(dampingRatio = 0.42f, stiffness = 500f)

    /** Rows gliding to a new place in a list. */
    fun <T> glide(threshold: T? = null): SpringSpec<T> = spring(dampingRatio = 0.85f, stiffness = 380f, visibilityThreshold = threshold)

    /** Screens and days sliding in. Barely any bounce. */
    fun <T> smooth(threshold: T? = null): SpringSpec<T> = spring(dampingRatio = 0.9f, stiffness = 400f, visibilityThreshold = threshold)

    /** The short fade used for small changes, and for everything when animations are removed. */
    fun <T> fade(): TweenSpec<T> = tween(FADE_MS)

    /** How much a card shrinks under a finger. */
    const val PRESSED_SCALE = 0.97f

    /** How long after a tap the tick lands, which is when the Taken buzz is felt. */
    const val TICK_LANDS_MS = 90L
}

/** True when the phone's "Remove animations" accessibility setting is on. */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Reads the phone's "Remove animations" setting (it sets the animation speed to zero). */
fun Context.animationsRemoved(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/**
 * Keeps the app's animations running at normal speed whatever the phone's animation speed is.
 * Without it, "Remove animations" makes every change snap, fades included. The app reads the
 * setting itself and swaps movement for short fades instead.
 */
object FullSpeed : MotionDurationScale {
    override val scaleFactor: Float = 1f
}

/**
 * The scale of a card while it's being touched. It dips as a finger lands on any of [a] or [b] and
 * springs back on release. A quick tap still gets a visible dip.
 */
@Composable
fun rememberPressScale(a: InteractionSource, b: InteractionSource = a): Animatable<Float, AnimationVector1D> {
    val reduced = LocalReducedMotion.current
    val scale = remember { Animatable(1f) }
    LaunchedEffect(a, b, reduced) {
        if (reduced) {
            scale.snapTo(1f)
            return@LaunchedEffect
        }
        val touches = if (a === b) a.interactions else merge(a.interactions, b.interactions)
        touches.collectLatest { touch ->
            when (touch) {
                is PressInteraction.Press -> scale.animateTo(Motion.PRESSED_SCALE, Motion.Press)
                is PressInteraction.Release -> {
                    if (scale.value > 0.985f) scale.animateTo(Motion.PRESSED_SCALE, Motion.Press)
                    scale.animateTo(1f, Motion.Release)
                }
                is PressInteraction.Cancel -> scale.animateTo(1f, Motion.Release)
            }
        }
    }
    return scale
}

/**
 * Movement shared by everything in the Today list: the stagger as the app opens, and the slide
 * when the day changes.
 */
class ListMotion(entered: Boolean) {
    /** 0 while the app-open entrance hasn't started, 1 once every row is in. */
    val entrance = Animatable(if (entered) 1f else 0f)

    /** How far the day's rows are pushed sideways, as a share of their width. */
    val slide = Animatable(0f)

    /** The day's rows fade out and in around a day change. */
    val alpha = Animatable(1f)

    /** How far in row number [index] is, from 0 to 1. Rows after the sixth arrive together. */
    fun entered(index: Int): Float {
        val elapsed = entrance.value * ENTRANCE_MS - index.coerceIn(0, STAGGERED_ROWS) * STAGGER_MS
        return FastOutSlowInEasing.transform((elapsed / ROW_MS).coerceIn(0f, 1f))
    }

    companion object {
        const val STAGGER_MS = 35f
        const val ROW_MS = 280f
        const val STAGGERED_ROWS = 6
        const val ENTRANCE_MS = STAGGERED_ROWS * STAGGER_MS + ROW_MS
    }
}

/**
 * Applies [motion] to one row. [travel] is how far it slides on a day change, as a share of its
 * width; rows that belong to every day pass [changesWithDay] false and only take part in the entrance.
 */
fun Modifier.listMotion(motion: ListMotion, index: Int, reduced: Boolean, changesWithDay: Boolean = true, travel: Float = 1f): Modifier =
    graphicsLayer {
        val entered = motion.entered(index)
        alpha = entered * (if (changesWithDay) motion.alpha.value else 1f)
        if (!reduced) {
            translationY = (1f - entered) * 12.dp.toPx()
            if (changesWithDay) translationX = motion.slide.value * size.width * travel
        }
    }
