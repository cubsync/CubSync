package io.github.cubsync.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import io.github.cubsync.service.Format
import io.github.cubsync.service.IconPaths
import io.github.cubsync.service.Section
import io.github.cubsync.service.Words
import kotlinx.coroutines.delay

/**
 * The phone: a top bar, one screen, and a bar of five.
 *
 * The top bar is the design's: `☰` on the four top-level screens, `←` on everything
 * opened from one, and a different action on the right of each — `⋮` for the screens with
 * a small menu behind it, `★` for a configuration, `⟳` for a preview. The transfer screen
 * has no bar of its own, because its `☰` and `⋮` live at the two ends of its sub-row.
 */
@Composable
fun CubAndroidApp(state: AndroidAppState) {
    val colors = CubTheme.colors
    val route = state.route

    Box(Modifier.fillMaxSize().background(colors.background)) {
        // The system bars sit over the app (MainActivity calls enableEdgeToEdge), so the
        // content has to keep itself out from under them. Without the top inset the clock
        // lands on the title; without the bottom one the tab bar lands on the gesture bar.
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            // The transfer screen carries its own two ends rather than a bar.
            if (!(route is Route.Tabs && state.tab == Section.TRANSFER)) {
                TopBar(state)
            }
            Box(Modifier.weight(1f)) {
                when (route) {
                    is Route.Tabs -> Tabs(state)
                    is Route.Config -> ConfigScreen(state, route.configId)
                    is Route.Preview -> PreviewScreen(state)
                    is Route.Running -> RunningScreen(state, route.jobId)
                    is Route.Job -> JobDetailScreen(state, route.jobId)
                    is Route.PickFolder -> PickFolderScreen(state, route.side)
                }
            }
            if (route is Route.Tabs) BottomBar(state)
        }
        NoticeBar(state)
        when {
            state.screenMenu -> ScreenMenu(state)
            state.connectSheet -> ConnectSheet(state)
            state.sharedSheet -> SharedRootsSheet(state)
        }
        Overlays(state)
        if (state.drawerOpen) Drawer(state)

        // The drawer also opens with a left swipe, which is what the design document lists
        // in its gesture table; the menu mark is the second way in, not the only one.
        if (!state.drawerOpen) {
            Box(
                Modifier
                    .width(24.dp)
                    .fillMaxHeight()
                    .align(Alignment.CenterStart)
                    .pointerInput(Unit) {
                        var travelled = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { travelled = 0f },
                            onDragEnd = { travelled = 0f },
                        ) { change, amount ->
                            change.consume()
                            travelled += amount
                            if (travelled > 48f) {
                                state.drawerOpen = true
                                travelled = 0f
                            }
                        }
                    },
            )
        }
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

// ------------------------------------------------------------------- top bar

/** What the top bar shows: where you came from, where you are, and this screen's action. */
private data class Bar(
    val back: Boolean,
    val title: String,
    val action: String?,
)

private fun barFor(state: AndroidAppState): Bar = when (val route = state.route) {
    is Route.Tabs -> Bar(back = false, title = state.tab.label, action = IconPaths.MORE)
    is Route.Config -> Bar(back = true, title = state.selectedConfig?.name ?: "配置详情", action = IconPaths.STAR)
    is Route.Preview -> Bar(back = true, title = "差异预览", action = IconPaths.REFRESH)
    is Route.Running -> Bar(back = true, title = "正在同步", action = IconPaths.MORE)
    is Route.Job -> Bar(back = true, title = "作业详情", action = IconPaths.MORE)
    is Route.PickFolder -> Bar(back = true, title = "选择文件夹", action = null)
}

@Composable
private fun TopBar(state: AndroidAppState) {
    val colors = CubTheme.colors
    val bar = barFor(state)
    val starred = bar.action == IconPaths.STAR &&
        state.selectedConfig?.let { config ->
            state.service.settings.isFavourite(io.github.cubsync.service.EndpointId.LOCAL, config.configId)
        } == true

    Column(Modifier.fillMaxWidth().background(colors.surface)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (bar.back) {
                IconButton(IconPaths.BACK, size = 38) { state.back() }
            } else {
                IconButton(IconPaths.MENU, size = 38) { state.drawerOpen = true }
            }

            Text(
                bar.title,
                style = CubType.topTitle,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )

            when (bar.action) {
                IconPaths.STAR -> IconButton(
                    IconPaths.STAR,
                    size = 38,
                    tint = if (starred) colors.warning else null,
                ) { state.toggleConfigStar() }

                IconPaths.REFRESH -> IconButton(IconPaths.REFRESH, size = 38) {
                    state.selectedConfig?.let { state.previewConfig(it) }
                }

                IconPaths.MORE -> IconButton(
                    IconPaths.MORE,
                    size = 38,
                    stroke = 3.4f,
                ) { state.screenMenu = true }

                else -> Spacer(Modifier.width(6.dp))
            }
        }
    }
}

/** The bottom bar: the design's five, each with its icon above its word. */
@Composable
private fun BottomBar(state: AndroidAppState) {
    val colors = CubTheme.colors
    Column {
        HorizontalDivider(color = colors.outline)
        Row(
            Modifier.fillMaxWidth().height(64.dp).background(colors.surfaceRaised),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            for (section in Section.entries.filter { it.onPhoneBar }) {
                val selected = state.tab == section
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { state.go(section) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(99.dp))
                            .background(if (selected) colors.accentContainer else Color.Transparent)
                            .padding(horizontal = 12.dp, vertical = 3.dp),
                    ) {
                        CubIcon(
                            sectionIcon(section),
                            size = 20,
                            tint = if (selected) colors.accent else colors.textFaint,
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        section.label,
                        style = CubType.navLabel,
                        color = if (selected) colors.accent else colors.textDim,
                    )
                }
            }
        }
    }
}

/** A message above the bar, which leaves on its own — a notice nobody can dismiss is a wall. */
@Composable
private fun NoticeBar(state: AndroidAppState) {
    val notice = state.notice ?: return
    val colors = CubTheme.colors
    val tone = when (notice.kind) {
        NoticeKind.INFO -> colors.info
        NoticeKind.SUCCESS -> colors.success
        NoticeKind.WARNING -> colors.warning
        NoticeKind.ERROR -> colors.danger
    }

    LaunchedEffect(notice) {
        delay(4_000)
        state.dismissNotice()
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier
                .padding(bottom = 84.dp, start = 16.dp, end = 16.dp)
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

/** The drawer: favourites, accounts, roots. Opened by `☰`, which is on every top-level screen. */
@Composable
private fun Drawer(state: AndroidAppState) {
    val colors = CubTheme.colors
    val drawer = state.drawer()

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .width(282.dp)
                    .fillMaxSize()
                    .background(colors.surface)
                    // The prototype draws its drawer under a 34px status strip; on a phone
                    // that strip is the real one, so the drawer keeps clear of it too.
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 16.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BrandMark(size = 32)
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text("CubSync", style = CubType.cardTitle, color = colors.text)
                        Text(
                            "${Words.LOCAL} ${if (state.peers.isEmpty()) "· 未连接" else "+ ${state.peers.size} 台"} · " +
                                "${drawer.favourites.sumOf { it.items.size }} 个收藏夹",
                            style = CubType.meta,
                            color = colors.textDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(IconPaths.CLOSE, size = 38) { state.drawerOpen = false }
                }
                Spacer(Modifier.height(8.dp))

                SectionLabel(Words.FAVOURITES)
                if (drawer.favourites.isEmpty()) {
                    Text(
                        "在传输页点星标收藏一个目录，它会出现在这里。",
                        style = CubType.rowDetail,
                        color = colors.textFaint,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                for (group in drawer.favourites) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        group.device,
                        style = CubType.meta,
                        color = colors.textFaint,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    for (item in group.items) {
                        ListRow(
                            title = item.label,
                            detail = item.path,
                            leadingIcon = IconPaths.FOLDER,
                            modifier = Modifier.padding(horizontal = 8.dp),
                            onClick = {
                                state.drawerOpen = false
                                state.go(Section.TRANSFER)
                                state.openPane(io.github.cubsync.service.Side.LEFT, item.path, item.backendId)
                            },
                        )
                    }
                }

                SectionLabel(Words.ACCOUNTS)
                for (account in drawer.accounts) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusDot(if (account.online) colors.success else colors.textFaint)
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(account.label, style = CubType.rowTitle, color = colors.text, maxLines = 1)
                            Text(account.role, style = CubType.meta, color = colors.textFaint, maxLines = 1)
                        }
                    }
                }

                if (drawer.roots.isNotEmpty()) {
                    SectionLabel(Words.ROOTS)
                    for (root in drawer.roots) {
                        ListRow(
                            title = root.label,
                            detail = root.path,
                            leadingIcon = IconPaths.FOLDER,
                            modifier = Modifier.padding(horizontal = 8.dp),
                            onClick = {
                                state.drawerOpen = false
                                state.openPane(io.github.cubsync.service.Side.LEFT, root.path, root.backendId)
                            },
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
}

/** The ⋮ behind the top bar, which is a different list on every screen. */
@Composable
private fun ScreenMenu(state: AndroidAppState) {
    val route = state.route
    BottomSheet(onClose = { state.screenMenu = false }) {
        SheetHeader("更多") { state.screenMenu = false }

        when {
            route is Route.Tabs && state.tab == Section.CONNECT -> {
                ListRow("按地址连接", "填 HOST:PORT，直连一台设备", leadingIcon = IconPaths.LINK) {
                    state.screenMenu = false
                    state.connectSheet = true
                }
                ListRow("共享的文件夹（${state.rootDraft.size}）", "本机对外提供的目录", leadingIcon = IconPaths.SHARE) {
                    state.screenMenu = false
                    state.sharedSheet = true
                }
                ListRow("刷新设备", "重新看一遍已经连上的设备", leadingIcon = IconPaths.REFRESH) {
                    state.refreshPeers(); state.screenMenu = false
                }
            }

            route is Route.Tabs && state.tab == Section.SYNC -> {
                ListRow("新建配置", "从空的文件夹对开始", leadingIcon = IconPaths.PLUS) {
                    state.saveConfig(state.newConfigDraft()); state.screenMenu = false
                }
                ListRow("另存为副本", "复制当前选中的配置", leadingIcon = IconPaths.NAV_SYNC) {
                    state.selectedConfig?.let { state.duplicateConfig(it) }; state.screenMenu = false
                }
                ListRow("导出 .cubcfg", "存到应用目录，可以发给别的机器", leadingIcon = IconPaths.NAV_LOGS) {
                    state.selectedConfig?.let { state.exportConfig(it) }; state.screenMenu = false
                }
                ListRow("删除选中的配置", "只删这一份，文件不动", leadingIcon = IconPaths.TRASH, tint = CubTheme.colors.danger) {
                    state.selectedConfigId?.let { state.deleteConfig(it) }; state.screenMenu = false
                }
            }

            route is Route.Tabs && state.tab == Section.JOBS -> {
                ListRow("切到日志", "本次会话的实时记录", leadingIcon = IconPaths.NAV_LOGS) {
                    state.jobsSegment = JobsSegment.LOGS; state.screenMenu = false
                }
                ListRow("切到作业", "跑过的每一次同步", leadingIcon = IconPaths.NAV_JOBS) {
                    state.jobsSegment = JobsSegment.JOBS; state.screenMenu = false
                }
                ListRow("清空日志", "只清内存里的记录", leadingIcon = IconPaths.TRASH) {
                    state.clearLogs(); state.screenMenu = false
                }
            }

            route is Route.Tabs && state.tab == Section.SETTINGS -> {
                ListRow("关于", "版本、状态目录与协议版本", leadingIcon = IconPaths.NAV_SETTINGS) {
                    state.notify(
                        "版本 ${io.github.cubsync.service.BuildInfo.VERSION} · 协议 v${io.github.cubsync.service.BuildInfo.PROTOCOL_VERSION}",
                        NoticeKind.INFO,
                    )
                    state.screenMenu = false
                }
            }

            route is Route.Running -> {
                ListRow("回到同步页", "运行还在继续", leadingIcon = IconPaths.BACK) {
                    state.go(Section.SYNC); state.screenMenu = false
                }
                ListRow("取消这次同步", "保留续传信息", leadingIcon = IconPaths.CLOSE, tint = CubTheme.colors.danger) {
                    state.cancelRun(); state.screenMenu = false
                }
            }

            route is Route.Job -> {
                val job = state.selectedJob(route.jobId)
                ListRow("再次运行", "用同一个配置再跑一次", leadingIcon = IconPaths.NAV_SYNC) {
                    state.configs.firstOrNull { it.configId == job?.configId }?.let { state.runConfig(it, dryRun = false) }
                    state.screenMenu = false
                }
                ListRow("导出报告", "写成文本文件放在应用目录", leadingIcon = IconPaths.NAV_LOGS) {
                    job?.let { state.exportReport(it) }
                    state.screenMenu = false
                }
                ListRow("删除这次记录", "只从列表里去掉", leadingIcon = IconPaths.TRASH, tint = CubTheme.colors.danger) {
                    state.forgetJob(route.jobId); state.screenMenu = false
                }
            }

            else -> ListRow("关闭", "", leadingIcon = IconPaths.CLOSE) { state.screenMenu = false }
        }
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

/**
 * A run in flight: the percentage, the pair it is on, every pair's state, and the two
 * things you can do.
 *
 * The percentage counts whole pairs, because that is the only progress the engine reports;
 * a bar that moved between pairs would be a bar showing something nobody measured.
 */
@Composable
fun RunningScreen(state: AndroidAppState, jobId: String) {
    val colors = CubTheme.colors
    val running = state.running
    val elapsed = running?.let { System.currentTimeMillis() - it.startedAtMs } ?: 0

    if (running == null) {
        EmptyState(
            "这次运行已经结束",
            "结果在作业详情里。",
            action = { PrimaryButton("看结果") { state.open(Route.Job(jobId)) } },
        )
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 12.dp),
    ) {
        Panel(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 22.dp),
        ) {
            Text(
                "${(running.fraction * 100).toInt()}%",
                style = CubType.huge,
                color = colors.accent,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            ProgressBar(running.fraction, height = 8)
            Text(
                buildString {
                    append("第 ${(running.finishedPairs + 1).coerceAtMost(running.pairCount)}/${running.pairCount} 对")
                    if (running.currentPair.isNotEmpty()) append(" · ${running.currentPair}")
                },
                style = CubType.rowDetail,
                color = colors.textDim,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Text(
                "${Format.rate(running.bytes, elapsed)} · 耗时 ${Format.duration(elapsed)} · " +
                    "已完成 ${running.finishedPairs} / ${running.pairCount} 项",
                style = CubType.rowDetail,
                color = colors.textFaint,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }

        Panel(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text("按文件夹对", style = CubType.rowTitle, color = colors.text)
            for ((index, name) in running.pairs.withIndex()) {
                val done = index < running.finishedPairs
                val active = name == running.currentPair && !done
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(name, style = CubType.body, color = colors.text, maxLines = 1)
                        Spacer(Modifier.height(5.dp))
                        ProgressBar(
                            if (done) 1f else if (active) 0.5f else 0f,
                            tone = when {
                                done -> colors.success
                                active -> colors.warning
                                else -> colors.textFaint
                            },
                            height = 4,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    when {
                        done -> Chip("完成", tone = colors.success)
                        active -> Chip(if (running.dryRun) "比较中" else "进行中", tone = colors.warning)
                        else -> Chip("排队", tone = colors.textFaint)
                    }
                }
            }
        }

        Panel(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), outlined = true) {
            Text("断点续传", style = CubType.rowTitle, color = colors.text)
            CardDesc(
                if (state.liveSettings.resumeEnabled) {
                    "中断后可续：目标端的 .cubtmp 与 journal（带 pairId）留在原处，" +
                        "下次从这里继续，前段按块校验。"
                } else {
                    "断点续传关着：中断后下次重传整个文件。这一档在设置里开。"
                },
            )
        }

        Panel(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            CardButton("暂停", CardButtonKind.TONAL) {
                state.notify("协议还没实现暂停，只能取消；取消会保留续传信息", NoticeKind.WARNING)
            }
            CardButton("取消并保留续传信息", CardButtonKind.OUTLINED) { state.cancelRun() }
        }
        Spacer(Modifier.height(16.dp))
    }
}
