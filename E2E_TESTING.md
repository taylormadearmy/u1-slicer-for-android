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
| Shashibo-h2s-textured.3mf | 3MF | Yes (old fmt) | 2 | ✓ | ✓ | NOT TESTED |
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

- JVM unit tests: 235 / 235 passing (as of last run)
- Instrumented tests: pending run on device

## Automated Test Results

### Run 1: 2026-03-08
- JVM unit tests: **235/235 PASS**
- Instrumented tests: **91/91 PASS** (includes 2 new regression tests)
  - `coloredBenchy_printableZeroStripped_slicesWithoutCoordinateError` ✓
  - `fidgetCoaster_multiPlateWithoutPlateIds_extractsOneItemAtBedCentre` ✓

## Manual E2E Status

Test files pushed to device at `/sdcard/Download/`
App installed and running.

To monitor logs during testing:
```
adb -s 43211JEKB16931 logcat -s "SlicerVM,BambuSanitizer,ThreeMfParser"
```

### Shashibo-h2s-textured.3mf
- Multi-plate (old format, no plate JSONs, virtual positions)
- Expected: plate selector appears → model loads → drag works → slices to completion
- Status: **NOT YET TESTED manually**

### Dragon Scale infinity.3mf
- Multi-plate (old format, virtual positions)
- Expected: plate selector → load → drag → slice
- Status: **NOT YET TESTED manually**

### colored_3DBenchy (1).3mf
- Single-plate, printable="0" item stripped
- Expected: loads directly → drag → slice (no Clipper error)
- Status: **NOT YET TESTED manually**

### foldy+coaster (1).3mf
- Multi-plate new format, no p:object_id markers, position-based selection
- Expected: plate selector → load → drag → slice
- Status: **NOT YET TESTED manually**

### calib-cube-10-dual-colour-merged.3mf
- Single plate, 2 colours
- Status: **NOT YET TESTED manually**

### PrusaSlicer-printables-Korok_mask_4colour.3mf
- Single plate, 4 colours
- Status: **NOT YET TESTED manually**

### Button-for-S-trousers.3mf
- Single plate, 1 colour
- Status: **NOT YET TESTED manually**

### u1-auxiliary-fan-cover-hex_mw.3mf
- Single plate, 1 colour
- Status: **NOT YET TESTED manually**
