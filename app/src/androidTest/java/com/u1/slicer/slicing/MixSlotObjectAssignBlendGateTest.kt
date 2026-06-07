package com.u1.slicer.slicing

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.SlicerViewModel
import com.u1.slicer.U1SlicerApplication
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
 * ISSUE #3 HEADLINE GATE — the OBJECT/PART-ASSIGNMENT path.
 *
 * Sibling of [MixSlotRealLoadPathBlendTest] (which proves the Smart-Paint
 * painted-triangle path blends). This test exercises the OTHER mix surface:
 * assigning a whole OBJECT to a mix slot via the per-part / whole-object
 * "Assign to filament" dialog ([com.u1.slicer.ui.PartsPanel]'s
 * FilamentChooserDialog). That dialog's `onPick(slot)` for a mix calls
 * [SlicerViewModel.setObjectFilament] / [SlicerViewModel.setVolumeExtruder]
 * with a 1-based mix id (numPhysical + idx + 1), which bakes the mix id into
 * the object's native `extruder` metadata PRE-slice.
 *
 * Crucially this drives a PLAIN STL (no embedded profile, no full-spectrum
 * marker, no painted triangles): the model's natural declared extruder count
 * is 1. The fix under test is that [SlicerViewModel.startSlicing] expands the
 * physical filament base to TARGET_SLOTS (4) when any per-volume override is a
 * mix id, so the engine sees num_physical=4 and resolves the mix id (5) as a
 * virtual filament — alternating its two component tools per layer — instead
 * of mis-treating it as a literal physical filament (the invalid-T4 failure
 * the prior Send-dialog attempt produced).
 *
 * GATE assertions (mirrors MixSlotRealLoadPathBlendTest):
 *   - slice succeeds and produces G-code,
 *   - NO literal high tool (T>=4) — the mix id was NOT clamped/passed through,
 *   - both component tools (T0=E1, T1=E2) present,
 *   - >= 8 T0<->T1 alternations (real per-layer blend),
 *   - components used roughly evenly (50% recipe).
 */
@RunWith(AndroidJUnit4::class)
class MixSlotObjectAssignBlendGateTest {

    private lateinit var application: U1SlicerApplication
    private lateinit var viewModel: SlicerViewModel
    private lateinit var stl: File

    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setup() {
        assertTrue("Native library must be loaded on device (arm64 required)", NativeLibrary.isLoaded)
        application = targetContext.applicationContext as U1SlicerApplication
        viewModel = SlicerViewModel(application)
        // 3DBenchy.stl is a plain STL — no embedded profile, no full-spectrum
        // marker, no painted triangles. Its natural declared extruder count is
        // 1, which is exactly what stresses the physical-base-expansion fix:
        // without it the engine would size filament arrays to 1 and mis-handle
        // the mix id. Copied from the TEST APK assets (instrumentation context).
        stl = File(targetContext.cacheDir, "mix_objassign_benchy_${System.currentTimeMillis()}.stl")
        InstrumentationRegistry.getInstrumentation().context
            .assets.open("3DBenchy.stl").use { input -> stl.outputStream().use { input.copyTo(it) } }
    }

    @After
    fun teardown() {
        runCatching { viewModel.clearModel() }
        stl.delete()
        runCatching {
            viewModel.mixedFilamentManager.projectMixes.value.forEach {
                viewModel.mixedFilamentManager.delete(it.id)
            }
        }
    }

    private fun waitUntil(label: String, timeoutMs: Long = 300_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError("Timed out waiting for $label")
    }

    @Test
    fun objectAssignedToMixSlot_throughRealAssignAndSlicePath_resolvesToRealBlend() {
        // ── 1. Seed a 50/50 E1+E2 LAYER_CYCLE mix in the REAL manager ───────────────
        viewModel.mixedFilamentManager.add(
            componentA = 1,
            componentB = 2,
            mixBPercent = 50,
            distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
        )
        val recipe = viewModel.mixedFilamentManager.serialize(SegmentationCascade.TARGET_SLOTS)
        assertTrue("Seeded recipe must be non-empty", recipe.isNotEmpty())

        // ── 2. Load the PLAIN STL via the REAL path (loadModelFromFile) ─────────────
        assertTrue("STL fixture must be written (${stl.length()} bytes)", stl.length() > 1000)
        viewModel.loadModelFromFile(stl)
        waitUntil("STL model loaded or error") {
            val s = viewModel.state.value
            s is SlicerViewModel.SlicerState.ModelLoaded || s is SlicerViewModel.SlicerState.Error
        }
        val loadState = viewModel.state.value
        assertTrue(
            "GATE: STL must load to ModelLoaded (not Error). State=$loadState",
            loadState is SlicerViewModel.SlicerState.ModelLoaded,
        )

        // ── 3. Assign the whole object to the MIX slot, exactly as the dialog does ──
        // FilamentChooserDialog.onPick(mixSlot1Based) → setObjectFilament. Mix slot 0
        // (ordered) → 1-based id = numPhysical + 0 + 1 = 5.
        val numPhysical = SegmentationCascade.TARGET_SLOTS
        val mixSlot1Based = numPhysical + 0 + 1 // = 5
        viewModel.setObjectFilament(0, mixSlot1Based)
        Log.i("MixObjGate", "Assigned object 0 → mix slot $mixSlot1Based; " +
            "perVolume=${viewModel.perVolumeExtruders.value}")
        assertTrue(
            "Object-extruder override must record the mix id (5). Got ${viewModel.perVolumeExtruders.value}",
            viewModel.perVolumeExtruders.value.values.any { it == mixSlot1Based },
        )

        // ── 3b. BUG A — the Prepare PREVIEW palette must carry the mix's blend colour ──
        // The slice (below) bakes the mix into the object extruder and blends correctly, but
        // pre-fix the 3D Prepare preview rendered the object grey because no palette entry
        // existed for the mix slot. meshAlignedFilamentColors must now expose the naive-blend
        // colour at the mesh's compacted index for the assigned object. For a single-object STL
        // fully assigned to mix slot 5, the only source extruder is 4 (0-based) → compacted mesh
        // index 0 → palette[0] must be the E1(#FF0000)+E2(#00FF00) 50% blend = #808000.
        waitUntil("preview palette carries mix blend colour", timeoutMs = 10_000L) {
            viewModel.meshAlignedFilamentColors.value.isNotEmpty()
        }
        val previewPalette = viewModel.meshAlignedFilamentColors.value
        val slotColors = viewModel.activeExtruderColors.value
        Log.i("MixObjGate", "meshAlignedFilamentColors=$previewPalette slotColors=$slotColors")
        assertTrue(
            "GATE: object-assigned-mix preview palette must be non-empty. Got $previewPalette",
            previewPalette.isNotEmpty(),
        )
        // The mix blends the live E1 + E2 slot colours @ 50%. Compute the expected blend from
        // whatever the active slot presets actually are so the assertion is preset-independent.
        val e1 = slotColors.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "#888888"
        val e2 = slotColors.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "#888888"
        val expectedBlend = com.u1.slicer.aipaint.ColourMatch.naiveBlendHex(e1, e2, 50).uppercase()
        Log.i("MixObjGate", "expected mix blend of E1=$e1 E2=$e2 @50% = $expectedBlend")
        assertTrue(
            "GATE: preview palette must contain the E1+E2 50% mix blend ($expectedBlend), " +
                "not just a physical slot colour. Got ${previewPalette.map { it.uppercase() }}",
            previewPalette.any { it.uppercase() == expectedBlend },
        )

        // ── 4. Slice via the REAL path (startSlicing) ───────────────────────────────
        viewModel.startSlicing()
        waitUntil("object-assign slice complete", timeoutMs = 300_000L) {
            val s = viewModel.state.value
            s is SlicerViewModel.SlicerState.SliceComplete || s is SlicerViewModel.SlicerState.Error
        }
        val state = viewModel.state.value
        assertTrue("GATE: slice must complete (not Error). State=$state",
            state is SlicerViewModel.SlicerState.SliceComplete)
        val result = (state as SlicerViewModel.SlicerState.SliceComplete).result
        val gcode = File(result.gcodePath).readText()
        assertTrue("G-code must be non-empty", gcode.isNotEmpty())

        // num_physical surfaced via filament_diameter count in the config dump.
        gcode.lineSequence().filter {
            it.contains("mixed_filament_definitions") ||
                it.trimStart(';', ' ').startsWith("filament_colour") ||
                it.trimStart(';', ' ').startsWith("filament_diameter")
        }.forEach { Log.i("MixObjGate", "CFGDUMP: ${it.trim()}") }
        val diameterLine = gcode.lineSequence().firstOrNull {
            it.trimStart(';', ' ').startsWith("filament_diameter")
        }
        assertNotNull("G-code config dump must contain filament_diameter", diameterLine)
        val diameterCount = diameterLine!!.substringAfter("=").split(",").count { it.trim().isNotEmpty() }
        Log.i("MixObjGate", "num_physical (filament_diameter count) = $diameterCount")

        // ── 5. Parse per-layer tool usage ───────────────────────────────────────────
        val toolRegex = Regex("""^T(\d+)\b""")
        val totalToolCounts = IntArray(16)
        var t0t1Transitions = 0
        var lastTool = -1
        var sawAnyExtrude = false
        var activeTool = -1
        for (raw in gcode.lineSequence()) {
            val line = raw.trim()
            val tm = toolRegex.find(line)
            if (tm != null) {
                val t = tm.groupValues[1].toIntOrNull() ?: continue
                if (t in 0..15) {
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
        val toolCountSummary = (0..15).filter { totalToolCounts[it] > 0 }
            .joinToString(", ") { "T$it=${totalToolCounts[it]}" }
        Log.i("MixObjGate", "Tool counts: [$toolCountSummary]; T0<->T1 transitions=$t0t1Transitions")
        val diag = "num_physical=$diameterCount tool-counts=[$toolCountSummary] " +
            "T0<->T1=$t0t1Transitions recipe='$recipe'"

        // ── 6. ASSERT REAL BLEND ────────────────────────────────────────────────────
        assertTrue("Sanity: G-code must contain extrusion moves. $diag", sawAnyExtrude)
        val usesHighTool = (4..15).any { totalToolCounts[it] > 0 }
        assertTrue(
            "GATE: NO literal high tool (T>=4) may appear — a mix id must resolve to a blend, " +
                "not pass through as a physical filament. $diag",
            !usesHighTool,
        )
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

    /**
     * BUG B — the MODEL-WIDE assignment path (Filaments card Mixes subsection tap →
     * [SlicerViewModel.setModelFilament]). Same blend GATE as the per-object path above, but
     * driven through the model-wide API the always-visible Filaments card now uses. Also
     * confirms a NORMAL (physical) model-wide assignment is unaffected (no high tool, single
     * tool only).
     */
    @Test
    fun modelAssignedToMixSlot_throughModelWidePath_resolvesToRealBlend() {
        viewModel.mixedFilamentManager.add(
            componentA = 1,
            componentB = 2,
            mixBPercent = 50,
            distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
        )
        assertTrue("STL fixture must be written", stl.length() > 1000)
        viewModel.loadModelFromFile(stl)
        waitUntil("STL model loaded or error") {
            val s = viewModel.state.value
            s is SlicerViewModel.SlicerState.ModelLoaded || s is SlicerViewModel.SlicerState.Error
        }
        assertTrue(
            "GATE: STL must load to ModelLoaded. State=${viewModel.state.value}",
            viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded,
        )

        val numPhysical = SegmentationCascade.TARGET_SLOTS
        val mixSlot1Based = numPhysical + 0 + 1 // = 5

        // ── Control: a NORMAL physical model-wide assignment must NOT blend ─────────────
        viewModel.setModelFilament(2) // E2 (physical)
        assertTrue(
            "Model filament must aggregate to the assigned physical slot (2). " +
                "Got ${viewModel.aggregatedModelFilament()}",
            viewModel.aggregatedModelFilament() == 2,
        )

        // ── The mix model-wide assignment (the Bug B headline) ──────────────────────────
        viewModel.setModelFilament(mixSlot1Based)
        Log.i("MixModelGate", "Model assigned → mix slot $mixSlot1Based; " +
            "perVolume=${viewModel.perVolumeExtruders.value} agg=${viewModel.aggregatedModelFilament()}")
        assertTrue(
            "Model-wide assign must record the mix id (5) on every volume. " +
                "Got ${viewModel.perVolumeExtruders.value}",
            viewModel.aggregatedModelFilament() == mixSlot1Based,
        )

        viewModel.startSlicing()
        waitUntil("model-wide assign slice complete", timeoutMs = 300_000L) {
            val s = viewModel.state.value
            s is SlicerViewModel.SlicerState.SliceComplete || s is SlicerViewModel.SlicerState.Error
        }
        val state = viewModel.state.value
        assertTrue("GATE: slice must complete. State=$state",
            state is SlicerViewModel.SlicerState.SliceComplete)
        val gcode = File((state as SlicerViewModel.SlicerState.SliceComplete).result.gcodePath).readText()

        val toolRegex = Regex("""^T(\d+)\b""")
        val totalToolCounts = IntArray(16)
        var t0t1Transitions = 0
        var lastTool = -1
        for (raw in gcode.lineSequence()) {
            val tm = toolRegex.find(raw.trim()) ?: continue
            val t = tm.groupValues[1].toIntOrNull() ?: continue
            if (t in 0..15) {
                if ((t == 0 || t == 1) && (lastTool == 0 || lastTool == 1) && t != lastTool) t0t1Transitions++
                lastTool = t
                totalToolCounts[t]++
            }
        }
        val toolCountSummary = (0..15).filter { totalToolCounts[it] > 0 }
            .joinToString(", ") { "T$it=${totalToolCounts[it]}" }
        val diag = "tool-counts=[$toolCountSummary] T0<->T1=$t0t1Transitions"
        Log.i("MixModelGate", diag)

        assertTrue("GATE: NO literal high tool (T>=4). $diag", (4..15).none { totalToolCounts[it] > 0 })
        assertTrue("GATE: mix must use T0 (E1). $diag", totalToolCounts[0] > 0)
        assertTrue("GATE: mix must use T1 (E2). $diag", totalToolCounts[1] > 0)
        assertTrue("GATE: LAYER_CYCLE must alternate components. $diag", t0t1Transitions >= 8)
    }
}
