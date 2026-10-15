package io.github.cubsync.core.util

import java.security.MessageDigest

private class JvmDigest(algorithm: String) : Digest {
    private val delegate: MessageDigest = MessageDigest.getInstance(algorithm)
    private val buffer = StringBuilder(64)

    override fun update(bytes: ByteArray, offset: Int, length: Int) {
        delegate.update(bytes, offset, length)
    }

    override fun hex(): String {
        val bytes = delegate.digest()
        buffer.setLength(0)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            buffer.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return buffer.toString()
    }

    private companion object {
        const val HEX = "0123456789abcdef"
    }
}

/** Maps the wire algorithm name onto a JCA name. */
private fun jcaName(algorithm: String): String = when (Algorithms.require(algorithm)) {
    Algorithms.MD5 -> "MD5"
    Algorithms.SHA256 -> "SHA-256"
    else -> error("unreachable")
}

actual fun newDigest(algorithm: String): Digest = JvmDigest(jcaName(algorithm))

actual fun md5Of(bytes: ByteArray): String = newDigest(Algorithms.MD5).apply { update(bytes) }.hex()
