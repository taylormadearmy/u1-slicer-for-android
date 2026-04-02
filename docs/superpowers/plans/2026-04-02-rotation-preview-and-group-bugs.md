# Rotation Bug Fixes — Preview Update & Group Rotation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix two rotation bugs from v1.5.28: (1) the Prepare preview not updating live when rotation sliders change, and (2) multi-object plates rotating each object around its own origin instead of the group centroid.

**Architecture:** Bug 1 is Kotlin-only — add a `LaunchedEffect(modelRotation)` in `InlineModelPreview` that re-fetches the native preview mesh after each rotation change. Bug 2 is C++ — rewrite `setModelRotation` in `sapil_arrange.cpp` to cache base instance positions and apply group rotation around the combined bounding box centre (same pattern as `setModelScale`). A new `getInstanceOffsets()` JNI method enables regression testing of the multi-object behaviour without slicing.

**Tech Stack:** Kotlin/Jetpack Compose, Coroutines (Dispatchers.IO), C++17 (OrcaSlicer/Slic3r), Android JNI, JUnit4 (instrumented), JVM unit tests

---

## File Map

| File | Change |
|------|--------|
| `app/src/main/java/com/u1/slicer/MainActivity.kt` | Add `LaunchedEffect(modelRotation)` inside `InlineModelPreview` after line 2129 |
| `app/src/main/cpp/src/sapil_arrange.cpp` | Rewrite `setModelRotation`; add `g_rotation_base_positions` logic |
| `app/src/main/cpp/src/sapil_model.cpp` | Add `g_rotation_base_positions` global; clear it in `clearModel()` |
| `app/src/main/cpp/include/sapil.h` | Add `getInstanceOffsets()` declaration to `SlicerEngine` |
| `app/src/main/cpp/src/slicer_wrapper.cpp` | Add JNI wrapper for `getInstanceOffsets` |
| `app/src/main/java/com/u1/slicer/NativeLibrary.kt` | Add `external fun getInstanceOffsets(): FloatArray` |
| `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so` | Rebuilt after C++ changes |
| `app/src/test/java/com/u1/slicer/PreparePreviewPlacementTest.kt` | Add Bug 1 regression test |
| `app/src/androidTest/java/com/u1/slicer/slicing/SlicingIntegrationTest.kt` | Add Bug 2 regression test |

---

## Task 1: Add `getInstanceOffsets` JNI method (test scaffolding for Bug 2)

This lightweight JNI method returns a flat `FloatArray` of `[x0, y0, x1, y1, …]` — one X/Y pair per instance, in object/instance enumeration order. Used only in instrumented tests to assert that multi-object rotation preserves inter-object distances.

**Files:**
- Modify: `app/src/main/cpp/include/sapil.h` — add declaration inside `SlicerEngine`
- Modify: `app/src/main/cpp/src/sapil_arrange.cpp` — add implementation
- Modify: `app/src/main/cpp/src/slicer_wrapper.cpp` — add JNI wrapper
- Modify: `app/src/main/java/com/u1/slicer/NativeLibrary.kt` — add `external fun`

- [ ] **Step 1: Add declaration to sapil.h**

In `app/src/main/cpp/include/sapil.h`, after the `setModelRotation` declaration (line 154), add:

```cpp
    // Returns flat [x0, y0, x1, y1, ...] world-space XY offsets for all instances
    // (in object/instance enumeration order). Used by instrumented tests.
    std::vector<float> getInstanceOffsets() const;
```

- [ ] **Step 2: Add implementation to sapil_arrange.cpp**

At the end of `app/src/main/cpp/src/sapil_arrange.cpp`, before the closing `} // namespace sapil`, add:

```cpp
std::vector<float> SlicerEngine::getInstanceOffsets() const {
    std::vector<float> result;
    if (!isModelLoaded()) return result;
    const Slic3r::Model& model = getGlobalModel();
    for (const auto* obj : model.objects) {
        for (const auto* inst : obj->instances) {
            const Slic3r::Vec3d off = inst->get_offset();
            result.push_back(static_cast<float>(off.x()));
            result.push_back(static_cast<float>(off.y()));
        }
    }
    return result;
}
```

- [ ] **Step 3: Add JNI wrapper to slicer_wrapper.cpp**

In `app/src/main/cpp/src/slicer_wrapper.cpp`, after the `setModelRotation` wrapper (after line ~174), add:

```cpp
JNIEXPORT jfloatArray JNICALL
Java_com_u1_slicer_NativeLibrary_getInstanceOffsets(
        JNIEnv* env, jobject) {
    if (!g_engine) return env->NewFloatArray(0);
    auto offsets = g_engine->getInstanceOffsets();
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(offsets.size()));
    if (!offsets.empty()) {
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(offsets.size()), offsets.data());
    }
    return result;
}
```

- [ ] **Step 4: Add external fun to NativeLibrary.kt**

In `app/src/main/java/com/u1/slicer/NativeLibrary.kt`, after the `setModelRotation` declaration, add:

```kotlin
    // Returns flat [x0, y0, x1, y1, ...] world-space XY offsets for all instances.
    // Used by instrumented tests only.
    external fun getInstanceOffsets(): FloatArray
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/cpp/include/sapil.h \
        app/src/main/cpp/src/sapil_arrange.cpp \
        app/src/main/cpp/src/slicer_wrapper.cpp \
        app/src/main/java/com/u1/slicer/NativeLibrary.kt
git commit -m "feat(test): add getInstanceOffsets JNI method for rotation regression tests"
```

---

## Task 2: Write failing instrumented test for Bug 2 (multi-object group rotation)

**Files:**
- Modify: `app/src/androidTest/java/com/u1/slicer/slicing/SlicingIntegrationTest.kt`

- [ ] **Step 1: Add the failing test**

In `app/src/androidTest/java/com/u1/slicer/slicing/SlicingIntegrationTest.kt`, add this test after the existing `tetrahedron_stl_slicesSuccessfully_withRotation` test (after line ~130):

```kotlin
@Test
fun setModelRotation_multiObject_preservesInterObjectDistances() {
    // sydney_buttons.3mf has 4 objects (button clusters) spread across the bed.
    // Rotating as a group must preserve the distances between objects — each
    // object must orbit the group centre, not spin around its own local origin.
    val file = asset("sydney_buttons.3mf")
    assertTrue("sydney_buttons.3mf should load", lib.loadModel(file.absolutePath))

    val beforeOffsets = lib.getInstanceOffsets()
    // Need at least 2 objects (4 floats) to test inter-object distance
    assertTrue("Expected at least 2 objects", beforeOffsets.size >= 4)

    assertTrue("setModelRotation should succeed", lib.setModelRotation(0f, 0f, 45f))

    val afterOffsets = lib.getInstanceOffsets()
    assertEquals("Instance count must not change after rotation",
        beforeOffsets.size, afterOffsets.size)

    val objectCount = beforeOffsets.size / 2

    // For every pair of objects, verify their distance is preserved within 0.5 mm
    for (i in 0 until objectCount) {
        for (j in i + 1 until objectCount) {
            val beforeDx = beforeOffsets[i * 2] - beforeOffsets[j * 2]
            val beforeDy = beforeOffsets[i * 2 + 1] - beforeOffsets[j * 2 + 1]
            val beforeDist = Math.sqrt((beforeDx * beforeDx + beforeDy * beforeDy).toDouble()).toFloat()

            val afterDx = afterOffsets[i * 2] - afterOffsets[j * 2]
            val afterDy = afterOffsets[i * 2 + 1] - afterOffsets[j * 2 + 1]
            val afterDist = Math.sqrt((afterDx * afterDx + afterDy * afterDy).toDouble()).toFloat()

            assertEquals(
                "Distance between objects $i and $j should be preserved after rotation",
                beforeDist, afterDist, 0.5f
            )
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails (current behaviour)**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest \
  --no-daemon \
  -Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.slicing.SlicingIntegrationTest#setModelRotation_multiObject_preservesInterObjectDistances \
  2>&1 | tail -20
```

Expected: **FAIL** — the test will fail because `getInstanceOffsets` is not yet in the `.so` (UnsatisfiedLinkError) or because the distances are not preserved. Either way, this confirms the test is wired correctly and the bug is real.

- [ ] **Step 3: Commit the failing test**

```bash
git add app/src/androidTest/java/com/u1/slicer/slicing/SlicingIntegrationTest.kt
git commit -m "test(f57): add failing test for multi-object group rotation distance preservation"
```

---

## Task 3: Fix Bug 2 in C++ — group rotation around combined bbox centre

**Files:**
- Modify: `app/src/main/cpp/src/sapil_model.cpp` — add `g_rotation_base_positions` global and clear it in `clearModel()`
- Modify: `app/src/main/cpp/src/sapil_arrange.cpp` — rewrite `setModelRotation`

- [ ] **Step 1: Add global and extern declaration**

In `app/src/main/cpp/src/sapil_model.cpp`, after line 38 (`static bool g_preview_mesh_valid = false;`), add:

```cpp
// Base instance positions captured on first setModelRotation call.
// Cleared on model load/clear. Used to avoid positional drift across repeated
// slider calls (each call rotates from the original positions, not current ones).
static std::vector<Slic3r::Vec3d> g_rotation_base_positions;
```

In `clearModel()` (around line 503), after `g_model_preview_extruders.clear();`, add:

```cpp
    g_rotation_base_positions.clear();
```

- [ ] **Step 2: Add extern declaration in sapil_arrange.cpp**

In `app/src/main/cpp/src/sapil_arrange.cpp`, in the forward declarations block (lines 14-16), add:

```cpp
extern std::vector<Slic3r::Vec3d>& getRotationBasePositions();
```

And in `app/src/main/cpp/src/sapil_model.cpp`, add this accessor after `clearModel()`:

```cpp
std::vector<Slic3r::Vec3d>& getRotationBasePositions() {
    return g_rotation_base_positions;
}
```

- [ ] **Step 3: Rewrite setModelRotation in sapil_arrange.cpp**

Replace the entire `setModelRotation` function (lines 141-160) with:

```cpp
bool SlicerEngine::setModelRotation(float rx_deg, float ry_deg, float rz_deg) {
    if (!isModelLoaded()) {
        SAPIL_LOGE("setModelRotation: no model loaded");
        return false;
    }
    Slic3r::Model& model = getGlobalModel();
    std::vector<Slic3r::Vec3d>& basePositions = getRotationBasePositions();

    // Flatten all instances into a list (same order every call: object then instance)
    std::vector<Slic3r::ModelInstance*> allInsts;
    for (auto* obj : model.objects) {
        for (auto* inst : obj->instances) {
            allInsts.push_back(inst);
        }
    }

    // On first call after model load, snapshot current offsets as base positions.
    if (basePositions.empty()) {
        for (auto* inst : allInsts) {
            basePositions.push_back(inst->get_offset());
        }
    }

    const double deg2rad = M_PI / 180.0;
    const double rx = static_cast<double>(rx_deg) * deg2rad;
    const double ry = static_cast<double>(ry_deg) * deg2rad;
    const double rz = static_cast<double>(rz_deg) * deg2rad;

    // Compute group pivot: XY centre of the base-position bounding box.
    // Z pivot = 0 (rotate around the bed plane, not the mesh centroid).
    double minX = std::numeric_limits<double>::max();
    double minY = std::numeric_limits<double>::max();
    double maxX = std::numeric_limits<double>::lowest();
    double maxY = std::numeric_limits<double>::lowest();
    for (const auto& b : basePositions) {
        minX = std::min(minX, b.x()); maxX = std::max(maxX, b.x());
        minY = std::min(minY, b.y()); maxY = std::max(maxY, b.y());
    }
    const Slic3r::Vec3d pivot(
        (minX + maxX) * 0.5,
        (minY + maxY) * 0.5,
        0.0
    );

    // Build ZYX rotation matrix (OrcaSlicer convention: Z applied last → first in matrix terms).
    // R = Rz * Ry * Rx  (applied to column vectors)
    const double cx = std::cos(rx), sx = std::sin(rx);
    const double cy = std::cos(ry), sy = std::sin(ry);
    const double cz = std::cos(rz), sz = std::sin(rz);

    // Row-major 3x3: R[row][col]
    double R[3][3];
    R[0][0] = cy * cz;
    R[0][1] = cz * sx * sy - cx * sz;
    R[0][2] = cx * cz * sy + sx * sz;
    R[1][0] = cy * sz;
    R[1][1] = cx * cz + sx * sy * sz;
    R[1][2] = cx * sy * sz - cz * sx;
    R[2][0] = -sy;
    R[2][1] = cy * sx;
    R[2][2] = cx * cy;

    // Apply: new_offset = pivot + R * (base - pivot)
    for (size_t i = 0; i < allInsts.size(); ++i) {
        const Slic3r::Vec3d b = basePositions[i] - pivot;
        const Slic3r::Vec3d rotated(
            R[0][0] * b.x() + R[0][1] * b.y() + R[0][2] * b.z(),
            R[1][0] * b.x() + R[1][1] * b.y() + R[1][2] * b.z(),
            R[2][0] * b.x() + R[2][1] * b.y() + R[2][2] * b.z()
        );
        allInsts[i]->set_rotation(Slic3r::Vec3d(rx, ry, rz));
        allInsts[i]->set_offset(pivot + rotated);
    }

    invalidatePreviewMeshCache();
    SAPIL_LOGI("Set model rotation: %.1f, %.1f, %.1f deg (pivot: %.1f, %.1f)",
        rx_deg, ry_deg, rz_deg, pivot.x(), pivot.y());
    return true;
}
```

- [ ] **Step 4: Also add getInstanceOffsets implementation (from Task 1 Step 2) and the accessor**

Confirm `getInstanceOffsets` implementation is in the file from Task 1. Also confirm `getRotationBasePositions` accessor was added to `sapil_model.cpp` in Step 2 above.

- [ ] **Step 5: Commit the C++ changes**

```bash
git add app/src/main/cpp/src/sapil_arrange.cpp \
        app/src/main/cpp/src/sapil_model.cpp \
        app/src/main/cpp/include/sapil.h \
        app/src/main/cpp/src/slicer_wrapper.cpp
git commit -m "fix(f57): group rotation around combined bbox centre; cache base positions to prevent drift"
```

---

## Task 4: Rebuild the native .so

**Files:**
- Modify: `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so`

- [ ] **Step 1: Enable CMake in build.gradle**

In `app/build.gradle`, uncomment the `externalNativeBuild` block. It looks like:

```groovy
// externalNativeBuild {
//     cmake {
//         path "src/main/cpp/CMakeLists.txt"
//         version "3.22.1"
//     }
// }
```

Uncomment it (remove the `//` prefixes).

- [ ] **Step 2: Configure the build (generates build.ninja)**

```bash
cd c:/Users/kevin/projects/u1-slicer-orca
./gradlew assembleDebug --no-daemon 2>&1 | grep -E "BUILD|ninja|cmake|error" | tail -10
```

This will fail to compile (the existing `.so` is pre-built) but it configures CMake and writes `build.ninja`. The configure step will succeed even if the full build fails.

- [ ] **Step 3: Re-disable CMake in build.gradle**

Revert the `externalNativeBuild` block back to commented-out. This prevents accidental full rebuilds.

- [ ] **Step 4: Run ninja**

Find the RelWithDebInfo build dir (check `app/.cxx/RelWithDebInfo/` — use the hash dir that has `build.ninja`):

```bash
cd "c:/Users/kevin/projects/u1-slicer-orca/app/.cxx/RelWithDebInfo/20692i6u/arm64-v8a"
ninja -j1 libprusaslicer-jni.so 2>&1 | tail -5
```

Expected: `[N/N] Linking CXX shared library ...libprusaslicer-jni.so`

- [ ] **Step 5: Strip the .so**

```bash
"/c/Users/kevin/AppData/Local/Android/Sdk/ndk/25.1.8937393/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe" \
  --strip-unneeded \
  "c:/Users/kevin/projects/u1-slicer-orca/app/.cxx/RelWithDebInfo/20692i6u/arm64-v8a/libprusaslicer-jni.so"
```

- [ ] **Step 6: Verify stripped size is ~19MB**

```bash
ls -lh "c:/Users/kevin/projects/u1-slicer-orca/app/.cxx/RelWithDebInfo/20692i6u/arm64-v8a/libprusaslicer-jni.so"
```

Expected: approximately 19-20 MB. If it's >100 MB, stripping did not run — repeat Step 5.

- [ ] **Step 7: Copy to jniLibs**

```bash
cp "c:/Users/kevin/projects/u1-slicer-orca/app/.cxx/RelWithDebInfo/20692i6u/arm64-v8a/libprusaslicer-jni.so" \
   "c:/Users/kevin/projects/u1-slicer-orca/app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so"
```

- [ ] **Step 8: Commit the rebuilt .so**

```bash
git add app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so
git commit -m "build(f57): rebuild .so with group rotation fix and getInstanceOffsets"
```

---

## Task 5: Verify Bug 2 fix passes

**Files:**
- Test: `app/src/androidTest/java/com/u1/slicer/slicing/SlicingIntegrationTest.kt`

- [ ] **Step 1: Clean and install**

```bash
cd c:/Users/kevin/projects/u1-slicer-orca
./gradlew clean installDebug --no-daemon 2>&1 | tail -5
```

- [ ] **Step 2: Run the multi-object rotation test**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest \
  --no-daemon \
  -Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.slicing.SlicingIntegrationTest#setModelRotation_multiObject_preservesInterObjectDistances \
  2>&1 | tail -20
```

Expected: **PASS**

- [ ] **Step 3: Also run the existing single-object rotation test to confirm no regression**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest \
  --no-daemon \
  -Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.slicing.SlicingIntegrationTest#tetrahedron_stl_slicesSuccessfully_withRotation \
  2>&1 | tail -10
```

Expected: **PASS**

---

## Task 6: Fix Bug 1 — preview mesh refresh on rotation change

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/MainActivity.kt`

- [ ] **Step 1: Add the rotation-reactive LaunchedEffect**

In `app/src/main/java/com/u1/slicer/MainActivity.kt`, after the model scale effect (after line 2129):

```kotlin
    // Re-fetch preview mesh when rotation changes (non-painted 3MF and STL only).
    // Painted/SEMM models use ThreeMfMeshParser which is not rotation-aware.
    LaunchedEffect(modelRotation) {
        val rot = modelRotation
        if (rot.x == 0f && rot.y == 0f && rot.z == 0f) return@LaunchedEffect
        if (modelFilePath.endsWith(".3mf", ignoreCase = true) &&
            hasPaintData && (colorMapping != null || extruderMap != null)) return@LaunchedEffect

        val newMesh = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                NativeLibrary().setModelRotation(rot.x, rot.y, rot.z)
                NativeLibrary().getPreparePreviewMesh()?.toMeshData()
            } catch (_: Throwable) {
                null
            }
        }
        if (newMesh != null) {
            mesh = newMesh
            lastSetMesh = null  // force setMesh() on the GL thread
        }
    }
```

Note: `modelFilePath`, `hasPaintData`, `colorMapping`, `extruderMap`, `mesh`, and `lastSetMesh` are all already in scope inside `InlineModelPreview`. The `NativeLibrary()` constructor is cheap (just loads the already-loaded library).

- [ ] **Step 2: Verify it compiles**

```bash
cd c:/Users/kevin/projects/u1-slicer-orca
./gradlew assembleDebug --no-daemon 2>&1 | grep -E "BUILD|error:" | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/u1/slicer/MainActivity.kt
git commit -m "fix(f57): refresh prepare preview mesh when rotation changes"
```

---

## Task 7: Write and run JVM unit test for Bug 1

**Files:**
- Modify: `app/src/test/java/com/u1/slicer/PreparePreviewPlacementTest.kt`

The existing test file (`PreparePreviewPlacementTest.kt`) tests pure logic functions (`buildPreparePreviewPlacementConfig`, `resolvePreparePreviewModelInfo`) extracted from the composable. The `InlineModelPreview` composable itself calls `NativeLibrary()` directly and is not easily unit-testable without the native library.

The regression test for Bug 1 is therefore best expressed as a targeted instrumented test — simpler and more reliable than mocking a composable's native calls in a JVM test.

- [ ] **Step 1: Add the instrumented regression test to SlicingIntegrationTest.kt**

In `app/src/androidTest/java/com/u1/slicer/slicing/SlicingIntegrationTest.kt`, add after the multi-object distance test:

```kotlin
@Test
fun setModelRotation_invalidatesPreviewMesh_soSubsequentFetchReturnsRotatedGeometry() {
    // Load tetrahedron, get unrotated mesh, rotate, get new mesh.
    // The bounding boxes should differ — confirming the preview mesh reflects rotation.
    val file = asset("tetrahedron.stl")
    assertTrue("Model should load", lib.loadModel(file.absolutePath))

    val meshBefore = lib.getPreparePreviewMesh()
    assertNotNull("Initial preview mesh should not be null", meshBefore)

    assertTrue("setModelRotation should succeed", lib.setModelRotation(90f, 0f, 0f))

    val meshAfter = lib.getPreparePreviewMesh()
    assertNotNull("Post-rotation preview mesh should not be null", meshAfter)

    // A 90° X rotation swaps the Y and Z extents of the bounding box.
    // Calculate Z extents: max(z) - min(z)
    fun zExtent(mesh: com.u1.slicer.viewer.NativePreviewMesh): Float {
        var minZ = Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        val positions = mesh.trianglePositions
        var i = 2
        while (i < positions.size) {
            val z = positions[i]
            if (z < minZ) minZ = z
            if (z > maxZ) maxZ = z
            i += 3
        }
        return if (maxZ > minZ) maxZ - minZ else 0f
    }

    val zBefore = zExtent(meshBefore!!)
    val zAfter = zExtent(meshAfter!!)

    // After 90° X rotation the tetrahedron's Z extent must differ from before
    // by more than 1mm — confirms the mesh was regenerated with rotation applied.
    assertTrue(
        "Z extent should change after 90° X rotation (before=$zBefore after=$zAfter)",
        Math.abs(zAfter - zBefore) > 1.0f
    )
}
```

- [ ] **Step 2: Run the new preview mesh test**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest \
  --no-daemon \
  -Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.slicing.SlicingIntegrationTest#setModelRotation_invalidatesPreviewMesh_soSubsequentFetchReturnsRotatedGeometry \
  2>&1 | tail -20
```

Expected: **PASS** (this tests the native invalidation which already works; the Kotlin LaunchedEffect behaviour is tested by manual E2E)

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/java/com/u1/slicer/slicing/SlicingIntegrationTest.kt \
        app/src/test/java/com/u1/slicer/PreparePreviewPlacementTest.kt
git commit -m "test(f57): add regression tests for preview mesh refresh and group rotation"
```

---

## Task 8: Run full test suite and verify no regressions

- [ ] **Step 1: Run all JVM unit tests**

```bash
cd c:/Users/kevin/projects/u1-slicer-orca
./gradlew testDebugUnitTest --no-daemon 2>&1 | grep -E "tests|failures|errors|BUILD" | tail -5
```

Expected: all 612 tests pass, 0 failures.

- [ ] **Step 2: Run full instrumented suite**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest --no-daemon 2>&1 | tail -10
```

Expected: all 142 tests pass (140 existing + 2 new), 0 failures.

- [ ] **Step 3: Manual E2E smoke — verify preview updates live**

Install the debug build: `./gradlew installDebug --no-daemon`

On device:
1. Open a multi-object 3MF (e.g. sydney_buttons.3mf or any multi-colour file).
2. Go to Prepare screen → Scale & Copies card → Rotation tab.
3. Move the Z slider. The 3D preview should rotate visibly **without** needing to slice first.
4. Confirm the objects move as a group without overlapping.

- [ ] **Step 4: Version bump and release commit**

```bash
# In app/build.gradle, increment versionCode and versionName (e.g. 194 → 195, 1.5.28 → 1.5.29)
git add app/build.gradle
git commit -m "bump: v1.5.29 - fix rotation preview refresh and multi-object group rotation"
```

---

## Notes

- **`g_rotation_base_positions` is NOT cleared on reset to (0,0,0).** Calling `setModelRotation(0,0,0)` correctly restores original positions because it applies the identity rotation to the cached base positions. This is intentional.
- **Single-object models:** The fix is backward-compatible. With one instance, the pivot equals the base position, so `R * (b - pivot) = R * (0,0,0) = (0,0,0)` and `new_offset = pivot` — correct, the object stays centred and just rotates.
- **The `ninja` build dir hash `20692i6u`** is the current RelWithDebInfo dir. If it changes (Gradle re-configures), run `ls app/.cxx/RelWithDebInfo/` to find the new hash and use it in Task 4 Step 4.
- **Paging file note:** If `ninja -j1` fails with OOM/paging-file errors, close other memory-heavy apps first. The ninja build peak is ~1.5 GB during LTO linking.
