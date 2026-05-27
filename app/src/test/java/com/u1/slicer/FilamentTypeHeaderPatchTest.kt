package com.u1.slicer

import com.u1.slicer.data.CanonicalFilamentList
import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.FilamentEntry
import com.u1.slicer.data.FilamentProfile
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
        // B102 (physical-slot indexing) + B128 (declared file material wins for
        // an injective multi-colour mapping): the file declares PETG for
        // filament 3, mapped to physical slot 2 whose preset is ABS. The header
        // must carry the file's PETG at physical position 2 — proving both that
        // the array is physical-slot-indexed AND that the file's declared
        // material beats the slot preset for a declared, injective filament.
        val canonical = CanonicalFilamentList(
            filaments = listOf(
                FilamentEntry(0, "#0086D6", "PLA", FilamentSource.FILE_COLOUR),
                FilamentEntry(1, "#FFFF00", "PLA", FilamentSource.FILE_COLOUR),
                FilamentEntry(2, "#FFFFFF", "PETG", FilamentSource.FILE_COLOUR),
            )
        )
        val presets = listOf(
            ExtruderPreset(index = 0, color = "#0086D6", materialType = "PLA"),
            ExtruderPreset(index = 1, color = "#FFFF00", materialType = "PLA"),
            ExtruderPreset(index = 2, color = "#FFFFFF", materialType = "ABS"),
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

    // --- B105: resolveNonCanonicalHeaderPatchTypes ---

    @Test
    fun `B105 single-slot E3 produces one-element type list not four`() {
        val presets = listOf(
            ExtruderPreset(index = 0, color = "#FFFFFF", materialType = "PLA"),
            ExtruderPreset(index = 1, color = "#FFFFFF", materialType = "PLA"),
            ExtruderPreset(index = 2, color = "#FF0000", materialType = "PLA"),
            ExtruderPreset(index = 3, color = "#0000FF", materialType = "PLA"),
        )
        val types = resolveNonCanonicalHeaderPatchTypes(listOf(2), presets)
        assertEquals("B105: single-slot E3 must produce exactly 1 type entry", 1, types.size)
        assertEquals("PLA", types[0])
    }

    @Test
    fun `B105 single-slot E3 with PETG preset emits PETG`() {
        val presets = listOf(
            ExtruderPreset(index = 0, color = "#FFFFFF", materialType = "PLA"),
            ExtruderPreset(index = 1, color = "#FFFFFF", materialType = "PLA"),
            ExtruderPreset(index = 2, color = "#FF0000", materialType = "PETG"),
            ExtruderPreset(index = 3, color = "#0000FF", materialType = "PLA"),
        )
        val types = resolveNonCanonicalHeaderPatchTypes(listOf(2), presets)
        assertEquals(1, types.size)
        assertEquals("PETG", types[0])
    }

    @Test
    fun `B105 two-slot E2 and E3 produces two-element type list`() {
        val presets = listOf(
            ExtruderPreset(index = 0, color = "#FFFFFF", materialType = "PLA"),
            ExtruderPreset(index = 1, color = "#FF0000", materialType = "ABS"),
            ExtruderPreset(index = 2, color = "#00FF00", materialType = "PETG"),
            ExtruderPreset(index = 3, color = "#0000FF", materialType = "PLA"),
        )
        val types = resolveNonCanonicalHeaderPatchTypes(listOf(1, 2), presets)
        assertEquals(listOf("ABS", "PETG"), types)
    }

    @Test
    fun `B105 unknown slot falls back to PLA`() {
        val presets = listOf(
            ExtruderPreset(index = 0, color = "#FFFFFF", materialType = "PLA"),
        )
        val types = resolveNonCanonicalHeaderPatchTypes(listOf(3), presets)
        assertEquals(listOf("PLA"), types)
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

    // --- B110: nozzle_temperature header patch + alignment with filament_type ---

    @Test
    fun `B110 nozzle temps header patch comma-joined for 4 extruders`() {
        val f = gcode(
            "; nozzle_temperature = 200,200,200,200",
            "G28"
        )
        assertTrue(fixNozzleTemperatureHeader(f.absolutePath, listOf(220, 235, 270, 225)))
        val lines = f.readLines()
        assertEquals("; nozzle_temperature = 220,235,270,225", lines[0])
        assertEquals("G28", lines[1])
    }

    @Test
    fun `B110 nozzle temps header patch also patches initial_layer variant`() {
        val f = gcode(
            "; nozzle_temperature = 200,200,200,200",
            "; nozzle_temperature_initial_layer = 200,200,200,200",
            "G28"
        )
        assertTrue(fixNozzleTemperatureHeader(f.absolutePath, listOf(220, 235, 270, 225)))
        val lines = f.readLines()
        assertEquals("; nozzle_temperature = 220,235,270,225", lines[0])
        assertEquals("; nozzle_temperature_initial_layer = 220,235,270,225", lines[1])
        assertEquals("G28", lines[2])
    }

    @Test
    fun `B110 nozzle temps header patch returns false when no header line present`() {
        val f = gcode("; filament_density = 1.24", "G28")
        val before = f.readText()
        assertFalse(fixNozzleTemperatureHeader(f.absolutePath, listOf(220, 235)))
        assertEquals(before, f.readText())
    }

    @Test
    fun `B110 nozzle temps header patch returns false for empty temps list`() {
        val f = gcode("; nozzle_temperature = 220")
        assertFalse(fixNozzleTemperatureHeader(f.absolutePath, emptyList()))
        assertEquals("; nozzle_temperature = 220\n", f.readText())
    }

    @Test
    fun `B110 resolveNozzleTempsForHeaderPatch produces physical-slot-indexed array`() {
        // Mirrors the canonical "header patch" sparse colorMapping test for filament_type:
        // canonical T0 → physical E3 (slot 2), canonical T1 → physical E4 (slot 3).
        // PETG presets at slots 2 and 3 should land in positions 2 and 3 of the result.
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

        val temps = resolveNozzleTempsForHeaderPatch(
            canonical = canonical,
            overrides = emptyMap(),
            colorMapping = listOf(2, 3),
            presets = presets,
            filamentLibrary = emptyList(),
        )

        // PLA defaults to 220, PETG to 235. Sparse mapping leaves slot 0/1 as PLA defaults
        // (filled from presets), and slot 2/3 carry PETG temps from the canonical resolution.
        assertEquals(listOf(220, 220, 235, 235), temps)
    }

    @Test
    fun `B110 header patch filament_type and nozzle_temperature agree on PETG slot index after override`() {
        // The B110 scenario verbatim: H2C-shaped canonical filament list with all PLA, then
        // user overrides Filament 1 (fileIndex 0) → PETG via Prepare screen.
        // colorMapping mimics H2C: 7 file filaments → 4 physical slots.
        //
        // Acceptance criterion #2: the index where filament_type = PETG must equal the index
        // where nozzle_temperature = 235.
        val canonical = CanonicalFilamentList(
            filaments = listOf(
                FilamentEntry(0, "#0086D6", "PLA", FilamentSource.FILE_COLOUR),
                FilamentEntry(1, "#FFFF00", "PLA", FilamentSource.FILE_COLOUR),
                FilamentEntry(2, "#FFFFFF", "PLA", FilamentSource.FILE_COLOUR),
                FilamentEntry(3, "#6A00D5", "PLA", FilamentSource.FILE_COLOUR),
                FilamentEntry(4, "#FF6A13", "PLA", FilamentSource.FILE_COLOUR),
                FilamentEntry(5, "#00FF00", "PLA", FilamentSource.FILE_COLOUR),
                FilamentEntry(6, "#FF0000", "PLA", FilamentSource.FILE_COLOUR),
            )
        )
        val presets = listOf(
            ExtruderPreset(index = 0, color = "#0086D6", materialType = "PLA"),
            ExtruderPreset(index = 1, color = "#FFFF00", materialType = "PLA"),
            ExtruderPreset(index = 2, color = "#FFFFFF", materialType = "PLA"),
            ExtruderPreset(index = 3, color = "#6A00D5", materialType = "PLA"),
        )
        // H2C-style mapping: 7 file filaments distributed across 4 physical slots.
        // canonical[0] → slot 0 (user-tagged PETG override).
        val colorMapping = listOf(0, 1, 2, 3, 0, 1, 2)

        // User override: fileIndex 0 → PETG (Prepare screen Filament 1 → PETG).
        val overrides = mapOf(0 to (null to "PETG"))

        val types = resolveFilamentTypesForHeaderPatch(
            canonical = canonical,
            overrides = overrides,
            colorMapping = colorMapping,
            presets = presets,
            filamentLibrary = emptyList(),
        )
        val temps = resolveNozzleTempsForHeaderPatch(
            canonical = canonical,
            overrides = overrides,
            colorMapping = colorMapping,
            presets = presets,
            filamentLibrary = emptyList(),
        )

        assertEquals("filament_type and nozzle_temperature arrays must have equal length",
            types.size, temps.size)

        val petgIndex = types.indexOf("PETG")
        assertTrue("Expected a PETG entry in filament_type, got $types", petgIndex >= 0)
        assertEquals(
            "B110: nozzle_temperature must read 235°C at the same slot index where " +
                "filament_type reads PETG (types=$types, temps=$temps)",
            235, temps[petgIndex]
        )
        // Every other slot must remain at the PLA default (220) — no temp leaking onto slot 0
        // when the PETG override applied to a different physical slot.
        for ((i, t) in temps.withIndex()) {
            if (i == petgIndex) continue
            assertEquals(
                "Slot $i must remain PLA (220) when PETG override applies to slot $petgIndex " +
                    "(types=$types, temps=$temps)",
                220, t
            )
        }
    }

    @Test
    fun `B110 override on canonical fileIndex 0 with H2C-shaped mapping aligns both arrays`() {
        // Reproduces the exact scenario from the brief: Prepare → Filament 1 → PETG.
        // colorMapping is identity (canonical → physical slot i = i) for the first 4 file
        // filaments, then folds 5/6/7 back onto slots 0/1/2 (Bambu H2C-style fold).
        val canonical = CanonicalFilamentList(
            filaments = (0 until 7).map { i ->
                FilamentEntry(i, "#FFFFFF", "PLA", FilamentSource.FILE_COLOUR)
            }
        )
        val presets = (0 until 4).map { idx ->
            ExtruderPreset(index = idx, color = "#FFFFFF", materialType = "PLA")
        }
        val colorMapping = listOf(0, 1, 2, 3, 0, 1, 2)
        val overrides = mapOf(0 to (null to "PETG"))

        val types = resolveFilamentTypesForHeaderPatch(
            canonical = canonical,
            overrides = overrides,
            colorMapping = colorMapping,
            presets = presets,
            filamentLibrary = emptyList(),
        )
        val temps = resolveNozzleTempsForHeaderPatch(
            canonical = canonical,
            overrides = overrides,
            colorMapping = colorMapping,
            presets = presets,
            filamentLibrary = emptyList(),
        )

        // Both arrays must agree on indexing. The B102 contract pads the result to
        // max(maxPhysicalSlot+1, padTo); padTo defaults to canonical.size=7, so the
        // arrays are 7 entries long. The PETG override on fileIndex 0 maps to physical
        // slot 0 (per colorMapping[0]=0), so slot 0 carries PETG/235 in BOTH arrays.
        // All other slots stay at the preset defaults (PLA/220).
        assertEquals(listOf("PETG", "PLA", "PLA", "PLA", "PLA", "PLA", "PLA"), types)
        assertEquals(listOf(235, 220, 220, 220, 220, 220, 220), temps)
    }

    @Test
    fun `B110 resolveNonCanonicalHeaderPatchTemps derives temps from preset materialType`() {
        val presets = listOf(
            ExtruderPreset(index = 0, color = "#FFFFFF", materialType = "PLA"),
            ExtruderPreset(index = 1, color = "#FF0000", materialType = "PETG"),
            ExtruderPreset(index = 2, color = "#00FF00", materialType = "ABS"),
            ExtruderPreset(index = 3, color = "#0000FF", materialType = "TPU"),
        )
        val temps = resolveNonCanonicalHeaderPatchTemps(
            usedSlots = listOf(0, 1, 2, 3),
            presets = presets,
            filamentLibrary = emptyList(),
        )
        assertEquals(listOf(220, 235, 270, 225), temps)
    }

    @Test
    fun `B110 resolveNonCanonicalHeaderPatchTemps prefers linked filament profile temp`() {
        // When a slot has a linked filament profile, use the profile's nozzleTemp instead
        // of the materialType default — mirrors the override-free branch of resolvePerFilamentTypeAndTemp.
        val library = listOf(
            FilamentProfile(
                id = 99L,
                name = "My PETG @240",
                material = "PETG",
                nozzleTemp = 240,
                bedTemp = 70,
                retractLength = 0.8f,
                retractSpeed = 45f,
                color = "#FF0000",
            )
        )
        val presets = listOf(
            ExtruderPreset(index = 2, color = "#FF0000", materialType = "PETG", filamentProfileId = 99L),
        )
        val temps = resolveNonCanonicalHeaderPatchTemps(
            usedSlots = listOf(2),
            presets = presets,
            filamentLibrary = library,
        )
        assertEquals(listOf(240), temps)
    }
}
