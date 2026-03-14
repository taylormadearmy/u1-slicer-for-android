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

        val result = lib.slice(dualConfig)
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
}
