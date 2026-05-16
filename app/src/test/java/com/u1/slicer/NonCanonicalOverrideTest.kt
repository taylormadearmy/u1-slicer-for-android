package com.u1.slicer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B117 unit tests for [applyNonCanonicalOverride]. Guards the Prepare-screen
 * material override on non-canonical files (STL, single-colour 3MF without
 * per-filament metadata).
 *
 * Repro: DC15 reported in Discord (2026-05-16) that single-colour 3MFs and
 * STLs had no way to change filament type on the Prepare screen — they
 * could only print whatever the file declared (typically PLA). The fix wires
 * `_filamentOverrides[0]` through the slicer's slot-type/temp arrays.
 *
 * These tests cover the helper function in isolation; an instrumented test
 * covers the full slice → G-code header round-trip.
 */
class NonCanonicalOverrideTest {

    @Test fun nullOverride_returnsInputsUnchanged() {
        val (types, temps) = applyNonCanonicalOverride(
            slotTypes = listOf("PLA"),
            slotTemps = listOf(220),
            override = null,
        )
        assertEquals(listOf("PLA"), types)
        assertEquals(listOf(220), temps)
    }

    @Test fun overrideWithoutMaterialType_returnsInputsUnchanged() {
        // FilamentOverride with only a color (no material) shouldn't touch the type/temp.
        val (types, temps) = applyNonCanonicalOverride(
            slotTypes = listOf("PLA"),
            slotTemps = listOf(220),
            override = SlicerViewModel.FilamentOverride(color = "#FF0000", materialType = null),
        )
        assertEquals(listOf("PLA"), types)
        assertEquals(listOf(220), temps)
    }

    @Test fun materialOverride_replacesFirstSlotTypeAndTemp() {
        val (types, temps) = applyNonCanonicalOverride(
            slotTypes = listOf("PLA"),
            slotTemps = listOf(220),
            override = SlicerViewModel.FilamentOverride(materialType = "PETG"),
        )
        assertEquals(listOf("PETG"), types)
        assertEquals(listOf(235), temps)  // PETG default
    }

    @Test fun materialOverride_onMultiSlotArrays_replacesOnlyFirst() {
        // STL with support_filament / interface_filament padding produces N-entry
        // arrays from `applyNonCanonicalOverride`'s caller — the override should
        // only touch the FIRST entry (the file's primary filament). Trailing
        // entries are for support material on different slots.
        val (types, temps) = applyNonCanonicalOverride(
            slotTypes = listOf("PLA", "PLA", "ABS"),
            slotTemps = listOf(220, 220, 250),
            override = SlicerViewModel.FilamentOverride(materialType = "PETG"),
        )
        assertEquals(listOf("PETG", "PLA", "ABS"), types)
        assertEquals(listOf(235, 220, 250), temps)
    }

    @Test fun materialOverride_onEmptyArrays_isNoOp() {
        // Defensive: zero-length slotTypes shouldn't crash or invent entries.
        val (types, temps) = applyNonCanonicalOverride(
            slotTypes = emptyList(),
            slotTemps = emptyList(),
            override = SlicerViewModel.FilamentOverride(materialType = "PETG"),
        )
        assertEquals(emptyList<String>(), types)
        assertEquals(emptyList<Int>(), temps)
    }

    @Test fun allMaterials_resolveToTheirDefaultTemps() {
        // Pin each supported material → its default nozzle temp. If
        // nozzleTempDefaultForMaterial gains a new material the test will
        // surface it here as a missing entry instead of a silent slice bug.
        // Pinned to actual values in `nozzleTempDefaultForMaterial`. Update this
        // table in lockstep with that function — the goal is to surface accidental
        // changes to either side.
        val cases = listOf(
            "PLA"  to 220,
            "PETG" to 235,
            "ABS"  to 270,
            "TPU"  to 225,
            "PA"   to 260,
            "PVA"  to 210,
        )
        for ((material, expectedTemp) in cases) {
            val (types, temps) = applyNonCanonicalOverride(
                slotTypes = listOf("PLA"),
                slotTemps = listOf(220),
                override = SlicerViewModel.FilamentOverride(materialType = material),
            )
            assertEquals("type for $material", listOf(material), types)
            assertEquals("temp for $material", listOf(expectedTemp), temps)
        }
    }

    @Test fun unknownMaterial_fallsBackToPlaDefault() {
        // If the user-supplied material isn't in nozzleTempDefaultForMaterial's
        // table the slicer should still emit a printable temp (PLA 220°C).
        val (types, temps) = applyNonCanonicalOverride(
            slotTypes = listOf("PLA"),
            slotTemps = listOf(220),
            override = SlicerViewModel.FilamentOverride(materialType = "NEVER_HEARD_OF_IT"),
        )
        assertEquals(listOf("NEVER_HEARD_OF_IT"), types)
        // nozzleTempDefaultForMaterial returns 220 (PLA fallback) for unknown materials.
        assertEquals(listOf(220), temps)
    }
}
