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
    fun `two copies are centered in the same row`() {
        val positions = CopyArrangeCalculator.calculate(20f, 20f, 2)
        assertEquals(4, positions.size)
        // gridWidth = 2*20 + 1*5 = 45, offsetX = (270-45)/2 = 112.5
        assertEquals(112.5f, positions[0], 0.01f)
        assertEquals(125f, positions[1], 0.01f)
        assertEquals(137.5f, positions[2], 0.01f)
        assertEquals(125f, positions[3], 0.01f)
    }

    @Test
    fun `grid wraps to next row centered`() {
        // 270mm bed, 20mm object, 5mm margin -> cols = floor(275/25) = 11
        // 12 copies: 11 in row 0, 1 in row 1 (2 rows)
        // gridWidth = 11*20 + 10*5 = 270, offsetX = 0
        // gridHeight = 2*20 + 1*5 = 45, offsetY = (270-45)/2 = 112.5
        val positions = CopyArrangeCalculator.calculate(20f, 20f, 12)
        val x = positions[11 * 2]
        val y = positions[11 * 2 + 1]
        assertEquals(0f, x, 0.01f)
        assertEquals(137.5f, y, 0.01f)
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
        // 130x120 model at (70, 80) → occupies (70,80)-(200,200)
        // 60mm tower with 10mm edge margin: bottom-center at (105, 10) → (105,10)-(165,70)
        // Fully below model at y=80.
        val objPos = floatArrayOf(70f, 80f)
        val (tx, ty) = CopyArrangeCalculator.computeWipeTowerPosition(objPos, 130f, 120f, 60f)
        val tMaxX = tx + 60f; val tMaxY = ty + 60f
        val overlapsX = tx < 200f && tMaxX > 70f
        val overlapsY = ty < 200f && tMaxY > 80f
        assertFalse("Tower at ($tx,$ty) overlaps 130x120mm model", overlapsX && overlapsY)
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
    fun `grid is centered on bed for small objects`() {
        // 4 copies of 50mm objects with 5mm margin
        // cols = floor(275/55) = 5, 4 copies fit in 1 row
        // gridWidth = 4*50 + 3*5 = 215, offsetX = (270-215)/2 = 27.5
        // gridHeight = 1*50 = 50, offsetY = (270-50)/2 = 110
        val positions = CopyArrangeCalculator.calculate(50f, 50f, 4)
        assertEquals(8, positions.size)
        assertEquals(27.5f, positions[0], 0.01f) // first copy X
        assertEquals(110f, positions[1], 0.01f) // first copy Y
        assertEquals(82.5f, positions[2], 0.01f) // second copy X
        assertEquals(110f, positions[3], 0.01f) // second copy Y
    }

    @Test
    fun `grid copies all within bed bounds`() {
        val positions = CopyArrangeCalculator.calculate(50f, 50f, 9)
        for (i in positions.indices step 2) {
            assertTrue("x=${positions[i]} < 0", positions[i] >= 0f)
            assertTrue("y=${positions[i+1]} < 0", positions[i+1] >= 0f)
            assertTrue("x+size=${positions[i]+50f} > 270", positions[i] + 50f <= 270f)
            assertTrue("y+size=${positions[i+1]+50f} > 270", positions[i+1] + 50f <= 270f)
        }
    }

    @Test
    fun `three copies in a row are centered`() {
        // 3 copies of 80mm with 5mm margin → 1 row
        // gridWidth = 3*80 + 2*5 = 250, offsetX = (270-250)/2 = 10
        val positions = CopyArrangeCalculator.calculate(80f, 80f, 3)
        assertEquals(6, positions.size)
        assertEquals(10f, positions[0], 0.01f)
        assertEquals(95f, positions[1], 0.01f) // (270-80)/2 = 95
    }

    @Test
    fun `tower picks bottom-left when model is in top-right`() {
        // Model at (200, 200), size 50x50
        val objPos = floatArrayOf(200f, 200f)
        val (tx, ty) = CopyArrangeCalculator.computeWipeTowerPosition(objPos, 50f, 50f, 60f)
        // Should pick bottom-left corner with 10mm edge margin for skirt+brim clearance
        assertEquals(10f, tx, 0.01f)
        assertEquals(10f, ty, 0.01f)
    }

    @Test
    fun `tower position leaves skirt clearance from bed edges for all model positions`() {
        // Regression: tower at (0, 210) caused skirt to extend beyond bed boundary.
        // Clearance needed: prime_tower_brim (3mm) + skirt_distance (6mm) + 1 loop (~0.5mm) ≈ 9.5mm.
        val skirtClearance = 9.5f
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
