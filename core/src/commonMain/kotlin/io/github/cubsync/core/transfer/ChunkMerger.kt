package io.github.cubsync.core.transfer

/**
 * Reorders the chunks of one stream.
 *
 * The data channel sends chunks with an explicit index, and a receiver may hold
 * several chunks that arrived ahead of a gap. This class keeps those aside and
 * releases them in order once the gap closes. It is deliberately free of IO so
 * the reordering rule can be tested exhaustively: out-of-order arrival, chunks
 * from two streams interleaved, duplicates, and a stream whose final chunk never
 * shows up.
 *
 * Not thread-safe by design; one instance belongs to one stream on one thread.
 */
class ChunkMerger(
    private val expectedChunkSize: Int,
    initialIndex: Int = 0,
    initialOffset: Long = 0,
) {

    private val held = LinkedHashMap<Int, ByteArray>()

    /** Index of the next chunk that may be released. */
    var nextIndex: Int = initialIndex
        private set

    /** Stream offset that [nextIndex] corresponds to. */
    var nextOffset: Long = initialOffset
        private set

    /** Index flagged as the last chunk of the stream, or -1 while unknown. */
    var finalIndex: Int = -1
        private set

    /** True once the final chunk has been released. */
    var finished: Boolean = false
        private set

    var acceptedCount: Int = 0
        private set

    var releasedCount: Int = 0
        private set

    val bufferedChunks: Int get() = held.size

    /**
     * Adds one chunk and returns the chunks that are now contiguous, in stream
     * order. A chunk that is a duplicate of already released data is dropped
     * silently so a retransmission cannot rewind the stream position.
     */
    fun accept(chunkIndex: Int, data: ByteArray, length: Int, isFinal: Boolean): List<Released> {
        if (chunkIndex < 0) throw IllegalArgumentException("negative chunk index $chunkIndex")
        if (length < 0 || length > data.size) {
            throw IllegalArgumentException("chunk length $length does not fit a buffer of ${data.size}")
        }
        if (isFinal) {
            if (finalIndex >= 0 && finalIndex != chunkIndex) {
                throw IllegalArgumentException("conflicting final chunk index: $finalIndex then $chunkIndex")
            }
            finalIndex = chunkIndex
        }
        if (chunkIndex < nextIndex) return emptyList()

        acceptedCount++
        if (chunkIndex > nextIndex) {
            held[chunkIndex] = data.copyOf(length)
            return emptyList()
        }

        val out = ArrayList<Released>(4)
        releaseFrom(chunkIndex, data, length, out)
        while (true) {
            val buffered = held.remove(nextIndex) ?: break
            releaseFrom(nextIndex, buffered, buffered.size, out)
        }
        if (finalIndex >= 0 && nextIndex > finalIndex) finished = true
        return out
    }

    private fun releaseFrom(index: Int, data: ByteArray, length: Int, out: MutableList<Released>) {
        out.add(Released(nextOffset, data, length, index == finalIndex))
        nextOffset += length
        nextIndex = index + 1
        releasedCount++
    }

    /** Indices between the cursor and the final chunk that are still missing. */
    fun missingIndices(): List<Int> {
        if (finalIndex < 0) return emptyList()
        val missing = ArrayList<Int>()
        for (i in nextIndex..finalIndex) if (!held.containsKey(i)) missing.add(i)
        return missing
    }

    /** Bytes currently parked in the reorder buffer. */
    fun bufferedBytes(): Long = held.values.sumOf { it.size.toLong() }

    /** Number of chunks the stream will have once it completes, or -1 while unknown. */
    fun totalChunks(): Int = if (finalIndex < 0) -1 else finalIndex + 1

    fun reset() {
        held.clear()
        nextIndex = 0
        nextOffset = 0
        finalIndex = -1
        finished = false
        acceptedCount = 0
        releasedCount = 0
    }

    /** Indices are derived from a uniform chunk size, so a resume starts mid-sequence. */
    companion object {
        fun startingAt(offset: Long, chunkSize: Int): ChunkMerger =
            ChunkMerger(chunkSize, initialIndex = (offset / chunkSize).toInt(), initialOffset = offset)
    }

    data class Released(val offset: Long, val data: ByteArray, val length: Int, val isFinal: Boolean)
}

/** Chunk sizing helper; keeps every chunk except the last at a uniform size. */
object ChunkPlan {
    const val DEFAULT_CHUNK_SIZE: Int = 256 * 1024

    fun chunkCount(size: Long, chunkSize: Int = DEFAULT_CHUNK_SIZE): Int {
        if (size <= 0) return 0
        return ((size + chunkSize - 1) / chunkSize).toInt()
    }

    fun offsetOf(index: Int, chunkSize: Int = DEFAULT_CHUNK_SIZE): Long = index.toLong() * chunkSize

    fun lengthOf(index: Int, size: Long, chunkSize: Int = DEFAULT_CHUNK_SIZE): Int {
        val offset = offsetOf(index, chunkSize)
        if (offset >= size) return 0
        return minOf(chunkSize.toLong(), size - offset).toInt()
    }
}
