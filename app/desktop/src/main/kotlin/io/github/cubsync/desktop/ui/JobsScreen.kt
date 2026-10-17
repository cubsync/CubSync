package io.github.cubsync.desktop.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import io.github.cubsync.desktop.AppState
import io.github.cubsync.desktop.CubTheme
import io.github.cubsync.desktop.CubType
import io.github.cubsync.desktop.NoticeKind
import io.github.cubsync.desktop.JobsSegment
import io.github.cubsync.service.Format
import io.github.cubsync.service.JobDetails
import io.github.cubsync.service.JobFileLine
import io.github.cubsync.service.JobTone
import io.github.cubsync.service.Section
import io.github.cubsync.service.Words

/**
 * 作业: the list, and the screen a row opens.
 *
 * The detail is a screen of its own on both platforms — same fields, same order — because
 * "what did that run actually do" is the question somebody has after reading a result
 * badge, and a badge cannot answer it.
 */
@Composable
fun JobsScreen(state: AppState, showLogs: Boolean) {
    if (state.selectedJobId != null) {
        JobDetailScreen(state, showLogs)
    } else {
        JobListScreen(state, showLogs)
    }
}

// ------------------------------------------------------------------- list

@Composable
private fun JobListScreen(state: AppState, showLogs: Boolean) {
    val colors = CubTheme.colors
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        if (showLogs) {
            Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Segmented(
                    options = JobsSegment.entries.toList(),
                    selected = state.jobsSegment,
                    label = { it.label },
                    onSelect = { state.jobsSegment = it },
                )
            }
        }
        if (showLogs && state.jobsSegment == JobsSegment.LOGS) {
            LogsBody(state, Modifier.fillMaxSize())
            return@Column
        }

        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("${state.jobs.size} 次运行", tone = colors.accent)
            Spacer(Modifier.weight(1f))
            state.running?.let {
                Chip("${it.label}「${it.configName}」· ${it.finishedPairs}/${it.pairCount} 对", tone = colors.warning)
                Spacer(Modifier.width(6.dp))
                QuietButton("取消", danger = true) { state.cancelRun() }
            }
        }

        if (state.jobs.isEmpty()) {
            EmptyState("还没有跑过同步", "在同步页选一个配置，试运行一次会立刻出现在这里。")
            return@Column
        }

        Panel(Modifier.fillMaxSize(), title = "已完成", subtitle = "点开任一条看明细与当次日志") {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                for (job in state.jobs) {
                    val detail = detailsOf(state, job)
                    ListLine(
                        title = "${job.configName}  ·  ${Format.clockTime(job.result.startedAtMs)}",
                        detail = "${detail.headline} · ${detail.summary} · ${Format.ago(job.result.finishedAtMs)}",
                        tint = toneColor(detail.tone),
                        onClick = { state.selectedJobId = job.jobId },
                        trailing = {
                            if (job.result.stats.failures > 0) Chip("${job.result.stats.failures} 项失败", tone = colors.danger)
                            else if (job.dryRun) Chip("试运行", tone = colors.textDim)
                            else Chip("可再运行", tone = colors.textFaint)
                        },
                    )
                }
            }
        }
    }
}

/**
 * The same factory the detail screen uses, so a list row and the screen it opens cannot
 * disagree about what a run did.
 */
private fun detailsOf(state: AppState, job: io.github.cubsync.service.JobRecord): JobDetails =
    io.github.cubsync.service.JobDetailsFactory.of(job, state.service.log)

// ------------------------------------------------------------------ detail

/** 作业条目详情: the result, the numbers, the file lists, and that run's log. */
@Composable
private fun JobDetailScreen(state: AppState, showLogs: Boolean) {
    val colors = CubTheme.colors
    val job = state.selectedJob ?: run {
        state.selectedJobId = null
        return
    }
    val detail = detailsOf(state, job)
    var showTransferred by remember(job.jobId) { mutableStateOf(false) }
    var showSkipped by remember(job.jobId) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            QuietButton("← 返回") { state.selectedJobId = null }
            Spacer(Modifier.width(8.dp))
            Text(detail.title, style = CubType.cardTitle, color = colors.text)
            Spacer(Modifier.weight(1f))
            Chip(if (job.dryRun) "试运行" else Format.jobStatus(job.result.status), tone = toneColor(detail.tone))
        }

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            // ① the big result banner
            Panel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(detail.headline, style = CubType.big, color = toneColor(detail.tone))
                    Spacer(Modifier.weight(1f))
                    Text(detail.summary, style = CubType.meta, color = colors.textDim)
                }
                if (detail.resumeNote.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(detail.resumeNote, style = CubType.meta, color = colors.warning)
                }
            }
            Spacer(Modifier.height(12.dp))

            // ② the key/value block, in the order UiSpec states
            Panel(Modifier.fillMaxWidth(), title = "明细") {
                for ((key, value) in detail.rows) KeyValueRow(key, value)
            }
            Spacer(Modifier.height(12.dp))

            // ③ the file-level lists
            FileGroup(
                title = "已传输 ${detail.transferredTotal} 项",
                lines = detail.transferred,
                expanded = showTransferred,
                more = detail.moreTransferred,
                tone = colors.success,
                onToggle = { showTransferred = !showTransferred },
            )
            Spacer(Modifier.height(8.dp))
            FileGroup(
                title = "已跳过 ${detail.skippedTotal} 项",
                lines = detail.skipped,
                expanded = showSkipped,
                more = detail.moreSkipped,
                tone = colors.textDim,
                onToggle = { showSkipped = !showSkipped },
            )
            Spacer(Modifier.height(12.dp))

            // ④ this run's log, inline
            Panel(
                Modifier.fillMaxWidth(),
                title = "这次运行的日志",
                subtitle = "${detail.logs.size} 行",
                actions = {
                    QuietButton("在日志页看全部 →") {
                        if (showLogs) state.jobsSegment = JobsSegment.LOGS else state.goTo(Section.LOGS)
                    }
                },
            ) {
                if (detail.logs.isEmpty()) {
                    Text("这次运行没有写入日志。", style = CubType.meta, color = colors.textFaint)
                }
                for (entry in detail.logs.takeLast(60)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.Top) {
                        Text(Format.stamp(entry.atMs), style = CubType.monoDim, color = colors.textFaint, modifier = Modifier.width(70.dp))
                        Text(entry.area, style = CubType.monoDim, color = levelColor(entry.level), modifier = Modifier.width(56.dp))
                        Text(entry.message, style = CubType.monoDim, color = colors.textDim, modifier = Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        HorizontalDivider(color = colors.outline)
        Spacer(Modifier.height(8.dp))

        // ⑤ the actions, which depend on how the run ended
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val config = state.configs.firstOrNull { it.configId == job.configId }
            when {
                state.running != null -> {
                    QuietButton(if (state.runPaused) "继续" else "暂停") {
                        if (state.runPaused) state.resumeRun() else state.pauseRun()
                    }
                    QuietButton("取消", danger = true) { state.cancelRun() }
                }
                detail.canRetryFailedPairs -> {
                    PrimaryButton("重试失败对") {
                        if (config != null) {
                            state.runConfig(config, dryRun = false)
                        } else {
                            state.notify("这个配置已经不在了", NoticeKind.WARNING)
                        }
                    }
                    QuietButton("报告") { exportReport(state, detail) }
                }
                else -> {
                    PrimaryButton("再次运行") {
                        if (config != null) {
                            state.runConfig(config, dryRun = false)
                        } else {
                            state.notify("这个配置已经不在了", NoticeKind.WARNING)
                        }
                    }
                    QuietButton("报告") { exportReport(state, detail) }
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (config == null) "原来的配置已经被删掉了" else policySentenceOf(config),
                style = CubType.meta,
                color = colors.textFaint,
            )
        }
    }
}

private fun policySentenceOf(config: io.github.cubsync.core.model.SyncConfig): String =
    io.github.cubsync.service.policySentence(config)

/** 已传输 / 已跳过, folded until asked for, capped at twenty lines. */
@Composable
private fun FileGroup(
    title: String,
    lines: List<JobFileLine>,
    expanded: Boolean,
    more: Int,
    tone: androidx.compose.ui.graphics.Color,
    onToggle: () -> Unit,
) {
    val colors = CubTheme.colors
    Panel(
        Modifier.fillMaxWidth(),
        title = title,
        subtitle = if (expanded) "" else "点开看文件级明细",
        actions = { QuietButton(if (expanded) "收起" else "展开") { onToggle() } },
    ) {
        if (!expanded) return@Panel
        if (lines.isEmpty()) {
            Text("没有。", style = CubType.meta, color = colors.textFaint)
            return@Panel
        }
        for (line in lines) {
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (line.ok) tone else colors.danger, 6)
                Spacer(Modifier.width(7.dp))
                Text(line.relPath, style = CubType.body, color = colors.text, maxLines = 1, modifier = Modifier.weight(1f))
                if (line.bytes > 0) Text(Format.bytes(line.bytes), style = CubType.meta, color = colors.textFaint)
                if (line.note.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(line.note, style = CubType.meta, color = colors.textFaint, maxLines = 1)
                }
            }
        }
        if (more > 0) {
            Spacer(Modifier.height(4.dp))
            Text("还有 $more 项", style = CubType.meta, color = colors.textFaint)
        }
    }
}

/** Writes the run out as a text file, because a report that cannot leave the app is not one. */
private fun exportReport(state: AppState, detail: JobDetails) {
    val target = io.github.cubsync.desktop.pickSaveFile("保存报告", "${detail.title.replace(':', '-')}.txt", ".txt")
    if (target == null) {
        state.notify("已取消保存报告", NoticeKind.INFO)
        return
    }
    val text = buildString {
        appendLine(detail.title)
        appendLine(detail.headline + " —— " + detail.summary)
        appendLine()
        for ((key, value) in detail.rows) appendLine("$key\t$value")
        appendLine()
        appendLine("已传输 ${detail.transferredTotal} 项")
        for (line in detail.transferred) appendLine("  ${line.relPath}\t${Format.bytes(line.bytes)}")
        if (detail.moreTransferred > 0) appendLine("  还有 ${detail.moreTransferred} 项")
        appendLine("已跳过 ${detail.skippedTotal} 项")
        for (line in detail.skipped) appendLine("  ${line.relPath}\t${line.note}")
        if (detail.moreSkipped > 0) appendLine("  还有 ${detail.moreSkipped} 项")
    }
    runCatching { java.io.File(target).writeText(text, Charsets.UTF_8) }
        .onSuccess { state.notify("报告已保存到 $target", NoticeKind.SUCCESS) }
        .onFailure { state.notify("保存失败：${it.message}", NoticeKind.ERROR) }
}

/** The log body, shared by the jobs page's second segment and the standalone log screen. */
@Composable
fun LogsBody(state: AppState, modifier: Modifier = Modifier) {
    val colors = CubTheme.colors
    val levels = io.github.cubsync.service.LogLevel.entries.toList()
    val areas = state.logs.map { it.area }.distinct().sorted()
    var area by remember { mutableStateOf("") }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Segmented(
                options = levels,
                selected = state.logMinimumLevel,
                label = { Format.level(it) },
                onSelect = { state.logMinimumLevel = it },
            )
            Spacer(Modifier.width(10.dp))
            Segmented(
                options = listOf("") + areas,
                selected = area,
                label = { if (it.isEmpty()) "全部区域" else it },
                onSelect = { area = it },
            )
            Spacer(Modifier.weight(1f))
            Chip("${state.logs.size} 行", tone = colors.textDim)
            Spacer(Modifier.width(8.dp))
            QuietButton("清空") { state.clearLogs() }
        }

        val needle = state.logQuery.trim()
        val shown = state.logs
            .filter { it.level >= state.logMinimumLevel }
            .filter { area.isEmpty() || it.area == area }
            .filter {
                needle.isEmpty() ||
                    it.message.contains(needle, ignoreCase = true) ||
                    it.area.contains(needle, ignoreCase = true)
            }

        Panel(Modifier.fillMaxSize(), title = "日志", subtitle = "最新在最后") {
            if (shown.isEmpty()) {
                Text("没有符合条件的日志。", style = CubType.meta, color = colors.textFaint)
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                for (entry in shown) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.Top) {
                        // The prototype prints the clock, not the date: a log page is read
                        // in one sitting, and a wrapped date column is what a 72dp box does
                        // to "10-17 13:25:33".
                        Text(
                            Format.clockTime(entry.atMs),
                            style = CubType.monoDim,
                            color = colors.textFaint,
                            maxLines = 1,
                            modifier = Modifier.width(58.dp),
                        )
                        Text(Format.level(entry.level), style = CubType.monoDim, color = levelColor(entry.level), modifier = Modifier.width(44.dp))
                        Text(entry.area, style = CubType.monoDim, color = colors.textDim, modifier = Modifier.width(56.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.message, style = CubType.monoDim, color = colors.text)
                            if (entry.detail.isNotEmpty()) {
                                Text(entry.detail, style = CubType.monoDim, color = colors.textFaint)
                            }
                        }
                    }
                }
            }
        }
    }
}
