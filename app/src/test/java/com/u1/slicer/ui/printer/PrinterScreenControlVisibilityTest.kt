package com.u1.slicer.ui.printer

import com.u1.slicer.printer.PrinterTransportCapabilities
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrinterScreenControlVisibilityTest {

    @Test
    fun `Moonraker controls are visible when their capabilities are available`() {
        val visibility = resolvePrinterScreenControlVisibility(
            capabilities = PrinterTransportCapabilities(
                supportsCamera = true,
                supportsLightControl = true,
                supportsHeaterControl = true,
                supportsConsole = true,
            ),
            isConnected = true,
            isIdle = true,
        )

        assertTrue(visibility.showCamera)
        assertTrue(visibility.showLightControl)
        assertTrue(visibility.showHeaterControl)
        assertTrue(visibility.showConsole)
    }

    @Test
    fun `upload host controls stay hidden without their capabilities`() {
        val visibility = resolvePrinterScreenControlVisibility(
            capabilities = PrinterTransportCapabilities(supportsUpload = true),
            isConnected = true,
            isIdle = true,
        )

        assertFalse(visibility.showCamera)
        assertFalse(visibility.showLightControl)
        assertFalse(visibility.showHeaterControl)
        assertFalse(visibility.showConsole)
    }

    @Test
    fun `console requires an idle connected printer even when supported`() {
        val capabilities = PrinterTransportCapabilities(supportsConsole = true)

        assertFalse(resolvePrinterScreenControlVisibility(capabilities, isConnected = false, isIdle = true).showConsole)
        assertFalse(resolvePrinterScreenControlVisibility(capabilities, isConnected = true, isIdle = false).showConsole)
    }
}
