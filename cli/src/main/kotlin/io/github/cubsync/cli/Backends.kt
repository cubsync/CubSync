package io.github.cubsync.cli

import io.github.cubsync.backends.lan.LanBackend
import io.github.cubsync.backends.local.LocalBackend
import io.github.cubsync.core.engine.BackendResolver
import io.github.cubsync.core.spi.RemoteBackend
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Paths

/**
 * An endpoint identifier: `local`, or `host:port` for a peer.
 *
 * The identifier is what a configuration stores in `Endpoint.backendId`, so a saved
 * configuration says which machine a pair refers to without saying how to reach it
 * in any other way — one string, and the resolution rule lives here.
 */
data class EndpointId(val backendId: String) {
    val isLocal: Boolean get() = backendId == LOCAL

    val host: String?
        get() = if (isLocal || !backendId.startsWith("$LAN_PREFIX:")) null else backendId.removePrefix("$LAN_PREFIX:").substringBeforeLast(':')

    val port: Int?
        get() = if (isLocal) null else backendId.removePrefix("$LAN_PREFIX:").substringAfterLast(':').toIntOrNull()

    override fun toString(): String = backendId

    companion object {
        const val LOCAL: String = "local"
        const val LAN_PREFIX: String = "lan"

        fun local(): EndpointId = EndpointId(LOCAL)

        fun peer(host: String, port: Int): EndpointId = EndpointId("$LAN_PREFIX:$host:$port")

        /**
         * Parses what a user types. `local` and a bare path mean this machine;
         * anything with a colon is a peer. A bare host name without a port cannot be
         * resolved unambiguously, so it is refused rather than guessed.
         */
        fun parse(spec: String): EndpointId {
            val trimmed = spec.trim()
            if (trimmed.isEmpty()) throw UsageException("empty endpoint")
            if (trimmed == LOCAL) return local()
            if (trimmed.startsWith("$LAN_PREFIX:")) {
                val body = trimmed.removePrefix("$LAN_PREFIX:")
                return peer(requireHost(body), requirePort(body))
            }
            return peer(requireHost(trimmed), requirePort(trimmed))
        }

        private fun requireHost(spec: String): String {
            val host = spec.substringBeforeLast(':')
            if (host.isEmpty()) throw UsageException("expected HOST:PORT, got '$spec'")
            return host
        }

        private fun requirePort(spec: String): Int {
            val text = spec.substringAfterLast(':', "")
            return text.toIntOrNull()?.takeIf { it in 1..65535 }
                ?: throw UsageException("'$spec' does not end in a port number")
        }
    }
}

/**
 * Opens backends on demand and keeps them for the length of a run.
 *
 * Two pairs pointing at the same peer must share one session: opening a second
 * connection to the same machine would double the handshakes and, worse, give the
 * two pairs independent views of the same folder.
 */
class BackendPool(private val scope: CoroutineScope) {

    private val local = LocalBackend(displayName = "this computer")
    private val peers = LinkedHashMap<String, LanBackend>()
    private val registeredRoots = LinkedHashSet<String>()

    init {
        registeredRoots.add(Paths.get(".").toAbsolutePath().normalize().toString())
    }

    /** Declares a local folder that may be served, so a local endpoint can use it. */
    fun registerLocalRoot(path: String) {
        registeredRoots.add(Paths.get(path).toAbsolutePath().normalize().toString())
    }

    val resolver: BackendResolver = BackendResolver { backendId -> resolve(backendId) }

    suspend fun resolve(backendId: String): RemoteBackend {
        val endpoint = EndpointId(backendId)
        if (endpoint.isLocal) return local
        val host = endpoint.host ?: throw CommandFailedException("unreadable endpoint '$backendId'")
        val port = endpoint.port ?: throw CommandFailedException("unreadable endpoint '$backendId'")
        return peers.getOrPut(backendId) {
            LanBackend.connect(host, port, scope, nodeName = "cubsync cli", appVersion = BuildInfo.VERSION)
        }
    }

    fun close() {
        peers.values.forEach { runCatching { it.close() } }
        peers.clear()
        runCatching { local.close() }
    }
}
