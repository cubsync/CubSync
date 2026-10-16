package io.github.cubsync.service

import io.github.cubsync.core.engine.SnapshotFileStore
import io.github.cubsync.core.engine.SnapshotStore
import io.github.cubsync.core.model.SyncConfig
import io.github.cubsync.core.model.SyncConfigCodec
import io.github.cubsync.core.util.TextFiles
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

/**
 * Where configurations live on disk.
 *
 * `<state directory>/configs/<configId>.json`, plus `<state directory>/snapshots`
 * for the per-pair history. Keeping both under one directory means a user can move
 * the whole of CubSync's memory by moving one folder, and can see what the tool
 * remembers about them.
 */
class ConfigStore(private val base: Path) {

    val baseDirectory: Path get() = base
    val configDirectory: Path get() = base.resolve("configs")
    val snapshotDirectory: Path get() = base.resolve("snapshots")

    fun list(): List<SyncConfig> {
        if (!Files.isDirectory(configDirectory)) return emptyList()
        val found = ArrayList<SyncConfig>()
        Files.newDirectoryStream(configDirectory, "*$EXTENSION").use { entries ->
            for (entry in entries) {
                // A configuration that cannot be read is skipped rather than failing
                // the listing: one broken file must not hide the others.
                runCatching { load(entry) }.getOrNull()?.let { found.add(it) }
            }
        }
        return found.sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase() }))
    }

    fun load(path: Path): SyncConfig {
        val text = TextFiles.read(path)
        // A file may be a plain configuration or an export envelope, and the
        // extension is not a reliable way to tell them apart: somebody will rename
        // one. Trying the envelope first and falling back costs nothing.
        return runCatching { SyncConfigCodec.decodeExport(text) }.getOrElse { SyncConfigCodec.decode(text) }
    }

    /** Resolves a config id, a file path, or a path with an extension. */
    fun resolve(reference: String): SyncConfig {
        val direct = Paths.get(PathTools.expand(reference))
        if (Files.isRegularFile(direct)) return load(direct)
        for (extension in listOf(CONFIG_EXTENSION, EXPORT_EXTENSION)) {
            val candidate = configDirectory.resolve("$reference$extension")
            if (Files.isRegularFile(candidate)) return load(candidate)
        }
        throw NoSuchElementException("no configuration called '$reference' (looked in $configDirectory)")
    }

    fun save(config: SyncConfig): Path {
        Files.createDirectories(configDirectory)
        val target = pathFor(config.configId)
        val temporary = target.resolveSibling("${target.fileName}.tmp")
        TextFiles.write(temporary, SyncConfigCodec.encode(config))
        Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return target
    }

    fun delete(configId: String): Boolean = Files.deleteIfExists(pathFor(configId))

    /** True when a configuration with this id is already saved. */
    fun exists(configId: String): Boolean = Files.isRegularFile(pathFor(configId))

    fun pathFor(configId: String): Path {
        val safe = configId.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
        return configDirectory.resolve("$safe$CONFIG_EXTENSION")
    }

    fun export(config: SyncConfig, destination: Path): Path {
        destination.parent?.let { Files.createDirectories(it) }
        TextFiles.write(
            destination,
            SyncConfigCodec.encodeExport(config, exportedAt = Instant.now().toString(), appVersion = BuildInfo.VERSION),
        )
        return destination
    }

    fun snapshotStore(): SnapshotStore {
        Files.createDirectories(snapshotDirectory)
        return SnapshotFileStore(snapshotDirectory)
    }

    companion object {
        const val CONFIG_EXTENSION: String = ".json"
        const val EXPORT_EXTENSION: String = ".cubcfg"
        const val EXTENSION: String = ".json"

        fun defaultBase(): Path =
            Paths.get(System.getProperty("user.home"), ".cubsync").toAbsolutePath().normalize()

        fun at(configured: String?): ConfigStore =
            ConfigStore(if (configured.isNullOrBlank()) defaultBase() else Paths.get(configured).toAbsolutePath().normalize())
    }
}
