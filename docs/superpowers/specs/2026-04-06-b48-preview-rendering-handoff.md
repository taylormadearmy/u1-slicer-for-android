# B48 Part 2 Handoff: Prepare Preview Not Rendering Per-Vertex Colors

## Status: Slicer FIXED, Preview rendering NOT FIXED

## What was fixed (this session)

The B48 slicer bug is fully fixed and tested:
- **Kotlin**: `computeEmbedTargetCount()` in `SlicerViewModel.kt` — SEMM models use `colorMapping.size` (7) as targetCount instead of `distinct().size` (4)
- **Native**: `sapil_print.cpp` — reordered `applyConfigToPrusa()` before profile_keys loop so embedded `filament_colour` (7 entries) overrides the hardware default (4). Added array padding block for `flush_volumes_matrix` and all per-extruder arrays when `filament_colour.size() > n_ext`.
- **Native .so rebuilt** (RelWithDebInfo config at `app/.cxx/RelWithDebInfo/20692i6u/arm64-v8a/`), stripped, copied to jniLibs.
- **G-code verified**: T0=591, T1=224, T2=360, T3=241 (before fix: T1=0)
- **3 instrumented tests pass** on device

## What is NOT fixed

**Per-vertex colors in the Prepare preview are completely ignored by the GL shader.** The model renders using the uniform `u_Color` (a single flat color per draw call) instead of per-vertex `a_Color` from the VBO.

### Definitive evidence

Forcing ALL 5.9M vertices to bright green (0,1,0,1) before `uploadMesh()` produces **zero green pixels** in the framebuffer. The rendered pixel distribution is identical regardless of vertex color data:
```
red=23 green=0 blue=39 white=11  (same with normal recolor AND with all-green override)
```

### Root cause hypothesis

**`useVertexColorLoc` is likely -1** (uniform location not found), so `glUniform1i(useVertexColorLoc, 1)` is a no-op, and `u_UseVertexColor` stays at its OpenGL default of 0. The shader then uses `v_Color = u_Color` (the per-draw uniform), ignoring `a_Color` (per-vertex).

This would happen if:
1. The shader failed to compile/link (but the model still renders, so the shader works — just without the `u_UseVertexColor` uniform being found)
2. The uniform was optimized away by the GLSL compiler on this specific GPU (Mali-G715 on Pixel 8a)
3. There's a name mismatch or the uniform location was fetched before the shader was ready

### First diagnostic to run

Add this one line to `onSurfaceCreated()` in `ModelRenderer.kt` after line 100:
```kotlin
android.util.Log.i("ModelRenderer", "useVertexColorLoc=$useVertexColorLoc")
```

If it logs `-1`, the uniform isn't found. Check `ShaderProgram.getUniformLocation()` and the shader compilation logs for errors.

### Key files

| File | What |
|------|------|
| `app/src/main/java/com/u1/slicer/viewer/ModelRenderer.kt:96-100` | Shader init + `useVertexColorLoc` assignment |
| `app/src/main/java/com/u1/slicer/viewer/ShaderProgram.kt` | Shader compilation + `getUniformLocation()` |
| `app/src/main/assets/shaders/model.vert` | Vertex shader — uses `u_UseVertexColor` to choose between `a_Color` and `u_Color` |
| `app/src/main/assets/shaders/model.frag` | Fragment shader — `fragColor = v_Color.rgb * v_Intensity` |
| `app/src/main/java/com/u1/slicer/viewer/MeshData.kt:36-56` | `recolor()` — writes RGBA into vertex buffer |
| `app/src/main/java/com/u1/slicer/viewer/NativePreviewMesh.kt:103-108` | `allocateBuffer()` — uses `ByteBuffer.allocateDirect()` (buffer IS direct) |
| `app/src/main/java/com/u1/slicer/MainActivity.kt:2101-2134` | Compose `LaunchedEffect` that calls `v.recolorMesh(palette)` |

### Race condition also found

`pendingMesh` and `pendingRecolor` arrive on different GL frames due to a Compose threading race:
1. Frame N: `pendingMesh` → `uploadMesh(grey)` → grey on GPU
2. Frame N+1: `pendingRecolor` → `recolor()` + `updateColorData()` → fails silently

The current code (left by this session) recolors BEFORE upload when both arrive on the same frame, and uses `uploadMesh()` (full re-upload) instead of `updateColorData()` for the existing-mesh path. But this is MOOT until the `u_UseVertexColor` issue is resolved — vertex colors have no effect regardless.

### What the TDD tests verify (and don't verify)

**Pass (data layer correct):**
- `NativePreparePreviewTest.h2cBenchy_allSevenIndicesPresent_andGreenVisibleAfterRecolor` — native mesh has 7 indices, CPU recolor produces green
- `SemmSlicingTest.h2cBenchy_semm_allFourToolsPresent_inSlicedGcode` — sliced G-code has T0-T3 > 0
- `PreparePreviewViewModelTest.h2cBenchy_fullPipeline_greenVisibleInPreview_andAllToolsInGcode` — full ViewModel flow produces green in vertex buffer

**Not verified (rendering layer broken):**
- Whether `u_UseVertexColor` uniform is active in the compiled shader
- Whether vertex colors reach the GPU via VBO upload
- Whether the framebuffer contains the expected colors after drawing

**Needed:** A `glReadPixels`-based test that runs after `onDrawFrame()` and asserts green pixels exist. The `framebufferScanCountdown` pattern from this session works but needs the rendering fix first.

### Test asset
`app/src/androidTest/assets/3DBenchy-H2C-Multi-Color.3mf`

### Files changed this session (committed state)

- `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` — `computeEmbedTargetCount()`, `embedProfile()` targetCount fix
- `app/src/main/cpp/src/sapil_print.cpp` — reorder + B48 array padding block
- `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so` — rebuilt .so
- `app/src/main/java/com/u1/slicer/viewer/ModelRenderer.kt` — recolor-before-upload + uploadMesh for existing-mesh recolor (non-diagnostic changes kept)
- `app/src/test/java/com/u1/slicer/MergeThreeMfInfoTest.kt` — 5 `computeEmbedTargetCount` tests
- `app/src/androidTest/java/com/u1/slicer/viewer/NativePreparePreviewTest.kt` — H2C benchy preview test
- `app/src/androidTest/java/com/u1/slicer/slicing/SemmSlicingTest.kt` — H2C benchy G-code test
- `app/src/androidTest/java/com/u1/slicer/PreparePreviewViewModelTest.kt` — H2C benchy full pipeline test
- `CLAUDE.md` — red-green TDD documentation
