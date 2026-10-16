package io.github.cubsync.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.cubsync.desktop.AppState
import io.github.cubsync.desktop.CubTheme
import io.github.cubsync.desktop.CubType
import io.github.cubsync.desktop.NoticeKind
import io.github.cubsync.desktop.pickDirectory
import io.github.cubsync.service.Channel
import io.github.cubsync.service.Format
import io.github.cubsync.service.Peers
import io.github.cubsync.service.RunMode
import io.github.cubsync.service.Section
import io.github.cubsync.service.ServerState
import io.github.cubsync.desktop.copyToClipboard
import io.github.cubsync.service.Words

/**
 * 连接: what this machine offers, and what it is talking to.
 *
 * The order is the order somebody needs it in — the run mode answers "can this even read
 * my folders", then the channel says how the bytes travel, then sharing, then the peers,
 * then what happened last time.
 */
@Composable
fun ConnectScreen(state: AppState, server: ServerState) {
    val colors = CubTheme.colors

    Row(Modifier.fillMaxWidth().fillMaxHeight().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            DevicesPanel(state, server)
            Spacer(Modifier.height(12.dp))
            AccountsPanel(state, server)
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            RunModePanel(state)
            Spacer(Modifier.height(12.dp))
            ChannelsPanel(state)
            Spacer(Modifier.height(12.dp))
            SharingPanel(state, server)
            Spacer(Modifier.height(12.dp))
            RecentPanel(state)
        }
    }
    colors.hashCode()
}

// ------------------------------------------------------------------ devices

/** 设备发现: the peers this machine has reached, and a box for a new one. */
@Composable
private fun DevicesPanel(state: AppState, server: ServerState) {
    val colors = CubTheme.colors
    Panel(
        Modifier.fillMaxWidth(),
        title = "设备发现",
        subtitle = "局域网里直接连，不经过任何服务器",
        actions = {
            QuietButton("刷新设备") { state.refreshPeers() }
            Spacer(Modifier.width(4.dp))
            PrimaryButton("连接", enabled = !state.connecting) { state.connectPeer(state.peerDraft) }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(
                value = state.peerDraft,
                onValueChange = { state.peerDraft = it },
                modifier = Modifier.weight(1f),
                placeholder = "192.168.1.20:43917",
                onSubmit = { state.connectPeer(state.peerDraft) },
            )
        }
        if (state.peerError != null) {
            Spacer(Modifier.height(6.dp))
            Text(state.peerError.orEmpty(), style = CubType.meta, color = colors.danger)
        }

        Spacer(Modifier.height(10.dp))
        SubHeading("已连接", if (state.peers.isEmpty()) "" else "${state.peers.size} 台")

        if (state.peers.isEmpty()) {
            Text(
                if (server.running) "还没有连上别的设备。把上面的地址拿到另一台机器上填。" else "还没有连上别的设备。",
                style = CubType.meta,
                color = colors.textFaint,
            )
        }
        for (peer in state.peers) {
            ListLine(
                title = peer.nodeName.ifEmpty { peer.backendId },
                detail = "${peer.host}:${peer.port} · 协议 ${peer.protocolVersion} · v${peer.appVersion} · ${peer.roots.size} 个可访问目录",
                trailing = {
                    QuietButton("断开") { state.disconnectPeer(peer.backendId) }
                    Spacer(Modifier.width(4.dp))
                    PrimaryButton("浏览") {
                        state.goTo(Section.TRANSFER)
                        state.openPane(io.github.cubsync.service.Side.RIGHT, peer.roots.firstOrNull().orEmpty(), peer.backendId)
                    }
                },
            )
        }

        val remembered = state.service.settings.knownPeers.filterNot { known -> state.peers.any { it.backendId == io.github.cubsync.service.EndpointId.peer(known.host, known.port).backendId } }
        if (remembered.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            SubHeading("之前连过")
            for (peer in remembered) {
                ListLine(
                    title = peer.name.ifEmpty { "${peer.host}:${peer.port}" },
                    detail = "${peer.host}:${peer.port}" + if (peer.seenAtMs > 0) " · ${Format.ago(peer.seenAtMs)}" else "",
                    trailing = {
                        QuietButton("连接") {
                            state.peerDraft = peer.address
                            state.connectPeer(peer.address)
                        }
                    },
                )
            }
        }
    }
}

// ------------------------------------------------------------------ accounts

/** 账户与连接状态, then the last few runs. */
@Composable
private fun AccountsPanel(state: AppState, server: ServerState) {
    val colors = CubTheme.colors
    Panel(Modifier.fillMaxWidth(), title = Words.ACCOUNTS, subtitle = "本机、远端，以及它们现在的状态") {
        val drawer = state.drawer()
        for (account in drawer.accounts) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                DeviceDot(account.online)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(account.label, style = CubType.body, color = colors.text, maxLines = 1)
                    Text(account.role, style = CubType.meta, color = colors.textFaint, maxLines = 1)
                }
                Chip(if (account.online) "在线" else "离线", tone = if (account.online) colors.success else colors.textFaint)
            }
        }
        if (drawer.accounts.size <= 1) {
            Spacer(Modifier.height(4.dp))
            Text("云端账户还没有接入：现在只有本机与远端。", style = CubType.meta, color = colors.textFaint)
        }
    }
}

// ------------------------------------------------------------------ run mode

/**
 * 运行模式, collapsed by default.
 *
 * Always collapsed, even though the choice matters: the header already says which mode is
 * in force, and a phone user who is not fighting permissions should never have to read
 * about them.
 */
@Composable
private fun RunModePanel(state: AppState) {
    val colors = CubTheme.colors
    var open by remember { mutableStateOf(false) }
    val current = state.liveSettings.runMode

    Panel(
        Modifier.fillMaxWidth(),
        title = "运行模式",
        subtitle = if (open) "" else current.label,
        actions = {
            QuietButton(if (open) "收起" else "展开") { open = !open }
        },
    ) {
        if (!open) {
            Text(
                "当前：${current.label} —— ${current.detail}",
                style = CubType.meta,
                color = colors.textDim,
            )
            return@Panel
        }
        for (mode in RunMode.entries) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Column {
                        Text(mode.label, style = CubType.body, color = colors.text)
                        Text(mode.detail, style = CubType.meta, color = colors.textFaint)
                    }
                }
                if (mode == current) Chip("使用中", tone = colors.accent)
                else QuietButton("切到这一档") { state.changeSettings { it.runMode = mode } }
            }
        }
        if (current == RunMode.ROOT) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Root 模式需要设备已获取 Root 权限；桌面端用它直读任意目录，不经系统授权框。",
                style = CubType.meta,
                color = colors.textFaint,
            )
        }
    }
}

// ------------------------------------------------------------------ channels

/** 传输通道: which links a transfer may use, and how fast they are going. */
@Composable
private fun ChannelsPanel(state: AppState) {
    val colors = CubTheme.colors
    val addresses = remember { Peers.localAddresses() }
    val elapsed = state.running?.let { System.currentTimeMillis() - it.startedAtMs } ?: 0

    Panel(
        Modifier.fillMaxWidth(),
        title = "传输通道",
        subtitle = if (addresses.isEmpty()) "这个网段的地址还没读到" else addresses.joinToString("  ·  "),
    ) {
        for (channel in Channel.entries) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Column {
                        Text(channel.label, style = CubType.body, color = colors.text)
                        Text(channel.detail, style = CubType.meta, color = colors.textFaint)
                    }
                }
                ChannelState(state, channel)
            }
        }

        Spacer(Modifier.height(8.dp))
        val rate = if (state.running != null && elapsed > 0) {
            Format.rate(state.running?.bytes ?: 0, elapsed)
        } else {
            DASH
        }
        KeyValueRow("当前速率", rate)
        KeyValueRow("本机名称", state.liveSettings.serverNodeName.ifEmpty { Peers.local().name })
        Text(
            "USB-ADB 与多链路还是占位的档位：协议支持多链路，界面上先如实标出来，传输本身仍走局域网。",
            style = CubType.meta,
            color = colors.textFaint,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** What a channel is doing right now, said honestly rather than dressed up. */
@Composable
private fun ChannelState(state: AppState, channel: Channel) {
    val colors = CubTheme.colors
    when {
        channel == Channel.USB_ADB -> Chip("未实现", tone = colors.textFaint)
        channel == Channel.MULTI_LINK -> Chip("单链路", tone = colors.textFaint)
        state.peers.isNotEmpty() -> Chip("已连接 ${state.peers.size} 台", tone = colors.success)
        else -> Chip("空闲", tone = colors.textFaint)
    }
}

// ------------------------------------------------------------------ sharing

/** 启动服务端并等待远端连接. */
@Composable
private fun SharingPanel(state: AppState, server: ServerState) {
    val colors = CubTheme.colors
    Box {
        Panel(
            Modifier.fillMaxWidth(),
            title = "${Words.SERVER}（主控）",
            subtitle = if (server.running) "等待远端连接" else "选择要共享的文件夹，然后开始",
            actions = {
                if (server.running) {
                    QuietButton("停止共享", danger = true) { state.stopServer() }
                } else {
                    PrimaryButton("开始共享") {
                        state.startServer(state.liveSettings.sharePort, state.liveSettings.serverNodeName.ifEmpty { Peers.local().name })
                    }
                }
            },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                QuietButton("添加文件夹…") {
                    val picked = pickDirectory("选择要共享的文件夹")
                    if (picked != null) state.addRoot(picked) else state.notify("已取消", NoticeKind.INFO)
                }
                Spacer(Modifier.width(6.dp))
                Text("${state.rootDraft.size} 个", style = CubType.meta, color = colors.textFaint)
            }

            Spacer(Modifier.height(8.dp))
            if (state.rootDraft.isEmpty()) {
                Text("还没有共享任何文件夹。不共享也能连出去，只是别人看不到这台机器上的东西。", style = CubType.meta, color = colors.textFaint)
            }
            for (root in state.rootDraft) {
                ListLine(
                    title = io.github.cubsync.service.PathTools.leafName(root).ifEmpty { root },
                    detail = root,
                    trailing = { QuietButton("移除") { state.removeRoot(root) } },
                )
            }

            if (server.running) {
                Spacer(Modifier.height(10.dp))
                SubHeading("把下面的地址填到另一台设备上")
                for (endpoint in server.endpoints) {
                    ListLine(
                        title = endpoint,
                        detail = server.nodeName,
                        trailing = { QuietButton("复制") { copyToClipboard(endpoint); state.notify("已复制 $endpoint", NoticeKind.SUCCESS) } },
                    )
                }
                if (server.endpoints.isEmpty()) {
                    Text("没有读到可用的网卡地址，检查一下网络连接。", style = CubType.meta, color = colors.warning)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ recent

/** 最近传输: the last few runs, without leaving the screen that started them. */
@Composable
private fun RecentPanel(state: AppState) {
    val colors = CubTheme.colors
    Panel(
        Modifier.fillMaxWidth(),
        title = "最近传输",
        actions = { QuietButton("全部作业") { state.goTo(Section.JOBS) } },
    ) {
        if (state.jobs.isEmpty()) {
            Text("还没有跑过同步。", style = CubType.meta, color = colors.textFaint)
            return@Panel
        }
        for (job in state.jobs.take(4)) {
            ListLine(
                title = job.configName,
                detail = "${idle(job)} · ${Format.bytes(job.result.stats.copiedBytes)} · ${Format.ago(job.result.finishedAtMs)}",
                onClick = {
                    state.selectJob(job.jobId)
                    state.jobsSegment = io.github.cubsync.desktop.JobsSegment.JOBS
                    state.goTo(Section.JOBS)
                },
            )
        }
    }
    colors.hashCode()
}

private fun idle(job: io.github.cubsync.service.JobRecord): String =
    if (job.dryRun) "试运行" else Format.jobStatus(job.result.status)

/**
 * 浏览文件（双栏）, as an entrance rather than a footnote.
 *
 * It sits here because this is the screen that has just said which folders this machine
 * offers, and looking inside one is the next thing anybody wants to do.
 */
@Composable
fun BrowseEntryButton(state: AppState) {
    PrimaryButton("浏览文件（双栏）") {
        state.goTo(Section.TRANSFER)
        val roots = state.localRoots()
        if (roots.isNotEmpty()) state.openPane(io.github.cubsync.service.Side.LEFT, roots.first())
    }
}
