package com.u1.slicer.slicing

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.aipaint.AiRegion
import com.u1.slicer.aipaint.PaintedMeshWriter
import com.u1.slicer.data.MixedFilamentRow
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.viewer.StlParser
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * B146/B147 gate — a model PAINTED (Smart Paint) to a MIX slot must apply the
 * within-layer top-surface mix split for ALL top-mix modes, exactly as the
 * object/part-assignment path already does.
 *
 * RED→GREEN history (do not weaken): the painted path encodes the mix per-triangle
 * as an mmu paint state and the engine resolves it through `apply_mm_segmentation`.
 * Before B146, that pass COLLAPSED the painted mix channel onto a single physical
 * tool per layer for ALL modes, so `region.config().solid_infill_filament` was a
 * single physical slot (3 or 4), never the mix id. The GCode top-surface split and
 * the ToolOrdering component registration BOTH gate on `is_mixed(solid_infill_filament)`,
 * so the within-layer split never engaged. Confirmed empirically on-device with native
 * instrumentation (B146DBG): `is_mixed=0`, `region_sif=3/4`, `seq=(null)`.
 *
 * B146 fixed proportional/dither/ironing-glaze modes but accidentally excluded
 * STRIPES (top_mix_mode == 0) and STRIPES + fine_top_lines — both still collapsed.
 * B147 broadens the condition to `mixed_row->enabled` (any enabled painted mix keeps
 * its mix id, regardless of mode flags).
 *
 * This file tests both DITHER (existing B146 gate) and STRIPES (new B147 gate).
 * Gate for each: at least one layer must place BOTH mix-component tools (T2 + T3)
 * on its `;TYPE:Top surface` extrusion lines.
 */
@RunWith(AndroidJUnit4::class)
class PaintedMixTopSurfaceTest {

    private companion object {
        const val TAG = "PaintedMixTopSurface"
        const val ASSET = "3DBenchy.stl"
    }

    private lateinit var lib: NativeLibrary
    private lateinit var cacheDir: File

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

    private fun asset(name: String): File {
        val file = File(cacheDir, name)
        InstrumentationRegistry.getInstrumentation().context
            .assets.open(name).use { it.copyTo(file.outputStream()) }
        return file
    }

    private fun makeConfig(recipe: String) = SliceConfig(
        layerHeight = 0.2f,
        firstLayerHeight = 0.2f,
        perimeters = 2,
        topSolidLayers = 3,
        bottomSolidLayers = 3,
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
        extruderCount = 4,
        extruderTemps = IntArray(4) { 220 },
        wipeTowerEnabled = true,
        mixedFilamentDefinitions = recipe,
    )

    /** Flat trianglePositions (9 floats/triangle) extracted from the STL's 10-float vertex buffer. */
    private fun readTrianglePositions(stl: File): FloatArray {
        val mesh = StlParser.parse(stl)
        val vc = mesh.vertexCount
        val buf = mesh.vertices.duplicate()
        buf.rewind()
        val out = FloatArray(vc * 3)
        for (v in 0 until vc) {
            val base = v * 10
            out[v * 3] = buf.get(base)
            out[v * 3 + 1] = buf.get(base + 1)
            out[v * 3 + 2] = buf.get(base + 2)
        }
        return out
    }

    private val toolRe = Regex("^T\\d+$")
    private val extrudingMoveRe = Regex("""G1 .*\bE\+?(\d*\.?\d+)""")
    private fun isExtrudingMove(line: String): Boolean = extrudingMoveRe.containsMatchIn(line)

    /** Distinct tools that issue at least one positive-E extruding move anywhere in the file. */
    private fun extrudingToolsWholeFile(gcode: String): Set<Int> {
        val tools = mutableSetOf<Int>()
        var current = -1
        for (raw in gcode.lineSequence()) {
            val t = raw.trim()
            when {
                t.matches(toolRe) -> current = t.substring(1).toInt()
                current >= 0 && isExtrudingMove(t) -> tools.add(current)
            }
        }
        return tools
    }

    /**
     * Distinct tools extruding in BODY regions — not inside a ;TYPE:Top surface
     * block and not inside a prime/wipe tower block. >=2 means the mix blends the body.
     */
    private fun bodyExtrudingTools(gcode: String): Set<Int> {
        val tools = mutableSetOf<Int>()
        var current = -1
        var inTop = false
        var inTower = false
        for (raw in gcode.lineSequence()) {
            val t = raw.trim()
            when {
                t.startsWith(";TYPE:") -> {
                    inTop = t.equals(";TYPE:Top surface", ignoreCase = true)
                    inTower = t.contains("Prime tower", ignoreCase = true) ||
                        t.contains("Wipe tower", ignoreCase = true)
                }
                t.matches(toolRe) -> current = t.substring(1).toInt()
                !inTop && !inTower && current >= 0 && isExtrudingMove(t) -> tools.add(current)
            }
        }
        return tools
    }

    /** Per-layer set of tools extruding inside ;TYPE:Top surface blocks. */
    private fun perLayerTopTools(gcode: String): Map<Int, Set<Int>> {
        val out = mutableMapOf<Int, MutableSet<Int>>()
        var layerIdx = -1
        var current = -1
        var inTop = false
        for (raw in gcode.lineSequence()) {
            val t = raw.trim()
            when {
                t.startsWith(";LAYER_CHANGE") -> { layerIdx++; inTop = false }
                t.startsWith(";TYPE:") -> inTop = t.equals(";TYPE:Top surface", ignoreCase = true)
                t.matches(toolRe) -> current = t.substring(1).toInt()
                inTop && layerIdx >= 0 && current >= 0 && isExtrudingMove(t) ->
                    out.getOrPut(layerIdx) { mutableSetOf() }.add(current)
            }
        }
        return out
    }

    /**
     * B147 gate — STRIPES mode (top_mix_mode == 0) painted mix must split the top
     * surface. Before B147, the collapse-prevention condition in apply_mm_segmentation
     * only covered proportional/dither/ironing-glaze, so STRIPES mode collapsed to one
     * physical tool per layer and produced no within-layer split on the top surface.
     */
    @Test
    fun paintedMix_stripesMode_topSurfaceSplits() {
        val stl = asset(ASSET)
        val positions = readTrianglePositions(stl)
        val nTri = positions.size / 9
        assertTrue("STL must have triangles", nTri > 0)

        val (_, recipe) = SurfaceColorMixTestSupport.buildRecipeAndSlot(
            componentSlots = listOf(3, 4),
            weights = listOf(50, 50),
            distributionMode = MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS,
            canonicalCount = 4,
            topMixMode = MixedFilamentRow.TopMixMode.STRIPES,
        )

        val mixRegionId = 4
        val regionIds = IntArray(nTri) { mixRegionId }
        val regions = (0..3).map { s ->
            AiRegion(id = s, label = "Slot ${s + 1}", suggestedColour = "#888888", slot = s)
        }
        val painted = File(cacheDir, "painted_mix_stripes_${System.currentTimeMillis()}.3mf")
        PaintedMeshWriter.write(
            positions = positions,
            regionIds = regionIds,
            regions = regions,
            outputFile = painted,
            printerColours = listOf("#000000", "#FF0000", "#00FF00", "#0000FF"),
            mixDisplayColours = listOf("#00FFFF"),
        )
        assertTrue("painted 3MF must be written", painted.length() > 0)
        assertTrue("loadModel must succeed", lib.loadModel(painted.absolutePath))

        val result = lib.slice(makeConfig(recipe)) ?: error("slice() returned null")
        assertTrue("slice must succeed, error='${result.errorMessage}'", result.success)
        val gcode = File(result.gcodePath).readText()

        val bodyTools = bodyExtrudingTools(gcode).sorted()
        assertTrue(
            "precondition: painted STRIPES mix must blend the body (>=2 tools), got body=$bodyTools",
            bodyTools.size >= 2,
        )

        val topByLayer = perLayerTopTools(gcode)
        val topLayersWithMix = topByLayer.entries.filter { 2 in it.value && 3 in it.value }
        val topSummary = topByLayer.entries.sortedBy { it.key }
            .joinToString(" ") { (l, s) -> "L$l${s.sorted()}" }
            .ifEmpty { "(no top-surface extrusion)" }
        assertTrue(
            "B147: STRIPES mode painted mix must apply within-layer top-surface split: " +
                "expected >=1 layer with BOTH T2+T3 in ;TYPE:Top surface, got " +
                "${topLayersWithMix.size}. body=$bodyTools | top per layer: $topSummary",
            topLayersWithMix.isNotEmpty(),
        )
    }

    @Test
    fun paintedMix_allTrianglesToMixSlot_topSurfaceSplits() {
        val stl = asset(ASSET)
        val positions = readTrianglePositions(stl)
        val nTri = positions.size / 9
        assertTrue("STL must have triangles", nTri > 0)

        // 2-component mix [E3,E4] @ 50/50, DITHER top mix mode (clear within-layer
        // signal). canonicalCount=4 matches the painted 3MF's physical-region count
        // so the recipe's mix slot lines up with the painted physical count.
        val (recipeMixSlot, recipe) = SurfaceColorMixTestSupport.buildRecipeAndSlot(
            componentSlots = listOf(3, 4),
            weights = listOf(50, 50),
            distributionMode = MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS,
            canonicalCount = 4,
            topMixMode = MixedFilamentRow.TopMixMode.DITHER,
        )
        Log.i(TAG, "recipe mix slot (1-based) = $recipeMixSlot ; recipe = $recipe")

        // Painted 3MF: 4 physical slot regions (E1-E4); every triangle painted to the
        // first MIX slot = 0-based regionId 4 (physicalCount=4 → usesMixSlot true).
        val mixRegionId = 4
        val regionIds = IntArray(nTri) { mixRegionId }
        val regions = (0..3).map { s ->
            AiRegion(id = s, label = "Slot ${s + 1}", suggestedColour = "#888888", slot = s)
        }
        val printerColours = listOf("#000000", "#FF0000", "#00FF00", "#0000FF")
        val mixDisplayColours = listOf("#00FFFF")

        val painted = File(cacheDir, "painted_mix_${System.currentTimeMillis()}.3mf")
        PaintedMeshWriter.write(
            positions = positions,
            regionIds = regionIds,
            regions = regions,
            outputFile = painted,
            printerColours = printerColours,
            mixDisplayColours = mixDisplayColours,
        )
        assertTrue("painted 3MF must be written (len>0)", painted.length() > 0)

        // Load the painted 3MF and slice with the recipe — NO nativeSetVolumeExtruder
        // (this is the painted path, not object-assignment).
        assertTrue("loadModel must succeed for painted 3MF", lib.loadModel(painted.absolutePath))

        val result = lib.slice(makeConfig(recipe)) ?: error("slice() returned null")
        assertTrue("slice must succeed, error='${result.errorMessage}'", result.success)
        val gcode = File(result.gcodePath).readText()

        // Precondition: the mix reached the engine as >=2 physical tools and blends
        // the body — guards against a regression that folds the mix to one slot
        // before slicing (which would make the top-surface gate vacuously fail).
        val wholeFileTools = extrudingToolsWholeFile(gcode).sorted()
        val bodyTools = bodyExtrudingTools(gcode).sorted()
        assertTrue(
            "precondition: painted mix must reach the engine as >=2 physical tools " +
                "blending the body, got whole-file=$wholeFileTools body=$bodyTools",
            bodyTools.size >= 2,
        )

        // Gate: at least one layer places BOTH mix-component tools (T2 + T3) on its
        // ;TYPE:Top surface lines. Before the fix this was always 0 for the painted
        // path (mix collapsed to one physical slot per layer pre-slice).
        val topByLayer = perLayerTopTools(gcode)
        val topLayersWithMix = topByLayer.entries.filter { 2 in it.value && 3 in it.value }
        val topSummary = topByLayer.entries.sortedBy { it.key }
            .joinToString(" ") { (l, s) -> "L$l${s.sorted()}" }
            .ifEmpty { "(no top-surface extrusion)" }
        assertTrue(
            "painted mix must apply the within-layer top-surface split: expected >=1 " +
                "layer with BOTH T2+T3 in ;TYPE:Top surface blocks, got " +
                "${topLayersWithMix.size}. body tools=$bodyTools whole-file=$wholeFileTools | " +
                "top-surface per layer: $topSummary",
            topLayersWithMix.isNotEmpty(),
        )
    }
}
