package com.u1.slicer.bambu

import com.u1.slicer.data.BambuConfig
import com.u1.slicer.data.SliceConfig

/** Bambu local slicing currently uses one diameter across all participating hotends. */
object BambuNozzlePolicy {
    fun validate(config: Map<String, Any>, diameter: Float) {
        com.u1.slicer.machine.NozzleProcessPolicy.validate(config, diameter)
        if (diameter == 0.4f) return
        for (key in listOf("layer_height", "initial_layer_print_height")) {
            val height = config[key]?.toString()?.toFloatOrNull() ?: continue
            require(height >= diameter * 0.2f - 0.00001f && height <= diameter * 0.7f + 0.00001f) {
                "$key is outside the upstream profile limits for the $diameter mm nozzle. Choose a compatible process and slice again."
            }
        }
    }

    fun baseConfig(base: SliceConfig, diameter: Float): SliceConfig {
        require(diameter in BambuConfig.SUPPORTED_NOZZLE_DIAMETERS) { "Unsupported Bambu nozzle diameter: $diameter" }
        return if (diameter == 0.4f) base.copy(nozzleDiameter = diameter) else base.copy(
            nozzleDiameter = diameter,
            layerHeight = diameter / 2,
            firstLayerHeight = diameter / 2,
        )
    }
}
