# B46 colored_3DBenchy Prepare Preview Fix — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix painted/SEMM 3MF Prepare preview to show clean colored mesh with correct color boundaries — no seam artifacts, no spike artifacts, no missing colors.

**Architecture:** Modify native `getPreparePreviewMesh()` to skip QEM/stride decimation for MMU state groups and output model-local coordinates (no instance transform). Route painted models through the native path in Kotlin. Fold H2C extruder indices in `toMeshData()`.

**Tech Stack:** C++ (sapil_model.cpp, native rebuild), Kotlin (MainActivity.kt, NativePreviewMesh.kt), JUnit tests

**Spec:** `docs/superpowers/specs/2026-04-05-b46-benchy-preview-color-design.md`

---

### Task 1: Modify native `getPreparePreviewMesh()` MMU path — skip decimation, output model-local coords

**Files:**
- Modify: `app/src/main/cpp/src/sapil_model.cpp:437-467`

- [ ] **Step 1: Count MMU triangles and compute gentle stride**

In `getPreparePreviewMesh()`, replace lines 437-467 (the MMU `if` block) with code that:
1. First counts total MMU triangles across all state groups
2. Computes a gentle stride only if total > 500K
3. Skips QEM entirely
4. Applies only the volume transform (not instance transform)

Replace this block:
```cpp
                if (!volume->mmu_segmentation_facets.empty()) {
                    std::vector<indexed_triangle_set> facets_per_type;
                    volume->mmu_segmentation_facets.get_facets(*volume, facets_per_type);
                    int tri_counter = 0;
                    for (size_t state_idx = 0; state_idx < facets_per_type.size(); ++state_idx) {
                        auto its = facets_per_type[state_idx];
                        if (its.indices.empty()) continue;
                        its_transform(its, volume->get_matrix(), true);
                        its_transform(its, instance_matrix, true);
                        const int vol_tris = static_cast<int>(its.indices.size());
                        // Same logic as non-MMU: skip small groups, rely on time budget
                        const int MIN_DECIMATION_TRIS_MMU = 1000;
                        const bool vol_needs_dec = needs_decimation && vol_tris > MIN_DECIMATION_TRIS_MMU;
                        const bool can_qem = vol_needs_dec && !qem_budget_exceeded;
                        if (can_qem) {
                            const uint32_t target = static_cast<uint32_t>(
                                std::max(INT64_C(1),
                                    static_cast<int64_t>(vol_tris) * effective_max / total_tris));
                            if (its.indices.size() > target)
                                Slic3r::its_quadric_edge_collapse(its, target);
                            if (std::chrono::steady_clock::now() > qem_deadline) {
                                qem_budget_exceeded = true;
                                SAPIL_LOGW("getPreparePreviewMesh: QEM time budget exceeded, switching to stride");
                            }
                        }
                        const uint8_t extruder_index = state_idx == 0
                            ? fallback_index
                            : static_cast<uint8_t>(state_idx - 1);
                        const int vol_stride = (can_qem || !vol_needs_dec) ? 1 : stride;
                        appendItsPreviewMesh(out, its, extruder_index, vol_stride, tri_counter);
                    }
```

With:
```cpp
                if (!volume->mmu_segmentation_facets.empty()) {
                    std::vector<indexed_triangle_set> facets_per_type;
                    volume->mmu_segmentation_facets.get_facets(*volume, facets_per_type);

                    // B46: count total MMU triangles for gentle stride calculation.
                    // Skip QEM entirely — TS-expanded per-state groups are small and
                    // disconnected, causing QEM timeouts and stride fallback that
                    // destroys mesh connectivity (spike artifacts).
                    int mmu_total = 0;
                    for (const auto& fpt : facets_per_type)
                        mmu_total += static_cast<int>(fpt.indices.size());
                    // Gentle stride only if over 500K (the budget already tolerated
                    // for painted models on the Kotlin path).
                    const int mmu_stride = (mmu_total > 500000)
                        ? ((mmu_total + 499999) / 500000)
                        : 1;
                    SAPIL_LOGI("getPreparePreviewMesh MMU: mmu_total=%d mmu_stride=%d",
                        mmu_total, mmu_stride);

                    int tri_counter = 0;
                    for (size_t state_idx = 0; state_idx < facets_per_type.size(); ++state_idx) {
                        auto its = facets_per_type[state_idx];
                        if (its.indices.empty()) continue;
                        // B46: apply volume transform only (model-local coords).
                        // Skip instance_matrix — the Kotlin placement system handles
                        // per-instance bed positioning via drawModelAt().
                        its_transform(its, volume->get_matrix(), true);
                        const uint8_t extruder_index = state_idx == 0
                            ? fallback_index
                            : static_cast<uint8_t>(state_idx - 1);
                        appendItsPreviewMesh(out, its, extruder_index, mmu_stride, tri_counter);
                    }
```

- [ ] **Step 2: Verify the change compiles**

The change only modifies logic within an existing `if` block. No new includes, no signature changes. Save the file and verify no syntax errors by checking with a quick grep for balanced braces around the modified section.

---

### Task 2: Rebuild native `.so`

**Files:**
- Modify: `app/build.gradle` (temporary — enable CMake, then disable)
- Output: `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so`

- [ ] **Step 1: Enable CMake in build.gradle**

Uncomment the `externalNativeBuild` blocks in `app/build.gradle`. There are two blocks to uncomment — one in `android.defaultConfig` and one in `android`. Find them with:
```bash
grep -n "externalNativeBuild" app/build.gradle
```

- [ ] **Step 2: Run Gradle assembleDebug to configure CMake**

```bash
./gradlew assembleDebug 2>&1 | tail -20
```

This generates the ninja build files. It may fail at link time — that's OK, we just need the configuration.

- [ ] **Step 3: Disable CMake in build.gradle**

Re-comment the `externalNativeBuild` blocks (revert step 1).

- [ ] **Step 4: Run ninja build**

```bash
ninja -j1 -C app/.cxx/Debug/186o3e6t/arm64-v8a/ 2>&1 | tail -20
```

Use `-j1` to avoid OOM. If the hash dir `186o3e6t` doesn't have the updated build files, find the correct one:
```bash
ls -lt app/.cxx/Debug/*/arm64-v8a/build.ninja | head -1
```

Expected: compiles `sapil_model.cpp` and links `libprusaslicer-jni.so`.

- [ ] **Step 5: Strip and copy the `.so`**

```bash
# Find the NDK llvm-strip
find ~/AppData/Local/Android/Sdk/ndk -name "llvm-strip" -path "*/aarch64*" 2>/dev/null | head -1
# Strip (use the path found above)
<ndk-path>/llvm-strip --strip-unneeded app/.cxx/Debug/186o3e6t/arm64-v8a/libprusaslicer-jni.so
# Copy to jniLibs
cp app/.cxx/Debug/186o3e6t/arm64-v8a/libprusaslicer-jni.so app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so
```

- [ ] **Step 6: Clean build to pick up the new `.so`**

```bash
./gradlew clean assembleDebug 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit native changes**

```bash
git add app/src/main/cpp/src/sapil_model.cpp app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so
git commit -m "fix(native): B46 skip QEM/stride for MMU preview, use model-local coords"
```

---

### Task 3: Add H2C index folding in NativePreviewMesh.toMeshData()

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/viewer/NativePreviewMesh.kt:74-84`
- Test: `app/src/test/java/com/u1/slicer/viewer/NativePreviewMeshTest.kt`

- [ ] **Step 1: Write the failing test**

Add this test to `NativePreviewMeshTest.kt` after the existing `toMeshData converts triangle payload` test (after line 35):

```kotlin
    @Test
    fun `toMeshData folds H2C indices above 3 with modulo 4`() {
        val preview = NativePreviewMesh(
            trianglePositions = FloatArray(5 * 9) { idx ->
                when (idx % 9) {
                    0 -> 0f; 1 -> 0f; 2 -> 0f
                    3 -> 1f; 4 -> 0f; 5 -> 0f
                    6 -> 0f; 7 -> 1f; else -> 0f
                }
            },
            extruderIndices = byteArrayOf(0, 3, 4, 7, 9)
        )
        val mesh = preview.toMeshData()
        assertNotNull(mesh)
        // 0→0, 3→3 (no fold), 4→0, 7→3, 9→1
        assertArrayEquals(byteArrayOf(0, 3, 0, 3, 1), mesh!!.extruderIndices)
    }
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew testDebugUnitTest --tests "com.u1.slicer.viewer.NativePreviewMeshTest.toMeshData folds H2C indices above 3 with modulo 4" 2>&1 | tail -10
```

Expected: FAIL — indices `[0, 3, 4, 7, 9]` returned unchanged, not folded.

- [ ] **Step 3: Add H2C index folding**

In `NativePreviewMesh.kt`, replace lines 74-84:

```kotlin
        return MeshData(
            vertices = buf,
            vertexCount = triangleCount * 3,
            minX = minX,
            minY = minY,
            minZ = minZ,
            maxX = maxX,
            maxY = maxY,
            maxZ = maxZ,
            extruderIndices = extruderIndices.copyOf()
        )
```

With:

```kotlin
        // B46: fold indices >= 4 down to 0-3 for Snapmaker U1's 4 extruders.
        // The native C++ maps TriangleSelector state_idx directly to extruder_index
        // without folding, producing indices beyond the 4-entry palette for H2C models.
        val foldedIndices = extruderIndices.copyOf()
        for (i in foldedIndices.indices) {
            val idx = foldedIndices[i].toInt() and 0xFF
            if (idx >= 4) foldedIndices[i] = (idx % 4).toByte()
        }

        return MeshData(
            vertices = buf,
            vertexCount = triangleCount * 3,
            minX = minX,
            minY = minY,
            minZ = minZ,
            maxX = maxX,
            maxY = maxY,
            maxZ = maxZ,
            extruderIndices = foldedIndices
        )
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew testDebugUnitTest --tests "com.u1.slicer.viewer.NativePreviewMeshTest" 2>&1 | tail -10
```

Expected: All 5 NativePreviewMeshTest tests pass (4 existing + 1 new).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/viewer/NativePreviewMesh.kt app/src/test/java/com/u1/slicer/viewer/NativePreviewMeshTest.kt
git commit -m "fix: B46 fold H2C extruder indices >= 4 to 0-3 in NativePreviewMesh"
```

---

### Task 4: Route painted models through native path in MainActivity.kt

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/MainActivity.kt:2076-2093` and `2160-2167`

- [ ] **Step 1: Change main LaunchedEffect to return null for all 3MF**

In `MainActivity.kt`, replace lines 2076-2093:

```kotlin
                    modelFilePath.endsWith(".3mf", ignoreCase = true) ->
                        // Painted/SEMM models: use Kotlin ThreeMfMeshParser (per-triangle paint_color).
                        // Non-painted 3MF: return null here — the LaunchedEffect(modelRotation) below
                        // will call setModelRotation + getPreparePreviewMesh() and owns that fetch.
                        // Doing it here as well races with the rotation effect (both on Dispatchers.IO),
                        // causing setModelRotation to mutate instances while getPreparePreviewMesh reads
                        // them, producing a garbled/90°-rotated preview mesh.
                        if (hasPaintData && (colorMapping != null || extruderMap != null)) {
                            com.u1.slicer.viewer.ThreeMfMeshParser.parse(
                                file = file,
                                extruderMap = extruderMap,
                                detectedColorCount = colorMapping?.size ?: 0
                            )
                        } else if (hasPaintData) {
                            null  // colorMapping not ready yet — wait for re-fire
                        } else {
                            null  // rotation effect owns this fetch
                        }
```

With:

```kotlin
                    modelFilePath.endsWith(".3mf", ignoreCase = true) ->
                        // B46 fix: ALL 3MF models use the native getPreparePreviewMesh()
                        // path via the rotation LaunchedEffect below. The Kotlin
                        // ThreeMfMeshParser created seam artifacts at color boundaries
                        // and lost color regions for painted/SEMM models. The native path
                        // produces clean meshes with correct per-state color grouping.
                        null  // rotation effect owns this fetch for all 3MF
```

- [ ] **Step 2: Remove hasPaintData early-return from rotation LaunchedEffect**

In `MainActivity.kt`, replace lines 2160-2167:

```kotlin
    // Re-fetch preview mesh when rotation changes (non-painted 3MF and STL only).
    // Painted/SEMM models use ThreeMfMeshParser which is not rotation-aware.
    // Uses previewMutex to serialize against concurrent fetches from other composable instances —
    // setModelRotation mutates global native state; concurrent getPreparePreviewMesh reads race it.
    LaunchedEffect(modelRotation) {
        val rot = modelRotation
        if (modelFilePath.endsWith(".3mf", ignoreCase = true) &&
            hasPaintData) return@LaunchedEffect
```

With:

```kotlin
    // Re-fetch preview mesh when rotation changes (all 3MF models).
    // B46 fix: painted/SEMM models also use this native path now — the Kotlin
    // ThreeMfMeshParser path created seam artifacts and lost color boundaries.
    // Rotation is not user-adjustable for painted models, but the initial call
    // (rot=0,0,0) correctly initializes instance transforms via setModelRotation()
    // before calling getPreparePreviewMesh().
    // Uses previewMutex to serialize against concurrent fetches from other composable instances —
    // setModelRotation mutates global native state; concurrent getPreparePreviewMesh reads race it.
    LaunchedEffect(modelRotation) {
        val rot = modelRotation
```

- [ ] **Step 3: Build and verify compilation**

```bash
./gradlew assembleDebug 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Run all unit tests**

```bash
./gradlew testDebugUnitTest 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL (all 640 tests pass — 639 existing + 1 new)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/MainActivity.kt
git commit -m "fix: B46 route painted 3MF through native preview path"
```

---

### Task 5: Run instrumented tests

**Files:** None (test-only)

- [ ] **Step 1: Install and run instrumented tests on Pixel 8a**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest --no-daemon 2>&1 | tail -30
```

Expected: 152 tests, 0 failures. Key tests to watch:
- `NativePreparePreviewTest` — dual-colour, painted, Dragon plate 3
- `PreparePreviewViewModelTest` — Dragon plate 3 colour coverage
- `SlicingIntegrationTest` — STL/3MF load→slice pipeline

- [ ] **Step 2: If any test fails, investigate and fix before proceeding**

Read the test report:
```bash
cat app/build/outputs/androidTest-results/connected/debug/*.xml | grep -i "failure"
```

---

### Task 6: Manual visual verification on device

**Files:** None (manual test)

- [ ] **Step 1: Push test assets and load colored_3DBenchy**

```bash
MSYS_NO_PATHCONV=1 adb -s 43211JEKB16931 push "app/src/androidTest/assets/colored_3DBenchy (1).3mf" /data/local/tmp/colored_3DBenchy.3mf
MSYS_NO_PATHCONV=1 adb -s 43211JEKB16931 shell am broadcast -a com.u1.slicer.orca.LOAD_FILE --es path /data/local/tmp/colored_3DBenchy.3mf
```

Verify: 4 colors visible, clean boundaries, no speckled seams, no spike artifacts, smooth mesh. "Drag to move objects" should be visible (placement enabled).

- [ ] **Step 2: Load Korok mask**

```bash
MSYS_NO_PATHCONV=1 adb -s 43211JEKB16931 push "app/src/androidTest/assets/PrusaSlicer-printables-Korok_mask_4colour.3mf" /data/local/tmp/Korok.3mf
MSYS_NO_PATHCONV=1 adb -s 43211JEKB16931 shell am broadcast -a com.u1.slicer.orca.LOAD_FILE --es path /data/local/tmp/Korok.3mf
```

Verify: multi-object model correctly positioned on bed (not off-bed), colors visible, placement enabled.

- [ ] **Step 3: Load plain STL benchy**

```bash
MSYS_NO_PATHCONV=1 adb -s 43211JEKB16931 push "app/src/androidTest/assets/3DBenchy.stl" /data/local/tmp/3DBenchy.stl
MSYS_NO_PATHCONV=1 adb -s 43211JEKB16931 shell am broadcast -a com.u1.slicer.orca.LOAD_FILE --es path /data/local/tmp/3DBenchy.stl
```

Verify: placement works, model centered on bed, single extruder color shown.

- [ ] **Step 4: Load non-painted multi-color 3MF (Dragon Scale)**

```bash
MSYS_NO_PATHCONV=1 adb -s 43211JEKB16931 push "app/src/androidTest/assets/Dragon Scale infinity.3mf" /data/local/tmp/Dragon.3mf
MSYS_NO_PATHCONV=1 adb -s 43211JEKB16931 shell am broadcast -a com.u1.slicer.orca.LOAD_FILE --es path /data/local/tmp/Dragon.3mf
```

Verify: rotation/placement functional, multi-color assignment works, model on bed.

- [ ] **Step 5: Take screenshots and log verification results**

```bash
adb -s 43211JEKB16931 exec-out screencap -p > c:/tmp/b46-final-colored.png
adb -s 43211JEKB16931 exec-out screencap -p > c:/tmp/b46-final-korok.png
adb -s 43211JEKB16931 exec-out screencap -p > c:/tmp/b46-final-stl.png
adb -s 43211JEKB16931 exec-out screencap -p > c:/tmp/b46-final-dragon.png
```

---

### Task 7: Final commit and build for user testing

- [ ] **Step 1: Copy APK for user testing**

```bash
cp app/build/outputs/apk/debug/app-debug.apk "G:/My Drive/claude/b46-native-fix.apk"
```

- [ ] **Step 2: Report results to user**

Provide screenshots and summary of what was fixed, what was tested, and any remaining issues found during verification.
