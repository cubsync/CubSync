package io.github.cubsync.android

import io.github.cubsync.core.model.CompareMode
import io.github.cubsync.core.model.ConflictPolicy
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
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The phone's state object, driven the way a person drives it.
 *
 * It runs against the real service and the real local backend, so a run started from
 * here moves real files. That is the point: the interesting bugs on a phone are the
 * ones where the screen believes it started something and did not.
 */
class AndroidAppStateTest {

    private class FakePreferences : Preferences {
        override var darkTheme: Boolean = true
        override var defaultDirection: SyncDirection = SyncDirection.TWO_WAY
        override var defaultCompare: CompareMode = CompareMode.MTIME_AND_SIZE
        override var defaultConflict: ConflictPolicy = ConflictPolicy.ASK
        override var defaultDelete: DeletePolicy = DeletePolicy.RECYCLE
        override var sharedRoots: List<String> = emptyList()
        override var sharePort: Int = 0
        override var lastPeer: String = ""
    }

    private val temporaries = mutableListOf<Path>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val states = mutableListOf<AndroidAppState>()

    private fun tempDir(prefix: String): Path = Files.createTempDirectory(prefix).also { temporaries.add(it) }

    private fun state(appDirectory: File = tempDir("app-").toFile()): AndroidAppState {
        val log = AppLog()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob()).also { scopes.add(it) }
        val created = AndroidAppState(
            service = SyncService(stateDirectory = tempDir("state-"), log = log),
            server = ServerController(log),
            preferences = FakePreferences(),
            scope = scope,
            appDirectory = appDirectory,
        )
        states.add(created)
        return created
    }

    @AfterTest
    fun cleanUp() {
        states.forEach { runCatching { it.close() } }
        states.clear()
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
        name = "手机测试",
        direction = SyncDirection.UPDATE_LEFT_TO_RIGHT,
        pairs = pairs,
    )

    @Test
    fun a_detail_is_a_place_and_the_back_arrow_leaves_it() {
        val state = state()
        state.go(Tab.SYNC)
        assertEquals(Tab.SYNC, state.tab)
        assertEquals(Route.Tabs, state.route)

        state.open(Route.ConfigDetail("cfg-1"))
        assertTrue(state.route is Route.ConfigDetail)

        assertTrue(state.back(), "the back arrow should have somewhere to go")
        assertEquals(Route.Tabs, state.route)
        assertFalse(state.back(), "there is nothing behind the tabs")

        // Choosing a tab always returns to the list, even from a detail.
        state.open(Route.JobDetail("job-1"))
        state.go(Tab.JOBS)
        assertEquals(Route.Tabs, state.route)
        assertEquals(Tab.JOBS, state.tab)
    }

    @Test
    fun a_configuration_without_folder_pairs_is_refused_with_a_reason() {
        val state = state()
        state.runConfig(config(emptyList()), dryRun = false)
        assertEquals(NoticeKind.WARNING, state.notice?.kind)
        assertTrue(state.notice!!.text.contains("文件夹对"))
        assertNull(state.running)
        assertTrue(state.jobs.isEmpty())
    }

    @Test
    fun a_bad_peer_address_is_reported_without_opening_a_connection() {
        val state = state()
        state.connectPeer("192.168.1.10")
        assertEquals("「192.168.1.10」看起来不是 HOST:PORT", state.peerError)
        assertTrue(state.peers.isEmpty())
    }

    @Test
    fun a_new_configuration_starts_from_the_preferences() {
        val state = state()
        state.preferences.defaultDirection = SyncDirection.MIRROR_RIGHT_TO_LEFT
        state.preferences.defaultDelete = DeletePolicy.PROPAGATE
        val draft = state.newConfigDraft()
        assertEquals(SyncDirection.MIRROR_RIGHT_TO_LEFT, draft.direction)
        assertEquals(DeletePolicy.PROPAGATE, draft.delete)
        assertTrue(draft.pairs.isEmpty())
    }

    @Test
    fun only_folders_that_exist_are_offered() {
        val existing = tempDir("real-").toFile()
        val state = state(appDirectory = existing)
        val roots = state.localRoots()
        assertTrue(roots.contains(existing.absolutePath), roots.toString())
        // Nothing is offered that a person would then have to correct by hand.
        assertTrue(roots.all { File(it).isDirectory }, roots.toString())
    }

    @Test
    fun saving_and_deleting_a_configuration_updates_the_list_and_leaves_the_detail() {
        val state = state()
        val draft = state.newConfigDraft()
        state.saveConfig(draft)
        assertEquals(listOf(draft.configId), state.configs.map { it.configId })

        state.open(Route.ConfigDetail(draft.configId))
        state.deleteConfig(draft.configId)
        assertTrue(state.configs.isEmpty())
        assertEquals(Route.Tabs, state.route, "deleting what is open has to leave the screen")
    }

    @Test
    fun a_run_started_from_the_phone_moves_files_and_opens_the_result() = runBlocking {
        val left = tempDir("left-")
        val right = tempDir("right-")
        left.resolve("holiday.txt").toFile().writeText("holiday")

        val state = state()
        state.saveConfig(config(listOf(pair(left, right))))
        state.runConfig(config(listOf(pair(left, right))), dryRun = false)

        awaitUntil("the run to finish") { state.running == null && state.jobs.isNotEmpty() }

        assertEquals("holiday", right.resolve("holiday.txt").toFile().readText())
        assertTrue(state.route is Route.JobDetail, "the result is what a person wants to see next")
        val job = assertNotNull(state.job((state.route as Route.JobDetail).jobId))
        assertEquals(1, job.result.stats.copiedFiles)
        assertEquals(NoticeKind.SUCCESS, state.notice?.kind)
    }

    @Test
    fun the_server_can_be_started_and_stopped_from_the_phone() {
        val shared = tempDir("shared-")
        val state = state()
        state.addRoot(shared.toString())
        state.addRoot(shared.toString())
        assertEquals(1, state.rootDraft.size, "adding the same folder twice is a no-op")
        assertEquals(state.rootDraft, state.preferences.sharedRoots)

        state.startServer(port = 0, nodeName = "phone-test")
        assertTrue(state.serverState.running, state.serverState.error)
        assertTrue(state.serverState.port > 0)

        state.stopServer()
        assertFalse(state.serverState.running)
    }

    private suspend fun awaitUntil(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(50)
        }
        throw AssertionError("timed out waiting for $what")
    }
}
