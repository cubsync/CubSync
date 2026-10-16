package io.github.cubsync.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import io.github.cubsync.desktop.AppState
import io.github.cubsync.desktop.CubTheme
import io.github.cubsync.desktop.CubType
import io.github.cubsync.desktop.Format
import io.github.cubsync.desktop.Section
import io.github.cubsync.service.LogEntry
import io.github.cubsync.service.LogLevel

/**
 * The running record.
 *
 * This is the screen that turns "it did something odd" into a diagnosis: which
 * machine connected, which files were compared, which write failed. It is a live
 * list rather than a file, because the useful moment to read it is while something
 * is happening, and the level filter is what keeps a ten-thousand-file run readable.
 */
@Composable
fun LogsScreen(state: AppState) {
    val colors = CubTheme.colors
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    var areaFilter by remember { mutableStateOf<String?>(null) }

    val visible = state.logs.filter { it.level >= state.logMinimumLevel && (areaFilter == null || it.area == areaFilter) }
    val areas = state.logs.map { it.area }.distinct().sorted()

    LaunchedEffect(visible.size, follow) {
        if (follow && visible.isNotEmpty()) {
            listState.scrollToItem(visible.size - 1)
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            Section.LOGS.label,
            Section.LOGS.hint,
            actions = {
                SegmentedChoice(
                    options = listOf(
                        Choice(LogLevel.DEBUG, "全部"),
                        Choice(LogLevel.INFO, "信息"),
                        Choice(LogLevel.WARN, "警告"),
                        Choice(LogLevel.ERROR, "错误"),
                    ),
                    selected = state.logMinimumLevel,
                    onSelect = { state.logMinimumLevel = it },
                )
                Spacer(Modifier.width(8.dp))
                QuietButton(if (follow) "跟随中" else "跟随", onClick = { follow = !follow })
                QuietButton("清空", onClick = { state.clearLogs() })
            },
        )

        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 10.dp)) {
            SegmentedChoice(
                options = listOf(Choice<String?>(null, "全部来源")) + areas.map { Choice<String?>(it, areaLabel(it)) },
                selected = areaFilter,
                onSelect = { areaFilter = it },
            )
        }

        Column(
            Modifier
                .weight(1f)
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surface),
        ) {
            if (visible.isEmpty()) {
                EmptyState("没有符合条件的日志", if (state.logs.isEmpty()) "还没有任何记录。" else "换一个筛选条件试试。")
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(vertical = 6.dp), state = listState) {
                    items(visible) { entry -> LogRow(entry) }
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val colors = CubTheme.colors
    val tone = when (entry.level) {
        LogLevel.DEBUG -> colors.textFaint
        LogLevel.INFO -> colors.textDim
        LogLevel.WARN -> colors.warning
        LogLevel.ERROR -> colors.danger
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(Format.clockTime(entry.atMs), style = CubType.monoDim, color = colors.textFaint)
        Spacer(Modifier.width(10.dp))
        Text(
            areaLabel(entry.area).padEnd(4),
            style = CubType.monoDim,
            color = colors.textFaint,
            modifier = Modifier.width(58.dp),
        )
        Text(
            entry.message,
            style = CubType.monoDim,
            color = tone,
            modifier = Modifier.weight(1f),
        )
        if (entry.detail.isNotEmpty()) {
            Spacer(Modifier.width(12.dp))
            Text(entry.detail, style = CubType.monoDim, color = colors.textFaint)
        }
    }
}

internal fun areaLabel(area: String): String = when (area) {
    "server" -> "服务端"
    "connect" -> "连接"
    "job" -> "作业"
    "pair" -> "文件夹对"
    "file" -> "文件"
    else -> area
}

@Composable
internal fun levelColor(level: LogLevel): Color = when (level) {
    LogLevel.DEBUG -> CubTheme.colors.textFaint
    LogLevel.INFO -> CubTheme.colors.textDim
    LogLevel.WARN -> CubTheme.colors.warning
    LogLevel.ERROR -> CubTheme.colors.danger
}

/** Padding helper kept here because only the log rows need it. */
private fun String.padEnd(width: Int): String =
    if (length >= width) substring(0, width) else this + " ".repeat(width - length)
