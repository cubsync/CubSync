package io.github.cubsync.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.cubsync.core.engine.JobItem
import io.github.cubsync.core.engine.JobStatus
import io.github.cubsync.core.engine.PairOutcome
import io.github.cubsync.service.Format
import io.github.cubsync.service.JobRecord
import io.github.cubsync.service.LogLevel

/**
 * What each run did, and one run in detail.
 *
 * The log belongs to the job here rather than to a tab of its own: on a phone the
 * useful question after a run is "what did this run do", and a separate log screen
 * would make a person correlate two lists by timestamp. The entries are the ones
 * written between the run's start and its end, which is exactly the window they
 * describe.
 */
@Composable
fun JobsListScreen(state: AndroidAppState) {
    val colors = CubTheme.colors

    if (state.jobs.isEmpty()) {
        ScreenBody { EmptyState("还没有运行过", "在「同步」页选一个配置，点「立即同步」或「试运行」。") }
        return
    }

    ScreenBody {
        for (job in state.jobs) {
            val tone = statusTone(job.result.status)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surface)
                    .clickable { state.open(Route.JobDetail(job.jobId)) }
                    .padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(7.dp).height(7.dp).clip(CircleShape).background(tone))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        job.configName.ifBlank { job.configId },
                        style = CubType.cardTitle,
                        color = colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (job.dryRun) Text("试运行", style = CubType.meta, color = colors.textFaint)
                }
                Spacer(Modifier.height(4.dp))
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
    }
}

@Composable
fun JobDetailScreen(state: AndroidAppState, job: JobRecord) {
    val colors = CubTheme.colors
    val result = job.result
    val stats = result.stats
    val tone = statusTone(result.status)
    val config = state.config(job.configId)

    ScreenBody {
        Panel {
            Text(Format.jobStatus(result.status), style = CubType.big, color = tone)
            Spacer(Modifier.height(4.dp))
            Text(
                "${job.configName} · ${Format.duration(result.durationMs)} · " +
                    Format.rate(stats.copiedBytes, result.durationMs),
                style = CubType.body,
                color = colors.textDim,
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("复制", Format.count(stats.copiedFiles, "个"))
                Metric("数据量", Format.bytes(stats.copiedBytes))
                Metric("删除", Format.count(stats.deletedFiles, "个"))
                Metric("失败", Format.count(stats.failures, "个"), if (stats.failures > 0) colors.danger else null)
            }
        }

        Panel(title = "明细") {
            KeyValue("配置", job.configName)
            KeyValue("作业号", job.jobId)
            KeyValue("方向", Format.direction(job.direction))
            KeyValue("模式", if (job.dryRun) "试运行（不写入）" else "实际写入")
            KeyValue("文件夹对", Format.count(result.pairs.size, "对"))
            KeyValue("开始", Format.stamp(result.startedAtMs))
            KeyValue("结束", Format.stamp(result.finishedAtMs))
            KeyValue("冲突", Format.count(stats.conflicts, "个"), colors.conflict)
            KeyValue("跳过", Format.count(stats.skipped, "个"), colors.textDim)
        }

        for (pair in result.pairs) {
            PairPanel(pair)
        }

        Panel(title = "接下来") {
            Button(
                enabled = config != null && state.running == null,
                onClick = { config?.let { state.runConfig(it, dryRun = false) } },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("再次运行")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                enabled = config != null && state.running == null,
                onClick = { config?.let { state.runConfig(it, dryRun = true) } },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("试运行")
            }
            if (config == null) {
                Spacer(Modifier.height(6.dp))
                Text("原配置已被删除", style = CubType.meta, color = colors.warning)
            }
        }

        RunLogPanel(state, job)
    }
}

@Composable
private fun Metric(label: String, value: String, color: Color? = null) {
    Column {
        Text(label, style = CubType.meta, color = CubTheme.colors.textFaint)
        Text(value, style = CubType.cardTitle, color = color ?: CubTheme.colors.text)
    }
}

@Composable
private fun PairPanel(pair: PairOutcome) {
    val colors = CubTheme.colors
    var showTransferred by remember(pair.pairId) { mutableStateOf(false) }
    var showSkipped by remember(pair.pairId) { mutableStateOf(false) }
    val failed = pair.items.filter { !it.ok }

    Panel(
        title = pair.pairName.ifEmpty { pair.pairId },
        subtitle = "${Format.pairStatus(pair.status)} · 复制 ${pair.stats.copiedFiles} · 删除 ${pair.stats.deletedFiles}" +
            if (pair.error.isNotEmpty()) " · ${pair.error}" else "",
    ) {
        if (pair.transferred.isNotEmpty()) {
            ItemGroup("已传输 ${pair.transferred.size} 项", showTransferred, { showTransferred = !showTransferred }, pair.transferred, colors.text)
        }
        if (failed.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            ItemGroup("失败 ${failed.size} 项", true, {}, failed, colors.danger)
        }
        if (pair.skippedItems.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            ItemGroup("已跳过 ${pair.skippedItems.size} 项", showSkipped, { showSkipped = !showSkipped }, pair.skippedItems, colors.textDim)
        }
        if (pair.transferred.isEmpty() && failed.isEmpty() && pair.skippedItems.isEmpty()) {
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
            Modifier.fillMaxWidth().clickable { onToggle() }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (expanded) "▾" else "▸", style = CubType.meta, color = colors.textFaint)
            Spacer(Modifier.width(6.dp))
            Text(title, style = CubType.label, color = tone)
        }
        if (expanded) {
            for (item in items.take(20)) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 3.dp, bottom = 3.dp)) {
                    Text(
                        item.relPath,
                        style = CubType.meta,
                        color = if (item.ok) colors.text else colors.danger,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
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
            if (items.size > 20) {
                Text(
                    "还有 ${items.size - 20} 项",
                    style = CubType.meta,
                    color = colors.textFaint,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun RunLogPanel(state: AndroidAppState, job: JobRecord) {
    val colors = CubTheme.colors
    var expanded by remember(job.jobId) { mutableStateOf(false) }
    val entries = state.logs.filter {
        it.atMs >= job.result.startedAtMs && it.atMs <= job.result.finishedAtMs + 1_000
    }.filter { it.level >= LogLevel.DEBUG }

    Panel(
        title = "这次运行的日志",
        subtitle = if (entries.isEmpty()) "没有留下记录" else "${entries.size} 条，从 ${Format.clockTime(job.result.startedAtMs)} 开始",
    ) {
        if (entries.isNotEmpty()) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起" else "展开全部", style = CubType.label)
            }
        }
        for (entry in if (expanded) entries else entries.take(8)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                Text(Format.clockTime(entry.atMs), style = CubType.meta, color = colors.textFaint)
                Spacer(Modifier.width(8.dp))
                Text(
                    entry.message,
                    style = CubType.meta,
                    color = when (entry.level) {
                        LogLevel.DEBUG -> colors.textFaint
                        LogLevel.INFO -> colors.textDim
                        LogLevel.WARN -> colors.warning
                        LogLevel.ERROR -> colors.danger
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!expanded && entries.size > 8) {
            Text("还有 ${entries.size - 8} 条", style = CubType.meta, color = colors.textFaint)
        }
    }
}

@Composable
private fun statusTone(status: JobStatus): Color = when (status) {
    JobStatus.COMPLETED -> CubTheme.colors.success
    JobStatus.PARTIAL_FAILED -> CubTheme.colors.warning
    JobStatus.CANCELLED -> CubTheme.colors.textDim
    JobStatus.FAILED -> CubTheme.colors.danger
}
