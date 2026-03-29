package com.u1.slicer.printer

import com.u1.slicer.data.SettingsRepository
import com.u1.slicer.network.FilamentSlot
import com.u1.slicer.network.MoonrakerClient
import com.u1.slicer.network.PrinterStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class PrinterRepository(
    private val appContext: android.content.Context,
    private val client: MoonrakerClient,
    private val settingsRepo: SettingsRepository
) {
    private val _status = MutableStateFlow(PrinterStatus(state = "disconnected", progress = 0f))
    val status: StateFlow<PrinterStatus> = _status.asStateFlow()

    private val _printerUrl = MutableStateFlow("")
    val printerUrl: StateFlow<String> = _printerUrl.asStateFlow()

    private var pollingJob: Job? = null

    /**
     * When > 0 the polling loop uses 500 ms intervals instead of 2 000 ms.
     * Decremented each cycle; when it reaches 0 normal polling resumes.
     */
    @Volatile
    private var rapidPollCyclesRemaining = 0

    init {
        // Load saved printer URL
        CoroutineScope(Dispatchers.IO).launch {
            settingsRepo.printerUrl.collect { url ->
                _printerUrl.value = url
                client.baseUrl = url
            }
        }
    }

    suspend fun updateUrl(url: String) {
        val normalized = MoonrakerClient.normalizeUrl(url)
        _printerUrl.value = normalized
        client.baseUrl = normalized
        settingsRepo.savePrinterUrl(normalized)
    }

    /** Returns null on success, or an error message string on failure. */
    suspend fun testConnection(): String? {
        return client.testConnection()
    }

    /** Returns true if the paxx12 extended-firmware remote screen endpoint is reachable. */
    suspend fun probeRemoteScreen(): Boolean = client.probeRemoteScreen()

    /** Returns the URL for the paxx12 extended-firmware remote screen, or null if unavailable. */
    fun remoteScreenUrl(): String? = client.remoteScreenUrl()

    fun startPolling(scope: CoroutineScope) {
        stopPolling()
        pollingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val latestStatus = client.getStatus()
                _status.value = latestStatus
                PrintProgressNotifier.update(appContext, latestStatus)
                val interval = if (rapidPollCyclesRemaining > 0) {
                    rapidPollCyclesRemaining--
                    500L
                } else 2000L
                delay(interval)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        PrintProgressNotifier.clear(appContext)
    }

    suspend fun uploadAndPrint(gcodeFile: java.io.File, filename: String): Boolean {
        val uploadName = buildPrinterUploadFilename(filename)
        val uploaded = client.uploadGcode(gcodeFile, uploadName)
        if (!uploaded) return false
        val started = client.startPrint(uploadName)
        if (started) {
            // Kick off rapid polling so the UI picks up the "printing" state
            // as soon as Moonraker/Klipper transitions (PRINT_START macro may
            // take several seconds on Snapmaker U1).  60 cycles × 500 ms = 30 s.
            rapidPollCyclesRemaining = 60
            // Immediate refresh so we don't wait for the next poll cycle.
            val latestStatus = client.getStatus()
            _status.value = latestStatus
            PrintProgressNotifier.update(appContext, latestStatus)
        }
        return started
    }

    suspend fun uploadOnly(gcodeFile: java.io.File, filename: String): Boolean {
        val uploadName = buildPrinterUploadFilename(filename)
        return client.uploadGcode(gcodeFile, uploadName)
    }

    suspend fun queryWebcamSnapshotCandidates(): List<String> = client.queryWebcamSnapshotCandidates()

    suspend fun queryFilamentSlots(): List<FilamentSlot>? = client.queryFilamentSlots()

    suspend fun pausePrint(): Boolean = client.pausePrint()
    suspend fun resumePrint(): Boolean = client.resumePrint()
    suspend fun cancelPrint(): Boolean = client.cancelPrint()

    suspend fun getLedState(): Boolean? = client.getLedState()
    suspend fun setLed(on: Boolean): Boolean = client.setLed(on)
    suspend fun setHeaterTemperature(heater: String, targetC: Int): Boolean =
        client.setHeaterTemperature(heater, targetC)

    companion object {
        internal fun buildPrinterUploadFilename(sourceName: String, nowMillis: Long = System.currentTimeMillis()): String {
            val base = sourceName
                .substringBeforeLast('.', sourceName)
                .replace(Regex("""[^A-Za-z0-9._-]+"""), "_")
                .replace(Regex("""_+"""), "_")
                .trim('_')
                .ifBlank { "print" }
            return "${base}_$nowMillis.gcode"
        }
    }
}
