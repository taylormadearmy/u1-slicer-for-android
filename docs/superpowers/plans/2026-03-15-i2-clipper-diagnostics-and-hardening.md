# ARCHIVED — Fully implemented. Phase 1 (diagnostics + Guard 1) shipped v1.3.29. Phase 2 (Guard 2 hiRange) shipped v1.3.42. No open work remains.

# I2 Clipper "Coordinate outside allowed range" — Diagnostics & Hardening

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Instrument the Clipper1 library to capture failing coordinates on next occurrence, and harden `Round()`/`IntersectPoint()` to prevent undefined behavior from NaN/Inf propagation — eliminating the non-deterministic "Coordinate outside allowed range" crash.

**Architecture:** Three defensive layers: (1) `Round<>()` guards against NaN/Inf before `static_cast<int64_t>` UB, (2) `IntersectPoint()` clamps near-parallel-edge divisions, (3) `RangeTest()` logs failing coordinates to diagnostics before throwing. All changes are in Clipper1 (`clipper.cpp`), guarded by `#ifdef __ANDROID__` so upstream OrcaSlicer code is unaffected.

**Tech Stack:** C++ (Clipper1 library), SAPIL diagnostics, native .so rebuild (ninja -j1 + llvm-strip)

**Status:** Chunk 1 (Tasks 1-4) DEPLOYED in v1.3.29. Chunk 3 (Tasks 8-10) added after diagnostics bundle (2) revealed the v1.3.29 hardening is insufficient — see Phase 2 analysis below.

**Root cause analysis (from diagnostics bundle 1):**
- Error occurs in Clipper1's INT64 `RangeTest()` — coordinates must exceed `hiRange` (4.6×10¹⁸)
- NOT from TreeSupport3D (no `clipper_coordinate_out_of_range` event in diagnostics)
- NOT from Clipper2 (different error message)
- Model coordinates max ~270M in Clipper space — 10 billion× below threshold
- Most likely path: `IntersectPoint()` divides by `(Edge1.Dx - Edge2.Dx)` → near-zero for near-parallel edges → huge `q` → `Round<cInt>(q)` → UB from `static_cast<int64_t>(±Inf)` → ARM64 FCVTZS saturates to INT64_MAX → exceeds hiRange

**Phase 2 root cause analysis (from diagnostics bundle 2, v1.3.33/v1.3.35):**

Two new failures captured AFTER v1.3.29 hardening deployed (nativeBuildId `2.2.0-sm-android/20260305`):

| Field | Failure 1 (v1.3.33) | Failure 2 (v1.3.35) |
|-------|---------------------|---------------------|
| Model | calib-cube-10-dual-colour, 8 copies | makerworld_2331289, single copy |
| Extruders | 2 | 4 |
| x (Clipper) | 7,821,903 → 7.82mm ✓ | 113,830,718 → 113.83mm ✓ |
| y (Clipper) | 9,223,372,036,854,775,807 (INT64_MAX) ✗ | -9,223,372,036,730,087,619 (≈-INT64_MAX) ✗ |
| worldBounds | xMin=-2.45 (off-bed) | all within bed |
| Recovery | retry failed, same coords | retry failed, same coords |

**Critical observations:**
- **One axis valid, other corrupted** → matches `IntersectPoint()` vertical-edge branch where x is set directly from `Edge.Bot.x()` and y is computed via division
- **y ≠ exactly ±INT64_MAX** (failure 2 off by ~124M) → NOT from FCVTZS saturation of ±Inf → Round NaN/Inf guard doesn't help
- **Result is finite but exceeds hiRange** → the existing Round() guard only catches NaN/Inf, not large finite values
- **v1.3.29 near-parallel guard (1e-12 threshold) only covers the `else` branch** (lines 402-410) — the `if (Edge1.Dx == 0)` and `else if (Edge2.Dx == 0)` branches at lines 382-395 are **unprotected**

**Identified gap — vertical-edge branches (clipper.cpp:382-395):**
```cpp
if (Edge1.Dx == 0) {       // Edge1 exactly vertical
    ip.x() = Edge1.Bot.x();  // ← x is valid (set directly)
    y = Round<int64_t>(ip.x() / Edge2.Dx + ...);  // ← y OVERFLOWS when Edge2.Dx ≈ 0
}
```
When Edge2.Dx < ~5.9e-11 and ip.x() ≈ 113M: `ip.x() / Edge2.Dx > hiRange (4.6e18)`.
The result is finite (not ±Inf), so Round's NaN/Inf guard passes it through.

**Threshold math:** For max bed coordinate (270mm → 270,000,000 in Clipper):
`270e6 / Dx < 4.6e18` → `Dx > 5.9e-11`. Threshold of 1e-10 gives ~2× safety margin.

---

## File Map

| File | Action | Purpose |
|------|--------|---------|
| `app/src/main/cpp/orcaslicer/deps_src/clipper/clipper.cpp` | Modify | Harden Round(), IntersectPoint(), RangeTest() |
| `app/src/main/cpp/src/sapil_print.cpp` | Modify | Log extended config context in slice diagnostics |
| `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so` | Rebuild | Deploy hardened native library |

No new files. No Kotlin changes. No test file changes (existing tests validate slicing still works).

---

## Chunk 1: Clipper1 Hardening & Diagnostics

### Task 1: Harden Round() against NaN/Inf — DEPLOYED v1.3.29

**Files:**
- Modify: `app/src/main/cpp/orcaslicer/deps_src/clipper/clipper.cpp:95-106`

**Context:** `Round<cInt>()` converts double→int64_t via `static_cast`. On ARM64, `static_cast<int64_t>(±Inf)` saturates to INT64_MAX/MIN via FCVTZS, producing coordinates that exceed Clipper's hiRange. `static_cast<int64_t>(NaN)` → 0 (safe on ARM64 but UB in C++ standard). The existing range check is compiled out (`CLIPPERLIB_INT32 && !NDEBUG` — both conditions false).

- [x] **Step 1: Add NaN/Inf guard to Round()**

In `clipper.cpp`, replace lines 95-106:

```cpp
template<typename IType>
inline IType Round(double val)
{
    double v = FRound(val);
#if defined(CLIPPERLIB_INT32) && ! defined(NDEBUG)
    static_assert(sizeof(IType) == 4 || sizeof(IType) == 8, "IType must be int32 or int64");
    static constexpr const double hi = 65536. * 16383. * (sizeof(IType) == 4 ? 1 : 65536. * 65536.);
    if (v > hi || -v > hi)
        throw clipperException("Coordinate outside allowed range");
#endif
    return static_cast<IType>(v);
}
```

with:

```cpp
template<typename IType>
inline IType Round(double val)
{
    double v = FRound(val);
#if defined(CLIPPERLIB_INT32) && ! defined(NDEBUG)
    static_assert(sizeof(IType) == 4 || sizeof(IType) == 8, "IType must be int32 or int64");
    static constexpr const double hi = 65536. * 16383. * (sizeof(IType) == 4 ? 1 : 65536. * 65536.);
    if (v > hi || -v > hi)
        throw clipperException("Coordinate outside allowed range");
#endif
#ifdef __ANDROID__
    // Guard against NaN/Inf → static_cast<int64_t> undefined behavior.
    // ARM64 FCVTZS saturates ±Inf to INT64_MAX/MIN which exceeds Clipper's
    // hiRange, causing non-deterministic "Coordinate outside allowed range".
    if (std::isnan(v) || std::isinf(v))
        throw clipperException("Coordinate outside allowed range");
#endif
    return static_cast<IType>(v);
}
```

Also add `#include <cmath>` near the top of the file if not already present (for `std::isnan`/`std::isinf`).

- [x] **Step 2: Verify cmath include exists**

Check that `#include <cmath>` is present near the top of `clipper.cpp`. If not, add it after the existing includes (around line 20-30 area).

### Task 2: Harden IntersectPoint() near-parallel edge division — DEPLOYED v1.3.29 (INSUFFICIENT — see Chunk 3)

**Files:**
- Modify: `app/src/main/cpp/orcaslicer/deps_src/clipper/clipper.cpp:386-395`

**Context:** When `Edge1.Dx ≈ Edge2.Dx` (near-parallel edges), the denominator `(Edge1.Dx - Edge2.Dx)` approaches zero. The exact-equality check on line 363 (`Edge1.Dx == Edge2.Dx`) only catches perfect matches. Near-zero denominators produce huge `q` values that Round() converts to coordinates exceeding hiRange. This is the most likely trigger for the non-deterministic Clipper error.

**NOTE:** This only guards the `else` branch. The vertical-edge branches (`Edge1.Dx == 0` / `Edge2.Dx == 0`) at lines 382-395 have the same division-by-near-zero vulnerability but are unprotected. See Task 8 in Chunk 3.

- [x] **Step 1: Add near-parallel clamping in IntersectPoint()**

In `clipper.cpp`, replace lines 386-395 (the `else` branch of IntersectPoint):

```cpp
  else
  {
    double b1 = double(Edge1.Bot.x()) - double(Edge1.Bot.y()) * Edge1.Dx;
    double b2 = double(Edge2.Bot.x()) - double(Edge2.Bot.y()) * Edge2.Dx;
    double q = (b2-b1) / (Edge1.Dx - Edge2.Dx);
    y = Round<int64_t>(q);
    ip.x() = (std::fabs(Edge1.Dx) < std::fabs(Edge2.Dx)) ?
      Round<cInt>(Edge1.Dx * q + b1) :
      Round<cInt>(Edge2.Dx * q + b2);
  }
```

with:

```cpp
  else
  {
    double b1 = double(Edge1.Bot.x()) - double(Edge1.Bot.y()) * Edge1.Dx;
    double b2 = double(Edge2.Bot.x()) - double(Edge2.Bot.y()) * Edge2.Dx;
    double denom = Edge1.Dx - Edge2.Dx;
#ifdef __ANDROID__
    // Near-parallel edges: denominator approaches zero, producing huge q values
    // that overflow int64_t range. Fall back to the parallel-edge handler.
    if (std::fabs(denom) < 1e-12) {
      ip.y() = Edge1.Curr.y();
      ip.x() = TopX(Edge1, ip.y());
      y = ip.y();
    } else
#endif
    {
      double q = (b2-b1) / denom;
      y = Round<int64_t>(q);
      ip.x() = (std::fabs(Edge1.Dx) < std::fabs(Edge2.Dx)) ?
        Round<cInt>(Edge1.Dx * q + b1) :
        Round<cInt>(Edge2.Dx * q + b2);
    }
  }
```

### Task 3: Add diagnostic logging to RangeTest() — DEPLOYED v1.3.29

**Files:**
- Modify: `app/src/main/cpp/orcaslicer/deps_src/clipper/clipper.cpp:601-616`

**Context:** The INT64 `RangeTest()` throws without any logging, making it impossible to determine which coordinates fail. Adding a diagnostics call (same pattern as TreeSupport3D.cpp) captures the exact values for the next occurrence.

- [x] **Step 1: Add Android include for diagnostics**

Add this include near the top of `clipper.cpp` (after the existing includes, around line 20):

```cpp
#ifdef __ANDROID__
#include "../../../src/sapil_diagnostics.h"
#endif
```

This mirrors the same relative-path pattern used in `TreeSupport3D.cpp` (line 10).

- [x] **Step 2: Add logging to INT64 RangeTest()**

Replace lines 601-616:

```cpp
#else // CLIPPERLIB_INT32
// Called from ClipperBase::AddPath() to verify the scale of the input polygon coordinates.
static inline void RangeTest(const IntPoint& Pt, bool& useFullRange)
{
  if (useFullRange)
  {
    if (Pt.x() > hiRange || Pt.y() > hiRange || -Pt.x() > hiRange || -Pt.y() > hiRange)
      throw clipperException("Coordinate outside allowed range");
  }
  else if (Pt.x() > loRange|| Pt.y() > loRange || -Pt.x() > loRange || -Pt.y() > loRange)
  {
    useFullRange = true;
    RangeTest(Pt, useFullRange);
  }
}
#endif // CLIPPERLIB_INT32
```

with:

```cpp
#else // CLIPPERLIB_INT32
// Called from ClipperBase::AddPath() to verify the scale of the input polygon coordinates.
static inline void RangeTest(const IntPoint& Pt, bool& useFullRange)
{
  if (useFullRange)
  {
    if (Pt.x() > hiRange || Pt.y() > hiRange || -Pt.x() > hiRange || -Pt.y() > hiRange) {
#ifdef __ANDROID__
      sapil::diagnostics_note_clipper_point(Pt.x(), Pt.y(), "clipper.RangeTest.hiRange");
#endif
      throw clipperException("Coordinate outside allowed range");
    }
  }
  else if (Pt.x() > loRange|| Pt.y() > loRange || -Pt.x() > loRange || -Pt.y() > loRange)
  {
    useFullRange = true;
    RangeTest(Pt, useFullRange);
  }
}
#endif // CLIPPERLIB_INT32
```

### Task 4: Add extended slice config to diagnostics — DEPLOYED v1.3.29

**Files:**
- Modify: `app/src/main/cpp/src/sapil_print.cpp` (around line 632-646)

**Context:** The current `slice_pre_process` event logs world bounds and support config, but not per-extruder array sizes or wipe tower config. Adding these helps correlate Clipper failures with specific config states.

- [x] **Step 1: Extend the slice_pre_process diagnostic payload**

After the existing `slice_context` block (line 632-646 in `sapil_print.cpp`), add additional fields before the closing `}`:

Find this code:
```cpp
            << "\"zMax\":" << finalWorldBB.max.z()
            << "}"
            << "}";
```

Replace with:
```cpp
            << "\"zMax\":" << finalWorldBB.max.z()
            << "}"
            << ",\"extruderCount\":" << config.extruder_count
            << ",\"wipeTowerEnabled\":" << (config.wipe_tower_enabled ? "true" : "false")
            << ",\"wipeTowerX\":" << config.wipe_tower_x
            << ",\"wipeTowerY\":" << config.wipe_tower_y
            << ",\"isSnapmakerProfile\":" << (is_snapmaker_profile ? "true" : "false")
            << ",\"profileKeysApplied\":" << (is_snapmaker_profile ? applied : 0)
            << "}";
```

Note: the `applied` variable is already declared at line 537 inside the `if (is_snapmaker_profile)` block. Move its declaration before the if block so it's accessible here:

Find `int applied = 0;` (line 537) — move it to just before the `if (is_snapmaker_profile)` block (around line 369), changing to:
```cpp
int applied = 0;
if (is_snapmaker_profile) {
```

---

## Chunk 2: Native Rebuild & Verification — DONE in v1.3.29

### Task 5: Rebuild native .so — DONE

**Files:**
- Rebuild: `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so`

- [x] **Step 1: Enable CMake in build.gradle**

Temporarily re-enable the CMake `externalNativeBuild` block in `app/build.gradle` (uncomment the `cmake` block in `android.defaultConfig`).

- [x] **Step 2: Configure the build**

```powershell
cd C:\Users\kevin\projects\u1-slicer-orca
.\gradlew assembleDebug --no-daemon 2>&1 | Select-String "Build command failed|CMake Error" | Select-Object -First 5
```

If no CMake errors, the build is configured. If it errors, check the CMake output.

- [x] **Step 3: Run ninja build (single-threaded to avoid OOM)**

```powershell
# Find the build directory
$buildDir = Get-ChildItem -Path "app\.cxx\Debug" -Directory | Select-Object -First 1
ninja -C "$($buildDir.FullName)\arm64-v8a" -j1
```

Expected: build succeeds (may take ~10 minutes with -j1).

- [x] **Step 4: Strip and copy .so to jniLibs**

```powershell
$ndk = "C:\Users\kevin\AppData\Local\Android\Sdk\ndk\26.1.10909125"
$buildDir = (Get-ChildItem -Path "app\.cxx\Debug" -Directory | Select-Object -First 1).FullName
& "$ndk\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe" --strip-unneeded "$buildDir\arm64-v8a\libprusaslicer-jni.so"
Copy-Item "$buildDir\arm64-v8a\libprusaslicer-jni.so" "app\src\main\jniLibs\arm64-v8a\libprusaslicer-jni.so" -Force
```

- [x] **Step 5: Disable CMake and clean install**

Re-comment the CMake block in `app/build.gradle`. Then:

```powershell
.\gradlew clean installDebug --no-daemon
```

### Task 6: Run existing test suite — DONE

- [x] **Step 1: Run unit tests**

```powershell
cd C:\Users\kevin\projects\u1-slicer-orca
.\gradlew testDebugUnitTest --no-daemon
```

Expected: all 322+ unit tests pass. Pay attention to `SlicingOverridesTest`, `GcodeValidatorTest`, `BambuSanitizerTest`.

- [x] **Step 2: Run instrumented tests on test device**

```powershell
Remove-Item -Recurse -Force app\build\outputs\androidTest-results -ErrorAction SilentlyContinue
.\gradlew connectedDebugAndroidTest --no-daemon
```

Expected: all 103 instrumented tests pass. Key tests: `SlicingIntegrationTest` (25), `BambuPipelineIntegrationTest` (27), `SemmSlicingTest` (2).

### Task 7: E2E test on physical device — DONE

- [x] **Step 1: Install on test device and test calib-cube**

```powershell
adb -s 43211JEKB16931 shell am start -n com.u1.slicer.orca/com.u1.slicer.MainActivity
```

Load `calib-cube-10-dual-colour-merged.3mf`, slice it. Verify no Clipper error. Check logcat:

```bash
adb -s 43211JEKB16931 logcat -s "SAPIL" -d | tail -20
```

- [x] **Step 2: Test multi-color model (3DBenchy H2C)**

Load `3DBenchy+H2C+Multi+Color+Test+Print.3mf`, slice it. Verify 4-extruder slicing succeeds.

- [x] **Step 3: Commit** — DONE in v1.3.29

---

## Chunk 3: Phase 2 — Vertical-Edge Branch Fix + Belt-and-Suspenders

> Added 2026-03-16 after diagnostics bundle (2) showed v1.3.29 hardening is insufficient.
> Two new Clipper failures on v1.3.33 and v1.3.35 bypass all three Phase 1 guards.

### Task 8: Guard IntersectPoint() vertical-edge branches

**Files:**
- Modify: `app/src/main/cpp/orcaslicer/deps_src/clipper/clipper.cpp:381-418`

**Context:** The `IntersectPoint()` function has three branches:
1. `Edge1.Dx == 0` (Edge1 vertical) — lines 382-389: computes `y = Round(ip.x() / Edge2.Dx + ...)`
2. `Edge2.Dx == 0` (Edge2 vertical) — lines 390-396: computes `y = Round(ip.x() / Edge1.Dx + ...)`
3. `else` (general case) — lines 397-418: has the v1.3.29 near-parallel guard

Branches 1 and 2 divide by the OTHER edge's Dx. When that edge is near-vertical (`Dx ≈ 2e-11`), the division produces finite values exceeding hiRange. The Round NaN/Inf guard doesn't catch these because they're finite.

- [ ] **Step 1: Add near-zero Dx guards to vertical-edge branches + widen else threshold**

Replace lines 381-418 of `clipper.cpp` (the full IntersectPoint body after the parallel-edge early return):

```cpp
  int64_t y;
  if (Edge1.Dx == 0)
  {
    ip.x() = Edge1.Bot.x();
    if (IsHorizontal(Edge2))
      y = Edge2.Bot.y();
#ifdef __ANDROID__
    // Near-vertical Edge2: division by near-zero Dx produces huge y values
    // that exceed hiRange. Fall back to Edge1's current scanline position.
    else if (std::fabs(Edge2.Dx) < 1e-10) {
      sapil::diagnostics_note_clipper_point(
        ip.x(), Round<int64_t>(Edge2.Dx * 1e18),
        "clipper.IntersectPoint.verticalEdge2");
      y = Edge1.Curr.y();
    }
#endif
    else
      y = Round<int64_t>(ip.x() / Edge2.Dx + Edge2.Bot.y() - (Edge2.Bot.x() / Edge2.Dx));
  }
  else if (Edge2.Dx == 0)
  {
    ip.x() = Edge2.Bot.x();
    if (IsHorizontal(Edge1))
      y = Edge1.Bot.y();
#ifdef __ANDROID__
    // Near-vertical Edge1: same guard as above.
    else if (std::fabs(Edge1.Dx) < 1e-10) {
      sapil::diagnostics_note_clipper_point(
        ip.x(), Round<int64_t>(Edge1.Dx * 1e18),
        "clipper.IntersectPoint.verticalEdge1");
      y = Edge1.Curr.y();
    }
#endif
    else
      y = Round<int64_t>(ip.x() / Edge1.Dx + Edge1.Bot.y() - (Edge1.Bot.x() / Edge1.Dx));
  }
  else
  {
    double b1 = double(Edge1.Bot.x()) - double(Edge1.Bot.y()) * Edge1.Dx;
    double b2 = double(Edge2.Bot.x()) - double(Edge2.Bot.y()) * Edge2.Dx;
    double denom = Edge1.Dx - Edge2.Dx;
#ifdef __ANDROID__
    // Near-parallel edges: widen threshold from 1e-12 to 1e-10 to catch
    // cases where b2-b1 is large (multi-copy models with wide geometry spread).
    if (std::fabs(denom) < 1e-10) {
      sapil::diagnostics_note_clipper_point(
        Round<int64_t>(Edge1.Dx * 1e18), Round<int64_t>(Edge2.Dx * 1e18),
        "clipper.IntersectPoint.nearParallel");
      ip.y() = Edge1.Curr.y();
      ip.x() = TopX(Edge1, ip.y());
      y = ip.y();
    } else
#endif
    {
      double q = (b2-b1) / denom;
      y = Round<int64_t>(q);
      ip.x() = (std::fabs(Edge1.Dx) < std::fabs(Edge2.Dx)) ?
        Round<cInt>(Edge1.Dx * q + b1) :
        Round<cInt>(Edge2.Dx * q + b2);
    }
  }
```

**Threshold rationale (1e-10):** For the largest legitimate Clipper coordinate on 270mm bed: `270e6 / Dx < hiRange(4.6e18)` → `Dx > 5.9e-11`. Using 1e-10 gives ~2× safety margin.

### Task 9: Add hiRange magnitude guard to Round() (belt-and-suspenders)

**Files:**
- Modify: `app/src/main/cpp/orcaslicer/deps_src/clipper/clipper.cpp:109-115`

**Context:** The existing Round() only guards NaN/Inf, but finite values exceeding hiRange pass through and later fail at RangeTest. Adding a magnitude check catches ANY overflow path (not just the ones we've identified), acting as a safety net for undiscovered division-by-near-zero sites.

- [ ] **Step 1: Extend Round() guard to also check magnitude**

Replace the existing `__ANDROID__` block in Round():

```cpp
#ifdef __ANDROID__
    // Guard against NaN/Inf → static_cast<int64_t> undefined behavior.
    // ARM64 FCVTZS saturates ±Inf to INT64_MAX/MIN which exceeds Clipper's
    // hiRange, causing non-deterministic "Coordinate outside allowed range".
    if (std::isnan(v) || std::isinf(v))
        throw clipperException("Coordinate overflow: NaN/Inf input to Round");
#endif
```

With:

```cpp
#ifdef __ANDROID__
    // Guard against:
    // 1. NaN/Inf → static_cast<int64_t> UB (ARM64 FCVTZS saturates to INT64_MAX/MIN)
    // 2. Finite values exceeding hiRange (from near-zero division in IntersectPoint
    //    vertical-edge branches — result is finite but > 4.6e18)
    if (std::isnan(v) || std::isinf(v) || v > 4.6e18 || v < -4.6e18)
        throw clipperException("Coordinate outside allowed range");
#endif
```

Note: 4.6e18 ≈ hiRange (0x3FFFFFFFFFFFFFFFLL = 4,611,686,018,427,387,903). This catches overflow at the Round() call site before the value can propagate through Clipper operations and eventually reach RangeTest via AddPath.

### Task 10: Rebuild, verify, and deploy

Same procedure as Chunk 2 (Tasks 5-7), plus additional verification:

- [ ] **Step 1: Rebuild native .so** (enable CMake, gradle configure, ninja -j1, strip, copy, disable CMake, clean install)
- [ ] **Step 2: Run unit tests** (`.\gradlew testDebugUnitTest`)
- [ ] **Step 3: Run instrumented tests** (`.\gradlew connectedDebugAndroidTest` on 43211JEKB16931)
- [ ] **Step 4: Manual test — calib-cube with 8 copies** (previously failed on v1.3.33)
- [ ] **Step 5: Manual test — 4-extruder MakerWorld model** (previously failed on v1.3.35)
- [ ] **Step 6: Check logcat for new diagnostic events**

```bash
adb -s 43211JEKB16931 logcat -s "SAPIL" -d | grep -E "verticalEdge|nearParallel"
```

If `clipper.IntersectPoint.verticalEdge*` or `nearParallel` events appear → confirms the guards are triggering and falling back safely (instead of crashing). If no events appear and slicing succeeds → the fix prevented the problematic geometry from forming in the first place.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/cpp/orcaslicer/deps_src/clipper/clipper.cpp app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so
git commit -m "fix: I2 phase 2 — guard IntersectPoint vertical-edge branches + Round magnitude check

IntersectPoint(): add near-zero Dx guards (< 1e-10) to vertical-edge
branches (Edge1.Dx==0 / Edge2.Dx==0) — these were unprotected in v1.3.29
and are the confirmed cause of Clipper failures in diagnostics bundle (2).
Widen near-parallel threshold from 1e-12 to 1e-10 for wider geometry.

Round(): add magnitude guard (|v| > 4.6e18) as belt-and-suspenders to
catch any remaining overflow path, not just NaN/Inf.

Diagnostic logging on all new guard paths for production telemetry.

Requires native .so rebuild."
```
