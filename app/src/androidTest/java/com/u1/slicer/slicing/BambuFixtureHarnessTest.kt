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

    private fun embedAndLoadForPlate(assetName: String, plateIndex0Based: Int?): LoadResult {
        val file = copyAsset(assetName)
        val info = ThreeMfParser.parse(file)
        val target = info.detectedExtruderCount.coerceAtLeast(1)
        // Multi-extruder Bambu files require sourceConfig to be passed to
        // buildConfig so the embed uses the preserve path (source config +
        // Snapmaker hardware overlay) instead of the standard profile stack.
        // The standard stack is single-extruder; without sourceConfig the
        // multi-color fixtures slice as single-tool (canary: Button-for-S-
        // trousers came back T0=2, T1=T2=T3=0). Mirrors SemmSlicingTest's
        // setup for SEMM models.
        val sourceConfig = if (info.isBambu) {
            java.util.zip.ZipFile(file).use { embedder.parseSourceConfig(it) }
        } else null
        val config = embedder.buildConfig(
            info = info,
            sourceConfig = sourceConfig,
            targetExtruderCount = target
        )
        // Mirror SlicerViewModel's selectPlate routing:
        //  - multi-plate Bambu: embed with plate filter (sub-plan #2d strip pipeline).
        //  - single-plate Bambu: BambuSanitizer.process to strip Bambu-specific xmlns
        //    + p:path component refs, then embed; native otherwise rejects the load.
        val sourceForEmbed = when {
            info.isMultiPlate -> file
            info.isBambu -> BambuSanitizer.process(file, outDir)
            else -> file
        }
        // ProfileEmbedder.embed expects a 1-based BBS plateId. The harness
        // exposes 0-based plateIndex in the spec JSON; convert here.
        val embedPlateId = plateIndex0Based?.let { it + 1 }
        val embedded = embedder.embed(sourceForEmbed, config, outDir, info, plateId = embedPlateId)
        assertTrue(
            "loadModel must succeed for $assetName plate=$embedPlateId",
            lib.loadModel(embedded.absolutePath)
        )
        val state = NativePlateState.parseVolumeMapJson(lib.nativeGetAllVolumeExtruders())
        return LoadResult(info, state)
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
                val plateIdx = plate.plateIndex.takeIf { it >= 0 }
                val (info, state) = embedAndLoadForPlate(spec.file, plateIdx)
                val enriched = enrichedUsedExtruders(lib, info, state, plateIdx)

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
                    // The default SliceConfig sets extruderCount=1 which forces
                    // single-extruder slicing regardless of the embedded
                    // profile. For multi-extruder fixtures that suppresses
                    // T1..T3 in the G-code (canary: button-for-s-trousers
                    // came back T0=2, T1=T2=T3=0). Mirror SemmSlicingTest's
                    // pattern and parameterise extruderCount + per-extruder
                    // arrays + wipe tower from the enriched extruder set.
                    val nExt = enriched.size.coerceAtLeast(1)
                    val sliceCfg = SliceConfig(
                        extruderCount = nExt,
                        extruderTemps = IntArray(nExt) { 220 },
                        wipeTowerEnabled = nExt > 1,
                        wipeTowerX = 170f,
                        wipeTowerY = 140f,
                        wipeTowerWidth = 60f
                    )
                    val result = lib.slice(sliceCfg)
                    assertNotNull("$tag: slice returned null", result)
                    assertTrue("$tag: slice failed: ${result!!.errorMessage}", result.success)
                    val toolCounts = parseToolCounts(result.gcodePath)
                    val (width, height) = parseGcodeBounds(result.gcodePath)
                    // Diagnostic: always log the actual T0..T3 counts and G-code
                    // bounds for this slice so a logcat scrape can populate or
                    // re-baseline `expectedToolCounts` in the fixture spec JSON
                    // without weakening assertions. Read with:
                    //   adb logcat -s FixtureHarness -d | grep "ACTUAL"
                    Log.i(
                        "FixtureHarness",
                        "ACTUAL $tag: T0=${toolCounts["T0"] ?: 0} " +
                            "T1=${toolCounts["T1"] ?: 0} " +
                            "T2=${toolCounts["T2"] ?: 0} " +
                            "T3=${toolCounts["T3"] ?: 0} " +
                            "width=${"%.1f".format(width)}mm height=${"%.1f".format(height)}mm"
                    )
                    for ((tool, expected) in plate.expectedToolCounts) {
                        val actual = toolCounts[tool] ?: 0
                        if (abs(actual - expected) > plate.toolCountTolerance) {
                            failures.add(
                                "$tag: $tool count $actual not within ±${plate.toolCountTolerance} of $expected"
                            )
                        }
                    }
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
