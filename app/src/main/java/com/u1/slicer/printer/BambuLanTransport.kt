package com.u1.slicer.printer

import android.graphics.Bitmap
import android.util.Log
import com.u1.slicer.data.BambuConfig
import com.u1.slicer.network.FilamentRouting
import com.u1.slicer.network.FilamentSlot
import com.u1.slicer.network.NozzleSide
import com.u1.slicer.network.PrinterStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File

class BambuLanTransport(
    val config: BambuConfig,
    private val client: BambuLanClient = DefaultBambuLanClient(),
    private val uploadClient: BambuFileUploadClient = DefaultBambuFileUploadClient(),
    private val cameraClient: BambuCameraClient = DefaultBambuCameraClient(),
    private val cameraRetryDelayMs: Long = CAMERA_RETRY_DELAY_MS,
    private val connectionRetryDelayMs: Long = CONNECTION_RETRY_DELAY_MS,
) : PrinterTransport {
    override val capabilities = PrinterTransportCapabilities(
        supportsCamera = cameraClient.supports(config.model),
        supportsFilamentSync = true,
        reportsFilamentSlotsWithStatus = true,
        supportsUpload = true,
        supportsStartProject = true,
        supportsPause = true,
        supportsResume = true,
        supportsCancel = true,
    )

    private val _status = MutableStateFlow(PrinterStatus(state = "disconnected", progress = 0f))
    override val status: Flow<PrinterStatus> = _status.asStateFlow()

    private val _filamentSlots = MutableStateFlow<List<FilamentSlot>>(emptyList())
    override val filamentSlots: Flow<List<FilamentSlot>> = _filamentSlots.asStateFlow()

    private val _cameraState = MutableStateFlow<CameraState>(CameraState.Disabled)
    override val cameraState: Flow<CameraState> = _cameraState.asStateFlow()
    private var cameraJob: Job? = null
    private var reconnectJob: Job? = null
    private var transportScope: CoroutineScope? = null
    @Volatile private var stopping = false
    private val uploadedProjects = mutableMapOf<String, File>()
    private var lastDiagnosticStatusFingerprint: String? = null

    override suspend fun start(scope: CoroutineScope) {
        stopping = false
        transportScope = scope
        startCamera(scope)
        BambuDiagnostics.record(
            "connection_started",
            config,
            mapOf("cameraSupported" to capabilities.supportsCamera),
        )
        runCatching {
            Log.i(TAG, "start model=${config.model} ip=${config.ip}")
            client.start(config, ::applyPushReport)
            BambuDiagnostics.record("connection_ready", config)
        }.onFailure {
            Log.w(TAG, "start failed: ${it.javaClass.simpleName}: ${it.message}")
            _status.value = PrinterStatus(state = "disconnected", progress = 0f)
            _filamentSlots.value = emptyList()
            BambuDiagnostics.record("connection_failed", config, BambuDiagnostics.errorDetails(it, config))
            scheduleReconnect()
        }
    }

    override suspend fun stop() {
        stopping = true
        transportScope = null
        reconnectJob?.cancelAndJoin()
        reconnectJob = null
        BambuDiagnostics.record("connection_stopping", config)
        cameraJob?.cancel()
        cameraClient.stop()
        cameraJob?.cancelAndJoin()
        cameraJob = null
        client.stop()
        _status.value = PrinterStatus(state = "disconnected", progress = 0f)
        _filamentSlots.value = emptyList()
        _cameraState.value = CameraState.Disabled
        BambuDiagnostics.record("connection_stopped", config)
    }

    override suspend fun testConnection(): String? {
        BambuDiagnostics.record("connection_test_started", config)
        val connectionError = when {
            config.accessCode.isBlank() -> "Bambu access code is required"
            config.serial.isBlank() -> "Bambu serial is required"
            config.ip.isBlank() -> "Bambu printer IP is required"
            else -> client.testConnection(config)
        }
        if (connectionError != null) {
            BambuDiagnostics.record(
                "connection_test_finished",
                config,
                mapOf("result" to "failed", "errorMessage" to BambuDiagnostics.redact(connectionError, config)),
            )
            return connectionError
        }
        BambuDiagnostics.record("connection_test_finished", config, mapOf("result" to "success"))
        return null
    }

    override suspend fun queryFilamentSlots(): List<FilamentSlot> = _filamentSlots.value

    override suspend fun uploadJob(file: File, remoteName: String): TransportCommandResult = try {
        BambuDiagnostics.record(
            "upload_started",
            config,
            mapOf("projectId" to BambuDiagnostics.projectId(remoteName), "bytes" to file.length()),
        )
        uploadClient.upload(config, file, DefaultBambuLanClient.projectUploadPath(config.model, remoteName))
        uploadedProjects[remoteName] = file
        BambuDiagnostics.record(
            "upload_finished",
            config,
            mapOf("result" to "success", "projectId" to BambuDiagnostics.projectId(remoteName), "bytes" to file.length()),
        )
        TransportCommandResult.Success
    } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
        BambuDiagnostics.record(
            "upload_finished",
            config,
            mapOf("result" to "failed", "projectId" to BambuDiagnostics.projectId(remoteName)) +
                BambuDiagnostics.errorDetails(timeout, config),
        )
        TransportCommandResult.Failure(BambuFtpsUploadFailure.describe(timeout))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        BambuDiagnostics.record(
            "upload_finished",
            config,
            mapOf("result" to "failed", "projectId" to BambuDiagnostics.projectId(remoteName)) +
                BambuDiagnostics.errorDetails(e, config),
        )
        TransportCommandResult.Failure(BambuFtpsUploadFailure.describe(e))
    }

    override suspend fun validateStartProject(): TransportCommandResult =
        if (_status.value.isPrinting || _status.value.state == "paused") {
            TransportCommandResult.Failure("The printer is already running a job")
        } else {
            TransportCommandResult.Success
        }

    override suspend fun startProject(
        remoteName: String,
        plateId: Int,
        amsMapping: List<Int>,
        useAms: Boolean,
        subtaskName: String,
    ): TransportCommandResult {
        val projectId = BambuDiagnostics.projectId(remoteName)
        BambuDiagnostics.record(
            "project_preflight_started",
            config,
            mapOf(
                "projectId" to projectId,
                "plateId" to plateId,
                "mapping" to amsMapping,
                "useAms" to useAms,
                "slotCount" to _filamentSlots.value.size,
                "ftsInstalled" to _status.value.filamentTrackSwitch.installed,
            ),
        )
        val validation = validateStartProject()
        if (validation !is TransportCommandResult.Success) {
            BambuDiagnostics.record(
                "project_preflight_finished",
                config,
                mapOf("result" to "failed", "projectId" to projectId, "stage" to "busy_guard"),
            )
            return validation
        }
        val project = uploadedProjects[remoteName]
            ?: return TransportCommandResult.Failure(
                "The uploaded Bambu project is unavailable. Upload it again before starting the print.",
            ).also {
                BambuDiagnostics.record(
                    "project_preflight_finished",
                    config,
                    mapOf("result" to "failed", "projectId" to projectId, "stage" to "local_file"),
                )
            }
        val preflight = BambuProjectFileInspector.validateExecutableProject(
            projectFile = project,
            plateId = plateId,
            model = config.model,
            amsMapping = amsMapping,
            filamentSlots = _filamentSlots.value,
            filamentTrackSwitchInstalled = _status.value.filamentTrackSwitch.installed,
            installedNozzles = _status.value.nozzles,
        ).getOrElse { error ->
            BambuDiagnostics.record(
                "project_preflight_finished",
                config,
                mapOf("result" to "failed", "projectId" to projectId, "stage" to "archive_validation") +
                    BambuDiagnostics.errorDetails(error, config),
            )
            return TransportCommandResult.Failure(error.message ?: "The uploaded Bambu project is invalid")
        }
        BambuDiagnostics.record(
            "project_preflight_finished",
            config,
            mapOf(
                "result" to "success",
                "projectId" to projectId,
                "plateId" to plateId,
                "projectFilamentCount" to preflight.projectFilamentCount,
                "mapping" to preflight.amsMapping,
                "projectNozzleDiameters" to preflight.projectNozzleDiameters,
                "projectNozzleTypes" to preflight.projectNozzleTypes,
                "installedNozzles" to _status.value.nozzles.map { nozzle ->
                    mapOf("index" to nozzle.index, "diameter" to nozzle.diameter, "type" to nozzle.type)
                },
                "checksumPresent" to preflight.plateGcodeMd5.isNotBlank(),
            ),
        )
        return try {
            BambuDiagnostics.record(
                "project_command_started",
                config,
                mapOf("projectId" to projectId, "plateId" to plateId, "useAms" to useAms),
            )
            client.startProjectFile(
                config = config,
                remoteName = remoteName,
                plateId = plateId,
                amsMapping = preflight.amsMapping,
                useAms = useAms,
                subtaskName = subtaskName,
                plateGcodeMd5 = preflight.plateGcodeMd5,
            )
            BambuDiagnostics.record(
                "project_command_finished",
                config,
                mapOf("result" to "accepted", "projectId" to projectId, "plateId" to plateId),
            )
            TransportCommandResult.Success
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            BambuDiagnostics.record(
                "project_command_finished",
                config,
                mapOf("result" to "timeout_no_retry", "projectId" to projectId, "plateId" to plateId) +
                    BambuDiagnostics.errorDetails(timeout, config),
            )
            TransportCommandResult.Failure(
                "Bambu print request timed out. The 3MF was uploaded, but no print was started.",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            BambuDiagnostics.record(
                "project_command_finished",
                config,
                mapOf("result" to "failed_no_retry", "projectId" to projectId, "plateId" to plateId) +
                    BambuDiagnostics.errorDetails(e, config),
            )
            TransportCommandResult.Failure(e.message ?: "Bambu start print failed")
        }
    }

    override suspend fun pauseJob(): TransportCommandResult = runPrintCommand("pause")

    override suspend fun resumeJob(): TransportCommandResult = runPrintCommand("resume")

    override suspend fun cancelJob(): TransportCommandResult = runPrintCommand("stop")

    internal fun applyPushReport(json: String) {
        val parsed = BambuPushReportParser.parse(json)
        val previousStatus = _status.value
        val reportedSwitch = parsed.status.filamentTrackSwitch
        val effectiveSwitch = if (reportedSwitch.installed) {
            reportedSwitch
        } else {
            previousStatus.filamentTrackSwitch
        }
        Log.i(
            TAG,
            "parsed report hasStatus=${parsed.hasStatus} " +
                "state=${if (parsed.hasStatus) parsed.status.state else "unchanged"} " +
                "hasSlots=${parsed.hasFilamentSlots} slots=${parsed.filamentSlots.size}"
        )
        if (parsed.hasFilamentSlots) {
            val previousByIndex = _filamentSlots.value.associateBy { it.index }
            val mergedByIndex = previousByIndex.toMutableMap()
            parsed.filamentSlots.map { slot ->
                val previous = previousByIndex[slot.index]
                when {
                    effectiveSwitch.installed && slot.index < BAMBU_EXTERNAL_ROUTE_START ->
                        slot.copy(
                            nozzleSide = slot.nozzleSide.takeUnless {
                                it == NozzleSide.UNKNOWN
                            } ?: previous?.nozzleSide ?: slot.nozzleSide,
                            routing = FilamentRouting.SWITCHABLE,
                        )
                    slot.routing == FilamentRouting.UNKNOWN &&
                        previous != null &&
                        previous.routing != FilamentRouting.UNKNOWN ->
                        slot.copy(
                            nozzleSide = previous.nozzleSide,
                            routing = previous.routing,
                        )
                    else -> slot
                }
            }.forEach { slot -> mergedByIndex[slot.index] = slot }
            // Current Bambu firmware sends partial AMS/vt_tray deltas. Preserve
            // routes not present in this packet, matching Bambuddy's deep merge,
            // so a one-unit update cannot erase another AMS or external spool.
            _filamentSlots.value = mergedByIndex.values.sortedBy { it.index }
        } else if (reportedSwitch.installed) {
            _filamentSlots.value = _filamentSlots.value.map { slot ->
                if (slot.index < BAMBU_EXTERNAL_ROUTE_START) {
                    slot.copy(routing = FilamentRouting.SWITCHABLE)
                } else {
                    slot
                }
            }
        }
        val effectiveNozzles = parsed.status.nozzles.ifEmpty { previousStatus.nozzles }
        if (parsed.hasStatus) {
            _status.value = parsed.status.copy(
                filamentTrackSwitch = effectiveSwitch,
                nozzles = effectiveNozzles,
            )
        } else if (reportedSwitch.installed) {
            // Device/topology fields can arrive in an incremental push without
            // gcode_state. Preserve the current job fields while recording FTS.
            _status.value = previousStatus.copy(
                filamentTrackSwitch = reportedSwitch,
                nozzles = effectiveNozzles,
            )
        } else if (parsed.status.nozzles.isNotEmpty()) {
            _status.value = previousStatus.copy(nozzles = parsed.status.nozzles)
        }
        val status = _status.value
        val slots = _filamentSlots.value
        val fingerprint = listOf(
            status.state,
            status.progressPercent,
            status.extruders.size,
            slots.size,
            slots.count { it.loaded },
            slots.joinToString(",") { "${it.index}:${it.loaded}" },
            status.filamentTrackSwitch.installed,
            slots.count { it.routing == FilamentRouting.SWITCHABLE },
            status.nozzles.joinToString(",") { "${it.index}:${it.diameter}:${it.type}" },
        ).joinToString("|")
        if (fingerprint != lastDiagnosticStatusFingerprint) {
            lastDiagnosticStatusFingerprint = fingerprint
            BambuDiagnostics.record(
                "status_changed",
                config,
                mapOf(
                    "state" to status.state,
                    "progressPercent" to status.progressPercent,
                    "extruderCount" to status.extruders.size,
                    "installedNozzles" to status.nozzles.map { nozzle ->
                        mapOf("index" to nozzle.index, "diameter" to nozzle.diameter, "type" to nozzle.type)
                    },
                    "slotCount" to slots.size,
                    "loadedSlotCount" to slots.count { it.loaded },
                    "routeIds" to slots.map { it.index },
                    "loadedRouteIds" to slots.filter { it.loaded }.map { it.index },
                    "amsHtSlotCount" to slots.count { it.index in 128..253 },
                    "externalSlotCount" to slots.count { it.index >= BAMBU_EXTERNAL_ROUTE_START },
                    "switchableSlotCount" to slots.count { it.routing == FilamentRouting.SWITCHABLE },
                    "ftsInstalled" to status.filamentTrackSwitch.installed,
                ),
            )
        }
        if (status.state == "disconnected") scheduleReconnect()
    }

    private fun scheduleReconnect() {
        val scope = transportScope ?: return
        if (stopping || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch(Dispatchers.IO) {
            var attempt = 0
            while (isActive && !stopping) {
                attempt += 1
                delay(connectionRetryDelayMs)
                if (stopping) return@launch
                BambuDiagnostics.record(
                    "connection_reconnect_attempt",
                    config,
                    mapOf("attempt" to attempt),
                )
                val error = runCatching { client.start(config, ::applyPushReport) }.exceptionOrNull()
                if (error == null) {
                    BambuDiagnostics.record(
                        "connection_reconnect_finished",
                        config,
                        mapOf("attempt" to attempt, "result" to "success"),
                    )
                    return@launch
                }
                BambuDiagnostics.record(
                    "connection_reconnect_finished",
                    config,
                    mapOf("attempt" to attempt, "result" to "failed") +
                        BambuDiagnostics.errorDetails(error, config),
                )
            }
        }
    }

    private companion object {
        const val TAG = "BambuLanTransport"
        const val CAMERA_RETRY_DELAY_MS = 3_000L
        const val CONNECTION_RETRY_DELAY_MS = 3_000L
        const val BAMBU_EXTERNAL_ROUTE_START = 254
    }

    private suspend fun runPrintCommand(command: String): TransportCommandResult = try {
        BambuDiagnostics.record("job_command_started", config, mapOf("stage" to command))
        client.sendPrintCommand(config, command)
        BambuDiagnostics.record("job_command_finished", config, mapOf("stage" to command, "result" to "queued"))
        TransportCommandResult.Success
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        BambuDiagnostics.record(
            "job_command_finished",
            config,
            mapOf("stage" to command, "result" to "failed") + BambuDiagnostics.errorDetails(e, config),
        )
        TransportCommandResult.Failure(e.message ?: "Bambu command failed")
    }

    private fun startCamera(scope: CoroutineScope) {
        cameraJob?.cancel()
        cameraClient.rtspUri(config)?.let { uri ->
            BambuDiagnostics.record("camera_selected", config, mapOf("protocol" to "RTSPS", "result" to "ready"))
            _cameraState.value = CameraState.Rtsp(uri)
            return
        }
        if (!capabilities.supportsCamera) {
            BambuDiagnostics.record("camera_selected", config, mapOf("protocol" to "none", "result" to "unsupported"))
            _cameraState.value = CameraState.Disabled
            return
        }
        BambuDiagnostics.record("camera_selected", config, mapOf("protocol" to "TCP_JPEG", "result" to "starting"))
        val frames = MutableSharedFlow<Bitmap>(replay = 1, extraBufferCapacity = 1)
        val streamingState = CameraState.Streaming(frames.asSharedFlow())
        _cameraState.value = streamingState
        cameraJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                // A prior failure leaves the UI in Error; make each retry's frame
                // flow visible before the camera client reconnects.
                _cameraState.value = streamingState
                try {
                    BambuDiagnostics.record("camera_stream_attempt", config, mapOf("protocol" to "TCP_JPEG"))
                    cameraClient.stream(config) { bitmap ->
                        frames.tryEmit(bitmap)
                    }
                    if (isActive) {
                        BambuDiagnostics.record(
                            "camera_stream_ended",
                            config,
                            mapOf("protocol" to "TCP_JPEG", "result" to "retrying"),
                        )
                        _cameraState.value = CameraState.Error("Camera stream ended")
                        delay(cameraRetryDelayMs)
                    }
                } catch (error: Exception) {
                    if (isActive) {
                        Log.w(TAG, "camera failed: ${error.javaClass.simpleName}: ${error.message}")
                        _cameraState.value = CameraState.Error(error.message ?: "Camera stream failed")
                        BambuDiagnostics.record(
                            "camera_stream_failed",
                            config,
                            mapOf("protocol" to "TCP_JPEG", "result" to "retrying") +
                                BambuDiagnostics.errorDetails(error, config),
                        )
                        delay(cameraRetryDelayMs)
                    }
                }
            }
        }
    }

}
