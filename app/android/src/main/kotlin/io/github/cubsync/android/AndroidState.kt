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
import io.github.cubsync.service.AppSettings
import io.github.cubsync.service.DrawerModel
import io.github.cubsync.service.EndpointId
import io.github.cubsync.service.Favorite
import io.github.cubsync.service.Format
import io.github.cubsync.service.JobDetails
import io.github.cubsync.service.JobDetailsFactory
import io.github.cubsync.service.JobRecord
import io.github.cubsync.service.LogEntry
import io.github.cubsync.service.LogLevel
import io.github.cubsync.service.PathTools
import io.github.cubsync.service.PeerSession
import io.github.cubsync.service.Peers
import io.github.cubsync.service.RunMode
import io.github.cubsync.service.Section
import io.github.cubsync.service.ServerController
import io.github.cubsync.service.ServerState
import io.github.cubsync.service.Side
import io.github.cubsync.service.SyncObserver
import io.github.cubsync.service.SyncPreview
import io.github.cubsync.service.SyncService
import io.github.cubsync.service.Words
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

enum class NoticeKind { INFO, SUCCESS, WARNING, ERROR }

data class Notice(val text: String, val kind: NoticeKind, val atMs: Long = System.currentTimeMillis())

/** Which half of the jobs tab is showing. On a phone the log lives inside the job. */
enum class JobsSegment(val label: String) { JOBS("作业"), LOGS("日志") }

/** A run that is in flight. */
data class RunningJob(
    val jobId: String,
    val configName: String,
    val dryRun: Boolean,
    val pairs: List<String>,
    val startedAtMs: Long,
    val currentPair: String = "",
    val finishedPairs: Int = 0,
    val bytes: Long = 0,
) {
    val pairCount: Int get() = pairs.size

    /** Whole pairs only: the engine reports a pair when it is done, not while it runs. */
    val fraction: Float get() = if (pairCount == 0) 0f else finishedPairs.toFloat() / pairCount

    val label: String get() = if (dryRun) "试运行" else "同步中"
}

/**
 * Where the phone is.
 *
 * The design's second-level screens — a configuration, a preview, a job, the folder
 * picker — are routes rather than tabs: the bottom bar keeps the parent tab lit, which is
 * the rule that stops a phone from ever showing a page that belongs to nothing.
 */
sealed interface Route {
    data object Tabs : Route
    data class Config(val configId: String) : Route
    data class Preview(val configId: String) : Route

    /** 正在同步: its own screen while a run is in flight, as the design has it. */
    data class Running(val jobId: String) : Route
    data class Job(val jobId: String) : Route
    data class PickFolder(val side: Side) : Route
}

/** A row's long-press panel. */
data class ActionSheet(val side: Side, val name: String, val isDirectory: Boolean)

/**
 * Everything the phone is showing.
 *
 * One object for the same reason the desktop has one: a run started on one screen has to
 * appear on another, and a peer connected on one screen is what the folder picker offers.
 * The preferences themselves live in the shared layer; this only says when one changed.
 */
class AndroidAppState(
    val service: SyncService,
    val server: ServerController,
    val scope: CoroutineScope,
    val appDirectory: File,
) {

    var tab by mutableStateOf(Section.CONNECT)
    var route by mutableStateOf<Route>(Route.Tabs)

    var configs by mutableStateOf<List<SyncConfig>>(emptyList())
    var selectedConfigId by mutableStateOf<String?>(null)
    var preview by mutableStateOf<SyncPreview?>(null)
    var previewing by mutableStateOf(false)

    var jobs by mutableStateOf<List<JobRecord>>(emptyList())
    var jobsSegment by mutableStateOf(JobsSegment.JOBS)

    var logs by mutableStateOf<List<LogEntry>>(emptyList())
    var logMinimumLevel by mutableStateOf(LogLevel.INFO)

    var peers by mutableStateOf<List<PeerSession>>(emptyList())
    var peerError by mutableStateOf<String?>(null)
    var peerDraft by mutableStateOf("")
    var peerHost by mutableStateOf("")
    var peerPortText by mutableStateOf("")
    var connecting by mutableStateOf(false)

    var running by mutableStateOf<RunningJob?>(null)
    var notice by mutableStateOf<Notice?>(null)

    var rootDraft by mutableStateOf<List<String>>(emptyList())
    var rootDraftInput by mutableStateOf("")

    // ------------------------------------------------------------------ transfer

    var drawerOpen by mutableStateOf(false)
    var displayBarOpen by mutableStateOf(false)
    var paneMenu by mutableStateOf<Side?>(null)
    var actionSheet by mutableStateOf<ActionSheet?>(null)
    var showSoloLocal by mutableStateOf(false)
    var showSoloRemote by mutableStateOf(false)

    /**
     * 副行最右那颗 ⋮: 新建文件夹 / 多选 / 收藏当前目录 / 收藏列表 / 放大 / 缩小.
     *
     * A separate panel from the per-pane ⋮, which is 排序与作用范围 — the prototype keeps
     * them apart so one control does not mean two things depending on where it was tapped.
     */
    var moreSheet by mutableStateOf(false)

    /** The top bar's ⋮ menu, which is a different list on every screen. */
    var screenMenu by mutableStateOf(false)

    /** The two sheets the ⋮ menu opens on 连接: an address to dial, and the shared roots. */
    var connectSheet by mutableStateOf(false)
    var sharedSheet by mutableStateOf(false)

    /**
     * Bumped when a browsing or settings preference changes.
     *
     * The preferences belong to the shared layer as plain mutable state, because both
     * ends read the same ones; this is how a screen announces that it changed one.
     */
    var revision by mutableStateOf(0)
        private set

    val liveSettings: AppSettings get() = service.settings.also { revision }

    private var runTask: Job? = null
    private var logSubscription: AutoCloseable? = null

    init {
        reloadConfigs()
        reloadJobs()
        logs = service.log.entries()
        logSubscription = service.log.subscribe { entry ->
            val next = logs + entry
            logs = if (next.size > LOG_LIMIT) next.takeLast(LOG_LIMIT) else next
        }
        peerDraft = service.settings.lastPeer

        // 启动服务端 needs something to serve, and a phone that has never been told where
        // its folders are would answer "choose at least one folder to share" to its owner
        // with no way to comply. The one folder every phone has is seeded instead, and
        // 抽屉 → 根 is where it is changed.
        if (service.settings.sharedRoots.isEmpty()) {
            usableRoot()?.let { service.settings.sharedRoots = listOf(it) }
        }
        rootDraft = service.settings.sharedRoots

        // Tell the backend what this phone can actually open. Without this the local
        // backend exposes no roots, so 上一层 had no boundary to stop at and walked from
        // /storage/emulated/0 up to / — three levels the process cannot read.
        localRoots().forEach { service.browsePool.registerLocalRoot(it) }
    }

    fun close() {
        logSubscription?.close()
        logSubscription = null
        runTask?.cancel()
        service.close()
        server.stop()
    }

    fun touch() {
        revision++
    }

    fun changeSettings(change: (AppSettings) -> Unit) {
        change(service.settings)
        revision++
        service.browsing.resort()
    }

    // ---------------------------------------------------------------- navigation

    /** Where the last few sections came from, so ← goes back the way it came. */
    private val sectionHistory = ArrayDeque<Section>()
    private var goingBack = false

    fun go(target: Section) {
        if (goingBack) {
            goingBack = false
        } else if (target != tab) {
            sectionHistory.remove(target)
            sectionHistory.addLast(tab)
            if (sectionHistory.size > 12) sectionHistory.removeFirst()
        }
        tab = target
        route = Route.Tabs
        drawerOpen = false
        if (target == Section.TRANSFER) openSomethingToLookAt()
    }

    /**
     * 传输 opens on something real rather than on `/`.
     *
     * A pane on an empty path is not a starting state, it is one that reads 这个文件夹是空的
     * and looks broken. Done on the way in rather than in the constructor, so that the scan
     * cannot race one the caller asked for.
     */
    private fun openSomethingToLookAt() {
        val left = service.browsing.current(Side.LEFT)
        if (left.path.isBlank() || left.backendId != EndpointId.LOCAL) {
            (rootDraft.firstOrNull() ?: usableRoot())?.let {
                service.browsing.open(Side.LEFT, EndpointId.LOCAL, it)
            }
        }
        // The right pane is 远端. When a peer is connected it opens on that peer rather
        // than on this phone's own disk, which is what made 远端栏 show "/" and then refuse
        // a path from the other machine: it was still the local backend.
        val right = service.browsing.current(Side.RIGHT)
        if (right.backendId == EndpointId.LOCAL) {
            peers.firstOrNull()?.let { peer ->
                service.browsing.open(Side.RIGHT, peer.backendId, "")
            }
        }
    }

    fun open(route: Route) {
        this.route = route
    }

    fun back() {
        route = Route.Tabs
    }

    /**
     * The system back button, in the order a person would undo things.
     *
     * Android's back used to leave the application from every screen, so a folder entered
     * in 传输 closed CubSync instead of going back to the folder. Panels close, then the
     * screen, then the section, then one folder up — and only when there is nothing left to
     * undo does the system get the event and finish the activity.
     */
    fun handleBack() {
        when {
            drawerOpen -> drawerOpen = false
            moreSheet -> moreSheet = false
            connectSheet -> connectSheet = false
            sharedSheet -> sharedSheet = false
            screenMenu -> screenMenu = false
            paneMenu != null -> paneMenu = null
            actionSheet != null -> actionSheet = null
            route !is Route.Tabs -> route = Route.Tabs
            sectionHistory.isNotEmpty() -> {
                // Popped rather than pushed: going back must not record the place it is
                // leaving, or ← would bounce between two screens forever.
                goingBack = true
                go(sectionHistory.removeLast())
            }
            else -> {
                val side = liveSettings.activeSide
                service.browsing.up(side)
                touch()
            }
        }
    }

    /** Whether back has something to undo; when it does not, the application closes. */
    val canGoBack: Boolean
        get() {
            if (drawerOpen || moreSheet || connectSheet || sharedSheet || screenMenu ||
                paneMenu != null || actionSheet != null || route !is Route.Tabs ||
                sectionHistory.isNotEmpty()
            ) {
                return true
            }
            val side = liveSettings.activeSide
            val pane = service.browsing.current(side)
            if (pane.path.isBlank()) return false
            val roots = service.browsing.exposedRoots(pane.backendId)
            val root = roots.filter { pane.path == it || pane.path.startsWith("$it/") }
                .maxByOrNull { it.length }
            // At the exposed root there is no parent to show, so back means "leave".
            return root == null || pane.path != root
        }

    val selectedConfig: SyncConfig?
        get() = selectedConfigId?.let { id -> configs.firstOrNull { it.configId == id } }

    fun matchingConfigs(needle: String): List<SyncConfig> {
        val text = needle.trim()
        if (text.isEmpty()) return configs
        return configs.filter { it.name.contains(text, ignoreCase = true) }
    }

    fun selectedJob(jobId: String): JobRecord? = jobs.firstOrNull { it.jobId == jobId }

    fun detailsOf(job: JobRecord): JobDetails = JobDetailsFactory.of(job, service.log)

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
    }

    fun reloadJobs() {
        jobs = service.history.all()
    }

    fun newConfigDraft(): SyncConfig = SyncConfig(
        configId = Ids.newConfigId(),
        name = PathTools.uniqueName(configs.map { it.name }.toSet(), "新建同步"),
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

    fun duplicateConfig(config: SyncConfig) {
        saveConfig(
            config.copy(
                configId = Ids.newConfigId(),
                name = PathTools.uniqueName(configs.map { it.name }.toSet(), "${config.name} 副本"),
            ),
        )
    }

    fun deleteConfig(configId: String) {
        service.configs.delete(configId)
        reloadConfigs()
        if (route is Route.Config && (route as Route.Config).configId == configId) back()
        notify("已删除配置", NoticeKind.INFO)
    }

    fun exportConfig(config: SyncConfig) {
        val target = File(appDirectory, "${config.name.ifBlank { config.configId }}.cubcfg")
        runCatching { service.configs.export(config, target.toPath()) }
            .onSuccess { notify("已导出到 ${target.absolutePath}", NoticeKind.SUCCESS) }
            .onFailure { notify("导出失败：${it.message}", NoticeKind.ERROR) }
    }

    // ------------------------------------------------------------------- browsing

    val browsing get() = service.browsing

    fun openPane(side: Side, path: String, backendId: String = EndpointId.LOCAL) {
        service.browsing.open(side, backendId, path)
        service.settings.rememberFolder(path)
        revision++
    }

    fun transfer(from: Side) {
        service.browsing.transfer(from) { outcome ->
            val tone = if (outcome.ok) NoticeKind.SUCCESS else NoticeKind.WARNING
            val text = when {
                outcome.cancelled -> "已取消：传送了 ${outcome.copied} 项"
                !outcome.ok -> "传送了 ${outcome.copied} 项，${outcome.failures.size} 项失败"
                outcome.copied == 0 -> "没有需要传送的内容"
                else -> "已传送 ${outcome.copied} 项（${Format.bytes(outcome.bytes)}）"
            }
            notify(text, tone)
            revision++
        }
    }

    fun toggleFavourite(side: Side) {
        val pane = service.browsing.current(side)
        val device = if (pane.backendId == EndpointId.LOCAL) {
            Words.LOCAL
        } else {
            peerFor(pane.backendId)?.nodeName ?: Words.REMOTE
        }
        val favourite = Favorite(device, pane.backendId, pane.path)
        service.settings.toggleFavourite(favourite)
        revision++
        notify(
            if (service.settings.isFavourite(device, pane.path)) "已收藏 ${pane.path}" else "已取消收藏",
            NoticeKind.INFO,
        )
    }

    fun applyPrefs(side: Side, scope: io.github.cubsync.service.Scope, change: (io.github.cubsync.service.BrowsePrefs) -> io.github.cubsync.service.BrowsePrefs) {
        service.browsing.applyPrefs(side, scope, change(service.browsing.prefsOf(side)))
        revision++
    }

    fun setDisplayMode(side: Side, mode: io.github.cubsync.service.DisplayMode) {
        service.browsing.setDisplayMode(side, mode)
        revision++
    }

    /** 多选: the sub-row's ⋮ toggles it, and the row tap follows it. */
    fun toggleMultiSelect() {
        service.browsing.toggleMultiSelect()
        revision++
        notify(
            if (service.browsing.multiSelect.value) "多选：单击行＝勾选，不再进入目录" else "多选已关：单击目录＝进入",
            NoticeKind.INFO,
        )
    }

    fun newFolder(side: Side) {
        service.browsing.makeFolder(side, Words.NEW_FOLDER) { error ->
            notify(if (error == null) "已新建文件夹" else "新建文件夹失败：$error", if (error == null) NoticeKind.SUCCESS else NoticeKind.ERROR)
            revision++
        }
    }

    fun rename(side: Side, from: String, to: String) {
        service.browsing.renameEntry(side, from, to) { error ->
            notify(if (error == null) "已重命名为 $to" else error, if (error == null) NoticeKind.SUCCESS else NoticeKind.ERROR)
            revision++
        }
    }

    fun deleteSelection(side: Side) {
        val names = service.browsing.current(side).targetNames()
        if (names.isEmpty()) {
            notify(Words.EMPTY_FOLDER, NoticeKind.WARNING)
            return
        }
        service.browsing.delete(side, names, toTrash = service.settings.deleteToTrash) { done, failures ->
            notify(
                if (failures.isEmpty()) "已删除 $done 项，进${Words.TRASH}" else "删除了 $done 项，${failures.size} 项失败",
                if (failures.isEmpty()) NoticeKind.SUCCESS else NoticeKind.WARNING,
            )
            revision++
        }
    }

    /**
     * Folders worth opening in a pane.
     *
     * There is no folder picker: the platform's picker hands back a content URI and the
     * engine works in paths, so a suggestion somebody can correct beats a picker that
     * produces something the engine cannot open.
     */
    /**
     * The folder this phone offers when nobody has chosen one.
     *
     * 启动服务端 with nothing selected is a button that always fails, which is what the
     * first cut of this screen shipped. On a real device this is the shared storage the
     * phone already shows in every file manager; when even that is absent — a test runner,
     * a device with no external storage — the application's own directory stands in.
     */
    fun usableRoot(): String? = listOf(
        "/storage/emulated/0",
        "/sdcard",
        appDirectory.absolutePath,
    ).firstOrNull { runCatching { File(it).isDirectory }.getOrDefault(false) }

    fun localRoots(): List<String> {
        val shared = service.settings.sharedRoots
        val common = listOf(
            "/sdcard/DCIM",
            "/sdcard/Pictures",
            "/sdcard/Download",
            "/sdcard/Documents",
            "/sdcard/Music",
            "/sdcard/Movies",
            "/storage/emulated/0",
            appDirectory.absolutePath,
        ).filter { runCatching { File(it).isDirectory }.getOrDefault(false) }
        return (shared + service.settings.recentFolders + common).distinct()
    }

    fun drawer(): DrawerModel = service.drawer(serverState)

    /** Opens a favourite in the focused pane, which is where the next tap would go. */
    fun openFavourite(favourite: Favorite) {
        drawerOpen = false
        go(Section.TRANSFER)
        service.browsing.open(liveSettings.activeSide, favourite.backendId, favourite.path)
        touch()
    }

    /** The drawer row whose menu is open. */
    var favouriteMenu by mutableStateOf<Favorite?>(null)

    /** The pane whose 展开 list is open. */
    var historySide by mutableStateOf<Side?>(null)

    fun moveFavourite(favourite: Favorite, by: Int) {
        val all = service.settings.favourites.toMutableList()
        val from = all.indexOfFirst { it.key == favourite.key }
        if (from < 0) return
        val to = (from + by).coerceIn(0, all.lastIndex)
        if (to == from) return
        val moved = all.removeAt(from)
        all.add(to, moved)
        service.settings.reorderFavourites(all)
        revision++
    }

    fun renameFavourite(favourite: Favorite, label: String, group: String) {
        service.settings.updateFavourite(favourite, label = label, group = group)
        revision++
    }

    fun addFavouriteGroup(name: String) {
        val created = service.settings.addFavouriteGroup(name)
        if (created.isNotEmpty()) revision++
    }

    fun removeFavourite(favourite: Favorite) {
        service.settings.removeFavourite(favourite)
        revision++
        notify("已取消收藏 ${favourite.path}", NoticeKind.INFO)
    }

    /** 收藏 a configuration — the star in the top bar of its screen. */
    fun toggleConfigStar() {
        val config = selectedConfig ?: return
        val favourite = Favorite(Words.LOCAL, EndpointId.LOCAL, config.configId, config.name)
        service.settings.toggleFavourite(favourite)
        revision++
        notify(
            if (service.settings.isFavourite(EndpointId.LOCAL, config.configId)) "已收藏「${config.name}」" else "已取消收藏",
            NoticeKind.INFO,
        )
    }

    /** Writes a run out as a text file, in the application's own directory. */
    fun exportReport(job: io.github.cubsync.service.JobRecord) {
        val detail = detailsOf(job)
        val text = buildString {
            appendLine(detail.title)
            appendLine(detail.headline + " —— " + detail.summary)
            appendLine()
            for ((key, value) in detail.rows) appendLine("$key\t$value")
            appendLine()
            appendLine("已传输 ${detail.transferredTotal} 项")
            for (line in detail.transferred) appendLine("  ${line.relPath}\t${Format.bytes(line.bytes)}")
            if (detail.moreTransferred > 0) appendLine("  还有 ${detail.moreTransferred} 项")
            appendLine("已跳过 ${detail.skippedTotal} 项")
            for (line in detail.skipped) appendLine("  ${line.relPath}\t${line.note}")
            if (detail.moreSkipped > 0) appendLine("  还有 ${detail.moreSkipped} 项")
        }
        val file = File(appDirectory, "${detail.title.replace(':', '-')}.txt")
        runCatching { file.writeText(text, Charsets.UTF_8) }
            .onSuccess { notify("报告已保存到 ${file.absolutePath}", NoticeKind.SUCCESS) }
            .onFailure { notify("保存失败：${it.message}", NoticeKind.ERROR) }
    }

    /** Takes a run out of the list without touching anything on disk. */
    fun forgetJob(jobId: String) {
        runCatching { service.history.forget(jobId) }
        reloadJobs()
        if (route is Route.Job && (route as Route.Job).jobId == jobId) back()
        notify("已从列表里去掉这次记录", NoticeKind.INFO)
    }

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
            pairs = prepared.pairs.map { it.name.ifEmpty { it.pairId } },
            startedAtMs = System.currentTimeMillis(),
        )
        dismissNotice()
        open(Route.Running(jobId))

        val gate = io.github.cubsync.core.engine.PauseGate()
        runGate = gate
        runPaused = false
        runTask = scope.launch {
            try {
                val result = service.run(prepared, jobId = jobId, observer = PhoneObserver(), gate = gate)
                reloadJobs()
                go(Section.JOBS)
                // The run screen becomes the job's detail screen when it finishes, which
                // is what the design does: one screen, two states.
                open(Route.Job(result.jobId))
                notify(summaryLine(result), toneFor(result.status))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                notify("已取消「${prepared.name}」", NoticeKind.WARNING)
            } catch (failure: Throwable) {
                notify("运行失败：${failure.message ?: failure::class.simpleName}", NoticeKind.ERROR)
            } finally {
                running = null
                runTask = null
                runGate = null
                runPaused = false
            }
        }
    }

    fun previewConfig(config: SyncConfig) {
        if (config.pairs.isEmpty()) {
            notify("这个配置还没有文件夹对", NoticeKind.WARNING)
            return
        }
        previewing = true
        open(Route.Preview(config.configId))
        scope.launch {
            try {
                preview = service.preview(config)
            } catch (failure: Throwable) {
                notify("预览失败：${failure.message ?: failure::class.simpleName}", NoticeKind.ERROR)
            } finally {
                previewing = false
            }
        }
    }

    /** 暂停 / 继续 the run in flight, between blocks rather than by tearing it down. */
    var runPaused by mutableStateOf(false)
        private set

    private var runGate: io.github.cubsync.core.engine.PauseGate? = null

    fun pauseRun() {
        runGate?.pause()
        runPaused = true
    }

    fun resumeRun() {
        runGate?.resume()
        runPaused = false
    }

    fun cancelRun() {
        val task = runTask ?: return
        running = null
        runPaused = false
        task.cancel()
    }

    private fun summaryLine(result: JobResult): String {
        val stats = result.stats
        return "${Format.jobStatus(result.status)}：${stats.copiedFiles} 个文件（${Format.bytes(stats.copiedBytes)}），" +
            "失败 ${stats.failures}，耗时 ${Format.duration(result.durationMs)}"
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
            running = running?.copy(
                finishedPairs = (running?.finishedPairs ?: 0) + 1,
                currentPair = "",
                bytes = 0,
            )
        }

        override fun onBytesTransferred(total: Long) {
            running = running?.copy(bytes = total)
        }
    }

    // ---------------------------------------------------------------------- peers

    /** A host and a port typed apart; an empty port means the protocol default. */
    fun connectPeerAddress() {
        val host = peerHost.trim()
        if (host.isEmpty()) {
            peerError = "先填地址"
            return
        }
        val port = peerPortText.trim().let {
            if (it.isEmpty()) io.github.cubsync.core.protocol.Protocol.DEFAULT_PORT else it.toIntOrNull()
        }
        if (port == null || port !in 1..65535) {
            peerError = "端口要填 1–65535，留空就用默认 ${io.github.cubsync.core.protocol.Protocol.DEFAULT_PORT}"
            return
        }
        connectPeer("$host:$port")
    }

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
                val session = service.connect(endpoint.host!!, endpoint.port!!)
                peers = peers.filterNot { it.backendId == session.backendId } + session
                service.settings.lastPeer = text
                revision++
                // A handshake that leaves both panes as they were is a handshake the person
                // cannot see. The right pane becomes the peer and opens on the first folder
                // it offers — the same thing the desktop does when it connects out.
                // An empty path lists every folder the other side exposes. Opening the
                // first one only hid the rest: a machine sharing two folders looked like a
                // machine sharing one.
                service.browsing.open(Side.RIGHT, session.backendId, "")
                liveSettings.activeSide = Side.RIGHT
                notify(
                    if (session.roots.isEmpty()) "已连接 ${session.nodeName}，但对面没有共享文件夹"
                    else "已连接 ${session.nodeName} · ${session.roots.size} 个共享文件夹",
                    if (session.roots.isEmpty()) NoticeKind.WARNING else NoticeKind.SUCCESS,
                )
            } catch (failure: Throwable) {
                peerError = failure.message ?: "连接失败"
                notify("连接失败：$peerError", NoticeKind.ERROR)
            } finally {
                connecting = false
            }
        }
    }

    fun disconnectPeer(backendId: String) {
        service.disconnect(backendId)
        peers = peers.filterNot { it.backendId == backendId }
    }

    fun peerFor(backendId: String): PeerSession? = peers.firstOrNull { it.backendId == backendId }

    fun refreshPeers() {
        val current = peers
        if (current.isEmpty()) {
            notify("还没有连上任何设备", NoticeKind.INFO)
            return
        }
        scope.launch {
            peers = current.mapNotNull { session ->
                runCatching { service.browsePool.probe(session.host, session.port) }.getOrElse { null }
            }
            notify("已刷新 ${peers.size} 台设备", NoticeKind.INFO)
        }
    }

    // --------------------------------------------------------------------- server

    val serverState: ServerState get() = server.state.value

    fun addRoot(path: String) {
        val clean = path.trim()
        if (clean.isEmpty()) return
        val absolute = PathTools.normalizeRoot(PathTools.absolute(clean))
        if (rootDraft.any { it == absolute }) return
        rootDraft = rootDraft + absolute
        service.settings.sharedRoots = rootDraft
        revision++
    }

    fun removeRoot(path: String) {
        rootDraft = rootDraft.filterNot { it == path }
        service.settings.sharedRoots = rootDraft
        revision++
    }

    fun startServer(port: Int, nodeName: String) {
        val state = server.start(
            roots = rootDraft,
            port = if (port <= 0) Protocol.DEFAULT_PORT else port,
            nodeName = nodeName,
        )
        if (state.running) {
            service.settings.sharePort = state.port
            service.settings.serverNodeName = state.nodeName
            revision++
            notify("已在端口 ${state.port} 共享 ${state.roots.size} 个文件夹", NoticeKind.SUCCESS)
        } else {
            notify(state.error, NoticeKind.ERROR)
        }
    }

    fun stopServer() {
        server.stop()
        // The screen reads `serverState` off the controller, which is not Compose state;
        // without this the button stayed 停止共享 and the address stayed on screen after
        // the server had already stopped.
        revision++
        notify("已停止共享", NoticeKind.INFO)
    }

    fun setRunMode(mode: RunMode) {
        changeSettings { it.runMode = mode }
        // Say what is true rather than what the label promises. The two privileged modes
        // need a backend that speaks through `su` or Shizuku, and this build does not have
        // one — so the setting is recorded, and the sentence beside it says so.
        when (mode) {
            RunMode.NORMAL -> notify("已切到普通模式", NoticeKind.INFO)
            RunMode.ROOT -> notify(
                if (suAvailable()) "已记录 Root 模式；本版引擎仍按应用权限读文件，su 只用于探测"
                else "没有拿到 Root 权限：su 不可用或被拒绝",
                NoticeKind.WARNING,
            )
            RunMode.SHIZUKU -> notify("Shizuku 需要 Shizuku 应用与它的 API；本版还没有接入", NoticeKind.WARNING)
        }
    }

    /** Whether `su` answers with uid 0. A probe, not a promise. */
    private fun suAvailable(): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        // Close its input: `su` that wants a password otherwise waits for one, and reading
        // its output first blocks the caller forever. That is what hung CI's test step —
        // the JVM there has `su` and no root.
        runCatching { process.outputStream.close() }
        val finished = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return@runCatching false
        }
        process.exitValue() == 0 &&
            process.inputStream.bufferedReader().use { it.readText() }.contains("uid=0")
    }.getOrDefault(false)

    // ------------------------------------------------------------ 传输通道

    /** The cards 传输通道 offers, taken from the system rather than invented. */
    fun nics(): List<Peers.Nic> = Peers.localNics()

    /**
     * Whether a card takes part. An empty selection means every card, so the first run
     * shows all of them on — which is also what makes the answer true.
     */
    fun nicSelected(address: String): Boolean {
        val chosen = service.settings.channelNics
        return chosen.isEmpty() || chosen.contains(address)
    }

    fun toggleNic(address: String) {
        val available = nics().map { it.address }
        val chosen = service.settings.channelNics.ifEmpty { available }
        val next = if (chosen.contains(address)) chosen - address else chosen + address
        if (next.isEmpty()) {
            notify("至少要留一张网卡，否则这台机器没法被连上", NoticeKind.WARNING)
            return
        }
        if (next.size < chosen.size) {
            notify("多链路还没实现：选中的网卡协议会协商，传输本身仍走其中一条", NoticeKind.INFO)
        }
        changeSettings { it.channelNics = next }
    }

    // ----------------------------------------------------------------------- text

    fun clearLogs() {
        service.log.clear()
        logs = emptyList()
    }

    fun statusLine(): String {
        val parts = mutableListOf<String>()
        parts += if (serverState.running) "共享中 :${serverState.port}" else "未共享"
        parts += if (peers.isEmpty()) "未连接${Words.REMOTE}" else "已连接 ${peers.size} 台"
        return parts.joinToString(" · ")
    }

    companion object {
        const val LOG_LIMIT: Int = 1_500
    }
}
