# F73: Multi-Plate Navigation — Design Spec

**Date:** 2026-04-21
**Feature:** Change plate without reloading the file

---

## Problem

After loading a multi-plate 3MF and selecting a plate, returning to the plate picker requires exiting and fully reloading the file. The native model is already in memory — re-selection is fast, but the UI offers no way to trigger it.

---

## Goal

Let the user switch to a different plate from the Prepare screen with one tap, without reloading the file.

---

## Key Insight

`_multiPlateSourceFile` (the full processed 3MF) and `_fileThreeMfInfo` (the original file-level `ThreeMfInfo` with all plates) both survive `selectPlate()` — they are only cleared by `dismissPlateSelector()` (cancel) or loading a new model. Re-opening the dialog just requires setting `_showPlateSelector.value = true` again, but the dialog needs to read plates from `_fileThreeMfInfo` (not the post-selection `_threeMfInfo`, which only has the single selected plate).

---

## Design

### `SlicerViewModel.kt`

**New state:**

```kotlin
private val _multiPlatePlates = MutableStateFlow<List<com.u1.slicer.bambu.PlateInfo>>(emptyList())
val multiPlatePlates: StateFlow<List<com.u1.slicer.bambu.PlateInfo>> = _multiPlatePlates.asStateFlow()
```

**Set** at the point where `_fileThreeMfInfo = mergedInfo` is assigned (line ~1582):
```kotlin
_multiPlatePlates.value = if (mergedInfo.isMultiPlate) mergedInfo.plates else emptyList()
```

**Clear** wherever `_fileThreeMfInfo = null` is assigned (dismissPlateSelector ~line 1101, newModel ~line 2952):
```kotlin
_multiPlatePlates.value = emptyList()
```

**New function:**

```kotlin
fun reopenPlateSelector() {
    if (_multiPlatePlates.value.isNotEmpty()) _showPlateSelector.value = true
}
```

### `MainActivity.kt` — Prepare screen composable

**Update dialog call** to use `multiPlatePlates` instead of `threeMfInfo!!.plates` (which is empty after the first selection):

```kotlin
val multiPlatePlates by viewModel.multiPlatePlates.collectAsState()

if (showPlateSelector && multiPlatePlates.isNotEmpty()) {
    PlateSelectDialog(
        plates = multiPlatePlates,
        ...
    )
}
```

**Add "Change plate" chip** — visible when `multiPlatePlates.isNotEmpty()` and state is `ModelLoaded` or `SliceComplete`. Placement: below the colour chip row, above the Scale card.

```kotlin
if (multiPlatePlates.isNotEmpty()) {
    AssistChip(
        onClick = { viewModel.reopenPlateSelector() },
        label = { Text("Change plate") },
        leadingIcon = { Icon(Icons.Default.Layers, contentDescription = null, Modifier.size(16.dp)) }
    )
}
```

---

## Data Flow

```
User taps "Change plate"
  → reopenPlateSelector() → _showPlateSelector.value = true
  → PlateSelectDialog shown with multiPlatePlates (original full list)
  → User selects plate N
  → selectPlate(N) → _showPlateSelector = false → extract + embed + load
  → multiPlatePlates unchanged (still full list, ready for another change)

User taps Cancel in dialog
  → dismissPlateSelector() → _showPlateSelector = false
  → _multiPlatePlates.value = emptyList() (chip disappears)
  → _multiPlateSourceFile = null, _fileThreeMfInfo = null
  → Idle state
```

Note: Cancel from a reopen clears everything (same as the original cancel). This is correct — the user explicitly dismissed.

---

## Files

| File | Change |
|---|---|
| `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` | Add `_multiPlatePlates` StateFlow, set/clear alongside `_fileThreeMfInfo`, add `reopenPlateSelector()` |
| `app/src/main/java/com/u1/slicer/MainActivity.kt` | Collect `multiPlatePlates`, update `PlateSelectDialog` call, add `AssistChip` |

No new files. No native changes.

---

## Tests

Unit test in `SlicerViewModelTest` or a new `MultiPlateNavTest`:
- `multiPlatePlates` is populated after a multi-plate load and survives `selectPlate()`
- `multiPlatePlates` is cleared after `dismissPlateSelector()`
- `reopenPlateSelector()` sets `showPlateSelector = true` when plates are non-empty
- `reopenPlateSelector()` is a no-op when `multiPlatePlates` is empty
