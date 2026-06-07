package com.u1.slicer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [buildCanonicalNonMmuPalette] — the Prepare-preview palette mapping for the
 * non-MMU canonical (3MF per-object extruder) path. Review finding #1 (2026-06-07): a canonical
 * 3MF with an object assigned to a mix slot rendered grey because the mix id fell out of the
 * canonical palette bounds. These tests pin the fix (mix → blend colour) and document the
 * finding #2 collision boundary (mix base floored at the palette size).
 */
class BuildCanonicalNonMmuPaletteTest {

    private val NUM_PHYSICAL = 4

    @Test fun allPhysical_identityMapping() {
        val palette = listOf("#FF0000", "#00FF00", "#0000FF")
        val result = buildCanonicalNonMmuPalette(
            sourceExtruders = listOf(1, 2, 3),
            fullPalette = palette,
            mixColors = emptyList(),
            numPhysical = NUM_PHYSICAL,
        )
        assertEquals(listOf("#FF0000", "#00FF00", "#0000FF"), result)
    }

    @Test fun sparsePhysical_picksByIndex() {
        val palette = listOf("#FF0000", "#00FF00", "#0000FF", "#FFFF00")
        // Only file filaments 3 + 4 are used.
        val result = buildCanonicalNonMmuPalette(
            sourceExtruders = listOf(3, 4),
            fullPalette = palette,
            mixColors = emptyList(),
            numPhysical = NUM_PHYSICAL,
        )
        assertEquals(listOf("#0000FF", "#FFFF00"), result)
    }

    @Test fun objectAssignedMix_rendersBlendNotGrey() {
        // Finding #1: physical filament 1 + a mix (1-based id 5 = numPhysical + 0 + 1).
        val palette = listOf("#FF0000", "#00FF00", "#0000FF", "#FFFF00")
        val result = buildCanonicalNonMmuPalette(
            sourceExtruders = listOf(1, 5),
            fullPalette = palette,
            mixColors = listOf("#ABCDEF"),
            numPhysical = NUM_PHYSICAL,
        )
        assertEquals(listOf("#FF0000", "#ABCDEF"), result)
    }

    @Test fun secondMix_picksSecondBlend() {
        val palette = listOf("#FF0000", "#00FF00", "#0000FF", "#FFFF00")
        // mix-1 → 1-based id 6.
        val result = buildCanonicalNonMmuPalette(
            sourceExtruders = listOf(6),
            fullPalette = palette,
            mixColors = listOf("#111111", "#222222"),
            numPhysical = NUM_PHYSICAL,
        )
        assertEquals(listOf("#222222"), result)
    }

    @Test fun mixBeyondMixColors_fallsBackToGrey() {
        val palette = listOf("#FF0000", "#00FF00", "#0000FF", "#FFFF00")
        val result = buildCanonicalNonMmuPalette(
            sourceExtruders = listOf(7), // mix-2, but only one mix colour supplied
            fullPalette = palette,
            mixColors = listOf("#111111"),
            numPhysical = NUM_PHYSICAL,
        )
        assertEquals(listOf("#888888"), result)
    }

    @Test fun morePhysicalThanBase_doesNotMisclassifyAsMix() {
        // Finding #2 boundary: a 3MF declaring 5 canonical filaments. File filament 5 (id 5)
        // must resolve to its physical colour, NOT mix-0 — the mix base is floored at the
        // palette size (5), so idx0=4 < 5 → physical.
        val palette = listOf("#FF0000", "#00FF00", "#0000FF", "#FFFF00", "#FF00FF")
        val result = buildCanonicalNonMmuPalette(
            sourceExtruders = listOf(5),
            fullPalette = palette,
            mixColors = listOf("#ABCDEF"),
            numPhysical = NUM_PHYSICAL,
        )
        assertEquals(listOf("#FF00FF"), result)
    }
}
