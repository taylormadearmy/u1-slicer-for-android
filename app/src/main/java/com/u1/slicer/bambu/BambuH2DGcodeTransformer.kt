package com.u1.slicer.bambu

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Bridges the bundled single-physical-extruder Orca core to H2D firmware.
 *
 * Logical tool ids remain project filament ids. Current H2D firmware also
 * requires an explicit physical hotend (`H0`/`H1`) on material and tool
 * selection commands, so this pass adds it from the one-based filament map.
 * It then rejects any sliced extrusion outside that nozzle's reachable area.
 */
internal object BambuH2DGcodeTransformer {
    private val toolCommand = Regex("""^(\s*T)(\d+)(\s*(?:;.*)?)$""")
    private val materialCommand = Regex("""^(\s*M62[01]\s+S)(\d+)(A\b)(.*)$""")
    private val extrusionWord = Regex("""\s+E-?(?:\d+(?:\.\d*)?|\.\d+)(?=\s|$)""")
    private val motionCommand = Regex("""^G[0123](?:\s|$)""", RegexOption.IGNORE_CASE)
    private val standalonePositiveExtrusion = Regex(
        """\s*G1\s+E(?:\+)?(?:\d+(?:\.\d*)?|\.\d+)(?:\s+F\d+(?:\.\d*)?)?\s*(?:;.*)?""",
    )
    private const val MAX_TOWER_FILAMENT_PER_MM = 0.15

    fun transformCopy(
        source: File,
        output: File,
        filamentMap: List<Int>,
        profile: BambuMachineProfile = BAMBU_MACHINE_PROFILES.getValue(
            com.u1.slicer.slice.SlicerTarget.BambuH2D,
        ),
    ) {
        require(filamentMap.isNotEmpty()) { "H2D requires at least one filament-to-nozzle assignment" }
        require(filamentMap.all { it in 1..profile.nozzlePrintableAreas.size }) {
            "H2D filament map must contain one-based physical nozzle ids"
        }
        val transformed = transform(source.readText(StandardCharsets.UTF_8), filamentMap, profile)
        output.parentFile?.mkdirs()
        output.writeText(transformed, StandardCharsets.UTF_8)
    }

    internal fun transform(
        source: String,
        filamentMap: List<Int>,
        profile: BambuMachineProfile = BAMBU_MACHINE_PROFILES.getValue(
            com.u1.slicer.slice.SlicerTarget.BambuH2D,
        ),
    ): String {
        val mapped = source
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .map { line -> addHotendArgument(line, filamentMap) }
            .toMutableList()

        removeLegacySingleHotendLoad(mapped)

        upsertConfig(mapped, "filament_map", filamentMap.joinToString(","))
        upsertConfig(mapped, "nozzle_diameter", profile.nozzleDiameters.joinToString(",") { it.toString() })
        if (profile.nozzleTypes.isNotEmpty()) {
            upsertConfig(mapped, "nozzle_type", profile.nozzleTypes.joinToString(","))
        }
        if (profile.nozzleVolumes.isNotEmpty()) {
            upsertConfig(mapped, "nozzle_volume", profile.nozzleVolumes.joinToString(","))
        }
        if (profile.printerExtruderIds.isNotEmpty()) {
            upsertConfig(mapped, "printer_extruder_id", profile.printerExtruderIds.joinToString(","))
        }
        if (profile.printerExtruderVariants.isNotEmpty()) {
            upsertConfig(mapped, "printer_extruder_variant", profile.printerExtruderVariants.joinToString(","))
        }
        upsertConfig(mapped, "extruder_type", List(profile.nozzleDiameters.size) { "Direct Drive" }.joinToString(","))
        upsertConfig(mapped, "nozzle_volume_type", List(profile.nozzleDiameters.size) { "Standard" }.joinToString(","))
        upsertConfig(mapped, "default_nozzle_volume_type", List(profile.nozzleDiameters.size) { "Standard" }.joinToString(","))
        upsertConfig(mapped, "physical_extruder_map", "1,0")

        validateTowerExtrusionDensity(mapped)
        validateNozzleReach(mapped, filamentMap, profile)
        return mapped.joinToString("\n").trimEnd() + "\n"
    }

    private fun upsertConfig(lines: MutableList<String>, key: String, value: String) {
        val replacement = "; $key = $value"
        val existing = lines.indexOfFirst { it.startsWith("; $key = ") }
        if (existing >= 0) {
            lines[existing] = replacement
            return
        }
        val configEnd = lines.indexOfFirst { it == "; CONFIG_BLOCK_END" }
        if (configEnd >= 0) lines.add(configEnd, replacement)
    }

    /**
     * The bundled core emits a single-hotend MMU load stroke after Bambu's
     * M620/T/M621 sequence. H2D firmware has already loaded and chute-flushed
     * the selected physical hotend, so the legacy 63 mm + 9 mm tower load
     * creates a dangerous mound. Preserve positioning, but remove extrusion
     * until the ordinary tower wipe begins.
     */
    private fun removeLegacySingleHotendLoad(lines: MutableList<String>) {
        var inLegacyLoad = false
        lines.indices.forEach { index ->
            val line = lines[index]
            when {
                line.trim() == "; CP TOOLCHANGE LOAD" -> {
                    inLegacyLoad = true
                    // Orca places a standalone deretraction just before this
                    // marker; it belongs to the same already-completed load.
                    for (prior in index - 1 downTo (index - 5).coerceAtLeast(0)) {
                        if (standalonePositiveExtrusion.matches(lines[prior])) {
                            lines[prior] = "; H2D removed legacy single-hotend prime: ${lines[prior].trim()}"
                            break
                        }
                    }
                }
                line.trim() == "; CP TOOLCHANGE WIPE" -> inLegacyLoad = false
                inLegacyLoad && motionCommand.containsMatchIn(line.trimStart()) && extrusionWord.containsMatchIn(line) -> {
                    lines[index] = extrusionWord.replace(line, "").replace(Regex(" {2,}"), " ").trimEnd()
                }
            }
        }
    }

    /** Reject moving tower strokes grossly denser than a printable bead. */
    private fun validateTowerExtrusionDensity(lines: List<String>) {
        var inTower = false
        var absolutePosition = true
        var absoluteExtrusion = false
        var x: Double? = null
        var y: Double? = null
        var e = 0.0
        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.substringBefore(';').trim()
            when (rawLine.trim()) {
                "; WIPE_TOWER_START" -> inTower = true
                "; WIPE_TOWER_END" -> inTower = false
            }
            when (line) {
                "G90" -> absolutePosition = true
                "G91" -> absolutePosition = false
                "M82" -> absoluteExtrusion = true
                "M83" -> absoluteExtrusion = false
            }
            if (line.startsWith("G92")) {
                wordValue(line, 'E')?.let { e = it }
                return@forEachIndexed
            }
            if (!line.startsWith("G0 ") && !line.startsWith("G1 ")) return@forEachIndexed
            val oldX = x
            val oldY = y
            val xValue = wordValue(line, 'X')
            val yValue = wordValue(line, 'Y')
            if (xValue != null) x = if (absolutePosition || x == null) xValue else x!! + xValue
            if (yValue != null) y = if (absolutePosition || y == null) yValue else y!! + yValue
            val eValue = wordValue(line, 'E')
            val extrusion = eValue?.let { if (absoluteExtrusion) it - e else it }
            if (eValue != null) e = if (absoluteExtrusion) eValue else e + eValue
            if (!inTower || oldX == null || oldY == null || x == null || y == null) return@forEachIndexed
            if (extrusion == null) return@forEachIndexed
            if (extrusion <= 0.0) return@forEachIndexed
            val distance = kotlin.math.hypot(x!! - oldX, y!! - oldY)
            if (distance <= 0.01) return@forEachIndexed
            require(extrusion / distance <= MAX_TOWER_FILAMENT_PER_MM) {
                "H2D wipe tower line ${index + 1} extrudes ${"%.3f".format(java.util.Locale.US, extrusion)} mm " +
                    "of filament over ${"%.3f".format(java.util.Locale.US, distance)} mm of travel; " +
                    "refusing unsafe over-extrusion"
            }
        }
    }

    internal fun inferFilamentMap(
        source: String,
        filamentCount: Int,
        profile: BambuMachineProfile = BAMBU_MACHINE_PROFILES.getValue(
            com.u1.slicer.slice.SlicerTarget.BambuH2D,
        ),
    ): List<Int> = inferFilamentMapLines(
        lines = source.replace("\r\n", "\n").replace('\r', '\n').lineSequence(),
        filamentCount = filamentCount,
        profile = profile,
    )

    internal fun inferFilamentMap(
        source: File,
        filamentCount: Int,
        profile: BambuMachineProfile = BAMBU_MACHINE_PROFILES.getValue(
            com.u1.slicer.slice.SlicerTarget.BambuH2D,
        ),
    ): List<Int> = source.useLines { lines ->
        inferFilamentMapLines(lines, filamentCount, profile)
    }

    private fun inferFilamentMapLines(
        lines: Sequence<String>,
        filamentCount: Int,
        profile: BambuMachineProfile,
    ): List<Int> {
        require(filamentCount > 0)
        val pointsByFilament = List(filamentCount) { mutableListOf<Pair<Double, Double>>() }
        var absolutePosition = true
        var absoluteExtrusion = false
        var x: Double? = null
        var y: Double? = null
        var e = 0.0
        var activeFilament = 0
        var slicedLayersStarted = false

        lines.forEach { rawLine ->
            val line = rawLine.substringBefore(';').trim()
            when {
                rawLine == ";LAYER_CHANGE" -> slicedLayersStarted = true
                line == "G90" -> absolutePosition = true
                line == "G91" -> absolutePosition = false
                line == "M82" -> absoluteExtrusion = true
                line == "M83" -> absoluteExtrusion = false
                line.startsWith("G92") ->
                    wordValue(line, 'E')?.let { e = it }
                selectedToolIndex(line) != null -> {
                    val tool = selectedToolIndex(line)!!
                    if (tool in pointsByFilament.indices) activeFilament = tool
                }
                line.startsWith("G0 ") || line.startsWith("G1 ") ||
                    line.startsWith("G2 ") || line.startsWith("G3 ") -> {
                    val xValue = wordValue(line, 'X')
                    val yValue = wordValue(line, 'Y')
                    if (xValue != null) x = if (absolutePosition || x == null) xValue else x!! + xValue
                    if (yValue != null) y = if (absolutePosition || y == null) yValue else y!! + yValue
                    val eValue = wordValue(line, 'E')
                    val extruding = eValue != null && if (absoluteExtrusion) eValue > e else eValue > 0.0
                    if (eValue != null) e = if (absoluteExtrusion) eValue else e + eValue
                    if (slicedLayersStarted && extruding && (xValue != null || yValue != null)) {
                        x?.let { px -> y?.let { py -> pointsByFilament[activeFilament] += px to py } }
                    }
                }
            }
        }

        return pointsByFilament.mapIndexed { filament, points ->
            val preferred = if (filament % 2 == 0) profile.masterNozzle else 1 - profile.masterNozzle
            val fits = profile.nozzlePrintableAreas.indices.filter { nozzle ->
                val area = profile.nozzlePrintableAreas[nozzle]
                points.all { (px, py) ->
                    px in area.minX..area.maxX && py in area.minY..area.maxY
                }
            }
            require(fits.isNotEmpty()) {
                val bounds = points.fold(null as DoubleArray?) { current, (px, py) ->
                    current?.also {
                        it[0] = minOf(it[0], px)
                        it[1] = minOf(it[1], py)
                        it[2] = maxOf(it[2], px)
                        it[3] = maxOf(it[3], py)
                    } ?: doubleArrayOf(px, py, px, py)
                }
                "H2D filament ${filament + 1} has toolpaths that no single physical nozzle can reach" +
                    (bounds?.let { " (${it[0]}..${it[2]} x ${it[1]}..${it[3]} mm)" } ?: "")
            }
            ((preferred.takeIf(fits::contains) ?: fits.first()) + 1)
        }
    }

    private fun addHotendArgument(line: String, filamentMap: List<Int>): String {
        val trimmed = line.trimStart()
        if (trimmed.length >= 2 && trimmed[0] == 'T' && trimmed[1].isDigit()) {
            toolCommand.matchEntire(line)?.let { match ->
                val filament = match.groupValues[2].toInt()
                val hotend = filamentMap.getOrNull(filament)?.minus(1) ?: return line
                return "${match.groupValues[1]}$filament H$hotend${match.groupValues[3]}"
            }
        }
        if (trimmed.startsWith("M620 ") || trimmed.startsWith("M621 ")) {
            materialCommand.matchEntire(line)?.let { match ->
                val filament = match.groupValues[2].toInt()
                val hotend = filamentMap.getOrNull(filament)?.minus(1) ?: return line
                return "${match.groupValues[1]}$filament${match.groupValues[3]} H$hotend${match.groupValues[4]}"
            }
        }
        return line
    }

    private fun validateNozzleReach(
        lines: List<String>,
        filamentMap: List<Int>,
        profile: BambuMachineProfile,
    ) {
        var absolutePosition = true
        var absoluteExtrusion = false
        var x: Double? = null
        var y: Double? = null
        var e = 0.0
        var activeFilament = 0
        var slicedLayersStarted = false

        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.substringBefore(';').trim()
            when {
                rawLine == ";LAYER_CHANGE" -> slicedLayersStarted = true
                line == "G90" -> absolutePosition = true
                line == "G91" -> absolutePosition = false
                line == "M82" -> absoluteExtrusion = true
                line == "M83" -> absoluteExtrusion = false
                line.startsWith("G92") -> {
                    wordValue(line, 'E')?.let { e = it }
                }
                selectedToolIndex(line) != null -> {
                    val tool = selectedToolIndex(line)!!
                    if (tool in filamentMap.indices) activeFilament = tool
                }
                line.startsWith("G0 ") || line.startsWith("G1 ") ||
                    line.startsWith("G2 ") || line.startsWith("G3 ") -> {
                    val oldX = x
                    val oldY = y
                    val xValue = wordValue(line, 'X')
                    val yValue = wordValue(line, 'Y')
                    if (xValue != null) x = if (absolutePosition || x == null) xValue else x!! + xValue
                    if (yValue != null) y = if (absolutePosition || y == null) yValue else y!! + yValue

                    val eValue = wordValue(line, 'E')
                    val extruding = eValue != null && if (absoluteExtrusion) eValue > e else eValue > 0.0
                    if (eValue != null) e = if (absoluteExtrusion) eValue else e + eValue
                    if (!slicedLayersStarted || !extruding || xValue == null && yValue == null) return@forEachIndexed

                    val nozzleIndex = filamentMap.getOrElse(activeFilament) { profile.masterNozzle + 1 } - 1
                    val area = profile.nozzlePrintableAreas[nozzleIndex]
                    listOfNotNull(oldX?.let { px -> oldY?.let { py -> px to py } }, x?.let { px -> y?.let { py -> px to py } })
                        .forEach { (px, py) ->
                            require(px in area.minX..area.maxX && py in area.minY..area.maxY) {
                                "H2D filament ${activeFilament + 1} is assigned to nozzle ${nozzleIndex + 1}, " +
                                    "but line ${index + 1} extrudes at ($px, $py) outside its " +
                                    "${area.minX}..${area.maxX} x ${area.minY}..${area.maxY} mm reach"
                            }
                        }
                }
            }
        }
    }

    /** Allocation-light parser for the millions of coordinate words in large H2D jobs. */
    private fun wordValue(line: String, word: Char): Double? {
        var index = line.indexOf(word)
        while (index >= 0) {
            if (index == 0 || line[index - 1].isWhitespace()) {
                val start = index + 1
                var end = start
                while (end < line.length && !line[end].isWhitespace()) end++
                if (end > start) return line.substring(start, end).toDoubleOrNull()
            }
            index = line.indexOf(word, index + 1)
        }
        return null
    }

    private fun selectedToolIndex(line: String): Int? {
        val trimmed = line.trimStart()
        if (trimmed.length < 2 || trimmed[0] != 'T' || !trimmed[1].isDigit()) return null
        var end = 2
        while (end < trimmed.length && trimmed[end].isDigit()) end++
        return trimmed.substring(1, end).toIntOrNull()
    }
}

internal fun resolveH2DFilamentMap(
    sourceFilamentIndices: List<Int>,
    explicitNozzleAssignments: List<Int>,
    inferredNozzleAssignments: List<Int> = sourceFilamentIndices.map { sourceIndex ->
        if (sourceIndex % 2 == 0) 2 else 1
    },
): List<Int> {
    require(inferredNozzleAssignments.size >= sourceFilamentIndices.size) {
        "H2D automatic nozzle map does not cover every used filament"
    }
    val sourceWideAssignments = sourceFilamentIndices.maxOrNull()
        ?.let { maxSourceIndex -> explicitNozzleAssignments.size > maxSourceIndex }
        ?: false
    val mapping = sourceFilamentIndices.mapIndexed { compactIndex, sourceIndex ->
        val requested = if (sourceWideAssignments) {
            explicitNozzleAssignments.getOrNull(sourceIndex)
        } else {
            explicitNozzleAssignments.getOrNull(compactIndex)
        }
        when (requested) {
            null, 0 -> inferredNozzleAssignments[compactIndex]
            1, 2 -> requested
            else -> throw IllegalArgumentException(
                "H2D nozzle assignments must be 0 (auto), 1 (left), or 2 (right)",
            )
        }
    }
    require(mapping.all { it == 1 || it == 2 }) {
        "H2D nozzle assignments must be 1 (left) or 2 (right)"
    }
    return mapping
}
