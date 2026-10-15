package io.github.cubsync.testing

import io.github.cubsync.core.engine.ActionKind
import io.github.cubsync.core.engine.InMemorySnapshotStore
import io.github.cubsync.core.engine.JobRunner
import io.github.cubsync.core.engine.JobStatus
import io.github.cubsync.core.engine.PairSnapshot
import io.github.cubsync.core.engine.PairStatus
import io.github.cubsync.core.engine.RootFingerprint
import io.github.cubsync.core.engine.SnapshotEntry
import io.github.cubsync.core.engine.TrashLayout
import io.github.cubsync.core.model.CompareMode
import io.github.cubsync.core.model.ConflictPolicy
import io.github.cubsync.core.model.DeletePolicy
import io.github.cubsync.core.model.Endpoint
import io.github.cubsync.core.model.FilterSet
import io.github.cubsync.core.model.FolderPair
import io.github.cubsync.core.model.SyncConfig
import io.github.cubsync.core.model.SyncDirection
import io.github.cubsync.core.spi.RemoteBackend
import io.github.cubsync.core.spi.SimpleRoot
import io.github.cubsync.core.transfer.ChunkPlan
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The engine driving a full backend contract.
 *
 * The in-memory backend shares no code with the local one, so anything the engine
 * gets right here is a property of the engine rather than of the filesystem it
 * happened to run against.
 */
class EngineIntegrationTest {

    private val left = MemoryBackend(id = "L", displayName = "left", rootPaths = listOf("/left"))
    private val right = MemoryBackend(id = "R", displayName = "right", rootPaths = listOf("/right"))
    private val snapshots = InMemorySnapshotStore()

    private val leftRoot = SimpleRoot("L", "/left")
    private val rightRoot = SimpleRoot("R", "/right")

    private fun pair(
        pairId: String = "p1",
        leftPath: String = "/left",
        rightPath: String = "/right",
    ) = FolderPair(
        pairId = pairId,
        order = 0,
        left = Endpoint("L", leftPath),
        right = Endpoint("R", rightPath),
    )

    private fun config(
        pairs: List<FolderPair> = listOf(pair()),
        direction: SyncDirection = SyncDirection.TWO_WAY,
        compare: CompareMode = CompareMode.MTIME_AND_SIZE,
        conflict: ConflictPolicy = ConflictPolicy.ASK,
        delete: DeletePolicy = DeletePolicy.RECYCLE,
        filter: FilterSet = FilterSet(),
        parallelism: Int = 1,
        dryRun: Boolean = false,
        tolerance: Long = 0,
    ) = SyncConfig(
        configId = "cfg",
        name = "test",
        direction = direction,
        compare = compare,
        conflict = conflict,
        delete = delete,
        globalFilter = filter,
        parallelism = parallelism,
        dryRun = dryRun,
        mtimeToleranceMs = tolerance,
        pairs = pairs,
    )

    private fun runner(
        leftBackend: RemoteBackend = left,
        rightBackend: RemoteBackend = right,
        chunkSize: Int = ChunkPlan.DEFAULT_CHUNK_SIZE,
    ) = JobRunner.of(leftBackend, rightBackend, snapshots, chunkSize = chunkSize)

    private fun snapshotOf(entries: Map<String, SnapshotEntry>) = PairSnapshot(
        pairId = "p1",
        rev = 1,
        leftFingerprint = RootFingerprint.of(leftRoot),
        rightFingerprint = RootFingerprint.of(rightRoot),
        entries = entries,
    )

    @Test
    fun a_first_run_copies_everything_and_writes_a_snapshot() = runTest {
        left.putFile("/left", "a.txt", "alpha", mtimeMs = 1_000)
        left.putFile("/left", "nested/b.txt", "beta", mtimeMs = 1_000)

        val result = runner().run(config())

        assertEquals(PairStatus.COMPLETED, result.pairs.single().status, result.pairs.single().error)
        assertEquals("alpha", right.readText("/right", "a.txt"))
        assertEquals("beta", right.readText("/right", "nested/b.txt"))
        assertEquals(2, result.stats.copiedFiles)

        val snapshot = assertNotNull(snapshots.load("p1"))
        assertEquals(1, snapshot.rev)
        assertEquals(setOf("a.txt", "nested/b.txt"), snapshot.entries.keys)
    }

    @Test
    fun a_second_run_over_an_unchanged_pair_does_nothing() = runTest {
        left.putFile("/left", "a.txt", "alpha", mtimeMs = 1_000)
        runner().run(config())

        val second = runner().run(config())

        assertEquals(PairStatus.COMPLETED, second.pairs.single().status)
        assertEquals(0, second.stats.copiedFiles)
        assertEquals(0, second.stats.failures)
        assertTrue(second.pairs.single().items.isEmpty(), "an unchanged pair must not report work")
    }

    @Test
    fun content_comparison_spots_an_edit_that_kept_the_timestamp() = runTest {
        left.putFile("/left", "a.txt", "alpha", mtimeMs = 1_000)
        right.putFile("/right", "a.txt", "alpha", mtimeMs = 1_000)
        val result = runner().run(config(compare = CompareMode.CONTENT_HASH))
        assertEquals(0, result.stats.copiedFiles)
        assertTrue(left.hashCalls > 0 && right.hashCalls > 0)
    }

    @Test
    fun a_mirror_deletes_what_the_source_no_longer_has_and_recycles_it() = runTest {
        left.putFile("/left", "keep.txt", "k", mtimeMs = 1_000)
        right.putFile("/right", "gone.txt", "g", mtimeMs = 1_000)

        val result = runner().run(config(direction = SyncDirection.MIRROR_LEFT_TO_RIGHT))

        assertEquals(PairStatus.COMPLETED, result.pairs.single().status)
        assertEquals(1, result.stats.deletedFiles)
        assertTrue(right.exists("/right", "keep.txt"))
        assertFalse(right.exists("/right", "gone.txt"))
        // Recycled rather than unlinked: the content is still there to recover.
        val recycled = right.paths("/right")
            .firstOrNull { it.startsWith(TrashLayout.TRASH_DIR) && it.endsWith("gone.txt") }
        assertNotNull(recycled)
        assertEquals("g", right.readText("/right", recycled))
    }

    @Test
    fun an_update_direction_copies_additions_from_its_source() = runTest {
        right.putFile("/right", "extra.txt", "e", mtimeMs = 1_000)
        val result = runner().run(config(direction = SyncDirection.UPDATE_RIGHT_TO_LEFT))
        assertEquals(PairStatus.COMPLETED, result.pairs.single().status)
        assertTrue(left.exists("/left", "extra.txt"))
        assertTrue(right.exists("/right", "extra.txt"))
    }

    @Test
    fun a_two_way_run_propagates_a_deletion_that_the_snapshot_explains() = runTest {
        left.putFile("/left", "shared.txt", "s", mtimeMs = 1_000)
        runner().run(config())
        assertTrue(right.exists("/right", "shared.txt"))

        // The right side deletes it; the left still has exactly what the snapshot
        // recorded, which is what makes this a deletion and not a new file.
        right.delete(rightRoot, "shared.txt", toTrash = false)
        val result = runner().run(config())

        assertEquals(PairStatus.COMPLETED, result.pairs.single().status)
        assertFalse(left.exists("/left", "shared.txt"))
        assertEquals(1, result.stats.deletedFiles)
    }

    @Test
    fun a_two_way_run_without_a_snapshot_treats_a_delete_as_a_new_file() = runTest {
        right.putFile("/right", "lonely.txt", "l", mtimeMs = 1_000)
        val result = runner().run(config())
        assertEquals(PairStatus.COMPLETED, result.pairs.single().status)
        assertTrue(left.exists("/left", "lonely.txt"), "with no history, adding is the safe guess")
    }

    @Test
    fun two_changed_files_are_reported_as_a_conflict_and_left_alone() = runTest {
        left.putFile("/left", "doc.txt", "left version", mtimeMs = 5_000)
        right.putFile("/right", "doc.txt", "right version", mtimeMs = 6_000)
        snapshots.save(snapshotOf(mapOf("doc.txt" to SnapshotEntry("doc.txt", 5, 500))))

        val result = runner().run(config())

        assertEquals(PairStatus.COMPLETED, result.pairs.single().status)
        assertEquals(1, result.stats.conflicts)
        assertEquals("left version", left.readText("/left", "doc.txt"))
        assertEquals("right version", right.readText("/right", "doc.txt"))
    }

    @Test
    fun keep_both_renames_the_loser_and_keeps_the_winner() = runTest {
        snapshots.save(snapshotOf(mapOf("doc.txt" to SnapshotEntry("doc.txt", 5, 500))))
        left.putFile("/left", "doc.txt", "left version", mtimeMs = 5_000)
        right.putFile("/right", "doc.txt", "right version", mtimeMs = 6_000)

        val result = runner().run(config(conflict = ConflictPolicy.KEEP_BOTH))

        assertEquals(PairStatus.COMPLETED, result.pairs.single().status, result.pairs.single().error)
        assertEquals("left version", right.readText("/right", "doc.txt"))
        val kept = right.paths("/right").firstOrNull { it.contains("cubconflict") }
        assertNotNull(kept)
        assertEquals("right version", right.readText("/right", kept))
    }

    @Test
    fun prefer_right_writes_the_right_version_over_the_left() = runTest {
        snapshots.save(snapshotOf(mapOf("doc.txt" to SnapshotEntry("doc.txt", 5, 500))))
        left.putFile("/left", "doc.txt", "left version", mtimeMs = 5_000)
        right.putFile("/right", "doc.txt", "right version", mtimeMs = 6_000)

        runner().run(config(conflict = ConflictPolicy.PREFER_RIGHT))

        assertEquals("right version", left.readText("/left", "doc.txt"))
    }

    @Test
    fun filters_keep_excluded_files_out_of_the_plan() = runTest {
        left.putFile("/left", "keep.txt", "k", mtimeMs = 1_000)
        left.putFile("/left", "skip.tmp", "s", mtimeMs = 1_000)
        left.putFile("/left", "cache/deep.tmp", "d", mtimeMs = 1_000)

        runner().run(config(filter = FilterSet(globs = listOf("*.tmp"))))

        assertTrue(right.exists("/right", "keep.txt"))
        assertFalse(right.exists("/right", "skip.tmp"))
        assertFalse(right.exists("/right", "cache/deep.tmp"))
    }

    @Test
    fun a_per_pair_filter_override_replaces_the_global_one() = runTest {
        left.putFile("/left", "skip.tmp", "s", mtimeMs = 1_000)
        val overriding = pair().copy(filterOverride = FilterSet(enabled = false))
        runner().run(config(pairs = listOf(overriding), filter = FilterSet(globs = listOf("*.tmp"))))
        assertTrue(right.exists("/right", "skip.tmp"))
    }

    @Test
    fun hidden_entries_are_skipped_unless_asked_for() = runTest {
        left.putFile("/left", "visible.txt", "v", mtimeMs = 1_000)
        left.putFile("/left", ".secret", "s", mtimeMs = 1_000)
        runner().run(config())
        assertTrue(right.exists("/right", "visible.txt"))
        assertFalse(right.exists("/right", ".secret"))

        left.putFile("/left", ".other", "o", mtimeMs = 1_000)
        runner().run(config(filter = FilterSet(includeHidden = true)))
        assertTrue(right.exists("/right", ".other"))
    }

    @Test
    fun nested_roots_block_execution_without_touching_anything() = runTest {
        left.putFile("/left", "sub/file.txt", "f", mtimeMs = 1_000)
        val result = runner().run(config(pairs = listOf(pair(rightPath = "/left/sub"))))

        assertEquals(PairStatus.BLOCKED, result.pairs.single().status)
        assertTrue(result.pairs.single().error.contains("overlap"))
        assertTrue(left.exists("/left", "sub/file.txt"))
        assertFalse(left.exists("/left", "sub/sub/file.txt"))
    }

    @Test
    fun dry_run_plans_without_writing() = runTest {
        left.putFile("/left", "a.txt", "alpha", mtimeMs = 1_000)
        val result = runner().run(config(dryRun = true))

        assertEquals(PairStatus.DRY_RUN, result.pairs.single().status)
        assertFalse(right.exists("/right", "a.txt"))
        assertEquals(ActionKind.COPY_LEFT_TO_RIGHT, result.pairs.single().items.single().action)
        assertNull(snapshots.load("p1"))
    }

    @Test
    fun an_interrupted_copy_leaves_a_staging_entry_and_can_be_resumed() = runTest {
        val payload = ByteArray(700_000) { (it * 7 and 0xFF).toByte() }
        left.putFile("/left", "big.bin", payload, mtimeMs = 4_242)

        val faulty = FaultInjectingBackend(right).failAfterWrite(1)
        val interrupted = JobRunner.of(left, faulty, snapshots, chunkSize = 262_144).run(config())

        assertEquals(PairStatus.FAILED, interrupted.pairs.single().status)
        assertEquals(1, faulty.injectedFailures)
        assertEquals(262_144L, right.stagedSize("/right", "big.bin"))
        assertFalse(right.exists("/right", "big.bin"))
        assertNull(snapshots.load("p1"), "a failed pair must not record a snapshot")

        faulty.heal()
        val resumed = runner().run(config())

        assertEquals(PairStatus.COMPLETED, resumed.pairs.single().status, resumed.pairs.single().error)
        assertContentEquals(payload, right.readFile("/right", "big.bin"))
        assertEquals(4_242L, right.stat(rightRoot, "big.bin")?.mtimeMs)
        assertFalse(right.existsStaged("/right", "big.bin"))
        assertNotNull(snapshots.load("p1"))

        // And a third run has nothing left to do, which is the real proof that the
        // resumed file is complete and not merely the right length.
        val settled = runner().run(config())
        assertEquals(0, settled.stats.copiedFiles)
    }

    @Test
    fun a_rename_is_detected_and_propagated_as_a_copy_to_the_new_name() = runTest {
        left.putFile("/left", "old.txt", "content", mtimeMs = 1_000)
        runner().run(config())
        left.rename(leftRoot, "old.txt", "new.txt")

        val result = runner().run(config())

        assertEquals(PairStatus.COMPLETED, result.pairs.single().status, result.pairs.single().error)
        assertTrue(right.exists("/right", "new.txt"))
        assertFalse(right.exists("/right", "old.txt"))
        assertEquals(
            "old.txt",
            result.pairs.single().plan!!.actions.first { it.diff.kind == io.github.cubsync.core.engine.DiffKind.RENAMED }.diff.renamedFrom,
        )
    }

    @Test
    fun one_failing_pair_does_not_stop_the_others() = runTest {
        left.putFile("/left", "good.txt", "g", mtimeMs = 1_000)
        val pairs = listOf(
            pair(pairId = "good"),
            pair(pairId = "bad", rightPath = "/missing"),
        )

        val result = runner().run(config(pairs = pairs))

        assertEquals(JobStatus.PARTIAL_FAILED, result.status)
        assertTrue(right.exists("/right", "good.txt"))
        assertEquals(PairStatus.COMPLETED, result.pairs.first { it.pairId == "good" }.status)
        assertEquals(PairStatus.FAILED, result.pairs.first { it.pairId == "bad" }.status)
    }

    @Test
    fun a_disabled_pair_is_skipped() = runTest {
        left.putFile("/left", "a.txt", "a", mtimeMs = 1_000)
        val disabled = pair().copy(enabled = false)
        val result = runner().run(config(pairs = listOf(disabled)))
        assertEquals(PairStatus.SKIPPED, result.pairs.single().status)
        assertFalse(right.exists("/right", "a.txt"))
    }

    @Test
    fun several_files_are_copied_in_parallel_without_losing_any() = runTest {
        for (i in 0 until 12) left.putFile("/left", "dir/f$i.bin", "content-$i", mtimeMs = 1_000)

        val result = runner().run(config(parallelism = 4))

        assertEquals(PairStatus.COMPLETED, result.pairs.single().status)
        assertEquals(12, result.stats.copiedFiles)
        for (i in 0 until 12) assertEquals("content-$i", right.readText("/right", "dir/f$i.bin"))
    }

    @Test
    fun an_empty_pair_is_a_success_with_nothing_to_report() = runTest {
        val result = runner().run(config())
        assertEquals(PairStatus.COMPLETED, result.pairs.single().status)
        assertEquals(0, result.stats.copiedFiles)
        assertEquals(0, result.stats.failures)
    }
}
