package com.u1.slicer.printer

import com.u1.slicer.network.FilamentSlot
import com.u1.slicer.network.MoonrakerClient
import com.u1.slicer.network.PrinterStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class MoonrakerTransport(
    private val client: MoonrakerClient,
    private val u1: Boolean = true,
    private val maxBedTemperatureC: Int = com.u1.slicer.machine.MachineProfile.DEFAULT_MAX_BED_TEMPERATURE_C,
    private val maxNozzleTemperatureC: Int = com.u1.slicer.machine.MachineProfile.DEFAULT_MAX_NOZZLE_TEMPERATURE_C,
) : PrinterTransport {
    override val capabilities = PrinterTransportCapabilities(
        supportsCamera = true,
        supportsRemoteScreen = u1,
        supportsLightControl = u1,
        supportsFilamentSync = u1,
        supportsHeaterControl = true,
        supportsConsole = true,
        supportsSkipObjects = true,
        supportsUpload = true,
        supportsStartJob = true,
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

    private var pollingJob: Job? = null

    @Volatile
    private var rapidPollCyclesRemaining = 0

    override suspend fun start(scope: CoroutineScope) {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                refreshStatusOnce()
                val interval = if (rapidPollCyclesRemaining > 0) {
                    rapidPollCyclesRemaining--
                    500L
                } else {
                    2000L
                }
                delay(interval)
            }
        }
    }

    override suspend fun stop() {
        pollingJob?.cancel()
        pollingJob = null
        _status.value = PrinterStatus(state = "disconnected", progress = 0f)
    }

    override suspend fun testConnection(): String? = client.testConnection()

    override suspend fun queryWebcamSources(): List<WebcamSource> = client.queryWebcamSources()

    override suspend fun wakeCamera() {
        if (u1) client.wakeCamera()
    }

    override suspend fun queryFilamentSlots(): List<FilamentSlot>? {
        if (!u1) return null
        val slots = client.queryFilamentSlots()
        _filamentSlots.value = slots ?: emptyList()
        return slots
    }

    override suspend fun getLedState(): Boolean? = if (u1) client.getLedState() else null

    override suspend fun setLed(on: Boolean): TransportCommandResult =
        if (!u1) TransportCommandResult.Unsupported("U1 light control is not available on generic Klipper printers")
        else if (client.setLed(on)) TransportCommandResult.Success
        else TransportCommandResult.Failure("Light update failed")

    override suspend fun sendGcode(gcode: String): TransportCommandResult =
        if (client.sendGcode(gcode)) TransportCommandResult.Success
        else TransportCommandResult.Failure("Could not send G-code")

    override suspend fun setHeaterTemperature(heater: String, targetC: Int): TransportCommandResult =
        if (client.setHeaterTemperature(
                heater,
                targetC,
                maxBedTemperatureC,
                maxNozzleTemperatureC,
            )
        ) TransportCommandResult.Success
        else TransportCommandResult.Failure("Could not update temperature")

    override suspend fun probeRemoteScreen(): Boolean = u1 && client.probeRemoteScreen()

    override fun remoteScreenUrl(): String? = if (u1) client.remoteScreenUrl() else null

    override suspend fun hintJobStarting() {
        rapidPollCyclesRemaining = 60
        refreshStatusOnce()
    }

    override suspend fun uploadJob(
        file: File,
        remoteName: String,
        startAfterUpload: Boolean,
    ): TransportCommandResult =
        if (client.uploadGcode(file, remoteName)) TransportCommandResult.Success
        else TransportCommandResult.Failure("Upload failed")

    override suspend fun startJob(remoteName: String): TransportCommandResult =
        if (client.startPrint(remoteName)) TransportCommandResult.Success
        else TransportCommandResult.Failure("Start print failed")

    override suspend fun pauseJob(): TransportCommandResult =
        if (client.pausePrint()) TransportCommandResult.Success
        else TransportCommandResult.Failure("Pause failed")

    override suspend fun resumeJob(): TransportCommandResult =
        if (client.resumePrint()) TransportCommandResult.Success
        else TransportCommandResult.Failure("Resume failed")

    override suspend fun cancelJob(): TransportCommandResult =
        if (client.cancelPrint()) TransportCommandResult.Success
        else TransportCommandResult.Failure("Cancel failed")

    private suspend fun refreshStatusOnce() {
        _status.value = client.getStatus()
    }
}
