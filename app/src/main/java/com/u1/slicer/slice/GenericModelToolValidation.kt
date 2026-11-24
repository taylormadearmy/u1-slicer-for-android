package com.u1.slicer.slice

import com.u1.slicer.bambu.ThreeMfInfo

/** Validates source assignments and returns the source-to-physical mapping for a sole filament. */
internal fun validateGenericModelTools(info: ThreeMfInfo, extruderCount: Int, plateId: Int? = null): Map<Int, Int> {
    val plate = plateId?.let { id ->
        requireNotNull(info.plates.firstOrNull { it.plateId == id }) { "The selected plate is not available in the project." }
    }
    val tools = if (plate == null) (info.usedExtruderIndices + info.volumeExtruders)
        .map { if (it > 0) it else 1 }.toSet().ifEmpty { setOf(1) } else {
        val objectTools = plate.objectIds.flatMap { id ->
            val defaultTool = info.objectExtruderMap[id]?.takeIf { it > 0 } ?: 1
            info.objectPartExtruders[id]?.takeIf { it.isNotEmpty() }
                ?.map { if (it > 0) it else defaultTool }
                ?: listOf(defaultTool)
        }.filter { it > 0 }.toSet()
        (objectTools + plate.paintExtruderStates + plate.layerToolExtruders)
            .filter { it > 0 }.toSet().ifEmpty { setOf(1) }
    }
    require(!(plate?.hasLayerToolChanges ?: info.hasLayerToolChanges)) {
        "The model references tools not available on the selected machine."
    }
    require(extruderCount > 1 || (!(plate?.hasPaintData ?: info.hasPaintData) && tools.size <= 1 &&
        plate?.filamentMapSlots.orEmpty().filter { it > 0 }.size <= 1)) {
        "Single-nozzle machines require an unpainted single-filament model."
    }
    if (extruderCount == 1) return mapOf((tools.singleOrNull() ?: 1) to 1)
    require((tools + plate?.filamentMapSlots.orEmpty()).all { it <= extruderCount }) {
        "The model references tools not available on the selected machine."
    }
    return emptyMap()
}
