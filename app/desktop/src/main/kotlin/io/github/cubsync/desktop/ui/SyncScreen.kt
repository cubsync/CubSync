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
import androidx.compose.material3.HorizontalDivider
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
import io.github.cubsync.core.model.CompareMode
import io.github.cubsync.core.model.ConflictPolicy
import io.github.cubsync.core.model.DeletePolicy
import io.github.cubsync.core.model.FolderPair
import io.github.cubsync.core.model.SyncConfig
import io.github.cubsync.core.model.SyncDirection
import io.github.cubsync.desktop.AppState
import io.github.cubsync.desktop.CubTheme
import io.github.cubsync.desktop.CubType
import io.github.cubsync.desktop.NoticeKind
import io.github.cubsync.desktop.SyncLayer
import io.github.cubsync.desktop.pickDirectory
import io.github.cubsync.service.Format
import io.github.cubsync.service.PairPreview
import io.github.cubsync.service.Words
import io.github.cubsync.service.policySentence

/**
 * 同步: the configuration list, the configuration itself, the preview, and the run.
 *
 * The design puts these four layers inside the one section, and the desktop has room to
 * show the list beside them instead of pushing and popping — the layers are the same
 * either way, which is what the consistency table actually requires.
 */
@Composable
fun SyncScreen(state: AppState) {
    val colors = CubTheme.colors
    Row(Modifier.fillMaxSize().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        ConfigList(state, Modifier.width(280.dp).fillMaxHeight())
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val config = state.selectedConfig
            when {
                config == null -> EmptyState(
                    "还没有同步配置",
                    "一个配置是一组文件夹对加一套策略。",
                    action = { PrimaryButton("新建") { state.saveConfig(state.newConfigDraft()) } },
                )

                state.syncLayer == SyncLayer.PREVIEW -> PreviewPanel(state)
                else -> ConfigDetail(state, config)
            }
        }
    }
    colors.hashCode()
}

/**
 * 同步's `.chead .acts`: the one chip that says which configuration is open.
 *
 * Not a button: the prototype prints `配置：照片库 · 4 对 · 已保存` there, and it is the only
 * thing on that row.
 */
@Composable
fun SyncHeadActions(state: AppState) {
    val config = state.selectedConfig ?: return
    Chip("配置：${config.name} · ${config.pairs.size} 对", tone = CubTheme.colors.accent)
}

// ------------------------------------------------------------------- list

@Composable
private fun ConfigList(state: AppState, modifier: Modifier) {
    val colors = CubTheme.colors
    Panel(
        modifier,
        title = "配置",
        subtitle = "收藏 · 双击重命名",
        actions = {
            QuietButton("新建") { state.saveConfig(state.newConfigDraft()) }
            QuietButton("另存为") { state.selectedConfig?.let { state.duplicateConfig(it) } }
            QuietButton("导入") { state.importConfig() }
        },
    ) {
        Field(
            value = state.syncSearch,
            onValueChange = { state.syncSearch = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "搜索配置…",
            style = CubType.meta,
        )
        Spacer(Modifier.height(8.dp))

        for (config in state.matchingConfigs()) {
            val selected = config.configId == state.selectedConfigId
            val last = state.jobs.firstOrNull { it.configId == config.configId }
            ListLine(
                title = config.name,
                detail = buildString {
                    append("${Format.directionShort(config.direction)} · ${config.pairs.size} 对")
                    append(if (last != null) " · ${Format.ago(last.result.finishedAtMs)}" else " · 从未运行")
                },
                selected = selected,
                onClick = { state.selectConfig(config.configId); state.syncLayer = SyncLayer.DETAIL },
                trailing = {
                    QuietButton("导出") { state.exportConfig(config) }
                    QuietButton("删除", danger = true) { state.deleteConfig(config.configId) }
                },
            )
        }
        if (state.matchingConfigs().isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("没有匹配的配置。", style = CubType.meta, color = colors.textFaint)
        }
    }
}

// ------------------------------------------------------------------ detail

/** 配置详情: the policy, the folder pairs, the filter. */
@Composable
private fun ConfigDetail(state: AppState, config: SyncConfig) {
    val colors = CubTheme.colors
    var draft by remember(config.configId, config.name) { mutableStateOf(config) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("配置：${draft.name} · ${draft.pairs.size} 对", tone = colors.accent)
            Spacer(Modifier.width(8.dp))
            Chip(policySentence(draft), tone = colors.textDim)
            Spacer(Modifier.weight(1f))
            QuietButton("预览") { state.saveConfig(draft); state.previewConfig(draft) }
            Spacer(Modifier.width(6.dp))
            QuietButton("试运行") { state.saveConfig(draft); state.runConfig(draft, dryRun = true) }
            Spacer(Modifier.width(6.dp))
            PrimaryButton("立即同步") { state.saveConfig(draft); state.runConfig(draft, dryRun = false) }
        }

        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                PolicyPanel(state, draft) { draft = it }
                Spacer(Modifier.height(12.dp))
                PairsPanel(state, draft) { draft = it }
                Spacer(Modifier.height(12.dp))
                FilterPanel(state, draft) { draft = it }
            }
        }
    }
}

/** 策略: direction, comparison, conflict, deletion, parallelism, and the derived summary. */
@Composable
private fun PolicyPanel(state: AppState, draft: SyncConfig, onChange: (SyncConfig) -> Unit) {
    val colors = CubTheme.colors
    Panel(Modifier.fillMaxWidth(), title = "策略", subtitle = policySentence(draft)) {
        SettingRow("同步方向", Format.direction(draft.direction)) {
            Segmented(
                options = SyncDirection.entries.toList(),
                selected = draft.direction,
                label = { Format.directionShort(it) },
                onSelect = { onChange(draft.copy(direction = it)) },
            )
        }
        SettingRow("比较方式", Format.compare(draft.compare)) {
            Segmented(
                options = CompareMode.entries.toList(),
                selected = draft.compare,
                label = { Format.compare(it) },
                onSelect = { onChange(draft.copy(compare = it)) },
            )
        }
        SettingRow("冲突时", Format.conflict(draft.conflict)) {
            Segmented(
                options = ConflictPolicy.entries.toList(),
                selected = draft.conflict,
                label = { Format.conflict(it) },
                onSelect = { onChange(draft.copy(conflict = it)) },
            )
        }
        SettingRow("删除时", Format.delete(draft.delete)) {
            Segmented(
                options = DeletePolicy.entries.toList(),
                selected = draft.delete,
                label = { Format.delete(it) },
                onSelect = { onChange(draft.copy(delete = it)) },
            )
        }
        SettingRow("并行度", "${draft.parallelism} 个文件同时传输") {
            Segmented(
                options = listOf(1, 2, 4, 8),
                selected = draft.parallelism.coerceIn(1, 8),
                label = { it.toString() },
                onSelect = { onChange(draft.copy(parallelism = it)) },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(policySentence(draft), style = CubType.meta, color = colors.textFaint)
    }
}

/** 文件夹对: one row per pair, with both sides editable. */
@Composable
private fun PairsPanel(state: AppState, draft: SyncConfig, onChange: (SyncConfig) -> Unit) {
    val colors = CubTheme.colors
    Panel(
        Modifier.fillMaxWidth(),
        title = "文件夹对",
        subtitle = "${draft.pairs.size} 对",
        actions = {
            QuietButton("添加一对") {
                onChange(draft.copy(pairs = draft.pairs + state.newPairDraft(draft.pairs)))
            }
        },
    ) {
        if (draft.pairs.isEmpty()) {
            Text(
                "还没有文件夹对。一对是「这一侧的一个目录」和「那一侧的一个目录」。",
                style = CubType.meta,
                color = colors.textFaint,
            )
        }
        for ((index, pair) in draft.pairs.withIndex()) {
            PairRow(
                state = state,
                pair = pair,
                onChange = { updated ->
                    val next = draft.pairs.toMutableList()
                    next[index] = updated
                    onChange(draft.copy(pairs = next))
                },
                onRemove = { onChange(draft.copy(pairs = draft.pairs.filterNot { it.pairId == pair.pairId })) },
            )
        }
    }
}

@Composable
private fun PairRow(
    state: AppState,
    pair: FolderPair,
    onChange: (FolderPair) -> Unit,
    onRemove: () -> Unit,
) {
    val colors = CubTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceRaised)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(
                value = pair.name,
                onValueChange = { onChange(pair.copy(name = it)) },
                modifier = Modifier.weight(1f),
                placeholder = "这一对的备注名",
                style = CubType.meta,
            )
            Spacer(Modifier.width(6.dp))
            Toggle(pair.enabled) { onChange(pair.copy(enabled = it)) }
            Spacer(Modifier.width(6.dp))
            QuietButton("移除", danger = true, onClick = onRemove)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                GroupLabel(Words.LOCAL)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(
                        value = pair.left.rootPath,
                        onValueChange = { onChange(pair.copy(left = pair.left.copy(rootPath = it))) },
                        modifier = Modifier.weight(1f),
                        placeholder = "本机目录",
                        style = CubType.meta,
                    )
                    Spacer(Modifier.width(4.dp))
                    QuietButton("选择…") {
                        val picked = pickDirectory("选择${Words.LOCAL}目录")
                        if (picked != null) onChange(pair.copy(left = pair.left.copy(rootPath = picked)))
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                GroupLabel(Words.REMOTE)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(
                        value = pair.right.rootPath,
                        onValueChange = { onChange(pair.copy(right = pair.right.copy(rootPath = it))) },
                        modifier = Modifier.weight(1f),
                        placeholder = "远端目录",
                        style = CubType.meta,
                    )
                    Spacer(Modifier.width(4.dp))
                    PeerFolderPicker(state, pair) { path, backendId ->
                        onChange(pair.copy(right = pair.right.copy(rootPath = path, backendId = backendId)))
                    }
                }
            }
        }
    }
    colors.hashCode()
}

/** A menu of the folders the connected peers expose, so nothing has to be typed blind. */
@Composable
private fun PeerFolderPicker(state: AppState, pair: FolderPair, onPick: (String, String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        QuietButton("远端目录…") { open = !open }
        if (open) {
            FloatingCard(Modifier.padding(top = 30.dp), width = 300) {
                FloatingHeader("远端可访问的目录", onClose = { open = false })
                if (state.peers.isEmpty()) {
                    Text("先到连接页连上一台设备。", style = CubType.meta, color = CubTheme.colors.textFaint)
                }
                for (peer in state.peers) {
                    GroupLabel(peer.nodeName)
                    for (root in peer.roots) {
                        ListLine(root, peer.host, onClick = { onPick(root, peer.backendId); open = false })
                    }
                }
            }
        }
    }
    pair.hashCode()
}

/** 过滤: the exclude list, which is the only filter that has a screen of its own today. */
@Composable
private fun FilterPanel(state: AppState, draft: SyncConfig, onChange: (SyncConfig) -> Unit) {
    val colors = CubTheme.colors
    var text by remember(draft.configId) { mutableStateOf(draft.globalFilter.globs.joinToString("\n")) }

    Panel(Modifier.fillMaxWidth(), title = Words.FILTER, subtitle = "先排除黑名单，再套白名单；排除优先于包含") {
        Text("排除（每行一条，支持 * ? ** [...]）", style = CubType.meta, color = colors.textFaint)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(88.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surfaceSunken)
                .border(1.dp, colors.outlineStrong, RoundedCornerShape(8.dp))
                .padding(8.dp),
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = CubType.meta.copy(color = colors.text),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            QuietButton("应用过滤") {
                val patterns = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                onChange(draft.copy(globalFilter = draft.globalFilter.copy(globs = patterns, enabled = patterns.isNotEmpty())))
                state.notify("已应用 ${patterns.size} 条排除规则", NoticeKind.SUCCESS)
            }
            Chip("${draft.globalFilter.globs.size} 条", tone = colors.textDim)
        }
    }
}

// ----------------------------------------------------------------- preview

/** 预览: what would happen, counted from the plan rather than from a second opinion. */
@Composable
private fun PreviewPanel(state: AppState) {
    val colors = CubTheme.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("预览", style = CubType.cardTitle, color = colors.text)
            Spacer(Modifier.weight(1f))
            QuietButton("返回详情") { state.syncLayer = SyncLayer.DETAIL }
        }

        val preview = state.preview
        when {
            state.previewing -> EmptyState("正在比较…", "这一遍只读，不写任何东西。")
            preview == null -> EmptyState("还没有预览", "点「预览」先看看会发生什么。")
            else -> {
                val totals = preview.totals
                Panel(Modifier.fillMaxWidth(), title = "差异汇总", subtitle = totals.sentence()) {
                    KeyValueRow("将写入", Format.bytes(totals.bytes))
                    KeyValueRow("新增", "${totals.create} 项")
                    KeyValueRow("更新", "${totals.update} 项")
                    KeyValueRow("删除", "${totals.delete} 项")
                    KeyValueRow("冲突", "${totals.conflict} 项")
                    KeyValueRow("跳过", "${totals.skip} 项")
                    if (totals.total == 0) {
                        Spacer(Modifier.height(6.dp))
                        Text("两边已经一致，这一遍什么都不用做。", style = CubType.meta, color = colors.success)
                    }
                }
                Spacer(Modifier.height(12.dp))
                for (pair in preview.pairs) {
                    Panel(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        title = pair.pairName.ifEmpty { pair.pairId },
                        subtitle = pair.sentence(),
                    ) {
                        if (pair.blockReason.isNotEmpty()) {
                            Text(pair.blockReason, style = CubType.meta, color = colors.danger)
                        } else if (pair.total == 0) {
                            Text("这一对已经一致。", style = CubType.meta, color = colors.textFaint)
                        }
                    }
                }
                if (preview.blocked.isNotEmpty()) {
                    Panel(Modifier.fillMaxWidth()) {
                        Text(
                            "${preview.blocked.size} 对被拦下了，原因是路径重叠或方向不允许。修好之后才能同步。",
                            style = CubType.meta,
                            color = colors.danger,
                        )
                    }
                }
            }
        }
    }
}
