package io.github.cubsync.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.cubsync.desktop.AppState

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
