# F3 + F25: Per-Vertex Multi-Color Preview + Single-Color Extruder Picker

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add per-vertex coloring to the 3D model preview so multi-color models display actual extruder colors with live recoloring, plus an extruder picker for single-color models.

**Architecture:** Expand vertex format from 6 to 10 floats (pos+normal+RGBA). Store per-triangle extruder indices alongside geometry. Recoloring writes new RGBA values in-place and does a GPU partial update. Single-color extruder picker is a row of 4 chips on the Prepare screen.

**Tech Stack:** Kotlin, OpenGL ES 3.0 (GLSL 300 es), Jetpack Compose, Android GLSurfaceView

**Spec:** `docs/superpowers/specs/2026-03-15-f3-multicolor-preview-design.md`

---

## Chunk 1: MeshData + Shaders + ModelRenderer

### Task 1: Expand MeshData vertex format and add recolor()

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/viewer/MeshData.kt`
- Test: `app/src/test/java/com/u1/slicer/viewer/MeshDataTest.kt` (new)

- [ ] **Step 1: Write MeshData recolor test**

Create `app/src/test/java/com/u1/slicer/viewer/MeshDataTest.kt`:

```kotlin
package com.u1.slicer.viewer

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MeshDataTest {

    @Test
    fun `recolor writes correct RGBA for each triangle`() {
        // 2 triangles, 6 vertices, extruder indices [0, 1]
        val triCount = 2
        val buf = MeshData.allocateBuffer(triCount)
        // Fill with dummy pos+normal+color (10 floats per vertex)
        for (i in 0 until triCount * 3) {
            buf.put(floatArrayOf(
                i.toFloat(), 0f, 0f,   // pos
                0f, 0f, 1f,            // normal
                1f, 1f, 1f, 1f         // color (white placeholder)
            ))
        }
        buf.flip()

        val indices = byteArrayOf(0, 1) // tri 0 → extruder 0, tri 1 → extruder 1
        val mesh = MeshData(
            vertices = buf, vertexCount = 6,
            minX = 0f, minY = 0f, minZ = 0f,
            maxX = 5f, maxY = 0f, maxZ = 0f,
            extruderIndices = indices
        )

        val palette = listOf(
            floatArrayOf(1f, 0f, 0f, 1f),  // extruder 0 = red
            floatArrayOf(0f, 0f, 1f, 1f)   // extruder 1 = blue
        )
        mesh.recolor(palette)

        // Verify tri 0 vertices (indices 0,1,2) have red
        for (v in 0 until 3) {
            val base = v * MeshData.FLOATS_PER_VERTEX + 6
            assertEquals("tri0 v$v R", 1f, buf.get(base), 0.001f)
            assertEquals("tri0 v$v G", 0f, buf.get(base + 1), 0.001f)
            assertEquals("tri0 v$v B", 0f, buf.get(base + 2), 0.001f)
        }
        // Verify tri 1 vertices (indices 3,4,5) have blue
        for (v in 3 until 6) {
            val base = v * MeshData.FLOATS_PER_VERTEX + 6
            assertEquals("tri1 v$v R", 0f, buf.get(base), 0.001f)
            assertEquals("tri1 v$v G", 0f, buf.get(base + 1), 0.001f)
            assertEquals("tri1 v$v B", 1f, buf.get(base + 2), 0.001f)
        }
    }

    @Test
    fun `recolor clamps out-of-range extruder index to last palette entry`() {
        val buf = MeshData.allocateBuffer(1)
        for (i in 0 until 3) {
            buf.put(floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f, 1f))
        }
        buf.flip()

        val indices = byteArrayOf(5) // index 5, but palette only has 2 entries
        val mesh = MeshData(
            vertices = buf, vertexCount = 3,
            minX = 0f, minY = 0f, minZ = 0f,
            maxX = 0f, maxY = 0f, maxZ = 0f,
            extruderIndices = indices
        )

        val palette = listOf(
            floatArrayOf(1f, 0f, 0f, 1f),
            floatArrayOf(0f, 1f, 0f, 1f)  // green = last entry
        )
        mesh.recolor(palette)

        // Should clamp to green (last palette entry)
        val base = 6
        assertEquals(0f, buf.get(base), 0.001f)
        assertEquals(1f, buf.get(base + 1), 0.001f)
        assertEquals(0f, buf.get(base + 2), 0.001f)
    }

    @Test
    fun `recolor with null extruderIndices is no-op`() {
        val buf = MeshData.allocateBuffer(1)
        for (i in 0 until 3) {
            buf.put(floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 0.5f, 0.5f, 0.5f, 1f))
        }
        buf.flip()

        val mesh = MeshData(
            vertices = buf, vertexCount = 3,
            minX = 0f, minY = 0f, minZ = 0f,
            maxX = 0f, maxY = 0f, maxZ = 0f,
            extruderIndices = null
        )

        mesh.recolor(listOf(floatArrayOf(1f, 0f, 0f, 1f)))

        // Color should be unchanged (0.5, 0.5, 0.5)
        assertEquals(0.5f, buf.get(6), 0.001f)
        assertEquals(0.5f, buf.get(7), 0.001f)
    }

    @Test
    fun `allocateBuffer produces correct size for 10-float format`() {
        val buf = MeshData.allocateBuffer(100)
        assertEquals(100 * 3 * 10, buf.capacity())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew testDebugUnitTest --tests "com.u1.slicer.viewer.MeshDataTest" --no-daemon` (PowerShell)
Expected: FAIL — `MeshData` has no `extruderIndices` field or `recolor()` method yet.

- [ ] **Step 3: Update MeshData with new vertex format, extruderIndices, and recolor()**

Modify `app/src/main/java/com/u1/slicer/viewer/MeshData.kt`:

```kotlin
package com.u1.slicer.viewer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Holds interleaved vertex data for OpenGL rendering.
 * Format per vertex: x, y, z, nx, ny, nz, r, g, b, a (10 floats = 40 bytes)
 */
data class MeshData(
    val vertices: FloatBuffer,
    val vertexCount: Int,
    val minX: Float, val minY: Float, val minZ: Float,
    val maxX: Float, val maxY: Float, val maxZ: Float,
    val extruderIndices: ByteArray? = null
) {
    val centerX get() = (minX + maxX) / 2
    val centerY get() = (minY + maxY) / 2
    val centerZ get() = (minZ + maxZ) / 2
    val sizeX get() = maxX - minX
    val sizeY get() = maxY - minY
    val sizeZ get() = maxZ - minZ
    val maxDimension get() = maxOf(sizeX, sizeY, sizeZ)
    val hasPerVertexColor get() = extruderIndices != null

    /** Rewrite RGBA color slots in the vertex buffer using the palette. */
    fun recolor(colorPalette: List<FloatArray>) {
        val indices = extruderIndices ?: return
        if (colorPalette.isEmpty()) return
        val lastIdx = colorPalette.size - 1
        for (tri in indices.indices) {
            val color = colorPalette[minOf(indices[tri].toInt() and 0xFF, lastIdx)]
            for (v in 0 until 3) {
                val base = (tri * 3 + v) * FLOATS_PER_VERTEX + 6 // offset to RGBA
                vertices.put(base, color[0])
                vertices.put(base + 1, color[1])
                vertices.put(base + 2, color[2])
                vertices.put(base + 3, color[3])
            }
        }
    }

    companion object {
        const val FLOATS_PER_VERTEX = 10 // x,y,z, nx,ny,nz, r,g,b,a
        const val BYTES_PER_VERTEX = FLOATS_PER_VERTEX * 4

        fun allocateBuffer(triangleCount: Int): FloatBuffer {
            val floatCount = triangleCount * 3 * FLOATS_PER_VERTEX
            return ByteBuffer.allocateDirect(floatCount * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew testDebugUnitTest --tests "com.u1.slicer.viewer.MeshDataTest" --no-daemon`
Expected: 4 PASS

- [ ] **Step 5: Run existing ThreeMfMeshParserTest to check for breakage**

Run: `.\gradlew testDebugUnitTest --tests "com.u1.slicer.viewer.ThreeMfMeshParserTest" --no-daemon`
Expected: FAIL — existing tests reference old 6-float format. These will be fixed in Task 3.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/u1/slicer/viewer/MeshData.kt app/src/test/java/com/u1/slicer/viewer/MeshDataTest.kt
git commit -m "feat(F3): expand MeshData to 10-float vertex format with recolor()"
```

---

### Task 2: Update shaders for per-vertex color

**Files:**
- Modify: `app/src/main/assets/shaders/model.vert`
- Modify: `app/src/main/assets/shaders/model.frag`

- [ ] **Step 1: Read current shader files**

Read `app/src/main/assets/shaders/model.vert` and `app/src/main/assets/shaders/model.frag` to confirm current contents.

- [ ] **Step 2: Update model.vert — add a_Color input, u_UseVertexColor uniform, u_Color uniform, v_Color output**

Add after existing attribute declarations:
```glsl
layout(location = 2) in vec4 a_Color;
```

**IMPORTANT**: Move `uniform vec4 u_Color;` FROM the fragment shader INTO the vertex shader. It must be declared here because `v_Color` is computed in the vertex shader. Add:
```glsl
uniform vec4 u_Color;
uniform int u_UseVertexColor;
```

Add output:
```glsl
out vec4 v_Color;
```

At end of `main()`, after lighting calculation:
```glsl
v_Color = (u_UseVertexColor == 1) ? a_Color : u_Color;
```

- [ ] **Step 3: Update model.frag — use v_Color instead of u_Color**

The fragment shader should:
- **Remove** `uniform vec4 u_Color;` (moved to vertex shader)
- Receive `in vec4 v_Color;` (interpolated from vertex shader)
- Output: `fragColor = vec4(v_Color.rgb * v_Intensity, v_Color.a);`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/assets/shaders/model.vert app/src/main/assets/shaders/model.frag
git commit -m "feat(F3): add per-vertex color support to model shaders"
```

---

### Task 3: Update ModelRenderer for new vertex format + recolor

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/viewer/ModelRenderer.kt`

- [ ] **Step 1: Read ModelRenderer.kt fully**

Read the entire file. Note:
- `uploadMesh()` (line ~162): VAO/VBO creation, vertex attrib pointers
- `drawModel()` (line ~194): single instance drawing
- `drawModelAt()` (line ~220): multi-instance drawing
- `setupBox()` (line ~340): wipe tower box VAO setup
- Shader uniform location variables
- `onDrawFrame()` loop

- [ ] **Step 2: Add modelVBO field and u_UseVertexColor uniform location**

Add class fields:
```kotlin
private var modelVBO = 0
private var useVertexColorLoc = -1
```

In `onSurfaceCreated()`, after getting other uniform locations for modelShader:
```kotlin
useVertexColorLoc = GLES30.glGetUniformLocation(modelShader.program, "u_UseVertexColor")
```

- [ ] **Step 3: Update uploadMesh() for 10-float stride + store VBO handle**

In `uploadMesh()`:
- Change buffer usage hint from `GL_STATIC_DRAW` to `GL_DYNAMIC_DRAW`
- Store the VBO handle: `modelVBO = vbos[0]`
- Update vertex attribute pointers:
  - Attrib 0 (pos): 3 floats, offset 0, stride `MeshData.BYTES_PER_VERTEX` (40)
  - Attrib 1 (normal): 3 floats, offset 12, stride 40
  - Attrib 2 (color): 4 floats, offset 24, stride 40 — `glEnableVertexAttribArray(2)`

- [ ] **Step 4: Add updateColorData() method**

```kotlin
/** Re-upload vertex buffer after recolor(). Must be called on GL thread. */
private fun updateColorData(mesh: MeshData) {
    if (modelVBO == 0) return
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, modelVBO)
    mesh.vertices.position(0)
    GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0,
        mesh.vertexCount * MeshData.BYTES_PER_VERTEX, mesh.vertices)
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
}
```

- [ ] **Step 5: Add pendingRecolor mechanism**

Add field:
```kotlin
@Volatile var pendingRecolor: List<FloatArray>? = null
```

In `onDrawFrame()`, after the pending mesh upload block, add:
```kotlin
pendingRecolor?.let { palette ->
    pendingRecolor = null
    currentMesh?.let { mesh ->
        mesh.recolor(palette)
        updateColorData(mesh)
    }
}
```

**Race condition note:** If `pendingRecolor` is set BEFORE `pendingMesh` arrives, the recolor is silently dropped because `currentMesh` is null. To handle this, also apply `pendingRecolor` (if non-null) right after uploading a new pending mesh in the same frame:

```kotlin
pendingMesh?.let { mesh ->
    pendingMesh = null
    uploadMesh(mesh)
    currentMesh = mesh
    // Apply any pending recolor immediately after upload
    pendingRecolor?.let { palette ->
        pendingRecolor = null
        mesh.recolor(palette)
        updateColorData(mesh)
    }
}
```

- [ ] **Step 6: Update drawModel() and drawModelAt() to set u_UseVertexColor**

In both `drawModel()` and `drawModelAt()`, before the `glDrawArrays` call:
```kotlin
GLES30.glUniform1i(useVertexColorLoc, if (mesh.hasPerVertexColor) 1 else 0)
```

Still set `u_Color` as before (it's the fallback when `u_UseVertexColor == 0`).

- [ ] **Step 7: Update drawBox() (wipe tower) to disable per-vertex color**

Before drawing the box, ensure:
```kotlin
GLES30.glUniform1i(useVertexColorLoc, 0)
GLES30.glVertexAttrib4f(2, 1f, 1f, 1f, 1f) // default white attrib
```

The box VAO doesn't have attrib 2 enabled (VAOs store their own attrib state), so this is a safety measure.

- [ ] **Step 8: Run the app to verify shaders compile and model renders**

Build and install: `.\gradlew installDebug --no-daemon`
Launch on test device. Load a simple STL file. Verify it renders (single color, no crash).

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/u1/slicer/viewer/ModelRenderer.kt
git commit -m "feat(F3): update ModelRenderer for 10-float vertex format + recolor pipeline"
```

---

### Task 3.5: Update StlParser for 10-float vertex format

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/viewer/StlParser.kt`
- Modify: `app/src/test/java/com/u1/slicer/viewer/StlParserTest.kt`

**CRITICAL**: `StlParser.parseBinary()` and `StlParser.parseAscii()` both call `MeshData.allocateBuffer()` and write 6 floats per vertex. After Task 1 changed `FLOATS_PER_VERTEX` to 10, the buffer is sized for 10 floats but only 6 are written — leaving garbage in the color slots and producing wrong vertex counts.

- [ ] **Step 1: Read StlParser.kt**

Read full file. Find where pos+normal are written to the buffer.

- [ ] **Step 2: Update both parseBinary() and parseAscii() to write 10 floats per vertex**

After each set of 6 floats (pos+normal), append 4 default color floats:
```kotlin
buf.put(0.7f); buf.put(0.7f); buf.put(0.7f); buf.put(1f) // default gray
```

Also return `extruderIndices = null` in the MeshData constructor (STL files have no color data — all-zero indices are not needed since `recolor()` is a no-op with null indices).

- [ ] **Step 3: Run existing StlParserTest**

Run: `.\gradlew testDebugUnitTest --tests "com.u1.slicer.viewer.StlParserTest" --no-daemon`
Expected: All 9 tests PASS.

- [ ] **Step 4: Add STL fallback color test**

Add to `StlParserTest.kt`:
```kotlin
@Test
fun `parsed STL has null extruderIndices`() {
    // Use any existing STL test helper to parse a basic STL
    val mesh = parseBinaryStl(buildMinimalBinaryStl(1))
    assertNotNull(mesh)
    assertNull("STL files should have null extruderIndices", mesh!!.extruderIndices)
}
```

- [ ] **Step 5: Run tests and commit**

Run: `.\gradlew testDebugUnitTest --tests "com.u1.slicer.viewer.StlParserTest" --no-daemon`

```bash
git add app/src/main/java/com/u1/slicer/viewer/StlParser.kt app/src/test/java/com/u1/slicer/viewer/StlParserTest.kt
git commit -m "feat(F3): update StlParser for 10-float vertex format"
```

---

### Task 4: Update ThreeMfMeshParser for 10-float output + extruder indices

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/viewer/ThreeMfMeshParser.kt`
- Modify: `app/src/test/java/com/u1/slicer/viewer/ThreeMfMeshParserTest.kt`

- [ ] **Step 1: Read ThreeMfMeshParser.kt fully**

Read entire file. Note:
- `GrowableFloatArray` / `GrowableIntArray` (lines ~432-454)
- `buildMeshData()` (lines ~369-428): vertex buffer construction
- `streamParseModel()`: line-by-line XML parsing
- `collectMeshes()`: recursive mesh collection with transforms

- [ ] **Step 2: Add GrowableByteArray as private class**

After the existing `GrowableIntArray`, add:
```kotlin
private class GrowableByteArray(initialCapacity: Int = 8192) {
    private var data = ByteArray(initialCapacity)
    var size = 0
    fun add(value: Byte) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }
    fun toArray(): ByteArray = data.copyOf(size)
}
```

- [ ] **Step 3: Thread objectId through collectMeshes() into buildMeshData()**

Currently `collectMeshes()` returns `List<Pair<RawMesh, FloatArray?>>` (mesh + transform). It discards the objectId. Change the return type to carry objectId:

Create a small data class or use a Triple:
```kotlin
private data class MeshWithContext(
    val mesh: RawMesh,
    val transform: FloatArray?,
    val objectId: Int
)
```

Update `collectMeshes()` to populate `objectId` from the component's `objectid` attribute. Update `buildMeshData()` to accept `List<MeshWithContext>`.

- [ ] **Step 4: Update buildMeshData() to write 10 floats per vertex + collect extruder indices**

Add `extruderIndexMap: Map<Int, Byte>? = null` parameter to `buildMeshData()`. This maps object ID → extruder index.

In the per-triangle loop, after writing pos+normal (6 floats), write 4 color floats:
```kotlin
// Default color — will be overwritten by recolor()
buf.put(0.7f); buf.put(0.7f); buf.put(0.7f); buf.put(1f)
```

Build a `GrowableByteArray` for extruder indices. For each triangle:
- Look up `extruderIndexMap[meshContext.objectId]` for the current mesh's extruder index
- If found, use it. Otherwise default to 0.

Return `MeshData(..., extruderIndices = extruderIdxArray.toArray())`.

- [ ] **Step 4: Update parse() to accept and pass through extruder mapping**

Add optional parameter `extruderMap: Map<Int, Byte>? = null` to `parse()`. Pass it through to `buildMeshData()`.

The mapping comes from ThreeMfInfo (parsed by ThreeMfParser). The ViewModel will provide it.

- [ ] **Step 5: Fix existing ThreeMfMeshParserTest tests**

The existing tests use `buildModelXml()` helper and check bounds. Since the vertex format changed from 6→10 floats, the tests should still work because they only check `vertexCount` and bounds (minX/maxX etc.), not buffer layout. Verify this by running:

Run: `.\gradlew testDebugUnitTest --tests "com.u1.slicer.viewer.ThreeMfMeshParserTest" --no-daemon`
Expected: All 7 tests PASS (bounds and vertex counts are format-independent).

- [ ] **Step 6: Add new test for extruder index output**

Add to `ThreeMfMeshParserTest.kt`:
```kotlin
@Test
fun `parse with extruder map assigns correct indices`() {
    val xml = """<?xml version="1.0" encoding="UTF-8"?>
        <model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
        <resources>
            <object id="1" type="model">
                <mesh>
                    <vertices>
                        <vertex x="0" y="0" z="0" />
                        <vertex x="10" y="0" z="0" />
                        <vertex x="5" y="10" z="0" />
                    </vertices>
                    <triangles>
                        <triangle v1="0" v2="1" v3="2" />
                    </triangles>
                </mesh>
            </object>
        </resources>
        <build>
            <item objectid="1" />
        </build>
        </model>"""
    val file = create3mfZip(xml)
    val mesh = ThreeMfMeshParser.parse(file, extruderMap = mapOf(1 to 2.toByte()))
    assertNotNull(mesh)
    assertNotNull(mesh!!.extruderIndices)
    assertEquals(1, mesh.extruderIndices!!.size) // 1 triangle
    assertEquals(2.toByte(), mesh.extruderIndices!![0])
}

@Test
fun `parse without extruder map defaults to index 0`() {
    val model = buildModelXml(
        transform = null,
        vertices = listOf(Triple(0f, 0f, 0f), Triple(10f, 0f, 0f), Triple(5f, 10f, 0f)),
        triangles = listOf(Triple(0, 1, 2))
    )
    val mesh = parseModel(model)
    assertNotNull(mesh)
    assertNotNull(mesh!!.extruderIndices)
    assertEquals(1, mesh.extruderIndices!!.size)
    assertEquals(0.toByte(), mesh.extruderIndices!![0])
}
```

- [ ] **Step 7: Run all viewer tests**

Run: `.\gradlew testDebugUnitTest --tests "com.u1.slicer.viewer.*" --no-daemon`
Expected: All tests PASS (MeshDataTest + ThreeMfMeshParserTest).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/u1/slicer/viewer/ThreeMfMeshParser.kt app/src/test/java/com/u1/slicer/viewer/ThreeMfMeshParserTest.kt
git commit -m "feat(F3): ThreeMfMeshParser outputs 10-float vertices + extruder indices"
```

---

### Task 5: Update ModelViewerView with recolorMesh()

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/viewer/ModelViewerView.kt`

- [ ] **Step 1: Read ModelViewerView.kt**

Read full file.

- [ ] **Step 2: Add recolorMesh() method**

```kotlin
/** Recolor the mesh using the given palette. Thread-safe: queues work on GL thread. */
fun recolorMesh(colorPalette: List<FloatArray>) {
    renderer.pendingRecolor = colorPalette
    requestRender()
}
```

This sets the volatile `pendingRecolor` field on the renderer (which picks it up in `onDrawFrame()`), following the same pattern as `pendingMesh`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/u1/slicer/viewer/ModelViewerView.kt
git commit -m "feat(F3): add recolorMesh() to ModelViewerView"
```

---

### Task 6: Integration test — load and render on device

- [ ] **Step 1: Build and install**

Run: `.\gradlew installDebug --no-daemon`

- [ ] **Step 2: Smoke test on device**

Launch the app on the test device (43211JEKB16931). Load a simple STL file. Verify:
- Model renders with a solid color (no crash, no visual artifacts)
- Wipe tower preview still renders correctly
- Placement mode (drag) still works

- [ ] **Step 3: Check logcat for GL errors**

Run: `adb -s 43211JEKB16931 logcat -s "SlicerVM" -d | tail -30`

- [ ] **Step 4: Run full unit test suite**

Run: `.\gradlew testDebugUnitTest --no-daemon`
Expected: All tests pass.

- [ ] **Step 5: Commit if any fixups were needed**

---

## Chunk 2: Paint Data Parsing + Per-Volume Coloring

### Task 7: Parse paint_color from 3MF triangles

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/viewer/ThreeMfMeshParser.kt`
- Test: `app/src/test/java/com/u1/slicer/viewer/ThreeMfMeshParserTest.kt`

- [ ] **Step 1: Investigate paint_color attribute format**

Read actual 3MF test files to determine the format. Check the component .model files inside multi-color 3MFs. Use the existing `streamDetectPaintData()` in `ThreeMfParser.kt` (lines 388-414) for reference on what patterns to look for.

Key test files:
- `app/src/androidTest/assets/calib-cube-10-dual-colour-merged.3mf` (per-volume, 2 colors)
- `app/src/androidTest/assets/Shashibo-h2s-textured.3mf` (has paint data)
- `app/src/androidTest/assets/PrusaSlicer-printables-Korok_mask_4colour.3mf` (4 colors)

Extract a sample triangle line with `paint_color` and document the format.

- [ ] **Step 2: Add paint_color extraction to triangle parsing**

In `streamParseModel()`, in the triangle parsing section, after extracting v1/v2/v3:
- Check if the line contains `"paint_color"` (fast-path — skip regex for 99.9% of lines)
- If present, extract the attribute value and map to an extruder index
- Store per-triangle paint index in a parallel `GrowableByteArray`
- If not present, use `Byte.MIN_VALUE` as sentinel (will be overwritten by volume default)

- [ ] **Step 3: Merge paint indices with volume indices in buildMeshData()**

In `buildMeshData()`, for each triangle:
1. If paint index is not sentinel → use paint index
2. Else → use volume's extruder index from `extruderMap`
3. Else → use 0

- [ ] **Step 4: Write test for paint_color parsing**

Add to `ThreeMfMeshParserTest.kt`:
```kotlin
@Test
fun `parse extracts paint_color as extruder index per triangle`() {
    // Build a 3MF with paint_color attributes on triangles
    val xml = """<?xml version="1.0" encoding="UTF-8"?>
        <model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
        <resources>
            <object id="1" type="model">
                <mesh>
                    <vertices>
                        <vertex x="0" y="0" z="0" />
                        <vertex x="10" y="0" z="0" />
                        <vertex x="5" y="10" z="0" />
                        <vertex x="15" y="0" z="0" />
                    </vertices>
                    <triangles>
                        <triangle v1="0" v2="1" v3="2" paint_color="2" />
                        <triangle v1="1" v2="3" v3="2" />
                    </triangles>
                </mesh>
            </object>
        </resources>
        <build>
            <item objectid="1" />
        </build>
        </model>"""
    val file = create3mfZip(xml)
    val mesh = ThreeMfMeshParser.parse(file)
    assertNotNull(mesh)
    assertEquals(2, mesh!!.extruderIndices!!.size)
    assertEquals(2.toByte(), mesh.extruderIndices!![0]) // paint_color=2
    assertEquals(0.toByte(), mesh.extruderIndices!![1]) // no paint → default 0
}
```

**Note:** The exact attribute value format may differ from this test once Step 1 is complete. Update the test to match the actual format discovered.

- [ ] **Step 5: Run tests**

Run: `.\gradlew testDebugUnitTest --tests "com.u1.slicer.viewer.*" --no-daemon`
Expected: All PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/u1/slicer/viewer/ThreeMfMeshParser.kt app/src/test/java/com/u1/slicer/viewer/ThreeMfMeshParserTest.kt
git commit -m "feat(F3): parse paint_color attributes for per-triangle extruder indices"
```

---

### Task 8: Wire per-volume extruder mapping from ViewModel to mesh parser

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`
- Modify: `app/src/main/java/com/u1/slicer/MainActivity.kt` (InlineModelPreview)

- [ ] **Step 1: Read relevant sections of SlicerViewModel.kt**

Focus on:
- Where `ThreeMfMeshParser.parse()` is called (likely in `loadNativeModel()` or passed through composable)
- Where `ThreeMfInfo` with extruder assignments is available
- How `InlineModelPreview` receives the model file path

- [ ] **Step 2: Build extruder map from available data**

Read `ThreeMfInfo.kt` and `ThreeMfParser.kt` to find what per-object extruder data is actually available. The extruder→object mapping comes from `model_settings.config` or `Slic3r_PE_model.config` in the 3MF — `ThreeMfParser.parseModelSettingsConfig()` already parses this.

In `SlicerViewModel`, add a function that builds `Map<Int, Byte>` (objectId → extruder index) from the parsed data. The exact implementation depends on the data classes available — likely `ThreeMfInfo.usedExtruderIndices` plus the per-object extruder assignments from the config. Read the actual data structures before writing this function.

**Key principle:** Map objectId → 0-based extruder index. For compound objects (after restructureForMultiColor), each inlined mesh object has its own objectId with a per-volume extruder assignment.

- [ ] **Step 3: Expose extruder map as StateFlow**

Add:
```kotlin
private val _extruderMap = MutableStateFlow<Map<Int, Byte>>(emptyMap())
val extruderMap: StateFlow<Map<Int, Byte>> = _extruderMap.asStateFlow()
```

Set it after parsing ThreeMfInfo in `loadModel()`.

- [ ] **Step 4: Pass extruder map through to InlineModelPreview**

In `MainActivity.kt` `PrepareScreen`, collect `extruderMap` from viewModel and pass to `InlineModelPreview` as a new parameter.

In `InlineModelPreview`, pass it to `ThreeMfMeshParser.parse(file, extruderMap = extruderMap)`.

- [ ] **Step 5: Build and verify on device**

Run: `.\gradlew installDebug --no-daemon`
Load a multi-color 3MF (e.g. calib-cube-10-dual-colour-merged.3mf via file picker). Verify the preview shows distinct colors for different volumes.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/u1/slicer/SlicerViewModel.kt app/src/main/java/com/u1/slicer/MainActivity.kt
git commit -m "feat(F3): wire per-volume extruder mapping from ViewModel to mesh parser"
```

---

### Task 9: Wire recoloring on extruder mapping changes

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/MainActivity.kt` (InlineModelPreview)
- Modify: `app/src/main/java/com/u1/slicer/viewer/ModelViewerView.kt` (if needed)

- [ ] **Step 1: Read InlineModelPreview composable**

Read the `InlineModelPreview` composable in `MainActivity.kt`. Note the existing `LaunchedEffect` blocks that set mesh and colors.

- [ ] **Step 2: Add LaunchedEffect for recoloring**

In `InlineModelPreview`, add a `LaunchedEffect` that triggers when `extruderColors` or `colorMapping` changes:

```kotlin
LaunchedEffect(extruderColors, colorMapping) {
    val v = viewerView ?: return@LaunchedEffect
    val m = mesh ?: return@LaunchedEffect
    if (!m.hasPerVertexColor) return@LaunchedEffect

    // Build palette from active extruder colors
    val palette = extruderColors.map { hex ->
        try {
            val c = android.graphics.Color.parseColor(hex)
            floatArrayOf(
                android.graphics.Color.red(c) / 255f,
                android.graphics.Color.green(c) / 255f,
                android.graphics.Color.blue(c) / 255f,
                1f
            )
        } catch (_: Exception) {
            floatArrayOf(0.91f, 0.48f, 0f, 1f) // default orange
        }
    }
    v.recolorMesh(palette)
}
```

**Note:** The palette needs to be indexed by extruder slot (0-3). The `extruderIndices` in MeshData are 0-based extruder indices that directly index into this palette.

- [ ] **Step 3: Also recolor on initial mesh load**

After `setMesh()` is called in the mesh-loading `LaunchedEffect`, immediately call `recolorMesh()` with the current palette so the initial render shows correct colors.

- [ ] **Step 4: Build and verify live recoloring on device**

Run: `.\gradlew installDebug --no-daemon`
Load a multi-color 3MF. Open the multi-color dialog. Change an extruder mapping. Verify the 3D preview updates immediately.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/MainActivity.kt
git commit -m "feat(F3): live recoloring on extruder mapping changes"
```

---

## Chunk 3: Single-Color Extruder Picker (F25) + Final Testing

### Task 10: Add selectedExtruder StateFlow to ViewModel

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`

- [ ] **Step 1: Add selectedExtruder state**

```kotlin
private val _selectedExtruder = MutableStateFlow(0)
val selectedExtruder: StateFlow<Int> = _selectedExtruder.asStateFlow()

fun setSelectedExtruder(index: Int) {
    _selectedExtruder.value = index
    // Trigger recolor via activeExtruderColors update
    updateSingleColorExtruder(index)
}

private fun updateSingleColorExtruder(index: Int) {
    // For single-color models, all triangles have extruder index 0.
    // The recolor palette is indexed by extruder index in the mesh,
    // so the selected extruder's color must go at palette index 0.
    val presets = extruderPresets.value
    val color = presets.firstOrNull { it.index == index }?.color ?: ""
    val resolvedColor = color.ifBlank { ExtruderPreset.DEFAULT_COLORS[index] }
    // Put selected color at index 0 so recolor() finds it
    val colors = MutableList(4) { "" }
    colors[0] = resolvedColor
    _activeExtruderColors.value = colors
}
```

- [ ] **Step 2: Reset selectedExtruder on new model load**

In `loadModel()`, after determining it's a single-color model, reset:
```kotlin
_selectedExtruder.value = 0
```

- [ ] **Step 3: Wire selectedExtruder into startSlicing()**

In `startSlicing()`, when building model instances for a single-color model, use `_selectedExtruder.value` as the extruder assignment instead of hardcoded 0.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/u1/slicer/SlicerViewModel.kt
git commit -m "feat(F25): add selectedExtruder StateFlow for single-color extruder picker"
```

---

### Task 11: Add extruder picker UI to PrepareScreen

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/MainActivity.kt` (PrepareScreen)

- [ ] **Step 1: Read PrepareScreen layout**

Read the section around the scale/copies controls in `PrepareScreen` to find the right insertion point.

- [ ] **Step 2: Add ExtruderPickerRow composable**

```kotlin
@Composable
fun ExtruderPickerRow(
    selectedExtruder: Int,
    extruderPresets: List<ExtruderPreset>,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Extruder:", style = MaterialTheme.typography.bodyMedium)
        for (i in 0 until 4) {
            val preset = extruderPresets.firstOrNull { it.index == i }
            val color = preset?.color?.takeIf { it.isNotBlank() && it != "#FFFFFF" }
                ?: ExtruderPreset.DEFAULT_COLORS[i]
            val parsedColor = try {
                Color(android.graphics.Color.parseColor(color))
            } catch (_: Exception) { Color.Gray }

            val isSelected = selectedExtruder == i
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(i) },
                label = { Text("E${i + 1}") },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(parsedColor, CircleShape)
                    )
                }
            )
        }
    }
}
```

- [ ] **Step 3: Insert ExtruderPickerRow into PrepareScreen**

In `PrepareScreen`, after the scale/copies controls section, show the picker only for single-color models:

```kotlin
val colorMapping by viewModel.colorMapping.collectAsState()
val selectedExtruder by viewModel.selectedExtruder.collectAsState()

if (colorMapping == null && state is SlicerState.ModelLoaded) {
    ExtruderPickerRow(
        selectedExtruder = selectedExtruder,
        extruderPresets = extruderPresets,
        onSelect = { viewModel.setSelectedExtruder(it) }
    )
}
```

- [ ] **Step 4: Wire the picker to the recolor pipeline**

`setSelectedExtruder()` updates `_activeExtruderColors`, which the `InlineModelPreview` LaunchedEffect already observes. The preview should update automatically. Verify this works.

- [ ] **Step 5: Build and verify on device**

Run: `.\gradlew installDebug --no-daemon`
Load a plain STL. Verify:
- 4 extruder chips visible below scale/copies
- Tapping E2 changes the model color to E2's preset color
- Loading a multi-color 3MF hides the picker
- Tapping E3 then slicing produces G-code with T2 (0-indexed)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/u1/slicer/MainActivity.kt
git commit -m "feat(F25): add single-color extruder picker to PrepareScreen"
```

---

### Task 12: Full test suite + E2E verification

- [ ] **Step 1: Run full unit test suite**

Run: `.\gradlew testDebugUnitTest --no-daemon`
Expected: All tests pass (including new MeshDataTest and ThreeMfMeshParserTest additions).

- [ ] **Step 2: Run instrumented tests**

Run:
```powershell
Remove-Item -Recurse -Force app\build\outputs\androidTest-results -ErrorAction SilentlyContinue
.\gradlew connectedDebugAndroidTest --no-daemon
```
Expected: All instrumented tests pass. The ThreeMfMeshParserTest (instrumented) may need updates for the new vertex format.

- [ ] **Step 3: E2E test — multi-color preview**

On device:
1. Load `calib-cube-10-dual-colour-merged.3mf` (2 colors)
2. Verify 3D preview shows 2 distinct colors
3. Open multi-color dialog, swap extruder assignments
4. Verify preview updates live
5. Slice and verify G-code has correct tool changes

- [ ] **Step 4: E2E test — single-color extruder picker**

On device:
1. Load any STL file
2. Verify extruder picker chips visible
3. Tap E2 → model turns E2's color
4. Slice → verify G-code starts with T1 (or appropriate tool)
5. Load a multi-color 3MF → verify picker disappears

- [ ] **Step 5: E2E test — SEMM paint model (if test asset available)**

If a SEMM paint model is available in test assets:
1. Load it
2. Verify per-triangle coloring visible in preview
3. Change extruder mapping → verify preview updates

- [ ] **Step 6: Fix any issues found during testing**

- [ ] **Step 7: Version bump and final commit**

Update version in `build.gradle` (versionCode + versionName). Commit all remaining changes.

```bash
git add -A
git commit -m "bump: vX.Y.Z — F3 per-vertex multi-color preview + F25 single-color extruder picker"
```

- [ ] **Step 8: Update CLAUDE.md and memory files**

Update test counts, add new conventions for per-vertex coloring, update backlog to mark F3 and F25 as done.
