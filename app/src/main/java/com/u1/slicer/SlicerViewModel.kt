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
import com.u1.slicer.gcode.GcodeParser
import com.u1.slicer.gcode.GcodeThumbnailInjector
import com.u1.slicer.gcode.GcodeToolRemapper
import com.u1.slicer.gcode.ParsedGcode
import com.u1.slicer.network.MakerWorldClient
import com.u1.slicer.data.ModelInfo
import com.u1.slicer.data.OverrideMode
import com.u1.slicer.data.SettingsBackup
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.data.SliceJob
import com.u1.slicer.data.SliceResult
import com.u1.slicer.data.SlicingOverrides
import com.u1.slicer.model.CopyArrangeCalculator
import com.u1.slicer.data.ExtruderPreset
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
    private val container = (application as U1SlicerApplication).container
    private val settingsRepo = container.settingsRepository
    private val filamentDao = container.filamentDao
    private val sliceJobDao = container.sliceJobDao
    private val profileEmbedder by lazy { ProfileEmbedder(getApplication()) }

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

    // MakerWorld import state
    private val _importLoading = MutableStateFlow(false)
    val importLoading: StateFlow<Boolean> = _importLoading.asStateFlow()

    private val _importProgress = MutableStateFlow(0)
    val importProgress: StateFlow<Int> = _importProgress.asStateFlow()

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

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

    private val makerWorldClient = MakerWorldClient()

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

    // MakerWorld cookies (for authenticated downloads)
    val makerWorldCookies: StateFlow<String> = settingsRepo.makerWorldCookies
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun saveMakerWorldCookies(cookies: String) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.saveMakerWorldCookies(cookies)
        }
    }

    // Track the current working file (may be sanitized copy)
    private var currentModelFile: File? = null
    private var currentModelName: String = ""
    private var lastModelInfo: ModelInfo? = null

    // Source file and info before embedding — kept so we can re-embed with extruder remap
    // when the user sets non-identity slot assignments after initial load.
    private var sourceModelFile: File? = null
    private var sourceModelInfo: ThreeMfInfo? = null

    /** Exposed for 3D viewer navigation */
    val currentModelPath: String? get() = currentModelFile?.absolutePath

    /**
     * Path to use for the inline 3D preview. Uses the original source file when available
     * (before sanitization/embedding) because the sanitized file may have component files
     * stripped out, leaving no geometry for the preview parser.
     */
    val previewModelPath: String? get() = (sourceModelFile ?: currentModelFile)?.absolutePath

    init {
        _coreVersion.value = if (NativeLibrary.isLoaded) {
            "Snapmaker Orca 2.2.4 (Android ARM64)"
        } else {
            "Native library not available"
        }

        viewModelScope.launch {
            val saved = settingsRepo.sliceConfig.first()
            _config.value = saved
        }
    }

    fun importFromUrl(urlOrId: String) {
        if (!NativeLibrary.isLoaded) {
            _state.value = SlicerState.Error("Native slicer library not available on this device (arm64 required)")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _importLoading.value = true
            _importError.value = null
            _importProgress.value = 0
            try {
                val context = getApplication<Application>()
                val cookies = settingsRepo.makerWorldCookies.first()
                val result = makerWorldClient.download(
                    urlOrId = urlOrId,
                    outputDir = context.filesDir,
                    cookies = cookies,
                    onProgress = { _importProgress.value = it }
                )

                _importLoading.value = false
                currentModelName = result.filename

                // Process through the same 3MF pipeline (sanitize, plate detection, etc.)
                val info = ThreeMfParser.parse(result.file)
                _threeMfInfo.value = info

                Log.i("SlicerVM", "MakerWorld 3MF: bambu=${info.isBambu}, multiPlate=${info.isMultiPlate}")

                sourceModelFile = result.file
                sourceModelInfo = info
                toolRemapSlots = null
                val sanitized = embedProfile(result.file, info, context)

                if (info.isMultiPlate && info.plates.size > 1) {
                    currentModelFile = sanitized
                    _showPlateSelector.value = true
                    return@launch
                }

                currentModelFile = sanitized
                loadNativeModel(sanitized)
            } catch (e: Throwable) {
                _importLoading.value = false
                _importError.value = e.message ?: "Download failed"
                Log.e("SlicerVM", "MakerWorld import failed", e)
            }
        }
    }

    fun clearImportError() {
        _importError.value = null
    }

    fun loadModel(uri: Uri) {
        if (!NativeLibrary.isLoaded) {
            _state.value = SlicerState.Error("Native slicer library not available on this device (arm64 required)")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
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

                    val info = ThreeMfParser.parse(file)
                    _threeMfInfo.value = info

                    Log.i("SlicerVM", "3MF: bambu=${info.isBambu}, multiPlate=${info.isMultiPlate}, " +
                        "colors=${info.detectedColors.size}, extruders=${info.detectedExtruderCount}, " +
                        "paint=${info.hasPaintData}, toolChanges=${info.hasLayerToolChanges}")

                    // Embed Snapmaker profile and strip incompatible Bambu metadata.
                    // Store source before embedding so startSlicing() can re-embed with
                    // the correct extruder remap once the user has picked their slots.
                    sourceModelFile = file
                    sourceModelInfo = info
                    toolRemapSlots = null  // reset on each new file load
                    val sanitized = embedProfile(file, info, context)

                    // Show plate selector for multi-plate files
                    if (info.isMultiPlate && info.plates.size > 1) {
                        currentModelFile = sanitized
                        _showPlateSelector.value = true
                        // Don't load yet — wait for plate selection
                        return@launch
                    }

                    sanitized
                } else {
                    _threeMfInfo.value = null
                    // Clear source file so previewModelPath uses the STL directly.
                    // Without this, loading an STL after a 3MF leaves sourceModelFile
                    // pointing at the old 3MF, causing the wrong model to appear in the viewer.
                    sourceModelFile = null
                    sourceModelInfo = null
                    file
                }

                currentModelFile = fileToLoad
                loadNativeModel(fileToLoad)
            } catch (e: Throwable) {
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

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val plateFile = BambuSanitizer.extractPlate(file, plateId, context.filesDir)
                // plateFile is now the source for any subsequent re-embed with remap
                sourceModelFile = plateFile
                sourceModelInfo = ThreeMfParser.parse(plateFile)
                toolRemapSlots = null
                currentModelFile = plateFile
                loadNativeModel(plateFile)
            } catch (e: Throwable) {
                _state.value = SlicerState.Error("Error extracting plate: ${e.message}")
            }
        }
    }

    fun dismissPlateSelector() {
        _showPlateSelector.value = false
        // Load all plates (first plate by default)
        val file = currentModelFile ?: return
        viewModelScope.launch(Dispatchers.IO) {
            loadNativeModel(file)
        }
    }

    private fun loadNativeModel(file: File) {
        val success = native.loadModel(file.absolutePath)
        if (success) {
            val info = native.getModelInfo()
            if (info != null) {
                lastModelInfo = info
                _modelScale.value = ModelScale()  // reset to 1× on each new load
                _state.value = SlicerState.ModelLoaded(info)

                // Check for multi-color from 3MF parsing
                val mfInfo = _threeMfInfo.value
                if (mfInfo != null && mfInfo.detectedExtruderCount > 1) {
                    val extCount = mfInfo.detectedExtruderCount.coerceAtMost(4)
                    _config.value = _config.value.copy(
                        extruderCount = extCount,
                        wipeTowerEnabled = true
                    )
                    // Auto-apply closest-extruder mapping immediately — no dialog popup.
                    // The inline UI on the model page lets the user change assignments.
                    val presets = extruderPresets.value
                    val initialMapping = mfInfo.detectedColors.map { modelColor ->
                        com.u1.slicer.ui.findClosestExtruder(modelColor, presets)?.index ?: 0
                    }
                    _colorMapping.value = initialMapping
                    applyMultiColorAssignments(initialMapping, presets, emptyList())
                    Log.i("SlicerVM", "Auto-applied color mapping: $extCount extruders, mapping=$initialMapping")
                } else {
                    _colorMapping.value = null
                    // Single-color model: set E1's color from current printer slot config so
                    // the 3D model preview shows the correct filament color instead of default orange.
                    val presets = extruderPresets.value
                    val colors = MutableList(4) { "" }
                    presets.forEach { preset -> if (preset.index in 0..3) colors[preset.index] = preset.color }
                    _activeExtruderColors.value = colors
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
        val extCount = usedSlots.size.coerceIn(1, 4)
        // Store remap only if it's non-identity (e.g. [2,3] for E3+E4 — T0→T2, T1→T3)
        val isIdentity = usedSlots == (0 until extCount).toList()
        toolRemapSlots = if (isIdentity) null else usedSlots
        val temps = IntArray(extCount) { i ->
            val slotIndex = usedSlots.getOrElse(i) { i }
            val preset = extruderPresets.firstOrNull { it.index == slotIndex }
            val profileId = preset?.filamentProfileId
            filaments.firstOrNull { it.id == profileId }?.nozzleTemp ?: 210
        }
        _config.value = _config.value.copy(
            extruderCount = extCount,
            extruderTemps = temps,
            extruderRetractLength = FloatArray(extCount) { _config.value.retractLength },
            extruderRetractSpeed = FloatArray(extCount) { _config.value.retractSpeed },
            wipeTowerEnabled = extCount > 1
        )
        // Store per-extruder colors for G-code viewers, indexed by physical slot.
        // After tool remapping the G-code uses physical T-indices (e.g. T2, T3),
        // so the color list must have entries at those positions.
        val detectedColors = _threeMfInfo.value?.detectedColors ?: emptyList()
        val fullColors = MutableList(4) { "" }  // 4 slots on Snapmaker U1
        usedSlots.forEachIndexed { compactIdx, slotIndex ->
            fullColors[slotIndex] = extruderPresets.firstOrNull { it.index == slotIndex }?.color
                ?: detectedColors.getOrElse(compactIdx) { "#808080" }
        }
        _activeExtruderColors.value = fullColors
        Log.i("SlicerVM", "Applied color mapping: $extCount extruders used=${usedSlots}, remap=${toolRemapSlots}, temps=${temps.toList()}, colors=$fullColors")
    }

    fun dismissMultiColorDialog() {
        _showMultiColorDialog.value = false
    }

    fun showMultiColorReassign() {
        _showMultiColorDialog.value = true
    }

    fun setModelScale(scale: ModelScale) {
        _modelScale.value = scale
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

    /** Returns initial positions for inline 3D placement (custom or auto-calculated). */
    fun getPlacementPositions(): FloatArray {
        customObjectPositions?.let { return it }
        val mi = lastModelInfo ?: return floatArrayOf(135f, 135f)
        return CopyArrangeCalculator.calculate(mi.sizeX, mi.sizeY, _copyCount.value)
    }

    fun updateConfig(updater: (SliceConfig) -> SliceConfig) {
        _config.value = updater(_config.value)
    }

    fun saveConfig() {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.saveSliceConfig(_config.value)
        }
    }

    fun saveSlicingOverrides(overrides: SlicingOverrides) {
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
        // Always use compact extruder count (= number of used extruders, not physical slot max).
        // OrcaSlicer's toolpath optimizer scales badly with extruder count — using maxSlot+1
        // (e.g. 4 for E3+E4) causes OOM even on small models.  Compact mode slices as N-extruder
        // and we post-process the G-code to remap T-commands + SM indices to physical slots.
        val targetCount = if (usedSlots != null) usedSlots.size else extCount
        // No extruder remap in the 3MF — keep compact numbering (1,2,…).
        // G-code post-processing handles T0→T2, T1→T3, SM EXTRUDER/INDEX remapping.
        val extruderRemap: Map<Int, Int>? = null
        val sourceConfig = if (info.isBambu) {
            java.util.zip.ZipFile(file).use { profileEmbedder.parseSourceConfig(it) }
        } else null
        val embeddedConfig = profileEmbedder.buildConfig(
            info = info,
            sourceConfig = sourceConfig,
            overrides = buildProfileOverrides(cfg, extCount, usedSlots),
            targetExtruderCount = targetCount
        )
        return profileEmbedder.embed(file, embeddedConfig, context.filesDir, info, extruderRemap)
    }

    private fun buildProfileOverrides(cfg: SliceConfig, extCount: Int, usedSlots: List<Int>? = null): Map<String, Any> {
        // Always use compact temp arrays (one entry per used extruder, in order).
        // Non-identity slot mapping (E3+E4) is handled by G-code post-processing,
        // not by padding the array to physical slot positions.
        val temps: MutableList<String> = if (cfg.extruderTemps.size >= extCount) {
            cfg.extruderTemps.take(extCount).map { it.toString() }.toMutableList()
        } else {
            MutableList(extCount) { cfg.nozzleTemp.toString() }
        }

        val ov = slicingOverrides.value
        val defaults = SlicingOverrides.ORCA_DEFAULTS

        // Resolve an override: USE_FILE → use cfg value, ORCA_DEFAULT → factory default, OVERRIDE → user value
        fun <T> resolve(override: com.u1.slicer.data.OverrideValue<T>, cfgValue: T, defaultKey: String): T {
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
        val brimWidth = resolve(ov.brimWidth, cfg.brimWidth, "brimWidth")
        val skirtLoops = resolve(ov.skirtLoops, cfg.skirtLoops, "skirtLoops")
        val bedTemp = resolve(ov.bedTemp, cfg.bedTemp, "bedTemp")
        val primeTower = resolve(ov.primeTower, cfg.wipeTowerEnabled, "primeTower")

        // Prime tower detail overrides (ProfileEmbedder JSON path, not JNI)
        val primeVolume = resolve(ov.primeVolume, 45, "primeVolume")
        val primeTowerBrimWidth = resolve(ov.primeTowerBrimWidth, 3f, "primeTowerBrimWidth")
        val primeTowerBrimChamfer = resolve(ov.primeTowerBrimChamfer, true, "primeTowerBrimChamfer")
        val primeTowerChamferMaxWidth = resolve(ov.primeTowerChamferMaxWidth, 5f, "primeTowerChamferMaxWidth")

        return mapOf(
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
            "enable_support" to if (supportEnabled) "1" else "0",
            "support_threshold_angle" to cfg.supportAngle.toInt().toString(),
            "brim_width" to brimWidth.toString(),
            "skirt_loops" to skirtLoops.toString(),
            // Prime tower (wipe_tower_x/y are ConfigOptionFloats arrays in OrcaSlicer)
            "enable_prime_tower" to if (primeTower) "1" else "0",
            "prime_tower_width" to cfg.wipeTowerWidth.toString(),
            "wipe_tower_x" to MutableList(extCount) { cfg.wipeTowerX.toString() },
            "wipe_tower_y" to MutableList(extCount) { cfg.wipeTowerY.toString() },
            // Prime tower detail settings (ProfileEmbedder JSON path)
            "prime_volume" to primeVolume.toString(),
            "prime_tower_brim_width" to primeTowerBrimWidth.toString(),
            "prime_tower_brim_chamfer" to if (primeTowerBrimChamfer) "1" else "0",
            "prime_tower_brim_chamfer_max_width" to primeTowerChamferMaxWidth.toString()
        )
    }

    fun startSlicing() {
        viewModelScope.launch(Dispatchers.IO) {
            var maxPct = 0
            native.progressListener = { pct, stage ->
                if (pct > maxPct) maxPct = pct
                _state.value = SlicerState.Slicing(maxPct, stage)
            }

            _state.value = SlicerState.Slicing(0, "Preparing...")

            // If the user assigned non-identity extruder slots (e.g. E3+E4), we must
            // re-embed the 3MF now — embedProfile() ran at load time with no remap.
            val remap = toolRemapSlots
            if (remap != null) {
                val src = sourceModelFile
                val srcInfo = sourceModelInfo
                val context = getApplication<Application>()
                if (src != null && srcInfo != null) {
                    Log.i("SlicerVM", "Re-embedding 3MF with extruder remap $remap before slicing")
                    // Free the old model from native memory before loading the new one.
                    // loadModel does g_model = read_from_file(...) which holds both old and
                    // new models in memory simultaneously — enough to OOM on a large 3MF.
                    native.clearModel()
                    val reembedded = embedProfile(src, srcInfo, context)
                    currentModelFile = reembedded
                    native.loadModel(reembedded.absolutePath)
                }
            }

            // Always reload from file for a clean instance state, then apply placement.
            // This prevents stale instance offsets from the inline 3D viewer corrupting
            // PrusaSlicer's internal state (SIGSEGV in export_gcode on multi-object models).
            val path = currentModelPath
            if (path != null) {
                native.clearModel()
                native.loadModel(path)
                Log.i("SlicerVM", "Reloaded model for clean state before placement")
            }

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
                native.setModelInstances(custom)
                Log.i("SlicerVM", "Using custom placement: ${custom.size / 2} instances")
            } else {
                // Auto-arrange: single copy → centered, multiple copies → grid
                if (mi != null && mi.sizeX > 0f && mi.sizeY > 0f) {
                    val positions = CopyArrangeCalculator.calculate(mi.sizeX, mi.sizeY, copies)
                    native.setModelInstances(positions)
                    Log.i("SlicerVM", "Auto-placed $copies instance(s) at [${positions.toList().take(4)}]")
                }
            }

            // Build the effective config for this slice.
            // resolveInto() applies OVERRIDE / ORCA_DEFAULT modes to the current UI config.
            // USE_FILE passthrough: base values from _config.value are used as-is.
            // We use a local copy — _config.value (the UI state) is never mutated here.
            val ov = slicingOverrides.value
            val sliceConfig = ov.resolveInto(_config.value)
            Log.i("SlicerVM", "Resolved slice config: layer=${sliceConfig.layerHeight} " +
                "infill=${sliceConfig.fillDensity} walls=${sliceConfig.perimeters} " +
                "support=${sliceConfig.supportEnabled} speed=${sliceConfig.printSpeed}")

            val result = native.slice(sliceConfig)

            native.progressListener = null

            if (result != null && result.success) {
                // Post-process G-code to remap compact tool indices to physical slots.
                // OrcaSlicer sliced in compact mode (T0,T1,…) — remap to actual printer
                // slots (e.g. T2,T3 for E3+E4) and fix SM_ command EXTRUDER/INDEX params.
                val slots = toolRemapSlots
                if (slots != null) {
                    GcodeToolRemapper.remap(result.gcodePath, slots)
                    Log.i("SlicerVM", "Post-processed G-code: remapped tools to physical slots $slots")
                }
                // Inject preview thumbnails into G-code for Klipper/Moonraker
                val sourcePath = sourceModelFile?.absolutePath ?: currentModelFile?.absolutePath
                if (sourcePath != null) {
                    val injected = GcodeThumbnailInjector.inject(result.gcodePath, sourcePath)
                    if (injected) Log.i("SlicerVM", "Thumbnails injected into G-code")
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
                _state.value = SlicerState.Error(result?.errorMessage ?: "Slicing failed")
            }
        }
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

    fun exportBackupAsync(onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val cfg = _config.value
            val overrides = slicingOverrides.value
            val presets = extruderPresets.value
            val printerUrl = settingsRepo.printerUrl.first()
            val profiles = filamentDao.getAll().first()
            val json = SettingsBackup.export(cfg, overrides, printerUrl, presets, profiles)
            onResult(json)
        }
    }

    fun importBackup(json: String) {
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
                data.printerUrl?.let {
                    settingsRepo.savePrinterUrl(it)
                }
                data.extruderPresets?.let {
                    settingsRepo.saveExtruderPresets(it)
                }
                data.filamentProfiles?.let { profiles ->
                    profiles.forEach { filamentDao.insert(it) }
                }
                Log.i("SlicerVM", "Settings backup imported successfully")
            } catch (e: Exception) {
                Log.e("SlicerVM", "Failed to import backup: ${e.message}")
            }
        }
    }

    fun clearModel() {
        native.clearModel()
        _state.value = SlicerState.Idle
        _gcodePreview.value = ""
        _parsedGcode.value = null
        _activeExtruderColors.value = emptyList()
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
                context.contentResolver.openOutputStream(uri)?.use { out ->
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
}
