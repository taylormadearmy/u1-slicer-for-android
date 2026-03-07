package com.u1.slicer.bambu

data class ThreeMfPlate(
    val plateId: Int,
    val name: String,
    val objectIds: List<String>,
    val printable: Boolean = true,
    val transform: FloatArray = floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f, 0f,0f,0f),
    val thumbnailBytes: ByteArray? = null
) {
    val translationX: Float get() = transform[9]
    val translationY: Float get() = transform[10]
    val translationZ: Float get() = transform[11]
}

data class ThreeMfObject(
    val objectId: String,
    val name: String,
    val vertices: Int = 0,
    val triangles: Int = 0
)

data class ThreeMfInfo(
    val objects: List<ThreeMfObject>,
    val plates: List<ThreeMfPlate>,
    val isBambu: Boolean,
    val isMultiPlate: Boolean,
    val hasPaintData: Boolean = false,
    val hasLayerToolChanges: Boolean = false,
    val hasMultiExtruderAssignments: Boolean = false,
    val detectedColors: List<String> = emptyList(),
    val detectedExtruderCount: Int = 1
) {
    /** Whether the original Bambu structure must be preserved (not rebuilt with trimesh) */
    val needsPreserve: Boolean get() = isBambu && (
        hasMultiExtruderAssignments ||
        hasLayerToolChanges ||
        isMultiPlate
    )
}
