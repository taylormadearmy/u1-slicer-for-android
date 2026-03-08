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
}
