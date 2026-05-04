# Code Review — B99 Support/Interface Filament Branch

**Date:** 2026-05-03
**Branch:** working tree on `main` (no commit yet)
**Reviewer:** Claude (Opus 4.7, 1M ctx)
**Scope:** native (`sapil.h`, `sapil_config.cpp`, `sapil_print.cpp`), data layer (`SliceConfig`, `SlicingOverrides`, `PerFilamentResolver`), `SlicerViewModel` slicing path, `MainActivity` slice summary, `SlicingOverridesUI`, all new and updated unit/instrumented tests, plus three docs (`AGENTS.md`, `BACKLOG.md`, `CLAUDE.md`, `README.md`).

---

## Overview

The B99 user-facing summary fix (Slice Summary hiding the support extruder behind "Filament 1 · PLA") is plausibly correct: the native + JNI plumbing is wired symmetrically, the post-slice header patch and chip fallback do roll over to raw G-code slots when the slicer emits more active extruders than the canonical list, and there is solid integration coverage. But several issues fall out around it — most notably a test regression in the Bambu fixture harness, a header-truncation hazard for Bambu files with support beyond canonical, a priority swap in `PerFilamentResolver` whose docs lag the code, and a UTF-8 encoding regression on `PerFilamentResolver.kt`.

---

## High

### H1. `BambuFixtureHarnessTest` substantively weakens multi-tool coverage

**Files:** [`BambuFixtureHarnessTest.kt:112-216`](../../../app/src/androidTest/java/com/u1/slicer/slicing/BambuFixtureHarnessTest.kt#L112-L216)

The harness now passes `SliceConfig()` (`extruderCount=1`, no `extruderTemps`, no wipe tower) and drops `sourceConfig` from `embedder.buildConfig`. With single-extruder slicing, `T1`/`T2`/`T3` in `expectedToolCounts` collapse toward zero, and most spec entries (e.g., `colored-benchy.json` `T1=5,T2=3,T3=2,tol=5`) become satisfied trivially because `|0 − expected| ≤ tolerance`. Only `dragon-scale-plate3.json` (`T2=53,T3=90,tol=10`) and `slip-slide-spin-plate3.json` (`T2=50,T3=26,tol=10`) would still meaningfully assert multi-tool output — and they only pass if the per-volume extruder mapping in the embedded model still drives multi-tool output despite the missing `sourceConfig`, i.e. the test now silently depends on a property it no longer asserts. The diagnostic `Log.i("FixtureHarness", "ACTUAL …")` baseline-helper was also removed.

`BACKLOG.md` frames this as a "release-equivalent restoration" with the Shashibo crash documented as a separate blocker, but the change still violates `CLAUDE.md`'s **"NEVER weaken a test assertion to make a failing test pass"** rule.

**Fix options:**
- Re-introduce the multi-extruder slice config behind a per-fixture flag (`useMultiExtruderSlice: true` in spec JSON), keep release-equivalent for the Shashibo entry only.
- Update the spec JSONs and tolerances to match the actually-asserted single-tool reality, and rename the field so future readers understand it's not multi-tool coverage.

---

### H2. `fixFilamentTypeHeader` truncates Bambu G-code metadata when `support_filament > canonical.size`

**Files:** [`SlicerViewModel.kt:3000-3025`](../../../app/src/main/java/com/u1/slicer/SlicerViewModel.kt#L3000-L3025), interacting with [`SlicerViewModel.kt:5026-5028`](../../../app/src/main/java/com/u1/slicer/SlicerViewModel.kt#L5026-L5028)

`buildProfileOverridesImpl` sizes `filament_type` to `effectiveFilamentCount = max(filamentCount, supportFilament, supportInterfaceFilament)`, so the embedded profile + native slice produce a header e.g. `; filament_type = PLA;PLA;PETG;PETG`.

The post-slice patch path then computes `ftTypes` via `resolveFilamentTypesForHeaderPatch`, which is sized to `canonical.size`. The guard

```kotlin
!(canonicalForPatch.size <= 1 && supportDrivenSlotCount > canonicalForPatch.size)
```

only handles the degenerate `canonical.size <= 1` case. For a 2- or 3-color Bambu file with `support_filament` / `support_interface_filament` beyond canonical (entirely possible after Phase 2.7 — user has 4 slots), the patch overwrites the wider header with `canonical.size` entries, dropping the support material from the metadata that the Slice Summary parses.

**This is the opposite of the B99 intent.**

**Fix:** size the header patch to the same `effectiveFilamentCount` used during embedding — i.e., compute `padTo = max(canonical.size, supportFilament, supportInterfaceFilament)` and pad with slot-preset materials beyond `canonical.size`.

---

### H3. PerFilamentResolver priority swap is intentional but stale comments mislead future readers

**Files:**
- [`PerFilamentResolver.kt:9-29 and 50-58`](../../../app/src/main/java/com/u1/slicer/data/PerFilamentResolver.kt#L9-L58)
- [`SlicerViewModel.kt:2363-2371`](../../../app/src/main/java/com/u1/slicer/SlicerViewModel.kt#L2363-L2371)
- [`MainActivity.kt:2382`](../../../app/src/main/java/com/u1/slicer/MainActivity.kt#L2382)
- [`PerFilamentResolverTest.kt:124-125`](../../../app/src/test/java/com/u1/slicer/data/PerFilamentResolverTest.kt#L124-L125)

Resolution order changed from `override → canonical → preset → "PLA"` to `override → preset → canonical → "PLA"`. This is consistent with Phase 2.6 design ("Prepare row mirrors slot preset"), but multiple call sites still describe the old order, and one test even *asserts* a result with stale reasoning:

- `SlicerViewModel.kt:2363-2371` — KDoc on `buildPerFilamentTypeAndTemp` still says "2. Canonical entry's declared materialType … 3. The mapped slot's preset materialType". Wrong.
- `MainActivity.kt:2382` — comment "Material priority: override → canonical → slot preset" but the code below it does `override ?: mappedMaterial ?: canonicalEntry?.materialType`. Wrong.
- `PerFilamentResolverTest.kt:124-125` — comment claims "fileIdx 0 → PETG (from canonical)" but in the new test the canonical is PLA and the PETG comes from the slot preset. The assertion happens to be right; the comment documents it wrong.

This is the kind of priority change that absolutely needs the docs to keep up, because the cascade-free claim hinges on it.

---

### H4. `PerFilamentResolver.kt` was rewritten with a UTF-8 BOM and double-encoded mojibake

**File:** [`PerFilamentResolver.kt`](../../../app/src/main/java/com/u1/slicer/data/PerFilamentResolver.kt)

Verified via byte inspection: file now starts with `EF BB BF` (UTF-8 BOM), and several block-comment lines contain double-encoded UTF-8 (`â€"` for `—`, `Â§` for `§`). Examples on lines 6, 10, 14, 18, 27, 29, 32, 35.

Kotlin tolerates the BOM and the corruption is in comments, so this should compile cleanly, but it's an obvious editor / transcoding regression that other tooling (git's diff display, IDE blame, grep) will misrender.

**Fix:** re-save as UTF-8 (no BOM) with proper em-dashes and `§`. No other file in the diff has this problem.

---

## Medium

### M1. `buildSupportFilamentOptions` configValue collides with embedded filament_type slots when colorMapping is sparse

**Files:** [`SlicingOverridesUI.kt:842-866`](../../../app/src/main/java/com/u1/slicer/ui/SlicingOverridesUI.kt#L842-L866) and tests at [`SupportFilamentOptionTest.kt:54-64`](../../../app/src/test/java/com/u1/slicer/ui/SupportFilamentOptionTest.kt#L54-L64)

Walking the test `non identity mapping translates physical slots to first matching file filament` (`colorMapping=[2,0,1]`, `filamentCount=3`):

- slot 0 → label "E1·…", `configValue=2` → OrcaSlicer reads `filament_type[1]` = canonical[1] = mapped to slot 0 ✓
- slot 1 → label "E2·…", `configValue=3` → reads `filament_type[2]` = canonical[2] = mapped to slot 1 ✓
- slot 2 → label "E3·…", `configValue=1` → reads `filament_type[0]` = canonical[0] = mapped to slot 2 ✓

That is consistent for fully-mapped cases. **But** consider `colorMapping=[0,2]` (slot 1 unused):

- slot 1 preferredValue = 2 (`indexOfFirst{it==1}=-1` → null, `null ?: 1` + 1 = 2)
- slot 2 preferredValue = 2 (`indexOfFirst{it==2}=1`, +1 = 2) — collision

Slot 1 wins because it iterates first; slot 2 falls through to the synthetic branch and gets `configValue=3` (`minimumSynthetic = filamentCount + 1 = 3`). User picks "E2·…" → `support_filament=2` → `filament_type[1]` = canonical[1] = **slot 2's** material, NOT slot 1's. The label shows the wrong slot.

No test exercises this. Worth either guarding (skip label for unmapped slots, or relabel) or covering with a test that asserts label-to-slot identity, not just configValue uniqueness.

---

### M2. STL multi-extruder slicing path has no auto-prime-tower

**Files:** [`SliceConfig.kt`](../../../app/src/main/java/com/u1/slicer/data/SliceConfig.kt), [`SlicerViewModel.kt:2911-2927`](../../../app/src/main/java/com/u1/slicer/SlicerViewModel.kt#L2911-L2927)

For an STL, `_config.value.extruderCount` is set to 1 at load, so `resolveInto`'s wipe-tower auto-enable (`if (base.extruderCount > 1 …)`) never fires. The new code then bumps `extruderCount` to `effectiveExtruderCount = max(1, supportFilament, supportInterfaceFilament)` *after* `resolveInto`, so the user can ship a multi-extruder STL slice with `wipe_tower_enabled=false`. Native `applyConfigToPrusa` then sets `enable_prime_tower=false` and OrcaSlicer produces tool changes with no purge.

The new `tetrahedron_stl_supportAndInterfaceFilaments_appearInGcode` test does not exercise the wipe-tower side of this; the other STL B99 tests all explicitly pass `wipeTowerEnabled=true`.

**Fix options:**
- Bump `wipeTowerEnabled = true` in the same `cfg.copy` that bumps `extruderCount`, OR
- Add a pre-slice validation that refuses to slice with effectiveExtruderCount > 1 unless wipe tower or skirt is configured, OR
- At minimum, add a test that an STL with `support_filament=2` and no explicit wipe-tower override still produces a usable purge sequence.

---

### M3. `applyConfigToPrusa` writes a uniform filament_type vector for STL even when extruderCount > 1

**File:** [`sapil_print.cpp:482-487`](../../../app/src/main/cpp/src/sapil_print.cpp#L482-L487)

For STL, `n_ext` follows `config.extruder_count` (now potentially 2-4 after the auto-bump), and:

```cpp
std::vector<std::string> ftypes(n_ext, config.filament_type);
dpc.set_key_value("filament_type", new Slic3r::ConfigOptionStrings(ftypes));
```

broadcasts a single material to every slot. The post-slice header patch corrects the metadata in the comment, but OrcaSlicer's flow / cooling / retraction selection has already happened against the pre-patch uniform vector.

The B99 user report was UI-display-only, so this might be acceptable today, but the new tests assert only header-comment substrings (`gcode.contains("nozzle_temperature = 220,235,235")`, etc.) and don't notice that PETG support is being extruded with PLA fan/flow/retraction tuning. Worth at least documenting; ideally pass a per-slot `filament_type` vector through JNI for STL by reading from `extruderPresets`.

---

### M4. New SEMM/Leo tests only verify metadata strings

**Files:**
- [`SemmSlicingTest.kt:200-340`](../../../app/src/androidTest/java/com/u1/slicer/slicing/SemmSlicingTest.kt#L200-L340)
- [`BambuPipelineIntegrationTest.kt:159-235`](../../../app/src/androidTest/java/com/u1/slicer/slicing/BambuPipelineIntegrationTest.kt#L159-L235)

The new B99 instrumented tests assert `gcode.contains("support_filament = 3")`, `… = 4`, and `nozzle_temperature = 220,220,235,235`. They do **not** assert that there are actually `T2`/`T3` blocks of motion in the file (i.e., supports are *generated* and assigned to those tools).

Combined with M3, a regression that silently zeroed support extrusion in the slicer would still satisfy these substring checks.

**Fix:** pair the existing assertions with a `gcode.lines().count { it.trim() == "T2" } > 0` check, or with `; filament used [mm] = a,b,c,d` parsing that asserts the support extruder slot has `> 0`.

---

### M5. `benchy_stl_appPlaced_supportPetgE2_interfacePetgE3_slicesSuccessfully` substring assertion is loose

**File:** [`SlicingIntegrationTest.kt:891-907`](../../../app/src/androidTest/java/com/u1/slicer/slicing/SlicingIntegrationTest.kt#L891-L907)

`gcode.contains("nozzle_temperature = 220,235,235")` is satisfied by `; nozzle_temperature = 220,235,235,220` *and* by `; nozzle_temperature_initial_layer = 220,235,235,220` and any other occurrence. The test uses `extruderCount=4` and `extruderTemps = [220,235,235,220]`. If the slicer one day emits only `n_ext=3` of these, this test would still pass even though the 4th slot was dropped.

**Fix:** anchor the assertion with a leading `;` and the trailing slot count expected:

```kotlin
val matched = gcode.lineSequence().any { it.trim() == "; nozzle_temperature = 220,235,235,220" }
assertTrue("…", matched)
```

---

### M6. Footer-driven `computePlateFileIndices` returns null whenever a single STL slot is wider than canonical, even when the prefix is in-range

**File:** [`MainActivity.kt:927-932`](../../../app/src/main/java/com/u1/slicer/MainActivity.kt#L927-L932)

The change is `if (nonZero.any { it >= canonicalSize }) return null`, which is the correct B99 behavior. **But** it also defeats the slicer's own canonical narrowing for any future case where the footer's first 4 entries are valid (`fileIdx < canonicalSize`) and a stray 5th slot has tiny non-zero output (e.g., calibration purge). For canonical=4 with footer `[100,100,100,100,5]`, this returns null instead of `[0,1,2,3]`.

The fallback path in `SliceCompleteSummaryCard` handles null correctly, so this is mostly cosmetic, but the comment at line 911 still claims the function returns the in-range subset; current behaviour returns null for any out-of-range entry.

**Fix:** either limit the guard to the STL case (`canonicalSize <= 1`) or update the comment to document that any out-of-range slot triggers the raw-slot fallback for everything.

---

## Low / Documentation

### L1. KDoc inside `SlicerViewModel.buildPerFilamentTypeAndTemp` and the `Phase 2.7` docstring

As called out in H3, both still document the old priority order. Fix when fixing H3.

---

### L2. `BACKLOG.md` B99 entry references "filament 1" UI symptom but the diff only addresses post-slice summary

**File:** [`BACKLOG.md:7-9`](../../../BACKLOG.md#L7-L9)

The TPU-in-tool-3-shows-Filament-1 symptom and the support-filament-type-not-exposed-in-UI symptom are still listed as open. The branch closes the Slice Summary chip case only.

Worth noting in the commit/PR that B99 symptom #1 (Prepare-screen filament identity for material overrides) is **not** addressed.

---

### L3. `Leo_test_Supports.3mf` is untracked

`git status` shows `?? app/src/androidTest/assets/Leo_test_Supports.3mf` and the new `BambuPipelineIntegrationTest.leoSupport_plate1_…` test depends on it. CI on a clean checkout will fail with a missing-asset error until this file is `git add`ed.

---

### L4. `SupportFilamentOptionTest` does not assert the "Default" entry

**File:** [`SlicingOverridesUI.kt:414-418`](../../../app/src/main/java/com/u1/slicer/ui/SlicingOverridesUI.kt#L414-L418)

The "Default" sentinel is prepended to options in the UI but the test never asserts that the "Default" entry stays at index 0 or that its config value is 0. Trivial gap.

---

### L5. `BambuFixtureHarnessTest` KDoc warns the harness "passes the value directly to ProfileEmbedder as plateId"

**File:** [`BambuFixtureHarnessTest.kt:30-37`](../../../app/src/androidTest/java/com/u1/slicer/slicing/BambuFixtureHarnessTest.kt#L30-L37)

This is a deliberate revert to release behavior, but a future contributor reading the spec JSON will see `"plateIndex": 4` for Shashibo plate 5 and reasonably assume it is 0-based.

**Fix options:**
- Rename the field in the spec JSONs to `plateId` to match what's being passed, OR
- Add a comment in each spec JSON explaining the mismatch.

---

### L6. `ConfigCard` defaults `extruderPresets = emptyList()` and `colorMapping = null`

**File:** [`MainActivity.kt:1947-1950`](../../../app/src/main/java/com/u1/slicer/MainActivity.kt#L1947-L1950)

If any caller forgets to thread these through, the support-filament dropdown silently shows "E1·PLA … E4·PLA" regardless of the user's actual slots. Searched for callers and only `PrepareScreen` and `SettingsScreen` exist; the latter has no model loaded so empty defaults are correct. Just an observation — consider non-defaulting these to surface wiring issues.

---

### L7. `CLAUDE.md` test counts are now self-consistent

(1016 unit / 288 instrumented). The `BambuFixtureHarnessTest (6)` description was rewritten to drop the per-fixture extruder count list it used to enumerate. Acceptable in light of H1; just noting it.

---

## Areas that still need manual or regression testing

- **H1 / H2 verification:** a Bambu fixture with `canonical.size = 2` + user-set `support_filament = 4` — confirm the post-slice header still has 4 entries (this would catch H2). Add a unit test for `resolveFilamentTypesForHeaderPatch` that's sized to support filament index, not canonical, when the support filament exceeds canonical.
- **M2:** STL with `supportFilament = 2` and `wipeTowerEnabled = false` (default) — confirm OrcaSlicer doesn't reject the slice and that tool-change G-code is sane.
- **M4:** re-run the new SEMM and Leo tests with stronger assertions on per-tool extrusion length (footer `; filament used [mm] = a,b,c,d`) before declaring B99 closed.
- **Shashibo plate 5 direct-harness crash** documented in `BACKLOG.md` is still a current-branch failure that the harness regression at H1 partially hides. The new `PreparePreviewViewModelTest.shashiboPlate5_…` test validates Prepare state but does not slice, so it does not protect against that specific regression.
- **Bambu `Leo_test_Supports.3mf` slicing on physical printer:** the new instrumented test checks header strings only — confirm with a real send-and-upload (not Send & Print) that the tool changes for E3/E4 are present and that the wipe tower geometry matches.

---

## What I did **not** find an issue with

- The native plumbing of `support_filament` / `support_interface_filament` through `SliceConfig` ↔ JSON ↔ `SliceConfig (C++)` ↔ DPC is symmetric. The 1-based-index discipline holds. Both keys are correctly listed in the `profile_keys[]` whitelist for the embedded-profile path.
- `ComputePlateFileIndicesTest`'s new STL fallback test correctly captures the B99 root cause and the change to `computePlateFileIndices` is minimal and consistent with the comment (modulo M6).
- The `effectiveFilamentCount` extension of `filament_type` / `nozzle_temperature` / `filament_colour` in `buildProfileOverridesImpl` is internally consistent with the SlicingOverridesTest assertions for `support_filament=4` (4-entry array) and `support_interface_filament=3` (3-entry array).
- `SlicingOverrides.resolveInto` correctly carries supportFilament/supportInterfaceFilament into `SliceConfig`, with the override taking precedence and `ORCA_DEFAULTS` providing 0 fallbacks.
- The new `PreparePreviewViewModelTest.shashiboPlate5_selectPlate_appPathLoadsMultiExtruderPreparePreview` is a sound replacement for the slicing-based Shashibo coverage at the Prepare layer (though it does not catch slicing regressions, see "Areas still needing testing").

---

## Suggested fix order before regression run

1. **L3** — Add `Leo_test_Supports.3mf` to git. Blocks CI.
2. **H4** — Fix the encoding regression in `PerFilamentResolver.kt`. Trivial, cosmetic but visible.
3. **H3 / L1** — Fix the stale priority docs in `SlicerViewModel.kt`, `MainActivity.kt`, and `PerFilamentResolverTest.kt`. Same edit pass as H4.
4. **H1** — Decide: either restore the prior multi-extruder slice config in `BambuFixtureHarnessTest`, or document explicitly in the harness JSONs that they're now single-tool-tolerant.
5. **H2** — Size the header patch to the same `effectiveFilamentCount` used during embedding.
6. **M4 / M5** — Tighten substring assertions before treating B99 as closed.

---

*Generated 2026-05-03 against working tree on `main` (untracked `Leo_test_Supports.3mf`, `SupportFilamentOptionTest.kt`).*
