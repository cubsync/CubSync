package io.github.cubsync.service

import io.github.cubsync.core.spi.FileMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The browsing arithmetic, as a table.
 *
 * Order and path handling are the two things a person notices immediately when they
 * are wrong, and the two things that would otherwise be written once per platform.
 */
class BrowserTest {

    private fun file(name: String, size: Long = 0, mtime: Long = 0) =
        FileMeta(relPath = name, size = size, mtimeMs = mtime, isDirectory = false)

    private fun dir(name: String) = FileMeta(relPath = name, isDirectory = true)

    private val sample = listOf(
        file("zeta.txt", size = 10, mtime = 300),
        dir("photos"),
        file("Alpha.txt", size = 30, mtime = 100),
        dir("archive"),
        file("mid.mp4", size = 20, mtime = 200),
    )

    @Test
    fun sorting_by_name_is_case_insensitive_and_folders_come_first() {
        val sorted = Browsing.sort(sample, SortKey.NAME, ascending = true, foldersFirst = true)
        assertEquals(
            listOf("archive", "photos", "Alpha.txt", "mid.mp4", "zeta.txt"),
            sorted.map { it.relPath },
        )
    }

    @Test
    fun turning_folders_off_mixes_them_into_the_order() {
        val sorted = Browsing.sort(sample, SortKey.NAME, ascending = true, foldersFirst = false)
        assertEquals(
            listOf("Alpha.txt", "archive", "mid.mp4", "photos", "zeta.txt"),
            sorted.map { it.relPath },
        )
    }

    @Test
    fun sorting_by_size_and_time_descends_correctly() {
        assertEquals(
            listOf("Alpha.txt", "mid.mp4", "zeta.txt"),
            Browsing.sort(sample, SortKey.SIZE, ascending = false)
                .filterNot { it.isDirectory }.map { it.relPath },
        )
        assertEquals(
            listOf("zeta.txt", "mid.mp4", "Alpha.txt"),
            Browsing.sort(sample, SortKey.MODIFIED, ascending = false)
                .filterNot { it.isDirectory }.map { it.relPath },
        )
    }

    @Test
    fun entries_that_compare_equal_still_come_out_in_a_stable_order() {
        val twins = listOf(file("b.txt", size = 5), file("a.txt", size = 5), file("c.txt", size = 5))
        // Same size, so the tie-break by name is what decides - twice in a row.
        val first = Browsing.sort(twins, SortKey.SIZE)
        val second = Browsing.sort(twins, SortKey.SIZE)
        assertEquals(listOf("a.txt", "b.txt", "c.txt"), first.map { it.relPath })
        assertEquals(first.map { it.relPath }, second.map { it.relPath })
    }

    @Test
    fun a_path_knows_its_parent_and_its_children() {
        assertEquals("/srv/photos/2026", Browsing.child("/srv/photos", "2026"))
        assertEquals("/srv/photos", Browsing.parent("/srv/photos/2026"))
        assertEquals("/srv", Browsing.parent("/srv/photos"))
        assertEquals("/", Browsing.parent("/srv"))
        assertEquals("/", Browsing.parent("/"))
        assertEquals("2026", Browsing.name("/srv/photos/2026"))
        assertEquals("D:/Photos", Browsing.parent("D:/Photos/2026"))
    }

    @Test
    fun breadcrumbs_fold_the_middle_when_the_path_is_long() {
        val short = Browsing.crumbs("/sdcard/DCIM")
        assertEquals(listOf("/", "sdcard", "DCIM"), short.map { it.label })
        assertEquals(listOf("/", "/sdcard", "/sdcard/DCIM"), short.map { it.path })
        assertTrue(short.none { it.collapsed })

        val long = Browsing.crumbs("/sdcard/DCIM/Camera/2026/10/16", maxSegments = 5)
        assertEquals(listOf("/", "...", "2026", "10", "16"), long.map { it.label })
        val folded = long.single { it.collapsed }
        assertEquals("/sdcard/DCIM/Camera", folded.path, "the fold has to carry the path it hides")
        assertEquals("/sdcard/DCIM/Camera/2026/10/16", long.last().path)
    }

    @Test
    fun a_windows_path_gets_a_drive_as_its_root() {
        val crumbs = Browsing.crumbs("D:/Photos/2026")
        assertEquals(listOf("D:", "Photos", "2026"), crumbs.map { it.label })
        assertEquals(listOf("D:/", "D:/Photos", "D:/Photos/2026"), crumbs.map { it.path })
    }

    @Test
    fun density_and_columns_stay_inside_what_the_interface_can_show() {
        val settings = BrowserSettings()
        settings.bumpDensity(-100)
        assertEquals(BrowserSettings.MIN_DENSITY, settings.rowDensity)
        settings.bumpDensity(1_000)
        assertEquals(BrowserSettings.MAX_DENSITY, settings.rowDensity)

        settings.applyColumns(99)
        assertEquals(BrowserSettings.MAX_COLUMNS, settings.columns)
        settings.applyColumns(0)
        assertEquals(BrowserSettings.MIN_COLUMNS, settings.columns)
    }

    @Test
    fun automatic_arrangement_follows_the_shape_of_the_container() {
        val settings = BrowserSettings()
        assertEquals(ArrangementDirection.SIDE_BY_SIDE, settings.resolve(1_200, 800))
        assertEquals(ArrangementDirection.STACKED, settings.resolve(400, 900))
        // A square container is not obviously wide, so it stacks.
        assertEquals(ArrangementDirection.STACKED, settings.resolve(600, 600))

        settings.arrangement = ArrangementDirection.SIDE_BY_SIDE
        assertEquals(ArrangementDirection.SIDE_BY_SIDE, settings.resolve(300, 900))
    }

    @Test
    fun only_the_grid_modes_have_columns() {
        assertTrue(DisplayMode.CARD.grid)
        assertTrue(DisplayMode.THUMBNAIL.grid)
        assertTrue(!DisplayMode.DETAILED.grid)
        assertTrue(!DisplayMode.COMPACT.grid)
        assertTrue(!DisplayMode.COMPARE.grid)
    }
}
