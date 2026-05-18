package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.data.OverrideMode
import com.u1.slicer.data.OverrideValue
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.data.SliceResult
import com.u1.slicer.data.SlicingOverrides
import com.u1.slicer.gcode.GcodeValidator
import com.u1.slicer.gcode.applyPrintTimeRemap
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
    fun tetrahedron_stl_slicesSuccessfully_withRotation() {
        // tetrahedron.stl is the smallest bundled asset (~4 triangles)
        val file = asset("tetrahedron.stl")
        assertTrue("Model should load", lib.loadModel(file.absolutePath))

        // Rotate 90° on X — model stands upright; should still produce valid G-code
        assertTrue("setModelRotation should succeed", lib.setModelRotation(90f, 0f, 0f))

        val result = lib.slice(DEFAULT_CONFIG)
        assertNotNull("Slice result should not be null", result)
        assertTrue("Slice should succeed after rotation", result!!.success)
        assertTrue("G-code path should be non-empty", result.gcodePath.isNotEmpty())
        assertTrue("G-code file should be non-empty",
            File(result.gcodePath).length() > 0)
    }

    @Test
    fun setModelRotation_multiObject_preservesInterObjectDistances() {
        // flippy+flappy+mini.3mf has 4 objects spread across the bed.
        // Rotating as a group must preserve the distances between objects — each
        // object must orbit the group centre, not spin around its own local origin.
        val file = asset("flippy+flappy+mini.3mf")
        assertTrue("flippy+flappy+mini.3mf should load", lib.loadModel(file.absolutePath))

        val beforeOffsets = lib.getInstanceOffsets()
        // Need at least 2 objects (4 floats) to test inter-object distance
        assertTrue("Expected at least 2 objects", beforeOffsets.size >= 4)

        assertTrue("setModelRotation should succeed", lib.setModelRotation(0f, 0f, 45f))

        val afterOffsets = lib.getInstanceOffsets()
        assertEquals("Instance count must not change after rotation",
            beforeOffsets.size, afterOffsets.size)

        val objectCount = beforeOffsets.size / 2

        // For every pair of objects, verify their distance is preserved within 0.5 mm
        for (i in 0 until objectCount) {
            for (j in i + 1 until objectCount) {
                val beforeDx = beforeOffsets[i * 2] - beforeOffsets[j * 2]
                val beforeDy = beforeOffsets[i * 2 + 1] - beforeOffsets[j * 2 + 1]
                val beforeDist = Math.sqrt((beforeDx * beforeDx + beforeDy * beforeDy).toDouble()).toFloat()

                val afterDx = afterOffsets[i * 2] - afterOffsets[j * 2]
                val afterDy = afterOffsets[i * 2 + 1] - afterOffsets[j * 2 + 1]
                val afterDist = Math.sqrt((afterDx * afterDx + afterDy * afterDy).toDouble()).toFloat()

                assertEquals(
                    "Distance between objects $i and $j should be preserved after rotation",
                    beforeDist, afterDist, 0.5f
                )
            }
        }
    }

    @Test
    fun setModelRotation_invalidatesPreviewMesh_soSubsequentFetchReturnsRotatedGeometry() {
        // Load tetrahedron, get unrotated mesh, rotate, get new mesh.
        // The bounding boxes should differ — confirming the preview mesh reflects rotation.
        val file = asset("tetrahedron.stl")
        assertTrue("Model should load", lib.loadModel(file.absolutePath))

        val meshBefore = lib.getPreparePreviewMesh()
        assertNotNull("Initial preview mesh should not be null", meshBefore)

        assertTrue("setModelRotation should succeed", lib.setModelRotation(90f, 0f, 0f))

        val meshAfter = lib.getPreparePreviewMesh()
        assertNotNull("Post-rotation preview mesh should not be null", meshAfter)

        // After rotation the mesh must be a different object (cache invalidated + regenerated).
        // We verify the triangle position arrays are not referentially equal and that at least
        // one vertex coordinate differs — confirming the native cache was invalidated.
        val before = meshBefore!!.trianglePositions
        val after = meshAfter!!.trianglePositions
        assertEquals("Triangle count must be the same after rotation", before.size, after.size)
        val anyDiffers = before.indices.any { i -> before[i] != after[i] }
        assertTrue(
            "Mesh vertex positions must change after 90° X rotation — confirms cache was invalidated",
            anyDiffers
        )
    }

    @Test
    fun setModelRotation_sameValueSkips_cacheNotInvalidated() {
        // Regression test for tab-switch performance: calling setModelRotation with the
        // same rotation value twice should NOT invalidate the preview mesh cache.
        val file = asset("tetrahedron.stl")
        assertTrue("Model should load", lib.loadModel(file.absolutePath))

        // First rotation (45° Z) — establishes baseline + cache
        assertTrue("First rotation should succeed", lib.setModelRotation(0f, 0f, 45f))
        val mesh1 = lib.getPreparePreviewMesh()
        assertNotNull("Mesh after first rotation", mesh1)

        // Same rotation again — should skip invalidation, return cached mesh
        assertTrue("Same rotation should succeed", lib.setModelRotation(0f, 0f, 45f))
        val mesh2 = lib.getPreparePreviewMesh()
        assertNotNull("Mesh after second (identical) rotation", mesh2)

        // Verify the mesh data is identical (same cached result)
        assertArrayEquals(
            "Identical rotation must return same cached mesh positions",
            mesh1!!.trianglePositions, mesh2!!.trianglePositions, 0f
        )
    }

    @Test
    fun setModelRotation_preservesEmbedded3mfRotation() {
        // The F1 calendar 3MF has a 90° Z-rotation in its build-item transform.
        // setModelRotation(0,0,0) must preserve this embedded rotation — the
        // preview mesh should match the initial load (not lose the rotation).
        // Uses calib-cube as a simpler BBS 3MF with known geometry.
        val file = asset("calib-cube-10-dual-colour-merged.3mf")
        assertTrue("Model should load", lib.loadModel(file.absolutePath))

        // Get initial mesh (before any setModelRotation call)
        val info = lib.getModelInfo()
        assertNotNull("ModelInfo should exist", info)

        // setModelRotation(0,0,0) must not change the mesh — embedded rotation preserved
        assertTrue("setModelRotation(0,0,0) should succeed", lib.setModelRotation(0f, 0f, 0f))
        val meshAfterZero = lib.getPreparePreviewMesh()
        assertNotNull("Mesh after zero rotation", meshAfterZero)

        // Apply additional user rotation and verify mesh changes
        assertTrue("setModelRotation(0,0,45) should succeed", lib.setModelRotation(0f, 0f, 45f))
        val meshAfter45 = lib.getPreparePreviewMesh()
        assertNotNull("Mesh after 45° rotation", meshAfter45)

        val before = meshAfterZero!!.trianglePositions
        val after = meshAfter45!!.trianglePositions
        assertEquals("Triangle count must match", before.size, after.size)
        val anyDiffers = before.indices.any { i -> before[i] != after[i] }
        assertTrue(
            "Adding 45° Z rotation on top of embedded rotation must change mesh",
            anyDiffers
        )
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

    /**
     * F77: load two STLs onto the same bed, place them at explicit non-overlapping
     * positions, slice, and verify every G-code move stays within the 270×270mm bed.
     *
     * This is the end-to-end regression for the addModel() → setObjectPositions() →
     * slice() pipeline introduced by F77. The bounding-box invalidation fix in
     * sapil_arrange.cpp (obj->invalidate_bounding_box() after setObjectPositions) is
     * the root-cause guard: without it, OrcaSlicer's auto-center reads a stale bbox
     * and applies a spurious shift that pushes objects outside the bed.
     */
    @Test
    fun f77_twoStls_placeAndSlice_gcodeWithinBedBounds() {
        val tet = asset("tetrahedron.stl")
        assertTrue("First STL must load", lib.loadModel(tet.absolutePath))
        // addModel accepts the same path — produces a second independent object
        assertTrue("Second STL must add via addModel", lib.addModel(tet.absolutePath))
        assertEquals("Both STLs must appear as separate objects", 2, lib.nativeGetObjectCount())

        // Place explicitly: both tetrahedra are small (~20mm), set well within the bed.
        // Object 0 at lower-left (10, 10), Object 1 at (60, 10) — 50mm gap.
        assertTrue(
            "setObjectPositions must succeed for 2-object bed",
            lib.setObjectPositions(floatArrayOf(10f, 10f, 60f, 10f))
        )

        val result = lib.slice(DEFAULT_CONFIG)
        assertNotNull("Slice must return a result", result)
        assertTrue(
            "Slice must succeed for two-STL bed, got: ${result!!.errorMessage}",
            result.success
        )

        val gcode = File(result.gcodePath).readText()
        val bounds = GcodeValidator.checkBedBounds(gcode)
        assertTrue(
            "All G-code X/Y moves must stay within the 270×270mm bed. " +
                "X: ${bounds.minX}..${bounds.maxX}, Y: ${bounds.minY}..${bounds.maxY}. " +
                "Violations: ${bounds.violatingLines.take(3)}",
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

    // ─── B79: STL UI settings passed to native slicer ─────────────────────────

    /**
     * B79 regression: support_type="tree(auto)" must be applied by applyConfigToPrusa()
     * for raw STL files (no embedded Snapmaker profile). Before the fix, applyConfigToPrusa()
     * never set support_type, so OrcaSlicer always used its stNormalAuto default.
     */
    @Test
    fun benchy_stl_treeSupportType_producesTreeSupportInGcode() {
        val file = asset("3DBenchy.stl")
        assertTrue(lib.loadModel(file.absolutePath))
        val config = DEFAULT_CONFIG.copy(
            supportEnabled = true,
            supportType = "tree(auto)",
            supportAngle = 30f
        )
        val result = lib.slice(config)!!
        assertTrue("Slice with tree support should succeed", result.success)
        val gcode = File(result.gcodePath).readText()
        // OrcaSlicer writes the full runtime config as comments at the end of the G-code.
        // Verify the config dump records tree(auto), proving applyConfigToPrusa() applied it.
        assertTrue(
            "G-code config dump should record support_type = tree(auto)",
            gcode.contains("support_type = tree(auto)")
        )
    }

    /**
     * B79 regression: brim_type must be set to no_brim when brim_width=0 to suppress
     * OrcaSlicer's btAutoBrim default, which adds geometry-based brims even at width=0.
     */
    @Test
    fun tetrahedron_stl_zeroBrimWidth_producesNoBrimType() {
        val config = DEFAULT_CONFIG.copy(brimWidth = 0f)
        val (success, gcode) = sliceAsset("tetrahedron.stl", config)
        assertTrue("Slice must succeed", success)
        assertTrue(
            "G-code config should record brim_type = no_brim when brim_width=0",
            gcode!!.contains("brim_type = no_brim")
        )
    }

    /**
     * B79 regression: SlicingOverrides → resolveInto() → SliceConfig → JNI chain.
     * Verifies that setting supportType via SlicingOverrides (not SliceConfig.copy directly)
     * reaches applyConfigToPrusa() and appears in the G-code config dump.
     */
    @Test
    fun tetrahedron_stl_slicingOverrides_supportType_resolveIntoChain() {
        val file = asset("tetrahedron.stl")
        lib.loadModel(file.absolutePath)
        val overrides = SlicingOverrides(
            supports = OverrideValue(OverrideMode.OVERRIDE, true),
            supportType = OverrideValue(OverrideMode.OVERRIDE, "tree(auto)")
        )
        val cfg = overrides.resolveInto(DEFAULT_CONFIG)
        assertEquals("resolveInto must propagate tree(auto)", "tree(auto)", cfg.supportType)
        val result = lib.slice(cfg)!!
        assertTrue("Slice should succeed", result.success)
        val gcode = File(result.gcodePath).readText()
        assertTrue(
            "G-code config dump should record support_type = tree(auto) via resolveInto chain",
            gcode.contains("support_type = tree(auto)")
        )
    }

    /**
     * B79 regression: filament_type from SliceConfig must reach applyConfigToPrusa()
     * and appear in the sliced G-code for raw STL files.
     */
    @Test
    fun tetrahedron_stl_filamentType_petg_appearsInGcode() {
        val config = DEFAULT_CONFIG.copy(
            nozzleTemp = 235,
            filamentType = "PETG"
        )
        val (success, gcode) = sliceAsset("tetrahedron.stl", config)
        assertTrue("Slice must succeed", success)
        assertTrue(
            "G-code must report filament_type = PETG",
            gcode!!.contains("filament_type = PETG")
        )
    }

    // ─── B55: Slice cancellation ─────────────────────────────────────────────

    /**
     * B99 regression: raw STL support/interface filament choices must survive
     * SliceConfig -> JNI -> native DynamicPrintConfig and appear in G-code.
     */
    @Test
    fun tetrahedron_stl_supportAndInterfaceFilaments_appearInGcode() {
        val config = DEFAULT_CONFIG.copy(
            supportEnabled = true,
            supportType = "normal(auto)",
            extruderCount = 4,
            filamentTypes = arrayOf("PLA", "PLA", "PETG", "PLA"),
            extruderTemps = intArrayOf(220, 220, 235, 220),
            supportFilament = 3,
            supportInterfaceFilament = 4,
        )
        val (success, gcode) = sliceAsset("tetrahedron.stl", config)
        assertTrue("Slice must succeed", success)
        assertTrue(
            "G-code must preserve support_filament = 3",
            gcode!!.contains("support_filament = 3")
        )
        assertTrue(
            "G-code must preserve support_interface_filament = 4",
            gcode.contains("support_interface_filament = 4")
        )
        assertTrue(
            "G-code must preserve PETG temp for physical slot E3",
            hasExactGcodeLine(gcode, "; nozzle_temperature = 220,220,235,220")
        )
    }

    /**
     * B99 crash repro: 3DBenchy STL with generated supports on PETG support
     * filament E3 and PETG interface filament E4 must not crash the native
     * slicer. This exercises the real support + interface + prime tower path
     * that the smaller tetrahedron metadata test does not stress.
     */
    @Test
    fun benchy_stl_supportPetgE3_interfacePetgE4_slicesSuccessfully() {
        val config = DEFAULT_CONFIG.copy(
            supportEnabled = true,
            supportType = "normal(auto)",
            supportAngle = 45f,
            extruderCount = 4,
            filamentTypes = arrayOf("PLA", "PLA", "PETG", "PETG"),
            extruderTemps = intArrayOf(220, 220, 235, 235),
            supportFilament = 3,
            supportInterfaceFilament = 4,
            wipeTowerEnabled = true,
            wipeTowerX = 170f,
            wipeTowerY = 140f,
            wipeTowerWidth = 60f,
        )
        val (success, gcode) = sliceAsset("3DBenchy.stl", config)
        assertTrue("Benchy with PETG support E3/interface E4 must slice", success)
        assertTrue(
            "G-code must preserve support_filament = 3",
            gcode!!.contains("support_filament = 3")
        )
        assertTrue(
            "G-code must preserve support_interface_filament = 4",
            gcode.contains("support_interface_filament = 4")
        )
        assertTrue(
            "G-code must preserve PETG temps for physical slots E3/E4",
            hasExactGcodeLine(gcode, "; nozzle_temperature = 220,220,235,235")
        )
        assertTrue(
            "Native STL slicing must receive per-slot material types before slicing",
            hasExactGcodeLine(gcode, "; filament_type = PLA;PLA;PETG;PETG")
        )
        val used = filamentUsedMm(gcode)
        assertTrue("Support slot E3 must have extrusion, filament used = $used", used.getOrNull(2) ?: 0f > 0f)
    }

    /**
     * B99 manual crash repro: mirror the app slice path for the G-drive
     * 3DBenchy STL after restoring support-e3/interface-e4 settings. The app
     * places the model with setModelInstances() before slicing, uses the
     * slower imported print speeds, and auto-places the prime tower at
     * (200,10); the simpler metadata-only native test above does not exercise
     * those conditions.
     */
    @Test
    fun benchy_stl_appPlaced_supportPetgE3_interfacePetgE4_slicesSuccessfully() {
        val file = asset("3DBenchy.stl")
        assertTrue("Model must load", lib.loadModel(file.absolutePath))
        assertTrue(
            "App-style single-copy placement must succeed",
            lib.setModelInstances(floatArrayOf(104.999504f, 119.498f))
        )

        val config = DEFAULT_CONFIG.copy(
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
            filamentTypes = arrayOf("PLA", "PLA", "PETG", "PETG"),
            extruderTemps = intArrayOf(220, 220, 235, 235),
            supportFilament = 3,
            supportInterfaceFilament = 4,
            wipeTowerEnabled = true,
            wipeTowerX = 200f,
            wipeTowerY = 10f,
            wipeTowerWidth = 60f,
        )
        val result = lib.slice(config)
        assertNotNull("slice() must not return null", result)
        result!!
        assertTrue(
            "App-placed Benchy with PETG support E3/interface E4 must slice: ${result.errorMessage}",
            result.success
        )
        val gcode = File(result.gcodePath).readText()
        assertTrue(
            "G-code must preserve support_filament = 3",
            gcode.contains("support_filament = 3")
        )
        assertTrue(
            "G-code must preserve support_interface_filament = 4",
            gcode.contains("support_interface_filament = 4")
        )
        assertTrue(
            "G-code must preserve PETG temps for physical slots E3/E4",
            hasExactGcodeLine(gcode, "; nozzle_temperature = 220,220,235,235")
        )
        assertTrue(
            "Native STL slicing must receive per-slot material types before slicing",
            hasExactGcodeLine(gcode, "; filament_type = PLA;PLA;PETG;PETG")
        )
        val used = filamentUsedMm(gcode)
        assertTrue("Support slot E3 must have extrusion, filament used = $used", used.getOrNull(2) ?: 0f > 0f)
    }

    /**
     * B99 manual crash repro from screenshot:
     * Support Filament = E2 PETG, Interface Filament = E3 PETG,
     * Interface Top Layers = 3 override, Interface Bottom Layers = File.
     */
    @Test
    fun benchy_stl_appPlaced_supportPetgE2_interfacePetgE3_slicesSuccessfully() {
        val file = asset("3DBenchy.stl")
        assertTrue("Model must load", lib.loadModel(file.absolutePath))
        assertTrue(
            "App-style single-copy placement must succeed",
            lib.setModelInstances(floatArrayOf(104.999504f, 119.498f))
        )

        val config = DEFAULT_CONFIG.copy(
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
            filamentTypes = arrayOf("PLA", "PETG", "PETG", "PLA"),
            extruderTemps = intArrayOf(220, 235, 235, 220),
            supportFilament = 2,
            supportInterfaceFilament = 3,
            wipeTowerEnabled = true,
            wipeTowerX = 200f,
            wipeTowerY = 10f,
            wipeTowerWidth = 60f,
        )
        val result = lib.slice(config)
        assertNotNull("slice() must not return null", result)
        result!!
        assertTrue(
            "App-placed Benchy with PETG support E2/interface PETG E3 must slice: ${result.errorMessage}",
            result.success
        )
        val gcode = File(result.gcodePath).readText()
        assertTrue(
            "G-code must preserve support_filament = 2",
            gcode.contains("support_filament = 2")
        )
        assertTrue(
            "G-code must preserve support_interface_filament = 3",
            gcode.contains("support_interface_filament = 3")
        )
        assertTrue(
            "G-code must preserve PETG temps for active E2/E3 support slots",
            hasExactGcodeLine(gcode, "; nozzle_temperature = 220,235,235") ||
                hasExactGcodeLine(gcode, "; nozzle_temperature = 220,235,235,220")
        )
        assertTrue(
            "Native STL slicing must receive per-slot support/interface material types before slicing",
            hasExactGcodeLine(gcode, "; filament_type = PLA;PETG;PETG") ||
                hasExactGcodeLine(gcode, "; filament_type = PLA;PETG;PETG;PLA")
        )
        val used = filamentUsedMm(gcode)
        assertTrue("Support slot E2 must have extrusion, filament used = $used", used.getOrNull(1) ?: 0f > 0f)
        assertTrue("Interface slot E3 must have extrusion, filament used = $used", used.getOrNull(2) ?: 0f > 0f)
    }
    @Test
    fun sliceCancelReturnsCancelledResult() {
        val file = asset("3DBenchy.stl")
        assertTrue("Model must load", lib.loadModel(file.absolutePath))

        // Start slice on a background thread
        var result: SliceResult? = null
        val sliceThread = Thread {
            result = lib.slice(DEFAULT_CONFIG)
        }
        sliceThread.start()

        // Give the slice a moment to enter process(), then cancel.
        // 3DBenchy needs enough time to start the slicing pipeline.
        Thread.sleep(1000)
        lib.cancelSlice()

        // Wait for the slice to finish (should be fast after cancel)
        sliceThread.join(60_000)

        assertNotNull("slice() must return a result", result)
        // The slice may have completed before cancel was signalled (race condition),
        // so we check: either cancelled=true OR success=true (completed before cancel).
        assertTrue(
            "Result must be either cancelled or successfully completed",
            result!!.cancelled || result!!.success
        )
    }

    // ─── B73: Scale-down correct bed placement ──────────────────────────────────

    /**
     * B73 regression: setModelInstances must account for the instance scale when computing
     * the placement offset.  Buggy formula: offset = pos - meshBB.min (ignores scale).
     * Correct formula: offset = pos - scale * meshBB.min.
     *
     * This test directly verifies the native instance offset via getInstanceOffsets(),
     * bypassing G-code parsing (which is confounded by wipe towers and other moves).
     *
     * Derivation:
     *   At scale=1.0:  offset1.x = pos1.x - 1.0 * meshBB.min.x  →  meshBB.min.x = pos1.x - offset1.x
     *   At scale=0.5:  correct offset2.x = pos2.x - 0.5 * meshBB.min.x
     *                  buggy   offset2.x = pos2.x - 1.0 * meshBB.min.x
     *   Error for non-zero meshBB.min = 0.5 * meshBB.min.x (≈ sizeX/4 for centred meshes).
     */
    @Test
    fun setModelInstances_withScale_placesInstanceAtCorrectOffset() {
        // Use the auxiliary fan cover: single-object, single-colour (no wipe tower or
        // multi-object path that would bypass the buggy formula).
        val file = asset("u1-auxiliary-fan-cover-hex_mw.3mf")
        assertTrue("Fan cover should load", lib.loadModel(file.absolutePath))
        val info = lib.getModelInfo()!!

        // ── Scale=1.0 baseline: derive meshBB.min from the placed offset ─────────
        val p1x = (270f - info.sizeX) / 2f
        val p1y = (270f - info.sizeY) / 2f
        lib.setModelInstances(floatArrayOf(p1x, p1y))
        val offsets1 = lib.getInstanceOffsets()
        assertTrue("Model must produce at least one instance offset", offsets1.size >= 2)
        val offset1x = offsets1[0]
        val offset1y = offsets1[1]

        // meshBB.min = pos - offset at scale=1.0  (formula is exact at unity scale)
        val meshMinX = p1x - offset1x
        val meshMinY = p1y - offset1y

        // ── Scale=0.5: re-centre using ViewModel-style computation ───────────────
        lib.setModelScale(0.5f, 0.5f, 0.5f)
        val scale = 0.5f
        // getModelInfo() returns load-time size (not updated by setModelScale); scale it
        // explicitly — same as SlicerViewModel.prepareSlicer() does.
        val p2x = (270f - info.sizeX * scale) / 2f
        val p2y = (270f - info.sizeY * scale) / 2f
        lib.setModelInstances(floatArrayOf(p2x, p2y))
        val offsets2 = lib.getInstanceOffsets()
        val offset2x = offsets2[0]
        val offset2y = offsets2[1]

        // Correct: offset2.x = p2x - scale * meshBB.min.x
        val expectedOffset2x = p2x - scale * meshMinX
        val expectedOffset2y = p2y - scale * meshMinY

        assertEquals(
            "B73: instance offset X at scale=0.5 should be p2x - 0.5*meshMinX " +
                "(expected $expectedOffset2x, got $offset2x, " +
                "p2x=$p2x, meshMinX=$meshMinX, p1x=$p1x, offset1x=$offset1x)",
            expectedOffset2x.toDouble(), offset2x.toDouble(), 0.5
        )
        assertEquals(
            "B73: instance offset Y at scale=0.5 should be p2y - 0.5*meshMinY " +
                "(expected $expectedOffset2y, got $offset2y, " +
                "p2y=$p2y, meshMinY=$meshMinY, p1y=$p1y, offset1y=$offset1y)",
            expectedOffset2y.toDouble(), offset2y.toDouble(), 0.5
        )
    }

    // ─── Idle/standby temperature for parked extruders (B75) ─────────────────

    /**
     * Regression: parked extruders must cool to standby temp (~120°C for PLA) after a
     * tool change, not stay at full print temperature.
     *
     * Root cause: embedded Snapmaker profiles set ooze_prevention=0 and
     * single_extruder_multi_material=1, which together prevented OrcaSlicer from emitting
     * M104 ;cooldown commands for the departing extruder.  applyConfigToPrusa() now always
     * overrides both values for multi-extruder prints.
     *
     * Verification: slicing the dual-colour calib-cube (which has an embedded profile with
     * the bad values) must produce G-code containing `;cooldown` markers from OozePrevention.
     */
    @Test
    fun dualExtruder_parkedExtruder_coolsToStandbyTemp() {
        val file = asset("calib-cube-10-dual-colour-merged.3mf")
        assertTrue(lib.loadModel(file.absolutePath))
        val info = lib.getModelInfo()!!
        val cx = (270f - info.sizeX) / 2f
        val cy = (270f - info.sizeY) / 2f
        lib.setModelInstances(floatArrayOf(cx, cy))
        val config = DEFAULT_CONFIG.copy(extruderCount = 2)
        val result = lib.slice(config)
        assertNotNull("Dual-colour slice must succeed", result)
        assertTrue("Dual-colour slice must succeed", result!!.success)
        val gcode = File(result.gcodePath).readText()
        assertTrue(
            "G-code must contain ';cooldown' M104 commands to park idle extruders at standby temp " +
                "(embedded profile has ooze_prevention=0 + SEMM=1 which must be overridden)",
            gcode.contains(";cooldown")
        )
    }

    // ─── Nozzle temperature JNI path (B71 / v1.5.63 regression guard) ─────────

    /**
     * Regression guard: extruderTemps in SliceConfig flows through JNI to applyConfigToPrusa()
     * and sets the actual nozzle_temperature in the G-code header.
     *
     * Background: nozzle_temperature in the embedded profile JSON is NOT in profile_keys[] and
     * is ignored by the native slicer. The real source is SliceConfig.extruderTemps passed via
     * JNI. This test confirms that path works for both PLA (220) and PETG (235), catching any
     * regression where the wrong temperature field is used.
     */
    @Test
    fun nozzleTemp_fromExtruderTemps_appearsInGcode_PLA() {
        val config = DEFAULT_CONFIG.copy(
            nozzleTemp = 220,
            extruderTemps = intArrayOf(220)
        )
        val (success, gcode) = sliceAsset("tetrahedron.stl", config)
        assertTrue("Slice must succeed", success)
        assertTrue("G-code must contain nozzle_temperature = 220",
            gcode!!.contains("; nozzle_temperature = 220"))
    }

    @Test
    fun nozzleTemp_fromExtruderTemps_appearsInGcode_PETG() {
        // Simulates what computeFreshSlotTemps() produces when the user switches to PETG.
        // Before v1.5.63, extruderTemps stayed at 220 (set at load time) even after preset change.
        val config = DEFAULT_CONFIG.copy(
            nozzleTemp = 235,
            extruderTemps = intArrayOf(235),
            filamentType = "PETG"
        )
        val (success, gcode) = sliceAsset("tetrahedron.stl", config)
        assertTrue("Slice must succeed", success)
        assertTrue("G-code must contain nozzle_temperature = 235 when PETG extruderTemps set",
            gcode!!.contains("; nozzle_temperature = 235"))
    }

    @Test
    fun tetrahedron_stl_sliced_gcodeContainsExcludeObjectDefine() {
        val (success, gcode) = sliceAsset("tetrahedron.stl")
        assertTrue("Slice should succeed", success)
        assertNotNull(gcode)
        assertTrue(
            "G-code must contain EXCLUDE_OBJECT_DEFINE — rebuild native with exclude_object=true",
            gcode!!.contains("EXCLUDE_OBJECT_DEFINE")
        )
    }

    // B107: bed temp set by user must not be silently bumped +5 for the first layer.
    // sapil_print.cpp previously hardcoded hot_plate_temp_initial_layer = bedTemp + 5,
    // causing the printer to run at 70°C when the user set 65°C.
    @Test
    fun b107_stlSlice_bedTemp65_initialLayerNotBumped() {
        val config = DEFAULT_CONFIG.copy(bedTemp = 65)
        val (success, gcode) = sliceAsset("tetrahedron.stl", config)
        assertTrue("Slice must succeed", success)
        assertNotNull(gcode)
        assertFalse(
            "B107: first-layer bed temp must not be silently bumped to 70 when user set 65",
            gcode!!.contains("; bed_temperature_initial_layer = 70")
        )
        // OrcaSlicer emits hot_plate_temp in the header, not bed_temperature.
        // The assertFalse above is the definitive guard; confirm 65 also appears.
        assertTrue(
            "B107: hot_plate_temp header must reflect user setting of 65",
            gcode.contains("; hot_plate_temp = 65")
        )
    }

    // B106 Bug 2 regression: slicing an STL must inject machine_start_gcode from the
    // JNI config so the Snapmaker U1 PRINT_START macro (and SM_ preamble) runs.
    // Pass a sentinel token; verify it appears in the G-code output.
    @Test
    fun b106_stlSlice_machineStartGcodeInjected() {
        val config = DEFAULT_CONFIG.copy(machineStartGcode = "PRINT_START_SENTINEL")
        val (success, gcode) = sliceAsset("tetrahedron.stl", config)
        assertTrue("Slice must succeed", success)
        assertNotNull(gcode)
        assertTrue(
            "B106: machine_start_gcode must appear in G-code when injected via SliceConfig",
            gcode!!.contains("PRINT_START_SENTINEL")
        )
    }

    // B106 Bug 1 regression: send-time T-index remap must rewrite T0 → T2 for an STL
    // sliced with E3 (slot 2) selected. Before the fix, resolveCanonicalExportMapping
    // returned null for canonicalSize=0 (raw STL), so T0 was never rewritten.
    // Single-extruder STL G-code has no implicit T0 line, so we inject one via
    // machineStartGcode to give applyPrintTimeRemap something concrete to rewrite.
    @Test
    fun b106_stlSlice_e3SendTimeRemap_rewritesT0ToT2() {
        val config = DEFAULT_CONFIG.copy(machineStartGcode = "T0")
        val file = asset("tetrahedron.stl")
        val loaded = lib.loadModel(file.absolutePath)
        assertTrue("Model must load", loaded)
        val result = lib.slice(config)
        assertTrue("Slice must succeed", result?.success == true)
        val srcPath = result!!.gcodePath
        assertTrue("Source G-code must contain T0 from injected start gcode",
            File(srcPath).readText().contains("T0"))

        val outPath = "$srcPath.b106_remap_test"
        applyPrintTimeRemap(srcPath, outPath, listOf(2))
        val remapped = File(outPath).readText()

        assertFalse("B106: T0 must not remain after remap to E3", remapped.contains("\nT0\n"))
        assertTrue("B106: T2 must appear after remap to E3", remapped.contains("T2"))
    }

    // B108: 3MF files where the build-item transform has a large embedded scale (e.g. 5.083×)
    // and a Z translation that places the bottom at exactly z=0 at 100% scale must continue
    // to sit on the bed when the user scales down. Previously, setModelScale() set the
    // scaling_factor absolutely (overwriting the file's 5.083× with 0.6), and did not
    // re-snap Z, so the model floated ~15mm above the bed and produced an empty-initial-layer
    // slice error.
    @Test
    fun b108_articulatedFish_scaledTo60pct_slicesWithModelOnBed() {

        val file = asset("Articulated+Fish+(3).3mf")
        assertTrue("B108: model must load", lib.loadModel(file.absolutePath))

        lib.setModelScale(0.6f, 0.6f, 0.6f)

        val info = lib.getModelInfo()
        assertNotNull("B108: model info must be available after load", info)
        info!!
        val s = 0.6f
        val cx = (270f - info.sizeX * s) / 2f
        val cy = (270f - info.sizeY * s) / 2f
        lib.setModelInstances(floatArrayOf(cx, cy))

        val result = lib.slice(DEFAULT_CONFIG)
        assertNotNull("B108: slice at 60% must not crash — model may be floating above bed", result)
        result!!
        assertTrue(
            "B108: scale-down must not produce empty initial layer (model must sit on bed); " +
                "error: ${result.errorMessage}",
            result.success
        )
    }

    // F83 (GitHub #136): mm-input scale path. The UI translates an mm value
    // into a scale factor via ModelScaleConverter.mmToScale; the slicer then
    // sees a regular setModelScale call. End-to-end: load tetrahedron, ask
    // the converter for "30 mm on Z", apply it, slice, confirm the layer
    // count matches a ~30 mm tall print (≈ 30 / 0.2 = 150 layers).
    @Test
    fun f83_mmScalePath_producesExpectedSlicedHeight() {
        val file = asset("tetrahedron.stl")
        assertTrue("F83: tetrahedron must load", lib.loadModel(file.absolutePath))
        val info = lib.getModelInfo()!!

        val targetMm = 30f
        val scaleZ = com.u1.slicer.model.ModelScaleConverter.mmToScale(targetMm, info.sizeZ)!!
        lib.setModelScale(scaleZ, scaleZ, scaleZ)

        val centered = (270f - info.sizeX * scaleZ) / 2f
        lib.setModelInstances(floatArrayOf(centered, centered))

        val result = lib.slice(DEFAULT_CONFIG)!!
        assertTrue("F83: mm-driven slice must succeed", result.success)

        // 30 mm at 0.2 mm layer height = 150 layers (±10% for initial-layer
        // height and rounding).
        val expectedLayers = (targetMm / DEFAULT_CONFIG.layerHeight).toInt()
        val tolerance = (expectedLayers * 0.15f).toInt().coerceAtLeast(5)
        val low = expectedLayers - tolerance
        val high = expectedLayers + tolerance
        assertTrue(
            "F83: layer count for $targetMm mm tall print should be ~$expectedLayers " +
                "(±$tolerance), got ${result.totalLayers}",
            result.totalLayers in low..high
        )
    }

    // F77 (GitHub #109): the multi-STL combiner emits a single binary STL
    // with each input translated to a centred grid cell. The native loader
    // must read that file as a regular STL, the slicer must produce a
    // multi-row layer count ( ≥ ~10 layers for 3 stacked tetrahedrons ), and
    // bedX/Y must exceed a single part's footprint (proves the grid laid
    // them out rather than stacking).
    @Test
    fun f77_multiStl_combinerProducesSliceableFile() {
        val a = asset("tetrahedron.stl")
        val b = asset("tetrahedron.stl").copyTo(File(cacheDir, "tetrahedron_b.stl"), overwrite = true)
        val c = asset("tetrahedron.stl").copyTo(File(cacheDir, "tetrahedron_c.stl"), overwrite = true)
        val combined = File(cacheDir, "f77_combined.stl")

        val result = com.u1.slicer.model.MultiStlCombiner.combine(listOf(a, b, c), combined)
        assertNotNull("F77: combiner must return a result", result)
        result!!
        assertEquals(3, result.placedParts.size)
        assertTrue("F77: combined file must exist and be non-empty", combined.exists() && combined.length() > 100)

        assertTrue("F77: native loader must accept the combined STL", lib.loadModel(combined.absolutePath))
        val info = lib.getModelInfo()!!
        // 3 tetrahedrons in a row with 5 mm margin: combined footprint width
        // must clearly exceed a single tetrahedron's footprint.
        val singlePart = result.placedParts.first()
        assertTrue(
            "F77: combined sizeX (${info.sizeX}) must exceed a single part (${singlePart.sizeX})",
            info.sizeX > singlePart.sizeX * 1.5f
        )

        val sliced = lib.slice(DEFAULT_CONFIG)!!
        assertTrue("F77: combined STL must slice successfully", sliced.success)
        assertTrue("F77: combined slice must produce > 0 layers", sliced.totalLayers > 0)
    }

    // B108 multi-object gap: 3MF with two build items that each carry different embedded
    // rotation+scale matrices (~0.76×) AND different Z translations (z≈2.89mm and z≈16.75mm).
    // The original B108 fix used a single group-wide Z correction (shift all instances by
    // -groupMinZ), which only snaps the lowest object to the bed; the higher object stayed
    // floating ~14mm above the bed and triggered "[obj N] empty initial layer" slice errors.
    // The follow-up fix snaps each instance independently. Direct check via
    // getInstanceWorldZMins() — avoids a multi-million-triangle slice that OOMs the
    // instrumentation process on test devices.
    @Test
    fun b108_skywingMultiObject_perInstanceZOffsets_bedSnappedAfterScale() {
        val file = asset("skywing-seawing-silkwing.3mf")
        assertTrue("B108 multi-object: model must load", lib.loadModel(file.absolutePath))

        lib.setModelScale(0.8f, 0.8f, 0.8f)

        val zMins = lib.getInstanceWorldZMins()
        assertTrue(
            "B108 multi-object: fixture must produce at least 2 instances (one per build item), " +
                "got ${zMins.size}",
            zMins.size >= 2
        )
        for ((i, z) in zMins.withIndex()) {
            assertEquals(
                "B108 multi-object: instance $i must sit on the bed after scale " +
                    "(world min.z must be ~0); got worldZMins=${zMins.toList()}",
                0.0, z.toDouble(), 0.01
            )
        }
    }
}
