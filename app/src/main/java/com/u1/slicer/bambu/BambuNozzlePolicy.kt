package com.u1.slicer.bambu

import com.u1.slicer.data.BambuConfig
import com.u1.slicer.data.SliceConfig

/** Bambu local slicing currently uses one diameter across all participating hotends. */
object BambuNozzlePolicy {
    fun baseConfig(base: SliceConfig, diameter: Float): SliceConfig {
        require(diameter in BambuConfig.SUPPORTED_NOZZLE_DIAMETERS) { "Unsupported Bambu nozzle diameter: $diameter" }
        return if (diameter == 0.4f) base.copy(nozzleDiameter = diameter) else base.copy(
            nozzleDiameter = diameter,
            layerHeight = diameter / 2,
            firstLayerHeight = diameter / 2,
        )
    }
}
