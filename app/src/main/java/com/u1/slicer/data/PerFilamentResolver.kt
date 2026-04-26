package com.u1.slicer.data

import com.u1.slicer.nozzleTempDefaultForMaterial

/**
 * Phase 2.7 — pure resolver for the per-canonical-filament `filament_type`
 * and `nozzle_temperature` arrays sent to the slicer.
 *
 * For each fileIndex `i` in [canonical], the material resolution order is:
 *   1. `overrides[i].second` — user override material from the Prepare screen.
 *   2. `canonical.filaments[i].materialType` — the file's declared material.
 *   3. `presets[colorMapping[i]].materialType` — the slot the filament is
 *      mapped to (only consulted when the file/override don't supply one).
 *   4. `"PLA"` — final fallback.
 *
 * Nozzle-temperature resolution: when the user has overridden the material,
 * the temp comes purely from the resolved material via
 * [nozzleTempDefaultForMaterial] — do NOT consult the slot's linked filament
 * profile, because that profile is tuned for whatever was previously loaded
 * (typically PLA) and would defeat the override. When there is no override,
 * the slot's linked [FilamentProfile.nozzleTemp] wins; falling back to the
 * material default if the slot has no linked profile.
 *
 * **Cascade-free**: an override at fileIndex N affects only the entry at
 * index N. Other filaments mapped to the same physical slot are untouched.
 * This is the explicit contract that retired the slot-preset round-trip in
 * `applyFilamentOverridesToPresets` — see the architecture review at
 * `docs/superpowers/reviews/2026-04-26-phase2-architecture-review.md` §1.
 *
 * @param canonical The (possibly override-applied) canonical filament list.
 * @param overrides Map of `fileIndex → (colorHex?, materialType?)`. Only the
 *   second element (material) is consulted here; colour overrides flow
 *   through [applyOverridesToCanonical] separately.
 * @param colorMapping `fileIndex → physicalSlot` mapping; may be null for
 *   single-colour files (defaults to slot 0 for every entry).
 * @param presets The user's extruder presets (one per physical slot).
 * @param filamentLibrary The user's saved [FilamentProfile] library; used to
 *   look up linked profile temps for entries with no override.
 */
internal fun resolvePerFilamentTypeAndTemp(
    canonical: CanonicalFilamentList,
    overrides: Map<Int, Pair<String?, String?>>,
    colorMapping: List<Int>?,
    presets: List<ExtruderPreset>,
    filamentLibrary: List<FilamentProfile>,
): Pair<List<String>, List<Int>> {
    val types = ArrayList<String>(canonical.size)
    val temps = ArrayList<Int>(canonical.size)
    for (i in 0 until canonical.size) {
        val overrideMaterial = overrides[i]?.second
        val slot = colorMapping?.getOrNull(i) ?: 0
        val slotPreset = presets.firstOrNull { it.index == slot }
        val material = overrideMaterial
            ?: canonical.filaments[i].materialType
            ?: slotPreset?.materialType
            ?: "PLA"
        types.add(material)
        val profileTemp = if (overrideMaterial == null) {
            slotPreset?.filamentProfileId
                ?.let { id -> filamentLibrary.firstOrNull { it.id == id }?.nozzleTemp }
        } else null
        temps.add(profileTemp ?: nozzleTempDefaultForMaterial(material))
    }
    return types to temps
}
