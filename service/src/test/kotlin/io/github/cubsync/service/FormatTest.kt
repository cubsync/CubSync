package io.github.cubsync.service

import io.github.cubsync.core.model.CompareMode
import io.github.cubsync.core.model.ConflictPolicy
import io.github.cubsync.core.model.DeletePolicy
import io.github.cubsync.core.model.Endpoint
import io.github.cubsync.core.model.FolderPair
import io.github.cubsync.core.model.SyncConfig
import io.github.cubsync.core.model.SyncDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The words a person reads.
 *
 * These live in the shared layer because both front ends say them, and a policy
 * described one way on a phone and another way on a desktop is a bug report waiting
 * to happen.
 */
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
        for (level in LogLevel.entries) assertTrue(Format.level(level).isNotBlank())
    }

    @Test
    fun the_policy_sentence_says_what_will_really_happen() {
        assertTrue(policySentence(config(SyncDirection.TWO_WAY, DeletePolicy.RECYCLE)).contains("删除会同步"))
        assertTrue(policySentence(config(SyncDirection.UPDATE_LEFT_TO_RIGHT, DeletePolicy.RECYCLE)).contains("不会删除"))

        val mirrorKeep = policySentence(config(SyncDirection.MIRROR_LEFT_TO_RIGHT, DeletePolicy.KEEP))
        assertTrue(mirrorKeep.contains("不会"), mirrorKeep)
        assertFalse(mirrorKeep.contains("回收站"), mirrorKeep)

        assertTrue(policySentence(config(SyncDirection.MIRROR_LEFT_TO_RIGHT, DeletePolicy.RECYCLE)).contains("回收站"))
    }

    @Test
    fun the_policy_sentence_names_the_direction_it_will_run() {
        val sentence = policySentence(config(SyncDirection.MIRROR_RIGHT_TO_LEFT, DeletePolicy.RECYCLE))
        assertTrue(sentence.startsWith(Format.direction(SyncDirection.MIRROR_RIGHT_TO_LEFT)), sentence)
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
