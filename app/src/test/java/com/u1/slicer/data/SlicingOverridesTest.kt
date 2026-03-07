package com.u1.slicer.data

import org.junit.Assert.*
import org.junit.Test

class SlicingOverridesTest {

    @Test
    fun `default overrides are all USE_FILE`() {
        val overrides = SlicingOverrides()
        assertEquals(OverrideMode.USE_FILE, overrides.layerHeight.mode)
        assertEquals(OverrideMode.USE_FILE, overrides.infillDensity.mode)
        assertEquals(OverrideMode.USE_FILE, overrides.wallCount.mode)
        assertEquals(OverrideMode.USE_FILE, overrides.infillPattern.mode)
        assertEquals(OverrideMode.USE_FILE, overrides.supports.mode)
        assertEquals(OverrideMode.USE_FILE, overrides.brimWidth.mode)
        assertEquals(OverrideMode.USE_FILE, overrides.skirtLoops.mode)
        assertEquals(OverrideMode.USE_FILE, overrides.bedTemp.mode)
        assertEquals(OverrideMode.USE_FILE, overrides.primeTower.mode)
        assertTrue(overrides.flowCalibration)
    }

    @Test
    fun `default overrides have null values`() {
        val overrides = SlicingOverrides()
        assertNull(overrides.layerHeight.value)
        assertNull(overrides.infillDensity.value)
        assertNull(overrides.wallCount.value)
    }

    @Test
    fun `serialization round-trip preserves defaults`() {
        val original = SlicingOverrides()
        val json = original.toJson()
        val restored = SlicingOverrides.fromJson(json)
        assertEquals(original.layerHeight.mode, restored.layerHeight.mode)
        assertEquals(original.infillDensity.mode, restored.infillDensity.mode)
        assertEquals(original.flowCalibration, restored.flowCalibration)
    }

    @Test
    fun `serialization round-trip preserves OVERRIDE values`() {
        val original = SlicingOverrides(
            layerHeight = OverrideValue(OverrideMode.OVERRIDE, 0.3f),
            infillDensity = OverrideValue(OverrideMode.OVERRIDE, 0.30f),
            wallCount = OverrideValue(OverrideMode.OVERRIDE, 4),
            infillPattern = OverrideValue(OverrideMode.OVERRIDE, "grid"),
            supports = OverrideValue(OverrideMode.OVERRIDE, true),
            brimWidth = OverrideValue(OverrideMode.OVERRIDE, 5.0f),
            skirtLoops = OverrideValue(OverrideMode.OVERRIDE, 3),
            bedTemp = OverrideValue(OverrideMode.OVERRIDE, 80),
            primeTower = OverrideValue(OverrideMode.OVERRIDE, true),
            flowCalibration = false
        )
        val json = original.toJson()
        val restored = SlicingOverrides.fromJson(json)

        assertEquals(OverrideMode.OVERRIDE, restored.layerHeight.mode)
        assertEquals(0.3f, restored.layerHeight.value!!, 0.001f)
        assertEquals(OverrideMode.OVERRIDE, restored.infillDensity.mode)
        assertEquals(0.30f, restored.infillDensity.value!!, 0.001f)
        assertEquals(OverrideMode.OVERRIDE, restored.wallCount.mode)
        assertEquals(4, restored.wallCount.value)
        assertEquals(OverrideMode.OVERRIDE, restored.infillPattern.mode)
        assertEquals("grid", restored.infillPattern.value)
        assertEquals(OverrideMode.OVERRIDE, restored.supports.mode)
        assertTrue(restored.supports.value!!)
        assertEquals(OverrideMode.OVERRIDE, restored.brimWidth.mode)
        assertEquals(5.0f, restored.brimWidth.value!!, 0.001f)
        assertEquals(OverrideMode.OVERRIDE, restored.skirtLoops.mode)
        assertEquals(3, restored.skirtLoops.value)
        assertEquals(OverrideMode.OVERRIDE, restored.bedTemp.mode)
        assertEquals(80, restored.bedTemp.value)
        assertEquals(OverrideMode.OVERRIDE, restored.primeTower.mode)
        assertTrue(restored.primeTower.value!!)
        assertFalse(restored.flowCalibration)
    }

    @Test
    fun `serialization round-trip preserves ORCA_DEFAULT mode`() {
        val original = SlicingOverrides(
            layerHeight = OverrideValue(OverrideMode.ORCA_DEFAULT),
            wallCount = OverrideValue(OverrideMode.ORCA_DEFAULT)
        )
        val json = original.toJson()
        val restored = SlicingOverrides.fromJson(json)
        assertEquals(OverrideMode.ORCA_DEFAULT, restored.layerHeight.mode)
        assertEquals(OverrideMode.ORCA_DEFAULT, restored.wallCount.mode)
    }

    @Test
    fun `fromJson handles empty string`() {
        val restored = SlicingOverrides.fromJson("")
        assertEquals(OverrideMode.USE_FILE, restored.layerHeight.mode)
        assertTrue(restored.flowCalibration)
    }

    @Test
    fun `fromJson handles malformed JSON`() {
        val restored = SlicingOverrides.fromJson("{invalid json")
        assertEquals(OverrideMode.USE_FILE, restored.layerHeight.mode)
    }

    @Test
    fun `fromJson handles missing fields`() {
        val restored = SlicingOverrides.fromJson("{\"flowCalibration\": false}")
        assertEquals(OverrideMode.USE_FILE, restored.layerHeight.mode)
        assertFalse(restored.flowCalibration)
    }

    @Test
    fun `fromJson handles unknown mode gracefully`() {
        val json = """{"layerHeight":{"mode":"UNKNOWN_MODE","value":0.3}}"""
        val restored = SlicingOverrides.fromJson(json)
        assertEquals(OverrideMode.USE_FILE, restored.layerHeight.mode)
    }

    @Test
    fun `ORCA_DEFAULTS contains expected keys`() {
        val defaults = SlicingOverrides.ORCA_DEFAULTS
        assertEquals(0.2f, defaults["layerHeight"])
        assertEquals(0.15f, defaults["infillDensity"])
        assertEquals(2, defaults["wallCount"])
        assertEquals("gyroid", defaults["infillPattern"])
        assertEquals(false, defaults["supports"])
        assertEquals(0f, defaults["brimWidth"])
        assertEquals(0, defaults["skirtLoops"])
        assertEquals(60, defaults["bedTemp"])
        assertEquals(false, defaults["primeTower"])
    }

    @Test
    fun `mixed modes serialize correctly`() {
        val original = SlicingOverrides(
            layerHeight = OverrideValue(OverrideMode.USE_FILE),
            infillDensity = OverrideValue(OverrideMode.ORCA_DEFAULT),
            wallCount = OverrideValue(OverrideMode.OVERRIDE, 3)
        )
        val json = original.toJson()
        val restored = SlicingOverrides.fromJson(json)
        assertEquals(OverrideMode.USE_FILE, restored.layerHeight.mode)
        assertEquals(OverrideMode.ORCA_DEFAULT, restored.infillDensity.mode)
        assertEquals(OverrideMode.OVERRIDE, restored.wallCount.mode)
        assertEquals(3, restored.wallCount.value)
    }
}
