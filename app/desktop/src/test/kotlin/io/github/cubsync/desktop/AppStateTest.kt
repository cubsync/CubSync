package io.github.cubsync.desktop

import io.github.cubsync.core.model.CompareMode
import io.github.cubsync.core.model.DeletePolicy
import io.github.cubsync.core.model.Endpoint
import io.github.cubsync.core.model.FolderPair
import io.github.cubsync.core.model.SyncConfig
import io.github.cubsync.core.model.SyncDirection
import io.github.cubsync.service.AppLog
import io.github.cubsync.service.EndpointId
import io.github.cubsync.service.PathTools
import io.github.cubsync.service.ServerController
import io.github.cubsync.service.SyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The window's own behaviour, driven the way a person drives it.
 *
 * These run against the real service and the real local backend, so a run started
 * from the state object moves real files. That is the point: the interesting bugs
 * here are the ones where the window thinks it started something and did not.
 */
class AppStateTest {

    private val temporaries = mutableListOf<Path>()
    private val scopes = mutableListOf<CoroutineScope>()

    private fun tempDir(prefix: String): Path = Files.createTempDirectory(prefix).also { temporaries.add(it) }

    private fun state(stateDirectory: Path = tempDir("state-")): AppState {
        val log = AppLog()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob()).also { scopes.add(it) }
        return AppState(
            service = SyncService(stateDirectory = stateDirectory, log = log),
            server = ServerController(log),
            settings = DesktopSettings.inStateDirectory(stateDirectory),
            scope = scope,
        )
    }

    @AfterTest
    fun cleanUp() {
        scopes.forEach { runCatching { it.cancel() } }
        scopes.clear()
        for (dir in temporaries) {
            runCatching { Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
        temporaries.clear()
    }

    private fun pair(left: Path, right: Path) = FolderPair(
        pairId = "pair-1",
        order = 0,
        name = "folders",
        left = Endpoint(EndpointId.LOCAL, PathTools.normalizeRoot(left.toString())),
        right = Endpoint(EndpointId.LOCAL, PathTools.normalizeRoot(right.toString())),
    )

    private fun config(pairs: List<FolderPair>) = SyncConfig(
        configId = "cfg-1",
        name = "窗口测试",
        direction = SyncDirection.UPDATE_LEFT_TO_RIGHT,
        pairs = pairs,
    )

    @Test
    fun a_run_started_from_the_window_moves_files_and_shows_the_job() = runBlocking {
        val left = tempDir("left-")
        val right = tempDir("right-")
        Files.writeString(left.resolve("hello.txt"), "hello")
        Files.createDirectories(left.resolve("nested"))
        Files.writeString(left.resolve("nested/deep.txt"), "deep")

        val state = state()
        try {
            val config = config(listOf(pair(left, right)))
            state.saveConfig(config)
            state.runConfig(config, dryRun = false)

            awaitUntil("the run to finish") { state.running == null && state.jobs.isNotEmpty() }

            assertEquals("hello", Files.readString(right.resolve("hello.txt")))
            assertEquals("deep", Files.readString(right.resolve("nested/deep.txt")))

            // The window moves to the result, because that is what somebody wants to
            // see next.
            assertEquals(Section.JOBS, state.section)
            val job = assertNotNull(state.selectedJob)
            assertEquals(2, job.result.stats.copiedFiles)
            assertEquals(NoticeKind.SUCCESS, state.notice?.kind)
        } finally {
            state.close()
        }
    }

    @Test
    fun a_dry_run_leaves_the_other_side_alone() = runBlocking {
        val left = tempDir("left-")
        val right = tempDir("right-")
        Files.writeString(left.resolve("planned.txt"), "not yet")

        val state = state()
        try {
            val config = config(listOf(pair(left, right)))
            state.runConfig(config, dryRun = true)
            awaitUntil("the dry run to finish") { state.running == null && state.jobs.isNotEmpty() }

            assertTrue(!Files.exists(right.resolve("planned.txt")))
            assertTrue(assertNotNull(state.selectedJob).dryRun)
        } finally {
            state.close()
        }
    }

    @Test
    fun a_second_run_is_refused_while_one_is_in_flight() {
        val left = tempDir("left-")
        val right = tempDir("right-")
        Files.writeString(left.resolve("a.txt"), "a")

        val state = state()
        try {
            val config = config(listOf(pair(left, right)))
            // Pretend a run is in flight; the guard is what is under test, not the run.
            state.running = RunningJob("job-x", "窗口测试", dryRun = false, pairCount = 1, startedAtMs = 0)
            state.runConfig(config, dryRun = false)
            assertEquals(NoticeKind.WARNING, state.notice?.kind)
            assertTrue(state.jobs.isEmpty())
        } finally {
            state.close()
        }
    }

    @Test
    fun a_configuration_without_folder_pairs_is_refused_with_a_reason() {
        val state = state()
        try {
            state.runConfig(config(emptyList()), dryRun = false)
            assertEquals(NoticeKind.WARNING, state.notice?.kind)
            assertTrue(state.notice!!.text.contains("文件夹对"))
            assertNull(state.running)
        } finally {
            state.close()
        }
    }

    @Test
    fun a_bad_peer_address_is_reported_without_opening_a_connection() {
        val state = state()
        try {
            state.connectPeer("not an address")
            assertEquals("「not an address」看起来不是 HOST:PORT", state.peerError)
            assertTrue(state.peers.isEmpty())
        } finally {
            state.close()
        }
    }

    @Test
    fun the_server_can_be_started_and_stopped_from_the_window() {
        val shared = tempDir("shared-")
        val state = state()
        try {
            state.addRoot(shared.toString())
            assertEquals(1, state.rootDraft.size)
            // Adding the same folder twice is a no-op rather than a duplicate row.
            state.addRoot(shared.toString())
            assertEquals(1, state.rootDraft.size)

            state.startServer(port = 0, nodeName = "window-test")
            assertTrue(state.serverState.running, state.serverState.error)
            assertTrue(state.serverState.port > 0)

            state.stopServer()
            assertTrue(!state.serverState.running)
        } finally {
            state.close()
        }
    }

    @Test
    fun a_folder_that_does_not_exist_is_refused_by_the_server() {
        val state = state()
        try {
            state.addRoot("/definitely/not/here")
            state.startServer(port = 0, nodeName = "window-test")
            assertTrue(!state.serverState.running)
            assertEquals(NoticeKind.ERROR, state.notice?.kind)
        } finally {
            state.close()
        }
    }

    @Test
    fun a_new_configuration_starts_from_the_settings() {
        val state = state()
        try {
            state.settings.defaultDirection = SyncDirection.MIRROR_RIGHT_TO_LEFT
            state.settings.defaultDelete = DeletePolicy.PROPAGATE
            state.settings.defaultParallelism = 4
            val draft = state.newConfigDraft()
            assertEquals(SyncDirection.MIRROR_RIGHT_TO_LEFT, draft.direction)
            assertEquals(DeletePolicy.PROPAGATE, draft.delete)
            assertEquals(4, draft.parallelism)
            assertEquals(CompareMode.MTIME_AND_SIZE, draft.compare)
            assertTrue(draft.pairs.isEmpty())
        } finally {
            state.close()
        }
    }

    @Test
    fun saving_and_deleting_a_configuration_updates_the_list() {
        val state = state()
        try {
            val draft = state.newConfigDraft()
            state.saveConfig(draft)
            assertEquals(listOf(draft.configId), state.configs.map { it.configId })
            assertEquals(draft.configId, state.selectedConfigId)

            state.deleteConfig(draft.configId)
            assertTrue(state.configs.isEmpty())
            assertNull(state.selectedConfig)
        } finally {
            state.close()
        }
    }

    @Test
    fun the_log_streams_into_the_window() = runBlocking {
        val state = state()
        try {
            state.service.log.info("job", "something happened")
            awaitUntil("the log line to arrive") {
                state.logs.any { it.message == "something happened" }
            }
            state.clearLogs()
            assertTrue(state.logs.isEmpty())
            assertTrue(state.service.log.entries().isEmpty())
        } finally {
            state.close()
        }
    }

    @Test
    fun the_status_line_says_what_is_possible_right_now() {
        val state = state()
        try {
            val idle = state.statusLine()
            assertTrue(idle.contains("未共享"), idle)
            assertTrue(idle.contains("未连接"), idle)

            state.running = RunningJob("job-x", "窗口测试", dryRun = true, pairCount = 2, startedAtMs = 0)
            assertTrue(state.statusLine().contains("试运行"))
        } finally {
            state.close()
        }
    }

    /** A test that waits on a coroutine has to say so, and fail rather than hang. */
    private suspend fun awaitUntil(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(50)
        }
        throw AssertionError("timed out waiting for $what")
    }
}
