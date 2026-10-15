package io.github.cubsync.service

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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
        assertEquals(FilterSet(enabled = false), loaded.pairs.single().filterOverride)
        assertEquals(SyncDirection.UPDATE_LEFT_TO_RIGHT, loaded.pairs.single().directionOverride)
    }

    @Test
    fun listing_sorts_by_order_then_name() {
        val store = store()
        store.save(config("cfg-b", "zeta"))
        store.save(config("cfg-a", "alpha"))
        store.save(config("cfg-c", "pinned").copy(sortOrder = -1))
        assertEquals(listOf("pinned", "alpha", "zeta"), store.list().map { it.name })
    }

    @Test
    fun a_missing_directory_is_an_empty_listing_rather_than_an_error() {
        assertEquals(emptyList(), ConfigStore(store().configDirectory.resolve("nope")).list())
    }

    @Test
    fun a_broken_file_does_not_hide_the_others() {
        val store = store()
        store.save(config("cfg-good", "good"))
        Files.writeString(store.configDirectory.resolve("cfg-broken.json"), "{ this is not json")
        assertEquals(listOf("good"), store.list().map { it.name })
    }

    @Test
    fun deleting_a_configuration_removes_its_file() {
        val store = store()
        store.save(config())
        assertTrue(store.exists("cfg-1"))
        assertTrue(store.delete("cfg-1"))
        assertFalse(store.exists("cfg-1"))
        assertFalse(store.delete("cfg-1"))
    }

    @Test
    fun a_configuration_id_cannot_escape_the_config_directory() {
        val store = store()
        val path = store.pathFor("../../etc/passwd")
        assertEquals(store.configDirectory, path.parent)
        assertFalse(path.fileName.toString().contains(".."))
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
        assertTrue(text.contains(BuildInfo.VERSION))

        val imported = store.load(destination)
        assertEquals(config(), imported)
    }

    @Test
    fun an_unknown_reference_is_reported_by_name() {
        val failure = assertFailsWith<NoSuchElementException> { store().resolve("nope") }
        assertTrue(failure.message!!.contains("nope"))
    }

    @Test
    fun a_reference_may_be_a_path_instead_of_an_id() {
        val store = store()
        val saved = store.save(config("cfg-path", "by path"))
        assertEquals("by path", store.resolve(saved.toString()).name)
    }

    @Test
    fun a_snapshot_directory_is_created_where_the_configurations_live() {
        val store = store()
        store.snapshotStore()
        assertTrue(Files.isDirectory(store.snapshotDirectory))
    }

    @Test
    fun the_default_state_directory_is_under_the_user_home() {
        val base = ConfigStore.defaultBase().toString()
        assertTrue(base.endsWith(".cubsync"), base)
    }
}
