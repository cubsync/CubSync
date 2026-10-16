package io.github.cubsync.service

import io.github.cubsync.core.spi.FileMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class Side { LEFT, RIGHT }

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
}

/**
 * The two-pane browser: what is open, what is selected, and what happens when you
 * press the arrow in the middle.
 *
 * State and behaviour live here rather than in a screen, in flows rather than in
 * Compose state, so that a desktop pane and a phone pane are not merely similar but
 * the same thing. The screens draw it; they do not decide what opening a folder means
 * or what a copy does when one side cannot be reached.
 */
class BrowserSession(
    private val browser: FolderBrowser,
    private val scope: CoroutineScope,
    val settings: BrowserSettings = BrowserSettings(),
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

    private fun set(side: Side, state: PaneState) {
        if (side == Side.LEFT) _left.value = state else _right.value = state
    }

    fun current(side: Side): PaneState = pane(side).value

    /** Points a pane at a folder and lists it. */
    fun open(side: Side, backendId: String = current(side).backendId, path: String) {
        val before = current(side)
        set(side, before.copy(backendId = backendId, path = path, loading = true, error = "", selection = emptySet()))
        scope.launch {
            try {
                val entries = browser.list(backendId, path)
                set(side, current(side).copy(entries = Browsing.sort(entries, settings.sortKey, settings.ascending, settings.foldersFirst), loading = false))
            } catch (failure: Throwable) {
                val message = failure.message ?: failure::class.simpleName ?: "无法读取这个文件夹"
                log.warn("browse", "cannot list $path on $backendId: $message")
                set(side, current(side).copy(entries = emptyList(), loading = false, error = message))
            }
        }
    }

    fun enter(side: Side, name: String) = open(side, current(side).backendId, Browsing.child(current(side).path, name))

    fun up(side: Side) {
        val parent = Browsing.parent(current(side).path)
        if (parent != current(side).path) open(side, current(side).backendId, parent)
    }

    /** Re-lists a pane, keeping the selection that is still there. */
    fun refresh(side: Side) {
        val state = current(side)
        val stillThere = state.selection.intersect(state.entries.map { it.relPath }.toSet())
        open(side, state.backendId, state.path)
        set(side, current(side).copy(selection = stillThere))
    }

    fun toggle(side: Side, name: String) {
        val state = current(side)
        val selection = if (state.isSelected(name)) state.selection - name else state.selection + name
        set(side, state.copy(selection = selection))
    }

    fun selectAll(side: Side) = set(side, current(side).copy(selection = current(side).entries.map { it.relPath }.toSet()))

    fun clearSelection(side: Side) = set(side, current(side).copy(selection = emptySet()))

    /** Re-sorts both panes after a preference changed, without re-reading anything. */
    fun resort() {
        for (side in Side.entries) {
            val state = current(side)
            set(side, state.copy(entries = Browsing.sort(state.entries, settings.sortKey, settings.ascending, settings.foldersFirst)))
        }
    }

    /**
     * Copies the selection of [from] into the other pane, which is where the arrow in
     * the middle points.
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
        val target = current(if (from == Side.LEFT) Side.RIGHT else Side.LEFT)
        val names = if (source.selection.isEmpty()) {
            source.entries.map { it.relPath }
        } else {
            source.selection.toList()
        }
        if (names.isEmpty()) {
            onFinished(TransferOutcome(failures = listOf("这个文件夹是空的")))
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
            if (outcome.copied > 0) refresh(if (from == Side.LEFT) Side.RIGHT else Side.LEFT)
            onFinished(outcome)
        }
    }

    /** Where a pane can start: the folders this machine shares, plus the exposed ones. */
    suspend fun startingPoints(): List<String> = runCatching {
        val local = browser.list(EndpointId.LOCAL, "")
        local.map { it.relPath }
    }.getOrDefault(emptyList())
}
