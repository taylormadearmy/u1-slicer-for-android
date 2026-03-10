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

    // ─── extractToolchangeRetractLength ────────────────────────────────────────

    @Test
    fun extractToolchangeRetractLength_parsesCommaList() {
        val gcode = "; retract_length_toolchange = 0.8,0.8\n"
        val vals = GcodeValidator.extractToolchangeRetractLength(gcode)
        assertNotNull(vals)
        assertEquals(2, vals!!.size)
        assertEquals(0.8, vals[0], 0.001)
        assertEquals(0.8, vals[1], 0.001)
    }

    @Test
    fun extractToolchangeRetractLength_singleValue() {
        val gcode = "; retract_length_toolchange = 0.8\n"
        val vals = GcodeValidator.extractToolchangeRetractLength(gcode)
        assertNotNull(vals)
        assertEquals(1, vals!!.size)
        assertEquals(0.8, vals[0], 0.001)
    }

    @Test
    fun extractToolchangeRetractLength_nullWhenMissing() {
        val gcode = "G28\nG1 X10\n"
        assertNull(GcodeValidator.extractToolchangeRetractLength(gcode))
    }

    @Test
    fun extractToolchangeRetractLength_detectsBowdenDefault() {
        val gcode = "; retract_length_toolchange = 10\n"
        val vals = GcodeValidator.extractToolchangeRetractLength(gcode)
        assertNotNull(vals)
        assertEquals(10.0, vals!![0], 0.001)
    }

    // ─── maxRetractionMm ────────────────────────────────────────────────────────

    @Test
    fun maxRetractionMm_findsLargestRetraction() {
        val gcode = "G1 E-.8 F2700\nG1 X10 Y10 E1.5\nG1 E-2.0 F2700\n"
        assertEquals(2.0, GcodeValidator.maxRetractionMm(gcode), 0.001)
    }

    @Test
    fun maxRetractionMm_ignoresPositiveExtrusion() {
        val gcode = "G1 E10 F2700\nG1 X10 Y10 E1.5\n"
        assertEquals(0.0, GcodeValidator.maxRetractionMm(gcode), 0.001)
    }

    @Test
    fun maxRetractionMm_zeroWhenNoRetractions() {
        val gcode = "G28\nG1 X10 Y10\n"
        assertEquals(0.0, GcodeValidator.maxRetractionMm(gcode), 0.001)
    }

    @Test
    fun maxRetractionMm_detectsDangerousBowdenRetraction() {
        // This is the bug we're guarding against — 10mm toolchange retraction
        val gcode = "G1 E-.8 F2700\nG1 E-10 F2700\nG1 E10 F2700\n"
        assertEquals(10.0, GcodeValidator.maxRetractionMm(gcode), 0.001)
    }

    // ─── hasBowdenUnloadSequence (regression: filament jam from SEMM defaults) ─

    @Test
    fun hasBowdenUnloadSequence_detectsUnloadComment() {
        val gcode = """
            G1 X10 Y10
            ; Retract(unload)
            G1 E-15.0000 F6000
            G1 E-55.3000 F5400
        """.trimIndent()
        assertTrue(
            "Should detect bowden-style unload sequence",
            GcodeValidator.hasBowdenUnloadSequence(gcode)
        )
    }

    @Test
    fun hasBowdenUnloadSequence_absentInCorrectGcode() {
        // A correctly configured Snapmaker U1 multi-tool print should NOT have this
        val gcode = "G28\nT0\nG1 X10 E1.0\nT1\nG1 X20 E1.0\n"
        assertFalse(
            "Correct U1 gcode should NOT have bowden unload sequence",
            GcodeValidator.hasBowdenUnloadSequence(gcode)
        )
    }

    @Test
    fun maxRetractionMm_rejectsBowdenToolchangeRetraction() {
        // Regression: OrcaSlicer SEMM defaults caused 94mm retraction during tool changes,
        // jamming the Snapmaker U1's direct-drive extruders. Max safe retraction is ~5mm.
        val bowdenGcode = """
            G1 E-.4 F3000
            ; Retract(unload)
            G1 E-15.0000 F6000
            G1 E-55.3000 F5400
            G1 E-15.8000 F2700
            G1 E-7.9000 F1620
        """.trimIndent()
        val maxRetract = GcodeValidator.maxRetractionMm(bowdenGcode)
        assertTrue(
            "Max retraction ${maxRetract}mm exceeds 5mm safe limit for direct drive — " +
                    "bowden SEMM defaults are leaking through",
            maxRetract > 5.0  // This SHOULD be true for the bad gcode — test proves we can detect it
        )
    }

    @Test
    fun maxRetractionMm_safeForDirectDrive() {
        // Correct direct-drive gcode has only small retractions (0.4-0.8mm typical)
        val correctGcode = "G1 E-.4 F3000\nG1 X10 Y10 E1.0\nG1 E-.8 F2700\n"
        val maxRetract = GcodeValidator.maxRetractionMm(correctGcode)
        assertTrue(
            "Direct drive retraction should be ≤5mm, got ${maxRetract}mm",
            maxRetract <= 5.0
        )
    }

    // ─── extractConfigComment ───────────────────────────────────────────────────

    @Test
    fun extractConfigComment_findsKey() {
        val gcode = "; enable_filament_ramming = 0\n; cooling_tube_retraction = 0\n"
        assertEquals("0", GcodeValidator.extractConfigComment(gcode, "enable_filament_ramming"))
        assertEquals("0", GcodeValidator.extractConfigComment(gcode, "cooling_tube_retraction"))
    }

    @Test
    fun extractConfigComment_nullWhenMissing() {
        val gcode = "G28\nG1 X10\n"
        assertNull(GcodeValidator.extractConfigComment(gcode, "enable_filament_ramming"))
    }

    @Test
    fun extractConfigComment_detectsBowdenDefaults() {
        // Regression guard: these are the dangerous OrcaSlicer defaults
        val gcode = "; enable_filament_ramming = 1\n; cooling_tube_retraction = 91.5\n"
        assertEquals("1", GcodeValidator.extractConfigComment(gcode, "enable_filament_ramming"))
        assertEquals("91.5", GcodeValidator.extractConfigComment(gcode, "cooling_tube_retraction"))
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

    // ─── checkBedBounds ───────────────────────────────────────────────────────

    @Test
    fun checkBedBounds_allWithinBounds_returnsTrue() {
        val gcode = "G1 X135 Y135 F3000\nG1 X10 Y10\nG1 X260 Y260\n"
        val result = GcodeValidator.checkBedBounds(gcode)
        assertTrue("Should be within bounds", result.withinBounds)
        assertEquals(0, result.violatingLines.size)
    }

    @Test
    fun checkBedBounds_negativeX_returnsViolation() {
        val gcode = "G1 X135 Y135\nG1 X-5 Y135\n"
        val result = GcodeValidator.checkBedBounds(gcode)
        assertFalse("Negative X should fail bounds check", result.withinBounds)
        assertTrue("Should have violating line", result.violatingLines.isNotEmpty())
    }

    @Test
    fun checkBedBounds_xBeyond270_returnsViolation() {
        val gcode = "G1 X135 Y135\nG1 X280 Y135\n"
        val result = GcodeValidator.checkBedBounds(gcode)
        assertFalse("X=280 should fail bounds check", result.withinBounds)
    }

    @Test
    fun checkBedBounds_skipsCommentLines() {
        val gcode = "; G1 X-999 Y-999 this is a comment\nG1 X135 Y135\n"
        val result = GcodeValidator.checkBedBounds(gcode)
        assertTrue("Comment lines should not trigger violation", result.withinBounds)
    }

    @Test
    fun checkBedBounds_tracksBoundsExtents() {
        val gcode = "G1 X10 Y20\nG1 X200 Y180\n"
        val result = GcodeValidator.checkBedBounds(gcode)
        assertTrue(result.withinBounds)
        assertEquals(10.0, result.minX, 0.01)
        assertEquals(200.0, result.maxX, 0.01)
        assertEquals(20.0, result.minY, 0.01)
        assertEquals(180.0, result.maxY, 0.01)
    }
}
