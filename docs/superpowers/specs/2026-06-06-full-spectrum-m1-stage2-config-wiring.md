# Full-Spectrum M1 Stage 2 — Config-Key Wiring

**Date:** 2026-06-06
**Status:** Design approved; ready for implementation plan
**Parent roadmap:** [`2026-05-26-full-spectrum-roadmap.md`](2026-05-26-full-spectrum-roadmap.md) — see §M3, Appendix A, and the §7 (Status checks) Stage 1 status

## Context

M1 Stage 1 (committed branch `worktree-feature+full-spectrum-m1`) bumped the
OrcaSlicer submodule from `bd66b99` to a v2.3.3-based pin that includes PR #375
("Feat: mix filament"). The engine *can* now produce optically-blended colours
via layer alternation, but the Kotlin/SAPIL layer has no way to drive it — the
new mixed-filament config keys aren't wired into our `SliceConfig` → native
struct → engine config pipeline.

Stage 2 wires the **minimum** required to make full-spectrum reachable. No UI
work, no scalar tuning knobs exposed individually, no Smart Paint integration.
The acceptance test for "done" is: a Kotlin caller can construct a `SliceConfig`
with a mix recipe and `slice()` emits the corresponding layer-alternation G-code.

This stage unblocks M2 (real-U1 feasibility print) and M3 (Compose UI), each of
which gets its own subsequent spec.

## Goal

Surface ONE new field on `SliceConfig` — `mixedFilamentDefinitions: String?` —
that lets callers pass a mix recipe straight through to the engine, AND whitelist
all 19 of PR #375's new config keys in `profile_keys[]` so an externally-supplied
3MF profile can drive them via the embed path.

## Out of scope

- **M3 Compose UI** — colour picker, recipe-builder UI. Stage 2 is API-surface only.
- **Typed Kotlin fields for the other 18 scalar config keys** (`mixed_filament_gradient_mode`, `dithering_local_z_mode`, etc.). They still reach the engine via `profile_keys[]` (so an externally-supplied 3MF profile can drive them); we just don't carve individual `SliceConfig` slots until M3 needs them.
- **Smart Paint slot-byte widening (M3a)** — per-triangle slot must widen from `0..3` to virtual IDs `≥4`. Touches `AiRegion`, `PaintedMeshWriter`, the slot-reassignment chips. M3-time work.
- **Prusa `prusa-fdm-mixer` integration (M4)** — colour-prediction library bolt-on.
- **Real-U1 feasibility print (M2)** — stage 2 unblocks this; the print itself is M2.

## Architecture

A single conditional `set_key_value` in `applyConfigToPrusa()` writes the user's
recipe string into the engine's dynamic print config when present. The 19-key
whitelist addition in `profile_keys[]` lets the embed path do the same for
profile-driven recipes. Both paths converge on the engine's
`MixedFilamentManager::load_custom_entries(recipe)` which reconstructs virtual
filaments and produces the layer-alternation tool-change G-code.

Native rebuild required (source change in `sapil.h`, `sapil_print.cpp`,
`slicer_wrapper.cpp`). The protections shipped on main (`scripts/install-hooks.sh`
+ `scripts/rebuild-native-so.sh`) gate the rebuild.

## Components (5 files touched)

| File | Change | Lines |
|---|---|---|
| `app/src/main/cpp/include/sapil.h` | Add `std::string mixed_filament_definitions = ""` field to `SliceConfig` struct | 1 |
| `app/src/main/cpp/src/sapil_print.cpp` | (a) Conditional `set_key_value("mixed_filament_definitions", new ConfigOptionString(config.mixed_filament_definitions))` in `applyConfigToPrusa()` when non-empty. (b) Append 19 new key names to `profile_keys[]` whitelist. | ~25 |
| `app/src/main/cpp/src/slicer_wrapper.cpp` | JNI marshal: copy `mixedFilamentDefinitions` from Kotlin SliceConfig to native struct. Same pattern as `filament_type`. | ~3 |
| `app/src/main/java/com/u1/slicer/data/SliceConfig.kt` | Add `@JvmField var mixedFilamentDefinitions: String = ""` (non-nullable, empty default — matches existing `filament_type`/`fillPattern` pattern in this struct; non-nullable also keeps JNI marshalling simple). | 1 |
| `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` | In `buildProfileOverridesImpl`, emit `"mixed_filament_definitions"` → recipe string in the override map when SliceConfig's field is non-empty. Skip when empty/null so embed-path values win. | ~5 |

**The 19 keys** to add to `profile_keys[]` (per Appendix A of the parent roadmap):

```
mixed_filament_definitions
mixed_filament_gradient_mode
mixed_filament_height_lower_bound
mixed_filament_height_upper_bound
mixed_filament_advanced_dithering
mixed_filament_component_bias_enabled
mixed_filament_surface_indentation
mixed_filament_region_collapse
mixed_color_layer_height_a
mixed_color_layer_height_b
mixed_filament_pointillism_pixel_size
mixed_filament_pointillism_line_gap
dithering_z_step_size
dithering_local_z_mode
dithering_local_z_whole_objects
dithering_local_z_infill
dithering_local_z_direct_multicolor
dithering_step_painted_zones_only
local_z_wipe_tower_purge_lines
```

## Data flow

**User-recipe path** (Kotlin caller drives the recipe directly):

```
Kotlin: cfg = SliceConfig(...).copy(mixedFilamentDefinitions = "1,1/2")
        lib.slice(cfg)
    ↓ JNI marshal
Native SliceConfig: mixed_filament_definitions = "1,1/2"
    ↓ applyConfigToPrusa() (when has_embedded_profile=false, OR after embed-load)
dpc["mixed_filament_definitions"] = ConfigOptionString("1,1/2")
    ↓ engine
MixedFilamentManager::load_custom_entries("1,1/2") → virtual filament list
    ↓ slicing
G-code: alternating T0/T1 tool changes per layer cadence
```

**Embedded-profile path** (3MF brings its own recipe):

```
3MF: project_settings.config has "mixed_filament_definitions" + scalar tuning keys
    ↓ embed load
dpc["mixed_filament_definitions"] = <profile value> (allowed by profile_keys[] whitelist)
dpc["mixed_filament_gradient_mode"] = <profile value> (same)
... (etc. for other 18 keys)
    ↓ applyConfigToPrusa() — Kotlin field is empty, so no overwrite
    ↓ engine
Same as above; slicing produces the file's mix.
```

**Override precedence** stays consistent with the rest of the codebase:
JNI/Kotlin user value wins over embedded profile when non-empty, exactly because
`applyConfigToPrusa` runs *after* the embed load and `buildProfileOverrides`
emits the value into the override map.

## Error handling

- **Empty Kotlin field (default)** — `applyConfigToPrusa` checks `!config.mixed_filament_definitions.empty()` and skips the `set_key_value` otherwise. `buildProfileOverridesImpl` skips the map entry. Engine default = no mixing. Every existing slice is byte-identical to pre-stage-2 behaviour. (Verified by the existing test suite passing unchanged.)
- **Invalid recipe string** — `MixedFilamentManager::load_custom_entries` validates the format internally per PR #375's parser. Errors bubble up through normal slice-error reporting; nothing new to wire.
- **Recipe references a physical filament index higher than `extruder_count`** — engine treats it as an out-of-range error during slicing (matches its existing behaviour for other index-based settings); surfaces as a slice failure with the engine's error string.

## Testing

**JVM unit tests** (in `app/src/test/`):
- `buildProfileOverridesImpl_emitsMixedFilamentDefinitions_whenSet` — assert the returned map contains `"mixed_filament_definitions" → recipe` when SliceConfig has it set.
- `buildProfileOverridesImpl_omitsMixedFilamentDefinitions_whenEmpty` — assert the key is absent when SliceConfig has the default empty string `""`.
- These belong with the existing `buildProfileOverridesImpl` tests (search `BuildProfileOverridesTest` or wherever current overrides are tested).

**Instrumented test** — add a new test method to `SlicingIntegrationTest.kt` alongside the existing tetrahedron tests (or, if you prefer a focused file, create `MixedFilamentSlicingTest.kt` — but a single test method doesn't justify a new file yet):
- `mixedFilament_simpleLayerAlternation_producesAlternatingToolChanges` — load a small STL (e.g. the existing `tetrahedron.stl` or `3DBenchy.stl`), set `extruderCount=2`, set `mixedFilamentDefinitions = "1,1/2"` (filament 1 layer, then filament 2 layer, alternating), slice, assert the resulting G-code has both `T0` and `T1` tool-change lines AND that they alternate with the expected cadence (e.g. count of `T1` per layer-boundary).
- Specific assertions: `T0` lines > 0 AND `T1` lines > 0 (mix actually happened) AND a sanity-check that consecutive layers don't both use only T0 (alternation is visible).

**Don't add a separate native-side test for `profile_keys[]`** — the instrumented test indirectly verifies the whitelist by checking the slice actually produces a mix. If the key were missing from `profile_keys[]`, an embedded-profile-driven recipe would silently be ignored; we'd catch that in M2 (real-print) the moment we slice a profile-driven mix.

## Native rebuild + verification

After source changes:

```bash
scripts/rebuild-native-so.sh app/.cxx/Release/m1-stage2/arm64-v8a
```

The wrapper handles strip / size / clang-17 / 16KB-align / JNI-count verification.
The pre-commit hook installed in stage 1 prevents committing the rebuilt `.so` if
the orca submodule has uncommitted modifications — won't apply here since stage 2
only touches our own SAPIL sources, not the orca submodule.

The build dir name should differ from stage 1's (`m1-stage1/`) so the two builds
don't share `.cxx` cache and contaminate each other. Suggest `m1-stage2/`.

## Risks

- **PR #375's recipe-string format is still hardening upstream.** As of 2026-06-05, Snapmaker merged 6 follow-up fix PRs to mixed-filament after #375. If the recipe parser changes its accepted format, our hard-coded test string ("1,1/2") may need updating. Mitigation: use the simplest possible recipe in the test (two physical filaments, 1:1 ratio); avoid any of the manual-pattern / gradient / pointillism features.
- **Engine default for new keys.** Per Appendix A, every key's engine default is "off" / `0` / `""`. So unsetting `mixedFilamentDefinitions` truly produces no mixing. Verified at design time, but worth a sanity check at first build: slice an existing fixture (e.g. `colored_3DBenchy.stl`) and confirm the G-code is byte-identical to pre-stage-2.

## Acceptance criteria

1. JVM unit tests for `buildProfileOverrides` pass (existing + 2 new).
2. The new instrumented test passes (mix recipe → alternating G-code).
3. The full existing test suite (1,479 unit + ~405 instrumented) stays green — every fixture that was green pre-stage-2 stays green.
4. `.so` rebuilt under `m1-stage2/`, verified per the rebuild-script checks (~21–22 MB, clang 17, 51 JNI symbols, 16KB-aligned).
5. Committed on its own branch off `main` (NOT on `worktree-feature+full-spectrum-m1`), so stage 1 and stage 2 land as separable PRs.

## Sequencing after stage 2

- **M2** — drive a full-spectrum slice from the stage-2 SliceConfig + real U1 print. Verifies print quality + nozzle-offset registration. Pure user/hardware work; no further code changes if stage 2 passes its tests.
- **M3** — design the Compose UI (the marquee Smart-Paint integration work). Touches the M3a slot-widening question.
- **M4** — Prusa `prusa-fdm-mixer` for colour-prediction accuracy.
