package io.github.cubsync.cli

import io.github.cubsync.core.model.SyncConfig

/**
 * Rendering for configurations.
 *
 * Kept beside the job summary rather than in it because a configuration is a
 * document — it is what a user saves, exports and shows to somebody asking "what
 * does your sync actually do?" — and the two have nothing in common beyond columns.
 */
object ConfigOutput {

    fun list(configs: List<SyncConfig>, json: Boolean): String {
        if (json) {
            val items = configs.joinToString(",") { config ->
                val fields = listOf(
                    "\"configId\":${Quote.of(config.configId)}",
                    "\"name\":${Quote.of(config.name)}",
                    "\"direction\":${Quote.of(config.direction.name.lowercase())}",
                    "\"compare\":${Quote.of(config.compare.name.lowercase())}",
                    "\"pairs\":${config.pairs.size}",
                    "\"favorite\":${config.favorite}",
                )
                "{${fields.joinToString(",")}}"
            }
            return "[$items]"
        }
        val out = StringBuilder()
        out.append(Columns.pad("CONFIG ID", 20))
            .append(Columns.pad("NAME", 32))
            .append(Columns.pad("DIRECTION", 22))
            .append(Columns.pad("COMPARE", 18))
            .append(Columns.pad("PAIRS", 7))
            .append("FAVOURITE")
            .append('\n')
        for (config in configs) {
            out.append(Columns.pad(config.configId.take(19), 20))
                .append(Columns.pad(config.name.take(31), 32))
                .append(Columns.pad(config.direction.name.lowercase(), 22))
                .append(Columns.pad(config.compare.name.lowercase(), 18))
                .append(Columns.pad(config.pairs.size.toString(), 7))
                .append(if (config.favorite) "yes" else "")
                .append('\n')
        }
        return Columns.trim(out.toString())
    }

    fun summary(config: SyncConfig, json: Boolean): String {
        if (json) return summaryJson(config)

        val out = StringBuilder()
        out.append("${config.name} (${config.configId})\n")
        out.append("  schema      ${config.schemaVersion}\n")
        out.append("  direction   ${config.direction.name.lowercase()}\n")
        out.append("  compare     ${config.compare.name.lowercase()}\n")
        out.append("  conflict    ${config.conflict.name.lowercase()}\n")
        out.append("  delete      ${config.delete.name.lowercase()}\n")
        out.append("  parallel    ${config.parallelism}\n")
        out.append("  dry run     ${config.dryRun}\n")
        out.append("  filter      ${describeFilter(config)}\n")
        out.append("  pairs       ${config.pairs.size}\n")
        for (pair in config.pairs.sortedBy { it.order }) {
            out.append("    ${pair.order}. ${pair.name.ifEmpty { pair.pairId }}${describePair(pair)}\n")
            out.append("       left   ${pair.left.backendId}  ${pair.left.rootPath}\n")
            out.append("       right  ${pair.right.backendId}  ${pair.right.rootPath}\n")
        }
        return Columns.trim(out.toString())
    }

    private fun describeFilter(config: SyncConfig): String {
        val filter = config.globalFilter
        if (!filter.enabled) return "disabled"
        if (filter.globs.isEmpty() && filter.includeHidden) return "none"
        val parts = ArrayList<String>()
        if (filter.globs.isNotEmpty()) parts.add("exclude " + filter.globs.joinToString(", "))
        if (filter.includeHidden) parts.add("include hidden")
        if (filter.minSize >= 0) parts.add("min ${Output.humanBytes(filter.minSize)}")
        if (filter.maxSize >= 0) parts.add("max ${Output.humanBytes(filter.maxSize)}")
        return if (parts.isEmpty()) "none" else parts.joinToString("; ")
    }

    private fun describePair(pair: io.github.cubsync.core.model.FolderPair): String {
        val notes = ArrayList<String>()
        pair.directionOverride?.let { notes.add("direction ${it.name.lowercase()}") }
        val override = pair.filterOverride
        when {
            override == null -> notes.add("filter inherited")
            !override.enabled -> notes.add("filter off")
            override.globs.isNotEmpty() -> notes.add("filter ${override.globs.joinToString(", ")}")
            override.includeHidden -> notes.add("filter none")
        }
        if (!pair.enabled) notes.add("disabled")
        return if (notes.isEmpty()) "" else "  [${notes.joinToString("; ")}]"
    }

    private fun summaryJson(config: SyncConfig): String {
        val pairs = config.pairs.joinToString(",") { pair ->
            val override = pair.filterOverride
            val fields = listOf(
                "\"pairId\":${Quote.of(pair.pairId)}",
                "\"name\":${Quote.of(pair.name)}",
                "\"order\":${pair.order}",
                "\"enabled\":${pair.enabled}",
                "\"leftBackend\":${Quote.of(pair.left.backendId)}",
                "\"leftRoot\":${Quote.of(pair.left.rootPath)}",
                "\"rightBackend\":${Quote.of(pair.right.backendId)}",
                "\"rightRoot\":${Quote.of(pair.right.rootPath)}",
                "\"filterOverride\":${if (override == null) "null" else Quote.of(if (override.enabled) "custom" else "off")}",
                "\"directionOverride\":${pair.directionOverride?.let { Quote.of(it.name.lowercase()) } ?: "null"}",
            )
            "{${fields.joinToString(",")}}"
        }
        val fields = listOf(
            "\"configId\":${Quote.of(config.configId)}",
            "\"name\":${Quote.of(config.name)}",
            "\"schemaVersion\":${config.schemaVersion}",
            "\"direction\":${Quote.of(config.direction.name.lowercase())}",
            "\"compare\":${Quote.of(config.compare.name.lowercase())}",
            "\"conflict\":${Quote.of(config.conflict.name.lowercase())}",
            "\"delete\":${Quote.of(config.delete.name.lowercase())}",
            "\"parallelism\":${config.parallelism}",
            "\"dryRun\":${config.dryRun}",
            "\"pairs\":[$pairs]",
        )
        return "{${fields.joinToString(",")}}"
    }
}

/** Column padding and minimal JSON string quoting, shared by every listing. */
internal object Columns {
    fun pad(text: String, width: Int): String =
        if (text.length >= width) "$text " else text + " ".repeat(width - text.length)

    fun trim(text: String): String = text.lineSequence().joinToString("\n") { it.trimEnd() }
}

/** Just enough JSON escaping for the hand-written summaries; no key can be trusted. */
internal object Quote {
    fun of(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (character in value) {
            when (character) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n', '\r', '\t' -> out.append(' ')
                else -> out.append(character)
            }
        }
        out.append('"')
        return out.toString()
    }
}
