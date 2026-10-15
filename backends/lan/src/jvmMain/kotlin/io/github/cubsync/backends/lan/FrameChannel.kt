package io.github.cubsync.backends.lan

import io.github.cubsync.core.protocol.ErrorCode
import io.github.cubsync.core.protocol.Frame
import io.github.cubsync.core.protocol.FrameCodec
import io.github.cubsync.core.protocol.FrameHeader
import io.github.cubsync.core.protocol.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.Socket
import java.net.SocketException

/**
 * Frame transport over one TCP connection.
 *
 * Blocking IO on [Dispatchers.IO] rather than NIO: the protocol is a small number
 * of connections carrying large sequential payloads, which is exactly the shape
 * blocking sockets handle well, and it keeps the code readable. Writes are
 * serialised by a mutex because two coroutines must never interleave half a frame.
 */
class FrameChannel(
    private val socket: Socket,
    private val name: String,
) {

    private val input = DataInputStream(BufferedInputStream(socket.getInputStream(), BUFFER_SIZE))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE))
    private val writeLock = Mutex()
    private val headerBuffer = ByteArray(Protocol.HEADER_SIZE)

    val remoteAddress: String
        get() = "${socket.inetAddress?.hostAddress}:${socket.port}"

    val isOpen: Boolean get() = !socket.isClosed && socket.isConnected

    suspend fun send(frame: Frame) = withContext(Dispatchers.IO) {
        val bytes = FrameCodec.encode(frame)
        writeLock.withLock {
            try {
                output.write(bytes)
                output.flush()
            } catch (failure: IOException) {
                throw LinkClosedException("$name: write failed: ${failure.message}", failure)
            }
        }
    }

    /** Reads the next frame, blocking until one arrives or the peer goes away. */
    suspend fun receive(): Frame = withContext(Dispatchers.IO) {
        try {
            input.readFully(headerBuffer)
            val header: FrameHeader = try {
                FrameCodec.readHeader(headerBuffer)
            } catch (malformed: io.github.cubsync.core.protocol.ProtocolException) {
                throw LinkClosedException(
                    "$name: peer sent a malformed header (code ${malformed.code}): ${malformed.message}",
                    malformed,
                )
            }
            val payload = ByteArray(header.payloadLength)
            if (header.payloadLength > 0) input.readFully(payload)
            header.toFrame(payload)
        } catch (closed: EOFException) {
            throw LinkClosedException("$name: peer closed the connection", closed)
        } catch (reset: SocketException) {
            throw LinkClosedException("$name: connection reset: ${reset.message}", reset)
        } catch (failure: IOException) {
            throw LinkClosedException("$name: read failed: ${failure.message}", failure)
        }
    }

    fun close() {
        runCatching { output.flush() }
        runCatching { socket.close() }
    }

    companion object {
        private const val BUFFER_SIZE: Int = 1 shl 18

        /** Applies the socket options this protocol depends on. */
        fun configure(socket: Socket) {
            socket.tcpNoDelay = true
            socket.soTimeout = 0
            runCatching { socket.sendBufferSize = 1 shl 18 }
            runCatching { socket.receiveBufferSize = 1 shl 18 }
        }
    }
}

/**
 * The connection went away. Distinguishing this from a protocol error matters:
 * one is worth retrying, the other is a bug.
 */
class LinkClosedException(message: String, cause: Throwable? = null) : IOException(message, cause)

internal fun linkError(code: Int, message: String, cause: Throwable? = null): LinkClosedException {
    val detail = if (code == ErrorCode.OK) message else "(${code}) $message"
    return LinkClosedException(detail, cause)
}
