package com.u1.slicer.gcode

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LayerToolPauseInjectorTest {

    @Test
    fun `injectFrom3mf inserts pause before first layer above target top_z`() {
        val dir = createTempDir(prefix = "layer_tool_pause_")
        try {
            val model = File(dir, "sample.3mf")
            ZipOutputStream(model.outputStream()).use { zip ->
                write(zip, "Metadata/custom_gcode_per_layer.xml", """
                    <?xml version="1.0" encoding="utf-8"?>
                    <custom_gcodes_per_layer>
                      <plate>
                        <plate_info id="1"/>
                        <layer top_z="1.6" type="1" extruder="2" color="#F4D976" extra="" gcode="M601"/>
                        <mode value="SingleExtruder"/>
                      </plate>
                    </custom_gcodes_per_layer>
                """.trimIndent())
                write(zip, "Metadata/project_settings.config", """{"machine_pause_gcode":"M400 U1"}""")
            }

            val gcode = File(dir, "sample.gcode")
            gcode.writeText(
                """
                ;LAYER_CHANGE
                ;Z:1.5
                G1 X1 Y1
                ;LAYER_CHANGE
                ;Z:1.7
                G1 X2 Y2
                """.trimIndent() + "\n"
            )

            assertTrue(LayerToolPauseInjector.injectFrom3mf(gcode.absolutePath, model))
            val text = gcode.readText()
            assertTrue(text.contains("; PAUSE_PRINT\nM400 U1\n\n;LAYER_CHANGE\n;Z:1.7"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `injectFrom3mf does nothing when no custom layer metadata exists`() {
        val dir = createTempDir(prefix = "layer_tool_pause_empty_")
        try {
            val model = File(dir, "sample.3mf")
            ZipOutputStream(model.outputStream()).use { zip ->
                write(zip, "Metadata/project_settings.config", """{"machine_pause_gcode":"M400 U1"}""")
            }
            val gcode = File(dir, "sample.gcode")
            gcode.writeText(";LAYER_CHANGE\n;Z:0.2\n")

            assertFalse(LayerToolPauseInjector.injectFrom3mf(gcode.absolutePath, model))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `injectFrom3mf falls back to default pause command when source has no project settings`() {
        val dir = createTempDir(prefix = "layer_tool_pause_default_")
        try {
            val model = File(dir, "sample.3mf")
            ZipOutputStream(model.outputStream()).use { zip ->
                write(zip, "Metadata/custom_gcode_per_layer.xml", """
                    <?xml version="1.0" encoding="utf-8"?>
                    <custom_gcodes_per_layer>
                      <plate>
                        <plate_info id="1"/>
                        <layer top_z="0.4" type="2" extruder="2" color="#F4D976" extra="" gcode="tool_change"/>
                      </plate>
                    </custom_gcodes_per_layer>
                """.trimIndent())
            }
            val gcode = File(dir, "sample.gcode")
            gcode.writeText(";LAYER_CHANGE\n;Z:0.6\nG1 X2 Y2\n")

            assertTrue(LayerToolPauseInjector.injectFrom3mf(gcode.absolutePath, model))
            assertTrue(gcode.readText().contains("; PAUSE_PRINT\nM400 U1\n\n;LAYER_CHANGE"))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun write(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray())
        zip.closeEntry()
    }
}
