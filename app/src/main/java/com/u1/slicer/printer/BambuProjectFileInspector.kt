package com.u1.slicer.printer

import com.u1.slicer.bambu.ThreeMfInfo
import com.u1.slicer.data.BambuModel
import com.u1.slicer.network.FilamentRouting
import com.u1.slicer.network.FilamentSlot
import com.u1.slicer.network.NozzleSide
import com.u1.slicer.network.NozzleHardwareStatus
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.DigestInputStream
import java.util.Locale
import java.util.zip.ZipFile

data class BambuProjectDescriptor(
    val sourceFile: File,
    val displayName: String,
    val selectedPlateId: Int,
    val plateGcodeEntry: String,
    val isH2D: Boolean = false,
    /** Project-wide one-based nozzle ids read from slice_info.config. */
    val filamentNozzleMap: List<Int> = emptyList(),
    val requiresFilamentTrackSwitch: Boolean = false,
)

data class BambuProjectPreflight(
    val plateGcodeMd5: String,
    val projectFilamentCount: Int,
    val amsMapping: List<Int>,
    val projectNozzleDiameters: List<Float> = emptyList(),
    val projectNozzleTypes: List<String> = emptyList(),
)

object BambuProjectFileInspector {
    /**
     * Imported Bambu projects can be either model-only 3MFs or already-sliced
     * print projects. Only the latter contain a plate G-code entry that Bambu
     * printers can execute without re-slicing.
     */
    fun hasEmbeddedPlateGcode(
        rawInputFile: File?,
        info: ThreeMfInfo?,
    ): Boolean {
        val file = rawInputFile
            ?.takeIf { it.exists() && it.isFile && it.name.endsWith(".3mf", ignoreCase = true) }
            ?: return false
        if (info?.isBambu != true) return false
        return runCatching {
            ZipFile(file).use { zip ->
                zip.entries().asSequence().any { entry ->
                    entry.name.matches(Regex("Metadata/plate_[0-9]+\\.gcode", RegexOption.IGNORE_CASE))
                }
            }
        }.getOrDefault(false)
    }

    fun describe(
        rawInputFile: File?,
        sourceDisplayName: String,
        selectedPlateId: Int?,
        info: ThreeMfInfo?,
    ): BambuProjectDescriptor? {
        val file = rawInputFile
            ?.takeIf { it.exists() && it.isFile && it.name.endsWith(".3mf", ignoreCase = true) }
            ?: return null
        if (info?.isBambu != true) return null
        val plateId = selectedPlateId
            ?.takeIf { it > 0 }
            ?: info.plates.firstOrNull()?.plateId
            ?: 1
        val plateEntry = "Metadata/plate_${plateId}.gcode"
        val hasPlateGcode = runCatching {
            ZipFile(file).use { zip -> zip.getEntry(plateEntry) != null }
        }.getOrDefault(false)
        if (!hasPlateGcode) return null
        val embeddedNozzleInfo = runCatching {
            ZipFile(file).use { zip ->
                val machineName = zip.getEntry(plateEntry)?.let { entry ->
                    zip.getInputStream(entry).bufferedReader().useLines { lines ->
                        lines.mapNotNull(::machineNameFromGcodeLine).firstOrNull()
                    }
                }
                val isH2D = machineName?.contains("h2d", ignoreCase = true) == true
                val routing = if (isH2D) readH2DRouting(zip) else H2DRouting()
                Triple(isH2D, routing.filamentNozzleMap, routing.requiresFilamentTrackSwitch)
            }
        }.getOrDefault(Triple(false, emptyList(), false))
        return BambuProjectDescriptor(
            sourceFile = file,
            displayName = sourceDisplayName.ifBlank { file.name },
            selectedPlateId = plateId,
            plateGcodeEntry = plateEntry,
            isH2D = embeddedNozzleInfo.first,
            filamentNozzleMap = embeddedNozzleInfo.second,
            requiresFilamentTrackSwitch = embeddedNozzleInfo.third,
        )
    }

    fun validateExecutableProject(
        projectFile: File,
        plateId: Int,
        model: BambuModel,
        amsMapping: List<Int>,
        filamentSlots: List<FilamentSlot> = emptyList(),
        filamentTrackSwitchInstalled: Boolean = false,
        installedNozzles: List<NozzleHardwareStatus> = emptyList(),
    ): Result<BambuProjectPreflight> = runCatching {
        require(projectFile.exists() && projectFile.isFile) {
            "The uploaded Bambu project is no longer available on this device"
        }
        require(plateId > 0) { "The selected Bambu plate is invalid" }

        ZipFile(projectFile).use { zip ->
            val prefix = "Metadata/plate_$plateId"
            val gcodeEntry = zip.getEntry("$prefix.gcode")
                ?: error("The uploaded 3MF does not contain G-code for plate $plateId")
            val plateJsonEntry = zip.getEntry("$prefix.json")
                ?: error("The uploaded 3MF is missing plate $plateId metadata")
            val md5Entry = zip.getEntry("$prefix.gcode.md5")
                ?: error("The uploaded 3MF is missing the plate $plateId G-code checksum")

            var filamentColourCount: Int? = null
            var filamentIdCount: Int? = null
            var inLegacyToolchangeLoad = false
            var hasLegacyToolchangeLoadExtrusion = false
            val actualMd5 = MessageDigest.getInstance("MD5").let { digest ->
                DigestInputStream(zip.getInputStream(gcodeEntry), digest)
                    .bufferedReader()
                    .useLines { lines ->
                        lines.forEach { line ->
                            when (line.trim()) {
                                "; CP TOOLCHANGE LOAD" -> inLegacyToolchangeLoad = true
                                "; CP TOOLCHANGE WIPE" -> inLegacyToolchangeLoad = false
                                else -> if (inLegacyToolchangeLoad && POSITIVE_EXTRUSION_WORD.containsMatchIn(line)) {
                                    hasLegacyToolchangeLoadExtrusion = true
                                }
                            }
                            filamentCountFromLine(line, FILAMENT_COLOUR_REGEX)?.let {
                                filamentColourCount = it
                            }
                            filamentCountFromLine(line, FILAMENT_IDS_REGEX)?.let {
                                filamentIdCount = it
                            }
                        }
                    }
                digest.digest().joinToString("") { byte -> "%02X".format(byte) }
            }
            val storedMd5 = zip.getInputStream(md5Entry).bufferedReader().use { reader ->
                CHECKSUM_REGEX.find(reader.readText())?.value?.uppercase(Locale.ROOT)
            }
            require(storedMd5 == actualMd5) {
                "The uploaded 3MF has an invalid plate $plateId G-code checksum"
            }

            val machineName = zip.getInputStream(gcodeEntry).bufferedReader().useLines { lines ->
                lines.mapNotNull(::machineNameFromGcodeLine).firstOrNull()
            }
            require(machineName != null) {
                "The uploaded 3MF does not identify the printer profile used to slice it"
            }
            require(machineMatches(model, machineName)) {
                "This project was sliced for $machineName, not ${modelDisplayName(model)}"
            }
            require(model != BambuModel.H2D || !hasLegacyToolchangeLoadExtrusion) {
                "This H2D project contains an unsafe single-hotend purge-tower load. " +
                    "Re-slice it with the current H2D profile before printing."
            }

            val plateJson = zip.getInputStream(plateJsonEntry).bufferedReader().use { it.readText() }
            val filamentIds = JSONObject(plateJson).optJSONArray("filament_ids")
                ?: error("The uploaded 3MF is missing plate $plateId filament metadata")
            val usedFilamentPositions = (0 until filamentIds.length())
                .map { index -> filamentIds.optInt(index, -1) }
                .filter { it >= 0 }
                .distinct()
            val requiredMappingSize = usedFilamentPositions
                .maxOrNull()
                ?.plus(1)
                ?: 0
            require(amsMapping.size >= requiredMappingSize) {
                "This plate uses project filament position $requiredMappingSize, but only " +
                    "${amsMapping.size} filament mapping${if (amsMapping.size == 1) " was" else "s were"} provided"
            }
            val unresolvedPosition = usedFilamentPositions.firstOrNull { position ->
                amsMapping.getOrNull(position)?.let { it < 0 } != false
            }
            require(unresolvedPosition == null) {
                "Project filament ${unresolvedPosition!! + 1} has no selected AMS or external-spool route"
            }
            val routing = if (model == BambuModel.H2D) readH2DRouting(zip) else H2DRouting()
            val projectFilamentCount = if (model == BambuModel.H2D) {
                // H2D slice_info routing is authoritative. A compacted plate
                // can legitimately retain a stale, wider filament_colour
                // header from the source 3MF; counting that header would invent
                // filaments that have no plate entry or nozzle assignment.
                routing.filamentNozzleMap.size.takeIf { it > 0 }
                    ?.let { maxOf(requiredMappingSize, it) }
                    ?: maxOf(requiredMappingSize, filamentColourCount ?: filamentIdCount ?: requiredMappingSize)
            } else {
                requiredMappingSize
            }
            val projectNozzleDiameters = readProjectNozzleDiameters(zip)
            val projectNozzleTypes = readProjectNozzleTypes(zip)
            validateInstalledNozzles(
                model = model,
                projectNozzleDiameters = projectNozzleDiameters,
                projectNozzleTypes = projectNozzleTypes,
                usedFilamentPositions = usedFilamentPositions,
                filamentNozzleMap = routing.filamentNozzleMap,
                installedNozzles = installedNozzles,
            )
            val normalizedMapping = if (model == BambuModel.H2D) {
                require(amsMapping.drop(projectFilamentCount).all { it < 0 }) {
                    "The filament mapping contains positions not declared by this H2D project"
                }
                val result = amsMapping.take(projectFilamentCount) +
                    List((projectFilamentCount - amsMapping.size).coerceAtLeast(0)) { -1 }
                validateH2DNozzleRouting(
                    zip = zip,
                    amsMapping = result,
                    projectFilamentCount = projectFilamentCount,
                    filamentSlots = filamentSlots,
                    filamentTrackSwitchInstalled = filamentTrackSwitchInstalled,
                )
                result
            } else {
                // Match Bambuddy/BambuStudio: one route per project filament.
                // UI state may still contain mappings from another plate.
                amsMapping.take(projectFilamentCount)
            }

            BambuProjectPreflight(
                plateGcodeMd5 = actualMd5,
                projectFilamentCount = projectFilamentCount,
                amsMapping = normalizedMapping,
                projectNozzleDiameters = projectNozzleDiameters,
                projectNozzleTypes = projectNozzleTypes,
            )
        }
    }

    private fun readProjectNozzleDiameters(zip: ZipFile): List<Float> {
        val entry = zip.getEntry("Metadata/project_settings.config") ?: return emptyList()
        val value = runCatching {
            JSONObject(zip.getInputStream(entry).bufferedReader().use { it.readText() }).opt("nozzle_diameter")
        }.getOrNull() ?: return emptyList()
        val values = when (value) {
            is org.json.JSONArray -> (0 until value.length()).map { value.opt(it) }
            is Iterable<*> -> value.toList()
            else -> listOf(value)
        }
        return values.mapNotNull { raw ->
            when (raw) {
                is Number -> raw.toFloat()
                is String -> raw.toFloatOrNull()
                else -> null
            }?.takeIf { it > 0f }
        }
    }

    private fun readProjectNozzleTypes(zip: ZipFile): List<String> {
        val entry = zip.getEntry("Metadata/project_settings.config") ?: return emptyList()
        val value = runCatching {
            JSONObject(zip.getInputStream(entry).bufferedReader().use { it.readText() }).opt("nozzle_type")
        }.getOrNull() ?: return emptyList()
        val values = when (value) {
            is org.json.JSONArray -> (0 until value.length()).map { value.opt(it) }
            is Iterable<*> -> value.toList()
            is String -> value.split(',', ';')
            else -> listOf(value)
        }
        return values.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
    }

    /**
     * Block only a positive mismatch. Missing project/live data deliberately
     * remains compatible with older firmware, matching Bambuddy's fail-safe.
     */
    private fun validateInstalledNozzles(
        model: BambuModel,
        projectNozzleDiameters: List<Float>,
        projectNozzleTypes: List<String>,
        usedFilamentPositions: List<Int>,
        filamentNozzleMap: List<Int>,
        installedNozzles: List<NozzleHardwareStatus>,
    ) {
        val requiredNozzleIndexes = if (model == BambuModel.H2D && filamentNozzleMap.isNotEmpty()) {
            usedFilamentPositions.mapNotNull { position ->
                filamentNozzleMap.getOrNull(position)?.minus(1)?.takeIf { it in 0..1 }
            }.distinct()
        } else {
            listOf(0)
        }
        requiredNozzleIndexes.forEach { nozzleIndex ->
            val installedNozzle = installedNozzles.firstOrNull { it.index == nozzleIndex }
                ?: return@forEach
            val side = if (model == BambuModel.H2D) {
                if (nozzleIndex == 0) "left " else "right "
            } else {
                ""
            }
            val slicedDiameter = projectNozzleDiameters.getOrNull(nozzleIndex)
                ?: projectNozzleDiameters.singleOrNull()
            val installedDiameter = installedNozzle.diameter
            if (slicedDiameter != null && installedDiameter != null) {
                require(kotlin.math.abs(slicedDiameter - installedDiameter) < NOZZLE_DIAMETER_TOLERANCE) {
                    "This project was sliced for a ${formatDiameter(slicedDiameter)}mm ${side}nozzle, but the printer reports " +
                        "a ${formatDiameter(installedDiameter)}mm ${side}nozzle. Re-slice for the installed nozzle before printing."
                }
            }
            val slicedMaterial = projectNozzleTypes.getOrNull(nozzleIndex)
                ?: projectNozzleTypes.singleOrNull()
            val expected = canonicalNozzleMaterial(slicedMaterial)
            val installed = canonicalNozzleMaterial(installedNozzle.type)
            // Material identity is a dual-nozzle H2D safety check. A-series
            // projects may legitimately carry an undefined/legacy nozzle_type
            // value even though their single installed nozzle is otherwise
            // valid; applying this H2D-only guard to A1/A1 Mini produced the
            // misleading "This H2D project" preflight failure.
            if (model == BambuModel.H2D && installed != null && slicedMaterial.isUndefinedNozzleMaterial()) {
                error(
                    "This H2D project does not declare the ${side}nozzle material. " +
                        "Re-slice it with the current H2D profile before printing.",
                )
            }
            if (expected != null && installed != null) {
                require(expected == installed) {
                    "This project was sliced for a ${expected.replace('_', ' ')} ${side}nozzle, but the printer reports " +
                        "a ${installed.replace('_', ' ')} ${side}nozzle. Re-slice for the installed nozzle before printing."
                }
            }
        }
    }

    /**
     * H2D firmware reports compact hotend IDs (for example HS01). The last
     * pair identifies the material: 01 hardened steel and 00 stainless steel.
     * Unknown/older values intentionally return null so legacy firmware is not
     * blocked by data it does not expose.
     */
    private fun canonicalNozzleMaterial(value: String?): String? {
        val normalized = value?.trim()?.lowercase(Locale.ROOT)?.replace(' ', '_') ?: return null
        return when {
            normalized == "hardened_steel" -> "hardened_steel"
            normalized == "stainless_steel" -> "stainless_steel"
            normalized == "tungsten_carbide" -> "tungsten_carbide"
            normalized == "brass" -> "brass"
            normalized.matches(Regex("h[a-z]01")) -> "hardened_steel"
            normalized.matches(Regex("h[a-z]00")) -> "stainless_steel"
            else -> null
        }
    }

    private fun String?.isUndefinedNozzleMaterial(): Boolean =
        this?.trim()?.lowercase(Locale.ROOT) in setOf("undefine", "undefined", "unknown", "0")

    private fun formatDiameter(value: Float): String = value.toString().trimEnd('0').trimEnd('.')

    /** Validate both the project's logical nozzle groups and the printer's live tray topology. */
    private fun validateH2DNozzleRouting(
        zip: ZipFile,
        amsMapping: List<Int>,
        projectFilamentCount: Int,
        filamentSlots: List<FilamentSlot>,
        filamentTrackSwitchInstalled: Boolean,
    ) {
        val routing = readH2DRouting(zip)
        require(!routing.requiresFilamentTrackSwitch || filamentTrackSwitchInstalled) {
            "This H2D project uses dynamic Filament Track Switch routing, but the printer did not report an installed FTS"
        }
        val filamentMap = routing.filamentNozzleMap.ifEmpty { return }
        require(filamentMap.size >= projectFilamentCount) {
            "The H2D project does not declare a nozzle for every filament"
        }
        val slotsByRoute = filamentSlots.associateBy(FilamentSlot::index)
        amsMapping.forEachIndexed { index, route ->
            if (route < 0) return@forEachIndexed
            val nozzle = filamentMap.getOrNull(index) ?: return@forEachIndexed
            val slot = slotsByRoute[route]
            if (nozzle == DYNAMIC_NOZZLE) {
                require(slot?.routing == FilamentRouting.SWITCHABLE) {
                    "Project filament ${index + 1} uses dynamic FTS routing, but its selected tray is not reported as FTS-routed"
                }
                return@forEachIndexed
            }
            val requiredSide = nozzleSide(nozzle)
            val reportedSide = slot?.nozzleSide ?: externalNozzleSide(route)
            if (slot?.routing != FilamentRouting.SWITCHABLE &&
                reportedSide != NozzleSide.UNKNOWN &&
                reportedSide != requiredSide
            ) {
                val routeLabel = slot?.label ?: when (route) {
                    LEFT_EXTERNAL_ROUTE -> "left external spool"
                    RIGHT_EXTERNAL_ROUTE -> "right external spool"
                    else -> "selected tray"
                }
                error(
                    "Project filament ${index + 1} uses the ${requiredSide.displayName()} but $routeLabel feeds the " +
                        "${reportedSide.displayName()}",
                )
            }
        }
    }

    private data class H2DRouting(
        val filamentNozzleMap: List<Int> = emptyList(),
        val requiresFilamentTrackSwitch: Boolean = false,
    )

    /**
     * Current BambuStudio writes the authoritative zero-based nozzle group in
     * each filament element. A comma-list such as `0,1` denotes dynamic FTS
     * routing. Older fixed projects fall back to one-based `filament_maps`.
     */
    private fun readH2DRouting(zip: ZipFile): H2DRouting {
        val entry = zip.getEntry("Metadata/slice_info.config") ?: return H2DRouting()
        val xml = zip.getInputStream(entry).bufferedReader().use { it.readText() }
        val groupsByFilament = FILAMENT_TAG_REGEX.findAll(xml).mapNotNull { match ->
            val tag = match.value
            val id = XML_ID_REGEX.find(tag)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return@mapNotNull null
            val groups = XML_GROUP_ID_REGEX.find(tag)?.groupValues?.getOrNull(1)
                ?.split(',')
                ?.mapNotNull { it.trim().toIntOrNull() }
                ?.distinct()
                .orEmpty()
            if (groups.isEmpty()) null else id to groups
        }.toMap()
        if (groupsByFilament.isNotEmpty()) {
            val maxId = groupsByFilament.keys.maxOrNull() ?: 0
            return H2DRouting(
                filamentNozzleMap = List(maxId) { index ->
                    val groups = groupsByFilament[index + 1].orEmpty()
                    if (groups.size == 1 && groups.single() in 0..1) {
                        groups.single() + 1
                    } else {
                        DYNAMIC_NOZZLE
                    }
                },
                requiresFilamentTrackSwitch = HAS_FILAMENT_SWITCHER_TRUE.containsMatchIn(xml) ||
                    groupsByFilament.values.any { it.size > 1 },
            )
        }
        val fallback = FILAMENT_MAPS_REGEX.find(xml)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.mapNotNull(String::toIntOrNull)
            .orEmpty()
        return H2DRouting(
            filamentNozzleMap = fallback,
            requiresFilamentTrackSwitch = HAS_FILAMENT_SWITCHER_TRUE.containsMatchIn(xml),
        )
    }

    private fun nozzleSide(nozzle: Int): NozzleSide = when (nozzle) {
        1 -> NozzleSide.LEFT
        2 -> NozzleSide.RIGHT
        else -> NozzleSide.UNKNOWN
    }

    private fun externalNozzleSide(route: Int): NozzleSide = when (route) {
        LEFT_EXTERNAL_ROUTE -> NozzleSide.LEFT
        RIGHT_EXTERNAL_ROUTE -> NozzleSide.RIGHT
        else -> NozzleSide.UNKNOWN
    }

    private fun NozzleSide.displayName(): String = when (this) {
        NozzleSide.LEFT -> "left nozzle"
        NozzleSide.RIGHT -> "right nozzle"
        NozzleSide.UNKNOWN -> "unknown nozzle"
    }

    private fun filamentCountFromLine(line: String, regex: Regex): Int? =
        regex.find(line)
            ?.groupValues
            ?.getOrNull(1)
            ?.split(';')
            ?.count { it.trim().isNotEmpty() }
            ?.takeIf { it > 0 }

    private fun machineNameFromGcodeLine(line: String): String? {
        PRINTER_MODEL_REGEX.find(line)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let {
            return it
        }
        return MACHINE_HEADER_REGEX.find(line)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun machineMatches(model: BambuModel, machineName: String): Boolean {
        val normalized = machineName.lowercase(Locale.ROOT)
        return when (model) {
            BambuModel.A1_MINI -> "a1 mini" in normalized
            BambuModel.A1 -> Regex("\\ba1\\b").containsMatchIn(normalized) && "mini" !in normalized
            BambuModel.P1P -> "p1p" in normalized
            BambuModel.P1S -> "p1s" in normalized
            BambuModel.P2S -> "p2s" in normalized
            BambuModel.X1C -> "x1 carbon" in normalized || "x1c" in normalized
            BambuModel.X1E -> "x1e" in normalized
            BambuModel.H2D -> "h2d" in normalized
        }
    }

    private fun modelDisplayName(model: BambuModel): String = when (model) {
        BambuModel.A1_MINI -> "Bambu A1 Mini"
        BambuModel.A1 -> "Bambu A1"
        BambuModel.P1P -> "Bambu P1P"
        BambuModel.P1S -> "Bambu P1S"
        BambuModel.P2S -> "Bambu P2S"
        BambuModel.X1C -> "Bambu X1 Carbon"
        BambuModel.X1E -> "Bambu X1E"
        BambuModel.H2D -> "Bambu H2D"
    }

    private val CHECKSUM_REGEX = Regex("[0-9a-fA-F]{32}")
    private val PRINTER_MODEL_REGEX = Regex("^;\\s*printer_model\\s*=\\s*(.+)$", RegexOption.IGNORE_CASE)
    private val MACHINE_HEADER_REGEX = Regex("^;=+\\s*machine:\\s*([^=]+)", RegexOption.IGNORE_CASE)
    private val FILAMENT_COLOUR_REGEX = Regex(
        "^;\\s*filament_colour\\s*=\\s*(.+)$",
        RegexOption.IGNORE_CASE,
    )
    private val FILAMENT_IDS_REGEX = Regex(
        "^;\\s*filament_ids\\s*=\\s*(.+)$",
        RegexOption.IGNORE_CASE,
    )
    private val FILAMENT_MAPS_REGEX = Regex("""key="filament_maps"\s+value="([^"]+)"""")
    private val FILAMENT_TAG_REGEX = Regex("""<filament\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val XML_ID_REGEX = Regex("""\bid="(\d+)"""", RegexOption.IGNORE_CASE)
    private val XML_GROUP_ID_REGEX = Regex("""\bgroup_id="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val HAS_FILAMENT_SWITCHER_TRUE = Regex(
        """key="has_filament_switcher"\s+value="true"""",
        RegexOption.IGNORE_CASE,
    )
    private val POSITIVE_EXTRUSION_WORD = Regex(
        """(?:^|\s)E(?:\+)?(?:0*[1-9]\d*(?:\.\d*)?|0*\.0*[1-9]\d*)(?=\s|$)""",
        RegexOption.IGNORE_CASE,
    )
    private const val DYNAMIC_NOZZLE = 0
    private const val NOZZLE_DIAMETER_TOLERANCE = 0.05f
    private const val LEFT_EXTERNAL_ROUTE = 254
    private const val RIGHT_EXTERNAL_ROUTE = 255
}
