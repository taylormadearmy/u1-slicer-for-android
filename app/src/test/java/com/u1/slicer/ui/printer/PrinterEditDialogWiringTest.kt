package com.u1.slicer.ui.printer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PrinterEditDialogWiringTest {
    private val source = File("src/main/java/com/u1/slicer/ui/printer/PrinterEditDialog.kt").readText()

    @Test
    fun `dialog exposes every Phase 6 host type and credentials`() {
        listOf("HostType.MOONRAKER", "HostType.OCTOPRINT", "HostType.PRUSALINK", "HostType.BAMBU_LAN")
            .forEach { assertTrue("missing $it", source.contains(it)) }
        listOf("API key (optional)", "Username (optional)", "Password (optional)")
            .forEach { assertTrue("missing $it", source.contains(it)) }
    }

    @Test
    fun `dialog uses neutral connection copy`() {
        assertTrue(source.contains("Connects to your printer using the selected host service."))
        assertFalse(source.contains("Connects to your Snapmaker U1"))
    }
}
