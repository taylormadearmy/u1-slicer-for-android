# Full-Spectrum M1 Stage 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the engine's mixed-filament config keys through the SAPIL pipeline so a Kotlin caller can drive a full-spectrum slice via one new `SliceConfig` field, AND an externally-supplied 3MF profile can drive the engine's mixed-filament tuning keys via the `profile_keys[]` whitelist. No UI, no scalar Kotlin fields. The minimum surface that unblocks M2 (real-U1 feasibility print) and M3 (Compose UI).

**Architecture:** ONE Kotlin string field on `SliceConfig` round-trips via JNI to the native `SliceConfig` struct, then `applyConfigToPrusa` conditionally writes it into the engine's `mixed_filament_definitions` config key when non-empty. `profile_keys[]` adds 19 entries so an embedded 3MF profile's recipe + tuning scalars survive into the engine config. `buildProfileOverridesImpl` emits the user value into the embed override map so user-set always wins.

**Tech Stack:** Kotlin 1.9.22 + JNI C++; CMake 3.22.1 + Ninja (NDK 26 / Clang 17); native `.so` shipped pre-built via gradle.

**Reference docs:**
- Spec: [`docs/superpowers/specs/2026-06-06-full-spectrum-m1-stage2-config-wiring.md`](../specs/2026-06-06-full-spectrum-m1-stage2-config-wiring.md) — read fully before starting.
- Parent roadmap (for the 19-key catalog and parent context): [`docs/superpowers/specs/2026-05-26-full-spectrum-roadmap.md`](../specs/2026-05-26-full-spectrum-roadmap.md) — Appendix A has the key catalog.
- Native rebuild: `CLAUDE.md` §"Native Rebuild" and the protections shipped on main (`scripts/rebuild-native-so.sh`, `scripts/git-hooks/pre-commit` via `scripts/install-hooks.sh`).

---

## Prerequisites

1. **Worktree.** Stage 2 lands as a separate PR from stage 1. Create an isolated worktree off `origin/main` before starting — do NOT work on the `worktree-feature+full-spectrum-m1` branch (that's stage 1). The branch name suggestion: `feature/full-spectrum-m1-stage2`.
2. **Submodule.** The orca submodule must be initialised in this worktree (git worktrees don't auto-clone submodules). After creating the worktree:
   ```bash
   git submodule update --init --recursive app/src/main/cpp/orcaslicer
   ```
   Note that **main's submodule pin is still `bd66b99`** (stage 1's v2.3.3 bump hasn't merged yet). For stage 2 to actually test the mixed-filament feature, stage 1 must merge first, OR you must point the submodule at the same `feature/v2.3.3-android-m1` branch stage 1 pushed to our fork (`taylormadearmy/OrcaSlicer`). The simplest path is: wait for stage 1 to merge to main, then start stage 2.
3. **Git hooks.** If this is a fresh clone, install the protections shipped on main:
   ```bash
   scripts/install-hooks.sh
   ```
4. **Baseline tests.** Confirm starting state is green:
   ```bash
   ./gradlew testDebugUnitTest --no-daemon
   ```
   Expect BUILD SUCCESSFUL, ~1479 tests. If anything fails on unchanged code, stop and investigate before proceeding.

---

## Task 1: Add `mixedFilamentDefinitions` field to Kotlin `SliceConfig` (Red — test compiles, fails)

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/data/SliceConfig.kt`
- Create or modify: a test file (see Step 2)

- [ ] **Step 1: Add the Kotlin field**

Open `app/src/main/java/com/u1/slicer/data/SliceConfig.kt`. Find the existing `data class SliceConfig(` declaration. Add a new field. Place it near the bottom of the field list, before the closing `)` — match the existing `@JvmField var <name>: <type> = <default>,` pattern. Reasonable placement is right after the wipe-tower block:

```kotlin
    @JvmField var wipeTowerWidth: Float = 60f,
    // ...existing wipe tower fields...

    // Full-spectrum mixed-filament recipe (stage 2; serialized MixedFilamentManager output)
    @JvmField var mixedFilamentDefinitions: String = "",
```

Use the exact name `mixedFilamentDefinitions` and the exact native-side name `mixed_filament_definitions` — both are referenced in later tasks.

- [ ] **Step 2: Locate the existing `buildProfileOverridesImpl` JVM unit tests**

```bash
grep -rln 'buildProfileOverridesImpl' app/src/test/ | head -3
```

Open the file that returned. If no test file exists, create `app/src/test/java/com/u1/slicer/BuildProfileOverridesMixedFilamentTest.kt`. Otherwise add to the existing file (look for tests with names like `buildProfileOverrides_*`).

- [ ] **Step 3: Write the failing unit test for the "set" case**

Add this test method to the file located in Step 2 (or use the new file's package + imports):

```kotlin
@Test
fun buildProfileOverridesImpl_emitsMixedFilamentDefinitions_whenSet() {
    val cfg = SliceConfig(
        extruderCount = 2,
        mixedFilamentDefinitions = "1,1/2",
    )
    val overrides = buildProfileOverridesImpl(
        cfg = cfg,
        ov = SlicingOverrides(),
        slotCount = 2,
        filamentCount = 2,
    )
    assertEquals("1,1/2", overrides["mixed_filament_definitions"])
}
```

If the test file is new, add the standard imports:

```kotlin
package com.u1.slicer

import com.u1.slicer.data.SliceConfig
import com.u1.slicer.data.SlicingOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
```

- [ ] **Step 4: Write the failing unit test for the "empty default" case**

Right after the previous test:

```kotlin
@Test
fun buildProfileOverridesImpl_omitsMixedFilamentDefinitions_whenEmpty() {
    val cfg = SliceConfig(extruderCount = 2)  // mixedFilamentDefinitions defaults to ""
    val overrides = buildProfileOverridesImpl(
        cfg = cfg,
        ov = SlicingOverrides(),
        slotCount = 2,
        filamentCount = 2,
    )
    assertFalse(
        "mixed_filament_definitions must NOT be emitted when SliceConfig field is empty (lets embedded profile value win)",
        overrides.containsKey("mixed_filament_definitions"),
    )
}
```

- [ ] **Step 5: Run the two new tests; expect both to FAIL**

```bash
./gradlew testDebugUnitTest --no-daemon --tests "*MixedFilament*" 2>&1 | tail -20
```

Expected: BUILD FAILED, both tests fail.
- The "whenSet" test fails because `buildProfileOverridesImpl` doesn't emit the key yet — `overrides["mixed_filament_definitions"]` is `null` not `"1,1/2"`.
- The "whenEmpty" test fails *or* passes spuriously (it doesn't emit because no code emits it). If it passes, that's fine — Task 2 must keep it passing.

If both tests fail to compile, the new SliceConfig field name doesn't match the test — fix the field name to match Step 1.

- [ ] **Step 6: Commit the Red side**

```bash
git add app/src/main/java/com/u1/slicer/data/SliceConfig.kt \
        app/src/test/java/com/u1/slicer/BuildProfileOverridesMixedFilamentTest.kt
git commit -m "test(M1-stage2): RED — failing tests for mixedFilamentDefinitions wiring

Adds SliceConfig.mixedFilamentDefinitions field (empty default).
Adds JVM unit tests asserting buildProfileOverridesImpl emits
'mixed_filament_definitions' when set + omits it when empty.

Both tests fail on current code (no emission logic yet); Task 2
turns them green."
```

(If you added the tests to an existing file rather than a new one, adjust the `git add` accordingly.)

---

## Task 2: Wire `buildProfileOverridesImpl` to emit `mixed_filament_definitions` (Green)

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` (the `buildProfileOverridesImpl` function around line 7681)

- [ ] **Step 1: Locate the function's final `return result` statement**

```bash
grep -n 'fun buildProfileOverridesImpl\|^    return result' app/src/main/java/com/u1/slicer/SlicerViewModel.kt | head -5
```

The function builds a `MutableMap<String, Any>` named `result` throughout (you'll see lines like `result["support_filament"] = supportFilament.toString()` near the end). The final line of the function body is `return result`.

- [ ] **Step 2: Add the emission, scoped to non-empty values**

Insert this block just before the final `return result` statement:

```kotlin
    // Stage 2 — full-spectrum: only emit when the caller set a recipe.
    // Empty string (the default) lets an embedded profile's value win via profile_keys[].
    if (cfg.mixedFilamentDefinitions.isNotEmpty()) {
        result["mixed_filament_definitions"] = cfg.mixedFilamentDefinitions
    }
```

The variable name `result` matches the existing function body — no other naming to chase.

- [ ] **Step 3: Run the two new tests; expect both to PASS**

```bash
./gradlew testDebugUnitTest --no-daemon --tests "*MixedFilament*" 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL, both tests pass.

- [ ] **Step 4: Run the full unit suite to confirm no regression**

```bash
./gradlew testDebugUnitTest --no-daemon 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL, ~1481 tests pass (1479 baseline + 2 new). If any baseline tests fail, the emission change broke something — review the Step 2 edit and make sure it only added the emission, didn't change any other key's emission logic.

- [ ] **Step 5: Commit the Green side**

```bash
git add app/src/main/java/com/u1/slicer/SlicerViewModel.kt
git commit -m "feat(M1-stage2): GREEN — buildProfileOverridesImpl emits mixed_filament_definitions

When SliceConfig.mixedFilamentDefinitions is non-empty, emit it as
'mixed_filament_definitions' in the override map. Empty (default)
skips emission so an embedded 3MF profile's recipe wins.

Both Task 1 tests now green. Full unit suite stays green."
```

---

## Task 3: Add `mixed_filament_definitions` to the native `SliceConfig` struct + JNI marshal

**Files:**
- Modify: `app/src/main/cpp/include/sapil.h` (the `SliceConfig` struct)
- Modify: `app/src/main/cpp/src/sapil_config.cpp` (the `configFromJava` function around line 13)

- [ ] **Step 1: Add the native field**

In `app/src/main/cpp/include/sapil.h`, find the `struct SliceConfig {` declaration. Locate the existing `wipe_tower_width` field (the corresponding native form of the Kotlin field we placed `mixedFilamentDefinitions` after). After the wipe tower fields, add:

```cpp
    // Full-spectrum mixed-filament recipe (stage 2; serialized MixedFilamentManager output)
    std::string mixed_filament_definitions = "";
```

Use exact name `mixed_filament_definitions` — matches what `applyConfigToPrusa` will write into the engine config key (Task 4).

- [ ] **Step 2: Add the JNI marshal**

Open `app/src/main/cpp/src/sapil_config.cpp`. Find `config.filament_type = getString("filamentType");` (around line 124). Right after the `filament_types` line that follows it, add:

```cpp
    config.mixed_filament_definitions = getString("mixedFilamentDefinitions");
```

The `getString` lambda is the one defined earlier in the same function (around line 35). It looks up the Java field by name on `jconfig`. The Kotlin field name `mixedFilamentDefinitions` from Task 1 must match the string here exactly.

- [ ] **Step 3: Commit the native struct + marshal (no rebuild yet)**

Native source changes are commit-safe even without a rebuild — the `.so` and source can land in separate commits, and the rebuild is its own task.

```bash
git add app/src/main/cpp/include/sapil.h app/src/main/cpp/src/sapil_config.cpp
git commit -m "native(M1-stage2): add mixed_filament_definitions to SliceConfig + JNI marshal

Mirrors the Kotlin SliceConfig.mixedFilamentDefinitions field. JNI
marshal in configFromJava follows the existing string-field pattern
(matches filament_type). Engine wiring + rebuild are follow-up tasks."
```

---

## Task 4: Wire `applyConfigToPrusa` to set the engine config key when non-empty

**Files:**
- Modify: `app/src/main/cpp/src/sapil_print.cpp` (the `applyConfigToPrusa` function around line 169)

- [ ] **Step 1: Locate the function tail**

Open `sapil_print.cpp`. Find the line `static void applyConfigToPrusa(...) {` (around line 169). Scroll to the function's closing `}`. Just before the closing brace, after all existing `dpc.set_key_value` calls, add:

```cpp
    // Full-spectrum mixed-filament (stage 2): when the caller passed a
    // recipe via SliceConfig.mixed_filament_definitions, write it into
    // the engine's config key. Empty (default) is a no-op — engine
    // default produces no mixing, and embedded-profile path's value
    // (allowed via profile_keys[]) survives untouched.
    if (!config.mixed_filament_definitions.empty()) {
        dpc.set_key_value("mixed_filament_definitions",
            new Slic3r::ConfigOptionString(config.mixed_filament_definitions));
    }
```

- [ ] **Step 2: Confirm the file still compiles (no rebuild yet)**

Native-source edits don't compile-check until the rebuild. Visually verify:
- The `#include` for `ConfigOptionString` is already present in this file (search for other `ConfigOptionString` uses — they exist, e.g. around the `machine_end_gcode` set_key_value).
- The closing `}` of the function is intact and there's no orphan `}` from copy-paste.

- [ ] **Step 3: Commit (still no rebuild)**

```bash
git add app/src/main/cpp/src/sapil_print.cpp
git commit -m "native(M1-stage2): applyConfigToPrusa writes mixed_filament_definitions

When SliceConfig.mixed_filament_definitions is non-empty, set the
engine's mixed_filament_definitions config key (a ConfigOptionString).
Empty string is a no-op — engine default = no mixing, embedded
profile value survives via profile_keys[]."
```

---

## Task 5: Add the 19 mixed-filament keys to `profile_keys[]` whitelist

**Files:**
- Modify: `app/src/main/cpp/src/sapil_print.cpp` (the `profile_keys[]` array, somewhere after line ~800 based on earlier exploration)

- [ ] **Step 1: Locate the `profile_keys[]` array**

```bash
grep -n 'profile_keys\s*\[\]\s*=\|static.*profile_keys\s*\[' app/src/main/cpp/src/sapil_print.cpp
```

It's a large `const char* profile_keys[] = { "key_1", "key_2", ... };` declaration. Find the closing `};`.

- [ ] **Step 2: Append the 19 mixed-filament keys**

Add this block to the array. Place it together (don't sprinkle the keys throughout the existing array — keep them as a contiguous, clearly-tagged block so future maintenance is obvious). Just before the closing `};` of `profile_keys[]`:

```cpp
                    // Full-spectrum mixed-filament keys (stage 2; PR #375 in v2.3.3).
                    // The recipe string + 18 scalar tuning keys. Lets embedded 3MF
                    // project_settings.config drive mix behavior. Recipe is also
                    // exposed via SliceConfig.mixed_filament_definitions; the other
                    // 18 keys are profile-driven only until M3 exposes them.
                    "mixed_filament_definitions",
                    "mixed_filament_gradient_mode",
                    "mixed_filament_height_lower_bound",
                    "mixed_filament_height_upper_bound",
                    "mixed_filament_advanced_dithering",
                    "mixed_filament_component_bias_enabled",
                    "mixed_filament_surface_indentation",
                    "mixed_filament_region_collapse",
                    "mixed_color_layer_height_a",
                    "mixed_color_layer_height_b",
                    "mixed_filament_pointillism_pixel_size",
                    "mixed_filament_pointillism_line_gap",
                    "dithering_z_step_size",
                    "dithering_local_z_mode",
                    "dithering_local_z_whole_objects",
                    "dithering_local_z_infill",
                    "dithering_local_z_direct_multicolor",
                    "dithering_step_painted_zones_only",
                    "local_z_wipe_tower_purge_lines",
```

Match the indentation of surrounding entries (the existing array entries use a specific indent — probably 20 spaces or tab+16 spaces; check what's there and match exactly).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/cpp/src/sapil_print.cpp
git commit -m "native(M1-stage2): whitelist 19 mixed-filament keys in profile_keys[]

Lets embedded 3MF project_settings.config drive full-spectrum
behavior. The recipe key (mixed_filament_definitions) is also
exposed via SliceConfig; the 18 scalar tuning keys are
profile-driven only until M3 exposes them in Kotlin."
```

---

## Task 6: Rebuild the native `.so` via the wrapper script

**Files:**
- Touched: `app/.cxx/Release/m1-stage2/arm64-v8a/` (build directory — created fresh)
- Modified: `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so` (stripped output)

- [ ] **Step 1: Configure a fresh CMake build directory for stage 2**

Stage 1's `app/.cxx/Release/m1-stage1/` build dir is from a different branch's build state; do not reuse it. Run a fresh CMake configure per `CLAUDE.md` §"Native Rebuild" §"Fresh build":

```bash
CMAKE_BIN="D:/Android/Sdk/cmake/3.22.1/bin/cmake.exe"
NINJA_BIN="D:/Android/Sdk/cmake/3.22.1/bin/ninja.exe"
NDK="D:/Android/Sdk/ndk/26.1.10909125"
BUILD_DIR="app/.cxx/Release/m1-stage2/arm64-v8a"
mkdir -p "$BUILD_DIR"

"$CMAKE_BIN" \
  -H"app/src/main/cpp" \
  -DCMAKE_SYSTEM_NAME=Android \
  -DCMAKE_SYSTEM_VERSION=26 \
  -DANDROID_PLATFORM=android-26 \
  -DANDROID_ABI=arm64-v8a \
  -DCMAKE_ANDROID_ARCH_ABI=arm64-v8a \
  -DANDROID_NDK="$NDK" \
  -DCMAKE_ANDROID_NDK="$NDK" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DCMAKE_MAKE_PROGRAM="$NINJA_BIN" \
  -DCMAKE_BUILD_TYPE=Release \
  -B"$BUILD_DIR" \
  -GNinja \
  -DSLICER_BACKEND=orca \
  -DANDROID_STL=c++_shared \
  -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384"
```

(The `-Wl,-z,max-page-size=16384` flag preserves the stage-1 Android-15-compat alignment.)

- [ ] **Step 2: Run the rebuild via the wrapper script**

```bash
scripts/rebuild-native-so.sh app/.cxx/Release/m1-stage2/arm64-v8a
```

The wrapper runs `ninja -j1` then strips + verifies. The script will refuse the deploy step if the orca submodule has uncommitted modifications (it shouldn't here — stage 2 doesn't touch the submodule).

Expected output near the end:
- `Deployed: .../jniLibs/arm64-v8a/libprusaslicer-jni.so (21 MB)` (between 19–22 MB).
- `Compiler: clang version 17.0.2`.
- `LOAD segment alignments: 0x4000` (16KB-aligned).
- `JNI symbols: 51 in .so vs 51 external fun in NativeLibrary.kt` (matching counts).

If any check fails, address it before proceeding. Common issues + fixes are in `CLAUDE.md` §"Native Rebuild".

- [ ] **Step 3: Run the JVM unit suite to confirm the new `.so` doesn't break unit tests**

(Unit tests don't load the .so, but the APK packaging step does — `assembleDebug` will check the .so format.)

```bash
./gradlew assembleDebug --no-daemon 2>&1 | tail -5
./gradlew testDebugUnitTest --no-daemon 2>&1 | tail -5
```

Both should be BUILD SUCCESSFUL.

- [ ] **Step 4: Commit the rebuilt `.so`**

The pre-commit hook installed by `scripts/install-hooks.sh` will gate this commit against an uncommitted orca submodule. Stage 2 doesn't touch the submodule, so the hook passes cleanly.

```bash
git add app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so
git commit -m "native(M1-stage2): rebuild .so with mixed_filament_definitions wiring

Deploys Tasks 3-5 (SliceConfig field + JNI marshal + engine config-key
emission + profile_keys[] whitelist). Verified per scripts/rebuild-native-so.sh:
~21 MB stripped, clang 17, 16KB-page-aligned, 51 JNI symbols matching
NativeLibrary.kt."
```

---

## Task 7: Add the end-to-end instrumented test (layer alternation in G-code)

**Files:**
- Modify: `app/src/androidTest/java/com/u1/slicer/slicing/SlicingIntegrationTest.kt` (append a new `@Test` method)

- [ ] **Step 1: Locate a good insertion point**

```bash
grep -n 'fun tetrahedron_stl_sliced_gcodeContainsExcludeObjectDefine' app/src/androidTest/java/com/u1/slicer/slicing/SlicingIntegrationTest.kt
```

Append the new test method right after that test (it's a similar small-STL slice + G-code grep assertion shape).

- [ ] **Step 2: Add the new test method**

```kotlin
@Test
fun mixedFilament_simpleLayerAlternation_producesAlternatingToolChanges() {
    // Stage 2 end-to-end: a non-empty mixedFilamentDefinitions recipe must
    // reach the engine and produce a G-code with T0/T1 alternation across
    // layers. Recipe "1,1/2" = component_a=filament-1, component_b=filament-2,
    // ratio_a=1, ratio_b=1 (alternate every layer).
    val config = DEFAULT_CONFIG.copy(
        extruderCount = 2,
        mixedFilamentDefinitions = "1,1/2",
    )
    val (success, gcode) = sliceAsset("tetrahedron.stl", config)
    assertTrue("Slice with mixed-filament recipe must succeed", success)
    assertNotNull("G-code must be produced", gcode)

    val t0Lines = gcode!!.lines().count { it.trim() == "T0" }
    val t1Lines = gcode.lines().count { it.trim() == "T1" }
    assertTrue(
        "Mix recipe must produce at least one T0 tool change, got $t0Lines",
        t0Lines >= 1,
    )
    assertTrue(
        "Mix recipe must produce at least one T1 tool change (the alternation), got $t1Lines",
        t1Lines >= 1,
    )
}
```

The test uses the existing `DEFAULT_CONFIG` (a class-level `val` near the top of the file — see line ~37) and the existing `sliceAsset` helper (around line ~92). Both already exist; reuse them.

- [ ] **Step 3: Run the new test (instrumented — requires connected device)**

Per `CLAUDE.local.md`, pin to the Pixel 8a:

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.slicing.SlicingIntegrationTest#mixedFilament_simpleLayerAlternation_producesAlternatingToolChanges \
  --no-daemon 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL, 1 test passes.

If it fails:
- "T0 lines == 0" → the recipe didn't reach the engine. Verify Task 3 (struct field name + JNI marshal field name) and Task 4 (set_key_value call). Look at `adb logcat | grep SAPIL` while running the test for clues.
- "Slice failed" → the engine rejected the recipe. Check the slice error message; the recipe format `"1,1/2"` may need adjusting per PR #375's parser (the spec's "Risks" section flags this — try `"1/2"` if the comma form is rejected).
- "Build failed: tetrahedron.stl missing" → asset path wrong; check `find app/src/androidTest -name 'tetrahedron.stl'`.

- [ ] **Step 4: Commit the instrumented test**

```bash
git add app/src/androidTest/java/com/u1/slicer/slicing/SlicingIntegrationTest.kt
git commit -m "test(M1-stage2): instrumented test for layer-alternation G-code

End-to-end: SliceConfig.mixedFilamentDefinitions = '1,1/2' produces a
G-code with both T0 and T1 tool-change lines on tetrahedron.stl. Proves
the full pipe (Kotlin SliceConfig → JNI marshal → applyConfigToPrusa →
engine MixedFilamentManager → toolpath emission)."
```

---

## Task 8: Full regression sweep — unit + instrumented

- [ ] **Step 1: Run the full JVM unit suite**

```bash
./gradlew testDebugUnitTest --no-daemon 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL, ~1481 tests (1479 baseline + 2 new from Task 1). 0 failures.

- [ ] **Step 2: Run the full instrumented suite**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest --no-daemon 2>&1 | tee /d/tmp/m1-stage2-sweep.log | tail -30
```

Expected: BUILD SUCCESSFUL, ~406 tests (~405 baseline + 1 new from Task 7). 0 failures. Wall time ~25–35 min.

If a test fails:
- **`ForegroundServiceDidNotStartInTimeException` / "instrumentation process crashed"** — known Android-side flake (see stage 1's final report). Re-run JUST the failing test in isolation; if it passes, it's the same flake class.
- **Anything slicing-related that wasn't failing before** — your `applyConfigToPrusa` change leaked a side-effect somewhere. Verify the `if (!config.mixed_filament_definitions.empty())` guard is correct and not missing the negation.
- **`tetrahedron_stl_sliced_gcodeContainsExcludeObjectDefine` failing** — F71 patch regression; should NOT happen since stage 2 doesn't touch the orca submodule, but if it does, the rebuild dropped the F71 patch somehow. Read `git -C app/src/main/cpp/orcaslicer log -1` and confirm HEAD is correct.

- [ ] **Step 3: Don't commit anything — this task only verifies, nothing to add**

---

## Task 9: Final commit message tidying + push

- [ ] **Step 1: Check the branch state**

```bash
git log --oneline origin/main..HEAD
git status
git diff origin/main..HEAD --stat
```

Expected: 6–7 commits on the branch (Tasks 1, 2, 3, 4, 5, 6, 7 — each committed independently). Working tree clean. The diff stat shows ~5 Kotlin files + 3 native files + the rebuilt `.so` + 1 new unit test file.

If any commits look mis-targeted or have typos in their messages, you can tidy via interactive rebase (but only if the branch hasn't been pushed yet). Otherwise, leave them as-is — separate per-task commits are valuable for bisect.

- [ ] **Step 2: Push the branch**

```bash
git push -u origin feature/full-spectrum-m1-stage2
```

Use whatever branch name you chose in Prerequisites step 1.

- [ ] **Step 3: Verify the push**

```bash
gh api repos/taylormadearmy/u1-slicer-for-android/branches/feature/full-spectrum-m1-stage2 --jq '.commit.sha'
```

Should match local `git rev-parse HEAD`.

---

## Out of scope for this plan

These belong to follow-up plans, not stage 2:

- **M2 — Real-U1 feasibility print.** Stage 2 only proves the pipe works at the G-code level. M2 takes the resulting G-code to a real U1 and confirms blended-colour print quality. Pure user/hardware work; nothing to code if the test in Task 7 passes.
- **M3 — Compose UI.** A target-colour picker that builds the recipe string. Touches `SlicerViewModel`, multiple new composables, and the M3a slot-byte widening.
- **M3a — Smart Paint slot widening.** Per-triangle slot byte from `0..3` to virtual IDs `≥4`. Touches `AiRegion`, `PaintedMeshWriter`, slot-reassignment chips.
- **M4 — Prusa `prusa-fdm-mixer` integration.** Colour-prediction library bolt-on.
- **Typed Kotlin fields for the 18 scalar tuning keys.** Add them when M3's UI design needs them; each is a 2-line change (one in `SliceConfig.kt` + one in `buildProfileOverridesImpl`).

## Self-review notes

- Spec §"Components" covers all 5 files: SliceConfig.kt (Task 1), buildProfileOverridesImpl (Task 2), sapil.h (Task 3 step 1), sapil_config.cpp (Task 3 step 2), sapil_print.cpp applyConfigToPrusa (Task 4), sapil_print.cpp profile_keys (Task 5). ✓
- Spec §"Tests" covers both unit tests (Task 1 step 3+4 + Task 2 step 3) and the instrumented test (Task 7). ✓
- Spec §"Native rebuild + verification" covers Task 6. ✓
- Spec §"Acceptance criteria" #5 (separate branch off main, not stage 1's branch) → Prerequisites step 1. ✓
- No "TBD" / "TODO" / "implement later" / "fill in details" anywhere in this plan.
- Type consistency: `mixedFilamentDefinitions` (Kotlin camelCase) and `mixed_filament_definitions` (native snake_case) are used consistently throughout; the JNI marshal in Task 3 step 2 bridges them.
- Frequent commits: each task ends with a commit. The branch lands as 7 small commits (one per logical task), good for bisect.
