package io.github.cubsync.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.cubsync.desktop.AppState
import io.github.cubsync.desktop.CubTheme
import io.github.cubsync.desktop.CubType
import io.github.cubsync.desktop.NoticeKind
import io.github.cubsync.desktop.Section
import io.github.cubsync.service.BuildInfo
import io.github.cubsync.service.ServerState

/**
 * The window: a rail, the current screen, and a strip that says what just happened.
 *
 * Deliberately not a `Scaffold` with a `TopAppBar`: the status a person needs here
 * is not the screen's title but the two facts that decide whether anything can work
 * at all -- is this machine sharing, and is another machine connected.
 */
@Composable
fun CubApp(state: AppState) {
    val colors = CubTheme.colors
    val server by state.server.state.collectAsState()

    Column(Modifier.fillMaxSize().background(colors.background)) {
        Row(Modifier.weight(1f)) {
            Rail(state, server)
            Column(Modifier.fillMaxSize()) {
                when (state.section) {
                    Section.CONNECT -> ConnectScreen(state, server)
                    Section.TRANSFER -> TransferScreen(state)
                    Section.SYNC -> SyncScreen(state)
                    Section.JOBS -> JobsScreen(state)
                    Section.LOGS -> LogsScreen(state)
                    Section.SETTINGS -> SettingsScreen(state)
                }
            }
        }
        NoticeStrip(state)
    }
}

@Composable
private fun Rail(state: AppState, server: ServerState) {
    val colors = CubTheme.colors
    Column(
        Modifier
            .width(198.dp)
            .fillMaxHeight()
            .background(colors.rail)
            .padding(vertical = 14.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("CubSync", style = CubType.windowTitle, color = colors.text)
            Text("v${BuildInfo.VERSION}", style = CubType.meta, color = colors.textFaint)
        }
        Spacer(Modifier.height(16.dp))

        for (section in Section.entries) {
            RailItem(section, section == state.section) { state.goTo(section) }
        }

        Spacer(Modifier.weight(1f))

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (server.running) colors.success else colors.textFaint)
                Spacer(Modifier.width(7.dp))
                Text(
                    if (server.running) "共享中 :${server.port}" else "未共享",
                    style = CubType.meta,
                    color = colors.textDim,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (state.peers.isEmpty()) colors.textFaint else colors.accent)
                Spacer(Modifier.width(7.dp))
                Text(
                    if (state.peers.isEmpty()) "未连接远端" else "已连接 ${state.peers.size} 台",
                    style = CubType.meta,
                    color = colors.textDim,
                )
            }
            state.running?.let { run ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(colors.accent)
                    Spacer(Modifier.width(7.dp))
                    Text("${run.label}中", style = CubType.meta, color = colors.accent)
                }
            }
        }
    }
}

@Composable
private fun RailItem(section: Section, selected: Boolean, onClick: () -> Unit) {
    val colors = CubTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(if (selected) colors.accentSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(if (selected) colors.accent else Color.Transparent),
        )
        Spacer(Modifier.width(11.dp))
        Text(
            section.label,
            style = CubType.label,
            color = if (selected) colors.accent else colors.textDim,
        )
    }
}

@Composable
private fun NoticeStrip(state: AppState) {
    val notice = state.notice ?: return
    val colors = CubTheme.colors
    val tone = when (notice.kind) {
        NoticeKind.INFO -> colors.accent
        NoticeKind.SUCCESS -> colors.success
        NoticeKind.WARNING -> colors.warning
        NoticeKind.ERROR -> colors.danger
    }
    Box(Modifier.fillMaxWidth().background(colors.background).padding(start = 20.dp, end = 20.dp, bottom = 14.dp)) {
        Notice(notice.text, tone, onDismiss = { state.dismissNotice() })
    }
}
