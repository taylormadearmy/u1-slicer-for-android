package com.u1.slicer.printer

import com.u1.slicer.data.BambuConfig
import com.u1.slicer.data.BambuModel
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrinterKind
import com.u1.slicer.data.HostType
import com.u1.slicer.network.MoonrakerClient
import com.u1.slicer.machine.MachineFamily
import com.u1.slicer.machine.MachineProfile
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class PrinterTransportFactoryTest {
    @Test fun factoryPreservesReleasedCc1AdapterWithoutEnablingStartOnUpload() {
        val printer = Printer("cc1", "CC1", PrinterKind.ELEGOO_CC1,
            host = com.u1.slicer.data.PrintHostConfig("192.0.2.2"))
        val transport = DefaultPrinterTransportFactory().create(printer)
        assertTrue(transport is com.u1.slicer.printer.elegoo.ElegooCc1Transport)
        assertTrue(transport.capabilities.supportsUpload)
        org.junit.Assert.assertFalse(transport.capabilities.supportsStartOnUpload)
    }

    @Test fun factoryReadsReleasedOctoHostAddressAndKey() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"server":"1.10.0"}"""))
            val printer = Printer("octo", "Octo", PrinterKind.OCTOPRINT,
                host = com.u1.slicer.data.PrintHostConfig(server.url("/octo/").toString(), "old-key"))
            val transport = DefaultPrinterTransportFactory().create(printer)
            assertNull(transport.testConnection())
            val request = server.takeRequest()
            assertEquals("/octo/api/version", request.path)
            assertEquals("old-key", request.getHeader("X-Api-Key"))
        }
    }

    @Test
    fun `factory dispatches OctoPrint and PrusaLink by host type`() {
        val octo = object : PrinterTransport by OctoPrintTransport("http://octo", apiKey = null) {}
        val prusa = object : PrinterTransport by PrusaLinkTransport("http://prusa") {}
        val factory = DefaultPrinterTransportFactory(
            octoPrintTransportFactory = { octo },
            prusaLinkTransportFactory = { prusa },
        )
        assertTrue(factory.create(Printer("o", "Octo", hostType = HostType.OCTOPRINT)) === octo)
        assertTrue(factory.create(Printer("p", "Prusa", hostType = HostType.PRUSALINK)) === prusa)
    }

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
    fun `factory binds selected machine heater limits to Moonraker transport`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.start()
        try {
            val profile = requireNotNull(
                MachineProfile.fromResolvedOrcaJson(
                    """{
                      "type":"machine",
                      "name":"Limited",
                      "gcode_flavor":"klipper",
                      "nozzle_diameter":["0.4"],
                      "printable_height":"200",
                      "machine_start_gcode":"G28",
                      "machine_end_gcode":"M84",
                      "printable_area":["0x0","180x0","180x180","0x180"],
                      "nozzle_max_temperature":"275",
                      "bed_temperature_limit":"95"
                    }""",
                    MachineFamily.GENERIC,
                )
            )
            val factory = DefaultPrinterTransportFactory(
                moonrakerClientFactory = { MoonrakerClient() },
                machineProfileFactory = { profile },
            )
            val transport = factory.create(
                Printer(
                    id = "limited",
                    nickname = "Limited",
                    moonrakerUrl = server.url("/").toString(),
                )
            )

            assertEquals(
                TransportCommandResult.Success,
                transport.setHeaterTemperature("extruder", 999),
            )
            val request = server.takeRequest()
            assertEquals("/printer/gcode/script", request.path)
            assertTrue(request.body.readUtf8().contains("TARGET=275"))
        } finally {
            server.shutdown()
        }
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
