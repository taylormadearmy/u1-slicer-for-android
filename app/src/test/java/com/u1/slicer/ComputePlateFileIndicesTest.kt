package com.u1.slicer

import com.u1.slicer.bambu.ThreeMfInfo
import com.u1.slicer.bambu.ThreeMfPlate
import com.u1.slicer.data.CanonicalFilamentList
import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.FilamentEntry
import com.u1.slicer.data.FilamentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v2.0.0 post-rubric Bug 2/3 sibling — `computePlateFileIndices` was
 * early-exiting when `plateId < 0`, returning null for single-plate
 * SEMM files (recoveryPlateId stays -1) and forcing the dialog/summary
 * to fall back to the file-wide canonical list. For colored_3DBenchy
 * the canonical palette has 10 entries but the single plate uses 4 —
 * the leak surfaced as a 10-row mapping dialog and 9-chip Slice Summary.
 *
 * Fix: when `plateId < 0` skip the plate.filamentIndices lookup but
 * still consult `info.usedExtruderIndices`, which is plate-narrowed for
 * single-plate files via `mergeThreeMfInfoForPlate`.
 */
class ComputePlateFileIndicesTest {

    private fun info(
        plates: List<ThreeMfPlate> = emptyList(),
        usedExtruderIndices: Set<Int> = emptySet(),
        detectedColors: List<String> = emptyList(),
        hasPaintData: Boolean = false,
    ): ThreeMfInfo = ThreeMfInfo(
        objects = emptyList(),
        plates = plates,
        isBambu = true,
        isMultiPlate = plates.size > 1,
        usedExtruderIndices = usedExtruderIndices,
        detectedColors = detectedColors,
        hasPaintData = hasPaintData,
    )

    @Test
    fun `null info returns null`() {
        assertNull(computePlateFileIndices(null, plateId = -1, canonicalSize = 10))
    }

    @Test
    fun `gcode-driven path supersedes pre-slice heuristics for Border Collie`() {
        // Border Collie: single-plate SEMM, canonical=5, paint states 2 and 3
        // in geometry. Post-slice gcode footer:
        //   ; filament used [mm] = 0.00, 5388.76, 6565.54, 0.00, 0.00
        // Expected plateFileIndices: [1, 2] (non-zero positions).
        // Pre-fix this returned [0,1,2,3,4] (5 entries via hasPaintData detectedColors fallback)
        // — too many dialog rows. Now the gcode-driven path narrows correctly.
        val result = computePlateFileIndices(
            info = info(
                detectedColors = listOf("#AF734E", "#000000", "#FFFFFF", "#00B1B7", "#009D00"),
                hasPaintData = true,
            ),
            plateId = -1,
            canonicalSize = 5,
            perExtruderFilamentMm = listOf(0f, 5388.76f, 6565.54f, 0f, 0f),
        )
        assertEquals(listOf(1, 2), result)
    }

    @Test
    fun `gcode-driven path supersedes pre-slice heuristics for Buzz plate 1`() {
        // Buzz plate 1: multi-plate, canonical=11, sparse high indices used.
        // Native usedExtruderIndices=[1,2,6,9]; plate.filamentIndices=[1].
        // Post-slice gcode footer:
        //   ; filament used [mm] = 12690.18, 1828.95, 0, 0, 0, 1300.19, 0, 0, 632.68, 0, 0
        // Expected plateFileIndices: [0, 1, 5, 8].
        // Pre-fix returned [0] (1 entry via plate.filamentIndices short-circuit)
        // — only 1 dialog row for a 4-colour plate. Now correct.
        val plate = ThreeMfPlate(
            plateId = 1,
            name = "Plate",
            objectIds = listOf("33", "35"),
            filamentIndices = setOf(1),
        )
        val result = computePlateFileIndices(
            info = info(
                plates = listOf(plate),
                usedExtruderIndices = setOf(1, 2, 6, 9),
                detectedColors = listOf("#000000", "#0086D6", "#C12E1F", "#FEC600"),
                hasPaintData = false,
            ),
            plateId = 1,
            canonicalSize = 11,
            perExtruderFilamentMm = listOf(
                12690.18f, 1828.95f, 0f, 0f, 0f, 1300.19f, 0f, 0f, 632.68f, 0f, 0f
            ),
        )
        assertEquals(listOf(0, 1, 5, 8), result)
    }

    @Test
    fun `STL support-driven extruders wider than canonical fall back to raw gcode slots`() {
        // Benchy STL with model on PLA and supports/interfaces forced to PETG:
        //
        //   ; filament used [mm] = 3950.12, 1898.68, 24.64
        //   ; filament_type = PLA;PETG;PETG;PLA
        //   ; support_filament = 2
        //   ; support_interface_filament = 3
        //
        // STL prepare state has one synthetic canonical model filament, but
        // slicing can legitimately activate support/interface extruders. In
        // that shape, returning [0] hides PETG support/interface chips from
        // the Slice Summary. Returning null lets the caller render the raw
        // G-code per-extruder slots instead: Filament 1 PLA, 2 PETG, 3 PETG.
        val result = computePlateFileIndices(
            info = null,
            plateId = -1,
            canonicalSize = 1,
            perExtruderFilamentMm = listOf(3950.12f, 1898.68f, 24.64f),
        )
        assertNull(result)
    }

    @Test
    fun `gcode-driven path falls back to heuristics when perExtruderFilamentMm all zero`() {
        // Defensive: if the gcode parser returned all-zero (corrupt slice or
        // pre-slice state), don't return an empty plateFileIndices — fall
        // through to the pre-slice heuristic chain.
        val result = computePlateFileIndices(
            info = info(
                usedExtruderIndices = setOf(1, 2, 3),
                detectedColors = listOf("#A", "#B", "#C"),
            ),
            plateId = -1,
            canonicalSize = 5,
            perExtruderFilamentMm = listOf(0f, 0f, 0f, 0f, 0f),
        )
        assertEquals(listOf(0, 1, 2), result)
    }

    @Test
    fun `gcode-driven path ignored when null perExtruderFilamentMm (pre-slice)`() {
        val result = computePlateFileIndices(
            info = info(
                usedExtruderIndices = setOf(1, 2),
                detectedColors = listOf("#A", "#B", "#C", "#D"),
            ),
            plateId = -1,
            canonicalSize = 5,
            perExtruderFilamentMm = null,
        )
        assertEquals(listOf(0, 1), result)
    }

    @Test
    fun `zero canonicalSize returns null`() {
        assertNull(computePlateFileIndices(info(), plateId = -1, canonicalSize = 0))
    }

    @Test
    fun `single-plate file with plateId minus one falls back to usedExtruderIndices`() {
        // colored_3DBenchy shape: canonical=10, plate uses 4 (1-based extruders 1..4).
        val result = computePlateFileIndices(
            info = info(usedExtruderIndices = setOf(1, 2, 3, 4)),
            plateId = -1,
            canonicalSize = 10,
        )
        assertEquals(listOf(0, 1, 2, 3), result)
    }

    @Test
    fun `single-plate file with empty usedExtruderIndices returns null`() {
        // No narrowing data available — caller falls back to file-wide canonical.
        assertNull(computePlateFileIndices(info(), plateId = -1, canonicalSize = 10))
    }

    @Test
    fun `SEMM single-plate falls back to detectedColors size when usedExtruderIndices is just default`() {
        // colored_3DBenchy v2.0.0 reproducer: canonical=10, paint-only file;
        // parser leaves usedExtruderIndices = {0} (default-extruder marker
        // that gets filtered out), plate.filamentIndices = {1}. detectedColors
        // is correctly plate-narrowed by the paint-state pass to 4 entries.
        val plate = ThreeMfPlate(
            plateId = 1,
            name = "Plate",
            objectIds = listOf("2", "4"),
            filamentIndices = setOf(1),
        )
        val result = computePlateFileIndices(
            info = info(
                plates = listOf(plate),
                usedExtruderIndices = setOf(0),
                detectedColors = listOf("#0086D6", "#FB0207", "#F4EE2A", "#E2DEDB"),
                hasPaintData = true,
            ),
            plateId = -1,
            canonicalSize = 10,
        )
        assertEquals(listOf(0, 1, 2, 3), result)
    }

    @Test
    fun `SEMM single-plate prefers detectedColors over partial usedExtruderIndices`() {
        // old.3mf reproducer: canonical=6, 6 paint states declared, but the
        // file has 2 volumes referencing extruders {1, 4} (per-volume palette,
        // NOT paint-state palette). Pre-fix usedExtruderIndices took priority
        // and returned [0, 3] → 2 dialog rows. Fix: when hasPaintData is true,
        // detectedColors.size is the authoritative paint-state count.
        val plate = ThreeMfPlate(
            plateId = 1,
            name = "Plate",
            objectIds = listOf("1", "2"),
            filamentIndices = setOf(2, 1),
        )
        val result = computePlateFileIndices(
            info = info(
                plates = listOf(plate),
                usedExtruderIndices = setOf(1, 4),
                detectedColors = listOf("#5B6579", "#F9BBAC", "#E66B06", "#000000", "#795839", "#FFFFFF"),
                hasPaintData = true,
            ),
            plateId = -1,
            canonicalSize = 6,
        )
        assertEquals(listOf(0, 1, 2, 3, 4, 5), result)
    }

    @Test
    fun `detectedColors fallback skipped when canonical is smaller`() {
        // Defensive: detectedColors > canonicalSize should not produce
        // out-of-range indices.
        val result = computePlateFileIndices(
            info = info(detectedColors = listOf("#000", "#111", "#222", "#333", "#444")),
            plateId = -1,
            canonicalSize = 3,
        )
        // detectedSize=5 > canonicalSize=3 → skip fallback, return null
        assertNull(result)
    }

    @Test
    fun `detectedColors fallback skipped when usedExtruderIndices already narrows`() {
        // Button-S shape: usedExtruderIndices = {1, 2, 3, 4} from per-object
        // assignments, detectedColors.size = 4. The usedExtruderIndices path
        // wins (returns first), detectedColors fallback never runs.
        val result = computePlateFileIndices(
            info = info(
                usedExtruderIndices = setOf(1, 2, 3, 4),
                detectedColors = listOf("#A", "#B", "#C", "#D"),
            ),
            plateId = -1,
            canonicalSize = 15,
        )
        assertEquals(listOf(0, 1, 2, 3), result)
    }

    @Test
    fun `multi-plate SEMM prefers detectedColors over undercount plate filamentIndices`() {
        // slip_slide_spin_fidget plate 3 reproducer: SEMM multi-plate where
        // the parser's plate.filamentIndices = [2, 1] (2 entries) undercounts
        // because native enrichment discovered 4 active paint extruders.
        // Slicer emits T0-T3 (all 4). Pre-fix the multi-plate branch
        // short-circuited on plate.filamentIndices and dialog showed only
        // 2 rows, hiding 2 paint slots from user mapping.
        val plate = ThreeMfPlate(
            plateId = 3,
            name = "Plate 3",
            objectIds = listOf("1"),
            filamentIndices = setOf(2, 1),
        )
        val result = computePlateFileIndices(
            info = info(
                plates = listOf(plate),
                usedExtruderIndices = setOf(1, 2, 3, 4),
                detectedColors = listOf("#E4BD68", "#9D2235", "#6F5034", "#F72323"),
                hasPaintData = true,
            ),
            plateId = 3,
            canonicalSize = 10,
        )
        assertEquals(listOf(0, 1, 2, 3), result)
    }

    @Test
    fun `multi-plate file uses plate filamentIndices when present`() {
        val plate = ThreeMfPlate(
            plateId = 1,
            name = "Plate 1",
            objectIds = listOf("1"),
            filamentIndices = setOf(2, 3), // 1-based → 0-based [1, 2]
        )
        val result = computePlateFileIndices(
            info = info(plates = listOf(plate), usedExtruderIndices = setOf(1, 2, 3, 4)),
            plateId = 1,
            canonicalSize = 13,
        )
        assertEquals(listOf(1, 2), result)
    }

    @Test
    fun `multi-plate file with empty plate filamentIndices falls back to usedExtruderIndices`() {
        val plate = ThreeMfPlate(
            plateId = 2,
            name = "Plate 2",
            objectIds = listOf("1"),
            filamentIndices = emptySet(),
        )
        val result = computePlateFileIndices(
            info = info(plates = listOf(plate), usedExtruderIndices = setOf(1, 4)),
            plateId = 2,
            canonicalSize = 6,
        )
        assertEquals(listOf(0, 3), result)
    }

    @Test
    fun `out-of-range filamentIndices are filtered`() {
        val plate = ThreeMfPlate(
            plateId = 1,
            name = "Plate",
            objectIds = listOf("1"),
            filamentIndices = setOf(1, 12, 100), // canonical=4 → only 0 survives
        )
        val result = computePlateFileIndices(
            info = info(plates = listOf(plate)),
            plateId = 1,
            canonicalSize = 4,
        )
        assertEquals(listOf(0), result)
    }

    @Test
    fun `old-3mf shape collisions preserved`() {
        // old.3mf: canonical=6, all 6 file slots present in usedExtruderIndices
        // even when auto-mapping collapses to 4 physical extruders. Chip-strip
        // filtering of zero-mm slots happens downstream in the summary (gcode-
        // driven), not in this index resolver.
        val result = computePlateFileIndices(
            info = info(usedExtruderIndices = setOf(1, 2, 3, 4, 5, 6)),
            plateId = -1,
            canonicalSize = 6,
        )
        assertEquals(listOf(0, 1, 2, 3, 4, 5), result)
    }

    /**
     * B120 regression — `filament_maps = "1 1"` in a 2-filament file (PETG + TPU)
     * was previously parsed as values {1} → canonical {0} (only PETG). The fix
     * collects positions-of-non-zero-values so both positions 0 and 1 are recorded
     * as active → filamentIndices = {1, 2} (1-indexed) → canonical {0, 1}.
     *
     * Jon's file: plate 2 has one object with `extruder: 2` (TPU, canonical 1),
     * but `filament_maps = "1 1"` was parsed as canonical 0 only → plate selector
     * showed only PETG, override flowed to wrong canonical slot, slice-time dialog
     * reported PETG instead of TPU.
     */
    @Test
    fun `B120 filament_maps same-slot assignment detects both file filaments`() {
        // Parsed result of `filament_maps = "1 1"` with the fix:
        // positions 0,1 both non-zero → stored as {1, 2} (1-indexed).
        val plate = ThreeMfPlate(
            plateId = 2,
            name = "Plate 2",
            objectIds = listOf("6"),
            filamentIndices = setOf(1, 2),  // B120 fix: positions-of-nonzero, 1-indexed
        )
        val result = computePlateFileIndices(
            info = info(
                plates = listOf(plate),
                usedExtruderIndices = setOf(1, 2),
            ),
            plateId = 2,
            canonicalSize = 2,  // PETG (0) + TPU (1)
        )
        // Pre-fix returned [0] (PETG only) — missed the TPU object on plate 2.
        assertEquals(listOf(0, 1), result)
    }
}

/**
 * B121 unit tests for [buildWideGcodeMapping].
 *
 * Verifies that the helper synthesises [FilamentSource.SUPPORT_FILAMENT] entries
 * for G-code canonical slots beyond the model's declared canonical list, so the
 * Filament Mapping dialog shows all active extruders when an STL (or multi-STL)
 * is sliced with support/interface on a different extruder.
 */
class BuildWideGcodeMappingTest {

    private fun presets(vararg specs: Pair<String, String>) = specs.mapIndexed { i, (color, mat) ->
        ExtruderPreset(index = i, color = color, materialType = mat)
    }

    private fun canonical(vararg entries: FilamentEntry) = CanonicalFilamentList(
        filaments = entries.toList(),
    )

    private fun entry(idx: Int, color: String = "#FF0000", mat: String? = "TPU") = FilamentEntry(
        fileIndex = idx,
        color = color,
        materialType = mat,
        source = FilamentSource.STL_DEFAULT,
    )

    @Test
    fun `single STL model with PLA support on E2 expands to two rows`() {
        val canon = canonical(entry(0, "#FF0000", "TPU"))
        val presetList = presets("#FF0000" to "TPU", "#00FF00" to "PLA", "#0000FF" to "PLA", "#FFFFFF" to "PLA")
        // G-code used canonical index 0 (TPU model) and 1 (PLA support)
        val (expanded, indices) = buildWideGcodeMapping(
            canon, listOf(6492f, 4792f), presetList
        )!!
        assertEquals(listOf(0, 1), indices)
        assertEquals(2, expanded.filaments.size)
        // Row 0: existing canonical entry preserved
        assertEquals(FilamentSource.STL_DEFAULT, expanded.filaments[0].source)
        assertEquals("TPU", expanded.filaments[0].materialType)
        // Row 1: synthesised from E2 preset
        assertEquals(FilamentSource.SUPPORT_FILAMENT, expanded.filaments[1].source)
        assertEquals("PLA", expanded.filaments[1].materialType)
        assertEquals("#00FF00", expanded.filaments[1].color)
    }

    @Test
    fun `single STL model plus support and interface produces three rows`() {
        val canon = canonical(entry(0, "#FF0000", "TPU"))
        val presetList = presets("#FF0000" to "TPU", "#AAAAAA" to "PLA", "#BBBBBB" to "PETG", "#CCCCCC" to "PLA")
        val (expanded, indices) = buildWideGcodeMapping(
            canon, listOf(3950f, 1899f, 25f), presetList
        )!!
        assertEquals(listOf(0, 1, 2), indices)
        assertEquals(3, expanded.filaments.size)
        assertEquals(FilamentSource.STL_DEFAULT, expanded.filaments[0].source)
        assertEquals(FilamentSource.SUPPORT_FILAMENT, expanded.filaments[1].source)
        assertEquals("PLA", expanded.filaments[1].materialType)
        assertEquals(FilamentSource.SUPPORT_FILAMENT, expanded.filaments[2].source)
        assertEquals("PETG", expanded.filaments[2].materialType)
    }

    @Test
    fun `two STL models with support on E3 expands correctly`() {
        // F77 multi-STL: canonical has 2 entries (model A on E1, model B on E2);
        // support configured on E3 (canonical index 2).
        val canon = canonical(entry(0, "#FF0000", "PLA"), entry(1, "#00FF00", "PLA"))
        val presetList = presets("#FF0000" to "PLA", "#00FF00" to "PLA", "#0000FF" to "PETG", "#FFFFFF" to "PLA")
        val (expanded, indices) = buildWideGcodeMapping(
            canon, listOf(5000f, 3000f, 800f), presetList
        )!!
        assertEquals(listOf(0, 1, 2), indices)
        assertEquals(3, expanded.filaments.size)
        assertEquals(FilamentSource.STL_DEFAULT, expanded.filaments[0].source)
        assertEquals(FilamentSource.STL_DEFAULT, expanded.filaments[1].source)
        assertEquals(FilamentSource.SUPPORT_FILAMENT, expanded.filaments[2].source)
        assertEquals("PETG", expanded.filaments[2].materialType)
    }

    @Test
    fun `returns null when no active indices beyond canonical size`() {
        // All G-code active slots are within canonical — no expansion needed.
        val canon = canonical(entry(0, "#FF0000", "PLA"), entry(1, "#00FF00", "PETG"))
        val presetList = presets("#FF0000" to "PLA", "#00FF00" to "PETG")
        val result = buildWideGcodeMapping(
            canon, listOf(5000f, 3000f), presetList
        )
        assertEquals(null, result)
    }

    @Test
    fun `returns null when perExtruderFilamentMm is all zero`() {
        val canon = canonical(entry(0))
        val presetList = presets("#FF0000" to "TPU", "#00FF00" to "PLA")
        val result = buildWideGcodeMapping(
            canon, listOf(0f, 0f), presetList
        )
        assertEquals(null, result)
    }

    @Test
    fun `sparse active slots — only non-zero indices included`() {
        // G-code used canonical slots 0 (model) and 2 (support); slot 1 is unused.
        val canon = canonical(entry(0, "#FF0000", "PLA"))
        val presetList = presets("#FF0000" to "PLA", "#AAAAAA" to "unused", "#0000FF" to "PETG", "#FFFFFF" to "PLA")
        val (expanded, indices) = buildWideGcodeMapping(
            canon, listOf(5000f, 0f, 800f), presetList
        )!!
        assertEquals(listOf(0, 2), indices)
        assertEquals(2, expanded.filaments.size)
        assertEquals(FilamentSource.STL_DEFAULT, expanded.filaments[0].source)
        assertEquals(FilamentSource.SUPPORT_FILAMENT, expanded.filaments[1].source)
        assertEquals("PETG", expanded.filaments[1].materialType)
    }
}

/**
 * B144 unit tests for [buildMixSlotMapping].
 *
 * A mix-tool-space slice emits G-code in PHYSICAL-SLOT space (E1..E4 baked in),
 * so the Send dialog must show one row per active physical slot — each row
 * carrying that slot's PRESET colour + material and marked as a physical slot
 * (NOT a model "Filament N" or a synthetic SUPPORT_FILAMENT row).
 */
class BuildMixSlotMappingTest {

    private fun presets(vararg specs: Pair<String, String>) = specs.mapIndexed { i, (color, mat) ->
        ExtruderPreset(index = i, color = color, materialType = mat)
    }

    @Test
    fun `active physical slots become one preset-backed row each`() {
        // Slots 0, 2, 3 used (slot 1 idle). Presets E1..E4 distinct.
        val presetList = presets(
            "#FF0000" to "PLA",   // E1
            "#00FF00" to "PETG",  // E2 (idle)
            "#0000FF" to "ABS",   // E3
            "#FFFFFF" to "TPU",   // E4
        )
        val (canon, indices) = buildMixSlotMapping(
            listOf(5f, 0f, 3f, 2f), presetList
        )!!
        // plateFileIndices = active slots, sorted.
        assertEquals(listOf(0, 2, 3), indices)
        assertEquals(3, canon.filaments.size)
        // Row 0 → slot 0 preset (E1 / red / PLA), physical-slot marker.
        assertEquals(0, canon.filaments[0].fileIndex)
        assertEquals("#FF0000", canon.filaments[0].color)
        assertEquals("PLA", canon.filaments[0].materialType)
        assertEquals(FilamentSource.PHYSICAL_SLOT, canon.filaments[0].source)
        // Row 1 → slot 2 preset (E3 / blue / ABS).
        assertEquals(2, canon.filaments[1].fileIndex)
        assertEquals("#0000FF", canon.filaments[1].color)
        assertEquals("ABS", canon.filaments[1].materialType)
        assertEquals(FilamentSource.PHYSICAL_SLOT, canon.filaments[1].source)
        // Row 2 → slot 3 preset (E4 / white / TPU).
        assertEquals(3, canon.filaments[2].fileIndex)
        assertEquals("#FFFFFF", canon.filaments[2].color)
        assertEquals("TPU", canon.filaments[2].materialType)
        assertEquals(FilamentSource.PHYSICAL_SLOT, canon.filaments[2].source)
    }

    @Test
    fun `missing preset for active slot falls back to grey, null material`() {
        // Slot 1 active but only one preset supplied.
        val presetList = presets("#FF0000" to "PLA")
        val (canon, indices) = buildMixSlotMapping(
            listOf(0f, 7f), presetList
        )!!
        assertEquals(listOf(1), indices)
        assertEquals(1, canon.filaments.size)
        assertEquals(1, canon.filaments[0].fileIndex)
        assertEquals("#808080", canon.filaments[0].color)
        assertEquals(null, canon.filaments[0].materialType)
        assertEquals(FilamentSource.PHYSICAL_SLOT, canon.filaments[0].source)
    }

    @Test
    fun `empty perExtruderFilamentMm returns null`() {
        assertNull(buildMixSlotMapping(emptyList(), presets("#FF0000" to "PLA")))
    }

    @Test
    fun `all-zero perExtruderFilamentMm returns null`() {
        assertNull(buildMixSlotMapping(listOf(0f, 0f, 0f, 0f), presets("#FF0000" to "PLA")))
    }
}
