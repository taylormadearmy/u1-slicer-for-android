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
./gradlew testDebugUnitTest                                                    # 235 JVM unit tests
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest             # 96 instrumented tests
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

**Never test on NE12442001324 — that is the user's personal device.**

## ADB helpers

```bash
adb -s 43211JEKB16931 exec-out screencap -p > /tmp/screen.png        # screenshot → Read /tmp/screen.png
adb -s 43211JEKB16931 shell "run-as com.u1.slicer.orca ls -lh files/"  # inspect app's internal files
adb -s 43211JEKB16931 shell "run-as com.u1.slicer.orca sh -c 'rm files/plate1_embedded_*.3mf'"  # clear stale plate cache
adb -s 43211JEKB16931 logcat -b main -d | grep "11079" | tail -30     # filter logcat by app PID
```

Both test device and personal device are always connected. `connectedDebugAndroidTest` runs on ALL connected devices — file-lock errors occur when both are present and a previous run left output files open. Clean with:
```bash
rm -rf app/build/outputs/androidTest-results/
```

### Running from Windows (Windows agent / PowerShell)

All Gradle commands must be run from **Windows PowerShell**, not WSL:

```powershell
# Unit tests (235)
cd C:\Users\kevin\projects\u1-slicer-orca
.\gradlew testDebugUnitTest --no-daemon

# Instrumented tests (96) — test device must be connected
Remove-Item -Recurse -Force app\build\outputs\androidTest-results -ErrorAction SilentlyContinue
.\gradlew connectedDebugAndroidTest --no-daemon

# Build + install on test device only
.\gradlew installDebug --no-daemon
adb -s 43211JEKB16931 shell am start -n com.u1.slicer.orca/com.u1.slicer.MainActivity
```

Check results: `app\build\reports\tests\testDebugUnitTest\index.html` (unit) and `app\build\reports\androidTests\connected\debug\index.html` (instrumented).

**If instrumented tests fail with "file locked"**: a previous Gradle run left file handles open on the "Pixel 8a" (NE12442001324). Kill the Gradle daemon (`.\gradlew --stop`), rerun `Remove-Item` above, then retry.

### Unit tests (`app/src/test/`) — 235 tests across 15 classes
- `gcode/GcodeParserTest.kt` (16) — G-code parsing: layers, extrusion, extruder switching
- `gcode/GcodeValidatorTest.kt` (31) — Tool changes, nozzle temps, layer count, prime tower footprint
- `gcode/GcodeToolRemapperTest.kt` (19) — Compact tool index remapping, SM_ params, M104/M109
- `viewer/StlParserTest.kt` (9) — Binary/ASCII STL parsing, bounding box, vertex data
- `network/MakerWorldClientTest.kt` (12) — MakerWorld URL parsing and validation
- `network/MoonrakerClientTest.kt` (25) — PrinterStatus computed properties, URL normalization, LED state
- `data/SliceConfigTest.kt` (21) — Default values match Snapmaker U1 hardware specs
- `data/DataClassesTest.kt` (17) — FilamentProfile, SliceJob, GcodeMove, ModelInfo, WipeTowerInfo
- `data/SlicingOverridesTest.kt` (17) — Override modes, JSON serialization round-trip, defaults, resolveInto()
- `data/SettingsBackupTest.kt` (10) — Export/import round-trip, version validation, partial restore
- `bambu/ThreeMfParserTest.kt` (7) — 3MF data model construction, isMultiPlate detection
- `bambu/BambuSanitizerTest.kt` (21) — INI config parsing, nil replacement, array normalization, filterModelToPlate, stripNonPrintableBuildItems, stripAssembleSection, component size guard
- `ui/ExtruderAssignmentTest.kt` (6) — ExtruderAssignment defaults, copy, list building
- `ui/FilamentJsonImportTest.kt` (15) — JSON import parsing: snake_case/camelCase, defaults, errors
- `model/CopyArrangeCalculatorTest.kt` (9) — Grid layout, bed bounds, copy capping

### Instrumented tests (`app/src/androidTest/`) — 96 tests across 9 classes
- `data/FilamentDaoTest.kt` (9) — Room DAO CRUD, ordering, count
- `data/SliceJobDaoTest.kt` (5) — Room DAO insert, ordering, delete
- `native/NativeLibrarySymbolTest.kt` (6) — JNI symbol smoke tests
- `native/NativeLibraryCorrectnessTest.kt` (4) — JNI correctness checks
- `slicing/SlicingIntegrationTest.kt` (24) — STL/3MF load→slice, temps, layer count, metadata, SlicingOverrides E2E
- `slicing/BambuPipelineIntegrationTest.kt` (27) — Multi-plate, dual/4-colour, Shashibo sanitization, Benchy printable=0 strip, coaster position-based plate extraction
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
- `BambuSanitizer.process()` skips `restructureForMultiColor` when total component file size > 15MB (OOM guard) — OrcaSlicer handles `p:path` component refs natively for large files
- `ThreeMfParser.isMultiPlate`: uses plate JSON count (new format) OR virtual-plate item positions (TX>270 or TY<0 on printable items) for old format detection
- Do NOT call `clearModel()+loadModel()` before `setModelInstances()` in `startSlicing()` — `setModelInstances()` clears instances internally; the extra reload causes "Coordinate outside allowed range" Clipper errors
- `BambuSanitizer.extractPlate()` copies ALL ZIP entries including PNG previews (unlike `process()` which strips them) — plate files therefore work with `GcodeThumbnailInjector`
- `startSlicing()` wraps its entire body in `try/catch(Throwable)` with `finally { native.progressListener = null }` — any uncaught exception sets `SlicerState.Error` instead of leaving UI stuck at "100% Slicing"
- Stale plate cache: after changing sanitizer/extraction logic, delete `files/plate*_embedded_*.3mf` on device (or reinstall app) — cached pre-fix files will produce wrong results silently
