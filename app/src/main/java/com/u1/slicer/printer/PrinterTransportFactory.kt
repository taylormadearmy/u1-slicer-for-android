package com.u1.slicer.printer

import com.u1.slicer.data.Printer
import com.u1.slicer.data.MachineKind
import com.u1.slicer.printer.elegoo.ElegooCc1Transport
import com.u1.slicer.data.HostType
import com.u1.slicer.network.MoonrakerClient
import com.u1.slicer.machine.MachineProfile
import com.u1.slicer.network.FilamentSlot
import com.u1.slicer.network.PrinterStatus
import kotlinx.coroutines.flow.flowOf
import java.net.URI

interface PrinterTransportFactory {
    fun create(printer: Printer): PrinterTransport
}

class DefaultPrinterTransportFactory(
    private val moonrakerClientFactory: () -> MoonrakerClient = { MoonrakerClient() },
    private val bambuClientFactory: () -> BambuLanClient = { DefaultBambuLanClient() },
    private val octoPrintTransportFactory: (Printer) -> PrinterTransport = { p ->
        OctoPrintTransport(normalizePrintHostUrl(p.host?.url ?: p.moonrakerUrl, p.hostType), p.host?.apiKey ?: p.apiKey)
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
        HostType.MOONRAKER -> try {
            val profile = machineProfileFactory(printer)
            MoonrakerTransport(
                client = moonrakerClientFactory().also { client ->
                    client.baseUrl = MoonrakerClient.normalizeUrl(printer.moonrakerUrl)
                },
                u1 = printer.machine == MachineKind.SNAPMAKER_U1,
                maxBedTemperatureC = profile?.maxBedTemperatureC
                    ?: MachineProfile.DEFAULT_MAX_BED_TEMPERATURE_C,
                maxNozzleTemperatureC = profile?.maxNozzleTemperatureC
                    ?: MachineProfile.DEFAULT_MAX_NOZZLE_TEMPERATURE_C,
            )
        } catch (error: IllegalArgumentException) {
            UnavailableMachineTransport(error.message ?: "Choose a valid machine profile in Settings")
        }
        HostType.BAMBU_LAN -> BambuLanTransport(
            config = requireNotNull(printer.bambu),
            client = bambuClientFactory(),
        )
        HostType.ELEGOO_CC1 -> ElegooCc1Transport(requireNotNull(printer.host).url)
        HostType.OCTOPRINT -> octoPrintTransportFactory(printer)
        HostType.PRUSALINK -> prusaLinkTransportFactory(printer)
    }
}

private class UnavailableMachineTransport(private val reason: String) : PrinterTransport {
    override val capabilities = PrinterTransportCapabilities()
    override val status = flowOf(PrinterStatus(state = "disconnected", progress = 0f))
    override val cameraState = flowOf<CameraState>(CameraState.Disabled)
    override val filamentSlots = flowOf(emptyList<FilamentSlot>())
    override suspend fun testConnection(): String = reason
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
