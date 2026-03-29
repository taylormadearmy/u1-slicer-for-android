# F49: Reset-View Button Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a reset-view button to the Prepare and Preview 3D viewers that snaps the camera back to the default whole-plate view and clears the shared camera state for both tabs.

**Architecture:** Add `resetView()` to `ModelViewerView`. The Compose composable that wraps the viewer (`PrepareModelViewerComposable` / `PreviewModelViewerComposable` in `MainActivity.kt`) overlays an `IconButton` on the GL surface and calls `resetView()` plus nulls `sharedPreviewCameraState` on tap.

**Tech Stack:** Kotlin, Jetpack Compose, Material3, OpenGL ES 3.0 (ModelRenderer)

---

### Task 1: Add `resetView()` to ModelViewerView

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/viewer/ModelViewerView.kt`

- [ ] **Step 1: Add the method after `applyCameraState()`**

Open `app/src/main/java/com/u1/slicer/viewer/ModelViewerView.kt`. After the `applyCameraState` method (currently around line 89), add:

```kotlin
fun resetView() {
    renderer.resetCameraToDefaultView()
    renderer.camera.panX = 0f
    renderer.camera.panY = 0f
    requestRender()
}
```

Note: `resetCameraToDefaultView()` is `private` on `ModelRenderer` — change its visibility to `internal` so `ModelViewerView` (same module) can call it.

- [ ] **Step 2: Change `resetCameraToDefaultView` visibility in ModelRenderer**

Open `app/src/main/java/com/u1/slicer/viewer/ModelRenderer.kt`. Change:
```kotlin
private fun resetCameraToDefaultView() {
```
to:
```kotlin
internal fun resetCameraToDefaultView() {
```

- [ ] **Step 3: Verify it compiles**

```bash
./gradlew assembleDebug --no-daemon 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/u1/slicer/viewer/ModelViewerView.kt \
        app/src/main/java/com/u1/slicer/viewer/ModelRenderer.kt
git commit -m "feat(F49): add resetView() to ModelViewerView"
```

---

### Task 2: Wire reset button into the Prepare viewer composable

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/MainActivity.kt` (Prepare screen viewer section, around line 1953)

The Prepare viewer is a composable function (search for `fun.*PrepareModelViewer\|onViewerReady` near line 1953) that renders the `AndroidView` inside a `Box`. The reset button is overlaid on that same `Box`.

- [ ] **Step 1: Find the Prepare viewer composable**

Search `MainActivity.kt` for the function containing `onViewerReady` parameter around line 1953. It will look like:

```kotlin
@Composable
fun SomeName(
    ...
    cameraState: com.u1.slicer.viewer.CameraViewState? = null,
    onCameraStateChange: ((com.u1.slicer.viewer.CameraViewState) -> Unit)? = null,
    onViewerReady: ((com.u1.slicer.viewer.ModelViewerView?) -> Unit)? = null,
    ...
)
```

- [ ] **Step 2: Add `onResetView` parameter and local viewer ref**

Add a parameter:
```kotlin
onResetView: (() -> Unit)? = null,
```

Inside the composable, the `AndroidView` that creates `ModelViewerView` already stores the view in a `viewerView` state. Use that ref.

- [ ] **Step 3: Wrap the AndroidView in a Box with the reset button overlay**

Find the `AndroidView` in this composable. Wrap it (or find the existing `Box` it's already in) so it contains both the GL view and the reset button:

```kotlin
Box(modifier = Modifier.fillMaxSize()) {
    // existing AndroidView stays here unchanged

    val mesh = /* existing mesh state variable in this composable */
    if (mesh != null) {
        androidx.compose.material3.IconButton(
            onClick = { onResetView?.invoke() },
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.BottomEnd)
                .padding(8.dp)
                .background(
                    color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.4f),
                    shape = androidx.compose.foundation.shape.CircleShape
                )
        ) {
            androidx.compose.material3.Icon(
                imageVector = androidx.compose.material.icons.Icons.Default.CenterFocusWeak,
                contentDescription = "Reset view",
                tint = androidx.compose.ui.graphics.Color.White
            )
        }
    }
}
```

- [ ] **Step 4: Wire `onResetView` at the call site (Prepare screen)**

Find where this composable is called (around line 810 — it receives `cameraState = sharedPreviewCameraState` and `onViewerReady = { captureViewer = it }`). Add:

```kotlin
onResetView = { sharedPreviewCameraState = null; captureViewer?.resetView() },
```

- [ ] **Step 5: Build and smoke-test**

```bash
./gradlew installDebug --no-daemon 2>&1 | tail -5
```

Load a model on the Prepare screen, pan/zoom, tap the reset button — view should snap back to whole-plate.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/u1/slicer/MainActivity.kt
git commit -m "feat(F49): add reset-view button overlay to Prepare 3D viewer"
```

---

### Task 3: Wire reset button into the Preview viewer composable

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/MainActivity.kt` (Preview screen viewer section, around line 2753)

The Preview screen has a second viewer composable (search near line 2753 for `cameraState: com.u1.slicer.viewer.CameraViewState?`).

- [ ] **Step 1: Apply the same Box + IconButton overlay to the Preview viewer composable**

Same pattern as Task 2 Step 3. The Preview viewer composable also takes `cameraState`/`onCameraStateChange`. Add `onResetView` parameter and the same overlay.

- [ ] **Step 2: Wire `onResetView` at the Preview call site (around line 1131)**

```kotlin
onResetView = { sharedPreviewCameraState = null; /* viewer ref if available */ },
```

Note: the Preview viewer composable may not expose a `captureViewer` ref like Prepare does. If not, calling `onCameraStateChange` with `null` to clear the shared state is sufficient — the next mesh load will use `resetCameraToDefaultView()` anyway. Alternatively, expose a `viewerView` state from the composable via `onViewerReady` the same way Prepare does.

- [ ] **Step 3: Build and smoke-test**

```bash
./gradlew installDebug --no-daemon 2>&1 | tail -5
```

Navigate to Preview, pan/zoom, tap reset — view snaps back. Switch to Prepare — also reset.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/u1/slicer/MainActivity.kt
git commit -m "feat(F49): add reset-view button to Preview 3D viewer, clears shared camera state"
```
