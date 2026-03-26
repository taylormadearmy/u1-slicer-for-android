# Slicer Engine Upgrade Guide

How to update the OrcaSlicer submodule to a newer version (Snapmaker Orca or FullSpectrum fork) and what patches need re-applying.

## Current State

- **Submodule**: `app/src/main/cpp/orcaslicer` pinned to Snapmaker Orca 2.2.4 (commit `f11a7bf`)
- **Our patches**: ~2,400 lines of local modifications on top of the pinned commit (not committed into the submodule's own repo)
- **Pre-built .so**: `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so` (committed, ~20MB stripped)
- **Build config**: CMake disabled in `build.gradle` by default; enabled temporarily for native rebuilds

## Step-by-Step Upgrade Process

### 1. Save current patches

Before touching the submodule, export all current modifications:

```bash
cd app/src/main/cpp/orcaslicer
git diff > ../orcaslicer-android-patches.diff
```

### 2. Update the submodule

```bash
cd app/src/main/cpp/orcaslicer
git fetch origin
git checkout <new-commit-or-tag>
cd ../../../..
```

### 3. Re-apply patches

Apply the saved diff. Expect merge conflicts in files that changed upstream:

```bash
cd app/src/main/cpp/orcaslicer
git apply --3way ../orcaslicer-android-patches.diff
```

If `--3way` fails on some hunks, apply manually using the patch catalog below.

### 4. Rebuild the native library

```bash
# Enable CMake in app/build.gradle (uncomment externalNativeBuild blocks)
# Configure:
./gradlew assembleDebug
# Disable CMake in build.gradle again
# Build with ninja (use -j1 to avoid OOM):
cd app/.cxx/RelWithDebInfo/<hash>/arm64-v8a
<ndk-path>/cmake/3.22.1/bin/ninja -j1
# Strip:
<ndk-path>/ndk/26.1.10909125/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe \
  --strip-unneeded \
  ../../../../../../build/intermediates/cxx/RelWithDebInfo/<hash>/obj/arm64-v8a/libprusaslicer-jni.so \
  -o app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so
# Clean build:
./gradlew clean assembleRelease --no-daemon
```

**Important**: Always use `RelWithDebInfo` for release builds, not `Debug`. Debug builds produce an 80MB unstripped .so with no optimizations, making slicing much slower and changing memory layout enough to mask bugs.

### 5. Test

```bash
./gradlew testDebugUnitTest                 # 517 JVM unit tests
./gradlew connectedDebugAndroidTest         # 125 instrumented tests
```

Then do manual upgrade-cycle testing: `pm install -r` the release APK 10+ times and slice after each install. The B38 investigation showed that uninitialized memory bugs only manifest intermittently after install-over-the-top.

### 6. Commit

Stage the updated submodule reference and the new .so:

```bash
git add app/src/main/cpp/orcaslicer app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so
git commit -m "native: upgrade OrcaSlicer engine to <version>"
```

## Patch Catalog

All patches are `#ifdef __ANDROID__` guarded unless noted. Organized by priority — apply bug fixes first, then build fixes, then diagnostics.

### Critical Bug Fixes (MUST re-apply)

These fix real bugs in OrcaSlicer that affect non-GUI consumers:

| File | Change | Why |
|------|--------|-----|
| `src/libslic3r/Print.hpp` | `Vec3d m_origin = Vec3d::Zero();` | **ROOT CAUSE of B38.** Uninitialized; never set on Android. Release builds read garbage (-inf), corrupting all wipe tower moves. |
| `src/libslic3r/Print.hpp` | `bool m_isBBLPrinter = false;` | Uninitialized; release builds sometimes selected wrong wipe tower (BBL WipeTower instead of WipeTower2). |
| `src/libslic3r/Print.hpp` | `FakeWipeTower` members initialized to zero (`pos`, `width`, `height`, `layer_height`, `depth`, `brim_width`, `rotation_angle`, `cone_angle`, `plate_origin`) | Preventive — same class of uninitialized-member bug. |
| `src/libslic3r/GCode/WipeTower.hpp` | `size_t m_cur_layer_id = 0;` | Uninitialized in BBL wipe tower path. Low risk for us (we use WipeTower2) but fixed for safety. |

**These are filed upstream**: Snapmaker/OrcaSlicer#220, OrcaSlicer/OrcaSlicer#12969. Check if merged before re-applying.

### Build Compatibility Fixes (MUST re-apply)

These fix compilation errors when building with Android NDK:

| File | Change | Why |
|------|--------|-----|
| `src/libslic3r/CutSurface.cpp` | Qualify `ClipperLib::PolyFillType` with `Slic3r::` prefix | Ambiguous name resolution with Android NDK clang. |
| `src/libslic3r/Brim.cpp` | Qualify `Polygon`/`Point` types with `Slic3r::` prefix | Same ambiguity issue. |
| `src/libslic3r/clipper.hpp` | Replace `using` declarations with forward declarations | Avoids pulling in conflicting Clipper symbols. |
| `src/libslic3r/NSVGUtils.cpp` | Namespace-qualified function calls | Build fix for SVG utilities. |
| Various files | `#include <sstream>`, `<limits>`, `<deque>`, `<dlfcn.h>` | Missing STL headers that desktop builds get transitively. |

### Diagnostics (SHOULD re-apply)

Lightweight safety nets that catch coordinate corruption at runtime. All `#ifdef __ANDROID__`:

| File | What | Impact |
|------|------|--------|
| `src/libslic3r/GCodeWriter.cpp` | Coordinate bounds check in `travel_to_xy`/`extrude_to_xy` with native backtrace via `<unwind.h>` + `dladdr()`. Fires when coordinate exceeds 500mm. | **Keep** — cheap, catches any future regression. Emits `gcode_coordinate_violation` event. |
| `src/libslic3r/GCode/WipeTower2.cpp` | `construct_tcr()` bad-position logging, `finish_layer()` and `tool_change()` state logging | **Keep** — fires only on bad values. Emits `wipe_tower_tcr_bad_position`, `wipe_tower_finish_layer_state`, `wipe_tower_tool_change_state`. |
| `src/libslic3r/Print.cpp` | First-layer islands and convex hull snapshot logging | **Keep** — traces geometry construction for future debugging. |

### Heavy Diagnostics (OPTIONAL — re-apply if investigating)

These add significant instrumentation for deep debugging. They're safe (`#ifdef __ANDROID__`) but add code bulk:

| File | Lines | What |
|------|-------|------|
| `deps_src/clipper/clipper.cpp` | +306 | Thread-local operation labels, path validation, trace buffers, JSON diagnostics for Clipper operations |
| `src/libslic3r/ClipperUtils.cpp` | +525 | Safe bounds fallback, path summary, operation tracing for all Clipper utility functions |
| `src/libslic3r/GCode.cpp` | +564 | First-layer extrusion snapshots, head-wrap detection zone logging, G-code writer state tracing |
| `src/libslic3r/GCode/WipeTower2.cpp` | +349 | Move-by-move wipe tower motion tracing, layer state logging, spiral tracking |
| `src/Snapmaker_Orca.cpp` | +106 | Snapmaker profile operation labels |
| `src/libslic3r/Brim.cpp` | +111 | Brim generation and topology tracing |
| `src/slic3r/GUI/PartPlate.cpp` | +111 | Part plate manipulation tracing |

## SAPIL JNI Interface

The native library exposes these JNI functions (defined in `app/src/main/cpp/src/slicer_wrapper.cpp`):

| JNI Function | Purpose |
|---|---|
| `getCoreVersion()` | Returns engine version string |
| `configureDiagnostics(path)` | Sets diagnostics output directory |
| `getDiagnosticsState()` | Returns current diagnostics state JSON |
| `loadModel(path)` | Loads a 3MF/STL file |
| `clearModel()` | Clears the loaded model |
| `getModelInfo()` | Returns model metadata (bounds, objects, instances) |
| `getPreparePreviewMesh()` | Exports mesh data for 3D preview on Prepare screen |
| `slice(config)` | Runs the slicer with the given config; returns result object |
| `loadProfile(path)` | Loads a slicer profile from a 3MF |
| `getGcodePreview(maxLines)` | Returns G-code preview text |
| `setModelInstances(positions)` | Sets model instance positions (copy/arrange) |
| `setModelScale(x, y, z)` | Sets model scale |

The `slice()` config is built in Kotlin by `SlicerViewModel` and passed as a `SliceConfig` jobject. The native side reads it via `sapil_print.cpp:applyConfigToPrusa()` and the `profile_keys[]` whitelist. See the "Profile Key Pipeline" section in `CLAUDE.md` for details on how settings reach the engine.

## Config Key Differences

OrcaSlicer uses different config key names from PrusaSlicer. Key mappings are documented in `CLAUDE.md`. When upgrading, check if any keys were renamed or added upstream:

- `wall_loops` (not `perimeters`)
- `sparse_infill_density` (not `fill_density`)
- `enable_prime_tower` (not `wipe_tower`)
- `initial_layer_print_height` (not `first_layer_height`)

New settings need to be added to both `applyConfigToPrusa()` (fallback values) and `profile_keys[]` (embedded profile whitelist) in `sapil_print.cpp`. See "Profile Key Pipeline" checklist in `CLAUDE.md`.

## FullSpectrum Fork Considerations

If upgrading to ratdoux/OrcaSlicer-FullSpectrum instead of Snapmaker Orca:

- It's a fork of Snapmaker Orca 2.2.4, so the same base patches should apply
- Adds pseudo-extruder / mixed-colour support via layer-cycle alternation
- May introduce new config keys for colour blending that need `profile_keys[]` entries
- **Status as of March 2026**: v0.9.4 alpha, untested on real hardware. Wait for v1.0.
- See backlog item F14 for tracking

## Troubleshooting

### OOM during ninja build
Use `ninja -j1`. The OrcaSlicer C++ files are very large and clang uses 500MB+ per translation unit at `-O2`.

### `SIGILL` / `ILL_ILLOPN` with ASan/HWASan
Android's zygote fork model is incompatible with ASan/HWASan runtime injection. Use the coordinate violation backtrace diagnostics instead.

### Submodule shows dirty after commit
Expected — our patches are local modifications on top of the pinned upstream commit. The main repo tracks the submodule as a pointer; the individual file changes live as uncommitted diffs in the submodule's own git. See the project README or ask about the submodule workflow.
