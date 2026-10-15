package io.github.cubsync.testing

import io.github.cubsync.core.protocol.ErrorCode
import io.github.cubsync.core.spi.BackendException
import io.github.cubsync.core.spi.Chunk
import io.github.cubsync.core.spi.ReadStream
import io.github.cubsync.core.spi.RemoteBackend
import io.github.cubsync.core.spi.RootRef
import io.github.cubsync.core.spi.WriteStream

/**
 * Wraps a backend so a test can break it at a chosen point.
 *
 * Failing after the N-th chunk is the only way to test what a transfer leaves
 * behind when it is interrupted: the partial content, the staging entry, and the
 * fact that the previous target file is still intact.
 */
class FaultInjectingBackend(
    private val delegate: RemoteBackend,
    var mode: Mode = Mode.NONE,
    var failAfterChunks: Int = -1,
) : RemoteBackend by delegate {

    enum class Mode { NONE, READ, WRITE }

    var injectedFailures: Int = 0
        private set

    /** Fails during reads once [count] chunks have been delivered. */
    fun failAfterRead(count: Int): FaultInjectingBackend = apply {
        mode = Mode.READ
        failAfterChunks = count
    }

    /** Fails during writes once [count] chunks have been stored. */
    fun failAfterWrite(count: Int): FaultInjectingBackend = apply {
        mode = Mode.WRITE
        failAfterChunks = count
    }

    fun heal() {
        mode = Mode.NONE
        failAfterChunks = -1
    }

    override suspend fun openRead(root: RootRef, relPath: String, offset: Long, length: Long): ReadStream {
        val inner = delegate.openRead(root, relPath, offset, length)
        if (mode != Mode.READ) return inner
        return CountingReadStream(inner)
    }

    override suspend fun openWrite(
        root: RootRef,
        relPath: String,
        size: Long,
        mtimeMs: Long,
        resume: Boolean,
    ): WriteStream {
        val inner = delegate.openWrite(root, relPath, size, mtimeMs, resume)
        if (mode != Mode.WRITE) return inner
        return CountingWriteStream(inner)
    }

    private inner class CountingReadStream(private val inner: ReadStream) : ReadStream by inner {
        private var delivered = 0

        override suspend fun read(maxBytes: Int): Chunk? {
            if (failAfterChunks in 0..delivered) {
                injectedFailures++
                throw BackendException(ErrorCode.IO_ERROR, "injected read failure after $delivered chunk(s)")
            }
            val chunk = inner.read(maxBytes) ?: return null
            delivered++
            return chunk
        }
    }

    private inner class CountingWriteStream(private val inner: WriteStream) : WriteStream by inner {
        private var stored = 0

        override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (failAfterChunks in 0..stored) {
                injectedFailures++
                throw BackendException(ErrorCode.IO_ERROR, "injected write failure after $stored chunk(s)")
            }
            inner.write(buffer, offset, length)
            stored++
        }
    }
}
