package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.BambuSanitizer
import com.u1.slicer.bambu.ProfileEmbedder
import com.u1.slicer.bambu.ThreeMfParser
import com.u1.slicer.data.SliceConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Regression tests for Bambu SEMM (Single Extruder Multi Material) slicing.
 *
 * SEMM models store multi-color segmentation as paint_color= attributes on
 * individual triangles.  OrcaSlicer's multi_material_segmentation_by_painting()
 * processes these to produce per-extruder toolpaths.
 *
 * Current status: SEMM is DISABLED.
 * multi_material_segmentation_by_painting() crashes on Android with SIGSEGV in
 * MultiPoint::bounding_box() (corrupt ExPolygons from TBB parallel slice_volumes).
 * ProfileEmbedder.cleanModelXmlForOrcaSlicer() strips paint_color= attributes to
 * prevent the algorithm from running.
 *
 * TODO: Re-enable SEMM when the algorithm is stable on Android (native rebuild needed).
 * When re-enabling: remove paint_color= stripping, rebuild .so, flip assertion below
 * from assertEquals(0, t1Count) to assertTrue(t1Count > 0).
 */
@RunWith(AndroidJUnit4::class)
class SemmSlicingTest {

    private lateinit var lib: NativeLibrary
    private lateinit var cacheDir: File
    private lateinit var outDir: File
    private lateinit var embedder: ProfileEmbedder

    private val dualConfig = SliceConfig(
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
        extruderCount = 2,
        extruderTemps = intArrayOf(220, 220),
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
     * Regression guard: colored 3DBenchy uses Bambu SEMM (paint_color on triangles).
     *
     * SEMM is currently DISABLED — paint_color= is stripped in ProfileEmbedder to prevent
     * multi_material_segmentation_by_painting() SIGSEGV on Android.
     *
     * This test verifies:
     *   1. The pipeline completes without crashing (no process death).
     *   2. G-code is produced (slice succeeds).
     *   3. T1 count == 0 (SEMM disabled — single-extruder output expected).
     *
     * When SEMM is re-enabled, flip the final assertion to assertTrue(t1Count > 0).
     */
    @Test
    fun coloredBenchy_semm_gcodeHasToolChanges() {
        val input = asset("colored_3DBenchy (1).3mf")
        val origInfo = ThreeMfParser.parse(input)

        assertTrue("colored_3DBenchy must have hasPaintData=true", origInfo.hasPaintData)
        assertTrue("colored_3DBenchy must have >= 2 detected colors",
            origInfo.detectedColors.size >= 2)

        // Full pipeline: process → embed with 2-extruder SEMM config → load → slice
        val processed = BambuSanitizer.process(input, outDir)
        val config = embedder.buildConfig(
            info = origInfo,
            targetExtruderCount = 2
        )
        // Verify SEMM is DISABLED in the embedded config (algorithm crashes on Android).
        // When SEMM is re-enabled, change this assertion to assertEquals("1", ...).
        assertEquals("single_extruder_multi_material must be '0' while SEMM is disabled",
            "0", config["single_extruder_multi_material"])

        val embedded = embedder.embed(processed, config, outDir, origInfo)
        assertTrue("loadModel must succeed", lib.loadModel(embedded.absolutePath))

        val result = lib.slice(dualConfig)
        assertNotNull("slice() must not return null", result)
        result!!
        assertTrue("Colored Benchy must slice successfully: ${result.errorMessage}", result.success)

        val gcode = File(result.gcodePath).readText()
        val t1Count = gcode.lines().count { it.trimStart().startsWith("T1") }
        // SEMM disabled: paint_color= is stripped → single-extruder output, T1 count must be 0.
        // When SEMM is re-enabled (native rebuild + remove paint_color stripping), flip to > 0.
        assertEquals(
            "SEMM disabled: expected T1 count = 0 (single-extruder). " +
                "If non-zero, SEMM is running and may crash — check ProfileEmbedder.cleanModelXmlForOrcaSlicer().",
            0, t1Count
        )
    }
}
