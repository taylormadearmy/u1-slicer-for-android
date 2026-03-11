# U1 Slicer for Android

Android app wrapping **Snapmaker Orca 2.2.4** (OrcaSlicer fork) for Snapmaker U1 (270×270×270mm, 4 extruders).
Kotlin + Jetpack Compose + Material3 blue theme + Native C++ via JNI.
App ID: `com.u1.slicer.orca`

## Build

```bash
./gradlew installDebug          # Build and install on connected device
./gradlew assembleDebug          # Build APK only
```

Gradle daemon may OOM — use `--no-daemon` if builds fail.

**WSL environment**: `./gradlew` fails in WSL (CRLF line endings + Windows-only SDK). Always run Gradle via:
```bash
/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe -NoProfile -Command "cd 'C:\Users\kevin\projects\u1-slicer-orca'; .\gradlew <task> --no-daemon"
```

**Git worktree**: `.git` is a file (worktree), not a directory. Plain `git` fails in WSL. Use:
```bash
GIT_DIR="/mnt/c/Users/kevin/projects/u1-slicer-for-android/.git/worktrees/u1-slicer-orca" git --work-tree="$(pwd)" <cmd>
```
Add `--ignore-cr-at-eol` to `git diff` to skip CRLF-only noise and see real changes.

## Test

```bash
./gradlew testDebugUnitTest                                                    # 261 JVM unit tests
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest             # 98 instrumented tests (uses Orchestrator)
```

### MANDATORY: End-to-end testing before a feature is "done"

**Every new feature MUST be tested on the physical test device (43211JEKB16931) before being marked complete.**

End-to-end test checklist:
1. `ANDROID_SERIAL=43211JEKB16931 ./gradlew installDebug --no-daemon`
2. Launch the app and navigate to the relevant screen
3. Exercise the happy path
4. Exercise at least one error/edge case
5. `adb -s 43211JEKB16931 logcat -s "SlicerVM" -d` — check for exceptions
6. Only then mark the feature complete

**Never test on NE12442001324 (model: NF22E1, Android 13) — that is the user's personal device.**
User also has a Pixel 9a (serial: 5A101JEBF24790) — this is a personal device, do not run automated tests on it.

## ADB helpers

```bash
adb -s 43211JEKB16931 exec-out screencap -p > /tmp/screen.png        # screenshot → Read /tmp/screen.png
adb -s 43211JEKB16931 shell "run-as com.u1.slicer.orca ls -lh files/"  # inspect app's internal files
adb -s 43211JEKB16931 shell "run-as com.u1.slicer.orca sh -c 'rm files/plate1_embedded_*.3mf'"  # clear stale plate cache
adb -s 43211JEKB16931 logcat -b main -d | grep "11079" | tail -30     # filter logcat by app PID
```

Both test device and personal device are always connected. `connectedDebugAndroidTest` runs on ALL connected devices. **Disconnect NF22E1 (NE12442001324) before running instrumented tests** — it lacks the native .so and all tests fail on it. File-lock errors can also occur when both are present; clean with:
```bash
rm -rf app/build/outputs/androidTest-results/
```

### Running from Windows (Windows agent / PowerShell)

All Gradle commands must be run from **Windows PowerShell**, not WSL:

```powershell
# Unit tests (260)
cd C:\Users\kevin\projects\u1-slicer-orca
.\gradlew testDebugUnitTest --no-daemon

# Instrumented tests (97) — test device must be connected, uses Orchestrator
Remove-Item -Recurse -Force app\build\outputs\androidTest-results -ErrorAction SilentlyContinue
.\gradlew connectedDebugAndroidTest --no-daemon

# Build + install on test device only
.\gradlew installDebug --no-daemon
adb -s 43211JEKB16931 shell am start -n com.u1.slicer.orca/com.u1.slicer.MainActivity
```

Check results: `app\build\reports\tests\testDebugUnitTest\index.html` (unit) and `app\build\reports\androidTests\connected\debug\index.html` (instrumented).

**If instrumented tests fail with "file locked"**: a previous Gradle run left file handles open. Kill the Gradle daemon (`.\gradlew --stop`), rerun `Remove-Item` above, then retry.

### Unit tests (`app/src/test/`) — 260 tests across 17 classes
- `gcode/GcodeParserTest.kt` (16) — G-code parsing: layers, extrusion, extruder switching
- `gcode/GcodeValidatorTest.kt` (31) — Tool changes, nozzle temps, layer count, prime tower footprint
- `gcode/GcodeToolRemapperTest.kt` (19) — Compact tool index remapping, SM_ params, M104/M109
- `viewer/StlParserTest.kt` (9) — Binary/ASCII STL parsing, bounding box, vertex data
- `network/MakerWorldClientTest.kt` (12) — MakerWorld URL parsing and validation
- `network/MoonrakerClientTest.kt` (25) — PrinterStatus computed properties, URL normalization, LED state
- `data/SliceConfigTest.kt` (21) — Default values match Snapmaker U1 hardware specs
- `data/DataClassesTest.kt` (17) — FilamentProfile, SliceJob, GcodeMove, ModelInfo, WipeTowerInfo
- `data/SlicingOverridesTest.kt` (25) — Override modes, JSON serialization round-trip, defaults, resolveInto(), multi-extruder wipe tower, resolvePrimeTower() profile-embed path
- `data/SettingsBackupTest.kt` (10) — Export/import round-trip, version validation, partial restore
- `bambu/ThreeMfParserTest.kt` (7) — 3MF data model construction, isMultiPlate detection
- `bambu/BambuSanitizerTest.kt` (21) — INI config parsing, nil replacement, array normalization, filterModelToPlate, stripNonPrintableBuildItems, stripAssembleSection, component size guard
- `bambu/ProfileEmbedderTest.kt` (5) — convertToModelSettings: per-volume extruder preservation, remap, attribute order
- `ui/ExtruderAssignmentTest.kt` (6) — ExtruderAssignment defaults, copy, list building
- `ui/FilamentJsonImportTest.kt` (15) — JSON import parsing: snake_case/camelCase, defaults, errors
- `ui/MultiColorMappingTest.kt` (7) — ensureMultiSlotMapping collapse detection and sequential distribution
- `model/CopyArrangeCalculatorTest.kt` (15) — Grid layout, bed bounds, copy capping, wipe tower auto-positioning, skirt clearance

### Instrumented tests (`app/src/androidTest/`) — 97 tests across 10 classes
- `data/FilamentDaoTest.kt` (9) — Room DAO CRUD, ordering, count
- `data/SliceJobDaoTest.kt` (5) — Room DAO insert, ordering, delete
- `native/NativeLibrarySymbolTest.kt` (6) — JNI symbol smoke tests
- `native/NativeLibraryCorrectnessTest.kt` (4) — JNI correctness checks
- `slicing/SlicingIntegrationTest.kt` (25) — STL/3MF load→slice, temps, layer count, metadata, SlicingOverrides E2E
- `slicing/BambuPipelineIntegrationTest.kt` (26) — Multi-plate, dual/4-colour, Shashibo sanitization, Benchy printable=0 strip, coaster position-based plate extraction, G-code T1 tool change assertions, detectPaintData component-file regression, restructurePlateFile multi-extruder config guard
- `slicing/SemmSlicingTest.kt` (1) — SEMM (paint data) slicing pipeline
- `slicing/ProfileEmbedderIntegrationTest.kt` (11) — ZIP validity, config keys, full embed→slice pipeline
- `gcode/GcodeThumbnailInjectorTest.kt` (8) — 3MF image extraction, thumbnail blocks, G-code injection
- `viewer/ThreeMfMeshParserTest.kt` (2) — 3MF mesh parsing and transform resolution

## Architecture

- **MVVM**: SlicerViewModel (StateFlow) + Compose UI
- **DI**: Manual via AppContainer
- **Persistence**: Room DB (filaments, jobs) + DataStore (settings)
- **Network**: OkHttp (Moonraker printer API, MakerWorld downloads)
- **Native**: Snapmaker Orca C++ via JNI (`app/src/main/cpp/`) — pre-built `.so` in `jniLibs/`
- **3D**: OpenGL ES 3.0 via GLSurfaceView (`viewer/` package)

## Key conventions

- Kotlin 1.9.22, compileSdk 34, minSdk 26, JVM 17
- Use `Icons.AutoMirrored.Filled.ArrowBack` (not deprecated `Icons.Default.ArrowBack`)
- Do NOT add fields to ModelInfo/SliceConfig without rebuilding the native `.so` — JNI signatures must match
- OrcaSlicer config key names differ from PrusaSlicer: `wall_loops`, `sparse_infill_density`, `enable_prime_tower`, `initial_layer_print_height`, etc.
- `wipe_tower_x` / `wipe_tower_y` are `ConfigOptionFloats` arrays in OrcaSlicer
- Add unit tests for every new parsing/logic function
- `org.json` is Android API — add `testImplementation 'org.json:json:20231013'` for JVM tests that use it
- `SlicingOverrides.resolveInto(SliceConfig)` is the canonical way to apply override modes before slicing — always call it in `startSlicing()`, never pass `_config.value` directly to `native.slice()`
- `BambuSanitizer.filterModelToPlate` only rewrites `<build>` — never remove objects from `<resources>` (breaks OrcaSlicer via dangling refs in model_settings.config)
- `BambuSanitizer.filterModelToPlate` position-based fallback (no `p:object_id`) runs when `hasPlateJsons=true` OR any item has virtual TX/TY >270 or <0 — covers new format (foldy+coaster) and old format (Dragon Scale, Shashibo); picks N-th item by XML order and re-centres to (135,135)
- `BambuSanitizer.extractPlate()` accepts `hasPlateJsons` param — pass `sourceModelInfo.hasPlateJsons` from SlicerViewModel since process() strips plate_N.json files; also strips `<assemble>` section to avoid OrcaSlicer load failure
- `ThreeMfInfo.hasPlateJsons` — true when original Bambu ZIP has Metadata/plate_N.json files; persisted through the pipeline
- `BambuSanitizer.process()` skips `restructureForMultiColor` when total component file size > 50MB (OOM guard) — raised from 15MB because OrcaSlicer's `_generate_volumes_new` does NOT support firstid/lastid volume splitting; multi-color models MUST be restructured for per-volume extruder assignments to work
- `ThreeMfParser.isMultiPlate`: uses plate JSON count (new format) OR virtual-plate item positions (TX>270 or TY<0 on printable items) for old format detection
- Do NOT call `clearModel()+loadModel()` before `setModelInstances()` in `startSlicing()` — `setModelInstances()` clears instances internally; the extra reload causes "Coordinate outside allowed range" Clipper errors
- `BambuSanitizer.extractPlate()` copies ALL ZIP entries including PNG previews (unlike `process()` which strips them) — plate files therefore work with `GcodeThumbnailInjector`
- `startSlicing()` wraps its entire body in `try/catch(Throwable)` with `finally { native.progressListener = null }` — any uncaught exception sets `SlicerState.Error` instead of leaving UI stuck at "100% Slicing"
- Stale cache auto-cleared: `MainActivity.clearStaleCacheOnUpgrade()` deletes `embedded_*`, `sanitized_*`, and `plate*.3mf` cache files on **every cold start** (not just version upgrades) — prevents "Coordinate outside allowed range" Clipper errors from stale sanitizer output; files are regenerated on demand so no user data is lost
- `ensureMultiSlotMapping(rawMapping, colorCount)` in `MultiColorDialog.kt` — detects all-same-slot collapse for multi-color models and distributes 0,1,0,1,… to guarantee multi-extruder initial mapping
- `resolveInto()` and `resolvePrimeTower()` in `SlicingOverrides` both force wipe tower true when `extruderCount > 1` unless the user explicitly set `OVERRIDE` mode to false — OrcaSlicer requires a wipe tower for T1 tool changes; `buildProfileOverrides()` uses `resolvePrimeTower()` to set `enable_prime_tower` in the embedded profile
- `ThreeMfParser.detectPaintData()` checks ALL `.model` entries in the ZIP, not just the main `3D/3dmodel.model` — Bambu files using p:path component refs store `paint_color` on triangles in component files (e.g. `3D/Objects/*.model`)
- `ProfileEmbedder.buildConfig()` sets `extruder_count` in the embedded `project_settings.config` — OrcaSlicer defaults to 1 without it, ignoring per-volume extruder assignments
- `TestCommandReceiver` (debug-only): BroadcastReceiver for ADB-driven E2E testing — supports LOAD_FILE, SLICE, CHECK_GCODE, DUMP_STATE, DUMP_EMBEDDED_CONFIG, SET_COLORS, SET_PRINTER, SYNC_FILAMENTS, SELECT_PLATE, NAVIGATE
- Default extruder colours are R/G/B/W (`ExtruderPreset.DEFAULT_COLORS`) — aids visual testing of multi-colour slicing
- `BambuSanitizer.restructurePlateFile()` — deferred per-plate restructuring for multi-plate multi-colour files; `process()` preserves `model_settings.config` and defers restructuring, `selectPlate()` calls `extractPlate()` then `restructurePlateFile()` to inline component meshes and write OrcaSlicer-format config
- `BambuSanitizer.process()` writes EITHER `model_settings.config` (deferred restructuring OR compound objects) OR `Slic3r_PE_model.config` (non-compound immediate restructuring), never both — duplicate entries cause ProfileEmbedder ZIP corruption
- `restructureForMultiColor()` creates **compound objects** (parent with `<components>` referencing inlined mesh objects) — ONE build item per assembly, so `ensure_on_bed()` operates on the whole assembly as a unit (B8 fix: parts stay in relative positions)
- `buildOrcaModelConfig()` generates `<part id="N">` entries for compound objects — maps 1:1 to `<component objectid="N">` elements for per-volume extruder assignment
- Android Test Orchestrator (`execution 'ANDROIDX_TEST_ORCHESTRATOR'`) runs each instrumented test in its own process — prevents native memory accumulation OOM crashes across slicing test classes
- `CopyArrangeCalculator.computeWipeTowerPosition()` — auto-positions wipe tower by evaluating 8 candidate spots (4 corners + 4 edge midpoints) with 5mm edge margin (skirt clearance), picking the one with most clearance from all model bounding boxes; called in `loadNativeModel()` and `applyMultiColorAssignments()` when multi-extruder detected; user can override by dragging tower in placement viewer

## Profile Key Pipeline (IMPORTANT: read before adding slicer settings)

Settings reach OrcaSlicer's native engine through **two paths** — a setting that's only in one path will silently fall back to OrcaSlicer's compiled default (often wrong for Snapmaker U1):

### Path 1: `applyConfigToPrusa()` in `sapil_print.cpp`
- Hardcoded fallback values, always applied (even for raw STL files without embedded profiles)
- `applyConfigToPrusa()` runs AFTER `profile_keys[]`, so these are the lowest-priority defaults
- **Add new settings here** when you need a sensible fallback for files with no embedded profile

### Path 2: `profile_keys[]` whitelist in `sapil_print.cpp`
- Keys in this array are read from the embedded `project_settings.config` JSON in the 3MF
- Only applied when `is_snapmaker_profile = true` (start gcode contains "PRINT_START")
- **Add new settings here** when they come from the Snapmaker profiles (pla.json, standard_0.20mm.json, snapmaker_u1.json)

### What does NOT work
- Adding a key to `orca_profiles/*.json` or `ProfileEmbedder.buildConfig()` alone — the key goes into the 3MF JSON but the native side ignores it unless it's in `profile_keys[]`
- Adding a key to `buildProfileOverrides()` alone — same problem, it's only in the embedded JSON

### Checklist for adding a new slicer setting
1. Check the OrcaSlicer default in `PrintConfig.cpp` (`set_default_value` call) — is it acceptable?
2. If not, add a fallback in `applyConfigToPrusa()` with the correct `ConfigOption` type (check `PrintConfig.cpp` for `coFloat` vs `coFloats` vs `coInts` etc.)
3. Add the key name to `profile_keys[]` so the embedded profile can override the fallback
4. If the setting should be user-controllable, also add it to `buildProfileOverrides()` in `SlicerViewModel.kt`
5. **Rebuild the native `.so`** (enable CMake in build.gradle, set `-Xmx8g`, build, strip, disable CMake)
