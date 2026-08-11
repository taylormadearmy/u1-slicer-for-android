package com.u1.slicer.bambu

import org.json.JSONArray
import org.json.JSONObject
import com.u1.slicer.slice.SlicerTarget
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object BambuProjectWriter {

    fun writeSinglePlateProject(
        outputFile: File,
        gcodeFile: File,
        modelName: String,
        plateId: Int,
        filamentColours: List<String>,
        filamentTypes: List<String>,
        sourceFilamentIndices: List<Int> = emptyList(),
        target: SlicerTarget = SlicerTarget.BambuA1Mini,
        filamentNozzleMap: List<Int> = emptyList(),
    ) {
        require(target.family == com.u1.slicer.slice.SliceTargetFamily.BAMBU && target.supportsLocalSlicing) {
            "Bambu project packaging is not supported for $target"
        }
        val machine = BAMBU_MACHINE_PROFILES.getValue(target)
        val normalizedModelName = normalizeModelName(modelName)
        val normalizedFilamentColours = normalizeFilamentColours(filamentColours)
        val normalizedFilamentTypes = normalizeFilamentTypes(filamentTypes, normalizedFilamentColours.size)
        val filamentSettingsIds = normalizedFilamentTypes.map { buildFilamentSettingsId(it, machine) }
        val filamentIds = normalizedFilamentTypes.map { buildFilamentId(it, machine) }
        val sourceGcodeText = gcodeFile.readText(StandardCharsets.UTF_8)
        val normalizedFilamentMap = normalizeFilamentNozzleMap(
            filamentNozzleMap = filamentNozzleMap,
            filamentCount = normalizedFilamentColours.size,
            machine = machine,
        )
        val printMetadata = BambuGcodeProjectMetadataParser.parse(
            gcode = sourceGcodeText,
            printableBounds = BambuPrintBounds(0.0, 0.0, machine.bedSizeX.toDouble(), machine.bedSizeY.toDouble()),
        )
        val thumbnail = extractThumbnail(sourceGcodeText) ?: FALLBACK_PNG
        val gcodeText = normalizeGcodeForProject(sourceGcodeText)
        val gcode = gcodeText.toByteArray(StandardCharsets.UTF_8)
        val gcodeMd5 = gcode.md5Uppercase()
        val outputParent = outputFile.absoluteFile.parentFile
            ?: error("Bambu project output has no parent directory")
        outputParent.mkdirs()
        val temporaryOutput = File.createTempFile(".${outputFile.name}.", ".tmp", outputParent)
        var published = false
        try {
            ZipOutputStream(temporaryOutput.outputStream().buffered()).use { zip ->
                write(zip, "_rels/.rels", rootRelationshipsXml(plateId))
                write(zip, "3D/3dmodel.model", modelXml(normalizedModelName, printMetadata, machine))
                write(zip, "3D/_rels/3dmodel.model.rels", modelRelationshipsXml())
                write(zip, "3D/Objects/object_1.model", objectModelXml(normalizedModelName, printMetadata))
                write(
                    zip,
                    "Metadata/model_settings.config",
                    modelSettingsXml(
                        modelName = normalizedModelName,
                        plateId = plateId,
                        filamentCount = normalizedFilamentColours.size,
                        printMetadata = printMetadata,
                        filamentNozzleMap = normalizedFilamentMap,
                        machine = machine,
                    ),
                )
                write(zip, "Metadata/cut_information.xml", cutInformationXml())
                write(
                    zip,
                    "Metadata/project_settings.config",
                    projectSettings(
                        gcodeText = gcodeText,
                        modelName = normalizedModelName,
                        filamentColours = normalizedFilamentColours,
                        filamentTypes = normalizedFilamentTypes,
                        filamentSettingsIds = filamentSettingsIds,
                        filamentIds = filamentIds,
                        sourceFilamentIndices = sourceFilamentIndices,
                        machine = machine,
                        filamentNozzleMap = normalizedFilamentMap,
                    ).toString(),
                )
                // The printer resolves the selected plate through this relationship,
                // not merely from the project_file MQTT param.
                write(zip, "Metadata/_rels/model_settings.config.rels", modelSettingsRelationshipsXml(plateId))
                write(
                    zip,
                    "Metadata/plate_${plateId}.json",
                    plateMetadataJson(
                        modelName = normalizedModelName,
                        filamentColours = normalizedFilamentColours,
                        printMetadata = printMetadata,
                        machine = machine,
                        filamentNozzleMap = normalizedFilamentMap,
                    ).toString(),
                )
                write(zip, "Metadata/plate_${plateId}.gcode", gcode)
                write(zip, "Metadata/plate_${plateId}.gcode.md5", gcodeMd5)
                write(zip, "Metadata/plate_${plateId}.png", thumbnail)
                write(zip, "Metadata/plate_no_light_${plateId}.png", thumbnail)
                write(zip, "Metadata/plate_${plateId}_small.png", thumbnail)
                write(zip, "Metadata/top_${plateId}.png", thumbnail)
                write(zip, "Metadata/pick_${plateId}.png", thumbnail)
                write(zip, "Metadata/filament_sequence.json", filamentSequenceJson(plateId, normalizedFilamentColours.size).toString())
                write(
                    zip,
                    "Metadata/slice_info.config",
                    sliceInfoXml(
                        plateId = plateId,
                        modelName = normalizedModelName,
                        filamentColours = normalizedFilamentColours,
                        filamentTypes = normalizedFilamentTypes,
                        printMetadata = printMetadata,
                        machine = machine,
                        filamentNozzleMap = normalizedFilamentMap,
                    ),
                )
                write(zip, "[Content_Types].xml", CONTENT_TYPES_XML)
            }
            publishAtomically(temporaryOutput, outputFile)
            published = true
        } finally {
            if (!published) temporaryOutput.delete()
        }
    }

    private fun publishAtomically(temporaryOutput: File, outputFile: File) {
        try {
            Files.move(
                temporaryOutput.toPath(),
                outputFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporaryOutput.toPath(),
                outputFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun write(zip: ZipOutputStream, path: String, body: String) {
        write(zip, path, body.toByteArray(StandardCharsets.UTF_8))
    }

    private fun write(zip: ZipOutputStream, path: String, body: ByteArray) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(body)
        zip.closeEntry()
    }

    /**
     * Bambu Studio serializes the complete resolved slicer config into the
     * project. The G-code from the embedded Orca engine already carries that
     * config in its CONFIG_BLOCK, so preserve it rather than inventing a thin
     * project settings file that the printer may reject.
     */
    private fun projectSettings(
        gcodeText: String,
        modelName: String,
        filamentColours: List<String>,
        filamentTypes: List<String>,
        filamentSettingsIds: List<String>,
        filamentIds: List<String>,
        sourceFilamentIndices: List<Int>,
        machine: BambuMachineProfile,
        filamentNozzleMap: List<Int>,
    ): JSONObject = JSONObject().apply {
        configFromGcode(gcodeText).forEach { (key, value) ->
            put(key, compactPerFilamentSetting(key, value, sourceFilamentIndices))
        }
        put("model_name", modelName)
        put("printer_model", machine.printerModel)
        put("printer_model_id", machine.printerModelId)
        put("printer_variant", "0.4")
        put("printer_settings_id", machine.printerSettingsId)
        put(
            "printable_area",
            JSONArray(
                listOf(
                    "0x0",
                    "${formatNumber(machine.bedSizeX.toDouble())}x0",
                    "${formatNumber(machine.bedSizeX.toDouble())}x${formatNumber(machine.bedSizeY.toDouble())}",
                    "0x${formatNumber(machine.bedSizeY.toDouble())}",
                ),
            ),
        )
        put("printable_height", formatNumber(machine.maxPrintHeight.toDouble()))
        put("nozzle_diameter", JSONArray(machine.nozzleDiameters))
        put("filament_map", JSONArray(filamentNozzleMap))
        put("master_extruder_id", (machine.masterNozzle + 1).toString())
        if (machine.nozzlePrintableAreas.size > 1) {
            // BambuStudio keeps three related maps for dual-nozzle projects:
            // filament_map is the one-based logical extruder, while
            // filament_nozzle_map is the zero-based logical nozzle group.
            // The latter is also Bambuddy's fallback when slice_info.config
            // does not carry the authoritative per-filament group_id.
            put("filament_nozzle_map", JSONArray(filamentNozzleMap.map { it - 1 }))
            put("filament_volume_map", JSONArray(List(filamentNozzleMap.size) { 0 }))
            put(
                "extruder_printable_area",
                JSONArray(machine.nozzlePrintableAreas.map(::printableAreaString)),
            )
            put("physical_extruder_map", JSONArray(listOf("1", "0")))
            put(
                "extruder_nozzle_stats",
                JSONArray(
                    List(machine.nozzleDiameters.size) { nozzleIndex ->
                        val filamentCount = filamentNozzleMap.count { it == nozzleIndex + 1 }
                        "Standard#$filamentCount"
                    },
                ),
            )
            put("nozzle_volume_type", JSONArray(List(machine.nozzleDiameters.size) { "Standard" }))
            put("default_nozzle_volume_type", JSONArray(List(machine.nozzleDiameters.size) { "Standard" }))
            put("extruder_type", JSONArray(List(machine.nozzleDiameters.size) { "Direct Drive" }))
            // H2D firmware performs a physical hotend check during startup.
            // Do not preserve the single-extruder core's `undefine`/`0`
            // placeholders: declare the official selectable H2D variants.
            if (machine.nozzleTypes.isNotEmpty()) {
                put("nozzle_type", JSONArray(machine.nozzleTypes))
            }
            if (machine.nozzleVolumes.isNotEmpty()) {
                put("nozzle_volume", JSONArray(machine.nozzleVolumes))
            }
            if (machine.printerExtruderIds.isNotEmpty()) {
                put("printer_extruder_id", JSONArray(machine.printerExtruderIds))
            }
            if (machine.printerExtruderVariants.isNotEmpty()) {
                put("printer_extruder_variant", JSONArray(machine.printerExtruderVariants))
            }
            if (machine.extruderVariantList.isNotEmpty()) {
                put("extruder_variant_list", JSONArray(machine.extruderVariantList))
            }
        }
        put("filament_colour", JSONArray(filamentColours))
        put("extruder_colour", JSONArray(filamentColours))
        put("filament_type", JSONArray(filamentTypes))
        put("filament_settings_id", JSONArray(filamentSettingsIds))
        put("filament_ids", JSONArray(filamentIds))
        put("default_filament_profile", JSONArray(filamentSettingsIds))
        put("filament_count", filamentColours.size.toString())
        put("gcode_flavor", "marlin")
    }

    private fun configFromGcode(gcodeText: String): Map<String, String> {
        val config = linkedMapOf<String, String>()
        var inConfigBlock = false
        gcodeText.lineSequence().forEach { line ->
            when {
                line == "; CONFIG_BLOCK_START" -> inConfigBlock = true
                line == "; CONFIG_BLOCK_END" -> inConfigBlock = false
                inConfigBlock && line.startsWith("; ") -> {
                    val body = line.removePrefix("; ")
                    val separator = body.indexOf(" = ")
                    if (separator > 0) {
                        config[body.substring(0, separator)] = body.substring(separator + 3)
                    }
                }
            }
        }
        return config
    }

    /**
     * Bambu print archives place CONFIG_BLOCK before EXECUTABLE_BLOCK and keep
     * previews as separate PNG entries. The native CLI output puts its config
     * at EOF and embeds base64 thumbnails; older A-series firmware rejects
     * that layout before it reports a project_file result.
     */
    internal fun normalizeGcodeForProject(source: String): String {
        val lines = source
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')

        val configStart = lines.indexOfFirst { it == "; CONFIG_BLOCK_START" }
        val configEnd = if (configStart >= 0) {
            lines.indexOfFirstFrom(configStart + 1) { it == "; CONFIG_BLOCK_END" }
        } else {
            -1
        }
        val configLines = if (configStart >= 0 && configEnd >= configStart) {
            lines.subList(configStart, configEnd + 1)
        } else {
            emptyList()
        }

        val withoutConfig = lines.filterIndexed { index, _ ->
            configLines.isEmpty() || index !in configStart..configEnd
        }
        val cleaned = mutableListOf<String>()
        var inThumbnail = false
        withoutConfig.forEach { line ->
            when {
                line.startsWith("; thumbnail begin ") -> inThumbnail = true
                inThumbnail && line == "; thumbnail end" -> inThumbnail = false
                !inThumbnail -> cleaned += line
            }
        }

        if (configLines.isNotEmpty()) {
            val headerEnd = cleaned.indexOfFirst { it == "; HEADER_BLOCK_END" }
            val executableStart = cleaned.indexOfFirst { it == "; EXECUTABLE_BLOCK_START" }
            val insertionIndex = when {
                headerEnd >= 0 -> headerEnd + 1
                executableStart >= 0 -> executableStart
                else -> 0
            }
            val insertion = buildList {
                if (insertionIndex > 0 && cleaned[insertionIndex - 1].isNotBlank()) add("")
                addAll(configLines)
                if (cleaned.getOrNull(insertionIndex)?.isNotBlank() != false) add("")
            }
            cleaned.addAll(insertionIndex, insertion)
        }

        return cleaned.joinToString("\n").trimEnd() + "\n"
    }

    private fun List<String>.indexOfFirstFrom(
        startIndex: Int,
        predicate: (String) -> Boolean,
    ): Int {
        for (index in startIndex until size) {
            if (predicate(this[index])) return index
        }
        return -1
    }

    private fun compactPerFilamentSetting(
        key: String,
        value: String,
        sourceFilamentIndices: List<Int>,
    ): Any {
        val compacted = BambuFilamentConfigCompactor.compact(key, value, sourceFilamentIndices)
            ?: return value
        return JSONArray(compacted)
    }

    private fun extractThumbnail(gcodeText: String): ByteArray? {
        val lines = gcodeText.lineSequence().iterator()
        var fallback: ByteArray? = null
        while (lines.hasNext()) {
            val line = lines.next()
            if (!line.startsWith("; thumbnail begin ")) continue
            val isLarge = line.contains("300x300")
            val encoded = StringBuilder()
            while (lines.hasNext()) {
                val thumbnailLine = lines.next()
                if (thumbnailLine == "; thumbnail end") break
                if (thumbnailLine.startsWith("; ")) encoded.append(thumbnailLine.removePrefix("; "))
            }
            val decoded = runCatching { Base64.getDecoder().decode(encoded.toString()) }.getOrNull() ?: continue
            if (isLarge) return decoded
            fallback = decoded
        }
        return fallback
    }

    private fun ByteArray.md5Uppercase(): String = MessageDigest.getInstance("MD5")
        .digest(this)
        .joinToString("") { byte -> "%02X".format(byte) }

    private fun normalizeModelName(modelName: String): String =
        modelName.trim()
            .substringBeforeLast('.', modelName.trim())
            .ifBlank { "Model" }

    private fun normalizeFilamentColours(filamentColours: List<String>): List<String> {
        val normalized = filamentColours
            .map(::normalizeHexColour)
            .filter { it.isNotBlank() }
        return if (normalized.isNotEmpty()) normalized else listOf("#FFFFFF")
    }

    private fun normalizeFilamentTypes(
        filamentTypes: List<String>,
        targetSize: Int,
    ): List<String> {
        val normalized = filamentTypes.map { it.trim().ifBlank { "PLA" } }
        return List(targetSize) { index -> normalized.getOrNull(index) ?: "PLA" }
    }

    private fun normalizeHexColour(raw: String): String {
        val trimmed = raw.trim()
        val sixDigit = Regex("^#[0-9A-Fa-f]{6}$")
        val eightDigit = Regex("^#[0-9A-Fa-f]{8}$")
        return when {
            sixDigit.matches(trimmed) -> trimmed.uppercase()
            eightDigit.matches(trimmed) -> trimmed.take(7).uppercase()
            else -> "#FFFFFF"
        }
    }

    private fun buildFilamentSettingsId(
        filamentType: String,
        machine: BambuMachineProfile,
    ): String {
        val trimmed = filamentType.trim().ifBlank { "PLA" }
        return when {
            trimmed.equals("PLA", ignoreCase = true) -> machine.defaultPlaFilamentSettingsId
            trimmed.startsWith("Generic ", ignoreCase = true) -> trimmed
            else -> "Generic $trimmed"
        }
    }

    private fun buildFilamentId(
        filamentType: String,
        machine: BambuMachineProfile,
    ): String = when {
        filamentType.trim().equals("PLA", ignoreCase = true) -> "GFA00"
        else -> buildFilamentSettingsId(filamentType, machine)
    }

    private fun modelXml(
        modelName: String,
        printMetadata: BambuGcodeProjectMetadata,
        machine: BambuMachineProfile,
    ): String {
        val bounds = printMetadata.objectBounds ?: printMetadata.plateBounds
        val centreX = bounds?.centreX ?: machine.bedSizeX / 2.0
        val centreY = bounds?.centreY ?: machine.bedSizeY / 2.0
        return """
        <?xml version="1.0" encoding="UTF-8"?>
        <model unit="millimeter" xml:lang="en-US" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02" xmlns:BambuStudio="http://schemas.bambulab.com/package/2021" xmlns:p="http://schemas.microsoft.com/3dmanufacturing/production/2015/06" requiredextensions="p">
          <metadata name="Application">BambuStudio-02.02.04.00</metadata>
          <metadata name="BambuStudio:3mfVersion">1</metadata>
          <metadata name="Title">${xmlEscape(modelName)}</metadata>
          <resources>
            <object id="2" p:UUID="00000001-71cb-4c03-9d28-80fed5dfa1dc" type="model" name="${xmlEscape(modelName)}">
              <components>
                <component p:path="/3D/Objects/object_1.model" objectid="1" p:UUID="00010000-b206-40ff-9872-83e8017abed1"/>
              </components>
            </object>
          </resources>
          <build p:UUID="2c7c17d8-22b5-4d84-8835-1976022ea369">
            <item objectid="2" p:UUID="00000002-b1ec-4553-aec9-835e5b724bb4" transform="1 0 0 0 1 0 0 0 1 ${formatNumber(centreX)} ${formatNumber(centreY)} 0" printable="1"/>
          </build>
        </model>
        """.trimIndent()
    }

    private fun objectModelXml(
        modelName: String,
        printMetadata: BambuGcodeProjectMetadata,
    ): String {
        val bounds = printMetadata.objectBounds ?: printMetadata.plateBounds
        val halfWidth = ((bounds?.width ?: 1.0).coerceAtLeast(1.0)) / 2.0
        val halfDepth = ((bounds?.depth ?: 1.0).coerceAtLeast(1.0)) / 2.0
        val height = printMetadata.maxZ.coerceAtLeast(1.0)
        return """
        <?xml version="1.0" encoding="UTF-8"?>
        <model unit="millimeter" xml:lang="en-US" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02" xmlns:BambuStudio="http://schemas.bambulab.com/package/2021" xmlns:p="http://schemas.microsoft.com/3dmanufacturing/production/2015/06" requiredextensions="p">
          <metadata name="BambuStudio:3mfVersion">1</metadata>
          <resources>
            <object id="1" p:UUID="00010000-81cb-4c03-9d28-80fed5dfa1dc" type="model" name="${xmlEscape(modelName)}">
              <mesh>
                <vertices>
                  <vertex x="-${formatNumber(halfWidth)}" y="-${formatNumber(halfDepth)}" z="0"/><vertex x="${formatNumber(halfWidth)}" y="-${formatNumber(halfDepth)}" z="0"/>
                  <vertex x="${formatNumber(halfWidth)}" y="${formatNumber(halfDepth)}" z="0"/><vertex x="-${formatNumber(halfWidth)}" y="${formatNumber(halfDepth)}" z="0"/>
                  <vertex x="-${formatNumber(halfWidth)}" y="-${formatNumber(halfDepth)}" z="${formatNumber(height)}"/><vertex x="${formatNumber(halfWidth)}" y="-${formatNumber(halfDepth)}" z="${formatNumber(height)}"/>
                  <vertex x="${formatNumber(halfWidth)}" y="${formatNumber(halfDepth)}" z="${formatNumber(height)}"/><vertex x="-${formatNumber(halfWidth)}" y="${formatNumber(halfDepth)}" z="${formatNumber(height)}"/>
                </vertices>
                <triangles>
                  <triangle v1="0" v2="2" v3="1"/><triangle v1="0" v2="3" v3="2"/>
                  <triangle v1="4" v2="5" v3="6"/><triangle v1="4" v2="6" v3="7"/>
                  <triangle v1="0" v2="1" v3="5"/><triangle v1="0" v2="5" v3="4"/>
                  <triangle v1="1" v2="2" v3="6"/><triangle v1="1" v2="6" v3="5"/>
                  <triangle v1="2" v2="3" v3="7"/><triangle v1="2" v2="7" v3="6"/>
                  <triangle v1="3" v2="0" v3="4"/><triangle v1="3" v2="4" v3="7"/>
                </triangles>
              </mesh>
            </object>
          </resources>
          <build/>
        </model>
        """.trimIndent()
    }

    private fun cutInformationXml(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <objects>
          <object id="1">
            <cut_id id="0" check_sum="1" connectors_cnt="0"/>
          </object>
        </objects>
    """.trimIndent()

    private fun modelSettingsXml(
        modelName: String,
        plateId: Int,
        filamentCount: Int,
        printMetadata: BambuGcodeProjectMetadata,
        filamentNozzleMap: List<Int>,
        machine: BambuMachineProfile,
    ): String {
        val filamentMaps = filamentNozzleMap.take(filamentCount).joinToString(" ")
        val filamentVolumeMaps = List(filamentCount) { "0" }.joinToString(" ")
        val plateName = "Plate $plateId - $modelName"
        val bounds = printMetadata.objectBounds ?: printMetadata.plateBounds
        val centreX = bounds?.centreX ?: machine.bedSizeX / 2.0
        val centreY = bounds?.centreY ?: machine.bedSizeY / 2.0
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <config>
              <object id="2">
                <metadata key="name" value="${xmlEscape(modelName)}"/>
                <metadata key="extruder" value="1"/>
                <metadata face_count="12"/>
                <part id="1" subtype="normal_part">
                  <metadata key="name" value="${xmlEscape(modelName)}"/>
                  <metadata key="extruder" value="1"/>
                  <mesh_stat face_count="12" edges_fixed="0" degenerate_facets="0" facets_removed="0" facets_reversed="0" backwards_edges="0"/>
                </part>
              </object>
              <plate>
                <metadata key="plater_id" value="$plateId"/>
                <metadata key="plater_name" value="${xmlEscape(plateName)}"/>
                <metadata key="locked" value="false"/>
                <metadata key="filament_map_mode" value="Auto For Flush"/>
                <metadata key="thumbnail_file" value="Metadata/plate_${plateId}.png"/>
                <metadata key="thumbnail_no_light_file" value="Metadata/plate_no_light_${plateId}.png"/>
                <metadata key="top_file" value="Metadata/top_${plateId}.png"/>
                <metadata key="pick_file" value="Metadata/pick_${plateId}.png"/>
                <metadata key="pattern_bbox_file" value="Metadata/plate_${plateId}.json"/>
                <metadata key="gcode_file" value="Metadata/plate_${plateId}.gcode"/>
                <metadata key="filament_maps" value="$filamentMaps"/>
                <metadata key="filament_volume_maps" value="$filamentVolumeMaps"/>
                <model_instance>
                  <metadata key="object_id" value="2"/>
                  <metadata key="instance_id" value="0"/>
                  <metadata key="identify_id" value="1"/>
                </model_instance>
              </plate>
              <assemble>
                <assemble_item object_id="2" instance_id="0" transform="1 0 0 0 1 0 0 0 1 ${formatNumber(centreX)} ${formatNumber(centreY)} 0" offset="0 0 0"/>
              </assemble>
            </config>
        """.trimIndent()
    }

    private fun sliceInfoXml(
        plateId: Int,
        modelName: String,
        filamentColours: List<String>,
        filamentTypes: List<String>,
        printMetadata: BambuGcodeProjectMetadata,
        machine: BambuMachineProfile,
        filamentNozzleMap: List<Int>,
    ): String {
        val filaments = filamentColours.indices.joinToString("\n") { index ->
            val usedMetres = printMetadata.filamentUsedMetres.getOrElse(index) { 0.0 }
            val usedGrams = printMetadata.filamentUsedGrams.getOrElse(index) { 0.0 }
            val nozzle = filamentNozzleMap[index]
            val groupId = nozzle - 1
            val diameter = machine.nozzleDiameters[nozzle - 1]
            """    <filament id="${index + 1}" tray_info_idx="${buildFilamentId(filamentTypes[index], machine)}" type="${xmlEscape(filamentTypes[index])}" color="${filamentColours[index]}" used_m="${formatNumber(usedMetres)}" used_g="${formatNumber(usedGrams)}" group_id="$groupId" nozzle_diameter="${formatNumber(diameter.toDouble())}" volume_type="Standard" used_for_object="true" used_for_support="false"/>"""
        }
        val maps = filamentNozzleMap.joinToString(" ")
        val limitMaps = List(filamentColours.size) { "0" }.joinToString(" ")
        val nozzleVector = List(machine.nozzleDiameters.size) { "0" }.joinToString(",")
        val nozzles = filamentNozzleMap.distinct().sorted().joinToString("\n") { nozzle ->
            val diameter = machine.nozzleDiameters[nozzle - 1]
            """    <nozzle id="${nozzle - 1}" extruder_id="$nozzle" nozzle_diameter="${formatNumber(diameter.toDouble())}" volume_type="Standard"/>"""
        }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <config>
              <header>
                <header_item key="X-BBL-Client-Type" value="slicer"/>
                <header_item key="X-BBL-Client-Version" value="01.09.01.52"/>
              </header>
              <plate>
                <metadata key="index" value="$plateId"/>
                <metadata key="extruder_type" value="$nozzleVector"/>
                <metadata key="nozzle_volume_type" value="$nozzleVector"/>
                <metadata key="printer_model_id" value="${machine.printerModelId}"/>
                <metadata key="nozzle_diameters" value="${machine.nozzleDiameters.joinToString(",") { formatNumber(it.toDouble()) }}"/>
                <metadata key="timelapse_type" value="0"/>
                <metadata key="prediction" value="${printMetadata.predictionSeconds}"/>
                <metadata key="weight" value="${formatNumber(printMetadata.totalWeightGrams)}"/>
                <metadata key="first_layer_time" value="0"/>
                <metadata key="outside" value="false"/>
                <metadata key="support_used" value="${printMetadata.supportUsed}"/>
                <metadata key="label_object_enabled" value="false"/>
                <metadata key="enable_filament_dynamic_map" value="false"/>
                <metadata key="filament_maps" value="$maps"/>
                <metadata key="limit_filament_maps" value="$limitMaps"/>
                <metadata key="has_filament_switcher" value="${machine.supportsFilamentTrackSwitch}"/>
                <object identify_id="1" name="${xmlEscape(modelName)}" skipped="false" />
$filaments
$nozzles
              </plate>
            </config>
        """.trimIndent().trimStart()
    }

    private fun xmlEscape(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            append(
                when (ch) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> ch
                }
            )
        }
    }

    private fun formatNumber(value: Double): String {
        val canonical = if (value in -0.00005..0.00005) 0.0 else value
        return String.format(Locale.US, "%.4f", canonical)
            .trimEnd('0')
            .trimEnd('.')
    }

    private fun rootRelationshipsXml(plateId: Int): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Target="/3D/3dmodel.model" Id="rel-1" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>
          <Relationship Target="/Metadata/plate_${plateId}.png" Id="rel-2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/thumbnail"/>
          <Relationship Target="/Metadata/plate_${plateId}.png" Id="rel-4" Type="http://schemas.bambulab.com/package/2021/cover-thumbnail-middle"/>
          <Relationship Target="/Metadata/plate_${plateId}_small.png" Id="rel-5" Type="http://schemas.bambulab.com/package/2021/cover-thumbnail-small"/>
        </Relationships>
    """.trimIndent()

    private fun modelRelationshipsXml(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Target="/3D/Objects/object_1.model" Id="rel-1" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>
        </Relationships>
    """.trimIndent()

    private fun modelSettingsRelationshipsXml(plateId: Int): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Target="/Metadata/plate_${plateId}.gcode" Id="rel-1" Type="http://schemas.bambulab.com/package/2021/gcode"/>
        </Relationships>
    """.trimIndent()

    private fun plateMetadataJson(
        modelName: String,
        filamentColours: List<String>,
        printMetadata: BambuGcodeProjectMetadata,
        machine: BambuMachineProfile,
        filamentNozzleMap: List<Int>,
    ): JSONObject = JSONObject().apply {
        val plateBounds = printMetadata.plateBounds ?: printMetadata.objectBounds
        val objectBounds = printMetadata.objectBounds ?: plateBounds
        put("bbox_all", boundsJson(plateBounds))
        // Bambu's plate metadata addresses project filament slots from zero.
        put("filament_ids", JSONArray(List(filamentColours.size) { it }))
        put("filament_colors", JSONArray(filamentColours))
        put("is_seq_print", false)
        put("first_extruder", filamentNozzleMap.firstOrNull()?.minus(1) ?: machine.masterNozzle)
        put("nozzle_diameter", machine.nozzleDiameters[filamentNozzleMap.firstOrNull()?.minus(1) ?: machine.masterNozzle])
        put("version", 2)
        put("bed_type", printMetadata.bedType)
        put("first_layer_time", 0.0)
        put(
            "bbox_objects",
            JSONArray().put(
                JSONObject()
                    .put("id", 1)
                    .put("bbox", boundsJson(objectBounds))
                    .put("area", printMetadata.objectArea)
                    .put("layer_height", printMetadata.layerHeight)
                    .put("name", modelName),
            ),
        )
    }

    private fun boundsJson(bounds: BambuPrintBounds?): JSONArray = if (bounds == null) {
        JSONArray(listOf(0, 0, 0, 0))
    } else {
        JSONArray(listOf(bounds.minX, bounds.minY, bounds.maxX, bounds.maxY))
    }

    private fun filamentSequenceJson(plateId: Int, filamentCount: Int): JSONObject = JSONObject().apply {
        put("plate_$plateId", JSONObject().put("sequence", JSONArray(List(filamentCount) { it + 1 })))
    }

    private fun normalizeFilamentNozzleMap(
        filamentNozzleMap: List<Int>,
        filamentCount: Int,
        machine: BambuMachineProfile,
    ): List<Int> {
        val fallback = machine.masterNozzle + 1
        return List(filamentCount) { index -> filamentNozzleMap.getOrNull(index) ?: fallback }
            .also { map ->
                require(map.all { it in 1..machine.nozzleDiameters.size }) {
                    "Filament-to-nozzle map contains a nozzle not present on ${machine.printerModel}"
                }
            }
    }

    private fun printableAreaString(bounds: BambuPrintBounds): String =
        "${formatNumber(bounds.minX)}x${formatNumber(bounds.minY)}," +
            "${formatNumber(bounds.maxX)}x${formatNumber(bounds.minY)}," +
            "${formatNumber(bounds.maxX)}x${formatNumber(bounds.maxY)}," +
            "${formatNumber(bounds.minX)}x${formatNumber(bounds.maxY)}"

    private val CONTENT_TYPES_XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
          <Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/>
          <Default Extension="png" ContentType="image/png"/>
          <Default Extension="gcode" ContentType="text/x.gcode"/>
          <Default Extension="json" ContentType="application/json"/>
          <Override PartName="/Metadata/model_settings.config" ContentType="application/xml"/>
          <Override PartName="/Metadata/project_settings.config" ContentType="application/json"/>
        </Types>
    """.trimIndent()

    private val FALLBACK_PNG = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScL9swAAAABJRU5ErkJggg==",
    )
}
