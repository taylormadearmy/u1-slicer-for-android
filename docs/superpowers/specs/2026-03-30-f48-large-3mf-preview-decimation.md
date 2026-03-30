---
feature: F48
title: Better Prepare preview for large 3MF models — stride decimation
date: 2026-03-30
status: approved
---

# F48: Better Prepare Preview for Large 3MF Models

## Problem

Models with >1,200,000 triangles (e.g. Baby Dragon Egg at 1.7M) currently skip the 3D Prepare
preview entirely and show a 2D top-down bed footprint fallback. This loses 3D perspective and
colour detail. The fallback was introduced to guard against OOM when building the OpenGL interleaved
buffer (~188MB at 1.2M triangles).

## Goal

Show a real 3D preview for all practical models on the Prepare screen, at quality matching or
exceeding the G-code preview, within safe memory bounds.

## Quality Baseline

At 0.2mm layer height rendered on a ~400px screen (270mm bed → ~0.67mm/px), triangles smaller than
~0.45mm² are sub-pixel and invisible. A ~50mm model has ~20,000mm² surface area; 100,000 triangles
gives ~0.2mm²/triangle — roughly 2× finer than the G-code preview resolution. 100K is the target.

The Baby Dragon Egg at 1.7M triangles → stride 17 → ~100K exported. GL buffer: ~12MB (vs 204MB
today). Visually indistinguishable from the full mesh at this screen size.

## Approach: Fixed-target stride decimation in C++

The model is already loaded in Orca's C++ memory when the Prepare screen opens. Decimation happens
inside `getPreparePreviewMesh()` before data crosses the JNI bridge — no XML re-parse, no extra
heap allocation on the Kotlin side.

**Why not the Kotlin `ThreeMfMeshParser` path?**
That path is for multi-colour Bambu imports where colour data must be extracted from XML. It
requires a full XML parse regardless. Stride subsampling is added there too (same 100K target) but
as a secondary fix — it only fires for files that bypass the native loader.

## Architecture

### C++ (`sapil_print.cpp` or equivalent export function)

```
getPreparePreviewMesh(maxTriangles: Int = 100_000): NativePreviewMesh?
```

- Compute `stride = max(1, ceil(totalTriangles / maxTriangles))`
- Iterate the loaded model's triangle list stepping by `stride`
- Fill `FloatArray` of `≤maxTriangles × 9` floats (world-space positions)
- Fill `ByteArray` of `≤maxTriangles` extruder indices
- Return as `NativePreviewMesh` — JNI signature gains `int maxTriangles` parameter

### Kotlin (`NativeLibrary.kt`)

```kotlin
external fun getPreparePreviewMesh(maxTriangles: Int = 100_000): NativePreviewMesh?
```

### `NativePreviewMesh.kt`

- `MAX_DECIMATED_TRIANGLES = 100_000` replaces `MAX_SAFE_TRIANGLES = 1_200_000` as the export target
- `MAX_SAFE_TRIANGLES` raised from 1,200,000 to 50,000,000 — `wouldExceedSafePreviewBudget()`
  still exists and is still called by `LargePreviewFallback`, but the threshold is now effectively
  unreachable. B18 regression tests continue to pass unchanged.

### `MainActivity.kt` / Prepare screen

- `previewTooLarge` check removed — native path always taken for 3MF files
- `LargePreviewFallback` composable **kept** but threshold raised to 50M triangles (safety net,
  never fires in practice)
- No change to drag placement, wipe tower, or camera logic

### `ThreeMfMeshParser.kt` (secondary)

- After collecting all triangles, if `triCount > 100_000`: compute stride, copy every Nth triangle
  into the output arrays before building `MeshData`
- Preserves extruder/paint colour indices by taking the same stride through `paintSpecs`

## Data Flow

```
User loads 3MF
  → Orca C++ parses model (unchanged)
  → Prepare screen: NativeLibrary.getPreparePreviewMesh(100_000)
      → C++: stride = ceil(1_700_000 / 100_000) = 17
      → exports 100K triangles as FloatArray + ByteArray
      → JNI: ~2.4MB across bridge (vs 61MB today)
  → NativePreviewMesh.toMeshData(): builds 12MB GL buffer
  → ModelRenderer: displays 3D model (unchanged)
```

## Memory Budget

| Triangles | FloatArray (positions) | ByteArray (indices) | GL buffer | Total |
|-----------|----------------------|---------------------|-----------|-------|
| 1,700,000 (before) | 61MB | 1.7MB | 204MB | ~267MB → OOM |
| 100,000 (after) | 3.6MB | 0.1MB | 12MB | ~16MB |

## Edge Cases

- **Models ≤100K triangles**: stride=1, identical behaviour to today
- **maxTriangles ≤ 0 or totalTriangles = 0**: return null (existing behaviour unchanged)
- **STL files**: unaffected — go through `StlParser`, already well under budget
- **Multi-colour Bambu 3MF**: `ThreeMfMeshParser` path gets secondary stride cap

## Testing

### Unit tests (`NativePreviewMeshTest.kt`)
- Existing budget guardrail tests pass (threshold raised, not removed)
- New: given a `NativePreviewMesh` constructed with 1.7M triangles subsampled to 100K,
  `toMeshData()` returns non-null and `vertexCount == 100_000 * 3`

### Unit tests (`ThreeMfMeshParserTest.kt` or new `MeshDecimationTest.kt`)
- Stride computation: `ceil(1_700_000 / 100_000) == 17`
- Output triangle count ≤ 100K for oversized input

### Instrumented tests (`NativePreparePreviewTest.kt`)
- New: load a large 3MF asset, assert returned `MeshData.vertexCount ≤ 100_000 * 3`
- Existing Dragon Egg / placement tests unaffected

## What Does Not Change

- G-code preview path (unrelated)
- Camera, drag placement, wipe tower logic
- `ModelRenderer`, `ModelViewerView`, `MeshData` format
- `BambuSanitizer`, `ProfileEmbedder`, slicing pipeline
- B18 OOM protections (XML streaming in `ThreeMfMeshParser` unchanged)

## Out of Scope

- Progressive/coarse-then-refine streaming (Option C — revisit if 100K looks too coarse)
- Screen-density-proportional target (Option B — unnecessary at this screen size)
- Quadric Error Metrics decimation (overkill; uniform stride is visually equivalent at this resolution)
