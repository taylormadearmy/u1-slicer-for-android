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

## Test

```bash
./gradlew testDebugUnitTest                                                    # 235 JVM unit tests
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest             # 93 instrumented tests
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

### Unit tests (`app/src/test/`)
- `gcode/GcodeParserTest.kt` — G-code parsing: layers, extrusion, extruder switching
- `gcode/GcodeValidatorTest.kt` — Tool changes, nozzle temps, layer count, prime tower footprint
- `viewer/StlParserTest.kt` — Binary/ASCII STL parsing, bounding box, vertex data
- `network/MakerWorldClientTest.kt` — MakerWorld URL parsing and validation
- `network/MoonrakerClientTest.kt` — PrinterStatus computed properties, URL normalization, LED state
- `data/SliceConfigTest.kt` — Default values match Snapmaker U1 hardware specs
- `data/DataClassesTest.kt` — FilamentProfile, SliceJob, GcodeMove, ModelInfo, WipeTowerInfo
- `data/SlicingOverridesTest.kt` — Override modes, JSON serialization round-trip, defaults, resolveInto()
- `data/SettingsBackupTest.kt` — Export/import round-trip, version validation, partial restore
- `bambu/ThreeMfParserTest.kt` — 3MF data model construction, isMultiPlate detection
- `bambu/BambuSanitizerTest.kt` — INI config parsing, nil replacement, array normalization, filterModelToPlate, stripNonPrintableBuildItems, stripAssembleSection, component size guard
- `ui/ExtruderAssignmentTest.kt` — ExtruderAssignment defaults, copy, list building
- `ui/FilamentJsonImportTest.kt` — JSON import parsing: snake_case/camelCase, defaults, errors
- `model/CopyArrangeCalculatorTest.kt` — Grid layout, bed bounds, copy capping

### Instrumented tests (`app/src/androidTest/`)
- `data/FilamentDaoTest.kt` — Room DAO CRUD, ordering, count
- `data/SliceJobDaoTest.kt` — Room DAO insert, ordering, delete
- `native/NativeLibrarySymbolTest.kt` — JNI symbol smoke tests
- `slicing/SlicingIntegrationTest.kt` — STL/3MF load→slice, temps, layer count, metadata, SlicingOverrides E2E
- `slicing/BambuPipelineIntegrationTest.kt` — Multi-plate, dual/4-colour, Shashibo sanitization, Benchy printable=0 strip, coaster position-based plate extraction
- `slicing/ProfileEmbedderIntegrationTest.kt` — ZIP validity, config keys, full embed→slice pipeline
- `gcode/GcodeThumbnailInjectorTest.kt` — 3MF image extraction, thumbnail blocks, G-code injection

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
