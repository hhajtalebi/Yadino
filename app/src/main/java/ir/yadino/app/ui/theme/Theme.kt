package ir.yadino.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.yadino.app.R
import ir.yadino.app.core.Formatter
import ir.yadino.domain.model.AnimationLevel
import ir.yadino.domain.model.AppSettings
import ir.yadino.domain.model.NoteColor
import ir.yadino.domain.model.Palette
import ir.yadino.domain.model.ThemeMode

val Vazirmatn = FontFamily(
    Font(R.font.vazirmatn_light, FontWeight.Light),
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
)

private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = Vazirmatn, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)

/** Persian script needs a bit more line height than Latin defaults. */
val YadinoTypography = Typography(
    displayLarge = style(52, 64, FontWeight.Bold),
    displayMedium = style(42, 52, FontWeight.Bold),
    displaySmall = style(34, 44, FontWeight.Bold),
    headlineLarge = style(30, 40, FontWeight.Bold),
    headlineMedium = style(26, 36, FontWeight.Bold),
    headlineSmall = style(22, 32, FontWeight.Bold),
    titleLarge = style(20, 30, FontWeight.Bold),
    titleMedium = style(16, 26, FontWeight.Medium),
    titleSmall = style(14, 22, FontWeight.Medium),
    bodyLarge = style(16, 27),
    bodyMedium = style(14, 24),
    bodySmall = style(12, 20),
    labelLarge = style(14, 22, FontWeight.Medium),
    labelMedium = style(12, 18, FontWeight.Medium),
    labelSmall = style(11, 16, FontWeight.Medium),
)

val YadinoShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

// Brand colours: dark green, cream, soft gold
private val Green = Color(0xFF1F5C45)
private val GreenLight = Color(0xFF8FD3B0)
private val Cream = Color(0xFFFBF6E9)
private val Gold = Color(0xFFC9A227)
private val GoldLight = Color(0xFFE3C66F)

private val YadinoLight = lightColorScheme(
    primary = Green, onPrimary = Color.White, primaryContainer = Color(0xFFCDEBDC), onPrimaryContainer = Color(0xFF062016),
    secondary = Color(0xFF8A6D12), onSecondary = Color.White, secondaryContainer = Color(0xFFF6E3A8), onSecondaryContainer = Color(0xFF2B2000),
    tertiary = Color(0xFF3D6373), onTertiary = Color.White, tertiaryContainer = Color(0xFFC1E8FB), onTertiaryContainer = Color(0xFF001F29),
    background = Cream, onBackground = Color(0xFF1B1F1C), surface = Color(0xFFFFFDF7), onSurface = Color(0xFF1B1F1C),
    surfaceVariant = Color(0xFFE9E3D2), onSurfaceVariant = Color(0xFF4C4A40), outline = Color(0xFF7D7A6E), outlineVariant = Color(0xFFD0CABA),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFAF4E6), surfaceContainer = Color(0xFFF5EEDD),
    surfaceContainerHigh = Color(0xFFEFE8D6), surfaceContainerHighest = Color(0xFFE9E2CF),
    error = Color(0xFFB3261E), onError = Color.White, errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
)

private val YadinoDark = darkColorScheme(
    primary = GreenLight, onPrimary = Color(0xFF003824), primaryContainer = Color(0xFF155038), onPrimaryContainer = Color(0xFFCDEBDC),
    secondary = GoldLight, onSecondary = Color(0xFF3D2F00), secondaryContainer = Color(0xFF594500), onSecondaryContainer = Color(0xFFF6E3A8),
    tertiary = Color(0xFFA5CCDE), onTertiary = Color(0xFF073543), tertiaryContainer = Color(0xFF244C5A), onTertiaryContainer = Color(0xFFC1E8FB),
    background = Color(0xFF0F1A15), onBackground = Color(0xFFE3E9E4), surface = Color(0xFF121D18), onSurface = Color(0xFFE3E9E4),
    surfaceVariant = Color(0xFF3F4943), onSurfaceVariant = Color(0xFFBFC9C1), outline = Color(0xFF89938C), outlineVariant = Color(0xFF3F4943),
    surfaceContainerLowest = Color(0xFF0A130F), surfaceContainerLow = Color(0xFF16231D), surfaceContainer = Color(0xFF1A2721),
    surfaceContainerHigh = Color(0xFF24312B), surfaceContainerHighest = Color(0xFF2F3C36),
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410), errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
)

private fun seededLight(primary: Color, container: Color, secondary: Color, secondaryContainer: Color, background: Color) =
    YadinoLight.copy(primary = primary, primaryContainer = container, secondary = secondary, secondaryContainer = secondaryContainer, background = background)

private fun seededDark(primary: Color, container: Color, secondary: Color, secondaryContainer: Color) =
    YadinoDark.copy(primary = primary, primaryContainer = container, secondary = secondary, secondaryContainer = secondaryContainer)

private val OceanLight = seededLight(Color(0xFF1D5C8C), Color(0xFFD1E4FF), Color(0xFF8A6D12), Color(0xFFF6E3A8), Color(0xFFF7F8FB))
private val OceanDark = seededDark(Color(0xFF9DCAFF), Color(0xFF00497A), GoldLight, Color(0xFF594500))
private val RoseLight = seededLight(Color(0xFF9C3550), Color(0xFFFFD9DF), Color(0xFF7A5B12), Color(0xFFF6E3A8), Color(0xFFFFF8F7))
private val RoseDark = seededDark(Color(0xFFFFB1C1), Color(0xFF7E1D39), GoldLight, Color(0xFF594500))
private val AmethystLight = seededLight(Color(0xFF6A4C93), Color(0xFFEDDCFF), Color(0xFF8A6D12), Color(0xFFF6E3A8), Color(0xFFFBF8FD))
private val AmethystDark = seededDark(Color(0xFFD6BBFF), Color(0xFF51357A), GoldLight, Color(0xFF594500))

/** Settings available to every composable. */
val LocalAppSettings = staticCompositionLocalOf { AppSettings() }
val LocalFormatter = staticCompositionLocalOf { Formatter(AppSettings()) }
val LocalIsDark = staticCompositionLocalOf { false }

/** Animation duration multiplier derived from the animation level setting (0 = no animation). */
val LocalAnimationScale = staticCompositionLocalOf { 1f }

@Composable
fun YadinoTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val scheme: ColorScheme = when (settings.palette) {
        Palette.DYNAMIC -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else if (dark) YadinoDark else YadinoLight
        Palette.OCEAN -> if (dark) OceanDark else OceanLight
        Palette.ROSE -> if (dark) RoseDark else RoseLight
        Palette.AMETHYST -> if (dark) AmethystDark else AmethystLight
        Palette.YADINO -> if (dark) YadinoDark else YadinoLight
    }
    val base = LocalDensity.current
    val density = Density(base.density * settings.displayScale, base.fontScale * settings.fontScale)
    val animScale = when (settings.animationLevel) {
        AnimationLevel.FULL -> 1f
        AnimationLevel.REDUCED -> 0.5f
        AnimationLevel.OFF -> 0f
    }
    CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Rtl,
        LocalDensity provides density,
        LocalAppSettings provides settings,
        LocalFormatter provides Formatter(settings),
        LocalIsDark provides dark,
        LocalAnimationScale provides animScale,
    ) {
        MaterialTheme(colorScheme = scheme, typography = YadinoTypography, shapes = YadinoShapes, content = content)
    }
}

/** Display colours for note/goal/habit colour keys. */
fun NoteColor.container(dark: Boolean): Color = when (this) {
    NoteColor.DEFAULT -> if (dark) Color(0xFF1E2A24) else Color(0xFFFFFDF7)
    NoteColor.CREAM -> if (dark) Color(0xFF39352A) else Color(0xFFF8EFD6)
    NoteColor.GREEN -> if (dark) Color(0xFF1F3A2E) else Color(0xFFD7EEDF)
    NoteColor.GOLD -> if (dark) Color(0xFF453A17) else Color(0xFFF6E3A8)
    NoteColor.ROSE -> if (dark) Color(0xFF462630) else Color(0xFFF9DCE2)
    NoteColor.SKY -> if (dark) Color(0xFF1D3442) else Color(0xFFD6EAF6)
    NoteColor.LAVENDER -> if (dark) Color(0xFF332A44) else Color(0xFFE6DDF5)
    NoteColor.SAND -> if (dark) Color(0xFF3E3428) else Color(0xFFEDE0CC)
}

/** Strong accent variant (dots, progress) of a colour key. */
fun NoteColor.accent(dark: Boolean): Color = when (this) {
    NoteColor.DEFAULT -> if (dark) Color(0xFF8FD3B0) else Color(0xFF1F5C45)
    NoteColor.CREAM -> if (dark) Color(0xFFE6D4A5) else Color(0xFF9A7B3C)
    NoteColor.GREEN -> if (dark) Color(0xFF8FD3B0) else Color(0xFF1F7A55)
    NoteColor.GOLD -> if (dark) Color(0xFFE3C66F) else Color(0xFFB08A10)
    NoteColor.ROSE -> if (dark) Color(0xFFF3A6B5) else Color(0xFFB34160)
    NoteColor.SKY -> if (dark) Color(0xFF8CC8FF) else Color(0xFF2873A8)
    NoteColor.LAVENDER -> if (dark) Color(0xFFCDB6F5) else Color(0xFF6A4C93)
    NoteColor.SAND -> if (dark) Color(0xFFD9BE96) else Color(0xFF8C6A3F)
}

val HolidayRed = Color(0xFFC0392B)
val HolidayRedDark = Color(0xFFF08A80)
