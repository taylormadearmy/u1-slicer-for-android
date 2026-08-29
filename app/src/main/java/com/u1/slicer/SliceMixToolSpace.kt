package com.u1.slicer

internal data class SliceMixToolSpaceDecision(
    val anyMixAssigned: Boolean,
    val mixToolSpace: Boolean,
    val mixPhysicalBase: Int,
)

/**
 * F99 stages canonical file colours into physical/mix tool space before the native model reload.
 * In that state a virtual mix id is no longer a canonical source colour and must not be remapped.
 */
internal fun shouldReplayCanonicalVolumeMapping(
    mixToolSpace: Boolean,
    hasPaintData: Boolean,
    canonicalColourRemapActive: Boolean,
): Boolean = mixToolSpace && !hasPaintData && !canonicalColourRemapActive

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
    canonicalColourRemapActive: Boolean = false,
): SliceMixToolSpaceDecision {
    val anyMixAssigned = paintedMixAssigned || (hasActiveMixRows && objectMixAssigned)
    // F99 stages every remapped source state into U1's fixed four-tool space.
    // That applies to direct physical mappings too: subsequent G-code already
    // contains physical T commands and must never receive the old canonical
    // print-time mapping a second time.
    val mixToolSpace = anyMixAssigned || canonicalColourRemapActive
    val mixPhysicalBase = when {
        canonicalColourRemapActive -> numPhysical
        anyMixAssigned -> maxOf(numPhysical, canonicalCount)
        else -> 0
    }
    return SliceMixToolSpaceDecision(
        anyMixAssigned = anyMixAssigned,
        mixToolSpace = mixToolSpace,
        mixPhysicalBase = mixPhysicalBase,
    )
}
