# G-code 3D Preview Quality — Investigation Summary

## Problem

The G-code 3D preview looks rough, jagged, and broken compared to two reference implementations:
- **SliceBeam** (Android, native C++ via libvgcode) — looks like the actual model
- **U1 Slicer Bridge** (web, Three.js + gcode-preview lib) — also looks like the model

Our preview looks like an approximation regardless of what we try.

## What We Tried (all failed to match reference quality)

### Approach 1: Per-segment instanced rendering (original + many tweaks)
Each of ~143k G-code moves rendered as an independent tube (36 verts each via GPU instancing). Tried:
- Smooth normals, per-fragment lighting, gamma, fresnel
- Flat-top hex cross-section (6 faces)
- Tube dimension tuning (0.5x, 0.55x, 0.60x, 0.70x line width)
- Miter joints with angle computation (14-float instance format)
- Port of SliceBeam's view-adaptive ribbon shader
- Various lighting constants

**Why it failed**: 143k disconnected tubes will never look solid. No shared vertices, no joint smoothing possible.

### Approach 2: CPU polyline meshing (current branch `feature/polyline-gcode-preview-v2`)
Group connected moves into polylines, sweep continuous tube mesh along each. Tried:
- 6-vertex, 8-vertex, 12-vertex cross-sections
- Sharp corner breaking (>60° angle splits polyline)
- Chaikin subdivision for path smoothing
- Various dimension and lighting tweaks

**Why it failed**: 
- Chaikin smoothing on 143k moves → OOM (even 1 iteration with 8-vertex ring uses ~112MB)
- Without smoothing, the path is still raw G1 zigzags — the core roughness remains
- Memory scales poorly — small benchy already hits limits, real models would be impossible
- The fundamental issue: G-code has hundreds of tiny slightly-different-angle G1 segments per wall. Any approach that renders these directly will look rough.

## The Two Approaches That Actually Work

### SliceBeam — libvgcode (C++, AGPL v3)
- Source: `/c/Users/kevin/projects/u1-android-bambu-slicer/app/src/main/jni/libvgcode/`
- **Key files**: `src/Shaders.hpp`, `src/SegmentTemplate.cpp`, `src/ViewerImpl.cpp`, `include/PathVertex.hpp`
- **Architecture**: 
  - All data in GPU Texture Buffer Objects (TBOs) — position, height/width/angle, color per vertex
  - 8 vertices per segment, 24 indices (flat view-adaptive ribbons)
  - Vertex shader fetches data via `texelFetch()` from TBOs
  - View-adaptive: ribbon always faces camera, rotates per-frame
  - Angle-based beveled caps at joints between segments
  - Per-segment rendering (NOT polyline) — but looks smooth because:
    1. View-adaptive ribbons hide segment boundaries (you never see them edge-on)
    2. Beveled caps fill corner gaps perfectly
    3. The flat ribbon approach means adjacent paths visually merge into solid surfaces
- **Memory**: Very efficient — TBOs store raw data, vertex shader generates geometry on the fly
- **GL requirement**: Uses `samplerBuffer` / `texelFetch` — available in **OpenGL 3.1+ / OpenGL ES 3.2**
- **Problem for us**: Our app targets ES 3.0 (minSdk 26). `samplerBuffer` is NOT in ES 3.0. It's in ES 3.2 (API 24+, but not all devices support it).

**Could we use libvgcode directly via JNI?**
- It's already compiled in the SliceBeam project at `/c/Users/kevin/projects/u1-android-bambu-slicer/app/src/main/jni/libvgcode/`
- It uses desktop GL features (TBOs, `#version 150` shaders)
- Would need shader adaptation for ES 3.0 — replace TBOs with SSBO or UBO
- ES 3.1 (API 21+) has SSBOs which could replace TBOs. Pixel 8a supports ES 3.2.
- **Feasibility**: Medium-high. The C++ library is self-contained. Main work is shader adaptation.
- **License**: AGPL v3 — requires open-sourcing our app or getting a commercial license from Prusa

**Could we port just the shader approach (not the C++ lib)?**
- Yes. Replace TBOs with Shader Storage Buffer Objects (SSBOs, ES 3.1) or large Uniform Buffer Objects (UBOs, ES 3.0 with 16KB limit)
- SSBOs are the clean approach — same `texelFetch`-like random access, available on ES 3.1+
- Our minSdk 26 (Android 8.0) guarantees ES 3.1 support
- This is essentially what we tried with the ribbon shader port — but we used per-instance attributes instead of SSBOs, which limited us to one segment's data per instance

### U1 Slicer Bridge — gcode-preview (Three.js)
- Source: `/c/Users/kevin/projects/u1-slicer-bridge/apps/web/viewer.js`
- **Architecture**:
  - Three.js `GCodePreview.init()` with `renderTubes: true, extrusionWidth: 0.45`
  - For files <8MB: full tube geometry (TubeGeometry)
  - For files 8-50MB: line rendering (GL_LINES)
  - For files >50MB: server-side 2D PNG preview
  - Custom lighting: directional (1.2) + ambient (0.4)
- **Why it looks smooth**: Three.js TubeGeometry uses spline interpolation — it doesn't render raw G1 kinks, it computes a smooth curve through the points
- **Memory**: Handled by Three.js with automatic LOD and the tiered approach

**Could we embed this in a WebView?**
- Yes. Wrap a WebView with the gcode-preview library
- Proven quality — it's what Bridge uses
- Trade-offs: WebView memory overhead (~50-100MB), no native GL integration, slower than native
- **Feasibility**: High. Minimal code, proven library.
- **Performance**: WebView startup ~1-2s, rendering performance adequate for preview

## Options for a Fresh Start

### Option A: Port libvgcode shader approach using SSBOs (ES 3.1)
- Keep our parser, pack data into SSBOs instead of per-instance attributes
- Port the exact vertex shader from libvgcode, replacing `samplerBuffer` with `buffer` (SSBO)
- 8 vertices per segment, view-adaptive ribbons, angle-based bevels
- Memory-efficient: raw data in SSBOs, geometry generated in vertex shader
- **Effort**: Medium (shader rewrite + SSBO upload, ~1-2 days)
- **Risk**: Medium (SSBO performance varies by GPU vendor)
- **Quality**: Should match SliceBeam exactly (same algorithm)

### Option B: WebView + gcode-preview library
- Embed a WebView, load gcode-preview.js, pass G-code data via JS bridge
- Three.js handles all rendering
- **Effort**: Low (~half day)
- **Risk**: Low (proven in Bridge)
- **Quality**: Matches Bridge exactly
- **Trade-off**: WebView overhead, can't share camera state with native views

### Option C: Integrate libvgcode directly via JNI
- Compile libvgcode as a JNI library, call from Kotlin
- Need to adapt shaders for ES 3.1/3.2
- Get the full Prusa rendering pipeline including all optimizations
- **Effort**: High (build system integration, shader adaptation, JNI bindings)
- **Risk**: Medium (AGPL license, GL compatibility)
- **Quality**: Best possible (same as PrusaSlicer)

### Option D: Fix current polyline mesher with memory budget
- Add a move budget per layer (decimate if over limit)
- Use simpler cross-section (4 verts = flat quad) for infill, full ring only for outer walls
- Skip Chaikin smoothing entirely — accept the raw G1 path quality
- **Effort**: Low
- **Risk**: Low
- **Quality**: Worse than A/B/C. This is what we have now, essentially.

## License

Our app is AGPL v3 (same as OrcaSlicer/PrusaSlicer/libvgcode). **No licensing issue** with integrating libvgcode directly.

## Multicolor

libvgcode already supports multicolor natively — `PathVertex` has `extruder_id` and `color_id` fields. Our parser already tracks extruder per move. Multicolor rendering is just extruder→color mapping, which libvgcode handles in its color encoding. This means Option C (direct integration) gets us multicolor for free.

## Recommendation

**Option C** (integrate libvgcode directly via JNI) is the best path:
- Same visual quality as SliceBeam — proven, ships in PrusaSlicer
- License compatible (both AGPL v3)
- Multicolor support built-in (`extruder_id` per vertex)
- ES shaders already exist in `ShadersES.hpp` (SSBO-based, ES 3.1)
- Memory-efficient (GPU texture/storage buffers, vertex shader generates geometry)
- Already compiled in the SliceBeam project — can reference build config
- Most work upfront but zero ongoing maintenance of custom rendering code

**Option A** (clean-room SSBO port) is the fallback if direct integration proves too complex. Same rendering quality, but we maintain the shader code ourselves.

The key insight we missed in our previous ribbon shader port: we used **per-instance attributes** which limit each instance to its own data. libvgcode uses **TBOs/SSBOs** which give each vertex shader invocation random access to ALL segments' data — this is what enables the FIX_TWISTING optimization and proper view-adaptive geometry.

## Key Files for Reference

- libvgcode shaders: `/c/Users/kevin/projects/u1-android-bambu-slicer/app/src/main/jni/libvgcode/src/Shaders.hpp`
- libvgcode segment template: `/c/Users/kevin/projects/u1-android-bambu-slicer/app/src/main/jni/libvgcode/src/SegmentTemplate.cpp`
- libvgcode viewer: `/c/Users/kevin/projects/u1-android-bambu-slicer/app/src/main/jni/libvgcode/src/ViewerImpl.cpp`
- libvgcode ES shaders: `/c/Users/kevin/projects/u1-android-bambu-slicer/app/src/main/jni/libvgcode/src/ShadersES.hpp` (OpenGL ES version!)
- Bridge viewer: `/c/Users/kevin/projects/u1-slicer-bridge/apps/web/viewer.js`
- Our parser: `app/src/main/java/com/u1/slicer/gcode/GcodeParser.kt`
- Our renderer: `app/src/main/java/com/u1/slicer/viewer/GcodeRenderer.kt`
- Our current mesher (branch): `app/src/main/java/com/u1/slicer/viewer/GcodePolylineMesher.kt`

## Critical Detail: libvgcode HAS an ES version!

`ShadersES.hpp` in the libvgcode source contains OpenGL ES 3.1 shaders that replace TBOs with SSBOs. This is exactly what we need — it's already been adapted for mobile by Prusa. The ES shaders use `layout(std430) buffer` instead of `samplerBuffer`.
