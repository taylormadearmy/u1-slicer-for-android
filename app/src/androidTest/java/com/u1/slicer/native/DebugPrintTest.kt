package com.u1.slicer.native

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import com.u1.slicer.NativeLibrary
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue

@RunWith(AndroidJUnit4::class)
class DebugPrintTest {
    @Test
    fun debugFlippyFlappy() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val lib = NativeLibrary()
        val dst = File(context.cacheDir, "flippy.3mf")
        context.assets.open("flippy+flappy+mini-with-plate-painted.3mf").use { it.copyTo(dst.outputStream()) }
        assertTrue(lib.loadModel(dst.absolutePath))
        
        val count = lib.nativeGetObjectCount()
        println("DEBUG_OUTPUT: OBJECT COUNT: \$count")
        for (i in 0 until count) {
            val isSplit = lib.nativeIsObjectSplittable(i)
            val parts = lib.nativeGetVolumeCount(i)
            println("DEBUG_OUTPUT: OBJECT \$i IS SPLITTABLE: \$isSplit, VOLUMES: \$parts")
        }
    }
}
