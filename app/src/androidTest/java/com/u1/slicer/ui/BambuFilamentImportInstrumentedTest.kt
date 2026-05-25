package com.u1.slicer.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.data.BambuProfileResolver
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F91 follow-up: end-to-end Bambu filament profile import via [parseFilamentJson] with the
 * bundled inherits-chain resolver. Uses a real `Bambu PLA Basic @BBL P1S 0.4 nozzle.json`
 * profile from BambuStudio's system tree (committed under androidTest assets).
 *
 * Asserts the flattened import populates fields drawn from EACH level of the chain:
 *   - nozzle_temperature: from the child file directly
 *   - filament_max_volumetric_speed: from `Bambu PLA Basic @base`
 *   - filament_cost / filament_density: from `Bambu PLA Basic @base`
 *   - hot_plate_temp (= bed temp): from `fdm_filament_pla`
 *   - fan_min_speed / slow_down_layer_time / additional_cooling_fan_speed: from `fdm_filament_pla`
 *   - filament_type: from `fdm_filament_common` (root)
 */
@RunWith(AndroidJUnit4::class)
class BambuFilamentImportInstrumentedTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().context
    private val targetCtx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun readTestAsset(name: String): String =
        ctx.assets.open(name).bufferedReader().readText()

    @Test
    fun realBambuPlaBasic_inheritsChain_flattensCorrectly() {
        val json = readTestAsset("Bambu_PLA_Basic_P1S.json")
        val profiles = parseFilamentJson(json, targetCtx)
        assertEquals(1, profiles.size)
        val p = profiles[0]
        // Identity
        assertEquals("Bambu PLA Basic @BBL P1S 0.4 nozzle", p.name)
        assertEquals("PLA", p.material)
        // From child: nozzle_temperature
        assertEquals(220, p.nozzleTemp)
        assertEquals(220, p.nozzleTempInitialLayer)
        // From `Bambu PLA Basic @base`: cost/density/flow ratio/max vol speed
        // (child overrides max vol speed to "21"/"29" → first element 21)
        assertEquals(21f, p.maxVolumetricSpeed!!, 0.01f)
        assertEquals(0.98f, p.flowRatio!!, 0.01f)
        assertEquals(1.26f, p.density, 0.01f)
        assertEquals(24.99f, p.filamentCost!!, 0.01f)
        // From `fdm_filament_pla`: bed temp comes via hot_plate_temp=55
        assertEquals(55, p.bedTemp)
        assertEquals(55, p.bedTempInitialLayer)
        // From `fdm_filament_pla`: cooling
        assertEquals(100, p.fanMinSpeed)
        assertEquals(70, p.additionalCoolingFanSpeed)
        assertEquals(1, p.closeFanFirstLayers)
        assertEquals(4f, p.slowDownLayerTime!!, 0.01f)
        // From child: slow_down_min_speed overridden
        assertEquals(20f, p.slowDownMinSpeed!!, 0.01f)
    }

    /** Sanity: resolver returns null for objects with no `inherits` key — caller uses original. */
    @Test
    fun flatten_noInheritsKey_returnsNull() {
        val standalone = JSONObject().apply {
            put("type", "filament")
            put("name", "Standalone PLA")
            put("filament_type", org.json.JSONArray(listOf("PLA")))
            put("nozzle_temperature", org.json.JSONArray(listOf("215")))
        }
        val result = BambuProfileResolver.flatten(targetCtx, standalone)
        assertEquals("no inherits → null so caller can use original", null, result)
    }

    /** Resolver gracefully degrades when parent isn't in bundled chain (returns child as-is). */
    @Test
    fun flatten_unknownParent_returnsChildAsIs() {
        val orphan = JSONObject().apply {
            put("type", "filament")
            put("name", "OrphanFilament")
            put("inherits", "Some Unknown Vendor @base")
            put("filament_type", org.json.JSONArray(listOf("ABS")))
        }
        val result = BambuProfileResolver.flatten(targetCtx, orphan)
        assertNotNull(result)
        assertEquals("ABS", result!!.getJSONArray("filament_type").getString(0))
    }
}
