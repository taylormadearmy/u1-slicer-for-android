# Prime Tower Footprint Accuracy + Large Model Loading Message Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the Prepare preview wipe tower to show accurate X/Y position and estimated depth (not a square), and improve the loading dialog to warn users when a model is large.

**Architecture:** Two independent changes. #36 adds a small depth-estimator utility and wires it into the existing `InlineModelPreview` call site. #35 enhances `SlicerViewModel.loadModel()` and `loadNativeModel()` message strings based on file size and triangle count — no new UI components.

**Tech Stack:** Kotlin, Jetpack Compose, JUnit 4 (existing test infra), `org.json:json:20231013` not needed here.

---

## Part A — Prime Tower Footprint Accuracy (#36)

### File Map

| Action | File |
|--------|------|
| Create | `app/src/main/java/com/u1/slicer/data/WipeTowerDepthEstimator.kt` |
| Create | `app/src/test/java/com/u1/slicer/data/WipeTowerDepthEstimatorTest.kt` |
| Modify | `app/src/main/java/com/u1/slicer/MainActivity.kt` line 832 |

---

### Task A1: WipeTowerDepthEstimator — TDD

**Files:**
- Create: `app/src/test/java/com/u1/slicer/data/WipeTowerDepthEstimatorTest.kt`
- Create: `app/src/main/java/com/u1/slicer/data/WipeTowerDepthEstimator.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/u1/slicer/data/WipeTowerDepthEstimatorTest.kt`:

```kotlin
package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WipeTowerDepthEstimatorTest {

    @Test fun `below 100mm returns minimum 20mm`() {
        assertEquals(20f, WipeTowerDepthEstimator.estimateDepth(0f), 0.01f)
        assertEquals(20f, WipeTowerDepthEstimator.estimateDepth(50f), 0.01f)
    }

    @Test fun `at 100mm returns 20mm`() {
        assertEquals(20f, WipeTowerDepthEstimator.estimateDepth(100f), 0.01f)
    }

    @Test fun `at 175mm returns 30mm midpoint`() {
        assertEquals(30f, WipeTowerDepthEstimator.estimateDepth(175f), 0.01f)
    }

    @Test fun `at 250mm returns 40mm`() {
        assertEquals(40f, WipeTowerDepthEstimator.estimateDepth(250f), 0.01f)
    }

    @Test fun `above 250mm clamped to 40mm`() {
        assertEquals(40f, WipeTowerDepthEstimator.estimateDepth(300f), 0.01f)
        assertEquals(40f, WipeTowerDepthEstimator.estimateDepth(1000f), 0.01f)
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./gradlew testDebugUnitTest --tests "com.u1.slicer.data.WipeTowerDepthEstimatorTest" --no-daemon
```

Expected: BUILD FAIL — `WipeTowerDepthEstimator` does not exist yet.

- [ ] **Step 3: Create the estimator**

Create `app/src/main/java/com/u1/slicer/data/WipeTowerDepthEstimator.kt`:

```kotlin
package com.u1.slicer.data

/**
 * Estimates wipe tower depth for the Prepare preview before slicing.
 *
 * Mirrors PartPlate::estimate_wipe_tower_size() in the OrcaSlicer C++ source,
 * which uses a two-point lookup table interpolated on model height.
 * The actual depth is computed during slicing from purge volumes — this is
 * a preview-only estimate used to show an accurate footprint for collision checks.
 *
 * Lookup table matches WipeTower::min_depth_per_height: {100mm → 20mm, 250mm → 40mm}
 */
object WipeTowerDepthEstimator {
    fun estimateDepth(modelHeightMm: Float): Float = when {
        modelHeightMm <= 100f -> 20f
        modelHeightMm >= 250f -> 40f
        else -> 20f + (modelHeightMm - 100f) / 150f * 20f
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
./gradlew testDebugUnitTest --tests "com.u1.slicer.data.WipeTowerDepthEstimatorTest" --no-daemon
```

Expected: BUILD SUCCESSFUL, 5 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/data/WipeTowerDepthEstimator.kt \
        app/src/test/java/com/u1/slicer/data/WipeTowerDepthEstimatorTest.kt
git commit -m "feat(#36): add WipeTowerDepthEstimator mirroring OrcaSlicer height→depth heuristic"
```

---

### Task A2: Wire estimator into Prepare preview

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/MainActivity.kt` line 832

The `InlineModelPreview` call at line 816 already receives `loadedInfo` (which has `sizeZ`).
Line 832 passes `wipeTowerDepth = config.wipeTowerWidth` — the square assumption to fix.

- [ ] **Step 1: Replace the square depth assumption**

In `app/src/main/java/com/u1/slicer/MainActivity.kt`, find line 832:

```kotlin
wipeTowerDepth = config.wipeTowerWidth,
```

Replace with:

```kotlin
wipeTowerDepth = com.u1.slicer.data.WipeTowerDepthEstimator.estimateDepth(loadedInfo?.sizeZ ?: 0f),
```

- [ ] **Step 2: Build to confirm no compile errors**

```bash
./gradlew assembleDebug --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run existing unit tests to confirm no regressions**

```bash
./gradlew testDebugUnitTest --no-daemon
```

Expected: BUILD SUCCESSFUL, all existing tests pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/u1/slicer/MainActivity.kt
git commit -m "fix(#36): use estimated wipe tower depth in Prepare preview (was always square)"
```

---

## Part B — Large Model Loading Message (#35)

### File Map

| Action | File |
|--------|------|
| Modify | `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` lines ~607 and ~986 |
| Create | `app/src/test/java/com/u1/slicer/LargeModelLoadingMessageTest.kt` |

> Note: `SlicerViewModel` is large and Android-context-heavy. These tests use a minimal fake pattern (not Robolectric) consistent with existing test style in this codebase. The two state emissions are simple string decisions — test them with a focused helper rather than a full ViewModel spin-up.

---

### Task B1: Extract and test the message selection logic

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`
- Create: `app/src/test/java/com/u1/slicer/LargeModelLoadingMessageTest.kt`

The two decisions (which message to show) are pure functions of file size and triangle count. Extract them as internal functions so they can be tested without spinning up the full ViewModel.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/u1/slicer/LargeModelLoadingMessageTest.kt`:

```kotlin
package com.u1.slicer

import com.u1.slicer.viewer.NativePreviewMesh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class LargeModelLoadingMessageTest {

    @Test fun `small file uses filename message`() {
        val msg = loadingMessageFor(filename = "model.stl", fileSizeBytes = 10 * 1024 * 1024L)
        assertEquals("Loading model.stl…", msg)
    }

    @Test fun `file over 50MB triggers large model message`() {
        val msg = loadingMessageFor(filename = "big.3mf", fileSizeBytes = 51 * 1024 * 1024L)
        assertEquals("Large model — this may take a moment…", msg)
    }

    @Test fun `exactly 50MB is not large`() {
        val msg = loadingMessageFor(filename = "model.stl", fileSizeBytes = 50 * 1024 * 1024L)
        assertEquals("Loading model.stl…", msg)
    }

    @Test fun `small triangles not flagged as large`() {
        assertFalse(isLargeTriangleCount(NativePreviewMesh.MAX_KOTLIN_PREVIEW_TRIANGLES))
    }

    @Test fun `triangle count over threshold is large`() {
        assertTrue(isLargeTriangleCount(NativePreviewMesh.MAX_KOTLIN_PREVIEW_TRIANGLES + 1))
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./gradlew testDebugUnitTest --tests "com.u1.slicer.LargeModelLoadingMessageTest" --no-daemon
```

Expected: BUILD FAIL — `loadingMessageFor` and `isLargeTriangleCount` do not exist yet.

- [ ] **Step 3: Add the internal functions to SlicerViewModel**

In `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`, add these two package-level internal functions near the top of the file (after the imports, before the class declaration):

```kotlin
internal fun loadingMessageFor(filename: String, fileSizeBytes: Long): String =
    if (fileSizeBytes > 50 * 1024 * 1024L) "Large model — this may take a moment…"
    else "Loading $filename…"

internal fun isLargeTriangleCount(triangleCount: Int): Boolean =
    triangleCount > com.u1.slicer.viewer.NativePreviewMesh.MAX_KOTLIN_PREVIEW_TRIANGLES
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
./gradlew testDebugUnitTest --tests "com.u1.slicer.LargeModelLoadingMessageTest" --no-daemon
```

Expected: BUILD SUCCESSFUL, 5 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/SlicerViewModel.kt \
        app/src/test/java/com/u1/slicer/LargeModelLoadingMessageTest.kt
git commit -m "feat(#35): extract loadingMessageFor() and isLargeTriangleCount() helpers with tests"
```

---

### Task B2: Wire message helpers into loadModel()

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` line ~607

Currently line 607 reads:
```kotlin
_state.value = SlicerState.Loading("Loading $filename…")
```
This is inside `loadModel(uri)` after the file has been copied to workspace (so `file.length()` is available).

- [ ] **Step 1: Replace the hardcoded loading message with the helper**

Find the line in `loadModel(uri: Uri)`:
```kotlin
_state.value = SlicerState.Loading("Loading $filename…")
```

Replace with:
```kotlin
_state.value = SlicerState.Loading(loadingMessageFor(filename, file.length()))
```

> `file` at this point is the copied workspace file — `file.length()` gives accurate size.

- [ ] **Step 2: Build to confirm no compile errors**

```bash
./gradlew assembleDebug --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/u1/slicer/SlicerViewModel.kt
git commit -m "feat(#35): show large model warning in loading dialog based on file size"
```

---

### Task B3: Wire triangle count check into loadNativeModel()

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` line ~986

Currently `loadNativeModel()` emits `SlicerState.ModelLoaded(info)` immediately after `getModelInfo()`. We need to insert a transient loading state update before that if the triangle count is large — so the loading dialog updates while the preview mesh is being built.

The relevant section (around line 983) currently reads:
```kotlin
val info = native.getModelInfo()
if (info != null) {
    lastModelInfo = info
    _modelInfo.value = info
    _modelScale.value = ModelScale()
    _state.value = SlicerState.ModelLoaded(info)
    // ... multi-color setup follows
```

- [ ] **Step 1: Insert the triangle count check before ModelLoaded**

Find the block above and replace with:
```kotlin
val info = native.getModelInfo()
if (info != null) {
    lastModelInfo = info
    _modelInfo.value = info
    _modelScale.value = ModelScale()
    if (isLargeTriangleCount(info.triangleCount)) {
        _state.value = SlicerState.Loading("Large model — preview may take a moment…")
    }
    _state.value = SlicerState.ModelLoaded(info)
    // ... multi-color setup follows (unchanged)
```

> The loading state is immediately superseded by `ModelLoaded` in the same coroutine, but the Compose `collectAsState()` collector will see both emissions — the loading message will briefly display while the preview mesh coroutine (`LaunchedEffect` in `InlineModelPreview`) builds the mesh on the IO dispatcher. This matches the existing pattern where `viewerLoading = true` keeps the spinner visible until `setOnContentReady` fires.

- [ ] **Step 2: Build to confirm no compile errors**

```bash
./gradlew assembleDebug --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run full unit test suite**

```bash
./gradlew testDebugUnitTest --no-daemon
```

Expected: BUILD SUCCESSFUL, all tests pass (590+).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/u1/slicer/SlicerViewModel.kt
git commit -m "feat(#35): update loading message for large triangle count after native model load"
```

---

### Task B4: Also wire into loadModelFromFile()

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` line ~784

`loadModelFromFile(file: File)` is a parallel entry point (used by MakerWorld downloads). It has its own loading message at line ~784:
```kotlin
_state.value = SlicerState.Loading("Loading $filename…")
```

- [ ] **Step 1: Replace the hardcoded message**

Find in `loadModelFromFile(file: File)`:
```kotlin
_state.value = SlicerState.Loading("Loading $filename…")
```

Replace with:
```kotlin
_state.value = SlicerState.Loading(loadingMessageFor(filename, file.length()))
```

- [ ] **Step 2: Build and run unit tests**

```bash
./gradlew testDebugUnitTest --no-daemon
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/u1/slicer/SlicerViewModel.kt
git commit -m "feat(#35): apply large model loading message to loadModelFromFile() path"
```

---

## Final Verification

- [ ] **Install on device and test with a normal model** — loading dialog shows "Loading filename…"
- [ ] **Install and test with F1 calendar 3MF** — loading dialog should show "Large model — this may take a moment…" and then "Large model — preview may take a moment…" while preview builds
- [ ] **Check wipe tower footprint** — on a multi-extruder model with prime tower enabled, the tower in the Prepare preview should be rectangular (taller than wide for typical print heights around 30–50mm → depth ~22–24mm vs width 60mm)

```bash
./gradlew installDebug --no-daemon
```
