package com.u1.slicer

import com.u1.slicer.data.ExtruderPreset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Wiring tests for B59: verifies that the three ViewModel code paths that
 * update config.filamentType derive the correct slots and call
 * resolveFilamentTypeLabel correctly.
 *
 * Each path is covered by an extracted internal function:
 *
 *   resolveFilamentTypeForSingleColorLoad(presets)
 *     → always uses slot 0 (E1) — the physical extruder for a single-color model
 *
 *   resolveFilamentTypeLabelFromMapping(colorMapping, presets)
 *     → derives usedSlots = colorMapping.distinct().sorted(), then delegates
 *     → used by both applyMultiColorAssignments and the layer-tool branch
 */
class FilamentTypeWiringTest {

    private val presets = listOf(
        ExtruderPreset(0, materialType = "PLA"),
        ExtruderPreset(1, materialType = "PETG"),
        ExtruderPreset(2, materialType = "PETG"),
        ExtruderPreset(3, materialType = "ABS")
    )

    // ── Gap 1: single-color initial load ─────────────────────────────────────
    // The ViewModel sets selectedExtruder=0 and calls this path.
    // Wiring must use slot 0, not some other index.

    @Test
    fun `single-color initial load uses E1 material`() {
        val allPetgPresets = listOf(
            ExtruderPreset(0, materialType = "PETG"),
            ExtruderPreset(1, materialType = "PLA")
        )
        assertEquals("PETG", resolveFilamentTypeForSingleColorLoad(allPetgPresets))
    }

    @Test
    fun `single-color initial load uses E1 even when other slots differ`() {
        // Slot 0 = PLA; even though slots 1-3 are PETG/ABS, E1 is what matters
        assertEquals("PLA", resolveFilamentTypeForSingleColorLoad(presets))
    }

    @Test
    fun `single-color initial load falls back to PLA when presets empty`() {
        assertEquals("PLA", resolveFilamentTypeForSingleColorLoad(emptyList()))
    }

    // ── Gap 2: applyMultiColorAssignments ─────────────────────────────────────
    // usedSlots = colorMapping.distinct().sorted()

    @Test
    fun `multi-color mapping E1+E2 both PETG returns PETG`() {
        val petgPresets = listOf(
            ExtruderPreset(0, materialType = "PETG"),
            ExtruderPreset(1, materialType = "PETG")
        )
        // colorMapping=[0,1] → usedSlots=[0,1]
        assertEquals("PETG", resolveFilamentTypeLabelFromMapping(listOf(0, 1), petgPresets))
    }

    @Test
    fun `multi-color mapping E1 PLA + E2 PETG returns Mixed`() {
        // colorMapping=[0,1] → usedSlots=[0,1] → PLA+PETG → Mixed
        assertEquals("Mixed", resolveFilamentTypeLabelFromMapping(listOf(0, 1), presets))
    }

    @Test
    fun `multi-color mapping deduplicates slots before resolving`() {
        // colorMapping=[0,0,1,1] → usedSlots=[0,1] (deduplicated)
        assertEquals("Mixed", resolveFilamentTypeLabelFromMapping(listOf(0, 0, 1, 1), presets))
    }

    @Test
    fun `multi-color mapping sorts slots before resolving`() {
        // colorMapping=[1,0] → usedSlots=[0,1] (sorted) — same result as [0,1]
        assertEquals("Mixed", resolveFilamentTypeLabelFromMapping(listOf(1, 0), presets))
    }

    @Test
    fun `multi-color mapping with single unique slot returns that material`() {
        // colorMapping=[2,2,2] → usedSlots=[2] → PETG
        assertEquals("PETG", resolveFilamentTypeLabelFromMapping(listOf(2, 2, 2), presets))
    }

    // ── Gap 3: layer-tool branch ──────────────────────────────────────────────
    // previewSlots = initialMapping.distinct().sorted()
    // Same function as Gap 2 — layer-tool uses the same slot-derivation logic.

    @Test
    fun `layer-tool single-slot mapping returns that material`() {
        // Layer-tool: all model colors map to E1 (PLA)
        assertEquals("PLA", resolveFilamentTypeLabelFromMapping(listOf(0, 0), presets))
    }

    @Test
    fun `layer-tool two-slot mapping with same material returns that material`() {
        val petgPresets = listOf(
            ExtruderPreset(0, materialType = "PETG"),
            ExtruderPreset(1, materialType = "PETG"),
            ExtruderPreset(2, materialType = "PETG"),
            ExtruderPreset(3, materialType = "PETG")
        )
        assertEquals("PETG", resolveFilamentTypeLabelFromMapping(listOf(0, 1), petgPresets))
    }
}
