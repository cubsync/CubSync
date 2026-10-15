package io.github.cubsync.backends.lan

import io.github.cubsync.core.protocol.Capabilities
import io.github.cubsync.core.protocol.Command
import io.github.cubsync.core.protocol.ErrorCode
import io.github.cubsync.core.protocol.Frame
import io.github.cubsync.core.protocol.FrameFlags
import io.github.cubsync.core.protocol.FrameType
import io.github.cubsync.core.protocol.Handshake
import io.github.cubsync.core.protocol.HelloRequest
import io.github.cubsync.core.protocol.Messages
import io.github.cubsync.core.protocol.Protocol
import io.github.cubsync.core.protocol.Bind
import io.github.cubsync.core.spi.BackendException
import io.github.cubsync.core.spi.Chunk
import io.github.cubsync.core.spi.SimpleRoot
import io.github.cubsync.core.util.Algorithms
import io.github.cubsync.testing.MemoryBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The protocol over real sockets.
 *
 * These tests use an in-memory backend on the server side, so a failure here is a
 * failure of the wire protocol or of the client-side backend, never of the
 * filesystem.
 */
class LanTransferTest {

    private val root = SimpleRoot("memory", "/data")

    private fun withPeer(block: suspend (MemoryBackend, LanBackend) -> Unit) = runBlocking {
        val store = MemoryBackend(rootPaths = listOf("/data"))
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val server = LanServer(store, listOf("/data"), port = 0, bindAddress = "127.0.0.1")
        val port = server.bind()
        server.start(scope)
        val client = LanBackend.connect("127.0.0.1", port, scope)
        try {
            block(store, client)
        } finally {
            client.close()
            scope.cancel()
            server.close()
        }
    }

    @Test
    fun the_handshake_agrees_on_a_capability_set() = withPeer { _, client ->
        assertTrue(Capabilities.has(client.capabilities, Capabilities.LIST))
        assertTrue(Capabilities.has(client.capabilities, Capabilities.HASH))
        assertTrue(Capabilities.has(client.capabilities, Capabilities.RESUME))
        assertFalse(Capabilities.has(client.capabilities, Capabilities.MULTI_CHANNEL))
    }

    @Test
    fun metadata_commands_round_trip() = withPeer { store, client ->
        store.putFile("/data", "a/b.txt", "hello", mtimeMs = 1_234)
        store.putDirectory("/data", "empty")

        val entries = client.list(root, "", recursive = true)
        assertEquals(listOf("a", "a/b.txt", "empty"), entries.map { it.relPath })

        val stat = assertNotNull(client.stat(root, "a/b.txt"))
        assertEquals(5, stat.size)
        assertEquals(1_234, stat.mtimeMs)

        assertEquals(null, client.stat(root, "missing"))

        assertEquals("5d41402abc4b2a76b9719d911017c592", client.hash(root, "a/b.txt", Algorithms.MD5))

        client.mkdir(root, "made/deep", createParents = true)
        assertTrue(store.exists("/data", "made/deep"))

        client.rename(root, "a/b.txt", "a/c.txt")
        assertTrue(store.exists("/data", "a/c.txt"))
        assertFalse(store.exists("/data", "a/b.txt"))

        client.setMtime(root, "a/c.txt", 9_999)
        assertEquals(9_999, store.stat(root, "a/c.txt")?.mtimeMs)

        client.delete(root, "a", toTrash = false)
        assertFalse(store.exists("/data", "a/c.txt"))

        assertTrue(client.freeSpace(root) > 0)
    }

    @Test
    fun a_file_survives_the_round_trip_byte_for_byte() = withPeer { store, client ->
        val payload = ByteArray(1_500_000) { (it * 13 and 0xFF).toByte() }
        store.putFile("/data", "source.bin", payload, mtimeMs = 7_777)

        val reader = client.openRead(root, "source.bin", 0)
        val writer = client.openWrite(root, "target.bin", payload.size.toLong(), 7_777)
        val collected = ArrayList<Byte>()
        while (true) {
            val chunk: Chunk = reader.read(262_144) ?: break
            writer.write(chunk.data, 0, chunk.length)
            collected.addAll(chunk.data.take(chunk.length))
        }
        reader.close()
        writer.commit(7_777)

        assertContentEquals(payload, store.readFile("/data", "target.bin"))
        assertEquals(7_777, store.stat(root, "target.bin")?.mtimeMs)
        assertFalse(store.existsStaged("/data", "target.bin"))
    }

    @Test
    fun an_empty_file_announces_its_end() = withPeer { store, client ->
        store.putFile("/data", "empty.bin", ByteArray(0))
        val reader = client.openRead(root, "empty.bin", 0)
        assertEquals(null, reader.read(1024))
        reader.close()

        val writer = client.openWrite(root, "copy.bin", 0, 42)
        writer.commit(42)
        assertContentEquals(ByteArray(0), store.readFile("/data", "copy.bin"))
        assertEquals(42, store.stat(root, "copy.bin")?.mtimeMs)
    }

    @Test
    fun reading_inside_a_file_starts_at_the_requested_offset() = withPeer { store, client ->
        store.putFile("/data", "f.bin", "0123456789")
        val reader = client.openRead(root, "f.bin", offset = 4)
        assertEquals(4L, reader.startOffset)
        assertEquals(6L, reader.size)
        val chunk = assertNotNull(reader.read(1024))
        assertContentEquals("456789".encodeToByteArray(), chunk.data)
        assertEquals(4L, chunk.offset)
        assertEquals(null, reader.read(1024))
        reader.close()
    }

    @Test
    fun a_write_reports_where_resuming_would_continue() = withPeer { store, client ->
        store.putStagedFile("/data", "target.bin", "head".encodeToByteArray())
        val writer = client.openWrite(root, "target.bin", 8, 1, resume = true)
        assertEquals(4L, writer.startOffset)
        writer.write("tail".encodeToByteArray(), 0, 4)
        writer.commit(1)
        assertEquals("headtail", store.readText("/data", "target.bin"))
    }

    @Test
    fun a_root_that_was_not_exposed_is_refused() = withPeer { _, client ->
        val failure = assertFailsWith<BackendException> {
            client.list(SimpleRoot("memory", "/etc"), "", recursive = true)
        }
        assertEquals(ErrorCode.PATH_OUTSIDE_ROOT, failure.code)
    }

    @Test
    fun a_path_that_climbs_out_of_an_exposed_root_is_refused() = withPeer { _, client ->
        assertFailsWith<BackendException> {
            client.list(SimpleRoot("memory", "/data/../etc"), "", recursive = true)
        }
        assertFailsWith<BackendException> {
            client.stat(SimpleRoot("memory", "/data"), "../../etc/passwd")
        }
    }

    @Test
    fun a_peer_speaking_a_newer_protocol_is_refused_with_an_explanation() = runBlocking {
        val store = MemoryBackend(rootPaths = listOf("/data"))
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val server = LanServer(store, listOf("/data"), port = 0, bindAddress = "127.0.0.1")
        val port = server.bind()
        server.start(scope)
        try {
            val socket = Socket("127.0.0.1", port)
            val channel = FrameChannel(socket, "test client")
            channel.send(
                Frame(FrameType.HANDSHAKE, FrameFlags.NONE, 0, 0, Messages.encodeMessage(Bind(Bind.ROLE_CONTROL, 7L))),
            )
            channel.send(
                Frame(
                    FrameType.COMMAND,
                    FrameFlags.ACK_REQUIRED,
                    0,
                    1,
                    Messages.encodeCommand(
                        Command.HELLO,
                        HelloRequest(Protocol.VERSION + 1, Capabilities.ALL, "future", "jvm", "9.0.0"),
                    ),
                ),
            )
            val answer = channel.receive()
            assertEquals(FrameType.ERROR, answer.type)
            val error = Messages.decodeError(answer.payload)
            assertEquals(ErrorCode.UNSUPPORTED_VERSION, error.code)
            assertTrue(error.message.contains("protocol ${Protocol.VERSION + 1}"))
            channel.close()
        } finally {
            scope.cancel()
            server.close()
        }
    }

    @Test
    fun an_unknown_command_is_answered_by_name_instead_of_breaking_the_link() = runBlocking {
        val store = MemoryBackend(rootPaths = listOf("/data"))
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val server = LanServer(store, listOf("/data"), port = 0, bindAddress = "127.0.0.1")
        val port = server.bind()
        server.start(scope)
        try {
            val socket = Socket("127.0.0.1", port)
            val channel = FrameChannel(socket, "test client")
            val token = 11L
            channel.send(
                Frame(FrameType.HANDSHAKE, FrameFlags.NONE, 0, 0, Messages.encodeMessage(Bind(Bind.ROLE_CONTROL, token))),
            )
            channel.send(
                Frame(
                    FrameType.COMMAND,
                    FrameFlags.ACK_REQUIRED,
                    0,
                    1,
                    Messages.encodeCommand(
                        Command.HELLO,
                        HelloRequest(Protocol.VERSION, Capabilities.ALL, "tester", "jvm", "0.1.0"),
                    ),
                ),
            )
            assertEquals(FrameType.RESPONSE, channel.receive().type)

            val dataSocket = Socket("127.0.0.1", port)
            val dataChannel = FrameChannel(dataSocket, "test data")
            dataChannel.send(
                Frame(FrameType.HANDSHAKE, FrameFlags.NONE, 0, 0, Messages.encodeMessage(Bind(Bind.ROLE_DATA, token))),
            )
            assertEquals(FrameType.HANDSHAKE, dataChannel.receive().type)

            // Command 990 does not exist in this build.
            val payload = Messages.encodeCommand(Command.LIST, io.github.cubsync.core.protocol.ListRequest("/data", "", false, 0, 0))
            payload[0] = 0x03
            payload[1] = 0xDE.toByte() // 990
            channel.send(Frame(FrameType.COMMAND, FrameFlags.ACK_REQUIRED, 0, 42, payload))
            val error = Messages.decodeError(channel.receive().payload)
            assertEquals(ErrorCode.UNKNOWN_COMMAND, error.code)
            assertTrue(error.message.contains("990"))

            // And the link still works afterwards.
            channel.send(
                Frame(
                    FrameType.COMMAND,
                    FrameFlags.ACK_REQUIRED,
                    0,
                    43,
                    Messages.encodeCommand(Command.LIST, io.github.cubsync.core.protocol.ListRequest("/data", "", false, 0, 0)),
                ),
            )
            assertEquals(FrameType.RESPONSE, channel.receive().type)
            channel.close()
            dataChannel.close()
        } finally {
            scope.cancel()
            server.close()
        }
    }

    @Test
    fun the_capability_set_negotiates_to_the_intersection() {
        val ours = LanConnection.SERVER_CAPABILITIES
        val result = Handshake.evaluate(Protocol.VERSION, ours, Protocol.VERSION, Capabilities.LIST or Capabilities.HASH)
        assertEquals(Capabilities.LIST or Capabilities.HASH, result.negotiatedCapabilities)
    }
}
