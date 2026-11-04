package io.github.cubsync.service.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import io.github.cubsync.service.ActionSheet
import io.github.cubsync.service.Notice
import io.github.cubsync.service.PaneMenu
import io.github.cubsync.service.Side

/**
 * 弹层/覆盖层的界面状态：通知、抽屉、菜单、动作面板、重命名、详情卡。
 * 之前摊在 AppState，抽出来让"正在弹什么"这一域有自己的名字。
 */
class OverlayState {
    var notice by mutableStateOf<Notice?>(null)
    var drawerOpen by mutableStateOf(false)
    var displayBarOpen by mutableStateOf(false)
    var paneMenu by mutableStateOf<PaneMenu?>(null)
    var displayMenu by mutableStateOf(false)
    var historyMenu by mutableStateOf<Side?>(null)
    var actionSheet by mutableStateOf<ActionSheet?>(null)
    var actionSheetAt by mutableStateOf(Offset.Zero)
    var screenOrigin by mutableStateOf(Offset.Zero)
    var editingAddress by mutableStateOf<Side?>(null)
    var renaming by mutableStateOf<String?>(null)
    var detailOf by mutableStateOf<Pair<Side, String>?>(null)
    var nameCounter by mutableStateOf(0)
}
