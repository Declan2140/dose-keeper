package app.dosekeeper.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.dosekeeper.R

/** Graphite ink, greys, and a single green accent for taken doses and progress. */
val Ink = Color(0xFF15181C)
val Accent = Color(0xFF1F6F5C)
val Overdue = Color(0xFFC42B1C)
val Muted = Color(0xFF6B7280)
val Hairline = Color(0xFFEEF0F2)
val Fill = Color(0xFFF3F4F6)

private val light = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = Fill,
    onPrimaryContainer = Ink,
    secondary = Accent,
    onSecondary = Color.White,
    tertiary = Accent,
    background = Color.White,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Fill,
    onSurfaceVariant = Color(0xFF4B5563),
    surfaceContainer = Color.White,
    surfaceContainerLow = Color(0xFFFAFAFB),
    surfaceContainerHigh = Fill,
    surfaceContainerHighest = Fill,
    outline = Color(0xFFD1D5DB),
    outlineVariant = Hairline,
    error = Overdue,
    errorContainer = Color(0xFFFDF6F5),
    onErrorContainer = Ink,
    secondaryContainer = Fill,
    onSecondaryContainer = Ink,
)

private val dark = darkColorScheme(
    primary = Color.White,
    onPrimary = Ink,
    primaryContainer = Color(0xFF23272D),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF7CC8B0),
    onSecondary = Ink,
    tertiary = Color(0xFF7CC8B0),
    background = Ink,
    onBackground = Color.White,
    surface = Ink,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF23272D),
    onSurfaceVariant = Color(0xFFB4BAC2),
    surfaceContainer = Ink,
    surfaceContainerLow = Color(0xFF1A1E23),
    surfaceContainerHigh = Color(0xFF23272D),
    surfaceContainerHighest = Color(0xFF23272D),
    outline = Color(0xFF3A3F46),
    outlineVariant = Color(0xFF262A30),
    error = Color(0xFFFF8A80),
    errorContainer = Color(0xFF2A1C1B),
    onErrorContainer = Color.White,
    secondaryContainer = Color(0xFF23272D),
    onSecondaryContainer = Color.White,
)

val Geist = FontFamily(
    Font(R.font.geist_light, FontWeight.Light),
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
)

private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = 0.0) =
    TextStyle(fontFamily = Geist, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = tracking.sp)

/**
 * Body text starts at 18sp and every size is in sp, so the phone's own font size setting
 * scales all of it.
 */
private val type = Typography(
    displayLarge = style(88, 92, FontWeight.Light, -3.0),
    displayMedium = style(56, 60, FontWeight.Light, -2.0),
    displaySmall = style(40, 46, FontWeight.SemiBold, -1.0),
    headlineLarge = style(36, 42, FontWeight.SemiBold, -1.0),
    headlineMedium = style(32, 38, FontWeight.SemiBold, -0.8),
    headlineSmall = style(26, 32, FontWeight.SemiBold, -0.5),
    titleLarge = style(22, 28, FontWeight.SemiBold, -0.2),
    titleMedium = style(19, 26, FontWeight.Medium),
    titleSmall = style(17, 24, FontWeight.SemiBold),
    bodyLarge = style(19, 27),
    bodyMedium = style(18, 26),
    bodySmall = style(15, 21),
    labelLarge = style(17, 22, FontWeight.Medium),
    labelMedium = style(15, 20, FontWeight.Medium),
    labelSmall = style(13, 18, FontWeight.Medium),
)

@Composable
fun DoseKeeperTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dark else light, typography = type, content = content)
}

/** The fixed dark look of the full-screen alarm, whatever the phone's theme. */
@Composable
fun AlarmTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = dark, typography = type, content = content)
}
