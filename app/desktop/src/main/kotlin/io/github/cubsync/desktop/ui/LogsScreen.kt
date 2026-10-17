package io.github.cubsync.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.cubsync.desktop.AppState
import io.github.cubsync.desktop.CubType

/**
 * 日志, on its own.
 *
 * The phone folds this into the job it came from; the desktop keeps an entry of its own
 * because the window has room for one, and because "the log right now" and "what that run
 * did" are different questions.
 */
@Composable
fun LogsScreen(state: AppState) {
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        LogsBody(state, Modifier.fillMaxSize())
    }
}

/** 日志's `.chead .acts`: the filter box and 导出, as the prototype draws them. */
@Composable
fun LogsHeadActions(state: AppState) {
    Field(
        value = state.logQuery,
        onValueChange = { state.logQuery = it },
        modifier = Modifier.width(180.dp),
        placeholder = "过滤…",
        style = CubType.meta,
    )
    Spacer(Modifier.width(8.dp))
    QuietButton("导出") {
        val text = state.service.log.entries().joinToString("\n") { entry ->
            "${java.time.Instant.ofEpochMilli(entry.atMs)} ${entry.level} ${entry.area} ${entry.message}"
        }
        io.github.cubsync.desktop.copyToClipboard(text)
        state.notify("日志已复制到剪贴板", io.github.cubsync.desktop.NoticeKind.SUCCESS)
    }
}
