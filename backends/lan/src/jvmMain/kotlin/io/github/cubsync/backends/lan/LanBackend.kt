package io.github.cubsync.backends.lan

import io.github.cubsync.core.protocol.Bind
import io.github.cubsync.core.protocol.CloseStreamRequest
import io.github.cubsync.core.protocol.Command
import io.github.cubsync.core.protocol.DataChunkHeader
import io.github.cubsync.core.protocol.DeleteRequest
import io.github.cubsync.core.protocol.ErrorCode
import io.github.cubsync.core.protocol.Frame
import io.github.cubsync.core.protocol.FrameFlags
import io.github.cubsync.core.protocol.FrameType
import io.github.cubsync.core.protocol.FreeSpaceReply
import io.github.cubsync.core.protocol.FreeSpaceRequest
import io.github.cubsync.core.protocol.HashReply
import io.github.cubsync.core.protocol.HashRequest
import io.github.cubsync.core.protocol.HelloReply
import io.github.cubsync.core.protocol.HelloRequest
import io.github.cubsync.core.protocol.ListReply
import io.github.cubsync.core.protocol.ListRequest
import io.github.cubsync.core.protocol.Messages
import io.github.cubsync.core.protocol.MkdirRequest
import io.github.cubsync.core.protocol.Protocol
import io.github.cubsync.core.protocol.ReadChunkRequest
import io.github.cubsync.core.protocol.ReadOpenRequest
import io.github.cubsync.core.protocol.RenameRequest
import io.github.cubsync.core.protocol.RootsReply
import io.github.cubsync.core.protocol.RootsRequest
import io.github.cubsync.core.protocol.SetMtimeRequest
import io.github.cubsync.core.protocol.StatReply
import io.github.cubsync.core.protocol.StatRequest
import io.github.cubsync.core.protocol.StreamOpened
import io.github.cubsync.core.protocol.WriteCloseRequest
import io.github.cubsync.core.protocol.WriteOpenRequest
import io.github.cubsync.core.spi.BackendException
import io.github.cubsync.core.spi.Chunk
import io.github.cubsync.core.spi.ReadStream
import io.github.cubsync.core.spi.RemoteBackend
import io.github.cubsync.core.spi.RootRef
import io.github.cubsync.core.spi.SimpleRoot
import io.github.cubsync.core.spi.WriteStream
import io.github.cubsync.core.transfer.ChunkPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A peer's file tree, reached over the first-party protocol.
 *
 * From the engine's point of view this is just another [RemoteBackend]. What it
 * adds over a third-party protocol is the pairing of a control connection with a
 * data connection, so metadata calls keep flowing while a large file is in flight,
 * and a chunk index that lets the receiver reorder what arrives.
 */
class LanBackend(
    override val id: String,
    override val displayName: String,
    private val connection: LanConnection,
    private val chunkSize: Int = ChunkPlan.DEFAULT_CHUNK_SIZE,
) : RemoteBackend {

    override val capabilities: Long get() = connection.negotiatedCapabilities

    val remoteAddress: String get() = connection.remoteAddress
    val isOpen: Boolean get() = connection.isOpen
    val negotiationNote: String get() = "protocol ${Protocol.VERSION}"

    /** Asks the peer which trees it is willing to serve. */
    override suspend fun roots(): List<RootRef> =
        connection.request(Command.ROOTS, RootsRequest())
            .let { it as RootsReply }
            .roots
            .map { SimpleRoot(id, it) }

    override suspend fun list(root: RootRef, relPath: String, recursive: Boolean) =
        connection.request(Command.LIST, ListRequest(root.path, relPath, recursive, 0, 0))
            .let { it as ListReply }
            .entries
            .map { it.toMeta() }

    override suspend fun stat(root: RootRef, relPath: String) =
        connection.request(Command.STAT, StatRequest(root.path, relPath))
            .let { it as StatReply }
            .entry
            ?.toMeta()

    override suspend fun hash(root: RootRef, relPath: String, algorithm: String): String =
        connection.request(Command.HASH, HashRequest(root.path, relPath, algorithm))
            .let { it as HashReply }
            .hex

    override suspend fun mkdir(root: RootRef, relPath: String, createParents: Boolean) {
        connection.request(Command.MKDIR, MkdirRequest(root.path, relPath))
    }

    override suspend fun delete(root: RootRef, relPath: String, toTrash: Boolean) {
        connection.request(Command.DELETE, DeleteRequest(root.path, relPath, toTrash))
    }

    override suspend fun rename(root: RootRef, fromRel: String, toRel: String) {
        connection.request(Command.RENAME, RenameRequest(root.path, fromRel, toRel))
    }

    override suspend fun setMtime(root: RootRef, relPath: String, mtimeMs: Long) {
        connection.request(Command.SET_MTIME, SetMtimeRequest(root.path, relPath, mtimeMs))
    }

    override suspend fun freeSpace(root: RootRef): Long =
        connection.request(Command.FREE_SPACE, FreeSpaceRequest(root.path))
            .let { it as FreeSpaceReply }
            .freeBytes

    override suspend fun openRead(root: RootRef, relPath: String, offset: Long, length: Long): ReadStream {
        val opened = connection.request(
            Command.READ_OPEN,
            ReadOpenRequest(root.path, relPath, offset, length, chunkSize),
        ) as StreamOpened
        val queue = connection.registerStream(opened.streamId)
        return RemoteReadStream(opened, queue)
    }

    override suspend fun openWrite(
        root: RootRef,
        relPath: String,
        size: Long,
        mtimeMs: Long,
        resume: Boolean,
    ): WriteStream {
        val opened = connection.request(
            Command.WRITE_OPEN,
            WriteOpenRequest(root.path, relPath, size, mtimeMs, hash = "", chunkSize = chunkSize, resume = resume),
        ) as StreamOpened
        return RemoteWriteStream(opened, size)
    }

    override fun close() = connection.close()

    private inner class RemoteReadStream(
        private val opened: StreamOpened,
        private val queue: Channel<Frame>,
    ) : ReadStream {

        private var position = opened.offset
        private val end = opened.offset + opened.size

        override val size: Long get() = opened.size
        override val startOffset: Long get() = opened.offset
        override val chunkSize: Int get() = opened.chunkSize

        override suspend fun read(maxBytes: Int): Chunk? {
            if (position >= end) return null
            val want = minOf(maxBytes.toLong(), chunkSize.toLong()).toInt()
            connection.request(Command.READ_CHUNK, ReadChunkRequest(opened.streamId, position, want))
            val frame = queue.receiveCatching().getOrNull()
                ?: throw LinkClosedException("the stream ended before the file did (${opened.streamId})")
            val header = Messages.decodeChunkHeader(frame.payload)
            if (header.length == 0) return null
            val data = ByteArray(header.length)
            frame.payload.copyInto(data, 0, DataChunkHeader.SIZE, DataChunkHeader.SIZE + header.length)
            position = header.fileOffset + header.length
            return Chunk(header.fileOffset, data, header.length)
        }

        override fun close() {
            // Drops the routing entry; the server frees the stream when the request
            // arrives, and unconditionally when the session ends.
            connection.closeStream(opened.streamId)
        }
    }

    /**
     * Buffers into uniform chunks.
     *
     * The server derives a chunk's index from its offset, so chunks have to be
     * exactly `chunkSize` bytes for the index to be meaningful — which is what
     * makes an out-of-order arrival fixable at the receiving end.
     */
    private inner class RemoteWriteStream(
        private val opened: StreamOpened,
        private val declaredSize: Long,
    ) : WriteStream {

        override val handle: String = "lan-stream-${opened.streamId}"
        override val startOffset: Long get() = opened.offset
        override val chunkSize: Int get() = opened.chunkSize

        private val buffer = ByteArray(opened.chunkSize)
        private var buffered = 0
        private var fileOffset = opened.offset
        private var sentAny = false
        private var finished = false

        override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
            var consumed = 0
            while (consumed < length) {
                val take = minOf(length - consumed, chunkSize - buffered)
                buffer.copyInto(this.buffer, buffered, offset + consumed, offset + consumed + take)
                buffered += take
                consumed += take
                if (buffered == chunkSize) sendChunk()
            }
        }

        private suspend fun sendChunk() {
            val isLast = fileOffset + buffered >= declaredSize
            connection.sendChunk(
                Frame(
                    FrameType.DATA,
                    if (isLast) FrameFlags.FINAL else FrameFlags.NONE,
                    opened.streamId,
                    0,
                    Messages.encodeChunk(
                        DataChunkHeader(opened.streamId, (fileOffset / chunkSize).toInt(), fileOffset, buffered),
                        this.buffer,
                        0,
                        buffered,
                    ),
                ),
            )
            fileOffset += buffered
            buffered = 0
            sentAny = true
        }

        override suspend fun commit(mtimeMs: Long) {
            if (finished) return
            when {
                // A zero-length file still has to announce its end, or the receiver
                // would wait for a final chunk that never comes.
                declaredSize == 0L -> {
                    connection.sendChunk(
                        Frame(
                            FrameType.DATA,
                            FrameFlags.FINAL,
                            opened.streamId,
                            0,
                            Messages.encodeChunk(DataChunkHeader(opened.streamId, 0, opened.offset, 0), ByteArray(0), 0, 0),
                        ),
                    )
                    sentAny = true
                }

                buffered > 0 -> sendChunk()
                !sentAny -> Unit
            }
            connection.request(Command.WRITE_CLOSE, WriteCloseRequest(opened.streamId, ""))
            connection.closeStream(opened.streamId)
            finished = true
        }

        override suspend fun abort() {
            if (finished) return
            runCatching { connection.request(Command.CLOSE_STREAM, CloseStreamRequest(opened.streamId)) }
            connection.closeStream(opened.streamId)
            finished = true
        }

        override fun close() {
            if (!finished) connection.closeStream(opened.streamId)
        }
    }

    companion object {
        /**
         * Completes the handshake and returns a backend for the peer.
         *
         * Both connections are opened here, before anything else can use the
         * backend, so a half-established session is never handed out.
         */
        suspend fun connect(
            host: String,
            port: Int = Protocol.DEFAULT_PORT,
            scope: CoroutineScope,
            nodeName: String = "CubSync client",
            platform: String = System.getProperty("os.name") ?: "jvm",
            appVersion: String = "0.1.0",
            connectTimeoutMs: Int = 10_000,
        ): LanBackend = withContext(Dispatchers.IO) {
            val token = LanServer.newSessionToken()
            val controlSocket = openSocket(host, port, connectTimeoutMs)
            val control = FrameChannel(controlSocket, "control to $host:$port")
            control.send(
                Frame(FrameType.HANDSHAKE, FrameFlags.NONE, 0, 0, Messages.encodeMessage(Bind(Bind.ROLE_CONTROL, token))),
            )
            control.send(
                Frame(
                    FrameType.COMMAND,
                    FrameFlags.ACK_REQUIRED,
                    0,
                    HELLO_REQ_NO,
                    Messages.encodeCommand(
                        Command.HELLO,
                        HelloRequest(
                            protocolVersion = Protocol.VERSION,
                            capabilities = LanConnection.SERVER_CAPABILITIES,
                            deviceName = nodeName,
                            platform = platform,
                            appVersion = appVersion,
                        ),
                    ),
                ),
            )

            val replyFrame = control.receive()
            if (replyFrame.type == FrameType.ERROR) {
                val error = Messages.decodeError(replyFrame.payload)
                control.close()
                throw BackendException(error.code, "the peer refused the connection: ${error.message}")
            }
            val reply = Messages.decodeCommand(replyFrame.payload).message as? HelloReply
                ?: run {
                    control.close()
                    throw BackendException(ErrorCode.INTERNAL, "the peer answered the handshake with something else")
                }
            if (!reply.accepted) {
                control.close()
                throw BackendException(ErrorCode.UNSUPPORTED_VERSION, reply.note)
            }

            val dataSocket = openSocket(host, port, connectTimeoutMs)
            val data = FrameChannel(dataSocket, "data to $host:$port")
            data.send(
                Frame(FrameType.HANDSHAKE, FrameFlags.NONE, 0, 0, Messages.encodeMessage(Bind(Bind.ROLE_DATA, token))),
            )
            val ack = data.receive()
            val bound = ack.type == FrameType.HANDSHAKE &&
                (runCatching { Messages.decodeMessage(ack.payload) }.getOrNull() as? Bind)?.token == token
            if (!bound) {
                control.close()
                data.close()
                throw LinkClosedException("the peer did not accept the data connection")
            }

            val connection = LanConnection(
                sessionToken = token,
                remoteNodeName = reply.deviceName,
                negotiatedCapabilities = reply.capabilities,
                remoteAddress = "${dataSocket.inetAddress.hostAddress}:$port",
                control = control,
                data = data,
                scope = scope,
                handler = RejectingCommandHandler(),
            )
            connection.start()
            LanBackend(
                id = "lan:${dataSocket.inetAddress.hostAddress}:$port",
                displayName = reply.deviceName.ifBlank { "$host:$port" },
                connection = connection,
            )
        }

        private fun openSocket(host: String, port: Int, timeoutMs: Int): Socket {
            val socket = Socket()
            FrameChannel.configure(socket)
            try {
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            } catch (failure: Exception) {
                runCatching { socket.close() }
                throw LinkClosedException("cannot reach $host:$port: ${failure.message}", failure)
            }
            return socket
        }

        private const val HELLO_REQ_NO: Int = 1
    }
}

/**
 * A client has nothing to serve, so any command from the server is answered with an
 * explicit error rather than silence — a silent peer looks like a hung one.
 */
private class RejectingCommandHandler : LanConnection.CommandHandler {
    override suspend fun onCommand(
        connection: LanConnection,
        reqNo: Int,
        command: Int,
        message: io.github.cubsync.core.protocol.Message,
    ) {
        connection.respondError(
            reqNo,
            command,
            ErrorCode.UNKNOWN_COMMAND,
            "this endpoint is a client and does not serve commands",
        )
    }
}
