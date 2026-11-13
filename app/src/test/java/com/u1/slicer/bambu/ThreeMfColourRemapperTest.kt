package com.u1.slicer.bambu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ThreeMfColourRemapperTest {
    @Test fun `implicit first filament becomes an explicit physical assignment for build objects`() {
        val input = kotlin.io.path.createTempFile("implicit", ".3mf").toFile()
        val output = kotlin.io.path.createTempFile("physical", ".3mf").toFile()
        try {
            ZipOutputStream(input.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("3D/3dmodel.model"))
                zip.write("""<model><resources><object id="99"/></resources><build><item objectid="2"/><item objectid='4'/><item objectid="2"/></build></model>""".toByteArray())
                zip.closeEntry()
            }
            ThreeMfColourRemapper.remap(input, output, emptyMap(), mapOf(0 to 4))
            ZipFile(output).use { zip ->
                val settings = zip.getInputStream(zip.getEntry("Metadata/model_settings.config")).bufferedReader().readText()
                assertEquals("<config><object id=\"2\"><metadata key=\"extruder\" value=\"4\"/></object><object id=\"4\"><metadata key=\"extruder\" value=\"4\"/></object></config>", settings)
            }
            ThreeMfColourRemapper.remap(input, output, emptyMap(), mapOf(0 to 1))
            ZipFile(output).use { zip -> assertNull(zip.getEntry("Metadata/model_settings.config")) }
        } finally { input.delete(); output.delete() }
    }
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
