package io.github.cubsync.desktop

import io.github.cubsync.core.model.Endpoint
import io.github.cubsync.core.model.FolderPair
import io.github.cubsync.core.model.SyncConfig
import io.github.cubsync.service.AppLog
import io.github.cubsync.service.DisplayMode
import io.github.cubsync.service.EndpointId
import io.github.cubsync.service.PathTools
import io.github.cubsync.service.Section
import io.github.cubsync.service.ServerController
import io.github.cubsync.service.ServerState
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
 * The window's own behaviour: navigation, what a run leaves behind, and the two-pane
 * workflow as a person drives it.
 *
 * The rules about *what* a sync does are tested in the layers that own them. What is
 * tested here is that the window wires them together — that a button does the thing a
 * button is supposed to do, which is exactly what a screenshot cannot check.
 *
 * Most of what a screen starts is asynchronous, so the assertions wait for the effect
 * rather than for the call to return. Waiting on the effect is also the honest test:
 * "the file is there" is the thing the user sees, and "the function returned" is not.
 */
class AppStateTest {

    private class Fixture {
        val directory: Path = Files.createTempDirectory("cubsync-desktop-test-")
        val log = AppLog()
        val desktop = DesktopSettings.inStateDirectory(directory)
        val service = SyncService(stateDirectory = directory, log = log, settingsStorage = desktop)
        val server = ServerController(log)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val state = AppState(service, server, desktop, scope)

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

    /** Waits for something the screen started to actually happen. */
    private fun await(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("waited ${timeoutMs}ms for: $what")
    }

    private fun pairing(left: Path, right: Path, id: String) = SyncConfig(
        configId = "cfg-$id",
        name = "运行 $id",
        pairs = listOf(
            FolderPair(
                pairId = "pair-$id",
                order = 0,
                left = Endpoint(EndpointId.LOCAL, left.toAbsolutePath().toString().replace('\\', '/')),
                right = Endpoint(EndpointId.LOCAL, right.toAbsolutePath().toString().replace('\\', '/')),
            ),
        ),
    )

    // ------------------------------------------------------------------ navigation

    @Test
    fun the_window_starts_on_the_connect_screen() = withFixture { fixture ->
        assertEquals(Section.CONNECT, fixture.state.section)
        assertTrue(fixture.state.statusLine(ServerState()).startsWith("未共享"))
    }

    @Test
    fun going_to_a_screen_changes_which_one_is_open() = withFixture { fixture ->
        fixture.state.goTo(Section.TRANSFER)
        assertEquals(Section.TRANSFER, fixture.state.section)
        fixture.state.goTo(Section.SETTINGS)
        assertEquals(Section.SETTINGS, fixture.state.section)
    }

    // ------------------------------------------------------------ configurations

    @Test
    fun a_new_configuration_gets_a_name_that_is_free() = withFixture { fixture ->
        fixture.state.saveConfig(fixture.state.newConfigDraft())
        fixture.state.saveConfig(fixture.state.newConfigDraft())

        assertEquals(2, fixture.state.configs.size)
        assertEquals(2, fixture.state.configs.map { it.name }.toSet().size)
    }

    @Test
    fun saving_selects_the_configuration_and_deleting_clears_the_selection() = withFixture { fixture ->
        val config = fixture.state.newConfigDraft()
        fixture.state.saveConfig(config)
        assertEquals(config.configId, fixture.state.selectedConfigId)

        fixture.state.deleteConfig(config.configId)
        assertTrue(fixture.state.configs.isEmpty())
        assertNull(fixture.state.selectedConfigId)
    }

    @Test
    fun a_run_has_to_have_something_to_do() = withFixture { fixture ->
        fixture.state.runConfig(fixture.state.newConfigDraft(), dryRun = false)

        assertNull(fixture.state.running)
        assertNotNull(fixture.state.notice)
        assertTrue(fixture.state.notice!!.text.contains("文件夹对"))
    }

    // ------------------------------------------------------------------------ runs

    @Test
    fun a_run_that_moves_a_file_records_itself_and_opens_the_jobs_screen() = withFixture { fixture ->
        val left = Files.createTempDirectory("cubsync-run-left-")
        val right = Files.createTempDirectory("cubsync-run-right-")
        Files.write(left.resolve("holiday.txt"), "holiday".toByteArray())

        val config = pairing(left, right, "run")
        fixture.service.configs.save(config)
        fixture.state.reloadConfigs()

        fixture.state.runConfig(config, dryRun = false)

        await("the run to finish") { fixture.state.jobs.isNotEmpty() }
        assertEquals(Section.JOBS, fixture.state.section)
        assertEquals(1, fixture.state.jobs.first().result.stats.copiedFiles)
        assertEquals("holiday", right.resolve("holiday.txt").toFile().readText())
    }

    @Test
    fun a_preview_compares_without_recording_itself_as_a_run() = withFixture { fixture ->
        val left = Files.createTempDirectory("cubsync-preview-left-")
        val right = Files.createTempDirectory("cubsync-preview-right-")
        Files.write(left.resolve("holiday.txt"), "holiday".toByteArray())

        val config = pairing(left, right, "preview")
        fixture.service.configs.save(config)
        fixture.state.reloadConfigs()

        fixture.state.previewConfig(config)

        await("the preview to finish") { fixture.state.preview != null && !fixture.state.previewing }
        assertTrue(fixture.state.jobs.isEmpty(), "a preview is not a run and must not appear in the history")
        assertTrue(!right.resolve("holiday.txt").toFile().exists(), "a preview must not write anything")
    }

    // -------------------------------------------------------------------- browsing

    @Test
    fun the_two_panes_start_at_the_folders_this_machine_can_reach() = withFixture { fixture ->
        val roots = fixture.state.localRoots()
        assertTrue(roots.isNotEmpty(), "a pane must always have somewhere to go")
        assertTrue(roots.all { it.isNotBlank() })
    }

    @Test
    fun opening_a_folder_lists_it_and_entering_a_subfolder_narrows_the_path() = withFixture { fixture ->
        val dir = Files.createTempDirectory("cubsync-pane-")
        Files.createDirectories(dir.resolve("holiday"))
        Files.write(dir.resolve("notes.txt"), "notes".toByteArray())

        // One spelling of the folder, used both to open the pane and to assert where it came
        // back to.
        //
        // The first version opened the pane with `dir.toString()` and asserted against
        // `dir.toAbsolutePath()`. Those are the same string only when the test worker's
        // `java.io.tmpdir` is already absolute, which is not something a test gets to
        // assume — and when they differ the pane is *already* where the assertion wants it
        // while the two strings never match. That is how this test waited fifteen seconds
        // for the folder it was sitting in.
        val root = PathTools.normalizeRoot(dir.toAbsolutePath().toString())

        fixture.state.openPane(Side.LEFT, root)
        await("the listing") { fixture.state.browsing.current(Side.LEFT).entries.size == 2 }
        assertTrue(fixture.state.browsing.current(Side.LEFT).entries.any { it.relPath == "holiday" && it.isDirectory })

        fixture.state.browsing.enter(Side.LEFT, "holiday")
        await("the subfolder") { PathTools.leafName(fixture.state.browsing.current(Side.LEFT).path) == "holiday" }

        // 这一条测的是**导航**：栏位回到上一层。`up()` 是同步生效的（临时探针实测：
        // path 一步从 <root>/holiday 变回 <root>），所以直接断言，而不是等十五秒 ——
        // 等待会把「导航错了」和「列表慢」混成一个超时，而超时什么也不说。
        val inside = fixture.state.browsing.current(Side.LEFT).path
        fixture.state.browsing.up(Side.LEFT)
        assertEquals(
            io.github.cubsync.service.Browsing.parent(inside),
            fixture.state.browsing.current(Side.LEFT).path,
            "up() has to move the pane from $inside to its parent",
        )
    }

    @Test
    fun transferring_moves_the_selection_and_leaves_the_rest_alone() = withFixture { fixture ->
        val left = Files.createTempDirectory("cubsync-xfer-left-")
        val right = Files.createTempDirectory("cubsync-xfer-right-")
        Files.write(left.resolve("keep.txt"), "keep".toByteArray())
        Files.write(left.resolve("send.txt"), "send".toByteArray())

        fixture.state.openPane(Side.LEFT, left.toString())
        fixture.state.openPane(Side.RIGHT, right.toString())
        await("both listings") { fixture.state.browsing.current(Side.LEFT).entries.size == 2 }

        fixture.state.browsing.select(Side.LEFT, listOf("send.txt"))
        fixture.state.transfer(Side.LEFT)

        await("the transfer") { right.resolve("send.txt").toFile().exists() }
        assertTrue(!right.resolve("keep.txt").toFile().exists(), "an unselected file must not travel")
    }

    @Test
    fun a_new_folder_is_created_without_colliding() = withFixture { fixture ->
        val dir = Files.createTempDirectory("cubsync-mkdir-")
        fixture.state.openPane(Side.LEFT, dir.toString())
        fixture.state.newFolder(Side.LEFT)
        fixture.state.newFolder(Side.LEFT)

        try {
            await("two folders") { Files.list(dir).use { it.count() } == 2L }
        } catch (failure: AssertionError) {
            // The notice says why, which is the difference between a failing test that
            // explains itself and one that has to be reproduced by hand.
            throw AssertionError("${failure.message}; notice was: ${fixture.state.notice?.text}", failure)
        }
    }

    @Test
    fun deleting_a_row_sends_it_to_the_trash_rather_than_destroying_it() = withFixture { fixture ->
        val dir = Files.createTempDirectory("cubsync-delete-")
        Files.write(dir.resolve("draft.txt"), "draft".toByteArray())
        fixture.state.openPane(Side.LEFT, dir.toString())
        await("the listing") { fixture.state.browsing.current(Side.LEFT).entries.isNotEmpty() }

        fixture.state.browsing.select(Side.LEFT, listOf("draft.txt"))
        fixture.state.deleteSelection(Side.LEFT)

        await("the delete") { !dir.resolve("draft.txt").toFile().exists() }
        // The local backend keeps each root's trash beside it, which is what makes this
        // recoverable — the reason the default is the trash.
        assertTrue(Files.isDirectory(dir.resolve(".cub_trash")), "a delete should leave something to recover from")
    }

    @Test
    fun renaming_a_row_changes_its_name_and_nothing_else() = withFixture { fixture ->
        val dir = Files.createTempDirectory("cubsync-rename-")
        Files.write(dir.resolve("before.txt"), "content".toByteArray())
        fixture.state.openPane(Side.LEFT, dir.toString())
        await("the listing") { fixture.state.browsing.current(Side.LEFT).entries.isNotEmpty() }

        fixture.state.rename(Side.LEFT, "before.txt", "after.txt")

        await("the rename") { dir.resolve("after.txt").toFile().exists() }
        assertTrue(!dir.resolve("before.txt").toFile().exists())
        assertEquals("content", dir.resolve("after.txt").toFile().readText())
    }

    // ----------------------------------------------------------------- preferences

    @Test
    fun the_display_mode_is_kept_per_pane() = withFixture { fixture ->
        val left = Files.createTempDirectory("cubsync-mode-left-")
        val right = Files.createTempDirectory("cubsync-mode-right-")
        fixture.state.openPane(Side.LEFT, left.toString())
        fixture.state.openPane(Side.RIGHT, right.toString())

        fixture.state.setDisplayMode(Side.LEFT, DisplayMode.THUMBNAIL)
        assertEquals(DisplayMode.THUMBNAIL, fixture.state.browsing.prefsOf(Side.LEFT).displayMode)
        assertEquals(
            DisplayMode.DETAIL,
            fixture.state.browsing.prefsOf(Side.RIGHT).displayMode,
            "changing one pane's mode must not change the other's",
        )
    }

    @Test
    fun the_row_density_is_global_so_both_panes_agree() = withFixture { fixture ->
        fixture.state.changeSettings { it.rowDensity = 140 }
        assertEquals(140, fixture.service.settings.rowDensity)
        assertEquals(
            fixture.state.browsing.prefsOf(Side.LEFT).displayMode,
            fixture.state.browsing.prefsOf(Side.RIGHT).displayMode,
        )
    }

    @Test
    fun favouriting_the_current_folder_puts_it_in_the_drawer() = withFixture { fixture ->
        val dir = Files.createTempDirectory("cubsync-fav-")
        fixture.state.openPane(Side.LEFT, dir.toString())
        fixture.state.toggleFavourite(Side.LEFT)

        val favourites = fixture.service.settings.favourites
        assertEquals(1, favourites.size)
        assertTrue(favourites.first().path.endsWith(dir.fileName.toString()))
        assertTrue(fixture.state.drawer().favourites.isNotEmpty())
    }

    @Test
    fun the_drawer_lists_this_machine_even_before_anything_is_shared() = withFixture { fixture ->
        val drawer = fixture.state.drawer()
        assertTrue(drawer.accounts.any { it.role.contains("未共享") })
        assertTrue(drawer.accounts.none { it.online })
    }

    // ----------------------------------------------------------------------- peers

    @Test
    fun a_peer_that_is_not_there_is_refused_rather_than_pretended_at() = withFixture { fixture ->
        // Port 1 on the loopback address refuses immediately, which is what a peer that
        // is not running looks like — as opposed to an unroutable address, which hangs.
        fixture.state.connectPeer("127.0.0.1:1")

        await("the refusal") { fixture.state.peerError != null }
        assertTrue(fixture.state.peers.isEmpty())
    }

    @Test
    fun a_malformed_address_is_rejected_without_a_connection_attempt() = withFixture { fixture ->
        fixture.state.connectPeer("not an address")
        assertEquals("「not an address」看起来不是 HOST:PORT", fixture.state.peerError)
    }
}
