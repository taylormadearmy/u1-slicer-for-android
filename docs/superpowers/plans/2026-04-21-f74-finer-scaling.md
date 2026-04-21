# F74: Finer Model Scaling — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the stepped scale slider (10% increments) with a continuous slider + editable percentage text field so users can set any scale from 10%–300%.

**Architecture:** Single composable change in `ScaleSection` — remove `steps = 28` from all scale sliders, replace the read-only `Text("Scale: $pct")` / `Text("$axis: $pct")` labels with `OutlinedTextField` widgets that accept direct numeric entry. Three new imports required.

**Tech Stack:** Jetpack Compose, Material3, `KeyboardActions`, `ImeAction`, `LocalFocusManager`.

---

## Files

| File | Change |
|---|---|
| `app/src/main/java/com/u1/slicer/MainActivity.kt` | Add 3 imports; replace scale label+slider blocks with label+textfield+slider in `ScaleSection` |

No ViewModel changes. No tests (pure UI composition change).

---

## Task 1: Add imports and update ScaleSection

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/MainActivity.kt`

- [ ] **Step 1: Add 3 missing imports**

Find the existing import block around line 41–42:
```kotlin
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
```

Add immediately after those two lines:
```kotlin
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
```

- [ ] **Step 2: Replace the scale label + slider blocks in `ScaleSection`**

Find this block (starts around line 3099, inside the `if (selectedTab == 0)` branch, after the `Divider`):

```kotlin
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
```

Replace it with:

```kotlin
                        val focusManager = LocalFocusManager.current
                        if (uniformMode) {
                            var uniformText by remember(uniformValue) {
                                mutableStateOf("%.0f".format(uniformValue * 100))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Scale", style = MaterialTheme.typography.labelMedium)
                                OutlinedTextField(
                                    value = uniformText,
                                    onValueChange = { uniformText = it },
                                    suffix = { Text("%") },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(onDone = {
                                        val v = uniformText.toFloatOrNull()
                                            ?.div(100f)?.coerceIn(0.1f, 3f) ?: uniformValue
                                        uniformValue = v
                                        uniformText = "%.0f".format(v * 100)
                                        onScaleChange(SlicerViewModel.ModelScale(v, v, v))
                                        focusManager.clearFocus()
                                    }),
                                    singleLine = true,
                                    modifier = Modifier.width(96.dp)
                                )
                            }
                            Slider(
                                value = uniformValue,
                                onValueChange = { v ->
                                    uniformValue = v
                                    uniformText = "%.0f".format(v * 100)
                                    onScaleChange(SlicerViewModel.ModelScale(v, v, v))
                                },
                                valueRange = 0.1f..3f
                            )
                        } else {
                            var xText by remember(scale.x) {
                                mutableStateOf("%.0f".format(scale.x * 100))
                            }
                            var yText by remember(scale.y) {
                                mutableStateOf("%.0f".format(scale.y * 100))
                            }
                            var zText by remember(scale.z) {
                                mutableStateOf("%.0f".format(scale.z * 100))
                            }
                            // X
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("X", style = MaterialTheme.typography.labelMedium)
                                OutlinedTextField(
                                    value = xText,
                                    onValueChange = { xText = it },
                                    suffix = { Text("%") },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(onDone = {
                                        val v = xText.toFloatOrNull()
                                            ?.div(100f)?.coerceIn(0.1f, 3f) ?: scale.x
                                        xText = "%.0f".format(v * 100)
                                        onScaleChange(scale.copy(x = v))
                                        focusManager.clearFocus()
                                    }),
                                    singleLine = true,
                                    modifier = Modifier.width(96.dp)
                                )
                            }
                            Slider(
                                value = scale.x,
                                onValueChange = { nv ->
                                    xText = "%.0f".format(nv * 100)
                                    onScaleChange(scale.copy(x = nv))
                                },
                                valueRange = 0.1f..3f
                            )
                            // Y
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Y", style = MaterialTheme.typography.labelMedium)
                                OutlinedTextField(
                                    value = yText,
                                    onValueChange = { yText = it },
                                    suffix = { Text("%") },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(onDone = {
                                        val v = yText.toFloatOrNull()
                                            ?.div(100f)?.coerceIn(0.1f, 3f) ?: scale.y
                                        yText = "%.0f".format(v * 100)
                                        onScaleChange(scale.copy(y = v))
                                        focusManager.clearFocus()
                                    }),
                                    singleLine = true,
                                    modifier = Modifier.width(96.dp)
                                )
                            }
                            Slider(
                                value = scale.y,
                                onValueChange = { nv ->
                                    yText = "%.0f".format(nv * 100)
                                    onScaleChange(scale.copy(y = nv))
                                },
                                valueRange = 0.1f..3f
                            )
                            // Z
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Z", style = MaterialTheme.typography.labelMedium)
                                OutlinedTextField(
                                    value = zText,
                                    onValueChange = { zText = it },
                                    suffix = { Text("%") },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(onDone = {
                                        val v = zText.toFloatOrNull()
                                            ?.div(100f)?.coerceIn(0.1f, 3f) ?: scale.z
                                        zText = "%.0f".format(v * 100)
                                        onScaleChange(scale.copy(z = v))
                                        focusManager.clearFocus()
                                    }),
                                    singleLine = true,
                                    modifier = Modifier.width(96.dp)
                                )
                            }
                            Slider(
                                value = scale.z,
                                onValueChange = { nv ->
                                    zText = "%.0f".format(nv * 100)
                                    onScaleChange(scale.copy(z = nv))
                                },
                                valueRange = 0.1f..3f
                            )
                        }
```

- [ ] **Step 3: Build and verify no compile errors**

```bash
cd c:/Users/kevin/projects/u1-slicer-orca
./gradlew compileDebugKotlin --no-daemon --no-build-cache
```

Expected: `BUILD SUCCESSFUL`. Fix any import or type errors before continuing.

- [ ] **Step 4: Run unit tests to confirm no regressions**

```bash
./gradlew testDebugUnitTest --no-daemon
```

Expected: all 821 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/MainActivity.kt
git commit -m "feat(F74): continuous scale slider + editable percentage text field"
```
