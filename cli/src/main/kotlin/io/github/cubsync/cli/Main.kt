package io.github.cubsync.cli

import io.github.cubsync.backends.lan.LanServer
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
import io.github.cubsync.service.AppLog
import io.github.cubsync.service.BackendPool
import io.github.cubsync.service.BuildInfo
import io.github.cubsync.service.ConfigStore
import io.github.cubsync.service.EndpointId
import io.github.cubsync.service.PathTools
import io.github.cubsync.service.ServerController
import io.github.cubsync.service.ServerState
import io.github.cubsync.service.SyncObserver
import io.github.cubsync.service.SyncService
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
    } catch (failure: NoSuchElementException) {
        System.err.println("error: ${failure.message ?: failure::class.simpleName}")
        EXIT_FAILURE
    } catch (usage: IllegalArgumentException) {
        System.err.println("error: ${usage.message}")
        EXIT_USAGE
    } catch (failure: Throwable) {
        System.err.println("error: ${failure.message ?: failure::class.simpleName}")
        EXIT_FAILURE
    }
}

/**
 * Serves one or more folders until interrupted.
 *
 * The same controller the desktop window uses, so a folder served from a terminal
 * and a folder served from a button behave the same way. The lines are printed
 * after the socket is bound, so they describe a server that is really listening
 * rather than one that is about to be.
 */
private fun serve(args: Args): Int = runBlocking {
    val roots = args.stringList("root").ifEmpty {
        throw UsageException("--root is required (repeat the flag to expose several folders)")
    }
    val controller = ServerController()
    val state = controller.start(
        roots = roots,
        port = args.int("port", Protocol.DEFAULT_PORT),
        bindAddress = args.string("bind") ?: ServerState.DEFAULT_BIND,
        nodeName = args.string("name") ?: LanServer.defaultNodeName(),
    )
    if (!state.running) throw CommandFailedException(state.error)

    println("cubsync ${BuildInfo.VERSION} - ${state.nodeName}")
    println("sharing ${state.roots.size} folder(s) on port ${state.port}")
    state.roots.forEach { println("  exposing $it") }
    if (state.endpoints.isEmpty()) {
        println("  reachable at <this machine> :${state.port}")
    } else {
        state.endpoints.forEach { println("  reachable at $it") }
    }
    println("press Ctrl-C to stop")

    try {
        while (true) delay(1_000)
    } finally {
        controller.stop()
    }
    0
}

private fun listPeer(args: Args): Int = runBlocking {
    val target = parseEndpoint(args.positional.firstOrNull() ?: args.requireString("to"))
    if (target.isLocal) throw UsageException("ls needs a peer, not 'local'")
    val path = args.positional.getOrNull(1) ?: args.string("path")

    // The same pool a run uses, so a peer that a listing proved reachable is not
    // handshaken a second time when a job follows.
    val pool = BackendPool(nodeName = "cubsync cli")
    try {
        val session = pool.probe(target.host!!, target.port!!)
        println("${session.nodeName} (${session.host}:${session.port}) version ${session.appVersion.ifBlank { "?" }}")
        if (path == null) {
            // A client cannot guess another machine's folder layout, so it asks.
            println("exposed folders (${session.roots.size}):")
            session.roots.forEach { println("  dir  $it") }
            return@runBlocking 0
        }
        val matchesARoot = session.roots.any { root ->
            val base = root.trimEnd('/', '\\')
            path == root || path.startsWith("$base/") || path.startsWith("$base\\")
        }
        if (!matchesARoot) println("note: $path is not one of this peer's exposed folders")
        val entries = pool.peer(target.host!!, target.port!!)
            .list(SimpleRoot(target.backendId, path), "", recursive = false)
        println("${entries.size} entries in $path")
        for (entry in entries.sortedBy { it.relPath }) {
            val kind = if (entry.isDirectory) "dir " else "file"
            println("  $kind ${entry.relPath.padEnd(60)} ${Output.humanBytes(entry.size).padStart(10)}")
        }
    } finally {
        pool.close()
    }
    0
}

/**
 * Pushes, pulls or (with `--dry-run`) compares one local folder with one peer folder.
 *
 * The flags build a one-pair configuration and hand it to the same service a saved
 * configuration uses, so there is exactly one code path that synchronises.
 */
private fun transfer(args: Args, push: Boolean, forceDryRun: Boolean = false): Int = runBlocking {
    val remoteId = parseEndpoint(if (push) args.requireString("to") else args.requireString("from"))
    if (remoteId.isLocal) throw UsageException("this command needs a peer, not 'local'")
    val remoteDir = args.requireString("remote")
    val localDir = PathTools.absolute(if (push) args.requireString("from") else args.requireString("to"))
    if (!PathTools.isDirectory(localDir)) throw CommandFailedException("not a folder: $localDir")

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
                    Endpoint(EndpointId.LOCAL, localDir)
                } else {
                    Endpoint(remoteId.backendId, remoteDir)
                },
                right = if (push) {
                    Endpoint(remoteId.backendId, remoteDir)
                } else {
                    Endpoint(EndpointId.LOCAL, localDir)
                },
            ),
        ),
    )
    execute(config, args)
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
    return execute(config, args)
}

/**
 * Runs a configuration and prints the result.
 *
 * Snapshots live in the state directory rather than in memory, because a two-way
 * run without history cannot tell a deletion from a new file. This command is what
 * a scheduled job would call, and a scheduled two-way sync that forgot every run
 * would keep resurrecting deleted files.
 */
private fun execute(config: SyncConfig, args: Args): Int = runBlocking {
    val quiet = args.flag("quiet") || args.flag("json")
    val service = SyncService(
        stateDirectory = ConfigStore.at(args.string("state-dir")).baseDirectory,
        log = if (quiet) AppLog() else ConsoleLog,
    )
    try {
        if (!quiet) {
            println("${if (config.dryRun) "comparing" else "syncing"} ${config.name} (${config.pairs.size} pair(s))")
        }
        val result = service.run(config, observer = if (quiet) SyncObserver.NONE else ProgressPrinter())
        if (args.flag("json")) {
            println(Output.jobSummary(result, json = true))
        } else {
            println()
            print(Output.jobSummary(result, json = false))
            if (args.flag("verbose")) result.pairs.forEach { println(Output.pairDetail(it)) }
        }
        Output.exitCodeFor(result.status)
    } finally {
        service.close()
    }
}

/** Per-file detail is only interesting when something goes wrong, so it goes to the log. */
private val ConsoleLog: AppLog = AppLog().also { log ->
    log.subscribe { entry ->
        if (entry.level >= io.github.cubsync.service.LogLevel.WARN) {
            System.err.println("  ${entry.level.name.lowercase()}: ${entry.area}: ${entry.message}")
        }
    }
}

private class ProgressPrinter : SyncObserver {
    private var bytes = 0L
    private var lastPrint = 0L

    override fun onPairStarted(pair: FolderPair) {
        println("  pair ${pair.name.ifEmpty { pair.pairId }} starting")
    }

    override fun onBytesTransferred(total: Long) {
        bytes = total
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
            val leftId = parseEndpoint(args.string("left") ?: EndpointId.LOCAL)
            val rightId = parseEndpoint(args.requireString("right"))
            val leftRoot = args.string("left-root") ?: throw UsageException("--left-root is required")
            val rightRoot = args.string("right-root") ?: throw UsageException("--right-root is required")

            if (leftId == rightId && isSameFolder(leftRoot, rightRoot)) {
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
                left = Endpoint(leftId.backendId, PathTools.normalizeRoot(leftRoot)),
                right = Endpoint(rightId.backendId, PathTools.normalizeRoot(rightRoot)),
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
            val destination = Paths.get(PathTools.expand(args.requireString("out"))).toAbsolutePath()
            val written = store.export(config, destination)
            println("exported ${config.name} to $written")
            0
        }

        "import" -> {
            val source = Paths.get(PathTools.expand(rest.firstOrNull() ?: args.requireString("in"))).toAbsolutePath()
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

private fun isSameFolder(a: String, b: String): Boolean =
    PathTools.toPath(PathTools.expand(a)) == PathTools.toPath(PathTools.expand(b))

/** `EndpointId` refuses a bad spec with an [IllegalArgumentException]; a CLI wants an exit code. */
private fun parseEndpoint(spec: String): EndpointId =
    runCatching { EndpointId.parse(spec) }.getOrElse { failure ->
        throw UsageException(failure.message ?: "unreadable endpoint '$spec'")
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
