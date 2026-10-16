package io.github.cubsync.android

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.cubsync.service.IconPaths
import io.github.cubsync.service.JobTone
import io.github.cubsync.service.LogLevel

/**
 * The pieces the phone's screens are built from.
 *
 * Every tappable thing here is at least 34dp and the list rows are at least 56dp, because
 * these are hit with a thumb and the design says so. The desktop's versions of these are
 * smaller on purpose; the words they carry are the same, and those come from `UiSpec`.
 *
 * A card is a raised block with no border, a section label sits above a group of them, and
 * a row inside one is a 40dp icon tile beside two lines of text. Those three things are
 * what "consistent" means on this end, so they are only defined once.
 */

// ---------------------------------------------------------------- cards

/** A raised block of content — the shape almost every screen is made of. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    outlined: Boolean = false,
    highlight: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CubTheme.colors
    val shape = RoundedCornerShape(20.dp)
    val border = when {
        highlight -> Modifier.border(1.5.dp, colors.accent, shape)
        outlined -> Modifier.border(1.dp, colors.outline, shape)
        else -> Modifier
    }
    Column(
        modifier
            .clip(shape)
            .background(if (outlined) Color.Transparent else colors.surfaceRaised)
            .then(border),
    ) {
        if (title != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = CubType.cardTitle, color = colors.text)
                    if (subtitle != null) Text(subtitle, style = CubType.rowDetail, color = colors.textDim)
                }
                actions()
            }
        }
        Column(Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** The grey sentence inside a card. */
@Composable
fun CardDesc(text: String, modifier: Modifier = Modifier) {
    Text(text, style = CubType.rowDetail, color = CubTheme.colors.textDim, lineHeight = 18.sp, modifier = modifier)
}

/** The small label above a group of cards. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = CubType.sectionLabel,
        color = CubTheme.colors.textDim,
        modifier = modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 2.dp),
    )
}

/**
 * A card whose body folds away.
 *
 * 运行模式 is the reason this exists: it matters enormously to the person fighting
 * permissions and not at all to anybody else, so its heading stays visible and carries the
 * answer while its body stays shut.
 */
@Composable
fun FoldCard(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CubTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceRaised),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggle,
                )
                .padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CubIcon(
                if (expanded) IconPaths.CHEVRON_DOWN else IconPaths.CHEVRON_RIGHT,
                size = 18,
                tint = colors.textDim,
            )
            Spacer(Modifier.width(8.dp))
            Text(title, style = CubType.cardTitle, color = colors.text)
            Spacer(Modifier.weight(1f))
            Text(
                summary,
                style = CubType.rowDetail,
                color = colors.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expanded) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 15.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

/** One line inside a card: an icon tile, a name, a second line, and whatever goes on the right. */
@Composable
fun CardRow(
    title: String,
    detail: String = "",
    modifier: Modifier = Modifier,
    icon: String? = null,
    tint: Color? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val colors = CubTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 2.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(colors.accentContainer),
                contentAlignment = Alignment.Center,
            ) {
                CubIcon(icon, size = 20, tint = colors.accent)
            }
            Spacer(Modifier.width(13.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = CubType.rowTitle,
                color = tint ?: colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    style = CubType.rowDetail,
                    color = colors.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) trailing()
    }
}

/** Which of the design's three button weights a card button is drawn in. */
enum class CardButtonKind { FILLED, TONAL, OUTLINED }

/** A full-width button at the bottom of a card. */
@Composable
fun CardButton(
    text: String,
    kind: CardButtonKind = CardButtonKind.FILLED,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = CubTheme.colors
    val shape = RoundedCornerShape(99.dp)
    val background = when (kind) {
        CardButtonKind.FILLED -> if (enabled) colors.accent else colors.surfaceSunken
        CardButtonKind.TONAL -> colors.accentContainer
        CardButtonKind.OUTLINED -> Color.Transparent
    }
    val foreground = when {
        !enabled -> colors.textFaint
        kind == CardButtonKind.FILLED -> Color.White
        kind == CardButtonKind.TONAL -> colors.accent
        else -> colors.text
    }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(background)
            .then(if (kind == CardButtonKind.OUTLINED) Modifier.border(1.dp, colors.outlineStrong, shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = CubType.buttonLabel, color = foreground, maxLines = 1)
    }
}

// ---------------------------------------------------------------- 档位

/** The pill row the design uses wherever a value is picked from a handful. */
@Composable
fun <T> ChoiceChips(
    options: List<T>,
    selected: T,
    modifier: Modifier = Modifier,
    label: (T) -> String = { it.toString() },
    onSelect: (T) -> Unit,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        for (option in options) {
            val active = option == selected
            Box(
                Modifier
                    .heightIn(min = 34.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(if (active) CubTheme.colors.accent else Color.Transparent)
                    .border(1.dp, if (active) CubTheme.colors.accent else CubTheme.colors.outlineStrong, RoundedCornerShape(99.dp))
                    .clickable { onSelect(option) }
                    .padding(horizontal = 13.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = CubType.label,
                    color = if (active) Color.White else CubTheme.colors.textDim,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The same pill row, for the values where more than one can be on. */
@Composable
fun <T> ToggleChips(
    options: List<T>,
    isOn: (T) -> Boolean,
    modifier: Modifier = Modifier,
    label: (T) -> String = { it.toString() },
    onToggle: (T) -> Unit,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        for (option in options) {
            val active = isOn(option)
            Box(
                Modifier
                    .heightIn(min = 34.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(if (active) CubTheme.colors.accent else Color.Transparent)
                    .border(1.dp, if (active) CubTheme.colors.accent else CubTheme.colors.outlineStrong, RoundedCornerShape(99.dp))
                    .clickable { onToggle(option) }
                    .padding(horizontal = 13.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = CubType.label,
                    color = if (active) Color.White else CubTheme.colors.textDim,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * A field that opens a list of choices.
 *
 * The phone has no room for a dropdown, so a value picked from many — 同步方向 has six —
 * is a row that says what is chosen now and opens a sheet when tapped.
 */
@Composable
fun SelectRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = CubTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceSunken)
            .border(1.dp, colors.outlineStrong, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, style = CubType.rowDetail, color = colors.textFaint)
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                value,
                style = CubType.body,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            CubIcon(IconPaths.CHEVRON_DOWN, size = 18, tint = colors.textDim)
        }
    }
}

// ---------------------------------------------------------------- 传输与作业的状态

/** A bar that grows from the left, for a run in flight. */
@Composable
fun ProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    tone: Color? = null,
    height: Int = 8,
) {
    val colors = CubTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape(99.dp))
            .background(if (colors.isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.10f)),
    ) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(99.dp))
                    .background(tone ?: colors.accent),
            )
        }
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

@Composable
fun StatusDot(color: Color, size: Int = 8) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

/** The round button in a sub-row, drawn with the same mark size as the top bar's. */
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

/** The floating action button a list screen carries. */
@Composable
fun Fab(icon: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .size(58.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(CubTheme.colors.accent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CubIcon(icon, size = 26, stroke = 2f, tint = Color.White)
    }
}

// ---------------------------------------------------------------- 按钮与输入

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
        modifier.fillMaxWidth().heightIn(min = 46.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = CubType.rowTitle, color = colors.text)
            if (hint.isNotEmpty()) Text(hint, style = CubType.rowDetail, color = colors.textFaint)
        }
        Spacer(Modifier.width(10.dp))
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(key, style = CubType.rowDetail, color = colors.textFaint, modifier = Modifier.width(92.dp))
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
    Text(text, style = CubType.rowDetail, color = CubTheme.colors.textFaint, modifier = modifier)
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
            Text(detail, style = CubType.rowDetail, color = colors.textFaint)
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
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(colors.surface)
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 20.dp),
            content = content,
        )
    }
}

/** The header of a bottom sheet: a title and a way out. */
@Composable
fun SheetHeader(title: String, onClose: () -> Unit) {
    val colors = CubTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = CubType.cardTitle, color = colors.text, modifier = Modifier.weight(1f))
        IconButton(IconPaths.CLOSE, size = 38, onClick = onClose)
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
    leadingIcon: String? = null,
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
        if (leadingIcon != null) {
            CubIcon(leadingIcon, size = 20, tint = colors.textFaint)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = CubType.rowTitle, color = tint ?: colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) {
                Text(detail, style = CubType.rowDetail, color = colors.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) trailing()
    }
}

// ---------------------------------------------------------------- 品牌

/**
 * The mark, at whatever size it is asked for.
 *
 * Two circles and a hole punched through the smaller one — the same three numbers the logo
 * build uses, so what appears in the product is the product's own logo rather than a
 * drawing of it. The hole is cleared rather than filled, because a fill would show as a
 * disc against whatever it happens to sit on.
 */
@Composable
fun BrandMark(size: Int = 34, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size.dp)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // The 120-unit design grid, scaled so the mark's bounding box fills the box.
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

const val DASH: String = "—"
