package com.u1.slicer.data

/**
 * Estimates wipe tower depth for the Prepare preview before slicing.
 *
 * Mirrors PartPlate::estimate_wipe_tower_size() in the OrcaSlicer C++ source,
 * which uses a two-point lookup table interpolated on model height.
 * The actual depth is computed during slicing from purge volumes — this is
 * a preview-only estimate used to show an accurate footprint for collision checks.
 *
 * Lookup table matches WipeTower::min_depth_per_height: {100mm → 20mm, 250mm → 40mm}
 */
object WipeTowerDepthEstimator {
    fun estimateDepth(modelHeightMm: Float, primeVolumeMm: Float = 0f): Float {
        val heightBased = when {
            modelHeightMm <= 100f -> 20f
            modelHeightMm >= 250f -> 40f
            else -> 20f + (modelHeightMm - 100f) / 150f * 20f
        }
        return maxOf(heightBased, primeVolumeMm)
    }
}
