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

    // --- resolveInto ---

    @Test
    fun `resolveInto USE_FILE keeps base values`() {
        val base = SliceConfig(layerHeight = 0.3f, fillDensity = 0.20f, perimeters = 3,
            supportEnabled = true, brimWidth = 5f, skirtLoops = 2, bedTemp = 70,
            wipeTowerEnabled = true)
        val overrides = SlicingOverrides() // all USE_FILE
        val resolved = overrides.resolveInto(base)
        assertEquals(0.3f, resolved.layerHeight, 0.001f)
        assertEquals(0.20f, resolved.fillDensity, 0.001f)
        assertEquals(3, resolved.perimeters)
        assertTrue(resolved.supportEnabled)
        assertEquals(5f, resolved.brimWidth, 0.001f)
        assertEquals(2, resolved.skirtLoops)
        assertEquals(70, resolved.bedTemp)
        assertTrue(resolved.wipeTowerEnabled)
    }

    @Test
    fun `resolveInto ORCA_DEFAULT replaces with factory defaults`() {
        val base = SliceConfig(layerHeight = 0.3f, fillDensity = 0.40f, perimeters = 5,
            fillPattern = "grid", supportEnabled = true, brimWidth = 8f, skirtLoops = 3,
            bedTemp = 90, wipeTowerEnabled = true)
        val overrides = SlicingOverrides(
            layerHeight   = OverrideValue(OverrideMode.ORCA_DEFAULT),
            infillDensity = OverrideValue(OverrideMode.ORCA_DEFAULT),
            wallCount     = OverrideValue(OverrideMode.ORCA_DEFAULT),
            infillPattern = OverrideValue(OverrideMode.ORCA_DEFAULT),
            supports      = OverrideValue(OverrideMode.ORCA_DEFAULT),
            brimWidth     = OverrideValue(OverrideMode.ORCA_DEFAULT),
            skirtLoops    = OverrideValue(OverrideMode.ORCA_DEFAULT),
            bedTemp       = OverrideValue(OverrideMode.ORCA_DEFAULT),
            primeTower    = OverrideValue(OverrideMode.ORCA_DEFAULT)
        )
        val resolved = overrides.resolveInto(base)
        assertEquals(0.2f, resolved.layerHeight, 0.001f)
        assertEquals(0.15f, resolved.fillDensity, 0.001f)
        assertEquals(2, resolved.perimeters)
        assertEquals("gyroid", resolved.fillPattern)
        assertFalse(resolved.supportEnabled)
        assertEquals(0f, resolved.brimWidth, 0.001f)
        assertEquals(0, resolved.skirtLoops)
        assertEquals(60, resolved.bedTemp)
        assertFalse(resolved.wipeTowerEnabled)
    }

    @Test
    fun `resolveInto OVERRIDE uses user value`() {
        val base = SliceConfig(layerHeight = 0.2f, fillDensity = 0.15f, perimeters = 2,
            supportEnabled = false, brimWidth = 0f, skirtLoops = 0, bedTemp = 60,
            wipeTowerEnabled = false)
        val overrides = SlicingOverrides(
            layerHeight   = OverrideValue(OverrideMode.OVERRIDE, 0.15f),
            infillDensity = OverrideValue(OverrideMode.OVERRIDE, 0.30f),
            wallCount     = OverrideValue(OverrideMode.OVERRIDE, 4),
            supports      = OverrideValue(OverrideMode.OVERRIDE, true),
            brimWidth     = OverrideValue(OverrideMode.OVERRIDE, 6f),
            skirtLoops    = OverrideValue(OverrideMode.OVERRIDE, 2),
            bedTemp       = OverrideValue(OverrideMode.OVERRIDE, 75),
            primeTower    = OverrideValue(OverrideMode.OVERRIDE, true)
        )
        val resolved = overrides.resolveInto(base)
        assertEquals(0.15f, resolved.layerHeight, 0.001f)
        assertEquals(0.30f, resolved.fillDensity, 0.001f)
        assertEquals(4, resolved.perimeters)
        assertTrue(resolved.supportEnabled)
        assertEquals(6f, resolved.brimWidth, 0.001f)
        assertEquals(2, resolved.skirtLoops)
        assertEquals(75, resolved.bedTemp)
        assertTrue(resolved.wipeTowerEnabled)
    }

    @Test
    fun `resolveInto OVERRIDE with null value falls back to base`() {
        val base = SliceConfig(layerHeight = 0.25f, perimeters = 3)
        val overrides = SlicingOverrides(
            layerHeight = OverrideValue(OverrideMode.OVERRIDE, null),
            wallCount   = OverrideValue(OverrideMode.OVERRIDE, null)
        )
        val resolved = overrides.resolveInto(base)
        assertEquals(0.25f, resolved.layerHeight, 0.001f)
        assertEquals(3, resolved.perimeters)
    }

    @Test
    fun `resolveInto mixed modes`() {
        val base = SliceConfig(layerHeight = 0.3f, fillDensity = 0.20f, perimeters = 3,
            supportEnabled = true)
        val overrides = SlicingOverrides(
            layerHeight   = OverrideValue(OverrideMode.USE_FILE),
            infillDensity = OverrideValue(OverrideMode.ORCA_DEFAULT),
            wallCount     = OverrideValue(OverrideMode.OVERRIDE, 5),
            supports      = OverrideValue(OverrideMode.ORCA_DEFAULT)
        )
        val resolved = overrides.resolveInto(base)
        assertEquals(0.3f, resolved.layerHeight, 0.001f)
        assertEquals(0.15f, resolved.fillDensity, 0.001f)
        assertEquals(5, resolved.perimeters)
        assertFalse(resolved.supportEnabled)
    }

    // --- Multi-extruder wipe tower regression ---

    /**
     * Regression: when primeTower override = ORCA_DEFAULT and extruderCount >= 2,
     * resolveInto() must NOT clobber wipeTowerEnabled=true.
     * Without a wipe tower, OrcaSlicer never emits T1 even with extruderCount=2.
     * MUST FAIL on current code (ORCA_DEFAULTS["primeTower"]=false wins over base.wipeTowerEnabled).
     */
    @Test
    fun `resolveInto ORCA_DEFAULT primeTower preserves wipe tower for multi-extruder`() {
        val base = SliceConfig(wipeTowerEnabled = true, extruderCount = 2)
        val overrides = SlicingOverrides(primeTower = OverrideValue(OverrideMode.ORCA_DEFAULT))
        val resolved = overrides.resolveInto(base)
        assertTrue(
            "Multi-extruder slice must keep wipeTowerEnabled=true regardless of primeTower override",
            resolved.wipeTowerEnabled
        )
    }

    /**
     * Corollary: ORCA_DEFAULT primeTower with single-extruder should still disable the tower.
     * Must pass on both old and new code.
     */
    @Test
    fun `resolveInto ORCA_DEFAULT primeTower disables wipe tower for single-extruder`() {
        val base = SliceConfig(wipeTowerEnabled = true, extruderCount = 1)
        val overrides = SlicingOverrides(primeTower = OverrideValue(OverrideMode.ORCA_DEFAULT))
        val resolved = overrides.resolveInto(base)
        assertFalse(
            "Single-extruder slice with ORCA_DEFAULT primeTower should disable wipe tower",
            resolved.wipeTowerEnabled
        )
    }

    /**
     * OVERRIDE primeTower=false is an explicit user choice — should be respected even for
     * multi-extruder (user knows what they're doing).
     * Must pass on both old and new code.
     */
    @Test
    fun `resolveInto OVERRIDE primeTower false is respected for multi-extruder`() {
        val base = SliceConfig(wipeTowerEnabled = true, extruderCount = 2)
        val overrides = SlicingOverrides(primeTower = OverrideValue(OverrideMode.OVERRIDE, false))
        val resolved = overrides.resolveInto(base)
        assertFalse(
            "Explicit OVERRIDE primeTower=false must be respected regardless of extruder count",
            resolved.wipeTowerEnabled
        )
    }

    // --- resolvePrimeTower (profile-embed path) ---

    /**
     * Regression: buildProfileOverrides() used plain resolve() which returns false for
     * ORCA_DEFAULT — so the embedded profile got enable_prime_tower=0, overriding the JNI
     * wipeTowerEnabled=true → 0 T1 commands even for multi-extruder slices.
     * MUST FAIL on the original code path; passes once resolvePrimeTower() guards extCount.
     */
    @Test
    fun `resolvePrimeTower ORCA_DEFAULT preserves prime tower for multi-extruder`() {
        val overrides = SlicingOverrides(primeTower = OverrideValue(OverrideMode.ORCA_DEFAULT))
        assertTrue(
            "Multi-extruder embed must have prime tower enabled regardless of ORCA_DEFAULT mode",
            overrides.resolvePrimeTower(extCount = 2, cfgWipeTower = true)
        )
    }

    @Test
    fun `resolvePrimeTower USE_FILE with multi-extruder preserves prime tower`() {
        val overrides = SlicingOverrides() // all USE_FILE
        assertTrue(
            "Multi-extruder embed with USE_FILE must keep prime tower enabled",
            overrides.resolvePrimeTower(extCount = 2, cfgWipeTower = true)
        )
    }

    @Test
    fun `resolvePrimeTower ORCA_DEFAULT with single-extruder disables prime tower`() {
        val overrides = SlicingOverrides(primeTower = OverrideValue(OverrideMode.ORCA_DEFAULT))
        assertFalse(
            "Single-extruder embed with ORCA_DEFAULT should disable prime tower",
            overrides.resolvePrimeTower(extCount = 1, cfgWipeTower = false)
        )
    }

    @Test
    fun `resolvePrimeTower OVERRIDE false is respected for multi-extruder`() {
        val overrides = SlicingOverrides(primeTower = OverrideValue(OverrideMode.OVERRIDE, false))
        assertFalse(
            "Explicit OVERRIDE false must be respected even for multi-extruder",
            overrides.resolvePrimeTower(extCount = 2, cfgWipeTower = true)
        )
    }

    @Test
    fun `resolvePrimeTower OVERRIDE true is returned for single-extruder`() {
        val overrides = SlicingOverrides(primeTower = OverrideValue(OverrideMode.OVERRIDE, true))
        assertTrue(
            "OVERRIDE true should return true for single-extruder too",
            overrides.resolvePrimeTower(extCount = 1, cfgWipeTower = false)
        )
    }

    @Test
    fun `resolveInto does not modify base config`() {
        val base = SliceConfig(layerHeight = 0.2f, perimeters = 2)
        val overrides = SlicingOverrides(
            layerHeight = OverrideValue(OverrideMode.OVERRIDE, 0.1f),
            wallCount   = OverrideValue(OverrideMode.ORCA_DEFAULT)
        )
        overrides.resolveInto(base)
        assertEquals(0.2f, base.layerHeight, 0.001f)
        assertEquals(2, base.perimeters)
    }
}
