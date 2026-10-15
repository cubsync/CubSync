package io.github.cubsync.core.engine

import io.github.cubsync.core.model.CompareMode
import io.github.cubsync.core.spi.FileMeta
import io.github.cubsync.core.util.Algorithms
import io.github.cubsync.core.util.newDigest

/** How one path on the two sides relates. */
enum class DiffKind {
    /** Present on both sides, judged identical. */
    EQUAL,
    LEFT_ONLY,
    RIGHT_ONLY,
    /** The left file is newer than the right one. */
    LEFT_NEWER,
    RIGHT_NEWER,
    /** Same timestamp, different content — nothing to order them by. */
    DIFFERENT,
    /** Both sides changed since the last run. */
    CONFLICT,
    /** A file on one side, a directory on the other. */
    TYPE_MISMATCH,
    /** Same content as a path recorded in the snapshot that no longer exists. */
    RENAMED,
    ;

    val needsAction: Boolean get() = this != EQUAL
}

data class DiffEntry(
    val relPath: String,
    val kind: DiffKind,
    val left: FileMeta? = null,
    val right: FileMeta? = null,
    val leftHash: String? = null,
    val rightHash: String? = null,
    /** For [DiffKind.RENAMED]: the path this entry was moved from. */
    val renamedFrom: String? = null,
) {
    val isDirectory: Boolean get() = left?.isDirectory ?: right?.isDirectory ?: false
}

/**
 * Decides how the two sides of one folder pair relate.
 *
 * Pure: it sees two listings, optionally a set of hashes, optionally the previous
 * snapshot, and returns classifications. No IO, no time source, no ordering
 * dependence — which is what makes the comparison matrix testable as a table.
 */
class TreeComparator(
    private val mode: CompareMode,
    private val toleranceMs: Long,
    private val caseSensitive: Boolean = true,
) {

    fun compare(
        left: List<FileMeta>,
        right: List<FileMeta>,
        leftHashes: Map<String, String> = emptyMap(),
        rightHashes: Map<String, String> = emptyMap(),
        snapshot: PairSnapshot? = null,
    ): List<DiffEntry> {
        val leftMap = index(left)
        val rightMap = index(right)
        val paths = LinkedHashSet<String>(leftMap.size + rightMap.size)
        paths.addAll(leftMap.keys)
        paths.addAll(rightMap.keys)

        val out = ArrayList<DiffEntry>(paths.size)
        for (key in paths) {
            val l = leftMap[key]
            val r = rightMap[key]
            out.add(classify(key, l, r, leftHashes, rightHashes, snapshot))
        }

        val renamedFrom = detectRenames(out, snapshot)
        val resolved = if (renamedFrom.isEmpty()) {
            out
        } else {
            out.map { entry ->
                val from = renamedFrom[entry.relPath]
                if (from == null) entry else entry.copy(kind = DiffKind.RENAMED, renamedFrom = from)
            }
        }
        // Lexicographic order also puts a directory before its own children.
        return resolved.sortedWith(compareBy({ it.relPath }))
    }

    /** Lists a directory tree; always returns entries sorted, paths normalised to `/`. */
    private fun index(entries: List<FileMeta>): Map<String, FileMeta> {
        val map = LinkedHashMap<String, FileMeta>(entries.size * 2)
        for (entry in entries) {
            val normalized = normalize(entry.relPath)
            val key = if (caseSensitive) normalized else normalized.lowercase()
            // Two entries that differ only by case collide on a case-insensitive
            // root; the first one wins the index and the collision is reported by
            // classify() when the other side disagrees.
            map.putIfAbsent(key, entry.copy(relPath = normalized))
        }
        return map
    }

    private fun classify(
        key: String,
        l: FileMeta?,
        r: FileMeta?,
        leftHashes: Map<String, String>,
        rightHashes: Map<String, String>,
        snapshot: PairSnapshot?,
    ): DiffEntry {
        if (l == null && r == null) error("classify called with no sides for $key")
        if (l == null) return DiffEntry(r!!.relPath, DiffKind.RIGHT_ONLY, right = r)
        if (r == null) return DiffEntry(l.relPath, DiffKind.LEFT_ONLY, left = l)

        if (!caseSensitive && l.relPath != r.relPath) {
            // Same entry under a different spelling. Without a case-preserving
            // filesystem the two names cannot coexist, so this is a conflict
            // instead of a silent overwrite.
            return DiffEntry(l.relPath, DiffKind.CONFLICT, left = l, right = r)
        }
        if (l.isDirectory != r.isDirectory) {
            return DiffEntry(l.relPath, DiffKind.TYPE_MISMATCH, left = l, right = r)
        }
        if (l.isDirectory) {
            return DiffEntry(l.relPath, DiffKind.EQUAL, left = l, right = r)
        }

        val sameSize = l.size == r.size
        val timeDelta = l.mtimeMs - r.mtimeMs
        val sameTime = timeDelta <= toleranceMs && timeDelta >= -toleranceMs

        var contentEqual: Boolean? = null
        if (sameSize && mode == CompareMode.CONTENT_HASH) {
            val lh = leftHashes[l.relPath]
            val rh = rightHashes[r.relPath]
            if (lh != null && rh != null) contentEqual = lh == rh
        }

        val kind = when (mode) {
            CompareMode.SIZE_ONLY -> when {
                !sameSize -> newerSide(timeDelta, l, r, DiffKind.DIFFERENT)
                snapshotChangedBothWays(snapshot, l, r) -> DiffKind.CONFLICT
                else -> DiffKind.EQUAL
            }

            CompareMode.MTIME_AND_SIZE -> when {
                sameSize && sameTime -> DiffKind.EQUAL
                snapshotChangedBothWays(snapshot, l, r) -> DiffKind.CONFLICT
                sameSize -> if (timeDelta > 0) DiffKind.LEFT_NEWER else DiffKind.RIGHT_NEWER
                else -> newerSide(timeDelta, l, r, DiffKind.DIFFERENT)
            }

            CompareMode.CONTENT_HASH -> when {
                !sameSize -> newerSide(timeDelta, l, r, DiffKind.DIFFERENT)
                contentEqual == true -> DiffKind.EQUAL
                contentEqual == false -> when {
                    snapshotChangedBothWays(snapshot, l, r) -> DiffKind.CONFLICT
                    sameTime -> DiffKind.DIFFERENT
                    timeDelta > 0 -> DiffKind.LEFT_NEWER
                    else -> DiffKind.RIGHT_NEWER
                }

                // No hash available (backend cannot hash, or hashing was skipped):
                // degrade to the timestamp rule rather than guessing.
                sameTime -> DiffKind.EQUAL
                timeDelta > 0 -> DiffKind.LEFT_NEWER
                else -> DiffKind.RIGHT_NEWER
            }
        }
        return DiffEntry(
            relPath = l.relPath,
            kind = kind,
            left = l,
            right = r,
            leftHash = leftHashes[l.relPath],
            rightHash = rightHashes[r.relPath],
        )
    }

    private fun newerSide(timeDelta: Long, l: FileMeta, r: FileMeta, tie: DiffKind): DiffKind = when {
        timeDelta > toleranceMs -> DiffKind.LEFT_NEWER
        timeDelta < -toleranceMs -> DiffKind.RIGHT_NEWER
        else -> tie
    }

    /**
     * True when the snapshot says the previous common state differed from *both*
     * current states — meaning both sides were edited, which is a real conflict
     * rather than a one-sided update.
     */
    private fun snapshotChangedBothWays(snapshot: PairSnapshot?, l: FileMeta, r: FileMeta): Boolean {
        val previous = snapshot?.entries?.get(normalize(l.relPath)) ?: return false
        val leftChanged = l.size != previous.size || kotlin.math.abs(l.mtimeMs - previous.mtimeMs) > toleranceMs
        val rightChanged = r.size != previous.size || kotlin.math.abs(r.mtimeMs - previous.mtimeMs) > toleranceMs
        if (!leftChanged || !rightChanged) return false
        return l.size != r.size || kotlin.math.abs(l.mtimeMs - r.mtimeMs) > toleranceMs
    }

    /**
     * Maps a one-sided path onto the snapshot path it appears to have moved from.
     *
     * A rename is only claimed when the old path is gone from *both* sides and the
     * snapshot remembers it, because otherwise "new file here, deleted file there"
     * is the more likely explanation.
     */
    private fun detectRenames(diffs: List<DiffEntry>, snapshot: PairSnapshot?): Map<String, String> {
        if (snapshot == null || snapshot.entries.isEmpty()) return emptyMap()
        val oneSided = diffs.filter { it.kind == DiffKind.LEFT_ONLY || it.kind == DiffKind.RIGHT_ONLY }
        if (oneSided.isEmpty()) return emptyMap()
        val present = diffs.mapTo(HashSet()) { it.relPath }
        val vanished = snapshot.entries.values.filter { !present.contains(it.relPath) }
        if (vanished.isEmpty()) return emptyMap()

        val out = LinkedHashMap<String, String>()
        for (diff in oneSided) {
            val side = diff.left ?: diff.right ?: continue
            val match = vanished.firstOrNull { candidate ->
                !out.values.contains(candidate.relPath) &&
                    candidate.size == side.size &&
                    (candidate.hash != null && diff.let { it.leftHash ?: it.rightHash } == candidate.hash ||
                        kotlin.math.abs(candidate.mtimeMs - side.mtimeMs) <= toleranceMs)
            } ?: continue
            out[diff.relPath] = match.relPath
        }
        return out
    }

    companion object {
        fun normalize(path: String): String {
            var p = path.replace('\\', '/')
            while (p.startsWith("./")) p = p.removePrefix("./")
            while (p.endsWith("/")) p = p.dropLast(1)
            return p
        }

        /** Convenience for the common "hash a file and compare" path. */
        fun hashOf(algorithm: String, bytes: ByteArray): String =
            newDigest(Algorithms.require(algorithm)).apply { update(bytes) }.hex()
    }
}
