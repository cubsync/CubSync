package io.github.cubsync.cli

import io.github.cubsync.core.engine.JobResult
import io.github.cubsync.core.engine.JobStatus
import io.github.cubsync.core.engine.PairOutcome
import io.github.cubsync.core.engine.PairStatus
import io.github.cubsync.core.engine.TransferStats
import io.github.cubsync.service.BuildInfo

/**
 * Human-readable output.
 *
 * Columns are padded so a summary can be compared down the page, and the exit code
 * distinguishes "everything ran" from "something did not" — which is what a script
 * around this command will actually look at.
 */
object Output {

    fun version(): String = """
        cubsync ${BuildInfo.VERSION}
        protocol ${BuildInfo.PROTOCOL_VERSION}
        ${BuildInfo.PLATFORM}
    """.trimIndent()

    fun usage(): String = """
        cubsync - file synchronisation, one engine, many backends

        Usage:
          cubsync serve   --root DIR [--root DIR ...] [--port N] [--bind ADDR] [--name NAME]
          cubsync ls      HOST:PORT [PATH]
          cubsync push    --to HOST:PORT --remote DIR --from DIR [options]
          cubsync pull    --from HOST:PORT --remote DIR --to DIR [options]
          cubsync compare --to HOST:PORT --remote DIR --from DIR [options]
          cubsync run     CONFIG [--pair NAME] [--dry-run] [--json]
          cubsync config  new|list|show|add-pair|export|import ...

        Configurations:
          cubsync config new --name NAME [--direction D] [--compare C] [--delete P]
          cubsync config add-pair ID --left local --left-root DIR \
                                    --right HOST:PORT --right-root DIR [--name N]
          cubsync config list | show ID | export ID --out FILE | import FILE

        Common options:
          --direction two-way|mirror-to|mirror-from|update-to|update-from   (default: update-to)
          --compare   mtime|hash|size                                       (default: mtime)
          --conflict  ask|prefer-left|prefer-right|keep-both|skip            (default: ask)
          --delete    recycle|propagate|keep                                 (default: recycle)
          --parallel  N                                                      (default: 1)
          --filter    GLOB                                                   (repeatable)
          --include-hidden
          --dry-run
          --state-dir DIR            configurations and snapshots (default: ~/.cubsync)
          --json
          --quiet | --verbose

        Exit codes:
          0 every pair finished   1 something did not   2 the command line was wrong
    """.trimIndent()

    fun jobSummary(result: JobResult, json: Boolean): String {
        if (json) return jsonSummary(result)
        val out = StringBuilder()
        val stats = result.stats
        out.append("job ${result.jobId} - ${result.status.name.lowercase()} in ${result.durationMs} ms\n")
        out.append("pairs: ${result.pairs.size}\n\n")
        out.append(pad("PAIR", 24)).append(pad("STATUS", 14)).append(pad("FILES", 8))
            .append(pad("BYTES", 14)).append(pad("DEL", 6)).append(pad("CONFLICT", 10)).append("NOTE").append('\n')
        for (pair in result.pairs) {
            out.append(pad(pair.pairName.take(23), 24))
                .append(pad(pair.status.name.lowercase(), 14))
                .append(pad(pair.stats.copiedFiles.toString(), 8))
                .append(pad(humanBytes(pair.stats.copiedBytes), 14))
                .append(pad(pair.stats.deletedFiles.toString(), 6))
                .append(pad(pair.stats.conflicts.toString(), 10))
                .append(if (pair.error.isNotEmpty()) pair.error.take(120) else "")
                .append('\n')
        }
        out.append('\n')
        out.append("total: ${stats.copiedFiles} file(s), ${humanBytes(stats.copiedBytes)}, ")
        out.append("${stats.deletedFiles} deleted, ${stats.conflicts} conflict(s), ${stats.failures} failure(s)\n")

        val skips = result.pairs.flatMap { it.skippedItems }.take(20)
        if (skips.isNotEmpty()) {
            out.append("\nskipped (first ${skips.size}):\n")
            for (item in skips) {
                out.append("  ").append(pad(item.relPath.take(60), 62)).append(item.skippedReason).append('\n')
            }
        }
        // Padding the columns leaves spaces at the end of short rows; a copied
        // summary should not carry them.
        return trimTrailing(out.toString())
    }

    fun pairDetail(pair: PairOutcome): String {
        val out = StringBuilder()
        out.append("pair ${pair.pairId} (${pair.pairName}) - ${pair.status.name.lowercase()}\n")
        for (item in pair.items.take(50)) {
            out.append("  ").append(pad(item.relPath.take(60), 62))
                .append(pad(item.action.name.lowercase(), 22))
                .append(if (item.error.isNotEmpty()) item.error else item.skippedReason)
                .append('\n')
        }
        if (pair.items.size > 50) out.append("  ... ${pair.items.size - 50} more\n")
        return trimTrailing(out.toString())
    }

    private fun trimTrailing(text: String): String =
        text.lineSequence().joinToString("\n") { it.trimEnd() }

    fun humanBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KiB", "MiB", "GiB", "TiB")
        var value = bytes.toDouble() / 1024
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        return "${round(value)} ${units[unit]}"
    }

    private fun round(value: Double): String {
        val scaled = (value * 10).toLong()
        return "${scaled / 10}.${scaled % 10}"
    }

    private fun pad(text: String, width: Int): String =
        if (text.length >= width) "$text " else text + " ".repeat(width - text.length)

    private fun jsonSummary(result: JobResult): String {
        val stats = result.stats
        val pairs = result.pairs.joinToString(",") { pair ->
            """{"pairId":"${pair.pairId}","status":"${pair.status.name.lowercase()}",""" +
                """"copied":${pair.stats.copiedFiles},"bytes":${pair.stats.copiedBytes},""" +
                """"deleted":${pair.stats.deletedFiles},"conflicts":${pair.stats.conflicts},""" +
                """"failures":${pair.stats.failures},"error":${quote(pair.error)}}"""
        }
        return """{"jobId":"${result.jobId}","status":"${result.status.name.lowercase()}",""" +
            """"durationMs":${result.durationMs},"pairs":[$pairs],""" +
            """"totals":{"copied":${stats.copiedFiles},"bytes":${stats.copiedBytes},""" +
            """"deleted":${stats.deletedFiles},"conflicts":${stats.conflicts},"failures":${stats.failures}}}"""
    }

    private fun quote(value: String): String =
        '"' + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + '"'

    fun exitCodeFor(status: JobStatus): Int = if (status == JobStatus.COMPLETED) 0 else 1

    fun statsLine(stats: TransferStats): String =
        "${stats.copiedFiles} copied, ${humanBytes(stats.copiedBytes)}, ${stats.deletedFiles} deleted"

    fun pairStatusName(status: PairStatus): String = status.name.lowercase()
}
