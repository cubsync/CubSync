package io.github.cubsync.desktop.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.cubsync.core.model.CompareMode
import io.github.cubsync.core.model.ConflictPolicy
import io.github.cubsync.core.model.DeletePolicy
import io.github.cubsync.core.model.Endpoint
import io.github.cubsync.core.model.FolderPair
import io.github.cubsync.core.model.SyncConfig
import io.github.cubsync.core.model.SyncDirection
import io.github.cubsync.desktop.AppState
import io.github.cubsync.desktop.CubTheme
import io.github.cubsync.desktop.CubType
import io.github.cubsync.desktop.NoticeKind
import io.github.cubsync.desktop.Section
import io.github.cubsync.desktop.pickDirectory
import io.github.cubsync.service.EndpointId
import io.github.cubsync.service.Format
import io.github.cubsync.service.policySentence
import io.github.cubsync.service.JobRecord
import io.github.cubsync.service.PathTools
import io.github.cubsync.service.PeerSession

/**
 * Where a person describes what should be kept in step.
 *
 * The layout follows the shape of the problem: a list of configurations on the
 * left, and on the right one configuration's policy followed by its folder pairs.
 * Every edit is to a draft, and nothing reaches the disk until 保存 is pressed --
 * an accidental keystroke in a sync tool should not change what the next run does.
 */
@Composable
fun SyncScreen(state: AppState) {
    val colors = CubTheme.colors
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            Section.SYNC.label,
            Section.SYNC.hint,
            actions = {
                QuietButton("导入配置…", onClick = { state.importConfig() })
                Spacer(Modifier.width(6.dp))
                PrimaryButton("新建配置", onClick = {
                    state.saveConfig(state.newConfigDraft())
                    state.goTo(Section.SYNC)
                })
            },
        )
        Row(Modifier.fillMaxSize()) {
            ConfigList(state, Modifier.width(272.dp).fillMaxHeight())
            Box(Modifier.width(1.dp).fillMaxHeight().background(colors.outline))
            val selected = state.selectedConfig
            if (selected == null) {
                Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.TopCenter) {
                    EmptyState(
                        "还没有同步配置",
                        "配置描述「哪些文件夹、往哪个方向、按什么规则」；一个配置可以包含多对文件夹。",
                        action = { PrimaryButton("新建配置", onClick = { state.saveConfig(state.newConfigDraft()) }) },
                    )
                }
            } else {
                ConfigEditor(state, selected, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun ConfigList(state: AppState, modifier: Modifier) {
    val colors = CubTheme.colors
    Column(modifier.padding(vertical = 4.dp)) {
        if (state.configs.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(14.dp)) {
                Text("列表为空", style = CubType.meta, color = colors.textFaint)
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            for (config in state.configs) {
                ConfigRow(
                    config = config,
                    selected = config.configId == state.selectedConfigId,
                    lastRun = state.jobs.firstOrNull { it.configId == config.configId },
                    onClick = { state.selectConfig(config.configId) },
                )
            }
        }
    }
}

@Composable
private fun ConfigRow(config: SyncConfig, selected: Boolean, lastRun: JobRecord?, onClick: () -> Unit) {
    val colors = CubTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.accentSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                config.name.ifBlank { config.configId },
                style = CubType.cardTitle,
                color = if (selected) colors.accent else colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (config.favorite) Text("★", style = CubType.meta, color = colors.warning)
        }
        Spacer(Modifier.height(3.dp))
        Text(
            "${Format.directionShort(config.direction)} · ${Format.count(config.pairs.size, "对")}",
            style = CubType.meta,
            color = colors.textDim,
        )
        Text(
            lastRun?.let { "上次 ${Format.ago(it.result.finishedAtMs)} · ${Format.jobStatus(it.result.status)}" } ?: "从未运行",
            style = CubType.meta,
            color = colors.textFaint,
        )
    }
}

@Composable
private fun ConfigEditor(state: AppState, config: SyncConfig, modifier: Modifier) {
    val colors = CubTheme.colors
    var draft by remember(config) { mutableStateOf(config) }
    val dirty = draft != config

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Panel(
            modifier = Modifier.fillMaxWidth(),
            title = "这个配置做什么",
            actions = {
                if (dirty) {
                    Pill("有未保存的改动", colors.warning)
                    Spacer(Modifier.width(8.dp))
                    QuietButton("放弃改动", onClick = { draft = config })
                    PrimaryButton("保存", onClick = { state.saveConfig(draft) })
                } else {
                    QuietButton("另存为副本", onClick = { state.saveConfig(draft.copy(configId = io.github.cubsync.core.model.Ids.newConfigId(), name = draft.name + " 副本")) })
                    QuietButton("导出…", onClick = { state.exportConfig(draft) })
                    QuietButton("删除", danger = true, onClick = { state.deleteConfig(draft.configId) })
                }
            },
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    FieldLabel("名称")
                    Spacer(Modifier.height(5.dp))
                    CubField(
                        value = draft.name,
                        onValueChange = { draft = draft.copy(name = it) },
                        placeholder = "例如 照片备份",
                    )
                }
                Column(Modifier.width(140.dp)) {
                    FieldLabel("并行度")
                    Spacer(Modifier.height(5.dp))
                    SegmentedChoice(
                        options = listOf(1, 2, 4, 8).map { Choice(it, it.toString()) },
                        selected = draft.parallelism.coerceIn(1, io.github.cubsync.desktop.DesktopSettings.MAX_PARALLELISM),
                        onSelect = { draft = draft.copy(parallelism = it) },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    FieldLabel("同步方向")
                    Spacer(Modifier.height(5.dp))
                    Dropdown(
                        options = SyncDirection.entries
                            .filter { it.supported }
                            .map { Choice(it, Format.direction(it)) },
                        selected = draft.direction,
                        onSelect = { draft = draft.copy(direction = it) },
                    )
                }
                Column(Modifier.weight(1f)) {
                    FieldLabel("比较方式")
                    Spacer(Modifier.height(5.dp))
                    Dropdown(
                        options = CompareMode.entries.map { Choice(it, Format.compare(it)) },
                        selected = draft.compare,
                        onSelect = { draft = draft.copy(compare = it) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    FieldLabel("冲突时")
                    Spacer(Modifier.height(5.dp))
                    Dropdown(
                        options = ConflictPolicy.entries.map { Choice(it, Format.conflict(it)) },
                        selected = draft.conflict,
                        onSelect = { draft = draft.copy(conflict = it) },
                    )
                }
                Column(Modifier.weight(1f)) {
                    FieldLabel("删除时")
                    Spacer(Modifier.height(5.dp))
                    Dropdown(
                        options = DeletePolicy.entries.map { Choice(it, Format.delete(it)) },
                        selected = draft.delete,
                        onSelect = { draft = draft.copy(delete = it) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(policySentence(draft), style = CubType.meta, color = colors.textDim)
        }

        Panel(
            modifier = Modifier.fillMaxWidth(),
            title = "文件夹对",
            subtitle = "每一对是「一台设备上的一个文件夹」对应「另一台设备上的一个文件夹」",
            actions = {
                QuietButton("添加文件夹对", onClick = {
                    draft = draft.copy(pairs = draft.pairs + state.newPairDraft(draft.pairs))
                })
            },
        ) {
            if (draft.pairs.isEmpty()) {
                EmptyState("这个配置还没有文件夹对", "没有文件夹对时运行不会做任何事。")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    draft.pairs.forEachIndexed { index, pair ->
                        val ordered = pair.copy(order = index)
                        PairCard(
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
                                    pairs = draft.pairs
                                        .filterNot { it.pairId == ordered.pairId }
                                        .mapIndexed { i, p -> p.copy(order = i) },
                                )
                            },
                        )
                    }
                }
            }
        }

        RunPanel(state, draft, dirty)
    }
}

@Composable
private fun RunPanel(state: AppState, draft: SyncConfig, dirty: Boolean) {
    val colors = CubTheme.colors
    val running = state.running

    Panel(modifier = Modifier.fillMaxWidth(), title = "运行") {
        if (running != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${running.label}「${running.configName}」", style = CubType.body, color = colors.text)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (running.currentPair.isNotEmpty()) "正在处理 ${running.currentPair}" else "",
                    style = CubType.meta,
                    color = colors.textDim,
                )
                Spacer(Modifier.weight(1f))
                Text(Format.bytes(running.bytes), style = CubType.meta, color = colors.textDim)
                Spacer(Modifier.width(10.dp))
                QuietButton("取消", danger = true, onClick = { state.cancelRun() })
            }
            Spacer(Modifier.height(8.dp))
            ProgressBar(
                fraction = if (running.pairCount == 0) 0f else running.finishedPairs.toFloat() / running.pairCount,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(
                    text = "立即同步",
                    enabled = !dirty,
                    onClick = { state.runConfig(draft, dryRun = false) },
                )
                Spacer(Modifier.width(8.dp))
                QuietButton(
                    "试运行（不写入）",
                    enabled = !dirty,
                    onClick = { state.runConfig(draft, dryRun = true) },
                )
                Spacer(Modifier.weight(1f))
                Text(
                    when {
                        dirty -> "先保存再运行"
                        draft.pairs.isEmpty() -> "还没有文件夹对"
                        else -> "方向：${Format.direction(draft.direction)}"
                    },
                    style = CubType.meta,
                    color = if (dirty) colors.warning else colors.textFaint,
                )
            }
        }
    }
}

@Composable
private fun PairCard(
    state: AppState,
    index: Int,
    pair: FolderPair,
    onChange: (FolderPair) -> Unit,
    onRemove: () -> Unit,
) {
    val colors = CubTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceRaised)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("文件夹对 ${index + 1}", style = CubType.label, color = colors.text)
            Spacer(Modifier.width(10.dp))
            CubField(
                value = pair.name,
                onValueChange = { onChange(pair.copy(name = it)) },
                modifier = Modifier.width(220.dp),
                placeholder = "备注名（可留空）",
            )
            Spacer(Modifier.weight(1f))
            QuietButton("删除", danger = true, onClick = onRemove)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            EndpointEditor(
                state = state,
                title = "左侧",
                endpoint = pair.left,
                onChange = { onChange(pair.copy(left = it)) },
                modifier = Modifier.weight(1f),
            )
            Column(
                Modifier.width(44.dp).padding(top = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (pair.directionOverride?.name?.endsWith("RIGHT") == true) "←" else "⇄",
                    style = CubType.sectionTitle,
                    color = colors.textFaint,
                )
            }
            EndpointEditor(
                state = state,
                title = "右侧",
                endpoint = pair.right,
                onChange = { onChange(pair.copy(right = it)) },
                modifier = Modifier.weight(1f),
            )
        }
        if (sameFolderOnBothSides(pair)) {
            Spacer(Modifier.height(6.dp))
            Text(
                "两侧指向同一个文件夹，运行会被阻止。",
                style = CubType.meta,
                color = colors.danger,
            )
        }
    }
}

@Composable
private fun EndpointEditor(
    state: AppState,
    title: String,
    endpoint: Endpoint,
    onChange: (Endpoint) -> Unit,
    modifier: Modifier,
) {
    val colors = CubTheme.colors
    val kindOptions = remember(state.peers, endpoint.backendId) {
        buildList {
            add(Choice(EndpointId.LOCAL, "本机"))
            for (peer in state.peers) add(Choice(peer.backendId, peer.nodeName))
            if (endpoint.backendId != EndpointId.LOCAL && state.peers.none { it.backendId == endpoint.backendId }) {
                add(Choice(endpoint.backendId, EndpointId(endpoint.backendId).label))
            }
        }
    }
    val peer = state.peerFor(endpoint.backendId)

    Column(modifier) {
        FieldLabel(title)
        Spacer(Modifier.height(5.dp))
        Dropdown(
            options = kindOptions,
            selected = endpoint.backendId,
            placeholder = "选择设备",
            onSelect = { backendId ->
                val nextRoot = when {
                    backendId == EndpointId.LOCAL -> ""
                    else -> state.peerFor(backendId)?.roots?.firstOrNull() ?: ""
                }
                onChange(Endpoint(backendId, nextRoot))
            },
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CubField(
                value = endpoint.rootPath,
                onValueChange = { onChange(endpoint.copy(rootPath = it)) },
                modifier = Modifier.weight(1f),
                placeholder = if (endpoint.backendId == EndpointId.LOCAL) "D:\\Photos" else "/sdcard/DCIM",
            )
            if (endpoint.backendId == EndpointId.LOCAL) {
                Spacer(Modifier.width(6.dp))
                QuietButton(
                    "浏览",
                    onClick = {
                        pickDirectory(endpoint.rootPath.ifBlank { null })
                            ?.let { onChange(endpoint.copy(rootPath = PathTools.normalizeRoot(it))) }
                    },
                )
            }
        }
        if (peer != null) {
            Spacer(Modifier.height(6.dp))
            if (peer.roots.isEmpty()) {
                Text("这台设备没有共享任何文件夹", style = CubType.meta, color = colors.warning)
            } else {
                Text("它共享的文件夹", style = CubType.meta, color = colors.textFaint)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (root in peer.roots.take(3)) {
                        RootChip(
                            root = root,
                            selected = endpoint.rootPath == root,
                            onClick = { onChange(endpoint.copy(rootPath = root)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RootChip(root: String, selected: Boolean, onClick: () -> Unit) {
    val colors = CubTheme.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(if (selected) colors.accentSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(
            PathTools.shorten(root, segments = 2),
            style = CubType.meta,
            color = if (selected) colors.accent else colors.textDim,
            maxLines = 1,
        )
    }
}

private fun sameFolderOnBothSides(pair: FolderPair): Boolean =
    pair.left.backendId == pair.right.backendId &&
        pair.left.rootPath.isNotBlank() &&
        pair.left.rootPath == pair.right.rootPath

/** Files shared with the connect screen, which needs the same wording for a peer. */
internal fun peerLabel(peer: PeerSession?): String = peer?.nodeName ?: "远端"
