package io.github.cubsync.service

import io.github.cubsync.backends.lan.LanBackend
import io.github.cubsync.backends.local.LocalBackend
import io.github.cubsync.core.engine.BackendResolver
import io.github.cubsync.core.spi.RemoteBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

/** What a front end shows in a "connected peers" list. */
data class PeerSession(
    val backendId: String,
    val host: String,
    val port: Int,
    val nodeName: String,
    val appVersion: String,
    val protocolVersion: Int,
    val capabilities: Long,
    val roots: List<String>,
)

/**
 * Opens backends on demand and keeps them for as long as the pool lives.
 *
 * Two pairs pointing at the same peer must share one session: opening a second
 * connection to the same machine would double the handshakes and, worse, give the
 * two pairs independent views of the same folder. The same argument applies to a
 * window that browses a peer and then runs a job against it.
 */
class BackendPool(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val nodeName: String = "CubSync",
    private val appVersion: String = BuildInfo.VERSION,
    private val log: AppLog = AppLog(),
) : AutoCloseable {

    private val local = LocalBackend(id = EndpointId.LOCAL, displayName = "this computer")
    private val peers = LinkedHashMap<String, LanBackend>()
    private val registeredRoots = LinkedHashSet<String>()

    val resolver: BackendResolver = BackendResolver { backendId -> resolve(backendId) }

    /** The backend for this machine. One instance, because it holds no session state. */
    fun localBackend(): LocalBackend = local

    /** Declares a local folder that may be served, so a local endpoint can use it. */
    fun registerLocalRoot(path: String) {
        registeredRoots.add(PathTools.normalizeRoot(PathTools.absolute(path)))
    }

    fun registeredRoots(): List<String> = registeredRoots.toList()

    suspend fun resolve(backendId: String): RemoteBackend {
        val endpoint = EndpointId(backendId)
        if (endpoint.isLocal) return local
        val host = endpoint.host ?: throw IllegalArgumentException("unreadable endpoint '$backendId'")
        val port = endpoint.port ?: throw IllegalArgumentException("unreadable endpoint '$backendId'")
        return peer(host, port)
    }

    /**
     * The session for a peer, opening one if needed.
     *
     * A session whose socket has died is replaced rather than handed out, because a
     * window that reconnects after the other machine restarted should not have to
     * be restarted itself.
     */
    suspend fun peer(host: String, port: Int): LanBackend {
        val backendId = EndpointId.peer(host, port).backendId
        peers[backendId]?.let { existing ->
            if (existing.isOpen) return existing
            log.info("connect", "the session with ${existing.displayName} had ended; reconnecting")
            runCatching { existing.close() }
            peers.remove(backendId)
        }
        log.info("connect", "connecting to $host:$port")
        return withContext(Dispatchers.IO) {
            val opened = LanBackend.connect(
                host = host,
                port = port,
                scope = scope,
                nodeName = nodeName,
                platform = BuildInfo.PLATFORM,
                appVersion = appVersion,
            )
            peers[backendId] = opened
            log.info(
                "connect",
                "connected to ${opened.displayName} at ${opened.remoteAddress}",
                "protocol ${opened.peerProtocolVersion}, version ${opened.peerAppVersion.ifBlank { "unknown" }}",
            )
            opened
        }
    }

    /** Connects and reports what the peer is, for a "test connection" button. */
    suspend fun probe(host: String, port: Int): PeerSession {
        val backend = peer(host, port)
        val roots = backend.roots().map { it.path }
        return PeerSession(
            backendId = backend.id,
            host = host,
            port = port,
            nodeName = backend.displayName,
            appVersion = backend.peerAppVersion,
            protocolVersion = backend.peerProtocolVersion,
            capabilities = backend.capabilities,
            roots = roots,
        )
    }

    fun sessions(): List<String> = peers.keys.toList()

    fun isConnected(backendId: String): Boolean = peers[backendId]?.isOpen == true

    /** Closes one peer session, leaving the rest alone. */
    fun disconnect(backendId: String) {
        peers.remove(backendId)?.let { backend ->
            log.info("connect", "disconnecting from ${backend.displayName}")
            runCatching { backend.close() }
        }
    }

    override fun close() {
        peers.values.forEach { runCatching { it.close() } }
        peers.clear()
        runCatching { local.close() }
        runCatching { scope.cancel() }
    }
}
