# Clipper Retry + Wipe Tower Clamp Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix two regressions from v1.5.24 — (A) wipe tower pre-slice Y-clamp uses the wrong depth (30mm `wipeTowerWidth` instead of the estimated 20mm depth), which lets the user drag the tower past the safe zone and causes Clipper overflow; and (B) after a Clipper slicing failure the user must reload the model rather than being able to adjust settings and retry.

**Architecture:** Three independent fixes. Fix A1 corrects the pre-slice Y-clamp in `SlicerViewModel.startSlicing()` to use `WipeTowerDepthEstimator` (same as the drag clamp). Fix A2 corrects `CopyArrangeCalculator.computeWipeTowerPosition()` to use actual tower depth (not width) for the Y footprint so auto-placement doesn't collide. Fix B adds a `recoverFromClipperError()` function to `SlicerViewModel` and wires it into the `ErrorCard` call site — native model state is already preserved after a Clipper failure, so only a JNI reload + state transition is needed.

**Tech Stack:** Kotlin, Jetpack Compose, JUnit 4, `WipeTowerDepthEstimator` (already in `com.u1.slicer.data`)

---

## File Map

| Action | File |
|--------|------|
| Modify | `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` lines ~1836-1844 |
| Modify | `app/src/main/java/com/u1/slicer/model/CopyArrangeCalculator.kt` lines 87-151 |
| Modify | `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` (add `recoverFromClipperError()`) |
| Modify | `app/src/main/java/com/u1/slicer/MainActivity.kt` line ~799 |
| Modify | `app/src/test/java/com/u1/slicer/model/CopyArrangeCalculatorTest.kt` (extend existing) |
| Create | `app/src/test/java/com/u1/slicer/WipeTowerClampTest.kt` |

---

## Part A1 — Fix pre-slice Y-clamp in startSlicing()

### Task A1: Correct wipe tower Y-clamp to use estimated depth

**Context:** In `SlicerViewModel.kt` around line 1836-1844, the pre-slice clamp computes `maxY` using `cfg.wipeTowerWidth` (30mm by default). But since v1.5.24, the drag clamp in `MainActivity.kt` uses `WipeTowerDepthEstimator.estimateDepth(modelHeight)` — for a model ≤100mm tall this returns 20mm, not 30mm. This 10mm gap lets users drag the tower to Y=250 while the slicer silently clamps it back to Y=240, but worse: if the tower gets sent unclamped, it can reach into Clipper overflow territory.

The current (buggy) code in `startSlicing()`:
```kotlin
val maxX = (cfg.bedSizeX - cfg.wipeTowerWidth).coerceAtLeast(0f)
val maxY = (cfg.bedSizeY - cfg.wipeTowerWidth).coerceAtLeast(0f)  // BUG: should use estimated depth
val clampedX = cfg.wipeTowerX.coerceIn(0f, maxX)
val clampedY = cfg.wipeTowerY.coerceIn(0f, maxY)
```

**Files:**
- Create: `app/src/test/java/com/u1/slicer/WipeTowerClampTest.kt`
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` lines ~1836-1844

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/u1/slicer/WipeTowerClampTest.kt`:

```kotlin
package com.u1.slicer

import com.u1.slicer.data.WipeTowerDepthEstimator
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies that wipeTowerClampBounds() uses estimated depth (not width) for the Y axis.
 *
 * The regression: depth is 20mm for short models (≤100mm), but pre-slice clamp was
 * using wipeTowerWidth (30mm) for both axes, creating a 10mm discrepancy with the
 * drag clamp in the UI.
 */
class WipeTowerClampTest {

    @Test fun `maxY uses estimated depth not width for short model`() {
        val modelHeightMm = 6f  // F1 calendar is very flat
        val bedSize = 270f
        val towerWidth = 60f
        val estimatedDepth = WipeTowerDepthEstimator.estimateDepth(modelHeightMm)
        val (maxX, maxY) = wipeTowerClampBounds(
            bedSizeX = bedSize, bedSizeY = bedSize,
            towerWidth = towerWidth, towerDepth = estimatedDepth
        )
        assertEquals(bedSize - towerWidth, maxX, 0.01f)   // X uses width
        assertEquals(bedSize - estimatedDepth, maxY, 0.01f) // Y uses depth (20mm, not 30mm)
    }

    @Test fun `maxY uses estimated depth for tall model`() {
        val modelHeightMm = 250f  // at cap
        val bedSize = 270f
        val towerWidth = 60f
        val estimatedDepth = WipeTowerDepthEstimator.estimateDepth(modelHeightMm) // 40mm
        val (maxX, maxY) = wipeTowerClampBounds(
            bedSizeX = bedSize, bedSizeY = bedSize,
            towerWidth = towerWidth, towerDepth = estimatedDepth
        )
        assertEquals(bedSize - towerWidth, maxX, 0.01f)
        assertEquals(bedSize - estimatedDepth, maxY, 0.01f) // 230mm, not 240mm
    }

    @Test fun `clamp moves out-of-bounds tower inside bed`() {
        val bounds = wipeTowerClampBounds(
            bedSizeX = 270f, bedSizeY = 270f,
            towerWidth = 60f, towerDepth = 20f
        )
        val clampedX = 300f.coerceIn(0f, bounds.first)
        val clampedY = 260f.coerceIn(0f, bounds.second)
        assertEquals(210f, clampedX, 0.01f) // 270 - 60
        assertEquals(250f, clampedY, 0.01f) // 270 - 20
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./gradlew testDebugUnitTest --tests "com.u1.slicer.WipeTowerClampTest" --no-daemon
```

Expected: BUILD FAIL — `wipeTowerClampBounds` does not exist yet.

- [ ] **Step 3: Add the helper function and fix the clamp**

In `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`, add this package-level internal function near the other package-level helpers (after the existing `loadingMessageFor` and `isLargeTriangleCount` functions, before the class declaration):

```kotlin
/**
 * Returns (maxX, maxY) for wipe tower position clamping.
 * X uses towerWidth (the tower is wider than it is deep).
 * Y uses towerDepth (estimated from model height via WipeTowerDepthEstimator).
 */
internal fun wipeTowerClampBounds(
    bedSizeX: Float, bedSizeY: Float,
    towerWidth: Float, towerDepth: Float
): Pair<Float, Float> {
    val maxX = (bedSizeX - towerWidth).coerceAtLeast(0f)
    val maxY = (bedSizeY - towerDepth).coerceAtLeast(0f)
    return maxX to maxY
}
```

Then in `startSlicing()` (around line 1836), replace:

```kotlin
val maxX = (cfg.bedSizeX - cfg.wipeTowerWidth).coerceAtLeast(0f)
val maxY = (cfg.bedSizeY - cfg.wipeTowerWidth).coerceAtLeast(0f)
val clampedX = cfg.wipeTowerX.coerceIn(0f, maxX)
val clampedY = cfg.wipeTowerY.coerceIn(0f, maxY)
if (clampedX != cfg.wipeTowerX || clampedY != cfg.wipeTowerY) {
    Log.w("SlicerVM", "Clamped wipe tower from (${cfg.wipeTowerX},${cfg.wipeTowerY}) to ($clampedX,$clampedY) — was outside bed bounds")
}
```

With:

```kotlin
val estimatedDepth = com.u1.slicer.data.WipeTowerDepthEstimator.estimateDepth(lastModelInfo?.sizeZ ?: 0f)
val (maxX, maxY) = wipeTowerClampBounds(cfg.bedSizeX, cfg.bedSizeY, cfg.wipeTowerWidth, estimatedDepth)
val clampedX = cfg.wipeTowerX.coerceIn(0f, maxX)
val clampedY = cfg.wipeTowerY.coerceIn(0f, maxY)
if (clampedX != cfg.wipeTowerX || clampedY != cfg.wipeTowerY) {
    Log.w("SlicerVM", "Clamped wipe tower from (${cfg.wipeTowerX},${cfg.wipeTowerY}) to ($clampedX,$clampedY) — was outside bed bounds")
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
./gradlew testDebugUnitTest --tests "com.u1.slicer.WipeTowerClampTest" --no-daemon
```

Expected: BUILD SUCCESSFUL, 3 tests passed.

- [ ] **Step 5: Run full unit test suite to confirm no regressions**

```bash
./gradlew testDebugUnitTest --no-daemon
```

Expected: BUILD SUCCESSFUL, all existing tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/u1/slicer/SlicerViewModel.kt \
        app/src/test/java/com/u1/slicer/WipeTowerClampTest.kt
git commit -m "fix(#37): use estimated tower depth (not width) for pre-slice Y-clamp"
```

---

## Part A2 — Fix auto-placement Y footprint in CopyArrangeCalculator

### Task A2: Pass towerDepth separately to computeWipeTowerPosition()

**Context:** `CopyArrangeCalculator.computeWipeTowerPosition()` takes a single `towerWidth` parameter and uses it for **both** the X and Y dimensions of the tower footprint (square assumption). The tower is 60mm wide but only 20mm deep for a flat model. This means the auto-placement overlap check over-estimates how much space the tower takes on the Y axis, which can push it into bad positions.

The function signature is:
```kotlin
fun computeWipeTowerPosition(
    objectPositions: FloatArray,
    objectSizeX: Float,
    objectSizeY: Float,
    towerWidth: Float = 60f,   // used for both axes
    bedSizeX: Float = 270f,
    bedSizeY: Float = 270f
): Pair<Float, Float>
```

The call site in `SlicerViewModel.kt` line ~1047:
```kotlin
val towerPos = CopyArrangeCalculator.computeWipeTowerPosition(
    positions, info.sizeX, info.sizeY, _config.value.wipeTowerWidth
)
```

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/model/CopyArrangeCalculator.kt`
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` line ~1047
- Modify: `app/src/test/java/com/u1/slicer/model/CopyArrangeCalculatorTest.kt`

- [ ] **Step 1: Write the failing tests**

Open `app/src/test/java/com/u1/slicer/model/CopyArrangeCalculatorTest.kt` and add these tests to the existing class:

```kotlin
@Test fun `auto-placement uses towerDepth for Y footprint not towerWidth`() {
    // A model that exactly fits the left half of the bed (135mm wide, 270mm tall)
    // The tower should be placed on the right side; with the correct 20mm depth
    // it should fit without Y-overlap issues.
    val positions = floatArrayOf(0f, 0f)  // model at origin
    val result = CopyArrangeCalculator.computeWipeTowerPosition(
        objectPositions = positions,
        objectSizeX = 135f,
        objectSizeY = 270f,
        towerWidth = 60f,
        towerDepth = 20f
    )
    // Tower placed to the right: x should be >= 135 + some margin
    assertTrue("Tower X should be right of model: ${result.first}", result.first >= 140f)
}

@Test fun `computeWipeTowerPosition accepts separate towerDepth`() {
    val positions = floatArrayOf(105f, 105f)  // centered model
    val resultSquare = CopyArrangeCalculator.computeWipeTowerPosition(
        objectPositions = positions,
        objectSizeX = 60f,
        objectSizeY = 60f,
        towerWidth = 60f,
        towerDepth = 60f  // square: same as old behavior
    )
    val resultRect = CopyArrangeCalculator.computeWipeTowerPosition(
        objectPositions = positions,
        objectSizeX = 60f,
        objectSizeY = 60f,
        towerWidth = 60f,
        towerDepth = 20f  // rectangular: narrower depth
    )
    // Both should produce valid positions within bed bounds
    assertTrue(resultSquare.first >= 0f && resultSquare.first <= 210f)
    assertTrue(resultSquare.second >= 0f && resultSquare.second <= 210f)
    assertTrue(resultRect.first >= 0f && resultRect.first <= 210f)
    assertTrue(resultRect.second >= 0f && resultRect.second <= 250f)  // 270-20
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./gradlew testDebugUnitTest --tests "com.u1.slicer.model.CopyArrangeCalculatorTest" --no-daemon
```

Expected: BUILD FAIL — `computeWipeTowerPosition` doesn't have a `towerDepth` parameter yet.

- [ ] **Step 3: Update CopyArrangeCalculator.computeWipeTowerPosition()**

In `app/src/main/java/com/u1/slicer/model/CopyArrangeCalculator.kt`, replace the `computeWipeTowerPosition` function (lines 87–151) with:

```kotlin
/**
 * Compute a wipe tower position that avoids overlapping the model(s).
 * Tries eight candidate positions around the bed perimeter, picks the one with
 * the most clearance from all object bounding boxes.
 *
 * @param objectPositions flat [x0,y0,x1,y1,...] model positions (min-corner, mm)
 * @param objectSizeX model bounding box X
 * @param objectSizeY model bounding box Y
 * @param towerWidth wipe tower width (X dimension)
 * @param towerDepth wipe tower depth (Y dimension); defaults to towerWidth for backward compat
 * @param bedSizeX bed X dimension
 * @param bedSizeY bed Y dimension
 * @return Pair(towerX, towerY) in mm
 */
fun computeWipeTowerPosition(
    objectPositions: FloatArray,
    objectSizeX: Float,
    objectSizeY: Float,
    towerWidth: Float = 60f,
    towerDepth: Float = towerWidth,
    bedSizeX: Float = 270f,
    bedSizeY: Float = 270f
): Pair<Float, Float> {
    val bedCenter = bedSizeX / 2f
    // Margin from bed edge: prime_tower_brim_width (3mm) + skirt_distance (6mm)
    // + 1 skirt loop (~0.5mm) ≈ 9.5mm. Use 10mm to be safe.
    val edgeMargin = 10f
    val candidates = listOf(
        edgeMargin to edgeMargin,                                                        // bottom-left
        bedSizeX - towerWidth - edgeMargin to edgeMargin,                                // bottom-right
        edgeMargin to bedSizeY - towerDepth - edgeMargin,                                // top-left
        bedSizeX - towerWidth - edgeMargin to bedSizeY - towerDepth - edgeMargin,        // top-right
        bedCenter - towerWidth / 2f to edgeMargin,                                       // bottom-center
        bedCenter - towerWidth / 2f to bedSizeY - towerDepth - edgeMargin,               // top-center
        edgeMargin to bedCenter - towerDepth / 2f,                                       // left-center
        bedSizeX - towerWidth - edgeMargin to bedCenter - towerDepth / 2f                // right-center
    )

    // Build list of object bounding boxes [minX, minY, maxX, maxY]
    val objectCount = objectPositions.size / 2
    val objectBoxes = (0 until objectCount).map { i ->
        val ox = objectPositions[i * 2]
        val oy = objectPositions[i * 2 + 1]
        floatArrayOf(ox, oy, ox + objectSizeX, oy + objectSizeY)
    }

    // For each candidate, compute the minimum distance to any object box
    var bestCandidate = candidates[0]
    var bestMinDist = Float.NEGATIVE_INFINITY

    for ((cx, cy) in candidates) {
        val tMinX = cx; val tMinY = cy
        val tMaxX = cx + towerWidth; val tMaxY = cy + towerDepth
        var minDist = Float.MAX_VALUE

        for (box in objectBoxes) {
            val oMinX = box[0]; val oMinY = box[1]
            val oMaxX = box[2]; val oMaxY = box[3]
            // Signed distance: negative = overlapping
            val dx = maxOf(oMinX - tMaxX, tMinX - oMaxX, 0f)
            val dy = maxOf(oMinY - tMaxY, tMinY - oMaxY, 0f)
            val dist = if (dx == 0f && dy == 0f) {
                // Overlapping — compute negative penetration
                val overlapX = minOf(tMaxX - oMinX, oMaxX - tMinX)
                val overlapY = minOf(tMaxY - oMinY, oMaxY - tMinY)
                -minOf(overlapX, overlapY)
            } else {
                dx + dy
            }
            minDist = minOf(minDist, dist)
        }

        if (minDist > bestMinDist) {
            bestMinDist = minDist
            bestCandidate = cx to cy
        }
    }

    return bestCandidate
}
```

- [ ] **Step 4: Update the call site in SlicerViewModel.kt**

In `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`, find the call to `computeWipeTowerPosition` (around line 1046-1048):

```kotlin
val towerPos = CopyArrangeCalculator.computeWipeTowerPosition(
    positions, info.sizeX, info.sizeY, _config.value.wipeTowerWidth
)
```

Replace with:

```kotlin
val estimatedTowerDepth = com.u1.slicer.data.WipeTowerDepthEstimator.estimateDepth(info.sizeZ)
val towerPos = CopyArrangeCalculator.computeWipeTowerPosition(
    positions, info.sizeX, info.sizeY,
    towerWidth = _config.value.wipeTowerWidth,
    towerDepth = estimatedTowerDepth
)
```

- [ ] **Step 5: Run tests to confirm they pass**

```bash
./gradlew testDebugUnitTest --tests "com.u1.slicer.model.CopyArrangeCalculatorTest" --no-daemon
```

Expected: BUILD SUCCESSFUL, all CopyArrangeCalculatorTest tests pass.

- [ ] **Step 6: Run full unit test suite**

```bash
./gradlew testDebugUnitTest --no-daemon
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/u1/slicer/model/CopyArrangeCalculator.kt \
        app/src/main/java/com/u1/slicer/SlicerViewModel.kt \
        app/src/test/java/com/u1/slicer/model/CopyArrangeCalculatorTest.kt
git commit -m "fix(#37): pass towerDepth to computeWipeTowerPosition (was square assumption)"
```

---

## Part B — Retry slice after Clipper failure without reloading

### Task B: Wire recoverFromClipperError() and expose Retry button

**Context:** After a Clipper slicing failure, `attemptClipperRecovery()` calls `native.clearModel()` and sets `SlicerState.Error`. All Kotlin model state is preserved (`lastModelInfo`, `_threeMfInfo`, `currentModelFile`, `rawInputFile`). The `ErrorCard` composable already has an `onResetAndRetry` parameter and shows a "Reset & Retry" button for Clipper errors when it's non-null — but the call site at `MainActivity.kt:~799` doesn't pass this parameter.

The fix: add `recoverFromClipperError()` to `SlicerViewModel` that re-runs `loadNativeModel()` from `currentModelFile` and transitions back to `ModelLoaded`. Then pass it to `ErrorCard`.

Note: `currentModelFile` is the processed/sanitized file (not `rawInputFile`). It survives the `clearModel()` call — only `native.clearModel()` is called, not `clearIntermediateCache()`.

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` (add `recoverFromClipperError()`)
- Modify: `app/src/main/java/com/u1/slicer/MainActivity.kt` line ~799

> Note: `recoverFromClipperError()` doesn't need unit tests because it's a thin orchestrator calling already-tested helpers (`loadNativeModel`, `native.loadModel`). The behavior is verified by manual/instrumented test (install on device, trigger Clipper error, use Reset & Retry).

- [ ] **Step 1: Add recoverFromClipperError() to SlicerViewModel**

In `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`, find `attemptClipperRecovery()` (around line 2289). Add a new public function immediately after it:

```kotlin
/**
 * Reload the model from the already-processed file and return to ModelLoaded state.
 * Called when the user taps "Reset & Retry" after a Clipper slicing failure.
 *
 * All Kotlin model state (lastModelInfo, _threeMfInfo, color mapping) is already intact —
 * only the native model was cleared. Re-running loadNativeModel() restores the JNI state
 * without requiring the user to pick the file again.
 */
fun recoverFromClipperError() {
    val file = currentModelFile ?: return
    viewModelScope.launch {
        _state.value = SlicerState.Loading("Reloading model…")
        loadNativeModel(file)
    }
}
```

- [ ] **Step 2: Wire onResetAndRetry into the ErrorCard call site**

In `app/src/main/java/com/u1/slicer/MainActivity.kt`, find the `ErrorCard` call inside the `state is SlicerViewModel.SlicerState.Error` branch (around line 798):

```kotlin
ErrorCard(
    (state as SlicerViewModel.SlicerState.Error).message,
    onPickFile,
    onRestart = { viewModel.restartApp() },
    onShareDiagnostics = { viewModel.shareDiagnostics() }
)
```

Replace with:

```kotlin
ErrorCard(
    (state as SlicerViewModel.SlicerState.Error).message,
    onPickFile,
    onResetAndRetry = { viewModel.recoverFromClipperError() },
    onRestart = { viewModel.restartApp() },
    onShareDiagnostics = { viewModel.shareDiagnostics() }
)
```

- [ ] **Step 3: Build to confirm no compile errors**

```bash
./gradlew assembleDebug --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run full unit test suite**

```bash
./gradlew testDebugUnitTest --no-daemon
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/SlicerViewModel.kt \
        app/src/main/java/com/u1/slicer/MainActivity.kt
git commit -m "feat(#38): add Reset & Retry after Clipper error — reload model without picking file again"
```

---

## Final Verification

- [ ] Build and install on device:

```bash
./gradlew installDebug --no-daemon
```

- [ ] **Test wipe tower clamp fix**: Load the F1 calendar 3MF (flat model, sizeZ ~6mm). Enable prime tower. Drag the wipe tower to the very top of the bed. Verify the drag stops at ~250mm (270 - 20mm depth). Slice — should not produce a Clipper overflow.

- [ ] **Test Reset & Retry**: Load the F1 calendar and force a Clipper error (drag wipe tower fully off-bed in a previous build, or trigger by slicing a known-bad position). After error card appears, tap "Reset & Retry". Verify the model reloads (loading spinner appears briefly), returns to the Prepare screen with the 3D preview intact, and the Slice button is active again. Adjust settings and slice again — verify success.
