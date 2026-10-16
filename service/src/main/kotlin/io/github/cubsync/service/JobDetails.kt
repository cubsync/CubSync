package io.github.cubsync.service

import io.github.cubsync.core.engine.ActionKind
import io.github.cubsync.core.engine.DiffKind
import io.github.cubsync.core.engine.JobItem
import io.github.cubsync.core.engine.JobResult
import io.github.cubsync.core.engine.JobStatus
import io.github.cubsync.core.engine.PairOutcome
import io.github.cubsync.core.engine.PairStatus

/** The tone of a result headline. Three, because the design gives three. */
enum class JobTone { GOOD, WARN, BAD }

/** One file line in the job detail's transferred or skipped group. */
data class JobFileLine(
    val relPath: String,
    val note: String = "",
    val bytes: Long = 0,
    val ok: Boolean = true,
)

/**
 * Everything the job detail screen shows.
 *
 * Built here rather than on each screen, because both ends are required to show the
 * same numbers in the same order and the fastest way for that to stop being true is
 * for two screens to each fold the same statistics their own way. The row order comes
 * from [JOB_DETAIL_ROWS], so a row cannot be added on one end and forgotten on the
 * other.
 */
data class JobDetails(
    val jobId: String,
    val title: String,
    val headline: String,
    val tone: JobTone,
    val summary: String,
    val rows: List<Pair<String, String>>,
    val transferred: List<JobFileLine>,
    val transferredTotal: Int,
    val skipped: List<JobFileLine>,
    val skippedTotal: Int,
    val resumeNote: String,
    val logs: List<LogEntry>,
) {
    /** How many more file lines exist than are shown. */
    val moreTransferred: Int get() = (transferredTotal - transferred.size).coerceAtLeast(0)
    val moreSkipped: Int get() = (skippedTotal - skipped.size).coerceAtLeast(0)

    val canRetryFailedPairs: Boolean get() = tone == JobTone.WARN || tone == JobTone.BAD
}

object JobDetailsFactory {

    /**
     * Folds a finished run into what the detail screen reads.
     *
     * [log] supplies the run's own lines, taken by time window: a run knows when it
     * started and when it stopped, and that is the only honest way to attribute a log
     * line to it without threading a job id through every layer that logs.
     */
    fun of(record: JobRecord, log: AppLog? = null): JobDetails {
        val result = record.result
        val stats = result.stats
        val duration = result.durationMs
        val pairs = result.pairs
        val counts = result.changeCounts()

        val transferredItems = pairs.flatMap { it.transferred }
        val skippedItems = pairs.flatMap { it.skippedItems }

        val headline = when (result.status) {
            JobStatus.COMPLETED -> "完成"
            JobStatus.PARTIAL_FAILED -> "部分失败"
            JobStatus.FAILED -> "失败"
            JobStatus.CANCELLED -> "已取消"
        }
        val tone = when (result.status) {
            JobStatus.COMPLETED -> JobTone.GOOD
            JobStatus.PARTIAL_FAILED, JobStatus.CANCELLED -> JobTone.WARN
            JobStatus.FAILED -> JobTone.BAD
        }

        val summary = listOf(
            Format.bytes(stats.copiedBytes),
            Format.count(stats.copiedFiles),
            Format.duration(duration),
            Format.rate(stats.copiedBytes, duration),
        ).joinToString(" · ")

        val blockedPairs = pairs.filter { it.status == PairStatus.BLOCKED || it.status == PairStatus.FAILED }
        val resumeNote = when {
            blockedPairs.isNotEmpty() ->
                "${blockedPairs.size} 对没有完成：${blockedPairs.joinToString("、") { it.pairName }}（失败隔离，其余已写入）"
            stats.copiedFiles > 0 && record.dryRun -> "试运行：比较完成，没有写入任何文件"
            else -> ""
        }

        val values = listOf(
            record.configName,
            Format.direction(record.direction),
            "${pairs.size} 对",
            Format.clockTime(result.startedAtMs),
            Format.clockTime(result.finishedAtMs),
            Format.bytes(stats.copiedBytes),
            Format.count(stats.copiedFiles),
            Format.duration(duration),
            Format.rate(stats.copiedBytes, duration),
            "${counts.create} · ${counts.update}",
            "${stats.deletedFiles} · ${stats.conflicts}",
            "${stats.skipped} · ${stats.failures}",
            resumeNote.ifEmpty { "—" },
        )

        return JobDetails(
            jobId = record.jobId,
            title = "${record.configName} · ${Format.clockTime(result.startedAtMs)}",
            headline = if (record.dryRun) "$headline（试运行）" else headline,
            tone = tone,
            summary = summary,
            rows = JOB_DETAIL_ROWS.zip(values),
            transferred = transferredItems.take(JobDetail.FILE_LIMIT).map { it.toLine(ok = true) },
            transferredTotal = transferredItems.size,
            skipped = skippedItems.take(JobDetail.FILE_LIMIT).map { it.toLine(ok = false) },
            skippedTotal = skippedItems.size,
            resumeNote = resumeNote,
            logs = log?.entries()?.filter { it.atMs in result.startedAtMs..(result.finishedAtMs + 1_000) }.orEmpty(),
        )
    }

    private fun JobItem.toLine(ok: Boolean) = JobFileLine(
        relPath = relPath,
        note = error.ifEmpty { Format.skipReason(skippedReason) },
        bytes = bytes,
        ok = ok && this.ok,
    )
}

/**
 * The difference summary shown before a run.
 *
 * A preview is a promise about what is going to happen, so it is counted from the same
 * actions the engine would execute rather than from a second comparison written for the
 * screen.
 */
data class SyncPreview(
    val pairs: List<PairPreview>,
) {
    val totals: PairPreview get() = pairs.fold(PairPreview("", "")) { acc, pair ->
        PairPreview(
            pairId = "",
            pairName = "",
            create = acc.create + pair.create,
            update = acc.update + pair.update,
            delete = acc.delete + pair.delete,
            conflict = acc.conflict + pair.conflict,
            skip = acc.skip + pair.skip,
            bytes = acc.bytes + pair.bytes,
        )
    }

    val blocked: List<PairPreview> get() = pairs.filter { it.blockReason.isNotEmpty() }
}

/**
 * How a run's work divides into 新增 / 更新 / 删除 / 冲突 / 跳过.
 *
 * Read off the **comparison**, not off the action kinds. Counting actions said the
 * opposite of the truth twice over: every copied file is a `COPY`, so every new file was
 * reported as 更新 and 新增 counted directories only; and the job detail did not count at
 * all — it printed the number of copied files twice, once under each heading.
 *
 * The distinction a reader wants is "did the other side have this before": if it did not,
 * the file is new; if it had an older copy, the file is updated. That is in the diff, so
 * that is where this reads it.
 */
data class ChangeCounts(
    val create: Int = 0,
    val update: Int = 0,
    val delete: Int = 0,
    val conflict: Int = 0,
    val skip: Int = 0,
    val bytes: Long = 0,
) {
    operator fun plus(other: ChangeCounts) = ChangeCounts(
        create = create + other.create,
        update = update + other.update,
        delete = delete + other.delete,
        conflict = conflict + other.conflict,
        skip = skip + other.skip,
        bytes = bytes + other.bytes,
    )
}

/** What one folder pair's plan amounts to. */
fun PairOutcome.changeCounts(): ChangeCounts {
    val actions = plan?.actions.orEmpty()
    fun copies() = actions.filter {
        it.kind == ActionKind.COPY_LEFT_TO_RIGHT || it.kind == ActionKind.COPY_RIGHT_TO_LEFT
    }
    val copied = copies().filterNot { it.resolvesConflict }

    return ChangeCounts(
        create = actions.count { it.kind == ActionKind.MKDIR } +
            copied.count { it.diff.kind == DiffKind.LEFT_ONLY || it.diff.kind == DiffKind.RIGHT_ONLY },
        update = copied.count {
            it.diff.kind == DiffKind.LEFT_NEWER || it.diff.kind == DiffKind.RIGHT_NEWER ||
                it.diff.kind == DiffKind.DIFFERENT || it.diff.kind == DiffKind.TYPE_MISMATCH
        },
        delete = actions.count { it.kind == ActionKind.DELETE_LEFT || it.kind == ActionKind.DELETE_RIGHT },
        conflict = actions.count { it.resolvesConflict },
        skip = actions.count { it.kind == ActionKind.NONE },
        bytes = copies().sumOf { it.diff.right?.size ?: it.diff.left?.size ?: 0L },
    )
}

/** The same, over every pair of a run. */
fun JobResult.changeCounts(): ChangeCounts =
    pairs.fold(ChangeCounts()) { acc, pair -> acc + pair.changeCounts() }

data class PairPreview(
    val pairId: String,
    val pairName: String,
    val create: Int = 0,
    val update: Int = 0,
    val delete: Int = 0,
    val conflict: Int = 0,
    val skip: Int = 0,
    val blockReason: String = "",
    val bytes: Long = 0,
) {
    val total: Int get() = create + update + delete + conflict + skip

    fun sentence(): String = listOf(
        "新增 $create",
        "更新 $update",
        "删除 $delete",
        "冲突 $conflict",
        "跳过 $skip",
    ).joinToString(" · ")
}
