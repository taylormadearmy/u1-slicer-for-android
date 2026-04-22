package com.u1.slicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * B92: Unit tests for `computeSlicerColorOrder`.
 *
 * See its KDoc for the full contract. These tests enumerate the inputs for the
 * canonical cases so a regression in the helper triggers immediately, without
 * having to run the slow Buzz instrumented test.
 */
class SlicerColorOrderTest {

    @Test
    fun `returns null when hasPaintData is false`() {
        val result = computeSlicerColorOrder(
            detectedColors = listOf("#6F5034", "#FFFFFF"),
            usedExtruderIndices = sortedSetOf(3, 10),
            objectExtruderMap = mapOf("1" to 10),
            hasPaintData = false,
            isH2cStyle = false
        )
        assertNull(result)
    }

    @Test
    fun `returns null for H2C models`() {
        val result = computeSlicerColorOrder(
            detectedColors = listOf("#A", "#B", "#C", "#D"),
            usedExtruderIndices = sortedSetOf(1, 2, 3, 4),
            objectExtruderMap = mapOf("1" to 1),
            hasPaintData = true,
            isH2cStyle = true
        )
        assertNull(result)
    }

    @Test
    fun `returns null for single-colour detection`() {
        val result = computeSlicerColorOrder(
            detectedColors = listOf("#FFFFFF"),
            usedExtruderIndices = sortedSetOf(1),
            objectExtruderMap = mapOf("1" to 1),
            hasPaintData = true,
            isH2cStyle = false
        )
        assertNull(result)
    }

    @Test
    fun `returns null when detectedColors size mismatches usedExtruderIndices size`() {
        val result = computeSlicerColorOrder(
            detectedColors = listOf("#A", "#B"),
            usedExtruderIndices = sortedSetOf(1, 2, 3),
            objectExtruderMap = mapOf("1" to 1),
            hasPaintData = true,
            isH2cStyle = false
        )
        assertNull(result)
    }

    @Test
    fun `returns null when object default is at detectedColors index 0 (identity)`() {
        // Typical Flarewing / simple SEMM: all objects share extruder=1 and paint
        // states use filaments 1..4 — the default already sits at position 0.
        val result = computeSlicerColorOrder(
            detectedColors = listOf("#A", "#B", "#C", "#D"),
            usedExtruderIndices = sortedSetOf(1, 2, 3, 4),
            objectExtruderMap = mapOf(
                "1" to 1, "2" to 1, "3" to 1, "4" to 1
            ),
            hasPaintData = true,
            isH2cStyle = false
        )
        assertNull(result)
    }

    @Test
    fun `returns null when multiple dominant object extruders tie`() {
        val result = computeSlicerColorOrder(
            detectedColors = listOf("#A", "#B", "#C"),
            usedExtruderIndices = sortedSetOf(1, 2, 3),
            objectExtruderMap = mapOf(
                "a" to 1, "b" to 2, "c" to 3
            ),
            hasPaintData = true,
            isH2cStyle = false
        )
        assertNull(result)
    }

    @Test
    fun `Buzz plate 8 shape — default extruder 10 with paint state 3 permutes`() {
        // Buzz Lightyear plate 8: all objects use extruder=10 (white filament,
        // source filament 10), painted triangles tagged paint_color=3 (brown,
        // source filament 3). detectedColors is [brown, white] after ascending
        // sort; slicer print-order is [white (default), brown (paint state)] so
        // slicer T0 → detectedColors[1] and slicer T1 → detectedColors[0].
        val result = computeSlicerColorOrder(
            detectedColors = listOf("#6F5034", "#FFFFFF"),
            usedExtruderIndices = sortedSetOf(3, 10),
            objectExtruderMap = mapOf(
                "obj1" to 10, "obj2" to 10, "obj3" to 10
            ),
            hasPaintData = true,
            isH2cStyle = false
        )
        assertEquals(listOf(1, 0), result)
    }

    @Test
    fun `three-colour case with default at the top of detectedColors — permutes`() {
        // Object uses extruder 7; paint states use filaments 2 and 5.
        // detectedColors sorted ascending = [filament2, filament5, filament7];
        // slicer order = [filament7 (default), filament2, filament5] →
        // [detectedColors[2], detectedColors[0], detectedColors[1]].
        val result = computeSlicerColorOrder(
            detectedColors = listOf("#AAAAAA", "#BBBBBB", "#CCCCCC"),
            usedExtruderIndices = sortedSetOf(2, 5, 7),
            objectExtruderMap = mapOf(
                "body" to 7, "body2" to 7
            ),
            hasPaintData = true,
            isH2cStyle = false
        )
        assertEquals(listOf(2, 0, 1), result)
    }

    @Test
    fun `returns null when no objects reference any extruder`() {
        val result = computeSlicerColorOrder(
            detectedColors = listOf("#A", "#B"),
            usedExtruderIndices = sortedSetOf(1, 2),
            objectExtruderMap = emptyMap(),
            hasPaintData = true,
            isH2cStyle = false
        )
        assertNull(result)
    }
}
