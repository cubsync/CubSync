package io.github.cubsync.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import io.github.cubsync.service.Format
import io.github.cubsync.service.IconPaths
import io.github.cubsync.service.JobDetails
import io.github.cubsync.service.JobFileLine
import io.github.cubsync.service.JobRecord
import io.github.cubsync.service.LogLevel
import io.github.cubsync.service.Words

/**
 * 作业, with the log as its second half.
 *
 * The log is not a tab of its own here: after a run the question is "what did this run
 * do", and the answer belongs next to the run. The desktop keeps a separate log entry
 * because it has the room; the design allows exactly that difference and no other.
 */
@Composable
fun JobsScreen(state: AndroidAppState) {
    Column(Modifier.fillMaxSize()) {
        // The sub-row: the two segments on the left, the running count on the right.
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Segmented(
                options = JobsSegment.entries.toList(),
                selected = state.jobsSegment,
                label = { it.label },
                onSelect = { state.jobsSegment = it },
            )
            Spacer(Modifier.weight(1f))
            val inFlight = if (state.running != null) 1 else 0
            if (inFlight > 0) Chip("$inFlight 进行中", tone = CubTheme.colors.accent)
        }

        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            if (state.jobsSegment == JobsSegment.LOGS) {
                LogsBody(state, Modifier.fillMaxSize())
                return@Column
            }

            if (state.jobs.isEmpty()) {
                EmptyState("还没有跑过同步", "在同步页选一个配置，试运行一次会立刻出现在这里。")
                return@Column
            }

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                for (job in state.jobs) {
                    val detail = state.detailsOf(job)
                    Panel(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        CardRow(
                            title = job.configName,
                            detail = "${Format.clockTime(job.result.startedAtMs)} · ${detail.summary}",
                            icon = IconPaths.NAV_SYNC,
                            tint = toneColor(detail.tone),
                            onClick = { state.open(Route.Job(job.jobId)) },
                            trailing = {
                                if (job.result.stats.failures > 0) {
                                    Chip("${job.result.stats.failures} 项失败", tone = CubTheme.colors.danger)
                                } else {
                                    Chip(if (job.dryRun) "试运行" else "完成", tone = CubTheme.colors.textDim)
                                }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** 作业条目详情 — the same five parts as the desktop, in the same order. */
@Composable
fun JobDetailScreen(state: AndroidAppState, jobId: String) {
    val colors = CubTheme.colors
    val job = state.selectedJob(jobId) ?: run {
        state.back()
        return
    }
    val detail = state.detailsOf(job)
    var showTransferred by remember(jobId) { mutableStateOf(false) }
    var showSkipped by remember(jobId) { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 12.dp),
    ) {
        // ① the result banner
        Panel(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(detail.headline, style = CubType.big, color = toneColor(detail.tone))
                Spacer(Modifier.weight(1f))
                Chip(if (job.dryRun) "试运行" else Format.jobStatus(job.result.status), tone = toneColor(detail.tone))
            }
            CardDesc(detail.summary)
            if (detail.resumeNote.isNotEmpty()) {
                Text(detail.resumeNote, style = CubType.rowDetail, color = colors.warning)
            }
        }

        // ② the key/value block
        SectionLabel("明细")
        Panel(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            for ((key, value) in detail.rows) KeyValueRow(key, value)
        }

        // ③ the file-level lists
        SectionLabel("文件级")
        Panel(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            title = "已传输 ${detail.transferredTotal} 项",
            actions = { QuietButton(if (showTransferred) "收起" else "展开") { showTransferred = !showTransferred } },
        ) {
            FileList(detail.transferred, detail.moreTransferred, showTransferred, colors.success)
        }
        Panel(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            title = "已跳过 ${detail.skippedTotal} 项",
            actions = { QuietButton(if (showSkipped) "收起" else "展开") { showSkipped = !showSkipped } },
        ) {
            FileList(detail.skipped, detail.moreSkipped, showSkipped, colors.textDim)
        }

        // ④ this run's log, inline
        SectionLabel("这次运行的日志")
        Panel(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            title = "${detail.logs.size} 行",
            actions = {
                QuietButton("看全部 →") {
                    state.jobsSegment = JobsSegment.LOGS
                    state.back()
                }
            },
        ) {
            if (detail.logs.isEmpty()) {
                CardDesc("这次运行没有写入日志。")
            }
            for (entry in detail.logs.takeLast(40)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(Format.stamp(entry.atMs), style = CubType.mono, color = colors.textFaint, modifier = Modifier.width(68.dp))
                    Text(entry.area, style = CubType.mono, color = levelColor(entry.level), modifier = Modifier.width(50.dp))
                    Text(entry.message, style = CubType.mono, color = colors.textDim, modifier = Modifier.weight(1f))
                }
            }
        }

        // ⑤ the actions, which depend on how it ended
        Spacer(Modifier.height(8.dp))
        val config = state.configs.firstOrNull { it.configId == job.configId }
        Panel(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            when {
                state.running != null -> CardButton("取消这次同步", CardButtonKind.OUTLINED) { state.cancelRun() }

                detail.canRetryFailedPairs -> CardButton("重试失败对") {
                    if (config != null) state.runConfig(config, dryRun = false)
                    else state.notify("这个配置已经不在了", NoticeKind.WARNING)
                }

                else -> CardButton("再次运行") {
                    if (config != null) state.runConfig(config, dryRun = false)
                    else state.notify("这个配置已经不在了", NoticeKind.WARNING)
                }
            }
            CardButton("导出报告", CardButtonKind.OUTLINED) { report(state, detail) }
            if (config == null) CardDesc("原来的配置已经被删掉了。")
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun FileList(
    lines: List<JobFileLine>,
    more: Int,
    expanded: Boolean,
    tone: androidx.compose.ui.graphics.Color,
) {
    val colors = CubTheme.colors
    if (!expanded) return
    if (lines.isEmpty()) {
        CardDesc("没有。")
        return
    }
    for (line in lines) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (line.ok) tone else colors.danger, 6)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(line.relPath, style = CubType.body, color = colors.text, maxLines = 1)
                if (line.note.isNotEmpty()) {
                    Text(line.note, style = CubType.rowDetail, color = colors.textFaint, maxLines = 1)
                }
            }
            if (line.bytes > 0) Text(Format.bytes(line.bytes), style = CubType.rowDetail, color = colors.textFaint)
        }
    }
    if (more > 0) {
        Text("还有 $more 项", style = CubType.rowDetail, color = colors.textFaint)
    }
}

/** The log, filterable, as the second half of the jobs tab. */
@Composable
fun LogsBody(state: AndroidAppState, modifier: Modifier = Modifier) {
    val colors = CubTheme.colors
    var area by remember { mutableStateOf("") }
    val areas = state.logs.map { it.area }.distinct().sorted()

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Segmented(
                options = LogLevel.entries.toList(),
                selected = state.logMinimumLevel,
                label = { Format.level(it) },
                onSelect = { state.logMinimumLevel = it },
            )
            Spacer(Modifier.weight(1f))
            QuietButton("清空") { state.clearLogs() }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(99.dp))
                    .background(colors.surfaceRaised)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    if (area.isEmpty()) "全部区域（${areas.size}）" else area,
                    style = CubType.rowDetail,
                    color = colors.textDim,
                )
            }
            Spacer(Modifier.width(8.dp))
            QuietButton("切换区域") {
                val next = areas.indexOf(area).let { index -> if (index + 1 >= areas.size) -1 else index + 1 }
                area = if (next < 0) "" else areas[next]
            }
        }

        val shown = state.logs
            .filter { it.level >= state.logMinimumLevel }
            .filter { area.isEmpty() || it.area == area }

        Panel(Modifier.weight(1f).fillMaxWidth()) {
            CardDesc("最新在最后，共 ${shown.size} 行")
            if (shown.isEmpty()) {
                Text("没有符合条件的日志。", style = CubType.rowDetail, color = colors.textFaint)
            }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (entry in shown) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text(Format.stamp(entry.atMs), style = CubType.mono, color = colors.textFaint, modifier = Modifier.width(66.dp))
                        Text(Format.level(entry.level), style = CubType.mono, color = levelColor(entry.level), modifier = Modifier.width(40.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.message, style = CubType.mono, color = colors.text)
                            if (entry.detail.isNotEmpty()) {
                                Text(entry.detail, style = CubType.mono, color = colors.textFaint)
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = colors.outline)
    }
}

/** Writes the run out as a file in the application's own directory. */
private fun report(state: AndroidAppState, detail: JobDetails) {
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
    val file = java.io.File(state.appDirectory, "${detail.title.replace(':', '-')}.txt")
    runCatching { file.writeText(text, Charsets.UTF_8) }
        .onSuccess { state.notify("报告已保存到 ${file.absolutePath}", NoticeKind.SUCCESS) }
        .onFailure { state.notify("保存失败：${it.message}", NoticeKind.ERROR) }
}
