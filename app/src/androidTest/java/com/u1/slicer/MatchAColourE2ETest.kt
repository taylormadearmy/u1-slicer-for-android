package com.u1.slicer

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.aipaint.AiRegion
import com.u1.slicer.aipaint.MixColourMatcher
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
 * Pick-a-colour Task 6 — END-TO-END GATE.
 *
 * Proves the feature's headline promise: a mix SUGGESTED by [MixColourMatcher.bestMix]
 * slices into G-code that uses EXACTLY the filaments it suggested — every suggested
 * filament's tool prints, and any non-suggested filament's tool does not.
 *
 * Setup mirrors MixSlotNWayBlendGateTest: one painted box assigned entirely to mix slot 4,
 * the mix recipe built from the matcher's own suggestion. Do NOT weaken these assertions.
 */
@RunWith(AndroidJUnit4::class)
class MatchAColourE2ETest {
    private lateinit var lib: NativeLibrary
    private lateinit var out3mf: File

    @Before fun setup() {
        assertTrue("Native library must be loaded (arm64)", NativeLibrary.isLoaded)
        lib = NativeLibrary(); lib.clearModel()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        out3mf = File(ctx.cacheDir, "match_colour_${System.currentTimeMillis()}.3mf")
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

    @Test fun matchedMix_slicesWithSuggestedTools() {
        val loaded = listOf("#FF0000", "#00FF00", "#0000FF", "#FFFF00")
        val s = MixColourMatcher.bestMix("#7a8a3a", loaded, count = 2)
        Log.i(TAG, "suggestion componentIndices=${s.componentIndices} weights=${s.weights} " +
            "predictedHex=${s.predictedHex} deltaE=${s.deltaE}")

        val positions = box(12f, 12f, 10f)             // ~50 layers @ 0.2mm
        val triCount = positions.size / 9
        val regionIds = IntArray(triCount) { 4 }        // mix slot 4 -> paint state 5
        val regions = (0..3).map { slot -> AiRegion(id = slot, label = "Slot ${slot+1}", suggestedColour = "#888888", slot = slot) }
        PaintedMeshWriter.write(
            positions = positions,
            regionIds = regionIds,
            regions = regions,
            outputFile = out3mf,
            printerColours = loaded,
            mixDisplayColours = listOf(s.predictedHex),
        )
        assertTrue("3MF written", out3mf.length() > 0)

        val mgr = MixedFilamentManager({ emptyList() }, { emptyList() }, {}, {})
        mgr.addN(s.componentIndices, s.weights, MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
        val recipe = mgr.serialize(4)
        Log.i(TAG, "recipe=$recipe")
        assertTrue("recipe non-empty", recipe.isNotEmpty())

        assertTrue("loadModel", lib.loadModel(out3mf.absolutePath))
        val result = lib.slice(makeConfig(recipe)); assertNotNull(result); result!!
        assertTrue("slice ok: '${result.errorMessage}'", result.success)

        val gcode = File(result.gcodePath).readText()
        val counts = IntArray(8)
        Regex("""^T(\d+)\b""").let { rx ->
            gcode.lineSequence().forEach { l -> rx.find(l.trim())?.let { val t = it.groupValues[1].toInt(); if (t in 0..7) counts[t]++ } }
        }
        val diag = "suggested=${s.componentIndices} T0=${counts[0]} T1=${counts[1]} T2=${counts[2]} T3=${counts[3]}"
        Log.i(TAG, diag)

        // GATE 1: every filament the matcher suggested must actually print (tool = component-1).
        for (idx in s.componentIndices) {
            assertTrue("GATE: suggested filament E$idx (T${idx-1}) must print. $diag", counts[idx - 1] > 0)
        }
        // GATE 2: any filament NOT suggested must NOT print.
        for (t in 0..3) {
            if ((t + 1) !in s.componentIndices) {
                assertTrue("GATE: non-suggested filament E${t+1} (T$t) must NOT print. $diag", counts[t] == 0)
            }
        }
        // GATE 3: sanity — something actually extruded.
        assertTrue("GATE: at least one tool must print. $diag", counts.sliceArray(0..3).sum() > 0)
    }

    companion object { private const val TAG = "MatchAColourE2E" }
}
