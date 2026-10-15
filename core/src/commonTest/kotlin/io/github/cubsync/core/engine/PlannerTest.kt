package io.github.cubsync.core.engine

import io.github.cubsync.core.model.ConflictPolicy
import io.github.cubsync.core.model.DeletePolicy
import io.github.cubsync.core.model.SyncDirection
import io.github.cubsync.core.spi.FileMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlannerTest {

    private fun diff(path: String, kind: DiffKind): DiffEntry = DiffEntry(
        relPath = path,
        kind = kind,
        left = if (kind == DiffKind.RIGHT_ONLY) null else FileMeta(path, size = 10, mtimeMs = 10_000),
        right = if (kind == DiffKind.LEFT_ONLY) null else FileMeta(path, size = 10, mtimeMs = 10_000),
    )

    private data class Row(
        val direction: SyncDirection,
        val kind: DiffKind,
        val expected: ActionKind,
    )

    private val matrix = listOf(
        // Two-way: additions propagate both ways, updates follow the newer side.
        Row(SyncDirection.TWO_WAY, DiffKind.EQUAL, ActionKind.NONE),
        Row(SyncDirection.TWO_WAY, DiffKind.LEFT_ONLY, ActionKind.COPY_LEFT_TO_RIGHT),
        Row(SyncDirection.TWO_WAY, DiffKind.RIGHT_ONLY, ActionKind.COPY_RIGHT_TO_LEFT),
        Row(SyncDirection.TWO_WAY, DiffKind.LEFT_NEWER, ActionKind.COPY_LEFT_TO_RIGHT),
        Row(SyncDirection.TWO_WAY, DiffKind.RIGHT_NEWER, ActionKind.COPY_RIGHT_TO_LEFT),
        Row(SyncDirection.TWO_WAY, DiffKind.DIFFERENT, ActionKind.CONFLICT_ASK),
        Row(SyncDirection.TWO_WAY, DiffKind.CONFLICT, ActionKind.CONFLICT_ASK),
        Row(SyncDirection.TWO_WAY, DiffKind.TYPE_MISMATCH, ActionKind.CONFLICT_ASK),

        // Mirror left to right: the right side is made to look like the left.
        Row(SyncDirection.MIRROR_LEFT_TO_RIGHT, DiffKind.EQUAL, ActionKind.NONE),
        Row(SyncDirection.MIRROR_LEFT_TO_RIGHT, DiffKind.LEFT_ONLY, ActionKind.COPY_LEFT_TO_RIGHT),
        Row(SyncDirection.MIRROR_LEFT_TO_RIGHT, DiffKind.RIGHT_ONLY, ActionKind.DELETE_RIGHT),
        Row(SyncDirection.MIRROR_LEFT_TO_RIGHT, DiffKind.LEFT_NEWER, ActionKind.COPY_LEFT_TO_RIGHT),
        Row(SyncDirection.MIRROR_LEFT_TO_RIGHT, DiffKind.RIGHT_NEWER, ActionKind.DELETE_RIGHT),
        Row(SyncDirection.MIRROR_LEFT_TO_RIGHT, DiffKind.DIFFERENT, ActionKind.COPY_LEFT_TO_RIGHT),

        // Mirror right to left is the same with the sides swapped.
        Row(SyncDirection.MIRROR_RIGHT_TO_LEFT, DiffKind.LEFT_ONLY, ActionKind.DELETE_LEFT),
        Row(SyncDirection.MIRROR_RIGHT_TO_LEFT, DiffKind.RIGHT_ONLY, ActionKind.COPY_RIGHT_TO_LEFT),
        Row(SyncDirection.MIRROR_RIGHT_TO_LEFT, DiffKind.LEFT_NEWER, ActionKind.DELETE_LEFT),
        Row(SyncDirection.MIRROR_RIGHT_TO_LEFT, DiffKind.RIGHT_NEWER, ActionKind.COPY_RIGHT_TO_LEFT),

        // Update-only never deletes, in either direction.
        Row(SyncDirection.UPDATE_LEFT_TO_RIGHT, DiffKind.EQUAL, ActionKind.NONE),
        Row(SyncDirection.UPDATE_LEFT_TO_RIGHT, DiffKind.LEFT_ONLY, ActionKind.COPY_LEFT_TO_RIGHT),
        Row(SyncDirection.UPDATE_LEFT_TO_RIGHT, DiffKind.RIGHT_ONLY, ActionKind.NONE),
        Row(SyncDirection.UPDATE_LEFT_TO_RIGHT, DiffKind.LEFT_NEWER, ActionKind.COPY_LEFT_TO_RIGHT),
        Row(SyncDirection.UPDATE_LEFT_TO_RIGHT, DiffKind.RIGHT_NEWER, ActionKind.NONE),
        Row(SyncDirection.UPDATE_LEFT_TO_RIGHT, DiffKind.DIFFERENT, ActionKind.COPY_LEFT_TO_RIGHT),
        Row(SyncDirection.UPDATE_RIGHT_TO_LEFT, DiffKind.RIGHT_ONLY, ActionKind.COPY_RIGHT_TO_LEFT),
        Row(SyncDirection.UPDATE_RIGHT_TO_LEFT, DiffKind.LEFT_ONLY, ActionKind.NONE),
        Row(SyncDirection.UPDATE_RIGHT_TO_LEFT, DiffKind.RIGHT_NEWER, ActionKind.COPY_RIGHT_TO_LEFT),
        Row(SyncDirection.UPDATE_RIGHT_TO_LEFT, DiffKind.LEFT_NEWER, ActionKind.NONE),
    )

    @Test
    fun direction_matrix_is_table_driven() {
        for (row in matrix) {
            val plan = Planner.plan(
                pairId = "p",
                diffs = listOf(diff("a", row.kind)),
                direction = row.direction,
                conflict = ConflictPolicy.ASK,
                delete = DeletePolicy.RECYCLE,
            )
            assertEquals(row.expected, plan.actions.single().kind, "${row.direction} × ${row.kind}")
        }
    }

    @Test
    fun delete_policy_keep_suppresses_every_deletion() {
        val deletions = listOf(
            SyncDirection.MIRROR_LEFT_TO_RIGHT to DiffKind.RIGHT_ONLY,
            SyncDirection.MIRROR_RIGHT_TO_LEFT to DiffKind.LEFT_ONLY,
            SyncDirection.MIRROR_LEFT_TO_RIGHT to DiffKind.RIGHT_NEWER,
            SyncDirection.MIRROR_RIGHT_TO_LEFT to DiffKind.LEFT_NEWER,
        )
        for ((direction, kind) in deletions) {
            val plan = Planner.plan("p", listOf(diff("a", kind)), direction, ConflictPolicy.ASK, DeletePolicy.KEEP)
            assertEquals(ActionKind.NONE, plan.actions.single().kind, "$direction × $kind with KEEP")
            assertTrue(plan.warnings.any { it.contains("KEEP") })
        }
    }

    @Test
    fun recycle_moves_deletions_to_the_trash_and_propagate_does_not() {
        val recycled = Planner.plan(
            "p",
            listOf(diff("a", DiffKind.RIGHT_ONLY)),
            SyncDirection.MIRROR_LEFT_TO_RIGHT,
            ConflictPolicy.ASK,
            DeletePolicy.RECYCLE,
        )
        assertTrue(recycled.actions.single().recycle)

        val unlinked = Planner.plan(
            "p",
            listOf(diff("a", DiffKind.RIGHT_ONLY)),
            SyncDirection.MIRROR_LEFT_TO_RIGHT,
            ConflictPolicy.ASK,
            DeletePolicy.PROPAGATE,
        )
        assertFalse(unlinked.actions.single().recycle)
    }

    @Test
    fun conflict_policies_pick_a_side_or_ask() {
        val expected = mapOf(
            ConflictPolicy.ASK to ActionKind.CONFLICT_ASK,
            ConflictPolicy.PREFER_LEFT to ActionKind.COPY_LEFT_TO_RIGHT,
            ConflictPolicy.PREFER_RIGHT to ActionKind.COPY_RIGHT_TO_LEFT,
            ConflictPolicy.KEEP_BOTH to ActionKind.CONFLICT_KEEP_BOTH,
            ConflictPolicy.SKIP to ActionKind.CONFLICT_SKIP,
        )
        for ((policy, kind) in expected) {
            val plan = Planner.plan("p", listOf(diff("a", DiffKind.CONFLICT)), SyncDirection.TWO_WAY, policy, DeletePolicy.RECYCLE)
            assertEquals(kind, plan.actions.single().kind, policy.name)
        }
    }

    @Test
    fun a_new_file_is_copied_but_a_vanished_one_is_deleted() {
        val snapshot = PairSnapshot(pairId = "p", entries = mapOf("gone" to SnapshotEntry("gone", 10, 10_000)))

        val plan = Planner.plan(
            pairId = "p",
            diffs = listOf(diff("gone", DiffKind.LEFT_ONLY), diff("fresh", DiffKind.LEFT_ONLY)),
            direction = SyncDirection.TWO_WAY,
            conflict = ConflictPolicy.ASK,
            delete = DeletePolicy.RECYCLE,
            snapshot = snapshot,
        )
        assertEquals(ActionKind.DELETE_RIGHT, plan.actions.first { it.relPath == "gone" }.kind)
        assertEquals(ActionKind.COPY_LEFT_TO_RIGHT, plan.actions.first { it.relPath == "fresh" }.kind)
    }

    @Test
    fun a_survivor_that_changed_since_the_snapshot_is_not_treated_as_a_deletion() {
        val snapshot = PairSnapshot(pairId = "p", entries = mapOf("x" to SnapshotEntry("x", 10, 10_000)))
        val edited = DiffEntry(
            relPath = "x",
            kind = DiffKind.LEFT_ONLY,
            left = FileMeta("x", size = 99, mtimeMs = 50_000),
        )
        val plan = Planner.plan(
            "p",
            listOf(edited),
            SyncDirection.TWO_WAY,
            ConflictPolicy.ASK,
            DeletePolicy.RECYCLE,
            snapshot,
        )
        assertEquals(ActionKind.COPY_LEFT_TO_RIGHT, plan.actions.single().kind)
    }

    @Test
    fun update_directions_never_delete_anything() {
        val deletions = listOf(
            SyncDirection.UPDATE_LEFT_TO_RIGHT to DiffKind.RIGHT_ONLY,
            SyncDirection.UPDATE_RIGHT_TO_LEFT to DiffKind.LEFT_ONLY,
        )
        for ((direction, kind) in deletions) {
            val plan = Planner.plan("p", listOf(diff("a", kind)), direction, ConflictPolicy.ASK, DeletePolicy.PROPAGATE)
            assertEquals(ActionKind.NONE, plan.actions.single().kind, "$direction × $kind")
        }
    }

    @Test
    fun a_mirror_propagates_the_deletion_it_finds_on_the_surviving_side() {
        val snapshot = PairSnapshot(pairId = "p", entries = mapOf("x" to SnapshotEntry("x", 10, 10_000)))
        val plan = Planner.plan(
            "p",
            listOf(diff("x", DiffKind.LEFT_ONLY)),
            SyncDirection.MIRROR_RIGHT_TO_LEFT,
            ConflictPolicy.ASK,
            DeletePolicy.RECYCLE,
            snapshot,
        )
        assertEquals(ActionKind.DELETE_LEFT, plan.actions.single().kind)
    }

    @Test
    fun ask_policy_reports_the_conflicts_it_could_not_resolve() {
        val plan = Planner.plan(
            "p",
            listOf(diff("a", DiffKind.CONFLICT), diff("b", DiffKind.DIFFERENT)),
            SyncDirection.TWO_WAY,
            ConflictPolicy.ASK,
            DeletePolicy.RECYCLE,
        )
        assertEquals(2, plan.conflicts.size)
        assertTrue(plan.actionable.isEmpty())
        assertTrue(plan.warnings.any { it.contains("2 conflict") })
    }

    @Test
    fun an_unimplemented_direction_blocks_instead_of_guessing() {
        val plan = Planner.plan("p", listOf(diff("a", DiffKind.LEFT_ONLY)), SyncDirection.CUSTOM, ConflictPolicy.ASK, DeletePolicy.RECYCLE)
        assertTrue(plan.blocked)
        assertTrue(plan.actions.isEmpty())
        assertTrue(plan.warnings.single().contains("not implemented"))
    }

    @Test
    fun renames_copy_to_the_new_path() {
        val renamed = DiffEntry(
            relPath = "new.bin",
            kind = DiffKind.RENAMED,
            left = FileMeta("new.bin", size = 10, mtimeMs = 10_000),
            renamedFrom = "old.bin",
        )
        val plan = Planner.plan("p", listOf(renamed), SyncDirection.TWO_WAY, ConflictPolicy.ASK, DeletePolicy.RECYCLE)
        assertEquals(ActionKind.COPY_LEFT_TO_RIGHT, plan.actions.single().kind)
        assertEquals("rename", plan.actions.single().note)
    }

    @Test
    fun nil_sized_plans_are_still_well_formed() {
        val plan = Planner.plan("p", emptyList(), SyncDirection.TWO_WAY, ConflictPolicy.ASK, DeletePolicy.RECYCLE)
        assertFalse(plan.blocked)
        assertTrue(plan.actions.isEmpty())
        assertTrue(plan.warnings.isEmpty())
    }
}
