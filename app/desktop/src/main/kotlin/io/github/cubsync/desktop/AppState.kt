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
import io.github.cubsync.service.AppSettings
import io.github.cubsync.service.ConfigStore
import io.github.cubsync.service.DisplayMode
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
import io.github.cubsync.service.Scope
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class NoticeKind { INFO, SUCCESS, WARNING, ERROR }

data class Notice(val text: String, val kind: NoticeKind, val atMs: Long = System.currentTimeMillis())

/** Which layer of the sync section is open. The desktop puts them side by side. */
enum class SyncLayer { LIST, DETAIL, PREVIEW }

/** The two halves of the jobs section. On the desktop the log also has its own entry. */
enum class JobsSegment(val label: String) { JOBS("作业"), LOGS("日志") }

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

    /** Whole pairs finished, which is the only progress the engine reports. */
    val percent: Int get() = if (pairCount <= 0) 0 else finishedPairs * 100 / pairCount
}

/** Which pane's ⋮ menu is open, if any. */
data class PaneMenu(val side: Side)

/** The long-press action panel for one row. */
data class ActionSheet(val side: Side, val name: String, val isDirectory: Boolean)

/**
 * Everything the window is showing.
 *
 * One object rather than a view model per screen, because the screens are not
 * independent: a run started on the sync screen has to appear on the jobs screen, and a
 * peer connected on the connect screen is what the pair editor offers as a destination.
 * Keeping that in one place is what stops the two from disagreeing.
 *
 * The preference fields are read through [liveSettings], which registers the Compose
 * read that makes a change redraw the window. Settings live in the shared layer because
 * both ends read the same ones, so this class cannot own them; it can only say when they
 * changed.
 */
class AppState(
    val service: SyncService,
    val server: ServerController,
    val settings: DesktopSettings,
    private val scope: CoroutineScope,
    /** Where elapsed times come from; pinned by the screenshot test, untouched by the app. */
    val now: () -> Long = System::currentTimeMillis,
) {

    var section by mutableStateOf(Section.CONNECT)

    var configs by mutableStateOf<List<SyncConfig>>(emptyList())
    var selectedConfigId by mutableStateOf<String?>(null)
    var syncLayer by mutableStateOf(SyncLayer.LIST)
    var syncSearch by mutableStateOf("")
    var preview by mutableStateOf<SyncPreview?>(null)
    var previewing by mutableStateOf(false)

    var jobs by mutableStateOf<List<JobRecord>>(emptyList())
    var selectedJobId by mutableStateOf<String?>(null)
    var jobsSegment by mutableStateOf(JobsSegment.JOBS)

    var logs by mutableStateOf<List<LogEntry>>(emptyList())
    var logMinimumLevel by mutableStateOf(LogLevel.INFO)

    /** The log page's filter box, which the heading draws and the body reads. */
    var logQuery by mutableStateOf("")

    var peers by mutableStateOf<List<PeerSession>>(emptyList())
    var peerError by mutableStateOf<String?>(null)

    /** The address box's two halves, kept apart so a port is optional and a colon is not. */
    var peerHost by mutableStateOf("")
    var peerPortText by mutableStateOf("")
    var peerDraft by mutableStateOf("")
    var connecting by mutableStateOf(false)

    /** USB: what adb can see, and the last thing it said. */
    var adbDevices by mutableStateOf<List<io.github.cubsync.service.Adb.Device>>(emptyList())
    var adbNote by mutableStateOf<String?>(null)

    var running by mutableStateOf<RunningJob?>(null)
    var notice by mutableStateOf<Notice?>(null)

    /** Roots the server editor is holding before it is started. */
    var rootDraft by mutableStateOf<List<String>>(emptyList())

    // ---------------------------------------------------------------- transfer page

    var drawerOpen by mutableStateOf(false)
    var displayBarOpen by mutableStateOf(false)
    var paneMenu by mutableStateOf<PaneMenu?>(null)

    /** The 显示方式 drop-down on the transfer heading. */
    var displayMenu by mutableStateOf(false)

    /** 展开: the paths this pane has been to, listed beside the star. */
    var historyMenu by mutableStateOf<Side?>(null)
    var actionSheet by mutableStateOf<ActionSheet?>(null)

    /** Where the context menu was asked for, in window coordinates. */
    var actionSheetAt by mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)

    /** The transfer screen's top-left corner, so a pointer can be turned into an offset. */
    var screenOrigin by mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)
    var editingAddress by mutableStateOf<Side?>(null)
    var renaming by mutableStateOf<String?>(null)
    var detailOf by mutableStateOf<Pair<Side, String>?>(null)
    var nameCounter by mutableStateOf(0)

    private var runTask: Job? = null
    private var logSubscription: AutoCloseable? = null

    /**
     * The live settings, read through a revision counter.
     *
     * Settings belong to the shared layer; this only makes reading them observable, so a
     * checkbox that writes one redraws the screen that shows it.
     */
    val liveSettings: AppSettings get() = service.settings.also { settingsRevision }

    var settingsRevision by mutableStateOf(0)
        private set

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
        peerDraft = service.settings.lastPeer
        service.settings.lastPeer.substringBeforeLast(':').takeIf { it.isNotEmpty() }?.let { peerHost = it }
        service.settings.lastPeer.substringAfterLast(':', "").toIntOrNull()?.let { peerPortText = it.toString() }
        // A server with nothing shared cannot be started, so the first run seeds the list
        // with the one folder every machine has. 添加文件夹 changes it afterwards; the
        // point is that 启动服务端 works before anybody has been taught where to click.
        if (service.settings.sharedRoots.isEmpty()) {
            localRoots().firstOrNull()?.let { service.settings.sharedRoots = listOf(it) }
        }
        rootDraft = service.settings.sharedRoots
    }

    fun close() {
        logSubscription?.close()
        logSubscription = null
        runTask?.cancel()
        service.close()
        server.stop()
    }

    /** Writes a setting and asks the window to look again. */
    fun changeSettings(change: (AppSettings) -> Unit) {
        change(service.settings)
        settingsRevision++
        if (section == Section.TRANSFER) service.browsing.resort()
    }

    // ---------------------------------------------------------------- navigation

    fun goTo(target: Section) {
        section = target
        if (target == Section.TRANSFER) openSomethingToLookAt()
    }

    /**
     * 传输 opens on something real.
     *
     * Done when the screen is first shown rather than in the constructor: opening a pane
     * starts a directory scan, and a scan the window did not ask for is a scan that can
     * race the one it did. An empty address is not a starting state — it is a pane that
     * looks broken, which is what the first screenshot of 0.3.3 showed — so this runs
     * exactly once, on the way in.
     */
    private fun openSomethingToLookAt() {
        if (service.browsing.current(Side.LEFT).path.isNotBlank()) return
        localRoots().firstOrNull()?.let { service.browsing.open(Side.LEFT, EndpointId.LOCAL, it) }
    }

    fun selectConfig(configId: String?) {
        selectedConfigId = configId
        if (configId != null) syncLayer = SyncLayer.DETAIL
    }

    fun selectJob(jobId: String?) {
        selectedJobId = jobId
    }

    val selectedConfig: SyncConfig?
        get() = selectedConfigId?.let { id -> configs.firstOrNull { it.configId == id } }

    val selectedJob: JobRecord?
        get() = selectedJobId?.let { id -> jobs.firstOrNull { it.jobId == id } }

    /** The job detail for the selected run, built once per frame from the same factory. */
    val selectedJobDetail: JobDetails?
        get() = selectedJob?.let { JobDetailsFactory.of(it, service.log) }

    fun matchingConfigs(): List<SyncConfig> {
        val needle = syncSearch.trim()
        if (needle.isEmpty()) return configs
        return configs.filter { it.name.contains(needle, ignoreCase = true) }
    }

    // ------------------------------------------------------------------ messages

    /**
     * Shows a notice for four seconds.
     *
     * It used to stay until it was clicked, which meant a window could hold a sentence from
     * ten minutes ago — the person who wrote it had already read it. A newer notice cancels
     * the older timer, so the last thing said is the thing that stays.
     */
    private var noticeJob: Job? = null

    fun notify(text: String, kind: NoticeKind = NoticeKind.INFO) {
        notice = Notice(text, kind)
        noticeJob?.cancel()
        noticeJob = scope.launch {
            delay(4_000)
            if (notice?.text == text) notice = null
        }
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

    /**
     * A new configuration.
     *
     * The policy defaults come from the model rather than from the settings screen,
     * because the design moved 同步方向 / 比较方式 / 冲突 / 删除 out of settings and into
     * the configuration they belong to — the same numbers apply to every configuration,
     * so asking for them once per install never made sense.
     */
    fun newConfigDraft(): SyncConfig = SyncConfig(
        configId = Ids.newConfigId(),
        name = newConfigName(),
        pairs = emptyList(),
    )

    private fun newConfigName(): String {
        val base = "新建同步"
        val taken = configs.map { it.name }.toSet()
        return PathTools.uniqueName(taken, base)
    }

    /** 另存为: the same configuration under a name that is free. */
    fun duplicateConfig(config: SyncConfig) {
        val copy = config.copy(
            configId = Ids.newConfigId(),
            name = PathTools.uniqueName(configs.map { it.name }.toSet(), "${config.name} 副本"),
        )
        saveConfig(copy)
    }

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
                syncLayer = SyncLayer.DETAIL
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
     * front ends read the same ones; this is how a screen announces that it changed one,
     * so the layout knows to look again.
     */
    var browserRevision by mutableStateOf(0)
        private set

    fun touchBrowsing() {
        browserRevision++
    }

    /** The session both panes are a view of. */
    val browsing get() = service.browsing

    fun openPane(side: Side, path: String, backendId: String = EndpointId.LOCAL) {
        service.browsing.open(side, backendId, path)
        service.settings.rememberFolder(path)
        settingsRevision++
    }

    fun transfer(from: Side) {
        service.browsing.transfer(from) { outcome ->
            val tone = if (outcome.ok) NoticeKind.SUCCESS else NoticeKind.WARNING
            val text = when {
                outcome.cancelled -> "已取消：传送了 ${outcome.copied} 项"
                !outcome.ok -> "传送了 ${outcome.copied} 项，${outcome.failures.size} 项失败：${outcome.failures.first()}"
                outcome.copied == 0 -> "没有需要传送的内容"
                else -> "已传送 ${outcome.copied} 项（${Format.bytes(outcome.bytes)}）"
            }
            notify(text, tone)
            touchBrowsing()
        }
    }

    fun toggleFavourite(side: Side) {
        val pane = service.browsing.current(side)
        val device = if (pane.backendId == EndpointId.LOCAL) Words.LOCAL else peerFor(pane.backendId)?.nodeName ?: Words.REMOTE
        val favourite = Favorite(device, pane.backendId, pane.path)
        service.settings.toggleFavourite(favourite)
        settingsRevision++
        notify(
            if (service.settings.isFavourite(pane.backendId, pane.path)) "已收藏 ${pane.path}" else "已取消收藏",
            NoticeKind.INFO,
        )
    }

    /** 收藏夹's `+`: a folder added by address, not only the one a pane happens to show. */
    var favouriteDraft by mutableStateOf<String?>(null)

    /** Which favourite's context menu is open, and where it opened. */
    var favouriteMenu by mutableStateOf<Favorite?>(null)
    var favouriteMenuAt by mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)

    /** The 收藏夹 dialogs: add a path, a new group, editing one row, or the panel settings. */
    sealed interface FavDialog {
        data object AddPath : FavDialog
        data object NewGroup : FavDialog
        data class Edit(val favourite: Favorite) : FavDialog
        data object Settings : FavDialog
    }

    var favouriteDialog by mutableStateOf<FavDialog?>(null)

    /** 收藏夹's own ⋮: add a path, a group, an account, or open its settings. */
    var favouriteHeaderMenu by mutableStateOf(false)

    /** 排序模式: rows become draggable and the star is out of the way. */
    var favouriteSorting by mutableStateOf(false)

    fun addFavourite(path: String) {
        val clean = io.github.cubsync.service.PathTools.normalizeRoot(path.trim())
        if (clean.isEmpty()) return
        val side = liveSettings.activeSide
        val pane = service.browsing.current(side)
        val backendId = if (pane.backendId == EndpointId.LOCAL) EndpointId.LOCAL else pane.backendId
        val device = if (backendId == EndpointId.LOCAL) Words.LOCAL else peerFor(backendId)?.nodeName ?: Words.REMOTE
        service.settings.toggleFavourite(Favorite(device, backendId, clean))
        // toggle means "add"; if it was already there it has just been removed, so put it
        // back — pressing + on a folder that is already starred should not unstar it.
        if (!service.settings.isFavourite(backendId, clean)) {
            service.settings.toggleFavourite(Favorite(device, backendId, clean))
        }
        settingsRevision++
        favouriteDraft = null
        notify("已收藏 $clean", NoticeKind.SUCCESS)
    }

    fun removeFavourite(favourite: Favorite) {
        service.settings.removeFavourite(favourite)
        settingsRevision++
        notify("已取消收藏 ${favourite.path}", NoticeKind.INFO)
    }

    fun renameFavourite(favourite: Favorite, label: String, group: String) {
        service.settings.updateFavourite(favourite, label = label, group = group)
        settingsRevision++
        notify("已保存「${label.ifEmpty { favourite.path }}」", NoticeKind.SUCCESS)
    }

    fun addFavouriteGroup(name: String) {
        val created = service.settings.addFavouriteGroup(name)
        if (created.isEmpty()) return
        settingsRevision++
        notify("已新建分组「$created」", NoticeKind.SUCCESS)
    }

    /** Moves one row up or down inside the drawer — the keyboard/no-drag way to order them. */
    fun moveFavourite(favourite: Favorite, by: Int) {
        val all = service.settings.favourites.toMutableList()
        val from = all.indexOfFirst { it.key == favourite.key }
        if (from < 0) return
        val to = (from + by).coerceIn(0, all.lastIndex)
        if (to == from) return
        val moved = all.removeAt(from)
        all.add(to, moved)
        service.settings.reorderFavourites(all)
        settingsRevision++
    }

    /** Drops a row onto another row's place, which is what a drag ends with. */
    fun dropFavourite(from: Int, to: Int) {
        val all = service.settings.favourites.toMutableList()
        if (from !in all.indices || to !in all.indices || from == to) return
        val moved = all.removeAt(from)
        all.add(to, moved)
        service.settings.reorderFavourites(all)
        settingsRevision++
    }

    /**
     * Opens a favourite in whichever pane is focused.
     *
     * It used to always open on the left, so a favourite of a folder on the phone landed in
     * 本端栏 and failed. The focused pane is the one the next click would have acted on,
     * which is where a person expects it to appear.
     */
    fun openFavourite(favourite: Favorite) {
        goTo(Section.TRANSFER)
        val side = liveSettings.activeSide
        service.browsing.open(side, favourite.backendId, favourite.path)
        touchBrowsing()
    }

    fun applyPrefs(side: Side, scope: Scope, change: (io.github.cubsync.service.BrowsePrefs) -> io.github.cubsync.service.BrowsePrefs) {
        val prefs = service.browsing.prefsOf(side)
        service.browsing.applyPrefs(side, scope, change(prefs))
        touchBrowsing()
        settingsRevision++
    }

    fun setDisplayMode(side: Side, mode: DisplayMode) {
        service.browsing.setDisplayMode(side, mode)
        touchBrowsing()
        settingsRevision++
    }

    fun newFolder(side: Side) {
        service.browsing.makeFolder(side, Words.NEW_FOLDER) { error ->
            if (error == null) {
                notify("已新建文件夹", NoticeKind.SUCCESS)
            } else {
                notify("新建文件夹失败：$error", NoticeKind.ERROR)
            }
            touchBrowsing()
        }
    }

    fun rename(side: Side, from: String, to: String) {
        service.browsing.renameEntry(side, from, to) { error ->
            if (error == null) notify("已重命名为 $to", NoticeKind.SUCCESS) else notify(error, NoticeKind.ERROR)
            touchBrowsing()
        }
        renaming = null
    }

    fun deleteSelection(side: Side) {
        val pane = service.browsing.current(side)
        val names = pane.targetNames()
        if (names.isEmpty()) {
            notify(Words.EMPTY_FOLDER, NoticeKind.WARNING)
            return
        }
        service.browsing.delete(side, names, toTrash = service.settings.deleteToTrash) { done, failures ->
            notify(
                if (failures.isEmpty()) {
                    "已删除 $done 项，进${Words.TRASH}"
                } else {
                    "删除了 $done 项，${failures.size} 项失败：${failures.first()}"
                },
                if (failures.isEmpty()) NoticeKind.SUCCESS else NoticeKind.WARNING,
            )
            touchBrowsing()
        }
    }

    /**
     * Folders worth opening in a pane.
     *
     * The folders this machine shares come first, because those are the ones somebody has
     * deliberately decided to work with; then the folders recently opened; then the home
     * folder and the drives, so a pane is never stuck with nowhere to go.
     */
    fun localRoots(): List<String> {
        val shared = service.settings.sharedRoots
        val home = runCatching { java.nio.file.Paths.get(System.getProperty("user.home")).toString() }.getOrNull()
        val drives = runCatching { java.io.File.listRoots().map { it.absolutePath }.toList() }.getOrDefault(emptyList())
        return (shared + service.settings.recentFolders + listOfNotNull(home) + drives).distinct()
    }

    /** The drawer's contents, rebuilt on demand from live state. */
    fun drawer(): DrawerModel = service.drawer(serverState)

    /**
     * A pair with the sensible default: the local side is this machine and the other side
     * is the peer that is connected, if there is one.
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
            startedAtMs = now(),
        )
        dismissNotice()

        val gate = io.github.cubsync.core.engine.PauseGate()
        runGate = gate
        runPaused = false
        runTask = scope.launch {
            try {
                val result = service.run(prepared, jobId = jobId, observer = WindowObserver(), gate = gate)
                reloadJobs()
                selectedJobId = result.jobId
                jobsSegment = JobsSegment.JOBS
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

    /** 暂停 / 继续 the run in flight. Pauses between blocks; nothing is torn down. */
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

    /** Compares without writing, and shows what would happen. */
    fun previewConfig(config: SyncConfig) {
        if (config.pairs.isEmpty()) {
            notify("这个配置还没有文件夹对", NoticeKind.WARNING)
            return
        }
        previewing = true
        syncLayer = SyncLayer.PREVIEW
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

    /** Looks for devices on the other end of a cable. */
    fun refreshAdb() {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val found = io.github.cubsync.service.Adb.devices()
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                adbDevices = found
                adbNote = when {
                    !io.github.cubsync.service.Adb.available() -> "没有找到 adb：装好 platform-tools 并加进 PATH"
                    found.isEmpty() -> "没有已连接的 USB 设备"
                    else -> null
                }
            }
        }
    }

    /**
     * Connects over the cable: `adb forward` first, then the ordinary TCP handshake.
     *
     * The phone is already serving on its share port; the cable only changes the route, so
     * after the forward this is the same code path as Wi-Fi and the rest of the screen does
     * not need to know which one is in use.
     */
    fun connectUsb(serial: String?, port: Int) {
        val target = if (port <= 0) io.github.cubsync.core.protocol.Protocol.DEFAULT_PORT else port
        connecting = true
        peerError = null
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val failure = io.github.cubsync.service.Adb.forward(target, target, serial)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                connecting = false
                if (failure != null) {
                    adbNote = failure
                    peerError = failure
                } else {
                    adbNote = "已转发 tcp:$target → 设备 tcp:$target"
                    connectPeer("127.0.0.1:$target")
                }
            }
        }
    }

    /** The address box: a host, and a port that defaults to the protocol's. */
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
                settingsRevision++
                // The far end just said which folders it offers; the right pane opens on one
                // of them, because a pane that stays empty after a successful handshake
                // reads as a failed handshake.
                // Every shared folder, as a list — the pane's empty path means "the roots".
                service.browsing.open(Side.RIGHT, session.backendId, "")
                notify(
                    "已连接 ${session.nodeName} · ${session.roots.size} 个共享文件夹",
                    NoticeKind.SUCCESS,
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

    /** Re-reads what the peers expose; the button on the connect screen says 刷新设备. */
    fun refreshPeers() {
        val current = peers
        if (current.isEmpty()) {
            notify("还没有连上任何设备", NoticeKind.INFO)
            return
        }
        scope.launch {
            peers = current.mapNotNull { session ->
                runCatching { service.browsePool.probe(session.host, session.port) }
                    .getOrElse { null }
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
        rememberServer()
    }

    /**
     * Turns one interface on or off for the server.
     *
     * The last one may not be turned off: a machine with no interface cannot be reached, so
     * the switch that would do it reports why instead of leaving a server nobody can dial.
     */
    fun toggleChannelNic(name: String) {
        val current = service.settings.channelNics.ifEmpty { io.github.cubsync.service.Peers.localNics().map { it.name } }
        val next = if (name in current) current - name else current + name
        if (next.isEmpty()) {
            notify("至少要留一张网卡", NoticeKind.WARNING)
            return
        }
        changeSettings { it.channelNics = next }
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
            service.settings.sharePort = state.port
            service.settings.serverNodeName = state.nodeName
            service.settings.sharedRoots = state.roots
            settingsRevision++
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
        service.settings.sharedRoots = rootDraft
        settingsRevision++
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
        parts += if (peers.isEmpty()) "未连接${Words.REMOTE}" else "已连接 ${peers.size} 台设备"
        running?.let { parts += "${it.label}「${it.configName}」" }
        return parts.joinToString("  ·  ")
    }

    companion object {
        const val LOG_LIMIT: Int = 2_000

        /** The state directory the window will use unless `--state-dir` says otherwise. */
        fun defaultStateDirectory(): String = ConfigStore.defaultBase().toString()
    }
}
