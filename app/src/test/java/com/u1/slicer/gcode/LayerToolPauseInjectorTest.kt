package com.u1.slicer.gcode

import org.junit.Assert.assertEquals
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
                write(
                    zip,
                    "Metadata/project_settings.config",
                    """{"machine_pause_gcode":"M400 U1","nozzle_temperature":["220","230","220","220","220"]}"""
                )
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
            assertTrue(text.contains("; PAUSE_PRINT\nM400 U1\n"))
            assertTrue("Bambu extruder 2 must map to T1 after pause", text.contains("T1"))
            assertTrue("nozzle_temperature[1] should follow T1 for filament 2", text.contains("M109 S230 T1"))
            assertTrue(text.contains(";LAYER_CHANGE\n;Z:1.7"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `injectFrom3mf preserves nozzle_temperature array indexes with nil entries`() {
        val dir = createTempDir(prefix = "layer_tool_pause_nil_indexes_")
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
                // If index handling is wrong (e.g. skipping "nil" entries), toolIndex=1 (T1)
                // could incorrectly pick up the next value (230).
                write(
                    zip,
                    "Metadata/project_settings.config",
                    """{"machine_pause_gcode":"M400 U1","nozzle_temperature":["nil","220","230"]}"""
                )
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
            assertTrue(text.contains("; PAUSE_PRINT\nM400 U1\n"))
            assertTrue(text.contains("T1"))
            assertTrue("Injector must use nozzle_temperature[1]=220 for toolIndex=1 even when index 0 is nil", text.contains("M109 S220"))
            assertFalse("Should not incorrectly use nozzle_temperature[2]=230 due to shifted indexes", text.contains("M109 S230"))
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
            gcode.writeText("M109 S215 T1\n;LAYER_CHANGE\n;Z:0.6\nG1 X2 Y2\n")

            assertTrue(LayerToolPauseInjector.injectFrom3mf(gcode.absolutePath, model))
            val out = gcode.readText()
            assertTrue(out.contains("; PAUSE_PRINT\nM400 U1\n"))
            assertTrue(out.contains("T1"))
            assertTrue("Fallback should reuse tool temp found in G-code prologue", out.contains("M109 S215 T1"))
            assertTrue(out.contains(";LAYER_CHANGE"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `injectFrom3mf uses gcode temp fallback when project settings missing`() {
        val dir = createTempDir(prefix = "layer_tool_pause_gcode_temp_")
        try {
            val model = File(dir, "sample.3mf")
            ZipOutputStream(model.outputStream()).use { zip ->
                write(zip, "Metadata/custom_gcode_per_layer.xml", """
                    <?xml version="1.0" encoding="utf-8"?>
                    <custom_gcodes_per_layer>
                      <plate>
                        <plate_info id="1"/>
                        <layer top_z="1.6" type="1" extruder="3" color="#AABBCC" extra="" gcode="tool_change"/>
                      </plate>
                    </custom_gcodes_per_layer>
                """.trimIndent())
            }

            val gcode = File(dir, "sample.gcode")
            gcode.writeText(
                """
                M109 S225 T2
                ;LAYER_CHANGE
                ;Z:1.7
                G1 X10 Y10
                """.trimIndent() + "\n"
            )

            assertTrue(LayerToolPauseInjector.injectFrom3mf(gcode.absolutePath, model))
            val text = gcode.readText()
            assertTrue(text.contains("; PAUSE_PRINT\nM400 U1\n"))
            assertTrue(text.contains("T2"))
            assertTrue("Missing project settings should still heat selected tool", text.contains("M109 S225 T2"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `injectFrom3mf uses current tool for M109 without T when project settings missing`() {
        val dir = createTempDir(prefix = "layer_tool_pause_gcode_temp_current_tool_")
        try {
            val model = File(dir, "sample.3mf")
            ZipOutputStream(model.outputStream()).use { zip ->
                write(zip, "Metadata/custom_gcode_per_layer.xml", """
                    <?xml version="1.0" encoding="utf-8"?>
                    <custom_gcodes_per_layer>
                      <plate>
                        <plate_info id="1"/>
                        <layer top_z="1.6" type="1" extruder="3" color="#AABBCC" extra="" gcode="tool_change"/>
                      </plate>
                    </custom_gcodes_per_layer>
                """.trimIndent())
            }

            val gcode = File(dir, "sample.gcode")
            gcode.writeText(
                """
                T2
                M109 S225
                ;LAYER_CHANGE
                ;Z:1.7
                G1 X10 Y10
                """.trimIndent() + "\n"
            )

            assertTrue(LayerToolPauseInjector.injectFrom3mf(gcode.absolutePath, model))
            val text = gcode.readText()
            assertTrue(text.contains("; PAUSE_PRINT\nM400 U1\n"))
            assertTrue(text.contains("T2"))

            // The input already contains a tool temp for the current tool (T2). Injector should also
            // inject another `M109 S225` immediately after switching to T2 for the pause.
            val count = Regex("""\bM109\s+S225(?:\s+T2)?\b""").findAll(text).count()
            assertEquals(2, count)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `injectFrom3mf falls back to SM_PRINT_START_LINE target temp`() {
        val dir = createTempDir(prefix = "layer_tool_pause_sm_target_temp_")
        try {
            val model = File(dir, "sample.3mf")
            ZipOutputStream(model.outputStream()).use { zip ->
                write(zip, "Metadata/custom_gcode_per_layer.xml", """
                    <?xml version="1.0" encoding="utf-8"?>
                    <custom_gcodes_per_layer>
                      <plate>
                        <plate_info id="1"/>
                        <layer top_z="1.6" type="1" extruder="3" color="#AABBCC" extra="" gcode="tool_change"/>
                      </plate>
                    </custom_gcodes_per_layer>
                """.trimIndent())
            }

            val gcode = File(dir, "sample.gcode")
            gcode.writeText(
                """
                ;LAYER_CHANGE
                ;Z:1.5
                G1 X0 Y0
                SM_PRINT_START_LINE INDEX=2 TARGET_TEMP=220
                ;LAYER_CHANGE
                ;Z:1.7
                G1 X10 Y10
                """.trimIndent() + "\n"
            )

            assertTrue(LayerToolPauseInjector.injectFrom3mf(gcode.absolutePath, model))
            val text = gcode.readText()
            assertTrue(text.contains("T2"))
            assertTrue("SM target temp fallback should provide explicit wait-temp for switched tool", text.contains("M109 S220 T2"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `injectFrom3mf falls back to last seen M109 temp when tool-specific missing`() {
        val dir = createTempDir(prefix = "layer_tool_pause_last_temp_fallback_")
        try {
            val model = File(dir, "sample.3mf")
            ZipOutputStream(model.outputStream()).use { zip ->
                write(zip, "Metadata/custom_gcode_per_layer.xml", """
                    <?xml version="1.0" encoding="utf-8"?>
                    <custom_gcodes_per_layer>
                      <plate>
                        <plate_info id="1"/>
                        <layer top_z="1.6" type="1" extruder="2" color="#AABBCC" extra="" gcode="tool_change"/>
                      </plate>
                    </custom_gcodes_per_layer>
                """.trimIndent())
            }

            val gcode = File(dir, "sample.gcode")
            gcode.writeText(
                """
                M109 S220 T2
                ;LAYER_CHANGE
                ;Z:1.7
                G1 X10 Y10
                """.trimIndent() + "\n"
            )

            assertTrue(LayerToolPauseInjector.injectFrom3mf(gcode.absolutePath, model))
            val text = gcode.readText()
            assertTrue(text.contains("T1"))
            assertTrue("If tool-specific lookup fails, injector should still set switched tool temp", text.contains("M109 S220 T1"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `injectFrom3mf skips when native CP toolchange workflow already exists`() {
        val dir = createTempDir(prefix = "layer_tool_pause_native_toolchange_")
        try {
            val model = File(dir, "sample.3mf")
            ZipOutputStream(model.outputStream()).use { zip ->
                write(zip, "Metadata/custom_gcode_per_layer.xml", """
                    <?xml version="1.0" encoding="utf-8"?>
                    <custom_gcodes_per_layer>
                      <plate>
                        <plate_info id="1"/>
                        <layer top_z="1.6" type="1" extruder="2" color="#AABBCC" extra="" gcode="tool_change"/>
                      </plate>
                    </custom_gcodes_per_layer>
                """.trimIndent())
                write(
                    zip,
                    "Metadata/project_settings.config",
                    """{"machine_pause_gcode":"M400 U1","nozzle_temperature":["220","220","220","220"]}"""
                )
            }

            val gcode = File(dir, "sample.gcode")
            gcode.writeText(
                """
                ; CP TOOLCHANGE START
                T1
                ; CP TOOLCHANGE END
                ;LAYER_CHANGE
                ;Z:1.7
                G1 X10 Y10
                """.trimIndent() + "\n"
            )

            assertFalse(LayerToolPauseInjector.injectFrom3mf(gcode.absolutePath, model))
            val text = gcode.readText()
            assertFalse(text.contains("; PAUSE_PRINT"))
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
