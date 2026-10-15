package io.github.cubsync.core.protocol

/**
 * CubSync first-party LAN protocol.
 *
 * Two independent TCP connections carry this protocol: a control channel for
 * commands and a data channel for file payloads. Mixing them is what makes bulk
 * transfers stall command traffic, so the split is a hard rule rather than a
 * tuning knob.
 *
 * Header layout, all fields big-endian:
 *
 * ```
 * offset size field
 *      0    2 MAGIC          'T','S'
 *      2    1 VERSION        protocol version
 *      3    1 FRAME_TYPE
 *      4    4 FLAGS
 *      8    8 STREAM_ID      non-zero for DATA frames, 0 otherwise
 *     16    4 REQ_NO         correlates a response with its request
 *     20    4 PAYLOAD_LEN    payload bytes following the header, <= MAX_PAYLOAD
 * ```
 */
object Protocol {
    const val MAGIC_0: Int = 0x54 // 'T'
    const val MAGIC_1: Int = 0x53 // 'S'

    /** Bumped whenever the header layout or any payload shape changes. */
    const val VERSION: Int = 1

    const val HEADER_SIZE: Int = 24
    const val MAX_PAYLOAD: Int = 1 shl 20 // 1 MiB

    /** Handshake cadence and the watchdog that decides a peer is gone. */
    const val HEARTBEAT_INTERVAL_MS: Long = 3_000
    const val HEARTBEAT_TIMEOUT_MS: Long = 12_000

    /** Default port the daemon and desktop app listen on. */
    const val DEFAULT_PORT: Int = 43_817
}

/** Frame kinds. An unrecognised kind is a framing error, not a command error. */
object FrameType {
    const val HANDSHAKE: Int = 1
    const val COMMAND: Int = 2
    const val RESPONSE: Int = 3
    const val EVENT: Int = 4
    const val DATA: Int = 5
    const val ERROR: Int = 6

    val ALL: Set<Int> = setOf(HANDSHAKE, COMMAND, RESPONSE, EVENT, DATA, ERROR)

    fun name(type: Int): String = when (type) {
        HANDSHAKE -> "HANDSHAKE"
        COMMAND -> "COMMAND"
        RESPONSE -> "RESPONSE"
        EVENT -> "EVENT"
        DATA -> "DATA"
        ERROR -> "ERROR"
        else -> "UNKNOWN($type)"
    }
}

object FrameFlags {
    const val NONE: Int = 0
    const val ACK_REQUIRED: Int = 1 shl 0
    const val MORE: Int = 1 shl 1
    const val FINAL: Int = 1 shl 2
}

/**
 * Command numbers start at 100 so that later minor protocol revisions can add
 * low-numbered internal frames (pings, diagnostics) without colliding.
 */
object Command {
    const val HELLO: Int = 100
    const val LIST: Int = 101
    const val STAT: Int = 102
    const val HASH: Int = 103
    const val MKDIR: Int = 104
    const val DELETE: Int = 105
    const val RENAME: Int = 106
    const val SET_MTIME: Int = 107
    const val FREE_SPACE: Int = 108
    const val READ_OPEN: Int = 109
    const val READ_CHUNK: Int = 110
    const val WRITE_OPEN: Int = 111
    const val WRITE_CHUNK: Int = 112
    const val WRITE_CLOSE: Int = 113
    const val CLOSE_STREAM: Int = 114
    const val KEEPALIVE: Int = 115
    const val CANCEL: Int = 116
    const val ROOTS: Int = 117

    private val NAMES: Map<Int, String> = mapOf(
        HELLO to "HELLO",
        LIST to "LIST",
        STAT to "STAT",
        HASH to "HASH",
        MKDIR to "MKDIR",
        DELETE to "DELETE",
        RENAME to "RENAME",
        SET_MTIME to "SET_MTIME",
        FREE_SPACE to "FREE_SPACE",
        READ_OPEN to "READ_OPEN",
        READ_CHUNK to "READ_CHUNK",
        WRITE_OPEN to "WRITE_OPEN",
        WRITE_CHUNK to "WRITE_CHUNK",
        WRITE_CLOSE to "WRITE_CLOSE",
        CLOSE_STREAM to "CLOSE_STREAM",
        KEEPALIVE to "KEEPALIVE",
        CANCEL to "CANCEL",
        ROOTS to "ROOTS",
    )

    val ALL: Set<Int> = NAMES.keys

    fun isKnown(command: Int): Boolean = NAMES.containsKey(command)

    fun name(command: Int): String = NAMES[command] ?: "UNKNOWN($command)"
}

object ErrorCode {
    const val OK: Int = 0
    const val BAD_MAGIC: Int = 1
    const val UNSUPPORTED_VERSION: Int = 2
    const val UNKNOWN_COMMAND: Int = 3
    const val MALFORMED_PAYLOAD: Int = 4
    const val PAYLOAD_TOO_LARGE: Int = 5
    const val UNKNOWN_FRAME_TYPE: Int = 6
    const val TRUNCATED_FRAME: Int = 7
    const val TRUNCATED_PAYLOAD: Int = 8
    const val NOT_FOUND: Int = 9
    const val ALREADY_EXISTS: Int = 10
    const val IO_ERROR: Int = 11
    const val PERMISSION_DENIED: Int = 12
    const val CANCELLED: Int = 13
    const val PATH_OUTSIDE_ROOT: Int = 14
    const val RESUME_MISMATCH: Int = 15
    const val BAD_REQUEST: Int = 16
    const val INTERNAL: Int = 17
}

/**
 * Thrown by codec and message decoding. The [code] is a protocol error code so a
 * caller can turn it straight into an ERROR frame without guessing.
 */
class ProtocolException(
    val code: Int,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** One protocol frame, header fields plus payload. */
class Frame(
    val type: Int,
    val flags: Int,
    val streamId: Long,
    val reqNo: Int,
    val payload: ByteArray,
) {
    init {
        require(FrameType.ALL.contains(type)) { "unknown frame type: $type" }
        require(payload.size <= Protocol.MAX_PAYLOAD) {
            "payload ${payload.size} exceeds ${Protocol.MAX_PAYLOAD}"
        }
    }

    val payloadSize: Int get() = payload.size

    override fun toString(): String =
        "Frame(${FrameType.name(type)}, flags=$flags, stream=$streamId, req=$reqNo, len=${payload.size})"
}

/**
 * The fixed 24-byte prefix of a frame, parsed and validated on its own.
 *
 * A streaming reader needs the payload length before it knows how much to read,
 * and validating the header first means an unvalidated number can never drive an
 * allocation.
 */
class FrameHeader(
    val type: Int,
    val flags: Int,
    val streamId: Long,
    val reqNo: Int,
    val payloadLength: Int,
) {
    fun toFrame(payload: ByteArray): Frame {
        if (payload.size != payloadLength) {
            throw ProtocolException(
                ErrorCode.TRUNCATED_PAYLOAD,
                "frame declared $payloadLength payload byte(s) but ${payload.size} were supplied",
            )
        }
        return Frame(type, flags, streamId, reqNo, payload)
    }

    override fun toString(): String =
        "FrameHeader(${FrameType.name(type)}, flags=$flags, stream=$streamId, req=$reqNo, len=$payloadLength)"
}

/**
 * Frame encoding and decoding.
 *
 * Decoding never reads past the buffer it was given and never allocates based on
 * an unvalidated length, so a hostile or buggy peer cannot make the receiver
 * allocate a gigabyte before it notices something is wrong.
 */
object FrameCodec {

    fun encode(frame: Frame): ByteArray {
        val out = ByteArray(Protocol.HEADER_SIZE + frame.payloadSize)
        encodeInto(frame, out, 0)
        return out
    }

    /** Writes header + payload into [out] and returns the total number of bytes written. */
    fun encodeInto(frame: Frame, out: ByteArray, offset: Int): Int {
        val total = Protocol.HEADER_SIZE + frame.payload.size
        if (out.size - offset < total) {
            throw IllegalArgumentException("output buffer too small: need $total, have ${out.size - offset}")
        }
        var p = offset
        out[p++] = Protocol.MAGIC_0.toByte()
        out[p++] = Protocol.MAGIC_1.toByte()
        out[p++] = Protocol.VERSION.toByte()
        out[p++] = frame.type.toByte()
        p = writeInt(out, p, frame.flags)
        p = writeLong(out, p, frame.streamId)
        p = writeInt(out, p, frame.reqNo)
        p = writeInt(out, p, frame.payload.size)
        frame.payload.copyInto(out, p)
        return total
    }

    /**
     * Decodes one frame from [bytes]. [length] is the number of bytes available.
     *
     * Throws [ProtocolException] with a specific code for every malformed input:
     * a peer that sends garbage gets a diagnostic, not a desynchronised stream.
     */
    fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Frame {
        val header = readHeader(bytes, offset, length)
        if (length - Protocol.HEADER_SIZE < header.payloadLength) {
            throw ProtocolException(
                ErrorCode.TRUNCATED_PAYLOAD,
                "declared payload ${header.payloadLength} but only ${length - Protocol.HEADER_SIZE} byte(s) present",
            )
        }
        val payload = bytes.copyOfRange(
            offset + Protocol.HEADER_SIZE,
            offset + Protocol.HEADER_SIZE + header.payloadLength,
        )
        return header.toFrame(payload)
    }

    /**
     * Parses and validates just the fixed header.
     *
     * A streaming reader needs the payload length before it can decide how much to
     * read; validating the header first means a peer cannot make the receiver
     * allocate on the strength of an unvalidated number.
     */
    fun readHeader(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): FrameHeader {
        if (length < Protocol.HEADER_SIZE) {
            throw ProtocolException(
                ErrorCode.TRUNCATED_FRAME,
                "need ${Protocol.HEADER_SIZE} header bytes, got $length",
            )
        }
        if (bytes[offset].toInt() and 0xFF != Protocol.MAGIC_0 ||
            bytes[offset + 1].toInt() and 0xFF != Protocol.MAGIC_1
        ) {
            throw ProtocolException(
                ErrorCode.BAD_MAGIC,
                "bad magic 0x${hexByte(bytes[offset])}${hexByte(bytes[offset + 1])}",
            )
        }
        val version = bytes[offset + 2].toInt() and 0xFF
        if (version != Protocol.VERSION) {
            throw ProtocolException(
                ErrorCode.UNSUPPORTED_VERSION,
                "peer protocol version $version, this build speaks ${Protocol.VERSION}",
            )
        }
        val type = bytes[offset + 3].toInt() and 0xFF
        if (!FrameType.ALL.contains(type)) {
            throw ProtocolException(ErrorCode.UNKNOWN_FRAME_TYPE, "unknown frame type $type")
        }
        val payloadLength = readInt(bytes, offset + 20)
        if (payloadLength < 0 || payloadLength > Protocol.MAX_PAYLOAD) {
            throw ProtocolException(
                ErrorCode.PAYLOAD_TOO_LARGE,
                "declared payload $payloadLength outside 0..${Protocol.MAX_PAYLOAD}",
            )
        }
        return FrameHeader(
            type = type,
            flags = readInt(bytes, offset + 4),
            streamId = readLong(bytes, offset + 8),
            reqNo = readInt(bytes, offset + 16),
            payloadLength = payloadLength,
        )
    }

    /**
     * Reads just enough of a header to learn how big the frame is. Returns the
     * payload length, or throws if the 8-byte prefix is not a plausible header.
     */
    fun peekPayloadLength(prefix: ByteArray, offset: Int = 0, available: Int = prefix.size - offset): Int {
        if (available < Protocol.HEADER_SIZE) {
            throw ProtocolException(ErrorCode.TRUNCATED_FRAME, "header prefix incomplete")
        }
        val length = readInt(prefix, offset + 20)
        if (length < 0 || length > Protocol.MAX_PAYLOAD) {
            throw ProtocolException(ErrorCode.PAYLOAD_TOO_LARGE, "declared payload $length is out of range")
        }
        return length
    }

    private fun writeInt(out: ByteArray, offset: Int, value: Int): Int {
        var p = offset
        for (shift in 24 downTo 0 step 8) out[p++] = (value ushr shift).toByte()
        return p
    }

    private fun writeLong(out: ByteArray, offset: Int, value: Long): Int {
        var p = offset
        for (shift in 56 downTo 0 step 8) out[p++] = (value ushr shift).toByte()
        return p
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int {
        var value = 0
        for (i in 0 until 4) value = (value shl 8) or (bytes[offset + i].toInt() and 0xFF)
        return value
    }

    private fun readLong(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return value
    }
}
