package com.u1.slicer.native

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeAdaptiveLayerHeightTest {
    private fun copyAsset(name: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, name)
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use(input::copyTo) }
        return file.absolutePath
    }

    @Test fun adaptiveProfile_generatesReportsAndClears() {
        val lib = NativeLibrary()
        assertTrue(lib.loadModel(copyAsset("3DBenchy.stl")))
        assertNull(lib.nativeGetVariableLayerHeightRange())
        val range = lib.nativeSetAdaptiveLayerHeight(.5f, 5, false)
        assertNotNull(range)
        requireNotNull(range)
        assertTrue(range[0] > 0f && range[1] >= range[0] && range[2] > 2f)
        assertTrue(lib.nativeClearVariableLayerHeights())
        assertNull(lib.nativeGetVariableLayerHeightRange())
        lib.clearModel()
    }
}
