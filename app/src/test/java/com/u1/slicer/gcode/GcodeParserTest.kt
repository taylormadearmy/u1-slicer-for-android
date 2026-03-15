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

    // Helper extension
    private fun ParsedGcode.moves(layerIndex: Int) = layers[layerIndex].moves
}
