package io.github.cubsync.core.protocol

/**
 * Backend capability bits.
 *
 * The engine never asks "what protocol is this?" — it asks what the negotiated
 * capability set allows and degrades accordingly. A backend that cannot seek
 * cannot resume; a backend without a batch hash cannot do content comparison.
 */
object Capabilities {
    const val NONE: Long = 0

    const val LIST: Long = 1L shl 0
    const val STAT: Long = 1L shl 1
    const val HASH: Long = 1L shl 2
    const val MKDIR: Long = 1L shl 3
    const val DELETE: Long = 1L shl 4
    const val RENAME: Long = 1L shl 5
    const val SET_MTIME: Long = 1L shl 6
    const val FREE_SPACE: Long = 1L shl 7

    /** Write at an arbitrary offset instead of only appending. */
    const val RANDOM_WRITE: Long = 1L shl 8
    const val RESUME: Long = 1L shl 9

    /** Hash several files in one round trip instead of one request per file. */
    const val BATCH_HASH: Long = 1L shl 10

    /** Whole tree in one (paged) response instead of directory-by-directory walks. */
    const val TREE_AT_ONCE: Long = 1L shl 11

    /** Parallel channels allowed. Only the first-party LAN protocol has this. */
    const val MULTI_CHANNEL: Long = 1L shl 12
    const val SYMLINK: Long = 1L shl 13
    const val PERMISSIONS: Long = 1L shl 14
    const val TRASH: Long = 1L shl 15

    private val NAMES: List<Pair<Long, String>> = listOf(
        LIST to "LIST",
        STAT to "STAT",
        HASH to "HASH",
        MKDIR to "MKDIR",
        DELETE to "DELETE",
        RENAME to "RENAME",
        SET_MTIME to "SET_MTIME",
        FREE_SPACE to "FREE_SPACE",
        RANDOM_WRITE to "RANDOM_WRITE",
        RESUME to "RESUME",
        BATCH_HASH to "BATCH_HASH",
        TREE_AT_ONCE to "TREE_AT_ONCE",
        MULTI_CHANNEL to "MULTI_CHANNEL",
        SYMLINK to "SYMLINK",
        PERMISSIONS to "PERMISSIONS",
        TRASH to "TRASH",
    )

    val ALL: Long = NAMES.fold(NONE) { acc, (bit, _) -> acc or bit }

    fun has(set: Long, bit: Long): Boolean = (set and bit) == bit

    fun describe(set: Long): List<String> = NAMES.filter { has(set, it.first) }.map { it.second }

    /** Effective capability set is the intersection; neither side may use what the other lacks. */
    fun negotiate(local: Long, remote: Long): Long = local and remote

    /** Bits the remote offers that we do not implement — useful in diagnostics. */
    fun unknownBits(local: Long, remote: Long): Long = remote and ALL.inv()
}
