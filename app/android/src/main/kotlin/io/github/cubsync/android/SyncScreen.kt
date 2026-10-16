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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import io.github.cubsync.core.model.CompareMode
import io.github.cubsync.core.model.ConflictPolicy
import io.github.cubsync.core.model.DeletePolicy
import io.github.cubsync.core.model.FolderPair
import io.github.cubsync.core.model.SyncConfig
import io.github.cubsync.core.model.SyncDirection
import io.github.cubsync.service.Format
import io.github.cubsync.service.IconPaths
import io.github.cubsync.service.PathTools
import io.github.cubsync.service.Words
import io.github.cubsync.service.policySentence

/**
 * 同步, in four layers: the list, the configuration, the preview, and the run.
 *
 * The phone presses the last three inside this tab and comes back with `←`, which is what
 * the design asks for — the bottom bar keeps 同步 lit throughout, so no screen is ever
 * without a parent.
 *
 * A value picked from a list of six — 同步方向 — is a row that opens a sheet here, because
 * a dropdown is a mouse's affordance. The values are the shared ones either way; only the
 * way they are asked for differs between the two ends, which is exactly the difference the
 * design allows.
 */
@Composable
fun SyncListScreen(state: AndroidAppState) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(bottom = 12.dp)) {
            if (state.configs.isEmpty()) {
                EmptyState(
                    "还没有同步配置",
                    "一个配置是一组文件夹对加一套策略。",
                    action = { PrimaryButton("新建") { state.saveConfig(state.newConfigDraft()) } },
                )
                return@Column
            }

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Panel(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    CardDesc("一个配置可含多个文件夹对；方向与比较是配置级全局，过滤可在每对单独覆盖。")
                }
                for (config in state.configs) {
                    val last = state.jobs.firstOrNull { it.configId == config.configId }
                    Panel(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        highlight = state.selectedConfigId == config.configId,
                    ) {
                        CardRow(
                            title = config.name,
                            detail = listOf(
                                Format.directionShort(config.direction),
                                "${config.pairs.count { it.enabled }} 对启用",
                                if (last != null) Format.ago(last.result.finishedAtMs) else "从未运行",
                            ).joinToString(" · "),
                            icon = IconPaths.NAV_SYNC,
                            onClick = {
                                state.selectedConfigId = config.configId
                                state.open(Route.Config(config.configId))
                            },
                        )
                    }
                }
                Spacer(Modifier.height(72.dp))
            }
        }

        Fab(
            IconPaths.PLUS,
            Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 18.dp),
        ) { state.saveConfig(state.newConfigDraft()) }
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
    var sheet by remember(configId) { mutableStateOf<String?>(null) }
    var exclude by remember(configId) { mutableStateOf(saved.globalFilter.globs.joinToString("\n")) }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
        ) {
            SectionLabel("策略（全局）")
            Panel(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                SelectRow("同步方向", Format.direction(draft.direction)) { sheet = "direction" }
                SelectRow("比较方式", Format.compare(draft.compare)) { sheet = "compare" }
                SelectRow("冲突时", Format.conflict(draft.conflict)) { sheet = "conflict" }
                SelectRow("删除时", Format.delete(draft.delete)) { sheet = "delete" }
                SettingRow("对内并行", "${draft.parallelism} 个文件同时传输") {
                    StepperField(
                        value = draft.parallelism,
                        onChange = { draft = draft.copy(parallelism = it.coerceIn(1, 16)) },
                        canDecrease = draft.parallelism > 1,
                        canIncrease = draft.parallelism < 16,
                        fieldWidth = 26,
                    )
                }
                CardButton(
                    "过滤器：排除 ${draft.globalFilter.globs.size} 条" +
                        if (draft.globalFilter.enabled) "" else " · 未启用",
                    CardButtonKind.OUTLINED,
                ) { sheet = "filter" }
            }

            SectionLabel("文件夹对（${draft.pairs.size}）")
            for ((index, pair) in draft.pairs.withIndex()) {
                Panel(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    PairCard(state, pair, onRemove = {
                        draft = draft.copy(pairs = draft.pairs.filterNot { it.pairId == pair.pairId })
                    }) { updated ->
                        val next = draft.pairs.toMutableList()
                        next[index] = updated
                        draft = draft.copy(pairs = next)
                    }
                }
            }

            Panel(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                CardButton("＋ 添加文件夹对", CardButtonKind.TONAL) {
                    draft = draft.copy(pairs = draft.pairs + state.newPairDraft(draft.pairs))
                }
                CardButton("比较并预览差异") {
                    state.saveConfig(draft)
                    state.previewConfig(draft)
                }
            }

            Spacer(Modifier.height(8.dp))
            Panel(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                CardDesc(policySentence(draft))
                CardButton("保存") {
                    state.saveConfig(draft)
                    state.back()
                }
                CardButton("试运行（不写任何东西）", CardButtonKind.OUTLINED) {
                    state.saveConfig(draft)
                    state.runConfig(draft, dryRun = true)
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        when (sheet) {
            "direction" -> ChoicesSheet("同步方向", SyncDirection.entries, draft.direction, Format::direction) {
                draft = draft.copy(direction = it); sheet = null
            }

            "compare" -> ChoicesSheet("比较方式", CompareMode.entries, draft.compare, Format::compare) {
                draft = draft.copy(compare = it); sheet = null
            }

            "conflict" -> ChoicesSheet("冲突时", ConflictPolicy.entries, draft.conflict, Format::conflict) {
                draft = draft.copy(conflict = it); sheet = null
            }

            "delete" -> ChoicesSheet("删除时", DeletePolicy.entries, draft.delete, Format::delete) {
                draft = draft.copy(delete = it); sheet = null
            }

            "filter" -> BottomSheet(onClose = { sheet = null }) {
                SheetHeader("过滤器") { sheet = null }
                CardDesc("排除优先于包含：先按排除剔掉命中项，再在剩下的里套包含。每行一条，支持 * ? ** 与 [...]。")
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceSunken)
                        .border(1.dp, colors.outlineStrong, RoundedCornerShape(12.dp))
                        .padding(10.dp),
                ) {
                    if (exclude.isEmpty()) {
                        Text("*.tmp、Thumbs.db、.cub_trash/** …", style = CubType.meta, color = colors.textFaint)
                    }
                    BasicTextField(
                        value = exclude,
                        onValueChange = { exclude = it },
                        textStyle = CubType.meta.copy(color = colors.text),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("应用") {
                        val patterns = exclude.lines().map { it.trim() }.filter { it.isNotEmpty() }
                        draft = draft.copy(
                            globalFilter = draft.globalFilter.copy(globs = patterns, enabled = patterns.isNotEmpty()),
                        )
                        state.notify("已应用 ${patterns.size} 条排除规则", NoticeKind.SUCCESS)
                        sheet = null
                    }
                    QuietButton("清空") { exclude = "" }
                }
            }
        }
    }
}

/** One choice out of several, as a sheet. */
@Composable
private fun <T> ChoicesSheet(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onPick: (T) -> Unit,
) {
    BottomSheet(onClose = { onPick(selected) }) {
        SheetHeader(title) { onPick(selected) }
        for (option in options) {
            val active = option == selected
            ListRow(
                title = label(option),
                tint = if (active) CubTheme.colors.accent else null,
                selected = active,
                trailing = { if (active) CubIcon(IconPaths.CHECK, size = 20, tint = CubTheme.colors.accent) },
                onClick = { onPick(option) },
            )
        }
        Spacer(Modifier.height(6.dp))
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
    var picking by remember(pair.pairId) { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(
                value = pair.name,
                onValueChange = { onChange(pair.copy(name = it)) },
                modifier = Modifier.weight(1f),
                placeholder = "这一对的备注名",
                style = CubType.body,
            )
            Spacer(Modifier.width(8.dp))
            Toggle(pair.enabled) { onChange(pair.copy(enabled = it)) }
        }

        GroupLabel(Words.LOCAL)
        PathRow(pair.left.rootPath, "本机目录") { picking = "left" }
        GroupLabel(Words.REMOTE)
        PathRow(pair.right.rootPath, "远端目录") { picking = "right" }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuietButton("移除这一对", danger = true, onClick = onRemove)
        }

        if (picking == "left") {
            Spacer(Modifier.height(6.dp))
            for (root in state.localRoots().take(8)) {
                ListRow(
                    title = PathTools.leafName(root).ifEmpty { root },
                    detail = root,
                    leadingIcon = IconPaths.FOLDER,
                    onClick = {
                        onChange(pair.copy(left = pair.left.copy(rootPath = root, backendId = io.github.cubsync.service.EndpointId.LOCAL)))
                        picking = null
                    },
                )
            }
        }
        if (picking == "right") {
            Spacer(Modifier.height(6.dp))
            if (state.peers.isEmpty()) {
                Text("先到连接页连上一台设备。", style = CubType.rowDetail, color = colors.textFaint)
            }
            for (peer in state.peers) {
                for (root in peer.roots) {
                    ListRow(
                        title = root,
                        detail = "${peer.nodeName} · ${peer.host}",
                        leadingIcon = IconPaths.FOLDER,
                        onClick = {
                            onChange(pair.copy(right = pair.right.copy(rootPath = root, backendId = peer.backendId)))
                            picking = null
                        },
                    )
                }
            }
        }
    }
}

/** A root path, edited in place or picked from a list. */
@Composable
private fun PathRow(value: String, placeholder: String, onPick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            value.ifEmpty { placeholder },
            style = CubType.body,
            color = if (value.isEmpty()) CubTheme.colors.textFaint else CubTheme.colors.text,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        QuietButton("选…", onClick = onPick)
    }
}

/** 预览: what would happen, counted from the plan. */
@Composable
fun PreviewScreen(state: AndroidAppState) {
    val colors = CubTheme.colors
    val preview = state.preview

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 12.dp),
    ) {
        when {
            state.previewing -> EmptyState("正在比较…", "这一遍只读，不写任何东西。")
            preview == null -> EmptyState("还没有预览", "在配置详情里点「比较并预览差异」。")

            else -> {
                val totals = preview.totals
                Panel(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Chip("${preview.pairs.size} 对", tone = colors.accent)
                        if (totals.conflict > 0) Chip("冲突 ${totals.conflict}", tone = colors.conflict)
                        Chip("新增 ${totals.create}", tone = colors.success)
                        Chip("更新 ${totals.update}", tone = colors.textDim)
                        Chip("删除 ${totals.delete}", tone = colors.danger)
                    }
                    CardDesc(
                        "要写入 ${Format.bytes(totals.bytes)} · 跳过 ${totals.skip} 项 · 汇总 ＝ 各对之和。",
                    )
                }

                for (pair in preview.pairs) {
                    Panel(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        CardRow(
                            title = pair.pairName.ifEmpty { pair.pairId },
                            detail = pair.sentence(),
                            icon = IconPaths.FOLDER,
                            tint = if (pair.blockReason.isNotEmpty()) colors.danger else null,
                        )
                        if (pair.blockReason.isNotEmpty()) {
                            CardDesc(pair.blockReason)
                        } else if (pair.total == 0) {
                            CardDesc("这一对已经一致。")
                        }
                    }
                }

                if (preview.blocked.isNotEmpty()) {
                    Panel(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), outlined = true) {
                        Text(
                            "${preview.blocked.size} 对被拦下了：路径重叠或方向不允许。修好之后才能同步。",
                            style = CubType.rowDetail,
                            color = colors.danger,
                        )
                    }
                }

                Panel(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    CardButton(
                        if (totals.conflict > 0) "执行同步（${totals.conflict} 冲突待处理）" else "执行同步",
                        enabled = preview.blocked.isEmpty() && totals.total > 0,
                    ) {
                        state.selectedConfig?.let { state.runConfig(it, dryRun = false) }
                    }
                    CardDesc("冲突永远不自动覆盖：先按这对的策略处理，剩下的留到作业详情里看。")
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
