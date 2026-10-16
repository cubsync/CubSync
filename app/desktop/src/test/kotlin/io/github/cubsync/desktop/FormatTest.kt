package io.github.cubsync.desktop

import io.github.cubsync.core.model.CompareMode
import io.github.cubsync.core.model.DeletePolicy
import io.github.cubsync.core.model.SyncDirection
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopSettingsTest {

    private val temporaries = mutableListOf<Path>()

    private fun directory(): Path = Files.createTempDirectory("cubsync-settings-").also { temporaries.add(it) }

    @AfterTest
    fun cleanUp() {
        for (dir in temporaries) {
            runCatching { Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
        temporaries.clear()
    }

    @Test
    fun preferences_survive_a_restart() {
        val dir = directory()
        val first = DesktopSettings.inStateDirectory(dir)
        first.darkTheme = false
        first.defaultDirection = SyncDirection.MIRROR_LEFT_TO_RIGHT
        first.defaultCompare = CompareMode.CONTENT_HASH
        first.defaultDelete = DeletePolicy.PROPAGATE
        first.defaultParallelism = 4
        first.sharedRoots = listOf("D:/Photos", "D:/Documents")
        first.sharePort = 43917
        first.lastPeer = "192.168.1.20:43917"
        first.save()

        val second = DesktopSettings.inStateDirectory(dir)
        assertFalse(second.darkTheme)
        assertEquals(SyncDirection.MIRROR_LEFT_TO_RIGHT, second.defaultDirection)
        assertEquals(CompareMode.CONTENT_HASH, second.defaultCompare)
        assertEquals(DeletePolicy.PROPAGATE, second.defaultDelete)
        assertEquals(4, second.defaultParallelism)
        assertEquals(listOf("D:/Photos", "D:/Documents"), second.sharedRoots)
        assertEquals(43917, second.sharePort)
        assertEquals("192.168.1.20:43917", second.lastPeer)
    }

    @Test
    fun a_missing_file_yields_the_documented_defaults() {
        val settings = DesktopSettings.inStateDirectory(directory())
        assertTrue(settings.darkTheme)
        assertEquals(SyncDirection.TWO_WAY, settings.defaultDirection)
        assertEquals(DeletePolicy.RECYCLE, settings.defaultDelete)
        assertEquals(1, settings.defaultParallelism)
        assertEquals(emptyList(), settings.sharedRoots)
    }

    @Test
    fun a_corrupt_file_does_not_stop_the_window_from_opening() {
        val dir = directory()
        Files.writeString(dir.resolve(DesktopSettings.FILE_NAME), "this is not a properties file\u0000\u0001")
        val settings = DesktopSettings.inStateDirectory(dir)
        assertTrue(settings.darkTheme)
    }

    @Test
    fun nonsense_values_fall_back_instead_of_propagating() {
        val dir = directory()
        Files.writeString(
            dir.resolve(DesktopSettings.FILE_NAME),
            """
            default.direction=TELEPORT
            default.parallelism=999
            theme.dark=perhaps
            """.trimIndent(),
        )
        val settings = DesktopSettings.inStateDirectory(dir)
        assertEquals(SyncDirection.TWO_WAY, settings.defaultDirection)
        assertEquals(DesktopSettings.MAX_PARALLELISM, settings.defaultParallelism)
        assertTrue(settings.darkTheme)
    }
}
