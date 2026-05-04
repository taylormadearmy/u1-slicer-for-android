package com.u1.slicer

import com.u1.slicer.data.CanonicalFilamentList
import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.FilamentEntry
import com.u1.slicer.data.FilamentSource
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
    fun `header patch types use canonical mapping even when canonical size fits physical slots`() {
        val canonical = CanonicalFilamentList(
            filaments = listOf(
                FilamentEntry(0, "#0086D6", "PLA", FilamentSource.FILE_COLOUR),
                FilamentEntry(1, "#FFFF00", "PLA", FilamentSource.FILE_COLOUR),
                FilamentEntry(2, "#FFFFFF", "PLA", FilamentSource.FILE_COLOUR),
            )
        )
        val presets = listOf(
            ExtruderPreset(index = 0, color = "#0086D6", materialType = "PLA"),
            ExtruderPreset(index = 1, color = "#FFFF00", materialType = "PLA"),
            ExtruderPreset(index = 2, color = "#FFFFFF", materialType = "PETG"),
            ExtruderPreset(index = 3, color = "#6A00D5", materialType = "ABS"),
        )

        val types = resolveFilamentTypesForHeaderPatch(
            canonical = canonical,
            overrides = emptyMap(),
            colorMapping = listOf(0, 1, 2),
            presets = presets,
            filamentLibrary = emptyList(),
        )

        assertEquals(listOf("PLA", "PLA", "PETG"), types)
    }

    @Test
    fun `header patch types keep explicit override above mapped slot material`() {
        val canonical = CanonicalFilamentList(
            filaments = listOf(
                FilamentEntry(0, "#FFFFFF", "PLA", FilamentSource.FILE_COLOUR),
            )
        )
        val presets = listOf(
            ExtruderPreset(index = 2, color = "#FFFFFF", materialType = "PETG"),
        )

        val types = resolveFilamentTypesForHeaderPatch(
            canonical = canonical,
            overrides = mapOf(0 to (null to "TPU")),
            colorMapping = listOf(2),
            presets = presets,
            filamentLibrary = emptyList(),
        )

        // B102: physical-slot-indexed — TPU at slot 2 (E3), PLA defaults for unused slots 0,1.
        assertEquals(listOf("PLA", "PLA", "TPU"), types)
    }

    @Test
    fun `B102 sparse colorMapping produces physical-slot-indexed filament_type`() {
        // colorMapping=[2,3]: canonical T0 → physical E3, canonical T1 → physical E4.
        // After PrintTimeRemap the physical G-code uses T2 and T3. The header must have
        // PETG at positions 2 and 3 so the printer reads filament_type[2]=PETG (not PLA default).
        val canonical = CanonicalFilamentList(
            filaments = listOf(
                FilamentEntry(0, "#FF0000", "PETG", FilamentSource.FILE_COLOUR),
                FilamentEntry(1, "#0000FF", "PETG", FilamentSource.FILE_COLOUR),
            )
        )
        val presets = listOf(
            ExtruderPreset(index = 0, color = "#FFFFFF", materialType = "PLA"),
            ExtruderPreset(index = 1, color = "#FFFFFF", materialType = "PLA"),
            ExtruderPreset(index = 2, color = "#FF0000", materialType = "PETG"),
            ExtruderPreset(index = 3, color = "#0000FF", materialType = "PETG"),
        )

        val types = resolveFilamentTypesForHeaderPatch(
            canonical = canonical,
            overrides = emptyMap(),
            colorMapping = listOf(2, 3),
            presets = presets,
            filamentLibrary = emptyList(),
        )

        assertEquals(listOf("PLA", "PLA", "PETG", "PETG"), types)
    }

    @Test
    fun `header patch pads beyond canonical when support filament uses higher slot`() {
        val canonical = CanonicalFilamentList(
            filaments = listOf(
                FilamentEntry(0, "#0086D6", "PLA", FilamentSource.FILE_COLOUR),
                FilamentEntry(1, "#FFFF00", "PLA", FilamentSource.FILE_COLOUR),
            )
        )
        val presets = listOf(
            ExtruderPreset(index = 0, color = "#0086D6", materialType = "PLA"),
            ExtruderPreset(index = 1, color = "#FFFF00", materialType = "PLA"),
            ExtruderPreset(index = 2, color = "#FFFFFF", materialType = "PETG"),
            ExtruderPreset(index = 3, color = "#6A00D5", materialType = "ABS"),
        )

        val types = resolveFilamentTypesForHeaderPatch(
            canonical = canonical,
            overrides = emptyMap(),
            colorMapping = listOf(0, 1),
            presets = presets,
            filamentLibrary = emptyList(),
            padTo = 4,
        )

        assertEquals(listOf("PLA", "PLA", "PETG", "ABS"), types)
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
