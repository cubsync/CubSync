package io.github.cubsync.service

import io.github.cubsync.backends.lan.LanServer
import io.github.cubsync.backends.local.LocalBackend
import io.github.cubsync.core.protocol.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

/** What the "this computer" card shows. */
data class ServerState(
    val running: Boolean = false,
    val port: Int = 0,
    val requestedPort: Int = Protocol.DEFAULT_PORT,
    val bindAddress: String = DEFAULT_BIND,
    val nodeName: String = "",
    val roots: List<String> = emptyList(),
    val addresses: List<String> = emptyList(),
    val sessions: Int = 0,
    val error: String = "",
) {
    /** The strings a person reads out to type on the other machine. */
    val endpoints: List<String> get() = if (!running) emptyList() else addresses.map { "$it:$port" }

    val connectionString: String get() = addresses.firstOrNull()?.let { "$it:$port" } ?: ""

    companion object {
        const val DEFAULT_BIND: String = "0.0.0.0"
    }
}

/**
 * Owns the folder server: exposing chosen local folders to other machines.
 *
 * The server is the piece that makes the product usable without a command line,
 * because "share this folder" is a thing a person does with a mouse. It is also the
 * one place that opens a listening socket, so it is the one place that has to be
 * careful about what it exposes: a folder has to exist and be readable before it is
 * served, and the state is published only once the port is actually bound.
 */
class ServerController(private val log: AppLog = AppLog()) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var server: LanServer? = null
    private var backend: LocalBackend? = null

    private val _state = MutableStateFlow(ServerState())
    val state: StateFlow<ServerState> = _state.asStateFlow()

    val isRunning: Boolean get() = _state.value.running

    /**
     * Starts serving [roots].
     *
     * @return the published state; when `running` is false, `error` says why.
     */
    fun start(
        roots: List<String>,
        port: Int = Protocol.DEFAULT_PORT,
        bindAddress: String = ServerState.DEFAULT_BIND,
        nodeName: String = LanServer.defaultNodeName(),
    ): ServerState {
        stop()

        val absolute = roots.map { PathTools.normalizeRoot(PathTools.absolute(it)) }.distinct()
        if (absolute.isEmpty()) {
            return fail(port, bindAddress, "choose at least one folder to share")
        }
        for (root in absolute) {
            if (!PathTools.isDirectory(root)) return fail(port, bindAddress, "not a folder: $root")
            if (!PathTools.isReadable(root)) return fail(port, bindAddress, "cannot read: $root")
        }

        val local = LocalBackend(id = EndpointId.LOCAL, displayName = nodeName)
        absolute.forEach { local.registerRoot(it) }

        val started = LanServer(
            backend = local,
            exposedRoots = absolute,
            port = port,
            bindAddress = bindAddress,
            nodeName = nodeName,
            platform = BuildInfo.PLATFORM,
            appVersion = BuildInfo.VERSION,
        )
        val bound = runCatching { started.bind() }.getOrElse { failure ->
            runCatching { started.close() }
            val message = failure.message ?: failure.toString()
            log.error("server", "could not listen on $bindAddress:$port", message)
            return fail(port, bindAddress, friendlyBindError(port, message))
        }
        started.start(scope)

        server = started
        backend = local

        val published = ServerState(
            running = true,
            port = bound,
            requestedPort = port,
            bindAddress = bindAddress,
            nodeName = started.nodeName,
            roots = absolute,
            addresses = localAddresses(),
        )
        _state.value = published
        log.info(
            "server",
            "sharing ${absolute.size} folder(s) on port $bound as ${started.nodeName}",
            published.endpoints.joinToString(", "),
        )
        watchSessions(started)
        return published
    }

    fun stop() {
        val running = server
        server = null
        runCatching { backend?.close() }
        backend = null
        if (running != null) {
            runCatching { running.close() }
            log.info("server", "stopped sharing folders")
        }
        _state.value = ServerState()
    }

    /**
     * Polls the session count so the card can say how many machines are connected.
     *
     * A counter rather than an event, because the server's accept loop has no
     * callback and a window only needs the number to be roughly current.
     */
    private fun watchSessions(started: LanServer) {
        scope.launch {
            while (server === started) {
                val sessions = runCatching { started.sessionCount }.getOrDefault(0)
                if (_state.value.sessions != sessions) {
                    _state.value = _state.value.copy(sessions = sessions)
                    if (sessions > 0) log.debug("server", "$sessions peer session(s) attached")
                }
                delay(1_000)
            }
        }
    }

    private fun fail(port: Int, bindAddress: String, message: String): ServerState {
        val state = ServerState(requestedPort = port, bindAddress = bindAddress, error = message)
        _state.value = state
        return state
    }

    private fun friendlyBindError(port: Int, message: String): String = when {
        message.contains("Address already in use", ignoreCase = true) ->
            "port $port is already in use; pick another one"

        message.contains("Permission denied", ignoreCase = true) ->
            "not allowed to listen on $port; try a port above 1024"

        else -> "could not start: $message"
    }

    companion object {
        /**
         * IPv4 addresses another machine could reach.
         *
         * Loopback is excluded because a person reading the address is going to type
         * it somewhere else, and link-local addresses are excluded because they need
         * a scope id to be usable.
         */
        fun localAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
                .filter { !it.startsWith("169.254.") }
                .distinct()
        }.getOrDefault(emptyList())
    }
}
