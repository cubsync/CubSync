package io.github.cubsync.cli

import io.github.cubsync.backends.lan.LanBackend
import io.github.cubsync.backends.lan.LanServer
import io.github.cubsync.backends.local.LocalBackend
import io.github.cubsync.core.engine.InMemorySnapshotStore
import io.github.cubsync.core.engine.JobListener
import io.github.cubsync.core.engine.JobRunner
import io.github.cubsync.core.engine.PairOutcome
import io.github.cubsync.core.engine.SnapshotStore
import io.github.cubsync.core.model.CompareMode
import io.github.cubsync.core.model.ConflictPolicy
import io.github.cubsync.core.model.DeletePolicy
import io.github.cubsync.core.model.Endpoint
import io.github.cubsync.core.model.FilterSet
import io.github.cubsync.core.model.FolderPair
import io.github.cubsync.core.model.Ids
import io.github.cubsync.core.model.SyncConfig
import io.github.cubsync.core.model.SyncDirection
import io.github.cubsync.core.protocol.Protocol
import io.github.cubsync.core.spi.SimpleRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
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
        // `--state-dir` decides where configurations and snapshots live, so it is
        // read here rather than by each sub-command.
        val store = ConfigStore.at(args.string("state-dir"))
        when (command) {
            "serve" -> serve(args)
            "ls" -> listPeer(args)
            "push" -> transfer(args, push = true)
            "pull" -> transfer(args, push = false)
            "compare" -> transfer(args, push = true, forceDryRun = true)
            "run" -> runConfig(args, store)
            "config" -> configCommand(args, store)
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
 * The log lines are printed after the socket is bound, so they reflect a server
 * that is really listening rather than one that is about to be.
 */
private fun serve(args: Args): Int = runBlocking {
    val roots = args.stringList("root").ifEmpty {
        throw UsageException("--root is required (repeat the flag to expose several folders)")
    }.map { Paths.get(expand(it)).toAbsolutePath().normalize().toString() }

    for (root in roots) {
        if (!Files.isDirectory(Paths.get(root))) throw CommandFailedException("not a folder: $root")
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

private fun listPeer(args: Args): Int = runBlocking {
    val target = EndpointId.parse(args.positional.firstOrNull() ?: args.requireString("to"))
    if (target.isLocal) throw UsageException("ls needs a peer, not 'local'")
    val path = args.positional.getOrNull(1) ?: args.string("path")
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    try {
        val peer = LanBackend.connect(
            target.host!!,
            target.port!!,
            scope,
            nodeName = "cubsync cli",
            appVersion = BuildInfo.VERSION,
        )
        try {
            println("${peer.displayName} (${peer.remoteAddress})")
            val roots = peer.roots()
            if (path == null) {
                // A client cannot guess another machine's folder layout, so it asks.
                println("exposed folders (${roots.size}):")
                roots.forEach { println("  dir  ${it.path}") }
                return@runBlocking 0
            }
            val matchesARoot = roots.any { root ->
                val base = root.path.trimEnd('/', '\\')
                path == root.path || path.startsWith("$base/") || path.startsWith("$base\\")
            }
            if (!matchesARoot) println("note: $path is not one of this peer's exposed folders")
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
 * Pushes, pulls or (with `--dry-run`) compares one local folder with one peer folder.
 *
 * The flags build a one-pair configuration and hand it to the same runner a saved
 * configuration uses, so there is exactly one code path that synchronises.
 */
private fun transfer(args: Args, push: Boolean, forceDryRun: Boolean = false): Int = runBlocking {
    val remoteId = EndpointId.parse(if (push) args.requireString("to") else args.requireString("from"))
    if (remoteId.isLocal) throw UsageException("this command needs a peer, not 'local'")
    val remoteDir = args.requireString("remote")
    val localDir = Paths.get(expand(if (push) args.requireString("from") else args.requireString("to")))
        .toAbsolutePath()
        .normalize()
    if (!Files.isDirectory(localDir)) throw CommandFailedException("not a folder: $localDir")

    val config = SyncConfig(
        configId = "cli-${if (push) "push" else "pull"}",
        name = if (push) "cli push" else "cli pull",
        direction = parseDirection(args.string("direction") ?: "update-to", push),
        compare = parseCompare(args.string("compare") ?: "mtime"),
        conflict = parseConflict(args.string("conflict") ?: "ask"),
        delete = parseDelete(args.string("delete") ?: "recycle"),
        parallelism = args.int("parallel", 1),
        globalFilter = FilterSet(
            globs = args.stringList("filter"),
            includeHidden = args.flag("include-hidden"),
        ),
        dryRun = forceDryRun || args.flag("dry-run"),
        pairs = listOf(
            FolderPair(
                pairId = "cli",
                order = 0,
                left = if (push) {
                    Endpoint(EndpointId.LOCAL, localDir.toString())
                } else {
                    Endpoint(remoteId.backendId, remoteDir)
                },
                right = if (push) {
                    Endpoint(remoteId.backendId, remoteDir)
                } else {
                    Endpoint(EndpointId.LOCAL, localDir.toString())
                },
            ),
        ),
    )
    execute(config, args, ConfigStore.at(args.string("state-dir")))
}

private fun runConfig(args: Args, store: ConfigStore): Int {
    val reference = args.positional.firstOrNull()
        ?: throw UsageException("a configuration id or a .cubcfg path is required")
    var config = store.resolve(reference)
    if (args.flag("dry-run")) config = config.copy(dryRun = true)
    val only = args.string("pair")
    if (only != null) {
        val selected = config.pairs.filter { it.pairId == only || it.name == only }
        if (selected.isEmpty()) throw CommandFailedException("no pair called '$only' in ${config.name}")
        config = config.copy(pairs = selected)
    }
    return execute(config, args, store)
}

/**
 * Runs a configuration and prints the result.
 *
 * Snapshots live in the state directory rather than in memory, because a two-way
 * run without history cannot tell a deletion from a new file. This command is what
 * a scheduled job would call, and a scheduled two-way sync that forgot every run
 * would keep resurrecting deleted files.
 */
private fun execute(config: SyncConfig, args: Args, store: ConfigStore): Int = runBlocking {
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val pool = BackendPool(scope)
    try {
        for (pair in config.pairs) {
            for (endpoint in listOf(pair.left, pair.right)) {
                if (EndpointId(endpoint.backendId).isLocal) pool.registerLocalRoot(endpoint.rootPath)
            }
        }

        val snapshots: SnapshotStore =
            if (config.dryRun && args.string("state-dir") == null) InMemorySnapshotStore() else store.snapshotStore()
        val runner = JobRunner(
            backends = pool.resolver,
            snapshots = snapshots,
            listener = if (args.flag("quiet") || args.flag("json")) JobListener.NOOP else ProgressPrinter(),
        )
        if (!args.flag("json") && !args.flag("quiet")) {
            println("${if (config.dryRun) "comparing" else "syncing"} ${config.name} (${config.pairs.size} pair(s))")
        }
        val result = runner.run(config)
        if (args.flag("json")) {
            println(Output.jobSummary(result, json = true))
        } else {
            println()
            print(Output.jobSummary(result, json = false))
            if (args.flag("verbose")) result.pairs.forEach { println(Output.pairDetail(it)) }
        }
        Output.exitCodeFor(result.status)
    } finally {
        pool.close()
        scope.cancel()
    }
}

private class ProgressPrinter : JobListener {
    private var bytes = 0L
    private var lastPrint = 0L

    override fun onPairStarted(pair: FolderPair) {
        println("  pair ${pair.name.ifEmpty { pair.pairId }} starting")
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

    override fun onPairFinished(outcome: PairOutcome) {
        print("\r")
        System.out.flush()
        println("  pair ${outcome.pairName}: ${Output.pairStatusName(outcome.status)} - ${Output.statsLine(outcome.stats)}")
    }
}

// ---------------------------------------------------------------------------
// config
// ---------------------------------------------------------------------------

private fun configCommand(args: Args, store: ConfigStore): Int {
    val action = args.positional.firstOrNull()
        ?: throw UsageException("config needs an action: new, list, show, add-pair, export or import")
    val rest = args.positional.drop(1)

    return when (action) {
        "list" -> {
            val configs = store.list()
            if (configs.isEmpty() && !args.flag("json")) {
                println("no configurations in ${store.configDirectory}")
            } else {
                print(ConfigOutput.list(configs, json = args.flag("json")))
            }
            0
        }

        "new" -> {
            val name = args.requireString("name")
            val config = SyncConfig(
                configId = args.string("id") ?: Ids.newConfigId(),
                name = name,
                favorite = args.flag("favorite"),
                direction = parseDirection(args.string("direction") ?: "two-way", push = true),
                compare = parseCompare(args.string("compare") ?: "mtime"),
                conflict = parseConflict(args.string("conflict") ?: "ask"),
                delete = parseDelete(args.string("delete") ?: "recycle"),
                parallelism = args.int("parallel", 1),
                globalFilter = FilterSet(
                    globs = args.stringList("filter"),
                    includeHidden = args.flag("include-hidden"),
                ),
            )
            val path = store.save(config)
            println("created ${config.name} (${config.configId})")
            println("  file  $path")
            println("  next: cubsync config add-pair ${config.configId} --left local --left-root DIR --right HOST:PORT --right-root DIR")
            0
        }

        "show" -> {
            val config = store.resolve(rest.firstOrNull() ?: args.requireString("id"))
            println(ConfigOutput.summary(config, json = args.flag("json")))
            0
        }

        "add-pair" -> {
            val config = store.resolve(rest.firstOrNull() ?: args.requireString("id"))
            val leftId = EndpointId.parse(args.string("left") ?: EndpointId.LOCAL)
            val rightId = EndpointId.parse(args.requireString("right"))
            val leftRoot = args.string("left-root") ?: throw UsageException("--left-root is required")
            val rightRoot = args.string("right-root") ?: throw UsageException("--right-root is required")

            if (leftId == rightId && sameFolder(leftRoot, rightRoot)) {
                throw CommandFailedException("a pair cannot have the same folder on both sides")
            }

            val override = when {
                args.flag("no-filter") -> FilterSet(enabled = false)
                args.stringList("filter").isNotEmpty() -> FilterSet(
                    globs = args.stringList("filter"),
                    includeHidden = args.flag("include-hidden"),
                )

                else -> null
            }
            val pair = FolderPair(
                pairId = args.string("id") ?: Ids.newPairId(),
                order = config.pairs.size,
                name = args.string("name") ?: "",
                left = Endpoint(leftId.backendId, normalizeRoot(leftRoot)),
                right = Endpoint(rightId.backendId, normalizeRoot(rightRoot)),
                filterOverride = override,
                directionOverride = args.string("direction")?.let { parseDirection(it, push = true) },
            )
            val updated = config.copy(pairs = config.pairs + pair)
            store.save(updated)
            println("added ${pair.pairId} to ${updated.name}: ${pair.left.rootPath} <-> ${pair.right.rootPath}")
            0
        }

        "export" -> {
            val config = store.resolve(rest.firstOrNull() ?: args.requireString("id"))
            val destination = Paths.get(expand(args.requireString("out"))).toAbsolutePath()
            val written = store.export(config, destination)
            println("exported ${config.name} to $written")
            0
        }

        "import" -> {
            val source = Paths.get(expand(rest.firstOrNull() ?: args.requireString("in"))).toAbsolutePath()
            if (!Files.isRegularFile(source)) throw CommandFailedException("no such file: $source")
            val imported = store.load(source)
            val named = args.string("name")?.let { imported.copy(name = it) } ?: imported
            // An imported file carries the id of the configuration it came from, and
            // saving it under that id would silently replace what is already here.
            val renamed = if (store.exists(named.configId)) {
                println("note: '${named.configId}' already exists; importing as a new configuration")
                named.copy(configId = Ids.newConfigId())
            } else {
                named
            }
            val saved = store.save(renamed)
            println("imported ${renamed.name} (${renamed.pairs.size} pair(s)) as ${renamed.configId} to $saved")
            0
        }

        else -> throw UsageException("unknown config action: $action")
    }
}

private fun sameFolder(a: String, b: String): Boolean =
    Paths.get(expand(a)).toAbsolutePath().normalize() == Paths.get(expand(b)).toAbsolutePath().normalize()

private fun normalizeRoot(path: String): String {
    val p = path.trim()
    return if (p.isEmpty()) p else p.replace('\\', '/').trimEnd('/').ifEmpty { "/" }
}

/** `~` and a leading environment variable, so a configuration can be written portably. */
private fun expand(path: String): String {
    var expanded = path.trim()
    if (expanded == "~" || expanded.startsWith("~/") || expanded.startsWith("~\\")) {
        expanded = System.getProperty("user.home") + expanded.substring(1)
    }
    if (expanded.startsWith("$")) {
        val name = expanded.substring(1).substringBefore('/').substringBefore('\\')
        val value = System.getenv(name)
        if (value != null) expanded = value + expanded.substring(1 + name.length)
    }
    return expanded
}

private fun parseDirection(value: String, push: Boolean): SyncDirection = when (value.lowercase()) {
    "two-way", "twoway", "both" -> SyncDirection.TWO_WAY
    "mirror-to", "mirror" -> if (push) SyncDirection.MIRROR_LEFT_TO_RIGHT else SyncDirection.MIRROR_RIGHT_TO_LEFT
    "mirror-from" -> if (push) SyncDirection.MIRROR_RIGHT_TO_LEFT else SyncDirection.MIRROR_LEFT_TO_RIGHT
    "update-to", "update" -> if (push) SyncDirection.UPDATE_LEFT_TO_RIGHT else SyncDirection.UPDATE_RIGHT_TO_LEFT
    "update-from" -> if (push) SyncDirection.UPDATE_RIGHT_TO_LEFT else SyncDirection.UPDATE_LEFT_TO_RIGHT
    "mirror-left-to-right" -> SyncDirection.MIRROR_LEFT_TO_RIGHT
    "mirror-right-to-left" -> SyncDirection.MIRROR_RIGHT_TO_LEFT
    "update-left-to-right" -> SyncDirection.UPDATE_LEFT_TO_RIGHT
    "update-right-to-left" -> SyncDirection.UPDATE_RIGHT_TO_LEFT
    "custom" -> SyncDirection.CUSTOM
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
