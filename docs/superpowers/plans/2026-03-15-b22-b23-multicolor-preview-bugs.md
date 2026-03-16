# B22/B23: Multi-Color Preview Bugs — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix two multi-color 3D preview bugs — initial load race condition (calicube) and per-object extruder map ID mismatch after plate restructuring (dragon cube plate 3) — and add comprehensive test coverage.

**Architecture:** Bug 1 is a Compose LaunchedEffect race in `InlineModelPreview` where mesh and colors arrive asynchronously but recolor depends on both. Bug 2 is a data-flow bug where `restructurePlateFile()` generates new object IDs but `objectExtruderMap` still holds the original IDs. Tests span unit (JVM), instrumented (device), and E2E (visual).

**Tech Stack:** Kotlin, Jetpack Compose, JUnit 4, AndroidX Test, ADB/Maestro for E2E

---

## Chunk 1: Bug Fixes

### Task 1: Fix B22 — Initial load race condition in InlineModelPreview

**Bug:** On first load of a multi-color 3MF (e.g. calicube), the 3D preview shows default grey instead of extruder colors. Changing any color triggers the correct display.

**Root cause:** Three `LaunchedEffect`s in `InlineModelPreview` have a timing gap:
1. `LaunchedEffect(modelFilePath, extruderMap)` → parses mesh on IO thread (slow)
2. `LaunchedEffect(mesh, viewerView)` → calls `setMesh()` + initial recolor, but reads `extruderColors` from stale closure (was empty when effect launched)
3. `LaunchedEffect(viewerView, extruderColors, colorMapping)` → live recolor, but fires before `mesh` is set → `mesh?.hasPerVertexColor` is null → skip

**Fix:** Consolidate the mesh-set + recolor into a single `LaunchedEffect` that keys on all relevant variables, so it always fires with the latest values.

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/MainActivity.kt:1622-1674`

- [ ] **Step 1: Replace the two separate LaunchedEffects with one combined effect**

Replace lines 1622-1674 (the `LaunchedEffect(mesh, viewerView)` and `LaunchedEffect(viewerView, extruderColors, colorMapping)`) with a single combined effect that tracks whether `setMesh` has already been called (to avoid redundant VBO re-uploads when only colors change):

```kotlin
    // Track whether we've already uploaded this mesh to avoid redundant VBO re-uploads
    // when only colors/mapping change.
    var lastSetMesh by remember { mutableStateOf<com.u1.slicer.viewer.MeshData?>(null) }

    LaunchedEffect(mesh, viewerView, extruderColors, colorMapping) {
        val m = mesh; val v = viewerView
        if (m != null && v != null) {
            // Only call setMesh when the mesh instance actually changed
            if (m !== lastSetMesh) {
                v.setMesh(m)
                lastSetMesh = m
            }
            if (extruderColors.isNotEmpty()) {
                v.setExtruderColors(extruderColors)
            }
            // Apply recolor when we have both mesh and colors
            if (m.hasPerVertexColor && extruderColors.isNotEmpty()) {
                fun toRgba(hex: String): FloatArray {
                    if (hex.isBlank()) return floatArrayOf(0.7f, 0.7f, 0.7f, 1f)
                    return try {
                        val c = android.graphics.Color.parseColor(hex)
                        floatArrayOf(
                            android.graphics.Color.red(c) / 255f,
                            android.graphics.Color.green(c) / 255f,
                            android.graphics.Color.blue(c) / 255f, 1f
                        )
                    } catch (_: Exception) { floatArrayOf(0.91f, 0.48f, 0f, 1f) }
                }
                val palette = if (colorMapping != null) {
                    colorMapping.map { slot -> toRgba(extruderColors.getOrElse(slot) { "" }) }
                } else {
                    listOf(toRgba(extruderColors.firstOrNull { it.isNotBlank() } ?: ""))
                }
                v.recolorMesh(palette)
            }
        } else if (v != null && extruderColors.isNotEmpty()) {
            // Mesh not ready yet but colors changed — just update instance colors
            v.setExtruderColors(extruderColors)
        }
    }
```

Key differences: (1) `mesh` is now a key of the effect, so when it arrives (after IO parse), the effect re-runs with the CURRENT `extruderColors` — no stale closure. (2) `lastSetMesh` tracks whether `setMesh` was already called for this mesh instance, avoiding redundant VBO re-uploads when only colors change.

- [ ] **Step 2: Verify build compiles**

Run: `cd /c/Users/kevin/projects/u1-slicer-orca && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/u1/slicer/MainActivity.kt
git commit -m "fix(B22): consolidate InlineModelPreview LaunchedEffects to fix initial recolor race"
```

---

### Task 2: Fix B23 — objectExtruderMap ID mismatch after plate restructuring

**Bug:** Dragon cube plate 3 — changing E1 changes the whole model, E2/E3 have no effect. All triangles get extruder index 0.

**Root cause:** `restructurePlateFile()` creates new object IDs (sequential from 1) in the restructured 3MF, and writes a new `model_settings.config` with those new IDs. But `mergeThreeMfInfoForPlate()` copies `objectExtruderMap` from `sourceInfo` which has the ORIGINAL file's object IDs. When `ThreeMfMeshParser.parse()` reads the restructured file, mesh `objectId` values are the new IDs, but `buildExtruderMap()` returns the old IDs → lookup miss → fallback to 0 for all triangles.

**Fix:** `parseForPlateSelection()` already parses the restructured file's `model_settings.config` into `extruderAssignments`, but doesn't include it in the returned `ThreeMfInfo`. Add `objectExtruderMap` to its return, and in `mergeThreeMfInfoForPlate()`, prefer the plate's map (which has the new IDs) over the source's map (which has old IDs).

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/bambu/ThreeMfParser.kt:286-292`
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt:1712-1719`

- [ ] **Step 1: Include objectExtruderMap in parseForPlateSelection return**

In `ThreeMfParser.kt`, change the return at line 286 from:
```kotlin
                ThreeMfInfo(
                    objects = emptyList(),
                    plates = emptyList(),
                    isBambu = isBambu,
                    isMultiPlate = false,
                    usedExtruderIndices = uniqueExtruders
                )
```
to:
```kotlin
                ThreeMfInfo(
                    objects = emptyList(),
                    plates = emptyList(),
                    isBambu = isBambu,
                    isMultiPlate = false,
                    usedExtruderIndices = uniqueExtruders,
                    objectExtruderMap = extruderAssignments.toMap()
                )
```

- [ ] **Step 2: In mergeThreeMfInfoForPlate, prefer plate's objectExtruderMap when available**

In `SlicerViewModel.kt`, change line 1719 from:
```kotlin
                objectExtruderMap = sourceInfo.objectExtruderMap
```
to:
```kotlin
                objectExtruderMap = plateInfo.objectExtruderMap.ifEmpty { sourceInfo.objectExtruderMap }
```

This way: after `restructurePlateFile()` → `parseForPlateSelection()`, `plateInfo.objectExtruderMap` has the NEW IDs from the restructured `model_settings.config`. If it's non-empty (i.e. the restructured file has config), use it. Otherwise fall back to the original (for non-restructured plates).

- [ ] **Step 3: Verify build compiles**

Run: `cd /c/Users/kevin/projects/u1-slicer-orca && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/u1/slicer/bambu/ThreeMfParser.kt app/src/main/java/com/u1/slicer/SlicerViewModel.kt
git commit -m "fix(B23): use restructured objectExtruderMap after plate selection for correct per-object preview colors"
```

---

## Chunk 2: Unit Tests

### Task 3: Unit tests for B22 — MeshData.recolor with multi-extruder scenarios

These tests verify the recolor logic that B22 depends on — ensuring that when recolor IS called with correct data, it works for multi-object scenarios.

**Files:**
- Modify: `app/src/test/java/com/u1/slicer/viewer/MeshDataTest.kt`

- [ ] **Step 1: Add test — recolor with 3-extruder palette applies distinct colors per triangle**

```kotlin
@Test
fun `recolor with 3-extruder palette applies distinct colors per object`() {
    // 3 triangles, each assigned to a different extruder (like dragon cube plate 3)
    val indices = byteArrayOf(0, 1, 2)
    val mesh = makeMesh(triangleCount = 3, extruderIndices = indices)
    val palette = listOf(
        floatArrayOf(1f, 0f, 0f, 1f),  // E1 = red
        floatArrayOf(0f, 1f, 0f, 1f),  // E2 = green
        floatArrayOf(0f, 0f, 1f, 1f)   // E3 = blue
    )
    mesh.recolor(palette)
    val buf = mesh.vertices
    // Each triangle has 3 vertices. Color at offset 6 within each vertex.
    for (tri in 0..2) {
        for (v in 0..2) {
            val base = (tri * 3 + v) * MeshData.FLOATS_PER_VERTEX + 6
            assertEquals("tri$tri v$v R", palette[tri][0], buf.get(base), 0.001f)
            assertEquals("tri$tri v$v G", palette[tri][1], buf.get(base + 1), 0.001f)
            assertEquals("tri$tri v$v B", palette[tri][2], buf.get(base + 2), 0.001f)
        }
    }
}
```

- [ ] **Step 2: Add test — recolor called twice updates colors correctly**

This verifies the live-recolor path (changing extruder assignment re-applies colors).

```kotlin
@Test
fun `recolor called twice applies second palette correctly`() {
    val indices = byteArrayOf(0, 1)
    val mesh = makeMesh(triangleCount = 2, extruderIndices = indices)
    // First recolor: red, green
    mesh.recolor(listOf(floatArrayOf(1f, 0f, 0f, 1f), floatArrayOf(0f, 1f, 0f, 1f)))
    // Second recolor: blue, yellow
    val palette2 = listOf(floatArrayOf(0f, 0f, 1f, 1f), floatArrayOf(1f, 1f, 0f, 1f))
    mesh.recolor(palette2)
    val buf = mesh.vertices
    // Triangle 0, vertex 0 should be blue now
    val base0 = 0 * MeshData.FLOATS_PER_VERTEX + 6
    assertEquals(0f, buf.get(base0), 0.001f)
    assertEquals(0f, buf.get(base0 + 1), 0.001f)
    assertEquals(1f, buf.get(base0 + 2), 0.001f)
    // Triangle 1, vertex 0 should be yellow
    val base1 = 3 * MeshData.FLOATS_PER_VERTEX + 6
    assertEquals(1f, buf.get(base1), 0.001f)
    assertEquals(1f, buf.get(base1 + 1), 0.001f)
    assertEquals(0f, buf.get(base1 + 2), 0.001f)
}
```

- [ ] **Step 3: Run unit tests**

Run: `cd /c/Users/kevin/projects/u1-slicer-orca && ./gradlew testDebugUnitTest --tests "com.u1.slicer.viewer.MeshDataTest" 2>&1 | tail -10`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/com/u1/slicer/viewer/MeshDataTest.kt
git commit -m "test: add MeshData.recolor multi-extruder and re-recolor tests"
```

---

### Task 4: Unit tests for B23 — ThreeMfMeshParser with per-object extruder map

These tests verify that per-object extruder indices are correctly applied by the mesh parser, matching the dragon cube scenario.

**Files:**
- Modify: `app/src/test/java/com/u1/slicer/viewer/ThreeMfMeshParserTest.kt`

- [ ] **Step 1: Add test — multi-object 3MF with per-object extruder map assigns distinct indices**

Build a 3MF with 3 separate objects (IDs 1, 2, 3), each with 1 triangle, and pass `extruderMap = {1→0, 2→1, 3→2}`. Verify each triangle gets the correct extruder index.

```kotlin
@Test
fun `multi-object extruder map assigns distinct indices per object`() {
    // 3 objects, each with 1 triangle, different extruder assignments
    val xml = """<?xml version="1.0" encoding="UTF-8"?>
<model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
  <resources>
    <object id="1" type="model">
      <mesh>
        <vertices>
          <vertex x="0" y="0" z="0"/><vertex x="10" y="0" z="0"/><vertex x="5" y="10" z="0"/>
        </vertices>
        <triangles><triangle v1="0" v2="1" v3="2"/></triangles>
      </mesh>
    </object>
    <object id="2" type="model">
      <mesh>
        <vertices>
          <vertex x="20" y="0" z="0"/><vertex x="30" y="0" z="0"/><vertex x="25" y="10" z="0"/>
        </vertices>
        <triangles><triangle v1="0" v2="1" v3="2"/></triangles>
      </mesh>
    </object>
    <object id="3" type="model">
      <mesh>
        <vertices>
          <vertex x="40" y="0" z="0"/><vertex x="50" y="0" z="0"/><vertex x="45" y="10" z="0"/>
        </vertices>
        <triangles><triangle v1="0" v2="1" v3="2"/></triangles>
      </mesh>
    </object>
  </resources>
  <build>
    <item objectid="1"/><item objectid="2"/><item objectid="3"/>
  </build>
</model>"""
    val file = create3mfZip(xml)
    val extruderMap = mapOf(1 to 0.toByte(), 2 to 1.toByte(), 3 to 2.toByte())
    val mesh = ThreeMfMeshParser.parse(file, extruderMap = extruderMap)
    assertNotNull(mesh)
    assertNotNull(mesh!!.extruderIndices)
    assertEquals(3, mesh.extruderIndices!!.size)  // 3 triangles
    assertEquals(0.toByte(), mesh.extruderIndices!![0])  // object 1 → E1
    assertEquals(1.toByte(), mesh.extruderIndices!![1])  // object 2 → E2
    assertEquals(2.toByte(), mesh.extruderIndices!![2])  // object 3 → E3
}
```

- [ ] **Step 2: Add test — extruder map with mismatched IDs falls back to index 0**

This reproduces the exact B23 bug scenario: extruderMap has old IDs that don't match the mesh object IDs.

```kotlin
@Test
fun `extruder map with non-matching IDs falls back to index 0`() {
    // Object has ID 5, but extruderMap only has IDs 1,2,3 (old IDs from before restructuring)
    val xml = """<?xml version="1.0" encoding="UTF-8"?>
<model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
  <resources>
    <object id="5" type="model">
      <mesh>
        <vertices>
          <vertex x="0" y="0" z="0"/><vertex x="10" y="0" z="0"/><vertex x="5" y="10" z="0"/>
        </vertices>
        <triangles><triangle v1="0" v2="1" v3="2"/></triangles>
      </mesh>
    </object>
  </resources>
  <build><item objectid="5"/></build>
</model>"""
    val file = create3mfZip(xml)
    val staleMap = mapOf(1 to 0.toByte(), 2 to 1.toByte(), 3 to 2.toByte())
    val mesh = ThreeMfMeshParser.parse(file, extruderMap = staleMap)
    assertNotNull(mesh)
    assertNotNull(mesh!!.extruderIndices)
    // All triangles should fall back to 0 (the bug behavior)
    for (idx in mesh.extruderIndices!!) {
        assertEquals(0.toByte(), idx)
    }
}
```

- [ ] **Step 3: Run unit tests**

Run: `cd /c/Users/kevin/projects/u1-slicer-orca && ./gradlew testDebugUnitTest --tests "com.u1.slicer.viewer.ThreeMfMeshParserTest" 2>&1 | tail -10`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/com/u1/slicer/viewer/ThreeMfMeshParserTest.kt
git commit -m "test: add ThreeMfMeshParser multi-object extruder map and ID mismatch tests"
```

---

### Task 5: Unit tests for mergeThreeMfInfoForPlate objectExtruderMap preference

**Files:**
- Modify: `app/src/test/java/com/u1/slicer/viewer/ThreeMfMeshParserTest.kt` — NO, this belongs in a ViewModel test or wherever mergeThreeMfInfoForPlate is tested.

Check: `mergeThreeMfInfoForPlate` is a companion function on SlicerViewModel. We need to test that it now prefers the plate's map over the source's map.

- Modify: An existing test file that tests SlicerViewModel merge functions, OR add to `app/src/test/java/com/u1/slicer/data/DataClassesTest.kt` since these are pure functions.

Actually, looking at the existing test structure, `BambuPipelineIntegrationTest` already tests the merge functions via `viewModelMergeThreeMfInfo_preservesOrigInfoColors_calibCube`. But that's an instrumented test. For a pure function unit test:

**Files:**
- Create: `app/src/test/java/com/u1/slicer/MergeThreeMfInfoTest.kt`

- [ ] **Step 1: Add test — mergeThreeMfInfoForPlate prefers plate objectExtruderMap when non-empty**

```kotlin
package com.u1.slicer

import com.u1.slicer.bambu.ThreeMfInfo
import org.junit.Assert.*
import org.junit.Test

class MergeThreeMfInfoTest {

    @Test
    fun `mergeThreeMfInfoForPlate prefers plate objectExtruderMap when non-empty`() {
        val sourceInfo = ThreeMfInfo(
            objects = emptyList(), plates = emptyList(),
            isBambu = true, isMultiPlate = true,
            objectExtruderMap = mapOf("10" to 1, "11" to 2, "12" to 3),  // old IDs
            detectedColors = listOf("#FF0000", "#00FF00", "#0000FF"),
            detectedExtruderCount = 3
        )
        val plateInfo = ThreeMfInfo(
            objects = emptyList(), plates = emptyList(),
            isBambu = false, isMultiPlate = false,
            objectExtruderMap = mapOf("4" to 1, "5" to 2, "6" to 3),  // new restructured IDs
            usedExtruderIndices = setOf(1, 2, 3)
        )
        val merged = SlicerViewModel.mergeThreeMfInfoForPlate(plateInfo, sourceInfo)
        // Should use plate's map (new IDs), not source's (old IDs)
        assertEquals(mapOf("4" to 1, "5" to 2, "6" to 3), merged.objectExtruderMap)
    }

    @Test
    fun `mergeThreeMfInfoForPlate falls back to source objectExtruderMap when plate is empty`() {
        val sourceInfo = ThreeMfInfo(
            objects = emptyList(), plates = emptyList(),
            isBambu = true, isMultiPlate = true,
            objectExtruderMap = mapOf("10" to 1, "11" to 2),
            detectedColors = listOf("#FF0000", "#00FF00"),
            detectedExtruderCount = 2
        )
        val plateInfo = ThreeMfInfo(
            objects = emptyList(), plates = emptyList(),
            isBambu = false, isMultiPlate = false,
            objectExtruderMap = emptyMap(),  // no config in plate file
            usedExtruderIndices = setOf(1, 2)
        )
        val merged = SlicerViewModel.mergeThreeMfInfoForPlate(plateInfo, sourceInfo)
        // Should fall back to source's map
        assertEquals(mapOf("10" to 1, "11" to 2), merged.objectExtruderMap)
    }
}
```

- [ ] **Step 2: Run unit tests**

Run: `cd /c/Users/kevin/projects/u1-slicer-orca && ./gradlew testDebugUnitTest --tests "com.u1.slicer.MergeThreeMfInfoTest" 2>&1 | tail -10`
Expected: All tests PASS

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/u1/slicer/MergeThreeMfInfoTest.kt
git commit -m "test: add mergeThreeMfInfoForPlate objectExtruderMap preference tests"
```

---

## Chunk 3: Instrumented Tests

### Task 6: Instrumented test — calicube mesh parse produces correct extruder indices on first parse

This verifies the mesh parser correctly assigns per-triangle extruder indices from the calicube's SEMM paint data — the data that B22's recolor depends on.

**Files:**
- Modify: `app/src/androidTest/java/com/u1/slicer/viewer/ThreeMfMeshParserTest.kt`

- [ ] **Step 1: Add test for calicube mesh with extruder indices**

```kotlin
@Test
fun calibCube_parsesMeshWithDistinctExtruderIndices() {
    val file = asset("calib-cube-10-dual-colour-merged.3mf")
    // Parse with Bambu pipeline: sanitize first, then parse mesh with extruder map
    val origInfo = com.u1.slicer.bambu.ThreeMfParser.parse(file)
    val extruderMap = origInfo.objectExtruderMap.mapNotNull { (idStr, ext1) ->
        val id = idStr.toIntOrNull() ?: return@mapNotNull null
        id to (ext1 - 1).coerceAtLeast(0).toByte()
    }.toMap()

    val mesh = ThreeMfMeshParser.parse(file, extruderMap = extruderMap)
    assertNotNull("Mesh should parse", mesh)
    assertNotNull("Should have extruder indices", mesh!!.extruderIndices)
    assertTrue("Should have triangles", mesh.extruderIndices!!.isNotEmpty())

    // Calicube is dual-color: should have at least 2 distinct extruder indices
    val distinctIndices = mesh.extruderIndices!!.toSet()
    assertTrue(
        "Calicube should have >=2 distinct extruder indices, got $distinctIndices",
        distinctIndices.size >= 2
    )
}
```

- [ ] **Step 2: Run instrumented test**

Run: `cd /c/Users/kevin/projects/u1-slicer-orca && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.viewer.ThreeMfMeshParserTest -s 43211JEKB16931 2>&1 | tail -15`
Expected: All tests PASS

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/java/com/u1/slicer/viewer/ThreeMfMeshParserTest.kt
git commit -m "test(instrumented): calicube mesh parse produces distinct extruder indices"
```

---

### Task 7: Instrumented test — dragon cube plate 3 per-object extruder indices after restructuring

This is the end-to-end test for B23: sanitize → extract plate → restructure → parse mesh → verify per-object extruder indices are distinct.

**Files:**
- Modify: `app/src/androidTest/java/com/u1/slicer/slicing/BambuPipelineIntegrationTest.kt`

- [ ] **Step 1: Add test for dragon cube plate 3 extruder indices after full pipeline**

Note: We need to identify which test asset has a multi-object plate. The `calib-cube-10-dual-colour-merged.3mf` is dual-color with SEMM paint data. For per-OBJECT extruder assignments (like dragon cube), we need an asset that has multiple objects with different extruder assignments in `model_settings.config`. Check which existing assets have this — `Dragon Scale infinity-1-plate-2-colours.3mf` likely has per-object assignments.

```kotlin
@Test
fun dragonScale_1plate_meshHasDistinctExtruderIndicesAfterRestructure() {
    val file = asset("Dragon Scale infinity-1-plate-2-colours.3mf")
    val context = InstrumentationRegistry.getInstrumentation().targetContext

    // Full pipeline matching SlicerViewModel.selectPlate():
    // parse original → sanitize → extractPlate → restructurePlateFile → parse mesh
    val origInfo = com.u1.slicer.bambu.ThreeMfParser.parse(file)
    val processed = com.u1.slicer.bambu.BambuSanitizer.process(file, context.filesDir, isBambu = origInfo.isBambu)

    // Dragon Scale 1-plate is a single-plate file, so extractPlate with plate 1
    // For single-plate files extractPlate may return the file as-is
    val plateFile = com.u1.slicer.bambu.BambuSanitizer.extractPlate(
        processed, 1, context.filesDir,
        hasPlateJsons = origInfo.hasPlateJsons,
        plateObjectIds = origInfo.plates.find { it.plateId == 1 }?.objectIds?.toSet()
    )
    val restructured = com.u1.slicer.bambu.BambuSanitizer.restructurePlateFile(plateFile, context.filesDir)

    // Parse the restructured file's config for new object IDs (the B23 fix)
    val plateInfo = com.u1.slicer.bambu.ThreeMfParser.parseForPlateSelection(restructured)

    // Use the plate's objectExtruderMap (new IDs from restructured file)
    val extruderMap = plateInfo.objectExtruderMap.ifEmpty { origInfo.objectExtruderMap }
        .mapNotNull { (idStr, ext1) ->
            val id = idStr.toIntOrNull() ?: return@mapNotNull null
            id to (ext1 - 1).coerceAtLeast(0).toByte()
        }.toMap()

    Log.i("B23Test", "origInfo.objectExtruderMap: ${origInfo.objectExtruderMap}")
    Log.i("B23Test", "plateInfo.objectExtruderMap: ${plateInfo.objectExtruderMap}")
    Log.i("B23Test", "Final extruderMap: $extruderMap")

    val mesh = com.u1.slicer.viewer.ThreeMfMeshParser.parse(restructured, extruderMap = extruderMap)
    assertNotNull("Mesh should parse from restructured file", mesh)

    if (mesh!!.extruderIndices != null && extruderMap.isNotEmpty()) {
        val distinctIndices = mesh.extruderIndices!!.toSet()
        Log.i("B23Test", "Restructured mesh extruder indices: $distinctIndices")
        // Multi-color plate should have >=2 distinct extruder indices
        assertTrue(
            "Multi-color plate should have >=2 distinct extruder indices after restructure, got $distinctIndices",
            distinctIndices.size >= 2
        )
    }
}
```

- [ ] **Step 2: Run instrumented test**

Run: `cd /c/Users/kevin/projects/u1-slicer-orca && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.slicing.BambuPipelineIntegrationTest#dragonScale_1plate_meshHasDistinctExtruderIndicesAfterRestructure -s 43211JEKB16931 2>&1 | tail -15`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/java/com/u1/slicer/slicing/BambuPipelineIntegrationTest.kt
git commit -m "test(instrumented): dragon scale per-object extruder indices survive restructure pipeline"
```

---

### Task 8: Instrumented test — parseForPlateSelection returns objectExtruderMap

Verifies the B23 fix: `parseForPlateSelection` now includes `objectExtruderMap` in its result.

**Files:**
- Modify: `app/src/androidTest/java/com/u1/slicer/slicing/BambuPipelineIntegrationTest.kt`

- [ ] **Step 1: Add test**

```kotlin
@Test
fun parseForPlateSelection_includesObjectExtruderMap() {
    val file = asset("Dragon Scale infinity-1-plate-2-colours.3mf")
    val context = InstrumentationRegistry.getInstrumentation().targetContext

    // Full pipeline: parse → sanitize → extract → restructure
    val origInfo = com.u1.slicer.bambu.ThreeMfParser.parse(file)
    val processed = com.u1.slicer.bambu.BambuSanitizer.process(file, context.filesDir, isBambu = origInfo.isBambu)
    val plateFile = com.u1.slicer.bambu.BambuSanitizer.extractPlate(
        processed, 1, context.filesDir,
        hasPlateJsons = origInfo.hasPlateJsons,
        plateObjectIds = origInfo.plates.find { it.plateId == 1 }?.objectIds?.toSet()
    )
    val restructured = com.u1.slicer.bambu.BambuSanitizer.restructurePlateFile(plateFile, context.filesDir)

    val plateInfo = com.u1.slicer.bambu.ThreeMfParser.parseForPlateSelection(restructured)
    Log.i("B23Test", "parseForPlateSelection objectExtruderMap: ${plateInfo.objectExtruderMap}")

    // After restructuring, should have objectExtruderMap with the new IDs
    assertTrue(
        "parseForPlateSelection should return non-empty objectExtruderMap from restructured file",
        plateInfo.objectExtruderMap.isNotEmpty()
    )
}
```

- [ ] **Step 2: Run and commit**

Run: `cd /c/Users/kevin/projects/u1-slicer-orca && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.slicing.BambuPipelineIntegrationTest#parseForPlateSelection_includesObjectExtruderMap -s 43211JEKB16931 2>&1 | tail -15`

```bash
git add app/src/androidTest/java/com/u1/slicer/slicing/BambuPipelineIntegrationTest.kt
git commit -m "test(instrumented): parseForPlateSelection returns objectExtruderMap after restructure"
```

---

## Chunk 4: Full Test Suite + Version Bump

### Task 9: Run full test suite

- [ ] **Step 1: Run all unit tests**

Run: `cd /c/Users/kevin/projects/u1-slicer-orca && ./gradlew testDebugUnitTest 2>&1 | tail -20`
Expected: All tests PASS (should now be 370+ tests)

- [ ] **Step 2: Run all instrumented tests**

Run: `cd /c/Users/kevin/projects/u1-slicer-orca && ./gradlew connectedDebugAndroidTest -s 43211JEKB16931 2>&1 | tail -20`
Expected: All tests PASS (should now be 106+ tests)

- [ ] **Step 3: Fix any failures**

If any test fails, investigate and fix before proceeding.

---

### Task 10: Version bump and final commit

- [ ] **Step 1: Bump version in build.gradle**

Increment `versionCode` by 1 and update `versionName` to `"1.3.37"` in `app/build.gradle`.

- [ ] **Step 2: Update CLAUDE.md test counts**

Update the unit test count and instrumented test count in `CLAUDE.md` to reflect the new tests added.

- [ ] **Step 3: Update memory files**

Update `MEMORY.md` version to v1.3.37, add B22/B23 to version history, update test counts.
Update `backlog.md` with B22/B23 entries marked as FIXED.

- [ ] **Step 4: Commit and push**

```bash
git add -A
git commit -m "bump: v1.3.37 — fix B22 initial recolor race + B23 per-object extruder map after plate restructure"
git push
```

---

## Chunk 5: E2E Visual Tests

### Task 11: E2E batch test — calicube and dragon scale multi-color preview

After the code fixes are pushed and installed on device, run E2E visual tests:

- [ ] **Step 1: Install on device**

Run: `cd /c/Users/kevin/projects/u1-slicer-orca && ./gradlew installDebug -s 43211JEKB16931`

- [ ] **Step 2: E2E test — calicube initial load shows colors**

Invoke: "E2E test: calicube-initial-color" — load `calib-cube-10-dual-colour-merged.3mf`, verify the 3D preview shows two distinct colors immediately on first load (no interaction needed).

- [ ] **Step 3: E2E test — dragon scale plate per-object colors**

Invoke: "E2E test: dragon-scale-per-object-color" — load `Dragon Scale infinity-1-plate-2-colours.3mf`, verify the 3D preview shows objects in their assigned extruder colors. Change E2 color and verify only the E2-assigned objects change color.
