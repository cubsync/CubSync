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
import io.github.cubsync.service.Format
import io.github.cubsync.service.IconPaths
import io.github.cubsync.service.PathTools
import io.github.cubsync.service.Peers
import io.github.cubsync.service.RunMode
import io.github.cubsync.service.Section
import io.github.cubsync.service.Side
import io.github.cubsync.service.Words

/**
 * 连接: this machine's side of the relationship, then the peers it can reach.
 *
 * The order is the design's. 运行模式 comes first and stays folded because it is the
 * question underneath everything else on a phone — whether the application can read a
 * folder at all — and somebody who is not fighting permissions should never have to read
 * about them. 传输通道 follows, then the one button that starts sharing, then the
 * accounts, then what was transferred recently.
 *
 * The two things a phone has no room for on this screen — dialling an address, and
 * choosing which folders are shared — live behind `⋮`, which is where the design puts
 * everything that does not fit.
 */
@Composable
fun ConnectScreen(state: AndroidAppState) {
    val colors = CubTheme.colors
    val server = state.serverState
    val settings = state.liveSettings
    var modeOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
        ) {
            FoldCard(
                title = "运行模式",
                summary = "${settings.runMode.label} · 默认收起",
                expanded = modeOpen,
                onToggle = { modeOpen = !modeOpen },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                ChoiceChips(
                    options = RunMode.entries,
                    selected = settings.runMode,
                    label = { it.label },
                    onSelect = { state.setRunMode(it) },
                )
                CardDesc("Root 模式可读写全部目录，需在 Magisk / KernelSU 中授权。")
            }

            SectionLabel("传输通道（多选网卡）")
            Panel(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                val nics = state.nics()
                if (nics.isEmpty()) {
                    CardDesc("没有读到可用的网卡地址，检查一下网络连接。")
                }
                for (nic in nics) {
                    CardRow(
                        title = nic.kind,
                        detail = if (nic.kind == nic.name) nic.address else "${nic.address} · ${nic.name}",
                        icon = IconPaths.WIFI,
                        trailing = {
                            Toggle(state.nicSelected(nic.address)) { state.toggleNic(nic.address) }
                        },
                    )
                }
                val running = state.running
                if (running != null) {
                    Text(
                        "本次传输 ${Format.rate(running.bytes, System.currentTimeMillis() - running.startedAtMs)}",
                        style = CubType.rowTitle,
                        color = colors.accent,
                    )
                }
                CardDesc("USB-ADB 与多链路是如实的占位档位：协议支持多链路，界面先把它标出来，传输本身仍走局域网。")
            }

            Panel(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                if (server.running) {
                    CardButton("停止共享", CardButtonKind.OUTLINED) { state.stopServer() }
                } else {
                    CardButton("启动服务端并等待远端连接") {
                        state.startServer(
                            settings.sharePort,
                            settings.serverNodeName.ifEmpty { Peers.local().name },
                        )
                    }
                }
                CardButton("浏览文件（双栏）", CardButtonKind.OUTLINED) {
                    state.go(Section.TRANSFER)
                    state.localRoots().firstOrNull()?.let { state.openPane(Side.LEFT, it) }
                }
            }

            SectionLabel("账户与连接状态")
            Panel(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                CardRow(
                    title = "本机 · ${settings.serverNodeName.ifEmpty { Peers.local().name }}",
                    detail = if (server.running) {
                        "服务端运行中 · 端口 ${server.port} · 共享 ${server.roots.size} 个文件夹"
                    } else {
                        "服务端未启动 · 共享 ${state.rootDraft.size} 个文件夹"
                    },
                    icon = IconPaths.SHARE,
                )
                for (peer in state.peers) {
                    CardRow(
                        title = peer.nodeName.ifEmpty { peer.backendId },
                        detail = "${peer.host}:${peer.port} · 协议 v${peer.protocolVersion} · ${peer.roots.size} 个可访问目录",
                        icon = IconPaths.DEVICE,
                        onClick = {
                            state.go(Section.TRANSFER)
                            state.openPane(Side.RIGHT, peer.roots.firstOrNull().orEmpty(), peer.backendId)
                        },
                        trailing = { QuietButton("断开") { state.disconnectPeer(peer.backendId) } },
                    )
                }
                if (state.peers.isEmpty()) {
                    CardDesc("还没有连上别的设备。点右上角 ⋮，填一个地址，或者等对面连过来。")
                }
                val remembered = settings.knownPeers.filterNot { known ->
                    state.peers.any {
                        it.backendId == io.github.cubsync.service.EndpointId.peer(known.host, known.port).backendId
                    }
                }
                if (remembered.isNotEmpty()) {
                    GroupLabel("之前连过")
                    for (peer in remembered.take(4)) {
                        CardRow(
                            title = peer.name.ifEmpty { peer.address },
                            detail = peer.address,
                            icon = IconPaths.CLOCK,
                            onClick = { state.connectPeer(peer.address) },
                        )
                    }
                }
            }

            SectionLabel("最近")
            Panel(Modifier.fillMaxWidth().padding(horizontal = 12.dp), outlined = true) {
                if (state.jobs.isEmpty()) {
                    CardDesc("还没有跑过同步。")
                }
                for (job in state.jobs.take(3)) {
                    CardRow(
                        title = job.configName,
                        detail = "${if (job.dryRun) "试运行" else Format.jobStatus(job.result.status)} · " +
                            "${Format.bytes(job.result.stats.copiedBytes)} · ${Format.ago(job.result.finishedAtMs)}",
                        icon = IconPaths.NAV_SYNC,
                        onClick = { state.go(Section.JOBS); state.open(Route.Job(job.jobId)) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 按地址连接 — the address box, which the phone keeps behind `⋮`. */
@Composable
fun ConnectSheet(state: AndroidAppState) {
    val colors = CubTheme.colors
    BottomSheet(onClose = { state.connectSheet = false }) {
        SheetHeader("按地址连接") { state.connectSheet = false }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(
                value = state.peerDraft,
                onValueChange = { state.peerDraft = it },
                modifier = Modifier.weight(1f),
                placeholder = "192.168.1.20:43917",
                onSubmit = { state.connectPeer(state.peerDraft) },
            )
            Spacer(Modifier.width(8.dp))
            PrimaryButton(if (state.connecting) "连接中…" else "连接", enabled = !state.connecting) {
                state.connectPeer(state.peerDraft)
            }
        }
        if (state.peerError != null) {
            Text(state.peerError.orEmpty(), style = CubType.rowDetail, color = colors.danger)
        }
        CardDesc("地址是「主机:端口」，端口就是对面共享时显示的那个。")

        ListRow("刷新设备", "重新看一遍已经连上的设备", leadingIcon = IconPaths.REFRESH) {
            state.refreshPeers(); state.connectSheet = false
        }
        ListRow("共享的文件夹（${state.rootDraft.size}）", "本机对外提供的目录", leadingIcon = IconPaths.SHARE) {
            state.connectSheet = false
            state.sharedSheet = true
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** 共享的文件夹 — what this machine offers, and the picker that adds to it. */
@Composable
fun SharedRootsSheet(state: AndroidAppState) {
    val colors = CubTheme.colors
    BottomSheet(onClose = { state.sharedSheet = false }) {
        SheetHeader("共享的文件夹") { state.sharedSheet = false }

        CardDesc("不共享也能连出去，只是别人看不到这台机器上的东西。")
        if (state.rootDraft.isEmpty()) {
            Text("还没有共享任何文件夹。", style = CubType.rowDetail, color = colors.textFaint)
        }
        for (root in state.rootDraft) {
            ListRow(
                title = PathTools.leafName(root).ifEmpty { root },
                detail = root,
                leadingIcon = IconPaths.FOLDER,
                trailing = { QuietButton("移除", danger = true) { state.removeRoot(root) } },
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("添加文件夹") {
                state.sharedSheet = false
                state.open(Route.PickFolder(Side.LEFT))
            }
            QuietButton("选本机根目录") { state.localRoots().forEach { state.addRoot(it) } }
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** The folder picker: the folders the engine can actually open, in one list. */
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
            style = CubType.rowDetail,
            color = colors.textFaint,
        )
        Spacer(Modifier.height(10.dp))
        Panel(Modifier.fillMaxWidth(), title = "可以打开的目录") {
            for (root in state.localRoots()) {
                CardRow(
                    title = PathTools.leafName(root).ifEmpty { root },
                    detail = root,
                    icon = IconPaths.FOLDER,
                    onClick = {
                        state.addRoot(root)
                        state.back()
                    },
                )
            }
        }
    }
}
