package com.u1.slicer.printer

import com.u1.slicer.data.BambuConfig
import com.u1.slicer.data.BambuModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class BambuDiagnosticsTest {
    private val config = BambuConfig(
        ip = "192.168.4.27",
        accessCode = "secret-code",
        serial = "01P00A123456789",
        model = BambuModel.P1S,
    )

    @Test
    fun `error details redact credentials serial and endpoint`() {
        val message = "secret-code 01P00A123456789 at 192.168.4.27:8883"

        val details = BambuDiagnostics.errorDetails(IllegalStateException(message), config)
        val safe = details.getValue("errorMessage").toString()

        assertFalse(safe.contains(config.accessCode))
        assertFalse(safe.contains(config.serial))
        assertFalse(safe.contains(config.ip))
        assertTrue(safe.contains("<redacted>") || safe.contains("<endpoint>"))
    }

    @Test
    fun `error classifier keeps transport failures searchable`() {
        assertEquals("timeout", BambuDiagnostics.classifyError(SocketTimeoutException("late")))
        assertEquals("validation", BambuDiagnostics.classifyError(IllegalArgumentException("bad route")))
        assertEquals("authorization", BambuDiagnostics.classifyError(IllegalStateException("FTPS 530 denied")))
    }

    @Test
    fun `project id is stable and does not expose the filename`() {
        val first = BambuDiagnostics.projectId("private-model.gcode.3mf")
        val second = BambuDiagnostics.projectId("private-model.gcode.3mf")

        assertEquals(first, second)
        assertEquals(12, first.length)
        assertFalse(first.contains("private"))
    }
}
