# B51 Handoff: SEMM Prepare Preview Regression

## Status: Regression identified, needs fix

The B48 commit (`08fff2c`) fixed H2C benchy slicing + preview but broke the Prepare preview for other SEMM models (old.3mf, Korok mask). The G-code output and G-code preview are correct for all models. Only the Prepare preview mesh from `getPreparePreviewMesh()` is affected.

## What's broken

### old.3mf
- **Prepare preview**: Model is tiny, lying down, broken into two separate pieces
- **G-code preview**: Correct — full figure, 1163 layers, 9h 9m, proper colours
- **Screenshots**: `G:\My Drive\Logs\Screenshot (7 Apr 2026 14 35 20).png` (gcode, correct) and `Screenshot (7 Apr 2026 14 35 27).png` (prepare, broken)

### Korok mask
- **Prepare preview**: Mask standing upright instead of lying flat on the bed
- **G-code preview**: Correct — 18 layers, flat, proper orientation
- **Screenshots**: `G:\My Drive\Logs\Screenshot (7 Apr 2026 14 26 38).png` (prepare, wrong orientation) and `Screenshot (7 Apr 2026 14 26 30).png` (gcode, correct)

### H2C benchy
- **Both previews correct** — this model is NOT affected

## Root cause analysis

The regression is in `sapil_model.cpp` in the `getPreparePreviewMesh()` MMU path. The B48 commit rewrote this from:

**Before (v1.5.38, working):**
```cpp
// Sequential per-state emission with stride
for (size_t state_idx = 0; state_idx < facets_per_type.size(); ++state_idx) {
    auto its = facets_per_type[state_idx];
    its_transform(its, volume->get_matrix(), true);
    appendItsPreviewMesh(out, its, extruder_index, mmu_stride, tri_counter);
}
```

**After (B48 commit, broken for non-H2C):**
```cpp
// Build flat position arrays per state, then round-robin interleave
struct StateTris { std::vector<float> positions; uint8_t extruder; };
std::vector<StateTris> states;
for (size_t state_idx = 0; state_idx < facets_per_type.size(); ++state_idx) {
    auto its = facets_per_type[state_idx];
    its_transform(its, volume->get_matrix(), true);
    // ... manually extract vertex positions into flat float array
    states.push_back(std::move(st));
}
// Round-robin interleave across states
```

### Key differences to investigate:
1. **`appendItsPreviewMesh()` vs manual vertex extraction** — the old code used `appendItsPreviewMesh()` which handled normals and the full 10-float vertex format. The new code extracts only 9 floats (3 positions x 3 vertices) per triangle. The `PreviewMesh` struct uses `triangle_positions` (9 floats per tri) + separate `extruder_indices`, so this might be fine — but verify `appendItsPreviewMesh()` wasn't doing something else important.

2. **Volume transform handling** — both paths call `its_transform(its, volume->get_matrix(), true)`. But the old code went through `appendItsPreviewMesh()` which may have applied additional transforms or coordinate adjustments.

3. **Multi-volume models** — old.3mf and Korok may have multiple volumes with different transforms. H2C benchy may be a single volume. If the interleaving is mixing triangles from different volumes without respecting per-volume transforms, that could produce broken/split geometry.

4. **The `mmu_stride` calculation was removed** — the old code had a stride for decimation. The new code emits all triangles. While this shouldn't cause orientation issues, it changes which code path handles the output.

## What the committed code looks like

Key file: `app/src/main/cpp/src/sapil_model.cpp` around line 440-510 (the MMU path in `getPreparePreviewMesh()`).

The non-MMU path (regular volumes) was NOT changed and still uses `appendItsPreviewMesh()`. The regression is specific to the MMU/SEMM code path.

## Suggested fix approach

1. **Write red tests first** (TDD):
   - `NativePreparePreviewTest`: Load old.3mf, get preview mesh, verify bounding box is reasonable (not tiny), verify single connected region (not split in two)
   - `NativePreparePreviewTest`: Load Korok mask, get preview mesh, verify Z-extent matches expected (flat, ~3.8mm, not tall)

2. **Compare `appendItsPreviewMesh()` with the manual extraction** — the function is defined earlier in the file. Check if it does any coordinate flipping, normal computation, or transform that the manual path misses.

3. **Check multi-volume handling** — dump `volume->get_matrix()` for old.3mf and Korok. If there are multiple volumes with non-identity transforms, the round-robin interleaving across states may be mixing triangles from different coordinate spaces.

4. **Simplest fix may be**: restore `appendItsPreviewMesh()` for the state emission, but wrap it in the round-robin interleaving structure. This preserves the B48 interleaving benefit (all colours proportionally represented) while using the proven vertex emission code.

## Test verification

- 657 unit tests pass (current HEAD)
- 157 instrumented tests pass (current HEAD)
- H2C benchy E2E: PASS (T0=120 T1=238 T2=241 T3=239)
- old.3mf E2E: preview FAIL (tiny/broken), gcode PASS
- Korok mask E2E: preview FAIL (wrong orientation), gcode PASS

## Files to focus on

| File | Why |
|------|-----|
| `app/src/main/cpp/src/sapil_model.cpp` | The MMU path in `getPreparePreviewMesh()` — this is where the regression is |
| `app/src/androidTest/java/com/u1/slicer/viewer/NativePreparePreviewTest.kt` | Add red tests for old.3mf + Korok bounding box / orientation |
| `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so` | Must rebuild after C++ fix |
