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
 * 4. Strip PrusaSlicer mmu_segmentation (different encoding; Bambu paint_color= preserved)
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
    fun process(inputFile: File, outputDir: File, isBambu: Boolean? = null): File {
        val bambu = isBambu ?: ThreeMfParser.parse(inputFile).isBambu
        if (!bambu) {
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
                    // Buffer model_settings.config — preserved for multi-plate files so
                    // restructurePlateFile() can restructure per-plate later
                    var modelSettingsContent: ByteArray? = null
                    // Set to true when multi-plate restructuring was deferred
                    var deferredRestructuring = false
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

                            // Read model_settings.config for extruder assignments.
                            // For restructured files: don't write (IDs change).
                            // For multi-plate files (restructuring skipped): buffer for later.
                            name == "Metadata/model_settings.config" -> {
                                parseModelSettingsExtruders(content, bambuObjectParts)
                                modelSettingsContent = content
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
                        // Guard against OOM and oversized XML: check total component file size
                        // before loading.  Restructuring inlines ALL objects' meshes into the main
                        // model XML.  For multi-plate files (e.g. 7-plate Dragon Scale, Shashibo),
                        // this creates a 40+ MB XML that exceeds OrcaSlicer's BBS 3MF reader
                        // capacity on Android.  Skip restructuring for multi-plate files — each
                        // plate is extracted later by extractPlate() which keeps the per-plate
                        // files small.  OrcaSlicer handles component refs (p:path) natively.
                        val totalComponentSize = if (hasMultiColorComponents) {
                            componentFileNames.sumOf { name -> srcZip.getEntry(name)?.size ?: 0L }
                        } else 0L
                        // Detect multi-plate: check for virtual-position build items (TX>270 or
                        // TY<0) which indicate multiple plates packed into one model.
                        val modelXml = String(mainModelContent!!)
                        val isMultiPlate = detectMultiPlateFromBuild(modelXml)
                        if (isMultiPlate && hasMultiColorComponents) {
                            Log.i(TAG, "Multi-plate file detected — skipping restructuring " +
                                "(${totalComponentSize / 1_000_000}MB components), will restructure per-plate")
                            deferredRestructuring = true
                        }
                        // For single-plate files: 50MB threshold allows restructuring of large
                        // multi-color models.  For multi-plate files: always skip — inlining all
                        // plates' meshes creates oversized XML that crashes the BBS reader.
                        val safeToInline = !isMultiPlate && totalComponentSize <= 50_000_000L  // 50 MB
                        if (hasMultiColorComponents && !safeToInline && !isMultiPlate) {
                            Log.w(TAG, "Component files too large to inline " +
                                "(${totalComponentSize / 1_000_000}MB > 50MB), preserving component refs")
                        }
                        // Load component files only when safe to do so
                        val componentFiles = if (hasMultiColorComponents && safeToInline) {
                            componentFileNames.associateWith { name ->
                                srcZip.getEntry(name)?.let { srcZip.getInputStream(it).readBytes() } ?: ByteArray(0)
                            }
                        } else {
                            emptyMap()
                        }

                        val (finalModelBytes, newParentParts) = if (hasMultiColorComponents && safeToInline) {
                            restructureForMultiColor(mainModelContent!!, bambuObjectParts, componentFiles)
                        } else {
                            Pair(convertMmuSegmentation(mainModelContent!!), emptyMap())
                        }

                        // Clean model XML: strip Bambu extensions.
                        // For restructured files: strip everything including p:path.
                        // For non-restructured files: preserve p:path (needed for component refs).
                        val cleanedModel = if (hasMultiColorComponents && newParentParts.isNotEmpty()) {
                            cleanModelXml(finalModelBytes)
                        } else {
                            cleanModelXmlPreserveComponentRefs(finalModelBytes)
                        }
                        val cleanedModelFinal = stripNonPrintableBuildItems(cleanedModel)
                        writeStored(destZip, "3D/3dmodel.model", cleanedModelFinal)

                        val wasRestructured = newParentParts.isNotEmpty()

                        // Write component files and rels:
                        // - If restructured (meshes inlined): skip component files + rels entirely
                        // - If not restructured: write them (needed for component refs)
                        if (!wasRestructured) {
                            // Copy component files — use streaming for large files to avoid OOM
                            for (path in componentFileNames) {
                                val srcEntry = srcZip.getEntry(path) ?: continue
                                if (deferredRestructuring) {
                                    // Multi-plate deferred: raw-copy without XML cleaning.
                                    // restructurePlateFile() will clean when inlining meshes.
                                    rawCopyZipEntry(srcZip, srcEntry, destZip)
                                } else if (srcEntry.size > 10_000_000) {
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

                        // Override bambuObjectParts with the new per-parent-object part lists
                        if (newParentParts.isNotEmpty()) {
                            bambuObjectParts.clear()
                            for ((parentId, parts) in newParentParts) {
                                bambuObjectParts[parentId] = parts.toMutableList()
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
                    if (deferredRestructuring && modelSettingsContent != null) {
                        // For multi-plate files where restructuring was deferred, preserve
                        // model_settings.config so restructurePlateFile() can restructure
                        // each plate individually after extraction.  Skip Slic3r_PE_model.config
                        // to avoid embed() writing duplicate model_settings.config entries.
                        writeStored(destZip, "Metadata/model_settings.config", modelSettingsContent!!)
                        Log.i(TAG, "Preserved model_settings.config for deferred per-plate restructuring")
                    } else if (needsModelConfig) {
                        // For compound objects (restructured with components), use OrcaSlicer-format
                        // model_settings.config with <part> entries.  The BBS 3MF reader matches
                        // part IDs to component objectids for per-volume extruder assignment.
                        // Slic3r_PE_model.config with firstid/lastid doesn't work for compound
                        // objects because each component has its own mesh (no shared triangle index).
                        val isCompound = bambuObjectParts.values.any { parts ->
                            parts.any { it.meshObjectId.isNotEmpty() }
                        }
                        if (isCompound) {
                            val modelConfig = buildOrcaModelConfig(bambuObjectParts)
                            writeStored(destZip, "Metadata/model_settings.config", modelConfig.toByteArray())
                            Log.i(TAG, "Generated model_settings.config (compound):\n$modelConfig")
                        } else {
                            val slic3rModelConfig = buildSlic3rModelConfig(bambuObjectParts)
                            writeStored(destZip, "Metadata/Slic3r_PE_model.config", slic3rModelConfig.toByteArray())
                            Log.i(TAG, "Generated Slic3r_PE_model.config:\n$slic3rModelConfig")
                        }
                    } else {
                        // No model config needed — no-op
                    }
                } // ZipOutputStream
            } // ZipFile

            Log.i(TAG, "Sanitization complete: ${outputFile.absolutePath}")
            return outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Sanitization failed: ${e.message}")
            // Return original file as fallback
            outputFile.delete()
            return inputFile
        }
    }

    /** Per-part info extracted from Bambu model_settings.config.
     *  [meshObjectId] is set after restructuring — it's the inlined mesh object ID used as
     *  the `<part id>` in model_settings.config for compound objects. */
    private data class PartInfo(val faceCount: Int, val extruder: Int, val meshObjectId: String = "")

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

    /**
     * Build OrcaSlicer-format model_settings.config for compound objects (parent + components).
     *
     * Unlike [buildSlic3rModelConfig] which uses firstid/lastid triangle ranges (only works
     * for single-mesh objects), this generates `<part>` entries that map 1:1 to the compound
     * object's `<component>` elements.  OrcaSlicer's BBS 3MF reader matches part IDs to
     * component object IDs for per-volume extruder assignment.
     */
    private fun buildOrcaModelConfig(objectParts: Map<String, List<PartInfo>>): String {
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("<config>")

        for ((objectId, parts) in objectParts.entries.sortedBy { it.key.toIntOrNull() ?: 0 }) {
            if (parts.isEmpty()) continue
            val overallExtruder = parts.maxOf { it.extruder }
            sb.appendLine("""  <object id="$objectId">""")
            sb.appendLine("""    <metadata key="name" value=""/>""")
            sb.appendLine("""    <metadata key="extruder" value="$overallExtruder"/>""")

            for (part in parts) {
                if (part.meshObjectId.isNotEmpty()) {
                    sb.appendLine("""    <part id="${part.meshObjectId}" subtype="normal_part">""")
                    sb.appendLine("""      <metadata key="name" value=""/>""")
                    sb.appendLine("""      <metadata key="extruder" value="${part.extruder}"/>""")
                    sb.appendLine("""    </part>""")
                }
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
     * component parts with different extruders) into a single parent object with inlined
     * mesh components.
     *
     * For example, a Calicube stored as:
     *   Object 2 → {Component→mesh (p:path), extruder=1}, {Component→mesh (p:path), extruder=2}
     *   Build: item objectid=2, transform=T
     *
     * becomes:
     *   Object 1 → inlined mesh from component 1
     *   Object 2 → inlined mesh from component 2
     *   Object 3 (parent) → <components> refs to 1 and 2 (with component transforms)
     *   Build: item objectid=3, transform=T
     *
     * This keeps the assembly as ONE OrcaSlicer object with multiple volumes, so
     * ensure_on_bed() operates on the whole assembly (B8 fix: assembly parts stay in
     * their relative positions instead of being dropped individually).
     *
     * Per-volume extruder assignments use firstid/lastid face ranges in model config.
     *
     * Returns (new model bytes, parentObjectId → list of PartInfo for config generation).
     */
    private fun restructureForMultiColor(
        modelContent: ByteArray,
        objectParts: Map<String, List<PartInfo>>,
        componentFiles: Map<String, ByteArray> = emptyMap()
    ): Pair<ByteArray, Map<String, List<PartInfo>>> {
        // Identify compound objects that need splitting
        val splitTargets = objectParts.filter { (_, parts) ->
            parts.map { it.extruder }.distinct().size > 1
        }
        if (splitTargets.isEmpty()) {
            return Pair(convertMmuSegmentation(modelContent), emptyMap())
        }

        val modelStr = String(modelContent)
        val newParentParts = mutableMapOf<String, List<PartInfo>>()

        // Parse component refs and build transforms from the model
        val objectComponents = mutableMapOf<String, List<ComponentRef>>()
        val buildTransforms = mutableMapOf<String, FloatArray>()
        parseMainModelStructure(modelContent, objectComponents, buildTransforms)

        var result = modelStr
        // Use small sequential IDs (starting at 1) — OrcaSlicer's model config
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

            val meshObjectDefs = StringBuilder()
            val componentRefs = StringBuilder()
            val partInfos = mutableListOf<PartInfo>()

            for ((idx, component) in components.withIndex()) {
                val meshId = (nextId++).toString()
                val extruder = parts[idx].extruder

                // Inline the mesh as a separate object in <resources>
                val meshXml = componentFiles[component.path.trimStart('/')]
                    ?.let { extractMeshXml(it, component.objectId) }

                if (meshXml != null) {
                    meshObjectDefs.append("""  <object id="$meshId" type="model">
    $meshXml
  </object>
""")
                    val faceCount = countTriangles(meshXml)
                    // Component ref with the COMPONENT transform (not combined with build)
                    val tStr = component.transform.joinToString(" ") { "%.9f".format(it) }
                    componentRefs.append("""      <component objectid="$meshId" transform="$tStr"/>
""")
                    partInfos.add(PartInfo(faceCount, extruder, meshObjectId = meshId))
                } else {
                    // Fallback: p:path component reference (preserves original structure)
                    Log.w(TAG, "Could not inline mesh for component ${component.path}/${component.objectId}, using component ref")
                    val tStr = component.transform.joinToString(" ") { "%.9f".format(it) }
                    componentRefs.append("""      <component p:path="${component.path}" objectid="${component.objectId}" transform="$tStr"/>
""")
                    val faceCount = parts[idx].faceCount
                    partInfos.add(PartInfo(faceCount, extruder))
                }
            }

            // Create parent object with <components> referencing the inlined meshes.
            // ONE build item for the parent keeps the assembly as a single OrcaSlicer object.
            val parentId = (nextId++).toString()
            val parentObjectDef = """  <object id="$parentId" type="model">
    <components>
$componentRefs    </components>
  </object>
"""
            val tStr = buildTransform.joinToString(" ") { "%.9f".format(it) }
            val buildItem = """    <item objectid="$parentId" transform="$tStr"/>
"""

            // Remove original compound object and its build item; insert new ones
            result = removeObjectBlock(result, objectId)
            result = removeBuildItem(result, objectId)
            result = result.replace("</resources>", "${meshObjectDefs}${parentObjectDef}</resources>")
            result = result.replace("</build>", "${buildItem}</build>")

            newParentParts[parentId] = partInfos
            Log.i(TAG, "Restructured object $objectId into compound object $parentId with ${partInfos.size} component volumes")
        }

        return Pair(convertMmuSegmentation(result.toByteArray()), newParentParts)
    }

    /** Count <triangle> elements in a mesh XML string. */
    private fun countTriangles(meshXml: String): Int {
        var count = 0
        var idx = 0
        while (true) {
            idx = meshXml.indexOf("<triangle", idx)
            if (idx < 0) break
            count++
            idx += 9
        }
        return count
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
     * Strip PrusaSlicer slic3rpe:mmu_segmentation attributes from triangle elements.
     *
     * PrusaSlicer uses a different encoding than Bambu's paint_color format.
     * Renaming slic3rpe:mmu_segmentation → paint_color produces malformed data
     * that causes OrcaSlicer's multi_material_segmentation_by_painting() to crash
     * with SIGSEGV (corrupt ExPolygons).  Strip the attribute entirely so it is
     * never loaded into mmu_segmentation_facets.
     * Multi-color for PrusaSlicer files is handled by per-volume extruder assignment
     * (Slic3r_PE_model.config) which is injected by process().
     *
     * Native Bambu paint_color= attributes (e.g. colored 3DBenchy) are NOT affected
     * by this function — they remain in place so OrcaSlicer's SEMM algorithm can
     * process them correctly.
     */
    private fun convertMmuSegmentation(content: ByteArray): ByteArray {
        var text = String(content)
        if (!text.contains("slic3rpe:mmu_segmentation")) return content

        // Strip the attribute value and name (handles both quoted forms)
        text = text.replace(Regex("""\s+slic3rpe:mmu_segmentation="[^"]*""""), "")
        text = text.replace(Regex("""\s+xmlns:slic3rpe="[^"]*""""), "")

        return text.toByteArray()
    }

    // ---- Multi-plate detection (used by process()) ----

    /**
     * Detect whether the model XML represents a multi-plate file by inspecting
     * <build> items for virtual-position transforms (TX>270 or TY<-10 or TY>270)
     * or p:object_id plate markers.
     */
    private fun detectMultiPlateFromBuild(modelXml: String): Boolean {
        val buildRegex = Regex("""<build\b[^>]*>(.*?)</build>""", setOf(RegexOption.DOT_MATCHES_ALL))
        val buildBody = buildRegex.find(modelXml)?.groupValues?.get(1) ?: return false
        val itemRegex = Regex("""<item\b[^>]*(?:/>|>.*?</item>)""", setOf(RegexOption.DOT_MATCHES_ALL))
        val items = itemRegex.findAll(buildBody).map { it.value }.toList()
        if (items.size <= 1) return false
        // Check for p:object_id plate markers (older Bambu format with explicit plate tags)
        if (items.any { it.contains("p:object_id=") }) return true
        // Check for virtual-position transforms
        val transformRegex = Regex("""transform="([^"]+)"""")
        return items.any { item ->
            val parts = transformRegex.find(item)?.groupValues?.get(1)
                ?.trim()?.split(Regex("\\s+")) ?: emptyList()
            val tx = parts.getOrNull(9)?.toFloatOrNull() ?: 0f
            val ty = parts.getOrNull(10)?.toFloatOrNull() ?: 0f
            tx > 270f || ty < -10f || ty > 270f
        }
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
        // Strip PrusaSlicer mmu_segmentation paint data: for restructured files multi-color
        // is handled via per-volume extruder assignment (Slic3r_PE_model.config), not SEMM.
        // Keeping slic3rpe:mmu_segmentation would populate mmu_segmentation_facets and trigger
        // multi_material_segmentation_by_painting(), causing SIGSEGV on this data.
        if (text.contains("slic3rpe:mmu_segmentation")) {
            text = text.replace(Regex("""\s+slic3rpe:mmu_segmentation="[^"]*""""), "")
            text = text.replace(Regex("""\s+xmlns:slic3rpe="[^"]*""""), "")
        }
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
            // Line-by-line streaming clean to avoid OOM on 100MB+ entries.
            // Fast path: 99.9%+ of lines are mesh data (<vertex>/<triangle>) with no
            // Bambu attributes — skip regex entirely for those lines.
            tmpFile.bufferedWriter().use { out ->
                srcZip.getInputStream(srcEntry).bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        // Fast path: mesh data lines contain none of the target patterns
                        if (!line.contains("p:UUID") && !line.contains("requiredextensions") &&
                            !line.contains("xmlns:BambuStudio") && !line.contains("xmlns:slic3rpe") &&
                            !line.contains("<metadata") && !line.contains("mmu_segmentation") &&
                            !line.contains("type=\"other\"")) {
                            if (line.isNotBlank()) {
                                out.write(line)
                                out.newLine()
                            }
                            return@forEachLine
                        }
                        // Slow path: header/footer lines — apply full regex cleaning
                        var cleaned = line.replace(pUuidRegex, "")
                        cleaned = cleaned.replace(reqExtRegex, "")
                        cleaned = cleaned.replace(bambuNsRegex, "")
                        cleaned = cleaned.replace(slic3rpeNsRegex, "")
                        cleaned = cleaned.replace(metadataRegex, "")
                        if (cleaned.contains("slic3rpe:mmu_segmentation=")) {
                            cleaned = cleaned.replace(Regex("""\s+slic3rpe:mmu_segmentation="[^"]*""""), "")
                        }
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

    /** Raw-copy a ZIP entry without any processing — preserves original CRC/size. */
    private fun rawCopyZipEntry(srcZip: ZipFile, srcEntry: ZipEntry, destZip: ZipOutputStream) {
        val entry = ZipEntry(srcEntry.name)
        entry.method = ZipEntry.STORED
        entry.size = srcEntry.size
        entry.compressedSize = srcEntry.size
        entry.crc = srcEntry.crc
        destZip.putNextEntry(entry)
        srcZip.getInputStream(srcEntry).use { input ->
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } >= 0) {
                destZip.write(buf, 0, n)
            }
        }
        destZip.closeEntry()
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
                     hasPlateJsons: Boolean? = null,
                     plateObjectIds: Set<String>? = null): File {
        val outputFile = File(outputDir, "plate${targetPlateId}_${inputFile.name}")

        ZipFile(inputFile).use { srcZip ->
            // Auto-detect from the file when the caller hasn't provided an explicit value.
            val actualHasPlateJsons = hasPlateJsons ?: srcZip.entries().asSequence()
                .any { it.name.matches(Regex("Metadata/plate_\\d+\\.json")) }

            // Use caller-provided plate object IDs (from ThreeMfParser which reads the
            // original file before process() strips model_settings.config), or fall back
            // to parsing from the file if still available.
            val effectivePlateObjectIds = plateObjectIds ?: run {
                val map = mutableMapOf<Int, MutableList<String>>()
                val msEntry = srcZip.getEntry("Metadata/model_settings.config")
                if (msEntry != null) {
                    parseModelSettingsPlateObjects(
                        srcZip.getInputStream(msEntry).readBytes(), map
                    )
                }
                map[targetPlateId]?.toSet()
            }

            // Two-pass approach: first filter the main model to determine which
            // object IDs are referenced, then write entries — stripping unreferenced
            // objects from config.  This keeps plate files lean when the original
            // file has many plates.
            var referencedObjectIds: Set<String>? = null

            ZipOutputStream(FileOutputStream(outputFile)).use { destZip ->
                for (entry in srcZip.entries()) {
                    val content = srcZip.getInputStream(entry).readBytes()

                    when {
                        entry.name == "3D/3dmodel.model" -> {
                            val filtered = filterModelToPlate(String(content), targetPlateId,
                                actualHasPlateJsons, effectivePlateObjectIds)
                            val (stripped, refIds) = stripUnreferencedResources(filtered)
                            referencedObjectIds = refIds
                            if (refIds != null) {
                                Log.i(TAG, "extractPlate: stripped unreferenced resources, " +
                                    "keeping ${refIds.size} object(s)")
                            }
                            writeStored(destZip, entry.name, stripped.toByteArray())
                        }
                        // Component model files: keep only if they contain meshes
                        // referenced by the target plate's objects
                        entry.name.endsWith(".model") && entry.name != "3D/3dmodel.model" -> {
                            writeStored(destZip, entry.name, content)
                        }
                        entry.name == "Metadata/model_settings.config" -> {
                            // Strip <assemble> section and unreferenced object entries
                            var stripped = stripAssembleSection(String(content))
                            val refIds = referencedObjectIds
                            if (refIds != null) {
                                stripped = stripUnreferencedConfigObjects(stripped, refIds)
                            }
                            writeStored(destZip, entry.name, stripped.toByteArray())
                        }
                        entry.name == "Metadata/Slic3r_PE_model.config" -> {
                            // Strip unreferenced object entries from PrusaSlicer config too
                            var text = String(content)
                            val refIds = referencedObjectIds
                            if (refIds != null) {
                                text = stripUnreferencedConfigObjects(text, refIds)
                            }
                            writeStored(destZip, entry.name, text.toByteArray())
                        }
                        else -> writeStored(destZip, entry.name, content)
                    }
                }
            }
        }
        return outputFile
    }

    /**
     * Restructure an extracted single-plate file: inline component meshes into
     * the main model so OrcaSlicer can assign per-volume extruders.
     *
     * This is needed for multi-plate files where [process] skips restructuring
     * to avoid oversized XML.  After [extractPlate] produces a manageable
     * single-plate file, this method inlines the component meshes and generates
     * the correct Slic3r_PE_model.config for per-object extruder assignments.
     *
     * @return The restructured file, or [plateFile] unchanged if no
     *   restructuring was needed.
     */
    fun restructurePlateFile(plateFile: File, outDir: File): File {
        ZipFile(plateFile).use { srcZip ->
            // 1. Parse extruder assignments from model_settings.config
            val bambuObjectParts = mutableMapOf<String, MutableList<PartInfo>>()
            val modelSettingsEntry = srcZip.getEntry("Metadata/model_settings.config")
            if (modelSettingsEntry != null) {
                parseModelSettingsExtruders(
                    srcZip.getInputStream(modelSettingsEntry).readBytes(),
                    bambuObjectParts
                )
            }

            // Check if multi-color restructuring is needed
            val hasMultiColorComponents = bambuObjectParts.values.any { parts ->
                parts.filter { it.extruder in 1..4 }.map { it.extruder }.distinct().size > 1
            }
            if (!hasMultiColorComponents) {
                Log.d(TAG, "restructurePlateFile: no multi-color components, skipping")
                return plateFile
            }

            // 2. Read main model and component files
            val mainModelEntry = srcZip.getEntry("3D/3dmodel.model")
                ?: return plateFile
            // Clean the main model XML BEFORE restructuring — it's small (~1KB of XML
            // wrapper + component refs). After restructuring, the inlined mesh data
            // bloats it to 15MB+, making regex cleaning 7× slower for no benefit
            // (mesh data contains no Bambu attributes).
            // MUST use PreserveComponentRefs variant: restructureForMultiColor() needs
            // p:path attributes to locate meshes in component files.
            val rawMainModel = srcZip.getInputStream(mainModelEntry).readBytes()
            val mainModelContent = cleanModelXmlPreserveComponentRefs(rawMainModel)

            val componentFileNames = mutableListOf<String>()
            for (entry in srcZip.entries()) {
                if (entry.name.endsWith(".model") && entry.name != "3D/3dmodel.model") {
                    componentFileNames.add(entry.name)
                }
            }

            val componentFiles = componentFileNames.associateWith { name ->
                srcZip.getEntry(name)?.let { srcZip.getInputStream(it).readBytes() }
                    ?: ByteArray(0)
            }

            // 3. Restructure — keeps assembly as one compound object with per-volume extruders
            val (restructuredModel, newParentParts) =
                restructureForMultiColor(mainModelContent, bambuObjectParts, componentFiles)

            if (newParentParts.isEmpty()) {
                Log.w(TAG, "restructurePlateFile: restructuring produced no new objects")
                return plateFile
            }

            Log.i(TAG, "restructurePlateFile: created ${newParentParts.size} compound object(s) " +
                "from ${componentFileNames.size} component file(s)")

            // 4. Update object parts for config generation
            bambuObjectParts.clear()
            for ((parentId, parts) in newParentParts) {
                bambuObjectParts[parentId] = parts.toMutableList()
            }

            // 5. Write output ZIP — model already cleaned before restructuring,
            // only need to strip non-printable build items
            val cleanedModelFinal = stripNonPrintableBuildItems(restructuredModel)

            val outputFile = File(outDir, "restructured_${plateFile.name}")
            ZipOutputStream(FileOutputStream(outputFile)).use { destZip ->
                writeStored(destZip, "3D/3dmodel.model", cleanedModelFinal)

                // Generate model_settings.config in OrcaSlicer format with <part> entries.
                // Compound objects need per-part extruder assignment (not firstid/lastid).
                val modelConfig = buildOrcaModelConfig(bambuObjectParts)
                writeStored(destZip, "Metadata/model_settings.config", modelConfig.toByteArray())

                // Copy remaining entries (skip old model, components, configs)
                for (entry in srcZip.entries()) {
                    when {
                        entry.name == "3D/3dmodel.model" -> {} // already written
                        entry.name.endsWith(".model") -> {} // skip component files (inlined)
                        entry.name.endsWith(".rels") && entry.name.contains("3dmodel.model") -> {} // not needed
                        entry.name == "Metadata/model_settings.config" -> {} // already written (OrcaSlicer format)
                        entry.name == "Metadata/Slic3r_PE_model.config" -> {} // replaced by model_settings
                        else -> rawCopyZipEntry(srcZip, entry, destZip)
                    }
                }
            }
            return outputFile
        }
    }

    /**
     * Strip `<object>` blocks from `<resources>` that are NOT referenced by any `<build>` item.
     * After filterModelToPlate reduces the build section to a single plate's items, the
     * resources may contain objects for all other plates — stripping them keeps the file lean.
     *
     * Returns the stripped XML and the set of referenced object IDs (null if no stripping needed).
     */
    private fun stripUnreferencedResources(xml: String): Pair<String, Set<String>?> {
        val buildRegex = Regex("""<build\b[^>]*>(.*?)</build>""", setOf(RegexOption.DOT_MATCHES_ALL))
        val buildMatch = buildRegex.find(xml) ?: return Pair(xml, null)
        val itemObjIdRegex = Regex("""objectid="(\d+)"""")
        val buildItemIds = itemObjIdRegex.findAll(buildMatch.groupValues[1])
            .map { it.groupValues[1] }.toSet()
        if (buildItemIds.isEmpty()) return Pair(xml, null)

        // BFS: collect all transitively referenced objects via component refs
        val allReferencedIds = buildItemIds.toMutableSet()
        val objectBlockRegex = Regex("""<object\s+id="(\d+)"[^>]*>[\s\S]*?</object>""")
        val componentObjIdRegex = Regex("""<component[^>]*\bobjectid="(\d+)"[^>]*>""")
        val objectBlocks = objectBlockRegex.findAll(xml).toList()
        val queue = ArrayDeque(buildItemIds)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            val block = objectBlocks.find { it.groupValues[1] == id } ?: continue
            for (ref in componentObjIdRegex.findAll(block.value).map { it.groupValues[1] }) {
                if (allReferencedIds.add(ref)) queue.addLast(ref)
            }
        }

        val allObjectIds = objectBlocks.map { it.groupValues[1] }.toSet()
        val unreferenced = allObjectIds - allReferencedIds
        if (unreferenced.isEmpty()) return Pair(xml, null)

        var result = xml
        for (block in objectBlocks) {
            if (block.groupValues[1] in unreferenced) {
                result = result.replace(block.value, "")
            }
        }
        result = result.replace(Regex("""\n\s*\n\s*\n"""), "\n\n")
        Log.i(TAG, "stripUnreferencedResources: removed ${unreferenced.size} object(s), " +
                "kept ${allReferencedIds.size}: $allReferencedIds")
        return Pair(result, allReferencedIds)
    }

    /**
     * Strip `<object>` entries from model_settings.config or Slic3r_PE_model.config
     * that don't match any of the [referencedIds].
     */
    private fun stripUnreferencedConfigObjects(xml: String, referencedIds: Set<String>): String {
        val objectBlockRegex = Regex("""[ \t]*<object\s+id="(\d+)"[^>]*>[\s\S]*?</object>[ \t]*\r?\n?""")
        return objectBlockRegex.replace(xml) { match ->
            if (match.groupValues[1] in referencedIds) match.value else ""
        }
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
    /** String overload for use by ProfileEmbedder. */
    internal fun stripNonPrintableBuildItems(xml: String): String {
        return stripNonPrintableBuildItemsImpl(xml)
    }

    internal fun stripNonPrintableBuildItems(modelBytes: ByteArray): ByteArray {
        return stripNonPrintableBuildItemsImpl(String(modelBytes)).toByteArray()
    }

    private fun stripNonPrintableBuildItemsImpl(xml: String): String {
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
        return result
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
                                   hasPlateJsons: Boolean = true,
                                   plateObjectIds: Set<String>? = null): String {
        val targetPlateIndex = targetPlateId - 1  // p:object_id is 0-based

        val buildRegex  = Regex("""(<build\b[^>]*>)(.*?)(</build>)""", setOf(RegexOption.DOT_MATCHES_ALL))
        val itemRegex   = Regex("""<item\b[^>]*(?:/>|>.*?</item>)""",  setOf(RegexOption.DOT_MATCHES_ALL))
        val objectIdRegex = Regex("""objectid="(\d+)"""")
        val plateIdRegex = Regex("""p:object_id="(\d+)"""")

        return buildRegex.replace(xml) { m ->
            val open  = m.groupValues[1]
            val body  = m.groupValues[2]
            val close = m.groupValues[3]

            val allItems = itemRegex.findAll(body).map { it.value }.toList()

            // Priority 1: model_settings.config plate→object mapping (most reliable).
            // Filters build items by objectid matching the target plate's object list.
            if (plateObjectIds != null && plateObjectIds.isNotEmpty()) {
                val targetItems = allItems.filter { item ->
                    val objId = objectIdRegex.find(item)?.groupValues?.get(1) ?: ""
                    objId in plateObjectIds
                }
                if (targetItems.isEmpty()) {
                    Log.w(TAG, "filterModelToPlate: plate $targetPlateId — no items matched objectIds $plateObjectIds, keeping all")
                    return@replace m.value
                }
                // Re-centre items that are at virtual positions (off the 270mm bed)
                val recentered = targetItems.map { recenterItemIfVirtual(it) }
                Log.i(TAG, "filterModelToPlate: plate $targetPlateId — config-based, kept ${targetItems.size}/${allItems.size} items (objectIds=$plateObjectIds)")
                val newBody = "\n" + recentered.joinToString("\n") { "    $it" } + "\n  "
                return@replace "$open$newBody$close"
            }

            // Priority 2: p:object_id attribute on build items (older Bambu format)
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
                Log.i(TAG, "filterModelToPlate: plate $targetPlateId — p:object_id, kept ${targetItems.size}/${allItems.size} items")
                val newBody = "\n" + targetItems.joinToString("\n") { "    $it" } + "\n  "
                return@replace "$open$newBody$close"
            }

            // Priority 3: position-based fallback (no plate mapping, no p:object_id)
            val transformRegex = Regex("""transform="([^"]+)"""")
            val hasVirtualPositions = allItems.any { item ->
                val parts = transformRegex.find(item)?.groupValues?.get(1)
                    ?.trim()?.split(Regex("\\s+")) ?: emptyList()
                val tx = parts.getOrNull(9)?.toFloatOrNull() ?: 0f
                val ty = parts.getOrNull(10)?.toFloatOrNull() ?: 0f
                tx > 270f || ty < -10f || ty > 270f
            }

            if (allItems.size <= 1 || (!hasPlateJsons && !hasVirtualPositions)) return@replace m.value

            if (hasVirtualPositions) {
                val idx = targetPlateIndex.coerceIn(0, allItems.size - 1)
                val selected = recenterItemXY(allItems[idx])
                Log.i(TAG, "filterModelToPlate: plate $targetPlateId — position-based (virtual), selected item ${idx + 1}/${allItems.size}")
                val newBody = "\n    $selected\n  "
                "$open$newBody$close"
            } else {
                Log.i(TAG, "filterModelToPlate: plate $targetPlateId — no virtual positions, keeping all ${allItems.size} items")
                m.value
            }
        }
    }

    /**
     * Lightweight parser to extract plate→object_id mappings from model_settings.config.
     * Used by extractPlate() to pass plate object IDs to filterModelToPlate().
     */
    private fun parseModelSettingsPlateObjects(
        configBytes: ByteArray,
        plateObjectMap: MutableMap<Int, MutableList<String>>
    ) {
        try {
            val config = String(configBytes)
            val plateRegex = Regex("""<plate\b[^>]*>.*?</plate>""", setOf(RegexOption.DOT_MATCHES_ALL))
            val platerIdRegex = Regex("""<metadata\s+key="plater_id"\s+value="(\d+)"""")
            val objIdRegex = Regex("""<metadata\s+key="object_id"\s+value="(\d+)"""")
            val instanceRegex = Regex("""<model_instance>.*?</model_instance>""", setOf(RegexOption.DOT_MATCHES_ALL))

            for (plate in plateRegex.findAll(config)) {
                val plateId = platerIdRegex.find(plate.value)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val objIds = instanceRegex.findAll(plate.value).mapNotNull { inst ->
                    objIdRegex.find(inst.value)?.groupValues?.get(1)
                }.toList()
                if (objIds.isNotEmpty()) {
                    plateObjectMap[plateId] = objIds.toMutableList()
                }
            }
            if (plateObjectMap.isNotEmpty()) {
                Log.i(TAG, "parseModelSettingsPlateObjects: ${plateObjectMap.size} plates — $plateObjectMap")
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseModelSettingsPlateObjects failed: ${e.message}")
        }
    }

    /**
     * Re-centres an item's XY only if it's at a virtual position (outside the 270mm bed).
     * Items at normal bed coordinates are returned unchanged.
     */
    private fun recenterItemIfVirtual(item: String): String {
        val transformRegex = Regex("""transform="([^"]+)"""")
        val parts = transformRegex.find(item)?.groupValues?.get(1)
            ?.trim()?.split(Regex("\\s+")) ?: return item
        val tx = parts.getOrNull(9)?.toFloatOrNull() ?: 0f
        val ty = parts.getOrNull(10)?.toFloatOrNull() ?: 0f
        return if (tx > 270f || ty < -10f || ty > 270f) recenterItemXY(item) else item
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
