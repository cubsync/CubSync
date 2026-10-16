package io.github.cubsync.android

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.cubsync.service.Accent
import io.github.cubsync.service.ThemeMode

/**
 * The phone's tokens, which are the prototype's `--m3-*` set and **not** the desktop's.
 *
 * `--m3-surface` is `#141318`, `--m3-surface-2` is `#1f1f26`, `--m3-on` is `#e6e1e9`,
 * `--m3-onv` is `#c9c5d0`, `--m3-primary` is `#4c6ef5`. An earlier version reused the
 * desktop palette here, which is why the phone read as a small desktop rather than as the
 * other end of the same product: the two ends are supposed to share structure and words,
 * not hex.
 *
 * `--line` is the one token both ends do share, so the outline is the same value as the
 * desktop's and comes from the same declaration.
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
    background = Color(0xFF141318),
    surface = Color(0xFF141318),
    surfaceRaised = Color(0xFF1F1F26),
    surfaceSunken = Color(0xFF1F1F26),
    outline = Color(0xFF2A2F39),
    outlineStrong = Color(0xFFC9C5D0),
    text = Color(0xFFE6E1E9),
    textDim = Color(0xFFC9C5D0),
    textFaint = Color(0xFFC9C5D0),
    accent = Color(0xFF4C6EF5),
    accentContainer = Color(0xFF4C6EF5).copy(alpha = 0.18f),
    danger = Color(0xFFE5484D),
    warning = Color(0xFFE0A020),
    success = Color(0xFF2E9E5B),
    conflict = Color(0xFFF0731F),
    info = Color(0xFF3B82F6),
    rail = Color(0xFF1F1F26),
)

internal val LightCubColors = CubColors(
    isDark = false,
    background = Color(0xFFFBF8FF),
    surface = Color(0xFFFBF8FF),
    surfaceRaised = Color(0xFFF2EEF9),
    surfaceSunken = Color(0xFFF2EEF9),
    outline = Color(0xFFDDE3EA),
    outlineStrong = Color(0xFF44464F),
    text = Color(0xFF1B1B1F),
    textDim = Color(0xFF44464F),
    textFaint = Color(0xFF44464F),
    accent = Color(0xFF4C6EF5),
    accentContainer = Color(0xFF4C6EF5).copy(alpha = 0.18f),
    danger = Color(0xFFE5484D),
    warning = Color(0xFFE0A020),
    success = Color(0xFF2E9E5B),
    conflict = Color(0xFFF0731F),
    info = Color(0xFF3B82F6),
    rail = Color(0xFFF2EEF9),
)

val LocalCubColors = staticCompositionLocalOf { DarkCubColors }

object CubTheme {
    val colors: CubColors
        @Composable @ReadOnlyComposable
        get() = LocalCubColors.current
}

/**
 * Type scale, from the prototype's phone declarations.
 *
 * `.a-topbar .h` 22 for a top-level screen and `.h.sm` 17 for one opened from it, `.a-sec`
 * 11.5/700, `.a-card .ttl` 14/650, `.a-li .tx .n` 14/600 with an 11.5 second line,
 * `.a-btn` 14/650, `.a-nav .nv` 10.5/600.
 */
object CubType {
    /** The heading in the top bar. The prototype uses the 17px variant on every screen. */
    val topTitle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)

    /** `.h` without `.sm`, kept for the one screen that asks for it. */
    val topTitleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold)

    /** `.a-sec`. */
    val sectionLabel = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)

    /** `.a-card .ttl` and `.a-btn`. */
    val cardTitle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    val buttonLabel = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)

    /** `.a-li .tx .n` and its `.s` — the two sizes every card row uses. */
    val rowTitle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    val rowDetail = TextStyle(fontSize = 11.5.sp)

    /** `.a-chip` and `.a-seg button`: 12/600. */
    val chip = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

    /** `.a-nav .nv`. */
    val navLabel = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)

    val body = TextStyle(fontSize = 13.5.sp, lineHeight = 19.sp)
    val label = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
    val meta = TextStyle(fontSize = 11.5.sp)
    val big = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold)

    /** The percentage on the running screen, which is the one number that matters there. */
    val huge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold)

    val mono = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 17.sp)
}

/** `#RRGGBB` as a colour, with the prototype's phone accent as the fallback. */
fun parseHexColour(hex: String): Color {
    val clean = hex.removePrefix("#").trim()
    if (clean.length != 6) return Color(0xFF4C6EF5)
    val parsed = clean.toLongOrNull(16) ?: return Color(0xFF4C6EF5)
    return Color(0xFF000000L or parsed)
}

/**
 * The theme.
 *
 * Three ways the accent can arrive, in this order: 动态取色 takes the wallpaper's primary
 * through Material You (Android 12 and up, which is what the prototype's switch promises),
 * a chosen hue chip gives a fixed value, and failing both the palette's own
 * `--m3-primary` stands. Surfaces are never taken from Material You, because the
 * prototype fixes them and a wallpaper is not a reason to change a card's colour.
 */
@Composable
fun CubAndroidTheme(
    mode: ThemeMode,
    accentHex: String = Accent.DEFAULT.hex,
    dynamicColour: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val base = if (dark) DarkCubColors else LightCubColors
    val context = LocalContext.current
    val wallpaper = if (dynamicColour && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        runCatching {
            if (dark) dynamicDarkColorScheme(context).primary else dynamicLightColorScheme(context).primary
        }.getOrNull()
    } else {
        null
    }
    val chosen = wallpaper ?: parseHexColour(accentHex.ifEmpty { Accent.DEFAULT.hex })
    val cub = if (chosen == base.accent) {
        base
    } else {
        base.copy(accent = chosen, accentContainer = chosen.copy(alpha = 0.18f))
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

/** Kept so the light/dark pair can be blended where a border needs a mid tone. */
internal fun blend(a: Color, b: Color, t: Float): Color = lerp(a, b, t)
