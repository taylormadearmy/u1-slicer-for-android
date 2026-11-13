package com.u1.slicer

import com.u1.slicer.data.MachineKind
import com.u1.slicer.data.PrintHostConfig
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrinterKind
import com.u1.slicer.slice.GenericMachineProfile
import com.u1.slicer.slice.SliceArtifact
import com.u1.slicer.slice.SlicerTarget
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericPrinterSendUiTest {
    @Test
    fun `restored generic artifact cannot be sent to U1`() {
        val profile = GenericMachineProfile.centauriCarbon()
        val generic = Printer("generic", "Klipper", machine = MachineKind.GENERIC_KLIPPER, machineProfile = profile)
        val artifact = SliceArtifact.MoonrakerGcodeArtifact("saved", File("output.gcode"), SlicerTarget.GenericKlipper, machineProfileFingerprint = profile.fingerprint)
        assertTrue(canSendSliceArtifactToPrinter(artifact, generic))
        assertFalse(canSendSliceArtifactToPrinter(artifact, Printer("u1", "U1")))
    }

    @Test
    fun `restored Bambu plain Gcode requires reslicing to rebuild project`() {
        val artifact = SliceArtifact.MoonrakerGcodeArtifact("saved", File("output.gcode"), SlicerTarget.BambuA1Mini)
        val bambu = Printer("bambu", "Bambu", PrinterKind.BAMBU_LAN, bambu = com.u1.slicer.data.BambuConfig("192.0.2.1", "12345678", "SERIAL", com.u1.slicer.data.BambuModel.A1_MINI))
        assertFalse(canSendSliceArtifactToPrinter(artifact, bambu))
    }
    @Test
    fun `unknown artifact cannot be sent to generic or Centauri machines`() {
        val generic = Printer("generic", "Klipper", machine = MachineKind.GENERIC_KLIPPER)
        val cc1 = Printer("cc1", "CC1", PrinterKind.ELEGOO_CC1, host = PrintHostConfig("192.0.2.1"))
        assertFalse(canSendSliceArtifactToPrinter(null, generic))
        assertFalse(canSendSliceArtifactToPrinter(null, cc1))
    }

    @Test
    fun `generic machines bypass U1 physical slot remapping`() {
        assertTrue(usesPlainGcodeSend(Printer("generic", "Klipper", machine = MachineKind.GENERIC_KLIPPER)))
        assertTrue(usesPlainGcodeSend(Printer("cc1", "CC1", PrinterKind.ELEGOO_CC1, host = PrintHostConfig("192.0.2.1"))))
        assertFalse(usesPlainGcodeSend(Printer("u1", "U1")))
        assertFalse(usesPlainGcodeSend(null))
    }
}
