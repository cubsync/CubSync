package io.github.cubsync.service

import io.github.cubsync.core.engine.ActionKind
import io.github.cubsync.core.engine.JobListener
import io.github.cubsync.core.engine.JobResult
import io.github.cubsync.core.engine.PauseGate
import io.github.cubsync.core.engine.JobRunner
import io.github.cubsync.core.engine.JobStatus
import io.github.cubsync.core.engine.PairOutcome
import io.github.cubsync.core.model.FolderPair
import io.github.cubsync.core.model.SyncConfig
import io.github.cubsync.core.model.SyncDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One finished run, kept so a window can show a history without re-reading disk. */
data class JobRecord(
    val jobId: String,
    val configId: String,
    val configName: String,
    val direction: SyncDirection,
    val dryRun: Boolean,
    val result: JobResult,
)

/** The runs of this session, newest first. */
class JobHistory(private val limit: Int = 200) {

    private val lock = Any()
    private val records = ArrayDeque<JobRecord>()

    fun add(record: JobRecord): JobRecord {
        synchronized(lock) {
            records.addFirst(record)
            while (records.size > limit) records.removeLast()
        }
        return record
    }

    fun all(): List<JobRecord> = synchronized(lock) { records.toList() }

    fun find(jobId: String): JobRecord? = synchronized(lock) { records.firstOrNull { it.jobId == jobId } }

    /** Drops one run from the list. Nothing on disk is touched; this is a list, not a log. */
    fun forget(jobId: String) = synchronized(lock) { records.removeAll { it.jobId == jobId } }

    fun clear() = synchronized(lock) { records.clear() }
}

/** Progress callbacks for a front end. The engine's own listener is separate. */
interface SyncObserver {
    fun onStarted(config: SyncConfig, jobId: String) {}
    fun onPairStarted(pair: FolderPair) {}
    fun onPairFinished(outcome: PairOutcome) {}
    fun onBytesTransferred(total: Long) {}

    companion object {
        val NONE: SyncObserver = object : SyncObserver {}
    }
}

/**
 * Runs configurations and remembers what happened.
 *
 * Both front ends go through this rather than calling [JobRunner] directly, so the
 * decision about where snapshots live, which roots are registered, and what gets
 * logged is made once. A scheduled run, a button press and a CLI invocation then
 * behave identically, which is the property that matters when something goes wrong
 * at 3am and only one of them has been tried.
 */
class SyncService(
    val stateDirectory: Path = ConfigStore.defaultBase(),
    val log: AppLog = AppLog(),
    val history: JobHistory = JobHistory(),
    /**
     * Where the user's preferences are kept.
     *
     * Passed in rather than created here, because the store is the one thing that
     * genuinely differs between a desktop and a phone — a properties file against
     * SharedPreferences — while everything that reads it does not.
     */
    settingsStorage: SettingsStorage = SettingsStorage.memory(),
) {

    /** The settings screen's contents, and the browsing preferences derived from them. */
    val settings: AppSettings = AppSettings(settingsStorage)

    val configs: ConfigStore = ConfigStore(stateDirectory)

    /** Long-lived pool for browsing; a run may share it, or open its own. */
    private val browseScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val browsePool: BackendPool = BackendPool(
        scope = browseScope,
        nodeName = "CubSync",
        appVersion = BuildInfo.VERSION,
        log = log,
    )

    /** Listing and ad-hoc copying, for a two-pane browser. */
    val browser: FolderBrowser = FolderBrowser(browsePool, log)

    /**
     * The two panes and what they are showing.
     *
     * One session for the whole application, because a pane is a view of a machine
     * rather than a view of a screen: leaving the browser and coming back should find
     * the same folder open, not a fresh one.
     */
    val browsing: BrowserSession = BrowserSession(browser, browseScope, settings = settings, log = log)

    suspend fun run(
        config: SyncConfig,
        jobId: String = newJobId(),
        observer: SyncObserver = SyncObserver.NONE,
        pool: BackendPool? = browsePool,
        /**
         * Whether this run belongs in the history.
         *
         * A preview is a run with `dryRun` set, and a preview is not something that
         * happened — putting it in the job list would mean the list fills with runs that
         * moved no files.
         */
        record: Boolean = true,
        /** 暂停 / 继续 for this run; null when nobody can pause it. */
        gate: PauseGate? = null,
    ): JobResult {
        val owned = pool == null
        val actual = pool ?: BackendPool(
            nodeName = "CubSync",
            appVersion = BuildInfo.VERSION,
            log = log,
        )
        try {
            for (pair in config.pairs) {
                for (endpoint in listOf(pair.left, pair.right)) {
                    if (EndpointId(endpoint.backendId).isLocal) actual.registerLocalRoot(endpoint.rootPath)
                }
            }

            log.info(
                "job",
                "${if (config.dryRun) "Comparing" else "Syncing"} ${config.name}",
                "${config.pairs.size} folder pair(s), direction ${config.direction.name.lowercase()}",
            )

            val listener = LoggingListener(log, observer)
            val runner = JobRunner(
                backends = actual.resolver,
                snapshots = configs.snapshotStore(),
                listener = listener,
                // The setting is somebody's answer to "may a copy continue where it
                // stopped", so the engine has to read it rather than always continuing.
                resumeEnabled = settings.resumeEnabled,
                gate = gate,
            )
            val result = runner.run(config, jobId)
            log.write(
                if (result.status == JobStatus.COMPLETED) LogLevel.INFO else LogLevel.WARN,
                "job",
                "${config.name}: ${result.status.name.lowercase()}",
                "${result.stats.copiedFiles} copied, ${result.stats.failures} failed, ${result.durationMs} ms",
            )
            if (record) {
                history.add(
                    JobRecord(
                        jobId = result.jobId,
                        configId = config.configId,
                        configName = config.name,
                        direction = config.direction,
                        dryRun = config.dryRun,
                        result = result,
                    ),
                )
            }
            return result
        } finally {
            if (owned) actual.close()
        }
    }

    /**
     * What a configuration would do, without doing it.
     *
     * A preview is a promise, so it is counted from the plan the engine would execute
     * rather than from a second comparison written for the screen. If the two ever
     * disagree, this is the one that is wrong, and it is wrong in the only direction
     * that matters — telling somebody nothing will happen before it moves 40 GB.
     */
    suspend fun preview(config: SyncConfig): SyncPreview =
        SyncPreview(run(config.copy(dryRun = true), record = false).pairs.map { it.toPreview() })

    /**
     * The drawer's three sections.
     *
     * Built from live state: a peer is online when the pool still has an open session
     * for it, and a root is listed when the backend actually exposes it. A drawer that
     * lists remembered things rather than current ones is a drawer people stop trusting.
     */
    fun drawer(server: ServerState): DrawerModel {
        val favourites = settings.favourites
            .groupBy { it.device }
            .map { (device, items) -> FavoriteGroup(device, items.sortedBy { it.path }) }

        val accounts = buildList {
            add(
                AccountEntry(
                    label = server.nodeName.ifEmpty { Words.LOCAL },
                    role = if (server.running) "${Words.SERVER} · 共享中 :${server.port}" else "${Words.SERVER} · 未共享",
                    online = server.running,
                ),
            )
            for (peer in settings.knownPeers) {
                val backendId = EndpointId.peer(peer.host, peer.port).backendId
                add(
                    AccountEntry(
                        label = peer.name.ifEmpty { peer.address },
                        role = "${Words.CLIENT} · ${peer.address}",
                        online = browsePool.isConnected(backendId),
                    ),
                )
            }
        }

        val roots = buildList {
            for (path in browsePool.registeredRoots()) add(Favorite(Words.LOCAL, EndpointId.LOCAL, path))
            for (peer in settings.knownPeers) {
                val backendId = EndpointId.peer(peer.host, peer.port).backendId
                for (path in browsing.exposedRoots(backendId)) {
                    add(Favorite(peer.name.ifEmpty { peer.address }, backendId, path))
                }
            }
        }

        return DrawerModel(favourites = favourites, accounts = accounts, roots = roots)
    }

    /** Asks a peer what it exposes and remembers the connection. */
    suspend fun connect(host: String, port: Int): PeerSession {
        val session = browsePool.probe(host, port)
        settings.rememberPeer(KnownPeer(session.nodeName, host, port))
        return session
    }

    fun disconnect(backendId: String) = browsePool.disconnect(backendId)

    fun close() {
        runCatching { browsePool.close() }
        runCatching { browseScope.cancel() }
    }

    companion object {
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("MMdd-HHmmss")

        fun newJobId(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String =
            "job-" + STAMP.format(now.atZone(zone))
    }
}

/**
 * What one folder pair is about to do, counted from its plan.
 *
 * The counts come from the actions rather than from the diff, because "更新" is a
 * decision the planner made, not a fact about the two files.
 */
private fun PairOutcome.toPreview(): PairPreview {
    val counts = changeCounts()
    return PairPreview(
        pairId = pairId,
        pairName = pairName.ifEmpty { pairId },
        create = counts.create,
        update = counts.update,
        delete = counts.delete,
        conflict = counts.conflict,
        skip = counts.skip,
        blockReason = plan?.blockReason.orEmpty(),
        bytes = counts.bytes,
    )
}

/**
 * Turns engine callbacks into log lines and into front-end progress.
 *
 * Deliberately thin: the engine already knows what it did, and this only decides
 * what is worth writing down. Per-file events are [LogLevel.DEBUG] because a job
 * that copies ten thousand files would otherwise bury everything else.
 */
private class LoggingListener(
    private val log: AppLog,
    private val observer: SyncObserver,
) : JobListener {

    private var bytes = 0L

    override fun onJobStarted(jobId: String, pairCount: Int) {
        log.info("job", "job $jobId started", "$pairCount folder pair(s)")
    }

    override fun onPairStarted(pair: FolderPair) {
        log.info("pair", "${pair.name.ifEmpty { pair.pairId }}: comparing")
        observer.onPairStarted(pair)
    }

    override fun onPairPlanned(outcome: PairOutcome) {
        log.info(
            "pair",
            "${outcome.pairName}: ${outcome.plan?.actions?.size ?: 0} action(s) planned",
            outcome.plan?.blockReason.orEmpty(),
        )
    }

    override fun onActionFinished(pairId: String, item: io.github.cubsync.core.engine.JobItem) {
        when {
            !item.ok -> log.warn("file", "${item.relPath}: ${item.error.ifEmpty { "failed" }}")
            item.skippedReason.isNotEmpty() -> log.debug("file", "${item.relPath}: skipped (${item.skippedReason})")
            else -> log.debug("file", "${item.action.name.lowercase()} ${item.relPath}")
        }
    }

    override fun onBytesTransferred(pairId: String, relPath: String, delta: Long) {
        bytes += delta
        observer.onBytesTransferred(bytes)
    }

    override fun onPairFinished(outcome: PairOutcome) {
        val level = when (outcome.status) {
            io.github.cubsync.core.engine.PairStatus.COMPLETED -> LogLevel.INFO
            io.github.cubsync.core.engine.PairStatus.DRY_RUN -> LogLevel.INFO
            io.github.cubsync.core.engine.PairStatus.SKIPPED -> LogLevel.INFO
            else -> LogLevel.WARN
        }
        log.write(
            level,
            "pair",
            "${outcome.pairName}: ${outcome.status.name.lowercase()}",
            "${outcome.stats.copiedFiles} copied, ${outcome.stats.copiedBytes} bytes, " +
                "${outcome.stats.deletedFiles} deleted, ${outcome.stats.failures} failed",
        )
        observer.onPairFinished(outcome)
    }
}
