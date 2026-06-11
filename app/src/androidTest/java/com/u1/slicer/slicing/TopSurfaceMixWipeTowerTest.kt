package com.u1.slicer.slicing

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.data.MixedFilamentRow
import com.u1.slicer.data.SliceConfig
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * RED test for within-layer TOP-surface mixing with the wipe tower ENABLED.
 *
 * Fixture: `calib-cube-10-dual-colour-merged.3mf` is a DUAL-COLOUR cube whose
 * volumes use file tools T0 and T1. That means "a layer with >= 2 distinct
 * tools in its top-surface lines" is trivially true even with NO mix (both
 * colour regions top out in the same layers), so the discrimination is keyed
 * on mix-COMPONENT tools the file does NOT use: the mix recipe targets slots
 * 3+4 (tools T2/T3), and the red gate requires at least one layer whose
 * top-surface lines contain BOTH T2 and T3.
 *
 * Background: a SAME_LAYER_DOTS mix splits BODY infill by component within a
 * layer (wipe-tower-safe), but TOP solid infill currently stays single-tool —
 * that is the bug under test. A prior test branch masked a wipe-tower crash
 * (`WipeTowerIntegration::append_tcr unexpected toolchange`) by setting
 * wipeTowerEnabled=false; this class deliberately keeps the wipe tower ON so
 * both the crash guard and the top-surface mixing gap are exercised together.
 *
 * Expected outcomes while the engine bug exists:
 *  - noMix_wipeTowerOn_slicesCleanAndTopSingleTool: PASSES (control — top
 *    tools must stay a subset of the file's own {T0, T1}).
 *  - dotsMix_oneObject_wipeTowerOn_topSurfaceMixesWithinLayer: FAILS on the
 *    layersWithToolPairInTop(g, 2, 3) >= 1 assertion (slice succeeds, but no
 *    layer has both mix-component tools on top-surface extrusion lines).
 */
@RunWith(AndroidJUnit4::class)
class TopSurfaceMixWipeTowerTest {

    private companion object {
        const val TAG = "TopSurfaceMixWT"
        const val ASSET = "calib-cube-10-dual-colour-merged.3mf"
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

    // ─── G-code analysis helpers ─────────────────────────────────────────────

    /**
     * Count layers (";LAYER_CHANGE" blocks) whose extrusion lines inside
     * ";TYPE:Top surface" sections used BOTH tool [a] AND tool [b]. This is the
     * discriminating within-layer gate for a dual-colour fixture: the file's own
     * tools (T0/T1) can trivially co-occur in a top layer, so the gate is keyed
     * on the mix-component pair (T2/T3) that the file does NOT use.
     */
    private fun layersWithToolPairInTop(gcode: String, a: Int, b: Int): Int {
        var count = 0
        var currentTool = -1
        var inTop = false
        var layerOpen = false
        val layerTopTools = mutableSetOf<Int>()
        val toolRe = Regex("T\\d+")

        fun closeLayer() {
            if (layerOpen && a in layerTopTools && b in layerTopTools) count++
            layerTopTools.clear()
        }

        for (line in gcode.lineSequence()) {
            val t = line.trim()
            when {
                t.startsWith(";LAYER_CHANGE") -> {
                    closeLayer()
                    layerOpen = true
                    inTop = false
                }
                t.startsWith(";TYPE:") ->
                    inTop = t.equals(";TYPE:Top surface", ignoreCase = true)
                t.matches(toolRe) ->
                    currentTool = t.substring(1).toInt()
                inTop && layerOpen && currentTool >= 0 &&
                    t.startsWith("G1 ") && t.contains(" E") ->
                    layerTopTools.add(currentTool)
            }
        }
        closeLayer()
        return count
    }

    /**
     * BODY counterpart of [layersWithToolPairInTop] (diagnostics only): counts
     * layers whose extrusion lines in any ";TYPE:" section EXCEPT "Top surface"
     * AND EXCEPT tower sections (Prime tower / Wipe tower — case-insensitive
     * match on "tower" anywhere in the label) used BOTH tool [a] AND tool [b].
     * Excludes tower sections because the wipe tower legitimately extrudes with
     * multiple tools every layer, which would otherwise make bodyPairLayers
     * equal the total layer count and mask real model-body mixing info.
     */
    private fun layersWithToolPairInBody(gcode: String, a: Int, b: Int): Int {
        var count = 0
        var currentTool = -1
        var inBody = false
        var layerOpen = false
        val layerBodyTools = mutableSetOf<Int>()
        val toolRe = Regex("T\\d+")

        fun closeLayer() {
            if (layerOpen && a in layerBodyTools && b in layerBodyTools) count++
            layerBodyTools.clear()
        }

        for (line in gcode.lineSequence()) {
            val t = line.trim()
            when {
                t.startsWith(";LAYER_CHANGE") -> {
                    closeLayer()
                    layerOpen = true
                    inBody = false
                }
                t.startsWith(";TYPE:") -> {
                    val label = t.removePrefix(";TYPE:")
                    val isTop = label.equals("Top surface", ignoreCase = true)
                    val isTower = label.contains("tower", ignoreCase = true)
                    inBody = !isTop && !isTower
                }
                t.matches(toolRe) ->
                    currentTool = t.substring(1).toInt()
                inBody && layerOpen && currentTool >= 0 &&
                    t.startsWith("G1 ") && t.contains(" E") ->
                    layerBodyTools.add(currentTool)
            }
        }
        closeLayer()
        return count
    }

    /**
     * Per-layer summary of tool sets grouped by ;TYPE: label, for failure diagnostics.
     * For each ;LAYER_CHANGE block, lists the distinct ;TYPE: labels seen and the set
     * of tools that extruded (G1 with E) under each label.
     * Format example: "L12{Internal infill:[2], Prime tower:[2,3], Top surface:[1]}"
     * Only layers with any extrusion are included. [maxLayers] caps how many layers
     * are included in the output (first [maxLayers] layers that have extrusion).
     */
    private fun perLayerToolTypeSummary(gcode: String, maxLayers: Int = 60): String {
        // layerData: layerIdx -> map of typelabel -> set of tools
        val layerData = mutableMapOf<Int, MutableMap<String, MutableSet<Int>>>()
        var layerIdx = -1
        var currentTool = -1
        var currentType = ""
        val toolRe = Regex("T\\d+")

        for (line in gcode.lineSequence()) {
            val t = line.trim()
            when {
                t.startsWith(";LAYER_CHANGE") -> {
                    layerIdx++
                    currentType = ""
                }
                t.startsWith(";TYPE:") -> {
                    currentType = t.removePrefix(";TYPE:")
                }
                t.matches(toolRe) -> {
                    currentTool = t.substring(1).toInt()
                }
                layerIdx >= 0 && currentType.isNotEmpty() && currentTool >= 0 &&
                    t.startsWith("G1 ") && t.contains(" E") -> {
                    layerData
                        .getOrPut(layerIdx) { mutableMapOf() }
                        .getOrPut(currentType) { mutableSetOf() }
                        .add(currentTool)
                }
            }
        }

        if (layerData.isEmpty()) return "(no extrusion lines found)"

        val sortedLayers = layerData.keys.sorted()
        val sb = StringBuilder()

        fun appendLayer(idx: Int) {
            val typeMap = layerData[idx] ?: return
            sb.append("L$idx{")
            typeMap.entries.sortedBy { it.key }.forEachIndexed { i, (label, tools) ->
                if (i > 0) sb.append(", ")
                sb.append("$label:${tools.sorted()}")
            }
            sb.append("}")
        }

        // First maxLayers layers with extrusion
        val firstLayers = sortedLayers.take(maxLayers)
        firstLayers.forEach { appendLayer(it) }

        return sb.toString().ifEmpty { "(no extrusion lines found)" }
    }

    /** Collects all distinct ;TYPE: labels appearing in the G-code. */
    private fun allTypeLabels(gcode: String): Set<String> {
        val labels = mutableSetOf<String>()
        for (line in gcode.lineSequence()) {
            val t = line.trim()
            if (t.startsWith(";TYPE:")) labels.add(t.removePrefix(";TYPE:"))
        }
        return labels
    }

    /** All tools selected anywhere in the G-code (whole-file `T\d+` line set; diagnostics). */
    private fun wholeFileToolSet(gcode: String): Set<Int> {
        val tools = mutableSetOf<Int>()
        val toolRe = Regex("T\\d+")
        for (line in gcode.lineSequence()) {
            val t = line.trim()
            if (t.matches(toolRe)) tools.add(t.substring(1).toInt())
        }
        return tools
    }

    /**
     * Per-layer summary of top-surface tool sets, for failure diagnostics.
     * Returns e.g. "L0:[0] L1:[0] ... L48:[1] L49:[1]" (layers with no top lines omitted).
     */
    private fun perLayerTopToolSummary(gcode: String): String {
        val sb = StringBuilder()
        var layerIdx = -1
        var currentTool = -1
        var inTop = false
        val layerTopTools = mutableSetOf<Int>()
        val toolRe = Regex("T\\d+")

        fun flush() {
            if (layerIdx >= 0 && layerTopTools.isNotEmpty()) {
                sb.append("L").append(layerIdx).append(":").append(layerTopTools.sorted()).append(" ")
            }
            layerTopTools.clear()
        }

        for (line in gcode.lineSequence()) {
            val t = line.trim()
            when {
                t.startsWith(";LAYER_CHANGE") -> {
                    flush()
                    layerIdx++
                    inTop = false
                }
                t.startsWith(";TYPE:") ->
                    inTop = t.equals(";TYPE:Top surface", ignoreCase = true)
                t.matches(toolRe) ->
                    currentTool = t.substring(1).toInt()
                inTop && currentTool >= 0 && t.startsWith("G1 ") && t.contains(" E") ->
                    layerTopTools.add(currentTool)
            }
        }
        flush()
        return sb.toString().trim().ifEmpty { "(no top-surface extrusion lines found)" }
    }

    /** Tools seen on extrusion lines within ";TYPE:Top surface" blocks (diagnostics). */
    private fun topSurfaceTools(gcode: String): Set<Int> {
        val tools = mutableSetOf<Int>()
        var inTop = false
        var current = -1
        for (line in gcode.lineSequence()) {
            val t = line.trim()
            when {
                t.startsWith(";TYPE:") ->
                    inTop = t.equals(";TYPE:Top surface", ignoreCase = true)
                t.matches(Regex("T\\d+")) ->
                    current = t.substring(1).toInt()
                inTop && current >= 0 && t.startsWith("G1 ") && t.contains(" E") ->
                    tools.add(current)
            }
        }
        return tools
    }

    /**
     * Count tool CHANGES that occur inside ";TYPE:Top surface" blocks across all
     * layers (diagnostics). A change is when the active tool transitions to a
     * different tool (T0→T1 counts as 1).
     */
    private fun toolChangesInTopBlocks(gcode: String): Int {
        var inTop = false
        var last = -1
        var changes = 0
        for (line in gcode.lineSequence()) {
            val t = line.trim()
            if (t.startsWith(";TYPE:")) {
                inTop = t.equals(";TYPE:Top surface", ignoreCase = true)
                continue
            }
            if (inTop && t.matches(Regex("T\\d+"))) {
                val tool = t.substring(1).toInt()
                if (last >= 0 && tool != last) changes++
                last = tool
            }
        }
        return changes
    }

    // ─── Slice helpers ───────────────────────────────────────────────────────

    /**
     * Builds a SliceConfig sized for the U1 physical count (4 extruders) with the
     * wipe tower ENABLED. The prior branch's test disabled the wipe tower to mask
     * an `append_tcr unexpected toolchange` crash — this class must not.
     */
    private fun makeConfig(recipe: String = "") = SliceConfig(
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

    /**
     * Copies the calib cube asset to the device cache and returns a File.
     * Asset path is root-level in the test APK, loaded from the instrumentation
     * context (not targetContext) per the pattern in SlicingIntegrationTest.
     */
    private fun copyCalibCube(): File {
        val out = File(cacheDir, ASSET)
        InstrumentationRegistry.getInstrumentation().context
            .assets.open(ASSET)
            .use { i -> out.outputStream().use { i.copyTo(it) } }
        return out
    }

    private fun loadCalibCube() {
        val asset = copyCalibCube()
        assertTrue("loadModel must succeed", lib.loadModel(asset.absolutePath))
    }

    private fun sliceAndRead(recipe: String = ""): String {
        val result = lib.slice(makeConfig(recipe)) ?: error("slice() returned null")
        assertTrue(
            "slice must succeed with wipe tower ON (guards against the historical " +
                "WipeTowerIntegration::append_tcr unexpected-toolchange crash), " +
                "error='${result.errorMessage}'",
            result.success,
        )
        return File(result.gcodePath).readText()
    }

    // ─── Tests ───────────────────────────────────────────────────────────────

    /**
     * CONTROL (must PASS): an unmixed slice with the wipe tower ON must slice
     * cleanly, and its top-surface tool set must stay a SUBSET of the dual-colour
     * fixture's own tools {T0, T1}. The fixture legitimately tops out both colour
     * regions in the same layers, so a "single tool per top layer" assertion would
     * be wrong here — the control instead guards that no mix tools (T2/T3) appear
     * in top blocks when no mix is assigned.
     */
    @Test
    fun noMix_wipeTowerOn_slicesCleanAndTopSingleTool() {
        loadCalibCube()
        val g = sliceAndRead()
        val tools = topSurfaceTools(g)
        assertTrue(
            "unmixed slice of the dual-colour fixture must keep top-surface tools " +
                "within the file's own {0, 1}; got $tools. " +
                "per-layer: ${perLayerTopToolSummary(g)}",
            tools.all { it in setOf(0, 1) },
        )
    }

    /**
     * RED test: a 2-component SAME_LAYER_DOTS mix (E3+E4 @ 50/50 → tools T2/T3,
     * deliberately tools the dual-colour fixture does NOT use) assigned to ONE
     * object only, wipe tower ON, must (a) slice without the wipe-tower crash and
     * (b) produce at least one layer whose TOP-surface lines contain BOTH T2 and
     * T3. Keying on the unused T2/T3 pair makes the gate immune to the fixture's
     * own T0/T1 regions co-occurring in top layers (which is normal, not mixing).
     * Currently FAILS on (b): SAME_LAYER_DOTS splits body infill within a layer
     * but TOP solid infill stays single-tool.
     */
    @Test
    fun dotsMix_oneObject_wipeTowerOn_topSurfaceMixesWithinLayer() {
        loadCalibCube()

        // Log fixture structure and pick the assignment target.
        val objCount = lib.nativeGetObjectCount()
        Log.i(TAG, "fixture object count = $objCount")
        for (o in 0 until objCount) {
            Log.i(TAG, "object $o volume count = ${lib.nativeGetVolumeCount(o)}")
        }

        val (mixSlot, recipe) = SurfaceColorMixTestSupport.buildRecipeAndSlot(
            componentSlots = listOf(3, 4),
            weights = listOf(50, 50),
            distributionMode = MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS,
        )

        if (objCount >= 2) {
            // Assign ONLY object 0 (all its volumes) to the mix; leave object 1 untouched.
            val vols = lib.nativeGetVolumeCount(0)
            for (v in 0 until vols) {
                assertTrue(
                    "nativeSetVolumeExtruder(0, $v, $mixSlot) must succeed",
                    lib.nativeSetVolumeExtruder(0, v, mixSlot),
                )
            }
            Log.i(TAG, "assigned object 0 ($vols volumes) to mix slot $mixSlot")
        } else {
            // Single object: assign ONLY volume 0 to the mix.
            assertTrue("fixture must have at least 1 object", objCount == 1)
            assertTrue(
                "nativeSetVolumeExtruder(0, 0, $mixSlot) must succeed",
                lib.nativeSetVolumeExtruder(0, 0, mixSlot),
            )
            Log.i(TAG, "assigned object 0 / volume 0 to mix slot $mixSlot " +
                "(object has ${lib.nativeGetVolumeCount(0)} volumes)")
        }

        // (a) Slice must succeed with the wipe tower ON.
        val g = sliceAndRead(recipe)

        // (b) RED gate: at least one layer must place BOTH mix-component tools
        // (T2 and T3 — tools the dual-colour file does not use) on its top-surface lines.
        val mixedLayers = layersWithToolPairInTop(g, 2, 3)
        val bodyPairLayers = layersWithToolPairInBody(g, 2, 3)
        val tools = topSurfaceTools(g)
        val allTools = wholeFileToolSet(g)
        val changes = toolChangesInTopBlocks(g)
        val allLabels = allTypeLabels(g)
        val first30Summary = perLayerToolTypeSummary(g, maxLayers = 30)
        // Compute last-10-layers summary by finding the last 10 layer indices with extrusion
        val last10Summary = run {
            val layerData = mutableMapOf<Int, MutableMap<String, MutableSet<Int>>>()
            var layerIdx = -1
            var currentTool = -1
            var currentType = ""
            val toolRe = Regex("T\\d+")
            for (line in g.lineSequence()) {
                val t = line.trim()
                when {
                    t.startsWith(";LAYER_CHANGE") -> { layerIdx++; currentType = "" }
                    t.startsWith(";TYPE:") -> { currentType = t.removePrefix(";TYPE:") }
                    t.matches(toolRe) -> { currentTool = t.substring(1).toInt() }
                    layerIdx >= 0 && currentType.isNotEmpty() && currentTool >= 0 &&
                        t.startsWith("G1 ") && t.contains(" E") -> {
                        layerData.getOrPut(layerIdx) { mutableMapOf() }
                            .getOrPut(currentType) { mutableSetOf() }.add(currentTool)
                    }
                }
            }
            val sortedLayers = layerData.keys.sorted()
            val lastLayers = sortedLayers.takeLast(10)
            val sb = StringBuilder()
            for (idx in lastLayers) {
                val typeMap = layerData[idx] ?: continue
                sb.append("L$idx{")
                typeMap.entries.sortedBy { it.key }.forEachIndexed { i, (label, ts) ->
                    if (i > 0) sb.append(", ")
                    sb.append("$label:${ts.sorted()}")
                }
                sb.append("}")
            }
            sb.toString().ifEmpty { "(no extrusion lines found)" }
        }
        assertTrue(
            "SAME_LAYER_DOTS mix (components T2+T3) must place BOTH T2 and T3 within " +
                "at least one layer's ;TYPE:Top surface lines, got $mixedLayers such layers. " +
                "Diagnostics: top-surface tool set across all layers = $tools, " +
                "whole-file tool set = $allTools, " +
                "layers with BOTH T2+T3 in BODY (non-top, non-tower) sections = $bodyPairLayers, " +
                "tool changes inside top blocks = $changes, " +
                "distinct TYPE labels in file = $allLabels, " +
                "per-layer top-tool sets: ${perLayerTopToolSummary(g)}\n" +
                "per-type-tool summary first 30 layers: $first30Summary\n" +
                "per-type-tool summary last 10 layers: $last10Summary",
            mixedLayers >= 1,
        )
    }
}
