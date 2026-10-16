package io.github.cubsync.service

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Path handling shared by the front ends, so `~` means the same thing in both. */
object PathTools {

    /**
     * Expands the two things a person types that the filesystem does not: a leading
     * `~` for the home directory, and `$NAME` for an environment variable.
     */
    fun expand(path: String): String {
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

    /** An absolute, normalised path, with separators unified for display and storage. */
    fun absolute(path: String): String =
        Paths.get(expand(path)).toAbsolutePath().normalize().toString()

    fun toPath(path: String): Path = Paths.get(path).toAbsolutePath().normalize()

    fun isDirectory(path: String): Boolean = runCatching { Files.isDirectory(toPath(path)) }.getOrDefault(false)

    fun isReadable(path: String): Boolean = runCatching { Files.isReadable(toPath(path)) }.getOrDefault(false)

    /** A root path as the protocol and the configuration store it: forward slashes. */
    fun normalizeRoot(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return trimmed
        return trimmed.replace('\\', '/').trimEnd('/').ifEmpty { "/" }
    }

    /**
     * The last path segment, which is what a table column shows when there is no
     * room for the whole path.
     */
    fun leafName(path: String): String {
        val normalized = normalizeRoot(path).trimEnd('/')
        return normalized.substringAfterLast('/').ifEmpty { normalized }
    }

    /** A path shortened to its last [segments] segments, with a leading ellipsis. */
    fun shorten(path: String, segments: Int = 3): String {
        val normalized = normalizeRoot(path)
        val parts = normalized.split('/').filter { it.isNotEmpty() }
        if (parts.size <= segments) return normalized
        return ".../" + parts.takeLast(segments).joinToString("/")
    }

    /**
     * The anchor a path hangs from: `/` on Unix, `D:` for a Windows drive.
     *
     * This is the string form of "the top", not the backend's notion of a shared root —
     * a backend may expose `D:/photos` and nothing above it, in which case going "to the
     * root" from inside means `D:/photos`. The caller knows which of the two it wants;
     * this only splits the string.
     */
    fun rootOf(path: String): String {
        val normalized = normalizeRoot(path)
        if (normalized.isEmpty()) return ""
        if (normalized.startsWith("/")) return "/"
        val colon = normalized.indexOf(':')
        if (colon > 0) return normalized.substring(0, colon + 1)
        return normalized.substringBefore('/')
    }

    /** True when [child] is [parent] itself or lives inside it. */
    fun isInside(child: String, parent: String): Boolean {
        val a = normalizeRoot(absolute(child))
        val b = normalizeRoot(absolute(parent))
        if (a == b) return true
        return a.startsWith(b.trimEnd('/') + "/")
    }

    /** A file name that is free, by appending ` 2`, ` 3`, ... before the extension. */
    fun uniqueName(taken: Set<String>, candidate: String): String {
        if (candidate !in taken) return candidate
        val dot = candidate.lastIndexOf('.')
        val stem = if (dot > 0) candidate.substring(0, dot) else candidate
        val extension = if (dot > 0) candidate.substring(dot) else ""
        var index = 2
        while ("$stem $index$extension" in taken) index++
        return "$stem $index$extension"
    }
}
