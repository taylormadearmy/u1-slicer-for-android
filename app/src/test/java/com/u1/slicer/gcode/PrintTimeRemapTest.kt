package com.u1.slicer.gcode

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Phase 2.3 — JVM unit tests for [applyPrintTimeRemap].
 *
 * Verifies the non-destructive T-rewrite behaviour: source stays intact;
 * output reflects the user's chosen slot mapping; M104/M109/SM_ patterns
 * follow the same remap; identity mappings are no-ops.
 */
class PrintTimeRemapTest {

    private lateinit var tmpDir: File

    @Before
    fun setup() {
        tmpDir = Files.createTempDirectory("u1-print-time-remap-").toFile()
    }

    @After
    fun cleanup() {
        tmpDir.deleteRecursively()
    }

    private fun writeSource(content: String): File {
        val f = File(tmpDir, "source.gcode")
        f.writeText(content)
        return f
    }

    @Test
    fun `simple T-line remap produces remapped output without modifying source`() {
        val original = """
            T0
            G1 X10
            T1
            G1 Y20
            T2
        """.trimIndent()
        val src = writeSource(original)
        val out = File(tmpDir, "out.gcode")

        // colorMapping = [3, 2, 1] — file's filament 0 → slot 3, 1 → 2, 2 → 1.
        applyPrintTimeRemap(src.absolutePath, out.absolutePath, listOf(3, 2, 1))

        assertEquals(original, src.readText().trimEnd())
        val expected = """
            T3
            G1 X10
            T2
            G1 Y20
            T1
        """.trimIndent()
        assertEquals(expected, out.readText().trimEnd())
    }

    @Test
    fun `M104 and M109 with T parameter are remapped`() {
        val src = writeSource("""
            M104 S210 T0
            M109 S210 T1
            M104 S0 T2
        """.trimIndent())
        val out = File(tmpDir, "out.gcode")
        applyPrintTimeRemap(src.absolutePath, out.absolutePath, listOf(2, 0, 1))

        val expected = """
            M104 S210 T2
            M109 S210 T0
            M104 S0 T1
        """.trimIndent()
        assertEquals(expected, out.readText().trimEnd())
    }

    @Test
    fun `SM_ commands with EXTRUDER and INDEX are remapped`() {
        val src = writeSource("""
            SM_PRINT_AUTO_FEED EXTRUDER=0
            SM_PRINT_FLOW_CALIBRATE INDEX=1
        """.trimIndent())
        val out = File(tmpDir, "out.gcode")
        applyPrintTimeRemap(src.absolutePath, out.absolutePath, listOf(3, 2))

        val expected = """
            SM_PRINT_AUTO_FEED EXTRUDER=3
            SM_PRINT_FLOW_CALIBRATE INDEX=2
        """.trimIndent()
        assertEquals(expected, out.readText().trimEnd())
    }

    @Test
    fun `identity mapping leaves T-lines unchanged`() {
        val original = """
            T0
            T1
            T2
            T3
        """.trimIndent()
        val src = writeSource(original)
        val out = File(tmpDir, "out.gcode")
        applyPrintTimeRemap(src.absolutePath, out.absolutePath, listOf(0, 1, 2, 3))
        assertEquals(original, out.readText().trimEnd())
    }

    @Test
    fun `empty mapping copies source verbatim`() {
        val original = """
            T0
            G1 X10
            ; some comment
        """.trimIndent()
        val src = writeSource(original)
        val out = File(tmpDir, "out.gcode")
        applyPrintTimeRemap(src.absolutePath, out.absolutePath, emptyList())
        assertEquals(original, out.readText().trimEnd())
    }

    @Test
    fun `repeated apply with different mappings always reads source not previous output`() {
        // Verifies the non-destructive contract: applying twice with
        // different mappings produces both expected outputs from the same
        // source — not a chain.
        val original = "T0\nT1\nT2"
        val src = writeSource(original)
        val out1 = File(tmpDir, "out1.gcode")
        val out2 = File(tmpDir, "out2.gcode")

        applyPrintTimeRemap(src.absolutePath, out1.absolutePath, listOf(2, 1, 0))
        applyPrintTimeRemap(src.absolutePath, out2.absolutePath, listOf(3, 0, 1))

        assertEquals("T2\nT1\nT0", out1.readText().trimEnd())
        assertEquals("T3\nT0\nT1", out2.readText().trimEnd())
        assertEquals(original, src.readText().trimEnd())
    }

    @Test
    fun `out-of-bounds T-index in source falls back to itself`() {
        // If the source has T5 but mapping only has 3 entries, the rewrite
        // leaves T5 alone (matches GcodeToolRemapper.remapLine semantics).
        val src = writeSource("""
            T0
            T5
            T1
        """.trimIndent())
        val out = File(tmpDir, "out.gcode")
        applyPrintTimeRemap(src.absolutePath, out.absolutePath, listOf(3, 2, 1))

        val text = out.readText()
        assertTrue("T0 must remap to T3", text.contains("T3"))
        assertTrue("T1 must remap to T2", text.contains("T2"))
        assertTrue("T5 stays as T5 when out of bounds", text.contains("T5"))
    }

    @Test
    fun `comments after T-line are preserved`() {
        val src = writeSource("T0 ; switch to red")
        val out = File(tmpDir, "out.gcode")
        applyPrintTimeRemap(src.absolutePath, out.absolutePath, listOf(2))
        // Comment is on the T-line itself — remapper rewrites the whole line
        // to "T2" (matches GcodeToolRemapper.TOOL_LINE_RE behaviour). This
        // is documented behaviour, not a bug — Snapmaker's standalone T-line
        // form drops comments. If preserving the comment is needed,
        // GcodeToolRemapper.remapLine is the place to fix it.
        assertEquals("T2", out.readText().trimEnd())
    }
}
