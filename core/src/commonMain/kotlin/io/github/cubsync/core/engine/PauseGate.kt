package io.github.cubsync.core.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * 暂停, as a gate between blocks.
 *
 * Cancelling the coroutine would abort the write and lose the block in hand; what a
 * person pressing 暂停 expects is that the bytes stop and start again where they stopped.
 * So a run asks the gate before each block, and the gate parks the coroutine until it is
 * told otherwise — the run is suspended, not torn down, and nothing has to be re-derived
 * when it continues.
 *
 * Shared by both kinds of copy: a 同步 job and a two-pane transfer have the same three
 * verbs under them, and two implementations of "paused" is two chances to disagree.
 */
class PauseGate {

    private val paused = MutableStateFlow(false)

    @Volatile
    var cancelled: Boolean = false
        private set

    val isPaused: Boolean get() = paused.value

    fun pause() {
        paused.value = true
    }

    fun resume() {
        paused.value = false
    }

    fun cancel() {
        cancelled = true
        paused.value = false
    }

    /** Parks while paused, and throws once the run has been cancelled. */
    suspend fun awaitGo() {
        if (cancelled) throw RunCancelled()
        if (paused.value) paused.first { !it }
        if (cancelled) throw RunCancelled()
    }
}

/** Thrown out of a copy loop when the gate was told to stop. */
class RunCancelled : Exception("已取消")
