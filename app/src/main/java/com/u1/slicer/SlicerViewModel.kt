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
import com.u1.slicer.bambu.ThreeMfPlate
import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.FilamentProfile
import com.u1.slicer.data.ModelInfo
import com.u1.slicer.data.OverrideMode
import com.u1.slicer.data.OverrideValue
import com.u1.slicer.data.PlateType
import com.u1.slicer.data.SettingsBackup
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.data.SliceJob
import com.u1.slicer.data.SliceResult
import com.u1.slicer.data.SlicingOverrides
import com.u1.slicer.data.WipeTowerDepthEstimator
import com.u1.slicer.gcode.GcodeParser
import com.u1.slicer.gcode.GcodeThumbnailInjector
import com.u1.slicer.gcode.GcodeToolRemapper
import com.u1.slicer.gcode.GcodeValidator
import com.u1.slicer.gcode.LayerToolPauseInjector
import com.u1.slicer.gcode.ParsedGcode
import com.u1.slicer.gcode.buildSuspiciousModelLineContexts
import com.u1.slicer.model.CopyArrangeCalculator
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

internal fun loadingMessageFor(filename: String, fileSizeBytes: Long): String =
    if (fileSizeBytes > 50 * 1024 * 1024L) "Large model — this may take a moment…"
    else "Loading $filename…"

internal fun isLargeTriangleCount(triangleCount: Int): Boolean =
    triangleCount > com.u1.slicer.viewer.NativePreviewMesh.MAX_KOTLIN_PREVIEW_TRIANGLES

/**
 * Returns the effective wipe tower depth for the Prepare preview:
 * uses the active primeVolume override (in mm) if set, otherwise falls back to height-based estimate.
 */
internal fun resolveWipeTowerDepth(modelHeightMm: Float, overrides: com.u1.slicer.data.SlicingOverrides): Float {
    val ov = overrides.primeVolume
    val primeVolumeMm = if (ov.mode == com.u1.slicer.data.OverrideMode.OVERRIDE && ov.value != null) ov.value.toFloat() else 0f
    return com.u1.slicer.data.WipeTowerDepthEstimator.estimateDepth(modelHeightMm, primeVolumeMm)
}

/**
 * Returns the effective wipe tower width for the Prepare preview:
 * uses the active primeTowerWidth override if set, otherwise falls back to config default.
 */
internal fun resolveWipeTowerWidth(config: com.u1.slicer.data.SliceConfig, overrides: com.u1.slicer.data.SlicingOverrides): Float {
    val ov = overrides.primeTowerWidth
    return if (ov.mode == com.u1.slicer.data.OverrideMode.OVERRIDE && ov.value != null) ov.value else config.wipeTowerWidth
}

/**
 * Returns (maxX, maxY) for wipe tower position clamping.
 * X uses towerWidth (the tower is wider than it is deep).
 * Y uses towerDepth (estimated from model height via WipeTowerDepthEstimator).
 */
internal fun wipeTowerClampBounds(
    bedSizeX: Float, bedSizeY: Float,
    towerWidth: Float, towerDepth: Float
): Pair<Float, Float> {
    val maxX = (bedSizeX - towerWidth).coerceAtLeast(0f)
    val maxY = (bedSizeY - towerDepth).coerceAtLeast(0f)
    return maxX to maxY
}

/**
 * Synthesize a [SliceResult] from a [SliceJob] DB row so the Preview tab can enter
 * [SlicerViewModel.SlicerState.SliceComplete] when a saved G-code is loaded for viewing
 * (B40 — kill+reopen from Jobs tab used to leave state as Idle).
 */
internal fun sliceResultFromJob(job: SliceJob) = SliceResult(
    success = true,
    cancelled = false,
    errorMessage = "",
    gcodePath = job.gcodePath,
    totalLayers = job.totalLayers,
    estimatedTimeSeconds = job.estimatedTimeSeconds,
    estimatedFilamentMm = job.estimatedFilamentMm,
    estimatedFilamentGrams = job.estimatedFilamentGrams
)

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

    /**
     * Shared 3MF import pipeline contract for loadModel(uri), loadModelFromFile(file),
     * and MakerWorld imports.
     *
     * rawFile is the durable pre-sanitize copy retained for recovery. processedFile is the
     * sanitize/deferred-restructure artifact kept for later plate extraction and re-embed.
     * embeddedFile is the current profile-embedded artifact loaded into native code.
     */
    private data class PreparedModelArtifacts(
        val rawFile: File,
        val origInfo: ThreeMfInfo,
        val sourceConfig: Map<String, Any>?,
        val processedFile: File,
        val processedInfo: ThreeMfInfo,
        val mergedInfo: ThreeMfInfo,
        val embeddedFile: File
    ) {
        val requiresPlateSelection: Boolean
            get() = origInfo.isMultiPlate && origInfo.plates.size > 1
    }

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
        object Cancelling : SlicerState()
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

    private val _sliceStale = MutableStateFlow(false)
    val sliceStale: StateFlow<Boolean> = _sliceStale.asStateFlow()

    private val _config = MutableStateFlow(SliceConfig())
    val config: StateFlow<SliceConfig> = _config.asStateFlow()

    private val _gcodePreview = MutableStateFlow("")
    val gcodePreview: StateFlow<String> = _gcodePreview.asStateFlow()

    private val _parsedGcode = MutableStateFlow<ParsedGcode?>(null)
    val parsedGcode: StateFlow<ParsedGcode?> = _parsedGcode.asStateFlow()

    // Bambu / multi-plate state
    private val _threeMfInfo = MutableStateFlow<ThreeMfInfo?>(null)
    val threeMfInfo: StateFlow<ThreeMfInfo?> = _threeMfInfo.asStateFlow()
    // Original file-level ThreeMfInfo set on load, never overwritten by plate selections (B81).
    // Used as the stable sourceInfo for mergeThreeMfInfoForPlate so that cross-plate selections
    // always see the correct file-level metadata (e.g. hasPaintData=true for files where only
    // some plates have paint data).
    private var _fileThreeMfInfo: ThreeMfInfo? = null

    private val _multiPlatePlates = MutableStateFlow<List<ThreeMfPlate>>(emptyList())
    val multiPlatePlates: StateFlow<List<ThreeMfPlate>> = _multiPlatePlates.asStateFlow()

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
    private val _copyBedWarning = MutableStateFlow<String?>(null)
    val copyBedWarning: StateFlow<String?> = _copyBedWarning.asStateFlow()

    // Model scale: uniform or per-axis. Applied before slicing.
    data class ModelScale(val x: Float = 1f, val y: Float = 1f, val z: Float = 1f) {
        val isUniform get() = x == y && y == z
        val uniform get() = x
    }
    private val _modelScale = MutableStateFlow(ModelScale())
    val modelScale: StateFlow<ModelScale> = _modelScale.asStateFlow()

    data class ModelRotation(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f)
    private val _modelRotation = MutableStateFlow(ModelRotation())
    val modelRotation: StateFlow<ModelRotation> = _modelRotation.asStateFlow()

    // B78: snapshot of the native instance offsets captured right after loadModel.
    // InlineModelPreview restores these before getPreparePreviewMesh so the file's
    // original plate position is preserved (e.g. Shashibo plate 5's H2D transform).
    // Falls back to (135, 135) when the native call returns no offsets.
    private val _loadTimeInstanceOffsets = MutableStateFlow(floatArrayOf(135f, 135f))
    val loadTimeInstanceOffsets: StateFlow<FloatArray> = _loadTimeInstanceOffsets.asStateFlow()

    // B78: true once prepareSlicer() has mutated native scale or instance state.
    // InlineModelPreview only performs the B72/B73 reset when this is set; on a fresh
    // load we must not call setModelScale(1,1,1) because that would wipe the file's
    // baked build-transform scale (e.g. the 0.6 scale on Shashibo plate 5's H2D object).
    private val _nativeSliceStateDirty = MutableStateFlow(false)
    val nativeSliceStateDirty: StateFlow<Boolean> = _nativeSliceStateDirty.asStateFlow()

    // Custom object positions set from PlacementViewer (null = use auto grid)
    // Flat array [x0,y0,x1,y1,...] in mm
    private var customObjectPositions: FloatArray? = null
    // Custom wipe tower position (null = use config defaults)
    private var customWipeTowerPos: Pair<Float, Float>? = null

    // Tool remap: maps compact T-index (0,1,…) → actual printer slot index (e.g. 2,3 for E3+E4).
    // Null / identity mapping → no post-processing needed.
    private var toolRemapSlots: List<Int>? = null

    // B64: SEMM colour permutation — maps compact T-index → physical extruder slot
    // when the user's colour assignment is a non-identity permutation.
    // Null when no permutation needed (identity, H2C, non-SEMM).
    // Applied post-slice via GcodeToolRemapper, independently of toolRemapSlots.
    private var semmColorPermutation: List<Int>? = null

    // B92: for each slicer compact tool index k (0..N-1), which index into
    // detectedColors does it represent. Required when OrcaSlicer's print-order
    // differs from detectedColors order (e.g. Buzz plate 8: object default is
    // filament 10 but the painted state is filament 3, so slicer T0=detectedColors[1]
    // and slicer T1=detectedColors[0]). Null when identity (simple SEMM cases).
    // Used by the G-code viewer to align Preview palette with Prepare palette.
    private val _slicerColorOrder = MutableStateFlow<List<Int>?>(null)
    val slicerColorOrder: StateFlow<List<Int>?> = _slicerColorOrder.asStateFlow()

    // B92: expose semmColorPermutation to Compose so Preview can reindex correctly.
    private val _semmColorPermutationFlow = MutableStateFlow<List<Int>?>(null)
    val semmColorPermutationFlow: StateFlow<List<Int>?> = _semmColorPermutationFlow.asStateFlow()

    // B95: true when the post-slice GcodeToolRemapper applied an expanded
    // filament-index → physical-slot remap (because the embedded filament_colour
    // was bumped to fit a high-index source filament). When true, the parsedGcode
    // exposed via [parsedGcode] already carries physical-slot indices, so the
    // Preview's [normalizeGcodePreviewColors] should bypass the slicerColorOrder
    // / semmColorPermutation swap branches and use [activeExtruderColors] directly.
    private val _gcodeUsesPhysicalSlots = MutableStateFlow(false)
    val gcodeUsesPhysicalSlots: StateFlow<Boolean> = _gcodeUsesPhysicalSlots.asStateFlow()

    // B49: cache the Prepare preview MeshData so returning from G-code view is instant.
    // The native side caches the raw mesh, but toMeshData() (normal computation + FloatBuffer
    // allocation) is expensive for large SEMM models (2M tris).  This cache avoids re-conversion.
    // Invalidated on model load, rotation change, or arrangement change.
    // cachedPrepareMeshPath ties the cache to a specific model file so that a plate switch
    // (which changes previewModelPath before invalidation runs) doesn't serve a stale mesh.
    @Volatile var cachedPrepareMesh: com.u1.slicer.viewer.MeshData? = null
    @Volatile var cachedPrepareMeshPath: String? = null

    fun invalidatePrepareMeshCache() {
        cachedPrepareMesh = null
        cachedPrepareMeshPath = null
    }

    /**
     * B92: reset both the post-slice tool-remap state and the Preview palette-alignment
     * state so a fresh applyMultiColorAssignments / plate-switch / single-colour path
     * doesn't carry stale permutation data forward.
     */
    private fun resetToolRemapState() {
        toolRemapSlots = null
        semmColorPermutation = null
        _semmColorPermutationFlow.value = null
        _slicerColorOrder.value = null
        _gcodeUsesPhysicalSlots.value = false
    }

    // Filament library — StateFlow so .value is accessible synchronously (e.g. for nozzle temp lookup at slice time)
    val filaments = filamentDao.getAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
    private val _modelFileName = MutableStateFlow("")
    val modelFileName: StateFlow<String> = _modelFileName.asStateFlow()
    private var currentModelName: String = ""
        set(value) { field = value; _modelFileName.value = value }
    private var lastModelInfo: ModelInfo? = null
    private val _modelInfo = MutableStateFlow<ModelInfo?>(null)
    val modelInfo: StateFlow<ModelInfo?> = _modelInfo.asStateFlow()

    // Source file and info before embedding — kept so we can re-embed with extruder remap
    // when the user sets non-identity slot assignments after initial load.
    private var sourceModelFile: File? = null
    private var sourceModelInfo: ThreeMfInfo? = null
    // Full processed multi-plate file — set once on load, never replaced by per-plate
    // extractions so selectPlate() always calls extractPlate() on the right source.
    private var _multiPlateSourceFile: File? = null
    // Tracks the in-flight selectPlate coroutine so rapid plate changes cancel the prior one.
    private var selectPlateJob: Job? = null
    // Tracks the in-flight slice coroutine so a plate switch can cancel it before it writes
    // SliceComplete/Error and overwrites the Loading state set by selectPlate().
    private var slicingJob: Job? = null
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
    private val _currentPlateId = MutableStateFlow(-1)
    val currentPlateId: StateFlow<Int> = _currentPlateId.asStateFlow()
    private var recoveryPlateId: Int = -1
        set(value) { field = value; _currentPlateId.value = value }

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

        // Mark slice stale when extruder presets change from the Printer tab
        // (those writes go through settingsRepo directly, bypassing updateConfig()).
        viewModelScope.launch {
            extruderPresets.drop(1).collect {
                _sliceStale.value = true
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

                val prepared = prepareImportedModelArtifacts(outputFile, workspaceDir)

                currentModelFile = prepared.embeddedFile
                if (prepared.requiresPlateSelection) {
                    Log.i(
                        "SlicerVM",
                        "MakerWorld file is multi-plate (${prepared.origInfo.plates.size} plates), showing selector"
                    )
                    _showPlateSelector.value = true
                    return@launch
                }
                Log.i(
                    "SlicerVM",
                    "Loading MakerWorld model natively: ${prepared.embeddedFile.name} (${prepared.embeddedFile.length()} bytes)"
                )
                loadNativeModel(prepared.embeddedFile)
            } catch (e: Throwable) {
                NativeLibrary.previewMutex.withLock { native.clearModel() }
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
        invalidatePrepareMeshCache()
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
                val file = File(workspaceDir, filename)
                val uriSizeBytes = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
                _state.value = SlicerState.Loading(loadingMessageFor(filename, uriSizeBytes))
                // Copy via temp file to avoid self-referential truncation
                // when the source URI points to our own FileProvider
                val tmpFile = File(transientCacheDir(), "import_${System.currentTimeMillis()}")
                try {
                    tmpFile.outputStream().use { inputStream.copyTo(it) }
                    tmpFile.copyTo(file, overwrite = true)
                } finally {
                    tmpFile.delete()
                }
                // B67: Log copy size to detect truncation from content providers (e.g. Google Drive)
                val copiedBytes = file.length()
                Log.i("SlicerVM", "File copy: uriSize=$uriSizeBytes, copiedSize=$copiedBytes, match=${uriSizeBytes == copiedBytes || uriSizeBytes == 0L}, filename=$filename")
                if (uriSizeBytes > 0L && copiedBytes != uriSizeBytes) {
                    Log.w("SlicerVM", "B67: File copy size mismatch! Expected $uriSizeBytes but got $copiedBytes bytes — content provider may have truncated the stream")
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
                        "sizeBytes" to copiedBytes,
                        "uriSizeBytes" to uriSizeBytes,
                        "sizeMismatch" to (uriSizeBytes > 0L && copiedBytes != uriSizeBytes)
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

                    val prepared = prepareImportedModelArtifacts(file, workspaceDir)
                    val origInfo = prepared.origInfo

                    Log.i("SlicerVM", "3MF: bambu=${origInfo.isBambu}, multiPlate=${origInfo.isMultiPlate}, " +
                        "colors=${origInfo.detectedColors.size}, extruders=${origInfo.detectedExtruderCount}, " +
                        "paint=${origInfo.hasPaintData}, toolChanges=${origInfo.hasLayerToolChanges}")
                    // B67: persist parse results so we can diagnose paint detection failures
                    // on devices without adb access
                    diagnostics.recordEvent(
                        "threemf_parsed",
                        mapOf(
                            "filename" to filename,
                            "sizeBytes" to file.length(),
                            "isBambu" to origInfo.isBambu,
                            "hasPaintData" to origInfo.hasPaintData,
                            "hasPaintSupports" to origInfo.hasPaintSupports,
                            "hasLayerToolChanges" to origInfo.hasLayerToolChanges,
                            "hasMultiExtruderAssignments" to origInfo.hasMultiExtruderAssignments,
                            "detectedColors" to origInfo.detectedColors,
                            "detectedExtruderCount" to origInfo.detectedExtruderCount,
                            "usedExtruderIndices" to origInfo.usedExtruderIndices,
                            "objectExtruderMap" to origInfo.objectExtruderMap
                        )
                    )

                    // Show plate selector for multi-plate files (use origInfo since
                    // process() strips plate_N.json files that isMultiPlate relies on).
                    if (prepared.requiresPlateSelection) {
                        Log.i("SlicerVM", "Multi-plate: ${origInfo.plates.size} plates, showing selector")
                        currentModelFile = prepared.embeddedFile
                        _showPlateSelector.value = true
                        // Don't load yet — wait for plate selection
                        return@launch
                    }
                    Log.i("SlicerVM", "Single-plate, loading directly")

                    prepared.embeddedFile
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
                NativeLibrary.previewMutex.withLock { native.clearModel() }
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
        invalidatePrepareMeshCache()
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
                _state.value = SlicerState.Loading(loadingMessageFor(filename, file.length()))

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

                    val prepared = prepareImportedModelArtifacts(sourceFile, workspaceDir)
                    val origInfo = prepared.origInfo

                    Log.i("SlicerVM", "3MF: bambu=${origInfo.isBambu}, multiPlate=${origInfo.isMultiPlate}, " +
                        "colors=${origInfo.detectedColors.size}, extruders=${origInfo.detectedExtruderCount}, " +
                        "paint=${origInfo.hasPaintData}, toolChanges=${origInfo.hasLayerToolChanges}")
                    diagnostics.recordEvent(
                        "threemf_parsed",
                        mapOf(
                            "filename" to filename,
                            "sizeBytes" to sourceFile.length(),
                            "isBambu" to origInfo.isBambu,
                            "hasPaintData" to origInfo.hasPaintData,
                            "hasPaintSupports" to origInfo.hasPaintSupports,
                            "hasLayerToolChanges" to origInfo.hasLayerToolChanges,
                            "hasMultiExtruderAssignments" to origInfo.hasMultiExtruderAssignments,
                            "detectedColors" to origInfo.detectedColors,
                            "detectedExtruderCount" to origInfo.detectedExtruderCount,
                            "usedExtruderIndices" to origInfo.usedExtruderIndices,
                            "objectExtruderMap" to origInfo.objectExtruderMap
                        )
                    )

                    if (prepared.requiresPlateSelection) {
                        Log.i("SlicerVM", "Multi-plate: ${origInfo.plates.size} plates, showing selector")
                        currentModelFile = prepared.embeddedFile
                        _showPlateSelector.value = true
                        return@launch
                    }
                    Log.i("SlicerVM", "Single-plate, loading directly")

                    prepared.embeddedFile
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
                NativeLibrary.previewMutex.withLock { native.clearModel() }
                _state.value = SlicerState.Error("Error: ${e.message}")
            }
        }
    }

    /**
     * Called when user selects a plate from the multi-plate dialog.
     */
    fun selectPlate(plateId: Int) {
        selectPlateJob?.cancel()
        slicingJob?.cancel()
        _showPlateSelector.value = false
        // Always extract from the full processed multi-plate file so that switching plates
        // (e.g. plate 4 → plate 5) uses the correct source regardless of prior selections.
        // _multiPlateSourceFile is set once on load and never overwritten (B83 fix).
        val file = _multiPlateSourceFile
            ?: resolvePlateSelectionSourceFile(sourceModelFile, currentModelFile)
            ?: return
        recoveryPlateId = plateId          // Track for Clipper recovery
        clipperRetryAttempted = false      // New plate = fresh retry allowance
        // Transition to Loading immediately so InlineModelPreview unmounts.
        // Without this, the rotation LaunchedEffect fires between _threeMfInfo update
        // and loadNativeModel completing, hitting the native's stale plate-N cache and
        // delivering the wrong mesh.  Unmounting ensures the fresh effect fires only
        // after the correct plate is loaded in native.
        _state.value = SlicerState.Loading("Loading plate $plateId…")
        diagnostics.recordEvent(
            "plate_selected",
            mapOf(
                "plateId" to plateId,
                "currentModelPath" to file.absolutePath
            )
        )

        selectPlateJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val workspaceDir = transientWorkspaceDir()
                // Use the stable file-level info (set once on load, never mutated by plate
                // selections) so that switching plates doesn't lose the original plates list.
                // _threeMfInfo.value is overwritten to a per-plate merged result after each
                // selectPlate(), so it may no longer have the correct objectIds for other plates.
                val fileInfo = _fileThreeMfInfo ?: _threeMfInfo.value
                val hasPlateJsons = fileInfo?.hasPlateJsons
                val plateObjectIds = fileInfo?.plates
                    ?.find { it.plateId == plateId }?.objectIds?.toSet()
                val plateExtruderMap = fileInfo?.objectExtruderMap
                    ?.filterKeys { key -> plateObjectIds?.contains(key) == true }
                val rawPlateFile = BambuSanitizer.extractPlate(file, plateId, workspaceDir,
                    hasPlateJsons = hasPlateJsons,
                    plateObjectIds = plateObjectIds,
                    objectExtruderMap = plateExtruderMap)
                ensureActive()
                // Restructure per-plate: inline component meshes so OrcaSlicer
                // can assign per-volume extruders (deferred from process()).
                val plateFile = BambuSanitizer.restructurePlateFile(rawPlateFile, workspaceDir)
                ensureActive()
                // Lightweight parse: only reads model_settings.config (~1KB) for extruder
                // indices, skips the 15MB+ main model XML entirely (~2s saved).
                val plateInfo = ThreeMfParser.parseForPlateSelection(plateFile)
                sourceModelFile = plateFile
                sourceModelInfo = plateInfo
                // Merge plate structural info with the file-level info so that
                // color/extruder metadata from the original file is preserved.
                // plateInfo has 0 detected colors because extractPlate() works on the
                // processed file which has had filament_sequence.json stripped by process().
                // Always use _fileThreeMfInfo (set once on load, never mutated by plate
                // selections) so that cross-plate selections don't lose file-level state
                // like hasPaintData=true from other plates (B81).
                val preSelectInfo = _fileThreeMfInfo ?: _threeMfInfo.value
                val mergedPlateInfo = if (preSelectInfo != null)
                    mergeThreeMfInfoForPlate(plateInfo, preSelectInfo, plateId)
                else
                    plateInfo
                _threeMfInfo.value = mergedPlateInfo
                resetToolRemapState()
                // Re-embed the selected plate so slice-time config preserves the
                // original file's layer-change settings (SEMM/pause G-code), not just
                // the preview metadata merged above.
                val embeddedPlateFile = embedProfile(plateFile, mergedPlateInfo, workspaceDir)
                ensureActive()
                currentModelFile = embeddedPlateFile
                loadNativeModel(embeddedPlateFile)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                NativeLibrary.previewMutex.withLock { native.clearModel() }
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
        selectPlateJob?.cancel()
        _showPlateSelector.value = false
        // Cancel the load — multi-plate files need a plate selection to work correctly.
        // Loading the full file causes off-bed coordinates and Clipper errors (B12).
        _state.value = SlicerState.Idle
        currentModelFile = null
        sourceModelFile = null
        sourceModelInfo = null
        _multiPlateSourceFile = null
        _threeMfInfo.value = null
        _fileThreeMfInfo = null
        _multiPlatePlates.value = emptyList()
    }

    fun reopenPlateSelector() {
        if (_multiPlatePlates.value.isNotEmpty()) _showPlateSelector.value = true
    }

    private suspend fun loadNativeModel(file: File) {
        val firstModelLoadThisLaunch = diagnostics.markFirstModelLoad()
        // Stale cached mesh from a previous model/plate load would cause InlineModelPreview's
        // LaunchedEffect(modelRotation, modelFilePath) to hit the B49 early-return guard and
        // skip getPreparePreviewMesh() for the new model, leaving the spinner indefinitely.
        invalidatePrepareMeshCache()
        // Acquire previewMutex before touching native model — prevents SIGSEGV when
        // getPreparePreviewMesh (on the preview coroutine) is iterating model volumes
        // while we clear+reload here.  Large model QEM decimation can hold the lock for
        // 30+ seconds; without this, loading a new model while QEM is running crashes.
        val success = NativeLibrary.previewMutex.withLock {
            native.loadModel(file.absolutePath)
        }
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
            // B78: snapshot the file's natural instance offsets and clear the dirty flag.
            // Natural load stores the build-transform's tx/ty in inst->get_offset(); we
            // restore these before getPreparePreviewMesh so the plate's original XY position
            // survives the B72/B73 reset.
            val naturalOffsets = runCatching { native.getInstanceOffsets() }.getOrNull()
            if (naturalOffsets != null && naturalOffsets.isNotEmpty()) {
                _loadTimeInstanceOffsets.value = naturalOffsets.copyOf()
                Log.i("SlicerVM", "B78: snapshotted load-time instance offsets: ${naturalOffsets.toList()}")
            } else {
                _loadTimeInstanceOffsets.value = floatArrayOf(135f, 135f)
            }
            _nativeSliceStateDirty.value = false
            val info = native.getModelInfo()
            if (info != null) {
                lastModelInfo = info
                _modelInfo.value = info
                _modelScale.value = ModelScale()  // reset to 1× on each new load
                _modelRotation.value = ModelRotation()
                if (isLargeTriangleCount(info.triangleCount)) {
                    _state.value = SlicerState.Loading("Large model — preview may take a moment…")
                    kotlinx.coroutines.delay(0)
                }

                // B47: set colorMapping and all multi-color config BEFORE emitting ModelLoaded
                // so the UI sees a consistent snapshot — no race where state=ModelLoaded but
                // colorMapping=null causes InlineModelPreview to recolor with a single-slot palette.
                // Check for multi-color from 3MF parsing
                val mfInfo = _threeMfInfo.value
                if (mfInfo != null && mfInfo.detectedExtruderCount > 1) {
                    val layerToolOnly = mfInfo.hasLayerToolChanges &&
                        !mfInfo.hasPaintData &&
                        !mfInfo.hasMultiExtruderAssignments

                    // Auto-apply closest-extruder mapping immediately — no dialog popup.
                    // The inline UI on the model page lets the user change assignments.
                    // Use settingsRepo.extruderPresets.first() instead of extruderPresets.value to
                    // guarantee we read the actual stored presets. extruderPresets.value may still
                    // be defaultExtruderPresets() if DataStore hasn't emitted yet, causing
                    // findClosestExtruder to build a wrong colorMapping. (B86)
                    val presets = settingsRepo.extruderPresets.first()
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
                        resetToolRemapState()
                        customWipeTowerPos = null
                        _config.value = _config.value.copy(
                            extruderCount = 1,
                            wipeTowerEnabled = false,
                            filamentType = resolveFilamentTypeLabelFromMapping(initialMapping, presets)
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
                        val estimatedTowerDepth = WipeTowerDepthEstimator.estimateDepth(info.sizeZ)
                        val towerPos = CopyArrangeCalculator.computeWipeTowerPosition(
                            positions, info.sizeX, info.sizeY,
                            towerWidth = _config.value.wipeTowerWidth,
                            towerDepth = estimatedTowerDepth
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
                        applyMultiColorAssignments(initialMapping, presets, filaments.value)
                        Log.i("SlicerVM", "Auto-applied color mapping: $extCount extruders, mapping=$initialMapping")
                    }
                } else {
                    _colorMapping.value = null
                    _layerToolOnly.value = false
                    _selectedExtruder.value = 0
                    // Reset multi-extruder state: single-color model uses 1 extruder.
                    // Without this, stale extruderCount from a previous multi-color model
                    // forces the prime tower on and produces 2-extruder G-code (B24 fix).
                    resetToolRemapState()
                    customWipeTowerPos = null
                    val presets = extruderPresets.value
                    _config.value = _config.value.copy(
                        extruderCount = 1,
                        wipeTowerEnabled = false,
                        filamentType = resolveFilamentTypeForSingleColorLoad(presets),
                        extruderTemps = intArrayOf(computeSingleColorTemp(0))
                    )
                    // Single-color model: set E1's color from current printer slot config so
                    // the 3D model preview shows the correct filament color instead of default orange.
                    val colors = MutableList(4) { "" }
                    presets.forEach { preset -> if (preset.index in 0..3) colors[preset.index] = preset.color }
                    _activeExtruderColors.value = colors
                    // Persist the reset so wipeTowerEnabled=false survives across sessions (B24 fix).
                    saveConfig()
                    Log.i("SlicerVM", "Single-color model: set preview colors from slots ${colors}")
                }
                _state.value = SlicerState.ModelLoaded(info)
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
        // B48 fix: SEMM models must NOT remap G-code tool indices.  The slicer maps
        // model colours → physical extruders internally via multi_material_segmentation.
        // T0-T3 in the output ARE physical slot indices.  Applying the model→slot
        // colorMapping as a tool remap scrambles them (T1/green disappears).
        // Per-object models still need compact-slot remapping (e.g. E3+E4 → T0+T1).
        val hasPaintData = _threeMfInfo.value?.hasPaintData == true
        // B48: H2C models (>4 model colours) use virtual extruders — the slicer's
        // T0-T3 are already physical slot indices, no remap needed.
        // Normal painted models (<= 4 model colours) may have sparse slot indices
        // (e.g. [0,2,3]) that need compacting to sequential T0,T1,T2.
        val distinctPhysicalSlots = modelColorToExtruder.distinct().size
        val isH2cStyle = hasPaintData && distinctPhysicalSlots >= 4 && modelColorToExtruder.size > distinctPhysicalSlots
        toolRemapSlots = if (isH2cStyle) {
            null  // H2C: slicer already produces physical tool indices
        } else if (hasPaintData) {
            // Normal SEMM: may need compaction of sparse slots
            val compactSlots = usedSlots.take(extCount)
            val isIdentity = compactSlots == (0 until extCount).toList()
            if (isIdentity) null else compactSlots
        } else {
            // Per-object: extruderRemap in 3MF handles non-contiguous / non-identity slot order
            val compactSlots = usedSlots.take(extCount)
            val isIdentity = compactSlots == (0 until extCount).toList()
            if (isIdentity) null else compactSlots
        }
        // B64: compute SEMM colour permutation for post-slice G-code remapping.
        semmColorPermutation = computeSemmColorPermutation(
            colorMapping = modelColorToExtruder,
            hasPaintData = hasPaintData,
            isH2cStyle = isH2cStyle
        )
        _semmColorPermutationFlow.value = semmColorPermutation
        // B92: derive slicer tool-order mapping so the G-code preview can align its
        // palette with Prepare's compact ordering when OrcaSlicer prints the object
        // default tool first instead of filament-index-ascending.
        val info = _threeMfInfo.value
        _slicerColorOrder.value = computeSlicerColorOrder(
            detectedColors = info?.detectedColors.orEmpty(),
            usedExtruderIndices = info?.usedExtruderIndices ?: emptySet(),
            objectExtruderMap = info?.objectExtruderMap ?: emptyMap(),
            hasPaintData = hasPaintData,
            isH2cStyle = isH2cStyle
        )
        val temps = IntArray(extCount) { i ->
            val slotIndex = usedSlots.getOrElse(i) { i }
            val preset = extruderPresets.firstOrNull { it.index == slotIndex }
            val profileId = preset?.filamentProfileId
            filaments.firstOrNull { it.id == profileId }?.nozzleTemp
                ?: nozzleTempDefaultForMaterial(preset?.materialType ?: "PLA")
        }
        // Recompute wipe tower position if multi-extruder (unless user already placed it)
        val mi = lastModelInfo
        if (extCount > 1 && mi != null && mi.sizeX > 0f && customWipeTowerPos == null) {
            val objPos = CopyArrangeCalculator.calculate(mi.sizeX, mi.sizeY, _copyCount.value)
            val towerPos = CopyArrangeCalculator.computeWipeTowerPosition(
                objPos, mi.sizeX, mi.sizeY, _config.value.wipeTowerWidth
            )
            val filamentLabel = resolveFilamentTypeLabelFromMapping(modelColorToExtruder, extruderPresets)
            _config.value = _config.value.copy(
                extruderCount = extCount,
                extruderTemps = temps,
                extruderRetractLength = FloatArray(extCount) { _config.value.retractLength },
                extruderRetractSpeed = FloatArray(extCount) { _config.value.retractSpeed },
                wipeTowerEnabled = true,
                wipeTowerX = towerPos.first,
                wipeTowerY = towerPos.second,
                filamentType = filamentLabel
            )
            customWipeTowerPos = towerPos
            Log.i("SlicerVM", "Auto-placed wipe tower at (${towerPos.first}, ${towerPos.second})")
        } else {
            val filamentLabel = resolveFilamentTypeLabelFromMapping(modelColorToExtruder, extruderPresets)
            _config.value = _config.value.copy(
                extruderCount = extCount,
                extruderTemps = temps,
                extruderRetractLength = FloatArray(extCount) { _config.value.retractLength },
                extruderRetractSpeed = FloatArray(extCount) { _config.value.retractSpeed },
                wipeTowerEnabled = extCount > 1,
                filamentType = filamentLabel
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
                "semmColorPermutation" to semmColorPermutation,
                "slotColors" to fullColors
            )
        )
        _sliceStale.value = true
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
     * Resolves the nozzle temperature for the single-color extruder at [index].
     * Prefers the linked FilamentProfile's nozzleTemp; falls back to material-type default.
     */
    private fun computeSingleColorTemp(index: Int): Int {
        val preset = extruderPresets.value.firstOrNull { it.index == index }
        val profileId = preset?.filamentProfileId
        return filaments.value.firstOrNull { it.id == profileId }?.nozzleTemp
            ?: nozzleTempDefaultForMaterial(preset?.materialType ?: "PLA")
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

        // Update filament type from the selected extruder's material
        val material = presets.firstOrNull { it.index == index }?.materialType ?: "PLA"

        // Configure tool remapping: single-color model uses T0 in native slicer,
        // but we want it printed on the selected physical extruder slot.
        val temp = computeSingleColorTemp(index)
        if (index == 0) {
            // E1 selected — identity mapping, no remap needed
            resetToolRemapState()
            _config.value = _config.value.copy(
                extruderCount = 1,
                wipeTowerEnabled = false,
                filamentType = material,
                extruderTemps = intArrayOf(temp)
            )
        } else {
            // E2/E3/E4 — remap T0 → physical slot
            toolRemapSlots = listOf(index)
            _config.value = _config.value.copy(
                extruderCount = 1,
                wipeTowerEnabled = false,
                filamentType = material,
                extruderTemps = intArrayOf(temp)
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
        invalidatePrepareMeshCache() // B49: force fresh native fetch for new geometry
        _sliceStale.value = true
    }

    fun setModelRotation(rotation: ModelRotation) {
        _modelRotation.value = rotation
        customObjectPositions = null // reset positions — re-center for rotated footprint
        invalidatePrepareMeshCache() // B49: force fresh native fetch for rotated geometry
        _sliceStale.value = true
    }

    fun setCopyCount(count: Int) {
        _copyCount.value = count.coerceIn(1, 16)
        // B65: use scaled dimensions for bed warning, don't hard-block
        val mi = lastModelInfo
        val s = _modelScale.value
        _copyBedWarning.value = if (mi != null && mi.sizeX > 0f && mi.sizeY > 0f)
            CopyArrangeCalculator.copyBedWarning(mi.sizeX * s.x, mi.sizeY * s.y, _copyCount.value)
        else null
        customObjectPositions = null // reset custom positions when count changes
        _sliceStale.value = true
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
        _sliceStale.value = true
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
        _sliceStale.value = true
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.savePlateType(type)
            settingsRepo.saveSliceConfig(_config.value)
        }
    }

    /** Direct bed temp edit — user overrides the plate type preset. */
    fun setBedTemp(temp: Int) {
        _config.value = _config.value.copy(bedTemp = temp)
        _sliceStale.value = true
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
     * Toggle the prime tower on/off from the Prepare screen switch.
     *
     * B53: Previously only updated SliceConfig.wipeTowerEnabled, leaving primeTower in USE_FILE
     * mode. For multi-extruder models the resolvePrimeTower guard forced the tower back on.
     * Fix: always set OVERRIDE mode so the explicit user choice is honoured.
     */
    fun togglePrimeTower() {
        val cfg = _config.value
        val newOverride = computeTogglePrimeTower(slicingOverrides.value.primeTower, cfg.wipeTowerEnabled)
        _config.value = cfg.copy(wipeTowerEnabled = newOverride.value ?: cfg.wipeTowerEnabled)
        saveSlicingOverrides(slicingOverrides.value.copy(primeTower = newOverride))
        invalidatePrepareMeshCache()
        _sliceStale.value = true
    }

    /**
     * Runs the shared 3MF preparation path used by all import entry points:
     * parse original metadata, capture file-level config, sanitize/defer restructure,
     * merge stage metadata, then embed the active Snapmaker profile.
     */
    private fun prepareImportedModelArtifacts(sourceFile: File, workspaceDir: File): PreparedModelArtifacts {
        val origInfo = ThreeMfParser.parse(sourceFile)
        recoveryOrigInfo = origInfo

        val sourceConfig = if (origInfo.isBambu) {
            ZipFile(sourceFile).use { profileEmbedder.parseSourceConfig(it) }
        } else {
            null
        }
        _sourceConfig.value = sourceConfig

        val processed = BambuSanitizer.process(sourceFile, workspaceDir, isBambu = origInfo.isBambu)
        val processedInfo = ThreeMfParser.parse(processed, skipPaintDetection = true)
        val mergedInfo = mergeThreeMfInfo(processedInfo, origInfo)

        _threeMfInfo.value = mergedInfo
        _fileThreeMfInfo = mergedInfo
        _multiPlatePlates.value = if (origInfo.isMultiPlate) mergedInfo.plates else emptyList()
        sourceModelFile = processed
        sourceModelInfo = processedInfo
        if (origInfo.isMultiPlate) _multiPlateSourceFile = processed
        resetToolRemapState()

        // B93 phase 1: skip the full-file embedProfile for multi-plate 3MFs.
        // The user must pick a plate next, at which point selectPlate() extracts
        // the plate from `processed` and re-embeds only that plate. The full-file
        // embed was ~20-40s of throwaway work on large files (Buzz 73MB).
        // Export paths and currentModelPath fall back to the sanitized file for
        // pre-plate-selection state, which is acceptable because export of a
        // multi-plate file without a plate selection is already ambiguous.
        val willRequirePlateSelection = origInfo.isMultiPlate && mergedInfo.plates.size > 1
        val embedded = if (willRequirePlateSelection) {
            Log.i("SlicerVM", "B93: skipping full-file embedProfile for multi-plate " +
                "(${mergedInfo.plates.size} plates) — waiting for plate selection")
            processed
        } else {
            embedProfile(processed, mergedInfo, workspaceDir)
        }
        return PreparedModelArtifacts(
            rawFile = sourceFile,
            origInfo = origInfo,
            sourceConfig = sourceConfig,
            processedFile = processed,
            processedInfo = processedInfo,
            mergedInfo = mergedInfo,
            embeddedFile = embedded
        )
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
        // B95: pass max source filament index so plates with paint_color attributes
        // referencing high-index filaments (Buzz Lightyear plate 9: state 11 from "8C")
        // get an embedded filament_colour large enough for the slicer's segmentation
        // pass to address them. Without this, the high-index paint state is silently
        // dropped and the resulting G-code contains only the object-default tool.
        val maxSourceFilamentIndex = info.usedExtruderIndices.maxOrNull() ?: 0
        val targetCount = computeEmbedTargetCount(
            colorMapping, info.hasPaintData, usedSlots, extCount,
            hasMultiExtruderAssignments = info.hasMultiExtruderAssignments,
            maxSourceFilamentIndex = maxSourceFilamentIndex
        )
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
        val presets = extruderPresets.value
        val types = presets.sortedBy { it.index }.map { it.materialType }
        val temps = computeFreshExtruderTemps(extCount, usedSlots, presets, filaments.value).toList()
        return buildProfileOverridesImpl(cfg, slicingOverrides.value, extCount, hasSourceConfig, filamentTypes = types, nozzleTemps = temps)
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
            "semmColorPermutation" to semmColorPermutation,
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
     * Hard-cancel an in-progress slice via native Print::cancel().  Transitions to Cancelling
     * state (shows "Cancelling..." UI). The native pipeline throws CanceledException at the
     * next checkpoint and returns a result with cancelled=true, which triggers the transition
     * back to ModelLoaded.
     */
    fun cancelSlicing() {
        if (_state.value is SlicerState.Slicing) {
            _state.value = SlicerState.Cancelling
            viewModelScope.launch(Dispatchers.IO) {
                native.cancelSlice()
            }
            Log.i("SlicerVM", "Slicing cancel requested (native will stop at next checkpoint)")
        }
    }

    fun startSlicing() {
        // Consume the bitmap atomically before launching so it is cleared even if slicing
        // fails early or throws — avoids leaking a full-resolution screen-capture Bitmap.
        val capturedBitmap = pendingThumbnailBitmap.also { pendingThumbnailBitmap = null }
        _sliceStale.value = false
        slicingJob = viewModelScope.launch(Dispatchers.IO) {
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
                    // Guard: don't override Loading — selectPlate() may have set it to initiate
                    // a plate switch while this slice was still running. Overriding Loading would
                    // remount InlineModelPreview against the stale native state and produce
                    // wrong preview colours on the new plate.
                    val cur = _state.value
                    if (cur !is SlicerState.Loading && cur !is SlicerState.Idle) {
                        if (pct > maxPct) maxPct = pct
                        _state.value = SlicerState.Slicing(maxPct, stage)
                        SlicingService.updateProgress(context, maxPct, stage)
                    }
                }

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
                        val reembedded = embedProfile(src, srcInfo, transientWorkspaceDir())
                        // Acquire previewMutex before touching native model — prevents SIGSEGV
                        // when getPreparePreviewMesh is concurrently iterating model volumes
                        // on the preview coroutine while we clear+reload here.
                        val reloadOk = NativeLibrary.previewMutex.withLock {
                            if (!isSingleExtruderRefresh) {
                                // Multi-extruder/remap path: clear first to avoid OOM from holding
                                // two large model instances in native memory during re-load.
                                native.clearModel()
                            }
                            // Single-extruder settings refresh: skip clearModel() — files are small
                            // (no OOM risk) and clearModel()+loadModel() can corrupt native statics,
                            // causing "Coordinate outside allowed range" Clipper errors (I2).
                            native.loadModel(reembedded.absolutePath)
                        }
                        currentModelFile = reembedded
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
                val rot = _modelRotation.value
                if (rot.x != 0f || rot.y != 0f || rot.z != 0f) {
                    native.setModelRotation(rot.x, rot.y, rot.z)
                }
                // B78: mark native slice state dirty so InlineModelPreview knows the
                // file's natural scale/position has been clobbered and must be reset
                // before the next preview fetch (B72/B73). We set this before
                // setModelInstances below even when the setModelScale guard didn't fire,
                // because setModelInstances itself mutates instance state.
                _nativeSliceStateDirty.value = true

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
                        val estimatedDepth = WipeTowerDepthEstimator.estimateDepth(lastModelInfo?.sizeZ ?: 0f)
                        val (maxX, maxY) = wipeTowerClampBounds(cfg.bedSizeX, cfg.bedSizeY, cfg.wipeTowerWidth, estimatedDepth)
                        val clampedX = cfg.wipeTowerX.coerceIn(0f, maxX)
                        val clampedY = cfg.wipeTowerY.coerceIn(0f, maxY)
                        if (clampedX != cfg.wipeTowerX || clampedY != cfg.wipeTowerY) {
                            Log.w("SlicerVM", "Clamped wipe tower from (${cfg.wipeTowerX},${cfg.wipeTowerY}) to ($clampedX,$clampedY) — was outside bed bounds")
                        }
                        cfg.copy(wipeTowerX = clampedX, wipeTowerY = clampedY)
                    } else cfg
                }
                // Recompute extruderTemps from current presets at slice time.
                // applyMultiColorAssignments / updateSingleColorExtruder set extruderTemps at
                // model-load time; if the user changes presets (or applies a filament profile via
                // the library) after loading, the stored value is stale.
                // NOTE: extruder_temps is what applyConfigToPrusa() actually reads for
                // nozzle_temperature — NOT the nozzle_temperature key in the embedded profile
                // (which is not in profile_keys[] and is therefore ignored by the native slicer).
                val sliceConfig = resolvedSliceConfig.let { cfg ->
                    cfg.copy(extruderTemps = computeFreshExtruderTemps(
                        extruderCount = cfg.extruderCount,
                        usedSlots = toolRemapSlots,
                        presets = extruderPresets.value,
                        filaments = filaments.value
                    ))
                }
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
                ensureActive()

                // Native cancel: slice() returned with cancelled=true from CanceledException
                if (result?.cancelled == true) {
                    Log.i("SlicerVM", "Slice cancelled — returning to ModelLoaded")
                    result.gcodePath.let { path ->
                        if (path.isNotEmpty()) java.io.File(path).delete()
                    }
                    backToModelLoaded()
                    return@launch
                }

                if (result != null && result.success) {
                    // B63: patch filament_type header with current extruder preset material types.
                    // Needed for STL files (no embedded profile → native slicer uses OrcaSlicer
                    // default "PLA") and as a staleness guard for 3MF files (profile was embedded
                    // at model-load time; user may have changed presets since).
                    val ftTypes = extruderPresets.value.sortedBy { it.index }.map { it.materialType }
                    val ftPatched = fixFilamentTypeHeader(result.gcodePath, ftTypes)
                    Log.i("SlicerVM", "B63 filament_type patch: $ftPatched (types=$ftTypes)")

                    val layerToolMetadataFile = when {
                        _threeMfInfo.value?.hasLayerToolChanges != true -> null
                        sourceModelFile?.exists() == true -> sourceModelFile
                        else -> currentModelFile
                    }
                    // Native nativeGetPlateData takes a 0-based plate index. _currentPlateId is
                    // 1-based (or -1 when no plate selected — treat as plate 0 for the injector
                    // fallback, matching the STL / single-plate default used elsewhere).
                    val plateIdxForInjector = (_currentPlateId.value - 1).coerceAtLeast(0)
                    val injectedLayerToolPause = layerToolMetadataFile
                        ?.let {
                            LayerToolPauseInjector.injectFrom3mf(
                                result.gcodePath,
                                it,
                                plateIdxForInjector,
                                native
                            )
                        }
                        ?: false
                    if (injectedLayerToolPause) {
                        Log.i(
                            "SlicerVM",
                            "Injected layer-change pause commands into ${result.gcodePath} using ${layerToolMetadataFile?.name}"
                        )
                    }
                    // B92: Apply tool remap BEFORE parsing the G-code for the Preview viewer.
                    // The remap rewrites compact T-indices (T0, T1, ...) to physical slots
                    // (e.g. T1 → T3 when colorMapping=[0, 3]). validateSliceOutput parses the
                    // file into ParsedGcode that drives the Preview renderer, so it MUST see
                    // the post-remap T-indices — otherwise the renderer paints moves with
                    // GcodeRenderer's default-palette colour at the unmapped slot (sky blue
                    // for slot 1), reproducing the user's blue-stripes screenshot.
                    //
                    // B95: when the embedded filament_colour was bumped to fit a high-index
                    // source filament (Buzz plate 9: state 11 from `paint_color="8C"`), the
                    // slicer emits T<filament-1> for each used filament instead of compact
                    // T0..T(N-1). computeExpandedGcodeRemap returns a list mapping each
                    // emitted T-index back to the user's physical slot via colorMapping.
                    // This expanded remap takes precedence over the legacy
                    // semmColorPermutation when both apply.
                    val sliceInfo = _threeMfInfo.value
                    val sliceColorMapping = _colorMapping.value
                    val maxSourceFilamentIndex = sliceInfo?.usedExtruderIndices?.maxOrNull() ?: 0
                    val embeddedFilamentCount = computeEmbedTargetCount(
                        colorMapping = sliceColorMapping,
                        hasPaintData = sliceInfo?.hasPaintData == true,
                        toolRemapSlots = toolRemapSlots,
                        fallbackExtCount = _config.value.extruderCount.coerceAtLeast(1),
                        hasMultiExtruderAssignments = sliceInfo?.hasMultiExtruderAssignments == true,
                        maxSourceFilamentIndex = maxSourceFilamentIndex
                    )
                    val expandedRemap = computeExpandedGcodeRemap(
                        usedExtruderIndices = sliceInfo?.usedExtruderIndices.orEmpty(),
                        colorMapping = sliceColorMapping,
                        embeddedFilamentCount = embeddedFilamentCount
                    )
                    val composedRemap = expandedRemap
                        ?: composeSemmRemap(toolRemapSlots, semmColorPermutation)
                    _gcodeUsesPhysicalSlots.value = expandedRemap != null
                    if (composedRemap != null) {
                        GcodeToolRemapper.remap(result.gcodePath, composedRemap)
                        Log.i(
                            "SlicerVM",
                            "Post-processed G-code: remapped tools to $composedRemap " +
                                "(expandedRemap=${expandedRemap != null}, " +
                                "toolRemap=$toolRemapSlots, semmPerm=$semmColorPermutation)"
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
                        if (e is CancellationException) throw e
                        Log.w("SlicerVM", "Thumbnail injection failed (non-fatal): ${e.message}")
                    }

                    _state.value = SlicerState.SliceComplete(result)
                    _gcodePreview.value = native.getGcodePreview(50)
                    _parsedGcode.value = outputValidation.parsedGcode
                    settingsRepo.saveSliceConfig(_config.value)
                    // Save job to history. Copy source model to durable storage so it can be
                    // re-opened from the Jobs tab even after the transient workspace is cleared.
                    val cfg = _config.value
                    val jobId = sliceJobDao.insert(
                        SliceJob(
                            modelName = currentModelName.ifEmpty { "Unknown" },
                            gcodePath = result.gcodePath,
                            sourcePath = null, // filled in below once we have the rowid
                            totalLayers = result.totalLayers,
                            estimatedTimeSeconds = result.estimatedTimeSeconds,
                            estimatedFilamentMm = result.estimatedFilamentMm,
                            estimatedFilamentGrams = result.estimatedFilamentGrams,
                            layerHeight = cfg.layerHeight,
                            fillDensity = cfg.fillDensity,
                            nozzleTemp = cfg.nozzleTemp,
                            bedTemp = cfg.bedTemp,
                            supportEnabled = cfg.supportEnabled,
                            filamentType = cfg.filamentType
                        )
                    )
                    // Copy gcode to durable per-job storage so Jobs "View G-code" always reads the
                    // correct file even after subsequent slices overwrite the transient output.gcode.
                    val durableGcode = copyGcodeToDurableJobDir(jobId, File(result.gcodePath))
                    if (durableGcode != null) {
                        sliceJobDao.updateGcodePath(jobId, durableGcode.absolutePath)
                        // Also update local state so the current session uses the durable path.
                        _state.value = SlicerState.SliceComplete(result.copy(gcodePath = durableGcode.absolutePath))
                    }
                    // Store the original (pre-embed) source file for F61 re-open.
                    // rawInputFile is the sanitized-but-not-embedded copy — reloading it via
                    // loadModelFromFile() applies a fresh embed and correctly restores colour data.
                    // currentModelFile is the profile-embedded file; reloading it causes the
                    // sanitizer to strip project_settings.config before the colour parse, which can
                    // collapse multi-colour models to single-colour on re-open.
                    val durableSource = copySourceToDurableJobDir(jobId, rawInputFile ?: sourceModelFile ?: currentModelFile)
                    if (durableSource != null) {
                        sliceJobDao.updateSourcePath(jobId, durableSource.absolutePath)
                    }
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
                if (e is CancellationException) throw e
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
     * Reload the model from the already-processed file and return to ModelLoaded state.
     * Called when the user taps "Reset & Retry" after a Clipper slicing failure.
     *
     * All Kotlin model state (lastModelInfo, _threeMfInfo, color mapping) is already intact —
     * only the native model was cleared. Re-running loadNativeModel() restores the JNI state
     * without requiring the user to pick the file again.
     */
    fun recoverFromClipperError() {
        val file = currentModelFile ?: run {
            _state.value = SlicerState.Error("No model file to reload")
            return
        }
        clipperRetryAttempted = false  // grant fresh auto-recovery on next slice attempt
        viewModelScope.launch {
            _state.value = SlicerState.Loading("Reloading model…")
            loadNativeModel(file)
        }
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
            val existingNames = filamentDao.getAll().first().map { it.name }.toSet()
            profiles.filter { it.name !in existingNames }.forEach { filamentDao.insert(it) }
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
                retractLength = profile.retractLength,
                retractSpeed = profile.retractSpeed,
                filamentType = profile.material
            )
        }
        // Also update the selected extruder's preset so buildProfileOverrides() computes
        // the correct nozzle temp from the preset at slice time (not the stale materialType default).
        // Only do this for single-colour mode — multi-colour presets are managed separately.
        if (_config.value.extruderCount == 1) {
            val selectedIdx = _selectedExtruder.value
            val current = extruderPresets.value.toMutableList()
            val idx = current.indexOfFirst { it.index == selectedIdx }
            val updated = if (idx >= 0) {
                current[idx].copy(materialType = profile.material, filamentProfileId = profile.id)
            } else {
                com.u1.slicer.data.ExtruderPreset(
                    index = selectedIdx,
                    materialType = profile.material,
                    filamentProfileId = profile.id
                )
            }
            if (idx >= 0) current[idx] = updated else current.add(updated)
            viewModelScope.launch(Dispatchers.IO) {
                settingsRepo.saveExtruderPresets(current.sortedBy { it.index })
            }
        }
    }

    // ---- Job History ----
    fun deleteJob(job: SliceJob) {
        viewModelScope.launch(Dispatchers.IO) {
            sliceJobDao.delete(job)
            File(getApplication<Application>().filesDir, "jobs/${job.id}").deleteRecursively()
        }
    }

    fun deleteAllJobs() {
        viewModelScope.launch(Dispatchers.IO) {
            sliceJobDao.deleteAll()
            File(getApplication<Application>().filesDir, "jobs").deleteRecursively()
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

    // F60: parse saved G-code and set it as the active preview so the viewer can display it.
    // Returns true if the file existed and parsing was started, false if the file is missing.
    fun loadJobGcodeForViewer(job: SliceJob, onResult: (success: Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val gcodeFile = File(job.gcodePath)
            if (!gcodeFile.exists()) {
                launch(Dispatchers.Main) { onResult(false) }
                return@launch
            }
            try {
                val parsed = GcodeParser.parse(gcodeFile)
                _parsedGcode.value = parsed
                _state.value = SlicerState.SliceComplete(sliceResultFromJob(job))
                _gcodePreview.value = ""
                launch(Dispatchers.Main) { onResult(true) }
            } catch (e: Exception) {
                Log.e("SlicerVM", "Failed to parse job G-code: ${e.message}")
                launch(Dispatchers.Main) { onResult(false) }
            }
        }
    }

    // F61: reload the source 3MF/STL saved for a job back into the Prepare screen.
    // Returns true if the source file existed and loading was started, false if it is missing.
    fun reopenJobToEdit(job: SliceJob, onMissing: () -> Unit) {
        val sourcePath = job.sourcePath
        if (sourcePath == null) {
            onMissing()
            return
        }
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists()) {
            onMissing()
            return
        }
        loadModelFromFile(sourceFile)
    }

    // Copy the sliced gcode to files/jobs/<jobId>/ so Jobs "View G-code" always reads the right
    // file even after subsequent slices overwrite the transient output.gcode.
    private fun copyGcodeToDurableJobDir(jobId: Long, gcodeFile: File): File? {
        if (!gcodeFile.exists()) return null
        return try {
            val jobDir = File(getApplication<Application>().filesDir, "jobs/$jobId")
            jobDir.mkdirs()
            val dest = File(jobDir, "output.gcode")
            gcodeFile.copyTo(dest, overwrite = true)
            dest
        } catch (e: Exception) {
            Log.w("SlicerVM", "Failed to copy gcode to durable job dir: ${e.message}")
            null
        }
    }

    // Copy the current source model to files/jobs/<jobId>/ for durable storage (F61).
    private fun copySourceToDurableJobDir(jobId: Long, sourceFile: File?): File? {
        if (sourceFile == null || !sourceFile.exists()) return null
        return try {
            val jobDir = File(getApplication<Application>().filesDir, "jobs/$jobId")
            jobDir.mkdirs()
            val dest = File(jobDir, sourceFile.name)
            sourceFile.copyTo(dest, overwrite = true)
            dest
        } catch (e: Exception) {
            Log.w("SlicerVM", "Failed to copy source to durable job dir: ${e.message}")
            null
        }
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
                // Insert filament profiles first so we can resolve names → IDs.
                // Skip profiles whose name already exists to prevent duplicates on repeated imports.
                val nameToId = mutableMapOf<String, Long>()
                data.filamentProfiles?.let { profiles ->
                    val existingByName = filamentDao.getAll().first().associateBy { it.name }
                    profiles.forEach { profile ->
                        val existing = existingByName[profile.name]
                        if (existing == null) {
                            val newId = filamentDao.insert(profile)
                            nameToId[profile.name] = newId
                        } else {
                            nameToId[profile.name] = existing.id
                        }
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
        // B55: signal QEM to bail out immediately — the cancel flag is checked every
        // iteration inside its_quadric_edge_collapse, so QEM exits in microseconds.
        // This releases previewMutex before we try to acquire it below.
        native.cancelPreviewMesh()
        if (NativeLibrary.previewMutex.tryLock()) {
            try { native.clearModel() } finally { NativeLibrary.previewMutex.unlock() }
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                NativeLibrary.previewMutex.withLock { native.clearModel() }
            }
        }
        invalidatePrepareMeshCache()
        _sliceStale.value = false
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
        _fileThreeMfInfo = null
        _multiPlatePlates.value = emptyList()
        _multiPlateSourceFile = null
        _showPlateSelector.value = false
        _showMultiColorDialog.value = false
        currentModelFile = null
        lastModelInfo = null
        _modelInfo.value = null
        _copyCount.value = 1
        customObjectPositions = null
        customWipeTowerPos = null
        resetToolRemapState()
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

    fun getExportableModelArtifacts(): ExportableModelArtifacts? =
        ModelExportArtifacts.current(
            sourceDisplayName = currentModelName,
            selectedPlateId = recoveryPlateId.takeIf { it >= 0 },
            sanitizedFile = sourceModelFile,
            embeddedFile = currentModelFile,
            info = _threeMfInfo.value
        )

    fun buildModelDebugSummary(): String? {
        val artifacts = getExportableModelArtifacts() ?: return null
        return ModelExportArtifacts.buildDebugSummary(artifacts, currentModelFile)
    }

    fun suggestedArtifactFilename(kind: ExportArtifactKind): String? {
        val artifacts = getExportableModelArtifacts() ?: return null
        return ModelExportArtifacts.suggestedFilename(artifacts.sourceDisplayName, kind)
    }

    fun exportArtifactTo(
        kind: ExportArtifactKind,
        targetUri: Uri,
        onResult: (Result<Unit>) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val context = getApplication<Application>()
                val artifacts = getExportableModelArtifacts()
                    ?: error("No exportable model artifacts are available")
                val sourceFile = artifacts.fileFor(kind)
                    ?: error("Requested export artifact is unavailable")
                context.contentResolver.openOutputStream(targetUri, "w")?.use { out ->
                    sourceFile.inputStream().use { input -> input.copyTo(out) }
                } ?: error("Could not open export destination")
                diagnostics.recordEvent(
                    "model_artifact_exported",
                    mapOf(
                        "kind" to kind.name,
                        "sourceDisplayName" to artifacts.sourceDisplayName,
                        "selectedPlateId" to artifacts.selectedPlateId,
                        "artifactPath" to sourceFile.absolutePath,
                        "destinationUri" to targetUri.toString()
                    )
                )
            }.onFailure { error ->
                diagnostics.recordEvent(
                    "model_artifact_export_failed",
                    mapOf(
                        "kind" to kind.name,
                        "destinationUri" to targetUri.toString(),
                        "error" to (error.message ?: error.javaClass.simpleName)
                    )
                )
            }
            withContext(Dispatchers.Main) {
                onResult(result)
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
            layerToolSegments = origInfo.layerToolSegments,
            hasPaintSupports = origInfo.hasPaintSupports
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
            // Use plate-level hasPaintData (not file-level sourceInfo.hasPaintData) so that
            // non-painted plates in a file where other plates have SEMM paint data still get
            // the layerToolOnly treatment — avoiding phantom extra colour chips (B81).
            val plateHasPaintData = plateInfo.hasPaintData
            val layerToolOnly = sourceInfo.hasLayerToolChanges &&
                !plateHasPaintData &&
                !sourceInfo.hasMultiExtruderAssignments
            val filteredColors = if (layerToolOnly) {
                val selectedLayerToolColors = linkedSetOf<String>()
                // When sourcePlateObjectExtruders is non-empty (explicit object→extruder mapping),
                // check whether plateInfo.usedExtruderIndices is over-reporting:
                //   - If it includes the same extruder(s) as the object map, the reconstructed
                //     plate is echoing the base slot alongside transient tool-change slots →
                //     trust the object map only (avoids phantom secondary colour chips).
                //   - If it reports exclusively NEW extruder indices, those are real layer-tool
                //     secondaries → combine with object extruders for the full colour set.
                val selectedExtruders = if (sourcePlateObjectExtruders.isNotEmpty()) {
                    val plateLtColors = sourcePlate?.layerToolColors.orEmpty()
                    val plateLtExtruders = sourcePlate?.layerToolExtruders.orEmpty()
                    when {
                        plateLtColors.isNotEmpty() -> {
                            // Per-plate layer-tool data available. A secondary is "real" only when
                            // its display color matches the file's actual filament palette — this
                            // distinguishes genuine dual-colour layer-change plates (palette match)
                            // from single-colour plates whose layer-tool entry was set up with an
                            // off-palette colour (e.g. flippy plate 1: #2323F7 not in palette).
                            val hasRealSecondaries = plateLtColors.any { color ->
                                sourceInfo.detectedColors.any { it.equals(color, ignoreCase = true) }
                            }
                            if (hasRealSecondaries) {
                                (sourcePlateObjectExtruders + plateLtExtruders).filter { it > 0 }.sorted()
                            } else {
                                sourcePlateObjectExtruders.filter { it > 0 }.sorted()
                            }
                        }
                        plateInfo.usedExtruderIndices.any { it in sourcePlateObjectExtruders } -> {
                            // B80 fallback (no per-plate data): extracted plate over-reports
                            // transient tool-change extruders alongside the base slot → trust
                            // the object map only.
                            sourcePlateObjectExtruders.filter { it > 0 }.sorted()
                        }
                        else -> {
                            (sourcePlateObjectExtruders + plateInfo.usedExtruderIndices.filter { it > 0 })
                                .sorted()
                        }
                    }
                } else {
                    mergedUsedExtruderIndices.filter { it > 0 }.sorted()
                }
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
            // Hueforge plate: single object, single filament, layer-tool changes, no paint,
            // and no per-object extruder diversity in the source file.
            // The UI needs extruderCount > 1 and hasMultiExtruderAssignments=false to activate
            // the layerToolOnly recolor path.
            val sourceObjectExtruderDiversity = sourceInfo.objectExtruderMap.values.toSet().size
            val isHueforgePlate = selectedPlateId != null &&
                sourceInfo.hasLayerToolChanges && !plateHasPaintData &&
                sourcePlateObjectExtruders.size <= 1 && sourcePlateFilamentIndices.size <= 1 &&
                sourceObjectExtruderDiversity <= 1
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
                hasPaintData = plateHasPaintData,
                hasLayerToolChanges = sourceInfo.hasLayerToolChanges,
                hasMultiExtruderAssignments = if (sourcePlateObjectExtruders.size > 1) true
                    else if (isHueforgePlate) false
                    else sourceInfo.hasMultiExtruderAssignments,
                objectExtruderMap = plateInfo.objectExtruderMap.ifEmpty { sourceInfo.objectExtruderMap },
                layerToolSegments = sourceInfo.layerToolSegments,
                hasPaintSupports = sourceInfo.hasPaintSupports
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
    hasSourceConfig: Boolean = false,
    filamentTypes: List<String>? = null,
    nozzleTemps: List<Int>? = null
): Map<String, Any> {
    // nozzleTemps (fresh from slice-time preset lookup) takes priority over the stale
    // cfg.extruderTemps stored at model-load time.  Falls back to cfg.extruderTemps if
    // size matches, otherwise to cfg.nozzleTemp (unit-test / legacy path).
    val temps: MutableList<String> = when {
        nozzleTemps != null && nozzleTemps.size >= extCount ->
            nozzleTemps.take(extCount).map { it.toString() }.toMutableList()
        cfg.extruderTemps.size >= extCount ->
            cfg.extruderTemps.take(extCount).map { it.toString() }.toMutableList()
        else ->
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
    val primeTowerWidth = resolve(ov.primeTowerWidth, cfg.wipeTowerWidth, "primeTowerWidth")
    val wipeTowerRotationAngle = resolve(ov.wipeTowerRotationAngle, 0f, "wipeTowerRotationAngle")

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
        "prime_tower_width" to primeTowerWidth.toString(),
        "wipe_tower_x" to MutableList(extCount) { cfg.wipeTowerX.toString() },
        "wipe_tower_y" to MutableList(extCount) { cfg.wipeTowerY.toString() },
        "prime_volume" to primeVolume.toString(),
        "prime_tower_brim_width" to primeTowerBrimWidth.toString(),
        "prime_tower_brim_chamfer" to if (primeTowerBrimChamfer) "1" else "0",
        "prime_tower_brim_chamfer_max_width" to primeTowerChamferMaxWidth.toString(),
        "wipe_tower_rotation_angle" to wipeTowerRotationAngle.toString(),
        // B63: filament_type per extruder — resolved from user's extruder presets
        "filament_type" to MutableList(extCount) { i ->
            filamentTypes?.getOrNull(i) ?: "PLA"
        }
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

/**
 * Compute the new prime tower override when the user taps the Prepare screen switch.
 *
 * B53: The Prepare screen switch previously only updated SliceConfig.wipeTowerEnabled, leaving
 * SlicingOverrides.primeTower in USE_FILE mode. For multi-extruder models, resolvePrimeTower()
 * has a guard that forces true when mode != OVERRIDE, so the toggle was silently ignored.
 *
 * Fix: always set OVERRIDE mode so the guard is bypassed. The new value is the inverse of
 * the current effective state (override value if present, else cfgWipeTower).
 */
internal fun computeTogglePrimeTower(
    current: com.u1.slicer.data.OverrideValue<Boolean>,
    cfgWipeTower: Boolean
): com.u1.slicer.data.OverrideValue<Boolean> {
    val effective = current.value ?: cfgWipeTower
    return com.u1.slicer.data.OverrideValue(com.u1.slicer.data.OverrideMode.OVERRIDE, !effective)
}

/** Returns a sensible nozzle temperature default for a given material type string. */
internal fun nozzleTempDefaultForMaterial(material: String): Int = when (material.uppercase()) {
    "PETG" -> 235; "ABS" -> 270; "ASA" -> 260; "PA" -> 260; "TPU" -> 225; "PVA" -> 210; else -> 220
}

/**
 * Compute fresh nozzle temperatures for each extruder slot at slice time.
 *
 * [usedSlots] maps compact extruder index → physical slot (e.g. [0,2] for a model using E1+E3).
 * Null means identity (slot i == index i).
 *
 * Priority: linked filament profile nozzleTemp > materialType default.
 *
 * This must be called immediately before native.slice() — not at model-load time — because
 * the user can change presets or apply a library filament profile after the model is loaded,
 * which would otherwise leave extruderTemps stale.
 */
internal fun computeFreshExtruderTemps(
    extruderCount: Int,
    usedSlots: List<Int>?,
    presets: List<com.u1.slicer.data.ExtruderPreset>,
    filaments: List<com.u1.slicer.data.FilamentProfile>
): IntArray {
    val slots = usedSlots ?: (0 until extruderCount).toList()
    return IntArray(extruderCount) { i ->
        val slotIndex = slots.getOrElse(i) { i }
        val preset = presets.firstOrNull { it.index == slotIndex }
        val profileId = preset?.filamentProfileId
        filaments.firstOrNull { it.id == profileId }?.nozzleTemp
            ?: nozzleTempDefaultForMaterial(preset?.materialType ?: "PLA")
    }
}

/**
 * Derive the filament type label for the Slice Settings card from the active
 * extruder slots and their preset materials.
 *
 * - Single slot (or all slots share the same material) → that material name
 * - Slots differ → "Mixed"
 * - Unknown slot / empty inputs → "PLA" (safe default)
 */
internal fun resolveFilamentTypeLabel(
    usedSlots: List<Int>,
    presets: List<com.u1.slicer.data.ExtruderPreset>
): String {
    if (usedSlots.isEmpty()) return "PLA"
    val materials = usedSlots.map { slot ->
        presets.firstOrNull { it.index == slot }?.materialType ?: "PLA"
    }
    val distinct = materials.distinct()
    return if (distinct.size == 1) distinct.first() else "Mixed"
}

/**
 * Wiring helper for single-color initial model load.
 * A single-color model always prints on slot 0 (E1) initially — uses E1's material.
 */
internal fun resolveFilamentTypeForSingleColorLoad(
    presets: List<com.u1.slicer.data.ExtruderPreset>
): String = resolveFilamentTypeLabel(listOf(0), presets)

/**
 * Wiring helper for multi-color and layer-tool model load.
 * Derives used slots from the colorMapping (distinct, sorted) then resolves label.
 */
internal fun resolveFilamentTypeLabelFromMapping(
    colorMapping: List<Int>,
    presets: List<com.u1.slicer.data.ExtruderPreset>
): String {
    val usedSlots = colorMapping.distinct().sorted()
    return resolveFilamentTypeLabel(usedSlots, presets)
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

/**
 * Compute the extruder count to embed in the 3MF profile.
 * - H2C SEMM models (more model colours than physical extruders): use colorMapping.size
 *   so multi_material_segmentation_by_painting() captures all paint states.
 * - Normal SEMM models (model colours <= physical extruders): use distinct physical
 *   slot count from the mapping — same as pre-B48 behaviour.
 * - Per-object models with tool remap: use distinct slot count from toolRemapSlots.
 * - Everything else: use the physical extruder count.
 *
 * Note on B95: an attempt was made to bump the embed count to
 * `max(usedExtruderIndices)` for plates like Buzz 9 where the source references
 * a high-index paint state. It worked at the embed level (filament_colour sized
 * to 10) but the slicer's paint segmentation still only emitted T_<default>
 * — the paint state's bit-packed encoding decodes to a state different from
 * what the Kotlin first-char heuristic reports. The real fix requires either
 * decoding/re-encoding the bit-packed paint_color attribute or a native patch
 * inside `multi_material_segmentation_by_painting()`. Left as a known-open
 * investigation.
 */
internal fun computeEmbedTargetCount(
    colorMapping: List<Int>?,
    hasPaintData: Boolean,
    toolRemapSlots: List<Int>?,
    fallbackExtCount: Int,
    hasMultiExtruderAssignments: Boolean = false,
    maxSourceFilamentIndex: Int = 0
): Int {
    if (hasPaintData && colorMapping != null && colorMapping.isNotEmpty()) {
        val distinctSlots = colorMapping.distinct().size.coerceAtLeast(1)
        // B48 H2C: when all 4 physical extruders are used AND there are more model
        // colours, the slicer needs virtual extruders (one per model colour) so
        // multi_material_segmentation_by_painting() captures all paint states.
        val isH2c = distinctSlots >= 4 && colorMapping.size > distinctSlots
        // B76 (Jon's Goat): hybrid models where the user has collapsed ONE paint
        // state onto an existing slot (colorMapping.size - distinctSlots == 1)
        // need the full paint-state count preserved so per-object parts whose
        // extruder index matches the collapsed state still get a valid slot.
        // Anything looser than size-distinct==1 is normal SEMM with sparse
        // usage (e.g. old.3mf has 6 paint states on 3 slots — delta 3 — and
        // must keep the distinct count so GcodeToolRemapper's tool distribution
        // matches the physical slots exactly).
        val isHybridSingleDedup = hasMultiExtruderAssignments &&
            (colorMapping.size - distinctSlots) == 1
        val baseSize = if (isH2c || isHybridSingleDedup) {
            colorMapping.size
        } else {
            distinctSlots
        }
        // B95: when paint_color attributes reference 1-based filament indices higher
        // than the user's distinct-slot count, the embedded filament_colour must be
        // sized to address them or `multi_material_segmentation_by_painting()` will
        // silently drop the high-index states (`if (state > max_ebt) state = NONE`).
        // Buzz Lightyear plate 9 has paint_color="8C" → state 11 against detected-
        // colour count 2; without this bump, the 4-extruder Snapmaker U1 receives a
        // single-tool G-code instead of two. The post-slice GcodeToolRemapper
        // remaps the resulting high T-indices back to the user's physical slots.
        return maxOf(baseSize, maxSourceFilamentIndex)
    }
    if (toolRemapSlots != null) return toolRemapSlots.distinct().size
    return fallbackExtCount
}

/**
 * Compute the SEMM colour permutation for post-slice G-code remapping.
 *
 * For normal SEMM paint models, the slicer outputs T0–T(N-1) based on the 3MF's
 * filament_colour order. When the user assigns model colours to physical extruders
 * in a non-identity order (e.g. Color1→E4, [3,0,2,1]), this permutation must be
 * applied to the G-code so T0→T3, T1→T0, etc.
 *
 * Returns null when no remap is needed: identity mapping, H2C models, or non-SEMM models.
 */
internal fun computeSemmColorPermutation(
    colorMapping: List<Int>,
    hasPaintData: Boolean,
    isH2cStyle: Boolean
): List<Int>? {
    if (!hasPaintData) return null
    if (isH2cStyle) return null
    val identity = (0 until colorMapping.size).toList()
    if (colorMapping == identity) return null
    return colorMapping
}

/**
 * B92: Compute the mapping from a slicer compact tool index (k, 0-based) to the
 * corresponding index into `detectedColors`.
 *
 * OrcaSlicer's tool ordering for SEMM paint models is **print-order**: the object's
 * default extruder is emitted as T0 first, and paint states are emitted as T1, T2, ...
 * in ascending paint-state order. `detectedColors`, by contrast, is built by
 * `parseForPlateSelection` in **source-filament-ascending order**.
 *
 * When the object default has a higher source filament than one of the paint
 * states — Buzz plate 8 (object=10, paint state 3) is the canonical example —
 * the two orderings disagree. The Prepare preview uses detectedColors order; the
 * G-code uses slicer print order. Without correcting this, the Preview viewer
 * paints T0 with `detectedColors[0]`'s assigned colour even though T0 is really
 * `detectedColors[defaultIndex]`'s tool.
 *
 * Returns `null` when the slicer order equals `detectedColors` order (identity case,
 * safe to keep current behaviour) — includes non-paint, H2C, and simple paint-only
 * models where the default extruder sits at index 0 of detectedColors.
 *
 * For the non-identity case (Buzz plate 8 shape), returns a permutation:
 *   result[0] = defaultIndex      // slicer T0 = object default = detectedColors[defaultIndex]
 *   result[1..N-1] = remaining detectedColors indices in ascending order.
 */
internal fun computeSlicerColorOrder(
    detectedColors: List<String>,
    usedExtruderIndices: Set<Int>,
    objectExtruderMap: Map<String, Int>,
    hasPaintData: Boolean,
    isH2cStyle: Boolean
): List<Int>? {
    if (!hasPaintData) return null
    if (isH2cStyle) return null
    val n = detectedColors.size
    if (n < 2) return null
    // usedExtruderIndices is the sorted-ascending source filament list backing
    // detectedColors (mergeThreeMfInfoForPlate keeps them aligned).
    val extruders = usedExtruderIndices.toList()
    if (extruders.size != n) return null
    // Determine the dominant object extruder — the one most objects reference.
    // Models with mixed per-object extruders + paint data fall back to identity
    // because there is no single "object default" for OrcaSlicer to lead with.
    val counts = objectExtruderMap.values.groupingBy { it }.eachCount()
    if (counts.isEmpty()) return null
    val maxCount = counts.values.max()
    val dominantCandidates = counts.entries.filter { it.value == maxCount }.map { it.key }
    if (dominantCandidates.size != 1) return null
    val defaultExtruder = dominantCandidates.first()
    val defaultIndex = extruders.indexOf(defaultExtruder)
    if (defaultIndex <= 0) return null
    // Slicer order: default first, then paint states ascending (excluding the default).
    val result = mutableListOf(defaultIndex)
    for (i in 0 until n) if (i != defaultIndex) result.add(i)
    val identity = (0 until n).toList()
    if (result == identity) return null
    return result
}

/**
 * B95: Compute the post-slice tool remap for paint plates whose embedded
 * `filament_colour` was bumped to fit a high-index source filament (e.g. Buzz
 * Lightyear plate 9: state 11 from `paint_color="8C"`). The slicer emits
 * `T<filament_index - 1>` for each paint state and object extruder; this
 * function returns a list `out` such that `out[t]` is the physical slot
 * (0..3) the user wants for slicer tool index `t`.
 *
 * Returns `null` when the bump didn't apply (`embeddedFilamentCount` is at or
 * below the user's distinct-slot count) and the existing
 * [computeSemmColorPermutation] / [toolRemapSlots] paths should drive the
 * remap instead.
 *
 * Entries for tool indices the slicer never emits are populated with a
 * harmless identity fallback (`t` itself coerced into 0..3) so any stray
 * out-of-band T-index lands on a valid slot rather than being left as a
 * wrap-around to slot 3 by `GcodeParser`'s `coerceIn(0, 3)`.
 */
internal fun computeExpandedGcodeRemap(
    usedExtruderIndices: Iterable<Int>,
    colorMapping: List<Int>?,
    embeddedFilamentCount: Int
): List<Int>? {
    if (colorMapping.isNullOrEmpty()) return null
    if (embeddedFilamentCount <= 0) return null
    // Only useful when the embed was bumped beyond the distinct-slot count;
    // otherwise the existing semmColorPermutation / toolRemapSlots logic
    // keeps the same final mapping with less plumbing.
    val distinctSlots = colorMapping.distinct().size
    if (embeddedFilamentCount <= distinctSlots) return null
    val sortedFilaments = usedExtruderIndices.toSortedSet().toList()
    if (sortedFilaments.isEmpty()) return null
    val out = MutableList(embeddedFilamentCount) { idx -> idx.coerceIn(0, 3) }
    sortedFilaments.forEachIndexed { detectedIdx, filamentIdx ->
        val tIndex = filamentIdx - 1
        val userSlot = colorMapping.getOrNull(detectedIdx) ?: detectedIdx
        if (tIndex in out.indices && userSlot in 0..3) out[tIndex] = userSlot
    }
    return out
}

/**
 * Compose toolRemapSlots and semmColorPermutation into a single remap list.
 *
 * semmColorPermutation already maps compact T-index → physical slot, so when
 * both are present it subsumes toolRemapSlots (which maps compact T-index →
 * physical slot for sparse-slot compaction).
 */
internal fun composeSemmRemap(
    toolRemapSlots: List<Int>?,
    semmColorPermutation: List<Int>?
): List<Int>? = when {
    semmColorPermutation != null -> semmColorPermutation
    toolRemapSlots != null -> toolRemapSlots
    else -> null
}

/**
 * B63: Replace the `; filament_type = ...` header comment in a generated G-code file
 * with [filamentTypes] joined by semicolons.
 *
 * For STL files there is no embedded profile, so the native slicer writes OrcaSlicer's
 * default "PLA" for all slots.  For 3MF files the profile is embedded at model-load time;
 * if the user changes extruder presets after loading, the embedded value is stale.
 * This patch ensures the header always reflects the current extruder preset material types.
 *
 * @return true if the line was found and replaced, false otherwise (file unchanged).
 */
internal fun fixFilamentTypeHeader(gcodePath: String, filamentTypes: List<String>): Boolean {
    if (filamentTypes.isEmpty()) return false
    val file = java.io.File(gcodePath)
    if (!file.exists()) return false
    val replacement = filamentTypes.joinToString(";")
    val tmpFile = java.io.File("$gcodePath.ftype.tmp")
    return try {
        var found = false
        file.bufferedReader().use { reader ->
            tmpFile.bufferedWriter().use { writer ->
                for (line in reader.lineSequence()) {
                    if (!found && line.startsWith("; filament_type = ")) {
                        writer.write("; filament_type = $replacement")
                        found = true
                    } else {
                        writer.write(line)
                    }
                    writer.newLine()
                }
            }
        }
        if (found) {
            java.nio.file.Files.move(
                tmpFile.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        } else {
            tmpFile.delete()
        }
        found
    } catch (e: Exception) {
        tmpFile.delete()
        false
    }
}

