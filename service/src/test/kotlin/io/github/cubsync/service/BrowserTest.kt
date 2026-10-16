package io.github.cubsync.service

import io.github.cubsync.core.spi.FileMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The browsing arithmetic, as a table.
 *
 * Everything here is a function of its arguments, which is the point: it is the part of
 * the browser that two front ends share, and the part where "size, descending" quietly
 * putting folders at the bottom would be a bug nobody notices until they need a folder.
 */
class BrowserTest {

    private fun file(name: String, size: Long = 0, mtime: Long = 0) =
        FileMeta(relPath = name, size = size, mtimeMs = mtime)

    private fun dir(name: String) = FileMeta(relPath = name, isDirectory = true)

    private val entries = listOf(
        dir("Movies"),
        file("holiday.mp4", size = 400, mtime = 300),
        dir("DCIM"),
        file("Archive.zip", size = 900, mtime = 100),
        file("notes.txt", size = 10, mtime = 200),
    )

    private fun names(list: List<FileMeta>) = list.map { it.relPath }

    // ------------------------------------------------------------------- sorting

    @Test
    fun sorting_by_name_is_case_insensitive_and_folders_come_first() {
        val sorted = Browsing.sort(entries, SortKey.NAME, ascending = true, foldersFirst = true)
        assertEquals(listOf("DCIM", "Movies", "Archive.zip", "holiday.mp4", "notes.txt"), names(sorted))
    }

    @Test
    fun turning_folders_off_mixes_them_into_the_order() {
        val sorted = Browsing.sort(entries, SortKey.NAME, ascending = true, foldersFirst = false)
        assertEquals(listOf("Archive.zip", "DCIM", "holiday.mp4", "Movies", "notes.txt"), names(sorted))
    }

    @Test
    fun sorting_by_size_and_time_descends_correctly() {
        val bySize = Browsing.sort(entries, SortKey.SIZE, ascending = false, foldersFirst = false)
        assertEquals(listOf("Archive.zip", "holiday.mp4", "notes.txt", "Movies", "DCIM"), names(bySize))

        val byTime = Browsing.sort(entries, SortKey.MODIFIED, ascending = false, foldersFirst = false)
        assertEquals(listOf("holiday.mp4", "notes.txt", "Archive.zip", "Movies", "DCIM"), names(byTime))
    }

    @Test
    fun entries_that_compare_equal_still_come_out_in_a_stable_order() {
        val same = listOf(file("b.txt", size = 5, mtime = 1), file("a.txt", size = 5, mtime = 1))
        val once = Browsing.sort(same, SortKey.SIZE, ascending = true, foldersFirst = false)
        val twice = Browsing.sort(once.reversed(), SortKey.SIZE, ascending = true, foldersFirst = false)
        assertEquals(names(once), names(twice), "the tie-break has to be the name")
        assertEquals(listOf("a.txt", "b.txt"), names(once))
    }

    @Test
    fun a_pane_sorts_by_the_preferences_it_was_given() {
        val prefs = BrowsePrefs(sortKey = SortKey.SIZE, ascending = false, foldersFirst = false)
        assertEquals(
            listOf("Archive.zip", "holiday.mp4", "notes.txt", "Movies", "DCIM"),
            names(Browsing.sort(entries, prefs)),
        )
    }

    // --------------------------------------------------------------------- paths

    @Test
    fun a_path_knows_its_parent_and_its_children() {
        assertEquals("D:/photos/holiday", Browsing.parent("D:/photos/holiday/img.jpg"))
        assertEquals("D:", Browsing.parent("D:/photos"))
        assertEquals("/", Browsing.parent("/"))
        assertEquals("/sdcard/DCIM", Browsing.parent("/sdcard/DCIM/Camera"))

        assertEquals("D:/photos/holiday", Browsing.child("D:/photos", "holiday"))
        assertEquals("/sdcard/DCIM", Browsing.child("/sdcard", "DCIM"))
        assertEquals("/DCIM", Browsing.child("/", "DCIM"))
    }

    @Test
    fun a_windows_path_gets_a_drive_as_its_root() {
        assertEquals("D:", PathTools.rootOf("D:/photos/holiday"))
        assertEquals("/", PathTools.rootOf("/sdcard/DCIM"))
        assertEquals("", PathTools.rootOf(""))
    }

    @Test
    fun breadcrumbs_fold_the_middle_when_the_path_is_long() {
        val short = Browsing.crumbs("/sdcard/DCIM/Camera")
        assertEquals(listOf("/", "sdcard", "DCIM", "Camera"), short.map { it.label })
        assertEquals(emptyList(), short.filter { it.collapsed })

        val long = Browsing.crumbs("/sdcard/DCIM/Camera/2026/10/16", maxSegments = 5)
        assertEquals(listOf("/", "...", "2026", "10", "16"), long.map { it.label })
        val folded = long.single { it.collapsed }
        assertEquals("/sdcard/DCIM/Camera", folded.path, "the fold has to carry the path it hides")
        assertEquals("/sdcard/DCIM/Camera/2026/10/16", long.last().path)
    }

    @Test
    fun a_drive_path_folds_the_same_way_without_losing_the_drive() {
        val crumbs = Browsing.crumbs("D:/a/b/c/d/e", maxSegments = 4)
        assertEquals("D:", crumbs.first().label)
        assertEquals("D:/a/b/c/d/e", crumbs.last().path)
        assertTrue(crumbs.any { it.collapsed })
    }

    @Test
    fun typing_a_file_opens_the_folder_it_is_in() {
        val folders = setOf("D:/photos", "/sdcard/DCIM")
        assertEquals("D:/photos", Browsing.resolveTypedPath("D:/photos", { it in folders }))
        assertEquals("D:/photos", Browsing.resolveTypedPath("D:/photos/holiday.jpg", { it in folders }))
        // No extension: assume it is a folder, because that is what people type.
        assertEquals("D:/holiday", Browsing.resolveTypedPath("D:/holiday", { it in folders }))
        // A dotfile is a name, not an extension.
        assertEquals("/sdcard/.configs", Browsing.resolveTypedPath("/sdcard/.configs", { it in folders }))
    }

    // ------------------------------------------------------------------- compare

    @Test
    fun the_compare_view_names_what_is_different() {
        val left = listOf(
            file("same.txt", size = 10, mtime = 1),
            file("only-left.txt", size = 4),
            file("changed.txt", size = 7),
        )
        val right = listOf(
            file("same.txt", size = 10, mtime = 1),
            file("only-right.txt", size = 5),
            file("changed.txt", size = 8),
        )

        val rows = Browsing.compare(left, right)
        assertEquals(listOf("changed.txt", "only-left.txt", "only-right.txt", "same.txt"), rows.map { it.name })
        assertEquals(Difference.CONTENT_DIFFERS, rows.first { it.name == "changed.txt" }.difference)
        assertEquals(Difference.ONLY_LEFT, rows.first { it.name == "only-left.txt" }.difference)
        assertEquals(Difference.ONLY_RIGHT, rows.first { it.name == "only-right.txt" }.difference)
        assertEquals(Difference.SAME, rows.first { it.name == "same.txt" }.difference)
    }

    /** A missing file is the thing the compare view exists to show, so it is kept. */
    @Test
    fun a_row_that_exists_on_one_side_only_is_kept() {
        val rows = Browsing.compare(listOf(file("a.txt")), emptyList())
        assertEquals(1, rows.size)
        assertEquals(null, rows.first().right)
    }

    // ------------------------------------------------------------------- steps

    @Test
    fun only_the_grid_modes_have_a_column_count() {
        assertTrue(DisplayMode.CARD.hasColumns)
        assertTrue(DisplayMode.THUMBNAIL.hasColumns)
        assertFalse(DisplayMode.DETAIL.hasColumns)
        assertFalse(DisplayMode.COMPACT.hasColumns)
        assertFalse(DisplayMode.COMPARE.hasColumns)
    }

    @Test
    fun automatic_arrangement_follows_the_shape_of_the_container() {
        assertEquals(PaneDirection.SIDE_BY_SIDE, resolveDirection(PaneDirection.AUTO, 1000f, 600f))
        assertEquals(PaneDirection.STACKED, resolveDirection(PaneDirection.AUTO, 400f, 800f))
        // An explicit choice is not second-guessed by the shape of the window.
        assertEquals(PaneDirection.STACKED, resolveDirection(PaneDirection.STACKED, 2000f, 400f))
        assertEquals(PaneDirection.SIDE_BY_SIDE, resolveDirection(PaneDirection.SIDE_BY_SIDE, 300f, 900f))
    }

    @Test
    fun density_moves_in_fives_and_stays_inside_its_range() {
        assertEquals(100, Density.clamp(100))
        assertEquals(Density.MIN, Density.clamp(10))
        assertEquals(Density.MAX, Density.clamp(9_999))
        assertFalse(Density.canDecrease(Density.MIN))
        assertFalse(Density.canIncrease(Density.MAX))
        assertTrue(Density.canDecrease(Density.MIN + Density.STEP))
    }
}
