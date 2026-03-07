package com.u1.slicer.printer

import com.u1.slicer.data.SettingsRepository
import com.u1.slicer.network.FilamentSlot
import com.u1.slicer.network.MoonrakerClient
import com.u1.slicer.network.PrinterStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class PrinterRepository(
    private val client: MoonrakerClient,
    private val settingsRepo: SettingsRepository
) {
    private val _status = MutableStateFlow(PrinterStatus(state = "disconnected", progress = 0f))
    val status: StateFlow<PrinterStatus> = _status.asStateFlow()

    private val _printerUrl = MutableStateFlow("")
    val printerUrl: StateFlow<String> = _printerUrl.asStateFlow()

    private var pollingJob: Job? = null

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

    fun startPolling(scope: CoroutineScope) {
        stopPolling()
        pollingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                _status.value = client.getStatus()
                delay(2000)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    suspend fun uploadAndPrint(gcodeFile: java.io.File, filename: String): Boolean {
        val uploaded = client.uploadGcode(gcodeFile, filename)
        if (!uploaded) return false
        return client.startPrint(filename)
    }

    suspend fun queryWebcamSnapshotCandidates(): List<String> = client.queryWebcamSnapshotCandidates()

    suspend fun queryFilamentSlots(): List<FilamentSlot>? = client.queryFilamentSlots()

    suspend fun pausePrint(): Boolean = client.pausePrint()
    suspend fun resumePrint(): Boolean = client.resumePrint()
    suspend fun cancelPrint(): Boolean = client.cancelPrint()

    suspend fun getLedState(): Boolean? = client.getLedState()
    suspend fun setLed(on: Boolean): Boolean = client.setLed(on)
}
