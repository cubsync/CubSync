package io.github.cubsync.android

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.cubsync.service.JobTone
import io.github.cubsync.service.LogLevel

/**
 * The pieces the phone's screens are built from.
 *
 * Every tappable thing here is at least 34dp and the list rows are at least 56dp, because
 * these are hit with a thumb and the design says so. The desktop's versions of these are
 * smaller on purpose; the words they carry are the same, and those come from `UiSpec`.
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
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .border(1.dp, colors.outline, RoundedCornerShape(16.dp)),
    ) {
        if (title != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 10.dp),
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

/** A text button at the design's hit area for a thumb. */
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
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (danger && enabled) colors.danger.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = CubType.label, color = tint, maxLines = 1)
    }
}

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
            .heightIn(min = 38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) colors.accent else colors.surfaceRaised)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = CubType.label, color = if (enabled) Color.White else colors.textFaint, maxLines = 1)
    }
}

/** The round glyph button the sub-row is made of. */
@Composable
fun GlyphButton(
    glyph: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    size: Int = 38,
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

@Composable
fun Chip(text: String, tone: Color? = null, modifier: Modifier = Modifier) {
    val colors = CubTheme.colors
    val tint = tone ?: colors.textDim
    Box(
        modifier
            .clip(RoundedCornerShape(99.dp))
            .background(tint.copy(alpha = 0.16f))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(text, style = CubType.meta, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

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
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (option in options) {
            val active = option == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(if (active) colors.accent else Color.Transparent)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 11.dp, vertical = 7.dp),
            ) {
                Text(label(option), style = CubType.label, color = if (active) Color.White else colors.textDim, maxLines = 1)
            }
        }
    }
}

/** A labelled row with a control on the right. */
@Composable
fun SettingRow(
    label: String,
    hint: String = "",
    modifier: Modifier = Modifier,
    control: @Composable () -> Unit,
) {
    val colors = CubTheme.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 46.dp).padding(vertical = 4.dp),
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

@Composable
fun KeyValueRow(key: String, value: String, dim: Boolean = false) {
    val colors = CubTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(key, style = CubType.meta, color = colors.textFaint, modifier = Modifier.width(88.dp))
        Text(
            value.ifEmpty { "—" },
            style = CubType.body,
            color = if (dim) colors.textDim else colors.text,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    numeric: Boolean = false,
    style: TextStyle = CubType.body,
    onSubmit: (() -> Unit)? = null,
) {
    val colors = CubTheme.colors
    Box(
        modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (enabled) colors.surfaceSunken else colors.surfaceRaised)
            .border(1.dp, colors.outlineStrong, RoundedCornerShape(9.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
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
            keyboardOptions = KeyboardOptions(
                keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke() }, onGo = { onSubmit?.invoke() }),
        )
    }
}

/** A number that can be typed and stepped. 行密度 and 列数 both use it. */
@Composable
fun StepperField(
    value: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    canDecrease: Boolean = true,
    canIncrease: Boolean = true,
    step: Int = 1,
    suffix: String = "",
    fieldWidth: Int = 36,
) {
    val colors = CubTheme.colors
    var typed by remember(value) { mutableStateOf(value.toString()) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (canDecrease) colors.surfaceSunken else Color.Transparent)
                .clickable(enabled = canDecrease) { onChange(value - step) },
            contentAlignment = Alignment.Center,
        ) { Text("−", style = CubType.label, color = if (canDecrease) colors.text else colors.textFaint) }

        Row(
            Modifier
                .padding(horizontal = 4.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(colors.surfaceSunken)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = typed,
                onValueChange = { typed = it.filter { ch -> ch.isDigit() } },
                singleLine = true,
                textStyle = CubType.label.copy(color = colors.text),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.width(fieldWidth.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        val parsed = typed.toIntOrNull()
                        if (parsed != null) onChange(parsed)
                        typed = (parsed ?: value).toString()
                    },
                ),
            )
            if (suffix.isNotEmpty()) Text(suffix, style = CubType.meta, color = colors.textFaint)
        }

        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (canIncrease) colors.surfaceSunken else Color.Transparent)
                .clickable(enabled = canIncrease) { onChange(value + step) },
            contentAlignment = Alignment.Center,
        ) { Text("＋", style = CubType.label, color = if (canIncrease) colors.text else colors.textFaint) }
    }
}

@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = CubType.meta, color = CubTheme.colors.textFaint, modifier = modifier)
}

@Composable
fun StatusDot(color: Color, size: Int = 8) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

@Composable
fun EmptyState(text: String, detail: String = "", action: (@Composable () -> Unit)? = null) {
    val colors = CubTheme.colors
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = CubType.body, color = colors.textDim)
        if (detail.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(detail, style = CubType.meta, color = colors.textFaint)
        }
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            action()
        }
    }
}

/** A card that floats above the tab bar: a menu, a sheet. */
@Composable
fun BottomSheet(
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CubTheme.colors
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
        )
        Column(
            modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(colors.surfaceRaised)
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 20.dp),
            content = content,
        )
    }
}

/** The header of a bottom sheet: a grab handle, a title and a way out. */
@Composable
fun SheetHeader(title: String, onClose: () -> Unit) {
    val colors = CubTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = CubType.cardTitle, color = colors.text, modifier = Modifier.weight(1f))
        GlyphButton("×", size = 30, onClick = onClose)
    }
    Spacer(Modifier.height(4.dp))
}

/** One line of a list: a name, a detail and optional trailing controls. */
@Composable
fun ListRow(
    title: String,
    detail: String = "",
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    tint: Color? = null,
    leading: String = "",
    onClick: (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val colors = CubTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.accentContainer else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading.isNotEmpty()) {
            Text(leading, style = CubType.label, color = colors.textFaint, modifier = Modifier.width(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = CubType.body, color = tint ?: colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) {
                Text(detail, style = CubType.meta, color = colors.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) trailing()
    }
}

@Composable
fun toneColor(tone: JobTone): Color {
    val colors = CubTheme.colors
    return when (tone) {
        JobTone.GOOD -> colors.success
        JobTone.WARN -> colors.warning
        JobTone.BAD -> colors.danger
    }
}

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

/** A bar that grows from the left, for a run in flight. */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val colors = CubTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
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

const val DASH: String = "—"
