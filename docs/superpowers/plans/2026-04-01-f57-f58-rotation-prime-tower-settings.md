# F57 + F58: Model Rotation, Prime Tower Settings & Collapsible Cards Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add full 3-axis model rotation, prime tower width/rotation settings, and collapsible Prepare screen cards.

**Architecture:** Native `setModelRotation` mirrors `setModelScale` (same pattern in `sapil_arrange.cpp`); Kotlin ViewModel carries `ModelRotation` StateFlow; UI adds a Rotation tab inside the existing `ScaleSection` card; F58 threads two new fields through the existing `SlicingOverrides` → `buildProfileOverridesImpl` → `profile_keys[]` pipeline.

**Tech Stack:** Kotlin/Compose, OrcaSlicer C++ (`ModelInstance::set_rotation`), JNI, Gradle/NDK ninja build

---

## File Map

| File | Change |
|---|---|
| `app/src/main/cpp/include/sapil.h` | Add `setModelRotation` declaration |
| `app/src/main/cpp/src/sapil_arrange.cpp` | Implement `setModelRotation` |
| `app/src/main/cpp/src/slicer_wrapper.cpp` | Add JNI wrapper for `setModelRotation` |
| `app/src/main/jniLibs/arm64-v8a/libsapil.so` | Rebuilt native library |
| `app/src/main/java/com/u1/slicer/NativeLibrary.kt` | Add `external fun setModelRotation` |
| `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` | Add `ModelRotation` data class, StateFlow, setter, reset, call in `startSlicing()` |
| `app/src/main/java/com/u1/slicer/MainActivity.kt` | Add rotation tab + collapsible to `ScaleSection`; add collapsible to `PrintSetupSection`; rotation overlay badge; pass rotation to ScaleSection |
| `app/src/main/java/com/u1/slicer/data/SlicingOverrides.kt` | Add `primeTowerWidth`, `wipeTowerRotationAngle` fields + ORCA_DEFAULTS + toJson/fromJson |
| `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` | Resolve + emit new override fields in `buildProfileOverridesImpl` |
| `app/src/main/cpp/src/sapil_print.cpp` | Add `"wipe_tower_rotation_angle"` to `profile_keys[]` |
| `app/src/main/java/com/u1/slicer/ui/SlicingOverridesUI.kt` | Add two new `OverrideRow`s in Prime Tower section |
| `app/src/test/java/com/u1/slicer/data/SlicingOverridesTest.kt` | Add tests for new fields |
| `app/src/androidTest/java/com/u1/slicer/slicing/SlicingIntegrationTest.kt` | Add rotation smoke test |

---

## Task 1: Add `setModelRotation` to native layer

**Files:**
- Modify: `app/src/main/cpp/include/sapil.h`
- Modify: `app/src/main/cpp/src/sapil_arrange.cpp`
- Modify: `app/src/main/cpp/src/slicer_wrapper.cpp`

- [ ] **Step 1: Add declaration to `sapil.h`**

After the `setModelScale` declaration (around line 150), add:

```cpp
// Rotate the loaded model (Euler angles in degrees, applied per instance).
// Call after setModelScale and before setModelInstances.
bool setModelRotation(float rx_deg, float ry_deg, float rz_deg);
```

- [ ] **Step 2: Implement in `sapil_arrange.cpp`**

After the closing brace of `setModelScale` (around line 138), add:

```cpp
bool SlicerEngine::setModelRotation(float rx_deg, float ry_deg, float rz_deg) {
    if (!isModelLoaded()) {
        SAPIL_LOGE("setModelRotation: no model loaded");
        return false;
    }
    Slic3r::Model& model = getGlobalModel();
    for (auto* obj : model.objects) {
        for (auto* inst : obj->instances) {
            inst->set_rotation(Slic3r::Vec3d(
                static_cast<double>(rx_deg),
                static_cast<double>(ry_deg),
                static_cast<double>(rz_deg)
            ));
        }
    }
    invalidatePreviewMeshCache();
    SAPIL_LOGI("Set model rotation: %.1f, %.1f, %.1f deg", rx_deg, ry_deg, rz_deg);
    return true;
}
```

- [ ] **Step 3: Add JNI wrapper in `slicer_wrapper.cpp`**

Find the JNI wrapper for `setModelScale` (search for `setModelScale` in `slicer_wrapper.cpp`) and add the following immediately after it:

```cpp
extern "C" JNIEXPORT jboolean JNICALL
Java_com_u1_slicer_NativeLibrary_setModelRotation(
        JNIEnv*, jobject, jfloat rx, jfloat ry, jfloat rz) {
    return engine().setModelRotation(rx, ry, rz);
}
```

- [ ] **Step 4: Rebuild the native `.so`**

Follow the standard rebuild workflow from CLAUDE.md:

```bash
# 1. In app/build.gradle, uncomment the externalNativeBuild blocks, then:
./gradlew assembleDebug   # configures CMake, generates build files

# 2. Re-comment externalNativeBuild, then find the ninja dir:
ls app/.cxx/Debug/*/arm64-v8a/

# 3. Build with ninja (j1 to avoid OOM):
ninja -j1 -C app/.cxx/Debug/<hash>/arm64-v8a/ sapil

# 4. Strip:
$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip \
  --strip-unneeded app/.cxx/Debug/<hash>/arm64-v8a/libsapil.so

# 5. Copy:
cp app/.cxx/Debug/<hash>/arm64-v8a/libsapil.so \
   app/src/main/jniLibs/arm64-v8a/libsapil.so
```

- [ ] **Step 5: Add Kotlin JNI declaration to `NativeLibrary.kt`**

After the `setModelScale` declaration, add:

```kotlin
external fun setModelRotation(x: Float, y: Float, z: Float): Boolean
```

- [ ] **Step 6: Verify JNI symbol exists in rebuilt `.so`**

```bash
nm app/src/main/jniLibs/arm64-v8a/libsapil.so | grep setModelRotation
```

Expected: a line containing `T Java_com_u1_slicer_NativeLibrary_setModelRotation`

- [ ] **Step 7: Commit**

```bash
git add app/src/main/cpp/include/sapil.h \
        app/src/main/cpp/src/sapil_arrange.cpp \
        app/src/main/cpp/src/slicer_wrapper.cpp \
        app/src/main/jniLibs/arm64-v8a/libsapil.so \
        app/src/main/java/com/u1/slicer/NativeLibrary.kt
git commit -m "feat(f57): add setModelRotation JNI method to native sapil layer"
```

---

## Task 2: ViewModel — `ModelRotation` state

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`

- [ ] **Step 1: Add `ModelRotation` data class**

Find the `ModelScale` data class (line 187) and add `ModelRotation` immediately after the closing brace of `ModelScale`:

```kotlin
data class ModelRotation(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f)
```

- [ ] **Step 2: Add StateFlow and setter**

Find `private val _modelScale` (line 191) and add the rotation StateFlow below it:

```kotlin
private val _modelRotation = MutableStateFlow(ModelRotation())
val modelRotation: StateFlow<ModelRotation> = _modelRotation.asStateFlow()
```

Find `fun setModelScale` (line 1267) and add `setModelRotation` immediately after it:

```kotlin
fun setModelRotation(rotation: ModelRotation) {
    _modelRotation.value = rotation
    customObjectPositions = null // reset positions — re-center for rotated footprint
}
```

- [ ] **Step 3: Reset rotation on model load**

Find the line `_modelScale.value = ModelScale()` (line 1007) and add the reset on the next line:

```kotlin
_modelRotation.value = ModelRotation()
```

- [ ] **Step 4: Call `setModelRotation` in `startSlicing()`**

Find the scale call in `startSlicing()`:

```kotlin
val scale = _modelScale.value
if (scale.x != 1f || scale.y != 1f || scale.z != 1f) {
    native.setModelScale(scale.x, scale.y, scale.z)
}
```

Add rotation call immediately after it:

```kotlin
val rot = _modelRotation.value
if (rot.x != 0f || rot.y != 0f || rot.z != 0f) {
    native.setModelRotation(rot.x, rot.y, rot.z)
}
```

- [ ] **Step 5: Build to verify no compile errors**

```bash
./gradlew assembleDebug --no-daemon
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/u1/slicer/SlicerViewModel.kt
git commit -m "feat(f57): add ModelRotation StateFlow and setModelRotation to SlicerViewModel"
```

---

## Task 3: UI — Rotation tab + collapsible Scale card

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/MainActivity.kt`

- [ ] **Step 1: Add rotation state collection and pass-through in Prepare screen**

Find the line `val modelScale by viewModel.modelScale.collectAsState()` (line 685) and add below it:

```kotlin
val modelRotation by viewModel.modelRotation.collectAsState()
```

Find the `ScaleSection` call (line 879):

```kotlin
ScaleSection(
    scale = modelScale,
    onScaleChange = { viewModel.setModelScale(it) },
    copyCount = copyCount,
    onSetCopyCount = viewModel::setCopyCount
)
```

Replace with:

```kotlin
ScaleSection(
    scale = modelScale,
    onScaleChange = { viewModel.setModelScale(it) },
    copyCount = copyCount,
    onSetCopyCount = viewModel::setCopyCount,
    rotation = modelRotation,
    onRotationChange = { viewModel.setModelRotation(it) }
)
```

- [ ] **Step 2: Add rotation overlay badge in 3D viewport**

Find the existing scale overlay block (around line 2284):

```kotlin
val isScaled = modelScale.x != 1f || modelScale.y != 1f || modelScale.z != 1f
if (isScaled) {
```

Add a rotation overlay after the closing `}` of the scale overlay:

```kotlin
val isRotated = modelRotation.x != 0f || modelRotation.y != 0f || modelRotation.z != 0f
if (isRotated) {
    val rotText = buildString {
        val parts = listOf("X" to modelRotation.x, "Y" to modelRotation.y, "Z" to modelRotation.z)
            .filter { (_, v) -> v != 0f }
        if (parts.size == 1) {
            append("${parts[0].first}: %.0f°".format(parts[0].second))
        } else {
            append(parts.joinToString(" ") { (ax, v) -> "$ax:%.0f°".format(v) })
        }
    }
    Text(
        rotText,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(start = 8.dp, bottom = 36.dp) // above scale badge
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = Color.White
    )
}
```

Note: this composable already has `modelScale` and `modelRotation` in scope from Step 1. Verify `modelRotation` is available at this point in the composable — if the overlay is inside a separate composable that only received `modelScale`, you'll need to also pass `modelRotation` to it.

- [ ] **Step 3: Update `ScaleSection` signature and add collapsible + tabs**

Find `fun ScaleSection(` (line 2677). Replace the entire `ScaleSection` composable with:

```kotlin
@Composable
fun ScaleSection(
    scale: SlicerViewModel.ModelScale,
    onScaleChange: (SlicerViewModel.ModelScale) -> Unit,
    copyCount: Int = 1,
    onSetCopyCount: (Int) -> Unit = {},
    rotation: SlicerViewModel.ModelRotation = SlicerViewModel.ModelRotation(),
    onRotationChange: (SlicerViewModel.ModelRotation) -> Unit = {}
) {
    var uniformMode by remember { mutableStateOf(true) }
    var uniformValue by remember(scale) { mutableFloatStateOf(scale.uniform) }
    var expanded by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableIntStateOf(0) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Header row — tappable to collapse/expand
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.OpenWith, null, tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Scale, Copies & Rotation", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                            text = { Text("Scale") })
                        Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                            text = { Text("Rotation") })
                    }

                    if (selectedTab == 0) {
                        // --- Scale tab (existing content) ---
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Copies: $copyCount", style = MaterialTheme.typography.labelMedium)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Uniform", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                Spacer(Modifier.width(4.dp))
                                Switch(checked = uniformMode, onCheckedChange = { uniformMode = it })
                            }
                        }
                        Slider(
                            value = copyCount.toFloat(),
                            onValueChange = { v -> onSetCopyCount(v.toInt()) },
                            valueRange = 1f..16f,
                            steps = 14
                        )
                        Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        if (uniformMode) {
                            val pct = "%.0f%%".format(uniformValue * 100)
                            Text("Scale: $pct", style = MaterialTheme.typography.labelMedium)
                            Slider(
                                value = uniformValue,
                                onValueChange = { v ->
                                    uniformValue = v
                                    onScaleChange(SlicerViewModel.ModelScale(v, v, v))
                                },
                                valueRange = 0.1f..3f,
                                steps = 28
                            )
                        } else {
                            listOf("X" to scale.x, "Y" to scale.y, "Z" to scale.z).forEach { (axis, v) ->
                                Text("$axis: ${"%.0f%%".format(v * 100)}", style = MaterialTheme.typography.labelMedium)
                                Slider(
                                    value = v,
                                    onValueChange = { nv ->
                                        val ns = when (axis) {
                                            "X" -> scale.copy(x = nv)
                                            "Y" -> scale.copy(y = nv)
                                            else -> scale.copy(z = nv)
                                        }
                                        onScaleChange(ns)
                                    },
                                    valueRange = 0.1f..3f,
                                    steps = 28
                                )
                            }
                        }
                        if (scale.x != 1f || scale.y != 1f || scale.z != 1f) {
                            TextButton(
                                onClick = {
                                    uniformValue = 1f
                                    onScaleChange(SlicerViewModel.ModelScale())
                                },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("Reset to 100%", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    } else {
                        // --- Rotation tab ---
                        data class RotAxis(val label: String, val value: Float, val range: ClosedFloatingPointRange<Float>, val steps: Int)
                        val axes = listOf(
                            RotAxis("Tilt (X)", rotation.x, -180f..180f, 35),
                            RotAxis("Tilt (Y)", rotation.y, -180f..180f, 35),
                            RotAxis("Rotate on bed (Z)", rotation.z, 0f..360f, 71)
                        )
                        axes.forEachIndexed { idx, ax ->
                            Text("${ax.label}: ${"%.0f°".format(ax.value)}",
                                style = MaterialTheme.typography.labelMedium)
                            Slider(
                                value = ax.value,
                                onValueChange = { nv ->
                                    onRotationChange(when (idx) {
                                        0 -> rotation.copy(x = nv)
                                        1 -> rotation.copy(y = nv)
                                        else -> rotation.copy(z = nv)
                                    })
                                },
                                valueRange = ax.range,
                                steps = ax.steps
                            )
                        }
                        if (rotation.x != 0f || rotation.y != 0f || rotation.z != 0f) {
                            TextButton(
                                onClick = { onRotationChange(SlicerViewModel.ModelRotation()) },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("Reset to 0°", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: Add collapsible header to `PrintSetupSection`**

Find `fun PrintSetupSection(` (line 2527). Add an `expanded` state and wrap the body in `AnimatedVisibility`.

At the top of `PrintSetupSection`, add:

```kotlin
var expanded by remember { mutableStateOf(true) }
```

Find the existing header `Row` inside the card that contains the `"Print Setup"` text:

```kotlin
Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(Icons.Default.Palette, null, tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(20.dp))
    Spacer(Modifier.width(8.dp))
    Text("Print Setup", fontWeight = FontWeight.Bold, fontSize = 16.sp)
}
```

Replace it with:

```kotlin
Row(
    modifier = Modifier
        .fillMaxWidth()
        .clickable { expanded = !expanded },
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Palette, null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("Print Setup", fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
    Icon(
        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
        contentDescription = if (expanded) "Collapse" else "Expand",
        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
}
```

Then wrap all content below the header row (the `if (isMultiColor)` block and all rows below it) in:

```kotlin
AnimatedVisibility(visible = expanded) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // existing content here
    }
}
```

- [ ] **Step 5: Build**

```bash
./gradlew assembleDebug --no-daemon
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/u1/slicer/MainActivity.kt
git commit -m "feat(f57): add rotation tab + collapsible to Scale card; collapsible Print Setup card"
```

---

## Task 4: F58 — Prime tower width and rotation angle overrides

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/data/SlicingOverrides.kt`
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`
- Modify: `app/src/main/cpp/src/sapil_print.cpp`
- Modify: `app/src/main/java/com/u1/slicer/ui/SlicingOverridesUI.kt`

- [ ] **Step 1: Add fields to `SlicingOverrides.kt`**

In the `SlicingOverrides` data class, find the `primeTowerChamferMaxWidth` field (line 67) and add the two new fields after it:

```kotlin
val primeTowerWidth: OverrideValue<Float> = OverrideValue(),
val wipeTowerRotationAngle: OverrideValue<Float> = OverrideValue(),
```

In `ORCA_DEFAULTS` (around line 215), after `"primeTowerChamferMaxWidth" to 5f`, add:

```kotlin
"primeTowerWidth" to 35f,
"wipeTowerRotationAngle" to 0f,
```

In `toJson()`, after the `putOverride("primeTowerChamferMaxWidth", ...)` line (around line 171), add:

```kotlin
putOverride("primeTowerWidth", primeTowerWidth)
putOverride("wipeTowerRotationAngle", wipeTowerRotationAngle)
```

In `fromJson()`, after the `primeTowerChamferMaxWidth = parseOverride(...)` line (around line 266), add:

```kotlin
primeTowerWidth = parseOverride("primeTowerWidth") { (it as Number).toFloat() },
wipeTowerRotationAngle = parseOverride("wipeTowerRotationAngle") { (it as Number).toFloat() },
```

- [ ] **Step 2: Resolve and emit in `buildProfileOverridesImpl` in `SlicerViewModel.kt`**

In `buildProfileOverridesImpl` (line 2988), find the prime tower resolve block (around line 3046–3051):

```kotlin
val primeVolume = resolve(ov.primeVolume, 45, "primeVolume")
val primeTowerBrimWidth = resolve(ov.primeTowerBrimWidth, 3f, "primeTowerBrimWidth")
val primeTowerBrimChamfer = resolve(ov.primeTowerBrimChamfer, true, "primeTowerBrimChamfer")
val primeTowerChamferMaxWidth = resolve(ov.primeTowerChamferMaxWidth, 5f, "primeTowerChamferMaxWidth")
```

Add two new lines after `primeTowerChamferMaxWidth`:

```kotlin
val primeTowerWidth = resolve(ov.primeTowerWidth, cfg.wipeTowerWidth, "primeTowerWidth")
val wipeTowerRotationAngle = resolve(ov.wipeTowerRotationAngle, 0f, "wipeTowerRotationAngle")
```

Find the `"prime_tower_width"` line in the result map (line 3080):

```kotlin
"prime_tower_width" to cfg.wipeTowerWidth.toString(),
```

Replace it with:

```kotlin
"prime_tower_width" to primeTowerWidth.toString(),
```

After the `"prime_tower_brim_chamfer_max_width"` line (line 3086), add:

```kotlin
"wipe_tower_rotation_angle" to wipeTowerRotationAngle.toString(),
```

- [ ] **Step 3: Add `wipe_tower_rotation_angle` to `profile_keys[]` in `sapil_print.cpp`**

Find the Prime Tower section in `profile_keys[]` (around line 617–623):

```cpp
// Prime tower
"enable_prime_tower",
"prime_tower_width",
"prime_volume",
"prime_tower_brim_width",
"wipe_tower_x",
"wipe_tower_y",
```

Add `"wipe_tower_rotation_angle"` after `"wipe_tower_y"`:

```cpp
"wipe_tower_y",
"wipe_tower_rotation_angle",
```

Note: `prime_tower_width` is already present — no change needed there.

- [ ] **Step 4: Add OverrideRows to `SlicingOverridesUI.kt`**

Find the Brim Chamfer Max Width `OverrideRow` (last row in the Prime Tower section, around line 622–636). Add the two new rows after it:

```kotlin
OverrideRow(
    label = "Tower Width",
    override = overrides.primeTowerWidth,
    defaultHint = "35 mm",
    onModeChange = { mode -> onOverridesChange(overrides.copy(primeTowerWidth = overrides.primeTowerWidth.copy(mode = mode))) },
    fileKey = "prime_tower_width",
    sourceConfig = sourceConfig,
    valueContent = {
        OverrideFloatField(
            value = overrides.primeTowerWidth.value ?: 35f,
            suffix = "mm",
            onValueChange = { onOverridesChange(overrides.copy(primeTowerWidth = OverrideValue(OverrideMode.OVERRIDE, it))) }
        )
    }
)

OverrideRow(
    label = "Tower Rotation",
    override = overrides.wipeTowerRotationAngle,
    defaultHint = "0°",
    onModeChange = { mode -> onOverridesChange(overrides.copy(wipeTowerRotationAngle = overrides.wipeTowerRotationAngle.copy(mode = mode))) },
    fileKey = "wipe_tower_rotation_angle",
    sourceConfig = sourceConfig,
    valueContent = {
        OverrideFloatField(
            value = overrides.wipeTowerRotationAngle.value ?: 0f,
            suffix = "°",
            onValueChange = { onOverridesChange(overrides.copy(wipeTowerRotationAngle = OverrideValue(OverrideMode.OVERRIDE, it))) }
        )
    }
)
```

- [ ] **Step 5: Build**

```bash
./gradlew assembleDebug --no-daemon
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/u1/slicer/data/SlicingOverrides.kt \
        app/src/main/java/com/u1/slicer/SlicerViewModel.kt \
        app/src/main/cpp/src/sapil_print.cpp \
        app/src/main/java/com/u1/slicer/ui/SlicingOverridesUI.kt
git commit -m "feat(f58): add prime tower width and rotation angle overrides"
```

---

## Task 5: Unit tests

**Files:**
- Modify: `app/src/test/java/com/u1/slicer/data/SlicingOverridesTest.kt`

- [ ] **Step 1: Write failing tests**

Add the following test methods to `SlicingOverridesTest`:

```kotlin
@Test
fun `primeTowerWidth OVERRIDE resolves into wipeTowerWidth`() {
    val base = SliceConfig(wipeTowerWidth = 60f)
    val ov = SlicingOverrides(primeTowerWidth = OverrideValue(OverrideMode.OVERRIDE, 20f))
    val result = ov.resolveInto(base)
    assertThat(result.wipeTowerWidth).isEqualTo(20f)
}

@Test
fun `primeTowerWidth USE_FILE keeps base wipeTowerWidth`() {
    val base = SliceConfig(wipeTowerWidth = 60f)
    val ov = SlicingOverrides(primeTowerWidth = OverrideValue(OverrideMode.USE_FILE))
    val result = ov.resolveInto(base)
    assertThat(result.wipeTowerWidth).isEqualTo(60f)
}

@Test
fun `primeTowerWidth ORCA_DEFAULT uses 35mm`() {
    val base = SliceConfig(wipeTowerWidth = 60f)
    val ov = SlicingOverrides(primeTowerWidth = OverrideValue(OverrideMode.ORCA_DEFAULT))
    val result = ov.resolveInto(base)
    assertThat(result.wipeTowerWidth).isEqualTo(35f)
}

@Test
fun `wipeTowerRotationAngle round-trips through JSON`() {
    val ov = SlicingOverrides(wipeTowerRotationAngle = OverrideValue(OverrideMode.OVERRIDE, 45f))
    val json = ov.toJson()
    val restored = SlicingOverrides.fromJson(json)
    assertThat(restored.wipeTowerRotationAngle.mode).isEqualTo(OverrideMode.OVERRIDE)
    assertThat(restored.wipeTowerRotationAngle.value).isEqualTo(45f)
}

@Test
fun `primeTowerWidth round-trips through JSON`() {
    val ov = SlicingOverrides(primeTowerWidth = OverrideValue(OverrideMode.OVERRIDE, 25f))
    val json = ov.toJson()
    val restored = SlicingOverrides.fromJson(json)
    assertThat(restored.primeTowerWidth.mode).isEqualTo(OverrideMode.OVERRIDE)
    assertThat(restored.primeTowerWidth.value).isEqualTo(25f)
}

@Test
fun `wipeTowerRotationAngle OVERRIDE emitted in buildProfileOverrides`() {
    val cfg = SliceConfig(wipeTowerEnabled = true, extruderCount = 2)
    val ov = SlicingOverrides(wipeTowerRotationAngle = OverrideValue(OverrideMode.OVERRIDE, 45f))
    val result = buildProfileOverridesImpl(cfg, ov, extCount = 2)
    assertThat(result["wipe_tower_rotation_angle"]).isEqualTo("45.0")
}

@Test
fun `primeTowerWidth OVERRIDE emitted in buildProfileOverrides`() {
    val cfg = SliceConfig(wipeTowerEnabled = true, wipeTowerWidth = 60f, extruderCount = 2)
    val ov = SlicingOverrides(primeTowerWidth = OverrideValue(OverrideMode.OVERRIDE, 20f))
    val result = buildProfileOverridesImpl(cfg, ov, extCount = 2)
    assertThat(result["prime_tower_width"]).isEqualTo("20.0")
}
```

Note: `buildProfileOverridesImpl` is `internal` in `SlicerViewModel.kt` — it is already tested directly in `SlicingOverridesTest` (check the existing `buildProfileOverrides` tests for the import pattern).

- [ ] **Step 2: Run tests — expect failure**

```bash
./gradlew testDebugUnitTest --tests "com.u1.slicer.data.SlicingOverridesTest" --no-daemon
```

Expected: failures on the new tests (fields not yet added — or if Task 4 is done first, they should pass)

- [ ] **Step 3: Run all unit tests after Task 4 is complete**

```bash
./gradlew testDebugUnitTest --no-daemon
```

Expected: all 600+ tests pass (595 existing + 7 new)

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/com/u1/slicer/data/SlicingOverridesTest.kt
git commit -m "test(f58): add unit tests for primeTowerWidth and wipeTowerRotationAngle overrides"
```

---

## Task 6: Instrumented rotation smoke test

**Files:**
- Modify: `app/src/androidTest/java/com/u1/slicer/slicing/SlicingIntegrationTest.kt`

- [ ] **Step 1: Add rotation smoke test**

The test class uses `asset(name)` helper and `lib` field (NativeLibrary). Add the following new test after the existing STL tests:

```kotlin
@Test
fun tetrahedron_stl_slicesSuccessfully_withRotation() {
    // tetrahedron.stl is the smallest bundled asset (~4 triangles)
    val file = asset("tetrahedron.stl")
    assertTrue("Model should load", lib.loadModel(file.absolutePath))

    // Rotate 90° on X — model stands upright; should still produce valid G-code
    assertTrue("setModelRotation should succeed", lib.setModelRotation(90f, 0f, 0f))

    val result = lib.slice(DEFAULT_CONFIG)
    assertNotNull("Slice result should not be null", result)
    assertTrue("Slice should succeed after rotation", result!!.success)
    assertTrue("G-code path should be non-empty", result.gcodePath.isNotEmpty())
    assertTrue("G-code file should be non-empty",
        File(result.gcodePath).length() > 0)
}
```

- [ ] **Step 2: Run the instrumented test on device**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest \
  --tests "com.u1.slicer.slicing.SlicingIntegrationTest.sliceWithRotation_producesGcode" \
  --no-daemon
```

Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/java/com/u1/slicer/slicing/SlicingIntegrationTest.kt
git commit -m "test(f57): add rotation smoke test to SlicingIntegrationTest"
```

---

## Task 7: Final verification and bump

- [ ] **Step 1: Run all unit tests**

```bash
./gradlew testDebugUnitTest --no-daemon
```

Expected: all tests pass

- [ ] **Step 2: Install on device and manual smoke test**

```bash
./gradlew installDebug --no-daemon
```

Manual checks:
1. Load any STL. Tap Scale card header — card collapses. Tap again — expands.
2. Tap "Rotation" tab. Drag Z slider to 45°. Overlay badge shows "Z: 45°".
3. Drag X slider to 90°. Overlay shows "X: 90° Z: 45°". Tap "Reset to 0°" — all zero, badge gone.
4. Slice with X=90°. Confirm G-code generates (layer count should reflect standing model).
5. Open Prepare settings → Prime Tower section. Confirm "Tower Width" and "Tower Rotation" rows appear.
6. Set Tower Width to 20mm, slice multi-colour model, open G-code file, search for `prime_tower_width` — should be `20`.
7. Open Settings screen → Prime Tower section. Confirm same two new rows appear.
8. Load a multi-colour model. Tap Print Setup header — collapses. Tap again — expands.

- [ ] **Step 3: Bump version**

In `app/build.gradle`, increment `versionCode` by 1 and bump `versionName` (e.g. `1.5.25` → `1.5.26`).

Update test counts in `CLAUDE.md` and `README.md` if the unit test count changed (595 + 7 new = 602 unit tests; instrumented 139 + 1 = 140).

- [ ] **Step 4: Final commit**

```bash
git add app/build.gradle CLAUDE.md README.md
git commit -m "bump: v1.5.26 - model rotation (F57), prime tower width+rotation settings (F58), collapsible cards"
```
