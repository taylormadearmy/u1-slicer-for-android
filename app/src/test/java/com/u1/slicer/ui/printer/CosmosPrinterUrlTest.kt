package com.u1.slicer.ui.printer

import com.u1.slicer.data.HostType
import com.u1.slicer.printer.normalizePrintHostUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class CosmosPrinterUrlTest {
    @Test fun bareHostUsesCosmosWebPortThroughTheMoonrakerNormalizer() {
        assertEquals("http://192.0.2.5:80", normalizePrintHostUrl(cosmosPrinterUrl("192.0.2.5"), HostType.MOONRAKER))
    }
    @Test fun explicitPortsAndPathsArePreserved() {
        assertEquals("http://cosmos.local:7125/moonraker", cosmosPrinterUrl("http://cosmos.local:7125/moonraker/"))
        assertEquals("https://cosmos.local:443/moonraker", cosmosPrinterUrl("https://cosmos.local/moonraker"))
        assertEquals("http://[::1]:80", cosmosPrinterUrl("http://[::1]"))
    }
    @Test fun blankAndMalformedInputsAreLeftForExistingHostValidation() {
        assertEquals("", cosmosPrinterUrl("  "))
        assertEquals("http://not a host", cosmosPrinterUrl("not a host"))
    }
}
