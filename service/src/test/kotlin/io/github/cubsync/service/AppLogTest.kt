package io.github.cubsync.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppLogTest {

    @Test
    fun entries_are_bounded_and_the_evictions_are_counted() {
        val log = AppLog(limit = 5)
        repeat(20) { log.info("area", "entry $it") }
        val entries = log.entries()
        assertEquals(5, entries.size)
        assertEquals("entry 15", entries.first().message)
        assertEquals("entry 19", entries.last().message)
        assertEquals(15, log.droppedCount)
    }

    @Test
    fun a_sequence_number_never_repeats_after_a_clear() {
        val log = AppLog(limit = 5)
        log.info("area", "first")
        val before = log.entries().single().sequence
        log.clear()
        log.info("area", "second")
        assertTrue(log.entries().single().sequence > before)
    }

    @Test
    fun filtering_by_level_keeps_the_quiet_entries_out_of_sight() {
        val log = AppLog()
        log.debug("a", "noise")
        log.info("a", "routine")
        log.warn("a", "careful")
        log.error("a", "broken")
        assertEquals(4, log.entries().size)
        assertEquals(listOf(LogLevel.WARN, LogLevel.ERROR), log.entries(LogLevel.WARN).map { it.level })
    }

    @Test
    fun a_listener_sees_every_entry_until_it_unsubscribes() {
        val log = AppLog()
        val seen = ArrayList<String>()
        val subscription = log.subscribe { seen.add(it.message) }
        log.info("a", "one")
        log.info("a", "two")
        subscription.close()
        log.info("a", "three")
        assertEquals(listOf("one", "two"), seen)
    }

    @Test
    fun a_listener_that_throws_does_not_break_the_caller() {
        val log = AppLog()
        log.subscribe { throw IllegalStateException("boom") }
        val seen = ArrayList<String>()
        log.subscribe { seen.add(it.message) }
        log.info("a", "survived")
        assertEquals(listOf("survived"), seen)
    }

    @Test
    fun concurrent_writers_get_unique_sequence_numbers() {
        val log = AppLog(limit = 5_000)
        val workers = (0 until 8).map { worker ->
            Thread { repeat(100) { log.info("w$worker", "line") } }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        val sequences = log.entries().map { it.sequence }
        assertEquals(800, sequences.size)
        assertEquals(800, sequences.toSet().size)
    }
}
