package com.u1.slicer.printer

import android.util.Log
import com.u1.slicer.data.BambuConfig
import com.u1.slicer.data.BambuModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.eclipse.paho.client.mqttv3.MqttException
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

interface BambuLanClient {
    suspend fun start(config: BambuConfig, onReport: (String) -> Unit)
    suspend fun stop()
    suspend fun testConnection(config: BambuConfig): String?
    suspend fun sendPrintCommand(config: BambuConfig, command: String)
    suspend fun startProjectFile(
        config: BambuConfig,
        remoteName: String,
        plateId: Int,
        amsMapping: List<Int>,
        useAms: Boolean,
        subtaskName: String = remoteName.substringBeforeLast('.', remoteName),
        plateGcodeMd5: String = "",
    )
}

interface BambuMqttSession {
    suspend fun connect()
    suspend fun disconnect()
    suspend fun subscribe(
        topic: String,
        onMessage: (String) -> Unit,
        onDisconnected: () -> Unit,
    )
    suspend fun publish(topic: String, payload: String)
}

interface BambuMqttSessionFactory {
    fun create(
        serverUri: String,
        username: String,
        password: String,
        socketFactory: SSLSocketFactory,
    ): BambuMqttSession
}

class DefaultBambuLanClient(
    private val sessionFactory: BambuMqttSessionFactory = PlaceholderBambuMqttSessionFactory(),
    private val connectionProbeTimeoutMillis: Long = 3_000L,
    private val projectResponseTimeoutMillisOverride: Long? = null,
    private val reconnectSettleDelayMillis: Long = RECONNECT_SETTLE_DELAY_MILLIS,
) : BambuLanClient {
    @Volatile private var session: BambuMqttSession? = null
    @Volatile private var hasLiveSession = false
    @Volatile private var reportListener: ((String) -> Unit)? = null
    @Volatile private var projectSessionReady: CompletableDeferred<Unit>? = null
    @Volatile private var pendingProjectResponse: PendingProjectResponse? = null
    private val sequenceId = AtomicInteger(0)
    private val projectSubmissionId = AtomicInteger(
        (System.currentTimeMillis() % Int.MAX_VALUE).toInt().coerceAtLeast(1),
    )
    private val firmwareVersionsBySerial = mutableMapOf<String, String>()
    private val developerModeBySerial = mutableMapOf<String, Boolean>()
    private val projectStartMutex = Mutex()
    private val lifecycleMutex = Mutex()

    override suspend fun start(config: BambuConfig, onReport: (String) -> Unit) = lifecycleMutex.withLock {
        startSession(config, onReport)
    }

    private suspend fun startSession(config: BambuConfig, onReport: (String) -> Unit) {
        reportListener = onReport
        stopSession()
        Log.i(TAG, "start uri=${serverUri(config)} serial=${redactedSerial(config)}")
        BambuDiagnostics.record("mqtt_started", config, mapOf("stage" to "connect"))
        val created = sessionFactory.create(
            serverUri = serverUri(config),
            username = USERNAME,
            password = config.accessCode,
            socketFactory = trustAllSocketFactory(),
        )
        val sessionReady = CompletableDeferred<Unit>()
        session = created
        projectSessionReady = sessionReady
        val onDisconnected = {
            if (session === created) {
                hasLiveSession = false
                sessionReady.completeExceptionally(
                    IllegalStateException("MQTT connection dropped before the printer session became ready"),
                )
                failPendingProject("MQTT connection dropped while starting the print")
                Log.w(TAG, "disconnected callback")
                BambuDiagnostics.record("mqtt_disconnected", config, mapOf("result" to "unexpected"))
                onReport(DISCONNECTED_REPORT)
            }
        }
        try {
            created.connect()
            Log.i(TAG, "connected")
            BambuDiagnostics.record("mqtt_connected", config)
            created.subscribe(
                topic = reportTopic(config),
                onMessage = {
                    if (session === created) {
                        Log.i(TAG, "report received bytes=${it.length}")
                        recordFirmwareVersion(config, it)
                        recordDeveloperMode(config, it)
                        logPrinterState(it)
                        if (isProjectSessionReadyReport(it)) sessionReady.complete(Unit)
                        if (pendingProjectResponse != null) Log.i(TAG, "report while awaiting project_file bytes=${it.length}")
                        deliverProjectResponse(it)
                        onReport(it)
                    }
                },
                onDisconnected = onDisconnected,
            )
            Log.i(TAG, "subscribed topic=${reportTopic(config)}")
            BambuDiagnostics.record("mqtt_subscribed", config)

            created.publish(requestTopic(config), pushAllPayload())
            Log.i(TAG, "pushall published topic=${requestTopic(config)}")
            BambuDiagnostics.record("mqtt_pushall_queued", config)
            hasLiveSession = true
        } catch (error: Exception) {
            hasLiveSession = false
            sessionReady.completeExceptionally(error)
            if (session === created) {
                session = null
                projectSessionReady = null
            }
            runCatching { created.disconnect() }
            BambuDiagnostics.record("mqtt_failed", config, BambuDiagnostics.errorDetails(error, config))
            throw error
        }
    }

    override suspend fun stop() = lifecycleMutex.withLock {
        reportListener = null
        stopSession()
    }

    private suspend fun stopSession() {
        hasLiveSession = false
        failPendingProject("Bambu printer connection closed")
        val closingSession = session
        session = null
        projectSessionReady?.completeExceptionally(IllegalStateException("Bambu printer connection closed"))
        projectSessionReady = null
        runCatching { closingSession?.disconnect() }
            .onFailure { error ->
                Log.w(TAG, "disconnect failed: ${error.javaClass.simpleName}: ${error.message}")
            }
    }

    override suspend fun testConnection(config: BambuConfig): String? {
        if (hasLiveSession) {
            Log.i(TAG, "test using active MQTT session")
            return when {
                !awaitSessionReady() ->
                    "Connected, but the printer did not answer on its serial-specific MQTT topic. Check the Bambu serial."
                developerMode(config) == false -> developerModeDisabledMessage()
                else -> null
            }
        }
        val created = sessionFactory.create(
            serverUri = serverUri(config),
            username = USERNAME,
            password = config.accessCode,
            socketFactory = trustAllSocketFactory(),
        )
        return try {
            BambuDiagnostics.record("mqtt_probe_started", config)
            Log.i(TAG, "test uri=${serverUri(config)} serial=${redactedSerial(config)}")
            created.connect()
            Log.i(TAG, "test connected")
            val reportReceived = CompletableDeferred<String>()
            created.subscribe(
                topic = reportTopic(config),
                onMessage = {
                    if (!reportReceived.isCompleted) {
                        reportReceived.complete(it)
                    }
                },
                onDisconnected = {
                    if (!reportReceived.isCompleted) {
                        reportReceived.complete(DISCONNECTED_SENTINEL)
                    }
                },
            )
            created.publish(requestTopic(config), pushAllPayload())
            val result = when (val report = withTimeoutOrNull(connectionProbeTimeoutMillis) { reportReceived.await() }) {
                null -> "Connected, but the printer did not answer on its serial-specific MQTT topic. Check the Bambu serial."
                DISCONNECTED_SENTINEL ->
                    "Connection dropped while waiting for printer status. Check the Bambu serial and LAN stability."
                else -> {
                    if (parseDeveloperMode(report) == false) {
                        developerModeDisabledMessage()
                    } else {
                        null
                    }
                }
            }
            BambuDiagnostics.record(
                "mqtt_probe_finished",
                config,
                mapOf("result" to if (result == null) "success" else "failed", "errorMessage" to result),
            )
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "test failed: ${e.javaClass.simpleName}: ${e.message}")
            BambuDiagnostics.record("mqtt_probe_failed", config, BambuDiagnostics.errorDetails(e, config))
            mapConnectionError(e)
        } finally {
            runCatching { created.disconnect() }
        }
    }

    override suspend fun sendPrintCommand(config: BambuConfig, command: String) {
        val activeSession = requireLiveSession(config).session
        val payload = printCommandPayload(nextSequenceId(), command)
        Log.i(TAG, "command=$command topic=${requestTopic(config)}")
        activeSession.publish(requestTopic(config), payload)
        Log.i(TAG, "command queued=$command")
        BambuDiagnostics.record("mqtt_job_command_queued", config, mapOf("stage" to command))
    }

    override suspend fun startProjectFile(
        config: BambuConfig,
        remoteName: String,
        plateId: Int,
        amsMapping: List<Int>,
        useAms: Boolean,
        subtaskName: String,
        plateGcodeMd5: String,
    ) = projectStartMutex.withLock {
        // Preserve the healthy session that received status. Reconnecting at
        // dispatch time can leave A-series
        // firmware with a fresh telemetry channel that is not ready for writes.
        val activeSession = requireLiveSession(config)
        if (!awaitSessionReady()) {
            throw IllegalStateException(
                "Connected to MQTT, but the printer did not provide a ready status session. The print was not started.",
            )
        }
        if (activeSession.reconnected && reconnectSettleDelayMillis > 0) {
            Log.i(TAG, "waiting ${reconnectSettleDelayMillis}ms for reconnected printer session to settle")
            delay(reconnectSettleDelayMillis)
        }
        val firmwareVersion = firmwareVersion(config)
        val command = "project_file"
        val submissionId = nextProjectSubmissionId()
        // A-series firmware can de-duplicate commands by sequence_id. Reusing a
        // fixed value after an earlier rejection makes a corrected retry vanish.
        val sequenceId = submissionId.toInt()
        val payload = projectFileCommandPayload(
            sequenceId = sequenceId,
            submissionId = submissionId,
            remoteName = remoteName,
            plateId = plateId,
            amsMapping = amsMapping,
            useAms = useAms,
            subtaskName = subtaskName,
            model = config.model,
            firmwareVersion = firmwareVersion,
            plateGcodeMd5 = plateGcodeMd5,
        )
        val pending = PendingProjectResponse(
            sequenceId = sequenceId.toString(),
            submissionId = submissionId,
            remoteName = remoteName,
            model = config.model,
            command = command,
            reply = CompletableDeferred(),
        )
        pendingProjectResponse = pending
        Log.i(
            TAG,
            "$command remote=$remoteName plate=$plateId firmware=${firmwareVersion ?: "unknown"} " +
                "plateMd5Available=${plateGcodeMd5.isNotBlank()} " +
                "topic=${requestTopic(config)} payload=$payload",
        )
        try {
            BambuDiagnostics.record(
                "mqtt_project_dispatch",
                config,
                mapOf(
                    "projectId" to BambuDiagnostics.projectId(remoteName),
                    "plateId" to plateId,
                    "firmwareVersion" to firmwareVersion,
                    "mapping" to amsMapping,
                    "useAms" to useAms,
                    "reconnected" to activeSession.reconnected,
                ),
            )
            activeSession.session.publish(requestTopic(config), payload)
            Log.i(TAG, "$command queued remote=$remoteName")
            val response = awaitProjectResponse(config.model, pending)
            response ?: throw IllegalStateException(when {
                pending.sawSubmissionEvidence ->
                    "The printer received the uploaded project but did not enter print preparation. " +
                        "The app did not retry because that could start the same print twice."
                else ->
                    "Printer allowed monitoring and upload but did not acknowledge the print request. " +
                        "The uploaded 3MF was not started."
            })
            if (!response.isSuccess) throw IllegalStateException(response.failureMessage())
            Log.i(TAG, "$command accepted remote=$remoteName")
            BambuDiagnostics.record(
                "mqtt_project_response",
                config,
                mapOf("result" to "accepted", "projectId" to BambuDiagnostics.projectId(remoteName)),
            )
        } catch (error: Exception) {
            BambuDiagnostics.record(
                "mqtt_project_response",
                config,
                mapOf(
                    "result" to if (error.message.orEmpty().contains("did not", ignoreCase = true)) {
                        "timeout_or_unacknowledged_no_retry"
                    } else {
                        "rejected_no_retry"
                    },
                    "projectId" to BambuDiagnostics.projectId(remoteName),
                ) + BambuDiagnostics.errorDetails(error, config),
            )
            throw error
        } finally {
            if (pendingProjectResponse === pending) pendingProjectResponse = null
        }
        Unit
    }

    private suspend fun awaitProjectResponse(
        model: BambuModel,
        pending: PendingProjectResponse,
    ): BambuProjectResponse? = withTimeoutOrNull(
        projectResponseTimeoutMillisOverride ?: projectResponseTimeoutMillis(model),
    ) {
        pending.reply.await()
    }

    companion object {
        private const val TAG = "BambuLanClient"
        private const val USERNAME = "bblp"
        private const val PROJECT_RESPONSE_TIMEOUT_MILLIS = 60_000L
        private const val A_SERIES_PROJECT_RESPONSE_TIMEOUT_MILLIS = 30_000L
        private const val H2_PROJECT_RESPONSE_TIMEOUT_MILLIS = 240_000L
        private const val RECONNECT_SETTLE_DELAY_MILLIS = 1_500L
        private const val DEVELOPER_MODE_REQUIRED_BIT = 0x20000000L
        private const val DISCONNECTED_SENTINEL = "__disconnected__"

        private fun projectResponseTimeoutMillis(model: BambuModel): Long = when (model) {
            BambuModel.A1, BambuModel.A1_MINI ->
                A_SERIES_PROJECT_RESPONSE_TIMEOUT_MILLIS
            BambuModel.H2D -> H2_PROJECT_RESPONSE_TIMEOUT_MILLIS
            else -> PROJECT_RESPONSE_TIMEOUT_MILLIS
        }

        fun serverUri(config: BambuConfig): String = "ssl://${config.ip}:8883"

        fun reportTopic(config: BambuConfig): String = "device/${canonicalSerial(config)}/report"

        fun requestTopic(config: BambuConfig): String = "device/${canonicalSerial(config)}/request"

        fun pushAllPayload(): String = """{"pushing":{"command":"pushall"}}"""

        fun printCommandPayload(sequenceId: Int, command: String): String =
            """{"print":{"sequence_id":"$sequenceId","command":"$command","param":""}}"""

        @Suppress("UNUSED_PARAMETER")
        fun projectFileCommandPayload(
            sequenceId: Int,
            submissionId: String = sequenceId.toString(),
            remoteName: String,
            plateId: Int,
            amsMapping: List<Int>,
            useAms: Boolean,
            subtaskName: String,
            model: BambuModel = BambuModel.P1S,
            firmwareVersion: String? = null,
            plateGcodeMd5: String = "",
        ): String {
            val normalizedSubtask = remoteName
                .removeSuffix(".3mf")
                .removeSuffix(".gcode")
                .ifBlank { subtaskName }
            val ams = buildAmsPayload(amsMapping, useAms, model)
            if (usesLegacyASeriesProjectPayload(model, firmwareVersion)) {
                return legacyASeriesProjectFileCommandPayload(
                    sequenceId = sequenceId,
                    remoteName = remoteName,
                    plateId = plateId,
                    ams = ams,
                    model = model,
                )
            }
            val print = JSONObject()
                    .put("sequence_id", sequenceId.toString())
                    .put("command", "project_file")
                    .put("param", "Metadata/plate_${plateId}.gcode")
                    .put("url", projectFileUrl(model, remoteName))
                    .put("file", remoteName)
                    // Bambuddy deliberately leaves this empty. Supplying the
                    // plate digest can enable a different validation path on
                    // firmware that otherwise accepts the same local project.
                    .put("md5", "")
                    .put("bed_type", "auto")
                    .put("timelapse", false)
                    // BambuStudio's tri-state "auto" is encoded as a false
                    // boolean plus companion value 2. This lets firmware skip
                    // a recently completed calibration instead of forcing it
                    // on every print.
                    .put("bed_leveling", false)
                    .put("auto_bed_leveling", 2)
                    .put("flow_cali", false)
                    .put("vibration_cali", true)
                    .put("layer_inspect", false)
                    .put("use_ams", ams.useAms)
                    .put("cfg", "0")
                    .put("extrude_cali_flag", 2)
                    .put("extrude_cali_manual_mode", 0)
                    // The offset pass has no meaning on a single-nozzle
                    // printer. H2D uses the same automatic tri-state default
                    // as current BambuStudio/Bambuddy.
                    .put("nozzle_offset_cali", if (model == BambuModel.H2D) 2 else 0)
                    .put("subtask_name", normalizedSubtask)
                    .put("profile_id", "0")
                    .put("project_id", submissionId)
                    .put("subtask_id", submissionId)
                    .put("task_id", submissionId)
                    .put("ams_mapping", JSONArray(ams.flatMapping))
            print.put("ams_mapping2", JSONArray(ams.detailedMapping.map { entry ->
                JSONObject()
                    .put("ams_id", entry.amsId)
                    .put("slot_id", entry.slotId)
            }))
            return JSONObject().put("print", print).toString()
        }

        private fun legacyASeriesProjectFileCommandPayload(
            sequenceId: Int,
            remoteName: String,
            plateId: Int,
            ams: AmsRoutingPayload,
            model: BambuModel,
        ): String = JSONObject().put(
            "print",
            JSONObject()
                .put("sequence_id", sequenceId.toString())
                .put("command", "project_file")
                .put("param", "Metadata/plate_${plateId}.gcode")
                .put("subtask_name", remoteName)
                .put("plate_idx", (plateId - 1).coerceAtLeast(0))
                .put("url", projectFileUrl(model, remoteName))
                .put("timelapse", false)
                .put("bed_leveling", true)
                .put("flow_cali", false)
                .put("vibration_cali", false)
                .put("layer_inspect", false)
                .put("use_ams", ams.useAms)
                .put("ams_mapping", JSONArray(ams.flatMapping)),
        ).toString()

        /**
         * H2D executes projects from the FTPS root. Earlier printer families
         * use the on-printer cache path and address it through the local-file
         * URL expected by their firmware.
         */
        internal fun projectUploadPath(model: BambuModel, remoteName: String): String {
            requireExecutableProjectName(model, remoteName)
            val name = remoteName.trimStart('/')
            return if (model == BambuModel.H2D) "/$name" else "/cache/$name"
        }

        internal fun projectFileUrl(
            model: BambuModel,
            remoteName: String,
        ): String {
            requireExecutableProjectName(model, remoteName)
            val name = remoteName.trimStart('/')
            return if (model == BambuModel.H2D) {
                "ftp:///$name"
            } else {
                "file:///sdcard/cache/$name"
            }
        }

        internal fun usesLegacyASeriesProjectPayload(
            model: BambuModel,
            firmwareVersion: String?,
        ): Boolean {
            if (!isASeries(model)) return false
            val parts = firmwareVersion
                ?.split('.')
                ?.mapNotNull(String::toIntOrNull)
                ?: return false
            if (parts.size < 2) return false
            return parts[0] < 1 || (parts[0] == 1 && parts[1] <= 4)
        }

        internal fun requestCommand(payload: String): String = runCatching {
            val root = JSONObject(payload)
            val category = root.keys().asSequence().firstOrNull() ?: return "unknown"
            root.optJSONObject(category)?.optString("command").orEmpty().ifBlank { "unknown" }
        }.getOrDefault("unknown")

        internal fun isProjectSessionReadyReport(json: String): Boolean = runCatching {
            val print = JSONObject(json).optJSONObject("print") ?: return false
            print.optInt("msg", -1) == 0 || print.optString("gcode_state").isNotBlank()
        }.getOrDefault(false)

        private fun isASeries(model: BambuModel): Boolean =
            model == BambuModel.A1 || model == BambuModel.A1_MINI

        private fun requireExecutableProjectName(model: BambuModel, remoteName: String) {
            if (isASeries(model)) {
                require(remoteName.endsWith(".gcode.3mf", ignoreCase = true)) {
                    "A-series executable projects must use the .gcode.3mf filename suffix"
                }
            }
        }

        internal fun parseFirmwareVersion(json: String): String? = runCatching {
            val versions = JSONObject(json)
                .optJSONObject("print")
                ?.optJSONObject("upgrade_state")
                ?.optJSONArray("new_ver_list")
                ?: return null
            for (index in 0 until versions.length()) {
                val version = versions.optJSONObject(index) ?: continue
                if (version.optString("name") == "ota") {
                    return version.optString("cur_ver").takeIf { it.isNotBlank() }
                }
            }
            null
        }.getOrNull()

        /**
         * New secured firmware advertises command authorization in bit 29 of
         * the `fun` status field. Older firmware and some A/P releases omit the
         * field; omission deliberately remains unknown/allowed.
         */
        internal fun parseDeveloperMode(json: String): Boolean? = runCatching {
            val print = JSONObject(json).optJSONObject("print") ?: return null
            if (!print.has("fun") || print.isNull("fun")) return null
            val raw = print.opt("fun")?.toString()?.toLongOrNull() ?: return null
            (raw and DEVELOPER_MODE_REQUIRED_BIT) == 0L
        }.getOrNull()

        private fun developerModeDisabledMessage(): String =
            "LAN connection succeeded, but Developer Mode is disabled. Enable Developer Mode in the printer's " +
                "LAN-only settings, then refresh the access code."

        internal const val DISCONNECTED_REPORT = """{"print":{"gcode_state":"DISCONNECTED"}}"""

        internal fun parseProjectResponse(json: String, expectedCommand: String = "project_file"): BambuProjectResponse? {
            return try {
                val print = JSONObject(json).optJSONObject("print") ?: return null
                if (print.optString("command") != expectedCommand) return null
                val sequenceId = print.optString("sequence_id")
                val result = print.optString("result")
                if (sequenceId.isBlank() || result.isBlank()) return null
                val diagnostics = listOf("return_code", "code", "error_code", "error", "errno")
                    .mapNotNull { key ->
                        if (!print.has(key) || print.isNull(key)) return@mapNotNull null
                        print.opt(key)?.toString()?.takeIf { it.isNotBlank() }?.let { "$key=$it" }
                    }
                BambuProjectResponse(
                    sequenceId = sequenceId,
                    result = result,
                    reason = print.optString("reason"),
                    diagnostics = diagnostics,
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun canonicalSerial(config: BambuConfig): String =
            config.serial.trim().uppercase(Locale.ROOT)

        private fun redactedSerial(config: BambuConfig): String {
            val serial = canonicalSerial(config)
            return if (serial.length <= 4) "len=${serial.length}" else "len=${serial.length} suffix=${serial.takeLast(4)}"
        }

        internal fun trustAllSocketFactory(): SSLSocketFactory {
            val trustAll = arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }
            )
            return SSLContext.getInstance("TLS").apply {
                init(null, trustAll, SecureRandom())
            }.socketFactory
        }

        private data class AmsRoutingPayload(
            val flatMapping: List<Int>,
            val detailedMapping: List<AmsMappingEntry>,
            val useAms: Boolean,
        )

        private data class AmsMappingEntry(
            val amsId: Int,
            val slotId: Int,
        )

        private fun buildAmsPayload(
            amsMapping: List<Int>,
            useAms: Boolean,
            model: BambuModel,
        ): AmsRoutingPayload {
            val detailed = mutableListOf<AmsMappingEntry>()
            val flat = mutableListOf<Int>()
            val isDualNozzle = model == BambuModel.H2D
            // The mapping is indexed by the 3MF's filament slots. Bambu Studio
            // and Bambu Buddy send the first project filament in element zero.
            val requestedMapping = amsMapping
            requestedMapping.forEach { trayIdRaw ->
                when {
                    trayIdRaw < 0 -> {
                        flat += -1
                        detailed += AmsMappingEntry(255, 255)
                    }
                    trayIdRaw >= 254 -> {
                        flat += -1
                        detailed += AmsMappingEntry(
                            amsId = if (isDualNozzle) trayIdRaw else 255,
                            slotId = 0,
                        )
                    }
                    trayIdRaw >= 128 -> {
                        flat += trayIdRaw
                        detailed += AmsMappingEntry(trayIdRaw, 0)
                    }
                    else -> {
                        flat += trayIdRaw
                        detailed += AmsMappingEntry(trayIdRaw / 4, trayIdRaw % 4)
                    }
                }
            }
            val hasRealTray = requestedMapping.any { it in 0..253 }
            val allExplicitlyExternal = requestedMapping.isNotEmpty() &&
                requestedMapping.all { it >= 254 }
            val effectiveUseAms = when {
                isDualNozzle -> useAms
                hasRealTray -> true
                allExplicitlyExternal -> false
                // -1 means unresolved, not external. Preserve the caller's AMS
                // choice so firmware can still apply its own automatic route.
                else -> useAms
            }
            return AmsRoutingPayload(
                flatMapping = flat,
                detailedMapping = detailed,
                useAms = effectiveUseAms,
            )
        }

        internal fun mapConnectionError(error: Exception): String = when (error) {
            is java.net.ConnectException ->
                "Connection refused - check IP, access code, and LAN reachability"
            is java.net.UnknownHostException ->
                "Unknown host - check the Bambu printer IP"
            is java.net.SocketTimeoutException ->
                "Timed out - check the printer is on and reachable"
            is MqttException -> when (error.reasonCode) {
                MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt() ->
                    "Authentication failed - check the Bambu serial and access code"
                MqttException.REASON_CODE_NOT_AUTHORIZED.toInt() ->
                    "Not authorized - check the Bambu serial and access code"
                MqttException.REASON_CODE_INVALID_PROTOCOL_VERSION.toInt() ->
                    "MQTT protocol mismatch - retry after updating the app"
                else -> error.message ?: "Bambu LAN connection failed"
            }
            else -> error.message ?: "Bambu LAN connection failed"
        }
    }

    private fun nextSequenceId(): Int {
        return sequenceId.updateAndGet { current ->
            if (current == Int.MAX_VALUE) 1 else current + 1
        }
    }

    private fun nextProjectSubmissionId(): String = projectSubmissionId.updateAndGet { current ->
        if (current == Int.MAX_VALUE) 1 else current + 1
    }.toString()

    private fun recordFirmwareVersion(config: BambuConfig, json: String) {
        val version = parseFirmwareVersion(json) ?: return
        val changed = synchronized(firmwareVersionsBySerial) {
            firmwareVersionsBySerial.put(canonicalSerial(config), version) != version
        }
        Log.i(TAG, "firmware version=$version serial=${redactedSerial(config)}")
        if (changed) BambuDiagnostics.record(
            "firmware_observed",
            config,
            mapOf("firmwareVersion" to version),
        )
    }

    private fun recordDeveloperMode(config: BambuConfig, json: String) {
        val enabled = parseDeveloperMode(json) ?: return
        val changed = synchronized(developerModeBySerial) {
            developerModeBySerial.put(canonicalSerial(config), enabled) != enabled
        }
        Log.i(TAG, "developer mode enabled=$enabled serial=${redactedSerial(config)}")
        if (changed) BambuDiagnostics.record(
            "developer_mode_observed",
            config,
            mapOf("developerMode" to enabled),
        )
    }

    private fun developerMode(config: BambuConfig): Boolean? =
        synchronized(developerModeBySerial) {
            developerModeBySerial[canonicalSerial(config)]
        }

    private fun firmwareVersion(config: BambuConfig): String? =
        synchronized(firmwareVersionsBySerial) {
            firmwareVersionsBySerial[canonicalSerial(config)]
        }

    private suspend fun requireLiveSession(config: BambuConfig): ActiveBambuSession {
        if (hasLiveSession) {
            return ActiveBambuSession(
                session = session ?: error("Bambu printer session is unavailable"),
                reconnected = false,
            )
        }
        return lifecycleMutex.withLock {
            if (hasLiveSession) {
                return@withLock ActiveBambuSession(
                    session = session ?: error("Bambu printer session is unavailable"),
                    reconnected = false,
                )
            }
            val listener = reportListener
                ?: throw IllegalStateException("Bambu printer connection was lost. Return to the printer screen and retry.")
            Log.i(TAG, "reconnecting before printer command")
            startSession(config, listener)
            ActiveBambuSession(
                session = session ?: error("Bambu printer could not reconnect"),
                reconnected = true,
            )
        }
    }

    private data class ActiveBambuSession(
        val session: BambuMqttSession,
        val reconnected: Boolean,
    )

    private suspend fun awaitSessionReady(): Boolean {
        val ready = projectSessionReady ?: return false
        return try {
            withTimeoutOrNull(connectionProbeTimeoutMillis) { ready.await() } != null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private fun deliverProjectResponse(json: String) {
        val pending = pendingProjectResponse ?: return
        val response = parseProjectResponse(json, pending.command)
        if (response != null) {
            Log.i(
                TAG,
                "${pending.command} response result=${response.result} " +
                    "reason=${response.reason.ifBlank { "none" }} " +
                    "diagnostics=${response.diagnostics.ifEmpty { listOf("none") }}",
            )
            if (pending.sequenceId == response.sequenceId) pending.reply.complete(response)
            return
        }

        if (projectShowsSubmissionEvidence(json, pending)) {
            pending.sawSubmissionEvidence = true
        }

        // Some firmware accepts a project without echoing a command reply. A
        // matching submission followed by preparation is authoritative and
        // avoids reporting a false failure after the printer has started.
        if (pending.sawSubmissionEvidence && projectHasStarted(json)) {
            Log.i(TAG, "${pending.command} accepted via matching printer state")
            pending.reply.complete(BambuProjectResponse(pending.sequenceId, "success", ""))
        }
    }

    private fun failPendingProject(reason: String) {
        val pending = pendingProjectResponse ?: return
        pending.reply.complete(
            BambuProjectResponse(
                sequenceId = pending.sequenceId,
                result = "failed",
                reason = reason,
            ),
        )
    }

    private data class PendingProjectResponse(
        val sequenceId: String,
        val submissionId: String,
        val remoteName: String,
        val model: BambuModel,
        val command: String,
        val reply: CompletableDeferred<BambuProjectResponse>,
    ) {
        @Volatile var sawSubmissionEvidence: Boolean = false
    }

    private fun projectShowsSubmissionEvidence(json: String, pending: PendingProjectResponse): Boolean = runCatching {
        val print = JSONObject(json).optJSONObject("print") ?: return false
        val matchingId = pending.submissionId.isNotBlank() && pending.submissionId != "0" &&
            listOf("project_id", "task_id", "subtask_id")
                .any { key -> print.optString(key) == pending.submissionId }
        val matchingFile = listOf("gcode_file", "file")
            .map { key -> print.optString(key).substringAfterLast('/') }
            .any { reportedName -> reportedName == pending.remoteName }
        val normalizedName = pending.remoteName
            .removeSuffix(".3mf")
            .removeSuffix(".gcode")
        val matchingSubtask = print.optString("subtask_name") in setOf(
            pending.remoteName,
            normalizedName,
        )
        matchingId || matchingFile || matchingSubtask
    }.getOrDefault(false)

    private fun projectHasStarted(json: String): Boolean = runCatching {
        val state = JSONObject(json)
            .optJSONObject("print")
            ?.optString("gcode_state")
            ?.uppercase(Locale.ROOT)
        state == "PREPARE" || state == "RUNNING"
    }.getOrDefault(false)

    private fun logPrinterState(json: String) {
        val print = runCatching { JSONObject(json).optJSONObject("print") }.getOrNull() ?: return
        if (print.optString("gcode_state").isNotBlank()) {
            Log.i(
                TAG,
                "printer status state=${print.optString("gcode_state")} percent=${print.optInt("mc_percent", -1)}",
            )
        }
    }

}

internal data class BambuProjectResponse(
    val sequenceId: String,
    val result: String,
    val reason: String,
    val diagnostics: List<String> = emptyList(),
) {
    val isSuccess: Boolean get() = result.equals("success", ignoreCase = true)

    fun failureMessage(): String {
        if (reason.contains("verify failed", ignoreCase = true)) {
            return "Printer rejected LAN commands. Enable Developer Mode in the printer's LAN-only settings, " +
                "then refresh the access code and retry."
        }
        return buildString {
        append("Printer rejected the print request")
        if (reason.isNotBlank()) append(": ").append(reason)
        if (diagnostics.isNotEmpty()) append(" (").append(diagnostics.joinToString()).append(')')
        }
    }
}

private class PlaceholderBambuMqttSessionFactory : BambuMqttSessionFactory {
    override fun create(
        serverUri: String,
        username: String,
        password: String,
        socketFactory: SSLSocketFactory,
    ): BambuMqttSession = object : BambuMqttSession {
        override suspend fun connect() {
            throw UnsupportedOperationException("Bambu LAN live connection is not implemented yet")
        }

        override suspend fun disconnect() {
        }

        override suspend fun subscribe(
            topic: String,
            onMessage: (String) -> Unit,
            onDisconnected: () -> Unit,
        ) {
        }

        override suspend fun publish(topic: String, payload: String) {
        }
    }
}
