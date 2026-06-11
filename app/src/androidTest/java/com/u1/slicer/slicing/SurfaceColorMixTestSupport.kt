package com.u1.slicer.slicing

import com.u1.slicer.NativeLibrary
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
 *  - Mix slot = numPhysical + 0 + 1 = 5 (mixBase=max(4, canonicalCount)=4 for a
 *    2-canonical file; first mix id = mixBase + 1 = 5)
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
     */
    fun buildRecipeAndSlot(
        componentSlots: List<Int>,
        weights: List<Int>,
        distributionMode: MixedFilamentRow.MixDistributionMode =
            MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
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
        // Mix slot: mixBase = max(NUM_PHYSICAL, canonicalCount). For a 2-canonical file
        // canonicalCount=2 so mixBase=NUM_PHYSICAL=4; first mix id = 5.
        val mixSlot = NUM_PHYSICAL + 0 + 1  // = 5
        return Pair(mixSlot, recipe)
    }

    /**
     * Assigns every volume of every object in the currently-loaded model to [mixSlot]
     * via the native extruder-override path (mirrors SlicerViewModel.setObjectFilament /
     * setVolumeExtruder called from the FilamentChooserDialog onPick path).
     */
    fun assignWholeModel(lib: NativeLibrary, mixSlot: Int) {
        val objCount = lib.nativeGetObjectCount()
        for (o in 0 until objCount) {
            val vols = lib.nativeGetVolumeCount(o)
            for (v in 0 until vols) lib.nativeSetVolumeExtruder(o, v, mixSlot)
        }
    }
}
