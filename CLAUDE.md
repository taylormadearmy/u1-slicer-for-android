# U1 Slicer for Android

Android app wrapping PrusaSlicer 2.8.1 for Snapmaker U1 (270x270x270mm, 4 extruders).
Kotlin + Jetpack Compose + Material3 + Native C++ via JNI.

## Build

```bash
./gradlew installDebug          # Build and install on connected device
./gradlew assembleDebug          # Build APK only
```

Gradle daemon may OOM — use `--no-daemon` if builds fail.

## Test

```bash
./gradlew testDebugUnitTest              # 131 JVM unit tests (no device needed)
./gradlew connectedDebugAndroidTest      # Instrumented tests (requires device/emulator)
```

### MANDATORY: End-to-end testing before a feature is "done"

**Every new feature MUST be tested on the physical test device (43211JEKB00954) before being marked complete.** Do NOT declare a feature done based only on a successful build or passing unit tests.

End-to-end test checklist for each feature:
1. `./gradlew installDebug -s 43211JEKB00954` — install on test device
2. Launch the app manually and navigate to the relevant screen
3. Exercise the happy path (feature works as expected)
4. Exercise at least one error/edge case (invalid input, empty state, etc.)
5. Check `adb -s 43211JEKB00954 logcat -s "SlicerVM" -d` for errors/exceptions after testing
6. Only then mark the feature complete

**Never test on NE12442001324 (NF22E1) — that is the user's personal device.**

### Unit tests (`app/src/test/`)
- `gcode/GcodeParserTest.kt` — G-code parsing: layers, extrusion, extruder switching
- `viewer/StlParserTest.kt` — Binary/ASCII STL parsing, bounding box, vertex data
- `network/MakerWorldClientTest.kt` — MakerWorld URL parsing and validation
- `network/MoonrakerClientTest.kt` — PrinterStatus computed properties, URL normalization
- `data/SliceConfigTest.kt` — Default values match Snapmaker U1 hardware specs
- `data/DataClassesTest.kt` — FilamentProfile, SliceJob, GcodeMove, ModelInfo
- `bambu/ThreeMfParserTest.kt` — 3MF data model construction
- `bambu/BambuSanitizerTest.kt` — INI config parsing, nil replacement, array normalization
- `ui/ExtruderAssignmentTest.kt` — ExtruderAssignment defaults, copy, list building
- `ui/FilamentJsonImportTest.kt` — JSON import parsing: snake_case/camelCase, defaults, errors

### Instrumented tests (`app/src/androidTest/`)
- `data/FilamentDaoTest.kt` — Room DAO CRUD, ordering, count
- `data/SliceJobDaoTest.kt` — Room DAO insert, ordering, delete
- `native/NativeLibrarySymbolTest.kt` — JNI symbol smoke tests

## Architecture

- **MVVM**: SlicerViewModel (StateFlow) + Compose UI
- **DI**: Manual via AppContainer
- **Persistence**: Room DB (filaments, jobs) + DataStore (settings)
- **Network**: OkHttp (Moonraker printer API, MakerWorld downloads)
- **Native**: PrusaSlicer C++ via JNI (`app/src/main/cpp/`)
- **3D**: OpenGL ES 3.0 via GLSurfaceView (`viewer/` package)

## Key conventions

- Kotlin 1.9.22, compileSdk 34, minSdk 26, JVM 17
- Use `Icons.AutoMirrored.Filled.ArrowBack` (not deprecated `Icons.Default.ArrowBack`)
- Do NOT add fields to ModelInfo/SliceConfig without rebuilding the native .so — JNI signatures must match
- Add unit tests for every new parsing/logic function; add E2E test steps to the checklist above
- `org.json` is an Android API — add `testImplementation 'org.json:json:20231013'` for any new unit test class that uses it

## Further reading

- `.agents/agents.md` — Detailed agent memory: project history, native build notes, PrusaSlicer integration details, full test coverage table
