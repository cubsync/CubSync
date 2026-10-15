package io.github.cubsync.cli

import io.github.cubsync.core.model.CompareMode
import io.github.cubsync.core.model.ConflictPolicy
import io.github.cubsync.core.model.DeletePolicy
import io.github.cubsync.core.model.Endpoint
import io.github.cubsync.core.model.FilterSet
import io.github.cubsync.core.model.FolderPair
import io.github.cubsync.core.model.SyncConfig
import io.github.cubsync.core.model.SyncDirection
import io.github.cubsync.service.EndpointId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConfigOutputTest {

    private fun config(id: String = "cfg-1", name: String = "photos") = SyncConfig(
        configId = id,
        name = name,
        direction = SyncDirection.TWO_WAY,
        compare = CompareMode.CONTENT_HASH,
        conflict = ConflictPolicy.KEEP_BOTH,
        delete = DeletePolicy.RECYCLE,
        parallelism = 3,
        globalFilter = FilterSet(globs = listOf("*.tmp")),
        pairs = listOf(
            FolderPair(
                pairId = "pair-1",
                order = 0,
                name = "phone",
                left = Endpoint(EndpointId.LOCAL, "/home/me/photos"),
                right = Endpoint("lan:192.168.1.5:43817", "/sdcard/DCIM"),
                filterOverride = FilterSet(enabled = false),
                directionOverride = SyncDirection.UPDATE_LEFT_TO_RIGHT,
            ),
        ),
    )

    @Test
    fun a_summary_shows_the_overrides_that_change_behaviour() {
        val text = ConfigOutput.summary(config(), json = false)
        assertTrue(text.contains("photos"))
        assertTrue(text.contains("content_hash"))
        assertTrue(text.contains("keep_both"))
        assertTrue(text.contains("filter off"), "a per-pair filter switch has to be visible: $text")
        assertTrue(text.contains("update_left_to_right"))
        text.lineSequence().forEach { assertFalse(it.endsWith(" "), "trailing space: '$it'") }
    }

    @Test
    fun the_json_summary_is_parseable_enough_to_be_useful() {
        val json = ConfigOutput.summary(config(), json = true)
        assertTrue(json.startsWith("{") && json.endsWith("}"))
        assertTrue(json.contains("\"pairs\":["))
        assertTrue(json.contains("\"filterOverride\":\"off\""))
        assertTrue(json.contains("\"directionOverride\":\"update_left_to_right\""))
        assertTrue(json.contains("\"leftBackend\":\"local\""))
        // A name with a quote in it must not break the document.
        val awkward = config().copy(name = "the \"good\" one", pairs = emptyList())
        assertTrue(ConfigOutput.summary(awkward, json = true).contains("the \\\"good\\\" one"))
    }

    @Test
    fun the_listing_pads_columns_without_leaving_trailing_spaces() {
        val text = ConfigOutput.list(listOf(config()), json = false)
        assertTrue(text.contains("CONFIG ID"))
        assertTrue(text.contains("photos"))
        text.lineSequence().forEach { assertFalse(it.endsWith(" "), "trailing space: '$it'") }
        assertNotNull(ConfigOutput.list(emptyList(), json = true))
        assertEquals("[]", ConfigOutput.list(emptyList(), json = true))
    }

    @Test
    fun the_usage_text_lists_every_command() {
        val usage = Output.usage()
        for (command in listOf("serve", "ls", "push", "pull", "compare", "run", "config")) {
            assertTrue(usage.contains(command), "usage does not mention $command")
        }
    }

    @Test
    fun the_version_line_reports_the_build_and_the_protocol() {
        val version = Output.version()
        assertTrue(version.contains(io.github.cubsync.service.BuildInfo.VERSION))
        assertTrue(version.contains("protocol ${io.github.cubsync.core.protocol.Protocol.VERSION}"))
    }
}
