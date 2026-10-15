package io.github.cubsync.backends.lan

import io.github.cubsync.core.protocol.Bind
import io.github.cubsync.core.protocol.Capabilities
import io.github.cubsync.core.protocol.Command
import io.github.cubsync.core.protocol.ErrorCode
import io.github.cubsync.core.protocol.ErrorMessage
import io.github.cubsync.core.protocol.Frame
import io.github.cubsync.core.protocol.FrameFlags
import io.github.cubsync.core.protocol.FrameType
import io.github.cubsync.core.protocol.Handshake
import io.github.cubsync.core.protocol.HelloReply
import io.github.cubsync.core.protocol.HelloRequest
import io.github.cubsync.core.protocol.Messages
import io.github.cubsync.core.protocol.Protocol
import io.github.cubsync.core.spi.RemoteBackend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Accepts CubSync peers and serves a backend's trees to them.
 *
 * The server never trusts a path from the client: a request names one of the roots
 * it was started with, and every path inside that root is normalised and checked to
 * stay there. Running a server therefore exposes exactly the folders named on the
 * command line, and nothing reachable from them by `..`.
 *
 * Setting the connection up takes two steps on purpose. The control connection
 * completes the handshake and produces a session token; the data connection
 * presents that token and is paired with it. Without the pairing, any second
 * connection could attach itself to somebody else's session and start writing files.
 */
class LanServer(
    private val backend: RemoteBackend,
    private val exposedRoots: List<String>,
    private val port: Int = Protocol.DEFAULT_PORT,
    private val bindAddress: String = "0.0.0.0",
    val nodeName: String = defaultNodeName(),
    private val platform: String = System.getProperty("os.name") ?: "jvm",
    private val appVersion: String = "0.1.0",
    /** How long a write may stay open after the last chunk before it is refused. */
    private val writeCloseTimeoutMs: Long = 120_000,
) : AutoCloseable {

    private var serverSocket: ServerSocket? = null
    private var serverScope: CoroutineScope? = null
    private val sessions = ConcurrentHashMap<Long, LanConnection>()
    private val pending = ConcurrentHashMap<Long, PendingControl>()

    val capabilities: Long = LanConnection.SERVER_CAPABILITIES

    private val bound: Boolean get() = serverSocket != null

    val boundPort: Int get() = serverSocket?.localPort ?: port

    val sessionCount: Int get() = sessions.size

    /** Binds the listening socket and returns the port actually bound. */
    fun bind(): Int {
        check(!bound) { "this server is already bound" }
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(bindAddress, port))
        serverSocket = socket
        return socket.localPort
    }

    /** Starts accepting. Closing [scope] closes this server. */
    fun start(scope: CoroutineScope) {
        val socket = checkNotNull(serverSocket) { "bind() must be called first" }
        serverScope = scope
        scope.launch { acceptLoop(socket) }
        scope.coroutineContext[Job]?.invokeOnCompletion { close() }
    }

    override fun close() {
        pending.values.forEach { it.control.close() }
        pending.clear()
        sessions.values.forEach { it.close() }
        sessions.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverScope = null
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        val scope = checkNotNull(serverScope) { "start() must be called first" }
        while (true) {
            val accepted = try {
                withContext(Dispatchers.IO) { socket.accept() }
            } catch (closed: Exception) {
                return
            }
            FrameChannel.configure(accepted)
            // Handled on its own coroutine so a slow or stuck peer cannot stop
            // the accept loop.
            scope.launch { serveConnection(accepted, scope) }
        }
    }

    private suspend fun serveConnection(socket: Socket, scope: CoroutineScope) {
        val channel = FrameChannel(socket, "connection from ${socket.inetAddress?.hostAddress}")
        val first = runCatching { channel.receive() }.getOrNull()
        if (first == null || first.type != FrameType.HANDSHAKE) {
            channel.close()
            return
        }
        val bind = runCatching { Messages.decodeMessage(first.payload) }.getOrNull() as? Bind
        when (bind?.role) {
            Bind.ROLE_CONTROL -> handleControlConnection(channel, bind.token, scope)
            Bind.ROLE_DATA -> handleDataConnection(channel, bind.token, scope)
            else -> channel.close()
        }
    }

    private suspend fun handleControlConnection(control: FrameChannel, token: Long, scope: CoroutineScope) {
        val helloFrame = runCatching { control.receive() }.getOrNull()
        val request = helloFrame?.let {
            runCatching { Messages.decodeCommand(it.payload).message }.getOrNull() as? HelloRequest
        }
        if (helloFrame == null || request == null) {
            control.close()
            return
        }

        val verdict = Handshake.evaluate(
            ourVersion = Protocol.VERSION,
            ourCapabilities = capabilities,
            peerVersion = request.protocolVersion,
            peerCapabilities = request.capabilities,
        )
        if (verdict.rejected) {
            // Answered with the reason instead of dropping the connection, so an
            // outdated peer can say what is wrong rather than "connection closed".
            runCatching {
                control.send(
                    Frame(
                        FrameType.ERROR,
                        FrameFlags.NONE,
                        0,
                        helloFrame.reqNo,
                        Messages.encodeError(ErrorMessage(ErrorCode.UNSUPPORTED_VERSION, Command.HELLO, verdict.note)),
                    ),
                )
            }
            control.close()
            return
        }

        val reply = HelloReply(
            protocolVersion = Protocol.VERSION,
            capabilities = capabilities,
            deviceName = nodeName,
            platform = platform,
            appVersion = appVersion,
            accepted = true,
            note = verdict.note,
        )
        // The session is registered before the reply goes out: the peer opens the
        // data connection as soon as it sees the reply, and a token that is not
        // there yet would make that connection look like an intruder.
        val session = PendingControl(
            token = token,
            control = control,
            capabilities = verdict.negotiatedCapabilities,
            remoteName = request.deviceName.ifBlank { "peer" },
            scope = scope,
        )
        pending[token] = session

        val accepted = runCatching {
            control.send(
                Frame(
                    FrameType.RESPONSE,
                    FrameFlags.NONE,
                    0,
                    helloFrame.reqNo,
                    Messages.encodeCommand(Command.HELLO, reply),
                ),
            )
        }.isSuccess
        if (!accepted) {
            pending.remove(token)
            control.close()
            return
        }

        // If the matching data connection never arrives, the token is dropped
        // rather than left around as a way for a later connection to attach.
        if (withTimeoutOrNull(CONTROL_PAIR_TIMEOUT_MS) { session.paired.await() } == null) {
            pending.remove(token)
            control.close()
        }
    }

    private suspend fun handleDataConnection(data: FrameChannel, token: Long, scope: CoroutineScope) {
        val session = pending.remove(token)
        if (session == null) {
            data.close()
            return
        }
        val connection = LanConnection(
            sessionToken = token,
            remoteNodeName = session.remoteName,
            negotiatedCapabilities = session.capabilities,
            remoteAddress = data.remoteAddress,
            control = session.control,
            data = data,
            scope = session.scope,
            handler = ServerCommandHandler(
                backend = backend,
                exposedRoots = exposedRoots,
                nodeName = nodeName,
                writeCloseTimeoutMs = writeCloseTimeoutMs,
            ),
        )
        sessions[token] = connection
        connection.start()
        val bound = runCatching {
            data.send(
                Frame(
                    FrameType.HANDSHAKE,
                    FrameFlags.NONE,
                    0,
                    0,
                    Messages.encodeMessage(Bind(Bind.ROLE_DATA, token)),
                ),
            )
        }.isSuccess
        if (!bound) {
            sessions.remove(token)
            connection.close()
            return
        }
        session.paired.complete(Unit)
        // Keep the session list tidy; the connection closes itself when the peer
        // goes away and the watchdog notices.
        session.scope.launch {
            withTimeout(PAIR_LIFETIME_MS) { session.paired.await() }
            while (connection.isOpen) {
                kotlinx.coroutines.delay(1_000)
            }
            sessions.remove(token)
        }
    }

    private class PendingControl(
        val token: Long,
        val control: FrameChannel,
        val capabilities: Long,
        val remoteName: String,
        val scope: CoroutineScope,
        val paired: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    companion object {
        private const val CONTROL_PAIR_TIMEOUT_MS: Long = 10_000
        private const val PAIR_LIFETIME_MS: Long = 60_000

        fun defaultNodeName(): String = runCatching { java.net.InetAddress.getLocalHost().hostName }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "CubSync host"

        /** A session token is only useful if it cannot be guessed. */
        fun newSessionToken(random: Random = Random.Default): Long = random.nextLong(1L, Long.MAX_VALUE)

        /** Human-readable summary, used by the CLI on startup. */
        fun describe(port: Int, capabilities: Long): String =
            "listening on port $port with ${Capabilities.describe(capabilities).size} capability bit(s)"
    }
}
