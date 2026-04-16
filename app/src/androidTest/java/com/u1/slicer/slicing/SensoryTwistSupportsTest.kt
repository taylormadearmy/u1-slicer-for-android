package com.u1.slicer.slicing

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.BambuSanitizer
import com.u1.slicer.bambu.ProfileEmbedder
import com.u1.slicer.bambu.ThreeMfParser
import com.u1.slicer.data.SliceConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * B77 fix verification: Sensory Twist Ball has per-object enable_support=1 in
 * model_settings.config plus 2870 paint_supports="4" triangles on the mesh.
 * Before the BambuSanitizer pass-through fix, per-object metadata was dropped
 * and U1 produced 0 Support features (Bambu Studio emits 334 Support + Support
 * interface features from the same file).
 */
@RunWith(AndroidJUnit4::class)
class SensoryTwistSupportsTest {

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

    private fun makeConfig(extCount: Int = 1) = SliceConfig(
        layerHeight = 0.2f,
        firstLayerHeight = 0.2f,
        fillDensity = 0.05f,    // match 3MF's 5% embedded infill
        perimeters = 2,
        supportEnabled = false, // global off; per-object override must turn it on
        extruderCount = extCount,
        extruderTemps = IntArray(extCount) { 220 },
        nozzleTemp = 220,
        bedTemp = 55,
        wipeTowerEnabled = false
    )

    @Before
    fun setUp() {
        assertTrue("Native library required", NativeLibrary.isLoaded)
        lib = NativeLibrary()
        lib.clearModel()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        cacheDir = ctx.cacheDir
        outDir = File(cacheDir, "sensory_twist_test").also { it.mkdirs() }
        embedder = ProfileEmbedder(ctx)
    }

    @After
    fun tearDown() {
        lib.clearModel()
        outDir.deleteRecursively()
    }

    @Test
    fun sensoryTwist_paintOnSupports_producesSupportGcode() {
        val input = asset("SENSORY+TWIST+BALL+FIDGETS+optimised.3mf")
        val info = ThreeMfParser.parse(input)
        assertTrue("Sensory Twist must be detected as hasPaintSupports",
            info.hasPaintSupports)

        val sanitized = BambuSanitizer.process(input, outDir)
        val config = embedder.buildConfig(info = info, targetExtruderCount = 1)
        val embedded = embedder.embed(sanitized, config, outDir, info)

        assertTrue("loadModel must succeed", lib.loadModel(embedded.absolutePath))
        val result = lib.slice(makeConfig(1))
        assertNotNull("slice() must not return null", result)
        result!!
        assertTrue("Sensory Twist must slice successfully: ${result.errorMessage}",
            result.success)

        val gcode = File(result.gcodePath).readText()
        val supportCount = gcode.lines().count {
            val t = it.trim()
            t == "; FEATURE: Support" || t == "; FEATURE: Support interface"
        }
        Log.i("SensoryTwistTest", "Support feature count: $supportCount")
        assertTrue(
            "Sensory Twist must emit >0 Support features " +
            "(paint_supports + per-object enable_support=1). Got $supportCount",
            supportCount > 0
        )
    }
}
