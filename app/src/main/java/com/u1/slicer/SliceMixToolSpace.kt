package com.u1.slicer

internal data class SliceMixToolSpaceDecision(
    val anyMixAssigned: Boolean,
    val mixToolSpace: Boolean,
    val mixPhysicalBase: Int,
)

/**
 * Decide whether the current slice needs mix-tool space.
 *
 * Painted full-spectrum files must stay in mix-tool space even when the current
 * project has zero active mix rows. Object-assigned mixes still require active
 * rows because the assignment itself comes from the active mix table.
 */
internal fun decideSliceMixToolSpace(
    numPhysical: Int,
    canonicalCount: Int,
    hasActiveMixRows: Boolean,
    objectMixAssigned: Boolean,
    paintedMixAssigned: Boolean,
): SliceMixToolSpaceDecision {
    val anyMixAssigned = paintedMixAssigned || (hasActiveMixRows && objectMixAssigned)
    val mixPhysicalBase = if (anyMixAssigned) maxOf(numPhysical, canonicalCount) else 0
    return SliceMixToolSpaceDecision(
        anyMixAssigned = anyMixAssigned,
        mixToolSpace = anyMixAssigned,
        mixPhysicalBase = mixPhysicalBase,
    )
}
