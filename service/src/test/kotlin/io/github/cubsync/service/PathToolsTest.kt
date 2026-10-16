package io.github.cubsync.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PathToolsTest {

    @Test
    fun a_leading_tilde_means_the_home_directory() {
        val home = System.getProperty("user.home")
        assertEquals(home, PathTools.expand("~"))
        assertTrue(PathTools.expand("~/photos").startsWith(home))
        // A tilde anywhere else is an ordinary character, as it is in a shell.
        assertEquals("a~b", PathTools.expand("a~b"))
    }

    @Test
    fun a_root_is_stored_with_forward_slashes_and_no_trailing_separator() {
        assertEquals("C:/Users/me/photos", PathTools.normalizeRoot("C:\\Users\\me\\photos\\"))
        assertEquals("/srv/data", PathTools.normalizeRoot("/srv/data/"))
        assertEquals("/", PathTools.normalizeRoot("/"))
    }

    @Test
    fun a_path_is_recognised_as_inside_another() {
        assertTrue(PathTools.isInside("/srv/data/photos", "/srv/data"))
        assertTrue(PathTools.isInside("/srv/data", "/srv/data"))
        assertFalse(PathTools.isInside("/srv/database", "/srv/data"))
        assertFalse(PathTools.isInside("/srv", "/srv/data"))
    }

    @Test
    fun a_name_collision_gets_a_number_rather_than_overwriting() {
        assertEquals("a.txt", PathTools.uniqueName(emptySet(), "a.txt"))
        assertEquals("a 2.txt", PathTools.uniqueName(setOf("a.txt"), "a.txt"))
        assertEquals("a 4.txt", PathTools.uniqueName(setOf("a.txt", "a 2.txt", "a 3.txt"), "a.txt"))
        assertEquals("noext 2", PathTools.uniqueName(setOf("noext"), "noext"))
    }

    @Test
    fun a_long_path_is_shortened_from_the_front() {
        assertEquals(".../b/c/d", PathTools.shorten("/a/b/c/d", segments = 3))
        assertEquals("/a/b", PathTools.shorten("/a/b", segments = 3))
        assertEquals("d", PathTools.leafName("/a/b/c/d"))
        assertEquals("c", PathTools.leafName("/a/b/c/"))
    }
}
