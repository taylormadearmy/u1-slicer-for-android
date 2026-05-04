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

    private fun hasExactGcodeLine(gcode: String, expected: String): Boolean =
        gcode.lineSequence().any { it.trim() == expected }

    private fun filamentUsedMm(gcode: String): List<Float> {
        val payload = gcode.lineSequence()
            .firstOrNull { it.trimStart().startsWith("; filament used [mm] = ") }
            ?.substringAfter("=")
            ?: return emptyList()
        return payload.split(",").mapNotNull { it.trim().toFloatOrNull() }
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
        val slotCount = nColors.coerceIn(2, 4)

        val processed = BambuSanitizer.process(input, outDir)
        val config = embedder.buildConfig(
            info = origInfo,
            targetExtruderCount = slotCount
        )

        val embedded = embedder.embed(processed, config, outDir, origInfo)
        assertTrue("loadModel must succeed", lib.loadModel(embedded.absolutePath))

        val result = lib.slice(makeConfig(slotCount))
        assertNotNull("slice() must not return null", result)
        result!!
        assertTrue("$slotCount-extruder Benchy must slice: ${result.errorMessage}", result.success)

        // With N extruders and paint data, there must be tool changes beyond T0
        val gcode = File(result.gcodePath).readText()
        val lines = gcode.lines()
        val t1 = lines.count { it.trimStart().startsWith("T1") }
        assertTrue("$slotCount-extruder SEMM must produce T1 tool changes (got $t1)", t1 > 0)
        // If we have 3+ extruders, T2 must also appear
        if (slotCount >= 3) {
            val t2 = lines.count { it.trimStart().startsWith("T2") }
            assertTrue("$slotCount-extruder SEMM must produce T2 tool changes (got $t2)", t2 > 0)
        }
        // All 4 extruders must be active. Check SM_PRINT_AUTO_FEED lines in the
        // resolved start gcode — OrcaSlicer emits these only for is_extruder_used[N]=true.
        if (slotCount >= 4) {
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
     * B99 crash repro: coloured Benchy with generated supports on PETG E3
     * and support interfaces on PETG E4 must survive the embedded-profile
     * path. This is intentionally stronger than the raw STL support test:
     * it combines Bambu paint segmentation, support/interface extruder
     * selection, PETG canonical filament metadata, and the prime tower.
     */
    @Test
    fun coloredBenchy_supportPetgE3_interfacePetgE4_slicesSuccessfully() {
        val input = asset("colored_3DBenchy (1).3mf")
        val origInfo = ThreeMfParser.parse(input)
        assertTrue("colored_3DBenchy must have hasPaintData=true", origInfo.hasPaintData)

        val processed = BambuSanitizer.process(input, outDir)
        val sourceConfig = java.util.zip.ZipFile(input).use { embedder.parseSourceConfig(it) }
        val embedOverrides: Map<String, Any> = mapOf(
            "enable_support" to "1",
            "support_type" to "normal(auto)",
            "support_threshold_angle" to "45",
            "support_interface_top_layers" to "3",
            "support_interface_bottom_layers" to "0",
            "support_filament" to "3",
            "support_interface_filament" to "4",
            "filament_type" to mutableListOf("PLA", "PLA", "PETG", "PETG"),
            "nozzle_temperature" to mutableListOf("220", "220", "235", "235"),
            "nozzle_temperature_initial_layer" to mutableListOf("220", "220", "235", "235"),
        )
        val config = embedder.buildConfig(
            info = origInfo,
            sourceConfig = sourceConfig,
            overrides = embedOverrides,
            targetExtruderCount = 4
        )
        assertEquals("3", config["support_filament"].toString())
        assertEquals("4", config["support_interface_filament"].toString())

        val embedded = embedder.embed(processed, config, outDir, origInfo)
        assertTrue("loadModel must succeed", lib.loadModel(embedded.absolutePath))

        val sliceConfig = makeConfig(4).copy(
            supportEnabled = true,
            supportType = "normal(auto)",
            supportFilament = 3,
            supportInterfaceFilament = 4,
            extruderTemps = intArrayOf(220, 220, 235, 235),
        )
        val result = lib.slice(sliceConfig)
        assertNotNull("slice() must not return null", result)
        result!!
        assertTrue(
            "Colored Benchy with PETG support E3/interface E4 must slice: ${result.errorMessage}",
            result.success
        )

        val gcode = File(result.gcodePath).readText()
        assertTrue(
            "G-code must preserve support_filament = 3",
            gcode.contains("support_filament = 3")
        )
        assertTrue(
            "G-code must preserve support_interface_filament = 4",
            gcode.contains("support_interface_filament = 4")
        )
        assertTrue(
            "G-code must preserve PETG temps for E3/E4",
            hasExactGcodeLine(gcode, "; nozzle_temperature = 220,220,235,235")
        )
        val used = filamentUsedMm(gcode)
        assertTrue("Support slot E3 must have extrusion, filament used = $used", used.getOrNull(2) ?: 0f > 0f)
    }

    /**
     * B99 crash repro variant: H2C Benchy has seven canonical file filaments
     * mapped onto four physical slots. The reported crash path uses generated
     * supports on PETG E3 and support interfaces on PETG E4, so this test
     * keeps the full seven-entry embedded arrays while marking both physical
     * support slots as PETG.
     */
    @Test
    fun h2cBenchy_supportPetgE3_interfacePetgE4_slicesSuccessfully() {
        val input = asset("3DBenchy-H2C-Multi-Color.3mf")
        val origInfo = ThreeMfParser.parse(input)
        assertTrue("H2C benchy must have hasPaintData=true", origInfo.hasPaintData)
        assertEquals("H2C benchy must detect 7 model colours", 7, origInfo.detectedColors.size)

        val processed = BambuSanitizer.process(input, outDir)
        val sourceConfig = java.util.zip.ZipFile(input).use { embedder.parseSourceConfig(it) }
        val embedOverrides: Map<String, Any> = mapOf(
            "enable_support" to "1",
            "support_type" to "normal(auto)",
            "support_threshold_angle" to "45",
            "support_interface_top_layers" to "3",
            "support_interface_bottom_layers" to "0",
            "support_filament" to "3",
            "support_interface_filament" to "4",
            "filament_type" to mutableListOf("PLA", "PLA", "PETG", "PETG", "PLA", "PLA", "PLA"),
            "nozzle_temperature" to mutableListOf("220", "220", "235", "235", "220", "220", "220"),
            "nozzle_temperature_initial_layer" to mutableListOf("220", "220", "235", "235", "220", "220", "220"),
        )
        val config = embedder.buildConfig(
            info = origInfo,
            sourceConfig = sourceConfig,
            overrides = embedOverrides,
            targetExtruderCount = 7
        )
        assertEquals("3", config["support_filament"].toString())
        assertEquals("4", config["support_interface_filament"].toString())

        val embedded = embedder.embed(processed, config, outDir, origInfo)
        assertTrue("loadModel must succeed", lib.loadModel(embedded.absolutePath))

        val sliceConfig = makeConfig(4).copy(
            supportEnabled = true,
            supportType = "normal(auto)",
            supportFilament = 3,
            supportInterfaceFilament = 4,
            extruderTemps = intArrayOf(220, 220, 235, 235),
        )
        val result = lib.slice(sliceConfig)
        assertNotNull("slice() must not return null", result)
        result!!
        assertTrue(
            "H2C Benchy with PETG support E3/interface E4 must slice: ${result.errorMessage}",
            result.success
        )

        val gcode = File(result.gcodePath).readText()
        assertTrue(
            "G-code must preserve seven canonical filament_type entries with PETG at E3/E4 indices",
            gcode.contains("filament_type = PLA;PLA;PETG;PETG;PLA;PLA;PLA")
        )
        assertTrue(
            "G-code must preserve seven canonical nozzle temps with PETG E3/E4",
            hasExactGcodeLine(gcode, "; nozzle_temperature = 220,220,235,235,220,220,220")
        )
        assertTrue(
            "G-code must preserve support_filament = 3",
            gcode.contains("support_filament = 3")
        )
        assertTrue(
            "G-code must preserve support_interface_filament = 4",
            gcode.contains("support_interface_filament = 4")
        )
        val used = filamentUsedMm(gcode)
        assertTrue("Support slot E3 must have extrusion, filament used = $used", used.getOrNull(2) ?: 0f > 0f)
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
     * B64: SEMM colour permutation — verify that a non-identity colour mapping
     * is applied to the sliced G-code.
     *
     * Flarewing Dragon is a 4-colour SEMM model. Without the permutation fix,
     * the G-code T-indices match the 3MF filament_colour order regardless of the
     * user's colour assignment. With the fix, GcodeToolRemapper rewrites T-indices
     * to match the permuted mapping.
     *
     * Test applies mapping [3,0,2,1] (Color1→E4, Color2→E1, Color3→E3, Color4→E2)
     * and verifies T3 gets the body volume (was T0 before remap).
     */
    @Test
    fun flarewingDragon_semmPermutation_remapsGcodeToolIndices() {
        val input = asset("Flarewing-Dragon_100%_4FilamentMulticolor_v1.1.3mf")
        val origInfo = ThreeMfParser.parse(input)

        assertTrue("Flarewing Dragon must have hasPaintData=true", origInfo.hasPaintData)
        assertEquals("Flarewing Dragon must have 4 detected colors",
            4, origInfo.detectedColors.size)

        // Pipeline: sanitize → embed → load → slice
        val processed = BambuSanitizer.process(input, outDir)
        val config = embedder.buildConfig(
            info = origInfo,
            targetExtruderCount = 4
        )
        val embedded = embedder.embed(processed, config, outDir, origInfo)
        assertTrue("loadModel must succeed", lib.loadModel(embedded.absolutePath))

        val result = lib.slice(makeConfig(4))
        assertNotNull("slice() must not return null", result)
        result!!
        assertTrue("Flarewing Dragon must slice successfully: ${result.errorMessage}", result.success)

        // Before remap: T0 is the body (highest filament usage by far)
        val gcodeBeforeRemap = File(result.gcodePath).readText()
        val usageBefore = (0..3).map { t ->
            gcodeBeforeRemap.lines().count { line -> line.trim() == "T$t" }
        }
        Log.i("SemmTest", "Before remap tool counts: $usageBefore")
        // Sanity check: all 4 tools must be present before remap
        for (t in 0..3) {
            assertTrue("T$t must have tool changes before remap, got $usageBefore",
                usageBefore[t] > 0)
        }

        // Apply permutation [3,0,2,1]: Color1→E4, Color2→E1, Color3→E3, Color4→E2
        // This means: tool index t in original → permutation[t] in remapped output
        val permutation = listOf(3, 0, 2, 1)
        GcodeToolRemapper.remap(result.gcodePath, permutation)

        val gcodeAfterRemap = File(result.gcodePath).readText()
        val usageAfter = (0..3).map { t ->
            gcodeAfterRemap.lines().count { line -> line.trim() == "T$t" }
        }
        Log.i("SemmTest", "After remap tool counts: $usageAfter")

        // Verify the permutation was applied: each original Tx count must appear at
        // the permuted destination index.
        // permutation[0]=3 → T0 before remap becomes T3 after remap
        assertEquals("T3 after remap must equal T0 before remap",
            usageBefore[0], usageAfter[3])
        // permutation[1]=0 → T1 before remap becomes T0 after remap
        assertEquals("T0 after remap must equal T1 before remap",
            usageBefore[1], usageAfter[0])
        // permutation[2]=2 → T2 before remap becomes T2 after remap (identity)
        assertEquals("T2 must be unchanged (identity in this permutation)",
            usageBefore[2], usageAfter[2])
        // permutation[3]=1 → T3 before remap becomes T1 after remap
        assertEquals("T1 after remap must equal T3 before remap",
            usageBefore[3], usageAfter[1])
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

    /**
     * B87: Skywing / Seawing / Silkwing Dragon 3MF — SEMM model with paint_color
     * states up to 8 but only 3 declared filaments (#6FCAEF cyan, #482960 purple,
     * #FFFFFF white). User reports that "black" bottom layers in the Prepare
     * preview disappear from both the G-code preview and the actual print, so the
     * slicer is collapsing paint states rather than folding them to the declared
     * filaments like the Prepare preview does.
     *
     * Red: expect the sliced G-code to contain at least 2 distinct T<n> tool
     * changes (multi-colour), since the model has 3 paint states (0, 4, 8) mapped
     * to 3 filaments. Today's build produces a mono-coloured G-code for this file
     * — all triangles fall back to the object extruder (T0) because OrcaSlicer's
     * paint segmentation interprets states 4/8 as filaments 4/8 which do not
     * exist.
     *
     * Test file: `skywing-seawing-silkwing.3mf` (ASCII-renamed copy of the original
     * `天翼，海翼，丝翼拆件多色.3mf` — assets.open struggles with CJK filenames).
     */
    @Test
    fun skywingSeawingDragon_sliceProducesMultipleToolChanges() {
        val input = asset("skywing-seawing-silkwing.3mf")
        val origInfo = ThreeMfParser.parse(input)

        assertTrue("skywing dragon must have hasPaintData=true", origInfo.hasPaintData)
        val nColors = origInfo.detectedColors.size
        assertTrue(
            "skywing dragon must declare at least 3 filament colours, got ${origInfo.detectedColors}",
            nColors >= 3
        )

        val processed = BambuSanitizer.process(input, outDir)
        val config = embedder.buildConfig(
            info = origInfo,
            targetExtruderCount = nColors.coerceIn(2, 4)
        )
        val embedded = embedder.embed(processed, config, outDir, origInfo)
        assertTrue("loadModel must succeed", lib.loadModel(embedded.absolutePath))

        val slotCount = nColors.coerceIn(2, 4)
        val result = lib.slice(makeConfig(slotCount))
        assertNotNull("slice() must not return null", result)
        result!!
        assertTrue(
            "skywing dragon must slice successfully: ${result.errorMessage}",
            result.success
        )

        val gcode = File(result.gcodePath).readText()
        val lines = gcode.lines()
        val toolCounts = (0..3).map { t -> lines.count { it.trim() == "T$t" } }
        Log.i(
            "SemmSlicingTest",
            "B87 skywing dragon tool counts: T0=${toolCounts[0]} T1=${toolCounts[1]} " +
                "T2=${toolCounts[2]} T3=${toolCounts[3]} (slotCount=$slotCount)"
        )

        val toolsPresent = toolCounts.count { it > 0 }
        assertTrue(
            "B87: skywing dragon has 3 paint states (0, 4, 8) over 3 declared filaments " +
                "— slice output should use >= 2 distinct tools, got $toolsPresent " +
                "(counts=$toolCounts). A single-tool slice means the paint states > nColors " +
                "were silently dropped instead of folded to the declared filament range.",
            toolsPresent >= 2
        )

        // B87 core assertion: the bottom layers (user reports "black on bottom layers
        // disappearing after slicing") must themselves carry a tool change. If the
        // entire bottom shell is emitted with T0 (object extruder), the painted colour
        // never appears on the bed — matching what DC15 printed.
        val layerChanges = lines.mapIndexedNotNull { idx, line ->
            val trimmed = line.trim()
            if (trimmed.startsWith(";LAYER_CHANGE") || trimmed.startsWith("; CHANGE_LAYER")) idx
            else null
        }
        val layer3Index = layerChanges.getOrNull(3) ?: lines.size
        val bottomLines = lines.subList(0, layer3Index)
        val bottomTools = bottomLines
            .mapNotNull { Regex("""^T([0-3])\b""").find(it.trim())?.groupValues?.get(1)?.toIntOrNull() }
            .toSet()
        Log.i(
            "SemmSlicingTest",
            "B87 skywing dragon bottom-3-layers tool set=$bottomTools layerChangesFound=${layerChanges.size}"
        )
        assertTrue(
            "B87: the first 3 printed layers must include at least one painted tool change " +
                "(user reports the 'black' bottom layers disappear in the sliced output). " +
                "bottomTools=$bottomTools — if the set is {0} or empty, paint segmentation " +
                "is not applied to the bottom shell.",
            bottomTools.any { it > 0 }
        )
    }
}
