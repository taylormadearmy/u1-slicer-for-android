package com.u1.slicer

import com.u1.slicer.data.SliceConfig
import com.u1.slicer.data.SlicingOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BuildProfileOverridesMixedFilamentTest {

    @Test
    fun `buildProfileOverrides emits mixed_filament_definitions when field is set`() {
        val cfg = SliceConfig(
            extruderCount = 2,
            mixedFilamentDefinitions = "1,2,1,50",
        )
        val overrides = buildProfileOverridesImpl(
            cfg = cfg,
            ov = SlicingOverrides(),
            slotCount = 2,
        )
        assertEquals("1,2,1,50", overrides["mixed_filament_definitions"])
    }

    @Test
    fun `buildProfileOverrides omits mixed_filament_definitions when field is empty`() {
        val cfg = SliceConfig(extruderCount = 2)  // mixedFilamentDefinitions defaults to ""
        val overrides = buildProfileOverridesImpl(
            cfg = cfg,
            ov = SlicingOverrides(),
            slotCount = 2,
        )
        assertFalse(
            "mixed_filament_definitions must NOT be emitted when SliceConfig field is empty (lets embedded profile value win)",
            overrides.containsKey("mixed_filament_definitions"),
        )
    }
}
