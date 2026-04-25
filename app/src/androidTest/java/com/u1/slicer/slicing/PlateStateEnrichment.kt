package com.u1.slicer.slicing

import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.NativePlateState
import com.u1.slicer.bambu.ThreeMfInfo

/**
 * Mirrors `SlicerViewModel.buildThreeMfInfoFromNative`'s extruder enrichment
 * for tests. The production UI sees the union of:
 *  - native usedExtruders (per-volume extruder reported by g_model);
 *  - source plate's per-part extruders (from `objectPartExtruders`,
 *    populated by ThreeMfParser for compound objects whose `<part>`
 *    children carry distinct extruder metadata);
 *  - paint extruder states (from `nativeGetPaintStateCounts` for MMU-painted
 *    volumes — native usedExtruders alone may miss states encoded only as
 *    paint data, e.g. SEMM single-object multi-paint plates);
 *  - layer-tool extruders (from custom_gcode_per_layer.xml, only available
 *    in the source file metadata, not in g_model).
 *
 * @param plateIndex0Based 0-based plate index (matches the embed plateId
 *   convention). Production `ThreeMfPlate.plateId` is 1-based, so the lookup
 *   adds 1.
 */
@Suppress("DEPRECATION")
internal fun enrichedUsedExtruders(
    lib: NativeLibrary,
    info: ThreeMfInfo,
    nativeState: NativePlateState,
    plateIndex0Based: Int?
): Set<Int> {
    val sourcePlate = plateIndex0Based?.let { idx ->
        info.plates.firstOrNull { it.plateId == idx + 1 }
    }
    val perPartExtruders = sourcePlate?.objectIds.orEmpty().flatMap { id ->
        val perPart = info.objectPartExtruders[id]
        if (!perPart.isNullOrEmpty()) perPart else listOfNotNull(info.objectExtruderMap[id])
    }.filter { it > 0 }.toSet()

    val paintExtruders = mutableSetOf<Int>()
    if (nativeState.hasPaintData) {
        for (obj in nativeState.objects) {
            for (vol in obj.volumes) {
                if (vol.isMmPainted) {
                    val counts = lib.nativeGetPaintStateCounts(obj.objectIndex, vol.volumeIndex, 0)
                        ?: continue
                    for (k in counts.indices step 2) {
                        val state = counts[k]
                        if (state > 0 && counts[k + 1] > 0) paintExtruders.add(state)
                    }
                }
            }
        }
    }

    val layerToolExtruders = sourcePlate?.layerToolExtruders.orEmpty()
        .filter { it > 0 }.toSet()

    return (nativeState.usedExtruders + perPartExtruders + paintExtruders + layerToolExtruders)
        .filter { it > 0 }.toSortedSet()
}
