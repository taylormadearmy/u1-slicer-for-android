package com.u1.slicer.slicing

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

    // ─── Toolchange retraction regression (multi-filament clog prevention) ─────

    /**
     * Regression: OrcaSlicer defaults retract_length_toolchange to 10mm (bowden).
     * On Snapmaker U1's direct-drive extruders, 10mm pulls filament past the heat
     * break, causing heat-creep clogs during multi-colour standby periods.
     * Tests the full Bambu pipeline: sanitize → slice → verify retraction.
     */
    @Test
    fun calibCube_dualColour_toolchangeRetraction_notBowdenDefault() {
        val gcode = sanitizeAndSlice("calib-cube-10-dual-colour-merged.3mf", dualConfig())
        val vals = GcodeValidator.extractToolchangeRetractLength(gcode)
        assertNotNull("retract_length_toolchange must be in G-code config", vals)
        for (v in vals!!) {
            assertTrue("retract_length_toolchange=$v must be ≤ 2mm (direct drive), not bowden 10mm", v <= 2.0)
        }
    }

    @Test
    fun calibCube_dualColour_maxRetraction_notExcessive() {
        val gcode = sanitizeAndSlice("calib-cube-10-dual-colour-merged.3mf", dualConfig())
        val maxRetract = GcodeValidator.maxRetractionMm(gcode)
        assertTrue("Max retraction ${maxRetract}mm must be ≤ 5mm for direct drive", maxRetract <= 5.0)
    }

    // ─── Extruder remap regression (bridge: multicolour-slice.spec.ts line 98) ──

    /**
     * Full pipeline: E3+E4 assignment must produce G-code with T2/T3 after
     * compact slice (2 extruders) + GcodeToolRemapper post-process.
     *
     * OrcaSlicer always compacts tool indices (T0/T1) internally.  The app's
     * pipeline post-processes via GcodeToolRemapper to remap T0→T2, T1→T3
     * and SM_ EXTRUDER=0→2, EXTRUDER=1→3.
     *
     * Matches SlicerViewModel pipeline: embed with Snapmaker profile (compact,
     * no extruder remap in 3MF), slice, then post-process G-code.
     */
    @Test
    fun calibCube_dualColour_withExtruderRemap_usesT2andT3() {
        val input = asset("calib-cube-10-dual-colour-merged.3mf")
        val sanitized = BambuSanitizer.process(input, outDir)
        val info = ThreeMfParser.parse(input)

        // Embed Snapmaker profile with compact extruder count (no remap in 3MF)
        val config = embedder.buildConfig(info = info, targetExtruderCount = 2)
        val embedded = embedder.embed(sanitized, config, outDir, info)
        assertTrue("Embedded 3MF must exist", embedded.exists())

        // Slice in compact mode (2 extruders)
        assertTrue("loadModel must succeed", lib.loadModel(embedded.absolutePath))
        val result = lib.slice(dualConfig())
        assertNotNull(result); result!!
        assertTrue("Slice must succeed: ${result.errorMessage}", result.success)

        // Verify compact G-code has T0/T1 before remap
        val rawGcode = File(result.gcodePath).readText()
        assertTrue("Compact G-code must have T0/T1",
            GcodeValidator.hasToolChanges(rawGcode, "T0", "T1"))

        // Post-process: remap compact T0/T1 → physical T2/T3
        GcodeToolRemapper.remap(result.gcodePath, listOf(2, 3))
        val remapped = File(result.gcodePath).readText()

        // T2 and T3 must appear as standalone tool changes
        assertTrue("Remapped G-code must contain T2/T3",
            GcodeValidator.hasToolChanges(remapped, "T2", "T3"))
        // T0 and T1 must NOT appear
        assertTrue("Remapped G-code must not contain T0/T1",
            GcodeValidator.lacksToolChanges(remapped, "T0", "T1"))

        // SM_ commands must reference physical slots after remap.
        // Only check executable lines (not G-code config comments starting with ';')
        // because OrcaSlicer dumps the raw machine_start_gcode template in comments.
        val executableLines = remapped.lines().filter { !it.trimStart().startsWith(";") }
        val execBlock = executableLines.joinToString("\n")
        assertTrue("SM_PRINT_AUTO_FEED must use EXTRUDER=2",
            execBlock.contains(Regex("""SM_PRINT_AUTO_FEED\s+EXTRUDER=2""")))
        assertTrue("SM_PRINT_AUTO_FEED must use EXTRUDER=3",
            execBlock.contains(Regex("""SM_PRINT_AUTO_FEED\s+EXTRUDER=3""")))
        assertFalse("SM_PRINT_AUTO_FEED must not use EXTRUDER=0",
            execBlock.contains(Regex("""SM_PRINT_AUTO_FEED\s+EXTRUDER=0""")))
        assertFalse("SM_PRINT_AUTO_FEED must not use EXTRUDER=1",
            execBlock.contains(Regex("""SM_PRINT_AUTO_FEED\s+EXTRUDER=1""")))
    }

    /**
     * Regression: compact dual-colour slice with embedded Snapmaker profile
     * must produce G-code with non-zero nozzle temperatures.
     */
    @Test
    fun calibCube_dualColour_withExtruderRemap_hasNonZeroTempsForActiveSlots() {
        val input = asset("calib-cube-10-dual-colour-merged.3mf")
        val sanitized = BambuSanitizer.process(input, outDir)
        val info = ThreeMfParser.parse(input)
        val config = embedder.buildConfig(info = info, targetExtruderCount = 2)
        val embedded = embedder.embed(sanitized, config, outDir, info)
        assertTrue(lib.loadModel(embedded.absolutePath))
        val result = lib.slice(dualConfig())
        assertNotNull(result); result!!
        assertTrue(result.success)
        val gcode = File(result.gcodePath).readText()
        assertTrue("Active extruder slots must have non-zero temperatures",
            GcodeValidator.hasNonZeroNozzleTemps(gcode))
    }
}
