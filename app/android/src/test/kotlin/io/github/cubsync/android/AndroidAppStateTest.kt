package io.github.cubsync.android

import io.github.cubsync.core.model.Endpoint
import io.github.cubsync.core.model.FolderPair
import io.github.cubsync.core.model.SyncConfig
import io.github.cubsync.service.AppLog
import io.github.cubsync.service.DisplayMode
import io.github.cubsync.service.EndpointId
import io.github.cubsync.service.Section
import io.github.cubsync.service.ServerController
import io.github.cubsync.service.SettingsStorage
import io.github.cubsync.service.Side
import io.github.cubsync.service.SyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The phone's own behaviour: which screen is open, how the two panes are shown, and that
 * a second-level screen still belongs to a tab.
 *
 * The rules about what a sync does are tested where they live. What is tested here is that
 * the phone's navigation and the two-pane workflow behave the way the design describes —
 * including the parts that differ from the desktop on purpose.
 */
class AndroidAppStateTest {

    private class Fixture {
        val directory: Path = Files.createTempDirectory("cubsync-phone-test-")
        val log = AppLog()
        val service = SyncService(
            stateDirectory = directory,
            log = log,
            settingsStorage = SettingsStorage.memory(),
        )
        val server = ServerController(log)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val state = AndroidAppState(service, server, scope, directory.toFile())

        fun close() {
            runCatching { state.close() }
            scope.cancel()
        }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val fixture = Fixture()
        try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    private fun await(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("waited ${timeoutMs}ms for: $what")
    }

    @Test
    fun the_bottom_bar_has_five_entries_and_the_log_is_not_one_of_them() {
        val onBar = Section.entries.filter { it.onPhoneBar }
        assertEquals(5, onBar.size)
        assertTrue(onBar.none { it == Section.LOGS }, "the log belongs to the job it came from")
        assertEquals(
            listOf(Section.CONNECT, Section.TRANSFER, Section.SYNC, Section.JOBS, Section.SETTINGS),
            onBar,
        )
    }

    @Test
    fun the_phone_starts_on_the_connect_screen_with_no_second_level_open() = withFixture { fixture ->
        assertEquals(Section.CONNECT, fixture.state.tab)
        assertEquals(Route.Tabs, fixture.state.route)
    }

    @Test
    fun going_to_a_tab_closes_whatever_second_level_screen_was_open() = withFixture { fixture ->
        fixture.state.open(Route.Config("cfg-1"))
        fixture.state.go(Section.TRANSFER)
        assertEquals(Route.Tabs, fixture.state.route)
        assertEquals(Section.TRANSFER, fixture.state.tab)
    }

    @Test
    fun a_second_level_screen_keeps_its_parent_tab_lit() = withFixture { fixture ->
        fixture.state.go(Section.JOBS)
        fixture.state.open(Route.Job("job-1"))
        assertEquals(
            Section.JOBS,
            fixture.state.tab,
            "a page with no parent tab is what the design forbids",
        )
        fixture.state.back()
        assertEquals(Route.Tabs, fixture.state.route)
        assertEquals(Section.JOBS, fixture.state.tab)
    }

    @Test
    fun a_new_configuration_gets_a_name_that_is_free() = withFixture { fixture ->
        fixture.state.saveConfig(fixture.state.newConfigDraft())
        fixture.state.saveConfig(fixture.state.newConfigDraft())
        assertEquals(2, fixture.state.configs.size)
        assertEquals(2, fixture.state.configs.map { it.name }.toSet().size)
    }

    @Test
    fun deleting_the_configuration_that_is_open_closes_its_screen() = withFixture { fixture ->
        val config = fixture.state.newConfigDraft()
        fixture.state.saveConfig(config)
        fixture.state.open(Route.Config(config.configId))

        fixture.state.deleteConfig(config.configId)

        assertEquals(Route.Tabs, fixture.state.route, "the screen it was showing no longer exists")
        assertTrue(fixture.state.configs.isEmpty())
    }

    @Test
    fun a_run_has_to_have_something_to_do() = withFixture { fixture ->
        fixture.state.runConfig(fixture.state.newConfigDraft(), dryRun = false)
        assertNull(fixture.state.running)
        assertNotNull(fixture.state.notice)
        assertTrue(fixture.state.notice!!.text.contains("文件夹对"))
    }

    @Test
    fun a_run_that_moves_a_file_opens_its_own_detail_screen() = withFixture { fixture ->
        val left = Files.createTempDirectory("cubsync-phone-left-")
        val right = Files.createTempDirectory("cubsync-phone-right-")
        Files.write(left.resolve("holiday.txt"), "holiday".toByteArray())

        val config = pairing(left, right)
        fixture.service.configs.save(config)
        fixture.state.reloadConfigs()

        fixture.state.runConfig(config, dryRun = false)

        await("the run to finish") { fixture.state.jobs.isNotEmpty() }
        assertEquals(Section.JOBS, fixture.state.tab)
        assertEquals(Route.Job(fixture.state.jobs.first().jobId), fixture.state.route)
        assertEquals("holiday", right.resolve("holiday.txt").toFile().readText())
    }

    private fun pairing(left: Path, right: Path) = SyncConfig(
        configId = "cfg-phone",
        name = "手机测试",
        pairs = listOf(
            FolderPair(
                pairId = "pair-phone",
                order = 0,
                left = Endpoint(EndpointId.LOCAL, left.toAbsolutePath().toString().replace('\\', '/')),
                right = Endpoint(EndpointId.LOCAL, right.toAbsolutePath().toString().replace('\\', '/')),
            ),
        ),
    )

    @Test
    fun a_pane_lists_a_folder_and_the_single_pane_switch_is_a_view_not_a_different_session() = withFixture { fixture ->
        val dir = Files.createTempDirectory("cubsync-phone-pane-")
        Files.write(dir.resolve("notes.txt"), "notes".toByteArray())

        fixture.state.openPane(Side.LEFT, dir.toString())
        await("the listing") { fixture.state.browsing.current(Side.LEFT).entries.isNotEmpty() }

        fixture.state.showSoloLocal = true
        // Hiding a pane is a view of the same session, not a second one: the folder is
        // still open when the pane comes back.
        fixture.state.showSoloLocal = false
        assertEquals(1, fixture.state.browsing.current(Side.LEFT).entries.size)
    }

    @Test
    fun the_display_mode_is_kept_per_pane_on_the_phone_too() = withFixture { fixture ->
        val left = Files.createTempDirectory("cubsync-phone-mode-left-")
        val right = Files.createTempDirectory("cubsync-phone-mode-right-")
        fixture.state.openPane(Side.LEFT, left.toString())
        fixture.state.openPane(Side.RIGHT, right.toString())

        fixture.state.setDisplayMode(Side.LEFT, DisplayMode.COMPACT)
        assertEquals(DisplayMode.COMPACT, fixture.state.browsing.prefsOf(Side.LEFT).displayMode)
        assertEquals(DisplayMode.DETAIL, fixture.state.browsing.prefsOf(Side.RIGHT).displayMode)
    }

    @Test
    fun the_drawer_starts_from_what_the_application_already_knows() = withFixture { fixture ->
        val drawer = fixture.state.drawer()
        assertTrue(drawer.accounts.any { it.role.contains("未共享") })
        assertTrue(drawer.favourites.isEmpty())
    }

    @Test
    fun the_run_mode_is_remembered_and_root_says_what_it_needs() = withFixture { fixture ->
        fixture.state.setRunMode(io.github.cubsync.service.RunMode.ROOT)
        assertEquals(io.github.cubsync.service.RunMode.ROOT, fixture.service.settings.runMode)
        assertNotNull(fixture.state.notice)
        assertTrue(fixture.state.notice!!.text.contains("Root"))
    }

    @Test
    fun a_malformed_address_is_rejected_without_a_connection_attempt() = withFixture { fixture ->
        fixture.state.connectPeer("not an address")
        assertEquals("「not an address」看起来不是 HOST:PORT", fixture.state.peerError)
    }
}
