package io.github.cubsync.core.filter

import io.github.cubsync.core.model.FilterMode
import io.github.cubsync.core.model.FilterSet
import io.github.cubsync.core.model.Filters
import io.github.cubsync.core.spi.FileMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PathMatcherTest {

    private fun file(path: String, size: Long = 10, mtimeMs: Long = 1_000) =
        FileMeta(relPath = path, size = size, mtimeMs = mtimeMs)

    private fun dir(path: String) = FileMeta(relPath = path, isDirectory = true)

    @Test
    fun star_does_not_cross_a_directory_boundary() {
        assertTrue(Glob.toRegex("*.tmp").matches("a.tmp"))
        assertFalse(Glob.toRegex("*.tmp").matches("dir/a.tmp"))
        assertTrue(Glob.toRegex("a/*").matches("a/x"))
        assertFalse(Glob.toRegex("a/*").matches("a/b/x"))
    }

    @Test
    fun double_star_crosses_directories() {
        assertTrue(Glob.toRegex("**/*.tmp").matches("a.tmp"))
        assertTrue(Glob.toRegex("**/*.tmp").matches("a/b/c.tmp"))
        assertTrue(Glob.toRegex("cache/**").matches("cache/x/y/z"))
        assertFalse(Glob.toRegex("cache/**").matches("other/x"))
    }

    @Test
    fun question_mark_matches_exactly_one_character() {
        assertTrue(Glob.toRegex("file?.txt").matches("file1.txt"))
        assertFalse(Glob.toRegex("file?.txt").matches("file10.txt"))
        assertFalse(Glob.toRegex("file?.txt").matches("file/.txt"))
    }

    @Test
    fun character_classes_and_negation_work() {
        assertTrue(Glob.toRegex("img[0-9].png").matches("img5.png"))
        assertFalse(Glob.toRegex("img[0-9].png").matches("imgX.png"))
        assertTrue(Glob.toRegex("img[!0-9].png").matches("imgX.png"))
        assertTrue(Glob.toRegex("img[^0-9].png").matches("imgX.png"))
    }

    @Test
    fun escaping_treats_special_characters_literally() {
        assertTrue(Glob.toRegex("a\\*b").matches("a*b"))
        assertFalse(Glob.toRegex("a\\*b").matches("aXb"))
        assertTrue(Glob.toRegex("money\\?.txt").matches("money?.txt"))
    }

    @Test
    fun a_pattern_without_a_slash_matches_at_every_depth() {
        val matcher = PathMatcher(FilterSet(globs = listOf("*.tmp")))
        assertFalse(matcher.accept(file("a.tmp")))
        assertFalse(matcher.accept(file("x/y/z/a.tmp")))
        assertTrue(matcher.accept(file("a.txt")))
        assertTrue(matcher.accept(file("x/y/z/a.txt")))
    }

    @Test
    fun a_pattern_with_a_slash_is_anchored_at_the_root() {
        val matcher = PathMatcher(FilterSet(globs = listOf("build/**")))
        assertFalse(matcher.accept(dir("build")))
        assertFalse(matcher.accept(file("build/out.o")))
        assertTrue(matcher.accept(file("src/build/out.o")))
    }

    @Test
    fun hidden_entries_are_excluded_unless_requested() {
        val excluding = PathMatcher(FilterSet())
        assertFalse(excluding.accept(file(".env")))
        assertFalse(excluding.accept(file("dir/.hidden/x")))
        assertTrue(excluding.accept(file("visible.txt")))

        val including = PathMatcher(FilterSet(includeHidden = true))
        assertTrue(including.accept(file(".env")))
        assertTrue(including.accept(file("dir/.hidden/x")))
    }

    @Test
    fun size_bounds_apply_to_files_but_never_to_directories() {
        val matcher = PathMatcher(FilterSet(minSize = 1_000, maxSize = 2_000))
        assertFalse(matcher.accept(file("small", size = 999)))
        assertTrue(matcher.accept(file("ok", size = 1_500)))
        assertFalse(matcher.accept(file("big", size = 2_001)))
        // A directory has no size of its own, so it must not be pruned by size.
        assertTrue(matcher.accept(dir("any")))
    }

    @Test
    fun time_bounds_are_inclusive() {
        val matcher = PathMatcher(FilterSet(timeAfterMs = 1_000, timeBeforeMs = 2_000))
        assertFalse(matcher.accept(file("old", mtimeMs = 999)))
        assertTrue(matcher.accept(file("exact-low", mtimeMs = 1_000)))
        assertTrue(matcher.accept(file("exact-high", mtimeMs = 2_000)))
        assertFalse(matcher.accept(file("new", mtimeMs = 2_001)))
    }

    @Test
    fun whitelist_mode_inverts_the_globs() {
        val matcher = PathMatcher(FilterSet(mode = FilterMode.WHITELIST, globs = listOf("*.jpg")))
        assertTrue(matcher.accept(file("holiday.jpg")))
        assertTrue(matcher.accept(file("deep/holiday.jpg")))
        assertFalse(matcher.accept(file("notes.txt")))
        // A directory is never pruned by a whitelist: a name that does not match may
        // still hold files that do.
        assertTrue(matcher.acceptDirectory("deep"))
    }

    @Test
    fun an_arbitrary_directory_can_be_pruned_by_a_trailing_slash_pattern() {
        val matcher = PathMatcher(FilterSet(globs = listOf("node_modules/**")))
        assertFalse(matcher.acceptDirectory("node_modules"))
        assertTrue(matcher.acceptDirectory("src"))
    }

    @Test
    fun case_insensitive_matching_is_opt_in() {
        // Default mode excludes what matches, so `*.TMP` means "drop .TMP files".
        val sensitive = PathMatcher(FilterSet(globs = listOf("*.TMP")))
        assertFalse(sensitive.accept(file("a.TMP")))
        assertTrue(sensitive.accept(file("a.tmp")))
        assertTrue(sensitive.accept(file("b.txt")))

        val insensitive = PathMatcher(FilterSet(globs = listOf("*.TMP")), caseSensitive = false)
        assertFalse(insensitive.accept(file("a.tmp")))
        assertFalse(insensitive.accept(file("A.Tmp")))
        assertTrue(insensitive.accept(file("b.txt")))
    }

    @Test
    fun effective_filter_is_the_override_or_the_global_one() {
        val global = FilterSet(globs = listOf("*.tmp"))
        val custom = FilterSet(globs = listOf("*.log"))

        assertEquals(global, Filters.effective(global, null))
        assertEquals(custom, Filters.effective(global, custom))
        // An empty override is how a pair turns filtering off entirely.
        assertEquals(FilterSet(), Filters.effective(global, FilterSet()))
        assertNotEquals(global, Filters.effective(global, FilterSet()))
    }

    @Test
    fun an_empty_filter_accepts_everything_including_hidden_files() {
        val empty = FilterSet(includeHidden = true)
        assertTrue(empty.isEmpty)
        val matcher = PathMatcher(empty)
        assertTrue(matcher.accept(file(".hidden")))
        assertTrue(matcher.accept(file("any/nested/path.txt")))
    }
}
