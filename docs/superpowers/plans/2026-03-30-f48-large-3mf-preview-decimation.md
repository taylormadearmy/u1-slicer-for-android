# F48: Large 3MF Preview Decimation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a full 3D Prepare preview for all practical models by decimating to ≤100K triangles in C++ before crossing the JNI bridge, eliminating the 2D bed-footprint fallback.

**Architecture:** Add a `maxTriangles` parameter to `getPreparePreviewMesh()` in C++ (`sapil_model.cpp`) and its JNI wrapper (`slicer_wrapper.cpp`). The C++ function computes a stride and exports every Nth triangle so the returned `NativePreviewMesh` is always ≤100K triangles. On the Kotlin side, raise `MAX_SAFE_TRIANGLES` to 50M so the `LargePreviewFallback` never fires in practice, and remove the `previewTooLarge` early-exit from the `LaunchedEffect`. Add the same stride cap to `ThreeMfMeshParser.buildMeshData()` as a secondary safety net.

**Tech Stack:** Kotlin, C++ (C++17), JNI, Android OpenGL ES 3.0, JUnit 4, AndroidJUnit4

---

## Files Changed

- **Modify:** `app/src/main/cpp/include/sapil.h` — add `maxTriangles` param to `getPreparePreviewMesh()`
- **Modify:** `app/src/main/cpp/src/sapil_model.cpp` — stride decimation in `appendItsPreviewMesh` / `getPreparePreviewMesh`
- **Modify:** `app/src/main/cpp/src/slicer_wrapper.cpp` — pass `maxTriangles` from JNI to engine
- **Modify:** `app/src/main/java/com/u1/slicer/NativeLibrary.kt` — add `maxTriangles` param to `external fun`
- **Modify:** `app/src/main/java/com/u1/slicer/viewer/NativePreviewMesh.kt` — raise `MAX_SAFE_TRIANGLES` to 50M, add `MAX_DECIMATED_TRIANGLES = 100_000`
- **Modify:** `app/src/main/java/com/u1/slicer/MainActivity.kt` — remove `previewTooLarge` early-exit from `LaunchedEffect`
- **Modify:** `app/src/main/java/com/u1/slicer/viewer/ThreeMfMeshParser.kt` — stride cap in `buildMeshData()`
- **Modify:** `app/src/test/java/com/u1/slicer/viewer/NativePreviewMeshTest.kt` — update budget tests, add decimation unit test
- **Modify:** `app/src/androidTest/java/com/u1/slicer/viewer/NativePreparePreviewTest.kt` — add large-model triangle cap assertion

---

## Task 1: Update C++ API — add `maxTriangles` parameter

**Files:**
- Modify: `app/src/main/cpp/include/sapil.h:131`
- Modify: `app/src/main/cpp/src/sapil_model.cpp:316-371`
- Modify: `app/src/main/cpp/src/slicer_wrapper.cpp:71-77`

- [ ] **Step 1: Update the header declaration**

In `app/src/main/cpp/include/sapil.h`, change line 131:

```cpp
// Before:
PreviewMesh getPreparePreviewMesh() const;

// After:
PreviewMesh getPreparePreviewMesh(int max_triangles = 100000) const;
```

- [ ] **Step 2: Add stride decimation to `appendItsPreviewMesh`**

In `app/src/main/cpp/src/sapil_model.cpp`, replace the `appendItsPreviewMesh` function signature and body (lines 247–297) with a version that accepts and applies a stride:

```cpp
static void appendItsPreviewMesh(
    PreviewMesh& out,
    const indexed_triangle_set& its,
    uint8_t extruder_index,
    int stride
) {
    bool logged_invalid_index = false;
    bool logged_invalid_vertex = false;
    int tri_counter = 0;
    for (const auto& tri : its.indices) {
        // Stride decimation: only emit every stride-th valid triangle
        if (tri_counter % stride != 0) {
            ++tri_counter;
            continue;
        }

        bool valid = true;
        for (int i = 0; i < 3; ++i) {
            const int vertex_index = tri[i];
            if (vertex_index < 0 || static_cast<size_t>(vertex_index) >= its.vertices.size()) {
                if (!logged_invalid_index) {
                    SAPIL_LOGW(
                        "preview triangle skipped: invalid vertex index %d (vertex count=%zu)",
                        vertex_index,
                        its.vertices.size()
                    );
                    logged_invalid_index = true;
                }
                valid = false;
                break;
            }
        }
        if (!valid) {
            ++tri_counter;
            continue;
        }

        const size_t start_size = out.triangle_positions.size();
        for (int i = 0; i < 3; ++i) {
            const auto& vertex = its.vertices[tri[i]];
            if (!std::isfinite(vertex.x()) || !std::isfinite(vertex.y()) || !std::isfinite(vertex.z())) {
                if (!logged_invalid_vertex) {
                    SAPIL_LOGW(
                        "preview triangle skipped: non-finite vertex [%.3f,%.3f,%.3f]",
                        vertex.x(), vertex.y(), vertex.z()
                    );
                    logged_invalid_vertex = true;
                }
                valid = false;
                break;
            }
            out.triangle_positions.push_back(static_cast<float>(vertex.x()));
            out.triangle_positions.push_back(static_cast<float>(vertex.y()));
            out.triangle_positions.push_back(static_cast<float>(vertex.z()));
        }
        if (!valid) {
            out.triangle_positions.resize(start_size);
            ++tri_counter;
            continue;
        }
        out.extruder_indices.push_back(extruder_index);
        ++tri_counter;
    }
}
```

- [ ] **Step 3: Update `getPreparePreviewMesh` to compute and pass stride**

In `app/src/main/cpp/src/sapil_model.cpp`, replace the `getPreparePreviewMesh` function signature (line 316) and add stride computation before the loop. The full function becomes:

```cpp
PreviewMesh SlicerEngine::getPreparePreviewMesh(int max_triangles) const {
    PreviewMesh out;
    if (!g_model_loaded) {
        return out;
    }

    // Count total triangles across all printable volumes to compute stride
    int total_tris = 0;
    for (const auto* object : g_model.objects) {
        if (object == nullptr || !object->printable) continue;
        if (object->instances.empty()) continue;
        for (const auto* volume : object->volumes) {
            if (volume == nullptr || !volume->is_model_part()) continue;
            if (!volume->mmu_segmentation_facets.empty()) {
                std::vector<indexed_triangle_set> facets_per_type;
                volume->mmu_segmentation_facets.get_facets(*volume, facets_per_type);
                for (const auto& its : facets_per_type) {
                    total_tris += static_cast<int>(its.indices.size());
                }
            } else {
                total_tris += static_cast<int>(volume->mesh().its.indices.size());
            }
        }
    }

    const int effective_max = (max_triangles > 0) ? max_triangles : 100000;
    const int stride = (total_tris > effective_max)
        ? ((total_tris + effective_max - 1) / effective_max)
        : 1;

    SAPIL_LOGI("getPreparePreviewMesh: total_tris=%d max=%d stride=%d", total_tris, effective_max, stride);

    size_t object_index = 0;
    for (const auto* object : g_model.objects) {
        if (object == nullptr || !object->printable) continue;
        if (object->instances.empty()) continue;
        const std::vector<int>* preview_extruders =
            object_index < g_model_preview_extruders.size() ? &g_model_preview_extruders[object_index] : nullptr;

        for (const auto* instance : object->instances) {
            if (instance == nullptr || !instance->printable) continue;
            const Slic3r::Transform3d instance_matrix = instance->get_matrix();
            size_t volume_index = 0;

            for (const auto* volume : object->volumes) {
                if (volume == nullptr || !volume->is_model_part()) continue;

                int fallback_extruder = volume->extruder_id();
                if (preview_extruders != nullptr && volume_index < preview_extruders->size() &&
                    (*preview_extruders)[volume_index] > 0) {
                    fallback_extruder = (*preview_extruders)[volume_index];
                }
                if (fallback_extruder <= 0) fallback_extruder = 1;
                const uint8_t fallback_index = static_cast<uint8_t>(std::max(0, fallback_extruder - 1));

                if (!volume->mmu_segmentation_facets.empty()) {
                    std::vector<indexed_triangle_set> facets_per_type;
                    volume->mmu_segmentation_facets.get_facets(*volume, facets_per_type);
                    for (size_t state_idx = 0; state_idx < facets_per_type.size(); ++state_idx) {
                        auto its = facets_per_type[state_idx];
                        if (its.indices.empty()) continue;
                        its_transform(its, volume->get_matrix(), true);
                        its_transform(its, instance_matrix, true);
                        const uint8_t extruder_index = state_idx == 0
                            ? fallback_index
                            : static_cast<uint8_t>(state_idx - 1);
                        appendItsPreviewMesh(out, its, extruder_index, stride);
                    }
                } else {
                    auto its = volume->mesh().its;
                    its_transform(its, volume->get_matrix(), true);
                    its_transform(its, instance_matrix, true);
                    appendItsPreviewMesh(out, its, fallback_index, stride);
                }
                ++volume_index;
            }
        }
        ++object_index;
    }

    compactPreviewIndices(out);
    return out;
}
```

- [ ] **Step 4: Update JNI wrapper to pass `maxTriangles`**

In `app/src/main/cpp/src/slicer_wrapper.cpp`, replace the `getPreparePreviewMesh` JNI function (lines 71–77):

```cpp
JNIEXPORT jobject JNICALL
Java_com_u1_slicer_NativeLibrary_getPreparePreviewMesh(JNIEnv* env, jobject, jint maxTriangles) {
    if (!g_engine) return nullptr;
    sapil::PreviewMesh mesh = g_engine->getPreparePreviewMesh(static_cast<int>(maxTriangles));
    if (mesh.extruder_indices.empty()) return nullptr;
    return sapil::previewMeshToJava(env, mesh);
}
```

- [ ] **Step 5: Commit C++ changes (source only — .so rebuild is a separate task)**

```bash
git add app/src/main/cpp/include/sapil.h \
        app/src/main/cpp/src/sapil_model.cpp \
        app/src/main/cpp/src/slicer_wrapper.cpp
git commit -m "feat(F48): add stride decimation to getPreparePreviewMesh (C++ source)"
```

---

## Task 2: Update Kotlin JNI declaration and constants

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/NativeLibrary.kt`
- Modify: `app/src/main/java/com/u1/slicer/viewer/NativePreviewMesh.kt`

- [ ] **Step 1: Update `NativeLibrary.kt` external declaration**

Find the line (currently line 36):
```kotlin
external fun getPreparePreviewMesh(): NativePreviewMesh?
```
Change to:
```kotlin
external fun getPreparePreviewMesh(maxTriangles: Int = NativePreviewMesh.MAX_DECIMATED_TRIANGLES): NativePreviewMesh?
```

- [ ] **Step 2: Update constants in `NativePreviewMesh.kt`**

In the `companion object` of `NativePreviewMesh` (lines 108–119), replace:

```kotlin
companion object {
    // FloatArray positions + ByteArray extruder ids + interleaved GL mesh buffer.
    private const val ESTIMATED_BYTES_PER_TRIANGLE = 157L
    const val MAX_SAFE_TRIANGLES = 1_200_000
    const val MAX_SAFE_PREVIEW_BYTES = 188L * 1024L * 1024L

    fun wouldExceedSafePreviewBudget(triangleCount: Int): Boolean {
        if (triangleCount <= 0) return false
        if (triangleCount > MAX_SAFE_TRIANGLES) return true
        return triangleCount.toLong() * ESTIMATED_BYTES_PER_TRIANGLE > MAX_SAFE_PREVIEW_BYTES
    }
}
```

With:

```kotlin
companion object {
    /** Target triangle count passed to native decimation. At 100K, GL buffer ≈ 12MB. */
    const val MAX_DECIMATED_TRIANGLES = 100_000

    // Safety-net threshold for LargePreviewFallback — effectively unreachable after decimation.
    // Kept at a high value (not deleted) to preserve B18 regression test coverage.
    private const val ESTIMATED_BYTES_PER_TRIANGLE = 157L
    const val MAX_SAFE_TRIANGLES = 50_000_000
    const val MAX_SAFE_PREVIEW_BYTES = 188L * 1024L * 1024L

    fun wouldExceedSafePreviewBudget(triangleCount: Int): Boolean {
        if (triangleCount <= 0) return false
        if (triangleCount > MAX_SAFE_TRIANGLES) return true
        return triangleCount.toLong() * ESTIMATED_BYTES_PER_TRIANGLE > MAX_SAFE_PREVIEW_BYTES
    }
}
```

- [ ] **Step 3: Commit Kotlin constant changes**

```bash
git add app/src/main/java/com/u1/slicer/NativeLibrary.kt \
        app/src/main/java/com/u1/slicer/viewer/NativePreviewMesh.kt
git commit -m "feat(F48): add MAX_DECIMATED_TRIANGLES, raise safety-net threshold to 50M"
```

---

## Task 3: Remove `previewTooLarge` early-exit from Prepare screen

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/MainActivity.kt`

The `LaunchedEffect` at line ~2026 has this early-exit when `previewTooLarge` is true:

```kotlin
LaunchedEffect(modelFilePath, extruderMap, colorMapping?.size, previewTooLarge) {
    val requestId = parseRequestId + 1
    parseRequestId = requestId
    viewerLoading = true
    mesh = null
    lastSetMesh = null
    viewerView?.clearMesh()
    if (previewTooLarge) {
        viewerLoading = false
        return@LaunchedEffect          // <-- this block
    }
    ...
```

- [ ] **Step 1: Remove the `previewTooLarge` early-exit**

Delete the 4-line early-exit block. The `LaunchedEffect` key list stays unchanged (it still includes `previewTooLarge` — that's fine, changing keys is harmless). Result:

```kotlin
LaunchedEffect(modelFilePath, extruderMap, colorMapping?.size, previewTooLarge) {
    val requestId = parseRequestId + 1
    parseRequestId = requestId
    viewerLoading = true
    mesh = null
    lastSetMesh = null
    viewerView?.clearMesh()
    val parsedMesh = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        ...
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/u1/slicer/MainActivity.kt
git commit -m "feat(F48): remove previewTooLarge early-exit — decimation makes 3D preview always viable"
```

---

## Task 4: Add stride decimation to `ThreeMfMeshParser.buildMeshData()`

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/viewer/ThreeMfMeshParser.kt`

This is the secondary safety net for multi-colour Bambu imports that bypass the native loader. The change goes into `buildMeshData()` after `totalTris` is computed (line ~542) and before `allocateBuffer`.

- [ ] **Step 1: Add stride computation and apply it in the triangle loop**

After line 542 (`if (totalTris == 0) return null`), add:

```kotlin
val stride = if (totalTris > NativePreviewMesh.MAX_DECIMATED_TRIANGLES)
    (totalTris + NativePreviewMesh.MAX_DECIMATED_TRIANGLES - 1) / NativePreviewMesh.MAX_DECIMATED_TRIANGLES
else 1
val effectiveTris = if (stride > 1) (totalTris + stride - 1) / stride else totalTris
```

Change `val buf = MeshData.allocateBuffer(totalTris)` to:
```kotlin
val buf = MeshData.allocateBuffer(effectiveTris)
```

Then in the `for (i in 0 until mesh.triCount)` loop (line ~592), wrap the body with a stride check. Declare `var globalTriIndex = 0` immediately before the `for (meshCtx in mergedMeshes)` loop (line ~584) — this counter spans all mesh segments so stride is uniform across the whole model, not reset per object:

```kotlin
var globalTriIndex = 0   // <-- add this line before `for (meshCtx in mergedMeshes) {`

// inside `for (meshCtx in mergedMeshes)`, inside `for (i in 0 until mesh.triCount)`:
// add this as the very first line of the loop body:
if (globalTriIndex++ % stride != 0) continue
```

The full inner loop becomes:

```kotlin
for (i in 0 until mesh.triCount) {
    if (globalTriIndex++ % stride != 0) continue   // <-- add this line
    val a = tris[i * 3] * 3
    val b = tris[i * 3 + 1] * 3
    val c = tris[i * 3 + 2] * 3
    if (a + 2 >= verts.size || b + 2 >= verts.size || c + 2 >= verts.size) continue
    // ... rest of loop unchanged
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/u1/slicer/viewer/ThreeMfMeshParser.kt
git commit -m "feat(F48): add stride decimation cap to ThreeMfMeshParser.buildMeshData()"
```

---

## Task 5: Update unit tests

**Files:**
- Modify: `app/src/test/java/com/u1/slicer/viewer/NativePreviewMeshTest.kt`

The existing test `wouldExceedSafePreviewBudget rejects giant previews` asserts `wouldExceedSafePreviewBudget(1_260_000)` is true. With `MAX_SAFE_TRIANGLES` now 50M, this test will fail.

- [ ] **Step 1: Run the existing tests to see what breaks**

```bash
./gradlew testDebugUnitTest --tests "com.u1.slicer.viewer.NativePreviewMeshTest" --no-daemon
```

Expected: `wouldExceedSafePreviewBudget rejects giant previews` FAILS because 1,260,000 < 50,000,000.

- [ ] **Step 2: Update the budget test and add a new decimation constant test**

Replace the existing `wouldExceedSafePreviewBudget rejects giant previews` test and add two new tests:

```kotlin
@Test
fun `wouldExceedSafePreviewBudget safety net threshold is effectively unreachable`() {
    // After F48 decimation, no real model should reach 50M triangles.
    // The threshold exists only as a last-resort OOM guard.
    assertFalse(NativePreviewMesh.wouldExceedSafePreviewBudget(1_200_000))
    assertFalse(NativePreviewMesh.wouldExceedSafePreviewBudget(5_000_000))
    assertTrue(NativePreviewMesh.wouldExceedSafePreviewBudget(NativePreviewMesh.MAX_SAFE_TRIANGLES + 1))
}

@Test
fun `MAX_DECIMATED_TRIANGLES is 100000`() {
    assertEquals(100_000, NativePreviewMesh.MAX_DECIMATED_TRIANGLES)
}

@Test
fun `toMeshData on subsampled NativePreviewMesh produces correct vertex count`() {
    // Simulate a model with 300K triangles decimated to 100K (stride=3)
    // by constructing a NativePreviewMesh with exactly 100K triangles.
    val triCount = 100_000
    val positions = FloatArray(triCount * 9) { idx ->
        // Alternate between two non-degenerate triangles
        when (idx % 9) {
            0 -> 0f; 1 -> 0f; 2 -> 0f
            3 -> 1f; 4 -> 0f; 5 -> 0f
            6 -> 0f; 7 -> 1f; else -> 0f
        }
    }
    val indices = ByteArray(triCount) { (it % 4).toByte() }
    val preview = NativePreviewMesh(positions, indices)
    val mesh = preview.toMeshData()
    assertNotNull(mesh)
    assertEquals(triCount * 3, mesh!!.vertexCount)
}
```

- [ ] **Step 3: Run the tests to verify they pass**

```bash
./gradlew testDebugUnitTest --tests "com.u1.slicer.viewer.NativePreviewMeshTest" --no-daemon
```

Expected: All tests PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/com/u1/slicer/viewer/NativePreviewMeshTest.kt
git commit -m "test(F48): update budget test threshold, add MAX_DECIMATED_TRIANGLES and subsampled mesh tests"
```

---

## Task 6: Rebuild the native `.so`

The C++ changes in Task 1 must be compiled and shipped — source-only changes have no runtime effect.

**Files:**
- Modify: `app/build.gradle` (temporarily uncomment `externalNativeBuild`)
- Modify: `app/src/main/jniLibs/arm64-v8a/libsapil.so` (replace with rebuilt binary)

- [ ] **Step 1: Enable CMake in `build.gradle`**

In `app/build.gradle`, uncomment the `externalNativeBuild` block (it is commented out per CLAUDE.md):

```groovy
externalNativeBuild {
    cmake {
        path "src/main/cpp/CMakeLists.txt"
    }
}
```

- [ ] **Step 2: Run Gradle configure to generate the ninja build files**

```bash
./gradlew assembleDebug --no-daemon 2>&1 | tail -20
```

Expected: Configure step runs, `.cxx/Debug/<hash>/arm64-v8a/` directory appears. Build will likely fail (that's fine — we just need the ninja files).

- [ ] **Step 3: Find the ninja build directory**

```bash
ls app/.cxx/Debug/
```

Note the hash directory name (e.g. `3f4a6b8c`). Use it in the next step.

- [ ] **Step 4: Build with ninja (single-threaded to avoid OOM)**

```bash
cd app/.cxx/Debug/<hash>/arm64-v8a && ninja -j1 sapil 2>&1 | tail -30
```

Expected: Compiles `sapil_model.cpp`, `slicer_wrapper.cpp`, links `libsapil.so`. Takes several minutes.

- [ ] **Step 5: Strip the binary**

```bash
# Find NDK llvm-strip — path varies by NDK version
LLVM_STRIP=$(find $ANDROID_NDK_HOME -name "llvm-strip" -type f | head -1)
echo "Using strip: $LLVM_STRIP"
$LLVM_STRIP --strip-unneeded app/.cxx/Debug/<hash>/arm64-v8a/libsapil.so
```

- [ ] **Step 6: Copy to jniLibs**

```bash
cp app/.cxx/Debug/<hash>/arm64-v8a/libsapil.so app/src/main/jniLibs/arm64-v8a/libsapil.so
```

- [ ] **Step 7: Disable CMake in `build.gradle`**

Re-comment the `externalNativeBuild` block (revert Step 1 change).

- [ ] **Step 8: Clean build and install**

```bash
./gradlew clean installDebug --no-daemon
```

Expected: App installs successfully.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/jniLibs/arm64-v8a/libsapil.so app/build.gradle
git commit -m "feat(F48): rebuild native .so with stride decimation in getPreparePreviewMesh"
```

---

## Task 7: Run full unit test suite

- [ ] **Step 1: Run all unit tests**

```bash
./gradlew testDebugUnitTest --no-daemon
```

Expected: 587+ tests pass, 0 failures.

- [ ] **Step 2: If failures, investigate**

Any failure in `NativePreviewMeshTest` or `PreparePreviewPlacementTest` is likely this feature. Any other failure is a regression — investigate before continuing.

---

## Task 8: Run instrumented tests and manual smoke test

**Files:**
- Modify: `app/src/androidTest/java/com/u1/slicer/viewer/NativePreparePreviewTest.kt`

- [ ] **Step 1: Add triangle cap assertion to instrumented tests**

In `NativePreparePreviewTest.kt`, add a new test after the existing ones. Use `calib-cube-10-dual-colour-merged.3mf` (a small asset we know loads) to verify the cap is respected. A full large-model instrumented test would require a large asset not in the test suite; instead verify the cap is respected for the existing assets and that they still return non-null.

Add this test:

```kotlin
@Test
fun getPreparePreviewMesh_triangleCountRespectsCap_forDualColor3mf() {
    copyAssetToModelFile("calib-cube-10-dual-colour-merged.3mf")
    assertTrue(lib.loadModel(modelFile.absolutePath))

    val preview = lib.getPreparePreviewMesh(maxTriangles = NativePreviewMesh.MAX_DECIMATED_TRIANGLES)

    assertNotNull(preview)
    preview!!
    val triCount = preview.extruderIndices.size
    assertTrue(
        "Triangle count $triCount should be ≤ MAX_DECIMATED_TRIANGLES (${NativePreviewMesh.MAX_DECIMATED_TRIANGLES})",
        triCount <= NativePreviewMesh.MAX_DECIMATED_TRIANGLES
    )
    assertTrue(preview.trianglePositions.isNotEmpty())
    assertTrue(preview.extruderIndices.size * 9 == preview.trianglePositions.size)
}
```

- [ ] **Step 2: Run instrumented tests on device**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest \
  --tests "com.u1.slicer.viewer.NativePreparePreviewTest" --no-daemon
```

Expected: All 6 tests pass (5 existing + 1 new).

- [ ] **Step 3: Manual smoke test**

Load a large 3MF on the Prepare screen (Baby Dragon Egg or any model previously showing the 2D fallback). Verify:
- 3D model renders (no 2D bed-footprint fallback)
- Model shape is recognisable
- Drag placement and wipe tower still work
- No crash or OOM

- [ ] **Step 4: Commit instrumented test**

```bash
git add app/src/androidTest/java/com/u1/slicer/viewer/NativePreparePreviewTest.kt
git commit -m "test(F48): add triangle cap assertion to NativePreparePreviewTest"
```

---

## Task 9: Update docs and CLAUDE.md test counts

- [ ] **Step 1: Count tests**

```bash
./gradlew testDebugUnitTest --no-daemon 2>&1 | grep -E "tests were|tests passed|test results"
```

Note the new total. It should be 590+ (3 new unit tests added in Task 5).

- [ ] **Step 2: Update CLAUDE.md**

In `CLAUDE.md`, update:
- The total unit test count in `./gradlew testDebugUnitTest` line (e.g. `587 JVM unit tests` → new count)
- The `NativePreviewMeshTest` entry to reflect the new test names/count (currently `(2)`, will be `(5)`)
- The count in `### Unit tests` header line

- [ ] **Step 3: Update README.md**

Check `README.md` for any test count references and update to match.

- [ ] **Step 4: Final commit**

```bash
git add CLAUDE.md README.md
git commit -m "docs: update test counts for F48 NativePreviewMeshTest additions"
```
