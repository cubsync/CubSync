package io.github.cubsync.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncConfigCodecTest {

    private fun sampleConfig(): SyncConfig = SyncConfig(
        configId = "cfg-1",
        name = "Phone backup",
        favorite = true,
        sortOrder = 3,
        direction = SyncDirection.MIRROR_LEFT_TO_RIGHT,
        compare = CompareMode.CONTENT_HASH,
        conflict = ConflictPolicy.PREFER_LEFT,
        delete = DeletePolicy.RECYCLE,
        parallelism = 4,
        mtimeToleranceMs = 1_500,
        keepPermissions = true,
        dryRun = true,
        globalFilter = FilterSet(
            mode = FilterMode.BLACKLIST,
            globs = listOf("*.tmp", "cache/**"),
            includeHidden = false,
            minSize = 1,
            maxSize = 1L shl 30,
        ),
        pairs = listOf(
            FolderPair(
                pairId = "pair-1",
                order = 0,
                name = "photos",
                left = Endpoint("local", "/home/user/photos", "abc"),
                right = Endpoint("lan:phone", "/sdcard/DCIM", "def"),
                filterOverride = FilterSet(globs = listOf("*.raw"), includeHidden = true),
                directionOverride = SyncDirection.TWO_WAY,
            ),
            FolderPair(
                pairId = "pair-2",
                order = 1,
                enabled = false,
                left = Endpoint("local", "/home/user/docs"),
                right = Endpoint("lan:phone", "/sdcard/Documents"),
                filterOverride = null,
            ),
        ),
    )

    @Test
    fun a_configuration_round_trips_unchanged() {
        val original = sampleConfig()
        assertEquals(original, SyncConfigCodec.decode(SyncConfigCodec.encode(original)))
    }

    @Test
    fun the_three_filter_states_survive_a_round_trip() {
        val original = sampleConfig()
        val pair = original.pairs.first()
        assertEquals(FilterSet(globs = listOf("*.raw"), includeHidden = true), pair.filterOverride)

        val inherited = SyncConfigCodec.decode(SyncConfigCodec.encode(original)).pairs[1]
        assertNull(inherited.filterOverride)
        assertEquals(original.globalFilter, Filters.effective(original.globalFilter, inherited.filterOverride))

        // Disabled is an explicit state, distinct from "inherit".
        val disabled = original.copy(
            pairs = listOf(original.pairs[0].copy(pairId = "p3", filterOverride = FilterSet(enabled = false))),
        )
        val decoded = SyncConfigCodec.decode(SyncConfigCodec.encode(disabled))
        val restored = decoded.pairs.single().filterOverride
        assertEquals(false, restored?.enabled)
        assertTrue(restored!!.isEmpty)
    }

    @Test
    fun the_export_envelope_is_accepted_back() {
        val original = sampleConfig()
        val text = SyncConfigCodec.encodeExport(original, exportedAt = "2026-10-16T00:00:00Z", appVersion = "0.1.0")
        assertTrue(text.contains("cubsync-config/1"))
        assertEquals(original, SyncConfigCodec.decodeExport(text))
    }

    @Test
    fun an_unknown_field_is_rejected_on_load_but_tolerated_on_import() {
        val text = SyncConfigCodec.encode(sampleConfig())
        val tampered = text.replaceFirst("{", "{\n  \"somethingNew\": 1,")
        assertFailsWith<Exception> { SyncConfigCodec.decode(tampered) }

        val envelope = SyncConfigCodec.encodeExport(sampleConfig(), "2026-10-16T00:00:00Z", "9.9.9")
        val tamperedEnvelope = envelope.replaceFirst("{", "{\n  \"futureField\": true,")
        assertEquals(sampleConfig(), SyncConfigCodec.decodeExport(tamperedEnvelope))
    }

    @Test
    fun a_configuration_from_a_newer_schema_is_refused() {
        val text = SyncConfigCodec.encode(sampleConfig())
        val bumped = text.replace("\"schemaVersion\": 1", "\"schemaVersion\": ${SyncConfig.CURRENT_SCHEMA + 1}")
        val failure = assertFailsWith<IllegalArgumentException> { SyncConfigCodec.decode(bumped) }
        assertTrue(failure.message!!.contains("newer"))
    }

    @Test
    fun unknown_enum_values_fall_back_instead_of_failing_the_load() {
        val text = SyncConfigCodec.encode(sampleConfig())
        val tampered = text.replace("\"direction\": \"MIRROR_LEFT_TO_RIGHT\"", "\"direction\": \"SOMETHING_ELSE\"")
        assertEquals(SyncDirection.TWO_WAY, SyncConfigCodec.decode(tampered).direction)
    }

    @Test
    fun parallelism_cannot_be_zero_or_negative() {
        val text = SyncConfigCodec.encode(sampleConfig())
        val zeroed = text.replace("\"parallelism\": 4", "\"parallelism\": 0")
        assertEquals(1, SyncConfigCodec.decode(zeroed).parallelism)
    }

    @Test
    fun the_encoded_form_is_stable_and_readable() {
        val text = SyncConfigCodec.encode(sampleConfig())
        assertTrue(text.contains("\"schemaVersion\": 1"))
        assertTrue(text.contains("\"pairId\": \"pair-1\""))
        // Encoding the decoded value again must produce the same text.
        assertEquals(text, SyncConfigCodec.encode(SyncConfigCodec.decode(text)))
    }

    @Test
    fun ids_are_unique_and_prefixed() {
        val ids = List(500) { Ids.newPairId() }.toSet()
        assertEquals(500, ids.size)
        assertTrue(ids.all { it.startsWith("pair-") })
        assertTrue(Ids.newConfigId().startsWith("cfg-"))
    }

    @Test
    fun directions_report_their_own_semantics() {
        assertTrue(SyncDirection.MIRROR_LEFT_TO_RIGHT.mirror)
        assertTrue(SyncDirection.MIRROR_LEFT_TO_RIGHT.deletesAllowed)
        assertTrue(!SyncDirection.UPDATE_LEFT_TO_RIGHT.deletesAllowed)
        assertTrue(!SyncDirection.CUSTOM.supported)
        assertTrue(SyncDirection.TWO_WAY.supported)
    }
}
