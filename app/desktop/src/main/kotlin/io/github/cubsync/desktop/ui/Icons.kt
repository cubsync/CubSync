package io.github.cubsync.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import io.github.cubsync.desktop.CubTheme
import io.github.cubsync.desktop.CubType
import io.github.cubsync.service.IconPaths

/**
 * The prototype's SVG paths, drawn as vectors.
 *
 * These were characters until they met a font without an opinion about `\u2630` or
 * `\u22ee`, at which point the rail filled with small boxes. A path has no font to
 * depend on, which is the whole reason to pay for one here.
 */
fun cubIcon(
    path: String,
    size: Int = 24,
    stroke: Float = IconPaths.STROKE,
): ImageVector = ImageVector.Builder(
    name = "cubsync",
    defaultWidth = size.dp,
    defaultHeight = size.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    addPath(
        pathData = PathParser().parsePathString(path).toNodes(),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = stroke,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
}.build()

/** One icon, tinted. */
@Composable
fun CubIcon(
    path: String,
    modifier: Modifier = Modifier,
    size: Int = 18,
    stroke: Float = IconPaths.STROKE,
    tint: Color = CubTheme.colors.textDim,
) {
    Icon(
        imageVector = remember(path, size, stroke) { cubIcon(path, size, stroke) },
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(size.dp),
    )
}

/**
 * A round icon button.
 *
 * [size] is the hit area, not the drawing: the design's icon buttons are 26–34dp of
 * target around a 16–18dp mark.
 */
@Composable
fun IconButton(
    path: String,
    modifier: Modifier = Modifier,
    size: Int = 28,
    mark: Int = 17,
    stroke: Float = IconPaths.STROKE,
    selected: Boolean = false,
    enabled: Boolean = true,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val colors = CubTheme.colors
    val colour = when {
        !enabled -> colors.textFaint
        tint != null -> tint
        selected -> Color.White
        else -> colors.textDim
    }
    Box(
        modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(if (selected) colors.accent else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        CubIcon(path, size = mark, stroke = stroke, tint = colour)
    }
}

/** The same, drawn as text — for the few places a character is still the right answer. */
@Composable
fun TextGlyph(text: String, size: Int = 28, tint: Color = CubTheme.colors.textDim, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Text(text, style = CubType.label, color = tint, maxLines = 1)
    }
}

/** The rail's icon for a top-level entry. */
fun sectionIcon(section: io.github.cubsync.service.Section): String = when (section) {
    io.github.cubsync.service.Section.CONNECT -> IconPaths.NAV_CONNECT
    io.github.cubsync.service.Section.TRANSFER -> IconPaths.NAV_TRANSFER
    io.github.cubsync.service.Section.SYNC -> IconPaths.NAV_SYNC
    io.github.cubsync.service.Section.JOBS -> IconPaths.NAV_JOBS
    io.github.cubsync.service.Section.LOGS -> IconPaths.NAV_LOGS
    io.github.cubsync.service.Section.SETTINGS -> IconPaths.NAV_SETTINGS
}
