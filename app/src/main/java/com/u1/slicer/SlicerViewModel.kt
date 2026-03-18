package com.u1.slicer

import android.app.Application
import android.content.Intent
import android.net.Uri
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
import com.u1.slicer.gcode.ParsedGcode

import com.u1.slicer.data.ModelInfo
import com.u1.slicer.data.OverrideMode
import com.u1.slicer.data.OverrideValue
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

class SlicerViewModel(application: Application) : AndroidViewModel(application) {

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
        data class Loading(val filename: String) : SlicerState()
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

    // Source file and info before embedding — kept so we can re-embed with extruder remap
    // when the user sets non-identity slot assignments after initial load.
    private var sourceModelFile: File? = null
    private var sourceModelInfo: ThreeMfInfo? = null
    // Original Bambu file's project_settings.config, parsed before process() strips it.
    // Used by embedProfile() so the file's own settings (enable_support, etc.) survive
    // through the sanitize→embed→extractPlate→restructure→re-embed pipeline.
    private var originalSourceConfig: Map<String, Any>? = null

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
        originalSourceConfig = originalSourceConfig
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
            _config.value = saved
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
            _state.value = SlicerState.Error("Unsupported URL: $url")
            return
        }
        // Set loading state immediately (before coroutine dispatch) so UI shows spinner
        _state.value = SlicerState.Loading("Downloading from MakerWorld…")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                // Clean intermediate files from previous model loads
                val cleared = UpgradeDetector.clearIntermediateCache(context.filesDir)
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
                val outputFile = File(context.filesDir, "makerworld_${designId}.3mf")
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
                    _state.value = SlicerState.Loading(fileName)

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
                originalSourceConfig = if (origInfo.isBambu) {
                    java.util.zip.ZipFile(outputFile).use { profileEmbedder.parseSourceConfig(it) }
                } else null

                val processed = BambuSanitizer.process(outputFile, context.filesDir, isBambu = origInfo.isBambu)
                val processedInfo = ThreeMfParser.parse(processed, skipPaintDetection = true)
                _threeMfInfo.value = mergeThreeMfInfo(processedInfo, origInfo)

                sourceModelFile = processed
                sourceModelInfo = processedInfo
                toolRemapSlots = null
                val mergedInfo = _threeMfInfo.value!!
                val sanitized = embedProfile(processed, mergedInfo, context)

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
                // Clean intermediate files from previous model loads to prevent stale
                // sanitized/embedded/plate files from accidentally being referenced.
                val cleared = UpgradeDetector.clearIntermediateCache(context.filesDir)
                if (cleared > 0) Log.i("SlicerVM", "Cleared $cleared intermediate cache files before new model load")
                clipperRetryAttempted = false  // Reset retry flag for new model
                val inputStream = context.contentResolver.openInputStream(uri) ?: run {
                    _state.value = SlicerState.Error("Could not open file")
                    return@launch
                }

                val filename = getDisplayName(context, uri) ?: "model.stl"
                currentModelName = filename
                _state.value = SlicerState.Loading(filename)
                val file = File(context.filesDir, filename)
                // Copy via temp file to avoid self-referential truncation
                // when the source URI points to our own FileProvider
                val tmpFile = File(context.cacheDir, "import_${System.currentTimeMillis()}")
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

                    // Parse original file first for multi-plate detection (process()
                    // strips plate_N.json so detection would fail on the processed file).
                    val origInfo = ThreeMfParser.parse(file)
                    recoveryOrigInfo = origInfo  // Saved for Clipper recovery pipeline

                    Log.i("SlicerVM", "3MF: bambu=${origInfo.isBambu}, multiPlate=${origInfo.isMultiPlate}, " +
                        "colors=${origInfo.detectedColors.size}, extruders=${origInfo.detectedExtruderCount}, " +
                        "paint=${origInfo.hasPaintData}, toolChanges=${origInfo.hasLayerToolChanges}")

                    // Parse original file's config BEFORE process() strips it.
                    // This preserves file-level settings (enable_support, etc.) through the pipeline.
                    originalSourceConfig = if (origInfo.isBambu) {
                        java.util.zip.ZipFile(file).use { profileEmbedder.parseSourceConfig(it) }
                    } else null

                    // Sanitize first (strip printable="0", restructure multi-color, clean XML),
                    // then embed Snapmaker profile.  Without process(), non-printable build
                    // items cause "Coordinate outside allowed range" Clipper errors.
                    val processed = BambuSanitizer.process(file, context.filesDir, isBambu = origInfo.isBambu)
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
                    val sanitized = embedProfile(processed, mergedInfo, context)

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
                    originalSourceConfig = null
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

    fun loadModelFromFile(file: File) {
        if (!NativeLibrary.isLoaded) {
            _state.value = SlicerState.Error("Native slicer library not available on this device (arm64 required)")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val cleared = UpgradeDetector.clearIntermediateCache(context.filesDir)
                if (cleared > 0) Log.i("SlicerVM", "Cleared $cleared intermediate cache files before direct model load")
                clipperRetryAttempted = false
                if (!file.exists() || !file.canRead()) {
                    _state.value = SlicerState.Error("Could not read file: ${file.absolutePath}")
                    return@launch
                }

                val filename = file.name
                currentModelName = filename
                _state.value = SlicerState.Loading(filename)

                rawInputFile = file
                recoveryPlateId = -1
                diagnostics.recordEvent(
                    "model_imported",
                    mapOf(
                        "filename" to filename,
                        "copiedTo" to file.absolutePath,
                        "sizeBytes" to file.length(),
                        "directFileLoad" to true
                    )
                )

                val fileToLoad = if (filename.endsWith(".3mf", ignoreCase = true)) {
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

                    val origInfo = ThreeMfParser.parse(file)
                    recoveryOrigInfo = origInfo

                    Log.i("SlicerVM", "3MF: bambu=${origInfo.isBambu}, multiPlate=${origInfo.isMultiPlate}, " +
                        "colors=${origInfo.detectedColors.size}, extruders=${origInfo.detectedExtruderCount}, " +
                        "paint=${origInfo.hasPaintData}, toolChanges=${origInfo.hasLayerToolChanges}")

                    originalSourceConfig = if (origInfo.isBambu) {
                        java.util.zip.ZipFile(file).use { profileEmbedder.parseSourceConfig(it) }
                    } else null

                    val processed = BambuSanitizer.process(file, context.filesDir, isBambu = origInfo.isBambu)
                    val processedInfo = ThreeMfParser.parse(processed, skipPaintDetection = true)
                    _threeMfInfo.value = mergeThreeMfInfo(processedInfo, origInfo)

                    sourceModelFile = processed
                    sourceModelInfo = processedInfo
                    toolRemapSlots = null
                    val mergedInfo = _threeMfInfo.value!!
                    val sanitized = embedProfile(processed, mergedInfo, context)

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
                    originalSourceConfig = null
                    file
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
        val file = currentModelFile ?: return
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
                val rawPlateFile = BambuSanitizer.extractPlate(file, plateId, context.filesDir,
                    hasPlateJsons = hasPlateJsons,
                    plateObjectIds = plateObjectIds,
                    objectExtruderMap = plateExtruderMap)
                // Restructure per-plate: inline component meshes so OrcaSlicer
                // can assign per-volume extruders (deferred from process()).
                val plateFile = BambuSanitizer.restructurePlateFile(rawPlateFile, context.filesDir)
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
                _threeMfInfo.value = if (preSelectInfo != null)
                    mergeThreeMfInfoForPlate(plateInfo, preSelectInfo)
                else
                    plateInfo
                toolRemapSlots = null
                currentModelFile = plateFile
                loadNativeModel(plateFile)
            } catch (e: Throwable) {
                native.clearModel() // Reset native state to prevent stale Clipper errors on retry
                _state.value = SlicerState.Error("Error extracting plate: ${e.message}")
            }
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
        val success = native.loadModel(file.absolutePath)
        diagnostics.recordEvent(
            "native_model_load",
            mapOf(
                "success" to success,
                "path" to file.absolutePath,
                "firstModelLoadThisLaunch" to diagnostics.markFirstModelLoad()
            )
        )
        if (success) {
            profileNeedsReEmbed = false  // Profile is current — just embedded
            val info = native.getModelInfo()
            if (info != null) {
                lastModelInfo = info
                _modelScale.value = ModelScale()  // reset to 1× on each new load
                _state.value = SlicerState.ModelLoaded(info)

                // Check for multi-color from 3MF parsing
                val mfInfo = _threeMfInfo.value
                if (mfInfo != null && mfInfo.detectedExtruderCount > 1) {
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
                    // Auto-apply closest-extruder mapping immediately — no dialog popup.
                    // The inline UI on the model page lets the user change assignments.
                    val presets = extruderPresets.value
                    val rawMapping = mfInfo.detectedColors.map { modelColor ->
                        com.u1.slicer.ui.findClosestExtruder(modelColor, presets)?.index ?: 0
                    }
                    // If all colours collapsed to one slot (e.g. no presets configured),
                    // distribute sequentially so the initial slice is always multi-extruder.
                    val initialMapping = com.u1.slicer.ui.ensureMultiSlotMapping(
                        rawMapping, mfInfo.detectedColors.size)
                    _colorMapping.value = initialMapping
                    applyMultiColorAssignments(initialMapping, presets, emptyList())
                    Log.i("SlicerVM", "Auto-applied color mapping: $extCount extruders, mapping=$initialMapping")
                } else {
                    _colorMapping.value = null
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
        // Store remap for any non-identity mapping — e.g. non-zero-based slots (E3+E4)
        // or when model colors map to non-contiguous slots.
        val compactSlots = usedSlots.take(extCount)
        val isIdentity = compactSlots == (0 until extCount).toList()
        toolRemapSlots = if (isIdentity) null else compactSlots
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
        // Use the configured slot color whenever present, including explicit white.
        // Falling back from "#FFFFFF" to the detected model color made it impossible
        // to preview a real white assignment on multi-color models.
        val detectedColors = _threeMfInfo.value?.detectedColors ?: emptyList()
        val fullColors = MutableList(4) { "" }  // 4 slots on Snapmaker U1
        usedSlots.forEachIndexed { compactIdx, slotIndex ->
            val presetColor = extruderPresets.firstOrNull { it.index == slotIndex }?.color
            val modelColor = detectedColors.getOrElse(compactIdx) { "" }
            fullColors[slotIndex] = when {
                // Respect the user's assigned slot color, including white.
                !presetColor.isNullOrBlank() -> presetColor
                // Fall back to detected model color (from 3MF metadata)
                modelColor.isNotBlank() -> modelColor
                // Last resort: leave blank to use GcodeRenderer defaults (orange, blue, green, pink)
                else -> ""
            }
        }
        _activeExtruderColors.value = fullColors
        Log.i("SlicerVM", "Applied color mapping: $extCount extruders used=${usedSlots}, remap=${toolRemapSlots}, temps=${temps.toList()}, colors=$fullColors")
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
    private fun embedProfile(file: java.io.File, info: ThreeMfInfo, context: android.app.Application): java.io.File {
        val cfg = _config.value
        val extCount = cfg.extruderCount.coerceAtLeast(1)
        val usedSlots = toolRemapSlots  // e.g. [2,3] for E3+E4; null = identity/single
        // Use compact extruder count (= number of unique used slots, up to 4).
        // When slots are non-contiguous (e.g. E2+E4), we slice as compact N-extruder
        // and post-process G-code to remap T-commands + SM indices to physical slots.
        val targetCount = if (usedSlots != null) usedSlots.size else extCount
        // No extruder remap in the 3MF — keep compact numbering (1,2,…).
        // G-code post-processing handles T0→T2, T1→T3, SM EXTRUDER/INDEX remapping.
        val extruderRemap: Map<Int, Int>? = null
        // Use the original file's config (parsed before process() strips it) when available.
        // Falls back to parsing from the current file for non-Bambu or when original is unavailable.
        val sourceConfig = originalSourceConfig ?: if (info.isBambu) {
            java.util.zip.ZipFile(file).use { profileEmbedder.parseSourceConfig(it) }
        } else null
        Log.d("SlicerVM", "embedProfile: info.isBambu=${info.isBambu}, info.detectedExtruders=${info.detectedExtruderCount}, " +
            "info.hasToolChanges=${info.hasLayerToolChanges}, info.hasPaint=${info.hasPaintData}, " +
            "info.isMultiPlate=${info.isMultiPlate}, sourceConfig=${sourceConfig != null}, targetCount=$targetCount")
        val embeddedConfig = profileEmbedder.buildConfig(
            info = info,
            sourceConfig = sourceConfig,
            overrides = buildProfileOverrides(cfg, extCount, usedSlots, hasSourceConfig = sourceConfig != null),
            targetExtruderCount = targetCount
        )
        return profileEmbedder.embed(file, embeddedConfig, context.filesDir, info, extruderRemap)
    }

    private fun buildProfileOverrides(cfg: SliceConfig, extCount: Int, usedSlots: List<Int>? = null, hasSourceConfig: Boolean = false): Map<String, Any> {
        return buildProfileOverridesImpl(cfg, slicingOverrides.value, extCount, usedSlots, hasSourceConfig)
    }

    private fun configureNativeDiagnosticsIfAvailable() {
        if (!NativeLibrary.isLoaded) return
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
        firstSliceThisLaunch: Boolean,
        firstSliceAfterUpgrade: Boolean
    ): Map<String, Any?> {
        val currentInfo = lastModelInfo
        return mapOf(
            "firstSliceThisLaunch" to firstSliceThisLaunch,
            "firstSliceAfterUpgrade" to firstSliceAfterUpgrade,
            "modelName" to currentModelName,
            "currentModelPath" to currentModelFile?.absolutePath,
            "sourceModelPath" to sourceModelFile?.absolutePath,
            "rawInputPath" to rawInputFile?.absolutePath,
            "selectedPlateId" to recoveryPlateId.takeIf { it >= 0 },
            "copyCount" to _copyCount.value,
            "hasCustomPlacement" to (customObjectPositions != null),
            "toolRemapSlots" to toolRemapSlots,
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

    fun startSlicing() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            try {
                SlicingService.start(context)
                var maxPct = 0
                native.progressListener = { pct, stage ->
                    if (pct > maxPct) maxPct = pct
                    _state.value = SlicerState.Slicing(maxPct, stage)
                    SlicingService.updateProgress(context, maxPct, stage)
                }

                _state.value = SlicerState.Slicing(0, "Preparing...")

                val (firstSliceThisLaunch, firstSliceAfterUpgrade) = diagnostics.markSliceStart()

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
                        val reembedded = embedProfile(src, srcInfo, context)
                        currentModelFile = reembedded
                        val reloadOk = native.loadModel(reembedded.absolutePath)
                        diagnostics.recordEvent(
                            "native_model_reload_before_slice",
                            mapOf(
                                "success" to reloadOk,
                                "path" to reembedded.absolutePath,
                                "firstSliceAfterUpgrade" to firstSliceAfterUpgrade
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
                            "positions" to custom.toList(),
                            "firstSliceAfterUpgrade" to firstSliceAfterUpgrade
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
                                "scaledModelSizeY" to (mi.sizeY * s.y),
                                "firstSliceAfterUpgrade" to firstSliceAfterUpgrade
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
                val sliceConfig = ov.resolveInto(_config.value)
                val profileOverrides = buildProfileOverrides(
                    sliceConfig,
                    sliceConfig.extruderCount,
                    toolRemapSlots,
                    hasSourceConfig = originalSourceConfig != null
                )
                diagnostics.recordEvent(
                    "slice_started",
                    sliceDiagnosticsMap(
                        sliceConfig = sliceConfig,
                        profileOverrides = profileOverrides,
                        firstSliceThisLaunch = firstSliceThisLaunch,
                        firstSliceAfterUpgrade = firstSliceAfterUpgrade
                    )
                )
                diagnostics.recordEvent(
                    "pre_slice_native_state",
                    mapOf(
                        "firstSliceThisLaunch" to firstSliceThisLaunch,
                        "firstSliceAfterUpgrade" to firstSliceAfterUpgrade,
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

                val result = native.slice(sliceConfig)

                if (result != null && result.success) {
                    diagnostics.markSliceSucceeded()
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
                    // Inject preview thumbnails into G-code for Klipper/Moonraker
                    val sourcePath = rawInputFile?.absolutePath ?: sourceModelFile?.absolutePath ?: currentModelFile?.absolutePath
                    if (sourcePath != null) {
                        try {
                            val injected = GcodeThumbnailInjector.inject(result.gcodePath, sourcePath)
                            if (injected) Log.i("SlicerVM", "Thumbnails injected into G-code")
                        } catch (e: Throwable) {
                            Log.w("SlicerVM", "Thumbnail injection failed (non-fatal): ${e.message}")
                        }
                    }

                    _state.value = SlicerState.SliceComplete(result)
                    _gcodePreview.value = native.getGcodePreview(50)
                    // Parse G-code for layer viewer
                    try {
                        _parsedGcode.value = GcodeParser.parse(File(result.gcodePath))
                    } catch (e: Throwable) {
                        Log.w("SlicerVM", "G-code parse failed: ${e.message}")
                    }
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
     * reset native state, re-run the full pipeline from rawInputFile, then re-slice.
     *
     * Uses rawInputFile (the pre-sanitize raw copy, e.g. "Button-for-S-trousers.3mf") rather
     * than sourceModelFile / plateFile because those are intermediate files (sanitized_* /
     * plate*.3mf) and are deleted by clearIntermediateCache().  rawInputFile has no prefix
     * and survives the cache clear.
     */
    private fun attemptClipperRecovery() {
        // Clipper coordinate overflow is caused by stale native static state that
        // accumulates across model loads within the same process. In-process recovery
        // (clearModel + re-pipeline + re-slice) doesn't work — same native statics
        // produce identical failures. A process restart (SIGKILL + AlarmManager relaunch)
        // gives fresh JNI_OnLoad with clean state, which fixes the issue.
        Log.w("SlicerVM", "Clipper error: restarting app for fresh native state")
        diagnostics.recordEvent(
            "clipper_recovery_restart",
            mapOf(
                "rawFile" to rawInputFile?.absolutePath,
                "plateId" to recoveryPlateId,
                "currentModelPath" to currentModelFile?.absolutePath
            )
        )
        _state.value = SlicerState.Slicing(0, "Restarting for clean state…")
        restartApp()
    }

    /**
     * Nuclear option: clear all cache, kill process, restart via AlarmManager.
     * Guarantees fresh native state (clean JNI_OnLoad). Used as last resort for Clipper errors.
     */
    fun restartApp() {
        val app = getApplication<Application>()
        diagnostics.markUpgradeRestartRequested("manual_restart", safeNativeDiagnosticsState())
        UpgradeDetector.clearIntermediateCache(app.filesDir)
        native.clearModel()
        val intent = app.packageManager.getLaunchIntentForPackage(app.packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pi = android.app.PendingIntent.getActivity(
            app, 0, intent,
            android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val am = app.getSystemService(android.app.AlarmManager::class.java)
        am.set(android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 500, pi)
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
                    _config.value = it
                    settingsRepo.saveSliceConfig(it)
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

    fun clearCacheFiles(): Int {
        val ctx = getApplication<Application>()
        var count = 0
        ctx.filesDir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".3mf") || f.name.endsWith(".gcode")) {
                f.delete()
                count++
            }
        }
        if (count > 0) {
            clearModel()
            Log.i("SlicerVM", "Manually cleared $count cached files")
        }
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
            hasPaintData = origInfo.hasPaintData,
            hasLayerToolChanges = origInfo.hasLayerToolChanges,
            hasMultiExtruderAssignments = origInfo.hasMultiExtruderAssignments,
            // Prefer the processed file's map when available: BambuSanitizer can inline
            // compound-object parts into concrete mesh object IDs, which is exactly what
            // the preview parser needs for per-part coloring (for example calicube).
            objectExtruderMap = processedInfo.objectExtruderMap.ifEmpty { origInfo.objectExtruderMap }
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
            sourceInfo: com.u1.slicer.bambu.ThreeMfInfo
        ): com.u1.slicer.bambu.ThreeMfInfo {
            // Filter colors to only those extruder indices used on this plate.
            // usedExtruderIndices are 1-based; detectedColors is 0-indexed.
            // Filter whenever usedExtruderIndices is populated (size >= 1) AND
            // there are multiple source colors to filter from. This correctly
            // handles single-extruder plates (showing only 1 color instead of all).
            val usedIndices = plateInfo.usedExtruderIndices
            val filteredColors = if (usedIndices.isNotEmpty() && sourceInfo.detectedColors.size > 1) {
                usedIndices.sorted().mapNotNull { idx ->
                    sourceInfo.detectedColors.getOrNull(idx - 1) // 1-based → 0-indexed
                }
            } else {
                sourceInfo.detectedColors
            }
            return plateInfo.copy(
                isBambu = sourceInfo.isBambu,
                detectedColors = filteredColors,
                detectedExtruderCount = if (filteredColors.isNotEmpty()) filteredColors.size else sourceInfo.detectedExtruderCount,
                hasPaintData = sourceInfo.hasPaintData,
                hasLayerToolChanges = sourceInfo.hasLayerToolChanges,
                hasMultiExtruderAssignments = sourceInfo.hasMultiExtruderAssignments,
                objectExtruderMap = plateInfo.objectExtruderMap.ifEmpty { sourceInfo.objectExtruderMap }
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
        "top_shell_layers" to cfg.topSolidLayers.toString(),
        "bottom_shell_layers" to cfg.bottomSolidLayers.toString(),
        "sparse_infill_density" to "${(infillDensity * 100).toInt()}%",
        "sparse_infill_pattern" to infillPattern,
        "travel_speed" to cfg.travelSpeed.toString(),
        "nozzle_temperature" to temps,
        "nozzle_temperature_initial_layer" to temps.toMutableList(),
        "bed_temperature" to mutableListOf(bedTemp.toString()),
        "bed_temperature_initial_layer" to mutableListOf(bedTemp.toString()),
        "brim_width" to brimWidth.toString(),
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
        if (supportFilament > 0) {
            result["support_filament"] = supportFilament.toString()
        }
        if (supportInterfaceFilament > 0) {
            result["support_interface_filament"] = supportInterfaceFilament.toString()
        }
    }

    return result
}
