package io.github.cubsync.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The settings screen's contents, and the rule that decides how far a change reaches.
 *
 * The scope rule is the reason this file exists. "I changed the sort order and nothing
 * happened" is what a wrong precedence looks like from the outside, and it is invisible
 * to a test that only checks the setter.
 */
class AppSettingsTest {

    private fun settings(): AppSettings = AppSettings(SettingsStorage.memory())

    @Test
    fun defaults_are_the_ones_the_design_states() {
        val s = settings()
        assertEquals(DisplayMode.DETAIL, s.defaultDisplayMode)
        assertEquals(100, s.itemScale)
        assertTrue(s.showMetadata)
        assertTrue(s.headerAdaptive)
        assertEquals(Header.DEFAULT_LINES, s.headerLines)
        assertEquals(PaneDirection.AUTO, s.paneDirection)
        assertEquals(Buffering.DEFAULT, s.bufferBlocks)
        assertTrue(s.resumeEnabled)
        assertTrue(s.deleteToTrash)
        // 跟随系统 is the prototype's default on both ends; the phone hands it to
        // isSystemInDarkTheme and the desktop, which has no such query, draws dark.
        assertEquals(ThemeMode.SYSTEM, s.theme)
        // No accent is chosen until somebody chooses one: the desktop then uses the
        // prototype's --win-accent and the phone its --m3-primary, which stay apart.
        assertEquals("", s.accent)
    }

    @Test
    fun numbers_are_brought_into_range_rather_than_stored_out_of_it() {
        val s = settings()
        s.rowDensity = 9_000
        assertEquals(Density.MAX, s.rowDensity)
        s.rowDensity = -4
        assertEquals(Density.MIN, s.rowDensity)
        s.itemScale = 1_000
        assertEquals(ItemScale.MAX, s.itemScale)
        s.columns = 99
        assertEquals(Columns.MAX, s.columns)
        s.headerLines = 12
        assertEquals(Header.MAX_LINES, s.headerLines)
    }

    @Test
    fun values_survive_a_round_trip_through_the_store() {
        val storage = SettingsStorage.memory()
        val first = AppSettings(storage)
        first.defaultDisplayMode = DisplayMode.THUMBNAIL
        first.columns = 5
        first.theme = ThemeMode.LIGHT
        first.accent = "#2E9E5B"
        first.defaultReceiveDir = "D:/inbox"
        first.deleteToTrash = false

        val second = AppSettings(storage)
        assertEquals(DisplayMode.THUMBNAIL, second.defaultDisplayMode)
        assertEquals(5, second.columns)
        assertEquals(ThemeMode.LIGHT, second.theme)
        assertEquals("#2E9E5B", second.accent)
        assertEquals("D:/inbox", second.defaultReceiveDir)
        assertFalse(second.deleteToTrash)
    }

    @Test
    fun a_folder_change_beats_a_pane_change_which_beats_a_shared_one() {
        val s = settings()
        val path = "D:/photos"

        // Nothing written yet: the factory default answers.
        assertEquals(DisplayMode.DETAIL, s.prefsFor(Side.LEFT, path).displayMode)

        // Shared, then this pane, then this folder — each one more specific.
        s.apply(Side.LEFT, path, Scope.BOTH_PANES, BrowsePrefs(displayMode = DisplayMode.COMPACT))
        assertEquals(DisplayMode.COMPACT, s.prefsFor(Side.LEFT, path).displayMode)
        assertEquals(DisplayMode.COMPACT, s.prefsFor(Side.RIGHT, "D:/other").displayMode)

        s.apply(Side.LEFT, path, Scope.THIS_PANE, BrowsePrefs(displayMode = DisplayMode.CARD))
        assertEquals(DisplayMode.CARD, s.prefsFor(Side.LEFT, "D:/elsewhere").displayMode)

        s.apply(Side.LEFT, path, Scope.CURRENT_FOLDER, BrowsePrefs(displayMode = DisplayMode.THUMBNAIL))
        assertEquals(DisplayMode.THUMBNAIL, s.prefsFor(Side.LEFT, path).displayMode)
        // The pane's other folders still answer with the pane's value.
        assertEquals(DisplayMode.CARD, s.prefsFor(Side.LEFT, "D:/elsewhere").displayMode)
        // And the other pane is untouched.
        assertEquals(DisplayMode.COMPACT, s.prefsFor(Side.RIGHT, path).displayMode)
    }

    @Test
    fun writing_at_a_wider_scope_clears_the_narrower_records_that_would_shadow_it() {
        val s = settings()
        val path = "D:/photos"
        s.apply(Side.LEFT, path, Scope.CURRENT_FOLDER, BrowsePrefs(sortKey = SortKey.SIZE))
        s.apply(Side.LEFT, "D:/other", Scope.CURRENT_FOLDER, BrowsePrefs(sortKey = SortKey.MODIFIED))

        s.apply(Side.LEFT, path, Scope.THIS_PANE, BrowsePrefs(sortKey = SortKey.NAME))
        // Without the cleanup the folder record would still win and the change would look
        // like it did nothing.
        assertEquals(SortKey.NAME, s.prefsFor(Side.LEFT, path).sortKey)

        s.apply(Side.LEFT, path, Scope.BOTH_PANES, BrowsePrefs(sortKey = SortKey.SIZE))
        assertEquals(SortKey.SIZE, s.prefsFor(Side.LEFT, path).sortKey)
        assertEquals(SortKey.SIZE, s.prefsFor(Side.RIGHT, path).sortKey)
        assertTrue(
            s.snapshot().keys.none { it.startsWith("browse.pane.") || it.startsWith("browse.folder.") },
            "a shared change has to clear both panes' narrower layers",
        )
    }

    @Test
    fun favourites_are_kept_per_device_and_toggle() {
        val s = settings()
        val favourite = Favorite(device = Words.LOCAL, backendId = EndpointId.LOCAL, path = "D:/photos")
        s.toggleFavourite(favourite)
        // Keyed on the backend and the path: the display name of an end can change, and
        // "本端" is a label rather than an address.
        assertTrue(s.isFavourite(EndpointId.LOCAL, "D:/photos"))
        assertEquals(1, s.favourites.size)

        s.toggleFavourite(favourite)
        assertFalse(s.isFavourite(EndpointId.LOCAL, "D:/photos"))

        s.favourites = listOf(
            favorite("本端", "D:/photos"),
            favorite("Redmi", "/sdcard/DCIM", backendId = "lan:192.168.1.20:43917"),
        )
        val reloaded = AppSettings(SettingsStorage.memory(s.snapshot()))
        assertEquals(2, reloaded.favourites.size)
        assertEquals("Redmi", reloaded.favourites[1].device)
        assertEquals("/sdcard/DCIM", reloaded.favourites[1].path)
    }

    private fun favorite(device: String, path: String, backendId: String = EndpointId.LOCAL) =
        Favorite(device, backendId, path)

    @Test
    fun the_peer_list_is_newest_first_and_bounded() {
        val s = settings()
        repeat(AppSettings.KNOWN_PEER_LIMIT + 4) { index ->
            s.rememberPeer(KnownPeer("device-$index", "10.0.0.$index", 43917))
        }
        assertEquals(AppSettings.KNOWN_PEER_LIMIT, s.knownPeers.size)
        assertEquals("device-${AppSettings.KNOWN_PEER_LIMIT + 3}", s.knownPeers.first().name)

        // Reaching the same peer again moves it up rather than adding it twice.
        s.rememberPeer(KnownPeer("device-4", "10.0.0.4", 43917))
        assertEquals("device-4", s.knownPeers.first().name)
        assertEquals(1, s.knownPeers.count { it.host == "10.0.0.4" })
    }

    @Test
    fun header_lines_follow_the_adaptive_rule() {
        // Adaptive on, two narrow panes: the requested count is used.
        assertEquals(2, Header.linesFor(adaptive = true, requested = 2, paneWidthDp = 190f, paneCount = 2))
        // Adaptive on, one pane: one line, whatever was asked for.
        assertEquals(1, Header.linesFor(adaptive = true, requested = 3, paneWidthDp = 190f, paneCount = 1))
        // Adaptive on, a wide pane: one line.
        assertEquals(1, Header.linesFor(adaptive = true, requested = 3, paneWidthDp = 420f, paneCount = 2))
        // Adaptive off: the number is the answer.
        assertEquals(3, Header.linesFor(adaptive = false, requested = 3, paneWidthDp = 420f, paneCount = 2))
        assertEquals(4, Header.clampLines(9))
        assertEquals(1, Header.clampLines(0))
    }

    @Test
    fun the_header_grows_with_its_lines_instead_of_squeezing_the_list() {
        assertEquals(16 + 13, Header.heightDp(1))
        assertEquals(16 + 2 * 13, Header.heightDp(2))
    }
}
