package io.github.cubsync.core.engine

import io.github.cubsync.core.filter.PathMatcher
import io.github.cubsync.core.model.CompareMode
import io.github.cubsync.core.model.Filters
import io.github.cubsync.core.model.FolderPair
import io.github.cubsync.core.model.Side
import io.github.cubsync.core.model.SyncConfig
import io.github.cubsync.core.model.SyncDirection
import io.github.cubsync.core.protocol.Capabilities
import io.github.cubsync.core.protocol.ErrorCode
import io.github.cubsync.core.spi.BackendException
import io.github.cubsync.core.spi.Chunk
import io.github.cubsync.core.spi.FileMeta
import io.github.cubsync.core.spi.RemoteBackend
import io.github.cubsync.core.spi.RootRef
import io.github.cubsync.core.spi.SimpleRoot
import io.github.cubsync.core.spi.WriteStream
import io.github.cubsync.core.transfer.ChunkPlan
import io.github.cubsync.core.util.Algorithms
import io.github.cubsync.core.util.UtcClock
import io.github.cubsync.core.util.newDigest
import io.github.cubsync.core.util.systemUtcClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class JobStatus {
    COMPLETED,

    /** Some pairs finished and at least one did not; the finished work is kept. */
    PARTIAL_FAILED,
    FAILED,
    CANCELLED,
}

enum class PairStatus { COMPLETED, FAILED, BLOCKED, SKIPPED, DRY_RUN }

/** One file-level record, used for the job detail screen and for the run log. */
data class JobItem(
    val relPath: String,
    val action: ActionKind,
    val bytes: Long = 0,
    val ok: Boolean = true,
    val skippedReason: String = "",
    val error: String = "",
)

data class TransferStats(
    val copiedFiles: Int = 0,
    val copiedBytes: Long = 0,
    val deletedFiles: Int = 0,
    val conflicts: Int = 0,
    val skipped: Int = 0,
    val failures: Int = 0,
) {
    operator fun plus(other: TransferStats): TransferStats = TransferStats(
        copiedFiles = copiedFiles + other.copiedFiles,
        copiedBytes = copiedBytes + other.copiedBytes,
        deletedFiles = deletedFiles + other.deletedFiles,
        conflicts = conflicts + other.conflicts,
        skipped = skipped + other.skipped,
        failures = failures + other.failures,
    )
}

data class PairOutcome(
    val pairId: String,
    val pairName: String,
    val status: PairStatus,
    val items: List<JobItem>,
    val stats: TransferStats,
    val plan: PairPlan? = null,
    val error: String = "",
    val startedAtMs: Long = 0,
    val finishedAtMs: Long = 0,
) {
    val transferred: List<JobItem>
        get() = items.filter { it.ok && (it.action == ActionKind.COPY_LEFT_TO_RIGHT || it.action == ActionKind.COPY_RIGHT_TO_LEFT || it.action == ActionKind.DELETE_LEFT || it.action == ActionKind.DELETE_RIGHT) }

    val skippedItems: List<JobItem>
        get() = items.filter { it.skippedReason.isNotEmpty() || it.action == ActionKind.CONFLICT_SKIP }
}

data class JobResult(
    val jobId: String,
    val configId: String,
    val status: JobStatus,
    val pairs: List<PairOutcome>,
    val startedAtMs: Long,
    val finishedAtMs: Long,
) {
    val stats: TransferStats get() = pairs.fold(TransferStats()) { acc, pair -> acc + pair.stats }
    val durationMs: Long get() = finishedAtMs - startedAtMs
}

/** Progress sink. Every method has a default so a caller implements only what it shows. */
interface JobListener {
    fun onJobStarted(jobId: String, pairCount: Int) {}
    fun onPairStarted(pair: FolderPair) {}
    fun onPairPlanned(outcome: PairOutcome) {}
    fun onActionStarted(pairId: String, action: SyncAction) {}
    fun onActionFinished(pairId: String, item: JobItem) {}
    fun onBytesTransferred(pairId: String, relPath: String, delta: Long) {}
    fun onPairFinished(outcome: PairOutcome) {}

    companion object {
        val NOOP: JobListener = object : JobListener {}
    }
}

/**
 * Runs a configuration.
 *
 * Pairs run one after another — a failing pair must not take the others down with
 * it — while the files inside a pair may be copied in parallel, bounded by the
 * configuration's parallelism. A pair that had any failure keeps its previous
 * snapshot and touches no trash, so a retry starts from the state the last
 * successful run left behind.
 */
class JobRunner(
    private val left: RemoteBackend,
    private val right: RemoteBackend,
    private val snapshots: SnapshotStore = InMemorySnapshotStore(),
    private val listener: JobListener = JobListener.NOOP,
    private val utcClock: UtcClock = systemUtcClock(),
    private val chunkSize: Int = ChunkPlan.DEFAULT_CHUNK_SIZE,
    private val verifyCopies: Boolean = true,
    private val caseSensitive: Boolean = true,
) {

    suspend fun run(config: SyncConfig, jobId: String = "job"): JobResult {
        val started = utcClock.nowMs()
        listener.onJobStarted(jobId, config.pairs.count { it.enabled })

        val outcomes = ArrayList<PairOutcome>(config.pairs.size)
        for (pair in config.pairs.sortedBy { it.order }) {
            val outcome = if (!pair.enabled) {
                PairOutcome(pair.pairId, displayName(pair), PairStatus.SKIPPED, emptyList(), TransferStats())
            } else {
                try {
                    runPair(config, pair)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    PairOutcome(
                        pairId = pair.pairId,
                        pairName = displayName(pair),
                        status = PairStatus.FAILED,
                        items = emptyList(),
                        stats = TransferStats(failures = 1),
                        error = failure.message ?: failure::class.simpleName ?: "unknown failure",
                    )
                }
            }
            outcomes.add(outcome)
            listener.onPairFinished(outcome)
        }

        val status = when {
            outcomes.isEmpty() -> JobStatus.COMPLETED
            outcomes.any { it.status == PairStatus.FAILED || it.status == PairStatus.BLOCKED } &&
                outcomes.any { it.status == PairStatus.COMPLETED || it.status == PairStatus.DRY_RUN } -> JobStatus.PARTIAL_FAILED

            outcomes.all { it.status == PairStatus.FAILED || it.status == PairStatus.BLOCKED } -> JobStatus.FAILED
            else -> JobStatus.COMPLETED
        }
        return JobResult(jobId, config.configId, status, outcomes, started, utcClock.nowMs())
    }

    private suspend fun runPair(config: SyncConfig, pair: FolderPair): PairOutcome {
        val started = utcClock.nowMs()
        listener.onPairStarted(pair)

        val leftRoot: RootRef = SimpleRoot(left.id, pair.left.rootPath)
        val rightRoot: RootRef = SimpleRoot(right.id, pair.right.rootPath)
        val direction = pair.directionOverride ?: config.direction
        val matcher = PathMatcher(Filters.effective(config.globalFilter, pair.filterOverride), caseSensitive)
        val issues = PathOverlap.check(
            listOf(PairRoots(pair.pairId, left.id, pair.left.rootPath, pair.right.rootPath)),
            caseSensitive,
        )
        if (issues.isNotEmpty()) {
            // Checked before any IO: a pair whose roots nest cannot converge, so
            // there is nothing to learn from reading either tree.
            val reason = issues.joinToString("; ") { it.message }
            val blocked = PairOutcome(
                pairId = pair.pairId,
                pairName = displayName(pair),
                status = PairStatus.BLOCKED,
                items = emptyList(),
                stats = TransferStats(failures = 1),
                plan = PairPlan(pair.pairId, direction, emptyList(), blocked = true, blockReason = reason),
                error = reason,
                startedAtMs = started,
                finishedAtMs = utcClock.nowMs(),
            )
            listener.onPairPlanned(blocked)
            return blocked
        }

        val leftTree = listFiltered(left, leftRoot, matcher)
        val rightTree = listFiltered(right, rightRoot, matcher)
        val snapshot = snapshots.load(pair.pairId)?.takeIf { it.matches(leftRoot, rightRoot) }

        val hashLeft = config.compare == CompareMode.CONTENT_HASH
        val leftHashes = if (hashLeft) hashPaired(left, leftRoot, leftTree, rightTree) else emptyMap()
        val rightHashes = if (hashLeft) hashPaired(right, rightRoot, rightTree, leftTree) else emptyMap()

        val comparator = TreeComparator(config.compare, config.mtimeToleranceMs, caseSensitive)
        val diffs = comparator.compare(leftTree, rightTree, leftHashes, rightHashes, snapshot)
        val plan = Planner.plan(pair.pairId, diffs, direction, config.conflict, config.delete, snapshot)

        if (config.dryRun) {
            val items = plan.actions.map { action ->
                JobItem(
                    relPath = action.relPath,
                    action = action.kind,
                    skippedReason = if (action.kind == ActionKind.NONE) "" else "dry run",
                )
            }
            val outcome = PairOutcome(
                pairId = pair.pairId,
                pairName = displayName(pair),
                status = PairStatus.DRY_RUN,
                items = items,
                stats = TransferStats(skipped = items.count { it.skippedReason.isNotEmpty() }),
                plan = plan,
                startedAtMs = started,
                finishedAtMs = utcClock.nowMs(),
            )
            listener.onPairPlanned(outcome)
            return outcome
        }

        val items = ArrayList<JobItem>()
        val failures = ArrayList<String>()
        val timestamp = utcClock.nowMs()

        // Directories first: a file copy must never depend on a backend creating
        // its own parent chain implicitly.
        for (action in plan.actions.filter { it.kind == ActionKind.COPY_LEFT_TO_RIGHT && it.diff.isDirectory }) {
            items.add(createDirectory(right, rightRoot, action.relPath, failures))
        }
        for (action in plan.actions.filter { it.kind == ActionKind.COPY_RIGHT_TO_LEFT && it.diff.isDirectory }) {
            items.add(createDirectory(left, leftRoot, action.relPath, failures))
        }

        val copies = plan.actions.filter {
            (it.kind == ActionKind.COPY_LEFT_TO_RIGHT || it.kind == ActionKind.COPY_RIGHT_TO_LEFT) && !it.diff.isDirectory
        }
        val permits = Semaphore(config.parallelism.coerceAtLeast(1))
        val copied = coroutineScope {
            copies.map { action ->
                async { permits.withPermit { executeCopy(action, leftRoot, rightRoot, pair) } }
            }.awaitAll()
        }
        items.addAll(copied)
        copied.filterNot { it.ok }.forEach { failures.add("${it.relPath}: ${it.error}") }

        // Conflicts and deletions run after the copies: a renamed loser must not be
        // overwritten by its own winner, and a removed directory must not race a
        // file still being written into it.
        for (action in plan.actions) {
            when (action.kind) {
                ActionKind.CONFLICT_KEEP_BOTH ->
                    items.add(keepBoth(action, leftRoot, rightRoot, pair, timestamp, failures))

                ActionKind.CONFLICT_SKIP, ActionKind.CONFLICT_ASK ->
                    items.add(
                        JobItem(
                            action.relPath,
                            action.kind,
                            skippedReason = "both sides changed; a decision is required",
                        ),
                    )

                else -> Unit
            }
        }

        val deletes = plan.actions.filter { it.kind == ActionKind.DELETE_LEFT || it.kind == ActionKind.DELETE_RIGHT }
            .sortedByDescending { action -> action.relPath.count { it == '/' } }
        for (action in deletes) {
            items.add(executeDelete(action, leftRoot, rightRoot, config, pair, timestamp, failures))
        }

        for (action in plan.actions) {
            if (action.kind == ActionKind.NONE && action.diff.kind != DiffKind.EQUAL) {
                items.add(
                    JobItem(action.relPath, action.kind, skippedReason = action.note.ifEmpty { "no action selected" }),
                )
            }
        }

        val sorted = items.sortedBy { it.relPath }
        val stats = TransferStats(
            copiedFiles = sorted.count { it.ok && (it.action == ActionKind.COPY_LEFT_TO_RIGHT || it.action == ActionKind.COPY_RIGHT_TO_LEFT) },
            copiedBytes = sorted.sumOf { it.bytes },
            deletedFiles = sorted.count { it.ok && (it.action == ActionKind.DELETE_LEFT || it.action == ActionKind.DELETE_RIGHT) },
            conflicts = sorted.count {
                it.action == ActionKind.CONFLICT_ASK || it.action == ActionKind.CONFLICT_SKIP || it.action == ActionKind.CONFLICT_KEEP_BOTH
            },
            skipped = sorted.count { it.skippedReason.isNotEmpty() && it.action == ActionKind.NONE },
            failures = sorted.count { !it.ok },
        )

        val status = when {
            stats.failures > 0 -> PairStatus.FAILED
            else -> {
                snapshots.save(
                    PairSnapshot(
                        pairId = pair.pairId,
                        rev = (snapshot?.rev ?: 0L) + 1,
                        takenAtMs = utcClock.nowMs(),
                        leftFingerprint = RootFingerprint.of(leftRoot),
                        rightFingerprint = RootFingerprint.of(rightRoot),
                        entries = snapshotEntries(leftTree, rightTree, plan, leftHashes, rightHashes),
                    ),
                )
                PairStatus.COMPLETED
            }
        }

        val outcome = PairOutcome(
            pairId = pair.pairId,
            pairName = displayName(pair),
            status = status,
            items = sorted,
            stats = stats,
            plan = plan,
            error = failures.joinToString("; ").take(2_000),
            startedAtMs = started,
            finishedAtMs = utcClock.nowMs(),
        )
        return outcome
    }

    // -----------------------------------------------------------------------
    // Primitive steps
    // -----------------------------------------------------------------------

    private suspend fun listFiltered(backend: RemoteBackend, root: RootRef, matcher: PathMatcher): List<FileMeta> {
        if (!Capabilities.has(backend.capabilities, Capabilities.LIST)) {
            throw BackendException(ErrorCode.INTERNAL, "${backend.displayName} cannot list folders")
        }
        return backend.list(root, "", recursive = true)
            .filter { !TrashLayout.isTrashPath(it.relPath) }
            .filter { matcher.accept(it) }
    }

    /**
     * Hashes only the files whose size also appears on the other side; anything
     * else differs by size already and hashing it would be wasted work.
     */
    private suspend fun hashPaired(
        backend: RemoteBackend,
        root: RootRef,
        own: List<FileMeta>,
        other: List<FileMeta>,
    ): Map<String, String> {
        if (!Capabilities.has(backend.capabilities, Capabilities.HASH)) return emptyMap()
        val otherSizes = other.filterNot { it.isDirectory }.associate { it.relPath to it.size }
        val out = HashMap<String, String>(own.size)
        for (entry in own) {
            if (entry.isDirectory) continue
            if (otherSizes[entry.relPath] != entry.size) continue
            out[entry.relPath] = backend.hash(root, entry.relPath, Algorithms.MD5)
        }
        return out
    }

    private suspend fun createDirectory(
        backend: RemoteBackend,
        root: RootRef,
        relPath: String,
        failures: MutableList<String>,
    ): JobItem = try {
        backend.mkdir(root, relPath, createParents = true)
        JobItem(relPath, ActionKind.MKDIR, ok = true)
    } catch (failure: Throwable) {
        failures.add("$relPath: ${failure.message}")
        JobItem(relPath, ActionKind.MKDIR, ok = false, error = failure.message ?: "mkdir failed")
    }

    private suspend fun executeCopy(
        action: SyncAction,
        leftRoot: RootRef,
        rightRoot: RootRef,
        pair: FolderPair,
    ): JobItem {
        val leftToRight = action.kind == ActionKind.COPY_LEFT_TO_RIGHT
        val source = if (leftToRight) left else right
        val target = if (leftToRight) right else left
        val sourceRoot = if (leftToRight) leftRoot else rightRoot
        val targetRoot = if (leftToRight) rightRoot else leftRoot
        val meta = action.diff.left ?: action.diff.right
            ?: return JobItem(action.relPath, action.kind, ok = false, error = "no source metadata")

        listener.onActionStarted(pair.pairId, action)
        val item = try {
            val bytes = copyFile(source, sourceRoot, target, targetRoot, action.relPath, meta, pair.pairId) { delta ->
                listener.onBytesTransferred(pair.pairId, action.relPath, delta)
            }
            JobItem(action.relPath, action.kind, bytes, ok = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            JobItem(action.relPath, action.kind, ok = false, error = failure.message ?: "copy failed")
        }
        listener.onActionFinished(pair.pairId, item)
        return item
    }

    /**
     * Copies one file, resuming when the target already holds part of it.
     *
     * The digest is computed while streaming when the copy starts at byte zero, and
     * by re-reading when it resumes, because the bytes before the resume point are
     * not part of this pass's stream.
     */
    private suspend fun copyFile(
        source: RemoteBackend,
        sourceRoot: RootRef,
        target: RemoteBackend,
        targetRoot: RootRef,
        relPath: String,
        meta: FileMeta,
        pairId: String,
        onProgress: (Long) -> Unit = {},
    ): Long {
        val parent = relPath.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) {
            try {
                target.mkdir(targetRoot, parent, createParents = true)
            } catch (failure: BackendException) {
                if (failure.code != ErrorCode.ALREADY_EXISTS) throw failure
            }
        }

        var writer: WriteStream? = null
        try {
            val stream = target.openWrite(
                root = targetRoot,
                relPath = relPath,
                size = meta.size,
                mtimeMs = meta.mtimeMs,
                resume = true,
            )
            writer = stream
            val startOffset = stream.startOffset
            if (meta.size > 0 && startOffset >= meta.size) {
                stream.commit(meta.mtimeMs)
                writer = null
                return meta.size
            }

            val onTheFly = if (startOffset == 0L) newDigest(Algorithms.MD5) else null
            var written = 0L
            val reader = source.openRead(sourceRoot, relPath, startOffset)
            try {
                while (true) {
                    val chunk: Chunk = reader.read(chunkSize) ?: break
                    if (chunk.length == 0) break
                    stream.write(chunk.data, 0, chunk.length)
                    onTheFly?.update(chunk.data, 0, chunk.length)
                    written += chunk.length
                    onProgress(chunk.length.toLong())
                }
            } finally {
                reader.close()
            }
            stream.commit(meta.mtimeMs)
            writer = null

            if (verifyCopies) {
                val targetHash = target.hash(targetRoot, relPath, Algorithms.MD5)
                val sourceHash = onTheFly?.hex() ?: source.hash(sourceRoot, relPath, Algorithms.MD5)
                if (targetHash != sourceHash) {
                    throw BackendException(
                        ErrorCode.RESUME_MISMATCH,
                        "content mismatch after copy: source $sourceHash, target $targetHash",
                    )
                }
            }
            return written
        } finally {
            val pending = writer
            if (pending != null) {
                // A failure that is not a verification mismatch keeps the partial
                // file, which is what makes the next attempt able to resume.
                runCatching { pending.close() }
            }
        }
    }

    private suspend fun executeDelete(
        action: SyncAction,
        leftRoot: RootRef,
        rightRoot: RootRef,
        config: SyncConfig,
        pair: FolderPair,
        timestamp: Long,
        failures: MutableList<String>,
    ): JobItem {
        val backend = if (action.kind == ActionKind.DELETE_LEFT) left else right
        val root = if (action.kind == ActionKind.DELETE_LEFT) leftRoot else rightRoot
        listener.onActionStarted(pair.pairId, action)
        val item = try {
            if (action.recycle && Capabilities.has(backend.capabilities, Capabilities.RENAME)) {
                recycle(backend, root, action.relPath, config.configId, pair.pairId, timestamp)
            } else {
                backend.delete(root, action.relPath, toTrash = false)
            }
            JobItem(action.relPath, action.kind, ok = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            val failed = JobItem(action.relPath, action.kind, ok = false, error = failure.message ?: "delete failed")
            failures.add("${action.relPath}: ${failed.error}")
            failed
        }
        listener.onActionFinished(pair.pairId, item)
        return item
    }

    /**
     * Moves a path into the trash directory of its own root.
     *
     * A rename is tried first: within one filesystem it is atomic and instant. If
     * the trash cannot be reached that way the content is copied and only then
     * removed, so a failure halfway leaves the original where it was.
     */
    private suspend fun recycle(
        backend: RemoteBackend,
        root: RootRef,
        relPath: String,
        configId: String,
        pairId: String,
        timestampMs: Long,
    ) {
        val trashRel = TrashLayout.trashRelPath(configId, pairId, timestampMs, relPath)
        val parent = trashRel.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) runCatching { backend.mkdir(root, parent, createParents = true) }

        try {
            backend.rename(root, relPath, trashRel)
            return
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unreachable: Throwable) {
            // Fall through to copy + delete.
        }

        val entries = backend.list(root, relPath, recursive = true)
        for (entry in entries) {
            val suffix = entry.relPath.removePrefix(relPath).trimStart('/')
            val targetRel = if (suffix.isEmpty()) trashRel else "$trashRel/$suffix"
            if (entry.isDirectory) {
                backend.mkdir(root, targetRel, createParents = true)
            } else {
                copyFile(backend, root, backend, root, entry.relPath, entry, pairId) { }
            }
        }
        backend.delete(root, relPath, toTrash = false)
    }

    /**
     * Keeps both versions of a conflict: the losing side is renamed aside on its
     * own side and the winning version keeps the original name.
     */
    private suspend fun keepBoth(
        action: SyncAction,
        leftRoot: RootRef,
        rightRoot: RootRef,
        pair: FolderPair,
        timestamp: Long,
        failures: MutableList<String>,
    ): JobItem {
        listener.onActionStarted(pair.pairId, action)
        val meta = action.diff.left ?: action.diff.right
        if (meta == null) {
            return JobItem(action.relPath, action.kind, ok = false, error = "no source metadata")
        }
        return try {
            val keptRel = ConflictNaming.conflictName(action.relPath, pair.pairId, timestamp, Side.RIGHT)
            right.rename(rightRoot, action.relPath, keptRel)
            val bytes = copyFile(left, leftRoot, right, rightRoot, action.relPath, meta, pair.pairId) { delta ->
                listener.onBytesTransferred(pair.pairId, action.relPath, delta)
            }
            JobItem(action.relPath, ActionKind.CONFLICT_KEEP_BOTH, bytes, ok = true, skippedReason = "kept both: $keptRel")
                .also { listener.onActionFinished(pair.pairId, it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            val failed = JobItem(action.relPath, action.kind, ok = false, error = failure.message ?: "conflict handling failed")
            failures.add("${action.relPath}: ${failed.error}")
            listener.onActionFinished(pair.pairId, failed)
            failed
        }
    }

    /** Snapshot after a run: every copied path now looks the same on both sides. */
    private fun snapshotEntries(
        leftTree: List<FileMeta>,
        rightTree: List<FileMeta>,
        plan: PairPlan,
        leftHashes: Map<String, String>,
        rightHashes: Map<String, String>,
    ): Map<String, SnapshotEntry> {
        val merged = LinkedHashMap<String, FileMeta>(leftTree.size + rightTree.size)
        leftTree.forEach { if (!it.isDirectory) merged[it.relPath] = it }
        rightTree.forEach { if (!it.isDirectory) merged[it.relPath] = it }

        val hashes = HashMap<String, String>(leftHashes.size + rightHashes.size)
        hashes.putAll(rightHashes)
        hashes.putAll(leftHashes)

        for (action in plan.actions) {
            when (action.kind) {
                ActionKind.COPY_LEFT_TO_RIGHT, ActionKind.CONFLICT_KEEP_BOTH ->
                    action.diff.left?.takeIf { !it.isDirectory }?.let { merged[action.relPath] = it }

                ActionKind.COPY_RIGHT_TO_LEFT ->
                    action.diff.right?.takeIf { !it.isDirectory }?.let { merged[action.relPath] = it }

                ActionKind.DELETE_LEFT, ActionKind.DELETE_RIGHT -> merged.remove(action.relPath)
                else -> Unit
            }
        }
        return merged.mapValues { (path, meta) -> SnapshotEntry(path, meta.size, meta.mtimeMs, hashes[path]) }
    }

    private fun displayName(pair: FolderPair): String = pair.name.ifEmpty { pair.pairId }

    companion object {
        fun supportsDirection(direction: SyncDirection): Boolean = direction.supported
    }
}
