package com.u1.slicer.ui

import com.u1.slicer.data.ExtruderPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * B118 unit tests for [resolveSlicedWithMaterial] and the slot-0 default that
 * the Map & Print dialog uses to reconstruct the material the slicer actually
 * ran with.
 *
 * DC15 repro (Discord 2026-05-16, https://discord.com/channels/.../1505313302262321352):
 * load Jumping_frog.3mf (single-colour PLA-declared), with E1 slot preset =
 * Generic PETG. Slicer's resolver cascades override(null) → slotPreset(PETG)
 * → fileDeclared(PLA), so G-code is PETG. The dialog used to skip the slot
 * preset for single-colour files (sliceTimeColorMapping == null), reporting
 * "Sliced as PLA but slot has PETG" when the user mapped to a PETG slot — the
 * opposite direction of reality.
 */
class SlicedWithMaterialTest {

    @Test
    fun `override wins over everything`() {
        assertEquals(
            "ABS",
            resolveSlicedWithMaterial(
                overrideMaterial = "ABS",
                sliceTimeSlotMaterial = "PETG",
                filamentMaterial = "PLA",
            )
        )
    }

    @Test
    fun `sliceTime slot material wins when no override`() {
        assertEquals(
            "PETG",
            resolveSlicedWithMaterial(
                overrideMaterial = null,
                sliceTimeSlotMaterial = "PETG",
                filamentMaterial = "PLA",
            )
        )
    }

    @Test
    fun `file declared material is final fallback`() {
        assertEquals(
            "PLA",
            resolveSlicedWithMaterial(
                overrideMaterial = null,
                sliceTimeSlotMaterial = null,
                filamentMaterial = "PLA",
            )
        )
    }

    @Test
    fun `all null returns null`() {
        assertNull(
            resolveSlicedWithMaterial(
                overrideMaterial = null,
                sliceTimeSlotMaterial = null,
                filamentMaterial = null,
            )
        )
    }

    /**
     * DC15's exact scenario reconstructed from the dialog's inputs.
     *
     * Setup:
     *  - Single-colour Jumping_frog.3mf (canonical filament has PLA declared,
     *    no colorMapping → `sliceTimeColorMapping = null`)
     *  - User has set E1 slot preset to Generic PETG
     *  - User did NOT explicitly tap the Filament chip to set an override
     *
     * The dialog's per-row code at idx=0 computes:
     *  - displayFileIndex = 0
     *  - sliceTimeSlot = sliceTimeColorMapping?.getOrNull(0) ?: 0   // <-- B118 fix
     *  - sliceTimeSlotMaterial = presets.firstOrNull { it.index == 0 }?.materialType
     *  - overrideMaterial = null (user didn't tap)
     *  - filamentMaterial = "PLA" (file's declared)
     *
     * Expected: "PETG" — matching the slicer's resolver, so when the user
     * maps to a non-PETG slot the warning fires correctly.
     */
    @Test
    fun `DC15 single colour file with PETG slot preset`() {
        val presets = listOf(
            ExtruderPreset(index = 0, color = "#174B35", materialType = "PETG"),
            ExtruderPreset(index = 1, color = "#FFFFFF", materialType = "PLA"),
            ExtruderPreset(index = 2, color = "#000000", materialType = "PLA"),
            ExtruderPreset(index = 3, color = "#FF0000", materialType = "PLA"),
        )
        val sliceTimeColorMapping: List<Int>? = null  // single-colour file
        val displayFileIndex = 0

        // Mirror the dialog's slot resolution (the `?: 0` is the B118 fix).
        val sliceTimeSlot = sliceTimeColorMapping?.getOrNull(displayFileIndex) ?: 0
        val sliceTimeSlotMaterial = presets.firstOrNull { it.index == sliceTimeSlot }?.materialType

        val result = resolveSlicedWithMaterial(
            overrideMaterial = null,
            sliceTimeSlotMaterial = sliceTimeSlotMaterial,
            filamentMaterial = "PLA",  // file's declared
        )
        assertEquals("PETG", result)
    }

    /**
     * Regression guard: when the user HAS explicitly set an override, it wins
     * over the slot preset — even on a single-colour file.
     */
    @Test
    fun `explicit override wins on single colour file`() {
        val presets = listOf(
            ExtruderPreset(index = 0, color = "#174B35", materialType = "PETG"),
        )
        val sliceTimeSlot = (null as List<Int>?)?.getOrNull(0) ?: 0
        val sliceTimeSlotMaterial = presets.firstOrNull { it.index == sliceTimeSlot }?.materialType

        val result = resolveSlicedWithMaterial(
            overrideMaterial = "ABS",
            sliceTimeSlotMaterial = sliceTimeSlotMaterial,
            filamentMaterial = "PLA",
        )
        assertEquals("ABS", result)
    }

    /**
     * B121 — support/interface row uses displayFileIndex as default slot, not 0.
     *
     * For an STL sliced with TPU on E1 (canonical index 0) and PLA support on
     * E2 (canonical index 1), the support row's displayFileIndex = 1. Since
     * sliceTimeColorMapping has size 1, getOrNull(1) = null. The B121 fix
     * uses `?: displayFileIndex` (= 1 → E2 = PLA) instead of `?: 0` (= E1 = TPU).
     *
     * Without the fix: sliceTimeSlot = 0 → sliceTimeSlotMaterial = "TPU" →
     * false mismatch "Sliced as TPU but slot has PLA" when user correctly picks E2.
     *
     * With the fix: sliceTimeSlot = 1 → sliceTimeSlotMaterial = "PLA" → no
     * mismatch when user picks E2 = PLA. ✓
     */
    @Test
    fun `B121 support row uses displayFileIndex not slot 0 when colorMapping too short`() {
        val presets = listOf(
            ExtruderPreset(index = 0, color = "#FF0000", materialType = "TPU"),
            ExtruderPreset(index = 1, color = "#AAAAAA", materialType = "PLA"),
            ExtruderPreset(index = 2, color = "#0000FF", materialType = "PLA"),
            ExtruderPreset(index = 3, color = "#FFFFFF", materialType = "PLA"),
        )
        // Single-colour STL: colorMapping has only 1 entry covering the model.
        val sliceTimeColorMapping = listOf(0)
        val displayFileIndex = 1  // support row

        // B121 fix: use displayFileIndex as fallback, not 0.
        val sliceTimeSlot = sliceTimeColorMapping.getOrNull(displayFileIndex) ?: displayFileIndex
        val sliceTimeSlotMaterial = presets.firstOrNull { it.index == sliceTimeSlot }?.materialType

        // sliceTimeSlot should be 1 (E2 = PLA), not 0 (E1 = TPU).
        assertEquals(1, sliceTimeSlot)
        assertEquals("PLA", sliceTimeSlotMaterial)

        // Selecting E2 (slot 1, PLA) for support → no mismatch.
        val selectedSlotMaterial = presets.firstOrNull { it.index == 1 }?.materialType
        val result = resolveSlicedWithMaterial(null, sliceTimeSlotMaterial, filamentMaterial = "PLA")
        assertEquals("PLA", result)
        assertEquals(result, selectedSlotMaterial)  // no mismatch
    }

    /**
     * Regression guard: multi-colour file with explicit colorMapping uses
     * the mapped slot's material, not the slot-0 default.
     */
    @Test
    fun `multi colour file uses mapped slot not slot zero`() {
        val presets = listOf(
            ExtruderPreset(index = 0, color = "#FFFFFF", materialType = "PLA"),
            ExtruderPreset(index = 1, color = "#00FF00", materialType = "PETG"),
            ExtruderPreset(index = 2, color = "#FF0000", materialType = "ABS"),
            ExtruderPreset(index = 3, color = "#0000FF", materialType = "TPU"),
        )
        // canonical[0] → slot 2 (ABS), canonical[1] → slot 1 (PETG)
        val sliceTimeColorMapping = listOf(2, 1)

        // Row 0 — canonical fileIdx 0
        val slot0 = sliceTimeColorMapping.getOrNull(0) ?: 0
        val mat0 = presets.firstOrNull { it.index == slot0 }?.materialType
        assertEquals(
            "ABS",
            resolveSlicedWithMaterial(null, mat0, filamentMaterial = "PLA")
        )

        // Row 1 — canonical fileIdx 1
        val slot1 = sliceTimeColorMapping.getOrNull(1) ?: 0
        val mat1 = presets.firstOrNull { it.index == slot1 }?.materialType
        assertEquals(
            "PETG",
            resolveSlicedWithMaterial(null, mat1, filamentMaterial = "PLA")
        )
    }
}
