package io.github.cubsync.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.cubsync.desktop.AppState
import io.github.cubsync.desktop.CubTheme
import io.github.cubsync.desktop.CubType
import io.github.cubsync.desktop.NoticeKind
import io.github.cubsync.desktop.copyToClipboard
import io.github.cubsync.desktop.pickDirectory
import io.github.cubsync.service.BuildInfo
import io.github.cubsync.service.CapabilityNames
import io.github.cubsync.service.Channels
import io.github.cubsync.service.Format
import io.github.cubsync.service.IconPaths
import io.github.cubsync.service.LinkMode
import io.github.cubsync.service.Peers
import io.github.cubsync.service.Section
import io.github.cubsync.service.ServerState
import io.github.cubsync.service.Side
import io.github.cubsync.service.Words

/**
 * 连接设备 — the prototype's first screen.
 *
 * Two cards side by side and one under them: 连接方式 on the left, 协议协商 on the right,
 * 传输通道 across the bottom. The heading, the hint and the two buttons on the right are
 * the prototype's own lines.
 *
 * The prototype stops there. 服务端（主控）is added as a fourth card, because the protocol
 * this window speaks is a file server and a server with nothing shared has nothing to
 * serve — without it 连接 has nothing to connect to. It is drawn in the same card style and
 * listed in the design note as an addition rather than a reading of the prototype.
 */
@Composable
fun ConnectScreen(state: AppState, server: ServerState) {
    val colors = CubTheme.colors

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LinkCard(state, Modifier.weight(1f))
                NegotiationCard(state, Modifier.weight(1f))
            }
            ChannelsCard(state, server)
            ServerCard(state, server)
        }
    }
    colors.hashCode()
}

// ------------------------------------------------------------------ 连接方式

@Composable
private fun LinkCard(state: AppState, modifier: Modifier) {
    val colors = CubTheme.colors
    var mode by remember { mutableStateOf(LinkMode.ADB) }
    val settings = state.liveSettings

    // Ask once, on the frame the card first appears, so the list is not empty until the
    // person presses 刷新 for no reason.
    androidx.compose.runtime.LaunchedEffect(Unit) { state.refreshAdb() }

    Card(modifier, title = "连接方式", sub = if (io.github.cubsync.service.Adb.available()) "adb 已就绪" else "USB 需要 adb 在 PATH") {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, RoundedCornerShape(8.dp)),
        ) {
            for (option in LinkMode.entries) {
                val active = option == mode
                Box(
                    Modifier
                        .weight(1f)
                        .background(if (active) colors.accent else Color.Transparent)
                        .clickable { mode = option }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        option.label,
                        style = CubType.button,
                        color = if (active) Color.White else colors.textDim,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        if (mode == LinkMode.ADB) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldLabel("USB 设备")
                Spacer(Modifier.weight(1f))
                QuietButton("刷新") { state.refreshAdb() }
            }
            Spacer(Modifier.height(6.dp))
            val ready = state.adbDevices.filter { it.ready }
            if (ready.isEmpty()) {
                Text(
                    state.adbNote ?: "没有已连接的 USB 设备",
                    style = CubType.body,
                    color = colors.textFaint,
                )
            }
            for (device in ready) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(device.label, style = CubType.body, color = colors.text, modifier = Modifier.weight(1f))
                    QuietButton(if (state.connecting) "连接中…" else "连接并协商") {
                        // The phone's port, not this machine's: empty means the protocol
                        // default, which is what a phone shares on unless it was changed.
                        state.connectUsb(device.serial, state.peerPortText.toIntOrNull() ?: 0)
                    }
                }
            }
            state.adbNote?.takeIf { ready.isNotEmpty() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = CubType.tiny, color = colors.textFaint)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "USB 走的是 adb 转发：把设备上的端口（默认 ${settings.sharePort}）映射到本机同名端口，之后与局域网是同一条连接。",
                style = CubType.tiny,
                color = colors.textFaint,
            )
        } else {
            FieldLabel("地址")
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(
                    value = state.peerHost,
                    onValueChange = { state.peerHost = it },
                    modifier = Modifier.weight(2f),
                    placeholder = "192.168.1.20",
                    onSubmit = { state.connectPeerAddress() },
                )
                Field(
                    value = state.peerPortText,
                    onValueChange = { state.peerPortText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = "端口（默认 ${settings.sharePort}）",
                    onSubmit = { state.connectPeerAddress() },
                )
            }
            Spacer(Modifier.height(8.dp))
            PrimaryButton(if (state.connecting) "连接中…" else "连接并协商") { state.connectPeerAddress() }
        }

        state.peerError?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = CubType.tiny, color = colors.danger)
        }
    }
}

// ------------------------------------------------------------------ 协议协商

@Composable
private fun NegotiationCard(state: AppState, modifier: Modifier) {
    val colors = CubTheme.colors
    val peer = state.peers.firstOrNull()
    val negotiated = peer?.capabilities ?: 0L
    val agreed = CapabilityNames.agreedCount(negotiated)
    val total = CapabilityNames.ROWS.count { !CapabilityNames.reserved(it) }

    Card(modifier, title = "协议协商", sub = "v${BuildInfo.PROTOCOL_VERSION} · 双向对等") {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            var index = 0
            while (index < CapabilityNames.ROWS.size) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    for (row in CapabilityNames.ROWS.drop(index).take(4)) {
                        val on = CapabilityNames.agreed(row, negotiated)
                        val reserved = CapabilityNames.reserved(row)
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(99.dp))
                                .background(
                                    if (reserved) Color.Transparent
                                    else if (on) colors.success.copy(alpha = 0.12f)
                                    else Color.Transparent,
                                )
                                .border(
                                    1.dp,
                                    when {
                                        reserved -> colors.outline
                                        on -> colors.success.copy(alpha = 0.35f)
                                        else -> colors.outline
                                    },
                                    RoundedCornerShape(99.dp),
                                )
                                .clickable {
                                    state.notify(
                                        if (reserved) "$row.name 已留位：协议尚未实现这一位"
                                        else if (on) "$row.name 已协商"
                                        else "$row.name 未协商：远端没有这一位",
                                        if (on) NoticeKind.SUCCESS else NoticeKind.INFO,
                                    )
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                row.name,
                                style = CubType.chip,
                                color = when {
                                    reserved -> colors.textFaint.copy(alpha = 0.55f)
                                    on -> colors.success
                                    else -> colors.textFaint
                                },
                            )
                        }
                    }
                }
                index += 4
            }
        }

        Spacer(Modifier.height(14.dp))
        Kv("本机版本 / 远端版本") {
            // No peer is not version zero: an em dash says "nothing to compare with",
            // where 0 read as a build from before the protocol existed.
            val remote = peer?.protocolVersion?.let { "${it * 100}" } ?: DASH
            "${BuildInfo.PROTOCOL_VERSION * 100} / $remote"
        }
        Kv("比对到的能力位") { "$agreed / $total" }
        Kv("v3 老版本远端") { "仅退出双向模式（清晰提示，不错位）" }
    }
}

// ------------------------------------------------------------------ 传输通道

@Composable
private fun ChannelsCard(state: AppState, server: ServerState) {
    val colors = CubTheme.colors
    val selected = state.liveSettings.channelNics
    val nics = remember(state.settingsRevision, state.running) {
        val real = Peers.localNics()
        val chosen = if (selected.isEmpty()) real.map { it.name } else selected
        real.filter { it.name in chosen }
    }
    val adbOn = selected.contains(Channels.USB_ADB_NAME)
    val rate = state.running?.let {
        val elapsed = state.now() - it.startedAtMs
        if (elapsed > 0) Format.rate(it.bytes, elapsed) else DASH
    } ?: DASH

    Card(Modifier.fillMaxWidth(), title = "传输通道", sub = "合计 $rate", plain = true) {
        ChannelRow(
            name = Channels.USB_ADB_NAME,
            address = Channels.USB_ADB_ADDRESS,
            rate = if (adbOn) rate else "0.0 MB/s",
            enabled = adbOn,
            onClick = { state.notify(Channels.ADB_RESERVED, NoticeKind.INFO) },
        )
        for (nic in nics) {
            ChannelRow(
                name = nic.name,
                address = nic.address,
                rate = if (state.peers.isEmpty()) "0.0 MB/s" else rate,
                enabled = true,
                onClick = { state.toggleChannelNic(nic.name) },
            )
        }
        if (nics.isEmpty()) {
            Text(
                "没有读到可用的网卡地址。",
                style = CubType.tiny,
                color = colors.warning,
            )
        }
        Spacer(Modifier.height(6.dp))
        Kv("本机名称") { state.liveSettings.serverNodeName.ifEmpty { Peers.local().name } }
        Kv("服务端") { if (server.running) "端口 ${server.port} · ${server.roots.size} 个目录" else Words.UNCONNECTED }
    }
}

@Composable
private fun ChannelRow(
    name: String,
    address: String,
    rate: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = CubTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            CubIcon(IconPaths.DEVICE, size = 16, tint = if (enabled) colors.accent else colors.textFaint)
            Spacer(Modifier.width(9.dp))
            Text(name, style = CubType.body, color = colors.text)
        }
        Text(address, style = CubType.mono, color = colors.textDim, modifier = Modifier.width(150.dp))
        Text(rate, style = CubType.body, color = colors.text, modifier = Modifier.width(94.dp))
    }
}

// ------------------------------------------------------------------ 服务端（主控）

/**
 * 服务端（主控）— the addition.
 *
 * The prototype's desktop screens do not draw sharing anywhere, but the protocol is a
 * server: without a shared folder the peer that connects sees an empty machine, and
 * 连接设备 would be a screen whose button cannot lead anywhere.
 */
@Composable
private fun ServerCard(state: AppState, server: ServerState) {
    val colors = CubTheme.colors

    Card(
        Modifier.fillMaxWidth(),
        title = "${Words.SERVER}（主控）",
        sub = if (server.running) "等待远端连接" else "选择要共享的文件夹，然后开始",
        actions = {
            if (server.running) {
                QuietButton("停止共享", danger = true) { state.stopServer() }
            } else {
                PrimaryButton("开始共享") {
                    state.startServer(
                        state.liveSettings.sharePort,
                        state.liveSettings.serverNodeName.ifEmpty { Peers.local().name },
                    )
                }
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            QuietButton("添加文件夹…") {
                val picked = pickDirectory("选择要共享的文件夹")
                if (picked != null) state.addRoot(picked)
            }
            Spacer(Modifier.width(6.dp))
            Text("${state.rootDraft.size} 个", style = CubType.tiny, color = colors.textFaint)
        }

        for (root in state.rootDraft) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(io.github.cubsync.service.PathTools.leafName(root), style = CubType.body, color = colors.text)
                    Text(root, style = CubType.monoSmall, color = colors.textFaint)
                }
                QuietButton("移除") { state.removeRoot(root) }
            }
        }

        if (server.running) {
            Spacer(Modifier.height(10.dp))
            FieldLabel("把下面的地址填到另一台设备上")
            for (endpoint in server.endpoints) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(endpoint, style = CubType.mono, color = colors.text, modifier = Modifier.weight(1f))
                    QuietButton("复制") {
                        copyToClipboard(endpoint)
                        state.notify("已复制 $endpoint", NoticeKind.SUCCESS)
                    }
                }
            }
            if (server.endpoints.isEmpty()) {
                Text(
                    "没有读到可用的网卡地址。",
                    style = CubType.tiny,
                    color = colors.warning,
                )
            }
        }
        server.error.takeIf { it.isNotEmpty() }?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = CubType.tiny, color = colors.danger)
        }
    }
}

// ------------------------------------------------------------------ 零件

/** `.card`: a 12px block on `--panel-2`, with an optional heading row. */
@Composable
private fun Card(
    modifier: Modifier,
    title: String,
    sub: String = "",
    plain: Boolean = false,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val colors = CubTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (plain) colors.surface else colors.surfaceRaised)
            .border(1.dp, colors.outline, RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = CubType.cardTitle, color = colors.text)
            if (sub.isNotEmpty()) {
                Spacer(Modifier.weight(1f))
                Text(sub, style = CubType.cardSub, color = colors.textDim)
            }
            actions()
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = CubType.cardSub, color = CubTheme.colors.textDim)
}

/** `.kv`: a label on the left and a value hard against the right. */
@Composable
private fun Kv(key: String, value: () -> String) {
    val colors = CubTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, style = CubType.body, color = colors.textDim, modifier = Modifier.weight(1f))
        Text(value(), style = CubType.body, color = colors.text)
    }
}
