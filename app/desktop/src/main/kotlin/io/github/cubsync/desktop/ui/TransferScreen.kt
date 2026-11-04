package io.github.cubsync.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
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
import io.github.cubsync.desktop.openDirectory
import io.github.cubsync.service.Browsing
import io.github.cubsync.service.Columns
import io.github.cubsync.service.CompareMark
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
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow

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
    // 对比 is a mode of the screen, not of one pane: one listing cannot compare with
    // itself, so either pane selecting it puts both of them in it.
    val compare = state.browsing.prefsOf(Side.LEFT).displayMode == DisplayMode.COMPARE ||
        state.browsing.prefsOf(Side.RIGHT).displayMode == DisplayMode.COMPARE
    val revision = state.settingsRevision + state.browserRevision
    val settings = state.liveSettings
    val direction = resolveDirection(settings.paneDirection, 1000f, if (revision >= 0) 600f else 600f)
    val sideBySide = direction == PaneDirection.SIDE_BY_SIDE

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { state.overlay.screenOrigin = it.positionInWindow() },
    ) {
    Column(Modifier.fillMaxSize()) {
        // The prototype's transfer screen has no sub-row and no standing display bar: the
        // three verbs are on `.chead .acts`, and 显示方式 opens a drop-down from there. Both
        // were added here and both are gone, because a screen with two ways to reach one
        // control is a screen with one control too many.
        HorizontalDivider(color = colors.outline)

        if (sideBySide) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                Pane(state, Side.LEFT, left, compare, Modifier.weight(1f))
                TransferArrow(state, left, right, busy, horizontal = true)
                Pane(state, Side.RIGHT, right, compare, Modifier.weight(1f))
            }
        } else {
            Column(Modifier.weight(1f).fillMaxWidth()) {
                Pane(state, Side.LEFT, left, compare, Modifier.weight(1f))
                TransferArrow(state, left, right, busy, horizontal = false)
                Pane(state, Side.RIGHT, right, compare, Modifier.weight(1f))
            }
        }

        TransferBar(state)
    }
        // Over the screen, not in it: as the last child of the column the overlay claimed
        // the rest of the height, so opening 显示方式 or a right-click panel squeezed both
        // panes to nothing and the files appeared to vanish.
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
    QuietButton("显示方式：${active.displayMode.label} ▾") { state.overlay.displayMenu = true }
    QuietButton(Words.NEW_FOLDER) { state.newFolder(state.liveSettings.activeSide) }
    QuietButton("同步…") { state.goTo(Section.SYNC) }
}

// --------------------------------------------------------------------- gestures

/**
 * 条目缩放的手势，原型用它们代替双指。
 *
 * 规格 §3.5 要求「双击列表＝缩放复位 100%」「Ctrl+滚轮＝连续缩放 0.7×–2.0×」。两句话
 * 之前都写在界面上，而两个手势都不存在 —— 界面在承诺它做不到的事。
 *
 * 放在这一层（列表外面的 Box），不放在行上：行的点击由行自己消费，所以只有点在空白处
 * 的双击才会到这儿，不会把「双击打开一个目录」吃掉。
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.itemScaleGestures(state: AppState): Modifier = this
    .onPointerEvent(PointerEventType.Scroll) { event ->
        if (!event.keyboardModifiers.isCtrlPressed) return@onPointerEvent
        val notches = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
        if (notches == 0f) return@onPointerEvent
        // 滚轮报的是格数不是比例，所以一格走一步面板的步长。
        state.changeSettings { settings ->
            settings.itemScale = ItemScale.clamp(
                settings.itemScale - (notches.coerceIn(-3f, 3f) * ItemScale.STEP).toInt(),
            )
        }
    }
    .pointerInput(Unit) {
        detectTapGestures(onDoubleTap = {
            state.changeSettings { it.itemScale = ItemScale.DEFAULT }
        })
    }

/** One pane: an address header, a list, and the ⋮ that belongs to that pane. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Pane(state: AppState, side: Side, pane: PaneState, compare: Boolean, modifier: Modifier) {
    val colors = CubTheme.colors
    val settings = state.liveSettings
    val active = settings.activeSide == side
    val selected = state.browsing.prefsOf(side)
    val scale = settings.itemScale / 100f

    Column(
        modifier
            .fillMaxHeight()
            .background(if (active) colors.accent.copy(alpha = 0.07f) else Color.Transparent)
            // Pressing anywhere in a pane makes it the active one, which is what the
            // heading's 显示方式 button says it acts on. A clickable on the container rather
            // than a raw pointer observer: the observer ran after the rows had consumed
            // their presses, so clicking a row never moved the focus and the two panes
            // could not be switched at all.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                if (!active) {
                    settings.activeSide = side
                    state.touchBrowsing()
                }
            },
    ) {
        PaneHeader(state, side, pane, active)

        Box(Modifier.weight(1f).fillMaxWidth().itemScaleGestures(state)) {
            when {
                pane.loading && pane.entries.isEmpty() ->
                    EmptyState("正在读取…")
                pane.error.isNotEmpty() ->
                    EmptyState("读不了这个文件夹", pane.error)
                // 对比 before the empty check: a pane with nothing of its own is not an
                // empty folder there, it is a folder where the other side's files will be
                // created — and those ghost rows are the whole answer.
                compare ->
                    ComparePane(state, side, pane, settings.rowDensity, scale)
                pane.entries.isEmpty() ->
                    EmptyState(Words.EMPTY_FOLDER, action = { QuietButton(Words.NEW_FOLDER) { state.newFolder(side) } })
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
            val starred = state.service.settings.isFavourite(pane.backendId, pane.path)
            // Tighter, and one more button: 展开 lists the paths this pane has been to, so a
            // folder three levels up is one click instead of three.
            IconButton(IconPaths.CLOCK, size = 26, mark = 15) { state.overlay.historyMenu = side }
            IconButton(
                IconPaths.STAR,
                size = 26,
                mark = 15,
                tint = if (starred) colors.accent else null,
            ) { state.toggleFavourite(side) }
            IconButton(IconPaths.MORE, size = 26, mark = 16, stroke = 3.4f) {
                state.overlay.paneMenu = io.github.cubsync.desktop.PaneMenu(side)
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
    // Every layer, always. A folded `⋯` that still hides three of them reads as "the
    // address has one parent", which is what it looked like when the default limit was
    // five segments; a row that runs out of width scrolls instead.
    val shown = Browsing.crumbs(pane.path)

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 10.dp, end = 8.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((index, crumb) in shown.withIndex()) {
            if (index > 0) Text(" › ", style = CubType.meta, color = colors.textFaint)
            Text(
                crumb.label,
                style = CubType.meta.copy(fontSize = 11.sp),
                color = if (index == shown.lastIndex) colors.text else colors.textDim,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { state.openPane(side, crumb.path, pane.backendId) }
                    .padding(horizontal = 2.dp),
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

/** One row of a listing: mark, icon, name, and the two metadata columns. */
@OptIn(ExperimentalComposeUiApi::class)
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
    val multi = state.browsing.multiSelect.collectAsState().value
    var rowOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .background(if (selected) colors.selected else Color.Transparent)
            // 桌面的点击语义跟着文件管理器走：单击＝选中（勾选）这一行，双击＝进目录。
            // 多选模式下单击＝切换勾选、不进入，行首出现勾选框。长按/右键＝动作面板。
            .pointerInput(entry.relPath, multi) {
                detectTapGestures(
                    onTap = {
                        state.browsing.tap(side, entry)
                        state.touchBrowsing()
                    },
                    onDoubleTap = {
                        if (entry.isDirectory) state.browsing.enter(side, Browsing.name(entry.relPath))
                    },
                    onLongPress = {
                        // Without a position the sheet opens at 0,0 — the top-left corner of
                        // the window, nowhere near the row that was pressed, which reads as
                        // "nothing happened". Same offset for a long press and a right click.
                        state.overlay.actionSheetAt = rowOrigin + androidx.compose.ui.geometry.Offset(24f, 28f)
                        state.overlay.actionSheet = io.github.cubsync.desktop.ActionSheet(
                            side,
                            entry.relPath,
                            entry.isDirectory,
                        )
                    },
                )
            }
            // The screen's own hint says 右键理管, and until now nothing listened for it.
            .onGloballyPositioned { rowOrigin = it.positionInWindow() }
            .onPointerEvent(PointerEventType.Press) { event ->
                if (event.buttons.isSecondaryPressed) {
                    // Where the pointer is, not the middle of the window: a context menu
                    // that opens far from the thing it is about makes the person look for it.
                    state.overlay.actionSheetAt = rowOrigin + event.changes.first().position
                    state.overlay.actionSheet = io.github.cubsync.desktop.ActionSheet(side, entry.relPath, entry.isDirectory)
                }
            }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 多选模式才出现勾选框（原型只在差异档画了它；浏览行出现它是为了让勾选可见，
        // 记在决策记录里）。点击只切换勾选，不选中、不进入。
        if (multi) {
            Box(
                Modifier
                    .size(15.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.5.dp, if (selected) colors.accent else colors.textFaint, RoundedCornerShape(4.dp))
                    .background(if (selected) colors.accent else Color.Transparent)
                    .clickable { state.browsing.toggle(side, entry.relPath) },
                contentAlignment = Alignment.Center,
            ) {
                if (selected) CubIcon(io.github.cubsync.service.IconPaths.CHECK, size = 11, tint = Color.White)
            }
            Spacer(Modifier.width(8.dp))
        }
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
    }
}

@OptIn(ExperimentalComposeUiApi::class)
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
                        state.browsing.tap(side, entry)
                        state.touchBrowsing()
                    }
                    .onPointerEvent(PointerEventType.Press) { event ->
                        if (event.buttons.isSecondaryPressed) {
                            state.overlay.actionSheet = io.github.cubsync.desktop.ActionSheet(side, entry.relPath, entry.isDirectory)
                        }
                    }
                    .padding(8.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                CubIcon(
                    fileIcon(Browsing.name(entry.relPath), entry.isDirectory),
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
 * 对比: both panes keep their own columns and align by name.
 *
 * The prototype draws this as two panes whose rows line up, with the difference as a
 * coloured edge, and a dimmed ghost row where the other side has something this one does
 * not. An earlier version replaced the two panes with one shared table and a 差异
 * column — which reads like a report rather than like the browser, and put the two sides
 * side by side inside one column, so neither pane could still be scrolled or entered.
 */
@Composable
private fun ComparePane(state: AppState, side: Side, pane: PaneState, density: Int, scale: Float) {
    val colors = CubTheme.colors
    val lines = state.browsing.compareLines(side)
    val rowHeight = (ROW_BASE_DP * density / 100f * scale).dp
    val fontSize = (12.5f * scale).sp

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(colors.surfaceRaised).padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("名称", style = CubType.meta, color = colors.textFaint, modifier = Modifier.weight(1f))
            Text("修改时间", style = CubType.meta, color = colors.textFaint, modifier = Modifier.width(120.dp))
            Text("大小", style = CubType.meta, color = colors.textFaint, modifier = Modifier.width(72.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(lines, key = { it.name }) { line ->
                val markColor = when (line.mark) {
                    CompareMark.SAME -> Color.Transparent
                    CompareMark.ONLY_HERE -> colors.success
                    CompareMark.DIFFERS -> colors.conflict
                    CompareMark.MISSING -> Color.Transparent
                }
                val entry = line.entry
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .drawBehind {
                            if (markColor.alpha > 0f) {
                                drawRect(markColor, size = Size(3.dp.toPx(), size.height))
                            }
                        }
                        .then(
                            if (entry == null) {
                                Modifier
                            } else {
                                Modifier.combinedClickable(
                                    onClick = {
                                        state.browsing.tap(side, entry)
                                        state.touchBrowsing()
                                    },
                                    onLongClick = {
                                        state.overlay.actionSheet = io.github.cubsync.desktop.ActionSheet(
                                            side,
                                            line.name,
                                            entry.isDirectory,
                                        )
                                    },
                                )
                            },
                        )
                        .padding(start = 10.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CubIcon(
                        fileIcon(line.name, line.shown?.isDirectory == true),
                        size = 15,
                        tint = if (line.ghost) colors.outlineStrong else colors.textFaint,
                        modifier = Modifier.width(20.dp),
                    )
                    Text(
                        line.name,
                        style = CubType.body.copy(fontSize = fontSize),
                        color = when {
                            line.ghost -> colors.textFaint
                            line.entry?.isDirectory == true -> colors.text
                            else -> colors.textDim
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        when {
                            line.ghost -> "对侧缺失 · 将创建"
                            line.entry?.isDirectory == true -> DASH
                            else -> Format.stamp(line.entry?.mtimeMs ?: 0)
                        },
                        style = CubType.meta,
                        color = if (line.ghost) colors.warning else colors.textFaint,
                        maxLines = 1,
                        modifier = Modifier.width(120.dp),
                    )
                    Text(
                        if (line.ghost || line.entry?.isDirectory == true) DASH else Format.bytes(line.entry?.size ?: 0),
                        style = CubType.meta,
                        color = colors.textFaint,
                        maxLines = 1,
                        modifier = Modifier.width(72.dp),
                    )
                }
            }
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
