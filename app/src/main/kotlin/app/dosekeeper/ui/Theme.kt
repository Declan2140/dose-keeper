package app.dosekeeper.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Brand = Color(0xFF1F6F5C)
val Overdue = Color(0xFFC62828)

private val light = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3F2EC),
    onPrimaryContainer = Color(0xFF0B2E25),
    secondary = Color(0xFF4A635A),
    error = Overdue,
)

private val dark = darkColorScheme(
    primary = Color(0xFF8FD5BC),
    onPrimary = Color(0xFF00382B),
    primaryContainer = Color(0xFF1F4F42),
    onPrimaryContainer = Color(0xFFCDEFE2),
    error = Color(0xFFFF8A80),
)

/**
 * Body text starts at 18sp (the spec's minimum) and every size is in sp, so the phone's own
 * font size setting scales all of it.
 */
private val type = Typography().let { base ->
    base.copy(
        bodyLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp),
        bodyMedium = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
        bodySmall = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
        titleLarge = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        labelLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
        labelMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    )
}

@Composable
fun DoseKeeperTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dark else light, typography = type, content = content)
}
