# Full-Spectrum M1 Stage 1 — Engine Bump (Regression-Only)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bump the OrcaSlicer submodule from `bd66b99` (2026-05-01) to `v2.3.3`
(2026-06-01 — first tagged release containing the full-spectrum / mix-filament
feature, PR #375). Re-apply the Android patch catalog over the new base.
Rebuild the native `libprusaslicer-jni.so` (Release, 16KB-page-aligned). Verify
the full existing test suite stays green. **Full-spectrum capability is now
PRESENT in the engine but NOT exposed** — no new config keys, no UI, no
overrides. This stage is pure regression: prove the bumped engine slices our
existing fixtures identically to before.

**Architecture:** Three phases. **Phase A** — submodule bump + patch re-apply
over the new base, expecting the must-fix B38 inits + 4 build-compat fixes to
need manual care, with everything else either clean-apply or deferred. **Phase
B** — make our SAPIL wrapper (`app/src/main/cpp/src/sapil_*.cpp`) compile
against the changed libslic3r API (this is the dominant cost — 167 commits of
upstream drift, including #375's changes to `Print`, `Model`, `PrintConfig`,
`PresetBundle`). **Phase C** — Release build with 16KB-alignment flag, full
test suite as the regression oracle, spot-check three representative fixtures
for G-code equivalence, commit.

**Tech Stack:** Kotlin 1.9.22 + Compose + JNI C++; CMake 3.22.1 + Ninja
(NDK 26.1.10909125, Clang 17); pre-built `.so` shipped via Gradle. Submodule:
`Snapmaker/OrcaSlicer` (`app/src/main/cpp/orcaslicer`). Native source:
`app/src/main/cpp/src/sapil_*.cpp` (outside submodule).

**Reference docs:**
- Spec: [`docs/superpowers/specs/2026-05-26-full-spectrum-roadmap.md`](../specs/2026-05-26-full-spectrum-roadmap.md) — read §M1 pre-flight + §7 (status checks) before starting.
- Engine upgrade procedure: [`ENGINE_UPGRADE_GUIDE.md`](../../../ENGINE_UPGRADE_GUIDE.md) — patch catalog at §"Patch Catalog".
- Native rebuild checklist: `CLAUDE.md` §"Native Rebuild" — **MUST follow** the NDK 26 / Release / size + compiler verification steps exactly.

---

## Prerequisites

You are running in a worktree at
`D:\projects\u1-slicer-for-android\.claude\worktrees\feature+full-spectrum-m1`
on branch `worktree-feature+full-spectrum-m1` (branched from origin/main). The
roadmap doc lives at `docs/superpowers/specs/2026-05-26-full-spectrum-roadmap.md`.

The orca submodule was initialised in this worktree (via
`git submodule update --init --recursive app/src/main/cpp/orcaslicer`). The
submodule HEAD is `bd66b99` *clean* — i.e. without our Android patches, which
exist as uncommitted modifications in the **original** worktree's submodule at
`D:\projects\u1-slicer-orca\app\src\main\cpp\orcaslicer\`. You will extract
them in Task 2.

**Working directory** for all relative paths below is the worktree root:
`D:\projects\u1-slicer-for-android\.claude\worktrees\feature+full-spectrum-m1`.

---

## Task 1: Confirm baseline state

**Files:**
- Read: `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so` (committed binary)

- [ ] **Step 1: Confirm submodule HEAD is `bd66b99`**

```bash
git submodule status app/src/main/cpp/orcaslicer
```

Expected: ` bd66b99b2d2b69b7d6bb7d14d30cc74c37c6424b app/src/main/cpp/orcaslicer (heads/...)` — note the leading **space** (initialised, clean), not `-` (uninitialised) or `+` (modified).

- [ ] **Step 2: Confirm the committed `.so` is Release-built and clang-17**

```bash
NDK=D:/Android/Sdk/ndk/26.1.10909125
LLVMRE="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe"
ls -la app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so
"$LLVMRE" -p .comment app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so | head -3
```

Expected: file size ~19–21 MB; `.comment` shows `clang version 17.0.2` (per CLAUDE.md). If size is 50 MB+ or compiler is not clang-17, the baseline is already wrong — stop and ask.

- [ ] **Step 3: Confirm the JVM unit-test baseline is green**

```bash
./gradlew testDebugUnitTest --no-daemon 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, all unit tests pass. This is the regression oracle for later tasks. If anything fails on the unchanged code, **stop** and ask — the worktree itself is wrong.

- [ ] **Step 4: Commit nothing**

This task is read-only. No commit.

---

## Task 2: Extract current Android patches from the source-of-truth worktree

**Goal:** Capture the ~2,400 lines of `#ifdef __ANDROID__` patches (B38 inits,
NDK build-compat fixes, diagnostics) that live as uncommitted modifications in
the original worktree's submodule. Save as a single diff we can reference and
selectively re-apply.

**Files:**
- Create: `D:\tmp\full-spectrum-m1\bd66b99-android-patches.diff`
- Create: `D:\tmp\full-spectrum-m1\bd66b99-android-patches-stat.txt`
- Read: `D:\projects\u1-slicer-orca\app\src\main\cpp\orcaslicer\` (source worktree's patched submodule)

- [ ] **Step 1: Create the staging dir**

```bash
mkdir -p /d/tmp/full-spectrum-m1
```

- [ ] **Step 2: Export the patches as a unified diff**

```bash
git -C /d/projects/u1-slicer-orca/app/src/main/cpp/orcaslicer diff > /d/tmp/full-spectrum-m1/bd66b99-android-patches.diff
git -C /d/projects/u1-slicer-orca/app/src/main/cpp/orcaslicer diff --stat > /d/tmp/full-spectrum-m1/bd66b99-android-patches-stat.txt
wc -l /d/tmp/full-spectrum-m1/bd66b99-android-patches.diff
cat /d/tmp/full-spectrum-m1/bd66b99-android-patches-stat.txt | tail -30
```

Expected: ~2,000–2,500 line diff; the `--stat` summary should mention `Print.hpp`, `WipeTower.hpp`, `CutSurface.cpp`, `Brim.cpp`, `clipper.hpp`, `NSVGUtils.cpp`, `GCodeWriter.cpp`, `WipeTower2.cpp`, `Print.cpp`. (Heavy diagnostics — `GCode.cpp`, `ClipperUtils.cpp`, `deps_src/clipper/clipper.cpp` — may also be there but we will drop them.)

- [ ] **Step 3: Cross-check against the patch catalog**

Open `ENGINE_UPGRADE_GUIDE.md` and confirm that every file in the `--stat` summary is mentioned in §"Patch Catalog" under "Critical Bug Fixes", "Build Compatibility Fixes", or "Diagnostics" / "Heavy Diagnostics". If you see a file in the diff that is **not** in the catalog, stop — it's an undocumented patch and needs the user's decision (re-apply or drop) before proceeding.

- [ ] **Step 4: Commit nothing**

This task produces files outside the repo (in `D:\tmp`). No commit.

---

## Task 3: Bump the orca submodule to `v2.3.3`

**Files:**
- Modify: `app/src/main/cpp/orcaslicer` (gitlink — the submodule pointer in the parent repo)
- The submodule's working tree itself will check out the `v2.3.3` tag commit.

- [ ] **Step 1: Fetch upstream tags**

```bash
git -C app/src/main/cpp/orcaslicer fetch --tags origin
```

Expected: no errors. Verify the tag exists:

```bash
git -C app/src/main/cpp/orcaslicer tag --list 'v2.3.*'
```

Expected: list includes `v2.3.0`, `v2.3.1`, `v2.3.3`.

- [ ] **Step 2: Checkout v2.3.3 in the submodule**

```bash
git -C app/src/main/cpp/orcaslicer checkout v2.3.3
git -C app/src/main/cpp/orcaslicer log --oneline -1
```

Expected: HEAD is now the tagged commit for v2.3.3 (one specific commit hash — record it).

- [ ] **Step 3: Update nested submodules of orca**

```bash
git -C app/src/main/cpp/orcaslicer submodule update --init --recursive
```

Expected: no errors. Some nested submodules may already be initialised from the prior pin; that's fine.

- [ ] **Step 4: Stage the gitlink update**

```bash
git -C . status
git -C . diff app/src/main/cpp/orcaslicer
```

Expected: `app/src/main/cpp/orcaslicer` shows as modified with the new gitlink SHA.

```bash
git add app/src/main/cpp/orcaslicer
```

- [ ] **Step 5: Commit the bump (alone — no patches yet)**

```bash
git commit -m "chore(native): bump orca submodule to v2.3.3 (no patches re-applied yet)

bd66b99 (2026-05-01) -> v2.3.3 (2026-06-01, first tag with PR #375).
167 commits of upstream drift. Patches re-applied in follow-up commits.
Native .so NOT rebuilt yet — APK build will reference the stale .so until
later in M1 stage 1.

Refs: docs/superpowers/specs/2026-05-26-full-spectrum-roadmap.md
"
```

This commit isolates the bump from the patch re-apply so a bisect can pinpoint which step broke a test if something goes wrong.

---

## Task 4: Re-apply B38 init patches to `Print.hpp` and `WipeTower.hpp`

**Critical bug fixes — these are MUST-REAPPLY** (verified still needed on
`main`; uninitialised members would silently corrupt wipe tower moves on
Android release builds).

**Files:**
- Modify: `app/src/main/cpp/orcaslicer/src/libslic3r/Print.hpp`
- Modify: `app/src/main/cpp/orcaslicer/src/libslic3r/GCode/WipeTower.hpp`

- [ ] **Step 1: Locate `m_origin` and `m_isBBLPrinter` in the new `Print.hpp`**

```bash
grep -nE 'Vec3d\s+m_origin\s*;|bool\s+m_isBBLPrinter\s*;' app/src/main/cpp/orcaslicer/src/libslic3r/Print.hpp
```

Expected: two matches, on lines roughly in the 1100–1130 range (the line numbers may have drifted slightly from `bd66b99`).

- [ ] **Step 2: Initialise `m_isBBLPrinter`**

Use Edit tool. Match the line containing `bool m_isBBLPrinter;` exactly and change to `bool m_isBBLPrinter = false;`.

- [ ] **Step 3: Initialise `m_origin`**

Match the line containing `Vec3d m_origin;` (note: it may be `Vec3d   m_origin;` with extra spaces — match exactly what's there) and change to `Vec3d m_origin = Vec3d::Zero();`.

- [ ] **Step 4: Locate the `FakeWipeTower` struct**

```bash
grep -nE 'struct\s+FakeWipeTower' app/src/main/cpp/orcaslicer/src/libslic3r/Print.hpp
```

Expected: one match near line 630. Open the struct and identify the member variables that must be zero-initialised. Per the patch catalog: `pos`, `width`, `height`, `layer_height`, `depth`, `brim_width`, `rotation_angle`, `cone_angle`, `plate_origin`.

- [ ] **Step 5: Initialise FakeWipeTower members**

For each member, replace the declaration with an initialised form. Numeric scalars → `= 0;` (or `= 0.f;` for `float`/`double` depending on the declared type), `Vec3d` / `Vec2d` members → `= Vec3d::Zero();` / `= Vec2d::Zero();`.

Example (will not match verbatim — check actual types in the file):

```cpp
struct FakeWipeTower
{
    Vec3d  pos             = Vec3d::Zero();
    float  width           = 0.f;
    float  height          = 0.f;
    float  layer_height    = 0.f;
    float  depth           = 0.f;
    float  brim_width      = 0.f;
    float  rotation_angle  = 0.f;
    float  cone_angle      = 0.f;
    Vec3d  plate_origin    = Vec3d::Zero();
    // ... rest unchanged
};
```

Cross-reference with the original patched file (`D:\projects\u1-slicer-orca\app\src\main\cpp\orcaslicer\src\libslic3r\Print.hpp`) for the exact initialisation forms — the types may have changed in v2.3.3.

- [ ] **Step 6: Initialise `m_cur_layer_id` in `WipeTower.hpp`**

```bash
grep -n 'size_t m_cur_layer_id' app/src/main/cpp/orcaslicer/src/libslic3r/GCode/WipeTower.hpp
```

Expected: one match. Edit to `size_t m_cur_layer_id = 0;`.

- [ ] **Step 7: Verify the changes are syntactically valid**

```bash
git -C app/src/main/cpp/orcaslicer diff src/libslic3r/Print.hpp src/libslic3r/GCode/WipeTower.hpp
```

Expected: only the initialiser additions; no other unrelated changes.

- [ ] **Step 8: Commit the B38 inits**

```bash
git -C . status   # confirm submodule shows as modified (working tree has uncommitted submodule changes)
```

The parent repo will show the submodule as `+` (modified). We do NOT stage the submodule pin again here — the patches live as uncommitted changes inside the submodule, intentionally (per `ENGINE_UPGRADE_GUIDE.md` and the "Submodule shows dirty after commit — Expected" CLAUDE.md note).

There is nothing to commit in the *parent* repo at this step (the submodule pointer is unchanged from Task 3). The patches are intentionally uncommitted within the submodule — they're our Android-specific overlay.

---

## Task 5: Re-apply build-compatibility patches (NDK clang)

These fix compilation errors when building with Android NDK 26 / clang 17.
None of them collided with #375 per the M1 pre-flight, but v2.3.3 brings 167
commits of drift on top of `bd66b99`, so some upstream changes may have
landed in these files. Apply with `git apply --3way` first; fall back to
manual application per file if --3way rejects.

**Files (all under `app/src/main/cpp/orcaslicer/`):**
- Modify: `src/libslic3r/CutSurface.cpp`
- Modify: `src/libslic3r/Brim.cpp`
- Modify: `src/libslic3r/clipper.hpp`
- Modify: `src/libslic3r/NSVGUtils.cpp`
- Modify: various files (STL header additions)

- [ ] **Step 1: Extract just the build-compat hunks from the saved diff**

The saved diff at `/d/tmp/full-spectrum-m1/bd66b99-android-patches.diff` contains all patches. Slice it to just the build-compat files using `git apply --include`:

```bash
cd app/src/main/cpp/orcaslicer
git apply --3way \
  --include='src/libslic3r/CutSurface.cpp' \
  --include='src/libslic3r/Brim.cpp' \
  --include='src/libslic3r/clipper.hpp' \
  --include='src/libslic3r/NSVGUtils.cpp' \
  /d/tmp/full-spectrum-m1/bd66b99-android-patches.diff 2>&1 | tee /d/tmp/full-spectrum-m1/apply-buildcompat.log
echo "EXIT=$?"
cd ../../../../..
```

Expected: clean apply (EXIT=0) **or** a few `<<<<<<<`/`=======`/`>>>>>>>` 3-way merge markers if upstream changed any of these files.

- [ ] **Step 2: Resolve any conflicts**

If `git apply` reports conflicts:

```bash
grep -rln '<<<<<<<' app/src/main/cpp/orcaslicer/src/libslic3r/CutSurface.cpp app/src/main/cpp/orcaslicer/src/libslic3r/Brim.cpp app/src/main/cpp/orcaslicer/src/libslic3r/clipper.hpp app/src/main/cpp/orcaslicer/src/libslic3r/NSVGUtils.cpp
```

For each conflicted file, open it, find the markers, and resolve. The Android-side intent for each file is documented in `ENGINE_UPGRADE_GUIDE.md` §"Build Compatibility Fixes":
- `CutSurface.cpp` — qualify `ClipperLib::PolyFillType` with `Slic3r::` prefix
- `Brim.cpp` — qualify `Polygon`/`Point` types with `Slic3r::` prefix
- `clipper.hpp` — replace `using` declarations with forward declarations
- `NSVGUtils.cpp` — namespace-qualified function calls

Reference the original patched files in `D:\projects\u1-slicer-orca\app\src\main\cpp\orcaslicer\` for the exact text where context is unclear.

- [ ] **Step 3: Add missing STL headers**

The catalog mentions "Various files: `#include <sstream>`, `<limits>`, `<deque>`, `<dlfcn.h>`". Find which files need them by searching the original diff:

```bash
grep -B1 '#include <sstream>\|#include <limits>\|#include <deque>\|#include <dlfcn.h>' /d/tmp/full-spectrum-m1/bd66b99-android-patches.diff | grep -E '^\+\+\+ |^@@ '
```

For each file identified, open the file in the new submodule checkout and add the include at the same logical position (typically near the top with other system includes). If `git apply --3way` already added them, great; if not, edit manually.

- [ ] **Step 4: Verify no merge markers remain**

```bash
grep -rln '<<<<<<<\|=======\|>>>>>>>' app/src/main/cpp/orcaslicer/src/libslic3r/ 2>&1
```

Expected: no output.

- [ ] **Step 5: Commit nothing in the parent repo (patches still uncommitted inside the submodule)**

---

## Task 6: Re-apply must-keep lightweight diagnostics

Per the patch catalog "Diagnostics (SHOULD re-apply)" — these are
`#ifdef __ANDROID__` safety nets that emit diagnostics events when something
goes wrong. They have caught real regressions and are cheap.

**Files:**
- Modify: `app/src/main/cpp/orcaslicer/src/libslic3r/GCodeWriter.cpp` (coordinate bounds check)
- Modify: `app/src/main/cpp/orcaslicer/src/libslic3r/GCode/WipeTower2.cpp` (TCR bad-position logging — lightweight portions only)
- Modify: `app/src/main/cpp/orcaslicer/src/libslic3r/Print.cpp` (first-layer islands logging)

- [ ] **Step 1: Apply the lightweight diagnostic hunks**

```bash
cd app/src/main/cpp/orcaslicer
git apply --3way \
  --include='src/libslic3r/GCodeWriter.cpp' \
  --include='src/libslic3r/GCode/WipeTower2.cpp' \
  --include='src/libslic3r/Print.cpp' \
  /d/tmp/full-spectrum-m1/bd66b99-android-patches.diff 2>&1 | tee /d/tmp/full-spectrum-m1/apply-diag-light.log
echo "EXIT=$?"
cd ../../../../..
```

**Caveat:** the diff for `WipeTower2.cpp` and `Print.cpp` may contain BOTH the lightweight diagnostics AND heavy diagnostics. If `git apply --3way` pulls in the heavy hunks too, we'll back them out in Task 7. For now, accept whatever applies cleanly.

- [ ] **Step 2: Resolve any conflicts**

If `git apply` reports conflicts, find them:

```bash
grep -rln '<<<<<<<' app/src/main/cpp/orcaslicer/src/libslic3r/GCodeWriter.cpp app/src/main/cpp/orcaslicer/src/libslic3r/GCode/WipeTower2.cpp app/src/main/cpp/orcaslicer/src/libslic3r/Print.cpp
```

For each conflicted file, open it and resolve the `<<<<<<<` / `=======` / `>>>>>>>` markers manually. The diagnostic intent per `ENGINE_UPGRADE_GUIDE.md` §"Diagnostics":
- `GCodeWriter.cpp` — coordinate bounds check in `travel_to_xy`/`extrude_to_xy` (>500 mm triggers a native-backtrace event)
- `WipeTower2.cpp` — `construct_tcr()` bad-position logging, `finish_layer()` and `tool_change()` state logging
- `Print.cpp` — first-layer islands + convex hull snapshot logging

Hard rule: every diagnostic must remain wrapped in `#ifdef __ANDROID__ ... #endif`. If a conflict resolution requires unguarded code, stop and ask — it's a sign the original patch interleaves with new upstream code in a way that needs the user's call.

- [ ] **Step 3: Verify no merge markers remain**

```bash
grep -rln '<<<<<<<\|=======\|>>>>>>>' app/src/main/cpp/orcaslicer/src/libslic3r/ 2>&1
```

Expected: no output.

---

## Task 7: Drop heavy / GUI diagnostics

Per the M1 pre-flight analysis: drop the heavy `ClipperUtils.cpp` /
`deps_src/clipper/clipper.cpp` instrumentation, drop the `GCode.cpp` +564
heavy diagnostics, drop `Snapmaker_Orca.cpp` +106 heavy diagnostics, and drop
`slic3r/GUI/PartPlate.cpp` entirely (we don't use the GUI). These can be
re-added in a future debugging task; they are not needed for the regression
pass.

**Files:**
- Verify-unchanged: `app/src/main/cpp/orcaslicer/src/libslic3r/GCode.cpp`
- Verify-unchanged: `app/src/main/cpp/orcaslicer/src/libslic3r/ClipperUtils.cpp`
- Verify-unchanged: `app/src/main/cpp/orcaslicer/deps_src/clipper/clipper.cpp`
- Verify-unchanged: `app/src/main/cpp/orcaslicer/src/Snapmaker_Orca.cpp`
- Verify-unchanged: `app/src/main/cpp/orcaslicer/src/slic3r/GUI/PartPlate.cpp`
- Verify-unchanged: `app/src/main/cpp/orcaslicer/src/libslic3r/Brim.cpp` (heavy "topology tracing" portion only — light type-qualification fix from Task 5 stays)

- [ ] **Step 1: Confirm these files are at v2.3.3 upstream state (no patches applied)**

```bash
for f in src/libslic3r/GCode.cpp src/libslic3r/ClipperUtils.cpp deps_src/clipper/clipper.cpp src/Snapmaker_Orca.cpp src/slic3r/GUI/PartPlate.cpp; do
  status=$(git -C app/src/main/cpp/orcaslicer diff --stat -- "$f" | tail -1)
  echo "$f: ${status:-clean (no diff vs v2.3.3)}"
done
```

Expected: every file shows `clean (no diff vs v2.3.3)`. If any show modifications, those came from earlier `git apply --3way` runs that swept in heavy hunks — revert just those files:

```bash
git -C app/src/main/cpp/orcaslicer checkout -- src/libslic3r/GCode.cpp src/libslic3r/ClipperUtils.cpp deps_src/clipper/clipper.cpp src/Snapmaker_Orca.cpp src/slic3r/GUI/PartPlate.cpp
```

- [ ] **Step 2: For Brim.cpp, keep only the type-qualification fix**

Brim.cpp has both the lightweight type-qualification fix (kept, from Task 5) and a heavy topology tracing block (dropped). After Task 5, inspect:

```bash
git -C app/src/main/cpp/orcaslicer diff src/libslic3r/Brim.cpp | head -200
```

If you see large blocks (>10 lines) wrapped in `#ifdef __ANDROID__` that look like trace logging (calls to `g_diag_*`, `slog`, JSON emission), those are the heavy portions — remove them, leaving only the small `Slic3r::Polygon` / `Slic3r::Point` namespace qualifications. The light fix is typically 2–5 lines; anything more is the heavy block.

- [ ] **Step 3: Final sanity check on what's left**

```bash
git -C app/src/main/cpp/orcaslicer diff --stat
```

Expected: a short list — `Print.hpp`, `WipeTower.hpp`, `CutSurface.cpp`, `Brim.cpp`, `clipper.hpp`, `NSVGUtils.cpp`, `GCodeWriter.cpp`, `WipeTower2.cpp`, `Print.cpp`, plus possibly a handful of small `#include` additions in other files. Total lines added: ~300–600 (not 2,400 — heavy diagnostics are gone).

---

## Task 8: Attempt the first native build (will fail — captures SAPIL API breakage)

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

## Task 9: Fix SAPIL wrapper compile errors against new libslic3r API

**This is the open-ended task.** We don't know in advance exactly what
changed in `Print`, `Model`, `PrintConfig`, `PresetBundle`, `Config` between
`bd66b99` and `v2.3.3` — we'll discover it from the build failures.

**Files:** any of `app/src/main/cpp/src/sapil_*.cpp`, `app/src/main/cpp/src/slicer_wrapper.cpp`, `app/src/main/cpp/include/sapil.h`.

- [ ] **Step 1: For each compile error, identify the upstream API change**

For each `error:` line in `build1-errors.log`:
1. Read the surrounding code in our SAPIL file.
2. Open the corresponding libslic3r header at v2.3.3 (e.g. `app/src/main/cpp/orcaslicer/src/libslic3r/Print.hpp`).
3. Compare against `D:\projects\u1-slicer-orca\app\src\main\cpp\orcaslicer\src\libslic3r\Print.hpp` (the `bd66b99` patched version) to see how the API moved.
4. Update our SAPIL call site to match the new API.

Common patterns to expect (per the libslic3r files #375 touched):
- `MixedFilament` / `MixedFilamentManager` additions are new — won't cause errors, just new code that doesn't affect us yet.
- `PresetBundle` may have new mixed-filament accessors; existing accessors should still work.
- `Print` / `PrintObject` may have renamed members; check member-access sites in our code.
- `PrintConfig` adds keys but the access pattern (`config.option<T>("key_name")`) is unchanged.

- [ ] **Step 2: For each fix, write a single-purpose code change**

Use Edit tool to change one site at a time. Do NOT refactor or "improve" surrounding code while you're there — minimum-diff fixes only. We want a clean bisect target if a fix turns out to be wrong.

- [ ] **Step 3: Re-build incrementally**

```bash
cd "$BUILD_DIR" && "$NINJA_BIN" -j1 2>&1 | tee /d/tmp/full-spectrum-m1/build2-errors.log; cd -
```

Iterate until the build succeeds (EXIT=0) or you've run out of progress and need to ask.

- [ ] **Step 4: Commit nothing yet — wait for a clean build**

---

## Task 10: Strip + verify the built `.so`

**Files:**
- Read: `$BUILD_DIR/libprusaslicer-jni.so` (unstripped, from build)
- Create: `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so` (stripped, replaces the committed binary)

- [ ] **Step 1: Strip the binary**

```bash
LLVMSTRIP="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe"
"$LLVMSTRIP" --strip-unneeded \
  "$BUILD_DIR/libprusaslicer-jni.so" \
  -o app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so
```

- [ ] **Step 2: Verify size**

```bash
ls -la app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so
```

Expected: **19–21 MB**. If 50 MB+, build is Debug — go back and re-check `CMAKE_BUILD_TYPE` in `CMakeCache.txt`. Per CLAUDE.md "NEVER ship Debug .so" memory.

- [ ] **Step 3: Verify compiler is clang 17**

```bash
LLVMRE="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe"
"$LLVMRE" -p .comment app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so | head -3
```

Expected: `clang version 17.0.2`.

- [ ] **Step 4: Verify JNI symbol completeness**

```bash
JNI_COUNT=$("$LLVMRE" -p .dynsym app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so | grep -c 'Java_com_u1_slicer_NativeLibrary')
KOTLIN_COUNT=$(grep -cE 'external fun' app/src/main/java/com/u1/slicer/NativeLibrary.kt)
echo "JNI symbols in .so: $JNI_COUNT"
echo "external fun in Kotlin: $KOTLIN_COUNT"
```

Expected: equal. If they differ, the build dropped JNI methods — per CLAUDE.md, that means a worktree-only source file wasn't picked up by CMake (re-check the build glob input).

- [ ] **Step 5: Verify 16KB page alignment**

```bash
"$LLVMRE" -l app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so | grep -E '^\s+LOAD' | head -5
```

Expected: the `Align` column shows `0x4000` (= 16384 = 16KB) for every `LOAD` segment. If you see `0x1000` (4KB), the linker flag didn't take effect — go back to Task 8 Step 1 and confirm `-Wl,-z,max-page-size=16384` is in `CMAKE_SHARED_LINKER_FLAGS`.

- [ ] **Step 6: Commit nothing yet**

---

## Task 11: Clean build of the APK + JVM unit-test regression

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

Expected: **all 1,453 tests pass** (per the count in CLAUDE.md). If any fail, they are the regression surface — investigate root cause per case. Do NOT relax assertions to make tests pass (per CLAUDE.md hard rule).

- [ ] **Step 3: Record results**

```bash
./gradlew testDebugUnitTest --no-daemon 2>&1 | grep -E 'BUILD|tests completed' > /d/tmp/full-spectrum-m1/unit-test-results.txt
cat /d/tmp/full-spectrum-m1/unit-test-results.txt
```

- [ ] **Step 4: Commit nothing yet (wait for instrumented tests too)**

---

## Task 12: Instrumented-test regression (real device)

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

## Task 13: G-code equivalence spot-check on representative fixtures

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

## Task 14: Final commit + push

- [ ] **Step 1: Verify the final state of changes**

```bash
git status
git diff --stat HEAD~1..HEAD   # what Task 3 (submodule bump) added
git -C app/src/main/cpp/orcaslicer diff --stat   # the patches still uncommitted inside the submodule (expected)
git diff --stat app/src/main/jniLibs/   # the rebuilt .so
```

Expected:
- Parent repo working tree: `app/src/main/cpp/orcaslicer` (submodule pointer at v2.3.3), `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so` (rebuilt), and any SAPIL wrapper fixes in `app/src/main/cpp/src/`.
- Submodule working tree: ~300–600 lines of uncommitted Android patches (expected; intentionally not committed into the submodule's own repo).

- [ ] **Step 2: Stage and commit**

```bash
git add app/src/main/cpp/orcaslicer \
        app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so \
        app/src/main/cpp/src/ \
        app/src/main/cpp/include/
git commit -m "native: bump orca engine to v2.3.3 + rebuild .so (16KB-aligned, regression-only)

M1 stage 1 of the full-spectrum roadmap. Bumps OrcaSlicer submodule
bd66b99 (2026-05-01) -> v2.3.3 (2026-06-01, first tag with PR #375
'mix filament'). 167 commits of upstream drift.

Patches re-applied (uncommitted in submodule, per repo convention):
- B38 init fixes: Print.hpp m_origin/m_isBBLPrinter/FakeWipeTower,
  WipeTower.hpp m_cur_layer_id (verified still required on v2.3.3)
- NDK build-compat: CutSurface.cpp, Brim.cpp, clipper.hpp, NSVGUtils.cpp,
  missing STL includes
- Lightweight diagnostics: GCodeWriter coord bounds, WipeTower2 tcr/state,
  Print first-layer islands

Dropped (deferred): heavy ClipperUtils / GCode.cpp / clipper.cpp /
Snapmaker_Orca.cpp diagnostics, slic3r/GUI/PartPlate.cpp (GUI, unused).

SAPIL wrapper API-compat fixes in app/src/main/cpp/src/sapil_*.cpp,
slicer_wrapper.cpp — see git log for individual changes.

Native .so rebuilt Release + 16KB-page-aligned for Android 15+ compat
(linker flag -Wl,-z,max-page-size=16384). Verified ~20 MB, clang 17,
JNI symbol count matches NativeLibrary.kt, LOAD segments aligned 0x4000.

**Full-spectrum capability is now present in the engine but NOT exposed.**
No new config keys wired, no UI, no overrides. Pure regression: all 1,453
unit tests + 403 instrumented tests pass; G-code spot-check on three
representative fixtures shows acceptable diffs.

Refs:
- docs/superpowers/specs/2026-05-26-full-spectrum-roadmap.md (M1 stage 1)
- docs/superpowers/plans/2026-06-05-full-spectrum-m1-stage1-engine-bump.md

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
"
```

- [ ] **Step 3: Do NOT push yet**

Per repo convention and CLAUDE.md "DO NOT push to the remote repository unless the user explicitly asks." Ask the user before pushing.

---

## Out of scope for M1 stage 1

These belong to follow-up plans, **not** this stage:

- **Wiring `mixed_filament_definitions` + the other 18 mixed/dithering config keys** through `applyConfigToPrusa()` / `profile_keys[]` / `buildProfileOverrides()` — that is M1 stage 2 / M3.
- **Designing the M3 Compose UI** for the colour picker — M3.
- **Smart Paint slot-width widening** (per-triangle slot byte from `0..3` to virtual IDs ≥4) — M3a.
- **Prusa `prusa-fdm-mixer` integration** — M4.
- **A real U1 mixed-filament print** — M2.
- **Updating `ENGINE_UPGRADE_GUIDE.md`'s "Current State" section** to reflect the new pin — small follow-up doc commit, not part of this engine bump.

## Self-review notes

- Spec §M1 ("Engine bump") + §M1 pre-flight + 2026-06-05 status check are all covered by Tasks 1–14.
- 16KB-alignment note (commit 9e10334) is folded into Task 8 Step 1 (CMake configure) and verified in Task 10 Step 5.
- Patch catalog must-fix set fully covered (B38 inits in Task 4; build-compat in Task 5; lightweight diagnostics in Task 6).
- Drop-set fully covered in Task 7.
- SAPIL API-compat handled as open-ended Tasks 8 + 9 — unavoidable, since we can't enumerate API changes without running the build.
- Test oracle: existing 1,453 unit + 403 instrumented tests + 3 spot-check fixtures (Tasks 11–13).
- No new tests written this stage — by design (regression-only; new behaviour is gated on M1 stage 2).
