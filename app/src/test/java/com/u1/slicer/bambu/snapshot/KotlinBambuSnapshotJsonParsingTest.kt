package com.u1.slicer.bambu.snapshot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JVM unit tests for the JSON parser helpers in [KotlinBambuSnapshot].
 *
 * The end-to-end snapshot path is covered by the instrumented tests on-device
 * ([KotlinBambuSnapshotTest], [NativePlateDataTest], [NativeObjectExtruderMapTest]).
 * These JVM tests target the decode layer directly: malformed input, missing
 * keys, wrong types, empty arrays, and happy paths. They run in seconds and
 * catch regressions in the JSON contract without needing a device or a
 * rebuilt native .so.
 */
class KotlinBambuSnapshotJsonParsingTest {

    // ---- unpackStateCounts ----

    @Test
    fun `unpackStateCounts returns empty map for empty input`() {
        assertEquals(emptyMap<Int, Int>(), KotlinBambuSnapshot.unpackStateCounts(intArrayOf()))
    }

    @Test
    fun `unpackStateCounts decodes the packed interleaved state-count pairs`() {
        val out = KotlinBambuSnapshot.unpackStateCounts(intArrayOf(1, 10, 2, 20, 7, 700))
        assertEquals(mapOf(1 to 10, 2 to 20, 7 to 700), out)
    }

    @Test
    fun `unpackStateCounts preserves native-emitted ordering (state ascending)`() {
        // Native contract: sapil::count_paint_states emits states in ascending
        // order. The unpacker must retain that insertion order so callers can
        // iterate deterministically.
        val out = KotlinBambuSnapshot.unpackStateCounts(intArrayOf(3, 30, 5, 50, 9, 90))
        assertEquals(listOf(3, 5, 9), out.keys.toList())
    }

    @Test
    fun `unpackStateCounts rejects odd-length input`() {
        try {
            KotlinBambuSnapshot.unpackStateCounts(intArrayOf(1, 10, 2))
            fail("expected IllegalArgumentException on odd-length input")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    // ---- readStringArray ----

    @Test
    fun `readStringArray returns empty when the key is absent`() {
        val obj = JSONObject("""{"other":"value"}""")
        assertEquals(emptyList<String>(), KotlinBambuSnapshot.readStringArray(obj, "missing"))
    }

    @Test
    fun `readStringArray returns empty when the key maps to a non-array value`() {
        val obj = JSONObject("""{"filamentColours":"not an array"}""")
        assertEquals(emptyList<String>(), KotlinBambuSnapshot.readStringArray(obj, "filamentColours"))
    }

    @Test
    fun `readStringArray preserves order and empty strings`() {
        val obj = JSONObject("""{"ids":["first","","third"]}""")
        assertEquals(listOf("first", "", "third"), KotlinBambuSnapshot.readStringArray(obj, "ids"))
    }

    @Test
    fun `readStringArray returns empty for an empty JSON array`() {
        val obj = JSONObject("""{"ids":[]}""")
        assertEquals(emptyList<String>(), KotlinBambuSnapshot.readStringArray(obj, "ids"))
    }

    // ---- readObjectInstanceMap ----

    @Test
    fun `readObjectInstanceMap returns empty when key is absent`() {
        val obj = JSONObject("""{"plateIndex":0}""")
        assertEquals(emptyList<ObjectInstance>(), KotlinBambuSnapshot.readObjectInstanceMap(obj))
    }

    @Test
    fun `readObjectInstanceMap parses well-formed pairs preserving order`() {
        val obj = JSONObject("""{"objectInstanceMap":[
            {"objectId":2,"instanceId":0},
            {"objectId":4,"instanceId":1}
        ]}""")
        assertEquals(
            listOf(
                ObjectInstance(objectId = 2, instanceId = 0),
                ObjectInstance(objectId = 4, instanceId = 1),
            ),
            KotlinBambuSnapshot.readObjectInstanceMap(obj)
        )
    }

    @Test
    fun `readObjectInstanceMap fills missing objectId with -1 and missing instanceId with 0`() {
        val obj = JSONObject("""{"objectInstanceMap":[
            {"instanceId":5},
            {"objectId":9}
        ]}""")
        assertEquals(
            listOf(
                ObjectInstance(objectId = -1, instanceId = 5),
                ObjectInstance(objectId = 9, instanceId = 0),
            ),
            KotlinBambuSnapshot.readObjectInstanceMap(obj)
        )
    }

    // ---- readCustomGcodeArray ----

    @Test
    fun `readCustomGcodeArray returns empty when key is absent`() {
        val obj = JSONObject("""{"plateIndex":0}""")
        assertEquals(
            emptyList<CustomGcodeEntry>(),
            KotlinBambuSnapshot.readCustomGcodeArray(obj)
        )
    }

    @Test
    fun `readCustomGcodeArray parses the native append_plate customGcode shape`() {
        val obj = JSONObject("""{"customGcode":[
            {"printZ":10.5,"type":"ColorChange","extruder":1,"color":"#FF0000"},
            {"printZ":20.0,"type":"ToolChange","extruder":2,"color":"#00FF00"}
        ]}""")
        val entries = KotlinBambuSnapshot.readCustomGcodeArray(obj)
        assertEquals(2, entries.size)
        assertEquals(CustomGcodeEntry(printZ = 10.5, type = "ColorChange", extruder = 1, color = "#FF0000"), entries[0])
        assertEquals(CustomGcodeEntry(printZ = 20.0, type = "ToolChange", extruder = 2, color = "#00FF00"), entries[1])
    }

    @Test
    fun `readCustomGcodeArray defaults missing fields to zero and empty string`() {
        val obj = JSONObject("""{"customGcode":[{}]}""")
        val entries = KotlinBambuSnapshot.readCustomGcodeArray(obj)
        assertEquals(1, entries.size)
        assertEquals(CustomGcodeEntry(printZ = 0.0, type = "", extruder = 0, color = ""), entries[0])
    }

    // ---- readPlateConfig ----

    @Test
    fun `readPlateConfig returns empty when key is absent`() {
        val obj = JSONObject("""{"plateIndex":0}""")
        assertEquals(emptyMap<String, String>(), KotlinBambuSnapshot.readPlateConfig(obj))
    }

    @Test
    fun `readPlateConfig stringifies every key to a String value`() {
        // Phase 0 contract: native emits plateConfig as a string→string map
        // where every value is opt_serialize'd. The Kotlin decoder reads them
        // via optString so any non-string value becomes "" rather than failing.
        val obj = JSONObject("""{"plateConfig":{
            "print_sequence":"by layer",
            "first_layer_temperature":"215"
        }}""")
        assertEquals(
            mapOf("print_sequence" to "by layer", "first_layer_temperature" to "215"),
            KotlinBambuSnapshot.readPlateConfig(obj)
        )
    }

    @Test
    fun `readPlateConfig returns empty for an empty plateConfig object`() {
        val obj = JSONObject("""{"plateConfig":{}}""")
        assertEquals(emptyMap<String, String>(), KotlinBambuSnapshot.readPlateConfig(obj))
    }

    // ---- parseObjectArray (full end-to-end decode) ----

    @Test
    fun `parseObjectArray returns null for null input`() {
        assertNull(KotlinBambuSnapshot.parseObjectArray(null))
    }

    @Test
    fun `parseObjectArray returns null for empty string`() {
        assertNull(KotlinBambuSnapshot.parseObjectArray(""))
    }

    @Test
    fun `parseObjectArray returns null for malformed JSON`() {
        assertNull(KotlinBambuSnapshot.parseObjectArray("{not valid"))
    }

    @Test
    fun `parseObjectArray decodes the native append_object array shape`() {
        val json = """[
            {"objectId":42,"name":"hull","extruder":0,"sourcePath":"/tmp/hull.stl"},
            {"objectId":43,"name":"deck","extruder":2,"sourcePath":""}
        ]"""
        val out = KotlinBambuSnapshot.parseObjectArray(json)
        assertNotNull(out)
        out!!
        assertEquals(2, out.size)
        assertEquals(ObjectSnapshot(objectId = 42, name = "hull", extruder = 0, sourcePath = "/tmp/hull.stl"), out[0])
        assertEquals(ObjectSnapshot(objectId = 43, name = "deck", extruder = 2, sourcePath = ""), out[1])
    }

    @Test
    fun `parseObjectArray returns empty list for empty JSON array`() {
        assertEquals(emptyList<ObjectSnapshot>(), KotlinBambuSnapshot.parseObjectArray("[]"))
    }

    @Test
    fun `parseObjectArray tolerates null slots by returning sentinel ObjectSnapshots`() {
        // sapil_bambu_objects.cpp emits the literal `null` token for null slots
        // in g_model.objects (defensive — Slic3r's own code never produces this
        // state, but the C++ is written to tolerate it). The Kotlin decoder
        // must not throw on that input; it surfaces a sentinel objectId=-1
        // entry so the list length still matches the native object count.
        val out = KotlinBambuSnapshot.parseObjectArray("""[null,{"objectId":5,"name":"ok","extruder":0,"sourcePath":""}]""")
        assertNotNull(out)
        out!!
        assertEquals(2, out.size)
        assertEquals(-1, out[0].objectId)
        assertEquals(5, out[1].objectId)
    }

    @Test
    fun `parseObjectArray handles large objectId runtime values without overflow-at-Long`() {
        // Slic3r's runtime ObjectID is size_t; on arm64 that's 64-bit.
        // Check a value exceeding Int.MAX that truncates on conversion — the
        // decoder reads optLong then toInt, so the truncation is expected but
        // should not throw.
        val huge = 5_000_000_000L  // > Int.MAX (≈2.15e9)
        val json = """[{"objectId":$huge,"name":"x","extruder":0,"sourcePath":""}]"""
        val out = KotlinBambuSnapshot.parseObjectArray(json)
        assertNotNull(out)
        out!!
        assertEquals(1, out.size)
        // Truncation to Int; exact value not asserted (platform-dependent),
        // just that it didn't crash.
        assertTrue(out[0].name == "x")
    }
}
