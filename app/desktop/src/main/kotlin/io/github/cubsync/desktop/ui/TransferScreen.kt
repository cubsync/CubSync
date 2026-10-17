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
            .onGloballyPositioned { state.screenOrigin = it.positionInWindow() },
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
    QuietButton("显示方式：${active.displayMode.label} ▾") { state.displayMenu = true }
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
            IconButton(IconPaths.CLOCK, size = 26, mark = 15) { state.historyMenu = side }
            IconButton(
                IconPaths.STAR,
                size = 26,
                mark = 15,
                tint = if (starred) colors.accent else null,
            ) { state.toggleFavourite(side) }
            IconButton(IconPaths.MORE, size = 26, mark = 16, stroke = 3.4f) {
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
    var rowOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .background(if (selected) colors.selected else Color.Transparent)
            // 规格 §3.5：单击＝进目录/勾选，**长按＝动作面板**。手机端照此办理，桌面端此前
            // 只有行尾那颗 ⋮，而规格要求的入口在人按的那一行本身。
            .combinedClickable(
                onClick = {
                    state.browsing.tap(side, entry)
                    state.touchBrowsing()
                },
                onLongClick = {
                    state.actionSheet = io.github.cubsync.desktop.ActionSheet(
                        side,
                        entry.relPath,
                        entry.isDirectory,
                    )
                },
            )
            // The screen's own hint says 右键理管, and until now nothing listened for it.
            .onGloballyPositioned { rowOrigin = it.positionInWindow() }
            .onPointerEvent(PointerEventType.Press) { event ->
                if (event.buttons.isSecondaryPressed) {
                    // Where the pointer is, not the middle of the window: a context menu
                    // that opens far from the thing it is about makes the person look for it.
                    state.actionSheetAt = rowOrigin + event.changes.first().position
                    state.actionSheet = io.github.cubsync.desktop.ActionSheet(side, entry.relPath, entry.isDirectory)
                }
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
                            state.actionSheet = io.github.cubsync.desktop.ActionSheet(side, entry.relPath, entry.isDirectory)
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
                                        state.actionSheet = io.github.cubsync.desktop.ActionSheet(
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
@Composable
private fun TransferBar(state: AppState) {
    val colors = CubTheme.colors
    val running = state.running
    val progress by state.browsing.transferProgress.collectAsState()

    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = colors.outline)
        Row(
            Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.width(230.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            progress != null -> "传送中"
                            running != null -> "${running.label}「${running.configName}」"
                            else -> "等待任务"
                        },
                        style = CubType.label,
                        color = colors.text,
                    )
                    Spacer(Modifier.width(8.dp))
                    Chip(
                        when {
                            progress != null -> if (progress?.paused == true) "已暂停" else "进行中"
                            running != null -> "${running.finishedPairs}/${running.pairCount} 对"
                            else -> DASH
                        },
                        tone = when {
                            progress != null -> colors.accent
                            running != null -> colors.accent
                            else -> null
                        },
                    )
                }
                Text(
                    when {
                        progress != null -> progressLine(progress!!)
                        running != null -> Format.duration(System.currentTimeMillis() - running.startedAtMs)
                        else -> "选中文件后点中间的 → / ← 发起传输"
                    },
                    style = CubType.meta,
                    color = colors.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(16.dp))
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(colors.surfaceSunken),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress?.fraction ?: 0f)
                        .clip(RoundedCornerShape(99.dp))
                        .background(colors.accent),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                if (progress == null) DASH else "${progress!!.percent}%",
                style = CubType.meta,
                color = colors.textDim,
                modifier = Modifier.width(48.dp),
            )
            if (progress != null) {
                QuietButton(if (progress!!.paused) "继续" else "暂停") {
                    if (progress!!.paused) state.browsing.resumeTransfer() else state.browsing.pauseTransfer()
                }
                QuietButton("取消", danger = true) { state.browsing.cancelTransfer() }
            } else if (running != null) {
                QuietButton("暂停 / 取消", danger = true) { state.cancelRun() }
            } else {
                // The prototype draws both buttons whether or not anything is moving; with
                // nothing in flight they are off rather than absent, so the strip does not
                // change width every time a copy starts.
                QuietButton("暂停", enabled = false) {}
                QuietButton("取消", danger = true, enabled = false) {}
            }
        }
    }
}

/** `已传 / 总量 · 速率`, the line the prototype puts under the name. */
private fun progressLine(progress: io.github.cubsync.service.TransferProgress): String =
    "${Format.bytes(progress.doneBytes)} / ${Format.bytes(progress.totalBytes)} · ${progress.totalFiles} 项"

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
    val history = state.historyMenu

    if (paneMenu == null && sheet == null && detail == null && !displayMenu && history == null) return

    Box(Modifier.fillMaxSize()) {
        Scrim {
            state.paneMenu = null
            state.actionSheet = null
            state.detailOf = null
            state.renaming = null
            state.displayMenu = false
            state.historyMenu = null
        }
        if (displayMenu) {
            // Under the button that opened it: the heading's 显示方式 sits at the top-right
            // of the screen, so the card hangs from the same corner. It used to be pushed
            // 112dp down for no reason a reader could see.
            Box(Modifier.fillMaxSize().padding(top = 6.dp, end = 14.dp), contentAlignment = Alignment.TopEnd) {
                DisplayModeMenuCard(state)
            }
        }
        if (history != null) {
            Box(Modifier.fillMaxSize().padding(top = 6.dp, end = 14.dp), contentAlignment = Alignment.TopEnd) {
                HistoryCard(state, history)
            }
        }
        Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
            when {
                paneMenu != null -> PaneMenuCard(state, paneMenu.side)
                detail != null -> DetailCard(state, detail.first, detail.second)
            }
        }
        if (sheet != null) {
            val at = state.actionSheetAt - state.screenOrigin
            Box(
                Modifier.offset {
                    androidx.compose.ui.unit.IntOffset(
                        at.x.toInt().coerceAtLeast(0),
                        at.y.toInt().coerceAtLeast(0),
                    )
                },
            ) {
                ActionSheetCard(state, sheet.side, sheet.name, sheet.isDirectory)
            }
        }
    }
}

/** 展开: the paths this pane has been to, newest first. */
@Composable
private fun HistoryCard(state: AppState, side: Side) {
    val colors = CubTheme.colors
    val recent = state.browsing.recent(side).filter { it != state.browsing.current(side).path }
    FloatingCard(width = 340) {
        FloatingHeader("展开 · ${if (side == Side.LEFT) Words.LOCAL_PANE else Words.REMOTE_PANE}") {
            state.historyMenu = null
        }
        Block("打开过的路径") {
            if (recent.isEmpty()) {
                Text("这个栏还没有打开过别的目录。", style = CubType.meta, color = colors.textFaint)
            }
            for (path in recent.take(10)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            state.openPane(side, path, state.browsing.current(side).backendId)
                            state.historyMenu = null
                        }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    Text(
                        path,
                        style = CubType.monoSmall,
                        color = colors.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
                        tint = if (active) colors.accent else colors.text,
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

        // §3.4: 排序, 文件夹优先, 作用范围 — and nothing else. The address has its own box
        // above the list and the display mode has the heading's menu.
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
            // Only for a row that lives on this machine: the far pane's path belongs to
            // the peer, and handing it to Explorer would open a folder that is not there.
            if (pane.backendId == io.github.cubsync.service.EndpointId.LOCAL) {
                QuietButton("打开所在目录") {
                    val target = if (entry?.isDirectory == true) Browsing.child(pane.path, name) else pane.path
                    if (!openDirectory(target)) state.notify("打不开 $target", NoticeKind.WARNING)
                }
            }
        }
    }
}
