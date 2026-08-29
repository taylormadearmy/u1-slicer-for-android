package com.u1.slicer.printer

import com.u1.slicer.data.BambuConfig
import com.u1.slicer.data.BambuModel
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrinterKind
import com.u1.slicer.data.PrintersConfig
import com.u1.slicer.data.defaultExtruderPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrinterViewModelTest {
    @Test
    fun `switch eligibility uses saved printer configuration`() {
        val config = PrintersConfig(
            printers = listOf(
                Printer(id = "u1", nickname = "U1"),
                Printer(
                    id = "a1",
                    nickname = "A1",
                    kind = PrinterKind.BAMBU_LAN,
                    bambu = BambuConfig("192.168.1.2", "12345678", "A1TEST", BambuModel.A1_MINI),
                ),
            ),
            activeId = "a1",
        )

        assertTrue(PrinterViewModel.shouldSwitchActivePrinter(config, "u1"))
        assertFalse(PrinterViewModel.shouldSwitchActivePrinter(config, "a1"))
        assertFalse(PrinterViewModel.shouldSwitchActivePrinter(config, "missing"))
    }

    @Test
    fun `printer action result is accepted when active printer context is unchanged`() {
        val started = PrinterViewModel.PrinterActionContext(
            generation = 7,
            printerId = "printer-a",
        )

        assertTrue(
            PrinterViewModel.shouldApplyPrinterActionResult(
                started = started,
                currentGeneration = 7,
                currentPrinterId = "printer-a",
            )
        )
    }

    @Test
    fun `printer action result is rejected when active printer generation changes`() {
        val started = PrinterViewModel.PrinterActionContext(
            generation = 7,
            printerId = "printer-a",
        )

        assertFalse(
            PrinterViewModel.shouldApplyPrinterActionResult(
                started = started,
                currentGeneration = 8,
                currentPrinterId = "printer-a",
            )
        )
    }

    @Test
    fun `printer action result is rejected when active printer id changes`() {
        val started = PrinterViewModel.PrinterActionContext(
            generation = 7,
            printerId = "printer-a",
        )

        assertFalse(
            PrinterViewModel.shouldApplyPrinterActionResult(
                started = started,
                currentGeneration = 7,
                currentPrinterId = "printer-b",
            )
        )
    }

    @Test
    fun `printer action result is rejected when connection settings change`() {
        val started = PrinterViewModel.PrinterActionContext(
            generation = 7,
            printerId = "printer-a",
            connectionFingerprint = "old-settings",
        )

        assertFalse(
            PrinterViewModel.shouldApplyPrinterActionResult(
                started = started,
                currentGeneration = 7,
                currentPrinterId = "printer-a",
                currentConnectionFingerprint = "new-settings",
            )
        )
    }

    @Test
    fun `shouldStartCameraKeepalive returns false when job already active`() {
        assertFalse(PrinterViewModel.shouldStartCameraKeepalive(hasActiveJob = true))
    }

    @Test
    fun `shouldStartCameraKeepalive returns true when no active job exists`() {
        assertTrue(PrinterViewModel.shouldStartCameraKeepalive(hasActiveJob = false))
    }

    @Test
    fun `shouldPollLedOnConnectionEdge returns true only on rising connection edge`() {
        assertTrue(PrinterViewModel.shouldPollLedOnConnectionEdge(wasConnected = false, isConnected = true))
        assertFalse(PrinterViewModel.shouldPollLedOnConnectionEdge(wasConnected = true, isConnected = true))
        assertFalse(PrinterViewModel.shouldPollLedOnConnectionEdge(wasConnected = true, isConnected = false))
    }

    @Test
    fun `shouldPollLedOnConnectionEdge ignores disconnected steady state`() {
        assertFalse(PrinterViewModel.shouldPollLedOnConnectionEdge(wasConnected = false, isConnected = false))
    }

    // ---- F82: idle-state controls ----

    @Test
    fun `f82 sanitizeCustomGcode trims whitespace`() {
        assertEquals("M104 S0", PrinterViewModel.sanitizeCustomGcode("  M104 S0  "))
        assertEquals("G28 X", PrinterViewModel.sanitizeCustomGcode("\tG28 X\n"))
    }

    @Test
    fun `f82 sanitizeCustomGcode rejects empty and whitespace-only input`() {
        assertNull(PrinterViewModel.sanitizeCustomGcode(""))
        assertNull(PrinterViewModel.sanitizeCustomGcode("   "))
        assertNull(PrinterViewModel.sanitizeCustomGcode("\t\n"))
    }

    @Test
    fun `f82 sanitizeCustomGcode passes through normal commands unchanged`() {
        assertEquals("M104 S210 T0", PrinterViewModel.sanitizeCustomGcode("M104 S210 T0"))
        assertEquals("TURN_OFF_HEATERS", PrinterViewModel.sanitizeCustomGcode("TURN_OFF_HEATERS"))
        assertEquals("SET_PRESSURE_ADVANCE ADVANCE=0.05",
            PrinterViewModel.sanitizeCustomGcode("SET_PRESSURE_ADVANCE ADVANCE=0.05"))
    }

    @Test
    fun `buildPrinter creates normalized moonraker printer`() {
        val printer = PrinterViewModel.buildPrinter(
            id = "moonraker-1",
            fallbackNickname = "Printer 1",
            existingExtruderPresets = defaultExtruderPresets(),
            nickname = "U1",
            kind = PrinterKind.MOONRAKER,
            url = "printer.local",
            bambuIp = "",
            bambuAccessCode = "",
            bambuSerial = "",
            bambuModel = BambuModel.P1S,
        )

        assertEquals(PrinterKind.MOONRAKER, printer.kind)
        assertEquals("http://printer.local:7125", printer.moonrakerUrl)
        assertNull(printer.bambu)
    }

    @Test
    fun `buildPrinter creates bambu printer with blank moonraker url`() {
        val printer = PrinterViewModel.buildPrinter(
            id = "bambu-1",
            fallbackNickname = "Printer 1",
            existingExtruderPresets = defaultExtruderPresets(),
            nickname = "P1S",
            kind = PrinterKind.BAMBU_LAN,
            url = "http://ignored",
            bambuIp = " 192.168.1.88 ",
            bambuAccessCode = " 12345678 ",
            bambuSerial = " p1s123abc ",
            bambuModel = BambuModel.P1S,
        )

        assertEquals(PrinterKind.BAMBU_LAN, printer.kind)
        assertEquals("", printer.moonrakerUrl)
        assertEquals("192.168.1.88", printer.bambu?.ip)
        assertEquals("12345678", printer.bambu?.accessCode)
        assertEquals("P1S123ABC", printer.bambu?.serial)
    }

    @Test
    fun `buildPrinter keeps camera UID only for unchanged Moonraker printer`() {
        val moonraker = PrinterViewModel.buildPrinter(
            id = "moonraker-1",
            fallbackNickname = "Printer 1",
            existingExtruderPresets = defaultExtruderPresets(),
            nickname = "U1",
            kind = PrinterKind.MOONRAKER,
            url = "printer.local",
            bambuIp = "",
            bambuAccessCode = "",
            bambuSerial = "",
            bambuModel = BambuModel.P1S,
            selectedWebcamUid = "physical-camera",
        )
        val bambu = PrinterViewModel.buildPrinter(
            id = "bambu-1",
            fallbackNickname = "Printer 1",
            existingExtruderPresets = defaultExtruderPresets(),
            nickname = "P1S",
            kind = PrinterKind.BAMBU_LAN,
            url = "",
            bambuIp = "192.168.1.88",
            bambuAccessCode = "12345678",
            bambuSerial = "p1s123abc",
            bambuModel = BambuModel.P1S,
            selectedWebcamUid = "physical-camera",
        )

        assertEquals("physical-camera", moonraker.selectedWebcamUid)
        assertNull(bambu.selectedWebcamUid)
    }
}
