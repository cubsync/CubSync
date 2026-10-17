package io.github.cubsync.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.cubsync.desktop.CubTheme
import io.github.cubsync.desktop.CubType
import io.github.cubsync.service.IconPaths
import io.github.cubsync.service.JobTone
import io.github.cubsync.service.LogLevel

/**
 * The pieces every screen is built from.
 *
 * They are here rather than in a shared module because they are pixels, and the two
 * platforms are allowed to draw differently. What they are not allowed to do is use a
 * different word, or a different number of steps, for the same thing — and that is
 * enforced by the enums in `UiSpec` rather than by anything in this file.
 */

/**
 * The one container every screen puts its content in.
 *
 * [content] is a column scope, so a panel that needs a row of its own can just write one.
 * A version of this that centred its content instead made every two-row panel look like
 * a mistake until it was noticed.
 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CubTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, colors.outline, RoundedCornerShape(14.dp)),
    ) {
        if (title != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = CubType.cardTitle, color = colors.text)
                    if (subtitle != null) Text(subtitle, style = CubType.meta, color = colors.textDim)
                }
                actions()
            }
            HorizontalDivider(color = colors.outline)
        }
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/** A button that reads as text until you look at it. */
@Composable
fun QuietButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = CubTheme.colors
    val tint = when {
        !enabled -> colors.textFaint
        danger -> colors.danger
        else -> colors.text
    }
    Box(
        modifier
            .heightIn(min = 26.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (danger && enabled) colors.danger.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = CubType.label, color = tint, maxLines = 1)
    }
}

/** A filled button, for the one action a panel exists to perform. */
@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = CubTheme.colors
    Box(
        modifier
            .heightIn(min = 28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) colors.accent else colors.surfaceRaised)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = CubType.label, color = if (enabled) Color.White else colors.textFaint, maxLines = 1)
    }
}

/** A round glyph button, at the design's hit area for a pointer. */
@Composable
fun GlyphButton(
    glyph: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    size: Int = 28,
    onClick: () -> Unit,
) {
    val colors = CubTheme.colors
    Box(
        modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(if (selected) colors.accent else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            style = CubType.label,
            color = when {
                !enabled -> colors.textFaint
                selected -> Color.White
                else -> colors.textDim
            },
            maxLines = 1,
        )
    }
}

/** A small pill of text: a count, a state, a mode. */
@Composable
fun Chip(text: String, tone: Color? = null, modifier: Modifier = Modifier) {
    val colors = CubTheme.colors
    val tint = tone ?: colors.textDim
    Box(
        modifier
            .clip(RoundedCornerShape(99.dp))
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, style = CubType.meta, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A segmented control: the design's way of showing mutually exclusive choices. */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    modifier: Modifier = Modifier,
    label: (T) -> String = { it.toString() },
    onSelect: (T) -> Unit,
) {
    val colors = CubTheme.colors
    Row(
        modifier
            .clip(RoundedCornerShape(99.dp))
            .background(colors.surfaceRaised)
            .border(1.dp, colors.outline, RoundedCornerShape(99.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (option in options) {
            val active = option == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(if (active) colors.accent else Color.Transparent)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    label(option),
                    style = CubType.label,
                    color = if (active) Color.White else colors.textDim,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A labelled row holding whatever control the caller puts on the right. */
@Composable
fun SettingRow(
    label: String,
    hint: String = "",
    modifier: Modifier = Modifier,
    control: @Composable () -> Unit,
) {
    val colors = CubTheme.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 34.dp).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = CubType.label, color = colors.text)
            if (hint.isNotEmpty()) Text(hint, style = CubType.meta, color = colors.textFaint)
        }
        control()
    }
}

@Composable
fun Toggle(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val colors = CubTheme.colors
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = colors.accent,
            uncheckedThumbColor = colors.textFaint,
            uncheckedTrackColor = colors.surfaceSunken,
            uncheckedBorderColor = colors.outline,
        ),
    )
}

/** A row of label and value, for the key/value blocks. */
@Composable
fun KeyValueRow(key: String, value: String, dim: Boolean = false) {
    val colors = CubTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Text(key, style = CubType.meta, color = colors.textFaint, modifier = Modifier.width(96.dp))
        Text(
            value.ifEmpty { "—" },
            style = CubType.body,
            color = if (dim) colors.textDim else colors.text,
            // A path in a 300dp panel is longer than the panel. One line and an ellipsis
            // keeps the row a row; the full value is a copy button away.
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * A text field that looks like the surface it sits on.
 *
 * The Material field carries its own container colour and label animation, and every one
 * of them fights the panel it is dropped into. This is the input and the caret.
 */
@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    style: TextStyle = CubType.body,
    onSubmit: (() -> Unit)? = null,
) {
    val colors = CubTheme.colors
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) colors.surfaceSunken else colors.surfaceRaised)
            .border(1.dp, colors.outlineStrong, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            Text(placeholder, style = style, color = colors.textFaint, maxLines = 1)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = style.copy(color = colors.text),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.fillMaxWidth(),
            keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke() }, onGo = { onSubmit?.invoke() }),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
    }
}

/**
 * A number that can be typed, with − and ＋ beside it.
 *
 * 行密度 and 列数 are both "a number with a step", and both have to accept a typed value
 * as well as a pressed button: a person who knows they want 140% should not have to
 * press ＋ eight times.
 */
@Composable
fun StepperField(
    value: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    canDecrease: Boolean = true,
    canIncrease: Boolean = true,
    step: Int = 1,
    suffix: String = "",
    fieldWidth: Int = 44,
) {
    val colors = CubTheme.colors
    var typed by remember(value) { mutableStateOf(value.toString()) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (canDecrease) colors.surfaceSunken else Color.Transparent)
                .clickable(enabled = canDecrease) { onChange(value - step) },
            contentAlignment = Alignment.Center,
        ) { CubIcon(IconPaths.MINUS, size = 15, tint = if (canDecrease) colors.text else colors.textFaint) }

        Row(
            Modifier
                .padding(horizontal = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.surfaceSunken)
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = typed,
                onValueChange = { typed = it.filter { ch -> ch.isDigit() } },
                singleLine = true,
                textStyle = CubType.label.copy(color = colors.text),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.width(fieldWidth.dp),
                keyboardActions = KeyboardActions(
                    onDone = {
                        val parsed = typed.toIntOrNull()
                        if (parsed != null) onChange(parsed)
                        typed = (parsed ?: value).toString()
                    },
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            if (suffix.isNotEmpty()) Text(suffix, style = CubType.meta, color = colors.textFaint)
        }

        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (canIncrease) colors.surfaceSunken else Color.Transparent)
                .clickable(enabled = canIncrease) { onChange(value + step) },
            contentAlignment = Alignment.Center,
        ) { CubIcon(IconPaths.PLUS, size = 15, tint = if (canIncrease) colors.text else colors.textFaint) }
    }
}

/** A quiet label above a group of rows. */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = CubType.meta, color = CubTheme.colors.textFaint, modifier = modifier)
}

/** A coloured dot, for a state that is easier to see than to read. */
@Composable
fun StatusDot(color: Color, size: Int = 7) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

@Composable
fun EmptyState(text: String, detail: String = "", action: (@Composable () -> Unit)? = null) {
    val colors = CubTheme.colors
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = CubType.body, color = colors.textDim)
        if (detail.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(detail, style = CubType.meta, color = colors.textFaint)
        }
        if (action != null) {
            Spacer(Modifier.height(10.dp))
            action()
        }
    }
}

/** A panel that floats over the window: a menu, a sheet, a short form. */
@Composable
fun FloatingCard(
    modifier: Modifier = Modifier,
    width: Int = 260,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CubTheme.colors
    Column(
        modifier
            .width(width.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceRaised)
            .border(1.dp, colors.outlineStrong, RoundedCornerShape(14.dp))
            .padding(12.dp),
        content = content,
    )
}

/** The header of a floating card: a title and a way out. */
@Composable
fun FloatingHeader(title: String, onClose: () -> Unit) {
    val colors = CubTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = CubType.cardTitle, color = colors.text, modifier = Modifier.weight(1f))
        IconButton(IconPaths.CLOSE, size = 28, mark = 14, onClick = onClose)
    }
    Spacer(Modifier.height(6.dp))
}

/** A click-eating scrim, so clicking outside a pop-up closes it. */
@Composable
fun Scrim(onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.28f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    )
}

/** A progress bar in the design's shape: a thin track with a filled proportion. */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, height: Int = 6) {
    val colors = CubTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape(99.dp))
            .background(colors.surfaceSunken),
    ) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(99.dp))
                    .background(colors.accent),
            )
        }
    }
}

/** A heading inside a panel, smaller than the panel's own title. */
@Composable
fun SubHeading(text: String, trailing: String = "") {
    val colors = CubTheme.colors
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = CubType.label, color = colors.textDim, modifier = Modifier.weight(1f))
        if (trailing.isNotEmpty()) Text(trailing, style = CubType.meta, color = colors.textFaint)
    }
}

/** One line of a list: a name, a detail and an optional trailing control. */
@Composable
fun ListLine(
    title: String,
    detail: String = "",
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    tint: Color? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val colors = CubTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.selected else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = CubType.body, color = tint ?: colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) {
                Text(detail, style = CubType.meta, color = colors.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) trailing()
    }
}

/** A small headed block, used to group rows inside a long panel. */
@Composable
fun Block(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        GroupLabel(title)
        Spacer(Modifier.height(3.dp))
        content()
    }
}

/** The colour a result tone has. Kept here so two screens cannot pick two reds. */
@Composable
fun toneColor(tone: JobTone): Color {
    val colors = CubTheme.colors
    return when (tone) {
        JobTone.GOOD -> colors.success
        JobTone.WARN -> colors.warning
        JobTone.BAD -> colors.danger
    }
}

/** The colour a log level has. */
@Composable
fun levelColor(level: LogLevel): Color {
    val colors = CubTheme.colors
    return when (level) {
        LogLevel.DEBUG -> colors.textFaint
        LogLevel.INFO -> colors.textDim
        LogLevel.WARN -> colors.warning
        LogLevel.ERROR -> colors.danger
    }
}

/** What an unset value looks like, so it is never confused with an empty string. */
const val DASH: String = "—"

/**
 * The mark: two circles and a hole punched through the smaller one.
 *
 * The same three numbers the brand build script derives the shipped icon from, so what
 * appears in the window is the product's own logo rather than a drawing of it. The hole is
 * cleared rather than filled, because a fill shows as a disc against whatever it sits on.
 */
@Composable
fun BrandMark(size: Int = 22, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size.dp)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val side = this.size.minDimension
            val scale = side / 77.4f
            fun x(v: Float) = (v - 64.7f) * scale + side / 2f
            fun y(v: Float) = (v - 62.55f) * scale + side / 2f

            // The mark's own two colours, not the interface tokens.
            //
            // Drawing it with `accent`/`info` meant that changing the palette changed the
            // logo — which is exactly how the small circle stopped being teal.
            drawCircle(Color(io.github.cubsync.service.Brand.PRIMARY_ARGB), radius = 34f * scale, center = Offset(x(60f), y(60f)))
            drawCircle(Color(io.github.cubsync.service.Brand.ACCENT_ARGB), radius = 23f * scale, center = Offset(x(80.4f), y(76.1f)))
            drawCircle(
                Color.Black,
                radius = 8f * scale,
                center = Offset(x(80.4f), y(76.1f)),
                blendMode = BlendMode.Clear,
            )
        }
    }
}
