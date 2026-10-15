package io.github.cubsync.testing

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

/**
 * A complete in-memory [RemoteBackend].
 *
 * A second implementation of the SPI that shares no code with the local one is
 * what turns "the engine drives the SPI correctly" from a claim into a test: the
 * engine is exercised against listing, seek and rename semantics written
 * independently of the filesystem.
 *
 * Writes are staged the way a real backend stages them, so a resumed transfer
 * finds its partial content where it left it — a partial file the engine can
 * resume from has to look partial here too, or the resume path would only ever be
 * tested against a real disk.
 */
class MemoryBackend(
    override val id: String = "memory",
    override val displayName: String = "Memory",
    private val rootPaths: List<String> = listOf("/"),
) : RemoteBackend {

    private class Node(
        var directory: Boolean,
        var bytes: ByteArray? = null,
        var mtimeMs: Long = 0,
        var symlink: Boolean = false,
        var mode: Int = 0,
    )

    private val nodes = LinkedHashMap<String, Node>()

    var listCalls: Int = 0
        private set
    var hashCalls: Int = 0
        private set
    var bytesWritten: Long = 0
        private set

    /** Consulted on each chunk; lets a test fail at an exact point of a transfer. */
    var readHook: (suspend (String, Int) -> Unit)? = null
    var writeHook: (suspend (String, Int) -> Unit)? = null

    init {
        for (root in rootPaths) nodes[normalize(root)] = Node(directory = true)
    }

    override val capabilities: Long = Capabilities.LIST or Capabilities.STAT or Capabilities.HASH or
        Capabilities.MKDIR or Capabilities.DELETE or Capabilities.RENAME or Capabilities.SET_MTIME or
        Capabilities.FREE_SPACE or Capabilities.RANDOM_WRITE or Capabilities.RESUME or
        Capabilities.BATCH_HASH or Capabilities.SYMLINK or Capabilities.PERMISSIONS

    override suspend fun roots(): List<RootRef> = rootPaths.map { SimpleRoot(id, it) }

    // -- test helpers --------------------------------------------------------

    fun putFile(rootPath: String, relPath: String, content: ByteArray, mtimeMs: Long = 1_000) {
        val full = join(rootPath, relPath)
        ensureParents(full)
        nodes[full] = Node(directory = false, bytes = content.copyOf(), mtimeMs = mtimeMs)
    }

    fun putFile(rootPath: String, relPath: String, text: String, mtimeMs: Long = 1_000) =
        putFile(rootPath, relPath, text.encodeToByteArray(), mtimeMs)

    fun putDirectory(rootPath: String, relPath: String) {
        val full = join(rootPath, relPath)
        ensureParents(full)
        nodes[full] = Node(directory = true)
    }

    /** Simulates a transfer that was interrupted: only the staging entry exists. */
    fun putStagedFile(rootPath: String, relPath: String, content: ByteArray) {
        val full = join(rootPath, relPath) + STAGING_SUFFIX
        ensureParents(full)
        nodes[full] = Node(directory = false, bytes = content.copyOf(), mtimeMs = 0)
    }

    fun readFile(rootPath: String, relPath: String): ByteArray? = nodes[join(rootPath, relPath)]?.bytes?.copyOf()

    fun readText(rootPath: String, relPath: String): String? = readFile(rootPath, relPath)?.decodeToString()

    fun exists(rootPath: String, relPath: String): Boolean = nodes.containsKey(join(rootPath, relPath))

    fun existsStaged(rootPath: String, relPath: String): Boolean =
        nodes.containsKey(join(rootPath, relPath) + STAGING_SUFFIX)

    fun stagedSize(rootPath: String, relPath: String): Long =
        nodes[join(rootPath, relPath) + STAGING_SUFFIX]?.bytes?.size?.toLong() ?: -1L

    /** Every known path below [prefix], for assertions about what a run left behind. */
    fun paths(rootPath: String, prefix: String = ""): List<String> {
        val base = join(rootPath, prefix)
        val root = normalize(rootPath)
        return nodes.keys
            .filter { it == base || it.startsWith("$base/") }
            .map { it.removePrefix(root).trimStart('/') }
            .sorted()
    }

    // -- SPI -----------------------------------------------------------------

    override suspend fun list(root: RootRef, relPath: String, recursive: Boolean): List<FileMeta> {
        listCalls++
        val base = join(root.path, relPath)
        val baseNode = nodes[base] ?: throw notFound(relPath.ifEmpty { root.path })
        if (!baseNode.directory) throw BackendException(ErrorCode.BAD_REQUEST, "not a folder: $relPath")
        val out = ArrayList<FileMeta>(nodes.size)
        for ((path, node) in nodes) {
            if (path == base) continue
            if (!path.startsWith("$base/")) continue
            val relative = path.removePrefix("$base/")
            // Staging entries are the backend's own bookkeeping, not user content.
            // A real backend does not list them either, and if this one did the
            // engine would faithfully try to synchronise a half-written file.
            if (relative.substringAfterLast('/').endsWith(STAGING_SUFFIX)) continue
            if (!recursive && relative.contains('/')) continue
            out.add(meta(relative, node))
        }
        return out.sortedBy { it.relPath }
    }

    override suspend fun stat(root: RootRef, relPath: String): FileMeta? {
        val node = nodes[join(root.path, relPath)] ?: return null
        return meta(normalize(relPath), node)
    }

    override suspend fun hash(root: RootRef, relPath: String, algorithm: String): String {
        hashCalls++
        val node = nodes[join(root.path, relPath)] ?: throw notFound(relPath)
        if (node.directory) throw BackendException(ErrorCode.BAD_REQUEST, "$relPath is a directory")
        val digest = newDigest(Algorithms.require(algorithm))
        digest.update(node.bytes ?: ByteArray(0))
        return digest.hex()
    }

    override suspend fun mkdir(root: RootRef, relPath: String, createParents: Boolean) {
        val full = join(root.path, relPath)
        if (nodes.containsKey(full)) {
            if (createParents) return
            throw BackendException(ErrorCode.ALREADY_EXISTS, "$relPath already exists")
        }
        val parent = full.substringBeforeLast('/', "")
        if (parent.isNotEmpty() && !nodes.containsKey(parent)) {
            if (!createParents) throw notFound(parent)
        }
        if (createParents) ensureParents(full)
        nodes[full] = Node(directory = true)
    }

    override suspend fun delete(root: RootRef, relPath: String, toTrash: Boolean) {
        val full = join(root.path, relPath)
        if (!nodes.containsKey(full)) throw notFound(relPath)
        nodes.keys.filter { it == full || it.startsWith("$full/") }.forEach { nodes.remove(it) }
    }

    override suspend fun rename(root: RootRef, fromRel: String, toRel: String) {
        val from = join(root.path, fromRel)
        val to = join(root.path, toRel)
        if (!nodes.containsKey(from)) throw notFound(fromRel)
        val moved = nodes.entries.filter { it.key == from || it.key.startsWith("$from/") }.map { it.key to it.value }
        ensureParents(to)
        for ((path, node) in moved) {
            nodes.remove(path)
            nodes[to + path.removePrefix(from)] = node
        }
    }

    override suspend fun setMtime(root: RootRef, relPath: String, mtimeMs: Long) {
        val node = nodes[join(root.path, relPath)] ?: throw notFound(relPath)
        node.mtimeMs = mtimeMs
    }

    override suspend fun freeSpace(root: RootRef): Long = 1L shl 40

    override suspend fun openRead(root: RootRef, relPath: String, offset: Long, length: Long): ReadStream {
        val node = nodes[join(root.path, relPath)] ?: throw notFound(relPath)
        if (node.directory) throw BackendException(ErrorCode.BAD_REQUEST, "$relPath is a directory")
        val bytes = node.bytes ?: ByteArray(0)
        if (offset > bytes.size) {
            throw BackendException(ErrorCode.RESUME_MISMATCH, "offset $offset is past the end of $relPath (${bytes.size})")
        }
        val end = if (length < 0) bytes.size.toLong() else minOf(bytes.size.toLong(), offset + length)
        return MemoryReadStream(relPath, bytes, offset, end) { index -> readHook?.invoke(relPath, index) }
    }

    override suspend fun openWrite(
        root: RootRef,
        relPath: String,
        size: Long,
        mtimeMs: Long,
        resume: Boolean,
    ): WriteStream {
        val full = join(root.path, relPath)
        if (nodes[full]?.directory == true) throw BackendException(ErrorCode.BAD_REQUEST, "$relPath is a directory")
        val stagingKey = "$full$STAGING_SUFFIX"
        val start = if (resume && nodes.containsKey(stagingKey)) nodes[stagingKey]!!.bytes!!.size.toLong() else 0L
        if (!resume) nodes.remove(stagingKey)
        ensureParents(stagingKey)
        if (!nodes.containsKey(stagingKey)) nodes[stagingKey] = Node(directory = false, bytes = ByteArray(0))
        return MemoryWriteStream(relPath, nodes, stagingKey, full, start, this) { index ->
            writeHook?.invoke(relPath, index)
        }
    }

    override fun close() = Unit

    // -- internals -----------------------------------------------------------

    private fun meta(relative: String, node: Node) = FileMeta(
        relPath = normalize(relative),
        size = node.bytes?.size?.toLong() ?: 0,
        mtimeMs = node.mtimeMs,
        isDirectory = node.directory,
        symlink = node.symlink,
        mode = node.mode,
    )

    private fun ensureParents(full: String) {
        var path = full.substringBeforeLast('/', "")
        val missing = ArrayList<String>()
        while (path.isNotEmpty() && !nodes.containsKey(path)) {
            missing.add(path)
            path = path.substringBeforeLast('/', "")
        }
        missing.asReversed().forEach { nodes[it] = Node(directory = true) }
    }

    private fun join(rootPath: String, relPath: String): String {
        val normalizedRoot = normalize(rootPath)
        val normalizedRel = normalize(relPath)
        if (normalizedRel.startsWith("/")) {
            throw BackendException(ErrorCode.PATH_OUTSIDE_ROOT, "$relPath must be relative to the root")
        }
        return if (normalizedRel.isEmpty()) normalizedRoot else "$normalizedRoot/$normalizedRel"
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

    private fun notFound(relPath: String) = BackendException(ErrorCode.NOT_FOUND, "no such entry: $relPath")

    private class MemoryReadStream(
        private val relPath: String,
        private val bytes: ByteArray,
        private val start: Long,
        private val end: Long,
        private val onChunk: suspend (Int) -> Unit,
    ) : ReadStream {
        private var position = start
        private var chunkIndex = 0

        override val size: Long get() = end - start
        override val startOffset: Long get() = start
        override val chunkSize: Int = DEFAULT_CHUNK

        override suspend fun read(maxBytes: Int): Chunk? {
            onChunk(chunkIndex)
            if (position >= end) return null
            val length = minOf(maxBytes.toLong(), end - position).toInt()
            val data = bytes.copyOfRange(position.toInt(), position.toInt() + length)
            position += length
            chunkIndex++
            return Chunk(position - length, data, length)
        }

        override fun close() = Unit
    }

    private class MemoryWriteStream(
        private val relPath: String,
        private val nodes: LinkedHashMap<String, Node>,
        private val stagingKey: String,
        private val finalKey: String,
        override val startOffset: Long,
        private val owner: MemoryBackend,
        private val onChunk: suspend (Int) -> Unit,
    ) : WriteStream {

        override val handle: String = relPath
        override val chunkSize: Int = DEFAULT_CHUNK

        private var appended = 0
        private var chunkIndex = 0

        override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
            onChunk(chunkIndex++)
            val node = nodes[stagingKey] ?: Node(directory = false, bytes = ByteArray(0)).also { nodes[stagingKey] = it }
            val existing = node.bytes ?: ByteArray(0)
            val grown = ByteArray(existing.size + length)
            existing.copyInto(grown)
            buffer.copyInto(grown, existing.size, offset, offset + length)
            node.bytes = grown
            appended += length
            owner.bytesWritten += length.toLong()
        }

        override suspend fun commit(mtimeMs: Long) {
            val node = nodes.remove(stagingKey) ?: throw BackendException(ErrorCode.INTERNAL, "staging entry vanished")
            nodes[finalKey] = Node(directory = false, bytes = node.bytes ?: ByteArray(0), mtimeMs = mtimeMs)
        }

        override suspend fun abort() {
            nodes.remove(stagingKey)
        }

        override fun close() = Unit
    }

    companion object {
        const val STAGING_SUFFIX: String = ".cubtmp"
        private const val DEFAULT_CHUNK: Int = 256 * 1024
    }
}
