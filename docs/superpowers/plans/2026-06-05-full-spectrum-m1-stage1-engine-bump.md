# Full-Spectrum M1 Stage 1 — Engine Bump (Regression-Only)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bump the OrcaSlicer submodule from `bd66b99` (2026-05-01) to a new
commit rebased onto **Snapmaker's `v2.3.3` tag** (2026-06-01 — first tag
containing the full-spectrum / mix-filament feature, PR #375). Rebuild the
native `libprusaslicer-jni.so` (Release, 16KB-page-aligned). Verify the full
existing test suite stays green. **Full-spectrum capability is now PRESENT in
the engine but NOT exposed** — no new config keys, no UI, no overrides. This
stage is pure regression: prove the bumped engine slices our existing fixtures
identically to before.

**Architecture:** Three phases. **Phase A** — in our orcaslicer fork
(`github.com/taylormadearmy/OrcaSlicer`), rebase our 8 Android patch commits
onto `v2.3.3` and push the result. Conflicts expected in files #375 also
touched (TriangleSelector, PrintApply, GCodeProcessor, PrintObjectSlice).
**Phase B** — in the parent repo, update the submodule pin to the rebased
head, then make our SAPIL wrapper (`app/src/main/cpp/src/sapil_*.cpp`) compile
against the changed libslic3r API. **Phase C** — Release build with 16KB-page
alignment flag, full test suite as the regression oracle, spot-check three
representative fixtures for G-code equivalence, commit.

**Tech Stack:** Kotlin 1.9.22 + Compose + JNI C++; CMake 3.22.1 + Ninja
(NDK 26.1.10909125, Clang 17); pre-built `.so` shipped via Gradle. Submodule:
`taylormadearmy/OrcaSlicer` (our fork of `Snapmaker/OrcaSlicer`), at
`app/src/main/cpp/orcaslicer`. Native source:
`app/src/main/cpp/src/sapil_*.cpp` (outside submodule).

**Reference docs:**
- Spec: [`docs/superpowers/specs/2026-05-26-full-spectrum-roadmap.md`](../specs/2026-05-26-full-spectrum-roadmap.md) — read §M1 pre-flight + §7 (status checks) before starting.
- Engine upgrade procedure: [`ENGINE_UPGRADE_GUIDE.md`](../../../ENGINE_UPGRADE_GUIDE.md) — caveat: the "Patch Catalog" describes content correctly but the *mechanism* it implies ("patches as uncommitted modifications, applied via `git apply --3way`") is wrong for the current state of our fork. The patches are committed in our fork as a clean linear stack of 8 commits above the v2.2.x upstream base, and the right mechanism is **rebase**.
- Native rebuild checklist: `CLAUDE.md` §"Native Rebuild" — **MUST follow** the NDK 26 / Release / size + compiler verification steps exactly.

## Key state discovered 2026-06-05 (before this plan revision)

- Orca submodule remote = **our fork** `github.com/taylormadearmy/OrcaSlicer`.
- Current pin `bd66b99` is 9 commits above the v2.2.x base: 1 docs commit + 8 Android patch commits (clean linear stack, no merges).
- The 8 patch commits (oldest → newest):
  1. `a828cd9` — `fix: widen sprintf buf[128] to buf[256]` (GCodeProcessor.cpp, PrintObjectSlice.cpp)
  2. `010c1bb` — `fix: skip TBB thread pool barrier on Android` (Thread.cpp)
  3. `cc24c57` — `fix: harden Clipper1 against NaN/Inf coordinates + Android diag` (clipper.cpp, TreeSupport3D.cpp)
  4. `f11a7bf` — `fix(I2): clamp large-but-finite IntersectPoint q to Clipper hiRange` (clipper.cpp)
  5. `8ba027e` — `fix: guard Clipper IntersectPoint and Round against ARM64 int64 overflow` (clipper.cpp)
  6. `727ed76` — `fix: initialise uninitialised PrintObject/FakeWipeTower/Print members` (Print.hpp) — **B38**
  7. `3256df1` — `fix: fold H2C paint states 5-8 → 1-4 for Snapmaker U1` (PrintApply.cpp, TriangleSelector.cpp)
  8. `06f5c36` — `fix(triangle-selector): drop H2C fold from multi-state get_facets` (TriangleSelector.cpp)
  9. `bd66b99` — `docs(triangle-selector): explain single-state get_facets retains H2C fold` (TriangleSelector.cpp comment only)
- Merge base with upstream `2.3.2` (i.e. the upstream commit immediately below our patch stack) = `706508c`.
- **`v2.3.3` tag is already fetched in our fork** — no remote setup needed.
- **No GUI files touched by our patches.** No "heavy diagnostics" exist as separate commits. The original plan's Tasks 5–7 (drop heavy / GUI diag) are unnecessary.

## Conflict expectations

Files where v2.3.3 is likely to conflict with our patches (because PR #375 or
the 167 commits of drift touched them):

| Our commit | Files | Conflict risk | Why |
|---|---|---|---|
| `727ed76` (B38 inits) | Print.hpp | **Low** | M0 already verified `m_origin`/`m_isBBLPrinter` still exist with same names on `main`; FakeWipeTower struct at the same line range. |
| `3256df1` (H2C fold) | PrintApply.cpp, TriangleSelector.cpp | **High** | PR #375 added `MixedFilament` integration to both. |
| `06f5c36` (drop H2C fold from multi-state) | TriangleSelector.cpp | **High** | Same. |
| `bd66b99` (docs note) | TriangleSelector.cpp | **Medium** | Comment-only, but anchored at moved code. |
| `cc24c57` (Clipper NaN/Inf + tree support) | clipper.cpp, TreeSupport3D.cpp | **Low** | Vendored Clipper rarely changes; tree-support area not in #375. |
| `f11a7bf`, `8ba027e` (Clipper int64) | clipper.cpp | **Low** | Same. |
| `010c1bb` (TBB skip) | Thread.cpp | **Low** | Untouched by drift. |
| `a828cd9` (sprintf widen) | GCodeProcessor.cpp, PrintObjectSlice.cpp | **Medium** | #375 touched both, but at different sites. |

Hard rule: every `#ifdef __ANDROID__` block must remain intact through
conflict resolution. If a resolution requires unguarded code, stop and ask.

---

## Prerequisites

You are running in a worktree at
`D:\projects\u1-slicer-for-android\.claude\worktrees\feature+full-spectrum-m1`
on branch `worktree-feature+full-spectrum-m1` (branched from origin/main). The
roadmap doc lives at `docs/superpowers/specs/2026-05-26-full-spectrum-roadmap.md`.

The orca submodule is initialised in this worktree at HEAD `bd66b99`, and our
fork has `v2.3.3` already fetched. **Working directory** for all relative
paths below is the worktree root.

For pushes to our orca fork (`github.com/taylormadearmy/OrcaSlicer`),
authenticate as `taylormadearmy` per `CLAUDE.local.md`:

```bash
gh auth switch -u taylormadearmy
```

Task 1 was already completed manually in the controller session — baseline
verified: submodule HEAD `bd66b99`, `.so` 20,943,120 bytes / clang-17, 1,479
unit tests green. Skip Task 1 — it's preserved below for reference only.

---

## Task 1: Confirm baseline state ✅ DONE (already verified)

Confirmed: submodule HEAD `bd66b99`, committed `.so` is ~20 MB clang-17
Release, all 1,479 JVM unit tests green. No action required. (Re-run only if
working tree has changed since.)

---

## Task 2: Rebase Android patches onto v2.3.3 (in the orca fork)

**Goal:** Produce a new commit on top of `v2.3.3` that contains all 8 (+1
docs) of our Android patches, resolving conflicts per commit. This replaces
the original plan's Tasks 2–7 (extract diff, re-apply textually, drop heavy
diag) with one git-native operation.

**Files:**
- Modify: 8 commits worth of files in `app/src/main/cpp/orcaslicer/` (the submodule)
- Output: a new branch `feature/v2.3.3-android-m1` in the orca submodule, head SHA recorded

- [ ] **Step 1: Fetch latest from the fork (ensures v2.3.3 + main are current)**

```bash
git -C app/src/main/cpp/orcaslicer fetch --tags origin
git -C app/src/main/cpp/orcaslicer rev-parse v2.3.3
git -C app/src/main/cpp/orcaslicer rev-parse 706508c
```

Expected: `v2.3.3` resolves to a specific commit SHA; `706508c` resolves (it's the merge base of our patch stack with upstream).

- [ ] **Step 2: Create the rebase branch from v2.3.3**

```bash
git -C app/src/main/cpp/orcaslicer checkout -b feature/v2.3.3-android-m1 v2.3.3
git -C app/src/main/cpp/orcaslicer log --oneline -1
```

Expected: HEAD shows the v2.3.3 tag commit. Working tree clean.

- [ ] **Step 3: Run the rebase to replay our 8+1 commits onto v2.3.3**

```bash
cd app/src/main/cpp/orcaslicer
git rebase --onto feature/v2.3.3-android-m1 706508c bd66b99
cd ../../../../..
```

This replays each commit from `706508c..bd66b99` (9 commits: 8 patches + 1 docs) onto the tip of `feature/v2.3.3-android-m1`.

Possible outcomes per commit:
- **Clean apply** → rebase continues automatically.
- **Conflict** → rebase pauses with merge markers in conflicting files. Resolve, `git add`, then `git rebase --continue`.
- **Empty commit** → if upstream already incorporated the change, run `git rebase --skip`.

- [ ] **Step 4: Resolve conflicts per commit**

When the rebase pauses, find conflicts:

```bash
git -C app/src/main/cpp/orcaslicer status
git -C app/src/main/cpp/orcaslicer diff --name-only --diff-filter=U
```

For each conflicted file, open in an editor (Edit tool). The conflict markers `<<<<<<<` / `=======` / `>>>>>>>` separate the v2.3.3 version (above `=======`) from our patch (below `=======`). Resolution rules:

1. **Preserve every `#ifdef __ANDROID__ ... #endif` block** from our patch. These are non-negotiable.
2. **Adopt the v2.3.3 form** of any surrounding non-Android code.
3. **If the upstream code was refactored** such that our patch site no longer makes sense (e.g. the function was renamed or moved), re-apply our patch's *intent* at the new site rather than copying the old hunk verbatim. The commit message describes the intent.
4. **If the upstream code already does what our patch did** (e.g. upstream now also initialises `m_origin`), skip our patch with `git rebase --skip` after staging the upstream-as-is form. Note this in the final report.

After resolving:

```bash
git -C app/src/main/cpp/orcaslicer add <resolved-files>
git -C app/src/main/cpp/orcaslicer rebase --continue
```

Iterate until rebase completes (success message: `Successfully rebased and updated refs/heads/feature/v2.3.3-android-m1`).

- [ ] **Step 5: Verify the rebased history**

```bash
git -C app/src/main/cpp/orcaslicer log --oneline v2.3.3..HEAD
git -C app/src/main/cpp/orcaslicer log --oneline -1
```

Expected: 9 commits (or fewer if any were `--skip`ed), starting from a commit on top of v2.3.3. Record the new HEAD SHA — call it `${NEW_PIN}`.

- [ ] **Step 6: Smoke check — every `#ifdef __ANDROID__` block survived**

```bash
git -C app/src/main/cpp/orcaslicer grep -c '__ANDROID__' -- 'src/libslic3r/**' 'deps_src/clipper/**' | sort
```

Compare against the bd66b99 baseline:

```bash
git -C app/src/main/cpp/orcaslicer grep -c '__ANDROID__' bd66b99 -- 'src/libslic3r/**' 'deps_src/clipper/**' | sort
```

Expected: the counts in the rebased HEAD should match or exceed the bd66b99 counts. Any reduction → an `#ifdef __ANDROID__` block was dropped during conflict resolution → STOP and re-examine.

- [ ] **Step 7: Commit nothing in the parent repo yet**

The parent repo's submodule pointer still says `bd66b99`. Updating it happens in Task 3.

---

## Task 3: Push the rebased branch and update the parent submodule pin

**Files:**
- Modify: `app/src/main/cpp/orcaslicer` (gitlink pointer in the parent repo)

- [ ] **Step 1: Verify gh auth identity**

```bash
gh auth status 2>&1 | grep 'Active account'
```

Expected: shows `taylormadearmy`. If not, switch:

```bash
gh auth switch -u taylormadearmy
```

- [ ] **Step 2: Push the rebased branch to the fork**

```bash
git -C app/src/main/cpp/orcaslicer push -u origin feature/v2.3.3-android-m1
```

Expected: branch pushed; no errors. Confirm the push:

```bash
gh api repos/taylormadearmy/OrcaSlicer/branches/feature/v2.3.3-android-m1 --jq '.commit.sha'
```

Should match the local `${NEW_PIN}`.

- [ ] **Step 3: Stage the parent repo submodule pointer update**

```bash
git status
git diff app/src/main/cpp/orcaslicer
```

Expected: submodule shows as modified (gitlink pointer changed from `bd66b99` to `${NEW_PIN}`).

```bash
git add app/src/main/cpp/orcaslicer
```

- [ ] **Step 4: Commit the pin bump (alone — no .so rebuild yet)**

```bash
git commit -m "chore(native): bump orca submodule to v2.3.3 + Android patches

Rebased 8 Android patch commits onto Snapmaker v2.3.3 (2026-06-01,
first tag with PR #375 'mix filament'). 167 commits of upstream drift
absorbed by per-commit rebase conflict resolution.

bd66b99 (v2.2.4-9-...) -> \${NEW_PIN} (v2.3.3-+-...)

New orca branch: feature/v2.3.3-android-m1 (pushed to fork).

Native .so NOT rebuilt yet — APK build will reference the stale .so
until later in M1 stage 1. Tests will fail until rebuild lands.

Refs: docs/superpowers/specs/2026-05-26-full-spectrum-roadmap.md
"
```

(Substitute the actual new SHA for `\${NEW_PIN}`.)

This isolates the pin bump from .so rebuild so a bisect can pinpoint which step broke a test.

---

## Task 4: First native build attempt (will fail — captures SAPIL API breakage)

Per the M1 pre-flight: the real cost is SAPIL API-compat against the new
libslic3r. We expect compile errors. This task is a controlled failure to
generate a complete list of what needs fixing.

**Files:**
- Read: `app/src/main/cpp/src/sapil_*.cpp`, `slicer_wrapper.cpp` (will compile against new libslic3r)

- [ ] **Step 1: Set up a fresh CMake build directory**

Per `CLAUDE.md` §"Native Rebuild" §"Fresh build":

```bash
CMAKE_BIN="D:/Android/Sdk/cmake/3.22.1/bin/cmake.exe"
NINJA_BIN="D:/Android/Sdk/cmake/3.22.1/bin/ninja.exe"
NDK="D:/Android/Sdk/ndk/26.1.10909125"
BUILD_DIR="app/.cxx/Release/m1-stage1/arm64-v8a"
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

The `-Wl,-z,max-page-size=16384` flag is the **16KB-alignment add** the user requested in commit `9e10334`. We bake it in at first build so we don't forget.

- [ ] **Step 2: Run the build (expect failures)**

```bash
mkdir -p /d/tmp/full-spectrum-m1
cd "$BUILD_DIR"
"$NINJA_BIN" -j1 2>&1 | tee /d/tmp/full-spectrum-m1/build1-errors.log
echo "EXIT=$?"
cd -
```

Expected: build fails somewhere in `sapil_*.cpp` or `slicer_wrapper.cpp` with errors like "no member named X", "no matching function", "no type named Y in namespace `Slic3r`". This is the API-drift surface we need to fix.

- [ ] **Step 3: Categorise the errors**

```bash
grep -E 'error:' /d/tmp/full-spectrum-m1/build1-errors.log | head -40
grep -cE 'error:' /d/tmp/full-spectrum-m1/build1-errors.log
```

Group by file (`sapil_print.cpp`, `sapil_bambu_*.cpp`, `slicer_wrapper.cpp`, etc.) and by error type (renamed member, changed signature, removed class). Save the categorisation to `/d/tmp/full-spectrum-m1/build1-errors-summary.txt`.

- [ ] **Step 4: Commit nothing yet**

---

## Task 5: Fix SAPIL wrapper compile errors against new libslic3r API

**This is the open-ended task.** We don't know in advance exactly what
changed in `Print`, `Model`, `PrintConfig`, `PresetBundle`, `Config` between
`bd66b99` and `v2.3.3` — we'll discover it from the build failures. PR #375
introduced `MixedFilament` / `MixedFilamentManager` classes and modified
many libslic3r files; some signatures our SAPIL code touches likely changed.

**Files:** any of `app/src/main/cpp/src/sapil_*.cpp`, `app/src/main/cpp/src/slicer_wrapper.cpp`, `app/src/main/cpp/include/sapil.h`.

- [ ] **Step 1: For each compile error, identify the upstream API change**

For each `error:` line in `build1-errors.log`:
1. Read the surrounding code in our SAPIL file.
2. Open the corresponding libslic3r header at the new pin (e.g. `app/src/main/cpp/orcaslicer/src/libslic3r/Print.hpp`).
3. Reference the equivalent header at `bd66b99` to see how the API moved (`git -C app/src/main/cpp/orcaslicer show bd66b99:src/libslic3r/Print.hpp`).
4. Update our SAPIL call site to match the new API.

Common patterns expected (per the libslic3r files #375 touched):
- `MixedFilament` / `MixedFilamentManager` additions are new — won't cause errors, just new code that doesn't affect us yet.
- `PresetBundle` may have new mixed-filament accessors; existing accessors should still work.
- `Print` / `PrintObject` may have renamed members.
- `PrintConfig` adds keys but the access pattern (`config.option<T>("key_name")`) is unchanged.
- `Model` may have new fields for mixed-filament state; existing accessors should still work.

- [ ] **Step 2: For each fix, write a single-purpose code change**

Use Edit tool to change one site at a time. Do NOT refactor or "improve" surrounding code while you're there — minimum-diff fixes only. We want a clean bisect target if a fix turns out to be wrong.

- [ ] **Step 3: Re-build incrementally**

```bash
cd "$BUILD_DIR" && "$NINJA_BIN" -j1 2>&1 | tee /d/tmp/full-spectrum-m1/build2-errors.log; cd -
```

Iterate until the build succeeds (EXIT=0) or you've run out of progress and need to ask.

- [ ] **Step 4: Commit nothing yet — wait for a clean build**

---

## Task 6: Strip + verify the built `.so`

**Files:**
- Read: `$BUILD_DIR/libprusaslicer-jni.so` (unstripped, from build)
- Create: `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so` (stripped, replaces the committed binary)

- [ ] **Step 1: Locate the unstripped .so**

```bash
find "$BUILD_DIR" -name 'libprusaslicer-jni.so' 2>&1
```

(The exact subdirectory CMake places it in depends on the project's CMakeLists.txt — it's usually at the BUILD_DIR root, but verify.)

Set `UNSTRIPPED=<path>` for the next step.

- [ ] **Step 2: Strip the binary**

```bash
LLVMSTRIP="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe"
"$LLVMSTRIP" --strip-unneeded \
  "$UNSTRIPPED" \
  -o app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so
```

- [ ] **Step 3: Verify size**

```bash
ls -la app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so
```

Expected: **19–22 MB** (slightly larger ceiling than before; v2.3.3 adds the FilamentMixer + Local-Z code paths). If 50 MB+, build is Debug — go back and re-check `CMAKE_BUILD_TYPE` in `CMakeCache.txt`. Per CLAUDE.md "NEVER ship Debug .so" memory.

- [ ] **Step 4: Verify compiler is clang 17**

```bash
LLVMRE="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe"
"$LLVMRE" -p .comment app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so | head -3
```

Expected: `clang version 17.0.2`.

- [ ] **Step 5: Verify JNI symbol completeness**

```bash
JNI_COUNT=$("$LLVMRE" -p .dynsym app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so | grep -c 'Java_com_u1_slicer_NativeLibrary')
KOTLIN_COUNT=$(grep -cE 'external fun' app/src/main/java/com/u1/slicer/NativeLibrary.kt)
echo "JNI symbols in .so: $JNI_COUNT"
echo "external fun in Kotlin: $KOTLIN_COUNT"
```

Expected: equal. If they differ, the build dropped JNI methods — per CLAUDE.md, that means a worktree-only source file wasn't picked up by CMake (re-check the build glob input).

- [ ] **Step 6: Verify 16KB page alignment**

```bash
"$LLVMRE" -l app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so | grep -E '^\s+LOAD' | head -5
```

Expected: the `Align` column shows `0x4000` (= 16384 = 16KB) for every `LOAD` segment. If you see `0x1000` (4KB), the linker flag didn't take effect — go back to Task 4 Step 1 and confirm `-Wl,-z,max-page-size=16384` is in `CMAKE_SHARED_LINKER_FLAGS`.

- [ ] **Step 7: Commit nothing yet**

---

## Task 7: Clean build of the APK + JVM unit-test regression

This is the primary regression gate.

- [ ] **Step 1: Clean Gradle build**

```bash
./gradlew clean --no-daemon
./gradlew assembleDebug --no-daemon 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL`. If Kotlin compile errors appear (we might have broken `NativeLibrary.kt` or a caller), fix them.

- [ ] **Step 2: Run JVM unit-test suite**

```bash
./gradlew testDebugUnitTest --no-daemon 2>&1 | tail -40
```

Expected: **all 1,479 tests pass** (the baseline established in Task 1). If any fail, they are the regression surface — investigate root cause per case. Do NOT relax assertions to make tests pass (per CLAUDE.md hard rule).

- [ ] **Step 3: Record results**

```bash
./gradlew testDebugUnitTest --no-daemon 2>&1 | grep -E 'BUILD|tests completed' > /d/tmp/full-spectrum-m1/unit-test-results.txt
cat /d/tmp/full-spectrum-m1/unit-test-results.txt
```

- [ ] **Step 4: Commit nothing yet (wait for instrumented tests too)**

---

## Task 8: Instrumented-test regression (real device)

**Per CLAUDE.local.md**, default to single device pin to avoid file-lock contention.

- [ ] **Step 1: Verify a device is attached**

```bash
adb devices
```

Expected: at least one device in `device` state. If multiple devices, pin to one (e.g. Pixel 8a `43211JEKB16931` per CLAUDE.local).

- [ ] **Step 2: Run the full instrumented suite**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest --no-daemon 2>&1 | tee /d/tmp/full-spectrum-m1/inst-test-output.log | tail -50
```

Expected: **all 403 instrumented tests pass** (per CLAUDE.md). The suite takes 25–35 minutes.

- [ ] **Step 3: Inspect failures (if any)**

```bash
grep -E 'FAILED|failed:' /d/tmp/full-spectrum-m1/inst-test-output.log | head -30
```

For each failure:
1. Read the test class + method name.
2. Open the failing test in `app/src/androidTest/...` and read its assertion.
3. Open any G-code / 3MF output the test inspects (the test usually points to the path).
4. Determine if the change reflects an upstream behaviour change in the engine or a bug we introduced. If it's an upstream behaviour change that's defensible (e.g. slightly different default value), document it and ask the user before either relaxing the test or treating it as a regression. **Do not silently soften assertions.**

- [ ] **Step 4: Commit nothing yet**

---

## Task 9: G-code equivalence spot-check on representative fixtures

A test-suite pass means tested behaviour is preserved; this task validates
*untested-but-representative* slicing by diffing G-code output before and
after the bump on three fixtures.

**Fixtures:** pick existing test assets per CLAUDE.md slicing test descriptions:
- A single-colour STL (e.g. the Benchy STL)
- A 4-colour Bambu 3MF (e.g. `colored_3DBenchy` or Dragon-Scale fixture)
- A painted H2C 3MF (the H2C benchy fixture)

These are all present under `app/src/androidTest/assets/` or referenced from
fixtures. Locate them with `find app/src/androidTest -name '*.3mf' -o -name '*.stl' | head -20`.

**Files:**
- Create: `/d/tmp/full-spectrum-m1/gcode-spotcheck/before-<fixture>.gcode` (from original worktree)
- Create: `/d/tmp/full-spectrum-m1/gcode-spotcheck/after-<fixture>.gcode` (from this worktree)
- Create: `/d/tmp/full-spectrum-m1/gcode-spotcheck/diff-<fixture>.txt`

- [ ] **Step 1: Capture the "before" G-code from the original worktree**

If the original worktree has slicing tests that emit G-code to known paths, use those. Otherwise, write a one-off Kotlin test (`@Test`) in `app/src/androidTest/` that invokes `NativeLibrary.slice()` for each fixture and copies the resulting G-code to `/sdcard/full-spectrum-m1/before-<fixture>.gcode`, run it on the *original* worktree's branch (`fix/b139-locale-decimal-input` or `main`).

- [ ] **Step 2: Capture the "after" G-code**

Run the same test in this worktree, output to `/sdcard/full-spectrum-m1/after-<fixture>.gcode`.

- [ ] **Step 3: Diff**

For each fixture:

```bash
adb pull /sdcard/full-spectrum-m1/. /d/tmp/full-spectrum-m1/gcode-spotcheck/
diff -u /d/tmp/full-spectrum-m1/gcode-spotcheck/before-<fixture>.gcode \
        /d/tmp/full-spectrum-m1/gcode-spotcheck/after-<fixture>.gcode \
        > /d/tmp/full-spectrum-m1/gcode-spotcheck/diff-<fixture>.txt
wc -l /d/tmp/full-spectrum-m1/gcode-spotcheck/diff-<fixture>.txt
```

Expected: small diff (header comments, timestamps may differ). Material differences in extrusion lines should be investigated. **Acceptable** diffs: timestamps, generator version strings, comment lines, minor wipe-tower position deltas if the engine refined the algorithm. **Unacceptable**: changes in extrusion volumes, missing tool changes, layer count changes — these are real regressions to investigate.

- [ ] **Step 4: Record findings**

Write a brief summary of each diff to `/d/tmp/full-spectrum-m1/gcode-spotcheck-summary.txt` (1–3 lines per fixture: "single-colour STL — diff: header only, identical extrusions / wipe-tower x shifted 0.02 mm / etc."). If you find unacceptable diffs, stop and ask.

---

## Task 10: Final commit

- [ ] **Step 1: Verify the final state of changes**

```bash
git status
git log --oneline -3
git diff --stat HEAD~1..HEAD   # what Task 3 added (submodule pin) — verify Tasks 4-9 left it unchanged
git diff --stat app/src/main/jniLibs/   # the rebuilt .so
git diff --stat app/src/main/cpp/src/   # SAPIL wrapper fixes
```

Expected:
- The submodule gitlink is still at the rebased commit (committed in Task 3).
- `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so` is modified (rebuilt) — uncommitted until this task.
- `app/src/main/cpp/src/sapil_*.cpp` and/or `slicer_wrapper.cpp` and/or `app/src/main/cpp/include/sapil.h` modified (uncommitted SAPIL fixes from Task 5).

- [ ] **Step 2: Stage and commit**

```bash
git add app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so \
        app/src/main/cpp/src/ \
        app/src/main/cpp/include/
git commit -m "native: rebuild .so for v2.3.3 (16KB-aligned) + SAPIL API-compat

M1 stage 1 final commit. Native libprusaslicer-jni.so rebuilt against
the v2.3.3-based submodule pin (set in the prior commit). Release,
16KB-page-aligned (-Wl,-z,max-page-size=16384) for Android 15+ compat.
Verified ~20 MB, clang 17, JNI symbol count matches NativeLibrary.kt,
LOAD segments aligned 0x4000.

SAPIL wrapper API-compat fixes in app/src/main/cpp/src/sapil_*.cpp,
slicer_wrapper.cpp — addresses libslic3r drift between bd66b99 and
v2.3.3 (167 commits, including PR #375 'mix filament').

**Full-spectrum capability is now present in the engine but NOT exposed.**
No new config keys wired, no UI, no overrides. Pure regression: all
1,479 unit tests + N instrumented tests pass; G-code spot-check on
three representative fixtures shows acceptable diffs.

Refs:
- docs/superpowers/specs/2026-05-26-full-spectrum-roadmap.md (M1 stage 1)
- docs/superpowers/plans/2026-06-05-full-spectrum-m1-stage1-engine-bump.md
"
```

(Substitute the actual instrumented test count for `N`.)

- [ ] **Step 3: Do NOT push yet**

Per repo convention and CLAUDE.md "DO NOT push to the remote repository unless the user explicitly asks." Ask the user before pushing the parent-repo branch.

The orca submodule branch `feature/v2.3.3-android-m1` was already pushed in Task 3 Step 2 — that's expected (the submodule needs to be pushable so the gitlink resolves for anyone else cloning).

---

## Out of scope for M1 stage 1

These belong to follow-up plans, **not** this stage:

- **Wiring `mixed_filament_definitions` + the other 18 mixed/dithering config keys** through `applyConfigToPrusa()` / `profile_keys[]` / `buildProfileOverrides()` — that is M1 stage 2 / M3.
- **Designing the M3 Compose UI** for the colour picker — M3.
- **Smart Paint slot-width widening** (per-triangle slot byte from `0..3` to virtual IDs ≥4) — M3a.
- **Prusa `prusa-fdm-mixer` integration** — M4.
- **A real U1 mixed-filament print** — M2.
- **Updating `ENGINE_UPGRADE_GUIDE.md`'s "Current State" section and patch-mechanism description** to reflect that patches are committed on our fork, not uncommitted in worktrees — small follow-up doc commit, not part of this engine bump.

## Self-review notes

- Spec §M1 ("Engine bump") + §M1 pre-flight + 2026-06-05 status check are all covered by Tasks 1–10.
- 16KB-alignment note (commit 9e10334) is folded into Task 4 Step 1 (CMake configure) and verified in Task 6 Step 6.
- All 8 must-fix patch commits are explicitly addressed by Task 2's rebase.
- The "drop heavy/GUI diagnostics" tasks from the original plan are removed — they were based on a wrong understanding of the patch state (the heavy diagnostics never existed as separate commits in our fork).
- SAPIL API-compat handled as open-ended Tasks 4 + 5 — unavoidable, since we can't enumerate API changes without running the build.
- Test oracle: existing 1,479 unit + N instrumented tests + 3 spot-check fixtures (Tasks 7–9).
- No new tests written this stage — by design (regression-only; new behaviour is gated on M1 stage 2).
