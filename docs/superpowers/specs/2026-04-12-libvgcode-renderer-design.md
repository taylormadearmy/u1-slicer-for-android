# libvgcode-style G-code Renderer — Design Spec

## Goal

Replace the current per-segment instanced hexagonal tube renderer with libvgcode's
view-adaptive ribbon approach. Target: match SliceBeam's visual quality on Android.

## Why the Current Approach Looks Bad

The current renderer draws each G-code move as an independent hexagonal tube (36 vertices
via `gl_VertexID`, 12 floats per instance). With 143k+ moves, this produces disconnected
tubes that never visually merge — gaps at corners, visible seams between adjacent segments,
and no smooth surface appearance.

libvgcode solves this with:
1. **View-adaptive ribbons**: 8-vertex cross-section that reorients per-frame to always
   face the camera. Adjacent ribbons visually merge because you never see them edge-on.
2. **Angle-based beveled caps**: At each joint, the cap geometry is angled based on the
   turning angle between segments, filling corner gaps perfectly.
3. **FIX_TWISTING**: Uses the closer endpoint's camera direction for consistent orientation
   across a segment, preventing visual twisting artifacts.

## Architecture

### Shader Approach: 2D Textures with texelFetch (ES 3.0)

The actual `ShadersES.hpp` from libvgcode uses `#version 300 es` with **2D texture lookups**
(`sampler2D` + `texelFetch`), not SSBOs or TBOs. This is what SliceBeam ships on Android.

Four GPU textures hold all per-vertex data:

| Texture | GL Internal Format | GL Format | GL Type | Content |
|---------|-------------------|-----------|---------|---------|
| `position_tex` | `GL_RGB32F` | `GL_RGB` | `GL_FLOAT` | vec3: x, y, z per vertex |
| `hwa_tex` | `GL_RGB32F` | `GL_RGB` | `GL_FLOAT` | vec3: height, width, angle per vertex |
| `color_tex` | `GL_R32F` | `GL_RED` | `GL_FLOAT` | float: packed RGB as int-to-float |
| `segment_index_tex` | `GL_R32UI` | `GL_RED_INTEGER` | `GL_UNSIGNED_INT` | uint: vertex index for segment start |

A fifth texture is not needed — `segment_index_tex` maps `gl_InstanceID` to the start
vertex of each segment. The end vertex is always `start + 1`.

### Segment Template VAO

A single VBO containing 24 float values representing 8 triangles (24 vertices).
Each value is a vertex ID (0–7) that the vertex shader uses to determine position
within the ribbon cross-section:

```
Triangle fan front cap:  (0,1,2), (0,2,3)
Body quads:              (0,3,4), (0,4,5), (0,5,6), (0,6,1)
Triangle fan back cap:   (5,4,7), (5,7,6)
```

Draw call: `glDrawArraysInstanced(GL_TRIANGLES, 0, 24, segmentCount)`

### Vertex Shader Geometry Generation

Ported from `ShadersES.hpp` `Segments_Vertex_Shader_ES`. For each of the 24 vertices
per instance:

1. Read segment index from `segment_index_tex` using `gl_InstanceID + instance_offset`
2. Fetch positions of both endpoints (`id_a`, `id_b = id_a + 1`) from `position_tex`
3. Compute line direction, perpendicular, and up vectors
4. Determine horizontal vs vertical view based on camera angle to segment
5. Position vertex using height/width from `hwa_tex`, with view-adaptive sign flipping
6. For cap vertices (2 and 7): apply angle-based bevel from `hwa_tex.z`
7. Compute normal as `normalize(pos - endpoint_pos)` for lighting
8. Apply Phong lighting model (ambient + 2 directional diffuse + specular + emission)
9. Decode color from `color_tex` packed float

Key shader modification from ShadersES.hpp: add `uniform int instance_offset;` and use
`gl_InstanceID + instance_offset` for the segment_index_tex lookup. This enables
per-layer draw calls without rebuilding the segment index texture.

### Fragment Shader

Trivial pass-through: `fragment_color = vec4(color, 1.0);`

## Data Model

### GcodeSegmentPacker

New file replacing `GcodeInstancePacker.kt`. Pure Kotlin, no Android dependencies
(unit-testable on JVM).

**Input**: `ParsedGcode` (layers of `GcodeMove`)

**Output**: `SegmentPackResult` containing:
- `positions: FloatArray` — 3 floats per vertex (x, y, z)
- `heightsWidthsAngles: FloatArray` — 3 floats per vertex (h, w, angle)
- `extruderColors: FloatArray` — 1 float per vertex (packed RGB)
- `featureColors: FloatArray` — 1 float per vertex (packed RGB, feature-type mode)
- `segmentIndices: IntArray` — 1 int per segment (index into vertex arrays)
- `totalVertices: Int`
- `totalSegments: Int`
- `layerRanges: List<SegmentLayerRange>` — per-layer (firstSegment, segmentCount)
- `texWidth: Int`, `texHeight: Int` — computed texture dimensions

### Chain Construction Algorithm

Walk all layers and moves, building vertex chains from consecutive EXTRUDE moves:

```
For each layer:
  Record layerFirstSegment
  For each move in layer:
    If EXTRUDE and non-zero-length:
      If starting new chain (first move, or previous was travel/different layer):
        Add start vertex (x0, y0, z) with angle=0 (pointy cap start)
        chainStartIdx = currentVertexIndex
      Add end vertex (x1, y1, z)
      Add segment (chainStartIdx + offset → chainStartIdx + offset + 1)
      Compute angle at shared vertex from previous segment direction
    Else (TRAVEL):
      If chain is open:
        Set last vertex angle=0 (pointy cap end)
        Close chain
  If chain still open at layer end:
    Set last vertex angle=0
    Close chain
  Record SegmentLayerRange(layerFirstSegment, segmentsInLayer)
```

### Vertex Data Details

**Position (vec3 per vertex)**:
- For extrusion moves: `z = layer.z - 0.5 * height` (render at centerline, not top)
- For chain start: `(move.x0, move.y0, z)`
- For subsequent vertices: `(move.x1, move.y1, z)`

**Height/Width/Angle (vec3 per vertex)**:
- `height = 0.36f` (extrusion height, 2 × current HALF_HEIGHT)
- `width = 0.56f` (extrusion width, 2 × current HALF_WIDTH)
- `angle = atan2(cross2D(prevDir, nextDir), dot(prevDir, nextDir))`
  - Chain start: angle = 0.0 (pointy cap)
  - Chain end: angle = 0.0 (pointy cap)
  - Interior vertex: turning angle between incoming and outgoing segments

**Color (packed float per vertex)**:
- Pack: `((r_byte << 16) | (g_byte << 8) | b_byte).toFloat()`
- Two arrays maintained: one for extruder colors, one for feature-type colors
- Layer brightness gradient applied: `brightness = 0.45 + 0.55 * (layerIdx / (totalLayers - 1))`
- Brightness is baked into the packed color (multiply RGB before packing)

**Segment Index (uint per segment)**:
- Points to the start vertex of each segment
- Segments are ordered by layer (all segments for layer 0, then layer 1, etc.)

### Texture Dimensions

Textures are 2D. Compute dimensions from vertex/segment count:

```kotlin
fun computeTexDimensions(count: Int): Pair<Int, Int> {
    if (count == 0) return Pair(1, 1)
    val maxSize = 4096  // conservative; could query GL_MAX_TEXTURE_SIZE
    val width = minOf(count, maxSize)
    val height = (count + width - 1) / width
    return Pair(width, height)
}
```

For 2M vertices: 4096 × 489 = 2,002,944 texels. Well within limits.

## Renderer Changes

### GcodeRenderer Rewrite

The renderer is rewritten but keeps the same class structure and public API.

**New GL resources**:
- `segmentTemplateVAO/VBO` — 24-float VBO for vertex IDs (created once in onSurfaceCreated)
- `positionTexId` — GL_TEXTURE_2D for position data
- `hwaTexId` — GL_TEXTURE_2D for height/width/angle data
- `colorTexId` — GL_TEXTURE_2D for active color mode
- `extruderColorTexId` — GL_TEXTURE_2D for extruder colors (cached)
- `featureColorTexId` — GL_TEXTURE_2D for feature colors (cached)
- `segmentIndexTexId` — GL_TEXTURE_2D for segment indices

**uploadGcode()**: Pack via `GcodeSegmentPacker`, upload 4 textures.

**drawToolpaths()**: For each visible layer range:
1. Set `instance_offset` uniform to `layerRange.firstSegment`
2. Call `glDrawArraysInstanced(GL_TRIANGLES, 0, 24, layerRange.segmentCount)`

**Color mode toggle**: Swap `colorTexId` between `extruderColorTexId` and
`featureColorTexId`. No data rebuild needed.

**Extruder color update**: Rebuild extruder color texture only (4 bytes/vertex — fast).

### Shader Program

New shader files in `assets/shaders/`:
- `segment.vert` — ported from ShadersES.hpp with `instance_offset` uniform added
- `segment.frag` — trivial pass-through

The existing `toolpath.vert/frag` are kept for travel line rendering.
The old `toolpath_instanced.vert/frag` are deleted.

### GL State

Segments must be drawn with `glDisable(GL_CULL_FACE)` — the view-adaptive ribbons
are essentially flat and need to be visible from both sides. Re-enable culling for
other draw calls if needed.

### Camera Position Uniform

The shader requires `camera_position` (vec3) for FIX_TWISTING and view-adaptive
orientation. This is extracted from the view matrix inverse without modifying Camera.kt:

```kotlin
val v = camera.viewMatrix
val camX = -(v[0]*v[12] + v[1]*v[13] + v[2]*v[14])
val camY = -(v[4]*v[12] + v[5]*v[13] + v[6]*v[14])
val camZ = -(v[8]*v[12] + v[9]*v[13] + v[10]*v[14])
```

### Travel Lines

Unchanged. Keep existing GL_LINES approach with `toolpath.vert/frag`.

## What Changes / What Stays

| Component | Status | Notes |
|-----------|--------|-------|
| `GcodeParser.kt` | Keep | No changes |
| `ParsedGcode`, `GcodeMove` | Keep | No changes |
| `Camera.kt` | Keep | No changes |
| `BedDrawable.kt` | Keep | No changes |
| `GcodeViewerView.kt` | Keep | Public API unchanged |
| `GcodeViewer3DScreen.kt` | Keep | No changes |
| `MainActivity.kt` (gcode UI) | Keep | isPreviewSimplified toast unchanged |
| `GcodeInstancePacker.kt` | Delete | Replaced by GcodeSegmentPacker |
| `GcodeRenderer.kt` | Rewrite | Same class, new internals |
| `toolpath_instanced.vert/frag` | Delete | Replaced by segment.vert/frag |
| `toolpath.vert/frag` | Keep | Travel lines unchanged |
| `GcodeRendererGeometryTest.kt` | Rewrite | Tests for new packer |

## Large File Handling

### Existing Safeguards (preserved)

1. **GcodeParser 2M move cap** with stride-based sampling — unchanged
2. **`isPreviewSimplified` toast** — unchanged
3. **`ParsedGcode._totalMoves`** tracking — unchanged

### Memory Budget (new renderer)

For 2M moves (worst case after stride sampling):

| Resource | Current | New | Change |
|----------|---------|-----|--------|
| Instance/vertex data | 96 MB (2M × 48B) | 56 MB (2M × 28B) | -42% |
| Segment index | — | 8 MB (2M × 4B) | new |
| Color texture (×2) | — | 8 MB each | new |
| **Total GPU** | **~96 MB** | **~80 MB** | **-17%** |

The per-vertex cost breakdown:
- Position: 12 bytes
- HWA: 12 bytes
- Color: 4 bytes
- = 28 bytes/vertex (vs 48 bytes/instance currently)

Plus segment index at 4 bytes/segment and second color texture at 4 bytes/vertex.

### Texture Size Limits

2M vertices → 4096×489 texture. ES 3.0 guarantees `GL_MAX_TEXTURE_SIZE >= 2048`.
Most Android devices support 4096 or higher. We query `GL_MAX_TEXTURE_SIZE` at
runtime and adapt dimensions accordingly.

If a device has `GL_MAX_TEXTURE_SIZE = 2048`: 2048×977 = 2,001,920 texels. Still fits.

### No Additional Fallback Needed

The existing 2M move cap ensures textures stay within GPU limits on all devices.
No new fallback mechanism is needed beyond what the parser already provides.

## Multicolor Support

Extruder coloring works identically to current: each vertex gets a color based on
`move.extruder` (0–3). The packed-float encoding stores full 24-bit RGB, so any
color is representable.

Color update flow:
1. User changes extruder colors → `setExtruderColors(hexColors)`
2. Renderer rebuilds extruder color texture (4 bytes × vertexCount — fast)
3. If in extruder color mode, the new texture is immediately visible

## Testing

### New Unit Tests (GcodeRendererGeometryTest.kt)

Tests for `GcodeSegmentPacker`:

1. **Single move → 2 vertices, 1 segment**: Verify vertex count, segment index
2. **Consecutive extrude moves → shared vertices**: 3 moves → 4 vertices, 3 segments
3. **Travel breaks chain**: Extrude, travel, extrude → 2 chains, no segment across gap
4. **Angle computation at corners**: 90° turn → angle ≈ π/2; straight → angle ≈ 0
5. **Chain start/end angles are zero**: Pointy caps at chain boundaries
6. **Color encoding round-trip**: Pack RGB → float → unpack → matches original
7. **Layer ranges tracked correctly**: Multi-layer with per-layer segment counts
8. **Feature-type coloring**: Feature type → correct color in feature color array
9. **Brightness gradient**: Bottom layer dimmer, top layer full brightness
10. **Zero-length moves filtered**: Degenerate moves skipped
11. **Large dataset stress test**: 400k moves, verify no crash or limit
12. **Empty gcode returns empty result**: Edge case
13. **Texture dimensions**: Verify computed dimensions fit vertex count
14. **Z-offset for extrusion**: Position z = layer.z - 0.5 * height
15. **Layer boundary breaks chain**: Moves in different layers don't share vertices

## Lighting Model

Ported from libvgcode (matches SliceBeam exactly):

```
ambient = 0.3
emission = 0.15
light_top: direction=(-0.457, 0.457, 0.762), diffuse=0.48, specular=0.075, shininess=20
light_front: direction=(0.699, 0.140, 0.699), diffuse=0.18
intensity = ambient + top_diffuse + front_diffuse + top_specular + emission
final_color = base_color * intensity
```

This replaces the current lighting (ambient=0.35, top_diffuse=0.75, front_diffuse=0.30,
specular=0.20, shininess=32) which was tuned for hexagonal tubes.
