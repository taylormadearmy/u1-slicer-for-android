# B46 Design: colored_3DBenchy Prepare Preview — Native MMU Path Fix

## Problem

The Prepare preview for painted/SEMM 3MF models has two issues since v1.5.1:

1. **Missing color regions** — color boundaries within base triangles (hull color change, door detail) are not shown
2. **Triangle seam artifacts** — speckled/noisy appearance at color boundaries

These broke when commit `1d9d19b` (v1.5.1) replaced the native `getPreparePreviewMesh()` path with the Kotlin `ThreeMfMeshParser` for painted models. The Kotlin parser's TriangleSelector subdivision creates sub-triangles with flat normals that don't match adjacent base triangles (seams) and loses color boundaries that span individual base triangles (missing regions).

Reverting to the native path was attempted but failed because:
- **Post-v1.5.0 decimation** (QEM + stride, added in F48) destroys painted meshes — QEM times out on small per-state groups, stride randomly drops triangles causing spike/wireframe artifacts
- **World-space coordinates** — native mesh includes instance transforms, but `drawModelAt` expects model-local coordinates, pushing multi-object models (Korok mask) off the bed

Layer-change color models (Hueforge-style, `layerToolOnly = true`) are unaffected — they use the non-MMU native path and `recolorByZBands()`.

## Root Cause

Two independent bugs:

1. **Native C++**: `getPreparePreviewMesh()` applies QEM + stride to each TS state group independently. Small disconnected groups decimate poorly — QEM times out (10s budget), stride drops 8/9 triangles, producing spike artifacts. This decimation was added post-v1.5.0 and is why v1.5.1 switched to the Kotlin parser.

2. **Kotlin**: `ThreeMfMeshParser.emitTriangleSelectorTriangles()` + `splitTriangle()` creates sub-triangles with mismatched normals (seams) and loses intra-triangle color boundaries.

## Design

Three coordinated changes: two in C++ (native rebuild required) and one in Kotlin.

### Change 1: Skip decimation for MMU state groups (C++)

**File**: `app/src/main/cpp/src/sapil_model.cpp` — `getPreparePreviewMesh()`

In the MMU path (lines 437-467), when iterating `facets_per_type` from `get_facets()`:
- Skip QEM entirely for MMU state groups — these are already at viewable quality from the TS expansion
- Skip stride by default (`vol_stride = 1`)
- Apply a gentle stride (stride 2) ONLY if the total MMU triangle count exceeds 500K, to stay within the `MAX_KOTLIN_PREVIEW_TRIANGLES` budget already tolerated on mobile

This restores v1.5.0's clean output for painted models. Non-painted models continue using QEM decimation unchanged.

**Implementation detail**: Count total MMU triangles in a pre-pass before the append loop. If total <= 500K, all groups get `vol_stride = 1`. If total > 500K, compute `mmu_stride = (total_mmu_tris + 500000 - 1) / 500000` and apply uniformly across all state groups.

### Change 2: Output model-local coordinates for MMU path (C++)

**File**: `app/src/main/cpp/src/sapil_model.cpp` — `getPreparePreviewMesh()`

Currently both MMU and non-MMU paths apply:
```cpp
its_transform(its, volume->get_matrix(), true);   // volume → object space
its_transform(its, instance_matrix, true);          // object → world space
```

For the MMU path, skip the instance transform but keep the volume transform:
```cpp
its_transform(its, volume->get_matrix(), true);   // volume → object space only
// Do NOT apply instance_matrix for MMU groups
```

This puts each volume's mesh in object-local coordinates. `drawModelAt` handles per-instance bed positioning via the Kotlin placement system (which provides per-instance positions from `getPlacementPositions()`).

**Multi-instance models**: For objects with multiple instances, the outer loop (`for instance : instances`) already iterates instances. With instance transform skipped, all instances produce identical geometry at object-local origin. The Kotlin placement system positions each instance via `objectPositions` — this is the same as how the STL path works (model-local mesh + Kotlin placement).

**Why not also fix the non-MMU path?** The non-MMU path already works correctly for all tested models. Changing both paths risks breaking working functionality. Only fix what's broken.

### Change 3: Route painted models through native path + H2C folding (Kotlin)

**File**: `app/src/main/java/com/u1/slicer/MainActivity.kt`

In `InlineModelPreview`'s main LaunchedEffect, change the `hasPaintData` branch to return `null` (same as non-painted 3MF), so the rotation LaunchedEffect handles the native fetch via `setModelRotation()` + `getPreparePreviewMesh()`.

Remove the `hasPaintData` early-return from the rotation LaunchedEffect so painted models flow through the same native path as non-painted 3MF.

Keep placement enabled (`objectPositions` passed through for all models) — Change 2 ensures the mesh coordinates are compatible with `drawModelAt`.

**File**: `app/src/main/java/com/u1/slicer/viewer/NativePreviewMesh.kt`

In `toMeshData()`, fold extruder indices >= 4 down to 0-3 via `idx % 4`. The native C++ maps TriangleSelector `state_idx` directly to `extruder_index = state_idx - 1` without folding, producing indices 0-9 for H2C models. Snapmaker U1 has 4 extruders, so indices must be folded.

## Native Rebuild

Required. Changes are in `sapil_model.cpp` only — no JNI signature changes, no new functions. Rebuild with `ninja -j1`, strip with `llvm-strip`, copy to `jniLibs/arm64-v8a/`.

## Test Plan

### Automated
- New unit test: H2C index folding in `NativePreviewMeshTest`
- All 639 JVM unit tests must pass
- All 152 instrumented tests must pass (includes `NativePreparePreviewTest` for dual-colour, painted, Dragon plate 3)

### Manual visual verification
| Model | What to verify |
|-------|---------------|
| colored_3DBenchy | 4 colors visible, clean boundaries, no speckled seams, no spike artifacts, smooth mesh |
| Korok mask (4-colour) | Multi-object painted model, correctly positioned on bed, colors visible |
| H2C benchy | Folded indices produce correct 4-color mapping, no garbled geometry |
| Plain 3DBenchy.stl | Placement works, model centered on bed, single extruder color |
| Non-painted 3MF (e.g. Dragon Scale) | Rotation/placement functional, multi-color assignment works |
| Layer-change model | Z-band recoloring unaffected |

## What NOT to change

- **Kotlin ThreeMfMeshParser** — kept as-is. It's still used indirectly by instrumented tests and may be useful for future non-preview use cases. Just no longer called from the preview path.
- **Non-MMU native decimation** — QEM + stride for non-painted models stays exactly as-is
- **`drawModelAt` / `drawModel` renderer** — no changes to the GL rendering pipeline
- **Layer-change (Hueforge) path** — completely separate code path, untouched
