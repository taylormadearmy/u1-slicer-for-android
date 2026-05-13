package com.u1.slicer.aipaint

import org.junit.Assert.*
import org.junit.Test

class SymmetryDetectorTest {

    /** Build a per-component "triangle" at the given centroid (degenerate but enough for centroid math). */
    private fun triAt(cx: Float, cy: Float, cz: Float): FloatArray = floatArrayOf(
        cx - 0.1f, cy, cz,
        cx + 0.1f, cy, cz,
        cx, cy + 0.1f, cz
    )

    @Test
    fun `single component is its own representative`() {
        val positions = triAt(0f, 0f, 0f)
        val rep = SymmetryDetector.detectPairsByX(positions, intArrayOf(0), numComponents = 1)
        assertArrayEquals(intArrayOf(0), rep)
    }

    @Test
    fun `mirrored pair gets shared representative`() {
        // Two components: A at x=-10, B at x=+10. Mesh centre x = 0. Mirror(A).x = +10 → matches B.
        val positions = triAt(-10f, 5f, 5f) + triAt(10f, 5f, 5f)
        val componentIds = intArrayOf(0, 1)
        val rep = SymmetryDetector.detectPairsByX(positions, componentIds, numComponents = 2)
        assertEquals("paired components must share a representative", rep[0], rep[1])
    }

    @Test
    fun `self-mirroring components on the centre plane stay independent`() {
        // Two components both centred on x=0 (e.g. body + head, both bilaterally self-symmetric).
        // Mirror of each is itself; they must not be paired with each other.
        val positions = triAt(0f, -5f, 5f) + triAt(0f, 5f, 15f)
        val componentIds = intArrayOf(0, 1)
        val rep = SymmetryDetector.detectPairsByX(positions, componentIds, numComponents = 2)
        assertNotEquals("self-mirroring components must not pair with each other", rep[0], rep[1])
    }

    @Test
    fun `two pairs of legs in goat-like configuration form two distinct pairs`() {
        // 4 legs: FL(-3, -5, 2), FR(3, -5, 2), BL(-3, 5, 2), BR(3, 5, 2). Plus a single body at origin.
        val body = triAt(0f, 0f, 8f)
        val fl = triAt(-3f, -5f, 2f); val fr = triAt(3f, -5f, 2f)
        val bl = triAt(-3f,  5f, 2f); val br = triAt(3f,  5f, 2f)
        val positions = body + fl + fr + bl + br
        // componentIds: one triangle per component → 5 components total
        val componentIds = intArrayOf(0, 1, 2, 3, 4)
        val rep = SymmetryDetector.detectPairsByX(positions, componentIds, numComponents = 5)
        // Body (0) stays alone; FL & FR share rep; BL & BR share rep.
        assertEquals(0, rep[0])
        assertEquals("FL/FR must pair", rep[1], rep[2])
        assertEquals("BL/BR must pair", rep[3], rep[4])
        assertNotEquals("FL pair and BL pair must be distinct", rep[1], rep[3])
        assertNotEquals("body must not pair with any leg", rep[0], rep[1])
        assertNotEquals("body must not pair with any leg", rep[0], rep[3])
    }

    @Test
    fun `asymmetric components stay unpaired`() {
        // Two components on the same side of the model — no mirror match exists.
        val positions = triAt(-10f, 0f, 0f) + triAt(-8f, 5f, 0f)
        val componentIds = intArrayOf(0, 1)
        val rep = SymmetryDetector.detectPairsByX(positions, componentIds, numComponents = 2)
        // Both should remain their own rep.
        assertEquals(0, rep[0])
        assertEquals(1, rep[1])
    }

    @Test
    fun `tolerance respects relative model size`() {
        // Components offset by 0.5 mm in Y on a 100 mm model — should still pair.
        val positions = triAt(-50f, 0f, 0f) + triAt(50f, 0.5f, 0f)
        val componentIds = intArrayOf(0, 1)
        val rep = SymmetryDetector.detectPairsByX(positions, componentIds, numComponents = 2)
        assertEquals("0.5mm offset on a 100mm model is within 5% tolerance", rep[0], rep[1])
    }

    @Test
    fun `tolerance rejects pairs far outside the model-sized window`() {
        // Components offset by 20 mm in Y on a 100 mm model — outside 5% tolerance.
        val positions = triAt(-50f, 0f, 0f) + triAt(50f, 20f, 0f)
        val componentIds = intArrayOf(0, 1)
        val rep = SymmetryDetector.detectPairsByX(positions, componentIds, numComponents = 2)
        assertNotEquals(rep[0], rep[1])
    }

    // ---- buildAiComponentMap ----

    @Test
    fun `buildAiComponentMap collapses pairs into dense ids`() {
        // 5 components: rep = [0, 0, 2, 2, 4]  (pair 0-1, pair 2-3, lone 4)
        val rep = intArrayOf(0, 0, 2, 2, 4)
        val (aiIdOf, origForAi) = SymmetryDetector.buildAiComponentMap(rep)
        assertEquals(3, origForAi.size)
        // AI ids are dense 0..2 in order of first appearance.
        assertEquals(0, aiIdOf[0])
        assertEquals(0, aiIdOf[1])
        assertEquals(1, aiIdOf[2])
        assertEquals(1, aiIdOf[3])
        assertEquals(2, aiIdOf[4])
        // Reverse map: AI id → original component ids.
        assertEquals(listOf(0, 1), origForAi[0])
        assertEquals(listOf(2, 3), origForAi[1])
        assertEquals(listOf(4),    origForAi[2])
    }

    @Test
    fun `buildAiComponentMap identity when no pairs`() {
        val rep = intArrayOf(0, 1, 2, 3)
        val (aiIdOf, origForAi) = SymmetryDetector.buildAiComponentMap(rep)
        assertArrayEquals(rep, aiIdOf)
        assertEquals(4, origForAi.size)
        origForAi.forEachIndexed { i, l -> assertEquals(listOf(i), l) }
    }
}
