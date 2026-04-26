package com.u1.slicer.slicing

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.data.SliceConfig
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Targeted diagnostic for PM-reported bugs #3 (hanging file translate-then-slice) and
 * #4 (calicube enlarged + copied → off-bed). The diagnostic from the user's earlier
 * report showed the slice produced a model placed ~19mm leftward of where the Kotlin
 * caller's CopyArrangeCalculator + setModelInstances combination should have put it.
 *
 * This test exercises the same path against a known centered mesh (3DBenchy.stl)
 * with a non-unit scale + multi-copy arrangement, capturing four diagnostic points:
 *   1. positions Kotlin sent into setModelInstances
 *   2. getInstanceOffsets() immediately after setModelInstances
 *   3. G-code minX/minY after slice
 * Mismatches between any two of these narrow down where the offset divergence is
 * introduced (sapil_arrange.cpp's offset math, the slice path's auto-centering safety
 * net, OrcaSlicer's print.apply / Print::process step, or the G-code writer).
 */
@RunWith(AndroidJUnit4::class)
class SetModelInstancesOffsetTest {

    private lateinit var lib: NativeLibrary
    private lateinit var cacheDir: File

    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val assetContext get() = InstrumentationRegistry.getInstrumentation().context

    @Before
    fun setup() {
        assertTrue("Native library must be loaded", NativeLibrary.isLoaded)
        lib = NativeLibrary()
        lib.clearModel()
        cacheDir = File(targetContext.cacheDir, "offset_diag").also { it.mkdirs() }
    }

    @After
    fun teardown() {
        lib.clearModel()
        cacheDir.deleteRecursively()
    }

    private fun copyAsset(name: String): File {
        val out = File(cacheDir, name.replace("/", "_"))
        assetContext.assets.open(name).use { it.copyTo(out.outputStream()) }
        return out
    }

    /**
     * Reproduce PM bug #4: calib-cube-10-dual-colour-merged.3mf at the user's
     * settings (scale 2.5793, 7 copies). Captures positions sent to setModelInstances,
     * native's stored offsets, and G-code bounds. Logs everything via OffsetDiag tag
     * even on failure so the diagnostic is visible without re-running.
     *
     * Stays in 1-copy + 1.5x form first to avoid OOM; once we have a baseline shape
     * we can scale up.
     */
    @Test
    fun calicubeScaleSingleCopy_offsetMatchesGcodeMinX() {
        val file = File(cacheDir, "calib-cube-10-dual-colour-merged.3mf")
        assetContext.assets.open("calib-cube-10-dual-colour-merged.3mf").use {
            it.copyTo(file.outputStream())
        }
        // The 3MF embeds a Bambu profile but for the offset diagnostic we just want
        // the raw mesh + setModelScale + setModelInstances → slice path. Loading the
        // 3MF directly via NativeLibrary.loadModel exercises the same arrange code.
        assertTrue("loadModel calicube", lib.loadModel(file.absolutePath))

        val mi = lib.getModelInfo()
        assertNotNull("getModelInfo", mi)
        Log.i("OffsetDiag", "calicube modelInfo: size=${mi!!.sizeX}x${mi.sizeY}x${mi.sizeZ}mm")

        val scale = 1.5f
        assertTrue("setModelScale", lib.setModelScale(scale, scale, scale))

        val scaledSizeX = mi.sizeX * scale
        val scaledSizeY = mi.sizeY * scale
        Log.i("OffsetDiag", "calicube scaledSize: ${scaledSizeX}x${scaledSizeY}mm")

        val originX = 30f
        val originY = 40f
        val positions = floatArrayOf(originX, originY)
        Log.i("OffsetDiag", "calicube requested positions=${positions.toList()}")
        assertTrue("setModelInstances", lib.setModelInstances(positions))

        val storedOffsets = lib.getInstanceOffsets()
        Log.i("OffsetDiag", "calicube storedOffsets=${storedOffsets.toList()}")
        Log.i("OffsetDiag", "calicube expected stored.x = pos - sf*meshBB.min.x — sf=${scale} ; if mesh is centered with min.x=-${mi.sizeX / 2}, expect ${originX + scale * mi.sizeX / 2}")

        val result = lib.slice(SliceConfig().copy(extruderCount = 1))
        assertNotNull("slice", result)
        assertTrue("slice success: ${result!!.errorMessage}", result.success)
        val gcode = File(result.gcodePath).readText()
        val xRegex = Regex("""G[01]\s+(?:[^\s;]+\s+)*X(-?[\d.]+)""")
        val xs = xRegex.findAll(gcode).mapNotNull { it.groupValues[1].toFloatOrNull() }
            .filter { it > 0f }.toList()
        val gcodeMinX = if (xs.isNotEmpty()) xs.min() else Float.NaN
        val gcodeMaxX = if (xs.isNotEmpty()) xs.max() else Float.NaN
        Log.i("OffsetDiag", "calicube gcodeBounds: x=[$gcodeMinX, $gcodeMaxX]")
        Log.i("OffsetDiag", "calicube expected: minX=$originX")
        // Copy G-code to a stable, persistent path so we can inspect it post-teardown.
        val keepPath = File(targetContext.cacheDir, "calicube_diag.gcode")
        File(result.gcodePath).copyTo(keepPath, overwrite = true)
        Log.i("OffsetDiag", "calicube gcode persisted to ${keepPath.absolutePath}")
        // Also log the first 30 G1 X moves so we can read them straight out of logcat.
        val firstMoves = Regex("""^G[01]\s+(?:[^\n;]*?\b)X(-?[\d.]+)[^\n;]*?\bY(-?[\d.]+)""")
            .findAll(gcode)
            .take(30)
            .map { it.value.trim() }
            .toList()
        firstMoves.forEachIndexed { i, line -> Log.i("OffsetDiag", "calicube g1[$i]: $line") }

        // Soft assertion — log diagnostic on either branch so we capture the data.
        if (abs(gcodeMinX - originX) > 5f) {
            Log.e("OffsetDiag", "calicube REGRESSION: gcodeMinX=$gcodeMinX vs requested=$originX (diff=${gcodeMinX - originX}mm)")
        }
        assertTrue(
            "calicube gcodeMinX ($gcodeMinX) should match requested origin ($originX) ±5mm. " +
                "stored offset.x=${storedOffsets.getOrNull(0)} (storedOffset - pos = ${storedOffsets.getOrNull(0)?.minus(originX)}mm).",
            abs(gcodeMinX - originX) <= 5f
        )
    }

    /**
     * Reproduce PM bug #4: calib-cube-10-dual-colour-merged.3mf at the user's
     * settings (scale 2.5793, 7 copies). Captures positions sent to setModelInstances,
     * native's stored offsets, and G-code bounds. Logs everything via OffsetDiag tag
     * even on failure so the diagnostic is visible without re-running.
     *
     * Stays in 1-copy + 1.5x form first to avoid OOM; once we have a baseline shape
     * we can scale up.
     */
    @Test
    fun stlScaleMultiCopy_offsetMatchesGcodeMinX() {
        val file = copyAsset("3DBenchy.stl")
        assertTrue("loadModel", lib.loadModel(file.absolutePath))

        val mi = lib.getModelInfo()
        assertNotNull("getModelInfo", mi)
        Log.i("OffsetDiag", "modelInfo: size=${mi!!.sizeX}x${mi.sizeY}x${mi.sizeZ}mm tris=${mi.triangleCount}")

        // 1 copy at 1.5× scale to keep the slice memory-light while still exercising
        // the offset+scale path. The 4-copy variant OOMs the test process.
        val scale = 1.5f
        assertTrue("setModelScale", lib.setModelScale(scale, scale, scale))

        val scaledSizeX = mi.sizeX * scale
        val scaledSizeY = mi.sizeY * scale
        Log.i("OffsetDiag", "scaledSize: ${scaledSizeX}x${scaledSizeY}mm")

        val originX = 30f
        val originY = 40f
        val positions = floatArrayOf(originX, originY)
        Log.i("OffsetDiag", "requested positions=${positions.toList()}")
        assertTrue("setModelInstances", lib.setModelInstances(positions))

        // Read back what native stored.
        val storedOffsets = lib.getInstanceOffsets()
        Log.i("OffsetDiag", "storedOffsets=${storedOffsets.toList()}")
        assertTrue("offsets non-empty", storedOffsets.isNotEmpty())

        // Slice and parse G-code bounds.
        val result = lib.slice(SliceConfig().copy(extruderCount = 1))
        assertNotNull("slice result", result)
        assertTrue("slice success: ${result!!.errorMessage}", result.success)
        val gcode = File(result.gcodePath).readText()

        val xRegex = Regex("""G[01]\s+(?:[^\s;]+\s+)*X(-?[\d.]+)""")
        val yRegex = Regex("""G[01]\s+(?:[^\s;]+\s+)*Y(-?[\d.]+)""")
        val xs = xRegex.findAll(gcode).mapNotNull { it.groupValues[1].toFloatOrNull() }
            .filter { it > 0f }.toList()
        val ys = yRegex.findAll(gcode).mapNotNull { it.groupValues[1].toFloatOrNull() }
            .filter { it > 0f }.toList()
        val gcodeMinX = if (xs.isNotEmpty()) xs.min() else Float.NaN
        val gcodeMinY = if (ys.isNotEmpty()) ys.min() else Float.NaN
        val gcodeMaxX = if (xs.isNotEmpty()) xs.max() else Float.NaN
        val gcodeMaxY = if (ys.isNotEmpty()) ys.max() else Float.NaN

        Log.i("OffsetDiag", "gcodeBounds: x=[$gcodeMinX, $gcodeMaxX] y=[$gcodeMinY, $gcodeMaxY]")
        Log.i("OffsetDiag", "expected: minX=$originX minY=$originY " +
            "maxX=${originX + scaledSizeX} maxY=${originY + scaledSizeY}")

        // The minimum extrusion X should be at or near the requested origin (allowing a few mm
        // for skirt and wipe-tower clearance — those add OUTSIDE the model footprint).
        // If the bug from the PM report is present, gcodeMinX will be ~scaledSizeX/2 less
        // than originX (model-centred-on-position convention vs. lower-left convention).
        val tolerance = 5f
        assertTrue(
            "gcodeMinX ($gcodeMinX) should be within ±${tolerance}mm of requested origin ($originX). " +
                "If shifted by ~scaledSizeX/2 (${scaledSizeX / 2}), the offset convention in " +
                "sapil_arrange.cpp differs from CopyArrangeCalculator's lower-left convention.",
            abs(gcodeMinX - originX) <= tolerance
        )
        assertTrue(
            "gcodeMinY ($gcodeMinY) should be within ±${tolerance}mm of requested origin ($originY)",
            abs(gcodeMinY - originY) <= tolerance
        )
    }
}
