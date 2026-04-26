package com.u1.slicer.data

/**
 * Where a [FilamentEntry] came from when the file was normalized at load time.
 *
 * Used by the load-time normalize step to record provenance of each entry so
 * downstream consumers (UI, embed pipeline, future merge feature) can reason
 * about it without re-parsing the source file.
 *
 * - [FILE_COLOUR]    — declared in the file's `filament_colour` array (or
 *                      equivalent for non-Bambu formats).
 * - [PAINT_DERIVED]  — derived from `paint_color` / `mmu_segmentation`
 *                      triangle attributes (SEMM paint segmentation files).
 * - [OBJECT_DEFAULT] — derived from a `<part>` / `<object>` extruder
 *                      assignment in `model_settings.config`.
 * - [LAYER_TOOL]     — derived from `customGcodePerLayer.xml` Z-band tool
 *                      change entries (Hueforge / layer-tool files).
 * - [STL_DEFAULT]    — synthetic: STL geometry has no embedded filament data,
 *                      so a single entry is created keyed to the user's
 *                      currently-selected extruder preset.
 */
enum class FilamentSource {
    FILE_COLOUR,
    PAINT_DERIVED,
    OBJECT_DEFAULT,
    LAYER_TOOL,
    STL_DEFAULT,
}

/**
 * One entry in the canonical filament list.
 *
 * The [fileIndex] is the canonical identity — it matches the index the slicer
 * emits in G-code (`T<fileIndex>`) and the index referenced by paint_color /
 * mmu_segmentation triangle attributes. Filament 3 stays filament 3 even when
 * its [color] is later edited; this index-stability invariant is load-bearing
 * for the print-time-mapping refactor (see `2026-04-26-canonical-filament-list-ux.md`).
 *
 * @param fileIndex     0-based index into the file's filament list. Stable.
 * @param color         Hex string `"#RRGGBB"`. For [FilamentSource.STL_DEFAULT]
 *                      this is a derived value from the user's current
 *                      extruder preset and may change at render time.
 * @param materialType  Material name (e.g. `"PLA"`, `"PETG"`) — `null` if the
 *                      file does not declare one.
 * @param source        Provenance ([FilamentSource]).
 */
data class FilamentEntry(
    val fileIndex: Int,
    val color: String,
    val materialType: String?,
    val source: FilamentSource,
)

/**
 * The single source of truth for "what filaments does this file contain?",
 * produced once at load time by the format-specific normalize step.
 *
 * Replaces the four synthesis sources on `ThreeMfInfo`
 * (`objectPartExtruders`, `compoundPartParents`, `paintExtruderStates`,
 * `objectExtruderMap`) with one unified shape that the UI, embed pipeline,
 * and future pre-slice merge feature all read from.
 *
 * @param filaments       1..N entries, ordered by [FilamentEntry.fileIndex].
 *                        For files with paint segmentation referencing a
 *                        higher index than the declared array length, entries
 *                        are added with [FilamentSource.PAINT_DERIVED].
 * @param paintStateMap   Maps decoded paint state (the integer extracted by
 *                        `PaintColorDecoder` from a `paint_color` /
 *                        `mmu_segmentation` triangle attribute) → the
 *                        `fileIndex` of the [FilamentEntry] that state
 *                        addresses. Absorbs B95 high-index, AMS2 fold, and
 *                        bit-packed encoding edge cases at one place.
 *                        Empty for non-paint files.
 */
data class CanonicalFilamentList(
    val filaments: List<FilamentEntry>,
    val paintStateMap: Map<Int, Int> = emptyMap(),
) {
    /** Number of filaments declared by the file. Matches G-code `T0..T(size-1)`. */
    val size: Int get() = filaments.size

    /** True if more than one filament is in use — a quick UI predicate. */
    val isMultiColour: Boolean get() = filaments.size > 1
}

/**
 * Phase 2.6/2.8 — applies per-file-index user overrides to a canonical
 * list. For each entry whose [FilamentEntry.fileIndex] has an override
 * with non-null `color` or `materialType`, those values replace the
 * file's defaults.
 *
 * @param canonical The raw canonical list from the file.
 * @param overrides Map of file-filament-index → (colorHex?, materialType?).
 *   Caller-side type — the SlicerViewModel keeps it as
 *   `Map<Int, FilamentOverride>` but this helper only needs the two hex
 *   strings, so it stays in the data package without depending on UI types.
 */
fun applyOverridesToCanonical(
    canonical: CanonicalFilamentList,
    overrides: Map<Int, Pair<String?, String?>>,
): CanonicalFilamentList {
    if (overrides.isEmpty()) return canonical
    val updated = canonical.filaments.map { entry ->
        val ov = overrides[entry.fileIndex] ?: return@map entry
        val (overrideColor, overrideMaterial) = ov
        if (overrideColor == null && overrideMaterial == null) return@map entry
        entry.copy(
            color = overrideColor ?: entry.color,
            materialType = overrideMaterial ?: entry.materialType,
        )
    }
    return canonical.copy(filaments = updated)
}

/**
 * Phase 2.1 — synthesises a single-entry [CanonicalFilamentList] for an STL
 * file. STL geometry has no embedded filament data, so the canonical list
 * is built from the user's currently-selected extruder preset:
 *
 *   - [FilamentEntry.fileIndex] = 0 (slicer emits T0 only).
 *   - [FilamentEntry.color]    = the preset's currently-loaded colour.
 *   - [FilamentEntry.materialType] = the preset's material type.
 *   - [FilamentEntry.source]   = [FilamentSource.STL_DEFAULT].
 *
 * Callers should re-call this on every render rather than caching, because
 * the preset's colour can be edited by the user and the canonical list
 * should reflect that without requiring a file reload.
 */
fun stlCanonicalList(presetColor: String, presetMaterialType: String?): CanonicalFilamentList =
    CanonicalFilamentList(
        filaments = listOf(
            FilamentEntry(
                fileIndex = 0,
                color = presetColor,
                materialType = presetMaterialType,
                source = FilamentSource.STL_DEFAULT,
            )
        )
    )
