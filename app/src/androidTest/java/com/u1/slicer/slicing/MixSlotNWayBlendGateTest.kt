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
 * M4 HEADLINE GATE — proves a 3+ component mix ACTUALLY BLENDS at slice time using the
 * engine's weighted gradient sequence (not the 2-way a/b fallback). One tall box painted
 * entirely to a single 3-component mix slot (E1+E2+E3, weights 50/30/20). Asserts all three
 * component tools appear, the off-palette tool (T3/E4) never appears, and usage roughly
 * tracks the weights. A non-N-way result would use only T0/T1. Do NOT weaken these assertions.
 */
@RunWith(AndroidJUnit4::class)
class MixSlotNWayBlendGateTest {
    private lateinit var lib: NativeLibrary
    private lateinit var out3mf: File

    @Before fun setup() {
        assertTrue("Native library must be loaded (arm64)", NativeLibrary.isLoaded)
        lib = NativeLibrary(); lib.clearModel()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        out3mf = File(ctx.cacheDir, "mix_nway_${System.currentTimeMillis()}.3mf")
    }
    @After fun teardown() { lib.clearModel(); out3mf.delete() }

    private fun box(w: Float, d: Float, h: Float): FloatArray {
        val x0=0f; val x1=w; val y0=0f; val y1=d; val z0=0f; val z1=h
        return floatArrayOf(
            x0,y0,z0, x1,y1,z0, x1,y0,z0,  x0,y0,z0, x0,y1,z0, x1,y1,z0,
            x0,y0,z1, x1,y0,z1, x1,y1,z1,  x0,y0,z1, x1,y1,z1, x0,y1,z1,
            x0,y0,z0, x1,y0,z0, x1,y0,z1,  x0,y0,z0, x1,y0,z1, x0,y0,z1,
            x0,y1,z0, x1,y1,z1, x1,y1,z0,  x0,y1,z0, x0,y1,z1, x1,y1,z1,
            x0,y0,z0, x0,y0,z1, x0,y1,z1,  x0,y0,z0, x0,y1,z1, x0,y1,z0,
            x1,y0,z0, x1,y1,z1, x1,y0,z1,  x1,y0,z0, x1,y1,z0, x1,y1,z1,
        )
    }

    private fun makeConfig(recipe: String) = SliceConfig(
        layerHeight = 0.2f, firstLayerHeight = 0.2f, perimeters = 2,
        topSolidLayers = 3, bottomSolidLayers = 3, fillDensity = 0.15f, fillPattern = "gyroid",
        printSpeed = 150f, travelSpeed = 200f, firstLayerSpeed = 50f,
        nozzleTemp = 220, bedTemp = 65, nozzleDiameter = 0.4f, filamentDiameter = 1.75f,
        retractLength = 0.8f, retractSpeed = 45f, extruderCount = 4,
        extruderTemps = IntArray(4) { 220 }, wipeTowerEnabled = false,
        mixedFilamentDefinitions = recipe,
    )

    @Test fun threeComponentMix_blendsAllThreeToolsByWeight() {
        val positions = box(12f, 12f, 10f)            // ~50 layers @ 0.2mm
        val triCount = positions.size / 9
        val regionIds = IntArray(triCount) { 4 }       // mix slot 4 -> paint state 5
        val regions = (0..3).map { s -> AiRegion(id = s, label = "Slot ${s+1}", suggestedColour = "#888888", slot = s) }
        val printerColours = listOf("#FF0000", "#00FF00", "#0000FF", "#FFFF00")
        PaintedMeshWriter.write(
            positions = positions,
            regionIds = regionIds,
            regions = regions,
            outputFile = out3mf,
            printerColours = printerColours,
            mixDisplayColours = listOf("#806633"),
        )
        assertTrue("3MF written", out3mf.length() > 0)

        val mgr = MixedFilamentManager({ emptyList() }, { emptyList() }, {}, {})
        mgr.addN(listOf(1, 2, 3), listOf(50, 30, 20), MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
        val recipe = mgr.serialize(4)
        Log.i("MixNWayGate", "recipe=$recipe")
        assertTrue("recipe non-empty", recipe.isNotEmpty())

        assertTrue("loadModel", lib.loadModel(out3mf.absolutePath))
        val result = lib.slice(makeConfig(recipe)); assertNotNull(result); result!!
        assertTrue("slice ok: '${result.errorMessage}'", result.success)

        val gcode = File(result.gcodePath).readText()
        val counts = IntArray(8)
        Regex("""^T(\d+)\b""").let { rx ->
            gcode.lineSequence().forEach { l -> rx.find(l.trim())?.let { val t = it.groupValues[1].toInt(); if (t in 0..7) counts[t]++ } }
        }
        val diag = "T0=${counts[0]} T1=${counts[1]} T2=${counts[2]} T3=${counts[3]}"
        Log.i("MixNWayGate", diag)

        assertTrue("GATE: component E1 (T0) must print. $diag", counts[0] > 0)
        assertTrue("GATE: component E2 (T1) must print. $diag", counts[1] > 0)
        assertTrue("GATE: component E3 (T2) must print — proves 3-way path, not a/b fallback. $diag", counts[2] > 0)
        assertTrue("GATE: uninvolved E4 (T3) must NOT print. $diag", counts[3] == 0)
        assertTrue("GATE: usage should track weights (T0>=T1>=T2). $diag", counts[0] >= counts[1] && counts[1] >= counts[2])
    }
}
