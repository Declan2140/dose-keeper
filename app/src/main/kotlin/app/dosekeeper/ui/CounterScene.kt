package app.dosekeeper.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.dosekeeper.data.MedForm

/** The container drawn on the counter for each medicine. */
enum class Bottle(val widthDp: Float, val heightDp: Float) {
    PILL(66f, 110f), LIQUID(60f, 120f), DROPPER(44f, 84f),
    INHALER(48f, 92f), PEN(26f, 120f), TUBE(46f, 100f), BOX(70f, 64f);

    companion object {
        fun of(form: MedForm): Bottle = when (form) {
            MedForm.PILL -> PILL
            MedForm.LIQUID -> LIQUID
            MedForm.DROPS -> DROPPER
            MedForm.INHALER -> INHALER
            MedForm.INJECTION -> PEN
            MedForm.CREAM -> TUBE
            MedForm.OTHER -> BOX
        }
    }
}

/** One container on the counter. [key] keeps it the same bottle while others come and go. */
data class CounterItem(val key: Long, val bottle: Bottle)

/** Line colors for the scene, light or dark. */
private class SceneColors(val tile: Color, val counter: Color, val counterEdge: Color, val bottle: Color, val outline: Color, val label: Color, val ridge: Color)

private val lightScene = SceneColors(
    tile = Color(0xFFEEF0F2), counter = Color(0xFFF1F2F4), counterEdge = Color(0xFFDADDE2),
    bottle = Color.White, outline = Color(0xFFB8BEC6), label = Color(0xFFF3F4F6), ridge = Color(0xFFDADDE2),
)
private val darkScene = SceneColors(
    tile = Color(0xFF1D2126), counter = Color(0xFF1A1E23), counterEdge = Color(0xFF2C3138),
    bottle = Ink, outline = Color(0xFF343A42), label = Color(0xFF1B1F24), ridge = Color(0xFF2A2F36),
)

/**
 * A faint line drawing of a bathroom counter against a tiled wall, with one container for each
 * medicine in [items], lined up from the right. More than [maxShown] shows a "+N more" note.
 *
 * When a medicine leaves [items] its container lifts off the counter and fades, and the others
 * slide over to close the gap. One that comes back drops into place.
 *
 * [counterHeight] is the strip of counter at the bottom; [scale] enlarges the bottles (the alarm
 * screen draws one big one); [endPadding] is the gap between the last bottle and the right edge.
 */
@Composable
fun CounterScene(
    items: List<CounterItem>,
    modifier: Modifier = Modifier,
    dark: Boolean = false,
    scale: Float = 1f,
    counterHeight: Dp = 24.dp,
    endPadding: Dp = 20.dp,
    tileSize: Dp = 48.dp,
    maxShown: Int = 3,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val colors = if (dark) darkScene else lightScene
    val shown = items.take(maxShown)
    val extra = items.size - shown.size
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val counterTop = size.height - counterHeight.toPx()
            drawTiles(colors, tileSize.toPx(), counterTop)
            drawRect(colors.counter, topLeft = Offset(0f, counterTop), size = Size(size.width, size.height - counterTop))
            drawLine(colors.counterEdge, Offset(0f, counterTop), Offset(size.width, counterTop), strokeWidth = 1.5.dp.toPx())
        }
        AnimatedBottles(
            shown, colors, scale,
            Modifier.align(Alignment.BottomEnd).padding(end = endPadding, bottom = counterHeight),
        )
        if (extra > 0) {
            Text(
                "+$extra more",
                style = MaterialTheme.typography.labelSmall,
                color = if (dark) Color(0xFF8A919B) else Muted,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = endPadding, bottom = 3.dp),
            )
        }
        content()
    }
}

/**
 * The row of containers, lined up from the right. Each one is placed by sliding it (never by
 * resizing anything), so when one leaves the others glide over to close the gap. A container that
 * leaves stays drawn where it stood until it has lifted away.
 */
@Composable
private fun AnimatedBottles(items: List<CounterItem>, colors: SceneColors, scale: Float, modifier: Modifier) {
    val reduced = LocalReducedMotion.current
    val display = remember { mutableStateListOf<CounterItem>().apply { addAll(items) } }
    val initialKeys = remember { items.map { it.key }.toSet() }

    LaunchedEffect(items) {
        val merged = mergeKeepingDeparting(display.toList(), items)
        if (merged != display.toList()) {
            display.clear()
            display.addAll(merged)
        }
    }

    val gap = 12f
    Box(modifier) {
        for (item in display) {
            key(item.key) {
                val place = items.indexOfFirst { it.key == item.key }
                val present = place >= 0
                // How far this one stands from the right edge: the widths of everything after it.
                val distance = with(LocalDensity.current) {
                    items.drop(place + 1).sumOf { ((it.bottle.widthDp + gap) * scale).toDouble() }.toFloat().dp.toPx()
                }
                val shift = remember { Animatable(distance) }
                val seen = remember { Animatable(if (item.key in initialKeys) 1f else 0f) }
                LaunchedEffect(distance, present, reduced) {
                    if (!present) return@LaunchedEffect
                    if (reduced) shift.snapTo(distance) else shift.animateTo(distance, Motion.glide())
                }
                LaunchedEffect(present, reduced) {
                    if (present) {
                        seen.animateTo(1f, if (reduced) Motion.fade() else spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow))
                    } else {
                        seen.animateTo(0f, if (reduced) Motion.fade() else Motion.smooth())
                        display.removeAll { it.key == item.key }
                    }
                }
                Canvas(
                    Modifier.align(Alignment.BottomEnd)
                        .size(item.bottle.widthDp.dp * scale, item.bottle.heightDp.dp * scale)
                        .graphicsLayer {
                            translationX = -shift.value
                            alpha = seen.value.coerceIn(0f, 1f)
                            // Arriving drops in from a little above; leaving lifts off further.
                            if (!reduced) translationY = -(1f - seen.value) * size.height * (if (present) 0.33f else 0.5f)
                        },
                ) {
                    drawBottle(item.bottle, 1.dp.toPx() * scale, colors)
                }
            }
        }
    }
}

/**
 * A small copy of a medicine's container, for a list row whose medicine has no pill picture
 * (liquids, drops, inhalers and creams). Decoration only: the name beside it says what it is.
 */
@Composable
fun BottleGlyph(bottle: Bottle, modifier: Modifier = Modifier, height: Dp = 30.dp, dimmed: Boolean = false) {
    val dark = MaterialTheme.colorScheme.background == Ink
    val base = if (dark) darkScene else lightScene
    val outline = (if (dark) Color(0xFF9AA1AB) else Color(0xFF6B7280)).let { if (dimmed) it.copy(alpha = 0.7f) else it }
    val colors = remember(dark, dimmed) {
        SceneColors(tile = base.tile, counter = base.counter, counterEdge = base.counterEdge, bottle = base.bottle, outline = outline, label = base.label, ridge = base.ridge)
    }
    val scale = height.value / bottle.heightDp
    Canvas(modifier.size(bottle.widthDp.dp * scale, height)) {
        drawBottle(bottle, 1.dp.toPx() * scale, colors, minLine = 1.25.dp.toPx())
    }
}

/**
 * The new list in order, with any item that has left kept at the position it had, so it can
 * animate away where it stood.
 */
internal fun mergeKeepingDeparting(old: List<CounterItem>, new: List<CounterItem>): List<CounterItem> {
    val newKeys = new.map { it.key }.toSet()
    val result = new.toMutableList()
    old.forEachIndexed { index, item ->
        if (item.key !in newKeys) result.add(index.coerceAtMost(result.size), item)
    }
    return result
}

private fun DrawScope.drawTiles(colors: SceneColors, tile: Float, bottom: Float) {
    val stroke = 1.dp.toPx()
    var x = tile * 0.6f
    while (x < size.width) {
        drawLine(colors.tile, Offset(x, 0f), Offset(x, bottom), strokeWidth = stroke)
        x += tile
    }
    var y = bottom - tile * 0.33f
    while (y > 0f) {
        drawLine(colors.tile, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
        y -= tile
    }
}

/** Draws one container with its top-left at the origin, in dp units multiplied by [u]. */
private fun DrawScope.drawBottle(bottle: Bottle, u: Float, c: SceneColors, minLine: Float = 0f) {
    val line = Stroke(width = (1.5f * u.coerceAtMost(1.dp.toPx() * 1.4f)).coerceAtLeast(minLine), join = StrokeJoin.Round)

    fun shape(x: Float, y: Float, w: Float, h: Float, r: Float, fill: Color = c.bottle) {
        val tl = Offset(x * u, y * u)
        val sz = Size(w * u, h * u)
        val cr = CornerRadius(r * u)
        drawRoundRect(fill, tl, sz, cr)
        drawRoundRect(c.outline, tl, sz, cr, style = line)
    }

    fun path(fill: Color = c.bottle, build: Path.() -> Unit) {
        val p = Path().apply(build)
        drawPath(p, fill)
        drawPath(p, c.outline, style = line)
    }

    fun ridge(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(c.ridge, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), strokeWidth = line.width)

    when (bottle) {
        Bottle.PILL -> {
            shape(0f, 27f, 66f, 83f, 13f)
            shape(0f, 46f, 66f, 40f, 0f, c.label)
            shape(11f, 20f, 44f, 7f, 0f)
            shape(3f, 0f, 60f, 20f, 4f)
            ridge(11f, 6f, 55f, 6f)
            ridge(11f, 12f, 55f, 12f)
        }
        Bottle.LIQUID -> {
            path {
                moveTo(4f * u, 44f * u)
                quadraticTo(4f * u, 26f * u, 22f * u, 26f * u)
                lineTo(38f * u, 26f * u)
                quadraticTo(56f * u, 26f * u, 56f * u, 44f * u)
                lineTo(56f * u, 112f * u)
                quadraticTo(56f * u, 120f * u, 48f * u, 120f * u)
                lineTo(12f * u, 120f * u)
                quadraticTo(4f * u, 120f * u, 4f * u, 112f * u)
                close()
            }
            shape(4f, 58f, 52f, 42f, 0f, c.label)
            shape(22f, 16f, 16f, 10f, 0f)
            shape(18f, 0f, 24f, 16f, 3f)
        }
        Bottle.DROPPER -> {
            shape(4f, 32f, 36f, 52f, 8f)
            shape(4f, 44f, 36f, 28f, 0f, c.label)
            shape(12f, 18f, 20f, 14f, 3f)
            path {
                moveTo(18f * u, 0f)
                lineTo(26f * u, 0f)
                lineTo(28f * u, 18f * u)
                lineTo(16f * u, 18f * u)
                close()
            }
        }
        Bottle.INHALER -> {
            // Canister poking out of the top, then the L-shaped actuator with its mouthpiece.
            shape(16f, 0f, 22f, 34f, 6f)
            shape(0f, 64f, 26f, 24f, 6f)
            shape(10f, 24f, 36f, 68f, 12f)
            ridge(16f, 40f, 40f, 40f)
            ridge(4f, 70f, 4f, 82f)
        }
        Bottle.PEN -> {
            // An injection pen standing on its dose knob, cap on top, with a little window.
            shape(7f, 108f, 12f, 12f, 3f)
            shape(2f, 40f, 22f, 70f, 6f)
            shape(7f, 56f, 12f, 24f, 2f, c.label)
            shape(3f, 0f, 20f, 44f, 6f)
            ridge(19f, 8f, 19f, 30f)
            ridge(7f, 96f, 19f, 96f)
        }
        Bottle.TUBE -> {
            // A cream tube standing on its cap: crimped end at the top, tapering down.
            shape(13f, 84f, 20f, 16f, 3f)
            shape(17f, 78f, 12f, 8f, 1f)
            path {
                moveTo(2f * u, 8f * u)
                lineTo(44f * u, 8f * u)
                quadraticTo(44f * u, 50f * u, 34f * u, 78f * u)
                lineTo(12f * u, 78f * u)
                quadraticTo(2f * u, 50f * u, 2f * u, 8f * u)
                close()
            }
            shape(8f, 28f, 30f, 26f, 0f, c.label)
            shape(0f, 0f, 46f, 9f, 2f)
            ridge(6f, 4.5f, 40f, 4.5f)
        }
        Bottle.BOX -> {
            // A medicine carton, drawn as a simple box with its top and side showing.
            path {
                moveTo(0f, 14f * u); lineTo(14f * u, 0f); lineTo(70f * u, 0f); lineTo(56f * u, 14f * u); close()
            }
            path(c.label) {
                moveTo(56f * u, 14f * u); lineTo(70f * u, 0f); lineTo(70f * u, 50f * u); lineTo(56f * u, 64f * u); close()
            }
            shape(0f, 14f, 56f, 50f, 0f)
            shape(0f, 30f, 56f, 18f, 0f, c.label)
        }
    }
}
