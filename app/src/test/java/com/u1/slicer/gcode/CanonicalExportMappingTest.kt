package com.u1.slicer.gcode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 2 (2026-04-28) — covers `resolveCanonicalExportMapping`, the
 * pure resolver behind every printer-bound G-code export path (Send,
 * Save, Share, Jobs share). The 3rd-party adversarial review found 3
 * P1 leaks where canonical-fileIndex G-code reached the printer; the
 * resolver centralises the four input cases so the leak class becomes
 * a unit-test surface.
 *
 * Spec: docs/superpowers/specs/2026-04-28-canonical-export-mapping-helper-design.md
 */
class CanonicalExportMappingTest {

    @Test
    fun fullCanonicalMapping_returnedAsIs() {
        // Case 1: user confirmed a full canonical mapping via the
        // Filament Mapping dialog. canonical size 4, confirmed mapping
        // assigns each file filament to a specific physical slot.
        val result = resolveCanonicalExportMapping(
            canonicalSize = 4,
            confirmedMapping = listOf(2, 0, 1, 3),
            selectedExtruder = 0,
        )
        assertEquals(listOf(2, 0, 1, 3), result)
    }

    @Test
    fun plateNarrowedMapping_expandsToCanonicalSizeWithModFourFallback() {
        // Case 2: 10-filament file, 2-colour plate selected. Auto-derived
        // mapping is 2-wide. The remap must still produce a 10-wide
        // mapping so any out-of-plate canonical T-index still resolves
        // to a valid physical slot via mod-4 fallback.
        val result = resolveCanonicalExportMapping(
            canonicalSize = 10,
            confirmedMapping = listOf(0, 1),
            selectedExtruder = 0,
        )
        assertEquals(
            listOf(0, 1, 2, 3, 0, 1, 2, 3, 0, 1),
            result,
        )
    }

    @Test
    fun singleColourSelectedSlot_mapsT0ToSelectedExtruder() {
        // Case 3: STL or single-colour 3MF; user selected E3 (index 2)
        // as the destination slot. _colorMapping is null in this case;
        // the user's choice lives in _selectedExtruder.
        val result = resolveCanonicalExportMapping(
            canonicalSize = 1,
            confirmedMapping = null,
            selectedExtruder = 2,
        )
        assertEquals(listOf(2), result)
    }

    @Test
    fun singleColourDefaultE1_mapsT0ToSlot0() {
        val result = resolveCanonicalExportMapping(
            canonicalSize = 1,
            confirmedMapping = null,
            selectedExtruder = 0,
        )
        assertEquals(listOf(0), result)
    }

    @Test
    fun singleColourSelectedExtruderClampedToPhysicalRange() {
        // Defensive: even if _selectedExtruder somehow holds an out-of-
        // range value, the resolver clamps to 0..3 (U1's physical slot
        // range).
        val result = resolveCanonicalExportMapping(
            canonicalSize = 1,
            confirmedMapping = null,
            selectedExtruder = 99,
        )
        assertEquals(listOf(3), result)
    }

    @Test
    fun noCanonicalContext_returnsNullForIdentityCopy() {
        // Case 4: legacy / unrecognised file with no canonical filament
        // list. Caller should treat null as "skip remap, just copy".
        val result = resolveCanonicalExportMapping(
            canonicalSize = 0,
            confirmedMapping = null,
            selectedExtruder = 0,
        )
        assertNull(result)
    }

    @Test
    fun multiColourNoMappingYet_fallsBackToIdentityModFour() {
        // Edge case: canonical filament list loaded (e.g. 5 file
        // filaments) but the user hasn't gone through any dialog yet
        // and _colorMapping is null. Use identity-mod-4 so an
        // accidental Save/Share before Send still produces a printable
        // file (vs. raw canonical T-indices the firmware can't
        // execute).
        val result = resolveCanonicalExportMapping(
            canonicalSize = 5,
            confirmedMapping = null,
            selectedExtruder = 0,
        )
        assertEquals(listOf(0, 1, 2, 3, 0), result)
    }

    @Test
    fun emptyConfirmedMapping_treatedAsAbsent() {
        // Defensive: an explicitly empty mapping should behave like
        // null — falls through to the no-mapping branch.
        val result = resolveCanonicalExportMapping(
            canonicalSize = 4,
            confirmedMapping = emptyList(),
            selectedExtruder = 0,
        )
        assertEquals(listOf(0, 1, 2, 3), result)
    }

    @Test
    fun confirmedMappingLargerThanCanonical_truncatesToCanonical() {
        // Defensive: stale mapping outliving a fixture switch. Truncate
        // rather than overrun.
        val result = resolveCanonicalExportMapping(
            canonicalSize = 3,
            confirmedMapping = listOf(2, 0, 1, 3, 0),
            selectedExtruder = 0,
        )
        assertEquals(listOf(2, 0, 1), result)
    }

    @Test
    fun plateNarrowedMappingExactlyMatchesCanonical_returnedAsIs() {
        // Boundary: confirmed mapping size == canonical size means no
        // expansion needed, identity returned.
        val result = resolveCanonicalExportMapping(
            canonicalSize = 4,
            confirmedMapping = listOf(0, 1, 2, 3),
            selectedExtruder = 0,
        )
        assertEquals(listOf(0, 1, 2, 3), result)
    }

    @Test
    fun plateNarrowedMappingWithFileIndexKeys_appliesAtCorrectCanonicalIndices() {
        // Phase 2 (post-round-2-review, Reviewer 3 P1) — multi-plate
        // file with 10 canonical filaments, plate uses file filaments
        // 1 + 2 (0-indexed: 0 + 1). User maps plate-filament 0 → E2
        // (slot 1) and plate-filament 1 → E1 (slot 0).
        //
        // Pre-fix the resolver did positional expansion: result[0]=1,
        // result[1]=0, then mod-4 for the rest. That's the "correct"
        // shape for THIS plate but only because plate filaments 0 + 1
        // happen to be at canonical fileIndices 0 + 1.
        //
        // The real failure mode (per Reviewer 3): a plate using file
        // filaments 8 + 9 (canonical fileIndices 7 + 8). Pre-fix would
        // emit result[0]=slot for plate-fil 0, result[1]=slot for
        // plate-fil 1, then result[2..9]=mod-4. The slicer body emits
        // T7 + T8 for those filaments — those would land on result[7]
        // and result[8], which the pre-fix populates with arbitrary
        // mod-4 values, NOT the user's confirmed slot picks. Post-fix:
        // the byFileIdx map ensures the user's mapping lands at the
        // right canonical fileIndex.
        val result = resolveCanonicalExportMapping(
            canonicalSize = 10,
            confirmedMapping = listOf(1, 0),
            selectedExtruder = 0,
            confirmedMappingFileIndices = listOf(7, 8),  // plate uses canonical fileIdx 7 + 8
        )
        // result[7] = slot 1 (user's pick for plate-filament 0 = canonical 7)
        // result[8] = slot 0 (user's pick for plate-filament 1 = canonical 8)
        // Other indices: mod-4 fallback (unmapped, won't be emitted by slicer)
        assertEquals(1, result?.get(7))
        assertEquals(0, result?.get(8))
        // Verify mod-4 default at unmapped indices
        assertEquals(0, result?.get(0))  // 0 % 4
        assertEquals(1, result?.get(1))
        assertEquals(2, result?.get(2))
        assertEquals(3, result?.get(3))
    }

    @Test
    fun plateNarrowedMappingWithFileIndexKeys_outOfRangeFileIdxIgnored() {
        // Defensive: stale plate metadata claims a canonical fileIdx
        // beyond canonicalSize. The resolver should silently drop the
        // entry rather than throwing or producing a sparse list.
        val result = resolveCanonicalExportMapping(
            canonicalSize = 4,
            confirmedMapping = listOf(0, 1, 2),
            selectedExtruder = 0,
            confirmedMappingFileIndices = listOf(0, 1, 99),  // last entry out of range
        )
        // First two land correctly, third is dropped.
        assertEquals(0, result?.get(0))
        assertEquals(1, result?.get(1))
        // Index 2 falls back to mod-4 = 2 (untouched by mapping).
        assertEquals(2, result?.get(2))
    }

    @Test
    fun confirmedMappingValuesClampedToPhysicalRange() {
        // Phase 2 (post-round-2-review, Reviewer 1 hardening) —
        // confirmedMapping entries must be coerced to 0..3 so a stale
        // / malformed entry can't produce out-of-range T<n> on the
        // printer-bound output.
        val result = resolveCanonicalExportMapping(
            canonicalSize = 3,
            confirmedMapping = listOf(99, -5, 7),
            selectedExtruder = 0,
        )
        assertEquals(listOf(3, 0, 3), result)  // clamped to 0..3
    }
}
