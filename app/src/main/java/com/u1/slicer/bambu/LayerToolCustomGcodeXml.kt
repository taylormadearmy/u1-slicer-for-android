package com.u1.slicer.bambu

/**
 * Parses Bambu/Orca `Metadata/custom_gcode_per_layer.xml` for layer-based tool/colour changes.
 * Used by [ThreeMfParser] for colour detection; [com.u1.slicer.gcode.LayerToolPauseInjector] uses the
 * same layer `type` rules for pause insertion — keep them aligned.
 */
internal data class LayerToolCustomGcodeXmlInfo(
    val hasToolChanges: Boolean,
    val colors: List<String>,
    val extruders: Set<Int>
)

internal fun parseLayerToolCustomGcodeXml(xml: String): LayerToolCustomGcodeXmlInfo {
    val colors = linkedSetOf<String>()
    val extruders = linkedSetOf<Int>()
    val layerRegex = Regex("""<layer\b([^>]*)>""")
    val typeRegex = Regex("""\btype="([^"]+)"""")
    val extruderRegex = Regex("""\bextruder="([^"]+)"""")
    val colorRegex = Regex("""\bcolor="([^"]+)"""")
    var hasToolChanges = false

    layerRegex.findAll(xml).forEach { match ->
        val attrs = match.groupValues[1]
        val type = typeRegex.find(attrs)?.groupValues?.getOrNull(1)
        if (type == "1" || type == "2") {
            hasToolChanges = true
            extruderRegex.find(attrs)?.groupValues?.getOrNull(1)
                ?.toIntOrNull()
                ?.let { extruders.add(it) }
            colorRegex.find(attrs)?.groupValues?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
                ?.let { colors.add(it.take(7)) }
        }
    }

    return LayerToolCustomGcodeXmlInfo(hasToolChanges, colors.toList(), extruders)
}

/**
 * Parses `custom_gcode_per_layer.xml` per plate. Each `<plate>` block starts with a
 * `<plate_info id="N"/>` element; subsequent `<layer>` entries belong to that plate.
 * Returns a map from 1-based plate ID → LayerToolCustomGcodeXmlInfo.
 */
internal fun parseLayerToolCustomGcodeXmlPerPlate(xml: String): Map<Int, LayerToolCustomGcodeXmlInfo> {
    val result = mutableMapOf<Int, LayerToolCustomGcodeXmlInfo>()
    val plateInfoIdRegex = Regex("""<plate_info\b[^>]*\bid="(\d+)"""")
    for (section in xml.split("<plate>").drop(1)) {
        val content = section.substringBefore("</plate>")
        val plateId = plateInfoIdRegex.find(content)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: continue
        result[plateId] = parseLayerToolCustomGcodeXml(content)
    }
    return result
}

/**
 * Parses `custom_gcode_per_layer.xml` into an ordered list of Z→extruder segments.
 * Each entry represents "from this Z and up, use this extruder" (last entry with topZ ≤ triangle Z wins).
 * extruderBambu is 1-based (1→T0, 2→T1). Entries are sorted ascending by topZ.
 * Returns empty list if no type-1 or type-2 layers are found.
 */
internal fun parseLayerToolSegments(xml: String): List<LayerToolSegment> {
    val layerRegex = Regex("""<layer\b([^>]*)>""")
    val typeRegex = Regex("""\btype="([^"]+)"""")
    val topZRegex = Regex("""\btop_z="([^"]+)"""")
    val extruderRegex = Regex("""\bextruder="([^"]+)"""")

    return layerRegex.findAll(xml).mapNotNull { match ->
        val attrs = match.groupValues[1]
        val type = typeRegex.find(attrs)?.groupValues?.getOrNull(1)
        if (type != "1" && type != "2") return@mapNotNull null
        val topZ = topZRegex.find(attrs)?.groupValues?.getOrNull(1)?.toFloatOrNull()
            ?: return@mapNotNull null
        val extruder = extruderRegex.find(attrs)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
        LayerToolSegment(topZ = topZ, extruderBambu = extruder)
    }.sortedBy { it.topZ }.toList()
}
