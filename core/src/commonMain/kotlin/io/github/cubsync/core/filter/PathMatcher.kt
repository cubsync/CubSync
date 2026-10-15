package io.github.cubsync.core.filter

import io.github.cubsync.core.model.FilterMode
import io.github.cubsync.core.model.FilterSet
import io.github.cubsync.core.spi.FileMeta

/**
 * FreeFileSync-style glob compiler.
 *
 * Rules:
 *  - `*`  matches any run of characters except `/`
 *  - `?`  matches exactly one character except `/`
 *  - `**` matches any run including `/`
 *  - `[...]` character class, `[!...]` or `[^...]` negates it
 *  - `\x` escapes the next character
 *
 * A pattern with no `/` in it is matched against the *file name only*, so
 * `*.tmp` excludes temporary files at every depth — which is what users expect
 * and what FreeFileSync does.
 */
object Glob {

    /** Characters that must be escaped to be matched literally outside a class. */
    private const val META = "\\^$.|?*+()[]{}"

    private const val ESCAPE = '\\'

    fun toRegex(pattern: String): Regex = Regex(translate(pattern))

    /** Anchored regex source for [pattern]. */
    fun translate(pattern: String): String = "^" + body(pattern) + "$"

    private fun body(pattern: String): String {
        // A trailing `/**` also matches the directory itself, so `build/**`
        // excludes `build` rather than only its content.
        if (pattern.endsWith("/**")) return body(pattern.dropLast(3)) + "(?:/.*)?"

        val out = StringBuilder(pattern.length + 16)
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]

            if (c == ESCAPE && i + 1 < pattern.length) {
                out.append(literal(pattern[i + 1]))
                i += 2
                continue
            }

            if (c == '*') {
                val doubleStar = i + 1 < pattern.length && pattern[i + 1] == '*'
                when {
                    // `**/` matches any number of leading directories, including
                    // none: `**/*.tmp` also matches a file at the root.
                    doubleStar && i + 2 < pattern.length && pattern[i + 2] == '/' -> {
                        out.append("(?:.*/)?")
                        i += 3
                    }
                    // `**` on its own crosses directory boundaries.
                    doubleStar -> {
                        out.append(".*")
                        i += 2
                    }
                    // A single `*` stays inside one path component.
                    else -> {
                        out.append("[^/]*")
                        i += 1
                    }
                }
                continue
            }

            if (c == '?') {
                out.append("[^/]")
                i += 1
                continue
            }

            if (c == '[') {
                val close = findClassEnd(pattern, i)
                if (close < 0) {
                    out.append(literal('['))
                    i += 1
                    continue
                }
                out.append(classBody(pattern.substring(i + 1, close)))
                i = close + 1
                continue
            }

            out.append(literal(c))
            i += 1
        }
        return out.toString()
    }

    /** `[abc]`, `[a-z]`, `[!abc]` and `[^abc]`; anything else between brackets is literal. */
    private fun classBody(body: String): String {
        val out = StringBuilder(body.length + 4)
        out.append('[')
        var i = 0
        if (body.startsWith("!") || body.startsWith("^")) {
            // A negated class must not swallow the separator either, or `[!x]`
            // would match a whole directory level at once.
            out.append("^/")
            i = 1
        }
        while (i < body.length) {
            val ch = body[i]
            when {
                ch == ESCAPE && i + 1 < body.length -> {
                    out.append(literal(body[i + 1]))
                    i += 2
                }
                // `-` keeps its range meaning between two members.
                ch == '-' && i > 0 && i + 1 < body.length -> {
                    out.append('-')
                    i += 1
                }
                else -> {
                    out.append(literal(ch))
                    i += 1
                }
            }
        }
        out.append(']')
        return out.toString()
    }

    private fun literal(c: Char): String = if (META.indexOf(c) >= 0) "$ESCAPE$c" else c.toString()

    private fun findClassEnd(pattern: String, start: Int): Int {
        var i = start + 1
        while (i < pattern.length) {
            when (pattern[i]) {
                ESCAPE -> i++
                ']' -> return i
            }
            i++
        }
        return -1
    }
}

/**
 * Compiled filter. Compile once per job; [accept] is called for every entry of
 * every tree so it must not allocate.
 */
class PathMatcher(val filter: FilterSet, private val caseSensitive: Boolean = true) {

    private val patterns: List<Regex> = filter.globs.map {
        val raw = if (caseSensitive) it else it.lowercase()
        val normalized = if (raw.contains('/')) raw else "**/$raw"
        Glob.toRegex(normalized)
    }

    private val whitelist: Boolean = filter.mode == FilterMode.WHITELIST

    /** True when this matcher can reject something. */
    val active: Boolean = filter.enabled && (patterns.isNotEmpty() || !filter.includeHidden ||
        filter.minSize >= 0 || filter.maxSize >= 0 || filter.timeAfterMs >= 0 || filter.timeBeforeMs >= 0)

    /**
     * Directory acceptance ignores the size and time bounds, because a directory
     * has no size of its own.
     *
     * In whitelist mode a directory is never pruned by a pattern: a name that does
     * not match may still contain files that do. Only hidden directories are pruned,
     * and only when hidden entries were not asked for.
     */
    fun acceptDirectory(relPath: String): Boolean {
        if (!filter.enabled) return true
        if (relPath.isEmpty()) return true
        if (!filter.includeHidden && isHidden(relPath)) return false
        if (whitelist) return true
        if (patterns.isEmpty()) return true
        return !matchesAny(relPath)
    }

    fun accept(meta: FileMeta): Boolean {
        if (!filter.enabled) return true
        if (meta.isDirectory) return acceptDirectory(meta.relPath)
        if (meta.relPath.isEmpty()) return true
        if (!filter.includeHidden && isHidden(meta.relPath)) return false
        if (patterns.isNotEmpty()) {
            val hit = matchesAny(meta.relPath)
            if (whitelist && !hit) return false
            if (!whitelist && hit) return false
        }
        if (filter.minSize >= 0 && meta.size < filter.minSize) return false
        if (filter.maxSize >= 0 && meta.size > filter.maxSize) return false
        if (filter.timeAfterMs >= 0 && meta.mtimeMs < filter.timeAfterMs) return false
        if (filter.timeBeforeMs >= 0 && meta.mtimeMs > filter.timeBeforeMs) return false
        return true
    }

    private fun matchesAny(relPath: String): Boolean {
        val candidate = if (caseSensitive) relPath else relPath.lowercase()
        return patterns.any { it.matches(candidate) }
    }

    /**
     * A path is hidden when any of its components is, not just the last one: a file
     * inside a hidden directory is as invisible to a user as the directory is.
     */
    private fun isHidden(relPath: String): Boolean =
        relPath.split('/').any { it.length > 1 && it.startsWith(".") }
}
