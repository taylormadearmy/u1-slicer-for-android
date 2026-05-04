package com.u1.slicer.slicing

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.BambuSanitizer
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
 * tolerance), and a bounding box ceiling.
 *
 * Historical caveat: the fixture field is named plateIndex, but this legacy
 * harness passes the value directly to ProfileEmbedder as plateId to preserve
 * release-comparison behavior. Do not use this class as proof of true 1-based
 * app plate coverage until the fixture specs and harness are migrated together.
 * Use -1 to skip the plate filter.
 *
 * The harness loads each approved fixture, embeds with the supplied legacy
 * plate value, reads native plate state, slices, and verifies tool counts +
 * bounding box.
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

    private data class LoadResult(
        val info: com.u1.slicer.bambu.ThreeMfInfo,
        val state: NativePlateState
    )

    private fun embedAndLoadForPlate(assetName: String, plateId: Int?): LoadResult {
        val file = copyAsset(assetName)
        val info = ThreeMfParser.parse(file)
        val target = info.detectedExtruderCount.coerceAtLeast(1)
        val config = embedder.buildConfig(info, targetExtruderCount = target)
        // Mirror SlicerViewModel's selectPlate routing:
        //  - multi-plate Bambu: embed with plate filter (sub-plan #2d strip pipeline).
        //  - single-plate Bambu: BambuSanitizer.process to strip Bambu-specific xmlns
        //    + p:path component refs, then embed; native otherwise rejects the load.
        val sourceForEmbed = when {
            info.isMultiPlate -> file
            info.isBambu -> BambuSanitizer.process(file, outDir)
            else -> file
        }
        val embedded = embedder.embed(sourceForEmbed, config, outDir, info, plateId = plateId)
        assertTrue(
            "loadModel must succeed for $assetName plate=$plateId",
            lib.loadModel(embedded.absolutePath)
        )
        val state = NativePlateState.parseVolumeMapJson(lib.nativeGetAllVolumeExtruders())
        return LoadResult(info, state)
    }

    private data class GcodeStats(
        val toolCounts: Map<String, Int>,
        val width: Float,
        val height: Float
    )

    private fun parseGcodeStats(gcodePath: String): GcodeStats {
        val toolCounts = (0..3).associate { "T$it" to 0 }.toMutableMap()
        val xRegex = Regex("""G[01]\s+(?:[^\s;]+\s+)*X(-?[\d.]+)""")
        val yRegex = Regex("""G[01]\s+(?:[^\s;]+\s+)*Y(-?[\d.]+)""")
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY

        File(gcodePath).useLines { lines ->
            lines.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.length == 2 && trimmed[0] == 'T' && trimmed[1] in '0'..'3') {
                    toolCounts[trimmed] = (toolCounts[trimmed] ?: 0) + 1
                }
                xRegex.find(line)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                    ?.takeIf { it > 0f }
                    ?.let {
                        if (it < minX) minX = it
                        if (it > maxX) maxX = it
                    }
                yRegex.find(line)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                    ?.takeIf { it > 0f }
                    ?.let {
                        if (it < minY) minY = it
                        if (it > maxY) maxY = it
                    }
            }
        }

        val width = if (minX.isFinite() && maxX.isFinite()) maxX - minX else 0f
        val height = if (minY.isFinite() && maxY.isFinite()) maxY - minY else 0f
        return GcodeStats(toolCounts, width, height)
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

    /**
     * Validate a single approved spec — exposed as one method per spec file so
     * Android Test Orchestrator gives each its own process. Slicing accumulates
     * native memory; running 6 fixtures in one method OOMs the process.
     */
    private fun validateSingleSpec(specFileName: String) {
        val json = try {
            assetContext.assets.open("fixture-specs/$specFileName").use {
                it.bufferedReader().readText()
            }
        } catch (e: Exception) {
            Log.i("FixtureHarness", "Spec $specFileName not present — skipping")
            return
        }
        val spec = parseSpec(JSONObject(json))
        if (!spec.approved) {
            Log.i("FixtureHarness", "Spec $specFileName not approved — skipping")
            return
        }
        Log.i("FixtureHarness", "Validating ${spec.file}")
        val failures = mutableListOf<String>()

        for (plate in spec.plates) {
            val tag = "${spec.file} plate ${plate.plateIndex}"
            try {
                lib.clearModel()
                val legacyPlateId = plate.plateIndex.takeIf { it >= 0 }
                val (info, state) = embedAndLoadForPlate(spec.file, legacyPlateId)
                val enriched = enrichedUsedExtruders(lib, info, state, legacyPlateId)

                if (enriched.size != plate.expectedExtruderCount) {
                    failures.add(
                        "$tag: extruder count ${enriched.size} != ${plate.expectedExtruderCount} " +
                            "(native=${state.usedExtruders}, enriched=$enriched)"
                    )
                }
                if (state.hasPaintData != plate.hasPaintData) {
                    failures.add(
                        "$tag: hasPaintData ${state.hasPaintData} != ${plate.hasPaintData}"
                    )
                }

                if (plate.expectedToolCounts.isNotEmpty()) {
                    val expectedToolExtruderCount = plate.expectedToolCounts.keys
                        .mapNotNull { it.removePrefix("T").toIntOrNull() }
                        .maxOrNull()
                        ?.plus(1)
                        ?: 1
                    val sliceExtruderCount = maxOf(
                        plate.expectedExtruderCount,
                        expectedToolExtruderCount,
                        enriched.maxOrNull() ?: 1
                    ).coerceAtLeast(1)
                    val result = lib.slice(SliceConfig(extruderCount = sliceExtruderCount))
                    assertNotNull("$tag: slice returned null", result)
                    assertTrue("$tag: slice failed: ${result!!.errorMessage}", result.success)
                    val stats = parseGcodeStats(result.gcodePath)
                    val toolCounts = stats.toolCounts
                    Log.i("FixtureHarness", "ACTUAL $tag toolCounts=$toolCounts")
                    for ((tool, expected) in plate.expectedToolCounts) {
                        val actual = toolCounts[tool] ?: 0
                        if (expected > 0 && actual == 0) {
                            failures.add("$tag: $tool expected non-zero tool use near $expected but was 0")
                        }
                        if (abs(actual - expected) > plate.toolCountTolerance) {
                            failures.add(
                                "$tag: $tool count $actual not within ±${plate.toolCountTolerance} of $expected"
                            )
                        }
                    }
                    val width = stats.width
                    val height = stats.height
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

        if (failures.isNotEmpty()) {
            fail("Fixture harness failures:\n" + failures.joinToString("\n"))
        }
    }

    @Test fun fixture_dragon_scale_plate3() = validateSingleSpec("dragon-scale-plate3.json")
    @Test fun fixture_button_for_s_trousers() = validateSingleSpec("button-for-s-trousers.json")
    @Test fun fixture_colored_benchy() = validateSingleSpec("colored-benchy.json")
    @Test fun fixture_shashibo_plate5() = validateSingleSpec("shashibo-plate5.json")
    @Test fun fixture_slip_slide_spin_plate3() = validateSingleSpec("slip-slide-spin-plate3.json")
    @Test fun fixture_flippy_flappy_plate4() = validateSingleSpec("flippy-flappy-plate4.json")
}
