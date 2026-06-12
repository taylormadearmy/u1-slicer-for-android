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
 * RED tests for the per-mix top-surface settings (TopMixMode PROPORTIONAL/DITHER,
 * fineTopLines, ironingGlaze) — Task 4 of the 2026-06-12 top-surface mix-modes plan.
 *
 * The Kotlin side (Task 1) already serializes the t/f/i recipe tokens and the engine
 * (Task 3) parses them, but the engine does NOT yet act on them: every mode behaves
 * as v1 STRIPES, no ironing glaze is emitted, and fine top lines do not narrow the
 * extrusion width. Tests 1-4 must therefore FAIL on their behavioural gates today
 * and go green after Tasks 5-7 land in the rebuilt `.so`. Test 5 is the drift guard:
 * default settings must keep the existing stripes behaviour green before AND after.
 *
 * Fixture: `calib-cube-10-dual-colour-merged.3mf` (dual-colour cube, file tools
 * T0/T1). The mix recipe targets slots 3+4 → tools T2/T3, which the file does NOT
 * use, so any T2/T3 co-occurrence in top blocks is attributable to the mix. Mirrors
 * TopSurfaceMixWipeTowerTest's fixture/assignment/scanning conventions (helpers are
 * duplicated privately here; that class must not be modified).
 *
 * Discriminator notes:
 *  - WHY ALTERNATION-COUNTING CAN NEVER WORK (do not reintroduce it): the engine
 *    routes each tool's pieces to per-tool ISLANDS, so every ;TYPE:Top surface
 *    block is single-tool and toolchanges occur BETWEEN ;TYPE: blocks, never
 *    inside them — in EVERY mode (stripes, proportional, dither alike). Any
 *    metric counting T-line transitions "inside top blocks" is structurally
 *    always 0 and cannot discriminate modes. The valid discriminator is the
 *    EXTRUSION-RUN COUNT inside top blocks: a run is a maximal sequence of
 *    extruding G1 moves (positive E, via isExtrudingMove) not interrupted by a
 *    travel (G1 without E / G0) or a block/layer boundary. Splitting or dashing
 *    lines forces extra travels between a tool's non-adjacent pieces, so run
 *    counts grow even though all pieces sit in one single-tool block.
 *  - Test 1 (PROPORTIONAL): stripes assigns whole lines round-robin (one run per
 *    printed line-chain); proportional splits every line at the cumulative-weight
 *    boundary, so each line contributes ≥2 pieces ⇒ extra travels ⇒ the per-layer
 *    run count must EXCEED the stripes control's for the same layer. Today
 *    PROPORTIONAL degrades to stripes → identical run counts → RED on the gate.
 *    Both slices happen inside the test.
 *  - Test 2 (DITHER): each scattered halftone dash is its own travel-separated
 *    run (a tool's dashes are non-adjacent), so the dither slice's TOTAL top-block
 *    run count must be ≥ 2× the stripes control's (2× not 3×: the unmixed second
 *    cube's constant baseline runs are included in both totals — see the test's
 *    own KDoc for the fixture math). Today dither degrades to stripes → equal
 *    run counts → RED on the gate.
 *  - Test 3 (fine): the width metric prefers explicit `;WIDTH:<mm>` annotations
 *    inside top blocks (Orca's G-code processor annotations); if a slice carries
 *    none, it falls back to the E-per-XY-mm ratio of positive-E moves inside top
 *    blocks (proportional to line width at fixed layer height/filament). Which
 *    source was used is logged and embedded in the failure message.
 */
@RunWith(AndroidJUnit4::class)
class TopSurfaceMixModesTest {

    private companion object {
        const val TAG = "TopSurfaceMixModes"
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

    // ─── G-code scanning helpers (private copies; see TopSurfaceMixWipeTowerTest) ──

    /** True when [line] is a G1 move depositing material (positive E; retracts excluded). */
    private val extrudingMoveRe = Regex("""G1 .*\bE\+?(\d*\.?\d+)""")
    private fun isExtrudingMove(line: String): Boolean =
        extrudingMoveRe.containsMatchIn(line)

    private val toolRe = Regex("T\\d+")

    /** Per-layer top-surface stats: tools extruding + travel-separated extrusion-run count. */
    private data class LayerTopStats(val tools: Set<Int>, val runCount: Int)

    /**
     * One pass over [gcode] collecting, for every ;LAYER_CHANGE block, the set of
     * tools that extruded inside ";TYPE:Top surface" sections and the number of
     * extrusion RUNS there. A run is a maximal sequence of extruding G1 moves
     * (positive E, via [isExtrudingMove]) not interrupted by a travel (G1 without
     * E / G0), a tool change, a ;TYPE: section change, or a layer boundary.
     * Travel separation is what makes the metric mode-discriminating: with
     * per-tool island routing every top block is single-tool, but split lines
     * (proportional) and scattered dashes (dither) still force extra travels
     * between a tool's non-adjacent pieces, multiplying the run count.
     */
    private fun perLayerTopStats(gcode: String): Map<Int, LayerTopStats> {
        val out = mutableMapOf<Int, LayerTopStats>()
        var layerIdx = -1
        var currentTool = -1
        var inTop = false
        var runOpen = false
        var runTool = -1
        var runCount = 0
        val layerTools = mutableSetOf<Int>()

        fun closeLayer() {
            if (layerIdx >= 0 && layerTools.isNotEmpty()) {
                out[layerIdx] = LayerTopStats(layerTools.toSet(), runCount)
            }
            layerTools.clear()
            runCount = 0
            runOpen = false
        }

        for (line in gcode.lineSequence()) {
            val t = line.trim()
            when {
                t.startsWith(";LAYER_CHANGE") -> {
                    closeLayer()
                    layerIdx++
                    inTop = false
                }
                t.startsWith(";TYPE:") -> {
                    inTop = t.equals(";TYPE:Top surface", ignoreCase = true)
                    runOpen = false
                }
                t.matches(toolRe) -> {
                    currentTool = t.substring(1).toInt()
                }
                inTop && layerIdx >= 0 && currentTool >= 0 && isExtrudingMove(t) -> {
                    layerTools.add(currentTool)
                    if (!runOpen || runTool != currentTool) {
                        runCount++
                        runTool = currentTool
                        runOpen = true
                    }
                }
                // Travel (G0, or G1 without positive E) inside a top block ends the run.
                inTop && (t.startsWith("G0") || t.startsWith("G1")) -> {
                    runOpen = false
                }
            }
        }
        closeLayer()
        return out
    }

    /** Count layers whose top-surface extrusion lines used BOTH tool [a] AND tool [b]. */
    private fun layersWithToolPairInTop(gcode: String, a: Int, b: Int): Int =
        perLayerTopStats(gcode).values.count { a in it.tools && b in it.tools }

    /**
     * Total travel-separated extrusion-run count inside ";TYPE:Top surface" blocks
     * across the whole file (sum of [LayerTopStats.runCount] over all layers).
     * NOTE: do NOT replace this with tool-CHANGE counting inside top blocks — the
     * engine's per-tool island routing makes every top block single-tool, so that
     * count is structurally always 0 in every mode (see class KDoc).
     */
    private fun totalTopBlockRunCount(gcode: String): Int =
        perLayerTopStats(gcode).values.sumOf { it.runCount }

    /** Tools seen on extrusion lines within ";TYPE:Top surface" blocks. */
    private fun topSurfaceTools(gcode: String): Set<Int> {
        val tools = mutableSetOf<Int>()
        var inTop = false
        var current = -1
        for (line in gcode.lineSequence()) {
            val t = line.trim()
            when {
                t.startsWith(";TYPE:") ->
                    inTop = t.equals(";TYPE:Top surface", ignoreCase = true)
                t.matches(toolRe) ->
                    current = t.substring(1).toInt()
                inTop && current >= 0 && isExtrudingMove(t) ->
                    tools.add(current)
            }
        }
        return tools
    }

    /** Tools extruding inside ";TYPE:Ironing" blocks across the file. */
    private fun toolsInIroningBlocks(gcode: String): Set<Int> {
        val tools = mutableSetOf<Int>()
        var inIroning = false
        var current = -1
        for (line in gcode.lineSequence()) {
            val t = line.trim()
            when {
                t.startsWith(";TYPE:") ->
                    inIroning = t.removePrefix(";TYPE:")
                        .contains("ironing", ignoreCase = true)
                t.matches(toolRe) ->
                    current = t.substring(1).toInt()
                inIroning && current >= 0 && isExtrudingMove(t) ->
                    tools.add(current)
            }
        }
        return tools
    }

    /** All distinct ;TYPE: labels (diagnostics — e.g. to report whether ironing exists). */
    private fun allTypeLabels(gcode: String): Set<String> {
        val labels = mutableSetOf<String>()
        for (line in gcode.lineSequence()) {
            val t = line.trim()
            if (t.startsWith(";TYPE:")) labels.add(t.removePrefix(";TYPE:"))
        }
        return labels
    }

    // ─── Width measurement (test 3) ──────────────────────────────────────────

    private data class TopWidthSample(val widths: List<Double>, val source: String)

    private val widthAnnoRe = Regex("""^;WIDTH:([\d.]+)""")
    private val xRe = Regex("""\bX(-?\d*\.?\d+)""")
    private val yRe = Regex("""\bY(-?\d*\.?\d+)""")
    private val eRe = Regex("""\bE(-?\d*\.?\d+)""")

    /**
     * Collects width-proportional samples for extrusion inside top-surface blocks.
     * Prefers explicit `;WIDTH:<mm>` annotations seen while a top block is active
     * (one sample per annotation line); when a slice carries none, falls back to
     * the E-per-XY-mm ratio of each positive-E move with >0.5 mm XY travel inside
     * top blocks (handles both relative M83 and absolute M82 E, plus G92 E resets).
     * The ratio is proportional to line width at fixed layer height, so the
     * fine-vs-control RATIO gate is valid for either source.
     */
    private fun topBlockWidthSamples(gcode: String): TopWidthSample {
        val annotated = mutableListOf<Double>()
        val derived = mutableListOf<Double>()
        var inTop = false
        var relativeE = false
        var x = 0.0
        var y = 0.0
        var eAbs = 0.0
        for (line in gcode.lineSequence()) {
            val t = line.trim()
            if (t.startsWith(";TYPE:")) {
                inTop = t.equals(";TYPE:Top surface", ignoreCase = true)
                continue
            }
            if (inTop) {
                widthAnnoRe.find(t)?.let { m ->
                    m.groupValues[1].toDoubleOrNull()?.let { annotated.add(it) }
                }
            }
            if (t.startsWith("M83")) { relativeE = true; continue }
            if (t.startsWith("M82")) { relativeE = false; continue }
            if (t.startsWith("G92")) {
                eRe.find(t)?.groupValues?.get(1)?.toDoubleOrNull()?.let { eAbs = it }
                continue
            }
            if (!t.startsWith("G1") && !t.startsWith("G0")) continue
            val nx = xRe.find(t)?.groupValues?.get(1)?.toDoubleOrNull()
            val ny = yRe.find(t)?.groupValues?.get(1)?.toDoubleOrNull()
            val ne = eRe.find(t)?.groupValues?.get(1)?.toDoubleOrNull()
            val dx = if (nx != null) nx - x else 0.0
            val dy = if (ny != null) ny - y else 0.0
            if (nx != null) x = nx
            if (ny != null) y = ny
            if (ne != null) {
                val deltaE = if (relativeE) ne else ne - eAbs
                if (!relativeE) eAbs = ne
                val dist = Math.hypot(dx, dy)
                if (inTop && deltaE > 0 && dist > 0.5) {
                    derived.add(deltaE / dist)
                }
            }
        }
        return if (annotated.size >= 5) TopWidthSample(annotated, "WIDTH-annotations")
        else TopWidthSample(derived, "derived-E-per-XY-mm")
    }

    private fun median(values: List<Double>): Double {
        require(values.isNotEmpty()) { "no width samples" }
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
    }

    // ─── Slice helpers ───────────────────────────────────────────────────────

    /** SliceConfig matching TopSurfaceMixWipeTowerTest: 4 extruders, wipe tower ON. */
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

    private fun copyCalibCube(): File {
        val out = File(cacheDir, ASSET)
        InstrumentationRegistry.getInstrumentation().context
            .assets.open(ASSET)
            .use { i -> out.outputStream().use { i.copyTo(it) } }
        return out
    }

    /**
     * Fresh model load + mix-slot assignment (same logic as TopSurfaceMixWipeTowerTest):
     * with >=2 objects, all volumes of object 0 get the mix slot; with a single
     * object, only volume 0 does. clearModel() first so back-to-back control/mixed
     * slices within one test never share native state.
     */
    private fun loadAndAssignMix(mixSlot: Int) {
        lib.clearModel()
        val asset = copyCalibCube()
        assertTrue("loadModel must succeed", lib.loadModel(asset.absolutePath))
        val objCount = lib.nativeGetObjectCount()
        if (objCount >= 2) {
            val vols = lib.nativeGetVolumeCount(0)
            for (v in 0 until vols) {
                assertTrue(
                    "nativeSetVolumeExtruder(0, $v, $mixSlot) must succeed",
                    lib.nativeSetVolumeExtruder(0, v, mixSlot),
                )
            }
            Log.i(TAG, "assigned object 0 ($vols volumes) to mix slot $mixSlot")
        } else {
            assertTrue("fixture must have at least 1 object", objCount == 1)
            assertTrue(
                "nativeSetVolumeExtruder(0, 0, $mixSlot) must succeed",
                lib.nativeSetVolumeExtruder(0, 0, mixSlot),
            )
            Log.i(TAG, "assigned object 0 / volume 0 to mix slot $mixSlot")
        }
    }

    /**
     * Builds the recipe for the given top-surface settings, loads + assigns the
     * fixture fresh, slices with the wipe tower ON, and returns the G-code text.
     */
    private fun sliceWithSettings(
        topMixMode: MixedFilamentRow.TopMixMode,
        fineTopLines: Boolean = false,
        ironingGlaze: Boolean = false,
        weights: List<Int> = listOf(50, 50),
    ): String {
        val (mixSlot, recipe) = SurfaceColorMixTestSupport.buildRecipeAndSlot(
            componentSlots = listOf(3, 4),
            weights = weights,
            distributionMode = MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS,
            topMixMode = topMixMode,
            fineTopLines = fineTopLines,
            ironingGlaze = ironingGlaze,
        )
        Log.i(TAG, "recipe ($topMixMode, fine=$fineTopLines, glaze=$ironingGlaze): $recipe")
        loadAndAssignMix(mixSlot)
        val result = lib.slice(makeConfig(recipe)) ?: error("slice() returned null")
        assertTrue(
            "slice must succeed (mode=$topMixMode fine=$fineTopLines glaze=$ironingGlaze), " +
                "error='${result.errorMessage}'",
            result.success,
        )
        return File(result.gcodePath).readText()
    }

    private fun statsSummary(stats: Map<Int, LayerTopStats>): String =
        stats.entries.sortedBy { it.key }.joinToString(" ") { (l, s) ->
            "L$l{tools=${s.tools.sorted()},runs=${s.runCount}}"
        }.ifEmpty { "(no top-surface extrusion)" }

    // ─── Tests ───────────────────────────────────────────────────────────────

    /**
     * RED gate 1 — PROPORTIONAL splits WITHIN each top line: at least one layer
     * must (a) place both mix tools T2+T3 on its top-surface lines AND (b) show a
     * travel-separated extrusion-run count strictly greater than the STRIPES
     * control's run count for the same layer (every line contributing >=2 pieces
     * forces extra travels between a tool's non-adjacent pieces, even though all
     * its pieces sit in one single-tool island block — see class KDoc). Today the
     * engine ignores t1 → proportional output is identical to stripes → run
     * counts match on every layer → RED.
     */
    @Test
    fun proportional_topLinesSplitWithinLine() {
        val controlG = sliceWithSettings(MixedFilamentRow.TopMixMode.STRIPES)
        val mixedG = sliceWithSettings(MixedFilamentRow.TopMixMode.PROPORTIONAL)
        val controlStats = perLayerTopStats(controlG)
        val mixedStats = perLayerTopStats(mixedG)
        val qualifying = mixedStats.entries.count { (layer, s) ->
            2 in s.tools && 3 in s.tools &&
                (controlStats[layer]?.let { s.runCount > it.runCount } ?: false)
        }
        assertTrue(
            "PROPORTIONAL must split top lines within-line: expected >=1 layer with " +
                "both T2+T3 in top blocks AND more same-tool runs than the stripes " +
                "control, got $qualifying. " +
                "control per-layer: ${statsSummary(controlStats)} | " +
                "proportional per-layer: ${statsSummary(mixedStats)}",
            qualifying >= 1,
        )
    }

    /**
     * RED gate 2 — DITHER chops lines into halftone dashes: every scattered dash
     * is its own travel-separated extrusion run (a tool's dashes are non-adjacent),
     * so the dither slice's TOTAL top-block run count must be >= 2x the STRIPES
     * control's. Tool-alternation counting inside top blocks is NOT used because
     * per-tool island routing makes it structurally always 0 in every mode (see
     * class KDoc). Today dither degrades to stripes → equal run counts → RED
     * (control run count asserted > 0 first so 2x0 can't trivially pass).
     *
     * Gate calibration (2x, not 3x) — fixture math: both totals include the SAME
     * unmixed second cube, whose constant baseline runs dilute the mixed cube's
     * ratio. The mixed 10mm calib cube's top lines are ~9mm at ~0.43mm pitch
     * (~23 lines/layer); with 1.5mm dashes that's ~6 dashes per line, and the
     * ordinal-Bayer checkerboard at 50/50 alternates tools per dash, so each mixed
     * line yields multiple runs vs the stripes control's one whole-line run — but
     * averaged against the unmixed cube's unchanged baseline the achievable TOTAL
     * ratio sits well under 3x. 2x retains a huge margin over the broken behaviour,
     * which measured 0.92x (dither=210 vs control=228: the old spatial-cell Bayer,
     * with cells >= the line pitch, put adjacent lines on the same tool so monotonic
     * fill merged them into FEWER runs than stripes).
     */
    @Test
    fun dither_topRunCountFarExceedsStripes() {
        val controlG = sliceWithSettings(MixedFilamentRow.TopMixMode.STRIPES)
        val mixedG = sliceWithSettings(MixedFilamentRow.TopMixMode.DITHER)
        val controlRuns = totalTopBlockRunCount(controlG)
        val ditherRuns = totalTopBlockRunCount(mixedG)
        assertTrue(
            "stripes control must have travel-separated extrusion runs inside top " +
                "blocks (precondition), got $controlRuns runs; " +
                "top tools=${topSurfaceTools(controlG)}",
            controlRuns > 0,
        )
        assertTrue(
            "DITHER must scatter top lines into dashes: total top-block extrusion-run " +
                "count must be >= 2x the stripes control's: dither=$ditherRuns vs " +
                "control=$controlRuns (needed >= ${2 * controlRuns}). " +
                "dither top tools=${topSurfaceTools(mixedG)} | " +
                "control per-layer: ${statsSummary(perLayerTopStats(controlG))} | " +
                "dither per-layer: ${statsSummary(perLayerTopStats(mixedG))}",
            ditherRuns >= 2 * controlRuns,
        )
    }

    /**
     * RED gate 3 — fineTopLines halves the top-surface extrusion width: the median
     * width metric of the fine slice must be <= 0.6x the control's. The metric is
     * `;WIDTH:` annotations inside top blocks when present, otherwise the derived
     * E-per-XY-mm ratio (source embedded in the failure message). Today f1 is
     * ignored → identical widths → RED.
     */
    @Test
    fun fineTopLines_halvesTopSurfaceWidth() {
        val controlG = sliceWithSettings(MixedFilamentRow.TopMixMode.STRIPES, fineTopLines = false)
        val fineG = sliceWithSettings(MixedFilamentRow.TopMixMode.STRIPES, fineTopLines = true)
        val control = topBlockWidthSamples(controlG)
        val fine = topBlockWidthSamples(fineG)
        Log.i(TAG, "width source: control=${control.source} (${control.widths.size} samples), " +
            "fine=${fine.source} (${fine.widths.size} samples)")
        assertTrue(
            "control slice must yield top-block width samples " +
                "(source=${control.source}, n=${control.widths.size})",
            control.widths.isNotEmpty(),
        )
        assertTrue(
            "fine slice must yield top-block width samples " +
                "(source=${fine.source}, n=${fine.widths.size})",
            fine.widths.isNotEmpty(),
        )
        val controlMedian = median(control.widths)
        val fineMedian = median(fine.widths)
        assertTrue(
            "fineTopLines must narrow top-surface lines: median fine width " +
                "$fineMedian (source=${fine.source}, n=${fine.widths.size}) must be " +
                "<= 0.6x control median $controlMedian (source=${control.source}, " +
                "n=${control.widths.size}); ratio=${fineMedian / controlMedian}",
            fineMedian <= 0.6 * controlMedian,
        )
    }

    /**
     * RED gate 4 — ironingGlaze emits a multi-tool ironing pass over mixed top
     * surfaces: the G-code must contain ;TYPE:Ironing blocks AND >=2 distinct tools
     * must extrude inside them (70/30 weights also exercise weighting). Today no
     * ironing is emitted at all for this config → RED. The failure message reports
     * whether ANY ironing label appears so the report can state today's baseline.
     */
    @Test
    fun ironingGlaze_bothToolsInsideIroningBlocks() {
        val g = sliceWithSettings(
            MixedFilamentRow.TopMixMode.STRIPES,
            ironingGlaze = true,
            weights = listOf(70, 30),
        )
        val labels = allTypeLabels(g)
        val ironingLabels = labels.filter { it.contains("ironing", ignoreCase = true) }
        val ironingTools = toolsInIroningBlocks(g)
        assertTrue(
            "ironingGlaze must emit ;TYPE:Ironing blocks with >=2 distinct tools " +
                "extruding inside them; ironing labels found=$ironingLabels, tools " +
                "inside ironing blocks=$ironingTools, all TYPE labels=$labels, " +
                "top tools=${topSurfaceTools(g)}",
            ironingLabels.isNotEmpty() && ironingTools.size >= 2,
        )
    }

    /**
     * GREEN drift guard — default settings (STRIPES, no fine, no glaze) must keep
     * satisfying the existing TopSurfaceMixWipeTowerTest gate: at least one layer
     * places BOTH mix tools T2+T3 on its top-surface lines. Must stay green before
     * and after the engine work.
     */
    @Test
    fun defaults_unchanged_stripesStillGreen() {
        val g = sliceWithSettings(MixedFilamentRow.TopMixMode.STRIPES)
        val mixedLayers = layersWithToolPairInTop(g, 2, 3)
        assertTrue(
            "default (t0,f0,i0) recipe must keep the existing stripes behaviour: " +
                ">=1 layer with both T2+T3 in top-surface blocks, got $mixedLayers. " +
                "per-layer: ${statsSummary(perLayerTopStats(g))}",
            mixedLayers >= 1,
        )
    }
}
