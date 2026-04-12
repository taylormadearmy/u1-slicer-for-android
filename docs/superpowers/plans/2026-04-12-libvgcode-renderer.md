# libvgcode-style G-code Renderer — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the per-segment instanced hex-tube G-code renderer with libvgcode's view-adaptive ribbon approach to match SliceBeam visual quality.

**Architecture:** Port Prusa's ShadersES.hpp vertex shader (8-vertex view-adaptive ribbons with angle-based beveled caps) into our Kotlin + GLSL pipeline. Data stored in 2D textures (ES 3.0), fetched via `texelFetch`. New `GcodeSegmentPacker` converts `ParsedGcode` into vertex chains with shared endpoints and turning angles.

**Tech Stack:** Kotlin, OpenGL ES 3.0, GLSL `#version 300 es`, 2D textures with `texelFetch`

**Spec:** `docs/superpowers/specs/2026-04-12-libvgcode-renderer-design.md`

---

## File Map

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `app/src/main/java/com/u1/slicer/viewer/GcodeSegmentPacker.kt` | Convert ParsedGcode → texture-ready arrays |
| Create | `app/src/main/assets/shaders/segment.vert` | View-adaptive ribbon vertex shader (port from ShadersES.hpp) |
| Create | `app/src/main/assets/shaders/segment.frag` | Trivial fragment pass-through |
| Rewrite | `app/src/main/java/com/u1/slicer/viewer/GcodeRenderer.kt` | Texture-based rendering with segment instancing |
| Rewrite | `app/src/test/java/com/u1/slicer/viewer/GcodeRendererGeometryTest.kt` | Tests for GcodeSegmentPacker |
| Delete | `app/src/main/java/com/u1/slicer/viewer/GcodeInstancePacker.kt` | Old per-instance data packer |
| Delete | `app/src/main/assets/shaders/toolpath_instanced.vert` | Old hex-tube vertex shader |
| Delete | `app/src/main/assets/shaders/toolpath_instanced.frag` | Old hex-tube fragment shader |

**Unchanged files:** `GcodeViewerView.kt`, `Camera.kt`, `BedDrawable.kt`, `GcodeParser.kt`, `GcodeLayer.kt`, `GcodeViewer3DScreen.kt`, `toolpath.vert`, `toolpath.frag`, `ShaderProgram.kt`

---

### Task 1: Branch Setup

**Files:** None (git only)

- [ ] **Step 1: Create feature branch**

```bash
cd /c/Users/kevin/projects/u1-slicer-orca
git checkout -b feature/libvgcode-renderer
```

- [ ] **Step 2: Commit plan and spec**

```bash
git add docs/superpowers/plans/2026-04-12-libvgcode-renderer.md docs/superpowers/specs/2026-04-12-libvgcode-renderer-design.md
git commit -m "docs: add libvgcode renderer design spec and implementation plan"
```

---

### Task 2: GcodeSegmentPacker

**Files:**
- Create: `app/src/main/java/com/u1/slicer/viewer/GcodeSegmentPacker.kt`

This is the core data transformation: `ParsedGcode` (layers of moves) → four flat arrays ready for GPU texture upload. Pure Kotlin, no Android dependencies, fully unit-testable.

- [ ] **Step 1: Write GcodeSegmentPacker.kt**

```kotlin
package com.u1.slicer.viewer

import com.u1.slicer.gcode.MoveType
import com.u1.slicer.gcode.ParsedGcode
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

data class SegmentLayerRange(val firstSegment: Int, val segmentCount: Int)

data class SegmentPackResult(
    val positions: FloatArray,
    val heightsWidthsAngles: FloatArray,
    val extruderColors: FloatArray,
    val featureColors: FloatArray,
    val segmentIndices: IntArray,
    val totalVertices: Int,
    val totalSegments: Int,
    val layerRanges: List<SegmentLayerRange>
)

object GcodeSegmentPacker {

    const val HEIGHT = 0.36f
    const val WIDTH = 0.56f

    /**
     * Encode RGB (0-1 range) with brightness into a single float for GPU texture.
     * Format: ((R_byte << 16) | (G_byte << 8) | B_byte) as float.
     * Matches libvgcode's color encoding decoded in the vertex shader.
     */
    fun encodeColor(r: Float, g: Float, b: Float, brightness: Float = 1f): Float {
        val ri = ((r * brightness).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val gi = ((g * brightness).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val bi = ((b * brightness).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return ((ri shl 16) or (gi shl 8) or bi).toFloat()
    }

    /** Decode packed color float back to (R, G, B) bytes 0-255. For testing. */
    fun decodeColor(packed: Float): Triple<Int, Int, Int> {
        val c = (packed + 0.5f).toInt()
        return Triple((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
    }

    /**
     * Compute 2D texture dimensions that fit [count] texels.
     * Width = min(count, maxTexSize), height = ceil(count / width).
     */
    fun computeTexDimensions(count: Int, maxTexSize: Int = 4096): Pair<Int, Int> {
        if (count <= 0) return Pair(1, 1)
        val w = min(count, maxTexSize)
        val h = (count + w - 1) / w
        return Pair(w, h)
    }

    /**
     * Pack parsed G-code into arrays ready for GPU texture upload.
     *
     * Consecutive EXTRUDE moves form **chains** sharing vertices at endpoints.
     * Chains break at travel moves and layer boundaries. At each shared vertex,
     * the turning angle between segments is computed for beveled cap geometry.
     * Chain start/end vertices get angle = 0 (pointy caps).
     *
     * Two color arrays are produced: one for extruder-based coloring, one for
     * feature-type coloring. The renderer swaps between them without re-packing.
     */
    fun pack(
        gcode: ParsedGcode,
        extruderPalette: Array<FloatArray>,
        featurePalette: Array<FloatArray>
    ): SegmentPackResult {
        val totalLayers = gcode.layers.size
        if (totalLayers == 0) return emptyResult()

        var totalExtrudeMoves = 0
        for (layer in gcode.layers) {
            for (move in layer.moves) {
                if (move.type == MoveType.EXTRUDE) totalExtrudeMoves++
            }
        }
        if (totalExtrudeMoves == 0) return emptyResult()

        // Worst case: every move is its own chain → 2 vertices per move.
        // Typical: chains share endpoints → ~N+chainCount vertices.
        val maxVerts = totalExtrudeMoves * 2
        val pos = FloatArray(maxVerts * 3)
        val hwa = FloatArray(maxVerts * 3)
        val extCol = FloatArray(maxVerts)
        val featCol = FloatArray(maxVerts)
        val segIdx = IntArray(totalExtrudeMoves)

        var vc = 0          // vertex count
        var sc = 0          // segment count
        var chainOpen = false
        var prevDx = 0f
        var prevDy = 0f
        val layerRanges = mutableListOf<SegmentLayerRange>()

        for ((layerIdx, layer) in gcode.layers.withIndex()) {
            val layerFirstSeg = sc
            val brightness = if (totalLayers <= 1) 1f
                else 0.45f + 0.55f * (layerIdx.toFloat() / (totalLayers - 1))
            val z = layer.z - 0.5f * HEIGHT

            // Layer boundary always breaks chains
            if (chainOpen) {
                hwa[(vc - 1) * 3 + 2] = 0f
                chainOpen = false
            }

            for (move in layer.moves) {
                if (move.type != MoveType.EXTRUDE) {
                    if (chainOpen) {
                        hwa[(vc - 1) * 3 + 2] = 0f
                        chainOpen = false
                    }
                    continue
                }

                val dx = move.x1 - move.x0
                val dy = move.y1 - move.y0
                val len = sqrt(dx * dx + dy * dy)
                if (len < 0.001f) continue

                val dirX = dx / len
                val dirY = dy / len

                if (!chainOpen) {
                    // Start vertex (pointy cap: angle = 0)
                    val vi = vc * 3
                    pos[vi] = move.x0; pos[vi + 1] = move.y0; pos[vi + 2] = z
                    hwa[vi] = HEIGHT; hwa[vi + 1] = WIDTH; hwa[vi + 2] = 0f
                    val ec = extruderPalette[move.extruder.coerceIn(0, extruderPalette.size - 1)]
                    val fc = featurePalette[move.featureType.toInt().coerceIn(0, featurePalette.size - 1)]
                    extCol[vc] = encodeColor(ec[0], ec[1], ec[2], brightness)
                    featCol[vc] = encodeColor(fc[0], fc[1], fc[2], brightness)
                    vc++
                    chainOpen = true
                } else {
                    // Update turning angle at shared vertex
                    val cross = prevDx * dirY - prevDy * dirX
                    val dot = prevDx * dirX + prevDy * dirY
                    hwa[(vc - 1) * 3 + 2] = atan2(cross, dot)
                }

                // End vertex
                val vi = vc * 3
                pos[vi] = move.x1; pos[vi + 1] = move.y1; pos[vi + 2] = z
                hwa[vi] = HEIGHT; hwa[vi + 1] = WIDTH; hwa[vi + 2] = 0f
                val ec = extruderPalette[move.extruder.coerceIn(0, extruderPalette.size - 1)]
                val fc = featurePalette[move.featureType.toInt().coerceIn(0, featurePalette.size - 1)]
                extCol[vc] = encodeColor(ec[0], ec[1], ec[2], brightness)
                featCol[vc] = encodeColor(fc[0], fc[1], fc[2], brightness)

                segIdx[sc] = vc - 1   // segment: prev vertex → this vertex
                sc++
                vc++

                prevDx = dirX
                prevDy = dirY
            }

            layerRanges.add(SegmentLayerRange(layerFirstSeg, sc - layerFirstSeg))
        }

        if (chainOpen) {
            hwa[(vc - 1) * 3 + 2] = 0f
        }

        return SegmentPackResult(
            positions = pos.copyOf(vc * 3),
            heightsWidthsAngles = hwa.copyOf(vc * 3),
            extruderColors = extCol.copyOf(vc),
            featureColors = featCol.copyOf(vc),
            segmentIndices = segIdx.copyOf(sc),
            totalVertices = vc,
            totalSegments = sc,
            layerRanges = layerRanges
        )
    }

    private fun emptyResult() = SegmentPackResult(
        FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0),
        IntArray(0), 0, 0, emptyList()
    )
}
```

- [ ] **Step 2: Verify compilation**

```bash
cd /c/Users/kevin/projects/u1-slicer-orca
./gradlew compileDebugKotlin --no-daemon 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/u1/slicer/viewer/GcodeSegmentPacker.kt
git commit -m "feat: add GcodeSegmentPacker for libvgcode texture data format"
```

---

### Task 3: Unit Tests for GcodeSegmentPacker

**Files:**
- Rewrite: `app/src/test/java/com/u1/slicer/viewer/GcodeRendererGeometryTest.kt`

All tests target `GcodeSegmentPacker` — the pure-Kotlin packer that replaces `GcodeInstancePacker`.

- [ ] **Step 1: Write GcodeRendererGeometryTest.kt**

```kotlin
package com.u1.slicer.viewer

import com.u1.slicer.gcode.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class GcodeRendererGeometryTest {

    private val extruderPalette = arrayOf(
        floatArrayOf(1.0f, 0.6f, 0.0f, 1.0f),  // T0: orange
        floatArrayOf(0.2f, 0.7f, 1.0f, 1.0f),  // T1: blue
        floatArrayOf(0.0f, 0.9f, 0.4f, 1.0f),  // T2: green
        floatArrayOf(0.9f, 0.2f, 0.5f, 1.0f)   // T3: pink
    )

    private val featurePalette = arrayOf(
        floatArrayOf(1.00f, 0.85f, 0.00f, 1.0f),  // OUTER_WALL
        floatArrayOf(0.53f, 0.81f, 0.92f, 1.0f),  // INNER_WALL
        floatArrayOf(0.30f, 0.71f, 0.68f, 1.0f),  // SPARSE_INFILL
        floatArrayOf(0.40f, 0.73f, 0.42f, 1.0f),  // SOLID_INFILL
        floatArrayOf(0.00f, 0.74f, 0.83f, 1.0f),  // TOP_SURFACE
        floatArrayOf(0.00f, 0.59f, 0.53f, 1.0f),  // BOTTOM_SURFACE
        floatArrayOf(0.67f, 0.28f, 0.74f, 1.0f),  // SUPPORT
        floatArrayOf(0.81f, 0.58f, 0.85f, 1.0f),  // SUPPORT_INTERFACE
        floatArrayOf(1.00f, 0.25f, 0.51f, 1.0f),  // PRIME_TOWER
        floatArrayOf(1.00f, 0.44f, 0.26f, 1.0f),  // BRIDGE
        floatArrayOf(0.69f, 0.75f, 0.76f, 1.0f),  // SKIRT
        floatArrayOf(0.62f, 0.62f, 0.62f, 1.0f)   // OTHER
    )

    private fun makeGcode(vararg layerMoves: List<GcodeMove>): ParsedGcode {
        val layers = layerMoves.mapIndexed { i, moves ->
            GcodeLayer(i, (i + 1) * 0.2f, moves)
        }
        return ParsedGcode(layers)
    }

    private fun pack(gcode: ParsedGcode) =
        GcodeSegmentPacker.pack(gcode, extruderPalette, featurePalette)

    // --- Color encoding ---

    @Test
    fun `encodeColor round-trip preserves RGB`() {
        val packed = GcodeSegmentPacker.encodeColor(1.0f, 0.5f, 0.0f)
        val (r, g, b) = GcodeSegmentPacker.decodeColor(packed)
        assertEquals(255, r)
        assertEquals(128, g)
        assertEquals(0, b)
    }

    @Test
    fun `encodeColor applies brightness`() {
        val packed = GcodeSegmentPacker.encodeColor(1.0f, 1.0f, 1.0f, 0.5f)
        val (r, g, b) = GcodeSegmentPacker.decodeColor(packed)
        assertEquals(128, r)
        assertEquals(128, g)
        assertEquals(128, b)
    }

    // --- Basic packing ---

    @Test
    fun `single extrude move produces 2 vertices and 1 segment`() {
        val gcode = makeGcode(listOf(
            GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f)
        ))
        val result = pack(gcode)
        assertEquals(2, result.totalVertices)
        assertEquals(1, result.totalSegments)
        assertEquals(6, result.positions.size)           // 2 verts × 3 floats
        assertEquals(6, result.heightsWidthsAngles.size) // 2 verts × 3 floats
        assertEquals(2, result.extruderColors.size)      // 2 verts × 1 float
        assertEquals(1, result.segmentIndices.size)      // 1 segment
        assertEquals(0, result.segmentIndices[0])        // segment starts at vertex 0
    }

    @Test
    fun `consecutive extrude moves share vertices`() {
        val gcode = makeGcode(listOf(
            GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f),
            GcodeMove(MoveType.EXTRUDE, 10f, 0f, 20f, 0f),
            GcodeMove(MoveType.EXTRUDE, 20f, 0f, 30f, 0f)
        ))
        val result = pack(gcode)
        // 3 moves in one chain → 4 vertices (shared endpoints), 3 segments
        assertEquals(4, result.totalVertices)
        assertEquals(3, result.totalSegments)
        // Segments reference consecutive vertex pairs
        assertEquals(0, result.segmentIndices[0]) // V0→V1
        assertEquals(1, result.segmentIndices[1]) // V1→V2
        assertEquals(2, result.segmentIndices[2]) // V2→V3
    }

    @Test
    fun `travel breaks chain into two`() {
        val gcode = makeGcode(listOf(
            GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f),
            GcodeMove(MoveType.TRAVEL, 10f, 0f, 50f, 50f),
            GcodeMove(MoveType.EXTRUDE, 50f, 50f, 60f, 50f)
        ))
        val result = pack(gcode)
        // Chain 1: V0, V1 (1 segment). Chain 2: V2, V3 (1 segment).
        assertEquals(4, result.totalVertices)
        assertEquals(2, result.totalSegments)
        assertEquals(0, result.segmentIndices[0]) // chain 1: V0→V1
        assertEquals(2, result.segmentIndices[1]) // chain 2: V2→V3 (gap at V1→V2)
    }

    // --- Angles ---

    @Test
    fun `90 degree turn produces correct angle`() {
        val gcode = makeGcode(listOf(
            GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f),   // →
            GcodeMove(MoveType.EXTRUDE, 10f, 0f, 10f, 10f)  // ↑
        ))
        val result = pack(gcode)
        // Shared vertex V1 (at 10,0) has turning angle
        val angleAtV1 = result.heightsWidthsAngles[1 * 3 + 2]
        assertEquals(PI.toFloat() / 2f, angleAtV1, 0.01f)
    }

    @Test
    fun `straight path produces zero angle at interior vertex`() {
        val gcode = makeGcode(listOf(
            GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f),
            GcodeMove(MoveType.EXTRUDE, 10f, 0f, 20f, 0f)
        ))
        val result = pack(gcode)
        val angleAtV1 = result.heightsWidthsAngles[1 * 3 + 2]
        assertEquals(0f, angleAtV1, 0.001f)
    }

    @Test
    fun `chain start and end have zero angle`() {
        val gcode = makeGcode(listOf(
            GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f),
            GcodeMove(MoveType.EXTRUDE, 10f, 0f, 10f, 10f)
        ))
        val result = pack(gcode)
        assertEquals(0f, result.heightsWidthsAngles[0 * 3 + 2], 0.001f) // V0 start
        assertEquals(0f, result.heightsWidthsAngles[2 * 3 + 2], 0.001f) // V2 end
    }

    // --- Positions ---

    @Test
    fun `positions include z-offset for extrusion centerline`() {
        val gcode = makeGcode(listOf(
            GcodeMove(MoveType.EXTRUDE, 1f, 2f, 3f, 4f)
        ))
        val result = pack(gcode)
        val expectedZ = 0.2f - 0.5f * GcodeSegmentPacker.HEIGHT
        assertEquals(1f, result.positions[0], 0.001f)  // x0
        assertEquals(2f, result.positions[1], 0.001f)  // y0
        assertEquals(expectedZ, result.positions[2], 0.001f) // z (offset)
        assertEquals(3f, result.positions[3], 0.001f)  // x1
        assertEquals(4f, result.positions[4], 0.001f)  // y1
        assertEquals(expectedZ, result.positions[5], 0.001f) // z
    }

    @Test
    fun `height and width constants stored per vertex`() {
        val gcode = makeGcode(listOf(
            GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f)
        ))
        val result = pack(gcode)
        assertEquals(GcodeSegmentPacker.HEIGHT, result.heightsWidthsAngles[0], 0.001f)
        assertEquals(GcodeSegmentPacker.WIDTH, result.heightsWidthsAngles[1], 0.001f)
    }

    // --- Layer ranges ---

    @Test
    fun `layer ranges tracked correctly`() {
        val gcode = makeGcode(
            listOf(
                GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f),
                GcodeMove(MoveType.EXTRUDE, 10f, 0f, 20f, 0f)
            ),
            listOf(
                GcodeMove(MoveType.EXTRUDE, 0f, 5f, 10f, 5f)
            )
        )
        val result = pack(gcode)
        assertEquals(2, result.layerRanges.size)
        assertEquals(0, result.layerRanges[0].firstSegment)
        assertEquals(2, result.layerRanges[0].segmentCount)
        assertEquals(2, result.layerRanges[1].firstSegment)
        assertEquals(1, result.layerRanges[1].segmentCount)
    }

    @Test
    fun `layer boundary breaks chain`() {
        // Even if positions match across layers, chain must break
        val gcode = makeGcode(
            listOf(GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f)),
            listOf(GcodeMove(MoveType.EXTRUDE, 10f, 0f, 20f, 0f))
        )
        val result = pack(gcode)
        // Layer 0: V0→V1 (1 seg). Layer 1: V2→V3 (1 seg). No shared vertex.
        assertEquals(4, result.totalVertices)
        assertEquals(2, result.totalSegments)
        assertEquals(0, result.segmentIndices[0])
        assertEquals(2, result.segmentIndices[1])
    }

    // --- Colors ---

    @Test
    fun `extruder colors use correct palette entry`() {
        val gcode = makeGcode(listOf(
            GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f, extruder = 1)
        ))
        val result = pack(gcode)
        // Single layer → brightness 1.0. T1 = (0.2, 0.7, 1.0)
        val (r, g, b) = GcodeSegmentPacker.decodeColor(result.extruderColors[0])
        assertEquals(51, r)   // 0.2 * 255 ≈ 51
        assertEquals(179, g)  // 0.7 * 255 ≈ 179
        assertEquals(255, b)  // 1.0 * 255 = 255
    }

    @Test
    fun `feature colors use correct palette entry`() {
        val gcode = makeGcode(listOf(
            GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f, featureType = FeatureType.SUPPORT)
        ))
        val result = pack(gcode)
        // SUPPORT = palette[6] = (0.67, 0.28, 0.74)
        val (r, g, b) = GcodeSegmentPacker.decodeColor(result.featureColors[0])
        assertEquals(171, r)  // 0.67 * 255 ≈ 171
        assertEquals(71, g)   // 0.28 * 255 ≈ 71
        assertEquals(189, b)  // 0.74 * 255 ≈ 189
    }

    @Test
    fun `brightness gradient across layers`() {
        val gcode = makeGcode(
            listOf(GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f)),
            listOf(GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f))
        )
        val result = pack(gcode)
        // T0 = (1.0, 0.6, 0.0). Layer 0 brightness = 0.45, Layer 1 = 1.0
        val (r0, _, _) = GcodeSegmentPacker.decodeColor(result.extruderColors[0])
        val (r1, _, _) = GcodeSegmentPacker.decodeColor(result.extruderColors[2]) // layer 1 start vertex
        // Layer 0: 1.0 * 0.45 * 255 ≈ 115
        assertTrue("Layer 0 R=$r0 should be ~115", abs(r0 - 115) <= 2)
        // Layer 1: 1.0 * 1.0 * 255 = 255
        assertEquals(255, r1)
    }

    // --- Filtering ---

    @Test
    fun `zero-length moves are skipped`() {
        val gcode = makeGcode(listOf(
            GcodeMove(MoveType.EXTRUDE, 5f, 5f, 5f, 5f),       // zero length
            GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f)       // valid
        ))
        val result = pack(gcode)
        assertEquals(2, result.totalVertices)
        assertEquals(1, result.totalSegments)
    }

    @Test
    fun `travel moves are excluded from segments`() {
        val gcode = makeGcode(listOf(
            GcodeMove(MoveType.TRAVEL, 0f, 0f, 10f, 10f),
            GcodeMove(MoveType.EXTRUDE, 10f, 10f, 20f, 20f)
        ))
        val result = pack(gcode)
        assertEquals(2, result.totalVertices)
        assertEquals(1, result.totalSegments)
    }

    // --- Edge cases ---

    @Test
    fun `empty gcode returns empty result`() {
        val gcode = ParsedGcode(emptyList())
        val result = pack(gcode)
        assertEquals(0, result.totalVertices)
        assertEquals(0, result.totalSegments)
        assertTrue(result.layerRanges.isEmpty())
    }

    @Test
    fun `large move count does not hit any limit`() {
        val moves = (0 until 400_000).map { i ->
            GcodeMove(MoveType.EXTRUDE, i.toFloat(), 0f, i + 1f, 0f)
        }
        val gcode = makeGcode(moves)
        val result = pack(gcode)
        // All in one chain → 400_001 vertices, 400_000 segments
        assertEquals(400_001, result.totalVertices)
        assertEquals(400_000, result.totalSegments)
    }

    // --- Texture dimensions ---

    @Test
    fun `texture dimensions fit vertex count`() {
        val (w, h) = GcodeSegmentPacker.computeTexDimensions(5000)
        assertTrue("$w x $h must fit 5000", w * h >= 5000)
        assertTrue("width <= 4096", w <= 4096)
    }

    @Test
    fun `texture dimensions for zero returns 1x1`() {
        val (w, h) = GcodeSegmentPacker.computeTexDimensions(0)
        assertEquals(1, w)
        assertEquals(1, h)
    }
}
```

- [ ] **Step 2: Run tests**

```bash
cd /c/Users/kevin/projects/u1-slicer-orca
./gradlew testDebugUnitTest --tests "com.u1.slicer.viewer.GcodeRendererGeometryTest" --no-daemon 2>&1 | tail -20
```

Expected: All tests PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/u1/slicer/viewer/GcodeRendererGeometryTest.kt
git commit -m "test: rewrite GcodeRendererGeometryTest for segment packer"
```

---

### Task 4: Segment Shaders

**Files:**
- Create: `app/src/main/assets/shaders/segment.vert`
- Create: `app/src/main/assets/shaders/segment.frag`

Vertex shader ported from libvgcode `ShadersES.hpp` `Segments_Vertex_Shader_ES` with one addition: `uniform int instance_offset` for per-layer draw calls without texture rebuild.

- [ ] **Step 1: Write segment.vert**

```glsl
#version 300 es
precision highp usampler2D;
precision highp sampler2D;

#define POINTY_CAPS
#define FIX_TWISTING

const vec3  light_top_dir = vec3(-0.4574957, 0.4574957, 0.7624929);
const float light_top_diffuse = 0.6 * 0.8;
const float light_top_specular = 0.6 * 0.125;
const float light_top_shininess = 20.0;
const vec3  light_front_dir = vec3(0.6985074, 0.1397015, 0.6985074);
const float light_front_diffuse = 0.6 * 0.3;
const float ambient = 0.3;
const float emission = 0.15;
const vec3 UP = vec3(0, 0, 1);

uniform mat4 view_matrix;
uniform mat4 projection_matrix;
uniform vec3 camera_position;
uniform int instance_offset;

uniform sampler2D position_tex;
uniform sampler2D height_width_angle_tex;
uniform sampler2D color_tex;
uniform usampler2D segment_index_tex;

layout(location = 0) in float vertex_id_float;
out vec3 color;

vec3 decode_color(float color) {
  int c = int(round(color));
  int r = (c >> 16) & 0xFF;
  int g = (c >> 8) & 0xFF;
  int b = (c >> 0) & 0xFF;
  float f = 1.0 / 255.0;
  return f * vec3(r, g, b);
}

float lighting(vec3 eye_position, vec3 eye_normal) {
  float top_diffuse = light_top_diffuse * max(dot(eye_normal, light_top_dir), 0.0);
  float front_diffuse = light_front_diffuse * max(dot(eye_normal, light_front_dir), 0.0);
  float top_specular = light_top_specular * pow(max(dot(-normalize(eye_position), reflect(-light_top_dir, eye_normal)), 0.0), light_top_shininess);
  return ambient + top_diffuse + front_diffuse + top_specular + emission;
}

ivec2 tex_coord(sampler2D sampler, int id) {
  ivec2 tex_size = textureSize(sampler, 0);
  return (tex_size.y == 1) ? ivec2(id, 0) : ivec2(id % tex_size.x, id / tex_size.x);
}

ivec2 tex_coord_u(usampler2D sampler, int id) {
  ivec2 tex_size = textureSize(sampler, 0);
  return (tex_size.y == 1) ? ivec2(id, 0) : ivec2(id % tex_size.x, id / tex_size.x);
}

void main() {
  int vertex_id = int(vertex_id_float);
  int id_a = int(texelFetch(segment_index_tex, tex_coord_u(segment_index_tex, gl_InstanceID + instance_offset), 0).r);
  int id_b = id_a + 1;
  vec3 pos_a = texelFetch(position_tex, tex_coord(position_tex, id_a), 0).xyz;
  vec3 pos_b = texelFetch(position_tex, tex_coord(position_tex, id_b), 0).xyz;
  vec3 line = pos_b - pos_a;

  float line_len = length(line);
  vec3 line_dir;
  if (line_len < 1e-4)
    line_dir = vec3(1.0, 0.0, 0.0);
  else
    line_dir = line / line_len;

  vec3 line_right_dir;
  if (abs(dot(line_dir, UP)) > 0.9) {
    line_right_dir = normalize(cross(vec3(1, 0, 0), line_dir));
  } else {
    line_right_dir = normalize(cross(line_dir, UP));
  }
  vec3 line_up_dir = normalize(cross(line_right_dir, line_dir));

  const vec2 horizontal_vertical_view_signs_array[16] = vec2[](
    vec2(1.0, 0.0),  vec2(0.0, 1.0),  vec2(0.0, 0.0),  vec2(0.0, -1.0),
    vec2(0.0, -1.0), vec2(1.0, 0.0),  vec2(0.0, 1.0),  vec2(0.0, 0.0),
    vec2(0.0, 1.0),  vec2(-1.0, 0.0), vec2(0.0, 0.0),  vec2(1.0, 0.0),
    vec2(1.0, 0.0),  vec2(0.0, 1.0),  vec2(-1.0, 0.0), vec2(0.0, 0.0)
  );

  int id = vertex_id < 4 ? id_a : id_b;
  vec3 endpoint_pos = vertex_id < 4 ? pos_a : pos_b;
  vec3 height_width_angle = texelFetch(height_width_angle_tex, tex_coord(height_width_angle_tex, id), 0).xyz;

#ifdef FIX_TWISTING
  int closer_id = (dot(camera_position - pos_a, camera_position - pos_a) < dot(camera_position - pos_b, camera_position - pos_b)) ? id_a : id_b;
  vec3 closer_pos = (closer_id == id_a) ? pos_a : pos_b;
  vec3 camera_view_dir = normalize(closer_pos - camera_position);
  vec3 closer_height_width_angle = texelFetch(height_width_angle_tex, tex_coord(height_width_angle_tex, closer_id), 0).xyz;
  vec3 diagonal_dir_border = normalize(closer_height_width_angle.x * line_up_dir + closer_height_width_angle.y * line_right_dir);
#else
  vec3 camera_view_dir = normalize(endpoint_pos - camera_position);
  vec3 diagonal_dir_border = normalize(height_width_angle.x * line_up_dir + height_width_angle.y * line_right_dir);
#endif

  bool is_vertical_view = abs(dot(camera_view_dir, line_up_dir)) / abs(dot(diagonal_dir_border, line_up_dir)) >
    abs(dot(camera_view_dir, line_right_dir)) / abs(dot(diagonal_dir_border, line_right_dir));
  vec2 signs = horizontal_vertical_view_signs_array[vertex_id + 8 * int(is_vertical_view)];

#ifndef POINTY_CAPS
  if (vertex_id == 2 || vertex_id == 7) signs = -horizontal_vertical_view_signs_array[(vertex_id - 2) + 8 * int(is_vertical_view)];
#endif

  float view_right_sign = sign(dot(-camera_view_dir, line_right_dir));
  float view_top_sign = sign(dot(-camera_view_dir, line_up_dir));
  float half_height = 0.5 * height_width_angle.x;
  float half_width = 0.5 * height_width_angle.y;
  vec3 horizontal_dir = half_width * line_right_dir;
  vec3 vertical_dir = half_height * line_up_dir;
  float horizontal_sign = signs.x * view_right_sign;
  float vertical_sign = signs.y * view_top_sign;
  vec3 pos = endpoint_pos + horizontal_sign * horizontal_dir + vertical_sign * vertical_dir;

  if (vertex_id == 2 || vertex_id == 7) {
    float line_dir_sign = (vertex_id == 2) ? -1.0 : 1.0;
    if (height_width_angle.z == 0.0) {
#ifdef POINTY_CAPS
      pos += line_dir_sign * line_dir * half_width;
#endif
    } else {
      pos += line_dir_sign * line_dir * half_width * sin(abs(height_width_angle.z) * 0.5);
      pos += sign(height_width_angle.z) * horizontal_dir * cos(abs(height_width_angle.z) * 0.5);
    }
  }

  vec3 eye_position = (view_matrix * vec4(pos, 1.0)).xyz;
  vec3 eye_normal = (view_matrix * vec4(normalize(pos - endpoint_pos), 0.0)).xyz;
  vec3 color_base = decode_color(texelFetch(color_tex, tex_coord(color_tex, id), 0).r);
  color = color_base * lighting(eye_position, eye_normal);
  gl_Position = projection_matrix * vec4(eye_position, 1.0);
}
```

- [ ] **Step 2: Write segment.frag**

```glsl
#version 300 es
precision highp float;

in vec3 color;
out vec4 fragment_color;

void main() {
  fragment_color = vec4(color, 1.0);
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/assets/shaders/segment.vert app/src/main/assets/shaders/segment.frag
git commit -m "feat: add libvgcode-style segment shaders (ES 3.0)"
```

---

### Task 5: Rewrite GcodeRenderer

**Files:**
- Rewrite: `app/src/main/java/com/u1/slicer/viewer/GcodeRenderer.kt`

Replace per-instance attribute rendering with texture-based segment instancing. Public API (used by `GcodeViewerView`) is unchanged. Travel line rendering is preserved.

- [ ] **Step 1: Write the new GcodeRenderer.kt**

```kotlin
package com.u1.slicer.viewer

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import com.u1.slicer.gcode.FeatureType
import com.u1.slicer.gcode.MoveType
import com.u1.slicer.gcode.ParsedGcode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renders G-code toolpaths as view-adaptive ribbons via GPU texture instancing.
 *
 * Ported from Prusa's libvgcode ShadersES.hpp. Per-vertex data (position,
 * height/width/angle, color) is stored in 2D textures read via texelFetch.
 * A segment template VAO of 24 vertices (8 triangles) is instanced once per
 * segment. The vertex shader generates ribbon geometry that always faces the camera.
 *
 * Travel moves are rendered as GL_LINES via a separate shader.
 */
class GcodeRenderer(private val context: Context) : GLSurfaceView.Renderer {

    val camera = Camera()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var viewportWidth = 0
    private var viewportHeight = 0
    @Volatile var preserveRestoredCameraOnSurfaceInit = false
    @Volatile var onContentReady: (() -> Unit)? = null
    @Volatile private var pendingContentReadyDispatch = false
    private var segmentShader: ShaderProgram? = null
    private var toolpathShader: ShaderProgram? = null
    private val bed = BedDrawable(context)

    // Segment template: 24 vertex IDs for 8 triangles (created once)
    private var templateVAO = 0
    private var templateVBO = 0

    // Data textures (recreated on each uploadGcode)
    private var positionTexId = 0
    private var hwaTexId = 0
    private var activeColorTexId = 0      // currently bound color texture
    private var extruderColorTexId = 0    // extruder-mode colors
    private var featureColorTexId = 0     // feature-mode colors
    private var segmentIndexTexId = 0

    // Layer data
    private var segmentLayerRanges = listOf<SegmentLayerRange>()
    private var hasSegments = false

    // Travel lines (unchanged from original renderer)
    private var travelVAO = 0
    private var travelVBO = 0
    private data class TravelLayerRange(val first: Int, val count: Int)
    private val travelLayerRanges = mutableListOf<TravelLayerRange>()

    private var totalLayers = 0
    var minLayer = 0
        set(value) { field = value.coerceIn(0, totalLayers - 1) }
    var maxLayer = 0
        set(value) { field = value.coerceIn(0, totalLayers - 1) }
    var showTravel = false

    @Volatile var pendingGcode: ParsedGcode? = null
    @Volatile var preserveCameraOnNextUpload = false
    @Volatile var pendingExtruderColors: List<String>? = null
    @Volatile var pendingColorMode: Boolean? = null
    private var useFeatureColors = false
    private var lastGcode: ParsedGcode? = null
    private var lastPackResult: SegmentPackResult? = null

    private val extruderColors = arrayOf(
        floatArrayOf(1.0f, 0.6f, 0.0f, 1.0f),
        floatArrayOf(0.2f, 0.7f, 1.0f, 1.0f),
        floatArrayOf(0.0f, 0.9f, 0.4f, 1.0f),
        floatArrayOf(0.9f, 0.2f, 0.5f, 1.0f)
    )
    private val travelColor = floatArrayOf(0.6f, 0.6f, 0.6f, 0.6f)

    private val featureTypeColors = arrayOf(
        floatArrayOf(1.00f, 0.85f, 0.00f, 1.0f),
        floatArrayOf(0.53f, 0.81f, 0.92f, 1.0f),
        floatArrayOf(0.30f, 0.71f, 0.68f, 1.0f),
        floatArrayOf(0.40f, 0.73f, 0.42f, 1.0f),
        floatArrayOf(0.00f, 0.74f, 0.83f, 1.0f),
        floatArrayOf(0.00f, 0.59f, 0.53f, 1.0f),
        floatArrayOf(0.67f, 0.28f, 0.74f, 1.0f),
        floatArrayOf(0.81f, 0.58f, 0.85f, 1.0f),
        floatArrayOf(1.00f, 0.25f, 0.51f, 1.0f),
        floatArrayOf(1.00f, 0.44f, 0.26f, 1.0f),
        floatArrayOf(0.69f, 0.75f, 0.76f, 1.0f),
        floatArrayOf(0.62f, 0.62f, 0.62f, 1.0f)
    )

    fun setExtruderColors(hexColors: List<String>) {
        hexColors.forEachIndexed { i, hex ->
            if (i >= extruderColors.size || hex.isBlank()) return@forEachIndexed
            try {
                val c = android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex")
                extruderColors[i] = floatArrayOf(
                    android.graphics.Color.red(c) / 255f,
                    android.graphics.Color.green(c) / 255f,
                    android.graphics.Color.blue(c) / 255f,
                    1.0f
                )
            } catch (_: Exception) { }
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.059f, 0.059f, 0.118f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glLineWidth(1.5f)

        segmentShader = ShaderProgram(context, "shaders/segment.vert", "shaders/segment.frag")
        toolpathShader = ShaderProgram(context, "shaders/toolpath.vert", "shaders/toolpath.frag")
        bed.setup(context)
        createSegmentTemplate()

        if (preserveRestoredCameraOnSurfaceInit) {
            preserveRestoredCameraOnSurfaceInit = false
        } else {
            camera.setTarget(135.0, 135.0, 0.0)
            camera.distance = 500.0
            camera.elevation = 62.0
            camera.azimuth = -90.0
        }
    }

    private fun createSegmentTemplate() {
        // 24 vertex IDs for 8 triangles (libvgcode SegmentTemplate)
        val templateData = floatArrayOf(
            0f, 1f, 2f,  0f, 2f, 3f,   // front cap
            0f, 3f, 4f,  0f, 4f, 5f,   // body
            0f, 5f, 6f,  0f, 6f, 1f,   // body
            5f, 4f, 7f,  5f, 7f, 6f    // back cap
        )
        val buf = ByteBuffer.allocateDirect(templateData.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(templateData).flip()

        val vaos = IntArray(1); GLES30.glGenVertexArrays(1, vaos, 0); templateVAO = vaos[0]
        val vbos = IntArray(1); GLES30.glGenBuffers(1, vbos, 0); templateVBO = vbos[0]

        GLES30.glBindVertexArray(templateVAO)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, templateVBO)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, templateData.size * 4, buf, GLES30.GL_STATIC_DRAW)
        // vertex_id_float at location 0
        GLES30.glVertexAttribPointer(0, 1, GLES30.GL_FLOAT, false, 4, 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glBindVertexArray(0)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        camera.updateProjectionMatrix(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        pendingExtruderColors?.let { colors ->
            setExtruderColors(colors)
            pendingExtruderColors = null
            rebuildExtruderColorTexture()
        }

        pendingColorMode?.let { mode ->
            pendingColorMode = null
            if (mode != useFeatureColors) {
                useFeatureColors = mode
                activeColorTexId = if (useFeatureColors) featureColorTexId else extruderColorTexId
            }
        }

        pendingGcode?.let { gcode ->
            uploadGcode(gcode)
            pendingGcode = null
            if (preserveCameraOnNextUpload) {
                preserveCameraOnNextUpload = false
            } else {
                frameContentCamera(gcode)
            }
            pendingContentReadyDispatch = true
        }

        camera.updateViewMatrix()
        if (viewportWidth > 0 && viewportHeight > 0) {
            camera.updateProjectionMatrix(viewportWidth, viewportHeight)
        }
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        bed.draw(camera)
        drawSegments()
        drawTravel()

        if (pendingContentReadyDispatch) {
            pendingContentReadyDispatch = false
            onContentReady?.let { cb -> mainHandler.post { cb() } }
        }
    }

    private fun frameContentCamera(gcode: ParsedGcode) {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (layer in gcode.layers) {
            for (move in layer.moves) {
                if (move.x0 < minX) minX = move.x0; if (move.x0 > maxX) maxX = move.x0
                if (move.x1 < minX) minX = move.x1; if (move.x1 > maxX) maxX = move.x1
                if (move.y0 < minY) minY = move.y0; if (move.y0 > maxY) maxY = move.y0
                if (move.y1 < minY) minY = move.y1; if (move.y1 > maxY) maxY = move.y1
            }
        }
        if (minX == Float.MAX_VALUE) {
            camera.setTarget(135.0, 135.0, 0.0)
            camera.distance = 500.0
        } else {
            val pad = 20f
            camera.setTarget(((minX + maxX) / 2f).toDouble(), ((minY + maxY) / 2f).toDouble(), 0.0)
            val dist = maxOf((maxX - minX + 2 * pad).toDouble(), (maxY - minY + 2 * pad).toDouble()) * 2.0
            camera.distance = dist.coerceAtLeast(100.0)
        }
        camera.elevation = 62.0
        camera.azimuth = -90.0
        camera.panX = 0.0
        camera.panY = 0.0
    }

    // --- Texture helpers ---

    private fun createDataTexture(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val id = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return id
    }

    private fun deleteTexture(id: Int) {
        if (id != 0) GLES30.glDeleteTextures(1, intArrayOf(id), 0)
    }

    private fun uploadFloatTexture(texId: Int, internalFormat: Int, format: Int,
                                   width: Int, height: Int, data: FloatArray, components: Int) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        val paddedSize = width * height * components
        val padded = if (data.size < paddedSize) data.copyOf(paddedSize) else data
        val buf = ByteBuffer.allocateDirect(paddedSize * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(padded, 0, paddedSize).flip()
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internalFormat, width, height, 0,
            format, GLES30.GL_FLOAT, buf)
    }

    private fun uploadUintTexture(texId: Int, width: Int, height: Int, data: IntArray) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        val paddedSize = width * height
        val padded = if (data.size < paddedSize) data.copyOf(paddedSize) else data
        val buf = ByteBuffer.allocateDirect(paddedSize * 4)
            .order(ByteOrder.nativeOrder()).asIntBuffer()
        buf.put(padded, 0, paddedSize).flip()
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32UI, width, height, 0,
            GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_INT, buf)
    }

    // --- Data upload ---

    fun uploadGcode(gcode: ParsedGcode) {
        lastGcode = gcode
        totalLayers = gcode.layers.size
        maxLayer = totalLayers - 1
        if (totalLayers == 0) {
            hasSegments = false
            segmentLayerRanges = emptyList()
            return
        }

        // Pack data
        val pack = GcodeSegmentPacker.pack(gcode, extruderColors, featureTypeColors)
        lastPackResult = pack
        segmentLayerRanges = pack.layerRanges
        hasSegments = pack.totalSegments > 0

        if (hasSegments) {
            val (vTexW, vTexH) = GcodeSegmentPacker.computeTexDimensions(pack.totalVertices)
            val (sTexW, sTexH) = GcodeSegmentPacker.computeTexDimensions(pack.totalSegments)

            // Recreate textures
            deleteTexture(positionTexId);    positionTexId = createDataTexture()
            deleteTexture(hwaTexId);         hwaTexId = createDataTexture()
            deleteTexture(extruderColorTexId); extruderColorTexId = createDataTexture()
            deleteTexture(featureColorTexId); featureColorTexId = createDataTexture()
            deleteTexture(segmentIndexTexId); segmentIndexTexId = createDataTexture()

            uploadFloatTexture(positionTexId, GLES30.GL_RGB32F, GLES30.GL_RGB, vTexW, vTexH, pack.positions, 3)
            uploadFloatTexture(hwaTexId, GLES30.GL_RGB32F, GLES30.GL_RGB, vTexW, vTexH, pack.heightsWidthsAngles, 3)
            uploadFloatTexture(extruderColorTexId, GLES30.GL_R32F, GLES30.GL_RED, vTexW, vTexH, pack.extruderColors, 1)
            uploadFloatTexture(featureColorTexId, GLES30.GL_R32F, GLES30.GL_RED, vTexW, vTexH, pack.featureColors, 1)
            uploadUintTexture(segmentIndexTexId, sTexW, sTexH, pack.segmentIndices)

            activeColorTexId = if (useFeatureColors) featureColorTexId else extruderColorTexId
        }

        // Travel lines (unchanged logic)
        uploadTravelLines(gcode)
    }

    private fun rebuildExtruderColorTexture() {
        val pack = lastPackResult ?: return
        if (pack.totalVertices == 0) return
        val gcode = lastGcode ?: return

        // Re-pack just extruder colors with new palette
        val newPack = GcodeSegmentPacker.pack(gcode, extruderColors, featureTypeColors)
        lastPackResult = newPack
        val (vTexW, vTexH) = GcodeSegmentPacker.computeTexDimensions(newPack.totalVertices)
        uploadFloatTexture(extruderColorTexId, GLES30.GL_R32F, GLES30.GL_RED, vTexW, vTexH, newPack.extruderColors, 1)
        if (!useFeatureColors) activeColorTexId = extruderColorTexId
    }

    private fun uploadTravelLines(gcode: ParsedGcode) {
        if (travelVAO != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(travelVAO), 0)
            GLES30.glDeleteBuffers(1, intArrayOf(travelVBO), 0)
            travelVAO = 0; travelVBO = 0
        }
        travelLayerRanges.clear()

        val fpv = 7 // 3 pos + 4 color
        var totalTravel = 0
        for (layer in gcode.layers) {
            for (move in layer.moves) {
                if (move.type != MoveType.EXTRUDE) totalTravel++
            }
        }

        if (totalTravel > 0) {
            val lineData = FloatArray(totalTravel * 2 * fpv)
            var off = 0
            for (layer in gcode.layers) {
                val first = off / fpv
                for (move in layer.moves) {
                    if (move.type == MoveType.EXTRUDE) continue
                    if (off + fpv * 2 > lineData.size) break
                    lineData[off++] = move.x0; lineData[off++] = move.y0; lineData[off++] = layer.z
                    lineData[off++] = travelColor[0]; lineData[off++] = travelColor[1]; lineData[off++] = travelColor[2]; lineData[off++] = travelColor[3]
                    lineData[off++] = move.x1; lineData[off++] = move.y1; lineData[off++] = layer.z
                    lineData[off++] = travelColor[0]; lineData[off++] = travelColor[1]; lineData[off++] = travelColor[2]; lineData[off++] = travelColor[3]
                }
                travelLayerRanges.add(TravelLayerRange(first, off / fpv - first))
            }
            if (off > 0) {
                val buf = ByteBuffer.allocateDirect(off * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                buf.put(lineData, 0, off).flip()
                val vaos = IntArray(1); GLES30.glGenVertexArrays(1, vaos, 0); travelVAO = vaos[0]
                val vbos = IntArray(1); GLES30.glGenBuffers(1, vbos, 0); travelVBO = vbos[0]
                GLES30.glBindVertexArray(travelVAO)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, travelVBO)
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, off * 4, buf, GLES30.GL_STATIC_DRAW)
                GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, fpv * 4, 0)
                GLES30.glEnableVertexAttribArray(0)
                GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, fpv * 4, 12)
                GLES30.glEnableVertexAttribArray(1)
                GLES30.glBindVertexArray(0)
            }
        } else {
            for (layer in gcode.layers) travelLayerRanges.add(TravelLayerRange(0, 0))
        }
    }

    // --- Drawing ---

    private fun drawSegments() {
        if (!hasSegments || segmentLayerRanges.isEmpty() || templateVAO == 0) return
        val shader = segmentShader ?: return
        shader.use()

        camera.computeMVP()

        // Extract camera world position from view matrix
        val v = camera.viewMatrix
        val camX = -(v[0] * v[12] + v[1] * v[13] + v[2] * v[14])
        val camY = -(v[4] * v[12] + v[5] * v[13] + v[6] * v[14])
        val camZ = -(v[8] * v[12] + v[9] * v[13] + v[10] * v[14])

        GLES30.glUniformMatrix4fv(shader.getUniformLocation("view_matrix"), 1, false, camera.viewMatrix, 0)
        GLES30.glUniformMatrix4fv(shader.getUniformLocation("projection_matrix"), 1, false, camera.projectionMatrix, 0)
        GLES30.glUniform3f(shader.getUniformLocation("camera_position"), camX, camY, camZ)

        // Bind textures to units
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, positionTexId)
        GLES30.glUniform1i(shader.getUniformLocation("position_tex"), 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, hwaTexId)
        GLES30.glUniform1i(shader.getUniformLocation("height_width_angle_tex"), 1)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, activeColorTexId)
        GLES30.glUniform1i(shader.getUniformLocation("color_tex"), 2)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, segmentIndexTexId)
        GLES30.glUniform1i(shader.getUniformLocation("segment_index_tex"), 3)

        val instanceOffsetLoc = shader.getUniformLocation("instance_offset")

        // View-adaptive ribbons need both faces visible
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        GLES30.glBindVertexArray(templateVAO)
        val min = minLayer.coerceIn(0, segmentLayerRanges.size - 1)
        val max = maxLayer.coerceIn(0, segmentLayerRanges.size - 1)
        for (i in min..max) {
            if (i >= segmentLayerRanges.size) break
            val range = segmentLayerRanges[i]
            if (range.segmentCount <= 0) continue
            GLES30.glUniform1i(instanceOffsetLoc, range.firstSegment)
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, 24, range.segmentCount)
        }
        GLES30.glBindVertexArray(0)
    }

    private fun drawTravel() {
        if (!showTravel || travelLayerRanges.isEmpty() || travelVAO == 0) return
        val shader = toolpathShader ?: return
        shader.use()
        camera.computeMVP()
        GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_MVPMatrix"), 1, false, camera.mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_NormalMatrix"), 1, false, camera.normalMatrix, 0)

        val min = minLayer.coerceIn(0, travelLayerRanges.size - 1)
        val max = maxLayer.coerceIn(0, travelLayerRanges.size - 1)
        GLES30.glBindVertexArray(travelVAO)
        for (i in min..max) {
            if (i >= travelLayerRanges.size) break
            val r = travelLayerRanges[i]
            if (r.count > 0) GLES30.glDrawArrays(GLES30.GL_LINES, r.first, r.count)
        }
        GLES30.glBindVertexArray(0)
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
cd /c/Users/kevin/projects/u1-slicer-orca
./gradlew compileDebugKotlin --no-daemon 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/u1/slicer/viewer/GcodeRenderer.kt
git commit -m "feat: rewrite GcodeRenderer with libvgcode texture-based segments"
```

---

### Task 6: Cleanup and Verification

**Files:**
- Delete: `app/src/main/java/com/u1/slicer/viewer/GcodeInstancePacker.kt`
- Delete: `app/src/main/assets/shaders/toolpath_instanced.vert`
- Delete: `app/src/main/assets/shaders/toolpath_instanced.frag`

- [ ] **Step 1: Delete old files**

```bash
cd /c/Users/kevin/projects/u1-slicer-orca
rm app/src/main/java/com/u1/slicer/viewer/GcodeInstancePacker.kt
rm app/src/main/assets/shaders/toolpath_instanced.vert
rm app/src/main/assets/shaders/toolpath_instanced.frag
```

- [ ] **Step 2: Search for stale references**

```bash
cd /c/Users/kevin/projects/u1-slicer-orca
grep -r "GcodeInstancePacker\|InstanceLayerRange\|InstancePackResult\|toolpath_instanced" app/src/main/ --include="*.kt" --include="*.java" -l
```

Expected: No results (all references removed by renderer rewrite and test rewrite).

- [ ] **Step 3: Run all unit tests**

```bash
cd /c/Users/kevin/projects/u1-slicer-orca
./gradlew testDebugUnitTest --no-daemon 2>&1 | tail -30
```

Expected: All 743+ tests PASS. The rewritten `GcodeRendererGeometryTest` replaces old tests.

- [ ] **Step 4: Build APK**

```bash
cd /c/Users/kevin/projects/u1-slicer-orca
./gradlew assembleDebug --no-daemon 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit cleanup**

```bash
git add -A
git commit -m "refactor: remove old instanced renderer (GcodeInstancePacker, toolpath_instanced shaders)"
```

---

## Dependency Graph

```
Task 1 (branch) ──┬── Task 2 (packer) ──── Task 3 (tests)
                   │                              │
                   └── Task 4 (shaders)           │
                                │                 │
                                └── Task 5 (renderer) ── Task 6 (cleanup + verify)
```

**Parallel opportunities:**
- Tasks 2 + 4 can run in parallel (no shared files)
- Task 3 depends on Task 2 (tests import packer)
- Task 5 depends on Tasks 2 + 4 (renderer uses packer API and shader uniforms)
- Task 6 depends on Task 5

## Test count delta

Old `GcodeRendererGeometryTest`: 11 tests (for `GcodeInstancePacker`)
New `GcodeRendererGeometryTest`: 21 tests (for `GcodeSegmentPacker`)

**Net change: +10 tests.** Update CLAUDE.md test count from 743 to 753 after verification.
