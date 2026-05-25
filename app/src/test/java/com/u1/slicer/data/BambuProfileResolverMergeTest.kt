package com.u1.slicer.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F91 follow-up: pure unit tests for [BambuProfileResolver.merge].
 *
 * The chain-walking path needs Android `Context.assets` and is covered in instrumented tests.
 */
class BambuProfileResolverMergeTest {

    @Test
    fun child_scalarOverridesParent() {
        val parent = JSONObject().apply {
            put("nozzle_temperature", JSONArray(listOf("220")))
            put("filament_type", JSONArray(listOf("PLA")))
        }
        val child = JSONObject().apply {
            put("nozzle_temperature", JSONArray(listOf("215")))
        }
        val merged = BambuProfileResolver.merge(parent, child)
        assertEquals(JSONArray(listOf("215")).toString(), merged.getJSONArray("nozzle_temperature").toString())
        // filament_type carried over from parent.
        assertEquals("PLA", merged.getJSONArray("filament_type").getString(0))
    }

    @Test
    fun child_addsNewKeyWithoutOverwritingExistingParentKey() {
        val parent = JSONObject().apply { put("a", "1") }
        val child = JSONObject().apply { put("b", "2") }
        val merged = BambuProfileResolver.merge(parent, child)
        assertEquals("1", merged.getString("a"))
        assertEquals("2", merged.getString("b"))
    }

    @Test
    fun arrayMerge_nilEntryFallsThroughToParent() {
        // Parent: [220, 220]
        // Child:  ["nil", 215] → result should be [220, 215]
        val parent = JSONObject().apply {
            put("nozzle_temperature", JSONArray(listOf("220", "220")))
        }
        val child = JSONObject().apply {
            put("nozzle_temperature", JSONArray(listOf("nil", "215")))
        }
        val merged = BambuProfileResolver.merge(parent, child)
        val arr = merged.getJSONArray("nozzle_temperature")
        assertEquals("220", arr.getString(0))
        assertEquals("215", arr.getString(1))
    }

    @Test
    fun arrayMerge_childLongerThanParent_keepsTail() {
        val parent = JSONObject().apply { put("k", JSONArray(listOf("a"))) }
        val child = JSONObject().apply { put("k", JSONArray(listOf("b", "c", "d"))) }
        val merged = BambuProfileResolver.merge(parent, child)
        val arr = merged.getJSONArray("k")
        assertEquals(3, arr.length())
        assertEquals("b", arr.getString(0))
        assertEquals("c", arr.getString(1))
        assertEquals("d", arr.getString(2))
    }

    @Test
    fun provenanceKeysStripped() {
        val parent = JSONObject().apply { put("filament_type", JSONArray(listOf("PLA"))) }
        val child = JSONObject().apply {
            put("inherits", "Bambu PLA Basic @base")
            put("from", "system")
            put("instantiation", "true")
            put("setting_id", "GFSA00_34")
            put("include", JSONArray(listOf("fdm_filament_template_direct_dual")))
            put("filament_max_volumetric_speed", JSONArray(listOf("21")))
        }
        val merged = BambuProfileResolver.merge(parent, child)
        assertFalse("inherits stripped", merged.has("inherits"))
        assertFalse("from stripped", merged.has("from"))
        assertFalse("instantiation stripped", merged.has("instantiation"))
        assertFalse("setting_id stripped", merged.has("setting_id"))
        assertFalse("include stripped", merged.has("include"))
        assertTrue("real keys retained", merged.has("filament_max_volumetric_speed"))
        assertTrue("parent keys retained", merged.has("filament_type"))
    }

    @Test
    fun deepChain_threeLevels() {
        val grandparent = JSONObject().apply {
            put("nozzle_temperature", JSONArray(listOf("220")))
            put("filament_density", JSONArray(listOf("1.24")))
            put("fan_min_speed", JSONArray(listOf("100")))
        }
        val parent = JSONObject().apply {
            put("filament_density", JSONArray(listOf("1.26")))
            put("filament_max_volumetric_speed", JSONArray(listOf("21")))
        }
        val child = JSONObject().apply {
            put("nozzle_temperature", JSONArray(listOf("215")))
        }
        // Simulate the chain: first merge grandparent ← parent, then result ← child.
        val partial = BambuProfileResolver.merge(grandparent, parent)
        val merged = BambuProfileResolver.merge(partial, child)
        assertEquals("215", merged.getJSONArray("nozzle_temperature").getString(0)) // child wins
        assertEquals("1.26", merged.getJSONArray("filament_density").getString(0))  // parent overrides grandparent
        assertEquals("21", merged.getJSONArray("filament_max_volumetric_speed").getString(0))
        assertEquals("100", merged.getJSONArray("fan_min_speed").getString(0))  // grandparent stands
    }
}
