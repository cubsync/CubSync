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
import io.github.cubsync.service.ActionSheet
import io.github.cubsync.service.AppSettings
import io.github.cubsync.service.ConfigStore
import io.github.cubsync.service.DisplayMode
import io.github.cubsync.service.DrawerModel
import io.github.cubsync.service.EndpointId
import io.github.cubsync.service.FavDialog
import io.github.cubsync.service.Favorite
import io.github.cubsync.service.Format
import io.github.cubsync.service.JobDetails
import io.github.cubsync.service.JobDetailsFactory
import io.github.cubsync.service.JobRecord
import io.github.cubsync.service.JobsSegment
import io.github.cubsync.service.LogEntry
import io.github.cubsync.service.LogLevel
import io.github.cubsync.service.Notice
import io.github.cubsync.service.NoticeKind
import io.github.cubsync.service.PaneMenu
import io.github.cubsync.service.PathTools
import io.github.cubsync.service.PeerSession
import io.github.cubsync.service.RunningJob
import io.github.cubsync.service.Scope
import io.github.cubsync.service.Section
import io.github.cubsync.service.ServerController
import io.github.cubsync.service.ServerState
import io.github.cubsync.service.Side
import io.github.cubsync.service.SyncLayer
import io.github.cubsync.service.SyncObserver
import io.github.cubsync.service.SyncPreview
import io.github.cubsync.service.state.ConnectState
import io.github.cubsync.service.SyncService
import io.github.cubsync.service.Words
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
/**
 * 同步页 + 作业页的界面状态：配置列表、正在跑的作业、试运行预览。
 * 之前摊在 AppState，抽出来让"同步/作业"这一域有自己的名字，UI 读 `state.sync.xxx`。
 */
class SyncState {
    var configs by mutableStateOf<List<SyncConfig>>(emptyList())
    var selectedConfigId by mutableStateOf<String?>(null)
    var syncLayer by mutableStateOf(SyncLayer.LIST)
    var syncSearch by mutableStateOf("")
    var preview by mutableStateOf<SyncPreview?>(null)
    var previewing by mutableStateOf(false)
    var jobs by mutableStateOf<List<JobRecord>>(emptyList())
    var selectedJobId by mutableStateOf<String?>(null)
    var jobsSegment by mutableStateOf(JobsSegment.JOBS)
    var running by mutableStateOf<RunningJob?>(null)
    var runPaused by mutableStateOf(false)
}

/**
 * 日志页的界面状态：日志行、最低级别过滤、搜索框。
 */
class LogState {
    var logs by mutableStateOf<List<LogEntry>>(emptyList())
    var logMinimumLevel by mutableStateOf(LogLevel.INFO)

    /** The log page's filter box, which the heading draws and the body reads. */
    var logQuery by mutableStateOf("")
}

/**
 * 弹层/覆盖层的界面状态：通知、抽屉、菜单、动作面板、重命名、详情卡。
 * 之前摊在 AppState，抽出来让"正在弹什么"这一域有自己的名字。
 */
class OverlayState {
    var notice by mutableStateOf<Notice?>(null)
    var drawerOpen by mutableStateOf(false)
    var displayBarOpen by mutableStateOf(false)
    var paneMenu by mutableStateOf<PaneMenu?>(null)
    var displayMenu by mutableStateOf(false)
    var historyMenu by mutableStateOf<Side?>(null)
    var actionSheet by mutableStateOf<ActionSheet?>(null)
    var actionSheetAt by mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)
    var screenOrigin by mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)
    var editingAddress by mutableStateOf<Side?>(null)
    var renaming by mutableStateOf<String?>(null)
    var detailOf by mutableStateOf<Pair<Side, String>?>(null)
    var nameCounter by mutableStateOf(0)
}

/**
 * 收藏夹（侧边抽屉）的界面状态：正在编辑哪条、菜单开在哪、排序开关。
 */
class FavouriteState {
    var favouriteDraft by mutableStateOf<String?>(null)
    var favouriteMenu by mutableStateOf<Favorite?>(null)
    var favouriteMenuAt by mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)
    var favouriteDialog by mutableStateOf<FavDialog?>(null)
    var favouriteHeaderMenu by mutableStateOf(false)
    var favouriteSorting by mutableStateOf(false)
}

class AppState(
    val service: SyncService,
    val server: ServerController,
    val settings: DesktopSettings,
    private val scope: CoroutineScope,
    /** Where elapsed times come from; pinned by the screenshot test, untouched by the app. */
    val now: () -> Long = System::currentTimeMillis,
) {

    /** 连接域的状态都住在 [ConnectState]，UI 读 `state.connect.xxx`。 */
    val connect = ConnectState()

    /** 同步/作业域的状态住在 [SyncState]，UI 读 `state.sync.xxx`。 */
    val sync = SyncState()

    /** 日志域的状态住在 [LogState]，UI 读 `state.log.xxx`。 */
    val log = LogState()

    /** 弹层/覆盖层状态住在 [OverlayState]，UI 读 `state.overlay.xxx`。 */
    val overlay = OverlayState()

    /** 收藏夹状态住在 [FavouriteState]，UI 读 `state.favourites.xxx`。 */
    val favourites = FavouriteState()










    // ---------------------------------------------------------------- transfer page


    /** The 显示方式 drop-down on the transfer heading. */

    /** 展开: the paths this pane has been to, listed beside the star. */

    /** Where the context menu was asked for, in window coordinates. */

    /** The transfer screen's top-left corner, so a pointer can be turned into an offset. */

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
        log.logs = service.log.entries()
        logSubscription = service.log.subscribe { entry ->
            // The log arrives from whichever thread wrote it. Appending to a snapshot
            // list is safe from any thread, and the list is bounded so a long-sync.running
            // window cannot grow without limit.
            val next = log.logs + entry
            log.logs = if (next.size > LOG_LIMIT) next.takeLast(LOG_LIMIT) else next
        }
        connect.peerDraft = service.settings.lastPeer
        service.settings.lastPeer.substringBeforeLast(':').takeIf { it.isNotEmpty() }?.let { connect.peerHost = it }
        service.settings.lastPeer.substringAfterLast(':', "").toIntOrNull()?.let { connect.peerPortText = it.toString() }
        // A server with nothing shared cannot be started, so the first run seeds the list
        // with the one folder every machine has. 添加文件夹 changes it afterwards; the
        // point is that 启动服务端 works before anybody has been taught where to click.
        if (service.settings.sharedRoots.isEmpty()) {
            localRoots().firstOrNull()?.let { service.settings.sharedRoots = listOf(it) }
        }
        connect.rootDraft = service.settings.sharedRoots
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
        if (connect.section == Section.TRANSFER) service.browsing.resort()
    }

    // ---------------------------------------------------------------- navigation

    fun goTo(target: Section) {
        connect.section = target
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
        sync.selectedConfigId = configId
        if (configId != null) sync.syncLayer = SyncLayer.DETAIL
    }

    fun selectJob(jobId: String?) {
        sync.selectedJobId = jobId
    }

    val selectedConfig: SyncConfig?
        get() = sync.selectedConfigId?.let { id -> sync.configs.firstOrNull { it.configId == id } }

    val selectedJob: JobRecord?
        get() = sync.selectedJobId?.let { id -> sync.jobs.firstOrNull { it.jobId == id } }

    /** The job detail for the selected run, built once per frame from the same factory. */
    val selectedJobDetail: JobDetails?
        get() = selectedJob?.let { JobDetailsFactory.of(it, service.log) }

    fun matchingConfigs(): List<SyncConfig> {
        val needle = sync.syncSearch.trim()
        if (needle.isEmpty()) return sync.configs
        return sync.configs.filter { it.name.contains(needle, ignoreCase = true) }
    }

    // ------------------------------------------------------------------ messages

    /**
     * Shows a overlay.notice for four seconds.
     *
     * It used to stay until it was clicked, which meant a window could hold a sentence from
     * ten minutes ago — the person who wrote it had already read it. A newer overlay.notice cancels
     * the older timer, so the last thing said is the thing that stays.
     */
    private var noticeJob: Job? = null

    fun notify(text: String, kind: NoticeKind = NoticeKind.INFO) {
        overlay.notice = Notice(text, kind)
        noticeJob?.cancel()
        noticeJob = scope.launch {
            delay(4_000)
            if (overlay.notice?.text == text) overlay.notice = null
        }
    }

    fun dismissNotice() {
        overlay.notice = null
    }

    // -------------------------------------------------------------- configurations

    fun reloadConfigs() {
        sync.configs = service.configs.list()
        if (sync.selectedConfigId != null && sync.configs.none { it.configId == sync.selectedConfigId }) {
            sync.selectedConfigId = sync.configs.firstOrNull()?.configId
        }
        if (sync.selectedConfigId == null && connect.section == Section.SYNC) {
            sync.selectedConfigId = sync.configs.firstOrNull()?.configId
        }
    }

    fun reloadJobs() {
        sync.jobs = service.history.all()
        if (sync.selectedJobId != null && sync.jobs.none { it.jobId == sync.selectedJobId }) sync.selectedJobId = null
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
        val taken = sync.configs.map { it.name }.toSet()
        return PathTools.uniqueName(taken, base)
    }

    /** 另存为: the same configuration under a name that is free. */
    fun duplicateConfig(config: SyncConfig) {
        val copy = config.copy(
            configId = Ids.newConfigId(),
            name = PathTools.uniqueName(sync.configs.map { it.name }.toSet(), "${config.name} 副本"),
        )
        saveConfig(copy)
    }

    fun saveConfig(config: SyncConfig) {
        runCatching { service.configs.save(config) }
            .onSuccess {
                reloadConfigs()
                sync.selectedConfigId = config.configId
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
                sync.selectedConfigId = service.configs.load(saved).configId
                sync.syncLayer = SyncLayer.DETAIL
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

    /** Which favourite's context menu is open, and where it opened. */

    /** The 收藏夹 dialogs: add a path, a new group, editing one row, or the panel settings. */


    /** 收藏夹's own ⋮: add a path, a group, an account, or open its settings. */

    /** 排序模式: rows become draggable and the star is out of the way. */

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
        favourites.favouriteDraft = null
        notify("已收藏 $clean", NoticeKind.SUCCESS)
    }

    fun removeFavourite(favourite: Favorite) {
        service.settings.removeFavourite(favourite)
        settingsRevision++
        notify("已取消收藏 ${favourite.path}", NoticeKind.INFO)
    }

    fun updateFavourite(favourite: Favorite, label: String, group: String, path: String) {
        service.settings.updateFavourite(favourite, label = label, group = group, path = path)
        settingsRevision++
        notify("已保存「${label.ifEmpty { path }}」", NoticeKind.SUCCESS)
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
        overlay.renaming = null
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
        val peer = connect.peers.firstOrNull()
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
        if (sync.running != null) {
            notify("已经有一个${sync.running?.label ?: "任务"}在进行中", NoticeKind.WARNING)
            return
        }
        if (config.pairs.isEmpty()) {
            notify("这个配置还没有文件夹对", NoticeKind.WARNING)
            return
        }

        val jobId = SyncService.newJobId()
        val prepared = config.copy(dryRun = dryRun)
        sync.running = RunningJob(
            jobId = jobId,
            configName = prepared.name,
            dryRun = dryRun,
            pairCount = prepared.pairs.size,
            startedAtMs = now(),
        )
        dismissNotice()

        val gate = io.github.cubsync.core.engine.PauseGate()
        runGate = gate
        sync.runPaused = false
        runTask = scope.launch {
            try {
                val result = service.run(prepared, jobId = jobId, observer = WindowObserver(), gate = gate)
                reloadJobs()
                sync.selectedJobId = result.jobId
                sync.jobsSegment = JobsSegment.JOBS
                goTo(Section.JOBS)
                notify(summaryLine(result), toneFor(result.status))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                notify("已取消「${prepared.name}」", NoticeKind.WARNING)
            } catch (failure: Throwable) {
                notify("运行失败：${failure.message ?: failure::class.simpleName}", NoticeKind.ERROR)
            } finally {
                sync.running = null
                runTask = null
            }
        }
    }

    /** 暂停 / 继续 the run in flight. Pauses between blocks; nothing is torn down. */

    private var runGate: io.github.cubsync.core.engine.PauseGate? = null

    fun pauseRun() {
        runGate?.pause()
        sync.runPaused = true
    }

    fun resumeRun() {
        runGate?.resume()
        sync.runPaused = false
    }

    fun cancelRun() {
        val task = runTask ?: return
        sync.running = null
        sync.runPaused = false
        task.cancel()
    }

    /** Compares without writing, and shows what would happen. */
    fun previewConfig(config: SyncConfig) {
        if (config.pairs.isEmpty()) {
            notify("这个配置还没有文件夹对", NoticeKind.WARNING)
            return
        }
        sync.previewing = true
        sync.syncLayer = SyncLayer.PREVIEW
        scope.launch {
            try {
                sync.preview = service.preview(config)
            } catch (failure: Throwable) {
                notify("预览失败：${failure.message ?: failure::class.simpleName}", NoticeKind.ERROR)
            } finally {
                sync.previewing = false
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
            sync.running = sync.running?.copy(currentPair = pair.name.ifEmpty { pair.pairId })
        }

        override fun onPairFinished(outcome: PairOutcome) {
            sync.running = sync.running?.copy(finishedPairs = (sync.running?.finishedPairs ?: 0) + 1, bytes = 0)
        }

        override fun onBytesTransferred(total: Long) {
            sync.running = sync.running?.copy(bytes = total)
        }
    }

    // ---------------------------------------------------------------------- peers

    /** Looks for devices on the other end of a cable. */
    fun refreshAdb() {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val found = io.github.cubsync.service.Adb.devices()
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                connect.adbDevices = found
                connect.adbNote = when {
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
        connect.connecting = true
        connect.peerError = null
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val failure = io.github.cubsync.service.Adb.forward(target, target, serial)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                connect.connecting = false
                if (failure != null) {
                    connect.adbNote = failure
                    connect.peerError = failure
                } else {
                    connect.adbNote = "已转发 tcp:$target → 设备 tcp:$target"
                    connectPeer("127.0.0.1:$target")
                }
            }
        }
    }

    /** The address box: a host, and a port that defaults to the protocol's. */
    fun connectPeerAddress() {
        val host = connect.peerHost.trim()
        if (host.isEmpty()) {
            connect.peerError = "先填地址"
            return
        }
        val port = connect.peerPortText.trim().let {
            if (it.isEmpty()) io.github.cubsync.core.protocol.Protocol.DEFAULT_PORT else it.toIntOrNull()
        }
        if (port == null || port !in 1..65535) {
            connect.peerError = "端口要填 1–65535，留空就用默认 ${io.github.cubsync.core.protocol.Protocol.DEFAULT_PORT}"
            return
        }
        connectPeer("$host:$port")
    }

    fun connectPeer(spec: String) {
        val text = spec.trim()
        if (text.isEmpty()) {
            connect.peerError = "先填 HOST:PORT"
            return
        }
        val endpoint = EndpointId.parseOrNull(text)?.takeIf { !it.isLocal }
        if (endpoint == null) {
            connect.peerError = "「$text」看起来不是 HOST:PORT"
            return
        }
        connect.peerError = null
        connect.connecting = true
        scope.launch {
            try {
                val session = service.connect(endpoint.host!!, endpoint.port!!)
                connect.peers = connect.peers.filterNot { it.backendId == session.backendId } + session
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
                connect.peerError = failure.message ?: "连接失败"
                notify("连接失败：$connect.peerError", NoticeKind.ERROR)
            } finally {
                connect.connecting = false
            }
        }
    }

    fun disconnectPeer(backendId: String) {
        service.disconnect(backendId)
        connect.peers = connect.peers.filterNot { it.backendId == backendId }
    }

    fun peerFor(backendId: String): PeerSession? = connect.peers.firstOrNull { it.backendId == backendId }

    /** Re-reads what the connect.peers expose; the button on the connect screen says 刷新设备. */
    fun refreshPeers() {
        val current = connect.peers
        if (current.isEmpty()) {
            notify("还没有连上任何设备", NoticeKind.INFO)
            return
        }
        scope.launch {
            connect.peers = current.mapNotNull { session ->
                runCatching { service.browsePool.probe(session.host, session.port) }
                    .getOrElse { null }
            }
            notify("已刷新 ${connect.peers.size} 台设备", NoticeKind.INFO)
        }
    }

    // --------------------------------------------------------------------- server

    val serverState: ServerState get() = server.state.value

    fun addRoot(path: String) {
        val clean = path.trim()
        if (clean.isEmpty()) return
        val absolute = PathTools.normalizeRoot(PathTools.absolute(clean))
        if (connect.rootDraft.any { it == absolute }) return
        connect.rootDraft = connect.rootDraft + absolute
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
        connect.rootDraft = connect.rootDraft.filterNot { it == path }
        rememberServer()
    }

    fun startServer(port: Int, nodeName: String) {
        val state = server.start(
            roots = connect.rootDraft,
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
        service.settings.sharedRoots = connect.rootDraft
        settingsRevision++
    }

    // ----------------------------------------------------------------------- text

    fun clearLogs() {
        service.log.clear()
        log.logs = emptyList()
    }

    /** What the status strip under the title says. */
    fun statusLine(server: ServerState = serverState): String {
        val parts = mutableListOf<String>()
        parts += if (server.running) "共享中 :${server.port}" else "未共享"
        parts += if (connect.peers.isEmpty()) "未连接${Words.REMOTE}" else "已连接 ${connect.peers.size} 台设备"
        sync.running?.let { parts += "${it.label}「${it.configName}」" }
        return parts.joinToString("  ·  ")
    }

    companion object {
        const val LOG_LIMIT: Int = 2_000

        /** The state directory the window will use unless `--state-dir` says otherwise. */
        fun defaultStateDirectory(): String = ConfigStore.defaultBase().toString()
    }
}
