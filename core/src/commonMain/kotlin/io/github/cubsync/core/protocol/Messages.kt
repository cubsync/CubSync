package io.github.cubsync.core.protocol

/**
 * Every message that can appear in a frame payload.
 *
 * Payload layout for COMMAND and RESPONSE frames is `commandId:u16, msgId:u16,
 * body`; for ERROR frames it is `errorCode:u16, commandId:u16, message:str,
 * detail:str`; for DATA frames it is [DataChunkHeader] followed by raw bytes.
 * Keeping the message id inside the payload means a receiver can reject an
 * unknown command *by name* instead of losing byte alignment.
 */
interface Message : WireMessage {
    val msgId: Int
}

data class Entry(
    val relPath: String,
    val size: Long,
    val mtimeMs: Long,
    val isDirectory: Boolean = false,
    val symlink: Boolean = false,
    val mode: Int = 0,
) : Message {
    override val msgId: Int get() = MsgId.ENTRY

    override fun write(writer: BinWriter) {
        writer.string(relPath).i64(size).i64(mtimeMs).bool(isDirectory).bool(symlink).i32(mode)
    }

    companion object {
        fun read(reader: BinReader): Entry = Entry(
            relPath = reader.string(),
            size = reader.i64(),
            mtimeMs = reader.i64(),
            isDirectory = reader.bool(),
            symlink = reader.bool(),
            mode = reader.i32(),
        )
    }
}

// ---------------------------------------------------------------------------
// Handshake
// ---------------------------------------------------------------------------

data class HelloRequest(
    val protocolVersion: Int,
    val capabilities: Long,
    val deviceName: String,
    val platform: String,
    val appVersion: String,
) : Message {
    override val msgId: Int get() = MsgId.HELLO_REQUEST

    override fun write(writer: BinWriter) {
        writer.i32(protocolVersion).i64(capabilities).string(deviceName).string(platform).string(appVersion)
    }

    companion object {
        fun read(reader: BinReader): HelloRequest = HelloRequest(
            protocolVersion = reader.i32(),
            capabilities = reader.i64(),
            deviceName = reader.string(),
            platform = reader.string(),
            appVersion = reader.string(),
        )
    }
}

data class HelloReply(
    val protocolVersion: Int,
    val capabilities: Long,
    val deviceName: String,
    val platform: String,
    val appVersion: String,
    val accepted: Boolean,
    val note: String,
) : Message {
    override val msgId: Int get() = MsgId.HELLO_REPLY

    override fun write(writer: BinWriter) {
        writer.i32(protocolVersion).i64(capabilities).string(deviceName).string(platform).string(appVersion)
            .bool(accepted).string(note)
    }

    companion object {
        fun read(reader: BinReader): HelloReply = HelloReply(
            protocolVersion = reader.i32(),
            capabilities = reader.i64(),
            deviceName = reader.string(),
            platform = reader.string(),
            appVersion = reader.string(),
            accepted = reader.bool(),
            note = reader.string(),
        )
    }
}

/** Sent on the control channel every [Protocol.HEARTBEAT_INTERVAL_MS]. */
data class Keepalive(val monotonicMs: Long) : Message {
    override val msgId: Int get() = MsgId.KEEPALIVE

    override fun write(writer: BinWriter) {
        writer.i64(monotonicMs)
    }

    companion object {
        fun read(reader: BinReader): Keepalive = Keepalive(reader.i64())
    }
}

data class Cancel(val streamId: Long, val reason: String) : Message {
    override val msgId: Int get() = MsgId.CANCEL

    override fun write(writer: BinWriter) {
        writer.i64(streamId).string(reason)
    }

    companion object {
        fun read(reader: BinReader): Cancel = Cancel(reader.i64(), reader.string())
    }
}

// ---------------------------------------------------------------------------
// Metadata operations
// ---------------------------------------------------------------------------

/** [rootPath] is an opaque handle handed out by the server; the client never sends host paths. */
data class StatRequest(val rootPath: String, val relPath: String) : Message {
    override val msgId: Int get() = MsgId.STAT_REQUEST

    override fun write(writer: BinWriter) {
        writer.string(rootPath).string(relPath)
    }

    companion object {
        fun read(reader: BinReader): StatRequest = StatRequest(reader.string(), reader.string())
    }
}

data class StatReply(val entry: Entry?) : Message {
    override val msgId: Int get() = MsgId.STAT_REPLY

    override fun write(writer: BinWriter) {
        if (entry == null) {
            writer.bool(false)
        } else {
            writer.bool(true)
            entry.write(writer)
        }
    }

    companion object {
        fun read(reader: BinReader): StatReply = if (reader.bool()) StatReply(Entry.read(reader)) else StatReply(null)
    }
}

data class ListRequest(
    val rootPath: String,
    val relPath: String,
    val recursive: Boolean,
    val offset: Int,
    val limit: Int,
) : Message {
    override val msgId: Int get() = MsgId.LIST_REQUEST

    override fun write(writer: BinWriter) {
        writer.string(rootPath).string(relPath).bool(recursive).i32(offset).i32(limit)
    }

    companion object {
        fun read(reader: BinReader): ListRequest = ListRequest(
            rootPath = reader.string(),
            relPath = reader.string(),
            recursive = reader.bool(),
            offset = reader.i32(),
            limit = reader.i32(),
        )
    }
}

data class ListReply(val entries: List<Entry>, val nextOffset: Int, val done: Boolean) : Message {
    override val msgId: Int get() = MsgId.LIST_REPLY

    override fun write(writer: BinWriter) {
        writer.i32(entries.size)
        entries.forEach { it.write(writer) }
        writer.i32(nextOffset).bool(done)
    }

    companion object {
        fun read(reader: BinReader): ListReply {
            val count = reader.i32()
            if (count < 0) throw ProtocolException(ErrorCode.MALFORMED_PAYLOAD, "negative entry count $count")
            val entries = ArrayList<Entry>(minOf(count, 4096))
            repeat(count) { entries.add(Entry.read(reader)) }
            return ListReply(entries, reader.i32(), reader.bool())
        }
    }
}

data class HashRequest(val rootPath: String, val relPath: String, val algorithm: String) : Message {
    override val msgId: Int get() = MsgId.HASH_REQUEST

    override fun write(writer: BinWriter) {
        writer.string(rootPath).string(relPath).string(algorithm)
    }

    companion object {
        fun read(reader: BinReader): HashRequest = HashRequest(reader.string(), reader.string(), reader.string())
    }
}

data class HashReply(val hex: String, val size: Long) : Message {
    override val msgId: Int get() = MsgId.HASH_REPLY

    override fun write(writer: BinWriter) {
        writer.string(hex).i64(size)
    }

    companion object {
        fun read(reader: BinReader): HashReply = HashReply(reader.string(), reader.i64())
    }
}

data class MkdirRequest(val rootPath: String, val relPath: String) : Message {
    override val msgId: Int get() = MsgId.MKDIR_REQUEST

    override fun write(writer: BinWriter) {
        writer.string(rootPath).string(relPath)
    }

    companion object {
        fun read(reader: BinReader): MkdirRequest = MkdirRequest(reader.string(), reader.string())
    }
}

data class DeleteRequest(val rootPath: String, val relPath: String, val toTrash: Boolean) : Message {
    override val msgId: Int get() = MsgId.DELETE_REQUEST

    override fun write(writer: BinWriter) {
        writer.string(rootPath).string(relPath).bool(toTrash)
    }

    companion object {
        fun read(reader: BinReader): DeleteRequest = DeleteRequest(reader.string(), reader.string(), reader.bool())
    }
}

data class RenameRequest(val rootPath: String, val fromRel: String, val toRel: String) : Message {
    override val msgId: Int get() = MsgId.RENAME_REQUEST

    override fun write(writer: BinWriter) {
        writer.string(rootPath).string(fromRel).string(toRel)
    }

    companion object {
        fun read(reader: BinReader): RenameRequest = RenameRequest(reader.string(), reader.string(), reader.string())
    }
}

data class SetMtimeRequest(val rootPath: String, val relPath: String, val mtimeMs: Long) : Message {
    override val msgId: Int get() = MsgId.SET_MTIME_REQUEST

    override fun write(writer: BinWriter) {
        writer.string(rootPath).string(relPath).i64(mtimeMs)
    }

    companion object {
        fun read(reader: BinReader): SetMtimeRequest = SetMtimeRequest(reader.string(), reader.string(), reader.i64())
    }
}

/** Generic success reply for operations that carry no data of their own. */
data class Ack(val applied: Long) : Message {
    override val msgId: Int get() = MsgId.ACK

    override fun write(writer: BinWriter) {
        writer.i64(applied)
    }

    companion object {
        fun read(reader: BinReader): Ack = Ack(reader.i64())
    }
}

data class FreeSpaceRequest(val rootPath: String) : Message {
    override val msgId: Int get() = MsgId.FREE_SPACE_REQUEST

    override fun write(writer: BinWriter) {
        writer.string(rootPath)
    }

    companion object {
        fun read(reader: BinReader): FreeSpaceRequest = FreeSpaceRequest(reader.string())
    }
}

data class FreeSpaceReply(val freeBytes: Long) : Message {
    override val msgId: Int get() = MsgId.FREE_SPACE_REPLY

    override fun write(writer: BinWriter) {
        writer.i64(freeBytes)
    }

    companion object {
        fun read(reader: BinReader): FreeSpaceReply = FreeSpaceReply(reader.i64())
    }
}

// ---------------------------------------------------------------------------
// Streaming
// ---------------------------------------------------------------------------

data class ReadOpenRequest(
    val rootPath: String,
    val relPath: String,
    val offset: Long,
    val length: Long,
    val chunkSize: Int,
) : Message {
    override val msgId: Int get() = MsgId.READ_OPEN_REQUEST

    override fun write(writer: BinWriter) {
        writer.string(rootPath).string(relPath).i64(offset).i64(length).i32(chunkSize)
    }

    companion object {
        fun read(reader: BinReader): ReadOpenRequest = ReadOpenRequest(
            rootPath = reader.string(),
            relPath = reader.string(),
            offset = reader.i64(),
            length = reader.i64(),
            chunkSize = reader.i32(),
        )
    }
}

/**
 * Reply to READ_OPEN and WRITE_OPEN.
 *
 * [offset] is the position the transfer actually starts from — for writes this is
 * the resume point derived from the partially written target file.
 */
data class StreamOpened(val streamId: Long, val offset: Long, val size: Long, val chunkSize: Int) : Message {
    override val msgId: Int get() = MsgId.STREAM_OPENED

    override fun write(writer: BinWriter) {
        writer.i64(streamId).i64(offset).i64(size).i32(chunkSize)
    }

    companion object {
        fun read(reader: BinReader): StreamOpened =
            StreamOpened(reader.i64(), reader.i64(), reader.i64(), reader.i32())
    }
}

/** Asks the sender for the next chunk of an open read stream. */
data class ReadChunkRequest(val streamId: Long, val offset: Long, val length: Int) : Message {
    override val msgId: Int get() = MsgId.READ_CHUNK_REQUEST

    override fun write(writer: BinWriter) {
        writer.i64(streamId).i64(offset).i32(length)
    }

    companion object {
        fun read(reader: BinReader): ReadChunkRequest =
            ReadChunkRequest(reader.i64(), reader.i64(), reader.i32())
    }
}

data class WriteOpenRequest(
    val rootPath: String,
    val relPath: String,
    val size: Long,
    val mtimeMs: Long,
    val hash: String,
    val chunkSize: Int,
    val resume: Boolean,
) : Message {
    override val msgId: Int get() = MsgId.WRITE_OPEN_REQUEST

    override fun write(writer: BinWriter) {
        writer.string(rootPath).string(relPath).i64(size).i64(mtimeMs).string(hash).i32(chunkSize).bool(resume)
    }

    companion object {
        fun read(reader: BinReader): WriteOpenRequest = WriteOpenRequest(
            rootPath = reader.string(),
            relPath = reader.string(),
            size = reader.i64(),
            mtimeMs = reader.i64(),
            hash = reader.string(),
            chunkSize = reader.i32(),
            resume = reader.bool(),
        )
    }
}

data class WriteCloseRequest(val streamId: Long, val hash: String) : Message {
    override val msgId: Int get() = MsgId.WRITE_CLOSE_REQUEST

    override fun write(writer: BinWriter) {
        writer.i64(streamId).string(hash)
    }

    companion object {
        fun read(reader: BinReader): WriteCloseRequest = WriteCloseRequest(reader.i64(), reader.string())
    }
}

data class CloseStreamRequest(val streamId: Long) : Message {
    override val msgId: Int get() = MsgId.CLOSE_STREAM_REQUEST

    override fun write(writer: BinWriter) {
        writer.i64(streamId)
    }

    companion object {
        fun read(reader: BinReader): CloseStreamRequest = CloseStreamRequest(reader.i64())
    }
}

/**
 * Header of a DATA frame. [chunkIndex] is the position of this chunk inside the
 * stream, which is what lets a receiver reorder chunks that arrive out of order.
 */
data class DataChunkHeader(
    val streamId: Long,
    val chunkIndex: Int,
    val fileOffset: Long,
    val length: Int,
) : WireMessage {
    override fun write(writer: BinWriter) {
        writer.i64(streamId).i32(chunkIndex).i64(fileOffset).i32(length)
    }

    companion object {
        const val SIZE: Int = 24

        fun read(reader: BinReader): DataChunkHeader =
            DataChunkHeader(reader.i64(), reader.i32(), reader.i64(), reader.i32())
    }
}

data class ErrorMessage(
    val code: Int,
    val command: Int,
    val message: String,
    val detail: String = "",
) : Message {
    override val msgId: Int get() = MsgId.ERROR

    override fun write(writer: BinWriter) {
        writer.i32(code).i32(command).string(message).string(detail)
    }

    companion object {
        fun read(reader: BinReader): ErrorMessage =
            ErrorMessage(reader.i32(), reader.i32(), reader.string(), reader.string())
    }
}

object MsgId {
    const val ENTRY: Int = 1
    const val HELLO_REQUEST: Int = 1_000
    const val HELLO_REPLY: Int = 1_001
    const val KEEPALIVE: Int = 1_002
    const val CANCEL: Int = 1_003
    const val STAT_REQUEST: Int = 1_010
    const val STAT_REPLY: Int = 1_011
    const val LIST_REQUEST: Int = 1_020
    const val LIST_REPLY: Int = 1_021
    const val HASH_REQUEST: Int = 1_030
    const val HASH_REPLY: Int = 1_031
    const val MKDIR_REQUEST: Int = 1_040
    const val DELETE_REQUEST: Int = 1_041
    const val RENAME_REQUEST: Int = 1_042
    const val SET_MTIME_REQUEST: Int = 1_043
    const val ACK: Int = 1_044
    const val FREE_SPACE_REQUEST: Int = 1_050
    const val FREE_SPACE_REPLY: Int = 1_051
    const val READ_OPEN_REQUEST: Int = 1_060
    const val READ_CHUNK_REQUEST: Int = 1_062
    const val WRITE_OPEN_REQUEST: Int = 1_070
    const val WRITE_CLOSE_REQUEST: Int = 1_073
    const val CLOSE_STREAM_REQUEST: Int = 1_074
    const val STREAM_OPENED: Int = 1_061
    const val ERROR: Int = 1_080
}

/**
 * Message framing inside a payload: `msgId:u16` then the body.
 *
 * [encodeCommand] additionally prefixes the command number, which is what makes
 * an unrecognised command detectable as such.
 */
object Messages {

    fun encodeMessage(message: Message): ByteArray {
        val writer = BinWriter(64)
        writer.u16(message.msgId)
        message.write(writer)
        return writer.toByteArray()
    }

    fun decodeMessage(payload: ByteArray, offset: Int = 0, length: Int = payload.size - offset): Message {
        val reader = BinReader(payload, offset, offset + length)
        val msgId = reader.u16()
        return decodeBody(msgId, reader)
    }

    /** COMMAND / RESPONSE payload: `command:u16, msgId:u16, body`. */
    fun encodeCommand(command: Int, message: Message): ByteArray {
        val writer = BinWriter(64)
        writer.u16(command).u16(message.msgId)
        message.write(writer)
        return writer.toByteArray()
    }

    fun decodeCommand(payload: ByteArray): CommandEnvelope {
        val reader = BinReader(payload)
        val command = reader.u16()
        val msgId = reader.u16()
        if (!Command.isKnown(command)) {
            throw ProtocolException(
                ErrorCode.UNKNOWN_COMMAND,
                "unsupported command $command from peer (this build speaks protocol ${Protocol.VERSION})",
            )
        }
        return CommandEnvelope(command, decodeBody(msgId, reader))
    }

    /** ERROR payload: `code:u16, command:u16, message:str, detail:str`. */
    fun encodeError(error: ErrorMessage): ByteArray {
        val writer = BinWriter(64)
        writer.u16(error.code).u16(error.command).string(error.message).string(error.detail)
        return writer.toByteArray()
    }

    fun decodeError(payload: ByteArray): ErrorMessage {
        val reader = BinReader(payload)
        return ErrorMessage(
            code = reader.u16(),
            command = reader.u16(),
            message = reader.string(),
            detail = reader.string(),
        )
    }

    /** DATA payload: [DataChunkHeader] then the raw bytes. */
    fun encodeChunk(header: DataChunkHeader, data: ByteArray, offset: Int, length: Int): ByteArray {
        val writer = BinWriter(DataChunkHeader.SIZE + length)
        header.write(writer)
        writer.bytes(data, offset, length)
        return writer.toByteArray()
    }

    fun decodeChunkHeader(payload: ByteArray): DataChunkHeader = DataChunkHeader.read(BinReader(payload))

    fun decodeChunkBody(payload: ByteArray, into: ByteArray, intoOffset: Int): Int {
        val reader = BinReader(payload, DataChunkHeader.SIZE, payload.size)
        val length = reader.remaining
        reader.bytesInto(into, intoOffset, length)
        return length
    }

    private fun decodeBody(msgId: Int, reader: BinReader): Message = when (msgId) {
        MsgId.HELLO_REQUEST -> HelloRequest.read(reader)
        MsgId.HELLO_REPLY -> HelloReply.read(reader)
        MsgId.KEEPALIVE -> Keepalive.read(reader)
        MsgId.CANCEL -> Cancel.read(reader)
        MsgId.STAT_REQUEST -> StatRequest.read(reader)
        MsgId.STAT_REPLY -> StatReply.read(reader)
        MsgId.LIST_REQUEST -> ListRequest.read(reader)
        MsgId.LIST_REPLY -> ListReply.read(reader)
        MsgId.HASH_REQUEST -> HashRequest.read(reader)
        MsgId.HASH_REPLY -> HashReply.read(reader)
        MsgId.MKDIR_REQUEST -> MkdirRequest.read(reader)
        MsgId.DELETE_REQUEST -> DeleteRequest.read(reader)
        MsgId.RENAME_REQUEST -> RenameRequest.read(reader)
        MsgId.SET_MTIME_REQUEST -> SetMtimeRequest.read(reader)
        MsgId.ACK -> Ack.read(reader)
        MsgId.FREE_SPACE_REQUEST -> FreeSpaceRequest.read(reader)
        MsgId.FREE_SPACE_REPLY -> FreeSpaceReply.read(reader)
        MsgId.READ_OPEN_REQUEST -> ReadOpenRequest.read(reader)
        MsgId.READ_CHUNK_REQUEST -> ReadChunkRequest.read(reader)
        MsgId.WRITE_OPEN_REQUEST -> WriteOpenRequest.read(reader)
        MsgId.WRITE_CLOSE_REQUEST -> WriteCloseRequest.read(reader)
        MsgId.CLOSE_STREAM_REQUEST -> CloseStreamRequest.read(reader)
        MsgId.STREAM_OPENED -> StreamOpened.read(reader)
        MsgId.ERROR -> ErrorMessage.read(reader)
        else -> throw ProtocolException(
            ErrorCode.UNKNOWN_COMMAND,
            "unsupported message id $msgId (protocol ${Protocol.VERSION})",
        )
    }
}

data class CommandEnvelope(val command: Int, val message: Message)
