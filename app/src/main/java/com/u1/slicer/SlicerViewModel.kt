package com.u1.slicer

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Debug
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.u1.slicer.bambu.BambuSanitizer
import com.u1.slicer.bambu.NativePlateState
import com.u1.slicer.bambu.ProfileEmbedder
import com.u1.slicer.bambu.ThreeMfInfo
import com.u1.slicer.bambu.ThreeMfParser
import com.u1.slicer.bambu.ThreeMfPlate
import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.FilamentProfile
import com.u1.slicer.data.ModelInfo
import com.u1.slicer.data.OverrideMode
import com.u1.slicer.data.OverrideValue
import com.u1.slicer.data.PerObjectPose
import com.u1.slicer.data.PlateType
import com.u1.slicer.data.SessionState
import com.u1.slicer.data.remapPerObjectMapOnSplit
import com.u1.slicer.ui.ObjectSelection
import com.u1.slicer.data.SessionStateRepository
import com.u1.slicer.data.SettingsBackup
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.data.SliceJob
import com.u1.slicer.data.SliceResult
import com.u1.slicer.data.SlicingOverrides
import com.u1.slicer.data.WipeTowerDepthEstimator
import com.u1.slicer.gcode.ExcludeObjectInfo
import com.u1.slicer.gcode.GcodeParser
import com.u1.slicer.gcode.GcodeThumbnailInjector
import com.u1.slicer.gcode.GcodeValidator
import com.u1.slicer.gcode.LayerToolPauseInjector
import com.u1.slicer.gcode.ParsedGcode
import com.u1.slicer.gcode.buildSuspiciousModelLineContexts
import com.u1.slicer.gcode.parseExcludeObjects
import com.u1.slicer.model.CopyArrangeCalculator
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
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
    internal val nativeLib: NativeLibrary get() = native
    private val diagnostics = DiagnosticsStore(application)
    private val container = (application as U1SlicerApplication).container
    private val settingsRepo = container.settingsRepository
    private val printersRepo = container.printersRepository
    private val processProfilesRepo = container.processProfilesRepository
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

    /**
     * B129 — the G-code preview's layer-range slider position (min..max layer
     * index), hoisted here so it survives leaving the preview to move/rotate
     * the model. `null` means "show the full print"; it is reset to null
     * whenever a new (or cleared) parsed G-code is assigned via
     * [setParsedGcodeWithRangeReset], so a fresh slice always opens at the top.
     */
    private val _previewLayerRange = MutableStateFlow<Pair<Int, Int>?>(null)
    val previewLayerRange: StateFlow<Pair<Int, Int>?> = _previewLayerRange.asStateFlow()

    /** B129 — persist the user's current preview slider position. */
    fun setPreviewLayerRange(minLayer: Int, maxLayer: Int) {
        _previewLayerRange.value = minLayer to maxLayer
    }

    /**
     * B129 — assign [_parsedGcode] and reset the remembered slider range in one
     * step. Used everywhere a genuinely new (or cleared) preview is produced so
     * the slider opens at the full range; navigation away/back does NOT go
     * through here, so the remembered position is preserved.
     */
    private fun setParsedGcodeWithRangeReset(parsed: ParsedGcode?) {
        _parsedGcode.value = parsed
        _previewLayerRange.value = null
    }

    private val _excludeObjects = MutableStateFlow<List<ExcludeObjectInfo>>(emptyList())
    val excludeObjects: StateFlow<List<ExcludeObjectInfo>> = _excludeObjects.asStateFlow()

    // Bambu / multi-plate state
    private val _threeMfInfo = MutableStateFlow<ThreeMfInfo?>(null)
    val threeMfInfo: StateFlow<ThreeMfInfo?> = _threeMfInfo.asStateFlow()
    // Original file-level ThreeMfInfo set on load, never overwritten by plate selections (B81).
    // Used as the stable sourceInfo for mergeThreeMfInfoForPlate so that cross-plate selections
    // always see the correct file-level metadata (e.g. hasPaintData=true for files where only
    // some plates have paint data).
    private var _fileThreeMfInfo: ThreeMfInfo? = null

    /**
     * Phase 2 — cached canonical filament list (full file palette, file-fileIndex
     * indexed). Refreshed on every model load so `meshAlignedFilamentColors` does
     * not re-read the source ZIP on every override edit. Holds the **full** file
     * filament list from `bambuCanonicalList(file)`; plate narrowing happens in
     * the `meshAlignedFilamentColors` flow (Approach C: synthesise mesh-aligned
     * palettes by reordering canonical to match each mesh's compaction).
     *
     * `null` for STL / non-canonical files (the recolor falls back to the slot
     * palette in that case, same as `resolvedFilamentColors`).
     */
    private val _canonicalFilamentList =
        MutableStateFlow<com.u1.slicer.data.CanonicalFilamentList?>(null)
    val canonicalFilamentList: StateFlow<com.u1.slicer.data.CanonicalFilamentList?> =
        _canonicalFilamentList.asStateFlow()

    /**
     * Phase 2 (2026-04-28, post-adversarial-review) — identity tag for
     * the canonical-list cache. Records the absolute path of the source
     * file the cached `_canonicalFilamentList` was derived from. Set
     * synchronously by [beginNewModelLoad] (before any async embed /
     * prepare call could query the cache) and updated alongside every
     * `_canonicalFilamentList.value = ...` write.
     *
     * `getCanonicalFilamentList()` checks this against the current
     * source path; if they don't match the cache is treated as stale
     * and re-derived from the current file. Closes the cross-load leak
     * the reviewer flagged where loading file B after file A could
     * embed/map B using A's canonical list because the async cache
     * refresh hadn't published yet.
     */
    @Volatile
    private var canonicalCacheSourcePath: String? = null

    /**
     * Phase 2 (2026-04-28, post-adversarial-review) — synchronous
     * reset called at the very start of every load entry point
     * ([loadModel], [loadModelFromFile]). Clears all per-file mutable
     * state that would otherwise outlive the previous file:
     *
     *   - `_canonicalFilamentList` (cached for getCanonicalFilamentList)
     *   - `canonicalCacheSourcePath` (the cache identity tag)
     *   - `_filamentOverrides` (per-file-index user overrides)
     *
     * The reset is synchronous so any subsequent code path —
     * including async embed / prepare calls fired by the same load —
     * sees a clean slate when it queries the canonical list. Matches
     * the review's "synchronously clear at the start of every load"
     * recommendation.
     */
    private fun beginNewModelLoad() {
        _canonicalFilamentList.value = null
        canonicalCacheSourcePath = null
        _filamentOverrides.value = emptyMap()
        _objectBoundingBoxes.value = floatArrayOf()
        hasMultipleDistinctObjectsVar = false
        customObjectPositions = null
        _multiObjectPositions.value = null
        additionalModelFiles.clear()
        _pendingAddFile.value?.copiedFile?.delete()
        _pendingAddFile.value = null
        // F66 — drop all per-file F66 state. File-A's selection / per-object
        // pose / split history / per-volume overrides must not bleed into the
        // file-B native model whose object indices are entirely different.
        _selection.value = ObjectSelection()
        _perObjectPoses.value = emptyMap()
        _loadTimePoses.value = emptyMap()
        _perVolumeExtruders.value = emptyMap()
        _splitObjectOps.value = emptyList()
        _splitVolumeOps.value = emptyList()
    }

    /**
     * Reloads [_canonicalFilamentList] from the current model file. The paint-
     * walk in `bambuCanonicalList` walks every `.model` entry, which can be
     * a few seconds on Buzz-Lightyear-sized files (73 MB), so the load runs
     * on the IO dispatcher. The StateFlow updates when the read completes;
     * the recolor effect re-fires automatically when the palette flips
     * non-empty, so a brief paint of slot-fallback colours during the gap
     * resolves to canonical colours seamlessly.
     *
     * Synchronous null clears (e.g. `clearModel()`) take the immediate path
     * so the flow doesn't briefly expose a stale canonical for a freshly-
     * cleared file.
     */
    private fun refreshCanonicalFilamentList() {
        // Prefer the original input file (`rawInputFile`) over the
        // sanitized `_currentModelFile` — BambuSanitizer strips
        // `project_settings.config` from the output, so the sanitized
        // file's canonical comes back null. Falls back to
        // `_currentModelFile` for the rare case where rawInputFile isn't
        // tracked (e.g. recovery / re-embed paths).
        val file = rawInputFile ?: _currentModelFile
        if (file == null) {
            _canonicalFilamentList.value = null
            canonicalCacheSourcePath = null
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val list = try {
                // Try `bambuCanonicalList` first to bypass the extension
                // dispatch in `canonicalListAtLoad` — sanitized fallback
                // files may not have a `.3mf` extension.
                com.u1.slicer.bambu.bambuCanonicalList(file)
                    ?: com.u1.slicer.data.canonicalListAtLoad(file)
            } catch (e: Exception) {
                android.util.Log.w("SlicerVM", "canonicalListAtLoad failed for ${file.name}: ${e.message}")
                null
            }
            // Guard: another file load may have raced ahead while we walked the
            // ZIP. Only publish if the (raw-or-current) file still matches.
            val currentSource = rawInputFile ?: _currentModelFile
            if (currentSource?.absolutePath == file.absolutePath) {
                _canonicalFilamentList.value = list
                // Phase 2 (post-round-2-review F4 tightening) — keep
                // the cache-identity tag in lock-step with the value.
                // Reviewer 2 noted async refresh paths weren't
                // updating it.
                canonicalCacheSourcePath = file.absolutePath
            }
        }
    }

    private val _multiPlatePlates = MutableStateFlow<List<ThreeMfPlate>>(emptyList())
    val multiPlatePlates: StateFlow<List<ThreeMfPlate>> = _multiPlatePlates.asStateFlow()

    private val _showPlateSelector = MutableStateFlow(false)
    val showPlateSelector: StateFlow<Boolean> = _showPlateSelector.asStateFlow()

    // F85: pending "Add to bed" plate selection — set when a multi-plate 3MF is picked
    // via addModelFile; cleared when user picks a plate (confirmAddPlate) or dismisses.
    data class PendingAddFile(val copiedFile: java.io.File, val displayName: String, val plates: List<ThreeMfPlate>)
    private val _pendingAddFile = MutableStateFlow<PendingAddFile?>(null)
    val pendingAddFile: StateFlow<PendingAddFile?> = _pendingAddFile.asStateFlow()

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

    // ---- F66: per-object selection + pose state -----------------------------

    /** Tap-to-select selection. `objectIndex == null` → bed-wide mode (controls
     *  act on the whole scene, current behaviour). When non-null, Edit panel
     *  actions and the existing Rotate/Scale sliders target this single object. */
    private val _selection = MutableStateFlow(ObjectSelection())
    val selection: StateFlow<ObjectSelection> = _selection.asStateFlow()

    /** Per-object current pose. Read by the F77 multi-object renderer to
     *  compose per-instance model matrices, and by the EditPanel to wire the
     *  Rotate/Scale dials when an object is selected. */
    private val _perObjectPoses = MutableStateFlow<Map<Int, PerObjectPose>>(emptyMap())
    val perObjectPoses: StateFlow<Map<Int, PerObjectPose>> = _perObjectPoses.asStateFlow()

    /** Per-object pose snapshot at model-load time. `resetObjectRotation` /
     *  `resetObjectScale` restore from this — so a 3MF that declares its own
     *  rotation/scale keeps that rotation/scale as the Reset target rather
     *  than collapsing to identity. */
    private val _loadTimePoses = MutableStateFlow<Map<Int, PerObjectPose>>(emptyMap())
    val loadTimePoses: StateFlow<Map<Int, PerObjectPose>> = _loadTimePoses.asStateFlow()

    /** Per-volume extruder assignment overrides set via the Parts panel.
     *  Key = `"objIdx:volIdx"`, value = 1-indexed extruder slot.
     *  Empty map = honour each volume's file-declared extruder. */
    private val _perVolumeExtruders = MutableStateFlow<Map<String, Int>>(emptyMap())
    val perVolumeExtruders: StateFlow<Map<String, Int>> = _perVolumeExtruders.asStateFlow()

    /** F89 replay state — load-time-indexed object indices that were split
     *  this session. Persisted in v3 SessionState and replayed on resume. */
    private val _splitObjectOps = MutableStateFlow<List<Int>>(emptyList())
    val splitObjectOps: StateFlow<List<Int>> = _splitObjectOps.asStateFlow()

    private val _splitVolumeOps = MutableStateFlow<List<String>>(emptyList())
    val splitVolumeOps: StateFlow<List<String>> = _splitVolumeOps.asStateFlow()

    // Phase 2.6b — per-filament user overrides from the Prepare screen.
    // Map key = file filament index (0-based, matches CanonicalFilamentList
    // ordering and slicer T-emission). Values:
    //   - color = hex "#RRGGBB" override; null when user hasn't overridden
    //   - materialType = "PLA"/"PETG"/etc.; null when user hasn't overridden
    // Defaults fall back to the file's canonical list (3MF) or printer-loaded
    // data (STL). Overrides will drive slicing in Phase 2.6c.
    data class FilamentOverride(val color: String? = null, val materialType: String? = null)
    private val _filamentOverrides = MutableStateFlow<Map<Int, FilamentOverride>>(emptyMap())
    val filamentOverrides: StateFlow<Map<Int, FilamentOverride>> = _filamentOverrides.asStateFlow()

    fun setFilamentMaterialOverride(fileIndex: Int, materialType: String?) {
        val current = _filamentOverrides.value
        val existing = current[fileIndex] ?: FilamentOverride()
        val next = existing.copy(materialType = materialType)
        _filamentOverrides.value = if (next.color == null && next.materialType == null) {
            current - fileIndex
        } else {
            current + (fileIndex to next)
        }
    }

    fun setFilamentColorOverride(fileIndex: Int, color: String?) {
        val current = _filamentOverrides.value
        val existing = current[fileIndex] ?: FilamentOverride()
        val next = existing.copy(color = color)
        _filamentOverrides.value = if (next.color == null && next.materialType == null) {
            current - fileIndex
        } else {
            current + (fileIndex to next)
        }
    }

    fun clearFilamentOverrides() {
        _filamentOverrides.value = emptyMap()
    }

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

    // Per-object bounding boxes: flat [sizeX0,sizeY0,sizeZ0, sizeX1,...] populated from
    // native.getObjectBoundingBoxes() after each load or addModelFile call.
    private val _objectBoundingBoxes = MutableStateFlow<FloatArray>(floatArrayOf())

    /**
     * F66 — load-time per-object bounding-box sizes [sizeX0, sizeY0, sizeZ0, ...].
     * Captured alongside `_loadTimePoses` in `snapshotLoadTimePoses`. The
     * per-object Scale UX uses this as the "100%" reference so the mm-mode
     * input shows the post-scale dimension relative to a stable baseline,
     * matching the bed-wide `loadTimeSize` parameter on `ScaleSection`.
     */
    private val _loadTimeObjectBoundingBoxes = MutableStateFlow<FloatArray>(floatArrayOf())
    val loadTimeObjectBoundingBoxes: StateFlow<FloatArray> = _loadTimeObjectBoundingBoxes.asStateFlow()
    val objectBoundingBoxes: StateFlow<FloatArray> = _objectBoundingBoxes.asStateFlow()
    // True when multiple distinct objects from different files are on the bed.
    // Switches placement to setObjectPositions instead of setModelInstances at slice time.
    // Exposed as a StateFlow so InlineModelPreview can gate perObjectSizes on it — a single
    // multi-volume 3MF (e.g. Button-for-S-trousers, 40 volumes) has many objectBoundingBoxes
    // but is NOT multi-file, so it must not trigger multiObjectMode in the renderer (B124).
    private val _hasMultipleDistinctObjects = MutableStateFlow(false)
    val hasMultipleDistinctObjects: StateFlow<Boolean> = _hasMultipleDistinctObjects.asStateFlow()
    private var hasMultipleDistinctObjectsVar: Boolean
        get() = _hasMultipleDistinctObjects.value
        set(value) { _hasMultipleDistinctObjects.value = value }

    // Current multi-object positions [x0,y0,x1,y1,...] as a StateFlow so InlineModelPreview
    // can pass them into the rotation LaunchedEffect for setObjectPositions re-application.
    private val _multiObjectPositions = MutableStateFlow<FloatArray?>(null)
    val multiObjectPositions: StateFlow<FloatArray?> = _multiObjectPositions.asStateFlow()

    // Incremented each time a model is added via addModelFile / addModelFromFile, or when
    // multi-object positions change via applyPlacementPositions.  Used as a LaunchedEffect
    // key in InlineModelPreview so the prepare preview mesh re-fetches after an add or drag.
    private val _modelAddVersion = MutableStateFlow(0)
    val modelAddVersion: StateFlow<Int> = _modelAddVersion.asStateFlow()

    // Files added via "Add to bed", paired with their plateIdx (-1 = all plates).
    // Re-used after a 3MF re-embed reload; plateIdx preserved so plate-selected adds
    // reload the same plate instead of all plates.
    private val additionalModelFiles = mutableListOf<Pair<File, Int>>()

    // F89: session persistence — debounced save of Prepare-screen ephemeral state.
    private val sessionStateRepository = SessionStateRepository(getApplication())
    private val sessionSaveFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    // F89: toast events surfaced to MainActivity (Toast.makeText). One-shot strings.
    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toastEvents: SharedFlow<String> = _toastEvents

    // Most recent successful slice result, kept independent of [_state] so the Save /
    // Share path stays robust when [_state] transitions away from SliceComplete during
    // the file-picker round-trip (e.g. plate selector re-fires and triggers
    // [selectPlate] before the user's saveGcodeTo callback runs — the picker has
    // already created the destination file as 0 bytes; without this cache the save
    // would silently early-return and leave it empty). Cleared on clearModel and
    // whenever a new slice starts.
    private var _lastSliceResult: SliceResult? = null

    // F89: resume offer — non-null when a saved session was found on launch and the
    // source file is still on disk. Cleared on Resume (after the load starts) or
    // explicit dismiss; banner UI gates on `state == Idle && offer != null`.
    private val _sessionResumeOffer = MutableStateFlow<SessionResumeOffer?>(null)
    val sessionResumeOffer: StateFlow<SessionResumeOffer?> = _sessionResumeOffer.asStateFlow()

    data class SessionResumeOffer(val modelName: String, val plateId: Int?)

    // F89: most recently completed slice's job id. Captured in startSlicing's
    // success path; persisted in the session blob so resume can restore
    // SliceComplete state and navigate to Preview.
    private var lastSliceJobId: Long? = null

    // F89: handle to the currently-running restoreSession coroutine so any new
    // load (loadModelFromFile, loadModel, addModelFromFile, F61 reopen, etc.)
    // can cancel a stale restore before kicking off its own work. Prevents the
    // race where the user taps a Jobs row during a slow Resume and ends up
    // with a half-loaded VM.
    private var restoreSessionJob: kotlinx.coroutines.Job? = null

    // F89: true while acceptSessionResume's launched coroutine is running its
    // restore (fast-path or full). External load entry points (addModelFromFile,
    // loadJobGcodeForViewer, etc.) check this to avoid self-cancelling the
    // restore from inside its own coroutine.
    @Volatile private var restoreInProgress: Boolean = false

    // F89 silent-load completion signal. Emits true on successful native load,
    // false on error/cancellation. Fires regardless of silent flag so callers
    // can await load completion when _state writes are suppressed.
    private val _silentLoadCompleted = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)

    // F89: true while a silent background restore is in flight (post-fast-path).
    // The Prepare tab uses this to show a "Restoring model…" indicator instead
    // of the empty state, so users don't think Resume is broken and tap "Pick
    // file" mid-restore.
    private val _silentRestoreInProgress = MutableStateFlow(false)
    val silentRestoreInProgress: StateFlow<Boolean> = _silentRestoreInProgress.asStateFlow()

    // F89: navigation events emitted by the ViewModel (e.g. "navigate to Preview
    // after restoring a SliceComplete session"). MainActivity collects and routes.
    private val _navigateEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val navigateEvents: kotlinx.coroutines.flow.SharedFlow<String> = _navigateEvents

    // Tool remap: maps compact T-index (0,1,…) → actual printer slot index (e.g. 2,3 for E3+E4).
    // Null / identity mapping → no post-processing needed.
    private var toolRemapSlots: List<Int>? = null

    // B64: SEMM colour permutation — maps compact T-index → physical extruder slot
    // when the user's colour assignment is a non-identity permutation.
    // Null when no permutation needed (identity, H2C, non-SEMM).
    // Phase 2 (2026-04-28) — Group B legacy slice-time remap flows
    // (`semmColorPermutation`, `_slicerColorOrder`, `_semmColorPermutationFlow`,
    // `_gcodeUsesPhysicalSlots`) are retired. With the canonical-driven
    // contract the slicer emits T-indices in fileIndex space and PrintTimeRemap
    // does slot translation only at Send time, so these slot-aware-slicer
    // flows are dead by construction.

    // B49: cache the Prepare preview MeshData so returning from G-code view is instant.
    // The native side caches the raw mesh, but toMeshData() (normal computation + FloatBuffer
    // allocation) is expensive for large SEMM models (2M tris).  This cache avoids re-conversion.
    // Invalidated on model load, rotation change, or arrangement change.
    // cachedPrepareMeshPath ties the cache to a specific model file so that a plate switch
    // (which changes previewModelPath before invalidation runs) doesn't serve a stale mesh.
    @Volatile var cachedPrepareMesh: com.u1.slicer.viewer.MeshData? = null
    @Volatile var cachedPrepareMeshPath: String? = null

    // B109 review #1+#2 (2026-05-17, v2.2.7): pre-scale (Width, Depth) AABB of the
    // live native preview mesh, with rotation baked in. Fed by InlineModelPreview
    // after each `getPreparePreviewMesh()` returns; consumed by
    // [getPlacementPositions] (auto-center) and [setCopyCount] (bed-warning) so they
    // agree with the renderer's drawn footprint instead of using the over-conservative
    // box-rotation approximation. Cleared in [setModelRotation] / [invalidatePrepareMeshCache]
    // so a stale value never leaks across a rotation change — until the next
    // preview-mesh fetch repopulates it, callers fall back to the box approximation
    // via [CopyArrangeCalculator.effectivePlacementFootprint].
    private val _rotatedMeshSizeXY = MutableStateFlow<Pair<Float, Float>?>(null)
    val rotatedMeshSizeXY: StateFlow<Pair<Float, Float>?> = _rotatedMeshSizeXY.asStateFlow()

    fun setRotatedMeshSize(width: Float, depth: Float) {
        _rotatedMeshSizeXY.value = width to depth
    }

    fun invalidatePrepareMeshCache() {
        cachedPrepareMesh = null
        cachedPrepareMeshPath = null
        _rotatedMeshSizeXY.value = null
        // F66 — bump the modelAddVersion StateFlow so InlineModelPreview's
        // mesh-fetch LaunchedEffect re-keys and re-fetches. The bare cache
        // fields above are plain @Volatile vars that Compose doesn't observe,
        // so callers like setObjectRotation/setObjectScale/autoOrientObject
        // would otherwise mutate native state and never trigger a re-render.
        _modelAddVersion.value++
    }

    /** Reset toolRemapSlots so a fresh load doesn't carry stale slot state forward. */
    private fun resetToolRemapState() {
        toolRemapSlots = null
    }

    // ---- F66: per-object selection + pose actions ---------------------------

    /**
     * Set the tap-to-selected object. `null` returns to bed-wide mode.
     *
     * Same-index re-selection is intentionally idempotent (no toggle). The
     * "tap-same toggles deselect" version of this method made every other tap
     * on a single-object load look like a dropped tap — selection alternated
     * select/deselect with no visible difference on the second tap. Deselect
     * is now exclusively via tap on empty bed (which fires onEmptyTap →
     * deselect), the × button on the Edit panel header, or any path that
     * resets state (file load, plate switch, slice).
     */
    fun selectObject(idx: Int?) {
        _selection.value = if (idx == null) ObjectSelection() else _selection.value.withObject(idx)
    }

    /** Set the active Parts-panel volume within the currently-selected object. */
    fun selectVolume(idx: Int?) {
        _selection.value = _selection.value.withVolume(idx)
    }

    /** Clear selection, returning to bed-wide control mode. */
    fun deselect() {
        _selection.value = ObjectSelection()
    }

    /**
     * F66 — snapshot every object's current pose as its "load-time baseline".
     * Called after a successful load / addModel / split so subsequent Reset
     * operations restore each object to the pose it had immediately after
     * loading (preserving any rotation a 3MF file declared).
     */
    fun snapshotLoadTimePoses() {
        val n = native.nativeGetObjectCount()
        val snap = HashMap<Int, PerObjectPose>(n)
        for (i in 0 until n) {
            val r = native.nativeGetObjectRotation(i)
            val s = native.nativeGetObjectScale(i)
            snap[i] = PerObjectPose(r[0], r[1], r[2], s[0], s[1], s[2])
        }
        _loadTimePoses.value = snap
        _perObjectPoses.value = snap
        // Also snapshot bounding boxes — used as the "100%" mm-mode reference
        // in the per-object Scale UX. Done here (not after every refreshObject
        // …PoseChange) so the reference doesn't drift as the user scales.
        _loadTimeObjectBoundingBoxes.value = runCatching { native.getObjectBoundingBoxes() }
            .getOrDefault(floatArrayOf())
    }

    /**
     * F66 review-2026-05-30 P0 — extend the load-time snapshot to cover newly
     * added objects without clobbering existing baselines or user-applied
     * poses. Called by [doAddFile] / [splitObject] / [splitVolume] so file-B
     * objects (or new split pieces) get a Reset baseline + an mm-mode bbox
     * reference without losing file-A's user-applied rotation/scale.
     *
     * Object indices NOT already in `_loadTimePoses` are treated as new and
     * snapshotted from native. The full bounding-box array is always
     * republished — its size must match `nativeGetObjectCount()`.
     */
    fun extendLoadTimePosesForNewObjects() {
        val n = native.nativeGetObjectCount()
        val existing = _loadTimePoses.value
        if (existing.size >= n) {
            // Bbox might still be stale (e.g. a split changed object count
            // without growing it). Refresh defensively.
            _loadTimeObjectBoundingBoxes.value = runCatching { native.getObjectBoundingBoxes() }
                .getOrDefault(floatArrayOf())
            return
        }
        val merged = HashMap<Int, PerObjectPose>(existing)
        for (i in 0 until n) {
            if (existing.containsKey(i)) continue
            val r = native.nativeGetObjectRotation(i)
            val s = native.nativeGetObjectScale(i)
            merged[i] = PerObjectPose(r[0], r[1], r[2], s[0], s[1], s[2])
        }
        _loadTimePoses.value = merged
        // Preserve any user-applied poses from existing entries; new entries
        // also get a default-equals-load-time pose seeded into _perObjectPoses.
        val mergedPer = HashMap<Int, PerObjectPose>(_perObjectPoses.value)
        for ((idx, pose) in merged) {
            if (!mergedPer.containsKey(idx)) mergedPer[idx] = pose
        }
        _perObjectPoses.value = mergedPer
        _loadTimeObjectBoundingBoxes.value = runCatching { native.getObjectBoundingBoxes() }
            .getOrDefault(floatArrayOf())
    }

    /**
     * F66 — if the freshly-loaded model contains more than one native ModelObject,
     * promote to multi-object mode so the user can tap-select / drag / split each
     * one individually from the moment the file appears on the bed.
     *
     * The previous attempt flipped only the flag, leaving `customObjectPositions`
     * null. The renderer's per-object dispatch requires `objectMeshRanges.size ==
     * instancePositions.size / 2`; with N ranges and 1 default position it fell
     * back to drawing the combined mesh at world origin — the B129 "buttons file
     * no longer in middle of bed" regression. The fix is to read each object's
     * actual file-declared world-space AABB min from native via the new
     * [NativeLibrary.nativeGetObjectWorldAABBMins] (added Step 1 of this round)
     * and use those as `customObjectPositions`. The values are the engine's own,
     * so passing them through is layout-preserving rather than grid-imposing:
     * Button-for-S-trousers' tessellated buttons stay where the file put them,
     * and painted multi-extruder 3MFs (Dragon Scale, etc.) keep their canonical
     * coherent layouts.
     */
    private fun promoteToMultiObjectIfApplicable() {
        val n = native.nativeGetObjectCount()
        if (n <= 1) return
        val worldMins = runCatching { native.nativeGetObjectWorldAABBMins() }
            .getOrDefault(floatArrayOf())
        if (worldMins.size / 2 != n) {
            // Defensive: a JNI mismatch (size != 2*n) is recoverable by leaving
            // the model in single-mesh mode; per-object selection just won't be
            // available until the user splits.
            Log.w("SlicerVM", "promoteToMultiObject: AABB min count ${worldMins.size / 2} != object count $n; skipping promotion")
            return
        }
        val boxes = runCatching { native.getObjectBoundingBoxes() }.getOrDefault(floatArrayOf())
        if (boxes.size / 3 != n) {
            Log.w("SlicerVM", "promoteToMultiObject: bbox count ${boxes.size / 3} != $n; skipping")
            return
        }

        // File-declared positions can sit outside the 270×270 bed (Button-for-
        // S-trousers' raw layout spans far past x=270). Single-object render
        // path masked this by auto-centring the combined mesh in drawModelAt.
        // Per-object render path draws each object at its position verbatim,
        // so an off-bed object would render off-bed. Offset the whole group
        // by a single (dx, dy) so the combined AABB is centred on the bed —
        // preserves relative layout, lands every piece on-bed.
        var combinedMinX = Float.MAX_VALUE; var combinedMinY = Float.MAX_VALUE
        var combinedMaxX = -Float.MAX_VALUE; var combinedMaxY = -Float.MAX_VALUE
        for (i in 0 until n) {
            val px = worldMins[i * 2]
            val py = worldMins[i * 2 + 1]
            val sx = boxes[i * 3]
            val sy = boxes[i * 3 + 1]
            combinedMinX = minOf(combinedMinX, px)
            combinedMinY = minOf(combinedMinY, py)
            combinedMaxX = maxOf(combinedMaxX, px + sx)
            combinedMaxY = maxOf(combinedMaxY, py + sy)
        }
        val combinedW = combinedMaxX - combinedMinX
        val combinedH = combinedMaxY - combinedMinY
        // Target lower-left of the combined AABB so it sits centred on the bed.
        // If combined > 270 in either axis the group genuinely doesn't fit and
        // we clamp the lower-left to 0 — the user can scale or remove pieces.
        val targetMinX = ((270f - combinedW) / 2f).coerceAtLeast(0f)
        val targetMinY = ((270f - combinedH) / 2f).coerceAtLeast(0f)
        val dx = targetMinX - combinedMinX
        val dy = targetMinY - combinedMinY
        val centered = FloatArray(worldMins.size)
        for (i in 0 until n) {
            centered[i * 2]     = worldMins[i * 2]     + dx
            centered[i * 2 + 1] = worldMins[i * 2 + 1] + dy
        }

        // F66 review-2026-05-30 (B124 regression repair from 15a4ba3): do NOT
        // set hasMultipleDistinctObjectsVar here. That flag controls
        // multiObjectMode in the renderer (perObjectSizes != empty → per-object
        // dispatch + drag). For single-file multi-volume loads like
        // Button-for-S-trousers the user expects single-mesh drag — flipping
        // the flag broke B124. The F66 per-object selection (outline pass +
        // selection.objectIndex) still works regardless; this flag is reserved
        // for genuine multi-file scenarios (doAddFile) and explicit splits
        // (splitObject), both of which set it themselves.
        _objectBoundingBoxes.value = boxes
        customObjectPositions = centered
        _multiObjectPositions.value = centered
        // Push to native so the engine, the renderer, and the Kotlin layer
        // all agree on each object's current bed position. The native side
        // computes new instance offsets from these positions; idempotent on
        // a load where the file already places everything on-bed.
        runCatching { native.setObjectPositions(centered) }
        // Bump modelAddVersion so InlineModelPreview's mesh-fetch LaunchedEffect
        // re-keys. Without this the mesh + ranges + positions stay the
        // single-object values that were resolved before this promotion ran.
        _modelAddVersion.value++
    }

    fun setObjectRotation(objIdx: Int, x: Float, y: Float, z: Float) {
        if (!native.nativeSetObjectRotation(objIdx, x, y, z)) return
        _perObjectPoses.update { it + (objIdx to (it[objIdx] ?: PerObjectPose()).copy(
            rotXDeg = x, rotYDeg = y, rotZDeg = z)) }
        _sliceStale.value = true
        refreshObjectGeometryAfterPoseChange()
        invalidatePrepareMeshCache()
    }

    fun setObjectScale(objIdx: Int, sx: Float, sy: Float, sz: Float) {
        if (!native.nativeSetObjectScale(objIdx, sx, sy, sz)) return
        _perObjectPoses.update { it + (objIdx to (it[objIdx] ?: PerObjectPose()).copy(
            scaleX = sx, scaleY = sy, scaleZ = sz)) }
        _sliceStale.value = true
        refreshObjectGeometryAfterPoseChange()
        invalidatePrepareMeshCache()
    }

    /**
     * F66 — after a per-object rotation or scale change in multi-object mode,
     * refresh the bounding-box + per-object position arrays the renderer relies
     * on. Without this, the cache invalidation + mesh refetch produce a new
     * mesh with the scaled vertices baked in, but `splitMeshByObjects` and the
     * renderer's drag clamps both use stale per-object sizes — so the new
     * geometry gets misclassified and the user sees no visible change.
     */
    private fun refreshObjectGeometryAfterPoseChange() {
        if (!hasMultipleDistinctObjectsVar) return
        val boxes = runCatching { native.getObjectBoundingBoxes() }
            .getOrDefault(_objectBoundingBoxes.value)
        _objectBoundingBoxes.value = boxes
        // Also refresh world-AABB mins so splitMeshByObjects can re-classify
        // triangles against the new sizes. The native engine's instance
        // transform has been updated by the set call above, so this read
        // returns the post-transform positions.
        val worldMins = runCatching { native.nativeGetObjectWorldAABBMins() }
            .getOrDefault(floatArrayOf())
        if (worldMins.size / 2 == boxes.size / 3 && worldMins.size >= 2) {
            customObjectPositions = worldMins
            _multiObjectPositions.value = worldMins
        }
    }

    fun resetObjectRotation(objIdx: Int) {
        val baseline = _loadTimePoses.value[objIdx] ?: return
        setObjectRotation(objIdx, baseline.rotXDeg, baseline.rotYDeg, baseline.rotZDeg)
    }

    fun resetObjectScale(objIdx: Int) {
        val baseline = _loadTimePoses.value[objIdx] ?: return
        setObjectScale(objIdx, baseline.scaleX, baseline.scaleY, baseline.scaleZ)
    }

    fun resetAllRotations() {
        _loadTimePoses.value.keys.toList().forEach { resetObjectRotation(it) }
    }

    fun resetAllScales() {
        _loadTimePoses.value.keys.toList().forEach { resetObjectScale(it) }
    }

    /**
     * F66 — split the object at [objIdx] into its connected components.
     * Remaps per-object state to follow the new indices and auto-advances
     * selection to the first new piece (desktop Orca convention).
     *
     * Returns `true` if the object was actually split, `false` otherwise
     * (single-island object — native returned null without mutating).
     */
    fun splitObject(objIdx: Int): Boolean {
        val res = native.nativeSplitObject(objIdx) ?: return false
        val removedIdx = res[0]
        val addedCount = res[1]
        _perObjectPoses.value = remapPerObjectMapOnSplit(_perObjectPoses.value, removedIdx, addedCount)
        _loadTimePoses.value = remapPerObjectMapOnSplit(_loadTimePoses.value, removedIdx, addedCount)
        // Snapshot defaults for the new pieces so Reset works.
        val poses = _loadTimePoses.value.toMutableMap()
        val current = _perObjectPoses.value.toMutableMap()
        for (i in removedIdx until removedIdx + addedCount) {
            val r = native.nativeGetObjectRotation(i)
            val s = native.nativeGetObjectScale(i)
            val baseline = PerObjectPose(r[0], r[1], r[2], s[0], s[1], s[2])
            poses[i] = baseline
            current[i] = baseline
        }
        _loadTimePoses.value = poses
        _perObjectPoses.value = current
        // F66 review-2026-05-30 P1: also refresh the load-time bounding-box
        // reference so the ObjectScale mm-mode display has a non-zero "100%"
        // figure for the new pieces. Without this, ScaleAxisRow's mm column
        // shows blank for any post-split object.
        _loadTimeObjectBoundingBoxes.value = runCatching { native.getObjectBoundingBoxes() }
            .getOrDefault(floatArrayOf())
        _selection.value = _selection.value.onSplit(removedIdx, addedCount).withObject(removedIdx)
        _splitObjectOps.value = _splitObjectOps.value + removedIdx

        // F66: split promotes the model to multi-object mode so the renderer
        // gives each new piece its own draw call + per-object drag + per-object
        // hit-test. Mirrors the F77 addModel layout sequence: pull fresh
        // bounding boxes, generate a grid layout, commit positions to native,
        // remember as the custom placement.
        val newCount = native.nativeGetObjectCount()
        if (newCount > 1) {
            hasMultipleDistinctObjectsVar = true
            val boxes = runCatching { native.getObjectBoundingBoxes() }.getOrDefault(floatArrayOf())
            _objectBoundingBoxes.value = boxes
            if (boxes.size >= 3) {
                val positions = com.u1.slicer.model.CopyArrangeCalculator
                    .buildMultiObjectPositions(boxes)
                native.setObjectPositions(positions)
                customObjectPositions = positions
            }
        }

        _sliceStale.value = true
        invalidatePrepareMeshCache()
        return true
    }

    /**
     * F66 — split one volume within an object into multiple volumes.
     * Returns the new volume count, or `-1` on failure.
     */
    fun splitVolume(objIdx: Int, volIdx: Int): Int {
        val newCount = native.nativeSplitVolume(objIdx, volIdx)
        if (newCount > 0) {
            _splitVolumeOps.value = _splitVolumeOps.value + "$objIdx:$volIdx"
            _sliceStale.value = true
            invalidatePrepareMeshCache()
        }
        return newCount
    }

    /**
     * F66 — auto-orient one object. Wrapped in LongOpService since TBB-parallel
     * `Slic3r::orientation::orient` can take seconds on heavy meshes.
     */
    suspend fun autoOrientObject(objIdx: Int) {
        val ctx = beginLongOp("Auto-orienting object")
        try {
            var didOrient = false
            withContext(Dispatchers.IO) {
                val euler = native.nativeAutoOrientObject(objIdx) ?: return@withContext
                didOrient = true
                val r = native.nativeGetObjectRotation(objIdx)
                _perObjectPoses.update { it + (objIdx to (it[objIdx] ?: PerObjectPose())
                    .copy(rotXDeg = r[0], rotYDeg = r[1], rotZDeg = r[2])) }
            }
            if (didOrient) {
                _sliceStale.value = true
                invalidatePrepareMeshCache()
            } else {
                // F66 review-2026-05-30 P1: surface a toast so the user knows
                // the tap was acknowledged. Native returns null for flat or
                // degenerate meshes the orienter rejects.
                _toastEvents.tryEmit("Auto-orient didn't find a better orientation for this object")
            }
        } finally {
            endLongOp(ctx)
        }
    }

    /**
     * F66 review-2026-05-30 (MapAndPrintScopeRegression follow-on): fire-and-
     * forget wrapper around [autoOrientAll] so the UI doesn't need to allocate
     * a `rememberCoroutineScope` inside the Prepare-tab body. Production held
     * the only `rememberCoroutineScope()` outside the Map & Print dialog gate
     * (sendActionScope, line 521 of MainActivity); any other scope allocation
     * past the gate is at risk if a future refactor moves the gate down.
     */
    fun launchAutoOrientAll() {
        viewModelScope.launch { autoOrientAll() }
    }

    suspend fun autoOrientAll() {
        val ctx = beginLongOp("Auto-orienting all objects")
        try {
            withContext(Dispatchers.IO) {
                native.nativeAutoOrientAll()
                val n = native.nativeGetObjectCount()
                val updated = HashMap(_perObjectPoses.value)
                for (i in 0 until n) {
                    val r = native.nativeGetObjectRotation(i)
                    updated[i] = (updated[i] ?: PerObjectPose())
                        .copy(rotXDeg = r[0], rotYDeg = r[1], rotZDeg = r[2])
                }
                _perObjectPoses.value = updated
            }
            _sliceStale.value = true
            invalidatePrepareMeshCache()
        } finally {
            endLongOp(ctx)
        }
    }

    /** F66 — assign a per-volume extruder slot from the Parts panel. */
    fun setVolumeExtruder(objIdx: Int, volIdx: Int, slot: Int) {
        if (!native.nativeSetVolumeExtruder(objIdx, volIdx, slot)) return
        _perVolumeExtruders.update { it + ("$objIdx:$volIdx" to slot) }
        _sliceStale.value = true
        // F66 — invalidating the prepare cache bumps _modelAddVersion, which
        // is a key on InlineModelPreview's mesh-fetch LaunchedEffect. Without
        // this, the slot change goes to native + state but the rendered mesh
        // still has the previous per-triangle extruder indices, so the user
        // doesn't see the colour change until something else (e.g. selecting
        // a different part) triggers a recomposition.
        invalidatePrepareMeshCache()
    }

    /**
     * F66 — bulk-assign every volume of an object to the same filament slot.
     * Used by the EditPanel's whole-object filament row: "this object is one
     * colour" lets the user paint every part identically with one tap.
     *
     * Single cache invalidation at the end (vs the per-call invalidation in
     * [setVolumeExtruder]) keeps the preview-mesh refetch to one fetch even
     * for objects with many parts.
     */
    fun setObjectFilament(objIdx: Int, slot: Int) {
        val volumeCount = native.nativeGetVolumeCount(objIdx)
        if (volumeCount <= 0) return
        val updates = HashMap<String, Int>(volumeCount)
        var any = false
        for (v in 0 until volumeCount) {
            if (native.nativeSetVolumeExtruder(objIdx, v, slot)) {
                updates["$objIdx:$v"] = slot
                any = true
            }
        }
        if (any) {
            _perVolumeExtruders.update { it + updates }
            _sliceStale.value = true
            invalidatePrepareMeshCache()
        }
    }

    /**
     * F66 — derive the aggregated filament slot for an object. Returns the
     * common slot when every volume shares the same assignment, or `null` when
     * volumes use different slots ("mixed"). The whole-object filament row
     * displays "Mixed" + no highlight in the picker in the mixed case.
     */
    fun aggregatedObjectFilament(objIdx: Int): Int? {
        val volumeCount = native.nativeGetVolumeCount(objIdx)
        if (volumeCount <= 0) return null
        val overrides = _perVolumeExtruders.value
        val first = overrides["$objIdx:0"] ?: native.nativeGetVolumeExtruder(objIdx, 0).coerceAtLeast(1)
        for (v in 1 until volumeCount) {
            val s = overrides["$objIdx:$v"] ?: native.nativeGetVolumeExtruder(objIdx, v).coerceAtLeast(1)
            if (s != first) return null
        }
        return first
    }

    /** F66 — convenience queries for the Edit panel UI. */
    fun objectName(objIdx: Int): String = native.nativeGetObjectName(objIdx) ?: ""
    fun volumeName(objIdx: Int, volIdx: Int): String = native.nativeGetVolumeName(objIdx, volIdx) ?: ""
    fun volumeCount(objIdx: Int): Int = native.nativeGetVolumeCount(objIdx)
    fun volumeExtruder(objIdx: Int, volIdx: Int): Int = native.nativeGetVolumeExtruder(objIdx, volIdx)
    fun isObjectSplittable(objIdx: Int): Boolean = native.nativeIsObjectSplittable(objIdx)
    fun isVolumeSplittable(objIdx: Int, volIdx: Int): Boolean = native.nativeIsVolumeSplittable(objIdx, volIdx)

    // Filament library — StateFlow so .value is accessible synchronously (e.g. for nozzle temp lookup at slice time)
    val filaments = filamentDao.getAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Job history
    val sliceJobs = sliceJobDao.getAll()

    // Extruder slot config (from printer page, used for color mapping dialog)
    // F78: read from active printer record so multi-printer slot edits always reflect the correct printer.
    val extruderPresets: StateFlow<List<ExtruderPreset>> = printersRepo.activePrinter
        .map { it?.extruderPresets ?: com.u1.slicer.data.defaultExtruderPresets() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.u1.slicer.data.defaultExtruderPresets())

    // F87: imported process profiles + currently active selection. The repository owns the
    // list; the ViewModel observes for slice-time consumption + UI state.
    val processProfilesConfig: StateFlow<com.u1.slicer.data.ProcessProfilesConfig> =
        processProfilesRepo.config
            .stateIn(viewModelScope, SharingStarted.Eagerly, com.u1.slicer.data.ProcessProfilesConfig())
    val activeProcessProfile: StateFlow<com.u1.slicer.data.ProcessProfile?> =
        processProfilesRepo.activeProfile
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** F87: import a process profile from a JSON file picked by the user. */
    fun importProcessProfilesFromJson(json: String, fallbackName: String, onResult: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val parsed = try {
                com.u1.slicer.data.ProcessProfileParser.parse(json, fallbackName)
            } catch (e: Exception) {
                _toastEvents.tryEmit("Import failed: ${e.message ?: "Could not parse JSON"}")
                onResult(0)
                return@launch
            }
            if (parsed.isEmpty()) {
                _toastEvents.tryEmit("No process profiles found in file")
                onResult(0)
                return@launch
            }
            parsed.forEach { processProfilesRepo.add(it) }
            _toastEvents.tryEmit("Imported ${parsed.size} process profile${if (parsed.size == 1) "" else "s"}")
            onResult(parsed.size)
        }
    }

    fun renameProcessProfile(id: String, name: String) {
        viewModelScope.launch { processProfilesRepo.rename(id, name) }
    }

    fun deleteProcessProfile(id: String) {
        viewModelScope.launch { processProfilesRepo.delete(id) }
    }

    fun setActiveProcessProfile(id: String?) {
        viewModelScope.launch {
            processProfilesRepo.setActive(id)
            // Mark slice stale so the user re-slices with the new profile.
            _sliceStale.value = true
            if (lastModelInfo != null) profileNeedsReEmbed = true
        }
    }

    /**
     * F87/F91 (2026-05-25): after a model is loaded, mutations to the filament library,
     * active extruder presets, or active process profile must trigger a re-embed before
     * the next slice. Otherwise the slicer would use values frozen at load time and
     * silently ignore subsequent edits (e.g. tweaking flow ratio / max volumetric speed
     * in Filament Library after loading an STL — the original symptom of this bug, per
     * cheeky_b52's "16 mm³/s flow limit not abided by" report 2026-05-25).
     *
     * combine() emits both initial values + every subsequent change. `drop(1)` skips the
     * first combined emission so cold start doesn't race against an initial slice. The
     * `lastModelInfo != null` guard skips the secondary StateIn-loaded emissions that
     * also fire at startup before any model is loaded.
     */
    @Suppress("unused")
    private val _filamentChangesReEmbedTrigger = combine(
        filaments, extruderPresets, activeProcessProfile,
    ) { _, _, _ -> Unit }
        .drop(1)
        .onEach {
            if (lastModelInfo != null) {
                profileNeedsReEmbed = true
                _sliceStale.value = true
            }
        }
        .launchIn(viewModelScope)

    /**
     * Phase 2 §4 Step 7 (visual side) — per-file-filament resolved colours
     * for the 3D preview. For each file filament i, the value is:
     *   1. user override colour (Prepare screen), if set, else
     *   2. file's declared colour (`detectedColors[i]`).
     *
     * Drives the on-screen 3D model preview's recolor palette so the visual
     * agrees with the file's declared filament colours (independent of the
     * user's slot presets). Empty when no model is loaded.
     *
     * Reactive over `_threeMfInfo` (file load) and `_filamentOverrides`
     * (user edits on Prepare). Sourced from `info.detectedColors` (the
     * same source the Prepare chip strip uses) for guaranteed agreement
     * between chip swatches and the 3D model body. STL / non-canonical
     * paths leave `detectedColors` empty; the recolor falls back to the
     * slot palette in that case.
     */
    val resolvedFilamentColors: StateFlow<List<String>> = combine(
        _threeMfInfo,
        _filamentOverrides,
    ) { info, overrides ->
        val base = info?.detectedColors.orEmpty()
        if (base.isEmpty()) return@combine emptyList()
        if (overrides.isEmpty()) return@combine base
        base.mapIndexed { i, color -> overrides[i]?.color ?: color }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * B128 — the per-filament (materialType, nozzleTemp) the Prepare filament
     * list should DISPLAY, computed by the very same
     * [com.u1.slicer.data.resolvePerFilamentTypeAndTemp] the slice path uses
     * (see [buildPerFilamentTypeAndTemp]). Computing both from one function
     * guarantees the chip strip never diverges from the material actually
     * sliced — the divergence that caused B118.
     *
     * Indexed by canonical fileIndex (aligned with `detectedColors` for the
     * declared multi-colour files this drives). For a genuinely declared
     * multi-colour 3MF with an injective colour→slot mapping the resolver
     * surfaces the file's own `filament_type` here, so loading such a file
     * shows its declared materials instead of the user's slot presets.
     *
     * Empty when no canonical list is available (STL / non-canonical / pre-
     * load); the Prepare row then falls back to the slot preset material.
     */
    val displayedFilamentMaterials: StateFlow<List<Pair<String, Int>>> = combine(
        _canonicalFilamentList,
        _filamentOverrides,
        _colorMapping,
        extruderPresets,
        filaments,
    ) { canonical, overrides, mapping, presets, lib ->
        val list = canonical ?: return@combine emptyList()
        val (types, temps) = com.u1.slicer.data.resolvePerFilamentTypeAndTemp(
            canonical = list,
            overrides = overrides.mapValues { (_, ov) -> ov.color to ov.materialType },
            colorMapping = mapping,
            presets = presets,
            filamentLibrary = lib,
        )
        types.zip(temps)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Phase 2 (post-v2.0.0-validation, Bug 1 class sibling fix) —
     * canonical-fileIndex-aligned palette for the **G-code preview body**.
     *
     * The slicer emits `T<canonical-fileIndex>` in the G-code body. The
     * preview renderer queries `palette[T]` to colour each toolpath
     * segment. So the palette must be canonical-fileIndex-aligned:
     * `palette[i]` = canonical fileIdx i's colour (with per-filament
     * Prepare override applied), file-wide.
     *
     * Distinct from [resolvedFilamentColors], which is plate-narrowed for
     * the Slice Summary chip strip and Filament Mapping dialog rows.
     *
     * Bug class: pre-fix the gcode preview consumed
     * [resolvedFilamentColors] which is plate-narrowed (size = plate
     * filament count). For multi-plate Bambu files whose plate filaments
     * don't start at canonical fileIdx 0 (e.g. Dragon plate 1 using
     * canonical fileIdx 1+2 of 13), the palette indices misaligned with
     * the G-code T-values: T1 rendered as filament 2's colour, T2 fell
     * back to slot preset blue. Calicube didn't surface it because its
     * plate uses canonical fileIdx 0+1 (contiguous from 0) — the
     * plate-narrowed palette accidentally aligned with canonical
     * fileIndex.
     *
     * Empty when no canonical list is available — caller falls back to
     * the legacy 4-slot palette in [normalizeGcodePreviewColors].
     */
    val canonicalFilamentColors: StateFlow<List<String>> = combine(
        _canonicalFilamentList,
        _filamentOverrides,
    ) { canonical, overrides ->
        val list = canonical ?: return@combine emptyList()
        if (overrides.isEmpty()) list.filaments.map { it.color }
        else list.filaments.map { entry ->
            overrides[entry.fileIndex]?.color ?: entry.color
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Phase 2 (Approach C) — palette aligned to the **mesh's** extruder-index
     * space, NOT the file's. Used by the 3D Prepare preview's recolor path so
     * the displayed colours always agree with what the mesh's `extruderIndices`
     * (or layer-tool `extruderBambu`) actually addresses.
     *
     * Three regressions this flow resolves:
     *
     *   1. **Multi-plate Bambu paint** (e.g. Buzz plate 9, file filaments 8 + 10).
     *      Native skips compaction when MMU paint data is present; Kotlin's
     *      [com.u1.slicer.viewer.NativePreviewMesh.compactExtruderIndices]
     *      compacts to 0..N-1 in sorted-unique order. The `info.detectedColors`
     *      list for paint plates is **file-wide** (the slicer's
     *      multi_material_segmentation_by_painting needs the full palette), so
     *      indexing into it with the mesh's compact 0,1 returns the wrong
     *      filaments (file's #1 + #2 instead of #8 + #10).
     *      Fix: derive mesh-aligned palette from
     *      `info.usedExtruderIndices.sorted()` over the full canonical list.
     *
     *   2. **Layer-tool / Hueforge** (e.g. flippy plate 4). The mesh has no
     *      per-vertex colour; the recolor goes via
     *      [com.u1.slicer.viewer.MeshData.recolorByZBands] indexed by
     *      `extruderBambu - 1` (1-based file filament index). `detectedColors`
     *      gets narrowed by `mergeThreeMfInfoForPlate` for layer-tool plates,
     *      which collapses the palette and forces every Z-band to the same
     *      colour.
     *      Fix: use the **full** canonical filament list so palette[0..N-1]
     *      matches `extruderBambu - 1` exactly.
     *
     *   3. **Per-object multi-extruder** (e.g. Calicube, S-Buttons plate 1).
     *      Native compacts mesh extruderIndices to 0..N-1 in sorted order of
     *      the file-filament indices used. When the file uses filaments
     *      1..N consecutively (which is the common case), the compact
     *      identity matches a full-canonical palette anyway. The flow handles
     *      this case via the same sorted-used-index path, falling back to the
     *      full canonical list when `usedExtruderIndices` is unavailable.
     *
     * Indexing contract:
     *   - When [layerToolOnly] is `true` → `palette[i]` corresponds to file
     *     filament `(i+1)` (1-based extruderBambu indexing).
     *   - When [layerToolOnly] is `false` → `palette[i]` corresponds to the
     *     i-th distinct file-filament-index used on the current plate, in
     *     sorted-ascending order (matches `compactExtruderIndices`).
     *
     * Empty when no canonical list is available (STL / non-canonical paths);
     * callers fall back to the slot palette in that case.
     */
    /**
     * Phase 2 §4 (2026-04-27 architectural fix) — canonical-aligned palette
     * for the 3D preview's mesh recolor. Sourced from
     * [_canonicalFilamentList] (file-wide, indexed by fileIndex) so the
     * mesh's per-triangle `extruderIndices` (raw fileIndices) and the
     * Z-band recolor's `extruderBambu - 1` lookup both address the right
     * canonical entry directly.
     *
     * Distinct from [resolvedFilamentColors] (which is plate-narrowed for
     * the chip strip) — the recolor needs the full file palette so a plate
     * using filaments 10 + 11 finds the right colours at indices 9 + 10
     * even though the chip strip only displays 2 entries.
     *
     * Per-filament user overrides (from the Prepare screen) are applied at
     * the matching fileIndex.
     *
     * Empty when no canonical list is available (STL / non-canonical paths
     * / pre-load); the recolor consumer falls back to
     * [resolvedFilamentColors] (slot palette) in that case.
     */
    val meshAlignedFilamentColors: StateFlow<List<String>> = combine(
        _canonicalFilamentList,
        _filamentOverrides,
        _threeMfInfo,
        _layerToolOnly,
    ) { canonical, overrides, mfInfo, layerTool ->
        val list = canonical ?: return@combine emptyList()
        val fullPalette = if (overrides.isEmpty()) list.filaments.map { it.color }
        else list.filaments.map { entry ->
            overrides[entry.fileIndex]?.color ?: entry.color
        }

        // Phase 2 (2026-04-28, post-adversarial-review) — sparse non-MMU
        // mesh palette alignment.
        //
        // The native preview mesh has two compaction shapes:
        //   - MMU (paint segmentation): native B46 SKIPS compaction so
        //     `extruder_indices[tri]` carries the raw paint-state value
        //     (1..N). Kotlin's recolor logic indexes `palette[state-1]`.
        //     Palette must be the full canonical list. This applies to
        //     hasPaintData=true and to layerToolOnly (Z-band recolor
        //     also indexes palette by 1-based extruderBambu).
        //   - non-MMU (per-object extruder): native compacts via
        //     `compactPreviewIndices(out)` so `extruder_indices[tri]`
        //     is dense 0..N-1 keyed by sorted-ascending source extruder.
        //     If a sparse plate uses file filaments 3 + 4, mesh indices
        //     are {0, 1} but the canonical palette indices 0 + 1 still
        //     refer to file filaments 1 + 2. Pre-fix the palette was
        //     full canonical, painting filaments 3 + 4 with filaments
        //     1 + 2's colours. The fix: reorder the canonical palette
        //     to the same compaction order as the mesh.
        val hasPaint = mfInfo?.hasPaintData == true
        if (hasPaint || layerTool) {
            return@combine fullPalette
        }

        // Non-MMU path — derive the source-extruder compaction order.
        //
        // Phase 2 (post-round-3-review, Reviewer 3 NEW-CONCERN) —
        // source from `usedExtruderIndices` (plate-narrowed by
        // `buildThreeMfInfoFromNative`), NOT from
        // `objectExtruderMap`/`objectPartExtruders` directly. The
        // round-2 fix unioned `objectPartExtruders` to cover compound
        // objects, but `objectPartExtruders` is the *file-wide*
        // pre-Phase-1 map that `buildThreeMfInfoFromNative` does NOT
        // narrow to the active plate. On multi-plate files that
        // leaks other plates' volume extruders into the compaction
        // order, expanding the palette beyond the plate's actual
        // canonical-fileIndex coverage.
        //
        // `usedExtruderIndices` carries both per-object AND per-
        // volume extruders (it's "actually assigned to objects/
        // volumes in model config" per its docstring), and IS plate-
        // narrowed by the native-first plate-state read. One field,
        // covers compound objects, plate-correct.
        val sourceExtruders = mfInfo?.usedExtruderIndices
            ?.filter { it > 0 }
            ?.sorted()
            ?: return@combine fullPalette
        if (sourceExtruders.isEmpty()) return@combine fullPalette
        sourceExtruders.map { ext ->
            fullPalette.getOrNull(ext - 1).orEmpty()
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Slicing overrides (USE_FILE / ORCA_DEFAULT / OVERRIDE per setting)
    val slicingOverrides: StateFlow<SlicingOverrides> = settingsRepo.slicingOverrides
        .stateIn(viewModelScope, SharingStarted.Eagerly, SlicingOverrides())

    // Build plate type — determines bed temp preset per filament material
    val plateType: StateFlow<PlateType> = settingsRepo.plateType
        .stateIn(viewModelScope, SharingStarted.Eagerly, PlateType.DEFAULT)

    // MakerWorld cookies (for authenticated URL downloads)
    val makerWorldCookies: StateFlow<String> = settingsRepo.makerWorldCookies
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun saveMakerWorldCookies(cookies: String) {
        viewModelScope.launch(Dispatchers.IO) { settingsRepo.saveMakerWorldCookies(cookies) }
    }

    // AI Paint provider + API key
    val aiPaintProvider: StateFlow<String> = settingsRepo.aiPaintProvider
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.u1.slicer.aipaint.AiPaintProvider.DEFAULT.name)

    val aiPaintApiKey: StateFlow<String> = settingsRepo.aiPaintApiKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** Per-provider API key flow. Each provider's key is stored independently so switching
     *  providers in Settings doesn't leak / overwrite a different provider's key. */
    fun aiPaintApiKeyFor(provider: String): Flow<String> = settingsRepo.aiPaintApiKeyFor(provider)

    fun saveAiPaintSettings(provider: String, apiKey: String) {
        viewModelScope.launch(Dispatchers.IO) { settingsRepo.saveAiPaintSettings(provider, apiKey) }
    }

    fun saveAiPaintProvider(provider: String) {
        viewModelScope.launch(Dispatchers.IO) { settingsRepo.saveAiPaintProvider(provider) }
    }

    fun saveAiPaintKey(provider: String, apiKey: String) {
        viewModelScope.launch(Dispatchers.IO) { settingsRepo.saveAiPaintKey(provider, apiKey) }
    }

    /** F54: AI naming toggle (experimental). Off by default. */
    val aiNamingEnabled: StateFlow<Boolean> = settingsRepo.aiNamingEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun saveAiNamingEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { settingsRepo.saveAiNamingEnabled(enabled) }
    }

    /** F54 fix38.4: change one extruder slot's filament colour, persisting via the extruder
     *  presets DataStore. Used by Smart Paint's slot picker so editing a slot colour actually
     *  propagates back to the printer-side filament list (and shows up everywhere — Prepare
     *  filaments row, Map dialog, recolour preview). */
    fun setSlotColor(slotIndex: Int, hex: String) {
        if (slotIndex !in 0..3) return
        val cleaned = if (hex.startsWith("#")) hex else "#$hex"
        viewModelScope.launch(Dispatchers.IO) {
            // F78: write through PrintersRepository so the active printer's preset record is updated.
            val cfg = printersRepo.config.first() ?: return@launch
            val active = cfg.active
            val existing = active.extruderPresets.firstOrNull { it.index == slotIndex }
            val updatedPreset = existing?.copy(color = cleaned)
                ?: com.u1.slicer.data.ExtruderPreset(index = slotIndex, color = cleaned)
            val updatedPresets = (active.extruderPresets.filterNot { it.index == slotIndex } + updatedPreset)
                .sortedBy { it.index }
            printersRepo.update(active.copy(extruderPresets = updatedPresets))
        }
    }

    // Track the current working file (may be sanitized copy)
    private var _currentModelFile: File? = null
    private var currentModelFile: File?
        get() = _currentModelFile
        set(value) {
            _currentModelFile = value
            // Phase 2 — refresh the cached canonical list so the mesh-aligned
            // palette flow can react without re-reading the file on every
            // override edit. Cheap (one ZIP read per file change).
            refreshCanonicalFilamentList()
        }
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
    /**
     * Phase 2 (post-v2.0.0-validation): exposed (was private) so the
     * Send dialog flow can plate-narrow the canonical filament list it
     * shows the user. Stays write-restricted to internal callers via
     * the standard `private set` doesn't apply across files; the field
     * is logically owned by [selectPlate] and read-only outside.
     */
    internal var recoveryPlateId: Int = -1
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
     * Phase 2 — produces the canonical filament list for the currently
     * loaded file. Computed on demand from `currentModelFile` (and, for
     * STL files, the user's selected extruder preset).
     *
     * Used by the Filament mapping dialog at Send time. Returns `null`
     * when no model is loaded or the file is unrecognised.
     */
    fun getCanonicalFilamentList(): com.u1.slicer.data.CanonicalFilamentList? {
        // Phase 2 (2026-04-28, post-adversarial-review) — verify the
        // cached canonical belongs to the current source file before
        // returning it. Pre-fix the cache was returned blindly; loading
        // file B after file A could embed B using A's canonical because
        // the async refresh hadn't published yet.
        val currentSource = (rawInputFile ?: _multiPlateSourceFile ?: _currentModelFile)?.absolutePath
        val cached = _canonicalFilamentList.value
        if (cached != null && canonicalCacheSourcePath == currentSource && currentSource != null) {
            return cached
        }
        // Prefer the ORIGINAL user input file (`rawInputFile`) — its
        // `project_settings.config` is intact. The sanitized
        // `_multiPlateSourceFile` has no `project_settings.config` (Bambu
        // sanitizer strips it), and the embedded plate file has it
        // rewritten to the slot palette. Try `bambuCanonicalList`
        // directly to bypass `canonicalListAtLoad`'s extension check.
        val sources = listOfNotNull(rawInputFile, _multiPlateSourceFile, _currentModelFile)
        for (source in sources) {
            com.u1.slicer.bambu.bambuCanonicalList(source)?.let {
                _canonicalFilamentList.value = it
                canonicalCacheSourcePath = source.absolutePath
                return it
            }
            com.u1.slicer.data.canonicalListAtLoad(source)?.let {
                _canonicalFilamentList.value = it
                canonicalCacheSourcePath = source.absolutePath
                return it
            }
        }
        // STL fallback: synthesise a single-entry list from the user's
        // selected extruder preset.
        val slot = _selectedExtruder.value
        val preset = extruderPresets.value.firstOrNull { it.index == slot }
            ?: extruderPresets.value.firstOrNull()
            ?: return null
        return com.u1.slicer.data.stlCanonicalList(
            presetColor = preset.color,
            presetMaterialType = preset.materialType,
        )
    }

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
        wireSessionPersistence()
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

        // Phase 2.7 — when the user edits a filament override on Prepare,
        // refresh the 3D preview palette so the change is visible
        // immediately. Also marks the slice stale so the user knows to
        // re-slice (overrides drive nozzle temps and embed colour).
        //
        // Phase 2 (2026-04-28) cross-cutting agent finding: also flip
        // `profileNeedsReEmbed` so the next slice re-embeds the profile
        // with current overrides. Without this, single-colour STL/3MF
        // files (extruderCount=1, toolRemapSlots=null) skip the re-embed
        // gate at line ~2566 entirely and slice with the stale embedded
        // profile from initial load — the user's PETG override is silently
        // dropped at slice time. Same cascade family as the
        // nozzle_temperature bug fixed in c31ca49/1e95c7d.
        viewModelScope.launch {
            _filamentOverrides.drop(1).collect {
                refreshMappedPreviewColors(extruderPresets.value)
                _sliceStale.value = true
                if (lastModelInfo != null) {
                    profileNeedsReEmbed = true
                }
            }
        }

        viewModelScope.launch {
            var prevState: SlicerState = SlicerState.Idle
            state.collect { newState ->
                val ctx = getApplication<android.app.Application>()
                // Mirror the latest successful SliceResult into the save-path cache so it
                // survives a later state transition.
                if (newState is SlicerState.SliceComplete) {
                    _lastSliceResult = newState.result
                }
                // When state transitions AWAY from SliceComplete, the on-screen Save
                // button vanishes, but the parsed-G-code / G-code-preview StateFlows
                // would otherwise keep showing the previous slice — tricking the user
                // into thinking a saveable slice still exists. Clear them so the UI
                // matches the underlying state.
                if (prevState is SlicerState.SliceComplete && newState !is SlicerState.SliceComplete) {
                    setParsedGcodeWithRangeReset(null)
                    _gcodePreview.value = ""
                }
                when {
                    prevState is SlicerState.Loading && newState is SlicerState.ModelLoaded -> {
                        val filename = (newState as SlicerState.ModelLoaded).info.filename
                        // F81: ModelLoaded fires only for large files (>= 5 MB). Anything
                        // smaller loads fast enough that a notification is noise.
                        val fileBytes = currentModelFile?.length() ?: 0L
                        if (fileBytes >= LARGE_FILE_NOTIFY_BYTES) {
                            AppEventNotifier.notify(ctx, AppEventNotifier.Event.ModelLoaded(filename))
                        }
                        // F66 — snapshot per-object rotation+scale as the Reset baseline
                        // for every object now in the model. Without this, the Reset
                        // rotation / scale buttons on the Edit panel silently no-op (the
                        // baseline map stays empty) on every fresh load.
                        snapshotLoadTimePoses()
                        // F66 review-2026-05-30: promote-on-load is DISABLED. Earlier
                        // commits (15a4ba3) tried to make multi-volume Bambu files like
                        // Button-for-S-trousers individually selectable from the moment
                        // they hit the bed. That broke the renderer two ways:
                        //   (a) hasMultipleDistinctObjectsVar=true → multiObjectMode=true
                        //       on a single-volume cluster → drag had no visual effect
                        //       (B124 regression).
                        //   (b) hasMultipleDistinctObjectsVar=false + N customObjectPositions
                        //       (the half-fix) → renderer's per-instance loop drew the
                        //       combined mesh N times = "way too many sets of buttons"
                        //       on Pixel 9.
                        // Per-object selection (outline + selection.objectIndex) still
                        // works via objectMeshRanges produced when the Composable runs
                        // splitMeshByObjects post-load. Per-object pose/drag editing
                        // requires the user to tap "Split to Objects" first — explicit
                        // > implicit. doAddFile (multi-file) and splitObject (user
                        // action) still set hasMultipleDistinctObjectsVar themselves.
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

    /**
     * F81 (GitHub #120): called from the InlineModelPreview `onMeshCached`
     * callback once the Prepare preview mesh is built. Fires a background
     * notification when the mesh is large enough that the build took time.
     */
    fun onPreparePreviewReady(triangleCount: Int) {
        if (triangleCount < PREVIEW_NOTIFY_TRIANGLE_THRESHOLD) return
        val filename = currentModelName.ifEmpty { currentModelFile?.name ?: "model" }
        AppEventNotifier.notify(
            getApplication<android.app.Application>(),
            AppEventNotifier.Event.PreparePreviewReady(filename)
        )
    }

    private fun refreshMappedPreviewColors(presets: List<ExtruderPreset>) {
        val mapping = _colorMapping.value
        if (mapping.isNullOrEmpty()) return
        val usedSlots = mapping.distinct().sorted()
        // Phase 2 §4 Step 4 — overlay per-filament colour overrides onto
        // the slot palette without round-tripping through the slot
        // presets. For each slot, find the first file filament that maps
        // to it; if that filament has a colour override, the slot's
        // preview colour reflects it. This preserves today's preview
        // semantics; Step 6 (display palette N-indexed) will replace
        // this slot-level palette with a canonical-list-driven one.
        val overrides = _filamentOverrides.value
        val effectivePresets = if (overrides.isEmpty()) presets else presets.map { preset ->
            val firstFileIdx = mapping.indexOf(preset.index)
            val ov = if (firstFileIdx >= 0) overrides[firstFileIdx] else null
            if (ov?.color == null) preset else preset.copy(color = ov.color)
        }
        val refreshed = buildPreviewSlotColors(effectivePresets, usedSlots)
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
                Log.i("SlicerVM", "MakerWorld cookies: length=${cookies.length}")

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
                    if (cookies.isNotBlank()) header("Cookie", cookies)
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
        restoreSessionJob?.cancel()
        if (!NativeLibrary.isLoaded) {
            _state.value = SlicerState.Error("Native slicer library not available on this device (arm64 required)")
            return
        }
        invalidatePrepareMeshCache()
        // Phase 2 (2026-04-28) — same synchronous reset as
        // `loadModelFromFile`. The adversarial review found this picker
        // path missing the override-clear; without it, file A's
        // per-filament overrides could silently apply to file B.
        beginNewModelLoad()
        viewModelScope.launch(Dispatchers.IO) {
            val longOpCtx = beginLongOp("Loading model")
            try {
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

                // B104: same as loadModelFromFile — capture first plate ID for single-plate
                // Bambu files so re-embed at slice time applies filterModelToPlate.
                var firstBambuPlateId: Int? = null

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
                    if (prepared.origInfo.isBambu && prepared.mergedInfo.plates.isNotEmpty()) {
                        firstBambuPlateId = prepared.mergedInfo.plates.first().plateId
                    }

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
                firstBambuPlateId?.let { recoveryPlateId = it }
                markSessionDirty()
            } catch (e: Throwable) {
                NativeLibrary.previewMutex.withLock { native.clearModel() }
                _state.value = SlicerState.Error("Error: ${e.message}")
            }
            } finally {
                endLongOp(longOpCtx)
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

    /**
     * F77 (GitHub #109): load multiple STL files onto a single plate. Combines
     * the inputs into one binary STL via [com.u1.slicer.model.MultiStlCombiner]
     * and dispatches to the normal single-file load path so Prepare/Preview/
     * Slice see one model object.
     *
     * Constraints (validated before combine):
     *  - All files must share a supported extension. Mixed STL+3MF is rejected
     *    because formats carry different scene semantics; per the issue body
     *    Kevin wants both, but each batch must be homogeneous.
     *  - 3MF multi-file load is not yet supported by the combiner. Selecting
     *    multiple 3MFs surfaces a clear error so the user falls back to
     *    loading one at a time.
     */
    fun loadMultipleModels(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (uris.size == 1) {
            loadModel(uris.first())
            return
        }
        if (!NativeLibrary.isLoaded) {
            _state.value = SlicerState.Error("Native slicer library not available on this device (arm64 required)")
            return
        }
        beginNewModelLoad()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val workspaceDir = transientWorkspaceDir()
                val cleared = UpgradeDetector.clearIntermediateCache(workspaceDir)
                if (cleared > 0) Log.i("SlicerVM", "F77: cleared $cleared intermediate cache files before multi-file load")

                _state.value = SlicerState.Loading("Loading ${uris.size} files…")

                val names = uris.map { uri ->
                    normalizeIncomingFilename(getDisplayName(context, uri) ?: "model")
                }
                val extensions = names.map { it.substringAfterLast('.', "").lowercase() }
                val firstExt = extensions.first()
                if (extensions.any { it != firstExt }) {
                    _state.value = SlicerState.Error(
                        "Multi-file load requires every file to be the same type. " +
                            "Selected mix: ${extensions.distinct().joinToString(", ")}"
                    )
                    return@launch
                }
                if (firstExt == "3mf") {
                    _state.value = SlicerState.Error(
                        "Multi-file 3MF load is coming in a follow-up. For now load one 3MF at a time."
                    )
                    return@launch
                }
                if (firstExt != "stl") {
                    _state.value = SlicerState.Error(
                        "Multi-file load only supports STL files (got .$firstExt)"
                    )
                    return@launch
                }

                val copiedFiles = mutableListOf<File>()
                for ((index, uri) in uris.withIndex()) {
                    val inputStream = context.contentResolver.openInputStream(uri) ?: continue
                    val target = File(workspaceDir, "multi_${index}_${names[index]}")
                    inputStream.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    copiedFiles += target
                }
                if (copiedFiles.isEmpty()) {
                    _state.value = SlicerState.Error("Could not read any of the selected files")
                    return@launch
                }

                val combinedName = "combined_${System.currentTimeMillis()}.stl"
                val combinedFile = File(workspaceDir, combinedName)
                val result = com.u1.slicer.model.MultiStlCombiner.combine(copiedFiles, combinedFile)
                if (result == null) {
                    _state.value = SlicerState.Error(
                        "None of the selected STL files could be placed on the 270 mm bed."
                    )
                    return@launch
                }
                val warnings = buildList {
                    if (result.failed.isNotEmpty()) {
                        add("${result.failed.size} file(s) failed to parse")
                    }
                    if (result.oversize.isNotEmpty()) {
                        add("${result.oversize.size} file(s) exceeded bed bounds and were skipped")
                    }
                }
                if (warnings.isNotEmpty()) {
                    Log.w("SlicerVM", "F77 multi-STL warnings: ${warnings.joinToString("; ")}")
                }
                Log.i("SlicerVM", "F77: combined ${result.placedParts.size} STL files into $combinedName (${result.totalTriangles} triangles)")
                // Hand off to the normal single-file path; from here on the
                // combined STL is just another model.
                withContext(Dispatchers.Main) { loadModelFromFile(combinedFile) }
            } catch (e: Exception) {
                Log.e("SlicerVM", "F77 multi-file load failed", e)
                _state.value = SlicerState.Error("Multi-file load failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Add a file to the currently loaded model, placing both objects independently on the bed.
     * Requires a model to already be loaded. The primary file's embedded config is preserved.
     */
    fun addModelFile(uri: Uri) {
        if (!NativeLibrary.isLoaded) {
            _state.value = SlicerState.Error("Native slicer library not available on this device (arm64 required)")
            return
        }
        if (lastModelInfo == null) {
            _state.value = SlicerState.Error("Load a model first before adding files to the bed")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val displayName = normalizeIncomingFilename(getDisplayName(context, uri) ?: "model.stl")
                _state.value = SlicerState.Loading("Adding $displayName…")

                val workspaceDir = transientWorkspaceDir()
                val copiedFile = File(workspaceDir, "added_${System.currentTimeMillis()}_$displayName")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    copiedFile.outputStream().use { output -> input.copyTo(output) }
                } ?: run {
                    _state.value = SlicerState.Error("Could not read $displayName")
                    return@launch
                }

                // F85: multi-plate 3MF — show plate selector before adding.
                if (displayName.endsWith(".3mf", ignoreCase = true)) {
                    val info3mf = runCatching { ThreeMfParser.parse(copiedFile) }.getOrNull()
                    if (info3mf != null && info3mf.plates.size > 1) {
                        _pendingAddFile.value = PendingAddFile(copiedFile, displayName, info3mf.plates)
                        withContext(Dispatchers.Main) {
                            val info = lastModelInfo
                            if (info != null) _state.value = SlicerState.ModelLoaded(info)
                        }
                        return@launch  // confirmAddPlate() continues from here
                    }
                }

                doAddFile(copiedFile, displayName, plateIdx = -1)
            } catch (e: Exception) {
                Log.e("SlicerVM", "addModelFile failed", e)
                _state.value = SlicerState.Error("Failed to add file: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun addModelFromFile(file: File) {
        if (!restoreInProgress) restoreSessionJob?.cancel()
        if (!NativeLibrary.isLoaded) {
            _state.value = SlicerState.Error("Native slicer library not available on this device (arm64 required)")
            return
        }
        if (lastModelInfo == null) {
            _state.value = SlicerState.Error("Load a model first before adding files to the bed")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val longOpCtx = beginLongOp("Adding model")
            try {
            try {
                val displayName = normalizeIncomingFilename(file.name)
                _state.value = SlicerState.Loading("Adding $displayName…")
                doAddFile(file, displayName, plateIdx = -1)
            } catch (e: Exception) {
                Log.e("SlicerVM", "addModelFromFile failed", e)
                _state.value = SlicerState.Error("Failed to add file: ${e.message ?: e.javaClass.simpleName}")
            }
            } finally {
                endLongOp(longOpCtx)
            }
        }
    }

    /** Debug/test: add a specific plate of a 3MF without going through the plate-selector UI. */
    fun addModelFromFileForPlate(file: File, plateIdx: Int) {
        if (!restoreInProgress) restoreSessionJob?.cancel()
        if (!NativeLibrary.isLoaded) {
            _state.value = SlicerState.Error("Native slicer library not available on this device (arm64 required)")
            return
        }
        if (lastModelInfo == null) {
            _state.value = SlicerState.Error("Load a model first before adding files to the bed")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val longOpCtx = beginLongOp("Adding model")
            try {
            try {
                val displayName = normalizeIncomingFilename(file.name)
                _state.value = SlicerState.Loading("Adding $displayName (plate ${plateIdx + 1})…")
                doAddFile(file, displayName, plateIdx = plateIdx)
            } catch (e: Exception) {
                Log.e("SlicerVM", "addModelFromFileForPlate failed", e)
                _state.value = SlicerState.Error("Failed to add file: ${e.message ?: e.javaClass.simpleName}")
            }
            } finally {
                endLongOp(longOpCtx)
            }
        }
    }

    /**
     * F85: called when the user confirms a plate from the add-plate selector dialog.
     * plateId is 1-based (from ThreeMfPlate.plateId); converted to 0-based plateIdx for JNI.
     */
    fun confirmAddPlate(plateId: Int) {
        val pending = _pendingAddFile.value ?: return
        _pendingAddFile.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val longOpCtx = beginLongOp("Adding model")
            try {
            try {
                _state.value = SlicerState.Loading("Adding ${pending.displayName}…")
                doAddFile(pending.copiedFile, pending.displayName, plateIdx = plateId - 1)
            } catch (e: Exception) {
                Log.e("SlicerVM", "confirmAddPlate failed", e)
                _state.value = SlicerState.Error("Failed to add file: ${e.message ?: e.javaClass.simpleName}")
            }
            } finally {
                endLongOp(longOpCtx)
            }
        }
    }

    /** F85: dismiss the add-plate selector without adding anything. */
    fun cancelPendingAdd() {
        _pendingAddFile.value?.copiedFile?.delete()
        _pendingAddFile.value = null
    }

    /**
     * Shared implementation for addModelFile / addModelFromFile / confirmAddPlate.
     * Must be called from an IO coroutine. plateIdx = -1 means all plates (addModel);
     * plateIdx >= 0 means that specific plate (addModelForPlate).
     */
    private suspend fun doAddFile(file: File, displayName: String, plateIdx: Int) {
        val existingPos = if (hasMultipleDistinctObjectsVar) {
            customObjectPositions?.copyOf()
        } else {
            getPlacementPositions().let { p -> if (p.size >= 2) floatArrayOf(p[0], p[1]) else null }
        }
        var addOk = false
        NativeLibrary.previewMutex.withLock {
            val ok = if (plateIdx >= 0) native.addModelForPlate(file.absolutePath, plateIdx)
                     else native.addModel(file.absolutePath)
            if (!ok) {
                _state.value = SlicerState.Error("Failed to add $displayName to the bed")
                return@withLock
            }

            addOk = true
            additionalModelFiles.add(file to plateIdx)
            hasMultipleDistinctObjectsVar = true
            val boxes = runCatching { native.getObjectBoundingBoxes() }.getOrDefault(floatArrayOf())
            _objectBoundingBoxes.value = boxes

            val positions = if (existingPos != null) {
                com.u1.slicer.model.CopyArrangeCalculator.placeAdditionalObject(existingPos, boxes)
            } else {
                com.u1.slicer.model.CopyArrangeCalculator.buildMultiObjectPositions(boxes)
            }
            native.setObjectPositions(positions)
            customObjectPositions = positions

            // Recompute wipe tower position using all object footprints so the
            // tower doesn't overlap any added object. Uses the per-object overload
            // so objects of different sizes each contribute their real footprint.
            if (customWipeTowerPos != null) {
                val tw = resolveWipeTowerWidth(_config.value, slicingOverrides.value)
                val td = resolveWipeTowerDepth(lastModelInfo?.sizeZ ?: 0f, slicingOverrides.value)
                customWipeTowerPos = com.u1.slicer.model.CopyArrangeCalculator
                    .computeWipeTowerPositionForObjects(positions, boxes, towerWidth = tw, towerDepth = td)
            }

            invalidatePrepareMeshCache()
            _sliceStale.value = true
            _nativeSliceStateDirty.value = true
        }

        if (!addOk) return
        val publishPositions = customObjectPositions
        if (publishPositions != null) _multiObjectPositions.value = publishPositions
        // F66 review-2026-05-30 P0: file-B's new objects had no entry in
        // _loadTimePoses or _loadTimeObjectBoundingBoxes, so resetObjectRotation/
        // Scale silently no-op'd on them and ObjectScaleControl's mm-mode
        // reference fell back to zero. The extend variant adds baselines for
        // the new indices without clobbering file-A's user-applied poses.
        extendLoadTimePosesForNewObjects()
        _modelAddVersion.value++
        markSessionDirty()
        val objectCount = _objectBoundingBoxes.value.size / 3
        Log.i("SlicerVM", "Added $displayName (plateIdx=$plateIdx) — $objectCount objects on bed")
        diagnostics.recordEvent("add_model_to_bed", mapOf(
            "filename" to displayName,
            "plateIdx" to plateIdx,
            "objectCount" to objectCount,
        ))
        withContext(Dispatchers.Main) {
            currentModelName = "${_modelFileName.value} + $displayName"
            val info = lastModelInfo
            if (info != null) _state.value = SlicerState.ModelLoaded(info)
        }
    }

    fun loadModelFromFile(file: File, preserveDisplayName: String? = null, silent: Boolean = false) {
        // F66 review-2026-05-30: also gate on restoreInProgress like
        // addModelFromFile / addModelFromFileForPlate / loadJobGcodeForViewer.
        // Without this guard, restoreSession's own loadModelFromFile call
        // cancels its parent restoreSessionJob → CancellationException at the
        // next awaitLoadCompletion suspend point → F66 state replay + final
        // selectObject never run. Surfaced by the new
        // acceptSessionResume_withF66State integration test.
        if (!silent && !restoreInProgress) restoreSessionJob?.cancel()
        if (!NativeLibrary.isLoaded) {
            if (!silent) {
                _state.value = SlicerState.Error("Native slicer library not available on this device (arm64 required)")
            } else {
                _silentLoadCompleted.tryEmit(false)
            }
            return
        }
        invalidatePrepareMeshCache()
        // Phase 2 (2026-04-28) — clear per-filament overrides + canonical
        // cache at the start of every load so file A's overrides /
        // canonical-list don't silently apply to file B (the adversarial
        // review's cross-load leak).
        beginNewModelLoad()
        viewModelScope.launch(Dispatchers.IO) {
            val longOpCtx = beginLongOp("Loading model")
            try {
            try {
                val context = getApplication<Application>()
                val workspaceDir = transientWorkspaceDir()
                val cleared = UpgradeDetector.clearIntermediateCache(workspaceDir)
                if (cleared > 0) Log.i("SlicerVM", "Cleared $cleared intermediate cache files before direct model load")
                clipperRetryAttempted = false
                if (!file.exists() || !file.canRead()) {
                    if (!silent) {
                        _state.value = SlicerState.Error("Could not read file: ${file.absolutePath}")
                    } else {
                        _silentLoadCompleted.tryEmit(false)
                    }
                    return@launch
                }

                val filename = normalizeIncomingFilename(file.name)
                // F88 follow-up: when the caller provides a display name (e.g. the original
                // model name before Smart Paint rewrote it to "ai_paint_<ts>.3mf"), use that
                // instead of the cache-file's auto-generated name.
                currentModelName = preserveDisplayName?.takeIf { it.isNotBlank() } ?: filename
                if (!silent) {
                    _state.value = SlicerState.Loading(loadingMessageFor(currentModelName, file.length()))
                }

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

                // B104: capture the first plate ID for single-plate Bambu files so the
                // re-embed at slice time applies the same filterModelToPlate that the
                // initial load used. Without this _currentPlateId stays -1 and re-embed
                // includes all build items (including garbage off-plate objects), producing
                // an oversized bounding box that aborts with "no layers detected".
                var firstBambuPlateId: Int? = null

                val fileToLoad = if (filename.endsWith(".3mf", ignoreCase = true)) {
                    try {
                        java.util.zip.ZipFile(sourceFile).use { zip ->
                            if (zip.entries().toList().isEmpty()) {
                                if (!silent) {
                                    _state.value = SlicerState.Error("3MF file is empty or invalid")
                                } else {
                                    _silentLoadCompleted.tryEmit(false)
                                }
                                return@launch
                            }
                        }
                    } catch (e: java.util.zip.ZipException) {
                        if (!silent) {
                            _state.value = SlicerState.Error("3MF file is corrupt: ${e.message}")
                        } else {
                            _silentLoadCompleted.tryEmit(false)
                        }
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
                        if (!silent) {
                            _showPlateSelector.value = true
                        } else {
                            // Silent restore: caller knows which plate to pick and will
                            // call selectPlate(plate, silent=true) next. Signal so the
                            // awaitSilentLoadCompletion() in runSilentBackgroundRestore
                            // doesn't hang waiting for a ModelLoaded that never comes.
                            _silentLoadCompleted.tryEmit(true)
                        }
                        return@launch
                    }
                    Log.i("SlicerVM", "Single-plate, loading directly")
                    if (prepared.origInfo.isBambu && prepared.mergedInfo.plates.isNotEmpty()) {
                        firstBambuPlateId = prepared.mergedInfo.plates.first().plateId
                    }

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
                loadNativeModel(fileToLoad, silent = silent)
                firstBambuPlateId?.let { recoveryPlateId = it }
                markSessionDirty()
            } catch (e: Throwable) {
                NativeLibrary.previewMutex.withLock { native.clearModel() }
                if (!silent) {
                    _state.value = SlicerState.Error("Error: ${e.message}")
                } else {
                    _silentLoadCompleted.tryEmit(false)
                }
            }
            } finally {
                endLongOp(longOpCtx)
            }
        }
    }

    /**
     * Called when user selects a plate from the multi-plate dialog.
     */
    fun selectPlate(plateId: Int, silent: Boolean = false) {
        selectPlateJob?.cancel()
        slicingJob?.cancel()
        _showPlateSelector.value = false
        cancelPendingAdd()
        // F66 — switching plates rebuilds the native model with new object
        // indices. Clear all F66 per-object state so the next preview /
        // selection starts fresh on the post-switch model.
        _selection.value = ObjectSelection()
        _perObjectPoses.value = emptyMap()
        _loadTimePoses.value = emptyMap()
        _perVolumeExtruders.value = emptyMap()
        _splitObjectOps.value = emptyList()
        _splitVolumeOps.value = emptyList()
        // Always extract from the full processed multi-plate file so that switching plates
        // (e.g. plate 4 → plate 5) uses the correct source regardless of prior selections.
        // _multiPlateSourceFile is set once on load and never overwritten (B83 fix).
        val file = _multiPlateSourceFile
            ?: resolvePlateSelectionSourceFile(sourceModelFile, currentModelFile)
            ?: run {
                if (silent) _silentLoadCompleted.tryEmit(false)
                return
            }
        recoveryPlateId = plateId          // Track for Clipper recovery
        clipperRetryAttempted = false      // New plate = fresh retry allowance
        // Transition to Loading immediately so InlineModelPreview unmounts.
        // Without this, the rotation LaunchedEffect fires between _threeMfInfo update
        // and loadNativeModel completing, hitting the native's stale plate-N cache and
        // delivering the wrong mesh.  Unmounting ensures the fresh effect fires only
        // after the correct plate is loaded in native.
        if (!silent) {
            _state.value = SlicerState.Loading("Loading plate $plateId…")
        }
        diagnostics.recordEvent(
            "plate_selected",
            mapOf(
                "plateId" to plateId,
                "currentModelPath" to file.absolutePath
            )
        )

        selectPlateJob = viewModelScope.launch(Dispatchers.IO) {
            val longOpCtx = beginLongOp("Loading plate $plateId")
            try {
            try {
                val workspaceDir = transientWorkspaceDir()
                // Phase 1 native-first: use _fileThreeMfInfo (file-level metadata) for
                // the embed step. The embed needs plate-filtering (via plateId param) but
                // does NOT need plate-scoped usedExtruderIndices — file-level is safe
                // (over-estimates are harmless; under-estimates from buildSelectedPlateInfo
                // were the source of bugs #2/#5).
                //
                // The authoritative per-plate state is read FROM native after load.
                val fileInfo = _fileThreeMfInfo ?: _threeMfInfo.value
                ensureActive()

                // Bucket-1 fix (F1 calendar #2 / H2C benchy #5 / Buttons race):
                // pass file-level ThreeMfInfo to embedProfile, not the plate-scoped
                // narrowed one. Plate-narrowing (buildSelectedPlateInfo) under-counts
                // extruders for compound-object plates and SEMM-painted plates whose
                // paint states include indices the per-part scan misses. The embedded
                // filament_colour array sized to the narrowed count then drops paint
                // states the slicer would otherwise emit — Buttons appears with the
                // wrong colours on first load, F1 plate 1 misses its 4th colour, H2C
                // benchy collapses to fewer tools post-slice. File-level over-sizes
                // safely: extra filament entries past the active extruders are
                // ignored by the slicer, but missing entries silently drop tool
                // changes.
                //
                // Native plate scoping still happens at load time (plateId param to
                // ProfileEmbedder.embed runs the strip pipeline + native plate_id
                // filter). Geometry is plate-scoped; only the embedded filament
                // palette is file-wide.
                val embedInfo = fileInfo ?: ThreeMfParser.parseForPlateSelection(file)
                sourceModelFile = file
                sourceModelInfo = embedInfo
                resetToolRemapState()

                // ProfileEmbedder.embed(plateId) does:
                //   - filterModelToPlate on 3D/3dmodel.model <build>
                //   - stripUnreferencedResources BFS over component refs
                //   - stripAssembleSection on model_settings.config
                //   - stripUnreferencedConfigObjects to match the kept resource set
                //   - filterCustomGcodePerLayer on Metadata/custom_gcode_per_layer.xml
                // Equivalent to extractPlate + restructurePlateFile + embedProfile.
                val embeddedPlateFile = embedProfile(file, embedInfo, workspaceDir, plateId = plateId)
                ensureActive()
                // Direct field write — bypasses the setter's automatic
                // `refreshCanonicalFilamentList()` so the cached canonical
                // (from the multi-plate source file) survives plate
                // selection. Otherwise the setter would refresh from
                // `embeddedPlateFile`, whose `filament_colour` is stripped
                // by the embed pipeline, yielding a degenerate canonical.
                _currentModelFile = embeddedPlateFile
                // The embedded file is already plate-scoped — load all (plate_id=0).
                // The postLoadStateProvider callback reads plate state FROM native
                // and sets _threeMfInfo AFTER the native load (fixes race condition
                // where _threeMfInfo was set BEFORE native load completed).
                // Phase 2 §4 — derive the canonical filament list from the
                // ORIGINAL multi-plate source file. The embedded plate file
                // also has project_settings.config preserved, but only its
                // own plate's .model entries — so the file-wide paintStateMap
                // is only complete on the source. Use the source file as the
                // single source of truth so plate-narrowing is canonical-
                // driven rather than heuristic-driven.
                //
                // Use the cached value when present — the async refresh from
                // initial load may already have populated it. Only fall back
                // to a synchronous walk when the cache is empty (e.g. fast
                // user click before async completes). Walking a 73 MB ZIP
                // synchronously on every plate selection blows the 120s test
                // wait.
                val canonical = _canonicalFilamentList.value
                    ?: try {
                        // Sanitized `file` (= _multiPlateSourceFile) has its
                        // `project_settings.config` stripped by BambuSanitizer
                        // — the original input file (`rawInputFile`) still
                        // has it. Prefer rawInputFile as the canonical source.
                        val canonicalSource = rawInputFile ?: file
                        (com.u1.slicer.bambu.bambuCanonicalList(canonicalSource)
                            ?: com.u1.slicer.data.canonicalListAtLoad(canonicalSource))
                            ?.also {
                                _canonicalFilamentList.value = it
                                // Phase 2 (post-round-2-review F4) —
                                // keep the cache-identity tag in lock-
                                // step with the value.
                                canonicalCacheSourcePath = canonicalSource.absolutePath
                            }
                    } catch (_: Exception) { null }

                loadNativeModel(
                    embeddedPlateFile,
                    silent = silent,
                    postLoadStateProvider = {
                        // --- Native-first state reading ---
                        // Read authoritative plate state from native's loaded model.
                        val nativeState = readPlateStateFromNative()
                        // Build the UI-facing ThreeMfInfo from native data + file metadata.
                        val nativeInfo = if (fileInfo != null) {
                            buildThreeMfInfoFromNative(fileInfo, nativeState, plateId, canonical)
                        } else {
                            // Non-Bambu fallback: use embedInfo as-is
                            embedInfo
                        }
                        _threeMfInfo.value = nativeInfo
                        Log.i("SlicerVM", "selectPlate: native-first _threeMfInfo set: " +
                            "usedExtruders=${nativeInfo.usedExtruderIndices}, " +
                            "detectedExtruderCount=${nativeInfo.detectedExtruderCount}, " +
                            "hasPaint=${nativeInfo.hasPaintData}")
                        markSessionDirty()
                    }
                )
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.e("SlicerVM", "selectPlate(${plateId}) threw — clearing model", e)
                NativeLibrary.previewMutex.withLock { native.clearModel() }
                // M1 from post-fix code review: include the exception class so
                // null-message NPEs / UnsatisfiedLinkErrors are surfaced
                // distinguishably in the UI Error state instead of as bare
                // "Error loading plate: null".
                val cause = "${e::class.simpleName}: ${e.message ?: "(no message)"}"
                if (!silent) {
                    _state.value = SlicerState.Error("Error loading plate: $cause")
                } else {
                    _silentLoadCompleted.tryEmit(false)
                }
            }
            } finally {
                endLongOp(longOpCtx)
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

    /**
     * Load a model file into the native slicer engine and transition UI state.
     *
     * @param file       The (embedded) 3MF or STL file to load.
     * @param plateIdx   Plate index for plate-aware loading (-1 = all plates / legacy).
     * @param preserveTransforms When true, skip the identity reset of [_modelScale]
     *   and [_modelRotation]. Used by paths that reload the model AFTER the user has
     *   already applied transforms (e.g. a pre-slice re-embed) so the user's edits
     *   survive the reload. The pre-slice re-embed inside [startSlicing] currently
     *   bypasses this function entirely (calls [native.loadModel] directly), but the
     *   parameter exists so future consolidation does not silently regress #3 / #4.
     * @param postLoadStateProvider Optional callback invoked after a successful native load
     *   but BEFORE the B47 color-mapping block reads [_threeMfInfo]. Callers (e.g.
     *   [selectPlate]) use this to read plate state FROM native and set [_threeMfInfo]
     *   so downstream color-mapping sees the native-authoritative value.
     */
    private suspend fun loadNativeModel(
        file: File,
        plateIdx: Int = -1,
        preserveTransforms: Boolean = false,
        postLoadStateProvider: (suspend () -> Unit)? = null,
        silent: Boolean = false
    ) {
        val firstModelLoadThisLaunch = diagnostics.markFirstModelLoad()
        // Stale cached mesh from a previous model/plate load would cause InlineModelPreview's
        // LaunchedEffect(modelRotation, modelFilePath) to hit the B49 early-return guard and
        // skip getPreparePreviewMesh() for the new model, leaving the spinner indefinitely.
        invalidatePrepareMeshCache()
        // Acquire previewMutex before touching native model — prevents SIGSEGV when
        // getPreparePreviewMesh (on the preview coroutine) is iterating model volumes
        // while we clear+reload here.  Large model QEM decimation can hold the lock for
        // 30+ seconds; without this, loading a new model while QEM is running crashes.
        // Phase 1 sub-plan #2b: plateIdx >= 0 routes through loadModelForPlate so the
        // BBS importer filters objects to m_plater_data[plateIdx+1] at ingestion.
        // plateIdx = -1 (the default) preserves the legacy loadModel(path) behaviour.
        val success = NativeLibrary.previewMutex.withLock {
            if (plateIdx >= 0) {
                native.loadModelForPlate(file.absolutePath, plateIdx)
            } else {
                native.loadModel(file.absolutePath)
            }
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

            // Phase 1 native-first: invoke the callback so callers (selectPlate) can
            // read plate state FROM native and set _threeMfInfo before the B47
            // color-mapping block below reads it.
            postLoadStateProvider?.invoke()

            val info = native.getModelInfo()
            if (info != null) {
                lastModelInfo = info
                _modelInfo.value = info
                _objectBoundingBoxes.value = runCatching { native.getObjectBoundingBoxes() }.getOrDefault(floatArrayOf())
                if (!preserveTransforms) {
                    _modelScale.value = ModelScale()  // reset to 1× on each new load
                    _modelRotation.value = ModelRotation()
                }
                if (isLargeTriangleCount(info.triangleCount)) {
                    if (!silent) {
                        _state.value = SlicerState.Loading("Large model — preview may take a moment…")
                    }
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
                    // F78: read from active printer record instead of legacy settingsRepo so the
                    // correct per-printer presets are used. printersRepo.activePrinter.first() gives
                    // the actual stored presets; extruderPresets.value may still be
                    // defaultExtruderPresets() if DataStore hasn't emitted yet. (B86)
                    val presets = printersRepo.activePrinter.first()?.extruderPresets
                        ?: com.u1.slicer.data.defaultExtruderPresets()
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
                        val slotCount = mfInfo.detectedExtruderCount.coerceIn(1, 4)
                        // Compute tower position that avoids the model
                        val positions = CopyArrangeCalculator.calculate(info.sizeX, info.sizeY, _copyCount.value)
                        val estimatedTowerDepth = WipeTowerDepthEstimator.estimateDepth(info.sizeZ)
                        val towerPos = CopyArrangeCalculator.computeWipeTowerPosition(
                            positions, info.sizeX, info.sizeY,
                            towerWidth = _config.value.wipeTowerWidth,
                            towerDepth = estimatedTowerDepth
                        )
                        _config.value = _config.value.copy(
                            extruderCount = slotCount,
                            wipeTowerEnabled = true,
                            wipeTowerX = towerPos.first,
                            wipeTowerY = towerPos.second
                        )
                        customWipeTowerPos = towerPos
                        Log.i("SlicerVM", "Auto-placed wipe tower at (${towerPos.first}, ${towerPos.second})")
                        _colorMapping.value = initialMapping
                        _layerToolOnly.value = false
                        applyMultiColorAssignments(initialMapping, presets, filaments.value)
                        Log.i("SlicerVM", "Auto-applied color mapping: $slotCount extruders, mapping=$initialMapping")
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
                if (!silent) {
                    _state.value = SlicerState.ModelLoaded(info)
                }
                // Emit completion signal so silent callers know the load
                // succeeded. Gated on silent: loud loads must not pollute the
                // shared flow (stale tryEmit values could be consumed by a
                // later awaitSilentLoadCompletion()).
                if (silent) _silentLoadCompleted.tryEmit(true)
            } else {
                if (!silent) {
                    _state.value = SlicerState.Error("Failed to read model info")
                } else {
                    _silentLoadCompleted.tryEmit(false)
                }
            }
        } else {
            if (!silent) {
                _state.value = SlicerState.Error("Failed to load model")
            } else {
                _silentLoadCompleted.tryEmit(false)
            }
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
        val slotCount = usedSlots.size.coerceIn(1, 4)
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
            val compactSlots = usedSlots.take(slotCount)
            val isIdentity = compactSlots == (0 until slotCount).toList()
            if (isIdentity) null else compactSlots
        } else {
            // Per-object: extruderRemap in 3MF handles non-contiguous / non-identity slot order
            val compactSlots = usedSlots.take(slotCount)
            val isIdentity = compactSlots == (0 until slotCount).toList()
            if (isIdentity) null else compactSlots
        }
        // Phase 2 (2026-04-28) — Group B retired the slice-time
        // semmColorPermutation and slicerColorOrder flows. The slicer emits
        // canonical T-indices and PrintTimeRemap handles slot mapping at
        // Send time, so post-slice G-code permutation is no longer needed.
        val temps = IntArray(slotCount) { i ->
            val slotIndex = usedSlots.getOrElse(i) { i }
            val preset = extruderPresets.firstOrNull { it.index == slotIndex }
            val profileId = preset?.filamentProfileId
            filaments.firstOrNull { it.id == profileId }?.nozzleTemp
                ?: nozzleTempDefaultForMaterial(preset?.materialType ?: "PLA")
        }
        // Recompute wipe tower position if multi-extruder (unless user already placed it)
        val mi = lastModelInfo
        if (slotCount > 1 && mi != null && mi.sizeX > 0f && customWipeTowerPos == null) {
            val objPos = CopyArrangeCalculator.calculate(mi.sizeX, mi.sizeY, _copyCount.value)
            val towerPos = CopyArrangeCalculator.computeWipeTowerPosition(
                objPos, mi.sizeX, mi.sizeY, _config.value.wipeTowerWidth
            )
            val filamentLabel = resolveFilamentTypeLabelFromMapping(modelColorToExtruder, extruderPresets)
            _config.value = _config.value.copy(
                extruderCount = slotCount,
                extruderTemps = temps,
                extruderRetractLength = FloatArray(slotCount) { _config.value.retractLength },
                extruderRetractSpeed = FloatArray(slotCount) { _config.value.retractSpeed },
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
                extruderCount = slotCount,
                extruderTemps = temps,
                extruderRetractLength = FloatArray(slotCount) { _config.value.retractLength },
                extruderRetractSpeed = FloatArray(slotCount) { _config.value.retractSpeed },
                wipeTowerEnabled = slotCount > 1,
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
        Log.i("SlicerVM", "Applied color mapping: $slotCount extruders used=${usedSlots}, remap=${toolRemapSlots}, temps=${temps.toList()}, colors=$fullColors")
        diagnostics.recordEvent(
            "color_mapping_applied",
            mapOf(
                "colorMapping" to modelColorToExtruder,
                "usedSlots" to usedSlots,
                "extCount" to slotCount,
                "toolRemapSlots" to toolRemapSlots,
                "isIdentity" to (toolRemapSlots == null),
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
        // B65 + B109 review: use the mesh-AABB-aware effective footprint when
        // available so the bed-warning chip doesn't false-positive at off-axis
        // rotations on non-box meshes (e.g. Dragon Scale 45°, where the box
        // approximation reports a much larger footprint than the actual rotated
        // mesh, triggering a "may overlap" warning even when the model clearly
        // fits).
        val mi = lastModelInfo
        val s = _modelScale.value
        val rot = _modelRotation.value
        _copyBedWarning.value = if (mi != null && mi.sizeX > 0f && mi.sizeY > 0f) {
            val (effW, effH) = CopyArrangeCalculator.effectivePlacementFootprint(
                rotatedMeshSizeXY = _rotatedMeshSizeXY.value,
                loadTimeSizeX = mi.sizeX, loadTimeSizeY = mi.sizeY, loadTimeSizeZ = mi.sizeZ,
                scaleX = s.x, scaleY = s.y,
                rotationXDeg = rot.x, rotationYDeg = rot.y, rotationZDeg = rot.z,
            )
            CopyArrangeCalculator.copyBedWarning(effW, effH, _copyCount.value)
        } else null
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
        // In multi-object mode the preview mesh is world-positioned, so keep native offsets
        // in sync with the drag. Bump modelAddVersion so InlineModelPreview's rotation
        // LaunchedEffect re-fetches the mesh at the new world positions.
        if (hasMultipleDistinctObjectsVar) {
            _multiObjectPositions.value = positions
            viewModelScope.launch(Dispatchers.IO) {
                NativeLibrary.previewMutex.withLock {
                    native.setObjectPositions(positions)
                }
                invalidatePrepareMeshCache()
                _modelAddVersion.value++
            }
        }
        markSessionDirty()
    }

    /** Returns initial positions for inline 3D placement (custom or auto-calculated).
     *  Uses the mesh-AABB-aware effective footprint so the auto-center matches the
     *  renderer's drawn position at any rotation (B109 review #2). When the rotated
     *  preview mesh hasn't been fetched yet (cold-load gap), falls back to the
     *  box-rotation approximation — the next mesh refresh updates
     *  `_rotatedMeshSizeXY` and the composable re-collects, re-evaluating this
     *  function with the refined bounds. */
    fun getPlacementPositions(): FloatArray {
        customObjectPositions?.let { return it }
        val mi = lastModelInfo ?: return floatArrayOf(135f, 135f)
        val s = _modelScale.value
        val rot = _modelRotation.value
        val (effW, effH) = CopyArrangeCalculator.effectivePlacementFootprint(
            rotatedMeshSizeXY = _rotatedMeshSizeXY.value,
            loadTimeSizeX = mi.sizeX, loadTimeSizeY = mi.sizeY, loadTimeSizeZ = mi.sizeZ,
            scaleX = s.x, scaleY = s.y,
            rotationXDeg = rot.x, rotationYDeg = rot.y, rotationZDeg = rot.z,
        )
        return CopyArrangeCalculator.calculate(effW, effH, _copyCount.value)
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
            // B101: pass the first plate's ID for Bambu files so filterModelToPlate runs
            // and repositions objects to their correct on-plate transforms. Without this,
            // single-plate Bambu files (e.g. OreoProj1.3mf) load with parts off the build
            // plate because their <build> items carry plate-specific Z transforms that only
            // filterModelToPlate strips/normalises. selectPlate() already does this correctly
            // via plateId; now the initial load path does too.
            val firstPlateId = if (origInfo.isBambu && mergedInfo.plates.isNotEmpty())
                mergedInfo.plates.first().plateId else null
            embedProfile(processed, mergedInfo, workspaceDir, plateId = firstPlateId)
        }
        // F81 (GitHub #120): notify when the Bambu sanitize+embed pipeline
        // finishes — this is the long-running stage distinct from raw load,
        // and only Bambu files pay this cost. Non-Bambu STL/3MF skip it.
        if (origInfo.isBambu) {
            AppEventNotifier.notify(
                getApplication<android.app.Application>(),
                AppEventNotifier.Event.BambuPipelineReady(sourceFile.name)
            )
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
    private fun embedProfile(file: java.io.File, info: ThreeMfInfo, outputDir: java.io.File, plateId: Int? = null): java.io.File {
        val cfg = _config.value
        val slotCount = cfg.extruderCount.coerceAtLeast(1)
        val usedSlots = toolRemapSlots  // e.g. [2,3] for E3+E4; null = identity/single
        val colorMapping = _colorMapping.value
        // B95: pass max source filament index so plates with paint_color attributes
        // referencing high-index filaments (Buzz Lightyear plate 9: state 11 from "8C")
        // get an embedded filament_colour large enough for the slicer's segmentation
        // pass to address them. Without this, the high-index paint state is silently
        // dropped and the resulting G-code contains only the object-default tool.
        val maxSourceFilamentIndex = info.usedExtruderIndices.maxOrNull() ?: 0
        val computedTarget = computeEmbedTargetCount(
            colorMapping, info.hasPaintData, usedSlots, slotCount,
            hasMultiExtruderAssignments = info.hasMultiExtruderAssignments,
            maxSourceFilamentIndex = maxSourceFilamentIndex
        )
        // Phase 2 §4 Step 3 — ensure targetExtruderCount covers the
        // canonical filament list, so per-canonical overrides reach the
        // slicer without normalizePerFilamentArrays needing to truncate
        // them. Caller-side guarantee for the contract that the embedder
        // no longer enforces.
        // Exclude PAINT_DERIVED entries — those are AMS2/AMS3 overflow states that fold
        // back to states 1-4 via ThreeMfParser's modulo. Counting them would inflate
        // targetCount beyond the declared filament range (e.g. colored Benchy: 4 declared
        // colours but states up to 10 → canonical size=10 → T0-T9 emitted instead of T0-T3).
        // B95 high-AMS models are handled correctly by computeEmbedTargetCount via
        // maxSourceFilamentIndex — they don't need the canonical size path.
        val canonicalSize = getCanonicalFilamentList()
            ?.filaments?.count { it.source != com.u1.slicer.data.FilamentSource.PAINT_DERIVED }
            ?: 0
        // B125: if the user has overridden support_filament/support_interface_filament to
        // a slot beyond the model's own extruder count, expand targetCount so
        // normalizePerFilamentArrays keeps enough entries and extruder_count covers the
        // support slot. Without this, a single-colour model with supportFilament=4 gets
        // extruder_count=1 and OrcaSlicer silently ignores the support_filament setting.
        val ov125 = slicingOverrides.value
        val maxSupportSlot = maxOf(
            if (ov125.supportFilament.mode == OverrideMode.OVERRIDE) ov125.supportFilament.value ?: 0 else 0,
            if (ov125.supportInterfaceFilament.mode == OverrideMode.OVERRIDE) ov125.supportInterfaceFilament.value ?: 0 else 0
        )
        val targetCount = maxOf(computedTarget, canonicalSize, maxSupportSlot)
        // Phase 2 (S-Buttons mesh-diversity fix): the embed-time extruder remap is
        // disabled because the post-slice tool remap is also disabled
        // (`skipSliceTimeRemap = true` below) and the print-time slot mapping is
        // applied by [PrintTimeRemap] when sending to the printer. Applying the
        // embed-time remap with a non-identity colorMapping (e.g. a user whose
        // slot presets collapse multiple file colours onto fewer slots) would
        // collapse model_settings.config extruder values, producing a mesh with
        // fewer distinct extruder indices than the file's per-object layout —
        // S-Buttons plate 1's 4 buttons would render in only 2 colours instead
        // of the file's 4. The slicer continues to emit canonical T0..T(N-1)
        // values from the unmodified extruder layout; the user's slot mapping
        // is applied at print time, preserving the per-object diversity in the
        // 3D Prepare body.
        val extruderRemap: Map<Int, Int>? = null
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
        // F87: spill the active imported process profile's keys into the embed config
        // between the bundled Snapmaker process profile (or the preserved Bambu source
        // config) and the user's SlicingOverrides. ProfileEmbedder.buildConfig owns the
        // precedence order.
        val activeProcessKeys: Map<String, Any> = activeProcessProfile.value?.keys ?: emptyMap()
        // F91: per-slot OrcaSlicer tuning settings sourced from the user's filament library
        // entries (looked up via each slot's preset.filamentProfileId). Only keys with at
        // least one non-null library value are emitted; unset keys leave the bundled
        // profile / OrcaSlicer default in place.
        val filamentLibrarySettings: Map<String, Any> = buildFilamentLibrarySettings(
            slotCount = slotCount,
            usedSlots = usedSlots,
            presets = extruderPresets.value,
            filaments = filaments.value,
        )
        val embeddedConfig = profileEmbedder.buildConfig(
            info = info,
            sourceConfig = sourceConfig,
            filamentSettings = filamentLibrarySettings,
            overrides = buildProfileOverrides(cfg, targetCount, usedSlots, hasSourceConfig = sourceConfig != null),
            targetExtruderCount = targetCount,
            processProfileKeys = activeProcessKeys,
        )
        return profileEmbedder.embed(file, embeddedConfig, outputDir, info, extruderRemap, plateId = plateId)
    }

    private fun buildProfileOverrides(
        cfg: SliceConfig,
        slotCount: Int,
        usedSlots: List<Int>? = null,
        hasSourceConfig: Boolean = false,
    ): Map<String, Any> {
        // Phase 2 §4 Step 4 — slice config reads the canonical list (when
        // present) for per-filament types/temps, falling back to the
        // user's slot presets for non-canonical files (single-extruder
        // STL, layer-tool). The preset-cascade indirection retired
        // alongside `applyFilamentOverridesToPresets` — overrides only
        // apply per file filament, not per slot, so a non-canonical
        // single-filament file has nothing to override against here.
        val originalPresets = extruderPresets.value
        // B105: for non-canonical files with a known slot set (usedSlots != null),
        // derive types from the used slots only. Using all 4 presets causes
        // filamentCount=4, inflating nozzle_temperature/filament_type arrays in
        // embedded profiles to 4 entries when only 1 slot is used (e.g. single-color
        // 3MF re-embed with E3 remap). OrcaSlicer still uses nozzle_diameter.size()
        // as its effective extruder count, but mismatched arrays can confuse some
        // per-filament config paths.
        val slotTypes = if (usedSlots != null) {
            usedSlots.map { slot -> originalPresets.firstOrNull { it.index == slot }?.materialType ?: "PLA" }
        } else {
            originalPresets.sortedBy { it.index }.map { it.materialType }
        }
        val allSlotTemps = computeFreshSlotTemps(
            slotCount = originalPresets.size.coerceAtLeast(slotCount),
            usedSlots = null,
            presets = originalPresets,
            filaments = filaments.value
        ).toList()
        val slotTemps = computeFreshSlotTemps(slotCount, usedSlots, originalPresets, filaments.value).toList()

        val canonical = getCanonicalFilamentList()
        val perFilamentArrays = canonical?.let {
            buildPerFilamentTypeAndTemp(it, _filamentOverrides.value, originalPresets)
        }

        // B117: non-canonical files (STL, single-colour 3MF without per-filament
        // metadata) don't enter the `perFilamentArrays` branch — but DC15's bug
        // report showed users still need to override material type on Prepare for
        // those files (e.g. printing PETG on a file that declared PLA). Apply
        // the fileIndex=0 override to the first slot's type/temp here so the
        // slice picks up the user's choice. See `applyNonCanonicalOverride`.
        val (nonCanonicalTypes, nonCanonicalTemps) = applyNonCanonicalOverride(
            slotTypes = slotTypes,
            slotTemps = slotTemps,
            override = if (canonical == null) _filamentOverrides.value[0] else null,
        )

        val resolvedTypes = perFilamentArrays?.first ?: nonCanonicalTypes
        val resolvedTemps = perFilamentArrays?.second ?: nonCanonicalTemps
        // B99: a single-filament STL can still ask OrcaSlicer to print
        // supports/interface with another physical slot via support_filament
        // (1-based). Keep the file filament at index 0, then pad any
        // requested support slots from the user's physical-slot presets so
        // the emitted filament_type/nozzle_temperature arrays are long
        // enough and carry the support material instead of collapsing to the
        // model material.
        val finalTypes = if (perFilamentArrays != null && resolvedTypes.size < slotTypes.size) {
            resolvedTypes + slotTypes.drop(resolvedTypes.size)
        } else {
            resolvedTypes
        }
        val finalTemps = if (perFilamentArrays != null && resolvedTemps.size < allSlotTemps.size) {
            resolvedTemps + allSlotTemps.drop(resolvedTemps.size)
        } else {
            resolvedTemps
        }
        // Phase 2 §4 Step 2 — file-filament space is canonical.size when a
        // canonical list exists, otherwise it collapses to slot space.
        val filamentCount = finalTypes.size.coerceAtLeast(slotCount)

        // Phase 2 §4 Step 7 — per-filament colour overrides (Prepare screen)
        // reach the embedded filament_colour so the slicer's preview reflects
        // the user's chosen colours. canonical[i].color carries the resolved
        // colour (file → override per filament). For non-canonical files,
        // omit — Bambu preserve path keeps source filament_colour, default
        // profile stack supplies a single entry.
        val finalColours: List<String>? = canonical?.let { c ->
            val overridden = com.u1.slicer.data.applyOverridesToCanonical(
                canonical = c,
                overrides = _filamentOverrides.value
                    .mapValues { (_, ov) -> ov.color to ov.materialType },
            )
            overridden.filaments.map { it.color }
        }

        return buildProfileOverridesImpl(
            cfg = cfg,
            ov = slicingOverrides.value,
            slotCount = slotCount,
            filamentCount = filamentCount,
            hasSourceConfig = hasSourceConfig,
            filamentTypes = finalTypes,
            nozzleTemps = finalTemps,
            filamentColours = finalColours,
        )
    }

    /**
     * Phase 2.7 / B128 — produces per-canonical-filament filament_type and
     * nozzle_temperature lists. For each file filament i, the resolution
     * order is:
     *   1. User override on that fileIndex (Prepare screen).
     *   2. The file's declared materialType — but only for a genuinely
     *      declared spool (FILE_COLOUR, multi-colour, non-paint, injective
     *      slot). See [com.u1.slicer.data.resolvePerFilamentTypeAndTemp].
     *   3. The mapped slot's preset materialType (authoritative for
     *      paint-fold / support / single-colour cases).
     *   4. "PLA" / 220° as last resort.
     *
     * Nozzle temps are derived from the resolved material via
     * [nozzleTempDefaultForMaterial], or pulled from the linked
     * [FilamentProfile.nozzleTemp] when the user has explicitly chosen one.
     */
    private fun buildPerFilamentTypeAndTemp(
        canonical: com.u1.slicer.data.CanonicalFilamentList,
        overrides: Map<Int, FilamentOverride>,
        presets: List<ExtruderPreset>,
    ): Pair<List<String>, List<Int>> = com.u1.slicer.data.resolvePerFilamentTypeAndTemp(
        canonical = canonical,
        overrides = overrides.mapValues { (_, ov) -> ov.color to ov.materialType },
        colorMapping = _colorMapping.value,
        presets = presets,
        filamentLibrary = filaments.value,
    )

    /**
     * B128 — the per-canonical-fileIndex material the slice actually used, for
     * the Map & Print mismatch check. Mirrors [buildPerFilamentTypeAndTemp] but
     * takes the explicit slice-time [sliceTimeColorMapping] so the dialog's
     * "Sliced as X" reflects file-declared materials too (not just the slot
     * preset), keeping the mismatch warning honest after B128.
     */
    fun sliceTimeMaterials(
        canonical: com.u1.slicer.data.CanonicalFilamentList,
        sliceTimeColorMapping: List<Int>?,
    ): List<String> = com.u1.slicer.data.resolvePerFilamentTypeAndTemp(
        canonical = canonical,
        overrides = _filamentOverrides.value.mapValues { (_, ov) -> ov.color to ov.materialType },
        colorMapping = sliceTimeColorMapping,
        presets = extruderPresets.value,
        filamentLibrary = filaments.value,
    ).first

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
        // F66 — close the Edit panel and clear the selection highlight before
        // slicing. The re-embed/clearModel+loadModel inside the slice path
        // can invalidate object indices; the Edit panel would otherwise point
        // at a stale objIdx and the selection-scoped sliders would mutate the
        // wrong object on the post-slice model.
        _selection.value = ObjectSelection()
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
                LongOpService.start(context, "Slicing")
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
                        LongOpService.update(context, maxPct, stage)
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
                // 2026-05-25: always re-embed before slicing when a source file is available.
                // The previous gate (remap != null || extruderCount > 1 || profileNeedsReEmbed)
                // skipped re-embed for single-extruder slices, relying on `profileNeedsReEmbed`
                // being set by every mutation. But filament library edits and extruder-preset
                // changes (slot color, material, filamentProfileId) didn't flip that flag —
                // so single-colour STL slices kept the embed frozen at load time and ignored
                // subsequent filament-library tweaks (cheeky_b52's "16 mm³/s flow limit not
                // abided by" report). Re-embed for a single-extruder STL is sub-second; the
                // optimization isn't worth the correctness hazard.
                val needsReEmbed = sourceModelFile != null
                if (needsReEmbed) {
                    val src = sourceModelFile
                    // Use file-level ThreeMfInfo for the re-embed so filament_colour stays
                    // sized to the file's full palette. _threeMfInfo (per-plate, native-first)
                    // can under-count for compound objects + SEMM paint plates, dropping the
                    // 4th colour on F1 calendar / collapsing tools on H2C benchy. File-level
                    // over-sizes safely (extra entries are ignored by the slicer); native's
                    // plateId filter at load time keeps geometry plate-scoped.
                    val srcInfo = _fileThreeMfInfo ?: _threeMfInfo.value ?: sourceModelInfo
                    val context = getApplication<Application>()
                    if (src != null && srcInfo != null) {
                        val reason = when {
                            remap != null -> "extruder remap $remap"
                            _config.value.extruderCount > 1 -> "${_config.value.extruderCount}-extruder embed"
                            else -> "settings changed since last slice (B24)"
                        }
                        Log.i("SlicerVM", "Re-embedding 3MF ($reason) before slicing")
                        val isSingleExtruderRefresh = profileNeedsReEmbed && remap == null && _config.value.extruderCount <= 1
                        // Sub-plan #2d: sourceModelFile is the full multi-plate source file
                        // (selectPlate sets it to `file`, not a Kotlin-extracted single-plate
                        // file). The re-embed must thread currentPlateId through embedProfile
                        // so the embedded output is properly plate-scoped via the strip
                        // pipeline (filterModelToPlate + stripUnreferencedResources +
                        // stripAssembleSection + stripUnreferencedConfigObjects).
                        val activePlateId = _currentPlateId.value
                        val reembedPlateId = activePlateId.takeIf { it > 0 }
                        val reembedded = embedProfile(src, srcInfo, transientWorkspaceDir(), plateId = reembedPlateId)
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
                            //
                            // Sub-plan #2c E2E finding: re-embed already pre-filters the
                            // main model's <build> to reembedPlateId via ProfileEmbedder.
                            // Native load-all (loadModel path) avoids the double-filter
                            // mismatch that can fail BBS plate_id resolution for some files.
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

                        // F66 review-2026-05-30 P0 — replay order MUST be:
                        //   1. Re-add additional files (so the object index space
                        //      matches what the user saw when they performed
                        //      splits / per-object poses / per-volume overrides).
                        //   2. Replay splits (indices match user-action layout).
                        //   3. Replay per-volume extruders (uses post-split vol IDs).
                        //   4. Replay per-object poses (uses post-split obj IDs).
                        // The previous order replayed splits BEFORE re-adding files,
                        // so a split recorded against a file-B object (index >=
                        // n_primary at user-action time) would be out-of-range
                        // and silently no-op, leaving the user's bed missing the
                        // split they performed.

                        // Step 1: Re-add any extra files that were on the bed before
                        // the embed reload cleared the native model. Without this,
                        // Combo 1/3 (3MF primary with extruderCount > 1) would fail
                        // at setObjectPositions with a count mismatch.
                        if (hasMultipleDistinctObjectsVar && additionalModelFiles.isNotEmpty()) {
                            var allReAddOk = true
                            NativeLibrary.previewMutex.withLock {
                                for ((f, pIdx) in additionalModelFiles) {
                                    val reAddOk = if (pIdx >= 0) native.addModelForPlate(f.absolutePath, pIdx)
                                                  else native.addModel(f.absolutePath)
                                    if (!reAddOk) {
                                        Log.w("SlicerVM", "Re-add after embed failed for ${f.name}")
                                        allReAddOk = false
                                    }
                                }
                            }
                            val reAddObjectCount = NativeLibrary.previewMutex.withLock { native.nativeGetObjectCount() }
                            Log.i("SlicerVM", "Re-added ${additionalModelFiles.size} extra file(s) after 3MF re-embed")
                            diagnostics.recordEvent("re_add_after_embed", mapOf(
                                "fileCount" to additionalModelFiles.size,
                                "files" to additionalModelFiles.map { (f, pIdx) -> "${f.name}:plate$pIdx" },
                                "objectCountAfter" to reAddObjectCount,
                                "allOk" to allReAddOk,
                            ))
                            if (!allReAddOk) {
                                _state.value = SlicerState.Error(
                                    "One or more added files could not be reloaded before slicing. " +
                                    "Try adding them again."
                                )
                                return@launch
                            }
                        }

                        // Step 2: replay any pending split-to-objects/parts ops.
                        // The clearModel() + loadModel() above wiped the in-memory
                        // post-split state; without replay, slicing would use the
                        // original layout and the user's split would be lost.
                        if (_splitObjectOps.value.isNotEmpty() || _splitVolumeOps.value.isNotEmpty()) {
                            for (idx in _splitObjectOps.value) {
                                native.nativeSplitObject(idx)
                            }
                            for (entry in _splitVolumeOps.value) {
                                val parts = entry.split(":")
                                if (parts.size == 2) {
                                    val o = parts[0].toIntOrNull()
                                    val v = parts[1].toIntOrNull()
                                    if (o != null && v != null) native.nativeSplitVolume(o, v)
                                }
                            }
                            // Refresh bounding boxes after splits — the per-object
                            // size cache feeds the renderer's drag clamps.
                            _objectBoundingBoxes.value = runCatching {
                                native.getObjectBoundingBoxes()
                            }.getOrDefault(floatArrayOf())
                            Log.i("SlicerVM", "F66: replayed ${_splitObjectOps.value.size} object split(s) " +
                                "+ ${_splitVolumeOps.value.size} volume split(s) after re-embed")
                        }

                        // Step 3: replay per-volume extruder overrides. The
                        // clearModel+loadModel wiped the in-memory vol->config
                        // ["extruder"] overrides; without replay the slice falls
                        // back to file-declared per-volume extruders and the
                        // user's Parts-panel colour changes have no effect on the
                        // sliced G-code.
                        if (_perVolumeExtruders.value.isNotEmpty()) {
                            var replayedCount = 0
                            for ((key, slot) in _perVolumeExtruders.value) {
                                val parts = key.split(":")
                                if (parts.size == 2) {
                                    val o = parts[0].toIntOrNull()
                                    val v = parts[1].toIntOrNull()
                                    if (o != null && v != null) {
                                        if (native.nativeSetVolumeExtruder(o, v, slot)) replayedCount++
                                    }
                                }
                            }
                            Log.i("SlicerVM", "F66: replayed $replayedCount per-volume extruder override(s) after re-embed")
                        }

                        // Step 4: replay per-object rotation + scale. The
                        // clearModel+loadModel wiped instance[0]->set_rotation /
                        // set_scaling_factor overrides on every object; without
                        // replay the slice runs with file-declared identity
                        // rotation/scale and the user's per-object dial changes
                        // are lost in the G-code output.
                        if (_perObjectPoses.value.isNotEmpty()) {
                            var replayedPoses = 0
                            for ((idx, p) in _perObjectPoses.value) {
                                if (idx < 0 || idx >= native.nativeGetObjectCount()) continue
                                val rOk = native.nativeSetObjectRotation(idx, p.rotXDeg, p.rotYDeg, p.rotZDeg)
                                val sOk = native.nativeSetObjectScale(idx, p.scaleX, p.scaleY, p.scaleZ)
                                if (rOk && sOk) replayedPoses++
                            }
                            // Refresh sizes only — scaling changes per-object footprint,
                            // and downstream setObjectPositions(customObjectPositions)
                            // needs accurate sizes to place AABB-min corners correctly.
                            //
                            // Do NOT also overwrite customObjectPositions here from
                            // the post-replay world mins — that would clobber the
                            // user's chosen drag positions with the post-pose-replay
                            // native positions, producing the user-reported "objects
                            // moved and overlapped after slice" symptom.
                            _objectBoundingBoxes.value = runCatching {
                                native.getObjectBoundingBoxes()
                            }.getOrDefault(_objectBoundingBoxes.value)
                            Log.i("SlicerVM", "F66: replayed $replayedPoses per-object pose(s) after re-embed")
                        }
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

                if (custom != null && hasMultipleDistinctObjectsVar) {
                    val ok = native.setObjectPositions(custom)
                    Log.i("SlicerVM", "Multi-object placement: ${custom.size / 2} objects (ok=$ok)")
                    diagnostics.recordEvent(
                        "set_model_instances_for_slice",
                        mapOf("success" to ok, "mode" to "multi_object", "objectCount" to (custom.size / 2))
                    )
                } else if (custom != null) {
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
                //
                // Phase 2 (2026-04-28) structural fix — applyConfigToPrusa
                // is now gated on `!has_embedded_profile` for per-filament
                // tuning keys (nozzle_temperature, hot_plate_temp, retract_*,
                // filament_max_volumetric_speed, filament_density, fan_*),
                // and those keys are added to profile_keys[]. For embedded-
                // profile files (Snapmaker .3mf), the canonical-aware embed
                // values written by buildProfileOverrides reach the slicer
                // directly via profile_keys[] — cfg.extruderTemps is only
                // consulted for STL / non-Snapmaker fallbacks. For those
                // fallback paths, slot-space presets are the right source.
                // See docs/superpowers/exploration/2026-04-27-applyConfigToPrusa-cascade-audit.md
                val sliceConfig = resolvedSliceConfig.let { cfg ->
                    val effectiveExtruderCount = maxOf(
                        cfg.extruderCount,
                        cfg.supportFilament,
                        cfg.supportInterfaceFilament,
                    )
                    val slotFilamentTypes = extruderPresets.value
                        .sortedBy { it.index }
                        .map { it.materialType.takeIf { type -> type.isNotBlank() } ?: "PLA" }
                    cfg.copy(
                        extruderCount = effectiveExtruderCount,
                        wipeTowerEnabled = ov.resolvePrimeTower(effectiveExtruderCount, cfg.wipeTowerEnabled),
                        filamentTypes = Array(effectiveExtruderCount) { idx ->
                            slotFilamentTypes.getOrNull(idx) ?: cfg.filamentType.takeIf { it.isNotBlank() } ?: "PLA"
                        },
                        extruderTemps = computeFreshSlotTemps(
                        slotCount = effectiveExtruderCount,
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

                // B100: pass 0.0f sentinel for layerHeight when not in OVERRIDE mode so that
                // applyConfigToPrusa (which runs after profile_keys[]) doesn't stomp the value
                // the embedded profile set from the source 3MF or the process profile.
                // In OVERRIDE mode the explicit value is echoed here as a safety net.
                //
                // B106: STL files (sourceModelFile == null) have no embedded Snapmaker profile.
                // Without it, OrcaSlicer uses its bare G28 default start G-code, omitting
                // PRINT_START and the SM_PRINT_AUTO_FEED / SM_PRINT_FLOW_CALIBRATE macros.
                // Pass the machine G-code templates from assets so applyConfigToPrusa injects
                // them when has_embedded_profile=false. 3MF files (sourceModelFile != null)
                // already embed the profile via ProfileEmbedder — pass empty to avoid overriding it.
                val (b106StartGcode, b106EndGcode) = if (sourceModelFile == null) {
                    readPrinterMachineGcode()
                } else {
                    "" to ""
                }
                // F91 (2026-05-25): populate per-extruder SliceConfig fields from the user's
                // filament library so native applies them even when the slice bypasses
                // ProfileEmbedder (raw STL). buildFilamentLibraryArrays inspects the active
                // printer's extruder presets and looks up each linked FilamentProfile.
                val filamentArrays = buildFilamentLibraryArrays(
                    slotCount = sliceConfig.extruderCount.coerceAtLeast(1),
                    usedSlots = toolRemapSlots,
                    presets = extruderPresets.value,
                    filaments = filaments.value,
                )
                val jniSliceConfig = sliceConfig.copy(
                    layerHeight = if (ov.layerHeight.mode == OverrideMode.OVERRIDE)
                        sliceConfig.layerHeight else 0.0f,
                    machineStartGcode = b106StartGcode,
                    machineEndGcode = b106EndGcode,
                    filamentFlowRatios = filamentArrays.flowRatios,
                    filamentMaxVolumetricSpeeds = filamentArrays.maxVolumetricSpeeds,
                    filamentFanMinSpeeds = filamentArrays.fanMinSpeeds,
                    filamentFanMaxSpeeds = filamentArrays.fanMaxSpeeds,
                    filamentOverhangFanSpeeds = filamentArrays.overhangFanSpeeds,
                    filamentAdditionalCoolingFanSpeeds = filamentArrays.additionalCoolingFanSpeeds,
                    filamentSlowDownLayerTimes = filamentArrays.slowDownLayerTimes,
                    filamentSlowDownMinSpeeds = filamentArrays.slowDownMinSpeeds,
                    filamentCloseFanFirstLayers = filamentArrays.closeFanFirstLayers,
                    filamentFullFanSpeedLayers = filamentArrays.fullFanSpeedLayers,
                    filamentEnablePressureAdvance = filamentArrays.enablePressureAdvance,
                    filamentPressureAdvances = filamentArrays.pressureAdvances,
                    filamentMinimalPurgeOnWipeTower = filamentArrays.minimalPurgeOnWipeTower,
                    filamentNozzleTempInitialLayers = filamentArrays.nozzleTempInitialLayers,
                    filamentBedTempInitialLayers = filamentArrays.bedTempInitialLayers,
                    filamentCosts = filamentArrays.costs,
                )
                val result = native.slice(jniSliceConfig)
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
                    //
                    // Phase 2.7: when the file has more filaments than the user's extruder count
                    // (multi-filament file mapped to fewer slots), emit one filament_type per
                    // canonical filament so per-filament overrides reach the G-code header.
                    val canonicalForPatch = getCanonicalFilamentList()
                    val basePresets = extruderPresets.value
                    val supportDrivenSlotCount = maxOf(
                        slicingOverrides.value.supportFilament.value ?: 0,
                        slicingOverrides.value.supportInterfaceFilament.value ?: 0,
                    )
                    // B110: produce filament_type AND nozzle_temperature header arrays
                    // from the same physical-slot-indexed source so they agree on which
                    // slot received a Prepare-screen override. Without this, the
                    // post-slice `filament_type` patch is physical-slot-indexed (B102) but
                    // `nozzle_temperature` stays in canonical/UI order — the two header
                    // rows disagree (PLA at slot 0 but 235°C at slot 0, vs PETG at slot 2).
                    val ftTypes: List<String>
                    val ntTemps: List<Int>
                    if (
                        canonicalForPatch != null &&
                        !(canonicalForPatch.size <= 1 && supportDrivenSlotCount > canonicalForPatch.size)
                    ) {
                        val padTo = maxOf(canonicalForPatch.size, supportDrivenSlotCount)
                        ftTypes = resolveFilamentTypesForHeaderPatch(
                            canonical = canonicalForPatch,
                            overrides = _filamentOverrides.value
                                .mapValues { (_, ov) -> ov.color to ov.materialType },
                            colorMapping = _colorMapping.value,
                            presets = basePresets,
                            filamentLibrary = filaments.value,
                            padTo = padTo,
                        )
                        ntTemps = resolveNozzleTempsForHeaderPatch(
                            canonical = canonicalForPatch,
                            overrides = _filamentOverrides.value
                                .mapValues { (_, ov) -> ov.color to ov.materialType },
                            colorMapping = _colorMapping.value,
                            presets = basePresets,
                            filamentLibrary = filaments.value,
                            padTo = padTo,
                        )
                    } else {
                        // B105: for non-canonical files (STL, generic 3MF) emit only the
                        // materials for the physically-used slots. Using all 4 presets
                        // here wrote a 4-entry filament_type header even for single-colour
                        // prints (matched the slotTypes bug in buildProfileOverrides).
                        // B117: prefer the Prepare-screen `_filamentOverrides[0]` material
                        // for the FIRST slot — that's where DC15's "I want PETG on a
                        // single-colour 3MF" override lands. Without this the G-code
                        // header would still read PLA even though the slice used PETG.
                        val usedSlotsForPatch = toolRemapSlots
                            ?: _colorMapping.value?.distinct()?.sorted()
                            ?: listOf(_selectedExtruder.value)
                        val nonCanonicalOverride0 = _filamentOverrides.value[0]
                        ftTypes = applyNonCanonicalOverride(
                            slotTypes = resolveNonCanonicalHeaderPatchTypes(usedSlotsForPatch, basePresets),
                            slotTemps = resolveNonCanonicalHeaderPatchTemps(
                                usedSlotsForPatch, basePresets, filaments.value
                            ),
                            override = nonCanonicalOverride0,
                        ).first
                        ntTemps = applyNonCanonicalOverride(
                            slotTypes = resolveNonCanonicalHeaderPatchTypes(usedSlotsForPatch, basePresets),
                            slotTemps = resolveNonCanonicalHeaderPatchTemps(
                                usedSlotsForPatch, basePresets, filaments.value
                            ),
                            override = nonCanonicalOverride0,
                        ).second
                    }
                    val ftPatched = fixFilamentTypeHeader(result.gcodePath, ftTypes)
                    val ntPatched = fixNozzleTemperatureHeader(result.gcodePath, ntTemps)
                    Log.i("SlicerVM", "B63 filament_type patch: $ftPatched (types=$ftTypes)")
                    Log.i("SlicerVM", "B110 nozzle_temperature patch: $ntPatched (temps=$ntTemps)")

                    // Sub-plan #2c P1 fix: when a plate is selected (currentPlateId > 0), the
                    // plate-filtered custom_gcode_per_layer.xml lives in currentModelFile
                    // (the embedded file produced with plateId threading via ProfileEmbedder).
                    // sourceModelFile carries the FULL multi-plate source whose XML has all
                    // plates' layer-tool entries — reading it as the fallback would inject
                    // pauses for non-selected plates post-slice. Prefer currentModelFile
                    // whenever a plate is selected; fall back to sourceModelFile only for
                    // non-plate (STL / single-plate) flows.
                    val activePlateIdUi = _currentPlateId.value
                    val layerToolMetadataFile = when {
                        _threeMfInfo.value?.hasLayerToolChanges != true -> null
                        activePlateIdUi > 0 && currentModelFile?.exists() == true -> currentModelFile
                        sourceModelFile?.exists() == true -> sourceModelFile
                        else -> currentModelFile
                    }
                    // Native nativeGetPlateData takes a 0-based plate index. _currentPlateId is
                    // 1-based (or -1 when no plate selected — treat as plate 0 for the injector
                    // fallback, matching the STL / single-plate default used elsewhere).
                    val plateIdxForInjector = (activePlateIdUi - 1).coerceAtLeast(0)
                    val injectedLayerToolPause = layerToolMetadataFile
                        ?.let {
                            LayerToolPauseInjector.injectFrom3mf(
                                result.gcodePath,
                                it,
                                plateIdxForInjector,
                                native::nativeGetPlateData
                            )
                        }
                        ?: false
                    if (injectedLayerToolPause) {
                        Log.i(
                            "SlicerVM",
                            "Injected layer-change pause commands into ${result.gcodePath} using ${layerToolMetadataFile?.name}"
                        )
                    }
                    // Phase 2 (2026-04-28) — Group B retired the slice-time
                    // tool remap. The slicer emits canonical T-indices in
                    // fileIndex space; PrintTimeRemap (separate, Send-time)
                    // translates to physical slots when the user confirms
                    // the Filament mapping dialog. The on-disk G-code stays
                    // in canonical space so "how many filaments?" stays
                    // answerable from the slice summary alone.
                    //
                    // 2026-05-22 — the SEMM paint-state fold
                    // (`computeSemmFoldSlots` + `GcodeToolRemapper.remap`)
                    // was removed. The fold rewrote canonical T-indices to
                    // `T(i%N)` so the legacy fixed-width palette could
                    // address them; with the canonical-width palette in
                    // place (`normalizeGcodePreviewColors`,
                    // `GcodeRenderer.setExtruderColors`), the renderer
                    // paints canonical T5/T9 directly and the fold
                    // collapsed non-contiguous active T-indices to the
                    // same compact slot (Buzz plate 8: T5+T9 → T1).
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
                    _excludeObjects.value = parseExcludeObjects(result.gcodePath)
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
                    setParsedGcodeWithRangeReset(outputValidation.parsedGcode)
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
                            filamentType = cfg.filamentType,
                            // Phase 2 (2026-04-28) — canonical mapping
                            // metadata for shareJobGcode; lets historical
                            // shares reproduce canonical→physical remap.
                            //
                            // Bug-class fix (post-v2.0.0-validation): persist
                            // the resolved canonical-WIDE mapping, not the
                            // live `_colorMapping` (which is plate-narrowed
                            // post-`selectPlate` for multi-plate Bambu
                            // files). Without this, `shareJobGcode` would
                            // feed a 2-wide plate-narrowed CSV to
                            // `applyPrintTimeRemap` for jobs whose actual
                            // G-code emits canonical T-indices (e.g. T4/T7
                            // for Dragon plate 1) — leaving those T-values
                            // un-rewritten and shipping canonical-space
                            // G-code to the printer. Routing through
                            // `resolveExportMapping()` here makes the stored
                            // CSV the same canonical-positional shape that
                            // Save / Share / Send already produce, and
                            // unifies all four export paths through one
                            // resolver.
                            canonicalListSize = _canonicalFilamentList.value?.size,
                            colorMappingCsv = resolveExportMapping()
                                ?.takeIf { it.isNotEmpty() }
                                ?.joinToString(","),
                            // Phase 2 schema v6 (post-round-2-review,
                            // Reviewer 3 P1) — single-colour slot pick
                            // persistence. Captured for ALL jobs (even
                            // multi-colour, where it's harmless extra
                            // metadata) since the consumer in
                            // shareJobGcode reads it only when the
                            // canonical list is single-filament.
                            selectedExtruderAtSlice = _selectedExtruder.value
                                .takeIf { it in 0..3 },
                        )
                    )
                    lastSliceJobId = jobId
                    markSessionDirty()
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
                LongOpService.stop(context)
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
        // CopyArrangeCalculator returns lower-left world coords; setModelInstances
        // also takes lower-left world coords (verified on-device for STL files
        // via SetModelInstancesOffsetTest — gcodeMinX matches requested origin
        // within ±2mm). Earlier this method tried to read native's
        // `getInstanceOffsets()` instead, but that returns the raw stored
        // offset = pos - sf*meshBB.min — equal to pos only when meshBB.min=0
        // (STL); for Bambu files whose mesh vertices live at world coordinates
        // the raw offset is shifted by half the scaled mesh size and doesn't
        // represent the world lower-left.
        val positions = custom ?: CopyArrangeCalculator.calculate(scaledSizeX, scaledSizeY, copies)
        if (positions.isEmpty()) return null
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
        // F78: write through PrintersRepository so the active printer's preset record is updated.
        if (_config.value.extruderCount == 1) {
            val selectedIdx = _selectedExtruder.value
            viewModelScope.launch(Dispatchers.IO) {
                val cfg = printersRepo.config.first() ?: return@launch
                val active = cfg.active
                val current = active.extruderPresets.toMutableList()
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
                printersRepo.update(active.copy(extruderPresets = current.sortedBy { it.index }))
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

    /**
     * Phase 2 (2026-04-28, post-adversarial-review) — shares a
     * historical job's G-code, applying the canonical→physical remap
     * recorded at slice time. Pre-Phase-2 jobs (where
     * [SliceJob.canonicalListSize] is null) carry their pre-remap
     * physical-slot G-code on disk already, so they're shared as-is.
     *
     * Pre-revision this function shared the stored gcode raw — the
     * adversarial review's "4th-path leak". Now it routes through the
     * same export-mapping helper as Save / Share / Send.
     */
    fun shareJobGcode(job: SliceJob) {
        val sourceFile = File(job.gcodePath)
        if (!sourceFile.exists()) return

        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val baseName = com.u1.slicer.data.ModelFileNaming.baseName(
                job.modelName.ifBlank { null }, sourceFile.name
            )
            val shareFile = File(
                sourceFile.parentFile,
                "$baseName.share.gcode"
            )
            // Build the mapping from job metadata. Routes through the
            // same `resolveCanonicalExportMapping` helper as Save / Share
            // / Send so the four export paths share one source of truth.
            //
            //   - canonicalListSize == null → pre-Phase-2 job whose stored
            //     G-code is already physical-slot. Skip the helper and
            //     pass null → identity copy.
            //   - canonicalListSize present + colorMappingCsv non-null →
            //     stored CSV is canonical-positional (the slice-time
            //     persist site already routes through
            //     `resolveExportMapping()`), so the helper's case-1 path
            //     returns it as-is.
            //   - canonicalListSize == 1 + colorMappingCsv null →
            //     single-colour job; helper's case-3 path uses
            //     selectedExtruderAtSlice.
            //   - canonicalListSize > 1 + colorMappingCsv null →
            //     pre-schema-v5 multi-colour job. Identity-mod-4 fallback
            //     via the helper.
            val canonicalSize = job.canonicalListSize ?: 0
            val mapping: List<Int>? = if (canonicalSize == 0) {
                null
            } else {
                com.u1.slicer.gcode.resolveCanonicalExportMapping(
                    canonicalSize = canonicalSize,
                    confirmedMapping = com.u1.slicer.data.decodedColorMapping(job),
                    selectedExtruder = job.selectedExtruderAtSlice ?: 0,
                )
            }
            if (!prepareExportableGcodeWithMapping(sourceFile, shareFile, mapping)) return@launch

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                shareFile
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            withContext(Dispatchers.Main) {
                context.startActivity(
                    Intent.createChooser(intent, "Share G-code").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    // F60: parse saved G-code and set it as the active preview so the viewer can display it.
    // Returns true if the file existed and parsing was started, false if the file is missing.
    fun loadJobGcodeForViewer(job: SliceJob, onResult: (success: Boolean) -> Unit) {
        if (!restoreInProgress) restoreSessionJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            val gcodeFile = File(job.gcodePath)
            if (!gcodeFile.exists()) {
                launch(Dispatchers.Main) { onResult(false) }
                return@launch
            }
            try {
                val parsed = GcodeParser.parse(gcodeFile)
                setParsedGcodeWithRangeReset(parsed)
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
    private fun readPrinterMachineGcode(): Pair<String, String> {
        return try {
            val context = getApplication<Application>()
            val json = context.assets.open("orca_profiles/printer/snapmaker_u1.json")
                .bufferedReader().use { org.json.JSONObject(it.readText()) }
            Pair(
                json.optString("machine_start_gcode", ""),
                json.optString("machine_end_gcode", ""),
            )
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w("SlicerVM", "B106: failed to read machine G-code from assets: ${e.message}")
            "" to ""
        }
    }

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
            val profiles = filamentDao.getAll().first()
            val profileMap = profiles.associateBy { it.id }
            val cookies = settingsRepo.makerWorldCookies.first()
            val printersConfig = container.printersRepository.config.first()
            // Build filament-name-resolved extruder presets for the active printer
            val presets = extruderPresets.value
            val resolvedPresetsConfig = if (printersConfig != null) {
                val resolvedPresets = presets.map { p ->
                    p // filamentProfileId is preserved; name resolution is done at export
                }
                val active = printersConfig.active.copy(extruderPresets = resolvedPresets)
                com.u1.slicer.data.PrintersConfig(
                    printers = printersConfig.printers.map { if (it.id == active.id) active else it },
                    activeId = printersConfig.activeId,
                )
            } else printersConfig
            val processProfilesConfig = container.processProfilesRepository.config.first()
            val json = SettingsBackup.export(
                cfg, overrides, resolvedPresetsConfig?.active?.moonrakerUrl ?: "", presets, profiles,
                filamentNameResolver = { id -> profileMap[id]?.name },
                makerWorldCookies = cookies,
                printersConfig = resolvedPresetsConfig,
                processProfilesConfig = processProfilesConfig.takeIf { it.profiles.isNotEmpty() },
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
                data.makerWorldCookies?.let {
                    settingsRepo.saveMakerWorldCookies(it)
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
                // Restore the full printers config (v2) or legacy single-printer (v1 migrated).
                val hasPrinterUrl: Boolean
                if (data.printersConfig != null) {
                    hasPrinterUrl = data.printersConfig.printers.any { it.moonrakerUrl.isNotBlank() }
                    // Resolve filament profile names to new IDs on each printer's extruder presets.
                    // The extruderPresets in the JSON use the legacy slot-based format; re-parse them
                    // from the raw JSON so we can apply filamentProfileName resolution.
                    val root = JSONObject(json)
                    val presetsArr = root.optJSONArray("extruderPresets")
                    val resolvedActivePrinter = if (presetsArr != null) {
                        val parsed = SettingsBackup.parseExtruderPresetsWithNames(presetsArr)
                        val resolved = parsed.map { p ->
                            val resolvedId = p.filamentProfileName?.let { nameToId[it] }
                            p.preset.copy(filamentProfileId = resolvedId)
                        }
                        data.printersConfig.active.copy(extruderPresets = resolved)
                    } else data.printersConfig.active
                    val resolvedConfig = com.u1.slicer.data.PrintersConfig(
                        printers = data.printersConfig.printers.map {
                            if (it.id == resolvedActivePrinter.id) resolvedActivePrinter else it
                        },
                        activeId = data.printersConfig.activeId,
                    )
                    container.printersRepository.replace(resolvedConfig)
                    // Keep legacy settingsRepo.printerUrl in sync for any code still reading it.
                    settingsRepo.savePrinterUrl(resolvedActivePrinter.moonrakerUrl)
                    settingsRepo.saveExtruderPresets(resolvedActivePrinter.extruderPresets)
                } else {
                    hasPrinterUrl = false
                }
                // F87: restore process profiles + active selection.
                data.processProfilesConfig?.let { ppCfg ->
                    container.processProfilesRepository.replace(ppCfg)
                }
                Log.i("SlicerVM", "Settings backup imported successfully")
                onImported(hasPrinterUrl)
            } catch (e: Exception) {
                Log.e("SlicerVM", "Failed to import backup: ${e.message}", e)
            }
        }
    }

    // F89: capture a SessionState snapshot from the current ViewModel state.
    // Returns null if there's nothing meaningful to save (no rawInputFile = no
    // loaded model). Pure read of existing fields; safe to call from any thread.
    private fun captureSessionSnapshot(): SessionState? {
        val raw = rawInputFile ?: return null
        return SessionState(
            modelName = currentModelName,
            rawInputPath = raw.absolutePath,
            sourceModelPath = sourceModelFile?.absolutePath,
            currentModelPath = _currentModelFile?.absolutePath,
            multiPlateSourcePath = _multiPlateSourceFile?.absolutePath,
            selectedPlateId = recoveryPlateId.takeIf { it >= 0 },
            modelScale = _modelScale.value.let { Triple(it.x, it.y, it.z) },
            modelRotation = _modelRotation.value.let { Triple(it.x, it.y, it.z) },
            copyCount = _copyCount.value,
            customObjectPositions = customObjectPositions?.copyOf(),
            customWipeTowerPos = customWipeTowerPos,
            additionalFiles = additionalModelFiles.toList().map { (f, p) ->
                SessionState.AdditionalFile(path = f.absolutePath, plateIdx = p)
            },
            sliceJobId = lastSliceJobId,
            wasSliceComplete = _state.value is SlicerState.SliceComplete,
            savedAtEpochMs = System.currentTimeMillis(),
            appVersionCode = BuildConfig.VERSION_CODE,
            // ---- F66 ----
            selectedObjectIndex = _selection.value.objectIndex,
            selectedVolumeIndex = _selection.value.volumeIndex,
            perObjectPoses = _perObjectPoses.value,
            perVolumeExtruders = _perVolumeExtruders.value,
            splitObjectOperations = _splitObjectOps.value,
            splitVolumeOperations = _splitVolumeOps.value,
        )
    }

    // F89: emit to the debounced save flow. Cheap; safe to call per-frame.
    private fun markSessionDirty() {
        if (rawInputFile == null) return
        sessionSaveFlow.tryEmit(Unit)
    }

    /**
     * F90: start the [LongOpService] foreground notification for [stage]. Callers MUST pair
     * this with [endLongOp] in a try/finally so cancellation/exception pops the stack.
     * Returns the application context for re-use in the matching [endLongOp] call.
     */
    private fun beginLongOp(stage: String): Context {
        val context = getApplication<Application>()
        LongOpService.start(context, stage)
        return context
    }

    /** F90: pop the matching [beginLongOp] stage off the LongOpService stack. */
    private fun endLongOp(context: Context) {
        LongOpService.stop(context)
    }

    // F89: wire the debounced session save + the StateFlow-based dirty mirror.
    // Called once from init.
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun wireSessionPersistence() {
        viewModelScope.launch {
            sessionSaveFlow
                .debounce(500)
                .collectLatest {
                    val snapshot = captureSessionSnapshot() ?: return@collectLatest
                    try {
                        sessionStateRepository.write(snapshot)
                    } catch (e: Exception) {
                        Log.w("SlicerVM", "F89 session save failed: ${e.message}")
                    }
                }
        }
        // StateFlow-based mirror — combine emits once on subscribe with current
        // values; markSessionDirty no-ops while rawInputFile is null so the
        // startup emission is harmless.
        viewModelScope.launch {
            combine(_modelScale, _modelRotation, _copyCount) { _, _, _ -> Unit }
                .collect { markSessionDirty() }
        }
        // F89: one-shot init read. If a saved session exists and the source file
        // is still on disk, expose a resume offer for the banner UI. If the file
        // is gone, surface a one-time toast and clear the stale entry.
        viewModelScope.launch {
            val saved = try { sessionStateRepository.read() } catch (e: Exception) {
                Log.w("SlicerVM", "F89 session read failed: ${e.message}")
                null
            } ?: return@launch
            val raw = File(saved.rawInputPath)
            if (!raw.exists()) {
                try { sessionStateRepository.clear() } catch (e: Exception) {
                    Log.w("SlicerVM", "F89 stale-session clear failed: ${e.message}")
                }
                _toastEvents.tryEmit("Couldn't resume ${saved.modelName} — file no longer available")
                return@launch
            }
            _sessionResumeOffer.value = SessionResumeOffer(
                modelName = saved.modelName,
                plateId = saved.selectedPlateId,
            )
        }
    }

    /** F89: user tapped Resume on the banner. For sessions that ended past
     *  slice-complete, fast-path to Preview using the Room SliceJob row.
     *  Otherwise replay the full Prepare state via restoreSession(). */
    fun acceptSessionResume() {
        val offer = _sessionResumeOffer.value ?: return
        _sessionResumeOffer.value = null
        restoreSessionJob?.cancel()
        restoreSessionJob = viewModelScope.launch {
            restoreInProgress = true
            try {
                val saved = try { sessionStateRepository.read() } catch (e: Exception) { null }
                    ?: return@launch
                val raw = File(saved.rawInputPath)
                if (!raw.exists()) {
                    try { sessionStateRepository.clear() } catch (_: Exception) {}
                    _toastEvents.tryEmit("Couldn't resume ${saved.modelName} — file no longer available")
                    return@launch
                }
                // Fast-path: past slicing AND the Room row + gcode file still exist.
                // Skip the model reload, jump straight to SliceComplete + Preview.
                if (saved.wasSliceComplete && saved.sliceJobId != null) {
                    val row = try {
                        sliceJobDao.getAll().first().firstOrNull { it.id == saved.sliceJobId }
                    } catch (e: Exception) {
                        Log.w("SlicerVM", "F89 fast-path: SliceJob lookup failed: ${e.message}")
                        null
                    }
                    if (row != null && File(row.gcodePath).exists()) {
                        restoreSliceCompleteOnly(saved, row)
                        // Continue with a silent background load so Prepare populates
                        // for re-slicing. The fast-path SliceComplete state survives
                        // because every `_state.value = ...` write is suppressed when
                        // silent = true.
                        runSilentBackgroundRestore(saved, raw)
                        return@launch
                    }
                    Log.i("SlicerVM", "F89 fast-path unavailable (row=$row, gcodeExists=${row?.gcodePath?.let { File(it).exists() }}); falling back to full restore")
                }
                // Full Prepare-state restore (model load + plate + transforms).
                restoreSession(saved, raw)
            } finally {
                restoreInProgress = false
            }
        }
    }

    /** F89 fast-path: restore SliceComplete state directly from the Room row
     *  without re-loading the native model. Cheap (sub-second). The user can
     *  re-open from Jobs to get the model back into Prepare for re-slicing. */
    private suspend fun restoreSliceCompleteOnly(saved: SessionState, row: SliceJob) {
        lastSliceJobId = row.id
        currentModelName = saved.modelName
        // Parse the gcode file off the main thread so the Preview tab's 3D
        // InlineGcodePreview has its `parsedGcode.layers` to render. Without
        // this the Preview composable falls back to a near-empty card and
        // the user sees a blank screen post-Resume.
        val gcodeFile = File(row.gcodePath)
        val parsed = try {
            kotlinx.coroutines.withContext(Dispatchers.IO) { GcodeParser.parse(gcodeFile) }
        } catch (e: Exception) {
            Log.w("SlicerVM", "F89 fast-path: gcode parse failed: ${e.message}")
            null
        }
        setParsedGcodeWithRangeReset(parsed)
        _state.value = SlicerState.SliceComplete(sliceResultFromJob(row))
        _gcodePreview.value = ""
        _navigateEvents.tryEmit("preview")
        Log.i("SlicerVM", "F89 fast-path: restored SliceComplete for ${saved.modelName} (jobId=${row.id}, layers=${parsed?.layers?.size ?: 0})")
    }

    /** F89: after the fast-path has put us on Preview, do a silent model load
     *  so the Prepare tab populates for re-slicing. All `_state` writes are
     *  suppressed so the SliceComplete state from the fast-path survives. */
    private suspend fun runSilentBackgroundRestore(saved: SessionState, raw: File) {
        _silentRestoreInProgress.value = true
        try {
            loadModelFromFile(raw, preserveDisplayName = saved.modelName, silent = true)
            if (!awaitSilentLoadCompletion()) {
                Log.w("SlicerVM", "F89 silent restore: load failed or timed out")
                _toastEvents.tryEmit("Couldn't reload ${saved.modelName} for editing — re-open from Jobs to retry")
                try { sessionStateRepository.clear() } catch (_: Exception) {}
                return
            }
            // Multi-plate: load paused waiting for plate pick (we suppressed the
            // dialog). Pick the saved plate or fall back to first available.
            if (_multiPlatePlates.value.isNotEmpty()) {
                val plate = saved.selectedPlateId
                    ?: recoveryPlateId.takeIf { it > 0 }
                    ?: _multiPlatePlates.value.firstOrNull()?.plateId
                if (plate == null) {
                    Log.w("SlicerVM", "F89 silent restore: no plate to select")
                    _toastEvents.tryEmit("Couldn't reload ${saved.modelName} for editing — re-open from Jobs to retry")
                    try { sessionStateRepository.clear() } catch (_: Exception) {}
                    return
                }
                selectPlate(plate, silent = true)
                if (!awaitSilentLoadCompletion()) {
                    Log.w("SlicerVM", "F89 silent restore: plate select failed or timed out")
                    _toastEvents.tryEmit("Couldn't reload ${saved.modelName} for editing — re-open from Jobs to retry")
                    try { sessionStateRepository.clear() } catch (_: Exception) {}
                    return
                }
            }
            // Transforms (these don't write _state, safe even outside silent mode).
            setModelScale(ModelScale(
                saved.modelScale.first, saved.modelScale.second, saved.modelScale.third
            ))
            setModelRotation(ModelRotation(
                saved.modelRotation.first, saved.modelRotation.second, saved.modelRotation.third
            ))
            setCopyCount(saved.copyCount)
            // F66 review-2026-05-30: replay split/pose/extruder state BEFORE
            // applyPlacementPositions so the saved customObjectPositions count
            // matches the post-split object count. Without this, a Cali-cube
            // session that was split + sliced + resumed would silently load
            // the original 1-object cube and then apply N positions, leaving
            // the renderer to draw the un-split cube N times ("multiple sets
            // of the object on the plate" user report).
            for (idx in saved.splitObjectOperations) {
                splitObject(idx)
            }
            _splitObjectOps.value = saved.splitObjectOperations
            for (entry in saved.splitVolumeOperations) {
                val parts = entry.split(":")
                if (parts.size == 2) {
                    val o = parts[0].toIntOrNull()
                    val v = parts[1].toIntOrNull()
                    if (o != null && v != null) splitVolume(o, v)
                }
            }
            _splitVolumeOps.value = saved.splitVolumeOperations
            snapshotLoadTimePoses()
            for ((idx, pose) in saved.perObjectPoses) {
                setObjectRotation(idx, pose.rotXDeg, pose.rotYDeg, pose.rotZDeg)
                setObjectScale(idx, pose.scaleX, pose.scaleY, pose.scaleZ)
            }
            for ((key, slot) in saved.perVolumeExtruders) {
                val parts = key.split(":")
                if (parts.size == 2) {
                    val o = parts[0].toIntOrNull()
                    val v = parts[1].toIntOrNull()
                    if (o != null && v != null) setVolumeExtruder(o, v, slot)
                }
            }
            val positions = saved.customObjectPositions
            val tower = saved.customWipeTowerPos
            if (positions != null && tower != null) {
                applyPlacementPositions(positions, tower)
            }
            // F77 additional files NOT restored in silent mode (addModelFromFile
            // touches _state). v2.6.0 trade-off; can extend in a follow-up.
            if (saved.additionalFiles.isNotEmpty()) {
                Log.w("SlicerVM", "F89 silent restore: ${saved.additionalFiles.size} additional file(s) not restored (deferred)")
                _toastEvents.tryEmit("${saved.additionalFiles.size} added file(s) weren't restored — re-add them to the bed")
            }
            Log.i("SlicerVM", "F89 silent restore complete for ${saved.modelName}")
        } finally {
            _silentRestoreInProgress.value = false
        }
    }

    /** F89: user tapped × on the banner. Clear the offer and the DataStore entry. */
    fun dismissSessionResume() {
        _sessionResumeOffer.value = null
        viewModelScope.launch {
            try { sessionStateRepository.clear() } catch (e: Exception) {
                Log.w("SlicerVM", "F89 dismiss-clear failed: ${e.message}")
            }
        }
    }

    /** F89: replay the saved session. Runs on the caller's coroutine (already
     *  in viewModelScope). Suspends through the existing loading paths. */
    private suspend fun restoreSession(saved: SessionState, raw: File) {
        // 1. Trigger the standard load. loadModelFromFile launches its own
        //    coroutine; we observe _state to know when it completes.
        loadModelFromFile(raw, preserveDisplayName = saved.modelName)
        if (!awaitLoadCompletion()) {
            return // Error state — leave session in place so user can retry
        }
        // 2. Multi-plate handling: if the load paused on the plate-selector
        //    dialog, dismiss it programmatically and select the saved plate
        //    (or the firstBambuPlateId / first available as a fallback).
        if (_showPlateSelector.value) {
            _showPlateSelector.value = false
            val plate = saved.selectedPlateId
                ?: recoveryPlateId.takeIf { it > 0 }
                ?: _multiPlatePlates.value.firstOrNull()?.plateId
            if (plate == null) {
                Log.w("SlicerVM", "F89 restore: multi-plate file but no plate to select")
                return
            }
            selectPlate(plate)
            if (!awaitLoadCompletion()) return
        } else {
            // Single-plate path. If a saved plate id matches the available
            // plates, re-select it (handles edge cases like the user having
            // switched plates within a single-plate-looking file).
            val plateId = saved.selectedPlateId
            if (plateId != null && _multiPlatePlates.value.any { it.plateId == plateId }) {
                selectPlate(plateId)
                if (!awaitLoadCompletion()) return
            }
        }
        // 3. Additional files (F77). Skip missing ones rather than fail the whole restore.
        for (entry in saved.additionalFiles) {
            val f = File(entry.path)
            if (!f.exists()) {
                Log.w("SlicerVM", "F89 restore: skipping missing additional file ${entry.path}")
                continue
            }
            if (entry.plateIdx >= 0) addModelFromFileForPlate(f, entry.plateIdx)
            else addModelFromFile(f)
            if (!awaitLoadCompletion()) return
        }
        // 4. Scale / rotation / copies. These reset customObjectPositions —
        //    OK because we apply placement positions AFTER all F66 replays
        //    (per-object pose replay also clobbers customObjectPositions via
        //    refreshObjectGeometryAfterPoseChange).
        setModelScale(ModelScale(
            saved.modelScale.first, saved.modelScale.second, saved.modelScale.third
        ))
        setModelRotation(ModelRotation(
            saved.modelRotation.first, saved.modelRotation.second, saved.modelRotation.third
        ))
        setCopyCount(saved.copyCount)
        // ---- F66 restore (review-2026-05-30 P0 — runs BEFORE applyPlacementPositions) ----
        // Order matters: split ops first (they change object count and re-index
        // everything), then volume splits, then per-object pose, then per-volume
        // extruders, then selection. Pose replay calls setObjectScale which fires
        // refreshObjectGeometryAfterPoseChange — that writes worldMins to
        // customObjectPositions, so applyPlacementPositions must come AFTER pose
        // replay or the saved drag positions get overwritten with auto-mins.
        for (idx in saved.splitObjectOperations) {
            splitObject(idx)  // each call appends to _splitObjectOps; that's the same list we just replayed,
                              // so blank it before iterating and let splitObject rebuild it from the replay.
        }
        // splitObject() pushed onto _splitObjectOps during replay — replace
        // the live list with the canonical persisted one to avoid duplicates.
        _splitObjectOps.value = saved.splitObjectOperations
        for (entry in saved.splitVolumeOperations) {
            val parts = entry.split(":")
            if (parts.size == 2) {
                val o = parts[0].toIntOrNull()
                val v = parts[1].toIntOrNull()
                if (o != null && v != null) splitVolume(o, v)
            }
        }
        _splitVolumeOps.value = saved.splitVolumeOperations
        // Per-object pose — also captures load-time baselines first so Reset
        // works (snapshotLoadTimePoses reads native; the pose has not been
        // mutated by the saved state yet because the loadModelFromFile +
        // splits above produced the same model state).
        snapshotLoadTimePoses()
        for ((idx, pose) in saved.perObjectPoses) {
            setObjectRotation(idx, pose.rotXDeg, pose.rotYDeg, pose.rotZDeg)
            setObjectScale(idx, pose.scaleX, pose.scaleY, pose.scaleZ)
        }
        // Per-volume extruder assignments.
        for ((key, slot) in saved.perVolumeExtruders) {
            val parts = key.split(":")
            if (parts.size == 2) {
                val o = parts[0].toIntOrNull()
                val v = parts[1].toIntOrNull()
                if (o != null && v != null) setVolumeExtruder(o, v, slot)
            }
        }
        // 5. Custom placement — last so it overrides the per-object pose
        // replay's customObjectPositions side-effect.
        val positions = saved.customObjectPositions
        val tower = saved.customWipeTowerPos
        if (positions != null && tower != null) {
            applyPlacementPositions(positions, tower)
        }
        // Selection — non-null even if the object is gone (defensive: clamp to current count).
        val objCount = native.nativeGetObjectCount()
        val selObj = saved.selectedObjectIndex
        if (selObj != null && selObj in 0 until objCount) {
            selectObject(selObj)
            saved.selectedVolumeIndex?.let { selectVolume(it) }
        }
        // 6. Post-slice restore: if the saved session was past slicing AND the
        //    SliceJob row still exists in Room with a valid gcode file, restore
        //    the SliceComplete state and emit a navigate-to-Preview event.
        if (saved.wasSliceComplete && saved.sliceJobId != null) {
            val row = try {
                sliceJobDao.getAll().first().firstOrNull { it.id == saved.sliceJobId }
            } catch (e: Exception) {
                Log.w("SlicerVM", "F89 restore: SliceJob lookup failed: ${e.message}")
                null
            }
            if (row != null) {
                val gcode = File(row.gcodePath)
                if (gcode.exists()) {
                    lastSliceJobId = row.id
                    _state.value = SlicerState.SliceComplete(
                        SliceResult(
                            success = true,
                            cancelled = false,
                            errorMessage = "",
                            gcodePath = row.gcodePath,
                            totalLayers = row.totalLayers,
                            estimatedTimeSeconds = row.estimatedTimeSeconds,
                            estimatedFilamentMm = row.estimatedFilamentMm,
                            estimatedFilamentGrams = row.estimatedFilamentGrams,
                        )
                    )
                    // Also repopulate gcodePreview so the Preview page has its
                    // summary text rendered. We don't have native's gcode loaded
                    // here, so read the first 50 lines from the file directly.
                    _gcodePreview.value = try {
                        gcode.bufferedReader().use { reader ->
                            val sb = StringBuilder()
                            var line: String? = reader.readLine()
                            var n = 0
                            while (line != null && n < 50) {
                                sb.appendLine(line)
                                line = reader.readLine()
                                n++
                            }
                            sb.toString()
                        }
                    } catch (_: Exception) { "" }
                    _navigateEvents.tryEmit("preview")
                } else {
                    Log.w("SlicerVM", "F89 restore: SliceJob ${row.id} gcode missing at ${row.gcodePath}")
                }
            } else {
                Log.w("SlicerVM", "F89 restore: sliceJobId=${saved.sliceJobId} not found in Room")
            }
        }
    }

    /** F89 silent-mode completion. Suspends until the next _silentLoadCompleted
     *  emission (or timeout). Returns false on timeout or explicit-false emission. */
    private suspend fun awaitSilentLoadCompletion(timeoutMs: Long = 180_000): Boolean {
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            _silentLoadCompleted.first()
        } ?: false
    }

    /** F89 restore helper: suspend until the most recent mutator's loading
     *  cycle settles, OR until the plate selector becomes visible (which is
     *  a non-error pause; caller handles it). */
    private suspend fun awaitLoadCompletion(timeoutMs: Long = 120_000): Boolean {
        val startSnapshot = _state.value
        val outcome = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            kotlinx.coroutines.flow.combine(_state, _showPlateSelector) { s, sel ->
                Triple(s, sel, s !== startSnapshot && s !is SlicerState.Loading)
            }.first { (_, selectorShown, stateLeftLoading) ->
                stateLeftLoading || selectorShown
            }
        } ?: return false
        // Treat plate-selector-shown as a non-error pause (caller handles it);
        // otherwise the state itself decides.
        return outcome.second || outcome.first !is SlicerState.Error
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
        setParsedGcodeWithRangeReset(null)
        _lastSliceResult = null
        _excludeObjects.value = emptyList()
        _activeExtruderColors.value = emptyList()
        _selectedExtruder.value = 0
        _threeMfInfo.value = null
        _fileThreeMfInfo = null
        _multiPlatePlates.value = emptyList()
        _multiPlateSourceFile = null
        _showPlateSelector.value = false
        _showMultiColorDialog.value = false
        // Phase 2 (2026-04-28) cross-cutting agent finding: clear per-filament
        // overrides on model close. Without this, an override on file A's
        // filament 0 silently leaks into file B when the user opens a new
        // file (the StateFlow value persists across model loads).
        _filamentOverrides.value = emptyMap()
        currentModelFile = null
        lastModelInfo = null
        _modelInfo.value = null
        _copyCount.value = 1
        customObjectPositions = null
        customWipeTowerPos = null
        additionalModelFiles.clear()
        hasMultipleDistinctObjectsVar = false
        _objectBoundingBoxes.value = floatArrayOf()
        _multiObjectPositions.value = null
        _pendingAddFile.value?.copiedFile?.delete()
        _pendingAddFile.value = null
        // F66 — same teardown as beginNewModelLoad. clearModel runs on the
        // "X" close button + on app teardown; without this the next model
        // load would see stale selection / split history / per-volume overrides.
        _selection.value = ObjectSelection()
        _perObjectPoses.value = emptyMap()
        _loadTimePoses.value = emptyMap()
        _perVolumeExtruders.value = emptyMap()
        _splitObjectOps.value = emptyList()
        _splitVolumeOps.value = emptyList()
        resetToolRemapState()
        // Reset multi-extruder config to single extruder
        _config.value = _config.value.copy(
            extruderCount = 1,
            extruderTemps = intArrayOf(),
            extruderRetractLength = floatArrayOf(),
            extruderRetractSpeed = floatArrayOf(),
            wipeTowerEnabled = false
        )
        viewModelScope.launch {
            try {
                sessionStateRepository.clear()
            } catch (e: Exception) {
                Log.w("SlicerVM", "F89 session clear failed: ${e.message}")
            }
        }
        _sessionResumeOffer.value = null
        lastSliceJobId = null
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

    /**
     * Phase 2 (2026-04-28, revised after adversarial review) — produces
     * a print-ready copy of the sliced G-code at [destFile] by routing
     * through [com.u1.slicer.gcode.resolveCanonicalExportMapping] +
     * [com.u1.slicer.gcode.applyPrintTimeRemap]. Phase 2 emits T-indices
     * in canonical-fileIndex space (e.g. T4-T9 for 10-wide canonical
     * lists); the U1 firmware only understands physical slots T0-T3, so
     * any export destined for the printer (Save, Share, Jobs share,
     * manual upload) must remap before leaving the app. Returns true if
     * the file was produced; false if the source is missing.
     *
     * The resolver covers the four input cases (full canonical mapping,
     * plate-narrowed mapping, single-colour with selected slot, no
     * canonical context). Initial yesterday's fix only handled the
     * first; the adversarial review caught the gaps in cases 2 + 3.
     *
     * Spec: `docs/superpowers/specs/2026-04-28-canonical-export-mapping-helper-design.md`.
     */
    internal fun prepareExportableGcode(sourceFile: File, destFile: File): Boolean =
        prepareExportableGcodeWithMapping(sourceFile, destFile, resolveExportMapping())

    /**
     * Resolves the current canonical-fileIndex → physical-slot mapping
     * for export. Reads `_canonicalFilamentList`, `_colorMapping`, and
     * `_selectedExtruder`. See [com.u1.slicer.gcode.resolveCanonicalExportMapping]
     * for the four-case logic.
     */
    internal fun resolveExportMapping(): List<Int>? {
        val canonical = _canonicalFilamentList.value
        // Phase 2 (post-round-2-review, Reviewer 3 P1; tightened
        // post-round-3-review for F2 fallback gap) — supply
        // confirmedMappingFileIndices when the live `_colorMapping` is
        // plate-narrowed (multi-plate file post-selectPlate). Without
        // it the resolver assumes positional `mapping[i] → fileIdx i`,
        // which is wrong for plates whose filaments don't start at
        // canonical fileIdx 0.
        //
        // Two sources, in priority order:
        //   1. plate.filamentIndices (1-indexed Bambu → 0-indexed
        //      canonical). The richest source when present.
        //   2. _threeMfInfo.usedExtruderIndices (1-indexed plate-
        //      narrowed extruder set). Reviewer 3 round 3 caught the
        //      original fix's blind spot: legacy/recovery paths can
        //      have empty `plate.filamentIndices`. Using sorted
        //      `usedExtruderIndices` gives the canonical fileIndices
        //      for the plate's filaments in sorted-ascending order,
        //      matching how detectedColors are produced for those
        //      plates.
        //
        // Only supply the keys when the mapping is actually plate-
        // narrowed (size < canonicalSize), otherwise the canonical-
        // aligned positional path is correct.
        val confirmed = _colorMapping.value
        val canonicalSize = canonical?.size ?: 0
        val mappingFileIndices: List<Int>? = if (
            !confirmed.isNullOrEmpty() &&
            confirmed.size < canonicalSize
        ) {
            val info = _threeMfInfo.value
            val plateId = recoveryPlateId.takeIf { it >= 0 }
            val plate = plateId?.let {
                info?.plates?.firstOrNull { p -> p.plateId == it }
            }
            val fromPlate = plate?.filamentIndices
                ?.map { it - 1 }  // 1-indexed Bambu → 0-indexed canonical
                ?.filter { it in 0 until canonicalSize }
                ?.takeIf { it.size == confirmed.size }
            // Fallback to usedExtruderIndices when plate.filamentIndices
            // is missing or wrong size.
            fromPlate ?: info?.usedExtruderIndices
                ?.filter { it > 0 }
                ?.sorted()
                ?.map { it - 1 }  // 1-indexed → 0-indexed canonical
                ?.filter { it in 0 until canonicalSize }
                ?.takeIf { it.size == confirmed.size }
        } else null

        return com.u1.slicer.gcode.resolveCanonicalExportMapping(
            canonicalSize = canonicalSize,
            confirmedMapping = confirmed,
            selectedExtruder = _selectedExtruder.value,
            confirmedMappingFileIndices = mappingFileIndices,
        )
    }

    /**
     * Layer-2 file IO with an externally supplied [mapping]. Used by
     * the Send flow (which carries its own dialog-confirmed mapping)
     * and by the resolver-driven [prepareExportableGcode] above.
     *
     * Null/empty mapping → identity copy (no T-index rewrite). Caller
     * is responsible for checking [sourceFile] existence before
     * calling; we re-check defensively and return false on absence.
     */
    internal fun prepareExportableGcodeWithMapping(
        sourceFile: File,
        destFile: File,
        mapping: List<Int>?,
    ): Boolean {
        if (!sourceFile.exists()) return false
        if (mapping.isNullOrEmpty()) {
            sourceFile.copyTo(destFile, overwrite = true)
        } else {
            com.u1.slicer.gcode.applyPrintTimeRemap(
                sourceGcodePath = sourceFile.absolutePath,
                outputPath = destFile.absolutePath,
                colorMapping = mapping,
            )
        }
        return true
    }

    fun shareGcode() {
        val result = (_state.value as? SlicerState.SliceComplete)?.result ?: _lastSliceResult
        if (result == null) {
            _toastEvents.tryEmit("Share G-code failed: no completed slice available")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            // Foreground service for the same reason as saveGcodeTo: the remap of a
            // large G-code can outlast the freezer's background grace period.
            val longOpCtx = beginLongOp("Preparing G-code")
            try {
                val sourceFile = File(result.gcodePath)
                val baseName = com.u1.slicer.data.ModelFileNaming.baseName(
                    currentModelName.ifBlank { null }, sourceFile.name
                )
                val shareFile = File(
                    sourceFile.parentFile,
                    "$baseName.share.gcode"
                )
                if (!prepareExportableGcode(sourceFile, shareFile)) {
                    _toastEvents.tryEmit("Share G-code failed: sliced file is missing")
                    return@launch
                }

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    shareFile
                )

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                withContext(Dispatchers.Main) {
                    context.startActivity(
                        Intent.createChooser(intent, "Share G-code").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            } finally {
                endLongOp(longOpCtx)
            }
        }
    }

    fun saveGcodeTo(uri: Uri) {
        // Prefer the live SliceComplete state, but fall back to the cached
        // _lastSliceResult — the Android file picker has already created the
        // destination as a 0-byte file before this callback runs, so a silent
        // early-return leaves a useless empty file. The cache lets the save
        // succeed even when a background plate-selector / selectPlate race has
        // flipped _state away from SliceComplete during the picker round-trip.
        val liveResult = (_state.value as? SlicerState.SliceComplete)?.result
        val result = liveResult ?: _lastSliceResult
        if (result == null) {
            val stateName = _state.value::class.simpleName ?: "Unknown"
            Log.e("SaveGcode", "saveGcodeTo: no SliceResult available (state=$stateName)")
            _toastEvents.tryEmit("Save G-code failed: no completed slice available")
            diagnostics.recordEvent(
                "save_gcode_failed",
                mapOf("reason" to "no_slice_result", "state" to stateName)
            )
            return
        }
        val usedCache = liveResult == null
        if (usedCache) {
            Log.w("SaveGcode", "saveGcodeTo: using cached _lastSliceResult (state=${_state.value::class.simpleName})")
        }

        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            // Hold a foreground service for the duration. The remap + copy of a
            // large G-code (272 MB H2C plates take ~80 s) would otherwise be killed
            // by Android's cached-app freezer the moment the user backgrounds the
            // app (e.g. to check the saved file in Drive), leaving the picker's
            // pre-created 0-byte destination empty. See SaveGcodeStateResilienceTest.
            val longOpCtx = beginLongOp("Saving G-code")
            val sourceFile = File(result.gcodePath)
            val srcExists = sourceFile.exists()
            val srcLen = if (srcExists) sourceFile.length() else -1L
            Log.i("SaveGcode", "saveGcodeTo: src=${sourceFile.absolutePath} exists=$srcExists len=$srcLen usedCache=$usedCache uri=$uri")
            val exportFile = File(
                sourceFile.parentFile,
                "${sourceFile.nameWithoutExtension}.export.${sourceFile.extension}"
            )
            try {
                if (!prepareExportableGcode(sourceFile, exportFile)) {
                    Log.e("SaveGcode", "saveGcodeTo: prepareExportableGcode returned false (src missing or empty)")
                    _toastEvents.tryEmit("Save G-code failed: sliced file is missing")
                    diagnostics.recordEvent(
                        "save_gcode_failed",
                        mapOf(
                            "reason" to "prepare_returned_false",
                            "srcPath" to sourceFile.absolutePath,
                            "srcExists" to srcExists,
                            "srcLen" to srcLen,
                            "usedCache" to usedCache
                        )
                    )
                    return@launch
                }
                val exportLen = exportFile.length()
                Log.i("SaveGcode", "saveGcodeTo: prepared export file len=$exportLen")
                val out = context.contentResolver.openOutputStream(uri, "wt")
                if (out == null) {
                    Log.e("SaveGcode", "saveGcodeTo: contentResolver.openOutputStream returned null for uri=$uri")
                    _toastEvents.tryEmit("Save G-code failed: could not open destination")
                    diagnostics.recordEvent(
                        "save_gcode_failed",
                        mapOf("reason" to "open_output_stream_null", "uri" to uri.toString())
                    )
                    return@launch
                }
                val bytesCopied = out.use { sink ->
                    exportFile.inputStream().use { it.copyTo(sink) }
                }
                Log.i("SaveGcode", "saveGcodeTo: success bytesCopied=$bytesCopied")
                diagnostics.recordEvent(
                    "save_gcode_success",
                    mapOf(
                        "bytesCopied" to bytesCopied,
                        "srcLen" to srcLen,
                        "exportLen" to exportLen,
                        "usedCache" to usedCache
                    )
                )
                exportFile.delete()
            } catch (t: Throwable) {
                Log.e("SaveGcode", "saveGcodeTo: exception src=${sourceFile.absolutePath} srcLen=$srcLen", t)
                _toastEvents.tryEmit("Save G-code failed: ${t.message ?: t.javaClass.simpleName}")
                diagnostics.recordEvent(
                    "save_gcode_failed",
                    mapOf(
                        "reason" to "exception",
                        "exception" to t.javaClass.simpleName,
                        "message" to (t.message ?: ""),
                        "srcPath" to sourceFile.absolutePath,
                        "srcExists" to srcExists,
                        "srcLen" to srcLen,
                        "usedCache" to usedCache,
                        "uri" to uri.toString()
                    )
                )
            } finally {
                endLongOp(longOpCtx)
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

    // ---- Phase 1: Native-first plate state reading ----

    /**
     * Read plate state FROM native after loadModel/loadModelForPlate.
     * Single source of truth — replaces [buildSelectedPlateInfo] for Bambu multi-plate files.
     * Caller MUST have already loaded the model into native.
     *
     * Acquires [NativeLibrary.previewMutex] for the JNI accessors.
     */
    private suspend fun readPlateStateFromNative(): NativePlateState {
        val state = NativeLibrary.previewMutex.withLock {
            val json = native.nativeGetAllVolumeExtruders()
            val parsed = NativePlateState.parseVolumeMapJson(json)

            // Phase 2 (2026-04-28, post-adversarial-review; tightened
            // 2026-04-28 evening after Buzz cold-load regression) —
            // probe `nativeGetPaintStateCounts` selectively:
            //
            //   - When `vol.isMmPainted` is true → probe (we know the
            //     volume has paint data, just need to enumerate states).
            //   - When `parsed.hasPaintData` is FALSE at the file level
            //     → probe every volume regardless. This covers the
            //     documented SEMM-without-isMmPainted edge case from
            //     `PlateStateEnrichment.kt` (slip-slide-spin plate 3
            //     canary): paint data encoded on individual triangles
            //     rather than marked at the volume level.
            //   - When `parsed.hasPaintData` is TRUE and `isMmPainted`
            //     is false → SKIP. The file-level flag means at least
            //     one painted volume was found; un-flagged volumes
            //     contribute no NEW paint states. The earlier
            //     "always probe" version doubled JNI calls on Buzz
            //     (large multi-plate file with mixed isMmPainted
            //     volumes), pushing cold-load over its 90s gate.
            //
            // Cost: when `parsed.hasPaintData=true` we probe only the
            // flagged subset (matches pre-fix behaviour). When
            // `parsed.hasPaintData=false` we probe everything (matches
            // the F5 widening, preserving the SEMM canary).
            val paintExtruders = mutableSetOf<Int>()
            for (obj in parsed.objects) {
                for (vol in obj.volumes) {
                    if (!vol.isMmPainted && parsed.hasPaintData) continue
                    // kind=0 is MMU paint segmentation
                    val counts = native.nativeGetPaintStateCounts(
                        obj.objectIndex, vol.volumeIndex, 0
                    ) ?: continue
                    // counts is [state1, count1, state2, count2, ...]
                    for (k in counts.indices step 2) {
                        if (k + 1 >= counts.size) break
                        val paintState = counts[k]
                        if (paintState > 0 && counts[k + 1] > 0) {
                            paintExtruders.add(paintState)
                        }
                    }
                }
            }
            if (paintExtruders.isEmpty()) {
                parsed
            } else {
                parsed.copy(
                    usedExtruders = (parsed.usedExtruders + paintExtruders).toSortedSet(),
                    // Promote hasPaintData if any volume actually carried
                    // paint data, even when no volume's `isMmPainted`
                    // flag was set — downstream consumers
                    // (buildThreeMfInfoFromNative, B81 plate-classification,
                    // canonical-list construction) read this flag and
                    // skipping it leaves SEMM plates classified as
                    // non-painted.
                    hasPaintData = true,
                )
            }
        }
        Log.i("SlicerVM", "readPlateStateFromNative: usedExtruders=${state.usedExtruders}, " +
            "hasPaint=${state.hasPaintData}, objects=${state.objects.size}")
        return state
    }

    /**
     * Build a [ThreeMfInfo] for UI consumers from native-reported plate state.
     *
     * Phase 2 §4 (2026-04-27 architectural fix) — canonical-driven plate
     * narrowing. When [canonical] is non-null (Bambu / OrcaSlicer 3MF), the
     * plate's `detectedColors` are looked up directly from
     * [com.u1.slicer.data.CanonicalFilamentList.filaments] keyed by the
     * fileIndices actually used on this plate. The set of used fileIndices is
     * derived from the union of:
     *   - native `usedExtruders` (1-based, includes paint states folded
     *     by `readPlateStateFromNative`);
     *   - source plate's per-object / per-part / filament / layer-tool
     *     extruders (1-based, from source-file metadata).
     *
     * This replaces the previous heuristic narrowing pass (~150 lines of
     * layer-tool-only / Hueforge / per-plate-paint-data branching) — the
     * canonical list already absorbs B95 high-index paint states, AMS2 fold,
     * compound-object per-part palettes, and synthesised LAYER_TOOL entries.
     *
     * When [canonical] is null (non-Bambu fallback / canonical load failed),
     * falls back to file-wide [ThreeMfInfo.detectedColors].
     */
    private fun buildThreeMfInfoFromNative(
        fileInfo: ThreeMfInfo,
        nativeState: NativePlateState,
        plateId: Int,
        canonical: com.u1.slicer.data.CanonicalFilamentList?,
    ): ThreeMfInfo {
        val usedExtruders = nativeState.usedExtruders
        val objExtruderMap = nativeState.buildObjectExtruderMap()
        val sourcePlate = fileInfo.plates.firstOrNull { it.plateId == plateId }

        val plateHasPaintData = nativeState.hasPaintData
        val plateHasLayerToolChanges = sourcePlate?.hasLayerToolChanges ?: fileInfo.hasLayerToolChanges

        // Source-plate per-part extruders. For Bambu compound objects (one
        // <object> with many <part> children on different extruders), the
        // object-level objectExtruderMap collapses to the object's default
        // extruder; objectPartExtruders captures the full per-part palette.
        @Suppress("DEPRECATION")
        val sourcePlateObjectExtruders = sourcePlate?.objectIds
            ?.flatMap { objectId ->
                val perPart = fileInfo.objectPartExtruders[objectId]
                if (!perPart.isNullOrEmpty()) perPart
                else listOfNotNull(fileInfo.objectExtruderMap[objectId])
            }
            ?.filter { it > 0 }
            ?.toSet()
            ?: emptySet()

        // v2.0.0 systematic fix (Shashibo plate 5 / flippy plate 4 reports).
        // Architectural principle for "what extruders does this plate use":
        //   1. Native is authoritative when post-load — it ran the BBS
        //      importer and knows what the slicer will actually emit.
        //   2. Pre-load metadata (sourcePlate.layerToolExtruders,
        //      .filamentMapSlots, sourcePlateObjectExtruders) can include
        //      phantom extruders that the slicer ignores — Shashibo plate 5:
        //      native=[1,2] is correct; layer-tool metadata=[2,3] adds
        //      phantom 3 → pre-fix showed 3 chips for a 2-filament print.
        //   3. Painted plates: per-object default extruder IS the BACKGROUND
        //      for unpainted geometry — slip-slide-spin plate 3 native=[2,3,4]
        //      paint-only; object default {1} provides base → 4 actual.
        //   4. Some layer-tool plates produce native=[] (empty) — the BBS
        //      importer doesn't surface per-volume data for pure layer-tool
        //      plates with no painted regions (flippy plate 4 Hueforge). Fall
        //      back to layer-tool metadata when native is empty.
        val enrichedExtruders = if (fileInfo.hasLayerToolChanges && sourcePlate != null) {
            // Layer-tool files: native (post-load BBS importer) is authoritative
            // when it has data because it processes the full layer-change
            // script and reports the actual extruders the slicer will emit.
            // Per-plate metadata can include phantom extruders the slicer
            // ignores — Shashibo plate 5: native=[1,2] correct; layer-tool
            // metadata=[2,3] phantom 3. Pre-fix added the phantom → 3 chips
            // for a 2-filament print.
            //
            // BUT some layer-tool plates produce native=[] (empty) — the
            // BBS importer doesn't surface per-volume data for pure
            // layer-tool plates with no painted regions (flippy plate 4
            // Hueforge). When native is empty, fall back to layer-tool
            // metadata (with the phantom risk).
            //
            // H3 from post-Buzz code review (2026-04-30): when native is
            // non-empty we drop `sourcePlateObjectExtruders` entirely. For a
            // hypothetical layer-tool plate where the per-object default is
            // the BACKGROUND for unpainted regions AND that extruder doesn't
            // appear in any layer-tool entry, we'd silently lose it. None of
            // the current fixtures (Shashibo plate 5, flippy plate 4, slip-
            // slide-spin plate 3) hit that combination, so the divergence is
            // not currently observable. If a future fixture surfaces a layer-
            // tool plate with `objectExtruder ∉ native ∪ layerToolExtruders`,
            // restore the union here. Adding a synthetic regression test
            // would require either refactoring this branch into an `internal`
            // helper or adding instrumented coverage on a custom-built 3MF.
            if (usedExtruders.isNotEmpty()) {
                if (plateHasPaintData) {
                    (usedExtruders +
                        sourcePlate.paintExtruderStates.filter { it > 0 } +
                        sourcePlate.filamentMapSlots.filter { it > 0 } +
                        sourcePlateObjectExtruders).toSortedSet()
                } else {
                    usedExtruders.toSortedSet()
                }
            } else {
                val ltExtruders = sourcePlate.layerToolExtruders.filter { it > 0 }
                val filExtruders = sourcePlate.filamentMapSlots.filter { it > 0 }
                (ltExtruders + filExtruders + sourcePlateObjectExtruders).toSortedSet()
            }
        } else if (sourcePlateObjectExtruders.isNotEmpty()) {
            // Painted / per-object multi-extruder plates: per-object default
            // is the BACKGROUND extruder for unpainted geometry (e.g. slip-
            // slide-spin plate 3 native [2,3,4] + object default {1} → 4
            // physical extruders actually used). Adding it is correct here.
            // B120: also union filamentMapSlots — non-layer-tool plates can
            // declare multiple AMS slots in filament_maps that aren't captured
            // by native per-object extruder assignments (jons-bug plate 2:
            // native+objectMap gives TPU only; PETG lives in filamentMapSlots).
            val filExtruders = sourcePlate?.filamentMapSlots?.filter { it > 0 } ?: emptyList()
            (usedExtruders + sourcePlateObjectExtruders + filExtruders).toSortedSet()
        } else {
            // B120: same fix for the bare fallback path (no object extruder map,
            // no layer-tool — filament_maps still declares additional AMS slots).
            val filExtruders = sourcePlate?.filamentMapSlots?.filter { it > 0 } ?: emptyList()
            (usedExtruders + filExtruders).toSortedSet()
        }

        // Canonical-driven narrowing: convert each 1-based extruder/paint-state
        // to a 0-based fileIndex and look up the colour in the canonical list.
        // paintStateMap entries are equivalent to (state - 1) for currently-
        // declared states, so the simple `ext - 1` translation works for both
        // plain extruders and paint states.
        val narrowedColors: List<String> = if (canonical != null && canonical.size > 0) {
            val usedFileIndices = enrichedExtruders.mapNotNull { ext ->
                val idx = canonical.paintStateMap[ext] ?: (ext - 1)
                idx.takeIf { it in 0 until canonical.size }
            }.toSortedSet()
            usedFileIndices.map { canonical.filaments[it].color }
        } else if (enrichedExtruders.isNotEmpty() && fileInfo.detectedColors.size > 1) {
            // Non-Bambu / canonical load failed: fall back to fileInfo palette.
            enrichedExtruders.sorted().mapNotNull { idx ->
                fileInfo.detectedColors.getOrNull(idx - 1)
            }.ifEmpty { fileInfo.detectedColors }
        } else {
            fileInfo.detectedColors
        }

        Log.i("SlicerVM", "buildThreeMfInfoFromNative: plate=$plateId native=$usedExtruders " +
            "enriched=$enrichedExtruders hasPaint=$plateHasPaintData " +
            "canonicalSize=${canonical?.size ?: 0} narrowedColors=$narrowedColors")

        // For SEMM paint and compound-object plates, keep detectedColors as
        // narrowed (canonical-driven) for the UI chip display, but the slicer-
        // facing `filament_colour` palette stays file-wide via embed (handled
        // separately by ProfileEmbedder). UI consumers read detectedColors;
        // canonical narrowing is the right shape there.
        //
        // Layer-tool-only plates also use narrowedColors — canonical's
        // `mergeLayerToolData` already synthesises LAYER_TOOL entries for any
        // referenced extruder beyond the FILE_COLOUR base, so the canonical
        // lookup yields exactly the colours the plate uses.
        val paletteColors = narrowedColors.ifEmpty { fileInfo.detectedColors }
        // detectedExtruderCount stays file-wide so the slicer's
        // `extruderCount` covers all canonical fileIndices referenced by
        // `model_settings.config` parts. Plates whose parts reference
        // high fileIndices (e.g. Buzz plate 8: parts on extruder=10) need
        // the slicer's extruder_count to span up to that index, otherwise
        // the slicer collapses high-fileIndex parts to T0 and emits no
        // tool changes. The slicer caps at 4 physical slots in the
        // multi-color block (`coerceIn(1, 4)`); the cap itself preserves
        // print-time slot constraints. UI chip display uses
        // `detectedColors.size` (= paletteColors.size, plate-narrowed)
        // independently.
        val paletteCount = maxOf(
            paletteColors.size,
            fileInfo.detectedExtruderCount
        ).coerceAtLeast(1)

        // §4 Step 8 — volumeExtruders carries the spatial-only diversity
        // (native-reported usedExtruders ∪ object-level map) so the derived
        // hasMultiExtruderAssignments matches the previous hasMultiExtAssign
        // semantic without relying on a stored boolean.
        val volumeExtrudersForPlate = (usedExtruders + objExtruderMap.values).filter { it > 0 }.toSet()
        return fileInfo.copy(
            plates = listOfNotNull(sourcePlate),
            detectedColors = paletteColors,
            usedExtruderIndices = enrichedExtruders,
            volumeExtruders = volumeExtrudersForPlate,
            detectedExtruderCount = paletteCount,
            hasPaintData = plateHasPaintData,
            objectExtruderMap = objExtruderMap,
            hasLayerToolChanges = plateHasLayerToolChanges,
            hasPaintSupports = fileInfo.hasPaintSupports
        )
    }

    companion object {
        /**
         * F81 (GitHub #120): "large" file gate for the ModelLoaded notification.
         * Files smaller than this load fast enough that a background-state
         * notification is more noise than signal.
         */
        internal const val LARGE_FILE_NOTIFY_BYTES: Long = 5L * 1024 * 1024

        /**
         * F81: trigger threshold for PreparePreviewReady. The decimator caps
         * the preview mesh around 500 K triangles; anything above that took
         * meaningful time to build and is worth notifying about.
         */
        internal const val PREVIEW_NOTIFY_TRIANGLE_THRESHOLD: Int = 500_000

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
            volumeExtruders = origInfo.volumeExtruders,  // §4 Step 8 (drives derived hasMultiExtruderAssignments)
            hasPaintData = origInfo.hasPaintData,
            hasLayerToolChanges = origInfo.hasLayerToolChanges,
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
                    ?.filamentMapSlots
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
                .flatMap { it.filamentMapSlots }
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
            //
            // Sub-plan #2c: prefer ThreeMfPlate.hasPaintData from sourceInfo (populated by
            // ThreeMfParser's per-plate visual-colour pass) so we don't rely on plateInfo
            // being the result of extractPlate + parseForPlateSelection on a single-plate
            // file. Fall back to plateInfo.hasPaintData for the legacy call-site shape.
            val plateHasPaintData = sourcePlate?.hasPaintData ?: plateInfo.hasPaintData
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
            // §4 Step 8 — translate the previous hasMultiExtruderAssignments
            // override semantic into volumeExtruders adjustment so the
            // derived predicate matches intent:
            //   sourcePlateObjectExtruders.size > 1   → force "true": ensure
            //                                            ≥2 distinct values
            //   isHueforgePlate                       → force "false": single
            //                                            value (drops layer-
            //                                            tool secondaries)
            //   else                                  → inherit source's
            //                                            volumeExtruders
            // Force the synthetic shape: original code used a List `.size > 1`
            // check, NOT a distinct-set check — so a plate with 2 objects both
            // on extruder 1 would also force "true". Synthetic {1, 2} preserves
            // that exact (potentially over-permissive) semantic.
            val mergedVolumeExtruders: Set<Int> = when {
                sourcePlateObjectExtruders.size > 1 -> setOf(1, 2)
                isHueforgePlate -> setOf(1)
                else -> sourceInfo.volumeExtruders
            }
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
                volumeExtruders = mergedVolumeExtruders,
                hasPaintData = plateHasPaintData,
                hasLayerToolChanges = sourceInfo.hasLayerToolChanges,
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

        /**
         * Phase 1 sub-plan #2c: build a single-plate [ThreeMfInfo] view of [sourceInfo]
         * without running `BambuSanitizer.extractPlate` + `parseForPlateSelection` on
         * disk. Consumed by [selectPlate] and [mergeThreeMfInfoForPlate].
         *
         * The returned info has:
         * - `plates` narrowed to the single selected plate (preserving its per-plate
         *   fields including `hasPaintData` so merge's layerToolOnly / B81 decision
         *   stays plate-local).
         * - `usedExtruderIndices` derived from the target plate's `objectIds` resolved
         *   against [sourceInfo].objectExtruderMap.
         * - `hasPaintData` copied from the selected plate's per-plate flag.
         * - All other fields inherited from [sourceInfo] (detectedColors, isBambu,
         *   hasLayerToolChanges, etc.) — plate-local narrowing is best-effort.
         *
         * Falls back to returning [sourceInfo] unchanged when the plate is not found.
         *
         * @deprecated Production now uses [readPlateStateFromNative] + [buildThreeMfInfoFromNative]
         * for the authoritative plate state. This method is retained for the embed-time
         * plate-scoped ThreeMfInfo (needed by [embedProfile]) and for test compatibility.
         */
        @Deprecated("Use readPlateStateFromNative + buildThreeMfInfoFromNative for UI state")
        internal fun buildSelectedPlateInfo(
            sourceInfo: com.u1.slicer.bambu.ThreeMfInfo,
            selectedPlateId: Int
        ): com.u1.slicer.bambu.ThreeMfInfo {
            val sourcePlate = sourceInfo.plates.firstOrNull { it.plateId == selectedPlateId }
                ?: return sourceInfo
            val platesNarrowed = listOf(sourcePlate)
            // Sub-plan #2c fix (Dragon Scale plate 3 regression): prefer per-object
            // all-extruders (captures per-part extruders from `<part>` children) over
            // the object-level default. Pre-#2c `restructurePlateFile` inlined parts as
            // separate objects, which made per-object-only lookups sufficient. Post-#2c
            // we consume the source file directly where a compound object's parts live
            // inside a single `<object>` and their extruders must be unioned explicitly.
            val plateObjectExtruders = sourcePlate.objectIds
                .flatMap { objectId ->
                    val perPart = sourceInfo.objectPartExtruders[objectId]
                    if (!perPart.isNullOrEmpty()) perPart
                    else listOfNotNull(sourceInfo.objectExtruderMap[objectId])
                }
                .filter { it > 0 }
                .toSet()
            // Sub-plan #2d (slip slide regression fix): for painted plates, union the
            // plate's paint extruder states (from ThreeMfPlate.paintExtruderStates,
            // populated at parse time by computeVisualColorCountByPlate) with object-
            // and filament-level extruders. This captures plates where extruders come
            // from paint decode rather than object-level metadata (e.g. slip slide
            // plate 3 = 1 object with 4 paint regions). Pre-#2d paint plates fell back
            // to file-level usedExtruderIndices which collapsed to {1} for SEMM files.
            // For non-painted plates, include sourcePlate.layerToolExtruders so
            // layer-tool-only extruders aren't dropped from the merged palette.
            val plateUsedExtruders = if (sourcePlate.hasPaintData) {
                (plateObjectExtruders +
                    sourcePlate.paintExtruderStates.filter { it > 0 } +
                    sourcePlate.filamentMapSlots.filter { it > 0 } +
                    sourcePlate.layerToolExtruders.filter { it > 0 }).toSortedSet()
                    .ifEmpty { sourceInfo.usedExtruderIndices }
            } else {
                (plateObjectExtruders +
                    sourcePlate.filamentMapSlots.filter { it > 0 } +
                    sourcePlate.layerToolExtruders.filter { it > 0 }).toSortedSet()
            }
            // Sub-plan #2c blocker fix: narrow objectExtruderMap to the selected plate's
            // objectIds only. Leaving the full-file map leaks other plates' extruders
            // into downstream consumers like computeSlicerColorOrder (painted-plate
            // dominance) and the preview palette, letting other plates influence the
            // selected plate's palette order.
            //
            // Sub-plan #2c fix (Dragon Scale plate 3): compound-object part entries
            // live in objectExtruderMap keyed by partId, not objectId — and partIds
            // don't appear in any plate's objectIds (they're inside the parent
            // object). Keep a part iff its parent object is in the selected plate.
            val plateObjectIdSet = sourcePlate.objectIds.toSet()
            val plateScopedObjectExtruderMap = sourceInfo.objectExtruderMap
                .filterKeys { key ->
                    key in plateObjectIdSet ||
                        sourceInfo.compoundPartParents[key]?.let { it in plateObjectIdSet } == true
                }
            return sourceInfo.copy(
                plates = platesNarrowed,
                usedExtruderIndices = plateUsedExtruders,
                hasPaintData = sourcePlate.hasPaintData,
                hasLayerToolChanges = sourcePlate.hasLayerToolChanges || sourceInfo.hasLayerToolChanges,
                objectExtruderMap = plateScopedObjectExtruderMap
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        cancelPendingAdd()
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
    slotCount: Int,
    filamentCount: Int = slotCount,
    hasSourceConfig: Boolean = false,
    filamentTypes: List<String>? = null,
    nozzleTemps: List<Int>? = null,
    filamentColours: List<String>? = null,
): Map<String, Any> {
    // Phase 2 §4 Step 2 — `slotCount` is the number of physical extruder
    // slots used (capped at 4 for U1); `filamentCount` is the file's
    // filament-list size (uncapped). Per-filament arrays
    // (nozzle_temperature, filament_type) are sized to `filamentCount`.
    // Per-slot arrays (wipe_tower_x/y, prime_tower) are sized to
    // `slotCount`. Mixing the two is the root smell behind the §1 bug
    // class — keep them separate at every site below.
    //
    // nozzleTemps (fresh from slice-time preset lookup) takes priority
    // over the stale cfg.extruderTemps stored at model-load time. Sized
    // to the canonical filament list when supplied; the slot-cap fallback
    // chain only fires when no per-filament array reached us.
    val temps: MutableList<String> = when {
        nozzleTemps != null ->
            nozzleTemps.map { it.toString() }.toMutableList()
        cfg.extruderTemps.size >= filamentCount ->
            cfg.extruderTemps.take(filamentCount).map { it.toString() }.toMutableList()
        else ->
            MutableList(filamentCount) { cfg.nozzleTemp.toString() }
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
    val effectiveFilamentCount = maxOf(filamentCount, supportFilament, supportInterfaceFilament)
    while (temps.size < effectiveFilamentCount) {
        temps.add(nozzleTemps?.getOrNull(temps.size)?.toString() ?: cfg.nozzleTemp.toString())
    }
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
    val primeTower = ov.resolvePrimeTower(slotCount, cfg.wipeTowerEnabled)

    val primeVolume = resolve(ov.primeVolume, 45, "primeVolume")
    val primeTowerBrimWidth = resolve(ov.primeTowerBrimWidth, 3f, "primeTowerBrimWidth")
    val primeTowerBrimChamfer = resolve(ov.primeTowerBrimChamfer, true, "primeTowerBrimChamfer")
    val primeTowerChamferMaxWidth = resolve(ov.primeTowerChamferMaxWidth, 5f, "primeTowerChamferMaxWidth")
    val primeTowerWidth = resolve(ov.primeTowerWidth, cfg.wipeTowerWidth, "primeTowerWidth")
    val wipeTowerRotationAngle = resolve(ov.wipeTowerRotationAngle, 0f, "wipeTowerRotationAngle")

    val result = mutableMapOf<String, Any>(
        // layer_height: omitted for USE_FILE so the source 3MF's value survives through
        // ProfileEmbedder's preserve path, and the process profile's value survives in the
        // standard path. ORCA_DEFAULT and OVERRIDE explicitly set a value here; the 0.0f
        // sentinel passed to JNI (see startSlicing) gates applyConfigToPrusa from stomping it.
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
        "wipe_tower_x" to MutableList(slotCount) { cfg.wipeTowerX.toString() },
        "wipe_tower_y" to MutableList(slotCount) { cfg.wipeTowerY.toString() },
        "prime_volume" to primeVolume.toString(),
        "prime_tower_brim_width" to primeTowerBrimWidth.toString(),
        "prime_tower_brim_chamfer" to if (primeTowerBrimChamfer) "1" else "0",
        "prime_tower_brim_chamfer_max_width" to primeTowerChamferMaxWidth.toString(),
        "wipe_tower_rotation_angle" to wipeTowerRotationAngle.toString(),
        // B63: filament_type per file filament. Sized to filamentCount so
        // per-canonical-filament overrides survive even when slotCount is
        // smaller (H2C benchy: filamentCount=7, slotCount=4).
        "filament_type" to MutableList(effectiveFilamentCount) { i ->
            filamentTypes?.getOrNull(i) ?: "PLA"
        }
    )

    // Phase 2 §4 Step 7 — per-filament colour overrides reach the embedded
    // filament_colour so the slicer's preview reflects the user's chosen
    // colours. Only emit when the caller supplied a canonical-derived list;
    // for non-canonical files the source config / default profile stack
    // owns filament_colour.
    if (filamentColours != null) {
        result["filament_colour"] = MutableList(effectiveFilamentCount) { i ->
            filamentColours.getOrNull(i) ?: "#FFFFFF"
        }
    }

    // sparse_infill_speed: 0 means "auto" — only emit when the user has overridden to a
    // positive value; otherwise let applyConfigToPrusa / embedded profile decide.
    if (sparseInfillSpeed > 0) {
        result["sparse_infill_speed"] = sparseInfillSpeed.toString()
    }

    // layer_height: USE_FILE defers to the source config value (preserve path) or the
    // process profile value (standard path); ORCA_DEFAULT and OVERRIDE emit explicitly.
    // applyConfigToPrusa also checks a 0.0f sentinel (see startSlicing) so the embedded
    // profile value from profile_keys[] wins when the user hasn't chosen a specific override.
    if (ov.layerHeight.mode != OverrideMode.USE_FILE) {
        result["layer_height"] = layerHeight.toString()
    }

    // Support keys: when mode is USE_FILE and the file has its own config (Bambu 3MF),
    // omit enable_support so the file's original value survives through ProfileEmbedder's
    // preserve path. Without this, cfg.supportEnabled defaults to false and stomps the
    // file's embedded support=true (B10 fix). For STL/non-Bambu files (no source config),
    // always emit — cfg.supportEnabled IS the user's intent.
    //
    // Sub-settings (type, angle, speed, etc.) each emit when the full support block is
    // active OR when the user has explicitly set that particular setting to OVERRIDE mode.
    // This prevents stale OVERRIDE state from being silently dropped when the user later
    // reverts the top-level supports toggle back to USE_FILE (B125 siblings).
    val supportBlockActive = ov.supports.mode != OverrideMode.USE_FILE || !hasSourceConfig
    if (supportBlockActive) {
        result["enable_support"] = if (supportEnabled) "1" else "0"
    }
    if (supportBlockActive || ov.supportAngle.mode == OverrideMode.OVERRIDE)
        result["support_threshold_angle"] = supportAngle.toString()
    if (supportBlockActive || ov.supportType.mode == OverrideMode.OVERRIDE)
        result["support_type"] = supportType
    if (supportBlockActive || ov.supportBuildPlateOnly.mode == OverrideMode.OVERRIDE)
        result["support_on_build_plate_only"] = if (supportBuildPlateOnly) "1" else "0"
    if (supportBlockActive || ov.supportPattern.mode == OverrideMode.OVERRIDE)
        result["support_base_pattern"] = supportPattern
    if (supportBlockActive || ov.supportPatternSpacing.mode == OverrideMode.OVERRIDE)
        result["support_base_pattern_spacing"] = supportPatternSpacing.toString()
    if (supportBlockActive || ov.supportInterfaceTopLayers.mode == OverrideMode.OVERRIDE)
        result["support_interface_top_layers"] = supportInterfaceTopLayers.toString()
    if (supportBlockActive || ov.supportInterfaceBottomLayers.mode == OverrideMode.OVERRIDE)
        result["support_interface_bottom_layers"] = supportInterfaceBottomLayers.toString()
    if (supportBlockActive || ov.supportXyDistance.mode == OverrideMode.OVERRIDE)
        result["support_object_xy_distance"] = supportXyDistance.toString()
    if (supportBlockActive || ov.supportInterfacePattern.mode == OverrideMode.OVERRIDE)
        result["support_interface_pattern"] = supportInterfacePattern
    if (supportBlockActive || ov.supportInterfaceSpacing.mode == OverrideMode.OVERRIDE)
        result["support_interface_spacing"] = supportInterfaceSpacing.toString()
    // support_speed 0 = auto; only emit positive values
    if ((supportBlockActive || ov.supportSpeed.mode == OverrideMode.OVERRIDE) && supportSpeed > 0)
        result["support_speed"] = supportSpeed.toString()
    // Tree support parameters — only relevant when support type is tree
    val isTree = supportType.startsWith("tree") &&
        (supportBlockActive || ov.supportType.mode == OverrideMode.OVERRIDE)
    if (isTree) {
        if (supportBlockActive || ov.treeSupportBranchAngle.mode == OverrideMode.OVERRIDE)
            result["tree_support_branch_angle"] = treeSupportBranchAngle.toString()
        if (supportBlockActive || ov.treeSupportBranchDistance.mode == OverrideMode.OVERRIDE)
            result["tree_support_branch_distance"] = treeSupportBranchDistance.toString()
        if (supportBlockActive || ov.treeSupportBranchDiameter.mode == OverrideMode.OVERRIDE)
            result["tree_support_branch_diameter"] = treeSupportBranchDiameter.toString()
    }

    // support_filament / support_interface_filament: emit whenever the user has
    // explicitly chosen a filament slot — regardless of whether ov.supports is
    // USE_FILE or the file has a source config. The gate above only preserves the
    // file's enable_support value; it must not suppress an explicit extruder choice
    // (B125: H2C shoe with USE_FILE + hasSourceConfig silently dropped E4 support).
    if (supportFilament > 0 &&
        (ov.supportFilament.mode == OverrideMode.OVERRIDE || supportBlockActive)) {
        result["support_filament"] = supportFilament.toString()
    }
    if (supportInterfaceFilament > 0 &&
        (ov.supportInterfaceFilament.mode == OverrideMode.OVERRIDE || supportBlockActive)) {
        result["support_interface_filament"] = supportInterfaceFilament.toString()
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
 * F91 (2026-05-25): SliceConfig-bound per-extruder arrays from the user's filament library.
 *
 * Unlike [buildFilamentLibrarySettings] (which produces a Map<String, Any> for the embed
 * pipeline used on 3MF paths), this returns typed arrays that flow through SliceConfig and
 * applyConfigToPrusa directly. The applyConfigToPrusa path is the only way to reach STL
 * slices (they bypass ProfileEmbedder entirely on the Kotlin side).
 *
 * Empty array = "no slot has set this field — let the embed value / U1 default stand".
 * Non-empty = "user has values for at least one slot, forward/back-filled across slots".
 */
internal data class FilamentLibraryArrays(
    val flowRatios: FloatArray = floatArrayOf(),
    val maxVolumetricSpeeds: FloatArray = floatArrayOf(),
    val fanMinSpeeds: IntArray = intArrayOf(),
    val fanMaxSpeeds: IntArray = intArrayOf(),
    val overhangFanSpeeds: IntArray = intArrayOf(),
    val additionalCoolingFanSpeeds: IntArray = intArrayOf(),
    val slowDownLayerTimes: FloatArray = floatArrayOf(),
    val slowDownMinSpeeds: FloatArray = floatArrayOf(),
    val closeFanFirstLayers: IntArray = intArrayOf(),
    val fullFanSpeedLayers: IntArray = intArrayOf(),
    val enablePressureAdvance: IntArray = intArrayOf(),
    val pressureAdvances: FloatArray = floatArrayOf(),
    val minimalPurgeOnWipeTower: FloatArray = floatArrayOf(),
    val nozzleTempInitialLayers: IntArray = intArrayOf(),
    val bedTempInitialLayers: IntArray = intArrayOf(),
    val costs: FloatArray = floatArrayOf(),
)

internal fun buildFilamentLibraryArrays(
    slotCount: Int,
    usedSlots: List<Int>?,
    presets: List<com.u1.slicer.data.ExtruderPreset>,
    filaments: List<com.u1.slicer.data.FilamentProfile>,
): FilamentLibraryArrays {
    if (slotCount <= 0) return FilamentLibraryArrays()
    val slots = usedSlots ?: (0 until slotCount).toList()
    val profilesPerSlot: List<com.u1.slicer.data.FilamentProfile?> = (0 until slotCount).map { i ->
        val slotIndex = slots.getOrElse(i) { i }
        val preset = presets.firstOrNull { it.index == slotIndex }
        val profileId = preset?.filamentProfileId
        filaments.firstOrNull { it.id == profileId }
    }

    fun <T : Number> collectFloat(accessor: (com.u1.slicer.data.FilamentProfile) -> T?): FloatArray {
        val raw = profilesPerSlot.map { p -> p?.let(accessor)?.toFloat() }
        if (raw.all { it == null }) return floatArrayOf()
        // Forward + back fill
        val out = raw.toMutableList()
        var last: Float? = null
        for (i in out.indices) { if (out[i] != null) last = out[i] else if (last != null) out[i] = last }
        last = null
        for (i in out.indices.reversed()) { if (out[i] != null) last = out[i] else if (last != null) out[i] = last }
        return FloatArray(out.size) { out[it] ?: 0f }
    }
    fun collectInt(accessor: (com.u1.slicer.data.FilamentProfile) -> Int?): IntArray {
        val raw = profilesPerSlot.map { p -> p?.let(accessor) }
        if (raw.all { it == null }) return intArrayOf()
        val out = raw.toMutableList()
        var last: Int? = null
        for (i in out.indices) { if (out[i] != null) last = out[i] else if (last != null) out[i] = last }
        last = null
        for (i in out.indices.reversed()) { if (out[i] != null) last = out[i] else if (last != null) out[i] = last }
        return IntArray(out.size) { out[it] ?: 0 }
    }
    fun collectBoolAsInt(accessor: (com.u1.slicer.data.FilamentProfile) -> Boolean?): IntArray {
        val raw = profilesPerSlot.map { p -> p?.let(accessor)?.let { if (it) 1 else 0 } }
        if (raw.all { it == null }) return intArrayOf()
        val out = raw.toMutableList()
        var last: Int? = null
        for (i in out.indices) { if (out[i] != null) last = out[i] else if (last != null) out[i] = last }
        last = null
        for (i in out.indices.reversed()) { if (out[i] != null) last = out[i] else if (last != null) out[i] = last }
        return IntArray(out.size) { out[it] ?: 0 }
    }

    return FilamentLibraryArrays(
        flowRatios = collectFloat { it.flowRatio },
        maxVolumetricSpeeds = collectFloat { it.maxVolumetricSpeed },
        fanMinSpeeds = collectInt { it.fanMinSpeed },
        fanMaxSpeeds = collectInt { it.fanMaxSpeed },
        overhangFanSpeeds = collectInt { it.overhangFanSpeed },
        additionalCoolingFanSpeeds = collectInt { it.additionalCoolingFanSpeed },
        slowDownLayerTimes = collectFloat { it.slowDownLayerTime },
        slowDownMinSpeeds = collectFloat { it.slowDownMinSpeed },
        closeFanFirstLayers = collectInt { it.closeFanFirstLayers },
        fullFanSpeedLayers = collectInt { it.fullFanSpeedLayer },
        enablePressureAdvance = collectBoolAsInt { it.enablePressureAdvance },
        pressureAdvances = collectFloat { it.pressureAdvance },
        minimalPurgeOnWipeTower = collectFloat { it.filamentMinimalPurgeOnWipeTower },
        nozzleTempInitialLayers = collectInt { it.nozzleTempInitialLayer },
        bedTempInitialLayers = collectInt { it.bedTempInitialLayer },
        costs = collectFloat { it.filamentCost },
    )
}

/**
 * F91: Build a `filamentSettings` map of per-slot arrays from the user's filament library.
 *
 * For each new OrcaSlicer per-filament tuning key (filament_flow_ratio,
 * filament_max_volumetric_speed, fan_*, pressure_advance, etc.), emit a length-`slotCount`
 * array indexed by physical slot when AT LEAST ONE slot has a non-null library value for
 * that field. Slots without a library value fall back to a neighbour's value
 * (forward/backward-fill) to keep the array length consistent and avoid OrcaSlicer crashes
 * on mismatched per-extruder arrays.
 *
 * Keys are sized to `slotCount` (physical extruder count), NOT canonical filament count —
 * matches the existing per-extruder pad/clamp behaviour in sapil_print.cpp for these keys.
 *
 * When NO slot has a value for a given field, the key is omitted entirely so the bundled
 * filament profile / OrcaSlicer default stands.
 */
internal fun buildFilamentLibrarySettings(
    slotCount: Int,
    usedSlots: List<Int>?,
    presets: List<com.u1.slicer.data.ExtruderPreset>,
    filaments: List<com.u1.slicer.data.FilamentProfile>,
): Map<String, Any> {
    if (slotCount <= 0) return emptyMap()
    val slots = usedSlots ?: (0 until slotCount).toList()
    val profilesPerSlot: List<com.u1.slicer.data.FilamentProfile?> = (0 until slotCount).map { i ->
        val slotIndex = slots.getOrElse(i) { i }
        val preset = presets.firstOrNull { it.index == slotIndex }
        val profileId = preset?.filamentProfileId
        filaments.firstOrNull { it.id == profileId }
    }

    fun forwardBackFill(values: List<String?>): List<String> {
        val out = values.toMutableList()
        // forward fill
        var last: String? = null
        for (i in out.indices) {
            if (out[i] != null) last = out[i]
            else if (last != null) out[i] = last
        }
        // back fill (for leading nulls)
        last = null
        for (i in out.indices.reversed()) {
            if (out[i] != null) last = out[i]
            else if (last != null) out[i] = last
        }
        // any remaining null → empty string (means no slot had a value AND we shouldn't be
        // emitting this key — caller guards on anyNonNull)
        return out.map { it ?: "" }
    }

    val result = mutableMapOf<String, Any>()
    fun emitFloat(key: String, accessor: (com.u1.slicer.data.FilamentProfile) -> Float?) {
        val raw = profilesPerSlot.map { p -> p?.let(accessor)?.toString() }
        if (raw.all { it == null }) return
        result[key] = forwardBackFill(raw)
    }
    fun emitInt(key: String, accessor: (com.u1.slicer.data.FilamentProfile) -> Int?) {
        val raw = profilesPerSlot.map { p -> p?.let(accessor)?.toString() }
        if (raw.all { it == null }) return
        result[key] = forwardBackFill(raw)
    }
    fun emitBool(key: String, accessor: (com.u1.slicer.data.FilamentProfile) -> Boolean?) {
        val raw = profilesPerSlot.map { p -> p?.let(accessor)?.let { if (it) "1" else "0" } }
        if (raw.all { it == null }) return
        result[key] = forwardBackFill(raw)
    }

    emitInt("nozzle_temperature_initial_layer") { it.nozzleTempInitialLayer }
    emitInt("hot_plate_temp_initial_layer") { it.bedTempInitialLayer }
    emitFloat("filament_flow_ratio") { it.flowRatio }
    emitFloat("filament_max_volumetric_speed") { it.maxVolumetricSpeed }
    emitFloat("filament_cost") { it.filamentCost }
    emitInt("fan_min_speed") { it.fanMinSpeed }
    emitInt("fan_max_speed") { it.fanMaxSpeed }
    emitInt("overhang_fan_speed") { it.overhangFanSpeed }
    emitInt("additional_cooling_fan_speed") { it.additionalCoolingFanSpeed }
    emitFloat("slow_down_layer_time") { it.slowDownLayerTime }
    emitFloat("slow_down_min_speed") { it.slowDownMinSpeed }
    emitInt("close_fan_the_first_x_layers") { it.closeFanFirstLayers }
    emitInt("full_fan_speed_layer") { it.fullFanSpeedLayer }
    emitBool("enable_pressure_advance") { it.enablePressureAdvance }
    emitFloat("pressure_advance") { it.pressureAdvance }
    emitFloat("filament_minimal_purge_on_wipe_tower") { it.filamentMinimalPurgeOnWipeTower }
    return result
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
internal fun computeFreshSlotTemps(
    slotCount: Int,
    usedSlots: List<Int>?,
    presets: List<com.u1.slicer.data.ExtruderPreset>,
    filaments: List<com.u1.slicer.data.FilamentProfile>
): IntArray {
    val slots = usedSlots ?: (0 until slotCount).toList()
    return IntArray(slotCount) { i ->
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
    // extruder attribute remap in model_settings.config.  PrintTimeRemap handles the
    // canonical→physical slot translation at send time, so suppress the XML extruder
    // remap here.
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
        // must keep the distinct count so PrintTimeRemap's send-time
        // canonical→physical translation matches the user's slot mapping
        // exactly.
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
        // single-tool G-code instead of two. The resulting high T-indices stay in
        // canonical fileIndex space on disk; PrintTimeRemap translates them to the
        // user's physical slots at send time.
        return maxOf(baseSize, maxSourceFilamentIndex)
    }
    if (toolRemapSlots != null) return toolRemapSlots.distinct().size
    return fallbackExtCount
}

// Phase 2 (2026-04-28) — Group B: the slice-time tool remap helpers
// (`computeSemmColorPermutation`, `computeSlicerColorOrder`,
// `computeExpandedGcodeRemap`, `composeSemmRemap`) were retired. The slicer
// emits canonical T-indices in fileIndex space; PrintTimeRemap (separate,
// Send-time) translates to physical slots when the user confirms the
// Filament mapping dialog. None of these helpers had remaining production
// callers after `1e95c7d` made `applyConfigToPrusa` respect the embed for
// per-filament tuning. Their unit tests (SlicerColorOrderTest,
// SemmColorPermutationTest, ExpandedGcodeRemapTest) were removed alongside.

internal fun resolveFilamentTypesForHeaderPatch(
    canonical: com.u1.slicer.data.CanonicalFilamentList,
    overrides: Map<Int, Pair<String?, String?>>,
    colorMapping: List<Int>?,
    presets: List<ExtruderPreset>,
    filamentLibrary: List<FilamentProfile>,
    padTo: Int = canonical.size,
): List<String> {
    val resolved = com.u1.slicer.data.resolvePerFilamentTypeAndTemp(
        canonical = canonical,
        overrides = overrides,
        colorMapping = colorMapping,
        presets = presets,
        filamentLibrary = filamentLibrary,
    ).first

    // B102: when colorMapping is provided, produce a physical-slot-indexed array so that
    // after PrintTimeRemap the printer can look up filament_type[T_physical] and find the
    // correct material. Without this, the canonical-indexed array has entries at positions
    // 0..canonical.size-1, but the physical G-code uses T<colorMapping[i]> — for sparse
    // mappings like [2,3] the entries at positions 2 and 3 are absent → printer defaults
    // to PLA (phantom PLA demand).
    if (!colorMapping.isNullOrEmpty()) {
        val slotPresetByIndex = presets.associateBy { it.index }
        val maxPhysicalSlot = colorMapping.maxOrNull() ?: 0
        val physicalSize = maxOf(maxPhysicalSlot + 1, padTo)
        val result = MutableList(physicalSize) { idx ->
            slotPresetByIndex[idx]?.materialType?.takeIf { it.isNotBlank() } ?: "PLA"
        }
        // Process in forward order so lower canonical indices (which carry overrides) win
        // when multiple canonical indices share the same physical slot (e.g. SEMM H2C).
        val seen = mutableSetOf<Int>()
        colorMapping.forEachIndexed { canonicalIdx, physicalSlot ->
            if (physicalSlot in result.indices && canonicalIdx < resolved.size && physicalSlot !in seen) {
                result[physicalSlot] = resolved[canonicalIdx]
                seen.add(physicalSlot)
            }
        }
        return result
    }

    // No mapping (STL / single-colour without explicit slot assignment): return canonical-
    // indexed array padded to padTo using preset types at the matching positions.
    if (resolved.size >= padTo) return resolved
    val slotTypes = presets.sortedBy { it.index }.map { it.materialType }
    return resolved + (resolved.size until padTo).map { idx ->
        slotTypes.getOrNull(idx) ?: "PLA"
    }
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

/**
 * B105: For non-canonical files (STL, single-colour 3MF) derive the filament_type
 * header patch list from the physically-used slots only.
 *
 * Before this fix, all 4 presets were emitted regardless of which slots were
 * actually used. For a single-colour print on E3 that produced
 * `; filament_type = PLA;PLA;PLA;PLA` (4 entries) instead of `; filament_type = PLA`.
 */
internal fun resolveNonCanonicalHeaderPatchTypes(
    usedSlots: List<Int>,
    presets: List<ExtruderPreset>,
): List<String> = usedSlots.map { slot ->
    presets.firstOrNull { it.index == slot }?.materialType ?: "PLA"
}

/**
 * B117: apply a Prepare-screen `_filamentOverrides[0]` to the slot-derived
 * filament_type / nozzle_temperature lists for non-canonical files (STL,
 * single-colour 3MF without per-filament metadata).
 *
 * Replaces the FIRST entry of both arrays with the override's materialType +
 * its corresponding default nozzle temp. Leaves remaining entries unchanged
 * — those are usually padding for support_filament / wipe_tower slots that
 * the user didn't override.
 *
 * When `override` is null or has no materialType set, returns the inputs
 * unchanged. When the slot arrays are empty, returns empty (no-op).
 */
internal fun applyNonCanonicalOverride(
    slotTypes: List<String>,
    slotTemps: List<Int>,
    override: SlicerViewModel.FilamentOverride?,
): Pair<List<String>, List<Int>> {
    val newType = override?.materialType
    if (newType == null || slotTypes.isEmpty()) {
        return slotTypes to slotTemps
    }
    val overriddenTypes = listOf(newType) + slotTypes.drop(1)
    val overriddenTemps = if (slotTemps.isNotEmpty()) {
        listOf(nozzleTempDefaultForMaterial(newType)) + slotTemps.drop(1)
    } else {
        slotTemps
    }
    return overriddenTypes to overriddenTemps
}

/**
 * B110: physical-slot-indexed nozzle_temperature companion to
 * [resolveFilamentTypesForHeaderPatch]. The two arrays MUST agree on which slot
 * received a given material/temp so that downstream consumers reading
 * `filament_type[i]` and `nozzle_temperature[i]` see consistent rows.
 *
 * Resolution order mirrors [com.u1.slicer.data.resolvePerFilamentTypeAndTemp]:
 *   1. User override material → [nozzleTempDefaultForMaterial] of that material.
 *   2. Linked filament-profile temp for the mapped slot (when no override).
 *   3. Material-default temp of the slot's preset materialType.
 *   4. PLA default (220°C).
 *
 * Indexing:
 *   - When [colorMapping] is non-empty, the result is **physical-slot-indexed**
 *     (length = max(maxPhysicalSlot+1, padTo)). Forward-order traversal so the
 *     lowest canonical index wins on H2C-style folds where multiple file
 *     filaments share a physical slot.
 *   - When [colorMapping] is null/empty, the result is canonical-indexed and
 *     padded to [padTo] using material defaults from the preset at that slot.
 */
internal fun resolveNozzleTempsForHeaderPatch(
    canonical: com.u1.slicer.data.CanonicalFilamentList,
    overrides: Map<Int, Pair<String?, String?>>,
    colorMapping: List<Int>?,
    presets: List<ExtruderPreset>,
    filamentLibrary: List<FilamentProfile>,
    padTo: Int = canonical.size,
): List<Int> {
    val resolved = com.u1.slicer.data.resolvePerFilamentTypeAndTemp(
        canonical = canonical,
        overrides = overrides,
        colorMapping = colorMapping,
        presets = presets,
        filamentLibrary = filamentLibrary,
    ).second

    if (!colorMapping.isNullOrEmpty()) {
        val slotPresetByIndex = presets.associateBy { it.index }
        val maxPhysicalSlot = colorMapping.maxOrNull() ?: 0
        val physicalSize = maxOf(maxPhysicalSlot + 1, padTo)
        val result = MutableList(physicalSize) { idx ->
            val preset = slotPresetByIndex[idx]
            val material = preset?.materialType?.takeIf { it.isNotBlank() } ?: "PLA"
            val profileTemp = preset?.filamentProfileId
                ?.let { id -> filamentLibrary.firstOrNull { it.id == id }?.nozzleTemp }
            profileTemp ?: nozzleTempDefaultForMaterial(material)
        }
        val seen = mutableSetOf<Int>()
        colorMapping.forEachIndexed { canonicalIdx, physicalSlot ->
            if (physicalSlot in result.indices && canonicalIdx < resolved.size && physicalSlot !in seen) {
                result[physicalSlot] = resolved[canonicalIdx]
                seen.add(physicalSlot)
            }
        }
        return result
    }

    if (resolved.size >= padTo) return resolved
    val slotPresetByIndex = presets.associateBy { it.index }
    return resolved + (resolved.size until padTo).map { idx ->
        val preset = slotPresetByIndex[idx]
        val material = preset?.materialType?.takeIf { it.isNotBlank() } ?: "PLA"
        val profileTemp = preset?.filamentProfileId
            ?.let { id -> filamentLibrary.firstOrNull { it.id == id }?.nozzleTemp }
        profileTemp ?: nozzleTempDefaultForMaterial(material)
    }
}

/**
 * B110: nozzle_temperature companion to [resolveNonCanonicalHeaderPatchTypes].
 *
 * Returns one temp per entry in [usedSlots], pulled from the preset's linked
 * filament profile (when set) or otherwise the material default for the slot's
 * preset materialType. Used for STL / single-colour 3MF flows where the file has
 * no canonical filament list.
 */
internal fun resolveNonCanonicalHeaderPatchTemps(
    usedSlots: List<Int>,
    presets: List<ExtruderPreset>,
    filamentLibrary: List<FilamentProfile>,
): List<Int> = usedSlots.map { slot ->
    val preset = presets.firstOrNull { it.index == slot }
    val material = preset?.materialType ?: "PLA"
    val profileTemp = preset?.filamentProfileId
        ?.let { id -> filamentLibrary.firstOrNull { it.id == id }?.nozzleTemp }
    profileTemp ?: nozzleTempDefaultForMaterial(material)
}

/**
 * B110: Replace the `; nozzle_temperature = ...` (and matching
 * `; nozzle_temperature_initial_layer = ...`) header comment lines in a
 * generated G-code file with [temps] joined by commas.
 *
 * OrcaSlicer's `ConfigOptionInts::serialize` emits comma-separated values for
 * integer vector options (vs semicolons for string vectors like filament_type).
 *
 * Both `nozzle_temperature` and `nozzle_temperature_initial_layer` are patched
 * with the same array because B110's source mismatch is the slot-indexing
 * convention, not a per-line difference between layer 1 and layer 2+.
 *
 * @return true if at least one of the two header lines was found and replaced.
 */
internal fun fixNozzleTemperatureHeader(gcodePath: String, temps: List<Int>): Boolean {
    if (temps.isEmpty()) return false
    val file = java.io.File(gcodePath)
    if (!file.exists()) return false
    val replacement = temps.joinToString(",")
    val tmpFile = java.io.File("$gcodePath.ntemp.tmp")
    return try {
        var foundMain = false
        var foundInitial = false
        file.bufferedReader().use { reader ->
            tmpFile.bufferedWriter().use { writer ->
                for (line in reader.lineSequence()) {
                    when {
                        !foundMain && line.startsWith("; nozzle_temperature = ") -> {
                            writer.write("; nozzle_temperature = $replacement")
                            foundMain = true
                        }
                        !foundInitial && line.startsWith("; nozzle_temperature_initial_layer = ") -> {
                            writer.write("; nozzle_temperature_initial_layer = $replacement")
                            foundInitial = true
                        }
                        else -> writer.write(line)
                    }
                    writer.newLine()
                }
            }
        }
        val found = foundMain || foundInitial
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
