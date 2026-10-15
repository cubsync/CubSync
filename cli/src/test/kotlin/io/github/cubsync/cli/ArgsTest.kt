package io.github.cubsync.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArgsTest {

    @Test
    fun long_options_take_a_value_from_the_next_token() {
        val args = Args.parse(listOf("--root", "/data", "--port", "4390"))
        assertEquals("/data", args.string("root"))
        assertEquals(4390, args.int("port", 0))
    }

    @Test
    fun equals_form_works_too() {
        val args = Args.parse(listOf("--to=127.0.0.1:4390", "--parallel=4"))
        assertEquals("127.0.0.1:4390", args.string("to"))
        assertEquals(4, args.int("parallel", 1))
    }

    @Test
    fun a_flag_does_not_swallow_the_next_flag() {
        // --dry-run has no value, so the following token must not become one.
        val args = Args.parse(listOf("--dry-run", "--json"))
        assertTrue(args.flag("dry-run"))
        assertTrue(args.flag("json"))
        assertEquals(null, args.string("dry-run"))
    }

    @Test
    fun repeated_options_are_collected() {
        val args = Args.parse(listOf("--root", "a", "--root", "b", "--filter", "*.tmp", "--filter", "cache/**"))
        assertEquals(listOf("a", "b"), args.stringList("root"))
        assertEquals(listOf("*.tmp", "cache/**"), args.stringList("filter"))
    }

    @Test
    fun positionals_are_kept_separate() {
        val args = Args.parse(listOf("host:4390", "/data", "--json"))
        assertEquals(listOf("host:4390", "/data"), args.positional)
        assertTrue(args.flag("json"))
    }

    @Test
    fun a_missing_required_option_is_reported() {
        val args = Args.parse(emptyList())
        assertFailsWith<UsageException> { args.requireString("root") }
        assertEquals("fallback", args.string("root", "fallback"))
    }

    @Test
    fun an_unknown_command_returns_a_usage_error() {
        assertEquals(EXIT_USAGE, run(listOf("frobnicate")))
    }

    @Test
    fun help_and_version_succeed_without_a_command() {
        assertEquals(0, run(listOf("--help")))
        assertEquals(0, run(listOf("--version")))
    }

    @Test
    fun numbers_are_parsed_with_a_fallback() {
        val args = Args.parse(listOf("--port", "not-a-number"))
        assertEquals(4390, args.int("port", 4390))
        assertFalse(args.flag("missing"))
    }

    @Test
    fun byte_sizes_read_the_way_a_person_would_say_them() {
        assertEquals("512 B", Output.humanBytes(512))
        assertEquals("1.0 KiB", Output.humanBytes(1024))
        assertEquals("1.5 MiB", Output.humanBytes(1024 * 1024 * 3 / 2))
        assertEquals("1.0 GiB", Output.humanBytes(1024L * 1024 * 1024))
    }

    @Test
    fun the_summary_has_one_line_per_pair_and_a_total() {
        val result = io.github.cubsync.core.engine.JobResult(
            jobId = "job-1",
            configId = "cfg",
            status = io.github.cubsync.core.engine.JobStatus.COMPLETED,
            startedAtMs = 0,
            finishedAtMs = 1_500,
            pairs = listOf(
                io.github.cubsync.core.engine.PairOutcome(
                    pairId = "p1",
                    pairName = "photos",
                    status = io.github.cubsync.core.engine.PairStatus.COMPLETED,
                    items = emptyList(),
                    stats = io.github.cubsync.core.engine.TransferStats(copiedFiles = 2, copiedBytes = 2048),
                ),
            ),
        )
        val text = Output.jobSummary(result, json = false)
        assertTrue(text.contains("photos"))
        assertTrue(text.contains("completed"))
        assertTrue(text.contains("2.0 KiB"))
        assertTrue(text.contains("total: 2 file(s)"))
        assertEquals(0, Output.exitCodeFor(result.status))
        text.lineSequence().forEach { assertFalse(it.endsWith(" "), "trailing space in line: '$it'") }
    }
}
