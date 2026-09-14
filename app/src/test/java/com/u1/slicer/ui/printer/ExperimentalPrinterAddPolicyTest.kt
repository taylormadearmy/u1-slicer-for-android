package com.u1.slicer.ui.printer

import org.junit.Assert.assertEquals
import org.junit.Test

class ExperimentalPrinterAddPolicyTest {
    @Test
    fun `Snapmaker U1 never requires experimental acknowledgement`() {
        assertEquals(
            AddPrinterNextStep.Proceed,
            addPrinterNextStep(AddPrinterChoice.SNAPMAKER_U1, acknowledged = false),
        )
        assertEquals(
            AddPrinterNextStep.Proceed,
            addPrinterNextStep(AddPrinterChoice.SNAPMAKER_U1, acknowledged = true),
        )
    }

    @Test
    fun `every third party add path requires acknowledgement`() {
        val experimentalChoices = listOf(
            AddPrinterChoice.BAMBU_LAN,
            AddPrinterChoice.ORCA_CATALOGUE,
            AddPrinterChoice.ORCA_IMPORT,
        )

        experimentalChoices.forEach { choice ->
            assertEquals(
                "$choice should be gated",
                AddPrinterNextStep.RequireAcknowledgement,
                addPrinterNextStep(choice, acknowledged = false),
            )
        }
    }

    @Test
    fun `one acknowledgement permits every third party add path`() {
        AddPrinterChoice.entries.forEach { choice ->
            assertEquals(
                "$choice should proceed after acknowledgement",
                AddPrinterNextStep.Proceed,
                addPrinterNextStep(choice, acknowledged = true),
            )
        }
    }
}
