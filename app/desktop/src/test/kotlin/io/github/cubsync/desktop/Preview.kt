package io.github.cubsync.desktop

import io.github.cubsync.core.engine.ActionKind
import io.github.cubsync.core.engine.JobItem
import io.github.cubsync.core.engine.JobResult
import io.github.cubsync.core.engine.JobStatus
import io.github.cubsync.core.engine.PairOutcome
import io.github.cubsync.core.engine.PairPlan
import io.github.cubsync.core.engine.PairStatus
import io.github.cubsync.core.engine.TransferStats
import io.github.cubsync.core.model.CompareMode
import io.github.cubsync.core.model.ConflictPolicy
import io.github.cubsync.core.model.DeletePolicy
import io.github.cubsync.core.model.Endpoint
import io.github.cubsync.core.model.FolderPair
import io.github.cubsync.core.model.SyncConfig
import io.github.cubsync.core.model.SyncDirection
import io.github.cubsync.service.BuildInfo
import io.github.cubsync.service.ConfigStore
import io.github.cubsync.service.EndpointId
import io.github.cubsync.service.JobRecord
import io.github.cubsync.service.LogEntry
import io.github.cubsync.service.LogLevel
import io.github.cubsync.service.PeerSession
import io.github.cubsync.service.ServerController
import io.github.cubsync.service.SyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import java.nio.file.Path

/**
 * Sample state for the screenshot renderer.
 *
 * The screens are the real ones; only the data is made up. This exists because a
 * window cannot be photographed off screen without something to show, and because
 * the alternative -- launching the application and photographing a desktop -- is not
 * something a build should do.
 */
object Preview {

    private const val MINUTE = 60_000L

    fun configs(): List<SyncConfig> = listOf(
        SyncConfig(
            configId = "cfg-photos",
            name = "照片备份",
            favorite = true,
            direction = SyncDirection.TWO_WAY,
            compare = CompareMode.MTIME_AND_SIZE,
            conflict = ConflictPolicy.KEEP_BOTH,
            delete = DeletePolicy.RECYCLE,
            parallelism = 4,
            globalFilter = io.github.cubsync.core.model.FilterSet(globs = listOf("*.tmp", "Thumbs.db")),
            pairs = listOf(
                FolderPair(
                    pairId = "pair-dcim",
                    order = 0,
                    name = "手机相册",
                    left = Endpoint(EndpointId.peer("192.168.1.20", 43917).backendId, "/sdcard/DCIM"),
                    right = Endpoint(EndpointId.LOCAL, "D:/Photos/手机"),
                ),
                FolderPair(
                    pairId = "pair-docs",
                    order = 1,
                    name = "文档",
                    left = Endpoint(EndpointId.LOCAL, "D:/Documents"),
                    right = Endpoint(EndpointId.peer("192.168.1.20", 43917).backendId, "/sdcard/Documents"),
                    directionOverride = SyncDirection.UPDATE_LEFT_TO_RIGHT,
                ),
            ),
        ),
        SyncConfig(
            configId = "cfg-music",
            name = "音乐库",
            direction = SyncDirection.MIRROR_LEFT_TO_RIGHT,
            compare = CompareMode.CONTENT_HASH,
            conflict = ConflictPolicy.PREFER_LEFT,
            delete = DeletePolicy.PROPAGATE,
            parallelism = 2,
            pairs = listOf(
                FolderPair(
                    pairId = "pair-music",
                    order = 0,
                    left = Endpoint(EndpointId.LOCAL, "D:/Music"),
                    right = Endpoint(EndpointId.peer("192.168.1.31", 43917).backendId, "/sdcard/Music"),
                ),
            ),
        ),
        SyncConfig(
            configId = "cfg-work",
            name = "工作目录",
            direction = SyncDirection.UPDATE_RIGHT_TO_LEFT,
            compare = CompareMode.MTIME_AND_SIZE,
            delete = DeletePolicy.KEEP,
            pairs = emptyList(),
        ),
    )

    fun jobs(now: Long = System.currentTimeMillis()): List<JobRecord> = listOf(
        JobRecord(
            jobId = "job-1016-073012",
            configId = "cfg-photos",
            configName = "照片备份",
            direction = SyncDirection.TWO_WAY,
            dryRun = false,
            result = JobResult(
                jobId = "job-1016-073012",
                configId = "cfg-photos",
                status = JobStatus.COMPLETED,
                startedAtMs = now - 4 * MINUTE,
                finishedAtMs = now - 4 * MINUTE + 41_800,
                pairs = listOf(
                    PairOutcome(
                        pairId = "pair-dcim",
                        pairName = "手机相册",
                        status = PairStatus.COMPLETED,
                        startedAtMs = now - 4 * MINUTE,
                        finishedAtMs = now - 4 * MINUTE + 31_000,
                        stats = TransferStats(copiedFiles = 128, copiedBytes = 3L * 1024 * 1024 * 1024 + 412_000_000, skipped = 9_431),
                        items = transferred(128) + skipped(9_431),
                        plan = PairPlan("pair-dcim", SyncDirection.TWO_WAY, emptyList()),
                    ),
                    PairOutcome(
                        pairId = "pair-docs",
                        pairName = "文档",
                        status = PairStatus.COMPLETED,
                        startedAtMs = now - 4 * MINUTE + 31_000,
                        finishedAtMs = now - 4 * MINUTE + 41_800,
                        stats = TransferStats(copiedFiles = 6, copiedBytes = 18_400_000, deletedFiles = 2, conflicts = 1),
                        items = transferred(6) + listOf(
                            JobItem("草稿/会议记录.docx", ActionKind.CONFLICT_KEEP_BOTH, skippedReason = "两侧都改过，保留两份"),
                        ),
                        plan = PairPlan("pair-docs", SyncDirection.UPDATE_LEFT_TO_RIGHT, emptyList()),
                    ),
                ),
            ),
        ),
        JobRecord(
            jobId = "job-1016-070055",
            configId = "cfg-music",
            configName = "音乐库",
            direction = SyncDirection.MIRROR_LEFT_TO_RIGHT,
            dryRun = false,
            result = JobResult(
                jobId = "job-1016-070055",
                configId = "cfg-music",
                status = JobStatus.PARTIAL_FAILED,
                startedAtMs = now - 30 * MINUTE,
                finishedAtMs = now - 30 * MINUTE + 12_400,
                pairs = listOf(
                    PairOutcome(
                        pairId = "pair-music",
                        pairName = "音乐库",
                        status = PairStatus.FAILED,
                        startedAtMs = now - 30 * MINUTE,
                        finishedAtMs = now - 30 * MINUTE + 12_400,
                        stats = TransferStats(copiedFiles = 214, copiedBytes = 986_000_000, deletedFiles = 3, failures = 1),
                        items = transferred(12) + listOf(
                            JobItem(
                                "专辑/现场版/live.flac",
                                ActionKind.COPY_LEFT_TO_RIGHT,
                                ok = false,
                                error = "空间不足：目标端还剩 120 MiB",
                            ),
                        ),
                        error = "1 个文件写入失败",
                        plan = PairPlan("pair-music", SyncDirection.MIRROR_LEFT_TO_RIGHT, emptyList()),
                    ),
                ),
            ),
        ),
        JobRecord(
            jobId = "job-1016-064500",
            configId = "cfg-photos",
            configName = "照片备份",
            direction = SyncDirection.TWO_WAY,
            dryRun = true,
            result = JobResult(
                jobId = "job-1016-064500",
                configId = "cfg-photos",
                status = JobStatus.COMPLETED,
                startedAtMs = now - 45 * MINUTE,
                finishedAtMs = now - 45 * MINUTE + 2_900,
                pairs = listOf(
                    PairOutcome(
                        pairId = "pair-dcim",
                        pairName = "手机相册",
                        status = PairStatus.DRY_RUN,
                        startedAtMs = now - 45 * MINUTE,
                        finishedAtMs = now - 45 * MINUTE + 2_900,
                        stats = TransferStats(skipped = 9_431),
                        items = skipped(9_431),
                        plan = PairPlan("pair-dcim", SyncDirection.TWO_WAY, emptyList()),
                    ),
                ),
            ),
        ),
    )

    fun logs(now: Long = System.currentTimeMillis()): List<LogEntry> {
        var sequence = 0L
        fun entry(offset: Long, level: LogLevel, area: String, message: String, detail: String = "") =
            LogEntry(++sequence, now - offset, level, area, message, detail)

        return listOf(
            entry(240_000, LogLevel.INFO, "server", "sharing 2 folder(s) on port 43917 as DESKTOP-AU4", "192.168.1.10:43917"),
            entry(233_000, LogLevel.INFO, "connect", "connecting to 192.168.1.20:43917"),
            entry(232_400, LogLevel.INFO, "connect", "connected to Redmi-Note-Pro at 192.168.1.20:43917", "protocol ${BuildInfo.PROTOCOL_VERSION}, version ${BuildInfo.VERSION}"),
            entry(231_000, LogLevel.INFO, "job", "Syncing 照片备份", "2 folder pair(s), direction two_way"),
            entry(230_500, LogLevel.INFO, "pair", "手机相册: comparing"),
            entry(229_000, LogLevel.INFO, "pair", "手机相册: 140 action(s) planned"),
            entry(228_000, LogLevel.DEBUG, "file", "copy_left_to_right DCIM/Camera/IMG_20261016_0731.jpg"),
            entry(227_400, LogLevel.DEBUG, "file", "copy_left_to_right DCIM/Camera/IMG_20261016_0730.jpg"),
            entry(226_000, LogLevel.WARN, "file", "DCIM/Video/VID_20261015.mp4: 源文件在传输中被改动，已重新读取"),
            entry(225_000, LogLevel.DEBUG, "file", "DCIM/Video/VID_20261015.mp4: skipped (时间与大小一致)"),
            entry(224_000, LogLevel.INFO, "pair", "手机相册: completed", "128 copied, 3222704128 bytes, 0 deleted, 0 failed"),
            entry(200_000, LogLevel.INFO, "pair", "文档: comparing"),
            entry(198_000, LogLevel.INFO, "pair", "文档: 9 action(s) planned"),
            entry(197_000, LogLevel.INFO, "file", "草稿/会议记录.docx: 两侧都改过，保留两份"),
            entry(196_000, LogLevel.INFO, "pair", "文档: completed", "6 copied, 18400000 bytes, 2 deleted, 0 failed"),
            entry(150_000, LogLevel.INFO, "job", "照片备份: completed", "134 copied, 1 failed, 41800 ms"),
            entry(30_000, LogLevel.WARN, "server", "1 peer session(s) detached"),
        )
    }

    fun peers(): List<PeerSession> = listOf(
        PeerSession(
            backendId = EndpointId.peer("192.168.1.20", 43917).backendId,
            host = "192.168.1.20",
            port = 43917,
            nodeName = "Redmi-Note-Pro",
            appVersion = BuildInfo.VERSION,
            protocolVersion = BuildInfo.PROTOCOL_VERSION,
            capabilities = -1L,
            roots = listOf("/sdcard/DCIM", "/sdcard/Documents", "/sdcard/Music"),
        ),
    )

    /**
     * Builds a state object showing the sample data.
     *
     * The service points at a throwaway directory, so rendering a screenshot cannot
     * touch the settings or the snapshots of whoever ran the build.
     */
    fun state(section: Section = Section.CONNECT, darkTheme: Boolean = true): AppState {
        val stateDirectory: Path = Files.createTempDirectory("cubsync-preview-")
        val log = io.github.cubsync.service.AppLog()
        val service = SyncService(stateDirectory = stateDirectory, log = log)
        val settings = DesktopSettings.inStateDirectory(stateDirectory)
        val state = AppState(
            service = service,
            server = ServerController(log),
            settings = settings,
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
        )
        state.section = section
        state.configs = configs()
        state.selectedConfigId = "cfg-photos"
        state.jobs = jobs()
        state.selectedJobId = "job-1016-073012"
        state.logs = logs()
        state.peers = peers()
        state.peerDraft = "192.168.1.20:43917"
        state.rootDraft = listOf("D:/Photos", "D:/Documents")
        state.settings.darkTheme = darkTheme
        return state
    }

    fun dispose(state: AppState) {
        runCatching { state.close() }
        runCatching { Files.walk(state.service.stateDirectory).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private fun transferred(count: Int): List<JobItem> = (1..count).map { index ->
        JobItem(
            relPath = "DCIM/Camera/IMG_2026101${index % 9}_${(1_000 + index).toString().padStart(4, '0')}.jpg",
            action = ActionKind.COPY_LEFT_TO_RIGHT,
            bytes = 3_200_000L + index * 1_337L,
        )
    }

    private fun skipped(count: Int): List<JobItem> = buildList {
        // Only the first few are ever rendered, so a handful of representative
        // reasons is enough; the count is what the screen shows.
        repeat(minOf(count, 4)) { index ->
            add(
                JobItem(
                    relPath = "DCIM/Camera/IMG_2026010${index}_0${index}00.jpg",
                    action = ActionKind.NONE,
                    skippedReason = listOf("时间与大小一致", "被过滤器排除", "已忽略", "源不存在")[index % 4],
                ),
            )
        }
    }
}
