package io.github.cubsync.core.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MessagesTest {

    private val awkwardStrings = listOf(
        "",
        "plain",
        "ümlaut-日本語-🙂",
        "  spaces \t and \n newlines  ",
        "a".repeat(3_000),
    )

    @Test
    fun hello_round_trips() {
        for (text in awkwardStrings) {
            val request = HelloRequest(Protocol.VERSION, Capabilities.ALL, text, text, text)
            val decoded = Messages.decodeMessage(Messages.encodeMessage(request)) as HelloRequest
            assertEquals(request, decoded)
        }
        val reply = HelloReply(Protocol.VERSION, 5, "phone", "android", "0.1.0", true, "ok")
        assertEquals(reply, Messages.decodeMessage(Messages.encodeMessage(reply)) as HelloReply)
    }

    @Test
    fun metadata_messages_round_trip() {
        val root = "/data"
        val path = "photos/2026/10/日本 trip.jpg"

        assertEquals(
            StatRequest(root, path),
            Messages.decodeMessage(Messages.encodeMessage(StatRequest(root, path))) as StatRequest,
        )
        assertEquals(StatReply(null), Messages.decodeMessage(Messages.encodeMessage(StatReply(null))) as StatReply)

        val entry = Entry(path, 123_456_789L, 1_700_000_000_000L, false, false, 0b110_100_100)
        assertEquals(StatReply(entry), Messages.decodeMessage(Messages.encodeMessage(StatReply(entry))) as StatReply)

        val list = ListReply(listOf(entry, entry.copy(relPath = "b", isDirectory = true)), 42, false)
        assertEquals(list, Messages.decodeMessage(Messages.encodeMessage(list)) as ListReply)

        val empty = ListReply(emptyList(), 0, true)
        assertEquals(empty, Messages.decodeMessage(Messages.encodeMessage(empty)) as ListReply)

        assertEquals(
            ListRequest(root, path, recursive = true, offset = 100, limit = 2_000),
            Messages.decodeMessage(Messages.encodeMessage(ListRequest(root, path, true, 100, 2_000))) as ListRequest,
        )
        assertEquals(
            HashReply("d41d8cd98f00b204e9800998ecf8427e", 0),
            Messages.decodeMessage(
                Messages.encodeMessage(HashReply("d41d8cd98f00b204e9800998ecf8427e", 0)),
            ) as HashReply,
        )
        assertEquals(
            Ack(7),
            Messages.decodeMessage(Messages.encodeMessage(Ack(7))) as Ack,
        )
        assertEquals(
            FreeSpaceReply(1L shl 40),
            Messages.decodeMessage(Messages.encodeMessage(FreeSpaceReply(1L shl 40))) as FreeSpaceReply,
        )
    }

    @Test
    fun mutation_messages_round_trip() {
        assertEquals(
            MkdirRequest("/r", "a/b"),
            Messages.decodeMessage(Messages.encodeMessage(MkdirRequest("/r", "a/b"))) as MkdirRequest,
        )
        assertEquals(
            DeleteRequest("/r", "a/b", toTrash = true),
            Messages.decodeMessage(Messages.encodeMessage(DeleteRequest("/r", "a/b", true))) as DeleteRequest,
        )
        assertEquals(
            RenameRequest("/r", "a", "b"),
            Messages.decodeMessage(Messages.encodeMessage(RenameRequest("/r", "a", "b"))) as RenameRequest,
        )
        assertEquals(
            SetMtimeRequest("/r", "a", -1),
            Messages.decodeMessage(Messages.encodeMessage(SetMtimeRequest("/r", "a", -1))) as SetMtimeRequest,
        )
    }

    @Test
    fun streaming_messages_round_trip() {
        assertEquals(
            ReadOpenRequest("/r", "a", 1L shl 33, -1, 262_144),
            Messages.decodeMessage(
                Messages.encodeMessage(ReadOpenRequest("/r", "a", 1L shl 33, -1, 262_144)),
            ) as ReadOpenRequest,
        )
        assertEquals(
            ReadChunkRequest(9, 524_288, 262_144),
            Messages.decodeMessage(Messages.encodeMessage(ReadChunkRequest(9, 524_288, 262_144))) as ReadChunkRequest,
        )
        assertEquals(
            WriteOpenRequest("/r", "a", 12_345, 999, "abc", 262_144, resume = false),
            Messages.decodeMessage(
                Messages.encodeMessage(WriteOpenRequest("/r", "a", 12_345, 999, "abc", 262_144, false)),
            ) as WriteOpenRequest,
        )
        assertEquals(
            WriteCloseRequest(3, "deadbeef"),
            Messages.decodeMessage(Messages.encodeMessage(WriteCloseRequest(3, "deadbeef"))) as WriteCloseRequest,
        )
        assertEquals(
            CloseStreamRequest(3),
            Messages.decodeMessage(Messages.encodeMessage(CloseStreamRequest(3))) as CloseStreamRequest,
        )
        assertEquals(
            StreamOpened(5, 1_048_576, 10_000_000, 262_144),
            Messages.decodeMessage(
                Messages.encodeMessage(StreamOpened(5, 1_048_576, 10_000_000, 262_144)),
            ) as StreamOpened,
        )
        assertEquals(
            Keepalive(4_242),
            Messages.decodeMessage(Messages.encodeMessage(Keepalive(4_242))) as Keepalive,
        )
        assertEquals(
            Cancel(5, "user asked"),
            Messages.decodeMessage(Messages.encodeMessage(Cancel(5, "user asked"))) as Cancel,
        )
    }

    @Test
    fun error_message_round_trips() {
        val error = ErrorMessage(ErrorCode.UNKNOWN_COMMAND, 977, "no such command", "detail")
        assertEquals(error, Messages.decodeMessage(Messages.encodeMessage(error)) as ErrorMessage)
    }

    @Test
    fun chunk_frames_carry_header_and_bytes() {
        val data = ByteArray(1_000) { (it % 251).toByte() }
        val header = DataChunkHeader(streamId = 11, chunkIndex = 4, fileOffset = 1_048_576, length = 1_000)
        val encoded = Messages.encodeChunk(header, data, 0, data.size)
        assertEquals(header, Messages.decodeChunkHeader(encoded))
        val out = ByteArray(1_000)
        val copied = Messages.decodeChunkBody(encoded, out, 0)
        assertEquals(1_000, copied)
        assertContentEquals(data, out)
    }

    @Test
    fun command_envelope_preserves_the_command_number() {
        val payload = Messages.encodeCommand(Command.WRITE_OPEN, WriteOpenRequest("/r", "a", 1, 2, "", 8, true))
        val envelope = Messages.decodeCommand(payload)
        assertEquals(Command.WRITE_OPEN, envelope.command)
        assertTrue(envelope.message is WriteOpenRequest)
    }

    @Test
    fun an_unknown_command_is_reported_as_such() {
        val payload = Messages.encodeCommand(Command.KEEPALIVE, Keepalive(1))
        // Rewrite the command number to one this build does not implement.
        payload[0] = 0x03
        payload[1] = 0xE7.toByte() // 999
        val failure = assertFailsWith<ProtocolException> { Messages.decodeCommand(payload) }
        assertEquals(ErrorCode.UNKNOWN_COMMAND, failure.code)
        assertTrue(failure.message!!.contains("999"))
    }

    @Test
    fun an_unknown_message_id_is_reported_as_such() {
        val bytes = byteArrayOf(0x7F.toByte(), 0x00, 0x01)
        val failure = assertFailsWith<ProtocolException> { Messages.decodeMessage(bytes) }
        assertEquals(ErrorCode.UNKNOWN_COMMAND, failure.code)
    }

    @Test
    fun a_truncated_payload_is_reported_as_truncated() {
        val encoded = Messages.encodeMessage(ListReply(listOf(Entry("a", 1, 2)), 0, true))
        val failure = assertFailsWith<ProtocolException> { Messages.decodeMessage(encoded, 0, encoded.size - 4) }
        assertEquals(ErrorCode.TRUNCATED_PAYLOAD, failure.code)
    }

    @Test
    fun a_negative_entry_count_is_refused() {
        val writer = BinWriter()
        writer.u16(MsgId.LIST_REPLY).i32(-1)
        val failure = assertFailsWith<ProtocolException> { Messages.decodeMessage(writer.toByteArray()) }
        assertEquals(ErrorCode.MALFORMED_PAYLOAD, failure.code)
    }

    @Test
    fun negative_lengths_are_refused_by_the_reader() {
        val writer = BinWriter()
        writer.i32(-5)
        assertFailsWith<ProtocolException> { BinReader(writer.toByteArray()).string() }
        assertFailsWith<ProtocolException> { BinReader(writer.toByteArray()).bytes(16) }
    }

    @Test
    fun every_message_id_is_unique() {
        val ids = listOf(
            MsgId.HELLO_REQUEST, MsgId.HELLO_REPLY, MsgId.KEEPALIVE, MsgId.CANCEL,
            MsgId.STAT_REQUEST, MsgId.STAT_REPLY, MsgId.LIST_REQUEST, MsgId.LIST_REPLY,
            MsgId.HASH_REQUEST, MsgId.HASH_REPLY, MsgId.MKDIR_REQUEST, MsgId.DELETE_REQUEST,
            MsgId.RENAME_REQUEST, MsgId.SET_MTIME_REQUEST, MsgId.ACK, MsgId.FREE_SPACE_REQUEST,
            MsgId.FREE_SPACE_REPLY, MsgId.READ_OPEN_REQUEST, MsgId.STREAM_OPENED,
            MsgId.READ_CHUNK_REQUEST, MsgId.WRITE_OPEN_REQUEST, MsgId.WRITE_CLOSE_REQUEST,
            MsgId.CLOSE_STREAM_REQUEST, MsgId.ERROR,
        )
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it > 0 })
    }
}
