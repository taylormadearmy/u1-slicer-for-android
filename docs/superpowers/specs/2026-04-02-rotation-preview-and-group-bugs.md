# Spec: Rotation Bug Fixes — Preview Update & Group Rotation

**Date:** 2026-04-02  
**Scope:** Two bugs introduced in v1.5.28 (F57 model rotation)

---

## Bug 1 — Prepare preview does not update when rotation changes

### Root cause

`SlicerViewModel.setModelRotation()` (SlicerViewModel.kt:1277) only updates the `_modelRotation` StateFlow. There is no `LaunchedEffect` in `InlineModelPreview` that reacts to rotation changes and re-fetches the preview mesh. The native side correctly calls `invalidatePreviewMeshCache()` after rotation (sapil_arrange.cpp:157), so the data is ready — the Kotlin side just never requests it.

### Fix

**Kotlin (MainActivity.kt):** Add a new `LaunchedEffect(modelRotation)` inside `InlineModelPreview` (after the existing scale effect at line 2125). This effect:

1. Calls `withContext(Dispatchers.IO) { NativeLibrary().setModelRotation(rotation.x, rotation.y, rotation.z) }` — drives the native state from the current StateFlow value (ensures native is in sync even if the initial call from the slider already did it).
2. Calls `withContext(Dispatchers.IO) { NativeLibrary().getPreparePreviewMesh()?.toMeshData() }` — re-fetches the rotated mesh.
3. Only runs this path for non-painted 3MF files (same condition as the existing mesh load: `!hasPaintData`). Painted/SEMM models use the Kotlin `ThreeMfMeshParser` for their preview, which is not rotation-aware — leave them unchanged for now.
4. If a new mesh is returned, updates the `mesh` state variable, which the existing `LaunchedEffect(mesh, …)` at line 2083 will pick up and push to the GL renderer automatically.

**No change needed to SlicerViewModel.kt** — the StateFlow already captures the value; the effect just reacts to it.

### Regression test (unit — PreparePreviewPlacementTest.kt)

Add a test `rotationChange_triggersMeshRefresh_forNonPaintedModel` to `PreparePreviewPlacementTest.kt` (JVM). This test:
- Mocks `NativeLibrary` to record calls and return a dummy `NativePreviewMesh`.
- Sets up the `InlineModelPreview` composable under test with a non-painted `.3mf` path.
- Changes `modelRotation` on the ViewModel.
- Asserts that `getPreparePreviewMesh()` was called a second time (after the rotation change), confirming mesh re-fetch.

> Note: `PreparePreviewPlacementTest` is a JVM unit test (app/src/test/). The new test follows the same pattern as the existing tests in that file.

---

## Bug 2 — Multi-object plates rotate individually (overlap)

### Root cause

`setModelRotation` in sapil_arrange.cpp (lines 141-160) calls `inst->set_rotation(rx, ry, rz)` on every instance independently. This rotates each object around its own local origin. Objects that were spread across the bed (e.g. four button clusters at separate positions) each spin in place and end up overlapping.

Compare with `setModelScale` (lines 99-139) which computes the combined world bounding box centre first, then adjusts each instance's `offset` so the group scales proportionally around the shared centre.

### Fix

**C++ (sapil_arrange.cpp — `setModelRotation`):**

The function needs to:

1. **Compute group centre** — same approach as `setModelScale`: build a `BoundingBoxf3` from all instances' world bounding boxes, take `center()`. Use only X and Y for the pivot (Z pivot stays at 0 — we rotate around a vertical axis for Z, and for X/Y tilt we rotate around the bed plane centre).

2. **Cache base positions** — to avoid drift across repeated slider calls, store each instance's pre-rotation offset in a static `g_rotation_base_positions` vector (parallel to the flat enumeration of all instances). This cache is:
   - Populated on the **first** `setModelRotation` call after a model load (i.e. when `g_rotation_base_positions` is empty).
   - Cleared in `clearModelData()` / wherever the model is unloaded.
   - **Not** cleared on reset to (0,0,0) — the reset simply calls `setModelRotation(0,0,0)` which reapplies the stored base positions with identity rotation, restoring exact original positions.

3. **Apply group rotation** — for each instance at index `i` with base position `b`:
   ```
   new_offset = pivot + R(rx, ry, rz) * (b - pivot)
   inst->set_rotation(rx, ry, rz)
   inst->set_offset(new_offset)
   ```
   where `R` is the 3×3 rotation matrix for Euler angles (ZYX convention matching OrcaSlicer's `ModelInstance::set_rotation`).

4. The pivot is the X/Y centre of `g_rotation_base_positions`-based bounding box (recomputed from base positions + raw bounding boxes). Z pivot is 0.

**Header (sapil.h / sapil_arrange.cpp):** Add `static std::vector<Slic3r::Vec3d> g_rotation_base_positions;` alongside the other globals in sapil_model.cpp. Add `extern` declaration in sapil_arrange.cpp.

**Clear on model load:** In the function that calls `g_model_loaded = false` / clears the model, also clear `g_rotation_base_positions`.

### Regression test (instrumented — NativePreparePreviewTest.kt or new SlicingIntegrationTest)

Add `setModelRotation_multiObject_doesNotOverlap` to `SlicingIntegrationTest.kt`:
- Load `sydney_buttons.3mf` (already used in NativePreparePreviewTest).
- Record all instance offsets before rotation.
- Call `lib.setModelRotation(0f, 0f, 45f)`.
- Fetch instance offsets after rotation via a new helper (or via `getPreparePreviewMesh` bounding-box check).
- Assert that the pairwise distance between each object's centre is preserved to within a small tolerance (rotation preserves distances — objects that were apart should remain apart).

> This test requires a way to read back instance positions. If no such JNI method exists, add a lightweight `getInstanceOffsets(): FloatArray` JNI method alongside `setModelRotation` for test use only (can be `@VisibleForTesting` guarded or simply not exposed in the public API).

---

## Rebuild requirement

Both fixes touch C++ (`sapil_arrange.cpp`, `sapil_model.cpp`). The native `.so` must be rebuilt after implementing Bug 2's fix:

```
ninja -j1  →  llvm-strip --strip-unneeded  →  copy to jniLibs/arm64-v8a/
```

Bug 1 is Kotlin-only and does not require a rebuild.

---

## Test coverage summary

| Test | Type | File | Covers |
|------|------|------|--------|
| `rotationChange_triggersMeshRefresh_forNonPaintedModel` | JVM unit | PreparePreviewPlacementTest.kt | Bug 1 |
| `setModelRotation_multiObject_doesNotOverlap` | Instrumented | SlicingIntegrationTest.kt | Bug 2 |

Existing test `tetrahedron_stl_slicesSuccessfully_withRotation` (SlicingIntegrationTest.kt:112) covers the single-object rotation path and should continue to pass unchanged.

---

## Out of scope

- Rotation preview for painted/SEMM models (they use Kotlin `ThreeMfMeshParser` which is not rotation-aware; a separate ticket if needed).
- Live rotation preview performance (debouncing slider calls) — current step-based sliders (35/71 steps) already limit call frequency.
