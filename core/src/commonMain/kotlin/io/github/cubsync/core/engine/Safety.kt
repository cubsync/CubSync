package io.github.cubsync.core.engine

import io.github.cubsync.core.model.Side

/** The two roots of one folder pair, as the overlap check sees them. */
data class PairRoots(
    val pairId: String,
    val backendId: String,
    val leftPath: String,
    val rightPath: String,
)

enum class OverlapKind {
    /** A pair's two roots are the same folder or one contains the other. */
    SELF_NESTED,

    /** Two different pairs share a path, or one contains the other. */
    CROSS_NESTED,
}

data class OverlapIssue(
    val kind: OverlapKind,
    val pairId: String,
    val otherPairId: String?,
    val message: String,
)

/**
 * Refuses to run a plan that would copy a folder into itself.
 *
 * Synchronising `D:\data` with `D:\data\backup` does not converge — every pass
 * copies the backup into the backup — so the check happens before any IO rather
 * than being discovered halfway through. When an overlap exists, comparing is
 * still allowed; only execution is blocked.
 */
object PathOverlap {

    fun check(pairs: List<PairRoots>, caseSensitive: Boolean = true): List<OverlapIssue> {
        val issues = ArrayList<OverlapIssue>()
        val normalized = pairs.map { pair ->
            pair.copy(
                leftPath = normalize(pair.leftPath, caseSensitive),
                rightPath = normalize(pair.rightPath, caseSensitive),
            )
        }

        for (pair in normalized) {
            if (pair.leftPath.isEmpty() || pair.rightPath.isEmpty()) continue
            if (contains(pair.leftPath, pair.rightPath) || contains(pair.rightPath, pair.leftPath)) {
                issues.add(
                    OverlapIssue(
                        kind = OverlapKind.SELF_NESTED,
                        pairId = pair.pairId,
                        otherPairId = null,
                        message = "the two roots of this pair overlap: ${pair.leftPath} and ${pair.rightPath}",
                    ),
                )
            }
        }

        for (i in normalized.indices) {
            for (j in i + 1 until normalized.size) {
                val a = normalized[i]
                val b = normalized[j]
                if (a.backendId != b.backendId) continue
                val clash = endpointClash(a.leftPath, b) || endpointClash(a.rightPath, b) ||
                    endpointClash(b.leftPath, a) || endpointClash(b.rightPath, a)
                if (clash) {
                    issues.add(
                        OverlapIssue(
                            kind = OverlapKind.CROSS_NESTED,
                            pairId = a.pairId,
                            otherPairId = b.pairId,
                            message = "pairs ${a.pairId} and ${b.pairId} use overlapping folders",
                        ),
                    )
                }
            }
        }
        return issues
    }

    private fun endpointClash(own: String, other: PairRoots): Boolean =
        contains(own, other.leftPath) || contains(other.leftPath, own) ||
            contains(own, other.rightPath) || contains(other.rightPath, own)

    private fun contains(outer: String, inner: String): Boolean {
        if (outer.isEmpty() || inner.isEmpty()) return false
        if (outer == inner) return true
        return inner.startsWith(outer.trimEnd('/') + "/")
    }

    /**
     * Canonical form used only for the overlap comparison: forward slashes, one
     * separator between components, no trailing separator. It is not a filesystem
     * path and is never handed to a backend.
     */
    fun normalize(path: String, caseSensitive: Boolean): String {
        var p = path.replace('\\', '/')
        while (p.contains("//")) p = p.replace("//", "/")
        if (p.length > 1) p = p.trimEnd('/')
        if (p.isEmpty()) p = "/"
        return if (caseSensitive) p else p.lowercase()
    }
}

/**
 * Where a recycled file is put.
 *
 * The trash lives inside the root it belongs to, so a delete is a rename within
 * one filesystem — which is what makes it atomic and instant, and what makes it
 * survive a file whose name is too long for the target filesystem.
 */
object TrashLayout {
    const val TRASH_DIR: String = ".cub_trash"

    fun trashRelPath(configId: String, pairId: String, timestampMs: Long, relPath: String): String {
        val safeConfig = sanitize(configId)
        val safePair = sanitize(pairId)
        val safePath = relPath.replace('\\', '/').trimStart('/')
        return "$TRASH_DIR/$safeConfig/$safePair/$timestampMs/$safePath"
    }

    fun isTrashPath(relPath: String): Boolean {
        val normalized = relPath.replace('\\', '/')
        return normalized == TRASH_DIR || normalized.startsWith("$TRASH_DIR/")
    }

    private fun sanitize(value: String): String =
        value.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
}

/**
 * Name of the file created when a conflict is resolved by keeping both versions.
 *
 * The pair id is part of the name so that two pairs sharing a folder cannot
 * produce colliding conflict copies, and so a stray copy can be traced back to
 * the pair that produced it.
 */
object ConflictNaming {
    const val MARKER: String = "cubconflict"

    fun conflictName(relPath: String, pairId: String, timestampMs: Long, keptSide: Side): String {
        val normalized = relPath.replace('\\', '/')
        val dir = normalized.substringBeforeLast('/', "")
        val name = normalized.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        val suffix = "$MARKER-${sanitize(pairId)}-${keptSide.name.lowercase()}-$timestampMs"
        val renamed = "$stem.$suffix$ext"
        return if (dir.isEmpty()) renamed else "$dir/$renamed"
    }

    private fun sanitize(value: String): String =
        value.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
}
