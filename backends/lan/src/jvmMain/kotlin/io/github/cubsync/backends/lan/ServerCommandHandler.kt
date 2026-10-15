package io.github.cubsync.backends.lan

import io.github.cubsync.core.protocol.Ack
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
import io.github.cubsync.core.protocol.ListReply
import io.github.cubsync.core.protocol.ListRequest
import io.github.cubsync.core.protocol.Message
import io.github.cubsync.core.protocol.Messages
import io.github.cubsync.core.protocol.MkdirRequest
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
import io.github.cubsync.core.spi.ReadStream
import io.github.cubsync.core.spi.RemoteBackend
import io.github.cubsync.core.spi.RootRef
import io.github.cubsync.core.spi.SimpleRoot
import io.github.cubsync.core.spi.WriteStream
import io.github.cubsync.core.transfer.ChunkMerger
import io.github.cubsync.core.transfer.ChunkPlan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Answers a peer's commands from a [RemoteBackend].
 *
 * Everything the engine needs to synchronise is expressed here as commands, so the
 * server does not know what the client is doing with the files — it only knows how
 * to read, write and rename them. That is what makes the same server usable by the
 * desktop app, the daemon, and a future headless sync job.
 *
 * The root check happens here rather than in the backend: a peer may only name a
 * folder tree the server was started with, and any path inside that tree is
 * normalised before use so `..` cannot climb out of it.
 */
class ServerCommandHandler(
    private val backend: RemoteBackend,
    private val exposedRoots: List<String>,
    private val nodeName: String,
    private val chunkSize: Int = ChunkPlan.DEFAULT_CHUNK_SIZE,
    private val writeCloseTimeoutMs: Long = 120_000,
) : LanConnection.CommandHandler {

    private sealed interface OpenStream

    private class ReadSession(val stream: ReadStream) : OpenStream

    private class WriteSession(
        val streamId: Long,
        val stream: WriteStream,
        val queue: Channel<Frame>,
        val consumer: Job,
        val failure: AtomicReference<Throwable?>,
        val mtimeMs: Long,
        /** Completed once every chunk up to and including the final one has been written. */
        val merged: CompletableDeferred<Unit>,
    ) : OpenStream

    private val streams = ConcurrentHashMap<Long, OpenStream>()
    private val streamIds = AtomicLong(1)

    override suspend fun onCommand(connection: LanConnection, reqNo: Int, command: Int, message: Message) {
        when (command) {
            Command.LIST -> {
                val request = message as ListRequest
                val entries = backend.list(root(request.rootPath), request.relPath, request.recursive)
                connection.respond(reqNo, command, ListReply(entries.map { it.toEntry() }, 0, true))
            }

            Command.STAT -> {
                val request = message as StatRequest
                val meta = backend.stat(root(request.rootPath), request.relPath)
                connection.respond(reqNo, command, StatReply(meta?.toEntry()))
            }

            Command.HASH -> {
                val request = message as HashRequest
                val hex = backend.hash(root(request.rootPath), request.relPath, request.algorithm)
                val size = backend.stat(root(request.rootPath), request.relPath)?.size ?: 0
                connection.respond(reqNo, command, HashReply(hex, size))
            }

            Command.MKDIR -> {
                val request = message as MkdirRequest
                backend.mkdir(root(request.rootPath), request.relPath, createParents = true)
                connection.respond(reqNo, command, Ack(0))
            }

            Command.DELETE -> {
                val request = message as DeleteRequest
                backend.delete(root(request.rootPath), request.relPath, request.toTrash)
                connection.respond(reqNo, command, Ack(0))
            }

            Command.RENAME -> {
                val request = message as RenameRequest
                backend.rename(root(request.rootPath), request.fromRel, request.toRel)
                connection.respond(reqNo, command, Ack(0))
            }

            Command.SET_MTIME -> {
                val request = message as SetMtimeRequest
                backend.setMtime(root(request.rootPath), request.relPath, request.mtimeMs)
                connection.respond(reqNo, command, Ack(0))
            }

            Command.FREE_SPACE -> {
                val request = message as FreeSpaceRequest
                connection.respond(reqNo, command, FreeSpaceReply(backend.freeSpace(root(request.rootPath))))
            }

            Command.ROOTS -> connection.respond(reqNo, command, RootsReply(exposedRoots))

            Command.READ_OPEN -> openRead(connection, reqNo, command, message as ReadOpenRequest)

            Command.READ_CHUNK -> sendChunk(connection, reqNo, message as ReadChunkRequest)

            Command.WRITE_OPEN -> openWrite(connection, reqNo, command, message as WriteOpenRequest)

            Command.WRITE_CLOSE -> closeWrite(connection, reqNo, command, message as WriteCloseRequest)

            Command.CLOSE_STREAM -> {
                closeStream(message as CloseStreamRequest)
                connection.respond(reqNo, command, Ack(0))
            }

            else -> connection.respondError(
                reqNo,
                command,
                ErrorCode.UNKNOWN_COMMAND,
                "command $command is not implemented by $nodeName",
            )
        }
    }

    private suspend fun openRead(connection: LanConnection, reqNo: Int, command: Int, request: ReadOpenRequest) {
        val streamId = streamIds.getAndIncrement()
        val stream = backend.openRead(root(request.rootPath), request.relPath, request.offset, request.length)
        streams[streamId] = ReadSession(stream)
        connection.respond(
            reqNo,
            command,
            StreamOpened(streamId, stream.startOffset, stream.size, effectiveChunkSize(request.chunkSize)),
        )
    }

    /**
     * Serves one chunk.
     *
     * One chunk per request keeps the protocol easy to reason about: the peer knows
     * exactly how much it asked for, and a short or empty answer unambiguously means
     * the end of the stream.
     */
    private suspend fun sendChunk(connection: LanConnection, reqNo: Int, request: ReadChunkRequest) {
        val session = streams[request.streamId] as? ReadSession
            ?: throw BackendException(ErrorCode.NOT_FOUND, "no open read stream ${request.streamId}")
        val chunk = session.stream.read(minOf(request.length, chunkSize))
        val streamEnd = session.stream.startOffset + session.stream.size
        if (chunk == null || chunk.length == 0) {
            connection.sendChunk(
                Frame(
                    FrameType.DATA,
                    FrameFlags.FINAL,
                    request.streamId,
                    reqNo,
                    Messages.encodeChunk(
                        DataChunkHeader(request.streamId, (session.stream.startOffset / chunkSize).toInt(), streamEnd, 0),
                        EMPTY,
                        0,
                        0,
                    ),
                ),
            )
            connection.respond(reqNo, Command.READ_CHUNK, Ack(0))
            return
        }
        val index = (chunk.offset / chunkSize).toInt()
        val final = chunk.offset + chunk.length >= streamEnd
        connection.sendChunk(
            Frame(
                FrameType.DATA,
                if (final) FrameFlags.FINAL else FrameFlags.NONE,
                request.streamId,
                reqNo,
                Messages.encodeChunk(
                    DataChunkHeader(request.streamId, index, chunk.offset, chunk.length),
                    chunk.data,
                    0,
                    chunk.length,
                ),
            ),
        )
        // The data frame goes out first, then a small acknowledgement on the
        // control channel. The requester waits for the acknowledgement, so it
        // cannot mistake a slow arrival for a failure — and an error still reaches
        // it through the normal response path.
        connection.respond(reqNo, Command.READ_CHUNK, Ack(chunk.length.toLong()))
    }

    private suspend fun openWrite(connection: LanConnection, reqNo: Int, command: Int, request: WriteOpenRequest) {
        val streamId = streamIds.getAndIncrement()
        val chunk = effectiveChunkSize(request.chunkSize)
        val stream = backend.openWrite(
            root = root(request.rootPath),
            relPath = request.relPath,
            size = request.size,
            mtimeMs = request.mtimeMs,
            resume = request.resume,
        )
        val queue = connection.registerStream(streamId)
        val failure = AtomicReference<Throwable?>(null)
        val merged = CompletableDeferred<Unit>()
        val merger = ChunkMerger.startingAt(stream.startOffset, chunk)

        // Chunks may arrive out of order; the merger releases them in order and the
        // stream is written sequentially, which is what the backend contract allows.
        val consumer = connection.scope.launch {
            try {
                for (frame in queue) {
                    val header = Messages.decodeChunkHeader(frame.payload)
                    val length = header.length
                    val data = ByteArray(length)
                    if (length > 0) frame.payload.copyInto(data, 0, DataChunkHeader.SIZE, DataChunkHeader.SIZE + length)
                    val isFinal = (frame.flags and FrameFlags.FINAL) != 0
                    for (released in merger.accept(header.chunkIndex, data, length, isFinal)) {
                        stream.write(released.data, 0, released.length)
                    }
                    if (merger.finished) break
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure0: Throwable) {
                failure.set(failure0)
            } finally {
                // The close handler waits on this rather than on the queue being
                // empty: chunks travel on the data connection and the close request
                // on the control connection, and those two have no ordering
                // between them.
                if (merger.finished) merged.complete(Unit)
                queue.close()
            }
        }

        streams[streamId] = WriteSession(streamId, stream, queue, consumer, failure, request.mtimeMs, merged)
        connection.respond(
            reqNo,
            command,
            StreamOpened(streamId, stream.startOffset, request.size, chunk),
        )
    }

    /**
     * Finishes a write.
     *
     * The chunks arrive on the data connection and the close request on the control
     * connection, and TCP gives no ordering between two connections — so the close
     * can be processed while the last chunk is still in flight. Waiting for the
     * merger to report that it released everything is what makes the difference
     * between a completed file and a silently truncated one, and a write that never
     * announces its end is failed rather than committed.
     */
    private suspend fun closeWrite(connection: LanConnection, reqNo: Int, command: Int, request: WriteCloseRequest) {
        val session = streams.remove(request.streamId) as? WriteSession
            ?: throw BackendException(ErrorCode.NOT_FOUND, "no open write stream ${request.streamId}")

        val complete = withTimeoutOrNull(writeCloseTimeoutMs) { session.merged.await() } != null
        if (!complete) {
            session.queue.close()
            runCatching { session.stream.abort() }
            throw BackendException(
                ErrorCode.IO_ERROR,
                "the write was closed before the last chunk arrived (stream ${request.streamId})",
            )
        }

        session.queue.close()
        withTimeoutOrNull(writeCloseTimeoutMs) { session.consumer.join() }
        session.failure.get()?.let { failure ->
            runCatching { session.stream.abort() }
            throw BackendException(ErrorCode.IO_ERROR, "writing failed: ${failure.message}", failure)
        }
        session.stream.commit(session.mtimeMs)
        connection.closeStream(request.streamId)
        connection.respond(reqNo, command, Ack(0))
    }

    private suspend fun closeStream(request: CloseStreamRequest) {
        when (val session = streams.remove(request.streamId)) {
            is ReadSession -> session.stream.close()
            is WriteSession -> {
                session.queue.close()
                withTimeoutOrNull(10_000) { session.consumer.join() }
                runCatching { session.stream.abort() }
            }

            null -> Unit
        }
    }

    private fun effectiveChunkSize(requested: Int): Int = when {
        requested <= 0 -> chunkSize
        requested > MAX_CHUNK -> MAX_CHUNK
        else -> requested
    }

    /**
     * Turns a client-supplied root into something the backend may be asked to serve.
     *
     * The path is normalised first, so a root of `/data` cannot be escaped with
     * `/data/../etc`, and a root that was never exposed is refused outright.
     */
    private fun root(path: String): RootRef {
        val normalized = normalize(path)
        val allowed = exposedRoots.any { exposed ->
            val rootPath = normalize(exposed)
            normalized == rootPath || normalized.startsWith(if (rootPath.endsWith("/")) rootPath else "$rootPath/")
        }
        if (!allowed) {
            throw BackendException(ErrorCode.PATH_OUTSIDE_ROOT, "$path is not exposed by this peer")
        }
        return SimpleRoot(backend.id, path)
    }

    private fun normalize(path: String): String {
        var p = path.replace('\\', '/')
        while (p.contains("//")) p = p.replace("//", "/")
        if (p.length > 1) p = p.trimEnd('/')
        val absolute = p.startsWith("/")
        val parts = ArrayList<String>()
        for (part in p.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) {
                    throw BackendException(ErrorCode.PATH_OUTSIDE_ROOT, "path escapes the peer's roots: $path")
                } else {
                    parts.removeAt(parts.size - 1)
                }

                else -> parts.add(part)
            }
        }
        val body = parts.joinToString("/")
        val joined = if (absolute) "/$body" else body
        return if (!caseSensitive) joined.lowercase() else joined
    }

    /** Windows-style roots differ only by case, so the comparison has to as well. */
    private val caseSensitive: Boolean = run {
        val os = System.getProperty("os.name")?.lowercase() ?: ""
        !(os.contains("windows") || os.contains("mac"))
    }

    companion object {
        private val EMPTY = ByteArray(0)
        private const val MAX_CHUNK: Int = 1 shl 20
    }
}
