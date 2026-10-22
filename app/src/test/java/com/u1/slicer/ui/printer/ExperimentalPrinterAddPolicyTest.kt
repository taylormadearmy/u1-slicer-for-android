package com.u1.slicer.ui.printer

import org.junit.Assert.assertEquals
import org.junit.Test

class ExperimentalPrinterAddPolicyTest {
    @Test
    fun `experimental support off exposes only Snapmaker U1`() {
        assertEquals(
            listOf(AddPrinterChoice.SNAPMAKER_U1),
            visibleAddPrinterChoices(experimentalEnabled = false),
        )
    }

    @Test
    fun `experimental support on exposes every add route`() {
        assertEquals(
            AddPrinterChoice.entries,
            visibleAddPrinterChoices(experimentalEnabled = true),
        )
    }

    @Test
    fun `turning experimental support off hides third party routes again`() {
        assertEquals(5, visibleAddPrinterChoices(experimentalEnabled = true).size)
        assertEquals(
            listOf(AddPrinterChoice.SNAPMAKER_U1),
            visibleAddPrinterChoices(experimentalEnabled = false),
        )
    }
}
