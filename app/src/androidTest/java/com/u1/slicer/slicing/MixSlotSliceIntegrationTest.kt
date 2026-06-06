package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.aipaint.AiRegion
import com.u1.slicer.aipaint.PaintedMeshWriter
import com.u1.slicer.data.MixedFilamentManager
import com.u1.slicer.data.MixedFilamentRow
import com.u1.slicer.data.SliceConfig
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * M3-B Task 11 — end-to-end instrumented gate: a painted 3MF whose triangles are
 * assigned to a mix slot, sliced with a [MixedFilamentManager]-built recipe, must
 * produce G-code that carries the mix definition.
 *
 * Proof chain:
 *   PaintedMeshWriter.write (mix slot 4 = engine state 5)
 *     → lib.loadModel (BBS importer decodes paint states — proven by MixSlotPaintRoundTripTest)
 *       → lib.slice(config with mixedFilamentDefinitions = recipe)
 *         → G-code header contains "mixed_filament_definitions" AND the recipe substring
 *
 * The [MixSlotPaintRoundTripTest] already gates the annotation-survival step. This test
 * gates the NEXT step: that the recipe is fed to the engine and appears in its config dump.
 *
 * GATE: if the slice errors (engine rejects a painted mix 3MF), the test fails with the
 * exact engine error message — report as BLOCKED; do not weaken.
 */
@RunWith(AndroidJUnit4::class)
class MixSlotSliceIntegrationTest {

    private lateinit var lib: NativeLibrary
    private lateinit var out3mf: File

    @Before
    fun setup() {
        assertTrue("Native library must be loaded on device (arm64 required)", NativeLibrary.isLoaded)
        lib = NativeLibrary()
        lib.clearModel()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        out3mf = File(ctx.cacheDir, "mix_slot_slice_${System.currentTimeMillis()}.3mf")
    }

    @After
    fun teardown() {
        lib.clearModel()
        out3mf.delete()
    }

    /**
     * Builds a closed triangulated box (cuboid) as a FloatArray of 9-float triangles
     * (3 xyz per vertex × 3 vertices per triangle). The box spans [ox, ox+w] × [0, d] × [0, h]
     * and has proper outward-facing normals so OrcaSlicer treats it as a solid volume.
     * 12 triangles (2 per face × 6 faces).
     *
     * Painted triangles (ones that should carry [color]) are only the top face (tris 10-11)
     * and front face (tris 8-9) to ensure the mix slot has actual geometry.
     */
    private fun box(ox: Float, w: Float = 4f, d: Float = 4f, h: Float = 4f): FloatArray {
        val x0 = ox; val x1 = ox + w
        val y0 = 0f; val y1 = d
        val z0 = 0f; val z1 = h
        // 12 triangles, 2 per face. Each tri = 9 floats (v0.xyz, v1.xyz, v2.xyz)
        return floatArrayOf(
            // Bottom face (z=z0, normal -Z)
            x0,y0,z0,  x1,y1,z0,  x1,y0,z0,
            x0,y0,z0,  x0,y1,z0,  x1,y1,z0,
            // Top face (z=z1, normal +Z)
            x0,y0,z1,  x1,y0,z1,  x1,y1,z1,
            x0,y0,z1,  x1,y1,z1,  x0,y1,z1,
            // Front face (y=y0, normal -Y)
            x0,y0,z0,  x1,y0,z0,  x1,y0,z1,
            x0,y0,z0,  x1,y0,z1,  x0,y0,z1,
            // Back face (y=y1, normal +Y)
            x0,y1,z0,  x1,y1,z1,  x1,y1,z0,
            x0,y1,z0,  x0,y1,z1,  x1,y1,z1,
            // Left face (x=x0, normal -X)
            x0,y0,z0,  x0,y0,z1,  x0,y1,z1,
            x0,y0,z0,  x0,y1,z1,  x0,y1,z0,
            // Right face (x=x1, normal +X)
            x1,y0,z0,  x1,y1,z1,  x1,y0,z1,
            x1,y0,z0,  x1,y1,z0,  x1,y1,z1,
        )
    }

    /**
     * Builds a [SliceConfig] sized for [extruderCount] virtual extruders.
     *
     * Wipe tower is DISABLED. The wipe tower path calls
     * `ToolOrdering::reorder_extruders_for_minimum_flush_volume` which reads
     * `flush_volumes_matrix` sized N×N. For a non-Snapmaker-profile 3MF
     * (like the one [PaintedMeshWriter] writes), `is_snapmaker_profile=false`,
     * so the `flush_volumes_matrix` profile key is never applied to `dpc`,
     * leaving it at its `FullPrintConfig::defaults()` 1-element value. With 5
     * virtual extruders the wipe tower then crashes with a SIGSEGV trying to
     * read index [4] from a 1-element matrix. Disabling the wipe tower skips
     * `_make_wipe_tower()` entirely — paint segmentation and G-code generation
     * still work.
     */
    private fun makeConfig(extruderCount: Int, recipe: String) = SliceConfig(
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
        wipeTowerEnabled = false,
        mixedFilamentDefinitions = recipe,
    )

    @Test
    fun paintedMixSlot_slicesWithRecipe_gcodeContainsMixDefinition() {
        // ── 1. Build a small painted mesh with one mix slot ───────────────────
        // Three 4×4×4mm boxes placed side-by-side along X, each with 12 triangles.
        // Box A (x=0..4)   → all 12 triangles painted to physical slot 0 (state 1, E1)
        // Box B (x=20..24) → all 12 triangles painted to physical slot 1 (state 2, E2)
        // Box C (x=40..44) → all 12 triangles painted to mix slot 4 (state 5, virtual E1+E2)
        // Total 36 triangles. Boxes have real volume (4mm height) so the slicer
        // produces actual layers at 0.2mm layer height.
        val boxA = box(ox = 0f)
        val boxB = box(ox = 20f)
        val boxC = box(ox = 40f)
        val positions = boxA + boxB + boxC
        val regionIds = IntArray(12) { 0 } + IntArray(12) { 1 } + IntArray(12) { 4 }

        // 4 physical slot regions (slots 0-3) so filament_colour has 4 physical +
        // 1 mix display colour = 5 entries total (matches numPhysical=4 below).
        val regions = (0..3).map { s ->
            AiRegion(id = s, label = "Slot ${s + 1}", suggestedColour = "#888888", slot = s)
        }
        val printerColours = listOf("#FF0000", "#00FF00", "#0000FF", "#FFFF00") // E1-E4
        // One mix display colour for the single E1+E2 mix (slot 4 → state 5).
        val mixDisplayColours = listOf("#808000") // naive blend of E1+E2

        PaintedMeshWriter.write(
            positions = positions,
            regionIds = regionIds,
            regions = regions,
            outputFile = out3mf,
            printerColours = printerColours,
            mixDisplayColours = mixDisplayColours,
        )
        assertTrue("Painted 3MF must be written (size > 0)", out3mf.length() > 0)

        // ── 2. Build a recipe with one project mix (E1+E2 @ 50% LAYER_CYCLE) ─
        val mgr = MixedFilamentManager(
            loadProject = { emptyList() },
            loadLibrary = { emptyList() },
            saveProject = {},
            saveLibrary = {},
        )
        mgr.add(
            componentA = 1,
            componentB = 2,
            mixBPercent = 50,
            distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
        )
        val recipe = mgr.serialize(numPhysicalFilaments = 4)

        assertTrue(
            "Manager must produce a non-empty recipe string",
            recipe.isNotEmpty(),
        )
        // Sanity: recipe must start with the canonical field order the engine expects.
        assertTrue(
            "Recipe must start with '1,2,1,1,50,' (componentA,componentB,enabled,custom,mixBPct)",
            recipe.startsWith("1,2,1,1,50,"),
        )

        // ── 3. Load the painted 3MF and slice with the recipe ─────────────────
        assertTrue(
            "loadModel must succeed for the painted mix-slot 3MF",
            lib.loadModel(out3mf.absolutePath),
        )

        // extruderCount = nPhysical + nMix = 4 + 1 = 5 so the engine sizes filament_colour
        // to cover the mix slot's paint state (state 5 = virtual extruder 5). Without this,
        // applyConfigToPrusa sets filament_colour to only n_ext=4 entries and the paint
        // segmentation crashes OOB when it encounters state 5 ("Error: vector").
        val config = makeConfig(extruderCount = 5, recipe = recipe)
        val result = lib.slice(config)

        assertNotNull(
            "slice() must not return null — engine must not crash on a painted mix-slot 3MF",
            result,
        )
        result!!

        // GATE: if the engine errors, this assert surfaces the exact message.
        assertTrue(
            "GATE: slice must succeed on a painted mix-slot 3MF. Engine error: '${result.errorMessage}'. " +
                "If this fires, the engine cannot process mix-slot paint states — report as BLOCKED.",
            result.success,
        )
        assertTrue(
            "G-code path must be non-empty after a successful slice",
            result.gcodePath.isNotEmpty(),
        )

        val gcode = File(result.gcodePath).readText()
        assertTrue(
            "G-code must be non-empty",
            gcode.isNotEmpty(),
        )

        // ── 4. Assert the recipe is present in the G-code config dump ─────────
        // OrcaSlicer writes every set config key as a "; key = value" comment in
        // the G-code prelude. The mixed_filament_definitions key (set via
        // applyConfigToPrusa → dpc.set_key_value) must appear.
        assertTrue(
            "GATE: G-code config-dump must contain 'mixed_filament_definitions'. " +
                "If missing, the SliceConfig → JNI → applyConfigToPrusa wiring is broken for " +
                "the painted mix-slot path. Recipe was: '$recipe'.",
            gcode.contains("mixed_filament_definitions"),
        )

        // The recipe content itself (component fields 1,2,1,1,50) must be present so we
        // confirm the engine received and echoed back the actual value, not an empty string.
        val recipePrefix = "1,2,1,1,50"
        assertTrue(
            "G-code config-dump must contain the recipe prefix '$recipePrefix'. " +
                "If missing, the recipe value was not passed to the engine despite the key existing.",
            gcode.contains(recipePrefix),
        )
    }
}
