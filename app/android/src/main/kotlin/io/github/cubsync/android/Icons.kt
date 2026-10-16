package io.github.cubsync.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import io.github.cubsync.service.IconPaths
import io.github.cubsync.service.Section

/**
 * The prototype's SVG paths, drawn as vectors.
 *
 * These were characters until they met a font without an opinion about `\u2630` or
 * `\u22ee`, at which point the navigation bar lost its icons and the rail filled with
 * boxes. A path has no font to depend on, which is the whole reason to pay for one here.
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

@Composable
fun CubIcon(
    path: String,
    modifier: Modifier = Modifier,
    size: Int = 20,
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
 * A round icon button at the design's hit area for a thumb.
 *
 * [size] is the target, not the drawing: the design's top-bar buttons are 38–40dp around
 * an 18–20dp mark, because a thumb is not a cursor.
 */
@Composable
fun IconButton(
    path: String,
    modifier: Modifier = Modifier,
    size: Int = 40,
    mark: Int = 19,
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
        contentAlignment = Alignment.Center,
    ) {
        CubIcon(path, size = mark, stroke = stroke, tint = colour)
    }
}

/** The bottom bar's icon for a top-level entry. */
fun sectionIcon(section: Section): String = when (section) {
    Section.CONNECT -> IconPaths.NAV_CONNECT
    Section.TRANSFER -> IconPaths.NAV_TRANSFER
    Section.SYNC -> IconPaths.NAV_SYNC
    Section.JOBS -> IconPaths.NAV_JOBS
    Section.LOGS -> IconPaths.NAV_LOGS
    Section.SETTINGS -> IconPaths.NAV_SETTINGS
}
