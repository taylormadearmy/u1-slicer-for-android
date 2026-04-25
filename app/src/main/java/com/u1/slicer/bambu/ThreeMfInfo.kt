package com.u1.slicer.bambu

data class ThreeMfPlate(
    val plateId: Int,
    val name: String,
    val objectIds: List<String>,
    val filamentIndices: Set<Int> = emptySet(),
    val printable: Boolean = true,
    val transform: FloatArray = floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f, 0f,0f,0f),
    val thumbnailBytes: ByteArray? = null,
    /** Per-plate layer-tool change display colors (from `<layer color="...">` in this plate's XML section). */
    val layerToolColors: List<String> = emptyList(),
    /** Per-plate layer-tool change extruder indices (1-based) for this plate's XML section. */
    val layerToolExtruders: Set<Int> = emptySet(),
    /** True if this plate's XML section contains at least one type-1 or type-2 layer entry. */
    val hasLayerToolChanges: Boolean = false,
    /**
     * True when this plate's objects carry paint data (paint_color / mmu_segmentation attributes).
     * Sourced from [ThreeMfParser]'s `computeVisualColorCountByPlate` pass. Sub-plan #2c relies on
     * this flag to let `mergeThreeMfInfoForPlate` derive plate-local paint state without running
     * a separate Kotlin plate extraction + reparse pass. Defaults to `false` for non-Bambu files
     * and any plate the visual-colour pass didn't classify as painted.
     */
    val hasPaintData: Boolean = false,
    /**
     * Per-plate paint extruder states — the set of 1-based extruder indices encoded in the
     * plate's `paint_color` / `mmu_segmentation` triangle attributes. Sub-plan #2d uses this
     * to seed `usedExtruderIndices` for SEMM painted plates: when a plate has one object
     * with multiple paint states (e.g. slip slide plate 3 = 1 object with 4 paint regions),
     * the object-level `objectExtruderMap` collapses to a single extruder while paint
     * decode reveals the full palette. Populated by `computeVisualColorCountByPlate`.
     */
    val paintExtruderStates: Set<Int> = emptySet()
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

data class LayerToolSegment(
    val topZ: Float,
    val extruderBambu: Int  // 1-based: 1→T0, 2→T1, etc.
)

data class ThreeMfInfo(
    val objects: List<ThreeMfObject>,
    val plates: List<ThreeMfPlate>,
    val isBambu: Boolean,
    val isMultiPlate: Boolean,
    val hasPaintData: Boolean = false,
    val hasPaintSupports: Boolean = false,
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
    val objectExtruderMap: Map<String, Int> = emptyMap(),
    /**
     * Per-object ALL-extruders — for each object, the set of 1-based extruder indices
     * used by that object's parts (sub-volumes) plus the object-level default. For
     * Bambu compound objects (one `<object>` with many `<part>` children each on a
     * different extruder), this captures the full per-part palette that the
     * object-level-only `objectExtruderMap` collapses. Sub-plan #2c
     * `buildSelectedPlateInfo` reads this to produce correct
     * `sourcePlateObjectExtruders` for multi-part-single-object plates like Dragon
     * Scale plate 3.
     */
    val objectPartExtruders: Map<String, Set<Int>> = emptyMap(),
    /**
     * Compound-object parent mapping — for each `<part>` id that appears inside an
     * `<object>`, record the parent object's id. Lets plate-scoped filters treat a
     * part as belonging to its parent object for plate-membership tests. Without
     * this, `objectExtruderMap` filter by `sourcePlate.objectIds` would drop all
     * part entries (their ids don't appear in plate→object mappings), losing the
     * per-part extruder assignments needed by preview / embed downstream.
     */
    val compoundPartParents: Map<String, String> = emptyMap(),
    val layerToolSegments: List<LayerToolSegment>? = null
) {
    /** Whether the original Bambu structure must be preserved (not rebuilt with trimesh) */
    val needsPreserve: Boolean get() = isBambu && (
        hasMultiExtruderAssignments ||
        hasLayerToolChanges ||
        isMultiPlate
    )
}
