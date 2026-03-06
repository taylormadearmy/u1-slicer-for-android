package com.u1.slicer.gcode

import org.junit.Assert.*
import org.junit.Test

/**
 * JVM unit tests for GcodeValidator.
 *
 * Uses synthetic G-code strings to verify each parser in isolation.
 * No device required — runs via ./gradlew testDebugUnitTest.
 */
class GcodeValidatorTest {

    // ─── extractToolChanges ───────────────────────────────────────────────────

    @Test
    fun extractToolChanges_singleExtruder_empty() {
        val gcode = "G28\nG1 X10 Y10\nM104 S200\n"
        assertTrue(GcodeValidator.extractToolChanges(gcode).isEmpty())
    }

    @Test
    fun extractToolChanges_dualExtruder_findsT0andT1() {
        val gcode = "G28\nT0\nG1 X10\nT1\nG1 X20\n"
        val tools = GcodeValidator.extractToolChanges(gcode)
        assertTrue("T0 in $tools", "T0" in tools)
        assertTrue("T1 in $tools", "T1" in tools)
    }

    @Test
    fun extractToolChanges_withTrailingSpace_normalised() {
        val gcode = "T2  \nT3\n"
        val tools = GcodeValidator.extractToolChanges(gcode)
        assertTrue("T2" in tools)
        assertTrue("T3" in tools)
    }

    @Test
    fun extractToolChanges_doesNotMatchCommentLines() {
        val gcode = "; T0 this is a comment\nT1\n"
        val tools = GcodeValidator.extractToolChanges(gcode)
        assertFalse("T0 should not be found in comment", "T0" in tools)
        assertTrue("T1" in tools)
    }

    // ─── extractNozzleTemps ───────────────────────────────────────────────────

    @Test
    fun extractNozzleTemps_findsM104andM109() {
        val gcode = "M104 S215\nM109 S215 T0\nM104 S0\n"
        val temps = GcodeValidator.extractNozzleTemps(gcode)
        assertEquals(2, temps.size)
        assertTrue(temps.all { it == 215 })
    }

    @Test
    fun extractNozzleTemps_skipsZeroAndComments() {
        val gcode = "; M104 S200 — comment\nM104 S0\nM109 S220\n"
        val temps = GcodeValidator.extractNozzleTemps(gcode)
        assertEquals(1, temps.size)
        assertEquals(220, temps[0])
    }

    @Test
    fun hasNonZeroNozzleTemps_trueWhenPresent() {
        assertTrue(GcodeValidator.hasNonZeroNozzleTemps("M104 S200\n"))
    }

    @Test
    fun hasNonZeroNozzleTemps_falseWhenAllZero() {
        assertFalse(GcodeValidator.hasNonZeroNozzleTemps("M104 S0\n"))
    }

    // ─── extractLayerCount ────────────────────────────────────────────────────

    @Test
    fun extractLayerCount_countsLayerChangeAnnotations() {
        val gcode = ";LAYER_CHANGE\nG1 Z0.2\n;LAYER_CHANGE\nG1 Z0.4\n"
        assertEquals(2, GcodeValidator.extractLayerCount(gcode))
    }

    @Test
    fun extractLayerCount_countsZAnnotations() {
        val gcode = ";Z:0.200\nG1 Z0.2\n;Z:0.400\nG1 Z0.4\n;Z:0.600\n"
        assertEquals(3, GcodeValidator.extractLayerCount(gcode))
    }

    @Test
    fun extractLayerCount_zeroWhenNoAnnotations() {
        val gcode = "G28\nG1 X10 Y10 Z0.2\n"
        assertEquals(0, GcodeValidator.extractLayerCount(gcode))
    }

    // ─── hasToolChanges / lacksToolChanges ────────────────────────────────────

    @Test
    fun hasToolChanges_returnsTrueWhenAllPresent() {
        val gcode = "T2\nG1 X10\nT3\n"
        assertTrue(GcodeValidator.hasToolChanges(gcode, "T2", "T3"))
    }

    @Test
    fun hasToolChanges_returnsFalseWhenAnyMissing() {
        val gcode = "T0\nG1 X10\n"
        assertFalse(GcodeValidator.hasToolChanges(gcode, "T0", "T1"))
    }

    @Test
    fun lacksToolChanges_trueWhenNonePresent() {
        val gcode = "T2\nT3\n"
        assertTrue(GcodeValidator.lacksToolChanges(gcode, "T0", "T1"))
    }

    @Test
    fun lacksToolChanges_falseWhenAnyPresent() {
        val gcode = "T0\nT2\n"
        assertFalse(GcodeValidator.lacksToolChanges(gcode, "T0", "T1"))
    }

    // ─── parsePrimeTowerFootprint ─────────────────────────────────────────────

    @Test
    fun parsePrimeTowerFootprint_nullWhenNoPrimeTower() {
        val gcode = "G1 X10 Y10 E1.0\nG1 X20 Y20 E2.0\n"
        assertNull(GcodeValidator.parsePrimeTowerFootprint(gcode))
    }

    @Test
    fun parsePrimeTowerFootprint_parsesRelativeE() {
        // Relative extrusion mode
        val gcode = """
            M83
            ; FEATURE: Prime tower
            G1 X10.0 Y10.0 E2.0
            G1 X30.0 Y10.0 E2.0
            G1 X30.0 Y30.0 E2.0
            G1 X10.0 Y30.0 E2.0
        """.trimIndent()
        val fp = GcodeValidator.parsePrimeTowerFootprint(gcode)
        assertNotNull(fp)
        fp!!
        assertEquals(10.0, fp.minX, 0.01)
        assertEquals(30.0, fp.maxX, 0.01)
        assertEquals(20.0, fp.width, 0.01)
    }

    @Test
    fun parsePrimeTowerFootprint_skipsNonExtrudingMoves() {
        val gcode = """
            M83
            ; FEATURE: Prime tower
            G1 X5.0 Y5.0
            G1 X10.0 Y10.0 E2.0
            G1 X20.0 Y20.0 E2.0
        """.trimIndent()
        val fp = GcodeValidator.parsePrimeTowerFootprint(gcode)
        assertNotNull(fp)
        // Non-extruding G1 at (5,5) should not extend bounds
        assertEquals(10.0, fp!!.minX, 0.01)
        assertEquals(10.0, fp.minY, 0.01)
    }
}
