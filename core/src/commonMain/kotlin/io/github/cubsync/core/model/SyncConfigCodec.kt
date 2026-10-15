package io.github.cubsync.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Serialisable mirror of the configuration model.
 *
 * The engine works with plain data classes; this file owns the on-disk shape so
 * that a schema change is a single, reviewable edit and old files can still be
 * read through [SyncConfigCodec.upgrade].
 */
@Serializable
internal data class ConfigFile(
    val schemaVersion: Int,
    val configId: String,
    val name: String,
    val favorite: Boolean = false,
    val sortOrder: Int = 0,
    val direction: String,
    val compare: String,
    val conflict: String,
    val delete: String,
    val parallelism: Int = 1,
    val mtimeToleranceMs: Long = SyncConfig.DEFAULT_MTIME_TOLERANCE_MS,
    val keepPermissions: Boolean = false,
    val keepSymlink: Boolean = false,
    val keepEmptyDir: Boolean = true,
    val dryRun: Boolean = false,
    val recycleRoot: String = "",
    val globalFilter: FilterFile = FilterFile(),
    val remotePolicyHash: String = "",
    val pairs: List<PairFile> = emptyList(),
)

@Serializable
internal data class FilterFile(
    val enabled: Boolean = true,
    val mode: String = "BLACKLIST",
    val globs: List<String> = emptyList(),
    val presets: List<String> = emptyList(),
    val includeHidden: Boolean = false,
    val minSize: Long = -1,
    val maxSize: Long = -1,
    val timeAfterMs: Long = -1,
    val timeBeforeMs: Long = -1,
)

@Serializable
internal data class EndpointFile(val backendId: String, val rootPath: String, val rootFingerprint: String = "")

@Serializable
internal data class PairFile(
    val pairId: String,
    val order: Int,
    val enabled: Boolean = true,
    val name: String = "",
    val left: EndpointFile,
    val right: EndpointFile,
    val filterOverride: FilterFile? = null,
    val directionOverride: String? = null,
)

/** `*.cubcfg` envelope: the configuration plus provenance, so an import is traceable. */
@Serializable
internal data class ConfigExport(
    val format: String = FORMAT,
    val exportedAt: String,
    val appVersion: String,
    val config: ConfigFile,
) {
    companion object {
        const val FORMAT: String = "cubsync-config/1"
    }
}

object SyncConfigCodec {

    val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    /** Lenient reader used for imports from a newer build. */
    val importJson: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encode(config: SyncConfig): String = json.encodeToString(toFile(config))

    fun decode(text: String): SyncConfig = upgrade(fromFile(json.decodeFromString<ConfigFile>(text)))

    fun encodeExport(config: SyncConfig, exportedAt: String, appVersion: String): String =
        json.encodeToString(ConfigExport(exportedAt = exportedAt, appVersion = appVersion, config = toFile(config)))

    fun decodeExport(text: String): SyncConfig = upgrade(fromFile(importJson.decodeFromString<ConfigExport>(text).config))

    /**
     * Brings an older file up to [SyncConfig.CURRENT_SCHEMA]. Only one schema
     * exists today; the hook is here so the first migration has an obvious home.
     */
    fun upgrade(config: SyncConfig): SyncConfig {
        if (config.schemaVersion > SyncConfig.CURRENT_SCHEMA) {
            throw IllegalArgumentException(
                "configuration schema ${config.schemaVersion} is newer than this build understands " +
                    "(${SyncConfig.CURRENT_SCHEMA})",
            )
        }
        return config
    }

    private fun toFile(config: SyncConfig) = ConfigFile(
        schemaVersion = config.schemaVersion,
        configId = config.configId,
        name = config.name,
        favorite = config.favorite,
        sortOrder = config.sortOrder,
        direction = config.direction.name,
        compare = config.compare.name,
        conflict = config.conflict.name,
        delete = config.delete.name,
        parallelism = config.parallelism,
        mtimeToleranceMs = config.mtimeToleranceMs,
        keepPermissions = config.keepPermissions,
        keepSymlink = config.keepSymlink,
        keepEmptyDir = config.keepEmptyDir,
        dryRun = config.dryRun,
        recycleRoot = config.recycleRoot,
        globalFilter = toFile(config.globalFilter),
        remotePolicyHash = config.remotePolicyHash,
        pairs = config.pairs.map { pair ->
            PairFile(
                pairId = pair.pairId,
                order = pair.order,
                enabled = pair.enabled,
                name = pair.name,
                left = EndpointFile(pair.left.backendId, pair.left.rootPath, pair.left.rootFingerprint),
                right = EndpointFile(pair.right.backendId, pair.right.rootPath, pair.right.rootFingerprint),
                filterOverride = pair.filterOverride?.let { toFile(it) },
                directionOverride = pair.directionOverride?.name,
            )
        },
    )

    private fun toFile(filter: FilterSet) = FilterFile(
        enabled = filter.enabled,
        mode = filter.mode.name,
        globs = filter.globs,
        presets = filter.presets,
        includeHidden = filter.includeHidden,
        minSize = filter.minSize,
        maxSize = filter.maxSize,
        timeAfterMs = filter.timeAfterMs,
        timeBeforeMs = filter.timeBeforeMs,
    )

    private fun fromFile(file: ConfigFile) = SyncConfig(
        schemaVersion = file.schemaVersion,
        configId = file.configId,
        name = file.name,
        favorite = file.favorite,
        sortOrder = file.sortOrder,
        direction = enumOr(file.direction, SyncDirection.TWO_WAY),
        compare = enumOr(file.compare, CompareMode.MTIME_AND_SIZE),
        conflict = enumOr(file.conflict, ConflictPolicy.ASK),
        delete = enumOr(file.delete, DeletePolicy.RECYCLE),
        parallelism = file.parallelism.coerceAtLeast(1),
        mtimeToleranceMs = file.mtimeToleranceMs,
        keepPermissions = file.keepPermissions,
        keepSymlink = file.keepSymlink,
        keepEmptyDir = file.keepEmptyDir,
        dryRun = file.dryRun,
        recycleRoot = file.recycleRoot,
        globalFilter = fromFile(file.globalFilter),
        remotePolicyHash = file.remotePolicyHash,
        pairs = file.pairs.map { pair ->
            FolderPair(
                pairId = pair.pairId,
                order = pair.order,
                enabled = pair.enabled,
                name = pair.name,
                left = Endpoint(pair.left.backendId, pair.left.rootPath, pair.left.rootFingerprint),
                right = Endpoint(pair.right.backendId, pair.right.rootPath, pair.right.rootFingerprint),
                filterOverride = pair.filterOverride?.let { fromFile(it) },
                directionOverride = pair.directionOverride?.let { enumOr(it, SyncDirection.TWO_WAY) },
            )
        },
    )

    private fun fromFile(file: FilterFile) = FilterSet(
        enabled = file.enabled,
        mode = enumOr(file.mode, FilterMode.BLACKLIST),
        globs = file.globs,
        presets = file.presets,
        includeHidden = file.includeHidden,
        minSize = file.minSize,
        maxSize = file.maxSize,
        timeAfterMs = file.timeAfterMs,
        timeBeforeMs = file.timeBeforeMs,
    )

    private inline fun <reified T : Enum<T>> enumOr(name: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback
}
