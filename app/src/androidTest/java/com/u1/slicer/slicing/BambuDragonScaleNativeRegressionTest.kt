package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.SlicerViewModel
import com.u1.slicer.bambu.BambuImportedConfigComposer
import com.u1.slicer.bambu.BambuSanitizer
import com.u1.slicer.bambu.ProfileEmbedder
import com.u1.slicer.bambu.ThreeMfParser
import com.u1.slicer.bambu.resolveTargetedSliceConfig
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.slice.SlicerTarget
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

/**
 * Native crash regression for the real MakerWorld project that exposed parallel perimeter
 * corruption on ARM64. Keep this in its own Orchestrator process: unlike the compact machine
 * identity fixtures, it intentionally exercises the complete 240k-triangle project.
 */
@RunWith(AndroidJUnit4::class)
class BambuDragonScaleNativeRegressionTest {
    private lateinit var native: NativeLibrary
    private lateinit var workDir: File

    @Before
    fun setUp() {
        assertTrue("Native library required", NativeLibrary.isLoaded)
        native = NativeLibrary()
        native.clearModel()
        workDir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "dragon-scale-native-${System.nanoTime()}",
        ).also { assertTrue(it.mkdirs()) }
    }

    @After
    fun tearDown() {
        native.clearModel()
        workDir.deleteRecursively()
    }

    @Test
    fun fullMakerWorldProjectRetargetedToH2dSlicesWithoutPerimeterCrash() {
        val context = InstrumentationRegistry.getInstrumentation()
        val input = File(workDir, "Dragon Scale infinity.3mf")
        context.context.assets.open(input.name).use { source ->
            input.outputStream().use(source::copyTo)
        }

        val originalInfo = ThreeMfParser.parse(input)
        assertTrue("fixture must be detected as Bambu", originalInfo.isBambu)
        assertTrue("fixture must retain its multiplate complexity", originalInfo.isMultiPlate)

        val embedder = ProfileEmbedder(context.targetContext)
        val sourceConfig = ZipFile(input).use(embedder::parseSourceConfig)
        assertNotNull("real MakerWorld source profile must be readable", sourceConfig)

        // Mirror the ViewModel's multi-plate path: sanitize once, retain the pre-sanitize
        // metadata, select plate 1 (the two-colour plate used by the crash report), inline
        // its component meshes, then apply the selected Bambu target profile.
        val sanitized = BambuSanitizer.process(input, workDir, isBambu = true)
        val preSelectInfo = SlicerViewModel.mergeThreeMfInfo(
            ThreeMfParser.parse(sanitized),
            originalInfo,
        )
        val plateId = 1
        val plateObjectIds = preSelectInfo.plates
            .first { it.plateId == plateId }
            .objectIds
            .toSet()
        val rawPlate = BambuSanitizer.extractPlate(
            inputFile = sanitized,
            targetPlateId = plateId,
            outputDir = workDir,
            hasPlateJsons = preSelectInfo.hasPlateJsons,
            plateObjectIds = plateObjectIds,
            objectExtruderMap = preSelectInfo.objectExtruderMap.filterKeys(plateObjectIds::contains),
        )
        val plate = BambuSanitizer.restructurePlateFile(rawPlate, workDir)
        val plateInfo = ThreeMfParser.parseForPlateSelection(plate)
        val mergedInfo = SlicerViewModel.mergeThreeMfInfoForPlate(
            plateInfo = plateInfo,
            sourceInfo = preSelectInfo,
            selectedPlateId = plateId,
        )
        val composed = BambuImportedConfigComposer.compose(
            target = SlicerTarget.BambuH2D,
            sourceConfig = sourceConfig,
        )
        val embedded = embedder.embed(plate, composed.config, workDir, mergedInfo)

        assertTrue("retargeted project must load", native.loadModel(embedded.absolutePath))
        val config = resolveTargetedSliceConfig(
            target = SlicerTarget.BambuH2D,
            base = SliceConfig(
                extruderCount = 2,
                extruderTemps = intArrayOf(220, 220),
            ),
        ).copy(layerHeight = 0f)

        val result = native.slice(config)
        assertNotNull("native crash or process death must not masquerade as a null result", result)
        assertTrue(result!!.errorMessage, result.success)

        val gcode = File(result.gcodePath).readText()
        assertTrue(gcode.contains("; printer_model = Bambu Lab H2D"))
        assertTrue(gcode.contains("; printable_area = 0x0,350x0,350x320,0x320"))
        assertTrue(gcode.contains("; elefant_foot_compensation = 0.15"))
        assertTrue(gcode.contains("; initial_layer_print_height = 0.2"))
        assertTrue(gcode.contains("; curr_bed_type = Textured PEI Plate"))
        assertTrue(gcode.contains("; textured_plate_temp = 55"))
        assertTrue(gcode.contains("; filament_flow_ratio = 0.98"))
        assertTrue(gcode.contains("; reduce_fan_stop_start_freq = 1"))
        assertTrue(gcode.contains("; fan_cooling_layer_time = 100"))
        assertFalse("U1 protocol must not leak into Bambu output", gcode.contains("PRINT_START"))
        assertFalse("U1 protocol must not leak into Bambu output", gcode.contains("PRINT_END"))
    }
}
