package com.u1.slicer.printer

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.u1.slicer.U1SlicerApplication
import com.u1.slicer.data.BambuConfig
import com.u1.slicer.data.BambuModel
import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.FilamentLibraryEntry
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrinterKind
import com.u1.slicer.data.PrintersConfig
import com.u1.slicer.data.defaultExtruderPresets
import com.u1.slicer.data.upsertLibraryProfile
import com.u1.slicer.network.FilamentSlot
import com.u1.slicer.network.PrinterStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class PrinterViewModel(application: Application) : AndroidViewModel(application) {

    private val printerRepo = (application as U1SlicerApplication).container.printerRepository
    private val printersRepo = (application as U1SlicerApplication).container.printersRepository
    private val libraryRepo = (application as U1SlicerApplication).container.filamentLibraryRepository
    private val filamentDao = (application as U1SlicerApplication).container.filamentDao
    private val settingsRepo = (application as U1SlicerApplication).container.settingsRepository

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
    val cameraState: StateFlow<CameraState> = printerRepo.cameraState
    val printerFilamentSlots: StateFlow<List<FilamentSlot>> = printerRepo.filamentSlots

    val activeNickname: StateFlow<String> = printerRepo.activeNickname

    val printerCount: StateFlow<Int> = printerRepo.printerCount

    val capabilities: StateFlow<PrinterTransportCapabilities> = printerRepo.capabilities

    /** Full list of configured printers — drives the switcher bottom sheet. */
    val printerList: StateFlow<List<Printer>> = printersRepo.config
        .map { it?.printers ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val activePrinterId: StateFlow<String?> = printersRepo.config
        .map { it?.activeId }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val activePrinter: StateFlow<Printer?> = printersRepo.activePrinter
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    // Resolved Moonraker webcam sources. Each source owns its snapshot URL fallbacks.
    // Populated by resolveWebcam() which queries /server/webcams/list.
    private val _webcamSelection = MutableStateFlow(WebcamSelection())
    val webcamSelection: StateFlow<WebcamSelection> = _webcamSelection.asStateFlow()
    @Deprecated("Use webcamSelection so source identity is retained")
    val webcamCandidates: StateFlow<List<String>> = _webcamSelection
        .map { it.selected?.snapshotUrls.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Kept for backward compat — primary candidate or empty
    val webcamSnapshotUrl: StateFlow<String> = _webcamSelection
        .map { it.selected?.snapshotUrls?.firstOrNull().orEmpty() }
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
    private var syncJob: Job? = null
    private var testConnectionJob: Job? = null
    private var sendJob: Job? = null
    private var switchPrinterJob: Job? = null
    private val switchPrinterMutex = Mutex()
    private val auxiliaryPrinterJobs = Collections.synchronizedSet(mutableSetOf<Job>())
    private val activePrinterGeneration = AtomicInteger(0)
    @Volatile private var preparedSendGeneration: Int? = null

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
        val newColor: String?,       // from printer (or catalogue match), null if printer slot unavailable
        val currentType: String,
        val newType: String?,
        val matchedSlug: String? = null,  // OpenPrintTag catalogue slug, when a confident match was found
        val matchedName: String? = null   // catalogue display name for the dialog, when matched
    )

    sealed class SyncState {
        object Idle : SyncState()
        object Loading : SyncState()
        data class Preview(
            val entries: List<SyncPreviewEntry>,
            val actionContext: PrinterActionContext,
        ) : SyncState()
        data class Error(val message: String) : SyncState()
    }

    data class PrinterActionContext(
        val generation: Int,
        val printerId: String?,
        val connectionFingerprint: String? = null,
    )

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    init {
        viewModelScope.launch {
            if (!settingsRepo.bambuBetaEnabled.first()) disableBambuMode()
        }
        libraryRepo.ensureLoaded(viewModelScope)
        printerRepo.startPolling(viewModelScope)
        // Resolve webcam URLs for the already-saved printer URL (if any)
        viewModelScope.launch(Dispatchers.IO) {
            val context = capturePrinterActionContext(activePrinterGeneration.get())
            resolveWebcam(context)
        }
        // Auto-clear the "Print started!" banner once the printer confirms printing.
        // Also sync LED state on first connection (pollLedState only runs via testConnection otherwise).
        viewModelScope.launch {
            var wasConnected = false
            status.collect { s ->
                if (_sendingState.value is SendingState.PrintStarted && s.isPrinting) {
                    _sendingState.value = SendingState.Idle
                }
                if (shouldPollLedOnConnectionEdge(wasConnected = wasConnected, isConnected = s.isConnected)) {
                    val requestedGeneration = activePrinterGeneration.get()
                    launch(Dispatchers.IO) {
                        val context = capturePrinterActionContext(requestedGeneration)
                        if (isCurrentPrinterAction(context)) pollLedState(context)
                    }
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
        invalidateActivePrinterOperations()
        viewModelScope.launch(Dispatchers.IO) {
            printerRepo.prepareForActivePrinterSwitch()
            printerRepo.updateActiveUrl(url)
            _connectionState.value = ConnectionState.Unknown
            val context = capturePrinterActionContext(activePrinterGeneration.get())
            resolveWebcam(context)
        }
    }

    fun switchActivePrinter(id: String) {
        // Invalidate synchronously so a send confirmed in the same UI frame
        // cannot start against the printer being left behind. The persisted
        // config remains the authority for deciding whether the id is valid.
        invalidateActivePrinterOperations()
        switchPrinterJob?.cancel()
        switchPrinterJob = viewModelScope.launch {
            switchPrinterMutex.withLock {
                // Read the repository's current value rather than UI-facing StateFlows,
                // which may briefly be stale while the switch sheet is being dismissed.
                val config = printersRepo.config.first() ?: return@withLock
                if (!shouldSwitchActivePrinter(config, id)) return@withLock
                val target = config.printers.first { it.id == id }
                printerRepo.prepareForActivePrinterSwitch()
                printersRepo.setActive(id)
                if (target.kind == PrinterKind.MOONRAKER) {
                    withTimeoutOrNull(3_000) { printerRepo.printerUrl.first { it.isNotBlank() } }
                    val context = capturePrinterActionContext(activePrinterGeneration.get())
                    resolveWebcam(context)
                }
            }
        }
    }

    /** Debug/E2E-only target setup. Uses a reserved non-routable address. */
    fun setBambuE2ETarget(modelName: String) {
        val model = runCatching { BambuModel.valueOf(modelName.uppercase(Locale.US)) }.getOrNull()
            ?: run {
                Log.w("PrinterViewModel", "Unknown Bambu E2E model '$modelName'")
                return
            }
        viewModelScope.launch {
            val current = printersRepo.config.first()
            val existing = current?.printers?.firstOrNull {
                it.kind == PrinterKind.BAMBU_LAN && it.bambu?.let { bambu ->
                    bambu.ip == "192.0.2.1" && bambu.model == model
                } == true
            }
            val target = existing ?: Printer(
                id = java.util.UUID.randomUUID().toString(),
                nickname = "E2E Bambu ${model.name}",
                kind = PrinterKind.BAMBU_LAN,
                bambu = BambuConfig(
                    ip = "192.0.2.1",
                    accessCode = "00000000",
                    serial = "E2E${model.name}",
                    model = model,
                ),
                extruderPresets = emptyList(),
            ).also { printersRepo.add(it) }
            printerRepo.prepareForActivePrinterSwitch()
            printersRepo.setActive(target.id)
            Log.i("PrinterViewModel", "Bambu E2E target active: ${model.name}")
        }
    }

    /** Debug/E2E-only U1 target setup. Uses a reserved non-routable address. */
    fun setU1E2ETarget() {
        viewModelScope.launch {
            val current = printersRepo.config.first()
            val existing = current?.printers?.firstOrNull {
                it.kind == PrinterKind.MOONRAKER && it.moonrakerUrl.contains("192.0.2.2")
            }
            val target = existing ?: Printer(
                id = java.util.UUID.randomUUID().toString(),
                nickname = "E2E U1",
                kind = PrinterKind.MOONRAKER,
                moonrakerUrl = "http://192.0.2.2",
                bambu = null,
                extruderPresets = defaultExtruderPresets(),
            ).also { printersRepo.add(it) }
            printerRepo.prepareForActivePrinterSwitch()
            printersRepo.setActive(target.id)
            Log.i("PrinterViewModel", "U1 E2E target active")
        }
    }

    fun disableBambuMode() {
        viewModelScope.launch {
            val config = printersRepo.config.first() ?: return@launch
            val fallback = config.printers.firstOrNull { it.kind != PrinterKind.BAMBU_LAN }
            if (fallback != null && config.activeId != fallback.id) {
                printerRepo.prepareForActivePrinterSwitch()
                printersRepo.setActive(fallback.id)
            }
        }
    }

    fun addPrinter(
        nickname: String,
        kind: PrinterKind,
        url: String,
        bambuIp: String = "",
        bambuAccessCode: String = "",
        bambuSerial: String = "",
        bambuModel: BambuModel = BambuModel.P1S,
    ) {
        viewModelScope.launch {
            val printer = buildPrinter(
                id = java.util.UUID.randomUUID().toString(),
                fallbackNickname = "Printer ${(printerList.value.size + 1)}",
                existingExtruderPresets = defaultExtruderPresets(),
                nickname = nickname,
                kind = kind,
                url = url,
                bambuIp = bambuIp,
                bambuAccessCode = bambuAccessCode,
                bambuSerial = bambuSerial,
                bambuModel = bambuModel,
            )
            printersRepo.add(printer)
        }
    }

    fun updatePrinter(
        id: String,
        nickname: String,
        kind: PrinterKind,
        url: String,
        bambuIp: String = "",
        bambuAccessCode: String = "",
        bambuSerial: String = "",
        bambuModel: BambuModel = BambuModel.P1S,
    ) {
        viewModelScope.launch {
            val current = printerList.value.firstOrNull { it.id == id } ?: return@launch
            val updated = buildPrinter(
                    id = current.id,
                    fallbackNickname = current.nickname,
                    existingExtruderPresets = current.extruderPresets,
                    nickname = nickname,
                    kind = kind,
                    url = url,
                    bambuIp = bambuIp,
                    bambuAccessCode = bambuAccessCode,
                    bambuSerial = bambuSerial,
                    bambuModel = bambuModel,
                    selectedWebcamUid = current.selectedWebcamUid.takeIf {
                        current.kind == PrinterKind.MOONRAKER &&
                            kind == PrinterKind.MOONRAKER &&
                            com.u1.slicer.network.MoonrakerClient.normalizeUrl(current.moonrakerUrl) ==
                                com.u1.slicer.network.MoonrakerClient.normalizeUrl(url)
                    },
                )
            val activeId = printersRepo.config.first()?.activeId
            if (id == activeId && printerConnectionFingerprint(current) != printerConnectionFingerprint(updated)) {
                invalidateActivePrinterOperations()
                printerRepo.prepareForActivePrinterSwitch()
            }
            printersRepo.update(updated)
        }
    }

    fun deletePrinter(id: String) {
        viewModelScope.launch {
            try {
                if (printersRepo.config.first()?.activeId == id) {
                    invalidateActivePrinterOperations()
                    printerRepo.prepareForActivePrinterSwitch()
                }
                printersRepo.delete(id)
            } catch (e: IllegalStateException) {
                _heaterError.value = e.message ?: "Cannot delete printer"
            }
        }
    }

    private suspend fun resolveWebcam(actionContext: PrinterActionContext) {
        val sources = printerRepo.queryWebcamSources()
        if (isCurrentPrinterAction(actionContext)) {
            val preferredUid = printersRepo.config.first()?.active?.selectedWebcamUid
            _webcamSelection.value = WebcamSelection.resolve(sources, preferredUid)
        }
    }

    /** Persist the active Moonraker printer's selected camera by its stable UID. */
    fun selectWebcam(uid: String) {
        viewModelScope.launch {
            val selectedPrinterId = printersRepo.config.first()?.activeId ?: return@launch
            val selection = _webcamSelection.value
            val source = selection.sources.firstOrNull { it.uid == uid } ?: return@launch
            if (source.isLegacyFallback) return@launch
            val config = printersRepo.config.first() ?: return@launch
            val active = config.active
            if (active.id != selectedPrinterId || active.kind != PrinterKind.MOONRAKER) return@launch
            printersRepo.update(active.copy(selectedWebcamUid = uid))
            _webcamSelection.value = WebcamSelection.resolve(selection.sources, uid)
        }
    }

    /** Returns the URL for the paxx12 extended-firmware remote screen, or null. */
    fun remoteScreenUrl(): String? = printerRepo.remoteScreenUrl()

    fun testConnection() {
        testConnectionJob?.cancel()
        _connectionState.value = ConnectionState.Testing
        val requestedGeneration = activePrinterGeneration.get()
        testConnectionJob = viewModelScope.launch(Dispatchers.IO) {
            val actionContext = capturePrinterActionContext(requestedGeneration)
            if (!isCurrentPrinterAction(actionContext)) return@launch
            val error = printerRepo.testConnection()
            if (!isCurrentPrinterAction(actionContext)) return@launch
            _connectionState.value = if (error == null) ConnectionState.Connected
                                     else ConnectionState.Failed(error)
            if (error == null) {
                pollLedState(actionContext)
                if (!isCurrentPrinterAction(actionContext)) return@launch
                _remoteScreenAvailable.value = printerRepo.probeRemoteScreen()
                if (!isCurrentPrinterAction(actionContext)) return@launch
                resolveWebcam(actionContext)
            } else {
                _remoteScreenAvailable.value = false
            }
        }
    }

    fun toggleLight() {
        launchBoundPrinterAction { actionContext ->
            val current = _isLightOn.value ?: false
            val success = printerRepo.setLed(!current)
            if (success && isCurrentPrinterAction(actionContext)) _isLightOn.value = !current
        }
    }

    private suspend fun pollLedState(actionContext: PrinterActionContext) {
        val state = printerRepo.getLedState()
        if (isCurrentPrinterAction(actionContext)) _isLightOn.value = state
    }

    /** F78: writes back into the active printer's extruderPresets list. */
    fun updateExtruderPreset(preset: ExtruderPreset) {
        launchBoundPrinterAction { actionContext ->
            val cfg = printersRepo.config.first() ?: return@launchBoundPrinterAction
            val active = cfg.active
            val updated = active.extruderPresets.map { if (it.index == preset.index) preset else it }
            if (!isCurrentPrinterAction(actionContext)) return@launchBoundPrinterAction
            printersRepo.update(active.copy(extruderPresets = updated))
        }
    }

    fun syncFilaments() {
        syncJob?.cancel()
        _syncState.value = SyncState.Loading
        val requestedGeneration = activePrinterGeneration.get()
        syncJob = viewModelScope.launch(Dispatchers.IO) {
            val actionContext = capturePrinterActionContext(requestedGeneration)
            if (!isCurrentPrinterAction(actionContext)) return@launch
            val slots = printerRepo.queryFilamentSlots()
            if (!isCurrentPrinterAction(actionContext)) return@launch
            if (slots == null || slots.isEmpty()) {
                _syncState.value = SyncState.Error("No filament data available from printer")
                return@launch
            }
            val active = printersRepo.config.first()?.active
            val library = (libraryState.value as? com.u1.slicer.data.LibraryState.Ready)?.library
            val entries = buildSyncPreviewEntries(
                presets = extruderPresets.value,
                slots = slots,
                library = library,
                includeAllPrinterSlots = active?.kind == PrinterKind.BAMBU_LAN,
            )
            if (active?.kind == PrinterKind.BAMBU_LAN) {
                BambuDiagnostics.record(
                    "filament_sync_preview",
                    active.bambu,
                    mapOf(
                        "slotCount" to slots.size,
                        "loadedSlotCount" to slots.count { it.loaded },
                        "amsHtSlotCount" to slots.count { it.index in 128..253 },
                        "externalSlotCount" to slots.count { it.index >= 254 },
                    ),
                )
            }
            if (!isCurrentPrinterAction(actionContext)) return@launch
            _syncState.value = SyncState.Preview(entries, actionContext)
        }
    }

    /** Apply the sync result — update presets with printer data as requested. */
    fun applySyncResult(
        preview: SyncState.Preview,
        applyColors: Boolean,
        applyTypes: Boolean,
        importMatchedProfiles: Boolean = false,
    ) {
        _syncState.value = SyncState.Idle
        viewModelScope.launch {
            if (!isCurrentPrinterAction(preview.actionContext)) return@launch
            val cfg = printersRepo.config.first() ?: return@launch
            val active = cfg.active
            val library = (libraryState.value as? com.u1.slicer.data.LibraryState.Ready)?.library
            val linkedProfileIds = if (importMatchedProfiles && library != null) {
                withContext(Dispatchers.IO) {
                    preview.entries.mapNotNull { entry ->
                        val slug = entry.matchedSlug ?: return@mapNotNull null
                        val catalogueEntry = library.entry(slug) ?: return@mapNotNull null
                        entry.slotIndex to upsertLibraryProfile(filamentDao, catalogueEntry)
                    }.toMap()
                }
            } else emptyMap()
            val current = applySyncPreviewEntries(
                presets = active.extruderPresets,
                entries = preview.entries,
                applyColors = applyColors,
                applyTypes = applyTypes,
                linkedProfileIds = linkedProfileIds,
            )
            if (!isCurrentPrinterAction(preview.actionContext)) return@launch
            printersRepo.update(active.copy(extruderPresets = current))
            // Record catalogue recents for slots whose matched filament was applied,
            // so the library surfaces them in search/recents next time.
            if (applyColors || applyTypes || importMatchedProfiles) {
                preview.entries.mapNotNull { it.matchedSlug }.forEach { slug ->
                    libraryRepo.recordRecent(slug)
                }
            }
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
        preparedSendGeneration = activePrinterGeneration.get()
        _sendingState.value = SendingState.Preparing
    }

    /** F94 — surface a send/prep failure on the Printer screen (prevents a stuck banner). */
    fun reportSendError(message: String) {
        val generation = preparedSendGeneration
        if (generation != null && generation != activePrinterGeneration.get()) return
        preparedSendGeneration = null
        _sendingState.value = SendingState.Error(message)
    }

    /**
     * Phase 2 B.1 (2026-04-28) — accepts only [com.u1.slicer.gcode.PhysicalGcodePath].
     * Callers must apply [com.u1.slicer.gcode.applyPrintTimeRemap] (or
     * confirm the source is already in physical-slot space) before
     * reaching this function. The compiler enforces it.
     */
    fun sendAndPrint(physical: com.u1.slicer.gcode.PhysicalGcodePath, modelName: String? = null) {
        sendJob?.cancel()
        _sendingState.value = SendingState.Uploading
        val requestedGeneration = consumePreparedSendGeneration()
        sendJob = viewModelScope.launch(Dispatchers.IO) {
            val actionContext = capturePrinterActionContext(requestedGeneration)
            if (!isCurrentPrinterAction(actionContext)) return@launch
            // Hold a foreground service across the upload. A 272 MB G-code can take
            // minutes over WAN; without this the cached-app freezer suspends the
            // process the moment the user backgrounds the app, truncating the
            // upload to the printer.
            val ctx = getApplication<Application>()
            com.u1.slicer.LongOpService.start(ctx, "Uploading to printer")
            try {
                val file = physical.toFile()
                if (!file.exists()) {
                    if (!isCurrentPrinterAction(actionContext)) return@launch
                    _sendingState.value = SendingState.Error("G-code file not found")
                    return@launch
                }
                // F84: prefer the original model name over the on-disk gcode name
                // so the printer's file browser shows distinct, recognisable jobs.
                val filename = PrinterRepository.resolveUploadBaseName(modelName, file.name)
                val result = printerRepo.uploadAndPrint(file, filename)
                if (!isCurrentPrinterAction(actionContext)) return@launch
                _sendingState.value = when (result) {
                    TransportCommandResult.Success -> SendingState.PrintStarted
                    is TransportCommandResult.Unsupported -> SendingState.Error(result.reason)
                    is TransportCommandResult.Failure -> SendingState.Error(result.reason)
                }
            } catch (error: Exception) {
                handleUnexpectedSendFailure(error, actionContext, "Printer upload failed")
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
        sendJob?.cancel()
        _sendingState.value = SendingState.Uploading
        val requestedGeneration = consumePreparedSendGeneration()
        sendJob = viewModelScope.launch(Dispatchers.IO) {
            val actionContext = capturePrinterActionContext(requestedGeneration)
            if (!isCurrentPrinterAction(actionContext)) return@launch
            // Foreground service across the upload — see sendAndPrint. A truncated
            // upload-only would leave a corrupt G-code on the printer's storage.
            val ctx = getApplication<Application>()
            com.u1.slicer.LongOpService.start(ctx, "Uploading to printer")
            try {
                val file = physical.toFile()
                if (!file.exists()) {
                    if (!isCurrentPrinterAction(actionContext)) return@launch
                    _sendingState.value = SendingState.Error("G-code file not found")
                    return@launch
                }
                val filename = PrinterRepository.resolveUploadBaseName(modelName, file.name)
                val result = printerRepo.uploadOnly(file, filename)
                if (!isCurrentPrinterAction(actionContext)) return@launch
                _sendingState.value = when (result) {
                    TransportCommandResult.Success -> SendingState.UploadComplete
                    is TransportCommandResult.Unsupported -> SendingState.Error(result.reason)
                    is TransportCommandResult.Failure -> SendingState.Error(result.reason)
                }
                if (result is TransportCommandResult.Success) {
                    com.u1.slicer.AppEventNotifier.notify(
                        getApplication(),
                        com.u1.slicer.AppEventNotifier.Event.UploadComplete(filename)
                    )
                }
            } catch (error: Exception) {
                handleUnexpectedSendFailure(error, actionContext, "Bambu project upload failed")
            } finally {
                com.u1.slicer.LongOpService.stop(ctx)
            }
        }
    }

    fun pausePrint() {
        launchBoundPrinterAction { printerRepo.pausePrint() }
    }

    fun resumePrint() {
        launchBoundPrinterAction { printerRepo.resumePrint() }
    }

    fun cancelPrint() {
        launchBoundPrinterAction { printerRepo.cancelPrint() }
    }

    fun skipObject(name: String) {
        launchBoundPrinterAction { actionContext ->
            printerRepo.sendGcode("EXCLUDE_OBJECT NAME=$name")
            if (isCurrentPrinterAction(actionContext)) _skippedObjects.update { it + name }
        }
    }

    fun setHeaterTemperature(heater: String, targetC: Int) {
        launchBoundPrinterAction { actionContext ->
            val ok = printerRepo.setHeaterTemperature(heater, targetC)
            if (!ok && isCurrentPrinterAction(actionContext)) _heaterError.value = "Could not update temperature"
        }
    }

    // F82: idle-state printer controls — temperature-only set, no head motion.
    // `TURN_OFF_HEATERS` is the standard Klipper macro that drops bed + every
    // extruder to 0 in one call. Safe to send when idle, printing, or paused.
    fun cooldownAll() {
        launchBoundPrinterAction { actionContext ->
            val ok = printerRepo.sendGcode("TURN_OFF_HEATERS")
            if (!ok && isCurrentPrinterAction(actionContext)) _heaterError.value = "Could not send cooldown"
        }
    }

    // F82: arbitrary G-code line from the user-typed custom-G-code box. User
    // owns the risk (movements, raw heater commands, etc); the UI displays a
    // warning beside the field. Empty / whitespace-only input is rejected.
    fun sendCustomGcode(script: String) {
        val trimmed = sanitizeCustomGcode(script) ?: return
        launchBoundPrinterAction { actionContext ->
            val ok = printerRepo.sendGcode(trimmed)
            if (!ok && isCurrentPrinterAction(actionContext)) _heaterError.value = "Could not send G-code"
        }
    }

    fun clearSendingState() {
        _sendingState.value = SendingState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        cancelActivePrinterJobs()
        stopCameraKeepalive()
        printerRepo.stopPolling()
    }

    companion object {
        internal fun shouldSwitchActivePrinter(config: PrintersConfig, requestedId: String): Boolean =
            config.activeId != requestedId && config.printers.any { it.id == requestedId }

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

        internal fun shouldApplyPrinterActionResult(
            started: PrinterActionContext,
            currentGeneration: Int,
            currentPrinterId: String?,
            currentConnectionFingerprint: String? = null,
        ): Boolean = started.generation == currentGeneration &&
            started.printerId == currentPrinterId &&
            started.connectionFingerprint == currentConnectionFingerprint

        internal fun printerConnectionFingerprint(printer: Printer?): String? = when (printer?.kind) {
            PrinterKind.MOONRAKER -> listOf(
                printer.id,
                printer.kind.name,
                com.u1.slicer.network.MoonrakerClient.normalizeUrl(printer.moonrakerUrl),
            ).joinToString("|")
            PrinterKind.BAMBU_LAN -> printer.bambu?.let { bambu ->
                listOf(
                    printer.id,
                    printer.kind.name,
                    bambu.ip.trim(),
                    bambu.serial.trim().uppercase(Locale.ROOT),
                    bambu.accessCode.trim(),
                    bambu.model.name,
                ).joinToString("|")
            }
            null -> null
        }

        internal fun buildPrinter(
            id: String,
            fallbackNickname: String,
            existingExtruderPresets: List<ExtruderPreset>,
            nickname: String,
            kind: PrinterKind,
            url: String,
            bambuIp: String,
            bambuAccessCode: String,
            bambuSerial: String,
            bambuModel: BambuModel,
            selectedWebcamUid: String? = null,
        ): Printer {
            val resolvedNickname = nickname.ifBlank { fallbackNickname }
            return when (kind) {
                PrinterKind.MOONRAKER -> Printer(
                    id = id,
                    nickname = resolvedNickname,
                    kind = PrinterKind.MOONRAKER,
                    moonrakerUrl = com.u1.slicer.network.MoonrakerClient.normalizeUrl(url),
                    bambu = null,
                    extruderPresets = existingExtruderPresets,
                    selectedWebcamUid = selectedWebcamUid,
                )
                PrinterKind.BAMBU_LAN -> Printer(
                    id = id,
                    nickname = resolvedNickname,
                    kind = PrinterKind.BAMBU_LAN,
                    moonrakerUrl = "",
                    bambu = BambuConfig(
                        ip = bambuIp.trim(),
                        accessCode = bambuAccessCode.trim(),
                        serial = bambuSerial.trim().uppercase(Locale.ROOT),
                        model = bambuModel,
                    ),
                    extruderPresets = existingExtruderPresets,
                    selectedWebcamUid = null,
                )
            }
        }
    }

    fun sendBambuProjectAndPrint(
        projectFile: File,
        modelName: String? = null,
        plateId: Int,
        amsMapping: List<Int>,
        useAms: Boolean = true,
    ) {
        sendJob?.cancel()
        _sendingState.value = SendingState.Uploading
        val requestedGeneration = consumePreparedSendGeneration()
        sendJob = viewModelScope.launch(Dispatchers.IO) {
            val actionContext = capturePrinterActionContext(requestedGeneration)
            if (!isCurrentPrinterAction(actionContext)) return@launch
            val ctx = getApplication<Application>()
            com.u1.slicer.LongOpService.start(ctx, "Uploading Bambu project")
            try {
                if (!projectFile.exists()) {
                    if (!isCurrentPrinterAction(actionContext)) return@launch
                    _sendingState.value = SendingState.Error("Bambu project file not found")
                    return@launch
                }
                val filename = PrinterRepository.buildBambuProjectUploadFilename(
                    modelName?.takeIf { it.isNotBlank() } ?: projectFile.name,
                )
                val result = printerRepo.uploadAndPrintBambuProject(
                    projectFile = projectFile,
                    remoteName = filename,
                    plateId = plateId,
                    amsMapping = amsMapping,
                    useAms = useAms,
                )
                if (!isCurrentPrinterAction(actionContext)) return@launch
                _sendingState.value = when (result) {
                    TransportCommandResult.Success -> SendingState.PrintStarted
                    is TransportCommandResult.Unsupported -> SendingState.Error(result.reason)
                    is TransportCommandResult.Failure -> SendingState.Error(result.reason)
                }
            } catch (error: Exception) {
                handleUnexpectedSendFailure(error, actionContext, "Bambu project upload failed")
            } finally {
                com.u1.slicer.LongOpService.stop(ctx)
            }
        }
    }

    fun sendBambuProjectUploadOnly(projectFile: File, modelName: String? = null) {
        sendJob?.cancel()
        _sendingState.value = SendingState.Uploading
        val requestedGeneration = consumePreparedSendGeneration()
        sendJob = viewModelScope.launch(Dispatchers.IO) {
            val actionContext = capturePrinterActionContext(requestedGeneration)
            if (!isCurrentPrinterAction(actionContext)) return@launch
            val ctx = getApplication<Application>()
            com.u1.slicer.LongOpService.start(ctx, "Uploading Bambu project")
            try {
                if (!projectFile.exists()) {
                    if (!isCurrentPrinterAction(actionContext)) return@launch
                    _sendingState.value = SendingState.Error("Bambu project file not found")
                    return@launch
                }
                val filename = PrinterRepository.buildBambuProjectUploadFilename(
                    modelName?.takeIf { it.isNotBlank() } ?: projectFile.name,
                )
                val result = printerRepo.uploadOnlyBambuProject(projectFile, filename)
                if (!isCurrentPrinterAction(actionContext)) return@launch
                _sendingState.value = when (result) {
                    TransportCommandResult.Success -> SendingState.UploadComplete
                    is TransportCommandResult.Unsupported -> SendingState.Error(result.reason)
                    is TransportCommandResult.Failure -> SendingState.Error(result.reason)
                }
                if (result is TransportCommandResult.Success) {
                    com.u1.slicer.AppEventNotifier.notify(
                        getApplication(),
                        com.u1.slicer.AppEventNotifier.Event.UploadComplete(filename)
                    )
                }
            } catch (error: Exception) {
                handleUnexpectedSendFailure(error, actionContext, "Printer upload failed")
            } finally {
                com.u1.slicer.LongOpService.stop(ctx)
            }
        }
    }

    private suspend fun capturePrinterActionContext(
        generation: Int = activePrinterGeneration.get(),
    ): PrinterActionContext {
        // Send actions can be launched immediately after navigating to Printer.
        // The UI-facing state flow is lazy and may still contain its initial null
        // value at that point, so use the persisted selection for this guard.
        val config = printersRepo.config.first()
        return PrinterActionContext(
            generation = generation,
            printerId = config?.activeId,
            connectionFingerprint = printerConnectionFingerprint(config?.active),
        )
    }

    private suspend fun isCurrentPrinterAction(actionContext: PrinterActionContext): Boolean {
        val config = printersRepo.config.first()
        return shouldApplyPrinterActionResult(
            started = actionContext,
            currentGeneration = activePrinterGeneration.get(),
            currentPrinterId = config?.activeId,
            currentConnectionFingerprint = printerConnectionFingerprint(config?.active),
        )
    }

    private fun consumePreparedSendGeneration(): Int =
        (preparedSendGeneration ?: activePrinterGeneration.get()).also {
            preparedSendGeneration = null
        }

    private suspend fun handleUnexpectedSendFailure(
        error: Exception,
        actionContext: PrinterActionContext,
        fallbackMessage: String,
    ) {
        if (error is kotlinx.coroutines.CancellationException &&
            error !is kotlinx.coroutines.TimeoutCancellationException
        ) {
            throw error
        }
        if (isCurrentPrinterAction(actionContext)) {
            _sendingState.value = SendingState.Error(error.message ?: fallbackMessage)
        }
    }

    private fun launchBoundPrinterAction(
        block: suspend (PrinterActionContext) -> Unit,
    ) {
        val requestedGeneration = activePrinterGeneration.get()
        lateinit var job: Job
        job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val actionContext = capturePrinterActionContext(requestedGeneration)
            if (!isCurrentPrinterAction(actionContext)) return@launch
            block(actionContext)
        }
        auxiliaryPrinterJobs += job
        job.invokeOnCompletion { auxiliaryPrinterJobs -= job }
        job.start()
    }

    private fun invalidateActivePrinterOperations() {
        activePrinterGeneration.incrementAndGet()
        preparedSendGeneration = null
        cancelActivePrinterJobs()
        _connectionState.value = ConnectionState.Unknown
        _syncState.value = SyncState.Idle
        _sendingState.value = SendingState.Idle
        _remoteScreenAvailable.value = false
        _webcamSelection.value = WebcamSelection()
        _isLightOn.value = null
    }

    private fun cancelActivePrinterJobs() {
        syncJob?.cancel()
        syncJob = null
        testConnectionJob?.cancel()
        testConnectionJob = null
        sendJob?.cancel()
        sendJob = null
        synchronized(auxiliaryPrinterJobs) {
            auxiliaryPrinterJobs.toList().forEach(Job::cancel)
            auxiliaryPrinterJobs.clear()
        }
    }
}
