# Slicer Engine Upgrade Guide

How to update the OrcaSlicer submodule to a newer version (Snapmaker Orca or FullSpectrum fork) and what patches need re-applying.

## Current State

- **Submodule**: `app/src/main/cpp/orcaslicer` pinned at a commit on **our fork** `github.com/taylormadearmy/OrcaSlicer.git`. The current pin (main as of 2026-06-06) is `bd66b99b2d` — *upstream Snapmaker Orca 2.2.4 + 8 Android patch commits on top*. The orca submodule's remote is **our fork**, not Snapmaker's directly.
- **Our patches**: a clean linear stack of commits on top of the upstream Snapmaker base, living in our fork. Inspect via `git -C app/src/main/cpp/orcaslicer log v2.2.4..HEAD`. Each patch has its own commit message and rationale. **Do not assume patches live as uncommitted modifications** — that was the pre-2026-06 mechanism and the v2.3.3 bump (M1 stage 1) discovered it was lossy (commits like F71's GCode.cpp extension got built into a .so but never committed to the fork).
- **Pre-built .so**: `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so` (committed, ~20MB stripped, Release, 16KB-page-aligned, NDK 26 / Clang 17).
- **Build config**: gradle's externalNativeBuild is disabled by default — APK builds package the committed `.so` directly. Native rebuilds happen out-of-band via manual CMake.

## Step-by-Step Upgrade Process

The upgrade mechanic is **`git rebase`** in the orca submodule, **not** textual patch re-application. Each Android patch is a real commit on our fork; the rebase replays them onto the new upstream tag with proper 3-way merge conflict resolution.

### 1. Identify the merge base + patch tip

In the submodule, find the upstream commit immediately below your patch stack (the merge base with upstream) and the tip of your patch stack:

```bash
git -C app/src/main/cpp/orcaslicer log --oneline <upstream-tag>..HEAD    # lists your patches above the upstream tag
git -C app/src/main/cpp/orcaslicer merge-base HEAD origin/<upstream-branch>   # the upstream commit your stack sits on
```

For the current pin, the merge base with upstream `2.3.2` is `706508c` and the patch tip is `bd66b99`. There are 8 patch commits in between (+ 1 docs commit).

### 2. Fetch the new upstream tag

```bash
git -C app/src/main/cpp/orcaslicer fetch --tags origin
git -C app/src/main/cpp/orcaslicer tag --list 'v2.3.*'   # confirm the new tag exists
```

Our fork tracks Snapmaker's branches as remote refs — the upstream tags are already available without adding a separate Snapmaker remote.

### 3. Rebase the patch stack onto the new tag

Create a new branch for the rebase target and replay the patches:

```bash
git -C app/src/main/cpp/orcaslicer checkout -b feature/<new-tag>-android-<task> <new-tag>
cd app/src/main/cpp/orcaslicer
git rebase --onto feature/<new-tag>-android-<task> <merge-base> <patch-tip>
cd -
```

For example, for the v2.3.3 bump in M1 stage 1:

```bash
git -C app/src/main/cpp/orcaslicer checkout -b feature/v2.3.3-android-m1 v2.3.3
cd app/src/main/cpp/orcaslicer
git rebase --onto feature/v2.3.3-android-m1 706508c bd66b99
cd -
```

### 4. Resolve conflicts per commit

The rebase will pause whenever a patch conflicts with upstream changes. For each conflict:

- **Preserve every `#ifdef __ANDROID__ ... #endif` block** from the patch. Non-negotiable.
- **Adopt the new upstream form** of any surrounding non-Android code.
- **If upstream refactored the patch site** so the original code no longer applies, re-apply the patch's *intent* at the new equivalent site. Each commit's message describes its intent — read it.
- **If upstream already does what the patch did**, run `git rebase --skip` and note it.

Resolution commands:

```bash
git -C app/src/main/cpp/orcaslicer add <resolved-files>
git -C app/src/main/cpp/orcaslicer rebase --continue
```

### 5. Smoke-check the rebase

Before pushing, verify every Android guard survived conflict resolution:

```bash
# Count __ANDROID__ blocks at the old pin vs the rebased HEAD
git -C app/src/main/cpp/orcaslicer grep -c '__ANDROID__' <old-pin> -- 'src/libslic3r/**' 'deps_src/clipper/**' | sort
git -C app/src/main/cpp/orcaslicer grep -c '__ANDROID__' HEAD -- 'src/libslic3r/**' 'deps_src/clipper/**' | sort
```

The new counts must be ≥ old counts. Any drop means a guard was dropped during conflict resolution → re-examine.

### 6. Push the rebased branch

```bash
git -C app/src/main/cpp/orcaslicer push -u origin feature/<new-tag>-android-<task>
```

Then update the parent repo's submodule pin in a commit:

```bash
git add app/src/main/cpp/orcaslicer
git commit -m "chore(native): bump orca submodule to <new-tag> + Android patches"
```

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
