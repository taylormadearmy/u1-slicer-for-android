package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.gcode.GcodeValidator
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented E2E slicing integration tests.
 *
 * Mirrors bridge tests/slicing.spec.ts and tests/stl-upload.spec.ts:
 * load a real model file → slice it → validate the G-code output.
 *
 * All tests run on-device against the real OrcaSlicer native library.
 * Timeout: 3 minutes per test (slicing can be slow on device).
 */
@RunWith(AndroidJUnit4::class)
class SlicingIntegrationTest {

    private lateinit var lib: NativeLibrary
    private lateinit var cacheDir: File

    companion object {
        // Mirror bridge default slice params (layer_height=0.2, infill=15%, no supports)
        val DEFAULT_CONFIG = SliceConfig(
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
    }

    @Before
    fun setup() {
        assertTrue("Native library required", NativeLibrary.isLoaded)
        lib = NativeLibrary()
        lib.clearModel()
        cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        cacheDir.mkdirs()
    }

    @After
    fun teardown() {
        lib.clearModel()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun asset(name: String): File {
        val file = File(cacheDir, name)
        InstrumentationRegistry.getInstrumentation().context
            .assets.open(name).use { it.copyTo(file.outputStream()) }
        return file
    }

    private fun sliceAsset(assetName: String, config: SliceConfig = DEFAULT_CONFIG): Pair<Boolean, String?> {
        val file = asset(assetName)
        val loaded = lib.loadModel(file.absolutePath)
        if (!loaded) return Pair(false, null)
        val result = lib.slice(config) ?: return Pair(false, null)
        if (!result.success) return Pair(false, result.errorMessage)
        val gcode = if (result.gcodePath.isNotEmpty()) File(result.gcodePath).readText() else ""
        return Pair(true, gcode)
    }

    // ─── STL Tests ────────────────────────────────────────────────────────────

    /**
     * Bridge: stl-upload.spec.ts — "STL file slices successfully"
     * tetrahedron.stl is the smallest bundled STL (~4 triangles).
     */
    @Test
    fun tetrahedron_stl_slicesSuccessfully() {
        val (success, gcode) = sliceAsset("tetrahedron.stl")
        assertTrue("Slice should succeed", success)
        assertNotNull(gcode)
        assertTrue("G-code should be non-empty", gcode!!.isNotEmpty())
    }

    @Test
    fun tetrahedron_stl_gcodeHasNonZeroTemps() {
        val (success, gcode) = sliceAsset("tetrahedron.stl")
        assertTrue(success)
        assertTrue("G-code must contain non-zero nozzle temps",
            GcodeValidator.hasNonZeroNozzleTemps(gcode!!))
    }

    @Test
    fun tetrahedron_stl_singleExtruderHasNoToolChanges() {
        val (success, gcode) = sliceAsset("tetrahedron.stl")
        assertTrue(success)
        // Single-extruder print must not have T1/T2/T3 tool changes
        assertTrue("No T1+ tool changes for single-extruder print",
            GcodeValidator.lacksToolChanges(gcode!!, "T1", "T2", "T3"))
    }

    /**
     * Bridge: slicing.spec.ts — "3DBenchy STL slices to reasonable layer count"
     * 3DBenchy.stl is the standard STL stress test (10.8MB, ~1M triangles).
     */
    @Test
    fun benchy_stl_slicesSuccessfully() {
        val (success, gcode) = sliceAsset("3DBenchy.stl")
        assertTrue("3DBenchy slice should succeed", success)
        assertNotNull(gcode)
        assertTrue(gcode!!.isNotEmpty())
    }

    @Test
    fun benchy_stl_hasReasonableLayerCount() {
        val file = asset("3DBenchy.stl")
        assertTrue(lib.loadModel(file.absolutePath))
        val result = lib.slice(DEFAULT_CONFIG)!!
        assertTrue(result.success)
        // At 0.2mm layers, 48mm tall Benchy → ~240 layers
        assertTrue("Layer count should be >100, was ${result.totalLayers}", result.totalLayers > 100)
        assertTrue("Layer count should be <600, was ${result.totalLayers}", result.totalLayers < 600)
    }

    @Test
    fun benchy_stl_estimatedTimeIsNonZero() {
        val file = asset("3DBenchy.stl")
        assertTrue(lib.loadModel(file.absolutePath))
        val result = lib.slice(DEFAULT_CONFIG)!!
        assertTrue(result.success)
        assertTrue("Estimated time should be > 0", result.estimatedTimeSeconds > 0f)
    }

    // ─── Single-colour 3MF Tests ──────────────────────────────────────────────

    /**
     * Bridge: upload.spec.ts — "MakerWorld real file slices without crash"
     * u1-auxiliary-fan-cover-hex_mw.3mf is a real MakerWorld-downloaded 3MF.
     */
    @Test
    fun fanCover_3mf_slicesSuccessfully() {
        val (success, gcode) = sliceAsset("u1-auxiliary-fan-cover-hex_mw.3mf")
        assertTrue("Fan cover slice should succeed", success)
        assertNotNull(gcode)
        assertTrue(gcode!!.isNotEmpty())
    }

    @Test
    fun fanCover_3mf_gcodeHasNonZeroTemps() {
        val (success, gcode) = sliceAsset("u1-auxiliary-fan-cover-hex_mw.3mf")
        assertTrue(success)
        assertTrue(GcodeValidator.hasNonZeroNozzleTemps(gcode!!))
    }

    /**
     * Bridge: slicing.spec.ts — "Button-for-S-trousers plate 1 slice succeeds (regression)"
     * This file was a regression case for plate transform handling.
     */
    @Test
    fun buttonTrousers_3mf_slicesSuccessfully() {
        val (success, gcode) = sliceAsset("Button-for-S-trousers.3mf")
        assertTrue("Button-for-S-trousers slice should succeed", success)
        assertNotNull(gcode)
        assertTrue(gcode!!.isNotEmpty())
    }

    // ─── SliceResult metadata ─────────────────────────────────────────────────

    @Test
    fun sliceResult_filamentEstimateIsPositive() {
        val file = asset("tetrahedron.stl")
        assertTrue(lib.loadModel(file.absolutePath))
        val result = lib.slice(DEFAULT_CONFIG)!!
        assertTrue(result.success)
        assertTrue("Filament mm should be > 0, was ${result.estimatedFilamentMm}",
            result.estimatedFilamentMm > 0f)
    }

    @Test
    fun sliceResult_gcodePathIsWritable() {
        val file = asset("tetrahedron.stl")
        assertTrue(lib.loadModel(file.absolutePath))
        val result = lib.slice(DEFAULT_CONFIG)!!
        assertTrue(result.success)
        assertTrue("G-code file should exist", File(result.gcodePath).exists())
        assertTrue("G-code file should be > 0 bytes", File(result.gcodePath).length() > 0)
    }
}
