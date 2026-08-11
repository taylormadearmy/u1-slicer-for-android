package com.u1.slicer.printer

import com.u1.slicer.AppEventNotifier
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrinterKind
import com.u1.slicer.network.FilamentSlot
import com.u1.slicer.network.MoonrakerClient
import com.u1.slicer.network.PrinterStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PrinterRepository(
    private val appContext: android.content.Context,
    private val printersRepo: com.u1.slicer.data.PrintersRepository,
    private val transportFactory: PrinterTransportFactory,
) {
    private val _status = MutableStateFlow(PrinterStatus(state = "disconnected", progress = 0f))
    val status: StateFlow<PrinterStatus> = _status.asStateFlow()

    private val _printerUrl = MutableStateFlow("")
    val printerUrl: StateFlow<String> = _printerUrl.asStateFlow()

    /** Active printer's nickname used to prefix notification titles. */
    private val _activeNickname = MutableStateFlow("")
    val activeNickname: StateFlow<String> = _activeNickname.asStateFlow()

    /** Total configured printer count used to decide whether to prefix notifications. */
    private val _printerCount = MutableStateFlow(0)
    val printerCount: StateFlow<Int> = _printerCount.asStateFlow()

    private val _capabilities = MutableStateFlow(PrinterTransportCapabilities())
    val capabilities: StateFlow<PrinterTransportCapabilities> = _capabilities.asStateFlow()

    private val _cameraState = MutableStateFlow<CameraState>(CameraState.Disabled)
    val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    private val _filamentSlots = MutableStateFlow<List<FilamentSlot>>(emptyList())
    val filamentSlots: StateFlow<List<FilamentSlot>> = _filamentSlots.asStateFlow()

    private var activePrinter: Printer? = null
    @Volatile private var currentTransport: PrinterTransport? = null
    private var statusCollectionJob: Job? = null
    private var cameraCollectionJob: Job? = null
    private var filamentCollectionJob: Job? = null
    private var pollingScope: CoroutineScope? = null
    private var lifecycleJob: Job? = null
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var consecutiveFailures = 0

    @Volatile
    private var previousEffectiveState = "disconnected"

    init {
        repositoryScope.launch {
            printersRepo.config.collectLatest { cfg ->
                val active = cfg?.active ?: return@collectLatest
                rebind(active)
                _activeNickname.value = active.nickname
                _printerCount.value = cfg.printers.size
            }
        }
    }

    private suspend fun rebind(newPrinter: Printer) {
        val existingPrinter = activePrinter
        if (!shouldRecreateTransport(existingPrinter, newPrinter) && currentTransport != null) {
            activePrinter = newPrinter
            _activeNickname.value = newPrinter.nickname
            _printerUrl.value = when (newPrinter.kind) {
                PrinterKind.MOONRAKER -> MoonrakerClient.normalizeUrl(newPrinter.moonrakerUrl)
                PrinterKind.BAMBU_LAN -> newPrinter.bambu?.ip.orEmpty()
            }
            return
        }
        val wasPolling = pollingScope != null
        val scope = pollingScope
        lifecycleJob?.cancelAndJoin()
        stopPollingInternal(currentTransport)
        activePrinter = newPrinter
        currentTransport = transportFactory.create(newPrinter)
        consecutiveFailures = 0
        previousEffectiveState = "disconnected"
        _printerUrl.value = when (newPrinter.kind) {
            PrinterKind.MOONRAKER -> MoonrakerClient.normalizeUrl(newPrinter.moonrakerUrl)
            PrinterKind.BAMBU_LAN -> newPrinter.bambu?.ip.orEmpty()
        }
        _capabilities.value = currentTransport?.capabilities ?: PrinterTransportCapabilities()
        _status.value = PrinterStatus(state = "disconnected", progress = 0f)
        _cameraState.value = CameraState.Disabled
        _filamentSlots.value = emptyList()
        if (wasPolling && scope != null) {
            startPolling(scope)
        }
    }

    /** Convenience: update the active printer's URL via PrintersRepository. */
    suspend fun updateActiveUrl(url: String) {
        val normalized = MoonrakerClient.normalizeUrl(url)
        val cfg = printersRepo.config.first() ?: return
        val active = cfg.active
        if (active.kind != PrinterKind.MOONRAKER) return
        printersRepo.update(active.copy(moonrakerUrl = normalized))
    }

    /** Returns null on success, or an error message string on failure. */
    suspend fun testConnection(): String? =
        currentTransport?.testConnection() ?: "No printer configured"

    /** Returns true if the paxx12 extended-firmware remote screen endpoint is reachable. */
    suspend fun probeRemoteScreen(): Boolean = currentTransport?.probeRemoteScreen() ?: false

    /** Returns the URL for the paxx12 extended-firmware remote screen, or null if unavailable. */
    fun remoteScreenUrl(): String? = currentTransport?.remoteScreenUrl()

    fun startPolling(scope: CoroutineScope) {
        pollingScope = scope
        val transport = currentTransport ?: return
        lifecycleJob?.cancel()
        lifecycleJob = scope.launch(Dispatchers.IO) {
            stopPollingInternal(transport)
            statusCollectionJob = scope.launch(Dispatchers.IO) {
                transport.status.collect { latestStatus ->
                    if (transport.capabilities.reportsFilamentSlotsWithStatus) {
                        transport.queryFilamentSlots()?.takeIf { it.isNotEmpty() }?.let { slots ->
                            _filamentSlots.value = slots
                        }
                    }
                    onStatusUpdated(latestStatus)
                }
            }
            cameraCollectionJob = scope.launch(Dispatchers.IO) {
                transport.cameraState.collect { latestCameraState ->
                    _cameraState.value = latestCameraState
                }
            }
            filamentCollectionJob = scope.launch(Dispatchers.IO) {
                transport.filamentSlots.collect { latestSlots ->
                    _filamentSlots.value = latestSlots
                }
            }
            transport.start(scope)
        }
    }

    fun stopPolling() {
        lifecycleJob?.cancel()
        val transport = currentTransport
        val scope = pollingScope
        val scopeJob = scope?.coroutineContext?.get(Job)
        if (scope != null && scopeJob?.isActive == true) {
            lifecycleJob = scope.launch(Dispatchers.IO) {
                stopPollingInternal(transport)
            }
        } else {
            repositoryScope.launch {
                stopPollingInternal(transport)
            }
        }
    }

    suspend fun prepareForActivePrinterSwitch() {
        // MQTT disconnect can block in vendor code.  Cancel the old work and
        // detach its collectors before changing the active printer; finish the
        // physical disconnect in the background so the selector always responds.
        lifecycleJob?.cancel()
        lifecycleJob = null
        val previousTransport = currentTransport
        stopPollingInternal(transport = null)
        currentTransport = null
        activePrinter = null
        consecutiveFailures = 0
        previousEffectiveState = "disconnected"
        _printerUrl.value = ""
        _capabilities.value = PrinterTransportCapabilities()
        _status.value = PrinterStatus(state = "disconnected", progress = 0f)
        previousTransport?.let { transport ->
            repositoryScope.launch {
                runCatching { transport.stop() }
            }
        }
    }

    private suspend fun stopPollingInternal(transport: PrinterTransport?) {
        statusCollectionJob?.cancelAndJoin()
        statusCollectionJob = null
        cameraCollectionJob?.cancelAndJoin()
        cameraCollectionJob = null
        filamentCollectionJob?.cancelAndJoin()
        filamentCollectionJob = null
        transport?.stop()
        _cameraState.value = CameraState.Disabled
        _filamentSlots.value = emptyList()
        PrintProgressNotifier.clear(appContext)
    }

    suspend fun uploadAndPrint(gcodeFile: java.io.File, filename: String): TransportCommandResult {
        val uploadName = buildPrinterUploadFilename(filename)
        val transport = currentTransport ?: return TransportCommandResult.Failure("No printer configured")
        when (val uploaded = transport.uploadJob(gcodeFile, uploadName)) {
            TransportCommandResult.Success -> Unit
            is TransportCommandResult.Unsupported -> return uploaded
            is TransportCommandResult.Failure -> return uploaded
        }
        currentCoroutineContext().ensureActive()
        if (currentTransport !== transport) {
            return TransportCommandResult.Failure("Active printer changed after upload; the print was not started")
        }
        return when (val started = transport.startJob(uploadName)) {
            TransportCommandResult.Success -> {
                transport.hintJobStarting()
                TransportCommandResult.Success
            }
            is TransportCommandResult.Unsupported -> started
            is TransportCommandResult.Failure -> started
        }
    }

    suspend fun uploadOnly(gcodeFile: java.io.File, filename: String): TransportCommandResult {
        val uploadName = buildPrinterUploadFilename(filename)
        return currentTransport?.uploadJob(gcodeFile, uploadName)
            ?: TransportCommandResult.Failure("No printer configured")
    }

    suspend fun uploadAndPrintBambuProject(
        projectFile: java.io.File,
        remoteName: String,
        plateId: Int,
        amsMapping: List<Int>,
        useAms: Boolean,
    ): TransportCommandResult {
        val transport = currentTransport ?: return TransportCommandResult.Failure("No printer configured")
        when (val validation = transport.validateStartProject()) {
            TransportCommandResult.Success -> Unit
            is TransportCommandResult.Unsupported -> return validation
            is TransportCommandResult.Failure -> return validation
        }
        when (val uploaded = transport.uploadJob(projectFile, remoteName)) {
            TransportCommandResult.Success -> Unit
            is TransportCommandResult.Unsupported -> return uploaded
            is TransportCommandResult.Failure -> return uploaded
        }
        currentCoroutineContext().ensureActive()
        if (currentTransport !== transport) {
            return TransportCommandResult.Failure("Active printer changed after upload; the print was not started")
        }
        return when (val started = transport.startProject(
            remoteName = remoteName,
            plateId = plateId,
            amsMapping = amsMapping,
            useAms = useAms,
            subtaskName = remoteName,
        )) {
            TransportCommandResult.Success -> {
                transport.hintJobStarting()
                TransportCommandResult.Success
            }
            is TransportCommandResult.Unsupported -> started
            is TransportCommandResult.Failure -> started
        }
    }

    suspend fun uploadOnlyBambuProject(
        projectFile: java.io.File,
        remoteName: String,
    ): TransportCommandResult {
        return currentTransport?.uploadJob(projectFile, remoteName)
            ?: TransportCommandResult.Failure("No printer configured")
    }

    suspend fun queryWebcamSnapshotCandidates(): List<String> =
        currentTransport?.queryWebcamSnapshotCandidates() ?: emptyList()

    suspend fun wakeCamera() {
        currentTransport?.wakeCamera()
    }

    suspend fun queryFilamentSlots(): List<FilamentSlot>? = currentTransport?.queryFilamentSlots()

    suspend fun pausePrint(): Boolean = currentTransport?.pauseJob().succeeded()
    suspend fun resumePrint(): Boolean = currentTransport?.resumeJob().succeeded()
    suspend fun cancelPrint(): Boolean = currentTransport?.cancelJob().succeeded()

    suspend fun sendGcode(gcode: String): Boolean = currentTransport?.sendGcode(gcode).succeeded()

    suspend fun getLedState(): Boolean? = currentTransport?.getLedState()
    suspend fun setLed(on: Boolean): Boolean = currentTransport?.setLed(on).succeeded()
    suspend fun setHeaterTemperature(heater: String, targetC: Int): Boolean =
        currentTransport?.setHeaterTemperature(heater, targetC).succeeded()

    private fun onStatusUpdated(latestStatus: PrinterStatus) {
        _status.value = latestStatus
        PrintProgressNotifier.update(appContext, latestStatus)
        val (effectiveState, newFailures) = applyGracePeriod(
            latestStatus.state,
            previousEffectiveState,
            consecutiveFailures,
        )
        consecutiveFailures = newFailures
        val event = detectTransition(
            previousEffectiveState,
            effectiveState,
            latestStatus.filename,
            latestStatus.progressPercent,
        )
        event?.let {
            AppEventNotifier.notify(
                appContext,
                it,
                nickname = _activeNickname.value,
                printerCount = _printerCount.value,
            )
        }
        previousEffectiveState = effectiveState
    }

    companion object {
        /** Number of consecutive "disconnected" polls required before PrinterOffline fires. */
        internal const val OFFLINE_GRACE_FAILURES = 3

        internal fun applyGracePeriod(
            rawState: String,
            prevState: String,
            consecutiveFailures: Int,
        ): Pair<String, Int> {
            return if (rawState == "disconnected") {
                val newCount = consecutiveFailures + 1
                val effectiveState = if (newCount >= OFFLINE_GRACE_FAILURES) rawState else prevState
                Pair(effectiveState, newCount)
            } else {
                Pair(rawState, 0)
            }
        }

        internal fun detectTransition(
            prev: String,
            curr: String,
            filename: String,
            progress: Int,
        ): AppEventNotifier.Event? {
            val activePrev = prev == "printing" || prev == "paused"
            return when {
                curr == "printing" && prev != "printing" ->
                    AppEventNotifier.Event.PrintStarted(filename)
                curr == "paused" && prev == "printing" ->
                    AppEventNotifier.Event.PrintPaused(filename, progress)
                curr == "complete" && activePrev ->
                    AppEventNotifier.Event.PrintComplete(filename)
                (curr == "error" || curr == "cancelled") && activePrev ->
                    AppEventNotifier.Event.PrintFailed(filename)
                curr == "disconnected" && activePrev ->
                    AppEventNotifier.Event.PrinterOffline
                else -> null
            }
        }

        internal fun resolveUploadBaseName(modelName: String?, gcodeFileName: String): String {
            return if (modelName != null && modelName.isNotBlank()) modelName else gcodeFileName
        }

        internal fun buildPrinterUploadFilename(
            sourceName: String,
            nowMillis: Long = System.currentTimeMillis(),
        ): String = buildUploadFilename(sourceName, nowMillis, ".gcode", "print")

        internal fun buildBambuProjectUploadFilename(
            sourceName: String,
            nowMillis: Long = System.currentTimeMillis(),
        ): String {
            var stem = sourceName
            while (true) {
                stem = when {
                    stem.endsWith(".gcode.3mf", ignoreCase = true) -> stem.dropLast(10)
                    stem.endsWith(".3mf", ignoreCase = true) -> stem.dropLast(4)
                    else -> break
                }
            }
            // The compound suffix is part of Bambu's executable-project
            // contract. A plain .3mf is treated as a model project by A-series
            // firmware and its project_file command can be silently ignored.
            return buildUploadFilename(stem, nowMillis, ".gcode.3mf", "project")
        }

        private fun buildUploadFilename(
            sourceName: String,
            nowMillis: Long,
            extension: String,
            fallbackBase: String,
        ): String {
            val base = sourceName
                .substringBeforeLast('.', sourceName)
                .replace(Regex("""[^A-Za-z0-9._-]+"""), "_")
                .replace(Regex("""_+"""), "_")
                .trim('_')
                .ifBlank { fallbackBase }
            return "${base}_$nowMillis$extension"
        }

        internal fun shouldRecreateTransport(current: Printer?, next: Printer): Boolean {
            if (current == null) return true
            if (current.kind != next.kind) return true
            return when (next.kind) {
                PrinterKind.MOONRAKER ->
                    MoonrakerClient.normalizeUrl(current.moonrakerUrl) !=
                        MoonrakerClient.normalizeUrl(next.moonrakerUrl)
                PrinterKind.BAMBU_LAN -> current.bambu != next.bambu
            }
        }

        private fun TransportCommandResult?.succeeded(): Boolean = this is TransportCommandResult.Success
    }
}
