package io.github.cubsync.service

enum class NoticeKind { INFO, SUCCESS, WARNING, ERROR }

data class Notice(val text: String, val kind: NoticeKind, val atMs: Long = System.currentTimeMillis())

/** Which layer of the sync section is open. The desktop puts them side by side. */
enum class SyncLayer { LIST, DETAIL, PREVIEW }

/** The two halves of the jobs section. On the desktop the log also has its own entry. */
enum class JobsSegment(val label: String) { JOBS("作业"), LOGS("日志") }

/** A run that is in flight, as the window needs to show it. */
data class RunningJob(
    val jobId: String,
    val configName: String,
    val dryRun: Boolean,
    val pairCount: Int,
    val startedAtMs: Long,
    val finishedPairs: Int = 0,
    val currentPair: String = "",
    val bytes: Long = 0,
    /** The pairs' display names, in order. The phone lists them; the desktop only needs the count. */
    val pairNames: List<String> = emptyList(),
) {
    val label: String get() = if (dryRun) "试运行" else "同步中"

    /** Whole pairs finished, which is the only progress the engine reports. */
    val percent: Int get() = if (pairCount <= 0) 0 else finishedPairs * 100 / pairCount

    /** The same progress as a fraction in 0..1, for progress bars. */
    val fraction: Float get() = if (pairCount <= 0) 0f else finishedPairs.toFloat() / pairCount
}

/** Which pane's ⋮ menu is open, if any. */
data class PaneMenu(val side: Side)

/** The long-press action panel for one row. */
data class ActionSheet(val side: Side, val name: String, val isDirectory: Boolean)

/** The four favourite dialogs: add, new group, edit one, and the drawer settings. */
sealed interface FavDialog {
    data object AddPath : FavDialog
    data object NewGroup : FavDialog
    data class Edit(val favourite: Favorite) : FavDialog
    data object Settings : FavDialog
}
