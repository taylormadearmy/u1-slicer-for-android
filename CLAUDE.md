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

## Release

1. **Bump version** in `app/build.gradle` - increment both `versionCode` and `versionName` (e.g. `1.3.65` -> `1.3.66`)
2. **Update docs** — update test counts in this file and `README.md` if they changed
3. **Commit and push**:
   ```bash
   git add -p
   git commit -m "bump: v1.3.66 - <short description>"
   git push
   ```
4. **Build the release APK**:
   ```bash
   ./gradlew assembleRelease --no-daemon
   ```
5. **Rename the APK** with the version number:
   ```bash
   cp app/build/outputs/apk/release/app-release.apk u1-slicer-v1.3.66.apk
   ```
6. **Create a GitHub release** (never overwrite or delete an existing release — always use a new tag):
   ```bash
   gh release create v1.3.66 u1-slicer-v1.3.66.apk \
     --title "v1.3.66" \
     --notes "Brief description of what changed."
   ```

> **Rule**: Never reuse or update a published GitHub release. If you need to fix something, bump to a new version.

## Test

```bash
./gradlew testDebugUnitTest                        # 387 JVM unit tests
./gradlew connectedDebugAndroidTest                # 109 instrumented tests (uses Orchestrator)
```

### Unit tests (`app/src/test/`) - 387 tests across 22 classes
- `gcode/GcodeParserTest.kt` (18) — G-code parsing: layers, extrusion, extruder switching
- `gcode/GcodeValidatorTest.kt` (41) — Tool changes, nozzle temps, layer count, prime tower footprint, bed bounds validation
- `gcode/GcodeToolRemapperTest.kt` (19) — Compact tool index remapping, SM_ params, M104/M109
- `viewer/StlParserTest.kt` (10) — Binary/ASCII STL parsing, bounding box, vertex data, 10-float vertex format
- `viewer/MeshDataTest.kt` (9) — MeshData 10-float vertex format, extruderIndices, recolor(), RGBA values, multi-extruder recolor
- `viewer/ThreeMfMeshParserTest.kt` (21) — 3MF mesh parsing, per-triangle color extraction, extruderMap, MeshWithContext, SEMM paint_color parsing, multi-object extruder map
- `network/MakerWorldUtilsTest.kt` (36) — URL parsing, design→instance ID resolution, download response parsing, error classification, cookie sanitization
- `network/MoonrakerClientTest.kt` (25) — PrinterStatus computed properties, URL normalization, LED state
- `data/SliceConfigTest.kt` (21) — Default values match Snapmaker U1 hardware specs
- `data/DataClassesTest.kt` (17) — FilamentProfile, SliceJob, GcodeMove, ModelInfo, WipeTowerInfo
- `data/SlicingOverridesTest.kt` (41) — Override modes, JSON serialization round-trip, defaults, resolveInto(), multi-extruder wipe tower, B24 stale config
- `data/SettingsBackupTest.kt` (15) — Export/import round-trip, version validation, partial restore, filament profile name resolution
- `bambu/ThreeMfParserTest.kt` (7) - 3MF data model construction, isMultiPlate detection
- `bambu/BambuSanitizerTest.kt` (22) — INI config parsing, nil replacement, array normalization, filterModelToPlate, component size guard
- `bambu/ProfileEmbedderTest.kt` (5) — convertToModelSettings: per-volume extruder preservation, remap, attribute order
- `ui/ExtruderAssignmentTest.kt` (6) — ExtruderAssignment defaults, copy, list building
- `ui/FilamentJsonImportTest.kt` (15) — JSON import parsing: snake_case/camelCase, defaults, errors
- `ui/MultiColorMappingTest.kt` (7) — ensureMultiSlotMapping collapse detection and sequential distribution
- `model/CopyArrangeCalculatorTest.kt` (18) — Centered grid layout, bed bounds, copy capping, wipe tower auto-positioning, skirt clearance
- `UpgradeDetectorTest.kt` (15) — APK upgrade detection logic, version/timestamp comparison, file clearing patterns
- `DiagnosticsStoreTest.kt` (5) — Diagnostics event logging, JSONL output
- `MergeThreeMfInfoTest.kt` (7) — mergeThreeMfInfo/ForPlate objectExtruderMap preference, preview file selection, H2C source detection

### Instrumented tests (`app/src/androidTest/`) - 109 tests across 11 classes
- `data/FilamentDaoTest.kt` (9) — Room DAO CRUD, ordering, count
- `data/SliceJobDaoTest.kt` (5) — Room DAO insert, ordering, delete
- `data/GcodeSaveTruncationTest.kt` (2) — Save truncation regression
- `native/NativeLibrarySymbolTest.kt` (6) — JNI symbol smoke tests
- `native/NativeLibraryCorrectnessTest.kt` (4) — JNI correctness checks
- `slicing/SlicingIntegrationTest.kt` (25) — STL/3MF load→slice, temps, layer count, metadata, SlicingOverrides E2E
- `slicing/BambuPipelineIntegrationTest.kt` (30) — Multi-plate, dual/4-colour, sanitization, position-based plate extraction, B23 extruder map after restructure, per-part extruder parsing
- `slicing/SemmSlicingTest.kt` (2) — SEMM (paint data) slicing pipeline: 2-extruder + 4-extruder assertions
- `slicing/ProfileEmbedderIntegrationTest.kt` (14) — ZIP validity, config keys, full embed→slice pipeline, re-embed regression guard (B24)
- `gcode/GcodeThumbnailInjectorTest.kt` (8) — 3MF image extraction, thumbnail blocks, G-code injection
- `viewer/ThreeMfMeshParserTest.kt` (4) - 3MF mesh parsing, transform resolution, per-triangle color extraction, calicube extruder indices

## Backlog

Open bugs and features are in [`BACKLOG.md`](BACKLOG.md). Do not implement backlog items unless asked.

## Architecture

- **MVVM**: SlicerViewModel (StateFlow) + Compose UI
- **DI**: Manual via AppContainer
- **Persistence**: Room DB (filaments, jobs) + DataStore (settings)
- **Network**: OkHttp (Moonraker printer API)
- **Native**: Snapmaker Orca C++ via JNI (`app/src/main/cpp/`) — pre-built `.so` in `jniLibs/`
- **3D**: OpenGL ES 3.0 via GLSurfaceView (`viewer/` package)

## Key Conventions

- Kotlin 1.9.22, compileSdk 34, minSdk 26, JVM 17
- Do NOT add fields to ModelInfo/SliceConfig without rebuilding the native `.so` — JNI signatures must match
- OrcaSlicer config key names differ from PrusaSlicer: `wall_loops`, `sparse_infill_density`, `enable_prime_tower`, `initial_layer_print_height`, etc.
- Add unit tests for every new parsing/logic function
- `org.json` is Android API — add `testImplementation 'org.json:json:20231013'` for JVM tests that use it
- Android Test Orchestrator runs each instrumented test in its own process — prevents native memory OOM
- `MeshData` vertex format: 10 floats per vertex (3 pos + 3 normal + 4 RGBA); `extruderIndices` ByteArray stores per-triangle extruder index; `recolor(extruderColors)` updates RGBA in-place from extruder index → color mapping
- `ModelRenderer.pendingRecolor` — thread-safe recolor mechanism: UI thread sets `pendingRecolor = colors`, GL thread applies via `meshData.recolor()` + VBO re-upload in `onDrawFrame()`
- `ThreeMfMeshParser.MeshWithContext` — data class holding parsed `MeshData` + `objectId`; `extruderMap: Map<String, Int>` parameter maps object IDs to extruder indices for per-triangle coloring; `parsePaintIndex()` extracts extruder index from `paint_color`/`mmu_segmentation` triangle attributes for SEMM models
- `ExtruderPickerRow` composable — row of 4 extruder chips (E1-E4) with color circles for single-color model extruder selection on Prepare screen
- `selectedExtruder` StateFlow on SlicerViewModel — tracks which extruder is selected for single-color models; triggers live recolor of 3D preview
- `objectExtruderMap` on `ThreeMfInfo` — `Map<String, Int>` of per-object extruder assignments parsed from `model_settings.config`; used by `ThreeMfMeshParser` for per-triangle coloring of multi-extruder Bambu models

## Profile Key Pipeline

Settings reach OrcaSlicer's native engine through **two paths** — a setting that's only in one path will silently fall back to OrcaSlicer's compiled default (often wrong for Snapmaker U1):

### Path 1: `applyConfigToPrusa()` in `sapil_print.cpp`
- Hardcoded fallback values, always applied (even for raw STL files without embedded profiles)
- **Add new settings here** when you need a sensible fallback for files with no embedded profile

### Path 2: `profile_keys[]` whitelist in `sapil_print.cpp`
- Keys in this array are read from the embedded `project_settings.config` JSON in the 3MF
- Only applied when `is_snapmaker_profile = true` (start gcode contains "PRINT_START")
- **Add new settings here** when they come from the Snapmaker profiles

### Checklist for adding a new slicer setting
1. Check the OrcaSlicer default in `PrintConfig.cpp` (`set_default_value` call) — is it acceptable?
2. If not, add a fallback in `applyConfigToPrusa()` with the correct `ConfigOption` type
3. **For per-extruder options**: size the vector to `n_ext`, not 1 — `WipeTowerIntegration` copies raw vectors without bounds checking
4. Add the key name to `profile_keys[]` so the embedded profile can override the fallback
5. If the setting should be user-controllable, add it to `buildProfileOverrides()` in `SlicerViewModel.kt`
6. **Rebuild the native `.so`** (use `ninja -j1` to avoid OOM, strip with `llvm-strip`, copy to `jniLibs/`)

## Native Rebuild

The native `.so` is pre-built in `app/src/main/jniLibs/arm64-v8a/`. To rebuild:

1. Enable CMake in `build.gradle` (uncomment `externalNativeBuild` blocks)
2. Run `./gradlew assembleDebug` to configure
3. Disable CMake, then run `ninja -j1` in `app/.cxx/Debug/<hash>/arm64-v8a/` (OOMs at `-j2`+)
4. Strip with NDK `llvm-strip --strip-unneeded`
5. Copy `.so` to `app/src/main/jniLibs/arm64-v8a/`
6. `./gradlew clean installDebug` — incremental builds may cache old APK
