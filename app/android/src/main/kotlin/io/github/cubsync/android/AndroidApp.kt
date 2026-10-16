package io.github.cubsync.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.material3.ExperimentalMaterial3Api
import io.github.cubsync.service.BuildInfo

/**
 * The phone's shell: one screen at a time, with four tabs and a place to put a
 * message about what just happened.
 *
 * A phone has no room for a rail and no room for two panels, so a configuration or a
 * job opens as its own screen and the back arrow returns to the list. The names and
 * the states are the desktop's; the landing points are not, which is what the design
 * document asks for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CubAndroidApp(state: AndroidAppState) {
    val colors = CubTheme.colors
    val snackbar = remember { SnackbarHostState() }
    val notice = state.notice

    LaunchedEffect(notice) {
        if (notice != null) {
            snackbar.showSnackbar(notice.text)
            state.dismissNotice()
        }
    }

    Scaffold(
        containerColor = colors.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(topTitle(state), style = CubType.sectionTitle, color = colors.text)
                        Text(topSubtitle(state), style = CubType.meta, color = colors.textDim)
                    }
                },
                navigationIcon = {
                    if (state.route !is Route.Tabs) {
                        IconButton(onClick = { state.back() }) {
                            Text("←", style = CubType.sectionTitle, color = colors.text)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.surface,
                    titleContentColor = colors.text,
                ),
            )
        },
        bottomBar = {
            if (state.route is Route.Tabs) {
                BottomTabs(state)
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val route = state.route) {
                is Route.Tabs -> when (state.tab) {
                    Tab.CONNECT -> ConnectScreen(state)
                    Tab.TRANSFER -> TransferScreen(state)
                    Tab.SYNC -> SyncListScreen(state)
                    Tab.JOBS -> JobsListScreen(state)
                    Tab.SETTINGS -> SettingsScreen(state)
                }

                is Route.ConfigDetail -> {
                    val config = state.config(route.configId)
                    if (config == null) {
                        MissingScreen("配置不存在", "它可能已经被删除了。")
                    } else {
                        ConfigDetailScreen(state, config)
                    }
                }

                is Route.JobDetail -> {
                    val job = state.job(route.jobId)
                    if (job == null) {
                        MissingScreen("作业不存在", "它可能已经被清理了。")
                    } else {
                        JobDetailScreen(state, job)
                    }
                }
            }
        }
    }
}

private fun topTitle(state: AndroidAppState): String = when (state.route) {
    is Route.ConfigDetail -> "配置"
    is Route.JobDetail -> "作业详情"
    is Route.Tabs -> state.tab.label
}

private fun topSubtitle(state: AndroidAppState): String = when (state.route) {
    is Route.ConfigDetail -> "修改策略与文件夹对"
    is Route.JobDetail -> "这次运行做了什么"
    is Route.Tabs -> when (state.tab) {
        Tab.CONNECT -> "共享本机文件夹，或连上另一台设备"
        Tab.TRANSFER -> "两栏对照，把选中的东西送过去"
        Tab.SYNC -> "配置要同步的文件夹对，然后运行"
        Tab.JOBS -> "每次运行的结果与明细"
        Tab.SETTINGS -> "状态目录、默认策略与外观"
    }
}

/**
 * The bottom bar, drawn by hand.
 *
 * Material's navigation bar carries an icon size and a colour animation that do not
 * match the design's compact bar, and every tab here is a two-character glyph and a
 * word. A row of four is the whole component.
 */
@Composable
private fun BottomTabs(state: AndroidAppState) {
    val colors = CubTheme.colors
    Column {
        HorizontalDivider(color = colors.outline)
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            for (tab in Tab.entries) {
                val selected = state.tab == tab && state.route is Route.Tabs
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { state.go(tab) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        tabGlyph(tab),
                        style = CubType.body,
                        color = if (selected) colors.accent else colors.textFaint,
                    )
                    Text(
                        tab.label,
                        style = CubType.meta,
                        color = if (selected) colors.accent else colors.textDim,
                    )
                }
            }
        }
    }
}

private fun tabGlyph(tab: Tab): String = when (tab) {
    Tab.CONNECT -> "⇄"
    Tab.TRANSFER -> "⇅"
    Tab.SYNC -> "▤"
    Tab.JOBS -> "✓"
    Tab.SETTINGS -> "⚙"
}

@Composable
fun ScreenBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun MissingScreen(title: String, detail: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
        EmptyState(title, detail)
    }
}

/** The one container a screen puts its content in. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CubTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, colors.outline, RoundedCornerShape(14.dp)),
    ) {
        if (title != null) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp)) {
                Text(title, style = CubType.cardTitle, color = colors.text)
                if (subtitle != null) Text(subtitle, style = CubType.meta, color = colors.textDim)
            }
            HorizontalDivider(color = colors.outline)
        }
        Column(Modifier.padding(14.dp), content = content)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = CubType.label, color = CubTheme.colors.textDim)
}

@Composable
fun KeyValue(label: String, value: String, valueColor: Color = CubTheme.colors.text) {
    val colors = CubTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(label, modifier = Modifier.width(92.dp), style = CubType.meta, color = colors.textFaint)
        Text(value, style = CubType.body, color = valueColor, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Pill(text: String, color: Color) {
    Row(
        Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = CubType.meta, color = color)
    }
}

@Composable
fun EmptyState(title: String, detail: String = "") {
    val colors = CubTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = CubType.body, color = colors.textDim)
        if (detail.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(detail, style = CubType.meta, color = colors.textFaint)
        }
    }
}

@Composable
fun CubField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    isError: Boolean = false,
    keyboard: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
) {
    Column(modifier) {
        Text(label, style = CubType.meta, color = CubTheme.colors.textFaint)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            isError = isError,
            singleLine = true,
            textStyle = CubType.body,
            placeholder = { if (placeholder.isNotEmpty()) Text(placeholder, style = CubType.body) },
            shape = RoundedCornerShape(10.dp),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboard),
        )
    }
}

data class Choice<T>(val value: T, val label: String)

/** A pull-down that shows its current value, which is what a settings row needs. */
@Composable
fun <T> Dropdown(
    label: String,
    options: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CubTheme.colors
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.value == selected }

    Column(modifier) {
        Text(label, style = CubType.meta, color = colors.textFaint)
        Spacer(Modifier.height(4.dp))
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, colors.outline, RoundedCornerShape(10.dp))
                    .clickable { open = true }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    current?.label ?: "选择",
                    style = CubType.body,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("▾", style = CubType.meta, color = colors.textFaint)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (option in options) {
                    DropdownMenuItem(
                        text = { Text(option.label, style = CubType.body) },
                        onClick = {
                            open = false
                            onSelect(option.value)
                        },
                    )
                }
            }
        }
    }
}

/** Chips rather than a segmented control: they wrap, and a phone needs that. */
@Composable
fun <T> ChoiceChips(
    options: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (option in options) {
            FilterChip(
                selected = option.value == selected,
                onClick = { onSelect(option.value) },
                label = { Text(option.label, style = CubType.meta) },
            )
        }
    }
}

@Composable
fun ActionRow(vararg buttons: Pair<String, () -> Unit>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((label, action) in buttons) {
            TextButton(onClick = action, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(label, style = CubType.label)
            }
        }
    }
}

@Composable
fun VersionFooter() {
    val colors = CubTheme.colors
    Spacer(Modifier.height(6.dp))
    Text(
        "CubSync ${BuildInfo.VERSION} · 协议 ${BuildInfo.PROTOCOL_VERSION} · ${BuildInfo.PLATFORM}",
        style = CubType.meta,
        color = colors.textFaint,
    )
    Text(
        "协议没有加密与鉴权，请只在可信网络里共享文件夹。",
        style = CubType.meta,
        color = colors.warning,
    )
}
