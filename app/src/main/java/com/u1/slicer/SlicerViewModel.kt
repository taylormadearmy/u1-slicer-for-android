package com.u1.slicer

import android.app.ActivityManager
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Debug
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.u1.slicer.bambu.BambuSanitizer
import com.u1.slicer.bambu.ProfileEmbedder
import com.u1.slicer.bambu.ThreeMfInfo
import com.u1.slicer.bambu.ThreeMfParser
import com.u1.slicer.data.FilamentProfile
import org.json.JSONObject
import com.u1.slicer.gcode.GcodeParser
import com.u1.slicer.gcode.GcodeThumbnailInjector
import com.u1.slicer.gcode.GcodeToolRemapper
import com.u1.slicer.gcode.GcodeValidator
import com.u1.slicer.gcode.LayerToolPauseInjector
import com.u1.slicer.gcode.ParsedGcode

import com.u1.slicer.data.ModelInfo
import com.u1.slicer.data.OverrideMode
import com.u1.slicer.data.OverrideValue
import com.u1.slicer.data.PlateType
import com.u1.slicer.data.SettingsBackup
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.data.SliceJob
import com.u1.slicer.data.SliceResult
import com.u1.slicer.data.SlicingOverrides
import com.u1.slicer.model.CopyArrangeCalculator
import com.u1.slicer.data.ExtruderPreset
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

class SlicerViewModel(application: Application) : AndroidViewModel(application) {

    private data class SliceOutputValidation(
        val parsedGcode: ParsedGcode?,
        val summary: Map<String, Any?>,
        val errorMessage: String?
    )

    private data class ExpectedModelFootprint(
        val minX: Float,
        val maxX: Float,
        val minY: Float,
        val maxY: Float,
        val instanceCount: Int
    )

    private val native = NativeLibrary()
    private val diagnostics = DiagnosticsStore(application)
    private val container = (application as U1SlicerApplication).container
    private val settingsRepo = container.settingsRepository
    private val filamentDao = container.filamentDao
    private val sliceJobDao = container.sliceJobDao
    private val profileEmbedder by lazy { ProfileEmbedder(getApplication()) }

    /** Debug-only: set by MainActivity to wire TestCommandReceiver navigation. */
    @Volatile var setNavigateCallback: ((((String) -> Unit)) -> Unit)? = null

    // ---- UI State ----
    sealed class SlicerState {
        object Idle : SlicerState()
        data class Loading(val message: String) : SlicerState()
        data class ModelLoaded(val info: ModelInfo) : SlicerState()
        data class Slicing(val progress: Int, val stage: String) : SlicerState()
        data class SliceComplete(val result: SliceResult) : SlicerState()
        data class Error(val message: String) : SlicerState()
    }

    /** Return to ModelLoaded state so the user can adjust settings and re-slice. */
    fun backToModelLoaded() {
        val info = lastModelInfo ?: return
        _state.value = SlicerState.ModelLoaded(info)
    }

    private val _state = MutableStateFlow<SlicerState>(SlicerState.Idle)
    val state: StateFlow<SlicerState> = _state.asStateFlow()

    private val _config = MutableStateFlow(SliceConfig())
    val config: StateFlow<SliceConfig> = _config.asStateFlow()

    private val _coreVersion = MutableStateFlow("")
    val coreVersion: StateFlow<String> = _coreVersion.asStateFlow()

    private val _gcodePreview = MutableStateFlow("")
    val gcodePreview: StateFlow<String> = _gcodePreview.asStateFlow()

    private val _parsedGcode = MutableStateFlow<ParsedGcode?>(null)
    val parsedGcode: StateFlow<ParsedGcode?> = _parsedGcode.asStateFlow()

    // Bambu / multi-plate state
    private val _threeMfInfo = MutableStateFlow<ThreeMfInfo?>(null)
    val threeMfInfo: StateFlow<ThreeMfInfo?> = _threeMfInfo.asStateFlow()

    private val _showPlateSelector = MutableStateFlow(false)
    val showPlateSelector: StateFlow<Boolean> = _showPlateSelector.asStateFlow()

    // Multi-color state — dialog only shown when user explicitly requests reassignment
    private val _showMultiColorDialog = MutableStateFlow(false)
    val showMultiColorDialog: StateFlow<Boolean> = _showMultiColorDialog.asStateFlow()

    // Current color→extruder mapping for inline UI. Index = detected color index, value = extruder slot index.
    // Null when model is single-color.
    private val _colorMapping = MutableStateFlow<List<Int>?>(null)
    val colorMapping: StateFlow<List<Int>?> = _colorMapping.asStateFlow()

    // Active extruder colors for G-code viewers (hex strings, one per extruder slot)
    private val _activeExtruderColors = MutableStateFlow<List<String>>(emptyList())
    val activeExtruderColors: StateFlow<List<String>> = _activeExtruderColors.asStateFlow()

    // Selected extruder for single-color models (0-based: E1=0, E2=1, E3=2, E4=3)
    private val _selectedExtruder = MutableStateFlow(0)
    val selectedExtruder: StateFlow<Int> = _selectedExtruder.asStateFlow()

    /**
     * True when the loaded model uses only layer-tool (Hueforge-style) colour changes —
     * no paint data and no per-object extruder assignments. In this mode the Prepare
     * preview should be recoloured by Z-band using [recolorByZBands] instead of the
     * per-triangle extruder-index path.
     */
    private val _layerToolOnly = MutableStateFlow(false)
    val layerToolOnly: StateFlow<Boolean> = _layerToolOnly.asStateFlow()

    /**
     * Convert a hex color string (#RRGGBB or RRGGBB) to a FloatArray of [R, G, B, 1f].
     * Returns a neutral grey on parse failure.
     */
    fun hexColorToFloatArray(hex: String): FloatArray = staticHexColorToFloatArray(hex)

    /**
     * Build a map of objectId (Int) → 0-based extruder index (Byte) for mesh preview coloring.
     * Uses objectExtruderMap from ThreeMfInfo (1-based) converted to 0-based.
     */
    fun buildExtruderMap(): Map<Int, Byte>? {
        val info = _threeMfInfo.value ?: return null
        val objMap = info.objectExtruderMap
        if (objMap.isEmpty()) return null
        return objMap.mapNotNull { (objIdStr, extruder1Based) ->
            val objId = objIdStr.toIntOrNull() ?: return@mapNotNull null
            objId to (extruder1Based - 1).coerceAtLeast(0).toByte()
        }.toMap().ifEmpty { null }
    }

    // Multiple copies
    private val _copyCount = MutableStateFlow(1)
    val copyCount: StateFlow<Int> = _copyCount.asStateFlow()

    // Model scale: uniform or per-axis. Applied before slicing.
    data class ModelScale(val x: Float = 1f, val y: Float = 1f, val z: Float = 1f) {
        val isUniform get() = x == y && y == z
        val uniform get() = x
    }
    private val _modelScale = MutableStateFlow(ModelScale())
    val modelScale: StateFlow<ModelScale> = _modelScale.asStateFlow()

    // Custom object positions set from PlacementViewer (null = use auto grid)
    // Flat array [x0,y0,x1,y1,...] in mm
    private var customObjectPositions: FloatArray? = null
    // Custom wipe tower position (null = use config defaults)
    private var customWipeTowerPos: Pair<Float, Float>? = null

    // Tool remap: maps compact T-index (0,1,…) → actual printer slot index (e.g. 2,3 for E3+E4).
    // Null / identity mapping → no post-processing needed.
    private var toolRemapSlots: List<Int>? = null

    // Filament library
    val filaments = filamentDao.getAll()

    // Job history
    val sliceJobs = sliceJobDao.getAll()

    // Extruder slot config (from printer page, used for color mapping dialog)
    val extruderPresets: StateFlow<List<ExtruderPreset>> = settingsRepo.extruderPresets
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.u1.slicer.data.defaultExtruderPresets())

    // Slicing overrides (USE_FILE / ORCA_DEFAULT / OVERRIDE per setting)
    val slicingOverrides: StateFlow<SlicingOverrides> = settingsRepo.slicingOverrides
        .stateIn(viewModelScope, SharingStarted.Eagerly, SlicingOverrides())

    // Build plate type — determines bed temp preset per filament material
    val plateType: StateFlow<PlateType> = settingsRepo.plateType
        .stateIn(viewModelScope, SharingStarted.Eagerly, PlateType.DEFAULT)

    // MakerWorld cookies (for authenticated URL downloads)
    val makerWorldCookies: StateFlow<String> = settingsRepo.makerWorldCookies
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val makerWorldCookiesEnabled: StateFlow<Boolean> = settingsRepo.makerWorldCookiesEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun saveMakerWorldCookies(cookies: String) {
        viewModelScope.launch(Dispatchers.IO) { settingsRepo.saveMakerWorldCookies(cookies) }
    }
    fun saveMakerWorldCookiesEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { settingsRepo.saveMakerWorldCookiesEnabled(enabled) }
    }

    // Track the current working file (may be sanitized copy)
    private var currentModelFile: File? = null
    private var currentModelName: String = ""
    private var lastModelInfo: ModelInfo? = null
    private val _modelInfo = MutableStateFlow<ModelInfo?>(null)
    val modelInfo: StateFlow<ModelInfo?> = _modelInfo.asStateFlow()

    // Source file and info before embedding — kept so we can re-embed with extruder remap
    // when the user sets non-identity slot assignments after initial load.
    private var sourceModelFile: File? = null
    private var sourceModelInfo: ThreeMfInfo? = null
    // Original Bambu file's project_settings.config, parsed before process() strips it.
    // Used by embedProfile() so the file's own settings (enable_support, etc.) survive
    // through the sanitize→embed→extractPlate→restructure→re-embed pipeline.
    private val _sourceConfig = MutableStateFlow<Map<String, Any>?>(null)
    val sourceConfig: StateFlow<Map<String, Any>?> = _sourceConfig.asStateFlow()

    // Recovery fields — track the pre-sanitize raw input so attemptClipperRecovery() can
    // re-run the full pipeline after clearing intermediate files.  rawInputFile is NOT an
    // intermediate (no embedded_/sanitized_/plate prefix) so clearIntermediateCache() leaves
    // it intact.  sourceModelFile/plateFiles ARE intermediates and get deleted by the clear,
    // which is why we can't use them in recovery.
    private var rawInputFile: File? = null
    private var recoveryOrigInfo: ThreeMfInfo? = null
    private var recoveryPlateId: Int = -1

    // B24 RC2: Track whether profile needs re-embedding before next slice.
    // Set to true when config/overrides are saved while a model is loaded.
    // Reset to false after each successful embed (initial load or re-embed).
    // Enables single-extruder re-embed without clearModel() to avoid Clipper state corruption.
    private var profileNeedsReEmbed = false

    // Keep each import/slice session in its own transient workspace so an upgrade
    // or reinstall cannot accidentally reuse stale generated files from a previous epoch.
    private val transientWorkspaceToken = diagnostics.sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun transientWorkspaceDir(): File {
        val dir = File(getApplication<Application>().filesDir, "transient/$transientWorkspaceToken")
        dir.mkdirs()
        return dir
    }

    private fun transientCacheDir(): File {
        val dir = File(getApplication<Application>().cacheDir, "transient/$transientWorkspaceToken")
        dir.mkdirs()
        return dir
    }

    /** Exposed for 3D viewer navigation */
    val currentModelPath: String? get() = currentModelFile?.absolutePath

    /**
     * Path to use for the inline 3D preview. Uses the original source file when available
     * (before sanitization/embedding) because the sanitized file may have component files
     * stripped out, leaving no geometry for the preview parser.
     */
    val previewModelPath: String? get() = resolvePreviewModelFile(
        rawInputFile = rawInputFile,
        sourceModelFile = sourceModelFile,
        currentModelFile = currentModelFile,
        info = _threeMfInfo.value,
        originalSourceConfig = _sourceConfig.value
    )?.absolutePath

    init {
        _coreVersion.value = if (NativeLibrary.isLoaded) {
            "Snapmaker Orca 2.2.4 (Android ARM64)"
        } else {
            "Native library not available"
        }
        configureNativeDiagnosticsIfAvailable()

        viewModelScope.launch {
            val saved = settingsRepo.sliceConfig.first()
            // If no bedTemp was explicitly saved, apply the current plate type preset so the
            // default 60°C doesn't override a plate-type-derived value on first launch.
            val savedPlate = settingsRepo.plateType.first()
            val resolvedBedTemp = if (saved.bedTemp == SliceConfig().bedTemp) {
                savedPlate.bedTempFor(saved.filamentType)
            } else {
                saved.bedTemp
            }
            _config.value = saved.copy(bedTemp = resolvedBedTemp)
        }

        // Keep Preview colors in sync with printer slot colors.
        // Mapping can be chosen before presets finish loading from DataStore, which
        // otherwise leaves G-code Preview stuck on default slot colors.
        viewModelScope.launch {
            extruderPresets.collect { presets ->
                refreshMappedPreviewColors(presets)
            }
        }

        viewModelScope.launch {
            var prevState: SlicerState = SlicerState.Idle
            state.collect { newState ->
                val ctx = getApplication<android.app.Application>()
                when {
                    prevState is SlicerState.Loading && newState is SlicerState.ModelLoaded -> {
                        val filename = (newState as SlicerState.ModelLoaded).info.filename
                        AppEventNotifier.notify(ctx, AppEventNotifier.Event.ModelLoaded(filename))
                    }
                    prevState is SlicerState.Slicing && newState is SlicerState.SliceComplete -> {
                        val filename = currentModelFile?.name ?: "model"
                        AppEventNotifier.notify(ctx, AppEventNotifier.Event.SliceComplete(filename))
                    }
                    prevState is SlicerState.Slicing && newState is SlicerState.Error -> {
                        AppEventNotifier.notify(ctx, AppEventNotifier.Event.SliceFailed(
                            (newState as SlicerState.Error).message))
                    }
                }
                prevState = newState
            }
        }
    }

    private fun refreshMappedPreviewColors(presets: List<ExtruderPreset>) {
        val mapping = _colorMapping.value
        if (mapping.isNullOrEmpty()) return
        val usedSlots = mapping.distinct().sorted()
        val refreshed = buildPreviewSlotColors(presets, usedSlots)
        if (refreshed != _activeExtruderColors.value) {
            _activeExtruderColors.value = refreshed
            Log.i("SlicerVM", "Refreshed mapped preview slot colors from presets: used=$usedSlots colors=$refreshed")
        }
    }

    /**
     * Handle a shared URL (e.g. MakerWorld link from Bambu Handy).
     * Extracts the design ID, downloads the 3MF, and loads it.
     */
    fun importFromSharedUrl(url: String) {
        if (!NativeLibrary.isLoaded) {
            _state.value = SlicerState.Error("Native slicer library not available on this device (arm64 required)")
            return
        }
        diagnostics.recordEvent("shared_url_import_started", mapOf("url" to url))
        // Extract MakerWorld design ID
        val designId = com.u1.slicer.network.MakerWorldUtils.extractDesignId(url)
        if (designId == null) {
            _state.value = SlicerState.Error(
                "Unsupported shared link.\n\n" +
                    "Share a MakerWorld model page link, or send a downloaded 3MF/STL file to U1 Slicer."
            )
            return
        }
        // Set loading state immediately (before coroutine dispatch) so UI shows spinner
        _state.value = SlicerState.Loading("Downloading from MakerWorld…")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val workspaceDir = transientWorkspaceDir()
                // Clean intermediate files from previous model loads
                val cleared = UpgradeDetector.clearIntermediateCache(workspaceDir)
                if (cleared > 0) Log.i("SlicerVM", "Cleared $cleared intermediate cache files before MakerWorld import")
                clipperRetryAttempted = false  // Reset retry flag for new model
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                    .followRedirects(true)
                    .build()
                val cookies = com.u1.slicer.network.MakerWorldUtils.sanitizeCookies(
                    settingsRepo.makerWorldCookies.first()
                )
                val cookiesEnabled = settingsRepo.makerWorldCookiesEnabled.first()
                Log.i("SlicerVM", "MakerWorld cookies: enabled=$cookiesEnabled, length=${cookies.length}")

                // Browser-like headers to avoid bot detection (matches u1-slicer-bridge)
                val browserUA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                fun okhttp3.Request.Builder.withBrowserHeaders(isApi: Boolean): okhttp3.Request.Builder {
                    header("User-Agent", browserUA)
                    header("Accept-Language", "en-US,en;q=0.9")
                    header("DNT", "1")
                    header("Sec-Ch-Ua", "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"")
                    header("Sec-Ch-Ua-Mobile", "?1")
                    header("Sec-Ch-Ua-Platform", "\"Android\"")
                    if (isApi) {
                        header("Accept", "application/json, text/plain, */*")
                        header("Origin", "https://makerworld.com")
                        header("Sec-Fetch-Dest", "empty")
                        header("Sec-Fetch-Mode", "cors")
                        header("Sec-Fetch-Site", "same-origin")
                        header("X-BBL-Client-Type", "web")
                        header("X-BBL-Client-Name", "MakerWorld")
                    } else {
                        header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                        header("Sec-Fetch-Dest", "document")
                        header("Sec-Fetch-Mode", "navigate")
                        header("Sec-Fetch-Site", "none")
                        header("Sec-Fetch-User", "?1")
                        header("Upgrade-Insecure-Requests", "1")
                    }
                    if (cookiesEnabled && cookies.isNotBlank()) header("Cookie", cookies)
                    return this
                }

                // Step 0: Visit model page first (establishes session, avoids bot detection)
                val pageUrl = "https://makerworld.com/en/models/$designId"
                val pageRequest = okhttp3.Request.Builder().url(pageUrl)
                    .withBrowserHeaders(isApi = false).get().build()
                try {
                    client.newCall(pageRequest).execute().use { /* consume+close */ }
                } catch (_: Exception) { /* best-effort */ }
                delay(kotlin.random.Random.nextLong(500, 1500))

                // Step 1: Resolve design ID → instance ID (MakerWorld page ID ≠ download instance ID)
                val designApiUrl = "https://makerworld.com/api/v1/design-service/design/$designId"
                val designRequest = okhttp3.Request.Builder().url(designApiUrl)
                    .withBrowserHeaders(isApi = true)
                    .header("Referer", pageUrl)
                    .get().build()
                val instanceId = client.newCall(designRequest).execute().use { designResponse ->
                    if (designResponse.isSuccessful) {
                        val resolved = com.u1.slicer.network.MakerWorldUtils.extractInstanceId(
                            designResponse.body?.string() ?: ""
                        )
                        if (resolved != null) {
                            Log.i("SlicerVM", "Resolved design $designId → instance $resolved")
                            resolved
                        } else designId
                    } else {
                        Log.w("SlicerVM", "Design API failed (${designResponse.code}), falling back to design ID")
                        designId
                    }
                }

                // Step 2: Download the 3MF from the instance endpoint
                val downloadUrl = "https://makerworld.com/api/v1/design-service/instance/$instanceId/f3mf?type=download"
                val request = okhttp3.Request.Builder().url(downloadUrl)
                    .withBrowserHeaders(isApi = true)
                    .header("Referer", pageUrl)
                    .get().build()
                Log.i("SlicerVM", "MakerWorld downloading instance $instanceId...")
                val response = client.newCall(request).execute()
                Log.i("SlicerVM", "MakerWorld download response: HTTP ${response.code}")
                if (!response.isSuccessful) {
                    val code = response.code
                    val errorBody = try { response.body?.string()?.take(500) } catch (_: Exception) { null }
                    response.close()
                    Log.w("SlicerVM", "MakerWorld download failed: HTTP $code, body: $errorBody")
                    val msg = com.u1.slicer.network.MakerWorldUtils.classifyDownloadError(code, errorBody)
                    _state.value = SlicerState.Error(msg)
                    return@launch
                }
                val contentType = response.header("Content-Type") ?: "unknown"
                Log.i("SlicerVM", "MakerWorld response: HTTP ${response.code}, Content-Type: $contentType")

                // API returns JSON with signed download URL — follow it
                val outputFile = File(workspaceDir, "makerworld_${designId}.3mf")
                if (contentType.contains("json")) {
                    val json = response.body?.string() ?: ""
                    response.close()
                    val parsed = com.u1.slicer.network.MakerWorldUtils.parseDownloadResponse(json)
                    when (parsed) {
                        is com.u1.slicer.network.MakerWorldUtils.DownloadResponse.ParseError -> {
                            _state.value = SlicerState.Error("Could not parse MakerWorld response")
                            return@launch
                        }
                        is com.u1.slicer.network.MakerWorldUtils.DownloadResponse.ApiError -> {
                            Log.w("SlicerVM", "MakerWorld API error: ${parsed.message}")
                            _state.value = SlicerState.Error(parsed.message)
                            return@launch
                        }
                        is com.u1.slicer.network.MakerWorldUtils.DownloadResponse.Success -> { /* continue below */ }
                    }
                    parsed as com.u1.slicer.network.MakerWorldUtils.DownloadResponse.Success
                    val fileUrl = parsed.url
                    val fileName = parsed.fileName
                    Log.i("SlicerVM", "MakerWorld redirect: $fileName -> ${fileUrl.take(80)}...")
                    currentModelName = fileName
                    _state.value = SlicerState.Loading("Downloading $fileName…")

                    val fileRequest = okhttp3.Request.Builder().url(fileUrl)
                        .header("User-Agent", "U1Slicer/1.0 Android").get().build()
                    val fileResponse = client.newCall(fileRequest).execute()
                    if (!fileResponse.isSuccessful) {
                        fileResponse.close()
                        _state.value = SlicerState.Error("File download failed: HTTP ${fileResponse.code}")
                        return@launch
                    }
                    fileResponse.body?.byteStream()?.use { input ->
                        outputFile.outputStream().use { input.copyTo(it) }
                    }
                    fileResponse.close()
                } else {
                    // Direct binary download
                    response.body?.byteStream()?.use { input ->
                        outputFile.outputStream().use { input.copyTo(it) }
                    }
                    response.close()
                }

                // Validate ZIP
                if (outputFile.length() < 4) {
                    outputFile.delete()
                    _state.value = SlicerState.Error("Downloaded file is empty")
                    return@launch
                }
                val magic = ByteArray(4)
                outputFile.inputStream().use { it.read(magic) }
                if (magic[0] != 0x50.toByte() || magic[1] != 0x4B.toByte()) {
                    val fileSize = outputFile.length()
                    val preview = outputFile.readText(Charsets.UTF_8).take(500)
                    Log.w("SlicerVM", "MakerWorld response is not a ZIP ($fileSize bytes): ${preview.take(200)}")
                    outputFile.delete()
                    val hint = com.u1.slicer.network.MakerWorldUtils.classifyNonZipResponse(preview, fileSize)
                    _state.value = SlicerState.Error(hint)
                    return@launch
                }

                oversizedArchiveMessage(outputFile)?.let { message ->
                    Log.w("SlicerVM", "Large MakerWorld 3MF will try to load anyway: $message")
                    diagnostics.recordEvent(
                        "oversized_archive_warning",
                        mapOf(
                            "source" to "makerworld",
                            "designId" to designId,
                            "instanceId" to instanceId,
                            "sizeBytes" to outputFile.length()
                        )
                    )
                }

                Log.i("SlicerVM", "Downloaded MakerWorld #$designId: ${outputFile.length()} bytes")
                currentModelName = "makerworld_${designId}.3mf"
                diagnostics.recordEvent(
                    "shared_url_import_downloaded",
                    mapOf(
                        "designId" to designId,
                        "instanceId" to instanceId,
                        "outputFile" to outputFile.absolutePath,
                        "sizeBytes" to outputFile.length()
                    )
                )
                rawInputFile = outputFile   // Track for Clipper recovery
                recoveryPlateId = -1
                _state.value = SlicerState.Loading("Preparing model…")

                // Same pipeline as loadModel(): parse → sanitize → embed → load
                val origInfo = ThreeMfParser.parse(outputFile)
                recoveryOrigInfo = origInfo  // Saved for Clipper recovery pipeline
                _sourceConfig.value = if (origInfo.isBambu) {
                    java.util.zip.ZipFile(outputFile).use { profileEmbedder.parseSourceConfig(it) }
                } else null

                val processed = BambuSanitizer.process(outputFile, workspaceDir, isBambu = origInfo.isBambu)
                val processedInfo = ThreeMfParser.parse(processed, skipPaintDetection = true)
                _threeMfInfo.value = mergeThreeMfInfo(processedInfo, origInfo)

                sourceModelFile = processed
                sourceModelInfo = processedInfo
                toolRemapSlots = null
                val mergedInfo = _threeMfInfo.value!!
                val sanitized = embedProfile(processed, mergedInfo, workspaceDir)

                currentModelFile = sanitized
                if (origInfo.isMultiPlate && origInfo.plates.size > 1) {
                    Log.i("SlicerVM", "MakerWorld file is multi-plate (${origInfo.plates.size} plates), showing selector")
                    _showPlateSelector.value = true
                    return@launch
                }
                Log.i("SlicerVM", "Loading MakerWorld model natively: ${sanitized.name} (${sanitized.length()} bytes)")
                loadNativeModel(sanitized)
            } catch (e: Throwable) {
                native.clearModel() // Reset native state to prevent stale Clipper errors on retry
                _state.value = SlicerState.Error(e.message ?: "Import failed")
                Log.e("SlicerVM", "Shared URL import failed", e)
            }
        }
    }

    fun loadModel(uri: Uri) {
        if (!NativeLibrary.isLoaded) {
            _state.value = SlicerState.Error("Native slicer library not available on this device (arm64 required)")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val workspaceDir = transientWorkspaceDir()
                // Clean intermediate files from previous model loads to prevent stale
                // sanitized/embedded/plate files from accidentally being referenced.
                val cleared = UpgradeDetector.clearIntermediateCache(workspaceDir)
                if (cleared > 0) Log.i("SlicerVM", "Cleared $cleared intermediate cache files before new model load")
                clipperRetryAttempted = false  // Reset retry flag for new model
                val inputStream = context.contentResolver.openInputStream(uri) ?: run {
                    _state.value = SlicerState.Error("Could not open file")
                    return@launch
                }

                val filename = normalizeIncomingFilename(getDisplayName(context, uri) ?: "model.stl")
                currentModelName = filename
                _state.value = SlicerState.Loading("Loading $filename…")
                val file = File(workspaceDir, filename)
                // Copy via temp file to avoid self-referential truncation
                // when the source URI points to our own FileProvider
                val tmpFile = File(transientCacheDir(), "import_${System.currentTimeMillis()}")
                try {
                    tmpFile.outputStream().use { inputStream.copyTo(it) }
                    tmpFile.copyTo(file, overwrite = true)
                } finally {
                    tmpFile.delete()
                }

                // Track raw input for Clipper recovery (rawInputFile is never an intermediate,
                // so clearIntermediateCache() won't delete it — safe to use after a cache clear).
                rawInputFile = file
                recoveryPlateId = -1
                diagnostics.recordEvent(
                    "model_imported",
                    mapOf(
                        "filename" to filename,
                        "copiedTo" to file.absolutePath,
                        "sizeBytes" to file.length()
                    )
                )

                // For 3MF files: parse metadata and sanitize if Bambu
                val fileToLoad = if (filename.endsWith(".3mf", ignoreCase = true)) {
                    // Verify it's a valid ZIP first
                    try {
                        java.util.zip.ZipFile(file).use { zip ->
                            if (zip.entries().toList().isEmpty()) {
                                _state.value = SlicerState.Error("3MF file is empty or invalid")
                                return@launch
                            }
                        }
                    } catch (e: java.util.zip.ZipException) {
                        _state.value = SlicerState.Error("3MF file is corrupt: ${e.message}")
                        return@launch
                    }

                    oversizedArchiveMessage(file)?.let { message ->
                        Log.w("SlicerVM", "Large 3MF will try to load anyway: $message")
                        diagnostics.recordEvent(
                            "oversized_archive_warning",
                            mapOf(
                                "source" to "picker",
                                "filename" to filename,
                                "sizeBytes" to file.length()
                            )
                        )
                    }

                    // Parse original file first for multi-plate detection (process()
                    // strips plate_N.json so detection would fail on the processed file).
                    val origInfo = ThreeMfParser.parse(file)
                    recoveryOrigInfo = origInfo  // Saved for Clipper recovery pipeline

                    Log.i("SlicerVM", "3MF: bambu=${origInfo.isBambu}, multiPlate=${origInfo.isMultiPlate}, " +
                        "colors=${origInfo.detectedColors.size}, extruders=${origInfo.detectedExtruderCount}, " +
                        "paint=${origInfo.hasPaintData}, toolChanges=${origInfo.hasLayerToolChanges}")

                    // Parse original file's config BEFORE process() strips it.
                    // This preserves file-level settings (enable_support, etc.) through the pipeline.
                    _sourceConfig.value = if (origInfo.isBambu) {
                        java.util.zip.ZipFile(file).use { profileEmbedder.parseSourceConfig(it) }
                    } else null

                    // Sanitize first (strip printable="0", restructure multi-color, clean XML),
                    // then embed Snapmaker profile.  Without process(), non-printable build
                    // items cause "Coordinate outside allowed range" Clipper errors.
                val processed = BambuSanitizer.process(file, workspaceDir, isBambu = origInfo.isBambu)
                    val processedInfo = ThreeMfParser.parse(processed, skipPaintDetection = true)
                    _threeMfInfo.value = mergeThreeMfInfo(processedInfo, origInfo)

                    // Store source before embedding so startSlicing() can re-embed with
                    // the correct extruder remap once the user has picked their slots.
                    sourceModelFile = processed
                    sourceModelInfo = processedInfo
                    toolRemapSlots = null  // reset on each new file load
                    // Use merged info (preserves origInfo's extruder count, paint data, etc.)
                    // so the preserve path in buildConfig() activates correctly for Bambu files
                    // with multi-extruder assignments (needed for support preservation — B10 fix).
                    val mergedInfo = _threeMfInfo.value!!
                    val sanitized = embedProfile(processed, mergedInfo, workspaceDir)

                    // Show plate selector for multi-plate files (use origInfo since
                    // process() strips plate_N.json files that isMultiPlate relies on).
                    if (origInfo.isMultiPlate && origInfo.plates.size > 1) {
                        Log.i("SlicerVM", "Multi-plate: ${origInfo.plates.size} plates, showing selector")
                        currentModelFile = sanitized
                        _showPlateSelector.value = true
                        // Don't load yet — wait for plate selection
                        return@launch
                    }
                    Log.i("SlicerVM", "Single-plate, loading directly")

                    sanitized
                } else {
                    _threeMfInfo.value = null
                    recoveryOrigInfo = null  // STL: no 3MF pipeline needed for recovery
                    // Clear source file so previewModelPath uses the STL directly.
                    // Without this, loading an STL after a 3MF leaves sourceModelFile
                    // pointing at the old 3MF, causing the wrong model to appear in the viewer.
                    sourceModelFile = null
                    sourceModelInfo = null
                    _sourceConfig.value = null
                    file
                }

                currentModelFile = fileToLoad
                loadNativeModel(fileToLoad)
            } catch (e: Throwable) {
                native.clearModel() // Reset native state to prevent stale Clipper errors on retry
                _state.value = SlicerState.Error("Error: ${e.message}")
            }
        }
    }

    /**
     * Download a model from a pre-signed URL (e.g. from MakerWorld WebView)
     * and load it into the slicer.
     */
    fun downloadAndLoadModel(url: String, filename: String, userAgent: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _state.value = SlicerState.Loading("Downloading $filename…")
                val context = getApplication<Application>()
                val safeFilename = filename.replace(Regex("[/\\\\:*?\"<>|]"), "_")
                val cacheFile = File(transientCacheDir(), safeFilename)
                val client = okhttp3.OkHttpClient.Builder()
                    .followRedirects(true)
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    response.close()
                    _state.value = SlicerState.Error("Download failed: HTTP ${response.code}")
                    return@launch
                }
                response.body?.byteStream()?.use { input ->
                    cacheFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i("SlicerVM", "MakerWorld browser download complete: ${cacheFile.name} (${cacheFile.length()} bytes)")
                loadModelFromFile(cacheFile)
            } catch (e: Exception) {
                Log.e("SlicerVM", "MakerWorld browser download failed", e)
                _state.value = SlicerState.Error("Download failed: ${e.message}")
            }
        }
    }

    fun loadModelFromFile(file: File) {
        if (!NativeLibrary.isLoaded) {
            _state.value = SlicerState.Error("Native slicer library not available on this device (arm64 required)")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val workspaceDir = transientWorkspaceDir()
                val cleared = UpgradeDetector.clearIntermediateCache(workspaceDir)
                if (cleared > 0) Log.i("SlicerVM", "Cleared $cleared intermediate cache files before direct model load")
                clipperRetryAttempted = false
                if (!file.exists() || !file.canRead()) {
                    _state.value = SlicerState.Error("Could not read file: ${file.absolutePath}")
                    return@launch
                }

                val filename = normalizeIncomingFilename(file.name)
                currentModelName = filename
                _state.value = SlicerState.Loading("Loading $filename…")

                val sourceFile = if (file.parentFile?.absolutePath == workspaceDir.absolutePath) {
                    file
                } else {
                    val copied = File(workspaceDir, filename)
                    file.copyTo(copied, overwrite = true)
                    copied
                }

                rawInputFile = sourceFile
                recoveryPlateId = -1
                diagnostics.recordEvent(
                    "model_imported",
                    mapOf(
                        "filename" to filename,
                        "copiedTo" to sourceFile.absolutePath,
                        "sizeBytes" to sourceFile.length(),
                        "directFileLoad" to true
                    )
                )

                val fileToLoad = if (filename.endsWith(".3mf", ignoreCase = true)) {
                    try {
                        java.util.zip.ZipFile(sourceFile).use { zip ->
                            if (zip.entries().toList().isEmpty()) {
                                _state.value = SlicerState.Error("3MF file is empty or invalid")
                                return@launch
                            }
                        }
                    } catch (e: java.util.zip.ZipException) {
                        _state.value = SlicerState.Error("3MF file is corrupt: ${e.message}")
                        return@launch
                    }

                    oversizedArchiveMessage(sourceFile)?.let { message ->
                        Log.w("SlicerVM", "Large direct 3MF will try to load anyway: $message")
                        diagnostics.recordEvent(
                            "oversized_archive_warning",
                            mapOf(
                                "source" to "direct_file",
                                "filename" to filename,
                                "sizeBytes" to sourceFile.length()
                            )
                        )
                    }

                    val origInfo = ThreeMfParser.parse(sourceFile)
                    recoveryOrigInfo = origInfo

                    Log.i("SlicerVM", "3MF: bambu=${origInfo.isBambu}, multiPlate=${origInfo.isMultiPlate}, " +
                        "colors=${origInfo.detectedColors.size}, extruders=${origInfo.detectedExtruderCount}, " +
                        "paint=${origInfo.hasPaintData}, toolChanges=${origInfo.hasLayerToolChanges}")

                    _sourceConfig.value = if (origInfo.isBambu) {
                        java.util.zip.ZipFile(sourceFile).use { profileEmbedder.parseSourceConfig(it) }
                    } else null

                    val processed = BambuSanitizer.process(sourceFile, workspaceDir, isBambu = origInfo.isBambu)
                    val processedInfo = ThreeMfParser.parse(processed, skipPaintDetection = true)
                    _threeMfInfo.value = mergeThreeMfInfo(processedInfo, origInfo)

                    sourceModelFile = processed
                    sourceModelInfo = processedInfo
                    toolRemapSlots = null
                    val mergedInfo = _threeMfInfo.value!!
                    val sanitized = embedProfile(processed, mergedInfo, workspaceDir)

                    if (origInfo.isMultiPlate && origInfo.plates.size > 1) {
                        Log.i("SlicerVM", "Multi-plate: ${origInfo.plates.size} plates, showing selector")
                        currentModelFile = sanitized
                        _showPlateSelector.value = true
                        return@launch
                    }
                    Log.i("SlicerVM", "Single-plate, loading directly")

                    sanitized
                } else {
                    _threeMfInfo.value = null
                    recoveryOrigInfo = null
                    sourceModelFile = null
                    sourceModelInfo = null
                    _sourceConfig.value = null
                    sourceFile
                }

                currentModelFile = fileToLoad
                loadNativeModel(fileToLoad)
            } catch (e: Throwable) {
                native.clearModel()
                _state.value = SlicerState.Error("Error: ${e.message}")
            }
        }
    }

    /**
     * Called when user selects a plate from the multi-plate dialog.
     */
    fun selectPlate(plateId: Int) {
        _showPlateSelector.value = false
        val file = resolvePlateSelectionSourceFile(sourceModelFile, currentModelFile) ?: return
        recoveryPlateId = plateId          // Track for Clipper recovery
        clipperRetryAttempted = false      // New plate = fresh retry allowance
        diagnostics.recordEvent(
            "plate_selected",
            mapOf(
                "plateId" to plateId,
                "currentModelPath" to file.absolutePath
            )
        )

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val workspaceDir = transientWorkspaceDir()
                // Pass hasPlateJsons from _threeMfInfo which preserves the original value
                // (process() strips plate_N.json files from the ZIP, so auto-detection
                // on the processed file would always return false).
                val hasPlateJsons = _threeMfInfo.value?.hasPlateJsons
                // Pass plate object IDs from the original parse (model_settings.config
                // may have been stripped by process() from the sanitized file).
                val plateObjectIds = _threeMfInfo.value?.plates
                    ?.find { it.plateId == plateId }?.objectIds?.toSet()
                val plateExtruderMap = _threeMfInfo.value?.objectExtruderMap
                    ?.filterKeys { key -> plateObjectIds?.contains(key) == true }
                val rawPlateFile = BambuSanitizer.extractPlate(file, plateId, workspaceDir,
                    hasPlateJsons = hasPlateJsons,
                    plateObjectIds = plateObjectIds,
                    objectExtruderMap = plateExtruderMap)
                // Restructure per-plate: inline component meshes so OrcaSlicer
                // can assign per-volume extruders (deferred from process()).
                val plateFile = BambuSanitizer.restructurePlateFile(rawPlateFile, workspaceDir)
                // Lightweight parse: only reads model_settings.config (~1KB) for extruder
                // indices, skips the 15MB+ main model XML entirely (~2s saved).
                val plateInfo = ThreeMfParser.parseForPlateSelection(plateFile)
                sourceModelFile = plateFile
                sourceModelInfo = plateInfo
                // Merge plate structural info with the pre-select merged info so that
                // color/extruder metadata from the original file is preserved.
                // plateInfo has 0 detected colors because extractPlate() works on the
                // processed file which has had filament_sequence.json stripped by process().
                // _threeMfInfo.value holds the correctly-merged info from openModel().
                val preSelectInfo = _threeMfInfo.value
                val mergedPlateInfo = if (preSelectInfo != null)
                    mergeThreeMfInfoForPlate(plateInfo, preSelectInfo, plateId)
                else
                    plateInfo
                _threeMfInfo.value = mergedPlateInfo
                toolRemapSlots = null
                // Re-embed the selected plate so slice-time config preserves the
                // original file's layer-change settings (SEMM/pause G-code), not just
                // the preview metadata merged above.
                val embeddedPlateFile = embedProfile(plateFile, mergedPlateInfo, workspaceDir)
                currentModelFile = embeddedPlateFile
                loadNativeModel(embeddedPlateFile)
            } catch (e: Throwable) {
                native.clearModel() // Reset native state to prevent stale Clipper errors on retry
                _state.value = SlicerState.Error("Error extracting plate: ${e.message}")
            }
        }
    }

    private fun oversizedArchiveMessage(file: File): String? {
        val risk = ThreeMfParser.inspectArchiveSizing(file) ?: return null
        val largestMiB = risk.largestComponentBytes / (1024L * 1024L)
        val totalMiB = risk.totalComponentBytes / (1024L * 1024L)
        return buildString {
            append("This 3MF contains very large embedded component models ")
            append("($largestMiB MiB largest, $totalMiB MiB total) and is likely to run out of memory on-device.")
            append(" Try exporting a simpler plate or a flattened 3MF first.")
        }
    }

    fun dismissPlateSelector() {
        _showPlateSelector.value = false
        // Cancel the load — multi-plate files need a plate selection to work correctly.
        // Loading the full file causes off-bed coordinates and Clipper errors (B12).
        _state.value = SlicerState.Idle
        currentModelFile = null
        sourceModelFile = null
        sourceModelInfo = null
        _threeMfInfo.value = null
    }

    private fun loadNativeModel(file: File) {
        val firstModelLoadThisLaunch = diagnostics.markFirstModelLoad()
        val success = native.loadModel(file.absolutePath)
        diagnostics.recordEvent(
            "native_model_load",
            mapOf(
                "success" to success,
                "path" to file.absolutePath,
                "firstModelLoadThisLaunch" to firstModelLoadThisLaunch
            )
        )
        if (success) {
            profileNeedsReEmbed = false  // Profile is current — just embedded
            val info = native.getModelInfo()
            if (info != null) {
                lastModelInfo = info
                _modelInfo.value = info
                _modelScale.value = ModelScale()  // reset to 1× on each new load
                _state.value = SlicerState.ModelLoaded(info)

                // Check for multi-color from 3MF parsing
                val mfInfo = _threeMfInfo.value
                if (mfInfo != null && mfInfo.detectedExtruderCount > 1) {
                    val layerToolOnly = mfInfo.hasLayerToolChanges &&
                        !mfInfo.hasPaintData &&
                        !mfInfo.hasMultiExtruderAssignments

                    // Auto-apply closest-extruder mapping immediately — no dialog popup.
                    // The inline UI on the model page lets the user change assignments.
                    val presets = extruderPresets.value
                    val rawMapping = mfInfo.detectedColors.map { modelColor ->
                        com.u1.slicer.ui.findClosestExtruder(modelColor, presets)?.index ?: 0
                    }
                    // If closest-colour matching collapses multiple model colours onto too few
                    // slots, distribute across the available slots so the initial slice preview
                    // stays visually distinct until the user overrides it.
                    val initialMapping = com.u1.slicer.ui.ensureMultiSlotMapping(
                        rawMapping, mfInfo.detectedColors.size
                    )

                    if (layerToolOnly) {
                        // Hueforge-style layer-change files should stay a single-filament slice so
                        // Orca applies the custom per-layer tool changes as color-change pauses.
                        // We still keep the preview colours/mapping so the model view remains
                        // multi-colour, but we do not force a multi-extruder slice configuration.
                        _colorMapping.value = initialMapping
                        val previewSlots = initialMapping.distinct().sorted()
                        _activeExtruderColors.value = buildPreviewSlotColors(presets, previewSlots)
                        _selectedExtruder.value = 0
                        toolRemapSlots = null
                        customWipeTowerPos = null
                        _config.value = _config.value.copy(
                            extruderCount = 1,
                            wipeTowerEnabled = false
                        )
                        // F46: signal that InlineModelPreview should use recolorByZBands
                        _layerToolOnly.value = true
                        val segs = _threeMfInfo.value?.layerToolSegments
                        Log.i("SlicerVM", "Applied layer-tool preview mapping only: colors=${initialMapping.size}, previewSlots=$previewSlots, layerToolSegments=${segs?.size ?: "null"}")
                    } else {
                        // Compact extruder count: use the smaller of detected colors and
                        // physical extruders (Snapmaker U1 has 4).  Compact mode slices as
                        // N-extruder and G-code post-processing remaps T-commands to physical slots.
                        val extCount = mfInfo.detectedExtruderCount.coerceIn(1, 4)
                        // Compute tower position that avoids the model
                        val positions = CopyArrangeCalculator.calculate(info.sizeX, info.sizeY, _copyCount.value)
                        val towerPos = CopyArrangeCalculator.computeWipeTowerPosition(
                            positions, info.sizeX, info.sizeY, _config.value.wipeTowerWidth
                        )
                        _config.value = _config.value.copy(
                            extruderCount = extCount,
                            wipeTowerEnabled = true,
                            wipeTowerX = towerPos.first,
                            wipeTowerY = towerPos.second
                        )
                        customWipeTowerPos = towerPos
                        Log.i("SlicerVM", "Auto-placed wipe tower at (${towerPos.first}, ${towerPos.second})")
                        _colorMapping.value = initialMapping
                        _layerToolOnly.value = false
                        applyMultiColorAssignments(initialMapping, presets, emptyList())
                        Log.i("SlicerVM", "Auto-applied color mapping: $extCount extruders, mapping=$initialMapping")
                    }
                } else {
                    _colorMapping.value = null
                    _layerToolOnly.value = false
                    _selectedExtruder.value = 0
                    // Reset multi-extruder state: single-color model uses 1 extruder.
                    // Without this, stale extruderCount from a previous multi-color model
                    // forces the prime tower on and produces 2-extruder G-code (B24 fix).
                    toolRemapSlots = null
                    customWipeTowerPos = null
                    _config.value = _config.value.copy(
                        extruderCount = 1,
                        wipeTowerEnabled = false
                    )
                    // Single-color model: set E1's color from current printer slot config so
                    // the 3D model preview shows the correct filament color instead of default orange.
                    val presets = extruderPresets.value
                    val colors = MutableList(4) { "" }
                    presets.forEach { preset -> if (preset.index in 0..3) colors[preset.index] = preset.color }
                    _activeExtruderColors.value = colors
                    // Persist the reset so wipeTowerEnabled=false survives across sessions (B24 fix).
                    saveConfig()
                    Log.i("SlicerVM", "Single-color model: set preview colors from slots ${colors}")
                }
            } else {
                _state.value = SlicerState.Error("Failed to read model info")
            }
        } else {
            _state.value = SlicerState.Error("Failed to load model")
        }
    }

    /**
     * Called when user confirms the color-to-extruder mapping.
     * @param modelColorToExtruder  For each detected model color, the extruder index (0-based) it maps to.
     * @param extruderPresets       Current printer slot config (for looking up temps via profile).
     * @param filaments             Filament library (for temp lookup from profile id).
     */
    fun applyMultiColorAssignments(
        modelColorToExtruder: List<Int>,
        extruderPresets: List<com.u1.slicer.data.ExtruderPreset>,
        filaments: List<FilamentProfile>
    ) {
        _showMultiColorDialog.value = false
        _colorMapping.value = modelColorToExtruder
        val usedSlots = modelColorToExtruder.distinct().sorted()
        // Compact extruder count: number of unique slots used, capped at 4 (U1 max).
        // G-code post-processing remaps T-commands to physical slots when non-identity.
        val extCount = usedSlots.size.coerceIn(1, 4)
        // SEMM (paint-based) models: extruderRemap in model_settings.config only affects
        // per-volume extruder attributes, NOT mmu_segmentation_facets paint state filament
        // indices.  For these models, use GcodeToolRemapper with the full colorMapping list
        // as the remap table.  For per-object models, keep the existing compact-slot remap.
        val hasPaintData = _threeMfInfo.value?.hasPaintData == true
        toolRemapSlots = if (hasPaintData) {
            // SEMM: non-identity colorMapping → GcodeToolRemapper handles remap
            val isColorMappingIdentity = modelColorToExtruder == (0 until modelColorToExtruder.size).toList()
            if (isColorMappingIdentity) null else modelColorToExtruder
        } else {
            // Per-object: extruderRemap in 3MF handles non-contiguous / non-identity slot order
            val compactSlots = usedSlots.take(extCount)
            val isIdentity = compactSlots == (0 until extCount).toList()
            if (isIdentity) null else compactSlots
        }
        val temps = IntArray(extCount) { i ->
            val slotIndex = usedSlots.getOrElse(i) { i }
            val preset = extruderPresets.firstOrNull { it.index == slotIndex }
            val profileId = preset?.filamentProfileId
            filaments.firstOrNull { it.id == profileId }?.nozzleTemp ?: 210
        }
        // Recompute wipe tower position if multi-extruder (unless user already placed it)
        val mi = lastModelInfo
        if (extCount > 1 && mi != null && mi.sizeX > 0f && customWipeTowerPos == null) {
            val objPos = CopyArrangeCalculator.calculate(mi.sizeX, mi.sizeY, _copyCount.value)
            val towerPos = CopyArrangeCalculator.computeWipeTowerPosition(
                objPos, mi.sizeX, mi.sizeY, _config.value.wipeTowerWidth
            )
            _config.value = _config.value.copy(
                extruderCount = extCount,
                extruderTemps = temps,
                extruderRetractLength = FloatArray(extCount) { _config.value.retractLength },
                extruderRetractSpeed = FloatArray(extCount) { _config.value.retractSpeed },
                wipeTowerEnabled = true,
                wipeTowerX = towerPos.first,
                wipeTowerY = towerPos.second
            )
            customWipeTowerPos = towerPos
            Log.i("SlicerVM", "Auto-placed wipe tower at (${towerPos.first}, ${towerPos.second})")
        } else {
            _config.value = _config.value.copy(
                extruderCount = extCount,
                extruderTemps = temps,
                extruderRetractLength = FloatArray(extCount) { _config.value.retractLength },
                extruderRetractSpeed = FloatArray(extCount) { _config.value.retractSpeed },
                wipeTowerEnabled = extCount > 1
            )
        }
        // Store per-extruder colors for G-code viewers, indexed by physical slot.
        // After tool remapping the G-code uses physical T-indices (e.g. T2, T3),
        // so the color list must have entries at those positions.
        // Prepare preview should match sliced Preview, so both must color from the
        // final extruder slot palette rather than drifting back toward detected
        // model colors.
        val fullColors = buildPreviewSlotColors(extruderPresets, usedSlots)
        _activeExtruderColors.value = fullColors
        Log.i("SlicerVM", "Applied color mapping: $extCount extruders used=${usedSlots}, remap=${toolRemapSlots}, temps=${temps.toList()}, colors=$fullColors")
        diagnostics.recordEvent(
            "color_mapping_applied",
            mapOf(
                "colorMapping" to modelColorToExtruder,
                "usedSlots" to usedSlots,
                "extCount" to extCount,
                "toolRemapSlots" to toolRemapSlots,
                "isIdentity" to (toolRemapSlots == null),
                "slotColors" to fullColors
            )
        )
    }

    fun dismissMultiColorDialog() {
        _showMultiColorDialog.value = false
    }

    /**
     * Set the selected extruder for single-color models.
     * Updates the 3D preview color and configures tool remapping so the slicer
     * emits the correct T-command for the chosen physical extruder slot.
     */
    fun setSelectedExtruder(index: Int) {
        _selectedExtruder.value = index
        updateSingleColorExtruder(index)
    }

    /**
     * For single-color models, all triangles have extruder index 0 in the mesh.
     * The recolor palette is indexed by extruder index in the mesh, so the selected
     * extruder's color must go at palette index 0. Also sets up tool remapping
     * so that the native slicer's T0 gets remapped to the physical slot.
     */
    private fun updateSingleColorExtruder(index: Int) {
        val presets = extruderPresets.value
        val color = presets.firstOrNull { it.index == index }?.color ?: ""
        val resolvedColor = color.ifBlank { ExtruderPreset.DEFAULT_COLORS[index] }
        val colors = MutableList(4) { "" }
        colors[0] = resolvedColor  // Put at index 0 since all mesh triangles have extruder index 0
        _activeExtruderColors.value = colors

        // Configure tool remapping: single-color model uses T0 in native slicer,
        // but we want it printed on the selected physical extruder slot.
        if (index == 0) {
            // E1 selected — identity mapping, no remap needed
            toolRemapSlots = null
            _config.value = _config.value.copy(
                extruderCount = 1,
                wipeTowerEnabled = false
            )
        } else {
            // E2/E3/E4 — remap T0 → physical slot
            toolRemapSlots = listOf(index)
            _config.value = _config.value.copy(
                extruderCount = 1,
                wipeTowerEnabled = false
            )
        }
        Log.i("SlicerVM", "Single-color extruder set to E${index + 1}, remap=$toolRemapSlots")
    }

    /**
     * Re-trigger auto-mapping of detected model colors to the closest extruder slots.
     * Useful after changing extruder filament colors.
     */
    fun reAutoMapColors(
        extruderPresets: List<com.u1.slicer.data.ExtruderPreset>,
        filaments: List<FilamentProfile>
    ) {
        val colors = _threeMfInfo.value?.detectedColors ?: return
        if (colors.isEmpty()) return
        val rawMapping = colors.map { modelColor ->
            com.u1.slicer.ui.findClosestExtruder(modelColor, extruderPresets)?.index ?: 0
        }
        val mapping = com.u1.slicer.ui.ensureMultiSlotMapping(rawMapping, colors.size)
        applyMultiColorAssignments(mapping, extruderPresets, filaments)
        Log.i("SlicerVM", "Re-auto-mapped colors: mapping=$mapping")
    }

    fun showMultiColorReassign() {
        _showMultiColorDialog.value = true
    }

    fun setModelScale(scale: ModelScale) {
        _modelScale.value = scale
        customObjectPositions = null // reset positions — re-center for new scaled size
    }

    fun setCopyCount(count: Int) {
        val mi = lastModelInfo
        val max = if (mi != null && mi.sizeX > 0f && mi.sizeY > 0f)
            CopyArrangeCalculator.maxCopies(mi.sizeX, mi.sizeY)
        else 16
        _copyCount.value = count.coerceIn(1, max)
        customObjectPositions = null // reset custom positions when count changes
    }

    /** Called from inline 3D placement viewer when user drags objects. */
    fun applyPlacementPositions(positions: FloatArray, wipeTowerPos: Pair<Float, Float>) {
        customObjectPositions = positions
        customWipeTowerPos = wipeTowerPos
        // Also update wipe tower config with new position
        _config.value = _config.value.copy(
            wipeTowerX = wipeTowerPos.first,
            wipeTowerY = wipeTowerPos.second
        )
        Log.i("SlicerVM", "Custom placement applied: ${positions.size / 2} objects, tower=(${wipeTowerPos.first},${wipeTowerPos.second})")
    }

    /** Returns initial positions for inline 3D placement (custom or auto-calculated).
     *  Uses scaled model size so the preview positions match the visual footprint on the bed. */
    fun getPlacementPositions(): FloatArray {
        customObjectPositions?.let { return it }
        val mi = lastModelInfo ?: return floatArrayOf(135f, 135f)
        val s = _modelScale.value
        return CopyArrangeCalculator.calculate(mi.sizeX * s.x, mi.sizeY * s.y, _copyCount.value)
    }

    fun updateConfig(updater: (SliceConfig) -> SliceConfig) {
        _config.value = updater(_config.value)
    }

    fun saveConfig() {
        if (lastModelInfo != null) profileNeedsReEmbed = true
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.saveSliceConfig(_config.value)
        }
    }

    /**
     * Change the build plate type and update bedTemp to the recommended preset for the
     * current filament material type.  Persists both the plate selection and the new bedTemp.
     */
    fun setPlateType(type: PlateType) {
        val newBedTemp = type.bedTempFor(_config.value.filamentType)
        _config.value = _config.value.copy(bedTemp = newBedTemp)
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.savePlateType(type)
            settingsRepo.saveSliceConfig(_config.value)
        }
    }

    /** Direct bed temp edit — user overrides the plate type preset. */
    fun setBedTemp(temp: Int) {
        _config.value = _config.value.copy(bedTemp = temp)
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.saveSliceConfig(_config.value)
        }
    }

    fun saveSlicingOverrides(overrides: SlicingOverrides) {
        if (lastModelInfo != null) profileNeedsReEmbed = true
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.saveSlicingOverrides(overrides)
        }
    }

    /**
     * Build Snapmaker profile config and embed it into the 3MF file.
     * Replaces BambuSanitizer.process() for the OrcaSlicer backend.
     */
    private fun embedProfile(file: java.io.File, info: ThreeMfInfo, outputDir: java.io.File): java.io.File {
        val cfg = _config.value
        val extCount = cfg.extruderCount.coerceAtLeast(1)
        val usedSlots = toolRemapSlots  // e.g. [2,3] for E3+E4; null = identity/single
        val colorMapping = _colorMapping.value
        // Use compact extruder count (= number of unique used slots, up to 4).
        // When slots are non-contiguous (e.g. E2+E4), we slice as compact N-extruder
        // and post-process G-code to remap T-commands + SM indices to physical slots.
        // For SEMM models toolRemapSlots holds the full colorMapping list (may contain
        // duplicates, e.g. [0,0,1,1,3]) — use distinct count, not raw size.
        val targetCount = if (usedSlots != null) usedSlots.distinct().size else extCount
        // No extruder remap in the 3MF — keep compact numbering (1,2,…).
        // G-code post-processing handles T0→T2, T1→T3, SM EXTRUDER/INDEX remapping.
        val extruderRemap = buildCompactExtruderRemap(info, colorMapping)
        // Use the original file's config (parsed before process() strips it) when available.
        // Falls back to parsing from the current file for non-Bambu or when original is unavailable.
        val sourceConfig = _sourceConfig.value ?: if (info.isBambu) {
            java.util.zip.ZipFile(file).use { profileEmbedder.parseSourceConfig(it) }
        } else null
        Log.d("SlicerVM", "embedProfile: info.isBambu=${info.isBambu}, info.detectedExtruders=${info.detectedExtruderCount}, " +
            "info.hasToolChanges=${info.hasLayerToolChanges}, info.hasPaint=${info.hasPaintData}, " +
            "info.isMultiPlate=${info.isMultiPlate}, sourceConfig=${sourceConfig != null}, " +
            "targetCount=$targetCount, extruderRemap=$extruderRemap")
        diagnostics.recordEvent(
            "profile_embedded",
            mapOf(
                "targetCount" to targetCount,
                "usedSlots" to usedSlots,
                "colorMapping" to colorMapping,
                "extruderRemap" to extruderRemap,
                "isBambu" to info.isBambu,
                "hasPaint" to info.hasPaintData,
                "hasSourceConfig" to (sourceConfig != null),
                "detectedExtruders" to info.detectedExtruderCount
            )
        )
        val embeddedConfig = profileEmbedder.buildConfig(
            info = info,
            sourceConfig = sourceConfig,
            overrides = buildProfileOverrides(cfg, targetCount, usedSlots, hasSourceConfig = sourceConfig != null),
            targetExtruderCount = targetCount
        )
        return profileEmbedder.embed(file, embeddedConfig, outputDir, info, extruderRemap)
    }

    private fun buildProfileOverrides(cfg: SliceConfig, extCount: Int, usedSlots: List<Int>? = null, hasSourceConfig: Boolean = false): Map<String, Any> {
        return buildProfileOverridesImpl(cfg, slicingOverrides.value, extCount, usedSlots, hasSourceConfig)
    }

    private fun configureNativeDiagnosticsIfAvailable() {
        if (!NativeLibrary.isLoaded) return
        // Detect hard native crashes (OOM/SIGSEGV) from the previous session.
        // A stale marker means native.slice() started but the process was killed before
        // clearSliceInProgress() ran in the finally block.
        val staleMarker = diagnostics.consumeSliceInProgressMarker()
        if (staleMarker != null) {
            diagnostics.recordEvent(
                "hard_crash_during_slice",
                mapOf("staleMarker" to staleMarker)
            )
        }
        // If this session was launched by clipper recovery restart, suppress further
        // auto-restarts to prevent crash loops (error → restart → same error → restart).
        if (diagnostics.consumeClipperRecoveryPending()) {
            clipperRetryAttempted = true
            diagnostics.recordEvent(
                "clipper_recovery_suppressed",
                mapOf(
                    "currentSessionId" to diagnostics.sessionId,
                    "currentPid" to android.os.Process.myPid()
                )
            )
        }
        try {
            native.configureDiagnostics(diagnostics.diagnosticsPath())
            diagnostics.recordNativeConfigured(native.getDiagnosticsState())
        } catch (_: UnsatisfiedLinkError) {
            diagnostics.recordEvent("native_diagnostics_unavailable")
        }
    }

    private fun safeNativeDiagnosticsState(): String? {
        if (!NativeLibrary.isLoaded) return null
        return try {
            native.getDiagnosticsState()
        } catch (_: UnsatisfiedLinkError) {
            null
        }
    }

    private fun sliceDiagnosticsMap(
        sliceConfig: SliceConfig,
        profileOverrides: Map<String, Any>,
        firstSliceThisLaunch: Boolean
    ): Map<String, Any?> {
        val currentInfo = lastModelInfo
        return mapOf(
            "firstSliceThisLaunch" to firstSliceThisLaunch,
            "modelName" to currentModelName,
            "currentModelPath" to currentModelFile?.absolutePath,
            "sourceModelPath" to sourceModelFile?.absolutePath,
            "rawInputPath" to rawInputFile?.absolutePath,
            "selectedPlateId" to recoveryPlateId.takeIf { it >= 0 },
            "copyCount" to _copyCount.value,
            "hasCustomPlacement" to (customObjectPositions != null),
            "toolRemapSlots" to toolRemapSlots,
            "colorMapping" to _colorMapping.value,
            "extruderCount" to sliceConfig.extruderCount,
            "wipeTowerEnabled" to sliceConfig.wipeTowerEnabled,
            "wipeTowerX" to sliceConfig.wipeTowerX,
            "wipeTowerY" to sliceConfig.wipeTowerY,
            "supportEnabled" to sliceConfig.supportEnabled,
            "supportOverrideMode" to slicingOverrides.value.supports.mode.name,
            "supportTypeOverrideMode" to slicingOverrides.value.supportType.mode.name,
            "supportTypeOverrideValue" to slicingOverrides.value.supportType.value,
            "resolvedSupportTypeForProfile" to profileOverrides["support_type"],
            "resolvedSupportEnabledForProfile" to profileOverrides["enable_support"],
            "resolvedSupportAngleForProfile" to profileOverrides["support_threshold_angle"],
            "modelBounds" to if (currentInfo != null) mapOf(
                "sizeX" to currentInfo.sizeX,
                "sizeY" to currentInfo.sizeY,
                "sizeZ" to currentInfo.sizeZ
            ) else null
        )
    }

    private fun sliceGeometrySnapshot(
        sliceConfig: SliceConfig,
        profileOverrides: Map<String, Any>,
        firstSliceThisLaunch: Boolean,
        mi: ModelInfo?,
        copies: Int,
        custom: FloatArray?,
        remap: List<Int>?
    ): Map<String, Any?> {
        val scale = _modelScale.value
        return mapOf(
            "firstSliceThisLaunch" to firstSliceThisLaunch,
            "modelName" to currentModelName,
            "currentModelPath" to currentModelFile?.absolutePath,
            "sourceModelPath" to sourceModelFile?.absolutePath,
            "rawInputPath" to rawInputFile?.absolutePath,
            "selectedPlateId" to recoveryPlateId.takeIf { it >= 0 },
            "copyCount" to copies,
            "hasCustomPlacement" to (custom != null),
            "customPlacement" to custom?.toList(),
            "toolRemapSlots" to remap,
            "colorMapping" to _colorMapping.value,
            "modelScale" to mapOf("x" to scale.x, "y" to scale.y, "z" to scale.z),
            "modelInfo" to if (mi != null) mapOf(
                "sizeX" to mi.sizeX,
                "sizeY" to mi.sizeY,
                "sizeZ" to mi.sizeZ,
                "triangleCount" to mi.triangleCount,
                "volumeCount" to mi.volumeCount,
                "isManifold" to mi.isManifold
            ) else null,
            "extruderCount" to sliceConfig.extruderCount,
            "wipeTower" to mapOf(
                "enabled" to sliceConfig.wipeTowerEnabled,
                "x" to sliceConfig.wipeTowerX,
                "y" to sliceConfig.wipeTowerY,
                "width" to sliceConfig.wipeTowerWidth
            ),
            "support" to mapOf(
                "enabled" to sliceConfig.supportEnabled,
                "overrideMode" to slicingOverrides.value.supports.mode.name,
                "typeOverrideMode" to slicingOverrides.value.supportType.mode.name,
                "typeOverrideValue" to slicingOverrides.value.supportType.value
            ),
            "resolvedSupportTypeForProfile" to profileOverrides["support_type"],
            "resolvedSupportEnabledForProfile" to profileOverrides["enable_support"],
            "resolvedSupportAngleForProfile" to profileOverrides["support_threshold_angle"]
        )
    }

    private fun sliceProcessDiagnosticsMap(): Map<String, Any?> {
        val runtime = Runtime.getRuntime()
        val activityManager = getApplication<Application>()
            .getSystemService(android.content.Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also { info ->
            activityManager?.getMemoryInfo(info)
        }
        return mapOf(
            "pid" to android.os.Process.myPid(),
            "nativeState" to safeNativeDiagnosticsState(),
            "javaHeapUsedBytes" to (runtime.totalMemory() - runtime.freeMemory()),
            "javaHeapFreeBytes" to runtime.freeMemory(),
            "javaHeapTotalBytes" to runtime.totalMemory(),
            "javaHeapMaxBytes" to runtime.maxMemory(),
            "nativeHeapAllocatedBytes" to Debug.getNativeHeapAllocatedSize(),
            "nativeHeapFreeBytes" to Debug.getNativeHeapFreeSize(),
            "nativeHeapSizeBytes" to Debug.getNativeHeapSize(),
            "systemLowMemory" to memoryInfo?.lowMemory,
            "systemAvailMemBytes" to memoryInfo?.availMem,
            "systemTotalMemBytes" to memoryInfo?.totalMem,
            "systemThresholdBytes" to memoryInfo?.threshold
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun summarizeZipEntries(file: File, maxEntries: Int = 20): List<Map<String, Any?>> {
        ZipFile(file).use { zip ->
            return zip.entries().asSequence()
                .take(maxEntries)
                .map { entry ->
                    mapOf(
                        "name" to entry.name,
                        "size" to entry.size,
                        "compressedSize" to entry.compressedSize,
                        "crc" to entry.crc
                    )
                }
                .toList()
        }
    }

    private fun extractEmbeddedConfigSummary(file: File): Map<String, Any?>? {
        if (!file.extension.equals("3mf", ignoreCase = true)) return null
        return try {
            ZipFile(file).use { zip ->
                val projectSettingsEntry = zip.getEntry("Metadata/project_settings.config")
                val projectSettings = projectSettingsEntry
                    ?.let { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }
                val modelSettingsEntry = zip.entries().asSequence()
                    .firstOrNull { it.name.contains("model_settings") || it.name.contains("Slic3r_PE_model") }
                val modelSettings = modelSettingsEntry
                    ?.let { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }

                fun digestText(text: String?): String? {
                    if (text == null) return null
                    val digest = MessageDigest.getInstance("SHA-256")
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    digest.update(bytes)
                    return digest.digest().joinToString("") { "%02x".format(it) }
                }

                fun pickLines(text: String?, keys: List<String>): List<String> {
                    val lines = text?.lines().orEmpty()
                    return keys.mapNotNull { key ->
                        lines.firstOrNull { it.startsWith("$key ") || it.startsWith("$key=") }
                            ?.trim()
                    }
                }

                mapOf(
                    "projectSettingsEntry" to projectSettingsEntry?.name,
                    "projectSettingsSha256" to digestText(projectSettings),
                    "projectSettingsLines" to pickLines(
                        projectSettings,
                        listOf(
                            "single_extruder_multi_material",
                            "enable_prime_tower",
                            "extruder_count",
                            "is_extruder_used",
                            "wipe_tower_x",
                            "wipe_tower_y",
                            "prime_tower_width"
                        )
                    ),
                    "modelSettingsEntry" to modelSettingsEntry?.name,
                    "modelSettingsSha256" to digestText(modelSettings),
                    "modelSettingsLines" to pickLines(
                        modelSettings,
                        listOf(
                            "plater_name",
                            "extruder",
                            "filament_ids",
                            "is_extruder_used",
                            "wipe_tower_x",
                            "wipe_tower_y"
                        )
                    )
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun summarizeModelFile(file: File?): Map<String, Any?>? {
        if (file == null || !file.exists()) return null
        return mapOf(
            "path" to file.absolutePath,
            "sizeBytes" to file.length(),
            "lastModifiedMs" to file.lastModified(),
            "sha256" to sha256(file),
            "zipEntries" to if (file.extension.equals("3mf", ignoreCase = true)) summarizeZipEntries(file) else null,
            "embeddedConfig" to extractEmbeddedConfigSummary(file)
        )
    }

    private fun recordClipperFailure(source: String, message: String, autoRecoveryAttempted: Boolean) {
        diagnostics.recordEvent(
            "clipper_failure",
            mapOf(
                "source" to source,
                "message" to message,
                "autoRecoveryAttempted" to autoRecoveryAttempted,
                "currentModelPath" to currentModelFile?.absolutePath,
                "sourceModelPath" to sourceModelFile?.absolutePath,
                "rawInputPath" to rawInputFile?.absolutePath,
                "selectedPlateId" to recoveryPlateId.takeIf { it >= 0 }
            )
        )
    }

    @Volatile private var pendingThumbnailBitmap: android.graphics.Bitmap? = null
    fun setPendingThumbnailBitmap(bitmap: android.graphics.Bitmap?) {
        pendingThumbnailBitmap = bitmap
    }

    /**
     * Soft-cancel an in-progress slice.  Transitions immediately back to ModelLoaded so the
     * UI is responsive.  The native slice() call runs to completion in the background but its
     * result is discarded once it finishes (checked via [sliceCancelled]).
     *
     * A native rebuild would allow hard cancellation via a JNI flag; this approach avoids
     * that while still giving the user an immediate way out.
     */
    @Volatile private var sliceCancelled = false

    fun cancelSlicing() {
        if (_state.value is SlicerState.Slicing) {
            sliceCancelled = true
            backToModelLoaded()
            Log.i("SlicerVM", "Slicing cancelled by user (native call will still complete in background)")
        }
    }

    fun startSlicing() {
        // Consume the bitmap atomically before launching so it is cleared even if slicing
        // fails early or throws — avoids leaking a full-resolution screen-capture Bitmap.
        val capturedBitmap = pendingThumbnailBitmap.also { pendingThumbnailBitmap = null }
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            try {
                when (_state.value) {
                    is SlicerState.Loading -> {
                        Log.w("SlicerVM", "Ignoring slice request while model is still loading")
                        _state.value = SlicerState.Error("Model is still loading. Please wait a moment and try again.")
                        return@launch
                    }
                    is SlicerState.Idle -> {
                        Log.w("SlicerVM", "Ignoring slice request with no model loaded")
                        _state.value = SlicerState.Error("No model loaded")
                        return@launch
                    }
                    else -> Unit
                }
                if (currentModelFile == null) {
                    Log.w("SlicerVM", "Ignoring slice request because currentModelFile is null")
                    _state.value = SlicerState.Error("Model is not ready to slice yet")
                    return@launch
                }
                SlicingService.start(context)
                var maxPct = 0
                native.progressListener = { pct, stage ->
                    if (pct > maxPct) maxPct = pct
                    _state.value = SlicerState.Slicing(maxPct, stage)
                    SlicingService.updateProgress(context, maxPct, stage)
                }

                sliceCancelled = false
                _state.value = SlicerState.Slicing(0, "Preparing...")

                val firstSliceThisLaunch = diagnostics.markSliceStart()

                // Re-embed before slicing when needed: settings changes between slices
                // (overrides, extruder count, prime tower toggle) must reach the native
                // slicer via the embedded profile. 40+ profile_keys[] settings have no
                // applyConfigToPrusa() fallback — without re-embed they silently use stale
                // values from the initial loadModel() embed (B24 fix RC2).
                val remap = toolRemapSlots
                val needsReEmbed = remap != null || _config.value.extruderCount > 1 || profileNeedsReEmbed
                if (needsReEmbed) {
                    val src = sourceModelFile
                    // Use merged ThreeMfInfo (colours + extruder count from original file) so the
                    // re-embedded profile carries the correct extruder_count.  sourceModelInfo for
                    // plate files is plateInfo (0 colours); _threeMfInfo.value is correctly merged.
                    val srcInfo = _threeMfInfo.value ?: sourceModelInfo
                    val context = getApplication<Application>()
                    if (src != null && srcInfo != null) {
                        val reason = when {
                            remap != null -> "extruder remap $remap"
                            _config.value.extruderCount > 1 -> "${_config.value.extruderCount}-extruder embed"
                            else -> "settings changed since last slice (B24)"
                        }
                        Log.i("SlicerVM", "Re-embedding 3MF ($reason) before slicing")
                        val isSingleExtruderRefresh = profileNeedsReEmbed && remap == null && _config.value.extruderCount <= 1
                        if (!isSingleExtruderRefresh) {
                            // Multi-extruder/remap path: clear first to avoid OOM from holding
                            // two large model instances in native memory during re-load.
                            native.clearModel()
                        }
                        // Single-extruder settings refresh: skip clearModel() — files are small
                        // (no OOM risk) and clearModel()+loadModel() can corrupt native statics,
                        // causing "Coordinate outside allowed range" Clipper errors (I2).
                        val reembedded = embedProfile(src, srcInfo, transientWorkspaceDir())
                        currentModelFile = reembedded
                        val reloadOk = native.loadModel(reembedded.absolutePath)
                        diagnostics.recordEvent(
                            "native_model_reload_before_slice",
                            mapOf(
                                "success" to reloadOk,
                                "path" to reembedded.absolutePath
                            )
                        )
                        if (!reloadOk) {
                            throw IllegalStateException("Failed to reload model before slicing")
                        }
                        native.getModelInfo()?.let { reloadedInfo ->
                            lastModelInfo = reloadedInfo
                        }
                        profileNeedsReEmbed = false
                    }
                }

                // Do NOT clear+reload the model here.
                // Previously a clearModel()+loadModel() was done to "reset stale instance offsets
                // set by the 3D viewer", but setModelInstances() already calls obj->clear_instances()
                // internally, making the reload redundant. Worse, the reload leaves OrcaSlicer global
                // state inconsistent, causing "Coordinate outside allowed range" Clipper errors.
                // The remap path above handles its own reload when needed.

                // Apply model scale if non-default (before setModelInstances so it's included in trafo)
                val scale = _modelScale.value
                if (scale.x != 1f || scale.y != 1f || scale.z != 1f) {
                    native.setModelScale(scale.x, scale.y, scale.z)
                    Log.i("SlicerVM", "Applied model scale: ${scale.x}×${scale.y}×${scale.z}")
                }

                val copies = _copyCount.value
                val custom = customObjectPositions
                val mi = lastModelInfo

                // SAFETY CHECK: refuse to slice if model is larger than the bed.
                // A combined bounding box > 270mm means objects from multiple plates were loaded,
                // or the model genuinely doesn't fit. Slicing would produce off-bed toolpaths
                // that could crash the printhead into the frame.
                if (mi != null && mi.sizeX > 270f && mi.sizeY > 270f && custom == null) {
                    Log.e("SlicerVM", "Model too large for bed: ${mi.sizeX}×${mi.sizeY}mm — aborting slice")
                    _state.value = SlicerState.Error(
                        "Model bounding box (${mi.sizeX.toInt()}×${mi.sizeY.toInt()}mm) exceeds the 270×270mm bed.\n" +
                        "This usually means a multi-plate 3MF still contains all plates. " +
                        "Try reloading and reselecting the plate."
                    )
                    return@launch
                }

                if (custom != null) {
                    val ok = native.setModelInstances(custom)
                    Log.i("SlicerVM", "Using custom placement: ${custom.size / 2} instances (ok=$ok)")
                    diagnostics.recordEvent(
                        "set_model_instances_for_slice",
                        mapOf(
                            "success" to ok,
                            "mode" to "custom",
                            "instanceCount" to (custom.size / 2),
                            "positions" to custom.toList()
                        )
                    )
                } else {
                    // Auto-arrange: single copy → centered, multiple copies → grid
                    if (mi != null && mi.sizeX > 0f && mi.sizeY > 0f) {
                        val s = _modelScale.value
                        val positions = CopyArrangeCalculator.calculate(mi.sizeX * s.x, mi.sizeY * s.y, copies)
                        Log.i("SlicerVM", "setModelInstances: model=${mi.sizeX}×${mi.sizeY}mm " +
                            "pos=[${positions.toList().take(4)}]")
                        val ok = native.setModelInstances(positions)
                        if (!ok) Log.e("SlicerVM", "setModelInstances returned false — model may not be loaded")
                        Log.i("SlicerVM", "Auto-placed $copies instance(s) (ok=$ok)")
                        diagnostics.recordEvent(
                            "set_model_instances_for_slice",
                            mapOf(
                                "success" to ok,
                                "mode" to "auto",
                                "instanceCount" to copies,
                                "positions" to positions.toList(),
                                "scaledModelSizeX" to (mi.sizeX * s.x),
                                "scaledModelSizeY" to (mi.sizeY * s.y)
                            )
                        )
                    } else {
                        Log.w("SlicerVM", "Skipping setModelInstances: mi=${mi?.sizeX}×${mi?.sizeY}")
                    }
                }

                // Build the effective config for this slice.
                // resolveInto() applies OVERRIDE / ORCA_DEFAULT modes to the current UI config.
                // USE_FILE passthrough: base values from _config.value are used as-is.
                // We use a local copy — _config.value (the UI state) is never mutated here.
                val ov = slicingOverrides.value
                val resolvedSliceConfig = ov.resolveInto(_config.value).let { cfg ->
                    // Clamp wipe tower to bed bounds — an out-of-bounds tower can produce
                    // degenerate geometry that overflows Clipper2's int64 coordinate range.
                    if (cfg.wipeTowerEnabled) {
                        val maxX = (cfg.bedSizeX - cfg.wipeTowerWidth).coerceAtLeast(0f)
                        val maxY = (cfg.bedSizeY - cfg.wipeTowerWidth).coerceAtLeast(0f)
                        val clampedX = cfg.wipeTowerX.coerceIn(0f, maxX)
                        val clampedY = cfg.wipeTowerY.coerceIn(0f, maxY)
                        if (clampedX != cfg.wipeTowerX || clampedY != cfg.wipeTowerY) {
                            Log.w("SlicerVM", "Clamped wipe tower from (${cfg.wipeTowerX},${cfg.wipeTowerY}) to ($clampedX,$clampedY) — was outside bed bounds")
                        }
                        cfg.copy(wipeTowerX = clampedX, wipeTowerY = clampedY)
                    } else cfg
                }
                val sliceConfig = resolvedSliceConfig
                val profileOverrides = buildProfileOverrides(
                    sliceConfig,
                    sliceConfig.extruderCount,
                    toolRemapSlots,
                    hasSourceConfig = _sourceConfig.value != null
                )
                diagnostics.recordEvent(
                    "slice_started",
                    sliceDiagnosticsMap(
                        sliceConfig = sliceConfig,
                        profileOverrides = profileOverrides,
                        firstSliceThisLaunch = firstSliceThisLaunch
                    )
                )
                diagnostics.recordEvent(
                    "slice_geometry_snapshot",
                    sliceGeometrySnapshot(
                        sliceConfig = sliceConfig,
                        profileOverrides = profileOverrides,
                        firstSliceThisLaunch = firstSliceThisLaunch,
                        mi = mi,
                        copies = copies,
                        custom = custom,
                        remap = remap
                    )
                )
                diagnostics.recordEvent(
                    "slice_process_snapshot",
                    sliceProcessDiagnosticsMap()
                )
                diagnostics.recordEvent(
                    "slice_file_snapshot",
                    mapOf(
                        "rawInputFile" to summarizeModelFile(rawInputFile),
                        "sourceModelFile" to summarizeModelFile(sourceModelFile),
                        "currentModelFile" to summarizeModelFile(currentModelFile)
                    )
                )
                diagnostics.recordEvent(
                    "pre_slice_native_state",
                    mapOf(
                        "firstSliceThisLaunch" to firstSliceThisLaunch,
                        "nativeState" to safeNativeDiagnosticsState(),
                        "currentModelPath" to currentModelFile?.absolutePath,
                        "sourceModelPath" to sourceModelFile?.absolutePath,
                        "rawInputPath" to rawInputFile?.absolutePath
                    )
                )
                Log.i("SlicerVM", "Resolved slice config: layer=${sliceConfig.layerHeight} " +
                    "infill=${sliceConfig.fillDensity} walls=${sliceConfig.perimeters} " +
                    "support=${sliceConfig.supportEnabled} speed=${sliceConfig.printSpeed} " +
                    "extruders=${sliceConfig.extruderCount} wipeTower=${sliceConfig.wipeTowerEnabled} " +
                    "wipeTowerXY=(${sliceConfig.wipeTowerX},${sliceConfig.wipeTowerY})")

                diagnostics.markSliceInProgress(currentModelFile!!.name)

                val result = native.slice(sliceConfig)

                // If the user cancelled while the native call was running, discard the result.
                if (sliceCancelled) {
                    Log.i("SlicerVM", "Discarding slice result after user cancel")
                    return@launch
                }

                if (result != null && result.success) {
                    val layerToolMetadataFile = when {
                        _threeMfInfo.value?.hasLayerToolChanges != true -> null
                        sourceModelFile?.exists() == true -> sourceModelFile
                        else -> currentModelFile
                    }
                    val injectedLayerToolPause = layerToolMetadataFile
                        ?.let { LayerToolPauseInjector.injectFrom3mf(result.gcodePath, it) }
                        ?: false
                    if (injectedLayerToolPause) {
                        Log.i(
                            "SlicerVM",
                            "Injected layer-change pause commands into ${result.gcodePath} using ${layerToolMetadataFile?.name}"
                        )
                    }
                    val outputValidation = validateSliceOutput(
                        result,
                        buildExpectedModelFootprint(mi, copies, custom),
                        // Only force pause-segment coloring when we actually injected pause markers.
                        // Painted/toolchange workflows should continue to use T-command extruder indices.
                        colorSegmentsByPausePrint = injectedLayerToolPause
                    )
                    diagnostics.recordEvent("slice_output_validation", outputValidation.summary)
                    if (outputValidation.errorMessage != null) {
                        diagnostics.recordEvent(
                            "slice_output_invalid",
                            outputValidation.summary + ("reason" to outputValidation.errorMessage)
                        )
                        diagnostics.clearSliceInProgress()
                        _state.value = SlicerState.Error(outputValidation.errorMessage)
                        return@launch
                    }

                    diagnostics.recordEvent(
                        "slice_succeeded",
                        mapOf(
                            "gcodePath" to result.gcodePath,
                            "totalLayers" to result.totalLayers,
                            "estimatedTimeSeconds" to result.estimatedTimeSeconds
                        )
                    )
                    // Post-process G-code to remap compact tool indices to physical slots.
                    // OrcaSlicer sliced in compact mode (T0,T1,…) — remap to actual printer
                    // slots (e.g. T2,T3 for E3+E4) and fix SM_ command EXTRUDER/INDEX params.
                    val slots = toolRemapSlots
                    if (slots != null) {
                        GcodeToolRemapper.remap(result.gcodePath, slots)
                        Log.i("SlicerVM", "Post-processed G-code: remapped tools to physical slots $slots")
                    }
                    // Inject preview thumbnails into G-code for Klipper/Moonraker.
                    // 3MF: extract preview image from ZIP. STL: fall back to GL capture bitmap.
                    try {
                        val sourcePath = rawInputFile?.absolutePath ?: sourceModelFile?.absolutePath ?: currentModelFile?.absolutePath
                        val injected = sourcePath != null && GcodeThumbnailInjector.inject(result.gcodePath, sourcePath)
                        if (injected) {
                            Log.i("SlicerVM", "Thumbnails injected from 3MF preview")
                        } else if (capturedBitmap != null && GcodeThumbnailInjector.injectFromBitmap(result.gcodePath, capturedBitmap)) {
                            Log.i("SlicerVM", "Thumbnails injected from GL capture")
                        }
                    } catch (e: Throwable) {
                        Log.w("SlicerVM", "Thumbnail injection failed (non-fatal): ${e.message}")
                    }

                    _state.value = SlicerState.SliceComplete(result)
                    _gcodePreview.value = native.getGcodePreview(50)
                    _parsedGcode.value = outputValidation.parsedGcode
                    settingsRepo.saveSliceConfig(_config.value)
                    // Save job to history
                    val cfg = _config.value
                    sliceJobDao.insert(
                        SliceJob(
                            modelName = currentModelName.ifEmpty { "Unknown" },
                            gcodePath = result.gcodePath,
                            totalLayers = result.totalLayers,
                            estimatedTimeSeconds = result.estimatedTimeSeconds,
                            estimatedFilamentMm = result.estimatedFilamentMm,
                            layerHeight = cfg.layerHeight,
                            fillDensity = cfg.fillDensity,
                            nozzleTemp = cfg.nozzleTemp,
                            bedTemp = cfg.bedTemp,
                            supportEnabled = cfg.supportEnabled,
                            filamentType = cfg.filamentType
                        )
                    )
                } else {
                    val errorMsg = result?.errorMessage ?: "Slicing failed"
                    if (isClipperError(errorMsg)) {
                        recordClipperFailure(
                            source = "slice_result",
                            message = errorMsg,
                            autoRecoveryAttempted = clipperRetryAttempted
                        )
                    }
                    if (isClipperError(errorMsg) && !clipperRetryAttempted) {
                        Log.w("SlicerVM", "Clipper error detected in slice result, attempting auto-recovery")
                        clipperRetryAttempted = true
                        attemptClipperRecovery()
                        return@launch
                    }
                    _state.value = SlicerState.Error(clipperUserMessage(errorMsg))
                }
            } catch (e: Throwable) {
                Log.e("SlicerVM", "Unexpected error during slicing", e)
                val errorMsg = e.message ?: e.javaClass.simpleName
                if (isClipperError(errorMsg)) {
                    recordClipperFailure(
                        source = "slice_exception",
                        message = errorMsg,
                        autoRecoveryAttempted = clipperRetryAttempted
                    )
                }
                if (isClipperError(errorMsg) && !clipperRetryAttempted) {
                    Log.w("SlicerVM", "Clipper error detected in exception, attempting auto-recovery")
                    clipperRetryAttempted = true
                    attemptClipperRecovery()
                    return@launch
                }
                _state.value = SlicerState.Error(clipperUserMessage("Slicing error: $errorMsg"))
            } finally {
                sliceCancelled = false
                diagnostics.clearSliceInProgress()
                native.progressListener = null
                SlicingService.stop(context)
            }
        }
    }

    /** Flag to prevent infinite retry loops — reset on each new model load. */
    private var clipperRetryAttempted = false

    private fun isClipperError(msg: String): Boolean {
        return msg.contains("Coordinate outside allowed range", ignoreCase = true) ||
            msg.contains("clipper", ignoreCase = true)
    }

    private fun validateSliceOutput(
        result: SliceResult,
        expectedFootprint: ExpectedModelFootprint?,
        colorSegmentsByPausePrint: Boolean = false
    ): SliceOutputValidation {
        val gcodeFile = File(result.gcodePath)
        val baseSummary = linkedMapOf<String, Any?>(
            "gcodePath" to result.gcodePath,
            "fileExists" to gcodeFile.exists(),
            "fileSizeBytes" to gcodeFile.length(),
            "nativeTotalLayers" to result.totalLayers,
            "nativeEstimatedTimeSeconds" to result.estimatedTimeSeconds,
            "nativeEstimatedFilamentMm" to result.estimatedFilamentMm,
            "expectedModelFootprint" to expectedFootprint?.let {
                mapOf(
                    "minX" to it.minX,
                    "maxX" to it.maxX,
                    "minY" to it.minY,
                    "maxY" to it.maxY,
                    "instanceCount" to it.instanceCount
                )
            }
        )
        if (!gcodeFile.exists() || gcodeFile.length() <= 0L) {
            return SliceOutputValidation(
                parsedGcode = null,
                summary = baseSummary,
                errorMessage = "Slicing produced no usable G-code output.\n\nTry Reset App State and slice again."
            )
        }

        return try {
            val parsed = GcodeParser.parse(gcodeFile, colorSegmentsByPausePrint = colorSegmentsByPausePrint)
            val outputSummary = GcodeValidator.summarizeParsedOutput(parsed)
            val modelBounds = outputSummary.modelExtrudeBounds
            val nonPrimeBounds = outputSummary.nonPrimeExtrudeBounds
            val suspiciousLineContexts = buildSuspiciousModelLineContexts(
                gcodeFile = gcodeFile,
                samples = outputSummary.suspiciousModelSamples
            )
            val overlapsExpectedModelFootprint = expectedFootprint?.let { expected ->
                modelBounds?.let { actual ->
                    rectanglesOverlap(
                        expected.minX,
                        expected.maxX,
                        expected.minY,
                        expected.maxY,
                        actual.minX,
                        actual.maxX,
                        actual.minY,
                        actual.maxY
                    )
                }
            }
            val summary = baseSummary + mapOf(
                "parsedLayerCount" to outputSummary.layerCount,
                "parsedTotalMoves" to outputSummary.totalMoves,
                "parsedExtrudeMoves" to outputSummary.extrudeMoves,
                "parsedNonPrimeExtrudeMoves" to outputSummary.nonPrimeExtrudeMoves,
                "parsedPrimeTowerExtrudeMoves" to outputSummary.primeTowerExtrudeMoves,
                "parsedModelExtrudeMoves" to outputSummary.modelExtrudeMoves,
                "parsedSkirtExtrudeMoves" to outputSummary.skirtExtrudeMoves,
                "parsedSupportExtrudeMoves" to outputSummary.supportExtrudeMoves,
                "parsedHelperExtrudeMoves" to outputSummary.helperExtrudeMoves,
                "parsedSuspiciousModelExtrudeMoves" to outputSummary.suspiciousModelExtrudeMoves,
                "parsedSuspiciousModelSamples" to outputSummary.suspiciousModelSamples.map {
                    mapOf(
                        "x0" to it.x0,
                        "y0" to it.y0,
                        "x1" to it.x1,
                        "y1" to it.y1,
                        "featureType" to it.featureType,
                        "lineNumber" to it.lineNumber,
                        "featureLabel" to it.featureLabel
                    )
                },
                "parsedSuspiciousModelLineContexts" to suspiciousLineContexts,
                "parsedModelExtrudeBounds" to modelBounds?.let {
                    mapOf(
                        "minX" to it.minX,
                        "maxX" to it.maxX,
                        "minY" to it.minY,
                        "maxY" to it.maxY,
                        "moveCount" to it.moveCount
                    )
                },
                "parsedNonPrimeExtrudeBounds" to nonPrimeBounds?.let {
                    mapOf(
                        "minX" to it.minX,
                        "maxX" to it.maxX,
                        "minY" to it.minY,
                        "maxY" to it.maxY,
                        "moveCount" to it.moveCount
                    )
                },
                "overlapsExpectedModelFootprint" to overlapsExpectedModelFootprint,
                "parsedWipeTowerFilamentMm" to parsed.wipeTowerFilamentMm
            )
            val errorMessage = when {
                GcodeValidator.isEffectivelyEmpty(outputSummary) ->
                    "Slicing produced empty or invalid output.\n\nThe generated G-code did not contain any printable model extrusion. Try Reset App State and slice again."
                GcodeValidator.hasSuspiciousModelGeometry(outputSummary) ->
                    "Slicing produced invalid output.\n\nThe generated model extrusion contained impossible coordinates. Try Reset App State and slice again."
                overlapsExpectedModelFootprint == false ->
                    "Slicing produced invalid output.\n\nThe generated model extrusion did not overlap the expected model footprint. Try Reset App State and slice again."
                else -> null
            }

            SliceOutputValidation(
                parsedGcode = parsed,
                summary = summary,
                errorMessage = errorMessage
            )
        } catch (t: Throwable) {
            SliceOutputValidation(
                parsedGcode = null,
                summary = baseSummary + mapOf(
                    "parseError" to (t.message ?: t.javaClass.simpleName)
                ),
                errorMessage = "Slicing produced unreadable G-code output.\n\nTry Reset App State and slice again."
            )
        }
    }

    private fun buildSuspiciousModelLineContexts(
        gcodeFile: File,
        samples: List<GcodeValidator.MoveSample>
    ): List<Map<String, Any?>> {
        if (samples.isEmpty() || !gcodeFile.exists()) return emptyList()
        val lineNumbers = samples
            .mapNotNull { it.lineNumber.takeIf { line -> line > 0 } }
            .distinct()
            .take(3)
        if (lineNumbers.isEmpty()) return emptyList()

        val lines = try {
            gcodeFile.readLines()
        } catch (_: Throwable) {
            return emptyList()
        }

        val contexts = mutableListOf<Map<String, Any?>>()
        for (lineNumber in lineNumbers) {
            val idx = lineNumber - 1
            if (idx !in lines.indices) continue
            val start = maxOf(0, idx - 2)
            val end = minOf(lines.lastIndex, idx + 2)
            contexts += mapOf(
                "lineNumber" to lineNumber,
                "windowStart" to start + 1,
                "windowEnd" to end + 1,
                "lines" to (start..end).map { rawIndex ->
                    mapOf(
                        "lineNumber" to rawIndex + 1,
                        "text" to lines[rawIndex]
                    )
                }
            )
        }
        return contexts
    }

    private fun buildExpectedModelFootprint(
        mi: ModelInfo?,
        copies: Int,
        custom: FloatArray?
    ): ExpectedModelFootprint? {
        if (mi == null || mi.sizeX <= 0f || mi.sizeY <= 0f) return null
        val scale = _modelScale.value
        val scaledSizeX = mi.sizeX * scale.x
        val scaledSizeY = mi.sizeY * scale.y
        if (scaledSizeX <= 0f || scaledSizeY <= 0f) return null
        val positions = custom ?: CopyArrangeCalculator.calculate(scaledSizeX, scaledSizeY, copies)
        if (positions.isEmpty()) return null
        // CopyArrangeCalculator returns min-corner (lower-left) coordinates, not centers.
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (i in positions.indices step 2) {
            val ox = positions[i]
            val oy = positions.getOrNull(i + 1) ?: continue
            minX = minOf(minX, ox)
            maxX = maxOf(maxX, ox + scaledSizeX)
            minY = minOf(minY, oy)
            maxY = maxOf(maxY, oy + scaledSizeY)
        }
        if (minX.isInfinite() || maxX.isInfinite() || minY.isInfinite() || maxY.isInfinite()) {
            return null
        }
        return ExpectedModelFootprint(
            minX = minX,
            maxX = maxX,
            minY = minY,
            maxY = maxY,
            instanceCount = positions.size / 2
        )
    }

    private fun rectanglesOverlap(
        aMinX: Float,
        aMaxX: Float,
        aMinY: Float,
        aMaxY: Float,
        bMinX: Float,
        bMaxX: Float,
        bMinY: Float,
        bMaxY: Float
    ): Boolean {
        return maxOf(aMinX, bMinX) <= minOf(aMaxX, bMaxX) &&
            maxOf(aMinY, bMinY) <= minOf(aMaxY, bMaxY)
    }

    /**
     * Produce a user-friendly error message for Clipper errors, with actionable suggestions.
     * If copyCount > 4, automatically halve it to help avoid the overflow on retry.
     */
    private fun clipperUserMessage(rawMsg: String): String {
        if (!isClipperError(rawMsg)) return rawMsg

        val copies = _copyCount.value
        val base = "Slicing failed: geometry overflow. Try reducing copies or moving the wipe tower."
        return if (copies > 4) {
            val reduced = (copies / 2).coerceAtLeast(1)
            _copyCount.value = reduced
            customObjectPositions = null
            Log.i("SlicerVM", "Clipper error: auto-reduced copyCount from $copies to $reduced")
            "$base\n\nCopy count was $copies — automatically reduced to $reduced. Tap Slice to retry."
        } else {
            base
        }
    }

    /**
     * Proactive recovery from Clipper errors: clear all intermediate cache files,
     * reset native state, and surface a recoverable error instead of killing the app.
     *
     * Uses rawInputFile (the pre-sanitize raw copy, e.g. "Button-for-S-trousers.3mf") rather
     * than sourceModelFile / plateFile because those are intermediate files (sanitized_* /
     * plate*.3mf) and are deleted by clearIntermediateCache().  rawInputFile has no prefix
     * and survives the cache clear.
     */
    private fun attemptClipperRecovery() {
        Log.w("SlicerVM", "Clipper error: leaving app running and surfacing recoverable error")
        diagnostics.recordEvent(
            "clipper_recovery_deferred",
            mapOf(
                "rawFile" to rawInputFile?.absolutePath,
                "plateId" to recoveryPlateId,
                "currentModelPath" to currentModelFile?.absolutePath
            )
        )
        native.clearModel()
        diagnostics.clearSliceInProgress()
        _state.value = SlicerState.Error(
            "Slicing failed: geometry overflow.\n\n" +
                "Try reducing copies, moving the wipe tower, or restarting the app and trying again."
        )
    }

    /**
     * Nuclear option: clear transient cache, reset native state, and exit the current process.
     * The user can reopen the app manually to get a fresh JNI/native init.
     */
    fun restartApp() {
        val app = getApplication<Application>()
        diagnostics.markUpgradeRestartRequested("manual_restart", safeNativeDiagnosticsState())
        UpgradeDetector.clearIntermediateCache(app.filesDir)
        app.cacheDir.deleteRecursively()
        diagnostics.consumePendingUpgradeMarker()
        native.clearModel()
        clearModel()
        diagnostics.recordEvent(
            "manual_restart_requested",
            mapOf(
                "clearedIntermediateCache" to true,
                "clearedCacheDir" to true
            )
        )
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    // ---- Filament Library ----
    fun addFilament(profile: FilamentProfile) {
        viewModelScope.launch(Dispatchers.IO) {
            filamentDao.insert(profile)
        }
    }

    fun importFilaments(profiles: List<FilamentProfile>) {
        viewModelScope.launch(Dispatchers.IO) {
            profiles.forEach { filamentDao.insert(it) }
        }
    }

    fun updateFilament(profile: FilamentProfile) {
        viewModelScope.launch(Dispatchers.IO) {
            filamentDao.update(profile)
        }
    }

    fun deleteFilament(profile: FilamentProfile) {
        viewModelScope.launch(Dispatchers.IO) {
            filamentDao.delete(profile)
        }
    }

    fun setDefaultFilament(profile: FilamentProfile) {
        viewModelScope.launch(Dispatchers.IO) {
            filamentDao.clearAllDefaults()
            filamentDao.update(profile.copy(isDefault = true))
        }
    }

    fun applyFilament(profile: FilamentProfile) {
        updateConfig {
            it.copy(
                nozzleTemp = profile.nozzleTemp,
                bedTemp = profile.bedTemp,
                printSpeed = profile.printSpeed,
                retractLength = profile.retractLength,
                retractSpeed = profile.retractSpeed,
                filamentType = profile.material
            )
        }
    }

    // ---- Job History ----
    fun deleteJob(job: SliceJob) {
        viewModelScope.launch(Dispatchers.IO) {
            sliceJobDao.delete(job)
        }
    }

    fun deleteAllJobs() {
        viewModelScope.launch(Dispatchers.IO) {
            sliceJobDao.deleteAll()
        }
    }

    fun shareJobGcode(job: SliceJob) {
        val context = getApplication<Application>()
        val gcodeFile = File(job.gcodePath)
        if (!gcodeFile.exists()) return

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            gcodeFile
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Share G-code").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareDiagnostics() {
        val context = getApplication<Application>()
        val latestError = (_state.value as? SlicerState.Error)?.message
        val bundle = diagnostics.buildBundle(latestError)
        if (!bundle.exists()) return
        diagnostics.recordEvent(
            "diagnostics_shared",
            mapOf(
                "bundlePath" to bundle.absolutePath,
                "latestError" to latestError
            )
        )
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            bundle
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            Intent.createChooser(intent, "Share Diagnostics")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun exportBackupAsync(onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val cfg = _config.value
            val overrides = slicingOverrides.value
            val presets = extruderPresets.value
            val printerUrl = settingsRepo.printerUrl.first()
            val profiles = filamentDao.getAll().first()
            val profileMap = profiles.associateBy { it.id }
            val cookies = settingsRepo.makerWorldCookies.first()
            val cookiesEnabled = settingsRepo.makerWorldCookiesEnabled.first()
            val json = SettingsBackup.export(cfg, overrides, printerUrl, presets, profiles,
                filamentNameResolver = { id -> profileMap[id]?.name },
                makerWorldCookies = cookies,
                makerWorldCookiesEnabled = cookiesEnabled
            )
            onResult(json)
        }
    }

    fun importBackup(json: String, onImported: (hasPrinterUrl: Boolean) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val data = SettingsBackup.import(json)
                data.sliceConfig?.let {
                    val normalized = normalizeImportedSliceConfig(it)
                    _config.value = normalized
                    settingsRepo.saveSliceConfig(normalized)
                }
                data.slicingOverrides?.let {
                    settingsRepo.saveSlicingOverrides(it)
                }
                val hasPrinterUrl = !data.printerUrl.isNullOrBlank()
                data.printerUrl?.let {
                    settingsRepo.savePrinterUrl(it)
                }
                data.makerWorldCookies?.let {
                    settingsRepo.saveMakerWorldCookies(it)
                }
                data.makerWorldCookiesEnabled?.let {
                    settingsRepo.saveMakerWorldCookiesEnabled(it)
                }
                // Insert filament profiles first so we can resolve names → IDs
                val nameToId = mutableMapOf<String, Long>()
                data.filamentProfiles?.let { profiles ->
                    profiles.forEach { profile ->
                        val newId = filamentDao.insert(profile)
                        nameToId[profile.name] = newId
                    }
                }
                // Resolve filament profile names to new IDs on extruder presets
                val root = JSONObject(json)
                val presetsArr = root.optJSONArray("extruderPresets")
                if (presetsArr != null && data.extruderPresets != null) {
                    val parsed = SettingsBackup.parseExtruderPresetsWithNames(presetsArr)
                    val resolved = parsed.map { p ->
                        val resolvedId = p.filamentProfileName?.let { nameToId[it] }
                        p.preset.copy(filamentProfileId = resolvedId)
                    }
                    settingsRepo.saveExtruderPresets(resolved)
                } else {
                    data.extruderPresets?.let {
                        settingsRepo.saveExtruderPresets(it)
                    }
                }
                Log.i("SlicerVM", "Settings backup imported successfully")
                onImported(hasPrinterUrl)
            } catch (e: Exception) {
                Log.e("SlicerVM", "Failed to import backup: ${e.message}", e)
            }
        }
    }

    fun clearModel() {
        native.clearModel()
        rawInputFile = null
        recoveryOrigInfo = null
        recoveryPlateId = -1
        clipperRetryAttempted = false
        _state.value = SlicerState.Idle
        _gcodePreview.value = ""
        _parsedGcode.value = null
        _activeExtruderColors.value = emptyList()
        _selectedExtruder.value = 0
        _threeMfInfo.value = null
        _showPlateSelector.value = false
        _showMultiColorDialog.value = false
        currentModelFile = null
        lastModelInfo = null
        _modelInfo.value = null
        _copyCount.value = 1
        customObjectPositions = null
        customWipeTowerPos = null
        // Reset multi-extruder config to single extruder
        _config.value = _config.value.copy(
            extruderCount = 1,
            extruderTemps = intArrayOf(),
            extruderRetractLength = floatArrayOf(),
            extruderRetractSpeed = floatArrayOf(),
            wipeTowerEnabled = false
        )
    }

    private fun deleteRecursivelyCount(file: File): Int {
        if (!file.exists()) return 0
        var count = 0
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                count += deleteRecursivelyCount(child)
            }
        }
        if (file.delete()) count++
        return count
    }

    fun resetAppState(): Int {
        val ctx = getApplication<Application>()
        val filesCleared = deleteRecursivelyCount(ctx.filesDir)
        val cacheCleared = deleteRecursivelyCount(ctx.cacheDir)
        diagnostics.consumePendingUpgradeMarker()
        diagnostics.consumeClipperRecoveryPending()
        diagnostics.consumeSliceInProgressMarker()
        val count = filesCleared + cacheCleared
        native.clearModel()
        clearModel()
        diagnostics.recordEvent(
            "manual_reset_app_state",
            mapOf(
                "filesCleared" to filesCleared,
                "cacheCleared" to cacheCleared,
                "totalCleared" to count
            )
        )
        android.os.Process.killProcess(android.os.Process.myPid())
        return count
    }

    fun loadProfile(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            native.loadProfile(path)
        }
    }

    fun shareGcode() {
        val state = _state.value
        if (state !is SlicerState.SliceComplete) return

        val context = getApplication<Application>()
        val gcodeFile = File(state.result.gcodePath)
        if (!gcodeFile.exists()) return

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            gcodeFile
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Share G-code").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun saveGcodeTo(uri: Uri) {
        val state = _state.value
        if (state !is SlicerState.SliceComplete) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val gcodeFile = File(state.result.gcodePath)
                context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    gcodeFile.inputStream().use { it.copyTo(out) }
                }
            } catch (_: Throwable) {
                // Silent fail — user may have cancelled the save dialog
            }
        }
    }

    /** Public accessor for file picker validation in MainActivity. */
    fun getFileDisplayName(uri: Uri): String? =
        getDisplayName(getApplication(), uri)

    /** Immediately transition to Loading state when the file picker returns a URI. */
    fun setLoadingFromPicker() {
        _state.value = SlicerState.Loading("Loading…")
    }

    /** Show error for unsupported file type selected in the picker. */
    fun showUnsupportedFileError(filename: String) {
        val ext = filename.substringAfterLast('.', "")
        _state.value = SlicerState.Error(
            "Unsupported file type: .$ext\n\n" +
                "Please open a 3MF, STL, OBJ, or STEP model file in U1 Slicer."
        )
    }

    private fun getDisplayName(context: android.content.Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
    }

    companion object {
        /**
         * Convert a hex color string (#RRGGBB or RRGGBB) to a FloatArray of [R, G, B, 1f].
         * Returns a neutral grey on parse failure. Callable without a ViewModel instance.
         */
        fun staticHexColorToFloatArray(hex: String): FloatArray {
            if (hex.isBlank()) return floatArrayOf(0.7f, 0.7f, 0.7f, 1f)
            return try {
                val c = android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex")
                floatArrayOf(
                    android.graphics.Color.red(c) / 255f,
                    android.graphics.Color.green(c) / 255f,
                    android.graphics.Color.blue(c) / 255f,
                    1f
                )
            } catch (_: Exception) { floatArrayOf(0.91f, 0.48f, 0f, 1f) }
        }

        internal fun normalizeImportedSliceConfig(config: SliceConfig): SliceConfig {
            // Older backups can carry skirt_loops=1 from before the Snapmaker U1
            // default was corrected to 0. Keep imports aligned with SettingsRepository.
            return config.copy(skirtLoops = 0)
        }

        /** File extensions accepted by the file picker. */
        val SUPPORTED_EXTENSIONS = setOf("3mf", "stl", "obj", "step", "stp")

        /** Returns true if the filename has a supported 3D model extension. */
        fun isSupportedFile(filename: String): Boolean {
            val ext = filename.substringAfterLast('.', "").lowercase()
            return ext in SUPPORTED_EXTENSIONS
        }

        /**
         * Normalize filenames coming from Android download/content providers.
         *
         * Some download sources append RFC 5987 style metadata after the visible name,
         * e.g. "super+clean.3mf;filename*=utf-8''super+clean.3mf".
         * We only care about the display name before that suffix.
         */
        internal fun normalizeIncomingFilename(filename: String): String =
            filename.substringBefore(";filename", filename)

        /**
         * Merges a sanitized ThreeMfInfo (processedInfo) with the original parse (origInfo).
         *
         * BambuSanitizer.process() strips filament_sequence.json, project_settings.config and
         * similar metadata, so processedInfo carries no color/extruder data.  origInfo retains
         * the full color detection results.  We take structural fields from processedInfo
         * (objects, isBambu, isMultiPlate) and color/extruder metadata from origInfo.
         *
         * Extracted as a pure function so it can be unit-tested independently of the ViewModel.
         */
        fun mergeThreeMfInfo(
            processedInfo: com.u1.slicer.bambu.ThreeMfInfo,
            origInfo: com.u1.slicer.bambu.ThreeMfInfo
        ): com.u1.slicer.bambu.ThreeMfInfo = processedInfo.copy(
            isBambu = origInfo.isBambu,
            plates = origInfo.plates,
            hasPlateJsons = origInfo.hasPlateJsons,
            detectedColors = origInfo.detectedColors,
            detectedExtruderCount = origInfo.detectedExtruderCount,
            usedExtruderIndices = origInfo.usedExtruderIndices,
            hasPaintData = origInfo.hasPaintData,
            hasLayerToolChanges = origInfo.hasLayerToolChanges,
            hasMultiExtruderAssignments = origInfo.hasMultiExtruderAssignments,
            // Prefer the processed file's map when available: BambuSanitizer can inline
            // compound-object parts into concrete mesh object IDs, which is exactly what
            // the preview parser needs for per-part coloring (for example calicube).
            objectExtruderMap = processedInfo.objectExtruderMap.ifEmpty { origInfo.objectExtruderMap },
            layerToolSegments = origInfo.layerToolSegments
        )

        /**
         * Merges a single-plate extract (plateInfo, which has no color metadata because it was
         * extracted from the processed/embedded file) with the pre-select merged info (sourceInfo,
         * which has the full color/extruder metadata from the original parse).
         *
         * selectPlate() calls BambuSanitizer.extractPlate() on the processed file, so plateInfo
         * has 0 detected colors.  sourceInfo is the correctly-merged info from openModel().
         * We take the plate's structural fields but restore color/extruder metadata from sourceInfo.
         *
         * Extracted as a pure function for testability.
         */
        fun mergeThreeMfInfoForPlate(
            plateInfo: com.u1.slicer.bambu.ThreeMfInfo,
            sourceInfo: com.u1.slicer.bambu.ThreeMfInfo,
            selectedPlateId: Int? = null
        ): com.u1.slicer.bambu.ThreeMfInfo {
            val sourcePlate = selectedPlateId?.let { plateId ->
                sourceInfo.plates.firstOrNull { it.plateId == plateId }
            }
            val sourcePlateFilamentIndices = selectedPlateId?.let { plateId ->
                sourceInfo.plates
                    .firstOrNull { it.plateId == plateId }
                    ?.filamentIndices
                    ?.filter { it > 0 }
                    ?.toSet()
                    ?: emptySet()
            } ?: emptySet()
            val sourcePlateObjectExtruders = sourcePlate
                ?.objectIds
                ?.mapNotNull { objectId -> sourceInfo.objectExtruderMap[objectId] }
                ?.filter { it > 0 }
                ?.toSet()
                ?: emptySet()
            val plateFilamentIndices = plateInfo.plates
                .flatMap { it.filamentIndices }
                .filter { it > 0 }
                .toSet()
            val mergedUsedExtruderIndices = if (sourceInfo.hasLayerToolChanges) {
                linkedSetOf<Int>().apply {
                    addAll(sourcePlateObjectExtruders.sorted())
                    addAll(sourcePlateFilamentIndices.sorted())
                    addAll(plateFilamentIndices.sorted())
                    addAll(plateInfo.usedExtruderIndices.filter { it > 0 }.sorted())
                }
            } else {
                plateInfo.usedExtruderIndices
            }
            // Layer-change models use per-layer tool colors rather than per-object
            // extruder assignment, so filtering to plate filament indices collapses the
            // preview back to one colour. Keep the full source palette in that case.
            val layerToolOnly = sourceInfo.hasLayerToolChanges &&
                !sourceInfo.hasPaintData &&
                !sourceInfo.hasMultiExtruderAssignments
            val filteredColors = if (layerToolOnly) {
                val selectedLayerToolColors = linkedSetOf<String>()
                val selectedExtruders = mergedUsedExtruderIndices.filter { it > 0 }.sorted()
                if (selectedExtruders.isNotEmpty()) {
                    selectedExtruders.forEach { extruderIndex ->
                        sourceInfo.detectedColors.getOrNull(extruderIndex - 1)
                            ?.let { selectedLayerToolColors.add(it) }
                    }
                }
                selectedLayerToolColors.toList().ifEmpty {
                    plateInfo.detectedColors.ifEmpty { sourceInfo.detectedColors }
                }
            } else {
                // Filter colors to only those extruder indices used on this plate.
                // usedExtruderIndices are 1-based; detectedColors is 0-indexed.
                // Prefer the selected source-plate indices first, because restructured plate
                // extraction can over-report transient/toolchange indices in plateInfo.
                val usedIndices = if (sourceInfo.hasMultiExtruderAssignments) {
                    if (sourceInfo.hasLayerToolChanges) {
                        // Layer-tool + assignment models are prone to over-reported transient indices;
                        // trust the selected source plate first to avoid phantom extra colours.
                        when {
                            sourcePlateFilamentIndices.isNotEmpty() -> sourcePlateFilamentIndices
                            sourcePlateObjectExtruders.isNotEmpty() -> sourcePlateObjectExtruders
                            plateFilamentIndices.isNotEmpty() -> plateFilamentIndices
                            else -> plateInfo.usedExtruderIndices
                        }
                    } else {
                        // Pure per-object assignment models (no layer-tool metadata) can have sparse
                        // source object mappings after restructure; keep the richest extracted set.
                        listOf(
                            sourcePlateFilamentIndices,
                            sourcePlateObjectExtruders,
                            plateFilamentIndices,
                            plateInfo.usedExtruderIndices
                        ).maxByOrNull { it.size } ?: emptySet()
                    }
                } else {
                    listOf(
                        sourcePlateFilamentIndices,
                        plateFilamentIndices,
                        plateInfo.usedExtruderIndices
                    ).maxByOrNull { it.size } ?: emptySet()
                }
                val effectiveUsedIndices = if (
                    sourceInfo.hasMultiExtruderAssignments &&
                    sourceInfo.hasLayerToolChanges &&
                    usedIndices.size <= 1 &&
                    plateInfo.usedExtruderIndices.size > 1
                ) {
                    // Some restructured plates under-report selected source filament indices
                    // as a single base slot even though the plate actually uses one additional
                    // layer-tool slot. Expand to a stable dual-slot set instead of collapsing
                    // to one color or expanding to all over-reported transient slots.
                    val seed = usedIndices.firstOrNull() ?: plateInfo.usedExtruderIndices.minOrNull() ?: 1
                    val next = plateInfo.usedExtruderIndices
                        .filter { it > 0 && it != seed }
                        .sorted()
                        .firstOrNull()
                    linkedSetOf(seed).apply { next?.let { add(it) } }
                } else {
                    usedIndices
                }
                if (effectiveUsedIndices.isNotEmpty() && sourceInfo.detectedColors.size > 1) {
                    effectiveUsedIndices.sorted().mapNotNull { idx ->
                        sourceInfo.detectedColors.getOrNull(idx - 1) // 1-based → 0-indexed
                    }
                } else {
                    sourceInfo.detectedColors
                }
            }
            Log.i(
                "SlicerVM",
                "mergeThreeMfInfoForPlate: plate=$selectedPlateId layerTools=${sourceInfo.hasLayerToolChanges} layerToolOnly=$layerToolOnly " +
                    "sourcePlateObjectExtruders=$sourcePlateObjectExtruders sourcePlateFilamentIndices=$sourcePlateFilamentIndices " +
                    "plateInfo.usedExtruders=${plateInfo.usedExtruderIndices} mergedUsedExtruders=$mergedUsedExtruderIndices " +
                    "filteredColors=$filteredColors"
            )
            // Hueforge plate: single object, single filament, layer-tool changes, no paint.
            // The UI needs extruderCount > 1 and hasMultiExtruderAssignments=false to activate
            // the layerToolOnly recolor path.
            val isHueforgePlate = selectedPlateId != null &&
                sourceInfo.hasLayerToolChanges && !sourceInfo.hasPaintData &&
                sourcePlateObjectExtruders.size <= 1 && sourcePlateFilamentIndices.size <= 1
            return plateInfo.copy(
                isBambu = sourceInfo.isBambu,
                detectedColors = filteredColors,
                detectedExtruderCount = if (layerToolOnly) {
                    maxOf(plateInfo.detectedExtruderCount, filteredColors.size, mergedUsedExtruderIndices.size)
                } else if (isHueforgePlate) {
                    maxOf(filteredColors.size, 2)
                } else if (filteredColors.isNotEmpty()) {
                    filteredColors.size
                } else {
                    sourceInfo.detectedExtruderCount
                },
                usedExtruderIndices = mergedUsedExtruderIndices,
                hasPaintData = sourceInfo.hasPaintData,
                hasLayerToolChanges = sourceInfo.hasLayerToolChanges,
                hasMultiExtruderAssignments = if (sourcePlateObjectExtruders.size > 1) true
                    else if (isHueforgePlate) false
                    else sourceInfo.hasMultiExtruderAssignments,
                objectExtruderMap = plateInfo.objectExtruderMap.ifEmpty { sourceInfo.objectExtruderMap },
                layerToolSegments = sourceInfo.layerToolSegments
            )
        }

        internal fun resolvePreviewModelFile(
            rawInputFile: File?,
            sourceModelFile: File?,
            currentModelFile: File?,
            info: com.u1.slicer.bambu.ThreeMfInfo?,
            originalSourceConfig: Map<String, Any>?
        ): File? = when {
            // Only genuine H2C source files need the raw project_settings.config-driven preview
            // path. Other Bambu models, such as calicube, rely on the sanitized/restructured
            // mesh path to preserve per-object preview colouring.
            rawInputFile != null && info?.isBambu == true && !info.isMultiPlate &&
                isH2cSourceConfig(originalSourceConfig) -> rawInputFile
            sourceModelFile != null -> sourceModelFile
            else -> currentModelFile
        }

        internal fun resolvePlateSelectionSourceFile(
            sourceModelFile: File?,
            currentModelFile: File?
        ): File? = sourceModelFile ?: currentModelFile

        internal fun isH2cSourceConfig(config: Map<String, Any>?): Boolean {
            if (config.isNullOrEmpty()) return false
            fun anyString(value: Any?, predicate: (String) -> Boolean): Boolean = when (value) {
                null -> false
                is String -> predicate(value)
                is Iterable<*> -> value.any { anyString(it, predicate) }
                is Array<*> -> value.any { anyString(it, predicate) }
                else -> false
            }

            // H2C source files carry one of these explicit project markers. Generic machine
            // compatibility strings can also mention "H2C", so we intentionally do NOT scan
            // every config value for that substring.
            return anyString(config["filament_settings_id"]) { it.contains("@BBL H2C") } ||
                anyString(config["change_filament_gcode"]) { it.contains("H2C filament_change") }
        }

        internal fun buildPreviewSlotColors(
            extruderPresets: List<com.u1.slicer.data.ExtruderPreset>,
            usedSlots: List<Int>
        ): List<String> {
            val fullColors = MutableList(4) { "" }
            usedSlots.forEach { slotIndex ->
                if (slotIndex !in 0..3) return@forEach
                val presetColor = extruderPresets.firstOrNull { it.index == slotIndex }?.color
                fullColors[slotIndex] = presetColor
                    ?.takeIf { it.isNotBlank() }
                    ?: com.u1.slicer.data.ExtruderPreset.DEFAULT_COLORS[slotIndex]
            }
            return fullColors
        }
    }

}

/**
 * Build the profile overrides map for embedding into 3MF.
 * Extracted as a top-level function for testability.
 *
 * When [hasSourceConfig] is true (Bambu 3MF with its own config) and the support override
 * mode is USE_FILE, support keys are omitted so the file's original enable_support /
 * support_threshold_angle survive through ProfileEmbedder's preserve path.
 */
internal fun buildProfileOverridesImpl(
    cfg: SliceConfig,
    ov: SlicingOverrides,
    extCount: Int,
    usedSlots: List<Int>? = null,
    hasSourceConfig: Boolean = false
): Map<String, Any> {
    val temps: MutableList<String> = if (cfg.extruderTemps.size >= extCount) {
        cfg.extruderTemps.take(extCount).map { it.toString() }.toMutableList()
    } else {
        MutableList(extCount) { cfg.nozzleTemp.toString() }
    }

    val defaults = SlicingOverrides.ORCA_DEFAULTS

    fun <T> resolve(override: OverrideValue<T>, cfgValue: T, defaultKey: String): T {
        return when (override.mode) {
            OverrideMode.USE_FILE -> cfgValue
            OverrideMode.ORCA_DEFAULT -> {
                @Suppress("UNCHECKED_CAST")
                (defaults[defaultKey] as? T) ?: cfgValue
            }
            OverrideMode.OVERRIDE -> override.value ?: cfgValue
        }
    }

    val layerHeight = resolve(ov.layerHeight, cfg.layerHeight, "layerHeight")
    val infillDensity = resolve(ov.infillDensity, cfg.fillDensity, "infillDensity")
    val wallCount = resolve(ov.wallCount, cfg.perimeters, "wallCount")
    val infillPattern = resolve(ov.infillPattern, cfg.fillPattern, "infillPattern")
    val topShellLayers = resolve(ov.topShellLayers, cfg.topSolidLayers, "topShellLayers")
    val bottomShellLayers = resolve(ov.bottomShellLayers, cfg.bottomSolidLayers, "bottomShellLayers")
    val topSurfacePattern = resolve(ov.topSurfacePattern, "monotonic", "topSurfacePattern")
    val bottomSurfacePattern = resolve(ov.bottomSurfacePattern, "monotonic", "bottomSurfacePattern")
    val sparseInfillSpeed = resolve(ov.sparseInfillSpeed, 0, "sparseInfillSpeed")
    val reduceInfillRetraction = resolve(ov.reduceInfillRetraction, false, "reduceInfillRetraction")
    val wallGenerator = resolve(ov.wallGenerator, "arachne", "wallGenerator")
    val seamPosition = resolve(ov.seamPosition, "aligned", "seamPosition")
    val supportEnabled = resolve(ov.supports, cfg.supportEnabled, "supports")
    val supportType = resolve(ov.supportType, cfg.supportType, "supportType")
    val supportAngle = resolve(ov.supportAngle, cfg.supportAngle.toInt(), "supportAngle")
    val supportBuildPlateOnly = resolve(ov.supportBuildPlateOnly, false, "supportBuildPlateOnly")
    val supportPattern = resolve(ov.supportPattern, "default", "supportPattern")
    val supportPatternSpacing = resolve(ov.supportPatternSpacing, 2.5f, "supportPatternSpacing")
    val supportInterfaceTopLayers = resolve(ov.supportInterfaceTopLayers, 3, "supportInterfaceTopLayers")
    val supportInterfaceBottomLayers = resolve(ov.supportInterfaceBottomLayers, 0, "supportInterfaceBottomLayers")
    val supportFilament = resolve(ov.supportFilament, 0, "supportFilament")
    val supportInterfaceFilament = resolve(ov.supportInterfaceFilament, 0, "supportInterfaceFilament")
    val supportXyDistance = resolve(ov.supportXyDistance, 0.35f, "supportXyDistance")
    val supportInterfacePattern = resolve(ov.supportInterfacePattern, "auto", "supportInterfacePattern")
    val supportInterfaceSpacing = resolve(ov.supportInterfaceSpacing, 0.5f, "supportInterfaceSpacing")
    val supportSpeed = resolve(ov.supportSpeed, 0, "supportSpeed")
    val treeSupportBranchAngle = resolve(ov.treeSupportBranchAngle, 40, "treeSupportBranchAngle")
    val treeSupportBranchDistance = resolve(ov.treeSupportBranchDistance, 5.0f, "treeSupportBranchDistance")
    val treeSupportBranchDiameter = resolve(ov.treeSupportBranchDiameter, 5.0f, "treeSupportBranchDiameter")
    val brimWidth = resolve(ov.brimWidth, cfg.brimWidth, "brimWidth")
    val skirtLoops = resolve(ov.skirtLoops, cfg.skirtLoops, "skirtLoops")
    val bedTemp = resolve(ov.bedTemp, cfg.bedTemp, "bedTemp")
    val primeTower = ov.resolvePrimeTower(extCount, cfg.wipeTowerEnabled)

    val primeVolume = resolve(ov.primeVolume, 45, "primeVolume")
    val primeTowerBrimWidth = resolve(ov.primeTowerBrimWidth, 3f, "primeTowerBrimWidth")
    val primeTowerBrimChamfer = resolve(ov.primeTowerBrimChamfer, true, "primeTowerBrimChamfer")
    val primeTowerChamferMaxWidth = resolve(ov.primeTowerChamferMaxWidth, 5f, "primeTowerChamferMaxWidth")

    val result = mutableMapOf<String, Any>(
        "layer_height" to layerHeight.toString(),
        "initial_layer_print_height" to cfg.firstLayerHeight.toString(),
        "wall_loops" to wallCount.toString(),
        "top_shell_layers" to topShellLayers.toString(),
        "bottom_shell_layers" to bottomShellLayers.toString(),
        "top_surface_pattern" to topSurfacePattern,
        "bottom_surface_pattern" to bottomSurfacePattern,
        "sparse_infill_density" to "${(infillDensity * 100).toInt()}%",
        "sparse_infill_pattern" to infillPattern,
        "reduce_infill_retraction" to if (reduceInfillRetraction) "1" else "0",
        "wall_generator" to wallGenerator,
        "seam_position" to seamPosition,
        "travel_speed" to cfg.travelSpeed.toString(),
        "nozzle_temperature" to temps,
        "nozzle_temperature_initial_layer" to temps.toMutableList(),
        "bed_temperature" to mutableListOf(bedTemp.toString()),
        "bed_temperature_initial_layer" to mutableListOf(bedTemp.toString()),
        "brim_width" to brimWidth.toString(),
        // brim_type must be explicit — auto_brim from Bambu source files leaks through
        // profile_keys[] and adds geometry-based brims even when brim_width=0 (B31).
        "brim_type" to if (brimWidth > 0f) "manual_brim" else "no_brim",
        "skirt_loops" to skirtLoops.toString(),
        // OrcaSlicer defaults skirt_height=1; explicitly set to 0 when no skirt
        // to prevent skirt generation even if some other config path sets loops>0
        "skirt_height" to if (skirtLoops > 0) "1" else "0",
        "enable_prime_tower" to if (primeTower) "1" else "0",
        "prime_tower_width" to cfg.wipeTowerWidth.toString(),
        "wipe_tower_x" to MutableList(extCount) { cfg.wipeTowerX.toString() },
        "wipe_tower_y" to MutableList(extCount) { cfg.wipeTowerY.toString() },
        "prime_volume" to primeVolume.toString(),
        "prime_tower_brim_width" to primeTowerBrimWidth.toString(),
        "prime_tower_brim_chamfer" to if (primeTowerBrimChamfer) "1" else "0",
        "prime_tower_brim_chamfer_max_width" to primeTowerChamferMaxWidth.toString()
    )

    // sparse_infill_speed: 0 means "auto" — only emit when the user has overridden to a
    // positive value; otherwise let applyConfigToPrusa / embedded profile decide.
    if (sparseInfillSpeed > 0) {
        result["sparse_infill_speed"] = sparseInfillSpeed.toString()
    }

    // Support keys: when mode is USE_FILE and the file has its own config (Bambu 3MF),
    // omit these keys so the file's original enable_support / support_threshold_angle
    // survive through ProfileEmbedder's preserve path. Without this, cfg.supportEnabled
    // defaults to false and stomps the file's embedded support=true (B10 fix).
    // For STL/non-Bambu files (no source config), always emit — cfg.supportEnabled IS
    // the user's intent and there's no file value to preserve.
    if (ov.supports.mode != OverrideMode.USE_FILE || !hasSourceConfig) {
        result["enable_support"] = if (supportEnabled) "1" else "0"
        result["support_threshold_angle"] = supportAngle.toString()
        result["support_type"] = supportType
        result["support_on_build_plate_only"] = if (supportBuildPlateOnly) "1" else "0"
        result["support_base_pattern"] = supportPattern
        result["support_base_pattern_spacing"] = supportPatternSpacing.toString()
        result["support_interface_top_layers"] = supportInterfaceTopLayers.toString()
        result["support_interface_bottom_layers"] = supportInterfaceBottomLayers.toString()
        result["support_object_xy_distance"] = supportXyDistance.toString()
        result["support_interface_pattern"] = supportInterfacePattern
        result["support_interface_spacing"] = supportInterfaceSpacing.toString()
        // support_speed 0 = auto; only emit positive values
        if (supportSpeed > 0) {
            result["support_speed"] = supportSpeed.toString()
        }
        if (supportFilament > 0) {
            result["support_filament"] = supportFilament.toString()
        }
        if (supportInterfaceFilament > 0) {
            result["support_interface_filament"] = supportInterfaceFilament.toString()
        }
        // Tree support parameters — only relevant when support type is tree
        val isTree = supportType.startsWith("tree")
        if (isTree) {
            result["tree_support_branch_angle"] = treeSupportBranchAngle.toString()
            result["tree_support_branch_distance"] = treeSupportBranchDistance.toString()
            result["tree_support_branch_diameter"] = treeSupportBranchDiameter.toString()
        }
    }

    return result
}

internal fun buildCompactExtruderRemap(
    info: ThreeMfInfo,
    colorMapping: List<Int>?
): Map<Int, Int>? {
    if (colorMapping.isNullOrEmpty()) return null
    if (info.hasLayerToolChanges && !info.hasPaintData) return null
    // SEMM (paint-based) models: paint state filament indices are not affected by the
    // extruder attribute remap in model_settings.config.  GcodeToolRemapper handles the
    // physical slot assignment for these models, so suppress the XML extruder remap here.
    if (info.hasPaintData) return null

    val compactSlotOrder = colorMapping.distinct().sorted().take(4)
    if (compactSlotOrder.isEmpty()) return null

    val sortedSourceExtruders = info.usedExtruderIndices
        .filter { it > 0 }
        .sorted()
        .ifEmpty {
            (1..colorMapping.size).toList()
        }
    if (sortedSourceExtruders.isEmpty()) return null

    val remap = linkedMapOf<Int, Int>()
    sortedSourceExtruders.forEachIndexed { sourceIndex, sourceExtruder ->
        val assignedSlot = colorMapping.getOrNull(sourceIndex) ?: colorMapping.last()
        val compactIndex = compactSlotOrder.indexOf(assignedSlot)
        if (compactIndex >= 0) {
            remap[sourceExtruder] = compactIndex + 1
        }
    }

    return remap.takeIf { it.isNotEmpty() }
}
