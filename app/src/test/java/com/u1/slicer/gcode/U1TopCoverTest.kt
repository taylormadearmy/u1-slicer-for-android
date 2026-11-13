package com.u1.slicer.gcode

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class U1TopCoverTest {
    @Test fun materialModes() {
        assertTrue(U1TopCover.command(listOf("PLA")).contains("DESIRE_TEMP=42 ALARM_TEMP=48"))
        assertTrue(U1TopCover.command(listOf("PETG")).contains("DESIRE_TEMP=45 ALARM_TEMP=51"))
        assertTrue(U1TopCover.command(listOf("ASA")).contains("MODE=3"))
        assertTrue(U1TopCover.command(listOf("ABS", "PLA")).contains("DESIRE_TEMP=42"))
        assertTrue(U1TopCover.command(listOf("custom")).contains("DESIRE_TEMP=42"))
    }

    @Test fun unusedSoftFilamentDoesNotOverrideUsedAbs() = withFile {
        writeText("PRINT_START\nT1\n; filament used [mm] = 0, 20\nPRINT_END\n")
        assertTrue(U1TopCover.apply(this, listOf("PLA", "ABS")))
        assertTrue(readText().startsWith("PRINT_START\n${U1TopCover.command(listOf("ABS"))}\nT1"))
        assertFalse(U1TopCover.apply(this, listOf("PLA")))
    }

    @Test fun existingCommandIsPreserved() = withFile {
        val source = "PRINT_START\nSET_PURIFIER_MODE MODE=0\nG28\n"
        writeText(source)
        assertFalse(U1TopCover.apply(this, listOf("PLA")))
        assertEquals(source, readText())
    }

    @Test fun noPrintStartIsNotModified() = withFile {
        writeText("G28\n")
        assertFalse(U1TopCover.apply(this, listOf("PLA")))
        assertEquals("G28\n", readText())
    }

    @Test fun remapPreservesPurifier() {
        val command = U1TopCover.command(listOf("PETG"))
        assertEquals(command, GcodeToolRemapper.remapLine(command, mapOf(0 to 3)))
    }

    private fun withFile(block: File.() -> Unit) {
        val file = File.createTempFile("u1-cover-test", ".gcode")
        try { file.block() } finally { file.delete() }
    }
}
