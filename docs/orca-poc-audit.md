# Snapmaker OrcaSlicer POC Audit — U1 Android App

**Date:** 2026-03-05 (updated 2026-03-06)
**Branch:** `poc/orca-slicer`
**Source:** `Snapmaker/OrcaSlicer` (Snapmaker's fork of OrcaSlicer)
**Objective:** Can Snapmaker OrcaSlicer's libslic3r replace PrusaSlicer 2.8.1 as the JNI backend?
**Status:** BUILD SUCCEEDS — `libprusaslicer-jni.so` (565MB debug, arm64-v8a) links successfully

---

## 1. Dependency Delta

### Directory Structure Mismatch

PrusaSlicer uses `bundled_deps/` for vendored libs; OrcaSlicer puts them under `src/`:

| Library | PrusaSlicer Path | OrcaSlicer Path | Status |
|---------|-----------------|-----------------|--------|
| admesh | `bundled_deps/admesh/` | `src/admesh/` | Path change only |
| semver | `bundled_deps/semver/` | `src/semver/` | Path change only |
| miniz | `bundled_deps/miniz/` | `src/miniz/` | Path change only |
| glu-libtess | `bundled_deps/glu-libtess/` | `src/glu-libtess/` | Path change only |
| qoi | `bundled_deps/qoi/` | `src/qoi/` | Path change only |
| clipper | `src/clipper/` | `src/clipper/` | Same |
| fast_float | `bundled_deps/fast_float/` | `src/fast_float/` | Path change only |
| ankerl | `bundled_deps/ankerl/` | `src/ankerl/` | Path change only |
| libigl | `bundled_deps/libigl/` | `src/libigl/` | Path change only |
| nanosvg | `bundled_deps/nanosvg/` | `src/nanosvg/` | Path change only |
| localesutils | `bundled_deps/localesutils/` | Inlined in `libslic3r/LocalesUtils.cpp` | **Structural change** |
| tcbspan | `bundled_deps/tcbspan/` | Not present | **Missing** (may not be needed) |
| agg | `bundled_deps/agg/` | `src/agg/` | Path change only |

### New External Dependencies (OrcaSlicer only)

| Dependency | Type | Bundled? | Cross-compile Needed? | Notes |
|-----------|------|----------|----------------------|-------|
| **OpenCV** (core) | External | No (in `deps/OpenCV/`) | **Yes — heavy** | Used for image analysis features |
| **mcut** | Bundled | Yes (`src/mcut/`) | No — compiles from source | Mesh cutting for CSG booleans |
| **FreeType** | External | No (in `deps/FREETYPE/`) | Yes | Text/emboss features (non-Windows) |
| **FontConfig** | External | No | Yes (Linux only) | Font discovery |
| **OpenSSL** | External | No (in `deps/OpenSSL/`) | Yes | TLS (may be skippable for headless) |
| **CURL** | External | No (in `deps/CURL/`) | Yes | Network (may be skippable for headless) |

### Shared External Dependencies (both need cross-compilation)

| Dependency | PrusaSlicer | OrcaSlicer | Delta |
|-----------|-------------|------------|-------|
| Boost 1.84 | Required | Required (1.66+) | Compatible |
| TBB | Required | Required | Same |
| CGAL | Required | Required | Same |
| OpenCASCADE | Required (21 TK libs) | Required | Same |
| GMP/MPFR | Required | Required | Same |
| Eigen3 | Required | Required | Same |
| zlib | Required | Required | Same |
| Expat | Required | Required | Same |
| Clipper2 | Required | Required | Same |
| NLopt | Stubbed | Required | **Needs real build for Orca** |
| PNG | Stubbed | Required | **Needs real build for Orca** |
| JPEG | Stubbed | Required | **Needs real build for Orca** |
| Qhull | Stubbed | Required | Likely still stubbable |
| LibBGCode | Stubbed | Not used | N/A |

### Key Takeaway
OrcaSlicer requires ~3 additional heavy cross-compiled libraries (OpenCV, FreeType, plus making PNG/JPEG/NLopt real instead of stubs). OpenCV alone is a significant cross-compilation effort for ARM64 Android.

---

## 2. API Surface Compatibility

### SAPIL JNI Layer — Full Compatibility Matrix

| SAPIL API Call | File | OrcaSlicer Status | Notes |
|---------------|------|-------------------|-------|
| `Model::read_from_file()` | sapil_model.cpp | **IDENTICAL** | Same signature, optional extra params |
| `DynamicPrintConfig` | sapil_model.cpp | **IDENTICAL** | Config.hpp |
| `ConfigSubstitutionContext` | sapil_model.cpp | **IDENTICAL** | Config.hpp |
| `model.objects` / `obj->volumes` | sapil_model.cpp | **IDENTICAL** | Same public members |
| `mesh.facets_count()` / `mesh.stats()` | sapil_model.cpp | **IDENTICAL** | TriangleMesh.hpp |
| `obj->bounding_box_exact()` | sapil_model.cpp | **IDENTICAL** | Model.hpp |
| `FullPrintConfig::defaults()` | sapil_print.cpp | **IDENTICAL** | PrintConfig.hpp |
| `dpc.set_key_value()` | sapil_print.cpp | **IDENTICAL** | Config.hpp |
| `ConfigOptionFloat/Int/Percent/Enum` | sapil_print.cpp | **IDENTICAL** | Config.hpp |
| `InfillPattern` enum values | sapil_print.cpp | **COMPATIBLE** | All SAPIL patterns present in Orca |
| `Print::apply(model, config)` | sapil_print.cpp | **IDENTICAL** | PrintBase.hpp |
| `Print::process()` | sapil_print.cpp | **IDENTICAL** | PrintBase.hpp |
| `Print::export_gcode()` | sapil_print.cpp | **COMPATIBLE** | Extra optional params in Orca |
| `Print::set_status_callback()` | sapil_print.cpp | **IDENTICAL** | PrintBase.hpp |
| `PrintBase::SlicingStatus` | sapil_print.cpp | **IDENTICAL** | Same struct (percent, text) |
| `Print::print_statistics()` | sapil_print.cpp | **IDENTICAL** | Same fields |
| `obj->total_layer_count()` | sapil_print.cpp | **IDENTICAL** | Print.hpp |
| `obj->ensure_on_bed()` | sapil_print.cpp | **IDENTICAL** | Model.hpp |
| `obj->clear_instances()` | sapil_arrange.cpp | **IDENTICAL** | Model.hpp |
| `obj->add_instance()` | sapil_arrange.cpp | **IDENTICAL** | Model.hpp |
| `inst->set_offset(Vec3d)` | sapil_arrange.cpp | **IDENTICAL** | ModelInstance |
| G-code file I/O | sapil_gcode.cpp | **N/A** | Pure file reading, no API dep |

### One Breaking Change

**`DynamicPrintConfig::load()` in sapil_print.cpp:260-267** — OrcaSlicer does not have this method directly. The `loadProfile()` function needs adaptation:

```cpp
// Current (PrusaSlicer):
pImpl->print_config.load(ini_path, ForwardCompatibilitySubstitutionRule::Enable);

// Fix options for OrcaSlicer:
// 1. Parse INI manually + set_key_value() loop
// 2. Use PresetBundle::load_from_file()
// 3. Use config deserialization (set_deserialize)
```

### Verdict: 99% API Compatible
All model loading, slicing, G-code generation, configuration, and multi-copy placement APIs work identically. Only `loadProfile()` needs a small adaptation.

---

## 3. CMake Configure Attempt

### Setup
- `build.gradle` passes `-DSLICER_BACKEND=orca` to CMake
- `CMakeLists.txt` routes `PRUSA_DIR` to `orcaslicer/` when backend=orca
- NDK 26.1.10909125, CMake 3.22.1, target arm64-v8a

### Result: **FAILED** — directory structure mismatch

The current `CMakeLists.txt` was written for PrusaSlicer's layout and assumes:
- `${PRUSA_DIR}/bundled_deps/` → OrcaSlicer uses `${PRUSA_DIR}/src/` instead
- `${PRUSA_DIR}/src/clipper/` → Same path in both (OK)

### Specific Errors

```
Cannot find source file: .../orcaslicer/bundled_deps/miniz/miniz.c
Cannot find source file: .../orcaslicer/bundled_deps/glu-libtess/src/dict.c
Cannot find source file: .../orcaslicer/bundled_deps/qoi/qoilib.c
Cannot find source file: .../orcaslicer/bundled_deps/localesutils/LocalesUtils.cpp
Cannot find source file: .../orcaslicer/src/libslic3r/ExtrusionRole.cpp
```

### Source File Differences

Files in PrusaSlicer's CMake list that **don't exist in OrcaSlicer**:
- `ExtrusionRole.cpp` — Orca inlines this differently
- `Format/PrintRequest.cpp` — Orca doesn't have this file

Files in OrcaSlicer that are **new** (not in PrusaSlicer):
- `ArcFitter.cpp` — Arc welding feature
- `FaceDetector.cpp` — Face detection for supports
- `Circle.cpp` — Circle fitting (separate from Geometry/)
- `CurveAnalyzer.cpp` — Curve analysis
- `Format/bbs_3mf.cpp` — Bambu Lab 3MF extensions
- `GCode/FanMover.cpp` — Fan control optimization
- `GCode/AdaptivePAInterpolator.cpp` — Pressure advance
- `GCode/AdaptivePAProcessor.cpp`
- `Interlocking/InterlockingGenerator.cpp` — Interlocking parts
- `Shape/TextShape.cpp` — Text embedding
- `Support/TreeSupport3D.cpp` — Enhanced tree supports

### Fix Required
The CMakeLists.txt needs a complete rewrite for the orca backend:
1. Change `BUNDLED_DIR` from `bundled_deps/` to `src/` (for OrcaSlicer)
2. Remove missing source files (`ExtrusionRole.cpp`, `Format/PrintRequest.cpp`)
3. Add new OrcaSlicer-specific source files
4. Handle `localesutils` being inlined into libslic3r proper
5. Add `mcut` as a bundled dependency target
6. Provide or stub OpenCV, FreeType, NLopt, PNG, JPEG

---

## 4. Snapmaker U1 Printer Profiles

### Status: **U1 profile does NOT exist in OrcaSlicer**

OrcaSlicer includes Snapmaker profiles at:
`orcaslicer/resources/profiles/Snapmaker/`

Available models:
| Model | Bed Size | Extruders | Notes |
|-------|----------|-----------|-------|
| Snapmaker Artisan (A400) | 400x400mm | 2 (IDEX) | Largest bed |
| Snapmaker J1 | 324x324mm | 2 (IDEX) | Mid-range |
| Snapmaker A350 | 320x350mm | 1 (dual variant available) | Closest by size |
| Snapmaker A250 | 230x250mm | 1 (dual variant available) | Smallest |

**No 270x270x270mm bed. No 4-extruder configuration.**

### Action Required
Create custom U1 machine profile:
1. `machine/fdm_u1.json` — Base config (270x270 printable_area, 270mm Z, 4 extruders)
2. Update `Snapmaker.json` to register the model
3. This is straightforward — copy A350 as template, adjust dimensions and extruder count

---

## 5. Blocker List

| # | Blocker | Severity | Suggested Fix | Effort |
|---|---------|----------|--------------|--------|
| B1 | CMake `BUNDLED_DIR` points to wrong path | High | Add orca-specific path logic: `set(BUNDLED_DIR "${PRUSA_DIR}/src")` | 1 hour |
| B2 | Source file list differs (missing/new files) | High | Generate new LIBSLIC3R_CORE_SOURCES list from OrcaSlicer's CMakeLists.txt | 2-4 hours |
| B3 | `localesutils` not a separate bundled lib | Medium | Include `LocalesUtils.cpp` in libslic3r directly (it's already there in Orca) | 30 min |
| B4 | `mcut` dependency needed for CGAL mesh booleans | Medium | Add `mcut` as a static lib target from `src/mcut/` | 1 hour |
| B5 | OpenCV required by libslic3r | **Critical** | Cross-compile OpenCV for ARM64 Android, or stub/ifdef out | 1-3 days |
| B6 | NLopt needs real implementation (not stub) | Medium | Cross-compile NLopt for ARM64 (straightforward CMake project) | 2 hours |
| B7 | PNG/JPEG need real implementations | Medium | Cross-compile libpng + libjpeg-turbo for ARM64 | 2-4 hours |
| B8 | FreeType needed for text/emboss | Low | Cross-compile, or stub out text features with ifdefs | 4 hours |
| B9 | `loadProfile()` API change | Low | Rewrite to use INI parsing + `set_key_value()` | 1 hour |
| B10 | No Snapmaker U1 profile | Low | Create custom JSON profile (copy from A350 template) | 1 hour |
| B11 | OpenSSL/CURL for network features | Low | Likely stubbable for headless Android slicing | 2 hours |
| B12 | tcbspan header missing from OrcaSlicer | Low | Copy from PrusaSlicer or replace with `std::span` (C++20) | 30 min |

---

## 6. Go/No-Go Recommendation

### Verdict: **CONDITIONAL GO** — feasible but significantly more work than PrusaSlicer

#### Pros
- **99% API compatible** — SAPIL JNI layer works almost unchanged
- **OrcaSlicer has Snapmaker profiles** (just not U1 specifically)
- **Bambu 3MF support built-in** — eliminates need for BambuSanitizer
- **Enhanced features** — tree supports 3D, adaptive PA, arc fitting, fan control
- All shared deps (Boost, TBB, CGAL, OCCT, GMP/MPFR) already cross-compiled

#### Cons
- **OpenCV cross-compilation** is the biggest blocker (~1-3 days of work)
- **3 more external libraries** need real builds (NLopt, PNG, JPEG)
- **CMakeLists.txt needs significant rewrite** for different directory structure
- **Source file list diverged** — must regenerate from OrcaSlicer's CMake
- **Larger binary size** — more features = more code = bigger .so

#### Recommended Path
1. Fix CMake paths (B1-B4) — ~4 hours
2. Stub OpenCV with ifdefs if possible, or cross-compile — 1-3 days
3. Build NLopt, PNG, JPEG for ARM64 — ~4 hours
4. Regenerate source file list from OrcaSlicer's CMakeLists — ~2 hours
5. Fix `loadProfile()` API — ~1 hour
6. Create U1 profile — ~1 hour
7. Test end-to-end on device

**Estimated total: 3-5 days of focused work**, vs PrusaSlicer which is already building.

#### Alternative: Stay with PrusaSlicer
The main advantage of OrcaSlicer is built-in Bambu 3MF support and Snapmaker-specific features. But these are already handled in the Kotlin layer (BambuSanitizer, ThreeMfParser). Unless OrcaSlicer-specific slicer features (tree supports 3D, adaptive PA) are needed, **PrusaSlicer remains the lower-risk path**.
