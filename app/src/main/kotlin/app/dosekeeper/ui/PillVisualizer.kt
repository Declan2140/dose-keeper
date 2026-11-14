package app.dosekeeper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.dosekeeper.data.Medicine
import app.dosekeeper.data.PillColor
import app.dosekeeper.data.PillShape

/**
 * A small line drawing of a medicine: a round tablet, a capsule or an injection pen, optionally
 * filled with one of the muted [PillColor]s. It helps tell look-alike and sound-alike medicines
 * apart, but it's never the only clue: the name and strength are always written next to it, and
 * screen readers hear [description] ("blue capsule").
 *
 * Color lives only inside this drawing. The rest of the app stays black, white, grey and one green.
 */
@Composable
fun PillVisualizer(
    shape: PillShape,
    color: PillColor?,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    dimmed: Boolean = false,
    description: String? = listOfNotNull(color?.label, shape.label).joinToString(" ").lowercase(),
) {
    val dark = isSystemInDarkTheme()
    // The outline carries the shape, so it keeps 3:1 against the background in both themes.
    val outline = (if (dark) Color(0xFF9AA1AB) else Color(0xFF6B7280)).let { if (dimmed) it.copy(alpha = 0.7f) else it }
    val blank = if (dark) Color(0xFF23272D) else Color.White
    val fill = (color?.let { Color(it.argb) } ?: blank).let { if (dimmed) it.copy(alpha = 0.55f) else it }
    Canvas(
        modifier.size(size).then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
    ) {
        val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
        when (shape) {
            PillShape.ROUND -> drawRoundTablet(fill, outline, stroke)
            PillShape.CAPSULE -> drawCapsule(fill, blank, outline, stroke)
            PillShape.INJECTION -> drawPen(fill, blank, outline, stroke)
        }
    }
}

/** The picture for a stored medicine, or nothing when its form has none. */
@Composable
fun PillVisualizer(medicine: Medicine, modifier: Modifier = Modifier, size: Dp = 36.dp, dimmed: Boolean = false) {
    val shape = medicine.shape ?: return
    PillVisualizer(shape, medicine.color, modifier, size, dimmed)
}

/**
 * A round tablet seen at an angle: an oval top with its edge showing below, so it reads as a pill
 * and never as a minus sign or a "skipped" mark.
 */
private fun DrawScope.drawRoundTablet(fill: Color, outline: Color, stroke: Stroke) {
    val w = size.minDimension * 0.8f
    val h = size.minDimension * 0.46f
    val edge = size.minDimension * 0.14f
    val left = center.x - w / 2
    val top = center.y - (h + edge) / 2
    val topOval = Size(w, h)
    // The edge: the lower half of a second oval, joined to the top one by straight sides.
    val side = Path().apply {
        moveTo(left, top + h / 2)
        lineTo(left, top + h / 2 + edge)
        arcTo(Rect(Offset(left, top + edge), topOval), 180f, -180f, false)
        lineTo(left + w, top + h / 2)
        close()
    }
    drawPath(side, fill)
    drawPath(side, outline, style = stroke)
    drawOval(fill, Offset(left, top), topOval)
    drawOval(outline, Offset(left, top), topOval, style = stroke)
}

/** A tilted two-part capsule: one half in the color, the other left plain. */
private fun DrawScope.drawCapsule(fill: Color, blank: Color, outline: Color, stroke: Stroke) {
    val w = size.minDimension * 0.86f
    val h = size.minDimension * 0.38f
    val topLeft = Offset(center.x - w / 2, center.y - h / 2)
    val body = Size(w, h)
    val radius = CornerRadius(h / 2, h / 2)
    rotate(-35f) {
        drawRoundRect(blank, topLeft, body, radius)
        clipRect(left = topLeft.x, top = topLeft.y, right = center.x, bottom = topLeft.y + h) {
            drawRoundRect(fill, topLeft, body, radius)
        }
        drawRoundRect(outline, topLeft, body, radius, style = stroke)
        drawLine(outline, Offset(center.x, topLeft.y), Offset(center.x, topLeft.y + h), stroke.width)
    }
}

/** A tilted injection pen: colored barrel, plain cap end, and a short needle. */
private fun DrawScope.drawPen(fill: Color, blank: Color, outline: Color, stroke: Stroke) {
    val w = size.minDimension * 0.7f
    val h = size.minDimension * 0.22f
    val topLeft = Offset(center.x - w / 2 - size.minDimension * 0.06f, center.y - h / 2)
    val body = Size(w, h)
    val radius = CornerRadius(h * 0.3f, h * 0.3f)
    val split = topLeft.x + w * 0.3f
    rotate(-35f) {
        drawRoundRect(blank, topLeft, body, radius)
        clipRect(left = split, top = topLeft.y, right = topLeft.x + w, bottom = topLeft.y + h) {
            drawRoundRect(fill, topLeft, body, radius)
        }
        drawRoundRect(outline, topLeft, body, radius, style = stroke)
        drawLine(outline, Offset(split, topLeft.y), Offset(split, topLeft.y + h), stroke.width)
        // Needle, with a small hub where it meets the barrel.
        val tip = topLeft.x + w
        drawLine(outline, Offset(tip, center.y - h * 0.25f), Offset(tip, center.y + h * 0.25f), stroke.width * 2f, StrokeCap.Round)
        drawLine(outline, Offset(tip, center.y), Offset(tip + size.minDimension * 0.2f, center.y), stroke.width, StrokeCap.Round)
    }
}
