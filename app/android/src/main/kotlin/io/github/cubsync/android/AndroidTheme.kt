package io.github.cubsync.android

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.cubsync.service.AppSettings
import io.github.cubsync.service.ThemeMode

/**
 * The design's colour tokens.
 *
 * The same names and the same values as the desktop's, drawn through Material 3 because
 * that is what this platform's widgets expect. Sharing the palette is what keeps a
 * screenshot of one end recognisable in the other; not sharing every widget is what keeps
 * each feeling like it belongs where it is.
 */
data class CubColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceSunken: Color,
    val outline: Color,
    val outlineStrong: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    val accent: Color,
    val accentContainer: Color,
    val danger: Color,
    val warning: Color,
    val success: Color,
    val conflict: Color,
    val info: Color,
    val rail: Color,
)

internal val DarkCubColors = CubColors(
    isDark = true,
    background = Color(0xFF0F1116),
    surface = Color(0xFF181B21),
    surfaceRaised = Color(0xFF1E222A),
    surfaceSunken = Color(0xFF242932),
    outline = Color(0xFF2A2F39),
    outlineStrong = Color(0xFF3A4150),
    text = Color(0xFFE8EBF1),
    textDim = Color(0xFF98A1B0),
    textFaint = Color(0xFF6D7686),
    accent = Color(0xFF3A5CCC),
    accentContainer = Color(0xFF232B4A),
    danger = Color(0xFFE5484D),
    warning = Color(0xFFE0A020),
    success = Color(0xFF2E9E5B),
    conflict = Color(0xFFF0731F),
    info = Color(0xFF1FA8A0),
    rail = Color(0xFF181B21),
)

internal val LightCubColors = CubColors(
    isDark = false,
    background = Color(0xFFE9EDF3),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFF6F8FB),
    surfaceSunken = Color(0xFFEFF2F7),
    outline = Color(0xFFDDE3EA),
    outlineStrong = Color(0xFFC3CBD6),
    text = Color(0xFF161A20),
    textDim = Color(0xFF637085),
    textFaint = Color(0xFF8B97A8),
    accent = Color(0xFF3A5CCC),
    accentContainer = Color(0xFFE4E9FA),
    danger = Color(0xFFE5484D),
    warning = Color(0xFFE0A020),
    success = Color(0xFF2E9E5B),
    conflict = Color(0xFFF0731F),
    info = Color(0xFF1FA8A0),
    rail = Color(0xFFFFFFFF),
)

val LocalCubColors = staticCompositionLocalOf { DarkCubColors }

object CubTheme {
    val colors: CubColors
        @Composable @ReadOnlyComposable
        get() = LocalCubColors.current
}

/**
 * Type scale.
 *
 * Larger than the desktop's throughout: these are held at arm's length rather than read
 * from a monitor, and the design's sizes for this end say so.
 */
object CubType {
    val windowTitle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val sectionTitle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    val cardTitle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(fontSize = 13.5.sp, lineHeight = 19.sp)
    val label = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
    val meta = TextStyle(fontSize = 11.5.sp)
    val big = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    val mono = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 17.sp)
}

/** `#RRGGBB` as a colour, with the brand's indigo as the fallback. */
fun parseHexColour(hex: String): Color {
    val clean = hex.removePrefix("#").trim()
    if (clean.length != 6) return Color(0xFF3A5CCC)
    val parsed = clean.toLongOrNull(16) ?: return Color(0xFF3A5CCC)
    return Color(0xFF000000L or parsed)
}

@Composable
fun CubAndroidTheme(
    mode: ThemeMode,
    accentHex: String = AppSettings.ACCENT_DEFAULT,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val base = if (dark) DarkCubColors else LightCubColors
    val cub = if (accentHex.equals(AppSettings.ACCENT_DEFAULT, ignoreCase = true)) {
        base
    } else {
        val accent = parseHexColour(accentHex)
        base.copy(
            accent = accent,
            accentContainer = lerp(accent, base.background, 0.80f),
        )
    }

    val scheme = if (dark) {
        darkColorScheme(
            primary = cub.accent,
            onPrimary = Color.White,
            background = cub.background,
            onBackground = cub.text,
            surface = cub.surface,
            onSurface = cub.text,
            surfaceVariant = cub.surfaceRaised,
            onSurfaceVariant = cub.textDim,
            outline = cub.outline,
            error = cub.danger,
        )
    } else {
        lightColorScheme(
            primary = cub.accent,
            onPrimary = Color.White,
            background = cub.background,
            onBackground = cub.text,
            surface = cub.surface,
            onSurface = cub.text,
            surfaceVariant = cub.surfaceRaised,
            onSurfaceVariant = cub.textDim,
            outline = cub.outline,
            error = cub.danger,
        )
    }

    CompositionLocalProvider(LocalCubColors provides cub) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
