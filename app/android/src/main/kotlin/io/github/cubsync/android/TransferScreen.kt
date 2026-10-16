package io.github.cubsync.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 * Two folders on a phone.
 *
 * A phone shows one pane at a time, so the two panes become a switch and the arrow
 * becomes a button. Everything else — the ordering, the folder-first rule, the density
 * and the modes, and the copy itself — is the same shared session the desktop window
 * drives, which is why neither of them can be the one that is wrong.
 */
@Composable
fun TransferScreen(state: AndroidAppState) {
    val session = state.service.browsing
    val settings = session.settings
    val revision = state.browserRevision
    val left by session.left.collectAsState()
    val right by session.right.collectAsState()
    val busy by session.busy.collectAsState()
    var showing by remember { mutableStateOf(Side.LEFT) }
    if (revision < 0) return

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

    val pane = if (showing == Side.LEFT) left else right
    val other = if (showing == Side.LEFT) right else left

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChoiceChips(
                options = listOf(
                    Choice(Side.LEFT, "本机"),
                    Choice(Side.RIGHT, "远端"),
                ),
                selected = showing,
                onSelect = { showing = it },
                modifier = Modifier.width(160.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${pane.entries.size} 项",
                style = CubType.meta,
                color = CubTheme.colors.textFaint,
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { session.up(showing) }) { Text("←", style = CubType.cardTitle) }
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (crumb in Browsing.crumbs(pane.path, maxSegments = 4)) {
                    if (crumb.label != "/") {
                        Text("›", style = CubType.meta, color = CubTheme.colors.textFaint)
                    }
                    Text(
                        crumb.label,
                        style = CubType.meta,
                        color = CubTheme.colors.textDim,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .clickable { session.open(showing, pane.backendId, crumb.path) }
                            .padding(horizontal = 5.dp, vertical = 3.dp),
                    )
                }
            }
            TextButton(onClick = { session.refresh(showing) }) { Text("⟳", style = CubType.body) }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChoiceChips(
                options = SortKey.entries.map {
                    Choice(it, it.label + if (settings.sortKey == it) (if (settings.ascending) " ↑" else " ↓") else "")
                },
                selected = settings.sortKey,
                onSelect = { key ->
                    state.changeBrowserSettings { current ->
                        if (current.sortKey == key) current.ascending = !current.ascending else current.sortKey = key
                    }
                },
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChoiceChips(
                options = listOf(
                    Choice(DisplayMode.COMPACT, "紧凑"),
                    Choice(DisplayMode.DETAILED, "详细"),
                    Choice(DisplayMode.CARD, "卡片"),
                    Choice(DisplayMode.THUMBNAIL, "缩略"),
                ),
                selected = settings.displayMode,
                onSelect = { mode -> state.changeBrowserSettings { it.displayMode = mode } },
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            val colors = CubTheme.colors
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.outline, RoundedCornerShape(12.dp))
                    .padding(6.dp),
            ) {
                when {
                    pane.error.isNotEmpty() -> EmptyState("打不开这个文件夹", pane.error)
                    pane.loading -> EmptyState("正在读取…", pane.path)
                    pane.path.isEmpty() -> EmptyState(
                        "还没有选择文件夹",
                        if (showing == Side.RIGHT) "先在「连接」页连上另一台设备" else "从上面的目录里选一个",
                    )

                    pane.entries.isEmpty() -> EmptyState("这个文件夹是空的")
                    settings.displayMode.grid -> GridPane(state, pane, settings, showing)
                    else -> ListPane(state, pane, settings, showing)
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "已选 ${pane.selection.size} 项",
                style = CubType.meta,
                color = CubTheme.colors.textFaint,
            )
            Spacer(Modifier.width(10.dp))
            TextButton(onClick = { session.selectAll(showing) }) { Text("全选", style = CubType.meta) }
            TextButton(
                enabled = pane.selection.isNotEmpty(),
                onClick = { session.clearSelection(showing) },
            ) { Text("取消选择", style = CubType.meta) }
            Spacer(Modifier.weight(1f))
            Button(
                enabled = !busy && (pane.selection.isNotEmpty() || pane.entries.isNotEmpty()) && other.path.isNotEmpty(),
                onClick = { state.transfer(showing) },
            ) {
                Text(if (showing == Side.LEFT) "送到远端 →" else "← 送到本机")
            }
        }
    }
}

@Composable
private fun ListPane(state: AndroidAppState, pane: PaneState, settings: BrowserSettings, side: Side) {
    val colors = CubTheme.colors
    val session = state.service.browsing
    val rowHeight = (40 * settings.rowDensity / 100).dp

    LazyColumn(Modifier.fillMaxSize()) {
        items(pane.entries, key = { it.relPath }) { entry ->
            val selected = pane.isSelected(entry.relPath)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) colors.accentContainer else Color.Transparent)
                    .clickable {
                        if (entry.isDirectory) session.enter(side, entry.relPath) else session.toggle(side, entry.relPath)
                    }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .border(1.dp, if (selected) colors.accent else colors.outline, RoundedCornerShape(4.dp))
                        .background(if (selected) colors.accent else Color.Transparent),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    (if (entry.isDirectory) "▸ " else "") + entry.relPath,
                    style = CubType.body,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!entry.isDirectory) {
                    Text(Format.bytes(entry.size), style = CubType.meta, color = colors.textDim)
                }
            }
        }
    }
}

@Composable
private fun GridPane(state: AndroidAppState, pane: PaneState, settings: BrowserSettings, side: Side) {
    val colors = CubTheme.colors
    val session = state.service.browsing
    val tile = if (settings.displayMode == DisplayMode.THUMBNAIL) 104.dp else 76.dp

    LazyVerticalGrid(
        columns = GridCells.Fixed(settings.columns.coerceAtLeast(2)),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(pane.entries, key = { it.relPath }) { entry ->
            val selected = pane.isSelected(entry.relPath)
            Column(
                Modifier
                    .height(tile)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) colors.accentContainer else colors.surfaceRaised)
                    .border(1.dp, if (selected) colors.accent else colors.outline, RoundedCornerShape(10.dp))
                    .clickable {
                        if (entry.isDirectory) session.enter(side, entry.relPath) else session.toggle(side, entry.relPath)
                    }
                    .padding(8.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(if (entry.isDirectory) "▸" else "•", style = CubType.cardTitle, color = colors.textFaint)
                Text(entry.relPath, style = CubType.meta, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
