package com.u1.slicer.data

import com.u1.slicer.nozzleTempDefaultForMaterial

/**
 * Phase 2.7 - pure resolver for the per-canonical-filament `filament_type`
 * and `nozzle_temperature` arrays sent to the slicer.
 *
 * For each fileIndex `i` in [canonical], the material resolution order is:
 *   1. `overrides[i].second` - user override material from the Prepare screen.
 *   2. `canonical.filaments[i].materialType` — the file's declared material,
 *      but ONLY when filament `i` is a genuinely *declared* spool (B128): the
 *      entry is [FilamentSource.FILE_COLOUR], the file is multi-colour, it has
 *      no paint segmentation, and `i`'s physical slot is not shared with any
 *      other filament (injective mapping). For paint-fold (SEMM/H2C),
 *      support/interface, single-colour, or slot-collision cases the file's
 *      material is NOT authoritative and resolution defers to the slot preset.
 *   3. `presets[colorMapping[i]].materialType` — the mapped physical slot.
 *   4. `"PLA"` - final fallback.
 *
 * B128 rationale: a normal multi-colour 3MF declares one material per filament
 * (e.g. PETG/PLA/TPU); those declared materials are the meaningful defaults the
 * user expects to see on load. But when several "filaments" fold onto one
 * physical slot (H2C/SEMM paint states) or a support filament is deliberately
 * routed to a different-material slot, the physical slot is authoritative — so
 * file-material authority is gated on the discriminator above. This keeps
 * B99/B125 (support filament) and B118 (single-colour slot preset) intact.
 *
 * Nozzle-temperature resolution: when the user has overridden the material,
 * the temp comes purely from the resolved material via
 * [nozzleTempDefaultForMaterial] - do NOT consult the slot's linked filament
 * profile, because that profile is tuned for whatever was previously loaded
 * (typically PLA) and would defeat the override. When there is no override,
 * the slot's linked [FilamentProfile.nozzleTemp] wins; falling back to the
 * material default if the slot has no linked profile.
 *
 * **Cascade-free**: an override at fileIndex N affects only the entry at
 * index N. Other filaments mapped to the same physical slot are untouched.
 * This is the explicit contract that retired the slot-preset round-trip
 * (`applyFilamentOverridesToPresets`, deleted in Phase 2 Step 4) - see the
 * architecture review at
 * `docs/superpowers/reviews/2026-04-26-phase2-architecture-review.md` section 1.
 *
 * @param canonical The (possibly override-applied) canonical filament list.
 * @param overrides Map of `fileIndex -> (colorHex?, materialType?)`. Only the
 *   second element (material) is consulted here; colour overrides flow
 *   through [applyOverridesToCanonical] separately.
 * @param colorMapping `fileIndex -> physicalSlot` mapping; may be null for
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

    // B128 discriminator context: count how many filaments land on each
    // physical slot so we can tell an injective (1:1) declared mapping from a
    // fold (collision). `declaredContext` gates file-material authority to
    // multi-colour, non-paint files.
    val slotUsage = HashMap<Int, Int>()
    for (i in 0 until canonical.size) {
        val slot = colorMapping?.getOrNull(i) ?: 0
        slotUsage[slot] = (slotUsage[slot] ?: 0) + 1
    }
    val declaredContext = canonical.size > 1 && canonical.paintStateMap.isEmpty()

    for (i in 0 until canonical.size) {
        val overrideMaterial = overrides[i]?.second
        val slot = colorMapping?.getOrNull(i) ?: 0
        val slotPreset = presets.firstOrNull { it.index == slot }
        val fileMaterial = canonical.filaments[i].materialType
        // File material is authoritative only for a genuinely declared spool:
        // FILE_COLOUR source, declares a material, in a multi-colour non-paint
        // file, and owns its physical slot (no collision).
        val fileMaterialWins = declaredContext &&
            canonical.filaments[i].source == FilamentSource.FILE_COLOUR &&
            fileMaterial != null &&
            (slotUsage[slot] ?: 0) == 1

        // DC15 regression guard (Discord 1510408385571586212, 2026-05-30): when a
        // slot preset's materialType is blank ("" from a corrupted/legacy
        // ExtruderPreset JSON parse, or a user that cleared the field), the
        // fallback chain previously bottomed out at the blank string — the
        // Prepare row rendered as "none". Treating blank-as-null lets the
        // chain fall through to the file-declared material (if any) and
        // finally to the "PLA" guarantee, so the row always shows a real
        // material name.
        val slotMaterial = slotPreset?.materialType?.takeIf { it.isNotBlank() }
        val material = when {
            overrideMaterial != null -> overrideMaterial
            fileMaterialWins -> fileMaterial!!
            else -> slotMaterial ?: fileMaterial ?: "PLA"
        }
        types.add(material)

        // Nozzle temp: the slot's linked filament-profile temp applies only when
        // the resolved material actually comes from (or matches) that slot — i.e.
        // there is no override AND the resolved material equals the slot preset's
        // material. Otherwise the profile (tuned for the slot's spool) no longer
        // describes the resolved material, so we use the material's default temp.
        val profileTempApplies = overrideMaterial == null &&
            slotPreset?.materialType == material
        val profileTemp = if (profileTempApplies) {
            slotPreset?.filamentProfileId
                ?.let { id -> filamentLibrary.firstOrNull { it.id == id }?.nozzleTemp }
        } else null
        temps.add(profileTemp ?: nozzleTempDefaultForMaterial(material))
    }
    return types to temps
}
