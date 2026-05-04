package com.u1.slicer.data

import com.u1.slicer.buildProfileOverridesImpl
import com.u1.slicer.computeTogglePrimeTower
import com.u1.slicer.ui.formatFileValue
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
        assertEquals(OverrideMode.USE_FILE, overrides.reduceInfillRetraction.mode)
        assertEquals(OverrideMode.USE_FILE, overrides.wallGenerator.mode)
        assertEquals(OverrideMode.USE_FILE, overrides.seamPosition.mode)
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
        assertEquals(false, defaults["reduceInfillRetraction"])
        assertEquals("arachne", defaults["wallGenerator"])
        assertEquals("aligned", defaults["seamPosition"])
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
     * MUST FAIL on the original code path; passes once resolvePrimeTower() guards slotCount.
     */
    @Test
    fun `resolvePrimeTower ORCA_DEFAULT preserves prime tower for multi-extruder`() {
        val overrides = SlicingOverrides(primeTower = OverrideValue(OverrideMode.ORCA_DEFAULT))
        assertTrue(
            "Multi-extruder embed must have prime tower enabled regardless of ORCA_DEFAULT mode",
            overrides.resolvePrimeTower(slotCount = 2, cfgWipeTower = true)
        )
    }

    @Test
    fun `resolvePrimeTower USE_FILE with multi-extruder preserves prime tower`() {
        val overrides = SlicingOverrides() // all USE_FILE
        assertTrue(
            "Multi-extruder embed with USE_FILE must keep prime tower enabled",
            overrides.resolvePrimeTower(slotCount = 2, cfgWipeTower = true)
        )
    }

    @Test
    fun `resolvePrimeTower ORCA_DEFAULT with single-extruder disables prime tower`() {
        val overrides = SlicingOverrides(primeTower = OverrideValue(OverrideMode.ORCA_DEFAULT))
        assertFalse(
            "Single-extruder embed with ORCA_DEFAULT should disable prime tower",
            overrides.resolvePrimeTower(slotCount = 1, cfgWipeTower = false)
        )
    }

    @Test
    fun `resolvePrimeTower OVERRIDE false is respected for multi-extruder`() {
        val overrides = SlicingOverrides(primeTower = OverrideValue(OverrideMode.OVERRIDE, false))
        assertFalse(
            "Explicit OVERRIDE false must be respected even for multi-extruder",
            overrides.resolvePrimeTower(slotCount = 2, cfgWipeTower = true)
        )
    }

    @Test
    fun `resolvePrimeTower OVERRIDE true is returned for single-extruder`() {
        val overrides = SlicingOverrides(primeTower = OverrideValue(OverrideMode.OVERRIDE, true))
        assertTrue(
            "OVERRIDE true should return true for single-extruder too",
            overrides.resolvePrimeTower(slotCount = 1, cfgWipeTower = false)
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

    // --- resolveInto: B79 supportType and supportAngle ---

    @Test
    fun `resolveInto OVERRIDE supportType passes value to SliceConfig`() {
        val base = SliceConfig(supportType = "normal")
        val overrides = SlicingOverrides(
            supportType = OverrideValue(OverrideMode.OVERRIDE, "tree(auto)")
        )
        val resolved = overrides.resolveInto(base)
        assertEquals("tree(auto)", resolved.supportType)
    }

    @Test
    fun `resolveInto ORCA_DEFAULT supportType uses factory default`() {
        val base = SliceConfig(supportType = "tree(auto)")
        val overrides = SlicingOverrides(
            supportType = OverrideValue(OverrideMode.ORCA_DEFAULT)
        )
        val resolved = overrides.resolveInto(base)
        assertEquals("normal(auto)", resolved.supportType)
    }

    @Test
    fun `resolveInto USE_FILE supportType keeps base value`() {
        val base = SliceConfig(supportType = "tree(manual)")
        val overrides = SlicingOverrides()
        val resolved = overrides.resolveInto(base)
        assertEquals("tree(manual)", resolved.supportType)
    }

    @Test
    fun `resolveInto OVERRIDE supportAngle passes value to SliceConfig`() {
        val base = SliceConfig(supportAngle = 45f)
        val overrides = SlicingOverrides(
            supportAngle = OverrideValue(OverrideMode.OVERRIDE, 30)
        )
        val resolved = overrides.resolveInto(base)
        assertEquals(30f, resolved.supportAngle, 0.001f)
    }

    @Test
    fun `resolveInto ORCA_DEFAULT supportAngle uses factory default`() {
        val base = SliceConfig(supportAngle = 45f)
        val overrides = SlicingOverrides(
            supportAngle = OverrideValue(OverrideMode.ORCA_DEFAULT)
        )
        val resolved = overrides.resolveInto(base)
        assertEquals(30f, resolved.supportAngle, 0.001f)
    }

    // --- buildProfileOverridesImpl tests (B10 support preservation) ---

    @Test
    fun `buildProfileOverrides omits support keys for Bambu file with USE_FILE mode`() {
        val cfg = SliceConfig(supportEnabled = false)
        val ov = SlicingOverrides() // defaults to USE_FILE for supports
        val result = buildProfileOverridesImpl(cfg, ov, slotCount = 1, hasSourceConfig = true)
        assertFalse("enable_support should be omitted for Bambu USE_FILE", result.containsKey("enable_support"))
        assertFalse("support_threshold_angle should be omitted for Bambu USE_FILE", result.containsKey("support_threshold_angle"))
    }

    @Test
    fun `buildProfileOverrides includes support keys for STL file with USE_FILE mode`() {
        val cfg = SliceConfig(supportEnabled = true, supportAngle = 45f)
        val ov = SlicingOverrides() // USE_FILE
        val result = buildProfileOverridesImpl(cfg, ov, slotCount = 1, hasSourceConfig = false)
        assertEquals("1", result["enable_support"])
        assertEquals("45", result["support_threshold_angle"])
    }

    @Test
    fun `buildProfileOverrides includes support keys when user sets OVERRIDE mode on Bambu file`() {
        val cfg = SliceConfig(supportEnabled = false)
        val ov = SlicingOverrides(supports = OverrideValue(OverrideMode.OVERRIDE, true))
        val result = buildProfileOverridesImpl(cfg, ov, slotCount = 1, hasSourceConfig = true)
        assertEquals("1", result["enable_support"])
    }

    @Test
    fun `buildProfileOverrides includes support keys when user sets ORCA_DEFAULT mode on Bambu file`() {
        val cfg = SliceConfig(supportEnabled = true)
        val ov = SlicingOverrides(supports = OverrideValue(OverrideMode.ORCA_DEFAULT, null))
        val result = buildProfileOverridesImpl(cfg, ov, slotCount = 1, hasSourceConfig = true)
        assertTrue("enable_support should be present for ORCA_DEFAULT", result.containsKey("enable_support"))
    }

    @Test
    fun `buildProfileOverrides STL with support disabled emits 0`() {
        val cfg = SliceConfig(supportEnabled = false, supportAngle = 30f)
        val ov = SlicingOverrides()
        val result = buildProfileOverridesImpl(cfg, ov, slotCount = 1, hasSourceConfig = false)
        assertEquals("0", result["enable_support"])
        assertEquals("30", result["support_threshold_angle"])
    }

    // --- F8: Support extruder override tests ---

    @Test
    fun `default supportFilament and supportInterfaceFilament are USE_FILE`() {
        val overrides = SlicingOverrides()
        assertEquals(OverrideMode.USE_FILE, overrides.supportFilament.mode)
        assertEquals(OverrideMode.USE_FILE, overrides.supportInterfaceFilament.mode)
    }

    @Test
    fun `serialization round-trip preserves support extruder overrides`() {
        val original = SlicingOverrides(
            supportFilament = OverrideValue(OverrideMode.OVERRIDE, 2),
            supportInterfaceFilament = OverrideValue(OverrideMode.OVERRIDE, 3)
        )
        val json = original.toJson()
        val restored = SlicingOverrides.fromJson(json)
        assertEquals(OverrideMode.OVERRIDE, restored.supportFilament.mode)
        assertEquals(2, restored.supportFilament.value)
        assertEquals(OverrideMode.OVERRIDE, restored.supportInterfaceFilament.mode)
        assertEquals(3, restored.supportInterfaceFilament.value)
    }

    @Test
    fun `ORCA_DEFAULTS contains support extruder keys`() {
        val defaults = SlicingOverrides.ORCA_DEFAULTS
        assertEquals(0, defaults["supportFilament"])
        assertEquals(0, defaults["supportInterfaceFilament"])
    }

    @Test
    fun `buildProfileOverrides includes support_filament when overridden to non-zero`() {
        val cfg = SliceConfig()
        val ov = SlicingOverrides(
            supports = OverrideValue(OverrideMode.OVERRIDE, true),
            supportFilament = OverrideValue(OverrideMode.OVERRIDE, 2),
            supportInterfaceFilament = OverrideValue(OverrideMode.OVERRIDE, 3)
        )
        val result = buildProfileOverridesImpl(cfg, ov, slotCount = 4, hasSourceConfig = false)
        assertEquals("2", result["support_filament"])
        assertEquals("3", result["support_interface_filament"])
    }

    @Test
    fun `support filament override grows per-filament arrays to requested index`() {
        val cfg = SliceConfig(nozzleTemp = 225)
        val ov = SlicingOverrides(
            supports = OverrideValue(OverrideMode.OVERRIDE, true),
            supportFilament = OverrideValue(OverrideMode.OVERRIDE, 4)
        )
        val result = buildProfileOverridesImpl(
            cfg = cfg,
            ov = ov,
            slotCount = 1,
            filamentCount = 1,
            hasSourceConfig = false,
            filamentTypes = listOf("TPU"),
            nozzleTemps = listOf(225),
            filamentColours = listOf("#112233")
        )

        @Suppress("UNCHECKED_CAST")
        val types = result["filament_type"] as List<String>
        @Suppress("UNCHECKED_CAST")
        val temps = result["nozzle_temperature"] as List<String>
        @Suppress("UNCHECKED_CAST")
        val colours = result["filament_colour"] as List<String>

        assertEquals("4", result["support_filament"])
        assertEquals(listOf("TPU", "PLA", "PLA", "PLA"), types)
        assertEquals(listOf("225", "225", "225", "225"), temps)
        assertEquals(listOf("#112233", "#FFFFFF", "#FFFFFF", "#FFFFFF"), colours)
    }

    @Test
    fun `support interface filament override grows per-filament arrays to requested index`() {
        val cfg = SliceConfig(nozzleTemp = 220)
        val ov = SlicingOverrides(
            supports = OverrideValue(OverrideMode.OVERRIDE, true),
            supportInterfaceFilament = OverrideValue(OverrideMode.OVERRIDE, 3)
        )
        val result = buildProfileOverridesImpl(
            cfg = cfg,
            ov = ov,
            slotCount = 1,
            filamentCount = 1,
            hasSourceConfig = false,
            filamentTypes = listOf("TPU"),
            nozzleTemps = listOf(225)
        )

        @Suppress("UNCHECKED_CAST")
        val types = result["filament_type"] as List<String>
        @Suppress("UNCHECKED_CAST")
        val temps = result["nozzle_temperature"] as List<String>

        assertEquals("3", result["support_interface_filament"])
        assertEquals(listOf("TPU", "PLA", "PLA"), types)
        assertEquals(listOf("225", "220", "220"), temps)
    }

    @Test
    fun `buildProfileOverrides omits support_filament when default (0)`() {
        val cfg = SliceConfig()
        val ov = SlicingOverrides(
            supports = OverrideValue(OverrideMode.OVERRIDE, true),
            supportFilament = OverrideValue(OverrideMode.OVERRIDE, 0)
        )
        val result = buildProfileOverridesImpl(cfg, ov, slotCount = 1, hasSourceConfig = false)
        assertFalse("support_filament should be omitted when 0 (default)", result.containsKey("support_filament"))
    }

    // --- B24: stale extruderCount forces prime tower on single-color models ---

    @Test
    fun `resolveInto with extruderCount 1 and wipeTowerEnabled false stays false`() {
        // After B24 Fix 1 resets wipeTowerEnabled=false + extruderCount=1,
        // resolveInto must not re-enable the tower for single-extruder models.
        val base = SliceConfig(extruderCount = 1, wipeTowerEnabled = false)
        val overrides = SlicingOverrides() // all USE_FILE
        val resolved = overrides.resolveInto(base)
        assertFalse(
            "Single-extruder model with wipeTowerEnabled=false must stay false",
            resolved.wipeTowerEnabled
        )
    }

    @Test
    fun `buildProfileOverrides with stale wipeTowerEnabled produces correct prime tower flag`() {
        // resolvePrimeTower for single-extruder with USE_FILE passes through cfgWipeTower.
        // After B24 Fix 1 resets wipeTowerEnabled=false, the embedded profile must get 0.
        val cfg = SliceConfig(extruderCount = 1, wipeTowerEnabled = false)
        val ov = SlicingOverrides() // all USE_FILE
        val result = buildProfileOverridesImpl(cfg, ov, slotCount = 1, hasSourceConfig = false)
        assertEquals(
            "Single-extruder with wipeTowerEnabled=false should have enable_prime_tower=0",
            "0", result["enable_prime_tower"]
        )
    }

    @Test
    fun `buildProfileOverrides single-extruder with stale wipeTowerEnabled true still produces 1`() {
        // If somehow wipeTowerEnabled is still true for single-extruder (pre-B24-fix path),
        // resolvePrimeTower USE_FILE passes through cfgWipeTower=true → enable_prime_tower=1.
        // This documents the passthrough behavior — the ViewModel fix prevents this state.
        val cfg = SliceConfig(extruderCount = 1, wipeTowerEnabled = true)
        val ov = SlicingOverrides() // all USE_FILE
        val result = buildProfileOverridesImpl(cfg, ov, slotCount = 1, hasSourceConfig = false)
        assertEquals(
            "USE_FILE passes through cfgWipeTower value",
            "1", result["enable_prime_tower"]
        )
    }

    // --- B17: skirt_height in profile overrides ---

    @Test
    fun `buildProfileOverrides sets skirt_height 0 when skirt_loops is 0`() {
        val cfg = SliceConfig(skirtLoops = 0)
        val ov = SlicingOverrides()
        val result = buildProfileOverridesImpl(cfg, ov, slotCount = 1, hasSourceConfig = false)
        assertEquals("0", result["skirt_loops"])
        assertEquals("0", result["skirt_height"])
    }

    @Test
    fun `buildProfileOverrides sets skirt_height 1 when skirt_loops greater than 0`() {
        val cfg = SliceConfig(skirtLoops = 0)
        val ov = SlicingOverrides(skirtLoops = OverrideValue(OverrideMode.OVERRIDE, 3))
        val result = buildProfileOverridesImpl(cfg, ov, slotCount = 1, hasSourceConfig = false)
        assertEquals("3", result["skirt_loops"])
        assertEquals("1", result["skirt_height"])
    }

    @Test
    fun `buildProfileOverrides skirt_height 0 when ORCA_DEFAULT skirt_loops resolves to 0`() {
        val cfg = SliceConfig(skirtLoops = 2)
        val ov = SlicingOverrides(skirtLoops = OverrideValue(OverrideMode.ORCA_DEFAULT))
        val result = buildProfileOverridesImpl(cfg, ov, slotCount = 1, hasSourceConfig = false)
        assertEquals("0", result["skirt_loops"])
        assertEquals("0", result["skirt_height"])
    }

    // --- B31: auto_brim from Bambu source leaking through profile_keys[] ---

    @Test
    fun `buildProfileOverrides emits no_brim when brim_width is 0`() {
        // auto_brim from Bambu file must not leak through — brim_type must be
        // explicitly set so OrcaSlicer does not add geometry-based brims to small objects.
        val cfg = SliceConfig(brimWidth = 0f)
        val ov = SlicingOverrides()
        val result = buildProfileOverridesImpl(cfg, ov, slotCount = 1, hasSourceConfig = true)
        assertEquals("no_brim", result["brim_type"])
        assertEquals("0.0", result["brim_width"])
    }

    @Test
    fun `buildProfileOverrides emits manual_brim when brim_width is positive`() {
        val cfg = SliceConfig(brimWidth = 0f)
        val ov = SlicingOverrides(brimWidth = OverrideValue(OverrideMode.OVERRIDE, 5f))
        val result = buildProfileOverridesImpl(cfg, ov, slotCount = 1, hasSourceConfig = true)
        assertEquals("manual_brim", result["brim_type"])
        assertEquals("5.0", result["brim_width"])
    }

    // --- F30/F31: New shell, infill-speed, surface-pattern, support-detail overrides ---

    @Test
    fun `default new overrides are all USE_FILE`() {
        val ov = SlicingOverrides()
        assertEquals(OverrideMode.USE_FILE, ov.topShellLayers.mode)
        assertEquals(OverrideMode.USE_FILE, ov.bottomShellLayers.mode)
        assertEquals(OverrideMode.USE_FILE, ov.topSurfacePattern.mode)
        assertEquals(OverrideMode.USE_FILE, ov.bottomSurfacePattern.mode)
        assertEquals(OverrideMode.USE_FILE, ov.sparseInfillSpeed.mode)
        assertEquals(OverrideMode.USE_FILE, ov.supportXyDistance.mode)
        assertEquals(OverrideMode.USE_FILE, ov.supportInterfacePattern.mode)
        assertEquals(OverrideMode.USE_FILE, ov.supportInterfaceSpacing.mode)
        assertEquals(OverrideMode.USE_FILE, ov.supportSpeed.mode)
        assertEquals(OverrideMode.USE_FILE, ov.treeSupportBranchAngle.mode)
        assertEquals(OverrideMode.USE_FILE, ov.treeSupportBranchDistance.mode)
        assertEquals(OverrideMode.USE_FILE, ov.treeSupportBranchDiameter.mode)
    }

    @Test
    fun `serialization round-trip preserves new override fields`() {
        val original = SlicingOverrides(
            topShellLayers = OverrideValue(OverrideMode.OVERRIDE, 4),
            bottomShellLayers = OverrideValue(OverrideMode.OVERRIDE, 3),
            topSurfacePattern = OverrideValue(OverrideMode.OVERRIDE, "monotonic"),
            bottomSurfacePattern = OverrideValue(OverrideMode.OVERRIDE, "rectilinear"),
            sparseInfillSpeed = OverrideValue(OverrideMode.OVERRIDE, 150),
            supportXyDistance = OverrideValue(OverrideMode.OVERRIDE, 0.3f),
            supportInterfacePattern = OverrideValue(OverrideMode.OVERRIDE, "rectilinear"),
            supportInterfaceSpacing = OverrideValue(OverrideMode.OVERRIDE, 0.2f),
            supportSpeed = OverrideValue(OverrideMode.OVERRIDE, 80),
            treeSupportBranchAngle = OverrideValue(OverrideMode.OVERRIDE, 40),
            treeSupportBranchDistance = OverrideValue(OverrideMode.OVERRIDE, 5.0f),
            treeSupportBranchDiameter = OverrideValue(OverrideMode.OVERRIDE, 2.0f)
        )
        val restored = SlicingOverrides.fromJson(original.toJson())

        assertEquals(OverrideMode.OVERRIDE, restored.topShellLayers.mode)
        assertEquals(4, restored.topShellLayers.value)
        assertEquals(OverrideMode.OVERRIDE, restored.bottomShellLayers.mode)
        assertEquals(3, restored.bottomShellLayers.value)
        assertEquals("monotonic", restored.topSurfacePattern.value)
        assertEquals("rectilinear", restored.bottomSurfacePattern.value)
        assertEquals(150, restored.sparseInfillSpeed.value)
        assertEquals(0.3f, restored.supportXyDistance.value!!, 0.001f)
        assertEquals("rectilinear", restored.supportInterfacePattern.value)
        assertEquals(0.2f, restored.supportInterfaceSpacing.value!!, 0.001f)
        assertEquals(80, restored.supportSpeed.value)
        assertEquals(40, restored.treeSupportBranchAngle.value)
        assertEquals(5.0f, restored.treeSupportBranchDistance.value!!, 0.001f)
        assertEquals(2.0f, restored.treeSupportBranchDiameter.value!!, 0.001f)
    }

    @Test
    fun `buildProfileOverrides emits top and bottom shell layers when overridden`() {
        val cfg = SliceConfig()
        val ov = SlicingOverrides(
            topShellLayers = OverrideValue(OverrideMode.OVERRIDE, 5),
            bottomShellLayers = OverrideValue(OverrideMode.OVERRIDE, 4)
        )
        val result = buildProfileOverridesImpl(cfg, ov, slotCount = 1, hasSourceConfig = false)
        assertEquals("5", result["top_shell_layers"])
        assertEquals("4", result["bottom_shell_layers"])
    }

    @Test
    fun `buildProfileOverrides emits surface patterns when overridden`() {
        val cfg = SliceConfig()
        val ov = SlicingOverrides(
            topSurfacePattern = OverrideValue(OverrideMode.OVERRIDE, "concentric"),
            bottomSurfacePattern = OverrideValue(OverrideMode.OVERRIDE, "monotonic")
        )
        val result = buildProfileOverridesImpl(cfg, ov, slotCount = 1, hasSourceConfig = false)
        assertEquals("concentric", result["top_surface_pattern"])
        assertEquals("monotonic", result["bottom_surface_pattern"])
    }

    @Test
    fun `buildProfileOverrides emits sparse infill speed when overridden and nonzero`() {
        val cfg = SliceConfig()
        val ov = SlicingOverrides(sparseInfillSpeed = OverrideValue(OverrideMode.OVERRIDE, 200))
        val result = buildProfileOverridesImpl(cfg, ov, slotCount = 1, hasSourceConfig = false)
        assertEquals("200", result["sparse_infill_speed"])
    }

    @Test
    fun `buildProfileOverrides omits sparse infill speed when zero (auto)`() {
        val cfg = SliceConfig()
        val ov = SlicingOverrides(sparseInfillSpeed = OverrideValue(OverrideMode.OVERRIDE, 0))
        val result = buildProfileOverridesImpl(cfg, ov, slotCount = 1, hasSourceConfig = false)
        assertFalse("sparse_infill_speed=0 means auto — must not be emitted", result.containsKey("sparse_infill_speed"))
    }

    @Test
    fun `buildProfileOverrides emits support detail keys when overridden`() {
        val cfg = SliceConfig()
        val ov = SlicingOverrides(
            supportXyDistance = OverrideValue(OverrideMode.OVERRIDE, 0.35f),
            supportInterfacePattern = OverrideValue(OverrideMode.OVERRIDE, "rectilinear"),
            supportInterfaceSpacing = OverrideValue(OverrideMode.OVERRIDE, 0.2f),
            supportSpeed = OverrideValue(OverrideMode.OVERRIDE, 60)
        )
        val result = buildProfileOverridesImpl(cfg, ov, slotCount = 1, hasSourceConfig = false)
        assertEquals("0.35", result["support_object_xy_distance"])
        assertEquals("rectilinear", result["support_interface_pattern"])
        assertEquals("0.2", result["support_interface_spacing"])
        assertEquals("60", result["support_speed"])
    }

    @Test
    fun `buildProfileOverrides omits support speed when zero (auto)`() {
        val cfg = SliceConfig()
        val ov = SlicingOverrides(supportSpeed = OverrideValue(OverrideMode.OVERRIDE, 0))
        val result = buildProfileOverridesImpl(cfg, ov, slotCount = 1, hasSourceConfig = false)
        assertFalse("support_speed=0 means auto — must not be emitted", result.containsKey("support_speed"))
    }

    @Test
    fun `buildProfileOverrides emits tree support params only when tree support is active`() {
        val cfg = SliceConfig()
        val ov = SlicingOverrides(
            supports = OverrideValue(OverrideMode.OVERRIDE, true),
            supportType = OverrideValue(OverrideMode.OVERRIDE, "tree(auto)"),
            treeSupportBranchAngle = OverrideValue(OverrideMode.OVERRIDE, 45),
            treeSupportBranchDistance = OverrideValue(OverrideMode.OVERRIDE, 6.0f),
            treeSupportBranchDiameter = OverrideValue(OverrideMode.OVERRIDE, 2.5f)
        )
        val result = buildProfileOverridesImpl(cfg, ov, slotCount = 1, hasSourceConfig = false)
        assertEquals("45", result["tree_support_branch_angle"])
        assertEquals("6.0", result["tree_support_branch_distance"])
        assertEquals("2.5", result["tree_support_branch_diameter"])
    }

    @Test
    fun `buildProfileOverrides omits tree support params when normal support is active`() {
        val cfg = SliceConfig()
        val ov = SlicingOverrides(
            supports = OverrideValue(OverrideMode.OVERRIDE, true),
            supportType = OverrideValue(OverrideMode.OVERRIDE, "normal(auto)"),
            treeSupportBranchAngle = OverrideValue(OverrideMode.OVERRIDE, 45)
        )
        val result = buildProfileOverridesImpl(cfg, ov, slotCount = 1, hasSourceConfig = false)
        assertFalse("tree params must not appear for normal support", result.containsKey("tree_support_branch_angle"))
    }

    @Test
    fun `F41 F42 serialization round-trip for new override fields`() {
        val original = SlicingOverrides(
            reduceInfillRetraction = OverrideValue(OverrideMode.OVERRIDE, true),
            wallGenerator = OverrideValue(OverrideMode.OVERRIDE, "classic"),
            seamPosition = OverrideValue(OverrideMode.OVERRIDE, "back")
        )
        val restored = SlicingOverrides.fromJson(original.toJson())
        assertEquals(OverrideMode.OVERRIDE, restored.reduceInfillRetraction.mode)
        assertTrue(restored.reduceInfillRetraction.value!!)
        assertEquals(OverrideMode.OVERRIDE, restored.wallGenerator.mode)
        assertEquals("classic", restored.wallGenerator.value)
        assertEquals(OverrideMode.OVERRIDE, restored.seamPosition.mode)
        assertEquals("back", restored.seamPosition.value)
    }

    @Test
    fun `F41 F42 buildProfileOverrides emits new keys`() {
        val cfg = SliceConfig()
        val ov = SlicingOverrides(
            reduceInfillRetraction = OverrideValue(OverrideMode.OVERRIDE, true),
            wallGenerator = OverrideValue(OverrideMode.OVERRIDE, "classic"),
            seamPosition = OverrideValue(OverrideMode.OVERRIDE, "back")
        )
        val result = buildProfileOverridesImpl(cfg, ov, 1)
        assertEquals("1", result["reduce_infill_retraction"])
        assertEquals("classic", result["wall_generator"])
        assertEquals("back", result["seam_position"])
    }

    @Test
    fun `F41 reduceInfillRetraction defaults to false (0)`() {
        val cfg = SliceConfig()
        val ov = SlicingOverrides(
            reduceInfillRetraction = OverrideValue(OverrideMode.ORCA_DEFAULT)
        )
        val result = buildProfileOverridesImpl(cfg, ov, 1)
        assertEquals("0", result["reduce_infill_retraction"])
    }

    @Test
    fun `F42 wallGenerator defaults to arachne`() {
        val cfg = SliceConfig()
        val ov = SlicingOverrides(
            wallGenerator = OverrideValue(OverrideMode.ORCA_DEFAULT)
        )
        val result = buildProfileOverridesImpl(cfg, ov, 1)
        assertEquals("arachne", result["wall_generator"])
    }

    @Test
    fun `F42 seamPosition defaults to aligned`() {
        val cfg = SliceConfig()
        val ov = SlicingOverrides(
            seamPosition = OverrideValue(OverrideMode.ORCA_DEFAULT)
        )
        val result = buildProfileOverridesImpl(cfg, ov, 1)
        assertEquals("aligned", result["seam_position"])
    }

    @Test
    fun `formatFileValue boolean true shows on`() {
        assertEquals("on", formatFileValue(true))
    }

    @Test
    fun `formatFileValue boolean false shows off`() {
        assertEquals("off", formatFileValue(false))
    }

    @Test
    fun `formatFileValue string 1 shows on`() {
        assertEquals("on", formatFileValue("1"))
    }

    @Test
    fun `formatFileValue string 0 shows off`() {
        assertEquals("off", formatFileValue("0"))
    }

    @Test
    fun `formatFileValue percentage passes through`() {
        assertEquals("15%", formatFileValue("15%"))
    }

    @Test
    fun `formatFileValue enum with parens strips parens`() {
        assertEquals("Normal", formatFileValue("normal(auto)"))
    }

    @Test
    fun `formatFileValue underscore enum title-cases`() {
        assertEquals("Aligned Back", formatFileValue("aligned_back"))
        assertEquals("Arachne", formatFileValue("arachne"))
        assertEquals("Gyroid", formatFileValue("gyroid"))
    }

    @Test
    fun `formatFileValue number passes through`() {
        assertEquals("0.2", formatFileValue(0.2f))
        assertEquals("30", formatFileValue(30))
    }

    @Test
    fun `formatFileValue list takes first element`() {
        assertEquals("on", formatFileValue(listOf("1", "1")))
        assertEquals("60", formatFileValue(listOf(60, 60)))
    }

    // --- Korok / SEMM duplicate-slot compact extruder count ---

    @Test
    fun `buildProfileOverrides nozzle_temperature length matches compact extruder count not raw slot list size`() {
        // SEMM colorMapping [0,0,1,1,3] has 5 elements but only 3 unique physical slots.
        // targetCount = distinct().size = 3; buildProfileOverrides must get 3, not 5.
        // Regression: passing raw usedSlots.size (5) resulted in extruders=5 in overrides,
        // but passing usedSlots.distinct().size (3) correctly configures a 3-extruder job.
        val cfg = SliceConfig(nozzleTemp = 220)
        val ov = SlicingOverrides()
        val result = buildProfileOverridesImpl(cfg, ov, slotCount = 3, hasSourceConfig = false)
        @Suppress("UNCHECKED_CAST")
        val temps = result["nozzle_temperature"] as? List<*>
        assertEquals("nozzle_temperature should have 3 entries for 3-extruder SEMM job", 3, temps?.size)
    }

    // --- primeTowerWidth and wipeTowerRotationAngle overrides ---

    @Test
    fun `primeTowerWidth OVERRIDE resolves into wipeTowerWidth`() {
        val base = SliceConfig(wipeTowerWidth = 60f)
        val ov = SlicingOverrides(primeTowerWidth = OverrideValue(OverrideMode.OVERRIDE, 20f))
        val result = ov.resolveInto(base)
        assertEquals(20f, result.wipeTowerWidth, 0.001f)
    }

    @Test
    fun `primeTowerWidth USE_FILE keeps base wipeTowerWidth`() {
        val base = SliceConfig(wipeTowerWidth = 60f)
        val ov = SlicingOverrides(primeTowerWidth = OverrideValue(OverrideMode.USE_FILE))
        val result = ov.resolveInto(base)
        assertEquals(60f, result.wipeTowerWidth, 0.001f)
    }

    @Test
    fun `primeTowerWidth ORCA_DEFAULT uses 35mm`() {
        val base = SliceConfig(wipeTowerWidth = 60f)
        val ov = SlicingOverrides(primeTowerWidth = OverrideValue(OverrideMode.ORCA_DEFAULT))
        val result = ov.resolveInto(base)
        assertEquals(35f, result.wipeTowerWidth, 0.001f)
    }

    @Test
    fun `wipeTowerRotationAngle round-trips through JSON`() {
        val ov = SlicingOverrides(wipeTowerRotationAngle = OverrideValue(OverrideMode.OVERRIDE, 45f))
        val json = ov.toJson()
        val restored = SlicingOverrides.fromJson(json)
        assertEquals(OverrideMode.OVERRIDE, restored.wipeTowerRotationAngle.mode)
        assertEquals(45f, restored.wipeTowerRotationAngle.value!!, 0.001f)
    }

    @Test
    fun `primeTowerWidth round-trips through JSON`() {
        val ov = SlicingOverrides(primeTowerWidth = OverrideValue(OverrideMode.OVERRIDE, 25f))
        val json = ov.toJson()
        val restored = SlicingOverrides.fromJson(json)
        assertEquals(OverrideMode.OVERRIDE, restored.primeTowerWidth.mode)
        assertEquals(25f, restored.primeTowerWidth.value!!, 0.001f)
    }

    @Test
    fun `wipeTowerRotationAngle OVERRIDE emitted in buildProfileOverrides`() {
        val cfg = SliceConfig(wipeTowerEnabled = true, extruderCount = 2)
        val ov = SlicingOverrides(wipeTowerRotationAngle = OverrideValue(OverrideMode.OVERRIDE, 45f))
        val result = buildProfileOverridesImpl(cfg, ov, slotCount = 2)
        assertEquals("45.0", result["wipe_tower_rotation_angle"])
    }

    @Test
    fun `primeTowerWidth OVERRIDE emitted in buildProfileOverrides`() {
        val cfg = SliceConfig(wipeTowerEnabled = true, wipeTowerWidth = 60f, extruderCount = 2)
        val ov = SlicingOverrides(primeTowerWidth = OverrideValue(OverrideMode.OVERRIDE, 20f))
        val result = buildProfileOverridesImpl(cfg, ov, slotCount = 2)
        assertEquals("20.0", result["prime_tower_width"])
    }

    // --- computeTogglePrimeTower (B53: Prepare screen prime tower switch) ---

    @Test
    fun `computeTogglePrimeTower USE_FILE true cfg → OVERRIDE false`() {
        val current = OverrideValue<Boolean>(OverrideMode.USE_FILE)
        val result = computeTogglePrimeTower(current, cfgWipeTower = true)
        assertEquals(OverrideMode.OVERRIDE, result.mode)
        assertEquals(false, result.value)
    }

    @Test
    fun `computeTogglePrimeTower USE_FILE false cfg → OVERRIDE true`() {
        val current = OverrideValue<Boolean>(OverrideMode.USE_FILE)
        val result = computeTogglePrimeTower(current, cfgWipeTower = false)
        assertEquals(OverrideMode.OVERRIDE, result.mode)
        assertEquals(true, result.value)
    }

    @Test
    fun `computeTogglePrimeTower OVERRIDE true → OVERRIDE false`() {
        val current = OverrideValue(OverrideMode.OVERRIDE, true)
        val result = computeTogglePrimeTower(current, cfgWipeTower = false)
        assertEquals(OverrideMode.OVERRIDE, result.mode)
        assertEquals(false, result.value)
    }

    @Test
    fun `computeTogglePrimeTower OVERRIDE false → OVERRIDE true`() {
        val current = OverrideValue(OverrideMode.OVERRIDE, false)
        val result = computeTogglePrimeTower(current, cfgWipeTower = true)
        assertEquals(OverrideMode.OVERRIDE, result.mode)
        assertEquals(true, result.value)
    }

    @Test
    fun `buildProfileOverrides enable_prime_tower 0 when OVERRIDE false multi-extruder`() {
        // After fix, toggling off sets OVERRIDE false — resolvePrimeTower guard is bypassed
        val cfg = SliceConfig(wipeTowerEnabled = true)
        val ov = SlicingOverrides(primeTower = OverrideValue(OverrideMode.OVERRIDE, false))
        val result = buildProfileOverridesImpl(cfg, ov, slotCount = 2)
        assertEquals("enable_prime_tower must be 0 for explicit OVERRIDE false", "0", result["enable_prime_tower"])
    }

    // --- B63: filament_type override tests ---

    @Test
    fun `buildProfileOverrides emits filament_type from extruder presets`() {
        val cfg = SliceConfig()
        val ov = SlicingOverrides()
        val types = listOf("PETG", "ABS", "TPU", "PLA")
        val result = buildProfileOverridesImpl(cfg, ov, slotCount = 4, filamentTypes = types)
        @Suppress("UNCHECKED_CAST")
        val ft = result["filament_type"] as List<String>
        assertEquals(listOf("PETG", "ABS", "TPU", "PLA"), ft)
    }

    @Test
    fun `buildProfileOverrides filament_type defaults to PLA when no types given`() {
        val cfg = SliceConfig()
        val ov = SlicingOverrides()
        val result = buildProfileOverridesImpl(cfg, ov, slotCount = 2)
        @Suppress("UNCHECKED_CAST")
        val ft = result["filament_type"] as List<String>
        assertEquals(listOf("PLA", "PLA"), ft)
    }

    @Test
    fun `buildProfileOverrides filament_type pads short list with PLA`() {
        val cfg = SliceConfig()
        val ov = SlicingOverrides()
        val types = listOf("PETG")
        val result = buildProfileOverridesImpl(cfg, ov, slotCount = 4, filamentTypes = types)
        @Suppress("UNCHECKED_CAST")
        val ft = result["filament_type"] as List<String>
        assertEquals(listOf("PETG", "PLA", "PLA", "PLA"), ft)
    }

    // --- Nozzle temp: extruderTemps populated by applyMultiColorAssignments ---

    @Test
    fun `nozzle_temperature uses extruderTemps when size matches slotCount`() {
        // PETG E1 (235°C) + PLA E2 (210°C) — the normal 2-colour case
        val cfg = SliceConfig(extruderTemps = intArrayOf(235, 210))
        val ov = SlicingOverrides()
        val result = buildProfileOverridesImpl(cfg, ov, slotCount = 2)
        @Suppress("UNCHECKED_CAST")
        val temps = result["nozzle_temperature"] as List<String>
        assertEquals(listOf("235", "210"), temps)
    }

    @Test
    fun `nozzle_temperature falls back to nozzleTemp when extruderTemps empty`() {
        // extruderTemps not yet set (e.g. stale config) → falls back to cfg.nozzleTemp
        val cfg = SliceConfig(nozzleTemp = 210, extruderTemps = intArrayOf())
        val ov = SlicingOverrides()
        val result = buildProfileOverridesImpl(cfg, ov, slotCount = 2)
        @Suppress("UNCHECKED_CAST")
        val temps = result["nozzle_temperature"] as List<String>
        assertEquals(listOf("210", "210"), temps)
    }

    @Test
    fun `nozzle_temperature single-color PETG uses 235 when extruderTemps set`() {
        val cfg = SliceConfig(extruderTemps = intArrayOf(235))
        val ov = SlicingOverrides()
        val result = buildProfileOverridesImpl(cfg, ov, slotCount = 1)
        @Suppress("UNCHECKED_CAST")
        val temps = result["nozzle_temperature"] as List<String>
        assertEquals(listOf("235"), temps)
    }

    @Test
    fun `nozzleTemps param overrides stale extruderTemps — profile linked after model load`() {
        // Simulates: model loaded (extruderTemps=[210] stored), user then links PETG profile,
        // slice fires with fresh nozzleTemps=[235] from buildProfileOverrides at slice time.
        val cfg = SliceConfig(extruderTemps = intArrayOf(210))  // stale load-time value
        val ov = SlicingOverrides()
        val result = buildProfileOverridesImpl(cfg, ov, slotCount = 1, nozzleTemps = listOf(235))
        @Suppress("UNCHECKED_CAST")
        val temps = result["nozzle_temperature"] as List<String>
        assertEquals(listOf("235"), temps)
    }

    @Test
    fun `nozzleTemps param overrides stale extruderTemps — multi-colour case`() {
        val cfg = SliceConfig(extruderTemps = intArrayOf(210, 210))  // stale
        val ov = SlicingOverrides()
        val result = buildProfileOverridesImpl(cfg, ov, slotCount = 2, nozzleTemps = listOf(235, 270))
        @Suppress("UNCHECKED_CAST")
        val temps = result["nozzle_temperature"] as List<String>
        assertEquals(listOf("235", "270"), temps)
    }
    @Test
    fun `resolveInto carries support filament choices into raw STL SliceConfig`() {
        val cfg = SliceConfig(extruderCount = 1)
        val ov = SlicingOverrides(
            supports = OverrideValue(OverrideMode.OVERRIDE, true),
            supportFilament = OverrideValue(OverrideMode.OVERRIDE, 3),
            supportInterfaceFilament = OverrideValue(OverrideMode.OVERRIDE, 4)
        )

        val resolved = ov.resolveInto(cfg)

        assertEquals(true, resolved.supportEnabled)
        assertEquals(3, resolved.supportFilament)
        assertEquals(4, resolved.supportInterfaceFilament)
    }
}
