package io.github.cubsync.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HandshakeTest {

    private val ours = Capabilities.LIST or Capabilities.STAT or Capabilities.HASH or Capabilities.MULTI_CHANNEL or
        Capabilities.RESUME or Capabilities.RANDOM_WRITE

    private data class Case(
        val peerVersion: Int,
        val peerCaps: Long,
        val accepted: Boolean,
        val expectedCaps: Long,
        val noteFragment: String,
    )

    @Test
    fun version_matrix_is_table_driven() {
        val peerFull = Capabilities.ALL
        val peerOlder = Capabilities.LIST or Capabilities.STAT

        val cases = buildList {
            add(Case(Protocol.VERSION, peerFull, true, ours, "capability bit"))
            add(Case(Protocol.VERSION, Capabilities.NONE, true, Capabilities.NONE, "0 capability bit"))
            add(Case(Protocol.VERSION, peerOlder, true, peerOlder, "capability bit"))
            // There is no valid "older" peer while the protocol is still at version 1;
            // this row starts exercising the downgrade path as soon as it moves on.
            if (Protocol.VERSION > 1) add(Case(Protocol.VERSION - 1, peerFull, true, ours, "older"))
            add(Case(Protocol.VERSION + 1, peerFull, false, Capabilities.NONE, "peer speaks protocol"))
            add(Case(Protocol.VERSION + 9, peerFull, false, Capabilities.NONE, "peer speaks protocol"))
            add(Case(0, peerFull, false, Capabilities.NONE, "invalid"))
            add(Case(-3, peerFull, false, Capabilities.NONE, "invalid"))
        }

        for (case in cases) {
            val result = Handshake.evaluate(Protocol.VERSION, ours, case.peerVersion, case.peerCaps)
            assertEquals(case.accepted, result.accepted, "version ${case.peerVersion} acceptance")
            assertEquals(case.expectedCaps, result.negotiatedCapabilities, "version ${case.peerVersion} caps")
            assertTrue(
                result.note.contains(case.noteFragment),
                "version ${case.peerVersion} note was '${result.note}'",
            )
        }
    }

    @Test
    fun negotiation_never_offers_a_capability_the_peer_lacks() {
        val result = Handshake.evaluate(Protocol.VERSION, ours, Protocol.VERSION, Capabilities.LIST or Capabilities.HASH)
        assertEquals(Capabilities.LIST or Capabilities.HASH, result.negotiatedCapabilities)
        assertFalse(Capabilities.has(result.negotiatedCapabilities, Capabilities.MULTI_CHANNEL))
        assertFalse(Capabilities.has(result.negotiatedCapabilities, Capabilities.RESUME))
    }

    @Test
    fun describe_lists_only_the_bits_that_are_set() {
        val described = Capabilities.describe(Capabilities.LIST or Capabilities.RESUME)
        assertEquals(listOf("LIST", "RESUME"), described)
        assertEquals(emptyList(), Capabilities.describe(Capabilities.NONE))
    }

    @Test
    fun bits_we_do_not_implement_are_still_reported() {
        // A future peer offering bit 40 must not be mistaken for a known capability.
        val result = Handshake.evaluate(Protocol.VERSION, ours, Protocol.VERSION, ours or (1L shl 40))
        assertEquals(ours, result.negotiatedCapabilities)
        assertEquals(1L shl 40, Capabilities.unknownBits(ours, ours or (1L shl 40)))
    }
}
