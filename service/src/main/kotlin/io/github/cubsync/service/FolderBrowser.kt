package io.github.cubsync.service

import io.github.cubsync.core.protocol.ErrorCode
import io.github.cubsync.core.spi.BackendException
import io.github.cubsync.core.spi.FileMeta
import io.github.cubsync.core.spi.RemoteBackend
import io.github.cubsync.core.spi.RootRef
import io.github.cubsync.core.engine.PauseGate
import io.github.cubsync.core.engine.RunCancelled
import io.github.cubsync.core.spi.SimpleRoot
import io.github.cubsync.core.transfer.ChunkPlan

/** What an ad-hoc copy did. */
data class TransferOutcome(
    val copied: Int = 0,
    val bytes: Long = 0,
    val failures: List<String> = emptyList(),
    val cancelled: Boolean = false,
) {
    /** Whether anything went wrong. A cancel is not a failure — it is what was asked for. */
    val ok: Boolean get() = failures.isEmpty()
}

/** How far a copy has got, for the bar that draws it. */
data class TransferProgress(
    val totalBytes: Long,
    val doneBytes: Long,
    val totalFiles: Int = 0,
    val paused: Boolean = false,
) {
    val fraction: Float get() = if (totalBytes <= 0L) 0f else (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    val percent: Int get() = (fraction * 100f).toInt()
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
        control: PauseGate = PauseGate(),
        onTotal: (bytes: Long, files: Int) -> Unit = { _, _ -> },
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
        val files = tree.filterNot { it.isDirectory }
        // The bar has to know the denominator before the first byte moves, or it shows a
        // percentage of something it cannot see.
        onTotal(files.sumOf { it.size }, files.size)

        var copied = 0
        var bytes = 0L
        var cancelled = false
        val failures = ArrayList<String>()

        try {
            // Folders before the files going into them: a copy must not depend on a
            // backend creating a parent chain implicitly.
            for (meta in tree.filter { it.isDirectory }) {
                runCatching { target.mkdir(targetRoot, meta.relPath, createParents = true) }
                    .onFailure { failures.add("${meta.relPath}: ${it.message}") }
            }
            for (meta in files) {
                try {
                    bytes += copyFile(source, sourceRoot, target, targetRoot, meta, control, onProgress)
                    copied++
                } catch (stop: RunCancelled) {
                    cancelled = true
                    break
                } catch (failure: Throwable) {
                    failures.add("${meta.relPath}: ${failure.message ?: failure::class.simpleName}")
                }
            }
        } catch (failure: Throwable) {
            failures.add(failure.message ?: "列出源文件夹失败")
        }

        log.info(
            "browse",
            when {
                cancelled -> "copied $copied item(s), then cancelled"
                failures.isEmpty() -> "copied $copied item(s)"
                else -> "copied $copied item(s), ${failures.size} failed"
            },
            "$fromDirectory -> $toDirectory",
        )
        return TransferOutcome(copied = copied, bytes = bytes, failures = failures, cancelled = cancelled)
    }

    private suspend fun copyFile(
        source: RemoteBackend,
        sourceRoot: RootRef,
        target: RemoteBackend,
        targetRoot: RootRef,
        meta: FileMeta,
        control: PauseGate,
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
                    control.awaitGo()
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

    // ---------------------------------------------------------------- editing

    /**
     * Creates a folder and returns its path.
     *
     * Two things about this are deliberate. A name that is taken gets a suffix rather
     * than an error — the button says "new folder", and refusing because a folder called
     * 新建文件夹 exists is a conversation nobody wants to have with a file manager. And
     * the creation is attempted for real, with the collision surfacing as an exception
     * rather than being pre-empted by a `createDirectories` that quietly succeeds when
     * the folder is already there: pressing the button twice quickly used to produce one
     * folder and a second press that did nothing at all.
     */
    suspend fun makeDirectory(backendId: String, directory: String, wanted: String): String {
        require(directory.isNotBlank()) { "先打开一个文件夹" }
        val backend = pool.resolve(backendId)
        val root = SimpleRoot(backendId, directory)

        var attempt = 0
        while (true) {
            val existing = runCatching {
                backend.list(root, "", recursive = false).map { PathTools.leafName(it.relPath) }.toSet()
            }.getOrDefault(emptySet())
            val name = Browsing.uniqueName(existing, wanted)
            try {
                backend.mkdir(root, name, createParents = false)
                log.info("browse", "created folder $name", directory)
                return Browsing.child(directory, name)
            } catch (failure: BackendException) {
                // Someone created that name between the listing and the call. Looking
                // again is the whole answer; a handful of tries is more than enough,
                // because each one competes with a human pressing a button.
                if (failure.code != ErrorCode.ALREADY_EXISTS || ++attempt >= 8) throw failure
            }
        }
    }

    /** Renames one entry inside [directory]. */
    suspend fun rename(backendId: String, directory: String, from: String, to: String) {
        val clean = to.trim().trim('/')
        require(clean.isNotEmpty()) { "名字不能为空" }
        val backend = pool.resolve(backendId)
        backend.rename(SimpleRoot(backendId, directory), from, clean)
        log.info("browse", "renamed $from to $clean", directory)
    }

    /**
     * Deletes entries, to the backend's trash by default.
     *
     * `toTrash` defaults to true and the caller has to say otherwise on purpose. The
     * two-pane browser is where somebody is moving things around in a hurry, and the
     * difference between recoverable and not is the whole value of this parameter.
     */
    suspend fun delete(
        backendId: String,
        directory: String,
        names: List<String>,
        toTrash: Boolean = true,
    ): List<String> {
        val backend = pool.resolve(backendId)
        val root = SimpleRoot(backendId, directory)
        val failures = ArrayList<String>()
        for (name in names) {
            runCatching { backend.delete(root, name, toTrash = toTrash) }
                .onFailure { failures.add("$name: ${it.message ?: "删除失败"}") }
        }
        log.info(
            "browse",
            if (failures.isEmpty()) "deleted ${names.size} item(s)" else "deleted ${names.size - failures.size} item(s), ${failures.size} failed",
            if (toTrash) "to trash" else "permanently",
        )
        return failures
    }

    /** Metadata for one entry, for the detail sheet. */
    suspend fun stat(backendId: String, directory: String, name: String): FileMeta? = runCatching {
        pool.resolve(backendId).stat(SimpleRoot(backendId, directory), name)
    }.getOrNull()

    /** Whether this exact path is a folder right now, used by the address box. */
    suspend fun isFolder(backendId: String, path: String): Boolean = runCatching {
        if (path.isBlank()) return@runCatching true
        pool.resolve(backendId).stat(SimpleRoot(backendId, Browsing.parent(path)), PathTools.leafName(path))
            ?.isDirectory ?: false
    }.getOrDefault(false)

    /** How much room is left, so a transfer can say no before it starts. */
    suspend fun freeSpace(backendId: String, directory: String): Long = runCatching {
        pool.resolve(backendId).freeSpace(SimpleRoot(backendId, directory))
    }.getOrDefault(-1L)
}
