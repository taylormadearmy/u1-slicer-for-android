package com.u1.slicer.printer

import com.u1.slicer.data.BambuConfig
import com.u1.slicer.data.BambuModel
import com.u1.slicer.network.FilamentRouting
import com.u1.slicer.network.NozzleSide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BambuLanTransportTest {

    @Test
    fun `read only capabilities are exposed for bambu transport`() {
        val transport = BambuLanTransport(validConfig())

        assertTrue(transport.capabilities.supportsFilamentSync)
        assertTrue(transport.capabilities.reportsFilamentSlotsWithStatus)
        assertTrue(transport.capabilities.supportsUpload)
        assertTrue(transport.capabilities.supportsStartProject)
        assertTrue(!transport.capabilities.supportsStartJob)
        assertTrue(transport.capabilities.supportsPause)
        assertTrue(transport.capabilities.supportsResume)
        assertTrue(transport.capabilities.supportsCancel)
    }

    @Test
    fun `testConnection validates access code and serial before transport is live`() = runBlocking {
        val missingAccessCode = BambuLanTransport(validConfig().copy(accessCode = ""))
        val invalidSerial = BambuLanTransport(validConfig().copy(serial = ""))
        val unresolved = BambuLanTransport(validConfig())

        assertEquals("Bambu access code is required", missingAccessCode.testConnection())
        assertEquals("Bambu serial is required", invalidSerial.testConnection())
        assertEquals("Bambu LAN live connection is not implemented yet", unresolved.testConnection())
    }

    @Test
    fun `applyPushReport updates status and filament slots`() = runBlocking {
        val transport = BambuLanTransport(validConfig())

        transport.applyPushReport(
            """
            {
              "print": {
                "gcode_state": "RUNNING",
                "mc_percent": 55,
                "subtask_name": "cube.3mf"
              },
              "ams": {
                "ams": [
                  { "tray": [
                    { "id": 0, "tray_type": "PLA", "tray_color": "ABCDEFff", "remain": 70 }
                  ]}
                ]
              }
            }
            """.trimIndent()
        )

        val status = transport.status.first()
        val slots = transport.filamentSlots.first()
        assertEquals("printing", status.state)
        assertEquals(0.55f, status.progress)
        assertEquals("cube.3mf", status.filename)
        assertEquals(1, slots.size)
        assertEquals("#ABCDEF", slots[0].color)
        assertEquals("PLA", slots[0].materialType)
    }

    @Test
    fun `applyPushReport keeps last status and slots for partial reports`() = runBlocking {
        val transport = BambuLanTransport(validConfig())

        transport.applyPushReport(
            """
            {
              "print": {
                "gcode_state": "FINISH",
                "mc_percent": 100,
                "subtask_name": "finished.3mf"
              },
              "ams": {
                "ams": [
                  { "tray": [
                    { "id": 0, "tray_type": "PLA", "tray_color": "ABCDEFff", "remain": 70 }
                  ]}
                ]
              }
            }
            """.trimIndent()
        )

        transport.applyPushReport("""{"print":{"mc_percent":100,"sequence_id":"2001"}}""")

        val status = transport.status.first()
        val slots = transport.filamentSlots.first()
        assertEquals("complete", status.state)
        assertEquals(1.0f, status.progress)
        assertEquals("finished.3mf", status.filename)
        assertEquals(1, slots.size)
        assertEquals("PLA", slots[0].materialType)
    }

    @Test
    fun `partial ams and external tray deltas deep merge without erasing other routes`() = runBlocking {
        val transport = BambuLanTransport(validConfig().copy(model = BambuModel.H2D))
        transport.applyPushReport(
            """
                {"print":{"gcode_state":"IDLE","vir_slot":[
                  {"id":254,"tray_type":"PLA"},{"id":255,"tray_type":"PETG"}
                ],"ams":{"ams":[
                  {"id":0,"tray":[{"id":0,"tray_type":"PLA"}]},
                  {"id":1,"tray":[{"id":0,"tray_type":"ABS"}]}
                ]}}}
            """.trimIndent(),
        )

        transport.applyPushReport(
            """{"print":{"ams":{"ams":[{"id":1,"tray":[{"id":0,"tray_type":"ASA"}]}]}}}""",
        )

        val slots = transport.filamentSlots.first().associateBy { it.index }
        assertEquals(setOf(0, 4, 254, 255), slots.keys)
        assertEquals("PLA", slots.getValue(0).materialType)
        assertEquals("ASA", slots.getValue(4).materialType)
        assertEquals("PLA", slots.getValue(254).materialType)
        assertEquals("PETG", slots.getValue(255).materialType)
    }

    @Test
    fun `applyPushReport preserves fixed nozzle topology when later ams report omits it`() = runBlocking {
        val transport = BambuLanTransport(validConfig().copy(model = BambuModel.H2D))

        transport.applyPushReport(
            """
                {
                  "print": {
                    "gcode_state": "IDLE",
                    "nozzle_temper": [25, 25],
                    "ams": { "ams": [{
                      "id": 0,
                      "info": "10001003",
                      "tray": [{ "id": 0, "tray_type": "PLA", "tray_color": "112233FF" }]
                    }] }
                  }
                }
            """.trimIndent(),
        )
        transport.applyPushReport(
            """
                {
                  "print": {
                    "gcode_state": "IDLE",
                    "ams": { "ams": [{
                      "id": 0,
                      "tray": [{ "id": 0, "tray_type": "PETG", "tray_color": "445566FF" }]
                    }] }
                  }
                }
            """.trimIndent(),
        )

        val slot = transport.filamentSlots.first().single()
        assertEquals("PETG", slot.materialType)
        assertEquals(NozzleSide.RIGHT, slot.nozzleSide)
        assertEquals(FilamentRouting.FIXED, slot.routing)
    }

    @Test
    fun `applyPushReport records partial fts report and keeps it across later status`() = runBlocking {
        val transport = BambuLanTransport(validConfig().copy(model = BambuModel.H2D))
        transport.applyPushReport(
            """
                {
                  "print": {
                    "gcode_state": "RUNNING",
                    "mc_percent": 25,
                    "nozzle_temper": [210, 205],
                    "ams": { "ams": [{
                      "id": 0,
                      "info": "10001003",
                      "tray": [{ "id": 0, "tray_type": "PLA" }]
                    }] }
                  }
                }
            """.trimIndent(),
        )

        // Topology-only incremental push: no gcode_state and no AMS tray list.
        transport.applyPushReport(
            """
                {
                  "print": {
                    "device": {
                      "fila_switch": { "in": [-1, 2], "out": [0, 1], "stat": 0, "info": 2 }
                    }
                  }
                }
            """.trimIndent(),
        )
        transport.applyPushReport("""{"print":{"gcode_state":"RUNNING","mc_percent":30}}""")

        val status = transport.status.first()
        val slot = transport.filamentSlots.first().single()
        assertEquals("printing", status.state)
        assertEquals(0.30f, status.progress)
        assertTrue(status.filamentTrackSwitch.installed)
        assertEquals(listOf(-1, 2), status.filamentTrackSwitch.inputSlots)
        assertEquals(FilamentRouting.SWITCHABLE, slot.routing)
        assertEquals(NozzleSide.RIGHT, slot.nozzleSide)
    }

    @Test
    fun `start wires client reports into transport flows and stop disconnects client`() = runBlocking {
        val client = FakeBambuLanClient()
        val transport = BambuLanTransport(validConfig(), client)

        transport.start(CoroutineScope(Dispatchers.Unconfined))
        client.emitReport(
            """
            {
              "print": { "gcode_state": "RUNNING", "mc_percent": 10, "subtask_name": "client.3mf" }
            }
            """.trimIndent()
        )

        assertTrue(client.started)
        assertEquals("printing", transport.status.first().state)
        assertEquals("client.3mf", transport.status.first().filename)

        transport.stop()

        assertTrue(client.stopped)
        assertEquals("disconnected", transport.status.first().state)
    }

    @Test
    fun `start keeps transport disconnected when mqtt startup fails`() = runBlocking {
        val transport = BambuLanTransport(
            validConfig(),
            FakeBambuLanClient(startFailure = IllegalStateException("boom")),
        )

        transport.start(CoroutineScope(Dispatchers.Unconfined))

        assertEquals("disconnected", transport.status.first().state)
        assertTrue(transport.filamentSlots.first().isEmpty())
    }

    @Test
    fun `unexpected mqtt disconnect reconnects monitoring without replaying commands`() = runBlocking {
        val client = FakeBambuLanClient()
        val transport = BambuLanTransport(
            config = validConfig(),
            client = client,
            connectionRetryDelayMs = 0,
        )

        transport.start(CoroutineScope(Dispatchers.Unconfined))
        client.emitReport("""{"print":{"gcode_state":"DISCONNECTED"}}""")
        withTimeout(1_000) {
            while (client.startCount < 2) delay(1)
        }

        assertEquals(2, client.startCount)
        assertTrue(client.commands.isEmpty())
        assertTrue(client.projectStarts.isEmpty())
        transport.stop()
    }

    @Test
    fun `testConnection delegates to client for configured transport`() = runBlocking {
        val client = FakeBambuLanClient(testConnectionResult = null)
        val transport = BambuLanTransport(validConfig(), client)

        assertEquals(null, transport.testConnection())
        assertTrue(client.tested)
    }

    @Test
    fun `pause resume and cancel publish via live bambu client`() = runBlocking {
        val client = FakeBambuLanClient(testConnectionResult = null)
        val transport = BambuLanTransport(validConfig(), client)
        transport.start(CoroutineScope(Dispatchers.Unconfined))

        assertEquals(TransportCommandResult.Success, transport.pauseJob())
        assertEquals(TransportCommandResult.Success, transport.resumeJob())
        assertEquals(TransportCommandResult.Success, transport.cancelJob())
        assertEquals(listOf("pause", "resume", "stop"), client.commands)
    }

    @Test
    fun `uploadJob delegates to ftps uploader`() = runBlocking {
        val uploadClient = FakeBambuFileUploadClient()
        val transport = BambuLanTransport(
            config = validConfig(),
            client = FakeBambuLanClient(testConnectionResult = null),
            uploadClient = uploadClient,
        )
        val file = File.createTempFile("bambu-upload", ".3mf")
        file.writeText("zip-ish")

        try {
            assertEquals(TransportCommandResult.Success, transport.uploadJob(file, "cube.3mf"))
            assertEquals(file.absolutePath, uploadClient.uploadedFile?.absolutePath)
            assertEquals("/cache/cube.3mf", uploadClient.remoteName)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a1 mini uploadJob targets legacy cache path`() = runBlocking {
        val uploadClient = FakeBambuFileUploadClient()
        val transport = BambuLanTransport(
            config = validConfig().copy(model = BambuModel.A1_MINI),
            client = FakeBambuLanClient(testConnectionResult = null),
            uploadClient = uploadClient,
        )
        val file = File.createTempFile("bambu-a1-upload", ".3mf")
        file.writeText("zip-ish")

        try {
            assertEquals(TransportCommandResult.Success, transport.uploadJob(file, "cube.gcode.3mf"))
            assertEquals("/cache/cube.gcode.3mf", uploadClient.remoteName)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `startProject delegates to mqtt project command`() = runBlocking {
        val client = FakeBambuLanClient(testConnectionResult = null)
        val transport = BambuLanTransport(
            config = validConfig(),
            client = client,
            uploadClient = FakeBambuFileUploadClient(),
        )
        val file = createExecutableProject(
            plateId = 2,
            machineName = "Bambu Lab P1S",
            filamentIds = listOf(0, 1),
        )

        try {
            assertEquals(TransportCommandResult.Success, transport.uploadJob(file, "cube.3mf"))
            assertEquals(
                TransportCommandResult.Success,
                transport.startProject(
                    remoteName = "cube.3mf",
                    plateId = 2,
                    amsMapping = listOf(0, 3),
                    useAms = true,
                    subtaskName = "cube",
                )
            )
            assertEquals(
                listOf(ProjectStartCall("cube.3mf", 2, listOf(0, 3), true, "cube", projectGcodeMd5("Bambu Lab P1S"))),
                client.projectStarts,
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `h2d start pads selected plate mapping to project filament count`() = runBlocking {
        val client = FakeBambuLanClient(testConnectionResult = null)
        val transport = BambuLanTransport(
            config = validConfig().copy(model = BambuModel.H2D),
            client = client,
            uploadClient = FakeBambuFileUploadClient(),
        )
        val file = createExecutableProject(
            plateId = 1,
            machineName = "Bambu Lab H2D",
            filamentIds = listOf(0),
            projectFilamentCount = 4,
        )

        try {
            assertEquals(TransportCommandResult.Success, transport.uploadJob(file, "cube.gcode.3mf"))
            assertEquals(
                TransportCommandResult.Success,
                transport.startProject(
                    remoteName = "cube.gcode.3mf",
                    plateId = 1,
                    amsMapping = listOf(2),
                    useAms = true,
                    subtaskName = "cube",
                ),
            )
            assertEquals(listOf(2, -1, -1, -1), client.projectStarts.single().amsMapping)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `uploaded project supplies selected plate gcode checksum to mqtt`() = runBlocking {
        val client = FakeBambuLanClient(testConnectionResult = null)
        val transport = BambuLanTransport(
            config = validConfig().copy(model = BambuModel.A1_MINI),
            client = client,
            uploadClient = FakeBambuFileUploadClient(),
        )
        val file = createExecutableProject(
            plateId = 2,
            machineName = "Bambu Lab A1 mini",
            filamentIds = listOf(0),
        )

        try {
            assertEquals(TransportCommandResult.Success, transport.uploadJob(file, "cube.gcode.3mf"))
            assertEquals(
                TransportCommandResult.Success,
                transport.startProject("cube.gcode.3mf", 2, listOf(0), true, "cube"),
            )
            assertEquals(projectGcodeMd5("Bambu Lab A1 mini"), client.projectStarts.single().plateGcodeMd5)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `startProject rejects project sliced for another printer before mqtt`() = runBlocking {
        val client = FakeBambuLanClient(testConnectionResult = null)
        val transport = BambuLanTransport(
            config = validConfig().copy(model = BambuModel.A1_MINI),
            client = client,
            uploadClient = FakeBambuFileUploadClient(),
        )
        val file = createExecutableProject(
            plateId = 1,
            machineName = "Bambu Lab P1S",
            filamentIds = listOf(0),
        )

        try {
            assertEquals(TransportCommandResult.Success, transport.uploadJob(file, "wrong.gcode.3mf"))
            val result = transport.startProject("wrong.gcode.3mf", 1, listOf(0), true, "wrong")

            assertTrue(result is TransportCommandResult.Failure)
            assertTrue((result as TransportCommandResult.Failure).reason.contains("not Bambu A1 Mini"))
            assertTrue(client.projectStarts.isEmpty())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `supported bambu models expose camera streaming state`() = runBlocking {
        val cameraClient = FakeBambuCameraClient(supported = true)
        val transport = BambuLanTransport(
            config = validConfig().copy(model = BambuModel.A1_MINI),
            client = FakeBambuLanClient(testConnectionResult = null),
            cameraClient = cameraClient,
        )

        transport.start(CoroutineScope(Dispatchers.Unconfined))

        assertTrue(transport.capabilities.supportsCamera)
        assertTrue(transport.cameraState.first() is CameraState.Streaming)
    }

    @Test
    fun `camera retry restores streaming state after connection error`() = runBlocking {
        val cameraClient = FailOnceBambuCameraClient()
        val transport = BambuLanTransport(
            config = validConfig().copy(model = BambuModel.A1_MINI),
            client = FakeBambuLanClient(testConnectionResult = null),
            cameraClient = cameraClient,
            cameraRetryDelayMs = 0,
        )

        transport.start(CoroutineScope(Dispatchers.Unconfined))
        repeat(100) {
            if (cameraClient.attempts >= 2) return@repeat
            delay(1)
        }

        assertEquals(2, cameraClient.attempts)
        assertTrue(transport.cameraState.first() is CameraState.Streaming)

        transport.stop()
    }

    @Test
    fun `unsupported bambu camera models keep camera disabled`() = runBlocking {
        val transport = BambuLanTransport(
            config = validConfig().copy(model = BambuModel.X1C),
            client = FakeBambuLanClient(testConnectionResult = null),
            cameraClient = FakeBambuCameraClient(supported = false),
        )

        transport.start(CoroutineScope(Dispatchers.Unconfined))

        assertTrue(!transport.capabilities.supportsCamera)
        assertTrue(transport.cameraState.first() is CameraState.Disabled)
    }

    private fun validConfig(): BambuConfig = BambuConfig(
        ip = "192.168.1.88",
        accessCode = "12345678",
        serial = "P1S123ABC",
        model = BambuModel.P1S,
    )

    private fun createExecutableProject(
        plateId: Int,
        machineName: String,
        filamentIds: List<Int>,
        projectFilamentCount: Int? = null,
    ): File {
        val file = File.createTempFile("bambu-project", ".3mf")
        val prefix = "Metadata/plate_$plateId"
        ZipOutputStream(file.outputStream()).use { zip ->
            fun add(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            add("$prefix.gcode", projectGcode(machineName, projectFilamentCount))
            add("$prefix.gcode.md5", projectGcodeMd5(machineName, projectFilamentCount))
            add("$prefix.json", "{\"filament_ids\":${filamentIds.joinToString(prefix = "[", postfix = "]")}}")
        }
        return file
    }

    private fun projectGcode(machineName: String, projectFilamentCount: Int? = null): String {
        val filamentHeader = projectFilamentCount?.let { count ->
            "; filament_colour = ${(0 until count).joinToString(";") { "#111111" }}\n"
        }.orEmpty()
        return "; printer_model = $machineName\n${filamentHeader}G28\nM104 S220\n"
    }

    private fun projectGcodeMd5(machineName: String, projectFilamentCount: Int? = null): String =
        MessageDigest.getInstance("MD5")
        .digest(projectGcode(machineName, projectFilamentCount).toByteArray())
        .joinToString("") { byte -> "%02X".format(byte) }

    private class FakeBambuLanClient(
        private val startFailure: Exception? = null,
        private val testConnectionResult: String? = "fake failure",
    ) : BambuLanClient {
        var started = false
        var startCount = 0
        var stopped = false
        var tested = false
        val commands = mutableListOf<String>()
        val projectStarts = mutableListOf<ProjectStartCall>()
        private var reportListener: ((String) -> Unit)? = null

        override suspend fun start(config: BambuConfig, onReport: (String) -> Unit) {
            startFailure?.let { throw it }
            started = true
            startCount += 1
            reportListener = onReport
        }

        override suspend fun stop() {
            stopped = true
        }

        override suspend fun testConnection(config: BambuConfig): String? {
            tested = true
            return testConnectionResult
        }

        override suspend fun sendPrintCommand(config: BambuConfig, command: String) {
            commands += command
        }

        override suspend fun startProjectFile(
            config: BambuConfig,
            remoteName: String,
            plateId: Int,
            amsMapping: List<Int>,
            useAms: Boolean,
            subtaskName: String,
            plateGcodeMd5: String,
        ) {
            projectStarts += ProjectStartCall(remoteName, plateId, amsMapping, useAms, subtaskName, plateGcodeMd5)
        }

        fun emitReport(json: String) {
            reportListener?.invoke(json)
        }
    }

    private data class ProjectStartCall(
        val remoteName: String,
        val plateId: Int,
        val amsMapping: List<Int>,
        val useAms: Boolean,
        val subtaskName: String,
        val plateGcodeMd5: String = "",
    )

    private class FakeBambuFileUploadClient : BambuFileUploadClient {
        var uploadedFile: File? = null
        var remoteName: String? = null

        override suspend fun upload(config: BambuConfig, file: File, remoteName: String) {
            uploadedFile = file
            this.remoteName = remoteName
        }
    }

    private class FakeBambuCameraClient(
        private val supported: Boolean,
    ) : BambuCameraClient {
        var started = false

        override fun supports(model: BambuModel): Boolean = supported

        override suspend fun stream(config: BambuConfig, onFrame: (android.graphics.Bitmap) -> Unit) {
            started = true
            awaitCancellation()
        }
    }

    private class FailOnceBambuCameraClient : BambuCameraClient {
        var attempts = 0

        override fun supports(model: BambuModel): Boolean = true

        override suspend fun stream(config: BambuConfig, onFrame: (android.graphics.Bitmap) -> Unit) {
            attempts += 1
            if (attempts == 1) throw java.net.SocketException("Connection reset")
            awaitCancellation()
        }
    }
}
