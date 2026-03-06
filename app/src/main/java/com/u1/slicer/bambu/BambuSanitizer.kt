package com.u1.slicer.bambu

import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Sanitizes Bambu Studio 3MF files for compatibility with PrusaSlicer.
 *
 * Ported from u1-slicer-bridge: profile_embedder.py
 *
 * Operations:
 * 1. Strip incompatible Bambu metadata files
 * 2. Clamp Bambu "-1" auto values to safe minimums
 * 3. Replace 'nil' strings with first non-nil value in arrays
 * 4. Convert PrusaSlicer mmu_segmentation → paint_color
 * 5. Recenter packed multi-plate transforms
 * 6. Clear stale plater_name values
 */
object BambuSanitizer {
    private const val TAG = "BambuSanitizer"

    // Files to drop entirely
    private val DROP_FILES = setOf(
        "Metadata/slice_info.config",
        "Metadata/cut_information.xml",
        "Metadata/filament_sequence.json",
        "Metadata/Slic3r_PE.config",
        "Metadata/Slic3r_PE_model.config"
    )

    // Preview image prefixes to drop
    private val DROP_PREFIXES = listOf(
        "Metadata/plate",
        "Metadata/top",
        "Metadata/pick"
    )

    // Parameters to clamp to minimum value
    private val CLAMP_RULES = mapOf(
        "raft_first_layer_expansion" to 0f,
        "tree_support_wall_count" to 0f,
        "prime_volume" to 0f,
        "prime_tower_brim_width" to 0f,
        "prime_tower_brim_chamfer" to 0f,
        "prime_tower_brim_chamfer_max_width" to 0f,
        "solid_infill_filament" to 1f,
        "sparse_infill_filament" to 1f,
        "wall_filament" to 1f
    )

    // Keys that are NOT per-filament arrays (excluded from normalization)
    private val NON_FILAMENT_KEYS = setOf(
        "compatible_printers",
        "compatible_prints"
    )

    // Keys with special list semantics (excluded from normalize, but padded)
    private val SPECIAL_LIST_KEYS = setOf(
        "flush_volumes_matrix",
        "flush_volumes_vector",
        "different_settings_to_system",
        "inherits_group",
        "upward_compatible_machine",
        "printable_area",
        "bed_exclude_area",
        "thumbnails",
        "head_wrap_detect_zone",
        "extruder_offset",
        "wipe_tower_x",
        "wipe_tower_y"
    )

    /**
     * Process a 3MF file: detect if Bambu, sanitize if needed, return the
     * (possibly modified) file path. If not Bambu, returns the original path unchanged.
     */
    fun process(inputFile: File, outputDir: File): File {
        val info = ThreeMfParser.parse(inputFile)
        if (!info.isBambu) {
            Log.i(TAG, "Not a Bambu file, no sanitization needed")
            return inputFile
        }

        Log.i(TAG, "Bambu file detected, sanitizing...")
        val outputFile = File(outputDir, "sanitized_${inputFile.name}")

        try {
            ZipFile(inputFile).use { srcZip ->
                ZipOutputStream(FileOutputStream(outputFile)).use { destZip ->
                    // 3MF requires STORED compression — PrusaSlicer rejects DEFLATED entries
                    var projectSettingsWritten = false
                    // objectId → ordered list of (faceCount, extruder) per part, built while processing model_settings.config
                    val bambuObjectParts = mutableMapOf<String, MutableList<PartInfo>>()
                    // Buffer the main model file for restructuring after we know the extruder assignments
                    var mainModelContent: ByteArray? = null
                    // Track component file names — content loaded on-demand from srcZip to reduce memory
                    val componentFileNames = mutableListOf<String>()
                    // Buffer model rels file — only dropped when restructuring inlines meshes
                    var modelRelsContent: ByteArray? = null

                    for (entry in srcZip.entries()) {
                        val name = entry.name

                        // Drop incompatible files
                        if (name in DROP_FILES) {
                            Log.d(TAG, "Dropping: $name")
                            continue
                        }

                        // Drop preview images
                        if (DROP_PREFIXES.any { name.startsWith(it) }) {
                            continue
                        }

                        // Buffer model rels file — decision to keep/drop made after
                        // restructuring (only drop when meshes are inlined)
                        if (name.endsWith(".rels") && name.contains("3dmodel.model")) {
                            modelRelsContent = srcZip.getInputStream(entry).readBytes()
                            continue
                        }

                        // Drop Bambu Auxiliaries — thumbnails/images not needed
                        if (name.startsWith("Auxiliaries/")) {
                            continue
                        }

                        // Read the entry content
                        val content = srcZip.getInputStream(entry).readBytes()

                        when {
                            // Read project_settings.config for data but don't write to output
                            // (Bambu JSON/INI config can confuse PrusaSlicer's 3MF loader)
                            name == "Metadata/project_settings.config" -> {
                                projectSettingsWritten = true
                            }

                            // Read model_settings.config for extruder assignments but don't write
                            // (references pre-restructured object IDs that no longer exist)
                            name == "Metadata/model_settings.config" -> {
                                parseModelSettingsExtruders(content, bambuObjectParts)
                            }

                            // Buffer all .model files — main one for restructuring,
                            // others for mesh inlining during restructuring
                            name.endsWith(".model") -> {
                                if (name == "3D/3dmodel.model") {
                                    mainModelContent = content
                                    // Don't write yet — written after restructuring below
                                } else {
                                    componentFileNames.add(name)
                                    // Don't read content yet — loaded on-demand below to save memory
                                }
                            }

                            // Clean _rels/.rels: keep only the main model relationship
                            name == "_rels/.rels" -> {
                                val cleanRels = """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
 <Relationship Target="/3D/3dmodel.model" Id="rel-1" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>
</Relationships>"""
                                writeStored(destZip, name, cleanRels.toByteArray())
                            }

                            // Clean [Content_Types].xml: only model and rels types
                            name == "[Content_Types].xml" -> {
                                val cleanTypes = """<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
 <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
 <Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/>
</Types>"""
                                writeStored(destZip, name, cleanTypes.toByteArray())
                            }

                            // Pass through everything else
                            else -> {
                                writeStored(destZip, name, content)
                            }
                        }
                    }

                    // Write (possibly restructured) main model file
                    if (mainModelContent != null) {
                        // Restructure compound multi-color objects into separate build items
                        val hasMultiColorComponents = bambuObjectParts.values.any { parts ->
                            parts.map { it.extruder }.distinct().size > 1
                        }
                        // Load component files on-demand only when restructuring needs them
                        val componentFiles = if (hasMultiColorComponents) {
                            componentFileNames.associateWith { name ->
                                srcZip.getEntry(name)?.let { srcZip.getInputStream(it).readBytes() } ?: ByteArray(0)
                            }
                        } else {
                            emptyMap()
                        }

                        val (finalModelBytes, newObjectExtruders) = if (hasMultiColorComponents) {
                            restructureForMultiColor(mainModelContent!!, bambuObjectParts, componentFiles)
                        } else {
                            Pair(convertMmuSegmentation(mainModelContent!!), emptyMap())
                        }

                        // Clean model XML: strip Bambu extensions.
                        // For restructured files: strip everything including p:path.
                        // For non-restructured files: preserve p:path (needed for component refs).
                        val cleanedModel = if (hasMultiColorComponents && newObjectExtruders.isNotEmpty()) {
                            cleanModelXml(finalModelBytes)
                        } else {
                            cleanModelXmlPreserveComponentRefs(finalModelBytes)
                        }
                        writeStored(destZip, "3D/3dmodel.model", cleanedModel)

                        val wasRestructured = newObjectExtruders.isNotEmpty()

                        // Write component files and rels:
                        // - If restructured (meshes inlined): skip component files + rels entirely
                        // - If not restructured: write them (needed for component refs)
                        if (!wasRestructured) {
                            // Copy component files from source ZIP — stream without full decompression
                            // to avoid OOM on large files (e.g. 27MB Turtle → 150MB uncompressed)
                            for (path in componentFileNames) {
                                val srcEntry = srcZip.getEntry(path) ?: continue
                                copyZipEntry(srcZip, srcEntry, destZip)
                            }
                            // Write the model rels file so PrusaSlicer can discover component files
                            if (modelRelsContent != null) {
                                writeStored(destZip, "3D/_rels/3dmodel.model.rels", modelRelsContent!!)
                            }
                        } else {
                            Log.d(TAG, "Skipping ${componentFileNames.size} component file(s) — meshes inlined")
                        }

                        // Override bambuObjectParts with the new per-object extruder assignments
                        if (newObjectExtruders.isNotEmpty()) {
                            // Collect face counts from original parts before clearing
                            val originalParts = bambuObjectParts.values.flatMap { it }
                            bambuObjectParts.clear()
                            var idx = 0
                            for ((objId, ext) in newObjectExtruders.entries.sortedBy { it.key.toIntOrNull() ?: 0 }) {
                                val faceCount = originalParts.getOrNull(idx)?.faceCount ?: 128
                                bambuObjectParts[objId] = mutableListOf(PartInfo(faceCount, ext))
                                idx++
                            }
                        }
                    }

                    // If no project_settings.config existed, that's fine
                    if (!projectSettingsWritten) {
                        Log.d(TAG, "No project_settings.config found in source")
                    }

                    // Inject Slic3r_PE_model.config so PrusaSlicer sees per-object extruder assignments.
                    // After restructuring, bambuObjectParts contains simple object-level extruder values.
                    val needsModelConfig = bambuObjectParts.values.any { parts ->
                        parts.any { it.extruder > 1 }
                    }
                    if (needsModelConfig) {
                        val slic3rModelConfig = buildSlic3rModelConfig(bambuObjectParts)
                        writeStored(destZip, "Metadata/Slic3r_PE_model.config", slic3rModelConfig.toByteArray())
                        Log.i(TAG, "Generated Slic3r_PE_model.config:\n$slic3rModelConfig")
                    }
                }
            }

            Log.i(TAG, "Sanitization complete: ${outputFile.absolutePath}")
            return outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Sanitization failed: ${e.message}")
            // Return original file as fallback
            outputFile.delete()
            return inputFile
        }
    }

    /** Per-part info extracted from Bambu model_settings.config. */
    private data class PartInfo(val faceCount: Int, val extruder: Int)

    /**
     * Parse Bambu model_settings.config XML for per-object, per-part extruder assignments.
     *
     * Bambu stores assignments at two levels:
     *  - <object id="N"><metadata key="extruder" value="M"/> — whole-object default
     *  - <object id="N"><part ...><metadata key="extruder" value="M"/> — per-volume
     *
     * Parts are collected in order with their face counts so we can compute cumulative face
     * ranges for PrusaSlicer's Slic3r_PE_model.config <volume firstid/lastid> entries.
     */
    private fun parseModelSettingsExtruders(
        content: ByteArray,
        objectParts: MutableMap<String, MutableList<PartInfo>>
    ) {
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(content.inputStream(), "UTF-8")

            var currentObjectId: String? = null
            var objectDefaultExtruder = 1
            var inPart = false
            var partExtruder = 1
            var partFaceCount = 0

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "object" -> {
                            currentObjectId = parser.getAttributeValue(null, "id")
                            objectDefaultExtruder = 1
                            currentObjectId?.let { objectParts.getOrPut(it) { mutableListOf() } }
                        }
                        "part" -> {
                            inPart = true
                            partExtruder = objectDefaultExtruder
                            partFaceCount = 0
                        }
                        "metadata" -> {
                            val key = parser.getAttributeValue(null, "key") ?: ""
                            val value = parser.getAttributeValue(null, "value") ?: ""
                            if (key == "extruder" && value.isNotBlank()) {
                                val ext = value.toIntOrNull() ?: 1
                                if (inPart) {
                                    partExtruder = ext
                                } else if (currentObjectId != null) {
                                    objectDefaultExtruder = ext
                                }
                            }
                        }
                        "mesh_stat" -> {
                            if (inPart) {
                                partFaceCount = parser.getAttributeValue(null, "face_count")?.toIntOrNull() ?: 0
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "part" -> {
                            if (inPart && currentObjectId != null) {
                                objectParts[currentObjectId]?.add(PartInfo(partFaceCount, partExtruder))
                            }
                            inPart = false
                        }
                        "object" -> {
                            // If no parts were found, represent the whole object as one entry
                            val parts = objectParts[currentObjectId]
                            if (parts != null && parts.isEmpty()) {
                                parts.add(PartInfo(0, objectDefaultExtruder))
                            }
                            currentObjectId = null
                        }
                    }
                }
                parser.next()
            }

            Log.d(TAG, "Parsed model_settings: ${objectParts.map { (id, parts) ->
                "$id=[${parts.joinToString { "${it.faceCount}f→T${it.extruder}" }}]"
            }}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse model_settings extruders: ${e.message}")
        }
    }

    /**
     * Build PrusaSlicer Slic3r_PE_model.config XML.
     * After restructuring, each entry in objectParts is a single-extruder object,
     * so we only need simple object-level extruder assignments.
     */
    private fun buildSlic3rModelConfig(objectParts: Map<String, List<PartInfo>>): String {
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("<config>")

        for ((objectId, parts) in objectParts.entries.sortedBy { it.key.toIntOrNull() ?: 0 }) {
            if (parts.isEmpty()) continue
            val extruder = parts.maxOf { it.extruder }
            val faceCount = parts.sumOf { it.faceCount }
            val lastId = maxOf(0, faceCount - 1)
            sb.appendLine("""  <object id="$objectId">""")
            sb.appendLine("""    <metadata type="object" key="extruder" value="$extruder"/>""")
            // PrusaSlicer uses volume firstid/lastid to map triangle ranges
            sb.appendLine("""    <volume firstid="0" lastid="$lastId">""")
            sb.appendLine("""      <metadata type="volume" key="extruder" value="$extruder"/>""")
            sb.appendLine("""    </volume>""")
            sb.appendLine("""  </object>""")
        }

        sb.appendLine("</config>")
        return sb.toString()
    }

    // ---- Multi-color restructuring ----

    /** A single <component> reference within a 3MF object. */
    private data class ComponentRef(
        val path: String,
        val objectId: String,
        val transform: FloatArray
    )

    /**
     * Restructure a Bambu 3D/3dmodel.model that has compound objects (one object, multiple
     * component parts with different extruders) into separate top-level build items.
     *
     * For example, a Calicube stored as:
     *   Object 2 → {Component→mesh, extruder=1}, {Component→mesh, extruder=2}
     *   Build: item objectid=2, transform=T
     *
     * becomes:
     *   Object 100 → single component (mesh at combined transform T*T1), extruder=1
     *   Object 101 → single component (mesh at combined transform T*T2), extruder=2
     *   Build: item objectid=100 | item objectid=101
     *
     * Returns (new model bytes, objectId→extruder map for Slic3r_PE_model.config).
     */
    private fun restructureForMultiColor(
        modelContent: ByteArray,
        objectParts: Map<String, List<PartInfo>>,
        componentFiles: Map<String, ByteArray> = emptyMap()
    ): Pair<ByteArray, Map<String, Int>> {
        // Identify compound objects that need splitting
        val splitTargets = objectParts.filter { (_, parts) ->
            parts.map { it.extruder }.distinct().size > 1
        }
        if (splitTargets.isEmpty()) {
            return Pair(convertMmuSegmentation(modelContent), emptyMap())
        }

        val modelStr = String(modelContent)
        val newObjectExtruders = mutableMapOf<String, Int>()

        // Parse component refs and build transforms from the model
        val objectComponents = mutableMapOf<String, List<ComponentRef>>()
        val buildTransforms = mutableMapOf<String, FloatArray>()
        parseMainModelStructure(modelContent, objectComponents, buildTransforms)

        var result = modelStr
        // Use small sequential IDs (starting at 1) — PrusaSlicer's Slic3r_PE_model.config
        // maps by these IDs and breaks with large IDs that don't match internal indices
        var nextId = 1

        for ((objectId, parts) in splitTargets) {
            val components = objectComponents[objectId]
            val buildTransform = buildTransforms[objectId]
            if (components == null || buildTransform == null) {
                Log.w(TAG, "Cannot restructure object $objectId: missing component/build data")
                continue
            }
            if (components.size != parts.size) {
                Log.w(TAG, "Component/part count mismatch for object $objectId (${components.size} vs ${parts.size}), skipping")
                continue
            }

            val newObjectDefs = StringBuilder()
            val newBuildItems = StringBuilder()

            for ((idx, component) in components.withIndex()) {
                val newId = (nextId++).toString()
                val extruder = parts[idx].extruder
                newObjectExtruders[newId] = extruder

                val combined = combineTransforms(buildTransform, component.transform)
                val tStr = combined.joinToString(" ") { "%.9f".format(it) }

                // Try to inline the mesh so PrusaSlicer tracks each object independently.
                // Component references to the same objectid get deduplicated by PrusaSlicer's
                // 3MF reader, breaking per-object extruder assignment via Slic3r_PE_model.config.
                val meshXml = componentFiles[component.path.trimStart('/')]
                    ?.let { extractMeshXml(it, component.objectId) }

                if (meshXml != null) {
                    newObjectDefs.append("""  <object id="$newId" type="model">
    $meshXml
  </object>
""")
                } else {
                    // Fallback: component reference (may not work for extruder assignment)
                    Log.w(TAG, "Could not inline mesh for component ${component.path}/${component.objectId}, using component ref")
                    newObjectDefs.append("""  <object id="$newId" type="model">
    <components>
      <component p:path="${component.path}" objectid="${component.objectId}"/>
    </components>
  </object>
""")
                }
                newBuildItems.append("""    <item objectid="$newId" transform="$tStr"/>
""")
            }

            // Remove original compound object and its build item; insert new ones
            result = removeObjectBlock(result, objectId)
            result = removeBuildItem(result, objectId)
            result = result.replace("</resources>", "${newObjectDefs}</resources>")
            result = result.replace("</build>", "${newBuildItems}</build>")

            Log.i(TAG, "Restructured object $objectId into ${components.size} separate build items: ${newObjectExtruders.entries.toList().takeLast(components.size)}")
        }

        return Pair(convertMmuSegmentation(result.toByteArray()), newObjectExtruders)
    }

    /**
     * Extract the <mesh>...</mesh> XML block for a given objectId from a component model file.
     * Used to inline geometry directly into 3dmodel.model so PrusaSlicer treats each inlined
     * object as independent (component-reference deduplication prevents per-object extruder assignment).
     */
    private fun extractMeshXml(componentContent: ByteArray, objectId: String): String? {
        val text = String(componentContent)
        val objStart = text.indexOf("""<object id="$objectId"""")
        if (objStart < 0) return null
        val meshStart = text.indexOf("<mesh", objStart)
        if (meshStart < 0) return null
        val meshEnd = text.indexOf("</mesh>", meshStart)
        if (meshEnd < 0) return null
        return text.substring(meshStart, meshEnd + "</mesh>".length).trim()
    }

    /** Parse component refs and build item transforms from 3D/3dmodel.model. */
    private fun parseMainModelStructure(
        content: ByteArray,
        objectComponents: MutableMap<String, List<ComponentRef>>,
        buildTransforms: MutableMap<String, FloatArray>
    ) {
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(content.inputStream(), "UTF-8")

            var currentObjectId: String? = null
            var currentComponents: MutableList<ComponentRef>? = null
            var inComponents = false
            var inBuild = false

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "object" -> {
                            currentObjectId = parser.getAttributeValue(null, "id")
                            currentComponents = mutableListOf()
                        }
                        "components" -> inComponents = true
                        "component" -> if (inComponents && currentObjectId != null) {
                            // Handle both "path" and "p:path" attribute names
                            val path = parser.getAttributeValue(null, "p:path")
                                ?: parser.getAttributeValue(null, "path") ?: ""
                            val refId = parser.getAttributeValue(null, "objectid") ?: ""
                            val tStr = parser.getAttributeValue(null, "transform") ?: ""
                            currentComponents?.add(ComponentRef(path, refId, parseTransformStr(tStr)))
                        }
                        "build" -> inBuild = true
                        "item" -> if (inBuild) {
                            val oid = parser.getAttributeValue(null, "objectid") ?: ""
                            val tStr = parser.getAttributeValue(null, "transform") ?: ""
                            buildTransforms[oid] = parseTransformStr(tStr)
                        }
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "components" -> inComponents = false
                        "object" -> {
                            if (currentObjectId != null && !currentComponents.isNullOrEmpty()) {
                                objectComponents[currentObjectId!!] = currentComponents!!
                            }
                            currentObjectId = null
                            currentComponents = null
                        }
                        "build" -> inBuild = false
                    }
                }
                parser.next()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse main model structure: ${e.message}")
        }
    }

    /** Find the highest object id integer in a model XML string. */
    private fun findMaxObjectId(modelStr: String): Int {
        val regex = Regex("""<(?:object|item)[^>]+(?:id|objectid)="(\d+)"""")
        return regex.findAll(modelStr).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 10
    }

    /** Remove the <object id="N">...</object> block from the model string. */
    private fun removeObjectBlock(modelStr: String, objectId: String): String {
        val start = modelStr.indexOf("""<object id="$objectId"""")
        if (start < 0) return modelStr
        var depth = 0
        var i = start
        while (i < modelStr.length) {
            if (modelStr.startsWith("<object", i) && i + 7 < modelStr.length &&
                (modelStr[i + 7] == ' ' || modelStr[i + 7] == '>')) depth++
            if (modelStr.startsWith("</object>", i)) {
                depth--
                if (depth == 0) {
                    return modelStr.substring(0, start) + modelStr.substring(i + "</object>".length)
                }
            }
            i++
        }
        return modelStr
    }

    /** Remove the <item objectid="N" .../> build item from the model string. */
    private fun removeBuildItem(modelStr: String, objectId: String): String {
        return modelStr.replace(
            Regex("""[ \t]*<item[^>]*objectid="$objectId"[^>]*/>\r?\n?"""), ""
        )
    }

    /**
     * Combine two 3MF affine transforms (each 12 floats: 3x3 rotation + translation).
     * Result = assembly * component (i.e. apply component in assembly space).
     */
    private fun combineTransforms(assembly: FloatArray, component: FloatArray): FloatArray {
        // Identity fallback
        val a = if (assembly.size == 12) assembly else floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f, 0f,0f,0f)
        val c = if (component.size == 12) component else floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f, 0f,0f,0f)

        // R = Ra * Rc  (both stored row-major: a[0..2]=row0, a[3..5]=row1, a[6..8]=row2)
        val r = FloatArray(9)
        for (row in 0..2) {
            for (col in 0..2) {
                r[row * 3 + col] = a[row*3+0]*c[0*3+col] + a[row*3+1]*c[1*3+col] + a[row*3+2]*c[2*3+col]
            }
        }
        // T = Ra * Tc + Ta
        val tx = a[0]*c[9] + a[1]*c[10] + a[2]*c[11] + a[9]
        val ty = a[3]*c[9] + a[4]*c[10] + a[5]*c[11] + a[10]
        val tz = a[6]*c[9] + a[7]*c[10] + a[8]*c[11] + a[11]
        return floatArrayOf(r[0],r[1],r[2], r[3],r[4],r[5], r[6],r[7],r[8], tx,ty,tz)
    }

    /** Parse a 3MF transform string into a 12-float array, or identity if blank/invalid. */
    private fun parseTransformStr(str: String): FloatArray {
        if (str.isBlank()) return floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f, 0f,0f,0f)
        val v = str.trim().split("\\s+".toRegex()).mapNotNull { it.toFloatOrNull() }
        return if (v.size == 12) v.toFloatArray()
        else floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f, 0f,0f,0f)
    }

    /**
     * Sanitize project_settings.config.
     * Bambu newer files use JSON; older use INI. JSON files are passed through (PrusaSlicer ignores
     * them anyway); INI files get the full sanitization pass.
     */
    private fun sanitizeProjectSettings(content: String): String {
        // Newer BambuStudio stores project_settings.config as JSON — PrusaSlicer doesn't read it,
        // so pass it through unchanged rather than mangling it with the INI parser.
        if (content.trimStart().startsWith("{")) {
            Log.d(TAG, "project_settings.config is JSON, passing through unchanged")
            return content
        }
        val config = parseIniConfig(content)

        // 1. Clamp parameters
        for ((key, minVal) in CLAMP_RULES) {
            clampParameter(config, key, minVal)
        }

        // 2. Replace 'nil' values
        sanitizeNilValues(config)

        // 3. Normalize per-filament arrays
        val extruderCount = detectExtruderCount(config)
        if (extruderCount > 1) {
            normalizePerFilamentArrays(config, extruderCount)
        }

        // 4. Strip Bambu-specific G-code
        stripBambuGcode(config)

        // 5. Clamp wipe tower position
        sanitizeWipeTowerPosition(config)

        return serializeIniConfig(config)
    }

    /**
     * Sanitize model_settings.config XML:
     * - Clear stale plater_name values
     * - Clamp packed transforms (>370mm) to bed center
     */
    private fun sanitizeModelSettings(content: ByteArray): ByteArray {
        var text = String(content)

        // Clear plater_name to prevent OrcaSlicer/PrusaSlicer segfaults
        text = text.replace(
            Regex("""(key="plater_name"\s+value=")([^"]*)""""),
            "$1\""
        )

        // Clamp assemble_item transforms that are packed (Bambu multi-plate)
        val bedCenter = 135f // 270mm / 2
        val packedThreshold = bedCenter * 2 + 100 // 370mm
        text = text.replace(
            Regex("""(<assemble_item[^>]*\btransform=")([\d\s.eE+-]+)(")""")
        ) { match ->
            val prefix = match.groupValues[1]
            val transformStr = match.groupValues[2]
            val suffix = match.groupValues[3]
            val vals = transformStr.trim().split("\\s+".toRegex())
                .mapNotNull { it.toFloatOrNull() }
            if (vals.size >= 12) {
                val tx = vals[9]
                val ty = vals[10]
                if (kotlin.math.abs(tx) > packedThreshold || kotlin.math.abs(ty) > packedThreshold) {
                    val newVals = vals.toMutableList()
                    newVals[9] = bedCenter
                    newVals[10] = bedCenter
                    newVals[11] = 0f
                    prefix + newVals.joinToString(" ") { "%.6f".format(it) } + suffix
                } else {
                    match.value
                }
            } else {
                match.value
            }
        }

        return text.toByteArray()
    }

    /**
     * Convert PrusaSlicer mmu_segmentation attribute to paint_color
     * for OrcaSlicer/PrusaSlicer multi-color compatibility.
     */
    private fun convertMmuSegmentation(content: ByteArray): ByteArray {
        var text = String(content)
        if (!text.contains("mmu_segmentation")) return content

        text = text.replace("slic3rpe:mmu_segmentation=", "paint_color=")
        text = text.replace(Regex("""\s+xmlns:slic3rpe="[^"]*""""), "")

        return text.toByteArray()
    }

    /**
     * Clean model XML for PrusaSlicer compatibility.
     *
     * Strips Bambu-specific extensions that PrusaSlicer cannot handle:
     * - Production extension (p:UUID, p:path, requiredextensions="p")
     * - BambuStudio namespace and metadata elements
     * - Other non-core metadata that can cause load failures
     *
     * After this, the file is a plain 3MF core document.
     */
    private fun cleanModelXml(content: ByteArray): ByteArray {
        var text = String(content)
        text = text.replace(Regex("""\s+requiredextensions="[^"]*""""), "")
        text = text.replace(Regex("""\s+xmlns:p="[^"]*""""), "")
        text = text.replace(Regex("""\s+p:UUID="[^"]*""""), "")
        text = text.replace(Regex("""\s+p:path="[^"]*""""), "")
        text = text.replace(Regex("""\s+xmlns:BambuStudio="[^"]*""""), "")
        text = text.replace(Regex("""[ \t]*<metadata name="[^"]*"(?:>[^<]*</metadata>|[^/]*/>) *\r?\n?"""), "")
        return text.toByteArray()
    }

    /** Like cleanModelXml but preserves p:path and xmlns:p (needed for component refs). */
    private fun cleanModelXmlPreserveComponentRefs(content: ByteArray): ByteArray {
        var text = String(content)
        text = text.replace(Regex("""\s+requiredextensions="[^"]*""""), "")
        text = text.replace(Regex("""\s+p:UUID="[^"]*""""), "")
        text = text.replace(Regex("""\s+xmlns:BambuStudio="[^"]*""""), "")
        text = text.replace(Regex("""[ \t]*<metadata name="[^"]*"(?:>[^<]*</metadata>|[^/]*/>) *\r?\n?"""), "")
        return text.toByteArray()
    }

    // ---- INI Config Helpers ----

    /**
     * Parse a Bambu/OrcaSlicer project_settings.config into a map.
     * Values that look like semicolon-separated lists become List<String>.
     * Everything else is a String.
     */
    private fun parseIniConfig(content: String): MutableMap<String, Any> {
        val config = mutableMapOf<String, Any>()
        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) continue
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx < 0) continue
            val key = trimmed.substring(0, eqIdx).trim()
            val value = trimmed.substring(eqIdx + 1).trim()

            // Detect list values (semicolon-separated in Bambu config format)
            if (value.contains(";")) {
                config[key] = value.split(";").map { it.trim() }.toMutableList()
            } else {
                config[key] = value
            }
        }
        return config
    }

    private fun serializeIniConfig(config: Map<String, Any>): String {
        val sb = StringBuilder()
        for ((key, value) in config) {
            when (value) {
                is List<*> -> sb.appendLine("$key = ${value.joinToString(";")}")
                else -> sb.appendLine("$key = $value")
            }
        }
        return sb.toString()
    }

    private fun clampParameter(config: MutableMap<String, Any>, key: String, minimum: Float) {
        val raw = config[key] ?: return
        val str = when (raw) {
            is List<*> -> (raw.firstOrNull() ?: return).toString()
            else -> raw.toString()
        }
        val numeric = str.trim().toFloatOrNull() ?: return
        if (numeric < minimum) {
            config[key] = if (minimum == minimum.toInt().toFloat()) {
                minimum.toInt().toString()
            } else {
                minimum.toString()
            }
        }
    }

    /**
     * Replace 'nil' strings in list values with the first non-nil element.
     * If all elements are 'nil', remove the key entirely.
     */
    private fun sanitizeNilValues(config: MutableMap<String, Any>) {
        val toRemove = mutableListOf<String>()
        for ((key, value) in config) {
            if (value !is MutableList<*>) continue
            @Suppress("UNCHECKED_CAST")
            val list = value as MutableList<String>
            if (!list.any { it == "nil" }) continue

            val default = list.firstOrNull { it != "nil" }
            if (default == null) {
                toRemove.add(key)
                continue
            }

            for (i in list.indices) {
                if (list[i] == "nil") list[i] = default
            }
        }
        toRemove.forEach { config.remove(it) }
    }

    /**
     * Normalize per-filament arrays to match target extruder count.
     * Pads short arrays by repeating last element. Truncates long arrays.
     */
    private fun normalizePerFilamentArrays(config: MutableMap<String, Any>, targetCount: Int) {
        for ((key, value) in config.entries.toList()) {
            if (key in NON_FILAMENT_KEYS || key in SPECIAL_LIST_KEYS) continue
            if (value !is MutableList<*>) continue
            @Suppress("UNCHECKED_CAST")
            val list = value as MutableList<Any>
            if (list.isEmpty()) continue

            when {
                list.size < targetCount -> {
                    val last = list.last()
                    while (list.size < targetCount) list.add(last)
                }
                list.size > targetCount -> {
                    while (list.size > targetCount) list.removeAt(list.lastIndex)
                }
            }
        }

        // Special: flush_volumes_matrix must be NxN
        normalizeFlushMatrix(config, targetCount)
    }

    private fun normalizeFlushMatrix(config: MutableMap<String, Any>, targetCount: Int) {
        val fvm = config["flush_volumes_matrix"]
        if (fvm is MutableList<*>) {
            @Suppress("UNCHECKED_CAST")
            val list = fvm as MutableList<Any>
            val needed = targetCount * targetCount
            val last = list.lastOrNull() ?: "0"
            while (list.size < needed) list.add(last)
            while (list.size > needed) list.removeAt(list.lastIndex)
        }
    }

    private fun detectExtruderCount(config: Map<String, Any>): Int {
        // Check filament_colour array size
        val colors = config["filament_colour"]
        if (colors is List<*> && colors.size > 1) return colors.size

        // Check extruder_colour
        val extColors = config["extruder_colour"]
        if (extColors is List<*> && extColors.size > 1) return extColors.size

        return 1
    }

    private fun stripBambuGcode(config: MutableMap<String, Any>) {
        // Remove Bambu-specific G-code keys
        config.remove("time_lapse_gcode")
        config.remove("machine_pause_gcode")

        // Strip filament_start_gcode if it contains Bambu macros
        val fsg = config["filament_start_gcode"]
        if (fsg is List<*>) {
            if (fsg.any { it.toString().contains("M142") || it.toString().contains("air_filtration") }) {
                config.remove("filament_start_gcode")
            }
        }
    }

    /**
     * Clamp wipe tower position to stay within 270mm bed.
     */
    private fun sanitizeWipeTowerPosition(
        config: MutableMap<String, Any>,
        bedSize: Float = 270f,
        extraMargin: Float = 6f
    ) {
        val towerWidth = getNumeric(config, "prime_tower_width", 35f)
        val towerBrim = maxOf(0f, getNumeric(config, "prime_tower_brim_width", 3f))
        val halfSpan = maxOf(12f, towerWidth / 2f + towerBrim + extraMargin)
        val minPos = halfSpan
        val maxPos = maxOf(minPos, bedSize - halfSpan)

        clampScalarPosition(config, "wipe_tower_x", minPos, maxPos)
        clampScalarPosition(config, "wipe_tower_y", minPos, maxPos)
    }

    private fun clampScalarPosition(config: MutableMap<String, Any>, key: String, min: Float, max: Float) {
        val raw = config[key] ?: return
        // Only clamp scalar values, not per-plate arrays
        if (raw is List<*>) return
        val value = raw.toString().toFloatOrNull() ?: return
        if (value < min || value > max) {
            config[key] = "%.3f".format(value.coerceIn(min, max))
        }
    }

    private fun getNumeric(config: Map<String, Any>, key: String, default: Float): Float {
        val raw = config[key] ?: return default
        return when (raw) {
            is List<*> -> raw.firstOrNull()?.toString()?.toFloatOrNull() ?: default
            else -> raw.toString().toFloatOrNull() ?: default
        }
    }

    /**
     * Extract a single plate from a multi-plate 3MF by marking other plates as non-printable.
     * This preserves all Bambu metadata links.
     */
    /**
     * Stream-copy a component .model ZIP entry as STORED, cleaning Bambu extensions.
     * Strips p:UUID from ALL chunks (appears on every object/component element)
     * and requiredextensions/BambuStudio from the header chunk.
     * Uses temp file + streaming to avoid OOM on 100MB+ entries.
     */
    private fun copyZipEntry(srcZip: ZipFile, srcEntry: ZipEntry, destZip: ZipOutputStream) {
        val tmpFile = File.createTempFile("3mf_component_", ".model")
        val pUuidRegex = Regex("""\s+p:UUID="[^"]*"""")
        try {
            // Pass 1: stream-clean and write to temp file
            tmpFile.outputStream().use { out ->
                srcZip.getInputStream(srcEntry).use { input ->
                    var headerCleaned = false
                    val buf = ByteArray(65536)
                    var n: Int
                    while (input.read(buf).also { n = it } >= 0) {
                        var chunk = String(buf, 0, n)
                        // Strip p:UUID from every chunk (appears on all object/component elements)
                        chunk = chunk.replace(pUuidRegex, "")
                        if (!headerCleaned) {
                            // Additional header-only cleanups
                            chunk = chunk.replace(Regex("""\s+requiredextensions="[^"]*""""), "")
                            chunk = chunk.replace(Regex("""\s+xmlns:BambuStudio="[^"]*""""), "")
                            chunk = chunk.replace(Regex("""[ \t]*<metadata name="[^"]*"(?:>[^<]*</metadata>|[^/]*/>) *\r?\n?"""), "")
                            headerCleaned = true
                        }
                        out.write(chunk.toByteArray())
                    }
                }
            }

            // Pass 2: compute CRC + size from temp file
            val crc = CRC32()
            var totalSize = 0L
            tmpFile.inputStream().use { input ->
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } >= 0) {
                    crc.update(buf, 0, n)
                    totalSize += n
                }
            }

            // Pass 3: write STORED entry from temp file
            val entry = ZipEntry(srcEntry.name)
            entry.method = ZipEntry.STORED
            entry.size = totalSize
            entry.compressedSize = totalSize
            entry.crc = crc.value
            destZip.putNextEntry(entry)
            tmpFile.inputStream().use { input ->
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } >= 0) {
                    destZip.write(buf, 0, n)
                }
            }
            destZip.closeEntry()
        } finally {
            tmpFile.delete()
        }
    }

    /** Write a ZIP entry using STORED (no compression) method — required by 3MF spec. */
    private fun writeStored(zip: ZipOutputStream, name: String, data: ByteArray) {
        val entry = ZipEntry(name)
        entry.method = ZipEntry.STORED
        entry.size = data.size.toLong()
        entry.compressedSize = data.size.toLong()
        val crc = CRC32()
        crc.update(data)
        entry.crc = crc.value
        zip.putNextEntry(entry)
        zip.write(data)
        zip.closeEntry()
    }

    fun extractPlate(inputFile: File, targetPlateId: Int, outputDir: File): File {
        val outputFile = File(outputDir, "plate${targetPlateId}_${inputFile.name}")

        ZipFile(inputFile).use { srcZip ->
            ZipOutputStream(FileOutputStream(outputFile)).use { destZip ->
                for (entry in srcZip.entries()) {
                    val content = srcZip.getInputStream(entry).readBytes()

                    if (entry.name.endsWith("3dmodel.model")) {
                        // Modify build items: set printable flag
                        var text = String(content)
                        var itemIndex = 0
                        text = text.replace(Regex("""<item\b([^>]*)""")) { match ->
                            itemIndex++
                            val attrs = match.groupValues[1]
                            val newPrintable = if (itemIndex == targetPlateId) "1" else "0"
                            if (attrs.contains("printable=")) {
                                "<item" + attrs.replace(
                                    Regex("""printable="[^"]*""""),
                                    """printable="$newPrintable""""
                                )
                            } else {
                                """<item printable="$newPrintable"${attrs}"""
                            }
                        }
                        writeStored(destZip, entry.name, text.toByteArray())
                    } else {
                        writeStored(destZip, entry.name, content)
                    }
                }
            }
        }
        return outputFile
    }
}
