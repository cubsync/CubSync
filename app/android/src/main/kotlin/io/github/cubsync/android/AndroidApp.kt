package io.github.cubsync.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.cubsync.service.Section
import io.github.cubsync.service.Words
import io.github.cubsync.service.sectionHint

/**
 * The phone: a title bar, one screen, and a bar of five.
 *
 * The five entries are the design's, in its order, and the log is folded into the job it
 * came from rather than given an entry of its own — the bar has room for five, and after
 * a run the question is "what did this run do", not "what has the application logged".
 * The desktop keeps the sixth entry because it has the room; that is the only difference.
 */
@Composable
fun CubAndroidApp(state: AndroidAppState) {
    val colors = CubTheme.colors
    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(state)
            Box(Modifier.weight(1f)) {
                when (val route = state.route) {
                    is Route.Tabs -> Tabs(state)
                    is Route.Config -> ConfigScreen(state, route.configId)
                    is Route.Preview -> PreviewScreen(state)
                    is Route.Job -> JobDetailScreen(state, route.jobId)
                    is Route.PickFolder -> PickFolderScreen(state, route.side)
                }
            }
            if (state.route is Route.Tabs) BottomBar(state)
        }
        state.notice?.let { NoticeBar(state, it) }
        Overlays(state)
        if (state.drawerOpen) Drawer(state)
    }
}

@Composable
private fun Tabs(state: AndroidAppState) {
    when (state.tab) {
        Section.CONNECT -> ConnectScreen(state)
        Section.TRANSFER -> TransferScreen(state)
        Section.SYNC -> SyncListScreen(state)
        Section.JOBS -> JobsScreen(state)
        else -> SettingsScreen(state)
    }
}

/** The title bar. A second-level screen gets a back arrow and the parent stays lit. */
@Composable
private fun TopBar(state: AndroidAppState) {
    val colors = CubTheme.colors
    val title = when (val route = state.route) {
        is Route.Tabs -> state.tab.label
        is Route.Config -> "配置详情"
        is Route.Preview -> "预览"
        is Route.Job -> "作业详情"
        is Route.PickFolder -> "选择文件夹"
    }
    val hint = when (val route = state.route) {
        is Route.Tabs -> sectionHint(state.tab)
        is Route.Config -> "方向、比较、文件夹对与过滤"
        is Route.Preview -> "只读一遍，看会发生什么"
        is Route.Job -> "这次运行的结果与明细"
        is Route.PickFolder -> route.side.let { if (it == io.github.cubsync.service.Side.LEFT) Words.LOCAL else Words.REMOTE }
    }

    Column(Modifier.fillMaxWidth().background(colors.rail)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(start = 6.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.route !is Route.Tabs) {
                GlyphButton("←", size = 38) { state.back() }
            } else {
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = CubType.sectionTitle, color = colors.text)
                Text(hint, style = CubType.meta, color = colors.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (state.running != null) {
                Chip(state.running?.label.orEmpty(), tone = colors.warning)
                Spacer(Modifier.width(6.dp))
                GlyphButton("×", size = 34) { state.cancelRun() }
            } else {
                Text(state.statusLine(), style = CubType.meta, color = colors.textFaint, maxLines = 1)
            }
        }
        HorizontalDivider(color = colors.outline)
    }
}

/** The bottom bar, drawn by hand: four glyphs and a word do not need a navigation bar. */
@Composable
private fun BottomBar(state: AndroidAppState) {
    val colors = CubTheme.colors
    Column {
        HorizontalDivider(color = colors.outline)
        Row(
            Modifier.fillMaxWidth().background(colors.rail).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            for (section in Section.entries.filter { it.onPhoneBar }) {
                val selected = state.tab == section
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { state.go(section) }
                        .padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        sectionGlyph(section),
                        style = CubType.label,
                        color = if (selected) colors.accent else colors.textFaint,
                    )
                    Text(
                        section.label,
                        style = CubType.meta,
                        color = if (selected) colors.accent else colors.textDim,
                    )
                }
            }
        }
    }
}

fun sectionGlyph(section: Section): String = when (section) {
    Section.CONNECT -> "连"
    Section.TRANSFER -> "传"
    Section.SYNC -> "同"
    Section.JOBS -> "业"
    Section.LOGS -> "志"
    Section.SETTINGS -> "设"
}

/** A short message above the tab bar. */
@Composable
private fun NoticeBar(state: AndroidAppState, notice: Notice) {
    val colors = CubTheme.colors
    val tone = when (notice.kind) {
        NoticeKind.INFO -> colors.info
        NoticeKind.SUCCESS -> colors.success
        NoticeKind.WARNING -> colors.warning
        NoticeKind.ERROR -> colors.danger
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier
                .padding(bottom = 86.dp, start = 16.dp, end = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surfaceRaised)
                .clickable { state.dismissNotice() }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(tone)
            Spacer(Modifier.width(9.dp))
            Text(notice.text, style = CubType.body, color = colors.text)
        }
    }
}

/** The left-swipe drawer: favourites, accounts, roots. */
@Composable
private fun Drawer(state: AndroidAppState) {
    val colors = CubTheme.colors
    val drawer = state.drawer()
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .width(268.dp)
                .fillMaxSize()
                .background(colors.surface)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("CubSync", style = CubType.sectionTitle, color = colors.text, modifier = Modifier.weight(1f))
                GlyphButton("×", size = 34) { state.drawerOpen = false }
            }
            Spacer(Modifier.height(10.dp))

            GroupLabel("收藏夹", Modifier.padding(horizontal = 16.dp))
            if (drawer.favourites.isEmpty()) {
                Text("在传输页收藏一个目录，它会出现在这里。", style = CubType.meta, color = colors.textFaint, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
            for (group in drawer.favourites) {
                Spacer(Modifier.height(4.dp))
                Text(group.device, style = CubType.meta, color = colors.textFaint, modifier = Modifier.padding(horizontal = 16.dp))
                for (item in group.items) {
                    ListRow(
                        title = item.label,
                        detail = item.path,
                        modifier = Modifier.padding(horizontal = 8.dp),
                        onClick = {
                            state.go(Section.TRANSFER)
                            state.openPane(io.github.cubsync.service.Side.LEFT, item.path, item.backendId)
                        },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            GroupLabel(Words.ACCOUNTS, Modifier.padding(horizontal = 16.dp))
            for (account in drawer.accounts) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (account.online) colors.success else colors.textFaint)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(account.label, style = CubType.body, color = colors.text, maxLines = 1)
                        Text(account.role, style = CubType.meta, color = colors.textFaint, maxLines = 1)
                    }
                }
            }

            if (drawer.roots.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                GroupLabel(Words.ROOTS, Modifier.padding(horizontal = 16.dp))
                for (root in drawer.roots) {
                    ListRow(
                        title = root.label,
                        detail = root.path,
                        modifier = Modifier.padding(horizontal = 8.dp),
                        onClick = { state.openPane(io.github.cubsync.service.Side.LEFT, root.path, root.backendId) },
                    )
                }
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f))
                .clickable { state.drawerOpen = false },
        )
    }
}

/** Whatever sheet the current screen asked for. */
@Composable
private fun Overlays(state: AndroidAppState) {
    val paneMenu = state.paneMenu
    val sheet = state.actionSheet
    if (paneMenu == null && sheet == null) return
    Box(Modifier.fillMaxSize()) {
        when {
            paneMenu != null -> PaneSheet(state, paneMenu)
            sheet != null -> ActionSheetPanel(state, sheet)
        }
    }
}
