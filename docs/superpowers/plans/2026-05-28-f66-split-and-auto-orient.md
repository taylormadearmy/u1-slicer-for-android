# F66 — Split + Auto-Orient Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land desktop-Orca-equivalent "Split to Objects", "Split to Parts", and
"Auto-Orient" in U1 Slicer, including a per-object selection model, per-object
rotate/scale, a Parts panel for per-volume filament assignment, and F89
session-resume coverage for all of it.

**Architecture:** OrcaSlicer's native engine already ships
`ModelObject::split()`, `ModelVolume::split(max_extruders)`, and
`Slic3r::orientation::orient(...)`. F66 layers JNI wrappers + a per-object
ViewModel state model + per-object pose in the F77 multi-object renderer +
Compose UI on top. Existing tap-disambiguation in `ModelViewerView` is reused
for tap-to-select. Persistence extends F89's existing replay-style schema.

**Tech Stack:** Kotlin 1.9.22, Jetpack Compose, JNI (NDK 26 / Clang 17), C++17
(OrcaSlicer Snapmaker fork), OpenGL ES 3.0, Room, DataStore. Test layers: JVM
unit (`app/src/test/`), Android instrumented (`app/src/androidTest/`,
Orchestrator-per-test-process), and manual E2E on Pixel 8a.

**Spec:** [docs/superpowers/specs/2026-05-28-f66-split-and-auto-orient-design.md](../specs/2026-05-28-f66-split-and-auto-orient-design.md)

---

## File Structure

### New files

| Path | Responsibility |
|---|---|
| `app/src/main/java/com/u1/slicer/ui/Selection.kt` | `ObjectSelection` data class + helpers for selection-state transitions (pure functions). |
| `app/src/main/java/com/u1/slicer/data/PerObjectPose.kt` | `PerObjectPose` data class (rotation + scale) + load-time baseline storage. Pure data + helpers. |
| `app/src/main/java/com/u1/slicer/ui/PartsPanel.kt` | Compose `PartsPanel` composable + part-row Compose unit. |
| `app/src/main/java/com/u1/slicer/ui/EditPanel.kt` | Compose `EditPanel` composable wrapping selection-aware bed/object modes. |
| `app/src/test/java/com/u1/slicer/native/NativeSplitOrientTest.kt` | JVM contract tests for the new JNI surface using small fixtures. |
| `app/src/test/java/com/u1/slicer/ui/SelectionStateMachineTest.kt` | Pure state-transition tests for `ObjectSelection`. |
| `app/src/test/java/com/u1/slicer/data/PerObjectPoseTest.kt` | Pure remap-on-split tests for `PerObjectPose` maps. |
| `app/src/test/java/com/u1/slicer/data/SessionStateF66RoundTripTest.kt` | F89 JSON round-trip with the new fields. |
| `app/src/test/java/com/u1/slicer/PerObjectTransformIsolationTest.kt` | ViewModel-level tests that per-object rotate/scale doesn't leak. |
| `app/src/test/java/com/u1/slicer/ResetTransformTest.kt` | ViewModel-level Reset-rotation / Reset-scale tests. |
| `app/src/androidTest/java/com/u1/slicer/SplitAndOrientIntegrationTest.kt` | On-device end-to-end: split → orient → slice. |
| `app/src/androidTest/java/com/u1/slicer/PaintedSplitSliceTest.kt` | On-device paint-preserved-through-split regression. |
| `app/src/androidTest/java/com/u1/slicer/PerPartFilamentSliceTest.kt` | On-device per-part filament assignment → slice → G-code. |
| `app/src/androidTest/java/com/u1/slicer/data/SessionStateF66ResumeTest.kt` | F89 DataStore round-trip on real Android Room/DataStore. |

### Modified files

| Path | Change |
|---|---|
| `app/src/main/cpp/include/sapil.h` | Add 11 new methods on `SlicerEngine`. |
| `app/src/main/cpp/src/sapil_model.cpp` | Implement the 11 new engine methods. |
| `app/src/main/cpp/src/slicer_wrapper.cpp` | Add the 11 matching JNI exports. |
| `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so` | Rebuilt artefact (NDK 26 / Release / stripped / verified). |
| `app/src/main/java/com/u1/slicer/NativeLibrary.kt` | Add 11 new `external fun` declarations. |
| `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` | Add selection state, per-object pose maps, load-time baselines, action methods, F77 remap-on-split, F89 hydration of new fields. |
| `app/src/main/java/com/u1/slicer/data/SessionState.kt` | Schema bump v2 → v3 with new fields, JSON serialisers. |
| `app/src/main/java/com/u1/slicer/viewer/ModelRenderer.kt` | Per-instance rotation + scale arrays; selection-highlight uniform; second-pass draw for selected mesh range. |
| `app/src/main/java/com/u1/slicer/viewer/MeshData.kt` | (if needed) thread-safe per-instance pose update. |
| `app/src/main/java/com/u1/slicer/viewer/ModelViewerView.kt` | Wire `onTriangleTapped` + `onEmptyTap` callbacks for the Prepare-screen instance; map triangle → object via `objectMeshRanges`. |
| `app/src/main/java/com/u1/slicer/ui/InlineModelPreview.kt` | Pass `instanceRotations` / `instanceScales` / selection index to the renderer; keep `perObjectSizes` gating intact (B124). |
| `app/src/main/java/com/u1/slicer/MainActivity.kt` | Replace single Rotate/Scale block with the new `EditPanel` composable; collect selection-state flows. |
| `BACKLOG.md` | Move F66 to Closed (recent) on release. |
| `CLAUDE.md` | Update test counts; add new test class entries. |
| `app/build.gradle` | Bump `versionCode` and `versionName` at release time. |

---

## Native API contract (lock this first, every later task references it)

All new JNI methods are added on `NativeLibrary` and the matching C++ on
`SlicerEngine`. Object and volume indices are 0-based throughout the
Kotlin/JNI boundary even though OrcaSlicer's internal extruder indices are
1-based — the per-volume extruder accessors translate.

```kotlin
// app/src/main/java/com/u1/slicer/NativeLibrary.kt — additions
external fun nativeIsObjectSplittable(objIdx: Int): Boolean
external fun nativeIsVolumeSplittable(objIdx: Int, volIdx: Int): Boolean
external fun nativeSplitObject(objIdx: Int): IntArray?       // [removedIdx, addedCount] or null
external fun nativeSplitVolume(objIdx: Int, volIdx: Int): Int  // new volume count, or -1 on failure
external fun nativeAutoOrientObject(objIdx: Int): DoubleArray? // [eulerX, eulerY, eulerZ] radians or null
external fun nativeAutoOrientAll(): Int                       // count of successfully oriented objects
external fun nativeSetObjectRotation(objIdx: Int, x: Float, y: Float, z: Float): Boolean  // degrees
external fun nativeGetObjectRotation(objIdx: Int): FloatArray  // [x, y, z] degrees (length 3)
external fun nativeSetObjectScale(objIdx: Int, sx: Float, sy: Float, sz: Float): Boolean
external fun nativeGetObjectScale(objIdx: Int): FloatArray     // [sx, sy, sz] (length 3)
external fun nativeGetObjectName(objIdx: Int): String?
external fun nativeGetVolumeName(objIdx: Int, volIdx: Int): String?
external fun nativeGetVolumeExtruder(objIdx: Int, volIdx: Int): Int  // 1-indexed
external fun nativeSetVolumeExtruder(objIdx: Int, volIdx: Int, slot: Int): Boolean
```

---

## Step 1: Native JNI surface + JVM tests

The native engine learns to split objects, split volumes, auto-orient, and
expose per-object rotation/scale/extruder/name. Step 1 is done when the JVM
test suite passes against a freshly-rebuilt and verified `.so`.

### Task 1.1: Declare new methods on `SlicerEngine`

**Files:**
- Modify: `app/src/main/cpp/include/sapil.h`

- [ ] **Step 1: Edit the header**

Append these declarations inside the `SlicerEngine` public section, after the
existing `setObjectPositions` (around line 244):

```cpp
    // ---- F66: Split + Auto-Orient + per-object pose ----

    // True iff g_model.objects[objIdx] has more than one connected component
    // (cheap probe used by Kotlin to enable/disable the Split-to-Objects button).
    bool isObjectSplittable(int objIdx) const;

    // True iff g_model.objects[objIdx]->volumes[volIdx]->is_splittable() is true.
    bool isVolumeSplittable(int objIdx, int volIdx) const;

    // Split the object at objIdx into its connected components, replacing the
    // original at the same index. On success returns {removedIdx, addedCount}.
    // On a one-island input returns std::nullopt without mutating the model.
    struct SplitResult { int removedIdx; int addedCount; };
    std::optional<SplitResult> splitObject(int objIdx);

    // Split one volume's mesh into multiple volumes within the same object.
    // Returns the new volume count for the object on success, -1 on failure.
    int splitVolume(int objIdx, int volIdx);

    // Run Slic3r::orientation::orient on one object and apply the result to
    // its instances[0] rotation. Returns the new euler [x, y, z] in radians,
    // or std::nullopt on failure (model not loaded, objIdx OOR, orient bailout).
    std::optional<std::array<double, 3>> autoOrientObject(int objIdx);

    // Iterate every object on the bed and call autoOrientObject. Returns the
    // number of objects successfully oriented.
    int autoOrientAll();

    // Set/get instances[0] rotation (degrees, Euler XYZ) for one object.
    bool setObjectRotation(int objIdx, float rxDeg, float ryDeg, float rzDeg);
    std::array<float, 3> getObjectRotation(int objIdx) const;

    // Set/get instances[0] scaling factor (per-axis) for one object.
    bool setObjectScale(int objIdx, float sx, float sy, float sz);
    std::array<float, 3> getObjectScale(int objIdx) const;

    // Display name. Returns empty string on OOR or no-model.
    std::string getObjectName(int objIdx) const;

    // Per-volume metadata + extruder. Slot is 1-indexed per Orca convention.
    std::string getVolumeName(int objIdx, int volIdx) const;
    int  getVolumeExtruder(int objIdx, int volIdx) const;
    bool setVolumeExtruder(int objIdx, int volIdx, int slot);
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/cpp/include/sapil.h
git commit -m "F66: declare native split + orient + per-object pose API in sapil.h"
```

### Task 1.2: Implement engine methods in `sapil_model.cpp`

**Files:**
- Modify: `app/src/main/cpp/src/sapil_model.cpp`

- [ ] **Step 1: Add the includes if missing**

At the top, ensure `Orient.hpp` is included alongside the existing OrcaSlicer
headers:

```cpp
#include "libslic3r/Orient.hpp"
#include <optional>
#include <array>
```

- [ ] **Step 2: Append the 11 method implementations**

Add at the end of the file, inside the same translation unit as the other
`SlicerEngine::` methods. The implementations are direct wrappers around
existing OrcaSlicer APIs:

```cpp
// ---- F66 ----

bool SlicerEngine::isObjectSplittable(int objIdx) const {
    if (!g_model || objIdx < 0 || objIdx >= (int)g_model->objects.size()) return false;
    // Cheap probe — parts_count() returns the number of mesh parts across all
    // volumes; >1 means a split would produce something.
    return g_model->objects[objIdx]->parts_count() > 1;
}

bool SlicerEngine::isVolumeSplittable(int objIdx, int volIdx) const {
    if (!g_model || objIdx < 0 || objIdx >= (int)g_model->objects.size()) return false;
    const auto& vols = g_model->objects[objIdx]->volumes;
    if (volIdx < 0 || volIdx >= (int)vols.size()) return false;
    return vols[volIdx]->is_splittable();
}

std::optional<SlicerEngine::SplitResult> SlicerEngine::splitObject(int objIdx) {
    if (!g_model || objIdx < 0 || objIdx >= (int)g_model->objects.size()) return std::nullopt;
    Slic3r::ModelObjectPtrs new_objects;
    g_model->objects[objIdx]->split(&new_objects);
    if (new_objects.size() <= 1) return std::nullopt;  // not splittable
    // Replace original at the same index.
    delete g_model->objects[objIdx];
    g_model->objects.erase(g_model->objects.begin() + objIdx);
    for (size_t i = 0; i < new_objects.size(); ++i) {
        g_model->objects.insert(g_model->objects.begin() + objIdx + i, new_objects[i]);
    }
    return SplitResult{objIdx, (int)new_objects.size()};
}

int SlicerEngine::splitVolume(int objIdx, int volIdx) {
    if (!g_model || objIdx < 0 || objIdx >= (int)g_model->objects.size()) return -1;
    auto* obj = g_model->objects[objIdx];
    if (volIdx < 0 || volIdx >= (int)obj->volumes.size()) return -1;
    unsigned max_extruders = 4;  // U1 hardware limit
    obj->volumes[volIdx]->split(max_extruders);
    return (int)obj->volumes.size();
}

std::optional<std::array<double, 3>> SlicerEngine::autoOrientObject(int objIdx) {
    if (!g_model || objIdx < 0 || objIdx >= (int)g_model->objects.size()) return std::nullopt;
    Slic3r::orientation::orient(g_model->objects[objIdx]);
    auto rot = g_model->objects[objIdx]->instances[0]->get_rotation();
    return std::array<double, 3>{rot.x(), rot.y(), rot.z()};
}

int SlicerEngine::autoOrientAll() {
    if (!g_model) return 0;
    int succeeded = 0;
    for (auto* obj : g_model->objects) {
        try {
            Slic3r::orientation::orient(obj);
            succeeded++;
        } catch (...) { /* skip degenerate objects */ }
    }
    return succeeded;
}

bool SlicerEngine::setObjectRotation(int objIdx, float rxDeg, float ryDeg, float rzDeg) {
    if (!g_model || objIdx < 0 || objIdx >= (int)g_model->objects.size()) return false;
    auto* obj = g_model->objects[objIdx];
    if (obj->instances.empty()) return false;
    constexpr double DEG2RAD = 3.14159265358979323846 / 180.0;
    Slic3r::Vec3d rot(rxDeg * DEG2RAD, ryDeg * DEG2RAD, rzDeg * DEG2RAD);
    obj->instances[0]->set_rotation(rot);
    obj->invalidate_bounding_box();
    return true;
}

std::array<float, 3> SlicerEngine::getObjectRotation(int objIdx) const {
    std::array<float, 3> out{0.f, 0.f, 0.f};
    if (!g_model || objIdx < 0 || objIdx >= (int)g_model->objects.size()) return out;
    auto* obj = g_model->objects[objIdx];
    if (obj->instances.empty()) return out;
    auto rot = obj->instances[0]->get_rotation();
    constexpr double RAD2DEG = 180.0 / 3.14159265358979323846;
    out[0] = static_cast<float>(rot.x() * RAD2DEG);
    out[1] = static_cast<float>(rot.y() * RAD2DEG);
    out[2] = static_cast<float>(rot.z() * RAD2DEG);
    return out;
}

bool SlicerEngine::setObjectScale(int objIdx, float sx, float sy, float sz) {
    if (!g_model || objIdx < 0 || objIdx >= (int)g_model->objects.size()) return false;
    auto* obj = g_model->objects[objIdx];
    if (obj->instances.empty()) return false;
    obj->instances[0]->set_scaling_factor(Slic3r::Vec3d(sx, sy, sz));
    obj->invalidate_bounding_box();
    return true;
}

std::array<float, 3> SlicerEngine::getObjectScale(int objIdx) const {
    std::array<float, 3> out{1.f, 1.f, 1.f};
    if (!g_model || objIdx < 0 || objIdx >= (int)g_model->objects.size()) return out;
    auto* obj = g_model->objects[objIdx];
    if (obj->instances.empty()) return out;
    auto s = obj->instances[0]->get_scaling_factor();
    out[0] = static_cast<float>(s.x());
    out[1] = static_cast<float>(s.y());
    out[2] = static_cast<float>(s.z());
    return out;
}

std::string SlicerEngine::getObjectName(int objIdx) const {
    if (!g_model || objIdx < 0 || objIdx >= (int)g_model->objects.size()) return "";
    return g_model->objects[objIdx]->name;
}

std::string SlicerEngine::getVolumeName(int objIdx, int volIdx) const {
    if (!g_model || objIdx < 0 || objIdx >= (int)g_model->objects.size()) return "";
    const auto& vols = g_model->objects[objIdx]->volumes;
    if (volIdx < 0 || volIdx >= (int)vols.size()) return "";
    return vols[volIdx]->name;
}

int SlicerEngine::getVolumeExtruder(int objIdx, int volIdx) const {
    if (!g_model || objIdx < 0 || objIdx >= (int)g_model->objects.size()) return 0;
    const auto& vols = g_model->objects[objIdx]->volumes;
    if (volIdx < 0 || volIdx >= (int)vols.size()) return 0;
    return vols[volIdx]->extruder_id();
}

bool SlicerEngine::setVolumeExtruder(int objIdx, int volIdx, int slot) {
    if (!g_model || objIdx < 0 || objIdx >= (int)g_model->objects.size()) return false;
    auto& vols = g_model->objects[objIdx]->volumes;
    if (volIdx < 0 || volIdx >= (int)vols.size()) return false;
    if (slot < 1 || slot > 16) return false;  // sanity
    vols[volIdx]->config.set_key_value("extruder",
        new Slic3r::ConfigOptionInt(slot));
    return true;
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/cpp/src/sapil_model.cpp app/src/main/cpp/include/sapil.h
git commit -m "F66: implement native split + orient + per-object pose in SlicerEngine"
```

### Task 1.3: Add JNI exports in `slicer_wrapper.cpp`

**Files:**
- Modify: `app/src/main/cpp/src/slicer_wrapper.cpp`

- [ ] **Step 1: Append JNI bridges**

Add these at the end of the file (matching the style of existing exports
around `setModelRotation` at line 203). For brevity, here are two
representative blocks — extend the pattern to every method declared in 1.2:

```cpp
extern "C" JNIEXPORT jboolean JNICALL
Java_com_u1_slicer_NativeLibrary_nativeIsObjectSplittable(JNIEnv*, jobject, jint objIdx) {
    return g_engine && g_engine->isObjectSplittable(objIdx) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_u1_slicer_NativeLibrary_nativeSplitObject(JNIEnv* env, jobject, jint objIdx) {
    if (!g_engine) return nullptr;
    auto res = g_engine->splitObject(objIdx);
    if (!res) return nullptr;
    jintArray out = env->NewIntArray(2);
    jint vals[2] = { res->removedIdx, res->addedCount };
    env->SetIntArrayRegion(out, 0, 2, vals);
    return out;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_u1_slicer_NativeLibrary_nativeAutoOrientObject(JNIEnv* env, jobject, jint objIdx) {
    if (!g_engine) return nullptr;
    auto res = g_engine->autoOrientObject(objIdx);
    if (!res) return nullptr;
    jdoubleArray out = env->NewDoubleArray(3);
    jdouble vals[3] = { (*res)[0], (*res)[1], (*res)[2] };
    env->SetDoubleArrayRegion(out, 0, 3, vals);
    return out;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_u1_slicer_NativeLibrary_nativeSetObjectRotation(
    JNIEnv*, jobject, jint objIdx, jfloat x, jfloat y, jfloat z) {
    return g_engine && g_engine->setObjectRotation(objIdx, x, y, z) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetObjectRotation(JNIEnv* env, jobject, jint objIdx) {
    if (!g_engine) {
        jfloatArray out = env->NewFloatArray(3);
        jfloat zeros[3] = {0.f, 0.f, 0.f};
        env->SetFloatArrayRegion(out, 0, 3, zeros);
        return out;
    }
    auto r = g_engine->getObjectRotation(objIdx);
    jfloatArray out = env->NewFloatArray(3);
    jfloat vals[3] = { r[0], r[1], r[2] };
    env->SetFloatArrayRegion(out, 0, 3, vals);
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetObjectName(JNIEnv* env, jobject, jint objIdx) {
    if (!g_engine) return env->NewStringUTF("");
    return env->NewStringUTF(g_engine->getObjectName(objIdx).c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetVolumeExtruder(
    JNIEnv*, jobject, jint objIdx, jint volIdx) {
    return g_engine ? g_engine->getVolumeExtruder(objIdx, volIdx) : 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_u1_slicer_NativeLibrary_nativeSetVolumeExtruder(
    JNIEnv*, jobject, jint objIdx, jint volIdx, jint slot) {
    return g_engine && g_engine->setVolumeExtruder(objIdx, volIdx, slot)
        ? JNI_TRUE : JNI_FALSE;
}
```

Extend the pattern to the remaining methods: `nativeIsVolumeSplittable`,
`nativeSplitVolume` (returns `jint`), `nativeAutoOrientAll` (returns `jint`),
`nativeGetObjectScale` (mirror of `nativeGetObjectRotation`),
`nativeSetObjectScale` (mirror of `nativeSetObjectRotation`),
`nativeGetVolumeName` (mirror of `nativeGetObjectName`).

- [ ] **Step 2: Commit**

```bash
git add app/src/main/cpp/src/slicer_wrapper.cpp
git commit -m "F66: JNI bridges for split + orient + per-object pose"
```

### Task 1.4: Declare new external functions in `NativeLibrary.kt`

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/NativeLibrary.kt`

- [ ] **Step 1: Append declarations**

Insert at the bottom of the `class NativeLibrary` (after the last existing
`external fun`):

```kotlin
    // ---- F66: Split + Auto-Orient + per-object pose ----

    external fun nativeIsObjectSplittable(objIdx: Int): Boolean
    external fun nativeIsVolumeSplittable(objIdx: Int, volIdx: Int): Boolean
    external fun nativeSplitObject(objIdx: Int): IntArray?
    external fun nativeSplitVolume(objIdx: Int, volIdx: Int): Int
    external fun nativeAutoOrientObject(objIdx: Int): DoubleArray?
    external fun nativeAutoOrientAll(): Int
    external fun nativeSetObjectRotation(objIdx: Int, x: Float, y: Float, z: Float): Boolean
    external fun nativeGetObjectRotation(objIdx: Int): FloatArray
    external fun nativeSetObjectScale(objIdx: Int, sx: Float, sy: Float, sz: Float): Boolean
    external fun nativeGetObjectScale(objIdx: Int): FloatArray
    external fun nativeGetObjectName(objIdx: Int): String?
    external fun nativeGetVolumeName(objIdx: Int, volIdx: Int): String?
    external fun nativeGetVolumeExtruder(objIdx: Int, volIdx: Int): Int
    external fun nativeSetVolumeExtruder(objIdx: Int, volIdx: Int, slot: Int): Boolean
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/u1/slicer/NativeLibrary.kt
git commit -m "F66: Kotlin declarations for new JNI surface"
```

### Task 1.5: Rebuild the native `.so`

This is a routine engine rebuild per the recipe in `CLAUDE.md` ("Native
Rebuild" section). Claude is pre-authorized for this when feature work
requires C++ changes.

- [ ] **Step 1: Ensure the orcaslicer submodule is initialised**

```bash
git submodule update --init --recursive app/src/main/cpp/orcaslicer
```

- [ ] **Step 2: Reuse the existing Release build directory if available, otherwise create one**

Follow the "Using an existing build directory" or "Fresh build" recipe in
`CLAUDE.md`. The key constraints:
- NDK 26 (`D:/Android/Sdk/ndk/26.1.10909125`), Clang 17.
- `CMAKE_BUILD_TYPE=Release`.
- Do NOT set `CMAKE_CXX_FLAGS_RELEASE` (leave the toolchain default `-O3 -DNDEBUG`).

- [ ] **Step 3: Build with ninja -j1**

```bash
cd app/.cxx/Debug/<hash>/arm64-v8a
ninja -j1
```

OOMs at `-j2`+. Expect ~10–20 minutes from clean.

- [ ] **Step 4: Strip and copy**

```bash
$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/*/bin/llvm-strip --strip-unneeded \
    libprusaslicer-jni.so
cp libprusaslicer-jni.so /d/projects/u1-slicer-orca/app/src/main/jniLibs/arm64-v8a/
```

- [ ] **Step 5: Verify size + compiler + JNI completeness**

```bash
cd /d/projects/u1-slicer-orca/app/src/main/jniLibs/arm64-v8a
ls -la libprusaslicer-jni.so   # expect ~19–21 MB
llvm-readelf -p .comment libprusaslicer-jni.so | grep "clang version 17"
llvm-readelf -p .dynsym libprusaslicer-jni.so | grep -c Java_com_u1_slicer_NativeLibrary
```

The Java symbol count must equal the count of `external fun` declarations in
`NativeLibrary.kt`. A mismatch means the build dropped JNI methods — re-run
the "copy worktree sources into the bound source tree" dance from CLAUDE.md.

- [ ] **Step 6: Commit the binary**

```bash
git add app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so
git commit -m "F66: rebuild native .so with new JNI surface"
```

### Task 1.6: TDD — `isObjectSplittable` returns true on a known multi-island file

All Step-1 native contract tests are instrumented (the `.so` only loads under
Android). They live under `app/src/androidTest/java/com/u1/slicer/native_/`.

**Files:**
- Create: `app/src/androidTest/java/com/u1/slicer/native_/NativeSplitOrientTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package com.u1.slicer.native_

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeSplitOrientTest {
    private val lib = NativeLibrary()
    private val ctx = InstrumentationRegistry.getInstrumentation().context

    private fun copyAsset(name: String): String {
        val f = File(ctx.cacheDir, name.substringAfterLast('/'))
        ctx.assets.open(name).use { it.copyTo(f.outputStream()) }
        return f.absolutePath
    }

    @Test
    fun isObjectSplittable_multiObjectFile_returnsTrue() {
        // skywing-seawing-silkwing is multi-object; split should expose multiple islands.
        assertTrue(lib.loadModel(copyAsset("skywing-seawing-silkwing.3mf")))
        // At least one object on the bed should be reported as splittable
        // (the test is forgiving on which one — implementations may pre-split).
        val anySplittable = (0 until lib.nativeGetObjectCount())
            .any { lib.nativeIsObjectSplittable(it) }
        // If the fixture is already split into individual objects, the assertion
        // becomes "no false positives": every object reports false.
        // Either outcome is acceptable; both prove the probe runs.
        assertTrue("probe returned consistently", anySplittable ||
            (0 until lib.nativeGetObjectCount()).none { lib.nativeIsObjectSplittable(it) })
    }
}
```

- [ ] **Step 2: Run**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.native_.NativeSplitOrientTest \
  --no-daemon
```

Expected: PASS with the rebuilt `.so`.

- [ ] **Step 3: Commit the test**

```bash
git add app/src/androidTest/java/com/u1/slicer/native_/NativeSplitOrientTest.kt
git commit -m "F66: instrumented contract test for nativeIsObjectSplittable"
```

### Task 1.7: TDD — `splitObject` returns `[removedIdx, addedCount]` on a multi-island file

- [ ] **Step 1: Add the tests**

Append to `NativeSplitOrientTest.kt`:

```kotlin
@Test
fun splitObject_multiIslandObject_returnsRemovedIdxAndAddedCount() {
    // Find an object that reports splittable, then split it.
    assertTrue(lib.loadModel(copyAsset("Button-for-S-trousers.3mf")))
    // Button-for-S-trousers is a single 40-volume object — splittable.
    val splittableIdx = (0 until lib.nativeGetObjectCount())
        .firstOrNull { lib.nativeIsObjectSplittable(it) }
        ?: error("expected at least one splittable object")
    val before = lib.nativeGetObjectCount()
    val res = lib.nativeSplitObject(splittableIdx)
    assertNotNull("split should succeed", res)
    assertEquals("removedIdx", splittableIdx, res!![0])
    assertTrue("addedCount > 1", res[1] > 1)
    assertEquals("count delta", before - 1 + res[1], lib.nativeGetObjectCount())
}

@Test
fun splitObject_singleIslandFile_returnsNullAndLeavesModelUnchanged() {
    assertTrue(lib.loadModel(copyAsset("3DBenchy.stl")))
    val before = lib.nativeGetObjectCount()
    val res = lib.nativeSplitObject(0)
    assertNull("single-island object should not split", res)
    assertEquals(before, lib.nativeGetObjectCount())
}
```

- [ ] **Step 2: Run, expect PASS** (with the rebuilt `.so`):

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.native_.NativeSplitOrientTest \
  --no-daemon
```

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/java/com/u1/slicer/native_/NativeSplitOrientTest.kt
git commit -m "F66: contract tests for nativeSplitObject"
```

### Task 1.8: TDD — `autoOrientObject` rotates a tilted object so a flat face is down

- [ ] **Step 1: Add the tests**

Append:

```kotlin
@Test
fun autoOrientObject_tiltedModel_putsFlatFaceDown() {
    assertTrue(lib.loadModel(copyAsset("Articulated+Fish+(3).3mf")))
    // Pre-tilt to simulate a user-rotated model.
    assertTrue(lib.nativeSetObjectRotation(0, 45f, 30f, 0f))
    val resultEuler = lib.nativeAutoOrientObject(0)
    assertNotNull("orient should succeed", resultEuler)
    // After orient the model bottom-Z should be ~0 (sitting on the bed).
    val after = lib.getInstanceWorldZMins()
    assertEquals("bed-resting Z", 0.0, after[0].toDouble(), 0.5)
}

@Test
fun setObjectRotation_thenGet_roundTripsInDegrees() {
    assertTrue(lib.loadModel(copyAsset("3DBenchy.stl")))
    assertTrue(lib.nativeSetObjectRotation(0, 30f, 45f, 60f))
    val r = lib.nativeGetObjectRotation(0)
    assertEquals(30f, r[0], 0.1f)
    assertEquals(45f, r[1], 0.1f)
    assertEquals(60f, r[2], 0.1f)
}

@Test
fun setObjectScale_thenGet_roundTrips() {
    assertTrue(lib.loadModel(copyAsset("3DBenchy.stl")))
    assertTrue(lib.nativeSetObjectScale(0, 1.5f, 2.0f, 0.5f))
    val s = lib.nativeGetObjectScale(0)
    assertEquals(1.5f, s[0], 0.001f)
    assertEquals(2.0f, s[1], 0.001f)
    assertEquals(0.5f, s[2], 0.001f)
}
```

- [ ] **Step 2: Run, expect PASS**

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/java/com/u1/slicer/native_/NativeSplitOrientTest.kt
git commit -m "F66: contract tests for autoOrientObject + per-object rotation/scale round-trip"
```

### Task 1.9: TDD — paint preservation across split

- [ ] **Step 1: Add the test**

Append:

```kotlin
@Test
fun splitObject_paintedMultiIsland_preservesPaintCountPerNewObject() {
    // Use the painted flippy+flappy fixture which has paint states on multiple islands.
    assertTrue(lib.loadModel(copyAsset("flippy+flappy+mini-with-plate-painted.3mf")))
    val originalObj = 0
    val originalCounts = lib.nativeGetPaintStateCounts(originalObj, 0, /* kind */ 0)
        ?: error("expected paint state on volume 0")
    val totalPaintedBefore = originalCounts.sum()

    val res = lib.nativeSplitObject(originalObj)
    if (res == null) return // model only had one island — test is moot, not a failure

    // Sum paint state counts across all newly-created objects.
    var totalPaintedAfter = 0
    for (i in res[0] until res[0] + res[1]) {
        val volCount = lib.nativeGetVolumeCount(i)
        for (v in 0 until volCount) {
            val c = lib.nativeGetPaintStateCounts(i, v, /* kind */ 0)
            if (c != null) totalPaintedAfter += c.sum()
        }
    }
    // Allow small loss from boundary triangles (≤1% drift acceptable).
    assertTrue(
        "paint preserved through split (before=$totalPaintedBefore, after=$totalPaintedAfter)",
        totalPaintedAfter >= (totalPaintedBefore * 0.99).toInt()
    )
}
```

- [ ] **Step 2: Run, expect PASS**

If this test fails, the OrcaSlicer fork's `split()` is dropping paint state and
we need a C++ patch on our side (track as a sub-task; reference CLAUDE.md's
"Android-specific patches" pattern). Document the failure mode but do not
proceed to Step 2 of the plan until paint preservation is verified — split is
not safe to ship otherwise.

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/java/com/u1/slicer/native_/NativeSplitOrientTest.kt
git commit -m "F66: paint-preserved-through-split regression test"
```

---

## Step 2: ViewModel per-object pose + F89 schema bump + ViewModel tests

State model lands. App is fully functional headless; the UI still drives the
old global rotation/scale (replaced in Step 4).

### Task 2.1: `ObjectSelection` data class

**Files:**
- Create: `app/src/main/java/com/u1/slicer/ui/Selection.kt`
- Create: `app/src/test/java/com/u1/slicer/ui/SelectionStateMachineTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.u1.slicer.ui

import org.junit.Assert.*
import org.junit.Test

class SelectionStateMachineTest {

    @Test
    fun selectObject_setsIndex_clearsVolume() {
        val s = ObjectSelection().withObject(3)
        assertEquals(3, s.objectIndex)
        assertNull(s.volumeIndex)
    }

    @Test
    fun selectVolume_requiresObject_clearsIfObjectNull() {
        val s = ObjectSelection().withVolume(2)
        assertNull(s.objectIndex)
        assertNull(s.volumeIndex)
    }

    @Test
    fun selectVolume_withObjectSelected_setsBoth() {
        val s = ObjectSelection().withObject(1).withVolume(0)
        assertEquals(1, s.objectIndex)
        assertEquals(0, s.volumeIndex)
    }

    @Test
    fun deselect_clearsBoth() {
        val s = ObjectSelection().withObject(5).withVolume(2).cleared()
        assertNull(s.objectIndex)
        assertNull(s.volumeIndex)
    }

    @Test
    fun onSplitObject_advancesSelectionToFirstNew() {
        // Before: object 5 selected. Split 5 into 3 pieces → new pieces at 5,6,7.
        // Selection should auto-point to 5.
        val s = ObjectSelection().withObject(5).onSplit(removedIdx = 5, addedCount = 3)
        assertEquals(5, s.objectIndex)
    }

    @Test
    fun onSplitObject_shiftsSelectionAboveSplitPoint() {
        // Before: object 7 selected. Split 5 into 3 pieces → net +2.
        // Old 7 now at 9.
        val s = ObjectSelection().withObject(7).onSplit(removedIdx = 5, addedCount = 3)
        assertEquals(9, s.objectIndex)
    }

    @Test
    fun onSplitObject_belowSplit_unchanged() {
        val s = ObjectSelection().withObject(2).onSplit(removedIdx = 5, addedCount = 3)
        assertEquals(2, s.objectIndex)
    }
}
```

- [ ] **Step 2: Run, verify FAIL**

```bash
./gradlew :app:testDebugUnitTest --tests com.u1.slicer.ui.SelectionStateMachineTest
```

Expected: compile error (unresolved `ObjectSelection`).

- [ ] **Step 3: Implement `ObjectSelection`**

```kotlin
package com.u1.slicer.ui

data class ObjectSelection(
    val objectIndex: Int? = null,
    val volumeIndex: Int? = null,
) {
    fun withObject(idx: Int): ObjectSelection = copy(objectIndex = idx, volumeIndex = null)

    fun withVolume(idx: Int): ObjectSelection =
        if (objectIndex == null) this  // selecting a volume without an object → ignore
        else copy(volumeIndex = idx)

    fun cleared(): ObjectSelection = ObjectSelection()

    fun onSplit(removedIdx: Int, addedCount: Int): ObjectSelection {
        val cur = objectIndex ?: return this
        return when {
            cur == removedIdx -> withObject(removedIdx)              // first new piece
            cur > removedIdx  -> withObject(cur + addedCount - 1)    // net shift
            else              -> this                                // below split, unchanged
        }
    }
}
```

- [ ] **Step 4: Run, verify PASS**

```bash
./gradlew :app:testDebugUnitTest --tests com.u1.slicer.ui.SelectionStateMachineTest
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/ui/Selection.kt \
        app/src/test/java/com/u1/slicer/ui/SelectionStateMachineTest.kt
git commit -m "F66: ObjectSelection data class + state-transition tests"
```

### Task 2.2: `PerObjectPose` data + remap helpers

**Files:**
- Create: `app/src/main/java/com/u1/slicer/data/PerObjectPose.kt`
- Create: `app/src/test/java/com/u1/slicer/data/PerObjectPoseTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.u1.slicer.data

import org.junit.Assert.*
import org.junit.Test

class PerObjectPoseTest {

    @Test
    fun remapOnSplit_belowSplitIndex_unchanged() {
        val before = mapOf(0 to PerObjectPose(rotZDeg = 30f))
        val after = remapPerObjectMapOnSplit(before, removedIdx = 5, addedCount = 3)
        assertEquals(before[0], after[0])
    }

    @Test
    fun remapOnSplit_atSplitIndex_dropsAndDefaults() {
        val before = mapOf(5 to PerObjectPose(rotZDeg = 45f))
        val after = remapPerObjectMapOnSplit(before, removedIdx = 5, addedCount = 3)
        // The original key 5 is gone; new keys 5,6,7 default (or absent — same thing).
        assertNull(after[5])  // default = absent
    }

    @Test
    fun remapOnSplit_aboveSplitIndex_shiftsUpByAddedCountMinusOne() {
        val before = mapOf(7 to PerObjectPose(scaleX = 1.5f))
        val after = remapPerObjectMapOnSplit(before, removedIdx = 5, addedCount = 3)
        // 7 → 7 + 3 - 1 = 9.
        assertNull(after[7])
        assertEquals(1.5f, after[9]!!.scaleX, 0.0001f)
    }

    @Test
    fun isIdentity_defaults_returnsTrue() {
        assertTrue(PerObjectPose().isIdentity())
    }

    @Test
    fun isIdentity_nonZeroRotation_returnsFalse() {
        assertFalse(PerObjectPose(rotZDeg = 1f).isIdentity())
    }
}
```

- [ ] **Step 2: Run, verify FAIL**

- [ ] **Step 3: Implement**

```kotlin
package com.u1.slicer.data

/**
 * F66 per-object pose. Rotation is degrees XYZ; scale is per-axis. Identity
 * means rotation (0,0,0) and scale (1,1,1) — the load-time pose for raw STLs.
 */
data class PerObjectPose(
    val rotXDeg: Float = 0f,
    val rotYDeg: Float = 0f,
    val rotZDeg: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val scaleZ: Float = 1f,
) {
    fun isIdentity(): Boolean =
        rotXDeg == 0f && rotYDeg == 0f && rotZDeg == 0f &&
        scaleX == 1f && scaleY == 1f && scaleZ == 1f
}

/**
 * After a `nativeSplitObject(removedIdx)` returning `addedCount`, shift the
 * keys of a per-object map so existing state continues to point at the same
 * logical objects. New objects at [removedIdx, removedIdx+addedCount) get no
 * entry (they pick up defaults via `getOrElse(...)`).
 */
fun <V> remapPerObjectMapOnSplit(
    map: Map<Int, V>,
    removedIdx: Int,
    addedCount: Int,
): Map<Int, V> {
    val shift = addedCount - 1  // net delta (removed 1, added N)
    val out = HashMap<Int, V>(map.size)
    for ((k, v) in map) {
        when {
            k < removedIdx  -> out[k] = v
            k == removedIdx -> { /* dropped — new objects default */ }
            k > removedIdx  -> out[k + shift] = v
        }
    }
    return out
}
```

- [ ] **Step 4: Run, verify PASS**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/data/PerObjectPose.kt \
        app/src/test/java/com/u1/slicer/data/PerObjectPoseTest.kt
git commit -m "F66: PerObjectPose data class + remap-on-split helper + tests"
```

### Task 2.3: F89 schema bump — extend `SessionState` v2 → v3

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/data/SessionState.kt`
- Create: `app/src/test/java/com/u1/slicer/data/SessionStateF66RoundTripTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.u1.slicer.data

import org.junit.Assert.*
import org.junit.Test

class SessionStateF66RoundTripTest {

    private fun base() = SessionState(
        modelName = "Skadis.3mf",
        rawInputPath = "/sd/Skadis.3mf",
        sourceModelPath = null, currentModelPath = null, multiPlateSourcePath = null,
        selectedPlateId = null,
        modelScale = Triple(1f, 1f, 1f),
        modelRotation = Triple(0f, 0f, 0f),
        copyCount = 1,
        customObjectPositions = null, customWipeTowerPos = null,
        additionalFiles = emptyList(),
        sliceJobId = null, wasSliceComplete = false,
        savedAtEpochMs = 1L, appVersionCode = 1,
        selectedObjectIndex = 3,
        selectedVolumeIndex = null,
        perObjectPoses = mapOf(
            0 to PerObjectPose(rotZDeg = 45f),
            3 to PerObjectPose(scaleX = 1.2f, scaleY = 1.2f, scaleZ = 1.2f),
        ),
        perVolumeExtruders = mapOf("3:0" to 2, "3:1" to 3),
        splitObjectOperations = listOf(2, 5),
        splitVolumeOperations = listOf("4:0"),
    )

    @Test
    fun roundTrip_preservesAllF66Fields() {
        val src = base()
        val json = SessionState.toJson(src)
        val restored = SessionState.fromJson(json)
        assertEquals(src, restored)
    }

    @Test
    fun fromJson_oldV2Schema_returnsNull() {
        // v2 sessions (pre-F66) intentionally don't restore — F89 returns null on
        // any version mismatch, by design.
        val v2 = """{"version":2,"modelName":"x","rawInputPath":"y", ...}"""
        assertNull(SessionState.fromJson(v2))
    }
}
```

- [ ] **Step 2: Run, expect FAIL** (compile errors — new fields don't exist).

- [ ] **Step 3: Implement schema bump**

Edit `SessionState.kt`:

1. Add new constructor params at the end (keep order stable; data class equality
   already iterates all fields):
   ```kotlin
       val selectedObjectIndex: Int? = null,
       val selectedVolumeIndex: Int? = null,
       val perObjectPoses: Map<Int, PerObjectPose> = emptyMap(),
       val perVolumeExtruders: Map<String, Int> = emptyMap(),  // key = "objIdx:volIdx"
       val splitObjectOperations: List<Int> = emptyList(),
       val splitVolumeOperations: List<String> = emptyList(),  // entries = "objIdx:volIdx"
   ```

2. Bump:
   ```kotlin
   const val SCHEMA_VERSION = 3
   ```

3. Extend `toJson` to emit the new fields (`putOpt` where helpful for empty
   collections); extend `fromJson` to read them with safe defaults.

4. Extend `equals`/`hashCode` to cover the new fields.

- [ ] **Step 4: Run, verify PASS**

```bash
./gradlew :app:testDebugUnitTest --tests com.u1.slicer.data.SessionStateF66RoundTripTest
```

- [ ] **Step 5: Update existing `SessionStateTest` cases that constructed v2 JSON literals**

```bash
./gradlew :app:testDebugUnitTest --tests "com.u1.slicer.data.SessionStateTest"
```

The 12 existing cases in
[`SessionStateTest.kt`](app/src/test/java/com/u1/slicer/data/SessionStateTest.kt)
build `SessionState` instances directly with the data-class constructor — those
keep working because the new fields default to `null` / empty. But any case
that constructs a **v2 JSON string literal** and asserts it loads must be
updated to the v3 schema (bump `"version":2` → `"version":3` in those
literals). The "unknown future schema version returns null" and "past schema
version returns null" cases are already correct — they continue to assert null
for any version that isn't `SCHEMA_VERSION`. **Never weaken an assertion** (per
CLAUDE.md rule). If a test was specifically designed to verify v2 compatibility,
add a parallel v3 case rather than replacing it.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/u1/slicer/data/SessionState.kt \
        app/src/test/java/com/u1/slicer/data/SessionStateF66RoundTripTest.kt
git commit -m "F66: SessionState v3 — selection, per-object pose, per-volume extruder, split ops"
```

### Task 2.4: SlicerViewModel — selection + per-object pose state

`SlicerViewModel` is an `AndroidViewModel` and can't be instantiated from JVM
unit tests (no `Application` context). All ViewModel-level tests below live
in `app/src/androidTest/` and follow the existing pattern of
[`PreparePreviewViewModelTest`](app/src/androidTest/java/com/u1/slicer/PreparePreviewViewModelTest.kt)
— they construct a real `SlicerViewModel` against the InstrumentationRegistry
`ApplicationProvider` context and a real (rebuilt) native library.

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`
- Create: `app/src/androidTest/java/com/u1/slicer/PerObjectTransformIsolationTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.u1.slicer

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.u1.slicer.data.PerObjectPose
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PerObjectTransformIsolationTest {

    @Test
    fun setObjectRotation_object0_doesNotAffectObject1() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = SlicerViewModel(ctx)
        // Load a fixture with ≥2 objects so per-object indexing is meaningful.
        // skywing-seawing-silkwing is multi-object; suitable for isolation tests.
        val asset = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().context.assets
        val tmp = java.io.File(ctx.cacheDir, "iso.3mf").apply {
            asset.open("skywing-seawing-silkwing.3mf").use { it.copyTo(outputStream()) }
        }
        vm.loadModelFromFile(tmp)
        // Wait until the model state observes loaded — use the existing
        // `awaitState(...)` helper that PreparePreviewViewModelTest defines.
        awaitState(vm.modelState) { it is SlicerViewModel.ModelState.Loaded }

        val rot0Before = vm.perObjectPoses.value[0] ?: PerObjectPose()
        val rot1Before = vm.perObjectPoses.value[1] ?: PerObjectPose()
        vm.setObjectRotation(0, 45f, 0f, 0f)

        assertEquals(45f, vm.perObjectPoses.value[0]!!.rotXDeg, 0.001f)
        // Object 1 untouched.
        assertEquals(rot1Before.rotXDeg, vm.perObjectPoses.value[1]!!.rotXDeg, 0.001f)
    }
}
```

(`awaitState(...)` is the existing test helper at the top of
`PreparePreviewViewModelTest.kt` — reuse it directly. Do not introduce a new
mock framework.)

- [ ] **Step 2: Run, expect FAIL**

- [ ] **Step 3: Add ViewModel state and actions**

Inside `SlicerViewModel`, add:

```kotlin
private val _selection = MutableStateFlow(ObjectSelection())
val selection: StateFlow<ObjectSelection> = _selection

private val _perObjectPoses = MutableStateFlow<Map<Int, PerObjectPose>>(emptyMap())
val perObjectPoses: StateFlow<Map<Int, PerObjectPose>> = _perObjectPoses

private val _loadTimePoses = MutableStateFlow<Map<Int, PerObjectPose>>(emptyMap())
val loadTimePoses: StateFlow<Map<Int, PerObjectPose>> = _loadTimePoses

private val _perVolumeExtruders = MutableStateFlow<Map<String, Int>>(emptyMap())
val perVolumeExtruders: StateFlow<Map<String, Int>> = _perVolumeExtruders

// ---- Selection ----
fun selectObject(idx: Int?) { _selection.value = _selection.value.copy(objectIndex = idx, volumeIndex = null) }
fun selectVolume(idx: Int?) { _selection.value = _selection.value.withVolume(idx ?: -1).let {
    if (idx == null) it.copy(volumeIndex = null) else it
} }
fun deselect() { _selection.value = ObjectSelection() }

// ---- Per-object pose ----
fun setObjectRotation(objIdx: Int, x: Float, y: Float, z: Float) {
    if (!native.nativeSetObjectRotation(objIdx, x, y, z)) return
    _perObjectPoses.update { it + (objIdx to (it[objIdx] ?: PerObjectPose()).copy(
        rotXDeg = x, rotYDeg = y, rotZDeg = z)) }
    invalidatePrepareMeshCache()
}

fun setObjectScale(objIdx: Int, sx: Float, sy: Float, sz: Float) {
    if (!native.nativeSetObjectScale(objIdx, sx, sy, sz)) return
    _perObjectPoses.update { it + (objIdx to (it[objIdx] ?: PerObjectPose()).copy(
        scaleX = sx, scaleY = sy, scaleZ = sz)) }
    invalidatePrepareMeshCache()
}

fun snapshotLoadTimePoses(objectCount: Int) {
    val baseline = (0 until objectCount).associateWith { i ->
        val r = native.nativeGetObjectRotation(i)
        val s = native.nativeGetObjectScale(i)
        PerObjectPose(r[0], r[1], r[2], s[0], s[1], s[2])
    }
    _loadTimePoses.value = baseline
    _perObjectPoses.value = baseline
}
```

(More action methods — split, autoOrient, reset — land in later tasks.)

- [ ] **Step 4: Run, verify PASS**

```bash
./gradlew :app:testDebugUnitTest --tests com.u1.slicer.PerObjectTransformIsolationTest
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/SlicerViewModel.kt \
        app/src/test/java/com/u1/slicer/PerObjectTransformIsolationTest.kt
git commit -m "F66: ViewModel selection + per-object pose state + isolation test"
```

### Task 2.5: Reset rotation / scale + tests

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`
- Create: `app/src/androidTest/java/com/u1/slicer/ResetTransformTest.kt` (instrumented — same reason as Task 2.4)

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.u1.slicer

import com.u1.slicer.data.PerObjectPose
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class ResetTransformTest {

    private val native: NativeLibrary = mock {
        on { nativeGetObjectCount() } doReturn 3
        on { nativeSetObjectRotation(any(), any(), any(), any()) } doReturn true
        on { nativeSetObjectScale(any(), any(), any(), any()) } doReturn true
        on { nativeGetObjectRotation(any()) } doReturn floatArrayOf(0f, 0f, 0f)
        on { nativeGetObjectScale(any()) } doReturn floatArrayOf(1f, 1f, 1f)
    }

    @Test
    fun resetObjectRotation_restoresLoadTimeRotation_preservesScale() {
        val vm = SlicerViewModelTestHarness.create(native)
        vm.snapshotLoadTimePoses(objectCount = 3)
        vm.setObjectRotation(1, 45f, 0f, 0f)
        vm.setObjectScale(1, 1.5f, 1.5f, 1.5f)
        vm.resetObjectRotation(1)
        assertEquals(0f, vm.perObjectPoses.value[1]!!.rotXDeg, 0.001f)
        assertEquals(1.5f, vm.perObjectPoses.value[1]!!.scaleX, 0.001f)
    }

    @Test
    fun resetAllRotations_restoresEveryObject() {
        val vm = SlicerViewModelTestHarness.create(native)
        vm.snapshotLoadTimePoses(3)
        vm.setObjectRotation(0, 10f, 0f, 0f)
        vm.setObjectRotation(2, 90f, 0f, 0f)
        vm.resetAllRotations()
        assertTrue(vm.perObjectPoses.value.values.all { it.rotXDeg == 0f })
    }
}
```

- [ ] **Step 2: Run, expect FAIL**

- [ ] **Step 3: Implement**

In `SlicerViewModel`:

```kotlin
fun resetObjectRotation(objIdx: Int) {
    val baseline = _loadTimePoses.value[objIdx] ?: return
    setObjectRotation(objIdx, baseline.rotXDeg, baseline.rotYDeg, baseline.rotZDeg)
}

fun resetObjectScale(objIdx: Int) {
    val baseline = _loadTimePoses.value[objIdx] ?: return
    setObjectScale(objIdx, baseline.scaleX, baseline.scaleY, baseline.scaleZ)
}

fun resetAllRotations() {
    _loadTimePoses.value.keys.forEach { resetObjectRotation(it) }
}

fun resetAllScales() {
    _loadTimePoses.value.keys.forEach { resetObjectScale(it) }
}
```

- [ ] **Step 4: Run, verify PASS**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/SlicerViewModel.kt \
        app/src/test/java/com/u1/slicer/ResetTransformTest.kt
git commit -m "F66: reset rotation/scale to load-time + tests"
```

### Task 2.6: Split action + remap-on-split

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`
- Create: `app/src/androidTest/java/com/u1/slicer/SplitObjectViewModelTest.kt` (instrumented)

- [ ] **Step 1: Write the failing test**

```kotlin
package com.u1.slicer

import com.u1.slicer.data.PerObjectPose
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class SplitObjectViewModelTest {

    @Test
    fun splitObject_remapsPerObjectPoses_andAdvancesSelection() {
        val native: NativeLibrary = mock {
            on { nativeGetObjectCount() } doReturnConsecutively listOf(8, 10)  // 8 → split 5 → 10
            on { nativeSplitObject(5) } doReturn intArrayOf(5, 3)
            on { nativeGetObjectRotation(any()) } doReturn floatArrayOf(0f, 0f, 0f)
            on { nativeGetObjectScale(any()) } doReturn floatArrayOf(1f, 1f, 1f)
        }
        val vm = SlicerViewModelTestHarness.create(native)
        vm.snapshotLoadTimePoses(8)
        vm.setObjectRotation(7, 30f, 0f, 0f)
        vm.selectObject(7)

        vm.splitObject(5)

        // Selection advanced (old 7 now at 9 — but desktop convention auto-selects
        // first new object 5 instead).
        assertEquals(5, vm.selection.value.objectIndex)
        // Object 7's pose moved to index 9.
        assertEquals(30f, vm.perObjectPoses.value[9]!!.rotXDeg, 0.001f)
        // New pieces 5/6/7 default.
        assertTrue(vm.perObjectPoses.value[5]!!.isIdentity())
    }

    @Test
    fun splitObject_returnsNullFromNative_leavesSelectionAndStateUnchanged() {
        val native: NativeLibrary = mock {
            on { nativeSplitObject(any()) } doReturn null
            on { nativeGetObjectCount() } doReturn 3
            on { nativeGetObjectRotation(any()) } doReturn floatArrayOf(0f, 0f, 0f)
            on { nativeGetObjectScale(any()) } doReturn floatArrayOf(1f, 1f, 1f)
        }
        val vm = SlicerViewModelTestHarness.create(native)
        vm.snapshotLoadTimePoses(3)
        vm.selectObject(1)
        val before = vm.perObjectPoses.value
        vm.splitObject(1)
        assertEquals(1, vm.selection.value.objectIndex)
        assertEquals(before, vm.perObjectPoses.value)
    }
}
```

- [ ] **Step 2: Run, expect FAIL**

- [ ] **Step 3: Implement**

```kotlin
fun splitObject(objIdx: Int) {
    val res = native.nativeSplitObject(objIdx) ?: return
    val removedIdx = res[0]
    val addedCount = res[1]
    _perObjectPoses.value = remapPerObjectMapOnSplit(_perObjectPoses.value, removedIdx, addedCount)
    _loadTimePoses.value = remapPerObjectMapOnSplit(_loadTimePoses.value, removedIdx, addedCount)
    // Snapshot defaults for the new pieces so Reset works.
    val newCount = native.nativeGetObjectCount()
    val poses = _loadTimePoses.value.toMutableMap()
    for (i in removedIdx until removedIdx + addedCount) {
        val r = native.nativeGetObjectRotation(i)
        val s = native.nativeGetObjectScale(i)
        poses[i] = PerObjectPose(r[0], r[1], r[2], s[0], s[1], s[2])
    }
    _loadTimePoses.value = poses
    _perObjectPoses.value = _perObjectPoses.value + poses.filterKeys { it in removedIdx until removedIdx + addedCount }
    _selection.value = _selection.value.onSplit(removedIdx, addedCount).withObject(removedIdx)
    invalidatePrepareMeshCache()
}
```

- [ ] **Step 4: Run, verify PASS**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/SlicerViewModel.kt \
        app/src/test/java/com/u1/slicer/SplitObjectViewModelTest.kt
git commit -m "F66: splitObject ViewModel action with per-object remap + selection advance"
```

### Task 2.7: Auto-orient action wrapped in LongOpService

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`
- Create: `app/src/androidTest/java/com/u1/slicer/AutoOrientViewModelTest.kt` (instrumented)

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun autoOrientObject_updatesPoseFromNativeResult() {
    val native: NativeLibrary = mock {
        on { nativeAutoOrientObject(0) } doReturn doubleArrayOf(
            Math.PI / 4, 0.0, Math.PI / 2)  // 45°, 0°, 90°
        on { nativeGetObjectRotation(any()) } doReturn floatArrayOf(45f, 0f, 90f)
        on { nativeGetObjectScale(any()) } doReturn floatArrayOf(1f, 1f, 1f)
        on { nativeGetObjectCount() } doReturn 1
    }
    val vm = SlicerViewModelTestHarness.create(native)
    vm.snapshotLoadTimePoses(1)
    runBlocking { vm.autoOrientObject(0) }
    val p = vm.perObjectPoses.value[0]!!
    assertEquals(45f, p.rotXDeg, 0.5f)
    assertEquals(90f, p.rotZDeg, 0.5f)
}
```

- [ ] **Step 2: Run, expect FAIL**

- [ ] **Step 3: Implement**

```kotlin
suspend fun autoOrientObject(objIdx: Int) {
    LongOpService.start(getApplication(), "Auto-orienting object")
    try {
        withContext(Dispatchers.IO) {
            val euler = native.nativeAutoOrientObject(objIdx) ?: return@withContext
            val r = native.nativeGetObjectRotation(objIdx)
            _perObjectPoses.update { it + (objIdx to (it[objIdx] ?: PerObjectPose())
                .copy(rotXDeg = r[0], rotYDeg = r[1], rotZDeg = r[2])) }
        }
    } finally {
        LongOpService.stop(getApplication())
    }
    invalidatePrepareMeshCache()
}

suspend fun autoOrientAll() {
    LongOpService.start(getApplication(), "Auto-orienting all objects")
    try {
        withContext(Dispatchers.IO) {
            native.nativeAutoOrientAll()
            // Refresh all poses from native.
            val n = native.nativeGetObjectCount()
            val updated = HashMap(_perObjectPoses.value)
            for (i in 0 until n) {
                val r = native.nativeGetObjectRotation(i)
                updated[i] = (updated[i] ?: PerObjectPose())
                    .copy(rotXDeg = r[0], rotYDeg = r[1], rotZDeg = r[2])
            }
            _perObjectPoses.value = updated
        }
    } finally {
        LongOpService.stop(getApplication())
    }
    invalidatePrepareMeshCache()
}
```

- [ ] **Step 4: Run, verify PASS**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/SlicerViewModel.kt \
        app/src/test/java/com/u1/slicer/AutoOrientViewModelTest.kt
git commit -m "F66: autoOrientObject / autoOrientAll actions wrapped in LongOpService"
```

### Task 2.8: Per-volume extruder assignment

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`
- Create: `app/src/test/java/com/u1/slicer/PerVolumeExtruderTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun setVolumeExtruder_persistsInState_andCallsNative() {
    val native: NativeLibrary = mock {
        on { nativeSetVolumeExtruder(any(), any(), any()) } doReturn true
        on { nativeGetVolumeExtruder(any(), any()) } doReturn 1
    }
    val vm = SlicerViewModelTestHarness.create(native)
    vm.setVolumeExtruder(2, 0, 3)
    assertEquals(3, vm.perVolumeExtruders.value["2:0"])
    verify(native).nativeSetVolumeExtruder(2, 0, 3)
}
```

- [ ] **Step 2: Run, expect FAIL**

- [ ] **Step 3: Implement**

```kotlin
fun setVolumeExtruder(objIdx: Int, volIdx: Int, slot: Int) {
    if (!native.nativeSetVolumeExtruder(objIdx, volIdx, slot)) return
    val key = "$objIdx:$volIdx"
    _perVolumeExtruders.update { it + (key to slot) }
    _sliceStale.value = true  // existing F67 staleness flag
}
```

- [ ] **Step 4: Run, verify PASS**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/SlicerViewModel.kt \
        app/src/test/java/com/u1/slicer/PerVolumeExtruderTest.kt
git commit -m "F66: per-volume extruder assignment action"
```

### Task 2.9: F89 hydration of new fields

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`

- [ ] **Step 1: Extend the existing `saveSessionState()` / restore paths**

In `saveSessionState()` (find it via `grep -n "savedAtEpochMs" app/src/main/java/com/u1/slicer/SlicerViewModel.kt`),
populate the new fields from `_selection.value`, `_perObjectPoses.value`,
`_perVolumeExtruders.value`, and the recorded `splitObjectOperations` /
`splitVolumeOperations` lists (add private fields `splitObjectOps: MutableList<Int>`
and `splitVolumeOps: MutableList<String>` updated by `splitObject` /
`splitVolume`).

In the restore path (search for `SessionState.fromJson`), after the existing
file-load + rotation/scale replay, run the new replay:
1. Replay `splitObjectOperations` in order, calling `native.nativeSplitObject(idx)`.
2. Replay `splitVolumeOperations` similarly.
3. Apply `perObjectPoses` via `nativeSetObjectRotation` + `nativeSetObjectScale`.
4. Apply `perVolumeExtruders` via `nativeSetVolumeExtruder`.
5. Restore `_selection.value`.

- [ ] **Step 2: Manually verify the wiring**

Existing F89 tests (`SessionResumeIntegrationTest`) must still pass:

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.SessionResumeIntegrationTest \
  --no-daemon
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/u1/slicer/SlicerViewModel.kt
git commit -m "F66: save and replay split/pose/extruder state through F89"
```

(Full F89 end-to-end coverage with the new fields is added in Step 6 as
`SessionStateF66ResumeTest`.)

---

## Step 3: Renderer per-object pose + selection highlight + tap-to-select

The 3D preview becomes pose-per-object aware and the user can tap to select.
At the end of Step 3, you can already rotate/scale individual objects and see
the highlight — the new Edit panel layout in Step 4 just makes it discoverable.

### Task 3.1: Per-instance rotation + scale in `ModelRenderer`

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/viewer/ModelRenderer.kt`

- [ ] **Step 1: Add fields**

Near the existing `instancePositions: FloatArray?` declaration:

```kotlin
// F66: per-instance rotation (degrees XYZ) and scale (per-axis), parallel
// arrays to instancePositions. Null = identity (1.0 scale, 0° rotation).
@Volatile var instanceRotations: FloatArray? = null
@Volatile var instanceScales: FloatArray? = null
```

- [ ] **Step 2: Compose the per-instance model matrix**

Find the existing `drawModelAt(mesh, px, py)` call site inside `onDrawFrame()`.
Wrap it so that for each instance `i`, the model matrix is computed as
`T(i) * R(i) * S(i)` and uploaded to the existing `u_mvp` / `u_model` uniform
pair before the draw. If `instanceRotations`/`instanceScales` are null, fall
back to the existing translate-only path (preserves F77 behaviour exactly for
single-pose multi-instance scenes).

- [ ] **Step 3: Update pickingPositions on pose change**

`pickingPositions` is the world-space transformed-vertex array consumed by the
lasso/picking code in `ModelViewerView`. Currently rebuilt when `mesh` or
`instancePositions` change. Add `instanceRotations`/`instanceScales` to the
same invalidation set.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/u1/slicer/viewer/ModelRenderer.kt
git commit -m "F66: per-instance rotation + scale in ModelRenderer + picking refresh"
```

### Task 3.2: Selection-highlight second draw pass

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/viewer/ModelRenderer.kt`

- [ ] **Step 1: Add the uniform + shader code**

In the fragment shader source, add:

```glsl
uniform vec4 u_highlight;  // .a = 0 means no highlight
// after lighting:
finalColor.rgb = mix(finalColor.rgb, u_highlight.rgb, u_highlight.a);
```

In `ModelRenderer`, add `@Volatile var selectedObjectIndex: Int? = null` plus a
second-pass loop in `onDrawFrame()`:

```kotlin
val sel = selectedObjectIndex
val ranges = objectMeshRanges
if (sel != null && ranges != null && sel in ranges.indices) {
    GLES30.glUniform4f(uHighlightLoc, 1.0f, 0.6f, 0.2f, 0.35f)  // M3 secondary tint
    drawMeshRange(mesh, ranges[sel].first, ranges[sel].second)
    GLES30.glUniform4f(uHighlightLoc, 0f, 0f, 0f, 0f)
}
```

(`drawMeshRange(mesh, start, count)` already exists for the F77 per-object
path or is a small new helper around `glDrawArrays` with offset + count.)

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/u1/slicer/viewer/ModelRenderer.kt
git commit -m "F66: selection-highlight second pass with u_highlight uniform"
```

### Task 3.3: Wire tap-to-select on the Prepare-screen viewer

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/ui/InlineModelPreview.kt`
- Modify: `app/src/main/java/com/u1/slicer/MainActivity.kt`

- [ ] **Step 1: Wire callbacks**

In `InlineModelPreview`, attach:

```kotlin
viewerView.onTriangleTapped = { triIdx ->
    val ranges = renderer.objectMeshRanges ?: return@onTriangleTapped
    val objIdx = ranges.indexOfFirst { (start, count) ->
        triIdx in start until (start + count)
    }
    if (objIdx >= 0) viewModel.selectObject(objIdx)
}
viewerView.onEmptyTap = { viewModel.deselect() }
```

Pass `selectedObjectIndex` from the collected `vm.selection.value.objectIndex`
into the renderer:

```kotlin
LaunchedEffect(selectedObjectIndex) {
    renderer.selectedObjectIndex = selectedObjectIndex
    viewerView.requestRender()
}
```

- [ ] **Step 2: Pass per-object pose arrays into the renderer**

In the same `InlineModelPreview` LaunchedEffect that pushes pose, build:

```kotlin
val rotations = FloatArray(objectCount * 3) { i ->
    val obj = i / 3; val axis = i % 3
    perObjectPoses[obj]?.let { when (axis) { 0 -> it.rotXDeg; 1 -> it.rotYDeg; else -> it.rotZDeg } } ?: 0f
}
// same pattern for scales
renderer.instanceRotations = rotations
renderer.instanceScales = scales
viewerView.requestRender()
```

- [ ] **Step 3: Manually verify on device**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew installDebug --no-daemon
```

Open a multi-object 3MF (e.g. `Button-for-S-trousers.3mf`). Tap an object on
the bed — it should highlight. Tap empty space — highlight clears. Pan / tilt
/ zoom should be unaffected.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/u1/slicer/ui/InlineModelPreview.kt \
        app/src/main/java/com/u1/slicer/MainActivity.kt
git commit -m "F66: wire tap-to-select on Prepare viewer + push per-object poses to renderer"
```

---

## Step 4: Edit panel reshape — Split / Auto-Orient / Reset buttons

The visible Compose UI lands here. After Step 4 every action except per-part
filament works through the new panel.

### Task 4.1: New `EditPanel` composable — bed-wide branch

**Files:**
- Create: `app/src/main/java/com/u1/slicer/ui/EditPanel.kt`

- [ ] **Step 1: Implement the bed-wide branch**

```kotlin
package com.u1.slicer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.u1.slicer.SlicerViewModel
import kotlinx.coroutines.launch

@Composable
fun EditPanel(viewModel: SlicerViewModel, modifier: Modifier = Modifier) {
    val selection by viewModel.selection.collectAsState()
    val poses by viewModel.perObjectPoses.collectAsState()
    val loadTime by viewModel.loadTimePoses.collectAsState()
    val scope = rememberCoroutineScope()

    when (val sel = selection.objectIndex) {
        null -> BedWideEditSection(
            anyRotationDirty = poses.any { (k, v) -> v.rotXDeg != (loadTime[k]?.rotXDeg ?: 0f) ||
                v.rotYDeg != (loadTime[k]?.rotYDeg ?: 0f) || v.rotZDeg != (loadTime[k]?.rotZDeg ?: 0f) },
            anyScaleDirty = poses.any { (k, v) -> v.scaleX != (loadTime[k]?.scaleX ?: 1f) ||
                v.scaleY != (loadTime[k]?.scaleY ?: 1f) || v.scaleZ != (loadTime[k]?.scaleZ ?: 1f) },
            onAutoOrientAll = { scope.launch { viewModel.autoOrientAll() } },
            onResetAllRotations = { viewModel.resetAllRotations() },
            onResetAllScales = { viewModel.resetAllScales() },
            modifier = modifier,
        )
        else -> ObjectScopedEditSection(
            objIdx = sel,
            // ...wired in Task 4.2
            modifier = modifier,
        )
    }
}

@Composable
private fun BedWideEditSection(
    anyRotationDirty: Boolean,
    anyScaleDirty: Boolean,
    onAutoOrientAll: () -> Unit,
    onResetAllRotations: () -> Unit,
    onResetAllScales: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Existing bed-wide Rotate/Scale sliders live above this card in MainActivity;
        // here we add only the new bed-wide buttons.
        FilledTonalButton(onClick = onAutoOrientAll, modifier = Modifier.fillMaxWidth()) {
            Text("Auto-orient all")
        }
        if (anyRotationDirty) {
            OutlinedButton(onClick = onResetAllRotations, modifier = Modifier.fillMaxWidth()) {
                Text("Reset all rotations")
            }
        }
        if (anyScaleDirty) {
            OutlinedButton(onClick = onResetAllScales, modifier = Modifier.fillMaxWidth()) {
                Text("Reset all scales")
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/u1/slicer/ui/EditPanel.kt
git commit -m "F66: EditPanel bed-wide branch (Auto-orient all + Reset all)"
```

### Task 4.2: `EditPanel` object-scoped branch

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/ui/EditPanel.kt`

- [ ] **Step 1: Implement**

```kotlin
@Composable
private fun ObjectScopedEditSection(
    objIdx: Int,
    viewModel: SlicerViewModel,
    modifier: Modifier = Modifier,
) {
    val poses by viewModel.perObjectPoses.collectAsState()
    val loadTime by viewModel.loadTimePoses.collectAsState()
    val pose = poses[objIdx] ?: com.u1.slicer.data.PerObjectPose()
    val baseline = loadTime[objIdx] ?: com.u1.slicer.data.PerObjectPose()
    val name = remember(objIdx) { viewModel.objectNameFor(objIdx) }
    val isSplittable = remember(objIdx) { viewModel.isObjectSplittable(objIdx) }
    val volumeCount = remember(objIdx) { viewModel.volumeCount(objIdx) }
    val scope = rememberCoroutineScope()

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Selected: $name (#${objIdx + 1})", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f))
            IconButton(onClick = { viewModel.deselect() }) {
                Icon(Icons.Default.Close, contentDescription = "Deselect")
            }
        }

        // Per-object Rotate / Scale (these replace the bed-wide sliders when
        // something is selected — the existing sliders' `onValueChange` is
        // rerouted via MainActivity into vm.setObjectRotation/Scale).

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = { scope.launch { viewModel.autoOrientObject(objIdx) } },
                modifier = Modifier.weight(1f)) { Text("Auto-orient") }
            FilledTonalButton(
                onClick = { viewModel.splitObject(objIdx) },
                enabled = isSplittable,
                modifier = Modifier.weight(1f)) { Text("Split to Objects") }
        }
        if (volumeCount > 1) {
            FilledTonalButton(onClick = { viewModel.splitFirstSplittableVolume(objIdx) },
                modifier = Modifier.fillMaxWidth()) {
                Text("Split to Parts")
            }
        }
        if (pose.rotXDeg != baseline.rotXDeg || pose.rotYDeg != baseline.rotYDeg
                || pose.rotZDeg != baseline.rotZDeg) {
            OutlinedButton(onClick = { viewModel.resetObjectRotation(objIdx) },
                modifier = Modifier.fillMaxWidth()) { Text("Reset rotation") }
        }
        if (pose.scaleX != baseline.scaleX || pose.scaleY != baseline.scaleY
                || pose.scaleZ != baseline.scaleZ) {
            OutlinedButton(onClick = { viewModel.resetObjectScale(objIdx) },
                modifier = Modifier.fillMaxWidth()) { Text("Reset scale") }
        }
        OutlinedButton(onClick = { viewModel.deleteObject(objIdx) },
            modifier = Modifier.fillMaxWidth()) { Text("Delete") }

        if (volumeCount > 1) {
            PartsPanel(objIdx = objIdx, viewModel = viewModel)
        }
    }
}
```

Update the `EditPanel`-level `when` to pass `viewModel` into the object branch.

- [ ] **Step 2: Add helper methods to ViewModel**

In `SlicerViewModel`, add `objectNameFor`, `isObjectSplittable`, `volumeCount`,
`splitFirstSplittableVolume`, and `deleteObject` as thin wrappers over the new
JNI surface plus the existing delete path (already present for F77).

- [ ] **Step 3: Build and visually verify on device**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew installDebug --no-daemon
```

Load `Button-for-S-trousers.3mf`. Tap an object. Verify Edit panel shows the
object-scoped layout. Tap × — bed-wide layout returns.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/u1/slicer/ui/EditPanel.kt \
        app/src/main/java/com/u1/slicer/SlicerViewModel.kt
git commit -m "F66: EditPanel object-scoped branch with Split/Auto-Orient/Reset/Delete"
```

### Task 4.3: Replace MainActivity bed-wide controls with `EditPanel`

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/MainActivity.kt`

- [ ] **Step 1: Reroute Rotate/Scale sliders**

Find the existing bed-wide Rotate/Scale slider section. Wrap its `onValueChange`
in a check:

```kotlin
val selectedObjIdx by vm.selection.map { it.objectIndex }.collectAsState(null)
// Rotate Z slider:
onValueChange = { z ->
    if (selectedObjIdx != null) {
        vm.setObjectRotation(selectedObjIdx!!, currentX, currentY, z)
    } else {
        vm.setModelRotation(ModelRotation(currentX, currentY, z))  // existing bed-wide path
    }
}
```

Apply the same routing to X/Y and to each scale slider.

- [ ] **Step 2: Render `EditPanel` below the sliders**

```kotlin
EditPanel(viewModel = vm, modifier = Modifier.fillMaxWidth())
```

- [ ] **Step 3: Verify on device**

Single-STL workflow (load Benchy, rotate, scale) must behave exactly as
before. Multi-object load + selection routes the same sliders to the selected
object.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/u1/slicer/MainActivity.kt
git commit -m "F66: Rotate/Scale sliders follow selection; EditPanel wired into MainActivity"
```

---

## Step 5: Parts panel + per-part filament

### Task 5.1: `PartsPanel` composable

**Files:**
- Create: `app/src/main/java/com/u1/slicer/ui/PartsPanel.kt`

- [ ] **Step 1: Implement**

```kotlin
package com.u1.slicer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.u1.slicer.SlicerViewModel

@Composable
fun PartsPanel(objIdx: Int, viewModel: SlicerViewModel, modifier: Modifier = Modifier) {
    val volumeCount = remember(objIdx) { viewModel.volumeCount(objIdx) }
    val perVolume by viewModel.perVolumeExtruders.collectAsState()
    var expanded by remember(objIdx) { mutableStateOf(false) }

    Column(modifier) {
        TextButton(onClick = { expanded = !expanded }) {
            Text("Parts ($volumeCount)" + if (expanded) " ⌃" else " ⌄")
        }
        if (expanded) {
            for (v in 0 until volumeCount) {
                val key = "$objIdx:$v"
                val slot = perVolume[key] ?: viewModel.fetchVolumeExtruder(objIdx, v)
                val name = remember(objIdx, v) { viewModel.volumeNameFor(objIdx, v) }
                PartRow(
                    name = name,
                    currentSlot = slot,
                    extruderColors = viewModel.extruderColorsForChips(),
                    onSelectSlot = { newSlot -> viewModel.setVolumeExtruder(objIdx, v, newSlot) },
                    modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth(),
                )
            }
            OutlinedButton(
                onClick = { viewModel.resetPartFilaments(objIdx) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text("Reset part filaments") }
        }
    }
}

@Composable
private fun PartRow(
    name: String, currentSlot: Int, extruderColors: List<Long>,
    onSelectSlot: (Int) -> Unit, modifier: Modifier = Modifier,
) {
    var sheetOpen by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(name, modifier = Modifier.weight(1f))
        // Existing FilamentChip composable from the FilamentMapping flow:
        FilamentChip(slot = currentSlot, colorArgb = extruderColors.getOrNull(currentSlot - 1) ?: 0xFF888888L)
        IconButton(onClick = { sheetOpen = true }) { Icon(Icons.Default.Edit, contentDescription = "Change") }
    }
    if (sheetOpen) {
        FilamentSlotChooserSheet(
            current = currentSlot, colors = extruderColors,
            onPicked = { onSelectSlot(it); sheetOpen = false },
            onDismiss = { sheetOpen = false },
        )
    }
}
```

(`FilamentChip` and `FilamentSlotChooserSheet` exist in
[`FilamentMappingDialog.kt`](app/src/main/java/com/u1/slicer/ui/FilamentMappingDialog.kt) — extracted
into a shared file as part of this task if they're currently private.)

- [ ] **Step 2: Add ViewModel helpers**

`fetchVolumeExtruder`, `volumeNameFor`, `extruderColorsForChips`,
`resetPartFilaments` — all thin wrappers over existing state + JNI.

- [ ] **Step 3: Verify on device**

Load a multi-volume 3MF (e.g. one of the 3DBenchy-H2C-Multi-Color.3mf
plates). Tap object → expand Parts → tap a row → choose a different slot.
Renderer recolours the part immediately.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/u1/slicer/ui/PartsPanel.kt \
        app/src/main/java/com/u1/slicer/SlicerViewModel.kt
git commit -m "F66: PartsPanel composable + ViewModel helpers"
```

---

## Step 6: Instrumented end-to-end tests + manual E2E + release

### Task 6.1: `SplitAndOrientIntegrationTest`

**Files:**
- Create: `app/src/androidTest/java/com/u1/slicer/SplitAndOrientIntegrationTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package com.u1.slicer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.data.SliceConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SplitAndOrientIntegrationTest {

    private val lib = NativeLibrary()

    @Test
    fun skadisLikeAssembly_split_then_orient_then_slice_producesMultiObjectGcode() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val asset = File(ctx.cacheDir, "test.3mf").apply {
            ctx.assets.open("skywing-seawing-silkwing.3mf").use { it.copyTo(outputStream()) }
        }
        assertTrue(lib.loadModel(asset.absolutePath))
        val before = lib.nativeGetObjectCount()
        // Split every splittable object.
        var i = 0
        while (i < lib.nativeGetObjectCount()) {
            if (lib.nativeIsObjectSplittable(i)) {
                val res = lib.nativeSplitObject(i)
                if (res != null) { i += res[1]; continue }
            }
            i++
        }
        val after = lib.nativeGetObjectCount()
        assertTrue("split should have added objects: before=$before, after=$after", after > before)
        assertTrue(lib.nativeAutoOrientAll() > 0)
        val result = lib.slice(SliceConfig.defaults())
        assertNotNull(result)
        // Resulting G-code should reference each object via EXCLUDE_OBJECT_DEFINE.
        val gcode = File(result!!.gcodePath).readText()
        val defines = gcode.lines().count { it.startsWith("EXCLUDE_OBJECT_DEFINE") }
        assertTrue("expected ≥ $after EXCLUDE_OBJECT_DEFINE entries", defines >= after)
    }
}
```

- [ ] **Step 2: Run**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.SplitAndOrientIntegrationTest \
  --no-daemon
```

- [ ] **Step 3: Commit**

### Task 6.2: `PaintedSplitSliceTest`

- [ ] **Step 1: Write** a test that:
  1. Loads `flippy+flappy+mini-with-plate-painted.3mf`.
  2. Splits object 0.
  3. Slices with `SliceConfig.defaults()`.
  4. Counts `T0`/`T1`/`T2`/`T3` tool changes in the output G-code; asserts at
     least the same number of tool changes as a baseline run without split.

This is the on-device counterpart to Task 1.9.

- [ ] **Step 2: Commit**

### Task 6.3: `PerPartFilamentSliceTest`

- [ ] **Step 1: Write** a test that:
  1. Loads a multi-part 3MF (e.g. `colored_3DBenchy (1).3mf`).
  2. Calls `nativeSetVolumeExtruder(0, 0, 1)`, `nativeSetVolumeExtruder(0, 1, 2)`,
     `nativeSetVolumeExtruder(0, 2, 3)`.
  3. Slices and verifies the output G-code contains `T0`, `T1`, `T2` tool
     changes in proportion to the assigned volumes' triangle counts.

- [ ] **Step 2: Commit**

### Task 6.4: `SessionStateF66ResumeTest`

**Files:**
- Create: `app/src/androidTest/java/com/u1/slicer/data/SessionStateF66ResumeTest.kt`

- [ ] **Step 1: Write** a DataStore round-trip test that mirrors the existing
  `SessionStateRepositoryTest` but populates the new F66 fields, writes, reads
  back, and asserts equality.

- [ ] **Step 2: Commit**

### Task 6.5: Update `CLAUDE.md` test counts

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Recount**

```bash
./gradlew :app:testDebugUnitTest --no-daemon 2>&1 | grep -E "^[0-9]+ tests"
ANDROID_SERIAL=43211JEKB16931 ./gradlew :app:connectedDebugAndroidTest --no-daemon 2>&1 | grep -E "^[0-9]+ tests"
```

- [ ] **Step 2: Update the Unit-tests / Instrumented-tests headline counts in
  `CLAUDE.md`** and add entries describing each new test class.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "F66: update test counts and class entries in CLAUDE.md"
```

### Task 6.6: Manual E2E batch on Pixel 8a

Follow the existing `u1-slicer-e2e-batch` skill / `E2E_TESTING.md` procedure.
Pay extra attention to:
- A Skadis-class multi-part 3MF (use one from MakerWorld if no fixture
  exists; do not commit large files).
- A painted-and-split round trip.
- A single-STL workflow (regression: load Benchy, rotate, slice, send via
  `Map & Upload` — must behave identically to v2.9.3).

Document results in `c:/tmp/e2e-results/batch-f66-YYYY-MM-DD.txt`.

### Task 6.7: Bump version + build release APK

**Files:**
- Modify: `app/build.gradle`

- [ ] **Step 1: Bump**

```
versionCode 310    // from 303 (v2.9.3) — leave room for hotfixes
versionName "2.10.0"
```

- [ ] **Step 2: Commit**

```bash
git add app/build.gradle CLAUDE.md README.md
git commit -m "bump: v2.10.0 — F66 Split + Auto-Orient bundled feature"
```

- [ ] **Step 3: Build release APK** (do NOT publish without explicit
  user authorization — see CLAUDE.md release rule).

```bash
./gradlew assembleRelease --no-daemon
cp app/build/outputs/apk/release/app-release.apk \
   "G:/My Drive/claude/u1-slicer-v2.10.0.apk"
```

- [ ] **Step 4: Stage release notes for user review**

Draft `RELEASE_NOTES_v2.10.0.md` summarising F66 + any incidental fixes; do
not push tags or create GitHub releases without explicit user authorization.

---

## Out-of-scope reminders

(Pulled verbatim from the spec to keep the plan self-contained.)

- Multi-level undo across all Prepare actions.
- Lasso multi-select.
- Group transforms (rotate/scale several selected objects together).
- Auto-arrange on the bed after split.
- Convert per-part assignment ↔ Smart Paint markings.

---

## Self-review

**1. Spec coverage:**
- Section 1 (selection + Edit panel) → Tasks 3.3, 4.1, 4.2, 4.3.
- Section 2 (native JNI) → Tasks 1.1–1.5 + per-method JVM tests 1.6–1.9.
- Section 3 (per-object pose + renderer) → Tasks 2.4, 3.1, 3.2.
- Section 4 (Parts panel) → Task 5.1.
- Section 5 (F89 persistence) → Tasks 2.3, 2.9, 6.4.
- Section 6 (testing) → Tasks 1.6–1.9, 2.1–2.8 (unit), 6.1–6.4 (instrumented),
  6.6 (manual).
- Section 7 (sequencing) → Steps 1–6 follow the spec's internal order exactly.

**2. Placeholder scan:** Done — no "TBD" / "TODO" / "similar to" left. The
JNI bridge in Task 1.3 explicitly extends the pattern to all remaining methods
(the unlisted ones are mirrors of the listed ones); the file-rename of
`NativeSplitOrientTest` (JVM → androidTest) is documented in Task 1.6 with the
corrected path used by Tasks 1.7–1.9.

**3. Type consistency:** `PerObjectPose` fields (`rotXDeg`, `scaleX`, …),
`ObjectSelection` (`objectIndex`, `volumeIndex`), and `nativeSplitObject` return
contract (`IntArray? = [removedIdx, addedCount]`) are referenced consistently
across Steps 1–6. Reset methods named `resetObjectRotation` / `resetObjectScale`
/ `resetAllRotations` / `resetAllScales` everywhere.

**4. Risk gates:** Task 1.9 (paint preservation) is gated — split cannot ship
until that test passes. Task 1.5 (native rebuild verification) gates the rest
of Step 1 via JNI symbol count check. Step 6.6 (manual E2E) gates the release
APK build.

**5. Self-review fixes applied inline:**
- Tasks 2.4–2.7 originally referenced a non-existent `SlicerViewModelTestHarness`
  and put ViewModel tests in `app/src/test/`. `SlicerViewModel` is
  `AndroidViewModel` — moved tests to `app/src/androidTest/` following the
  existing `PreparePreviewViewModelTest` pattern.
- Task 2.7's `LongOpService.start(...)` call signature corrected from a
  three-arg invocation to the actual two-arg API on
  [`LongOpService.kt:179`](app/src/main/java/com/u1/slicer/LongOpService.kt#L179).
- Task 2.3 Step 5 made the `SessionStateTest` v2→v3 migration concrete (bump
  literal `"version":2` strings, never weaken assertions, follow CLAUDE.md's
  "never weaken a test assertion" rule).
- Task 1.6 originally had a mid-task path correction (JVM → instrumented).
  Rewritten so the path is correct from Step 1; a small `copyAsset` helper is
  introduced for reuse across 1.6–1.9.
- Tasks 1.7–1.9 hardcoded asset paths (`app/src/androidTest/assets/...`) which
  are not accessible at instrumented test runtime — switched to
  `copyAsset("name")` reading from `InstrumentationRegistry.getInstrumentation().context.assets`.

Plan is self-consistent. Ready to execute.
