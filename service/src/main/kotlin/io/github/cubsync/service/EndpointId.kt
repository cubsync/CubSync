package io.github.cubsync.service

/**
 * An endpoint identifier: `local`, or `lan:host:port` for a peer.
 *
 * This string is what a saved configuration stores in `Endpoint.backendId`, so a
 * configuration names which machine a pair refers to without recording how to reach
 * it anywhere else. One string, one parsing rule, and both front ends read it.
 */
data class EndpointId(val backendId: String) {

    val isLocal: Boolean get() = backendId == LOCAL

    val host: String?
        get() = if (isLocal || !backendId.startsWith("$LAN_PREFIX:")) {
            null
        } else {
            backendId.removePrefix("$LAN_PREFIX:").substringBeforeLast(':')
        }

    val port: Int?
        get() = if (isLocal) null else backendId.removePrefix("$LAN_PREFIX:").substringAfterLast(':').toIntOrNull()

    /** What a person should see instead of the raw identifier. */
    val label: String
        get() = if (isLocal) "this computer" else "${host ?: "?"}:${port ?: 0}"

    override fun toString(): String = backendId

    companion object {
        const val LOCAL: String = "local"
        const val LAN_PREFIX: String = "lan"

        fun local(): EndpointId = EndpointId(LOCAL)

        fun peer(host: String, port: Int): EndpointId = EndpointId("$LAN_PREFIX:$host:$port")

        /**
         * Parses what a user types. `local` and a bare host name with a port mean
         * what they say; anything without a port cannot be resolved unambiguously
         * and is refused rather than guessed.
         */
        fun parse(spec: String): EndpointId {
            val trimmed = spec.trim()
            if (trimmed.isEmpty()) throw IllegalArgumentException("empty endpoint")
            if (trimmed == LOCAL) return local()
            if (trimmed.startsWith("$LAN_PREFIX:")) {
                val body = trimmed.removePrefix("$LAN_PREFIX:")
                return peer(requireHost(body, spec), requirePort(body, spec))
            }
            return peer(requireHost(trimmed, spec), requirePort(trimmed, spec))
        }

        /** Never throws: what a front end needs when a field is being typed into. */
        fun parseOrNull(spec: String): EndpointId? = runCatching { parse(spec) }.getOrNull()

        private fun requireHost(body: String, spec: String): String {
            val host = body.substringBeforeLast(':').trim()
            if (host.isEmpty()) throw IllegalArgumentException("expected HOST:PORT, got '$spec'")
            return host
        }

        private fun requirePort(body: String, spec: String): Int {
            val text = body.substringAfterLast(':', "")
            return text.trim().toIntOrNull()?.takeIf { it in 1..65535 }
                ?: throw IllegalArgumentException("'$spec' does not end in a port number")
        }
    }
}
