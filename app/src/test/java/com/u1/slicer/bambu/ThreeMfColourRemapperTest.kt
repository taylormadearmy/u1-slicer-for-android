package com.u1.slicer.bambu

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ThreeMfColourRemapperTest {
    @Test fun `rewrites consecutive attributes and preserves unmapped or invalid paint`() {
        val input = kotlin.io.path.createTempFile("mix-in", ".3mf").toFile()
        val output = kotlin.io.path.createTempFile("mix-out", ".3mf").toFile()
        val model = "<model><triangle paint_color=\"4\"/><triangle mmu_segmentation=\"8\"/>" +
            "<triangle slic3rpe:mmu_segmentation=\"4\"/><triangle paint_color=\"invalid\"/></model>"
        try {
            ZipOutputStream(input.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("3D/3dmodel.model"))
                zip.write(model.toByteArray())
                zip.closeEntry()
            }
            ThreeMfColourRemapper.remap(input, output, mapOf(1 to 5), emptyMap())
            ZipFile(output).use { zip ->
                val rewritten = zip.getInputStream(zip.getEntry("3D/3dmodel.model"))
                    .bufferedReader().use { it.readText() }
                assertEquals(model.replace("=\"4\"", "=\"2C\""), rewritten)
            }
        } finally {
            input.delete()
            output.delete()
        }
    }

    @Test fun `rewrites painted and component metadata while preserving other entries`() {
        val input = kotlin.io.path.createTempFile("f99-in", ".3mf").toFile()
        val output = kotlin.io.path.createTempFile("f99-out", ".3mf").toFile()
        ZipOutputStream(input.outputStream()).use { zip ->
            fun put(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
            put("3D/3dmodel.model", "<triangle paint_color=\"8C\"/>")
            put("Metadata/model_settings.config", "<metadata key=\"extruder\" value=\"2\"/>")
            put("keep.txt", "unchanged")
        }
        ThreeMfColourRemapper.remap(input, output, mapOf(11 to 5), mapOf(1 to 5))
        ZipFile(output).use { zip ->
            val model = zip.getInputStream(zip.getEntry("3D/3dmodel.model")).bufferedReader().readText()
            val settings = zip.getInputStream(zip.getEntry("Metadata/model_settings.config")).bufferedReader().readText()
            assertEquals(setOf(5), PaintColorDecoder.decodeStates(Regex("\"([^\"]+)\"").find(model)!!.groupValues[1]))
            assertEquals("<metadata key=\"extruder\" value=\"5\"/>", settings)
            assertEquals("unchanged", zip.getInputStream(zip.getEntry("keep.txt")).bufferedReader().readText())
        }
        input.delete(); output.delete()
    }
}
