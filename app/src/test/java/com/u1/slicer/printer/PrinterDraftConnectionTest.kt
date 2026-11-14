package com.u1.slicer.printer

import com.u1.slicer.data.PrintHostConfig
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrinterKind
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrinterDraftConnectionTest {
    private class TestTransport(val failure: RuntimeException? = null) : PrinterTransport {
        override val capabilities = PrinterTransportCapabilities()
        override val status = emptyFlow<com.u1.slicer.network.PrinterStatus>()
        override val filamentSlots = emptyFlow<List<com.u1.slicer.network.FilamentSlot>>()
        override val cameraState = emptyFlow<CameraState>()
        var stopped = 0
        var starts = 0
        override suspend fun testConnection(): String? {
            failure?.let { throw it }
            return null
        }
        override suspend fun stop() { stopped++ }
        override suspend fun startJob(remoteName: String): TransportCommandResult {
            starts++
            return TransportCommandResult.Success
        }
    }

    @Test
    fun `connection tests use draft factory and dispose without starting a print`() = runBlocking {
        val draft = Printer("draft", "CC1", PrinterKind.ELEGOO_CC1, host = PrintHostConfig("192.0.2.2"))
        val transport = TestTransport()
        var requested: Printer? = null
        val factory = object : PrinterTransportFactory {
            override fun create(printer: Printer): PrinterTransport {
                requested = printer
                return transport
            }
        }
        assertNull(PrinterViewModel.testDraftConnection(factory, draft))
        assertEquals(draft, requested)
        assertEquals(1, transport.stopped)
        assertEquals(0, transport.starts)
    }

    @Test
    fun `failed connection tests still dispose transport`() = runBlocking {
        val failure = IllegalStateException("Connection failed")
        val transport = TestTransport(failure)
        val factory = object : PrinterTransportFactory {
            override fun create(printer: Printer): PrinterTransport = transport
        }
        val actual = runCatching { PrinterViewModel.testDraftConnection(factory, Printer("draft", "U1")) }.exceptionOrNull()
        assertEquals(failure, actual)
        assertEquals(1, transport.stopped)
        assertEquals(0, transport.starts)
    }
}
