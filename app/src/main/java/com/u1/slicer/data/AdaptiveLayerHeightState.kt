package com.u1.slicer.data

/** Project-scoped variable-layer recipe. The generated curve stays native on ModelObject. */
data class AdaptiveLayerHeightState(
    val mode: Mode = Mode.USE_FILE,
    val preset: Preset = Preset.BALANCED,
) {
    enum class Mode { USE_FILE, OFF, ADAPTIVE }
    enum class Preset(val quality: Float, val smoothingRadius: Int, val keepMinimum: Boolean) {
        DETAIL(0.25f, 3, true),
        BALANCED(0.50f, 5, false),
        FAST(0.75f, 7, false),
    }
}
