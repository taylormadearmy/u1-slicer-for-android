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

    fun defaults(diameter: Float): Map<String, Any> = NozzleProcessPolicy.defaults(diameter)

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

    fun validate(config: Map<String, Any>, diameter: Float) = NozzleProcessPolicy.validate(config, diameter)

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

    private fun decimal(value: Float) = String.format(Locale.ROOT, "%.4f", value).trimEnd('0').trimEnd('.')
}
