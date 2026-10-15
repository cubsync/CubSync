package io.github.cubsync.core.engine

import io.github.cubsync.core.model.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafetyTest {

    private fun pair(id: String, backend: String, left: String, right: String) = PairRoots(id, backend, left, right)

    @Test
    fun a_pair_that_nests_inside_itself_is_blocked() {
        val issues = PathOverlap.check(listOf(pair("p", "local", "/data", "/data/backup")), caseSensitive = true)
        assertEquals(1, issues.size)
        assertEquals(OverlapKind.SELF_NESTED, issues.single().kind)
        assertTrue(issues.single().message.contains("overlap"))
    }

    @Test
    fun identical_roots_are_blocked() {
        val issues = PathOverlap.check(listOf(pair("p", "local", "/data", "/data")), caseSensitive = true)
        assertEquals(OverlapKind.SELF_NESTED, issues.single().kind)
    }

    @Test
    fun sibling_folders_are_fine() {
        val issues = PathOverlap.check(
            listOf(
                pair("p", "local", "/data/photos", "/mnt/backup/photos"),
                pair("q", "local", "/data/music", "/mnt/backup/music"),
            ),
            caseSensitive = true,
        )
        assertTrue(issues.isEmpty())
    }

    @Test
    fun a_name_that_merely_starts_with_another_name_is_not_nested() {
        val issues = PathOverlap.check(listOf(pair("p", "local", "/data", "/database")), caseSensitive = true)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun two_pairs_sharing_a_folder_are_reported() {
        val issues = PathOverlap.check(
            listOf(
                pair("p", "local", "/data/a", "/tmp/a"),
                pair("q", "local", "/data/a/sub", "/tmp/b"),
            ),
            caseSensitive = true,
        )
        assertEquals(1, issues.size)
        assertEquals(OverlapKind.CROSS_NESTED, issues.single().kind)
        assertEquals("p", issues.single().pairId)
        assertEquals("q", issues.single().otherPairId)
    }

    @Test
    fun the_same_path_on_two_backends_is_not_a_cross_overlap() {
        val issues = PathOverlap.check(
            listOf(
                pair("p", "local", "/data", "/tmp/a"),
                pair("q", "lan:phone", "/data", "/tmp/b"),
            ),
            caseSensitive = true,
        )
        assertTrue(issues.isEmpty())
    }

    @Test
    fun case_only_differences_depend_on_the_roots_case_sensitivity() {
        val sensitive = PathOverlap.check(listOf(pair("p", "local", "/Data", "/data/x")), caseSensitive = true)
        assertTrue(sensitive.isEmpty())

        val insensitive = PathOverlap.check(listOf(pair("p", "local", "D:/Data", "d:/data/x")), caseSensitive = false)
        assertEquals(1, insensitive.size)
    }

    @Test
    fun windows_separators_and_trailing_slashes_are_normalised_away() {
        val issues = PathOverlap.check(listOf(pair("p", "local", "D:\\Data\\", "D:/Data/backup/")), caseSensitive = false)
        assertEquals(1, issues.size)

        val issues2 = PathOverlap.check(listOf(pair("p", "local", "D:\\Data\\", "E:/Other")), caseSensitive = false)
        assertTrue(issues2.isEmpty())
    }

    @Test
    fun the_trash_layout_is_inside_the_root_it_belongs_to() {
        val path = TrashLayout.trashRelPath("cfg1", "pair1", 1_700_000_000_000, "photos/a.jpg")
        assertEquals(".cub_trash/cfg1/pair1/1700000000000/photos/a.jpg", path)
        assertTrue(TrashLayout.isTrashPath(path))
        assertTrue(TrashLayout.isTrashPath(".cub_trash"))
        assertFalse(TrashLayout.isTrashPath("photos/.cub_trash_fake/a"))
    }

    @Test
    fun identifiers_that_are_unsafe_in_a_path_are_sanitised() {
        val path = TrashLayout.trashRelPath("a b/c", "../evil", 1, "x")
        assertEquals(".cub_trash/a_b_c/___evil/1/x", path)
        assertFalse(path.contains("../evil"))
    }

    @Test
    fun conflict_names_keep_the_extension_and_carry_the_pair_id() {
        val name = ConflictNaming.conflictName("docs/report.tar.gz", "pair-abc", 1_700_000_000_000, Side.RIGHT)
        assertTrue(name.startsWith("docs/report.tar."))
        assertTrue(name.contains("pair-abc"))
        assertTrue(name.contains("right"))
        assertTrue(name.endsWith(".gz"))
    }

    @Test
    fun conflict_names_of_two_pairs_cannot_collide() {
        val a = ConflictNaming.conflictName("x.bin", "pair-1", 1, Side.LEFT)
        val b = ConflictNaming.conflictName("x.bin", "pair-2", 1, Side.LEFT)
        assertFalse(a == b)
        val c = ConflictNaming.conflictName("x.bin", "pair-1", 2, Side.LEFT)
        assertFalse(a == c)
    }

    @Test
    fun a_name_without_an_extension_still_gets_a_suffix() {
        val name = ConflictNaming.conflictName("README", "p", 5, Side.LEFT)
        assertTrue(name.startsWith("README.cubconflict-"))
    }

    @Test
    fun a_file_in_the_root_has_no_directory_prefix() {
        val name = ConflictNaming.conflictName("a.txt", "p", 5, Side.LEFT)
        assertFalse(name.contains("/"))
    }
}
