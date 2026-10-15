package io.github.cubsync.cli

import io.github.cubsync.core.model.CompareMode
import io.github.cubsync.core.model.ConflictPolicy
import io.github.cubsync.core.model.DeletePolicy
import io.github.cubsync.core.model.Endpoint
import io.github.cubsync.core.model.FilterSet
import io.github.cubsync.core.model.FolderPair
import io.github.cubsync.core.model.SyncConfig
import io.github.cubsync.core.model.SyncDirection
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConfigStoreTest {

    private val temporaries = mutableListOf<Path>()

    private fun store(): ConfigStore {
        val dir = Files.createTempDirectory("cubsync-config-")
        temporaries.add(dir)
        return ConfigStore(dir)
    }

    @AfterTest
    fun cleanUp() {
        for (dir in temporaries) {
            runCatching {
                Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
        temporaries.clear()
    }

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
    fun a_saved_configuration_survives_a_round_trip() {
        val store = store()
        val saved = store.save(config())
        assertTrue(Files.isRegularFile(saved))

        val loaded = store.resolve("cfg-1")
        assertEquals(config(), loaded)
        assertEquals(1, loaded.pairs.size)
        assertEquals(FilterSet(enabled = false), loaded.pairs.single().filterOverride)
        assertEquals(SyncDirection.UPDATE_LEFT_TO_RIGHT, loaded.pairs.single().directionOverride)
    }

    @Test
    fun listing_finds_every_saved_configuration_sorted_by_name() {
        val store = store()
        store.save(config("cfg-b", "zeta"))
        store.save(config("cfg-a", "alpha"))
        assertEquals(listOf("alpha", "zeta"), store.list().map { it.name })
        assertEquals(emptyList(), ConfigStore(store.configDirectory.resolve("nope")).list())
    }

    @Test
    fun a_broken_file_does_not_hide_the_others() {
        val store = store()
        store.save(config("cfg-good", "good"))
        Files.writeString(store.configDirectory.resolve("cfg-broken.json"), "{ this is not json")
        assertEquals(listOf("good"), store.list().map { it.name })
    }

    @Test
    fun an_export_can_be_read_back_as_an_import() {
        val store = store()
        val destination = Files.createTempDirectory("cubsync-export-").also { temporaries.add(it) }
            .resolve("photos.cubcfg")

        store.export(config(), destination)
        val text = Files.readString(destination)
        assertTrue(text.contains("cubsync-config/1"))
        assertTrue(text.contains("exportedAt"))

        val imported = store.load(destination)
        assertEquals(config(), imported)
    }

    @Test
    fun an_unknown_reference_is_reported_by_name() {
        val store = store()
        val failure = assertFailsWith<CommandFailedException> { store.resolve("nope") }
        assertTrue(failure.message!!.contains("nope"))
    }

    @Test
    fun a_reference_may_be_a_path_instead_of_an_id() {
        val store = store()
        val saved = store.save(config("cfg-path", "by path"))
        assertEquals("by path", store.resolve(saved.toString()).name)
    }

    @Test
    fun a_snapshot_store_is_created_where_the_configurations_live() {
        val store = store()
        assertTrue(Files.isDirectory(store.snapshotStore().let { store.snapshotDirectory }))
    }

    @Test
    fun local_endpoints_are_recognised() {
        assertTrue(EndpointId.parse("local").isLocal)
        assertTrue(EndpointId.parse("LOCAL".lowercase()).isLocal)
        assertFalse(EndpointId.parse("nas.local:43817").isLocal)
    }

    @Test
    fun a_peer_endpoint_carries_the_host_and_port() {
        val peer = EndpointId.parse("nas.local:43817")
        assertEquals("nas.local", peer.host)
        assertEquals(43817, peer.port)
        assertEquals("lan:nas.local:43817", peer.backendId)

        val explicit = EndpointId.parse("lan:nas.local:43817")
        assertEquals(peer, explicit)
        assertEquals("192.168.1.5", EndpointId.peer("192.168.1.5", 1).host)
    }

    @Test
    fun a_malformed_endpoint_is_refused_rather_than_guessed() {
        assertFailsWith<UsageException> { EndpointId.parse("") }
        assertFailsWith<UsageException> { EndpointId.parse("nas.local") }
        assertFailsWith<UsageException> { EndpointId.parse("nas.local:not-a-port") }
        assertFailsWith<UsageException> { EndpointId.parse("nas.local:70000") }
        assertFailsWith<UsageException> { EndpointId.parse(":43817") }
    }

    @Test
    fun the_default_state_directory_is_under_the_user_home() {
        val base = ConfigStore.defaultBase().toString()
        assertTrue(base.endsWith(".cubsync"), base)
        assertTrue(base.contains(System.getProperty("user.name")) || base.contains("home", ignoreCase = true))
    }

    @Test
    fun a_configuration_summary_shows_the_overrides_that_change_behaviour() {
        val text = ConfigOutput.summary(config(), json = false)
        assertTrue(text.contains("photos"))
        assertTrue(text.contains("content_hash"))
        assertTrue(text.contains("keep_both"))
        assertTrue(text.contains("filter off"), "a per-pair filter switch has to be visible: $text")
        assertTrue(text.contains("direction update_left_to_right"))
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
}
