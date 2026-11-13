package com.u1.slicer.data

import com.u1.slicer.printer.PrinterRepository
import com.u1.slicer.printer.DefaultPrinterTransportFactory
import com.u1.slicer.slice.GenericMachineProfile
import com.u1.slicer.slice.SliceArtifact
import com.u1.slicer.slice.SlicerTarget
import com.u1.slicer.slice.isCompatibleWith
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PrinterMachineTest {
    private fun profile(name: String = "Klipper") = GenericMachineProfile.fromJson("""{
        "name":"$name", "type":"machine", "gcode_flavor":"klipper",
        "nozzle_diameter":["0.4"], "printable_area":["0x0","220x0","220x220","0x220"],
        "printable_height":"250", "machine_start_gcode":"G28", "machine_end_gcode":"M104 S0"
    }""")

    @Test fun legacyMoonrakerRemainsU1() {
        val printer = Printer.fromJsonObject(JSONObject("""{"id":"old","nickname":"U1","moonrakerUrl":"http://u1"}"""))
        assertEquals(MachineKind.SNAPMAKER_U1, printer.machine)
        assertEquals(4, printer.extruderPresets.size)
        assertNull(printer.machineProfile)
    }

    @Test fun genericSnapshotAndHostCredentialsRoundTrip() {
        val printer = Printer("generic", "Workshop", PrinterKind.OCTOPRINT,
            extruderPresets = emptyList(), machineProfile = profile(), host = PrintHostConfig("http://host/octo", "secret"))
        assertEquals(printer, Printer.fromJsonObject(Printer.toJsonObject(printer)))
        assertEquals(MachineKind.GENERIC_KLIPPER, printer.machine)
    }

    @Test fun cc1RoundTripNeverGetsMoonrakerDefaults() {
        val printer = Printer("cc", "CC1", PrinterKind.ELEGOO_CC1, extruderPresets = emptyList(), host = PrintHostConfig("cc.local"))
        val restored = Printer.fromJsonObject(Printer.toJsonObject(printer))
        assertEquals(printer, restored)
        assertTrue(restored.moonrakerUrl.isBlank())
        assertTrue(restored.extruderPresets.isEmpty())
    }

    @Test fun artifactsMatchMachineNotConnectionProtocol() {
        val generic = Printer("generic", "Klipper", moonrakerUrl = "http://klipper",
            machine = MachineKind.GENERIC_KLIPPER, machineProfile = profile())
        val u1 = SliceArtifact.MoonrakerGcodeArtifact("model", File("model.gcode"))
        assertFalse(u1.isCompatibleWith(generic))
        val artifact = SliceArtifact.MoonrakerGcodeArtifact("model", File("model.gcode"),
            SlicerTarget.GenericKlipper, generic.machineProfile!!.fingerprint)
        assertTrue(artifact.isCompatibleWith(generic))
        assertFalse(artifact.isCompatibleWith(generic.copy(machineProfile = profile("Changed"))))
        assertFalse(artifact.copy(machineProfileFingerprint = null).isCompatibleWith(generic))
        assertFalse(artifact.isCompatibleWith(generic.copy(machineProfile = null)))
    }

    @Test fun genericMoonrakerHidesU1OnlyControls() {
        val printer = Printer("generic", "Klipper", moonrakerUrl = "http://klipper", machine = MachineKind.GENERIC_KLIPPER)
        val capabilities = DefaultPrinterTransportFactory().create(printer).capabilities
        assertFalse(capabilities.supportsLightControl)
        assertFalse(capabilities.supportsFilamentSync)
        assertFalse(capabilities.supportsRemoteScreen)
        assertTrue(capabilities.supportsHeaterControl)
        assertTrue(capabilities.supportsUpload)
    }

    @Test fun changesInvalidateBoundTransportBeforeSend() {
        val printer = Printer("generic", "Klipper", moonrakerUrl = "http://klipper", machine = MachineKind.GENERIC_KLIPPER, machineProfile = profile())
        assertTrue(PrinterRepository.shouldRecreateTransport(printer, printer.copy(id = "other")))
        assertTrue(PrinterRepository.shouldRecreateTransport(printer, printer.copy(machineProfile = profile("Changed"))))
        assertFalse(PrinterRepository.shouldRecreateTransport(printer, printer.copy(nickname = "Renamed")))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectMixedProviderCredentials() {
        Printer("cc", "CC1", PrinterKind.ELEGOO_CC1, moonrakerUrl = "http://u1", host = PrintHostConfig("cc.local"))
    }

    @Test fun rejectInvalidHostSettingsBeforePersistence() {
        assertThrows(IllegalArgumentException::class.java) {
            Printer("cc", "CC1", PrinterKind.ELEGOO_CC1, host = PrintHostConfig("cc.local:7125"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Printer("octo", "OctoPrint", PrinterKind.OCTOPRINT, host = PrintHostConfig("ftp://host"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Printer("octo", "OctoPrint", PrinterKind.OCTOPRINT, host = PrintHostConfig("http://user:secret@host"))
        }
    }
}
