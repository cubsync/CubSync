package io.github.cubsync.desktop.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.cubsync.desktop.AppState
import io.github.cubsync.desktop.CubTheme
import io.github.cubsync.desktop.CubType
import io.github.cubsync.desktop.Format
import io.github.cubsync.desktop.NoticeKind
import io.github.cubsync.desktop.Section
import io.github.cubsync.desktop.copyToClipboard
import io.github.cubsync.desktop.pickDirectory
import io.github.cubsync.service.PeerSession
import io.github.cubsync.service.ServerState

/**
 * The screen that has to work before anything else can: sharing a folder, and
 * reaching another machine.
 *
 * Both halves are the same shape -- a state, a list of folders, one button that
 * flips the state -- because that is what the two halves are. The server publishes
 * the addresses a person reads out; the peer card shows what the other machine
 * said about itself, which is what turns "it does not work" into a bug report.
 */
@Composable
fun ConnectScreen(state: AppState, server: ServerState) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        ScreenHeader(Section.CONNECT.label, Section.CONNECT.hint)

        Column(Modifier.padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ServerPanel(state, server)
            PeerPanel(state)
        }
    }
}

@Composable
private fun ServerPanel(state: AppState, server: ServerState) {
    val colors = CubTheme.colors
    var portText by remember(server.port) { mutableStateOf(if (server.port > 0) server.port.toString() else "") }
    var nameText by remember(server.nodeName) { mutableStateOf(server.nodeName) }
    var newRoot by remember { mutableStateOf("") }

    Panel(
        modifier = Modifier.fillMaxWidth(),
        title = "本机服务端",
        subtitle = if (server.running) {
            "正在端口 ${server.port} 上共享 ${server.roots.size} 个文件夹，等待其他设备连接"
        } else {
            "选择要共享给别的设备的文件夹"
        },
        actions = {
            if (server.running) {
                QuietButton("停止共享", onClick = { state.stopServer() })
            } else {
                PrimaryButton("开始共享", onClick = { state.startServer(portText.toIntOrNull() ?: 0, nameText) })
            }
        },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f)) {
                FieldLabel("共享的文件夹")
                Spacer(Modifier.height(6.dp))
                if (state.rootDraft.isEmpty()) {
                    Text("还没有选择文件夹", style = CubType.meta, color = colors.textFaint)
                }
                for (root in state.rootDraft) {
                    RootRow(root, removable = !server.running) { state.removeRoot(root) }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CubField(
                        value = newRoot,
                        onValueChange = { newRoot = it },
                        modifier = Modifier.weight(1f),
                        placeholder = "例如 D:\\Photos",
                        enabled = !server.running,
                    )
                    Spacer(Modifier.width(8.dp))
                    QuietButton(
                        "浏览",
                        enabled = !server.running,
                        onClick = { pickDirectory(newRoot.ifBlank { null })?.let { newRoot = it } },
                    )
                    Spacer(Modifier.width(4.dp))
                    QuietButton(
                        "添加",
                        enabled = !server.running && newRoot.isNotBlank(),
                        onClick = {
                            state.addRoot(newRoot)
                            newRoot = ""
                        },
                    )
                }
            }

            Column(Modifier.weight(1f)) {
                FieldLabel("端口与设备名")
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CubField(
                        value = portText,
                        onValueChange = { text -> portText = text.filter { it.isDigit() }.take(5) },
                        modifier = Modifier.width(120.dp),
                        placeholder = "43917",
                        enabled = !server.running,
                    )
                    CubField(
                        value = nameText,
                        onValueChange = { nameText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = "这台设备的名字",
                        enabled = !server.running,
                    )
                }
                Spacer(Modifier.height(14.dp))
                FieldLabel(if (server.running) "别的设备填这个地址" else "状态")
                Spacer(Modifier.height(6.dp))
                if (server.running) {
                    if (server.endpoints.isEmpty()) {
                        Text("没有找到可用的网络地址，请检查网络连接", style = CubType.meta, color = colors.warning)
                    }
                    for (endpoint in server.endpoints) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(endpoint, style = CubType.mono, color = colors.text)
                            Spacer(Modifier.width(8.dp))
                            QuietButton("复制", onClick = { copyToClipboard(endpoint) })
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (server.sessions == 0) "还没有设备连接" else "已有 ${server.sessions} 个连接",
                        style = CubType.meta,
                        color = if (server.sessions == 0) colors.textFaint else colors.success,
                    )
                } else {
                    Text(
                        "没有共享时，别的设备连不上这台机器；同步仍可单向进行。",
                        style = CubType.meta,
                        color = colors.textFaint,
                    )
                }
            }
        }
    }
}

@Composable
private fun RootRow(root: String, removable: Boolean, onRemove: () -> Unit) {
    val colors = CubTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceRaised)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(root, style = CubType.mono, color = colors.text, modifier = Modifier.weight(1f), maxLines = 1)
        if (removable) QuietButton("移除", danger = true, onClick = onRemove)
    }
}

@Composable
private fun PeerPanel(state: AppState) {
    val colors = CubTheme.colors

    Panel(
        modifier = Modifier.fillMaxWidth(),
        title = "远端设备",
        subtitle = "填上另一台设备上显示的「HOST:PORT」",
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CubField(
                value = state.peerDraft,
                onValueChange = { state.peerDraft = it },
                modifier = Modifier.weight(1f),
                placeholder = "192.168.1.20:43917",
                isError = state.peerError != null,
            )
            Spacer(Modifier.width(8.dp))
            PrimaryButton(
                text = if (state.connecting) "连接中…" else "连接",
                enabled = !state.connecting,
                onClick = { state.connectPeer(state.peerDraft) },
            )
        }
        state.peerError?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = CubType.meta, color = colors.danger)
        }

        Spacer(Modifier.height(14.dp))
        if (state.peers.isEmpty()) {
            EmptyState(
                "还没有连接任何设备",
                "在另一台设备上打开 CubSync 并开始共享，然后把它的地址填在上面。",
            )
        } else {
            for (peer in state.peers) {
                PeerRoad(state, peer)
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun PeerRoad(state: AppState, peer: PeerSession) {
    val colors = CubTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceRaised)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(colors.success)
            Spacer(Modifier.width(8.dp))
            Text(peer.nodeName, style = CubType.cardTitle, color = colors.text)
            Spacer(Modifier.width(8.dp))
            Text(
                "v${peer.appVersion.ifBlank { "?" }} · 协议 ${peer.protocolVersion}",
                style = CubType.meta,
                color = colors.textFaint,
            )
            Spacer(Modifier.weight(1f))
            QuietButton("断开", onClick = { state.disconnectPeer(peer.backendId) })
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "地址 ${peer.host}:${peer.port} · 暴露 ${Format.count(peer.roots.size, "个文件夹")}",
            style = CubType.meta,
            color = colors.textDim,
        )
        for (root in peer.roots) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(2.dp).height(12.dp).background(colors.outline))
                Spacer(Modifier.width(8.dp))
                Text(root, style = CubType.mono, color = colors.text, maxLines = 1)
            }
        }
    }
}
