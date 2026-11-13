package com.u1.slicer.ui

import java.util.Locale
import kotlin.math.roundToInt

internal fun formatBedFootprint(widthMm: Float, depthMm: Float): String =
    "${formatDimension(widthMm)}×${formatDimension(depthMm)} mm"

internal fun formatBuildVolume(widthMm: Float, depthMm: Float, heightMm: Float): String =
    "${formatDimension(widthMm)} x ${formatDimension(depthMm)} x ${formatDimension(heightMm)} mm"

internal fun formatBuildVolumeWarning(
    exceededAxes: List<String>,
    widthMm: Float,
    depthMm: Float,
    heightMm: Float,
): String? = exceededAxes.takeIf { it.isNotEmpty() }?.let { axes ->
    "Exceeds ${formatBuildVolume(widthMm, depthMm, heightMm)} build volume on ${axes.joinToString("/")}"
}

private fun formatDimension(value: Float): String =
    if (value == value.roundToInt().toFloat()) {
        value.roundToInt().toString()
    } else {
        String.format(Locale.US, "%.1f", value).trimEnd('0').trimEnd('.')
    }
