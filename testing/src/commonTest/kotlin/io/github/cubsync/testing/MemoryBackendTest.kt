package io.github.cubsync.testing

import io.github.cubsync.core.protocol.ErrorCode
import io.github.cubsync.core.spi.BackendException
import io.github.cubsync.core.spi.SimpleRoot
import io.github.cubsync.core.util.Algorithms
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MemoryBackendTest {

    private val root = SimpleRoot("memory", "/data")

    @Test
    fun files_and_directories_are_listed_relative_to_the_root() = runTest {
        val backend = MemoryBackend()
        backend.putFile("/data", "a/b.txt", "hello")
        backend.putFile("/data", "top.txt", "x")

        val recursive = backend.list(root, "", recursive = true)
        assertEquals(listOf("a", "a/b.txt", "top.txt"), recursive.map { it.relPath })

        val shallow = backend.list(root, "", recursive = false)
        assertEquals(listOf("a", "top.txt"), shallow.map { it.relPath })
        assertTrue(shallow.first { it.relPath == "a" }.isDirectory)
    }

    @Test
    fun hash_matches_the_fixed_vectors() = runTest {
        val backend = MemoryBackend()
        backend.putFile("/data", "empty", ByteArray(0))
        backend.putFile("/data", "abc", "abc")
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", backend.hash(root, "empty", Algorithms.MD5))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", backend.hash(root, "abc", Algorithms.MD5))
    }

    @Test
    fun a_missing_entry_is_reported_as_not_found() = runTest {
        val backend = MemoryBackend()
        val failure = assertFailsWith<BackendException> { backend.hash(root, "nope", Algorithms.MD5) }
        assertEquals(ErrorCode.NOT_FOUND, failure.code)
        assertEquals(null, backend.stat(root, "nope"))
        assertEquals(ErrorCode.NOT_FOUND, assertFailsWith<BackendException> {
            backend.list(root, "nope", recursive = true)
        }.code)
    }

    @Test
    fun listing_a_file_instead_of_a_folder_is_a_bad_request() = runTest {
        val backend = MemoryBackend()
        backend.putFile("/data", "f.txt", "x")
        assertEquals(ErrorCode.BAD_REQUEST, assertFailsWith<BackendException> {
            backend.list(root, "f.txt", recursive = false)
        }.code)
    }

    @Test
    fun paths_that_escape_the_root_are_refused() = runTest {
        val backend = MemoryBackend()
        assertEquals(ErrorCode.PATH_OUTSIDE_ROOT, assertFailsWith<BackendException> {
            backend.list(SimpleRoot("memory", "/data"), "../etc", recursive = false)
        }.code)
        assertEquals(ErrorCode.PATH_OUTSIDE_ROOT, assertFailsWith<BackendException> {
            backend.list(SimpleRoot("memory", "/data"), "/etc", recursive = false)
        }.code)
        assertEquals(ErrorCode.PATH_OUTSIDE_ROOT, assertFailsWith<BackendException> {
            backend.list(SimpleRoot("memory", "/data"), "a/../../etc", recursive = false)
        }.code)
        // ...but an internal .. that stays inside is fine.
        backend.putFile("/data", "a/b.txt", "x")
        assertEquals(1, backend.list(SimpleRoot("memory", "/data"), "a/../a", recursive = true).size)
    }

    @Test
    fun rename_moves_a_whole_subtree() = runTest {
        val backend = MemoryBackend()
        backend.putFile("/data", "old/a.txt", "a")
        backend.putFile("/data", "old/deep/b.txt", "b")
        backend.rename(root, "old", "new")
        assertFalse(backend.exists("/data", "old/a.txt"))
        assertEquals("a", backend.readText("/data", "new/a.txt"))
        assertEquals("b", backend.readText("/data", "new/deep/b.txt"))
    }

    @Test
    fun delete_removes_a_whole_subtree() = runTest {
        val backend = MemoryBackend()
        backend.putFile("/data", "tree/a.txt", "a")
        backend.putFile("/data", "tree/deep/b.txt", "b")
        backend.delete(root, "tree", toTrash = false)
        assertTrue(backend.paths("/data").none { it.startsWith("tree") })
    }

    @Test
    fun an_interrupted_write_leaves_only_the_staging_entry() = runTest {
        val backend = MemoryBackend()
        backend.putFile("/data", "target.bin", "previous")
        val writer = backend.openWrite(root, "target.bin", size = 100, mtimeMs = 5)
        writer.write("part".encodeToByteArray(), 0, 4)

        assertEquals("previous", backend.readText("/data", "target.bin"))
        assertEquals(4L, backend.stagedSize("/data", "target.bin"))

        writer.abort()
        assertEquals("previous", backend.readText("/data", "target.bin"))
        assertEquals(-1L, backend.stagedSize("/data", "target.bin"))
    }

    @Test
    fun commit_replaces_the_target_and_clears_the_staging_entry() = runTest {
        val backend = MemoryBackend()
        backend.putFile("/data", "target.bin", "previous")
        val writer = backend.openWrite(root, "target.bin", size = 4, mtimeMs = 99)
        writer.write("next".encodeToByteArray(), 0, 4)
        writer.commit(mtimeMs = 99)

        assertEquals("next", backend.readText("/data", "target.bin"))
        assertEquals(99L, backend.stat(root, "target.bin")?.mtimeMs)
        assertFalse(backend.existsStaged("/data", "target.bin"))
    }

    @Test
    fun a_resumed_write_continues_where_the_staging_entry_ends() = runTest {
        val backend = MemoryBackend()
        backend.putStagedFile("/data", "target.bin", "head".encodeToByteArray())
        val writer = backend.openWrite(root, "target.bin", size = 8, mtimeMs = 1, resume = true)
        assertEquals(4L, writer.startOffset)
        writer.write("tail".encodeToByteArray(), 0, 4)
        writer.commit(1)
        assertEquals("headtail", backend.readText("/data", "target.bin"))
    }

    @Test
    fun a_write_that_does_not_resume_discards_the_staging_entry() = runTest {
        val backend = MemoryBackend()
        backend.putStagedFile("/data", "target.bin", "stale".encodeToByteArray())
        val writer = backend.openWrite(root, "target.bin", size = 3, mtimeMs = 1, resume = false)
        assertEquals(0L, writer.startOffset)
        writer.write("new".encodeToByteArray(), 0, 3)
        writer.commit(1)
        assertEquals("new", backend.readText("/data", "target.bin"))
    }

    @Test
    fun reading_from_an_offset_never_invents_content() = runTest {
        val backend = MemoryBackend()
        backend.putFile("/data", "f.bin", "0123456789")
        val stream = backend.openRead(root, "f.bin", offset = 4)
        val chunk = assertNotNull(stream.read(1024))
        assertEquals(4L, chunk.offset)
        assertContentEquals("456789".encodeToByteArray(), chunk.data)
        assertEquals(null, stream.read(1024))

        assertFailsWith<BackendException> { backend.openRead(root, "f.bin", offset = 11) }
    }

    @Test
    fun fault_injection_fails_at_the_chosen_chunk() = runTest {
        val backend = MemoryBackend()
        backend.putFile("/data", "f.bin", "abcdefghij")
        val faulty = FaultInjectingBackend(backend).failAfterRead(1)

        val stream = faulty.openRead(root, "f.bin")
        assertEquals(4, assertNotNull(stream.read(4)).length)
        assertEquals(ErrorCode.IO_ERROR, assertFailsWith<BackendException> { stream.read(4) }.code)
        assertEquals(1, faulty.injectedFailures)

        faulty.heal()
        val recovered = faulty.openRead(root, "f.bin")
        assertEquals(4, assertNotNull(recovered.read(4)).length)
    }

    @Test
    fun fault_injection_can_break_writes_too() = runTest {
        val backend = MemoryBackend()
        val faulty = FaultInjectingBackend(backend).failAfterWrite(1)
        val writer = faulty.openWrite(root, "f.bin", size = 8, mtimeMs = 1)
        writer.write("abcd".encodeToByteArray(), 0, 4)
        assertEquals(ErrorCode.IO_ERROR, assertFailsWith<BackendException> {
            writer.write("efgh".encodeToByteArray(), 0, 4)
        }.code)
        assertEquals(4L, backend.stagedSize("/data", "f.bin"))
    }

    @Test
    fun advertised_capabilities_cover_what_the_engine_needs_to_copy() = runTest {
        val backend = MemoryBackend()
        for (capability in listOf(
            io.github.cubsync.core.protocol.Capabilities.LIST,
            io.github.cubsync.core.protocol.Capabilities.STAT,
            io.github.cubsync.core.protocol.Capabilities.HASH,
            io.github.cubsync.core.protocol.Capabilities.MKDIR,
            io.github.cubsync.core.protocol.Capabilities.DELETE,
            io.github.cubsync.core.protocol.Capabilities.RENAME,
            io.github.cubsync.core.protocol.Capabilities.SET_MTIME,
        )) {
            assertTrue(
                io.github.cubsync.core.protocol.Capabilities.has(backend.capabilities, capability),
                "missing capability ${io.github.cubsync.core.protocol.Capabilities.describe(capability)}",
            )
        }
        assertEquals(listOf(SimpleRoot("memory", "/")), backend.roots())
    }

    @Test
    fun listing_is_counted_so_a_test_can_prove_it_was_skipped() = runTest {
        val backend = MemoryBackend()
        assertEquals(0, backend.listCalls)
        backend.list(SimpleRoot("memory", "/"), "", recursive = false)
        assertEquals(1, backend.listCalls)
    }
}
