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
     * Reproduce the calicube #4 path: load STL, scale up, place multiple copies in a
     * known grid, slice, parse G-code minX. Compare to expected = position[leftmost].
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
