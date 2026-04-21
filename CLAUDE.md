# U1 Slicer for Android

Android app wrapping **Snapmaker Orca 2.2.4** (OrcaSlicer fork) for Snapmaker U1 (270×270×270mm, 4 extruders).
Kotlin + Jetpack Compose + Material3 blue theme + Native C++ via JNI.
App ID: `com.u1.slicer.orca`
Current release: `v1.6.8` (`versionCode 252`)

> For local-only device IDs, adb targets, and any machine-specific workflow notes, see `CLAUDE.local.md` if present.
> For the current deep-dive on the post-upgrade native Clipper failure, see [`CLIPPER_UPGRADE_INVESTIGATION.md`](CLIPPER_UPGRADE_INVESTIGATION.md).

## Build

```bash
./gradlew installDebug          # Build and install on connected device
./gradlew assembleDebug          # Build APK only
```

Gradle daemon may OOM — use `--no-daemon` if builds fail.

## Release

1. **Bump version** in `app/build.gradle` - increment both `versionCode` and `versionName` (e.g. `1.4.10` -> `1.4.11`)
2. **Update docs** — update test counts in this file and `README.md` if they changed
3. **Commit and push**:
   ```bash
   git add -p
   git commit -m "bump: v1.4.11 - <short description>"
   git push
   ```
4. **Build the release APK**:
   ```bash
   ./gradlew assembleRelease --no-daemon
   ```
5. **Rename the APK** with the version number:
   ```bash
   cp app/build/outputs/apk/release/app-release.apk u1-slicer-v1.4.11.apk
   ```
6. **Create a GitHub release** (never overwrite or delete an existing release — always use a new tag):
   ```bash
   gh release create v1.4.11 u1-slicer-v1.4.11.apk \
     --title "v1.4.11" \
     --notes "Brief description of what changed."
   ```

> **Rule**: Never reuse or update a published GitHub release. If you need to fix something, bump to a new version.

## Security

Public vulnerability reports should follow [`SECURITY.md`](SECURITY.md). Keep any private device IDs, adb targets, and local test notes in `CLAUDE.local.md` only.

## Test

```bash
./gradlew testDebugUnitTest                        # 827 JVM unit tests
./gradlew connectedDebugAndroidTest                # 186 instrumented tests (uses Orchestrator)
```

For local device IDs and any private E2E notes, consult `E2E_TESTING.local.md` if present.

> **All tests must pass — there are no known pre-existing failures.** If a test fails, investigate it; do not assume it is a pre-existing or flaky issue.

> **NEVER weaken a test assertion to make a failing test pass.** Do not change `>= 4` to `>= 2`, rename tests to match reduced expectations, or adjust expected values downward. Tests document correct behaviour. A failing test means the code regressed — investigate the root cause and fix the code, not the test.

### Unit tests (`app/src/test/`) - 813 tests across 56 classes
- `gcode/GcodeParserTest.kt` (33) — G-code parsing: layers, extrusion, extruder switching, ;TYPE: feature-type tagging, wipeTowerFilamentMm, B52 maxMoves cap + stride distribution
- `gcode/GcodeValidatorTest.kt` (45) — Tool changes, nozzle temps, layer count, prime tower footprint, bed bounds validation
- `gcode/SuspiciousLineContextTest.kt` (6) — B52 streaming line context lookup: window clamping, multi-sample cap, large file smoke test
- `gcode/GcodeToolRemapperTest.kt` (19) — Compact tool index remapping, SM_ params, M104/M109
- `viewer/StlParserTest.kt` (10) — Binary/ASCII STL parsing, bounding box, vertex data, 10-float vertex format
- `viewer/MeshDataTest.kt` (11) — MeshData 10-float vertex format, extruderIndices, recolor(), RGBA values, multi-extruder recolor
- `viewer/ThreeMfMeshParserTest.kt` (29) - 3MF mesh parsing, per-triangle color extraction, extruderMap, MeshWithContext, SEMM paint_color parsing, multi-object extruder map
- `network/MakerWorldUtilsTest.kt` (36) — URL parsing, design→instance ID resolution, download response parsing, error classification, cookie sanitization
- `network/MoonrakerClientTest.kt` (38) — PrinterStatus computed properties, URL normalization, LED state, remoteScreenUrl(), B33 virtual_sdcard progress parsing, sendGcode network path, queryWebcamSnapshotCandidates monitor.jpg appending
- `data/SliceConfigTest.kt` (25) — Default values match Snapmaker U1 hardware specs, wipe tower bounds clamping
- `data/DataClassesTest.kt` (17) — FilamentProfile, SliceJob, GcodeMove, ModelInfo, WipeTowerInfo
- `data/PlateTypeTest.kt` (21) — PlateType.bedTempFor per-material presets, fromName, case-insensitivity
- `data/SlicingOverridesTest.kt` (90) — Override modes, JSON serialization round-trip, defaults, resolveInto(), multi-extruder wipe tower, B24 stale config, B31 brim_type, F30/F31 plus F41/F42/F43 override/file-value coverage, F57/F58 primeTowerWidth + wipeTowerRotationAngle, B53 computeTogglePrimeTower, B71 nozzle temp extruderTemps + nozzleTemps slice-time override, B79 resolveInto supportType/supportAngle
- `data/SettingsBackupTest.kt` (16) — Export/import round-trip, version validation, partial restore, filament profile name resolution, stale skirt-loop import normalization
- `bambu/ThreeMfParserTest.kt` (12) - 3MF data model construction, isMultiPlate detection, hasPaintSupports field (B57)
- `bambu/BambuSanitizerTest.kt` (25) — INI config parsing, nil replacement, array normalization, filterModelToPlate, component size guard, group recentering
- `bambu/ProfileEmbedderTest.kt` (5) — convertToModelSettings: per-volume extruder preservation, remap, attribute order
- `bambu/LayerToolCustomGcodeXmlTest.kt` (3) — custom_gcode_per_layer.xml colour extraction for type 1 and 2 (parity with pause injector); per-plate parsing (parseLayerToolCustomGcodeXmlPerPlate)
- `ui/ExtruderAssignmentTest.kt` (6) — ExtruderAssignment defaults, copy, list building
- `ui/FilamentJsonImportTest.kt` (15) — JSON import parsing: snake_case/camelCase, defaults, errors
- `ui/MultiColorMappingTest.kt` (9) — ensureMultiSlotMapping collapse detection and sequential distribution
- `ui/PrinterStatusBadgeTest.kt` (14) — Printer status badge text, color, and icon mapping for all printer states
- `FilePickerValidationTest.kt` (8) — isSupportedFile extension matching for 3MF, STL, OBJ, STEP; rejects unsupported types
- `model/CopyArrangeCalculatorTest.kt` (21) — Centered grid layout, bed bounds, copy capping, wipe tower auto-positioning, skirt clearance
- `UpgradeDetectorTest.kt` (15) — APK upgrade detection logic, version/timestamp comparison, file clearing patterns
- `DiagnosticsStoreTest.kt` (5) — Diagnostics event logging, JSONL output
- `MergeThreeMfInfoTest.kt` (49) — mergeThreeMfInfo/ForPlate objectExtruderMap preference, preview file selection, H2C source detection, SEMM extruderRemap suppression, isHueforgePlate classification (extruder diversity, plate-level paint data, uniform extruder, mixed-paint plates), B60 hasPaintSupports preservation, B82 per-plate layer-tool secondary colour matching (palette-match = real, off-palette = artefact)
- `printer/PrinterRepositoryTest.kt` (2) — upload filename sanitization and unique suffix generation
- `printer/PrinterRepositoryNotificationTest.kt` (9) — printer state transition detection for all event types
- `printer/PrinterViewModelTest.kt` (4) — camera keepalive idempotency helper and LED-sync connection-edge helper
- `AppEventNotifierTest.kt` (13) — notification title/body/channel/navigate-target for all event types
- `PreviewSummaryMappingTest.kt` (7) — preview summary data class mapping, F65 resolveExtruderMaterialType by slot, F68 single-colour material label
- `PreviewColorNormalizationTest.kt` (7) — preview colour normalization
- `PreparePreviewPlacementTest.kt` (5) — native 3MF wipe tower visibility, object-placement rules, and large-preview fallback state retention
- `viewer/NativePreviewMeshTest.kt` (4) — preview budget guardrails, MAX_DECIMATED_TRIANGLES constant, F48 subsampled mesh vertex count
- iewer/ModelViewerViewTest.kt (3) — Prepare selection falls back from face-plane to bed-plane hit-testing when needed

- `ui/MakerWorldBrowserUtilsTest.kt` (10) — sanitizeFilename path traversal, hasAuthCookies heuristic
- `WipeTowerClampTest.kt` (8) — wipeTowerClampBounds: pre-slice Y-clamp uses estimated depth not width; resolveWipeTowerWidth/resolveWipeTowerDepth: return active override or config default
- `data/WipeTowerDepthEstimatorTest.kt` (8) — height-based depth lookup table; primeVolume override wins when larger than height-based minimum
- `viewer/GcodeRendererGeometryTest.kt` (21) — segment packer: chain construction, shared vertices, travel breaks, turning angles (90°, straight, caps), z-offset, layer ranges, extruder/feature colors, brightness gradient, color encode/decode round-trip, texture dimensions, 400k stress test
- `gcode/LayerToolPauseInjectorTest.kt` (9) — PAUSE_PRINT injection for layer-tool colour swaps
- `LargeModelLoadingMessageTest.kt` (5) — large model loading state messages
- `SliceResultFromJobTest.kt` (2) — SliceResult construction from SliceJob
- `printer/PrintProgressNotifierTest.kt` (3) — print progress notification logic
- `PreparePreviewCacheTest.kt` (10) — B49 Prepare preview cache state machine: fresh load, tab switch cache hit, GL upload after cache hit, repeated effect dedup, parse effect cache guard, B59b togglePrimeTower cache invalidation contract
- `SingleColorExtruderConfigTest.kt` (6) — B56 single-color extruder selection filamentType propagation: E1-E4 material types, round-trip, missing preset fallback
- `FilamentTypeLabelTest.kt` (12) — B59 resolveFilamentTypeLabel: single-slot, all-same-material, mixed materials, edge cases (unknown slot, empty inputs)
- `FilamentTypeWiringTest.kt` (11) — B59 wiring: resolveFilamentTypeForSingleColorLoad, resolveFilamentTypeLabelFromMapping for multi-color and layer-tool paths
- `ui/HsvColorPickerTest.kt` (9) — F64 HSV↔hex color conversion round-trips: hsvToHex, hexToHsv, red/green/blue/white/black, inverse property
- `SliceStalenessTest.kt` (4) — F67 _sliceStale StateFlow contract: initial false, config mutation sets true, startSlicing resets false, extruderPresets drop(1) skips startup
- `SliceCancelTest.kt` (5) — B55 cancel state machine: SliceResult.cancelled field, Cancelling state singleton
- `SemmColorPermutationTest.kt` (11) — B64 computeSemmColorPermutation: identity/permuted/H2C/non-SEMM/sparse guards; composeSemmRemap: priority, both-present, both-null
- `FilamentTypeHeaderPatchTest.kt` (6) — B63 fixFilamentTypeHeader: single/multi-extruder replacement, absent line guard, empty list guard, missing file, first-occurrence-only
- `network/UpdateCheckerTest.kt` (12) — F70 GitHub release JSON parsing, semantic version comparison, download URL extraction
- `NozzleTempDefaultTest.kt` (11+7=18) — nozzleTempDefaultForMaterial per-material defaults + ComputeFreshExtruderTempsTest: preset→temp lookup, filament profile ID priority, usedSlots remap, stale-config regression (v1.5.63)
- `bambu/BambuSanitizerMetadataPreservationTest.kt` (2) — B77: per-object non-extruder metadata (enable_support, support_type, seam_position, layer_height) preserved through sanitizer no-rewrite branch

### Instrumented tests (`app/src/androidTest/`) - 181 tests across 18 classes
- `data/FilamentDaoTest.kt` (9) — Room DAO CRUD, ordering, count
- `data/SliceJobDaoTest.kt` (8) — Room DAO insert, ordering, delete, sourcePath null default, round-trip, updateSourcePath
- `data/GcodeSaveTruncationTest.kt` (2) — Save truncation regression
- `native/NativeLibrarySymbolTest.kt` (6) — JNI symbol smoke tests
- `native/NativeLibraryCorrectnessTest.kt` (4) — JNI correctness checks
- `slicing/SlicingIntegrationTest.kt` (39) — STL/3MF load→slice, temps, layer count, metadata, SlicingOverrides E2E, F57 rotation smoke test, rotation preview mesh invalidation, multi-object group rotation distance preservation, rotation cache skip, embedded rotation preservation, B55 slice cancel, v1.5.63 nozzle temp JNI path (PLA=220, PETG=235), B73 scale-down placement correctness, B75 parked extruder cooldown, B79 tree support type + filament type for STL, brim_type no_brim guard, resolveInto→JNI chain
- `slicing/BambuPipelineIntegrationTest.kt` (34) — Multi-plate, dual/4-colour, sanitization, position-based plate extraction, B23 extruder map after restructure, per-part extruder parsing, B54 modifier volume subtype preservation, B82 per-plate layer-tool chip count (standard + painted flippy all plates)
- `slicing/SemmSlicingTest.kt` (5) — SEMM (paint data) slicing pipeline: 2-extruder + 4-extruder assertions, H2C benchy 7-colour G-code tool counts, SEMM tool remap guard, B64 Flarewing Dragon colour permutation remap
- `slicing/SensoryTwistSupportsTest.kt` (1) — B77 Sensory Twist Ball: paint_supports + per-object enable_support=1 emits Support features in G-code
- `slicing/GoatDedupeSemmTest.kt` (1) — B76 Goat: user mapping [0,1,2,2] preserves all 4 paint states in embed; post-remap T3 absorbs into T2
- `slicing/ProfileEmbedderIntegrationTest.kt` (14) — ZIP validity, config keys, full embed→slice pipeline, re-embed regression guard (B24)
- `gcode/GcodeThumbnailInjectorTest.kt` (8) — 3MF image extraction, thumbnail blocks, G-code injection
- `viewer/NativePreparePreviewTest.kt` (16) — native Prepare preview regressions: dual-colour, painted, old asset, selected multi-plate spread, Dragon plate 3 colour preservation, H2C benchy full/decimated 7-index preservation + green recolor + interleaving guard, layer-tool Z-band recolor, triangle count cap, B51 old.3mf bounding box + Korok orientation, B72 multi-instance post-slice bounds, B78 Shashibo plate 5 file-scale+centre preservation on fresh load + post-slice dirty-path reset
- `viewer/ThreeMfMeshParserTest.kt` (4) - 3MF mesh parsing, transform resolution, per-triangle color extraction, calicube extruder indices
- `PreparePreviewViewModelTest.kt` (10) — Dragon plate 3 end-to-end Prepare state, slice-output colour coverage, H2C benchy full pipeline green verification, B47 colorMapping-before-ModelLoaded ordering contract, B83 plate-switch objectIds stable-source fix, entry-point equivalence for `loadModel(uri)` vs `loadModelFromFile(file)`, B86 S-Buttons user-like presets (E2=white/E4=pink) 4-distinct-colour guard
- `ui/MakerWorldBrowserUtilsInstrumentedTest.kt` (6) — resolveDownloadFilename with URLUtil, RFC 5987, path traversal sanitization

### Red-green TDD for bug fixes

When fixing visual or pipeline bugs (preview colours, G-code output, colour mapping), use red-green TDD:

1. **Red**: Write a failing instrumented test that reproduces the bug programmatically. The test must fail on the current code and assert the correct behaviour.
2. **Green**: Fix the code until the test passes.
3. **Verify**: Run the test on-device (`connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.TestClass#testMethod"`) — do not rely on screenshots or manual inspection.

**Why**: Screenshots at default zoom are unreliable. Manual visual checks can't be repeated. Programmatic tests catch regressions automatically.

**Where to add tests**:
- G-code output bugs → `SemmSlicingTest.kt` or `SlicingIntegrationTest.kt` — load asset, embed profile, slice, grep G-code for tool counts / bounds / temps
- Prepare preview bugs → `NativePreparePreviewTest.kt` — load asset, get native preview mesh, check `extruderIndices` distribution, apply `recolor()`, verify RGBA values at specific triangle indices
- Colour mapping bugs → `MergeThreeMfInfoTest.kt` (unit) — test `computeEmbedTargetCount`, `buildCompactExtruderRemap`, `mergeThreeMfInfoForPlate`
- Gcode preview bugs → `GcodeRendererGeometryTest.kt` (unit) — test instanced tube colour assignment from parsed G-code

**Pattern for preview colour verification**:
```kotlin
val preview = lib.getPreparePreviewMesh()
val mesh = preview!!.toMeshData()
// Build palette from colorMapping + extruderColors
val palette = colorMapping.map { slot -> extruderColorFloats[slot] }
mesh.recolor(palette)
// Check RGBA at triangle index 5 (green in H2C benchy)
val rOffset = targetTriIndex * 3 * 10 + 6  // vertex 0, R channel
assertEquals(0f, mesh.vertices.get(rOffset), 0.01f)       // R=0
assertEquals(1f, mesh.vertices.get(rOffset + 1), 0.01f)   // G=1
```

**Pattern for G-code tool count verification**:
```kotlin
val result = lib.slice(config)
val gcode = File(result!!.gcodePath).readText()
val toolCounts = (0..3).map { t -> gcode.lines().count { it.trim() == "T$t" } }
assertTrue("T1 (green) must be > 0, got ${toolCounts[1]}", toolCounts[1] > 0)
```

## Backlog

Open bugs and features are in [`BACKLOG.md`](BACKLOG.md). Do not implement backlog items unless asked.

**BACKLOG ↔ GitHub issue sync**: Every open bug or feature in BACKLOG.md must have a corresponding GitHub issue, and vice versa. When adding a new bug/feature to either place, always create the matching entry in the other. Include the `(GitHub #N)` reference in the BACKLOG heading.

## Architecture

- **MVVM**: SlicerViewModel (StateFlow) + Compose UI
- **DI**: Manual via AppContainer
- **Persistence**: Room DB (filaments, jobs) + DataStore (settings)
- **Network**: OkHttp (Moonraker printer API)
- **Native**: Snapmaker Orca C++ via JNI (`app/src/main/cpp/`) — pre-built `.so` in `jniLibs/`
- Keep `app/src/main/jniLibs/arm64-v8a/libc++_shared.so` alongside `libprusaslicer-jni.so` — clean worktrees and connected tests need both packaged into the APK
- **3D**: OpenGL ES 3.0 via GLSurfaceView (`viewer/` package)

## Key Conventions

- Kotlin 1.9.22, compileSdk 34, minSdk 26, JVM 17
- Do NOT add fields to ModelInfo/SliceConfig without rebuilding the native `.so` — JNI signatures must match
- If native source changes are needed for new functionality or correct runtime fallback behavior, it is always OK to rebuild the native `.so`; don't leave required C++ changes source-only
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

For clarity: rebuilding the native library is not something to avoid on principle. If a feature depends on C++ changes, rebuild it so the shipped app actually gets the functionality.

**Authorization**: Claude is pre-authorized to rebuild the native `.so` whenever a bug fix or feature genuinely requires C++ changes — no need to pause and ask. Follow the NDK 26 / Release / size + compiler verification checklist below exactly; ship only stripped Release builds.

## Native Rebuild

The native `.so` is pre-built in `app/src/main/jniLibs/arm64-v8a/`. To rebuild:

> **CRITICAL: Must use NDK 26 (Clang 17).** NDK 25 (Clang 14) produces different code generation
> for OrcaSlicer's paint segmentation, causing B62 regression (H2C benchy 436 vs 840 tool changes).
> Always verify the compiler: `llvm-readelf -p .comment libprusaslicer-jni.so` must show `clang version 17`.

> **CRITICAL: Always build with Release optimization.** Debug builds (`-O0`) produce a ~83MB `.so`
> (vs ~20MB Release) that is 3-5x slower and causes native OOM crashes on heavy multi-colour models.

### Using an existing build directory (preferred — faster)

If `app/.cxx/Debug/<hash>/arm64-v8a/build.ninja` already exists from a previous build:

1. **Verify NDK 26** — check `CMakeCache.txt`:
   ```
   CMAKE_TOOLCHAIN_FILE:FILEPATH=.../ndk/26.1.10909125/build/cmake/android.toolchain.cmake
   ```
   If it points to NDK 25 or 23, create a fresh build directory instead (see below).
2. **Ensure Release flags** — check `CMakeCache.txt`:
   ```
   CMAKE_BUILD_TYPE:STRING=Release
   ```
   Do NOT set `CMAKE_CXX_FLAGS_RELEASE` — leave it empty so the toolchain default (`-O3 -DNDEBUG`) is used.
3. Run `ninja -j1` in the directory (OOMs at `-j2`+)
4. Strip: `$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/*/bin/llvm-strip --strip-unneeded libprusaslicer-jni.so`
5. Copy to `app/src/main/jniLibs/arm64-v8a/`
6. **Verify size**: stripped Release `.so` should be ~19-21MB. If it's 50MB+, you built with Debug — redo.
7. **Verify compiler**: `llvm-readelf -p .comment libprusaslicer-jni.so` must show `clang version 17.0.2`.
8. `./gradlew clean installDebug` — incremental builds may cache old APK

### Fresh build (when no existing build dir works)

Create a new build directory configured directly for Release with NDK 26:

```bash
CMAKE=C:/Users/kevin/AppData/Local/Android/Sdk/cmake/3.22.1/bin/cmake.exe
NDK=C:/Users/kevin/AppData/Local/Android/Sdk/ndk/26.1.10909125
BUILD_DIR=app/.cxx/Debug/ndk26release/arm64-v8a
mkdir -p "$BUILD_DIR"
"$CMAKE" \
  -Happ/src/main/cpp \
  -DCMAKE_SYSTEM_NAME=Android \
  -DCMAKE_SYSTEM_VERSION=26 \
  -DANDROID_PLATFORM=android-26 \
  -DANDROID_ABI=arm64-v8a \
  -DCMAKE_ANDROID_ARCH_ABI=arm64-v8a \
  -DANDROID_NDK="$NDK" \
  -DCMAKE_ANDROID_NDK="$NDK" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DCMAKE_MAKE_PROGRAM=C:/Users/kevin/AppData/Local/Android/Sdk/cmake/3.22.1/bin/ninja.exe \
  -DCMAKE_BUILD_TYPE=Release \
  -B"$BUILD_DIR" \
  -GNinja \
  -DSLICER_BACKEND=orca \
  -DANDROID_STL=c++_shared
```
Then follow steps 3-8 from "Using an existing build directory" above.
