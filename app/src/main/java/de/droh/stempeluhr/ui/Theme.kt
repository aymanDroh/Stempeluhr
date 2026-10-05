package de.droh.stempeluhr.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Zusätzliche Farben, die Material 3 nicht kennt (Status, Verlauf). */
@Immutable
data class AppColors(
    val good: Color,
    val goodContainer: Color,
    val bad: Color,
    val badContainer: Color,
    val warn: Color,
    val warnContainer: Color,
    val info: Color,
    val heroStart: Color,
    val heroEnd: Color,
    val heroIdleStart: Color,
    val heroIdleEnd: Color,
    val onHero: Color,
)

private val LightApp = AppColors(
    good = Color(0xFF1B8A5A),
    goodContainer = Color(0xFFD5F2E3),
    bad = Color(0xFFD1344B),
    badContainer = Color(0xFFFCE1E5),
    warn = Color(0xFFD9822B),
    warnContainer = Color(0xFFFFEBD6),
    info = Color(0xFF2F6FDE),
    heroStart = Color(0xFF14946B),
    heroEnd = Color(0xFF0E6F8F),
    heroIdleStart = Color(0xFF3B4A6B),
    heroIdleEnd = Color(0xFF253049),
    onHero = Color.White,
)

private val DarkApp = AppColors(
    good = Color(0xFF5BD69A),
    goodContainer = Color(0xFF113B2A),
    bad = Color(0xFFFF7A8C),
    badContainer = Color(0xFF4A1820),
    warn = Color(0xFFFFB46B),
    warnContainer = Color(0xFF45290E),
    info = Color(0xFF8AB4FF),
    heroStart = Color(0xFF0F7A58),
    heroEnd = Color(0xFF0B5670),
    heroIdleStart = Color(0xFF2C3650),
    heroIdleEnd = Color(0xFF1A2133),
    onHero = Color.White,
)

val LocalAppColors = staticCompositionLocalOf { LightApp }

/** Kurzzugriff: `App.colors.good` usw. */
object App {
    val colors: AppColors @Composable get() = LocalAppColors.current
}

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1F5FAD),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E4FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF14946B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5F2E3),
    onSecondaryContainer = Color(0xFF00210F),
    tertiary = Color(0xFF8A5CF6),
    background = Color(0xFFF5F7FB),
    onBackground = Color(0xFF181C22),
    surface = Color(0xFFF5F7FB),
    onSurface = Color(0xFF181C22),
    surfaceVariant = Color(0xFFE3E8F1),
    onSurfaceVariant = Color(0xFF5A6273),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFEEF2F8),
    surfaceContainerHigh = Color(0xFFE8EDF5),
    surfaceContainerHighest = Color(0xFFE2E8F1),
    outline = Color(0xFF8C94A5),
    outlineVariant = Color(0xFFD5DBE6),
    error = Color(0xFFD1344B),
    errorContainer = Color(0xFFFCE1E5),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFADC6FF),
    onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF1B4A8A),
    onPrimaryContainer = Color(0xFFD8E4FF),
    secondary = Color(0xFF5BD69A),
    onSecondary = Color(0xFF003920),
    secondaryContainer = Color(0xFF113B2A),
    onSecondaryContainer = Color(0xFFD5F2E3),
    tertiary = Color(0xFFC3A8FF),
    background = Color(0xFF0F1218),
    onBackground = Color(0xFFE2E6EE),
    surface = Color(0xFF0F1218),
    onSurface = Color(0xFFE2E6EE),
    surfaceVariant = Color(0xFF2A303C),
    onSurfaceVariant = Color(0xFFA9B1C2),
    surfaceContainerLowest = Color(0xFF0B0E13),
    surfaceContainerLow = Color(0xFF171B23),
    surfaceContainer = Color(0xFF1B2029),
    surfaceContainerHigh = Color(0xFF222833),
    surfaceContainerHighest = Color(0xFF2A313D),
    outline = Color(0xFF7A8293),
    outlineVariant = Color(0xFF353C49),
    error = Color(0xFFFF7A8C),
    errorContainer = Color(0xFF4A1820),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val base = Typography()
private val AppTypography = base.copy(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
)

/** Zahlen mit fester Breite (Uhrzeiten springen nicht). */
val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun StempelTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    androidx.compose.runtime.CompositionLocalProvider(LocalAppColors provides if (dark) DarkApp else LightApp) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            shapes = AppShapes,
            typography = AppTypography,
            content = content,
        )
    }
}
