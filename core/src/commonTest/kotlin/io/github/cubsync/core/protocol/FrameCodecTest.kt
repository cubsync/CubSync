package io.github.cubsync.core.protocol

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FrameCodecTest {

    private fun frame(
        type: Int = FrameType.COMMAND,
        flags: Int = FrameFlags.NONE,
        streamId: Long = 0,
        reqNo: Int = 0,
        payload: ByteArray = ByteArray(0),
    ) = Frame(type, flags, streamId, reqNo, payload)

    @Test
    fun header_round_trips_every_type() {
        for (type in FrameType.ALL) {
            val original = frame(type = type, flags = FrameFlags.FINAL, streamId = 7L, reqNo = 42, payload = byteArrayOf(1, 2, 3))
            val decoded = FrameCodec.decode(FrameCodec.encode(original))
            assertEquals(type, decoded.type)
            assertEquals(FrameFlags.FINAL, decoded.flags)
            assertEquals(7L, decoded.streamId)
            assertEquals(42, decoded.reqNo)
            assertContentEquals(original.payload, decoded.payload)
        }
    }

    @Test
    fun payload_lengths_at_the_boundaries() {
        for (size in listOf(0, 1, 2, 255, 256, 65_535, Protocol.MAX_PAYLOAD)) {
            val payload = ByteArray(size) { (it and 0xFF).toByte() }
            val decoded = FrameCodec.decode(FrameCodec.encode(frame(payload = payload)))
            assertEquals(size, decoded.payloadSize)
            assertContentEquals(payload, decoded.payload)
        }
    }

    @Test
    fun payload_larger_than_the_limit_is_refused_before_it_is_built() {
        assertFailsWith<IllegalArgumentException> {
            frame(payload = ByteArray(Protocol.MAX_PAYLOAD + 1))
        }
    }

    @Test
    fun decode_rejects_a_bad_magic() {
        val encoded = FrameCodec.encode(frame())
        encoded[0] = 0x00
        val failure = assertFailsWith<ProtocolException> { FrameCodec.decode(encoded) }
        assertEquals(ErrorCode.BAD_MAGIC, failure.code)
    }

    @Test
    fun decode_rejects_a_newer_protocol_version() {
        val encoded = FrameCodec.encode(frame())
        encoded[2] = (Protocol.VERSION + 1).toByte()
        val failure = assertFailsWith<ProtocolException> { FrameCodec.decode(encoded) }
        assertEquals(ErrorCode.UNSUPPORTED_VERSION, failure.code)
    }

    @Test
    fun decode_rejects_an_unknown_frame_type() {
        val encoded = FrameCodec.encode(frame())
        encoded[3] = 99
        val failure = assertFailsWith<ProtocolException> { FrameCodec.decode(encoded) }
        assertEquals(ErrorCode.UNKNOWN_FRAME_TYPE, failure.code)
    }

    @Test
    fun decode_rejects_a_truncated_header() {
        val encoded = FrameCodec.encode(frame(payload = ByteArray(10)))
        val failure = assertFailsWith<ProtocolException> { FrameCodec.decode(encoded, 0, Protocol.HEADER_SIZE - 1) }
        assertEquals(ErrorCode.TRUNCATED_FRAME, failure.code)
    }

    @Test
    fun decode_rejects_a_truncated_payload() {
        val encoded = FrameCodec.encode(frame(payload = ByteArray(100)))
        val failure = assertFailsWith<ProtocolException> { FrameCodec.decode(encoded, 0, Protocol.HEADER_SIZE + 99) }
        assertEquals(ErrorCode.TRUNCATED_PAYLOAD, failure.code)
    }

    @Test
    fun decode_rejects_an_over_long_declared_payload() {
        val encoded = FrameCodec.encode(frame())
        // Hand-write a payload length one byte past the limit.
        val declared = Protocol.MAX_PAYLOAD + 1
        for (i in 0 until 4) encoded[23 - i] = (declared ushr (8 * i)).toByte()
        val failure = assertFailsWith<ProtocolException> { FrameCodec.decode(encoded) }
        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, failure.code)
    }

    @Test
    fun peek_returns_the_declared_payload_length() {
        for (size in listOf(0, 1, 4096, Protocol.MAX_PAYLOAD)) {
            val encoded = FrameCodec.encode(frame(payload = ByteArray(size)))
            assertEquals(size, FrameCodec.peekPayloadLength(encoded))
        }
    }

    @Test
    fun encode_into_offset_copies_leave_the_prefix_untouched() {
        val out = ByteArray(8 + Protocol.HEADER_SIZE + 3)
        out.fill(0x7F)
        val written = FrameCodec.encodeInto(frame(payload = byteArrayOf(9, 8, 7)), out, 8)
        assertEquals(Protocol.HEADER_SIZE + 3, written)
        for (i in 0 until 8) assertEquals(0x7F.toByte(), out[i])
        val decoded = FrameCodec.decode(out, 8, written)
        assertContentEquals(byteArrayOf(9, 8, 7), decoded.payload)
    }

    @Test
    fun random_round_trips_stay_stable() {
        val random = Random(20261016)
        repeat(10_000) {
            val payload = ByteArray(random.nextInt(0, 4096)) { random.nextInt(256).toByte() }
            val original = frame(
                type = FrameType.ALL.random(random),
                flags = random.nextInt(),
                streamId = random.nextLong(),
                reqNo = random.nextInt(),
                payload = payload,
            )
            val decoded = FrameCodec.decode(FrameCodec.encode(original))
            assertContentEquals(payload, decoded.payload)
            assertEquals(original.streamId, decoded.streamId)
            assertEquals(original.reqNo, decoded.reqNo)
            assertEquals(original.flags, decoded.flags)
            assertEquals(original.type, decoded.type)
        }
    }

    @Test
    fun random_garbage_never_escapes_as_something_other_than_a_protocol_error() {
        val random = Random(7)
        repeat(20_000) {
            val bytes = ByteArray(random.nextInt(0, 64)) { random.nextInt(256).toByte() }
            try {
                val decoded = FrameCodec.decode(bytes)
                // Anything that parses must at least be internally consistent.
                assertTrue(decoded.payloadSize <= Protocol.MAX_PAYLOAD)
                assertTrue(FrameType.ALL.contains(decoded.type))
            } catch (expected: ProtocolException) {
                assertTrue(expected.code > 0)
                assertTrue(expected.message!!.isNotEmpty())
            }
        }
    }

    @Test
    fun command_numbers_start_at_one_hundred_and_are_unique() {
        assertTrue(Command.ALL.min() >= 100)
        assertEquals(Command.ALL.size, Command.ALL.toSet().size)
        for (command in Command.ALL) assertTrue(Command.isKnown(command))
        assertEquals("UNKNOWN(999)", Command.name(999))
    }
}
