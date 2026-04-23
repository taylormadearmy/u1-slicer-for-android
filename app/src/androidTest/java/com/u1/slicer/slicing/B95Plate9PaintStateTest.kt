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
     * End-to-end B95 guard: slice plate 9 through the full pipeline.
     * Pre-fix this FAILS (only T0 emitted). Post-fix both T0 and T1 must
     * appear because the paint state's triangles should become their own
     * tool change in the G-code.
     *
     * @Ignore'd for now: the fix requires understanding the bit-packed
     * encoding of `paint_color` attributes so BambuSanitizer can rewrite
     * source filament indices to compact ones, OR a native patch inside
     * `multi_material_segmentation_by_painting()`. An embed-count bump
     * attempt (filament_colour sized to `max(usedExtruderIndices)`) did
     * not fix the slicer output and regressed plate 8's Preview palette.
     * Leaving the assertion in the tree as documentation of the desired
     * behaviour — unignore when the real fix lands.
     */
    @Test
    fun plate9_slicedGcodeHasTwoTools() {
        val input = asset("Buzz_Multipart_3MF_Bambu.3mf")

        // Extract plate 9 (same path SlicerViewModel takes)
        val plateFile = BambuSanitizer.extractPlate(input, 9, outDir)
        assertTrue("plate 9 extract must produce a file", plateFile.exists())

        // Restructure if the plate has multi-color components
        val restructured = try {
            BambuSanitizer.restructurePlateFile(plateFile, outDir)
        } catch (_: Throwable) {
            plateFile
        }

        val plateInfo = ThreeMfParser.parseForPlateSelection(restructured)
        assertTrue("restructured plate must retain paint data", plateInfo.hasPaintData)

        // Mirror the real SlicerViewModel pipeline: derive a default identity
        // colorMapping from detected colours and use computeEmbedTargetCount to
        // size filament_colour. The B95 fix expects max(usedExtruderIndices) to
        // bump the embed count when it exceeds distinctSlots.
        val detectedSize = plateInfo.detectedColors.size
        val colorMapping = (0 until detectedSize).toList()
        val targetCount = com.u1.slicer.computeEmbedTargetCount(
            colorMapping = colorMapping,
            hasPaintData = plateInfo.hasPaintData,
            toolRemapSlots = null,
            fallbackExtCount = detectedSize,
            hasMultiExtruderAssignments = plateInfo.hasMultiExtruderAssignments,
            maxSourceFilamentIndex = plateInfo.usedExtruderIndices.maxOrNull() ?: 0
        )
        android.util.Log.i("B95",
            "plate 9 embed setup: detectedSize=$detectedSize " +
                "usedExtruderIndices=${plateInfo.usedExtruderIndices} " +
                "targetCount=$targetCount"
        )

        // Load the source filament_colour so the embedder can size its per-filament
        // arrays correctly — matching the real SlicerViewModel pipeline.
        val sourceConfig = ZipFile(input).use { embedder.parseSourceConfig(it) }
        val config = embedder.buildConfig(
            info = plateInfo,
            sourceConfig = sourceConfig,
            targetExtruderCount = targetCount
        )
        val embedded = embedder.embed(restructured, config, outDir, plateInfo)
        assertTrue("loadModel must succeed", lib.loadModel(embedded.absolutePath))

        // SliceConfig uses physical extruder count (<=4), not the virtual embed size.
        val physicalExtruderCount = detectedSize.coerceIn(2, 4)
        val result = lib.slice(makeConfig(physicalExtruderCount))
        assertNotNull("slice() must not return null", result)
        result!!
        assertTrue("plate 9 must slice: ${result.errorMessage}", result.success)

        // Mirror SlicerViewModel's post-slice remap so high-index slicer tools
        // (T7 from paint state 8, T9 from object default 10) get collapsed back
        // to the user's physical slot assignments.
        val semmPerm = com.u1.slicer.computeSemmColorPermutation(
            colorMapping = colorMapping,
            hasPaintData = plateInfo.hasPaintData,
            isH2cStyle = false
        )
        // B95: when the embed was bumped to address high-index source filaments,
        // the slicer emits T<filament-1> for each used filament (T9 + T10 for plate 9).
        // computeExpandedGcodeRemap returns a list that maps every emitted T-index back
        // to a physical slot via the user's colorMapping; this list takes priority
        // over the legacy compact semmColorPermutation when present.
        val expanded = com.u1.slicer.computeExpandedGcodeRemap(
            usedExtruderIndices = plateInfo.usedExtruderIndices,
            colorMapping = colorMapping,
            embeddedFilamentCount = targetCount
        )
        val composed = expanded ?: com.u1.slicer.composeSemmRemap(null, semmPerm)
        android.util.Log.i("B95", "plate 9 composedRemap=$composed expanded=$expanded")
        if (composed != null) {
            com.u1.slicer.gcode.GcodeToolRemapper.remap(result.gcodePath, composed)
        }

        val gcode = File(result.gcodePath).readText()
        val lines = gcode.lines()
        // Diagnostic: count ALL T-indices (0..15) in the post-remap G-code so we can
        // see whether the slicer emitted high-index tools (T9/T10 for paint states 10/11)
        // that the renderer-facing 0..3 view doesn't capture.
        val allCounts = (0..15).associateWith { t -> lines.count { it.trim() == "T$t" } }
            .filter { it.value > 0 }
        val toolCounts = (0..3).map { t -> lines.count { it.trim() == "T$t" } }
        android.util.Log.i(
            "B95",
            "plate 9 slice: T0=${toolCounts[0]} T1=${toolCounts[1]} " +
                "T2=${toolCounts[2]} T3=${toolCounts[3]} " +
                "detectedColors=${plateInfo.detectedColors} " +
                "physicalExtruderCount=$physicalExtruderCount " +
                "allCounts=$allCounts"
        )

        val activeTools = toolCounts.count { it > 0 }
        assertTrue(
            "B95: plate 9 has 2 distinct detected colours (peach paint state + " +
                "white object default) and MUST produce at least 2 tool changes " +
                "in the G-code. Got T0=${toolCounts[0]} T1=${toolCounts[1]} " +
                "T2=${toolCounts[2]} T3=${toolCounts[3]} " +
                "(activeTools=$activeTools). If activeTools == 1, the paint state " +
                "was silently dropped by paint segmentation because the embedded " +
                "filament_colour cannot address the source filament's state index.",
            activeTools >= 2
        )
    }
}
