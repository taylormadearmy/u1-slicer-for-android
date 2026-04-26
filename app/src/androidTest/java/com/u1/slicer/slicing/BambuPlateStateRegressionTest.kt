package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.BambuSanitizer
import com.u1.slicer.bambu.NativePlateState
import com.u1.slicer.bambu.ProfileEmbedder
import com.u1.slicer.bambu.ThreeMfInfo
import com.u1.slicer.bambu.ThreeMfParser
import com.u1.slicer.data.SliceConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Tier A regression tests for the 6 PM-reported plate state bugs from the
 * v1.7.0 Bambu refactor. Each test exercises a production-equivalent embed
 * + load + (optional slice) flow and asserts on the ENRICHED extruder set
 * the UI sees — i.e. native usedExtruders unioned with the file-level
 * objectPartExtruders for the plate's objects, mirroring
 * [SlicerViewModel.buildThreeMfInfoFromNative].
 */
@RunWith(AndroidJUnit4::class)
class BambuPlateStateRegressionTest {

    private lateinit var lib: NativeLibrary
    private lateinit var embedder: ProfileEmbedder
    private lateinit var cacheDir: File
    private lateinit var outDir: File

    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val assetContext get() = InstrumentationRegistry.getInstrumentation().context

    private fun copyAsset(name: String): File {
        val out = File(cacheDir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        assetContext.assets.open(name).use { it.copyTo(out.outputStream()) }
        return out
    }

    @Before
    fun setup() {
        assertTrue("Native library must be loaded", NativeLibrary.isLoaded)
        lib = NativeLibrary()
        lib.clearModel()
        cacheDir = File(targetContext.cacheDir, "plate_state_test").also { it.mkdirs() }
        outDir = File(cacheDir, "out").also { it.mkdirs() }
        embedder = ProfileEmbedder(targetContext)
    }

    @After
    fun teardown() {
        lib.clearModel()
        cacheDir.deleteRecursively()
    }

    /**
     * Embed (with optional plate filter) + load. Returns native plate state.
     * Mirrors SlicerViewModel.selectPlate's routing:
     *   - multi-plate Bambu: ProfileEmbedder.embed(plateId=N) (sub-plan #2d).
     *   - single-plate Bambu: BambuSanitizer.process to strip Bambu xmlns,
     *     then embed.
     */
    @Suppress("DEPRECATION")
    private fun embedAndLoad(
        assetName: String,
        plateId: Int? = null,
        targetExtruderCountOverride: Int? = null,
        sourceConfigForEmbed: Boolean = false
    ): Pair<ThreeMfInfo, NativePlateState> {
        val file = copyAsset(assetName)
        val info = ThreeMfParser.parse(file)
        val target = targetExtruderCountOverride
            ?: info.detectedExtruderCount.coerceAtLeast(1)
        val sourceConfig = if (sourceConfigForEmbed)
            java.util.zip.ZipFile(file).use { embedder.parseSourceConfig(it) }
        else null
        val config = embedder.buildConfig(
            info = info,
            sourceConfig = sourceConfig,
            targetExtruderCount = target
        )
        val sourceForEmbed = when {
            info.isMultiPlate -> file
            info.isBambu -> BambuSanitizer.process(file, outDir)
            else -> file
        }
        val embedded = embedder.embed(sourceForEmbed, config, outDir, info, plateId = plateId)
        assertTrue(
            "loadModel must succeed for $assetName plate=$plateId",
            lib.loadModel(embedded.absolutePath)
        )
        return Pair(info, NativePlateState.parseVolumeMapJson(lib.nativeGetAllVolumeExtruders()))
    }

    // --- Bug #1 / #2: Dragon Scale plate 3 — must enrich to 3 extruders ---

    @Test
    fun bug1_dragon_scale_plate3_enriches_to_three_extruders() {
        val (info, state) = embedAndLoad("Dragon Scale infinity.3mf", plateId = 2)
        val enriched = enrichedUsedExtruders(lib, info, state, plateIndex0Based = 2)
        assertTrue(
            "Dragon Scale plate 3 must enrich to >= 3 extruders. " +
                "native=${state.usedExtruders}, enriched=$enriched",
            enriched.size >= 3
        )
    }

    // --- Bug #2: F1 calendar plate 1 — multi-extruder detection ---
    //
    // The PM-reported bug was "preview missing colour 4; sliced base colour
    // wrong". On the post-refactor branch native + enrichment reports
    // [1, 2, 3] for plate 1 — three distinct object extruders. The PM's
    // "missing 4th colour" claim could not be reproduced as an extruder-count
    // gap (the file's plate 1 genuinely has 3 distinct object extruders
    // post-embed); if a future investigation surfaces a 4th, bump to == 4 and
    // treat the regression as live.
    //
    // Asserted as `== 3` (not `>= 3`) per Review 4 follow-up: with `>=` a
    // future regression that drops one of the three extruders would still
    // pass at the boundary. `==` catches drift in either direction.
    @Test
    fun bug2_f1_calendar_plate1_exactly_three_extruders() {
        val (info, state) = embedAndLoad(
            "2026+F1+CALENDAR+-+DATES+&+TRACK+NAMES+(P_X+SERIES).3mf",
            plateId = 0
        )
        val enriched = enrichedUsedExtruders(lib, info, state, plateIndex0Based = 0)
        assertEquals(
            "F1 calendar plate 1 must enrich to exactly 3 extruders. " +
                "native=${state.usedExtruders}, enriched=$enriched",
            3,
            enriched.size
        )
    }

    // --- Bug #3: Hanging file — translate preserved through slice ---
    //
    // PM bug #3 was "drag the hanging file to the right, slice it, the model
    // is sliced at its file-baked position rather than the dragged target".
    // FIXED by ea420ea (bypass raw_bounding_box cache for setModelInstances
    // offset math) and verified manually by the user post-merge.
    //
    // The earlier in-place test was structurally broken: it asserted
    // `getInstanceOffsets()[0] ~= targetX`, but per ea420ea's convention
    //   inst.offset = targetX - effectiveBB.min.x()
    // — for a centred mesh `effectiveBB.min.x ≈ -size·sf/2`, so the stored
    // offset diverges from `targetX` by half-mesh-width by design. The right
    // shape is to slice and assert sliced G-code minX/Y matches the dragged
    // target, mirroring
    // [SetModelInstancesOffsetTest.calicubeScaleSingleCopy_offsetMatchesGcodeMinX].
    //
    // Active coverage of the same code path lives in
    // [SetModelInstancesOffsetTest] (calicube + STL multi-copy variants); a
    // hanging-file-specific slice-and-assert variant is filed as a follow-up
    // (slicing the 19MB / 8M-tri fixture in CI needs a process-isolated
    // long-running test). Until then, `@Ignore` remains accurate but no
    // longer hides an unfixed bug — only an unwritten assertion shape.

    // --- Bug #5: H2C benchy — multi-tool G-code post-slice ---

    @Test
    fun bug5_h2c_benchy_multi_tool_gcode() {
        // H2C benchy uses 7 model colours (dual-AMS) folded to 4 physical extruders.
        // SemmSlicingTest already gates the full 7-colour path; here we only need
        // to verify multi-tool G-code is produced — the cheaper proxy for "colours
        // not collapsed by my Task 4-6 changes".
        val file = copyAsset("3DBenchy-H2C-Multi-Color.3mf")
        val info = ThreeMfParser.parse(file)
        assumeTrue("H2C benchy must report paint data", info.hasPaintData)

        val processed = BambuSanitizer.process(file, outDir)
        val sourceConfig = java.util.zip.ZipFile(file).use { embedder.parseSourceConfig(it) }
        val config = embedder.buildConfig(
            info = info,
            sourceConfig = sourceConfig,
            targetExtruderCount = info.detectedColors.size.coerceAtLeast(2)
        )
        val embedded = embedder.embed(processed, config, outDir, info)
        assertTrue("loadModel must succeed", lib.loadModel(embedded.absolutePath))

        val result = lib.slice(SliceConfig().copy(extruderCount = 4))
        assertNotNull("Slice must succeed", result)
        assertTrue("Slice success flag", result!!.success)
        val gcode = File(result.gcodePath).readText()
        val toolCounts = (0..3).map { t -> gcode.lines().count { it.trim() == "T$t" } }
        val activeTools = toolCounts.count { it > 0 }
        assertTrue(
            "H2C benchy must produce >= 2 active tools, got $activeTools (counts=$toolCounts)",
            activeTools >= 2
        )
    }

    // --- Bug #6: Buzz cold load perf gate ---
    //
    // The original Task 6 attempt (skip per-plate paint scan in parse() for
    // multi-plate files) regressed B82 painted-flippy. The follow-up refactor
    // keeps the per-plate scan but moves it behind a per-component cache, so
    // Buzz Lightyear's ~80 component .model files get scanned once each
    // instead of once per plate that references them — same answer, ~9× less
    // paint-spec I/O. Cold load measured ~47s on Pixel 8a after the refactor
    // (was ~120s pre-fix). Threshold sits at 60s — well above the observed
    // floor but tight enough to catch regressions back into the per-plate
    // re-scan shape. Going lower than ~45s would need parallelising the
    // component scans or merging with the streamDetectPaintSupports pass at
    // line ~172 (every component is read again there); deferred.
    @Test
    fun bug6_buzz_cold_load_under_threshold() {
        val file = copyAsset("Buzz_Multipart_3MF_Bambu.3mf")
        val startMs = System.currentTimeMillis()
        val info = ThreeMfParser.parse(file)
        val elapsedMs = System.currentTimeMillis() - startMs
        assertTrue(
            "Buzz parse took ${elapsedMs}ms — expected < 60000ms (post-cache-refactor floor ~47s)",
            elapsedMs < 60_000L
        )
        assertTrue("Buzz must be detected as multi-plate", info.isMultiPlate)
    }
}
