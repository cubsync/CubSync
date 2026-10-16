package io.github.cubsync.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.cubsync.core.spi.FileMeta
import io.github.cubsync.service.Browsing
import io.github.cubsync.service.Columns
import io.github.cubsync.service.Density
import io.github.cubsync.service.Difference
import io.github.cubsync.service.DisplayMode
import io.github.cubsync.service.Format
import io.github.cubsync.service.IconPaths
import io.github.cubsync.service.Header
import io.github.cubsync.service.ItemScale
import io.github.cubsync.service.PaneDirection
import io.github.cubsync.service.PaneState
import io.github.cubsync.service.Scope
import io.github.cubsync.service.Side
import io.github.cubsync.service.SortKey
import io.github.cubsync.service.Words
import io.github.cubsync.service.resolveDirection

/**
 * 传输, which is where this product is actually used.
 *
 * The sub-row is four items and never scrolls sideways; the display bar hangs under it and
 * is one control per line; the header *is* the address. Those three sentences are the
 * whole of the design's section 3, and the reason the header carries no separate address
 * bar is that on a 390px screen there is no room for both.
 */
@Composable
fun TransferScreen(state: AndroidAppState) {
    val colors = CubTheme.colors
    val session = state.browsing
    val left by session.left.collectAsState()
    val right by session.right.collectAsState()
    val busy by session.busy.collectAsState()

    // Reading the revision is what makes a preference written anywhere redraw this
    // screen; the value itself is not used.
    state.revision
    val settings = state.liveSettings
    val solo = when {
        state.showSoloLocal -> Side.LEFT
        state.showSoloRemote -> Side.RIGHT
        else -> null
    }

    Column(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Swiping right opens the drawer. The design's left-swipe gesture, said
                // from the other side, because this is where the thumb already is.
                var travelled = 0f
                detectHorizontalDragGestures(
                    onDragEnd = { if (travelled > 90f) state.drawerOpen = true; travelled = 0f },
                ) { _, delta -> travelled += delta }
            },
    ) {
        SubRow(state, left, right, busy)
        if (settings.displayBarOpen) DisplayBar(state)
        HorizontalDivider(color = colors.outline)

        val paneDirection = resolveDirection(
            settings.paneDirection,
            380f,
            if (solo != null) 700f else 560f,
        )

        when {
            solo != null -> Pane(state, solo, if (solo == Side.LEFT) left else right, Modifier.weight(1f))
            paneDirection == PaneDirection.SIDE_BY_SIDE ->
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Pane(state, Side.LEFT, left, Modifier.weight(1f))
                    Column(Modifier.width(1.dp).fillMaxHeight().background(colors.outline)) {}
                    Pane(state, Side.RIGHT, right, Modifier.weight(1f))
                }

            else -> Column(Modifier.weight(1f).fillMaxWidth()) {
                Pane(state, Side.LEFT, left, Modifier.weight(1f))
                TransferArrow(state, left, right, busy, horizontal = false)
                Pane(state, Side.RIGHT, right, Modifier.weight(1f))
            }
        }

        if (solo == null && paneDirection == PaneDirection.SIDE_BY_SIDE) {
            TransferArrow(state, left, right, busy, horizontal = true)
        }
        ActionBar(state)
    }
}

// --------------------------------------------------------------------- sub row

/** 副行：☰ · 单双栏 · 显示方式▾ · 已选 N · ⋮. Four items, and it does not scroll. */
@Composable
private fun SubRow(state: AndroidAppState, left: PaneState, right: PaneState, busy: Boolean) {
    val colors = CubTheme.colors
    val settings = state.liveSettings
    val active = settings.activeSide
    val prefs = state.browsing.prefsOf(active)
    val selected = left.selection.size + right.selection.size

    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphButton("☰", size = 38) { state.drawerOpen = true }

        Row(Modifier.padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton("▤", size = 34, selected = state.showSoloLocal.not() && state.showSoloRemote.not()) {
                state.showSoloLocal = false
                state.showSoloRemote = false
            }
            GlyphButton("◧", size = 34, selected = state.showSoloLocal) {
                state.showSoloLocal = true
                state.showSoloRemote = false
                settings.activeSide = Side.LEFT
            }
            GlyphButton("◨", size = 34, selected = state.showSoloRemote) {
                state.showSoloLocal = false
                state.showSoloRemote = true
                settings.activeSide = Side.RIGHT
            }
        }

        Row(
            Modifier
                .weight(1f)
                .clip(Shape9)
                .background(colors.surfaceRaised)
                .border(1.dp, colors.outline, Shape9)
                .clickableCustom { settings.displayBarOpen = !settings.displayBarOpen; state.touch() }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(prefs.displayMode.label, style = CubType.label, color = colors.text, maxLines = 1)
            Spacer(Modifier.width(4.dp))
            Text(if (settings.displayBarOpen) "▲" else "▼", style = CubType.meta, color = colors.textDim)
        }

        if (selected > 0) {
            Chip("已选 $selected", tone = colors.accent, modifier = Modifier.padding(horizontal = 4.dp))
        }
        GlyphButton("⋮", size = 38) { state.paneMenu = settings.activeSide }
    }
}

/** The two corner radii this screen uses, named so they are written once. */
private val Shape9 = androidx.compose.foundation.shape.RoundedCornerShape(9.dp)
private val Shape11 = androidx.compose.foundation.shape.RoundedCornerShape(11.dp)

/** `clickable` without the ripple, which reads as noise inside a dense list. */
private fun Modifier.clickableCustom(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

/**
 * 显示方式▾ 展开条: one control per line, never wrapped.
 *
 * 行密度 is the percentage the design names, typeable and steppable; 列数 only appears for
 * the two grid modes, because a control that does nothing is worse than no control.
 */
@Composable
private fun DisplayBar(state: AndroidAppState) {
    val colors = CubTheme.colors
    val settings = state.liveSettings
    val side = settings.activeSide
    val prefs = state.browsing.prefsOf(side)

    Column(
        Modifier.fillMaxWidth().background(colors.surfaceRaised).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GroupLabel("显示方式", Modifier.width(58.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (mode in DisplayMode.entries) {
                    val active = prefs.displayMode == mode
                    Box(
                        Modifier
                            .clip(Shape9)
                            .background(if (active) colors.accent else colors.surfaceSunken)
                            .clickableCustom { state.setDisplayMode(side, mode) }
                            .padding(horizontal = 9.dp, vertical = 6.dp),
                    ) {
                        Text(mode.label, style = CubType.meta, color = if (active) Color.White else colors.textDim)
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            GroupLabel("行密度", Modifier.width(58.dp))
            StepperField(
                value = settings.rowDensity,
                onChange = { value -> state.changeSettings { s -> s.rowDensity = Density.clamp(value) } },
                canDecrease = Density.canDecrease(settings.rowDensity),
                canIncrease = Density.canIncrease(settings.rowDensity),
                step = Density.STEP,
                suffix = "%",
            )
            Spacer(Modifier.width(8.dp))
            Text("100% ＝出厂", style = CubType.meta, color = colors.textFaint)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            GroupLabel("列数", Modifier.width(58.dp))
            if (!prefs.displayMode.hasColumns) {
                Text("${prefs.displayMode.label}没有列数参数", style = CubType.meta, color = colors.textFaint)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (count in Columns.SHORTCUTS) {
                        QuietButton(count.toString()) {
                            state.changeSettings { it.columns = count }
                            state.touch()
                        }
                    }
                }
                Spacer(Modifier.width(6.dp))
                StepperField(
                    value = settings.columns,
                    onChange = { value -> state.changeSettings { s -> s.columns = Columns.clamp(value) }; state.touch() },
                    canDecrease = settings.columns > Columns.MIN,
                    canIncrease = settings.columns < Columns.MAX,
                    fieldWidth = 28,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            GroupLabel("分栏方向", Modifier.width(58.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (direction in PaneDirection.entries) {
                    val active = settings.paneDirection == direction
                    Box(
                        Modifier
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(9.dp))
                            .background(if (active) colors.accent else colors.surfaceSunken)
                            .clickableCustom { state.changeSettings { it.paneDirection = direction } }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(direction.label, style = CubType.meta, color = if (active) Color.White else colors.textDim)
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            GroupLabel("条目缩放", Modifier.width(58.dp))
            StepperField(
                value = settings.itemScale,
                onChange = { value -> state.changeSettings { s -> s.itemScale = ItemScale.clamp(value) } },
                canDecrease = settings.itemScale > ItemScale.MIN,
                canIncrease = settings.itemScale < ItemScale.MAX,
                step = ItemScale.STEP,
                suffix = "%",
            )
        }
    }
}

// ------------------------------------------------------------------------ pane

@Composable
private fun Pane(state: AndroidAppState, side: Side, pane: PaneState, modifier: Modifier) {
    val colors = CubTheme.colors
    val settings = state.liveSettings
    val active = settings.activeSide == side
    val scale = settings.itemScale / 100f

    Column(
        modifier.fillMaxHeight().background(if (active) colors.accent.copy(alpha = 0.05f) else Color.Transparent),
    ) {
        PaneHeader(state, side, pane, active)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                pane.error.isNotEmpty() -> EmptyState("读不了这个文件夹", pane.error)
                pane.entries.isEmpty() && !pane.loading ->
                    EmptyState(Words.EMPTY_FOLDER, action = { QuietButton(Words.NEW_FOLDER) { state.newFolder(side) } })
                else -> PaneList(state, side, pane, scale)
            }
        }
    }
}

/**
 * 栏头＝本栏地址.
 *
 * One line when there is room, and the number of lines the settings say when there is not
 * — the header grows to fit rather than squeezing the list. Every layer is tappable, the
 * middle folds into a `⋯` that carries the path it hides, and tapping the blank part of
 * the row turns it into an address box.
 */
@Composable
private fun PaneHeader(state: AndroidAppState, side: Side, pane: PaneState, active: Boolean) {
    val colors = CubTheme.colors
    val settings = state.liveSettings
    val lines = Header.linesFor(
        adaptive = settings.headerAdaptive,
        requested = settings.headerLines,
        paneWidthDp = if (state.showSoloLocal || state.showSoloRemote) 380f else 190f,
        paneCount = if (state.showSoloLocal || state.showSoloRemote) 1 else 2,
    )
    var editing by remember(pane.path) { mutableStateOf(false) }
    var typed by remember(pane.path) { mutableStateOf(pane.path) }
    var expanded by remember(pane.path) { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Header.heightDp(lines).dp)
            .background(if (active) colors.accent.copy(alpha = 0.10f) else colors.surface)
            .border(1.dp, if (active) colors.accent.copy(alpha = 0.45f) else Color.Transparent),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton("←", size = 36) { state.browsing.up(side) }

            if (editing) {
                Box(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Field(
                        value = typed,
                        onValueChange = { typed = it },
                        placeholder = "输入路径后回车直达",
                        style = CubType.meta,
                        onSubmit = {
                            state.browsing.openTyped(side, typed)
                            editing = false
                        },
                    )
                }
            } else {
                Column(
                    Modifier
                        .weight(1f)
                        .clickableCustom { editing = true }
                        .padding(horizontal = 2.dp, vertical = 4.dp),
                ) {
                    val crumbs = Browsing.crumbs(pane.path, maxSegments = if (expanded) 64 else if (lines > 1) 3 else 4)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        for ((index, crumb) in crumbs.withIndex()) {
                            if (index > 0) Text(" › ", style = CubType.meta, color = colors.textFaint)
                            Text(
                                crumb.label,
                                style = CubType.meta.copy(fontSize = 11.5.sp),
                                color = when {
                                    crumb.collapsed -> colors.accent
                                    index == crumbs.lastIndex -> colors.text
                                    else -> colors.textDim
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                    .clickableCustom {
                                        if (crumb.collapsed) expanded = true else state.openPane(side, crumb.path, pane.backendId)
                                    }
                                    .padding(horizontal = 1.dp),
                            )
                        }
                    }
                    if (lines > 1 && crumbs.size > 1) {
                        Text(
                            crumbs.last().path,
                            style = CubType.meta,
                            color = colors.textDim,
                            maxLines = lines - 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            GlyphButton(
                if (settings.isFavourite(pane.backendId, pane.path)) "★" else "☆",
                size = 34,
            ) { state.toggleFavourite(side) }
            GlyphButton("⋮", size = 34) {
                settings.activeSide = side
                state.paneMenu = side
            }
        }
    }
}

/** The list, in whichever of the five modes is in force. */
@Composable
private fun PaneList(state: AndroidAppState, side: Side, pane: PaneState, scale: Float) {
    val colors = CubTheme.colors
    val settings = state.liveSettings
    val prefs = state.browsing.prefsOf(side)
    val mode = prefs.displayMode

    if (mode == DisplayMode.COMPARE) {
        CompareList(state)
        return
    }

    val base = if (mode == DisplayMode.COMPACT) 48 else 56
    val rowHeight = (base * settings.rowDensity / 100f * scale).dp
    val fontSize = (13.5f * scale).sp

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 2.dp)) {
        items(pane.entries, key = { it.relPath }) { entry ->
            if (mode.isGrid) {
                GridTile(state, side, pane, entry, scale)
            } else {
                val selected = pane.isSelected(entry.relPath)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .background(if (selected) colors.accentContainer else Color.Transparent)
                        .clickableCustom {
                            if (entry.isDirectory) state.browsing.enter(side, Browsing.name(entry.relPath))
                            else state.browsing.toggle(side, entry.relPath)
                            state.touch()
                        }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (entry.isDirectory) "▸" else " ",
                        style = CubType.meta,
                        color = colors.textFaint,
                        modifier = Modifier.width(16.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            Browsing.name(entry.relPath),
                            style = CubType.body.copy(fontSize = fontSize),
                            color = if (entry.isDirectory) colors.text else colors.textDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (settings.showMetadata && mode == DisplayMode.DETAIL) {
                            Text(
                                if (entry.isDirectory) DASH else "${Format.bytes(entry.size)} · ${Format.stamp(entry.mtimeMs)}",
                                style = CubType.meta,
                                color = colors.textFaint,
                                maxLines = 1,
                            )
                        }
                    }
                    GlyphButton("⋮", size = 34) {
                        state.actionSheet = ActionSheet(side, entry.relPath, entry.isDirectory)
                    }
                }
            }
        }
    }
}

/** 卡片 / 缩略: bigger tiles, with the column count the settings carry. */
@Composable
private fun GridTile(state: AndroidAppState, side: Side, pane: PaneState, entry: FileMeta, scale: Float) {
    val colors = CubTheme.colors
    val selected = pane.isSelected(entry.relPath)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .height((64 * scale).dp)
            .clip(Shape11)
            .background(if (selected) colors.accentContainer else colors.surfaceRaised)
            .border(1.dp, if (selected) colors.accent else colors.outline, Shape11)
            .clickableCustom {
                if (entry.isDirectory) state.browsing.enter(side, Browsing.name(entry.relPath))
                else state.browsing.toggle(side, entry.relPath)
                state.touch()
            }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CubIcon(
            if (entry.isDirectory) IconPaths.CHEVRON_RIGHT else IconPaths.DOT,
            size = 16,
            tint = colors.textFaint,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(Browsing.name(entry.relPath), style = CubType.body, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!entry.isDirectory) Text(Format.bytes(entry.size), style = CubType.meta, color = colors.textFaint)
        }
    }
}

/** 对比: the two listings aligned by name, with the difference named. */
@Composable
private fun CompareList(state: AndroidAppState) {
    val colors = CubTheme.colors
    val rows = state.browsing.compare()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(colors.surfaceRaised).padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(Words.LOCAL, style = CubType.label, color = colors.accent, modifier = Modifier.weight(1f))
            Text(Words.REMOTE, style = CubType.label, color = colors.conflict, modifier = Modifier.weight(1f))
            Text("差异", style = CubType.label, color = colors.textDim, modifier = Modifier.width(84.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.name }) { row ->
                Row(
                    Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompareCell(row.name, row.left, colors) {
                        if (row.left?.isDirectory == true) state.browsing.enter(Side.LEFT, row.name)
                        else state.browsing.toggle(Side.LEFT, row.name)
                    }
                    CompareCell(row.name, row.right, colors) {
                        if (row.right?.isDirectory == true) state.browsing.enter(Side.RIGHT, row.name)
                        else state.browsing.toggle(Side.RIGHT, row.name)
                    }
                    Text(
                        if (row.difference == Difference.SAME) DASH else row.difference.label,
                        style = CubType.meta,
                        color = when (row.difference) {
                            Difference.SAME -> colors.textFaint
                            Difference.CONTENT_DIFFERS -> colors.conflict
                            else -> colors.warning
                        },
                        maxLines = 1,
                        modifier = Modifier.width(84.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.CompareCell(
    name: String,
    entry: FileMeta?,
    colors: CubColors,
    onClick: () -> Unit,
) {
    Row(Modifier.weight(1f).padding(end = 6.dp).clickableCustom(onClick), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(if (entry == null) colors.outlineStrong else colors.textFaint, 6)
        Spacer(Modifier.width(6.dp))
        Text(
            if (entry == null) DASH else name,
            style = CubType.meta,
            color = if (entry == null) colors.textFaint else colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------- arrows

@Composable
private fun TransferArrow(state: AndroidAppState, left: PaneState, right: PaneState, busy: Boolean, horizontal: Boolean) {
    val colors = CubTheme.colors
    val movableRight = left.selection.isNotEmpty() || left.entries.isNotEmpty()
    val movableLeft = right.selection.isNotEmpty() || right.entries.isNotEmpty()

    if (horizontal) {
        Row(
            Modifier.fillMaxWidth().height(46.dp).background(colors.surface).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuietButton("取回来", enabled = !busy && movableLeft) { state.transfer(Side.RIGHT) }
            Text("  两端  ", style = CubType.meta, color = colors.textFaint)
            QuietButton("送过去", enabled = !busy && movableRight) { state.transfer(Side.LEFT) }
        }
    } else {
        Row(
            Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuietButton("取回来", enabled = !busy && movableLeft) { state.transfer(Side.RIGHT) }
            Text("  传输方向  ", style = CubType.meta, color = colors.textFaint)
            QuietButton("送过去", enabled = !busy && movableRight) { state.transfer(Side.LEFT) }
        }
    }
}

/** The bar that appears under a marked row: the two things you would want next. */
@Composable
private fun ActionBar(state: AndroidAppState) {
    val colors = CubTheme.colors
    val left = state.browsing.current(Side.LEFT)
    val right = state.browsing.current(Side.RIGHT)
    val selected = left.selection.size + right.selection.size
    if (selected == 0) return

    Column {
        HorizontalDivider(color = colors.outline)
        Row(
            Modifier.fillMaxWidth().background(colors.surfaceRaised).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("已选 $selected 项", style = CubType.label, color = colors.text)
            Spacer(Modifier.weight(1f))
            QuietButton("传送") {
                val from = if (left.selection.isNotEmpty()) Side.LEFT else Side.RIGHT
                state.transfer(from)
            }
            QuietButton("清除") {
                state.browsing.clearAllSelections()
                state.touch()
            }
        }
    }
}

// ---------------------------------------------------------------------- sheets

/** 每栏 ⋮: 排序 / 文件夹优先 / 作用范围. The address and the display mode live elsewhere. */
@Composable
fun PaneSheet(state: AndroidAppState, side: Side) {
    val colors = CubTheme.colors
    val prefs = state.browsing.prefsOf(side)
    val pane = state.browsing.current(side)

    BottomSheet(onClose = { state.paneMenu = null }) {
        SheetHeader(if (side == Side.LEFT) Words.LOCAL_PANE else Words.REMOTE_PANE) { state.paneMenu = null }

        GroupLabel("排序")
        for (key in SortKey.entries) {
            val active = prefs.sortKey == key
            ListRow(
                title = key.label + if (active) (if (prefs.ascending) " ↑" else " ↓") else "",
                tint = if (active) colors.accent else null,
                onClick = {
                    state.applyPrefs(side, Scope.CURRENT_FOLDER) { current ->
                        if (current.sortKey == key) current.copy(ascending = !current.ascending) else current.copy(sortKey = key)
                    }
                },
            )
        }

        Spacer(Modifier.height(8.dp))
        GroupLabel("文件夹优先")
        SettingRow(if (prefs.foldersFirst) "文件夹排在前面" else "按所选排序统一排列", "与其它条目一起排") {
            Toggle(prefs.foldersFirst) { value ->
                state.applyPrefs(side, Scope.CURRENT_FOLDER) { it.copy(foldersFirst = value) }
            }
        }

        Spacer(Modifier.height(8.dp))
        GroupLabel("作用范围")
        for (scope in Scope.entries) {
            ListRow(
                title = scope.label,
                detail = when (scope) {
                    Scope.CURRENT_FOLDER -> "只影响 ${Browsing.name(pane.path).ifEmpty { pane.path }}"
                    Scope.THIS_PANE -> "这一栏的所有目录"
                    Scope.BOTH_PANES -> "两栏共用，并清掉各自的局部设置"
                },
                onClick = {
                    state.applyPrefs(side, scope) { it }
                    state.notify("已按「${scope.label}」应用", NoticeKind.SUCCESS)
                    state.paneMenu = null
                },
            )
        }
        Text(
            "越具体越优先：当前目录 > 本栏全局 > 两栏共用 > 出厂默认",
            style = CubType.meta,
            color = colors.textFaint,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** 长按行的动作面板. */
@Composable
fun ActionSheetPanel(state: AndroidAppState, sheet: ActionSheet) {
    val colors = CubTheme.colors
    var renameTo by remember(sheet.name) { mutableStateOf(sheet.name) }
    var renaming by remember(sheet.name) { mutableStateOf(false) }

    BottomSheet(onClose = { state.actionSheet = null }) {
        SheetHeader(Browsing.name(sheet.name)) { state.actionSheet = null }

        if (renaming) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Field(renameTo, { renameTo = it }, Modifier.weight(1f), onSubmit = {
                    state.rename(sheet.side, sheet.name, renameTo)
                    state.actionSheet = null
                })
                Spacer(Modifier.width(8.dp))
                PrimaryButton("确定") {
                    state.rename(sheet.side, sheet.name, renameTo)
                    state.actionSheet = null
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        ListRow("传送 ▸ 对侧", "把这一项送到另一栏", onClick = {
            state.browsing.select(sheet.side, listOf(sheet.name))
            state.transfer(sheet.side)
            state.actionSheet = null
        })
        ListRow("收藏", "把当前目录加进收藏夹", onClick = {
            state.toggleFavourite(sheet.side)
            state.actionSheet = null
        })
        ListRow("过滤", "按名字或后缀排除", onClick = {
            state.notify("过滤器在同步配置的「过滤」里设置，这样两侧规则一致", NoticeKind.INFO)
            state.actionSheet = null
        })
        ListRow("重命名", "改成别的名字", onClick = { renaming = true })
        ListRow("详情", "大小、时间、类型", onClick = {
            val pane = state.browsing.current(sheet.side)
            val entry = pane.entries.firstOrNull { it.relPath == sheet.name }
            state.notify(
                "${Browsing.name(sheet.name)} · ${if (entry?.isDirectory == true) "文件夹" else Format.bytes(entry?.size ?: 0)} · ${Format.stamp(entry?.mtimeMs ?: 0)}",
                NoticeKind.INFO,
            )
            state.actionSheet = null
        })
        ListRow("删除", "进${Words.TRASH}，不是直接销毁", tint = colors.danger, onClick = {
            state.browsing.select(sheet.side, listOf(sheet.name))
            state.deleteSelection(sheet.side)
            state.actionSheet = null
        })
    }
}
