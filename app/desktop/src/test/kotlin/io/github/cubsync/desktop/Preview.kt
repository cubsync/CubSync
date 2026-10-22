package io.github.cubsync.desktop

import androidx.compose.ui.Modifier
import io.github.cubsync.core.engine.ActionKind
import io.github.cubsync.core.engine.JobItem
import io.github.cubsync.core.engine.JobResult
import io.github.cubsync.core.engine.JobStatus
import io.github.cubsync.core.engine.PairOutcome
import io.github.cubsync.core.engine.PairStatus
import io.github.cubsync.core.engine.TransferStats
import io.github.cubsync.core.model.Endpoint
import io.github.cubsync.core.model.FolderPair
import io.github.cubsync.core.model.SyncConfig
import io.github.cubsync.core.model.SyncDirection
import io.github.cubsync.core.util.UtcClock
import io.github.cubsync.service.AppLog
import io.github.cubsync.service.DisplayMode
import io.github.cubsync.service.EndpointId
import io.github.cubsync.service.Favorite
import io.github.cubsync.service.JobRecord
import io.github.cubsync.service.KnownPeer
import io.github.cubsync.service.Section
import io.github.cubsync.service.ServerController
import io.github.cubsync.service.Side
import io.github.cubsync.desktop.ui.WindowChrome
import io.github.cubsync.service.SyncService
import io.github.cubsync.service.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime

/**
 * The preview's own scratch area, at a path that does not change.
 *
 * A random directory would be drawn in the breadcrumb, and every run would then be a
 * different picture — the one thing a baseline cannot be. It is emptied first so a run
 * sees what it seeded and nothing a previous run left behind.
 */
private val scratch: Path by lazy {
    val dir = Paths.get(System.getProperty("java.io.tmpdir"), "cubsync-preview-data")
    runCatching { dir.toFile().deleteRecursively() }
    Files.createDirectories(dir)
    dir
}

internal fun previewFolder(name: String): Path {
    val path = scratch.resolve(name)
    Files.createDirectories(path)
    return path
}

/** Writes a file whose length differs from its neighbours', which is what the list shows. */
private fun seedFile(directory: Path, name: String, length: Int) {
    Files.createDirectories(directory)
    val file = directory.resolve(name)
    Files.write(file, ByteArray(length))
    // Fixed, so the 修改时间 column reads the same tomorrow as it does today.
    Files.setLastModifiedTime(file, FileTime.fromMillis(Preview.NOW - 3_600_000))
}

/**
 * Sample state for the screenshot test.
 *
 * The window is composed off screen and written to `build/screenshots`, which is how a
 * screen that fails to lay out fails the build instead of being found by whoever opens
 * the window next. The data here is deliberately real — real folders on disk, a real
 * configuration, a real finished run — because a screenshot of an empty window proves
 * nothing about the layout that matters.
 */
object Preview {

    /**
     * A window that does not exist.
     *
     * The title bar is drawn by the application rather than by the system, so a screenshot
     * of the window has to supply the four things its buttons would do. Here they do
     * nothing, which is what a still image needs.
     */
    val chrome: WindowChrome = WindowChrome(
        minimise = {},
        toggleMaximise = {},
        close = {},
        maximised = true,
        dragModifier = Modifier,
    )

    /** One screenshot to take. */
    data class Shot(
        val name: String,
        val section: Section,
        val dark: Boolean,
        val jobDetail: Boolean = false,
        val view: DisplayMode? = null,
        val displayMenu: Boolean = false,
    )

    val shots: List<Shot> = listOf(
        Shot("00-transfer", Section.TRANSFER, dark = true),
        Shot("01-connect", Section.CONNECT, dark = true),
        Shot("02-sync", Section.SYNC, dark = true),
        Shot("03-jobs", Section.JOBS, dark = true),
        Shot("04-job-detail", Section.JOBS, dark = true, jobDetail = true),
        Shot("05-logs", Section.LOGS, dark = true),
        Shot("06-settings", Section.SETTINGS, dark = true),
        Shot("07-transfer-light", Section.TRANSFER, dark = false),
        Shot("08-transfer-compare", Section.TRANSFER, dark = true, view = DisplayMode.COMPARE),
        Shot("09-transfer-card", Section.TRANSFER, dark = false, view = DisplayMode.CARD),
        Shot("10-transfer-viewmenu", Section.TRANSFER, dark = true, displayMenu = true),
        Shot("11-transfer-compact", Section.TRANSFER, dark = true, view = DisplayMode.COMPACT),
        Shot("12-transfer-thumb", Section.TRANSFER, dark = true, view = DisplayMode.THUMBNAIL),
    )

    /**
     * A state with something in it.
     *
     * [Dispatchers.Unconfined] rather than the window's own dispatcher, so a listing
     * started by [AppState] has finished by the time the frame is composed. A screenshot
     * test that sleeps to wait for its own data is one that fails on a slow machine.
     */
    fun state(
        section: Section,
        dark: Boolean,
        jobDetail: Boolean = false,
        view: DisplayMode? = null,
        displayMenu: Boolean = false,
    ): AppState {
        val directory = Files.createTempDirectory("cubsync-preview-")
        val log = AppLog(now = { NOW })
        val desktop = DesktopSettings.inStateDirectory(directory)
        val service = SyncService(
            stateDirectory = directory,
            log = log,
            settingsStorage = desktop,
            utcClock = UtcClock { NOW },
        )
        val server = ServerController(log)
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val state = AppState(service, server, desktop, scope, now = { NOW })

        seed()
        seedLog(log)

        val config = config()
        runCatching { service.configs.save(config) }
        state.reloadConfigs()
        state.selectConfig(config.configId)

        val record = finishedRun(config)
        service.history.add(record)
        state.reloadJobs()
        if (jobDetail) state.selectedJobId = record.jobId

        service.settings.rememberPeer(KnownPeer("Redmi-Note-Pro", "192.168.1.20", 43917))
        service.settings.favourites = listOf(
            Favorite("DESKTOP-AU4", EndpointId.LOCAL, previewFolder("Backup").toString()),
        )
        service.settings.serverNodeName = "DESKTOP-AU4"
        service.settings.sharedRoots = listOf(previewFolder("Backup").toString())

        state.section = section
        state.changeSettings { it.theme = if (dark) ThemeMode.DARK else ThemeMode.LIGHT }
        val left = previewFolder("DCIM/Camera")
        val right = previewFolder("Mirror")
        state.openPane(Side.LEFT, left.toString())
        state.openPane(Side.RIGHT, right.toString())
        if (view != null) {
            state.setDisplayMode(Side.LEFT, view)
            state.setDisplayMode(Side.RIGHT, view)
        }
        state.browsing.select(Side.LEFT, listOf("IMG_20261016_0731.jpg", "IMG_20261016_0730.jpg"))
        state.displayMenu = displayMenu
        return state
    }

    /**
     * 2026-10-17 20:00 — the moment every preview is drawn at.
     *
     * The window writes times: when a run started, when a file changed, how long ago the
     * last one finished. Left to the wall clock those are different on every run, and a
     * baseline that never matches is not a baseline.
     */
    const val NOW = 1_792_238_400_000L

    /** Releases everything the state owns. */
    fun dispose(state: AppState) {
        runCatching { state.close() }
    }

    private var seeded = false

    /** The sample tree, so a pane has something to list. */
    private fun seed() {
        if (seeded) return
        seeded = true
        val camera = previewFolder("DCIM/Camera")
        seedFile(camera, "IMG_20261016_0731.jpg", 2_400)
        seedFile(camera, "IMG_20261016_0730.jpg", 1_950)
        seedFile(camera, "IMG_20261015_2210.jpg", 3_100)
        seedFile(camera, "VID_20261014_1902.mp4", 48_000)
        seedFile(previewFolder("DCIM/Screenshots"), "shot.png", 512)
        seedFile(previewFolder("Movies"), "holiday.mp4", 18_000)
        seedFile(previewFolder("Backup"), "photos.cubcfg", 128)
        val mirror = previewFolder("Mirror")
        seedFile(mirror, "IMG_20261015_2210.jpg", 3_100)
        seedFile(mirror, "notes.txt", 64)
    }

    private fun config(): SyncConfig = SyncConfig(
        configId = "cfg-photos",
        name = "照片库",
        direction = SyncDirection.TWO_WAY,
        pairs = listOf(
            pair("pair-1", 0, "相机 → 备份", previewFolder("DCIM/Camera"), previewFolder("Mirror")),
            pair("pair-2", 1, "影片 → 备份", previewFolder("Movies"), previewFolder("Mirror")),
        ),
    )

    private fun pair(id: String, order: Int, name: String, left: Path, right: Path) = FolderPair(
        pairId = id,
        order = order,
        name = name,
        left = Endpoint(EndpointId.LOCAL, left.toAbsolutePath().toString().replace('\\', '/')),
        right = Endpoint(EndpointId.LOCAL, right.toAbsolutePath().toString().replace('\\', '/')),
    )

    /** A run that partly failed, because a run that went perfectly shows fewer states. */
    private fun finishedRun(config: SyncConfig): JobRecord {
        val now = NOW
        val first = listOf(
            JobItem("DCIM/Camera/IMG_20261016_0731.jpg", ActionKind.COPY_LEFT_TO_RIGHT, bytes = 2_400),
            JobItem("DCIM/Camera/IMG_20261016_0730.jpg", ActionKind.COPY_LEFT_TO_RIGHT, bytes = 1_950),
            JobItem("DCIM/Camera/IMG_20261015_2210.jpg", ActionKind.NONE, skippedReason = "时间与大小一致"),
        )
        val second = listOf(
            JobItem("Movies/holiday.mp4", ActionKind.COPY_LEFT_TO_RIGHT, ok = false, error = "目标端空间不足"),
        )
        val pairs = config.pairs.mapIndexed { index, folder ->
            PairOutcome(
                pairId = folder.pairId,
                pairName = folder.name,
                status = if (index == 0) PairStatus.COMPLETED else PairStatus.FAILED,
                items = if (index == 0) first else second,
                stats = TransferStats(
                    copiedFiles = if (index == 0) 2 else 0,
                    copiedBytes = if (index == 0) 4_350 else 0,
                    skipped = if (index == 0) 1 else 0,
                    failures = if (index == 0) 0 else 1,
                ),
                startedAtMs = now - 42_000,
                finishedAtMs = now - 12_000,
            )
        }
        return JobRecord(
            jobId = "job-preview",
            configId = config.configId,
            configName = config.name,
            direction = config.direction,
            dryRun = false,
            result = JobResult(
                jobId = "job-preview",
                configId = config.configId,
                status = JobStatus.PARTIAL_FAILED,
                pairs = pairs,
                startedAtMs = now - 42_000,
                finishedAtMs = now - 12_000,
            ),
        )
    }

    /** A few log lines inside the run's window, so the detail screen has its log. */
    private fun seedLog(log: AppLog) {
        log.info("server", "sharing 1 folder on port 43917 as DESKTOP-AU4")
        log.info("job", "Syncing 照片库", "2 folder pair(s), direction two_way")
        log.info("pair", "相机 → 备份: comparing")
        log.debug("file", "copy-left-to-right IMG_20261016_0731.jpg")
        log.info("pair", "影片 → 备份: failed", "insufficient space on the target")
        log.warn("job", "照片库: partial_failed", "2 copied, 1 failed")
    }
}
