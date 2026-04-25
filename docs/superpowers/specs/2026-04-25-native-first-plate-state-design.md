# Native-First Plate State — Design Spec

**Date:** 2026-04-25
**Branch:** `refactor/bambu-via-native-loader` (HEAD: `bed0823`)
**Goal:** Eliminate Kotlin/native state disagreements for Bambu multi-plate files by reading plate state FROM native after load, instead of synthesizing it from XML parsing.

## Problem

Sub-plan #2d shipped structurally clean (all test gates green, diff harness at 0) but manual E2E testing found 6 regressions. All 6 stem from the same root cause: **Kotlin predicts plate state independently from native, and the two disagree.**

| Bug | Symptom | Root cause |
|-----|---------|------------|
| #1 Dragon Scale | 2 colours on first load, 3 on reload | Race: `_threeMfInfo` updated before native load completes |
| #2 F1 calendar | Missing colour 4 in preview | `buildSelectedPlateInfo` under-counts extruders for compound objects |
| #3 Hanging file | Model moves when sliced after user translate | Re-embed reloads model, wiping user transforms |
| #4 Calicube | 19mm position shift after scale+copy | Kotlin and native use different origin conventions for footprint |
| #5 H2C benchy | Missing tool changes in G-code | Same extruder under-count as #2, SEMM/H2C variant |
| #6 Buzz cold load | ~20s slower than v1.6.13 | `computeVisualColorCountByPlate` in `parse()` re-introduced B93 regression |

**Pattern:** Kotlin's `buildSelectedPlateInfo` synthesizes a `ThreeMfInfo` from XML regex before native loads the file. Native then loads the same file and discovers different state. The synthesis is inherently fragile — every new Bambu file structure variant can introduce a new disagreement.

## Design Principle

**Single source of truth.** After native loads a plate, Kotlin reads the authoritative state FROM native. No independent prediction, no synthesis, no disagreement.

```
CURRENT (fragile):
  Kotlin parses XML → predicts extruders/colours/positions
  Native loads file → has its own view
  → Disagree → bugs

PROPOSED:
  Kotlin preprocesses file (embed profile + strip for BBS compat)
  Native loads preprocessed file
  Kotlin reads FROM native → extruders, volumes, paint states, positions
  Kotlin builds UI from native-reported state
  → Single source of truth
```

## Architecture

### Layer 1: Kotlin Preprocessing (KEEP — stable, well-tested)

`ProfileEmbedder.embed(plateId)` continues to:
- Inject Snapmaker U1 hardware config as `project_settings.config`
- Clean Bambu-specific XML extensions (`requiredextensions="p"`, BambuStudio namespaces)
- Filter `<build>` items to the target plate (`filterModelToPlate`)
- BFS-strip orphan `<object>` blocks (`stripUnreferencedResources`)
- Strip unreferenced config objects (`stripUnreferencedConfigObjects`)
- Filter `custom_gcode_per_layer.xml` to the target plate

This code has been shipping for many releases. It fixes BBS loader bugs (Shashibo crash, Button plate_id fallthrough) at the XML level, which is where those fixes must live — BBS crashes DURING parsing, too late for C++ post-processing.

**No changes to this layer.**

### Layer 2: Native Load (KEEP — already working)

`loadNativeModel(embeddedFile)` calls `Model::read_from_file` on the preprocessed file. The embedded file is already plate-scoped, so native loads with `plate_id = 0` (all objects in the pre-filtered file).

**No changes to this layer.**

### Layer 3: Native State Reader (NEW — replaces `buildSelectedPlateInfo`)

After native loads, Kotlin reads the complete plate state via JNI accessors:

#### 3a. New JNI accessor: `nativeGetAllVolumeExtruders()`

Returns the complete per-object, per-volume extruder map in one call:

```json
[
  {
    "objectIndex": 0,
    "objectExtruder": 1,
    "volumes": [
      {"volumeIndex": 0, "extruder": 1, "isMmPainted": true, "isSeamPainted": false},
      {"volumeIndex": 1, "extruder": 2, "isMmPainted": false, "isSeamPainted": false},
      {"volumeIndex": 2, "extruder": 3, "isMmPainted": false, "isSeamPainted": false}
    ]
  }
]
```

This replaces:
- `objectExtruderMap` (XML-parsed object-level defaults — misses per-part)
- `objectPartExtruders` (XML-parsed per-part union — fragile regex)
- N*M individual `nativeGetVolumeScalars` calls (chatty)

**Implementation:** New C++ file `sapil_bambu_volume_map.cpp` (~40 lines). Walks `g_model.objects[i].volumes[j]`, reads `config.opt_int("extruder")` and `is_mm_painted()` / `is_seam_painted()`. Single JSON return.

#### 3b. Existing accessors (already sufficient)

- `nativeGetPaintStateCounts(obj, vol, kind)` — which paint extruders have triangles
- `getModelInfo()` — bounding box, dimensions (authoritative positions)
- `getInstanceOffsets()` — native-computed instance positions
- `nativeGetPlateData(plateIdx)` — palette, filament info
- `nativeGetProjectConfig()` — embedded config

#### 3c. New Kotlin helper: `readPlateStateFromNative()`

Replaces `buildSelectedPlateInfo`. Called AFTER `loadNativeModel` completes:

```kotlin
private suspend fun readPlateStateFromNative(): NativePlateState {
    // All reads under previewMutex — single atomic snapshot
    previewMutex.withLock {
        val volumeMap = native.nativeGetAllVolumeExtruders()  // NEW
        val objectCount = native.nativeGetObjectCount()
        val modelInfo = native.getModelInfo()
        val instanceOffsets = native.getInstanceOffsets()

        // Derive used extruders from volume map (authoritative)
        val usedExtruders = volumeMap.flatMap { obj ->
            obj.volumes.map { it.extruder }.filter { it > 0 }
        }.toSortedSet()

        // Derive paint state from native (replaces computeVisualColorCountByPlate)
        val hasPaintData = volumeMap.any { obj ->
            obj.volumes.any { it.isMmPainted }
        }
        val paintExtruders = if (hasPaintData) {
            // Read paint state counts for painted volumes
            collectPaintExtrudersFromNative(volumeMap)
        } else emptySet()

        return NativePlateState(
            usedExtruders = usedExtruders union paintExtruders,
            hasPaintData = hasPaintData,
            modelInfo = modelInfo,
            instanceOffsets = instanceOffsets,
            objectCount = objectCount
        )
    }
}
```

### Layer 4: Revised `selectPlate` Flow

```
selectPlate(plateId):
  1. Cancel in-progress jobs
  2. Resolve source file
  3. Transition to Loading state (unmount preview)
  4. embedProfile(sourceFile, config, plateId)     // Kotlin preprocessing
  5. loadNativeModel(embeddedFile)                  // Native load
  6. nativePlateState = readPlateStateFromNative()  // Read FROM native
  7. Build _threeMfInfo from nativePlateState        // UI state
  8. Transition to ModelLoaded
```

**Key change:** Steps 6-7 happen AFTER native load, in the same coroutine, under the same mutex scope. No race condition (#1). No independent prediction (#2, #5). No stale state.

**Step 7 detail — bridging native state to `_threeMfInfo`:**

`_threeMfInfo` is a `ThreeMfInfo` data class consumed by many UI components (PrepareScreen, PreviewScreen, color picker, summary). It has two categories of fields:

- **File-level metadata** (plate count, plate names, `isMultiPlate`, file-level `detectedColors`, `isBambuFile`): comes from `_fileThreeMfInfo`, set once during initial file load via `ThreeMfParser.parse()`. Unchanged by plate selection.

- **Per-plate state** (`usedExtruderIndices`, `detectedExtruderCount`, `hasPaintData`, `objectExtruderMap`, `hasMultiExtruderAssignments`): currently synthesized by `buildSelectedPlateInfo` + `mergeThreeMfInfoForPlate`. **This is what we replace with native reads.**

Step 7 builds a new `ThreeMfInfo` by copying file-level fields from `_fileThreeMfInfo` and overwriting per-plate fields from `NativePlateState`:

```kotlin
val fileInfo = _fileThreeMfInfo ?: return
_threeMfInfo.value = fileInfo.copy(
    usedExtruderIndices = nativePlateState.usedExtruders,
    detectedExtruderCount = nativePlateState.usedExtruders.size,
    hasPaintData = nativePlateState.hasPaintData,
    objectExtruderMap = nativePlateState.objectExtruderMap,  // from nativeGetAllVolumeExtruders
    hasMultiExtruderAssignments = nativePlateState.usedExtruders.size > 1,
    // File-level fields inherited from fileInfo via copy()
)
```

This preserves the existing UI contract while sourcing per-plate truth from native.

**What's eliminated:**
- `buildSelectedPlateInfo(sourceInfo, plateId)` — replaced by `readPlateStateFromNative()`
- `mergeThreeMfInfoForPlate` synthesis path — state comes from native, not from merging two XML parses
- `computeVisualColorCountByPlate` in `parse()` — eliminates #6 (Buzz B93 regression)

**What's preserved:**
- `_fileThreeMfInfo` (full-file parse at initial load) — still needed for plate list, plate names, file-level metadata
- `mergeThreeMfInfoForPlate` for non-Bambu files — STL/single-plate 3MF still use the existing path
- `ThreeMfParser.parse()` — still runs on initial load, but WITHOUT the expensive per-plate paint scan

### Layer 5: Transform Preservation (fixes #3, #4)

#### Fix #3: Hanging file — translate lost on re-embed

**Problem:** The pre-slice re-embed path calls `embedProfile` → `loadNativeModel`, which resets `g_model`. User transforms (`_modelScale`, `_modelRotation`, `customObjectPositions`) are Kotlin StateFlows that survive the reload, but they're only applied in `prepareSlicer()` which runs AFTER the reload. The bug is that `loadNativeModel` resets `_modelScale` and `_modelRotation` to identity (line 1221-1222).

**Fix:** In the pre-slice re-embed path, snapshot transforms BEFORE reload, skip the identity reset, and let `prepareSlicer()` re-apply them:

```kotlin
// In the pre-slice re-embed path:
val savedScale = _modelScale.value
val savedRotation = _modelRotation.value
val savedPositions = customObjectPositions

loadNativeModel(reembeddedFile, preserveTransforms = true)  // Skip identity reset

// prepareSlicer() will apply savedScale, savedRotation, savedPositions
```

The `preserveTransforms` flag prevents `loadNativeModel` from resetting `_modelScale` and `_modelRotation` to identity. This is only used in the re-embed path — initial loads and plate switches still reset.

#### Fix #4: Calicube — position shift

**Problem:** Kotlin's `computeExpectedFootprint` uses one origin convention; native instances use another. 19mm systematic offset.

**Fix:** Stop computing expected footprint in Kotlin. Read it from native after `setModelInstances`:

```kotlin
// In prepareSlicer(), after setModelInstances:
val actualOffsets = native.getInstanceOffsets()
// Use actualOffsets for any position validation, not a Kotlin-computed expectation
```

The `expectedModelFootprint` in diagnostics becomes a read FROM native, not a Kotlin prediction. Any Kotlin-side position validation compares against native-reported positions.

### Layer 6: Test Harness

#### Tier A: Per-Fixture Regression Tests (immediate)

New instrumented test class: `BambuPlateStateTest.kt`

For each of the 6 bugs, a test that:
1. Loads the fixture via the production `selectPlate` path (not the test-only `extractPlateAndLoad` helper)
2. Reads state from native
3. Asserts the correct behaviour

```kotlin
@Test
fun dragon_scale_plate3_detects_three_extruders() {
    // Load Dragon Scale, select plate 3
    // Assert: usedExtruders.size >= 3
    // Assert: native volume map shows extruders {1, 2, 3}
}

@Test
fun f1_calendar_plate1_detects_four_extruders() {
    // REQUIRES: F1 calendar fixture added to androidTest/assets/
    // Load F1 calendar, select plate 1
    // Assert: usedExtruders.size >= 4
}

@Test
fun hanging_file_translate_preserved_through_slice() {
    // REQUIRES: Hanging file fixture added to androidTest/assets/
    // (or use any single-plate fixture — the bug is about translate-then-slice)
    // Load, apply translate, slice
    // Assert: G-code bounding box matches expected position
}

@Test
fun calicube_scaled_copies_position_matches_native() {
    // Load, scale to 2.5x, copy 7x, slice
    // Assert: G-code bounds match native-reported instance positions
}

@Test
fun h2c_benchy_all_colours_in_gcode() {
    // Load H2C benchy, slice
    // Assert: all expected tool changes present
}

@Test
fun buzz_cold_load_no_paint_scan_in_parse() {
    // Load Buzz, measure parse time
    // Assert: no computeVisualColorCountByPlate in parse() (structural)
    // Assert: parse completes in < 15s (loose perf gate)
}
```

#### Tier B: Data-Driven Test Harness (durable)

New instrumented test class: `BambuFixtureHarnessTest.kt`

**Spec format:** One JSON file per fixture in `app/src/androidTest/assets/fixture-specs/`:

```json
{
  "file": "Dragon Scale infinity.3mf",
  "approved": true,
  "plates": [
    {
      "plateIndex": 2,
      "expectedExtruderCount": 3,
      "expectedToolCounts": {"T0": 50, "T2": 53, "T3": 90},
      "toolCountTolerance": 5,
      "hasPaintData": false,
      "hasCompoundComponents": true,
      "maxBoundingBoxMm": [270, 270]
    }
  ]
}
```

**Bootstrap workflow:**
1. Run `BambuFixtureHarnessTest` with `generateSpec = true` (env flag or test parameter)
2. Test loads fixture, selects each plate, slices, records results
3. Writes draft JSON spec to device storage
4. Pull spec via `adb pull`, review, copy to `fixture-specs/` assets
5. Future runs assert against the approved spec

**Test runner:**
```kotlin
@Test
fun validate_all_approved_fixtures() {
    val specs = loadFixtureSpecs()  // Read all JSON specs from assets
    for (spec in specs.filter { it.approved }) {
        for (plate in spec.plates) {
            loadAndSelectPlate(spec.file, plate.plateIndex)
            val nativeState = readPlateStateFromNative()

            // Extruder count
            assertEquals(plate.expectedExtruderCount, nativeState.usedExtruders.size,
                "${spec.file} plate ${plate.plateIndex}: extruder count")

            // Tool counts (with tolerance)
            val result = slice()
            val toolCounts = parseToolCounts(result.gcodePath)
            for ((tool, expected) in plate.expectedToolCounts) {
                assertTrue(abs(toolCounts[tool] - expected) <= plate.toolCountTolerance,
                    "${spec.file} plate ${plate.plateIndex}: $tool expected ~$expected got ${toolCounts[tool]}")
            }

            // Bounding box
            val bounds = parseGcodeBounds(result.gcodePath)
            assertTrue(bounds.width <= plate.maxBoundingBoxMm[0],
                "${spec.file} plate ${plate.plateIndex}: width ${bounds.width} > ${plate.maxBoundingBoxMm[0]}")
        }
    }
}
```

**Adding a new fixture:**
1. Drop the `.3mf` into `app/src/androidTest/assets/`
2. Run harness in generate mode → draft spec created
3. Review spec, set `approved: true`
4. Commit both files
5. Future CI runs validate automatically

## What Gets Deleted

| Code | Reason |
|------|--------|
| `buildSelectedPlateInfo()` | Replaced by `readPlateStateFromNative()` |
| `computeVisualColorCountByPlate` call in `ThreeMfParser.parse()` | Eliminates B93 regression; paint state read from native on demand |
| `objectPartExtruders` field on `ThreeMfInfo` | Native provides per-volume extruders directly |
| `compoundPartParents` field on `ThreeMfInfo` | No longer needed for plate-scoped filtering |
| `ThreeMfPlate.paintExtruderStates` | Native provides paint state counts |
| Kotlin-side `computeExpectedFootprint` | Read from native instead |

**Not deleted** (still needed):
- `ThreeMfParser.parse()` — file-level metadata, plate list, plate names
- `ThreeMfPlate.hasPaintData` — still useful for UI hints during plate list display (before any plate is selected)
- `mergeThreeMfInfoForPlate` — still used for non-Bambu files (STL, single-plate)
- `ProfileEmbedder.embed(plateId)` — preprocessing stays
- `filterModelToPlate`, `stripUnreferencedResources` etc. — preprocessing stays
- `extractPlate` / `restructurePlateFile` — remain `@Deprecated(WARNING)` for regression test callers

## Scope Boundaries

**In scope:**
- New `nativeGetAllVolumeExtruders()` JNI accessor + native rebuild
- `readPlateStateFromNative()` Kotlin helper
- Revised `selectPlate` flow for multi-plate Bambu files
- Transform preservation in pre-slice re-embed path
- Position validation from native
- Tier A regression tests (6 tests for 6 bugs)
- Tier B data-driven harness + specs for all 22 fixtures
- Delete `buildSelectedPlateInfo` and related synthesis code

**Out of scope:**
- BBS submodule patches (keeping submodule clean for future upstream sync)
- `extractPlate` / `restructurePlateFile` test caller migration (separate cleanup)
- `LayerToolPauseInjector` XML fallback retirement
- Non-Bambu file paths (STL, single-plate 3MF — unchanged)

## Risk Assessment

| Risk | Mitigation |
|------|------------|
| Native accessor returns incomplete data for some file variant | Tier B harness catches it on first test run; fallback to file-level `_fileThreeMfInfo` |
| Pre-slice re-embed transform preservation breaks initial load | `preserveTransforms` flag only active in re-embed path; initial loads unchanged |
| `computeVisualColorCountByPlate` removal breaks plate list display | `ThreeMfPlate.hasPaintData` from file-level parse is kept; only the expensive per-plate extruder scan is removed |
| Native rebuild introduces unrelated regression | Verify: NDK 26, Clang 17, Release, 20-21 MB stripped, full test sweep |
| F1 calendar and Hanging file fixtures not in test assets | PM provides the files; Tier A tests for those bugs use any suitable substitute fixture if originals unavailable (translate bug is file-agnostic, F1 calendar bug may reproduce on any 4-extruder compound 3MF) |

## Success Criteria

1. All 6 PM-reported bugs fixed (verified by Tier A tests)
2. Tier B harness passes for all 22 fixtures with approved specs
3. Buzz cold load time returns to v1.6.13 baseline (< 45s on Pixel 8a)
4. Diff harness baseline remains at 0 entries
5. No regression in existing 844 JVM + 212 instrumented tests
6. New Bambu files can be validated by dropping file + JSON spec — no code changes needed
