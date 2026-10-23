package app.dosekeeper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.dosekeeper.data.MedForm

/** The container drawn on the counter for each medicine. */
enum class Bottle(val widthDp: Float, val heightDp: Float) {
    PILL(66f, 110f), LIQUID(60f, 120f), DROPPER(44f, 84f);

    companion object {
        fun of(form: MedForm): Bottle = when (form) {
            MedForm.PILL, MedForm.OTHER -> PILL
            MedForm.LIQUID, MedForm.INJECTION -> LIQUID
            MedForm.DROPS -> DROPPER
        }
    }
}

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
 * medicine in [bottles], lined up from the right. More than [maxShown] shows a "+N more" note.
 *
 * [counterHeight] is the strip of counter at the bottom; [scale] enlarges the bottles (the alarm
 * screen draws one big one); [endPadding] is the gap between the last bottle and the right edge.
 */
@Composable
fun CounterScene(
    bottles: List<Bottle>,
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
    val shown = bottles.take(maxShown)
    val extra = bottles.size - shown.size
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val counterTop = size.height - counterHeight.toPx()
            drawTiles(colors, tileSize.toPx(), counterTop)
            drawRect(colors.counter, topLeft = Offset(0f, counterTop), size = Size(size.width, size.height - counterTop))
            drawLine(colors.counterEdge, Offset(0f, counterTop), Offset(size.width, counterTop), strokeWidth = 1.5.dp.toPx())

            val unit = 1.dp.toPx() * scale
            var right = size.width - endPadding.toPx()
            for (bottle in shown.reversed()) {
                val w = bottle.widthDp * unit
                val h = bottle.heightDp * unit
                translate(left = right - w, top = counterTop - h) { drawBottle(bottle, unit, colors) }
                right -= w + 12.dp.toPx() * scale
            }
        }
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
private fun DrawScope.drawBottle(bottle: Bottle, u: Float, c: SceneColors) {
    val line = Stroke(width = 1.5f * u.coerceAtMost(1.dp.toPx() * 1.4f), join = StrokeJoin.Round)

    fun shape(x: Float, y: Float, w: Float, h: Float, r: Float, fill: Color = c.bottle) {
        val tl = Offset(x * u, y * u)
        val sz = Size(w * u, h * u)
        val cr = CornerRadius(r * u)
        drawRoundRect(fill, tl, sz, cr)
        drawRoundRect(c.outline, tl, sz, cr, style = line)
    }

    fun path(p: Path) {
        drawPath(p, c.bottle)
        drawPath(p, c.outline, style = line)
    }

    when (bottle) {
        Bottle.PILL -> {
            shape(0f, 27f, 66f, 83f, 13f)
            shape(0f, 46f, 66f, 40f, 0f, c.label)
            shape(11f, 20f, 44f, 7f, 0f)
            shape(3f, 0f, 60f, 20f, 4f)
            drawLine(c.ridge, Offset(11f * u, 6f * u), Offset(55f * u, 6f * u), strokeWidth = line.width)
            drawLine(c.ridge, Offset(11f * u, 12f * u), Offset(55f * u, 12f * u), strokeWidth = line.width)
        }
        Bottle.LIQUID -> {
            path(
                Path().apply {
                    moveTo(4f * u, 44f * u)
                    quadraticTo(4f * u, 26f * u, 22f * u, 26f * u)
                    lineTo(38f * u, 26f * u)
                    quadraticTo(56f * u, 26f * u, 56f * u, 44f * u)
                    lineTo(56f * u, 112f * u)
                    quadraticTo(56f * u, 120f * u, 48f * u, 120f * u)
                    lineTo(12f * u, 120f * u)
                    quadraticTo(4f * u, 120f * u, 4f * u, 112f * u)
                    close()
                },
            )
            shape(4f, 58f, 52f, 42f, 0f, c.label)
            shape(22f, 16f, 16f, 10f, 0f)
            shape(18f, 0f, 24f, 16f, 3f)
        }
        Bottle.DROPPER -> {
            shape(4f, 32f, 36f, 52f, 8f)
            shape(4f, 44f, 36f, 28f, 0f, c.label)
            shape(12f, 18f, 20f, 14f, 3f)
            path(
                Path().apply {
                    moveTo(18f * u, 0f)
                    lineTo(26f * u, 0f)
                    lineTo(28f * u, 18f * u)
                    lineTo(16f * u, 18f * u)
                    close()
                },
            )
        }
    }
}
