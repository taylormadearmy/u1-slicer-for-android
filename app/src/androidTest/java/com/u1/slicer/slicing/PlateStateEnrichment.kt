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

    // Probe nativeGetPaintStateCounts on EVERY volume of every object on the
    // plate, not just those flagged `isMmPainted`. SEMM-painted plates whose
    // paint data is encoded on individual triangles (rather than marked at
    // the volume level) have volumes that come back with `isMmPainted=false`
    // but still produce non-empty paint state counts when queried — slip-
    // slide-spin plate 3 was the canary, reporting `usedExtruders={1}` and
    // gating the paint probe on isMmPainted gave only 2 of 4 paint regions
    // back. Querying unconditionally and ignoring nulls/empties gives the
    // full set without false positives on non-painted volumes.
    val paintExtruders = mutableSetOf<Int>()
    for (obj in nativeState.objects) {
        for (vol in obj.volumes) {
            val counts = lib.nativeGetPaintStateCounts(obj.objectIndex, vol.volumeIndex, 0)
                ?: continue
            for (k in counts.indices step 2) {
                if (k + 1 >= counts.size) break
                val state = counts[k]
                if (state > 0 && counts[k + 1] > 0) paintExtruders.add(state)
            }
        }
    }

    val layerToolExtruders = sourcePlate?.layerToolExtruders.orEmpty()
        .filter { it > 0 }.toSet()

    // Fold extended paint states (AMS2 indices 5..8, B95 high indices 9..15)
    // onto the four physical Snapmaker U1 extruder slots. The slicer's
    // post-process G-code remap does the same fold via `((state-1) % 4) + 1`
    // (see ThreeMfParser's paintStateCount loop). Without folding, painted
    // files like colored_3DBenchy report 9 distinct paint states (1,2,3,5..10)
    // even though the user-visible result is only 4 physical tools.
    fun foldToPhysicalSlot(state: Int): Int =
        if (state > 4) ((state - 1) % 4) + 1 else state

    val foldedNativeUsed = nativeState.usedExtruders.map(::foldToPhysicalSlot).toSet()
    val foldedPerPart = perPartExtruders.map(::foldToPhysicalSlot).toSet()
    val foldedPaint = paintExtruders.map(::foldToPhysicalSlot).toSet()
    val foldedLayerTool = layerToolExtruders.map(::foldToPhysicalSlot).toSet()

    return (foldedNativeUsed + foldedPerPart + foldedPaint + foldedLayerTool)
        .filter { it > 0 }.toSortedSet()
}
