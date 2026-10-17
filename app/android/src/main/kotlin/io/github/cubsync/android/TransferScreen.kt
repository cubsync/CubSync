package io.github.cubsync.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.cubsync.core.spi.FileMeta
import io.github.cubsync.service.Browsing
import io.github.cubsync.service.Columns
import io.github.cubsync.service.CompareMark
import io.github.cubsync.service.Density
import io.github.cubsync.service.DisplayMode
import io.github.cubsync.service.EndpointId
import io.github.cubsync.service.Section
import io.github.cubsync.service.Format
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.cubsync.service.viewIcon
import io.github.cubsync.service.IconPaths
import io.github.cubsync.service.fileIcon
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
    // 对比 needs both listings: one pane cannot compare anything with itself.
    val compare = session.prefsOf(Side.LEFT).displayMode == DisplayMode.COMPARE ||
        session.prefsOf(Side.RIGHT).displayMode == DisplayMode.COMPARE
    val solo = when {
        compare -> null
        state.showSoloLocal -> Side.LEFT
        state.showSoloRemote -> Side.RIGHT
        else -> null
    }

    // The real size of the screen, not a guess: 分栏方向 said 自动 while the code compared
    // 380 against 560 — two numbers that never change, so a phone held sideways still
    // stacked the panes.
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val widthDp = maxWidth.value
    val heightDp = maxHeight.value
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

        val paneDirection = resolveDirection(settings.paneDirection, widthDp, heightDp)

        when {
            solo != null -> Pane(state, solo, if (solo == Side.LEFT) left else right, compare, Modifier.weight(1f))
            paneDirection == PaneDirection.SIDE_BY_SIDE ->
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Pane(state, Side.LEFT, left, compare, Modifier.weight(1f))
                    Column(Modifier.width(1.dp).fillMaxHeight().background(colors.outline)) {}
                    Pane(state, Side.RIGHT, right, compare, Modifier.weight(1f))
                }

            else -> Column(Modifier.weight(1f).fillMaxWidth()) {
                Pane(state, Side.LEFT, left, compare, Modifier.weight(1f))
                Pane(state, Side.RIGHT, right, compare, Modifier.weight(1f))
            }
        }

        // The prototype's phone has no strip between the panes: a thumb does not drag a
        // button that is 20px wide, so a transfer starts from the row's action panel or
        // from the selection bar. The desktop keeps its two arrows; this end does not.
        ActionBar(state)
    }
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
        // Every mark on this row is a path and every target is the same 38dp. The row used
        // to mix 34 and 38 and to spell 单双栏 with \u25a4 \u25e7 \u25e8, which are characters
        // a font may or may not have — the same failure that once emptied the tab bar.
        IconButton(IconPaths.MENU, size = 38) { state.drawerOpen = true }

        Row(Modifier.padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                IconPaths.PANE_BOTH,
                size = 38,
                mark = 18,
                selected = state.showSoloLocal.not() && state.showSoloRemote.not(),
            ) {
                state.showSoloLocal = false
                state.showSoloRemote = false
            }
            IconButton(IconPaths.PANE_LOCAL, size = 38, mark = 18, selected = state.showSoloLocal) {
                state.showSoloLocal = true
                state.showSoloRemote = false
                settings.activeSide = Side.LEFT
            }
            IconButton(IconPaths.PANE_REMOTE, size = 38, mark = 18, selected = state.showSoloRemote) {
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
            CubIcon(viewIcon(prefs.displayMode), size = 16, tint = colors.text)
            Spacer(Modifier.width(7.dp))
            // The prototype's pill reads 显示方式：详细 — the words, not only the mode.
            Text("显示方式 · ${prefs.displayMode.label}", style = CubType.label, color = colors.text, maxLines = 1)
            Spacer(Modifier.width(5.dp))
            CubIcon(
                if (settings.displayBarOpen) IconPaths.CHEVRON_DOWN else IconPaths.CHEVRON_RIGHT,
                size = 14,
                tint = colors.textDim,
            )
        }

        if (selected > 0) {
            Chip("已选 $selected", tone = colors.accent, modifier = Modifier.padding(horizontal = 4.dp))
        }
        IconButton(IconPaths.MORE, size = 38, mark = 18, stroke = 3.4f) { state.moreSheet = true }
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
            // The prototype's `.a-viewbar` shows five marks, not five words.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (mode in DisplayMode.entries) {
                    val active = prefs.displayMode == mode
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(if (active) colors.accent else colors.surfaceSunken)
                            .clickableCustom { state.setDisplayMode(side, mode) },
                        contentAlignment = Alignment.Center,
                    ) {
                        CubIcon(
                            viewIcon(mode),
                            size = 17,
                            tint = if (active) Color.White else colors.textDim,
                        )
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
            Spacer(Modifier.width(8.dp))
            Text("双指缩放可调列数与字号", style = CubType.meta, color = colors.textFaint)
        }
    }
}


/**
 * 条目缩放的手势：双指捏合连续调，双击复位 100%。
 *
 * 只在**两指**时接管事件。单指的拖动留给列表滚动、单击留给行 —— 一个无条件的
 * transform 检测器会把这两件事一起吃掉，那是比缺少手势更糟的 bug。
 */
@Composable
private fun Modifier.itemScaleGestures(state: AndroidAppState): Modifier = this
    .pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.none { it.pressed }) break
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                val twoFingers = event.changes.count { it.pressed } >= 2
                if (twoFingers && (zoom != 1f || pan != Offset.Zero)) {
                    event.changes.forEach { it.consume() }
                    if (zoom != 1f) {
                        state.changeSettings { it.itemScale = ItemScale.zoomBy(it.itemScale, zoom) }
                    }
                }
            }
        }
    }
    .pointerInput(Unit) {
        detectTapGestures(onDoubleTap = {
            state.changeSettings { it.itemScale = ItemScale.DEFAULT }
        })
    }

// ------------------------------------------------------------------------ pane

@Composable
private fun Pane(state: AndroidAppState, side: Side, pane: PaneState, compare: Boolean, modifier: Modifier) {
    val colors = CubTheme.colors
    val settings = state.liveSettings
    val active = settings.activeSide == side
    val scale = settings.itemScale / 100f

    Column(
        modifier
            .fillMaxHeight()
            .background(if (active) colors.accent.copy(alpha = 0.05f) else Color.Transparent)
            // Tap anywhere in a pane to focus it: the sub-row's 显示方式 acts on the focused
            // pane, and a focus you cannot change by touching the pane is a focus that lies.
            // A clickable on the container rather than a pointer observer — the observer ran
            // after the rows had consumed their presses, so rows never moved the focus.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                if (settings.activeSide != side) {
                    settings.activeSide = side
                    state.touch()
                }
            },
    ) {
        PaneHeader(state, side, pane, active)
        Box(Modifier.weight(1f).fillMaxWidth().itemScaleGestures(state)) {
            when {
                // 远端 with no peer is not an empty folder: it is a pane waiting for a
                // connection, and it used to show this phone's own disk instead — which is
                // how a path from the other machine ended up being asked of the wrong side.
                side == Side.RIGHT && pane.backendId == EndpointId.LOCAL &&
                    pane.path.isBlank() && state.peers.isEmpty() ->
                    EmptyState(
                        "还没有连接远端",
                        "在「连接」里填对面显示的地址，或者让对面先连上这台机器",
                        action = { QuietButton("去连接") { state.go(Section.CONNECT) } },
                    )
                pane.error.isNotEmpty() -> EmptyState("读不了这个文件夹", pane.error)
                // 对比 draws the other side's rows as ghosts, so a pane that has nothing of
                // its own is not an empty folder — it is a folder where everything will be
                // created, which is exactly what the mode is for.
                compare -> PaneList(state, side, pane, scale, compare = true)
                pane.entries.isEmpty() && !pane.loading ->
                    EmptyState(Words.EMPTY_FOLDER, action = { QuietButton(Words.NEW_FOLDER) { state.newFolder(side) } })
                else -> PaneList(state, side, pane, scale, compare = compare)
            }
        }
    }
}

/**
 * 栏头＝本栏地址.
 *
 * One line when there is room, and the number of lines the settings say when there is not
 * — the header grows to fit rather than squeezing the list. The gestures are the design's
 * five: tap a layer to go there, tap the ⋯ to unfold the middle, tap the blank part to type
 * an address, hold the blank part to copy it, tap ← to go up, hold ← to go to the root.
 */
@Composable
private fun PaneHeader(state: AndroidAppState, side: Side, pane: PaneState, active: Boolean) {
    val colors = CubTheme.colors
    val settings = state.liveSettings
    val copy = rememberClipboard()
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
            // ← takes two gestures: one step up, and the whole way back to this pane's root.
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = { state.browsing.up(side) },
                        onLongClick = { state.browsing.toRoot(side) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                CubIcon(IconPaths.BACK, size = 18, tint = colors.textDim)
            }

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
                // Every layer. The old limit folded three of them into one `⋯` even when
                // the header had room, so what looked like "only the parent" was really
                // "the parent and an ellipsis" — and a layer in the ellipsis cannot be
                // tapped, which is half the navigation the design promises.
                val crumbs = Browsing.crumbs(pane.path)
                Box(
                    Modifier
                        .weight(1f)
                        .pointerInput(pane.path, expanded, lines) {
                            detectTapGestures(
                                onTap = { editing = true },
                                onLongPress = {
                                    copy(pane.path)
                                    state.notify("已复制 ${pane.path}", NoticeKind.INFO)
                                },
                            )
                        }
                        .padding(horizontal = 2.dp, vertical = 4.dp),
                ) {
                    Column {
                        run {
                            // Scrolls rather than folds: a narrow two-pane header keeps
                            // every layer reachable, and a wide one shows them all.
                            val crumbScroll = rememberScrollState()
                            // Parked at the right end: the folder being shown is the last
                            // crumb, and a row that starts at the anchor cuts it off.
                            androidx.compose.runtime.LaunchedEffect(pane.path, crumbScroll.maxValue) {
                                crumbScroll.scrollTo(crumbScroll.maxValue)
                            }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(crumbScroll),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                for ((index, crumb) in crumbs.withIndex()) {
                                    if (index > 0) Text(" › ", style = CubType.meta, color = colors.textFaint)
                                    Text(
                                        crumb.label,
                                        style = CubType.meta.copy(fontSize = 11.5.sp),
                                        color = if (index == crumbs.lastIndex) colors.text else colors.textDim,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .clickableCustom {
                                                state.openPane(side, crumb.path, pane.backendId)
                                            }
                                            .padding(horizontal = 1.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            IconButton(
                if (settings.isFavourite(pane.backendId, pane.path)) IconPaths.STAR else IconPaths.STAR,
                size = 34,
                mark = 17,
                tint = if (settings.isFavourite(pane.backendId, pane.path)) colors.accent else colors.textDim,
            ) { state.toggleFavourite(side) }
            IconButton(IconPaths.MORE, size = 34, mark = 17, stroke = 3.4f) {
                settings.activeSide = side
                state.paneMenu = side
            }
        }
    }
}

/** `…/parent/leaf`, the three-segment tail a multi-line header shows for an ancestor. */
private fun tailPath(path: String): String {
    val parts = path.split('/', '\\').filter { it.isNotEmpty() }
    if (parts.size <= 2) return path
    return "…" + parts.takeLast(2).joinToString("/") { it }
}

/** The list, in whichever of the five modes is in force. */
@Composable
private fun PaneList(state: AndroidAppState, side: Side, pane: PaneState, scale: Float, compare: Boolean) {
    val colors = CubTheme.colors
    val settings = state.liveSettings
    val prefs = state.browsing.prefsOf(side)
    val mode = prefs.displayMode

    if (compare) {
        ComparePane(state, side, scale)
        return
    }

    if (mode.isGrid) {
        // 卡片 / 缩略 are grids: the column count in the settings is the whole point of
        // having those two modes, and a full-width tile per row made the setting a lie.
        LazyVerticalGrid(
            columns = GridCells.Fixed(settings.columns.coerceAtLeast(1)),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(6.dp),
        ) {
            items(pane.entries, key = { it.relPath }) { entry ->
                GridTile(state, side, pane, entry, mode, scale)
            }
        }
        return
    }

    val base = if (mode == DisplayMode.COMPACT) 48 else 56
    val rowHeight = (base * settings.rowDensity / 100f * scale).dp
    val fontSize = (13.5f * scale).sp

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 2.dp)) {
        items(pane.entries, key = { it.relPath }) { entry ->
            val selected = pane.isSelected(entry.relPath)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    // `.a-row`: a tap enters a folder or ticks a file, a long press opens
                    // the action panel. The per-row ⋮ this used to carry is gone — the
                    // prototype has no such button on a phone row, and a ⋮ beside every
                    // name is a target that gets in the way of the name.
                    .combinedClickable(
                        onClick = {
                            state.browsing.tap(side, entry)
                            state.touch()
                        },
                        onLongClick = {
                            state.actionSheet = ActionSheet(side, entry.relPath, entry.isDirectory)
                        },
                    )
                    .background(if (selected) colors.accentContainer else Color.Transparent)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size((40 * scale).dp)
                        .clip(RoundedCornerShape((11 * scale).dp))
                        .background(colors.accentContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    CubIcon(
                        fileIcon(Browsing.name(entry.relPath), entry.isDirectory),
                        size = (20 * scale).toInt(),
                        tint = colors.accent,
                    )
                }
                Spacer(Modifier.width((10 * scale).dp))
                if (mode == DisplayMode.COMPACT) {
                    // 紧凑 is one line: the size sits beside the name instead of under it.
                    // Otherwise it was the same screen as 详细 with a 8dp difference, which
                    // is what "every mode looks the same" means.
                    Text(
                        Browsing.name(entry.relPath),
                        style = CubType.body.copy(fontSize = fontSize),
                        color = if (entry.isDirectory) colors.text else colors.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (settings.showMetadata && !entry.isDirectory) {
                        Text(Format.bytes(entry.size), style = CubType.meta, color = colors.textFaint, maxLines = 1)
                    }
                } else {
                    Column(Modifier.weight(1f)) {
                        Text(
                            Browsing.name(entry.relPath),
                            style = CubType.body.copy(fontSize = fontSize),
                            color = if (entry.isDirectory) colors.text else colors.textDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (settings.showMetadata) {
                            Text(
                                if (entry.isDirectory) DASH else "${Format.bytes(entry.size)} · ${Format.stamp(entry.mtimeMs)}",
                                style = CubType.meta,
                                color = colors.textFaint,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 卡片 / 缩略: a tile in the grid, with the column count the settings carry. */
@Composable
private fun GridTile(
    state: AndroidAppState,
    side: Side,
    pane: PaneState,
    entry: FileMeta,
    mode: DisplayMode,
    scale: Float,
) {
    val colors = CubTheme.colors
    val selected = pane.isSelected(entry.relPath)
    val thumb = mode == DisplayMode.THUMBNAIL
    Column(
        Modifier
            .fillMaxWidth()
            .padding(4.dp)
            .height(((if (thumb) 112 else 88) * scale).dp)
            .clip(Shape11)
            .background(if (selected) colors.accentContainer else colors.surfaceRaised)
            .border(1.dp, if (selected) colors.accent else colors.outline, Shape11)
            .clickableCustom {
                state.browsing.tap(side, entry)
                state.touch()
            }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        CubIcon(
            fileIcon(Browsing.name(entry.relPath), entry.isDirectory),
            size = ((if (thumb) 30 else 22) * scale).toInt(),
            tint = colors.accent,
        )
        Column {
            Text(
                Browsing.name(entry.relPath),
                style = CubType.meta.copy(fontSize = ((if (thumb) 11.5f else 12.5f) * scale).sp),
                color = colors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!entry.isDirectory && !thumb) {
                Text(Format.bytes(entry.size), style = CubType.meta, color = colors.textFaint, maxLines = 1)
            }
        }
    }
}

/**
 * 对比: both panes stay two panes, aligned by name.
 *
 * The prototype marks the difference with a coloured edge and draws a dimmed ghost row
 * where the other side has something this one does not — one table with a 差异 column
 * would read like a report, and would stop both panes being panes.
 */
@Composable
private fun ComparePane(state: AndroidAppState, side: Side, scale: Float) {
    val colors = CubTheme.colors
    val settings = state.liveSettings
    val lines = state.browsing.compareLines(side)
    val rowHeight = (56 * settings.rowDensity / 100f * scale).dp

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 2.dp)) {
        items(lines, key = { it.name }) { line ->
            val entry = line.entry
            val mark = when (line.mark) {
                CompareMark.SAME -> Color.Transparent
                CompareMark.ONLY_HERE -> colors.success
                CompareMark.DIFFERS -> colors.conflict
                CompareMark.MISSING -> Color.Transparent
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .drawBehind {
                        if (mark.alpha > 0f) drawRect(mark, size = Size(3.dp.toPx(), size.height))
                    }
                    .then(
                        if (entry == null) {
                            Modifier
                        } else {
                            Modifier.combinedClickable(
                                onClick = {
                                    state.browsing.tap(side, entry)
                                    state.touch()
                                },
                                onLongClick = {
                                    state.actionSheet = ActionSheet(side, line.name, entry.isDirectory)
                                },
                            )
                        },
                    )
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size((36 * scale).dp)
                        .clip(RoundedCornerShape((11 * scale).dp))
                        .background(if (line.ghost) Color.Transparent else colors.accentContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    CubIcon(
                        fileIcon(line.name, line.shown?.isDirectory == true),
                        size = (18 * scale).toInt(),
                        tint = if (line.ghost) colors.outlineStrong else colors.accent,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        line.name,
                        style = CubType.body,
                        color = when {
                            line.ghost -> colors.textFaint
                            entry?.isDirectory == true -> colors.text
                            else -> colors.textDim
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when {
                            line.ghost -> "对侧缺失 · 将创建"
                            entry?.isDirectory == true -> DASH
                            else -> "${Format.bytes(entry?.size ?: 0)} · ${Format.stamp(entry?.mtimeMs ?: 0)}"
                        },
                        style = CubType.meta,
                        color = if (line.ghost) colors.warning else colors.textFaint,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ action bar

/** The bar that appears under a marked row: the two things you would want next. */
@Composable
private fun ActionBar(state: AndroidAppState) {
    val colors = CubTheme.colors
    val left = state.browsing.current(Side.LEFT)
    val right = state.browsing.current(Side.RIGHT)
    val selected = left.selection.size + right.selection.size
    val progress by state.browsing.transferProgress.collectAsState()

    // A copy in flight takes the strip: a thumb that started two gigabytes needs to see
    // that it started, and the selection it came from is no longer the question.
    val running = progress
    if (running != null) {
        Column {
            HorizontalDivider(color = colors.outline)
            Column(Modifier.fillMaxWidth().background(colors.surfaceRaised).padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (running.paused) "已暂停" else "传送中",
                        style = CubType.label,
                        color = colors.text,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${Format.bytes(running.doneBytes)} / ${Format.bytes(running.totalBytes)}",
                        style = CubType.meta,
                        color = colors.textFaint,
                        modifier = Modifier.weight(1f),
                    )
                    Text("${running.percent}%", style = CubType.meta, color = colors.textDim)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(5.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(colors.surfaceSunken),
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(running.fraction)
                                .clip(RoundedCornerShape(99.dp))
                                .background(colors.accent),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    QuietButton(if (running.paused) "继续" else "暂停") {
                        if (running.paused) state.browsing.resumeTransfer() else state.browsing.pauseTransfer()
                    }
                    QuietButton("取消", danger = true) { state.browsing.cancelTransfer() }
                }
            }
        }
        return
    }

    if (selected == 0) return
    val from = if (left.selection.isNotEmpty()) Side.LEFT else Side.RIGHT
    val target = if (from == Side.LEFT) Words.REMOTE else Words.LOCAL

    Column {
        HorizontalDivider(color = colors.outline)
        Row(
            Modifier.fillMaxWidth().background(colors.surfaceRaised).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("已选 $selected 项", style = CubType.label, color = colors.textDim)
            Spacer(Modifier.weight(1f))
            QuietButton("传送 ▸ $target") { state.transfer(from) }
            IconButton(IconPaths.CLOSE, size = 38, mark = 16) {
                state.browsing.clearAllSelections()
                state.touch()
            }
        }
    }
}

// ---------------------------------------------------------------------- sheets

/**
 * 副行最右的 ⋮: the six things the prototype puts behind it.
 *
 * Deliberately not the per-pane panel: that one is 排序与作用范围, and one mark that opens
 * two different lists depending on where it was tapped is the confusion this pair exists
 * to avoid.
 */
@Composable
fun MoreSheet(state: AndroidAppState) {
    val colors = CubTheme.colors
    val settings = state.liveSettings
    val side = settings.activeSide
    val pane = state.browsing.current(side)
    val multi = state.browsing.multiSelect.collectAsState().value
    val close = { state.moreSheet = false }

    BottomSheet(onClose = { state.moreSheet = false }) {
        SheetHeader("更多 · ${if (side == Side.LEFT) Words.LOCAL_PANE else Words.REMOTE_PANE}") { state.moreSheet = false }

        ListRow("新建文件夹", onClick = {
            state.newFolder(side)
            close()
        })
        ListRow(
            "多选",
            if (multi) "已开启：单击行＝勾选，不再进入" else "单击行＝勾选，不再进入目录",
            tint = if (multi) colors.accent else null,
            onClick = {
                state.toggleMultiSelect()
                close()
            },
        )
        ListRow("收藏当前目录", "收藏夹分组", onClick = {
            state.toggleFavourite(side)
            close()
        })
        ListRow(
            "收藏列表",
            "${settings.favourites.size} 项",
            onClick = {
                state.moreSheet = false
                state.drawerOpen = true
            },
        )
        ListRow("放大条目", "${settings.itemScale}%", onClick = {
            state.changeSettings { it.itemScale = ItemScale.clamp(it.itemScale + ItemScale.STEP) }
            close()
        })
        ListRow("缩小条目", onClick = {
            state.changeSettings { it.itemScale = ItemScale.clamp(it.itemScale - ItemScale.STEP) }
            close()
        })
        Spacer(Modifier.height(6.dp))
        Text(
            pane.path,
            style = CubType.meta,
            color = colors.textFaint,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
        )
    }
}

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
