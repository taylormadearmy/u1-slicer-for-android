package com.u1.slicer.ui.machine

import com.u1.slicer.machine.GcodeFlavor
import com.u1.slicer.machine.MachineFamily
import com.u1.slicer.machine.MachineProfile
import com.u1.slicer.machine.PrinterWizardVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PrinterWizardVariantListTest {
    @Test
    fun `same nozzle variants retain distinct labels and list keys`() {
        val standard = variant(
            displayName = "Co Print ChromaSet 0.4 nozzle",
            sourcePath = "machine/Co Print ChromaSet 0.4 nozzle.json",
        )
        val fast = variant(
            displayName = "Co Print ChromaSet 0.4 nozzle fast",
            sourcePath = "machine/Co Print ChromaSet 0.4 nozzle fast.json",
        )

        assertEquals("Co Print ChromaSet 0.4 nozzle", printerWizardVariantLabel(standard))
        assertEquals("Co Print ChromaSet 0.4 nozzle fast", printerWizardVariantLabel(fast))
        assertNotEquals(printerWizardVariantKey(standard), printerWizardVariantKey(fast))
    }

    private fun variant(displayName: String, sourcePath: String) = PrinterWizardVariant(
        profile = MachineProfile(
            id = "co-print-chromaset-0.4",
            displayName = displayName,
            vendor = "Co Print",
            printerModel = "Co Print ChromaSet",
            printerVariant = "0.4",
            family = MachineFamily.GENERIC,
            bedSizeX = 225f,
            bedSizeY = 225f,
            maxPrintHeight = 250f,
            extruderCount = 1,
            nozzleDiameter = 0.4f,
            gcodeFlavor = GcodeFlavor.KLIPPER,
            singleExtruderMultiMaterial = false,
            machineStartGcode = "",
            machineEndGcode = "",
            machineChangeFilamentGcode = "",
            machinePauseGcode = "",
            machineJson = "{}",
        ),
        sourcePath = sourcePath,
    )
}
