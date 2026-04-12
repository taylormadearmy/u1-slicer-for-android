# F69: 3D Viewer Thread-Safety Hardening — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix data races between the main thread (gesture handlers) and the GL thread (renderer) in the 3D model viewer, eliminating the root cause of a recurring class of viewer bugs.

**Architecture:** Three targeted changes — (1) mark `Camera` scalar fields `@Volatile` for visibility; (2) route `resetView()` and `applyCameraState()` through existing volatile pending-state fields so the GL thread owns all camera mutations; (3) enforce the immutable-array contract for `modelScale`. Bonus: convert camera scalars to `Double` for precision at zoom extremes.

**Tech Stack:** Kotlin, Android OpenGL ES 3.0, `GLSurfaceView.Renderer`, `@Volatile` JVM memory model.

---

## File Map

| File | Change |
|------|--------|
| `app/src/main/java/com/u1/slicer/viewer/Camera.kt` | Add `@Volatile` to scalar fields; optionally convert to `Double` |
| `app/src/main/java/com/u1/slicer/viewer/ModelRenderer.kt` | Add `@Volatile var pendingCameraState: CameraViewState?`; consume in `onDrawFrame` |
| `app/src/main/java/com/u1/slicer/viewer/ModelViewerView.kt` | `resetView()` → pending flag; `applyCameraState()` → pending state |

No new files. `BaseGLViewerView.kt` does not need changes — gesture handlers write through `camera.rotate/zoom/pan` which stays on main thread and is now safe via `@Volatile`.

---

## Task 1: Add `@Volatile` to Camera scalar fields

The `Camera` class fields `azimuth`, `elevation`, `distance`, `panX`, `panY`, `targetX`, `targetY`, `targetZ` are written on the main thread by gesture handlers and read on the GL thread in `updateViewMatrix()`. Adding `@Volatile` establishes the JVM happens-before edge that makes those writes visible. Float reads/writes are atomic (JVM spec §17.7), so there is no torn-write risk — only a visibility gap that `@Volatile` closes.

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/viewer/Camera.kt:24-31`

- [ ] **Step 1: Add `@Volatile` to Camera scalar fields**

  Replace the field declarations in `Camera.kt` (the block starting at `class Camera {`):

  ```kotlin
  class Camera {
      @Volatile var azimuth = -45f
      @Volatile var elevation = 45f
      @Volatile var distance = 300f
      @Volatile var panX = 0f
      @Volatile var panY = 0f
      @Volatile var targetX = 0f
      @Volatile var targetY = 0f
      @Volatile var targetZ = 0f
  ```

  The matrix arrays (`viewMatrix`, `projectionMatrix`, `mvpMatrix`, `normalMatrix`) are only ever accessed on the GL thread — leave them as-is.

- [ ] **Step 2: Build and confirm no compile errors**

  ```bash
  ./gradlew :app:compileDebugKotlin --no-daemon 2>&1 | tail -20
  ```
  Expected: `BUILD SUCCESSFUL` with no errors.

- [ ] **Step 3: Run unit tests**

  ```bash
  ./gradlew testDebugUnitTest --no-daemon 2>&1 | tail -10
  ```
  Expected: all 667 tests pass.

- [ ] **Step 4: Commit**

  ```bash
  git add app/src/main/java/com/u1/slicer/viewer/Camera.kt
  git commit -m "fix: add @Volatile to Camera scalar fields (main↔GL thread visibility)"
  ```

---

## Task 2: Route `resetView()` and `applyCameraState()` through pending state

Currently `resetView()` calls `renderer.resetCameraToDefaultView()` directly from the **main** thread, but `resetCameraToDefaultView()` is also called from the **GL thread** in `onSurfaceCreated()` and `onDrawFrame()` — a data race on every reset. Similarly, `applyCameraState()` calls `renderer.camera.restore(state)` directly, racing with `updateViewMatrix()` on the GL thread.

The fix: add a `@Volatile var pendingCameraState: CameraViewState?` to `ModelRenderer` (parallel to the existing `pendingMesh`, `pendingRecolor`, etc.) and consume it at the top of `onDrawFrame`. Then redirect both callers to use it.

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/viewer/ModelRenderer.kt`
- Modify: `app/src/main/java/com/u1/slicer/viewer/ModelViewerView.kt`

- [ ] **Step 1: Add `pendingCameraState` to `ModelRenderer`**

  In `ModelRenderer.kt`, directly below the `pendingVboRefresh` field (around line 133):

  ```kotlin
  // Pending camera state: written by main thread (applyCameraState, resetView),
  // consumed at top of onDrawFrame before matrix math.
  @Volatile
  var pendingCameraState: CameraViewState? = null
  ```

- [ ] **Step 2: Consume `pendingCameraState` in `onDrawFrame`**

  In `ModelRenderer.kt`, at the very top of `onDrawFrame` (before the `pendingClearMesh` block), add:

  ```kotlin
  pendingCameraState?.let { state ->
      camera.restore(state)
      pendingCameraState = null
  }
  ```

  The full start of `onDrawFrame` should now read:

  ```kotlin
  override fun onDrawFrame(gl: GL10?) {
      pendingCameraState?.let { state ->
          camera.restore(state)
          pendingCameraState = null
      }

      if (pendingClearMesh) {
          // ... rest unchanged
  ```

- [ ] **Step 3: Fix `resetView()` in `ModelViewerView`**

  `resetView()` currently calls `renderer.resetCameraToDefaultView()` directly. Replace it to post a pending camera reset instead:

  ```kotlin
  fun resetView() {
      renderer.pendingCameraReset = true
      requestRender()
  }
  ```

  Remove the old body (`renderer.resetCameraToDefaultView()` + direct `panX/panY` assignments). The GL thread's `onDrawFrame` already calls `resetCameraToDefaultView()` and zeros `panX`/`panY` when `pendingCameraReset` is true — so behaviour is identical, just thread-safe.

- [ ] **Step 4: Fix `applyCameraState()` in `ModelViewerView`**

  Replace the direct `camera.restore(state)` call with a pending state write:

  ```kotlin
  fun applyCameraState(state: CameraViewState) {
      renderer.preserveCameraOnNextMeshUpload = true
      renderer.pendingCameraReset = false
      renderer.pendingCameraState = state
      requestRender()
  }
  ```

- [ ] **Step 5: Build and confirm no compile errors**

  ```bash
  ./gradlew :app:compileDebugKotlin --no-daemon 2>&1 | tail -20
  ```
  Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Run unit tests**

  ```bash
  ./gradlew testDebugUnitTest --no-daemon 2>&1 | tail -10
  ```
  Expected: all 667 pass.

- [ ] **Step 7: Install and smoke-test on device**

  ```bash
  ANDROID_SERIAL=43211JEKB16931 ./gradlew installDebug --no-daemon 2>&1 | tail -5
  ```

  On device:
  - Load any 3MF or STL. Orbit, zoom, pan — verify smooth response.
  - Tap reset-view button — verify camera snaps back to default.
  - Switch tabs (Prepare → G-code → Prepare) — verify camera is preserved.

- [ ] **Step 8: Commit**

  ```bash
  git add app/src/main/java/com/u1/slicer/viewer/ModelRenderer.kt \
          app/src/main/java/com/u1/slicer/viewer/ModelViewerView.kt
  git commit -m "fix: route resetView/applyCameraState through pendingCameraState (GL thread owns camera)"
  ```

---

## Task 3: Audit and enforce immutable-array contract for `modelScale`

`@Volatile var modelScale = floatArrayOf(1f, 1f, 1f)` is safe only if every write creates a **new** `FloatArray`. Any in-place mutation (`modelScale[0] = x`) is invisible to other threads despite the volatile annotation. Grep all call sites; if any mutate in-place, fix them to assign a new array.

**Files:**
- Possibly modify: `app/src/main/java/com/u1/slicer/viewer/ModelRenderer.kt`
- Possibly modify: `app/src/main/java/com/u1/slicer/viewer/ModelViewerView.kt`
- Possibly modify: `app/src/main/java/com/u1/slicer/orca/SlicerViewModel.kt`

- [ ] **Step 1: Find all writes to `modelScale`**

  ```bash
  grep -rn "modelScale" app/src/main/java/ --include="*.kt"
  ```

  Look for any lines that mutate array elements in-place, e.g.:
  - `renderer.modelScale[0] = ...`  ← BAD
  - `modelScale[1] = ...`           ← BAD
  - `renderer.modelScale = floatArrayOf(...)` ← GOOD

- [ ] **Step 2: Fix any in-place mutations found**

  If any in-place mutation exists, replace it with a full array assignment. For example:

  ```kotlin
  // BAD — in-place mutation, invisible across threads
  renderer.modelScale[0] = sx
  renderer.modelScale[1] = sy
  renderer.modelScale[2] = sz

  // GOOD — new array assignment, volatile reference is visible
  renderer.modelScale = floatArrayOf(sx, sy, sz)
  ```

  If no in-place mutations exist, document this in a code comment on the field declaration:

  ```kotlin
  // @Volatile covers the reference — always assign a new FloatArray, never mutate elements in-place.
  @Volatile var modelScale = floatArrayOf(1f, 1f, 1f)
  ```

- [ ] **Step 3: Build, test, commit**

  ```bash
  ./gradlew testDebugUnitTest --no-daemon 2>&1 | tail -10
  ```
  Expected: all 667 pass.

  ```bash
  git add -p
  git commit -m "fix: enforce immutable-array contract for modelScale (@Volatile covers reference only)"
  ```

---

## Task 4 (Bonus): Double-precision Camera scalar fields

SliceBeam uses `double` for all camera state and downcasts to `float` only at shader uniform upload. At the U1's 270 mm bed this matters most at extreme zoom-in, where `float` precision (~7 significant digits) becomes visible as sub-mm jitter. This task converts Camera scalars to `Double` and adjusts all consumers.

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/viewer/Camera.kt`
- Modify: `app/src/main/java/com/u1/slicer/viewer/ModelRenderer.kt` (uniform upload)
- Modify: `app/src/main/java/com/u1/slicer/viewer/BaseGLViewerView.kt` (gesture deltas)

- [ ] **Step 1: Convert Camera fields to Double**

  In `Camera.kt`, change all scalar fields and `CameraViewState`:

  ```kotlin
  data class CameraViewState(
      val azimuth: Double,
      val elevation: Double,
      val distance: Double,
      val panX: Double,
      val panY: Double,
      val targetX: Double,
      val targetY: Double,
      val targetZ: Double
  )

  class Camera {
      @Volatile var azimuth = -45.0
      @Volatile var elevation = 45.0
      @Volatile var distance = 300.0
      @Volatile var panX = 0.0
      @Volatile var panY = 0.0
      @Volatile var targetX = 0.0
      @Volatile var targetY = 0.0
      @Volatile var targetZ = 0.0
  ```

- [ ] **Step 2: Update `updateViewMatrix()` to use Double intermediate math**

  ```kotlin
  fun updateViewMatrix() {
      val radAz = Math.toRadians(azimuth)
      val radEl = Math.toRadians(elevation)

      val eyeX = (targetX + panX + distance * cos(radEl) * cos(radAz)).toFloat()
      val eyeY = (targetY + panY + distance * cos(radEl) * sin(radAz)).toFloat()
      val eyeZ = (targetZ + distance * sin(radEl)).toFloat()

      Matrix.setLookAtM(
          viewMatrix, 0,
          eyeX, eyeY, eyeZ,
          (targetX + panX).toFloat(), (targetY + panY).toFloat(), targetZ.toFloat(),
          0f, 0f, 1f
      )
  }
  ```

- [ ] **Step 3: Update `updateProjectionMatrix()` near/far computation**

  ```kotlin
  fun updateProjectionMatrix(width: Int, height: Int) {
      val aspect = width.toFloat() / height.toFloat()
      val near = (distance * 0.01).coerceAtLeast(0.1).toFloat()
      val far = (distance * 10.0).toFloat()
      Matrix.perspectiveM(projectionMatrix, 0, 45f, aspect, near, far)
  }
  ```

- [ ] **Step 4: Update `pan()` to use Double math**

  ```kotlin
  fun pan(dx: Double, dy: Double) {
      val radAz = Math.toRadians(azimuth)
      val rightX = -sin(radAz)
      val rightY =  cos(radAz)
      val upX = -cos(radAz)
      val upY = -sin(radAz)
      panX += rightX * dx + upX * dy
      panY += rightY * dx + upY * dy
  }
  ```

- [ ] **Step 5: Update `rotate()` and `zoom()` signatures**

  ```kotlin
  fun rotate(dAzimuth: Double, dElevation: Double) {
      azimuth += dAzimuth
      elevation = (elevation + dElevation).coerceIn(5.0, 89.0)
  }

  fun zoom(factor: Double) {
      distance = (distance * factor).coerceIn(10.0, 2000.0)
  }
  ```

- [ ] **Step 6: Update `setTarget()` signature**

  ```kotlin
  fun setTarget(x: Double, y: Double, z: Double) {
      targetX = x; targetY = y; targetZ = z
  }
  ```

- [ ] **Step 7: Fix all callers (compile-driven)**

  ```bash
  ./gradlew :app:compileDebugKotlin --no-daemon 2>&1 | grep "error:" | head -30
  ```

  For each compiler error, apply the minimal fix — typically appending `.toDouble()` to `Float` arguments or `.toFloat()` to `Double` results at call sites. Common patterns:

  - `BaseGLViewerView.kt` gesture deltas: `camera.rotate(-dx * 0.3, dy * 0.3)` — already `Double` literals, just drop the `f` suffix
  - `camera.zoom(1.0 / detector.scaleFactor)` — `scaleFactor` is `Float`; use `.toDouble()`
  - `panScale` calculations: `camera.distance * 0.003` — `distance` is now `Double`, result is `Double`, fine
  - `ModelRenderer.resetCameraToDefaultView()`: `camera.setTarget(135.0, 135.0, 0.0)`, `camera.distance = 500.0`, etc.

- [ ] **Step 8: Build clean, run all unit tests**

  ```bash
  ./gradlew :app:compileDebugKotlin --no-daemon 2>&1 | tail -5
  ./gradlew testDebugUnitTest --no-daemon 2>&1 | tail -10
  ```
  Expected: compile clean; 667 unit tests pass.

- [ ] **Step 9: Install and smoke-test on device (zoom precision check)**

  ```bash
  ANDROID_SERIAL=43211JEKB16931 ./gradlew installDebug --no-daemon 2>&1 | tail -5
  ```

  On device: zoom in to maximum on a small feature — verify no pixel-jitter at extremes. Orbit, pan, reset — verify all gestures remain smooth.

- [ ] **Step 10: Commit**

  ```bash
  git add app/src/main/java/com/u1/slicer/viewer/Camera.kt \
          app/src/main/java/com/u1/slicer/viewer/ModelRenderer.kt \
          app/src/main/java/com/u1/slicer/viewer/BaseGLViewerView.kt
  git commit -m "feat: double-precision Camera scalars — downcast to float only at shader uniforms"
  ```

---

## Self-Review

**Spec coverage:**
- ✅ `@Volatile` on Camera fields → Task 1
- ✅ `resetView()` race → Task 2 Step 3
- ✅ `applyCameraState()` race → Task 2 Step 4
- ✅ `modelScale` mutable array → Task 3
- ✅ Double-precision camera → Task 4
- ✅ `screenToBed()` defensive workaround — not removed (it remains correct after these fixes; removing it is optional cleanup, not required for correctness)

**Placeholder scan:** None found — all steps contain actual code.

**Type consistency:**
- `CameraViewState` converted to `Double` fields in Task 4 Step 1; all consumers updated in Step 7. `pendingCameraState: CameraViewState?` (added in Task 2) is written/read consistently.
- Tasks 1–3 use `Float` `CameraViewState`; Task 4 changes it to `Double`. If only Tasks 1–3 are implemented, `CameraViewState` stays `Float` — no inconsistency.
