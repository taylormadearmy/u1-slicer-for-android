package com.u1.slicer.slicing

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.NativePlateState
import com.u1.slicer.bambu.ProfileEmbedder
import com.u1.slicer.bambu.ThreeMfParser
import com.u1.slicer.data.SliceConfig
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Data-driven Bambu fixture harness. Each approved JSON spec under
 * androidTest/assets/fixture-specs/ defines a fixture and its expected plate
 * behaviour: extruder count, paint flag, per-tool G-code counts (with a
 * tolerance), and a bounding box ceiling. Each spec entry's plateIndex is
 * 0-based; use -1 to skip the plate filter.
 *
 * The harness loads each approved fixture, embeds with the supplied plateIndex,
 * reads native plate state, slices, and verifies tool counts + bounding box.
 */
@RunWith(AndroidJUnit4::class)
class BambuFixtureHarnessTest {

    private lateinit var lib: NativeLibrary
    private lateinit var embedder: ProfileEmbedder
    private lateinit var cacheDir: File
    private lateinit var outDir: File

    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val assetContext get() = InstrumentationRegistry.getInstrumentation().context

    private data class PlateSpec(
        val plateIndex: Int,
        val expectedExtruderCount: Int,
        val expectedToolCounts: Map<String, Int>,
        val toolCountTolerance: Int,
        val hasPaintData: Boolean,
        val maxBoundingBoxMm: List<Int>
    )

    private data class FixtureSpec(
        val file: String,
        val approved: Boolean,
        val plates: List<PlateSpec>
    )

    private fun copyAsset(name: String): File {
        val out = File(cacheDir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        assetContext.assets.open(name).use { it.copyTo(out.outputStream()) }
        return out
    }

    private fun loadFixtureSpecs(): List<FixtureSpec> {
        val specFiles = assetContext.assets.list("fixture-specs") ?: return emptyList()
        return specFiles.filter { it.endsWith(".json") }.mapNotNull { specFile ->
            try {
                val json = assetContext.assets.open("fixture-specs/$specFile").use {
                    it.bufferedReader().readText()
                }
                parseSpec(JSONObject(json))
            } catch (e: Exception) {
                Log.w("FixtureHarness", "Failed to parse spec $specFile: ${e.message}")
                null
            }
        }
    }

    private fun parseSpec(json: JSONObject): FixtureSpec {
        val plates = mutableListOf<PlateSpec>()
        val platesArr = json.getJSONArray("plates")
        for (i in 0 until platesArr.length()) {
            val p = platesArr.getJSONObject(i)
            val toolCounts = mutableMapOf<String, Int>()
            val tc = p.optJSONObject("expectedToolCounts")
            if (tc != null) {
                val keys = tc.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    toolCounts[key] = tc.getInt(key)
                }
            }
            val bbox = p.optJSONArray("maxBoundingBoxMm")
            plates.add(
                PlateSpec(
                    plateIndex = p.getInt("plateIndex"),
                    expectedExtruderCount = p.getInt("expectedExtruderCount"),
                    expectedToolCounts = toolCounts,
                    toolCountTolerance = p.optInt("toolCountTolerance", 5),
                    hasPaintData = p.optBoolean("hasPaintData", false),
                    maxBoundingBoxMm = if (bbox != null)
                        listOf(bbox.getInt(0), bbox.getInt(1))
                    else
                        listOf(270, 270)
                )
            )
        }
        return FixtureSpec(
            file = json.getString("file"),
            approved = json.optBoolean("approved", false),
            plates = plates
        )
    }

    private fun embedAndLoadForPlate(assetName: String, plateId: Int?): NativePlateState {
        val file = copyAsset(assetName)
        val info = ThreeMfParser.parse(file)
        val target = info.detectedExtruderCount.coerceAtLeast(1)
        val config = embedder.buildConfig(info, targetExtruderCount = target)
        val embedded = embedder.embed(file, config, outDir, info, plateId = plateId)
        assertTrue(
            "loadModel must succeed for $assetName plate=$plateId",
            lib.loadModel(embedded.absolutePath)
        )
        return NativePlateState.parseVolumeMapJson(lib.nativeGetAllVolumeExtruders())
    }

    private fun parseToolCounts(gcodePath: String): Map<String, Int> {
        val gcode = File(gcodePath).readText()
        return (0..3).associate { t ->
            "T$t" to gcode.lines().count { it.trim() == "T$t" }
        }
    }

    private fun parseGcodeBounds(gcodePath: String): Pair<Float, Float> {
        val gcode = File(gcodePath).readText()
        val xRegex = Regex("""G[01]\s+(?:[^\s;]+\s+)*X(-?[\d.]+)""")
        val yRegex = Regex("""G[01]\s+(?:[^\s;]+\s+)*Y(-?[\d.]+)""")
        val xs = xRegex.findAll(gcode).mapNotNull { it.groupValues[1].toFloatOrNull() }
            .filter { it > 0f }.toList()
        val ys = yRegex.findAll(gcode).mapNotNull { it.groupValues[1].toFloatOrNull() }
            .filter { it > 0f }.toList()
        val width = if (xs.isNotEmpty()) xs.max() - xs.min() else 0f
        val height = if (ys.isNotEmpty()) ys.max() - ys.min() else 0f
        return Pair(width, height)
    }

    @Before
    fun setup() {
        assertTrue("Native library must be loaded", NativeLibrary.isLoaded)
        lib = NativeLibrary()
        lib.clearModel()
        cacheDir = File(targetContext.cacheDir, "fixture_harness").also { it.mkdirs() }
        outDir = File(cacheDir, "out").also { it.mkdirs() }
        embedder = ProfileEmbedder(targetContext)
    }

    @After
    fun teardown() {
        lib.clearModel()
        cacheDir.deleteRecursively()
    }

    @Test
    fun validate_all_approved_fixtures() {
        val specs = loadFixtureSpecs()
        if (specs.isEmpty()) {
            Log.i("FixtureHarness", "No fixture specs found in assets/fixture-specs/")
            return
        }

        val approved = specs.filter { it.approved }
        Log.i("FixtureHarness", "Validating ${approved.size} approved fixtures")
        val failures = mutableListOf<String>()

        for (spec in approved) {
            for (plate in spec.plates) {
                val tag = "${spec.file} plate ${plate.plateIndex}"
                try {
                    lib.clearModel()
                    val state = embedAndLoadForPlate(
                        spec.file,
                        plate.plateIndex.takeIf { it >= 0 }
                    )

                    if (state.usedExtruders.size != plate.expectedExtruderCount) {
                        failures.add(
                            "$tag: extruder count ${state.usedExtruders.size} != ${plate.expectedExtruderCount}"
                        )
                    }
                    if (state.hasPaintData != plate.hasPaintData) {
                        failures.add(
                            "$tag: hasPaintData ${state.hasPaintData} != ${plate.hasPaintData}"
                        )
                    }

                    if (plate.expectedToolCounts.isNotEmpty()) {
                        val result = lib.slice(SliceConfig())
                        assertNotNull("$tag: slice returned null", result)
                        assertTrue("$tag: slice failed: ${result!!.errorMessage}", result.success)
                        val toolCounts = parseToolCounts(result.gcodePath)
                        for ((tool, expected) in plate.expectedToolCounts) {
                            val actual = toolCounts[tool] ?: 0
                            if (abs(actual - expected) > plate.toolCountTolerance) {
                                failures.add(
                                    "$tag: $tool count $actual not within ±${plate.toolCountTolerance} of $expected"
                                )
                            }
                        }
                        val (width, height) = parseGcodeBounds(result.gcodePath)
                        if (width > plate.maxBoundingBoxMm[0]) {
                            failures.add(
                                "$tag: G-code width ${width}mm > ${plate.maxBoundingBoxMm[0]}mm"
                            )
                        }
                        if (height > plate.maxBoundingBoxMm[1]) {
                            failures.add(
                                "$tag: G-code height ${height}mm > ${plate.maxBoundingBoxMm[1]}mm"
                            )
                        }
                    }

                    Log.i("FixtureHarness", "PASS: $tag")
                } catch (e: Exception) {
                    failures.add("$tag: EXCEPTION ${e.message}")
                }
            }
        }

        if (failures.isNotEmpty()) {
            fail("Fixture harness failures:\n" + failures.joinToString("\n"))
        }
    }
}
