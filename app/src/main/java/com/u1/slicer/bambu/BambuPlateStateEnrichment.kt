package com.u1.slicer.bambu

import com.u1.slicer.NativeLibrary

/**
 * Single source of truth for "what extruders does this plate actually use".
 *
 * Both `SlicerViewModel.buildThreeMfInfoFromNative` (production UI) and the
 * androidTest helper `enrichedUsedExtruders` (Tier A regression + Tier B
 * fixture harness) call this to collapse five upstream signals into a single
 * physical-slot set:
 *
 *   1. native usedExtruders (per-volume extruder reported by `g_model`)
 *   2. per-part extruders from `objectPartExtruders` (compound objects whose
 *      `<part>` children carry distinct extruder metadata)
 *   3. paint extruder states from `nativeGetPaintStateCounts` — probed on
 *      every volume of every object on the plate, NOT just `isMmPainted` ones.
 *      SEMM-painted plates whose paint data is encoded on individual triangles
 *      have volumes that come back with `isMmPainted=false` but still produce
 *      non-empty paint state counts when queried (slip-slide-spin plate 3 was
 *      the canary, reporting `usedExtruders={1}` and gating the probe on
 *      `isMmPainted` gave only 2 of 4 paint regions back).
 *   4. layer-tool extruders from `custom_gcode_per_layer.xml` (only available
 *      in source-file metadata, not in `g_model`)
 *   5. per-plate filament indices from `model_settings.config` (when layer-
 *      tool changes are present)
 *
 * The combined set is then folded onto the four physical Snapmaker U1
 * extruder slots via `((state - 1) % 4) + 1`. The slicer's post-process
 * G-code remap does the same fold on AMS2 indices (5..8) and B95-style high
 * indices (9..15) — without the fold, files like `colored_3DBenchy` report
 * 9 distinct paint states (1, 2, 3, 5..10) even though the user-visible
 * result is only 4 physical tools.
 *
 * The pre-Phase-1 production path probed `nativeGetPaintStateCounts` only
 * when both `parsed.hasPaintData` and `vol.isMmPainted` were true, and never
 * folded. The androidTest helper had been doing the unconditional probe and
 * the fold for a while; the canary slip-slide-spin demonstrated the test
 * helper's version was the correct one. Phase 1 reconciles the two by
 * routing both through this helper.
 */
internal object BambuPlateStateEnrichment {

    /**
     * Compute the union of plate extruder signals folded onto the four
     * physical Snapmaker U1 slots, sorted ascending. Only indices > 0 are
     * returned (state 0 = unpainted, never a physical slot).
     *
     * @param state Native plate state from `nativeGetAllVolumeExtruders`.
     * @param sourcePlate The matching `ThreeMfPlate` from the file-level
     *   parse, used for per-part / layer-tool / filament-index signals.
     *   `null` when the file has no plates list (single-plate STL, etc.) —
     *   the helper falls back to native + paint probe only.
     * @param fileInfo The file-level `ThreeMfInfo` carrying
     *   `objectPartExtruders` and `objectExtruderMap` for per-part lookup.
     * @param lib The `NativeLibrary` to probe `nativeGetPaintStateCounts`
     *   on every volume of every object in [state].
     */
    @Suppress("DEPRECATION")
    fun enrich(
        state: NativePlateState,
        sourcePlate: ThreeMfPlate?,
        fileInfo: ThreeMfInfo,
        lib: NativeLibrary
    ): Set<Int> {
        val perPartExtruders = sourcePlate?.objectIds.orEmpty().flatMap { id ->
            val perPart = fileInfo.objectPartExtruders[id]
            if (!perPart.isNullOrEmpty()) perPart else listOfNotNull(fileInfo.objectExtruderMap[id])
        }.filter { it > 0 }.toSet()

        val paintExtruders = mutableSetOf<Int>()
        for (obj in state.objects) {
            for (vol in obj.volumes) {
                val counts = lib.nativeGetPaintStateCounts(obj.objectIndex, vol.volumeIndex, 0)
                    ?: continue
                for (k in counts.indices step 2) {
                    if (k + 1 >= counts.size) break
                    val paintState = counts[k]
                    if (paintState > 0 && counts[k + 1] > 0) paintExtruders.add(paintState)
                }
            }
        }

        val layerToolExtruders = sourcePlate?.layerToolExtruders.orEmpty()
            .filter { it > 0 }.toSet()
        val filamentIndices = sourcePlate?.filamentIndices.orEmpty()
            .filter { it > 0 }.toSet()

        val foldedNativeUsed = state.usedExtruders.map(::foldToPhysicalSlot).toSet()
        val foldedPerPart = perPartExtruders.map(::foldToPhysicalSlot).toSet()
        val foldedPaint = paintExtruders.map(::foldToPhysicalSlot).toSet()
        val foldedLayerTool = layerToolExtruders.map(::foldToPhysicalSlot).toSet()
        val foldedFilamentIndices = filamentIndices.map(::foldToPhysicalSlot).toSet()

        return (foldedNativeUsed + foldedPerPart + foldedPaint + foldedLayerTool + foldedFilamentIndices)
            .filter { it > 0 }.toSortedSet()
    }

    /**
     * Fold extended paint states (AMS2 indices 5..8, B95 high indices 9..15)
     * onto the four physical Snapmaker U1 extruder slots. The slicer's
     * post-process G-code remap does the same fold via `((state-1) % 4) + 1`
     * (see `ThreeMfParser`'s paint state count loop).
     */
    fun foldToPhysicalSlot(state: Int): Int =
        if (state > 4) ((state - 1) % 4) + 1 else state
}
