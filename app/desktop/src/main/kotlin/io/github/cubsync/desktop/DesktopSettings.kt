package io.github.cubsync.desktop

import io.github.cubsync.core.model.CompareMode
import io.github.cubsync.core.model.ConflictPolicy
import io.github.cubsync.core.model.DeletePolicy
import io.github.cubsync.core.model.SyncDirection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * What the window remembers about a person's preferences.
 *
 * Kept in the state directory beside the configurations, for the same reason: the
 * whole of CubSync's memory is one folder that can be moved, backed up or deleted.
 * Written through a temporary file so a crash halfway through cannot leave a
 * half-parsed properties file that resets everything.
 */
class DesktopSettings(private val file: Path) {

    var darkTheme: Boolean = true
    var defaultDirection: SyncDirection = SyncDirection.TWO_WAY
    var defaultCompare: CompareMode = CompareMode.MTIME_AND_SIZE
    var defaultConflict: ConflictPolicy = ConflictPolicy.ASK
    var defaultDelete: DeletePolicy = DeletePolicy.RECYCLE
    var defaultParallelism: Int = 1

    /** The folders the server was last asked to share, so the choice survives a restart. */
    var sharedRoots: List<String> = emptyList()
    var sharePort: Int = 0
    var serverNodeName: String = ""

    /** The last peer a person typed, offered as the starting point next time. */
    var lastPeer: String = ""

    fun load(): DesktopSettings {
        if (!Files.isRegularFile(file)) return this
        val properties = Properties()
        runCatching { Files.newInputStream(file).use { properties.load(it) } }.getOrElse { return this }

        darkTheme = properties.boolean("theme.dark", true)
        defaultDirection = properties.enumOr("default.direction", SyncDirection.TWO_WAY)
        defaultCompare = properties.enumOr("default.compare", CompareMode.MTIME_AND_SIZE)
        defaultConflict = properties.enumOr("default.conflict", ConflictPolicy.ASK)
        defaultDelete = properties.enumOr("default.delete", DeletePolicy.RECYCLE)
        defaultParallelism = properties.getProperty("default.parallelism")?.toIntOrNull()?.coerceIn(1, MAX_PARALLELISM) ?: 1
        sharedRoots = properties.getProperty("server.roots")?.split('|')?.filter { it.isNotBlank() } ?: emptyList()
        sharePort = properties.getProperty("server.port")?.toIntOrNull() ?: 0
        serverNodeName = properties.getProperty("server.name").orEmpty()
        lastPeer = properties.getProperty("peer.last").orEmpty()
        return this
    }

    fun save() {
        val properties = Properties()
        properties.setProperty("theme.dark", darkTheme.toString())
        properties.setProperty("default.direction", defaultDirection.name)
        properties.setProperty("default.compare", defaultCompare.name)
        properties.setProperty("default.conflict", defaultConflict.name)
        properties.setProperty("default.delete", defaultDelete.name)
        properties.setProperty("default.parallelism", defaultParallelism.toString())
        properties.setProperty("server.roots", sharedRoots.joinToString("|"))
        properties.setProperty("server.port", sharePort.toString())
        properties.setProperty("server.name", serverNodeName)
        properties.setProperty("peer.last", lastPeer)

        runCatching {
            Files.createDirectories(file.parent)
            val temporary = file.resolveSibling("${file.fileName}.tmp")
            Files.newOutputStream(temporary).use { properties.store(it, "CubSync desktop preferences") }
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun Properties.boolean(key: String, fallback: Boolean): Boolean =
        getProperty(key)?.toBooleanStrictOrNull() ?: fallback

    private inline fun <reified T : Enum<T>> Properties.enumOr(key: String, fallback: T): T {
        val raw = getProperty(key) ?: return fallback
        return enumValues<T>().firstOrNull { it.name == raw } ?: fallback
    }

    companion object {
        const val FILE_NAME: String = "desktop.properties"

        /**
         * The largest parallelism the window offers. The engine accepts more, but a
         * value the interface cannot produce is a value nobody can reason about, so
         * a hand-edited file is brought back into range rather than honoured.
         */
        const val MAX_PARALLELISM: Int = 8

        fun inStateDirectory(stateDirectory: Path): DesktopSettings =
            DesktopSettings(stateDirectory.resolve(FILE_NAME)).load()

        fun at(stateDirectory: String): DesktopSettings =
            inStateDirectory(Paths.get(stateDirectory).toAbsolutePath().normalize())
    }
}
