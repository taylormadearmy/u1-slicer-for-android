package com.u1.slicer.slicing

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.BambuSanitizer
import com.u1.slicer.bambu.ProfileEmbedder
import com.u1.slicer.bambu.ThreeMfParser
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.gcode.GcodeToolRemapper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * B76 fix verification: Goat ( Gray ).3mf is a 4-extruder per-object Bambu model
 * with paint_color triangle attributes.  When the user sets E4 to match E3
 * (mapping [0,1,2,2]), the horn parts previously printed on E1 because the
 * 3-filament embed (distinct count) silently dropped the 4th paint state.
 *
 * This test verifies the 4-filament embed is produced (all of T0-T3 appear
 * pre-remap) and that the post-slice remap collapses T3 → T2 so the combined
 * colour-3+colour-4 volume goes to physical E3.
 */
@RunWith(AndroidJUnit4::class)
class GoatDedupeSemmTest {

    private lateinit var lib: NativeLibrary
    private lateinit var cacheDir: File
    private lateinit var outDir: File
    private lateinit var embedder: ProfileEmbedder

    private fun asset(name: String): File {
        val file = File(cacheDir, name.replace("/", "_"))
        InstrumentationRegistry.getInstrumentation().context
            .assets.open(name).use { it.copyTo(file.outputStream()) }
        return file
    }

    private fun makeConfig(extCount: Int) = SliceConfig(
        layerHeight = 0.2f,
        firstLayerHeight = 0.2f,
        fillDensity = 0.15f,
        perimeters = 2,
        supportEnabled = false,
        extruderCount = extCount,
        extruderTemps = IntArray(extCount) { 220 },
        nozzleTemp = 220,
        bedTemp = 55,
        wipeTowerEnabled = extCount > 1,
        wipeTowerX = 170f,
        wipeTowerY = 140f,
        wipeTowerWidth = 60f
    )

    @Before
    fun setUp() {
        assertTrue("Native library required", NativeLibrary.isLoaded)
        lib = NativeLibrary()
        lib.clearModel()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        cacheDir = ctx.cacheDir
        outDir = File(cacheDir, "goat_dedupe_test").also { it.mkdirs() }
        embedder = ProfileEmbedder(ctx)
    }

    @After
    fun tearDown() {
        lib.clearModel()
        outDir.deleteRecursively()
    }

    @Test
    fun goat_dedupeMapping_preservesAllFourPaintStatesAndRemapsTo3Slots() {
        val input = asset("Goat ( Gray ).3mf")
        val info = ThreeMfParser.parse(input)
        assertTrue("Goat must be detected as hasPaintData", info.hasPaintData)
        assertEquals("Goat must have 4 detected colors", 4, info.detectedColors.size)

        // User dedupes: colour 4 onto same slot as colour 3 → [0,1,2,2].
        // extCount = distinct (3), but targetCount must be 4 after the B76 fix.
        val colorMapping = listOf(0, 1, 2, 2)
        val sanitized = BambuSanitizer.process(input, outDir)
        val config = embedder.buildConfig(info = info, targetExtruderCount = 4)
        val embedded = embedder.embed(sanitized, config, outDir, info)

        assertTrue("loadModel must succeed", lib.loadModel(embedded.absolutePath))
        val result = lib.slice(makeConfig(extCount = 3))
        assertNotNull("slice() must not return null", result)
        result!!
        assertTrue("Goat must slice successfully: ${result.errorMessage}", result.success)

        val gcodeBefore = File(result.gcodePath).readText()
        val usageBefore = (0..3).map { t ->
            gcodeBefore.lines().count { it.trim() == "T$t" }
        }
        Log.i("GoatDedupeTest", "Pre-remap tool counts: $usageBefore")
        assertTrue("T0 must be emitted (pre-remap) — colour 1 on E1", usageBefore[0] > 0)
        assertTrue("T1 must be emitted (pre-remap) — colour 2", usageBefore[1] > 0)
        assertTrue("T2 must be emitted (pre-remap) — colour 3", usageBefore[2] > 0)
        assertTrue(
            "T3 must be emitted (pre-remap) — colour 4 (horns or similar); " +
            "if this fails, computeEmbedTargetCount shrunk to 3 and dropped paint state 4",
            usageBefore[3] > 0
        )

        // Apply the dedupe remap [0,1,2,2]: T0→T0, T1→T1, T2→T2, T3→T2.
        GcodeToolRemapper.remap(result.gcodePath, colorMapping)
        val gcodeAfter = File(result.gcodePath).readText()
        val usageAfter = (0..3).map { t ->
            gcodeAfter.lines().count { it.trim() == "T$t" }
        }
        Log.i("GoatDedupeTest", "Post-remap tool counts: $usageAfter")

        assertEquals("T3 must disappear after dedupe remap", 0, usageAfter[3])
        assertEquals(
            "T2 must absorb T3's count (colour 3 + colour 4 combined onto E3)",
            usageBefore[2] + usageBefore[3], usageAfter[2]
        )
        assertEquals("T0 must be unchanged", usageBefore[0], usageAfter[0])
        assertEquals("T1 must be unchanged", usageBefore[1], usageAfter[1])
    }
}
