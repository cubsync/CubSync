package io.github.cubsync.desktop

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

enum class Section(val label: String, val hint: String) {
    CONNECT("连接", "共享本机文件夹，或连上另一台设备"),
    TRANSFER("传输", "两栏对照，把选中的东西送过去"),
    SYNC("同步", "配置要同步的文件夹对，然后运行"),
    JOBS("作业", "每次运行的结果与明细"),
    LOGS("日志", "连接、比较、写入的实时记录"),
    SETTINGS("设置", "状态目录、默认策略与外观"),
}

enum class NoticeKind { INFO, SUCCESS, WARNING, ERROR }

data class Notice(val text: String, val kind: NoticeKind, val atMs: Long = System.currentTimeMillis())

/** A run that is in flight, as the window needs to show it. */
data class RunningJob(
    val jobId: String,
    val configName: String,
    val dryRun: Boolean,
    val pairCount: Int,
    val startedAtMs: Long,
    val finishedPairs: Int = 0,
    val currentPair: String = "",
    val bytes: Long = 0,
) {
    val label: String get() = if (dryRun) "试运行" else "同步中"
}

/**
 * Everything the window is showing.
 *
 * One object rather than a view model per screen, because the screens are not
 * independent: a run started on the sync screen has to appear on the jobs screen,
 * and a peer connected on the connect screen is what the pair editor offers as a
 * destination. Keeping that in one place is what stops the two from disagreeing.
 */
class AppState(
    val service: SyncService,
    val server: ServerController,
    val settings: DesktopSettings,
    private val scope: CoroutineScope,
) {

    var section by mutableStateOf(Section.CONNECT)

    var configs by mutableStateOf<List<SyncConfig>>(emptyList())
    var selectedConfigId by mutableStateOf<String?>(null)
    var jobs by mutableStateOf<List<JobRecord>>(emptyList())
    var selectedJobId by mutableStateOf<String?>(null)
    var logs by mutableStateOf<List<LogEntry>>(emptyList())
    var logMinimumLevel by mutableStateOf(LogLevel.INFO)
    var peers by mutableStateOf<List<PeerSession>>(emptyList())
    var peerError by mutableStateOf<String?>(null)
    var peerDraft by mutableStateOf("")
    var connecting by mutableStateOf(false)
    var running by mutableStateOf<RunningJob?>(null)
    var notice by mutableStateOf<Notice?>(null)

    /** Roots the server editor is holding before it is started. */
    var rootDraft by mutableStateOf<List<String>>(emptyList())

    private var runTask: Job? = null
    private var logSubscription: AutoCloseable? = null

    val stateDirectory: String get() = service.stateDirectory.toString()

    init {
        reloadConfigs()
        reloadJobs()
        logs = service.log.entries()
        logSubscription = service.log.subscribe { entry ->
            // The log arrives from whichever thread wrote it. Appending to a snapshot
            // list is safe from any thread, and the list is bounded so a long-running
            // window cannot grow without limit.
            val next = logs + entry
            logs = if (next.size > LOG_LIMIT) next.takeLast(LOG_LIMIT) else next
        }
        peerDraft = settings.lastPeer
        rootDraft = settings.sharedRoots
    }

    fun close() {
        logSubscription?.close()
        logSubscription = null
        runTask?.cancel()
        service.close()
        server.stop()
    }

    // ---------------------------------------------------------------- navigation

    fun goTo(target: Section) {
        section = target
    }

    fun selectConfig(configId: String?) {
        selectedConfigId = configId
    }

    fun selectJob(jobId: String?) {
        selectedJobId = jobId
    }

    val selectedConfig: SyncConfig?
        get() = selectedConfigId?.let { id -> configs.firstOrNull { it.configId == id } }

    val selectedJob: JobRecord?
        get() = selectedJobId?.let { id -> jobs.firstOrNull { it.jobId == id } }

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
        if (selectedConfigId != null && configs.none { it.configId == selectedConfigId }) {
            selectedConfigId = configs.firstOrNull()?.configId
        }
        if (selectedConfigId == null && section == Section.SYNC) {
            selectedConfigId = configs.firstOrNull()?.configId
        }
    }

    fun reloadJobs() {
        jobs = service.history.all()
        if (selectedJobId != null && jobs.none { it.jobId == selectedJobId }) selectedJobId = null
    }

    /** A new configuration with the defaults a person chose in the settings screen. */
    fun newConfigDraft(): SyncConfig = SyncConfig(
        configId = Ids.newConfigId(),
        name = "新建同步",
        direction = settings.defaultDirection,
        compare = settings.defaultCompare,
        conflict = settings.defaultConflict,
        delete = settings.defaultDelete,
        parallelism = settings.defaultParallelism,
        pairs = emptyList(),
    )

    fun saveConfig(config: SyncConfig) {
        runCatching { service.configs.save(config) }
            .onSuccess {
                reloadConfigs()
                selectedConfigId = config.configId
                notify("已保存「${config.name}」", NoticeKind.SUCCESS)
            }
            .onFailure { notify("保存失败：${it.message}", NoticeKind.ERROR) }
    }

    fun deleteConfig(configId: String) {
        val removed = service.configs.delete(configId)
        reloadConfigs()
        if (removed) notify("已删除配置", NoticeKind.INFO)
    }

    fun renameConfig(config: SyncConfig, name: String): SyncConfig = config.copy(name = name)

    /** Writes a configuration out as a `.cubcfg` that another machine can import. */
    fun exportConfig(config: SyncConfig) {
        val target = pickSaveFile("导出配置", config.name.ifBlank { config.configId }, ".cubcfg")
        if (target == null) {
            notify("已取消导出", NoticeKind.INFO)
            return
        }
        runCatching { service.configs.export(config, java.nio.file.Paths.get(target)) }
            .onSuccess { notify("已导出到 $it", NoticeKind.SUCCESS) }
            .onFailure { notify("导出失败：${it.message}", NoticeKind.ERROR) }
    }

    /** Reads a `.cubcfg`; an id that is already taken becomes a new configuration. */
    fun importConfig() {
        val source = pickOpenFile("导入配置", ".cubcfg") ?: pickOpenFile("导入配置", ".json")
        if (source == null) {
            notify("已取消导入", NoticeKind.INFO)
            return
        }
        runCatching { service.configs.load(java.nio.file.Paths.get(source)) }
            .onSuccess { imported ->
                val clash = service.configs.exists(imported.configId)
                val saved = service.configs.save(
                    if (clash) imported.copy(configId = Ids.newConfigId()) else imported,
                )
                reloadConfigs()
                selectedConfigId = service.configs.load(saved).configId
                notify(
                    if (clash) "导入了一个同名配置，已另存为新配置" else "已导入「${imported.name}」",
                    NoticeKind.SUCCESS,
                )
            }
            .onFailure { notify("导入失败：${it.message}", NoticeKind.ERROR) }
    }

    // ------------------------------------------------------------------- browsing

    /**
     * Bumped whenever a browsing preference changes.
     *
     * The preferences live in the shared layer as plain mutable state, because both
     * front ends read the same ones; this is how a screen announces that it changed
     * one, so the layout knows to look again.
     */
    var browserRevision by mutableStateOf(0)
        private set

    fun changeBrowserSettings(change: (io.github.cubsync.service.BrowserSettings) -> Unit) {
        change(service.browsing.settings)
        service.browsing.resort()
        browserRevision++
    }

    /** Copies one pane's selection into the other, and says what happened. */
    fun transfer(from: io.github.cubsync.service.Side) {
        service.browsing.transfer(from) { outcome ->
            val tone = if (outcome.ok) NoticeKind.SUCCESS else NoticeKind.WARNING
            val text = when {
                !outcome.ok -> "传送了 ${outcome.copied} 项，${outcome.failures.size} 项失败：${outcome.failures.first()}"
                outcome.copied == 0 -> "没有需要传送的内容"
                else -> "已传送 ${outcome.copied} 项（${Format.bytes(outcome.bytes)}）"
            }
            notify(text, tone)
        }
    }

    /**
     * Folders worth opening in a pane.
     *
     * The folders this machine shares come first, because those are the ones somebody
     * has deliberately decided to work with; then the home folder and the drives, so a
     * pane is never stuck with nowhere to go.
     */
    fun localRoots(): List<String> {
        val shared = settings.sharedRoots
        val home = runCatching { java.nio.file.Paths.get(System.getProperty("user.home")).toString() }.getOrNull()
        val drives = runCatching { java.io.File.listRoots().map { it.absolutePath }.toList() }.getOrDefault(emptyList())
        return (shared + listOfNotNull(home) + drives).distinct()
    }

    /**
     * A pair with the sensible default: the local side is this machine and the other
     * side is the peer that is connected, if there is one.
     */
    fun newPairDraft(existing: List<FolderPair>): FolderPair {
        val peer = peers.firstOrNull()
        return FolderPair(
            pairId = Ids.newPairId(),
            order = existing.size,
            name = "",
            left = Endpoint(EndpointId.LOCAL, ""),
            right = if (peer != null && peer.roots.isNotEmpty()) {
                Endpoint(peer.backendId, peer.roots.first())
            } else {
                Endpoint(EndpointId.LOCAL, "")
            },
        )
    }

    // ----------------------------------------------------------------------- runs

    fun runConfig(config: SyncConfig, dryRun: Boolean) {
        if (running != null) {
            notify("已经有一个${running?.label ?: "任务"}在进行中", NoticeKind.WARNING)
            return
        }
        if (config.pairs.isEmpty()) {
            notify("这个配置还没有文件夹对", NoticeKind.WARNING)
            return
        }

        val jobId = SyncService.newJobId()
        val prepared = config.copy(dryRun = dryRun)
        running = RunningJob(
            jobId = jobId,
            configName = prepared.name,
            dryRun = dryRun,
            pairCount = prepared.pairs.size,
            startedAtMs = System.currentTimeMillis(),
        )
        dismissNotice()

        runTask = scope.launch {
            try {
                val result = service.run(prepared, jobId = jobId, observer = WindowObserver())
                reloadJobs()
                selectedJobId = result.jobId
                goTo(Section.JOBS)
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
        val head = Format.jobStatus(result.status)
        return "$head：复制 ${stats.copiedFiles} 个文件（${Format.bytes(stats.copiedBytes)}），" +
            "删除 ${stats.deletedFiles}，失败 ${stats.failures}，耗时 ${Format.duration(result.durationMs)}"
    }

    private fun toneFor(status: JobStatus): NoticeKind = when (status) {
        JobStatus.COMPLETED -> NoticeKind.SUCCESS
        JobStatus.PARTIAL_FAILED -> NoticeKind.WARNING
        else -> NoticeKind.ERROR
    }

    private inner class WindowObserver : SyncObserver {
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
        if (text.isEmpty()) {
            peerError = "先填 HOST:PORT"
            return
        }
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
                settings.lastPeer = text
                settings.save()
                notify("已连接 ${session.nodeName}", NoticeKind.SUCCESS)
            } catch (failure: Throwable) {
                peerError = failure.message ?: "连接失败"
                notify("连接失败：${peerError}", NoticeKind.ERROR)
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
        rememberServer()
    }

    fun removeRoot(path: String) {
        rootDraft = rootDraft.filterNot { it == path }
        rememberServer()
    }

    fun startServer(port: Int, nodeName: String) {
        val state = server.start(
            roots = rootDraft,
            port = if (port <= 0) Protocol.DEFAULT_PORT else port,
            nodeName = nodeName,
        )
        if (state.running) {
            settings.sharePort = state.port
            settings.serverNodeName = state.nodeName
            settings.sharedRoots = state.roots
            settings.save()
            notify("已在端口 ${state.port} 共享 ${state.roots.size} 个文件夹", NoticeKind.SUCCESS)
        } else {
            notify(state.error, NoticeKind.ERROR)
        }
    }

    fun stopServer() {
        server.stop()
        notify("已停止共享", NoticeKind.INFO)
    }

    private fun rememberServer() {
        settings.sharedRoots = rootDraft
        settings.save()
    }

    // ----------------------------------------------------------------------- text

    fun clearLogs() {
        service.log.clear()
        logs = emptyList()
    }

    /** What the status strip under the title says. */
    fun statusLine(server: ServerState = serverState): String {
        val parts = mutableListOf<String>()
        parts += if (server.running) "共享中 :${server.port}" else "未共享"
        parts += if (peers.isEmpty()) "未连接远端" else "已连接 ${peers.size} 台设备"
        running?.let { parts += "${it.label}「${it.configName}」" }
        return parts.joinToString("  ·  ")
    }

    companion object {
        const val LOG_LIMIT: Int = 2_000

        /** The state directory the window will use unless `--state-dir` says otherwise. */
        fun defaultStateDirectory(): String = ConfigStore.defaultBase().toString()
    }
}
