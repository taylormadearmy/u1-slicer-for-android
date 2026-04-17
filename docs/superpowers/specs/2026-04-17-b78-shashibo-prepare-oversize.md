# B78: Shashibo plate 5 Prepare preview oversized + off-centre — Handoff Spec

**Date:** 2026-04-17
**Status:** Pre-existing regression, not fixed in v1.5.69
**Source:** Kevin's review during v1.5.69 E2E batch

## Symptom

`Shashibo-h2s-textured.3mf` plate 5 (and likely other multi-part 3MFs with non-zero original plate positions) shows a Prepare preview where the model appears to fill 50–60 % of the bed width, centred on the bed. The actual model is 77×82 mm on a 270×270 mm bed — about 28 % — and older versions rendered it at the correct scale in the top-left quadrant (the original Bambu plate position).

Slice output is unaffected: G-code produces the correct 551 CP TOOLCHANGE events with T0=71, T1=69 (matches historical baseline).

## Bisect result

Regression introduced by **commit `bc2c76d` (v1.5.65, B73 fix)**. Bisect sequence on device:

| Version | Commit | Shashibo plate 5 Prepare |
|---|---|---|
| v1.5.48 | 80afab0 | **Correct** — small pyramid top-left of bed |
| v1.5.55 | 9629dc6 | **Correct** — small pyramid top-left |
| v1.5.60 | 0d26c4d | **Correct** — small pyramid top-left |
| v1.5.64 | c4a520c | **Correct** — small pyramid top-left |
| v1.5.65 | bc2c76d | **Regressed** — large pyramid centred |
| v1.5.69 | 6d08bcb (current main) | Regressed |

Screenshots saved at `c:/tmp/v148-shashibo-prepare.png`, `c:/tmp/v155-shashibo-prepare.png`, `c:/tmp/v160-shashibo-prepare.png`, `c:/tmp/v164-shashibo-prepare.png`, `c:/tmp/v165-shashibo-prepare.png`, `c:/tmp/v169-shashibo-prepare2.png` during the bisect.

## Root cause

B73 added to [`MainActivity.kt:2287-2288`](app/src/main/java/com/u1/slicer/MainActivity.kt#L2287-L2288):

```kotlin
lib.setModelScale(1f, 1f, 1f)
lib.setModelInstances(floatArrayOf(135f, 135f))
```

before `getPreparePreviewMesh()`. The intent was to undo B72/B73's double-scale and multi-copy baking.

But `setModelInstances([135, 135])` re-centres every loaded model to bed centre, overriding the original plate-defined position. For Bambu 3MFs where the plate stores the object at a specific XY (e.g. Shashibo plate 5 places its pyramid in the upper-left quadrant), the Prepare preview now shows the model in the **wrong world position**.

The "oversized" perception is an artefact: the GL camera auto-fits the mesh's world-space bounding box. Re-centring to (135, 135) moves the mesh closer to the camera's lookAt point, so the auto-fit zooms in, making the model appear larger on screen even though its physical dimensions are unchanged.

In v1.5.65's combined `sapil_arrange.cpp` change, `setModelInstances()` now uses `offset = pos - scale * meshBB.min` (correct for scaled models). This interacts with the re-centre call: for meshes whose `meshBB.min` is non-zero (i.e. the plate extract put the object somewhere other than origin), the offset computation shifts the mesh to (135, 135) + offset adjustments, disregarding the plate's original `model_instance` transform.

## Proposed fix

Three possible approaches. **Pick one**, write failing instrumented test first.

### Option A (lowest risk, Kotlin-only): read the plate's original position instead of forcing (135, 135)

`ThreeMfInfo` already carries plate metadata. Extract the object's instance transform from `model_settings.config` / `<assemble>` / `<model_instance>` sections during plate extraction, and pass it forward to the Prepare preview call:

```kotlin
val (px, py) = viewModel.lastPlateCentre ?: Pair(135f, 135f)
lib.setModelInstances(floatArrayOf(px, py))
```

Where `lastPlateCentre` is computed during `mergeThreeMfInfoForPlate` from the plate's `<assemble_item transform="…">` tx, ty values, or from `model_settings.config`'s per-object matrix. For Shashibo plate 5 this should reproduce the v1.5.64 top-left position.

### Option B (native): add `getPreparePreviewMesh` variant that doesn't require an instance

Add a new native entry point `getPreparePreviewMeshModelSpace()` that returns the mesh in model-local coordinates (pre-transform), so the Kotlin side doesn't need `setModelInstances` at all. The GL renderer already applies its own model transform, so it can position the mesh wherever it wants without affecting native state.

Requires a native rebuild (NDK 26, clang 17) per CLAUDE.md.

### Option C (hybrid): remember and restore prior setModelInstances state

Read the current instances from native before the Prepare preview call, reset to single-centred, fetch mesh, restore prior instances. Avoids mutating long-lived native state.

## Reproduction steps

1. Install v1.5.69+ on Pixel 8a.
2. Push `Shashibo-h2s-textured.3mf` into `files/` via base64 pipe.
3. `adb shell am broadcast -a com.u1.slicer.orca.LOAD_FILE --es path 'Shashibo-h2s-textured.3mf'`
4. `adb shell am broadcast -a com.u1.slicer.orca.SELECT_PLATE --ei plate 5`
5. Screenshot Prepare — compare to the v1.5.48/v1.5.64 reference in `c:/tmp/v148-shashibo-prepare.png`.

## Test plan for the fix

### Failing instrumented test (write first)

```kotlin
@Test
fun shashiboPlate5_preparePreview_preservesOriginalPlatePosition() {
    val input = asset("Shashibo-h2s-textured.3mf")
    // Full load→selectPlate→setup native state pipeline
    // Assert getPreparePreviewMesh returns mesh with world-space bounds matching
    // the original plate position (NOT re-centred to 135,135)
    val info = ThreeMfParser.parse(input)
    val plateInfo = /* plate 5 selection */
    // …
    val mesh = lib.getPreparePreviewMesh(…)!!.toMeshData()
    val bounds = computeMeshBounds(mesh)
    // For Shashibo plate 5 the 3MF puts the object at ~(90, 62) per
    // model_settings.config <assemble_item transform="... 90.07 0 62.26">
    assertTrue("Mesh X min should be near original plate position (~90), got ${bounds.minX}",
        bounds.minX < 120f)
}
```

### E2E screenshot diff

- Load Shashibo plate 5.
- Screenshot Prepare.
- Assert the rendered pyramid occupies <35 % of the bed area (vs current 50–60 %).
- Or pixel-compare to reference `v148-shashibo-prepare.png`.

## Files likely to change

| File | Change |
|---|---|
| `app/src/main/java/com/u1/slicer/MainActivity.kt` around line 2287–2288 | Replace `(135f, 135f)` with the plate's original centre |
| `app/src/main/java/com/u1/slicer/bambu/ThreeMfInfo.kt` | Add `platePrimaryInstanceTransform` or similar field |
| `app/src/main/java/com/u1/slicer/bambu/ThreeMfParser.kt` | Parse `<assemble_item transform="…">` tx/ty into `ThreeMfPlate` |
| `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` | Expose last-loaded plate centre to `InlineModelPreview` |
| `app/src/androidTest/java/com/u1/slicer/viewer/NativePreparePreviewTest.kt` | New regression test |

## What NOT to do

- **Do not revert B73.** B73 was fixing real double-scale bugs (e.g. scale-down placement, Korok/Flarewing preview). Reverting reintroduces those.
- **Do not remove the `setModelScale(1f, 1f, 1f)` call.** That's correct for undoing baked scale. Only the `setModelInstances([135, 135])` re-centre is wrong.

## Priority

Visual-only. Slicing works correctly. Ship v1.5.69 as-is; queue this for v1.5.70.
