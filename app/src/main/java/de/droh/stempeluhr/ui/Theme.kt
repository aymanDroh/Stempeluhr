package de.droh.stempeluhr.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.droh.stempeluhr.R
import de.droh.stempeluhr.data.Settings

/**
 * Farben, die Material 3 nicht kennt: Bedeutungsfarben (gut/schlecht/Warnung/Info) jeweils als
 * Textfarbe und als heller Hintergrund dazu, sowie die Verläufe der großen Statuskarte.
 * Alle Textfarben erreichen auf ihrem Hintergrund mindestens 4,5:1 Kontrast (hell und dunkel geprüft).
 */
@Immutable
data class AppColors(
    val good: Color,
    val goodContainer: Color,
    val bad: Color,
    val badContainer: Color,
    val warn: Color,
    val warnContainer: Color,
    val info: Color,
    val infoContainer: Color,
    val violet: Color,
    val violetContainer: Color,
    /** Statuskarte „anwesend“. */
    val heroPresent: List<Color>,
    /** Statuskarte „noch nicht eingestempelt“. */
    val heroIdle: List<Color>,
    /** Statuskarte „Feierabend“. */
    val heroDone: List<Color>,
    val onHero: Color,
    /** Schrift auf dem weißen Stempel-Knopf der Statuskarte: Kommen bzw. Gehen. */
    val heroButtonIn: Color,
    val heroButtonOut: Color,
    /** Einfarbige Fläche innerhalb einer Karte (Eingabefelder, Balken-Hintergrund, Kacheln). */
    val inset: Color,
    val dark: Boolean,
)

private val LightApp = AppColors(
    good = Color(0xFF076E4A),
    goodContainer = Color(0xFFDCF5EA),
    bad = Color(0xFFBE2038),
    badContainer = Color(0xFFFCE4E8),
    warn = Color(0xFF9C4A06),
    warnContainer = Color(0xFFFFECD6),
    info = Color(0xFF2458D6),
    infoContainer = Color(0xFFDCE7FF),
    violet = Color(0xFF6B42E0),
    violetContainer = Color(0xFFEAE3FF),
    heroPresent = listOf(Color(0xFF077551), Color(0xFF07697C)),
    heroIdle = listOf(Color(0xFF3152E0), Color(0xFF2637A8)),
    heroDone = listOf(Color(0xFF4A4F80), Color(0xFF2F335C)),
    onHero = Color.White,
    heroButtonIn = Color(0xFF077551),
    heroButtonOut = Color(0xFFBE2038),
    inset = Color(0xFFEEF1F7),
    dark = false,
)

private val DarkApp = AppColors(
    good = Color(0xFF4FDBA0),
    goodContainer = Color(0xFF0E3527),
    bad = Color(0xFFFF8496),
    badContainer = Color(0xFF47161F),
    warn = Color(0xFFFFB869),
    warnContainer = Color(0xFF40270B),
    info = Color(0xFF8FB4FF),
    infoContainer = Color(0xFF162C5C),
    violet = Color(0xFFC3AEFF),
    violetContainer = Color(0xFF2C2060),
    heroPresent = listOf(Color(0xFF096C4C), Color(0xFF075B6B)),
    heroIdle = listOf(Color(0xFF2C49C9), Color(0xFF1D2B85)),
    heroDone = listOf(Color(0xFF3D4270), Color(0xFF262A4D)),
    onHero = Color.White,
    heroButtonIn = Color(0xFF077551),
    heroButtonOut = Color(0xFFBE2038),
    inset = Color(0xFF1C2330),
    dark = true,
)

val LocalAppColors = staticCompositionLocalOf { LightApp }

/** Kurzzugriff: `App.colors.good` usw. */
object App {
    val colors: AppColors @Composable get() = LocalAppColors.current
}

private val LightScheme = lightColorScheme(
    primary = Color(0xFF3152E0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E6FF),
    onPrimaryContainer = Color(0xFF10227A),
    inversePrimary = Color(0xFFA3B6FF),
    // Getönte Flächen (ausgewählte Chips, Navigations-Markierung, Tonal-Buttons) bleiben im Blau der App –
    // Grün und Rot sind für Kommen/Gehen und gut/schlecht reserviert.
    secondary = Color(0xFF3152E0),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E6FF),
    onSecondaryContainer = Color(0xFF10227A),
    tertiary = Color(0xFF6B42E0),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEAE3FF),
    onTertiaryContainer = Color(0xFF24116B),
    background = Color(0xFFF2F4F9),
    onBackground = Color(0xFF131722),
    surface = Color(0xFFF2F4F9),
    onSurface = Color(0xFF131722),
    surfaceVariant = Color(0xFFE8ECF4),
    onSurfaceVariant = Color(0xFF555E73),
    surfaceTint = Color(0xFF3152E0),
    inverseSurface = Color(0xFF1B2030),
    inverseOnSurface = Color(0xFFEEF1F8),
    surfaceDim = Color(0xFFDFE4EE),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFE8ECF4),
    outline = Color(0xFF8891A4),
    outlineVariant = Color(0xFFDDE1EA),
    error = Color(0xFFBE2038),
    onError = Color.White,
    errorContainer = Color(0xFFFCE4E8),
    onErrorContainer = Color(0xFF5C0A17),
    scrim = Color.Black,
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFA3B6FF),
    onPrimary = Color(0xFF0B1C6B),
    primaryContainer = Color(0xFF1F3288),
    onPrimaryContainer = Color(0xFFDEE5FF),
    inversePrimary = Color(0xFF3152E0),
    secondary = Color(0xFFA3B6FF),
    onSecondary = Color(0xFF0B1C6B),
    secondaryContainer = Color(0xFF1F3288),
    onSecondaryContainer = Color(0xFFDEE5FF),
    tertiary = Color(0xFFC3AEFF),
    onTertiary = Color(0xFF24116B),
    tertiaryContainer = Color(0xFF2C2060),
    onTertiaryContainer = Color(0xFFEAE3FF),
    background = Color(0xFF0A0D14),
    onBackground = Color(0xFFE8EBF3),
    surface = Color(0xFF0A0D14),
    onSurface = Color(0xFFE8EBF3),
    surfaceVariant = Color(0xFF252D3C),
    onSurfaceVariant = Color(0xFFA5AEC2),
    surfaceTint = Color(0xFFA3B6FF),
    inverseSurface = Color(0xFFE8EBF3),
    inverseOnSurface = Color(0xFF1B2030),
    surfaceDim = Color(0xFF070A10),
    surfaceBright = Color(0xFF252D3C),
    surfaceContainerLowest = Color(0xFF070A10),
    surfaceContainerLow = Color(0xFF131822),
    surfaceContainer = Color(0xFF161C27),
    surfaceContainerHigh = Color(0xFF1B2230),
    surfaceContainerHighest = Color(0xFF252D3C),
    outline = Color(0xFF6F788C),
    outlineVariant = Color(0xFF262D3B),
    error = Color(0xFFFF8496),
    onError = Color(0xFF5C0A17),
    errorContainer = Color(0xFF47161F),
    onErrorContainer = Color(0xFFFFDADF),
    scrim = Color.Black,
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Plus Jakarta Sans (SIL Open Font License 1.1) – freundlich, modern, mit klaren Ziffern. */
val AppFont = FontFamily(
    Font(R.font.plus_jakarta_sans_regular, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans_medium, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold),
    Font(R.font.plus_jakarta_sans_extrabold, FontWeight.ExtraBold),
)

private fun style(size: Int, line: Int, weight: FontWeight, spacing: Double = 0.0) = TextStyle(
    fontFamily = AppFont,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = spacing.sp,
)

private val AppTypography = Typography(
    displayLarge = style(60, 64, FontWeight.Bold, -1.5),
    displayMedium = style(44, 50, FontWeight.Bold, -1.0),
    displaySmall = style(36, 42, FontWeight.Bold, -0.5),
    headlineLarge = style(30, 36, FontWeight.ExtraBold, -0.5),
    headlineMedium = style(26, 32, FontWeight.Bold, -0.3),
    headlineSmall = style(22, 28, FontWeight.Bold, -0.2),
    titleLarge = style(19, 26, FontWeight.Bold, -0.1),
    titleMedium = style(16, 22, FontWeight.SemiBold),
    titleSmall = style(14, 20, FontWeight.SemiBold),
    bodyLarge = style(16, 24, FontWeight.Normal),
    bodyMedium = style(14, 21, FontWeight.Normal),
    bodySmall = style(12, 17, FontWeight.Normal, 0.1),
    labelLarge = style(14, 20, FontWeight.SemiBold),
    labelMedium = style(12, 16, FontWeight.SemiBold, 0.2),
    labelSmall = style(11, 15, FontWeight.SemiBold, 0.3),
)

/** Zahlen mit fester Breite (Uhrzeiten und Zähler springen nicht). */
val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")

/** Ob die App gerade dunkel dargestellt wird (laut Einstellung „Darstellung“ oder wie das Handy). */
@Composable
fun isAppDark(themeMode: String): Boolean = when (themeMode) {
    Settings.THEME_LIGHT -> false
    Settings.THEME_DARK -> true
    else -> isSystemInDarkTheme()
}

@Composable
fun StempelTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppColors provides if (dark) DarkApp else LightApp) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            shapes = AppShapes,
            typography = AppTypography,
            content = content,
        )
    }
}
