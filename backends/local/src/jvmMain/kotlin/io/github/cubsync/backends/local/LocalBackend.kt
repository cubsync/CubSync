package io.github.cubsync.backends.local

import io.github.cubsync.core.protocol.Capabilities
import io.github.cubsync.core.protocol.ErrorCode
import io.github.cubsync.core.spi.BackendException
import io.github.cubsync.core.spi.Chunk
import io.github.cubsync.core.spi.FileMeta
import io.github.cubsync.core.spi.ReadStream
import io.github.cubsync.core.spi.RemoteBackend
import io.github.cubsync.core.spi.RootRef
import io.github.cubsync.core.spi.SimpleRoot
import io.github.cubsync.core.spi.WriteStream
import io.github.cubsync.core.util.Algorithms
import io.github.cubsync.core.util.newDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

/**
 * The local filesystem as a [RemoteBackend].
 *
 * This is the reference implementation of the SPI: every other backend is
 * expected to behave the way this one does, and the engine's tests run against
 * it because it is the only implementation with real durability semantics.
 *
 * Two things here are specific to real files and have to be handled explicitly:
 *
 * * **Staging.** A write goes to `<name>.cubtmp` next to the target and is moved
 *   into place with an atomic rename. The staging file is deterministic rather
 *   than random so that an interrupted transfer can be resumed: the next attempt
 *   finds it, sees how many bytes are already durable, and continues from there.
 * * **Ordering.** `commit()` makes the data durable, renames, and only then sets
 *   the timestamp. A rename that lands before the data is on disk can leave a
 *   truncated file behind under the correct name after a crash, which is worse
 *   than a leftover staging file.
 */
class LocalBackend(
    override val id: String = "local",
    override val displayName: String = "This computer",
) : RemoteBackend {

    private val registeredRoots = LinkedHashSet<String>()

    /**
     * Windows and macOS match path case insensitively. This is decided per
     * filesystem in a perfect world; per platform is what the rest of the system
     * needs in order to treat `Report.txt` and `report.txt` as a conflict rather
     * than as two files.
     */
    val caseSensitive: Boolean = run {
        val os = System.getProperty("os.name")?.lowercase() ?: ""
        !(os.contains("windows") || os.contains("mac"))
    }

    override val capabilities: Long = Capabilities.LIST or Capabilities.STAT or Capabilities.HASH or
        Capabilities.MKDIR or Capabilities.DELETE or Capabilities.RENAME or Capabilities.SET_MTIME or
        Capabilities.FREE_SPACE or Capabilities.RANDOM_WRITE or Capabilities.RESUME or Capabilities.BATCH_HASH or
        Capabilities.TREE_AT_ONCE or Capabilities.SYMLINK or Capabilities.PERMISSIONS or Capabilities.TRASH

    /**
     * Declares a folder this backend may serve.
     *
     * Stored with forward slashes because the same string travels over the protocol
     * and is written into a configuration. A root that comes back as `D:\photos`
     * from one machine and `D:/photos` from an address box would otherwise look
     * like two different folders to everything that compares them.
     */
    fun registerRoot(path: String): LocalBackend = apply {
        val absolute = Paths.get(path).toAbsolutePath().normalize().toString()
        registeredRoots.add(absolute.replace('\\', '/'))
    }

    override suspend fun roots(): List<RootRef> = registeredRoots.map { SimpleRoot(id, it) }

    override suspend fun list(root: RootRef, relPath: String, recursive: Boolean): List<FileMeta> =
        withContext(Dispatchers.IO) {
            val base = resolve(root, relPath)
            if (!Files.exists(base, LinkOption.NOFOLLOW_LINKS)) {
                throw BackendException(ErrorCode.NOT_FOUND, "no such folder: ${root.path}/$relPath")
            }
            if (!Files.isDirectory(base)) {
                throw BackendException(ErrorCode.BAD_REQUEST, "not a folder: ${root.path}/$relPath")
            }
            val out = ArrayList<FileMeta>(256)
            if (recursive) {
                Files.walkFileTree(
                    base,
                    object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                            val relative = relative(base, dir)
                            if (relative.isEmpty()) return FileVisitResult.CONTINUE
                            if (isInternal(relative)) return FileVisitResult.SKIP_SUBTREE
                            out.add(meta(relative, dir, attrs, isDirectory = true))
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                            val relative = relative(base, file)
                            if (isInternal(relative)) return FileVisitResult.CONTINUE
                            out.add(meta(relative, file, attrs, isDirectory = false))
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                            FileVisitResult.CONTINUE
                    },
                )
            } else {
                Files.newDirectoryStream(base).use { stream ->
                    for (entry in stream) {
                        val relative = relative(base, entry)
                        if (isInternal(relative)) continue
                        val attrs = Files.readAttributes(
                            entry,
                            BasicFileAttributes::class.java,
                            LinkOption.NOFOLLOW_LINKS,
                        )
                        out.add(meta(relative, entry, attrs, attrs.isDirectory))
                    }
                }
            }
            out.sortBy { it.relPath }
            out
        }

    override suspend fun stat(root: RootRef, relPath: String): FileMeta? = withContext(Dispatchers.IO) {
        val path = resolve(root, relPath)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return@withContext null
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        meta(normalize(relPath), path, attrs, attrs.isDirectory)
    }

    override suspend fun hash(root: RootRef, relPath: String, algorithm: String): String = withContext(Dispatchers.IO) {
        val path = resolve(root, relPath)
        if (!Files.isRegularFile(path)) {
            throw BackendException(ErrorCode.NOT_FOUND, "no such file: ${root.path}/$relPath")
        }
        val digest = newDigest(Algorithms.require(algorithm))
        val buffer = ByteArray(HASH_BUFFER_SIZE)
        Files.newInputStream(path).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.hex()
    }

    override suspend fun mkdir(root: RootRef, relPath: String, createParents: Boolean) = withContext(Dispatchers.IO) {
        val path = resolve(root, relPath)
        try {
            if (createParents) {
                Files.createDirectories(path)
            } else {
                Files.createDirectory(path)
            }
        } catch (failure: java.nio.file.FileAlreadyExistsException) {
            if (!createParents) throw BackendException(ErrorCode.ALREADY_EXISTS, "$relPath already exists", failure)
        } catch (failure: IOException) {
            throw BackendException(ErrorCode.IO_ERROR, "cannot create folder $relPath: ${failure.message}", failure)
        }
        Unit
    }

    override suspend fun delete(root: RootRef, relPath: String, toTrash: Boolean) = withContext(Dispatchers.IO) {
        val path = resolve(root, relPath)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw BackendException(ErrorCode.NOT_FOUND, "no such entry: $relPath")
        }
        if (toTrash) {
            val trash = resolve(root, trashRelativeTo(relPath))
            Files.createDirectories(trash.parent ?: trash)
            move(path, trash)
            return@withContext
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.walkFileTree(
                path,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        Files.deleteIfExists(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        } else {
            Files.deleteIfExists(path)
        }
        Unit
    }

    override suspend fun rename(root: RootRef, fromRel: String, toRel: String) = withContext(Dispatchers.IO) {
        val from = resolve(root, fromRel)
        val to = resolve(root, toRel)
        if (!Files.exists(from, LinkOption.NOFOLLOW_LINKS)) {
            throw BackendException(ErrorCode.NOT_FOUND, "no such entry: $fromRel")
        }
        Files.createDirectories(to.parent ?: to)
        move(from, to)
    }

    override suspend fun setMtime(root: RootRef, relPath: String, mtimeMs: Long) = withContext(Dispatchers.IO) {
        val path = resolve(root, relPath)
        try {
            Files.setLastModifiedTime(path, FileTime.fromMillis(mtimeMs))
        } catch (failure: IOException) {
            throw BackendException(ErrorCode.IO_ERROR, "cannot set the timestamp of $relPath: ${failure.message}", failure)
        }
        Unit
    }

    override suspend fun freeSpace(root: RootRef): Long = withContext(Dispatchers.IO) {
        val path = resolve(root, "")
        try {
            Files.getFileStore(path).usableSpace
        } catch (failure: IOException) {
            throw BackendException(ErrorCode.IO_ERROR, "cannot read free space for ${root.path}", failure)
        }
    }

    override suspend fun openRead(root: RootRef, relPath: String, offset: Long, length: Long): ReadStream =
        withContext(Dispatchers.IO) {
            val path = resolve(root, relPath)
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw BackendException(ErrorCode.NOT_FOUND, "no such file: ${root.path}/$relPath")
            }
            try {
                val size = Files.size(path)
                if (offset > size) {
                    throw BackendException(
                        ErrorCode.RESUME_MISMATCH,
                        "offset $offset is past the end of $relPath ($size)",
                    )
                }
                val end = if (length < 0) size else minOf(size, offset + length)
                val channel = FileChannel.open(path, StandardOpenOption.READ)
                channel.position(offset)
                FileReadStream(relPath, channel, offset, end)
            } catch (failure: IOException) {
                // A raw IOException would escape the SPI and abort a whole pair;
                // every failure has to arrive as a coded error instead.
                throw BackendException(ErrorCode.IO_ERROR, "cannot read $relPath: ${failure.message}", failure)
            }
        }

    override suspend fun openWrite(
        root: RootRef,
        relPath: String,
        size: Long,
        mtimeMs: Long,
        resume: Boolean,
    ): WriteStream = withContext(Dispatchers.IO) {
        val target = resolve(root, relPath)
        val staging = target.resolveSibling(target.fileName.toString() + STAGING_SUFFIX)
        try {
            Files.createDirectories(target.parent ?: target)

            var startOffset = 0L
            if (resume && Files.isRegularFile(staging)) {
                startOffset = Files.size(staging)
                if (startOffset > size) {
                    // The staging file is bigger than what is being written: it
                    // belongs to a different, longer file, so starting over is the
                    // only safe move.
                    Files.deleteIfExists(staging)
                    startOffset = 0
                }
            } else {
                Files.deleteIfExists(staging)
            }

            val channel = FileChannel.open(staging, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            channel.position(startOffset)
            FileWriteStream(relPath, channel, staging, target, startOffset, mtimeMs)
        } catch (failure: IOException) {
            throw BackendException(ErrorCode.IO_ERROR, "cannot write $relPath: ${failure.message}", failure)
        }
    }

    override fun close() = Unit

    // -- internals -----------------------------------------------------------

    private fun meta(relative: String, path: Path, attrs: BasicFileAttributes, isDirectory: Boolean) = FileMeta(
        relPath = relative.replace('\\', '/'),
        size = if (isDirectory) 0 else attrs.size(),
        mtimeMs = attrs.lastModifiedTime().toMillis(),
        isDirectory = isDirectory,
        symlink = Files.isSymbolicLink(path),
    )

    private fun relative(base: Path, path: Path): String = base.relativize(path).toString().replace('\\', '/')

    /** Staging files and the trash are implementation detail, not user content. */
    private fun isInternal(relative: String): Boolean {
        if (relative == TRASH_DIR || relative.startsWith("$TRASH_DIR/")) return true
        return relative.substringAfterLast('/').endsWith(STAGING_SUFFIX)
    }

    private fun trashRelativeTo(relPath: String): String {
        val stamp = System.currentTimeMillis()
        return "$TRASH_DIR/adhoc/$stamp/${normalize(relPath)}"
    }

    private fun move(from: Path, to: Path) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (notSupported: java.nio.file.AtomicMoveNotSupportedException) {
            try {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
            } catch (failure: IOException) {
                throw BackendException(ErrorCode.IO_ERROR, "cannot move $from to $to: ${failure.message}", failure)
            }
        } catch (failure: IOException) {
            throw BackendException(ErrorCode.IO_ERROR, "cannot move $from to $to: ${failure.message}", failure)
        }
    }

    /**
     * Resolves a path inside a root and refuses anything that would leave it.
     *
     * The check is on the normalised result rather than on the text of the input,
     * so `a/../../etc` is caught as reliably as `..`.
     */
    private fun resolve(root: RootRef, relPath: String): Path {
        val rootPath = Paths.get(root.path).toAbsolutePath().normalize()
        val normalized = normalize(relPath)
        if (normalized.startsWith("/")) {
            throw BackendException(ErrorCode.PATH_OUTSIDE_ROOT, "$relPath must be relative to the root")
        }
        val resolved = if (normalized.isEmpty()) rootPath else rootPath.resolve(normalized).normalize()
        if (resolved != rootPath && !resolved.startsWith(rootPath)) {
            throw BackendException(ErrorCode.PATH_OUTSIDE_ROOT, "$relPath escapes ${root.path}")
        }
        return resolved
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
                ".." -> {
                    if (parts.isEmpty()) {
                        throw BackendException(ErrorCode.PATH_OUTSIDE_ROOT, "path escapes the root: $path")
                    }
                    parts.removeAt(parts.size - 1)
                }

                else -> parts.add(part)
            }
        }
        val body = parts.joinToString("/")
        return if (absolute) "/$body" else body
    }

    private class FileReadStream(
        private val relPath: String,
        private val channel: FileChannel,
        override val startOffset: Long,
        private val end: Long,
    ) : ReadStream {

        override val size: Long get() = end - startOffset
        override val chunkSize: Int = DEFAULT_CHUNK

        override suspend fun read(maxBytes: Int): Chunk? {
            val position = channel.position()
            if (position >= end) return null
            val length = minOf(maxBytes.toLong(), end - position).toInt()
            val buffer = ByteBuffer.allocate(length)
            val read = channel.read(buffer)
            if (read <= 0) return null
            return Chunk(position, buffer.array(), read)
        }

        override fun close() {
            runCatching { channel.close() }
        }
    }

    private class FileWriteStream(
        private val relPath: String,
        private val channel: FileChannel,
        private val staging: Path,
        private val target: Path,
        override val startOffset: Long,
        private val declaredMtimeMs: Long,
    ) : WriteStream {

        override val handle: String = relPath
        override val chunkSize: Int = DEFAULT_CHUNK

        private var closed = false

        override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
            val byteBuffer = ByteBuffer.wrap(buffer, offset, length)
            while (byteBuffer.hasRemaining()) channel.write(byteBuffer)
        }

        /**
         * Data first, then the rename, then the timestamp.
         *
         * The timestamp goes last because the target only becomes visible at the
         * rename, and a timestamp applied to the staging file would be carried over
         * by the rename anyway — doing it last makes the intent explicit and keeps
         * the earlier state correct if anything in between fails.
         */
        override suspend fun commit(mtimeMs: Long) {
            if (!channel.isOpen) return
            channel.force(true)
            channel.close()
            closed = true
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (notSupported: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING)
            }
            val effective = if (mtimeMs > 0) mtimeMs else declaredMtimeMs
            if (effective > 0) {
                runCatching { Files.setLastModifiedTime(target, FileTime.fromMillis(effective)) }
            }
        }

        override suspend fun abort() {
            if (channel.isOpen) channel.close()
            closed = true
            Files.deleteIfExists(staging)
        }

        override fun close() {
            if (!closed && channel.isOpen) channel.close()
        }
    }

    companion object {
        const val STAGING_SUFFIX: String = ".cubtmp"
        const val TRASH_DIR: String = ".cub_trash"

        private const val DEFAULT_CHUNK: Int = 256 * 1024
        private const val HASH_BUFFER_SIZE: Int = 128 * 1024
    }
}
