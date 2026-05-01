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
import java.util.zip.ZipFile

/**
 * B95 (#102) — Buzz Lightyear plate 9 has a single object using source
 * filament 10 plus a paint state referencing source filament 8. The plate
 * sanitizer currently leaves paint_color attributes as-is, so the embedded
 * 3MF still carries paint_color="8" against a 2-entry filament_colour array.
 * Native `multi_material_segmentation_by_painting()` silently drops the
 * out-of-range state, and the G-code emits only `T0`.
 *
 * This test pair:
 *   1. [plate9_paintColorValuesPresentInSanitized] — confirm the source 3MF
 *      carries paint_color with character '8' in plate 9's triangles (or
 *      RLE strings that include '8').
 *   2. [plate9_slicedGcodeHasTwoTools] — red/green end-to-end: slice the
 *      plate and assert both T0 (object default) and T1 (paint state) appear.
 *      Today (before fix) this FAILS because only T0 is emitted.
 */
@RunWith(AndroidJUnit4::class)
class B95Plate9PaintStateTest {

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
        wipeTowerX = 10f,
        wipeTowerY = 10f,
        wipeTowerWidth = 60f
    )

    @Before
    fun setup() {
        assertTrue("Native library required", NativeLibrary.isLoaded)
        lib = NativeLibrary()
        lib.clearModel()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        cacheDir = ctx.cacheDir
        outDir = File(cacheDir, "b95_test_out").also { it.mkdirs() }
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

    @Test
    fun plate9_sourceHasHighIndexPaintColor() {
        val input = asset("Buzz_Multipart_3MF_Bambu.3mf")
        val plateFile = BambuSanitizer.extractPlate(input, 9, outDir)
        val info = ThreeMfParser.parseForPlateSelection(plateFile)

        assertTrue("plate 9 must be Bambu", info.isBambu)
        assertTrue("plate 9 must have paint data", info.hasPaintData)

        val paintStates = info.usedExtruderIndices
        android.util.Log.i("B95", "plate 9 usedExtruderIndices=$paintStates detectedColors=${info.detectedColors}")
        assertTrue(
            "plate 9 must reference at least one filament index > 4 (paint state or " +
                "object extruder beyond the physical 4 slots). Got $paintStates — if all " +
                "indices are <= 4 this test doesn't reproduce B95's shape.",
            paintStates.any { it > 4 }
        )
    }

    /**
     * End-to-end B95 guard: slice plate 9 through the SlicerViewModel
     * pipeline and assert the canonical-space G-code emits at least 2
     * distinct tool changes (paint state + object default).
     *
     * Phase 2 contract (2026-04-27 architectural fix): the slicer emits
     * T-indices in canonical fileIndex space (no slice-time slot remap).
     * Print-time slot mapping is applied by `PrintTimeRemap` when sending
     * to the printer; the on-disk G-code carries the slicer's native T-
     * indices.
     *
     * The pre-Phase-2 version of this test hand-rolled
     * `extractPlate` + `restructurePlateFile` + `embedder.buildConfig`
     * + `lib.slice` + manual `composeSemmRemap` /
     * `computeExpandedGcodeRemap` / `GcodeToolRemapper.remap`. That
     * pipeline is now retired in production and the manual remap collapsed
     * canonical T-indices back into compact slot space, masking the actual
     * paint-state-emission invariant. The rewrite goes through the same
     * production code path the user's Send button drives.
     */
    @Test
    fun plate9_slicedGcodeHasTwoTools() = kotlinx.coroutines.runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val app = ctx.applicationContext as com.u1.slicer.U1SlicerApplication
        val viewModel = com.u1.slicer.SlicerViewModel(app)
        val modelFile = asset("Buzz_Multipart_3MF_Bambu.3mf")

        try {
            viewModel.loadModelFromFile(modelFile)

            // Buzz is ~73MB; parse + plate enumeration is slow on Pixel 8a.
            val plateSelectorDeadline = System.currentTimeMillis() + 240_000L
            while (System.currentTimeMillis() < plateSelectorDeadline) {
                if (viewModel.showPlateSelector.value ||
                    viewModel.state.value is com.u1.slicer.SlicerViewModel.SlicerState.Error) break
                Thread.sleep(100)
            }
            val loadState = viewModel.state.value
            if (loadState is com.u1.slicer.SlicerViewModel.SlicerState.Error) {
                throw AssertionError("Buzz 3MF load failed: ${loadState.message}")
            }
            assertTrue("plate selector must appear", viewModel.showPlateSelector.value)

            viewModel.selectPlate(9)
            val plateLoadedDeadline = System.currentTimeMillis() + 120_000L
            while (System.currentTimeMillis() < plateLoadedDeadline) {
                val s = viewModel.state.value
                if (s is com.u1.slicer.SlicerViewModel.SlicerState.ModelLoaded &&
                    viewModel.colorMapping.value != null) break
                if (s is com.u1.slicer.SlicerViewModel.SlicerState.Error) {
                    throw AssertionError("Plate 9 load failed: ${s.message}")
                }
                Thread.sleep(100)
            }
            assertTrue(
                "plate 9 must reach ModelLoaded with colorMapping",
                viewModel.state.value is com.u1.slicer.SlicerViewModel.SlicerState.ModelLoaded &&
                    viewModel.colorMapping.value != null
            )

            // Sanity: plate 9 has paint data and the canonical-driven plate
            // narrowing produced 2 detected colours (object default + paint state).
            val plate9Info = viewModel.threeMfInfo.value!!
            assertTrue("plate 9 must have paint data", plate9Info.hasPaintData)
            assertEquals(
                "plate 9 detectedColors must have 2 entries (object default + paint state); got ${plate9Info.detectedColors}",
                2, plate9Info.detectedColors.size
            )

            // Slice through the production path.
            viewModel.startSlicing()
            val sliceDeadline = System.currentTimeMillis() + 300_000L
            while (System.currentTimeMillis() < sliceDeadline) {
                val s = viewModel.state.value
                if (s is com.u1.slicer.SlicerViewModel.SlicerState.SliceComplete) break
                if (s is com.u1.slicer.SlicerViewModel.SlicerState.Error) {
                    throw AssertionError("Plate 9 slice failed: ${s.message}")
                }
                Thread.sleep(200)
            }
            val sliceState = viewModel.state.value
            assertTrue(
                "plate 9 must reach SliceComplete; got $sliceState",
                sliceState is com.u1.slicer.SlicerViewModel.SlicerState.SliceComplete
            )
            sliceState as com.u1.slicer.SlicerViewModel.SlicerState.SliceComplete

            val gcode = File(sliceState.result.gcodePath).readText()
            val lines = gcode.lines()
            // Phase 2: count ANY T-index (canonical fileIndex space, not just
            // slot space). Buzz plate 9 references file filaments 6 (paint state)
            // and 10 (object default extruder); the slicer emits at least two
            // distinct T-indices for them.
            val toolRegex = Regex("""^\s*T(\d+)\s*$""")
            val toolCounts = lines.mapNotNull {
                toolRegex.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull()
            }.groupingBy { it }.eachCount()
            android.util.Log.i(
                "B95", "plate 9 slice: detectedColors=${plate9Info.detectedColors} " +
                    "colorMapping=${viewModel.colorMapping.value} toolCounts=$toolCounts"
            )
            assertTrue(
                "B95: plate 9 has 2 distinct detected colours (paint state + object " +
                    "default) and MUST produce at least 2 tool changes in the G-code. " +
                    "Got toolCounts=$toolCounts. If only one T-index is emitted, the " +
                    "paint state was silently dropped — likely because the embedded " +
                    "filament_colour cannot address the source filament's state index.",
                toolCounts.size >= 2
            )
        } finally {
            viewModel.clearModel()
            modelFile.delete()
        }
    }
}
