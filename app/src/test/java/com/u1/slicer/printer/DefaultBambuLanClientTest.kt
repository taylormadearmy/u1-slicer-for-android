package com.u1.slicer.printer

import com.u1.slicer.data.BambuConfig
import com.u1.slicer.data.BambuModel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.eclipse.paho.client.mqttv3.MqttException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLSocketFactory

class DefaultBambuLanClientTest {

    @Test
    fun `start creates session subscribes to report topic and publishes pushall`() = runBlocking {
        val session = FakeBambuMqttSession()
        val factory = FakeBambuMqttSessionFactory(session)
        val client = DefaultBambuLanClient(factory)
        var report: String? = null

        client.start(validConfig()) { report = it }

        assertEquals("ssl://192.168.1.88:8883", factory.serverUri)
        assertEquals("bblp", factory.username)
        assertEquals("12345678", factory.password)
        assertNotNull(factory.socketFactory)
        assertTrue(session.connected)
        assertEquals(
            listOf("device/P1S123ABC/report"),
            session.subscribedTopics,
        )
        assertEquals("device/P1S123ABC/request", session.publishedTopic)
        assertEquals("""{"pushing":{"command":"pushall"}}""", session.publishedPayload)

        session.emit("""{"print":{"gcode_state":"RUNNING"}}""")
        assertEquals("""{"print":{"gcode_state":"RUNNING"}}""", report)
    }

    @Test
    fun `testConnection probes serial specific mqtt topics before succeeding`() = runBlocking {
        val session = FakeBambuMqttSession(autoEmitOnPublish = """{"print":{"gcode_state":"IDLE"}}""")
        val factory = FakeBambuMqttSessionFactory(session)
        val client = DefaultBambuLanClient(factory)

        val result = client.testConnection(validConfig())

        assertEquals(null, result)
        assertTrue(session.connected)
        assertTrue(session.disconnected)
        assertEquals("device/P1S123ABC/report", session.subscribedTopic)
        assertEquals("device/P1S123ABC/request", session.publishedTopic)
        assertEquals("""{"pushing":{"command":"pushall"}}""", session.publishedPayload)
    }

    @Test
    fun `testConnection reports secured firmware with developer mode disabled`() = runBlocking {
        val session = FakeBambuMqttSession(
            autoEmitOnPublish = """{"print":{"gcode_state":"IDLE","fun":536870912}}""",
        )
        val client = DefaultBambuLanClient(FakeBambuMqttSessionFactory(session))

        val result = client.testConnection(validConfig())

        assertTrue(result?.contains("Developer Mode is disabled") == true)
        assertEquals(false, DefaultBambuLanClient.parseDeveloperMode(
            """{"print":{"fun":"536870912"}}""",
        ))
        assertEquals(true, DefaultBambuLanClient.parseDeveloperMode(
            """{"print":{"fun":0}}""",
        ))
        // Pre-security and A/P firmware may omit the field.
        assertEquals(null, DefaultBambuLanClient.parseDeveloperMode(
            """{"print":{"gcode_state":"IDLE"}}""",
        ))
    }

    @Test
    fun `testConnection succeeds without opening a second MQTT session when live session is connected`() = runBlocking {
        val session = FakeBambuMqttSession()
        val factory = FakeBambuMqttSessionFactory(session)
        val client = DefaultBambuLanClient(factory)

        client.start(validConfig()) {}

        assertEquals(null, client.testConnection(validConfig()))
        assertEquals(1, factory.createCount)
        assertTrue(!session.disconnected)
    }

    @Test
    fun `testConnection reports serial topic timeout when printer never answers`() = runBlocking {
        val session = FakeBambuMqttSession(respondToPushAll = false)
        val client = DefaultBambuLanClient(
            sessionFactory = FakeBambuMqttSessionFactory(session),
            connectionProbeTimeoutMillis = 1,
        )

        val result = client.testConnection(validConfig())

        assertEquals(
            "Connected, but the printer did not answer on its serial-specific MQTT topic. Check the Bambu serial.",
            result,
        )
    }

    @Test
    fun `stop disconnects active session`() = runBlocking {
        val session = FakeBambuMqttSession()
        val client = DefaultBambuLanClient(FakeBambuMqttSessionFactory(session))
        client.start(validConfig()) {}

        client.stop()

        assertTrue(session.disconnected)
    }

    @Test
    fun `disconnect callback emits disconnected sentinel report`() = runBlocking {
        val session = FakeBambuMqttSession()
        val client = DefaultBambuLanClient(FakeBambuMqttSessionFactory(session))
        var report: String? = null

        client.start(validConfig()) { report = it }
        session.emitDisconnected()

        assertEquals("""{"print":{"gcode_state":"DISCONNECTED"}}""", report)
    }

    @Test
    fun `testConnection maps common network failures to user facing messages`() = runBlocking {
        val connectRefused = DefaultBambuLanClient(
            FakeBambuMqttSessionFactory(FakeBambuMqttSession(connectFailure = ConnectException("refused")))
        )
        val unknownHost = DefaultBambuLanClient(
            FakeBambuMqttSessionFactory(FakeBambuMqttSession(connectFailure = UnknownHostException("unknown")))
        )
        val timeout = DefaultBambuLanClient(
            FakeBambuMqttSessionFactory(FakeBambuMqttSession(connectFailure = SocketTimeoutException("timeout")))
        )

        assertEquals("Connection refused - check IP, access code, and LAN reachability", connectRefused.testConnection(validConfig()))
        assertEquals("Unknown host - check the Bambu printer IP", unknownHost.testConnection(validConfig()))
        assertEquals("Timed out - check the printer is on and reachable", timeout.testConnection(validConfig()))
    }

    @Test
    fun `testConnection maps mqtt auth and protocol failures to user facing messages`() = runBlocking {
        val authFailed = DefaultBambuLanClient(
            FakeBambuMqttSessionFactory(FakeBambuMqttSession(connectFailure = MqttException(MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt())))
        )
        val badProtocol = DefaultBambuLanClient(
            FakeBambuMqttSessionFactory(FakeBambuMqttSession(connectFailure = MqttException(MqttException.REASON_CODE_INVALID_PROTOCOL_VERSION.toInt())))
        )

        assertEquals("Authentication failed - check the Bambu serial and access code", authFailed.testConnection(validConfig()))
        assertEquals("MQTT protocol mismatch - retry after updating the app", badProtocol.testConnection(validConfig()))
    }

    @Test
    fun `helper methods expose canonical mqtt contract`() {
        assertEquals("ssl://192.168.1.88:8883", DefaultBambuLanClient.serverUri(validConfig()))
        assertEquals("device/P1S123ABC/report", DefaultBambuLanClient.reportTopic(validConfig()))
        assertEquals("device/P1S123ABC/request", DefaultBambuLanClient.requestTopic(validConfig()))
        assertEquals("""{"pushing":{"command":"pushall"}}""", DefaultBambuLanClient.pushAllPayload())
    }

    @Test
    fun `sendPrintCommand publishes mqtt request on active session`() = runBlocking {
        val session = FakeBambuMqttSession()
        val client = DefaultBambuLanClient(FakeBambuMqttSessionFactory(session))

        client.start(validConfig()) {}
        client.sendPrintCommand(validConfig(), "pause")

        assertEquals("device/P1S123ABC/request", session.publishedTopic)
        assertEquals("""{"print":{"sequence_id":"1","command":"pause","param":""}}""", session.publishedPayload)
    }

    @Test
    fun `projectFileCommandPayload encodes selected plate and ams mapping`() {
        val payload = DefaultBambuLanClient.projectFileCommandPayload(
            sequenceId = 7,
            submissionId = "123",
            remoteName = "cube.3mf",
            plateId = 2,
            amsMapping = listOf(0, 3),
            useAms = true,
            subtaskName = "cube",
        )

        val print = JSONObject(payload).getJSONObject("print")
        assertEquals("project_file", print.getString("command"))
        assertEquals("7", print.getString("sequence_id"))
        assertEquals("Metadata/plate_2.gcode", print.getString("param"))
        assertEquals("file:///sdcard/cache/cube.3mf", print.getString("url"))
        assertEquals("cube.3mf", print.getString("file"))
        assertEquals("", print.getString("md5"))
        assertEquals("cube", print.getString("subtask_name"))
        assertTrue(print.getBoolean("use_ams"))
        assertEquals(JSONArray(listOf(0, 3)).toString(), print.getJSONArray("ams_mapping").toString())
        assertEquals("123", print.getString("project_id"))
        assertTrue(!print.getBoolean("bed_leveling"))
        assertEquals(2, print.getInt("auto_bed_leveling"))
        assertEquals(2, print.getInt("extrude_cali_flag"))
        assertEquals(0, print.getInt("nozzle_offset_cali"))
        val amsMapping2 = print.getJSONArray("ams_mapping2")
        assertEquals(2, amsMapping2.length())
        assertEquals(0, amsMapping2.getJSONObject(0).getInt("ams_id"))
        assertEquals(0, amsMapping2.getJSONObject(0).getInt("slot_id"))
        assertEquals(0, amsMapping2.getJSONObject(1).getInt("ams_id"))
        assertEquals(3, amsMapping2.getJSONObject(1).getInt("slot_id"))
    }

    @Test
    fun `h2d project payload preserves dual nozzle routing`() {
        val payload = DefaultBambuLanClient.projectFileCommandPayload(
            sequenceId = 8,
            submissionId = "456",
            remoteName = "h2-project.gcode.3mf",
            plateId = 1,
            amsMapping = listOf(0, -1, 128, 254, 255),
            useAms = true,
            subtaskName = "h2-project",
            model = BambuModel.H2D,
        )

        val print = JSONObject(payload).getJSONObject("print")
        assertEquals("ftp:///h2-project.gcode.3mf", print.getString("url"))
        assertTrue(print.getBoolean("use_ams"))
        assertTrue(!print.getBoolean("bed_leveling"))
        assertEquals(2, print.getInt("auto_bed_leveling"))
        assertEquals(2, print.getInt("extrude_cali_flag"))
        assertEquals(2, print.getInt("nozzle_offset_cali"))
        assertEquals(
            JSONArray(listOf(0, -1, 128, -1, -1)).toString(),
            print.getJSONArray("ams_mapping").toString(),
        )
        val detailed = print.getJSONArray("ams_mapping2")
        assertEquals(5, detailed.length())
        assertEquals(0, detailed.getJSONObject(0).getInt("ams_id"))
        assertEquals(0, detailed.getJSONObject(0).getInt("slot_id"))
        assertEquals(255, detailed.getJSONObject(1).getInt("ams_id"))
        assertEquals(255, detailed.getJSONObject(1).getInt("slot_id"))
        assertEquals(128, detailed.getJSONObject(2).getInt("ams_id"))
        assertEquals(0, detailed.getJSONObject(2).getInt("slot_id"))
        assertEquals(254, detailed.getJSONObject(3).getInt("ams_id"))
        assertEquals(0, detailed.getJSONObject(3).getInt("slot_id"))
        assertEquals(255, detailed.getJSONObject(4).getInt("ams_id"))
        assertEquals(0, detailed.getJSONObject(4).getInt("slot_id"))
    }

    @Test
    fun `single nozzle unresolved route does not disable ams`() {
        val print = JSONObject(
            DefaultBambuLanClient.projectFileCommandPayload(
                sequenceId = 9,
                remoteName = "cube.3mf",
                plateId = 1,
                amsMapping = listOf(-1),
                useAms = true,
                subtaskName = "cube",
                model = BambuModel.P1S,
            ),
        ).getJSONObject("print")

        assertTrue(print.getBoolean("use_ams"))
        assertEquals(-1, print.getJSONArray("ams_mapping").getInt(0))
        assertEquals(255, print.getJSONArray("ams_mapping2").getJSONObject(0).getInt("ams_id"))
        assertEquals(255, print.getJSONArray("ams_mapping2").getJSONObject(0).getInt("slot_id"))
    }

    @Test
    fun `single nozzle explicit external route disables ams`() {
        val print = JSONObject(
            DefaultBambuLanClient.projectFileCommandPayload(
                sequenceId = 10,
                remoteName = "cube.3mf",
                plateId = 1,
                amsMapping = listOf(254),
                useAms = true,
                subtaskName = "cube",
                model = BambuModel.P1S,
            ),
        ).getJSONObject("print")

        assertTrue(!print.getBoolean("use_ams"))
        assertEquals(-1, print.getJSONArray("ams_mapping").getInt(0))
        assertEquals(255, print.getJSONArray("ams_mapping2").getJSONObject(0).getInt("ams_id"))
        assertEquals(0, print.getJSONArray("ams_mapping2").getJSONObject(0).getInt("slot_id"))
    }

    @Test
    fun `single nozzle real tray forces ams even when caller toggle is false`() {
        val print = JSONObject(
            DefaultBambuLanClient.projectFileCommandPayload(
                sequenceId = 11,
                remoteName = "cube.3mf",
                plateId = 1,
                amsMapping = listOf(128),
                useAms = false,
                subtaskName = "cube",
                model = BambuModel.P1S,
            ),
        ).getJSONObject("print")

        assertTrue(print.getBoolean("use_ams"))
        assertEquals(128, print.getJSONArray("ams_mapping").getInt(0))
        assertEquals(128, print.getJSONArray("ams_mapping2").getJSONObject(0).getInt("ams_id"))
    }

    @Test
    fun `single nozzle mapping preserves more than five multi ams filaments`() {
        val mapping = (0..7).toList()
        val print = JSONObject(
            DefaultBambuLanClient.projectFileCommandPayload(
                sequenceId = 12,
                remoteName = "many-colours.3mf",
                plateId = 1,
                amsMapping = mapping,
                useAms = true,
                subtaskName = "many-colours",
                model = BambuModel.X1C,
            ),
        ).getJSONObject("print")

        assertEquals(8, print.getJSONArray("ams_mapping").length())
        assertEquals(8, print.getJSONArray("ams_mapping2").length())
        assertEquals(1, print.getJSONArray("ams_mapping2").getJSONObject(7).getInt("ams_id"))
        assertEquals(3, print.getJSONArray("ams_mapping2").getJSONObject(7).getInt("slot_id"))
    }

    @Test
    fun `verify failed response explains developer mode`() {
        val response = BambuProjectResponse(
            sequenceId = "1",
            result = "failed",
            reason = "mqtt message verify failed",
        )

        assertTrue(response.failureMessage().contains("Enable Developer Mode"))
    }

    @Test
    fun `file routing keeps a series projects in the legacy cache`() {
        assertEquals(
            "/cache/cube.gcode.3mf",
            DefaultBambuLanClient.projectUploadPath(BambuModel.A1_MINI, "cube.gcode.3mf"),
        )
        assertEquals(
            "file:///sdcard/cache/cube.gcode.3mf",
            DefaultBambuLanClient.projectFileUrl(BambuModel.A1_MINI, "cube.gcode.3mf"),
        )
        assertEquals(
            "/cache/cube.gcode.3mf",
            DefaultBambuLanClient.projectUploadPath(BambuModel.A1, "cube.gcode.3mf"),
        )
        listOf(BambuModel.P1P, BambuModel.P1S, BambuModel.X1C, BambuModel.X1E).forEach { model ->
            assertEquals("$model upload path", "/cache/cube.3mf", DefaultBambuLanClient.projectUploadPath(model, "cube.3mf"))
            assertEquals(
                "$model project URL",
                "file:///sdcard/cache/cube.3mf",
                DefaultBambuLanClient.projectFileUrl(model, "cube.3mf"),
            )
        }
        assertEquals(
            "/cube.gcode.3mf",
            DefaultBambuLanClient.projectUploadPath(BambuModel.H2D, "cube.gcode.3mf"),
        )
        assertEquals(
            "ftp:///cube.gcode.3mf",
            DefaultBambuLanClient.projectFileUrl(BambuModel.H2D, "cube.gcode.3mf"),
        )
    }

    @Test
    fun `a series routing rejects a model project filename`() {
        val uploadError = runCatching {
            DefaultBambuLanClient.projectUploadPath(BambuModel.A1_MINI, "cube.3mf")
        }.exceptionOrNull()
        val commandError = runCatching {
            DefaultBambuLanClient.projectFileUrl(BambuModel.A1_MINI, "cube.3mf")
        }.exceptionOrNull()

        assertTrue(uploadError is IllegalArgumentException)
        assertTrue(commandError is IllegalArgumentException)
    }

    @Test
    fun `a1 mini modern command still addresses the legacy cache`() {
        val print = JSONObject(
            DefaultBambuLanClient.projectFileCommandPayload(
                sequenceId = 7,
                remoteName = "cube.gcode.3mf",
                plateId = 1,
                amsMapping = listOf(0),
                useAms = true,
                subtaskName = "ignored-for-a-series",
                model = BambuModel.A1_MINI,
                plateGcodeMd5 = "abc123",
            ),
        ).getJSONObject("print")

        assertEquals("7", print.getString("sequence_id"))
        assertEquals("Metadata/plate_1.gcode", print.getString("param"))
        assertEquals("cube", print.getString("subtask_name"))
        assertEquals("file:///sdcard/cache/cube.gcode.3mf", print.getString("url"))
        assertEquals("cube.gcode.3mf", print.getString("file"))
        assertEquals(JSONArray(listOf(0)).toString(), print.getJSONArray("ams_mapping").toString())
        assertEquals("", print.getString("md5"))
        assertEquals("7", print.getString("project_id"))
        assertEquals(1, print.getJSONArray("ams_mapping2").length())
    }

    @Test
    fun `a1 project payload uses unique submission identity`() {
        val print = JSONObject(
            DefaultBambuLanClient.projectFileCommandPayload(
                sequenceId = 20_000,
                submissionId = "123",
                remoteName = "cube.gcode.3mf",
                plateId = 1,
                amsMapping = listOf(0),
                useAms = true,
                subtaskName = "cube",
                model = BambuModel.A1_MINI,
            ),
        ).getJSONObject("print")

        assertEquals("20000", print.getString("sequence_id"))
        assertEquals("cube", print.getString("subtask_name"))
        assertEquals("file:///sdcard/cache/cube.gcode.3mf", print.getString("url"))
        assertEquals("cube.gcode.3mf", print.getString("file"))
        assertTrue(!print.getBoolean("bed_leveling"))
        assertEquals(JSONArray(listOf(0)).toString(), print.getJSONArray("ams_mapping").toString())
        assertEquals("123", print.getString("project_id"))
        assertEquals("123", print.getString("task_id"))
        assertEquals("123", print.getString("subtask_id"))
        assertEquals(1, print.getJSONArray("ams_mapping2").length())
    }

    @Test
    fun `firmware parser reads pre security a series version`() {
        val report = """{"print":{"upgrade_state":{"new_ver_list":[{"name":"ota","cur_ver":"01.04.00.00"}]}}}"""

        assertEquals("01.04.00.00", DefaultBambuLanClient.parseFirmwareVersion(report))
    }

    @Test
    fun `legacy a series payload gate matches archived firmware boundary`() {
        assertTrue(DefaultBambuLanClient.usesLegacyASeriesProjectPayload(BambuModel.A1_MINI, "01.04.00.00"))
        assertTrue(DefaultBambuLanClient.usesLegacyASeriesProjectPayload(BambuModel.A1, "00.99.00.00"))
        assertTrue(!DefaultBambuLanClient.usesLegacyASeriesProjectPayload(BambuModel.A1_MINI, "01.05.00.00"))
        assertTrue(!DefaultBambuLanClient.usesLegacyASeriesProjectPayload(BambuModel.H2D, "01.04.00.00"))
        assertTrue(!DefaultBambuLanClient.usesLegacyASeriesProjectPayload(BambuModel.A1_MINI, null))
        assertTrue(!DefaultBambuLanClient.usesLegacyASeriesProjectPayload(BambuModel.A1_MINI, "unknown"))
    }

    @Test
    fun `pre security a1 firmware uses the legacy cache project payload`() {
        val print = JSONObject(
            DefaultBambuLanClient.projectFileCommandPayload(
                sequenceId = 20_000,
                submissionId = "456",
                remoteName = "cube.gcode.3mf",
                plateId = 1,
                amsMapping = listOf(0),
                useAms = true,
                subtaskName = "cube",
                model = BambuModel.A1_MINI,
                firmwareVersion = "01.04.00.00",
            ),
        ).getJSONObject("print")

        assertEquals("file:///sdcard/cache/cube.gcode.3mf", print.getString("url"))
        assertEquals("cube.gcode.3mf", print.getString("subtask_name"))
        assertEquals(JSONArray(listOf(0)).toString(), print.getJSONArray("ams_mapping").toString())
        assertEquals(0, print.getInt("plate_idx"))
        assertTrue(print.getBoolean("bed_leveling"))
        assertTrue(!print.getBoolean("vibration_cali"))
        listOf(
            "file",
            "md5",
            "bed_type",
            "auto_bed_leveling",
            "cfg",
            "extrude_cali_flag",
            "nozzle_offset_cali",
            "profile_id",
            "project_id",
            "task_id",
            "subtask_id",
            "ams_mapping2",
        ).forEach { key -> assertTrue("legacy payload must omit $key", !print.has(key)) }
    }

    @Test
    fun `pre security full size a1 uses the same exact legacy contract`() {
        val print = JSONObject(
            DefaultBambuLanClient.projectFileCommandPayload(
                sequenceId = 91,
                remoteName = "a1-job.gcode.3mf",
                plateId = 2,
                amsMapping = listOf(3, -1),
                useAms = true,
                subtaskName = "ignored",
                model = BambuModel.A1,
                firmwareVersion = "01.04.01.00",
            ),
        ).getJSONObject("print")

        assertEquals("file:///sdcard/cache/a1-job.gcode.3mf", print.getString("url"))
        assertEquals("a1-job.gcode.3mf", print.getString("subtask_name"))
        assertEquals(1, print.getInt("plate_idx"))
        assertEquals(JSONArray(listOf(3, -1)).toString(), print.getJSONArray("ams_mapping").toString())
        assertTrue(print.getBoolean("bed_leveling"))
        assertTrue(!print.has("file"))
        assertTrue(!print.has("ams_mapping2"))
    }

    @Test
    fun `a1 project payload does not invent unused filament slots`() {
        val print = JSONObject(
            DefaultBambuLanClient.projectFileCommandPayload(
                sequenceId = 20_000,
                submissionId = "456",
                remoteName = "cube.gcode.3mf",
                plateId = 1,
                amsMapping = listOf(0),
                useAms = true,
                subtaskName = "cube",
                model = BambuModel.A1_MINI,
            ),
        ).getJSONObject("print")

        assertEquals("file:///sdcard/cache/cube.gcode.3mf", print.getString("url"))
        assertEquals(JSONArray(listOf(0)).toString(), print.getJSONArray("ams_mapping").toString())
        assertEquals("456", print.getString("project_id"))
        assertEquals("456", print.getString("task_id"))
        assertEquals("456", print.getString("subtask_id"))
        assertTrue(!print.getBoolean("bed_leveling"))
        assertEquals("cube.gcode.3mf", print.getString("file"))
        assertEquals(2, print.getInt("auto_bed_leveling"))
        assertEquals(1, print.getJSONArray("ams_mapping2").length())
    }

    @Test
    fun `request command diagnostic parser handles nested commands and malformed payloads`() {
        assertEquals(
            "project_file",
            DefaultBambuLanClient.requestCommand(
                """{"print":{"command":"project_file","url":"ftp://cube.gcode.3mf"}}""",
            ),
        )
        assertEquals("unknown", DefaultBambuLanClient.requestCommand("not-json"))
    }

    @Test
    fun `startProjectFile publishes mqtt request on active session`() = runBlocking {
        val session = FakeBambuMqttSession()
        val client = DefaultBambuLanClient(
            sessionFactory = FakeBambuMqttSessionFactory(session),
        )

        client.start(validConfig()) {}
        session.publishResponder = successfulProjectResponse()
        client.startProjectFile(
            config = validConfig(),
            remoteName = "cube.3mf",
            plateId = 2,
            amsMapping = listOf(0, 3),
            useAms = true,
            subtaskName = "cube",
        )

        assertEquals("device/P1S123ABC/request", session.publishedTopic)
        val print = JSONObject(session.publishedPayload ?: error("Missing payload")).getJSONObject("print")
        assertEquals("project_file", print.getString("command"))
        assertEquals("Metadata/plate_2.gcode", print.getString("param"))
        assertEquals("file:///sdcard/cache/cube.3mf", print.getString("url"))
        assertEquals(JSONArray(listOf(0, 3)).toString(), print.getJSONArray("ams_mapping").toString())
    }

    @Test
    fun `successive project commands use distinct sequence and submission ids`() = runBlocking {
        val session = FakeBambuMqttSession(
            publishResponder = { payload ->
                if (!payload.contains("\"project_file\"")) return@FakeBambuMqttSession null
                val sequenceId = JSONObject(payload).getJSONObject("print").getString("sequence_id")
                """{"print":{"sequence_id":"$sequenceId","command":"project_file","result":"success"}}"""
            },
        )
        val client = DefaultBambuLanClient(
            sessionFactory = FakeBambuMqttSessionFactory(session),
        )

        client.start(validConfig()) {}
        repeat(2) {
            client.startProjectFile(
                config = validConfig(),
                remoteName = "cube-$it.3mf",
                plateId = 1,
                amsMapping = listOf(0),
                useAms = true,
            )
        }

        val printCommands = session.publishedPayloads
            .filter { it.contains("\"project_file\"") }
            .map { JSONObject(it).getJSONObject("print") }
        val sequenceIds = printCommands.map { it.getString("sequence_id") }
        assertEquals(2, sequenceIds.distinct().size)
        assertTrue(sequenceIds.none { it == "0" || it == "20000" })
        val submissionIds = printCommands.map { it.getString("project_id") }
        assertEquals(2, submissionIds.distinct().size)
        assertTrue(submissionIds.none { it == "0" })
        assertEquals(submissionIds, sequenceIds)
        assertEquals(submissionIds, printCommands.map { it.getString("task_id") })
        assertEquals(submissionIds, printCommands.map { it.getString("subtask_id") })
    }

    @Test
    fun `a1 mini project accepts printer prepare status when firmware sends no command reply`() = runBlocking {
        val session = FakeBambuMqttSession()
        val client = DefaultBambuLanClient(
            sessionFactory = FakeBambuMqttSessionFactory(session),
        )
        val config = validConfig().copy(model = BambuModel.A1_MINI)

        client.start(config) {}
        session.emit("""{"print":{"command":"push_status","msg":0,"gcode_state":"IDLE"}}""")
        session.autoEmitOnPublish =
            """{"print":{"gcode_file":"cube.gcode.3mf","gcode_state":"PREPARE"}}"""

        client.startProjectFile(
            config = config,
            remoteName = "cube.gcode.3mf",
            plateId = 1,
            amsMapping = listOf(0),
            useAms = true,
        )

        assertEquals("project_file", JSONObject(session.publishedPayload ?: error("Missing payload"))
            .getJSONObject("print").getString("command"))
    }

    @Test
    fun `a1 mini sliced container restores legacy command after old firmware report`() = runBlocking {
        val session = FakeBambuMqttSession()
        val client = DefaultBambuLanClient(
            sessionFactory = FakeBambuMqttSessionFactory(session),
        )
        val config = validConfig().copy(model = BambuModel.A1_MINI)

        client.start(config) {}
        session.emit(
            """{"print":{"command":"push_status","msg":0,"gcode_state":"IDLE","upgrade_state":{"new_ver_list":[{"name":"ota","cur_ver":"01.04.00.00"}]}}}""",
        )
        session.autoEmitOnPublish =
            """{"print":{"gcode_file":"cube.gcode.3mf","gcode_state":"PREPARE"}}"""

        client.startProjectFile(
            config = config,
            remoteName = "cube.gcode.3mf",
            plateId = 1,
            amsMapping = listOf(0),
            useAms = true,
        )

        val print = JSONObject(session.publishedPayload ?: error("Missing payload")).getJSONObject("print")
        assertTrue(print.getString("sequence_id").toInt() > 20_000)
        assertEquals("project_file", print.getString("command"))
        assertEquals("Metadata/plate_1.gcode", print.getString("param"))
        assertEquals("file:///sdcard/cache/cube.gcode.3mf", print.getString("url"))
        assertEquals("cube.gcode.3mf", print.getString("subtask_name"))
        assertEquals(JSONArray(listOf(0)).toString(), print.getJSONArray("ams_mapping").toString())
        assertEquals(0, print.getInt("plate_idx"))
        assertTrue(print.getBoolean("bed_leveling"))
        assertTrue(!print.has("project_id"))
        assertTrue(!print.has("ams_mapping2"))
    }

    @Test
    fun `a1 mini never retries an unacknowledged print command`() = runBlocking {
        val session = FakeBambuMqttSession()
        val factory = FakeBambuMqttSessionFactory(session)
        val client = DefaultBambuLanClient(
            sessionFactory = factory,
            projectResponseTimeoutMillisOverride = 5,
        )
        val config = validConfig().copy(model = BambuModel.A1_MINI)

        client.start(config) {}
        session.emit("""{"print":{"command":"push_status","msg":0,"gcode_state":"IDLE"}}""")
        session.autoEmitOnPublish =
            """{"print":{"command":"push_status","msg":0,"gcode_state":"IDLE","project_id":"0","task_id":"0","subtask_id":"0"}}"""
        val error = runCatching {
            client.startProjectFile(
                config = config,
                remoteName = "cube.gcode.3mf",
                plateId = 1,
                amsMapping = listOf(0),
                useAms = true,
            )
        }.exceptionOrNull()

        assertEquals(1, factory.createCount)
        assertTrue(!session.disconnected)
        assertEquals("project_file", JSONObject(session.publishedPayload ?: error("Missing payload"))
            .getJSONObject("print").getString("command"))
        assertEquals(
            "Printer allowed monitoring and upload but did not acknowledge the print request. " +
                "The uploaded 3MF was not started.",
            error?.message,
        )
    }

    @Test
    fun `a1 mini does not retry after printer reports the uploaded filename`() = runBlocking {
        val session = FakeBambuMqttSession(
            publishResponder = { payload ->
                when {
                    payload.contains("\"pushall\"") ->
                        """{"print":{"command":"push_status","msg":0,"gcode_state":"IDLE"}}"""
                    payload.contains("\"project_file\"") ->
                        """{"print":{"gcode_file":"cube.gcode.3mf"}}"""
                    else -> null
                }
            },
        )
        val factory = FakeBambuMqttSessionFactory(session)
        val client = DefaultBambuLanClient(
            sessionFactory = factory,
            projectResponseTimeoutMillisOverride = 5,
        )
        val config = validConfig().copy(model = BambuModel.A1_MINI)

        client.start(config) {}
        session.emit("""{"print":{"command":"push_status","msg":0,"gcode_state":"IDLE"}}""")
        val error = runCatching {
            client.startProjectFile(
                config = config,
                remoteName = "cube.gcode.3mf",
                plateId = 1,
                amsMapping = listOf(0),
                useAms = true,
            )
        }.exceptionOrNull()

        assertEquals(1, factory.createCount)
        assertEquals(
            "The printer received the uploaded project but did not enter print preparation. " +
                "The app did not retry because that could start the same print twice.",
            error?.message,
        )
    }

    @Test
    fun `a1 mini project keeps a healthy live mqtt session`() = runBlocking {
        val session = FakeBambuMqttSession()
        val factory = FakeBambuMqttSessionFactory(session)
        val client = DefaultBambuLanClient(
            sessionFactory = factory,
        )
        val config = validConfig().copy(model = BambuModel.A1_MINI)

        client.start(config) {}
        session.emit("""{"print":{"command":"push_status","msg":0,"gcode_state":"IDLE"}}""")
        session.autoEmitOnPublish =
            """{"print":{"gcode_file":"cube.gcode.3mf","gcode_state":"PREPARE"}}"""
        client.startProjectFile(
            config = config,
            remoteName = "cube.gcode.3mf",
            plateId = 1,
            amsMapping = listOf(0),
            useAms = true,
        )

        assertEquals(1, factory.createCount)
        assertEquals("project_file", JSONObject(session.publishedPayload ?: error("Missing payload"))
            .getJSONObject("print").getString("command"))
    }

    @Test
    fun `h2d accepts matching prepare state as print confirmation`() = runBlocking {
        val session = FakeBambuMqttSession()
        val factory = FakeBambuMqttSessionFactory(session)
        val client = DefaultBambuLanClient(
            sessionFactory = factory,
        )
        val config = validConfig().copy(model = BambuModel.H2D)

        client.start(config) {}
        session.emit("""{"print":{"command":"push_status","msg":0,"gcode_state":"IDLE"}}""")
        session.autoEmitOnPublish =
            """{"print":{"gcode_file":"h2-project.gcode.3mf","gcode_state":"PREPARE"}}"""
        client.startProjectFile(
            config = config,
            remoteName = "h2-project.gcode.3mf",
            plateId = 1,
            amsMapping = listOf(0, -1, -1, -1),
            useAms = true,
        )

        assertEquals(1, factory.createCount)
        assertEquals(
            "project_file",
            JSONObject(session.publishedPayload ?: error("Missing payload"))
                .getJSONObject("print")
                .getString("command"),
        )
    }

    @Test
    fun `a1 mini does not accept unrelated prepare status as print confirmation`() = runBlocking {
        val session = FakeBambuMqttSession()
        val client = DefaultBambuLanClient(
            sessionFactory = FakeBambuMqttSessionFactory(session),
            projectResponseTimeoutMillisOverride = 5,
        )
        val config = validConfig().copy(model = BambuModel.A1_MINI)

        client.start(config) {}
        session.autoEmitOnPublish =
            """{"print":{"gcode_file":"another-project.gcode.3mf","gcode_state":"PREPARE"}}"""

        val error = runCatching {
            client.startProjectFile(
                config = config,
                remoteName = "cube.gcode.3mf",
                plateId = 1,
                amsMapping = listOf(0),
                useAms = true,
            )
        }.exceptionOrNull()

        assertEquals(
            "Printer allowed monitoring and upload but did not acknowledge the print request. " +
                "The uploaded 3MF was not started.",
            error?.message,
        )
    }

    @Test
    fun `project command reconnects after the live MQTT session drops`() = runBlocking {
        val session = FakeBambuMqttSession()
        val factory = FakeBambuMqttSessionFactory(session)
        val client = DefaultBambuLanClient(
            sessionFactory = factory,
            reconnectSettleDelayMillis = 0,
        )

        client.start(validConfig()) {}
        session.emitDisconnected()
        session.publishResponder = { payload ->
            when {
                payload.contains("\"pushall\"") ->
                    """{"print":{"command":"push_status","msg":0,"gcode_state":"IDLE"}}"""
                payload.contains("\"project_file\"") -> {
                    val sequenceId = JSONObject(payload).getJSONObject("print").getString("sequence_id")
                    """{"print":{"sequence_id":"$sequenceId","command":"project_file","result":"success"}}"""
                }
                else -> null
            }
        }
        client.startProjectFile(
            config = validConfig(),
            remoteName = "cube.3mf",
            plateId = 1,
            amsMapping = listOf(0),
            useAms = true,
            subtaskName = "cube.3mf",
        )

        assertEquals(2, factory.createCount)
        assertEquals("project_file", JSONObject(session.publishedPayload ?: error("Missing payload"))
            .getJSONObject("print").getString("command"))
    }

    @Test
    fun `startProjectFile surfaces the printer rejection reason`() = runBlocking {
        val session = FakeBambuMqttSession()
        val client = DefaultBambuLanClient(FakeBambuMqttSessionFactory(session))

        client.start(validConfig()) {}
        session.publishResponder = { payload ->
            if (!payload.contains("\"project_file\"")) {
                null
            } else {
                val sequenceId = JSONObject(payload).getJSONObject("print").getString("sequence_id")
                """{"print":{"sequence_id":"$sequenceId","command":"project_file","result":"failed","reason":"unsupported print file path or name","return_code":405004002}}"""
            }
        }

        val error = runCatching {
            client.startProjectFile(
                config = validConfig(),
                remoteName = "cube.3mf",
                plateId = 1,
                amsMapping = listOf(0),
                useAms = true,
                subtaskName = "cube.3mf",
            )
        }.exceptionOrNull()

        assertEquals(
            "Printer rejected the print request: unsupported print file path or name (return_code=405004002)",
            error?.message,
        )
    }

    @Test
    fun `topics normalize saved serial to uppercase`() {
        val lowerCaseSerial = validConfig().copy(serial = "a1m123456789012")

        assertEquals("device/A1M123456789012/report", DefaultBambuLanClient.reportTopic(lowerCaseSerial))
        assertEquals("device/A1M123456789012/request", DefaultBambuLanClient.requestTopic(lowerCaseSerial))
    }

    private fun validConfig(): BambuConfig = BambuConfig(
        ip = "192.168.1.88",
        accessCode = "12345678",
        serial = "P1S123ABC",
        model = BambuModel.P1S,
    )

    private fun successfulProjectResponse(): (String) -> String? = { payload ->
        if (!payload.contains("\"project_file\"")) {
            null
        } else {
            val sequenceId = JSONObject(payload).getJSONObject("print").getString("sequence_id")
            """{"print":{"sequence_id":"$sequenceId","command":"project_file","result":"success"}}"""
        }
    }

    private class FakeBambuMqttSessionFactory(
        private val session: FakeBambuMqttSession,
    ) : BambuMqttSessionFactory {
        var createCount = 0
        var serverUri: String? = null
        var username: String? = null
        var password: String? = null
        var socketFactory: SSLSocketFactory? = null

        override fun create(
            serverUri: String,
            username: String,
            password: String,
            socketFactory: SSLSocketFactory,
        ): BambuMqttSession {
            createCount += 1
            this.serverUri = serverUri
            this.username = username
            this.password = password
            this.socketFactory = socketFactory
            return session
        }
    }

    private class FakeBambuMqttSession(
        private val connectFailure: Exception? = null,
        var autoEmitOnPublish: String? = null,
        var publishResponder: ((String) -> String?)? = null,
        private val respondToPushAll: Boolean = true,
    ) : BambuMqttSession {
        var connected = false
        var disconnected = false
        var subscribedTopic: String? = null
        val subscribedTopics = mutableListOf<String>()
        var publishedTopic: String? = null
        var publishedPayload: String? = null
        val publishedPayloads = mutableListOf<String>()
        private val listeners = mutableMapOf<String, (String) -> Unit>()
        private var disconnectedListener: (() -> Unit)? = null

        override suspend fun connect() {
            connectFailure?.let { throw it }
            connected = true
            disconnected = false
        }

        override suspend fun disconnect() {
            disconnected = true
        }

        override suspend fun subscribe(
            topic: String,
            onMessage: (String) -> Unit,
            onDisconnected: () -> Unit,
        ) {
            subscribedTopic = topic
            subscribedTopics += topic
            listeners[topic] = onMessage
            disconnectedListener = onDisconnected
        }

        override suspend fun publish(topic: String, payload: String) {
            publishedTopic = topic
            publishedPayload = payload
            publishedPayloads += payload
            val response = publishResponder?.invoke(payload)
                ?: autoEmitOnPublish
                ?: if (respondToPushAll && payload.contains("\"pushall\"")) {
                    """{"print":{"command":"push_status","msg":0,"gcode_state":"IDLE"}}"""
                } else {
                    null
                }
            response?.let {
                listeners[topic.replace("/request", "/report")]?.invoke(it)
            }
        }

        fun emit(payload: String) {
            listeners.entries.firstOrNull { it.key.endsWith("/report") }?.value?.invoke(payload)
        }

        fun emitDisconnected() {
            connected = false
            disconnectedListener?.invoke()
        }
    }
}
