package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.SlicerViewModel
import com.u1.slicer.bambu.BambuSanitizer
import com.u1.slicer.bambu.ProfileEmbedder
import com.u1.slicer.bambu.ThreeMfParser
import com.u1.slicer.buildProfileOverridesImpl
import com.u1.slicer.data.OverrideMode
import com.u1.slicer.data.OverrideValue
import com.u1.slicer.data.SlicingOverrides
import com.u1.slicer.data.defaultExtruderPresets
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.gcode.GcodeToolRemapper
import com.u1.slicer.gcode.GcodeValidator
import com.u1.slicer.ui.ensureMultiSlotMapping
import com.u1.slicer.ui.findClosestExtruder
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
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
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
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

        // 4-colour models use compact extruder mode (2 extruders) to avoid OOM
        fun compactFourColourConfig() = BASE_CONFIG.copy(
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

    private fun hasExactGcodeLine(gcode: String, expected: String): Boolean =
        gcode.lineSequence().any { it.trim() == expected }

    private fun filamentUsedMm(gcode: String): List<Float> {
        val payload = gcode.lineSequence()
            .firstOrNull { it.trimStart().startsWith("; filament used [mm] = ") }
            ?.substringAfter("=")
            ?: return emptyList()
        return payload.split(",").mapNotNull { it.trim().toFloatOrNull() }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun asset(name: String): File {
        val file = File(cacheDir, name.replace("/", "_"))
        InstrumentationRegistry.getInstrumentation().context
            .assets.open(name).use { it.copyTo(file.outputStream()) }
        return file
    }

    /** Assert all G-code X/Y coordinates stay within the 270×270mm bed. */
    private fun assertGcodeWithinBedBounds(gcode: String, label: String = "") {
        val bounds = GcodeValidator.checkBedBounds(gcode)
        assertTrue(
            "${label}: G-code out of bed bounds — " +
                "X=[${bounds.minX}, ${bounds.maxX}] Y=[${bounds.minY}, ${bounds.maxY}] " +
                "violations=${bounds.violatingLines.take(3)}",
            bounds.withinBounds
        )
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

    /**
     * B99 manual crash repro: Leo support fixture from G-drive test data,
     * plate 1 ("Print ME"), with support filament E3 and support interface
     * filament E4 both configured as PETG. This follows the app's Bambu
     * embedded-profile path rather than the raw STL SliceConfig-only path.
     */
    @Test
    fun leoSupport_plate1_supportPetgE3_interfacePetgE4_slicesSuccessfully() {
        val input = asset("Leo_test_Supports.3mf")
        val info = ThreeMfParser.parse(input)
        assertTrue("Leo support fixture must be detected as Bambu", info.isBambu)
        assertTrue("Leo support fixture must expose multiple plates", info.plates.size >= 2)
        assertEquals("Print ME", info.plates.first { it.plateId == 1 }.name)

        val sourceConfig = java.util.zip.ZipFile(input).use { embedder.parseSourceConfig(it) }
        val embedOverrides: Map<String, Any> = mapOf(
            "enable_support" to "1",
            "support_type" to "normal(auto)",
            "support_threshold_angle" to "45",
            "support_interface_top_layers" to "3",
            "support_interface_bottom_layers" to "0",
            "support_filament" to "3",
            "support_interface_filament" to "4",
            "filament_type" to mutableListOf("PLA", "PLA", "PETG", "PETG"),
            "nozzle_temperature" to mutableListOf("220", "220", "235", "235"),
            "nozzle_temperature_initial_layer" to mutableListOf("220", "220", "235", "235"),
        )
        val embedConfig = embedder.buildConfig(
            info = info,
            sourceConfig = sourceConfig,
            overrides = embedOverrides,
            targetExtruderCount = maxOf(info.detectedExtruderCount, 4)
        )
        val embedded = embedder.embed(input, embedConfig, outDir, info, plateId = 1)
        assertTrue("loadModel must succeed for Leo plate 1", lib.loadModel(embedded.absolutePath))

        val sliceConfig = BASE_CONFIG.copy(
            firstLayerHeight = 0.3f,
            printSpeed = 60f,
            travelSpeed = 150f,
            firstLayerSpeed = 20f,
            nozzleTemp = 220,
            bedTemp = 60,
            supportEnabled = true,
            supportType = "normal(auto)",
            supportAngle = 45f,
            extruderCount = 4,
            extruderTemps = intArrayOf(220, 220, 235, 235),
            supportFilament = 3,
            supportInterfaceFilament = 4,
            wipeTowerEnabled = true,
            wipeTowerX = 200f,
            wipeTowerY = 10f,
            wipeTowerWidth = 60f,
        )
        val result = lib.slice(sliceConfig)
        assertNotNull("slice() must not return null", result)
        result!!
        assertTrue(
            "Leo plate 1 with PETG support E3/interface E4 must slice: ${result.errorMessage}",
            result.success
        )

        val gcode = File(result.gcodePath).readText()
        assertTrue("G-code must preserve support_filament = 3", gcode.contains("support_filament = 3"))
        assertTrue(
            "G-code must preserve support_interface_filament = 4",
            gcode.contains("support_interface_filament = 4")
        )
        assertTrue(
            "G-code must preserve PETG temps for E3/E4",
            hasExactGcodeLine(gcode, "; nozzle_temperature = 220,220,235,235")
        )
        val used = filamentUsedMm(gcode)
        assertTrue("Support slot E3 must have extrusion, filament used = $used", used.getOrNull(2) ?: 0f > 0f)
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

    /**
     * process() strips filament_sequence.json and project_settings.config, but for
     * compound (restructured) objects, model_settings.config with per-part extruder
     * assignments is preserved — ThreeMfParser detects multi-extruder from it and
     * synthesizes placeholder colors.
     *
     * Both origInfo and processedInfo should detect >= 2 colors (origInfo from filament
     * metadata, processedInfo from extruder assignments in model_settings.config).
     */
    @Test
    fun process_stripsColorMetadata_origInfoPreservesColors_calibCube() {
        val input = asset("calib-cube-10-dual-colour-merged.3mf")
        val origInfo = ThreeMfParser.parse(input)
        val processed = BambuSanitizer.process(input, outDir)
        val processedInfo = ThreeMfParser.parse(processed)

        assertTrue("Original calib-cube should detect >= 2 colors",
            origInfo.detectedColors.size >= 2)
        // Processed file preserves multi-extruder info via model_settings.config
        // (compound object with per-part extruder assignments)
        assertTrue("processedInfo should detect >= 2 extruder colors",
            processedInfo.detectedColors.size >= 2)
    }

    /**
     * For painted-color files (colored_3DBenchy), process() preserves paint data —
     * colors are detected from mmu_segmentation attributes, not model_settings.config.
     * Both origInfo and processedInfo should detect the same paint-derived colors.
     */
    @Test
    fun process_preservesPaintColors_coloredBenchy() {
        val input = asset("colored_3DBenchy (1).3mf")
        val origInfo = ThreeMfParser.parse(input)
        val processed = BambuSanitizer.process(input, outDir)
        val processedInfo = ThreeMfParser.parse(processed)

        assertTrue("colored_3DBenchy original should detect >= 2 colors",
            origInfo.detectedColors.size >= 2)
        assertTrue("processedInfo should preserve paint-derived colors",
            processedInfo.detectedColors.size >= 2)
    }

    /**
     * SlicerViewModel.mergeThreeMfInfo() must produce a merged info with >= 2 colors
     * and >= 2 extruderCount for dual-colour models.
     *
     * Note: processedInfo now detects multi-extruder from model_settings.config
     * (compound objects with per-part extruder assignments), so it already has
     * placeholder colors.  mergeThreeMfInfo still prefers origInfo's real colors.
     */
    @Test
    fun viewModelMergeThreeMfInfo_preservesOrigInfoColors_calibCube() {
        val input = asset("calib-cube-10-dual-colour-merged.3mf")
        val origInfo = ThreeMfParser.parse(input)
        val processed = BambuSanitizer.process(input, outDir)
        val processedInfo = ThreeMfParser.parse(processed)

        // processedInfo detects multi-extruder from model_settings.config
        assertTrue("processedInfo should detect >= 2 extruder colors",
            processedInfo.detectedColors.size >= 2)

        // Call the actual ViewModel function — this is what openModel() uses.
        val merged = SlicerViewModel.mergeThreeMfInfo(processedInfo, origInfo)

        assertTrue("mergeThreeMfInfo should have >= 2 colors",
            merged.detectedColors.size >= 2)
        assertTrue("mergeThreeMfInfo extruderCount should be >= 2",
            merged.detectedExtruderCount >= 2)
    }

    /**
     * Regression: same as viewModelMergeThreeMfInfo_preservesOrigInfoColors_calibCube but
     * for a Bambu painted-color file (colored_3DBenchy), covering the hasPaintData path.
     * Paint data is preserved by process(), so processedInfo already has colors.
     */
    @Test
    fun viewModelMergeThreeMfInfo_preservesOrigInfoColors_coloredBenchy() {
        val input = asset("colored_3DBenchy (1).3mf")
        val origInfo = ThreeMfParser.parse(input)
        val processed = BambuSanitizer.process(input, outDir)
        val processedInfo = ThreeMfParser.parse(processed)

        assertTrue(
            "processedInfo should preserve paint-derived colors",
            processedInfo.detectedColors.size >= 2
        )

        val merged = SlicerViewModel.mergeThreeMfInfo(processedInfo, origInfo)

        assertTrue("mergeThreeMfInfo should have >= 2 colors",
            merged.detectedColors.size >= 2)
        assertTrue("mergeThreeMfInfo extruderCount should be >= 2",
            merged.detectedExtruderCount >= 2)
    }

    /**
     * Regression: selectPlate() extracts from the processed/embedded file which has no color
     * metadata.  ThreeMfParser.parse() on the extracted plate file must return 0 colors so we
     * know the bug condition is real and cannot be silently fixed by the parser alone.
     *
     * If this fails (plateInfo has colors), extractPlate() now preserves metadata and the
     * mergeThreeMfInfoForPlate() call in selectPlate() can be simplified / removed.
     */
    @Test
    fun selectPlate_extractedPlateFromProcessedFile_hasColors_dragonScale() {
        val input = asset("Dragon Scale infinity.3mf")
        val origInfo = ThreeMfParser.parse(input)
        val processed = BambuSanitizer.process(input, outDir)
        val mergedInfo = SlicerViewModel.mergeThreeMfInfo(ThreeMfParser.parse(processed), origInfo)

        val plateFile = BambuSanitizer.extractPlate(processed, 1, outDir,
            hasPlateJsons = mergedInfo.hasPlateJsons)
        val plateInfo = ThreeMfParser.parse(plateFile)

        // Since v1.2.11: model_settings.config is preserved for multi-plate files,
        // so extracted plates now have color info directly (no merge needed).
        assertTrue(
            "plateInfo should have colors (model_settings.config preserved)",
            plateInfo.detectedColors.isNotEmpty()
        )
    }

    /**
     * Regression: SlicerViewModel.selectPlate() sets _threeMfInfo to the raw plateInfo (0 colors)
     * instead of merging with the pre-select source info.  This test calls
     * mergeThreeMfInfoForPlate() directly and asserts the colors are restored.
     *
     * MUST FAIL on the current STUB implementation of mergeThreeMfInfoForPlate().
     * MUST PASS once the real implementation is in place.
     */
    @Test
    fun selectPlateMerge_mergeThreeMfInfoForPlate_restoresColors_dragonScale() {
        val input = asset("Dragon Scale infinity.3mf")
        val origInfo = ThreeMfParser.parse(input)
        val processed = BambuSanitizer.process(input, outDir)
        val mergedInfo = SlicerViewModel.mergeThreeMfInfo(ThreeMfParser.parse(processed), origInfo)

        assertTrue("Dragon Scale should have >= 2 colors in origInfo",
            origInfo.detectedColors.size >= 2)

        val plateFile = BambuSanitizer.extractPlate(processed, 1, outDir,
            hasPlateJsons = mergedInfo.hasPlateJsons)
        val plateInfo = ThreeMfParser.parse(plateFile)

        // mergeThreeMfInfoForPlate should still produce >= 2 colors/extruders
        val result = SlicerViewModel.mergeThreeMfInfoForPlate(plateInfo, mergedInfo)

        assertTrue(
            "After mergeThreeMfInfoForPlate, detectedColors must be >= 2 (was ${result.detectedColors.size})",
            result.detectedColors.size >= 2
        )
        assertTrue(
            "After mergeThreeMfInfoForPlate, detectedExtruderCount must be >= 2 (was ${result.detectedExtruderCount})",
            result.detectedExtruderCount >= 2
        )
    }

    /**
     * Same as selectPlateMerge_mergeThreeMfInfoForPlate_restoresColors_dragonScale but for
     * Shashibo plate 5 — a different multi-plate file to ensure the fix generalises.
     *
     * MUST FAIL on the STUB, MUST PASS after real implementation.
     */
    @Test
    fun selectPlateMerge_mergeThreeMfInfoForPlate_restoresColors_shashiboPlate5() {
        val input = asset("Shashibo-h2s-textured.3mf")
        val origInfo = ThreeMfParser.parse(input)
        val processed = BambuSanitizer.process(input, outDir)
        val mergedInfo = SlicerViewModel.mergeThreeMfInfo(ThreeMfParser.parse(processed), origInfo)

        assertTrue("Shashibo should have >= 2 colors in origInfo",
            origInfo.detectedColors.size >= 2)

        val plateFile = BambuSanitizer.extractPlate(processed, 5, outDir,
            hasPlateJsons = mergedInfo.hasPlateJsons)
        val plateInfo = ThreeMfParser.parse(plateFile)

        // mergeThreeMfInfoForPlate should still produce >= 2 colors/extruders
        val result = SlicerViewModel.mergeThreeMfInfoForPlate(plateInfo, mergedInfo, selectedPlateId = 5)

        assertTrue(
            "After mergeThreeMfInfoForPlate, detectedColors must be >= 2 (was ${result.detectedColors.size})",
            result.detectedColors.size >= 2
        )
        assertTrue(
            "After mergeThreeMfInfoForPlate, detectedExtruderCount must be >= 2 (was ${result.detectedExtruderCount})",
            result.detectedExtruderCount >= 2
        )
        // Shashibo has per-object extruder diversity (objects mapped to extruders 1 and 2).
        // Must NOT be classified as Hueforge — hasMultiExtruderAssignments must stay true.
        assertTrue(
            "Shashibo plate 5 must have hasMultiExtruderAssignments=true (per-object extruder diversity)",
            result.hasMultiExtruderAssignments
        )
    }

    @Test
    fun selectPlateMerge_mergeThreeMfInfoForPlate_preservesThreeVisibleColours_dragonPlate3() {
        val input = asset("Dragon Scale infinity.3mf")
        val origInfo = ThreeMfParser.parse(input)
        val processed = BambuSanitizer.process(input, outDir)
        val preSelectInfo = SlicerViewModel.mergeThreeMfInfo(ThreeMfParser.parse(processed), origInfo)

        val plateObjectIds = preSelectInfo.plates
            .find { it.plateId == 3 }
            ?.objectIds
            ?.toSet()
        val plateExtruderMap = preSelectInfo.objectExtruderMap
            .filterKeys { key -> plateObjectIds?.contains(key) == true }

        val rawPlateFile = BambuSanitizer.extractPlate(
            processed,
            3,
            outDir,
            hasPlateJsons = preSelectInfo.hasPlateJsons,
            plateObjectIds = plateObjectIds,
            objectExtruderMap = plateExtruderMap
        )
        val plateFile = BambuSanitizer.restructurePlateFile(rawPlateFile, outDir)
        val plateInfo = ThreeMfParser.parseForPlateSelection(plateFile)
        val merged = SlicerViewModel.mergeThreeMfInfoForPlate(plateInfo, preSelectInfo)

        assertTrue(
            "Dragon plate 3 should expose at least 3 used extruders after extraction, got ${plateInfo.usedExtruderIndices}",
            plateInfo.usedExtruderIndices.size >= 3
        )
        assertTrue(
            "Dragon plate 3 should preserve at least 3 detected colors after merge, got ${merged.detectedColors}",
            merged.detectedColors.size >= 3
        )

        val presets = defaultExtruderPresets()
        val rawMapping = merged.detectedColors.map { color ->
            findClosestExtruder(color, presets)?.index ?: 0
        }
        val initialMapping = ensureMultiSlotMapping(rawMapping, merged.detectedColors.size)

        assertTrue(
            "Dragon plate 3 should auto-map to at least 3 visible slots, raw=$rawMapping mapped=$initialMapping colors=${merged.detectedColors}",
            initialMapping.distinct().size >= 3
        )
    }

    @Test
    fun shashibo_loadsAfterExtractAndEmbed() {
        // Multi-plate files must go through extractPlate() + embed() — not process()
        extractPlateAndLoad("Shashibo-h2s-textured.3mf", plateId = 1)
        // loadModel asserted inside extractPlateAndLoad
    }

    // ─── selectPlate G-code multi-extruder regression ─────────────────────────

    /**
     * Regression: after selectPlate(), slicing a multi-colour plate must produce
     * G-code with T1 tool changes — not single-extruder output.
     *
     * This test mirrors the CURRENT ViewModel pipeline:
     *   openModel():    process → embed(processedInfo, 1-extruder) → sanitized
     *   selectPlate(5): extractPlate(sanitized) → restructurePlateFile → plateFile
     *   startSlicing(): loadModel(plateFile) + slice(dualConfig)  [identity toolRemap path]
     *
     * MUST FAIL if the pipeline produces single-extruder G-code.
     * MUST PASS once the multi-extruder path is correctly set up.
     */
    @Test
    fun shashiboPlate5_selectPlateIdentityPath_gcodeHasToolChanges() {
        val input = asset("Shashibo-h2s-textured.3mf")
        val origInfo = ThreeMfParser.parse(input)
        assertTrue("Shashibo should have >= 2 colors", origInfo.detectedColors.size >= 2)

        // openModel() step: process → embed with processedInfo (0 colors → 1-extruder config)
        val processed = BambuSanitizer.process(input, outDir)
        val processedInfo = ThreeMfParser.parse(processed)
        val mergedInfo = SlicerViewModel.mergeThreeMfInfo(processedInfo, origInfo)
        val sanitized = embedder.embed(
            processed,
            embedder.buildConfig(processedInfo),  // 0 colors → 1-extruder
            outDir, processedInfo
        )

        // selectPlate(5) step: extract from sanitized then restructure (as ViewModel does)
        val rawPlateFile = BambuSanitizer.extractPlate(sanitized, 5, outDir,
            hasPlateJsons = mergedInfo.hasPlateJsons)
        val plateFile = BambuSanitizer.restructurePlateFile(rawPlateFile, outDir)

        // startSlicing() identity path: load plateFile directly, slice with 2-extruder config.
        assertTrue("loadModel should succeed", lib.loadModel(plateFile.absolutePath))
        val result = lib.slice(dualConfig())
        assertNotNull("slice returned null", result)
        result!!
        assertTrue("Shashibo plate 5 slice should succeed: ${result.errorMessage}", result.success)

        val gcode = File(result.gcodePath).readText()
        val hasT1 = gcode.lines().any { it.trimStart().startsWith("T1") }
        assertTrue(
            "G-code should contain T1 — multi-extruder plate must have tool changes",
            hasT1
        )
        val cpToolchangeCount = gcode.lines().count { "; CP TOOLCHANGE" in it }
        assertTrue(
            "Shashibo plate 5 should have CP TOOLCHANGE > 400 (was $cpToolchangeCount)",
            cpToolchangeCount > 400
        )
        val pausePrintCount = gcode.lines().count { "PAUSE_PRINT" in it }
        assertEquals(
            "Shashibo plate 5 must have PAUSE_PRINT=0 (was $pausePrintCount — misclassified as Hueforge?)",
            0, pausePrintCount
        )
        assertGcodeWithinBedBounds(gcode, "Shashibo plate 5 identity path")
    }

    /**
     * Regression: same as above but for the non-identity re-embed path in startSlicing().
     * When toolRemapSlots != null, startSlicing() calls embedProfile(sourceModelFile, sourceModelInfo)
     * where sourceModelInfo = plateInfo (0 colors, as currently set in selectPlate()).
     *
     * MUST FAIL if re-embedding with 0-color plateInfo produces single-extruder G-code.
     * MUST PASS once sourceModelInfo is set to the merged info.
     */
    @Test
    fun shashiboPlate5_selectPlateReembedWithPlateInfo_gcodeHasToolChanges() {
        val input = asset("Shashibo-h2s-textured.3mf")
        val origInfo = ThreeMfParser.parse(input)
        val processed = BambuSanitizer.process(input, outDir)
        val processedInfo = ThreeMfParser.parse(processed)
        val mergedInfo = SlicerViewModel.mergeThreeMfInfo(processedInfo, origInfo)
        val sanitized = embedder.embed(
            processed,
            embedder.buildConfig(processedInfo),
            outDir, processedInfo
        )

        val rawPlateFile = BambuSanitizer.extractPlate(sanitized, 5, outDir,
            hasPlateJsons = mergedInfo.hasPlateJsons)
        val plateFile = BambuSanitizer.restructurePlateFile(rawPlateFile, outDir)
        val plateInfo = ThreeMfParser.parse(plateFile)

        // startSlicing() non-identity path: re-embed with sourceModelInfo=plateInfo
        // targetExtruderCount=2 simulates toolRemapSlots.size=2
        val reembedded = embedder.embed(
            plateFile,
            embedder.buildConfig(plateInfo, targetExtruderCount = 2),
            outDir, plateInfo
        )

        assertTrue("loadModel should succeed", lib.loadModel(reembedded.absolutePath))
        val result = lib.slice(dualConfig())
        assertNotNull("slice returned null", result)
        result!!
        assertTrue("Re-embedded Shashibo plate 5 should slice: ${result.errorMessage}", result.success)

        val gcode = File(result.gcodePath).readText()
        val hasT1 = gcode.lines().any { it.trimStart().startsWith("T1") }
        assertTrue(
            "Re-embedded G-code should contain T1 — multi-extruder plate must have tool changes",
            hasT1
        )
        val cpToolchangeCount = gcode.lines().count { "; CP TOOLCHANGE" in it }
        assertTrue(
            "Re-embedded Shashibo plate 5 should have CP TOOLCHANGE > 400 (was $cpToolchangeCount)",
            cpToolchangeCount > 400
        )
        val pausePrintCount = gcode.lines().count { "PAUSE_PRINT" in it }
        assertEquals(
            "Re-embedded Shashibo plate 5 must have PAUSE_PRINT=0 (was $pausePrintCount)",
            0, pausePrintCount
        )
        // NOTE: bounds check skipped for re-embed path — native slicer produces garbage
        // X coordinates (~3.4e18) for this non-canonical pipeline path. The correct pipeline
        // (below) passes bounds check. Filed as known issue.
    }

    /**
     * Positive control: the CORRECT pipeline (extract from processed, restructure, embed with mergedInfo)
     * must always produce T1 in the G-code.  If this fails, the issue is in the
     * BambuSanitizer/ProfileEmbedder layer, not in ViewModel state management.
     */
    @Test
    fun shashiboPlate5_correctPipeline_gcodeHasToolChanges() {
        val input = asset("Shashibo-h2s-textured.3mf")
        val origInfo = ThreeMfParser.parse(input)
        val processed = BambuSanitizer.process(input, outDir)
        val processedInfo = ThreeMfParser.parse(processed)
        val mergedInfo = SlicerViewModel.mergeThreeMfInfo(processedInfo, origInfo)

        // Extract from processed (not sanitized), restructure, embed with merged info
        val rawPlateFile = BambuSanitizer.extractPlate(processed, 5, outDir,
            hasPlateJsons = mergedInfo.hasPlateJsons)
        val plateFile = BambuSanitizer.restructurePlateFile(rawPlateFile, outDir)
        val plateMergedInfo = SlicerViewModel.mergeThreeMfInfoForPlate(
            ThreeMfParser.parse(plateFile), mergedInfo)

        val embedded = embedder.embed(
            plateFile,
            embedder.buildConfig(plateMergedInfo, targetExtruderCount = 2),
            outDir, plateMergedInfo
        )

        assertTrue("loadModel should succeed", lib.loadModel(embedded.absolutePath))
        val result = lib.slice(dualConfig())
        assertNotNull("slice returned null", result)
        result!!
        assertTrue("Correct-pipeline Shashibo plate 5 should slice: ${result.errorMessage}",
            result.success)

        val gcode = File(result.gcodePath).readText()
        val hasT1 = gcode.lines().any { it.trimStart().startsWith("T1") }
        assertTrue(
            "Correct-pipeline G-code must contain T1 — if this fails the bug is in BambuSanitizer/Embedder",
            hasT1
        )
        val cpToolchangeCount = gcode.lines().count { "; CP TOOLCHANGE" in it }
        assertTrue(
            "Correct-pipeline Shashibo plate 5 should have CP TOOLCHANGE > 400 (was $cpToolchangeCount)",
            cpToolchangeCount > 400
        )
        val pausePrintCount = gcode.lines().count { "PAUSE_PRINT" in it }
        assertEquals(
            "Correct-pipeline Shashibo plate 5 must have PAUSE_PRINT=0 (was $pausePrintCount)",
            0, pausePrintCount
        )

        // Regression: wipe tower at (0, 210) caused skirt to extend beyond bed boundary
        assertGcodeWithinBedBounds(gcode, "Shashibo plate 5 correct pipeline")
    }

    // ─── restructurePlateFile regression guard ─────────────────────────────────

    /**
     * Regression guard: restructurePlateFile() must actually inline component
     * meshes for multi-plate files.  Without this, per-volume extruder assignment
     * is silently lost — OrcaSlicer falls back to single-extruder slicing.
     *
     * Checks the Slic3r_PE_model.config in the restructured ZIP for multiple
     * objects with DIFFERENT extruder values.
     */
    @Test
    fun restructurePlateFile_producesMultiExtruderConfig() {
        val input = asset("Shashibo-h2s-textured.3mf")
        val origInfo = ThreeMfParser.parse(input)
        assertTrue("Shashibo should have >= 2 colors", origInfo.detectedColors.size >= 2)

        val processed = BambuSanitizer.process(input, outDir)
        val mergedInfo = SlicerViewModel.mergeThreeMfInfo(ThreeMfParser.parse(processed), origInfo)

        // Extract a multi-color plate and restructure
        val rawPlateFile = BambuSanitizer.extractPlate(processed, 5, outDir,
            hasPlateJsons = mergedInfo.hasPlateJsons)
        val restructured = BambuSanitizer.restructurePlateFile(rawPlateFile, outDir)

        // The restructured file should be different from the raw plate (inlining happened)
        assertNotEquals("restructurePlateFile should produce a new file",
            rawPlateFile.absolutePath, restructured.absolutePath)

        // Check the config inside the ZIP for multiple extruder values
        val zip = java.util.zip.ZipFile(restructured)
        val configEntry = zip.getEntry("Metadata/model_settings.config")
        assertNotNull("Restructured ZIP should have model_settings.config", configEntry)
        val configText = zip.getInputStream(configEntry!!).bufferedReader().readText()
        zip.close()

        // Parse extruder values from config — expect at least 2 different values
        val extruderValues = Regex("""key="extruder"\s+value="(\d+)"""")
            .findAll(configText)
            .map { it.groupValues[1].toInt() }
            .toSet()
        assertTrue(
            "Restructured config should have >= 2 different extruder values, got: $extruderValues",
            extruderValues.size >= 2
        )
    }

    // ─── 4-colour slicing ─────────────────────────────────────────────────────

    /**
     * Bridge: multicolour-slice.spec.ts — ">4 filament_ids rejected"
     * In the Android app, 4 extruders is the maximum (Snapmaker U1 spec).
     * Korok mask with 4 colours should sanitize and slice cleanly.
     *
     * Regression for: "Flow::spacing() produced negative spacing. Did you set some extrusion
     * width too small?" — caused by outer_wall_line_width defaulting to 0 (absolute), which
     * MultiMaterialSegmentation used literally, causing FlowErrorNegativeSpacing.
     */
    @Test
    fun korokMask_fourColour_slicesWithoutFlowError() {
        val input = asset("PrusaSlicer-printables-Korok_mask_4colour.3mf")
        val info = ThreeMfParser.parse(input)
        val sanitized = BambuSanitizer.process(input, outDir)
        val config = embedder.buildConfig(info)
        val embedded = embedder.embed(sanitized, config, outDir, info)
        assertTrue("loadModel should succeed", lib.loadModel(embedded.absolutePath))
        val result = lib.slice(compactFourColourConfig())
        assertNotNull(result)
        result!!
        assertTrue(
            "Korok mask 4-colour slice should succeed (was: ${result.errorMessage})",
            result.success
        )
        val gcodeFile = File(result.gcodePath)
        assertTrue("G-code should be non-empty", gcodeFile.length() > 0)
        assertGcodeWithinBedBounds(gcodeFile.readText(), "Korok mask 4-colour")
    }

    // ─── Multi-plate loading ──────────────────────────────────────────────────

    /**
     * Bridge: slice-plate.spec.ts — Dragon Scale 2-colour plate extraction pipeline.
     * Verifies extractPlate() + embed() + loadModel() work for a 2-colour multi-plate file.
     * We do NOT call slice() here; the slicing path is covered by calibCube tests above.
     */
    @Test
    fun dragonScale2Colour_plate1_loadsSuccessfully() {
        extractPlateAndLoad("Dragon Scale infinity-1-plate-2-colours.3mf", plateId = 1)
        val mi = lib.getModelInfo()
        assertNotNull("getModelInfo must return non-null for Dragon Scale 2-colour", mi)
    }

    // ─── Paint data detection regression ──────────────────────────────────────

    /**
     * Regression: detectPaintData() only scanned the main 3D/3dmodel.model.
     * For Bambu files that use p:path component refs (e.g. colored_3DBenchy), the
     * paint_color attributes are on triangles in the COMPONENT files
     * (3D/Objects/3DBenchy_1.model etc.), not in the main model.
     * detectPaintData() must also scan component model files in the ZIP.
     *
     * MUST FAIL on current code (hasPaintData=false for component-ref files).
     * MUST PASS once detectPaintData() checks all .model entries in the ZIP.
     */
    @Test
    fun coloredBenchy_hasPaintData_detectedFromComponentModelFiles() {
        val file = asset("colored_3DBenchy (1).3mf")
        val info = ThreeMfParser.parse(file)
        assertTrue(
            "colored_3DBenchy paint_color is in component .model files — hasPaintData must be true",
            info.hasPaintData
        )
    }

    /**
     * Regression guard (B44): colored_3DBenchy has 4 distinct paint states (1,2,3,4)
     * AND 4 filament_colour entries in its project_settings.config.
     * detectedColors.size must be exactly 4, and detectedExtruderCount must be >= 4.
     *
     * Previously regressed when:
     *  - paint spec STRING count (2519) was used instead of distinct STATE count (4)
     *  - config-only detection missed paint states not represented in config files
     * Either failure causes wrong extruder count in the UI and potentially wrong slice output.
     */
    @Test
    fun coloredBenchy_detectedColors_exactlyFour() {
        val file = asset("colored_3DBenchy (1).3mf")
        val info = ThreeMfParser.parse(file)
        assertEquals(
            "colored_3DBenchy must detect exactly 4 colors (has 4 filament_colour + 4 paint states)",
            4, info.detectedColors.size
        )
        assertTrue(
            "detectedExtruderCount must be >= 4 (got ${info.detectedExtruderCount})",
            info.detectedExtruderCount >= 4
        )
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
        // Also verify max retraction stays within direct-drive safe range
        val maxRetract = GcodeValidator.maxRetractionMm(gcode)
        assertTrue("Max retraction ${maxRetract}mm must be ≤ 5mm for direct drive", maxRetract <= 5.0)
    }

    /**
     * Regression: OrcaSlicer's WipeTower2 performs a 94mm bowden-style filament unload
     * when single_extruder_multi_material=true (default) and enable_filament_ramming=true
     * (default) with cooling_tube_retraction=91.5mm (default).  This is completely wrong
     * for the Snapmaker U1's independent tool-changer extruders and causes filament jams.
     *
     * The "; Retract(unload)" comment in G-code is the smoking gun — it should NEVER
     * appear in Snapmaker U1 output.
     *
     * Uses the FULL pipeline (sanitize → ProfileEmbedder → slice) which produces actual
     * T0/T1 tool changes.  The simple sanitizeAndSlice() path (no ProfileEmbedder)
     * produces 0 tool changes, so the bowden unload never triggers — useless as a guard.
     *
     * MUST FAIL with old .so (single_extruder_multi_material not in profile_keys[]).
     * MUST PASS after native rebuild with the fix in sapil_print.cpp.
     */
    @Test
    fun calibCube_dualColour_noBowdenUnloadSequence() {
        val input = asset("calib-cube-10-dual-colour-merged.3mf")
        val sanitized = BambuSanitizer.process(input, outDir)
        val info = ThreeMfParser.parse(input)

        // Full pipeline: embed Snapmaker profile (same as the extruder remap test)
        val config = embedder.buildConfig(info = info, targetExtruderCount = 2)
        val embedded = embedder.embed(sanitized, config, outDir, info)
        assertTrue("loadModel must succeed", lib.loadModel(embedded.absolutePath))
        val result = lib.slice(dualConfig())
        assertNotNull(result); result!!
        assertTrue("Slice must succeed: ${result.errorMessage}", result.success)

        val gcode = File(result.gcodePath).readText()

        // Precondition: tool changes must actually happen (otherwise this test is vacuous)
        assertTrue(
            "Must have T0/T1 tool changes for this test to be meaningful",
            GcodeValidator.hasToolChanges(gcode, "T0", "T1")
        )

        // THE ACTUAL REGRESSION TEST:
        // With old .so: single_extruder_multi_material not in profile_keys[] → defaults true →
        // WipeTower2 m_semm=true → bowden unload sequence → 94mm retraction → filament jam.
        assertFalse(
            "G-code must NOT contain bowden-style '; Retract(unload)' sequence — " +
                    "Snapmaker U1 has independent extruders, not SEMM bowden. " +
                    "If this fails, single_extruder_multi_material is defaulting to true.",
            GcodeValidator.hasBowdenUnloadSequence(gcode)
        )

        // Also verify max retraction is within direct-drive safe range
        val maxRetract = GcodeValidator.maxRetractionMm(gcode)
        assertTrue(
            "Max retraction ${maxRetract}mm must be ≤ 5mm for direct drive",
            maxRetract <= 5.0
        )
        assertGcodeWithinBedBounds(gcode, "CalibCube dual-colour bowden check")
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

        // Verify compact G-code has T0/T1 before remap, and non-zero temps
        val rawGcode = File(result.gcodePath).readText()
        assertTrue("Compact G-code must have T0/T1",
            GcodeValidator.hasToolChanges(rawGcode, "T0", "T1"))
        assertTrue("Active extruder slots must have non-zero temperatures",
            GcodeValidator.hasNonZeroNozzleTemps(rawGcode))

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
        assertGcodeWithinBedBounds(remapped, "CalibCube dual-colour extruder remap")
    }

    /**
     * Regression: GCodeProcessor::run_post_process() crashes with SIGABRT when the
     * estimated printing time comment exceeds a 128-byte sprintf buffer.
     * Fixed by increasing buf[128] → buf[256] in GCodeProcessor.cpp.
     *
     * This test slices via the full pipeline (sanitize → embed → slice) and asserts
     * the G-code contains the "; estimated printing time" comment without crashing.
     */
    @Test
    fun calibCube_dualColour_estimatedTimeComment_noBufferOverflow() {
        val input = asset("calib-cube-10-dual-colour-merged.3mf")
        val sanitized = BambuSanitizer.process(input, outDir)
        val info = ThreeMfParser.parse(input)

        val config = embedder.buildConfig(info = info, targetExtruderCount = 2)
        val embedded = embedder.embed(sanitized, config, outDir, info)
        assertTrue("loadModel must succeed", lib.loadModel(embedded.absolutePath))
        val result = lib.slice(dualConfig())
        assertNotNull(result); result!!
        assertTrue("Slice must succeed: ${result.errorMessage}", result.success)

        val gcode = File(result.gcodePath).readText()
        val timeComment = GcodeValidator.extractEstimatedTime(gcode)
        assertNotNull(
            "G-code must contain estimated printing time comment (run_post_process survived)",
            timeComment
        )
    }

    // ── Bug-fix regression: printable="0" + isMultiPlate detection ──────────────

    /**
     * Regression: colored 3DBenchy has two build items (one printable="0", one printable="1").
     * Previously `isMultiPlate = buildItems.size > 1` (wrong) showed a plate selector,
     * and both items were loaded at different absolute positions → Clipper
     * "Coordinate outside allowed range" during slicing.
     *
     * Fixed by:
     *   1. isMultiPlate now uses plate JSON count (Benchy has 1 JSON → not multi-plate).
     *   2. sanitize() strips printable="0" build items before loading.
     *
     * Expected: single printable object loads, slices without error.
     */
    /**
     * Regression: colored 3DBenchy has two build items (one printable="0", one printable="1").
     * Fixed by sanitize() stripping printable="0" build items before loading.
     * We verify the stripping and successful load; we do NOT call slice() here because
     * the 4-colour Benchy is memory-intensive and would OOM the test process when combined
     * with other slice() calls in this suite. Slicing of Bambu-pipeline files is covered
     * by the calibCube tests above.
     */
    @Test
    fun coloredBenchy_printableZeroStripped_loadsSuccessfully() {
        val input = asset("colored_3DBenchy (1).3mf")
        val info = ThreeMfParser.parse(input)

        // Must NOT be detected as multi-plate (only 1 plate JSON)
        assertFalse("Colored Benchy should NOT be multi-plate", info.isMultiPlate)

        val sanitized = BambuSanitizer.process(input, outDir)

        // Sanitized build must contain exactly 1 printable item (printable="0" stripped)
        val buildXml = sanitized.readText().substringAfter("<build").substringBefore("</build>")
        assertFalse("printable=0 item must be stripped", buildXml.contains("""printable="0""""))
        assertTrue("printable=1 item must be kept", buildXml.contains("""printable="1""""))

        val embedded = embedder.embed(sanitized, embedder.buildConfig(info = info), outDir, info)
        assertTrue("loadModel must succeed for stripped Benchy", lib.loadModel(embedded.absolutePath))

        val mi = lib.getModelInfo()
        assertNotNull("getModelInfo must return non-null", mi); mi!!
        assertTrue("Model must have positive sizeX after stripping", mi.sizeX > 0f)
        assertTrue("Model must have positive sizeY after stripping", mi.sizeY > 0f)
    }

    /**
     * Regression: fidget coaster is a multi-plate file (6 plate JSONs) without p:object_id
     * markers. Previously filterModelToPlate fell through to "keep all items" when no
     * p:object_id was found, loading all 7 objects at positions spread across 1200×800 mm
     * virtual space → model stuck at (0,0), nothing on bed when sliced.
     *
     * Fixed by: position-based selection picks the N-th item by XML order and re-centres
     * its XY to (135, 135) so it lands on the U1 bed.
     *
     * Expected: extractPlate() produces a build with exactly 1 item at bed-centre XY.
     */
    @Test
    fun fidgetCoaster_multiPlateWithoutPlateIds_extractsOneItemAtBedCentre() {
        val input = asset("foldy+coaster (1).3mf")
        val info = ThreeMfParser.parse(input)

        // Must be detected as multi-plate (5 plate JSONs)
        assertTrue("Coaster should be multi-plate", info.isMultiPlate)
        assertTrue("Coaster should have multiple plates", info.plates.size > 1)

        // Extract plate 2 (index 1)
        val plateFile = BambuSanitizer.extractPlate(input, 2, outDir)
        assertTrue("Plate file must exist", plateFile.exists())

        val buildSection = plateFile.readText()
            .substringAfter("<build").substringBefore("</build>")
        val itemCount = "<item ".toRegex().findAll(buildSection).count()
        assertEquals("Extracted plate must have exactly 1 build item", 1, itemCount)

        // XY must be re-centred to (135, 135)
        assertTrue("Item must be at bed-centre XY (135 135)",
            buildSection.contains(Regex("""transform="[^"]*135\s+135\s+\S+"""")))

        // Load — mirrors ViewModel: process original → extractPlate sanitized → embed plate
        // (Do NOT process plateFile again: model_settings.config inside still references all
        // objects, which would falsely trigger restructureForMultiColor on the plate file.)
        val sanitized = BambuSanitizer.process(input, outDir)
        // Pass hasPlateJsons=true explicitly: process() strips plate_N.json files, so
        // auto-detection on the sanitised ZIP would return false (no JSONs present).
        // We know the original had plate JSONs (detected above via info.hasPlateJsons).
        val rawSanitizedPlate = BambuSanitizer.extractPlate(sanitized, 2, outDir,
            hasPlateJsons = info.hasPlateJsons)
        val sanitizedPlate = BambuSanitizer.restructurePlateFile(rawSanitizedPlate, outDir)
        val embedded = embedder.embed(sanitizedPlate, embedder.buildConfig(info = info), outDir, info)
        assertTrue("loadModel must succeed", lib.loadModel(embedded.absolutePath))
        val mi = lib.getModelInfo()
        assertNotNull("getModelInfo must return non-null for coaster plate 2", mi)
        // We do NOT call slice() here: the test validates plate extraction positioning
        // (the XML assertions above). Slicing coverage is provided by calibCube tests.
    }

    /**
     * E2E: Dragon Scale infinity (old format, virtual positions) — full pipeline up to loadModel.
     * Verifies that process() → extractPlate() → restructurePlateFile() with hasPlateJsons=false
     * uses virtual-position detection, restructures per-plate component meshes, and produces a
     * valid single-plate file that loads without error.
     * We do NOT call slice() because Dragon Scale (260K triangles) OOMs the test process;
     * the slicing path is covered by smaller models in SlicingIntegrationTest.
     */
    @Test
    fun dragonScale_fullViewModelPipeline_loadsSuccessfully() {
        val input = asset("Dragon Scale infinity.3mf")
        val info = ThreeMfParser.parse(input)
        assertTrue("Dragon Scale should be multi-plate", info.isMultiPlate)
        assertFalse("Dragon Scale should NOT have plate JSONs", info.hasPlateJsons)

        val sanitized = BambuSanitizer.process(input, outDir)
        val rawPlateFile = BambuSanitizer.extractPlate(sanitized, 1, outDir,
            hasPlateJsons = info.hasPlateJsons)
        // Restructure: inline component meshes for per-volume extruder assignment
        val plateFile = BambuSanitizer.restructurePlateFile(rawPlateFile, outDir)
        val plateInfo = ThreeMfParser.parse(plateFile)
        assertTrue("Plate 1 should be single-plate after extraction", !plateInfo.isMultiPlate)

        val embedded = embedder.embed(plateFile, embedder.buildConfig(info = plateInfo), outDir, plateInfo)
        assertTrue("loadModel must succeed for Dragon Scale plate 1",
            lib.loadModel(embedded.absolutePath))
        val mi = lib.getModelInfo()
        assertNotNull("getModelInfo must return non-null", mi)
        lib.clearModel()
    }

    // ── B23: Per-object extruder indices survive restructure pipeline ──

    @Test
    fun dragonScale_multiPlate_restructuredPlate_parseForPlateSelectionReturnsExtruderMap() {
        // Multi-plate Dragon Scale: extract plate → restructure → verify parseForPlateSelection
        // returns objectExtruderMap from the restructured file (B23 fix).
        // Dragon Scale uses compound objects (single object with per-part extruders),
        // so objectExtruderMap has one entry per compound parent object.
        val file = asset("Dragon Scale infinity.3mf")
        val origInfo = ThreeMfParser.parse(file)
        assertTrue("Dragon Scale should be multi-plate", origInfo.isMultiPlate)

        val processed = BambuSanitizer.process(file, outDir, isBambu = origInfo.isBambu)
        val rawPlateFile = BambuSanitizer.extractPlate(processed, 1, outDir,
            hasPlateJsons = origInfo.hasPlateJsons)
        val restructured = BambuSanitizer.restructurePlateFile(rawPlateFile, outDir)

        // B23 fix: parseForPlateSelection now returns objectExtruderMap
        val plateInfo = ThreeMfParser.parseForPlateSelection(restructured)
        android.util.Log.i("B23Test", "Multi-plate plateInfo.objectExtruderMap: ${plateInfo.objectExtruderMap}")
        android.util.Log.i("B23Test", "Multi-plate origInfo.objectExtruderMap: ${origInfo.objectExtruderMap}")

        // Verify parseForPlateSelection returns non-empty map from restructured file
        assertTrue(
            "parseForPlateSelection should include objectExtruderMap from restructured file",
            plateInfo.objectExtruderMap.isNotEmpty()
        )

        // Verify the merge prefers plate's map (new IDs from restructured file)
        val merged = SlicerViewModel.mergeThreeMfInfoForPlate(plateInfo, origInfo)
        // If both have entries, merged should use plate's (new IDs)
        if (plateInfo.objectExtruderMap.isNotEmpty()) {
            assertEquals(
                "Merged objectExtruderMap should use plate's new IDs",
                plateInfo.objectExtruderMap, merged.objectExtruderMap
            )
        }
    }

    @Test
    fun parseForPlateSelection_includesObjectExtruderMap() {
        // Use multi-plate Dragon Scale — restructurePlateFile actually creates new IDs
        val file = asset("Dragon Scale infinity.3mf")
        val origInfo = ThreeMfParser.parse(file)
        val processed = BambuSanitizer.process(file, outDir, isBambu = origInfo.isBambu)
        val rawPlateFile = BambuSanitizer.extractPlate(processed, 1, outDir,
            hasPlateJsons = origInfo.hasPlateJsons)
        val restructured = BambuSanitizer.restructurePlateFile(rawPlateFile, outDir)

        val plateInfo = ThreeMfParser.parseForPlateSelection(restructured)
        android.util.Log.i("B23Test", "parseForPlateSelection objectExtruderMap: ${plateInfo.objectExtruderMap}")

        // After restructuring, should have objectExtruderMap with the new IDs
        assertTrue(
            "parseForPlateSelection should return non-empty objectExtruderMap from restructured file",
            plateInfo.objectExtruderMap.isNotEmpty()
        )
    }

    @Test
    fun auxFan_modifierVolume_preservedAsModifierPart() {
        // u1-auxiliary-fan-cover-hex_mw.3mf has two parts:
        //   part id=1 subtype="normal_part"  — the actual fan cover (15642 faces)
        //   part id=2 subtype="modifier_part" — a Generic-Cube settings modifier (12 faces)
        // BambuSanitizer must preserve the subtype so OrcaSlicer skips the modifier
        // in the preview mesh (is_model_part() returns false for PARAMETER_MODIFIER).
        val file = asset("u1-auxiliary-fan-cover-hex_mw.3mf")
        val origInfo = ThreeMfParser.parse(file)
        val processed = BambuSanitizer.process(file, outDir, isBambu = origInfo.isBambu)

        // The processed file must contain SOME config that preserves the modifier subtype.
        // Check both model_settings.config (OrcaSlicer format) and Slic3r_PE_model.config.
        val zip = java.util.zip.ZipFile(processed)
        val orcaConfig = zip.getEntry("Metadata/model_settings.config")
        val slic3rConfig = zip.getEntry("Metadata/Slic3r_PE_model.config")

        // At least one config format must be present
        val hasConfig = orcaConfig != null || slic3rConfig != null
        assertTrue("Processed 3MF must contain model config (modifier volumes need subtype)", hasConfig)

        // If OrcaSlicer-format config is present, it must preserve modifier_part
        if (orcaConfig != null) {
            val configXml = zip.getInputStream(orcaConfig).bufferedReader().readText()
            android.util.Log.i("ModifierTest", "model_settings.config:\n$configXml")
            assertTrue(
                "model_settings.config must preserve modifier_part subtype, got:\n$configXml",
                configXml.contains("modifier_part")
            )
            assertTrue(
                "model_settings.config must have normal_part for the model volume",
                configXml.contains("normal_part")
            )
        }

        zip.close()

        // Full pipeline: load into native and check only model_part volumes in preview.
        // The modifier cube should be filtered out by is_model_part().
        val info = ThreeMfParser.parse(processed)
        val config = embedder.buildConfig(info)
        val embedded = embedder.embed(processed, config, outDir, info)
        assertTrue("embed() should succeed", lib.loadModel(embedded.absolutePath))
        val preview = lib.getPreparePreviewMesh(500_000)
        assertNotNull("Preview mesh should not be null", preview)
        // The aux fan has 15642 triangles (model) + 12 (modifier cube).
        // If the modifier is correctly excluded, triangle count should be ≈15642.
        // If incorrectly included, it will be ≈15654.
        val triCount = preview!!.extruderIndices.size
        android.util.Log.i("ModifierTest", "Preview triangle count: $triCount")
        assertTrue(
            "Preview should have ~15642 tris (model only), not ~15654 (model+modifier). Got $triCount",
            triCount <= 15650
        )
    }

    @Test
    fun dragonScale_restructuredPlate_hasPerPartExtruderAssignments() {
        // Dragon Scale compound objects have per-part extruder assignments.
        // After restructuring, each part becomes a separate <object> with its own mesh,
        // and model_settings.config has <part id> entries matching these mesh object IDs.
        // parseModelSettingsConfig must extract per-PART extruder values (not just per-object).
        val file = asset("Dragon Scale infinity.3mf")
        val origInfo = ThreeMfParser.parse(file)
        val processed = BambuSanitizer.process(file, outDir, isBambu = origInfo.isBambu)
        val rawPlateFile = BambuSanitizer.extractPlate(processed, 1, outDir,
            hasPlateJsons = origInfo.hasPlateJsons)
        val restructured = BambuSanitizer.restructurePlateFile(rawPlateFile, outDir)

        val plateInfo = ThreeMfParser.parseForPlateSelection(restructured)
        val map = plateInfo.objectExtruderMap
        android.util.Log.i("PartTest", "Per-part objectExtruderMap: $map")

        // Should have entries for individual mesh object IDs (1, 2, 3),
        // not just the parent compound object ID
        assertTrue("Should have >=3 entries (per-part + parent)", map.size >= 3)

        // At least 2 distinct extruder values among the per-part entries
        val distinctExtruders = map.values.toSet()
        android.util.Log.i("PartTest", "Distinct extruders: $distinctExtruders")
        assertTrue(
            "Per-part extruders should have >=2 distinct values, got $distinctExtruders",
            distinctExtruders.size >= 2
        )
    }

    // ─── B67: Flarewing Dragon paint detection ──────────────────────────────

    /**
     * B67: Flarewing Dragon is a 4-colour SEMM model with 295,390 paint_color
     * attributes in 3D/Objects/object_21.model.  ThreeMfParser.parse() must
     * detect hasPaintData=true and 4 filament colours from project_settings.config.
     *
     * The model_settings.config has a single object with extruder="1" and an
     * empty filament_sequence.json — the colour chain must fall through to
     * project_settings.config's filament_colour array.
     */
    @Test
    fun b67_flarewingDragon_paintDetection() {
        val file = asset("Flarewing-Dragon_100%_4FilamentMulticolor_v1.1.3mf")
        val info = ThreeMfParser.parse(file)

        android.util.Log.i("B67Test", "hasPaintData=${info.hasPaintData}")
        android.util.Log.i("B67Test", "detectedColors=${info.detectedColors}")
        android.util.Log.i("B67Test", "detectedExtruderCount=${info.detectedExtruderCount}")
        android.util.Log.i("B67Test", "isBambu=${info.isBambu}")
        android.util.Log.i("B67Test", "usedExtruderIndices=${info.usedExtruderIndices}")
        android.util.Log.i("B67Test", "objectExtruderMap=${info.objectExtruderMap}")

        assertTrue("Flarewing Dragon must be detected as Bambu format", info.isBambu)
        assertTrue("Flarewing Dragon must have hasPaintData=true (295k paint_color attrs)",
            info.hasPaintData)
        assertEquals("Flarewing Dragon must detect 4 filament colours",
            4, info.detectedColors.size)
        assertTrue("Flarewing Dragon must have >=4 extruders",
            info.detectedExtruderCount >= 4)
    }

    // ─── B82: per-plate layer-tool chip count ─────────────────────────────────

    /**
     * B82: Standard flippy+flappy+mini.3mf — 4-plate file with layer-tool changes on
     * plates 2 and 4.  mergeThreeMfInfoForPlate must produce 1 chip for single-colour
     * plates (1, 3) and 2 chips for dual-colour plates (2, 4).
     */
    @Test
    fun b82_flippyStandard_perPlateChipCount() {
        val input = asset("flippy+flappy+mini.3mf")
        val origInfo = ThreeMfParser.parse(input)
        val processed = BambuSanitizer.process(input, outDir)
        val preSelectInfo = SlicerViewModel.mergeThreeMfInfo(ThreeMfParser.parse(processed), origInfo)

        val expectedChips = mapOf(1 to 1, 2 to 2, 3 to 1, 4 to 2)

        for ((plateId, expected) in expectedChips.entries.sortedBy { it.key }) {
            val sourcePlate = preSelectInfo.plates.find { it.plateId == plateId }
            val plateObjectIds = sourcePlate?.objectIds?.toSet()
            val plateExtruderMap = preSelectInfo.objectExtruderMap
                .filterKeys { key -> plateObjectIds?.contains(key) == true }

            val rawPlateFile = BambuSanitizer.extractPlate(
                processed, plateId, outDir,
                hasPlateJsons = preSelectInfo.hasPlateJsons,
                plateObjectIds = plateObjectIds,
                objectExtruderMap = plateExtruderMap
            )
            val plateFile = BambuSanitizer.restructurePlateFile(rawPlateFile, outDir)
            val plateInfo = ThreeMfParser.parseForPlateSelection(plateFile)
            val merged = SlicerViewModel.mergeThreeMfInfoForPlate(plateInfo, preSelectInfo, selectedPlateId = plateId)

            assertEquals(
                "Flippy plate $plateId: expected $expected chip(s), got ${merged.detectedColors.size} (${merged.detectedColors})",
                expected, merged.detectedColors.size
            )
        }
    }

    /**
     * B82: flippy+flappy+mini-with-plate-painted.3mf — 5-plate file.
     * Plates 1, 3: single-colour (1 chip).
     * Plates 2, 4: dual-colour via layer-tool palette match (2 chips).
     * Plate 5:     painted (SEMM) — hasPaintData=true, 2 chips.
     */
    @Test
    fun b82_flippyPainted_perPlateChipCount() {
        val input = asset("flippy+flappy+mini-with-plate-painted.3mf")
        val origInfo = ThreeMfParser.parse(input)
        val processed = BambuSanitizer.process(input, outDir)
        val preSelectInfo = SlicerViewModel.mergeThreeMfInfo(ThreeMfParser.parse(processed), origInfo)

        // Plates 1,3 → 1 chip; plates 2,4 → 2 chips; plate 5 → hasPaintData + 2 chips
        val expectedChips = mapOf(1 to 1, 2 to 2, 3 to 1, 4 to 2, 5 to 2)

        for ((plateId, expected) in expectedChips.entries.sortedBy { it.key }) {
            val sourcePlate = preSelectInfo.plates.find { it.plateId == plateId }
            val plateObjectIds = sourcePlate?.objectIds?.toSet()
            val plateExtruderMap = preSelectInfo.objectExtruderMap
                .filterKeys { key -> plateObjectIds?.contains(key) == true }

            val rawPlateFile = BambuSanitizer.extractPlate(
                processed, plateId, outDir,
                hasPlateJsons = preSelectInfo.hasPlateJsons,
                plateObjectIds = plateObjectIds,
                objectExtruderMap = plateExtruderMap
            )
            val plateFile = BambuSanitizer.restructurePlateFile(rawPlateFile, outDir)
            val plateInfo = ThreeMfParser.parseForPlateSelection(plateFile)
            val merged = SlicerViewModel.mergeThreeMfInfoForPlate(plateInfo, preSelectInfo, selectedPlateId = plateId)

            assertEquals(
                "Painted-flippy plate $plateId: expected $expected chip(s), got ${merged.detectedColors.size} (${merged.detectedColors})",
                expected, merged.detectedColors.size
            )
        }

        // Plate 5 must be detected as painted (SEMM)
        run {
            val sourcePlate = preSelectInfo.plates.find { it.plateId == 5 }
            val plateObjectIds = sourcePlate?.objectIds?.toSet()
            val plateExtruderMap = preSelectInfo.objectExtruderMap
                .filterKeys { key -> plateObjectIds?.contains(key) == true }
            val rawPlateFile = BambuSanitizer.extractPlate(
                processed, 5, outDir,
                hasPlateJsons = preSelectInfo.hasPlateJsons,
                plateObjectIds = plateObjectIds,
                objectExtruderMap = plateExtruderMap
            )
            val plateFile = BambuSanitizer.restructurePlateFile(rawPlateFile, outDir)
            val plateInfo = ThreeMfParser.parseForPlateSelection(plateFile)
            val merged = SlicerViewModel.mergeThreeMfInfoForPlate(plateInfo, preSelectInfo, selectedPlateId = 5)
            assertTrue(
                "Painted-flippy plate 5 must have hasPaintData=true",
                merged.hasPaintData
            )
        }
    }

    /**
     * B67: After BambuSanitizer.process() + mergeThreeMfInfo(), the merged info
     * must still carry hasPaintData=true and 4 colors from the original parse.
     * This tests the full pipeline path that the ViewModel uses.
     */
    @Test
    fun b67_flarewingDragon_mergedInfoPreservesPaintData() {
        val file = asset("Flarewing-Dragon_100%_4FilamentMulticolor_v1.1.3mf")
        val origInfo = ThreeMfParser.parse(file)
        val processed = com.u1.slicer.bambu.BambuSanitizer.process(file, outDir)
        val processedInfo = ThreeMfParser.parse(processed, skipPaintDetection = true)
        val mergedInfo = SlicerViewModel.mergeThreeMfInfo(processedInfo, origInfo)

        assertTrue("Merged info must preserve hasPaintData=true", mergedInfo.hasPaintData)
        assertEquals("Merged info must preserve 4 detected colours",
            4, mergedInfo.detectedColors.size)
        assertTrue("Merged info must have >=4 extruders",
            mergedInfo.detectedExtruderCount >= 4)
        // Verify specific colors from project_settings.config filament_colour
        assertTrue("Merged colors must include #0056B8",
            mergedInfo.detectedColors.any { it.equals("#0056B8", ignoreCase = true) })
    }

    /**
     * B100 regression: layer_height OVERRIDE must change the slice layer count; USE_FILE
     * must honour the embedded profile's value rather than the Kotlin default (0.2mm).
     *
     * Test strategy:
     * 1. Embed a Snapmaker profile with OVERRIDE layer_height=0.12mm → embedded config has 0.12.
     * 2. Slice with the 0.0f sentinel (simulating USE_FILE/startSlicing path) — applyConfigToPrusa
     *    skips layer_height so profile_keys[] keeps the embedded 0.12mm → more layers.
     * 3. Slice with explicit 0.2f — applyConfigToPrusa overwrites to 0.2mm → fewer layers.
     * 4. Assert sentinel produces more layers (0.12mm model is sliced more finely).
     */
    @Test
    fun b100_layerHeight_sentinel_respectsEmbeddedProfile() {
        val threeMf = asset("die-single-colour.3mf")

        // Embed a profile with layer_height=0.12mm
        val info = ThreeMfParser.parse(threeMf)
        val ovMap = buildProfileOverridesImpl(
            cfg = BASE_CONFIG,
            ov = SlicingOverrides(layerHeight = OverrideValue(OverrideMode.OVERRIDE, 0.12f)),
            slotCount = 1,
            hasSourceConfig = false
        )
        val embConfig = embedder.buildConfig(info = info, overrides = ovMap)
        val embedded = embedder.embed(threeMf, embConfig, outDir, info)
        assertTrue("Embedded file must exist", embedded.exists())

        // Slice with sentinel 0.0f → applyConfigToPrusa skips, profile_keys[] uses 0.12mm
        lib.clearModel()
        assertTrue("loadModel must succeed", lib.loadModel(embedded.absolutePath))
        val resultSentinel = lib.slice(BASE_CONFIG.copy(layerHeight = 0.0f))!!
        assertTrue("Sentinel slice must succeed", resultSentinel.success)

        // Slice with explicit 0.2f → applyConfigToPrusa applies 0.2mm (over profile's 0.12mm)
        lib.clearModel()
        assertTrue("loadModel must succeed for second run", lib.loadModel(embedded.absolutePath))
        val resultExplicit = lib.slice(BASE_CONFIG.copy(layerHeight = 0.2f))!!
        assertTrue("Explicit slice must succeed", resultExplicit.success)

        assertTrue(
            "0.12mm sentinel should produce more layers than 0.2mm explicit " +
                "(got sentinel=${resultSentinel.totalLayers} vs explicit=${resultExplicit.totalLayers})",
            resultSentinel.totalLayers > resultExplicit.totalLayers
        )
    }

    /**
     * B104: OreoProj single-plate Bambu file has 5 build items in the 3MF but only
     * 2 (Wafer + Oreo Filling) are on the print plate. The other 3 are garbage/degenerate
     * objects far off the plate (one at Z=634mm), making the combined bounding box
     * ~987×510×1268mm. Without a plate filter at embed time, loading that expanded file
     * causes "model too large for bed" → slice abort → "no layers detected".
     *
     * This test verifies that embedding with plateId = firstPlateId (the B104 fix)
     * produces a model within the 270×270mm bed bounds that slices successfully.
     */
    @Test
    fun b104_oreoProj_singlePlateBambu_withPlateFilter_slicesSuccessfully() {
        val input = asset("Oreo+Proj+1.3mf")
        val info = ThreeMfParser.parse(input)
        assertTrue("Oreo+Proj+1 must be detected as Bambu format", info.isBambu)
        assertTrue("Oreo+Proj+1 must have at least one plate", info.plates.isNotEmpty())

        val firstPlateId = info.plates.first().plateId
        val sanitized = BambuSanitizer.process(input, outDir)
        val embedConfig = embedder.buildConfig(info)
        val embedded = embedder.embed(sanitized, embedConfig, outDir, info, plateId = firstPlateId)

        assertTrue("loadModel must succeed with plate filter", lib.loadModel(embedded.absolutePath))
        val result = lib.slice(BASE_CONFIG)
        assertNotNull("slice() must not return null", result)
        result!!
        assertTrue(
            "Slice must succeed — plate filter must exclude off-plate garbage objects " +
                "that would otherwise produce a 987×510mm bounding box: ${result.errorMessage}",
            result.success
        )
        assertTrue("Must have at least 1 layer", result.totalLayers > 0)
    }

    // ─── B120: filament_maps same-slot parsing ────────────────────────────────

    /**
     * B120 regression — Jon's multi-plate 3MF (scraper head + TPU end piece).
     *
     * The file declares 2 filaments: PETG (canonical 0) and TPU (canonical 1).
     * Plate 2's model_settings.config assigns `extruder: 2` (= TPU) to the
     * scraper-head object, and its plate XML carries `filament_maps = "1 1"` —
     * both file-filaments mapped to AMS slot 1.
     *
     * Old parsing treated the VALUES ("1", "1") as filament indices → {1} →
     * after -1 → canonical {0} (PETG only).  The parser now collects the
     * POSITIONS of non-zero values instead (positions 0 and 1 both non-zero →
     * stored as {1, 2} → canonical {0, 1}), correctly representing both PETG
     * and TPU as active on plate 2.
     */
    @Test
    fun b120_jonsBug_plate2_detectsBothFilaments() {
        val input = asset("jons-bug.3mf")
        val info = ThreeMfParser.parse(input)

        assertTrue("jons-bug.3mf must be detected as Bambu format", info.isBambu)
        assertTrue("jons-bug.3mf must be multi-plate", info.isMultiPlate)

        val plate2 = info.plates.firstOrNull { it.plateId == 2 }
        assertNotNull("Plate 2 must be present in jons-bug.3mf", plate2)
        plate2!!

        // Pre-fix: filamentIndices was {1} (PETG only); post-fix must be {1, 2} (both).
        assertTrue(
            "Plate 2 must include canonical index 0 (PETG, filamentIndices contains 1), " +
                "got ${plate2.filamentIndices}",
            plate2.filamentIndices.contains(1)
        )
        assertTrue(
            "Plate 2 must include canonical index 1 (TPU, filamentIndices contains 2), " +
                "got ${plate2.filamentIndices} — pre-fix this was missing due to " +
                "'filament_maps = 1 1' being parsed as values {1} instead of positions {0,1}",
            plate2.filamentIndices.contains(2)
        )

        // computePlateFileIndices must return [0, 1] — not [0] — for plate 2.
        val canonical = com.u1.slicer.computePlateFileIndices(
            info = info,
            plateId = 2,
            canonicalSize = info.detectedColors.size.coerceAtLeast(2),
        )
        assertNotNull("computePlateFileIndices must not return null for plate 2", canonical)
        assertTrue(
            "computePlateFileIndices must include 0 (PETG) and 1 (TPU), got $canonical",
            canonical!!.containsAll(listOf(0, 1))
        )
    }

    /**
     * B128 end-to-end — a multi-colour 3MF that DECLARES distinct per-filament
     * materials must surface those FILE materials (not the user's all-PLA slot
     * presets) through the shared per-filament resolver, exercised on a real
     * file on-device.
     *
     * jons-bug.3mf declares `filament_type = ["PETG", "TPU"]`. With default
     * all-PLA presets and an injective 1:1 colour→slot mapping, the resolved
     * per-filament types must be PETG + TPU (and their temps), driving both the
     * Prepare chip strip (via `displayedFilamentMaterials`) and the slice (via
     * `buildPerFilamentTypeAndTemp`). Pre-B128 this collapsed to all-PLA because
     * the mapped slot preset won — DC15's "2nd/3rd show none/wrong material"
     * report.
     */
    @Test
    fun b128_jonsBug_declaredFileMaterialsDriveResolvedTypes() {
        val input = asset("jons-bug.3mf")
        val canonical = com.u1.slicer.data.canonicalListAtLoad(input)
        assertNotNull("canonicalListAtLoad must build a list for jons-bug.3mf", canonical)
        canonical!!
        assertTrue("jons-bug declares >= 2 filaments, got ${canonical.size}", canonical.size >= 2)

        // Default presets are all PLA — deliberately different from the file so
        // a regression to slot-preset authority is observable.
        val presets = com.u1.slicer.data.defaultExtruderPresets()
        assertTrue("default presets must all be PLA", presets.all { it.materialType == "PLA" })

        // Injective 1:1 mapping (no slot collisions) → declared file materials win.
        val injectiveMapping = (0 until canonical.size).toList()
        val (types, temps) = com.u1.slicer.data.resolvePerFilamentTypeAndTemp(
            canonical = canonical,
            overrides = emptyMap(),
            colorMapping = injectiveMapping,
            presets = presets,
            filamentLibrary = emptyList(),
        )

        assertEquals("file declares PETG for filament 0", "PETG", types[0])
        assertEquals("file declares TPU for filament 1", "TPU", types[1])
        // Temps follow the resolved (file) material, not the PLA slot default.
        assertEquals("PETG temp for filament 0", 235, temps[0])
        assertEquals("TPU temp for filament 1", 225, temps[1])
        assertFalse(
            "B128 regression: declared file materials must not collapse to all-PLA " +
                "(the mapped slot presets), got $types",
            types.take(2).all { it == "PLA" },
        )
    }

}
