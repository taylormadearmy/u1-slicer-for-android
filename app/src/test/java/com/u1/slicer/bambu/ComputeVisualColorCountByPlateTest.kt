package com.u1.slicer.bambu

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Review 3 pin tests for the per-component-cache aggregator behaviour of
 * `ThreeMfParser.computeVisualColorCountByPlate`. These lock in three properties
 * that the post-Phase-1 parser must keep guaranteeing:
 *
 *   a. Cap-divergence pin — across components with disjoint singleton paint
 *      state sets, the plate union is the full set, NOT truncated by the
 *      old plate-level EarlyExit (which would have stopped at 2 states).
 *   b. B82 plate-1 invariant pin — per-plate `hasPaint` reflects the plate's
 *      own components, not the file-level union (the original Task 6 attempt
 *      regressed this and turned every plate of a multi-plate painted file
 *      "painted").
 *   c. Shared-component cross-plate pin — when two plates reference the same
 *      component path via different object IDs, both plates see the same
 *      paint extruder states AND the per-component scanner is invoked exactly
 *      once. Verifies the Phase 1 dedup of `uniqueComponentPaths.toSet()`.
 *
 * The tests inject a stub `componentScanner` so we can synthesise paint scan
 * results directly instead of building megabyte-scale fake `.model` XML
 * payloads. The real `scanComponentForPaintInfo` has its own per-component
 * cap behaviour; that's not under test here — we test the aggregator that
 * unions the per-component results across plates.
 */
class ComputeVisualColorCountByPlateTest {

    private val tempZips = mutableListOf<File>()

    @After
    fun cleanupZips() {
        tempZips.forEach { runCatching { it.delete() } }
        tempZips.clear()
    }

    /** Build a tiny .3mf-shaped zip with the given empty component entries. The
     *  injected scanner ignores the entry contents, but `zip.getEntry(path)`
     *  must return non-null, so each declared path needs to exist as an entry. */
    private fun makeZipWithEntries(vararg paths: String): ZipFile {
        val tmp = File.createTempFile("compute-visual-pin", ".3mf")
        tempZips.add(tmp)
        ZipOutputStream(tmp.outputStream()).use { zos ->
            for (path in paths) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(ByteArray(0))
                zos.closeEntry()
            }
        }
        return ZipFile(tmp)
    }

    /** Build a fake ComponentPaintInfo with `specCount` distinct spec strings
     *  whose first hex char encodes `state`, and decoded paintExtruderStates
     *  matching `state`. */
    private fun fakeComponentInfo(state: Int, specCount: Int): ThreeMfParser.ComponentPaintInfo {
        require(state in 1..15) { "state must be 1..15 to encode as a single hex char" }
        val hexChar = if (state < 10) ('0' + state) else ('A' + (state - 10))
        val specs = (0 until specCount).map { i -> "$hexChar${"%04X".format(i)}" }.toSet()
        return ThreeMfParser.ComponentPaintInfo(
            paintSpecs = specs,
            allPaintStates = setOf(state),
            paintExtruderStates = setOf(state)
        )
    }

    @Test
    fun `cap-divergence pin - 3 components with disjoint singleton state sets union to full set`() {
        // Pin: locks in the new per-component-cache behaviour. The old plate-level
        // scan would have hit EarlyExit after seeing 2 distinct states across the
        // shared collector and stopped — losing state {3}. The new code scans each
        // component independently into its own ComponentPaintInfo and unions in
        // Phase 2, so all three states show up.
        val pathA = "3D/Objects/a.model"
        val pathB = "3D/Objects/b.model"
        val pathC = "3D/Objects/c.model"
        makeZipWithEntries(pathA, pathB, pathC).use { zip ->
            val plateObjectMap = mapOf(1 to listOf("10", "11", "12"))
            val componentPathsByObject = mapOf(
                "10" to listOf(pathA),
                "11" to listOf(pathB),
                "12" to listOf(pathC)
            )
            val cannedScans = mapOf(
                pathA to fakeComponentInfo(state = 1, specCount = 35),
                pathB to fakeComponentInfo(state = 2, specCount = 35),
                pathC to fakeComponentInfo(state = 3, specCount = 35)
            )
            val pathSeen = mutableListOf<String>()
            val scanner: (String, java.io.InputStream) -> ThreeMfParser.ComponentPaintInfo =
                { path, input ->
                    input.close()
                    pathSeen.add(path)
                    cannedScans.getValue(path)
                }

            val visual = ThreeMfParser.computeVisualColorCountByPlate(
                zip = zip,
                modelEntry = null,
                plateObjectMap = plateObjectMap,
                componentPathsByObject = componentPathsByObject,
                extruderAssignments = emptyMap(),
                componentScanner = scanner
            )

            val plate = visual[1]
            assertNotNull("plate 1 must be present in result", plate)
            assertEquals(
                "paintExtruderStates must union to full disjoint set, NOT truncate to 2",
                setOf(1, 2, 3),
                plate!!.paintExtruderStates
            )
            // count = objectExtruderSet.size + paintVisualCount; objectExtruderSet
            // is empty (no extruderAssignments), and 105 specs vs 3 states is the
            // complex-encoding path (paintSpecs.size != allPaintStates.size), so
            // count == paintExtruderStates.size == 3.
            assertEquals(3, plate.count)
            assertTrue(plate.hasPaint)
            assertEquals(setOf(pathA, pathB, pathC), pathSeen.toSet())
            assertEquals(3, pathSeen.size)
        }
    }

    @Test
    fun `B82 plate-1 invariant pin - empty plate stays unpainted while painted plate is painted`() {
        // Pin: synthetic 2-plate map. Plate 1 references an unpainted component
        // (paintExtruderStates = empty); plate 5 references a painted one.
        // Per-plate aggregation must NOT bleed paint state across plates.
        val unpainted = "3D/Objects/unpainted.model"
        val painted = "3D/Objects/painted.model"
        makeZipWithEntries(unpainted, painted).use { zip ->
            val plateObjectMap = mapOf(
                1 to listOf("100"),
                5 to listOf("500")
            )
            val componentPathsByObject = mapOf(
                "100" to listOf(unpainted),
                "500" to listOf(painted)
            )
            val emptyInfo = ThreeMfParser.ComponentPaintInfo(
                paintSpecs = emptySet(),
                allPaintStates = emptySet(),
                paintExtruderStates = emptySet()
            )
            val cannedScans = mapOf(
                unpainted to emptyInfo,
                painted to fakeComponentInfo(state = 3, specCount = 50)
            )
            val scanner: (String, java.io.InputStream) -> ThreeMfParser.ComponentPaintInfo =
                { path, input ->
                    input.close()
                    cannedScans.getValue(path)
                }

            val visual = ThreeMfParser.computeVisualColorCountByPlate(
                zip = zip,
                modelEntry = null,
                plateObjectMap = plateObjectMap,
                componentPathsByObject = componentPathsByObject,
                extruderAssignments = emptyMap(),
                componentScanner = scanner
            )

            val plate1 = visual[1]
            val plate5 = visual[5]
            assertNotNull(plate1); assertNotNull(plate5)
            assertFalse(
                "plate 1 references only unpainted components — must NOT report hasPaint",
                plate1!!.hasPaint
            )
            assertEquals(emptySet<Int>(), plate1.paintExtruderStates)
            assertTrue(
                "plate 5 references a painted component — must report hasPaint",
                plate5!!.hasPaint
            )
            assertEquals(setOf(3), plate5.paintExtruderStates)
        }
    }

    @Test
    fun `shared-component cross-plate pin - same path via different object IDs scans once`() {
        // Pin: two plates referencing the same component path via different
        // object IDs must agree on paintExtruderStates AND the underlying
        // scanner must run exactly once. Verifies Phase 1's dedup of
        // uniqueComponentPaths.toSet() — without it, multi-plate fixtures like
        // Buzz Lightyear that share component files across plates would re-scan
        // the same 50 MB file 8+ times (B91 perf path).
        val shared = "3D/Objects/shared.model"
        makeZipWithEntries(shared).use { zip ->
            val plateObjectMap = mapOf(
                1 to listOf("A1"),
                2 to listOf("B1")
            )
            val componentPathsByObject = mapOf(
                "A1" to listOf(shared),
                "B1" to listOf(shared)
            )
            val scanCalls = mutableListOf<String>()
            val scanner: (String, java.io.InputStream) -> ThreeMfParser.ComponentPaintInfo =
                { path, input ->
                    input.close()
                    scanCalls.add(path)
                    fakeComponentInfo(state = 2, specCount = 40)
                }

            val visual = ThreeMfParser.computeVisualColorCountByPlate(
                zip = zip,
                modelEntry = null,
                plateObjectMap = plateObjectMap,
                componentPathsByObject = componentPathsByObject,
                extruderAssignments = emptyMap(),
                componentScanner = scanner
            )

            assertEquals(
                "shared component must scan exactly once across plates",
                listOf(shared),
                scanCalls
            )
            val plate1 = visual[1]
            val plate2 = visual[2]
            assertNotNull(plate1); assertNotNull(plate2)
            assertEquals(
                "both plates referencing the same component must see the same paint states",
                plate1!!.paintExtruderStates,
                plate2!!.paintExtruderStates
            )
            assertEquals(setOf(2), plate1.paintExtruderStates)
        }
    }
}
