# F3: Per-Vertex Multi-Color 3D Model Preview + F25: Single-Color Extruder Picker

**Date**: 2026-03-15
**Status**: ARCHIVED — Implemented in v1.3.36

## Summary

Add per-vertex coloring to the Prepare screen's 3D model viewer so multi-color models display their actual extruder colors. Colors update live when the user changes extruder assignments. Also adds an extruder picker for single-color models.

## Goals

1. Multi-color models (per-volume and SEMM paint) render with per-region extruder colors
2. Changing extruder mapping in the UI immediately updates the 3D preview
3. Single-color models render in their assigned extruder's color
4. Single-color models get an extruder picker (E1-E4) on the Prepare screen
5. Future-proof for FullSpectrum virtual extruders (arbitrary RGB, >4 indices)

## Approach: Per-Vertex RGBA in Vertex Buffer

Expand the vertex format from 6 floats (pos+normal) to 10 floats (pos+normal+RGBA). Store a per-triangle extruder index alongside the geometry. Recoloring walks the index array and writes new RGBA values into the buffer — no re-parse, no full GPU re-upload.

### Why this approach

- **Unified path**: Handles per-volume, SEMM paint, and single-color models identically
- **Future-proof**: Full RGBA per vertex supports FullSpectrum blended colors with no palette size limit
- **Fast recolor**: In-place buffer write + `glBufferSubData` partial GPU update
- **Alternatives rejected**:
  - Color index + palette uniform: palette size limit, complex packing, not worth it for 4 extruders (and breaks for FullSpectrum)
  - Multi-draw-call by volume: can't handle per-triangle SEMM paint data

## Design

### 1. Vertex Format & MeshData

**Current**: 6 floats/vertex (pos xyz + normal xyz), stride 24 bytes.
**New**: 10 floats/vertex (pos xyz + normal xyz + color RGBA), stride 40 bytes.

`MeshData` additions:
- `hasPerVertexColor: Boolean` — whether color data is meaningful
- `extruderIndices: ByteArray?` — per-triangle extruder index (0-based). This is the recoloring key.

`MeshData.recolor(colorPalette: List<FloatArray>)`:
- Walks `extruderIndices`, writes RGBA into the float buffer at each triangle's 3 vertices' color slots
- In-place mutation, no allocation
- ~20ms for 1.7M triangles (linear walk, 12 float writes per triangle)

Color assignment during parse:
- **Per-volume**: all triangles in a volume get that volume's extruder index
- **SEMM paint**: each triangle's `paint_color` attribute gives the extruder index (see paint_color format below)
- **Single-color / STL**: all triangles get index 0

**Memory note**: The 204MB vertex buffer for 1.7M-triangle models uses `ByteBuffer.allocateDirect()` (native memory, NOT Java heap). This is separate from the 80MB OOM guard which protects Java heap during XML parsing. Native direct buffers are bounded by total device memory, not the Java heap limit. Acceptable on the Pixel 8a (8GB RAM). `MeshData` (Java-side reference + direct buffer) survives GL context loss — `uploadMesh()` re-uploads to GPU on `onSurfaceCreated`.

### 2. Shader Changes

**model.vert** — new attribute and uniform:
```glsl
layout(location = 2) in vec4 a_Color;
uniform int u_UseVertexColor;  // 1 = per-vertex, 0 = u_Color uniform

// At end of main():
v_Color = (u_UseVertexColor == 1) ? a_Color : u_Color;
```

**model.frag** — use interpolated color:
```glsl
in vec4 v_Color;
fragColor = vec4(v_Color.rgb * v_Intensity, v_Color.a);
```

**u_UseVertexColor toggle**: Wipe tower preview, drag highlight, and models without color data continue using `u_Color` uniform. One shader handles both paths.

**VAO setup** in `uploadMesh()`:
```
attrib 0: 3 floats (pos),    offset 0,  stride 40
attrib 1: 3 floats (normal), offset 12, stride 40
attrib 2: 4 floats (color),  offset 24, stride 40
```

**Buffer usage hint**: Use `GL_DYNAMIC_DRAW` (not `GL_STATIC_DRAW`) since the buffer will receive `glBufferSubData` calls on recolor. This tells the driver to place the buffer in memory optimal for partial updates.

**VBO handle**: `uploadMesh()` must store the VBO handle as a class field (`private var modelVBO = 0`) so `updateColorData()` can reference it for `glBufferSubData`.

**Box/wipe tower VAO compatibility**: The box VAO (`setupBox()`) uses the model shader but only has attribs 0+1 enabled. This is safe — each VAO stores its own attribute enable state, so attrib 2 is only enabled on the model VAO. Set `glVertexAttrib4f(2, 1f, 1f, 1f, 1f)` as a default before drawing the box, so any accidental read gets white rather than garbage.

### 3. ThreeMfMeshParser Changes

**Triangle parsing** — extract color metadata:
- Extract `paint_color` and `mmu_segmentation` attributes from `<triangle>` elements
- Store extruder index per triangle in a `GrowableByteArray` (same pattern as existing `GrowableIntArray`)
- Fast-path: check `line.contains("paint_color")` before attribute extraction (99.9% of triangle lines have no paint attributes)

**`paint_color` attribute format**: Bambu/OrcaSlicer encodes per-triangle extruder assignments as a semicolon-delimited string of facet indices per extruder on the `mmu_segmentation` attribute, or as a direct integer extruder index on `paint_color`. During implementation, inspect actual 3MF test files (Calicube, SEMM test assets) to determine the exact format and write the mapping. The key output is: for each triangle, which extruder index (0-based) it belongs to. If the attribute can't be parsed, fall back to the volume's extruder index.

**Per-volume extruder tracking**:
- Thread the per-object/per-part extruder assignments from `ThreeMfInfo` / `model_settings.config` into `buildMeshData()`
- Triangles without paint attributes inherit their parent volume's extruder index
- Data is already parsed by `ThreeMfParser` — just not connected to the mesh builder today
- **Note on compound objects**: `restructureForMultiColor()` creates compound objects with `<components>`. The mesh parser receives the sanitized file. Each component's `objectid` maps to an extruder via `model_settings.config` (Bambu) or `Slic3r_PE_model.config` (after restructure). The parser must track which component each mesh segment came from to assign the correct extruder index. The component→objectid→extruder mapping from `ThreeMfInfo` is passed into the parse call.

**buildMeshData() changes**:
- Output stride: 6 → 10 floats per vertex
- Write default RGBA after pos+normal for each vertex
- Return `extruderIndices: ByteArray` in `MeshData`

**STL files**: No color data. All triangles get extruder index 0. Recoloring paints everything one color.

### 4. Recoloring Pipeline & ViewModel Integration

```
User changes extruder mapping
  → SlicerViewModel.applyMultiColorAssignments()
  → builds colorPalette from extruder preset colors
  → ModelViewerView.recolorMesh(colorPalette)
  → MeshData.recolor(palette) — in-place buffer write
  → ModelRenderer.updateColorData() — glBufferSubData (partial GPU update)
  → requestRender()
```

Key points:
- `ModelViewerView.recolorMesh(palette: List<FloatArray>)` — new method, called from composable whenever `activeExtruderColors` or `colorMapping` changes
- Initial coloring happens at parse time using current palette — no second pass on first load
- Wipe tower and highlight colors continue using `u_Color` uniform with `u_UseVertexColor = 0`

**Threading safety**: `recolorMesh()` is called from the main thread (composable), but `MeshData.recolor()` mutates the FloatBuffer and `glBufferSubData` must run on the GL thread. Solution: `recolorMesh()` calls `queueEvent { recolor() + glBufferSubData() + requestRender() }` to run entirely on the GL thread. This follows the existing `pendingMesh` pattern where main-thread state is handed off to GL thread via queued work.

**GPU re-upload scope**: `glBufferSubData` re-uploads the entire interleaved buffer (pos+normal+color), not just the color portion, because colors are interleaved (not contiguous). The CPU-side `recolor()` (~20ms) is the bottleneck; the GPU upload of the already-prepared direct FloatBuffer adds ~5-10ms.

**Existing `instanceColors` / `setExtruderColors`**: The per-instance uniform color mechanism remains for placement mode (multiple copies). All copies share the same per-vertex colors from the single VAO — each copy renders identically. `instanceColors` is no longer used for model color (superseded by per-vertex), but remains available for the highlight-on-drag path. In placement mode with multiple copies, the `u_UseVertexColor` flag is set to 1 for all copies (they all show the same multi-color data).

### 5. Single-Color Extruder Picker (F25)

**UI**: Four tappable chips on Prepare screen near scale/copies controls:
```
[E1 ●] [E2 ●] [E3 ●] [E4 ●]
```
- Only shown for single-color models (`colorMapping == null`)
- Colored dots match extruder preset colors
- Selected chip highlighted with border/fill
- Hidden for multi-color models (existing multi-color dialog handles assignment)

**ViewModel**: New `selectedExtruder: StateFlow<Int>` (default 0).
- Tapping a chip updates `selectedExtruder`
- Triggers `recolorMesh()` with new extruder's color at index 0
- Feeds into slicing pipeline: passed as the extruder index in the `setModelInstances()` call (object-level extruder assignment in the native engine). If `selectedExtruder > 0` and support uses a different extruder, wipe tower must be enabled.

### 6. Edge Cases

1. **Large SEMM models (>80MB component files)**: Existing OOM guard skips oversized components. No per-triangle color data for those — fall back to single-color (extruder index 0). Graceful degradation.

2. **Mixed volumes + paint data**: `paint_color` attribute wins if present on a triangle; otherwise inherit volume's extruder index.

3. **Extruder index out of range**: Clamp to last available palette entry.

4. **Recolor during placement mode**: `recolor()` + `glBufferSubData` is fast enough to not interfere with drag interaction.

### 7. Performance

| Metric | Current | After |
|--------|---------|-------|
| Vertex buffer (1.7M tri) | ~122 MB | ~204 MB (+67%) |
| Extruder indices | 0 | ~1.7 MB |
| Parse overhead (non-paint) | baseline | negligible (fast-path skip) |
| Parse overhead (paint) | N/A | +1 string search per triangle |
| Recolor time (1.7M tri) | N/A | ~20 ms |
| GPU update on recolor | N/A | `glBufferSubData` (partial) |

204MB vertex buffer uses native direct memory (not Java heap) — see Section 1 memory note.

## Files Modified

| File | Change |
|------|--------|
| `viewer/MeshData.kt` | Add RGBA color fields, `extruderIndices`, `recolor()` method |
| `viewer/ThreeMfMeshParser.kt` | Parse paint_color/extruder per triangle, 10-float stride, `GrowableByteArray` |
| `viewer/ModelRenderer.kt` | VAO setup for 3 attribs, `u_UseVertexColor` uniform, `updateColorData()`, `glBufferSubData` |
| `viewer/ModelViewerView.kt` | `recolorMesh()` method |
| `assets/shaders/model.vert` | Add `a_Color` attribute, `u_UseVertexColor` uniform |
| `assets/shaders/model.frag` | Use `v_Color` instead of `u_Color` directly |
| `SlicerViewModel.kt` | `selectedExtruder` StateFlow, wire recolor into color change observers |
| `MainActivity.kt` (PrepareScreen) | Extruder picker chips, wire `recolorMesh` to color/mapping changes |

**No new files created.** `GrowableByteArray` is added as a private class inside `ThreeMfMeshParser.kt`, following the existing pattern of `GrowableFloatArray` and `GrowableIntArray`.

## Testing

- Unit: `ThreeMfMeshParser` returns correct extruder indices for multi-volume and paint-data 3MF files
- Unit: `MeshData.recolor()` writes correct RGBA values for given palette
- Unit: `GrowableByteArray` growth and access
- Instrumented: Load multi-color 3MF, verify `MeshData.hasPerVertexColor == true` and `extruderIndices` populated
- E2E: Load Calicube (per-volume), verify distinct colors visible in preview
- E2E: Change extruder mapping, verify preview updates
- E2E: Load STL, pick different extruder, verify color changes
