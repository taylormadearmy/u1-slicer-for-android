package com.u1.slicer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.data.MixedFilamentRow
import com.u1.slicer.data.MixedFilamentManager
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.slicing.SlicingIntegrationTest
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * E2E instrumented test: MixedFilamentManager.serialize() → SliceConfig → engine → G-code header.
 *
 * Mirrors stage 2's mixedFilament_recipeFromSliceConfig_reachesEngineConfigInGcode but uses
 * the Manager-built recipe (M3 Phase A — Task 11).
 */
@RunWith(AndroidJUnit4::class)
class MixedFilamentCreateE2ETest {

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

    private fun asset(name: String): File {
        val dest = File(cacheDir, name)
        InstrumentationRegistry.getInstrumentation().context
            .assets.open(name).use { it.copyTo(dest.outputStream()) }
        return dest
    }

    private fun sliceAsset(assetName: String, config: SliceConfig): Pair<Boolean, String?> {
        val file = asset(assetName)
        val loaded = lib.loadModel(file.absolutePath)
        if (!loaded) return Pair(false, null)
        val result = lib.slice(config) ?: return Pair(false, null)
        if (!result.success) return Pair(false, result.errorMessage)
        val gcode = if (result.gcodePath.isNotEmpty()) File(result.gcodePath).readText() else ""
        return Pair(true, gcode)
    }

    /**
     * Manager.serialize() output reaches the engine config-dump in the G-code header.
     *
     * Creates a MixedFilamentManager with no persistence side-effects (no-op save
     * callbacks), adds one row, serializes it, then slices a small STL with that
     * recipe on SliceConfig.mixedFilamentDefinitions. The engine writes every
     * set config key as a `; key = value` comment in the G-code prelude, so
     * finding "mixed_filament_definitions" in the G-code is the proof.
     */
    @Test
    fun managerSerialize_reachesEngineConfigInGcode() {
        val mgr = MixedFilamentManager(
            loadProject = { emptyList() },
            loadLibrary = { emptyList() },
            saveProject = {},
            saveLibrary = {},
        )
        mgr.add(
            componentA = 1,
            componentB = 2,
            mixBPercent = 50,
            distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
        )
        val recipe = mgr.serialize(numPhysicalFilaments = 2)

        assertTrue(
            "Manager must have produced a non-empty recipe string",
            recipe.isNotEmpty(),
        )
        // Recipe must start with the expected component fields
        assertTrue(
            "Recipe must start with '1,2,1,1,50,' (componentA,componentB,enabled,custom,mixBPercent)",
            recipe.startsWith("1,2,1,1,50,"),
        )

        val cfg = SlicingIntegrationTest.DEFAULT_CONFIG.copy(
            extruderCount = 2,
            mixedFilamentDefinitions = recipe,
        )
        val (success, gcode) = sliceAsset("tetrahedron.stl", cfg)

        assertTrue("Slice with manager-built mixed-filament recipe must succeed", success)
        assertNotNull("G-code must be produced", gcode)
        assertTrue(
            "G-code config-dump must contain 'mixed_filament_definitions' when SliceConfig has a recipe. " +
                "The wiring Manager→SliceConfig→JNI→engine is broken.",
            gcode!!.contains("mixed_filament_definitions"),
        )
        assertTrue(
            "G-code config-dump must contain the recipe content. Recipe: '$recipe'",
            gcode.contains("1,2,1,1,50"),
        )
    }
}
