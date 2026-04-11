package com.u1.slicer.data

/**
 * Mirrors sapil::SliceResult in C++.
 * Contains slicing outcome data.
 */
data class SliceResult(
    @JvmField val success: Boolean,
    @JvmField val cancelled: Boolean,
    @JvmField val errorMessage: String,
    @JvmField val gcodePath: String,
    @JvmField val totalLayers: Int,
    @JvmField val estimatedTimeSeconds: Float,
    @JvmField val estimatedFilamentMm: Float,
    @JvmField val estimatedFilamentGrams: Float
) {
    val estimatedTimeFormatted: String
        get() {
            val minutes = (estimatedTimeSeconds / 60).toInt()
            val hours = minutes / 60
            val mins = minutes % 60
            return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
        }

    val estimatedFilamentFormatted: String
        get() = String.format("%.1f g (%.0f mm)", estimatedFilamentGrams, estimatedFilamentMm)
}
