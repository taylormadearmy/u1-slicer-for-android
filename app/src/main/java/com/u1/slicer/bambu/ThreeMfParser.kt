package com.u1.slicer.bambu

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import kotlin.text.Charsets

/**
 * Parses 3MF files (ZIP archives) to extract object info, plate structure,
 * Bambu metadata, and multi-color information.
 *
 * Ported from u1-slicer-bridge: parser_3mf.py + multi_plate_parser.py
 */
object ThreeMfParser {
    private const val TAG = "ThreeMfParser"
    private const val NS_CORE = "http://schemas.microsoft.com/3dmanufacturing/core/2015/02"
    private const val MAX_SAFE_SINGLE_COMPONENT_BYTES = 256L * 1024L * 1024L
    private const val MAX_SAFE_TOTAL_COMPONENT_BYTES = 384L * 1024L * 1024L

    data class ArchiveSizingRisk(
        val largestComponentEntry: String,
        val largestComponentBytes: Long,
        val totalComponentBytes: Long
    )

    // Bambu detection markers
    private val BAMBU_MARKERS = setOf(
        "Metadata/model_settings.config",
        "Metadata/slice_info.config",
        "Metadata/filament_sequence.json",
        "Metadata/project_settings.config",
        "Metadata/custom_gcode_per_layer.xml"
    )

    fun inspectArchiveSizing(file: File): ArchiveSizingRisk? {
        if (!file.exists() || !file.name.endsWith(".3mf", ignoreCase = true)) return null
        return try {
            ZipFile(file).use { zip ->
                assessArchiveSizing(
                    zip.entries().toList().map { it.name to it.size }
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    internal fun assessArchiveSizing(entries: List<Pair<String, Long>>): ArchiveSizingRisk? {
        val componentEntries = entries.filter { (name, size) ->
            name.endsWith(".model", ignoreCase = true) &&
                name != "3D/3dmodel.model" &&
                size > 0
        }
        if (componentEntries.isEmpty()) return null

        val largest = componentEntries.maxByOrNull { it.second } ?: return null
        val total = componentEntries.sumOf { it.second }
        if (largest.second <= MAX_SAFE_SINGLE_COMPONENT_BYTES &&
            total <= MAX_SAFE_TOTAL_COMPONENT_BYTES
        ) {
            return null
        }
        return ArchiveSizingRisk(
            largestComponentEntry = largest.first,
            largestComponentBytes = largest.second,
            totalComponentBytes = total
        )
    }

    fun parse(file: File, skipPaintDetection: Boolean = false): ThreeMfInfo {
        if (!file.exists() || !file.name.endsWith(".3mf", ignoreCase = true)) {
            return ThreeMfInfo(
                objects = emptyList(),
                plates = emptyList(),
                isBambu = false,
                isMultiPlate = false
            )
        }

        return try {
            ZipFile(file).use { zip ->
                val entryNames = zip.entries().toList().map { it.name }.toSet()
                val isBambu = entryNames.any { it in BAMBU_MARKERS }

                // Parse main model file
                val modelEntry = zip.getEntry("3D/3dmodel.model")
                    ?: return ThreeMfInfo(emptyList(), emptyList(), isBambu, false)

                val objects = zip.getInputStream(modelEntry).use(::parseObjects)
                val buildItems = zip.getInputStream(modelEntry).use(::parseBuildItems)

                // Multi-plate detection: two complementary signals are checked.
                //
                // 1. Plate JSON count (new Bambu format): files with plate_N.json entries for
                //    each plate are definitively multi-plate when plateJsonCount > 1.
                //
                // 2. Virtual-plate item positions (old Bambu format, e.g. Dragon Scale /
                //    Shashibo): no plate JSONs, but printable items are laid out in a large
                //    virtual space with ~420 mm X-pitch and ~384 mm Y-pitch between plates.
                //    Any printable item with TX > 270 or TY outside [0, 270] is off the U1
                //    bed and therefore belongs to a different plate.
                //
                // Using build item count alone was wrong: multi-color single-plate models
                // (e.g. Benchy with a printable="0" reference item) also have > 1 build items.
                val plateJsonCount = entryNames.count { name ->
                    name.matches(Regex("Metadata/plate_\\d+\\.json"))
                }
                val hasVirtualPlateItems = buildItems.any { item ->
                    if (!item.printable) return@any false
                    val tx = if (item.transform.size > 9) item.transform[9] else 0f
                    val ty = if (item.transform.size > 10) item.transform[10] else 0f
                    tx > 270f || ty < 0f || ty > 270f
                }
                val isMultiPlate = plateJsonCount > 1 || hasVirtualPlateItems
                val componentModels = if (!skipPaintDetection) {
                    zip.entries().toList().filter { e ->
                        e.name.endsWith(".model") && e.name != "3D/3dmodel.model"
                    }
                } else {
                    emptyList()
                }

                // Read Bambu model_settings.config for plate names, extruder assignments,
                // and plate-to-object mappings (which objects belong to which plate).
                val plateNames = mutableMapOf<Int, String>()
                val objectNames = mutableMapOf<String, String>()
                val extruderAssignments = mutableMapOf<String, Int>()
                val plateObjectMap = mutableMapOf<Int, MutableList<String>>()
                val plateFilamentMap = mutableMapOf<Int, Set<Int>>()
                val plateFilamentSlotsMap = mutableMapOf<Int, Set<Int>>()

                val allExtruderValuesMain = mutableSetOf<Int>()
                val objectPartExtrudersMain = mutableMapOf<String, MutableSet<Int>>()
                val compoundPartParentsMain = mutableMapOf<String, String>()
                if (isBambu) {
                    val msEntry = zip.getEntry("Metadata/model_settings.config")
                    if (msEntry != null) {
                        parseModelSettingsConfig(
                            zip.getInputStream(msEntry),
                            plateNames, objectNames, extruderAssignments,
                            plateObjectMap,
                            plateFilamentMap,
                            plateFilamentSlotsMap,
                            allExtruderValues = allExtruderValuesMain,
                            objectPartExtruders = objectPartExtrudersMain,
                            compoundPartParents = compoundPartParentsMain
                        )
                        if (plateObjectMap.isNotEmpty()) {
                            Log.i(TAG, "Plate→object mapping: ${plateObjectMap.size} plates — $plateObjectMap")
                        }
                    }
                }

                // B57: Detect support painting — single-color Bambu files with paint_supports
                // attributes require the preserve path in ProfileEmbedder so embedded
                // enable_support and support_threshold_angle are not discarded.
                val hasPaintSupports = if (skipPaintDetection) false else {
                    (modelEntry != null && zip.getInputStream(modelEntry).use(::streamDetectPaintSupports)) ||
                        zip.entries().toList().any { e ->
                            e.name.endsWith(".model") && e.name != "3D/3dmodel.model" &&
                                streamDetectPaintSupports(zip.getInputStream(e))
                        }
                }

                // Detect paint data through the per-plate visual summary when possible.
                // Single-plate files keep the detailed paint-state decode. Multi-plate
                // files switch to a cheaper presence-only pass so the import path no
                // longer spends tens of seconds decoding paint specs that the native
                // plate-selection path will replace later anyway.
                val componentPathsByObject = if (!skipPaintDetection && isBambu && modelEntry != null && plateObjectMap.isNotEmpty()) {
                    zip.getInputStream(modelEntry).use(::parseComponentPaths)
                } else {
                    emptyMap()
                }
                val visualByPlateCache: Map<Int, PlateVisualInfo>
                val paintSummary: PaintDetectionSummary?
                val hasPaintData: Boolean
                if (skipPaintDetection) {
                    visualByPlateCache = emptyMap()
                    paintSummary = PaintDetectionSummary(hasPaintData = false, paintStateCount = 0)
                    hasPaintData = false
                } else if (isMultiPlate && componentPathsByObject.isNotEmpty()) {
                    val paintPresenceByPlate = computePaintPresenceByPlate(
                        zip = zip,
                        modelEntry = modelEntry,
                        plateObjectMap = plateObjectMap,
                        componentPathsByObject = componentPathsByObject
                    )
                    hasPaintData = paintPresenceByPlate.values.any { it }
                    visualByPlateCache = paintPresenceByPlate.mapValues { (plateId, hasPaint) ->
                        val objectExtruders = plateObjectMap[plateId]
                            ?.mapNotNull { extruderAssignments[it] }
                            ?.toSet()
                            ?: emptySet()
                        PlateVisualInfo(
                            count = if (hasPaint) maxOf(1, objectExtruders.size + 1) else objectExtruders.size,
                            hasPaint = hasPaint,
                            objectExtruders = objectExtruders,
                            paintExtruderStates = emptySet()
                        )
                    }
                    paintSummary = summarizePaintDetection(isMultiPlate, visualByPlateCache)
                } else {
                    val visual = if (isBambu && modelEntry != null && plateObjectMap.isNotEmpty()) {
                        computeVisualColorCountByPlate(
                            zip = zip,
                            modelEntry = modelEntry,
                            plateObjectMap = plateObjectMap,
                            componentPathsByObject = componentPathsByObject,
                            extruderAssignments = extruderAssignments
                        )
                    } else {
                        emptyMap()
                    }
                    visualByPlateCache = visual
                    paintSummary = if (visual.isNotEmpty()) {
                        summarizePaintDetection(isMultiPlate, visual)
                    } else {
                        null
                    }
                    hasPaintData = if (paintSummary != null) {
                        paintSummary.hasPaintData
                    } else {
                        // Fallback for single-plate or degenerate files: a quick byte scan is
                        // enough to answer the file-level boolean without decoding every paint
                        // spec string.
                        val mainHasPaint = zip.getInputStream(modelEntry).use(::streamDetectPaintData)
                        val componentHasPaint = componentModels.any { e ->
                            streamDetectPaintData(zip.getInputStream(e))
                        }
                        if (!mainHasPaint && !componentHasPaint && componentModels.isNotEmpty()) {
                            Log.d(TAG, "Byte-scan found no paint_color in main model or ${componentModels.size} component(s): ${componentModels.map { "${it.name} (${it.size}B)" }}")
                        }
                        mainHasPaint || componentHasPaint
                    }
                }
                // Detect layer tool changes and any per-layer colors embedded in the
                // custom_gcode_per_layer.xml metadata.  Hueforge-style files often use
                // this layer G-code instead of object-level color segmentation.
                val layerToolXml: String? = if (isBambu) {
                    zip.getEntry("Metadata/custom_gcode_per_layer.xml")
                        ?.let { zip.getInputStream(it).bufferedReader().readText() }
                } else null
                val layerToolInfo = layerToolXml?.let { parseLayerToolCustomGcodeXml(it) }
                val hasLayerToolChanges = layerToolInfo?.hasToolChanges == true
                val layerToolSegments: List<LayerToolSegment>? = if (hasLayerToolChanges && layerToolXml != null) {
                    parseLayerToolSegments(layerToolXml).takeIf { it.isNotEmpty() }
                } else null
                val perPlateLayerToolInfo: Map<Int, LayerToolCustomGcodeXmlInfo> =
                    if (layerToolXml != null) parseLayerToolCustomGcodeXmlPerPlate(layerToolXml) else emptyMap()

                // Detect colors from multiple sources (priority order)
                val detectedColors = mutableListOf<String>()

                // 1. Bambu filament_sequence.json
                val filamentSeqEntry = zip.getEntry("Metadata/filament_sequence.json")
                if (filamentSeqEntry != null) {
                    detectColorsFromFilamentSequence(
                        zip.getInputStream(filamentSeqEntry), detectedColors
                    )
                    if (detectedColors.isNotEmpty()) {
                        Log.d(TAG, "Color source: filament_sequence.json → ${detectedColors.size} colors")
                    }
                }
                // 2. Bambu project_settings.config — JSON format
                if (detectedColors.isEmpty()) {
                    val projEntry = zip.getEntry("Metadata/project_settings.config")
                    if (projEntry != null) {
                        detectColorsFromJsonSettings(
                            zip.getInputStream(projEntry), detectedColors
                        )
                        if (detectedColors.isNotEmpty()) {
                            Log.d(TAG, "Color source: project_settings.config → ${detectedColors.size} colors: $detectedColors")
                        }
                    }
                }

                // M3-B full-spectrum marker — Smart Paint stamps `full_spectrum_physical_count`
                // into project_settings.config when a mix slot is painted. Read it so the load
                // path can keep the physical filament count fixed (and treat higher paint states
                // as virtual mix filaments). Independent of the detectedColors short-circuit above.
                val fullSpectrumPhysicalCount: Int? = zip.getEntry("Metadata/project_settings.config")
                    ?.let { projEntry ->
                        runCatching {
                            val text = zip.getInputStream(projEntry).bufferedReader().use { it.readText() }
                            com.u1.slicer.bambu.readFullSpectrumPhysicalCount(text)
                        }.getOrNull()
                    }
                // 3. PrusaSlicer Slic3r_PE.config — semicolon-delimited INI
                //    Also check Slic3r_PE_model.config for per-object extruder assignments.
                if (detectedColors.isEmpty()) {
                    val slic3rEntry = zip.getEntry("Metadata/Slic3r_PE.config")
                    if (slic3rEntry != null) {
                        val slic3rColors = mutableListOf<String>()
                        detectColorsFromSlic3rConfig(zip.getInputStream(slic3rEntry), slic3rColors)
                        if (slic3rColors.size > 1) {
                            // Only add if file has per-object assignments or paint data
                            val hasAssignment = run {
                                val modelCfgEntry = zip.getEntry("Metadata/Slic3r_PE_model.config")
                                modelCfgEntry != null && detectSlic3rModelAssignments(zip.getInputStream(modelCfgEntry))
                            }
                            if (hasAssignment || hasPaintData) {
                                detectedColors.addAll(slic3rColors)
                            }
                        } else {
                            detectedColors.addAll(slic3rColors)
                        }
                    }
                }
                // 4. OrcaSlicer / generic config.ini — INI format
                if (detectedColors.isEmpty()) {
                    val configEntry = zip.getEntry("config.ini")
                        ?: zip.getEntry("Metadata/config.ini")
                    if (configEntry != null) {
                        detectColorsFromIniConfig(
                            zip.getInputStream(configEntry), detectedColors
                        )
                    }
                }

                // Hueforge-style per-layer tool changes can carry the real preview colors
                // even when the object geometry itself is single-colour.
                val layerToolColors = layerToolInfo?.colors.orEmpty()
                if (layerToolColors.isNotEmpty() && detectedColors.size <= 1) {
                    detectedColors.clear()
                    detectedColors.addAll(layerToolColors)
                }
                var paintStateCount = when {
                    skipPaintDetection -> 0
                    paintSummary != null -> paintSummary.paintStateCount
                    hasPaintData -> maxOf(1, detectedColors.size)
                    else -> 0
                }
                if (detectedColors.isNotEmpty() && paintStateCount > 0) {
                    // Use the detected physical palette as the upper bound for the
                    // file-level paint-state count. Some Bambu files expose extra
                    // object/extruder metadata on top of the same physical colors;
                    // the UI needs the palette size, not the object count.
                    paintStateCount = minOf(paintStateCount, detectedColors.size)
                }
                // Sub-plan #2c: compute per-plate paint state so ThreeMfPlate.hasPaintData
                // carries plate-scoped information. mergeThreeMfInfoForPlate reads this to
                // decide whether a selected plate is painted — replaces the pre-#2c Kotlin
                // extractPlate + parseForPlateSelection pass that derived hasPaintData by
                // running the parse on a single-plate extracted file.
                //
                // Review fix (cost): gate on file-level hasPaintData. On non-painted files
                // (which is the common case for multi-plate fixtures like Dragon Scale and
                // non-painted Buzz plates) the per-plate scan is a pure waste of IO — B93
                // regression risk. When hasPaintData is false we know no plate can be
                // painted, so every plate's hasPaintData is false by definition.
                // Sub-plan #2d: also capture per-plate paint extruder states so
                // ThreeMfPlate.paintExtruderStates can carry the full per-plate palette
                // for SEMM-painted plates (where extruders come from paint_color decode,
                // not object-level metadata). Used by buildSelectedPlateInfo to seed
                // usedExtruderIndices for plates like slip slide plate 3 (1 object with
                // 4 paint regions).
                val visualByPlate: Map<Int, PlateVisualInfo> = if (visualByPlateCache.isNotEmpty()) {
                    visualByPlateCache
                } else if (hasPaintData && plateObjectMap.size == 1) {
                    val plateId = plateObjectMap.keys.first()
                    val objectExtruders = plateObjectMap[plateId]
                        ?.mapNotNull { extruderAssignments[it] }
                        ?.toSet()
                        ?: emptySet()
                    mapOf(
                        plateId to PlateVisualInfo(
                            count = maxOf(1, paintStateCount),
                            hasPaint = true,
                            objectExtruders = objectExtruders,
                            paintExtruderStates = emptySet()
                        )
                    )
                } else {
                    emptyMap()
                }
                val paintByPlate: Map<Int, Boolean> = visualByPlate.mapValues { it.value.hasPaint }
                val paintExtruderStatesByPlate: Map<Int, Set<Int>> =
                    visualByPlate.mapValues { it.value.paintExtruderStates }

                // Build plates: use model_settings.config plate→object mappings when
                // available (groups multiple objects per plate correctly), otherwise
                // fall back to 1 build item = 1 plate (old behavior for non-Bambu files).
                val plates = if (plateObjectMap.isNotEmpty()) {
                    // Bambu format: plates defined in model_settings.config with
                    // model_instance entries mapping object_ids to each plate.
                    plateObjectMap.keys.sorted().map { plateId ->
                        val objIds = plateObjectMap[plateId] ?: emptyList()
                        val firstItem = buildItems.find { it.objectId in objIds }
                        val name = resolvePlateName(
                            plateId, firstItem?.objectId ?: "",
                            plateNames, objectNames, objects
                        )
                        val thumbnailBytes = zip.getEntry("Metadata/plate_$plateId.png")
                            ?.let { entry -> runCatching { zip.getInputStream(entry).readBytes() }.getOrNull() }
                        val plateLtInfo = perPlateLayerToolInfo[plateId]
                        ThreeMfPlate(
                            plateId = plateId,
                            name = name,
                            objectIds = objIds,
                            filamentIndices = plateFilamentMap[plateId] ?: emptySet(),
                            filamentMapSlots = plateFilamentSlotsMap[plateId] ?: emptySet(),
                            printable = firstItem?.printable ?: true,
                            transform = firstItem?.transform ?: floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f, 0f,0f,0f),
                            thumbnailBytes = thumbnailBytes,
                            layerToolColors = plateLtInfo?.colors.orEmpty(),
                            layerToolExtruders = plateLtInfo?.extruders.orEmpty(),
                            hasLayerToolChanges = plateLtInfo?.hasToolChanges == true,
                            hasPaintData = paintByPlate[plateId] == true,
                            paintExtruderStates = paintExtruderStatesByPlate[plateId] ?: emptySet()
                        )
                    }
                } else {
                    // Non-Bambu or old format: 1 build item = 1 plate
                    buildItems.mapIndexed { idx, item ->
                        val plateId = idx + 1
                        val name = resolvePlateName(
                            plateId, item.objectId,
                            plateNames, objectNames, objects
                        )
                        val thumbnailBytes = zip.getEntry("Metadata/plate_$plateId.png")
                            ?.let { entry -> runCatching { zip.getInputStream(entry).readBytes() }.getOrNull() }
                        ThreeMfPlate(
                            plateId = plateId,
                            name = name,
                            objectIds = listOf(item.objectId),
                            filamentIndices = plateFilamentMap[plateId] ?: emptySet(),
                            filamentMapSlots = plateFilamentSlotsMap[plateId] ?: emptySet(),
                            printable = item.printable,
                            transform = item.transform,
                            thumbnailBytes = thumbnailBytes
                        )
                    }
                }

                // Count unique extruders — use allExtruderValuesMain (collects ALL per-part
                // extruder values) so multi-part objects with different extruders are counted
                // correctly (B16 fix: max-per-object missed multi-part colour indices).
                val uniqueExtruders = if (allExtruderValuesMain.isNotEmpty())
                    allExtruderValuesMain else extruderAssignments.values.toSet()
                val layerToolExtruderCount = layerToolInfo?.extruders?.size ?: 0
                val extruderCount = maxOf(
                    1,
                    extruderAssignments.values.maxOrNull() ?: 0,
                    detectedColors.size,
                    layerToolExtruderCount,
                    paintStateCount  // B44: distinct paint states from model triangles
                )

                // 5. Synthesize placeholder colors when multi-extruder but no/insufficient colors detected
                //    so the dialog always shows assignable rows instead of missing rows.
                val palette = listOf("#CC3333", "#3399CC", "#33AA55", "#DDAA22")
                if (detectedColors.isEmpty() && extruderCount > 1) {
                    repeat(extruderCount) { i -> detectedColors.add(palette[i % palette.size]) }
                } else if (detectedColors.size < extruderCount) {
                    // B44: paint specs revealed more extruders than config listed colors — pad
                    while (detectedColors.size < extruderCount) {
                        detectedColors.add(palette[detectedColors.size % palette.size])
                    }
                }

                ThreeMfInfo(
                    objects = objects,
                    plates = plates,
                    isBambu = isBambu,
                    isMultiPlate = isMultiPlate,
                    hasPaintData = hasPaintData,
                    hasPaintSupports = hasPaintSupports,
                    hasLayerToolChanges = hasLayerToolChanges,
                    detectedColors = detectedColors,
                    detectedExtruderCount = extruderCount,
                    hasPlateJsons = plateJsonCount > 1,
                    usedExtruderIndices = uniqueExtruders,
                    volumeExtruders = uniqueExtruders,  // §4 Step 8
                    objectExtruderMap = extruderAssignments.toMap(),
                    objectPartExtruders = objectPartExtrudersMain.mapValues { it.value.toSet() },
                    compoundPartParents = compoundPartParentsMain.toMap(),
                    layerToolSegments = layerToolSegments,
                    fullSpectrumPhysicalCount = fullSpectrumPhysicalCount
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse 3MF: ${e.message}")
            ThreeMfInfo(emptyList(), emptyList(), isBambu = false, isMultiPlate = false)
        }
    }

    /**
     * Lightweight parse for plate selection — only reads model_settings.config
     * to extract usedExtruderIndices. Skips the 15MB+ main model XML entirely.
     * Returns a minimal ThreeMfInfo suitable for mergeThreeMfInfoForPlate().
     */
    fun parseForPlateSelection(file: File): ThreeMfInfo {
        return try {
            ZipFile(file).use { zip ->
                val entryNames = zip.entries().toList().map { it.name }.toSet()
                val isBambu = entryNames.any { it in BAMBU_MARKERS }
                val modelEntry = zip.getEntry("3D/3dmodel.model")

                val plateNames = mutableMapOf<Int, String>()
                val objectNames = mutableMapOf<String, String>()
                val extruderAssignments = mutableMapOf<String, Int>()
                val plateObjectMap = mutableMapOf<Int, MutableList<String>>()
                val plateFilamentMap = mutableMapOf<Int, Set<Int>>()
                val plateFilamentSlotsMapLite = mutableMapOf<Int, Set<Int>>()

                // Check model_settings.config first, then Slic3r_PE_model.config
                // (restructurePlateFile writes the latter for compound objects)
                val msEntry = zip.getEntry("Metadata/model_settings.config")
                    ?: zip.getEntry("Metadata/Slic3r_PE_model.config")
                val allExtruderValues = mutableSetOf<Int>()
                val objectPartExtrudersLite = mutableMapOf<String, MutableSet<Int>>()
                val compoundPartParentsLite = mutableMapOf<String, String>()
                if (msEntry != null) {
                    parseModelSettingsConfig(
                        zip.getInputStream(msEntry),
                        plateNames, objectNames, extruderAssignments,
                        plateObjectMap,
                        plateFilamentMap,
                        plateFilamentSlotsMapLite,
                        allExtruderValues = allExtruderValues,
                        objectPartExtruders = objectPartExtrudersLite,
                        compoundPartParents = compoundPartParentsLite
                    )
                }

                // Use allExtruderValues (collects ALL extruder indices from every
                // <part>/<metadata> entry) rather than extruderAssignments.values
                // which only tracks max-per-object and misses multi-part colours.
                val uniqueExtruders = if (allExtruderValues.isNotEmpty())
                    allExtruderValues else extruderAssignments.values.toSet()
                val componentPathsByObject = modelEntry
                    ?.let { entry -> zip.getInputStream(entry).use(::parseComponentPaths) }
                    .orEmpty()
                val visualColorCountByPlate = computeVisualColorCountByPlate(
                    zip = zip,
                    modelEntry = modelEntry,
                    plateObjectMap = plateObjectMap,
                    componentPathsByObject = componentPathsByObject,
                    extruderAssignments = extruderAssignments
                )
                val lightweightPlates = plateFilamentMap.keys.sorted().map { plateId ->
                    ThreeMfPlate(
                        plateId = plateId,
                        name = plateNames[plateId] ?: "Plate $plateId",
                        objectIds = plateObjectMap[plateId]?.toList() ?: emptyList(),
                        filamentIndices = plateFilamentMap[plateId] ?: emptySet(),
                        filamentMapSlots = plateFilamentSlotsMapLite[plateId] ?: emptySet(),
                        // Sub-plan #2c: per-plate paint state so mergeThreeMfInfoForPlate
                        // can derive hasPaintData from sourceInfo directly instead of
                        // re-running extractPlate + parseForPlateSelection on disk.
                        hasPaintData = visualColorCountByPlate[plateId]?.hasPaint == true
                    )
                }
                val hasPaintDataForPlate = visualColorCountByPlate.values.any { it.hasPaint }
                val effectiveExtruders = visualColorCountByPlate.values
                    .maxByOrNull { it.count }
                    ?.let { maxPlate ->
                        val visualCount = maxPlate.count
                        if (visualCount > uniqueExtruders.size) {
                            // B90: when the plate's real object extruders include indices
                            // strictly above `visualCount` (e.g. Buzz Lightyear plate 9:
                            // extruder=10, visualCount=2), the synthetic (1..visualCount)
                            // range drops those indices and substitutes low filaments that
                            // don't match the rendered mesh. Prefer the union of actual
                            // object extruders and paint states so `detectedColors` maps
                            // to the filaments the user would physically print.
                            //
                            // slip-slide plate 3 keeps the synthetic range: its object
                            // extruder (1) fits inside (1..visualCount=4), so the pre-B90
                            // B84 behaviour (4 distinct filament slots derived from the
                            // file's filament_colour array) is preserved.
                            val maxObjectExtruder = maxPlate.objectExtruders.maxOrNull() ?: 0
                            if (maxObjectExtruder > visualCount) {
                                (maxPlate.objectExtruders + maxPlate.paintExtruderStates)
                                    .filter { it > 0 }
                                    .toSortedSet()
                            } else {
                                (1..visualCount).toSet()
                            }
                        } else {
                            uniqueExtruders
                        }
                    } ?: uniqueExtruders
                val projectColors = mutableListOf<String>()
                zip.getEntry("Metadata/project_settings.config")?.let { entry ->
                    detectColorsFromJsonSettings(zip.getInputStream(entry), projectColors)
                }
                val layerToolInfo = zip.getEntry("Metadata/custom_gcode_per_layer.xml")
                    ?.let { entry ->
                        val xml = zip.getInputStream(entry).bufferedReader().readText()
                        parseLayerToolCustomGcodeXml(xml)
                    }
                val layerToolExtruders = layerToolInfo?.extruders.orEmpty()
                val filteredColors = linkedSetOf<String>()
                val filteredExtruders = (effectiveExtruders + layerToolExtruders)
                    .filter { it > 0 }
                    .toSortedSet()
                if (projectColors.isNotEmpty() && filteredExtruders.isNotEmpty()) {
                    filteredExtruders.forEach { extruderIndex ->
                        projectColors.getOrNull(extruderIndex - 1)?.let { filteredColors.add(it) }
                    }
                }
                layerToolInfo?.colors.orEmpty().forEach { filteredColors.add(it) }
                val detectedColors = filteredColors.toList()
                val detectedExtruderCount = when {
                    filteredExtruders.isNotEmpty() -> maxOf(filteredExtruders.size, detectedColors.size)
                    detectedColors.isNotEmpty() -> detectedColors.size
                    else -> 1
                }
                Log.i(
                    TAG,
                    "parseForPlateSelection: projectColors=${projectColors.size} layerToolColors=${layerToolInfo?.colors} " +
                        "layerToolExtruders=${layerToolInfo?.extruders} uniqueExtruders=$uniqueExtruders " +
                        "filteredExtruders=$filteredExtruders detectedColors=$detectedColors"
                )
                ThreeMfInfo(
                    objects = emptyList(),
                    plates = lightweightPlates,
                    isBambu = isBambu,
                    isMultiPlate = false,
                    hasLayerToolChanges = layerToolInfo?.hasToolChanges == true,
                    hasPaintData = hasPaintDataForPlate,
                    detectedColors = detectedColors,
                    detectedExtruderCount = detectedExtruderCount,
                    usedExtruderIndices = if (filteredExtruders.isNotEmpty()) filteredExtruders else effectiveExtruders,
                    objectExtruderMap = extruderAssignments.toMap(),
                    objectPartExtruders = objectPartExtrudersLite.mapValues { it.value.toSet() },
                    compoundPartParents = compoundPartParentsLite.toMap()
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parseForPlateSelection: ${e.message}")
            ThreeMfInfo(emptyList(), emptyList(), isBambu = false, isMultiPlate = false)
        }
    }

    private data class BuildItem(
        val objectId: String,
        val printable: Boolean,
        val transform: FloatArray
    )

    private fun parseObjects(inputStream: InputStream): List<ThreeMfObject> {
        val objects = mutableListOf<ThreeMfObject>()
        val parser = createParser(inputStream)

        var inResources = false
        var currentObjectId: String? = null
        var currentObjectName: String? = null
        var hasMesh = false

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    val localName = parser.name
                    when {
                        localName == "resources" -> inResources = true
                        localName == "object" && inResources -> {
                            currentObjectId = parser.getAttributeValue(null, "id")
                            currentObjectName = parser.getAttributeValue(null, "name")
                            hasMesh = false
                        }
                        localName == "mesh" -> hasMesh = true
                        localName == "vertices" || localName == "triangles" -> {
                            // Fast-forward over the millions of children to save parsing overhead
                            var depth = 1
                            while (depth > 0 && parser.eventType != XmlPullParser.END_DOCUMENT) {
                                when (parser.next()) {
                                    XmlPullParser.START_TAG -> depth++
                                    XmlPullParser.END_TAG -> depth--
                                }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "object" && currentObjectId != null) {
                        if (hasMesh) {
                            objects.add(ThreeMfObject(
                                objectId = currentObjectId!!,
                                name = currentObjectName ?: "Object_$currentObjectId",
                                vertices = 0,
                                triangles = 0
                            ))
                        }
                        currentObjectId = null
                        currentObjectName = null
                    }
                    if (parser.name == "resources") inResources = false
                }
            }
            parser.next()
        }
        return objects
    }

    private fun parseBuildItems(inputStream: InputStream): List<BuildItem> {
        val items = mutableListOf<BuildItem>()
        val parser = createParser(inputStream)

        var inBuild = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "build") inBuild = true
                    if (parser.name == "item" && inBuild) {
                        val objectId = parser.getAttributeValue(null, "objectid") ?: ""
                        val printable = parser.getAttributeValue(null, "printable") != "0"
                        val transformStr = parser.getAttributeValue(null, "transform") ?: ""
                        items.add(BuildItem(objectId, printable, parseTransform(transformStr)))
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "build") inBuild = false
                }
            }
            parser.next()
        }
        return items
    }

    private fun parseComponentPaths(inputStream: InputStream): Map<String, List<String>> {
        val componentPaths = mutableMapOf<String, MutableList<String>>()
        val parser = createParser(inputStream)
        var currentObjectId: String? = null

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "object" -> currentObjectId = parser.getAttributeValue(null, "id")
                        "component" -> {
                            val objectId = currentObjectId
                            val path = parser.getAttributeValue(null, "p:path")
                                ?: parser.getAttributeValue(null, "path")
                            if (objectId != null && !path.isNullOrBlank()) {
                                componentPaths.getOrPut(objectId) { mutableListOf() }
                                    .add(path.trimStart('/'))
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "object") currentObjectId = null
                }
            }
            parser.next()
        }

        return componentPaths
    }

    /**
     * Streaming paint data detection — reads from InputStream in chunks to avoid
     * loading large component .model files (15MB+) entirely into memory.
     * Searches for "paint_color" or "mmu_segmentation" in overlapping chunks.
     */
    private fun streamDetectPaintData(input: InputStream): Boolean {
        val needle1 = "paint_color".toByteArray()
        val needle2 = "mmu_segmentation".toByteArray()
        val maxNeedleLen = maxOf(needle1.size, needle2.size)
        val bufSize = 8192
        val buf = ByteArray(bufSize + maxNeedleLen)
        var carry = 0  // bytes carried over from previous chunk for overlap

        input.use {
            while (true) {
                val n = it.read(buf, carry, bufSize)
                if (n <= 0) break
                val total = carry + n
                if (containsBytes(buf, total, needle1) || containsBytes(buf, total, needle2)) {
                    return true
                }
                // Carry the tail for cross-boundary matches
                if (total > maxNeedleLen) {
                    System.arraycopy(buf, total - maxNeedleLen, buf, 0, maxNeedleLen)
                    carry = maxNeedleLen
                } else {
                    carry = total
                }
            }
        }
        return false
    }

    /**
     * Streaming detection for paint_supports attributes — single-color Bambu 3MF files
     * that use support painting store paint_supports on triangle elements.
     * Unlike paint_color/mmu_segmentation, this is NOT caught by streamDetectPaintData.
     * B57: files with paint_supports need the preserve path in ProfileEmbedder.buildConfig()
     * so the file's embedded enable_support/support_threshold_angle survive.
     */
    private fun streamDetectPaintSupports(input: InputStream): Boolean {
        val needle = "paint_supports".toByteArray()
        val bufSize = 8192
        val buf = ByteArray(bufSize + needle.size)
        var carry = 0
        input.use {
            while (true) {
                val n = it.read(buf, carry, bufSize)
                if (n <= 0) break
                val total = carry + n
                if (containsBytes(buf, total, needle)) return true
                if (total > needle.size) {
                    System.arraycopy(buf, total - needle.size, buf, 0, needle.size)
                    carry = needle.size
                } else {
                    carry = total
                }
            }
        }
        return false
    }

    /**
     * Streams paint_color / mmu_segmentation specs from a 3MF component model,
     * invoking [onSpec] for each occurrence. Returns early when the callback
     * throws [EarlyExit] so callers can stop after they've seen enough data —
     * Skywing's dragon has 162K paint_color attributes and reading them all was
     * blocking the UI for several minutes on plate selection (B91).
     */
    internal inline fun streamCollectPaintSpecs(
        input: InputStream,
        onSpec: (String) -> Unit
    ) {
        val n1 = "paint_color=\"".toByteArray()
        val n2 = "mmu_segmentation=\"".toByteArray()
        val bufSize = 65536
        val buf = ByteArray(bufSize)
        var carry = 0
        try {
            input.use {
                while (true) {
                    val read = it.read(buf, carry, bufSize - carry)
                    if (read <= 0) break
                    val total = carry + read
                    var pos = 0
                    while (pos < total) {
                        var matchIdx = -1
                        var matchLen = 0
                        var i1 = -1
                        for (i in pos until total - n1.size + 1) {
                            if (buf[i] == n1[0]) {
                                var match = true
                                for (j in 1 until n1.size) {
                                    if (buf[i + j] != n1[j]) { match = false; break }
                                }
                                if (match) { i1 = i; break }
                            }
                        }
                        var i2 = -1
                        for (i in pos until total - n2.size + 1) {
                            if (buf[i] == n2[0]) {
                                var match = true
                                for (j in 1 until n2.size) {
                                    if (buf[i + j] != n2[j]) { match = false; break }
                                }
                                if (match) { i2 = i; break }
                            }
                        }
                        
                        if (i1 != -1 && (i2 == -1 || i1 < i2)) { matchIdx = i1; matchLen = n1.size }
                        else if (i2 != -1) { matchIdx = i2; matchLen = n2.size }

                        if (matchIdx != -1) {
                            val vStart = matchIdx + matchLen
                            var vEnd = -1
                            for (i in vStart until total) {
                                if (buf[i] == '"'.code.toByte()) { vEnd = i; break }
                            }
                            if (vEnd != -1) {
                                val spec = String(buf, vStart, vEnd - vStart, Charsets.US_ASCII)
                                if (spec.isNotBlank()) onSpec(spec)
                                pos = vEnd + 1
                                continue
                            } else {
                                pos = matchIdx
                                break
                            }
                        } else {
                            break
                        }
                    }
                    val maxN = maxOf(n1.size, n2.size) + 2048
                    if (total > maxN && pos < total - maxN) {
                        System.arraycopy(buf, total - maxN, buf, 0, maxN)
                        carry = maxN
                    } else if (pos < total) {
                        val rem = total - pos
                        System.arraycopy(buf, pos, buf, 0, rem)
                        carry = rem
                    } else {
                        carry = 0
                    }
                    if (bufSize - carry == 0) {
                        // Safety fallback against pathological carry buildup
                        carry = 0
                    }
                }
            }
        } catch (_: EarlyExit) {
            // Callback signalled it has seen enough; stop reading the stream.
        }
    }

    internal object EarlyExit : RuntimeException() {
        private fun readResolve(): Any = EarlyExit
    }

    private val PAINT_SPEC_REGEX = Regex("""(?:paint_color|mmu_segmentation|slic3rpe:mmu_segmentation)="([^"]+)"""")

    private fun extractPaintSpec(line: String): String? {
        var idx = line.indexOf("paint_color=\"")
        if (idx == -1) idx = line.indexOf("mmu_segmentation=\"")
        if (idx == -1) return null

        val quoteStart = line.indexOf('"', idx) + 1
        val quoteEnd = line.indexOf('"', quoteStart)
        if (quoteEnd != -1) {
            return line.substring(quoteStart, quoteEnd)
        }
        return null
    }

    /**
     * Decode the first character of an OrcaSlicer paint_color / mmu_segmentation
     * spec to the 0-based paint-state index (0 = unpainted, 1..15 = filament
     * indices encoded as hex 1..F). Returns null for characters outside 0-9/A-Z.
     */
    internal fun paintCharToState(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'Z' -> c - 'A' + 10
        else -> null
    }

    /**
     * Per-plate visual colour metadata.
     * - [count]: total distinct colour regions visible on this plate (object extruders +
     *   paint visual count), used to size filament palettes.
     * - [hasPaint]: true when any non-zero paint state was observed.
     * - [objectExtruders]: 1-based filament indices assigned to objects on this plate.
     * - [paintExtruderStates]: non-zero paint states observed on this plate, interpreted
     *   as 1-based filament indices. Bambu Studio's SEMM encoding treats paint_color's
     *   first hex character as the filament index (1..F).
     */
    internal data class PlateVisualInfo(
        val count: Int,
        val hasPaint: Boolean,
        val objectExtruders: Set<Int>,
        val paintExtruderStates: Set<Int>
    )

    internal data class PaintDetectionSummary(
        val hasPaintData: Boolean,
        val paintStateCount: Int
    )

    internal fun summarizePaintDetection(
        isMultiPlate: Boolean,
        visualByPlate: Map<Int, PlateVisualInfo>
    ): PaintDetectionSummary {
        val hasPaintData = visualByPlate.values.any { it.hasPaint }
        val paintStateCount = if (isMultiPlate) 0 else visualByPlate.values.maxOfOrNull { it.count } ?: 0
        return PaintDetectionSummary(
            hasPaintData = hasPaintData,
            paintStateCount = paintStateCount
        )
    }

    /** Cheap per-plate paint-presence scan used by the multi-plate import fast path.
     *  It only answers "does this plate reference any painted component?" and avoids
     *  decoding individual paint specs, which is the expensive part of the older
     *  per-plate visual-colour pass. */
    internal fun computePaintPresenceByPlate(
        zip: ZipFile,
        modelEntry: java.util.zip.ZipEntry?,
        plateObjectMap: Map<Int, List<String>>,
        componentPathsByObject: Map<String, List<String>>,
        componentScanner: (String, java.io.InputStream) -> Boolean = { _, input -> streamDetectPaintData(input) }
    ): Map<Int, Boolean> {
        val fallbackPlateObjectMap = if (plateObjectMap.isNotEmpty() || modelEntry == null) {
            plateObjectMap
        } else {
            zip.getInputStream(modelEntry).use(::parseBuildItems).mapIndexed { index, item ->
                (index + 1) to listOf(item.objectId)
            }.toMap()
        }

        val componentPresenceCache = mutableMapOf<String, Boolean>()
        val uniqueComponentPaths = fallbackPlateObjectMap.values
            .asSequence()
            .flatMap { it.asSequence() }
            .flatMap { objectId -> componentPathsByObject[objectId].orEmpty().asSequence() }
            .toSet()
        for (path in uniqueComponentPaths) {
            val entry = zip.getEntry(path) ?: continue
            componentPresenceCache[path] = componentScanner(path, zip.getInputStream(entry))
        }

        return fallbackPlateObjectMap.mapValues { (_, objectIds) ->
            objectIds.any { objectId ->
                componentPathsByObject[objectId].orEmpty().any { path ->
                    componentPresenceCache[path] == true
                }
            }
        }
    }

    /** Per-component paint scan result, cached so Buzz-class multi-plate files don't
     *  re-read the same component file once per plate (~9× speedup on the 50 MB / 296K
     *  paint_color Buzz Lightyear fixture). */
    internal data class ComponentPaintInfo(
        val paintSpecs: Set<String>,
        val allPaintStates: Set<Int>,
        val paintExtruderStates: Set<Int>
    )

    /** Stream a single component .model file once; collect paint specs (capped) and
     *  decoded paint states. Honours the same simple/complex-encoding early-exit as
     *  the previous per-plate scan, scoped to this single component. */
    private fun scanComponentForPaintInfo(input: java.io.InputStream): ComponentPaintInfo {
        val specs = linkedSetOf<String>()
        val allStates = mutableSetOf<Int>()
        val paintStates = mutableSetOf<Int>()
        val cap = SPEC_CAP_FOR_COMPLEX_DETECTION
        streamCollectPaintSpecs(input) { spec ->
            if (specs.size < cap) specs.add(spec)
            val decoded = PaintColorDecoder.decodeStates(spec)
            if (decoded.isEmpty()) {
                if (spec.isNotEmpty()) allStates.add(0)
            } else {
                allStates.addAll(decoded)
                paintStates.addAll(decoded)
            }
            if (specs.size >= cap && allStates.size >= 2) throw EarlyExit
        }
        return ComponentPaintInfo(specs, allStates, paintStates)
    }

    internal fun computeVisualColorCountByPlate(
        zip: ZipFile,
        modelEntry: java.util.zip.ZipEntry?,
        plateObjectMap: Map<Int, List<String>>,
        componentPathsByObject: Map<String, List<String>>,
        extruderAssignments: Map<String, Int>,
        // Test seam (Review 3 pin tests): scanner receives both the component
        // path and its InputStream, so tests can return canned scan results
        // keyed by path without relying on Phase 1 iteration order. Production
        // callers use the default which ignores the path and streams real
        // paint specs from the InputStream.
        componentScanner: (String, java.io.InputStream) -> ComponentPaintInfo =
            { _, input -> scanComponentForPaintInfo(input) }
    ): Map<Int, PlateVisualInfo> {
        val fallbackPlateObjectMap = if (plateObjectMap.isNotEmpty() || modelEntry == null) {
            plateObjectMap
        } else {
            zip.getInputStream(modelEntry).use(::parseBuildItems).mapIndexed { index, item ->
                (index + 1) to listOf(item.objectId)
            }.toMap()
        }

        // Phase 1: scan each UNIQUE component file once and cache its paint info.
        // Multi-plate files like Buzz Lightyear share many components across plates;
        // the prior per-plate scan re-opened each component once per plate it was
        // referenced from, multiplying I/O by the plate count. The shared cache below
        // bounds the work to one read per component, then aggregates per plate from
        // the cache in Phase 2.
        val componentPaintCache = mutableMapOf<String, ComponentPaintInfo>()
        val uniqueComponentPaths = fallbackPlateObjectMap.values
            .asSequence()
            .flatMap { it.asSequence() }
            .flatMap { objectId -> componentPathsByObject[objectId].orEmpty().asSequence() }
            .toSet()
        for (path in uniqueComponentPaths) {
            val entry = zip.getEntry(path) ?: continue
            componentPaintCache[path] = componentScanner(path, zip.getInputStream(entry))
        }

        // Phase 2: per-plate aggregation. Each plate's PlateVisualInfo is the union of
        // its objects' component paint info, joined against the cache.
        return fallbackPlateObjectMap.mapValues { (_, objectIds) ->
            val objectExtruderSet = objectIds.mapNotNull { extruderAssignments[it] }.toSet()
            // paintSpecs: distinct spec strings — used to detect simple vs complex encoding.
            // paintExtruderStates: non-zero states only — used for hasPaintData and as the
            //   visual count for complex-encoded SEMM models (many specs, few states, e.g.
            //   painted flippy: 708 unique spec strings for only 2 extruder states — B81).
            // allPaintStates: all states including 0 — size matches paintSpecs size on
            //   simple-encoded models (one spec per state, e.g. slip-slide: "0C","1C","8").
            val paintSpecs = linkedSetOf<String>()
            val paintExtruderStates = mutableSetOf<Int>()
            val allPaintStates = mutableSetOf<Int>()
            for (objectId in objectIds) {
                for (path in componentPathsByObject[objectId].orEmpty()) {
                    val info = componentPaintCache[path] ?: continue
                    paintSpecs.addAll(info.paintSpecs)
                    allPaintStates.addAll(info.allPaintStates)
                    paintExtruderStates.addAll(info.paintExtruderStates)
                }
            }
            // Simple encoding (1 spec per state): include state 0 as a distinct region when
            // there is only one object extruder on the plate, so the pre-B81 count is
            // preserved (e.g. slip-slide: 1 object + 3 paint states → 4 colours).
            // Multi-object painted plates already account for the object palette separately,
            // so adding paintVisualCount on top would double-count files like colored Benchy
            // (4 object colours + 4 paint states must still report 4 visible colours).
            // Complex encoding (many specs, few states): use non-zero state count to avoid
            // inflation (e.g. painted flippy: 708 specs, 2 states → count 1 → 1+1=2 — B81).
            val paintVisualCount = if (paintSpecs.size == allPaintStates.size) {
                paintSpecs.size
            } else {
                paintExtruderStates.size
            }
            val visualColorCount = if (objectExtruderSet.size <= 1) {
                objectExtruderSet.size + paintVisualCount
            } else {
                maxOf(objectExtruderSet.size, paintVisualCount)
            }
            PlateVisualInfo(
                count = visualColorCount,
                hasPaint = paintExtruderStates.isNotEmpty(),
                objectExtruders = objectExtruderSet,
                paintExtruderStates = paintExtruderStates
            )
        }
    }

    /**
     * Complex SEMM encoding is detected when unique spec strings greatly exceed
     * distinct paint states. The upper bound for simple encoding is 16 (one spec
     * per state 0..F); any model past the cap is definitively complex-encoded, so
     * we stop growing the set to keep `computeVisualColorCountByPlate` fast on
     * dense painted models (B91 — Skywing's extracted plate was spending >3 min
     * on this step).
     */
    private const val SPEC_CAP_FOR_COMPLEX_DETECTION = 32

    private fun containsBytes(haystack: ByteArray, haystackLen: Int, needle: ByteArray): Boolean {
        if (needle.size > haystackLen) return false
        outer@ for (i in 0..haystackLen - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    private fun detectColorsFromFilamentSequence(
        inputStream: InputStream,
        colors: MutableList<String>
    ) {
        try {
            val json = inputStream.bufferedReader().readText()
            // Simple extraction of color hex values from JSON
            val colorRegex = Regex(""""color"\s*:\s*"(#[0-9A-Fa-f]{6})"""")
            colorRegex.findAll(json).forEach { match ->
                val color = match.groupValues[1]
                if (color !in colors) colors.add(color)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse filament_sequence.json: ${e.message}")
        }
    }

    /**
     * Bambu project_settings.config is JSON. Extract filament_colour or extruder_colour arrays.
     * Prefers colors for extruders actually assigned to objects (extruder_assignments from
     * model_settings.config are passed in via the caller's extruderAssignments map).
     */
    private fun detectColorsFromJsonSettings(
        inputStream: InputStream,
        colors: MutableList<String>
    ) {
        try {
            val content = inputStream.bufferedReader().readText().trim()
            val json = try { JSONObject(content) } catch (_: JSONException) { return }
            val colorRegex = Regex("#[0-9A-Fa-f]{6,8}")
            fun addFromArray(arr: JSONArray?) {
                if (arr == null) return
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i, "")
                    colorRegex.find(v)?.value?.take(7)?.let { c ->
                        if (c !in colors) colors.add(c)
                    }
                }
            }
            // filament_colour takes priority (per-filament), then extruder_colour (per-slot)
            addFromArray(json.optJSONArray("filament_colour"))
            if (colors.isEmpty()) addFromArray(json.optJSONArray("extruder_colour"))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse project_settings.config as JSON: ${e.message}")
        }
    }

    /**
     * PrusaSlicer Slic3r_PE.config — semicolon-delimited INI.
     * extruder_colour = #CC0000;#33AAFF
     */
    private fun detectColorsFromSlic3rConfig(
        inputStream: InputStream,
        colors: MutableList<String>
    ) {
        try {
            val content = inputStream.bufferedReader().readText()
            val colorRegex = Regex("#[0-9A-Fa-f]{6,8}")
            for (line in content.lines()) {
                val stripped = line.trimStart(';', ' ')
                if (stripped.startsWith("extruder_colour") || stripped.startsWith("filament_colour")) {
                    val value = stripped.substringAfter('=').trim()
                    value.split(";").forEach { part ->
                        colorRegex.find(part.trim())?.value?.take(7)?.let { c ->
                            if (c !in colors) colors.add(c)
                        }
                    }
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Slic3r_PE.config: ${e.message}")
        }
    }

    /** Returns true if Slic3r_PE_model.config has objects with different extruder assignments. */
    private fun detectSlic3rModelAssignments(inputStream: InputStream): Boolean {
        return try {
            val parser = createParser(inputStream)
            val assignedExtruders = mutableSetOf<String>()
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "metadata") {
                    val key = parser.getAttributeValue(null, "key") ?: ""
                    val type = parser.getAttributeValue(null, "type") ?: ""
                    val value = parser.getAttributeValue(null, "value") ?: ""
                    if (key == "extruder" && type == "object" && value.isNotBlank()) {
                        assignedExtruders.add(value)
                    }
                }
                parser.next()
            }
            assignedExtruders.size > 1
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Generic INI-style config (OrcaSlicer config.ini).
     * Looks for filament_colour or extruder_colour = #hex;#hex lines.
     */
    private fun detectColorsFromIniConfig(
        inputStream: InputStream,
        colors: MutableList<String>
    ) {
        try {
            val content = inputStream.bufferedReader().readText()
            val lineRegex = Regex("""(?:filament_colour|extruder_colour)\s*=\s*(.+)""")
            val colorRegex = Regex("#[0-9A-Fa-f]{6,8}")
            lineRegex.findAll(content).forEach { match ->
                val valStr = match.groupValues[1].trim()
                colorRegex.findAll(valStr).forEach { colorMatch ->
                    val color = colorMatch.value.take(7)
                    if (color !in colors) colors.add(color)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse ini config: ${e.message}")
        }
    }

    private fun parseModelSettingsConfig(
        inputStream: InputStream,
        plateNames: MutableMap<Int, String>,
        objectNames: MutableMap<String, String>,
        extruderAssignments: MutableMap<String, Int>,
        plateObjectMap: MutableMap<Int, MutableList<String>> = mutableMapOf(),
        plateFilamentMap: MutableMap<Int, Set<Int>> = mutableMapOf(),
        plateFilamentSlotsMap: MutableMap<Int, Set<Int>> = mutableMapOf(),
        allExtruderValues: MutableSet<Int>? = null,
        objectPartExtruders: MutableMap<String, MutableSet<Int>>? = null,
        compoundPartParents: MutableMap<String, String>? = null
    ) {
        try {
            val parser = createParser(inputStream)
            var currentPlateId: String? = null
            var currentObjectId: String? = null
            var currentPartId: String? = null
            var currentObjectExtruder: Int? = null  // object-level default extruder
            var inPlate = false
            var inModelInstance = false

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "plate" -> {
                                inPlate = true
                                // Some formats have plater_id as attribute; most use nested <metadata>
                                currentPlateId = parser.getAttributeValue(null, "plater_id")
                                    ?: parser.getAttributeValue(null, "id")
                            }
                            "model_instance" -> {
                                inModelInstance = true
                            }
                            "object" -> {
                                currentObjectId = parser.getAttributeValue(null, "id")
                                currentObjectExtruder = null
                            }
                            "part" -> {
                                // <part id="N"> inside <object> — compound object component.
                                // The part id matches the inlined mesh object ID in restructured
                                // files, so we track it for per-part extruder assignment.
                                currentPartId = parser.getAttributeValue(null, "id")
                                // Sub-plan #2c fix: track part→parent mapping so plate-scoped
                                // filters (buildSelectedPlateInfo) can identify parts as
                                // belonging to their parent object's plate.
                                val partId = currentPartId
                                val objId = currentObjectId
                                if (partId != null && objId != null) {
                                    compoundPartParents?.put(partId, objId)
                                }
                            }
                            "metadata" -> {
                                val key = parser.getAttributeValue(null, "key") ?: ""
                                val value = parser.getAttributeValue(null, "value") ?: ""
                                when {
                                    // <plate> → <metadata key="plater_id" value="N"/>
                                    key == "plater_id" && inPlate -> {
                                        currentPlateId = value
                                    }
                                    key == "plater_name" && currentPlateId != null -> {
                                        currentPlateId?.toIntOrNull()?.let { id ->
                                            if (value.isNotBlank()) plateNames[id] = value
                                        }
                                    }
                                    key == "filament_maps" && currentPlateId != null -> {
                                        currentPlateId?.toIntOrNull()?.let { id ->
                                            // filament_maps values are AMS-slot assignments, one per
                                            // file-filament position (e.g. "1 1" = both filaments
                                            // mapped to AMS slot 1).
                                            //
                                            // plateFilamentMap: POSITIONS of non-zero entries (1-indexed)
                                            // → which file-filaments are active on this plate.
                                            // Consumed by computePlateFileIndices (subtracts 1 to get
                                            // 0-indexed canonical positions). "1 1" → {1, 2} (both
                                            // file-filament 0 and 1 are active). B120 fix: collecting
                                            // values instead of positions broke when multiple filaments
                                            // shared the same AMS slot ("1 1" → {1} → only canonical 0).
                                            //
                                            // plateFilamentSlotsMap: UNIQUE non-zero slot VALUES
                                            // → which physical extruder slots are in use on this plate.
                                            // Consumed by enrichment and extruder-set builders.
                                            // "1 1" → {1} (only 1 physical AMS slot used).
                                            val tokens = value.trim().split(Regex("\\s+"))
                                            val filamentIndices = tokens
                                                .mapIndexedNotNull { idx, token ->
                                                    if ((token.toIntOrNull() ?: 0) > 0) idx + 1 else null
                                                }
                                                .toSet()
                                            val filamentSlots = tokens
                                                .mapNotNull { token -> token.toIntOrNull() }
                                                .filter { it > 0 }
                                                .toSet()
                                            if (filamentIndices.isNotEmpty()) {
                                                plateFilamentMap[id] = filamentIndices
                                            }
                                            if (filamentSlots.isNotEmpty()) {
                                                plateFilamentSlotsMap[id] = filamentSlots
                                            }
                                        }
                                    }
                                    // plate → model_instance → <metadata key="object_id" value="N"/>
                                    key == "object_id" && inModelInstance && currentPlateId != null -> {
                                        currentPlateId?.toIntOrNull()?.let { plateId ->
                                            plateObjectMap.getOrPut(plateId) { mutableListOf() }.add(value)
                                        }
                                    }
                                    key == "name" && currentObjectId != null && currentPartId == null -> {
                                        objectNames[currentObjectId!!] = value
                                    }
                                    key == "extruder" && currentObjectId != null -> {
                                        value.toIntOrNull()?.let { ext ->
                                            if (currentPartId != null) {
                                                // Per-part extruder: map part ID → extruder.
                                                // Part IDs match component mesh object IDs in
                                                // restructured files, enabling per-component
                                                // coloring downstream.
                                                extruderAssignments[currentPartId!!] = ext
                                            } else {
                                                // Object-level extruder: track as default for
                                                // this object and as max for the object entry.
                                                currentObjectExtruder = ext
                                                val current = extruderAssignments[currentObjectId!!] ?: 0
                                                if (ext > current) extruderAssignments[currentObjectId!!] = ext
                                            }
                                            allExtruderValues?.add(ext)
                                            // Sub-plan #2c fix: track ALL extruders used by this
                                            // object, including per-part extruders. Unlike
                                            // extruderAssignments (max-per-object), this captures
                                            // the full per-part palette — critical for compound
                                            // objects like Dragon Scale plate 3 (one object, three
                                            // parts each on a different extruder) where the
                                            // object-level-only view would collapse to a single
                                            // extruder in buildSelectedPlateInfo.
                                            objectPartExtruders
                                                ?.getOrPut(currentObjectId!!) { mutableSetOf() }
                                                ?.add(ext)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "plate" -> { currentPlateId = null; inPlate = false }
                            "model_instance" -> inModelInstance = false
                            "object" -> { currentObjectId = null; currentObjectExtruder = null }
                            "part" -> currentPartId = null
                        }
                    }
                }
                parser.next()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse model_settings.config: ${e.message}")
        }
    }

    /**
     * Resolve plate name using 5-level priority:
     * 1. Bambu plate name from model_settings.config
     * 2. Bambu object name from model_settings.config
     * 3. Object name from 3dmodel.model
     * 4. Fallback "Plate N"
     */
    private fun resolvePlateName(
        plateId: Int,
        objectId: String,
        plateNames: Map<Int, String>,
        objectNames: Map<String, String>,
        objects: List<ThreeMfObject>
    ): String {
        // 1. Bambu plate name
        plateNames[plateId]?.let { if (it.isNotBlank()) return it }
        // 2. Bambu object name
        objectNames[objectId]?.let { if (it.isNotBlank()) return it }
        // 3. Model object name
        objects.find { it.objectId == objectId }?.let {
            if (it.name.isNotBlank() && !it.name.startsWith("Object_")) return it.name
        }
        // 4. Fallback
        return "Plate $plateId"
    }

    /**
     * Parse 3MF transform string (12 space-separated floats for 3x4 affine matrix).
     * Format: m00 m01 m02 m10 m11 m12 m20 m21 m22 tx ty tz
     */
    private fun parseTransform(transformStr: String): FloatArray {
        if (transformStr.isBlank()) {
            return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f) // identity
        }
        val values = transformStr.trim().split("\\s+".toRegex()).mapNotNull { it.toFloatOrNull() }
        return when {
            values.size == 12 -> values.toFloatArray()
            values.size == 16 -> floatArrayOf(
                values[0], values[1], values[2],
                values[4], values[5], values[6],
                values[8], values[9], values[10],
                values[12], values[13], values[14]
            )
            else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f)
        }
    }

    private fun createParser(inputStream: InputStream): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false // We handle namespaces manually
        val parser = factory.newPullParser()
        parser.setInput(inputStream, "UTF-8")
        return parser
    }
}
