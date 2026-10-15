package io.github.cubsync.core.transfer

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChunkMergerTest {

    private fun chunk(index: Int, text: String) = text.encodeToByteArray()

    @Test
    fun in_order_chunks_are_released_immediately() {
        val merger = ChunkMerger(4)
        val first = merger.accept(0, chunk(0, "aaaa"), 4, isFinal = false)
        assertEquals(1, first.size)
        assertEquals(0L, first[0].offset)
        assertFalse(first[0].isFinal)

        val second = merger.accept(1, chunk(1, "bbbb"), 4, isFinal = true)
        assertEquals(1, second.size)
        assertEquals(4L, second[0].offset)
        assertTrue(second[0].isFinal)
        assertTrue(merger.finished)
    }

    @Test
    fun out_of_order_chunks_are_held_until_the_gap_closes() {
        val merger = ChunkMerger(4)
        assertTrue(merger.accept(2, chunk(2, "cccc"), 4, true).isEmpty())
        assertEquals(1, merger.bufferedChunks)
        assertTrue(merger.accept(1, chunk(1, "bbbb"), 4, false).isEmpty())
        assertEquals(2, merger.bufferedChunks)

        val released = merger.accept(0, chunk(0, "aaaa"), 4, false)
        assertEquals(3, released.size)
        assertEquals(listOf("aaaa", "bbbb", "cccc"), released.map { it.data.decodeToString() })
        assertEquals(listOf(0L, 4L, 8L), released.map { it.offset })
        assertTrue(released.last().isFinal)
        assertTrue(merger.finished)
        assertEquals(0, merger.bufferedChunks)
    }

    @Test
    fun duplicates_do_not_rewind_the_stream() {
        val merger = ChunkMerger(4)
        assertEquals(1, merger.accept(0, chunk(0, "aaaa"), 4, false).size)
        assertTrue(merger.accept(0, chunk(0, "XXXX"), 4, false).isEmpty())
        assertEquals(4L, merger.nextOffset)
        assertEquals(1, merger.nextIndex)
        assertTrue(merger.accept(1, chunk(1, "bbbb"), 4, true).single().data.decodeToString() == "bbbb")
        assertEquals(8L, merger.nextOffset)
        assertTrue(merger.finished)
    }

    @Test
    fun a_stream_that_ends_without_its_final_chunk_is_not_finished() {
        val merger = ChunkMerger(4)
        merger.accept(2, chunk(2, "cccc"), 4, false)
        merger.accept(0, chunk(0, "aaaa"), 4, false)
        assertFalse(merger.finished)
        // With no final chunk flagged there is nothing to measure a gap against.
        assertTrue(merger.missingIndices().isEmpty())

        merger.accept(3, chunk(3, "dddd"), 4, isFinal = true)
        assertEquals(listOf(1), merger.missingIndices())
        assertEquals(8L, merger.bufferedBytes())
        assertFalse(merger.finished)
    }

    @Test
    fun two_streams_do_not_share_state() {
        val a = ChunkMerger(4)
        val b = ChunkMerger(4)
        a.accept(1, chunk(1, "B1"), 2, true)
        b.accept(0, chunk(0, "A0"), 2, false)
        assertEquals(0, a.nextIndex)
        assertEquals(1, b.nextIndex)
        assertEquals(1, a.bufferedChunks)
        assertFalse(a.finished)
        assertFalse(b.finished)
    }

    @Test
    fun table_of_interleavings_all_converge() {
        val payloads = listOf("aaaa", "bbbb", "cccc", "dddd")
        val orders = listOf(
            listOf(0, 1, 2, 3),
            listOf(3, 2, 1, 0),
            listOf(1, 0, 3, 2),
            listOf(2, 0, 1, 3),
            listOf(0, 2, 3, 1),
            listOf(3, 0, 2, 1),
        )
        for (order in orders) {
            val merger = ChunkMerger(4)
            val released = ArrayList<ByteArray>()
            for (index in order) {
                val out = merger.accept(index, payloads[index].encodeToByteArray(), 4, index == 3)
                out.forEach { released.add(it.data) }
            }
            assertTrue(merger.finished, "order $order did not finish")
            assertEquals(payloads, released.map { it.decodeToString() }, "order $order content")
            assertEquals(4, merger.releasedCount)
        }
    }

    @Test
    fun conflicting_final_indices_are_rejected() {
        val merger = ChunkMerger(4)
        merger.accept(2, chunk(2, "cccc"), 4, true)
        assertFailsWith<IllegalArgumentException> { merger.accept(3, chunk(3, "dddd"), 4, true) }
    }

    @Test
    fun negative_indices_and_impossible_lengths_are_rejected() {
        val merger = ChunkMerger(4)
        assertFailsWith<IllegalArgumentException> { merger.accept(-1, chunk(0, "a"), 1, false) }
        assertFailsWith<IllegalArgumentException> { merger.accept(0, chunk(0, "a"), 5, false) }
    }

    @Test
    fun zero_length_files_never_produce_chunks() {
        assertEquals(0, ChunkPlan.chunkCount(0))
        assertEquals(0, ChunkPlan.chunkCount(-1))
        assertEquals(1, ChunkPlan.chunkCount(1, 4))
        assertEquals(1, ChunkPlan.chunkCount(4, 4))
        assertEquals(2, ChunkPlan.chunkCount(5, 4))
        assertEquals(4, ChunkPlan.lengthOf(1, 14, 4))
        assertEquals(2, ChunkPlan.lengthOf(3, 14, 4))
        assertEquals(0, ChunkPlan.lengthOf(4, 14, 4))
        assertEquals(8L, ChunkPlan.offsetOf(2, 4))
    }

    @Test
    fun partial_chunks_keep_their_own_length() {
        val merger = ChunkMerger(4)
        val released = merger.accept(0, "abc".encodeToByteArray(), 3, isFinal = true)
        assertEquals(1, released.size)
        assertEquals(3, released[0].length)
        assertContentEquals("abc".encodeToByteArray(), released[0].data)
        assertTrue(merger.finished)
        assertEquals(3L, merger.nextOffset)
    }

    @Test
    fun a_merger_can_start_in_the_middle_of_a_stream() {
        // A resumed transfer picks up where the staging file ended, so the indices
        // and offsets have to continue from there rather than from zero.
        val merger = ChunkMerger.startingAt(offset = 8, chunkSize = 4)
        assertEquals(2, merger.nextIndex)
        assertEquals(8L, merger.nextOffset)

        assertTrue(merger.accept(3, "dddd".encodeToByteArray(), 4, isFinal = true).isEmpty())
        val released = merger.accept(2, "cccc".encodeToByteArray(), 4, false)
        assertEquals(listOf(8L, 12L), released.map { it.offset })
        assertEquals(listOf("cccc", "dddd"), released.map { it.data.decodeToString() })
        assertTrue(released.last().isFinal)
        assertTrue(merger.finished)
    }
}
