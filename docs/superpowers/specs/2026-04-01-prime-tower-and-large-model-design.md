# Design: Prime Tower Footprint Accuracy (#36) + Large Model Loading Message (#35)

Date: 2026-04-01

## #36 — Prime Tower Footprint Accuracy

### Problem

The wipe tower rendered in the Prepare screen 3D preview uses a square footprint
(`wipeTowerDepth = wipeTowerWidth`). The actual sliced tower is rectangular — depth is
computed dynamically from purge volumes and tool changes. Users cannot tell from the
preview whether the tower will collide with their model.

Height accuracy is explicitly out of scope (height doesn't affect collision detection).

### Approach

Mirror the desktop OrcaSlicer `PartPlate::estimate_wipe_tower_size()` logic in Kotlin.
It uses a two-point lookup table interpolated on model height to estimate depth — the same
heuristic the desktop preview uses before slicing.

No native changes required.

### Changes

#### `SliceConfig.kt`
Add one field (mirrors OrcaSlicer `prime_volume` default of 45mm³):
```kotlin
@JvmField var primeVolume: Float = 45f
```
`primeVolume` is not used by `estimateDepth()` in this iteration — the desktop heuristic
only needs model height. It is included so embedded Snapmaker profiles can pass their
`prime_volume` value through for future volume-based depth refinement.

#### `WipeTowerDepthEstimator.kt` (new file, `data/` package)
Small utility mirroring `PartPlate::estimate_wipe_tower_size()`:

```kotlin
object WipeTowerDepthEstimator {
    // Mirrors WipeTower::min_depth_per_height {100→20, 250→40}
    fun estimateDepth(modelHeightMm: Float): Float {
        return when {
            modelHeightMm <= 100f -> 20f
            modelHeightMm >= 250f -> 40f
            else -> 20f + (modelHeightMm - 100f) / 150f * 20f
        }
    }
}
```

#### `PrepareScreen` / `InlineModelPreview` in `MainActivity.kt`
Replace:
```kotlin
wipeTowerDepth = config.wipeTowerWidth   // square assumption — wrong
```
with:
```kotlin
wipeTowerDepth = WipeTowerDepthEstimator.estimateDepth(modelInfo?.sizeZ ?: 0f)
```

### Tests

New unit test class `WipeTowerDepthEstimatorTest` covering:
- `modelHeight = 0f` → 20f (clamped to minimum)
- `modelHeight = 100f` → 20f (lower bound)
- `modelHeight = 175f` → 30f (midpoint interpolation)
- `modelHeight = 250f` → 40f (upper bound)
- `modelHeight = 300f` → 40f (clamped to maximum)

---

## #35 — Large Model Loading Message

### Problem

Loading a large model (e.g. 8M triangle F1 calendar) shows a generic "Loading…" dialog
with no indication the wait will be long. Users think the app is frozen.

### Approach

Enhance the existing `SlicerState.Loading` message string with two progressive triggers,
both in `SlicerViewModel` — no new UI components:

1. **File size trigger** (immediate): if file > 50MB, start with a large-model message.
2. **Triangle count trigger** (after `loadModel()` completes): if triangleCount > 500K
   (`MAX_KOTLIN_PREVIEW_TRIANGLES`), update message before preview mesh is built.

Both update the same loading dialog the user already sees.

### Changes

#### `SlicerViewModel.kt` — `loadModel()`

At the point the loading state is first emitted (after filename is known, before native call):
```kotlin
val largeFile = contentResolver.openFileDescriptor(uri, "r")?.statSize?.let { it > 50 * 1024 * 1024 } == true
_state.value = SlicerState.Loading(
    if (largeFile) "Large model — this may take a moment…" else "Loading $filename…"
)
```

#### `SlicerViewModel.kt` — `loadNativeModel()`

After `getModelInfo()` returns, before preview mesh is built:
```kotlin
if (info.triangleCount > NativePreviewMesh.MAX_KOTLIN_PREVIEW_TRIANGLES) {
    _state.value = SlicerState.Loading("Large model — preview may take a moment…")
}
```

State transitions to `ModelLoaded` once preview mesh completes — existing behaviour unchanged.

### Thresholds

| Trigger | Threshold | Rationale |
|---------|-----------|-----------|
| File size | 50 MB | Proxy for "probably large"; cheap to check before any parsing |
| Triangle count | 500K (`MAX_KOTLIN_PREVIEW_TRIANGLES`) | Known decimation threshold; already used in preview budget logic |

### Tests

New unit tests verifying:
- File < 50MB, triangles < 500K → normal "Loading $filename…" message throughout
- File > 50MB → immediate large-model message on load start
- File < 50MB but triangles > 500K → normal message on start, large-model message after `loadModel()` completes
- File > 50MB and triangles > 500K → large-model message throughout
