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
 * The prototype's tokens, one for one.
 *
 * Every name here maps to a custom property in `cubsync-ui-demo.html`: `background` is
 * `--bg`, `surface` is `--panel`, `surfaceRaised` is `--panel-2`, `surfaceSunken` is
 * `--panel-3`, `outline` is `--line`, `outlineFaint` is `--line-2`, `textDim` is `--dim`,
 * `textFaint` is `--dim-2`, `accent` is `--win-accent`. The values are copied, not
 * chosen: an earlier version substituted the brand indigo for `--win-accent` and called
 * it a deliberate difference, which is how the interface and the prototype ended up not
 * matching in the one property a person notices first.
 *
 * Two consequences worth stating. The desktop and the phone do **not** share a palette —
 * the prototype gives them `--win-*` and `--m3-*` separately, and they are different
 * colours on purpose. And `--win-accent` here is blue, not the logo's indigo: the mark
 * is the mark, the interface is the interface, and the design says which is which.
 */
data class CubColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceSunken: Color,
    val outline: Color,
    val outlineFaint: Color,
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
    val selected: Color,
    val selectedLine: Color,
)

internal val DarkCubColors = CubColors(
    isDark = true,
    // The phone's dark palette, value for value. The desktop had its own near-black set
    // (#0F1116 against #141318), so the two ends looked like two products side by side —
    // and the phone's is the one the design tokens name.
    background = Color(0xFF141318),
    surface = Color(0xFF141318),
    surfaceRaised = Color(0xFF1F1F26),
    surfaceSunken = Color(0xFF26252C),
    outline = Color(0xFF2A2F39),
    outlineFaint = Color(0xFF232229),
    outlineStrong = Color(0xFF3A3942),
    text = Color(0xFFE6E1E9),
    textDim = Color(0xFFC9C5D0),
    textFaint = Color(0xFF8E8A97),
    accent = Color(0xFF1E88E5),
    // `color-mix(in srgb, var(--win-accent) 14%, transparent)` — translucent on purpose,
    // so it composites over whatever it is put on exactly as the prototype's does.
    accentSoft = Color(0xFF1E88E5).copy(alpha = 0.14f),
    accentDeep = Color(0xFF1565C0),
    danger = Color(0xFFE5484D),
    warning = Color(0xFFE0A020),
    success = Color(0xFF2E9E5B),
    conflict = Color(0xFFF0731F),
    info = Color(0xFF3B82F6),
    rail = Color(0xFF1F1F26),
    hover = Color(0xFF26252C),
    selected = Color(0x2E4C6EF5),
    selectedLine = Color(0xFF4C6EF5),
)

internal val LightCubColors = CubColors(
    isDark = false,
    background = Color(0xFFE9EDF3),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFF6F8FB),
    surfaceSunken = Color(0xFFEFF2F7),
    outline = Color(0xFFDDE3EA),
    outlineFaint = Color(0xFFE8EDF3),
    outlineStrong = Color(0xFFDDE3EA),
    text = Color(0xFF161A20),
    textDim = Color(0xFF637085),
    textFaint = Color(0xFF8B97A8),
    accent = Color(0xFF1E88E5),
    accentSoft = Color(0xFF1E88E5).copy(alpha = 0.14f),
    accentDeep = Color(0xFF1565C0),
    danger = Color(0xFFE5484D),
    warning = Color(0xFFE0A020),
    success = Color(0xFF2E9E5B),
    conflict = Color(0xFFF0731F),
    info = Color(0xFF3B82F6),
    rail = Color(0xFFF6F8FB),
    hover = Color(0xFFF0F3F8),
    selected = Color(0xFFE6F0FB),
    selectedLine = Color(0xFF9FC9EF),
)

val LocalCubColors = staticCompositionLocalOf { DarkCubColors }

/** `CubTheme.colors.text` reads better in a layout than a colour literal does. */
object CubTheme {
    val colors: CubColors
        @Composable @ReadOnlyComposable
        get() = LocalCubColors.current
}

/**
 * Type scale, from the prototype's own declarations.
 *
 * `.chead h2` 17/680, `.chead p` 12, `.card h3` 13/650 with an 11.5 sub, `.ritem` 13,
 * `.rlabel` 10.5/700, `.chip` 11.5, `.kv` 12, `.chan .addr` mono 11.5. Nothing here is
 * rounded to a "nicer" number, because rounding is how 13 becomes 14 becomes 16.
 */
object CubType {
    /** `.win-title .t` — the product name in the title bar. */
    val windowTitle = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp)

    /** `.chead h2` — the heading of the open screen. */
    val screenTitle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp)

    /** `.chead p` — the one line under it. */
    val screenHint = TextStyle(fontSize = 12.sp)

    /** `.card h3` and its `.sub`. */
    val cardTitle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val cardSub = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Medium)

    /** `.btn`, `.dev-chip`, `.chip`. */
    val button = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
    val chip = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)

    /** `.rlabel` / `.ritem` — the rail's group labels and its entries. */
    val railLabel = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
    val railItem = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)

    /** `.kv`, `.chan`. */
    val body = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp)
    val label = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
    val meta = TextStyle(fontSize = 11.sp)
    val tiny = TextStyle(fontSize = 10.5.sp)

    /** `.win-status`, `.dev-chip`. */
    val status = TextStyle(fontSize = 11.5.sp)

    /** `.addredit input`, `.chan .addr`, the log. */
    val mono = TextStyle(fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, lineHeight = 17.sp)
    val monoSmall = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
    val monoDim = TextStyle(fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp)

    val big = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
}

/**
 * `#RRGGBB` as a colour, with the prototype's desktop accent as the fallback.
 *
 * Parsed here rather than through the platform's parser: this file is compiled for the
 * desktop, and reaching for an Android class for six hexadecimal digits is how a shared
 * file stops being shippable on one of the two platforms.
 */
fun parseHexColour(hex: String): Color {
    val clean = hex.removePrefix("#").trim()
    if (clean.length != 6) return Color(0xFF1E88E5)
    val parsed = clean.toLongOrNull(16) ?: return Color(0xFF1E88E5)
    return Color(0xFF000000L or parsed)
}

/**
 * The theme, with the user's accent laid over the prototype's palette.
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
    val chosen = accentHex.ifEmpty { io.github.cubsync.service.AppSettings.ACCENT_DEFAULT }
    val cub = if (chosen.equals(base.accent.hex(), ignoreCase = true)) {
        base
    } else {
        val accent = parseHexColour(chosen)
        base.copy(
            accent = accent,
            accentSoft = accent.copy(alpha = 0.14f),
            accentDeep = lerp(accent, Color.Black, 0.22f),
            selectedLine = accent,
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
fun Color.hex(): String {
    fun part(value: Float): Int = (value * 255f).toInt().coerceIn(0, 255)
    return "#%02X%02X%02X".format(part(red), part(green), part(blue))
}
