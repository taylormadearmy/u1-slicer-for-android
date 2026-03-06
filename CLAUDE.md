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
./gradlew testDebugUnitTest                                                    # 152 JVM unit tests
ANDROID_SERIAL=43211JEKB00954 ./gradlew connectedDebugAndroidTest             # 59 instrumented tests
```

### MANDATORY: End-to-end testing before a feature is "done"

**Every new feature MUST be tested on the physical test device (43211JEKB00954) before being marked complete.**

End-to-end test checklist:
1. `ANDROID_SERIAL=43211JEKB00954 ./gradlew installDebug --no-daemon`
2. Launch the app and navigate to the relevant screen
3. Exercise the happy path
4. Exercise at least one error/edge case
5. `adb -s 43211JEKB00954 logcat -s "SlicerVM" -d` — check for exceptions
6. Only then mark the feature complete

**Never test on NE12442001324 — that is the user's personal device.**

### Unit tests (`app/src/test/`)
- `gcode/GcodeParserTest.kt` — G-code parsing: layers, extrusion, extruder switching
- `gcode/GcodeValidatorTest.kt` — Tool changes, nozzle temps, layer count, prime tower footprint
- `viewer/StlParserTest.kt` — Binary/ASCII STL parsing, bounding box, vertex data
- `network/MakerWorldClientTest.kt` — MakerWorld URL parsing and validation
- `network/MoonrakerClientTest.kt` — PrinterStatus computed properties, URL normalization
- `data/SliceConfigTest.kt` — Default values match Snapmaker U1 hardware specs
- `data/DataClassesTest.kt` — FilamentProfile, SliceJob, GcodeMove, ModelInfo, WipeTowerInfo
- `bambu/ThreeMfParserTest.kt` — 3MF data model construction
- `bambu/BambuSanitizerTest.kt` — INI config parsing, nil replacement, array normalization
- `ui/ExtruderAssignmentTest.kt` — ExtruderAssignment defaults, copy, list building
- `ui/FilamentJsonImportTest.kt` — JSON import parsing: snake_case/camelCase, defaults, errors
- `model/CopyArrangeCalculatorTest.kt` — Grid layout, bed bounds, copy capping

### Instrumented tests (`app/src/androidTest/`)
- `data/FilamentDaoTest.kt` — Room DAO CRUD, ordering, count
- `data/SliceJobDaoTest.kt` — Room DAO insert, ordering, delete
- `native/NativeLibrarySymbolTest.kt` — JNI symbol smoke tests
- `slicing/SlicingIntegrationTest.kt` — STL/3MF load→slice, temps, layer count, metadata
- `slicing/BambuPipelineIntegrationTest.kt` — Multi-plate, dual/4-colour, Shashibo sanitization
- `slicing/ProfileEmbedderIntegrationTest.kt` — ZIP validity, config keys, full embed→slice pipeline

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
