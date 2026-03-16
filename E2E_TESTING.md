# U1 Slicer — E2E Testing Progress

**Device**: 43211JEKB16931 (Pixel 8a, Android 14)
**Branch**: main (uncommitted changes — BambuSanitizer/ThreeMfParser multi-plate fixes)

## What was fixed in this session

| Fix | File | Why |
|-----|------|-----|
| Multi-plate detection: use plate JSON count + virtual-plate positions (not build item count) | ThreeMfParser.kt | Old format (Dragon Scale, Shashibo) had no plate JSONs; Benchy had printable="0" items causing false positive |
| `hasPlateJsons` field added to ThreeMfInfo | ThreeMfInfo.kt | Needed to pass correct flag to extractPlate() after sanitization strips plate JSONs |
| `extractPlate()` accepts explicit `hasPlateJsons` param | BambuSanitizer.kt | process() strips plate_N.json; auto-detect on sanitised file always returned false |
| `stripAssembleSection()` in `extractPlate()` | BambuSanitizer.kt | model_settings.config had assemble_items for all plates → OrcaSlicer rejected single-plate file |
| `stripNonPrintableBuildItems()` called in `process()` | BambuSanitizer.kt | Benchy with printable="0" items caused "Coordinate outside allowed range" in Clipper |
| Multi-color detection ignores extruders > 4 | BambuSanitizer.kt | Paint color markers use indices 5+ → falsely triggered restructuring → OOM |
| OOM guard: skip restructuring if component files > 15 MB | BambuSanitizer.kt | Large multi-plate Bambu 3MFs exhausted Android heap |
| Per-part volume entries in Slic3rModelConfig for non-restructured multi-color | BambuSanitizer.kt | Color assignments lost when restructuring was skipped |
| `normalizePerFilamentArrays` always called (even for 1 extruder) | ProfileEmbedder.kt | 7-plate Bambu file embedded 7 filament entries → ThreeMfParser counted 7 extruders |
| `cleanModelXmlForOrcaSlicer()` applied to all .model files | ProfileEmbedder.kt | BambuStudio requiredextensions="p" caused OrcaSlicer rejection |
| `selectPlate()` updates `_threeMfInfo` with single-plate info | SlicerViewModel.kt | Full multi-plate info (7 extruders) was used after single-plate selection |
| `startSlicing()` wrapped in try/catch | SlicerViewModel.kt | Unhandled exceptions left UI stuck at 100% |
| Thumbnail injection non-fatal try/catch | SlicerViewModel.kt | Crash in thumbnail injection lost slice result |

## Test Files Matrix

| File | Type | Multi-plate | Colors | Move | Slice | Status |
|------|------|-------------|--------|------|-------|--------|
| 3DBenchy.stl | STL | No | 1 | — | — | NOT TESTED |
| tetrahedron.stl | STL | No | 1 | — | — | NOT TESTED |
| calib-cube-10-dual-colour-merged.3mf | 3MF | No | 2 | ✓ | ✓ | NOT TESTED |
| Dragon Scale infinity.3mf | 3MF | Yes (old fmt) | 2 | ✓ | ✓ | NOT TESTED |
| Dragon Scale infinity-1-plate-2-colours.3mf | 3MF | No | 2 | ✓ | ✓ | NOT TESTED |
| Dragon Scale infinity-1-plate-2-colours-new-plate.3mf | 3MF | No | 2 | ✓ | ✓ | NOT TESTED |
| Shashibo-h2s-textured.3mf | 3MF | Yes (old fmt) | 2 | ✓ | ✓ | PASS (user confirmed) |
| PrusaSlicer-printables-Korok_mask_4colour.3mf | 3MF | No | 4 | ✓ | ✓ | NOT TESTED |
| colored_3DBenchy (1).3mf | 3MF | No? | 1+ | ✓ | ✓ | NOT TESTED |
| foldy+coaster (1).3mf | 3MF | Yes (new fmt) | 1+ | ✓ | ✓ | NOT TESTED |
| Button-for-S-trousers.3mf | 3MF | ? | 1 | ✓ | ✓ | NOT TESTED |
| u1-auxiliary-fan-cover-hex_mw.3mf | 3MF | No | 1 | ✓ | ✓ | NOT TESTED |

## Test Protocol per file

1. Load file in app
2. If multi-plate: plate selector appears → select a plate → model loads correctly on bed
3. Drag model to a new position on bed (not stuck in corner)
4. Hit Slice → progress bar moves → completes (not stuck at 100%)
5. Check logcat for exceptions: `adb -s 43211JEKB16931 logcat -s "SlicerVM" -d`
6. Mark ✓ or note failure

## Unit Test Status

- JVM unit tests: 375 / 375 passing
- Instrumented tests: 109 / 109 passing (see Run 2 below)

## Automated Test Results

### Run 2: 2026-03-08
- JVM unit tests: **235/235 PASS**
- Instrumented tests: **93/93 PASS** (2 new E2E pipeline tests added)
  - `coloredBenchy_printableZeroStripped_slicesWithoutCoordinateError` ✓
  - `fidgetCoaster_multiPlateWithoutPlateIds_extractsOneItemAtBedCentre` ✓
  - `shashibo_plate1_slicesSuccessfully` ✓  ← NEW: full process→extract→embed→slice
  - `dragonScale_fullViewModelPipeline_slicesSuccessfully` ✓  ← NEW: full ViewModel pipeline

## Manual E2E Status

Test files pushed to device at `/sdcard/Download/`
App installed and running.

To monitor logs during testing:
```
adb -s 43211JEKB16931 logcat -s "SlicerVM,BambuSanitizer,ThreeMfParser"
```

## What Automated Tests Cover Per File

| File | Automated Coverage | Manual needed |
|------|-------------------|---------------|
| Shashibo-h2s-textured.3mf | `shashibo_loadsAfterExtractAndEmbed` + `shashibo_plate1_slicesSuccessfully` — full process→extract→embed→load→slice ✓ | Plate selector UI, drag |
| Dragon Scale infinity.3mf | `dragonScale_detectedAsMultiPlate` + `dragonScale_plate1_loadsAndSlices` + `dragonScale_fullViewModelPipeline_slicesSuccessfully` ✓ | Plate selector UI, drag |
| colored_3DBenchy (1).3mf | `coloredBenchy_printableZeroStripped_slicesWithoutCoordinateError` — printable=0 stripped, slices ✓ | Drag |
| foldy+coaster (1).3mf | `fidgetCoaster_multiPlateWithoutPlateIds_extractsOneItemAtBedCentre` — position-based, at bed centre, slices ✓ | Plate selector UI, drag |
| calib-cube-10-dual-colour-merged.3mf | Multiple dual-colour tests including temps, prime tower, retraction ✓ | Drag |
| PrusaSlicer-printables-Korok_mask_4colour.3mf | `korokMask_fourColour_slicesWithoutFlowError` ✓ | Drag |
| Button-for-S-trousers.3mf | `buttonTrousers_fullPipeline_slicesSuccessfully` ✓ | Drag |
| u1-auxiliary-fan-cover-hex_mw.3mf | `fanCover_fullPipeline_slicesSuccessfully` + `fanCover_fullPipeline_gcodeHasNonZeroTemps` ✓ | Drag |

## Manual E2E Still Required

The Google Drive file picker used by the app renders in custom views inaccessible to UIAutomator — automated ADB tap-based testing of the file picker is not feasible.

**What needs manual verification on device (files in /sdcard/Download/):**
1. Load each file via Browse Files
2. Multi-plate files: **plate selector dialog appears** with correct plate count
3. **Model appears on bed** (not stuck at corner/off-screen)
4. **Drag model** — moves freely, stays within bed bounds
5. **Slice completes** — progress bar reaches 100% and shows result (not stuck)

**User confirmed working:** Shashibo (from session start) — loads, drag works, slices.
