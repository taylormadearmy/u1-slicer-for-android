package com.u1.slicer.ui.printer

import com.u1.slicer.printer.PrinterTransportCapabilities

internal data class PrinterScreenControlVisibility(
    val showCamera: Boolean,
    val showLightControl: Boolean,
    val showHeaterControl: Boolean,
    val showConsole: Boolean,
)

internal fun resolvePrinterScreenControlVisibility(
    capabilities: PrinterTransportCapabilities,
    isConnected: Boolean,
    isIdle: Boolean,
) = PrinterScreenControlVisibility(
    showCamera = isConnected && capabilities.supportsCamera,
    showLightControl = isConnected && capabilities.supportsLightControl,
    showHeaterControl = capabilities.supportsHeaterControl,
    showConsole = isConnected && isIdle && capabilities.supportsConsole,
)
