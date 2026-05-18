package com.u1.slicer.printer

import com.u1.slicer.AppEventNotifier
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

    /**
     * Number of consecutive "disconnected" results from the printer.
     * PrinterOffline is only fired after OFFLINE_GRACE_FAILURES consecutive
     * failures, suppressing transient WiFi blips.
     */
    @Volatile
    private var consecutiveFailures = 0


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
            var prevState = "disconnected"
            while (isActive) {
                val latestStatus = client.getStatus()
                _status.value = latestStatus
                PrintProgressNotifier.update(appContext, latestStatus)
                // Grace-period logic: only surface "disconnected" to detectTransition
                // after OFFLINE_GRACE_FAILURES consecutive failures, so transient WiFi
                // blips don't immediately trigger a PrinterOffline notification.
                val (effectiveState, newFailures) = applyGracePeriod(
                    latestStatus.state, prevState, consecutiveFailures
                )
                consecutiveFailures = newFailures
                val event = detectTransition(
                    prevState, effectiveState,
                    latestStatus.filename, latestStatus.progressPercent
                )
                event?.let { AppEventNotifier.notify(appContext, it) }
                prevState = effectiveState
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

    suspend fun wakeCamera() = client.wakeCamera()

    suspend fun queryFilamentSlots(): List<FilamentSlot>? = client.queryFilamentSlots()

    suspend fun pausePrint(): Boolean = client.pausePrint()
    suspend fun resumePrint(): Boolean = client.resumePrint()
    suspend fun cancelPrint(): Boolean = client.cancelPrint()

    suspend fun sendGcode(gcode: String): Boolean = client.sendGcode(gcode)

    suspend fun getLedState(): Boolean? = client.getLedState()
    suspend fun setLed(on: Boolean): Boolean = client.setLed(on)
    suspend fun setHeaterTemperature(heater: String, targetC: Int): Boolean =
        client.setHeaterTemperature(heater, targetC)

    companion object {
        /** Number of consecutive "disconnected" polls required before PrinterOffline fires. */
        internal const val OFFLINE_GRACE_FAILURES = 3

        /**
         * Applies the grace-period rule to a raw poll result.
         *
         * Returns a pair of (effectiveState, updatedConsecutiveFailures).
         * If the printer reports "disconnected" but the failure count has not yet
         * reached [OFFLINE_GRACE_FAILURES], [prevState] is returned as the effective
         * state so that [detectTransition] does not fire [AppEventNotifier.Event.PrinterOffline].
         * Once the threshold is met the true "disconnected" state is passed through.
         * A non-disconnected result resets the failure counter to 0.
         */
        internal fun applyGracePeriod(
            rawState: String,
            prevState: String,
            consecutiveFailures: Int
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
            progress: Int
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

        /**
         * F84 (GitHub #138): pick the most user-meaningful base name for the
         * upload. Prefer the original model filename (e.g. `Jumping_frog.3mf`)
         * over the on-disk G-code name (`output.gcode`) so the printer's file
         * browser shows recognisable per-job names. Falls back to the gcode
         * name if the model name is null or blank.
         */
        internal fun resolveUploadBaseName(modelName: String?, gcodeFileName: String): String {
            return if (modelName != null && modelName.isNotBlank()) modelName else gcodeFileName
        }

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
