# u1-slicer-for-android — Agent Memory

## Project Purpose
Port `u1-slicer-bridge` (Docker-based OrcaSlicer server) to a **native Android app** using **PrusaSlicer 2.8.x** core via JNI/NDK, following the architecture of [SliceBeam](https://github.com/utkabobr/SliceBeam).

## Key Architecture Decisions
- **PrusaSlicer 2.8.x** chosen over OrcaSlicer (smaller dependency surface, SliceBeam proved the path)
- **JNI/NDK approach** (not CLI/container) for native performance
- **SAPIL abstraction layer** (Slicer API Layer) cleanly separates Kotlin UI from C++ core
- Target ABI: `arm64-v8a` only (modern Android devices)
- Min SDK: 26 (Android 8.0)

## Project Structure
```
u1-slicer-for-android/
├── scripts/
│   ├── build_deps.sh          # Cross-compiles native deps for ARM64
│   └── fetch_prusaslicer.sh   # Clones PrusaSlicer 2.8.1 source
├── app/src/main/
│   ├── cpp/
│   │   ├── CMakeLists.txt     # NDK build config
│   │   ├── include/sapil.h    # SAPIL API header
│   │   ├── src/               # SAPIL JNI implementation (6 files)
│   │   ├── extern/            # Pre-built native deps (output of build_deps.sh)
│   │   └── prusaslicer/       # PrusaSlicer 2.8.1 source (cloned)
│   └── java/com/u1/slicer/
│       ├── NativeLibrary.kt   # JNI bridge
│       ├── SlicerViewModel.kt # State management
│       ├── MainActivity.kt    # Compose UI
│       └── data/              # SliceConfig, ModelInfo, SliceResult
```

## MANDATORY: End-to-end testing before marking features done

Every new feature MUST be tested on the physical device **43211JEKB00954** (Pixel 8a, Android 16) before it is considered complete. Do NOT mark a feature done based only on a successful build or passing unit tests.

Steps:
1. `./gradlew installDebug --no-daemon` — confirm BUILD SUCCESSFUL + "Installed on 1 device"
2. Launch app on device, navigate to the feature's screen, exercise the happy path
3. Test at least one error case or edge case
4. Run `adb -s 43211JEKB00954 logcat -s "SlicerVM" -d` to check for exceptions
5. Only then mark the feature complete

**NEVER deploy to NE12442001324 (NF22E1) — that is the user's personal device.**

## Current Status (as of 2026-03-04)
- ✅ Project scaffolding complete (Gradle, git, .gitignore)
- ✅ SAPIL JNI layer complete — now using **real PrusaSlicer APIs**
- ✅ Compose UI complete (dark theme, model info, settings, progress, G-code preview)
- ✅ PrusaSlicer 2.8.1 source cloned
- ✅ CMake integration with 180+ libslic3r sources + 13 bundled deps
- ✅ Native dependency builds complete (Phase 1):
  - ✅ Header-only deps (Eigen, Cereal, JSON)
  - ✅ Compiled lightweight deps (zlib, expat, Clipper2)
  - ✅ Built heavy deps in WSL /tmp (Boost 1.84, OCCT 7.8.1, GMP, MPFR, TBB)
  - ✅ All libraries verified in `app/src/main/cpp/extern/`
- [/] Compile libslic3r-jni shared library via CMake (Phase 2)
- ⬜ Device build and testing (Phase 3)

## Development Environment Notes
- **WSL (Ubuntu)** is required for dependency cross-compilation.
- **Native Filesystem Build:** For heavy GNU-based deps (GMP, MPFR, Boost, OCCT), we now compile in `/tmp` natively within WSL instead of the Windows `/mnt/c` mount to avoid NTFS permission/rename bugs.
- **Fixes applied:** Permanent install of `m4`, `dos2unix` for patch files, and PATH sanitization to remove Windows paths with spaces during Boost builds.

## PrusaSlicer Integration Notes
### libslic3r Dependencies (from its CMakeLists.txt)
**Private:** libnest2d, libcereal, boost_libs, clipper, libexpat, glu-libtess, qhull, TBB, libslic3r_cgal, PNG, ZLIB, JPEG, qoi, fastfloat, int128
**Public:** Eigen3, semver, admesh, localesutils, LibBGCode, tcbspan, miniz, libigl, agg, ankerl

### Bundled deps location
Many deps are bundled inside PrusaSlicer's tree — look in the top-level `CMakeLists.txt` for `add_subdirectory()` calls pointing to directories under `src/` or separate repos.

### Key PrusaSlicer APIs for SAPIL
- `Slic3r::Model::read_from_file()` — model loading
- `Slic3r::DynamicPrintConfig` — configuration
- `Slic3r::Print` — slicing orchestration
- `Slic3r::GCode` — G-code generation
- `Slic3r::PrintBase::SlicingStatus` — progress callbacks

## Environment
- **NDK:** r26.1 at `/mnt/c/Users/kevin/AppData/Local/Android/Sdk/ndk/26.1.10909125` (Windows) / `/tmp/android-ndk-r26b` (Linux/WSL)
- **WSL:** Ubuntu (Running, WSL2)
- **Git:** Available in WSL
- **CMake:** Installed in WSL (3.22.1)
