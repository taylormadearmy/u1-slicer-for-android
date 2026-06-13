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
 * B145 RED test: a SINGLE-OBJECT STL assigned a MIX must still get a prime tower
 * when the wipe tower is enabled.
 *
 * Mechanism of the bug (verified via on-device repro + code trace):
 *  - PrintApply.cpp computes `used_filaments = this->extruders(true).size()` then
 *    calls `normalize_fdm_2(objects().size(), used_filaments)`.
 *  - `normalize_fdm_2` force-sets `enable_prime_tower = false` when used_filaments == 1.
 *  - For a single-object STL+mix, each region's CONFIGURED filament id is the single
 *    VIRTUAL mix id (e.g. 1-based 5) → extruders() size == 1. The mix's component
 *    physical tools (E3/E4 → T2/T3) are injected only at G-code export, so the count
 *    never sees them → prime tower wrongly disabled.
 *  - A dual-colour 3MF+mix escapes this (a 2nd real file filament makes the count 2),
 *    which is why TopSurfaceMixWipeTowerTest does not catch it.
 *
 * Expected outcome while the bug exists: this test FAILS — the G-code header shows
 * `enable_prime_tower = 0` and there is no `;TYPE:Prime tower` section, despite the
 * app-resolved `wipeTowerEnabled = true`.
 */
@RunWith(AndroidJUnit4::class)
class StlMixPrimeTowerTest {

    private companion object {
        const val TAG = "StlMixPrimeTower"
        // Single-colour STL: canonical count == 1, so the mix is the ONLY filament
        // the regions configure → reproduces the used_filaments == 1 path.
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

    private fun asset(name: String): File {
        val file = File(cacheDir, name)
        InstrumentationRegistry.getInstrumentation().context
            .assets.open(name).use { it.copyTo(file.outputStream()) }
        return file
    }

    /**
     * Reads the value of the `enable_prime_tower` config line (0/1) or null if
     * absent. OrcaSlicer writes config in the footer as a comment line
     * (`; enable_prime_tower = 0`), so strip any leading `; ` before matching.
     */
    private fun primeTowerHeaderValue(gcode: String): String? =
        gcode.lineSequence()
            .map { it.trim().removePrefix(";").trim() }
            .firstOrNull { it.startsWith("enable_prime_tower") && it.contains("=") }
            ?.substringAfter("=")
            ?.trim()

    private fun hasPrimeTowerSection(gcode: String): Boolean =
        gcode.lineSequence().any { it.trim().equals(";TYPE:Prime tower", ignoreCase = true) }

    @Test
    fun singleObjectStlMix_wipeTowerOn_emitsPrimeTower() {
        val file = asset(ASSET)
        assertTrue("loadModel must succeed", lib.loadModel(file.absolutePath))

        val objCount = lib.nativeGetObjectCount()
        Log.i(TAG, "fixture object count = $objCount")
        assertTrue("STL fixture must have exactly 1 object", objCount == 1)
        val vols = lib.nativeGetVolumeCount(0)
        Log.i(TAG, "object 0 volume count = $vols")

        // 2-component mix (E3+E4 @ 50/50). Single-colour STL → canonicalCount = 1.
        val (mixSlot, recipe) = SurfaceColorMixTestSupport.buildRecipeAndSlot(
            componentSlots = listOf(3, 4),
            weights = listOf(50, 50),
            distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
            canonicalCount = 1,
        )
        Log.i(TAG, "mix slot = $mixSlot, recipe = $recipe")

        // Assign the STL's single object's volume(s) to the mix slot.
        for (v in 0 until vols) {
            assertTrue(
                "nativeSetVolumeExtruder(0, $v, $mixSlot) must succeed",
                lib.nativeSetVolumeExtruder(0, v, mixSlot),
            )
        }

        val result = lib.slice(makeConfig(recipe)) ?: error("slice() returned null")
        assertTrue(
            "slice must succeed with wipe tower ON, error='${result.errorMessage}'",
            result.success,
        )
        val gcode = File(result.gcodePath).readText()

        val headerVal = primeTowerHeaderValue(gcode)
        val hasSection = hasPrimeTowerSection(gcode)
        Log.i(TAG, "enable_prime_tower header = $headerVal, has ;TYPE:Prime tower = $hasSection")

        assertTrue(
            "single-object STL + mix with wipe tower ON must emit a prime tower, but " +
                "enable_prime_tower header = '$headerVal' and ;TYPE:Prime tower present = $hasSection. " +
                "(B145: normalize_fdm_2 sees used_filaments == 1 because the mix's component " +
                "tools are injected only at export, so it wrongly auto-disables the prime tower.)",
            hasSection || headerVal == "1",
        )
    }
}
