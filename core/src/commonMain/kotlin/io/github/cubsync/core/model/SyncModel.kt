package io.github.cubsync.core.model

import kotlin.random.Random

/** Which side of a folder pair something lives on. */
enum class Side { LEFT, RIGHT }

/**
 * Synchronisation direction.
 *
 * `MIRROR_*` makes the target identical to the source, deletions included.
 * `UPDATE_*` only ever adds or replaces, never deletes.
 * `TWO_WAY` propagates the newer side in both directions.
 */
enum class SyncDirection {
    TWO_WAY,
    MIRROR_LEFT_TO_RIGHT,
    MIRROR_RIGHT_TO_LEFT,
    UPDATE_LEFT_TO_RIGHT,
    UPDATE_RIGHT_TO_LEFT,
    CUSTOM,
    ;

    val mirror: Boolean
        get() = this == MIRROR_LEFT_TO_RIGHT || this == MIRROR_RIGHT_TO_LEFT

    val deletesAllowed: Boolean get() = mirror

    val supported: Boolean get() = this != CUSTOM
}

/**
 * How two files are judged to be the same.
 *
 * [MTIME_AND_SIZE] tolerates a timestamp delta because FAT/exFAT store mtimes at
 * two-second granularity and network shares round differently.
 */
enum class CompareMode {
    MTIME_AND_SIZE,
    CONTENT_HASH,
    SIZE_ONLY,
    ;

    val needsHash: Boolean get() = this == CONTENT_HASH
}

/**
 * What to do when both sides changed since the last run.
 *
 * [PREFER_LEFT] and [PREFER_RIGHT] name the side that wins, in the same words
 * as the label the user sees — "left wins" writes the left version over the
 * right one. Naming the winner instead of the loser avoids the reading where
 * "overwrite left" means "overwrite *with* left".
 */
enum class ConflictPolicy {
    ASK,
    PREFER_LEFT,
    PREFER_RIGHT,
    KEEP_BOTH,
    SKIP,
}

/**
 * What happens to a file that exists only on the target of a mirror.
 *
 * [PROPAGATE] unlinks it, [RECYCLE] moves it into the per-root trash directory
 * so the deletion is reversible, [KEEP] leaves it alone.
 */
enum class DeletePolicy { PROPAGATE, RECYCLE, KEEP }

/**
 * Filter expression mode.
 *
 * [BLACKLIST] excludes everything matching, [WHITELIST] excludes everything *not*
 * matching. A blacklist with an empty pattern list therefore means "no filtering".
 */
enum class FilterMode { BLACKLIST, WHITELIST }

/**
 * Filter rules for a configuration or a single folder pair.
 *
 * [enabled] is the on/off switch and is what makes "no filtering for this pair" an
 * explicit state instead of an emergent property of an empty rule list. Size and
 * time bounds apply to files only; directories are never rejected by them, because
 * a directory has no meaningful size of its own.
 */
data class FilterSet(
    val enabled: Boolean = true,
    val mode: FilterMode = FilterMode.BLACKLIST,
    val globs: List<String> = emptyList(),
    val presets: List<String> = emptyList(),
    val includeHidden: Boolean = false,
    val minSize: Long = -1,
    val maxSize: Long = -1,
    val timeAfterMs: Long = -1,
    val timeBeforeMs: Long = -1,
) {
    /** True when this filter would reject nothing, including hidden entries. */
    val isEmpty: Boolean
        get() = !enabled || (globs.isEmpty() && presets.isEmpty() && includeHidden &&
            minSize < 0 && maxSize < 0 && timeAfterMs < 0 && timeBeforeMs < 0)
}

/** One end of a folder pair. */
data class Endpoint(
    val backendId: String,
    val rootPath: String,
    val rootFingerprint: String = "",
)

/**
 * A pair of folders to keep in sync.
 *
 * [filterOverride] is a three-state switch by construction: `null` inherits the
 * configuration-level filter, a non-null set replaces it, and an *empty* set
 * disables filtering for this pair.
 */
data class FolderPair(
    val pairId: String,
    val order: Int,
    val enabled: Boolean = true,
    val name: String = "",
    val left: Endpoint,
    val right: Endpoint,
    val filterOverride: FilterSet? = null,
    val directionOverride: SyncDirection? = null,
)

/** A saved synchronisation configuration: global policy plus a list of folder pairs. */
data class SyncConfig(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val configId: String,
    val name: String,
    val favorite: Boolean = false,
    val sortOrder: Int = 0,
    val direction: SyncDirection = SyncDirection.TWO_WAY,
    val compare: CompareMode = CompareMode.MTIME_AND_SIZE,
    val conflict: ConflictPolicy = ConflictPolicy.ASK,
    val delete: DeletePolicy = DeletePolicy.RECYCLE,
    val parallelism: Int = 1,
    val mtimeToleranceMs: Long = DEFAULT_MTIME_TOLERANCE_MS,
    val keepPermissions: Boolean = false,
    val keepSymlink: Boolean = false,
    val keepEmptyDir: Boolean = true,
    val dryRun: Boolean = false,
    val recycleRoot: String = "",
    val globalFilter: FilterSet = FilterSet(),
    val remotePolicyHash: String = "",
    val pairs: List<FolderPair> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA: Int = 1
        const val DEFAULT_MTIME_TOLERANCE_MS: Long = 2_000
    }
}

/** The filter a pair actually uses, after the inheritance rule is applied. */
object Filters {
    fun effective(global: FilterSet, override: FilterSet?): FilterSet = override ?: global
}

/** Identifier helper. Ids only need to be unique and stable inside one profile. */
object Ids {
    private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"

    fun newId(prefix: String, random: Random = Random.Default): String {
        val body = StringBuilder(12)
        repeat(12) { body.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        return if (prefix.isEmpty()) body.toString() else "$prefix-$body"
    }

    fun newPairId(random: Random = Random.Default): String = newId("pair", random)

    fun newConfigId(random: Random = Random.Default): String = newId("cfg", random)
}
