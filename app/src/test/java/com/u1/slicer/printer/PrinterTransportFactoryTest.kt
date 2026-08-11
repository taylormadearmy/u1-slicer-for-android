package com.u1.slicer.printer

import com.u1.slicer.data.BambuConfig
import com.u1.slicer.data.BambuModel
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrinterKind
import com.u1.slicer.network.MoonrakerClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class PrinterTransportFactoryTest {

    @Test
    fun `factory creates moonraker transport for moonraker printer`() {
        val client = MoonrakerClient()
        val factory = DefaultPrinterTransportFactory(moonrakerClientFactory = { client })

        val transport = factory.create(
            Printer(
                id = "moonraker-1",
                nickname = "U1",
                kind = PrinterKind.MOONRAKER,
                moonrakerUrl = "192.168.1.50",
            )
        )

        assertTrue(transport is MoonrakerTransport)
        assertTrue(client.baseUrl.contains(":7125"))
    }

    @Test
    fun `factory creates an independent moonraker client for each printer`() {
        val createdClients = mutableListOf<MoonrakerClient>()
        val factory = DefaultPrinterTransportFactory(
            moonrakerClientFactory = { MoonrakerClient().also(createdClients::add) },
        )

        factory.create(
            Printer(
                id = "moonraker-1",
                nickname = "U1 one",
                kind = PrinterKind.MOONRAKER,
                moonrakerUrl = "192.168.1.50",
            )
        )
        factory.create(
            Printer(
                id = "moonraker-2",
                nickname = "U1 two",
                kind = PrinterKind.MOONRAKER,
                moonrakerUrl = "192.168.1.51",
            )
        )

        assertEquals(2, createdClients.size)
        assertTrue(createdClients[0] !== createdClients[1])
        assertEquals("http://192.168.1.50:7125", createdClients[0].baseUrl)
        assertEquals("http://192.168.1.51:7125", createdClients[1].baseUrl)
    }

    @Test
    fun `factory creates bambu transport for bambu printer`() {
        val factory = DefaultPrinterTransportFactory()

        val transport = factory.create(
            Printer(
                id = "bambu-1",
                nickname = "P1S",
                kind = PrinterKind.BAMBU_LAN,
                moonrakerUrl = "",
                bambu = BambuConfig(
                    ip = "192.168.1.88",
                    accessCode = "12345678",
                    serial = "P1S123ABC",
                    model = BambuModel.P1S,
                ),
                extruderPresets = emptyList(),
            )
        )

        assertTrue(transport is BambuLanTransport)
    }

    @Test
    fun `factory uses injected bambu client factory`() {
        val fakeClient = object : BambuLanClient {
            override suspend fun start(config: BambuConfig, onReport: (String) -> Unit) {}
            override suspend fun stop() {}
            override suspend fun testConnection(config: BambuConfig): String? = null
            override suspend fun sendPrintCommand(config: BambuConfig, command: String) {}
            override suspend fun startProjectFile(
                config: BambuConfig,
                remoteName: String,
                plateId: Int,
                amsMapping: List<Int>,
                useAms: Boolean,
                subtaskName: String,
                plateGcodeMd5: String,
            ) {}
        }
        val factory = DefaultPrinterTransportFactory(
            bambuClientFactory = { fakeClient },
        )

        val transport = factory.create(
            Printer(
                id = "bambu-2",
                nickname = "P1S",
                kind = PrinterKind.BAMBU_LAN,
                bambu = BambuConfig(
                    ip = "192.168.1.90",
                    accessCode = "87654321",
                    serial = "P1S999XYZ",
                    model = BambuModel.P1S,
                ),
                extruderPresets = emptyList(),
            )
        )

        assertTrue(transport is BambuLanTransport)
        assertNull(kotlinx.coroutines.runBlocking { transport.testConnection() })
    }
}
