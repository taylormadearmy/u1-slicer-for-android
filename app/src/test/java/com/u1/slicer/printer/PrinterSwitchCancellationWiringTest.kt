package com.u1.slicer.printer

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PrinterSwitchCancellationWiringTest {
    private fun source(): String =
        File("src/main/java/com/u1/slicer/printer/PrinterViewModel.kt").readText()

    @Test
    fun `switchActivePrinter cancels active printer jobs and bumps generation before switching`() {
        val src = source()
        assertTrue(src.contains("activePrinterGeneration.incrementAndGet()"))
        assertTrue(src.contains("cancelActivePrinterJobs()"))
        assertTrue(src.contains("_sendingState.value = SendingState.Idle"))
    }

    @Test
    fun `printer actions capture and check active printer context before publishing results`() {
        val src = source()
        assertTrue(src.contains("val actionContext = capturePrinterActionContext(requestedGeneration)"))
        assertTrue(src.contains("if (!isCurrentPrinterAction(actionContext)) return@launch"))
    }

    @Test
    fun `repository checks transport identity between upload and physical start`() {
        val src = File("src/main/java/com/u1/slicer/printer/PrinterRepository.kt").readText()
        assertTrue(src.contains("if (currentTransport !== transport)"))
        assertTrue(src.contains("the print was not started"))
    }
}
