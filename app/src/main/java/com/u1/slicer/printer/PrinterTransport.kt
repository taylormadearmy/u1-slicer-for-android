package com.u1.slicer.printer

import android.graphics.Bitmap
import com.u1.slicer.network.FilamentSlot
import com.u1.slicer.network.PrinterStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import java.io.File

data class PrinterTransportCapabilities(
    val supportsCamera: Boolean = false,
    val supportsRemoteScreen: Boolean = false,
    val supportsLightControl: Boolean = false,
    val supportsFilamentSync: Boolean = false,
    val reportsFilamentSlotsWithStatus: Boolean = false,
    val supportsHeaterControl: Boolean = false,
    val supportsCustomGcode: Boolean = false,
    val supportsSkipObjects: Boolean = false,
    val supportsUpload: Boolean = false,
    val supportsStartJob: Boolean = false,
    val supportsStartProject: Boolean = false,
    val supportsPause: Boolean = false,
    val supportsResume: Boolean = false,
    val supportsCancel: Boolean = false,
)

sealed class CameraState {
    data object Disabled : CameraState()
    data object Connecting : CameraState()
    data class Streaming(val frames: Flow<Bitmap>) : CameraState()
    /** A printer-native live stream rendered by the UI's RTSP player. */
    data class Rtsp(val uri: String) : CameraState()
    data class Error(val message: String) : CameraState()
}

interface PrinterTransport {
    val capabilities: PrinterTransportCapabilities
    val status: Flow<PrinterStatus>
    val filamentSlots: Flow<List<FilamentSlot>>
    val cameraState: Flow<CameraState>

    suspend fun start(scope: CoroutineScope) {}
    suspend fun stop() {}
    suspend fun testConnection(): String?
    suspend fun queryWebcamSources(): List<WebcamSource> = emptyList()
    suspend fun wakeCamera() {}
    suspend fun queryFilamentSlots(): List<FilamentSlot>? = null
    suspend fun getLedState(): Boolean? = null
    suspend fun setLed(on: Boolean): TransportCommandResult =
        TransportCommandResult.Unsupported("Light control is not supported for this printer")
    suspend fun sendGcode(gcode: String): TransportCommandResult =
        TransportCommandResult.Unsupported("Custom G-code is not supported for this printer")
    suspend fun setHeaterTemperature(heater: String, targetC: Int): TransportCommandResult =
        TransportCommandResult.Unsupported("Heater control is not supported for this printer")
    suspend fun probeRemoteScreen(): Boolean = false
    fun remoteScreenUrl(): String? = null
    suspend fun hintJobStarting() {}

    suspend fun uploadJob(file: File, remoteName: String): TransportCommandResult =
        TransportCommandResult.Unsupported("Upload is not supported for this printer")

    suspend fun validateStartProject(): TransportCommandResult = TransportCommandResult.Success

    suspend fun startJob(remoteName: String): TransportCommandResult =
        TransportCommandResult.Unsupported("Start print is not supported for this printer")

    suspend fun startProject(
        remoteName: String,
        plateId: Int,
        amsMapping: List<Int>,
        useAms: Boolean,
        subtaskName: String,
    ): TransportCommandResult =
        TransportCommandResult.Unsupported("Project-file start is not supported for this printer")

    suspend fun pauseJob(): TransportCommandResult =
        TransportCommandResult.Unsupported("Pause is not supported for this printer")

    suspend fun resumeJob(): TransportCommandResult =
        TransportCommandResult.Unsupported("Resume is not supported for this printer")

    suspend fun cancelJob(): TransportCommandResult =
        TransportCommandResult.Unsupported("Cancel is not supported for this printer")
}
