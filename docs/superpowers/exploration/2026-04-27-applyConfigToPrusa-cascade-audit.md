# 2026-04-27 — `applyConfigToPrusa` cascade audit

Surfaced by E2E on `feature/phase2-canonical-filaments` @ `baf136e`: P2.1
(PETG override on Filament 1) propagates to the G-code's `; filament_type =`
header but NOT to `; nozzle_temperature =`. The temp stayed uniformly 220°C
for all positions when index 0 should have been 235°C (PETG default).

This document captures (a) the structural pattern that produced the bug and
(b) the audit of every other setting at risk from the same pattern.

## Two parallel native-side write layers

The U1 Slicer's native side has two layers that BOTH set per-extruder
slicer config:

1. **Embed pipeline** (`sapil_print.cpp:762`, `profile_keys[]` loop) — applies
   values from the embedded `project_settings.config` FIRST. This is where
   Phase 2's canonical-aware `buildPerFilamentTypeAndTemp` lands.

2. **`applyConfigToPrusa`** (`sapil_print.cpp:779`) — applies Snapmaker U1
   hardware defaults SECOND. Unconditionally overwrites ~50 keys, with only a
   tiny minority gated on `has_embedded_profile` (line 441 onwards: supports,
   filament_type fallback).

Any per-extruder key that `applyConfigToPrusa` writes but Phase 2 wires only
via the embed path will be silently overwritten on every slice. The embed
config in `project_settings.config` looks correct, but the slicer's effective
value comes from `applyConfigToPrusa`'s slot-space defaults.

The `B48 padding` step at `sapil_print.cpp:858` then extends the array to
canonical size by *repeating the last value*, which propagates the wrong
default everywhere.

## At-risk settings audit

Every `set_key_value` call in `applyConfigToPrusa` that writes a per-extruder
array, classified by Phase 2 user-facing exposure:

| Setting | User-facing today? | Risk | Status |
|---------|-------------------|------|--------|
| `nozzle_temperature` | Yes (material override) | High | **FIXED** in `c31ca49` via `computeCanonicalAwareSlotTemps` |
| `nozzle_temperature_initial_layer` | Yes (same source) | High | **FIXED** (same code path: `first_temps[i] = config.extruder_temps[i]`) |
| `hot_plate_temp` | No today | Latent | PETG bed = 70-80°C, PLA = 60°C — wrong bed temp on PETG override could fail adhesion or warp |
| `hot_plate_temp_initial_layer` | No today | Latent | Same as above |
| `retraction_length` | No today | Latent | PETG retracts differently (less bowden-style ooze, more strings) |
| `retraction_speed` | No today | Latent | Material-tuned |
| `retract_length_toolchange` | No today | Latent | Material-tuned |
| `filament_max_volumetric_speed` | No today | Latent | PETG ≈ 12 mm³/s, PLA ≈ 21 mm³/s — wrong value over-extrudes |
| `filament_density` | No today | Latent (cosmetic — only affects weight estimate) | PETG = 1.27, PLA = 1.24 |
| `fan_min_speed` / `fan_max_speed` | No today | Latent | PETG cooling differs from PLA |
| `filament_loading_speed`, `filament_unloading_speed`, `filament_cooling_moves`, `filament_toolchange_delay` | No today | Low | Toolchange tuning, all uniform defaults today |

Today's user-facing surface is just **material-type override**. That's the
only override the user can edit on the Prepare screen. Material type
logically drives:

- nozzle temp ← FIXED
- bed temp ← latent
- retraction tuning ← latent
- volumetric speed ← latent
- fan speed ← latent

If the user overrides Filament 1 to PETG today, the slicer prints PETG-marked
filament with **PLA-tuned everything else** — could warp (PLA cooling), could
fail bed adhesion (PLA bed temp), could over-extrude (PLA volumetric speed).
The output looks correct in the G-code header but prints poorly.

## Why the existing tests didn't catch the temp gap

| Test | Layer | What it checks | Why it missed |
|------|-------|----------------|--------------|
| `PerFilamentResolverTest` | Pure unit (JVM) | The resolver outputs (`filamentTypes`, `nozzleTemps`) | Resolver works; the loss happens later |
| `Phase2AlignmentTest.h2cBenchy_overrideFileIndexZeroToPETG_appearsOnlyAtIndexZero` | Integration | G-code's `; filament_type =` header | **Only checked `filament_type`, not `nozzle_temperature`** |
| `HardcodedExtruderCapTest` | Source-grep | `coerceIn(1,4)` patterns | Bug isn't a hardcoded cap; it's a parallel-write |
| Manual smoke-7 | E2E | Visual + summary | Pre-Phase-2 baseline didn't have material override |

The fix in `c31ca49` extends `Phase2AlignmentTest` to also read the
`; nozzle_temperature =` header line and assert `tempHeader[0] == 235`. The
extension covers nozzle temp specifically — but the broader pattern is still
unguarded for the other at-risk keys. When future Phase 2 work adds bed-temp
or retraction overrides, the same gap will need to be re-discovered.

## Structural fix recommendations

**Short-term (Kotlin-only, applied for nozzle_temperature):** add a
`cfg.<setting>PerSlot` array per at-risk key, plus a canonical-aware
resolver that folds canonical→slot space. Doesn't scale — every new
per-filament parameter needs its own slot-space wiring.

**Long-term (native, the structural fix):** make `applyConfigToPrusa` skip
writing keys that the embed config already provided. Specifically:

1. Change the at-risk lines to gate on `if (!has_embedded_profile)` the way
   line 441's support-enabled gate already does. Or add a finer-grained
   "embed already wrote this" check (probe `dpc.option<...>(key)` for a
   non-default size).
2. Add the relevant keys to `profile_keys[]` so the embed config's values
   reach the slicer:
   - `nozzle_temperature`, `nozzle_temperature_initial_layer`
   - `hot_plate_temp`, `hot_plate_temp_initial_layer`
   - `retraction_length`, `retraction_speed`, `retract_length_toolchange`
   - `filament_density`, `filament_max_volumetric_speed`
   - `fan_min_speed`, `fan_max_speed`, `overhang_fan_speed`
3. The Kotlin canonical resolver becomes the single source of truth for all
   per-filament tuning; `applyConfigToPrusa` only fills hardware defaults
   (machine acceleration, bed bounds, etc.) and only when there's no embed.

Requires a native rebuild (NDK 26 / Release / `llvm-strip` per CLAUDE.md).
The build infrastructure for this is already in place.

## Test guard recommendation

A **structural guard** would prevent the same class of bug recurring. Two
options:

**(a)** A `BuildProfileOverridesContract` instrumented test that, for every
key in a list of "Phase 2 per-filament keys", asserts the value in
`project_settings.config`'s embed reaches the G-code's `; <key> =` header
unchanged when the user has set an override. Currently we only have this
contract for `filament_type`; extending to all at-risk keys = compile-time
coverage of the parallel-write vulnerability.

**(b)** A C++-side audit test (run via a debug-only `nativeDumpConfig`
accessor) that reports the final `dpc` values for every per-filament key
after `applyConfigToPrusa` runs, and asserts they match the embed config
when an embed was present. Catches the cascade structurally rather than per
key.

Both require some build-out. Recommended after the native applyConfigToPrusa
gate fix.
