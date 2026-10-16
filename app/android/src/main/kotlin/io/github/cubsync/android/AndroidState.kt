package io.github.cubsync.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.cubsync.core.engine.JobResult
import io.github.cubsync.core.engine.JobStatus
import io.github.cubsync.core.engine.PairOutcome
import io.github.cubsync.core.model.Endpoint
import io.github.cubsync.core.model.FolderPair
import io.github.cubsync.core.model.Ids
import io.github.cubsync.core.model.SyncConfig
import io.github.cubsync.core.protocol.Protocol
import io.github.cubsync.service.ConfigStore
import io.github.cubsync.service.EndpointId
import io.github.cubsync.service.Format
import io.github.cubsync.service.JobRecord
import io.github.cubsync.service.LogEntry
import io.github.cubsync.service.LogLevel
import io.github.cubsync.service.PathTools
import io.github.cubsync.service.PeerSession
import io.github.cubsync.service.ServerController
import io.github.cubsync.service.ServerState
import io.github.cubsync.service.SyncObserver
import io.github.cubsync.service.SyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

enum class Tab(val label: String) {
    CONNECT("连接"),
    SYNC("同步"),
    JOBS("作业"),
    SETTINGS("设置"),
}

/** Where the phone is. A phone shows one thing at a time, so a detail is a place. */
sealed interface Route {
    data object Tabs : Route
    data class ConfigDetail(val configId: String) : Route
    data class JobDetail(val jobId: String) : Route
}

enum class NoticeKind { INFO, SUCCESS, WARNING, ERROR }

data class Notice(val text: String, val kind: NoticeKind)

data class RunningJob(
    val jobId: String,
    val configName: String,
    val dryRun: Boolean,
    val pairCount: Int,
    val finishedPairs: Int = 0,
    val currentPair: String = "",
    val bytes: Long = 0,
) {
    val label: String get() = if (dryRun) "试运行" else "同步中"
}

/**
 * Everything the phone is showing.
 *
 * The same shape as the desktop window's state object, for the same reason: the
 * screens are not independent, and a run started on one has to appear on another.
 * The difference is navigation — a phone shows one screen, so a configuration detail
 * is a route rather than a panel beside a list.
 */
class AndroidAppState(
    val service: SyncService,
    val server: ServerController,
    val preferences: Preferences,
    private val scope: CoroutineScope,
    val appDirectory: File,
) {

    var tab by mutableStateOf(Tab.CONNECT)
    var route by mutableStateOf<Route>(Route.Tabs)

    var configs by mutableStateOf<List<SyncConfig>>(emptyList())
    var jobs by mutableStateOf<List<JobRecord>>(emptyList())
    var logs by mutableStateOf<List<LogEntry>>(emptyList())
    var logMinimumLevel by mutableStateOf(LogLevel.INFO)
    var peers by mutableStateOf<List<PeerSession>>(emptyList())
    var peerError by mutableStateOf<String?>(null)
    var peerDraft by mutableStateOf("")
    var connecting by mutableStateOf(false)
    var running by mutableStateOf<RunningJob?>(null)
    var notice by mutableStateOf<Notice?>(null)
    var rootDraft by mutableStateOf<List<String>>(emptyList())

    private var runTask: Job? = null

    val stateDirectory: String get() = service.stateDirectory.toString()

    init {
        reloadConfigs()
        reloadJobs()
        logs = service.log.entries()
        service.log.subscribe { entry ->
            val next = logs + entry
            logs = if (next.size > LOG_LIMIT) next.takeLast(LOG_LIMIT) else next
        }
        peerDraft = preferences.lastPeer
        rootDraft = preferences.sharedRoots
    }

    fun close() {
        runTask?.cancel()
        service.close()
        server.stop()
    }

    // ---------------------------------------------------------------- navigation

    fun go(target: Tab) {
        tab = target
        route = Route.Tabs
    }

    fun open(target: Route) {
        route = target
    }

    fun back(): Boolean {
        if (route is Route.ConfigDetail || route is Route.JobDetail) {
            route = Route.Tabs
            return true
        }
        return false
    }

    fun config(configId: String): SyncConfig? = configs.firstOrNull { it.configId == configId }

    fun job(jobId: String): JobRecord? = jobs.firstOrNull { it.jobId == jobId }

    // ------------------------------------------------------------------ messages

    fun notify(text: String, kind: NoticeKind = NoticeKind.INFO) {
        notice = Notice(text, kind)
    }

    fun dismissNotice() {
        notice = null
    }

    // -------------------------------------------------------------- configurations

    fun reloadConfigs() {
        configs = service.configs.list()
    }

    fun reloadJobs() {
        jobs = service.history.all()
    }

    fun newConfigDraft(): SyncConfig = SyncConfig(
        configId = Ids.newConfigId(),
        name = "新建同步",
        direction = preferences.defaultDirection,
        compare = preferences.defaultCompare,
        conflict = preferences.defaultConflict,
        delete = preferences.defaultDelete,
        pairs = emptyList(),
    )

    fun saveConfig(config: SyncConfig) {
        runCatching { service.configs.save(config) }
            .onSuccess {
                reloadConfigs()
                notify("已保存「${config.name}」", NoticeKind.SUCCESS)
            }
            .onFailure { notify("保存失败：${it.message}", NoticeKind.ERROR) }
    }

    fun deleteConfig(configId: String) {
        if (service.configs.delete(configId)) {
            reloadConfigs()
            route = Route.Tabs
            notify("已删除配置", NoticeKind.INFO)
        }
    }

    fun newPairDraft(existing: List<FolderPair>): FolderPair {
        val peer = peers.firstOrNull()
        return FolderPair(
            pairId = Ids.newPairId(),
            order = existing.size,
            left = Endpoint(EndpointId.LOCAL, localRoots().firstOrNull().orEmpty()),
            right = if (peer != null && peer.roots.isNotEmpty()) {
                Endpoint(peer.backendId, peer.roots.first())
            } else {
                Endpoint(EndpointId.LOCAL, "")
            },
        )
    }

    /**
     * Paths worth offering on a phone.
     *
     * There is no folder picker here on purpose: the platform's picker hands back a
     * content URI, and the engine works in paths. A wrong guess that a person can
     * correct in a text field is better than a picker that produces something the
     * engine cannot open.
     */
    fun localRoots(): List<String> {
        val candidates = listOf(
            "/sdcard/DCIM",
            "/sdcard/Pictures",
            "/sdcard/Download",
            "/sdcard/Documents",
            "/sdcard/Music",
            "/sdcard/Movies",
            appDirectory.absolutePath,
        )
        return candidates.filter { runCatching { File(it).isDirectory }.getOrDefault(false) }
    }

    // ----------------------------------------------------------------------- runs

    fun runConfig(config: SyncConfig, dryRun: Boolean) {
        if (running != null) {
            notify("已经有一个任务在进行中", NoticeKind.WARNING)
            return
        }
        if (config.pairs.isEmpty()) {
            notify("这个配置还没有文件夹对", NoticeKind.WARNING)
            return
        }

        val jobId = SyncService.newJobId()
        val prepared = config.copy(dryRun = dryRun)
        running = RunningJob(jobId, prepared.name, dryRun, prepared.pairs.size)
        dismissNotice()

        runTask = scope.launch {
            try {
                val result = service.run(prepared, jobId = jobId, observer = PhoneObserver())
                reloadJobs()
                route = Route.JobDetail(result.jobId)
                notify(summaryLine(result), toneFor(result.status))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                notify("已取消「${prepared.name}」", NoticeKind.WARNING)
            } catch (failure: Throwable) {
                notify("运行失败：${failure.message ?: failure::class.simpleName}", NoticeKind.ERROR)
            } finally {
                running = null
                runTask = null
            }
        }
    }

    fun cancelRun() {
        val task = runTask ?: return
        running = null
        task.cancel()
    }

    private fun summaryLine(result: JobResult): String {
        val stats = result.stats
        return "${Format.jobStatus(result.status)}：复制 ${stats.copiedFiles} 个文件" +
            "（${Format.bytes(stats.copiedBytes)}），删除 ${stats.deletedFiles}，失败 ${stats.failures}"
    }

    private fun toneFor(status: JobStatus): NoticeKind = when (status) {
        JobStatus.COMPLETED -> NoticeKind.SUCCESS
        JobStatus.PARTIAL_FAILED -> NoticeKind.WARNING
        else -> NoticeKind.ERROR
    }

    private inner class PhoneObserver : SyncObserver {
        override fun onPairStarted(pair: FolderPair) {
            running = running?.copy(currentPair = pair.name.ifEmpty { pair.pairId })
        }

        override fun onPairFinished(outcome: PairOutcome) {
            running = running?.copy(finishedPairs = (running?.finishedPairs ?: 0) + 1, bytes = 0)
        }

        override fun onBytesTransferred(total: Long) {
            running = running?.copy(bytes = total)
        }
    }

    // ---------------------------------------------------------------------- peers

    fun connectPeer(spec: String) {
        val text = spec.trim()
        val endpoint = EndpointId.parseOrNull(text)?.takeIf { !it.isLocal }
        if (endpoint == null) {
            peerError = "「$text」看起来不是 HOST:PORT"
            return
        }
        peerError = null
        connecting = true
        scope.launch {
            try {
                val session = service.browsePool.probe(endpoint.host!!, endpoint.port!!)
                peers = peers.filterNot { it.backendId == session.backendId } + session
                preferences.lastPeer = text
                notify("已连接 ${session.nodeName}", NoticeKind.SUCCESS)
            } catch (failure: Throwable) {
                peerError = failure.message ?: "连接失败"
                notify("连接失败：$peerError", NoticeKind.ERROR)
            } finally {
                connecting = false
            }
        }
    }

    fun disconnectPeer(backendId: String) {
        service.browsePool.disconnect(backendId)
        peers = peers.filterNot { it.backendId == backendId }
    }

    fun peerFor(backendId: String): PeerSession? = peers.firstOrNull { it.backendId == backendId }

    // --------------------------------------------------------------------- server

    val serverState: ServerState get() = server.state.value

    fun addRoot(path: String) {
        val clean = path.trim()
        if (clean.isEmpty()) return
        val absolute = PathTools.normalizeRoot(PathTools.absolute(clean))
        if (rootDraft.any { it == absolute }) return
        rootDraft = rootDraft + absolute
        preferences.sharedRoots = rootDraft
    }

    fun removeRoot(path: String) {
        rootDraft = rootDraft.filterNot { it == path }
        preferences.sharedRoots = rootDraft
    }

    fun startServer(port: Int, nodeName: String) {
        val state = server.start(
            roots = rootDraft,
            port = if (port <= 0) Protocol.DEFAULT_PORT else port,
            nodeName = nodeName,
        )
        if (state.running) {
            preferences.sharePort = state.port
            preferences.sharedRoots = state.roots
            notify("已在端口 ${state.port} 共享 ${state.roots.size} 个文件夹", NoticeKind.SUCCESS)
        } else {
            notify(state.error, NoticeKind.ERROR)
        }
    }

    fun stopServer() {
        server.stop()
        notify("已停止共享", NoticeKind.INFO)
    }

    companion object {
        const val LOG_LIMIT: Int = 2_000

        /** Configurations and snapshots live in the app's own directory. */
        fun stateDirectoryIn(appDirectory: File): java.nio.file.Path =
            ConfigStore(appDirectory.toPath().resolve("state")).baseDirectory
    }
}
