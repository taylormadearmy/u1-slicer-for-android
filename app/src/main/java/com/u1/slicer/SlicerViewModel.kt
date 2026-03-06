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
import com.u1.slicer.gcode.ParsedGcode
import com.u1.slicer.network.MakerWorldClient
import com.u1.slicer.data.ModelInfo
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.data.SliceJob
import com.u1.slicer.data.SliceResult
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

    // Multi-color state
    private val _showMultiColorDialog = MutableStateFlow(false)
    val showMultiColorDialog: StateFlow<Boolean> = _showMultiColorDialog.asStateFlow()

    // Active extruder colors for G-code viewers (hex strings, one per extruder slot)
    private val _activeExtruderColors = MutableStateFlow<List<String>>(emptyList())
    val activeExtruderColors: StateFlow<List<String>> = _activeExtruderColors.asStateFlow()

    // Multiple copies
    private val _copyCount = MutableStateFlow(1)
    val copyCount: StateFlow<Int> = _copyCount.asStateFlow()

    // Custom object positions set from PlacementViewer (null = use auto grid)
    // Flat array [x0,y0,x1,y1,...] in mm
    private var customObjectPositions: FloatArray? = null
    // Custom wipe tower position (null = use config defaults)
    private var customWipeTowerPos: Pair<Float, Float>? = null

    private val makerWorldClient = MakerWorldClient()

    // Filament library
    val filaments = filamentDao.getAll()

    // Job history
    val sliceJobs = sliceJobDao.getAll()

    // Extruder slot config (from printer page, used for color mapping dialog)
    val extruderPresets: StateFlow<List<ExtruderPreset>> = settingsRepo.extruderPresets
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.u1.slicer.data.defaultExtruderPresets())

    // Track the current working file (may be sanitized copy)
    private var currentModelFile: File? = null
    private var currentModelName: String = ""
    private var lastModelInfo: ModelInfo? = null

    /** Exposed for 3D viewer navigation */
    val currentModelPath: String? get() = currentModelFile?.absolutePath

    init {
        _coreVersion.value = if (NativeLibrary.isLoaded) {
            "OrcaSlicer 2.2.0 (Native Android ARM64)"
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
                val result = makerWorldClient.download(
                    urlOrId = urlOrId,
                    outputDir = context.filesDir,
                    onProgress = { _importProgress.value = it }
                )

                _importLoading.value = false
                currentModelName = result.filename

                // Process through the same 3MF pipeline (sanitize, plate detection, etc.)
                val info = ThreeMfParser.parse(result.file)
                _threeMfInfo.value = info

                Log.i("SlicerVM", "MakerWorld 3MF: bambu=${info.isBambu}, multiPlate=${info.isMultiPlate}")

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

                    // Embed Snapmaker profile and strip incompatible Bambu metadata
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
                _state.value = SlicerState.ModelLoaded(info)

                // Check for multi-color from 3MF parsing
                val mfInfo = _threeMfInfo.value
                if (mfInfo != null && mfInfo.detectedExtruderCount > 1) {
                    // Auto-configure extruder count and show dialog
                    val extCount = mfInfo.detectedExtruderCount.coerceAtMost(4)
                    _config.value = _config.value.copy(
                        extruderCount = extCount,
                        wipeTowerEnabled = true
                    )
                    _showMultiColorDialog.value = true
                    Log.i("SlicerVM", "Multi-color detected: $extCount extruders, colors=${mfInfo.detectedColors}")
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
        val usedSlots = modelColorToExtruder.distinct().sorted()
        val extCount = usedSlots.size.coerceIn(1, 4)
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
        // Store per-extruder colors for G-code viewers: map slot index → slot color
        val detectedColors = _threeMfInfo.value?.detectedColors ?: emptyList()
        val slotColors = usedSlots.map { slotIndex ->
            extruderPresets.firstOrNull { it.index == slotIndex }?.color
                ?: detectedColors.getOrElse(slotIndex) { "#808080" }
        }
        _activeExtruderColors.value = slotColors
        Log.i("SlicerVM", "Applied color mapping: $extCount extruders used=${usedSlots}, temps=${temps.toList()}, colors=$slotColors")
    }

    fun dismissMultiColorDialog() {
        _showMultiColorDialog.value = false
    }

    fun showMultiColorReassign() {
        _showMultiColorDialog.value = true
    }

    fun setCopyCount(count: Int) {
        val mi = lastModelInfo
        val max = if (mi != null && mi.sizeX > 0f && mi.sizeY > 0f)
            CopyArrangeCalculator.maxCopies(mi.sizeX, mi.sizeY)
        else 16
        _copyCount.value = count.coerceIn(1, max)
        customObjectPositions = null // reset custom positions when count changes
    }

    /** Called from PlacementViewerScreen when user confirms positions. */
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

    /** Returns initial positions for PlacementViewerScreen (custom or auto-calculated). */
    fun getPlacementPositions(): FloatArray {
        customObjectPositions?.let { return it }
        val mi = lastModelInfo ?: return floatArrayOf(5f, 5f)
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

    /**
     * Build Snapmaker profile config and embed it into the 3MF file.
     * Replaces BambuSanitizer.process() for the OrcaSlicer backend.
     */
    private fun embedProfile(file: java.io.File, info: ThreeMfInfo, context: android.app.Application): java.io.File {
        val cfg = _config.value
        val extCount = cfg.extruderCount.coerceAtLeast(1)
        val sourceConfig = if (info.isBambu) {
            java.util.zip.ZipFile(file).use { profileEmbedder.parseSourceConfig(it) }
        } else null
        val embeddedConfig = profileEmbedder.buildConfig(
            info = info,
            sourceConfig = sourceConfig,
            overrides = buildProfileOverrides(cfg, extCount),
            targetExtruderCount = extCount
        )
        return profileEmbedder.embed(file, embeddedConfig, context.filesDir, info)
    }

    private fun buildProfileOverrides(cfg: SliceConfig, extCount: Int): Map<String, Any> {
        val temps = if (cfg.extruderTemps.size >= extCount) {
            cfg.extruderTemps.take(extCount).map { it.toString() }.toMutableList()
        } else {
            MutableList(extCount) { cfg.nozzleTemp.toString() }
        }
        return mapOf(
            "layer_height" to cfg.layerHeight.toString(),
            "initial_layer_print_height" to cfg.firstLayerHeight.toString(),
            "wall_loops" to cfg.perimeters.toString(),
            "top_shell_layers" to cfg.topSolidLayers.toString(),
            "bottom_shell_layers" to cfg.bottomSolidLayers.toString(),
            "sparse_infill_density" to "${(cfg.fillDensity * 100).toInt()}%",
            "travel_speed" to cfg.travelSpeed.toString(),
            "nozzle_temperature" to temps,
            "nozzle_temperature_initial_layer" to temps.toMutableList(),
            "bed_temperature" to mutableListOf(cfg.bedTemp.toString()),
            "bed_temperature_initial_layer" to mutableListOf(cfg.bedTemp.toString()),
            "enable_support" to if (cfg.supportEnabled) "1" else "0",
            "support_threshold_angle" to cfg.supportAngle.toInt().toString(),
            "brim_width" to cfg.brimWidth.toString()
        )
    }

    fun startSlicing() {
        viewModelScope.launch(Dispatchers.IO) {
            native.progressListener = { pct, stage ->
                _state.value = SlicerState.Slicing(pct, stage)
            }

            _state.value = SlicerState.Slicing(0, "Preparing...")

            // Apply object positions (custom from placement viewer, or auto grid if copies > 1)
            val copies = _copyCount.value
            val custom = customObjectPositions
            if (custom != null) {
                native.setModelInstances(custom)
                Log.i("SlicerVM", "Using custom placement: ${custom.size / 2} instances")
            } else if (copies > 1) {
                val mi = lastModelInfo
                if (mi != null && mi.sizeX > 0f && mi.sizeY > 0f) {
                    val positions = CopyArrangeCalculator.calculate(mi.sizeX, mi.sizeY, copies)
                    native.setModelInstances(positions)
                    Log.i("SlicerVM", "Auto-arranged $copies copies in grid (${positions.size / 2} actual)")
                }
            }

            val result = native.slice(_config.value)

            native.progressListener = null

            if (result != null && result.success) {
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
