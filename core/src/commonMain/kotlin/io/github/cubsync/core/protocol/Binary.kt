package io.github.cubsync.core.protocol

import kotlin.math.min

/**
 * Big-endian binary encoder used for every protocol payload.
 *
 * The frame header has a fixed layout, but payloads are hand-rolled per command.
 * Keeping one tiny writer/reader pair here avoids inventing a schema engine for
 * the handful of message types the protocol actually has.
 */
class BinWriter(initialCapacity: Int = 64) {
    private var buffer = ByteArray(initialCapacity)
    private var position = 0

    private fun ensure(extra: Int) {
        if (position + extra > buffer.size) {
            var newSize = if (buffer.isEmpty()) 64 else buffer.size * 2
            while (newSize < position + extra) newSize *= 2
            buffer = buffer.copyOf(newSize)
        }
    }

    fun u8(value: Int): BinWriter {
        ensure(1)
        buffer[position++] = value.toByte()
        return this
    }

    fun u16(value: Int): BinWriter {
        ensure(2)
        buffer[position++] = (value ushr 8).toByte()
        buffer[position++] = value.toByte()
        return this
    }

    fun i32(value: Int): BinWriter {
        ensure(4)
        for (shift in 24 downTo 0 step 8) buffer[position++] = (value ushr shift).toByte()
        return this
    }

    fun i64(value: Long): BinWriter {
        ensure(8)
        for (shift in 56 downTo 0 step 8) buffer[position++] = (value ushr shift).toByte()
        return this
    }

    fun bool(value: Boolean): BinWriter = u8(if (value) 1 else 0)

    /** Length-prefixed UTF-8 string; 4-byte length then encoded bytes. */
    fun string(value: String): BinWriter {
        val bytes = value.encodeToByteArray()
        i32(bytes.size)
        bytes(bytes)
        return this
    }

    /** Raw, un-prefixed bytes; used by chunk writers that already know the length. */
    fun bytes(value: ByteArray, offset: Int = 0, length: Int = value.size - offset): BinWriter {
        ensure(length)
        value.copyInto(buffer, position, offset, offset + length)
        position += length
        return this
    }

    fun size(): Int = position

    fun toByteArray(): ByteArray = buffer.copyOf(position)
}

/** Counterpart of [BinWriter]. Every read is bounds-checked and fails as [ProtocolException]. */
class BinReader(private val buffer: ByteArray, private val start: Int = 0, private val end: Int = buffer.size) {
    private var position = start

    val remaining: Int get() = end - position
    val offset: Int get() = position - start

    private fun need(count: Int) {
        if (remaining < count) {
            throw ProtocolException(
                ErrorCode.TRUNCATED_PAYLOAD,
                "payload ended early: need $count more byte(s) at offset $offset, $remaining available",
            )
        }
    }

    fun u8(): Int {
        need(1)
        return buffer[position++].toInt() and 0xFF
    }

    fun u16(): Int {
        need(2)
        val value = ((buffer[position].toInt() and 0xFF) shl 8) or (buffer[position + 1].toInt() and 0xFF)
        position += 2
        return value
    }

    fun i32(): Int {
        need(4)
        var value = 0
        repeat(4) { value = (value shl 8) or (buffer[position++].toInt() and 0xFF) }
        return value
    }

    fun i64(): Long {
        need(8)
        var value = 0L
        repeat(8) { value = (value shl 8) or (buffer[position++].toLong() and 0xFF) }
        return value
    }

    fun bool(): Boolean {
        val value = u8()
        if (value != 0 && value != 1) {
            throw ProtocolException(ErrorCode.MALFORMED_PAYLOAD, "boolean out of range: $value")
        }
        return value == 1
    }

    fun string(): String {
        val length = i32()
        if (length < 0) throw ProtocolException(ErrorCode.MALFORMED_PAYLOAD, "negative string length: $length")
        need(length)
        val text = buffer.decodeToString(position, position + length)
        position += length
        return text
    }

    fun bytes(length: Int): ByteArray {
        if (length < 0) throw ProtocolException(ErrorCode.MALFORMED_PAYLOAD, "negative byte count: $length")
        need(length)
        val out = buffer.copyOfRange(position, position + length)
        position += length
        return out
    }

    /** Copies into a caller-owned buffer to avoid an allocation per chunk. */
    fun bytesInto(target: ByteArray, targetOffset: Int, length: Int) {
        if (length < 0) throw ProtocolException(ErrorCode.MALFORMED_PAYLOAD, "negative byte count: $length")
        need(length)
        buffer.copyInto(target, targetOffset, position, position + length)
        position += length
    }

    fun skip(count: Int) {
        need(count)
        position += min(count, remaining)
    }

    fun requireExhausted() {
        if (remaining != 0) {
            throw ProtocolException(ErrorCode.MALFORMED_PAYLOAD, "$remaining trailing byte(s) in payload")
        }
    }
}

/** Every wire message knows how to serialise itself; decoding is centralised in [Messages]. */
interface WireMessage {
    fun write(writer: BinWriter)
}

private const val HEX_DIGITS = "0123456789abcdef"

internal fun hexByte(value: Byte): String {
    val v = value.toInt() and 0xFF
    return "${HEX_DIGITS[v ushr 4]}${HEX_DIGITS[v and 0x0F]}"
}

/** Lowercase hex, used for hashes and for diagnostics. */
fun ByteArray.toHexString(): String {
    val out = StringBuilder(size * 2)
    for (b in this) out.append(hexByte(b))
    return out.toString()
}
