package com.u1.slicer.gcode

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GcodeParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun writeGcode(content: String): File {
        val file = tempFolder.newFile("test.gcode")
        file.writeText(content)
        return file
    }

    @Test
    fun `parse empty file returns zero layers`() {
        val file = writeGcode("")
        val result = GcodeParser.parse(file)
        assertEquals(0, result.layers.size)
    }

    @Test
    fun `parse comments only returns zero layers`() {
        val file = writeGcode("""
            ; This is a comment
            ; Another comment
        """.trimIndent())
        val result = GcodeParser.parse(file)
        assertEquals(0, result.layers.size)
    }

    @Test
    fun `parse single layer with extrusion moves`() {
        val file = writeGcode("""
            G1 Z0.3 F3000
            G1 X10 Y0 E1.0 F1500
            G1 X10 Y10 E2.0
            G1 X0 Y10 E3.0
            G1 X0 Y0 E4.0
        """.trimIndent())
        val result = GcodeParser.parse(file)

        assertEquals(1, result.layers.size)
        val layer = result.layers[0]
        assertEquals(0, layer.index)
        assertEquals(0.3f, layer.z, 0.001f)
        assertEquals(4, layer.moves.size)
        // All should be extrude moves (E increases each time)
        assertTrue(layer.moves.all { it.type == MoveType.EXTRUDE })
    }

    @Test
    fun `parse travel moves detected correctly`() {
        val file = writeGcode("""
            G1 Z0.3
            G0 X50 Y50
            G1 X60 Y50 E1.0
        """.trimIndent())
        val result = GcodeParser.parse(file)

        assertEquals(1, result.layers.size)
        assertEquals(2, result.layers[0].moves.size)
        assertEquals(MoveType.TRAVEL, result.layers[0].moves[0].type)
        assertEquals(MoveType.EXTRUDE, result.layers[0].moves[1].type)
    }

    @Test
    fun `parse multiple layers via Z change`() {
        val file = writeGcode("""
            G1 Z0.3
            G1 X10 Y0 E1.0
            G1 X10 Y10 E2.0
            G1 Z0.5
            G1 X20 Y0 E3.0
            G1 X20 Y10 E4.0
            G1 Z0.7
            G1 X30 Y0 E5.0
        """.trimIndent())
        val result = GcodeParser.parse(file)

        assertEquals(3, result.layers.size)
        assertEquals(0.3f, result.layers[0].z, 0.001f)
        assertEquals(0.5f, result.layers[1].z, 0.001f)
        assertEquals(0.7f, result.layers[2].z, 0.001f)
    }

    @Test
    fun `parse layer change comments trigger new layer`() {
        val file = writeGcode("""
            G1 Z0.3
            G1 X10 Y10 E1.0
            ;LAYER_CHANGE
            G1 Z0.5
            G1 X20 Y20 E2.0
        """.trimIndent())
        val result = GcodeParser.parse(file)

        assertEquals(2, result.layers.size)
    }

    @Test
    fun `parse bambu layer change comment style`() {
        val file = writeGcode("""
            G1 Z0.2
            G1 X10 Y10 E1.0
            ; layer_change
            G1 Z0.4
            G1 X20 Y20 E2.0
        """.trimIndent())
        val result = GcodeParser.parse(file)

        assertEquals(2, result.layers.size)
    }

    @Test
    fun `parse extruder changes tracked correctly`() {
        val file = writeGcode("""
            G1 Z0.3
            T0
            G1 X10 Y10 E1.0
            T1
            G1 X20 Y20 E2.0
            T2
            G1 X30 Y30 E3.0
        """.trimIndent())
        val result = GcodeParser.parse(file)

        assertEquals(1, result.layers.size)
        assertEquals(3, result.layers[0].moves.size)
        assertEquals(0, result.layers[0].moves[0].extruder)
        assertEquals(1, result.layers[0].moves[1].extruder)
        assertEquals(2, result.layers[0].moves[2].extruder)
    }

    @Test
    fun `parse G92 E reset handled correctly`() {
        val file = writeGcode("""
            M82
            G1 Z0.3
            G1 X10 Y10 E5.0
            G92 E0
            G1 X20 Y20 E1.0
        """.trimIndent())
        val result = GcodeParser.parse(file)

        assertEquals(1, result.layers.size)
        // After G92 E0, E goes to 1.0 which is > 0, so it's an extrusion
        assertEquals(2, result.layers[0].moves.size)
        assertTrue(result.moves(0).all { it.type == MoveType.EXTRUDE })
    }

    @Test
    fun `parse relative extrusion mode M83`() {
        val file = writeGcode("""
            M83
            G1 Z0.3
            G1 X10 Y10 E0.5
            G1 X20 Y20 E0.5
            G1 X30 Y30 E0
        """.trimIndent())
        val result = GcodeParser.parse(file)

        assertEquals(1, result.layers.size)
        assertEquals(3, result.layers[0].moves.size)
        assertEquals(MoveType.EXTRUDE, result.layers[0].moves[0].type)
        assertEquals(MoveType.EXTRUDE, result.layers[0].moves[1].type)
        assertEquals(MoveType.TRAVEL, result.layers[0].moves[2].type)
    }

    @Test
    fun `parse inline comments stripped`() {
        val file = writeGcode("""
            G1 Z0.3
            G1 X10 Y10 E1.0 ; this is a wall
            G1 X20 Y20 E2.0 ; infill
        """.trimIndent())
        val result = GcodeParser.parse(file)

        assertEquals(1, result.layers.size)
        assertEquals(2, result.layers[0].moves.size)
    }

    @Test
    fun `parse move coordinates are correct`() {
        val file = writeGcode("""
            G1 Z0.3
            G1 X10 Y20 E1.0
            G1 X30 Y40 E2.0
        """.trimIndent())
        val result = GcodeParser.parse(file)

        val moves = result.layers[0].moves
        // First move: from origin (0,0) to (10,20)
        assertEquals(0f, moves[0].x0, 0.001f)
        assertEquals(0f, moves[0].y0, 0.001f)
        assertEquals(10f, moves[0].x1, 0.001f)
        assertEquals(20f, moves[0].y1, 0.001f)
        // Second move: from (10,20) to (30,40)
        assertEquals(10f, moves[1].x0, 0.001f)
        assertEquals(20f, moves[1].y0, 0.001f)
        assertEquals(30f, moves[1].x1, 0.001f)
        assertEquals(40f, moves[1].y1, 0.001f)
    }

    @Test
    fun `parse no XY displacement move is ignored`() {
        val file = writeGcode("""
            G1 Z0.3
            G1 X10 Y10 E1.0
            G1 E2.0
            G1 X20 Y20 E3.0
        """.trimIndent())
        val result = GcodeParser.parse(file)

        // The E-only move should be ignored (no XY displacement)
        assertEquals(1, result.layers.size)
        assertEquals(2, result.layers[0].moves.size)
    }

    @Test
    fun `parse default bed dimensions`() {
        val file = writeGcode("G1 Z0.3\nG1 X10 Y10 E1.0")
        val result = GcodeParser.parse(file)

        assertEquals(270f, result.bedWidth, 0.001f)
        assertEquals(270f, result.bedHeight, 0.001f)
    }

    @Test
    fun `parse all four extruders`() {
        val file = writeGcode("""
            G1 Z0.3
            T0
            G1 X10 Y10 E1.0
            T1
            G1 X20 Y20 E2.0
            T2
            G1 X30 Y30 E3.0
            T3
            G1 X40 Y40 E4.0
        """.trimIndent())
        val result = GcodeParser.parse(file)

        val extruders = result.layers[0].moves.map { it.extruder }
        assertEquals(listOf(0, 1, 2, 3), extruders)
    }

    @Test
    fun `parse layer indices are sequential`() {
        val file = writeGcode("""
            G1 Z0.2
            G1 X10 Y10 E1.0
            G1 Z0.4
            G1 X20 Y20 E2.0
            G1 Z0.6
            G1 X30 Y30 E3.0
            G1 Z0.8
            G1 X40 Y40 E4.0
        """.trimIndent())
        val result = GcodeParser.parse(file)

        val indices = result.layers.map { it.index }
        assertEquals(listOf(0, 1, 2, 3), indices)
    }

    @Test
    fun `parse filament used comment multi-extruder`() {
        val file = writeGcode("""
            ;LAYER_CHANGE
            G1 Z0.3
            G1 X10 Y10 E1.0
            ; filament used [mm] = 1234.56,789.01
        """.trimIndent())
        val result = GcodeParser.parse(file)

        assertEquals(2, result.perExtruderFilamentMm.size)
        assertEquals(1234.56f, result.perExtruderFilamentMm[0], 0.01f)
        assertEquals(789.01f, result.perExtruderFilamentMm[1], 0.01f)
    }

    @Test
    fun `parse filament used comment single-extruder`() {
        val file = writeGcode("""
            ;LAYER_CHANGE
            G1 Z0.3
            G1 X10 Y10 E1.0
            ; filament used [mm] = 5678.90
        """.trimIndent())
        val result = GcodeParser.parse(file)

        assertEquals(1, result.perExtruderFilamentMm.size)
        assertEquals(5678.9f, result.perExtruderFilamentMm[0], 0.01f)
    }

    // --- F33/F28: ;TYPE: feature-type tagging and prime-tower waste ---

    @Test
    fun `parse TYPE comment tags outer wall moves`() {
        val file = writeGcode("""
            ;LAYER_CHANGE
            G1 Z0.3
            ;TYPE:Outer wall
            G1 X10 Y0 E1.0
            G1 X10 Y10 E2.0
        """.trimIndent())
        val result = GcodeParser.parse(file)
        val moves = result.layers[0].moves.filter { it.type == MoveType.EXTRUDE }
        assertTrue("All moves should be tagged OUTER_WALL", moves.all { it.featureType == FeatureType.OUTER_WALL })
    }

    @Test
    fun `parse TYPE comment tags inner wall moves`() {
        val file = writeGcode("""
            ;LAYER_CHANGE
            G1 Z0.3
            ;TYPE:Inner wall
            G1 X10 Y0 E1.0
        """.trimIndent())
        val result = GcodeParser.parse(file)
        val moves = result.layers[0].moves.filter { it.type == MoveType.EXTRUDE }
        assertTrue(moves.all { it.featureType == FeatureType.INNER_WALL })
    }

    @Test
    fun `parse TYPE comment tags sparse infill moves`() {
        val file = writeGcode("""
            ;LAYER_CHANGE
            G1 Z0.3
            ;TYPE:Sparse infill
            G1 X10 Y0 E1.0
        """.trimIndent())
        val result = GcodeParser.parse(file)
        val moves = result.layers[0].moves.filter { it.type == MoveType.EXTRUDE }
        assertTrue(moves.all { it.featureType == FeatureType.SPARSE_INFILL })
    }

    @Test
    fun `parse TYPE comment tags support moves`() {
        val file = writeGcode("""
            ;LAYER_CHANGE
            G1 Z0.3
            ;TYPE:Support
            G1 X10 Y0 E1.0
        """.trimIndent())
        val result = GcodeParser.parse(file)
        val moves = result.layers[0].moves.filter { it.type == MoveType.EXTRUDE }
        assertTrue(moves.all { it.featureType == FeatureType.SUPPORT })
    }

    @Test
    fun `parse TYPE comment tags prime tower moves`() {
        val file = writeGcode("""
            ;LAYER_CHANGE
            G1 Z0.3
            ;TYPE:Prime tower
            G1 X10 Y0 E1.0
        """.trimIndent())
        val result = GcodeParser.parse(file)
        val moves = result.layers[0].moves.filter { it.type == MoveType.EXTRUDE }
        assertTrue(moves.all { it.featureType == FeatureType.PRIME_TOWER })
    }

    @Test
    fun `parse TYPE comment resets between feature regions`() {
        val file = writeGcode("""
            ;LAYER_CHANGE
            G1 Z0.3
            ;TYPE:Outer wall
            G1 X10 Y0 E1.0
            ;TYPE:Sparse infill
            G1 X20 Y0 E2.0
        """.trimIndent())
        val result = GcodeParser.parse(file)
        val moves = result.layers[0].moves.filter { it.type == MoveType.EXTRUDE }
        assertEquals(2, moves.size)
        assertEquals(FeatureType.OUTER_WALL, moves[0].featureType)
        assertEquals(FeatureType.SPARSE_INFILL, moves[1].featureType)
    }

    @Test
    fun `wipe tower filament accumulates E used inside prime tower region`() {
        val file = writeGcode("""
            ;LAYER_CHANGE
            G1 Z0.3
            ;TYPE:Outer wall
            G1 X10 Y0 E5.0
            ;TYPE:Prime tower
            G1 X20 Y0 E8.0
            G1 X30 Y0 E12.0
            ;TYPE:Outer wall
            G1 X40 Y0 E13.0
        """.trimIndent())
        val result = GcodeParser.parse(file)
        // Prime tower region consumed E12.0 - E5.0 = 7.0mm
        assertEquals(7.0f, result.wipeTowerFilamentMm, 0.1f)
    }

    @Test
    fun `wipe tower filament is zero when no prime tower present`() {
        val file = writeGcode("""
            ;LAYER_CHANGE
            G1 Z0.3
            ;TYPE:Outer wall
            G1 X10 Y0 E5.0
            G1 X20 Y0 E10.0
        """.trimIndent())
        val result = GcodeParser.parse(file)
        assertEquals(0f, result.wipeTowerFilamentMm, 0.01f)
    }

    @Test
    fun `colorSegmentsByPausePrint assigns extruder index after each PAUSE_PRINT`() {
        val file = writeGcode("""
            G1 Z0.2
            G1 X1 Y1 E1
            ; PAUSE_PRINT
            G1 X2 Y2 E2
            ; PAUSE_PRINT
            G1 X3 Y3 E3
        """.trimIndent())
        val result = GcodeParser.parse(file, colorSegmentsByPausePrint = true)
        val moves = result.layers.flatMap { it.moves }
        assertTrue(moves.isNotEmpty())
        assertEquals(0, moves.first().extruder)
        val bySeg = moves.groupBy { it.extruder }
        assertTrue(bySeg[0]?.isNotEmpty() == true)
        assertTrue(bySeg[1]?.isNotEmpty() == true)
        assertTrue(bySeg[2]?.isNotEmpty() == true)
    }

    @Test
    fun `colorSegmentsByPausePrint computes per-extruder filament from parsed moves`() {
        val file = writeGcode(
            """
            G1 Z0.2
            G1 X1 Y1 E1.0
            ; PAUSE_PRINT
            G1 X2 Y2 E2.0
            ; filament used [mm] = 3406.23,0.00
            """.trimIndent()
        )
        val result = GcodeParser.parse(file, colorSegmentsByPausePrint = true)

        // In pause-segment mode we must reflect post-pause segments, not stale footer comments.
        assertEquals(2, result.perExtruderFilamentMm.size)
        assertEquals(1.0f, result.perExtruderFilamentMm[0], 0.0001f)
        assertEquals(1.0f, result.perExtruderFilamentMm[1], 0.0001f)
    }

    @Test
    fun `multi-tool computed usage compacts away unused leading tool slots`() {
        val file = writeGcode(
            """
            G1 Z0.2
            T1
            G1 X1 Y1 E1.0
            T2
            G1 X2 Y2 E2.0
            ; filament used [mm] = 7203.11,6387.34,0.00,0.00,0.00
            """.trimIndent()
        )
        val result = GcodeParser.parse(file)

        // Parsed extrusion data should drive compact per-tool usage: two tools, no phantom extras.
        assertEquals(2, result.perExtruderFilamentMm.size)
        assertEquals(1.0f, result.perExtruderFilamentMm[0], 0.0001f)
        assertEquals(1.0f, result.perExtruderFilamentMm[1], 0.0001f)
    }

    // ─── B52: move cap for very large G-code files ──────────────────────────

    @Test
    fun `parse caps stored moves at maxMoves limit`() {
        // Generate G-code with 100 extrude moves across 2 layers
        val sb = StringBuilder()
        sb.appendLine("G1 Z0.2")
        sb.appendLine(";LAYER_CHANGE")
        for (i in 1..50) {
            sb.appendLine("G1 X${i} Y${i} E${i * 0.1}")
        }
        sb.appendLine("G1 Z0.4")
        sb.appendLine(";LAYER_CHANGE")
        for (i in 51..100) {
            sb.appendLine("G1 X${i} Y${i} E${i * 0.1}")
        }
        val file = writeGcode(sb.toString())

        // With maxMoves=30, should store at most 30 moves total but still report
        // correct layer count and total move count via metadata.
        val result = GcodeParser.parse(file, maxMoves = 30)

        val storedMoves = result.layers.sumOf { it.moves.size }
        assertTrue("Stored moves ($storedMoves) should be <= 30", storedMoves <= 30)
        // totalMoves should reflect the ACTUAL number parsed, not the capped count
        assertEquals("totalMoves should count all 100 moves", 100, result.totalMoves)
        // Layer count should still be correct
        assertEquals(2, result.layers.size)
    }

    @Test
    fun `parse with maxMoves distributes moves across all layers via stride`() {
        // 4 layers, 200 moves each = 800 total.  Cap at 200 → stride ~4.
        // Every layer should have SOME moves — not just the first layer.
        val sb = StringBuilder()
        for (layer in 0..3) {
            val z = 0.2f + layer * 0.2f
            sb.appendLine("G1 Z$z")
            sb.appendLine(";LAYER_CHANGE")
            for (i in 1..200) {
                sb.appendLine("G1 X${i} Y${layer * 10} E${i * 0.01}")
            }
        }
        val file = writeGcode(sb.toString())

        val result = GcodeParser.parse(file, maxMoves = 200)

        assertEquals(4, result.layers.size)
        assertEquals(800, result.totalMoves)
        val storedMoves = result.layers.sumOf { it.moves.size }
        assertTrue("Stored moves ($storedMoves) should be <= 200", storedMoves <= 200)
        // Critical: every layer must have at least some moves (stride distributes evenly)
        for ((idx, layer) in result.layers.withIndex()) {
            assertTrue(
                "Layer $idx should have moves (got ${layer.moves.size})",
                layer.moves.isNotEmpty()
            )
        }
        assertTrue("Preview should be marked as simplified", result.isPreviewSimplified)
    }

    @Test
    fun `parse without maxMoves stores all moves`() {
        val sb = StringBuilder()
        sb.appendLine("G1 Z0.2")
        sb.appendLine(";LAYER_CHANGE")
        for (i in 1..50) {
            sb.appendLine("G1 X${i} Y${i} E${i * 0.1}")
        }
        val file = writeGcode(sb.toString())

        val result = GcodeParser.parse(file)

        val storedMoves = result.layers.sumOf { it.moves.size }
        assertEquals(50, storedMoves)
        assertEquals(50, result.totalMoves)
        assertFalse("Normal file should not be marked simplified", result.isPreviewSimplified)
    }

    @Test
    fun `parse with maxMoves still tracks per-extruder filament correctly`() {
        val sb = StringBuilder()
        sb.appendLine("G1 Z0.2")
        sb.appendLine(";LAYER_CHANGE")
        sb.appendLine("T0")
        for (i in 1..20) {
            sb.appendLine("G1 X${i} Y0 E${i * 1.0}")
        }
        sb.appendLine("T1")
        for (i in 21..40) {
            sb.appendLine("G1 X${i} Y0 E${i * 1.0}")
        }
        val file = writeGcode(sb.toString())

        // Cap at 10 moves — but filament tracking should still cover all 40 moves
        val result = GcodeParser.parse(file, maxMoves = 10)

        assertEquals(2, result.perExtruderFilamentMm.size)
        assertTrue("T0 filament should be > 0", result.perExtruderFilamentMm[0] > 0f)
        assertTrue("T1 filament should be > 0", result.perExtruderFilamentMm[1] > 0f)
    }

    // Helper extension
    private fun ParsedGcode.moves(layerIndex: Int) = layers[layerIndex].moves
}
