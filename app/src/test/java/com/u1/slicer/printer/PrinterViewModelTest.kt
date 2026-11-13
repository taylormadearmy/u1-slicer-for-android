package com.u1.slicer.printer

import com.u1.slicer.data.BambuConfig
import com.u1.slicer.data.BambuModel
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrinterKind
import com.u1.slicer.data.MachineKind
import com.u1.slicer.data.PrintHostConfig
import com.u1.slicer.data.HostType
import com.u1.slicer.data.PrintersConfig
import com.u1.slicer.data.defaultExtruderPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrinterViewModelTest {
    @Test fun delimiterCharactersCannotHideCredentialChanges() {
        val printer = Printer("prusa", "Prusa", hostType = HostType.PRUSALINK,
            moonrakerUrl = "http://192.0.2.1", apiKey = "a|b", username = "c")
        val changed = printer.copy(apiKey = "a", username = "b|c")
        assertFalse(PrinterViewModel.printerConnectionFingerprint(printer) == PrinterViewModel.printerConnectionFingerprint(changed))
    }
    @Test fun independentMarlinSlotEditorUsesSelectedSnapshotCount() {
        val profile = com.u1.slicer.slice.GenericMachineProfile.fromJson("""{
            "type":"machine","name":"Two tools","gcode_flavor":"marlin2",
            "printable_area":["0x0","200x0","200x210","0x210"],"printable_height":"220",
            "nozzle_diameter":["0.4","0.4"],"extruder_offset":["0x0","20x0"],
            "machine_start_gcode":"G28","machine_end_gcode":"M84","change_filament_gcode":"T[next_extruder]"
        }""")
        val printer = Printer("two-tools", "Marlin", machineProfile = profile, extruderPresets = emptyList())
        assertEquals(MachineKind.GENERIC_MARLIN, printer.machine)
        assertEquals(listOf(0, 1), PrinterViewModel.visibleExtruderPresets(printer).map { it.index })
        assertFalse(PrinterViewModel.printerConnectionFingerprint(printer) ==
            PrinterViewModel.printerConnectionFingerprint(printer.copy(machineProfile =
                com.u1.slicer.slice.GenericMachineProfile.fromJson(profile.resolvedJson.replace("20x0", "21x0")))))
    }
    @Test fun `first filament edit is inserted and survives printer serialization`() {
        val preset = com.u1.slicer.data.ExtruderPreset(0, "#123456", "PETG", 42)
        val printer = Printer("generic", "Klipper", machine = MachineKind.GENERIC_KLIPPER, extruderPresets = emptyList())
        val updated = printer.copy(extruderPresets = PrinterViewModel.updatedExtruderPresets(printer.extruderPresets, preset))
        assertEquals(listOf(preset), Printer.fromJsonObject(Printer.toJsonObject(updated)).extruderPresets)
        assertEquals(listOf(preset.copy(materialType = "ABS")), PrinterViewModel.updatedExtruderPresets(updated.extruderPresets, preset.copy(materialType = "ABS")))
    }

    @Test
    fun `generic slot editor exposes one physical nozzle even with legacy four presets`() {
        val generic = Printer("generic", "Klipper", machine = MachineKind.GENERIC_KLIPPER)
        assertEquals(listOf(0), PrinterViewModel.visibleExtruderPresets(generic).map { it.index })
        val cc1 = Printer("cc1", "CC1", PrinterKind.ELEGOO_CC1, host = PrintHostConfig("192.0.2.1"), extruderPresets = emptyList())
        assertEquals(listOf(0), PrinterViewModel.visibleExtruderPresets(cc1).map { it.index })
        assertEquals(4, PrinterViewModel.visibleExtruderPresets(Printer("u1", "U1")).size)
    }

    @Test
    fun `legacy physical Gcode entrypoints only accept U1 machines`() {
        assertTrue(PrinterViewModel.allowsPhysicalGcodeSend(Printer("u1", "U1")))
        assertFalse(PrinterViewModel.allowsPhysicalGcodeSend(Printer("generic", "Klipper", machine = MachineKind.GENERIC_KLIPPER)))
        assertFalse(PrinterViewModel.allowsPhysicalGcodeSend(Printer("cc1", "CC1", PrinterKind.ELEGOO_CC1, host = PrintHostConfig("192.0.2.1"))))
        assertFalse(PrinterViewModel.allowsPhysicalGcodeSend(null))
    }
    @Test
    fun `changing a Moonraker machine invalidates the send context`() {
        val u1 = Printer("printer", "U1", moonrakerUrl = "http://192.0.2.1:7125")
        val generic = u1.copy(machine = MachineKind.GENERIC_KLIPPER)
        assertFalse(PrinterViewModel.printerConnectionFingerprint(u1) == PrinterViewModel.printerConnectionFingerprint(generic))
    }

    @Test
    fun `changing a host credential invalidates the send context`() {
        val octo = Printer("octo", "Octo", PrinterKind.OCTOPRINT, host = PrintHostConfig("http://192.0.2.2", "old"))
        val updated = octo.copy(host = octo.host!!.copy(apiKey = "new"))
        assertFalse(PrinterViewModel.printerConnectionFingerprint(octo) == PrinterViewModel.printerConnectionFingerprint(updated))
    }
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
    fun `buildPrinter binds a generic Moonraker printer to its machine profile`() {
        val printer = PrinterViewModel.buildPrinter(
            id = "ender-1",
            fallbackNickname = "Ender",
            existingExtruderPresets = defaultExtruderPresets(1),
            nickname = "Ender",
            kind = PrinterKind.MOONRAKER,
            url = "ender.local",
            bambuIp = "",
            bambuAccessCode = "",
            bambuSerial = "",
            bambuModel = BambuModel.P1S,
            machineProfileId = "creality-ender-3-v3-se-0.4",
        )

        assertEquals("creality-ender-3-v3-se-0.4", printer.machineProfileId)
        assertEquals(1, printer.extruderPresets.size)
        assertNull(printer.bambu)
    }

    @Test
    fun `buildPrinter normalizes OctoPrint without adding Moonraker port and retains credentials`() {
        val printer = PrinterViewModel.buildPrinter(
            id = "octo-1",
            fallbackNickname = "OctoPrint",
            existingExtruderPresets = defaultExtruderPresets(1),
            nickname = "OctoPrint",
            kind = PrinterKind.MOONRAKER,
            hostType = HostType.OCTOPRINT,
            url = "octo.local",
            apiKey = " key ",
            bambuIp = "",
            bambuAccessCode = "",
            bambuSerial = "",
            bambuModel = BambuModel.P1S,
        )

        assertEquals("http://octo.local", printer.moonrakerUrl)
        assertEquals("key", printer.apiKey)
        assertNull(printer.selectedWebcamUid)
    }

    @Test
    fun `buildPrinter retains selected PrusaLink authentication`() {
        val printer = PrinterViewModel.buildPrinter(
            id = "prusa-1",
            fallbackNickname = "Prusa",
            existingExtruderPresets = defaultExtruderPresets(1),
            nickname = "MK4S",
            kind = PrinterKind.MOONRAKER,
            hostType = HostType.PRUSALINK,
            url = " prusa.local ",
            apiKey = " api-key ",
            username = " maker ",
            password = "secret",
            bambuIp = "",
            bambuAccessCode = "",
            bambuSerial = "",
            bambuModel = BambuModel.P1S,
            machineProfileId = "prusa-mk4-0.4",
        )

        assertEquals(HostType.PRUSALINK, printer.hostType)
        assertEquals("http://prusa.local", printer.moonrakerUrl)
        assertEquals("api-key", printer.apiKey)
        assertEquals("maker", printer.username)
        assertEquals("secret", printer.password)
        assertEquals("prusa-mk4-0.4", printer.machineProfileId)
    }

    @Test
    fun `connection fingerprint includes host protocol and credentials`() {
        val printer = Printer(
            id = "host-1",
            nickname = "Host",
            hostType = HostType.OCTOPRINT,
            moonrakerUrl = "octo.local",
            apiKey = "one",
        )

        val original = PrinterViewModel.printerConnectionFingerprint(printer)

        assertFalse(original == PrinterViewModel.printerConnectionFingerprint(printer.copy(hostType = HostType.PRUSALINK)))
        assertFalse(original == PrinterViewModel.printerConnectionFingerprint(printer.copy(apiKey = "two")))
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
