package io.github.cubsync.core.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import io.github.cubsync.core.util.TextFiles
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * One JSON file per folder pair, written atomically.
 *
 * The snapshot is the only thing that lets the next run tell a deletion from a new
 * file, so losing it to a crash halfway through a write would silently turn into
 * "everything was deleted" on the following run. Writing to a temporary file and
 * renaming it means a reader either sees the previous snapshot or the new one.
 */
class SnapshotFileStore(private val directory: Path) : SnapshotStore {

    @Serializable
    private data class EntryDto(val p: String, val s: Long, val m: Long, val h: String? = null)

    @Serializable
    private data class SnapshotDto(
        val pairId: String,
        val rev: Long,
        val takenAtMs: Long,
        val leftFingerprint: String,
        val rightFingerprint: String,
        val entries: List<EntryDto> = emptyList(),
    )

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override suspend fun load(pairId: String): PairSnapshot? = withContext(Dispatchers.IO) {
        val file = fileFor(pairId)
        if (!Files.isRegularFile(file)) return@withContext null
        val dto = try {
            json.decodeFromString<SnapshotDto>(TextFiles.read(file))
        } catch (unreadable: Exception) {
            // A snapshot that cannot be parsed is treated as absent: the run then
            // behaves like a first sync instead of failing outright.
            return@withContext null
        }
        PairSnapshot(
            pairId = dto.pairId,
            rev = dto.rev,
            takenAtMs = dto.takenAtMs,
            leftFingerprint = dto.leftFingerprint,
            rightFingerprint = dto.rightFingerprint,
            entries = dto.entries.associate { it.p to SnapshotEntry(it.p, it.s, it.m, it.h) },
        )
    }

    override suspend fun save(snapshot: PairSnapshot) = withContext(Dispatchers.IO) {
        Files.createDirectories(directory)
        val dto = SnapshotDto(
            pairId = snapshot.pairId,
            rev = snapshot.rev,
            takenAtMs = snapshot.takenAtMs,
            leftFingerprint = snapshot.leftFingerprint,
            rightFingerprint = snapshot.rightFingerprint,
            entries = snapshot.entries.values.map { EntryDto(it.relPath, it.size, it.mtimeMs, it.hash) },
        )
        val target = fileFor(snapshot.pairId)
        val temporary = target.resolveSibling("${target.fileName}.tmp")
        TextFiles.write(temporary, json.encodeToString(dto))
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        Unit
    }

    override suspend fun invalidate(pairId: String) = withContext(Dispatchers.IO) {
        Files.deleteIfExists(fileFor(pairId))
        Unit
    }

    private fun fileFor(pairId: String): Path {
        val safe = pairId.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
        return directory.resolve("$safe.json")
    }
}
