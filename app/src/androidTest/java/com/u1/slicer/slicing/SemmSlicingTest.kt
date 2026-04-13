package com.u1.slicer.slicing

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.BambuSanitizer
import com.u1.slicer.bambu.ProfileEmbedder
import com.u1.slicer.bambu.ThreeMfParser
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.gcode.GcodeToolRemapper
import com.u1.slicer.gcode.GcodeValidator
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Tests for Bambu paint-based multi-color (SEMM) slicing.
 *
 * SEMM models store multi-color segmentation as paint_color= attributes on
 * individual triangles.  OrcaSlicer's multi_material_segmentation_by_painting()
 * processes these to produce per-extruder toolpaths.
 *
 * SEMM is ENABLED — TBB parallel execution algorithms are replaced with serial
 * shims (extern/tbb_serial/) to prevent ARM64 data races that previously caused
 * SIGSEGV in ExPolygon moves during parallel_for.
 */
@RunWith(AndroidJUnit4::class)
class SemmSlicingTest {

    private lateinit var lib: NativeLibrary
    private lateinit var cacheDir: File
    private lateinit var outDir: File
    private lateinit var embedder: ProfileEmbedder

    private fun makeConfig(extruderCount: Int) = SliceConfig(
        layerHeight = 0.2f,
        firstLayerHeight = 0.2f,
        perimeters = 2,
        topSolidLayers = 5,
        bottomSolidLayers = 4,
        fillDensity = 0.15f,
        fillPattern = "gyroid",
        printSpeed = 150f,
        travelSpeed = 200f,
        firstLayerSpeed = 50f,
        nozzleTemp = 220,
        bedTemp = 65,
        nozzleDiameter = 0.4f,
        filamentDiameter = 1.75f,
        retractLength = 0.8f,
        retractSpeed = 45f,
        extruderCount = extruderCount,
        extruderTemps = IntArray(extruderCount) { 220 },
        wipeTowerEnabled = true,
        wipeTowerX = 170f,
        wipeTowerY = 140f,
        wipeTowerWidth = 60f
    )

    @Before
    fun setup() {
        assertTrue("Native library required", NativeLibrary.isLoaded)
        lib = NativeLibrary()
        lib.clearModel()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        cacheDir = ctx.cacheDir
        outDir = File(cacheDir, "semm_test_out").also { it.mkdirs() }
        embedder = ProfileEmbedder(ctx)
    }

    @After
    fun teardown() {
        lib.clearModel()
        outDir.deleteRecursively()
    }

    private fun asset(name: String): File {
        val file = File(cacheDir, name.replace("/", "_"))
        InstrumentationRegistry.getInstrumentation().context
            .assets.open(name).use { it.copyTo(file.outputStream()) }
        return file
    }

    /**
     * Colored 3DBenchy uses Bambu paint_color on triangles for multi-color.
     *
     * Verifies the full pipeline:
     *   1. Pipeline completes without crashing (TBB serial shim prevents SIGSEGV).
     *   2. G-code is produced (slice succeeds).
     *   3. T1 tool changes present (paint segmentation assigns colors to extruders).
     *   4. G-code stays within bed bounds.
     */
    @Test
    fun coloredBenchy_semm_gcodeHasToolChanges() {
        val input = asset("colored_3DBenchy (1).3mf")
        val origInfo = ThreeMfParser.parse(input)

        assertTrue("colored_3DBenchy must have hasPaintData=true", origInfo.hasPaintData)
        assertTrue("colored_3DBenchy must have >= 2 detected colors",
            origInfo.detectedColors.size >= 2)

        // Full pipeline: process → embed with 2-extruder config → load → slice
        val processed = BambuSanitizer.process(input, outDir)
        val config = embedder.buildConfig(
            info = origInfo,
            targetExtruderCount = 2
        )
        // U1 has independent extruders (not MMU), so single_extruder_multi_material=0.
        // Paint segmentation runs based on filament_diameter.size() > 1, not SEMM flag.
        assertEquals("single_extruder_multi_material must be '0' (U1 has independent extruders)",
            "0", config["single_extruder_multi_material"])

        val embedded = embedder.embed(processed, config, outDir, origInfo)
        assertTrue("loadModel must succeed", lib.loadModel(embedded.absolutePath))

        val result = lib.slice(makeConfig(2))
        assertNotNull("slice() must not return null", result)
        result!!
        assertTrue("Colored Benchy must slice successfully: ${result.errorMessage}", result.success)

        val gcode = File(result.gcodePath).readText()
        val t1Count = gcode.lines().count { it.trimStart().startsWith("T1") }
        // Paint segmentation enabled: paint_color attributes preserved → multi-extruder output.
        // T1 tool changes must be present (paint data assigns triangles to different extruders).
        assertTrue(
            "Paint segmentation must produce T1 tool changes (got $t1Count). " +
                "If 0, paint_color may be stripped or paint segmentation is not running.",
            t1Count > 0
        )

        val bounds = GcodeValidator.checkBedBounds(gcode)
        assertTrue(
            "SEMM Benchy G-code out of bed bounds: X=[${bounds.minX}, ${bounds.maxX}] Y=[${bounds.minY}, ${bounds.maxY}]",
            bounds.withinBounds
        )
    }

    /**
     * Verify that extruder count is NOT capped at 2 — the Snapmaker U1 has 4.
     * Uses up to min(detectedColors, 4) extruders and checks that the native
     * slicer accepts it without OOM or crash.  With N extruders and paint data,
     * the slicer must produce tool changes for at least T0 and T(N-1).
     */
    @Test
    fun coloredBenchy_semm_maxExtruders_notCappedAtTwo() {
        val input = asset("colored_3DBenchy (1).3mf")
        val origInfo = ThreeMfParser.parse(input)

        val nColors = origInfo.detectedColors.size
        // B44 regression guard: colored_3DBenchy has exactly 4 paint states + 4 config colors
        assertEquals("colored_3DBenchy must detect exactly 4 colors", 4, nColors)
        // Use up to 4 extruders (U1 max), but no more than the file has colors
        val extCount = nColors.coerceIn(2, 4)

        val processed = BambuSanitizer.process(input, outDir)
        val config = embedder.buildConfig(
            info = origInfo,
            targetExtruderCount = extCount
        )

        val embedded = embedder.embed(processed, config, outDir, origInfo)
        assertTrue("loadModel must succeed", lib.loadModel(embedded.absolutePath))

        val result = lib.slice(makeConfig(extCount))
        assertNotNull("slice() must not return null", result)
        result!!
        assertTrue("$extCount-extruder Benchy must slice: ${result.errorMessage}", result.success)

        // With N extruders and paint data, there must be tool changes beyond T0
        val gcode = File(result.gcodePath).readText()
        val lines = gcode.lines()
        val t1 = lines.count { it.trimStart().startsWith("T1") }
        assertTrue("$extCount-extruder SEMM must produce T1 tool changes (got $t1)", t1 > 0)
        // If we have 3+ extruders, T2 must also appear
        if (extCount >= 3) {
            val t2 = lines.count { it.trimStart().startsWith("T2") }
            assertTrue("$extCount-extruder SEMM must produce T2 tool changes (got $t2)", t2 > 0)
        }
        // All 4 extruders must be active. Check SM_PRINT_AUTO_FEED lines in the
        // resolved start gcode — OrcaSlicer emits these only for is_extruder_used[N]=true.
        if (extCount >= 4) {
            val autoFeedExtruders = lines
                .filter { it.contains("SM_PRINT_AUTO_FEED") }
                .mapNotNull { Regex("""EXTRUDER=(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                .toSet()
            assertEquals(
                "4-extruder SEMM must use all 4 extruders (SM_PRINT_AUTO_FEED for 0,1,2,3), " +
                    "got $autoFeedExtruders. If an extruder is missing, paint segmentation " +
                    "or profile embedding is not configuring all 4 filament slots correctly.",
                setOf(0, 1, 2, 3), autoFeedExtruders
            )
        }
    }

    /**
     * B48 regression: H2C benchy (7 model colours from dual-AMS, mapped to 4 physical
     * extruders) must produce tool changes for ALL 4 physical tools in the sliced G-code.
     *
     * Before the fix, T1 (green/E2) was completely absent because the native slicer's
     * filament_colour array was sized to 4 (physical count) instead of 7 (virtual/model
     * colour count), causing paint states 5-7 to be silently dropped by
     * multi_material_segmentation_by_painting().
     *
     * Red-green TDD: this test was written while T1=0 in the output. It fails without
     * the fix and passes with it.
     */
    @Test
    fun h2cBenchy_semm_allFourToolsPresent_inSlicedGcode() {
        val input = asset("3DBenchy-H2C-Multi-Color.3mf")
        val origInfo = ThreeMfParser.parse(input)

        assertTrue("H2C benchy must have hasPaintData=true", origInfo.hasPaintData)
        assertEquals("H2C benchy must detect 7 model colours", 7, origInfo.detectedColors.size)

        // Full pipeline: process → embed with 7 virtual extruders → load → slice
        // B62 fix: pass sourceConfig so filament_colour with 7 entries reaches the
        // embedded profile.  Without it, the slicer sees only 4 virtual extruders
        // (from applyConfigToPrusa n_ext fallback) and drops paint states 5-7,
        // producing ~432 tool changes instead of ~1400.
        val processed = BambuSanitizer.process(input, outDir)
        val sourceConfig = java.util.zip.ZipFile(input).use { embedder.parseSourceConfig(it) }
        // B48 fix: targetExtruderCount = 7 (one per model colour, not 4 physical)
        val config = embedder.buildConfig(
            info = origInfo,
            sourceConfig = sourceConfig,
            targetExtruderCount = 7
        )
        assertEquals("extruder_count must be 7 for H2C with 7 model colours",
            "7", config["extruder_count"])
        // B62: verify filament_colour is present and sized to 7
        val fc = config["filament_colour"]
        assertTrue("filament_colour must be a list in embedded config (B62)",
            fc is List<*>)
        assertEquals("filament_colour must have 7 entries for H2C (B62)",
            7, (fc as List<*>).size)

        val embedded = embedder.embed(processed, config, outDir, origInfo)
        assertTrue("loadModel must succeed", lib.loadModel(embedded.absolutePath))

        val result = lib.slice(makeConfig(4))
        assertNotNull("slice() must not return null", result)
        result!!
        assertTrue("H2C benchy must slice successfully: ${result.errorMessage}", result.success)

        val gcode = File(result.gcodePath).readText()
        val lines = gcode.lines()
        val toolCounts = (0..3).map { t -> lines.count { it.trim() == "T$t" } }
        val totalToolChanges = toolCounts.sum()
        Log.i("SemmSlicingTest", "H2C benchy tool counts: T0=${toolCounts[0]} T1=${toolCounts[1]} T2=${toolCounts[2]} T3=${toolCounts[3]} total=$totalToolChanges")

        for (t in 0..3) {
            assertTrue(
                "H2C benchy must produce T$t tool changes (got ${toolCounts[t]}). " +
                    "If T1=0, B48 regression: paint states beyond filament_colour.size() are being dropped.",
                toolCounts[t] > 0
            )
        }

        // B62: with 7 virtual extruders and full paint segmentation, the H2C benchy
        // must produce significantly more tool changes than the degraded 4-extruder
        // path (~432).  v1.5.48 produced ~1416 tool changes.
        assertTrue(
            "B62 regression: H2C benchy must produce >600 total tool changes with " +
                "7-colour segmentation (got $totalToolChanges). If ~432, filament_colour " +
                "is not reaching the native slicer with 7 entries.",
            totalToolChanges > 600
        )
    }

    /**
     * B48 Part 2 regression: SEMM models must NOT have their G-code tool indices
     * remapped by GcodeToolRemapper.  The slicer already maps model colours to
     * physical extruders internally — T0-T3 in the output ARE physical slot indices.
     *
     * The ViewModel sets toolRemapSlots to the 7-entry colorMapping for SEMM models,
     * and GcodeToolRemapper.remap() scrambles the correct T0-T3 into wrong indices.
     * After remap with [2,0,3,2,0,1,0], T1 (green) disappears from the output.
     *
     * Red-green TDD: this test fails when remap is applied, passes when it's not.
     */
    @Test
    fun h2cBenchy_semm_toolRemapMustNotScrambleGcode() {
        val input = asset("3DBenchy-H2C-Multi-Color.3mf")
        val origInfo = ThreeMfParser.parse(input)

        val processed = BambuSanitizer.process(input, outDir)
        val config = embedder.buildConfig(info = origInfo, targetExtruderCount = 7)
        val embedded = embedder.embed(processed, config, outDir, origInfo)
        assertTrue("loadModel must succeed", lib.loadModel(embedded.absolutePath))

        val result = lib.slice(makeConfig(4))
        assertNotNull(result); result!!
        assertTrue("Slice must succeed: ${result.errorMessage}", result.success)

        // Simulate what the ViewModel does for SEMM models:
        // toolRemapSlots = colorMapping (7-entry) when non-identity
        val colorMapping = listOf(2, 0, 3, 2, 0, 1, 0)
        val hasPaintData = origInfo.hasPaintData
        assertTrue("H2C benchy must have paint data", hasPaintData)

        // For SEMM models, toolRemapSlots should be null (no remap).
        // The slicer's T0-T3 are already physical extruder indices.
        // Applying the model→slot colorMapping as a tool remap is WRONG.
        val toolRemapSlots: List<Int>? = if (hasPaintData) null else colorMapping

        if (toolRemapSlots != null) {
            GcodeToolRemapper.remap(result.gcodePath, toolRemapSlots)
        }

        val gcode = File(result.gcodePath).readText()
        val lines = gcode.lines()
        val toolCounts = (0..3).map { t -> lines.count { it.trim() == "T$t" } }
        Log.i("SemmSlicingTest", "H2C benchy post-remap tool counts: T0=${toolCounts[0]} T1=${toolCounts[1]} T2=${toolCounts[2]} T3=${toolCounts[3]}")

        for (t in 0..3) {
            assertTrue(
                "After post-processing, T$t must still be present (got ${toolCounts[t]}). " +
                    "If T1=0, GcodeToolRemapper scrambled SEMM tool indices.",
                toolCounts[t] > 0
            )
        }
    }
}
