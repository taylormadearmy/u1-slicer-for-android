# Implement Phase 3 extruder-count parameterisation

This execution plan expands Phase 3 of the [A2 multi-printer support plan](2026-08-30-multi-printer-support.md). Complete Tasks 3.1 through 3.5 in order. Do not start the G-code dialect work in Phase 4.

## Constraints

- Treat `MachineProfile.extruderCount` as the source of truth for Moonraker printer slot count.
- Support one to four slots. Clamp malformed profile counts at this boundary.
- Preserve the existing four-slot U1 defaults and serialized data.
- Keep the Bambu target path unchanged.
- Keep `SegmentationCascade.TARGET_SLOTS == 4` and the `4 + k` mix encoding unchanged.
- Add a failing regression test before each production change when a local test can exercise the behavior.
- Run focused tests after each task. Commit each completed task separately.

## Task 3.1: derive slot data from the machine profile

1. Add `count: Int` to `defaultExtruderPresets` and `parseExtruderPresets`. Keep `count = 4` as the compatibility default.
2. Fill and trim presets to `count`, with stable indices and the current U1 colors for indices zero through three.
3. Expose the active profile's bounded extruder count through the printer-to-profile mapping.
4. Add `ExtruderPresetTest` cases for one, two, and four slots. Add a repository mapping test for a cached one-slot profile and the built-in four-slot U1.
5. Run the focused data and machine-profile tests, check Task 3.1 in the master plan, and commit `feat(data): derive printer slots from machine profiles`.

## Task 3.2: replace four-slot clamps

1. Pass the active profile slot count through slot-indexed helpers and composables in the master plan inventory.
2. Replace physical-slot ranges, list sizes, and clamps with `0 until slotCount` or `slotCount - 1`.
3. Leave file-filament ranges, Bambu model rules, four-bit decoders, and mix encoding constants unchanged.
4. Add one-slot and two-slot cases to the multi-color mapping, print-time remap, and canonical-color remap tests.
5. Add a source guard for bare physical-slot `0..3` literals in the files changed by this task. Document each allowed four-slot encoding literal next to the guard.
6. Run focused tests and the full JVM suite, check Task 3.2 in the master plan, and commit `refactor(extruders): parameterize physical slot counts`.

## Task 3.3: hide mixes on non-four-slot printers

1. Gate every `CreateMixSlotDialog` entry point on `slotCount == 4`.
2. Gate the AI Paint mix palette and mix rows on `slotCount == 4`.
3. Keep physical AI Paint slots sized from the active profile. Keep virtual mix byte IDs based at four.
4. Add a structural wiring test that proves the active profile count reaches both gates. Pin `TARGET_SLOTS == 4` and the mix serialization base.
5. Run focused UI and AI Paint tests, check Task 3.3 in the master plan, and commit `feat(mix): gate color mixes to four-slot printers`.

## Task 3.4: honor native profile extruder settings

1. In `sapil_print.cpp`, preserve profile-provided `single_extruder_multi_material` for authored non-Snapmaker profiles. Keep the U1 fallback false.
2. Preserve profile-provided `extruder_offset`. Synthesize zero offsets only when the profile omits the key.
3. In `sapil_arrange.cpp`, derive the arrange extruder count from `config.extruder_count` and cap it at four.
4. Commit the Orca submodule change before copying a rebuilt library into `jniLibs`.
5. Run `scripts/rebuild-native-so.sh` with NDK 26 in Release mode and one Ninja worker.
6. Verify a stripped size near 19 to 21 MB, Clang 17.0.2, NDK 26 paths in `CMakeCache.txt`, and matching JNI declaration and exported-symbol counts.
7. Run focused native structural tests and the U1 instrumented sweep, check Task 3.4 in the master plan, and commit `feat(native): derive extruder handling from machine profiles`.

## Task 3.5: prove one-extruder slicing

1. Add `SingleExtruderMachineTest` with a generic one-extruder machine profile and a multi-color source fixture.
2. Assert that slicing succeeds, the output has no `T1` or higher command, the output has no toolchange sequence, the prime tower is disabled, and each configured temperature appears once.
3. Run the new instrumented class on device `NF12209000455`.
4. Check Task 3.5 in the master plan and commit `test(slicing): prove generic single-extruder output`.

## Phase 3 exit gate

1. Run the full JVM suite.
2. Run `\.\scripts\run-confidence-check.ps1` without filters.
3. Confirm all Phase 3 checkboxes are complete.
4. Confirm the U1 still has four slots and unchanged mix encoding.
5. Confirm non-four-slot profiles expose no mix creation or AI Paint mix palette.
6. Confirm `versionCode` and `versionName` did not change.
7. Confirm the worktree and Orca submodule are clean and the recorded submodule commit matches its checkout.
8. Stop before Phase 4.
