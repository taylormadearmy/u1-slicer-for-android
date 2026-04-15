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

    // --- B65: per-extruder colour swatches with high-index slots (e.g. calicube E3+E4) ---

    @Test
    fun `color lookup uses physical slot index into sparse extruderColors list`() {
        // calicube uses E3+E4 (physical slots 2 and 3).
        // buildPreviewSlotColors returns ["", "", "#0000FF", "#FF0000"] — 4 elements,
        // E1/E2 empty. displaySlots must return [2,3] and getOrNull(2)/getOrNull(3)
        // must resolve to the correct hex values — NOT grey.
        val colorMapping = listOf(2, 3)
        val slots = buildPerExtruderDisplaySlots(count = 2, colorMapping = colorMapping)
        assertEquals(listOf(2, 3), slots)

        // Simulate the sparse activeExtruderColors list (not filtered).
        val extruderColors = listOf("", "", "#0000FF", "#FF0000")
        val color0 = extruderColors.getOrNull(slots[0])?.takeIf { it.isNotBlank() } ?: "#808080"
        val color1 = extruderColors.getOrNull(slots[1])?.takeIf { it.isNotBlank() } ?: "#808080"
        assertEquals("E3 color must be blue, not grey", "#0000FF", color0)
        assertEquals("E4 color must be red, not grey", "#FF0000", color1)
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
