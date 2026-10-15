package io.github.cubsync.core.protocol

/**
 * Decides whether two peers can talk, and at which capability level.
 *
 * Pure and total so the version matrix can be tested as a table: every
 * combination of older, equal, and newer peer has a defined outcome, and a
 * mismatch produces an explanation rather than a dropped connection.
 */
object Handshake {

    data class Result(
        val accepted: Boolean,
        val protocolVersion: Int,
        val negotiatedCapabilities: Long,
        val note: String,
    ) {
        val rejected: Boolean get() = !accepted
    }

    fun evaluate(
        ourVersion: Int = Protocol.VERSION,
        ourCapabilities: Long,
        peerVersion: Int,
        peerCapabilities: Long,
    ): Result {
        if (peerVersion <= 0) {
            return Result(
                accepted = false,
                protocolVersion = ourVersion,
                negotiatedCapabilities = Capabilities.NONE,
                note = "peer announced an invalid protocol version ($peerVersion)",
            )
        }
        if (peerVersion > ourVersion) {
            return Result(
                accepted = false,
                protocolVersion = ourVersion,
                negotiatedCapabilities = Capabilities.NONE,
                note = "peer speaks protocol $peerVersion; this build speaks $ourVersion — update one of them",
            )
        }
        // An older peer is accepted at its own level: the intersection of the two
        // capability sets is by definition something both sides understand.
        val negotiated = Capabilities.negotiate(ourCapabilities, peerCapabilities)
        val note = if (peerVersion == ourVersion) {
            "protocol $ourVersion, ${Capabilities.describe(negotiated).size} capability bit(s) agreed"
        } else {
            "peer speaks protocol $peerVersion (older); running at that level with " +
                "${Capabilities.describe(negotiated).size} capability bit(s)"
        }
        return Result(true, peerVersion, negotiated, note)
    }
}
