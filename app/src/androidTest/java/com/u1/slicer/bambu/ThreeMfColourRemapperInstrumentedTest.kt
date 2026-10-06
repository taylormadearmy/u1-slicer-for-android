package com.u1.slicer.bambu

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ThreeMfColourRemapperInstrumentedTest {
    @Test(timeout = 15_000)
    fun benchySizedPaintRemap_completesWithoutRunawayNativeAllocations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.cacheDir, "remap-performance-input.3mf")
        val output = File(context.cacheDir, "remap-performance-output.3mf")
        val triangle = "<triangle v1=\"0\" v2=\"1\" v3=\"2\" paint_color=\"1C\"/>\n"
        val count = 100_000
        try {
            ZipOutputStream(input.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("3D/3dmodel.model"))
                zip.write(("<model>\n" + triangle.repeat(count) + "</model>").toByteArray())
                zip.closeEntry()
            }
            val heapBefore = Debug.getNativeHeapAllocatedSize()
            val start = System.nanoTime()
            ThreeMfColourRemapper.remap(input, output, mapOf(4 to 2), emptyMap())
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            val heapGrowth = Debug.getNativeHeapAllocatedSize() - heapBefore
            Log.i("PaintRemapTest", "elapsedMs=$elapsedMs nativeHeapGrowth=$heapGrowth")
            assertTrue("Paint remap took ${elapsedMs}ms", elapsedMs < 5_000)
            assertTrue("Native heap grew by $heapGrowth bytes", heapGrowth < 128L * 1024 * 1024)
            ZipFile(output).use { zip ->
                val text = zip.getInputStream(zip.getEntry("3D/3dmodel.model")).bufferedReader().readText()
                assertEquals("<model>\n" + triangle.replace("1C", "8").repeat(count) + "</model>", text)
            }
        } finally {
            input.delete()
            output.delete()
        }
    }
}
