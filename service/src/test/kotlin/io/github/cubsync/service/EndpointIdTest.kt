package io.github.cubsync.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EndpointIdTest {

    @Test
    fun the_two_shapes_of_endpoint_parse_into_one_form() {
        assertEquals("local", EndpointId.parse("local").backendId)
        assertEquals("lan:10.0.0.5:43917", EndpointId.parse("10.0.0.5:43917").backendId)
        assertEquals("lan:10.0.0.5:43917", EndpointId.parse("lan:10.0.0.5:43917").backendId)
        assertEquals("lan:nas.local:2", EndpointId.parse("nas.local:2").backendId)
    }

    @Test
    fun a_host_without_a_port_is_refused_rather_than_guessed() {
        // Guessing the default port here would make a typo reach the wrong machine.
        assertFailsWith<IllegalArgumentException> { EndpointId.parse("10.0.0.5") }
        assertFailsWith<IllegalArgumentException> { EndpointId.parse("") }
        assertFailsWith<IllegalArgumentException> { EndpointId.parse("nas.local:0") }
        assertFailsWith<IllegalArgumentException> { EndpointId.parse("nas.local:70000") }
        assertNull(EndpointId.parseOrNull("10.0.0.5"))
    }

    @Test
    fun the_parts_come_back_out() {
        val peer = EndpointId.parse("192.168.1.20:43917")
        assertEquals("192.168.1.20", peer.host)
        assertEquals(43917, peer.port)
        assertFalse(peer.isLocal)

        val local = EndpointId.local()
        assertTrue(local.isLocal)
        assertNull(local.host)
        assertNull(local.port)
    }

    @Test
    fun a_label_is_readable_even_when_the_identifier_is_not() {
        assertEquals("192.168.1.20:43917", EndpointId.parse("lan:192.168.1.20:43917").label)
        assertEquals("this computer", EndpointId.local().label)
    }

    @Test
    fun whitespace_around_what_a_person_typed_is_harmless() {
        assertEquals("lan:10.0.0.5:43917", EndpointId.parse("  lan:10.0.0.5:43917  ").backendId)
    }
}
