# Dropped Profile Keys Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pass through all print-quality-affecting keys from embedded Snapmaker 3MF profiles that were previously silently dropped by the `profile_keys[]` whitelist in `sapil_print.cpp`.

**Architecture:** Three coordinated changes in `sapil_print.cpp`: (1) add missing keys to the `profile_keys[]` whitelist so embedded profile values reach OrcaSlicer's engine, (2) add B48 SEMM padding for new per-extruder arrays, (3) fix B48's `flush_volumes_matrix` override to preserve profile-provided values when correctly sized. One new instrumented test verifies pressure advance passes through end-to-end.

**Tech Stack:** Kotlin (instrumented tests), C++ (native slicer config), Android NDK 26 / Clang 17, ninja build system.

---

## Background

When slicing a Snapmaker 3MF, the embedded `project_settings.config` is applied via a `profile_keys[]` whitelist in `sapil_print.cpp`. Keys not in the whitelist are silently dropped and OrcaSlicer's compiled defaults are used instead. Investigation of the Flarewing Dragon 3MF revealed several impactful dropped keys:

| Key | Embedded value | Effect of dropping |
|-----|---------------|-------------------|
| `filament_flow_ratio` | `[0.95, 0.95, 0.95, 0.95]` | Over-extrudes ~5% on calibrated profiles |
| `flush_volumes_matrix` | Per-combo 0–800mm³ | Under-purges (our hardcoded 140mm³ is far too low for some combos) |
| `flush_multiplier` | `0.7` | Purge multiplier not applied |
| `filament_minimal_purge_on_wipe_tower` | `[15,15,15,15]` | Min purge ignored |
| `purge_in_prime_tower` | `0` | Incorrect purge routing |
| `enable_pressure_advance` | `[1,1,1,1]` | `SET_PRESSURE_ADVANCE` never emitted even when firmware supports it |
| `pressure_advance` | `[0.04,0.04,0.04,0.04]` | PA value not applied |
| `filament_type` | `[PETG,PETG,PETG,PETG]` | `change_filament_gcode` template conditionals (e.g. PVA) never fire |
| `tool_change_temprature_wait` | `0` | Ooze prevention uses wrong re-heat mode |
| Per-feature jerk (`outer_wall_jerk` etc.) | `9` / `12` | Motion planning uses OrcaSlicer defaults |

Additionally, the B48 SEMM code unconditionally overwrites `flush_volumes_matrix` with a flat 140mm³ even for non-SEMM models where the profile's calibrated values would be correct.

---

## Files Modified

- **`app/src/main/cpp/src/sapil_print.cpp`** — three sections: `profile_keys[]`, `applyConfigToPrusa()` B48 block
- **`app/src/androidTest/java/com/u1/slicer/slicing/ProfileEmbedderIntegrationTest.kt`** — new test verifying pressure advance passes through to G-code
- **`app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so`** — rebuilt binary

---

## Task 1: Add missing keys to `profile_keys[]`

**Files:**
- Modify: `app/src/main/cpp/src/sapil_print.cpp` — `profile_keys[]` array (around line 513)

- [ ] **Step 1: Add new keys to the whitelist**

Find the `profile_keys[]` array. After the existing `"filament_colour"` entry (and the three ooze-prevention keys added in the prior fix), add the following block before `nullptr`:

```cpp
                    // Filament flow calibration — per-filament extrusion multiplier.
                    // Native Snapmaker profiles use 0.95; without this, flow defaults
                    // to 1.0 and calibrated profiles over-extrude by ~5%.
                    "filament_flow_ratio",
                    // Filament type string (per-filament).  Used in change_filament_gcode
                    // template conditionals, e.g. PVA-specific handling.
                    "filament_type",
                    // Pressure advance (Klipper SET_PRESSURE_ADVANCE).  OrcaSlicer emits
                    // these automatically when enable_pressure_advance is true and
                    // gcode_flavor=klipper.  enable_pressure_advance is ConfigOptionBools.
                    "enable_pressure_advance",
                    "pressure_advance",
                    // Wipe tower purge volumes.  flush_volumes_matrix is an NxN matrix of
                    // mm³ per filament-combo transition; flush_multiplier scales it.
                    // Native Snapmaker profiles calibrate these carefully (0–800mm³).
                    // Without them we use OrcaSlicer's compiled default (flat 140mm³).
                    "flush_volumes_matrix",
                    "flush_multiplier",
                    "filament_minimal_purge_on_wipe_tower",
                    "purge_in_prime_tower",
                    // Ooze prevention re-heat mode: 0 = M104 (non-blocking), 1 = M109 (wait).
                    "tool_change_temprature_wait",
                    // Per-feature jerk limits (from process/printer profile).
                    // Without these the slicer uses OrcaSlicer defaults instead of the
                    // Snapmaker-tuned values.
                    "default_jerk",
                    "outer_wall_jerk",
                    "inner_wall_jerk",
                    "infill_jerk",
                    "initial_layer_jerk",
                    "top_surface_jerk",
                    "travel_jerk",
```

- [ ] **Step 2: Verify the array still compiles (quick syntax check)**

The array must end with `nullptr` as the sentinel. Confirm the structure is:
```cpp
static const char* profile_keys[] = {
    // ... existing keys ...
    "filament_colour",
    // ... ooze prevention keys from prior fix ...
    "ooze_prevention",
    "idle_temperature",
    "standby_temperature_delta",
    // ... new keys above ...
    "travel_jerk",
    nullptr   // <-- must still be here
};
```

---

## Task 2: Fix B48 SEMM `flush_volumes_matrix` override

**Files:**
- Modify: `app/src/main/cpp/src/sapil_print.cpp` — B48 block (around line 771)

Currently the B48 block unconditionally replaces `flush_volumes_matrix` with a flat 140mm³ grid sized to `virtual_ext × virtual_ext`. This destroys calibrated values from the embedded profile even for non-SEMM models.

The fix: only override if the profile didn't provide a correctly-sized matrix. For SEMM models (where `virtual_ext > n_ext`), the profile's matrix is the wrong size and the fallback is still needed.

- [ ] **Step 1: Replace the unconditional flush_volumes_matrix assignment**

Find this block in the B48 section:
```cpp
                // Flush volumes matrix — NxN where N = virtual_ext.
                // ToolOrdering derives extruder count from sqrt(matrix.size()).
                // Default purge volume 140mm³ (matches prime_volume default).
                dpc.set_key_value("flush_volumes_matrix",
                    new Slic3r::ConfigOptionFloats(std::vector<double>(virtual_ext * virtual_ext, 140.0)));
                // Flush volumes vector (per-extruder multipliers, one per extruder)
                dpc.set_key_value("flush_volumes_vector",
                    new Slic3r::ConfigOptionFloats(std::vector<double>(virtual_ext * 2, 140.0)));
```

Replace with:
```cpp
                // Flush volumes matrix — NxN where N = virtual_ext.
                // ToolOrdering derives extruder count from sqrt(matrix.size()).
                // Prefer the profile's calibrated matrix when it is already sized for
                // virtual_ext.  For SEMM models (virtual_ext > profile extruder count)
                // the sizes won't match and we fall back to the 140mm³ default.
                {
                    auto* fvm = dpc.option<Slic3r::ConfigOptionFloats>("flush_volumes_matrix");
                    if (!fvm || (int)fvm->values.size() != virtual_ext * virtual_ext) {
                        dpc.set_key_value("flush_volumes_matrix",
                            new Slic3r::ConfigOptionFloats(std::vector<double>(virtual_ext * virtual_ext, 140.0)));
                    }
                }
                // Flush volumes vector (per-extruder multipliers, one per extruder)
                {
                    auto* fvv = dpc.option<Slic3r::ConfigOptionFloats>("flush_volumes_vector");
                    if (!fvv || (int)fvv->values.size() < virtual_ext * 2) {
                        dpc.set_key_value("flush_volumes_vector",
                            new Slic3r::ConfigOptionFloats(std::vector<double>(virtual_ext * 2, 140.0)));
                    }
                }
```

---

## Task 3: Add B48 padding for new per-extruder arrays

**Files:**
- Modify: `app/src/main/cpp/src/sapil_print.cpp` — B48 padding section (after the `pad_ints("idle_temperature")` line added in the prior fix)

SEMM models use `virtual_ext > n_ext`. All per-extruder arrays must be padded to `virtual_ext` or OrcaSlicer will crash / use wrong values for high-index virtual extruders.

- [ ] **Step 1: Add a `pad_strings` lambda and new padding calls**

First, add the `pad_strings` lambda alongside the existing `pad_floats` and `pad_ints` lambdas. Find the lambdas (they look like):
```cpp
auto pad_floats = [&](const char* key) {
    auto* opt = dpc.option<Slic3r::ConfigOptionFloats>(key);
    if (opt && (int)opt->values.size() < virtual_ext) {
        double last = opt->values.back();
        opt->values.resize(virtual_ext, last);
    }
};
auto pad_ints = [&](const char* key) {
    auto* opt = dpc.option<Slic3r::ConfigOptionInts>(key);
    if (opt && (int)opt->values.size() < virtual_ext) {
        int last = opt->values.back();
```

Add immediately after `pad_ints`:
```cpp
                auto pad_strings = [&](const char* key) {
                    auto* opt = dpc.option<Slic3r::ConfigOptionStrings>(key);
                    if (opt && (int)opt->values.size() < virtual_ext) {
                        std::string last = opt->values.back();
                        opt->values.resize(virtual_ext, last);
                    }
                };
                auto pad_bools = [&](const char* key) {
                    auto* opt = dpc.option<Slic3r::ConfigOptionBools>(key);
                    if (opt && (int)opt->values.size() < virtual_ext) {
                        bool last = opt->values.back();
                        opt->values.resize(virtual_ext, last);
                    }
                };
```

- [ ] **Step 2: Add the new padding calls**

After the `pad_ints("idle_temperature");` line, add:
```cpp
                // New per-filament arrays added to profile_keys
                pad_floats("filament_flow_ratio");
                pad_strings("filament_type");
                pad_bools("enable_pressure_advance");
                pad_floats("pressure_advance");
                pad_floats("filament_minimal_purge_on_wipe_tower");
```

---

## Task 4: Write failing instrumented test for pressure advance pass-through

**Files:**
- Modify: `app/src/androidTest/java/com/u1/slicer/slicing/ProfileEmbedderIntegrationTest.kt`

Pressure advance is the most directly verifiable of the new keys: when `enable_pressure_advance=true`, `gcode_flavor=klipper`, and `pressure_advance=0.04`, OrcaSlicer emits `SET_PRESSURE_ADVANCE ADVANCE=0.04` into the G-code automatically.

- [ ] **Step 1: Add the failing test**

Find the last test in `ProfileEmbedderIntegrationTest`. Add after it:

```kotlin
@Test
fun pressureAdvancePassesThroughFromEmbeddedProfile() {
    // When an embedded Snapmaker profile has enable_pressure_advance=true and
    // pressure_advance=0.04, OrcaSlicer should emit SET_PRESSURE_ADVANCE commands
    // (Klipper gcode_flavor only).  Before the fix, enable_pressure_advance and
    // pressure_advance were not in profile_keys[], so they were dropped and no
    // SET_PRESSURE_ADVANCE commands appeared in the output.
    val config = mapOf(
        "gcode_flavor" to "klipper",
        "enable_pressure_advance" to listOf("1"),
        "pressure_advance" to listOf("0.04"),
        // Minimal required keys for a valid Snapmaker profile
        "machine_start_gcode" to "PRINT_START\n",
        "machine_end_gcode" to "PRINT_END\n",
        "change_filament_gcode" to "T[next_extruder]\n",
        "nozzle_temperature" to listOf("220"),
        "nozzle_temperature_initial_layer" to listOf("220"),
        "hot_plate_temp" to listOf("60"),
        "hot_plate_temp_initial_layer" to listOf("65"),
    )
    val result = embedAndSlice(config)
    assertNotNull("Slice must succeed", result)
    val gcode = File(result!!.gcodePath).readText()
    assertTrue(
        "Expected SET_PRESSURE_ADVANCE ADVANCE=0.04 in G-code but not found.\n" +
        "This means enable_pressure_advance/pressure_advance are not passing through profile_keys[].",
        gcode.contains("SET_PRESSURE_ADVANCE ADVANCE=0.04")
    )
}
```

> **Note on `embedAndSlice`:** Check whether the existing tests use a helper with this exact signature or similar. The existing tests in this class embed a config map into a 3MF and slice it — use whatever helper is already there. If the method is named differently (e.g. `sliceWithEmbeddedConfig`), use that name.

- [ ] **Step 2: Run the test to confirm it fails before the native fix**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest \
  --tests "com.u1.slicer.slicing.ProfileEmbedderIntegrationTest.pressureAdvancePassesThroughFromEmbeddedProfile" \
  --no-daemon
```

Expected: **FAIL** — `SET_PRESSURE_ADVANCE` not found in G-code output.

---

## Task 5: Build the native `.so`

**Files:**
- `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so`

- [ ] **Step 1: Build**

```bash
cd app/.cxx/Debug/b62ndk26/arm64-v8a
C:/Users/kevin/AppData/Local/Android/Sdk/cmake/3.22.1/bin/ninja.exe -j1
```

Expected last line: `[2/2] Linking CXX shared library app\.cxx\Debug\b62ndk26\arm64-v8a\obj\libprusaslicer-jni.so`

- [ ] **Step 2: Strip, verify size and compiler**

```bash
NDK=C:/Users/kevin/AppData/Local/Android/Sdk/ndk/26.1.10909125
"$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe" \
  --strip-unneeded app/.cxx/Debug/b62ndk26/arm64-v8a/obj/libprusaslicer-jni.so
ls -lh app/.cxx/Debug/b62ndk26/arm64-v8a/obj/libprusaslicer-jni.so
"$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe" \
  -p .comment app/.cxx/Debug/b62ndk26/arm64-v8a/obj/libprusaslicer-jni.so | grep clang
```

Expected: ~20MB, `clang version 17.0.2`. If >50MB → built as Debug, redo.

- [ ] **Step 3: Copy to jniLibs**

Run from project root:
```bash
cp app/.cxx/Debug/b62ndk26/arm64-v8a/obj/libprusaslicer-jni.so \
   app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so
```

Note: the ninja CWD persists in the shell session. If `cp` can't find the source, use the absolute path:
```bash
cp "c:/Users/kevin/projects/u1-slicer-orca/app/.cxx/Debug/b62ndk26/arm64-v8a/obj/libprusaslicer-jni.so" \
   "c:/Users/kevin/projects/u1-slicer-orca/app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so"
```

---

## Task 6: Run the new test — verify it now passes

- [ ] **Step 1: Install and run the new test**

```bash
cd c:/Users/kevin/projects/u1-slicer-orca
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest \
  --tests "com.u1.slicer.slicing.ProfileEmbedderIntegrationTest.pressureAdvancePassesThroughFromEmbeddedProfile" \
  --no-daemon
```

Expected: **PASS** — `SET_PRESSURE_ADVANCE ADVANCE=0.04` found in G-code.

---

## Task 7: Run the full confidence check

- [ ] **Step 1: Run the full 3-layer verification**

Use the `u1-slicer-confidence-check` skill (or run manually):

```bash
# Unit tests
./gradlew testDebugUnitTest --no-daemon

# Instrumented tests on device
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest --no-daemon
```

All 770 unit tests and 164+ instrumented tests must pass. No pre-existing failures exist in this repo.

---

## Task 8: Commit

- [ ] **Step 1: Commit all changes**

```bash
cd c:/Users/kevin/projects/u1-slicer-orca
gh auth switch -u taylormadearmy
git add app/src/main/cpp/src/sapil_print.cpp \
        app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so \
        app/src/androidTest/java/com/u1/slicer/slicing/ProfileEmbedderIntegrationTest.kt
git commit -m "fix: pass through dropped Snapmaker profile keys (flow ratio, PA, flush matrix, jerk, filament_type)"
```

---

## What This Does NOT Fix

- **SEMM flush_volumes_matrix**: For SEMM/H2C models (virtual_ext > n_ext), the B48 code still uses 140mm³ because the profile's NxN matrix is the wrong size for the virtual extruder count. This is a separate issue requiring a mapping strategy.
- **filament_retraction_\* and filament_z_hop**: All `nil` in the test profile — no-ops for now.
- **300+ other dropped keys**: Bambu-specific, cosmetic, or have acceptable OrcaSlicer defaults.
