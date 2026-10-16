package io.github.cubsync.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import io.github.cubsync.core.spi.FileMeta
import io.github.cubsync.desktop.AppState
import io.github.cubsync.desktop.CubColors
import io.github.cubsync.desktop.CubTheme
import io.github.cubsync.desktop.CubType
import io.github.cubsync.desktop.NoticeKind
import io.github.cubsync.desktop.Section
import io.github.cubsync.desktop.copyToClipboard
import io.github.cubsync.service.ArrangementDirection
import io.github.cubsync.service.Browsing
import io.github.cubsync.service.BrowserSettings
import io.github.cubsync.service.DisplayMode
import io.github.cubsync.service.EndpointId
import io.github.cubsync.service.Format
import io.github.cubsync.service.PaneState
import io.github.cubsync.service.Side
import io.github.cubsync.service.SortKey

/**
 * Two folders and an arrow between them.
 *
 * This is the screen the design starts on: you look at what is here, look at what is
 * there, and move the difference yourself. The synchroniser is the version of this
 * that runs on a schedule.
 *
 * Both panes are the same component, so a pane cannot behave differently depending on
 * which side it happens to be, and everything about how they look comes from the
 * shared browsing settings rather than from this file.
 */
@Composable
fun TransferScreen(state: AppState) {
    val session = state.service.browsing
    val settings = session.settings
    // The settings are plain mutable state in the shared layer, so changing one has to
    // be announced for the layout to notice it.
    val revision = state.browserRevision
    val left by session.left.collectAsState()
    val right by session.right.collectAsState()
    val busy by session.busy.collectAsState()
    if (revision < 0) return

    // The first thing this screen needs is somewhere to be. A machine shares folders;
    // one of them is a better starting point than an empty pane.
    LaunchedEffect(state.peers.size) {
        if (left.path.isEmpty()) {
            state.localRoots().firstOrNull()?.let { session.open(Side.LEFT, EndpointId.LOCAL, it) }
        }
        if (right.path.isEmpty()) {
            val peer = state.peers.firstOrNull()
            val root = peer?.roots?.firstOrNull()
            if (peer != null && root != null) session.open(Side.RIGHT, peer.backendId, root)
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            Section.TRANSFER.label,
            Section.TRANSFER.hint,
            actions = { DisplayModeBar(state, settings) },
        )

        if (settings.displayMode == DisplayMode.COMPARE) {
            CompareView(state, left, right, Modifier.weight(1f))
        } else if (settings.resolve(1_200, 700) == ArrangementDirection.SIDE_BY_SIDE) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Pane(state, Side.LEFT, left, Modifier.weight(1f).fillMaxHeight())
                TransferArrow(state, busy, horizontal = true)
                Pane(state, Side.RIGHT, right, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.weight(1f).fillMaxWidth()) {
                Pane(state, Side.LEFT, left, Modifier.weight(1f))
                TransferArrow(state, busy, horizontal = false)
                Pane(state, Side.RIGHT, right, Modifier.weight(1f))
            }
        }

        StatusStrip(state, left, right)
    }
}

@Composable
private fun DisplayModeBar(state: AppState, settings: BrowserSettings) {
    val colors = CubTheme.colors
    var open by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        FieldLabel("显示方式", Modifier.padding(end = 6.dp))
        Box {
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceRaised)
                    .border(1.dp, colors.outline, RoundedCornerShape(8.dp))
                    .clickable { open = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(settings.displayMode.label, style = CubType.label, color = colors.text)
                Spacer(Modifier.width(6.dp))
                Text("▾", style = CubType.meta, color = colors.textFaint)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (mode in DisplayMode.entries) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                mode.label,
                                style = CubType.body,
                                color = if (mode == settings.displayMode) colors.accent else colors.text,
                            )
                        },
                        onClick = {
                            open = false
                            state.changeBrowserSettings { it.displayMode = mode }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.width(18.dp))
        if (settings.displayMode.grid) {
            FieldLabel("列数", Modifier.padding(end = 6.dp))
            SegmentedChoice(
                options = listOf(2, 3, 4, 6, 8).map { Choice(it, it.toString()) },
                selected = settings.columns,
                onSelect = { value -> state.changeBrowserSettings { it.applyColumns(value) } },
            )
        } else {
            FieldLabel("行密度", Modifier.padding(end = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                QuietButton("−", enabled = settings.rowDensity > BrowserSettings.MIN_DENSITY) {
                    state.changeBrowserSettings { it.bumpDensity(-BrowserSettings.DENSITY_STEP) }
                }
                Text(
                    "${settings.rowDensity}%",
                    style = CubType.meta,
                    color = colors.textDim,
                    modifier = Modifier.width(46.dp),
                )
                QuietButton("+", enabled = settings.rowDensity < BrowserSettings.MAX_DENSITY) {
                    state.changeBrowserSettings { it.bumpDensity(BrowserSettings.DENSITY_STEP) }
                }
            }
        }

        Spacer(Modifier.width(18.dp))
        FieldLabel("分栏方向", Modifier.padding(end = 6.dp))
        SegmentedChoice(
            options = ArrangementDirection.entries.map { Choice(it, it.label) },
            selected = settings.arrangement,
            onSelect = { value -> state.changeBrowserSettings { it.arrangement = value } },
        )
    }
}

@Composable
private fun Pane(state: AppState, side: Side, pane: PaneState, modifier: Modifier) {
    val colors = CubTheme.colors
    val session = state.service.browsing
    val settings = session.settings
    var menu by remember { mutableStateOf(false) }

    Column(
        modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.outline, RoundedCornerShape(12.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (side == Side.LEFT) "本机" else "远端",
                style = CubType.meta,
                color = colors.textFaint,
                modifier = Modifier.width(32.dp),
            )
            QuietButton("←", enabled = pane.path.isNotEmpty()) { session.up(side) }
            Breadcrumbs(pane.path, Modifier.weight(1f)) { path -> session.open(side, pane.backendId, path) }
            QuietButton("⟳") { session.refresh(side) }
            Box {
                QuietButton("⋯") { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for (key in SortKey.entries) {
                        val mark = if (settings.sortKey == key) (if (settings.ascending) " ↑" else " ↓") else ""
                        DropdownMenuItem(
                            text = { Text("按${key.label}排序$mark", style = CubType.body) },
                            onClick = {
                                menu = false
                                state.changeBrowserSettings { current ->
                                    if (current.sortKey == key) {
                                        current.ascending = !current.ascending
                                    } else {
                                        current.sortKey = key
                                    }
                                }
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text("文件夹优先：${if (settings.foldersFirst) "开" else "关"}", style = CubType.body)
                        },
                        onClick = {
                            menu = false
                            state.changeBrowserSettings { it.foldersFirst = !it.foldersFirst }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("全选", style = CubType.body) },
                        onClick = {
                            menu = false
                            session.selectAll(side)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("取消选择", style = CubType.body) },
                        onClick = {
                            menu = false
                            session.clearSelection(side)
                        },
                    )
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
            when {
                pane.error.isNotEmpty() -> EmptyState("打不开这个文件夹", pane.error)
                pane.loading -> EmptyState("正在读取…", pane.path)
                pane.path.isEmpty() -> EmptyState(
                    "还没有选择文件夹",
                    if (side == Side.RIGHT) "先在「连接」页连上另一台设备" else "用上面的地址栏选一个文件夹",
                )

                pane.entries.isEmpty() -> EmptyState("这个文件夹是空的")
                settings.displayMode.grid -> GridEntries(state, pane, settings, side)
                else -> ListEntries(state, pane, settings, side)
            }
        }
    }
}

@Composable
private fun Breadcrumbs(path: String, modifier: Modifier, onClick: (String) -> Unit) {
    val colors = CubTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        for (crumb in Browsing.crumbs(path, maxSegments = 5)) {
            if (crumb.label != "/") {
                Text("›", style = CubType.meta, color = colors.textFaint, modifier = Modifier.padding(horizontal = 3.dp))
            }
            Text(
                crumb.label,
                style = CubType.meta,
                color = if (crumb.collapsed) colors.textFaint else colors.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .clickable { onClick(crumb.path) }
                    .padding(horizontal = 5.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun ListEntries(state: AppState, pane: PaneState, settings: BrowserSettings, side: Side) {
    val colors = CubTheme.colors
    val session = state.service.browsing
    val rowHeight = (32 * settings.rowDensity / 100).dp

    LazyColumn(Modifier.fillMaxSize()) {
        items(pane.entries, key = { it.relPath }) { entry ->
            val selected = pane.isSelected(entry.relPath)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (selected) colors.accentSoft else Color.Transparent)
                    .clickable {
                        if (entry.isDirectory) session.enter(side, entry.relPath) else session.toggle(side, entry.relPath)
                    }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectionBox(selected)
                Spacer(Modifier.width(9.dp))
                Text(
                    if (entry.isDirectory) "▸" else " ",
                    style = CubType.meta,
                    color = colors.textFaint,
                    modifier = Modifier.width(10.dp),
                )
                Text(
                    entry.relPath,
                    style = CubType.body,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (settings.displayMode == DisplayMode.DETAILED) {
                    Text(
                        if (entry.mtimeMs > 0) Format.clockTime(entry.mtimeMs) else "-",
                        style = CubType.meta,
                        color = colors.textFaint,
                        modifier = Modifier.width(66.dp),
                    )
                }
                Text(
                    if (entry.isDirectory) "" else Format.bytes(entry.size),
                    style = CubType.meta,
                    color = colors.textDim,
                    modifier = Modifier.width(72.dp),
                )
            }
        }
    }
}

@Composable
private fun SelectionBox(selected: Boolean) {
    val colors = CubTheme.colors
    Box(
        Modifier
            .size(14.dp)
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, if (selected) colors.accent else colors.outlineStrong, RoundedCornerShape(4.dp))
            .background(if (selected) colors.accent else Color.Transparent),
    )
}

@Composable
private fun GridEntries(state: AppState, pane: PaneState, settings: BrowserSettings, side: Side) {
    val colors = CubTheme.colors
    val session = state.service.browsing
    val tileHeight = if (settings.displayMode == DisplayMode.THUMBNAIL) 104.dp else 76.dp

    LazyVerticalGrid(columns = GridCells.Fixed(settings.columns), modifier = Modifier.fillMaxSize()) {
        items(pane.entries, key = { it.relPath }) { entry ->
            val selected = pane.isSelected(entry.relPath)
            Column(
                Modifier
                    .padding(4.dp)
                    .height(tileHeight)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) colors.accentSoft else colors.surfaceRaised)
                    .border(1.dp, if (selected) colors.accent else colors.outline, RoundedCornerShape(10.dp))
                    .clickable {
                        if (entry.isDirectory) session.enter(side, entry.relPath) else session.toggle(side, entry.relPath)
                    }
                    .padding(8.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(if (entry.isDirectory) "▸" else "•", style = CubType.sectionTitle, color = colors.textFaint)
                Text(entry.relPath, style = CubType.meta, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The arrow between the panes, which is the whole point of the screen. */
@Composable
private fun TransferArrow(state: AppState, busy: Boolean, horizontal: Boolean) {
    val colors = CubTheme.colors
    val session = state.service.browsing
    val left by session.left.collectAsState()
    val right by session.right.collectAsState()
    val movableRight = left.selection.isNotEmpty() || left.entries.isNotEmpty()
    val movableLeft = right.selection.isNotEmpty() || right.entries.isNotEmpty()

    if (horizontal) {
        Column(
            Modifier.width(76.dp).fillMaxHeight().padding(top = 62.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("已选 ${left.selection.size + right.selection.size}", style = CubType.meta, color = colors.textFaint)
            Spacer(Modifier.height(6.dp))
            QuietButton("→", enabled = !busy && movableRight) { state.transfer(Side.LEFT) }
            Spacer(Modifier.height(2.dp))
            QuietButton("←", enabled = !busy && movableLeft) { state.transfer(Side.RIGHT) }
        }
    } else {
        Row(
            Modifier.fillMaxWidth().height(38.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuietButton("↑", enabled = !busy && movableLeft) { state.transfer(Side.RIGHT) }
            Text("  ⇅  ", style = CubType.sectionTitle, color = colors.textFaint)
            QuietButton("↓", enabled = !busy && movableRight) { state.transfer(Side.LEFT) }
        }
    }
}

/**
 * The compare mode: one list, aligned by name, so what is missing on one side shows as
 * a gap instead of something you have to find by reading two lists.
 */
@Composable
private fun CompareView(state: AppState, left: PaneState, right: PaneState, modifier: Modifier) {
    val colors = CubTheme.colors
    val session = state.service.browsing
    val names = (left.entries.map { it.relPath } + right.entries.map { it.relPath }).distinct().sorted()
    val leftBy = left.entries.associateBy { it.relPath }
    val rightBy = right.entries.associateBy { it.relPath }

    Column(
        modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.outline, RoundedCornerShape(12.dp)),
    ) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "本机 · " + Browsing.name(left.path),
                style = CubType.cardTitle,
                color = colors.accent,
                modifier = Modifier.weight(1f),
            )
            Text(
                "远端 · " + Browsing.name(right.path),
                style = CubType.cardTitle,
                color = colors.conflict,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(color = colors.outline)
        LazyColumn(Modifier.fillMaxSize()) {
            items(names, key = { it }) { name ->
                Row(
                    Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompareCell(name, leftBy[name], colors) { session.toggle(Side.LEFT, name) }
                    CompareCell(name, rightBy[name], colors) { session.toggle(Side.RIGHT, name) }
                }
            }
        }
    }
}

/** One half of a compare row: the name, or a dash where that side has nothing. */
@Composable
private fun RowScope.CompareCell(name: String, entry: FileMeta?, colors: CubColors, onClick: () -> Unit) {
    Row(
        Modifier.weight(1f).padding(end = 8.dp).clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(if (entry == null) colors.outlineStrong else colors.textFaint)
        Spacer(Modifier.width(8.dp))
        Text(
            if (entry == null) "-" else name,
            style = CubType.body,
            color = if (entry == null) colors.textFaint else colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (entry != null && !entry.isDirectory) {
            Text(Format.bytes(entry.size), style = CubType.meta, color = colors.textDim)
        }
    }
}

@Composable
private fun StatusStrip(state: AppState, left: PaneState, right: PaneState) {
    val colors = CubTheme.colors
    val session = state.service.browsing

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "本机 ${left.entries.size} 项 · 远端 ${right.entries.size} 项 · 已选 ${left.selection.size + right.selection.size}",
            style = CubType.meta,
            color = colors.textFaint,
        )
        Spacer(Modifier.weight(1f))
        QuietButton("复制本机地址") {
            copyToClipboard(left.path)
            state.notify("已复制 ${left.path}", NoticeKind.INFO)
        }
        QuietButton("重新读取两侧") {
            session.refresh(Side.LEFT)
            session.refresh(Side.RIGHT)
        }
    }
}
