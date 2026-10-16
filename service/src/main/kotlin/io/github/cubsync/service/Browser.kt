package io.github.cubsync.service

import io.github.cubsync.core.spi.FileMeta

/**
 * How a folder is shown.
 *
 * The five modes are the design's, and they are a decision about *reading*: detailed
 * when you are looking for a thing, compact when you are moving a lot of them, cards
 * and thumbnails when the name is not how you recognise it.
 */
enum class DisplayMode(val label: String) {
    COMPACT("紧凑"),
    DETAILED("详细"),
    CARD("卡片"),
    THUMBNAIL("缩略"),
    COMPARE("对比"),
    ;

    /** Only the grid modes have a column count; the list modes have a row height. */
    val grid: Boolean get() = this == CARD || this == THUMBNAIL
}

enum class SortKey(val label: String) {
    NAME("名称"),
    SIZE("大小"),
    MODIFIED("修改时间"),
}

enum class ArrangementDirection(val label: String) {
    AUTO("自动"),
    SIDE_BY_SIDE("左右"),
    STACKED("上下"),
}

/** How far a change reaches. More specific wins, so the order in the enum is the precedence. */
enum class Scope(val label: String) {
    CURRENT_DIRECTORY("当前目录"),
    PANE("本栏全局"),
    BOTH_PANES("两栏共用"),
}

/**
 * The browsing preferences, in one place.
 *
 * Deliberately not a Compose state object: the same rules decide what a desktop pane
 * and a phone pane show, and the sorting and the path arithmetic have no business
 * being written twice. A front end may hold this and render it; it may not invent its
 * own answer for what comes first in a list.
 */
class BrowserSettings {
    var displayMode: DisplayMode = DisplayMode.DETAILED
    var sortKey: SortKey = SortKey.NAME
    var ascending: Boolean = true
    var foldersFirst: Boolean = true

    /** Row height as a percentage of the factory height, 50..250 in steps of 5. */
    var rowDensity: Int = 100

    /** Columns for the grid modes, 1..12. */
    var columns: Int = 4

    var arrangement: ArrangementDirection = ArrangementDirection.AUTO

    fun bumpDensity(delta: Int) {
        rowDensity = (rowDensity + delta).coerceIn(MIN_DENSITY, MAX_DENSITY)
    }

    fun applyColumns(value: Int) {
        columns = value.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
    }

    /**
     * Auto is a decision, not a third state to render: a container wider than it is
     * tall reads better as two columns, and one that is taller than it is wide has to
     * stack.
     */
    fun resolve(widthPx: Int, heightPx: Int): ArrangementDirection = when (arrangement) {
        ArrangementDirection.AUTO -> if (widthPx >= heightPx * 1.15) {
            ArrangementDirection.SIDE_BY_SIDE
        } else {
            ArrangementDirection.STACKED
        }

        else -> arrangement
    }

    companion object {
        const val MIN_DENSITY: Int = 50
        const val MAX_DENSITY: Int = 250
        const val DENSITY_STEP: Int = 5
        const val MIN_COLUMNS: Int = 1
        const val MAX_COLUMNS: Int = 12
    }
}

/** One part of a breadcrumb. */
data class Crumb(val label: String, val path: String, val collapsed: Boolean = false)

/**
 * Pure browsing arithmetic: order, paths, breadcrumbs.
 *
 * Everything here is a function of its arguments, which is what makes it testable as
 * a table and what stops the two front ends from disagreeing about whether "size,
 * descending" puts folders at the top.
 */
object Browsing {

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
    fun crumbs(path: String, maxSegments: Int = 5): List<Crumb> {
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
            add(Crumb("...", under(trailing.take(hidden)), collapsed = true))
            for (index in hidden until trailing.size) {
                add(Crumb(trailing[index], under(trailing.take(index + 1))))
            }
        }
    }

    /** The file name that would not collide with what is already there. */
    fun uniqueName(taken: Set<String>, candidate: String): String = PathTools.uniqueName(taken, candidate)
}
