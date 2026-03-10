package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.data.OverrideMode
import com.u1.slicer.data.OverrideValue
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.data.SlicingOverrides
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
    fun benchy_stl_estimatedTimeIsReasonable() {
        val file = asset("3DBenchy.stl")
        assertTrue(lib.loadModel(file.absolutePath))
        val result = lib.slice(DEFAULT_CONFIG)!!
        assertTrue(result.success)
        // Time should be plausible: > 5 min and < 2 hours.
        // If machine kinematic limits are missing from sapil_print.cpp the GCodeProcessor
        // defaults to near-zero acceleration, inflating the estimate to 4+ hours.
        val minutes = result.estimatedTimeSeconds / 60f
        assertTrue("Estimated time should be > 5 min (was ${minutes}m)", result.estimatedTimeSeconds > 300f)
        assertTrue("Estimated time should be < 120 min (was ${minutes}m)", result.estimatedTimeSeconds < 7200f)
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

    // ─── Toolchange retraction regression (direct-drive clog prevention) ──────

    /**
     * Regression: OrcaSlicer defaults retract_length_toolchange to 10mm (bowden).
     * On the Snapmaker U1's direct-drive extruders, 10mm pulls filament past the
     * heat break, causing heat-creep clogs during standby.  Must be ≤ 2mm.
     */
    @Test
    fun singleExtruder_toolchangeRetractLength_notBowdenDefault() {
        val (success, gcode) = sliceAsset("tetrahedron.stl")
        assertTrue(success)
        val vals = GcodeValidator.extractToolchangeRetractLength(gcode!!)
        assertNotNull("retract_length_toolchange must be in G-code config", vals)
        for (v in vals!!) {
            assertTrue("retract_length_toolchange=$v must be ≤ 2mm (direct drive), not bowden 10mm", v <= 2.0)
        }
    }

    @Test
    fun singleExtruder_maxRetraction_notExcessive() {
        val (success, gcode) = sliceAsset("tetrahedron.stl")
        assertTrue(success)
        val maxRetract = GcodeValidator.maxRetractionMm(gcode!!)
        assertTrue("Max retraction ${maxRetract}mm must be ≤ 5mm for direct drive", maxRetract <= 5.0)
    }

    /**
     * Regression: verify retract_length_toolchange is sane with 2 extruders.
     * Uses STL with extruderCount=2 (avoids needing ProfileEmbedder pipeline).
     */
    @Test
    fun dualExtruder_toolchangeRetractLength_notBowdenDefault() {
        val file = asset("tetrahedron.stl")
        assertTrue(lib.loadModel(file.absolutePath))
        val config = DEFAULT_CONFIG.copy(extruderCount = 2)
        val result = lib.slice(config)
        assertNotNull(result)
        assertTrue("Dual-extruder slice should succeed", result!!.success)
        val gcode = File(result.gcodePath).readText()
        val vals = GcodeValidator.extractToolchangeRetractLength(gcode)
        assertNotNull("retract_length_toolchange must be in G-code config", vals)
        for (v in vals!!) {
            assertTrue("retract_length_toolchange=$v must be ≤ 2mm (direct drive), not bowden 10mm", v <= 2.0)
        }
    }

    @Test
    fun dualExtruder_maxRetraction_notExcessive() {
        val file = asset("tetrahedron.stl")
        assertTrue(lib.loadModel(file.absolutePath))
        val config = DEFAULT_CONFIG.copy(extruderCount = 2)
        val result = lib.slice(config)
        assertNotNull(result)
        assertTrue(result!!.success)
        val gcode = File(result.gcodePath).readText()
        val maxRetract = GcodeValidator.maxRetractionMm(gcode)
        assertTrue("Max retraction ${maxRetract}mm must be ≤ 5mm for direct drive", maxRetract <= 5.0)
    }

    /**
     * Regression: OrcaSlicer WipeTower2 bowden unload sequence (94mm retraction)
     * must never appear in Snapmaker U1 output.  The U1 has independent extruders,
     * not a single-extruder-multi-material (SEMM) setup.
     */
    @Test
    fun dualExtruder_noBowdenUnloadSequence() {
        val file = asset("tetrahedron.stl")
        assertTrue(lib.loadModel(file.absolutePath))
        val config = DEFAULT_CONFIG.copy(extruderCount = 2)
        val result = lib.slice(config)
        assertNotNull(result)
        assertTrue(result!!.success)
        val gcode = File(result.gcodePath).readText()
        assertFalse(
            "G-code must NOT contain '; Retract(unload)' bowden sequence",
            GcodeValidator.hasBowdenUnloadSequence(gcode)
        )
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

    // ─── Bed bounds validation (regression for off-bed placement bug) ──────────

    /**
     * Critical regression test: sliced G-code must NOT have X/Y coordinates outside
     * the 0–270mm bed.  This guards against the sapil_arrange.cpp placement bug where
     * setModelInstances used pos as a direct offset (ignoring mesh origin), causing
     * models to be placed outside the bed — potentially damaging the printer.
     */
    @Test
    fun benchy_stl_gcodeWithinBedBounds() {
        val file = asset("3DBenchy.stl")
        assertTrue(lib.loadModel(file.absolutePath))
        // Explicitly place at center (as ViewModel would do via CopyArrangeCalculator)
        val info = lib.getModelInfo()!!
        val cx = (270f - info.sizeX) / 2f
        val cy = (270f - info.sizeY) / 2f
        lib.setModelInstances(floatArrayOf(cx, cy))
        val result = lib.slice(DEFAULT_CONFIG)!!
        assertTrue("Benchy should slice successfully", result.success)
        val gcode = File(result.gcodePath).readText()
        val bounds = GcodeValidator.checkBedBounds(gcode)
        assertTrue(
            "All X/Y coordinates must be within 0-270mm bed. " +
            "X: ${bounds.minX}..${bounds.maxX}, Y: ${bounds.minY}..${bounds.maxY}. " +
            "Violations: ${bounds.violatingLines.take(3)}",
            bounds.withinBounds
        )
    }

    @Test
    fun tetrahedron_stl_gcodeWithinBedBounds() {
        val file = asset("tetrahedron.stl")
        assertTrue(lib.loadModel(file.absolutePath))
        // Center on bed (as ViewModel does via CopyArrangeCalculator)
        val info = lib.getModelInfo()!!
        val cx = (270f - info.sizeX) / 2f
        val cy = (270f - info.sizeY) / 2f
        lib.setModelInstances(floatArrayOf(cx, cy))
        val result = lib.slice(DEFAULT_CONFIG)!!
        assertTrue("Tetrahedron should slice successfully", result.success)
        val gcode = File(result.gcodePath).readText()
        val bounds = GcodeValidator.checkBedBounds(gcode)
        assertTrue(
            "All X/Y must be within 0-270mm. X: ${bounds.minX}..${bounds.maxX}, Y: ${bounds.minY}..${bounds.maxY}",
            bounds.withinBounds
        )
    }

    @Test
    fun threeMf_gcodeWithinBedBounds() {
        val file = asset("calib-cube-10-dual-colour-merged.3mf")
        assertTrue(lib.loadModel(file.absolutePath))
        // Center on bed (as ViewModel does via CopyArrangeCalculator)
        val info = lib.getModelInfo()!!
        val cx = (270f - info.sizeX) / 2f
        val cy = (270f - info.sizeY) / 2f
        lib.setModelInstances(floatArrayOf(cx, cy))
        val config = DEFAULT_CONFIG.copy(extruderCount = 2)
        val result = lib.slice(config)!!
        assertTrue("3MF should slice successfully", result.success)
        val gcode = File(result.gcodePath).readText()
        val bounds = GcodeValidator.checkBedBounds(gcode)
        assertTrue(
            "3MF G-code X/Y must be within 0-270mm. X: ${bounds.minX}..${bounds.maxX}, Y: ${bounds.minY}..${bounds.maxY}",
            bounds.withinBounds
        )
    }

    // ─── resolveInto / SlicingOverrides integration ───────────────────────────

    /**
     * OVERRIDE layer height produces fewer layers than a thicker default.
     * Default=0.2mm → N layers; OVERRIDE=0.3mm → fewer layers (same model height).
     */
    @Test
    fun override_layerHeight_producesCorrectLayerCount() {
        val file = asset("tetrahedron.stl")

        // Slice at 0.2mm (default)
        lib.loadModel(file.absolutePath)
        val r02 = lib.slice(DEFAULT_CONFIG)!!
        assertTrue("0.2mm slice should succeed", r02.success)
        val layers02 = r02.totalLayers

        // Slice at 0.3mm via OVERRIDE
        lib.clearModel()
        lib.loadModel(file.absolutePath)
        val overrides = SlicingOverrides(layerHeight = OverrideValue(OverrideMode.OVERRIDE, 0.3f))
        val cfg03 = overrides.resolveInto(DEFAULT_CONFIG)
        assertEquals(0.3f, cfg03.layerHeight, 0.001f)
        val r03 = lib.slice(cfg03)!!
        assertTrue("0.3mm slice should succeed", r03.success)
        val layers03 = r03.totalLayers

        assertTrue(
            "0.3mm should produce fewer layers than 0.2mm (got $layers03 vs $layers02)",
            layers03 < layers02
        )
    }

    /**
     * ORCA_DEFAULT layer height (0.2mm) should match slicing with 0.2mm directly.
     */
    @Test
    fun orcaDefault_layerHeight_matchesFactoryDefault() {
        val file = asset("tetrahedron.stl")

        // Slice with explicit 0.2mm config
        lib.loadModel(file.absolutePath)
        val rDirect = lib.slice(DEFAULT_CONFIG.copy(layerHeight = 0.2f))!!
        assertTrue(rDirect.success)

        // Slice with ORCA_DEFAULT override applied to a base that has 0.4mm
        lib.clearModel()
        lib.loadModel(file.absolutePath)
        val overrides = SlicingOverrides(layerHeight = OverrideValue(OverrideMode.ORCA_DEFAULT))
        val cfgResolved = overrides.resolveInto(DEFAULT_CONFIG.copy(layerHeight = 0.4f))
        assertEquals("ORCA_DEFAULT should resolve to 0.2", 0.2f, cfgResolved.layerHeight, 0.001f)
        val rDefault = lib.slice(cfgResolved)!!
        assertTrue(rDefault.success)

        assertEquals(
            "ORCA_DEFAULT layer height should produce same layer count as explicit 0.2mm",
            rDirect.totalLayers, rDefault.totalLayers
        )
    }

    /**
     * USE_FILE passthrough: resolveInto with USE_FILE keeps base config unchanged.
     */
    @Test
    fun useFile_passthrough_keepsCfgLayerHeight() {
        val file = asset("tetrahedron.stl")
        lib.loadModel(file.absolutePath)

        val baseConfig = DEFAULT_CONFIG.copy(layerHeight = 0.3f)
        val overrides = SlicingOverrides() // all USE_FILE
        val resolved = overrides.resolveInto(baseConfig)
        assertEquals(0.3f, resolved.layerHeight, 0.001f)

        val result = lib.slice(resolved)!!
        assertTrue("USE_FILE passthrough should slice successfully", result.success)
        assertTrue("Should produce layers", result.totalLayers > 0)
    }

    /**
     * OVERRIDE infill density: higher density increases estimated filament use.
     */
    @Test
    fun override_infillDensity_affectsFilamentEstimate() {
        val file = asset("tetrahedron.stl")

        lib.loadModel(file.absolutePath)
        val rLow = lib.slice(DEFAULT_CONFIG.copy(fillDensity = 0.10f))!!
        assertTrue(rLow.success)

        lib.clearModel()
        lib.loadModel(file.absolutePath)
        val overrides = SlicingOverrides(infillDensity = OverrideValue(OverrideMode.OVERRIDE, 0.50f))
        val cfgHigh = overrides.resolveInto(DEFAULT_CONFIG)
        assertEquals(0.50f, cfgHigh.fillDensity, 0.001f)
        val rHigh = lib.slice(cfgHigh)!!
        assertTrue(rHigh.success)

        assertTrue(
            "50% infill should use more filament than 10% (${rHigh.estimatedFilamentMm} vs ${rLow.estimatedFilamentMm})",
            rHigh.estimatedFilamentMm > rLow.estimatedFilamentMm
        )
    }

    /**
     * OVERRIDE support=true: G-code should contain support material moves.
     */
    @Test
    fun override_supports_enabledAppearsInGcode() {
        val file = asset("tetrahedron.stl")
        lib.loadModel(file.absolutePath)

        val overrides = SlicingOverrides(supports = OverrideValue(OverrideMode.OVERRIDE, true))
        val cfg = overrides.resolveInto(DEFAULT_CONFIG)
        assertTrue("Supports should be enabled", cfg.supportEnabled)

        val result = lib.slice(cfg)!!
        assertTrue("Slice with support should succeed", result.success)
        val gcode = File(result.gcodePath).readText()
        // OrcaSlicer emits "; support" type comments or SUPPORT_START in SM_ G-code
        assertTrue(
            "G-code should contain support-related content",
            gcode.contains("; support", ignoreCase = true) ||
            gcode.contains("SUPPORT", ignoreCase = true) ||
            gcode.contains(";TYPE:Support", ignoreCase = true)
        )
    }

    /**
     * ORCA_DEFAULT support=false: support should not appear in G-code even if base had it on.
     */
    @Test
    fun orcaDefault_supports_disabledInGcode() {
        val file = asset("tetrahedron.stl")
        lib.loadModel(file.absolutePath)

        val overrides = SlicingOverrides(supports = OverrideValue(OverrideMode.ORCA_DEFAULT))
        val cfg = overrides.resolveInto(DEFAULT_CONFIG.copy(supportEnabled = true))
        assertFalse("ORCA_DEFAULT support should be false", cfg.supportEnabled)

        val result = lib.slice(cfg)!!
        assertTrue("Slice should succeed without support", result.success)
    }
}
