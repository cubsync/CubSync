package io.github.cubsync.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import io.github.cubsync.service.Channel
import io.github.cubsync.service.Format
import io.github.cubsync.service.Peers
import io.github.cubsync.service.RunMode
import io.github.cubsync.service.Section
import io.github.cubsync.service.Side
import io.github.cubsync.service.Words

/**
 * 连接: this machine's side of the relationship, then the peers it can reach.
 *
 * The run mode comes first because on a phone it is the question underneath everything
 * else — whether the application can read a folder at all — and it stays collapsed
 * because somebody who is not fighting permissions should never have to read about them.
 */
@Composable
fun ConnectScreen(state: AndroidAppState) {
    val colors = CubTheme.colors
    val server = state.serverState
    val settings = state.liveSettings

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Panel(Modifier.fillMaxWidth(), title = "设备发现", subtitle = "局域网直连，不经过任何服务器") {
            Field(
                value = state.peerDraft,
                onValueChange = { state.peerDraft = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "192.168.1.20:43917",
                onSubmit = { state.connectPeer(state.peerDraft) },
            )
            if (state.peerError != null) {
                Spacer(Modifier.height(6.dp))
                Text(state.peerError.orEmpty(), style = CubType.meta, color = colors.danger)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(if (state.connecting) "连接中…" else "连接", enabled = !state.connecting) {
                    state.connectPeer(state.peerDraft)
                }
                QuietButton("刷新设备") { state.refreshPeers() }
            }
        }

        Spacer(Modifier.height(12.dp))
        Panel(Modifier.fillMaxWidth(), title = "运行模式", subtitle = settings.runMode.label) {
            for (mode in RunMode.entries) {
                val current = mode == settings.runMode
                ListRow(
                    title = mode.label,
                    detail = mode.detail,
                    tint = if (current) colors.accent else null,
                    trailing = { if (current) Chip("使用中", tone = colors.accent) },
                    onClick = { if (!current) state.setRunMode(mode) },
                )
            }
            Text(
                "Root 模式需要设备已获取 Root 权限（KernelSU / Magisk / APatch）。普通模式经系统授权读写，能碰到的目录就是系统给的目录。",
                style = CubType.meta,
                color = colors.textFaint,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Spacer(Modifier.height(12.dp))
        Panel(Modifier.fillMaxWidth(), title = "传输通道", subtitle = Peers.localAddresses().joinToString("  ·  ").ifEmpty { "还没读到网卡地址" }) {
            for (channel in Channel.entries) {
                ListRow(
                    title = channel.label,
                    detail = channel.detail,
                    trailing = {
                        when (channel) {
                            Channel.USB_ADB -> Chip("未实现", tone = colors.textFaint)
                            Channel.MULTI_LINK -> Chip("单链路", tone = colors.textFaint)
                            Channel.LAN -> if (state.peers.isEmpty()) Chip("空闲", tone = colors.textFaint)
                            else Chip("已连接 ${state.peers.size} 台", tone = colors.success)
                        }
                    },
                )
            }
            val rate = state.running?.let { Format.rate(it.bytes, System.currentTimeMillis() - it.startedAtMs) } ?: DASH
            KeyValueRow("当前速率", rate)
            Text(
                "USB-ADB 与多链路是如实的占位档位：协议支持多链路，界面先把它标出来，传输本身仍走局域网。",
                style = CubType.meta,
                color = colors.textFaint,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Spacer(Modifier.height(12.dp))
        Panel(
            Modifier.fillMaxWidth(),
            title = "${Words.SERVER}（主控）",
            subtitle = if (server.running) "等待${Words.REMOTE}连接" else "选择共享的文件夹，然后开始",
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                QuietButton("添加文件夹") { state.open(Route.PickFolder(Side.LEFT)) }
                Spacer(Modifier.width(8.dp))
                Text("${state.rootDraft.size} 个", style = CubType.meta, color = colors.textFaint)
            }
            Spacer(Modifier.height(6.dp))
            if (state.rootDraft.isEmpty()) {
                Text(
                    "还没有共享任何文件夹。不共享也能连出去，只是别人看不到这台机器上的东西。",
                    style = CubType.meta,
                    color = colors.textFaint,
                )
            }
            for (root in state.rootDraft) {
                ListRow(
                    title = io.github.cubsync.service.PathTools.leafName(root).ifEmpty { root },
                    detail = root,
                    trailing = { QuietButton("移除") { state.removeRoot(root) } },
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (server.running) {
                    PrimaryButton("停止共享") { state.stopServer() }
                } else {
                    PrimaryButton("开始共享") {
                        state.startServer(
                            state.liveSettings.sharePort,
                            state.liveSettings.serverNodeName.ifEmpty { Peers.local().name },
                        )
                    }
                }
                QuietButton("浏览文件（双栏）") {
                    state.go(Section.TRANSFER)
                    state.localRoots().firstOrNull()?.let { state.openPane(Side.LEFT, it) }
                }
            }
            if (server.running) {
                Spacer(Modifier.height(8.dp))
                for (endpoint in server.endpoints) {
                    ListRow(title = endpoint, detail = "把这行填到另一台设备上")
                }
                if (server.endpoints.isEmpty()) {
                    Text("没有读到可用的网卡地址，检查一下网络连接。", style = CubType.meta, color = colors.warning)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Panel(Modifier.fillMaxWidth(), title = "已连接的设备") {
            if (state.peers.isEmpty()) {
                Text("还没有连上别的设备。", style = CubType.meta, color = colors.textFaint)
            }
            for (peer in state.peers) {
                ListRow(
                    title = peer.nodeName.ifEmpty { peer.backendId },
                    detail = "${peer.host}:${peer.port} · 协议 ${peer.protocolVersion} · ${peer.roots.size} 个可访问目录",
                    trailing = {
                        QuietButton("断开") { state.disconnectPeer(peer.backendId) }
                    },
                    onClick = {
                        state.go(Section.TRANSFER)
                        state.openPane(Side.RIGHT, peer.roots.firstOrNull().orEmpty(), peer.backendId)
                    },
                )
            }
            val remembered = state.liveSettings.knownPeers.filterNot { known ->
                state.peers.any { it.backendId == io.github.cubsync.service.EndpointId.peer(known.host, known.port).backendId }
            }
            if (remembered.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                GroupLabel("之前连过")
                for (peer in remembered.take(5)) {
                    ListRow(
                        title = peer.name.ifEmpty { peer.address },
                        detail = peer.address,
                        onClick = {
                            state.peerDraft = peer.address
                            state.connectPeer(peer.address)
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Panel(
            Modifier.fillMaxWidth(),
            title = "最近传输",
            actions = { QuietButton("全部作业") { state.go(Section.JOBS) } },
        ) {
            if (state.jobs.isEmpty()) {
                Text("还没有跑过同步。", style = CubType.meta, color = colors.textFaint)
            }
            for (job in state.jobs.take(3)) {
                ListRow(
                    title = job.configName,
                    detail = "${if (job.dryRun) "试运行" else Format.jobStatus(job.result.status)} · ${Format.bytes(job.result.stats.copiedBytes)} · ${Format.ago(job.result.finishedAtMs)}",
                    onClick = { state.go(Section.JOBS); state.open(Route.Job(job.jobId)) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** The folder picker: the suggestions the engine can actually open, in one list. */
@Composable
fun PickFolderScreen(state: AndroidAppState, side: Side) {
    val colors = CubTheme.colors
    var typed by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(
                value = typed,
                onValueChange = { typed = it },
                modifier = Modifier.weight(1f),
                placeholder = "也可以直接输入路径",
                onSubmit = { if (typed.isNotBlank()) state.addRoot(typed) },
            )
            Spacer(Modifier.width(8.dp))
            PrimaryButton("用这个") { if (typed.isNotBlank()) state.addRoot(typed) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "系统选择器给出的是 content URI，而引擎按路径工作，所以这里列出的是能直接打开的目录；路径不对可以直接改。",
            style = CubType.meta,
            color = colors.textFaint,
        )
        Spacer(Modifier.height(10.dp))
        Panel(Modifier.fillMaxWidth(), title = "可以打开的目录") {
            for (root in state.localRoots()) {
                ListRow(
                    title = io.github.cubsync.service.PathTools.leafName(root).ifEmpty { root },
                    detail = root,
                    onClick = {
                        state.addRoot(root)
                        state.back()
                    },
                )
            }
        }
    }
}
