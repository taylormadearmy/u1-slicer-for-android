package com.u1.slicer.bambu

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThreeMfColourRemapperInstrumentedTest {
    @Test(timeout = 20_000)
    fun largePaintedModelRemapsToMixWithoutStalling() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File.createTempFile("mix-remap-input", ".3mf", context.cacheDir)
        val output = File.createTempFile("mix-remap-output", ".3mf", context.cacheDir)
        val triangleCount = 100_000
        val model = buildString {
            append("<model><resources><object id=\"1\"><mesh><triangles>\n")
            repeat(triangleCount) {
                append("<triangle v1=\"0\" v2=\"1\" v3=\"2\" paint_color=\"4\" />\n")
            }
            append("</triangles></mesh></object></resources></model>")
        }
        try {
            ZipOutputStream(input.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("3D/3dmodel.model"))
                zip.write(model.toByteArray())
                zip.closeEntry()
            }
            ThreeMfColourRemapper.remap(input, output, mapOf(1 to 5), mapOf(0 to 5))
            ZipFile(output).use { zip ->
                val rewritten = zip.getInputStream(zip.getEntry("3D/3dmodel.model"))
                    .bufferedReader().use { it.readText() }
                assertEquals(model.replace("paint_color=\"4\"", "paint_color=\"2C\""), rewritten)
            }
        } finally {
            input.delete()
            output.delete()
        }
    }
}
