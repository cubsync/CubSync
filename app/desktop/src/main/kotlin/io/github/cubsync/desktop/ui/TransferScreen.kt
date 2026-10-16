package io.github.cubsync.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.cubsync.core.spi.FileMeta
import io.github.cubsync.desktop.AppState
import io.github.cubsync.desktop.CubTheme
import io.github.cubsync.desktop.CubType
import io.github.cubsync.desktop.NoticeKind
import io.github.cubsync.desktop.copyToClipboard
import io.github.cubsync.desktop.pickDirectory
import io.github.cubsync.service.Browsing
import io.github.cubsync.service.Columns
import io.github.cubsync.service.Density
import io.github.cubsync.service.Difference
import io.github.cubsync.service.DisplayMode
import io.github.cubsync.service.Format
import io.github.cubsync.service.IconPaths
import io.github.cubsync.service.ItemScale
import io.github.cubsync.service.PaneDirection
import io.github.cubsync.service.PaneState
import io.github.cubsync.service.Scope
import io.github.cubsync.service.Section
import io.github.cubsync.service.Side
import io.github.cubsync.service.SortKey
import io.github.cubsync.service.Words
import io.github.cubsync.service.resolveDirection
import io.github.cubsync.service.fileIcon
import io.github.cubsync.service.viewIcon

/**
 * 传输: two panes, a display menu that acts on the pane you touched last, and the arrow
 * in the middle that is the whole point of the screen.
 *
 * The desktop is allowed a different *placement* from the phone — an always-on address
 * box instead of a header you tap, a menu instead of an expand bar — but not a different
 * word or a different number of steps. Both come from `UiSpec`.
 */
@Composable
fun TransferScreen(state: AppState) {
    val colors = CubTheme.colors
    val session = state.browsing
    val left by session.left.collectAsState()
    val right by session.right.collectAsState()
    val busy by session.busy.collectAsState()

    // The revision counters are read so that a preference written anywhere redraws here.
    val revision = state.settingsRevision + state.browserRevision
    val settings = state.liveSettings
    val direction = resolveDirection(settings.paneDirection, 1000f, if (revision >= 0) 600f else 600f)
    val sideBySide = direction == PaneDirection.SIDE_BY_SIDE

    Column(Modifier.fillMaxSize()) {
        // The prototype's transfer screen has no sub-row and no standing display bar: the
        // three verbs are on `.chead .acts`, and 显示方式 opens a drop-down from there. Both
        // were added here and both are gone, because a screen with two ways to reach one
        // control is a screen with one control too many.
        HorizontalDivider(color = colors.outline)

        if (sideBySide) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                Pane(state, Side.LEFT, left, Modifier.weight(1f))
                TransferArrow(state, left, right, busy, horizontal = true)
                Pane(state, Side.RIGHT, right, Modifier.weight(1f))
            }
        } else {
            Column(Modifier.weight(1f).fillMaxWidth()) {
                Pane(state, Side.LEFT, left, Modifier.weight(1f))
                TransferArrow(state, left, right, busy, horizontal = false)
                Pane(state, Side.RIGHT, right, Modifier.weight(1f))
            }
        }

        TransferBar(state, busy)
        Overlays(state)
    }
}

/**
 * 传输's `.chead .acts`: 显示方式, 新建文件夹, 同步…
 *
 * The same three the prototype puts on the heading, and the same three the phone keeps at
 * the two ends of its sub-row — one set of words, two places to reach them.
 */
@Composable
fun TransferHeadActions(state: AppState) {
    // The prototype's `.btn.bview`: the current mode on the button, and the five modes plus
    // their parameters in a drop-down. There is no standing display bar — that was added
    // here and is gone, because the design keeps this control in one place.
    val active = state.browsing.prefsOf(state.liveSettings.activeSide)
    QuietButton("显示方式：${active.displayMode.label} ▾") { state.displayMenu = true }
    QuietButton(Words.NEW_FOLDER) { state.newFolder(state.liveSettings.activeSide) }
    QuietButton("同步…") { state.goTo(Section.SYNC) }
}

// --------------------------------------------------------------------- sub row

/**
 * The sub-row: the display menu, the two creation verbs, the selection count, and the
 * two arrows.
 *
 * Never scrolls horizontally and never wraps — the phone's version of this row is four
 * items wide on a 280px screen, and it is the same four here with a mouse attached.
 */
@Composable
private fun SubRow(state: AppState, left: PaneState, right: PaneState, busy: Boolean) {
    val colors = CubTheme.colors
    val settings = state.liveSettings
    val active = state.browsing.prefsOf(settings.activeSide)

    Row(
        Modifier.fillMaxWidth().heightIn(min = 38.dp).padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surfaceRaised)
                .border(1.dp, colors.outline, RoundedCornerShape(8.dp))
                .clickable { settings.displayBarOpen = !settings.displayBarOpen; state.touchBrowsing() }
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("显示方式：${active.displayMode.label}", style = CubType.label, color = colors.text)
            Spacer(Modifier.width(5.dp))
            CubIcon(
                if (settings.displayBarOpen) IconPaths.CHEVRON_DOWN else IconPaths.CHEVRON_RIGHT,
                size = 13,
                tint = colors.textDim,
            )
        }
        Spacer(Modifier.width(8.dp))
        QuietButton(Words.NEW_FOLDER) { state.newFolder(settings.activeSide) }
        QuietButton("同步…") { state.goTo(Section.SYNC) }
        QuietButton("收藏当前目录") { state.toggleFavourite(settings.activeSide) }

        Spacer(Modifier.weight(1f))

        val selected = left.selection.size + right.selection.size
        if (selected > 0) {
            Chip("已选 $selected", tone = colors.accent)
            Spacer(Modifier.width(8.dp))
        }
        QuietButton("送至对侧", enabled = !busy) { state.transfer(Side.LEFT) }
        QuietButton("取回本栏", enabled = !busy) { state.transfer(Side.RIGHT) }
    }
}

/**
 * The display bar: five modes, a row density that is a percentage, a column count, and a
 * pane direction.
 *
 * 行密度 and 列数 are mutually exclusive in practice — a list has a row height, a grid has
 * columns — and the bar says which one applies rather than showing a control that does
 * nothing.
 */
@Composable
private fun DisplayBar(state: AppState) {
    val colors = CubTheme.colors
    val settings = state.liveSettings
    val side = settings.activeSide
    val prefs = state.browsing.prefsOf(side)

    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surfaceRaised)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GroupLabel("显示方式", Modifier.width(64.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (mode in DisplayMode.entries) {
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (prefs.displayMode == mode) colors.accent else colors.surfaceSunken)
                            .clickable { state.setDisplayMode(side, mode) }
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            mode.label,
                            style = CubType.label,
                            color = if (prefs.displayMode == mode) Color.White else colors.textDim,
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text("作用于${if (side == Side.LEFT) Words.LOCAL_PANE else Words.REMOTE_PANE}", style = CubType.meta, color = colors.textFaint)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            GroupLabel("行密度", Modifier.width(64.dp))
            StepperField(
                value = settings.rowDensity,
                onChange = { value -> state.changeSettings { s -> s.rowDensity = Density.clamp(value) } },
                canDecrease = Density.canDecrease(settings.rowDensity),
                canIncrease = Density.canIncrease(settings.rowDensity),
                step = Density.STEP,
                suffix = "%",
            )
            Spacer(Modifier.width(10.dp))
            Text("100% ＝出厂行高，50–250", style = CubType.meta, color = colors.textFaint)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            GroupLabel("列数", Modifier.width(64.dp))
            if (!prefs.displayMode.hasColumns) {
                Text("${prefs.displayMode.label}没有列数参数", style = CubType.meta, color = colors.textFaint)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (count in Columns.SHORTCUTS) {
                        QuietButton(
                            count.toString(),
                            enabled = true,
                        ) { state.changeSettings { s -> s.columns = count }; state.touchBrowsing() }
                    }
                }
                Spacer(Modifier.width(8.dp))
                StepperField(
                    value = settings.columns,
                    onChange = { value -> state.changeSettings { s -> s.columns = Columns.clamp(value) }; state.touchBrowsing() },
                    canDecrease = settings.columns > Columns.MIN,
                    canIncrease = settings.columns < Columns.MAX,
                    fieldWidth = 30,
                )
                Spacer(Modifier.width(8.dp))
                Text("1–12", style = CubType.meta, color = colors.textFaint)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            GroupLabel("分栏方向", Modifier.width(64.dp))
            Segmented(
                options = PaneDirection.entries.toList(),
                selected = settings.paneDirection,
                label = { it.label },
                onSelect = { state.changeSettings { s -> s.paneDirection = it } },
            )
            Spacer(Modifier.width(10.dp))
            Text("自动＝容器宽 ≥ 高 × 1.15 时左右", style = CubType.meta, color = colors.textFaint)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            GroupLabel("条目缩放", Modifier.width(64.dp))
            StepperField(
                value = settings.itemScale,
                onChange = { value -> state.changeSettings { s -> s.itemScale = ItemScale.clamp(value) } },
                canDecrease = settings.itemScale > ItemScale.MIN,
                canIncrease = settings.itemScale < ItemScale.MAX,
                step = ItemScale.STEP,
                suffix = "%",
            )
            Spacer(Modifier.width(10.dp))
            Text("双击列表或 Ctrl+滚轮可复位 / 连续调整", style = CubType.meta, color = colors.textFaint)
        }
    }
}

// ------------------------------------------------------------------------ pane

/** One pane: an address header, a list, and the ⋮ that belongs to that pane. */
@Composable
private fun Pane(state: AppState, side: Side, pane: PaneState, modifier: Modifier) {
    val colors = CubTheme.colors
    val settings = state.liveSettings
    val active = settings.activeSide == side
    val selected = state.browsing.prefsOf(side)
    val scale = settings.itemScale / 100f

    Column(
        modifier
            .fillMaxHeight()
            .background(if (active) colors.accent.copy(alpha = 0.04f) else Color.Transparent),
    ) {
        PaneHeader(state, side, pane, active)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                pane.loading && pane.entries.isEmpty() ->
                    EmptyState("正在读取…")
                pane.error.isNotEmpty() ->
                    EmptyState("读不了这个文件夹", pane.error)
                pane.entries.isEmpty() ->
                    EmptyState(Words.EMPTY_FOLDER, action = { QuietButton(Words.NEW_FOLDER) { state.newFolder(side) } })
                selected.displayMode == DisplayMode.COMPARE ->
                    CompareList(state)
                selected.displayMode.isGrid ->
                    GridList(state, side, pane, settings.columns, scale)
                else ->
                    ListPane(state, side, pane, selected.displayMode, settings.rowDensity, scale, settings.showMetadata)
            }
        }
    }
}

/** The header of a pane: ←, the address, ☆ and ⋮. */
@Composable
private fun PaneHeader(state: AppState, side: Side, pane: PaneState, active: Boolean) {
    val colors = CubTheme.colors
    var typed by remember(pane.path) { mutableStateOf(pane.path) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(if (active) colors.accent.copy(alpha = 0.13f) else colors.surface)
            .border(1.dp, if (active) colors.accent.copy(alpha = 0.4f) else Color.Transparent),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(IconPaths.BACK, size = 26) { state.browsing.up(side) }
            Spacer(Modifier.width(4.dp))
            Field(
                value = typed,
                onValueChange = { typed = it },
                modifier = Modifier.weight(1f),
                placeholder = "输入路径后回车直达",
                onSubmit = { state.openPane(side, typed, pane.backendId); state.touchBrowsing() },
                style = CubType.meta,
            )
            Spacer(Modifier.width(4.dp))
            val starred = state.service.settings.isFavourite(pane.backendId, pane.path)
            IconButton(
                IconPaths.STAR,
                size = 26,
                mark = 16,
                tint = if (starred) colors.accent else null,
            ) { state.toggleFavourite(side) }
            IconButton(IconPaths.MORE, size = 26, mark = 18, stroke = 3.4f) {
                state.paneMenu = io.github.cubsync.desktop.PaneMenu(side)
            }
        }
        Breadcrumbs(state, side, pane)
    }
}

/**
 * The breadcrumb row.
 *
 * Every layer is clickable, and the middle folds into one `⋯` that still carries the path
 * it hides — which is the difference between a collapsed address and a lost one.
 */
@Composable
private fun Breadcrumbs(state: AppState, side: Side, pane: PaneState) {
    val colors = CubTheme.colors
    if (pane.path.isEmpty()) return
    val crumbs = Browsing.crumbs(pane.path, maxSegments = 5)
    var expanded by remember(pane.path) { mutableStateOf(false) }
    val shown = if (expanded) Browsing.crumbs(pane.path, maxSegments = 64) else crumbs

    Row(
        Modifier.fillMaxWidth().padding(start = 10.dp, end = 8.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((index, crumb) in shown.withIndex()) {
            if (index > 0) Text(" › ", style = CubType.meta, color = colors.textFaint)
            Text(
                crumb.label,
                style = CubType.meta.copy(fontSize = 11.sp),
                color = when {
                    crumb.collapsed -> colors.accent
                    index == shown.lastIndex -> colors.text
                    else -> colors.textDim
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable {
                        if (crumb.collapsed) expanded = true else state.openPane(side, crumb.path, pane.backendId)
                    }
                    .padding(horizontal = 2.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        if (!expanded && crumbs.size > 1) {
            Text(
                "路径已折叠",
                style = CubType.meta,
                color = colors.textFaint,
                modifier = Modifier.clickable { expanded = true },
            )
        }
    }
}

// ----------------------------------------------------------------------- lists

private val ROW_BASE_DP = 24
private val COMPACT_BASE_DP = 20

@Composable
private fun ListPane(
    state: AppState,
    side: Side,
    pane: PaneState,
    mode: DisplayMode,
    density: Int,
    scale: Float,
    showMetadata: Boolean,
) {
    val colors = CubTheme.colors
    val base = if (mode == DisplayMode.COMPACT) COMPACT_BASE_DP else ROW_BASE_DP
    val rowHeight = (base * density / 100f * scale).dp
    val fontSize = (12.5f * scale).sp

    Column(Modifier.fillMaxSize()) {
        if (showMetadata) {
            ListHeader(mode = mode, prefs = state.browsing.prefsOf(side), side = side, state = state)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(pane.entries, key = { it.relPath }) { entry ->
                RowLine(
                    state = state,
                    side = side,
                    entry = entry,
                    selected = pane.isSelected(entry.relPath),
                    rowHeight = rowHeight,
                    fontSize = fontSize,
                    mode = mode,
                    showMetadata = showMetadata,
                )
            }
        }
    }
}

/** The clickable column headings, so sorting does not need a menu of its own. */
@Composable
private fun ListHeader(mode: DisplayMode, prefs: io.github.cubsync.service.BrowsePrefs, side: Side, state: AppState) {
    val colors = CubTheme.colors
    Row(
        Modifier.fillMaxWidth().background(colors.surfaceRaised).padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SortHeading(state, side, prefs, SortKey.NAME, "名称", Modifier.weight(1f))
        if (mode != DisplayMode.COMPACT) {
            SortHeading(state, side, prefs, SortKey.MODIFIED, "修改时间", Modifier.width(120.dp))
        }
        SortHeading(state, side, prefs, SortKey.SIZE, "大小", Modifier.width(72.dp))
    }
}

@Composable
private fun SortHeading(
    state: AppState,
    side: Side,
    prefs: io.github.cubsync.service.BrowsePrefs,
    key: SortKey,
    label: String,
    modifier: Modifier,
) {
    val colors = CubTheme.colors
    val active = prefs.sortKey == key
    val arrow = if (!active) "" else if (prefs.ascending) " ↑" else " ↓"
    Text(
        label + arrow,
        style = CubType.meta,
        color = if (active) colors.accent else colors.textFaint,
        maxLines = 1,
        modifier = modifier
            .clickable {
                state.applyPrefs(side, Scope.CURRENT_FOLDER) { current ->
                    if (current.sortKey == key) current.copy(ascending = !current.ascending) else current.copy(sortKey = key)
                }
            }
            .padding(vertical = 3.dp),
    )
}

@Composable
private fun RowLine(
    state: AppState,
    side: Side,
    entry: FileMeta,
    selected: Boolean,
    rowHeight: androidx.compose.ui.unit.Dp,
    fontSize: androidx.compose.ui.unit.TextUnit,
    mode: DisplayMode,
    showMetadata: Boolean,
) {
    val colors = CubTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .background(if (selected) colors.selected else Color.Transparent)
            .clickable {
                if (entry.isDirectory) state.browsing.enter(side, Browsing.name(entry.relPath))
                else state.browsing.toggle(side, entry.relPath)
                state.touchBrowsing()
            }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // `.drow .ico`: folder, image, video or document, before the name.
        CubIcon(
            fileIcon(Browsing.name(entry.relPath), entry.isDirectory),
            size = 15,
            tint = if (entry.isDirectory) colors.textDim else colors.textFaint,
            modifier = Modifier.width(20.dp),
        )
        Text(
            Browsing.name(entry.relPath),
            style = CubType.body.copy(fontSize = fontSize),
            color = if (entry.isDirectory) colors.text else colors.textDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (showMetadata && mode != DisplayMode.COMPACT) {
            Text(
                if (entry.isDirectory) DASH else Format.stamp(entry.mtimeMs),
                style = CubType.meta,
                color = colors.textFaint,
                maxLines = 1,
                modifier = Modifier.width(120.dp),
            )
        }
        if (showMetadata) {
            Text(
                if (entry.isDirectory) DASH else Format.bytes(entry.size),
                style = CubType.meta,
                color = colors.textFaint,
                maxLines = 1,
                modifier = Modifier.width(72.dp),
            )
        }
        IconButton(IconPaths.MORE, size = 22, mark = 16, stroke = 3.4f) {
            state.actionSheet = io.github.cubsync.desktop.ActionSheet(side, entry.relPath, entry.isDirectory)
        }
    }
}

@Composable
private fun GridList(state: AppState, side: Side, pane: PaneState, columns: Int, scale: Float) {
    val colors = CubTheme.colors
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns.coerceAtLeast(1)),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
    ) {
        items(pane.entries, key = { it.relPath }) { entry ->
            val selected = pane.isSelected(entry.relPath)
            Column(
                Modifier
                    .padding(3.dp)
                    .height((86 * scale).dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) colors.accentSoft else colors.surfaceRaised)
                    .border(1.dp, if (selected) colors.accent else colors.outline, RoundedCornerShape(10.dp))
                    .clickable {
                        if (entry.isDirectory) state.browsing.enter(side, Browsing.name(entry.relPath))
                        else state.browsing.toggle(side, entry.relPath)
                        state.touchBrowsing()
                    }
                    .padding(8.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                CubIcon(
                    if (entry.isDirectory) IconPaths.FOLDER else IconPaths.DOT,
                    size = 15,
                    tint = colors.textFaint,
                )
                Column {
                    Text(
                        Browsing.name(entry.relPath),
                        style = CubType.meta.copy(fontSize = (12f * scale).sp),
                        color = colors.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!entry.isDirectory) {
                        Text(Format.bytes(entry.size), style = CubType.meta, color = colors.textFaint)
                    }
                }
            }
        }
    }
}

/**
 * 对比: the two listings aligned by name.
 *
 * Rows that exist on one side only are kept, because a file that is missing is the thing
 * this view exists to show — and the difference is named rather than left to the reader
 * to spot.
 */
@Composable
private fun CompareList(state: AppState) {
    val colors = CubTheme.colors
    val rows = state.browsing.compare()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(colors.surfaceRaised).padding(horizontal = 10.dp, vertical = 4.dp)) {
            Text("${Words.LOCAL} · ${Browsing.name(state.browsing.current(Side.LEFT).path)}", style = CubType.label, color = colors.accent, modifier = Modifier.weight(1f))
            Text("${Words.REMOTE} · ${Browsing.name(state.browsing.current(Side.RIGHT).path)}", style = CubType.label, color = colors.conflict, modifier = Modifier.weight(1f))
            Text("差异", style = CubType.label, color = colors.textDim, modifier = Modifier.width(90.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.name }) { row ->
                Row(
                    Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 10.dp),
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
                        if (row.difference == Difference.SAME) "" else row.difference.label,
                        style = CubType.meta,
                        color = when (row.difference) {
                            Difference.SAME -> colors.textFaint
                            Difference.CONTENT_DIFFERS -> colors.conflict
                            else -> colors.warning
                        },
                        maxLines = 1,
                        modifier = Modifier.width(90.dp),
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
    colors: io.github.cubsync.desktop.CubColors,
    onClick: () -> Unit,
) {
    Row(
        Modifier.weight(1f).padding(end = 8.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(if (entry == null) colors.outlineStrong else colors.textFaint, 6)
        Spacer(Modifier.width(7.dp))
        Text(
            if (entry == null) DASH else name,
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

// ---------------------------------------------------------------------- arrows

/** The arrow between the panes, which is the reason the screen exists. */
@Composable
private fun TransferArrow(state: AppState, left: PaneState, right: PaneState, busy: Boolean, horizontal: Boolean) {
    val colors = CubTheme.colors
    val movableRight = left.selection.isNotEmpty() || left.entries.isNotEmpty()
    val movableLeft = right.selection.isNotEmpty() || right.entries.isNotEmpty()

    // The prototype's `.midbar`: `→` sends to the far pane and `←` brings back, and
    // nothing else. Drawn as a narrow strip so it reads as belonging to neither pane,
    // which two bare buttons between them did not.
    if (horizontal) {
        Column(
            Modifier
                .width(46.dp)
                .fillMaxHeight()
                .background(colors.surfaceSunken),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(72.dp))
            IconButton(
                IconPaths.ARROW_RIGHT,
                size = 30,
                mark = 16,
                enabled = !busy && movableRight,
            ) { state.transfer(Side.LEFT) }
            Spacer(Modifier.height(8.dp))
            IconButton(
                IconPaths.ARROW_LEFT,
                size = 30,
                mark = 16,
                enabled = !busy && movableLeft,
            ) { state.transfer(Side.RIGHT) }
        }
    } else {
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(colors.surfaceSunken),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                IconPaths.ARROW_RIGHT,
                size = 30,
                mark = 16,
                enabled = !busy && movableRight,
            ) { state.transfer(Side.LEFT) }
            Spacer(Modifier.width(16.dp))
            IconButton(
                IconPaths.ARROW_LEFT,
                size = 30,
                mark = 16,
                enabled = !busy && movableLeft,
            ) { state.transfer(Side.RIGHT) }
        }
    }
}

// ------------------------------------------------------------------- transfer bar

/** The strip along the bottom: what is moving, how fast, and how to stop it. */
@Composable
private fun TransferBar(state: AppState, busy: Boolean) {
    val colors = CubTheme.colors
    val running = state.running
    val left = state.browsing.current(Side.LEFT)
    val right = state.browsing.current(Side.RIGHT)

    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = colors.outline)
        Row(
            Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            busy -> "传送中"
                            running != null -> "${running.label}「${running.configName}」"
                            else -> "等待任务"
                        },
                        style = CubType.label,
                        color = colors.text,
                    )
                    Spacer(Modifier.width(8.dp))
                    Chip(if (busy) "读取中" else DASH)
                }
                Text(
                    "${Words.LOCAL} ${left.entries.size} 项 · ${Words.REMOTE} ${right.entries.size} 项 · 已选 ${left.selection.size + right.selection.size}",
                    style = CubType.meta,
                    color = colors.textFaint,
                )
            }
            if (running != null) {
                val elapsed = System.currentTimeMillis() - running.startedAtMs
                Text(Format.rate(running.bytes, elapsed), style = CubType.meta, color = colors.textDim)
                Spacer(Modifier.width(10.dp))
                QuietButton("暂停 / 取消", danger = true) { state.cancelRun() }
            }
            QuietButton("重新读取两侧") {
                state.browsing.refreshBoth()
                state.notify("已重新读取", NoticeKind.INFO)
            }
        }
    }
}

// --------------------------------------------------------------------- overlays

/**
 * The pop-ups: the pane menu, the row action sheet, the detail card.
 *
 * They are drawn here rather than inside a pane so that they are not clipped by the pane
 * they belong to, and so that clicking outside them closes them the same way everywhere.
 */
@Composable
private fun Overlays(state: AppState) {
    val paneMenu = state.paneMenu
    val sheet = state.actionSheet
    val detail = state.detailOf
    val displayMenu = state.displayMenu

    if (paneMenu == null && sheet == null && detail == null && !displayMenu) return

    Box(Modifier.fillMaxSize()) {
        Scrim {
            state.paneMenu = null
            state.actionSheet = null
            state.detailOf = null
            state.renaming = null
            state.displayMenu = false
        }
        if (displayMenu) {
            // Anchored where the prototype anchors it: under the button that opened it, at
            // the right end of the heading — which is where that button is.
            Box(Modifier.fillMaxSize().padding(top = 112.dp, end = 22.dp), contentAlignment = Alignment.TopEnd) {
                DisplayModeMenuCard(state)
            }
        }
        Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
            when {
                paneMenu != null -> PaneMenuCard(state, paneMenu.side)
                sheet != null -> ActionSheetCard(state, sheet.side, sheet.name, sheet.isDirectory)
                detail != null -> DetailCard(state, detail.first, detail.second)
            }
        }
    }
}

/**
 * 显示方式, as the prototype draws it: a drop-down from the heading.
 *
 * Five modes each behind its own mark, then 行密度, then 列数 when the mode has columns,
 * then 分栏方向. The marks come from `IconPaths.VIEW_*`, which are the prototype's
 * `⋮≡ ☰ ▤ ▦ ⇄` drawn as paths — a menu of five words where the design has five pictures is
 * the kind of difference that makes two interfaces stop looking like one product.
 */
@Composable
private fun DisplayModeMenuCard(state: AppState) {
    val colors = CubTheme.colors
    val settings = state.liveSettings
    val side = settings.activeSide
    val prefs = state.browsing.prefsOf(side)
    val paneName = if (side == Side.LEFT) Words.LOCAL_PANE else Words.REMOTE_PANE

    FloatingCard(width = 330) {
        FloatingHeader("显示方式 · 作用于$paneName（高亮那栏）") { state.displayMenu = false }

        Block("") {
            for (mode in DisplayMode.entries) {
                val active = prefs.displayMode == mode
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            state.setDisplayMode(side, mode)
                            state.touchBrowsing()
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CubIcon(
                        viewIcon(mode),
                        size = 16,
                        tint = if (active) colors.accent else colors.textDim,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        mode.label,
                        style = CubType.body,
                        color = if (active) colors.accent else colors.text,
                        modifier = Modifier.weight(1f),
                    )
                    if (active) CubIcon(IconPaths.CHECK, size = 14, tint = colors.accent)
                }
            }
        }

        Block("行密度") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepperField(
                    value = settings.rowDensity,
                    onChange = { value -> state.changeSettings { s -> s.rowDensity = Density.clamp(value) } },
                    canDecrease = Density.canDecrease(settings.rowDensity),
                    canIncrease = Density.canIncrease(settings.rowDensity),
                    step = Density.STEP,
                    suffix = "%",
                )
                Spacer(Modifier.width(10.dp))
                Text("100% ＝出厂行高，50–250", style = CubType.tiny, color = colors.textFaint)
            }
        }

        if (prefs.displayMode.hasColumns) {
            Block("${prefs.displayMode.label} · 列数") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    for (count in Columns.SHORTCUTS) {
                        QuietButton(count.toString()) {
                            state.changeSettings { s -> s.columns = count }
                            state.touchBrowsing()
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    StepperField(
                        value = settings.columns,
                        onChange = { value ->
                            state.changeSettings { s -> s.columns = Columns.clamp(value) }
                            state.touchBrowsing()
                        },
                        canDecrease = settings.columns > Columns.MIN,
                        canIncrease = settings.columns < Columns.MAX,
                        fieldWidth = 30,
                    )
                }
            }
        }

        Block("分栏方向 · 本窗口") {
            for ((option, note) in listOf(
                PaneDirection.AUTO to "宽 ≥ 高 时左右，否则上下",
                PaneDirection.SIDE_BY_SIDE to "左栏${Words.LOCAL} · 右栏${Words.REMOTE}",
                PaneDirection.STACKED to "上排${Words.LOCAL} · 下排${Words.REMOTE}",
            )) {
                val active = settings.paneDirection == option
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { state.changeSettings { s -> s.paneDirection = option } }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(if (active) colors.accent else colors.textFaint, size = 7)
                    Spacer(Modifier.width(9.dp))
                    Text(option.label, style = CubType.body, color = colors.text)
                    Spacer(Modifier.weight(1f))
                    Text(note, style = CubType.tiny, color = colors.textFaint)
                }
            }
        }
    }
}

/** 排序与作用范围 — and nothing else. The address and the display mode have their own homes. */
@Composable
private fun PaneMenuCard(state: AppState, side: Side) {
    val colors = CubTheme.colors
    val prefs = state.browsing.prefsOf(side)
    val pane = state.browsing.current(side)

    FloatingCard(width = 300) {
        FloatingHeader(if (side == Side.LEFT) Words.LOCAL_PANE else Words.REMOTE_PANE, onClose = { state.paneMenu = null })

        Block("地址") {
            ListLine(
                title = Browsing.name(pane.path).ifEmpty { pane.path },
                detail = pane.path,
                trailing = {
                    QuietButton("复制") {
                        copyToClipboard(pane.path)
                        state.notify("已复制 ${pane.path}", NoticeKind.SUCCESS)
                    }
                },
            )
        }

        Block("排序") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (key in SortKey.entries) {
                    QuietButton(
                        key.label + if (prefs.sortKey == key) (if (prefs.ascending) " ↑" else " ↓") else "",
                        enabled = true,
                    ) {
                        state.applyPrefs(side, Scope.CURRENT_FOLDER) { current ->
                            if (current.sortKey == key) current.copy(ascending = !current.ascending) else current.copy(sortKey = key)
                        }
                    }
                }
            }
        }

        Block("文件夹优先") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Toggle(prefs.foldersFirst) { value ->
                    state.applyPrefs(side, Scope.CURRENT_FOLDER) { it.copy(foldersFirst = value) }
                }
                Spacer(Modifier.width(8.dp))
                Text(if (prefs.foldersFirst) "文件夹排在前面" else "按所选排序统一排列", style = CubType.meta, color = colors.textFaint)
            }
        }

        Block("作用范围") {
            for (scope in Scope.entries) {
                ListLine(
                    title = scope.label,
                    detail = when (scope) {
                        Scope.CURRENT_FOLDER -> "只影响 ${Browsing.name(pane.path)}"
                        Scope.THIS_PANE -> "这一栏的所有目录"
                        Scope.BOTH_PANES -> "两栏共用，并清掉各自的局部设置"
                    },
                    trailing = {
                        QuietButton("应用到这一档") {
                            state.applyPrefs(side, scope) { it }
                            state.notify("已按「${scope.label}」应用", NoticeKind.SUCCESS)
                        }
                    },
                )
            }
            Text(
                "越具体越优先：当前目录 > 本栏全局 > 两栏共用 > 出厂默认",
                style = CubType.meta,
                color = colors.textFaint,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 长按行的动作面板, as a card with a mouse. */
@Composable
private fun ActionSheetCard(state: AppState, side: Side, name: String, isDirectory: Boolean) {
    val colors = CubTheme.colors
    var renameTo by remember(name) { mutableStateOf(name) }

    FloatingCard(width = 320) {
        FloatingHeader(Browsing.name(name), onClose = { state.actionSheet = null; state.renaming = null })

        if (state.renaming == name) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Field(renameTo, { renameTo = it }, Modifier.weight(1f), onSubmit = { state.rename(side, name, renameTo) })
                Spacer(Modifier.width(6.dp))
                PrimaryButton("确定") { state.rename(side, name, renameTo) }
            }
            Spacer(Modifier.height(6.dp))
        }

        Action("传送 ▸ 对侧", "把这一项送到另一栏") {
            state.browsing.select(side, listOf(name))
            state.transfer(side)
            state.actionSheet = null
        }
        Action("收藏", "把当前目录加进收藏夹") { state.toggleFavourite(side); state.actionSheet = null }
        Action("过滤", "按名字或后缀排除") {
            state.notify("过滤器在同步配置的「过滤」里设置，这样两侧规则一致", NoticeKind.INFO)
            state.actionSheet = null
        }
        Action("重命名", "改成别的名字") { state.renaming = name }
        Action("详情", "大小、时间、类型") { state.detailOf = side to name }
        Action(if (isDirectory) "删除目录" else "删除", "进${Words.TRASH}，不是直接销毁", danger = true) {
            state.browsing.select(side, listOf(name))
            state.deleteSelection(side)
            state.actionSheet = null
        }
        colors.hashCode()
    }
}

@Composable
private fun Action(label: String, hint: String, danger: Boolean = false, onClick: () -> Unit) {
    ListLine(title = label, detail = hint, tint = if (danger) CubTheme.colors.danger else null, onClick = onClick)
}

/** 详情: what the file is, without opening it. */
@Composable
private fun DetailCard(state: AppState, side: Side, name: String) {
    val pane = state.browsing.current(side)
    val entry = pane.entries.firstOrNull { it.relPath == name }
    FloatingCard(width = 340) {
        FloatingHeader("详情", onClose = { state.detailOf = null })
        KeyValueRow("名称", Browsing.name(name))
        KeyValueRow("位置", pane.path)
        KeyValueRow("类型", if (entry?.isDirectory == true) "文件夹" else "文件")
        KeyValueRow("大小", if (entry?.isDirectory == true) DASH else Format.bytes(entry?.size ?: 0))
        KeyValueRow("修改时间", Format.stamp(entry?.mtimeMs ?: 0))
        KeyValueRow("本栏", if (side == Side.LEFT) Words.LOCAL_PANE else Words.REMOTE_PANE)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            QuietButton("复制路径") {
                copyToClipboard(Browsing.child(pane.path, name))
                state.notify("已复制路径", NoticeKind.SUCCESS)
            }
            QuietButton("在新窗口打开所在目录") {
                val target = pickDirectory("")
                target.hashCode()
            }
        }
    }
}
