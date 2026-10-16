package io.github.cubsync.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import io.github.cubsync.service.Format
import io.github.cubsync.service.PathTools
import io.github.cubsync.service.Words
import io.github.cubsync.service.policySentence

/**
 * 同步, in four layers: the list, the configuration, the preview, and the run.
 *
 * The phone presses the last three inside this tab and comes back with `←`, which is what
 * the design asks for — the bottom bar keeps 同步 lit throughout, so no screen is ever
 * without a parent.
 */
@Composable
fun SyncListScreen(state: AndroidAppState) {
    val colors = CubTheme.colors
    var search by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Field(search, { search = it }, Modifier.weight(1f), placeholder = "搜索配置…")
            Spacer(Modifier.width(8.dp))
            PrimaryButton("新建") { state.saveConfig(state.newConfigDraft()) }
        }
        Spacer(Modifier.height(10.dp))

        if (state.configs.isEmpty()) {
            EmptyState(
                "还没有同步配置",
                "一个配置是一组文件夹对加一套策略。",
                action = { PrimaryButton("新建") { state.saveConfig(state.newConfigDraft()) } },
            )
            return@Column
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            for (config in state.matchingConfigs(search)) {
                val last = state.jobs.firstOrNull { it.configId == config.configId }
                Panel(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    ListRow(
                        title = config.name,
                        detail = "${Format.directionShort(config.direction)} · ${config.pairs.size} 对 · " +
                            if (last != null) Format.ago(last.result.finishedAtMs) else "从未运行",
                        onClick = {
                            state.selectedConfigId = config.configId
                            state.open(Route.Config(config.configId))
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton("立即同步") { state.runConfig(config, dryRun = false) }
                        QuietButton("预览") { state.previewConfig(config) }
                        QuietButton("导出") { state.exportConfig(config) }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietButton("另存为") { state.selectedConfig?.let { state.duplicateConfig(it) } }
                QuietButton("删除选中的", danger = true) {
                    state.selectedConfigId?.let { state.deleteConfig(it) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 配置详情: the policy, the pairs, the filter. */
@Composable
fun ConfigScreen(state: AndroidAppState, configId: String) {
    val colors = CubTheme.colors
    val saved = state.configs.firstOrNull { it.configId == configId } ?: run {
        state.back()
        return
    }
    var draft by remember(configId, saved.name) { mutableStateOf(saved) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Panel(Modifier.fillMaxWidth(), title = draft.name, subtitle = policySentence(draft)) {
            Field(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "配置名",
            )
        }

        Spacer(Modifier.height(12.dp))
        Panel(Modifier.fillMaxWidth(), title = "策略", subtitle = "同一套策略对所有文件夹对生效") {
            for (direction in SyncDirection.entries) {
                val active = draft.direction == direction
                ListRow(
                    title = Format.direction(direction),
                    tint = if (active) colors.accent else null,
                    trailing = { if (active) Chip("使用中", tone = colors.accent) },
                    onClick = { draft = draft.copy(direction = direction) },
                )
            }
            Spacer(Modifier.height(8.dp))
            GroupLabel("比较方式")
            for (mode in CompareMode.entries) {
                val active = draft.compare == mode
                ListRow(
                    title = Format.compare(mode),
                    tint = if (active) colors.accent else null,
                    onClick = { draft = draft.copy(compare = mode) },
                )
            }
            Spacer(Modifier.height(8.dp))
            GroupLabel("冲突时")
            for (policy in ConflictPolicy.entries) {
                val active = draft.conflict == policy
                ListRow(
                    title = Format.conflict(policy),
                    tint = if (active) colors.accent else null,
                    onClick = { draft = draft.copy(conflict = policy) },
                )
            }
            Spacer(Modifier.height(8.dp))
            GroupLabel("删除时")
            for (policy in DeletePolicy.entries) {
                val active = draft.delete == policy
                ListRow(
                    title = Format.delete(policy),
                    tint = if (active) colors.accent else null,
                    onClick = { draft = draft.copy(delete = policy) },
                )
            }
            Spacer(Modifier.height(8.dp))
            SettingRow("并行度", "${draft.parallelism} 个文件同时传输") {
                StepperField(
                    value = draft.parallelism,
                    onChange = { draft = draft.copy(parallelism = it.coerceIn(1, 8)) },
                    canDecrease = draft.parallelism > 1,
                    canIncrease = draft.parallelism < 8,
                    fieldWidth = 26,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Panel(
            Modifier.fillMaxWidth(),
            title = "文件夹对",
            subtitle = "${draft.pairs.size} 对",
            actions = { QuietButton("添加") { draft = draft.copy(pairs = draft.pairs + state.newPairDraft(draft.pairs)) } },
        ) {
            if (draft.pairs.isEmpty()) {
                Text("还没有文件夹对。一对是「这一侧的一个目录」和「那一侧的一个目录」。", style = CubType.meta, color = colors.textFaint)
            }
            for ((index, pair) in draft.pairs.withIndex()) {
                PairCard(state, pair, onRemove = {
                    draft = draft.copy(pairs = draft.pairs.filterNot { it.pairId == pair.pairId })
                }) { updated ->
                    val next = draft.pairs.toMutableList()
                    next[index] = updated
                    draft = draft.copy(pairs = next)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Panel(Modifier.fillMaxWidth(), title = Words.FILTER, subtitle = "先排除黑名单，再套白名单；排除优先于包含") {
            var exclude by remember(draft.configId) { mutableStateOf(draft.globalFilter.globs.joinToString("\n")) }
            Text("排除（每行一条，支持 * ? ** [...]）", style = CubType.meta, color = colors.textFaint)
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(colors.surfaceSunken)
                    .border(1.dp, colors.outlineStrong, RoundedCornerShape(9.dp))
                    .padding(8.dp),
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = exclude,
                    onValueChange = { exclude = it },
                    textStyle = CubType.meta.copy(color = colors.text),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                QuietButton("应用过滤") {
                    val patterns = exclude.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    draft = draft.copy(
                        globalFilter = draft.globalFilter.copy(globs = patterns, enabled = patterns.isNotEmpty()),
                    )
                    state.notify("已应用 ${patterns.size} 条排除规则", NoticeKind.SUCCESS)
                }
                Spacer(Modifier.width(8.dp))
                Chip("${draft.globalFilter.globs.size} 条", tone = colors.textDim)
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("保存") {
                state.saveConfig(draft)
                state.back()
            }
            QuietButton("试运行") { state.saveConfig(draft); state.runConfig(draft, dryRun = true) }
            QuietButton("立即同步") { state.saveConfig(draft); state.runConfig(draft, dryRun = false) }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** One folder pair, editable in place. */
@Composable
private fun PairCard(
    state: AndroidAppState,
    pair: FolderPair,
    onRemove: () -> Unit,
    onChange: (FolderPair) -> Unit,
) {
    val colors = CubTheme.colors
    var pickingLeft by remember(pair.pairId) { mutableStateOf(false) }
    var pickingRight by remember(pair.pairId) { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(12.dp))
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
            Spacer(Modifier.width(8.dp))
            Toggle(pair.enabled) { onChange(pair.copy(enabled = it)) }
            QuietButton("移除", danger = true, onClick = onRemove)
        }

        Spacer(Modifier.height(8.dp))
        GroupLabel(Words.LOCAL)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(
                value = pair.left.rootPath,
                onValueChange = { onChange(pair.copy(left = pair.left.copy(rootPath = it))) },
                modifier = Modifier.weight(1f),
                placeholder = "本机目录",
                style = CubType.meta,
            )
            Spacer(Modifier.width(6.dp))
            QuietButton("选") { pickingLeft = !pickingLeft }
        }
        if (pickingLeft) {
            for (root in state.localRoots().take(6)) {
                ListRow(
                    title = PathTools.leafName(root).ifEmpty { root },
                    detail = root,
                    onClick = {
                        onChange(pair.copy(left = pair.left.copy(rootPath = root, backendId = io.github.cubsync.service.EndpointId.LOCAL)))
                        pickingLeft = false
                    },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        GroupLabel(Words.REMOTE)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(
                value = pair.right.rootPath,
                onValueChange = { onChange(pair.copy(right = pair.right.copy(rootPath = it))) },
                modifier = Modifier.weight(1f),
                placeholder = "远端目录",
                style = CubType.meta,
            )
            Spacer(Modifier.width(6.dp))
            QuietButton("选") { pickingRight = !pickingRight }
        }
        if (pickingRight) {
            if (state.peers.isEmpty()) {
                Text("先到连接页连上一台设备。", style = CubType.meta, color = colors.textFaint, modifier = Modifier.padding(6.dp))
            }
            for (peer in state.peers) {
                for (root in peer.roots) {
                    ListRow(
                        title = root,
                        detail = "${peer.nodeName} · ${peer.host}",
                        onClick = {
                            onChange(pair.copy(right = pair.right.copy(rootPath = root, backendId = peer.backendId)))
                            pickingRight = false
                        },
                    )
                }
            }
        }
        HorizontalDivider(color = colors.outline, modifier = Modifier.padding(top = 8.dp))
    }
}

/** 预览: what would happen, counted from the plan. */
@Composable
fun PreviewScreen(state: AndroidAppState) {
    val colors = CubTheme.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
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
                    Panel(Modifier.fillMaxWidth().padding(bottom = 8.dp), title = pair.pairName.ifEmpty { pair.pairId }, subtitle = pair.sentence()) {
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
