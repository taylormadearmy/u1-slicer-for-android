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

/**
 * Parses 3MF files (ZIP archives) to extract object info, plate structure,
 * Bambu metadata, and multi-color information.
 *
 * Ported from u1-slicer-bridge: parser_3mf.py + multi_plate_parser.py
 */
object ThreeMfParser {
    private const val TAG = "ThreeMfParser"
    private const val NS_CORE = "http://schemas.microsoft.com/3dmanufacturing/core/2015/02"

    // Bambu detection markers
    private val BAMBU_MARKERS = setOf(
        "Metadata/model_settings.config",
        "Metadata/slice_info.config",
        "Metadata/filament_sequence.json"
    )

    fun parse(file: File): ThreeMfInfo {
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

                val modelBytes = zip.getInputStream(modelEntry).readBytes()
                val objects = parseObjects(modelBytes)
                val buildItems = parseBuildItems(modelBytes)

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

                // Read Bambu model_settings.config for plate names and extruder assignments
                val plateNames = mutableMapOf<Int, String>()
                val objectNames = mutableMapOf<String, String>()
                val extruderAssignments = mutableMapOf<String, Int>()

                if (isBambu) {
                    val msEntry = zip.getEntry("Metadata/model_settings.config")
                    if (msEntry != null) {
                        parseModelSettingsConfig(
                            zip.getInputStream(msEntry),
                            plateNames, objectNames, extruderAssignments
                        )
                    }
                }

                // Detect paint data
                val hasPaintData = detectPaintData(modelBytes)

                // Detect layer tool changes
                val hasLayerToolChanges = if (isBambu) {
                    val gcodeEntry = zip.getEntry("Metadata/custom_gcode_per_layer.xml")
                    gcodeEntry != null && detectLayerToolChanges(zip.getInputStream(gcodeEntry))
                } else false

                // Detect colors from multiple sources (priority order)
                val detectedColors = mutableListOf<String>()

                // 1. Bambu filament_sequence.json
                val filamentSeqEntry = zip.getEntry("Metadata/filament_sequence.json")
                if (filamentSeqEntry != null) {
                    detectColorsFromFilamentSequence(
                        zip.getInputStream(filamentSeqEntry), detectedColors
                    )
                }
                // 2. Bambu project_settings.config — JSON format
                if (detectedColors.isEmpty()) {
                    val projEntry = zip.getEntry("Metadata/project_settings.config")
                    if (projEntry != null) {
                        detectColorsFromJsonSettings(
                            zip.getInputStream(projEntry), detectedColors
                        )
                    }
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

                // Build plates with name resolution and extract PNG thumbnails
                val plates = buildItems.mapIndexed { idx, item ->
                    val plateId = idx + 1
                    val name = resolvePlateName(
                        plateId, item.objectId,
                        plateNames, objectNames, objects
                    )
                    // Try Bambu-style plate thumbnails: Metadata/plate_N.png
                    val thumbnailBytes = zip.getEntry("Metadata/plate_$plateId.png")
                        ?.let { entry -> runCatching { zip.getInputStream(entry).readBytes() }.getOrNull() }
                    ThreeMfPlate(
                        plateId = plateId,
                        name = name,
                        objectIds = listOf(item.objectId),
                        printable = item.printable,
                        transform = item.transform,
                        thumbnailBytes = thumbnailBytes
                    )
                }

                // Count unique extruders
                val uniqueExtruders = extruderAssignments.values.toSet()
                val hasMultiExtruderAssignments = uniqueExtruders.size > 1
                val extruderCount = maxOf(
                    1,
                    extruderAssignments.values.maxOrNull() ?: 0,
                    detectedColors.size
                )

                // 5. Synthesize placeholder colors when multi-extruder but no colors detected
                //    so the dialog always shows assignable rows instead of 0 rows.
                if (detectedColors.isEmpty() && extruderCount > 1) {
                    val palette = listOf("#CC3333", "#3399CC", "#33AA55", "#DDAA22")
                    repeat(extruderCount) { i -> detectedColors.add(palette[i % palette.size]) }
                }

                ThreeMfInfo(
                    objects = objects,
                    plates = plates,
                    isBambu = isBambu,
                    isMultiPlate = isMultiPlate,
                    hasPaintData = hasPaintData,
                    hasLayerToolChanges = hasLayerToolChanges,
                    hasMultiExtruderAssignments = hasMultiExtruderAssignments,
                    detectedColors = detectedColors,
                    detectedExtruderCount = extruderCount,
                    hasPlateJsons = plateJsonCount > 0
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse 3MF: ${e.message}")
            ThreeMfInfo(emptyList(), emptyList(), isBambu = false, isMultiPlate = false)
        }
    }

    private data class BuildItem(
        val objectId: String,
        val printable: Boolean,
        val transform: FloatArray
    )

    private fun parseObjects(modelBytes: ByteArray): List<ThreeMfObject> {
        val objects = mutableListOf<ThreeMfObject>()
        val parser = createParser(modelBytes.inputStream())

        var inResources = false
        var currentObjectId: String? = null
        var currentObjectName: String? = null
        var vertexCount = 0
        var triangleCount = 0

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    val localName = parser.name
                    when {
                        localName == "resources" -> inResources = true
                        localName == "object" && inResources -> {
                            currentObjectId = parser.getAttributeValue(null, "id")
                            currentObjectName = parser.getAttributeValue(null, "name")
                            vertexCount = 0
                            triangleCount = 0
                        }
                        localName == "vertex" -> vertexCount++
                        localName == "triangle" -> triangleCount++
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "object" && currentObjectId != null) {
                        if (vertexCount > 0) {
                            objects.add(ThreeMfObject(
                                objectId = currentObjectId!!,
                                name = currentObjectName ?: "Object_$currentObjectId",
                                vertices = vertexCount,
                                triangles = triangleCount
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

    private fun parseBuildItems(modelBytes: ByteArray): List<BuildItem> {
        val items = mutableListOf<BuildItem>()
        val parser = createParser(modelBytes.inputStream())

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

    private fun detectPaintData(modelBytes: ByteArray): Boolean {
        // Scan for paint_color or mmu_segmentation attributes
        val content = String(modelBytes, Charsets.UTF_8)
        return content.contains("paint_color") || content.contains("mmu_segmentation")
    }

    private fun detectLayerToolChanges(inputStream: InputStream): Boolean {
        // Look for type="2" entries (tool change G-code)
        val content = inputStream.bufferedReader().readText()
        return content.contains("type=\"2\"")
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
        extruderAssignments: MutableMap<String, Int>
    ) {
        try {
            val parser = createParser(inputStream)
            var currentPlateId: String? = null
            var currentObjectId: String? = null

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "plate" -> {
                                currentPlateId = parser.getAttributeValue(null, "plater_id")
                                    ?: parser.getAttributeValue(null, "id")
                            }
                            "object" -> {
                                currentObjectId = parser.getAttributeValue(null, "id")
                            }
                            "metadata" -> {
                                val key = parser.getAttributeValue(null, "key") ?: ""
                                val value = parser.getAttributeValue(null, "value") ?: ""
                                when {
                                    key == "plater_name" && currentPlateId != null -> {
                                        currentPlateId?.toIntOrNull()?.let { id ->
                                            if (value.isNotBlank()) plateNames[id] = value
                                        }
                                    }
                                    key == "name" && currentObjectId != null -> {
                                        objectNames[currentObjectId!!] = value
                                    }
                                    key == "extruder" && currentObjectId != null -> {
                                        value.toIntOrNull()?.let { ext ->
                                            // Track max extruder seen for this object (covers part-level assignments)
                                            val current = extruderAssignments[currentObjectId!!] ?: 0
                                            if (ext > current) extruderAssignments[currentObjectId!!] = ext
                                        }
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "plate" -> currentPlateId = null
                            "object" -> currentObjectId = null
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
