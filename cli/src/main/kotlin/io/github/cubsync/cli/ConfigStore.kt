package io.github.cubsync.cli

import io.github.cubsync.core.model.SyncConfig
import io.github.cubsync.core.model.SyncConfigCodec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Where configurations live on disk.
 *
 * `<state directory>/configs/<configId>.cubcfg`, plus `<state directory>/snapshots`
 * for the per-pair history. Keeping both under one directory means a user can move
 * their whole CubSync state by moving one folder, and can see what the tool
 * remembers about them.
 */
class ConfigStore(private val base: Path) {

    val configDirectory: Path get() = base.resolve("configs")
    val snapshotDirectory: Path get() = base.resolve("snapshots")

    fun list(): List<SyncConfig> {
        if (!Files.isDirectory(configDirectory)) return emptyList()
        val found = ArrayList<SyncConfig>()
        Files.newDirectoryStream(configDirectory, "*$EXTENSION").use { entries ->
            for (entry in entries) {
                // A configuration that cannot be read is skipped rather than
                // failing the listing: one broken file must not hide the others.
                runCatching { load(entry) }.getOrNull()?.let { found.add(it) }
            }
        }
        return found.sortedBy { it.name.lowercase() }
    }

    fun load(path: Path): SyncConfig {
        val text = Files.readString(path)
        // A file may be a plain configuration or an export envelope, and the
        // extension is not a reliable way to tell them apart: somebody will rename
        // one. Trying the envelope first and falling back costs nothing.
        return runCatching { SyncConfigCodec.decodeExport(text) }.getOrElse { SyncConfigCodec.decode(text) }
    }

    /** Resolves either a config id, a file path, or a path with an extension. */
    fun resolve(reference: String): SyncConfig {
        val direct = Paths.get(reference)
        if (Files.isRegularFile(direct)) return load(direct)
        for (extension in listOf(CONFIG_EXTENSION, EXPORT_EXTENSION)) {
            val candidate = configDirectory.resolve("$reference$extension")
            if (Files.isRegularFile(candidate)) return load(candidate)
        }
        throw CommandFailedException("no configuration called '$reference' (looked in $configDirectory)")
    }

    fun save(config: SyncConfig): Path {
        Files.createDirectories(configDirectory)
        val target = pathFor(config.configId)
        val temporary = target.resolveSibling("${target.fileName}.tmp")
        Files.writeString(temporary, SyncConfigCodec.encode(config))
        Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return target
    }

    /** True when a configuration with this id is already saved. */
    fun exists(configId: String): Boolean = Files.isRegularFile(pathFor(configId))

    private fun pathFor(configId: String): Path {
        val safe = configId.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
        return configDirectory.resolve("$safe$CONFIG_EXTENSION")
    }

    fun export(config: SyncConfig, destination: Path): Path {
        destination.parent?.let { Files.createDirectories(it) }
        Files.writeString(
            destination,
            SyncConfigCodec.encodeExport(config, exportedAt = isoNow(), appVersion = BuildInfo.VERSION),
        )
        return destination
    }

    fun snapshotStore(): io.github.cubsync.core.engine.SnapshotStore {
        Files.createDirectories(snapshotDirectory)
        return io.github.cubsync.core.engine.SnapshotFileStore(snapshotDirectory)
    }

    private fun isoNow(): String = java.time.Instant.now().toString()

    companion object {
        const val CONFIG_EXTENSION: String = ".json"
        const val EXPORT_EXTENSION: String = ".cubcfg"
        const val EXTENSION: String = ".json"

        fun defaultBase(): Path =
            Paths.get(System.getProperty("user.home"), ".cubsync").toAbsolutePath().normalize()

        fun at(configured: String?): ConfigStore =
            ConfigStore(if (configured == null) defaultBase() else Paths.get(configured).toAbsolutePath().normalize())
    }
}
