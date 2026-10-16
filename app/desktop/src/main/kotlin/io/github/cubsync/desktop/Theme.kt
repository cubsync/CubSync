package io.github.cubsync.desktop

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

/**
 * The design's colour tokens, taken from the prototype so the application and the
 * prototype cannot drift apart.
 *
 * Dark is the default, and the light palette is the same set of names with the
 * prototype's light values rather than a second design. Nothing in a screen refers
 * to a raw colour, so switching the theme cannot leave one panel unreadable.
 *
 * One deliberate difference from the prototype: the accent is the finalised brand
 * indigo (`#3A5CCC`) rather than the prototype's blue, because the mark and the
 * interface sitting next to it in the same window cannot be two different blues. The
 * teal from the same palette appears where something is informational.
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
    val accentSoft: Color,
    val accentDeep: Color,
    val danger: Color,
    val warning: Color,
    val success: Color,
    val conflict: Color,
    val info: Color,
    val rail: Color,
    val hover: Color,
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
    accentSoft = Color(0xFF232B4A),
    accentDeep = Color(0xFF2F4AA8),
    danger = Color(0xFFE5484D),
    warning = Color(0xFFE0A020),
    success = Color(0xFF2E9E5B),
    conflict = Color(0xFFF0731F),
    info = Color(0xFF1FA8A0),
    rail = Color(0xFF181B21),
    hover = Color(0xFF232833),
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
    accentSoft = Color(0xFFE4E9FA),
    accentDeep = Color(0xFF2F4AA8),
    danger = Color(0xFFE5484D),
    warning = Color(0xFFE0A020),
    success = Color(0xFF2E9E5B),
    conflict = Color(0xFFF0731F),
    info = Color(0xFF1FA8A0),
    rail = Color(0xFFFFFFFF),
    hover = Color(0xFFF0F3F8),
)

val LocalCubColors = staticCompositionLocalOf { DarkCubColors }

/** `CubTheme.colors.text` reads better in a layout than a colour literal does. */
object CubTheme {
    val colors: CubColors
        @Composable @ReadOnlyComposable
        get() = LocalCubColors.current
}

/**
 * Type scale.
 *
 * Sizes are the ones from the design document, kept as named styles so no screen
 * invents a 9sp label that nobody can read on a scaled display.
 */
object CubType {
    val windowTitle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val sectionTitle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    val cardTitle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp)
    val label = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
    val meta = TextStyle(fontSize = 10.5.sp)
    val big = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    val mono = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
    val monoDim = TextStyle(fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
}

/**
 * `#RRGGBB` as a colour, with the brand's indigo as the fallback.
 *
 * Parsed here rather than through the platform's parser: this file is compiled for the
 * desktop, and reaching for an Android class for six hexadecimal digits is how a shared
 * file stops being shippable on one of the two platforms.
 */
fun parseHexColour(hex: String): Color {
    val clean = hex.removePrefix("#").trim()
    if (clean.length != 6) return Color(0xFF3A5CCC)
    val parsed = clean.toLongOrNull(16) ?: return Color(0xFF3A5CCC)
    return Color(0xFF000000L or parsed)
}

/**
 * The theme, with the user's accent laid over the brand palette.
 *
 * Only the accent is replaceable. The surfaces, the outlines and the two text weights are
 * not offered as choices because a window with an unreadable panel is not a preference,
 * it is a bug — and this is the line where that distinction is made.
 */
@Composable
fun CubTheme(
    dark: Boolean,
    accentHex: String = io.github.cubsync.service.AppSettings.ACCENT_DEFAULT,
    content: @Composable () -> Unit,
) {
    val base = if (dark) DarkCubColors else LightCubColors
    val cub = if (accentHex.equals(base.accent.hex(), ignoreCase = true)) {
        base
    } else {
        val accent = parseHexColour(accentHex)
        base.copy(
            accent = accent,
            accentSoft = lerp(accent, base.background, 0.80f),
            accentDeep = lerp(accent, Color.Black, 0.22f),
        )
    }
    val material = if (dark) {
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
        MaterialTheme(colorScheme = material, content = content)
    }
}

/** `#RRGGBB` for a colour, so a swatch can be compared with the setting it came from. */
private fun Color.hex(): String {
    fun part(value: Float): Int = (value * 255f).toInt().coerceIn(0, 255)
    return "#%02X%02X%02X".format(part(red), part(green), part(blue))
}
