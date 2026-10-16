package io.github.cubsync.service

import io.github.cubsync.core.spi.FileMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class Side {
    LEFT,
    RIGHT,
    ;

    val other: Side get() = if (this == LEFT) RIGHT else LEFT
}

/** One pane of the two-pane browser, as it is at this moment. */
data class PaneState(
    val backendId: String = EndpointId.LOCAL,
    val path: String = "",
    val entries: List<FileMeta> = emptyList(),
    val selection: Set<String> = emptySet(),
    val loading: Boolean = false,
    val error: String = "",
) {
    val title: String get() = PathTools.leafName(path).ifEmpty { path }

    fun isSelected(name: String): Boolean = name in selection

    /** What the ⋮ menu's actions act on: the marked rows, or the whole folder. */
    fun targetNames(): List<String> = if (selection.isEmpty()) entries.map { it.relPath } else selection.toList()
}

/**
 * The two-pane browser: what is open, what is selected, and what happens when you press
 * the arrow in the middle.
 *
 * State and behaviour live here rather than in a screen, in flows rather than in Compose
 * state, so that a desktop pane and a phone pane are not merely similar but the same
 * thing. The screens draw it; they do not decide what opening a folder means, what a
 * copy does when one side cannot be reached, or which of the three 作用范围 layers a
 * change was written to.
 */
class BrowserSession(
    private val browser: FolderBrowser,
    private val scope: CoroutineScope,
    val settings: AppSettings = AppSettings(),
    private val log: AppLog = AppLog(),
) {

    private val _left = MutableStateFlow(PaneState())
    private val _right = MutableStateFlow(PaneState())
    private val _busy = MutableStateFlow(false)

    val left: StateFlow<PaneState> = _left.asStateFlow()
    val right: StateFlow<PaneState> = _right.asStateFlow()

    /** True while a copy is in flight, so a screen can refuse a second one. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun pane(side: Side): StateFlow<PaneState> = if (side == Side.LEFT) _left else _right

    fun current(side: Side): PaneState = pane(side).value

    private fun set(side: Side, state: PaneState) {
        if (side == Side.LEFT) _left.value = state else _right.value = state
    }

    // ----------------------------------------------------------------- listing

    /** Points a pane at a folder and lists it. */
    fun open(side: Side, backendId: String = current(side).backendId, path: String) {
        val before = current(side)
        set(
            side,
            before.copy(
                backendId = backendId,
                path = path,
                loading = true,
                error = "",
                selection = emptySet(),
            ),
        )
        scope.launch {
            try {
                val entries = browser.list(backendId, path)
                // An empty path lists the exposed roots, which is the answer to "where
                // can this pane go back to" and is worth remembering.
                if (path.isBlank()) rootsCache[backendId] = entries.map { it.relPath }
                set(side, current(side).copy(entries = sort(side, entries), loading = false))
            } catch (failure: Throwable) {
                val message = failure.message ?: failure::class.simpleName ?: "无法读取这个文件夹"
                log.warn("browse", "cannot list $path on $backendId: $message")
                set(side, current(side).copy(entries = emptyList(), loading = false, error = message))
            }
        }
    }

    private fun sort(side: Side, entries: List<FileMeta>): List<FileMeta> =
        Browsing.sort(entries, prefsOf(side))

    fun enter(side: Side, name: String) =
        open(side, current(side).backendId, Browsing.child(current(side).path, name))

    fun up(side: Side) {
        val parent = Browsing.parent(current(side).path)
        if (parent != current(side).path) open(side, current(side).backendId, parent)
    }

    /**
     * Long-pressing ← goes to this pane's root.
     *
     * "The root" means the folder the backend exposes, not the drive letter: a server
     * that shares `D:/photos` cannot be asked for `D:/`, and offering it would produce a
     * refusal that looks like a bug.
     */
    fun toRoot(side: Side) {
        val state = current(side)
        scope.launch {
            val roots = rootsOf(state.backendId)
            val target = roots.filter { state.path.startsWith(it) }.maxByOrNull { it.length }
                ?: roots.firstOrNull()
                ?: PathTools.rootOf(state.path)
            open(side, state.backendId, target)
        }
    }

    /** The folders a backend exposes. Listed once and remembered; they do not move. */
    private val rootsCache = mutableMapOf<String, List<String>>()

    private suspend fun rootsOf(backendId: String): List<String> =
        rootsCache[backendId] ?: runCatching {
            browser.list(backendId, "").map { it.relPath }
        }.getOrDefault(emptyList()).also { rootsCache[backendId] = it }

    fun exposedRoots(backendId: String): List<String> = rootsCache[backendId].orEmpty()

    /** Re-lists a pane, keeping the part of the selection that is still there. */
    fun refresh(side: Side) {
        val state = current(side)
        val stillThere = state.selection.intersect(state.entries.map { it.relPath }.toSet())
        open(side, state.backendId, state.path)
        set(side, current(side).copy(selection = stillThere))
    }

    fun refreshBoth() {
        refresh(Side.LEFT)
        refresh(Side.RIGHT)
    }

    /**
     * Where a typed address should actually land.
     *
     * Asking the backend whether the path is a folder is the only honest way to answer
     * "did they mean this folder or the folder this file is in", so it costs a round
     * trip and is worth it.
     */
    fun openTyped(side: Side, typed: String) {
        val backendId = current(side).backendId
        scope.launch {
            val cleaned = PathTools.normalizeRoot(typed.trim())
            if (cleaned.isEmpty()) {
                open(side, backendId, cleaned)
                return@launch
            }
            val asFolder = browser.isFolder(backendId, cleaned)
            val target = Browsing.resolveTypedPath(cleaned) { asFolder }
            open(side, backendId, target)
        }
    }

    // ----------------------------------------------------------------- selection

    fun toggle(side: Side, name: String) {
        val state = current(side)
        val selection = if (state.isSelected(name)) state.selection - name else state.selection + name
        set(side, state.copy(selection = selection))
    }

    fun select(side: Side, names: Collection<String>) {
        val state = current(side)
        set(side, state.copy(selection = state.selection + names))
    }

    fun selectAll(side: Side) =
        set(side, current(side).copy(selection = current(side).entries.map { it.relPath }.toSet()))

    fun clearSelection(side: Side) = set(side, current(side).copy(selection = emptySet()))

    fun clearAllSelections() {
        clearSelection(Side.LEFT)
        clearSelection(Side.RIGHT)
    }

    /** How many rows are marked across both panes — the count the sub-row shows. */
    val selectedCount: Int
        get() = current(Side.LEFT).selection.size + current(Side.RIGHT).selection.size

    // ----------------------------------------------------------------- preferences

    /**
     * What this pane should use, by the design's precedence.
     *
     * Read from the settings object rather than cached here, so that a change written at
     * 两栏共用 is visible to the other pane the moment it is written.
     */
    fun prefsOf(side: Side): BrowsePrefs = settings.prefsFor(side, current(side).path)

    /**
     * Writes a preference change at the scope the user picked, then re-sorts both panes.
     *
     * Both, not the one that was open: a 两栏共用 change that only refreshes the pane
     * under the menu is exactly the "it only changed one side" bug the design warns
     * about.
     */
    fun applyPrefs(side: Side, scope: Scope, prefs: BrowsePrefs) {
        settings.apply(side, current(side).path, scope, prefs)
        resort()
    }

    /** Re-sorts both panes after a preference changed, without re-reading anything. */
    fun resort() {
        for (side in Side.entries) {
            val state = current(side)
            set(side, state.copy(entries = Browsing.sort(state.entries, prefsOf(side))))
        }
    }

    /**
     * The display mode, applied to the pane the display bar is aimed at.
     *
     * Not scope-aware on purpose: 显示方式 is a property of a pane, decided by which one
     * was touched last, and asking a user to pick a scope for it as well would be one
     * question too many.
     */
    fun setDisplayMode(side: Side, mode: DisplayMode) {
        val prefs = prefsOf(side)
        settings.apply(side, current(side).path, Scope.CURRENT_FOLDER, prefs.copy(displayMode = mode))
        settings.activeSide = side
    }

    // ----------------------------------------------------------------- editing

    /**
     * Creates a folder in a pane.
     *
     * [onFinished] carries the reason when it fails. A folder that does not appear with
     * no explanation is the worst version of this: the button was pressed, nothing
     * happened, and the reason is only in a log the person has not opened.
     */
    fun makeFolder(side: Side, name: String = Words.NEW_FOLDER, onFinished: (String?) -> Unit = {}) {
        val state = current(side)
        scope.launch {
            runCatching { browser.makeDirectory(state.backendId, state.path, name) }
                .onSuccess { created ->
                    log.info("browse", "created $created")
                    refresh(side)
                    onFinished(null)
                }
                .onFailure { failure ->
                    val message = failure.message ?: "新建文件夹失败"
                    log.warn("browse", "cannot create a folder in ${state.path}: $message")
                    onFinished(message)
                }
        }
    }

    fun renameEntry(side: Side, from: String, to: String, onFinished: (String?) -> Unit = {}) {
        val state = current(side)
        scope.launch {
            runCatching { browser.rename(state.backendId, state.path, from, to) }
                .onSuccess {
                    refresh(side)
                    onFinished(null)
                }
                .onFailure { failure ->
                    val message = failure.message ?: "重命名失败"
                    log.warn("browse", "cannot rename $from: $message")
                    onFinished(message)
                }
        }
    }

    /**
     * Deletes named entries. Rows that were not named are left alone, and the deletion
     * goes to the trash unless the caller says otherwise.
     */
    fun delete(side: Side, names: List<String>, toTrash: Boolean = true, onFinished: (Int, List<String>) -> Unit = { _, _ -> }) {
        if (names.isEmpty()) return
        val state = current(side)
        scope.launch {
            val failures = browser.delete(state.backendId, state.path, names, toTrash)
            refresh(side)
            onFinished(names.size - failures.size, failures)
        }
    }

    /** Metadata for one entry, for the detail sheet. */
    suspend fun detail(side: Side, name: String): FileMeta? =
        browser.stat(current(side).backendId, current(side).path, name)

    // ----------------------------------------------------------------- compare

    /** The two listings aligned by name, for the compare mode. */
    fun compare(): List<CompareRow> = Browsing.compare(current(Side.LEFT).entries, current(Side.RIGHT).entries)

    // ----------------------------------------------------------------- transfer

    /**
     * Copies the selection of [from] into the other pane, which is where the arrow in the
     * middle points.
     *
     * A pane with nothing selected transfers everything visible, because that is what
     * pressing the arrow means when no row is marked.
     */
    fun transfer(from: Side, onFinished: (TransferOutcome) -> Unit = {}) {
        if (_busy.value) {
            onFinished(TransferOutcome(failures = listOf("已经有一个传送在进行中")))
            return
        }
        val source = current(from)
        val target = current(from.other)
        if (target.path.isBlank()) {
            onFinished(TransferOutcome(failures = listOf("对面还没有打开文件夹")))
            return
        }
        val names = if (source.selection.isEmpty()) {
            source.entries.map { it.relPath }
        } else {
            source.selection.toList()
        }
        if (names.isEmpty()) {
            onFinished(TransferOutcome(failures = listOf(Words.EMPTY_FOLDER)))
            return
        }

        _busy.value = true
        scope.launch {
            val outcome = try {
                browser.copy(
                    fromBackendId = source.backendId,
                    fromDirectory = source.path,
                    toBackendId = target.backendId,
                    toDirectory = target.path,
                    names = names,
                )
            } catch (failure: Throwable) {
                TransferOutcome(failures = listOf(failure.message ?: "传送失败"))
            } finally {
                _busy.value = false
            }
            // The target changed on disk, so it is re-listed rather than guessed at.
            if (outcome.copied > 0) refresh(from.other)
            onFinished(outcome)
        }
    }

    /** Where a pane can start: the folders this machine shares, plus the exposed ones. */
    suspend fun startingPoints(): List<String> = runCatching {
        browser.list(EndpointId.LOCAL, "").map { it.relPath }
    }.getOrDefault(emptyList())
}
