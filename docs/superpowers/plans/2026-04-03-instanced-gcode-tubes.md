# Instanced G-Code Tube Rendering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the CPU-expanded box-tube geometry in `GcodeRenderer` with GPU-instanced rendering so all g-code files get thick tube visuals regardless of move count — eliminating the 200k-move cliff that forces large files (like GreatPyr at 361k moves) to thin GL_LINES.

**Architecture:** Upload per-move data (start pos, end pos, color, layer Z, half-dimensions) into a compact VBO with one entry per extrusion move. A new instanced vertex shader expands an 8-vertex box-tube template per instance using `gl_InstanceID` + `gl_VertexID`. This drops GPU memory from ~720 bytes/move to ~48 bytes/move (~17MB for 361k moves vs ~260MB), removing the need for any fallback path.

**Tech Stack:** Kotlin, OpenGL ES 3.0 (`glDrawArraysInstanced`, `glVertexAttribDivisor`), GLSL ES 300

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `app/src/main/assets/shaders/toolpath_instanced.vert` | Create | Instanced vertex shader: expands 8-vertex template per instance |
| `app/src/main/assets/shaders/toolpath_instanced.frag` | Create | Fragment shader (same logic as existing `toolpath.frag`) |
| `app/src/main/java/com/u1/slicer/viewer/GcodeRenderer.kt` | Modify | Replace CPU tube expansion + fallback with instanced draw path |
| `app/src/test/java/com/u1/slicer/viewer/GcodeRendererGeometryTest.kt` | Create | Unit tests for the per-move data packing logic |

## Design Notes

### Instance data layout (12 floats = 48 bytes per move)

```
float[0..2]  = start position (x0, y0, z)
float[3..5]  = end position   (x1, y1, z)  — z duplicated for simplicity
float[6..9]  = color RGBA
float[10]    = halfWidth  (0.28)
float[11]    = halfHeight (0.18)
```

### Template vertices (8 positions, hardcoded in shader)

The box cross-section has 4 corners at each end of the segment:
```
  TL---TR        (z + halfHeight)
  |     |
  BL---BR        (z - halfHeight)
  left  right    (-perp, +perp)
```

6 faces × 2 triangles × 3 vertices = 36 vertices, but we only need top + left + right (3 faces) = 18 vertices (same visual as current). The vertex shader uses `gl_VertexID` (0..17) to select which corner of which face, then offsets by the instance's start/end + perpendicular direction.

### Layer range tracking

Each layer records `(firstInstance, instanceCount)` — same pattern as current `TubeLayerRange` but indexing instances not vertices. Draw call becomes `glDrawArraysInstanced(GL_TRIANGLES, 0, 18, instanceCount)` per visible layer range.

### Travel moves

Travel moves stay as GL_LINES in the existing `masterVAO`/`masterVBO` path — they don't need tubes.

### Miter joins

Dropped in this iteration. The current miter logic adds significant complexity and the visual improvement is marginal for a 3D preview. Segments will have simple perpendicular caps. This can be revisited later if needed.

---

### Task 1: Create instanced vertex shader

**Files:**
- Create: `app/src/main/assets/shaders/toolpath_instanced.vert`
- Create: `app/src/main/assets/shaders/toolpath_instanced.frag`

- [ ] **Step 1: Write the instanced vertex shader**

Create `app/src/main/assets/shaders/toolpath_instanced.vert`:

```glsl
#version 300 es
precision mediump float;

uniform mat4 u_MVPMatrix;
uniform mat4 u_NormalMatrix;

// Per-instance attributes (one per extrusion move, advanced via divisor=1)
layout(location = 0) in vec3 a_Start;       // x0, y0, z
layout(location = 1) in vec3 a_End;         // x1, y1, z (z same as start)
layout(location = 2) in vec4 a_Color;       // RGBA
layout(location = 3) in vec2 a_Dimensions;  // halfWidth, halfHeight

out vec4 v_Color;
out float v_Intensity;

// Lighting constants (matching toolpath.vert)
const vec3 LIGHT_TOP_DIR = normalize(vec3(-0.46, 0.46, 0.76));
const vec3 LIGHT_FRONT_DIR = normalize(vec3(0.70, 0.14, 0.70));
const float AMBIENT = 0.20;
const float DIFFUSE_TOP = 0.65;
const float DIFFUSE_FRONT = 0.30;
const float SPECULAR_TOP = 0.25;

void main() {
    float halfW = a_Dimensions.x;
    float halfH = a_Dimensions.y;

    // Direction and perpendicular
    vec2 dir = a_End.xy - a_Start.xy;
    float len = length(dir);
    vec2 fwd = (len > 0.001) ? dir / len : vec2(1.0, 0.0);
    vec2 perp = vec2(-fwd.y, fwd.x);  // unit perpendicular (points right of travel)

    // 18 vertices = 3 faces (top, right, left) × 2 triangles × 3 verts
    // Vertex ID selects which corner
    int vid = gl_VertexID;

    // Corner table: for each of 18 vertices, define (startOrEnd, leftOrRight, botOrTop)
    // Face 0 (top):     z=+halfH, normal=(0,0,1)
    //   Tri0: BL-start, BR-start, BR-end    -> (0,-1,+1), (0,+1,+1), (1,+1,+1)
    //   Tri1: BL-start, BR-end,   BL-end    -> (0,-1,+1), (1,+1,+1), (1,-1,+1)
    // Face 1 (right):   perp=+1, normal=(perp.x, perp.y, 0)
    //   Tri0: bot-start, top-start, top-end  -> (0,+1,-1), (0,+1,+1), (1,+1,+1)
    //   Tri1: bot-start, top-end,   bot-end  -> (0,+1,-1), (1,+1,+1), (1,+1,-1)
    // Face 2 (left):    perp=-1, normal=(-perp.x, -perp.y, 0)
    //   Tri0: bot-start, top-end, top-start  -> (0,-1,-1), (1,-1,+1), (0,-1,+1)
    //   Tri1: bot-start, bot-end, top-end    -> (0,-1,-1), (1,-1,-1), (1,-1,+1)

    // Encode as constant arrays
    // t: 0=start, 1=end
    const int t[18] = int[18](0,0,1, 0,1,1,  0,0,1, 0,1,1,  0,1,0, 0,1,1);
    // s: -1=left, +1=right (perpendicular side)
    const int s[18] = int[18](-1,1,1, -1,1,-1,  1,1,1, 1,1,1,  -1,-1,-1, -1,-1,-1);
    // h: -1=bot, +1=top (z offset)
    const int h[18] = int[18](1,1,1, 1,1,1,  -1,1,1, -1,1,-1,  -1,1,1, -1,-1,1);

    float tVal = float(t[vid]);
    float sVal = float(s[vid]);
    float hVal = float(h[vid]);

    vec3 basePos = mix(a_Start, a_End, tVal);
    vec3 pos = vec3(
        basePos.x + perp.x * sVal * halfW,
        basePos.y + perp.y * sVal * halfW,
        basePos.z + hVal * halfH
    );

    gl_Position = u_MVPMatrix * vec4(pos, 1.0);
    v_Color = a_Color;

    // Normal per face
    vec3 normal;
    if (vid < 6) {
        normal = vec3(0.0, 0.0, 1.0);  // top face
    } else if (vid < 12) {
        normal = vec3(perp, 0.0);       // right face
    } else {
        normal = vec3(-perp, 0.0);      // left face
    }
    vec3 worldNormal = normalize((u_NormalMatrix * vec4(normal, 0.0)).xyz);
    float NdotL_top = max(dot(worldNormal, LIGHT_TOP_DIR), 0.0);
    float NdotL_front = max(dot(worldNormal, LIGHT_FRONT_DIR), 0.0);
    vec3 halfVec = normalize(LIGHT_TOP_DIR + vec3(0.0, 0.0, 1.0));
    float specular = pow(max(dot(worldNormal, halfVec), 0.0), 32.0) * SPECULAR_TOP;
    v_Intensity = AMBIENT + DIFFUSE_TOP * NdotL_top + DIFFUSE_FRONT * NdotL_front + specular;
}
```

- [ ] **Step 2: Write the instanced fragment shader**

Create `app/src/main/assets/shaders/toolpath_instanced.frag` — identical to existing `toolpath.frag`:

```glsl
#version 300 es
precision mediump float;

in vec4 v_Color;
in float v_Intensity;
out vec4 fragColor;

void main() {
    fragColor = vec4(v_Color.rgb * v_Intensity, v_Color.a);
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/assets/shaders/toolpath_instanced.vert app/src/main/assets/shaders/toolpath_instanced.frag
git commit -m "feat: add instanced vertex/fragment shaders for gcode tube rendering"
```

---

### Task 2: Write unit tests for instance data packing

**Files:**
- Create: `app/src/test/java/com/u1/slicer/viewer/GcodeRendererGeometryTest.kt`

The packing logic will be extracted into a testable pure function `packInstanceData(gcode, extruderColors, featureTypeColors, useFeatureColors): InstancePackResult`. This task writes the tests first.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/u1/slicer/viewer/GcodeRendererGeometryTest.kt`:

```kotlin
package com.u1.slicer.viewer

import com.u1.slicer.gcode.*
import org.junit.Assert.*
import org.junit.Test

class GcodeRendererGeometryTest {

    private val defaultExtruderColors = arrayOf(
        floatArrayOf(1.0f, 0.6f, 0.0f, 1.0f),
        floatArrayOf(0.2f, 0.7f, 1.0f, 1.0f),
        floatArrayOf(0.0f, 0.9f, 0.4f, 1.0f),
        floatArrayOf(0.9f, 0.2f, 0.5f, 1.0f)
    )
    private val defaultFeatureColors = arrayOf(
        floatArrayOf(1.0f, 0.85f, 0.0f, 1.0f),  // OUTER_WALL
        floatArrayOf(0.53f, 0.81f, 0.92f, 1.0f), // INNER_WALL
        floatArrayOf(0.3f, 0.71f, 0.68f, 1.0f),  // SPARSE_INFILL
        floatArrayOf(0.4f, 0.73f, 0.42f, 1.0f),  // SOLID_INFILL
        floatArrayOf(0.0f, 0.74f, 0.83f, 1.0f),  // TOP_SURFACE
        floatArrayOf(0.0f, 0.59f, 0.53f, 1.0f),  // BOTTOM_SURFACE
        floatArrayOf(0.67f, 0.28f, 0.74f, 1.0f),  // SUPPORT
        floatArrayOf(0.81f, 0.58f, 0.85f, 1.0f),  // SUPPORT_INTERFACE
        floatArrayOf(1.0f, 0.25f, 0.51f, 1.0f),  // PRIME_TOWER
        floatArrayOf(1.0f, 0.44f, 0.26f, 1.0f),  // BRIDGE
        floatArrayOf(0.69f, 0.75f, 0.76f, 1.0f),  // SKIRT
        floatArrayOf(0.62f, 0.62f, 0.62f, 1.0f)   // OTHER
    )

    private fun pack(gcode: ParsedGcode, useFeatureColors: Boolean = false) =
        GcodeInstancePacker.pack(gcode, defaultExtruderColors, defaultFeatureColors, useFeatureColors)

    @Test
    fun `single extrusion move produces 12 floats`() {
        val gcode = ParsedGcode(listOf(
            GcodeLayer(0, 0.2f, listOf(
                GcodeMove(MoveType.EXTRUDE, 10f, 20f, 30f, 40f, extruder = 0)
            ))
        ))
        val result = pack(gcode)
        assertEquals(1, result.totalInstances)
        assertEquals(12, result.instanceData.size)
        // start pos
        assertEquals(10f, result.instanceData[0], 0.001f)
        assertEquals(20f, result.instanceData[1], 0.001f)
        assertEquals(0.2f, result.instanceData[2], 0.001f)
        // end pos
        assertEquals(30f, result.instanceData[3], 0.001f)
        assertEquals(40f, result.instanceData[4], 0.001f)
        assertEquals(0.2f, result.instanceData[5], 0.001f)
    }

    @Test
    fun `travel moves are excluded from instance data`() {
        val gcode = ParsedGcode(listOf(
            GcodeLayer(0, 0.2f, listOf(
                GcodeMove(MoveType.TRAVEL, 0f, 0f, 10f, 10f),
                GcodeMove(MoveType.EXTRUDE, 10f, 10f, 20f, 20f, extruder = 0),
                GcodeMove(MoveType.TRAVEL, 20f, 20f, 30f, 30f)
            ))
        ))
        val result = pack(gcode)
        assertEquals(1, result.totalInstances)
    }

    @Test
    fun `zero-length extrusion moves are skipped`() {
        val gcode = ParsedGcode(listOf(
            GcodeLayer(0, 0.2f, listOf(
                GcodeMove(MoveType.EXTRUDE, 10f, 20f, 10f, 20f, extruder = 0),  // zero length
                GcodeMove(MoveType.EXTRUDE, 10f, 20f, 30f, 40f, extruder = 0)   // real move
            ))
        ))
        val result = pack(gcode)
        assertEquals(1, result.totalInstances)
    }

    @Test
    fun `layer ranges track first instance and count`() {
        val gcode = ParsedGcode(listOf(
            GcodeLayer(0, 0.2f, listOf(
                GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f, extruder = 0),
                GcodeMove(MoveType.EXTRUDE, 10f, 0f, 20f, 0f, extruder = 0)
            )),
            GcodeLayer(1, 0.4f, listOf(
                GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f, extruder = 1)
            ))
        ))
        val result = pack(gcode)
        assertEquals(2, result.layerRanges.size)
        assertEquals(0, result.layerRanges[0].firstInstance)
        assertEquals(2, result.layerRanges[0].instanceCount)
        assertEquals(2, result.layerRanges[1].firstInstance)
        assertEquals(1, result.layerRanges[1].instanceCount)
    }

    @Test
    fun `color uses extruder index by default`() {
        val gcode = ParsedGcode(listOf(
            GcodeLayer(0, 0.2f, listOf(
                GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f, extruder = 1)
            ))
        ))
        val result = pack(gcode)
        // Color at offset 6..9 — extruder 1 is blue (0.2, 0.7, 1.0, 1.0) * brightness
        // Single layer so brightness = 1.0
        assertEquals(0.2f, result.instanceData[6], 0.001f)
        assertEquals(0.7f, result.instanceData[7], 0.001f)
        assertEquals(1.0f, result.instanceData[8], 0.001f)
    }

    @Test
    fun `color uses feature type when enabled`() {
        val gcode = ParsedGcode(listOf(
            GcodeLayer(0, 0.2f, listOf(
                GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f, extruder = 0,
                    featureType = FeatureType.PRIME_TOWER)
            ))
        ))
        val result = pack(gcode, useFeatureColors = true)
        // PRIME_TOWER = index 8 = hot pink (1.0, 0.25, 0.51, 1.0)
        assertEquals(1.0f, result.instanceData[6], 0.001f)
        assertEquals(0.25f, result.instanceData[7], 0.001f)
        assertEquals(0.51f, result.instanceData[8], 0.001f)
    }

    @Test
    fun `brightness gradient applied across layers`() {
        val gcode = ParsedGcode(listOf(
            GcodeLayer(0, 0.2f, listOf(
                GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f, extruder = 0)
            )),
            GcodeLayer(1, 0.4f, listOf(
                GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f, extruder = 0)
            ))
        ))
        val result = pack(gcode)
        // Layer 0 brightness = 0.45, layer 1 brightness = 1.0
        // Extruder 0 red = 1.0 * brightness
        val layer0Red = result.instanceData[6]
        val layer1Red = result.instanceData[12 + 6]
        assertTrue("Bottom layer should be darker", layer0Red < layer1Red)
        assertEquals(0.45f, layer0Red, 0.01f)
        assertEquals(1.0f, layer1Red, 0.01f)
    }

    @Test
    fun `halfWidth and halfHeight are set correctly`() {
        val gcode = ParsedGcode(listOf(
            GcodeLayer(0, 0.2f, listOf(
                GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f, extruder = 0)
            ))
        ))
        val result = pack(gcode)
        assertEquals(0.28f, result.instanceData[10], 0.001f)
        assertEquals(0.18f, result.instanceData[11], 0.001f)
    }

    @Test
    fun `large move count does not trigger fallback`() {
        // Create a gcode with 300k+ moves — packing should succeed without any limit
        val moves = (0 until 1000).map { i ->
            GcodeMove(MoveType.EXTRUDE, i.toFloat(), 0f, i + 1f, 0f, extruder = 0)
        }
        val layers = (0 until 400).map { GcodeLayer(it, it * 0.2f, moves) }
        val gcode = ParsedGcode(layers)
        val result = pack(gcode)
        assertEquals(400_000, result.totalInstances)
        // 400k * 12 floats = 4.8M floats — no problem
        assertEquals(400_000 * 12, result.instanceData.size)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.u1.slicer.viewer.GcodeRendererGeometryTest" --no-daemon`
Expected: FAIL — `GcodeInstancePacker` does not exist yet.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/u1/slicer/viewer/GcodeRendererGeometryTest.kt
git commit -m "test: add failing tests for instanced gcode tube data packing"
```

---

### Task 3: Implement instance data packer

**Files:**
- Create: `app/src/main/java/com/u1/slicer/viewer/GcodeInstancePacker.kt`

- [ ] **Step 1: Write the implementation**

Create `app/src/main/java/com/u1/slicer/viewer/GcodeInstancePacker.kt`:

```kotlin
package com.u1.slicer.viewer

import com.u1.slicer.gcode.MoveType
import com.u1.slicer.gcode.ParsedGcode
import kotlin.math.sqrt

data class InstanceLayerRange(val firstInstance: Int, val instanceCount: Int)

data class InstancePackResult(
    val instanceData: FloatArray,
    val totalInstances: Int,
    val layerRanges: List<InstanceLayerRange>
)

object GcodeInstancePacker {

    private const val FLOATS_PER_INSTANCE = 12  // 3 start + 3 end + 4 color + 2 dimensions
    private const val HALF_WIDTH = 0.28f
    private const val HALF_HEIGHT = 0.18f

    fun pack(
        gcode: ParsedGcode,
        extruderColors: Array<FloatArray>,
        featureTypeColors: Array<FloatArray>,
        useFeatureColors: Boolean
    ): InstancePackResult {
        val totalLayers = gcode.layers.size
        if (totalLayers == 0) return InstancePackResult(FloatArray(0), 0, emptyList())

        // Count extrusion moves for allocation
        var totalExtrudeMoves = 0
        for (layer in gcode.layers) {
            for (move in layer.moves) {
                if (move.type == MoveType.EXTRUDE) totalExtrudeMoves++
            }
        }
        if (totalExtrudeMoves == 0) return InstancePackResult(FloatArray(0), 0, emptyList())

        val data = FloatArray(totalExtrudeMoves * FLOATS_PER_INSTANCE)
        var offset = 0
        val layerRanges = mutableListOf<InstanceLayerRange>()
        var instanceCount = 0

        for ((layerIdx, layer) in gcode.layers.withIndex()) {
            val layerFirstInstance = instanceCount

            val layerBrightness = if (totalLayers <= 1) 1.0f
            else 0.45f + 0.55f * (layerIdx.toFloat() / (totalLayers - 1))

            for (move in layer.moves) {
                if (move.type != MoveType.EXTRUDE) continue

                // Skip zero-length moves
                val dx = move.x1 - move.x0
                val dy = move.y1 - move.y0
                if (dx * dx + dy * dy < 0.000001f) continue

                val baseColor = if (useFeatureColors) {
                    featureTypeColors[move.featureType.toInt().coerceIn(0, featureTypeColors.size - 1)]
                } else {
                    extruderColors[move.extruder.coerceIn(0, extruderColors.size - 1)]
                }

                // Start position
                data[offset++] = move.x0
                data[offset++] = move.y0
                data[offset++] = layer.z
                // End position
                data[offset++] = move.x1
                data[offset++] = move.y1
                data[offset++] = layer.z
                // Color (brightness-adjusted)
                data[offset++] = (baseColor[0] * layerBrightness).coerceAtMost(1.0f)
                data[offset++] = (baseColor[1] * layerBrightness).coerceAtMost(1.0f)
                data[offset++] = (baseColor[2] * layerBrightness).coerceAtMost(1.0f)
                data[offset++] = baseColor[3]
                // Dimensions
                data[offset++] = HALF_WIDTH
                data[offset++] = HALF_HEIGHT

                instanceCount++
            }

            layerRanges.add(InstanceLayerRange(layerFirstInstance, instanceCount - layerFirstInstance))
        }

        // Trim if zero-length moves were skipped
        val trimmedData = if (offset < data.size) data.copyOf(offset) else data
        return InstancePackResult(trimmedData, instanceCount, layerRanges)
    }
}
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.u1.slicer.viewer.GcodeRendererGeometryTest" --no-daemon`
Expected: All 8 tests PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/u1/slicer/viewer/GcodeInstancePacker.kt
git commit -m "feat: implement GcodeInstancePacker for instanced tube data"
```

---

### Task 4: Rewrite GcodeRenderer to use instanced drawing

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/viewer/GcodeRenderer.kt`

This is the core change. We replace the old tube VBO/VAO with an instance VBO, load the new instanced shader, and draw with `glDrawArraysInstanced`. The travel-lines path stays as-is. The old `useTubes` flag, `tubeData` expansion, miter logic, and fallback branch are all removed.

- [ ] **Step 1: Replace shader loading in `onSurfaceCreated`**

In `GcodeRenderer.kt`, add a second shader field and load the instanced shader alongside the existing one (which is still used for travel lines):

Replace:
```kotlin
    private var toolpathShader: ShaderProgram? = null
```
With:
```kotlin
    private var toolpathShader: ShaderProgram? = null       // for travel GL_LINES
    private var instancedShader: ShaderProgram? = null      // for instanced extrusion tubes
```

In `onSurfaceCreated`, after the existing `toolpathShader = ...` line, add:
```kotlin
        instancedShader = ShaderProgram(context, "shaders/toolpath_instanced.vert", "shaders/toolpath_instanced.frag")
```

- [ ] **Step 2: Replace tube fields with instance fields**

Remove these fields:
```kotlin
    private var tubeVAO = 0
    private var tubeVBO = 0
    private data class TubeLayerRange(val firstVertex: Int, val vertexCount: Int)
    private val tubeLayerRanges = mutableListOf<TubeLayerRange>()
    private var useTubes = false
```

Replace with:
```kotlin
    private var instanceVAO = 0
    private var instanceVBO = 0
    private var instanceLayerRanges = listOf<InstanceLayerRange>()
    private var hasInstances = false
```

- [ ] **Step 3: Rewrite `uploadGcode` method**

Replace the entire `uploadGcode` method body with the new implementation. The travel-lines path is preserved but simplified (always just travels, never extrusion fallback). The tube expansion is replaced by `GcodeInstancePacker.pack()` + VBO upload with `glVertexAttribDivisor`.

```kotlin
    fun uploadGcode(gcode: ParsedGcode) {
        lastGcode = gcode

        // --- Clean up previous GPU resources ---
        if (masterVAO != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(masterVAO), 0)
            GLES30.glDeleteBuffers(1, intArrayOf(masterVBO), 0)
            masterVAO = 0; masterVBO = 0
        }
        layerRanges.clear()

        if (instanceVAO != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(instanceVAO), 0)
            GLES30.glDeleteBuffers(1, intArrayOf(instanceVBO), 0)
            instanceVAO = 0; instanceVBO = 0
        }
        instanceLayerRanges = emptyList()
        hasInstances = false

        totalLayers = gcode.layers.size
        maxLayer = totalLayers - 1
        if (totalLayers == 0) return

        // --- Pack instanced extrusion data ---
        val packResult = GcodeInstancePacker.pack(gcode, extruderColors, featureTypeColors, useFeatureColors)
        instanceLayerRanges = packResult.layerRanges
        hasInstances = packResult.totalInstances > 0

        if (hasInstances) {
            val buf = java.nio.ByteBuffer.allocateDirect(packResult.instanceData.size * 4)
                .order(java.nio.ByteOrder.nativeOrder())
                .asFloatBuffer()
            buf.put(packResult.instanceData)
            buf.flip()

            val vaos = IntArray(1); GLES30.glGenVertexArrays(1, vaos, 0); instanceVAO = vaos[0]
            val vbos = IntArray(1); GLES30.glGenBuffers(1, vbos, 0); instanceVBO = vbos[0]

            GLES30.glBindVertexArray(instanceVAO)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVBO)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, packResult.instanceData.size * 4, buf, GLES30.GL_STATIC_DRAW)

            val stride = 12 * 4  // 48 bytes per instance
            // location 0: a_Start (vec3) — offset 0
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribDivisor(0, 1)
            // location 1: a_End (vec3) — offset 12
            GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribDivisor(1, 1)
            // location 2: a_Color (vec4) — offset 24
            GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, stride, 24)
            GLES30.glEnableVertexAttribArray(2)
            GLES30.glVertexAttribDivisor(2, 1)
            // location 3: a_Dimensions (vec2) — offset 40
            GLES30.glVertexAttribPointer(3, 2, GLES30.GL_FLOAT, false, stride, 40)
            GLES30.glEnableVertexAttribArray(3)
            GLES30.glVertexAttribDivisor(3, 1)

            GLES30.glBindVertexArray(0)
        }

        // --- Build travel-only line VBO ---
        val floatsPerVertex = 7  // 3 pos + 4 color
        var totalTravelMoves = 0
        for (layer in gcode.layers) {
            for (move in layer.moves) {
                if (move.type != MoveType.EXTRUDE) totalTravelMoves++
            }
        }

        if (totalTravelMoves > 0) {
            val lineData = FloatArray(totalTravelMoves * 2 * floatsPerVertex)
            var lineOffset = 0

            for (layer in gcode.layers) {
                val travelFirst = lineOffset / floatsPerVertex
                for (move in layer.moves) {
                    if (move.type == MoveType.EXTRUDE) continue
                    if (lineOffset + floatsPerVertex * 2 > lineData.size) break
                    lineData[lineOffset++] = move.x0; lineData[lineOffset++] = move.y0; lineData[lineOffset++] = layer.z
                    lineData[lineOffset++] = travelColor[0]; lineData[lineOffset++] = travelColor[1]; lineData[lineOffset++] = travelColor[2]; lineData[lineOffset++] = travelColor[3]
                    lineData[lineOffset++] = move.x1; lineData[lineOffset++] = move.y1; lineData[lineOffset++] = layer.z
                    lineData[lineOffset++] = travelColor[0]; lineData[lineOffset++] = travelColor[1]; lineData[lineOffset++] = travelColor[2]; lineData[lineOffset++] = travelColor[3]
                }
                val travelCount = lineOffset / floatsPerVertex - travelFirst
                layerRanges.add(LayerRange(0, 0, travelFirst, travelCount))
            }

            if (lineOffset > 0) {
                val buf = java.nio.ByteBuffer.allocateDirect(lineOffset * 4)
                    .order(java.nio.ByteOrder.nativeOrder())
                    .asFloatBuffer()
                buf.put(lineData, 0, lineOffset)
                buf.flip()

                val vaos = IntArray(1); GLES30.glGenVertexArrays(1, vaos, 0); masterVAO = vaos[0]
                val vbos = IntArray(1); GLES30.glGenBuffers(1, vbos, 0); masterVBO = vbos[0]

                GLES30.glBindVertexArray(masterVAO)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, masterVBO)
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, lineOffset * 4, buf, GLES30.GL_STATIC_DRAW)

                val lineStride = floatsPerVertex * 4
                GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, lineStride, 0)
                GLES30.glEnableVertexAttribArray(0)
                GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, lineStride, 12)
                GLES30.glEnableVertexAttribArray(1)
                GLES30.glBindVertexArray(0)
            }
        }
    }
```

- [ ] **Step 4: Rewrite `drawToolpaths` method**

Replace the `drawToolpaths` method:

```kotlin
    private fun drawToolpaths() {
        if (instanceLayerRanges.isEmpty() && layerRanges.isEmpty()) return

        val layerCount = maxOf(instanceLayerRanges.size, layerRanges.size)
        if (layerCount == 0) return
        val min = minLayer.coerceIn(0, layerCount - 1)
        val max = maxLayer.coerceIn(0, layerCount - 1)

        // Draw extrusion tubes (instanced)
        val iShader = instancedShader
        if (hasInstances && iShader != null && instanceVAO != 0) {
            iShader.use()
            camera.computeMVP()
            GLES30.glUniformMatrix4fv(iShader.getUniformLocation("u_MVPMatrix"), 1, false, camera.mvpMatrix, 0)
            GLES30.glUniformMatrix4fv(iShader.getUniformLocation("u_NormalMatrix"), 1, false, camera.normalMatrix, 0)

            GLES30.glBindVertexArray(instanceVAO)
            for (i in min..max) {
                if (i >= instanceLayerRanges.size) break
                val r = instanceLayerRanges[i]
                if (r.instanceCount > 0) {
                    // Draw 18 vertices per instance, starting from instance offset
                    // We need to use glDrawArraysInstanced with a base-instance workaround:
                    // Since ES 3.0 lacks glDrawArraysInstancedBaseInstance, we batch
                    // contiguous layers into single draw calls where possible.
                    // For simplicity, use per-layer draws with VBO offset rebinding.
                    GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, 18, r.instanceCount)
                }
            }
            GLES30.glBindVertexArray(0)
        }

        // Draw travel moves (GL_LINES, using original toolpath shader)
        val tShader = toolpathShader
        if (showTravel && layerRanges.isNotEmpty() && masterVAO != 0 && tShader != null) {
            tShader.use()
            camera.computeMVP()
            GLES30.glUniformMatrix4fv(tShader.getUniformLocation("u_MVPMatrix"), 1, false, camera.mvpMatrix, 0)
            GLES30.glUniformMatrix4fv(tShader.getUniformLocation("u_NormalMatrix"), 1, false, camera.normalMatrix, 0)

            GLES30.glBindVertexArray(masterVAO)
            for (i in min..max) {
                if (i >= layerRanges.size) break
                val r = layerRanges[i]
                if (r.travelCount > 0) GLES30.glDrawArrays(GLES30.GL_LINES, r.travelFirst, r.travelCount)
            }
            GLES30.glBindVertexArray(0)
        }
    }
```

- [ ] **Step 5: Remove unused imports and the old `LayerRange.extrudeFirst`/`extrudeCount` usage**

The `LayerRange` data class still holds `extrudeFirst`/`extrudeCount` but they're always 0 now. Simplify it:

Replace:
```kotlin
    private data class LayerRange(
        val extrudeFirst: Int, val extrudeCount: Int,
        val travelFirst: Int, val travelCount: Int
    )
```
With:
```kotlin
    private data class LayerRange(val travelFirst: Int, val travelCount: Int)
```

And update the `layerRanges.add(...)` call in `uploadGcode` accordingly:
```kotlin
                layerRanges.add(LayerRange(travelFirst, travelCount))
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/u1/slicer/viewer/GcodeRenderer.kt
git commit -m "feat: replace CPU tube expansion with instanced rendering in GcodeRenderer"
```

---

### Task 5: Fix base-instance problem for per-layer drawing

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/viewer/GcodeRenderer.kt`

OpenGL ES 3.0 has `glDrawArraysInstanced` but **no base-instance parameter** — `gl_InstanceID` always starts at 0. So `glDrawArraysInstanced(GL_TRIANGLES, 0, 18, count)` always reads from instance 0 in the VBO, regardless of which layer we're drawing.

The fix: before each layer's draw call, rebind the VBO with an offset so instance 0 maps to the correct first instance for that layer. We do this by calling `glVertexAttribPointer` with a byte offset of `firstInstance * 48`.

- [ ] **Step 1: Update drawToolpaths to rebind with per-layer offset**

Replace the instanced draw loop in `drawToolpaths`:

```kotlin
            GLES30.glBindVertexArray(instanceVAO)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVBO)
            for (i in min..max) {
                if (i >= instanceLayerRanges.size) break
                val r = instanceLayerRanges[i]
                if (r.instanceCount <= 0) continue

                val byteOffset = r.firstInstance * 48  // 12 floats * 4 bytes
                val stride = 48
                GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, byteOffset)
                GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, byteOffset + 12)
                GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, stride, byteOffset + 24)
                GLES30.glVertexAttribPointer(3, 2, GLES30.GL_FLOAT, false, stride, byteOffset + 40)

                GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, 18, r.instanceCount)
            }
            GLES30.glBindVertexArray(0)
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/u1/slicer/viewer/GcodeRenderer.kt
git commit -m "fix: rebind VBO offset per layer to work around ES 3.0 missing base-instance"
```

---

### Task 6: Build, install, and manually test on device

**Files:** (none modified — verification only)

- [ ] **Step 1: Run unit tests**

Run: `./gradlew testDebugUnitTest --no-daemon`
Expected: All 628+ tests pass (including the 8 new ones).

- [ ] **Step 2: Build and install**

Run: `./gradlew installDebug --no-daemon`
Expected: Build succeeds, APK installs on device.

- [ ] **Step 3: Manual smoke test**

1. Load `calib-cube-10-dual-colour-merged.3mf` → slice → view g-code preview. Tubes should render identically to before (small file, was already using tubes).
2. Load `GreatPyr-2ColorAMS.3mf` → slice → view g-code preview. **This is the key test** — should now show thick tube geometry instead of thin lines. Zoom in to confirm tubes have visible width and lighting.
3. Toggle between extruder color mode and feature-type color mode — colors should update.
4. Toggle travel visibility — travel lines should appear/disappear independently.
5. Slide layer range — only selected layers should render.

- [ ] **Step 4: Commit with test results**

If everything works:
```bash
git add -A
git commit -m "verify: instanced gcode tubes working on device for large and small files"
```

---

### Task 7: Clean up old code

**Files:**
- Modify: `app/src/main/assets/shaders/toolpath.vert`

- [ ] **Step 1: Remove normal-based lighting from the travel-only shader**

Since `toolpath.vert` is now only used for travel GL_LINES (which never have normals), simplify it to remove the normal/lighting code path. The `u_NormalMatrix` uniform and `a_Normal` attribute can stay (unused attributes are harmless and avoids changing the `drawToolpaths` uniform calls), but the lighting branch can be simplified:

Actually — leave it as-is. The shader is tiny and works correctly (the `normalLen < 0.01` branch handles the no-normal case). No change needed.

- [ ] **Step 2: Delete the old `toolpath.frag` if `toolpath_instanced.frag` is identical**

They're identical, but keep both — they serve different shader programs and renaming would add coupling for no benefit.

- [ ] **Step 3: Final commit**

No changes needed in this task — the cleanup is a no-op since the old shaders are still valid for their use case.
