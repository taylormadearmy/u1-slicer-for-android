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

                            // Drop unknown Metadata files (Bambu-specific configs like
                            // brim_ear_points.txt, filament_settings can confuse PrusaSlicer)
                            name.startsWith("Metadata/") -> {
                                Log.d(TAG, "Dropping unknown metadata: $name")
                            }

                            // Pass through everything else
                            else -> {
                                writeStored(destZip, name, content)
                            }
                        }
                    }

                    // Write (possibly restructured) main model file
                    if (mainModelContent != null) {
                        // Restructure compound multi-color objects into separate build items.
                        // Only consider extruders 1..4 — BambuStudio uses indices 5+ for paint
                        // colour markers that have no corresponding physical extruder slot on the
                        // U1.  Including out-of-range indices (e.g. extruder=5 on single-colour
                        // files) falsely triggers restructuring and causes OOM on large meshes.
                        val hasMultiColorComponents = bambuObjectParts.values.any { parts ->
                            parts.filter { it.extruder in 1..4 }.map { it.extruder }.distinct().size > 1
                        }
                        // Guard against OOM: check total component file size before loading.
                        // Large multi-plate files (e.g. 7-plate coasters) have many large component
                        // files.  Loading them all to inline meshes exhausts the Android heap.
                        // When they're too large, skip restructuring and preserve component refs
                        // instead — OrcaSlicer (BBS fork) handles p:path component refs natively.
                        val totalComponentSize = if (hasMultiColorComponents) {
                            componentFileNames.sumOf { name -> srcZip.getEntry(name)?.size ?: 0L }
                        } else 0L
                        val safeToInline = totalComponentSize <= 15_000_000L  // 15 MB
                        if (hasMultiColorComponents && !safeToInline) {
                            Log.w(TAG, "Component files too large to inline " +
                                "(${totalComponentSize / 1_000_000}MB > 15MB), preserving component refs")
                        }
                        // Load component files only when safe to do so
                        val componentFiles = if (hasMultiColorComponents && safeToInline) {
                            componentFileNames.associateWith { name ->
                                srcZip.getEntry(name)?.let { srcZip.getInputStream(it).readBytes() } ?: ByteArray(0)
                            }
                        } else {
                            emptyMap()
                        }

                        val (finalModelBytes, newObjectExtruders) = if (hasMultiColorComponents && safeToInline) {
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
                        // Strip printable="0" items from <build>.  Bambu 3MF files can include
                        // build items with printable="0" for objects that are in the scene but not
                        // assigned to the current plate.  Loading them causes multiple disjoint
                        // objects to be sliced together, which produces "Coordinate outside allowed
                        // range" errors in Clipper when the meshes are positioned far apart.
                        val cleanedModelFinal = stripNonPrintableBuildItems(cleanedModel)
                        writeStored(destZip, "3D/3dmodel.model", cleanedModelFinal)

                        val wasRestructured = newObjectExtruders.isNotEmpty()

                        // Write component files and rels:
                        // - If restructured (meshes inlined): skip component files + rels entirely
                        // - If not restructured: write them (needed for component refs)
                        if (!wasRestructured) {
                            // Copy component files — use streaming for large files to avoid OOM
                            for (path in componentFileNames) {
                                val srcEntry = srcZip.getEntry(path) ?: continue
                                if (srcEntry.size > 10_000_000) {
                                    // Large file: stream-copy with line-by-line cleaning
                                    copyZipEntry(srcZip, srcEntry, destZip)
                                } else {
                                    // Small file: load into memory and clean fully
                                    val data = srcZip.getInputStream(srcEntry).readBytes()
                                    writeStored(destZip, path, cleanModelXmlPreserveComponentRefs(convertMmuSegmentation(data)))
                                }
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
     *
     * For single-color objects (one part or all same extruder), emit one volume entry.
     * For multi-color objects that were NOT restructured (component-ref preserved path),
     * emit per-part volume entries using face-count ranges from model_settings.config.
     * If face counts are missing (zero), fall back to the max extruder for the whole object
     * so OrcaSlicer still gets a valid (if simplified) assignment.
     */
    private fun buildSlic3rModelConfig(objectParts: Map<String, List<PartInfo>>): String {
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("<config>")

        for ((objectId, parts) in objectParts.entries.sortedBy { it.key.toIntOrNull() ?: 0 }) {
            if (parts.isEmpty()) continue
            val overallExtruder = parts.maxOf { it.extruder }
            sb.appendLine("""  <object id="$objectId">""")
            sb.appendLine("""    <metadata type="object" key="extruder" value="$overallExtruder"/>""")

            val isMultiColor = parts.map { it.extruder }.distinct().size > 1
            val hasFaceCounts = parts.all { it.faceCount > 0 }

            if (isMultiColor && hasFaceCounts) {
                // Per-part volume entries — preserves extruder assignment for non-restructured objects
                var firstId = 0
                for (part in parts) {
                    val lastId = firstId + part.faceCount - 1
                    sb.appendLine("""    <volume firstid="$firstId" lastid="$lastId">""")
                    sb.appendLine("""      <metadata type="volume" key="extruder" value="${part.extruder}"/>""")
                    sb.appendLine("""    </volume>""")
                    firstId += part.faceCount
                }
            } else {
                // Single-color or missing face counts: one volume entry for the whole object
                val faceCount = parts.sumOf { it.faceCount }
                val lastId = maxOf(0, faceCount - 1)
                sb.appendLine("""    <volume firstid="0" lastid="$lastId">""")
                sb.appendLine("""      <metadata type="volume" key="extruder" value="$overallExtruder"/>""")
                sb.appendLine("""    </volume>""")
            }

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
    internal fun sanitizeModelSettings(content: ByteArray): ByteArray {
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

    // ---- Model XML Cleanup (used by process()) ----

    private fun cleanModelXml(content: ByteArray): ByteArray {
        var text = String(content)
        text = text.replace(Regex("""\s+requiredextensions="[^"]*""""), "")
        text = text.replace(Regex("""\s+xmlns:p="[^"]*""""), "")
        text = text.replace(Regex("""\s+p:UUID="[^"]*""""), "")
        text = text.replace(Regex("""\s+p:path="[^"]*""""), "")
        text = text.replace(Regex("""\s+xmlns:BambuStudio="[^"]*""""), "")
        text = text.replace(Regex("""[ \t]*<metadata name="[^"]*"(?:>[^<]*</metadata>|[^/]*/>) *\r?\n?"""), "")
        text = text.replace("""type="other"""", """type="model"""")
        return text.toByteArray()
    }

    private fun cleanModelXmlPreserveComponentRefs(content: ByteArray): ByteArray {
        var text = String(content)
        text = text.replace(Regex("""\s+requiredextensions="[^"]*""""), "")
        text = text.replace(Regex("""\s+p:UUID="[^"]*""""), "")
        text = text.replace(Regex("""\s+xmlns:BambuStudio="[^"]*""""), "")
        text = text.replace(Regex("""[ \t]*<metadata name="[^"]*"(?:>[^<]*</metadata>|[^/]*/>) *\r?\n?"""), "")
        text = text.replace("""type="other"""", """type="model"""")
        return text.toByteArray()
    }

    // ---- INI Config Helpers (public accessors for ProfileEmbedder) ----

    fun parseIniConfigPublic(content: String) = parseIniConfig(content)
    fun sanitizeModelSettingsPublic(content: ByteArray) = sanitizeModelSettings(content)

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
     * Uses line-by-line processing to avoid chunk-boundary attribute splitting.
     * Strips p:UUID, requiredextensions, BambuStudio namespace, and metadata from every line.
     * Uses temp file + streaming to avoid OOM on 100MB+ entries.
     */
    private fun copyZipEntry(srcZip: ZipFile, srcEntry: ZipEntry, destZip: ZipOutputStream) {
        val tmpFile = File.createTempFile("3mf_component_", ".model")
        val pUuidRegex = Regex("""\s+p:UUID="[^"]*"""")
        val reqExtRegex = Regex("""\s+requiredextensions="[^"]*"""")
        val bambuNsRegex = Regex("""\s+xmlns:BambuStudio="[^"]*"""")
        val slic3rpeNsRegex = Regex("""\s+xmlns:slic3rpe="[^"]*"""")
        val metadataRegex = Regex("""[ \t]*<metadata name="[^"]*"(?:>[^<]*</metadata>|[^/]*/>) *\r?\n?""")
        try {
            // Line-by-line streaming clean to avoid OOM on 100MB+ entries
            tmpFile.bufferedWriter().use { out ->
                srcZip.getInputStream(srcEntry).bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        var cleaned = line.replace(pUuidRegex, "")
                        cleaned = cleaned.replace(reqExtRegex, "")
                        cleaned = cleaned.replace(bambuNsRegex, "")
                        cleaned = cleaned.replace(slic3rpeNsRegex, "")
                        cleaned = cleaned.replace(metadataRegex, "")
                        // Convert MMU segmentation attributes for PrusaSlicer
                        cleaned = cleaned.replace("slic3rpe:mmu_segmentation=", "paint_color=")
                        // PrusaSlicer only accepts type="model"; Bambu uses "other" for support geometry
                        cleaned = cleaned.replace("""type="other"""", """type="model"""")
                        if (cleaned.isNotBlank()) {
                            out.write(cleaned)
                            out.newLine()
                        }
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

    /**
     * @param hasPlateJsons  If provided, overrides auto-detection.  Pass the value from
     *   [ThreeMfInfo.hasPlateJsons] (parsed from the *original* file) when calling on a
     *   processed/sanitised file that has had its Metadata/plate_N.json entries stripped.
     *   When null (default), the flag is auto-detected by inspecting the inputFile ZIP.
     */
    fun extractPlate(inputFile: File, targetPlateId: Int, outputDir: File,
                     hasPlateJsons: Boolean? = null): File {
        val outputFile = File(outputDir, "plate${targetPlateId}_${inputFile.name}")

        ZipFile(inputFile).use { srcZip ->
            // Auto-detect from the file when the caller hasn't provided an explicit value.
            val actualHasPlateJsons = hasPlateJsons ?: srcZip.entries().asSequence()
                .any { it.name.matches(Regex("Metadata/plate_\\d+\\.json")) }

            ZipOutputStream(FileOutputStream(outputFile)).use { destZip ->
                for (entry in srcZip.entries()) {
                    val content = srcZip.getInputStream(entry).readBytes()

                    when {
                        entry.name.endsWith("3dmodel.model") -> {
                            val filtered = filterModelToPlate(String(content), targetPlateId,
                                actualHasPlateJsons)
                            writeStored(destZip, entry.name, filtered.toByteArray())
                        }
                        entry.name == "Metadata/model_settings.config" -> {
                            // Strip <assemble> section: OrcaSlicer's _handle_start_assemble_item
                            // looks up each assemble_item's object_id in m_objects (which only
                            // contains objects that appear in <build>).  A plate-extracted file
                            // has only one build item, so assemble_items for the other 6 objects
                            // cause "can not find object for assemble item" → load failure.
                            val stripped = stripAssembleSection(String(content))
                            writeStored(destZip, entry.name, stripped.toByteArray())
                        }
                        else -> writeStored(destZip, entry.name, content)
                    }
                }
            }
        }
        return outputFile
    }

    /**
     * Remove the `<assemble>...</assemble>` block from a model_settings.config string.
     *
     * OrcaSlicer's _handle_start_assemble_item fails with "can not find object" when an
     * assemble_item references an object_id that is not present in m_objects (i.e. not
     * instantiated via a <build> item).  Plate-extracted files have only ONE <build> item,
     * but model_settings.config retains assemble_items for all original plates → load fails.
     *
     * The <assemble> section is used only by BBS plate manager for object placement and is
     * not required for slicing.  Stripping it lets OrcaSlicer use the <build> transforms.
     */
    private fun stripAssembleSection(xml: String): String {
        return xml.replace(Regex("""[ \t]*<assemble>.*?</assemble>[ \t]*\r?\n?""",
            setOf(RegexOption.DOT_MATCHES_ALL)), "")
    }

    /**
     * Remove `printable="0"` build items from a 3dmodel.model ByteArray.
     * These items are in the scene for reference/display only and must not be sliced.
     * Only rewrites `<build>`; `<resources>` is left intact.
     */
    private fun stripNonPrintableBuildItems(modelBytes: ByteArray): ByteArray {
        val xml = String(modelBytes)
        val buildRegex = Regex("""(<build\b[^>]*>)(.*?)(</build>)""", setOf(RegexOption.DOT_MATCHES_ALL))
        val itemRegex  = Regex("""<item\b[^>]*(?:/>|>.*?</item>)""",  setOf(RegexOption.DOT_MATCHES_ALL))
        val result = buildRegex.replace(xml) { m ->
            val open  = m.groupValues[1]
            val body  = m.groupValues[2]
            val close = m.groupValues[3]
            val allItems = itemRegex.findAll(body).map { it.value }.toList()
            val printable = allItems.filter { !it.contains("""printable="0"""") }
            if (printable.size == allItems.size) return@replace m.value  // nothing to strip
            val stripped = allItems.size - printable.size
            Log.i(TAG, "stripNonPrintableBuildItems: removed $stripped non-printable item(s)")
            // Guard: if ALL items were non-printable, keep the build section unchanged.
            // An empty <build> causes OrcaSlicer to silently load nothing (no model info).
            if (printable.isEmpty()) {
                Log.w(TAG, "stripNonPrintableBuildItems: all items are non-printable — keeping build unchanged")
                return@replace m.value
            }
            val newBody = "\n" + printable.joinToString("\n") { "  $it" } + "\n  "
            "$open$newBody$close"
        }
        return result.toByteArray()
    }

    /**
     * Rewrite the <build> section of a 3dmodel.model XML to contain ONLY the items
     * for [targetPlateId].
     *
     * Bambu multi-plate files tag each <item> with `p:object_id="N"` (0-based plate index).
     * We keep only the items where p:object_id == targetPlateId-1.
     *
     * Crucially, we leave <resources>/<objects> COMPLETELY INTACT.  Removing objects
     * breaks OrcaSlicer because model_settings.config and component refs still reference
     * them — the parser rejects a model with dangling object IDs.  OrcaSlicer only
     * instantiates objects that appear in <build>, so the bounding box is correct with
     * only the target plate's items in <build>.
     *
     * Fallback (newer Bambu format without p:object_id): select the N-th build item
     * by XML order (targetPlateId is 1-based) and re-centre its XY to the bed centre.
     * Newer BambuStudio exports lay out all plates in a large virtual space so each
     * item's absolute XY is outside the 270 mm bed.  Re-centring to (135, 135) lets
     * setModelInstances compute the correct centered placement afterwards.
     *
     * @param hasPlateJsons  true = newer format (each plate is independently loadable,
     *   safe to filter to 1 item).  false = older format (Dragon Scale / Shashibo style)
     *   where build items share component file refs and OrcaSlicer needs all of them.
     */
    private fun filterModelToPlate(xml: String, targetPlateId: Int,
                                   hasPlateJsons: Boolean = true): String {
        val targetPlateIndex = targetPlateId - 1  // p:object_id is 0-based

        val buildRegex  = Regex("""(<build\b[^>]*>)(.*?)(</build>)""", setOf(RegexOption.DOT_MATCHES_ALL))
        val itemRegex   = Regex("""<item\b[^>]*(?:/>|>.*?</item>)""",  setOf(RegexOption.DOT_MATCHES_ALL))
        val plateIdRegex = Regex("""p:object_id="(\d+)"""")

        return buildRegex.replace(xml) { m ->
            val open  = m.groupValues[1]
            val body  = m.groupValues[2]
            val close = m.groupValues[3]

            val allItems = itemRegex.findAll(body).map { it.value }.toList()

            // p:object_id-based filtering (older Bambu format with explicit plate tags)
            val hasPlateIds = allItems.any { plateIdRegex.containsMatchIn(it) }
            if (hasPlateIds) {
                val targetItems = allItems.filter { item ->
                    val plateIdx = plateIdRegex.find(item)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    plateIdx == targetPlateIndex
                }
                if (targetItems.isEmpty()) {
                    Log.w(TAG, "filterModelToPlate: no items for plate $targetPlateId — keeping all")
                    return@replace m.value
                }
                Log.i(TAG, "filterModelToPlate: plate $targetPlateId — kept ${targetItems.size}/${allItems.size} items")
                val newBody = "\n" + targetItems.joinToString("\n") { "    $it" } + "\n  "
                return@replace "$open$newBody$close"
            }

            // No p:object_id markers — use position-based selection.
            //
            // Trigger filtering when EITHER:
            //  (a) hasPlateJsons  — newer Bambu format (plate_N.json present); each plate is
            //                       independently loadable.
            //  (b) hasVirtualPositions — older Bambu format (Dragon Scale, Shashibo): build
            //                       items placed at virtual TX/TY offsets outside the 270mm bed.
            //                       Each item represents one physical plate.  We select the
            //                       N-th item and re-centre it.  The <assemble> section in
            //                       model_settings.config is stripped separately (stripAssembleSection),
            //                       so OrcaSlicer won't fail on assemble_item refs for omitted items.
            //
            // Do NOT filter when there are no virtual positions and no plate JSONs — those are
            // genuine single-plate multi-object files where all items belong together.
            val transformRegex = Regex("""transform="([^"]+)"""")
            val hasVirtualPositions = allItems.any { item ->
                val parts = transformRegex.find(item)?.groupValues?.get(1)
                    ?.trim()?.split(Regex("\\s+")) ?: emptyList()
                val tx = parts.getOrNull(9)?.toFloatOrNull() ?: 0f
                val ty = parts.getOrNull(10)?.toFloatOrNull() ?: 0f
                // Use -10f not 0f for TY to avoid false positives from legitimate
                // models placed slightly off-origin due to floating-point precision.
                // Bambu virtual-plate offsets are hundreds of mm (e.g. TY=-224), so
                // -10f is a safe threshold that won't catch normal placements.
                tx > 270f || ty < -10f || ty > 270f
            }

            if (allItems.size <= 1 || (!hasPlateJsons && !hasVirtualPositions)) return@replace m.value

            val idx = targetPlateIndex.coerceIn(0, allItems.size - 1)
            val selected = recenterItemXY(allItems[idx])
            val mode = if (hasPlateJsons) "json" else "virtual"
            Log.i(TAG, "filterModelToPlate: plate $targetPlateId — position-based ($mode), selected item ${idx + 1}/${allItems.size}")
            val newBody = "\n    $selected\n  "
            "$open$newBody$close"
        }
    }

    /**
     * Resets the XY translation in a build item's `transform` attribute to bed centre (135, 135),
     * preserving the rotation matrix and Z translation.
     * 3MF row-major 3×4 format: `m00 … m22 tx ty tz` — indices 9, 10, 11.
     */
    private fun recenterItemXY(item: String): String {
        val transformRegex = Regex("""transform="([^"]+)"""")
        return transformRegex.replace(item) { match ->
            val parts = match.groupValues[1].trim().split(Regex("\\s+"))
            if (parts.size >= 12) {
                val newParts = parts.toMutableList()
                newParts[9]  = "135"
                newParts[10] = "135"
                // parts[11] = tz preserved (holds the Z offset placing the mesh on the bed)
                """transform="${newParts.joinToString(" ")}""""
            } else {
                match.value  // unknown format, leave unchanged
            }
        }
    }
}
