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
import androidx.compose.ui.layout.onGloballyPositioned
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
import io.github.cubsync.service.Favorite
import io.github.cubsync.service.PathTools
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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.layout.positionInWindow

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

    Box(Modifier.fillMaxSize()) {
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
    }
    // Drawn over the window rather than under the status bar. As a child of the column it
    // took a row of its own, so every notice pushed the whole screen up and left a band of
    // empty surface below it — the window resized itself to say one sentence.
    NoticeBar(state)

    // 收藏夹's `+`: the folder a pane is showing is not always the one worth keeping, so
    // adding a favourite takes an address rather than only the current view.
    FavouriteOverlays(state)
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
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(Words.FAVOURITES, style = CubType.railLabel, color = colors.textFaint, modifier = Modifier.weight(1f))
                // No plus here any more: adding a folder is a menu item, and the icon that
                // used to be 加 is 排序 — the same control on both ends, because a list you
                // can order needs a place to say so.
                IconButton(
                    IconPaths.SORT,
                    size = 26,
                    mark = 14,
                    selected = state.favouriteSorting,
                    tint = if (state.favouriteSorting) Color.White else null,
                ) {
                    state.favouriteSorting = !state.favouriteSorting
                }
                IconButton(IconPaths.MORE, size = 24, mark = 15, stroke = 3.4f) {
                    state.favouriteDialog = null
                    state.favouriteHeaderMenu = true
                }
            }

            // Read, not cached: a favourite added on another screen has to appear here the
            // moment it is written, and this list used to wait for a section change.
            state.settingsRevision
            val favourites = state.service.settings.favourites
            val headings = state.service.settings.favouriteHeadings()
            val showPath = state.service.settings.favouritesShowPath

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                var flat = 0
                for (heading in headings) {
                    val items = favourites.filter { it.group == heading }
                    if (items.isEmpty() && heading !in state.service.settings.favouriteGroups) continue
                    Row(
                        Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 7.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            heading,
                            style = CubType.tiny,
                            color = colors.textFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    for (item in items) {
                        val index = favourites.indexOfFirst { it.key == item.key }.coerceAtLeast(flat)
                        FavouriteRow(state, item, index, showPath, state.favouriteSorting)
                        flat++
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
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier
                .padding(bottom = 40.dp)
                .clip(RoundedCornerShape(9.dp))
                // A floating bar in the window's own colours, not the inverse of them: on a
                // dark desktop the old `colors.text` background came out as a white slab.
                .background(colors.surfaceRaised)
                .border(1.dp, colors.outlineStrong, RoundedCornerShape(9.dp))
                .clickable { state.dismissNotice() }
                .padding(horizontal = 15.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(notice.text, style = CubType.button, color = colors.text)
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

/**
 * One row of 收藏夹.
 *
 * Three ways in: a click opens it in the focused pane, a right click opens the row's menu,
 * and in 排序 mode a press-and-drag moves it. The row used to be a click and a × — no menu,
 * no order, and a delete that was the only thing you could do to it.
 */
@Composable
private fun FavouriteRow(
    state: AppState,
    favourite: Favorite,
    index: Int,
    showPath: Boolean,
    sorting: Boolean,
) {
    val colors = CubTheme.colors
    var origin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var dragged by remember { mutableStateOf(0f) }
    val rowPx = with(androidx.compose.ui.platform.LocalDensity.current) { 28.dp.toPx() }

    Row(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { origin = it.positionInWindow() }
            .padding(end = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (dragged != 0f) colors.hover else Color.Transparent)
            .pointerInput(favourite.key, sorting) {
                if (!sorting) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDrag = { change, amount ->
                        change.consume()
                        dragged += amount.y
                    },
                    onDragEnd = {
                        val rows = kotlin.math.round(dragged / rowPx).toInt()
                        val last = state.service.settings.favourites.lastIndex
                        if (rows != 0) state.dropFavourite(index, (index + rows).coerceIn(0, last))
                        dragged = 0f
                    },
                    onDragCancel = { dragged = 0f },
                )
            }
            .pointerInput(favourite.key) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press &&
                            event.buttons.isSecondaryPressed
                        ) {
                            state.favouriteMenuAt = origin
                            state.favouriteMenu = favourite
                        }
                    }
                }
            }
            .clickable { if (!sorting) state.openFavourite(favourite) }
            .padding(start = 12.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (sorting) {
            CubIcon(IconPaths.SORT, size = 12, tint = colors.textFaint)
            Spacer(Modifier.width(5.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                favourite.label.ifEmpty { PathTools.leafName(favourite.path).ifEmpty { favourite.path } },
                style = CubType.body,
                color = colors.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showPath) {
                Text(
                    favourite.path,
                    style = CubType.monoSmall,
                    color = colors.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The row menu, the header menu and the four dialogs 收藏夹 needs. */
@Composable
private fun FavouriteOverlays(state: AppState) {
    val colors = CubTheme.colors
    val menu = state.favouriteMenu
    val dialog = state.favouriteDialog
    val headerMenu = state.favouriteHeaderMenu
    if (menu == null && dialog == null && !headerMenu) return

    fun closeAll() {
        state.favouriteMenu = null
        state.favouriteDialog = null
        state.favouriteHeaderMenu = false
    }

    Box(Modifier.fillMaxSize()) {
        Scrim { closeAll() }

        if (menu != null) {
            val at = state.favouriteMenuAt
            Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(at.x.toInt(), at.y.toInt()) }) {
                FloatingCard(width = 230) {
                    MenuLine("打开") { state.openFavourite(menu); closeAll() }
                    MenuLine("编辑…") {
                        state.favouriteDialog = AppState.FavDialog.Edit(menu)
                        state.favouriteMenu = null
                    }
                    MenuLine("上移") { state.moveFavourite(menu, -1) }
                    MenuLine("下移") { state.moveFavourite(menu, +1) }
                    MenuLine("取消收藏", danger = true) {
                        state.removeFavourite(menu)
                        closeAll()
                    }
                }
            }
        }

        if (headerMenu) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                FloatingCard(width = 300) {
                    FloatingHeader("收藏夹", onClose = { closeAll() })
                    MenuLine("添加路径…") {
                        state.favouriteDialog = AppState.FavDialog.AddPath
                        state.favouriteHeaderMenu = false
                    }
                    MenuLine("新建分组…") {
                        state.favouriteDialog = AppState.FavDialog.NewGroup
                        state.favouriteHeaderMenu = false
                    }
                    MenuLine("添加账户…（远端协议，尚未实现）", enabled = false) {}
                    MenuLine("收藏夹设置…") {
                        state.favouriteDialog = AppState.FavDialog.Settings
                        state.favouriteHeaderMenu = false
                    }
                }
            }
        }

        when (dialog) {
            is AppState.FavDialog.AddPath -> {
                var text by remember { mutableStateOf("") }
                FavouriteDialogCard("添加路径", onClose = { closeAll() }) {
                    Field(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "收藏在聚焦的那一栏里：本端路径或远端路径",
                        style = CubType.meta,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton("收藏") {
                            state.addFavourite(text)
                            closeAll()
                        }
                        QuietButton("取消") { closeAll() }
                    }
                }
            }

            is AppState.FavDialog.NewGroup -> {
                var text by remember { mutableStateOf("") }
                FavouriteDialogCard("新建分组", onClose = { closeAll() }) {
                    Field(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "分组名，例如「工作」「备份」",
                        style = CubType.meta,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton("新建") {
                            state.addFavouriteGroup(text)
                            closeAll()
                        }
                        QuietButton("取消") { closeAll() }
                    }
                }
            }

            is AppState.FavDialog.Edit -> {
                val favourite = dialog.favourite
                var label by remember {
                    mutableStateOf(favourite.label.ifEmpty { PathTools.leafName(favourite.path) })
                }
                var group by remember { mutableStateOf(favourite.groupName) }
                FavouriteDialogCard("编辑收藏", onClose = { closeAll() }) {
                    Text(favourite.path, style = CubType.monoSmall, color = colors.textFaint, maxLines = 2)
                    Field(
                        value = label,
                        onValueChange = { label = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "名称",
                        style = CubType.meta,
                    )
                    Text("分组", style = CubType.meta, color = colors.textFaint)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (name in state.service.settings.favouriteHeadings().take(4)) {
                            QuietButton(name, ghost = group != name) {
                                group = if (group == name) "" else name
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton("保存") {
                            state.renameFavourite(favourite, label, group)
                            closeAll()
                        }
                        QuietButton("取消") { closeAll() }
                    }
                }
            }

            is AppState.FavDialog.Settings -> {
                val settings = state.liveSettings
                FavouriteDialogCard("收藏夹设置", onClose = { closeAll() }) {
                    SettingRow("在名称下显示路径", "一行放不下时省略") {
                        Toggle(settings.favouritesShowPath) { on ->
                            state.changeSettings { it.favouritesShowPath = on }
                        }
                    }
                    Text(
                        "排序：点亮左上角的排序图标后，按住一行拖动即可调整顺序；也可以右键用上移/下移。",
                        style = CubType.meta,
                        color = colors.textFaint,
                    )
                    QuietButton("关闭") { closeAll() }
                }
            }

            null -> Unit
        }
    }
}

@Composable
private fun FavouriteDialogCard(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        FloatingCard(width = 380) {
            FloatingHeader(title, onClose = onClose)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        }
    }
}

/** One line of a floating menu: the whole row is the target, and it says so on hover. */
@Composable
private fun MenuLine(text: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val colors = CubTheme.colors
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (hovered && enabled) colors.hover else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            text,
            style = CubType.body,
            color = when {
                !enabled -> colors.textFaint
                danger -> colors.danger
                else -> colors.text
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
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
