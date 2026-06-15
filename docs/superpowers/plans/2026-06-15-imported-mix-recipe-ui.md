# Imported Mix Recipe UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make imported 3MF mix recipes visible, read-only by default, and explicitly duplicable into the project mix editor without changing the existing file-first slice precedence.

**Architecture:** The slice-time source of truth remains the file-embedded `mixed_filament_definitions` string when present. The UI gets a small imported-recipe state layer in `SlicerViewModel`, a parser/summary helper for the engine row format, and a compact Filaments-section card that offers view/copy/revert actions.

**Tech Stack:** Kotlin, Jetpack Compose, existing `MixedFilamentManager`, existing `SliceConfig.mixedFilamentDefinitions`, Android unit tests.

---

### Task 1: Add imported-recipe state and parser helpers

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`
- Modify: `app/src/main/java/com/u1/slicer/data/MixedFilamentManager.kt` if a small import helper is needed
- Test: `app/src/test/java/com/u1/slicer/ResolveMixedFilamentDefinitionsForSliceTest.kt`
- Test: `app/src/test/java/com/u1/slicer/data/MixedFilamentManagerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `resolveMixedFilamentDefinitionsForSlice prefers embedded recipe over project rows`() { /* ... */ }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --no-daemon --tests com.u1.slicer.ResolveMixedFilamentDefinitionsForSliceTest`
Expected: PASS for the current precedence helper, then a new failing UI/state test should be added in Task 2.

- [ ] **Step 3: Write minimal implementation**

Add a small parser helper for `mixed_filament_definitions` row strings and expose a lightweight imported-recipe state on `SlicerViewModel`:

```kotlin
internal data class ImportedMixRecipeSummary(
    val rawRecipe: String,
    val rowCount: Int,
    val rows: List<ImportedMixRecipeRowSummary>,
)

internal data class ImportedMixRecipeRowSummary(
    val components: List<Int>,
    val weights: List<Int>,
    val distributionMode: String,
    val topMixMode: String,
    val fineTopLines: Boolean,
    val ironingGlaze: Boolean,
)
```

The parser only needs to understand the `a,b,...,g...,w...,m...,t...,f...,i...,u...` shape we already serialize.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --no-daemon --tests com.u1.slicer.ResolveMixedFilamentDefinitionsForSliceTest --tests com.u1.slicer.data.MixedFilamentManagerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/SlicerViewModel.kt app/src/main/java/com/u1/slicer/data/MixedFilamentManager.kt app/src/test/java/com/u1/slicer/ResolveMixedFilamentDefinitionsForSliceTest.kt app/src/test/java/com/u1/slicer/data/MixedFilamentManagerTest.kt
git commit -m "feat: parse imported mix recipes"
```

### Task 2: Add imported-recipe card and editable-copy flow

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/MainActivity.kt`
- Modify: `app/src/main/java/com/u1/slicer/ui/FilamentScreen.kt`
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`
- Test: `app/src/test/java/com/u1/slicer/ui/MixSwatchPaletteSourceTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `filament screen shows imported recipe card when source config has mixed definitions`() { /* ... */ }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --no-daemon --tests com.u1.slicer.ui.MixSwatchPaletteSourceTest`
Expected: FAIL until the imported-recipe card is wired in.

- [ ] **Step 3: Write minimal implementation**

Show a compact imported-recipe card above the normal mix list when `sourceConfig["mixed_filament_definitions"]` is non-empty. Add:

```kotlin
onViewImportedRecipe: () -> Unit
onCreateEditableCopy: () -> Unit
onRevertToImportedRecipe: () -> Unit
```

in the VM/UI boundary as needed so the card can:

1. expand a read-only view of the imported rows,
2. duplicate the recipe into the project mix manager,
3. revert the active source back to the file recipe.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --no-daemon --tests com.u1.slicer.ui.MixSwatchPaletteSourceTest --tests com.u1.slicer.ResolveMixedFilamentDefinitionsForSliceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/MainActivity.kt app/src/main/java/com/u1/slicer/ui/FilamentScreen.kt app/src/main/java/com/u1/slicer/SlicerViewModel.kt app/src/test/java/com/u1/slicer/ui/MixSwatchPaletteSourceTest.kt
git commit -m "feat: show imported mix recipes in the UI"
```

### Task 3: Run confidence tests, full regression, and refresh the release APK

**Files:**
- Modify: no source files expected unless a follow-up regression appears
- Output: `G:\My Drive\claude\u1-slicer-orca-release.apk`

- [ ] **Step 1: Run confidence tests**

Run: `.\scripts\run-confidence-check.ps1`
Expected: exit 0 and a completed status file.

- [ ] **Step 2: Run remaining regression tests**

Run: `./gradlew testDebugUnitTest --no-daemon`
Run: `./gradlew assembleRelease --no-daemon`
Expected: both pass.

- [ ] **Step 3: Copy release APK**

```powershell
Copy-Item -LiteralPath 'D:\projects\u1-slicer-for-android\app\build\outputs\apk\release\app-release.apk' -Destination 'G:\My Drive\claude\u1-slicer-orca-release.apk' -Force
```

- [ ] **Step 4: Verify artifact**

```powershell
Get-Item 'G:\My Drive\claude\u1-slicer-orca-release.apk' | Select-Object FullName,Length,LastWriteTime
```

