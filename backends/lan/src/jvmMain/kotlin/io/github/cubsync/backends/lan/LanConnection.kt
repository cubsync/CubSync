package io.github.cubsync.backends.lan

import io.github.cubsync.core.protocol.Capabilities
import io.github.cubsync.core.protocol.Command
import io.github.cubsync.core.protocol.ErrorCode
import io.github.cubsync.core.protocol.Frame
import io.github.cubsync.core.protocol.FrameFlags
import io.github.cubsync.core.protocol.FrameType
import io.github.cubsync.core.protocol.Message
import io.github.cubsync.core.protocol.Messages
import io.github.cubsync.core.protocol.Protocol
import io.github.cubsync.core.protocol.ProtocolException
import io.github.cubsync.core.spi.BackendException
import io.github.cubsync.core.util.MonoClock
import io.github.cubsync.core.util.systemMonoClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * One session: a control connection and a data connection that belong together.
 *
 * Requests on the control connection are correlated by request number, so several
 * of them can be in flight at once — the engine copies files in parallel, and
 * serialising every metadata call would make parallel transfers pointless. Data
 * frames arrive on the other connection and are routed to whoever opened the
 * stream they belong to, which is what keeps a large transfer from blocking a
 * command.
 */
class LanConnection(
    val sessionToken: Long,
    val remoteNodeName: String,
    val negotiatedCapabilities: Long,
    val remoteAddress: String,
    private val control: FrameChannel,
    private val data: FrameChannel,
    val scope: CoroutineScope,
    private val handler: CommandHandler,
    private val monoClock: MonoClock = systemMonoClock(),
) : Closeable {

    /** Handles a command sent by the peer. Implementations answer through [respond]. */
    interface CommandHandler {
        suspend fun onCommand(connection: LanConnection, reqNo: Int, command: Int, message: Message)
    }

    private val requestCounter = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Message>>()
    private val dataStreams = ConcurrentHashMap<Long, Channel<Frame>>()
    private val closed = CompletableDeferred<Unit>()

    @Volatile
    private var lastReceivedMs: Long = monoClock.nowMs()

    private val jobs = mutableListOf<Job>()

    val isOpen: Boolean get() = control.isOpen && data.isOpen && !closed.isCompleted

    fun start() {
        jobs.add(scope.launch { controlLoop() })
        jobs.add(scope.launch { dataLoop() })
        jobs.add(scope.launch { heartbeatLoop() })
        jobs.add(scope.launch { watchdogLoop() })
    }

    // -- requests ------------------------------------------------------------

    suspend fun request(command: Int, message: Message, timeoutMs: Long = REQUEST_TIMEOUT_MS): Message {
        if (!isOpen) throw LinkClosedException("the link to $remoteNodeName is closed")
        val reqNo = requestCounter.getAndIncrement()
        val deferred = CompletableDeferred<Message>()
        pending[reqNo] = deferred
        try {
            control.send(Frame(FrameType.COMMAND, FrameFlags.ACK_REQUIRED, 0, reqNo, Messages.encodeCommand(command, message)))
            return withTimeout(timeoutMs) { deferred.await() }
        } catch (timedOut: TimeoutCancellationException) {
            throw BackendException(
                ErrorCode.IO_ERROR,
                "${Command.name(command)} timed out after ${timeoutMs}ms (${remoteNodeName})",
                timedOut,
            )
        } finally {
            pending.remove(reqNo)
        }
    }

    suspend fun respond(reqNo: Int, command: Int, message: Message) {
        control.send(Frame(FrameType.RESPONSE, FrameFlags.NONE, 0, reqNo, Messages.encodeCommand(command, message)))
    }

    suspend fun respondError(reqNo: Int, command: Int, code: Int, message: String, detail: String = "") {
        control.send(
            Frame(
                FrameType.ERROR,
                FrameFlags.NONE,
                0,
                reqNo,
                Messages.encodeError(io.github.cubsync.core.protocol.ErrorMessage(code, command, message, detail)),
            ),
        )
    }

    // -- streams -------------------------------------------------------------

    /** Registers the queue a stream's data frames will arrive on. */
    fun registerStream(streamId: Long): Channel<Frame> {
        val queue = Channel<Frame>(DATA_QUEUE_CAPACITY)
        dataStreams[streamId] = queue
        return queue
    }

    fun closeStream(streamId: Long) {
        dataStreams.remove(streamId)?.close()
    }

    suspend fun sendChunk(frame: Frame) = data.send(frame)

    // -- loops ---------------------------------------------------------------

    private suspend fun controlLoop() {
        try {
            while (currentCoroutineContext().isActive) {
                val frame = control.receive()
                lastReceivedMs = monoClock.nowMs()
                when (frame.type) {
                    FrameType.RESPONSE -> {
                        val envelope = Messages.decodeCommand(frame.payload)
                        pending.remove(frame.reqNo)?.complete(envelope.message)
                    }

                    FrameType.ERROR -> {
                        val error = Messages.decodeError(frame.payload)
                        pending.remove(frame.reqNo)?.completeExceptionally(
                            BackendException(error.code, error.message + if (error.detail.isEmpty()) "" else ": ${error.detail}"),
                        )
                    }

                    FrameType.COMMAND -> dispatchCommand(frame)

                    // The handshake happens before the loops start; a handshake
                    // frame arriving now is a peer that is confused about the state
                    // of the connection, and ignoring it is safer than guessing.
                    FrameType.HANDSHAKE -> Unit

                    FrameType.EVENT, FrameType.DATA -> Unit
                }
            }
        } catch (closedLink: LinkClosedException) {
            shutdown(closedLink.message ?: "link closed")
        } catch (failure: Throwable) {
            shutdown(failure.message ?: "control loop failed")
        }
    }

    private suspend fun dispatchCommand(frame: Frame) {
        if (frame.payload.size < 4) {
            respondError(frame.reqNo, 0, ErrorCode.MALFORMED_PAYLOAD, "command frame without a command number")
            return
        }
        val rawCommand = ((frame.payload[0].toInt() and 0xFF) shl 8) or (frame.payload[1].toInt() and 0xFF)
        val envelope = try {
            Messages.decodeCommand(frame.payload)
        } catch (unknown: ProtocolException) {
            // A command this build does not implement is answered by name, so the
            // peer learns what went wrong instead of seeing a dead connection.
            respondError(frame.reqNo, rawCommand, unknown.code, unknown.message ?: "unsupported command")
            return
        }
        if (envelope.command == Command.KEEPALIVE) return
        try {
            handler.onCommand(this, frame.reqNo, envelope.command, envelope.message)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: BackendException) {
            respondError(frame.reqNo, envelope.command, failure.code, failure.message ?: "failed")
        } catch (failure: Throwable) {
            respondError(frame.reqNo, envelope.command, ErrorCode.INTERNAL, failure.message ?: "failed")
        }
    }

    private suspend fun dataLoop() {
        try {
            while (currentCoroutineContext().isActive) {
                val frame = data.receive()
                lastReceivedMs = monoClock.nowMs()
                val queue = dataStreams[frame.streamId]
                if (queue == null) {
                    // Frames for a stream that was closed in the meantime: dropping
                    // them is correct, failing the session would not be.
                    continue
                }
                queue.send(frame)
            }
        } catch (closedLink: LinkClosedException) {
            shutdown(closedLink.message ?: "data link closed")
        } catch (failure: Throwable) {
            shutdown(failure.message ?: "data loop failed")
        }
    }

    private suspend fun heartbeatLoop() {
        try {
            while (currentCoroutineContext().isActive) {
                delay(Protocol.HEARTBEAT_INTERVAL_MS)
                if (!isOpen) return
                control.send(
                    Frame(
                        FrameType.COMMAND,
                        FrameFlags.NONE,
                        0,
                        0,
                        Messages.encodeCommand(Command.KEEPALIVE, io.github.cubsync.core.protocol.Keepalive(monoClock.nowMs())),
                    ),
                )
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            shutdown(failure.message ?: "heartbeat failed")
        }
    }

    /**
     * A monotonic watchdog rather than a socket timeout.
     *
     * Setting `SO_TIMEOUT` on the socket would kill a healthy transfer: a large
     * block legitimately takes longer to arrive than the heartbeat interval, and
     * the timeout cannot tell the two apart.
     */
    private suspend fun watchdogLoop() {
        while (currentCoroutineContext().isActive) {
            delay(WATCHDOG_INTERVAL_MS)
            if (!isOpen) return
            val silentFor = monoClock.nowMs() - lastReceivedMs
            if (silentFor > Protocol.HEARTBEAT_TIMEOUT_MS) {
                shutdown("no traffic from $remoteNodeName for ${silentFor}ms")
                return
            }
        }
    }

    private fun shutdown(reason: String) {
        if (closed.isCompleted) return
        closed.complete(Unit)
        pending.values.forEach { it.completeExceptionally(LinkClosedException(reason)) }
        pending.clear()
        dataStreams.values.forEach { it.close() }
        dataStreams.clear()
        jobs.forEach { it.cancel() }
        control.close()
        data.close()
    }

    override fun close() = shutdown("closed locally")

    companion object {
        const val REQUEST_TIMEOUT_MS: Long = 60_000
        private const val DATA_QUEUE_CAPACITY: Int = 8
        private const val WATCHDOG_INTERVAL_MS: Long = 1_000

        /** Capabilities a first-party peer always has. */
        val SERVER_CAPABILITIES: Long = Capabilities.LIST or Capabilities.STAT or Capabilities.HASH or
            Capabilities.MKDIR or Capabilities.DELETE or Capabilities.RENAME or Capabilities.SET_MTIME or
            Capabilities.FREE_SPACE or Capabilities.RANDOM_WRITE or Capabilities.RESUME or Capabilities.BATCH_HASH or
            Capabilities.TREE_AT_ONCE
    }
}
