package com.u1.slicer.printer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.u1.slicer.U1SlicerApplication
import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.FilamentLibraryEntry
import com.u1.slicer.data.defaultExtruderPresets
import com.u1.slicer.data.upsertLibraryProfile
import com.u1.slicer.network.FilamentSlot
import com.u1.slicer.network.PrinterStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class PrinterViewModel(application: Application) : AndroidViewModel(application) {

    private val printerRepo = (application as U1SlicerApplication).container.printerRepository
    private val printersRepo = (application as U1SlicerApplication).container.printersRepository
    private val libraryRepo = (application as U1SlicerApplication).container.filamentLibraryRepository
    private val filamentDao = (application as U1SlicerApplication).container.filamentDao

    // ── OpenPrintTag filament library (Task 7) — hosted in ExtruderSlotEditDialog ──
    val libraryState = libraryRepo.state
    val libraryFavourites: StateFlow<List<String>> = libraryRepo.favourites
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val libraryRecents: StateFlow<List<String>> = libraryRepo.recents
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun toggleLibraryFavourite(slug: String) {
        viewModelScope.launch { libraryRepo.toggleFavourite(slug) }
    }

    fun retryLibraryLoad() = libraryRepo.retry(viewModelScope)

    fun recordLibraryRecent(slug: String) {
        viewModelScope.launch { libraryRepo.recordRecent(slug) }
    }

    /** Upsert profile from a library entry; onDone delivers the row id on the main thread. */
    fun importLibraryProfile(entry: FilamentLibraryEntry, onDone: (Long) -> Unit) {
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                upsertLibraryProfile(filamentDao, entry)
            }
            libraryRepo.recordRecent(entry.slug)
            onDone(id)
        }
    }

    val status: StateFlow<PrinterStatus> = printerRepo.status
    val printerUrl: StateFlow<String> = printerRepo.printerUrl

    val activeNickname: StateFlow<String> = printerRepo.activeNickname

    val printerCount: StateFlow<Int> = printerRepo.printerCount

    /** Full list of configured printers — drives the switcher bottom sheet. */
    val printerList: StateFlow<List<com.u1.slicer.data.Printer>> = printersRepo.config
        .map { it?.printers ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val activePrinterId: StateFlow<String?> = printersRepo.config
        .map { it?.activeId }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    // Resolved webcam snapshot URL candidates (primary + optional alt with port).
    // Populated by resolveWebcam() which queries /server/webcams/list.
    private val _webcamCandidates = MutableStateFlow<List<String>>(emptyList())
    val webcamCandidates: StateFlow<List<String>> = _webcamCandidates.asStateFlow()

    // Kept for backward compat — primary candidate or empty
    val webcamSnapshotUrl: StateFlow<String> = _webcamCandidates
        .map { it.firstOrNull() ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** F78: per-extruder slot config sourced from the active printer. */
    val extruderPresets: StateFlow<List<ExtruderPreset>> = printersRepo.activePrinter
        .map { it?.extruderPresets ?: defaultExtruderPresets() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, defaultExtruderPresets())

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Unknown)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _sendingState = MutableStateFlow<SendingState>(SendingState.Idle)
    val sendingState: StateFlow<SendingState> = _sendingState.asStateFlow()

    // LED light state (null = unknown/not connected)
    private val _isLightOn = MutableStateFlow<Boolean?>(null)
    val isLightOn: StateFlow<Boolean?> = _isLightOn.asStateFlow()

    // paxx12 extended-firmware remote screen availability (probed after connection)
    private val _remoteScreenAvailable = MutableStateFlow(false)
    val remoteScreenAvailable: StateFlow<Boolean> = _remoteScreenAvailable.asStateFlow()

    private val _heaterError = MutableStateFlow<String?>(null)
    val heaterError: StateFlow<String?> = _heaterError.asStateFlow()

    private val _skippedObjects = MutableStateFlow<Set<String>>(emptySet())
    val skippedObjects: StateFlow<Set<String>> = _skippedObjects.asStateFlow()

    private var cameraKeepaliveJob: Job? = null

    fun startCameraKeepalive() {
        if (!shouldStartCameraKeepalive(cameraKeepaliveJob?.isActive == true)) return
        cameraKeepaliveJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                printerRepo.wakeCamera()
                delay(2000)
            }
        }
    }

    fun stopCameraKeepalive() {
        cameraKeepaliveJob?.cancel()
        cameraKeepaliveJob = null
    }

    fun clearHeaterError() { _heaterError.value = null }

    sealed class ConnectionState {
        object Unknown : ConnectionState()
        object Testing : ConnectionState()
        object Connected : ConnectionState()
        data class Failed(val reason: String) : ConnectionState()
    }

    sealed class SendingState {
        object Idle : SendingState()
        /** File is being prepared (remap/copy) before the upload begins. */
        object Preparing : SendingState()
        object Uploading : SendingState()
        /** Upload + print queued successfully. */
        object PrintStarted : SendingState()
        /** Upload-only succeeded (no print queued). */
        object UploadComplete : SendingState()
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
        libraryRepo.ensureLoaded(viewModelScope)
        printerRepo.startPolling(viewModelScope)
        // Resolve webcam URLs for the already-saved printer URL (if any)
        viewModelScope.launch(Dispatchers.IO) { resolveWebcam() }
        // Auto-clear the "Print started!" banner once the printer confirms printing.
        // Also sync LED state on first connection (pollLedState only runs via testConnection otherwise).
        viewModelScope.launch {
            var wasConnected = false
            status.collect { s ->
                if (_sendingState.value is SendingState.PrintStarted && s.isPrinting) {
                    _sendingState.value = SendingState.Idle
                }
                if (shouldPollLedOnConnectionEdge(wasConnected = wasConnected, isConnected = s.isConnected)) {
                    launch(Dispatchers.IO) { pollLedState() }
                }
                wasConnected = s.isConnected
            }
        }
        // Reset skippedObjects when a new print starts
        viewModelScope.launch {
            var lastFilename = ""
            status.collect { s ->
                if (s.filename.isNotEmpty() && s.filename != lastFilename) {
                    _skippedObjects.value = emptySet()
                    lastFilename = s.filename
                }
            }
        }
    }

    fun updateUrl(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            printerRepo.updateActiveUrl(url)
            _connectionState.value = ConnectionState.Unknown
            resolveWebcam()
        }
    }

    fun switchActivePrinter(id: String) {
        viewModelScope.launch { printersRepo.setActive(id) }
    }

    fun addPrinter(nickname: String, url: String) {
        viewModelScope.launch {
            val printer = com.u1.slicer.data.Printer(
                id = java.util.UUID.randomUUID().toString(),
                nickname = nickname.ifBlank { "Printer ${(printerList.value.size + 1)}" },
                moonrakerUrl = com.u1.slicer.network.MoonrakerClient.normalizeUrl(url),
            )
            printersRepo.add(printer)
        }
    }

    fun updatePrinter(id: String, nickname: String, url: String) {
        viewModelScope.launch {
            val current = printerList.value.firstOrNull { it.id == id } ?: return@launch
            printersRepo.update(current.copy(
                nickname = nickname,
                moonrakerUrl = com.u1.slicer.network.MoonrakerClient.normalizeUrl(url),
            ))
        }
    }

    fun deletePrinter(id: String) {
        viewModelScope.launch {
            try {
                printersRepo.delete(id)
            } catch (e: IllegalStateException) {
                _heaterError.value = e.message ?: "Cannot delete printer"
            }
        }
    }

    private suspend fun resolveWebcam() {
        val candidates = printerRepo.queryWebcamSnapshotCandidates()
        _webcamCandidates.value = candidates
    }

    /** Returns the URL for the paxx12 extended-firmware remote screen, or null. */
    fun remoteScreenUrl(): String? = printerRepo.remoteScreenUrl()

    fun testConnection() {
        _connectionState.value = ConnectionState.Testing
        viewModelScope.launch(Dispatchers.IO) {
            val error = printerRepo.testConnection()
            _connectionState.value = if (error == null) ConnectionState.Connected
                                     else ConnectionState.Failed(error)
            if (error == null) {
                pollLedState()
                _remoteScreenAvailable.value = printerRepo.probeRemoteScreen()
                resolveWebcam()
            } else {
                _remoteScreenAvailable.value = false
            }
        }
    }

    fun toggleLight() {
        viewModelScope.launch(Dispatchers.IO) {
            val current = _isLightOn.value ?: false
            val success = printerRepo.setLed(!current)
            if (success) _isLightOn.value = !current
        }
    }

    private suspend fun pollLedState() {
        _isLightOn.value = printerRepo.getLedState()
    }

    /** F78: writes back into the active printer's extruderPresets list. */
    fun updateExtruderPreset(preset: ExtruderPreset) {
        viewModelScope.launch {
            val cfg = printersRepo.config.first() ?: return@launch
            val active = cfg.active
            val updated = active.extruderPresets.map { if (it.index == preset.index) preset else it }
            printersRepo.update(active.copy(extruderPresets = updated))
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
        viewModelScope.launch {
            val cfg = printersRepo.config.first() ?: return@launch
            val active = cfg.active
            val current = active.extruderPresets.toMutableList()
            entries.forEach { entry ->
                val idx = current.indexOfFirst { it.index == entry.slotIndex }
                if (idx >= 0) {
                    val applyingType = applyTypes && entry.newType != null
                    current[idx] = current[idx].copy(
                        color = if (applyColors && entry.newColor != null) entry.newColor else current[idx].color,
                        materialType = if (applyingType) entry.newType!! else current[idx].materialType,
                        // Clear the linked filament profile when applying a type from the printer,
                        // so the slot label shows the synced material type rather than the old profile name.
                        filamentProfileId = if (applyingType) null else current[idx].filamentProfileId
                    )
                }
            }
            printersRepo.update(active.copy(extruderPresets = current))
        }
    }

    fun dismissSync() {
        _syncState.value = SyncState.Idle
    }

    /**
     * F94 — show the "Preparing G-code" banner the instant a send action is confirmed,
     * before any IO begins. sendUploadOnly/sendAndPrint flip it to Uploading; a prep
     * failure flips it to Error via [reportSendError].
     */
    fun beginSendPreparing() {
        _sendingState.value = SendingState.Preparing
    }

    /** F94 — surface a send/prep failure on the Printer screen (prevents a stuck banner). */
    fun reportSendError(message: String) {
        _sendingState.value = SendingState.Error(message)
    }

    /**
     * Phase 2 B.1 (2026-04-28) — accepts only [com.u1.slicer.gcode.PhysicalGcodePath].
     * Callers must apply [com.u1.slicer.gcode.applyPrintTimeRemap] (or
     * confirm the source is already in physical-slot space) before
     * reaching this function. The compiler enforces it.
     */
    fun sendAndPrint(physical: com.u1.slicer.gcode.PhysicalGcodePath, modelName: String? = null) {
        _sendingState.value = SendingState.Uploading
        viewModelScope.launch(Dispatchers.IO) {
            // Hold a foreground service across the upload. A 272 MB G-code can take
            // minutes over WAN; without this the cached-app freezer suspends the
            // process the moment the user backgrounds the app, truncating the
            // upload to the printer.
            val ctx = getApplication<Application>()
            com.u1.slicer.LongOpService.start(ctx, "Uploading to printer")
            try {
                val file = physical.toFile()
                if (!file.exists()) {
                    _sendingState.value = SendingState.Error("G-code file not found")
                    return@launch
                }
                // F84: prefer the original model name over the on-disk gcode name
                // so the printer's file browser shows distinct, recognisable jobs.
                val filename = PrinterRepository.resolveUploadBaseName(modelName, file.name)
                val ok = printerRepo.uploadAndPrint(file, filename)
                _sendingState.value = if (ok) {
                    SendingState.PrintStarted
                } else {
                    SendingState.Error("Failed to upload or start print")
                }
            } finally {
                com.u1.slicer.LongOpService.stop(ctx)
            }
        }
    }

    /**
     * Phase 2 B.1 (2026-04-28) — accepts only [com.u1.slicer.gcode.PhysicalGcodePath].
     * See [sendAndPrint] for the type-safety rationale.
     */
    fun sendUploadOnly(physical: com.u1.slicer.gcode.PhysicalGcodePath, modelName: String? = null) {
        _sendingState.value = SendingState.Uploading
        viewModelScope.launch(Dispatchers.IO) {
            // Foreground service across the upload — see sendAndPrint. A truncated
            // upload-only would leave a corrupt G-code on the printer's storage.
            val ctx = getApplication<Application>()
            com.u1.slicer.LongOpService.start(ctx, "Uploading to printer")
            try {
                val file = physical.toFile()
                if (!file.exists()) {
                    _sendingState.value = SendingState.Error("G-code file not found")
                    return@launch
                }
                val filename = PrinterRepository.resolveUploadBaseName(modelName, file.name)
                val ok = printerRepo.uploadOnly(file, filename)
                _sendingState.value = if (ok) SendingState.UploadComplete else SendingState.Error("Upload failed")
                if (ok) {
                    com.u1.slicer.AppEventNotifier.notify(
                        getApplication(),
                        com.u1.slicer.AppEventNotifier.Event.UploadComplete(filename)
                    )
                }
            } finally {
                com.u1.slicer.LongOpService.stop(ctx)
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

    fun skipObject(name: String) {
        viewModelScope.launch {
            printerRepo.sendGcode("EXCLUDE_OBJECT NAME=$name")
            _skippedObjects.update { it + name }
        }
    }

    fun setHeaterTemperature(heater: String, targetC: Int) {
        viewModelScope.launch {
            val ok = printerRepo.setHeaterTemperature(heater, targetC)
            if (!ok) _heaterError.value = "Could not update temperature"
        }
    }

    // F82: idle-state printer controls — temperature-only set, no head motion.
    // `TURN_OFF_HEATERS` is the standard Klipper macro that drops bed + every
    // extruder to 0 in one call. Safe to send when idle, printing, or paused.
    fun cooldownAll() {
        viewModelScope.launch {
            val ok = printerRepo.sendGcode("TURN_OFF_HEATERS")
            if (!ok) _heaterError.value = "Could not send cooldown"
        }
    }

    // F82: arbitrary G-code line from the user-typed custom-G-code box. User
    // owns the risk (movements, raw heater commands, etc); the UI displays a
    // warning beside the field. Empty / whitespace-only input is rejected.
    fun sendCustomGcode(script: String) {
        val trimmed = sanitizeCustomGcode(script) ?: return
        viewModelScope.launch {
            val ok = printerRepo.sendGcode(trimmed)
            if (!ok) _heaterError.value = "Could not send G-code"
        }
    }

    fun clearSendingState() {
        _sendingState.value = SendingState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        stopCameraKeepalive()
        printerRepo.stopPolling()
    }

    companion object {
        // F82: pre-flight check for the custom G-code input. Returns the
        // sanitised script when it should be sent, or null when the input is
        // empty/whitespace-only. Keeps the side-effecting `sendCustomGcode`
        // thin so this rule is unit-testable.
        internal fun sanitizeCustomGcode(raw: String): String? {
            val trimmed = raw.trim()
            return if (trimmed.isEmpty()) null else trimmed
        }

        internal fun shouldStartCameraKeepalive(hasActiveJob: Boolean): Boolean = !hasActiveJob

        internal fun shouldPollLedOnConnectionEdge(wasConnected: Boolean, isConnected: Boolean): Boolean =
            isConnected && !wasConnected
    }
}
