# 2026-04-28 — G-code differential vs v1.6.13

After Phase 2's structural native fix (`1e95c7d`) added per-filament tuning
keys to `profile_keys[]` and gated `applyConfigToPrusa` writes, an E2E sanity
check requested by the user surfaced print-quality regressions. This
document captures the findings and the gate-narrowing decision.

## Method

Reusable harness `GcodeBaselineDiffTest` slices 5 representative fixtures
end-to-end through the production SlicerViewModel pipeline and dumps
structured snapshots (~60 watched config keys + start/end/change_filament/
pause G-code templates + body head/tail + first toolchange sequence) to
`/sdcard/Download/u1-slicer-baselines/`.

Captured on **HEAD** (post-`fee8493`) and on **v1.6.13** (`26e6cf2`)
sequentially. Both APKs built with same toolchain (NDK 26 / Clang 17.0.2 /
Release / 20.79 MB stripped).

Fixtures:

1. `3DBenchy.stl` — STL, single-extruder, no embed
2. `calib-cube-10-dual-colour-merged.3mf` — Bambu dual-colour
3. `colored_3DBenchy (1).3mf` — SEMM paint, multi-colour
4. `flippy+flappy+mini.3mf` plate 4 — layer-tool / Hueforge
5. `PrusaSlicer-printables-Korok_mask_4colour.3mf` — PrusaSlicer 3MF

## Findings (initial state, post-`1e95c7d` + structural fix)

**Templates (start/end/change_filament/pause G-code):** byte-identical
across all 4 multi-colour fixtures. ✅ No regression in the print-template
layer.

**Per-filament header values:** material-tuned values diverge significantly
between v1.6.13 and HEAD because the structural fix added them to
`profile_keys[]`, allowing embedded Bambu/Prusa profile values to flow
through to the slicer instead of U1's hardcoded defaults.

| Fixture | Key | v1.6.13 | HEAD (post-`1e95c7d`) | Print impact |
|---------|-----|---------|----------------------|--------------|
| calibCube | `hot_plate_temp` | 65 | 60 | Lower bed adhesion risk |
| calibCube | `hot_plate_temp_initial_layer` | 70 | 60 | Lower first-layer adhesion |
| calibCube | `fan_max_speed` | 100 | 80 | Less cooling on overhangs |
| calibCube | `fan_min_speed` | 100 | 60 | Less cooling generally |
| calibCube | `slow_down_layer_time` | 4 | 6 | Slower at thin layers |
| calibCube | `filament_density` | 1.24 | 1.26 | Estimate only (cosmetic) |
| coloredBenchy | `hot_plate_temp_initial_layer` | 70 | 65 | Marginal bed adhesion |
| coloredBenchy | `filament_max_volumetric_speed` | 21 | 20 | Slightly slower max flow |
| flippy plate 4 | sliced as | 1-extruder | 5-extruder + wipe tower | **Major mode change** |
| flippy plate 4 | TPU at idx 4 | absent | 225°C nozzle / 35°C bed / 3.2 mm³/s | Wrong if user is single-PLA |
| Korok | `hot_plate_temp` | 65 | 45 | **Cool-plate temp on hot-plate U1 — will fail adhesion** |
| Korok | `flush_multiplier` | 0.3 | 1 | **3.3× more purge waste** |
| Korok | `flush_volumes_matrix` | 280 mm³ calibrated | 140 mm³ flat | Less calibrated transitions |

**STL** (3DBenchy single-extruder): no header diff; only an X-coordinate
offset of 0.825 mm in the body (positioning, not print-impact). ✅

## Gate-narrowing decision

The user's only Phase 2-exposed override surface today is **material type**.
Material type drives `nozzle_temperature` (PETG=235, PLA=220, etc.). It
doesn't yet drive bed temp, fan, volumetric speed, retraction etc. — those
material-correlated values are NOT exposed to the user in the Prepare UI.

Letting embed values flow through for the un-overrideable keys means a user
loading a Bambu-prepared 3MF on a U1 silently gets:

- Cool-plate bed temps (45°C) when their plate is hot-plate (65°C) → bed
  adhesion failure
- 60-80% fan when PLA needs 100% on U1 → stringing / poor overhang quality
- 3× higher purge volumes from PrusaSlicer calibration → filament waste
- Lower volumetric speed from a different printer's flow ceiling →
  unnecessary throttle
- Hueforge plate 4 sliced as multi-extruder when intent was layer-tool +
  pause-print → wrong print mode entirely

These would all be silent regressions vs v1.6.13. The user is using v1.6.13
in production today; the structural fix's "respect embed values" semantic
is correct ONLY for keys the user can override. For keys the user can't
override, U1 hardware defaults are the correct floor.

**Resolution (commit `<TBD>`):**

- `profile_keys[]` and the applyConfigToPrusa gate are narrowed to ONLY
  `nozzle_temperature` and `nozzle_temperature_initial_layer`.
- All other per-filament keys (`hot_plate_temp*`, `fan_*`, `slow_down_*`,
  `filament_max_volumetric_speed`, `filament_density`,
  `overhang_fan_speed`, plus the previously already-gated retraction keys)
  go back to U1 hardware defaults via applyConfigToPrusa, identical to
  v1.6.13.
- Phase2AlignmentTest's cascade detector key list narrows to match.
- The cascade-detector contract still holds for nozzle_temperature: a PETG
  override on Filament 1 still produces `nozzle_temperature[0] = 235` in
  the G-code header. The gcode differential is what should converge to
  zero per-key diffs on the un-overrideable keys.

When future Phase 2 work adds additional override UI (e.g. per-filament
bed temp, per-filament retraction tuning), the corresponding keys can be
re-added to `profile_keys[]` + gate, and the differential test re-run to
verify no regression for files NOT using the new override.

## Permanent test

`GcodeBaselineDiffTest` is committed for future regression checks. To
re-run after a major refactor:

```bash
# Capture baseline (last known good build)
git checkout <baseline-tag>
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell rm -rf /sdcard/Download/u1-slicer-baselines
ANDROID_SERIAL=<id> ./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class="com.u1.slicer.slicing.GcodeBaselineDiffTest"
adb pull /sdcard/Download/u1-slicer-baselines /tmp/gcode-snapshots/baseline

# Capture candidate
git checkout <candidate-branch>
# (rebuild .so per CLAUDE.md if native source changed)
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell rm -rf /sdcard/Download/u1-slicer-baselines
ANDROID_SERIAL=<id> ./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class="com.u1.slicer.slicing.GcodeBaselineDiffTest"
adb pull /sdcard/Download/u1-slicer-baselines /tmp/gcode-snapshots/head

# Diff
for f in /tmp/gcode-snapshots/baseline/*.txt; do
  fn=$(basename $f)
  diff -u /tmp/gcode-snapshots/baseline/$fn /tmp/gcode-snapshots/head/$fn > /tmp/diff-$fn.txt
done
```

Diffs in **header values** are real config changes — review each. Diffs in
**body coordinates / extrusion amounts** are positioning/geometry changes
— usually slicer-version drift, not print-impact.
