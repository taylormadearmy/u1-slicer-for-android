package com.u1.slicer.gcode

data class ExcludeObjectInfo(
    val name: String,
    val center: Pair<Float, Float>,       // mm, bed coordinates
    val polygon: List<Pair<Float, Float>> // mm, bed coordinates; empty when not in G-code
)
