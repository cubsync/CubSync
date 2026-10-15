package io.github.cubsync.core.engine

import io.github.cubsync.core.model.ConflictPolicy
import io.github.cubsync.core.model.DeletePolicy
import io.github.cubsync.core.model.SyncDirection

/** One decision the engine made about one path. */
enum class ActionKind {
    NONE,
    COPY_LEFT_TO_RIGHT,
    COPY_RIGHT_TO_LEFT,
    DELETE_LEFT,
    DELETE_RIGHT,
    /** Both sides changed; the policy says the user has to decide. */
    CONFLICT_ASK,
    /** Both sides changed; [ConflictPolicy.KEEP_BOTH] keeps the loser as a copy. */
    CONFLICT_KEEP_BOTH,
    /** Both sides changed; the policy is to leave both files untouched. */
    CONFLICT_SKIP,
}

data class SyncAction(
    val relPath: String,
    val kind: ActionKind,
    val diff: DiffEntry,
    /** Deletions go to the per-root trash instead of being unlinked. */
    val recycle: Boolean = false,
    val note: String = "",
) {
    val mutatesLeft: Boolean get() = kind == ActionKind.COPY_RIGHT_TO_LEFT || kind == ActionKind.DELETE_LEFT
    val mutatesRight: Boolean get() = kind == ActionKind.COPY_LEFT_TO_RIGHT || kind == ActionKind.DELETE_RIGHT
    val resolvesConflict: Boolean get() = kind == ActionKind.CONFLICT_ASK || kind == ActionKind.CONFLICT_KEEP_BOTH ||
        kind == ActionKind.CONFLICT_SKIP
}

data class PairPlan(
    val pairId: String,
    val direction: SyncDirection,
    val actions: List<SyncAction>,
    val warnings: List<String> = emptyList(),
    /** Set when the plan must not be executed, for example after an overlap was found. */
    val blocked: Boolean = false,
    val blockReason: String = "",
) {
    val actionable: List<SyncAction> get() = actions.filter { it.kind != ActionKind.NONE && !it.resolvesConflict }
    val conflicts: List<SyncAction> get() = actions.filter { it.resolvesConflict }
}

/**
 * Turns classifications into decisions.
 *
 * Deliberately pure and total: every [DiffKind] is mapped for every direction, so
 * a new direction cannot silently fall through to "do nothing".
 */
object Planner {

    fun plan(
        pairId: String,
        diffs: List<DiffEntry>,
        direction: SyncDirection,
        conflict: ConflictPolicy,
        delete: DeletePolicy,
        snapshot: PairSnapshot? = null,
    ): PairPlan {
        if (!direction.supported) {
            return PairPlan(
                pairId = pairId,
                direction = direction,
                actions = emptyList(),
                warnings = listOf("direction $direction is not implemented yet; nothing was planned"),
                blocked = true,
                blockReason = "unsupported direction",
            )
        }
        val deletions = if (direction == SyncDirection.TWO_WAY) detectDeletions(diffs, snapshot) else emptySet()
        val actions = diffs.map { actionFor(pairId, it, direction, conflict, delete, deletions) }
        val warnings = buildList {
            if (delete == DeletePolicy.KEEP && direction.deletesAllowed) {
                add("deletions are not propagated because the delete policy is KEEP")
            }
            val ask = actions.count { it.kind == ActionKind.CONFLICT_ASK }
            if (ask > 0) add("$ask conflict(s) need a decision before they can be resolved")
        }
        return PairPlan(pairId, direction, actions, warnings)
    }

    private fun actionFor(
        pairId: String,
        diff: DiffEntry,
        direction: SyncDirection,
        conflict: ConflictPolicy,
        delete: DeletePolicy,
        deletions: Set<String>,
    ): SyncAction {
        val recycle = delete == DeletePolicy.RECYCLE
        return when (diff.kind) {
            DiffKind.EQUAL -> SyncAction(diff.relPath, ActionKind.NONE, diff)

            DiffKind.CONFLICT, DiffKind.TYPE_MISMATCH -> conflictAction(diff, conflict)

            DiffKind.RENAMED -> {
                // A rename is metadata: make the target match the source path.
                if (diff.renamedFrom == null) {
                    SyncAction(diff.relPath, ActionKind.NONE, diff, note = "rename without a source path")
                } else {
                    when {
                        diff.left != null -> SyncAction(diff.relPath, ActionKind.COPY_LEFT_TO_RIGHT, diff, note = "rename")
                        else -> SyncAction(diff.relPath, ActionKind.COPY_RIGHT_TO_LEFT, diff, note = "rename")
                    }
                }
            }

            DiffKind.LEFT_ONLY -> when (direction) {
                SyncDirection.TWO_WAY -> if (deletions.contains(diff.relPath)) {
                    SyncAction(diff.relPath, ActionKind.DELETE_RIGHT, diff, recycle, note = "deleted on the right")
                } else {
                    SyncAction(diff.relPath, ActionKind.COPY_LEFT_TO_RIGHT, diff)
                }

                SyncDirection.MIRROR_LEFT_TO_RIGHT, SyncDirection.UPDATE_LEFT_TO_RIGHT ->
                    SyncAction(diff.relPath, ActionKind.COPY_LEFT_TO_RIGHT, diff)

                SyncDirection.MIRROR_RIGHT_TO_LEFT ->
                    if (delete == DeletePolicy.KEEP) {
                        SyncAction(diff.relPath, ActionKind.NONE, diff, note = "kept: delete policy is KEEP")
                    } else {
                        SyncAction(diff.relPath, ActionKind.DELETE_LEFT, diff, recycle)
                    }

                SyncDirection.UPDATE_RIGHT_TO_LEFT -> SyncAction(diff.relPath, ActionKind.NONE, diff)
                else -> SyncAction(diff.relPath, ActionKind.NONE, diff)
            }

            DiffKind.RIGHT_ONLY -> when (direction) {
                SyncDirection.TWO_WAY -> if (deletions.contains(diff.relPath)) {
                    SyncAction(diff.relPath, ActionKind.DELETE_LEFT, diff, recycle, note = "deleted on the left")
                } else {
                    SyncAction(diff.relPath, ActionKind.COPY_RIGHT_TO_LEFT, diff)
                }

                SyncDirection.MIRROR_RIGHT_TO_LEFT, SyncDirection.UPDATE_RIGHT_TO_LEFT ->
                    SyncAction(diff.relPath, ActionKind.COPY_RIGHT_TO_LEFT, diff)

                SyncDirection.MIRROR_LEFT_TO_RIGHT ->
                    if (delete == DeletePolicy.KEEP) {
                        SyncAction(diff.relPath, ActionKind.NONE, diff, note = "kept: delete policy is KEEP")
                    } else {
                        SyncAction(diff.relPath, ActionKind.DELETE_RIGHT, diff, recycle)
                    }

                SyncDirection.UPDATE_LEFT_TO_RIGHT -> SyncAction(diff.relPath, ActionKind.NONE, diff)
                else -> SyncAction(diff.relPath, ActionKind.NONE, diff)
            }

            DiffKind.LEFT_NEWER -> when (direction) {
                SyncDirection.TWO_WAY, SyncDirection.MIRROR_LEFT_TO_RIGHT, SyncDirection.UPDATE_LEFT_TO_RIGHT ->
                    SyncAction(diff.relPath, ActionKind.COPY_LEFT_TO_RIGHT, diff)

                // A mirror whose source is the right side treats the left file as
                // stale and removes it; an update-only run leaves it alone.
                SyncDirection.MIRROR_RIGHT_TO_LEFT ->
                    if (delete == DeletePolicy.KEEP) {
                        SyncAction(diff.relPath, ActionKind.NONE, diff, note = "kept: delete policy is KEEP")
                    } else {
                        SyncAction(diff.relPath, ActionKind.DELETE_LEFT, diff, recycle)
                    }

                SyncDirection.UPDATE_RIGHT_TO_LEFT -> SyncAction(diff.relPath, ActionKind.NONE, diff)
                else -> SyncAction(diff.relPath, ActionKind.NONE, diff)
            }

            DiffKind.RIGHT_NEWER -> when (direction) {
                SyncDirection.TWO_WAY, SyncDirection.MIRROR_RIGHT_TO_LEFT, SyncDirection.UPDATE_RIGHT_TO_LEFT ->
                    SyncAction(diff.relPath, ActionKind.COPY_RIGHT_TO_LEFT, diff)

                SyncDirection.MIRROR_LEFT_TO_RIGHT ->
                    if (delete == DeletePolicy.KEEP) {
                        SyncAction(diff.relPath, ActionKind.NONE, diff, note = "kept: delete policy is KEEP")
                    } else {
                        SyncAction(diff.relPath, ActionKind.DELETE_RIGHT, diff, recycle)
                    }

                SyncDirection.UPDATE_LEFT_TO_RIGHT -> SyncAction(diff.relPath, ActionKind.NONE, diff)
                else -> SyncAction(diff.relPath, ActionKind.NONE, diff)
            }

            DiffKind.DIFFERENT -> when (direction) {
                SyncDirection.TWO_WAY -> conflictAction(diff, conflict)
                SyncDirection.MIRROR_LEFT_TO_RIGHT, SyncDirection.UPDATE_LEFT_TO_RIGHT ->
                    SyncAction(diff.relPath, ActionKind.COPY_LEFT_TO_RIGHT, diff)

                SyncDirection.MIRROR_RIGHT_TO_LEFT, SyncDirection.UPDATE_RIGHT_TO_LEFT ->
                    SyncAction(diff.relPath, ActionKind.COPY_RIGHT_TO_LEFT, diff)

                else -> SyncAction(diff.relPath, ActionKind.NONE, diff)
            }
        }
    }

    private fun conflictAction(diff: DiffEntry, conflict: ConflictPolicy): SyncAction = when (conflict) {
        ConflictPolicy.ASK -> SyncAction(diff.relPath, ActionKind.CONFLICT_ASK, diff)
        ConflictPolicy.PREFER_LEFT -> SyncAction(diff.relPath, ActionKind.COPY_LEFT_TO_RIGHT, diff, note = "left wins")
        ConflictPolicy.PREFER_RIGHT -> SyncAction(diff.relPath, ActionKind.COPY_RIGHT_TO_LEFT, diff, note = "right wins")
        ConflictPolicy.KEEP_BOTH -> SyncAction(diff.relPath, ActionKind.CONFLICT_KEEP_BOTH, diff)
        ConflictPolicy.SKIP -> SyncAction(diff.relPath, ActionKind.CONFLICT_SKIP, diff)
    }

    /**
     * Paths whose disappearance from one side is a deletion rather than a new file.
     *
     * The snapshot is the only evidence that can tell the two apart: if the path is
     * remembered from the last agreed state and the surviving side still looks
     * exactly like it did then, the other side deleted it. Without a snapshot the
     * engine assumes an addition, which is the guess that cannot lose data.
     */
    private fun detectDeletions(diffs: List<DiffEntry>, snapshot: PairSnapshot?): Set<String> {
        if (snapshot == null || snapshot.entries.isEmpty()) return emptySet()
        val out = HashSet<String>()
        for (diff in diffs) {
            val survivor = when (diff.kind) {
                DiffKind.LEFT_ONLY -> diff.left
                DiffKind.RIGHT_ONLY -> diff.right
                else -> null
            } ?: continue
            val previous = snapshot.entries[survivor.relPath] ?: continue
            val unchanged = previous.size == survivor.size &&
                kotlin.math.abs(previous.mtimeMs - survivor.mtimeMs) <= DEFAULT_DELETION_TOLERANCE_MS
            if (unchanged) out.add(diff.relPath)
        }
        return out
    }

    /**
     * Tolerance used when deciding that the surviving side is untouched. Wider than
     * the comparison tolerance because a filesystem may round the timestamp of a
     * file it merely copied.
     */
    const val DEFAULT_DELETION_TOLERANCE_MS: Long = 2_000
}
