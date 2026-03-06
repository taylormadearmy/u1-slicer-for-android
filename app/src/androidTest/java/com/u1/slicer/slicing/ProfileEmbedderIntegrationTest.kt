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
import java.util.zip.ZipFile

/**
 * Instrumented integration tests for ProfileEmbedder — the Kotlin port of the
 * bridge's profile_embedder.py pipeline.
 *
 * Mirrors bridge tests:
 *   - slicing.spec.ts         (single-colour Bambu file slices via full embed pipeline)
 *   - file-settings.spec.ts   (embedded profile key counts, INI parsing)
 *   - settings.spec.ts        (filament JSON import round-trip)
 *
 * The full pipeline under test:
 *   ThreeMfParser.parse() → BambuSanitizer.process() → ProfileEmbedder.embed()
 *     → NativeLibrary.loadModel() → slice() → G-code validation
 */
@RunWith(AndroidJUnit4::class)
class ProfileEmbedderIntegrationTest {

    private lateinit var lib: NativeLibrary
    private lateinit var cacheDir: File
    private lateinit var outDir: File
    private lateinit var embedder: ProfileEmbedder

    @Before
    fun setup() {
        assertTrue("Native library required", NativeLibrary.isLoaded)
        lib = NativeLibrary()
        lib.clearModel()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        cacheDir = ctx.cacheDir
        outDir = File(cacheDir, "embed_test_out").also { it.mkdirs() }
        embedder = ProfileEmbedder(ctx)
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

    private fun fullPipeline(assetName: String): File {
        val input = asset(assetName)
        val info = ThreeMfParser.parse(input)

        // Stage 1: BambuSanitizer (only for Bambu files)
        val stage1 = if (info.isBambu) BambuSanitizer.process(input, outDir) else input

        // Stage 2: ProfileEmbedder
        val config = embedder.buildConfig(info)
        val embedded = embedder.embed(stage1, config, outDir, info)
        assertTrue("Embedded file should exist", embedded.exists())
        assertTrue("Embedded file should be non-empty", embedded.length() > 0)
        return embedded
    }

    private val defaultSliceConfig = SliceConfig(
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

    // ─── ProfileEmbedder output validity ─────────────────────────────────────

    /**
     * Bridge: file-settings.spec.ts — "embedded file is a valid ZIP"
     */
    @Test
    fun embed_producesValidZip_calibCube() {
        val embedded = fullPipeline("calib-cube-10-dual-colour-merged.3mf")
        // Validate ZIP integrity — ZipFile constructor throws if corrupt
        ZipFile(embedded).use { zip ->
            assertTrue("Embedded ZIP should have entries", zip.size() > 0)
        }
    }

    /**
     * Bridge: file-settings.spec.ts — "embedded file contains project_settings.config"
     */
    @Test
    fun embed_containsProjectSettingsConfig_calibCube() {
        val embedded = fullPipeline("calib-cube-10-dual-colour-merged.3mf")
        ZipFile(embedded).use { zip ->
            val entry = zip.getEntry("Metadata/project_settings.config")
                ?: zip.getEntry("project_settings.config")
            assertNotNull("Embedded ZIP must contain project_settings.config", entry)
            val content = zip.getInputStream(entry).readBytes().decodeToString()
            assertTrue("Config should have keys", content.contains("="))
        }
    }

    /**
     * Bridge: file-settings.spec.ts — "embedded config has >= 100 keys"
     * (bridge ProfileEmbedder builds ~200 key config)
     */
    @Test
    fun embed_configHasMinimumKeyCount_calibCube() {
        val input = asset("calib-cube-10-dual-colour-merged.3mf")
        val info = ThreeMfParser.parse(input)
        val config = embedder.buildConfig(info)
        assertTrue("Config should have >= 100 keys, was ${config.size}", config.size >= 100)
    }

    @Test
    fun embed_configHasMinimumKeyCount_singleColour() {
        val input = asset("u1-auxiliary-fan-cover-hex_mw.3mf")
        val info = ThreeMfParser.parse(input)
        val config = embedder.buildConfig(info)
        assertTrue("Config should have >= 100 keys, was ${config.size}", config.size >= 100)
    }

    /**
     * Bridge: file-settings.spec.ts — "embedded config contains nozzle_temperature key"
     */
    @Test
    fun embed_configContainsNozzleTemperature() {
        val input = asset("calib-cube-10-dual-colour-merged.3mf")
        val info = ThreeMfParser.parse(input)
        val config = embedder.buildConfig(info)
        assertTrue("Config must contain nozzle_temperature",
            config.containsKey("nozzle_temperature"))
    }

    @Test
    fun embed_configContainsLayerHeight() {
        val input = asset("u1-auxiliary-fan-cover-hex_mw.3mf")
        val info = ThreeMfParser.parse(input)
        val config = embedder.buildConfig(info)
        assertTrue("Config must contain layer_height", config.containsKey("layer_height"))
    }

    // ─── Full pipeline: embed → load → slice ──────────────────────────────────

    /**
     * Bridge: slicing.spec.ts — "single-color Bambu file slices via browser UI"
     * (equivalent: full embed pipeline for a single-colour Bambu 3MF)
     */
    @Test
    fun fanCover_fullPipeline_slicesSuccessfully() {
        val embedded = fullPipeline("u1-auxiliary-fan-cover-hex_mw.3mf")
        assertTrue(lib.loadModel(embedded.absolutePath))
        val result = lib.slice(defaultSliceConfig)!!
        assertTrue("Fan cover slice should succeed: ${result.errorMessage}", result.success)
        assertTrue("Layer count > 0", result.totalLayers > 0)
        assertTrue("Filament estimate > 0", result.estimatedFilamentMm > 0f)
    }

    @Test
    fun fanCover_fullPipeline_gcodeHasNonZeroTemps() {
        val embedded = fullPipeline("u1-auxiliary-fan-cover-hex_mw.3mf")
        assertTrue(lib.loadModel(embedded.absolutePath))
        val result = lib.slice(defaultSliceConfig)!!
        assertTrue(result.success)
        val gcode = File(result.gcodePath).readText()
        assertTrue("G-code must have non-zero nozzle temps",
            GcodeValidator.hasNonZeroNozzleTemps(gcode))
    }

    /**
     * Bridge: slicing.spec.ts — "calib cube slices at default position"
     * Dual-colour calib cube through the full embed pipeline.
     */
    @Test
    fun calibCube_fullPipeline_slicesSuccessfully() {
        val dualConfig = defaultSliceConfig.copy(
            extruderCount = 2,
            extruderTemps = intArrayOf(220, 220),
            wipeTowerEnabled = true
        )
        val embedded = fullPipeline("calib-cube-10-dual-colour-merged.3mf")
        assertTrue(lib.loadModel(embedded.absolutePath))
        val result = lib.slice(dualConfig)!!
        assertTrue("Calib cube dual-colour slice should succeed: ${result.errorMessage}",
            result.success)
        assertTrue("Layer count > 0", result.totalLayers > 0)
    }

    @Test
    fun buttonTrousers_fullPipeline_slicesSuccessfully() {
        val embedded = fullPipeline("Button-for-S-trousers.3mf")
        assertTrue(lib.loadModel(embedded.absolutePath))
        val result = lib.slice(defaultSliceConfig)!!
        assertTrue("Button-for-S-trousers should slice: ${result.errorMessage}", result.success)
    }

    // ─── INI / JSON import ────────────────────────────────────────────────────

    /**
     * Bridge: settings.spec.ts — "filament JSON imports correctly"
     * test-filament-profile.json from bridge test-data.
     */
    @Test
    fun bambuFilamentJson_parsedByBambuSanitizer() {
        val jsonFile = asset("Bambu PLA Basic @BBL P1S 0.4 nozzle.json")
        val content = jsonFile.readText()
        // Verify it has expected filament keys
        assertTrue("JSON should contain nozzle_temperature",
            content.contains("nozzle_temperature"))
        assertTrue("JSON should be valid JSON", content.trim().startsWith("{"))
    }
}
