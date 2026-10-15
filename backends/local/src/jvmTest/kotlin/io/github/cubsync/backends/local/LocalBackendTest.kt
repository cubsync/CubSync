package io.github.cubsync.backends.local

import io.github.cubsync.core.engine.JobRunner
import io.github.cubsync.core.engine.PairStatus
import io.github.cubsync.core.model.Endpoint
import io.github.cubsync.core.model.FilterSet
import io.github.cubsync.core.model.FolderPair
import io.github.cubsync.core.model.SyncConfig
import io.github.cubsync.core.model.SyncDirection
import io.github.cubsync.core.protocol.ErrorCode
import io.github.cubsync.core.spi.BackendException
import io.github.cubsync.core.spi.SimpleRoot
import io.github.cubsync.core.util.Algorithms
import io.github.cubsync.testing.FaultInjectingBackend
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The local backend against a real filesystem.
 *
 * The in-memory backend proves the engine's logic; this file proves the parts that
 * only exist on a real disk: staging files, atomic replacement, timestamp ordering,
 * and the fact that a hidden staging file never shows up in a listing.
 */
class LocalBackendTest {

    private val temporaries = mutableListOf<Path>()

    private fun tempDir(prefix: String): Path {
        val dir = Files.createTempDirectory("cubsync-$prefix-")
        temporaries.add(dir)
        return dir
    }

    @AfterTest
    fun cleanUp() {
        for (dir in temporaries) {
            runCatching {
                Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
        temporaries.clear()
    }

    private fun backend() = LocalBackend(displayName = "test")

    private fun root(path: Path) = SimpleRoot("local", path.toString())

    @Test
    fun metadata_operations_work_on_real_files() = runBlocking {
        val dir = tempDir("meta")
        val backend = backend()
        dir.resolve("sub").createDirectories()
        dir.resolve("sub/a.txt").writeText("hello")
        Files.setLastModifiedTime(dir.resolve("sub/a.txt"), FileTime.fromMillis(1_234))

        val entries = backend.list(root(dir), "", recursive = true)
        assertEquals(listOf("sub", "sub/a.txt"), entries.map { it.relPath })
        assertEquals(5L, entries.first { it.relPath == "sub/a.txt" }.size)

        val stat = assertNotNull(backend.stat(root(dir), "sub/a.txt"))
        assertEquals(1_234L, stat.mtimeMs)
        assertEquals(null, backend.stat(root(dir), "nothing"))

        assertEquals("5d41402abc4b2a76b9719d911017c592", backend.hash(root(dir), "sub/a.txt", Algorithms.MD5))

        backend.mkdir(root(dir), "made/deep", createParents = true)
        assertTrue(Files.isDirectory(dir.resolve("made/deep")))

        backend.rename(root(dir), "sub/a.txt", "sub/b.txt")
        assertFalse(Files.exists(dir.resolve("sub/a.txt")))
        assertTrue(Files.exists(dir.resolve("sub/b.txt")))

        backend.setMtime(root(dir), "sub/b.txt", 9_876)
        assertEquals(9_876L, Files.getLastModifiedTime(dir.resolve("sub/b.txt")).toMillis())

        backend.delete(root(dir), "sub", toTrash = false)
        assertFalse(Files.exists(dir.resolve("sub")))

        assertTrue(backend.freeSpace(root(dir)) > 0)
    }

    @Test
    fun paths_that_escape_the_root_are_refused() = runBlocking {
        val dir = tempDir("escape")
        val backend = backend()
        for (attempt in listOf("..", "../outside", "a/../../outside", "/etc/passwd")) {
            val failure = assertFailsWith<BackendException> { backend.list(root(dir), attempt, recursive = false) }
            assertEquals(ErrorCode.PATH_OUTSIDE_ROOT, failure.code, "attempt: $attempt")
        }
        // An internal `..` that stays inside the root is fine.
        dir.resolve("a").createDirectories()
        assertEquals(emptyList(), backend.list(root(dir), "a/../a", recursive = true).map { it.relPath })
    }

    @Test
    fun an_interrupted_write_leaves_the_target_alone() = runBlocking {
        val dir = tempDir("staging")
        val backend = backend()
        dir.resolve("target.bin").writeText("previous")

        val writer = backend.openWrite(root(dir), "target.bin", size = 100, mtimeMs = 5_000)
        assertEquals(0L, writer.startOffset)
        writer.write("part".encodeToByteArray(), 0, 4)
        // Nothing is visible under the real name until the commit.
        assertEquals("previous", Files.readString(dir.resolve("target.bin")))
        assertTrue(Files.exists(dir.resolve("target.bin.cubtmp")))
        writer.close()

        assertEquals("previous", Files.readString(dir.resolve("target.bin")))
        assertTrue(Files.exists(dir.resolve("target.bin.cubtmp")), "a partial file is kept for a later resume")
    }

    @Test
    fun a_resumed_write_continues_from_the_staged_bytes() = runBlocking {
        val dir = tempDir("resume")
        val backend = backend()
        dir.resolve("target.bin.cubtmp").writeText("head")

        val writer = backend.openWrite(root(dir), "target.bin", size = 8, mtimeMs = 42, resume = true)
        assertEquals(4L, writer.startOffset)
        writer.write("tail".encodeToByteArray(), 0, 4)
        writer.commit(42)

        assertEquals("headtail", Files.readString(dir.resolve("target.bin")))
        assertFalse(Files.exists(dir.resolve("target.bin.cubtmp")))
        assertEquals(42L, Files.getLastModifiedTime(dir.resolve("target.bin")).toMillis())
    }

    @Test
    fun a_staging_file_larger_than_the_declared_size_is_discarded() = runBlocking {
        val dir = tempDir("stale")
        val backend = backend()
        dir.resolve("target.bin.cubtmp").writeText("far too much content")

        val writer = backend.openWrite(root(dir), "target.bin", size = 4, mtimeMs = 1, resume = true)
        assertEquals(0L, writer.startOffset)
        writer.write("next".encodeToByteArray(), 0, 4)
        writer.commit(1)
        assertEquals("next", Files.readString(dir.resolve("target.bin")))
    }

    @Test
    fun staging_files_and_the_trash_never_appear_in_a_listing() = runBlocking {
        val dir = tempDir("hide")
        val backend = backend()
        dir.resolve("visible.txt").writeText("v")
        dir.resolve("half.bin.cubtmp").writeText("partial")
        dir.resolve(".cub_trash/old").createDirectories()
        dir.resolve(".cub_trash/old/file.txt").writeText("old")

        val listed = backend.list(root(dir), "", recursive = true).map { it.relPath }
        assertEquals(listOf("visible.txt"), listed)
    }

    @Test
    fun the_trash_flag_moves_a_file_instead_of_removing_it() = runBlocking {
        val dir = tempDir("trash")
        val backend = backend()
        dir.resolve("gone.txt").writeText("content")

        backend.delete(root(dir), "gone.txt", toTrash = true)

        assertFalse(Files.exists(dir.resolve("gone.txt")))
        val recycled = Files.walk(dir).filter { Files.isRegularFile(it) && it.toString().contains(".cub_trash") }.findFirst()
        assertTrue(recycled.isPresent)
        assertEquals("content", Files.readString(recycled.get()))
    }

    @Test
    fun a_local_pair_synchronises_and_recycles_on_delete() = runBlocking {
        val leftDir = tempDir("left")
        val rightDir = tempDir("right")
        val left = backend()
        val right = backend()
        leftDir.resolve("keep.txt").writeText("k")
        leftDir.resolve("nested").createDirectories()
        leftDir.resolve("nested/deep.txt").writeText("d")
        rightDir.resolve("extra.txt").writeText("e")

        val config = SyncConfig(
            configId = "cfg",
            name = "local",
            direction = SyncDirection.MIRROR_LEFT_TO_RIGHT,
            mtimeToleranceMs = 0,
            pairs = listOf(
                FolderPair(
                    pairId = "p1",
                    order = 0,
                    left = Endpoint(left.id, leftDir.toString()),
                    right = Endpoint(right.id, rightDir.toString()),
                ),
            ),
        )

        val result = JobRunner.of(left, right).run(config)

        assertEquals(PairStatus.COMPLETED, result.pairs.single().status, result.pairs.single().error)
        assertEquals("k", Files.readString(rightDir.resolve("keep.txt")))
        assertEquals("d", Files.readString(rightDir.resolve("nested/deep.txt")))
        assertFalse(Files.exists(rightDir.resolve("extra.txt")))
        assertTrue(
            Files.walk(rightDir).anyMatch { it.toString().contains(".cub_trash") && it.fileName.toString() == "extra.txt" },
            "the deletion should be recoverable from the trash",
        )
    }

    @Test
    fun a_local_run_resumes_after_an_injected_failure() = runBlocking {
        val leftDir = tempDir("left-resume")
        val rightDir = tempDir("right-resume")
        // Distinct ids, because the fault injector wraps only one side and the
        // resolver picks a backend by id. Two folders on one machine normally share
        // a single backend, and the folder is chosen by the endpoint's root path.
        val left = LocalBackend(id = "local-left", displayName = "left")
        val right = LocalBackend(id = "local-right", displayName = "right")
        val payload = ByteArray(900_000) { (it * 17 and 0xFF).toByte() }
        Files.write(leftDir.resolve("big.bin"), payload)

        val config = SyncConfig(
            configId = "cfg",
            name = "resume",
            mtimeToleranceMs = 0,
            pairs = listOf(
                FolderPair(
                    pairId = "p1",
                    order = 0,
                    left = Endpoint(left.id, leftDir.toString()),
                    right = Endpoint(right.id, rightDir.toString()),
                ),
            ),
        )

        val faulty = FaultInjectingBackend(right).failAfterWrite(1)
        val interrupted = JobRunner.of(left, faulty, chunkSize = 262_144).run(config)

        assertEquals(PairStatus.FAILED, interrupted.pairs.single().status)
        assertFalse(Files.exists(rightDir.resolve("big.bin")))
        assertTrue(Files.exists(rightDir.resolve("big.bin.cubtmp")))

        faulty.heal()
        val resumed = JobRunner.of(left, right, chunkSize = 262_144).run(config)

        assertEquals(PairStatus.COMPLETED, resumed.pairs.single().status, resumed.pairs.single().error)
        assertContentEquals(payload, Files.readAllBytes(rightDir.resolve("big.bin")))
        assertFalse(Files.exists(rightDir.resolve("big.bin.cubtmp")))
    }

    @Test
    fun filters_apply_to_a_real_tree() = runBlocking {
        val leftDir = tempDir("left-filter")
        val rightDir = tempDir("right-filter")
        val left = backend()
        val right = backend()
        leftDir.resolve("keep.txt").writeText("k")
        leftDir.resolve("cache").createDirectories()
        leftDir.resolve("cache/raw.tmp").writeText("t")
        leftDir.resolve(".hidden").writeText("h")

        val config = SyncConfig(
            configId = "cfg",
            name = "filter",
            globalFilter = FilterSet(globs = listOf("cache/**", "*.tmp")),
            mtimeToleranceMs = 0,
            pairs = listOf(
                FolderPair(
                    pairId = "p1",
                    order = 0,
                    left = Endpoint(left.id, leftDir.toString()),
                    right = Endpoint(right.id, rightDir.toString()),
                ),
            ),
        )

        JobRunner.of(left, right).run(config)

        assertTrue(Files.exists(rightDir.resolve("keep.txt")))
        assertFalse(Files.exists(rightDir.resolve("cache")))
        assertFalse(Files.exists(rightDir.resolve(".hidden")))
    }

    @Test
    fun the_backend_reports_the_platforms_case_sensitivity() {
        val backend = backend()
        val os = System.getProperty("os.name").lowercase()
        val expected = !(os.contains("windows") || os.contains("mac"))
        assertEquals(expected, backend.caseSensitive)
    }
}
