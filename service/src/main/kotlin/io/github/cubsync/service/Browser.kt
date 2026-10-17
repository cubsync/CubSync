package io.github.cubsync.service

import io.github.cubsync.core.spi.FileMeta

/** One part of a breadcrumb. */
data class Crumb(val label: String, val path: String, val collapsed: Boolean = false)

/** How two rows of the same name differ, in the compare mode. */
enum class Difference(val label: String) {
    ONLY_LEFT("仅本机有"),
    ONLY_RIGHT("仅远端有"),
    CONTENT_DIFFERS("内容不同"),
    WILL_CREATE("缺项将创建"),
    SAME("一致"),
}

/** One aligned line of the compare view. Either side may be absent. */
data class CompareRow(
    val name: String,
    val left: FileMeta?,
    val right: FileMeta?,
    val difference: Difference,
)

/**
 * How one pane should mark a compare line.
 *
 * The prototype does not print the difference in a column beside the rows — both panes
 * keep their own columns, and the mark is a coloured edge plus a dimmed ghost row. So the
 * thing a screen needs is not the sentence but the mark.
 */
enum class CompareMark {
    SAME,
    ONLY_HERE,
    DIFFERS,
    MISSING,
}

/** A compare line as one pane draws it: this side's row, or the hole where it would be. */
data class CompareLine(
    val name: String,
    val entry: FileMeta?,
    val mark: CompareMark,
    /** What the other side has, so a ghost row can still draw the right icon. */
    val elsewhere: FileMeta? = null,
) {
    val ghost: Boolean get() = entry == null
    /** The row to describe when this side has none: the other side's, for the ghost. */
    val shown: FileMeta? get() = entry ?: elsewhere
}

/**
 * Pure browsing arithmetic: order, paths, breadcrumbs, differences.
 *
 * Everything here is a function of its arguments, which is what makes it testable as a
 * table and what stops the two front ends from disagreeing about whether "size,
 * descending" puts folders at the top.
 */
object Browsing {

    fun sort(entries: List<FileMeta>, prefs: BrowsePrefs): List<FileMeta> =
        sort(entries, prefs.sortKey, prefs.ascending, prefs.foldersFirst)

    fun sort(
        entries: List<FileMeta>,
        key: SortKey = SortKey.NAME,
        ascending: Boolean = true,
        foldersFirst: Boolean = true,
    ): List<FileMeta> {
        val byContent: Comparator<FileMeta> = when (key) {
            SortKey.NAME -> compareBy { it.relPath.lowercase() }
            SortKey.SIZE -> compareBy { it.size }
            SortKey.MODIFIED -> compareBy { it.mtimeMs }
        }
        // The tie-break is always the name: two files written in the same second, or
        // two empty files, still have to come out in a stable order.
        val full = byContent.thenBy { it.relPath.lowercase() }
        val ordered = if (ascending) full else full.reversed()
        if (!foldersFirst) return entries.sortedWith(ordered)
        // A partition rather than a sort key, so the chosen order survives inside each
        // half. Sorting by "is a directory" would put files on top, which is not what
        // "folders are not first" means.
        val sorted = entries.sortedWith(ordered)
        return sorted.filter { it.isDirectory } + sorted.filterNot { it.isDirectory }
    }

    /** The path one level up. The root is its own parent. */
    fun parent(path: String): String {
        val normalized = PathTools.normalizeRoot(path).trimEnd('/')
        if (normalized.isEmpty() || normalized == "/") return "/"
        val cut = normalized.lastIndexOf('/')
        if (cut <= 0) return "/"
        return normalized.substring(0, cut)
    }

    fun child(parent: String, name: String): String {
        val base = PathTools.normalizeRoot(parent).trimEnd('/')
        val clean = name.trim('/')
        if (clean.isEmpty()) return base.ifEmpty { "/" }
        return if (base.isEmpty()) "/$clean" else "$base/$clean"
    }

    fun name(path: String): String = PathTools.leafName(path).ifEmpty { path }

    /**
     * Breadcrumbs for a path, with the middle folded away when there is no room.
     *
     * The first and last segments are the ones somebody navigates by, so those are the
     * ones that survive a narrow pane; the fold is a crumb of its own so it can be
     * clicked to show the rest.
     */
    /**
     * Breadcrumbs for a path, every layer of it.
     *
     * The default used to be five segments, so a phone header folded `/sdcard/DCIM/Camera`
     * into `/ › ⋯ › Camera` even when there was room for all four and every layer is
     * tappable in the design. Nothing folds now unless a caller asks for a limit; a header
     * that runs out of width scrolls, which loses nothing.
     */
    fun crumbs(path: String, maxSegments: Int = Int.MAX_VALUE): List<Crumb> {
        val normalized = PathTools.normalizeRoot(path)
        val absolute = normalized.startsWith("/")
        val parts = normalized.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return listOf(Crumb("/", "/"))

        // `/sdcard/DCIM` is a root and a folder; `D:/Photos` is a drive and a folder.
        // Either way the first part anchors the rest, and it is the anchor that the
        // first crumb shows: "/" or "D:".
        val anchor = parts.first()
        val anchorLabel = if (absolute) "/" else anchor
        val anchorPath = if (absolute) "/" else "$anchor/"
        val prefix = if (absolute) "" else anchor
        val trailing = if (absolute) parts else parts.drop(1)

        fun under(segments: List<String>): String =
            (prefix + segments.joinToString("") { "/$it" }).ifEmpty { "/" }

        if (trailing.size + 1 <= maxSegments) {
            return buildList {
                add(Crumb(anchorLabel, anchorPath))
                trailing.indices.forEach { index ->
                    add(Crumb(trailing[index], under(trailing.take(index + 1))))
                }
            }
        }

        // Too long: keep the anchor, the last few segments, and fold the middle into
        // one crumb that still carries the path it hides, so clicking it goes there.
        val keep = maxSegments - 2
        val hidden = trailing.size - keep
        return buildList {
            add(Crumb(anchorLabel, anchorPath))
            add(Crumb("⋯", under(trailing.take(hidden)), collapsed = true))
            for (index in hidden until trailing.size) {
                add(Crumb(trailing[index], under(trailing.take(index + 1))))
            }
        }
    }

    /**
     * Where an address box's text should land.
     *
     * Typing `D:/photos/holiday.jpg` and pressing return means "open the folder this is
     * in" far more often than it means "open this file" — a file is not a folder, and
     * refusing the input would be a worse answer than doing the obvious thing. A path
     * that names something with no extension is left alone, so `D:/photos` still opens
     * `photos`.
     */
    fun resolveTypedPath(typed: String, existsAsFolder: (String) -> Boolean): String {
        val cleaned = PathTools.normalizeRoot(typed.trim())
        if (cleaned.isEmpty()) return cleaned
        if (existsAsFolder(cleaned)) return cleaned
        val name = PathTools.leafName(cleaned)
        val looksLikeAFile = name.contains('.') && !name.startsWith('.')
        return if (looksLikeAFile) parent(cleaned) else cleaned
    }

    /**
     * Aligns two listings by name for the compare mode.
     *
     * Rows that exist on one side only are kept rather than dropped, because a missing
     * file is the thing this view exists to show.
     */
    fun compare(left: List<FileMeta>, right: List<FileMeta>): List<CompareRow> {
        val leftBy = left.associateBy { it.relPath }
        val rightBy = right.associateBy { it.relPath }
        val names = (leftBy.keys + rightBy.keys).sortedWith(String.CASE_INSENSITIVE_ORDER)
        return names.map { name ->
            val l = leftBy[name]
            val r = rightBy[name]
            CompareRow(name, l, r, differenceBetween(l, r))
        }
    }

    /**
     * The same alignment, seen from one pane.
     *
     * Both panes stay two panes in 对比: each keeps its own columns and its own rows, and
     * the names are aligned so a missing file is a real hole rather than a row that
     * silently disappears. A ghost line carries the name of what the other side has, so
     * "will be created" and "does not exist here" are the same row.
     */
    fun compareLines(rows: List<CompareRow>, side: Side): List<CompareLine> = rows.map { row ->
        val mine = if (side == Side.LEFT) row.left else row.right
        val other = if (side == Side.LEFT) row.right else row.left
        val mark = when {
            mine == null -> CompareMark.MISSING
            row.difference == Difference.SAME -> CompareMark.SAME
            row.difference == Difference.CONTENT_DIFFERS -> CompareMark.DIFFERS
            else -> CompareMark.ONLY_HERE
        }
        CompareLine(row.name, mine, mark, other)
    }

    private fun differenceBetween(left: FileMeta?, right: FileMeta?): Difference = when {        left == null && right == null -> Difference.SAME
        right == null -> Difference.ONLY_LEFT
        left == null -> Difference.ONLY_RIGHT
        left.isDirectory != right.isDirectory -> Difference.CONTENT_DIFFERS
        left.isDirectory -> Difference.SAME
        left.size != right.size -> Difference.CONTENT_DIFFERS
        left.mtimeMs != right.mtimeMs -> Difference.CONTENT_DIFFERS
        else -> Difference.SAME
    }

    /** The file name that would not collide with what is already there. */
    fun uniqueName(taken: Set<String>, candidate: String): String = PathTools.uniqueName(taken, candidate)
}
