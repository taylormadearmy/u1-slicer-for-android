package com.u1.slicer.model

import org.junit.Assert.*
import org.junit.Test

class CopyArrangeCalculatorTest {

    @Test
    fun `single copy returns centered position on bed`() {
        val positions = CopyArrangeCalculator.calculate(20f, 20f, 1)
        assertEquals(2, positions.size)
        // (270 - 20) / 2 = 125
        assertEquals(125f, positions[0], 0.01f)
        assertEquals(125f, positions[1], 0.01f)
    }

    @Test
    fun `two copies are in the same row`() {
        val positions = CopyArrangeCalculator.calculate(20f, 20f, 2)
        assertEquals(4, positions.size)
        assertEquals(5f, positions[0], 0.01f)
        assertEquals(5f, positions[1], 0.01f)
        assertEquals(30f, positions[2], 0.01f)
        assertEquals(5f, positions[3], 0.01f)
    }

    @Test
    fun `grid wraps to next row`() {
        // 270mm bed, 20mm object, 5mm margin -> cols = floor(275/25) = 11
        val positions = CopyArrangeCalculator.calculate(20f, 20f, 12)
        val x = positions[11 * 2]
        val y = positions[11 * 2 + 1]
        assertEquals(5f, x, 0.01f)
        assertEquals(30f, y, 0.01f)
    }

    @Test
    fun `copies capped at bed capacity`() {
        val positions = CopyArrangeCalculator.calculate(250f, 250f, 10)
        assertEquals(2, positions.size)
    }

    @Test
    fun `maxCopies returns correct fit for 20mm object`() {
        val max = CopyArrangeCalculator.maxCopies(20f, 20f)
        assertEquals(121, max)
    }

    @Test
    fun `maxCopies for large object is 1`() {
        assertEquals(1, CopyArrangeCalculator.maxCopies(260f, 260f))
    }

    @Test
    fun `all positions are within bed bounds`() {
        val positions = CopyArrangeCalculator.calculate(30f, 30f, 50)
        for (i in positions.indices step 2) {
            val x = positions[i]
            val y = positions[i + 1]
            assertTrue("x=$x out of bounds", x >= 0f && x + 30f <= 270f)
            assertTrue("y=$y out of bounds", y >= 0f && y + 30f <= 270f)
        }
    }

    @Test
    fun `copy count 1 always succeeds even for oversized object`() {
        val positions = CopyArrangeCalculator.calculate(300f, 300f, 1)
        assertEquals(2, positions.size)
    }

    @Test
    fun `single copy of oversized object positions at origin not negative`() {
        val positions = CopyArrangeCalculator.calculate(300f, 300f, 1)
        assertEquals(0f, positions[0], 0.01f)
        assertEquals(0f, positions[1], 0.01f)
    }

    // --- computeWipeTowerPosition tests ---

    @Test
    fun `tower avoids centered model - picks corner with most clearance`() {
        // Model centered at (125, 125), size 20x20 → occupies (125,125)-(145,145)
        val objPos = floatArrayOf(125f, 125f)
        val (tx, ty) = CopyArrangeCalculator.computeWipeTowerPosition(objPos, 20f, 20f, 60f)
        // Tower should NOT overlap the model
        val tMaxX = tx + 60f; val tMaxY = ty + 60f
        val oMinX = 125f; val oMinY = 125f; val oMaxX = 145f; val oMaxY = 145f
        val overlapsX = tx < oMaxX && tMaxX > oMinX
        val overlapsY = ty < oMaxY && tMaxY > oMinY
        assertFalse("Tower at ($tx,$ty) overlaps model", overlapsX && overlapsY)
    }

    @Test
    fun `tower avoids large centered model - picks edge midpoint`() {
        // 140x140 model centered at (65, 65) → occupies (65,65)-(205,205)
        // 60mm tower with 5mm edge margin: bottom-center at (105, 5) → (105,5)-(165,65)
        // Fully below model at y=65.
        val objPos = floatArrayOf(65f, 65f)
        val (tx, ty) = CopyArrangeCalculator.computeWipeTowerPosition(objPos, 140f, 140f, 60f)
        val tMaxX = tx + 60f; val tMaxY = ty + 60f
        val overlapsX = tx < 205f && tMaxX > 65f
        val overlapsY = ty < 205f && tMaxY > 65f
        assertFalse("Tower at ($tx,$ty) overlaps 140mm model", overlapsX && overlapsY)
    }

    @Test
    fun `tower position is within bed bounds`() {
        val objPos = floatArrayOf(125f, 125f)
        val (tx, ty) = CopyArrangeCalculator.computeWipeTowerPosition(objPos, 20f, 20f, 60f)
        assertTrue("tx=$tx out of bounds", tx >= 0f && tx + 60f <= 270f)
        assertTrue("ty=$ty out of bounds", ty >= 0f && ty + 60f <= 270f)
    }

    @Test
    fun `tower avoids multiple grid copies`() {
        // 4 copies of 50x50 in grid: (5,5), (60,5), (5,60), (60,60)
        val objPos = CopyArrangeCalculator.calculate(50f, 50f, 4)
        val (tx, ty) = CopyArrangeCalculator.computeWipeTowerPosition(objPos, 50f, 50f, 60f)
        // Verify no overlap with any copy
        for (i in 0 until objPos.size / 2) {
            val ox = objPos[i * 2]; val oy = objPos[i * 2 + 1]
            val overlapsX = tx < ox + 50f && tx + 60f > ox
            val overlapsY = ty < oy + 50f && ty + 60f > oy
            assertFalse("Tower at ($tx,$ty) overlaps copy $i at ($ox,$oy)", overlapsX && overlapsY)
        }
    }

    @Test
    fun `tower picks bottom-left when model is in top-right`() {
        // Model at (200, 200), size 50x50
        val objPos = floatArrayOf(200f, 200f)
        val (tx, ty) = CopyArrangeCalculator.computeWipeTowerPosition(objPos, 50f, 50f, 60f)
        // Should pick bottom-left corner with 5mm edge margin for skirt clearance
        assertEquals(5f, tx, 0.01f)
        assertEquals(5f, ty, 0.01f)
    }

    @Test
    fun `tower position leaves skirt clearance from bed edges for all model positions`() {
        // Regression: tower at (0, 210) caused skirt to extend beyond bed boundary.
        // Skirt needs ~4mm clearance (3mm distance + 2 loops × 0.5mm line width).
        val skirtClearance = 4f
        val towerWidth = 60f
        val bedSize = 270f

        // Test with models at various positions across the bed
        val testCases = listOf(
            floatArrayOf(105f, 105f) to (20f to 20f),   // centered small model
            floatArrayOf(200f, 200f) to (50f to 50f),   // top-right
            floatArrayOf(10f, 10f) to (50f to 50f),     // bottom-left
            floatArrayOf(105f, 10f) to (60f to 60f),    // bottom-center large
            floatArrayOf(85f, 91f) to (100f to 88f),    // Shashibo plate 5 approx
        )

        for ((objPos, size) in testCases) {
            val (tx, ty) = CopyArrangeCalculator.computeWipeTowerPosition(
                objPos, size.first, size.second, towerWidth, bedSize, bedSize
            )
            assertTrue(
                "Tower at ($tx,$ty): left edge too close to bed (need ${skirtClearance}mm for skirt)",
                tx >= skirtClearance
            )
            assertTrue(
                "Tower at ($tx,$ty): bottom edge too close to bed (need ${skirtClearance}mm for skirt)",
                ty >= skirtClearance
            )
            assertTrue(
                "Tower at ($tx,$ty): right edge too close to bed (need ${skirtClearance}mm for skirt)",
                tx + towerWidth <= bedSize - skirtClearance
            )
            assertTrue(
                "Tower at ($tx,$ty): top edge too close to bed (need ${skirtClearance}mm for skirt)",
                ty + towerWidth <= bedSize - skirtClearance
            )
        }
    }
}
