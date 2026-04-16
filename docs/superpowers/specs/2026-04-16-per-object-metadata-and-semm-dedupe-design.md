# Per-Object Metadata Preservation + SEMM Duplicate-Mapping Fix — Design

**Date:** 2026-04-16
**Author:** Claude (Opus 4.7)
**Target version:** v1.5.69
**Issues:** Discord reports from DC15 (Sensory Twist Ball no supports) and Jon (Goat horns wrong colour when E4 = E3)

## Summary

Two independent bugs, both stemming from U1 Slicer silently dropping structural metadata from Bambu 3MF files on the way to the native slicer.

1. **DC15 / Sensory Twist Ball:** A Bambu 3MF with **per-object** `enable_support=1`, `support_type=tree(manual)` overrides (set via BS's Objects tab) slices with no supports in U1 Slicer. Bambu Studio renders the supports correctly from the same file.
2. **Jon / Goat ( Gray ).3mf:** A 4-extruder per-object Bambu model that *also* has paint_color triangle attributes. When the user sets colour mapping `[0,1,2,2]` (E4 same slot as E3), the horn parts print in E1's filament instead of E3's.

Both regress the same class of user expectation: *"the file already has the settings I want — the slicer should honour them."*

## Bug 1: Per-Object Support Overrides Dropped

### Evidence

`Goat ( Gray ).3mf` is not involved here — the 3MF in question is [`SENSORY+TWIST+BALL+FIDGETS+optimised.3mf`](G:/My Drive/tes-data/SENSORY+TWIST+BALL+FIDGETS+optimised.3mf).

Source file `Metadata/project_settings.config`:
```
enable_support = 0
support_type = tree(auto)
```

Source file `Metadata/model_settings.config`:
```xml
<object id="2">
  <metadata key="enable_support" value="1"/>
  <metadata key="support_type" value="tree(manual)"/>
  <metadata key="support_on_build_plate_only" value="1"/>
  <metadata key="support_remove_small_overhang" value="0"/>
  <metadata key="extruder" value="1"/>
</object>
```

Mesh (`3D/Objects/object_2.model`):
```
2870 triangles tagged paint_supports="4"
```

Bambu-sliced G-code (from `bambu.SENSORY+TWIST+BALL+FIDGETS+optimised.gcode (2).3mf`):
- 173 `; FEATURE: Support` + 161 `; FEATURE: Support interface` segments
- Total: 18108.74 mm / 54.88 g, 3h 18m 40s

U1-sliced G-code (from `sensory.output.gcode`):
- **0** support segments
- 35884.83 mm / 107.03 g, 6h 23m 12s (1.95× more material)

### Root Cause

[`BambuSanitizer.kt:346-376`](app/src/main/java/com/u1/slicer/bambu/BambuSanitizer.kt#L346-L376). The sanitizer reads the source `model_settings.config`, extracts per-part *extruder* assignments, and either regenerates a minimal `model_settings.config` (extruder-only) or — for single-object/single-extruder files — writes **nothing at all**. All non-extruder per-object metadata is silently discarded.

Since OrcaSlicer resolves per-object config overrides via `object->config()` in [`Print.hpp:428`](app/src/main/cpp/orcaslicer/src/libslic3r/Print.hpp#L428):

```cpp
bool has_support() const { return m_config.enable_support || m_config.enforce_support_layers > 0; }
```

…dropping the per-object `enable_support=1` means `object->config().enable_support` resolves to the project-level `enable_support=0`, and the support pipeline is skipped. The `paint_supports="4"` triangle data on 2870 facets sits idle — there's even a warning for this exact condition at [`Print.cpp:1366-1376`](app/src/main/cpp/orcaslicer/src/libslic3r/Print.cpp#L1366-L1376).

The `buildSlic3rModelConfig` / `buildOrcaModelConfig` paths (the ones taken when restructuring *is* needed, e.g. Goat) also strip non-extruder metadata — but are a smaller user-impact category to fix. The dominant failure mode is the "no model config needed" no-op branch.

### Fix

In [`BambuSanitizer.kt:346-376`](app/src/main/java/com/u1/slicer/bambu/BambuSanitizer.kt#L346-L376), replace the `else { // No model config needed — no-op }` branch with pass-through of the buffered source `modelSettingsContent` when present.

```kotlin
} else {
    // No extruder-based rewrite needed — preserve the source model_settings.config
    // verbatim so per-object overrides (enable_support, support_type, layer_height,
    // seam_position, etc.) reach OrcaSlicer's per-object config layer.
    if (modelSettingsContent != null) {
        writeStored(destZip, "Metadata/model_settings.config", modelSettingsContent!!)
        Log.i(TAG, "Preserved source model_settings.config (per-object overrides)")
    }
}
```

### Why Pass-Through Is Safe

- The source `model_settings.config` is the canonical BBS format — OrcaSlicer's own BBS 3MF reader parses it.
- For Sensory Twist (single object, single extruder) there are no part-range mismatches to fix.
- For compound/restructured files, `needsModelConfig=true` is taken — we don't hit this branch.
- The only information lost by pass-through is any drift between the source file's part structure and the sanitizer's rebuilt structure. But the sanitizer only rebuilds when `needsModelConfig` is true.

### Fix Scope Limit

We do **not** change `buildSlic3rModelConfig` / `buildOrcaModelConfig` to emit non-extruder metadata in this fix. A follow-up can tackle multi-extruder files that also want per-object support overrides. The dominant real-world case (single-colour prints with paint-on-supports) is covered by the pass-through branch.

## Bug 2: SEMM Duplicate-Slot Mapping Drops Paint States

### Evidence

`Goat ( Gray ).3mf` is a 4-extruder per-object Bambu model (49 parts across extruders 1/2/3/4) whose mesh *also* has 25 924 + 5 724 triangles tagged with `paint_color` attributes. `ThreeMfParser` sets `hasPaintData=true`.

Device log from Pixel 8a run:
```
embedProfile: info.isBambu=true, info.detectedExtruders=4, info.hasPaint=true, …
Applied color mapping: 3 extruders used=[0, 1, 2], remap=null, …
Re-embedding 3MF (3-extruder embed) before slicing
Post-processed G-code: remapped tools to [0, 1, 2, 2] (toolRemap=null, semmPerm=[0, 1, 2, 2])
```

User report: horns (object parts with `extruder="3"` — or a similar high-index extruder — which should print on E3 physical) come out in E1's filament.

### Root Cause

[`SlicerViewModel.kt:3616`](app/src/main/java/com/u1/slicer/SlicerViewModel.kt#L3616) `computeEmbedTargetCount`:

```kotlin
return if (distinctSlots >= 4 && colorMapping.size > distinctSlots) {
    colorMapping.size          // H2C path (B48)
} else {
    distinctSlots              // Normal SEMM: BUGGY when distinct < full size
}
```

For Jon's `[0,1,2,2]`: `distinctSlots=3`, `colorMapping.size=4`, `distinctSlots < 4` — so it falls into the "normal SEMM" branch and returns **3**. The 3MF is re-embedded with only 3 filament slots. The native slicer's paint segmentation then loses the 4th paint state, and per-object parts with `extruder="4"` land on an out-of-range filament.

The B64 post-slice `semmColorPermutation` remap is then applied, but by that point the 4th paint state is already gone — the remap is rewriting a 3-tool G-code as if it had 4 tools, and the horn parts never had a T3 to rewrite.

### Why the "H2C-only" Carve-Out Was Wrong

B48's H2C carve-out treats the case where the user has *more model colours than physical extruders* (7 paint states on a 4-ext printer). But the *same reasoning* — preserve every paint state, dedupe in post-processing — applies whenever `colorMapping.size > distinctSlots`. Duplicate-slot mapping is just H2C-lite: the user wants two model colours printed on the same physical extruder, exactly as in H2C.

### Fix

Unify the SEMM path: when `hasPaintData=true`, always use `colorMapping.size`.

```kotlin
internal fun computeEmbedTargetCount(
    colorMapping: List<Int>?,
    hasPaintData: Boolean,
    toolRemapSlots: List<Int>?,
    fallbackExtCount: Int
): Int {
    if (hasPaintData && colorMapping != null && colorMapping.isNotEmpty()) {
        // Preserve every paint state — post-slice GcodeToolRemapper applies the
        // user's mapping (including duplicates) to compress tools back to
        // physical slots.  Unifies H2C and normal-SEMM duplicate-mapping paths.
        return colorMapping.size
    }
    if (toolRemapSlots != null) return toolRemapSlots.distinct().size
    return fallbackExtCount
}
```

### Downstream Correctness

- **ProfileEmbedder:** given `targetExtruderCount = colorMapping.size`, it pads per-filament arrays to N. Source files with fewer filament_colour entries get padded with defaults; source files with ≥ N entries are used as-is. No new code path.
- **Native slicer / B48 padding:** when `virtual_ext > n_ext`, the B48 block at [`sapil_print.cpp`](app/src/main/cpp/src/sapil_print.cpp) already pads `filament_flow_ratio`, `nozzle_temperature`, `idle_temperature`, etc. No new C++ required.
- **GcodeToolRemapper:** `composeSemmRemap(toolRemapSlots, semmColorPermutation)` already returns the full `colorMapping` list. With `semmPerm=[0,1,2,2]` and 4 tools emitted by the slicer, T0→T0, T1→T1, T2→T2, T3→T2. The horn parts (T3 in the fresh embed) land on physical E3. Correct.

### Test Update Required

[`MergeThreeMfInfoTest.kt`](app/src/test/java/com/u1/slicer/MergeThreeMfInfoTest.kt#L815-L836) has three unit tests that encode the *buggy* old-SEMM-uses-distinct behaviour. They must flip to the unified behaviour (return full `colorMapping.size`). The tests' intent was to lock in pre-B48 behaviour; the behaviour itself was wrong for duplicate mappings.

- `SEMM with duplicate mapping uses distinct count` → `uses colorMapping.size`, expect `4`.
- `old_3mf — 6 colours to 2 slots uses distinct` → `uses colorMapping.size`, expect `6`.
- `Korok — 5 colours to 3 slots uses distinct` → `uses colorMapping.size`, expect `5`.

No instrumented tests assert the old distinct behaviour (the Korok pipeline test only checks "slices cleanly"), so no slicing-integration regressions are expected.

## Non-Goals

- Fixing per-object metadata preservation in the `buildSlic3rModelConfig` / `buildOrcaModelConfig` rewrite paths (multi-extruder restructured files). Separate follow-up.
- Changing any C++ / native code. Both fixes are Kotlin-only.
- Handling Bambu non-Snapmaker profiles' full `profile_keys[]` whitelist — separate plan (`2026-04-15-dropped-profile-keys.md`) covers that.
- Changing `buildCompactExtruderRemap`'s `if (info.hasPaintData) return null` guard — the post-slice `semmColorPermutation` path handles the remap correctly once `targetCount` is right.

## Risk Assessment

- **DC15 fix:** low risk. Pass-through adds strictly *more* data; worst case is an unexpected metadata key in a file we otherwise would have dropped, which OrcaSlicer's BBS parser ignores. No new pipeline branch; the code path is already exercised by every Bambu 3MF.
- **Jon fix:** medium risk. The normal-SEMM distinct-count branch has shipped for many versions. The unit test regressions I'm updating are the *canary* — if anything else regresses, the full instrumented SEMM pipeline tests catch it (colored_benchy, flarewing_dragon, korok, H2C benchy). All those tests use `colorMapping.size == distinctSlots` in their assertions (identity or H2C), so they're unaffected.

## Success Criteria

1. Sensory Twist 3MF: `U1 G-code FEATURE: Support count > 0` (matches Bambu's 173+).
2. Goat 3MF with mapping `[0,1,2,2]`: the G-code tool count matches the 4-colour embed layout (T0-T3 all present pre-remap; T3→T2 applies so physical E3 sees T2+T3 combined volume).
3. All existing unit tests pass after the three targeted updates in `MergeThreeMfInfoTest.kt`.
4. All existing instrumented tests pass, including Korok, Flarewing Dragon, colored Benchy, H2C Benchy, old.3mf.
5. Manual E2E on Pixel 8a: Sensory Twist shows supports in the preview; Goat with E4 set to E3's colour shows horns in the correct slot colour in Preview.
