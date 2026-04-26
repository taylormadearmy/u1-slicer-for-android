package com.u1.slicer.slicing

import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.BambuPlateStateEnrichment
import com.u1.slicer.bambu.NativePlateState
import com.u1.slicer.bambu.ThreeMfInfo

/**
 * Test-side adapter for `BambuPlateStateEnrichment.enrich`. Resolves the
 * 0-based `plateIndex` argument to the source plate's 1-based `plateId` so
 * tests don't have to do that arithmetic at every call site.
 *
 * The enrichment logic itself lives in production
 * `com.u1.slicer.bambu.BambuPlateStateEnrichment` and is shared with
 * `SlicerViewModel.buildThreeMfInfoFromNative`. Pre-Phase-1 this file used
 * to host its own copy of the union/probe/fold logic; that drift was the
 * point of the chunk-3 BACKLOG entry.
 */
internal fun enrichedUsedExtruders(
    lib: NativeLibrary,
    info: ThreeMfInfo,
    nativeState: NativePlateState,
    plateIndex0Based: Int?
): Set<Int> {
    val sourcePlate = plateIndex0Based?.let { idx ->
        info.plates.firstOrNull { it.plateId == idx + 1 }
    }
    return BambuPlateStateEnrichment.enrich(
        state = nativeState,
        sourcePlate = sourcePlate,
        fileInfo = info,
        lib = lib
    )
}
