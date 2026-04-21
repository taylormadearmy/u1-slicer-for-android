# F73: Multi-Plate Navigation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users switch plates from the Prepare screen without reloading the file, by adding a "Change plate" chip and wiring it to the existing plate selector dialog.

**Architecture:** `SlicerViewModel` gets a new `multiPlatePlates` StateFlow (the stable original plates list) and `reopenPlateSelector()`. The Prepare screen collects `multiPlatePlates`, passes it to `PlateSelectDialog` (replacing the stale post-selection `threeMfInfo!!.plates`), and shows an `AssistChip` that triggers the reopen. No native changes.

**Tech Stack:** Kotlin StateFlow, Jetpack Compose `AssistChip`, existing `PlateSelectDialog`.

---

## Files

| File | Change |
|---|---|
| `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` | Add `_multiPlatePlates` StateFlow; set/clear alongside `_fileThreeMfInfo`; add `reopenPlateSelector()` |
| `app/src/main/java/com/u1/slicer/MainActivity.kt` | Collect `multiPlatePlates`; update `PlateSelectDialog` call; add `AssistChip` |

No new files. No native changes.

---

## Task 1: ViewModel — multiPlatePlates StateFlow + reopenPlateSelector()

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`

- [ ] **Step 1: Add the new StateFlow field**

Find this block (around line 197–200):
```kotlin
    private var _fileThreeMfInfo: ThreeMfInfo? = null

    private val _showPlateSelector = MutableStateFlow(false)
    val showPlateSelector: StateFlow<Boolean> = _showPlateSelector.asStateFlow()
```

Add the new field between `_fileThreeMfInfo` and `_showPlateSelector`:
```kotlin
    private var _fileThreeMfInfo: ThreeMfInfo? = null

    private val _multiPlatePlates = MutableStateFlow<List<com.u1.slicer.bambu.PlateInfo>>(emptyList())
    val multiPlatePlates: StateFlow<List<com.u1.slicer.bambu.PlateInfo>> = _multiPlatePlates.asStateFlow()

    private val _showPlateSelector = MutableStateFlow(false)
    val showPlateSelector: StateFlow<Boolean> = _showPlateSelector.asStateFlow()
```

- [ ] **Step 2: Populate multiPlatePlates on load**

Find this block (around line 1581–1585):
```kotlin
        _threeMfInfo.value = mergedInfo
        _fileThreeMfInfo = mergedInfo
        sourceModelFile = processed
        sourceModelInfo = processedInfo
        if (origInfo.isMultiPlate) _multiPlateSourceFile = processed
```

Add `_multiPlatePlates` assignment on the line after `_fileThreeMfInfo = mergedInfo`:
```kotlin
        _threeMfInfo.value = mergedInfo
        _fileThreeMfInfo = mergedInfo
        _multiPlatePlates.value = if (origInfo.isMultiPlate) mergedInfo.plates else emptyList()
        sourceModelFile = processed
        sourceModelInfo = processedInfo
        if (origInfo.isMultiPlate) _multiPlateSourceFile = processed
```

- [ ] **Step 3: Clear multiPlatePlates in dismissPlateSelector()**

Find `fun dismissPlateSelector()` (around line 1091). It currently ends with:
```kotlin
        _multiPlateSourceFile = null
        _threeMfInfo.value = null
        _fileThreeMfInfo = null
    }
```

Add `_multiPlatePlates.value = emptyList()` after `_fileThreeMfInfo = null`:
```kotlin
        _multiPlateSourceFile = null
        _threeMfInfo.value = null
        _fileThreeMfInfo = null
        _multiPlatePlates.value = emptyList()
    }
```

- [ ] **Step 4: Clear multiPlatePlates in the new-model reset block**

Find the block that clears model state (around line 2952–2954):
```kotlin
        _fileThreeMfInfo = null
        _multiPlateSourceFile = null
        _showPlateSelector.value = false
```

Add `_multiPlatePlates.value = emptyList()` after `_fileThreeMfInfo = null`:
```kotlin
        _fileThreeMfInfo = null
        _multiPlatePlates.value = emptyList()
        _multiPlateSourceFile = null
        _showPlateSelector.value = false
```

- [ ] **Step 5: Add reopenPlateSelector()**

Find `fun dismissPlateSelector()`. Add `reopenPlateSelector()` directly after its closing `}`:
```kotlin
    fun reopenPlateSelector() {
        if (_multiPlatePlates.value.isNotEmpty()) _showPlateSelector.value = true
    }
```

- [ ] **Step 6: Build to confirm no compile errors**

```bash
cd c:/Users/kevin/projects/u1-slicer-orca
./gradlew compileDebugKotlin --no-daemon --no-build-cache
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Run unit tests**

```bash
./gradlew testDebugUnitTest --no-daemon
```

Expected: all 821 tests pass.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/u1/slicer/SlicerViewModel.kt
git commit -m "feat(F73): multiPlatePlates StateFlow + reopenPlateSelector in SlicerViewModel"
```

---

## Task 2: UI — PlateSelectDialog fix + AssistChip

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/MainActivity.kt`

- [ ] **Step 1: Collect multiPlatePlates in the Prepare screen**

Find the block of `collectAsState()` calls near the top of the Prepare composable (around line 762–765):
```kotlin
    val showPlateSelector by viewModel.showPlateSelector.collectAsState()
    val showMultiColorDialog by viewModel.showMultiColorDialog.collectAsState()
    val colorMapping by viewModel.colorMapping.collectAsState()
    val threeMfInfo by viewModel.threeMfInfo.collectAsState()
```

Add `multiPlatePlates` collection after `showPlateSelector`:
```kotlin
    val showPlateSelector by viewModel.showPlateSelector.collectAsState()
    val multiPlatePlates by viewModel.multiPlatePlates.collectAsState()
    val showMultiColorDialog by viewModel.showMultiColorDialog.collectAsState()
    val colorMapping by viewModel.colorMapping.collectAsState()
    val threeMfInfo by viewModel.threeMfInfo.collectAsState()
```

- [ ] **Step 2: Fix PlateSelectDialog to use multiPlatePlates**

Find the existing dialog block (around line 782–789):
```kotlin
    // Plate selector dialog
    if (showPlateSelector && threeMfInfo != null) {
        com.u1.slicer.ui.PlateSelectDialog(
            plates = threeMfInfo!!.plates,
            onSelect = { viewModel.selectPlate(it) },
            onDismiss = { viewModel.dismissPlateSelector() },
            info = threeMfInfo
        )
    }
```

Replace with:
```kotlin
    // Plate selector dialog
    if (showPlateSelector && multiPlatePlates.isNotEmpty()) {
        com.u1.slicer.ui.PlateSelectDialog(
            plates = multiPlatePlates,
            onSelect = { viewModel.selectPlate(it) },
            onDismiss = { viewModel.dismissPlateSelector() },
            info = threeMfInfo
        )
    }
```

- [ ] **Step 3: Add the "Change plate" AssistChip**

Find the `PrintSetupSection(...)` call (around line 986–1000). Add the chip immediately before it:
```kotlin
                        // Change plate chip — visible when a multi-plate file is loaded
                        if (multiPlatePlates.isNotEmpty()) {
                            AssistChip(
                                onClick = { viewModel.reopenPlateSelector() },
                                label = { Text("Change plate") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Layers,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                        // Inline extruder/color assignment + prime tower toggle
                        PrintSetupSection(
```

- [ ] **Step 4: Build to confirm no compile errors**

```bash
./gradlew compileDebugKotlin --no-daemon --no-build-cache
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run unit tests**

```bash
./gradlew testDebugUnitTest --no-daemon
```

Expected: all 821 tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/u1/slicer/MainActivity.kt
git commit -m "feat(F73): Change plate chip + fix PlateSelectDialog to use stable plates list"
```
