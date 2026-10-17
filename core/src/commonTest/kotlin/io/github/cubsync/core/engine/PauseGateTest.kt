package io.github.cubsync.core.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 暂停 is a gate, not a teardown.
 *
 * The property worth pinning down is that a paused copy is *suspended*: nothing has been
 * thrown away, and 继续 carries on from the same block. A gate that simply stopped being
 * asked would pass a test that only checked "it stopped".
 */
class PauseGateTest {

    @Test
    fun a_paused_gate_parks_the_copy_until_it_is_resumed() = runBlocking {
        val gate = PauseGate()
        var went = false

        gate.pause()
        val job = launch(Dispatchers.Default) {
            gate.awaitGo()
            went = true
        }

        delay(80)
        assertFalse(went, "a paused gate must not let the copy through")
        assertTrue(gate.isPaused)

        gate.resume()
        job.join()
        assertTrue(went, "resuming has to let the same copy continue")
        assertFalse(gate.isPaused)
    }

    @Test
    fun an_open_gate_does_not_delay_anything() = runBlocking {
        val gate = PauseGate()
        gate.awaitGo()
        assertTrue(true)
    }

    @Test
    fun cancelling_a_gate_stops_the_copy() = runBlocking {
        val gate = PauseGate()
        gate.cancel()
        var stopped = false
        try {
            gate.awaitGo()
        } catch (expected: RunCancelled) {
            stopped = true
        }
        assertTrue(stopped, "a cancelled gate has to throw rather than park forever")
    }
}
