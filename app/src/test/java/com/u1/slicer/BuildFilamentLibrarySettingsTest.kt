package com.u1.slicer

import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.FilamentProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildFilamentLibrarySettingsTest {

    private val plaProfile = FilamentProfile(
        id = 1L, name = "PLA Pro", material = "PLA",
        nozzleTemp = 220, bedTemp = 60,
        retractLength = 0.8f, retractSpeed = 45f,
        flowRatio = 0.98f,
        maxVolumetricSpeed = 18f,
        fanMinSpeed = 100,
        fanMaxSpeed = 100,
        pressureAdvance = 0.045f,
        enablePressureAdvance = true,
    )

    private val petgProfile = FilamentProfile(
        id = 2L, name = "PETG-CF", material = "PETG",
        nozzleTemp = 250, bedTemp = 75,
        retractLength = 1.2f, retractSpeed = 40f,
        flowRatio = 0.94f,
        maxVolumetricSpeed = 8f,
    )

    @Test
    fun emptyWhenNoFilamentsLinked() {
        val presets = listOf(ExtruderPreset(0, "#FFFFFF", "PLA"))
        val result = buildFilamentLibrarySettings(
            slotCount = 1, usedSlots = null, presets = presets, filaments = emptyList()
        )
        assertTrue("no keys emitted when no slot has a linked filament", result.isEmpty())
    }

    @Test
    fun singleSlot_emitsConfiguredKeysOnly() {
        val presets = listOf(ExtruderPreset(0, "#FFFFFF", "PLA", filamentProfileId = 1L))
        val result = buildFilamentLibrarySettings(
            slotCount = 1, usedSlots = null,
            presets = presets, filaments = listOf(plaProfile)
        )
        assertEquals(listOf("0.98"), result["filament_flow_ratio"])
        assertEquals(listOf("18.0"), result["filament_max_volumetric_speed"])
        assertEquals(listOf("100"), result["fan_min_speed"])
        assertEquals(listOf("100"), result["fan_max_speed"])
        assertEquals(listOf("1"), result["enable_pressure_advance"])
        assertEquals(listOf("0.045"), result["pressure_advance"])
        // Fields the PLA profile doesn't set → omitted (not present in map).
        assertFalse(result.containsKey("filament_cost"))
        assertFalse(result.containsKey("slow_down_layer_time"))
        assertFalse(result.containsKey("close_fan_the_first_x_layers"))
    }

    @Test
    fun multiSlot_perSlotArray_alignedToPhysicalSlots() {
        // Slot 0 → PLA, Slot 1 → PETG, no usedSlots remap.
        val presets = listOf(
            ExtruderPreset(0, "#FFFFFF", "PLA", filamentProfileId = 1L),
            ExtruderPreset(1, "#FFFFFF", "PETG", filamentProfileId = 2L),
        )
        val result = buildFilamentLibrarySettings(
            slotCount = 2, usedSlots = null,
            presets = presets, filaments = listOf(plaProfile, petgProfile)
        )
        assertEquals(listOf("0.98", "0.94"), result["filament_flow_ratio"])
        assertEquals(listOf("18.0", "8.0"), result["filament_max_volumetric_speed"])
    }

    @Test
    fun partialSet_forwardBackFills_neighbourValue() {
        // Slot 0 → linked PLA (max_vol=18), Slot 1 → linked PETG without max_vol set
        // (we use a PETG variant with maxVolumetricSpeed=null).
        val petgNoMax = petgProfile.copy(maxVolumetricSpeed = null)
        val presets = listOf(
            ExtruderPreset(0, "#FFFFFF", "PLA", filamentProfileId = 1L),
            ExtruderPreset(1, "#FFFFFF", "PETG", filamentProfileId = 2L),
        )
        val result = buildFilamentLibrarySettings(
            slotCount = 2, usedSlots = null,
            presets = presets, filaments = listOf(plaProfile, petgNoMax)
        )
        // Slot 1 inherits slot 0's value via forward-fill (not OrcaSlicer-broken empty entry).
        assertEquals(listOf("18.0", "18.0"), result["filament_max_volumetric_speed"])
    }

    @Test
    fun usedSlotsRemap_resolvesByPhysicalSlot() {
        // Compact list says model uses physical slot 2 (E3) only. extruderPresets has E3 → PETG.
        val presets = listOf(
            ExtruderPreset(0, "#FFFFFF", "PLA", filamentProfileId = 1L),
            ExtruderPreset(1, "#FFFFFF", "PLA", filamentProfileId = 1L),
            ExtruderPreset(2, "#FFFFFF", "PETG", filamentProfileId = 2L),
        )
        val result = buildFilamentLibrarySettings(
            slotCount = 1, usedSlots = listOf(2),
            presets = presets, filaments = listOf(plaProfile, petgProfile)
        )
        assertEquals(listOf("0.94"), result["filament_flow_ratio"])
        assertEquals(listOf("8.0"), result["filament_max_volumetric_speed"])
    }

    @Test
    fun enablePressureAdvance_falseAlsoEmittedAsZero() {
        val falsePa = plaProfile.copy(enablePressureAdvance = false)
        val presets = listOf(ExtruderPreset(0, "#FFFFFF", "PLA", filamentProfileId = 1L))
        val result = buildFilamentLibrarySettings(
            slotCount = 1, usedSlots = null, presets = presets, filaments = listOf(falsePa)
        )
        assertEquals(listOf("0"), result["enable_pressure_advance"])
    }

    @Test
    fun slotCountZero_returnsEmpty() {
        val result = buildFilamentLibrarySettings(
            slotCount = 0, usedSlots = null, presets = emptyList(), filaments = emptyList()
        )
        assertTrue(result.isEmpty())
    }
}
