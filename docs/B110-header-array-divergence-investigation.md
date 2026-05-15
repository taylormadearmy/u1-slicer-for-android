# B110 investigation — `filament_type` / `nozzle_temperature` header arrays use different slot-indexing conventions for canonical multi-colour files

**Status:** Open. Pre-existing as of v2.1.2 (verified). Not blocking v2.2.0 release. Tracked separately because the two emitted header arrays disagree on which physical slot received a Prepare-screen override, which is a foot-gun.

**Severity:** Medium. No reported real-world misprint yet (printers likely drive temperature from per-tool `M104 T<n> S<temp>` commands sprinkled through the G-code body rather than from the header arrays). But the two arrays *should* agree, and any downstream consumer of the header would be misled.

## Reproduction

1. Install a current debug APK (`./gradlew installDebug`) on the test device.
2. Push the H2C fixture into app-private storage:
   ```bash
   adb -s 43211JEKB16931 shell run-as com.u1.slicer.orca cp /sdcard/Download/3DBenchy-H2C-Multi-Color.3mf files/h2c.3mf
   ```
   (or push from the repo's `app/src/androidTest/assets/3DBenchy-H2C-Multi-Color.3mf`)
3. Launch the app, load via `adb shell am broadcast -a com.u1.slicer.orca.LOAD_FILE --es path /data/data/com.u1.slicer.orca/files/h2c.3mf -p com.u1.slicer.orca`.
4. On the Prepare screen, tap **Filament 1** → material picker → choose **PETG**. Confirm the chip now shows PETG @ 235°C.
5. Slice. Wait for completion.
6. Read the latest G-code's headers:
   ```bash
   adb shell run-as com.u1.slicer.orca grep -E '^; (filament_type|nozzle_temperature) =' files/jobs/<N>/output.gcode
   ```

**Observed output (current, and v2.1.2):**

```
; filament_type      = PLA;PLA;PETG;PLA;PLA;PLA;PLA
; nozzle_temperature = 235,220,220,220,220,220,220
```

**Why this is wrong:** The two arrays disagree on which slot index received the PETG override.

- `filament_type` claims slot index **2** is PETG.
- `nozzle_temperature` claims slot index **0** got 235°C (the PETG temperature).

A downstream consumer that joins the two lists by index would see contradictory rows: e.g. "slot 0 is PLA but targeted at 235°C" and "slot 2 is PETG but targeted at 220°C". They must agree on a single indexing convention.

## What's verified so far (don't re-do)

- Both v2.1.2 (released) and v2.2.0 (current `main`) produce the same divergence. Captured in `c:/tmp/e2e-results/baseline-v2.1.2-h2c-p2.1.txt` (v2.1.2) and `c:/tmp/e2e-results/balance-04-h2c-benchy-phase2.txt` (v2.2.0). So the bug is pre-existing — bisecting recent commits is not productive.
- `PerFilamentResolver.kt` has zero commits since v2.0.1 (`git log v2.0.1..HEAD -- app/src/main/java/com/u1/slicer/data/PerFilamentResolver.kt` returns empty).
- B105/B106 modified `buildProfileOverrides` `slotTypes` derivation, but only for the **non-canonical** branch. H2C benchy is canonical (7-entry filament list) so it goes through `perFilamentArrays = buildPerFilamentTypeAndTemp(...)`, not through the B105-touched path.
- Source files that participate: `SlicerViewModel.kt` (`buildProfileOverrides` wrapper and the post-slice `fixFilamentTypeHeader` patch), `data/PerFilamentResolver.kt` (`buildPerFilamentTypeAndTemp` — feeds both `finalTypes` and `finalTemps` for canonical files), and the post-slice header patch helper `resolveFilamentTypesForHeaderPatch` (B102 — physical-slot-indexes `filament_type` only).

## Suspected root cause (a hypothesis, not verified)

`filament_type` is rewritten **post-slice** by `fixFilamentTypeHeader` using `resolveFilamentTypesForHeaderPatch` (the B102 change that physical-slot-indexes it). `nozzle_temperature` has **no equivalent post-slice patch** — it stays exactly as the embedded profile said, which uses canonical/UI ordering. So the two arrays end up in different coordinate systems:

- `filament_type` → physical-slot-indexed (post B102)
- `nozzle_temperature` → canonical/UI-indexed (whatever `buildPerFilamentTypeAndTemp` produced for the embedded profile)

If this hypothesis is right, the fix is one of:

- **Option A** (preferred, simpler): apply the same physical-slot reordering to `nozzle_temperature` in a post-slice patch — extend `fixFilamentTypeHeader` (or add a sibling `fixNozzleTemperatureHeader`) that takes the same physical-slot mapping B102 already computes. Both header arrays end up physical-slot-indexed.
- **Option B**: revert `filament_type` to canonical/UI ordering and undo B102's post-slice patch. Both arrays end up canonical-indexed. Likely more disruptive — B102 was added because OrcaSlicer downstream code reads `filament_type` by physical slot.
- **Option C**: change `buildPerFilamentTypeAndTemp` to produce both arrays physical-slot-indexed from the start, then drop B102's post-slice patch as redundant. Cleanest, but the largest blast radius — make sure no other call site depends on canonical-indexed temps.

The verifier needs to confirm which array is "correct" from OrcaSlicer's perspective by inspecting what the upstream slicer C++ does with each, then align the other.

## Suggested starting points

- Read `SlicerViewModel.kt:2380-2456` — see how `slotTypes`, `slotTemps`, and `perFilamentArrays` flow into `buildProfileOverridesImpl`.
- Read `SlicerViewModel.kt:3060-3100` — the post-slice patch site for `filament_type`. Note the absence of an equivalent block for nozzle temps.
- Read `data/PerFilamentResolver.kt` — `buildPerFilamentTypeAndTemp`. Determine whether the two returned lists are produced in the same order or different orders.
- Read `SlicerViewModel.kt:5060-5170` — `buildProfileOverridesImpl` (the function that actually emits both header keys into the override map).
- Check `app/src/main/cpp/src/sapil_print.cpp` — `profile_keys[]` and `applyConfigToPrusa()`. Confirm what OrcaSlicer C++ expects for both keys' indexing.

## Acceptance criteria

1. After Prepare-screen Filament 1 → PETG on H2C benchy, the emitted G-code has both arrays agreeing on PETG's slot index. E.g. either both at index 0 (canonical/UI) or both at index 2 (physical) — but consistent.
2. New unit test: `app/src/test/java/com/u1/slicer/.../HeaderArrayAlignmentTest.kt` (or extend `FilamentTypeHeaderPatchTest`). Inputs: H2C-shaped canonical filament list + per-filament override on file-filament 0 → PETG. Assert that the PETG slot index in the produced `filament_type` equals the 235°C slot index in `nozzle_temperature`.
3. New instrumented test (preferred location: `SemmSlicingTest.kt` or `BambuPipelineIntegrationTest.kt`): slice H2C benchy with Filament 1 = PETG, grep the two header lines, assert index alignment.
4. Existing tests still pass:
   - `FilamentTypeHeaderPatchTest` (B63 / B102 / B105 guards) — must still pass.
   - `SlicingIntegrationTest#v1_5_63_nozzleTempJniPath` — nozzle temp JNI path must still pass.
   - `SlicingIntegrationTest#b105_singleSlotStl_headerArraysOneElement` — single-slot STL guard.
   - `SemmSlicingTest` H2C tool-count assertions.
5. E2E re-run of the H2C P2.1 scenario shows aligned arrays (replaces the current pre-existing caveat row in `e2e-results-history.md`'s v2.2.0 balance table).

## What NOT to do

- Don't change OrcaSlicer C++ to "read both arrays differently" — that's papering over the Kotlin-side inconsistency.
- Don't weaken test assertions to make a failing test pass. If a fix breaks `FilamentTypeHeaderPatchTest`, investigate why — the test documents B63/B102 contract.
- Don't extend B105's non-canonical branch handling. H2C benchy is canonical; the fix is in the canonical path.
- Don't rely on the device-side `M104` body commands as justification for not fixing the headers. Even if real prints currently work, the headers are a contract.

## BACKLOG / issue housekeeping

When the fix is ready:

1. Add a `B110: filament_type / nozzle_temperature header slot-indexing divergence` entry under `## Open Bugs` in `BACKLOG.md` matching the format of B105–B109.
2. Create the matching GitHub issue (per CLAUDE.md BACKLOG↔issue sync rule) and cross-link.
3. Update `memory/e2e-results-history.md` — remove the "FAIL† pre-existing" caveat on the v2.2.0 balance row and add a new row confirming the H2C P2.1 alignment after fix.

## Test fixtures

- Primary: `app/src/androidTest/assets/3DBenchy-H2C-Multi-Color.3mf` — 7-state H2C canonical, the canonical repro.
- Secondary (worth re-checking for regression): `Dragon Scale infinity.3mf`, `Flarewing-Dragon_100%_4FilamentMulticolor_v1.1.3mf` — multi-colour canonical Bambu files. Apply a similar override and check header alignment.
- Negative control: `3DBenchy.stl` and `calib-cube-10-dual-colour-merged.3mf` — non-canonical paths; arrays should remain unchanged after the fix (B105 single-slot STL guard must still hold).

## Native rebuild

Probably not needed — both header arrays are emitted from Kotlin into the embedded profile, then `filament_type` is rewritten by a Kotlin post-slice patch. If the fix turns out to require a C++ change (e.g. confirming `profile_keys[]` reads `nozzle_temperature` in the order expected), follow the canonical rebuild procedure in `CLAUDE.md` (NDK 26 / Clang 17 / Release / verify size + symbol count). Don't ship a Debug `.so`.
