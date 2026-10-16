package io.github.cubsync.service

import io.github.cubsync.core.protocol.ErrorCode
import io.github.cubsync.core.spi.BackendException
import io.github.cubsync.core.spi.FileMeta
import io.github.cubsync.core.spi.RemoteBackend
import io.github.cubsync.core.spi.RootRef
import io.github.cubsync.core.spi.SimpleRoot
import io.github.cubsync.core.transfer.ChunkPlan

/** What an ad-hoc copy did. */
data class TransferOutcome(
    val copied: Int = 0,
    val bytes: Long = 0,
    val failures: List<String> = emptyList(),
) {
    val ok: Boolean get() = failures.isEmpty()
}

/**
 * Listing a folder and copying between two of them, without a configuration.
 *
 * This is the two-pane browser's engine: the same backends the synchroniser uses, the
 * same write contract -- open, write in order, commit, and only then apply the
 * timestamp -- but driven by a selection instead of by a comparison. It exists
 * because "copy these four files to the other pane" is not a synchronisation and
 * should not have to be expressed as one: building a temporary configuration with a
 * filter that happens to match the selection is how you end up copying a file nobody
 * selected.
 */
class FolderBrowser(private val pool: BackendPool, private val log: AppLog = AppLog()) {

    /** The entries directly inside [path] on one side. */
    suspend fun list(backendId: String, path: String): List<FileMeta> {
        val backend = pool.resolve(backendId)
        if (path.isBlank()) return backend.roots().map { root ->
            FileMeta(relPath = root.path, isDirectory = true)
        }
        return backend.list(SimpleRoot(backendId, path), "", recursive = false)
            .sortedBy { it.relPath.lowercase() }
    }

    /**
     * Copies the named entries from one folder to another, folders included.
     *
     * Names are relative to the folder they are in, which is what a pane has: a
     * selection is a set of rows, and a row is a name.
     */
    suspend fun copy(
        fromBackendId: String,
        fromDirectory: String,
        toBackendId: String,
        toDirectory: String,
        names: List<String>,
        onProgress: (Long) -> Unit = {},
    ): TransferOutcome {
        if (names.isEmpty()) return TransferOutcome()
        if (fromBackendId == toBackendId && fromDirectory == toDirectory) {
            return TransferOutcome(failures = listOf("源文件夹和目标文件夹是同一个"))
        }

        val source = pool.resolve(fromBackendId)
        val target = pool.resolve(toBackendId)
        val sourceRoot = SimpleRoot(fromBackendId, fromDirectory)
        val targetRoot = SimpleRoot(toBackendId, toDirectory)

        val wanted = names.map { it.trim('/') }.filter { it.isNotEmpty() }
        val tree = source.list(sourceRoot, "", recursive = true)
            .filter { meta -> wanted.any { name -> meta.relPath == name || meta.relPath.startsWith("$name/") } }

        var copied = 0
        var bytes = 0L
        val failures = ArrayList<String>()

        try {
            // Folders before the files going into them: a copy must not depend on a
            // backend creating a parent chain implicitly.
            for (meta in tree.filter { it.isDirectory }) {
                runCatching { target.mkdir(targetRoot, meta.relPath, createParents = true) }
                    .onFailure { failures.add("${meta.relPath}: ${it.message}") }
            }
            for (meta in tree.filterNot { it.isDirectory }) {
                try {
                    bytes += copyFile(source, sourceRoot, target, targetRoot, meta, onProgress)
                    copied++
                } catch (failure: Throwable) {
                    failures.add("${meta.relPath}: ${failure.message ?: failure::class.simpleName}")
                }
            }
        } catch (failure: Throwable) {
            failures.add(failure.message ?: "列出源文件夹失败")
        }

        log.info(
            "browse",
            if (failures.isEmpty()) "copied $copied item(s)" else "copied $copied item(s), ${failures.size} failed",
            "$fromDirectory -> $toDirectory",
        )
        return TransferOutcome(copied = copied, bytes = bytes, failures = failures)
    }

    private suspend fun copyFile(
        source: RemoteBackend,
        sourceRoot: RootRef,
        target: RemoteBackend,
        targetRoot: RootRef,
        meta: FileMeta,
        onProgress: (Long) -> Unit,
    ): Long {
        val reader = source.openRead(sourceRoot, meta.relPath, offset = 0, length = meta.size)
        try {
            val writer = target.openWrite(
                root = targetRoot,
                relPath = meta.relPath,
                size = meta.size,
                mtimeMs = meta.mtimeMs,
                resume = false,
            )
            try {
                var written = 0L
                while (true) {
                    val chunk = reader.read(ChunkPlan.DEFAULT_CHUNK_SIZE) ?: break
                    writer.write(chunk.data, 0, chunk.length)
                    written += chunk.length
                    onProgress(chunk.length.toLong())
                }
                writer.commit(meta.mtimeMs)
                return written
            } catch (failure: Throwable) {
                runCatching { writer.abort() }
                throw failure
            } finally {
                runCatching { writer.close() }
            }
        } finally {
            runCatching { reader.close() }
        }
    }

    /** True when this side can be browsed right now; a disconnected peer cannot. */
    suspend fun reachable(backendId: String): Boolean = try {
        pool.resolve(backendId)
        true
    } catch (failure: BackendException) {
        if (failure.code == ErrorCode.INTERNAL) false else true
    } catch (failure: Throwable) {
        log.debug("browse", "cannot reach $backendId: ${failure.message}")
        false
    }
}
