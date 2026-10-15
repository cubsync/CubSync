package io.github.cubsync.core.engine

import io.github.cubsync.core.spi.RootRef
import io.github.cubsync.core.util.Algorithms
import io.github.cubsync.core.util.newDigest

/** What one file looked like the last time both sides agreed. */
data class SnapshotEntry(
    val relPath: String,
    val size: Long,
    val mtimeMs: Long,
    val hash: String? = null,
)

/**
 * The record of the last successful run of one folder pair.
 *
 * This is what separates "a new file appeared" from "a file was moved or
 * deleted", and what turns a one-sided edit into a conflict. It is keyed by
 * relative path so it survives a folder being renamed on disk.
 */
data class PairSnapshot(
    val pairId: String,
    val rev: Long = 0,
    val takenAtMs: Long = 0,
    val leftFingerprint: String = "",
    val rightFingerprint: String = "",
    val entries: Map<String, SnapshotEntry> = emptyMap(),
) {
    /**
     * A snapshot is only usable while it describes the same two roots. A changed
     * fingerprint means the pair now points somewhere else, and the next run has
     * to be treated as a first sync.
     */
    fun matches(left: RootRef, right: RootRef): Boolean =
        leftFingerprint == RootFingerprint.of(left) && rightFingerprint == RootFingerprint.of(right)

    fun toEntries(files: List<io.github.cubsync.core.spi.FileMeta>, hashes: Map<String, String>): Map<String, SnapshotEntry> =
        files.filterNot { it.isDirectory }.associate { file ->
            file.relPath to SnapshotEntry(
                relPath = file.relPath,
                size = file.size,
                mtimeMs = file.mtimeMs,
                hash = hashes[file.relPath],
            )
        }
}

interface SnapshotStore {
    suspend fun load(pairId: String): PairSnapshot?

    suspend fun save(snapshot: PairSnapshot)

    suspend fun invalidate(pairId: String)
}

/** Used by tests and by single-shot CLI runs that keep no history. */
class InMemorySnapshotStore : SnapshotStore {
    private val snapshots = LinkedHashMap<String, PairSnapshot>()

    override suspend fun load(pairId: String): PairSnapshot? = snapshots[pairId]

    override suspend fun save(snapshot: PairSnapshot) {
        snapshots[snapshot.pairId] = snapshot
    }

    override suspend fun invalidate(pairId: String) {
        snapshots.remove(pairId)
    }

    fun all(): List<PairSnapshot> = snapshots.values.toList()
}

/**
 * Identifies a root by identity and path, not by where it is mounted.
 *
 * The point is to notice that a configuration now points at a different folder
 * than the one its snapshot was taken from, which must not be mistaken for a
 * synchronised state.
 */
object RootFingerprint {
    fun of(root: RootRef): String {
        val normalized = root.path.replace('\\', '/').trimEnd('/').lowercase()
        val digest = newDigest(Algorithms.MD5)
        val bytes = "${root.backendId}|$normalized".encodeToByteArray()
        digest.update(bytes)
        return digest.hex().take(16)
    }
}
