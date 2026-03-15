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
    val detectedExtruderCount: Int = 1,
    /**
     * True when the original Bambu 3MF ZIP contains Metadata/plate_N.json files.
     * Newer BambuStudio multi-plate format: each plate is independently loadable,
     * so extractPlate() can safely filter the build to a single item.
     * Older format files (Dragon Scale, Shashibo) have no plate JSONs; their build
     * items share component file refs and must ALL remain in <build> for OrcaSlicer
     * to resolve them correctly.
     */
    val hasPlateJsons: Boolean = false,
    /** 1-based extruder indices actually assigned to objects/volumes in model config.
     *  Empty when no assignments found. Used to filter colors per-plate. */
    val usedExtruderIndices: Set<Int> = emptySet(),
    /** Per-object extruder assignments: objectId (String) → 1-based extruder index.
     *  Used by mesh preview to color per-volume. */
    val objectExtruderMap: Map<String, Int> = emptyMap()
) {
    /** Whether the original Bambu structure must be preserved (not rebuilt with trimesh) */
    val needsPreserve: Boolean get() = isBambu && (
        hasMultiExtruderAssignments ||
        hasLayerToolChanges ||
        isMultiPlate
    )
}
