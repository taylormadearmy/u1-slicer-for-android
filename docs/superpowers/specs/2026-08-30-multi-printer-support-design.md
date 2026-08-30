# A2 — Heterogeneous multi-printer support — Design

**Status:** Draft, 2026-08-30
**Issue:** [GitHub #152](https://github.com/taylormadearmy/u1-slicer-for-android/issues/152) (BACKLOG A2)
**Ships as:** v5.0.0 (major — first release where the app is not U1-only)
**Companion plan:** [`docs/superpowers/plans/2026-08-30-multi-printer-support.md`](../plans/2026-08-30-multi-printer-support.md)
**Builds on:** F78 (multiple U1s, shipped), Bambu beta path (`SlicerTarget`/`BambuMachineProfile`/`machine_target`, shipped)

## TL;DR

Let a user add *any* printer from OrcaSlicer's vendor catalogue (367 models across 68 vendors), or import an `.orca_printer` bundle, and get correct local slicing + upload for it — while the Snapmaker U1 remains the first-class default and the Bambu beta path keeps working unchanged. The core move is replacing the two hardcoded machine paths (U1 literals in `applyConfigToPrusa()`, `BambuMachineProfile` map) with one data-driven `MachineProfile` pipeline fed by Orca-native profile JSON.

## Why now / evidence base

Three research inputs (2026-08-30):

1. **Codebase inventory** — the U1 coupling surface is fully mapped. Highest-risk findings:
   - `sapil_print.cpp:447-451` writes `printable_area` as a 270-literal and **ignores** the `bed_size_x/y` Kotlin already sends over JNI; `printable_height` is never set on the U1 path and is missing from `profile_keys[]`.
   - The only profile-provenance gate is a `PRINT_START` substring heuristic (`sapil_print.cpp:986`) that false-positives on any Klipper printer's 3MF. The intended replacement marker `snapmaker_authored_profile` is already written by `ProfileEmbedder.kt:337` but never read natively.
   - The 4-extruder slot model is structural in exactly one place — mix/AI-paint byte encoding is `4 + k` (`SlicerViewModel.kt:6387-6398`); everywhere else "4" is a default or a clamp.
   - Connectivity is **already multi-printer** (F78: `PrintersConfig`, `PrinterTransportFactory`), and the Bambu path already demonstrates the per-machine pattern to generalise (`resolveTargetedSliceConfig` + native `machine_target`).
2. **SliceBeam / OrcaSlicer-Mobile review** — their multi-printer support = bundled vendor catalogue + setup wizard (vendor → model → nozzle variant → filaments) + `.orca_printer` import with recursive `inherits` resolution and auto-download of missing bases from SoftFever GitHub raw. Their weakness (self-documented in their `ORCA_KEY_AUDIT.md`): a PrusaSlicer-ini conversion layer with rename maps and whitelists that silently drop keys. We copy the UX and the recursive-download idea; we skip the conversion layer entirely because our engine consumes Orca keys natively.
3. **Ecosystem survey** — OrcaSlicer `resources/profiles/` (AGPL-3.0, same licence as us) is the profile source: 68 vendor index JSONs, 367 printer models, flat JSON with single-parent `inherits`, per-vendor subtree typically < 1 MB once PNG/STL are dropped. Upstream Orca also has a ready-made OTA design (`PresetUpdater::sync_vendor_config`: vendor `version` field + per-vendor zip + snapshot/rollback) and a full catalogue of AGPL host clients in `src/slic3r/Utils/` (OctoPrint, PrusaLink, Duet, Repetier, MKS…) to port to Kotlin.

## Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | **Profile format: Orca vendor JSON, no conversion layer.** | Engine already eats Orca keys via `project_settings.config`. OrcaSlicer-Mobile's ini/rename-map approach is their documented top bug source. |
| D2 | **Profile source: SoftFever/OrcaSlicer `resources/profiles` pinned to tag `v2.2.0`**, fetched per-vendor at runtime from `raw.githubusercontent.com`, cached on device. Bundle only Snapmaker U1 (already in assets) + one generic-Klipper and one generic-Marlin profile. | 46 MB full tree is un-bundleable; per-vendor fetch is ~1 MB. Pin near our engine version (Snapmaker Orca 2.2.4) so key/placeholder syntax matches; bumping the pin is a deliberate, tested act. |
| D3 | **One `MachineProfile` data model** (Kotlin) resolved from Orca machine JSON (with `inherits` chain), feeding the existing `SliceConfig` fields (`bedSizeX/Y`, `maxPrintHeight`, `machineStartGcode`…, `machineTarget`). The Bambu enum path stays as-is in v1 and converges later. | `resolveTargetedSliceConfig` already proves the shape. Don't destabilise the shipping Bambu beta while rebuilding the U1 path. |
| D4 | **Native: honour JNI geometry everywhere; provenance via explicit marker.** `applyConfigToPrusa()` reads `config.bed_size_x/y`/`max_print_height` instead of 270-literals; `printable_height` joins `profile_keys[]`; the `PRINT_START` heuristic is replaced by reading `snapmaker_authored_profile` (kept as key name for back-compat; written for **every** app-authored embed) with the heuristic as fallback for old files. The 165-key whitelist applies to any app-authored profile, not just Snapmaker. | The marker already exists on the Kotlin side. This kills the false-positive class documented in-code at `sapil_print.cpp:973-981`. |
| D5 | **Every key `applyConfigToPrusa()` writes must be overridable via `profile_keys[]`** — enforced by a structural unit test that parses `sapil_print.cpp`, in the style of the repo's existing source-grep guards. | This is the exact silent-drop bug class OrcaSlicer-Mobile shipped. Make it impossible to regress. |
| D6 | **Extruder count 1–4 in v1.** `Printer`/`MachineProfile` gain `extruderCount`; slot UIs, presets, clamps parameterise. ColorMix / AI-paint mixes stay gated to 4-slot printers (the `4 + k` byte encoding is untouched in v1). >4-extruder printers are out of scope. | Mix encoding rework is high-risk, low-demand; single-extruder printers are the common non-U1 case and need no mixes. |
| D7 | **G-code dialect strategy layer.** `GcodeDialect` interface (Klipper-Snapmaker, Klipper-generic, Marlin) governs: `SM_*` handling in `GcodeToolRemapper`/`PrintTimeRemap`, pause command (`PAUSE_PRINT` marker + `M109 T` vs `M600`), thumbnail format (Klipper base64 block vs PRUSA thumbnail), `exclude_object` emission, heater G-code (`SET_HEATER_TEMPERATURE` vs `M104/M140`), and per-flavour `GcodeValidator` rules. | Inventory §5: the post-processing chain is narrow and well-isolated; a strategy object per printer is enough. |
| D8 | **Connectivity v1: Moonraker (generic Klipper) + OctoPrint + PrusaLink.** Persist PrusaSlicer/Orca's `host_type`/`print_host`/`printhost_apikey` key family on `Printer`. Port request shapes from Orca's AGPL `src/slic3r/Utils/` C++ clients. SDCP (Elegoo), Duet, and further Bambu work are later phases; Bambu LAN stays on the existing transport. | Covers the overwhelming majority of network-printable FDM printers with three small HTTP clients on the existing OkHttp stack. |
| D9 | **Bed rendering: generic rectangle + grid, sized from the profile; optional vendor `bed_model` STL fetched with the profile.** U1 keeps `u1_bed.stl`; the GL-rendered "snapmaker" logo quad renders only for the Snapmaker family. | `BedDrawable` currently rescales a U1-shaped STL — distorted for any other geometry. |
| D10 | **Migration:** existing users auto-get a "Snapmaker U1 (0.4 nozzle)" `MachineProfile` bound to their existing printers; behaviour byte-identical (guarded by the existing 1976 + 451 test suite). | Same pattern as F78's single-URL → `PrintersConfig` migration. |
| D11 | **Profile validation in CI:** run upstream's `orca_extra_profile_check.py` against our bundled/pinned profile snapshot; add fixture slices for each supported family per release. | Upstream profile churn must never brick the slice pipeline silently. |

## Out of scope (v1)

- Non-rectangular beds (delta/circular printers) — `printable_area` polygon is reduced to its bounding box; delta printers are filtered out of the wizard.
- \>4 extruders; mixes on non-4-slot printers (D6).
- SEMM/MMU single-extruder-multi-material printers (`single_extruder_multi_material=1` machines are filtered out of the wizard in v1; the engine flag exists but ramming/wipe-tower behaviour is untested).
- Bambu printers via the new pipeline (they keep the existing dedicated path).
- SLA printers, Duet, SDCP, Repetier hosts (phase 6+ follow-ups).
- Per-printer process/filament profile *editing* UI beyond what exists (SlicingOverrides continues to apply globally).

## Architecture

```
Orca vendor catalogue (GitHub raw, pinned tag v2.2.0)
        │  VendorCatalogClient (fetch index + profiles, cache)
        ▼
OrcaProfileResolver (inherits-chain resolution → flat machine/process/filament JSON)
        ▼
MachineProfile (Kotlin data class; persisted per configured Printer)
        │
        ├──► MachineConfigResolver → SliceConfig (bedSizeX/Y, maxPrintHeight,
        │      machine*Gcode, machineTarget="GENERIC", extruderCount)
        ├──► ProfileEmbedder.embed(machineJson, processJson, filamentJson)
        │      + snapmaker_authored_profile marker  → 3MF project_settings.config
        ├──► GcodeDialect (postprocess/validate/pause/thumbnail strategy)
        └──► BedDrawable / viewers (bed geometry, bed mesh, branding)

native sapil_print.cpp:
  applyConfigToPrusa(): U1 constants demoted to fallbacks; geometry from JNI config
  provenance: snapmaker_authored_profile marker (heuristic fallback)
  profile_keys[]: applies for ANY app-authored embed; coverage enforced by test
```

## Reuse map

| Source | Licence | What we take |
|---|---|---|
| OrcaSlicer `resources/profiles` | AGPL-3.0 | The profiles themselves (fetch, not bundle); vendor index schema; `machine_model` metadata (bed STL/texture, nozzle list) |
| OrcaSlicer `PresetUpdater` design | AGPL-3.0 | OTA flow: vendor `version` compare → per-vendor zip → snapshot → apply (reimplemented in Kotlin) |
| OrcaSlicer `src/slic3r/Utils/*.cpp` | AGPL-3.0 | OctoPrint/PrusaLink request shapes ported to OkHttp |
| PrusaSlicer physical-printer schema | AGPL-3.0 | `host_type`/`print_host`/`printhost_*` persistence keys |
| OrcaSlicer-Mobile `IOUtils.downloadProfilesRecursively` | AGPL-3.0 | Recursive `inherits` download idea + vendor-inference from preset-name conventions (re-written in Kotlin) |
| SliceBeam / OrcaSlicer-Mobile `SetupActivity` | AGPL-3.0 | Wizard UX flow: vendor → model → variant → confirm |
| upstream `scripts/orca_extra_profile_check.py` | AGPL-3.0 | CI profile validation |
| ha-bambulab, OpenBambuAPI, SDCP spec | MIT / GFDL / none | Bookmarked for phase 6+ only |

## Risks

1. **Native regression on the U1 path** while demoting literals to fallbacks. Mitigation: phase 1 changes are behaviour-preserving for U1 by construction (fallback values equal today's literals; JNI config for U1 sends the same 270/0.4/4 values), guarded by the full 451-test instrumented sweep + confidence-check before each phase merges.
2. **Upstream profile keys unknown to Snapmaker Orca 2.2.4** (fork predates upstream 2.4.x keys; newer `change_filament_gcode` placeholder syntax). Mitigation: pin to `v2.2.0` (D2); wizard-fetch validation step slices a 1-layer cube with the fetched profile before saving it; CI fixture per family.
3. **Marlin flavour is genuinely new** for this engine build (`gcode_flavor=klipper` suppresses body M104/M109 — Marlin needs them emitted). Mitigation: dedicated phase-4 fixtures asserting `M104`/`M109` presence for a Marlin profile and absence of Klipper macros (`EXCLUDE_OBJECT_*`, `SET_*`).
4. **Scope creep via the wizard** (thumbnails, filament libraries per vendor). Mitigation: wizard v1 shows text lists only; vendor cover PNGs are explicitly deferred.
5. **`BambuSanitizer` off-bed heuristics** use 270-literals to classify plates; changing them can shift multi-plate detection for existing U1 users. Mitigation: heuristics stay 270-based for Bambu-authored files (they describe *Bambu* bed conventions, ≤256 mm) — they are parameterised to the **source file's** bed size, not the target printer's, with regression fixtures pinned.
