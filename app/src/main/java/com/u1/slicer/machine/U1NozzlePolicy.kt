package com.u1.slicer.machine

import java.util.Locale

/** Uniform nozzle diameter for all tools participating in a U1 slicing job. */
object U1NozzlePolicy {
    val supportedDiameters = com.u1.slicer.data.U1NozzleConfiguration.SUPPORTED_DIAMETERS_MM

    fun baseConfig(base: com.u1.slicer.data.SliceConfig, diameter: Float): com.u1.slicer.data.SliceConfig {
        requireSupported(diameter)
        return if (diameter == 0.4f) base.copy(nozzleDiameter = diameter) else base.copy(
            nozzleDiameter = diameter,
            layerHeight = diameter / 2,
            firstLayerHeight = diameter / 2,
        )
    }

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

    /** Replace the bundled/source 0.4 process baseline before explicit process overrides. */
    fun applyDefaults(config: MutableMap<String, Any>, diameter: Float, extruderCount: Int) {
        requireSupported(diameter)
        if (diameter != 0.4f) config.putAll(defaults(diameter))
        applyHardware(config, diameter, extruderCount)
    }

    /** Hardware belongs to the selected printer; imported process values must be compatible. */
    fun finalize(config: MutableMap<String, Any>, diameter: Float, extruderCount: Int) {
        applyHardware(config, diameter, extruderCount)
        validate(config, diameter)
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

    private fun applyHardware(config: MutableMap<String, Any>, diameter: Float, extruderCount: Int) {
        requireSupported(diameter)
        require(extruderCount > 0) { "U1 requires at least one slicing extruder" }
        val nozzle = decimal(diameter)
        val printerId = "Snapmaker U1 ($nozzle nozzle) - multiplate"
        config["nozzle_diameter"] = MutableList(extruderCount) { nozzle }
        if (diameter != 0.4f) {
            config["min_layer_height"] = MutableList(extruderCount) { decimal(diameter * 0.2f) }
            config["max_layer_height"] = MutableList(extruderCount) { decimal(diameter * 0.8f) }
        }
        config["printer_settings_id"] = printerId
        config["print_settings_id"] = String.format(Locale.ROOT, "%.2fmm Standard @Snapmaker U1", diameter / 2)
        config["print_compatible_printers"] = mutableListOf(printerId)
        config["compatible_printers"] = mutableListOf(printerId)
    }

    private fun requireSupported(diameter: Float) = require(diameter in supportedDiameters) {
        "U1 nozzle diameter must be 0.2, 0.4, 0.6, or 0.8 mm"
    }

    private fun number(value: Any, key: String): Float = value.toString().toFloatOrNull()
        ?.takeIf { it.isFinite() } ?: throw IllegalArgumentException("Invalid $key: $value")

    private fun decimal(value: Float) = String.format(Locale.ROOT, "%.4f", value).trimEnd('0').trimEnd('.')
}
