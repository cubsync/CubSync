package io.github.cubsync.core.engine

import io.github.cubsync.core.model.CompareMode
import io.github.cubsync.core.spi.FileMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TreeComparatorTest {

    private fun file(path: String, size: Long = 100, mtime: Long = 10_000) = FileMeta(path, size, mtime)

    private fun dir(path: String) = FileMeta(path, isDirectory = true)

    private fun compare(
        left: List<FileMeta>,
        right: List<FileMeta>,
        mode: CompareMode = CompareMode.MTIME_AND_SIZE,
        tolerance: Long = 2_000,
        leftHashes: Map<String, String> = emptyMap(),
        rightHashes: Map<String, String> = emptyMap(),
        snapshot: PairSnapshot? = null,
        caseSensitive: Boolean = true,
    ) = TreeComparator(mode, tolerance, caseSensitive)
        .compare(left, right, leftHashes, rightHashes, snapshot)

    private fun kindOf(entries: List<DiffEntry>, path: String): DiffKind =
        entries.first { it.relPath == path }.kind

    @Test
    fun equal_files_within_tolerance_are_equal() {
        val entries = compare(listOf(file("a", 5, 10_000)), listOf(file("a", 5, 11_000)))
        assertEquals(DiffKind.EQUAL, kindOf(entries, "a"))
    }

    @Test
    fun tolerance_is_exactly_the_configured_window() {
        val inside = compare(listOf(file("a", 5, 12_000)), listOf(file("a", 5, 10_000)), tolerance = 2_000)
        assertEquals(DiffKind.EQUAL, kindOf(inside, "a"))

        val outside = compare(listOf(file("a", 5, 12_001)), listOf(file("a", 5, 10_000)), tolerance = 2_000)
        assertEquals(DiffKind.LEFT_NEWER, kindOf(outside, "a"))
    }

    @Test
    fun newer_side_is_reported_per_file() {
        val entries = compare(
            listOf(file("newer-left", 5, 20_000), file("newer-right", 5, 10_000)),
            listOf(file("newer-left", 5, 10_000), file("newer-right", 5, 20_000)),
        )
        assertEquals(DiffKind.LEFT_NEWER, kindOf(entries, "newer-left"))
        assertEquals(DiffKind.RIGHT_NEWER, kindOf(entries, "newer-right"))
    }

    @Test
    fun size_difference_with_equal_timestamps_is_different_not_newer() {
        val entries = compare(listOf(file("a", 5, 10_000)), listOf(file("a", 9, 10_000)))
        assertEquals(DiffKind.DIFFERENT, kindOf(entries, "a"))
    }

    @Test
    fun one_sided_paths_are_reported_by_side() {
        val entries = compare(listOf(file("only-left")), listOf(file("only-right")))
        assertEquals(DiffKind.LEFT_ONLY, kindOf(entries, "only-left"))
        assertEquals(DiffKind.RIGHT_ONLY, kindOf(entries, "only-right"))
        assertEquals(2, entries.size)
    }

    @Test
    fun identical_directories_are_equal_and_a_file_against_a_directory_is_a_mismatch() {
        val equal = compare(listOf(dir("d")), listOf(dir("d")))
        assertEquals(DiffKind.EQUAL, kindOf(equal, "d"))

        val mismatch = compare(listOf(dir("d")), listOf(file("d")))
        assertEquals(DiffKind.TYPE_MISMATCH, kindOf(mismatch, "d"))
    }

    @Test
    fun hash_mode_ignores_timestamps_when_the_content_matches() {
        val entries = compare(
            left = listOf(file("a", 5, 10_000)),
            right = listOf(file("a", 5, 99_000)),
            mode = CompareMode.CONTENT_HASH,
            leftHashes = mapOf("a" to "aaa"),
            rightHashes = mapOf("a" to "aaa"),
        )
        assertEquals(DiffKind.EQUAL, kindOf(entries, "a"))
    }

    @Test
    fun hash_mode_reports_different_content_of_the_same_length() {
        val entries = compare(
            left = listOf(file("a", 5, 10_000)),
            right = listOf(file("a", 5, 10_000)),
            mode = CompareMode.CONTENT_HASH,
            leftHashes = mapOf("a" to "aaa"),
            rightHashes = mapOf("a" to "bbb"),
        )
        assertEquals(DiffKind.DIFFERENT, kindOf(entries, "a"))
    }

    @Test
    fun hash_mode_degrades_to_timestamps_when_no_hash_is_available() {
        val entries = compare(
            left = listOf(file("a", 5, 20_000)),
            right = listOf(file("a", 5, 10_000)),
            mode = CompareMode.CONTENT_HASH,
        )
        assertEquals(DiffKind.LEFT_NEWER, kindOf(entries, "a"))
    }

    @Test
    fun size_only_mode_uses_the_timestamp_to_order_different_sizes() {
        val entries = compare(
            left = listOf(file("a", 5, 20_000)),
            right = listOf(file("a", 9, 10_000)),
            mode = CompareMode.SIZE_ONLY,
        )
        assertEquals(DiffKind.LEFT_NEWER, kindOf(entries, "a"))

        val equal = compare(
            left = listOf(file("a", 5, 20_000)),
            right = listOf(file("a", 5, 10_000)),
            mode = CompareMode.SIZE_ONLY,
        )
        assertEquals(DiffKind.EQUAL, kindOf(equal, "a"))
    }

    @Test
    fun both_sides_changed_since_the_snapshot_is_a_conflict() {
        val snapshot = PairSnapshot(
            pairId = "p",
            entries = mapOf("a" to SnapshotEntry("a", 5, 10_000)),
        )
        val entries = compare(
            left = listOf(file("a", 6, 30_000)),
            right = listOf(file("a", 7, 40_000)),
            snapshot = snapshot,
        )
        assertEquals(DiffKind.CONFLICT, kindOf(entries, "a"))
    }

    @Test
    fun one_sided_change_since_the_snapshot_is_not_a_conflict() {
        val snapshot = PairSnapshot(
            pairId = "p",
            entries = mapOf("a" to SnapshotEntry("a", 5, 10_000)),
        )
        val entries = compare(
            left = listOf(file("a", 6, 30_000)),
            right = listOf(file("a", 5, 10_000)),
            snapshot = snapshot,
        )
        assertEquals(DiffKind.LEFT_NEWER, kindOf(entries, "a"))
    }

    @Test
    fun a_clone_of_a_vanished_path_is_reported_as_a_rename() {
        val snapshot = PairSnapshot(
            pairId = "p",
            entries = mapOf("old/name.bin" to SnapshotEntry("old/name.bin", 5, 10_000)),
        )
        val entries = compare(
            left = listOf(file("new/name.bin", 5, 10_000)),
            right = emptyList(),
            snapshot = snapshot,
        )
        assertEquals(DiffKind.RENAMED, kindOf(entries, "new/name.bin"))
        assertEquals("old/name.bin", entries.first { it.relPath == "new/name.bin" }.renamedFrom)
    }

    @Test
    fun a_new_file_with_a_different_signature_stays_an_addition() {
        val snapshot = PairSnapshot(pairId = "p", entries = mapOf("other" to SnapshotEntry("other", 5, 10_000)))
        val entries = compare(listOf(file("fresh", 7, 20_000)), emptyList(), snapshot = snapshot)
        assertEquals(DiffKind.LEFT_ONLY, kindOf(entries, "fresh"))
    }

    @Test
    fun an_identical_signature_at_a_vanished_path_is_indistinguishable_from_a_rename() {
        // Documented limitation: without a hash, same size and same timestamp is the
        // strongest evidence available, and reporting a rename is the useful reading.
        val snapshot = PairSnapshot(pairId = "p", entries = mapOf("other" to SnapshotEntry("other", 5, 10_000)))
        val entries = compare(listOf(file("fresh", 5, 10_000)), emptyList(), snapshot = snapshot)
        assertEquals(DiffKind.RENAMED, kindOf(entries, "fresh"))
    }

    @Test
    fun the_same_name_in_a_different_case_is_a_conflict_on_a_case_insensitive_root() {
        val entries = compare(
            left = listOf(file("Report.txt")),
            right = listOf(file("report.txt")),
            caseSensitive = false,
        )
        assertEquals(DiffKind.CONFLICT, kindOf(entries, "Report.txt"))
    }

    @Test
    fun two_different_names_in_a_different_case_coexist_on_a_case_sensitive_root() {
        val entries = compare(listOf(file("Report.txt")), listOf(file("report.txt")), caseSensitive = true)
        assertEquals(2, entries.size)
        assertEquals(DiffKind.LEFT_ONLY, kindOf(entries, "Report.txt"))
        assertEquals(DiffKind.RIGHT_ONLY, kindOf(entries, "report.txt"))
    }

    @Test
    fun results_are_sorted_so_a_directory_precedes_its_children() {
        val entries = compare(
            left = listOf(file("b"), file("a/z"), file("a")),
            right = emptyList(),
        )
        assertEquals(listOf("a", "a/z", "b"), entries.map { it.relPath })
    }

    @Test
    fun a_directory_is_created_before_the_files_inside_it() {
        val entries = compare(
            left = listOf(dir("sub"), file("sub/a.txt"), dir("sub/deeper"), file("sub/deeper/b.txt")),
            right = emptyList(),
        )
        assertEquals(
            listOf("sub", "sub/a.txt", "sub/deeper", "sub/deeper/b.txt"),
            entries.map { it.relPath },
        )
        val order = entries.map { it.relPath }
        assertTrue(order.indexOf("sub") < order.indexOf("sub/a.txt"))
        assertTrue(order.indexOf("sub/deeper") < order.indexOf("sub/deeper/b.txt"))
    }

    @Test
    fun path_normalisation_accepts_both_separators_and_trailing_slashes() {
        assertEquals("a/b", TreeComparator.normalize("a\\b\\"))
        assertEquals("a/b", TreeComparator.normalize("./a/b"))
        assertEquals("", TreeComparator.normalize(""))
    }
}
