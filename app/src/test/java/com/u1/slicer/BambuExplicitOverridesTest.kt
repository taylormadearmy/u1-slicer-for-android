package com.u1.slicer

import com.u1.slicer.data.OverrideMode
import com.u1.slicer.data.OverrideValue
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.data.SlicingOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BambuExplicitOverridesTest {

    @Test
    fun `use file contributes no inherited u1 scalar defaults`() {
        val profile = buildProfileOverridesImpl(
            cfg = SliceConfig(firstLayerHeight = 0.30f, bedTemp = 60),
            ov = SlicingOverrides(),
            slotCount = 1,
            hasSourceConfig = true,
        )

        val explicit = buildExplicitBambuProfileOverrides(
            profileOverrides = profile,
            overrides = SlicingOverrides(),
            hasFilamentOverrides = false,
        )

        assertTrue(explicit.isEmpty())
        assertFalse(explicit.containsKey("initial_layer_print_height"))
    }

    @Test
    fun `explicit layer and bed overrides become final bambu profile keys`() {
        val overrides = SlicingOverrides(
            layerHeight = OverrideValue(OverrideMode.OVERRIDE, 0.28f),
            bedTemp = OverrideValue(OverrideMode.OVERRIDE, 70),
        )
        val profile = buildProfileOverridesImpl(
            cfg = SliceConfig(layerHeight = 0.28f, bedTemp = 70),
            ov = overrides,
            slotCount = 1,
            hasSourceConfig = true,
        )

        val explicit = buildExplicitBambuProfileOverrides(profile, overrides, false)

        assertEquals("0.28", explicit["layer_height"])
        assertEquals(listOf("70"), explicit["hot_plate_temp"])
        assertEquals(listOf("70"), explicit["textured_plate_temp_initial_layer"])
    }

    @Test
    fun `prepare filament edits opt in only the filament arrays`() {
        val profile = mapOf<String, Any>(
            "filament_type" to listOf("PETG"),
            "filament_colour" to listOf("#112233"),
            "nozzle_temperature" to listOf("235"),
            "nozzle_temperature_initial_layer" to listOf("240"),
            "wall_loops" to "7",
        )

        val explicit = buildExplicitBambuProfileOverrides(
            profileOverrides = profile,
            overrides = SlicingOverrides(),
            hasFilamentOverrides = true,
        )

        assertEquals(4, explicit.size)
        assertFalse(explicit.containsKey("wall_loops"))
    }
}
