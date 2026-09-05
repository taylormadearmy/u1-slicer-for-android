package com.u1.slicer.printer

import com.u1.slicer.data.Printer
import com.u1.slicer.data.HostType
import com.u1.slicer.network.MoonrakerClient
import com.u1.slicer.machine.MachineProfile
import java.net.URI

interface PrinterTransportFactory {
    fun create(printer: Printer): PrinterTransport
}

class DefaultPrinterTransportFactory(
    private val moonrakerClientFactory: () -> MoonrakerClient = { MoonrakerClient() },
    private val bambuClientFactory: () -> BambuLanClient = { DefaultBambuLanClient() },
    private val octoPrintTransportFactory: (Printer) -> PrinterTransport = { p ->
        OctoPrintTransport(normalizePrintHostUrl(p.moonrakerUrl, p.hostType), p.apiKey)
    },
    private val prusaLinkTransportFactory: (Printer) -> PrinterTransport = { p ->
        PrusaLinkTransport(
            normalizePrintHostUrl(p.moonrakerUrl, p.hostType),
            p.apiKey,
            p.username,
            p.password,
        )
    },
    private val machineProfileFactory: (Printer) -> MachineProfile? = { null },
) : PrinterTransportFactory {
    override fun create(printer: Printer): PrinterTransport = when (printer.hostType) {
        HostType.MOONRAKER -> {
            val profile = machineProfileFactory(printer)
            MoonrakerTransport(
                client = moonrakerClientFactory().also { client ->
                    client.baseUrl = MoonrakerClient.normalizeUrl(printer.moonrakerUrl)
                },
                maxBedTemperatureC = profile?.maxBedTemperatureC
                    ?: MachineProfile.DEFAULT_MAX_BED_TEMPERATURE_C,
                maxNozzleTemperatureC = profile?.maxNozzleTemperatureC
                    ?: MachineProfile.DEFAULT_MAX_NOZZLE_TEMPERATURE_C,
            )
        }
        HostType.BAMBU_LAN -> BambuLanTransport(
            config = requireNotNull(printer.bambu),
            client = bambuClientFactory(),
        )
        HostType.OCTOPRINT -> octoPrintTransportFactory(printer)
        HostType.PRUSALINK -> prusaLinkTransportFactory(printer)
    }
}

internal fun normalizePrintHostUrl(raw: String, hostType: HostType): String {
    if (hostType == HostType.MOONRAKER) return MoonrakerClient.normalizeUrl(raw)
    var value = raw.trim()
    if (value.isBlank()) return ""
    if (!value.startsWith("http://", ignoreCase = true) &&
        !value.startsWith("https://", ignoreCase = true)
    ) {
        value = "http://$value"
    }
    return runCatching { URI(value).toString().trimEnd('/') }
        .getOrDefault(value.trimEnd('/'))
}
