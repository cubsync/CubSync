package io.github.cubsync.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.cubsync.service.Format
import io.github.cubsync.service.ServerState

/**
 * The screen that has to work before anything else can.
 *
 * Both halves are the same shape — a state, a list of folders, one button that flips
 * the state — because that is what the two halves are. The address a person reads out
 * is shown in full rather than shortened, because it is going to be typed somewhere
 * else.
 */
@Composable
fun ConnectScreen(state: AndroidAppState) {
    val server = state.serverState
    ScreenBody {
        ServerPanel(state, server)
        PeerPanel(state)
    }
}

@Composable
private fun ServerPanel(state: AndroidAppState, server: ServerState) {
    val colors = CubTheme.colors
    var portText by remember(server.port) { mutableStateOf(if (server.port > 0) server.port.toString() else "") }
    var newRoot by remember { mutableStateOf("") }

    Panel(
        title = "本机服务端",
        subtitle = if (server.running) {
            "正在端口 ${server.port} 上共享 ${server.roots.size} 个文件夹"
        } else {
            "选择要共享给别的设备的文件夹"
        },
    ) {
        SectionTitle("共享的文件夹")
        Spacer(Modifier.height(6.dp))
        if (state.rootDraft.isEmpty()) {
            Text("还没有选择文件夹", style = CubType.meta, color = colors.textFaint)
        }
        for (root in state.rootDraft) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(root, style = CubType.mono, color = colors.text, modifier = Modifier.weight(1f), maxLines = 1)
                if (!server.running) {
                    TextButton(onClick = { state.removeRoot(root) }) {
                        Text("移除", style = CubType.meta, color = colors.danger)
                    }
                }
            }
        }

        if (!server.running) {
            Spacer(Modifier.height(6.dp))
            SectionTitle("这台设备上常见的目录")
            Spacer(Modifier.height(6.dp))
            ChoiceChips(
                options = state.localRoots().map { Choice(it, it.removePrefix("/sdcard/")) },
                selected = newRoot,
                onSelect = { chosen ->
                    newRoot = chosen
                    state.addRoot(chosen)
                },
            )
            Spacer(Modifier.height(8.dp))
            CubField(
                label = "或者直接填路径",
                value = newRoot,
                onValueChange = { newRoot = it },
                placeholder = "/sdcard/DCIM",
            )
            Spacer(Modifier.height(6.dp))
            CubField(
                label = "端口",
                value = portText,
                onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
                placeholder = "43917",
                keyboard = androidx.compose.ui.text.input.KeyboardType.Number,
            )
        }

        Spacer(Modifier.height(12.dp))
        if (server.running) {
            SectionTitle("别的设备填这个地址")
            Spacer(Modifier.height(4.dp))
            if (server.endpoints.isEmpty()) {
                Text("没有找到可用的网络地址，请检查是否连上了 Wi-Fi", style = CubType.meta, color = colors.warning)
            }
            for (endpoint in server.endpoints) {
                Text(endpoint, style = CubType.mono, color = colors.text)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (server.sessions == 0) "还没有设备连接" else "已有 ${server.sessions} 个连接",
                style = CubType.meta,
                color = if (server.sessions == 0) colors.textFaint else colors.success,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = { state.stopServer() }) { Text("停止共享") }
        } else {
            Button(
                enabled = state.rootDraft.isNotEmpty(),
                onClick = { state.startServer(portText.toIntOrNull() ?: 0, "CubSync-Android") },
            ) {
                Text("开始共享")
            }
            if (state.rootDraft.isEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("先选一个要共享的文件夹", style = CubType.meta, color = colors.textFaint)
            }
        }
    }
}

@Composable
private fun PeerPanel(state: AndroidAppState) {
    val colors = CubTheme.colors

    Panel(title = "远端设备", subtitle = "填上另一台设备上显示的 HOST:PORT") {
        CubField(
            label = "地址",
            value = state.peerDraft,
            onValueChange = {
                state.peerDraft = it
                state.peerError = null
            },
            placeholder = "192.168.1.10:43917",
            isError = state.peerError != null,
        )
        state.peerError?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = CubType.meta, color = colors.danger)
        }
        Spacer(Modifier.height(8.dp))
        Button(enabled = !state.connecting, onClick = { state.connectPeer(state.peerDraft) }) {
            Text(if (state.connecting) "连接中…" else "连接")
        }

        Spacer(Modifier.height(12.dp))
        if (state.peers.isEmpty()) {
            EmptyState("还没有连接任何设备", "在另一台设备上打开 CubSync 并开始共享，然后把它的地址填在上面。")
        } else {
            for (peer in state.peers) {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(peer.nodeName, style = CubType.cardTitle, color = colors.text)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "v${peer.appVersion.ifBlank { "?" }} · 协议 ${peer.protocolVersion}",
                        style = CubType.meta,
                        color = colors.textFaint,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { state.disconnectPeer(peer.backendId) }) {
                        Text("断开", style = CubType.meta, color = colors.textDim)
                    }
                }
                Text(
                    "${peer.host}:${peer.port} · ${Format.count(peer.roots.size, "个文件夹")}",
                    style = CubType.meta,
                    color = colors.textDim,
                )
                for (root in peer.roots) {
                    Text("  $root", style = CubType.mono, color = colors.textDim)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "两台设备都可以只连不收：不共享也能把文件送出去。",
                style = CubType.meta,
                color = colors.textFaint,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
