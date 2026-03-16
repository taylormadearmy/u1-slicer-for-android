# ARCHIVED — Implemented across v1.3.33–v1.3.38

# Preview & Settings UX Batch Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix B19 (thumbnails), add F12 (per-extruder filament), F15 (bed temp display), F16 (upload-only), F20 (travel toggle), B20 (scale preview), F17 (prime tower options), F18 (grouped settings), F19 (3D tubes in gcode viewer).

**Architecture:** Nine independent changes, ordered by dependency. B19 is a one-line fix. F12/F15 extend SliceCompleteCard with data already available. F16 splits upload/print. F20 adds a toggle to GcodeRenderer (field already exists: `showTravel`). B20 applies scale transform in ModelRenderer. F17 adds override fields. F18 restructures SettingsScreen into accordion groups. F19 replaces GL_LINES with instanced quad-strip tubes.

**Tech Stack:** Kotlin, Jetpack Compose, OpenGL ES 3.0, OkHttp (Moonraker API)

---

## Chunk 1: Quick Fixes (B19, F12, F15, F16, F20, B20)

### Task 1: B19 — Fix thumbnail source to use raw input file

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt:1090-1099`

The bug: `GcodeThumbnailInjector.inject()` uses `sourceModelFile` (post-sanitized, images stripped) instead of `rawInputFile` (original with preview images).

- [ ] **Step 1: Fix the thumbnail source path**

In `SlicerViewModel.kt` around line 1090, change:
```kotlin
val sourcePath = sourceModelFile?.absolutePath ?: currentModelFile?.absolutePath
```
to:
```kotlin
val sourcePath = rawInputFile?.absolutePath ?: sourceModelFile?.absolutePath ?: currentModelFile?.absolutePath
```

- [ ] **Step 2: Build and verify**

Run: `.\gradlew assembleDebug --no-daemon` (PowerShell)
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/u1/slicer/SlicerViewModel.kt
git commit -m "fix: B19 use rawInputFile for thumbnail injection (pre-sanitized has images)"
```

---

### Task 2: F12 — Show per-extruder filament usage on Preview page

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/gcode/GcodeParser.kt` — parse per-extruder filament comments
- Modify: `app/src/main/java/com/u1/slicer/gcode/GcodeLayer.kt` — add `perExtruderFilamentMm` to ParsedGcode
- Modify: `app/src/main/java/com/u1/slicer/MainActivity.kt` — display in SliceCompleteCard
- Test: `app/src/test/java/com/u1/slicer/gcode/GcodeParserTest.kt` — new test for per-extruder parsing

OrcaSlicer emits comments like `;  filament used [mm] = 1234.56,789.01` in the G-code footer. Parse these.

- [ ] **Step 1: Add perExtruderFilamentMm to ParsedGcode**

In `GcodeLayer.kt`, add a field to `ParsedGcode`:
```kotlin
data class ParsedGcode(
    val layers: List<GcodeLayer>,
    val bedWidth: Float = 270f,
    val bedHeight: Float = 270f,
    val perExtruderFilamentMm: List<Float> = emptyList()
)
```

- [ ] **Step 2: Write failing test**

In `GcodeParserTest.kt`, add:
```kotlin
@Test
fun `parses per-extruder filament usage from comments`() {
    val gcode = """
        ;LAYER_CHANGE
        G1 X10 Y10 E1
        ; filament used [mm] = 1234.56,789.01
    """.trimIndent()
    val file = File.createTempFile("test", ".gcode").apply { writeText(gcode); deleteOnExit() }
    val result = GcodeParser.parse(file)
    assertEquals(2, result.perExtruderFilamentMm.size)
    assertEquals(1234.56f, result.perExtruderFilamentMm[0], 0.1f)
    assertEquals(789.01f, result.perExtruderFilamentMm[1], 0.1f)
}

@Test
fun `per-extruder filament empty for single extruder`() {
    val gcode = """
        ;LAYER_CHANGE
        G1 X10 Y10 E1
        ; filament used [mm] = 5678.90
    """.trimIndent()
    val file = File.createTempFile("test", ".gcode").apply { writeText(gcode); deleteOnExit() }
    val result = GcodeParser.parse(file)
    assertEquals(1, result.perExtruderFilamentMm.size)
    assertEquals(5678.9f, result.perExtruderFilamentMm[0], 0.1f)
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `.\gradlew testDebugUnitTest --tests "com.u1.slicer.gcode.GcodeParserTest" --no-daemon`
Expected: FAIL (perExtruderFilamentMm is empty)

- [ ] **Step 4: Implement parsing in GcodeParser**

In `GcodeParser.kt`, inside the comment parsing block (after `if (l[start] == ';')`), add before the `continue`:
```kotlin
// Parse per-extruder filament usage: "; filament used [mm] = 123.45,678.90"
if (perExtruderMm.isEmpty() && startsWithAt(l, start, "; filament used [mm]")) {
    val eqIdx = l.indexOf('=', start)
    if (eqIdx >= 0) {
        val valStr = l.substring(eqIdx + 1).trim()
        perExtruderMm = valStr.split(',').mapNotNull { it.trim().toFloatOrNull() }
    }
}
```

Add `var perExtruderMm = emptyList<Float>()` with the other state variables at the top.

Change the return to:
```kotlin
return ParsedGcode(layers = layers, perExtruderFilamentMm = perExtruderMm)
```

- [ ] **Step 5: Run test to verify it passes**

Run: `.\gradlew testDebugUnitTest --tests "com.u1.slicer.gcode.GcodeParserTest" --no-daemon`
Expected: PASS

- [ ] **Step 6: Display per-extruder filament in SliceCompleteCard**

In `MainActivity.kt` `SliceCompleteCard`, after the existing `InfoRow("Filament", ...)` line (~1412), add a parameter `perExtruderFilamentMm: List<Float> = emptyList()` to the composable signature and display:
```kotlin
if (perExtruderFilamentMm.size > 1) {
    perExtruderFilamentMm.forEachIndexed { i, mm ->
        InfoRow("  E${i + 1}", "%.0f mm (%.1f g)".format(mm, mm * 0.00125f * 1.24f))
    }
}
```
Note: 0.00125 * 1.24 ≈ PLA density approximation (1.75mm filament, ~1.24 g/cm³).

Pass `perExtruderFilamentMm` from the call site in PreviewScreen by reading it from parsedGcode.

- [ ] **Step 7: Build and verify**

Run: `.\gradlew assembleDebug --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/u1/slicer/gcode/GcodeParser.kt \
       app/src/main/java/com/u1/slicer/gcode/GcodeLayer.kt \
       app/src/main/java/com/u1/slicer/MainActivity.kt \
       app/src/test/java/com/u1/slicer/gcode/GcodeParserTest.kt
git commit -m "feat: F12 show per-extruder filament usage on Preview page"
```

---

### Task 3: F15 — Show bed temperature on Preview page

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/MainActivity.kt` — add bed temp to SliceCompleteCard

Bed temp is already in `SliceConfig.bedTemp` which is available from SlicerViewModel's config state.

- [ ] **Step 1: Add bedTemp param to SliceCompleteCard**

Add `bedTemp: Int = 0` parameter. After `InfoRow("Filament", ...)`:
```kotlin
if (bedTemp > 0) {
    InfoRow("Bed Temp", "${bedTemp}°C")
}
```

- [ ] **Step 2: Pass bedTemp from PreviewScreen call site**

In PreviewScreen (around line 862-867), the `SliceCompleteCard` call needs bedTemp. Read it from viewModel's config:
```kotlin
val config by viewModel.config.collectAsState()
```
Then pass `bedTemp = config.bedTemp`.

- [ ] **Step 3: Build and verify**

Run: `.\gradlew assembleDebug --no-daemon`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/u1/slicer/MainActivity.kt
git commit -m "feat: F15 show bed temperature on slice complete card"
```

---

### Task 4: F16 — Upload to printer without starting print

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/printer/PrinterRepository.kt` — add `uploadOnly()`
- Modify: `app/src/main/java/com/u1/slicer/printer/PrinterViewModel.kt` — add `sendUploadOnly()`
- Modify: `app/src/main/java/com/u1/slicer/MainActivity.kt` — split button into Print + Upload

- [ ] **Step 1: Add uploadOnly to PrinterRepository**

In `PrinterRepository.kt`, after `uploadAndPrint()`:
```kotlin
suspend fun uploadOnly(gcodeFile: java.io.File, filename: String): Boolean {
    return client.uploadGcode(gcodeFile, filename)
}
```

- [ ] **Step 2: Add sendUploadOnly to PrinterViewModel**

In `PrinterViewModel.kt`, after `sendAndPrint()`:
```kotlin
fun sendUploadOnly(gcodePath: String) {
    _sendingState.value = SendingState.Uploading
    viewModelScope.launch(Dispatchers.IO) {
        val file = File(gcodePath)
        if (!file.exists()) {
            _sendingState.value = SendingState.Error("G-code file not found")
            return@launch
        }
        val ok = printerRepo.uploadOnly(file, file.name)
        _sendingState.value = if (ok) SendingState.Success else SendingState.Error("Upload failed")
    }
}
```

- [ ] **Step 3: Update SliceCompleteCard UI — two buttons**

In `SliceCompleteCard`, replace the single "Send to Printer" button with two buttons:
```kotlin
Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp)
) {
    Button(
        onClick = onSendToPrinter,
        modifier = Modifier.weight(1f),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
    ) {
        Icon(Icons.Default.Print, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text("Print", fontWeight = FontWeight.Bold)
    }
    OutlinedButton(
        onClick = onUploadOnly,
        modifier = Modifier.weight(1f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(Icons.Default.CloudUpload, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text("Upload")
    }
}
```

Add `onUploadOnly: () -> Unit = {}` parameter to `SliceCompleteCard`.

- [ ] **Step 4: Wire up onUploadOnly in PreviewScreen**

In PreviewScreen, pass the callback through:
```kotlin
onUploadOnly = { printerViewModel.sendUploadOnly(s.result.gcodePath) }
```

Add `onUploadOnly` parameter to `PreviewScreen` composable and thread through from NavHost.

- [ ] **Step 5: Build and verify**

Run: `.\gradlew assembleDebug --no-daemon`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/u1/slicer/printer/PrinterRepository.kt \
       app/src/main/java/com/u1/slicer/printer/PrinterViewModel.kt \
       app/src/main/java/com/u1/slicer/MainActivity.kt
git commit -m "feat: F16 upload to printer without starting print"
```

---

### Task 5: F20 — Toggle travel moves in G-code viewer

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/viewer/GcodeRenderer.kt` — use `showTravel` field to filter
- Modify: `app/src/main/java/com/u1/slicer/viewer/GcodeViewerView.kt` — add `setShowTravel()`
- Modify: `app/src/main/java/com/u1/slicer/ui/GcodeViewer3DScreen.kt` — add toggle button
- Modify: `app/src/main/java/com/u1/slicer/MainActivity.kt` — add toggle to InlineGcodePreview

`GcodeRenderer` already has `var showTravel = false` (line 35). The problem: travel moves are baked into the VBO at upload time and can't be toggled without re-uploading.

**Approach:** Store travel and extrusion moves in separate VBO ranges per layer. Draw travel range only when `showTravel` is true.

- [ ] **Step 1: Split VBO into extrusion and travel ranges per layer**

In `GcodeRenderer.kt`, change LayerRange to track both:
```kotlin
private data class LayerRange(
    val extrudeFirst: Int, val extrudeCount: Int,
    val travelFirst: Int, val travelCount: Int
)
```

In `uploadGcode()`, do two passes per layer — extrusions first, then travels:
```kotlin
for (layer in gcode.layers) {
    val extStart = offset / floatsPerVertex
    // First pass: extrusion moves
    for (move in layer.moves) {
        if (move.type != MoveType.EXTRUDE) continue
        if (sampleRate > 1 && moveIdx++ % sampleRate != 0) continue
        // ... write vertices with extruder color
    }
    val extCount = offset / floatsPerVertex - extStart

    val travStart = offset / floatsPerVertex
    // Second pass: travel moves (skip when downsampling)
    if (sampleRate <= 1) {
        for (move in layer.moves) {
            if (move.type != MoveType.TRAVEL) continue
            // ... write vertices with travelColor
        }
    }
    val travCount = offset / floatsPerVertex - travStart

    layerRanges.add(LayerRange(extStart, extCount, travStart, travCount))
}
```

- [ ] **Step 2: Update drawToolpaths() to conditionally draw travels**

```kotlin
private fun drawToolpaths() {
    val shader = toolpathShader ?: return
    if (layerRanges.isEmpty()) return

    shader.use()
    camera.computeMVP()
    GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_MVPMatrix"), 1, false, camera.mvpMatrix, 0)

    val min = minLayer.coerceIn(0, layerRanges.size - 1)
    val max = maxLayer.coerceIn(0, layerRanges.size - 1)

    GLES30.glBindVertexArray(masterVAO)

    // Draw extrusion moves
    for (i in min..max) {
        val r = layerRanges[i]
        if (r.extrudeCount > 0) GLES30.glDrawArrays(GLES30.GL_LINES, r.extrudeFirst, r.extrudeCount)
    }

    // Draw travel moves (only when enabled)
    if (showTravel) {
        for (i in min..max) {
            val r = layerRanges[i]
            if (r.travelCount > 0) GLES30.glDrawArrays(GLES30.GL_LINES, r.travelFirst, r.travelCount)
        }
    }

    GLES30.glBindVertexArray(0)
}
```

- [ ] **Step 3: Add setShowTravel to GcodeViewerView**

In `GcodeViewerView.kt`:
```kotlin
fun setShowTravel(show: Boolean) {
    renderer.showTravel = show
    requestRender()
}
```

- [ ] **Step 4: Add toggle to GcodeViewer3DScreen**

In `GcodeViewer3DScreen.kt`, add state and toggle button in the top bar actions:
```kotlin
var showTravel by remember { mutableStateOf(false) }

// In actions block of TopAppBar:
IconButton(onClick = {
    showTravel = !showTravel
    viewerView?.setShowTravel(showTravel)
}) {
    Icon(
        if (showTravel) Icons.Default.Visibility else Icons.Default.VisibilityOff,
        "Toggle travel moves",
        tint = if (showTravel) MaterialTheme.colorScheme.primary
               else MaterialTheme.colorScheme.onSurfaceVariant
    )
}
```

- [ ] **Step 5: Build and verify**

Run: `.\gradlew assembleDebug --no-daemon`

- [ ] **Step 6: Run existing unit tests**

Run: `.\gradlew testDebugUnitTest --no-daemon`
Expected: All pass (renderer changes are GL-only, no unit test impact)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/u1/slicer/viewer/GcodeRenderer.kt \
       app/src/main/java/com/u1/slicer/viewer/GcodeViewerView.kt \
       app/src/main/java/com/u1/slicer/ui/GcodeViewer3DScreen.kt
git commit -m "feat: F20 toggle travel moves visibility in G-code viewer"
```

---

### Task 6: B20 — Fix scale not updating model preview

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/viewer/ModelRenderer.kt` — apply scale matrix
- Modify: `app/src/main/java/com/u1/slicer/viewer/ModelViewerView.kt` — add setScale()
- Modify: `app/src/main/java/com/u1/slicer/MainActivity.kt` — pass scale to viewer

The model renderer uses raw mesh vertices and doesn't apply the `modelScale` from SlicerViewModel. Scale only goes to the native layer for slicing.

- [ ] **Step 1: Add scale field to ModelRenderer**

In `ModelRenderer.kt`, add:
```kotlin
@Volatile var modelScale = floatArrayOf(1f, 1f, 1f)
```

- [ ] **Step 2: Apply scale in drawModel and drawModelAt**

In `drawModel()`, before `camera.computeMVP()`:
```kotlin
val modelMatrix = FloatArray(16)
Matrix.setIdentityM(modelMatrix, 0)
val mesh = meshData ?: return
// Scale about the mesh center
val cx = (mesh.minX + mesh.maxX) / 2f
val cy = (mesh.minY + mesh.maxY) / 2f
val cz = (mesh.minZ + mesh.maxZ) / 2f
Matrix.translateM(modelMatrix, 0, cx, cy, cz)
Matrix.scaleM(modelMatrix, 0, modelScale[0], modelScale[1], modelScale[2])
Matrix.translateM(modelMatrix, 0, -cx, -cy, -cz)
camera.computeMVP(modelMatrix)
```

In `drawModelAt()`, apply scale before the translate:
```kotlin
Matrix.translateM(modelMatrix, 0, x - mesh.minX, y - mesh.minY, -mesh.minZ)
val cx = (mesh.maxX - mesh.minX) / 2f
val cy = (mesh.maxY - mesh.minY) / 2f
val cz = (mesh.maxZ - mesh.minZ) / 2f
Matrix.translateM(modelMatrix, 0, cx, cy, cz)
Matrix.scaleM(modelMatrix, 0, modelScale[0], modelScale[1], modelScale[2])
Matrix.translateM(modelMatrix, 0, -cx, -cy, -cz)
```

- [ ] **Step 3: Add setScale to ModelViewerView** (if exists, or update how scale flows)

Find the ModelViewerView (or where the renderer is accessed from compose) and add:
```kotlin
fun setScale(sx: Float, sy: Float, sz: Float) {
    renderer.modelScale = floatArrayOf(sx, sy, sz)
    requestRender()
}
```

- [ ] **Step 4: Pass scale changes to viewer in PrepareScreen**

In `MainActivity.kt`, where the model viewer is used in PrepareScreen, when `modelScale` changes, call `viewerView.setScale(scale.x, scale.y, scale.z)`.

- [ ] **Step 5: Build and verify**

Run: `.\gradlew assembleDebug --no-daemon`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/u1/slicer/viewer/ModelRenderer.kt \
       app/src/main/java/com/u1/slicer/MainActivity.kt
git commit -m "fix: B20 apply scale transform to model 3D preview"
```

---

## Chunk 2: Settings Enhancements (F17, F18)

### Task 7: F17 — Expose more prime tower options

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/data/SlicingOverrides.kt` — already has primeVolume, primeTowerBrimWidth, etc.
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` — emit new keys in buildProfileOverrides
- Modify: `app/src/main/cpp/src/sapil_print.cpp` — add keys to profile_keys[] if missing

Inspection shows `SlicingOverrides` already has `primeVolume`, `primeTowerBrimWidth`, `primeTowerBrimChamfer`, `primeTowerChamferMaxWidth` and `SettingsScreen` already displays them (lines 595-652). The missing piece is likely that `buildProfileOverrides()` doesn't emit these keys, and they may not be in `profile_keys[]`.

- [ ] **Step 1: Check which keys buildProfileOverrides emits**

Search `SlicerViewModel.kt` for `prime_volume`, `prime_tower_brim_width` in `buildProfileOverridesImpl()`. Also check `sapil_print.cpp` `profile_keys[]` for these keys.

- [ ] **Step 2: Add missing prime tower keys to buildProfileOverridesImpl()**

In `SlicerViewModel.kt`'s `buildProfileOverridesImpl()`, after the prime tower enable:
```kotlin
if (ov.primeVolume.mode == OverrideMode.OVERRIDE) {
    overrides["prime_volume"] = mutableListOf(resolve(ov.primeVolume, 45, "primeVolume").toString())
}
if (ov.primeTowerBrimWidth.mode == OverrideMode.OVERRIDE) {
    overrides["prime_tower_brim_width"] = mutableListOf(resolve(ov.primeTowerBrimWidth, 3f, "primeTowerBrimWidth").toString())
}
```

- [ ] **Step 3: Add keys to profile_keys[] in sapil_print.cpp if missing**

Check and add: `"prime_volume"`, `"prime_tower_brim_width"` to the `profile_keys[]` array. These need a native rebuild to take effect, but adding them now means they'll work after the next rebuild.

- [ ] **Step 4: Add unit test for new overrides**

In `SlicingOverridesTest.kt`:
```kotlin
@Test
fun `prime tower detail overrides emit correct keys`() {
    val ov = SlicingOverrides(
        primeVolume = OverrideValue(OverrideMode.OVERRIDE, 60),
        primeTowerBrimWidth = OverrideValue(OverrideMode.OVERRIDE, 5f)
    )
    // Test the overrides round-trip through JSON
    val json = ov.toJson()
    val restored = SlicingOverrides.fromJson(json)
    assertEquals(60, restored.primeVolume.value)
    assertEquals(5f, restored.primeTowerBrimWidth.value)
}
```

- [ ] **Step 5: Run tests**

Run: `.\gradlew testDebugUnitTest --no-daemon`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/u1/slicer/SlicerViewModel.kt \
       app/src/main/java/com/u1/slicer/data/SlicingOverrides.kt \
       app/src/main/cpp/src/sapil_print.cpp \
       app/src/test/java/com/u1/slicer/data/SlicingOverridesTest.kt
git commit -m "feat: F17 wire prime tower detail overrides to profile embed pipeline"
```

---

### Task 8: F18 — Group settings into collapsible accordion sections

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/ui/SettingsScreen.kt` — restructure overrides into groups

The current SettingsScreen has all overrides in a flat list under "Slicing Overrides". Group them into collapsible sections.

- [ ] **Step 1: Create ExpandableOverrideSection composable**

In `SettingsScreen.kt`, add:
```kotlin
@Composable
fun ExpandableOverrideSection(
    title: String,
    icon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    "Toggle"
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = content
                )
            }
        }
    }
}
```

- [ ] **Step 2: Group existing overrides into sections**

Replace the flat override list with grouped sections. Track expanded state per section:
```kotlin
var expandedSection by remember { mutableStateOf<String?>(null) }
```

Sections:
1. **Layer & Infill** (Icons.Default.Layers): layerHeight, infillDensity, wallCount, infillPattern
2. **Support** (Icons.Default.Support): supports, supportType, supportAngle, supportBuildPlateOnly, supportPattern, supportPatternSpacing, supportInterfaceTopLayers, supportInterfaceBottomLayers, supportFilament, supportInterfaceFilament
3. **Prime Tower** (Icons.Default.ViewInAr): primeTower, primeVolume, primeTowerBrimWidth, primeTowerBrimChamfer, primeTowerChamferMaxWidth
4. **Temperature** (Icons.Default.Thermostat): bedTemp
5. **Other** (Icons.Default.Tune): brimWidth, skirtLoops, flowCalibration

Move the existing `OverrideRow` calls into their respective sections.

- [ ] **Step 3: Build and verify**

Run: `.\gradlew assembleDebug --no-daemon`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/u1/slicer/ui/SettingsScreen.kt
git commit -m "feat: F18 group slicing overrides into collapsible accordion sections"
```

---

## Chunk 3: G-code 3D Tubes (F19)

### Task 9: F19 — Render G-code extrusion paths as 3D tubes

**Files:**
- Create: `app/src/main/assets/shaders/tube.vert` — tube vertex shader
- Create: `app/src/main/assets/shaders/tube.frag` — tube fragment shader
- Modify: `app/src/main/java/com/u1/slicer/viewer/GcodeRenderer.kt` — tube geometry generation + rendering

**Approach:** For each extrusion line segment, generate a flat quad (2 triangles) with a width matching the extrusion width. This gives a "ribbon" effect that looks much more solid than GL_LINES, without the full cost of actual cylinder geometry. Use the normals for basic lighting.

A line from (x0,y0) to (x1,y1) at height z gets expanded into a quad perpendicular to the direction vector with a half-width of ~0.2mm (half nozzle width).

- [ ] **Step 1: Create tube shaders**

`tube.vert` — same as model.vert (MVP + normal matrix + position/normal attributes):
```glsl
#version 300 es
in vec3 a_Position;
in vec3 a_Normal;
in vec4 a_Color;
out vec4 v_Color;
out vec3 v_Normal;
uniform mat4 u_MVPMatrix;
uniform mat4 u_NormalMatrix;

void main() {
    gl_Position = u_MVPMatrix * vec4(a_Position, 1.0);
    v_Normal = normalize((u_NormalMatrix * vec4(a_Normal, 0.0)).xyz);
    v_Color = a_Color;
}
```

`tube.frag` — simple directional lighting:
```glsl
#version 300 es
precision mediump float;
in vec4 v_Color;
in vec3 v_Normal;
out vec4 fragColor;

void main() {
    vec3 lightDir = normalize(vec3(0.3, 0.5, 1.0));
    float diff = max(dot(normalize(v_Normal), lightDir), 0.0);
    float ambient = 0.4;
    float lighting = ambient + (1.0 - ambient) * diff;
    fragColor = vec4(v_Color.rgb * lighting, v_Color.a);
}
```

- [ ] **Step 2: Add tube geometry generation to GcodeRenderer**

Add a second VBO for tube quads. For each extrusion move, generate 6 vertices (2 triangles):
- Direction vector: `d = normalize(end - start)`
- Perpendicular: `perp = (-d.y, d.x, 0) * halfWidth`
- Top face: 4 vertices at z + halfHeight, expanded by perp
- Vertices: (start-perp, start+perp, end+perp), (start-perp, end+perp, end-perp)

Each vertex: 3 pos + 3 normal + 4 color = 10 floats.

Track layer ranges separately for tubes (same LayerRange pattern).

- [ ] **Step 3: Add tube shader loading and draw call**

In `onSurfaceCreated()`:
```kotlin
tubeShader = ShaderProgram(context, "shaders/tube.vert", "shaders/tube.frag")
```

In `drawToolpaths()`, draw tubes instead of lines for extrusion moves. Keep GL_LINES for travel moves (when showTravel is on).

- [ ] **Step 4: Handle OOM — tube vertex budget**

Tube quads use 6 vertices per move vs 2 for lines (3x more). Reduce the 80MB buffer limit check accordingly, or keep the line fallback for very large files (>500K moves).

Add a threshold:
```kotlin
val useTubes = totalExtrusionMoves < 500_000
```

When `useTubes` is false, fall back to the existing GL_LINES path.

- [ ] **Step 5: Build and verify**

Run: `.\gradlew assembleDebug --no-daemon`

- [ ] **Step 6: Test on device with a known model**

Install on test device, slice a model, verify tubes render correctly in the G-code viewer.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/assets/shaders/tube.vert \
       app/src/main/assets/shaders/tube.frag \
       app/src/main/java/com/u1/slicer/viewer/GcodeRenderer.kt
git commit -m "feat: F19 render G-code extrusion paths as lit 3D ribbon quads"
```

---

## Final Verification

- [ ] **Run full unit test suite**: `.\gradlew testDebugUnitTest --no-daemon` — all 322+ tests pass
- [ ] **Run instrumented tests**: `.\gradlew connectedDebugAndroidTest --no-daemon` — all 103+ tests pass
- [ ] **E2E test on device**: Install, load a Bambu 3MF, verify:
  - Thumbnail in G-code (B19)
  - Per-extruder filament display (F12)
  - Bed temp shown (F15)
  - Print vs Upload buttons (F16)
  - Travel toggle in viewer (F20)
  - Scale updates preview (B20)
  - Accordion settings (F18)
  - 3D tube rendering (F19)
- [ ] **Update backlog**: Mark B19, F12, F15, F16, F20, B20, F17, F18, F19 as DONE
