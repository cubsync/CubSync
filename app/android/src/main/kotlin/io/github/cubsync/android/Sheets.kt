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
import io.github.cubsync.service.ActionSheet
import io.github.cubsync.service.Browsing
import io.github.cubsync.service.Columns
import io.github.cubsync.service.CompareMark
import io.github.cubsync.service.Density
import io.github.cubsync.service.DisplayMode
import io.github.cubsync.service.EndpointId
import io.github.cubsync.service.Section
import io.github.cubsync.service.Format
import io.github.cubsync.service.NoticeKind
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
