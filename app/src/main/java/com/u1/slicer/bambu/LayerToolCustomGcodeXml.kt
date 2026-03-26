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
