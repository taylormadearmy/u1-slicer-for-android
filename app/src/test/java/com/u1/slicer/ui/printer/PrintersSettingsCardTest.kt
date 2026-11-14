package com.u1.slicer.ui.printer

import com.u1.slicer.data.BambuConfig
import com.u1.slicer.data.BambuModel
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrinterKind
import com.u1.slicer.data.PrintHostConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class PrintersSettingsCardTest {
    @Test
    fun `host subtitles describe protocol without showing API keys`() {
        val octo = Printer("octo", "Octo", PrinterKind.OCTOPRINT, host = PrintHostConfig("http://octo.local", "secret"))
        val cc1 = Printer("cc1", "CC1", PrinterKind.ELEGOO_CC1, host = PrintHostConfig("192.0.2.1"))
        assertEquals("OctoPrint - http://octo.local", buildPrinterSubtitle(octo))
        assertEquals("Centauri Carbon 1 - 192.0.2.1", buildPrinterSubtitle(cc1))
    }

    @Test
    fun `connection labels distinguish protocols from machine names`() {
        assertEquals("Moonraker", PrinterKind.MOONRAKER.connectionLabel())
        assertEquals("OctoPrint", PrinterKind.OCTOPRINT.connectionLabel())
        assertEquals("Bambu LAN", PrinterKind.BAMBU_LAN.connectionLabel())
        assertEquals("Elegoo Centauri Carbon 1 (beta)", PrinterKind.ELEGOO_CC1.connectionLabel())
    }

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
