package com.u1.slicer

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * B63: fixFilamentTypeHeader — replaces `; filament_type = ...` in G-code header
 * with the actual per-extruder material types.
 */
class FilamentTypeHeaderPatchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun gcode(vararg lines: String): File {
        return tmp.newFile("test.gcode").also { it.writeText(lines.joinToString("\n") + "\n") }
    }

    @Test
    fun `replaces PLA with PETG for single extruder`() {
        val f = gcode("; filament_type = PLA", "G28")
        assertTrue(fixFilamentTypeHeader(f.absolutePath, listOf("PETG")))
        val lines = f.readLines()
        assertEquals("; filament_type = PETG", lines[0])
        assertEquals("G28", lines[1])
    }

    @Test
    fun `replaces with semicolon-joined list for 4 extruders`() {
        val f = gcode(
            "; filament_colour = #FF0000;#00FF00;#0000FF;#FFFFFF",
            "; filament_type = PLA",
            "G28"
        )
        assertTrue(fixFilamentTypeHeader(f.absolutePath, listOf("PETG", "ABS", "TPU", "PLA")))
        val lines = f.readLines()
        assertEquals("; filament_colour = #FF0000;#00FF00;#0000FF;#FFFFFF", lines[0])
        assertEquals("; filament_type = PETG;ABS;TPU;PLA", lines[1])
        assertEquals("G28", lines[2])
    }

    @Test
    fun `returns false and leaves file unchanged when header line absent`() {
        val f = gcode("; filament_density = 1.24", "G28")
        val before = f.readText()
        assertFalse(fixFilamentTypeHeader(f.absolutePath, listOf("PETG")))
        assertEquals(before, f.readText())
    }

    @Test
    fun `returns false for empty filamentTypes list`() {
        val f = gcode("; filament_type = PLA")
        assertFalse(fixFilamentTypeHeader(f.absolutePath, emptyList()))
        assertEquals("; filament_type = PLA\n", f.readText())
    }

    @Test
    fun `returns false for non-existent file`() {
        assertFalse(fixFilamentTypeHeader("/nonexistent/path.gcode", listOf("PETG")))
    }

    @Test
    fun `only replaces first occurrence`() {
        val f = gcode("; filament_type = PLA", "; filament_type = ABS", "G28")
        assertTrue(fixFilamentTypeHeader(f.absolutePath, listOf("PETG")))
        val lines = f.readLines()
        assertEquals("; filament_type = PETG", lines[0])
        assertEquals("; filament_type = ABS", lines[1])
    }
}
