package com.u1.slicer.bambu

import kotlin.math.abs

internal data class BambuPrintBounds(
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double,
) {
    val width: Double get() = maxX - minX
    val depth: Double get() = maxY - minY
    val centreX: Double get() = (minX + maxX) / 2.0
    val centreY: Double get() = (minY + maxY) / 2.0

    fun union(other: BambuPrintBounds): BambuPrintBounds = BambuPrintBounds(
        minX = minOf(minX, other.minX),
        minY = minOf(minY, other.minY),
        maxX = maxOf(maxX, other.maxX),
        maxY = maxOf(maxY, other.maxY),
    )
}

internal data class BambuGcodeProjectMetadata(
    val predictionSeconds: Int = 0,
    val filamentUsedMetres: List<Double> = emptyList(),
    val filamentUsedGrams: List<Double> = emptyList(),
    val maxZ: Double = 0.0,
    val layerHeight: Double = 0.2,
    val objectBounds: BambuPrintBounds? = null,
    val plateBounds: BambuPrintBounds? = null,
    val objectArea: Double = 0.0,
    val bedType: String = "auto",
    val supportUsed: Boolean = false,
) {
    val totalWeightGrams: Double get() = filamentUsedGrams.sum()
}

internal object BambuGcodeProjectMetadataParser {
    private val number = "-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)"
    private val pointRegex = Regex("\\[\\s*($number)\\s*,\\s*($number)\\s*]")
    private val xRegex = Regex("(?:^|\\s)X($number)(?=\\s|$)")
    private val yRegex = Regex("(?:^|\\s)Y($number)(?=\\s|$)")
    private val eRegex = Regex("(?:^|\\s)E($number)(?=\\s|$)")

    fun parse(
        gcode: String,
        printableBounds: BambuPrintBounds = BambuPrintBounds(0.0, 0.0, 180.0, 180.0),
    ): BambuGcodeProjectMetadata {
        val config = mutableMapOf<String, String>()
        val polygons = mutableListOf<List<Pair<Double, Double>>>()
        val firstLayerPoints = mutableListOf<Pair<Double, Double>>()
        var inConfigBlock = false
        var layerChanges = 0
        var x: Double? = null
        var y: Double? = null
        var predictionText: String? = null
        var filamentUsedMillimetres: String? = null
        var filamentUsedGrams: String? = null
        var maxZ = 0.0

        gcode.lineSequence().forEach { line ->
            when (line) {
                "; CONFIG_BLOCK_START" -> inConfigBlock = true
                "; CONFIG_BLOCK_END" -> inConfigBlock = false
                ";LAYER_CHANGE" -> layerChanges++
                else -> {
                    if (inConfigBlock && line.startsWith("; ")) {
                        val body = line.removePrefix("; ")
                        val separator = body.indexOf(" = ")
                        if (separator > 0) config[body.substring(0, separator)] = body.substring(separator + 3)
                    }
                }
            }
            parseExcludeObjectPolygon(line)?.let(polygons::add)
            when {
                line.startsWith("; max_z_height:") ->
                    maxZ = line.substringAfter(':', "").trim().toDoubleOrNull() ?: maxZ
                line.startsWith("; filament used [mm]") ->
                    filamentUsedMillimetres = line.substringAfter('=', "").trim()
                line.startsWith("; filament used [g]") ->
                    filamentUsedGrams = line.substringAfter('=', "").trim()
                line.startsWith("; estimated printing time (normal mode)") ->
                    predictionText = line.substringAfter('=', "").trim()
            }

            if (layerChanges == 1 && (line.startsWith("G0 ") || line.startsWith("G1 "))) {
                val previousX = x
                val previousY = y
                xRegex.find(line)?.groupValues?.get(1)?.toDoubleOrNull()?.let { x = it }
                yRegex.find(line)?.groupValues?.get(1)?.toDoubleOrNull()?.let { y = it }
                val extrusion = eRegex.find(line)?.groupValues?.get(1)?.toDoubleOrNull()
                if (extrusion != null && extrusion > 0.0) {
                    if (previousX != null && previousY != null &&
                        previousX in printableBounds.minX..printableBounds.maxX &&
                        previousY in printableBounds.minY..printableBounds.maxY
                    ) {
                        firstLayerPoints += previousX to previousY
                    }
                    val px = x
                    val py = y
                    if (px != null && py != null &&
                        px in printableBounds.minX..printableBounds.maxX &&
                        py in printableBounds.minY..printableBounds.maxY
                    ) {
                        firstLayerPoints += px to py
                    }
                }
            }
        }

        val objectBounds = polygons.map(::boundsOf).reduceOrNull(BambuPrintBounds::union)
        val firstLayerBounds = firstLayerPoints.takeIf { it.size >= 2 }?.let(::boundsOf)
        val plateBounds = listOfNotNull(objectBounds, firstLayerBounds)
            .reduceOrNull(BambuPrintBounds::union)

        return BambuGcodeProjectMetadata(
            predictionSeconds = parseDurationSeconds(predictionText),
            filamentUsedMetres = parseNumberList(filamentUsedMillimetres)
                .map { it / 1_000.0 },
            filamentUsedGrams = parseNumberList(filamentUsedGrams),
            maxZ = maxZ,
            layerHeight = config["layer_height"]?.toDoubleOrNull() ?: 0.2,
            objectBounds = objectBounds,
            plateBounds = plateBounds,
            objectArea = polygons.sumOf(::polygonArea),
            bedType = normalizeBedType(config["curr_bed_type"]),
            supportUsed = config["enable_support"] == "1",
        )
    }

    private fun parseExcludeObjectPolygon(line: String): List<Pair<Double, Double>>? {
        if (!line.startsWith("EXCLUDE_OBJECT_DEFINE ") || !line.contains(" POLYGON=")) return null
        val polygon = line.substringAfter(" POLYGON=")
        return pointRegex.findAll(polygon).map { match ->
            match.groupValues[1].toDouble() to match.groupValues[2].toDouble()
        }.toList().takeIf { it.size >= 3 }
    }

    private fun boundsOf(points: List<Pair<Double, Double>>): BambuPrintBounds = BambuPrintBounds(
        minX = points.minOf { it.first },
        minY = points.minOf { it.second },
        maxX = points.maxOf { it.first },
        maxY = points.maxOf { it.second },
    )

    private fun polygonArea(points: List<Pair<Double, Double>>): Double {
        var twiceArea = 0.0
        points.indices.forEach { index ->
            val current = points[index]
            val next = points[(index + 1) % points.size]
            twiceArea += current.first * next.second - next.first * current.second
        }
        return abs(twiceArea) / 2.0
    }

    private fun parseNumberList(value: String?): List<Double> = value
        ?.split(',')
        ?.mapNotNull { it.trim().toDoubleOrNull() }
        ?: emptyList()

    private fun parseDurationSeconds(value: String?): Int {
        if (value.isNullOrBlank()) return 0
        val days = Regex("(\\d+)d").find(value)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val hours = Regex("(\\d+)h").find(value)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = Regex("(\\d+)m").find(value)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val seconds = Regex("(\\d+)s").find(value)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return days * 86_400 + hours * 3_600 + minutes * 60 + seconds
    }

    private fun normalizeBedType(value: String?): String = when {
        value.isNullOrBlank() -> "auto"
        value.contains("textured", ignoreCase = true) -> "textured_plate"
        value.contains("cool", ignoreCase = true) -> "cool_plate"
        value.contains("engineering", ignoreCase = true) -> "eng_plate"
        value.contains("high temp", ignoreCase = true) -> "hot_plate"
        else -> "auto"
    }
}
