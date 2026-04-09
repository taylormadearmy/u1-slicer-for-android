package com.u1.slicer

import com.u1.slicer.data.ExtruderPreset
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewSummaryMappingTest {

    @Test
    fun `buildPerExtruderDisplaySlots prioritizes mapped physical slots`() {
        // Compact tools [0,1] mapped to physical E3,E2 (slots 2,1).
        val slots = buildPerExtruderDisplaySlots(
            count = 3,
            colorMapping = listOf(2, 1)
        )
        assertEquals(listOf(2, 1, 0), slots)
    }

    @Test
    fun `buildPerExtruderDisplaySlots falls back to identity when no mapping`() {
        val slots = buildPerExtruderDisplaySlots(
            count = 3,
            colorMapping = null
        )
        assertEquals(listOf(0, 1, 2), slots)
    }

    // --- resolveExtruderMaterialType (F65) ---

    private val testPresets = listOf(
        ExtruderPreset(0, materialType = "PLA"),
        ExtruderPreset(1, materialType = "PETG"),
        ExtruderPreset(2, materialType = "ABS"),
        ExtruderPreset(3, materialType = "TPU")
    )

    @Test
    fun `resolveExtruderMaterialType returns PLA for slot 0`() {
        assertEquals("PLA", resolveExtruderMaterialType(0, testPresets))
    }

    @Test
    fun `resolveExtruderMaterialType returns PETG for slot 1`() {
        assertEquals("PETG", resolveExtruderMaterialType(1, testPresets))
    }

    @Test
    fun `resolveExtruderMaterialType returns empty for unknown slot`() {
        assertEquals("", resolveExtruderMaterialType(5, testPresets))
    }

    @Test
    fun `resolveExtruderMaterialType returns empty for empty presets`() {
        assertEquals("", resolveExtruderMaterialType(0, emptyList()))
    }

    // --- F68: single-colour slice must show material type label ---

    @Test
    fun `single colour slice shows material type label`() {
        // Single-extruder summary: perExtruderFilamentMm has exactly 1 entry.
        // The guard condition `size > 1` was suppressing this path — it must be `isNotEmpty()`.
        val slots = buildPerExtruderDisplaySlots(
            count = 1,
            colorMapping = null
        )
        assertEquals("single-colour must produce one display slot", 1, slots.size)
        val materialType = resolveExtruderMaterialType(slots[0], testPresets)
        assertEquals("single-colour slot 0 must resolve to PLA", "PLA", materialType)
    }
}
