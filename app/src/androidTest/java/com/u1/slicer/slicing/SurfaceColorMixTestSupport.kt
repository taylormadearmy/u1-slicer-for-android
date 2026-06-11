package com.u1.slicer.slicing

import com.u1.slicer.data.MixedFilamentManager
import com.u1.slicer.data.MixedFilamentRow

/**
 * Test bridge: replicate the slice-time mix-assignment the app does so the engine
 * sees a mix-extruder volume. Mirrors SlicerViewModel mix setup + nativeSetVolumeExtruder
 * at the native layer.
 *
 * Wired to match how the real app drives a mix recipe to the engine:
 *  - Recipe string built via MixedFilamentManager.addN() + serialize(numPhysical=4)
 *  - Per-volume extruder set via NativeLibrary.nativeSetVolumeExtruder(obj, vol, slot)
 *  - Mix slot = max(NUM_PHYSICAL, canonicalCount) + 1 (e.g. 5 for a 2-canonical file
 *    with NUM_PHYSICAL=4; first mix id = mixBase + 1 = 5)
 *
 * Mirrors MixSlotBlendVerificationTest and MixSlotObjectAssignBlendGateTest for the
 * recipe-building and volume-assignment patterns.
 */
object SurfaceColorMixTestSupport {

    /** Number of physical extruder slots on Snapmaker U1. */
    const val NUM_PHYSICAL = 4

    /**
     * Builds a MixedFilamentManager with the given component slots and weights,
     * serializes the recipe, and returns (mixSlot1Based, recipeString).
     *
     * componentSlots: 1-based physical extruder slot indices (e.g. [1, 2]).
     * weights: per-component percentages summing to ~100 (e.g. [50, 50]).
     *          MixWeights.normalize() is applied by addN so they need not be exact.
     * distributionMode: how the engine distributes the components — LAYER_CYCLE
     *          alternates whole layers; SAME_LAYER_DOTS splits infill by component
     *          within a layer (wipe-tower-safe).
     * canonicalCount: number of canonical filament slots the file declares (default 2).
     *          Determines mixBase = max(NUM_PHYSICAL, canonicalCount); the mix slot is
     *          mixBase + 1. Pass a higher value for files with more canonical slots so
     *          the computed mix slot does not collide with an existing file slot.
     */
    fun buildRecipeAndSlot(
        componentSlots: List<Int>,
        weights: List<Int>,
        distributionMode: MixedFilamentRow.MixDistributionMode =
            MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
        canonicalCount: Int = 2,
    ): Pair<Int, String> {
        val mgr = MixedFilamentManager(
            loadProject = { emptyList() },
            loadLibrary = { emptyList() },
            saveProject = {},
            saveLibrary = {},
        )
        mgr.addN(
            components = componentSlots,
            weights = weights,
            distributionMode = distributionMode,
        )
        val recipe = mgr.serialize(numPhysicalFilaments = NUM_PHYSICAL)
        val mixBase = maxOf(NUM_PHYSICAL, canonicalCount)
        val mixSlot = mixBase + 1
        return Pair(mixSlot, recipe)
    }
}
