package io.github.cubsync.desktop

import io.github.cubsync.core.model.CompareMode
import io.github.cubsync.core.model.ConflictPolicy
import io.github.cubsync.core.model.DeletePolicy
import io.github.cubsync.core.model.SyncDirection
import io.github.cubsync.desktop.ui.policySentence
import io.github.cubsync.core.model.FolderPair
import io.github.cubsync.core.model.Endpoint
import io.github.cubsync.core.model.SyncConfig
import io.github.cubsync.service.EndpointId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FormatTest {

    @Test
    fun sizes_read_the_way_a_person_expects() {
        assertEquals("0 B", Format.bytes(0))
        assertEquals("512 B", Format.bytes(512))
        assertEquals("1.0 KiB", Format.bytes(1024))
        assertEquals("1.5 KiB", Format.bytes(1536))
        assertEquals("1.0 MiB", Format.bytes(1024L * 1024))
        assertEquals("1.0 GiB", Format.bytes(1024L * 1024 * 1024))
        // Past a hundred the decimal stops being interesting.
        assertEquals("512 GiB", Format.bytes(512L * 1024 * 1024 * 1024))
    }

    @Test
    fun durations_pick_the_unit_that_fits() {
        assertEquals("0 ms", Format.duration(0))
        assertEquals("850 ms", Format.duration(850))
        assertEquals("1.5 s", Format.duration(1_500))
        assertEquals("2 m 05 s", Format.duration(125_000))
        assertEquals("1 h 02 m", Format.duration(3_720_000))
    }

    @Test
    fun a_rate_is_derived_from_bytes_and_time() {
        assertEquals("-", Format.rate(0, 1_000))
        assertEquals("-", Format.rate(1_000, 0))
        assertEquals("1000 B/s", Format.rate(1_000, 1_000))
    }

    @Test
    fun elapsed_time_is_described_in_words() {
        val now = 1_700_000_000_000L
        assertEquals("从未", Format.ago(0, now))
        assertEquals("刚刚", Format.ago(now - 5_000, now))
        assertEquals("5 分钟前", Format.ago(now - 5 * 60_000, now))
        assertEquals("3 小时前", Format.ago(now - 3 * 3_600_000, now))
        assertEquals("2 天前", Format.ago(now - 2 * 86_400_000, now))
        // Older than a week, a date is more useful than a count.
        assertTrue(Format.ago(now - 30L * 86_400_000, now).contains("-"))
    }

    @Test
    fun every_enum_has_a_word_for_a_person() {
        for (direction in SyncDirection.entries) {
            assertTrue(Format.direction(direction).isNotBlank())
            assertTrue(Format.directionShort(direction).isNotBlank())
        }
        for (mode in CompareMode.entries) assertTrue(Format.compare(mode).isNotBlank())
        for (policy in ConflictPolicy.entries) assertTrue(Format.conflict(policy).isNotBlank())
        for (policy in DeletePolicy.entries) assertTrue(Format.delete(policy).isNotBlank())
    }

    @Test
    fun the_policy_sentence_says_what_will_really_happen() {
        val twoWay = config(SyncDirection.TWO_WAY, DeletePolicy.RECYCLE)
        assertTrue(policySentence(twoWay).contains("删除会同步"), policySentence(twoWay))

        val update = config(SyncDirection.UPDATE_LEFT_TO_RIGHT, DeletePolicy.RECYCLE)
        assertTrue(policySentence(update).contains("不会删除"), policySentence(update))

        val mirrorKeep = config(SyncDirection.MIRROR_LEFT_TO_RIGHT, DeletePolicy.KEEP)
        assertTrue(policySentence(mirrorKeep).contains("不会"), policySentence(mirrorKeep))
        assertFalse(policySentence(mirrorKeep).contains("回收站"))

        val mirrorTrash = config(SyncDirection.MIRROR_LEFT_TO_RIGHT, DeletePolicy.RECYCLE)
        assertTrue(policySentence(mirrorTrash).contains("回收站"))
    }

    private fun config(direction: SyncDirection, delete: DeletePolicy) = SyncConfig(
        configId = "cfg",
        name = "t",
        direction = direction,
        delete = delete,
        pairs = listOf(
            FolderPair(
                pairId = "p",
                order = 0,
                left = Endpoint(EndpointId.LOCAL, "/a"),
                right = Endpoint(EndpointId.LOCAL, "/b"),
            ),
        ),
    )
}

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
