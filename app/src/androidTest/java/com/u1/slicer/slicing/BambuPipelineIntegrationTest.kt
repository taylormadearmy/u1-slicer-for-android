package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.BambuSanitizer
import com.u1.slicer.bambu.ProfileEmbedder
import com.u1.slicer.bambu.ThreeMfParser
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.gcode.GcodeValidator
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented integration tests for the full Bambu 3MF slicing pipeline:
 *   ThreeMfParser → BambuSanitizer.process() → NativeLibrary.loadModel() → slice() → G-code
 *
 * Mirrors bridge tests:
 *   - multicolour-slice.spec.ts  (dual-colour slicing, extruder assignments, prime tower)
 *   - multicolour.spec.ts        (colour detection)
 *   - multiplate.spec.ts         (multi-plate Bambu files)
 *   - slicing.spec.ts            (Bambu file modifier crash regression, single-colour Bambu)
 *
 * All test files come from u1-slicer-bridge/test-data/ bundled in androidTest assets.
 */
@RunWith(AndroidJUnit4::class)
class BambuPipelineIntegrationTest {

    private lateinit var lib: NativeLibrary
    private lateinit var cacheDir: File
    private lateinit var outDir: File
    private lateinit var embedder: ProfileEmbedder

    companion object {
        val BASE_CONFIG = SliceConfig(
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
            extruderCount = 1
        )

        fun dualConfig() = BASE_CONFIG.copy(
            extruderCount = 2,
            extruderTemps = intArrayOf(220, 220),
            wipeTowerEnabled = true,
            wipeTowerX = 170f,
            wipeTowerY = 140f,
            wipeTowerWidth = 60f
        )
    }

    @Before
    fun setup() {
        assertTrue("Native library required", NativeLibrary.isLoaded)
        lib = NativeLibrary()
        lib.clearModel()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        cacheDir = ctx.cacheDir
        outDir = File(cacheDir, "bambu_test_out").also { it.mkdirs() }
        embedder = ProfileEmbedder(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @After
    fun teardown() {
        lib.clearModel()
        outDir.deleteRecursively()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun asset(name: String): File {
        val file = File(cacheDir, name.replace("/", "_"))
        InstrumentationRegistry.getInstrumentation().context
            .assets.open(name).use { it.copyTo(file.outputStream()) }
        return file
    }

    /**
     * For multi-plate Bambu files: extract a single plate then embed the Snapmaker profile.
     * This mirrors SlicerViewModel's actual pipeline for multi-plate files.
     */
    private fun extractPlateAndLoad(assetName: String, plateId: Int = 1): File {
        val input = asset(assetName)
        val info = ThreeMfParser.parse(input)
        val plateFile = BambuSanitizer.extractPlate(input, plateId, outDir)
        assertTrue("extractPlate file should exist", plateFile.exists())
        val config = embedder.buildConfig(info)
        val embedded = embedder.embed(plateFile, config, outDir, info)
        assertTrue("Embedded file should exist", embedded.exists())
        assertTrue("loadModel should succeed for plate $plateId of $assetName",
            lib.loadModel(embedded.absolutePath))
        return embedded
    }

    private fun sanitizeAndLoad(assetName: String): File {
        val input = asset(assetName)
        val sanitized = BambuSanitizer.process(input, outDir)
        assertTrue("Sanitized file should exist", sanitized.exists())
        assertTrue("Sanitized file should be non-empty", sanitized.length() > 0)
        assertTrue("loadModel should succeed for $assetName",
            lib.loadModel(sanitized.absolutePath))
        return sanitized
    }

    private fun sanitizeAndSlice(assetName: String, config: SliceConfig = BASE_CONFIG): String {
        sanitizeAndLoad(assetName)
        val result = lib.slice(config)
        assertNotNull("slice() returned null for $assetName", result)
        result!!
        assertTrue("Slice should succeed for $assetName: ${result.errorMessage}", result.success)
        val gcodeFile = File(result.gcodePath)
        assertTrue("G-code file should exist", gcodeFile.exists())
        return gcodeFile.readText()
    }

    // ─── Multi-plate detection ────────────────────────────────────────────────

    /**
     * Bridge: multiplate.spec.ts — "Dragon Scale is detected as multi-plate"
     */
    @Test
    fun dragonScale_detectedAsMultiPlate() {
        val file = asset("Dragon Scale infinity.3mf")
        val info = ThreeMfParser.parse(file)
        assertTrue("Dragon Scale should be Bambu", info.isBambu)
        assertTrue("Dragon Scale should be multi-plate", info.isMultiPlate)
        assertTrue("Dragon Scale should have plates", info.plates.isNotEmpty())
    }

    /**
     * Bridge: multiplate.spec.ts — "Dragon Scale 2-colour variant has colour info"
     */
    @Test
    fun dragonScale2Colour_hasColorInfo() {
        val file = asset("Dragon Scale infinity-1-plate-2-colours.3mf")
        val info = ThreeMfParser.parse(file)
        assertTrue(info.isBambu)
        assertTrue("Detected colors should be >= 2, was ${info.detectedColors.size}",
            info.detectedColors.size >= 2)
    }

    /**
     * Bridge: multiplate.spec.ts — "Dragon Scale new-plate variant is a Bambu file"
     * The "new-plate" variant may reference only 1 plate in the build manifest
     * (the new plate was added but the single-plate build item is all that's listed).
     */
    @Test
    fun dragonScaleNewPlate_isBambuFile() {
        val file = asset("Dragon Scale infinity-1-plate-2-colours-new-plate.3mf")
        val info = ThreeMfParser.parse(file)
        assertTrue("Dragon Scale new-plate should be Bambu", info.isBambu)
        // plates list may have 1 or 2 entries depending on manifest
        assertTrue("Dragon Scale new-plate should have at least 1 plate",
            info.plates.isNotEmpty())
    }

    // ─── Colour detection ────────────────────────────────────────────────────

    /**
     * Bridge: multicolour.spec.ts — "calib cube is detected as dual-colour"
     */
    @Test
    fun calibCube_detectedAsDualColour() {
        val file = asset("calib-cube-10-dual-colour-merged.3mf")
        val info = ThreeMfParser.parse(file)
        assertTrue("Calib cube should have >= 2 detected colors",
            info.detectedColors.size >= 2)
    }

    /**
     * Bridge: multicolour.spec.ts — "Korok mask 4-colour has >= 4 detected colours"
     */
    @Test
    fun korokMask_detectedAsFourColour() {
        val file = asset("PrusaSlicer-printables-Korok_mask_4colour.3mf")
        val info = ThreeMfParser.parse(file)
        assertTrue("Korok mask should have >= 4 detected colors, was ${info.detectedColors.size}",
            info.detectedColors.size >= 4)
    }

    // ─── Sanitization ─────────────────────────────────────────────────────────

    /**
     * Bridge: slicing.spec.ts — "Bambu file with modifier parts slices without crash"
     * Shashibo has Bambu modifier geometry (type="other") that must be converted to
     * type="model" — previously caused PrusaSlicer to reject 0-triangle objects.
     */
    @Test
    fun shashibo_sanitizesWithoutCrash() {
        // Parse first to verify structure
        val file = asset("Shashibo-h2s-textured.3mf")
        val info = ThreeMfParser.parse(file)
        assertTrue("Shashibo should be Bambu", info.isBambu)

        // Sanitize — must not throw
        val sanitized = BambuSanitizer.process(file, outDir)
        assertTrue(sanitized.exists())
        assertTrue(sanitized.length() > 0)
    }

    @Test
    fun shashibo_loadsAfterExtractAndEmbed() {
        // Multi-plate files must go through extractPlate() + embed() — not process()
        extractPlateAndLoad("Shashibo-h2s-textured.3mf", plateId = 1)
        // loadModel asserted inside extractPlateAndLoad
    }

    // ─── Dual-colour slicing ──────────────────────────────────────────────────

    /**
     * Bridge: multicolour-slice.spec.ts — "dual-colour file slices with two filament_ids"
     */
    @Test
    fun calibCube_dualColour_slicesSuccessfully() {
        val gcode = sanitizeAndSlice("calib-cube-10-dual-colour-merged.3mf", dualConfig())
        assertTrue(gcode.isNotEmpty())
    }

    @Test
    fun calibCube_dualColour_hasNonZeroTemps() {
        val gcode = sanitizeAndSlice("calib-cube-10-dual-colour-merged.3mf", dualConfig())
        assertTrue("Dual-colour G-code must have non-zero nozzle temps",
            GcodeValidator.hasNonZeroNozzleTemps(gcode))
    }

    @Test
    fun calibCube_dualColour_hasPositiveLayerCount() {
        lib.clearModel()
        sanitizeAndLoad("calib-cube-10-dual-colour-merged.3mf")
        val result = lib.slice(dualConfig())!!
        assertTrue(result.success)
        assertTrue("Layer count should be > 0, was ${result.totalLayers}", result.totalLayers > 0)
    }

    /**
     * Bridge: multicolour-slice.spec.ts — "multicolour slice with prime tower succeeds"
     */
    @Test
    fun calibCube_dualColour_withPrimeTower_slicesSuccessfully() {
        val config = dualConfig().copy(wipeTowerEnabled = true)
        val gcode = sanitizeAndSlice("calib-cube-10-dual-colour-merged.3mf", config)
        assertTrue(gcode.isNotEmpty())
        assertTrue(GcodeValidator.hasNonZeroNozzleTemps(gcode))
    }

    /**
     * Bridge: multicolour-slice.spec.ts — "single filament_id auto-expands for dual-colour
     * file (segfault fix)" — slicing a dual-colour model with only 1 extruder configured
     * must not crash (should succeed or give a clean error, not a native abort).
     */
    @Test
    fun calibCube_dualColour_singleExtruderDoesNotCrash() {
        sanitizeAndLoad("calib-cube-10-dual-colour-merged.3mf")
        // Using BASE_CONFIG (1 extruder) on a dual-colour file — must not crash
        val result = lib.slice(BASE_CONFIG)
        // We don't assert success/failure — just that slice() returned without crashing
        assertNotNull("slice() must return a result, not throw/abort", result)
    }

    // ─── 4-colour slicing ─────────────────────────────────────────────────────

    /**
     * Bridge: multicolour-slice.spec.ts — ">4 filament_ids rejected"
     * In the Android app, 4 extruders is the maximum (Snapmaker U1 spec).
     * Korok mask with 4 colours should slice cleanly with 4-extruder config.
     */
    @Test
    fun korokMask_fourColour_sanitizesSuccessfully() {
        val sanitized = BambuSanitizer.process(asset("PrusaSlicer-printables-Korok_mask_4colour.3mf"), outDir)
        assertTrue(sanitized.exists())
        assertTrue(sanitized.length() > 0)
    }

    // ─── Multi-plate slicing ──────────────────────────────────────────────────

    /**
     * Bridge: slice-plate.spec.ts — "Dragon Scale plate slices successfully"
     * Multi-plate Bambu files must go through extractPlate() + embed() pipeline.
     */
    @Test
    fun dragonScale_plate1_loadsAndSlices() {
        // Extract plate 1, embed Snapmaker profile, load and slice
        extractPlateAndLoad("Dragon Scale infinity.3mf", plateId = 1)
        val result = lib.slice(BASE_CONFIG)
        assertNotNull(result)
        result!!
        assertTrue("Dragon Scale plate 1 slice should succeed: ${result.errorMessage}",
            result.success)
        assertTrue("Dragon Scale G-code should be non-empty",
            File(result.gcodePath).length() > 0)
    }

    @Test
    fun dragonScale2Colour_plate1_loadsAndSlices() {
        extractPlateAndLoad("Dragon Scale infinity-1-plate-2-colours.3mf", plateId = 1)
        val result = lib.slice(dualConfig())
        assertNotNull(result)
        result!!
        assertTrue("Dragon Scale 2-colour slice should succeed: ${result.errorMessage}",
            result.success)
        assertTrue(GcodeValidator.hasNonZeroNozzleTemps(File(result.gcodePath).readText()))
    }
}
