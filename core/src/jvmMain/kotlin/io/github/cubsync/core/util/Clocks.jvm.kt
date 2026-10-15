package io.github.cubsync.core.util

private class SystemUtcClock : UtcClock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

private class SystemMonoClock : MonoClock {
    private val origin = System.nanoTime()

    override fun nowMs(): Long = (System.nanoTime() - origin) / 1_000_000
}

actual fun systemUtcClock(): UtcClock = SystemUtcClock()

actual fun systemMonoClock(): MonoClock = SystemMonoClock()
