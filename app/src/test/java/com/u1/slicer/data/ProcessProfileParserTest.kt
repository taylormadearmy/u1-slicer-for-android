package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessProfileParserTest {

    private val singleProfileJson = """
        {
            "type": "process",
            "name": "0.16mm Fine @Snapmaker U1",
            "inherits": "fdm_process_common",
            "from": "User",
            "instantiation": "true",
            "layer_height": "0.16",
            "initial_layer_print_height": "0.16",
            "wall_loops": "3",
            "sparse_infill_density": "20%",
            "sparse_infill_pattern": "gyroid",
            "compatible_printers": ["Snapmaker U1 (0.4 nozzle) - multiplate"]
        }
    """.trimIndent()

    @Test
    fun parse_singleObject_extractsName_and_keys() {
        val profiles = ProcessProfileParser.parse(singleProfileJson, "fallback")
        assertEquals(1, profiles.size)
        val p = profiles[0]
        assertEquals("0.16mm Fine @Snapmaker U1", p.name)
        assertEquals("0.16", p.keys["layer_height"])
        assertEquals("3", p.keys["wall_loops"])
        assertEquals("20%", p.keys["sparse_infill_density"])
        assertEquals("gyroid", p.keys["sparse_infill_pattern"])
    }

    @Test
    fun parse_dropsMetadataKeys() {
        val profiles = ProcessProfileParser.parse(singleProfileJson, "fallback")
        val keys = profiles[0].keys
        assertFalse("type should be dropped", keys.containsKey("type"))
        assertFalse("name should be dropped (it's the profile name, not a slicer key)", keys.containsKey("name"))
        assertFalse("inherits should be dropped", keys.containsKey("inherits"))
        assertFalse("from should be dropped", keys.containsKey("from"))
        assertFalse("instantiation should be dropped", keys.containsKey("instantiation"))
        assertFalse("compatible_printers should be dropped", keys.containsKey("compatible_printers"))
    }

    @Test
    fun parse_assignsFreshUuid() {
        val a = ProcessProfileParser.parse(singleProfileJson, "fallback")
        val b = ProcessProfileParser.parse(singleProfileJson, "fallback")
        assertTrue("id should be a UUID-shaped string", a[0].id.length >= 32)
        assertTrue("each parse produces a fresh id", a[0].id != b[0].id)
    }

    @Test
    fun parse_missingName_usesFallback() {
        val json = """{ "layer_height": "0.2" }"""
        val profiles = ProcessProfileParser.parse(json, "my-process")
        assertEquals(1, profiles.size)
        assertEquals("my-process", profiles[0].name)
        assertNull("sourceName is null when no name was present", profiles[0].sourceName)
    }

    @Test
    fun parse_arrayOfProfiles_returnsAll() {
        val json = """
            [
                { "type": "process", "name": "Fast", "layer_height": "0.28" },
                { "type": "process", "name": "Standard", "layer_height": "0.20" },
                { "type": "process", "name": "Fine", "layer_height": "0.16" }
            ]
        """.trimIndent()
        val profiles = ProcessProfileParser.parse(json, "fallback")
        assertEquals(3, profiles.size)
        assertEquals("Fast", profiles[0].name)
        assertEquals("Standard", profiles[1].name)
        assertEquals("Fine", profiles[2].name)
    }

    @Test
    fun parse_wrapperObject_unwraps() {
        val json = """
            {
                "process_profiles": [
                    { "name": "A", "layer_height": "0.2" },
                    { "name": "B", "layer_height": "0.16" }
                ]
            }
        """.trimIndent()
        val profiles = ProcessProfileParser.parse(json, "fallback")
        assertEquals(2, profiles.size)
    }

    @Test
    fun parse_emptyKeys_returnsNoProfile() {
        val json = """{ "type": "process", "name": "Empty" }"""
        val profiles = ProcessProfileParser.parse(json, "fallback")
        // After dropping `type` and `name`, no keys remain — not a useful profile.
        assertTrue(profiles.isEmpty())
    }

    @Test
    fun parse_arrayValuedKey_keptAsList() {
        val json = """
            {
                "name": "Multi",
                "layer_height": "0.2",
                "thumbnails": ["50x50", "100x100"]
            }
        """.trimIndent()
        val profiles = ProcessProfileParser.parse(json, "fallback")
        assertEquals(1, profiles.size)
        val thumbs = profiles[0].keys["thumbnails"]
        assertTrue(thumbs is List<*>)
        assertEquals(2, (thumbs as List<*>).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parse_garbageJson_throws() {
        ProcessProfileParser.parse("not json at all", "x")
    }

    @Test
    fun parse_dropsNilValues() {
        val json = """{ "name": "X", "layer_height": "nil", "wall_loops": "2" }"""
        val profiles = ProcessProfileParser.parse(json, "fallback")
        val keys = profiles[0].keys
        assertFalse("nil values should be dropped", keys.containsKey("layer_height"))
        assertEquals("2", keys["wall_loops"])
    }
}
