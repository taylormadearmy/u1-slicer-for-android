package com.u1.slicer

internal fun preparePreviewLoadingBody(
    modelTriangleCount: Int,
    hasPaintData: Boolean,
): String {
    val triangleSummary = if (modelTriangleCount > 0) {
        "This model has $modelTriangleCount triangles."
    } else {
        "The model metadata is ready."
    }
    val availabilitySummary = if (hasPaintData) {
        "Filaments and plate settings are already available while the colour preview streams in."
    } else {
        "Filaments and plate settings are already available while the preview streams in."
    }
    return "$triangleSummary $availabilitySummary"
}
