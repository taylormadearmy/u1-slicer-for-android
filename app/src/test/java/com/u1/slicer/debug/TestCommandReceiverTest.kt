package com.u1.slicer.debug

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TestCommandReceiverTest {

    private fun source(rel: String): String {
        val file = listOf(File(rel), File("../$rel"), File("app/$rel"))
            .firstOrNull { it.exists() }
            ?: error("$rel not found from ${File(".").absolutePath}")
        return file.readText()
    }

    @Test
    fun `dump state includes printer diagnostics`() {
        val src = source("src/main/java/com/u1/slicer/debug/TestCommandReceiver.kt")
        assertTrue(src.contains("PrinterStatus:"))
        assertTrue(src.contains("SendingState:"))
        assertTrue(src.contains("ActivePrinterId:"))
        assertTrue(src.contains("ActivePrinter:"))
        assertTrue(src.contains("PrinterList:"))
        assertTrue(src.contains("Capabilities:"))
        assertTrue(src.contains("CameraState:"))
        assertTrue(src.contains("FilamentSlots: count="))
    }

    @Test
    fun `debug receiver exposes bambu upload action`() {
        val src = source("src/main/java/com/u1/slicer/debug/TestCommandReceiver.kt")
        assertTrue(src.contains("ACTION_UPLOAD_BAMBU_PROJECT"))
        assertTrue(src.contains("UPLOAD_BAMBU_PROJECT: uploading"))
        assertTrue(src.contains("sendBambuProjectUploadOnly"))
    }
}
