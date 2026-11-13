package com.u1.slicer.ui.printer

import com.u1.slicer.data.MachineKind
import com.u1.slicer.data.Printer
import org.junit.Assert.*
import org.junit.Test

class OfflineU1SettingsTest {
    @Test fun `existing offline U1 can save installed nozzle changes`() {
        assertTrue(canSaveOfflineU1(Printer("u1", "U1"), MachineKind.SNAPMAKER_U1, ""))
    }

    @Test fun `new printer still needs an address`() {
        assertFalse(canSaveOfflineU1(null, MachineKind.SNAPMAKER_U1, ""))
    }

    @Test fun `other machines still need an address`() {
        assertFalse(canSaveOfflineU1(Printer("u1", "U1"), MachineKind.GENERIC_KLIPPER, ""))
    }
}
