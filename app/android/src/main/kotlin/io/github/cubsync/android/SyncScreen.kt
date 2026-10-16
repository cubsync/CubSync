package io.github.cubsync.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.cubsync.core.model.CompareMode
import io.github.cubsync.core.model.ConflictPolicy
import io.github.cubsync.core.model.DeletePolicy
import io.github.cubsync.core.model.Endpoint
import io.github.cubsync.core.model.FolderPair
import io.github.cubsync.core.model.SyncConfig
import io.github.cubsync.core.model.SyncDirection
import io.github.cubsync.service.EndpointId
import io.github.cubsync.service.Format
import io.github.cubsync.service.PathTools
import io.github.cubsync.service.policySentence

/**
 * The list of configurations, and one configuration opened as its own screen.
 *
 * A phone cannot show a list and an editor at once, so editing is a place you go and
 * come back from with the back arrow. Edits stay in a draft until 保存 is pressed,
 * for the same reason as on the desktop: an accidental tap should not change what the
 * next run does.
 */
@Composable
fun SyncListScreen(state: AndroidAppState) {
    val colors = CubTheme.colors

    Column(Modifier.fillMaxWidth().padding(14.dp)) {
        Button(
            onClick = {
                val draft = state.newConfigDraft()
                state.saveConfig(draft)
                state.open(Route.ConfigDetail(draft.configId))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("新建配置")
        }
    }

    if (state.configs.isEmpty()) {
        ScreenBody { EmptyState("还没有同步配置", "配置描述「哪些文件夹、往哪个方向、按什么规则」。") }
        return
    }

    ScreenBody {
        for (config in state.configs) {
            val lastRun = state.jobs.firstOrNull { it.configId == config.configId }
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surface)
                    .clickable { state.open(Route.ConfigDetail(config.configId)) }
                    .padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        config.name.ifBlank { config.configId },
                        style = CubType.cardTitle,
                        color = colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (config.favorite) Text("★", color = colors.warning, style = CubType.meta)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${Format.directionShort(config.direction)} · ${Format.count(config.pairs.size, "对")}",
                    style = CubType.meta,
                    color = colors.textDim,
                )
                Text(
                    lastRun?.let { "上次 ${Format.ago(it.result.finishedAtMs)} · ${Format.jobStatus(it.result.status)}" }
                        ?: "从未运行",
                    style = CubType.meta,
                    color = colors.textFaint,
                )
            }
        }
    }
}

@Composable
fun ConfigDetailScreen(state: AndroidAppState, config: SyncConfig) {
    val colors = CubTheme.colors
    var draft by remember(config) { mutableStateOf(config) }
    val dirty = draft != config

    ScreenBody {
        Panel(title = "这个配置做什么") {
            CubField(
                label = "名称",
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                placeholder = "例如 照片备份",
            )
            Spacer(Modifier.height(10.dp))
            Dropdown(
                label = "同步方向",
                options = SyncDirection.entries.filter { it.supported }
                    .map { Choice(it, Format.direction(it)) },
                selected = draft.direction,
                onSelect = { draft = draft.copy(direction = it) },
            )
            Spacer(Modifier.height(10.dp))
            Dropdown(
                label = "比较方式",
                options = CompareMode.entries.map { Choice(it, Format.compare(it)) },
                selected = draft.compare,
                onSelect = { draft = draft.copy(compare = it) },
            )
            Spacer(Modifier.height(10.dp))
            Dropdown(
                label = "冲突时",
                options = ConflictPolicy.entries.map { Choice(it, Format.conflict(it)) },
                selected = draft.conflict,
                onSelect = { draft = draft.copy(conflict = it) },
            )
            Spacer(Modifier.height(10.dp))
            Dropdown(
                label = "删除时",
                options = DeletePolicy.entries.map { Choice(it, Format.delete(it)) },
                selected = draft.delete,
                onSelect = { draft = draft.copy(delete = it) },
            )
            Spacer(Modifier.height(10.dp))
            Text("并行度", style = CubType.meta, color = colors.textFaint)
            Spacer(Modifier.height(4.dp))
            ChoiceChips(
                options = listOf(1, 2, 4, 8).map { Choice(it, it.toString()) },
                selected = draft.parallelism.coerceIn(1, 8),
                onSelect = { draft = draft.copy(parallelism = it) },
            )
            Spacer(Modifier.height(10.dp))
            Text(policySentence(draft), style = CubType.meta, color = colors.textDim)
        }

        Panel(
            title = "文件夹对",
            subtitle = "每一对是「一台设备上的一个文件夹」对应「另一台设备上的一个文件夹」",
        ) {
            if (draft.pairs.isEmpty()) {
                EmptyState("还没有文件夹对", "没有文件夹对时运行不会做任何事。")
            }
            draft.pairs.forEachIndexed { index, pair ->
                val ordered = pair.copy(order = index)
                PairEditor(
                    state = state,
                    index = index,
                    pair = ordered,
                    onChange = { changed ->
                        draft = draft.copy(
                            pairs = draft.pairs.map { if (it.pairId == ordered.pairId) changed else it },
                        )
                    },
                    onRemove = {
                        draft = draft.copy(
                            pairs = draft.pairs.filterNot { it.pairId == ordered.pairId }
                                .mapIndexed { i, p -> p.copy(order = i) },
                        )
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { draft = draft.copy(pairs = draft.pairs + state.newPairDraft(draft.pairs)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("添加文件夹对")
            }
        }

        Panel(title = "运行") {
            val running = state.running
            if (running != null) {
                Text("${running.label}「${running.configName}」", style = CubType.body, color = colors.text)
                if (running.currentPair.isNotEmpty()) {
                    Text("正在处理 ${running.currentPair}", style = CubType.meta, color = colors.textDim)
                }
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = {
                        if (running.pairCount == 0) 0f else running.finishedPairs.toFloat() / running.pairCount
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { state.cancelRun() }) {
                    Text("取消", color = colors.danger)
                }
            } else {
                Button(
                    enabled = !dirty && draft.pairs.isNotEmpty(),
                    onClick = { state.runConfig(draft, dryRun = false) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("立即同步")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    enabled = !dirty && draft.pairs.isNotEmpty(),
                    onClick = { state.runConfig(draft, dryRun = true) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("试运行（不写入）")
                }
                if (dirty) {
                    Spacer(Modifier.height(6.dp))
                    Text("先保存再运行", style = CubType.meta, color = colors.warning)
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = dirty,
                onClick = { state.saveConfig(draft) },
                modifier = Modifier.weight(1f),
            ) {
                Text("保存")
            }
            OutlinedButton(
                enabled = dirty,
                onClick = { draft = config },
                modifier = Modifier.weight(1f),
            ) {
                Text("放弃改动")
            }
        }

        TextButton(onClick = { state.deleteConfig(draft.configId) }) {
            Text("删除这个配置", color = colors.danger)
        }
    }
}

@Composable
private fun PairEditor(
    state: AndroidAppState,
    index: Int,
    pair: FolderPair,
    onChange: (FolderPair) -> Unit,
    onRemove: () -> Unit,
) {
    val colors = CubTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceRaised)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("文件夹对 ${index + 1}", style = CubType.label, color = colors.text)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRemove) { Text("删除", style = CubType.meta, color = colors.danger) }
        }
        CubField(
            label = "备注名（可留空）",
            value = pair.name,
            onValueChange = { onChange(pair.copy(name = it)) },
        )
        Spacer(Modifier.height(10.dp))
        EndpointEditor(state, "左侧", pair.left) { onChange(pair.copy(left = it)) }
        Spacer(Modifier.height(10.dp))
        EndpointEditor(state, "右侧", pair.right) { onChange(pair.copy(right = it)) }
        if (pair.left.backendId == pair.right.backendId &&
            pair.left.rootPath.isNotBlank() &&
            pair.left.rootPath == pair.right.rootPath
        ) {
            Spacer(Modifier.height(6.dp))
            Text("两侧指向同一个文件夹，运行会被阻止。", style = CubType.meta, color = colors.danger)
        }
    }
}

@Composable
private fun EndpointEditor(
    state: AndroidAppState,
    title: String,
    endpoint: Endpoint,
    onChange: (Endpoint) -> Unit,
) {
    val colors = CubTheme.colors
    val kindOptions = buildList {
        add(Choice(EndpointId.LOCAL, "本机"))
        for (peer in state.peers) add(Choice(peer.backendId, peer.nodeName))
        if (endpoint.backendId != EndpointId.LOCAL && state.peers.none { it.backendId == endpoint.backendId }) {
            add(Choice(endpoint.backendId, EndpointId(endpoint.backendId).label))
        }
    }
    val peer = state.peerFor(endpoint.backendId)

    Column(Modifier.fillMaxWidth()) {
        Dropdown(
            label = "$title · 设备",
            options = kindOptions,
            selected = endpoint.backendId,
            onSelect = { backendId ->
                val nextRoot = if (backendId == EndpointId.LOCAL) {
                    state.localRoots().firstOrNull().orEmpty()
                } else {
                    state.peerFor(backendId)?.roots?.firstOrNull().orEmpty()
                }
                onChange(Endpoint(backendId, nextRoot))
            },
        )
        Spacer(Modifier.height(8.dp))
        CubField(
            label = "$title · 文件夹",
            value = endpoint.rootPath,
            onValueChange = { onChange(endpoint.copy(rootPath = it)) },
            placeholder = if (endpoint.backendId == EndpointId.LOCAL) "/sdcard/DCIM" else "/srv/photos",
        )
        val suggestions = if (peer != null) peer.roots else state.localRoots()
        if (suggestions.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (peer != null) "这台设备共享的文件夹" else "这台设备上常见的目录",
                style = CubType.meta,
                color = colors.textFaint,
            )
            Spacer(Modifier.height(4.dp))
            ChoiceChips(
                options = suggestions.take(6).map { Choice(it, PathTools.shorten(it, segments = 2)) },
                selected = endpoint.rootPath,
                onSelect = { onChange(endpoint.copy(rootPath = it)) },
            )
        }
    }
}
