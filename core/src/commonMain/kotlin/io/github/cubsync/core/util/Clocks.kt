package io.github.cubsync.core.util

/** Wall-clock time, used for timestamps written into snapshots and exports. */
fun interface UtcClock {
    fun nowMs(): Long
}

/**
 * Monotonic time, used for heartbeats and progress.
 *
 * Kept separate from [UtcClock] on purpose: a watchdog driven by wall-clock time
 * misbehaves whenever the system clock is adjusted, which is exactly when a long
 * transfer is most likely to be running.
 */
fun interface MonoClock {
    fun nowMs(): Long
}

expect fun systemUtcClock(): UtcClock

expect fun systemMonoClock(): MonoClock
