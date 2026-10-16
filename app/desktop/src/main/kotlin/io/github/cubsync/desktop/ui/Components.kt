package io.github.cubsync.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.cubsync.desktop.CubTheme
import io.github.cubsync.desktop.CubType

/** A screen's heading, with room for the buttons that act on the whole screen. */
@Composable
fun ScreenHeader(title: String, subtitle: String, actions: @Composable RowScope.() -> Unit = {}) {
    val colors = CubTheme.colors
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = CubType.sectionTitle, color = colors.text)
            Spacer(Modifier.weight(1f))
            actions()
        }
        if (subtitle.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            Text(subtitle, style = CubType.meta, color = colors.textDim)
        }
    }
}

/** The one container every screen puts its content in. */
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
                Column {
                    Text(title, style = CubType.cardTitle, color = colors.text)
                    if (subtitle != null) Text(subtitle, style = CubType.meta, color = colors.textDim)
                }
                Spacer(Modifier.weight(1f))
                actions()
            }
            HorizontalDivider(color = colors.outline)
        }
        // A Column, not a Box: a panel's content is a stack of rows, and a Box would
        // draw them on top of one another.
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/** A label above a control, so a form reads down the page. */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = CubType.meta, color = CubTheme.colors.textDim)
}

@Composable
fun CubField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    isError: Boolean = false,
) {
    val colors = CubTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.heightIn(min = 46.dp),
        enabled = enabled,
        isError = isError,
        singleLine = true,
        textStyle = CubType.body,
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder, style = CubType.meta, color = colors.textFaint) },
        shape = RoundedCornerShape(9.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.accent,
            unfocusedBorderColor = colors.outline,
            errorBorderColor = colors.danger,
            focusedContainerColor = colors.surfaceRaised,
            unfocusedContainerColor = colors.surfaceRaised,
            focusedTextColor = colors.text,
            unfocusedTextColor = colors.text,
            cursorColor = colors.accent,
            focusedPlaceholderColor = colors.textFaint,
            unfocusedPlaceholderColor = colors.textFaint,
        ),
    )
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(34.dp),
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = CubTheme.colors.accent,
            contentColor = Color.White,
        ),
    ) {
        Text(text, style = CubType.label)
    }
}

@Composable
fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.height(34.dp),
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        Text(
            text,
            style = CubType.label,
            color = when {
                !enabled -> CubTheme.colors.textFaint
                danger -> CubTheme.colors.danger
                else -> CubTheme.colors.textDim
            },
        )
    }
}

/** A state word with a coloured dot; the dot carries the meaning at a glance. */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(99.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = CubType.meta, color = color)
    }
}

@Composable
fun KeyValue(label: String, value: String, wide: Boolean = false, valueColor: Color = CubTheme.colors.text) {
    val colors = CubTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(
            label,
            modifier = Modifier.width(if (wide) 120.dp else 96.dp),
            style = CubType.meta,
            color = colors.textFaint,
        )
        Text(value, style = CubType.body, color = valueColor, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun EmptyState(title: String, detail: String = "", action: (@Composable () -> Unit)? = null) {
    val colors = CubTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = CubType.body, color = colors.textDim)
        if (detail.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(detail, style = CubType.meta, color = colors.textFaint)
        }
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            action()
        }
    }
}

data class Choice<T>(val value: T, val label: String, val enabled: Boolean = true)

/** A segmented control. The selected segment is a solid accent block, per the design. */
@Composable
fun <T> SegmentedChoice(
    options: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CubTheme.colors
    Row(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(colors.surfaceRaised)
            .border(1.dp, colors.outline, RoundedCornerShape(9.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (option in options) {
            val isSelected = option.value == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (isSelected) colors.accent else Color.Transparent)
                    .clickable(enabled = option.enabled) { onSelect(option.value) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    option.label,
                    style = CubType.label,
                    color = when {
                        !option.enabled -> colors.textFaint
                        isSelected -> Color.White
                        else -> colors.textDim
                    },
                )
            }
        }
    }
}

/** A pull-down that shows its current value, which is what a form row needs. */
@Composable
fun <T> Dropdown(
    options: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "选择",
) {
    val colors = CubTheme.colors
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.value == selected }

    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(colors.surfaceRaised)
                .border(1.dp, colors.outline, RoundedCornerShape(9.dp))
                .clickable(enabled = options.isNotEmpty()) { open = true }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                current?.label ?: placeholder,
                style = CubType.body,
                color = if (current == null) colors.textFaint else colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("▾", style = CubType.meta, color = colors.textFaint)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (option in options) {
                DropdownMenuItem(
                    text = {
                        Text(
                            option.label,
                            style = CubType.body,
                            color = if (option.enabled) colors.text else colors.textFaint,
                        )
                    },
                    enabled = option.enabled,
                    onClick = {
                        open = false
                        onSelect(option.value)
                    },
                )
            }
        }
    }
}

@Composable
fun StatusDot(color: Color, size: androidx.compose.ui.unit.Dp = 7.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** A horizontal bar for "how far along", used instead of a spinner for runs. */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val colors = CubTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(99.dp))
            .background(colors.surfaceRaised),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(colors.accent),
        )
    }
}

/** A one-line message with a tone, used for what just happened. */
@Composable
fun Notice(text: String, color: Color, onDismiss: () -> Unit) {
    val colors = CubTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicMonospace(text, color)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onDismiss, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("关闭", style = CubType.meta, color = colors.textDim)
        }
    }
}

@Composable
private fun BasicMonospace(text: String, color: Color) {
    BasicText(text, style = CubType.body.copy(color = color))
}

/** The empty rectangle a screen shows while it has nothing to say. */
@Composable
fun SpacerLine(modifier: Modifier = Modifier) {
    Spacer(modifier.fillMaxWidth().height(1.dp).background(CubTheme.colors.outline))
}
