package com.u1.slicer.slicing

import android.util.Log
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
 * M3-B Phase B HEADLINE GATE — proves a mix slot ACTUALLY BLENDS at slice time.
 *
 * This is the deliverable that the old [MixSlotSliceIntegrationTest] failed to be: it does
 * NOT merely check that the recipe string survives into the G-code config dump (the
 * false-confidence trap). It slices a multi-layer mesh painted entirely with ONE mix slot
 * (E1+E2 @ 50% LAYER_CYCLE) and asserts the engine resolved the mix to a REAL optical blend:
 *
 *   - BOTH component physical tools are used (T0 count > 0 AND T1 count > 0), and
 *   - the active tool ALTERNATES across the printed layers in the painted region
 *     (a LAYER_CYCLE blend prints layer-0 with one component, layer-1 with the other, …).
 *
 * A broken / un-blended mix uses a SINGLE tool for the whole region — exactly what happened
 * when `filament_colour` was padded to `4 + nMix` so the engine's `num_physical` swelled past
 * the mix's virtual filament id, and `mixed_index_from_filament_id` returned -1 (treat as
 * physical). The fix (PaintedMeshWriter.buildProjectSettings keeps filament_colour at the 4
 * physical entries → num_physical == 4) lets state 5 resolve to virtual filament 5.
 *
 * The whole geometry is one box painted to the mix slot (no physical-only regions) so any
 * tool change observed MUST come from the mix resolving, not from a neighbouring solid region.
 *
 * If the mix does NOT blend, the test fails with the per-layer tool sequence so the failure
 * is self-diagnosing — do NOT weaken the assertion.
 */
@RunWith(AndroidJUnit4::class)
class MixSlotBlendVerificationTest {

    private lateinit var lib: NativeLibrary
    private lateinit var out3mf: File

    @Before
    fun setup() {
        assertTrue("Native library must be loaded on device (arm64 required)", NativeLibrary.isLoaded)
        lib = NativeLibrary()
        lib.clearModel()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        out3mf = File(ctx.cacheDir, "mix_slot_blend_${System.currentTimeMillis()}.3mf")
    }

    @After
    fun teardown() {
        lib.clearModel()
        out3mf.delete()
    }

    /**
     * Closed cuboid spanning [ox, ox+w] × [0, d] × [0, h], 12 outward-facing triangles
     * (2 per face × 6 faces). Tall enough that at 0.2mm layer height we get many layers
     * so LAYER_CYCLE alternation is observable.
     */
    private fun box(ox: Float, w: Float, d: Float, h: Float): FloatArray {
        val x0 = ox; val x1 = ox + w
        val y0 = 0f; val y1 = d
        val z0 = 0f; val z1 = h
        return floatArrayOf(
            // Bottom (-Z)
            x0,y0,z0,  x1,y1,z0,  x1,y0,z0,
            x0,y0,z0,  x0,y1,z0,  x1,y1,z0,
            // Top (+Z)
            x0,y0,z1,  x1,y0,z1,  x1,y1,z1,
            x0,y0,z1,  x1,y1,z1,  x0,y1,z1,
            // Front (-Y)
            x0,y0,z0,  x1,y0,z0,  x1,y0,z1,
            x0,y0,z0,  x1,y0,z1,  x0,y0,z1,
            // Back (+Y)
            x0,y1,z0,  x1,y1,z1,  x1,y1,z0,
            x0,y1,z0,  x0,y1,z1,  x1,y1,z1,
            // Left (-X)
            x0,y0,z0,  x0,y0,z1,  x0,y1,z1,
            x0,y0,z0,  x0,y1,z1,  x0,y1,z0,
            // Right (+X)
            x1,y0,z0,  x1,y1,z1,  x1,y0,z1,
            x1,y0,z0,  x1,y1,z0,  x1,y1,z1,
        )
    }

    /**
     * SliceConfig sized to the TRUE physical count (4). This is what production uses —
     * SlicerViewModel coerces a painted model's detected extruder count to [1,4]. The
     * mix is a VIRTUAL filament defined by the recipe, NOT an extra physical extruder, so
     * extruderCount stays 4. (The old MixSlotSliceIntegrationTest used 5 here, which by
     * itself sized filament_diameter to 5 → num_physical=5 → mix never blended.)
     */
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
        // Wipe tower disabled: the painted 3MF is not a Snapmaker profile, so the
        // calibrated flush_volumes_matrix is never applied; the wipe tower path would
        // index an under-sized matrix. Segmentation + G-code generation are unaffected.
        wipeTowerEnabled = false,
        mixedFilamentDefinitions = recipe,
    )

    @Test
    fun mixSlot_resolvesToRealLayerAlternatingBlend() {
        // ── 1. One tall box, EVERY triangle painted to mix slot 4 (engine state 5) ──
        // 8mm tall @ 0.2mm = ~40 layers — plenty for LAYER_CYCLE alternation to show.
        val positions = box(ox = 0f, w = 12f, d = 12f, h = 8f)
        val triCount = positions.size / 9
        val regionIds = IntArray(triCount) { 4 } // mix slot 4 → paint state 5

        val regions = (0..3).map { s ->
            AiRegion(id = s, label = "Slot ${s + 1}", suggestedColour = "#888888", slot = s)
        }
        // Physical E1=red, E2=green, E3=blue, E4=yellow. Mix 4 = E1+E2.
        val printerColours = listOf("#FF0000", "#00FF00", "#0000FF", "#FFFF00")
        // mixDisplayColours is now a no-op for filament_colour (Route K), but pass it as
        // production does to prove appending it back would NOT be reintroduced silently.
        val mixDisplayColours = listOf("#808000")

        PaintedMeshWriter.write(
            positions = positions,
            regionIds = regionIds,
            regions = regions,
            outputFile = out3mf,
            printerColours = printerColours,
            mixDisplayColours = mixDisplayColours,
        )
        assertTrue("Painted 3MF must be written (size > 0)", out3mf.length() > 0)

        // ── 2. Recipe: one project mix E1+E2 @ 50% LAYER_CYCLE ──────────────────────
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
        assertTrue("Manager must produce a non-empty recipe", recipe.isNotEmpty())

        // ── 3. Slice through the real config path (extruderCount = 4) ───────────────
        assertTrue("loadModel must succeed for the painted mix-slot 3MF", lib.loadModel(out3mf.absolutePath))
        val result = lib.slice(makeConfig(recipe))
        assertNotNull("slice() must not return null", result)
        result!!
        assertTrue(
            "GATE: slice must succeed. Engine error: '${result.errorMessage}'",
            result.success,
        )
        assertTrue("G-code path must be non-empty", result.gcodePath.isNotEmpty())

        val gcode = File(result.gcodePath).readText()
        assertTrue("G-code must be non-empty", gcode.isNotEmpty())

        // DIAGNOSTIC: dump the mixed_filament_definitions config-dump line + any "; filament_colour"
        // line so we can confirm what the engine actually received.
        gcode.lineSequence().filter {
            it.contains("mixed_filament_definitions") || it.startsWith("; filament_colour") ||
                it.startsWith("; filament_diameter")
        }.forEach { Log.i("MixBlendGate", "CFGDUMP: ${it.trim()}") }

        // ── 4. Parse per-layer tool usage ───────────────────────────────────────────
        // Walk the body. Track the active tool (last Tn seen) and which tools are used
        // on each printed layer (between ;LAYER_CHANGE markers, while extruding).
        val toolRegex = Regex("""^T(\d+)\b""")
        val layerToolSets = mutableListOf<MutableSet<Int>>()
        var currentLayerTools: MutableSet<Int>? = null
        var activeTool = -1
        var sawAnyExtrude = false
        val totalToolCounts = IntArray(8)
        // Tool-change transitions over the whole print. A real LAYER_CYCLE blend flips
        // between the two components repeatedly; a non-blending mix that collapsed to one
        // physical extruder emits at most ONE Tn at the top and never flips.
        var t0t1Transitions = 0
        var lastTool = -1

        for (raw in gcode.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith(";LAYER_CHANGE")) {
                currentLayerTools = mutableSetOf()
                layerToolSets.add(currentLayerTools)
                continue
            }
            val tm = toolRegex.find(line)
            if (tm != null) {
                val t = tm.groupValues[1].toIntOrNull() ?: continue
                if (t in 0..7) {
                    if ((t == 0 || t == 1) && (lastTool == 0 || lastTool == 1) && t != lastTool)
                        t0t1Transitions++
                    activeTool = t
                    lastTool = t
                    totalToolCounts[t]++
                }
                continue
            }
            // An extrusion move (G1 with an E parameter, positive) marks the active tool
            // as "used" on the current layer.
            if (currentLayerTools != null && activeTool >= 0 &&
                (line.startsWith("G1 ") || line.startsWith("G0 ")) && line.contains(" E")
            ) {
                currentLayerTools.add(activeTool)
                sawAnyExtrude = true
            }
        }

        // ── 5. Diagnostics (always logged so a failure is self-explaining) ──────────
        val perLayerSummary = layerToolSets
            .mapIndexed { i, set -> "L$i=${set.sorted()}" }
            .take(20)
            .joinToString(" ")
        val toolCountSummary = (0..7).filter { totalToolCounts[it] > 0 }
            .joinToString(", ") { "T$it=${totalToolCounts[it]}" }
        Log.i("MixBlendGate", "Total tool changes: $toolCountSummary")
        Log.i("MixBlendGate", "T0<->T1 transitions: $t0t1Transitions")
        Log.i("MixBlendGate", "Layers parsed: ${layerToolSets.size}; first 20: $perLayerSummary")

        val diag = "tool-change counts: [$toolCountSummary]; T0<->T1 transitions=$t0t1Transitions; " +
            "layers=${layerToolSets.size}; per-layer (first 20): $perLayerSummary"

        // ── 6. ASSERT REAL BLEND ────────────────────────────────────────────────────
        assertTrue("Sanity: G-code must contain extrusion moves. $diag", sawAnyExtrude)

        // (a) BOTH component physical tools must be used. A non-blending mix collapses to a
        //     single tool: pre-fix this was T0=1 / T1=0 (state 5 folded to physical 1 → T0).
        assertTrue(
            "GATE: mix must use component tool T0 (E1). If absent, the mix did not blend. $diag",
            totalToolCounts[0] > 0,
        )
        assertTrue(
            "GATE: mix must use component tool T1 (E2). If absent, the mix collapsed to a single " +
                "physical tool — the painted mix state was folded onto a physical extruder. $diag",
            totalToolCounts[1] > 0,
        )

        // (b) The two components must ALTERNATE repeatedly across the print — the defining
        //     behaviour of a LAYER_CYCLE blend (resolve() returns component_a on even layers,
        //     component_b on odd, per feature). A collapsed/un-blended mix prints one solid
        //     block and yields ~0 flips. Require many T0<->T1 transitions (one print has 40
        //     layers; we expect on the order of tens of flips, certainly far more than a
        //     handful). 8 is a conservative floor well above any incidental single switch.
        assertTrue(
            "GATE: LAYER_CYCLE blend must repeatedly alternate its two component tools. " +
                "Observed only $t0t1Transitions T0<->T1 transitions — a real blend flips on the " +
                "order of the layer count. Too few flips means the mix did not blend per layer. $diag",
            t0t1Transitions >= 8,
        )

        // (c) The split must be BALANCED — a 50% LAYER_CYCLE mix uses each component for
        //     roughly half the toolchanges. Guards against a degenerate "mostly one tool +
        //     one stray switch" that would pass (a)+(b) but isn't a real even blend.
        val minComponent = minOf(totalToolCounts[0], totalToolCounts[1])
        val maxComponent = maxOf(totalToolCounts[0], totalToolCounts[1])
        assertTrue(
            "GATE: 50% mix must use both components roughly evenly — got T0=${totalToolCounts[0]} " +
                "T1=${totalToolCounts[1]} (minor component < 30% of major). $diag",
            minComponent.toDouble() >= 0.30 * maxComponent,
        )

        // (d) No spurious tools (T2/T3) — the mix only references E1+E2.
        assertTrue(
            "GATE: mix must not use uninvolved tools T2/T3. T2=${totalToolCounts[2]} T3=${totalToolCounts[3]}. $diag",
            totalToolCounts[2] == 0 && totalToolCounts[3] == 0,
        )
    }
}
