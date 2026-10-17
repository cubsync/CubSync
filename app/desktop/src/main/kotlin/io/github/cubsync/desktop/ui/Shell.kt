package io.github.cubsync.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.cubsync.desktop.AppState
import io.github.cubsync.desktop.CubTheme
import io.github.cubsync.desktop.CubType
import io.github.cubsync.desktop.Notice
import io.github.cubsync.desktop.NoticeKind
import io.github.cubsync.service.BuildInfo
import io.github.cubsync.service.EndpointId
import io.github.cubsync.service.Format
import io.github.cubsync.service.IconPaths
import io.github.cubsync.service.Peers
import io.github.cubsync.service.Section
import io.github.cubsync.service.ServerState
import io.github.cubsync.service.Side
import io.github.cubsync.service.Words
import androidx.compose.foundation.gestures.detectDragGestures

/**
 * The window, built the way the prototype builds it.
 *
 * Three bands, each a declaration in the prototype: `.win-title` across the top (42px, the
 * mark, the product name, the device chip and the three window controls), then `.win-body`
 * — a `.rail` of 212px with its three group labels and its bookmark list, and `.content`
 * with a `.chead` over the open screen — and `.win-status` along the bottom (28px).
 *
 * The title bar is drawn rather than borrowed because the prototype draws one, and because
 * the alternative is what shipped before: a Windows title bar in the system colour, which
 * is white in the light theme and stays white in the dark one.
 */
@Composable
fun CubApp(state: AppState, chrome: WindowChrome) {
    val server by state.server.state.collectAsState()
    var railFolded by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(CubTheme.colors.surface)) {
        TitleBar(state, server, chrome)
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Rail(state, server, railFolded) { railFolded = !railFolded }
            Column(Modifier.fillMaxSize()) {
                ScreenHead(state, server) { HeadActions(state) }
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
        StatusBar(state, server)
        NoticeBar(state)
    }
}

/** What the drawn title bar needs from the window it belongs to. */
data class WindowChrome(
    val minimise: () -> Unit,
    val toggleMaximise: () -> Unit,
    val close: () -> Unit,
    val maximised: Boolean,
    val dragModifier: Modifier,
)

// ------------------------------------------------------------------- title bar

@Composable
private fun TitleBar(state: AppState, server: ServerState, chrome: WindowChrome) {
    val colors = CubTheme.colors
    val device = state.liveSettings.serverNodeName.ifEmpty { Peers.local().name }
    val nics = remember(state.settingsRevision) {
        state.liveSettings.channelNics.ifEmpty { Peers.localNics().map { it.name } }
    }
    val channels = if (nics.isEmpty()) "—" else nics.joinToString(" + ")

    Row(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
            .background(colors.surfaceRaised)
            .then(chrome.dragModifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(14.dp))
        BrandMark(size = 18)
        Spacer(Modifier.width(10.dp))
        Text("CubSync", style = CubType.windowTitle, color = colors.text)

        Spacer(Modifier.width(16.dp))
        Row(
            Modifier
                .clip(RoundedCornerShape(99.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, RoundedCornerShape(99.dp))
                .padding(horizontal = 11.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pulse(if (server.running) colors.success else colors.textFaint)
            Spacer(Modifier.width(7.dp))
            Text(device, style = CubType.status, color = colors.text, maxLines = 1)
            Text(
                " · $channels · 协议 v${BuildInfo.PROTOCOL_VERSION}",
                style = CubType.status,
                color = colors.textDim,
                maxLines = 1,
            )
        }

        Spacer(Modifier.weight(1f))
        WindowControl(IconPaths.WIN_MINIMISE) { chrome.minimise() }
        WindowControl(if (chrome.maximised) IconPaths.WIN_RESTORE else IconPaths.WIN_MAXIMISE) {
            chrome.toggleMaximise()
        }
        WindowControl(IconPaths.CLOSE, danger = true) { chrome.close() }
    }
    HorizontalDivider(color = colors.outline)
}

@Composable
private fun WindowControl(
    path: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = CubTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .width(44.dp)
            .height(42.dp)
            .background(if (hovered) (if (danger) colors.danger else colors.hover) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CubIcon(
            path,
            size = 14,
            stroke = 1.6f,
            tint = when {
                hovered && danger -> Color.White
                hovered -> colors.text
                else -> colors.textDim
            },
        )
    }
}

/** The 7px status dot, with the ring the prototype gives it. */
@Composable
private fun Pulse(colour: Color) {
    Box(Modifier.size(13.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(13.dp).clip(CircleShape).background(colour.copy(alpha = 0.22f)))
        Box(Modifier.size(7.dp).clip(CircleShape).background(colour))
    }
}

// ------------------------------------------------------------------- rail

@Composable
private fun Rail(state: AppState, server: ServerState, folded: Boolean, onFold: () -> Unit) {
    val colors = CubTheme.colors
    val width = if (folded) 56.dp else 212.dp

    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            .background(colors.rail)
            .padding(horizontal = if (folded) 6.dp else 10.dp, vertical = 12.dp),
    ) {
        if (!folded) {
            RailLabel("工作区")
        }
        RailItem(state, Section.CONNECT, folded)
        RailItem(state, Section.TRANSFER, folded)
        RailItem(state, Section.SYNC, folded)
        if (!folded) {
            RailLabel("任务")
        }
        RailItem(state, Section.JOBS, folded, badge = state.jobs.size)
        RailItem(state, Section.LOGS, folded)
        if (!folded) {
            RailLabel("其他")
        }
        RailItem(state, Section.SETTINGS, folded)

        if (!folded) {
            HorizontalDivider(Modifier.padding(top = 8.dp), color = colors.outline)
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(Words.FAVOURITES, style = CubType.railLabel, color = colors.textFaint, modifier = Modifier.weight(1f))
                IconButton(IconPaths.PLUS, size = 28, mark = 13) { state.toggleFavourite(Side.LEFT) }
            }
            val favourites = state.service.settings.favourites
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                for (group in favourites.groupBy { it.device }.toSortedMap()) {
                    Text(
                        group.key,
                        style = CubType.tiny,
                        color = colors.textFaint,
                        modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 3.dp),
                    )
                    for (favourite in group.value) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    state.goTo(Section.TRANSFER)
                                    state.openPane(Side.LEFT, favourite.path, favourite.backendId)
                                }
                                .padding(start = 20.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                favourite.path,
                                style = CubType.monoSmall,
                                color = colors.textDim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }

        if (!folded) {
            HorizontalDivider(color = colors.outline)
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "核心 cubsync-core · 双向对等 v${BuildInfo.PROTOCOL_VERSION}",
                        style = CubType.tiny,
                        color = colors.textFaint,
                        lineHeight = 16.sp,
                    )
                    Text(
                        if (server.running) "共享中 :${server.port}" else Words.UNCONNECTED,
                        style = CubType.tiny,
                        color = colors.textDim,
                    )
                }
                IconButton(IconPaths.FOLD, size = 28, mark = 13, onClick = onFold)
            }
        } else {
            IconButton(IconPaths.FOLD, size = 28, mark = 14, onClick = onFold)
        }
    }
    VerticalLine(colors.outline)
}

@Composable
private fun RailLabel(text: String) {
    Text(
        text,
        style = CubType.railLabel,
        color = CubTheme.colors.textFaint,
        modifier = Modifier.padding(start = 10.dp, top = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun RailItem(state: AppState, section: Section, folded: Boolean, badge: Int = 0) {
    val colors = CubTheme.colors
    val selected = state.section == section
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.accentSoft else Color.Transparent)
            .clickable { state.goTo(section) }
            .padding(horizontal = if (folded) 0.dp else 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (folded) Arrangement.Center else Arrangement.Start,
    ) {
        CubIcon(
            sectionIcon(section),
            size = 17,
            tint = if (selected) colors.accent else colors.textDim,
        )
        if (!folded) {
            Spacer(Modifier.width(11.dp))
            Text(
                section.label,
                style = CubType.railItem,
                color = if (selected) colors.accent else colors.textDim,
                modifier = Modifier.weight(1f),
            )
            if (badge > 0) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .background(colors.accent)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(badge.toString(), style = CubType.tiny, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun VerticalLine(colour: Color) {
    Box(Modifier.width(1.dp).fillMaxHeight().background(colour))
}

// ------------------------------------------------------------------- screen head

/** `.chead`: the heading, the one-line hint, and the screen's own actions. */
@Composable
fun ScreenHead(state: AppState, server: ServerState, actions: @Composable () -> Unit = {}) {
    val colors = CubTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text(state.section.heading, style = CubType.screenTitle, color = colors.text)
            Text(state.section.hint, style = CubType.screenHint, color = colors.textDim)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            actions()
            if (state.running != null) {
                Chip("${state.running?.label}「${state.running?.configName}」", tone = colors.warning)
                QuietButton("取消") { state.cancelRun() }
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(state.statusLine(server), style = CubType.tiny, color = colors.textFaint)
    }
    HorizontalDivider(color = colors.outlineFaint)
}

/**
 * The buttons the prototype puts on each screen's heading.
 *
 * They live here rather than inside the screens so the heading row stays one row: the
 * prototype's `.acts` is part of `.chead`, not part of the body.
 */
@Composable
private fun HeadActions(state: AppState) {
    when (state.section) {
        Section.CONNECT -> {
            QuietButton("刷新设备") { state.refreshPeers() }
            PrimaryButton("连接", enabled = !state.connecting) { state.connectPeer(state.peerDraft) }
        }

        Section.TRANSFER -> TransferHeadActions(state)
        Section.SYNC -> SyncHeadActions(state)
        Section.LOGS -> LogsHeadActions(state)
        Section.SETTINGS -> SettingsHeadActions(state)
        Section.JOBS -> {}
    }
}

// ------------------------------------------------------------------- status bar

/** `.win-status`: the same four facts the prototype puts along the bottom. */
@Composable
private fun StatusBar(state: AppState, server: ServerState) {
    val colors = CubTheme.colors
    val running = state.running
    val elapsed = if (running != null) System.currentTimeMillis() - running.startedAtMs else 0
    val rate = if (running != null && elapsed > 0) Format.rate(running.bytes, elapsed) else DASH

    Row(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(colors.surfaceRaised)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pulse(if (state.peers.isEmpty() && !server.running) colors.textFaint else colors.success)
            Spacer(Modifier.width(6.dp))
            Text(
                if (state.peers.isEmpty()) Words.UNCONNECTED else "已连接 · ${state.peers.size} 通道",
                style = CubType.status,
                color = colors.textDim,
            )
        }
        Text("↑ $rate", style = CubType.status, color = colors.textDim)
        Text(
            if (running != null) {
                "作业：${running.configName} ${running.percent}%"
            } else {
                "作业：$DASH"
            },
            style = CubType.status,
            color = colors.textDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        Text("v${BuildInfo.VERSION} · Apache-2.0", style = CubType.status, color = colors.textDim)
    }
}

/** A short-lived message above the status bar. */
@Composable
private fun NoticeBar(state: AppState) {
    val notice = state.notice ?: return
    val colors = CubTheme.colors
    val tone = colourFor(notice, colors)
    Box(Modifier.fillMaxWidth().padding(bottom = 46.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(colors.text)
                .clickable { state.dismissNotice() }
                .padding(horizontal = 15.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(notice.text, style = CubType.button, color = colors.surface)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(7.dp).clip(CircleShape).background(tone))
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

/** The label a pane's title uses. */
fun paneTitle(state: AppState, side: Side): String {
    val pane = state.browsing.current(side)
    return if (pane.backendId == EndpointId.LOCAL) {
        Words.LOCAL_PANE
    } else {
        state.peerFor(pane.backendId)?.nodeName ?: Words.REMOTE_PANE
    }
}

/** The online dot beside an account. */
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

/** A row of chips. */
@Composable
fun ChipRow(texts: List<String>, tone: Color? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (text in texts) Chip(text, tone)
    }
}

/** The gradient the prototype puts behind a placeholder brand tile. */
internal fun brandGradient(accent: Color) = Brush.linearGradient(listOf(accent, Color(0xFF7B4DF5)))
