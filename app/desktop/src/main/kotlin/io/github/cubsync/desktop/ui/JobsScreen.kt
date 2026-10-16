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
import io.github.cubsync.core.engine.JobItem
import io.github.cubsync.core.engine.JobStatus
import io.github.cubsync.core.engine.PairOutcome
import io.github.cubsync.core.engine.PairStatus
import io.github.cubsync.desktop.AppState
import io.github.cubsync.desktop.CubTheme
import io.github.cubsync.desktop.CubType
import io.github.cubsync.service.Format
import io.github.cubsync.desktop.Section
import io.github.cubsync.service.JobRecord

/**
 * What each run did.
 *
 * The list is the runs of this session; the detail is one run, and it answers the
 * three questions somebody actually has afterwards: how much moved, what was left
 * alone and why, and what failed. Files are listed to twenty and then counted,
 * because a run that touched ten thousand files is not something a person reads.
 */
@Composable
fun JobsScreen(state: AppState) {
    val colors = CubTheme.colors
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            Section.JOBS.label,
            Section.JOBS.hint,
            actions = {
                QuietButton("在日志页看全部", onClick = { state.goTo(Section.LOGS) })
            },
        )
        Row(Modifier.fillMaxSize()) {
            JobList(state, Modifier.width(300.dp).fillMaxHeight())
            Box(Modifier.width(1.dp).fillMaxHeight().background(colors.outline))
            val job = state.selectedJob
            if (job == null) {
                Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.TopCenter) {
                    EmptyState(
                        if (state.jobs.isEmpty()) "还没有运行过" else "选择左侧的一次运行",
                        if (state.jobs.isEmpty()) "在「同步」页选一个配置，点「立即同步」或「试运行」。" else "",
                    )
                }
            } else {
                JobDetail(state, job, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun JobList(state: AppState, modifier: Modifier) {
    val colors = CubTheme.colors
    Column(modifier.padding(vertical = 4.dp)) {
        if (state.jobs.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(14.dp)) {
                Text("列表为空", style = CubType.meta, color = colors.textFaint)
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            for (job in state.jobs) {
                JobRow(
                    job = job,
                    selected = job.jobId == state.selectedJobId,
                    onClick = { state.selectJob(job.jobId) },
                )
            }
        }
    }
}

@Composable
private fun JobRow(job: JobRecord, selected: Boolean, onClick: () -> Unit) {
    val colors = CubTheme.colors
    val tone = statusColor(job.result.status)
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
            StatusDot(tone)
            Spacer(Modifier.width(7.dp))
            Text(
                job.configName.ifBlank { job.configId },
                style = CubType.cardTitle,
                color = if (selected) colors.accent else colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (job.dryRun) Text("试运行", style = CubType.meta, color = colors.textFaint)
        }
        Spacer(Modifier.height(3.dp))
        Text(
            "${Format.stamp(job.result.startedAtMs)} · ${Format.jobStatus(job.result.status)}",
            style = CubType.meta,
            color = colors.textDim,
        )
        Text(
            "${Format.bytes(job.result.stats.copiedBytes)} · ${Format.duration(job.result.durationMs)}",
            style = CubType.meta,
            color = colors.textFaint,
        )
    }
}

@Composable
private fun JobDetail(state: AppState, job: JobRecord, modifier: Modifier) {
    val colors = CubTheme.colors
    val result = job.result
    val stats = result.stats
    val tone = statusColor(result.status)

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Panel(modifier = Modifier.fillMaxWidth()) {
            Text(Format.jobStatus(result.status), style = CubType.big, color = tone)
            Spacer(Modifier.height(4.dp))
            Text(
                "${job.configName} · ${Format.duration(result.durationMs)} · " +
                    "${Format.rate(stats.copiedBytes, result.durationMs)}",
                style = CubType.body,
                color = colors.textDim,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Metric("复制", Format.count(stats.copiedFiles, "个"), colors.text)
                Metric("数据量", Format.bytes(stats.copiedBytes), colors.text)
                Metric("删除", Format.count(stats.deletedFiles, "个"), if (stats.deletedFiles > 0) colors.warning else colors.textDim)
                Metric("冲突", Format.count(stats.conflicts, "个"), if (stats.conflicts > 0) colors.conflict else colors.textDim)
                Metric("跳过", Format.count(stats.skipped, "个"), colors.textDim)
                Metric("失败", Format.count(stats.failures, "个"), if (stats.failures > 0) colors.danger else colors.textDim)
            }
        }

        Panel(modifier = Modifier.fillMaxWidth(), title = "明细") {
            KeyValue("配置", "${job.configName}（${job.configId}）", wide = true)
            KeyValue("作业号", job.jobId, wide = true)
            KeyValue("方向", Format.direction(job.direction), wide = true)
            KeyValue("模式", if (job.dryRun) "试运行（不写入）" else "实际写入", wide = true)
            KeyValue("文件夹对", Format.count(result.pairs.size, "对"), wide = true)
            KeyValue("开始", Format.stamp(result.startedAtMs), wide = true)
            KeyValue("结束", Format.stamp(result.finishedAtMs), wide = true)
            KeyValue("耗时", Format.duration(result.durationMs), wide = true)
        }

        for (pair in result.pairs) {
            PairOutcomePanel(pair)
        }

        Panel(modifier = Modifier.fillMaxWidth(), title = "接下来") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val config = state.configs.firstOrNull { it.configId == job.configId }
                PrimaryButton(
                    text = "再次运行",
                    enabled = config != null && state.running == null,
                    onClick = { config?.let { state.runConfig(it, dryRun = false) } },
                )
                Spacer(Modifier.width(8.dp))
                QuietButton(
                    "试运行",
                    enabled = config != null && state.running == null,
                    onClick = { config?.let { state.runConfig(it, dryRun = true) } },
                )
                Spacer(Modifier.weight(1f))
                if (config == null) {
                    Text("原配置已被删除", style = CubType.meta, color = colors.warning)
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, color: Color) {
    Column {
        Text(label, style = CubType.meta, color = CubTheme.colors.textFaint)
        Text(value, style = CubType.cardTitle, color = color)
    }
}

@Composable
private fun PairOutcomePanel(pair: PairOutcome) {
    val colors = CubTheme.colors
    var showTransferred by remember(pair.pairId) { mutableStateOf(false) }
    var showSkipped by remember(pair.pairId) { mutableStateOf(false) }

    Panel(
        modifier = Modifier.fillMaxWidth(),
        title = pair.pairName.ifEmpty { pair.pairId },
        subtitle = "${Format.pairStatus(pair.status)} · 复制 ${pair.stats.copiedFiles} · 删除 ${pair.stats.deletedFiles}" +
            if (pair.error.isNotEmpty()) " · ${pair.error}" else "",
    ) {
        val transferred = pair.transferred
        val skipped = pair.skippedItems
        val failed = pair.items.filter { !it.ok }

        if (transferred.isNotEmpty()) {
            ItemGroup(
                title = "已传输 ${transferred.size} 项",
                expanded = showTransferred,
                onToggle = { showTransferred = !showTransferred },
                items = transferred,
                tone = colors.text,
            )
        }
        if (failed.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            ItemGroup(
                title = "失败 ${failed.size} 项",
                expanded = true,
                onToggle = {},
                items = failed,
                tone = colors.danger,
            )
        }
        if (skipped.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            ItemGroup(
                title = "已跳过 ${skipped.size} 项",
                expanded = showSkipped,
                onToggle = { showSkipped = !showSkipped },
                items = skipped,
                tone = colors.textDim,
            )
        }
        if (transferred.isEmpty() && skipped.isEmpty() && failed.isEmpty()) {
            EmptyState("这次没有任何改动", "两侧已经一致。")
        }
    }
}

@Composable
private fun ItemGroup(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    items: List<JobItem>,
    tone: Color,
) {
    val colors = CubTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onToggle() }
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (expanded) "▾" else "▸", style = CubType.meta, color = colors.textFaint)
            Spacer(Modifier.width(6.dp))
            Text(title, style = CubType.label, color = tone)
        }
        if (expanded) {
            for (item in items.take(20)) {
                ItemRow(item, tone)
            }
            if (items.size > 20) {
                Text(
                    "还有 ${items.size - 20} 项",
                    style = CubType.meta,
                    color = colors.textFaint,
                    modifier = Modifier.padding(start = 18.dp, top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ItemRow(item: JobItem, tone: Color) {
    val colors = CubTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            item.relPath,
            style = CubType.monoDim,
            color = if (item.ok) colors.text else colors.danger,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            when {
                item.error.isNotEmpty() -> item.error
                item.skippedReason.isNotEmpty() -> item.skippedReason
                item.bytes > 0 -> Format.bytes(item.bytes)
                else -> Format.action(item.action)
            },
            style = CubType.meta,
            color = tone,
            maxLines = 1,
        )
    }
}

@Composable
internal fun statusColor(status: JobStatus): Color = when (status) {
    JobStatus.COMPLETED -> CubTheme.colors.success
    JobStatus.PARTIAL_FAILED -> CubTheme.colors.warning
    JobStatus.CANCELLED -> CubTheme.colors.textDim
    JobStatus.FAILED -> CubTheme.colors.danger
}

@Composable
internal fun pairStatusColor(status: PairStatus): Color = when (status) {
    PairStatus.COMPLETED -> CubTheme.colors.success
    PairStatus.DRY_RUN -> CubTheme.colors.info
    PairStatus.SKIPPED -> CubTheme.colors.textDim
    PairStatus.BLOCKED -> CubTheme.colors.warning
    PairStatus.FAILED -> CubTheme.colors.danger
}
