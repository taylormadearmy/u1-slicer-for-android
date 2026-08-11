package com.u1.slicer.printer

import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrinterKind
import com.u1.slicer.network.MoonrakerClient

interface PrinterTransportFactory {
    fun create(printer: Printer): PrinterTransport
}

class DefaultPrinterTransportFactory(
    private val moonrakerClientFactory: () -> MoonrakerClient = { MoonrakerClient() },
    private val bambuClientFactory: () -> BambuLanClient = { DefaultBambuLanClient() },
) : PrinterTransportFactory {
    override fun create(printer: Printer): PrinterTransport = when (printer.kind) {
        PrinterKind.MOONRAKER -> {
            MoonrakerTransport(
                moonrakerClientFactory().also { client ->
                    client.baseUrl = MoonrakerClient.normalizeUrl(printer.moonrakerUrl)
                }
            )
        }
        PrinterKind.BAMBU_LAN -> BambuLanTransport(
            config = requireNotNull(printer.bambu),
            client = bambuClientFactory(),
        )
    }
}
