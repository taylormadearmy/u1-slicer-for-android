package com.u1.slicer.native

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HammerCrashTest {
    private val lib = NativeLibrary()

    private fun copyAsset(name: String): String {
        val testCtx = InstrumentationRegistry.getInstrumentation().context
        val targetCtx = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(targetCtx.cacheDir, name)
        out.parentFile?.mkdirs()
        testCtx.assets.open(name).use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        return out.absolutePath
    }

    @Test
    fun testHammerCrash() {
        lib.clearModel()
        assertTrue(lib.loadModelForPlate(copyAsset("Hammer.3mf"), 3))
        val splittableIdx = (0 until lib.nativeGetObjectCount())
            .firstOrNull { lib.nativeIsObjectSplittable(it) }
        assertNotNull(splittableIdx)
        
        val res = lib.nativeSplitObject(splittableIdx!!)
        assertNotNull(res)
        println("SPLIT RESULT: removedIdx=${res!![0]}, addedCount=${res[1]}")
        
        // This is what the ViewModel does:
        val boxes = lib.getObjectBoundingBoxes()
        val mins = lib.nativeGetObjectWorldAABBMins()
        
        assertNotNull(boxes)
        assertNotNull(mins)
    }
}
