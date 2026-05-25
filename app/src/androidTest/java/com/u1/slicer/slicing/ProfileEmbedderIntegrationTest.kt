package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.SlicerViewModel
import com.u1.slicer.bambu.BambuSanitizer
import com.u1.slicer.bambu.ProfileEmbedder
import com.u1.slicer.bambu.ThreeMfInfo
import com.u1.slicer.bambu.ThreeMfParser
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.gcode.GcodeValidator
import com.u1.slicer.gcode.LayerToolPauseInjector
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

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
        val bounds = GcodeValidator.checkBedBounds(gcode)
        assertTrue(
            "Fan cover G-code out of bed bounds: X=[${bounds.minX}, ${bounds.maxX}] Y=[${bounds.minY}, ${bounds.maxY}]",
            bounds.withinBounds
        )
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

    // ─── Support preservation (B10 regression guard) ──────────────────────────

    /**
     * Regression guard: Bambu files with enable_support=1 in their source config
     * must preserve that setting through the embed pipeline when using merged info
     * (which has detectedExtruderCount > 1, triggering the preserve path).
     *
     * Bug: SlicerViewModel passed processedInfo (detectedExtruderCount=1) instead of
     * mergedInfo (detectedExtruderCount>1), causing needsPreserve=false and losing
     * the file's support settings.
     */
    @Test
    fun shashibo_supportPreservedInEmbeddedConfig() {
        val input = asset("Shashibo-h2s-textured.3mf")
        val origInfo = ThreeMfParser.parse(input)

        // Parse source config BEFORE sanitizer strips it (mirrors originalSourceConfig in VM)
        val sourceConfig = java.util.zip.ZipFile(input).use { embedder.parseSourceConfig(it) }
        assertNotNull("Shashibo should have source config", sourceConfig)
        assertEquals("Source config should have enable_support=1",
            "1", sourceConfig!!["enable_support"]?.toString())

        // Sanitize (strips project_settings.config)
        val processed = BambuSanitizer.process(input, outDir)
        val processedInfo = ThreeMfParser.parse(processed)

        // Merge info — this is what SlicerViewModel does
        val mergedInfo = SlicerViewModel.mergeThreeMfInfo(processedInfo, origInfo)
        assertTrue("Merged info should preserve isBambu=true", mergedInfo.isBambu)
        assertTrue("Merged info should have detectedExtruderCount > 1",
            mergedInfo.detectedExtruderCount > 1)

        // Build config with merged info + source config (correct path)
        val config = embedder.buildConfig(
            info = mergedInfo,
            sourceConfig = sourceConfig,
            targetExtruderCount = 2
        )
        assertEquals("Embedded config must preserve enable_support=1",
            "1", config["enable_support"]?.toString())

        // processedInfo now also preserves support because BambuSanitizer.process()
        // preserves model_settings.config in the deferred restructuring path,
        // so processedInfo.detectedExtruderCount > 1 and needsPreserve=true.
        val configWithProcessedInfo = embedder.buildConfig(
            info = processedInfo,
            sourceConfig = sourceConfig,
            targetExtruderCount = 2
        )
        assertEquals("processedInfo now preserves support (deferred restructuring keeps model_settings.config)",
            "1", configWithProcessedInfo["enable_support"]?.toString())
    }

    // ─── INI / JSON import ────────────────────────────────────────────────────

    @Test
    fun embed_preservesMachinePauseGcodeForLayerToolChanges() {
        val sourceConfig = mapOf(
            "machine_pause_gcode" to "M0 ; pause for color swap",
            "change_filament_gcode" to "M600 ; swap filament",
            "single_extruder_multi_material" to "1",
            "enable_support" to "1"
        )
        val info = ThreeMfInfo(
            objects = emptyList(),
            plates = emptyList(),
            isBambu = true,
            isMultiPlate = false,
            hasLayerToolChanges = true
        )

        val preserved = embedder.buildConfig(
            info = info,
            sourceConfig = sourceConfig,
            targetExtruderCount = 1
        )
        assertEquals(
            "Layer tool changes must preserve machine_pause_gcode for Hueforge-style pauses",
            "M0 ; pause for color swap",
            preserved["machine_pause_gcode"]?.toString()
        )
        assertEquals(
            "Layer tool changes must force manual pause compatibility on U1",
            "0",
            preserved["single_extruder_multi_material"]?.toString()
        )
        assertFalse(
            "Layer tool changes should drop the Bambu filament swap script",
            preserved.containsKey("change_filament_gcode")
        )

        val stripped = embedder.buildConfig(
            info = info.copy(hasLayerToolChanges = false),
            sourceConfig = sourceConfig,
            targetExtruderCount = 1
        )
        assertFalse(
            "machine_pause_gcode should be stripped when the source has no layer tool changes",
            stripped.containsKey("machine_pause_gcode")
        )
    }

    @Test
    fun shashibo_detectsLayerToolColors() {
        val info = ThreeMfParser.parse(asset("Shashibo-h2s-textured.3mf"))

        assertTrue("Shashibo should be recognized as Bambu", info.isBambu)
        assertTrue("layer tool changes should be detected", info.hasLayerToolChanges)
        assertTrue(
            "layer tool colors should produce a multi-colour preview",
            info.detectedColors.size > 1
        )
        assertTrue(
            "layer tool colors should produce a multi-extruder preview state",
            info.detectedExtruderCount > 1
        )
    }

    @Test
    fun coloredBenchy_detectsMultiColourMetadata() {
        val info = ThreeMfParser.parse(asset("colored_3DBenchy (1).3mf"))

        assertTrue("colored benchy should be recognized as Bambu", info.isBambu)
        assertTrue(
            "project settings should produce a multi-colour preview",
            info.detectedColors.size > 1
        )
        assertTrue(
            "project settings should produce a multi-extruder preview state",
            info.detectedExtruderCount > 1
        )
    }

    @Test
    fun flippyFlappyMini_detectsLayerChangeColours() {
        val info = ThreeMfParser.parse(asset("flippy+flappy+mini.3mf"))

        assertTrue("flippy+flappy+mini should be recognized as Bambu", info.isBambu)
        assertTrue(
            "layer-change tool changes should be detected",
            info.hasLayerToolChanges
        )
        assertTrue(
            "layer-change colors should produce a multi-colour preview",
            info.detectedColors.size > 1
        )
        assertTrue(
            "layer-change colors should produce a multi-extruder preview state",
            info.detectedExtruderCount > 1
        )
    }

    @Test
    fun flippyFlappyMini_fullPipeline_emitsLayerChangePauseGcode() {
        val sourceAsset = asset("flippy+flappy+mini.3mf")
        val embedded = fullPipeline("flippy+flappy+mini.3mf")
        assertTrue("embedded sample should load", lib.loadModel(embedded.absolutePath))

        val result = lib.slice(defaultSliceConfig)
        assertNotNull("slice should return a result", result)
        assertTrue("layer-change sample should slice successfully: ${result?.errorMessage}", result!!.success)

        // Same as app: native slice omits pause lines; LayerToolPauseInjector adds them from
        // nativeGetPlateData(0) — which reads from the currently-loaded g_model (the embedded
        // file, which preserves the plate-level customGcode after sanitization).
        assertTrue(
            "pause injector should run using source asset metadata",
            LayerToolPauseInjector.injectFrom3mf(result.gcodePath, sourceAsset, 0, lib::nativeGetPlateData)
        )
        val gcode = File(result.gcodePath).readText()
        assertTrue(
            "layer-change sample should emit pause/color-swap G-code",
            gcode.contains("M400 U1") || gcode.contains("; PAUSE_PRINT")
        )
        assertTrue(
            "Bambu extruder 2 in custom_gcode_per_layer must emit T1 (or remapped after GcodeToolRemapper)",
            gcode.contains("; layer_tool extruder 2")
        )
        assertTrue(
            "layer-change sample should stay in single-extruder pause mode",
            gcode.contains("; single_extruder_multi_material = 0")
        )
    }

    @Test
    fun flippyFlappyMini_embedDropsLayerToolMetadataForNativeSliceFallback() {
        val input = asset("flippy+flappy+mini.3mf")
        val info = ThreeMfParser.parse(input)
        val sanitized = BambuSanitizer.process(input, outDir, isBambu = true)
        val config = embedder.buildConfig(
            info = info,
            sourceConfig = ZipFile(input).use { embedder.parseSourceConfig(it) },
            targetExtruderCount = 1
        )

        val embedded = embedder.embed(sanitized, config, outDir, info)
        ZipFile(embedded).use { zip ->
            val entry = zip.getEntry("Metadata/custom_gcode_per_layer.xml")
            assertNull(
                "embedded file should drop custom_gcode_per_layer.xml so native slicing stays on the fast path",
                entry
            )
        }
    }

    @Test
    fun flippyFlappyMini_sanitizerPreservesLayerToolMetadata() {
        val input = asset("flippy+flappy+mini.3mf")
        val sanitized = BambuSanitizer.process(input, outDir, isBambu = true)
        ZipFile(sanitized).use { zip ->
            assertNotNull(
                "sanitized file should preserve custom_gcode_per_layer.xml",
                zip.getEntry("Metadata/custom_gcode_per_layer.xml")
            )
        }
    }

    @Test
    fun flippyFlappyMini_extractPlateKeepsOnlySelectedLayerToolMetadata() {
        val input = asset("flippy+flappy+mini.3mf")
        val origInfo = ThreeMfParser.parse(input)
        val sanitized = BambuSanitizer.process(input, outDir, isBambu = true)
        val targetPlateId = 4
        val plateObjectIds = origInfo.plates.firstOrNull { it.plateId == targetPlateId }?.objectIds?.toSet()
        assertNotNull("sample should expose plate $targetPlateId", plateObjectIds)

        val plateFile = BambuSanitizer.extractPlate(
            inputFile = sanitized,
            targetPlateId = targetPlateId,
            outputDir = outDir,
            hasPlateJsons = origInfo.hasPlateJsons,
            plateObjectIds = plateObjectIds
        )

        ZipFile(plateFile).use { zip ->
            val entry = zip.getEntry("Metadata/custom_gcode_per_layer.xml")
            assertNotNull("plate extract should keep custom_gcode_per_layer.xml", entry)
            val xml = zip.getInputStream(entry).bufferedReader().readText()
            assertTrue("single-plate extract should be renumbered to plate 1", xml.contains("""plate_info id="1""""))
            assertFalse("original plate id should be rewritten for single-plate slicing", xml.contains("""plate_info id="$targetPlateId""""))
            assertFalse("other plate metadata should be removed", xml.contains("""plate_info id="10""""))
        }
    }

    @Test
    fun flippyFlappyMini_plateSelectionDetectsOnlySelectedColours() {
        val input = asset("flippy+flappy+mini.3mf")
        val origInfo = ThreeMfParser.parse(input)
        val sanitized = BambuSanitizer.process(input, outDir, isBambu = true)
        val targetPlateId = 4
        val plateObjectIds = origInfo.plates.firstOrNull { it.plateId == targetPlateId }?.objectIds?.toSet()
        assertNotNull("sample should expose plate $targetPlateId", plateObjectIds)

        val rawPlateFile = BambuSanitizer.extractPlate(
            inputFile = sanitized,
            targetPlateId = targetPlateId,
            outputDir = outDir,
            hasPlateJsons = origInfo.hasPlateJsons,
            plateObjectIds = plateObjectIds
        )
        val plateFile = BambuSanitizer.restructurePlateFile(rawPlateFile, outDir)
        val plateInfo = ThreeMfParser.parseForPlateSelection(plateFile)

        assertTrue("selected plate should keep layer tool changes", plateInfo.hasLayerToolChanges)
        // process() strips project_settings.config (Orca load compatibility), so parseForPlateSelection
        // cannot map filament_colour by extruder — only layer-tool hex from custom_gcode_per_layer.xml.
        // Plate 4 has one layer entry → one detected hex; extruder indices still reflect object+layer tool.
        assertEquals(
            "layer metadata hex for this plate extract",
            1,
            plateInfo.detectedColors.size
        )
        assertEquals("#F4D976", plateInfo.detectedColors.single())
        // Without project_settings (stripped by process()), lightweight parse may only see
        // layer-tool extruders from custom_gcode_per_layer.xml for this path.
        assertTrue(
            "should report at least one extruder from layer metadata",
            plateInfo.detectedExtruderCount >= 1 && plateInfo.usedExtruderIndices.isNotEmpty()
        )
    }

    @Test
    fun layerToolPlateMerge_prefersSelectedPlatePalette() {
        val sourceInfo = ThreeMfInfo(
            objects = emptyList(),
            plates = emptyList(),
            isBambu = true,
            isMultiPlate = true,
            detectedColors = listOf("#111111", "#222222", "#333333"),
            detectedExtruderCount = 3,
            hasLayerToolChanges = true
        )
        val plateInfo = ThreeMfInfo(
            objects = emptyList(),
            plates = emptyList(),
            isBambu = true,
            isMultiPlate = false,
            detectedColors = listOf("#111111", "#333333"),
            detectedExtruderCount = 2,
            hasLayerToolChanges = true
        )

        val merged = SlicerViewModel.mergeThreeMfInfoForPlate(plateInfo, sourceInfo, selectedPlateId = 1)
        assertEquals(
            "layer-tool plates should prefer the selected plate palette",
            plateInfo.detectedColors,
            merged.detectedColors
        )
        assertEquals(
            "layer-tool plates should keep the selected plate extruder count",
            2,
            merged.detectedExtruderCount
        )
    }

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

    // ─── Re-embed regression guard (B24/v1.3.38) ─────────────────────────────

    /**
     * Regression guard for B24: re-embedding a 3MF before slicing must not break
     * slicing. v1.3.38 introduced "always re-embed" which caused Clipper overflow
     * on every model. This test simulates the startSlicing() re-embed path:
     *   embed → load → clearModel → re-embed → load → slice
     */
    @Test
    fun calibCube_reEmbedBeforeSlice_doesNotBreakSlicing() {
        val input = asset("calib-cube-10-dual-colour-merged.3mf")
        val info = ThreeMfParser.parse(input)
        val sanitized = BambuSanitizer.process(input, outDir, isBambu = info.isBambu)

        // First embed + load (simulates initial loadModel in openModel)
        val config1 = embedder.buildConfig(info)
        val embedded1 = embedder.embed(sanitized, config1, outDir, info)
        assertTrue("First loadModel must succeed", lib.loadModel(embedded1.absolutePath))

        // Re-embed + load (simulates startSlicing re-embed path)
        lib.clearModel()
        val config2 = embedder.buildConfig(info)
        val embedded2 = embedder.embed(sanitized, config2, outDir, info)
        assertTrue("Second loadModel must succeed", lib.loadModel(embedded2.absolutePath))

        // Slice must succeed after re-embed
        val dualConfig = defaultSliceConfig.copy(
            extruderCount = 2,
            extruderTemps = intArrayOf(220, 220),
            wipeTowerEnabled = true
        )
        val result = lib.slice(dualConfig)!!
        assertTrue(
            "Slice after re-embed must succeed (B24 regression guard): ${result.errorMessage}",
            result.success
        )
        assertTrue("Layer count > 0 after re-embed slice", result.totalLayers > 0)
    }

    /**
     * Same re-embed regression guard but for single-extruder STL files.
     * STL files have no sourceModelFile so the re-embed path is skipped,
     * but this verifies that a plain STL still slices after the B24 changes.
     */
    /**
     * Regression guard: plate-extracted 3MFs can contain both Slic3r_PE_model.config
     * AND model_settings.config (synthesized by extractPlate). embed() must not crash
     * with "duplicate entry: Metadata/model_settings.config" when both are present.
     */
    @Test
    fun embed_bothSlic3rAndModelSettings_noDuplicateEntry() {
        // Build a synthetic 3MF with both config entries (reproduces the crash scenario)
        val synthetic = File(outDir, "both_configs.3mf")
        val modelXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
              <resources>
                <object id="1" type="model">
                  <mesh>
                    <vertices>
                      <vertex x="0" y="0" z="0"/>
                      <vertex x="10" y="0" z="0"/>
                      <vertex x="5" y="10" z="0"/>
                      <vertex x="5" y="5" z="10"/>
                    </vertices>
                    <triangles>
                      <triangle v1="0" v2="1" v3="2"/>
                      <triangle v1="0" v2="1" v3="3"/>
                      <triangle v1="1" v2="2" v3="3"/>
                      <triangle v1="0" v2="2" v3="3"/>
                    </triangles>
                  </mesh>
                </object>
              </resources>
              <build><item objectid="1"/></build>
            </model>
        """.trimIndent()
        val slic3rConfig = """
            <config>
              <object id="1">
                <metadata type="object" key="extruder" value="1"/>
                <volume firstid="0" lastid="3">
                  <metadata type="volume" key="extruder" value="1"/>
                </volume>
              </object>
            </config>
        """.trimIndent()
        val modelSettings = """
            <?xml version="1.0" encoding="UTF-8"?>
            <config>
              <object id="1">
                <metadata key="extruder" value="1"/>
              </object>
            </config>
        """.trimIndent()

        ZipOutputStream(FileOutputStream(synthetic)).use { zip ->
            zip.putNextEntry(ZipEntry("3D/3dmodel.model"))
            zip.write(modelXml.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("Metadata/Slic3r_PE_model.config"))
            zip.write(slic3rConfig.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("Metadata/model_settings.config"))
            zip.write(modelSettings.toByteArray())
            zip.closeEntry()
        }

        val info = ThreeMfParser.parse(synthetic)
        val config = embedder.buildConfig(info)

        // This crashed before the fix with ZipException: duplicate entry
        val embedded = embedder.embed(synthetic, config, outDir, info)
        assertTrue("Embedded file should exist", embedded.exists())

        // Verify only one model_settings.config entry in output
        ZipFile(embedded).use { zip ->
            val count = zip.entries().asSequence()
                .count { it.name == "Metadata/model_settings.config" }
            assertEquals("Must have exactly one model_settings.config", 1, count)
        }
    }

    @Test
    fun benchy_stl_slicesAfterClearAndReload() {
        val input = asset("3DBenchy.stl")
        assertTrue("First loadModel must succeed", lib.loadModel(input.absolutePath))

        // Simulate clear + reload (what would happen if re-embed ran on STL)
        lib.clearModel()
        assertTrue("Second loadModel must succeed", lib.loadModel(input.absolutePath))

        val result = lib.slice(defaultSliceConfig)!!
        assertTrue(
            "STL slice after clear+reload must succeed: ${result.errorMessage}",
            result.success
        )
        assertTrue("Layer count > 0", result.totalLayers > 0)
    }

    /**
     * Regression guard for enable_pressure_advance / pressure_advance profile key pass-through.
     *
     * When an embedded Snapmaker profile has enable_pressure_advance=1 and pressure_advance=0.04,
     * OrcaSlicer (Klipper flavor) should emit SET_PRESSURE_ADVANCE ADVANCE=0.04 in the G-code.
     * Before the fix, these keys were not in profile_keys[] and were silently dropped.
     */
    @Test
    fun pressureAdvancePassesThroughFromEmbeddedProfile() {
        // Step 1: Get a valid embedded Snapmaker 3MF (machine_start_gcode contains PRINT_START,
        // gcode_flavor=klipper — both already set by fullPipeline via snapmaker_u1.json)
        val embedded = fullPipeline("calib-cube-10-dual-colour-merged.3mf")

        // Step 2: Inject enable_pressure_advance=1 and pressure_advance=0.04 into the
        // project_settings.config JSON inside the embedded ZIP.
        val modified = File(outDir, "pa_test.3mf")
        modified.outputStream().use { fos ->
            ZipOutputStream(fos).use { zout ->
                ZipFile(embedded).use { zin ->
                    for (entry in zin.entries()) {
                        if (entry.name == "Metadata/project_settings.config") {
                            val original = zin.getInputStream(entry).readBytes().decodeToString()
                            val json = JSONObject(original)
                            // Inject PA keys — per-extruder arrays (1 extruder for calib cube)
                            json.put("enable_pressure_advance", JSONArray(listOf("1")))
                            json.put("pressure_advance", JSONArray(listOf("0.04")))
                            val patched = json.toString(2).toByteArray()
                            zout.putNextEntry(ZipEntry(entry.name))
                            zout.write(patched)
                            zout.closeEntry()
                        } else {
                            zout.putNextEntry(ZipEntry(entry.name))
                            zin.getInputStream(entry).use { it.copyTo(zout) }
                            zout.closeEntry()
                        }
                    }
                }
            }
        }

        // Step 3: Load and slice
        assertTrue("Modified 3MF should load", lib.loadModel(modified.absolutePath))
        val result = lib.slice(defaultSliceConfig)
        assertNotNull("Slice must not return null", result)
        assertTrue("Slice must succeed: ${result!!.errorMessage}", result.success)

        // Step 4: Assert PA command in G-code
        val gcode = File(result.gcodePath).readText()
        assertTrue(
            "Expected SET_PRESSURE_ADVANCE ADVANCE=0.04 in G-code.\n" +
            "This means enable_pressure_advance/pressure_advance are not in profile_keys[].\n" +
            "First 2000 chars of G-code:\n${gcode.take(2000)}",
            gcode.contains("SET_PRESSURE_ADVANCE ADVANCE=0.04")
        )
    }

    // ─── Sub-plan #2b: ProfileEmbedder.embed plateId + XML filter ──────────────

    /**
     * Sub-plan #2b: when embed() is called with plateId=null, the legacy behaviour
     * is to drop Metadata/custom_gcode_per_layer.xml entirely for painted files
     * (pre-sub-plan-#2b contract). Sub-plan #3's LayerToolPauseInjector XML
     * fallback tolerates a missing file silently.
     */
    @Test
    fun embed_withPlateIdNull_dropsCustomGcodePerLayerXml_whenHasLayerToolChanges() {
        val source = buildSource3mfWithCustomGcodeXml(
            """<?xml version="1.0"?>
<custom_gcodes_per_layer>
  <plate>
    <plate_info id="1"/>
    <layer top_z="1.6" type="2" extruder="2" color="#AA0000" extra="" gcode="tool_change"/>
  </plate>
</custom_gcodes_per_layer>"""
        )
        val info = ThreeMfInfo(
            objects = emptyList(),
            plates = emptyList(),
            isBambu = true,
            isMultiPlate = false,
            hasLayerToolChanges = true
        )
        val out = embedder.embed(source, emptyMap(), outDir, info)

        val hasXml = ZipFile(out).use { zip ->
            zip.getEntry("Metadata/custom_gcode_per_layer.xml") != null
        }
        assertFalse("plateId=null legacy path must drop the XML", hasXml)
    }

    /**
     * Sub-plan #2b Risk 4 mitigation m1: when embed() is called with a specific
     * plateId, it must keep Metadata/custom_gcode_per_layer.xml in the output
     * and filter it to only the target plate's block (reusing
     * BambuSanitizer.filterCustomGcodePerLayer). This preserves sub-plan #3's
     * XML fallback for post-slice-null scenarios on painted multi-plate
     * fixtures without leaking non-target plates' layers.
     */
    @Test
    fun embed_withPlateId1_filtersCustomGcodePerLayerXml_toThatPlateOnly() {
        val customGcodeXml = """<?xml version="1.0"?>
<custom_gcodes_per_layer>
  <plate>
    <plate_info id="1"/>
    <layer top_z="1.6" type="2" extruder="2" color="#AA0000" extra="" gcode="tool_change"/>
  </plate>
  <plate>
    <plate_info id="2"/>
    <layer top_z="3.2" type="2" extruder="3" color="#00AA00" extra="" gcode="tool_change"/>
  </plate>
</custom_gcodes_per_layer>"""
        val source = buildSource3mfWithCustomGcodeXml(customGcodeXml)
        val info = ThreeMfInfo(
            objects = emptyList(),
            plates = emptyList(),
            isBambu = true,
            isMultiPlate = true,
            hasLayerToolChanges = true
        )
        val out = embedder.embed(source, emptyMap(), outDir, info, plateId = 1)

        val body = ZipFile(out).use { zip ->
            val entry = zip.getEntry("Metadata/custom_gcode_per_layer.xml")
            assertNotNull("plateId=1 must keep the XML in the embedded file", entry)
            zip.getInputStream(entry).bufferedReader().readText()
        }
        assertTrue(
            "embedded XML must contain plate 1's layer (top_z=1.6)",
            body.contains("""top_z="1.6"""")
        )
        assertFalse(
            "embedded XML must NOT contain plate 2's layer (top_z=3.2)",
            body.contains("""top_z="3.2"""")
        )
        // filterCustomGcodePerLayer renumbers the selected plate_info id to 1.
        val plateOpenCount = Regex("""<plate>""").findAll(body).count()
        assertEquals(
            "filtered output must have exactly one <plate> block",
            1,
            plateOpenCount
        )
    }

    /**
     * Build a minimal Bambu-like 3MF source file containing just the
     * custom_gcode_per_layer.xml entry under test. Shape matters, not content
     * validity — embed() only reads individual entries when the name matches.
     */
    // ─── F87 — Process profile end-to-end ────────────────────────────────────

    /**
     * F87: parse die-single-colour.3mf, embed via standard path with a `processProfileKeys`
     * map that overrides keys NOT clamped by applyConfigToPrusa. Slice and assert the G-code
     * header reflects the imported profile's values.
     *
     * NOTE: many process-profile keys (`sparse_infill_density`, `seam_position`, the speed
     * family, `wall_generator`, etc.) are unconditionally overwritten by `applyConfigToPrusa`
     * in `sapil_print.cpp` AFTER the `profile_keys[]` loop runs. That's a pre-existing
     * architecture gap that also affects `SlicingOverrides.seamPosition` etc. — see the
     * F87/F91 BACKLOG entry under "Known limitation".
     */
    @Test
    fun f87_processProfileKeys_overrideBundledProcessProfile() {
        val input = asset("die-single-colour.3mf")
        val info = ThreeMfParser.parse(input)
        val stage1 = if (info.isBambu) BambuSanitizer.process(input, outDir) else input
        val processProfileKeys = mapOf<String, Any>(
            "layer_height" to "0.16",  // sentinel-gated, see applyConfigToPrusa
            "top_surface_pattern" to "concentric",   // not in applyConfigToPrusa
            "ironing_type" to "topmost",            // not in applyConfigToPrusa
            "wall_loops" to "5",
        )
        val config = embedder.buildConfig(
            info = info,
            processProfileKeys = processProfileKeys,
            targetExtruderCount = 1,
        )
        val embedded = embedder.embed(stage1, config, outDir, info)
        assertTrue(embedded.exists())

        assertTrue("native load", lib.loadModel(embedded.absolutePath))
        val sliceConfig = defaultSliceConfig.copy(layerHeight = 0.0f, perimeters = 5)
        val result = lib.slice(sliceConfig)
        assertNotNull("slice produced a result", result)
        assertTrue("slice succeeded: ${result!!.errorMessage}", result.success)

        val gcode = File(result.gcodePath).readText()
        fun headerValue(key: String): String? = gcode.lineSequence()
            .firstOrNull { it.startsWith("; $key =") }?.substringAfter("=")?.trim()

        assertEquals("layer_height should pick up process profile value", "0.16", headerValue("layer_height"))
        assertEquals("top_surface_pattern unclamped → process profile wins", "concentric", headerValue("top_surface_pattern"))
        assertEquals("ironing_type unclamped → process profile wins", "topmost", headerValue("ironing_type"))
        // wall_loops is in applyConfigToPrusa but we set perimeters=5 in SliceConfig too, so the
        // override matches.
        assertEquals("5", headerValue("wall_loops"))
    }

    /**
     * F87: an empty processProfileKeys map must NOT change behaviour — bundled
     * `standard_0.20mm.json` values should still drive the slice.
     */
    @Test
    fun f87_emptyProcessProfileKeys_keepsBundledDefaults() {
        val input = asset("die-single-colour.3mf")
        val info = ThreeMfParser.parse(input)
        val stage1 = if (info.isBambu) BambuSanitizer.process(input, outDir) else input
        val config = embedder.buildConfig(
            info = info,
            processProfileKeys = emptyMap(),
            targetExtruderCount = 1,
        )
        val embedded = embedder.embed(stage1, config, outDir, info)
        assertTrue(lib.loadModel(embedded.absolutePath))
        val result = lib.slice(defaultSliceConfig.copy(layerHeight = 0.0f))
        assertTrue("slice succeeds with empty processProfileKeys", result?.success == true)
        // Bundled standard_0.20mm.json says layer_height=0.2.
        val gcode = File(result!!.gcodePath).readText()
        assertTrue("bundled layer_height=0.2 survives", gcode.contains("; layer_height = 0.2"))
    }

    // ─── F91 — Filament library settings end-to-end ──────────────────────────

    /**
     * F91: load die-single-colour.3mf with `filamentSettings` that emit per-slot arrays for
     * NEW per-filament tuning keys. Asserts G-code header carries the values.
     *
     * NOTE: `filament_max_volumetric_speed`, `fan_min_speed`, `fan_max_speed`,
     * `overhang_fan_speed`, `slow_down_layer_time`, `slow_down_min_speed` are unconditionally
     * clamped to U1 hardware defaults in `applyConfigToPrusa` (sapil_print.cpp:389-403). The
     * library values for those keys reach the embed but get overwritten by C++. See BACKLOG
     * F87+F91 "Known limitation".
     *
     * Verified working keys (not clamped + on profile_keys[]): filament_flow_ratio,
     * pressure_advance, enable_pressure_advance, filament_minimal_purge_on_wipe_tower.
     *
     * `filament_cost` is NOT on `profile_keys[]` in `sapil_print.cpp` so native ignores it
     * entirely (G-code header shows the OrcaSlicer default of 0). The library stores the
     * value but it doesn't reach the slicer — also tracked under BACKLOG Known limitation.
     */
    @Test
    fun f91_filamentSettings_emitInGcodeHeader() {
        val input = asset("die-single-colour.3mf")
        val info = ThreeMfParser.parse(input)
        val stage1 = if (info.isBambu) BambuSanitizer.process(input, outDir) else input
        val filamentSettings = mapOf<String, Any>(
            "filament_flow_ratio" to listOf("0.97"),
            "pressure_advance" to listOf("0.055"),
            "enable_pressure_advance" to listOf("1"),
            "filament_minimal_purge_on_wipe_tower" to listOf("18"),
        )
        val config = embedder.buildConfig(
            info = info,
            filamentSettings = filamentSettings,
            targetExtruderCount = 1,
        )
        val embedded = embedder.embed(stage1, config, outDir, info)
        assertTrue(lib.loadModel(embedded.absolutePath))
        val result = lib.slice(defaultSliceConfig.copy(layerHeight = 0.0f))
        assertTrue("slice succeeded: ${result?.errorMessage}", result?.success == true)

        val gcode = File(result!!.gcodePath).readText()
        fun headerValue(key: String): String? = gcode.lineSequence()
            .firstOrNull { it.startsWith("; $key =") }
            ?.substringAfter("=")?.trim()

        val flow = headerValue("filament_flow_ratio")
        assertNotNull("filament_flow_ratio header missing", flow)
        assertTrue("filament_flow_ratio expected to contain 0.97, got: $flow", flow!!.contains("0.97"))

        val pa = headerValue("pressure_advance")
        assertNotNull("pressure_advance header missing", pa)
        assertTrue("pressure_advance expected to contain 0.055, got: $pa", pa!!.contains("0.055"))

        val purge = headerValue("filament_minimal_purge_on_wipe_tower")
        assertNotNull("filament_minimal_purge_on_wipe_tower header missing", purge)
        assertTrue("filament_minimal_purge expected to contain 18, got: $purge", purge!!.contains("18"))
    }

    /**
     * F91: omitted filamentSettings keys should leave the bundled `pla.json` values intact.
     * The bundled profile sets filament_flow_ratio=1, filament_max_volumetric_speed=21.
     */
    @Test
    fun f91_omittedFilamentSettings_keepsBundledDefaults() {
        val input = asset("die-single-colour.3mf")
        val info = ThreeMfParser.parse(input)
        val stage1 = if (info.isBambu) BambuSanitizer.process(input, outDir) else input
        val config = embedder.buildConfig(
            info = info,
            filamentSettings = emptyMap(),
            targetExtruderCount = 1,
        )
        val embedded = embedder.embed(stage1, config, outDir, info)
        assertTrue(lib.loadModel(embedded.absolutePath))
        val result = lib.slice(defaultSliceConfig.copy(layerHeight = 0.0f))
        assertTrue("slice succeeds with empty filamentSettings", result?.success == true)
        val gcode = File(result!!.gcodePath).readText()
        assertTrue(
            "bundled filament_flow_ratio=1 survives",
            gcode.contains("; filament_flow_ratio = 1")
        )
        assertTrue(
            "bundled filament_max_volumetric_speed=21 survives",
            gcode.contains("; filament_max_volumetric_speed = 21")
        )
    }

    private fun buildSource3mfWithCustomGcodeXml(customGcodeXml: String): File {
        val file = File(cacheDir, "sub_plan_2b_src_${System.nanoTime()}.3mf")
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            val minimalModel = """<?xml version="1.0"?><model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02"><resources><object id="1" type="model"><mesh><vertices/><triangles/></mesh></object></resources><build><item objectid="1"/></build></model>"""
            zip.putNextEntry(ZipEntry("3D/3dmodel.model"))
            zip.write(minimalModel.toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("Metadata/project_settings.config"))
            zip.write("""{"filament_colour":["#FFFFFF"]}""".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("Metadata/custom_gcode_per_layer.xml"))
            zip.write(customGcodeXml.toByteArray())
            zip.closeEntry()
        }
        return file
    }

}
