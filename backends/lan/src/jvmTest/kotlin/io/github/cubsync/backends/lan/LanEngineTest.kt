package io.github.cubsync.backends.lan

import io.github.cubsync.core.engine.JobRunner
import io.github.cubsync.core.engine.JobStatus
import io.github.cubsync.core.engine.PairStatus
import io.github.cubsync.core.engine.TrashLayout
import io.github.cubsync.core.model.Endpoint
import io.github.cubsync.core.model.FolderPair
import io.github.cubsync.core.model.SyncConfig
import io.github.cubsync.core.model.SyncDirection
import io.github.cubsync.testing.MemoryBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The engine driving a peer across the wire.
 *
 * This is the shape of the product: a local tree on one side, a remote peer's tree
 * on the other, and the same engine in the middle. It exercises the whole path —
 * scan, compare, plan, stream, verify, snapshot — with only the transport swapped.
 */
class LanEngineTest {

    private fun pair(leftId: String, leftPath: String, rightId: String, rightPath: String) = FolderPair(
        pairId = "p1",
        order = 0,
        left = Endpoint(leftId, leftPath),
        right = Endpoint(rightId, rightPath),
    )

    private fun config(
        leftId: String,
        leftPath: String,
        rightId: String,
        rightPath: String,
        direction: SyncDirection = SyncDirection.TWO_WAY,
    ) = SyncConfig(
        configId = "cfg",
        name = "over the wire",
        direction = direction,
        mtimeToleranceMs = 0,
        pairs = listOf(pair(leftId, leftPath, rightId, rightPath)),
    )

    @Test
    fun a_pair_synchronises_across_the_protocol() = runBlocking {
        val remoteStore = MemoryBackend(id = "memory", rootPaths = listOf("/remote"))
        val local = MemoryBackend(id = "L", displayName = "local", rootPaths = listOf("/local"))
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val server = LanServer(remoteStore, listOf("/remote"), port = 0, bindAddress = "127.0.0.1")
        val port = server.bind()
        server.start(scope)
        val peer = LanBackend.connect("127.0.0.1", port, scope)
        try {
            val payload = ByteArray(900_000) { (it * 31 and 0xFF).toByte() }
            local.putFile("/local", "docs/report.bin", payload, mtimeMs = 5_000)
            local.putFile("/local", "notes.txt", "hello", mtimeMs = 5_000)

            val result = JobRunner(local, peer, chunkSize = 262_144)
                .run(config(local.id, "/local", peer.id, "/remote"))

            assertEquals(PairStatus.COMPLETED, result.pairs.single().status, result.pairs.single().error)
            assertEquals(JobStatus.COMPLETED, result.status)
            assertContentEquals(payload, remoteStore.readFile("/remote", "docs/report.bin"))
            assertEquals("hello", remoteStore.readText("/remote", "notes.txt"))
            assertEquals(5_000, remoteStore.stat(io.github.cubsync.core.spi.SimpleRoot("memory", "/remote"), "docs/report.bin")?.mtimeMs)
            assertEquals(2, result.stats.copiedFiles)
        } finally {
            peer.close()
            scope.cancel()
            server.close()
        }
    }

    @Test
    fun files_come_back_from_the_peer_as_well() = runBlocking {
        val remoteStore = MemoryBackend(id = "memory", rootPaths = listOf("/remote"))
        val local = MemoryBackend(id = "L", rootPaths = listOf("/local"))
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val server = LanServer(remoteStore, listOf("/remote"), port = 0, bindAddress = "127.0.0.1")
        val port = server.bind()
        server.start(scope)
        val peer = LanBackend.connect("127.0.0.1", port, scope)
        try {
            remoteStore.putFile("/remote", "from-peer.bin", "remote content", mtimeMs = 3_000)

            val result = JobRunner(local, peer, chunkSize = 262_144)
                .run(config(local.id, "/local", peer.id, "/remote"))

            assertEquals(PairStatus.COMPLETED, result.pairs.single().status, result.pairs.single().error)
            assertEquals("remote content", local.readText("/local", "from-peer.bin"))
        } finally {
            peer.close()
            scope.cancel()
            server.close()
        }
    }

    @Test
    fun a_mirror_deletes_on_the_peer_and_recycles_there() = runBlocking {
        val remoteStore = MemoryBackend(id = "memory", rootPaths = listOf("/remote"))
        val local = MemoryBackend(id = "L", rootPaths = listOf("/local"))
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val server = LanServer(remoteStore, listOf("/remote"), port = 0, bindAddress = "127.0.0.1")
        val port = server.bind()
        server.start(scope)
        val peer = LanBackend.connect("127.0.0.1", port, scope)
        try {
            local.putFile("/local", "keep.txt", "k", mtimeMs = 1_000)
            remoteStore.putFile("/remote", "gone.txt", "g", mtimeMs = 1_000)

            val result = JobRunner(local, peer, chunkSize = 262_144)
                .run(config(local.id, "/local", peer.id, "/remote", SyncDirection.MIRROR_LEFT_TO_RIGHT))

            assertEquals(PairStatus.COMPLETED, result.pairs.single().status, result.pairs.single().error)
            assertTrue(remoteStore.exists("/remote", "keep.txt"))
            assertFalse(remoteStore.exists("/remote", "gone.txt"))
            val recycled = remoteStore.paths("/remote")
                .firstOrNull { it.startsWith(TrashLayout.TRASH_DIR) && it.endsWith("gone.txt") }
            assertTrue(recycled != null, "the deletion should have been recycled on the peer: ${remoteStore.paths("/remote")}")
            assertEquals("g", remoteStore.readText("/remote", recycled))
        } finally {
            peer.close()
            scope.cancel()
            server.close()
        }
    }

    @Test
    fun several_files_move_in_parallel_over_one_session() = runBlocking {
        val remoteStore = MemoryBackend(id = "memory", rootPaths = listOf("/remote"))
        val local = MemoryBackend(id = "L", rootPaths = listOf("/local"))
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val server = LanServer(remoteStore, listOf("/remote"), port = 0, bindAddress = "127.0.0.1")
        val port = server.bind()
        server.start(scope)
        val peer = LanBackend.connect("127.0.0.1", port, scope)
        try {
            for (i in 0 until 6) {
                local.putFile("/local", "batch/f$i.bin", ByteArray(200_000) { ((it + i) and 0xFF).toByte() }, 2_000)
            }
            val settings = config(local.id, "/local", peer.id, "/remote").copy(parallelism = 3)

            val result = JobRunner(local, peer, chunkSize = 131_072).run(settings)

            assertEquals(PairStatus.COMPLETED, result.pairs.single().status, result.pairs.single().error)
            assertEquals(6, result.stats.copiedFiles)
            for (i in 0 until 6) {
                val expected = ByteArray(200_000) { ((it + i) and 0xFF).toByte() }
                assertContentEquals(expected, remoteStore.readFile("/remote", "batch/f$i.bin"))
            }
        } finally {
            peer.close()
            scope.cancel()
            server.close()
        }
    }
}
