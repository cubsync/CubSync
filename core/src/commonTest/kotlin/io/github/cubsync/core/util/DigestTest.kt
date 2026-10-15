package io.github.cubsync.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class DigestTest {

    @Test
    fun md5_matches_the_fixed_vectors() {
        assertEquals(HashVectors.EMPTY_MD5, md5Of(ByteArray(0)))
        assertEquals(HashVectors.ABC_MD5, md5Of("abc".encodeToByteArray()))
    }

    @Test
    fun sha256_matches_the_fixed_vectors() {
        assertEquals(HashVectors.EMPTY_SHA256, newDigest(Algorithms.SHA256).hex())
        assertEquals(HashVectors.ABC_SHA256, newDigest(Algorithms.SHA256).apply { update("abc".encodeToByteArray()) }.hex())
    }

    @Test
    fun streaming_in_pieces_equals_hashing_in_one_go() {
        val payload = ByteArray(100_000) { (it * 31 and 0xFF).toByte() }
        val whole = newDigest(Algorithms.MD5).apply { update(payload) }.hex()

        val streamed = newDigest(Algorithms.MD5)
        var offset = 0
        var step = 1
        while (offset < payload.size) {
            val length = minOf(step, payload.size - offset)
            streamed.update(payload, offset, length)
            offset += length
            step = step * 2 + 1
        }
        assertEquals(whole, streamed.hex())
    }

    @Test
    fun offsets_are_respected() {
        val buffer = "xxxabcxxx".encodeToByteArray()
        assertEquals(HashVectors.ABC_MD5, newDigest(Algorithms.MD5).apply { update(buffer, 3, 3) }.hex())
    }

    @Test
    fun the_two_algorithms_disagree_on_the_same_input() {
        assertNotEquals(md5Of("abc".encodeToByteArray()), newDigest(Algorithms.SHA256).apply { update("abc".encodeToByteArray()) }.hex())
    }

    @Test
    fun unknown_algorithms_are_rejected() {
        assertFailsWith<IllegalArgumentException> { Algorithms.require("crc32") }
        assertFailsWith<IllegalArgumentException> { newDigest("crc32") }
        assertEquals(Algorithms.MD5, Algorithms.require("MD5"))
    }
}
