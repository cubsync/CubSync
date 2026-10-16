package io.github.cubsync.service

import io.github.cubsync.core.engine.JobStatus
import io.github.cubsync.core.engine.PairStatus
import io.github.cubsync.core.model.Endpoint
import io.github.cubsync.core.model.FolderPair
import io.github.cubsync.core.model.SyncConfig
import io.github.cubsync.core.model.SyncDirection
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The service layer, exercised the way a front end uses it.
 *
 * These run over the real local backend on real temporary directories rather than
 * over a fake, because the thing being tested here is the wiring between the
 * runner, the snapshot store and the state directory — which a fake would replace.
 */
class SyncServiceTest {

    private val temporaries = mutableListOf<Path>()
    private val services = mutableListOf<SyncService>()

    private fun tempDir(prefix: String = "cubsync-svc-"): Path =
        Files.createTempDirectory(prefix).also { temporaries.add(it) }

    private fun service(): SyncService = SyncService(stateDirectory = tempDir()).also { services.add(it) }

    @AfterTest
    fun cleanUp() {
        services.forEach { runCatching { it.close() } }
        services.clear()
        for (dir in temporaries) {
            runCatching { Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
        temporaries.clear()
    }

    private fun config(
        left: Path,
        right: Path,
        direction: SyncDirection = SyncDirection.UPDATE_LEFT_TO_RIGHT,
        dryRun: Boolean = false,
    ) = SyncConfig(
        configId = "cfg-test",
        name = "test",
        direction = direction,
        dryRun = dryRun,
        pairs = listOf(
            FolderPair(
                pairId = "pair-test",
                order = 0,
                name = "folders",
                left = Endpoint(EndpointId.LOCAL, PathTools.normalizeRoot(left.toString())),
                right = Endpoint(EndpointId.LOCAL, PathTools.normalizeRoot(right.toString())),
            ),
        ),
    )

    @Test
    fun a_run_copies_files_and_remembers_the_job() = runBlocking {
        val left = tempDir("left-")
        val right = tempDir("right-")
        Files.writeString(left.resolve("hello.txt"), "hello")
        Files.createDirectories(left.resolve("nested"))
        Files.writeString(left.resolve("nested/deep.txt"), "deep")

        val service = service()
        val result = service.run(config(left, right))

        assertEquals(JobStatus.COMPLETED, result.status)
        assertEquals("hello", Files.readString(right.resolve("hello.txt")))
        assertEquals("deep", Files.readString(right.resolve("nested/deep.txt")))

        val record = service.history.all().single()
        assertEquals(result.jobId, record.jobId)
        assertEquals("test", record.configName)
        assertEquals(2, record.result.stats.copiedFiles)

        assertTrue(service.log.entries().any { it.area == "job" && it.message.contains("Syncing") })
        assertTrue(service.log.entries().any { it.area == "pair" && it.message.contains("completed") })
        assertTrue(service.log.entries().any { it.area == "file" && it.message.contains("hello.txt") })
    }

    @Test
    fun a_second_run_finds_nothing_left_to_do() = runBlocking {
        val left = tempDir("left-")
        val right = tempDir("right-")
        Files.writeString(left.resolve("a.txt"), "a")
        val service = service()
        val config = config(left, right, direction = SyncDirection.TWO_WAY)

        assertEquals(1, service.run(config).stats.copiedFiles)
        val second = service.run(config)
        assertEquals(JobStatus.COMPLETED, second.status)
        assertEquals(0, second.stats.copiedFiles)
        assertEquals(0, second.stats.deletedFiles)
    }

    @Test
    fun a_dry_run_changes_nothing_but_still_reports_what_it_would_do() = runBlocking {
        val left = tempDir("left-")
        val right = tempDir("right-")
        Files.writeString(left.resolve("planned.txt"), "not yet")

        val service = service()
        val result = service.run(config(left, right, dryRun = true))

        assertEquals(PairStatus.DRY_RUN, result.pairs.single().status)
        assertFalse(Files.exists(right.resolve("planned.txt")))
        assertTrue(result.pairs.single().plan!!.actionable.isNotEmpty())
    }

    @Test
    fun a_peer_that_cannot_be_reached_fails_the_pair_instead_of_throwing() = runBlocking {
        val left = tempDir("left-")
        Files.writeString(left.resolve("a.txt"), "a")

        // Port 1 is reserved and nothing is listening on it.
        val unreachable = SyncConfig(
            configId = "cfg-unreachable",
            name = "unreachable",
            direction = SyncDirection.UPDATE_LEFT_TO_RIGHT,
            pairs = listOf(
                FolderPair(
                    pairId = "pair-unreachable",
                    order = 0,
                    left = Endpoint(EndpointId.LOCAL, PathTools.normalizeRoot(left.toString())),
                    right = Endpoint(EndpointId.peer("127.0.0.1", 1).backendId, "/nowhere"),
                ),
            ),
        )

        val service = service()
        val result = service.run(unreachable)

        assertTrue(result.status != JobStatus.COMPLETED, "a dead peer cannot count as success")
        assertEquals(PairStatus.FAILED, result.pairs.single().status)
        assertTrue(result.pairs.single().error.isNotEmpty())
        // The failure was recorded, so a window can show it rather than losing it.
        assertEquals(1, service.history.all().size)
    }

    @Test
    fun the_state_directory_decides_where_snapshots_live() = runBlocking {
        val left = tempDir("left-")
        val right = tempDir("right-")
        val state = tempDir("state-")
        Files.writeString(left.resolve("a.txt"), "a")

        val service = SyncService(stateDirectory = state)
        services.add(service)
        service.run(config(left, right, direction = SyncDirection.TWO_WAY))

        assertTrue(Files.isDirectory(state.resolve("snapshots")))
        val snapshots = Files.list(state.resolve("snapshots")).use { it.toList() }
        assertTrue(snapshots.isNotEmpty(), "a two-way run has to leave a snapshot behind")
    }

    @Test
    fun observers_are_told_about_each_pair_as_it_finishes() = runBlocking {
        val left = tempDir("left-")
        val right = tempDir("right-")
        Files.writeString(left.resolve("a.txt"), "a")

        val started = ArrayList<String>()
        val finished = ArrayList<String>()
        val service = service()
        service.run(
            config(left, right),
            observer = object : SyncObserver {
                override fun onPairStarted(pair: FolderPair) {
                    started.add(pair.pairId)
                }

                override fun onPairFinished(outcome: io.github.cubsync.core.engine.PairOutcome) {
                    finished.add(outcome.pairId)
                }
            },
        )

        assertEquals(listOf("pair-test"), started)
        assertEquals(listOf("pair-test"), finished)
    }

    @Test
    fun the_job_id_is_sortable_by_the_time_it_ran() {
        val first = SyncService.newJobId(java.time.Instant.parse("2026-10-16T09:15:00Z"), java.time.ZoneOffset.UTC)
        val second = SyncService.newJobId(java.time.Instant.parse("2026-10-16T10:15:00Z"), java.time.ZoneOffset.UTC)
        assertTrue(first.startsWith("job-"))
        assertTrue(first < second, "'$first' should sort before '$second'")
    }

    /**
     * 新增 and 更新 are about the comparison, not about the action.
     *
     * A copied file is a copy whether the other side had nothing there or had an older
     * copy, so counting by action kind reported every addition as an update and left 新增
     * counting directories only — a preview that disagreed with its own byte total.
     */
    @Test
    fun the_preview_calls_a_new_file_new_and_a_changed_file_updated() = runBlocking {
        val left = tempDir("left-")
        val right = tempDir("right-")
        val service = service()
        val config = config(left, right, direction = SyncDirection.UPDATE_LEFT_TO_RIGHT)

        // Two new files and a new folder: all additions.
        Files.writeString(left.resolve("fresh.txt"), "fresh")
        Files.createDirectories(left.resolve("nested"))
        Files.writeString(left.resolve("nested/deep.txt"), "deep")
        val first = service.preview(config).totals
        assertTrue(first.create >= 2, "both new files should count as 新增, got ${first.create}")
        assertEquals(0, first.update, "a file the other side has never had is not an update")
        assertEquals(0, first.delete)

        // Now change one of them and compare again: that one is an update.
        service.run(config)
        Files.writeString(left.resolve("fresh.txt"), "changed and longer")
        Files.setLastModifiedTime(
            left.resolve("fresh.txt"),
            java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000),
        )
        val second = service.preview(config).totals
        assertEquals(0, second.create, "nothing is new the second time round")
        assertEquals(1, second.update, "the changed file should count as 更新")
        assertEquals(0, second.delete)
    }
}
