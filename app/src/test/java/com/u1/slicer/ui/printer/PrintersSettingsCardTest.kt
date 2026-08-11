package com.u1.slicer.ui.printer

import com.u1.slicer.data.BambuConfig
import com.u1.slicer.data.BambuModel
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrinterKind
import org.junit.Assert.assertEquals
import org.junit.Test

class PrintersSettingsCardTest {

    @Test
    fun `buildPrinterSubtitle returns moonraker url when configured`() {
        val subtitle = buildPrinterSubtitle(
            Printer(
                id = "moonraker-1",
                nickname = "U1",
                kind = PrinterKind.MOONRAKER,
                moonrakerUrl = "http://printer.local:7125",
            )
        )

        assertEquals("http://printer.local:7125", subtitle)
    }

    @Test
    fun `buildPrinterSubtitle returns bambu model and ip`() {
        val subtitle = buildPrinterSubtitle(
            Printer(
                id = "bambu-1",
                nickname = "P1S",
                kind = PrinterKind.BAMBU_LAN,
                bambu = BambuConfig(
                    ip = "192.168.1.88",
                    accessCode = "12345678",
                    serial = "P1S123ABC",
                    model = BambuModel.P1S,
                ),
                extruderPresets = emptyList(),
            )
        )

        assertEquals("P1S - 192.168.1.88", subtitle)
    }
}
