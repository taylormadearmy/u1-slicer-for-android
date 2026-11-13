package com.u1.slicer.machine

import java.util.Locale

/** Nozzle geometry defaults and process compatibility shared across machine families. */
object NozzleProcessPolicy {
    private val widthMultipliers = mapOf(
        "line_width" to 1.125f,
        "outer_wall_line_width" to 1.05f,
        "inner_wall_line_width" to 1.125f,
        "top_surface_line_width" to 1.05f,
        "sparse_infill_line_width" to 1.125f,
        "internal_solid_infill_line_width" to 1.125f,
        "initial_layer_line_width" to 1.25f,
        "support_line_width" to 1.125f,
    )

    fun defaults(diameter: Float): Map<String, Any> {
        requireSupported(diameter)
        return buildMap {
            put("layer_height", decimal(diameter / 2))
            put("initial_layer_print_height", decimal(diameter / 2))
            widthMultipliers.forEach { (key, multiplier) -> put(key, decimal(diameter * multiplier)) }
        }
    }

    fun validate(config: Map<String, Any>, diameter: Float) {
        requireSupported(diameter)
        val maximumHeight = diameter * 0.8f
        for (key in listOf("layer_height", "initial_layer_print_height")) {
            val value = config[key] ?: continue
            val height = number(value, key)
            require(height > 0f && height <= maximumHeight + 0.00001f) {
                "$key must be greater than 0 and at most ${decimal(maximumHeight)} mm for a ${decimal(diameter)} mm nozzle. Choose a compatible process or override the layer height."
            }
        }
        val layerHeight = config["layer_height"]?.let { number(it, "layer_height") } ?: diameter / 2
        val firstHeight = config["initial_layer_print_height"]?.let { number(it, "initial_layer_print_height") } ?: layerHeight
        for (key in widthMultipliers.keys) {
            val raw = config[key] ?: continue
            val text = raw.toString().trim()
            val width = if (text.endsWith('%')) number(text.dropLast(1), key) * diameter / 100 else number(raw, key)
            val height = if (key == "initial_layer_line_width") firstHeight else layerHeight
            require(width == 0f || (width > height && width <= diameter * 2f + 0.00001f)) {
                "$key is incompatible with the ${decimal(diameter)} mm nozzle and layer height. Choose a compatible process or line width."
            }
        }
    }

    private fun requireSupported(diameter: Float) = require(diameter in com.u1.slicer.data.BambuConfig.SUPPORTED_NOZZLE_DIAMETERS) {
        "Nozzle diameter must be 0.2, 0.4, 0.6, or 0.8 mm"
    }

    private fun number(value: Any, key: String): Float = value.toString().toFloatOrNull()
        ?.takeIf { it.isFinite() } ?: throw IllegalArgumentException("Invalid $key: $value")

    private fun decimal(value: Float) = String.format(Locale.ROOT, "%.4f", value).trimEnd('0').trimEnd('.')
}
