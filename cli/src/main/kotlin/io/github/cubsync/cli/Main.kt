package io.github.cubsync.cli

import io.github.cubsync.backends.lan.LanBackend
import io.github.cubsync.backends.lan.LanServer
import io.github.cubsync.backends.local.LocalBackend
import io.github.cubsync.core.engine.InMemorySnapshotStore
import io.github.cubsync.core.engine.JobListener
import io.github.cubsync.core.engine.JobRunner
import io.github.cubsync.core.engine.SnapshotFileStore
import io.github.cubsync.core.engine.SnapshotStore
import io.github.cubsync.core.model.CompareMode
import io.github.cubsync.core.model.ConflictPolicy
import io.github.cubsync.core.model.DeletePolicy
import io.github.cubsync.core.model.Endpoint
import io.github.cubsync.core.model.FilterSet
import io.github.cubsync.core.model.FolderPair
import io.github.cubsync.core.model.SyncConfig
import io.github.cubsync.core.model.SyncDirection
import io.github.cubsync.core.protocol.Protocol
import io.github.cubsync.core.spi.RemoteBackend
import io.github.cubsync.core.spi.SimpleRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

fun main(arguments: Array<String>) {
    val exit = run(arguments.toList())
    if (exit != 0) exitProcess(exit)
}

internal fun run(arguments: List<String>): Int {
    if (arguments.isEmpty() || arguments.contains("--help") || arguments.contains("-h")) {
        println(Output.usage())
        return if (arguments.isEmpty()) EXIT_USAGE else 0
    }
    if (arguments.contains("--version") || arguments.contains("-v")) {
        println(Output.version())
        return 0
    }
    return try {
        val command = arguments.first()
        val args = Args.parse(arguments.drop(1))
        when (command) {
            "serve" -> serve(args)
            "ls" -> list(args)
            "push" -> transfer(args, push = true)
            "pull" -> transfer(args, push = false)
            "compare" -> transfer(args, push = true, forceDryRun = true)
            else -> throw UsageException("unknown command: $command")
        }
    } catch (usage: UsageException) {
        System.err.println("error: ${usage.message}")
        System.err.println("run `cubsync --help` for usage")
        EXIT_USAGE
    } catch (failure: Throwable) {
        System.err.println("error: ${failure.message ?: failure::class.simpleName}")
        EXIT_FAILURE
    }
}

/**
 * Serves one or more folders until interrupted.
 *
 * The server is bound before anything is printed, so the log line reflects a
 * socket that is really listening rather than one that is about to be.
 */
private fun serve(args: Args): Int = runBlocking {
    val roots = args.stringList("root").ifEmpty {
        throw UsageException("--root is required (repeat the flag to expose several folders)")
    }.map { Paths.get(it).toAbsolutePath().normalize().toString() }

    for (root in roots) {
        if (!Files.isDirectory(Paths.get(root))) {
            throw CommandFailedException("not a folder: $root")
        }
    }

    val backend = LocalBackend(displayName = LanServer.defaultNodeName())
    roots.forEach { backend.registerRoot(it) }

    val server = LanServer(
        backend = backend,
        exposedRoots = roots,
        port = args.int("port", Protocol.DEFAULT_PORT),
        bindAddress = args.string("bind") ?: "0.0.0.0",
        nodeName = args.string("name") ?: LanServer.defaultNodeName(),
        appVersion = BuildInfo.VERSION,
    )
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val port = server.bind()
    server.start(scope)

    println("cubsync ${BuildInfo.VERSION} - ${server.nodeName}")
    println(LanServer.describe(port, server.capabilities))
    for (root in roots) println("  exposing $root")
    println("press Ctrl-C to stop")

    try {
        while (true) delay(1_000)
    } catch (interrupted: InterruptedException) {
        println("stopping")
    } finally {
        scope.cancel()
        server.close()
    }
    0
}

private fun list(args: Args): Int = runBlocking {
    val target = args.positional.firstOrNull()?.let(::parseTarget)
        ?: (args.string("to")?.let(::parseTarget) ?: throw UsageException("HOST:PORT is required"))
    val path = args.positional.getOrNull(1) ?: args.string("path")
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    try {
        val peer = LanBackend.connect(target.host, target.port, scope, nodeName = "cubsync cli")
        try {
            println("${peer.displayName} (${peer.remoteAddress})")
            val roots = peer.roots()
            if (path == null) {
                // Without a path there is nothing to list: the peer's folder layout
                // is not something a client can guess, so it is asked for.
                println("exposed folders (${roots.size}):")
                roots.forEach { println("  dir  ${it.path}") }
                return@runBlocking 0
            }
            val matchesARoot = roots.any { root ->
                val base = root.path.trimEnd('/', '\\')
                path == root.path || path.startsWith("$base/") || path.startsWith("$base\\")
            }
            if (!matchesARoot) {
                println("note: $path is not one of this peer's exposed folders")
            }
            val entries = peer.list(SimpleRoot(peer.id, path), "", recursive = false)
            println("${entries.size} entries in $path")
            for (entry in entries.sortedBy { it.relPath }) {
                val kind = if (entry.isDirectory) "dir " else "file"
                println("  $kind ${entry.relPath.padEnd(60)} ${Output.humanBytes(entry.size).padStart(10)}")
            }
        } finally {
            peer.close()
        }
    } finally {
        scope.cancel()
    }
    0
}

/**
 * Pushes, pulls or (with `--dry-run`) compares a local folder with a peer's folder.
 *
 * The default direction is update-only: a first run against a peer must not be able
 * to delete anything there. Mirroring has to be asked for.
 */
private fun transfer(args: Args, push: Boolean, forceDryRun: Boolean = false): Int = runBlocking {
    val remoteSpec = if (push) args.requireString("to") else args.requireString("from")
    val target = parseTarget(remoteSpec)
    val remoteDir = args.requireString("remote")
    val localDir = Paths.get(
        if (push) args.requireString("from") else args.requireString("to"),
    ).toAbsolutePath().normalize()

    if (!Files.isDirectory(localDir)) {
        throw CommandFailedException("not a folder: $localDir")
    }

    val direction = parseDirection(args.string("direction") ?: "update-to", push)
    val filter = FilterSet(
        globs = args.stringList("filter"),
        includeHidden = args.flag("include-hidden"),
    )
    val dryRun = forceDryRun || args.flag("dry-run")
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    try {
        val local = LocalBackend(displayName = "local")
        local.registerRoot(localDir.toString())
        val peer = LanBackend.connect(target.host, target.port, scope, nodeName = "cubsync cli")

        try {
            println("${if (dryRun) "comparing" else "syncing"} $localDir with ${peer.displayName}:$remoteDir")
            val left: RemoteBackend = if (push) local else peer
            val right: RemoteBackend = if (push) peer else local
            val config = SyncConfig(
                configId = "cli-${if (push) "push" else "pull"}",
                name = if (push) "cli push" else "cli pull",
                direction = direction,
                compare = parseCompare(args.string("compare") ?: "mtime"),
                conflict = parseConflict(args.string("conflict") ?: "ask"),
                delete = parseDelete(args.string("delete") ?: "recycle"),
                parallelism = args.int("parallel", 1),
                globalFilter = filter,
                dryRun = dryRun,
                pairs = listOf(
                    FolderPair(
                        pairId = "cli",
                        order = 0,
                        left = Endpoint(left.id, if (push) localDir.toString() else remoteDir),
                        right = Endpoint(right.id, if (push) remoteDir else localDir.toString()),
                    ),
                ),
            )

            val snapshots: SnapshotStore = if (dryRun && args.string("state-dir") == null) {
                InMemorySnapshotStore()
            } else {
                SnapshotFileStore(stateDirectory(args))
            }

            val result = JobRunner(
                left = left,
                right = right,
                snapshots = snapshots,
                listener = if (args.flag("quiet")) JobListener.NOOP else ProgressPrinter(),
            ).run(config)

            println()
            print(Output.jobSummary(result, args.flag("json")))
            if (args.flag("verbose")) {
                result.pairs.forEach { println(Output.pairDetail(it)) }
            }
            Output.exitCodeFor(result.status)
        } finally {
            peer.close()
        }
    } finally {
        scope.cancel()
    }
}

private class ProgressPrinter : JobListener {
    private var bytes = 0L
    private var lastPrint = 0L

    override fun onPairStarted(pair: FolderPair) {
        println("  pair ${pair.pairId} starting")
    }

    override fun onBytesTransferred(pairId: String, relPath: String, delta: Long) {
        bytes += delta
        val now = System.currentTimeMillis()
        if (now - lastPrint > 1_000) {
            lastPrint = now
            print("\r  transferred ${Output.humanBytes(bytes)}   ")
            System.out.flush()
        }
    }

    override fun onPairFinished(outcome: io.github.cubsync.core.engine.PairOutcome) {
        print("\r")
        System.out.flush()
        println("  pair ${outcome.pairId}: ${Output.pairStatusName(outcome.status)} - ${Output.statsLine(outcome.stats)}")
    }
}

private fun stateDirectory(args: Args): Path {
    val configured = args.string("state-dir")
    val base = if (configured != null) {
        Paths.get(configured)
    } else {
        Paths.get(System.getProperty("user.home"), ".cubsync")
    }
    return base.toAbsolutePath().normalize()
}

private data class Target(val host: String, val port: Int)

private fun parseTarget(spec: String): Target {
    val parts = spec.split(':')
    if (parts.size != 2) throw UsageException("expected HOST:PORT, got '$spec'")
    val port = parts[1].toIntOrNull() ?: throw UsageException("'${parts[1]}' is not a port number")
    if (parts[0].isEmpty()) throw UsageException("expected HOST:PORT, got '$spec'")
    return Target(parts[0], port)
}

private fun parseDirection(value: String, push: Boolean): SyncDirection = when (value.lowercase()) {
    "two-way", "twoway", "both" -> SyncDirection.TWO_WAY
    "mirror-to", "mirror" -> if (push) SyncDirection.MIRROR_LEFT_TO_RIGHT else SyncDirection.MIRROR_RIGHT_TO_LEFT
    "mirror-from" -> if (push) SyncDirection.MIRROR_RIGHT_TO_LEFT else SyncDirection.MIRROR_LEFT_TO_RIGHT
    "update-to", "update" -> if (push) SyncDirection.UPDATE_LEFT_TO_RIGHT else SyncDirection.UPDATE_RIGHT_TO_LEFT
    "update-from" -> if (push) SyncDirection.UPDATE_RIGHT_TO_LEFT else SyncDirection.UPDATE_LEFT_TO_RIGHT
    else -> throw UsageException("unknown direction: $value")
}

private fun parseCompare(value: String): CompareMode = when (value.lowercase()) {
    "mtime", "time", "mtime+size" -> CompareMode.MTIME_AND_SIZE
    "hash", "content" -> CompareMode.CONTENT_HASH
    "size" -> CompareMode.SIZE_ONLY
    else -> throw UsageException("unknown compare mode: $value")
}

private fun parseConflict(value: String): ConflictPolicy = when (value.lowercase()) {
    "ask" -> ConflictPolicy.ASK
    "prefer-left", "left" -> ConflictPolicy.PREFER_LEFT
    "prefer-right", "right" -> ConflictPolicy.PREFER_RIGHT
    "keep-both", "both" -> ConflictPolicy.KEEP_BOTH
    "skip" -> ConflictPolicy.SKIP
    else -> throw UsageException("unknown conflict policy: $value")
}

private fun parseDelete(value: String): DeletePolicy = when (value.lowercase()) {
    "recycle", "trash" -> DeletePolicy.RECYCLE
    "propagate", "delete" -> DeletePolicy.PROPAGATE
    "keep", "none" -> DeletePolicy.KEEP
    else -> throw UsageException("unknown delete policy: $value")
}

internal const val EXIT_FAILURE: Int = 1
internal const val EXIT_USAGE: Int = 2
