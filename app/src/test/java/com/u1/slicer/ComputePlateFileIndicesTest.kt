package com.u1.slicer

import com.u1.slicer.bambu.ThreeMfInfo
import com.u1.slicer.bambu.ThreeMfPlate
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
}
