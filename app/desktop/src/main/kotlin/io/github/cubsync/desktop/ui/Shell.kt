package io.github.cubsync.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.cubsync.desktop.AppState
import io.github.cubsync.desktop.CubTheme
import io.github.cubsync.desktop.CubType
import io.github.cubsync.desktop.Notice
import io.github.cubsync.desktop.NoticeKind
import io.github.cubsync.service.EndpointId
import io.github.cubsync.service.Section
import io.github.cubsync.service.ServerState
import io.github.cubsync.service.Words
import io.github.cubsync.service.sectionHint

/**
 * The window: a rail, a title bar, and whichever screen is open.
 *
 * The rail carries all six entries, including 日志, which the phone folds into 作业 — the
 * design keeps it separate here because a desktop has the room, and one extra entry is
 * not a second concept.
 */
@Composable
fun CubApp(state: AppState) {
    val server by state.server.state.collectAsState()
    Box(Modifier.fillMaxSize().background(CubTheme.colors.background)) {
        Row(Modifier.fillMaxSize()) {
            Rail(state, server)
            Column(Modifier.fillMaxSize()) {
                TopBar(state, server)
                Box(Modifier.fillMaxSize()) {
                    when (state.section) {
                        Section.CONNECT -> ConnectScreen(state, server)
                        Section.TRANSFER -> TransferScreen(state)
                        Section.SYNC -> SyncScreen(state)
                        Section.JOBS -> JobsScreen(state, showLogs = false)
                        Section.LOGS -> LogsScreen(state)
                        Section.SETTINGS -> SettingsScreen(state)
                    }
                }
            }
        }
        NoticeBar(state, Modifier.align(Alignment.BottomCenter))
    }
}

/** The left rail: where you can go, and what is shared right now. */
@Composable
private fun Rail(state: AppState, server: ServerState) {
    val colors = CubTheme.colors
    Column(
        Modifier
            .width(196.dp)
            .fillMaxSize()
            .background(colors.rail)
            .padding(vertical = 10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("CubSync", style = CubType.windowTitle, color = colors.text)
            Spacer(Modifier.weight(1f))
            Text(io.github.cubsync.service.BuildInfo.VERSION, style = CubType.meta, color = colors.textFaint)
        }
        Spacer(Modifier.height(12.dp))

        for (section in Section.entries) {
            val selected = state.section == section
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 1.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) colors.accentSoft else colors.background.copy(alpha = 0f))
                    .clickable { state.goTo(section) }
                    .padding(horizontal = 8.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(sectionGlyph(section), style = CubType.label, color = if (selected) colors.accent else colors.textFaint)
                Spacer(Modifier.width(9.dp))
                Text(
                    section.label,
                    style = CubType.body,
                    color = if (selected) colors.text else colors.textDim,
                    modifier = Modifier.weight(1f),
                )
                if (section == Section.JOBS && state.jobs.isNotEmpty()) {
                    Chip(state.jobs.size.toString(), tone = colors.accent)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = colors.outline)
        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Words.FAVOURITES, style = CubType.meta, color = colors.textFaint, modifier = Modifier.weight(1f))
            GlyphButton("＋", size = 22) { state.toggleFavourite(io.github.cubsync.service.Side.LEFT) }
        }

        val favourites = state.service.settings.favourites
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (favourites.isEmpty()) {
                Text(
                    "在传输页点 ☆ 收藏一个目录",
                    style = CubType.meta,
                    color = colors.textFaint,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            for (favourite in favourites) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 1.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            state.goTo(Section.TRANSFER)
                            state.openPane(io.github.cubsync.service.Side.LEFT, favourite.path, favourite.backendId)
                        }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("☆", style = CubType.meta, color = colors.textFaint)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(favourite.label, style = CubType.body, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(favourite.device, style = CubType.meta, color = colors.textFaint, maxLines = 1)
                    }
                }
            }
        }

        HorizontalDivider(color = colors.outline)
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (server.running) colors.success else colors.textFaint)
                Spacer(Modifier.width(7.dp))
                Text(
                    if (server.running) "共享中 :${server.port}" else "未共享",
                    style = CubType.meta,
                    color = colors.textDim,
                )
            }
            Text(
                if (state.peers.isEmpty()) "未连接${Words.REMOTE}" else "已连接 ${state.peers.size} 台",
                style = CubType.meta,
                color = colors.textFaint,
            )
        }
    }
}

/** The title bar: where you are, what is happening, and the display menu when it applies. */
@Composable
private fun TopBar(state: AppState, server: ServerState) {
    val colors = CubTheme.colors
    Column(Modifier.fillMaxWidth().background(colors.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(state.section.label, style = CubType.sectionTitle, color = colors.text)
                Text(sectionHint(state.section), style = CubType.meta, color = colors.textFaint)
            }
            if (state.running != null) {
                Chip("${state.running?.label}「${state.running?.configName}」", tone = colors.warning)
                Spacer(Modifier.width(8.dp))
                QuietButton("取消") { state.cancelRun() }
                Spacer(Modifier.width(8.dp))
            }
            Text(state.statusLine(server), style = CubType.meta, color = colors.textFaint)
        }
        HorizontalDivider(color = colors.outline)
    }
}

/** A short-lived message at the bottom of the window. */
@Composable
private fun NoticeBar(state: AppState, modifier: Modifier = Modifier) {
    val notice = state.notice ?: return
    val colors = CubTheme.colors
    val tone = colourFor(notice, colors)
    Box(modifier.fillMaxWidth().padding(bottom = 18.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surfaceRaised)
                .border(1.dp, tone.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                .clickable { state.dismissNotice() }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(tone)
            Spacer(Modifier.width(8.dp))
            Text(notice.text, style = CubType.body, color = colors.text)
        }
    }
}

@Composable
private fun colourFor(notice: Notice, colors: io.github.cubsync.desktop.CubColors) = when (notice.kind) {
    NoticeKind.INFO -> colors.info
    NoticeKind.SUCCESS -> colors.success
    NoticeKind.WARNING -> colors.warning
    NoticeKind.ERROR -> colors.danger
}

/** The rail's glyph for a section. Short, because the rail is narrow. */
fun sectionGlyph(section: Section): String = when (section) {
    Section.CONNECT -> "⇄"
    Section.TRANSFER -> "⇅"
    Section.SYNC -> "▤"
    Section.JOBS -> "▦"
    Section.LOGS -> "≡"
    Section.SETTINGS -> "⚙"
}

/** The label a pane's title uses. */
fun paneTitle(state: AppState, side: io.github.cubsync.service.Side): String {
    val pane = state.browsing.current(side)
    return if (pane.backendId == EndpointId.LOCAL) {
        Words.LOCAL_PANE
    } else {
        state.peerFor(pane.backendId)?.nodeName ?: Words.REMOTE_PANE
    }
}

/** A circular avatar used for a peer, so two rows with the same host are distinguishable. */
@Composable
fun DeviceDot(online: Boolean, size: Int = 8) {
    val colors = CubTheme.colors
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(if (online) colors.success else colors.textFaint),
    )
}

/** A row of chips, with wrapping avoided by ellipsis. */
@Composable
fun ChipRow(texts: List<String>, tone: androidx.compose.ui.graphics.Color? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (text in texts) Chip(text, tone)
    }
}
