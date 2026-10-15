package io.github.cubsync.backends.lan

import io.github.cubsync.core.protocol.Bind
import io.github.cubsync.core.protocol.Command
import io.github.cubsync.core.protocol.DataChunkHeader
import io.github.cubsync.core.protocol.ErrorCode
import io.github.cubsync.core.protocol.Frame
import io.github.cubsync.core.protocol.FrameFlags
import io.github.cubsync.core.protocol.FrameType
import io.github.cubsync.core.protocol.HelloRequest
import io.github.cubsync.core.protocol.Messages
import io.github.cubsync.core.protocol.Protocol
import io.github.cubsync.core.protocol.StreamOpened
import io.github.cubsync.core.protocol.WriteCloseRequest
import io.github.cubsync.core.protocol.WriteOpenRequest
import io.github.cubsync.testing.MemoryBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The write path against a peer that does not behave.
 *
 * Chunks travel on the data connection and the close request on the control
 * connection. Two TCP connections give no ordering between them, so the close can
 * arrive while the last chunk is still in flight. These tests drive that case with
 * a raw client, because a well-behaved client cannot be made to produce it on
 * demand.
 */
class LanWriteIntegrityTest {

    private class RawPeer(
        val store: MemoryBackend,
        val scope: CoroutineScope,
        val server: LanServer,
        val control: FrameChannel,
        val data: FrameChannel,
        val token: Long,
    ) : AutoCloseable {

        var nextReqNo = 10

        suspend fun command(command: Int, message: io.github.cubsync.core.protocol.Message): Frame {
            control.send(
                Frame(
                    FrameType.COMMAND,
                    FrameFlags.ACK_REQUIRED,
                    0,
                    nextReqNo++,
                    Messages.encodeCommand(command, message),
                ),
            )
            return control.receive()
        }

        override fun close() {
            control.close()
            data.close()
            scope.cancel()
            server.close()
        }
    }

    private suspend fun startPeer(writeCloseTimeoutMs: Long): RawPeer {
        val store = MemoryBackend(rootPaths = listOf("/data"))
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val server = LanServer(
            backend = store,
            exposedRoots = listOf("/data"),
            port = 0,
            bindAddress = "127.0.0.1",
            writeCloseTimeoutMs = writeCloseTimeoutMs,
        )
        val port = server.bind()
        server.start(scope)

        val token = 4_242L
        val control = FrameChannel(Socket("127.0.0.1", port), "raw control")
        control.send(Frame(FrameType.HANDSHAKE, FrameFlags.NONE, 0, 0, Messages.encodeMessage(Bind(Bind.ROLE_CONTROL, token))))
        control.send(
            Frame(
                FrameType.COMMAND,
                FrameFlags.ACK_REQUIRED,
                0,
                1,
                Messages.encodeCommand(
                    Command.HELLO,
                    HelloRequest(Protocol.VERSION, LanConnection.SERVER_CAPABILITIES, "raw", "jvm", "0.1.0"),
                ),
            ),
        )
        assertEquals(FrameType.RESPONSE, control.receive().type)

        val data = FrameChannel(Socket("127.0.0.1", port), "raw data")
        data.send(Frame(FrameType.HANDSHAKE, FrameFlags.NONE, 0, 0, Messages.encodeMessage(Bind(Bind.ROLE_DATA, token))))
        assertEquals(FrameType.HANDSHAKE, data.receive().type)

        return RawPeer(store, scope, server, control, data, token)
    }

    private fun chunk(streamId: Long, index: Int, offset: Long, text: String, isFinal: Boolean) = Frame(
        FrameType.DATA,
        if (isFinal) FrameFlags.FINAL else FrameFlags.NONE,
        streamId,
        0,
        Messages.encodeChunk(
            DataChunkHeader(streamId, index, offset, text.length),
            text.encodeToByteArray(),
            0,
            text.length,
        ),
    )

    @Test
    fun closing_a_write_before_its_last_chunk_arrives_is_refused() = runBlocking {
        val peer = startPeer(writeCloseTimeoutMs = 500)
        try {
            val opened = peer.command(
                Command.WRITE_OPEN,
                WriteOpenRequest("/data", "target.bin", size = 8, mtimeMs = 7, hash = "", chunkSize = 4, resume = true),
            )
            val stream = Messages.decodeCommand(opened.payload).message as StreamOpened
            assertEquals(0L, stream.offset)

            // Half of the file, and no final chunk: exactly what a peer that lost its
            // last packet would look like.
            peer.data.send(chunk(stream.streamId, 0, 0, "abcd", isFinal = false))

            // Sent immediately, so it overtakes the chunk.
            val answer = peer.command(Command.WRITE_CLOSE, WriteCloseRequest(stream.streamId, ""))
            assertEquals(FrameType.ERROR, answer.type)
            val error = Messages.decodeError(answer.payload)
            assertEquals(ErrorCode.IO_ERROR, error.code)
            assertTrue(error.message.contains("before the last chunk arrived"), error.message)

            // The half-written file must not have been committed under the real name.
            assertFalse(peer.store.exists("/data", "target.bin"))
        } finally {
            peer.close()
        }
    }

    @Test
    fun a_complete_write_still_commits() = runBlocking {
        val peer = startPeer(writeCloseTimeoutMs = 5_000)
        try {
            val opened = peer.command(
                Command.WRITE_OPEN,
                WriteOpenRequest("/data", "target.bin", size = 8, mtimeMs = 7, hash = "", chunkSize = 4, resume = true),
            )
            val stream = Messages.decodeCommand(opened.payload).message as StreamOpened

            peer.data.send(chunk(stream.streamId, 0, 0, "abcd", isFinal = false))
            peer.data.send(chunk(stream.streamId, 1, 4, "efgh", isFinal = true))

            val answer = peer.command(Command.WRITE_CLOSE, WriteCloseRequest(stream.streamId, ""))
            assertEquals(FrameType.RESPONSE, answer.type)

            assertEquals("abcdefgh", peer.store.readText("/data", "target.bin"))
            assertEquals(7L, peer.store.stat(io.github.cubsync.core.spi.SimpleRoot("memory", "/data"), "target.bin")?.mtimeMs)
        } finally {
            peer.close()
        }
    }

    @Test
    fun chunks_that_arrive_out_of_order_are_written_in_order() = runBlocking {
        val peer = startPeer(writeCloseTimeoutMs = 5_000)
        try {
            val opened = peer.command(
                Command.WRITE_OPEN,
                WriteOpenRequest("/data", "target.bin", size = 12, mtimeMs = 1, hash = "", chunkSize = 4, resume = true),
            )
            val stream = Messages.decodeCommand(opened.payload).message as StreamOpened

            // Last first, then the middle, then the beginning: the receiver holds the
            // ones it cannot place yet and releases everything once the gap closes.
            peer.data.send(chunk(stream.streamId, 2, 8, "cccc", isFinal = true))
            peer.data.send(chunk(stream.streamId, 1, 4, "bbbb", isFinal = false))
            peer.data.send(chunk(stream.streamId, 0, 0, "aaaa", isFinal = false))

            val answer = peer.command(Command.WRITE_CLOSE, WriteCloseRequest(stream.streamId, ""))
            assertEquals(FrameType.RESPONSE, answer.type)
            assertEquals("aaaabbbbcccc", peer.store.readText("/data", "target.bin"))
        } finally {
            peer.close()
        }
    }
}
