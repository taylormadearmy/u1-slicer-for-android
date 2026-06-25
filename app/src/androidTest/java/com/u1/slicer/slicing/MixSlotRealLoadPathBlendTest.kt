package com.u1.slicer.slicing

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.SlicerViewModel
import com.u1.slicer.U1SlicerApplication
import com.u1.slicer.aipaint.AiRegion
import com.u1.slicer.aipaint.PaintedMeshWriter
import com.u1.slicer.aipaint.SegmentationCascade
import com.u1.slicer.data.MixedFilamentRow
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * M3-B Phase B HEADLINE GATE — the REAL-PATH version.
 *
 * Unlike [MixSlotBlendVerificationTest] (which calls `lib.loadModel(out3mf)` directly with a
 * hand-built SliceConfig of extruderCount=4 — a SYNTHETIC proxy that bypasses the load path),
 * this test drives the user's actual Smart Paint accept → load → slice flow:
 *
 *   1. Seed a 50/50 E1+E2 LAYER_CYCLE mix in the real [SlicerViewModel.mixedFilamentManager].
 *   2. Produce the painted whole-model 3MF the way [com.u1.slicer.aipaint.AiPaintViewModel.finalizePainting]
 *      does — every triangle painted to the mix slot (regionId = 4 → paint state 5),
 *      filament_colour = the 4 physical slot colours, plus the full-spectrum marker.
 *   3. **Load it via [SlicerViewModel.loadModelFromFile]** — the thing under test.
 *   4. **Slice via [SlicerViewModel.startSlicing]** — the real config-build path that
 *      sizes filament arrays from config.extruderCount and serializes the mix recipe.
 *
 * Assertions:
 *   (a) the loaded model declares the 4 physical filaments (config.extruderCount == 4),
 *   (b) the engine saw num_physical == 4 (G-code config dump filament_diameter has 4 entries),
 *   (c) the mix genuinely blends — both component tools present AND alternating across layers.
 *
 * The user's repro (grey preview, single tool, filament 5 only) happened because the load
 * path collapsed extruderCount → 1, sizing filament_colour/diameter to 1 → num_physical=1 →
 * the mix's virtual id (5) was treated as physical and never blended.
 */
@RunWith(AndroidJUnit4::class)
class MixSlotRealLoadPathBlendTest {

    private lateinit var application: U1SlicerApplication
    private lateinit var viewModel: SlicerViewModel
    private lateinit var painted3mf: File

    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setup() {
        assertTrue("Native library must be loaded on device (arm64 required)", NativeLibrary.isLoaded)
        application = targetContext.applicationContext as U1SlicerApplication
        viewModel = SlicerViewModel(application)
        painted3mf = File(targetContext.cacheDir, "mix_realpath_${System.currentTimeMillis()}.3mf")
    }

    @After
    fun teardown() {
        runCatching { viewModel.clearModel() }
        painted3mf.delete()
        // Clear any project mixes we seeded so a re-run / other tests start clean.
        runCatching {
            viewModel.mixedFilamentManager.projectMixes.value.forEach {
                viewModel.mixedFilamentManager.delete(it.id)
            }
        }
    }

    /** Closed cuboid spanning [ox, ox+w] × [0, d] × [0, h], 12 outward triangles. */
    private fun box(ox: Float, w: Float, d: Float, h: Float): FloatArray {
        val x0 = ox; val x1 = ox + w
        val y0 = 0f; val y1 = d
        val z0 = 0f; val z1 = h
        return floatArrayOf(
            x0,y0,z0,  x1,y1,z0,  x1,y0,z0,
            x0,y0,z0,  x0,y1,z0,  x1,y1,z0,
            x0,y0,z1,  x1,y0,z1,  x1,y1,z1,
            x0,y0,z1,  x1,y1,z1,  x0,y1,z1,
            x0,y0,z0,  x1,y0,z0,  x1,y0,z1,
            x0,y0,z0,  x1,y0,z1,  x0,y0,z1,
            x0,y1,z0,  x1,y1,z1,  x1,y1,z0,
            x0,y1,z0,  x0,y1,z1,  x1,y1,z1,
            x0,y0,z0,  x0,y0,z1,  x0,y1,z1,
            x0,y0,z0,  x0,y1,z1,  x0,y1,z0,
            x1,y0,z0,  x1,y1,z1,  x1,y0,z1,
            x1,y0,z0,  x1,y1,z0,  x1,y1,z1,
        )
    }

    private fun waitUntil(label: String, timeoutMs: Long = 120_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError("Timed out waiting for $label")
    }

    @Test
    fun mixSlot_throughRealLoadAndSlicePath_resolvesToRealBlend() {
        // ── 1. Seed a 50/50 E1+E2 LAYER_CYCLE mix in the REAL manager ───────────────
        viewModel.mixedFilamentManager.add(
            componentA = 1,
            componentB = 2,
            mixBPercent = 50,
            distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
        )
        val recipe = viewModel.mixedFilamentManager.serialize(SegmentationCascade.TARGET_SLOTS)
        assertTrue("Seeded recipe must be non-empty", recipe.isNotEmpty())

        // ── 2. Produce the painted whole-model 3MF the way finalizePainting does ────
        // 8mm tall @ 0.2mm ≈ 40 layers — plenty for LAYER_CYCLE alternation.
        val positions = box(ox = 0f, w = 12f, d = 12f, h = 8f)
        val triCount = positions.size / 9
        val regionIds = IntArray(triCount) { 4 } // mix slot 4 → paint state 5 (whole model)
        val regions = (0..3).map { s ->
            AiRegion(id = s, label = "Slot ${s + 1}", suggestedColour = "#888888", slot = s)
        }
        val printerColours = listOf("#FF0000", "#00FF00", "#0000FF", "#FFFF00")
        val mixDisplayColours = viewModel.mixedFilamentManager.activeOrder(SegmentationCascade.TARGET_SLOTS).map {
            com.u1.slicer.aipaint.ColourMatch.naiveBlendHex(
                printerColours.getOrElse(it.componentA - 1) { "#888888" },
                printerColours.getOrElse(it.componentB - 1) { "#888888" },
                it.mixBPercent,
            )
        }
        PaintedMeshWriter.write(
            positions = positions,
            regionIds = regionIds,
            regions = regions,
            outputFile = painted3mf,
            printerColours = printerColours,
            mixDisplayColours = mixDisplayColours,
        )
        assertTrue("Painted 3MF must be written", painted3mf.length() > 0)

        // ── 3. Load via the REAL path (loadModelFromFile) ───────────────────────────
        viewModel.loadModelFromFile(painted3mf)
        waitUntil("painted mix model loaded") {
            viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded
        }

        val cfgExtruderCount = viewModel.config.value.extruderCount
        val info = viewModel.threeMfInfo.value
        Log.i("MixRealPath", "After load: config.extruderCount=$cfgExtruderCount " +
            "detectedExtruderCount=${info?.detectedExtruderCount} hasPaint=${info?.hasPaintData} " +
            "detectedColors=${info?.detectedColors} colorMapping=${viewModel.colorMapping.value}")

        // (a) The loaded model must declare 4 physical filaments — NOT collapse to 1.
        assertTrue(
            "GATE: loaded full-spectrum model must keep extruderCount=4 (was the collapse-to-1 bug). " +
                "Got config.extruderCount=$cfgExtruderCount, threeMfInfo.detectedExtruderCount=" +
                "${info?.detectedExtruderCount}",
            cfgExtruderCount == 4,
        )
        assertTrue(
            "Full-spectrum marker must be parsed on load (fullSpectrumPhysicalCount=4). " +
                "Got ${info?.fullSpectrumPhysicalCount}",
            info?.fullSpectrumPhysicalCount == 4,
        )

        // PREVIEW-COLOUR FIX: the loaded model's mesh-aligned preview palette must extend past
        // the 4 physical filaments to give the mix slot (index 4 ⇔ paint state 5) its predicted
        // blend colour — otherwise palette[state-1] falls off the end and the region renders grey.
        waitUntil("preview palette extended with mix colour", timeoutMs = 10_000L) {
            viewModel.meshAlignedFilamentColors.value.size >= 5
        }
        val previewPalette = viewModel.meshAlignedFilamentColors.value
        Log.i("MixRealPath", "meshAlignedFilamentColors=$previewPalette")
        assertTrue(
            "Preview palette must have a 5th entry for the mix slot. Got $previewPalette",
            previewPalette.size >= 5,
        )
        // E1(#FF0000) + E2(#00FF00) @ 50% → the FilamentMixPredictor blend (not grey #808080, and
        // not the old naive sRGB average #808000 — the pick-a-colour feature replaced the naive
        // average with the predictor at every mix-colour display site, including this palette).
        val expectedBlend = com.u1.slicer.aipaint.FilamentMixPredictor
            .predict(listOf("#FF0000", "#00FF00"), listOf(50, 50)).uppercase()
        val mixColour = previewPalette[4].uppercase()
        assertTrue(
            "Mix preview colour must be the E1+E2 50% predicted blend ($expectedBlend), not grey. Got $mixColour",
            mixColour == expectedBlend,
        )

        // ── 4. Slice via the REAL path (startSlicing) ───────────────────────────────
        viewModel.startSlicing()
        waitUntil("real-path slice complete", timeoutMs = 300_000L) {
            val s = viewModel.state.value
            s is SlicerViewModel.SlicerState.SliceComplete || s is SlicerViewModel.SlicerState.Error
        }
        val state = viewModel.state.value
        assertTrue(
            "GATE: slice must complete (not Error). State=$state",
            state is SlicerViewModel.SlicerState.SliceComplete,
        )
        val result = (state as SlicerViewModel.SlicerState.SliceComplete).result
        val gcode = File(result.gcodePath).readText()
        assertTrue("G-code must be non-empty", gcode.isNotEmpty())

        // (b) num_physical == 4: the engine derives it from filament_diameter.size().
        // The config dump line "; filament_diameter = 1.75,1.75,1.75,1.75" must have 4 values.
        gcode.lineSequence().filter {
            it.contains("mixed_filament_definitions") ||
                it.trimStart(';', ' ').startsWith("filament_colour") ||
                it.trimStart(';', ' ').startsWith("filament_diameter")
        }.forEach { Log.i("MixRealPath", "CFGDUMP: ${it.trim()}") }

        val diameterLine = gcode.lineSequence().firstOrNull {
            it.trimStart(';', ' ').startsWith("filament_diameter")
        }
        assertNotNull("G-code config dump must contain filament_diameter", diameterLine)
        val diameterCount = diameterLine!!.substringAfter("=").split(",").count { it.trim().isNotEmpty() }
        Log.i("MixRealPath", "filament_diameter count (num_physical) = $diameterCount")
        assertTrue(
            "GATE: engine must see num_physical=4 (filament_diameter has 4 entries). Got $diameterCount " +
                "from line: ${diameterLine.trim()}",
            diameterCount == 4,
        )

        // ── 5. Parse per-layer tool usage ───────────────────────────────────────────
        val toolRegex = Regex("""^T(\d+)\b""")
        val totalToolCounts = IntArray(8)
        var t0t1Transitions = 0
        var lastTool = -1
        var sawAnyExtrude = false
        var activeTool = -1
        for (raw in gcode.lineSequence()) {
            val line = raw.trim()
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
            if (activeTool >= 0 && (line.startsWith("G1 ") || line.startsWith("G0 ")) && line.contains(" E")) {
                sawAnyExtrude = true
            }
        }
        val toolCountSummary = (0..7).filter { totalToolCounts[it] > 0 }
            .joinToString(", ") { "T$it=${totalToolCounts[it]}" }
        Log.i("MixRealPath", "Tool counts: [$toolCountSummary]; T0<->T1 transitions=$t0t1Transitions")
        val diag = "extruderCount=$cfgExtruderCount num_physical=$diameterCount " +
            "tool-counts=[$toolCountSummary] T0<->T1=$t0t1Transitions recipe='$recipe'"

        // ── 6. ASSERT REAL BLEND ────────────────────────────────────────────────────
        assertTrue("Sanity: G-code must contain extrusion moves. $diag", sawAnyExtrude)
        assertTrue("GATE: mix must use component tool T0 (E1). $diag", totalToolCounts[0] > 0)
        assertTrue("GATE: mix must use component tool T1 (E2). $diag", totalToolCounts[1] > 0)
        assertTrue(
            "GATE: LAYER_CYCLE blend must repeatedly alternate its two component tools. $diag",
            t0t1Transitions >= 8,
        )
        val minComponent = minOf(totalToolCounts[0], totalToolCounts[1])
        val maxComponent = maxOf(totalToolCounts[0], totalToolCounts[1])
        assertTrue(
            "GATE: 50% mix must use both components roughly evenly. $diag",
            minComponent.toDouble() >= 0.30 * maxComponent,
        )
    }
}
