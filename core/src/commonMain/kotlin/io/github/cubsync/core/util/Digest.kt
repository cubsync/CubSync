package io.github.cubsync.core.util

/** Streaming hash. Implementations live in the platform source set. */
interface Digest {
    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset)
    fun hex(): String
}

/** Supported hash names, as they appear on the wire. */
object Algorithms {
    const val MD5: String = "md5"
    const val SHA256: String = "sha256"

    val supported: Set<String> = setOf(MD5, SHA256)

    fun require(name: String): String {
        val normalized = name.lowercase()
        require(supported.contains(normalized)) { "unsupported hash algorithm: $name" }
        return normalized
    }
}

expect fun newDigest(algorithm: String): Digest

expect fun md5Of(bytes: ByteArray): String

/**
 * Fixed vectors used by tests and by cross-implementation checks. Hard-coded
 * expectations are the only way to notice an accidental change of hash semantics.
 */
object HashVectors {
    const val EMPTY_MD5: String = "d41d8cd98f00b204e9800998ecf8427e"
    const val EMPTY_SHA256: String = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    const val ABC_MD5: String = "900150983cd24fb0d6963f7d28e17f72"
    const val ABC_SHA256: String = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
}
