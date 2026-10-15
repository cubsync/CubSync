package io.github.cubsync.core.spi

import io.github.cubsync.core.protocol.Capabilities
import io.github.cubsync.core.protocol.ErrorCode

/** Metadata of one entry, always relative to the backend root it was listed from. */
data class FileMeta(
    val relPath: String,
    val size: Long = 0,
    val mtimeMs: Long = 0,
    val isDirectory: Boolean = false,
    val symlink: Boolean = false,
    val mode: Int = 0,
)

/** A block of file content. [length] may be smaller than `data.size` at EOF. */
class Chunk(
    val offset: Long,
    val data: ByteArray,
    val length: Int = data.size,
) {
    val isEnd: Boolean get() = length < data.size
}

/**
 * Identifies a file tree a backend is allowed to touch.
 *
 * For a local backend [path] is a filesystem path. For a remote backend it is an
 * opaque handle the server handed out during the handshake — a client never sends
 * host paths it invented itself.
 */
interface RootRef {
    val backendId: String
    val path: String
}

data class SimpleRoot(
    override val backendId: String,
    override val path: String,
) : RootRef

/** Sequential reader positioned at [startOffset]; used for transfers and for hashing. */
interface ReadStream : AutoCloseable {
    val size: Long
    val startOffset: Long
    val chunkSize: Int

    /** Returns null once the stream is exhausted. */
    suspend fun read(maxBytes: Int): Chunk?

    override fun close()
}

/**
 * Sequential writer.
 *
 * [startOffset] is non-zero when resuming: everything before it is already
 * durable on disk and must not be rewritten. Nothing about the target becomes
 * visible to the other side until [commit] succeeds.
 */
interface WriteStream : AutoCloseable {
    val handle: String
    val startOffset: Long
    val chunkSize: Int

    suspend fun write(buffer: ByteArray, offset: Int, length: Int)

    /**
     * Flushes, writes the data durably, atomically moves the temporary file into
     * place, and *then* applies [mtimeMs]. The order matters: a rename that lands
     * before the data is durable can leave a truncated file behind after a crash.
     */
    suspend fun commit(mtimeMs: Long)

    /** Discards the temporary file, leaving any previous target untouched. */
    suspend fun abort()

    override fun close()
}

/** Failure carrying a protocol error code so it survives the wire unchanged. */
class BackendException(
    val code: Int,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

fun BackendException.toProtocolCode(): Int = code

/**
 * The single abstraction the synchronisation engine knows about.
 *
 * Local disk, the first-party LAN protocol, WebDAV, SMB, SFTP and FTP are all
 * just implementations. The engine decides what it may do from [capabilities],
 * never from the backend's identity.
 */
interface RemoteBackend : AutoCloseable {
    val id: String
    val displayName: String
    val capabilities: Long

    /** Trees this backend can serve. A local backend reports its configured roots. */
    suspend fun roots(): List<RootRef>

    suspend fun list(root: RootRef, relPath: String, recursive: Boolean): List<FileMeta>

    suspend fun stat(root: RootRef, relPath: String): FileMeta?

    suspend fun hash(root: RootRef, relPath: String, algorithm: String = ALGORITHM_MD5): String

    suspend fun mkdir(root: RootRef, relPath: String, createParents: Boolean = true)

    suspend fun delete(root: RootRef, relPath: String, toTrash: Boolean = false)

    suspend fun rename(root: RootRef, fromRel: String, toRel: String)

    suspend fun setMtime(root: RootRef, relPath: String, mtimeMs: Long)

    suspend fun freeSpace(root: RootRef): Long

    suspend fun openRead(root: RootRef, relPath: String, offset: Long = 0, length: Long = -1): ReadStream

    suspend fun openWrite(
        root: RootRef,
        relPath: String,
        size: Long,
        mtimeMs: Long = 0,
        resume: Boolean = true,
    ): WriteStream

    override fun close()

    companion object {
        const val ALGORITHM_MD5: String = "md5"
        const val ALGORITHM_SHA256: String = "sha256"

        /** Capabilities every backend must have for the engine to be able to copy at all. */
        const val MINIMUM: Long = Capabilities.LIST
    }
}

/** Convenience for tests and for backends that only implement part of the surface. */
abstract class ReadOnlyBackend : RemoteBackend {
    override val capabilities: Long get() = Capabilities.LIST or Capabilities.STAT or Capabilities.HASH
}

internal object SpiDefaults {
    const val NOT_IMPLEMENTED: Int = ErrorCode.INTERNAL
}
