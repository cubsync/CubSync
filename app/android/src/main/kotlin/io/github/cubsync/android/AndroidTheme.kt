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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The design's Material tokens, with the finalised brand indigo as the accent.
 *
 * The desktop's tokens are the same names with the desktop's surface values; the two
 * ends share the palette and the semantics, not every single value.
 */
data class CubColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val outline: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    val accent: Color,
    val accentContainer: Color,
    val onAccent: Color,
    val danger: Color,
    val warning: Color,
    val success: Color,
    val conflict: Color,
)

private val DarkCubColors = CubColors(
    isDark = true,
    background = Color(0xFF0F1116),
    surface = Color(0xFF141318),
    surfaceRaised = Color(0xFF1F1F26),
    outline = Color(0xFF3A3A44),
    text = Color(0xFFE6E1E9),
    textDim = Color(0xFFC9C5D0),
    textFaint = Color(0xFF8E8A96),
    accent = Color(0xFF3A5CCC),
    accentContainer = Color(0xFF232B4A),
    onAccent = Color(0xFFFFFFFF),
    danger = Color(0xFFE5484D),
    warning = Color(0xFFE0A020),
    success = Color(0xFF2E9E5B),
    conflict = Color(0xFFF0731F),
)

private val LightCubColors = CubColors(
    isDark = false,
    background = Color(0xFFF2EEF9),
    surface = Color(0xFFFBF8FF),
    surfaceRaised = Color(0xFFFFFFFF),
    outline = Color(0xFFC9C5D0),
    text = Color(0xFF1B1B1F),
    textDim = Color(0xFF44464F),
    textFaint = Color(0xFF767680),
    accent = Color(0xFF3A5CCC),
    accentContainer = Color(0xFFE4E9FA),
    onAccent = Color(0xFFFFFFFF),
    danger = Color(0xFFE5484D),
    warning = Color(0xFFB26A00),
    success = Color(0xFF1B8A5A),
    conflict = Color(0xFFF0731F),
)

val LocalCubColors = staticCompositionLocalOf { DarkCubColors }

object CubTheme {
    val colors: CubColors
        @Composable @ReadOnlyComposable
        get() = LocalCubColors.current
}

object CubType {
    val sectionTitle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    val cardTitle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val label = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
    val meta = TextStyle(fontSize = 12.sp)
    val big = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
    val mono = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 17.sp)
}

@Composable
fun CubAndroidTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val cub = if (dark) DarkCubColors else LightCubColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = cub.accent,
            onPrimary = cub.onAccent,
            primaryContainer = cub.accentContainer,
            onPrimaryContainer = cub.text,
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
            onPrimary = cub.onAccent,
            primaryContainer = cub.accentContainer,
            onPrimaryContainer = cub.text,
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
