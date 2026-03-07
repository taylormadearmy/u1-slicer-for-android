# U1 Slicer — UX Improvement Plan

> **Status: Planning only — do not implement until bug-fix agent completes.**
> Created: 2026-03-06

---

## Overview

15 UX/feature improvements across viewer, settings, printer controls, and navigation.
Grouped into themes below with implementation notes and test plan for each.

---

## Feature Groups

### Group A — 3D Viewer Overhaul (items 1, 2, 3, 4, 9)
### Group B — Slice Settings (items 5, 6)
### Group C — Filament & UI Polish (items 7, 10, 12, 13)
### Group D — Printer & Hardware (items 8, 11, 13b, 14)
### Group E — App Structure (items 12, 13, 14, 15)

---

## Item 1 — Fix 3D viewer default camera angle

**Problem:** Default camera angle is too shallow — model appears side-on. Two separate root causes confirmed by code inspection.

**Root cause 1 — elevation too low:**
- `ModelRenderer.kt:82` sets `elevation = 30f` → `sin(30°) = 0.5` — camera only half a distance-unit above the bed plane
- `GcodeRenderer.kt:86,108` sets `elevation = 35f` → `sin(35°) = 0.574` — slightly better but still too low
- At these angles the model height (Z axis) appears compressed and the view looks side-on

**Root cause 2 — wrong camera target for model viewer:**
- `ModelRenderer.kt:76` targets the **bed centre** `(bedSize/2, bedSize/2)` = (135, 135) regardless of where the model is
- Most models sit near the origin (0,0 corner after OrcaSlicer placement), so the camera points mostly at empty bed space and the model appears in the corner/periphery
- `GcodeRenderer.kt:106` also uses fixed `(135f, 135f)` target — same issue

**Fix:**
- Raise default elevation to `45f` in both `ModelRenderer` and `GcodeRenderer`
- Keep azimuth at `-45f` (true isometric diagonal — no change needed)
- **Model viewer**: target `(mesh.centerX, mesh.centerY, mesh.sizeZ / 2)` (actual model centre) instead of bed centre. Keep bed-centre as fallback only when model is larger than 1.5× bed size
- **G-code viewer**: target `(gcodeMinX + gcodeWidth/2, gcodeMinY + gcodeHeight/2, maxZ/2)` — the toolpath bounding box centre. If unavailable, fall back to `(135, 135, maxZ/2)`

**Files:** `viewer/ModelRenderer.kt`, `viewer/GcodeRenderer.kt`

**Tests:**
- Existing `StlParserTest` and `NativeLibrarySymbolTest` still pass (no logic change)
- Manual E2E: load test_cube.stl → model should appear at a clear top-front-left angle showing all 3 faces

---

## Item 2 — Show actual model in pre-slice viewer (not placeholder)

**Problem:** For 3MF files, the inline `InlineModelPreview` may show nothing or a placeholder because `ThreeMfMeshParser` is not always triggered.

**Current state:** `MainActivity.kt:349-369` — `InlineModelPreview` is only shown when `modelPath != null && (endsWith .stl || .3mf)`. The viewer calls `ModelViewerView.setMesh()` which internally uses `StlParser` or `ThreeMfMeshParser`. Need to verify 3MF path loads correctly.

**Fix:**
- Audit `InlineModelPreview` composable (below line 700 in MainActivity.kt) — ensure it branches on file extension and calls `ThreeMfMeshParser` for 3MF files
- If `ThreeMfMeshParser` fails to extract mesh (e.g. multi-body with no primary), fall back to bounding-box placeholder cube (already in `setupBox()`)
- Add a loading spinner overlay while mesh is being parsed on background thread

**Files:** `MainActivity.kt` (InlineModelPreview composable), `viewer/ThreeMfMeshParser.kt`, `viewer/ModelViewerView.kt`

**Tests:**
- Unit: `StlParserTest` already covers STL; add test to `ThreeMfMeshParser` (or a new `ThreeMfMeshParserTest`) that confirms mesh vertices are non-empty for a known 3MF
- E2E: load calib-cube 3MF → orange mesh should appear in inline viewer

---

## Item 3 — Redesign pre-slice viewer controls & position

**Problem:** Viewer is buried below info cards mid-page. Touch controls don't match the requested interaction model.

**Requested UX:**
- Move viewer to **top of page** (first element after top bar in `ModelLoaded` state)
- Viewer fills most of the screen (e.g. `fillMaxHeight(0.55f)`)
- **Single finger** → rotate bed/model in all dimensions (orbit camera) — *already the default non-placement mode*
- **Two fingers** → pan (already implemented) and pinch-to-zoom (already implemented via `ScaleGestureDetector`)
- **Tap on model** → "freeze view" and enter placement/move mode for that object
- **Tap off model** → deselect, return to orbit/pan/zoom mode

**Implementation notes:**
- The current `ModelViewerView` already has `placementMode: Boolean`. The new design integrates placement into the same view — placement is triggered by a tap-hit-test rather than an external toggle.
- Add a `GestureDetector` (single-tap) alongside the existing `ScaleGestureDetector`. On tap-up (not drag), do a hit test: if hit → enter placement for that object index; if miss → clear selection.
- Add a visual indicator (e.g. small "Move mode" chip overlay) when an object is selected.
- The existing 2D `PlacementViewerScreen` can be retired or kept as a fallback — the 3D inline viewer replaces its function.
- Remove `InlineModelPreview` card padding and constraints that limit its height.

**Files:** `viewer/ModelViewerView.kt`, `MainActivity.kt` (SlicerScreen ModelLoaded branch, InlineModelPreview composable)

**Tests:**
- Instrumented: `SlicingIntegrationTest` — verify model loads and viewer doesn't crash
- E2E: tap model → chip appears; drag → model moves; tap empty space → chip gone, swipe rotates

---

## Item 4 — Model info behind (i) icon

**Problem:** `ModelInfoCard`, `BambuInfoCard`, and `MultiColorInfoCard` take vertical space at the top of the slicer page before the viewer.

**Fix:**
- Remove these cards from the inline flow
- Add a small `(i)` `IconButton` overlaid on the top-right corner of the viewer
- Tapping it opens an `AlertDialog` or `ModalBottomSheet` with all three cards' content consolidated

**Files:** `MainActivity.kt` (SlicerScreen ModelLoaded branch, ModelInfoCard, BambuInfoCard, MultiColorInfoCard)

**Tests:**
- E2E: tap (i) → sheet appears with filename, dimensions, extruder count etc; dismiss → sheet gone

---

## Item 9 — Replace G-code text preview with 3D view (post-slice)

**Problem:** After slicing, `SliceCompleteCard` shows a `GcodePreviewCard` which is raw G-code text. The 3D G-code viewer is available but only via a separate button.

**Fix:**
- In `SlicerState.SliceComplete`: embed an `InlineGcodeViewer` composable (similar to `InlineModelPreview` but using `GcodeViewerView`) showing the full 3D G-code view directly on the result page
- Remove the `GcodePreviewCard` (text preview)
- Keep the "3D View" button for full-screen navigation
- The inline viewer should show all layers by default (Layer slider at max)
- The "3D View" full-screen button becomes "Expand" on the inline viewer

**Files:** `MainActivity.kt` (SliceCompleteCard, GcodePreviewCard), possibly new `InlineGcodePreview` composable

**Tests:**
- E2E: slice test_cube.stl → 3D G-code layers visible inline; tap "Expand" → full screen GcodeViewer3DScreen

---

## Item 5 — Prime tower overrides on slicer page

**Problem:** No UI for prime tower fine-tuning. Bridge has: Prime Volume, Tower Width, Tower Brim Width, Brim Chamfer, Chamfer Max Width.

**OrcaSlicer config keys and their delivery path (confirmed by code analysis):**

| Setting | Key | Path | Notes |
|---------|-----|------|-------|
| Tower Width | `prime_tower_width` | JNI `SliceConfig.wipeTowerWidth` | Already works |
| Enable | `enable_prime_tower` | JNI `SliceConfig.wipeTowerEnabled` | Already works |
| Position | `wipe_tower_x/y` | JNI `SliceConfig.wipeTowerX/Y` | Already works |
| Prime Volume | `prime_volume` | ProfileEmbedder JSON | In `CLAMP_INT_RULES`, NOT in `sapil_print.cpp` ✓ |
| Brim Width | `prime_tower_brim_width` | ProfileEmbedder JSON | In `CLAMP_INT_RULES`, used by `sanitizeWipeTowerPosition()` ✓ |
| Brim Chamfer | `prime_tower_brim_chamfer` | ProfileEmbedder JSON | In `CLAMP_INT_RULES`, NOT in `sapil_print.cpp` ✓ |
| Chamfer Max Width | `prime_tower_brim_chamfer_max_width` | ProfileEmbedder JSON | In `CLAMP_INT_RULES`, NOT in `sapil_print.cpp` ✓ |

**No JNI rebuild required. No new `SliceConfig` fields needed.**

**Implementation notes:**
- Add 4 UI-only state fields to a new `PrimeTowerUiState` (or directly as `SlicerViewModel` `StateFlow`s): `primeTowerVolume`, `primeTowerBrimWidth`, `primeTowerChamfer`, `primeTowerChamferMaxWidth`
- In `SlicerViewModel.startSlicing()`, pass these as additional overrides to `ProfileEmbedder.buildConfig(overrides = ...)` before embedding
- Add a `PrimeTowerOverridesCard` composable shown in `ModelLoaded` state when `config.wipeTowerEnabled == true`

**Files:** `SlicerViewModel.kt`, `bambu/ProfileEmbedder.kt` (no changes needed — already handles these keys), `MainActivity.kt` (new PrimeTowerOverridesCard)

**Tests:**
- Unit: `SliceConfigTest` — no new fields (keys go through ProfileEmbedder, not SliceConfig)
- Unit: `ProfileEmbedderIntegrationTest` (instrumented) — verify prime tower keys appear in embedded config JSON
- E2E: enable wipe tower → prime tower card visible; change volume → slice → verify G-code has updated prime tower

---

## Item 6 — Slicer overrides in Settings page

**Problem:** Settings page only has temperature, speed, retraction. No per-job override system. Bridge has a full override system: Use File / Orca (default) / Override with value for each setting.

**New `SlicingOverrides` data class:**
```kotlin
data class OverrideValue<T>(
    val mode: OverrideMode,  // USE_FILE, ORCA_DEFAULT, OVERRIDE
    val value: T? = null
)
enum class OverrideMode { USE_FILE, ORCA_DEFAULT, OVERRIDE }

data class SlicingOverrides(
    val layerHeight: OverrideValue<Float> = OverrideValue(USE_FILE),
    val infillDensity: OverrideValue<Float> = OverrideValue(USE_FILE),
    val wallCount: OverrideValue<Int> = OverrideValue(USE_FILE),
    val infillPattern: OverrideValue<String> = OverrideValue(USE_FILE),
    val supports: OverrideValue<Boolean> = OverrideValue(USE_FILE),
    val supportType: OverrideValue<String> = OverrideValue(USE_FILE),
    val brimType: OverrideValue<String> = OverrideValue(USE_FILE),
    val brimWidth: OverrideValue<Float> = OverrideValue(USE_FILE),
    val brimGap: OverrideValue<Float> = OverrideValue(USE_FILE),
    val skirtLoops: OverrideValue<Int> = OverrideValue(USE_FILE),
    val skirtDistance: OverrideValue<Float> = OverrideValue(USE_FILE),
    val skirtHeight: OverrideValue<Int> = OverrideValue(USE_FILE),
    val bedTemp: OverrideValue<Int> = OverrideValue(USE_FILE),
    val buildPlate: OverrideValue<String> = OverrideValue(USE_FILE),
    val primeTower: OverrideValue<Boolean> = OverrideValue(USE_FILE),
    val flowCalibration: Boolean = true  // checkbox, always-on option
)
```

**UI:** New `SlicingOverridesSection` in `SettingsScreen`. Each row shows:
- Setting name (left)
- `Use File | Orca | Override` segmented button (right)
- When Override: value picker appears inline (dropdown for enums, text field for numerics)
- Orca column shows the Orca default hint text (greyed)

**Persistence:** Serialised to JSON in `DataStore` via `SettingsRepository`.

**Application:** In `SlicerViewModel.startSlicing()` — before calling native slice, apply active overrides to `SliceConfig` and `ProfileEmbedder` JSON. `USE_FILE` = leave the 3MF value as-is. `ORCA_DEFAULT` = set key to OrcaSlicer's factory default. `OVERRIDE` = use the user value.

**Files:**
- New: `data/SlicingOverrides.kt`
- `data/SettingsRepository.kt` — add overrides DataStore persistence
- `ui/SettingsScreen.kt` — add `SlicingOverridesSection`
- `SlicerViewModel.kt` — apply overrides in `startSlicing()`
- `bambu/ProfileEmbedder.kt` — accept override map

**Tests:**
- Unit: new `SlicingOverridesTest.kt` — default values, serialization round-trip
- Unit: `SliceConfigTest` — verify overrides applied correctly
- E2E: set Infill Density → Override → 30%; slice cube → G-code should reflect 30% infill

---

## Item 7 — Fix filament assignment dialog text contrast

**Problem:** `MultiColorDialog` (`ui/MultiColorDialog.kt`) shows colour swatches with text that may be unreadable against coloured backgrounds (e.g. red swatch with red text).

**Fix:**
- In the extruder dropdown rows: remove or override any `color` tinting on the label `Text`
- Ensure text always uses `MaterialTheme.colorScheme.onSurface` or `onBackground`
- The colour dot should be separate from the text label — don't inherit its colour
- Review `MultiColorDialog.kt` specifically the `ExtruderDropdown` or row composable

**Files:** `ui/MultiColorDialog.kt`

**Tests:**
- E2E: load dual-colour 3MF → dialog shows → all text readable regardless of swatch colour

---

## Item 8 — Printer light control

**Problem:** No UI to toggle the printer's LED light.

**Confirmed via live Moonraker query (`/printer/objects/list` on 192.168.0.151):**
- LED object name: `led cavity_led`
- Type: RGBW (4-channel: R, G, B, W)
- Current state: `color_data: [[0.0, 0.0, 0.0, 1.0]]` — meaning WHITE=1.0 (full on), RGB=0

**API:** `POST /printer/gcode/script` body `{"script": "SET_LED LED=cavity_led WHITE=1.0 RED=0 GREEN=0 BLUE=0"}` for on; `WHITE=0` for off.

**Getting current state:** Query `/printer/objects/query?led cavity_led` → parse `color_data[0][3]` (W channel). This lets us show correct initial toggle state.

**Implementation:**
- Add `MoonrakerClient.getLedState(): Boolean` — queries `led cavity_led`, returns `color_data[0][3] > 0`
- Add `MoonrakerClient.setLed(on: Boolean): Boolean` — posts gcode script `SET_LED LED=cavity_led WHITE={1.0 or 0.0} RED=0 GREEN=0 BLUE=0`
- Expose `PrinterViewModel.toggleLight()` and `isLightOn: StateFlow<Boolean?>` (null = unknown/not connected)
- Poll LED state alongside printer status (add to existing status poll loop)
- Add lightbulb `IconToggleButton` to `PrinterScreen` top bar, visible when connected
- Also show in `PrintMonitorScreen` top bar

**Files:** `network/MoonrakerClient.kt`, `printer/PrinterViewModel.kt`, `ui/PrinterScreen.kt`, `ui/PrintMonitorScreen.kt`

**Tests:**
- Unit: `MoonrakerClientTest` — mock test for LED query (parse `color_data`) and set (POST gcode script)
- E2E: tap light icon → light toggles; icon state reflects actual state on next poll

---

## Item 10 — JSON filament import (already partially done)

**Status:** Already implemented. `FilamentScreen.kt` has `importLauncher` via `GetContent()` that calls `parseFilamentJson()`. The button may just need to be more prominent.

**Fix:**
- Verify the import button is visible and accessible on the `FilamentScreen` (check if it's in the top-bar actions or buried)
- If missing from the nav-bar accessible Filaments section after restructure (item 12), ensure it's present in the new location
- No new logic needed

**Files:** `ui/FilamentScreen.kt` — verify import button placement

**Tests:** Already covered by `FilamentJsonImportTest.kt` (11 tests). E2E: import Bambu PLA JSON → profiles added.

---

## Item 11 — Send print thumbnail to printer

**Problem:** No thumbnail sent to printer. The bridge already solves this — we port the approach to Android.

**How it works (confirmed from `gcode_thumbnails.py` in u1-slicer-bridge):**
- Thumbnails are base64-encoded PNG blocks injected into the G-code file header
- Format: `; thumbnail begin WxH LEN\n; <base64, 76 chars/line>\n; thumbnail end`
- Sizes: **48×48** and **300×300** (matches Klipper's expected sizes)
- Source image: extracted from the `.3mf` file's `metadata/` folder (looks for `thumbnail`, `preview`, `cover`, `top`, `plate`, `pick` keywords in filename)
- Injected after the `HEADER_BLOCK_END` comment in the G-code, or prepended if not found
- Moonraker reads these blocks from uploaded G-code and serves them via `/server/files/thumbnails`

**Android implementation plan:**
1. New `GcodeThumbnailInjector.kt` class (ports bridge's `gcode_thumbnails.py`)
2. For **3MF files**: open the source `.3mf` ZIP, scan `metadata/` for best preview image (same scoring: thumbnail > preview > cover > top > plate > pick), read bytes, decode with `BitmapFactory`
3. For **STL files**: no embedded image — capture a screenshot of the `ModelViewerView` OpenGL surface using `PixelCopy` API (API 26+), use that as the preview
4. Resize to 48×48 and 300×300 using `Bitmap.createScaledBitmap`, encode as PNG via `Bitmap.compress`, base64-encode, build comment block
5. Inject into G-code after `HEADER_BLOCK_END` (or prepend)
6. Call from `SlicerViewModel` after slice completes, before upload: `GcodeThumbnailInjector.inject(gcodePath, sourcePath)`
7. `PrintMonitorScreen` already fetches webcam — add thumbnail fetch from `/server/files/thumbnails?filename=<name>` and display it

**Files:** New `gcode/GcodeThumbnailInjector.kt`, `SlicerViewModel.kt`, `viewer/ModelRenderer.kt` (PixelCopy helper), `ui/PrintMonitorScreen.kt`, `network/MoonrakerClient.kt`

**Tests:**
- Unit: `GcodeThumbnailInjectorTest.kt` — test with a real 3MF asset containing metadata images; verify output G-code contains `; thumbnail begin 48x48` and `300x300` blocks
- E2E: slice 3MF → send to printer → PrintMonitorScreen shows thumbnail

---

## Item 12 — Move filament profiles to Settings

**Problem:** Filaments is a bottom-nav tab. After restructure it moves into Settings as a section.

**Fix:**
- Keep `FilamentScreen.kt` composable as-is
- In `SettingsScreen.kt`: add a `FilamentsSection` row that navigates to `FilamentScreen` (or embeds it inline as a sub-screen)
- Remove "Filaments" from bottom nav bar
- Add "Filaments" entry to Settings page list

**Navigation impact:** `Routes.FILAMENTS` still works, just not in the nav bar.

**Files:** `ui/SettingsScreen.kt`, `MainActivity.kt` (nav bar), `navigation/NavGraph.kt`

**Tests:**
- E2E: tap Settings → tap Filaments → filament list loads

---

## Item 13 — Rename "Filaments" nav to "Sync Filaments" + move sync button

**Problem:** The bottom nav "Filaments" tab currently shows filament profiles. After item 12, this tab is freed. The sync-filaments workflow currently lives in `PrinterScreen`. The "Sync" button in `PrinterScreen` calls `viewModel.syncFilaments()`.

**Proposed layout after changes:**
- Bottom nav bar: **Slicer | Printer | Jobs | Settings** (4 items — Filaments removed from nav)
- The "Sync Filaments" action moves to Settings → Filaments section (a "Sync from Printer" button at the top of the FilamentScreen)
- The `PrinterScreen` extruder-slots card removes its own "Sync" button (it moves to FilamentScreen)

**Files:** `ui/PrinterScreen.kt`, `ui/FilamentScreen.kt`, `MainActivity.kt` (nav bar)

**Tests:**
- E2E: Settings → Filaments → "Sync from Printer" tapped → sync dialog appears

---

## Item 13b — Printer tab shows live state + camera

**Problem:** The "Printer" nav tab goes to `PrinterScreen` which shows config/settings. The live monitoring view (`PrintMonitorScreen`) is only reachable after a "Send to printer" action.

**Fix:**
- Merge `PrinterScreen` and `PrintMonitorScreen` into a single screen
- When printer is connected: top section = live status + webcam feed (from PrintMonitorScreen); bottom = controls
- When printing: full PrintMonitorScreen layout with webcam, progress, controls
- When not connected: show connection setup (URL field + connect button) — moved from Settings (see item 14)

**Approach:**
- `PrinterScreen.kt` absorbs `PrintMonitorScreen`'s webcam + progress content
- `PrintMonitorScreen.kt` can be retired (its content merged into `PrinterScreen`)
- The "Send to Printer" flow navigates to the `Routes.PRINTER` tab rather than `Routes.PRINT_MONITOR`

**Files:** `ui/PrinterScreen.kt`, `ui/PrintMonitorScreen.kt`, `navigation/NavGraph.kt`, `MainActivity.kt`

**Tests:**
- E2E: navigate to Printer tab while printing → see webcam + progress; when idle → see status

---

## Item 14 — Move Moonraker settings to Settings page

**Problem:** URL input and connection test live in `PrinterScreen`. After item 13b rework, settings go to Settings.

**Fix:**
- In `SettingsScreen.kt`: add a "Printer Connection" section with URL field and Connect button (cut from `PrinterScreen`)
- `PrinterScreen` becomes purely a monitoring/control screen with no URL editing

**Files:** `ui/PrinterScreen.kt`, `ui/SettingsScreen.kt`

**Tests:**
- E2E: Settings → Printer Connection → enter URL → Connect → connected indicator shown

---

## Item 15 — Backup and restore settings

**Problem:** No way to export/import app settings. Bridge uses a JSON file format for its settings.

**Bridge format reference:** The bridge stores settings as a flat JSON file with keys matching its config fields. We should use a similar schema for cross-compatibility where possible.

**Scope of backup:**
- `SliceConfig` (all fields)
- `SlicingOverrides` (new, item 6)
- Printer URL
- `ExtruderPreset` list (4 slots: color + materialType)
- `FilamentProfile` list (all Room DB rows)

**Export:** `FileProvider` + `Intent.ACTION_CREATE_DOCUMENT` → write JSON
**Import:** `GetContent` → read JSON → validate → write back to DataStore + Room DB

**JSON schema (top-level keys):**
```json
{
  "version": 1,
  "sliceConfig": { ... },
  "slicingOverrides": { ... },
  "printerUrl": "http://...",
  "extruderPresets": [ { "slot": 1, "color": "#FF0000", "materialType": "PLA" }, ... ],
  "filamentProfiles": [ { ... }, ... ]
}
```

**Files:** New `data/SettingsBackup.kt`, `ui/SettingsScreen.kt`, `data/SettingsRepository.kt`, `SlicerViewModel.kt`

**Tests:**
- Unit: `SettingsBackupTest` — round-trip serialize/deserialize, version field, partial restore
- E2E: export → delete profiles → import → profiles restored

---

## Implementation Order & Dependencies

```
Phase 1 (independent, low risk):
  1  Camera default angle fix
  7  Multi-color dialog text contrast
  10 JSON import button verification

Phase 2 (viewer rebuild):
  2  Actual mesh in pre-slice viewer
  3  Viewer controls + position
  4  Info behind (i) icon
  9  3D G-code view after slice

Phase 3 (settings expansion):
  5  Prime tower overrides
  6  Slicer overrides in settings
  14 Moonraker settings to settings page
  15 Backup/restore

Phase 4 (nav restructure):
  12 Filaments → Settings section
  13 Sync filaments move + nav cleanup
  13b Printer tab = live state

Phase 5 (hardware):
  8  Printer light control
  11 Print thumbnail
```

---

## Files Changed Summary

| File | Items |
|------|-------|
| `viewer/Camera.kt` | 1 |
| `viewer/ModelRenderer.kt` | 1, 2 |
| `viewer/ModelViewerView.kt` | 3 |
| `viewer/GcodeRenderer.kt` | 1 |
| `MainActivity.kt` | 3, 4, 9, 12, 13 |
| `ui/MultiColorDialog.kt` | 7 |
| `ui/SettingsScreen.kt` | 6, 12, 14, 15 |
| `ui/PrinterScreen.kt` | 8, 13, 13b, 14 |
| `ui/PrintMonitorScreen.kt` | 11, 13b |
| `ui/FilamentScreen.kt` | 10, 13 |
| `data/SliceConfig.kt` | 5 |
| `data/SlicingOverrides.kt` | 6 (new file) |
| `data/SettingsBackup.kt` | 15 (new file) |
| `data/SettingsRepository.kt` | 6, 15 |
| `bambu/ProfileEmbedder.kt` | 5, 6 |
| `network/MoonrakerClient.kt` | 8, 11 |
| `printer/PrinterViewModel.kt` | 8, 13b |
| `SlicerViewModel.kt` | 6, 11 |
| `navigation/NavGraph.kt` | 12, 13, 13b |

---

## Test Plan Summary

### New unit test files needed
- `data/SlicingOverridesTest.kt` — default values, serialization round-trip (item 6)
- `data/SettingsBackupTest.kt` — round-trip export/import, version field (item 15)
- `viewer/ThreeMfMeshParserTest.kt` — mesh vertex extraction from known 3MF (item 2) [JVM unit test]
- `gcode/GcodeThumbnailInjectorTest.kt` — 3MF image extraction, 48×48 + 300×300 blocks, injection after HEADER_BLOCK_END, STL fallback (item 11) [instrumented, needs Bitmap]

### Additions to existing test files
- `MoonrakerClientTest.kt` — LED state parse (`color_data[0][3]`), LED set gcode script (item 8)
- `ProfileEmbedderIntegrationTest.kt` — verify `prime_volume` / `prime_tower_brim_width` appear in embedded config JSON (item 5)
- `ProfileEmbedderIntegrationTest.kt` — override application (item 6) [prime tower already covered in line above]

### E2E checklist per phase (on device 43211JEKB00954)
Each phase: `ANDROID_SERIAL=43211JEKB00954 ./gradlew installDebug --no-daemon` then manual test per item.

---

## Open Questions

1. **Item 8 — Light control:** RESOLVED. Live Moonraker query confirmed: LED object is `led cavity_led`, RGBW (4-channel). Control: `SET_LED LED=cavity_led WHITE=1.0 RED=0 GREEN=0 BLUE=0`. Current state readable from `/printer/objects/query?led cavity_led` → `color_data[0][3] > 0`.

2. **Item 11 — Thumbnail:** RESOLVED. Port bridge's `gcode_thumbnails.py` to Kotlin (`GcodeThumbnailInjector.kt`). Extract from 3MF `metadata/` folder, resize to 48×48 and 300×300, inject as `; thumbnail begin` blocks after `HEADER_BLOCK_END`. For STL files (no embedded image): use `PixelCopy` to capture OpenGL viewer screenshot.

3. **Item 5 — JNI boundary:** RESOLVED. Code analysis of `sapil_print.cpp` and `ProfileEmbedder.kt` confirms two distinct paths:
   - `enable_prime_tower`, `prime_tower_width`, `wipe_tower_x/y` → set by JNI (`SliceConfig`) — already work, no changes needed
   - `prime_volume`, `prime_tower_brim_width`, `prime_tower_brim_chamfer`, `prime_tower_brim_chamfer_max_width` → **NOT set by `sapil_print.cpp`**, already in `ProfileEmbedder.CLAMP_INT_RULES` (lines 61-64 of ProfileEmbedder.kt), and `prime_tower_brim_width` is already read by `sanitizeWipeTowerPosition()` — **ProfileEmbedder JSON path confirmed safe, no JNI rebuild needed**

4. **Item 3 — Tap-to-select UX:** RESOLVED — tap model = enter move mode; tap empty space = deselect → back to orbit. Tapping model again while selected initiates drag (no toggle-deselect on model tap).

5. **Item 13b — PrintMonitorScreen retirement:** After merging into `PrinterScreen`, the `Routes.PRINT_MONITOR` route is no longer needed. Confirm the "Send to Printer" flow redirect is acceptable.
