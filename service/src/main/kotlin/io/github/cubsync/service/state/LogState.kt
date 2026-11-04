package io.github.cubsync.service.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.cubsync.service.LogEntry
import io.github.cubsync.service.LogLevel

/**
 * 日志页的界面状态：日志行、最低级别过滤、搜索框。
 */
class LogState {
    var logs by mutableStateOf<List<LogEntry>>(emptyList())
    var logMinimumLevel by mutableStateOf(LogLevel.INFO)

    /** The log page's filter box, which the heading draws and the body reads. */
    var logQuery by mutableStateOf("")
}
