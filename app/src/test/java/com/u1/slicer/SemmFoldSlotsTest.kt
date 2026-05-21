package com.u1.slicer

import com.u1.slicer.data.CanonicalFilamentList
import com.u1.slicer.data.FilamentEntry
import com.u1.slicer.data.FilamentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * B123 — unit tests for [computeSemmFoldSlots].
 *
 * The fold compresses T(N)..T(canonical-1) back to T(i%N) for SEMM models
 * where the slicer emits more tool indices than the user's colorMapping declares.
 *
 * Two shapes:
 * - Shape A: canonical has FILE_COLOUR + PAINT_DERIVED entries; cm.size == base count.
 * - Shape B: all canonical entries are FILE_COLOUR but cm.size < canonical.size
 *   (colored_3DBenchy: 10 FILE_COLOUR, cm=[0,1,2,3] → fold T4-T9 → T(i%4)).
 */
class SemmFoldSlotsTest {

    private fun canonical(fileCounts: Int, paintDerivedCounts: Int = 0): CanonicalFilamentList {
        val entries = (0 until fileCounts).map { i ->
            FilamentEntry(fileIndex = i, color = "#FFFFFF", materialType = null, source = FilamentSource.FILE_COLOUR)
        } + (0 until paintDerivedCounts).map { i ->
            FilamentEntry(
                fileIndex = fileCounts + i,
                color = "#808080",
                materialType = null,
                source = FilamentSource.PAINT_DERIVED,
            )
        }
        return CanonicalFilamentList(filaments = entries)
    }

    // --- Shape B (B123): all FILE_COLOUR, cm.size < canonical.size ---

    @Test
    fun `coloredBenchy shape B — 10 FILE_COLOUR entries cm size 4 folds to 4 base slots`() {
        val list = canonical(fileCounts = 10)
        val cm = listOf(0, 1, 2, 3)
        val result = computeSemmFoldSlots(cm, list)
        assertEquals(listOf(0, 1, 2, 3, 0, 1, 2, 3, 0, 1), result)
    }

    @Test
    fun `shape B — T0-T5 with cm size 3 folds to base 3`() {
        val list = canonical(fileCounts = 6)
        val cm = listOf(0, 1, 2)
        val result = computeSemmFoldSlots(cm, list)
        assertEquals(listOf(0, 1, 2, 0, 1, 2), result)
    }

    // --- Shape A: PAINT_DERIVED entries exist ---

    @Test
    fun `shape A — 4 FILE_COLOUR plus 6 PAINT_DERIVED folds to base 4`() {
        val list = canonical(fileCounts = 4, paintDerivedCounts = 6)
        val cm = listOf(0, 1, 2, 3)
        val result = computeSemmFoldSlots(cm, list)
        assertEquals(listOf(0, 1, 2, 3, 0, 1, 2, 3, 0, 1), result)
    }

    // --- No-fold cases ---

    @Test
    fun `canonical size equals cm size — no fold needed`() {
        val list = canonical(fileCounts = 4)
        val cm = listOf(0, 1, 2, 3)
        assertNull(computeSemmFoldSlots(cm, list))
    }

    @Test
    fun `null cm with no PAINT_DERIVED — no fold`() {
        val list = canonical(fileCounts = 4)
        assertNull(computeSemmFoldSlots(null, list))
    }

    @Test
    fun `null cm with PAINT_DERIVED — falls back to non-PAINT_DERIVED count, folds`() {
        val list = canonical(fileCounts = 4, paintDerivedCounts = 3)
        // cm is null; fallback counts 4 non-PAINT_DERIVED; canonical=7 > 4 → fold
        val result = computeSemmFoldSlots(null, list)
        assertEquals(listOf(0, 1, 2, 3, 0, 1, 2), result)
    }

    @Test
    fun `cm size smaller than FILE_COLOUR count — cm size wins, 4th FILE_COLOUR treated as overflow`() {
        // 4 FILE_COLOUR + 3 PAINT_DERIVED; user assigned only 3 slots (cm=[0,1,2]).
        // New code: cm.size=3 < canonical=7 → base=3, fold T3-T6 → T(i%3).
        // Old code would have used base=4 (non-PAINT_DERIVED count) → T4-T6 → T(i%4).
        // New behavior is correct: user chose 3 slots, so T3 collapses to T0.
        val list = canonical(fileCounts = 4, paintDerivedCounts = 3)
        val cm = listOf(0, 1, 2)
        val result = computeSemmFoldSlots(cm, list)
        assertEquals(listOf(0, 1, 2, 0, 1, 2, 0), result)
    }

    @Test
    fun `single entry canonical — no fold`() {
        val list = canonical(fileCounts = 1)
        val cm = listOf(0)
        assertNull(computeSemmFoldSlots(cm, list))
    }

    @Test
    fun `cm size larger than canonical — no fold`() {
        val list = canonical(fileCounts = 4)
        val cm = listOf(0, 1, 2, 3, 3, 3) // 6 entries, canonical only 4
        assertNull(computeSemmFoldSlots(cm, list))
    }
}
