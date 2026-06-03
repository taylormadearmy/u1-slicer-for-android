package com.u1.slicer.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F94: the "Preparing G-code" banner must appear on the Printer screen the moment a
 * send action is confirmed, before the (potentially ~80s) remap runs. Structural guard
 * because the project has no Compose UI / Robolectric harness for PrinterViewModel.
 */
class SendPreparingBannerTest {

    private fun source(rel: String): String {
        val f = listOf(File(rel), File("../$rel"), File("app/$rel"))
            .firstOrNull { it.exists() }
            ?: error("$rel not found from ${File(".").absolutePath}")
        return f.readText()
    }

    @Test fun `PrinterViewModel declares Preparing state and helpers`() {
        val src = source("src/main/java/com/u1/slicer/printer/PrinterViewModel.kt")
        assertTrue("Preparing state missing", src.contains("object Preparing : SendingState()"))
        assertTrue("beginSendPreparing() missing", src.contains("fun beginSendPreparing()"))
        assertTrue("reportSendError() missing", src.contains("fun reportSendError("))
    }

    @Test fun `PrinterScreen renders a Preparing arm`() {
        val src = source("src/main/java/com/u1/slicer/ui/PrinterScreen.kt")
        assertTrue("Preparing arm missing in PrinterScreen",
            src.contains("SendingState.Preparing"))
        assertTrue("Preparing card text missing",
            src.contains("Preparing G-code"))
    }

    @Test fun `all three send sites trigger beginSendPreparing`() {
        val src = source("src/main/java/com/u1/slicer/MainActivity.kt")
        val count = Regex("beginSendPreparing\\(\\)").findAll(src).count()
        assertTrue("Expected >= 3 beginSendPreparing() calls (UploadOnly, PrintAndUpload, " +
            "Absent), found $count", count >= 3)
    }

    @Test fun `send sites surface prep failures as Error`() {
        val src = source("src/main/java/com/u1/slicer/MainActivity.kt")
        val count = Regex("reportSendError\\(").findAll(src).count()
        assertTrue("Expected >= 3 reportSendError() calls, found $count", count >= 3)
    }
}
