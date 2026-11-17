package com.u1.slicer.slice

import com.u1.slicer.bambu.ThreeMfInfo

internal fun validateGenericModelTools(info: ThreeMfInfo, extruderCount: Int, plateId: Int? = null) {
    val plate = plateId?.let { id ->
        requireNotNull(info.plates.firstOrNull { it.plateId == id }) { "The selected plate is not available in the project." }
    }
    val tools = if (plate == null) info.usedExtruderIndices + info.volumeExtruders else {
        val objectTools = plate.objectIds.flatMap { id ->
            info.objectPartExtruders[id]?.takeIf { it.isNotEmpty() }
                ?: listOf(info.objectExtruderMap[id] ?: 1)
        }.filter { it > 0 }.toSet()
        (objectTools + plate.paintExtruderStates + plate.layerToolExtruders + plate.filamentMapSlots)
            .filter { it > 0 }.toSet().ifEmpty { setOf(1) }
    }
    require(!(plate?.hasLayerToolChanges ?: info.hasLayerToolChanges) && tools.all { it <= extruderCount }) {
        "The model references tools not available on the selected machine."
    }
    require(extruderCount > 1 || (!(plate?.hasPaintData ?: info.hasPaintData) && tools.size <= 1)) {
        "Single-nozzle machines require an unpainted single-filament model."
    }
}
