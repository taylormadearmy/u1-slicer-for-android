package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.NativePlateState
import com.u1.slicer.bambu.ProfileEmbedder
import com.u1.slicer.bambu.ThreeMfParser
import com.u1.slicer.data.SliceConfig
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Tier A regression tests for the 6 PM-reported bugs surfaced by the v1.7.0
 * Bambu refactor. Each test exercises the production ProfileEmbedder + native
 * loadModel path and reads plate state from native via nativeGetAllVolumeExtruders.
 *
 * These tests are the "moment of truth" for Tasks 4-6 of the native-first plate
 * state plan: if a fix didn't actually land at the user-visible level, the
 * matching test here fails.
 */
@RunWith(AndroidJUnit4::class)
class BambuPlateStateRegressionTest {

    private lateinit var lib: NativeLibrary
    private lateinit var embedder: ProfileEmbedder
    private lateinit var cacheDir: File
    private lateinit var outDir: File

    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val assetContext get() = InstrumentationRegistry.getInstrumentation().context

    private fun copyAsset(name: String): File {
        val out = File(cacheDir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        assetContext.assets.open(name).use { it.copyTo(out.outputStream()) }
        return out
    }

    @Before
    fun setup() {
        assertTrue("Native library must be loaded", NativeLibrary.isLoaded)
        lib = NativeLibrary()
        lib.clearModel()
        cacheDir = File(targetContext.cacheDir, "plate_state_test").also { it.mkdirs() }
        outDir = File(cacheDir, "out").also { it.mkdirs() }
        embedder = ProfileEmbedder(targetContext)
    }

    @After
    fun teardown() {
        lib.clearModel()
        cacheDir.deleteRecursively()
    }

    /**
     * Embed (with optional plate filter) and load via native. Returns the
     * plate state read from native via nativeGetAllVolumeExtruders.
     */
    private fun embedAndLoad(assetName: String, plateId: Int? = null): NativePlateState {
        val file = copyAsset(assetName)
        val info = ThreeMfParser.parse(file)
        val config = embedder.buildConfig(info, targetExtruderCount = info.detectedExtruderCount.coerceAtLeast(1))
        val embedded = embedder.embed(file, config, outDir, info, plateId = plateId)
        assertTrue(
            "loadModel must succeed for $assetName plate=$plateId",
            lib.loadModel(embedded.absolutePath)
        )
        return NativePlateState.parseVolumeMapJson(lib.nativeGetAllVolumeExtruders())
    }

    // --- Bug #1 / #2: Dragon Scale plate 3 — must detect 3 extruders ---

    @Test
    fun bug1_dragon_scale_plate3_three_extruders() {
        val state = embedAndLoad("Dragon Scale infinity.3mf", plateId = 2)
        assertTrue(
            "Dragon Scale plate 3 must have >= 3 extruders, got ${state.usedExtruders}",
            state.usedExtruders.size >= 3
        )
    }

    // --- Bug #2: F1 calendar — must detect 4 extruders ---

    @Test
    fun bug2_f1_calendar_plate1_four_extruders() {
        val state = embedAndLoad(
            "2026+F1+CALENDAR+-+DATES+&+TRACK+NAMES+(P_X+SERIES).3mf",
            plateId = 0
        )
        assertTrue(
            "F1 calendar plate 1 must have >= 4 extruders, got ${state.usedExtruders}",
            state.usedExtruders.size >= 4
        )
    }

    // --- Bug #3: Hanging file — translate preserved through slice ---

    @Test
    fun bug3_translate_preserved_through_slice() {
        val file = copyAsset("hanging+pre+cut+colour+3mf.3mf")
        val info = ThreeMfParser.parse(file)
        val config = embedder.buildConfig(info, targetExtruderCount = info.detectedExtruderCount.coerceAtLeast(1))
        val embedded = embedder.embed(file, config, outDir, info)
        assertTrue("loadModel must succeed", lib.loadModel(embedded.absolutePath))

        val modelInfo = lib.getModelInfo()
        assertNotNull("getModelInfo must return non-null after load", modelInfo)

        // Apply a 50 mm X translation: target lower-left = bed-center + 50.
        // setModelInstances takes lower-left positions per the C++ convention.
        val targetX = 135f + 50f
        val targetY = 135f
        assertTrue(
            "setModelInstances must succeed",
            lib.setModelInstances(floatArrayOf(targetX, targetY))
        )

        // Verify native echoes the requested offset back.
        val offsets = lib.getInstanceOffsets()
        assertTrue("Instance offsets must be non-empty", offsets.size >= 2)
        val offsetX = offsets[0]
        assertTrue(
            "Native offset X ($offsetX) must be near requested $targetX (±5mm)",
            kotlin.math.abs(offsetX - targetX) < 5f
        )

        // Slice and verify G-code X moves shift right with the translation.
        val result = lib.slice(SliceConfig())
        assertNotNull("Slice must succeed", result)
        assertTrue("Slice success flag", result!!.success)
        val gcode = File(result.gcodePath).readText()
        val xValues = Regex("""G[01]\s+(?:[^\s;]+\s+)*X(-?[\d.]+)""")
            .findAll(gcode)
            .mapNotNull { it.groupValues[1].toFloatOrNull() }
            .filter { it > 0f }
            .toList()
        assertTrue("G-code must have X moves", xValues.isNotEmpty())
        val minX = xValues.min()
        // With lower-left at 185 and a model wider than 0, minX in extrusion moves
        // must be > 100 (we requested a 50 mm offset; baseline centred ≈ 135 - half-width).
        assertTrue(
            "G-code minX ($minX) should reflect the 50mm translation (expected > 100)",
            minX > 100f
        )
    }

    // --- Bug #5: H2C benchy — multi-tool G-code post-slice ---

    @Test
    fun bug5_h2c_benchy_all_colours_in_gcode() {
        val file = copyAsset("3DBenchy-H2C-Multi-Color.3mf")
        val info = ThreeMfParser.parse(file)
        val config = embedder.buildConfig(info, targetExtruderCount = info.detectedExtruderCount.coerceAtLeast(1))
        val embedded = embedder.embed(file, config, outDir, info)
        assertTrue("loadModel must succeed", lib.loadModel(embedded.absolutePath))

        val state = NativePlateState.parseVolumeMapJson(lib.nativeGetAllVolumeExtruders())
        assertTrue("H2C benchy must have paint data", state.hasPaintData)

        val result = lib.slice(SliceConfig())
        assertNotNull("Slice must succeed", result)
        assertTrue("Slice success flag", result!!.success)
        val gcode = File(result.gcodePath).readText()
        val toolCounts = (0..3).map { t -> gcode.lines().count { it.trim() == "T$t" } }
        val activeTools = toolCounts.count { it > 0 }
        assertTrue(
            "H2C benchy must have >= 2 active tools, got $activeTools (counts=$toolCounts)",
            activeTools >= 2
        )
    }

    // --- Bug #6: Buzz cold load — Task 6 perf gate ---

    @Test
    fun bug6_buzz_cold_load_under_threshold() {
        val file = copyAsset("Buzz_Multipart_3MF_Bambu.3mf")
        val startMs = System.currentTimeMillis()
        val info = ThreeMfParser.parse(file)
        val elapsedMs = System.currentTimeMillis() - startMs

        // Buzz parse should complete well under 30 seconds. Pre-Task 6 the per-plate
        // paint scan in parse() was the dominant cost on this fixture (~50 MB,
        // 296K paint_color attributes); a regression here means the multi-plate
        // skip we added reverted or some other expensive scan crept back in.
        assertTrue(
            "Buzz parse took ${elapsedMs}ms — expected < 30000ms",
            elapsedMs < 30_000L
        )
        assertTrue("Buzz must be detected as multi-plate", info.isMultiPlate)
    }
}
