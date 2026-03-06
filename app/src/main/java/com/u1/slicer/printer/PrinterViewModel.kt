package com.u1.slicer.printer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.u1.slicer.U1SlicerApplication
import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.defaultExtruderPresets
import com.u1.slicer.network.FilamentSlot
import com.u1.slicer.network.PrinterStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class PrinterViewModel(application: Application) : AndroidViewModel(application) {

    private val printerRepo = (application as U1SlicerApplication).container.printerRepository
    private val settingsRepo = (application as U1SlicerApplication).container.settingsRepository

    val status: StateFlow<PrinterStatus> = printerRepo.status
    val printerUrl: StateFlow<String> = printerRepo.printerUrl

    // Resolved webcam snapshot URL candidates (primary + optional alt with port).
    // Populated by resolveWebcam() which queries /server/webcams/list.
    private val _webcamCandidates = MutableStateFlow<List<String>>(emptyList())
    val webcamCandidates: StateFlow<List<String>> = _webcamCandidates.asStateFlow()

    // Kept for backward compat — primary candidate or empty
    val webcamSnapshotUrl: StateFlow<String> = _webcamCandidates
        .map { it.firstOrNull() ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** Persisted per-extruder slot config (color + material type + optional profile). */
    val extruderPresets: StateFlow<List<ExtruderPreset>> = settingsRepo.extruderPresets
        .stateIn(viewModelScope, SharingStarted.Eagerly, defaultExtruderPresets())

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Unknown)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _sendingState = MutableStateFlow<SendingState>(SendingState.Idle)
    val sendingState: StateFlow<SendingState> = _sendingState.asStateFlow()

    sealed class ConnectionState {
        object Unknown : ConnectionState()
        object Testing : ConnectionState()
        object Connected : ConnectionState()
        data class Failed(val reason: String) : ConnectionState()
    }

    sealed class SendingState {
        object Idle : SendingState()
        object Uploading : SendingState()
        object Success : SendingState()
        data class Error(val message: String) : SendingState()
    }

    /**
     * Sync preview: each entry is (current preset, printer slot or null).
     * User confirms applying colors and/or material types.
     */
    data class SyncPreviewEntry(
        val slotIndex: Int,
        val label: String,
        val currentColor: String,
        val newColor: String?,       // from printer, null if printer slot unavailable
        val currentType: String,
        val newType: String?
    )

    sealed class SyncState {
        object Idle : SyncState()
        object Loading : SyncState()
        data class Preview(val entries: List<SyncPreviewEntry>) : SyncState()
        data class Error(val message: String) : SyncState()
    }

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    init {
        printerRepo.startPolling(viewModelScope)
        // Resolve webcam URLs for the already-saved printer URL (if any)
        viewModelScope.launch(Dispatchers.IO) { resolveWebcam() }
    }

    fun updateUrl(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            printerRepo.updateUrl(url)
            _connectionState.value = ConnectionState.Unknown
            resolveWebcam()
        }
    }

    private suspend fun resolveWebcam() {
        val candidates = printerRepo.queryWebcamSnapshotCandidates()
        _webcamCandidates.value = candidates
    }

    fun testConnection() {
        _connectionState.value = ConnectionState.Testing
        viewModelScope.launch(Dispatchers.IO) {
            val error = printerRepo.testConnection()
            _connectionState.value = if (error == null) ConnectionState.Connected
                                     else ConnectionState.Failed(error)
        }
    }

    /** Update a single extruder slot (color or material type edit on printer page). */
    fun updateExtruderPreset(preset: ExtruderPreset) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = extruderPresets.value.toMutableList()
            val idx = current.indexOfFirst { it.index == preset.index }
            if (idx >= 0) current[idx] = preset else current.add(preset)
            settingsRepo.saveExtruderPresets(current.sortedBy { it.index })
        }
    }

    fun syncFilaments() {
        _syncState.value = SyncState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            val slots = printerRepo.queryFilamentSlots()
            if (slots == null || slots.isEmpty()) {
                _syncState.value = SyncState.Error("No filament data available from printer")
                return@launch
            }
            val presets = extruderPresets.value
            val entries = (0..3).map { i ->
                val preset = presets.getOrElse(i) { ExtruderPreset(i) }
                val printerSlot = slots.getOrNull(i)
                SyncPreviewEntry(
                    slotIndex = i,
                    label = "E${i + 1}",
                    currentColor = preset.color,
                    newColor = printerSlot?.color,
                    currentType = preset.materialType,
                    newType = printerSlot?.materialType
                )
            }
            _syncState.value = SyncState.Preview(entries)
        }
    }

    /** Apply the sync result — update presets with printer data as requested. */
    fun applySyncResult(entries: List<SyncPreviewEntry>, applyColors: Boolean, applyTypes: Boolean) {
        _syncState.value = SyncState.Idle
        viewModelScope.launch(Dispatchers.IO) {
            val current = extruderPresets.value.toMutableList()
            entries.forEach { entry ->
                val idx = current.indexOfFirst { it.index == entry.slotIndex }
                if (idx >= 0) {
                    current[idx] = current[idx].copy(
                        color = if (applyColors && entry.newColor != null) entry.newColor else current[idx].color,
                        materialType = if (applyTypes && entry.newType != null) entry.newType else current[idx].materialType
                    )
                }
            }
            settingsRepo.saveExtruderPresets(current)
        }
    }

    fun dismissSync() {
        _syncState.value = SyncState.Idle
    }

    fun sendAndPrint(gcodePath: String) {
        _sendingState.value = SendingState.Uploading
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(gcodePath)
            if (!file.exists()) {
                _sendingState.value = SendingState.Error("G-code file not found")
                return@launch
            }
            val filename = file.name
            val ok = printerRepo.uploadAndPrint(file, filename)
            _sendingState.value = if (ok) {
                SendingState.Success
            } else {
                SendingState.Error("Failed to upload or start print")
            }
        }
    }

    fun pausePrint() {
        viewModelScope.launch(Dispatchers.IO) { printerRepo.pausePrint() }
    }

    fun resumePrint() {
        viewModelScope.launch(Dispatchers.IO) { printerRepo.resumePrint() }
    }

    fun cancelPrint() {
        viewModelScope.launch(Dispatchers.IO) { printerRepo.cancelPrint() }
    }

    fun clearSendingState() {
        _sendingState.value = SendingState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        printerRepo.stopPolling()
    }
}
