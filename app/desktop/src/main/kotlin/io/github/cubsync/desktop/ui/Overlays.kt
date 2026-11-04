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

// --------------------------------------------------------------------- overlays

/**
 * The pop-ups: the pane menu, the row action sheet, the detail card.
 *
 * They are drawn here rather than inside a pane so that they are not clipped by the pane
 * they belong to, and so that clicking outside them closes them the same way everywhere.
 */
@Composable
fun Overlays(state: AppState) {
    val paneMenu = state.overlay.paneMenu
    val sheet = state.overlay.actionSheet
    val detail = state.overlay.detailOf
    val displayMenu = state.overlay.displayMenu
    val history = state.overlay.historyMenu

    if (paneMenu == null && sheet == null && detail == null && !displayMenu && history == null) return

    Box(Modifier.fillMaxSize()) {
        Scrim {
            state.overlay.paneMenu = null
            state.overlay.actionSheet = null
            state.overlay.detailOf = null
            state.overlay.renaming = null
            state.overlay.displayMenu = false
            state.overlay.historyMenu = null
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
            val at = state.overlay.actionSheetAt - state.overlay.screenOrigin
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
            state.overlay.historyMenu = null
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
                            state.overlay.historyMenu = null
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
        FloatingHeader("显示方式 · 作用于$paneName（高亮那栏）") { state.overlay.displayMenu = false }

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
        FloatingHeader(if (side == Side.LEFT) Words.LOCAL_PANE else Words.REMOTE_PANE, onClose = { state.overlay.paneMenu = null })

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

        Block("选择") {
            // 桌面此前没有开多选的地方：`BrowserSession.tap` 在非多选时把文件夹当"进入"，
            // 于是这一端永远勾不上一个文件夹。手机的多选在「更多」里，这里放进每栏的 ⋮。
            val multi = state.browsing.multiSelect.collectAsState().value
            Row(verticalAlignment = Alignment.CenterVertically) {
                Toggle(multi) { state.browsing.toggleMultiSelect() }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (multi) "单击行＝勾选，文件夹也能勾" else "单击目录＝进入，单击文件＝勾选",
                    style = CubType.meta,
                    color = colors.textFaint,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                QuietButton("全选这一栏") { state.browsing.selectAll(side) }
                QuietButton("反选") { state.browsing.invertSelection(side) }
                QuietButton("清空勾选") { state.browsing.clearSelection(side) }
            }
            if (multi) {
                Row(modifier = Modifier.padding(top = 6.dp)) {
                    QuietButton("退出选择模式") { state.browsing.exitMultiSelect() }
                }
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
        FloatingHeader(Browsing.name(name), onClose = { state.overlay.actionSheet = null; state.overlay.renaming = null })

        if (state.overlay.renaming == name) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Field(renameTo, { renameTo = it }, Modifier.weight(1f), onSubmit = { state.rename(side, name, renameTo) })
                Spacer(Modifier.width(6.dp))
                PrimaryButton("确定") { state.rename(side, name, renameTo) }
            }
            Spacer(Modifier.height(6.dp))
        }

        // 菜单项不挂说明：说明在 hover 到某一项时浮在卡片底部，像系统右键菜单的
        // 状态栏那样。要解释的看 hover，熟练的只看动作名。
        var hoverHint by remember { mutableStateOf<String?>(null) }

        if (state.browsing.current(side).isSelected(name)) {
            Action("取消选择", "从勾选里拿掉这一项", onHover = { hoverHint = it }) {
                state.browsing.toggle(side, name)
                state.overlay.actionSheet = null
            }
        } else {
            Action("选择", "勾上这一项并进入多选", onHover = { hoverHint = it }) {
                if (!state.browsing.multiSelect.value) state.browsing.toggleMultiSelect()
                state.browsing.toggle(side, name)
                state.overlay.actionSheet = null
            }
        }
        Action("传送 ▸ 对侧", "把这一项送到另一栏", onHover = { hoverHint = it }) {
            state.browsing.select(side, listOf(name))
            state.transfer(side)
            state.overlay.actionSheet = null
        }
        Action("收藏", "把当前目录加进收藏夹", onHover = { hoverHint = it }) { state.toggleFavourite(side); state.overlay.actionSheet = null }
        Action("过滤", "按名字或后缀排除", onHover = { hoverHint = it }) {
            state.notify("过滤器在同步配置的「过滤」里设置，这样两侧规则一致", NoticeKind.INFO)
            state.overlay.actionSheet = null
        }
        Action("重命名", "改成别的名字", onHover = { hoverHint = it }) { state.overlay.renaming = name }
        Action("详情", "大小、时间、类型", onHover = { hoverHint = it }) { state.overlay.detailOf = side to name }
        Action(if (isDirectory) "删除目录" else "删除", "进${Words.TRASH}，不是直接销毁", danger = true, onHover = { hoverHint = it }) {
            state.browsing.select(side, listOf(name))
            state.deleteSelection(side)
            state.overlay.actionSheet = null
        }

        hoverHint?.let { hint ->
            Spacer(Modifier.height(4.dp))
            Text(hint, style = CubType.meta, color = colors.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun Action(
    label: String,
    hint: String,
    danger: Boolean = false,
    onHover: (String?) -> Unit,
    onClick: () -> Unit,
) {
    Box(
        Modifier.onPointerEvent(PointerEventType.Enter) { onHover(hint) }
            .onPointerEvent(PointerEventType.Exit) { onHover(null) },
    ) {
        ListLine(title = label, tint = if (danger) CubTheme.colors.danger else null, onClick = onClick)
    }
}

/** 详情: what the file is, without opening it. */
@Composable
private fun DetailCard(state: AppState, side: Side, name: String) {
    val pane = state.browsing.current(side)
    val entry = pane.entries.firstOrNull { it.relPath == name }
    FloatingCard(width = 340) {
        FloatingHeader("详情", onClose = { state.overlay.detailOf = null })
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
