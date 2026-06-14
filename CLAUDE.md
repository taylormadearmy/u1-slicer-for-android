# U1 Slicer for Android

Android app wrapping **Snapmaker Orca 2.2.4** (OrcaSlicer fork) for Snapmaker U1 (270×270×270mm, 4 extruders).
Kotlin + Jetpack Compose + Material3 blue theme + Native C++ via JNI.
App ID: `com.u1.slicer.orca`
Current release: `v3.3.0` (`versionCode 330`)

> **NEVER start a print on the user's physical printer without explicit permission.**
> The "Map & Print" / "Send to Printer" / "Send & Print" buttons upload G-code AND
> start the print physically. Filament heats, head moves, build plate gets used.
>
> When testing send/upload flows on-device:
>  - Use **"Map & Upload"** / **"Upload Only"** (uploads file but does NOT start the print).
>  - If you need to test the start-print path, ask the user first — even on what
>    looks like an idle printer.
>  - Subagent prompts that drive the device must NOT instruct the agent to tap
>    "Map & Print" / "Send & Print" without an explicit user-authorised reason.
>
> For local-only device IDs, adb targets, and any machine-specific workflow notes, see `CLAUDE.local.md` if present.
> The post-upgrade native Clipper failure (B38) has been resolved since v1.5.0 and is not observed in any v2.x release. Historical investigation lives in [`CLIPPER_UPGRADE_INVESTIGATION.md`](CLIPPER_UPGRADE_INVESTIGATION.md); see BACKLOG entry A3 for the resolution note.
> For the multi-phase Bambu refactor status (Phase 1 + Phase 2 done; Phase 2.0/2.6 future UX work), see [`docs/REFACTOR_STATUS.md`](docs/REFACTOR_STATUS.md).

## Build

```bash
./gradlew installDebug          # Build and install on connected device
./gradlew assembleDebug          # Build APK only
```

Gradle daemon may OOM — use `--no-daemon` if builds fail.

## Release

> **NEVER create a GitHub release or push a public tag without explicit user authorization.**
> Building the APK and staging it locally is fine; the `gh release create` step is not.
> Releases are permanent public records — always ask before publishing, even if the version
> bump and APK build were already authorized as part of a fix.

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
7. **Post-release sync** — must happen in the same turn as the release, not deferred:
   1. **Flip statuses in [`BACKLOG.md`](BACKLOG.md)** for every bug/feature that shipped:
      - Bugs: `OPEN` / `FIXED v<earlier>` → `FIXED v<earlier>, RELEASED v1.4.11 (released YYYY-MM-DD)`.
      - Features: `OPEN` → `DONE v1.4.11 (released YYYY-MM-DD)` with a short implementation summary (key files / native APIs / regression tests).
      - Add a single rollup entry to the `## Closed (recent)` section at the top of that list summarising the cluster of fixes shipped with the release. Keep the older entries intact (they form the changelog).
      - Leave items still open (partial fixes, known follow-ups) explicitly in their section with the rationale.
   2. **Close GitHub issues** for everything you just flipped:
      ```bash
      gh issue close <N> --comment "Fixed in v1.4.11: <release-url>

      <2–4 line root-cause + key code locations>"
      ```
      File any follow-up issues discovered during the release sweep (e.g. a test you had to relax, a known edge case) **before** closing the parent issue, and reference the new issue number in the closing comment.
   3. **Verify [`CLAUDE.md`](CLAUDE.md) and [`README.md`](README.md) test counts** match the latest sweep — both the inline command comment (`# N instrumented tests`) and the `## Instrumented tests` heading in CLAUDE.md and the "N total tests" line in README.md. Bump them in this commit if they drifted.
   4. **Commit the doc/backlog churn** as a follow-up commit:
      ```bash
      git add BACKLOG.md CLAUDE.md README.md
      git commit -m "docs: v1.4.11 post-release backlog/docs sync"
      git push
      ```
      Don't bundle this into the version-bump commit — keep release/version-bump and post-release-sync as separate commits so the release-tagged commit stays clean.

> **Rule**: Never reuse or update a published GitHub release. If you need to fix something, bump to a new version.

> **Rule**: A release is not "done" until step 7 is done. The BACKLOG and the GitHub issue tracker are the user's source of truth for what's outstanding — leaving them out of sync turns every future "what's left?" question into archaeology.

## Security

Public vulnerability reports should follow [`SECURITY.md`](SECURITY.md). Keep any private device IDs, adb targets, and local test notes in `CLAUDE.local.md` only.

## Test

```bash
./gradlew testDebugUnitTest                        # 1699 JVM unit tests
./gradlew connectedDebugAndroidTest                # 433 instrumented tests — uses Orchestrator
```

For live progress during the long Windows instrumented sweep, use:
```powershell
.\scripts\run-connected-with-progress.ps1 -Device <id>
```

For the repo-defined confidence sweep, use:
```powershell
.\scripts\run-confidence-check.ps1
.\scripts\run-confidence-check.ps1 -Status
```
The default smoke set is defined in [`scripts/confidence-check.psd1`](scripts/confidence-check.psd1). When touching session resume, copy-count / bed-fit, or Prepare/slice state, include the recent-change add-ons from the manifest rather than inventing a new subset.

For local device IDs and any private E2E notes, consult `E2E_TESTING.local.md` if present.

> **All tests must pass — there are no known pre-existing failures.** If a test fails, investigate it; do not assume it is a pre-existing or flaky issue.

> **NEVER weaken a test assertion to make a failing test pass.** Do not change `>= 4` to `>= 2`, rename tests to match reduced expectations, or adjust expected values downward. Tests document correct behaviour. A failing test means the code regressed — investigate the root cause and fix the code, not the test.

### Unit tests (`app/src/test/`) - 1699 tests across 150 classes
- `gcode/GcodeParserTest.kt` (36) — G-code parsing: layers, extrusion, extruder switching, ;TYPE: feature-type tagging, wipeTowerFilamentMm, B52 maxMoves cap + stride distribution, B67 perExtruderFilamentMm canonical footer order, multi-digit T-index (T15) high-tool attribution
- `gcode/GcodeValidatorTest.kt` (45) — Tool changes, nozzle temps, layer count, prime tower footprint, bed bounds validation
- `gcode/ExcludeObjectParserTest.kt` (5) — F72: parse NAME/CENTER/POLYGON from EXCLUDE_OBJECT_DEFINE lines; missing POLYGON graceful fallback; multiple objects; empty file; ignores START/END lines
- `gcode/SuspiciousLineContextTest.kt` (6) — B52 streaming line context lookup: window clamping, multi-sample cap, large file smoke test
- `gcode/GcodeToolRemapperTest.kt` (19) — Compact tool index remapping, SM_ params, M104/M109
- `gcode/CanonicalExportMappingTest.kt` (24) — resolveCanonicalExportMapping: full/plate-narrowed/single-colour/no-canonical export mapping cases, fileIndex-key sparse mapping, clamping, B106 STL non-canonical E2/E3/E4 slot remap, default E1 identity, physical/canonical G-code tool-space export and Upload Only handling
- `viewer/StlParserTest.kt` (10) — Binary/ASCII STL parsing, bounding box, vertex data, 10-float vertex format
- `viewer/MeshDataTest.kt` (12) — MeshData 10-float vertex format, extruderIndices, recolor(), RGBA values, multi-extruder recolor, F95 recolor paints trailing modifier block translucent (modifierBlockStartTriangle boundary) while model parts keep palette colour
- `network/MakerWorldUtilsTest.kt` (36) — URL parsing, design→instance ID resolution, download response parsing, error classification, cookie sanitization
- `network/MoonrakerClientTest.kt` (38) — PrinterStatus computed properties, URL normalization, LED state, remoteScreenUrl(), B33 virtual_sdcard progress parsing, sendGcode network path, queryWebcamSnapshotCandidates monitor.jpg appending
- `data/SliceConfigTest.kt` (25) — Default values match Snapmaker U1 hardware specs, wipe tower bounds clamping
- `data/DataClassesTest.kt` (17) — FilamentProfile, SliceJob, GcodeMove, ModelInfo, WipeTowerInfo
- `data/SliceJobMappingResolutionTest.kt` (10) — historical job export mapping: legacy physical rows, single-colour selected slot, canonical-wide CSV, blank/malformed fallbacks, physical/canonical G-code tool-space rows
- `data/PlateTypeTest.kt` (21) — PlateType.bedTempFor per-material presets, fromName, case-insensitivity
- `data/SlicingOverridesTest.kt` (108) — Override modes, JSON serialization round-trip, defaults, resolveInto(), multi-extruder wipe tower, B24 stale config, B31 brim_type, F30/F31 plus F41/F42/F43 override/file-value coverage, F57/F58 primeTowerWidth + wipeTowerRotationAngle, B53 computeTogglePrimeTower, B71 nozzle temp extruderTemps + nozzleTemps slice-time override, B79 resolveInto supportType/supportAngle, B100 buildProfileOverrides layer_height omitted for USE_FILE mode, B105 single-slot nozzle_temperature/filament_type 1-element guard, B125 support_filament emitted/omitted for Bambu file depending on OverrideMode, B125 sibling per-field OVERRIDE respected when supports=USE_FILE
- `data/SettingsBackupTest.kt` (15) — Export/import round-trip, version validation, partial restore, filament profile name resolution, stale skirt-loop import normalization, F76 legacy cookie key import regression
- `data/SessionStateTest.kt` (16) — F89 session-resume schema: toJson/fromJson round-trip (basic fields, FloatArray positions, empty/multi additionalFiles, all-nullables-null), malformed JSON returns null, missing version returns null, unknown future schema version returns null, past schema version returns null, missing required modelName/rawInputPath returns null, odd-length customObjectPositions array returns null, G-code tool-space round-trip/default
- `bambu/ThreeMfParserTest.kt` (12) - 3MF data model construction, isMultiPlate detection, hasPaintSupports field (B57)
- `bambu/BambuSanitizerTest.kt` (25) — INI config parsing, nil replacement, array normalization, filterModelToPlate, component size guard, group recentering
- `bambu/ProfileEmbedderTest.kt` (5) — convertToModelSettings: per-volume extruder preservation, remap, attribute order
- `bambu/LayerToolCustomGcodeXmlTest.kt` (3) — custom_gcode_per_layer.xml colour extraction for type 1 and 2 (parity with pause injector); per-plate parsing (parseLayerToolCustomGcodeXmlPerPlate)
- `ui/ExtruderAssignmentTest.kt` (6) — ExtruderAssignment defaults, copy, list building
- `ui/FilamentJsonImportTest.kt` (15) — JSON import parsing: snake_case/camelCase, defaults, errors
- `ui/MultiColorMappingTest.kt` (9) — ensureMultiSlotMapping collapse detection and sequential distribution
- `ui/PrinterStatusBadgeTest.kt` (14) — Printer status badge text, color, and icon mapping for all printer states
- `ui/ModelInfoDialogScrollTest.kt` (2) — B89 structural guard: ModelInfoDialog content Column applies `verticalScroll(rememberScrollState())` (source-grep test, no Compose UI harness in project)
- `FilePickerValidationTest.kt` (8) — isSupportedFile extension matching for 3MF, STL, OBJ, STEP; rejects unsupported types
- `model/CopyArrangeCalculatorTest.kt` (42) — Centered grid layout, bed bounds, copy capping, wipe tower auto-positioning, skirt clearance, B109 computeRotatedFootprint (5 rotation cases), B109 v2.2.6 effectivePlacementFootprint (6 cases: mesh-AABB priority, scale handling, null fallback, fallback applies scale, zero-rotation shortcut, Dragon-Scale-class divergence proof), F77 buildMultiObjectPositions (5 cases: empty, single, two-in-row, row-wrap, row-height tracking), F92 autoArrange (5 cases: two-object no-tower, reserved wipe-tower keep-out, overflow-never-off-bed, single-object clear-of-reserved, row-wrap)
- `ui/SendPreparingBannerTest.kt` (4) — F94 structural guard: PrinterViewModel `SendingState.Preparing` + `beginSendPreparing`/`reportSendError`; PrinterScreen Preparing card arm; ≥3 `beginSendPreparing()` + ≥3 `reportSendError(` calls across the MainActivity send sites
- `ui/UploadOnlyUxTest.kt` (6) — Upload Only confirmation dialog wiring, sliced-material display, physical tool-space threading for mix G-code, button label guard
- `UpgradeDetectorTest.kt` (15) — APK upgrade detection logic, version/timestamp comparison, file clearing patterns
- `DiagnosticsStoreTest.kt` (5) — Diagnostics event logging, JSONL output
- `MergeThreeMfInfoTest.kt` (49) — mergeThreeMfInfo/ForPlate objectExtruderMap preference, preview file selection, H2C source detection, SEMM extruderRemap suppression, isHueforgePlate classification (extruder diversity, plate-level paint data, uniform extruder, mixed-paint plates), B60 hasPaintSupports preservation, B82 per-plate layer-tool secondary colour matching (palette-match = real, off-palette = artefact)
- `printer/PrinterRepositoryTest.kt` (2) — upload filename sanitization and unique suffix generation
- `printer/PrinterRepositoryNotificationTest.kt` (9) — printer state transition detection for all event types
- `printer/PrinterViewModelTest.kt` (4) — camera keepalive idempotency helper and LED-sync connection-edge helper
- `AppEventNotifierTest.kt` (13) — notification title/body/channel/navigate-target for all event types
- `PreviewSummaryMappingTest.kt` (7) — preview summary data class mapping, F65 resolveExtruderMaterialType by slot, F68 single-colour material label
- `ComputePlateFileIndicesTest.kt` (25) — post-slice summary slot narrowing for Bambu/SEMM sparse canonical lists; B99 STL support/interface raw-G-code fallback; B121 buildWideGcodeMapping: single-STL+support, STL+support+interface, multi-STL+support, no-expansion guard, all-zero guard, sparse-active-slots guard; B120 filament_maps same-slot-assignment detects both file filaments
- `PreviewColorNormalizationTest.kt` (10) — preview colour normalization, B92 Buzz plate 8 slicer-tool-order palette alignment + identity + legacy paths
- `PreparePreviewPlacementTest.kt` (5) — native 3MF wipe tower visibility, object-placement rules, and large-preview fallback state retention
- `viewer/NativePreviewMeshTest.kt` (6) — preview budget guardrails, MAX_DECIMATED_TRIANGLES constant, F48 subsampled mesh vertex count, B88 toMeshData compaction contract, F95 toMeshData carries modifierBlockStartTriangle (set case + null-when-none case)
- `viewer/ModifierBlockRenderTest.kt` (3) — F95 structural guards: ModelRenderer `drawModifierTail` uses GL_BLEND SRC_ALPHA/ONE_MINUS_SRC_ALPHA + glDepthMask(false/true); single-mesh draws + outlines stop at `modelPartVertexCount(mesh)` and call `drawModifierTail`; MainActivity + ModelViewerScreen tag `modifierBlockStartTriangle` from `nativeGetPreviewModifierBlockStart()`
- `util/LocaleNumbersTest.kt` (7) — B139 `String.toFloatLenient()`: comma decimal parses as period, period unchanged, whitespace trimmed, whole number, negative comma, blank/whitespace→null, non-numeric→null
- `util/LocaleNumberWiringTest.kt` (3) — B139 wiring guards: all 5 user-typed decimal screens use `toFloatLenient`; OverrideFloatField uses it (not `toFloatOrNull`); `extractBambuValue` profile parsing stays locale-independent (`toFloatOrNull`)
- `viewer/NativePreviewMeshCompactionTest.kt` (6) — B88 `compactExtruderIndices`: sparse high indices → compact 0..N-1, already-compact no-op, sparse gaps, single index, empty input, full-byte range
- `viewer/SplitMeshByObjectsTest.kt` (3) — `splitMeshByObjects` AABB-first triangle classification: edge triangle of large object stays with owner not closer neighbour, well-separated objects, gap triangle nearest-centre fallback
- iewer/ModelViewerViewTest.kt (3) — Prepare selection falls back from face-plane to bed-plane hit-testing when needed

- `ui/MakerWorldBrowserUtilsTest.kt` (10) — sanitizeFilename path traversal, hasAuthCookies heuristic
- `WipeTowerClampTest.kt` (8) — wipeTowerClampBounds: pre-slice Y-clamp uses estimated depth not width; resolveWipeTowerWidth/resolveWipeTowerDepth: return active override or config default
- `data/WipeTowerDepthEstimatorTest.kt` (8) — height-based depth lookup table; primeVolume override wins when larger than height-based minimum
- `viewer/GcodeRendererGeometryTest.kt` (21) — segment packer: chain construction, shared vertices, travel breaks, turning angles (90°, straight, caps), z-offset, layer ranges, extruder/feature colors, brightness gradient, color encode/decode round-trip, texture dimensions, 400k stress test
- `gcode/LayerToolPauseInjectorTest.kt` (11) — PAUSE_PRINT injection for layer-tool colour swaps; includes 2 direct unit tests for extractPauseTargetsFromNativeJson (sub-plan #3 native-JSON path)
- `LargeModelLoadingMessageTest.kt` (5) — large model loading state messages
- `LongOpServiceStackTest.kt` (9) — F90 stack semantics: push/pop, top-of-stack replace via updatePure, empty-stack stop, parent reveal on nested pop, ACTION_STOP emission, concurrent push/pop pairing
- `SliceResultFromJobTest.kt` (2) — SliceResult construction from SliceJob
- `SaveGcodeStateResilienceTest.kt` (5) — 2026-05-26 0-byte-save guard: `_lastSliceResult` cache field declared; state observer mirrors SliceComplete into cache + clears `_parsedGcode`/`_gcodePreview` on transition away; `clearModel` drops cache; `saveGcodeTo` reads live-state-or-cache + toasts on every failure (no silent swallow); `shareGcode` same cache fallback
- `printer/PrintProgressNotifierTest.kt` (3) — print progress notification logic
- `PreparePreviewCacheTest.kt` (10) — B49 Prepare preview cache state machine: fresh load, tab switch cache hit, GL upload after cache hit, repeated effect dedup, parse effect cache guard, B59b togglePrimeTower cache invalidation contract
- `SingleColorExtruderConfigTest.kt` (6) — B56 single-color extruder selection filamentType propagation: E1-E4 material types, round-trip, missing preset fallback
- `FilamentTypeLabelTest.kt` (12) — B59 resolveFilamentTypeLabel: single-slot, all-same-material, mixed materials, edge cases (unknown slot, empty inputs)
- `FilamentTypeWiringTest.kt` (11) — B59 wiring: resolveFilamentTypeForSingleColorLoad, resolveFilamentTypeLabelFromMapping for multi-color and layer-tool paths
- `ui/HsvColorPickerTest.kt` (9) — F64 HSV↔hex color conversion round-trips: hsvToHex, hexToHsv, red/green/blue/white/black, inverse property
- `SliceStalenessTest.kt` (4) — F67 _sliceStale StateFlow contract: initial false, config mutation sets true, startSlicing resets false, extruderPresets drop(1) skips startup
- `SliceCancelTest.kt` (5) — B55 cancel state machine: SliceResult.cancelled field, Cancelling state singleton
- `FilamentTypeHeaderPatchTest.kt` (14) — B63 fixFilamentTypeHeader: single/multi-extruder replacement, absent line guard, empty list guard, missing file, first-occurrence-only, B99 header patch canonical padding for support/interface slots beyond canonical size, B102 sparse colorMapping produces physical-slot-indexed filament_type, B105 resolveNonCanonicalHeaderPatchTypes single/multi-slot, unknown-slot fallback
- `ui/SupportFilamentOptionTest.kt` (5) — B99 support/interface filament option labels and config values for H2C, STL, non-identity, and sparse color mappings
- `ui/SlicedWithMaterialTest.kt` (8) — B118 Map & Print dialog material-mismatch cascade: override priority, sliceTime-slot priority, file-declared fallback, DC15 single-colour PETG-slot repro, explicit override on single-colour, multi-colour mapped-slot resolution; B121 support-row uses displayFileIndex not slot-0 when colorMapping too short
- `ui/InlineModelPreviewRotationKeysTest.kt` (5) — B109 Compose-only structural guards: (a) placement LaunchedEffect lists `effPlaceSizeX/Y` so the object drag callback re-captures the rotated footprint on rotation, (b) placement LaunchedEffect lists `wipeTowerWidth/Depth` so the wipe-tower drag callback re-captures on prime-tower dimension change, (c) the `onMeshCached` lambda calls `setRotatedMeshSize` so the ViewModel's rotated-AABB cache repopulates after each mesh fetch, (d) F77 placement LaunchedEffect lists `perObjectSizes` so the drag clamp re-captures per-object footprints when files are added to the bed, (e) B124 perObjectSizes gated on hasMultipleDistinctObjects so single multi-volume 3MF doesn't trigger multiObjectMode. The math itself is unit-tested in `CopyArrangeCalculatorTest.effectivePlacementFootprint_*`.
- `ui/AiPaintViewerCameraResetTest.kt` (3) — Smart Paint camera-reset regression guards (v2.9.3): (a) the `mesh` remember must NOT key on per-paint `displayRegions`/`triangleRegions` (keying on it rebuilt the VBO on every paint → re-fired `LaunchedEffect(mesh)` → `applyCameraState(fitCamera)` → viewer snapped back to default orientation; regression from `fd59a72`), (b) `mesh` remember must key on geometry (`recenteredPositions`), (c) the per-paint recolor `LaunchedEffect` (keyed on `displayRegions`) must never call `setMesh`/`applyCameraState` — colours mutate in place via `updateExtruderIndices`. Source-grep guards; see BACKLOG A4 for the planned behavioural harness.
- `PrepareMeshCacheInvalidationTest.kt` (3) — B109 v2.2.6 lifecycle guard: `SlicerViewModel.invalidatePrepareMeshCache()` must clear `cachedPrepareMesh`, `cachedPrepareMeshPath`, AND `_rotatedMeshSizeXY` so stale rotated bounds never leak across a rotation change.
- `ui/PreparePreviewLongOpWrapTest.kt` (3) — F90 v2.7.1 follow-up structural guard: the `InlineModelPreview` `LaunchedEffect` that fetches `lib.getPreparePreviewMesh(...)` must (a) call `LongOpService.start(..., "Preparing preview")`, (b) pair it with `LongOpService.stop(...)` in a `finally` block, AND (c) place the `start(...)` call AFTER `kotlinx.coroutines.delay(300)`. Putting `start` before the debounce causes `ForegroundServiceDidNotStartInTimeException` under rotation-slider drag because each cancelled-mid-debounce LaunchedEffect still fires `startForegroundService` and Android's per-call 5-second watchdog can't be satisfied during the rapid cancel/restart churn.
- `network/UpdateCheckerTest.kt` (12) — F70 GitHub release JSON parsing, semantic version comparison, download URL extraction
- `NozzleTempDefaultTest.kt` (11+7=18) — nozzleTempDefaultForMaterial per-material defaults + ComputeFreshExtruderTempsTest: preset→temp lookup, filament profile ID priority, usedSlots remap, stale-config regression (v1.5.63)
- `bambu/BambuSanitizerMetadataPreservationTest.kt` (2) — B77: per-object non-extruder metadata (enable_support, support_type, seam_position, layer_height) preserved through sanitizer no-rewrite branch
- `bambu/NativePlateStateTest.kt` (7) — Native-first plate state JSON parsing: empty/null guards, single object, multi-object, paint flag detection, default-extruder fallback, buildObjectExtruderMap derivation
- `data/MixWeightsTest.kt` (12) — M4 N-way mix weight math: `even`, `normalize`, `rebalanceAfterType`, `rebalanceAfterDrag`, `addEven`, `remove`, sum-to-100 invariants, encode helpers
- `data/MixedFilamentRowMigrationTest.kt` (4) — M4 N-component `MixedFilamentRow`: derived accessors match first two components, `fromLegacy` reconstructs components/weights, `autoLabel` list form, init rejects bad component count and mismatched weights
- `ui/MixedSlotSwatchTest.kt` (1) — M4 N-segment swatch: `mixSegmentOffsets` cumulative start+fraction offsets for a 3-component weight list
- `ui/CreateMixSlotDialogLogicTest.kt` (2) — M4 Create/Edit mix dialog state helpers: add-component-then-type-weight keeps sum at 100; remove-component floors at 2
- `aipaint/FilamentMixPredictorTest.kt` (5) — pick-a-colour forward model: Kotlin port of prusa-fdm-mixer pinned to 7 reference vectors (ΔE<1), pure-endpoint short-circuit, blue+yellow→green (not grey), 3-way midtone, predictor differs from the naive sRGB average
- `aipaint/MixColourMatcherTest.kt` (8) — pick-a-colour reverse search: recovers a known 2-colour mix's subset+ΔE, respects requested count, caps count to loaded, closest-single-filament nearest, <250ms/query, empty-loaded clear error + sentinel, count=1 single component
- `ui/MatchAColourWiringTest.kt` (2) — pick-a-colour structural guard: `CreateMixSlotDialog` offers "Match a colour", calls `MixColourMatcher.bestMix`, has the 2/3/4 count selector, derives a closeness badge from `deltaE`, and surfaces the `closestSingleFilament` note
- `ui/MixBlendedColourTest.kt` (2) — pick-a-colour `mixBlendedColour`: cyan+yellow blends green-dominant (not the naive grey average), empty input → Gray; drives the blended-colour preview on mix-slot swatches + the Create-Mix editor
- `data/FilamentLibraryTest.kt` (11) — F96 OpenPrintTag library: parse (snapshot info, optional fields, nulls), entry lookup, search ranking (favourites→recents→alpha on blank query; all-token substring match; material filter; favourite-first; limit cap), malformed-JSON rejection, displayName
- `data/FilamentLibraryAssetContractTest.kt` (3) — F96 bundled asset guard: >10000 entries / ≥100 brands / count==size / commit+date stamped; every entry has slug/brand/name + well-formed #RRGGBB hex when present; prusament-pla-azure-blue round-trips (Prusament / PLA / #008FBE)
- `data/LibrarySlugListCodecTest.kt` (3) — F96 favourites/recents slug-list codec round-trip, blank→empty, malformed→empty
- `data/FilamentLibraryRecentsTest.kt` (3) — F96 updateRecents: new-first, move-to-front dedupe, MAX_RECENTS cap
- `data/FilamentLibraryImportTest.kt` (7) — F96 import mapping: hasImportableData gate, preview rows (en-dash ranges, g/cm³, TD/RI translucency note, single-ended ranges, colour-only→empty), libraryEntryToProfile midpoints + material defaults + re-import keeps id
- `data/FilamentLibraryMatcherTest.kt` (9) — F96 RFID sync matcher: exact brand+colour match, vendor normalisation both ways, ΔE>10 rejection, material mismatch rejection, unknown/missing vendor→null (incl. Snapmaker stock tags), subtype tie-break, colourless entries never matched, MAX_DELTA_E pinned at 10
- `printer/SyncPreviewBuilderTest.kt` (5) — F96 buildSyncPreviewEntries: matched slot carries catalogue name/colour/material, unmatched falls back to raw RFID values, null library = raw behaviour, missing slot → null news, 4 entries always built
- `ui/FilamentLibraryPickerStructuralTest.kt` (8) — F96 picker structural guards: search wiring, material chips, horizontal chip scroll + non-wrapping Use button, Failed-state retry, import affordance gated on hasImportableData, preview dialog rows, snapshot footer, star toggle
- `ui/FilamentLibraryTabWiringTest.kt` (7) — F96 Library tab ONLY in physical-slot contexts (AiPaint slot dialog + PrinterScreen slot editor; CreateMix + Prepare per-file dialogs stay HSV-only); no early-return past sibling composables (Compose group-balance crash class); SlicerViewModel applyLibraryPick + PrinterViewModel importLibraryProfile wiring
- `ui/FilamentSyncMatchWiringTest.kt` (3) — F96 sync dialog renders matched names; syncFilaments uses the pure builder; apply records library recents
- `ui/MixSwatchPaletteSourceTest.kt` (6) — B140/B142/B142b: chooser mix palette from printer slot presets (never file-resolved colours), filament chip resolves mix slots to blend colour, model mix blends from extruderPresets, post-slice G-code/summary use slot-space palette when the slice was mix-assigned
- `MeshPaletteLiveSourceTest.kt` (1) — B141: canonical Prepare recolor palette prefers the live per-volume extruder set (refreshed on assignment, includes mix ids) over static usedExtruderIndices

### Instrumented tests (`app/src/androidTest/`) - 433 tests across 61 classes
- `data/FilamentDaoTest.kt` (9) — Room DAO CRUD, ordering, count
- `data/FilamentLibraryAssetTest.kt` (2) — F96: bundled filament_library.json packaged in the APK parses at runtime (>10000 entries, count consistent; known Prusament entry present)
- `data/SliceJobDaoTest.kt` (8) — Room DAO insert, ordering, delete, sourcePath null default, round-trip, updateSourcePath
- `data/SessionStateRepositoryTest.kt` (4) — F89 DataStore round-trip: write_thenRead_returnsSameSessionState, read_emptyStore_returnsNull, clear_afterWrite_readReturnsNull, write_overwrites_prior
- `SessionResumeIntegrationTest.kt` (3) — F89 ViewModel restore flow: init_savedSessionWithExistingFile_exposesResumeOffer, init_savedSessionMissingFile_emitsToastAndClears, dismissSessionResume_clearsOfferAndDataStore
- `data/GcodeSaveTruncationTest.kt` (2) — Save truncation regression
- `native/NativeLibrarySymbolTest.kt` (6) — JNI symbol smoke tests
- `native/NativeLibraryCorrectnessTest.kt` (18) — JNI correctness checks + Phase 1 sub-plan #1 accessors (`nativeGetObjectCount`, `nativeGetVolumeCount`, `nativeGetObjectModelId`, `nativeGetVolumeScalars`, `nativeGetPaintStateCounts` for both mmu and supports kinds) + sub-plan #5 accessor (`nativeGetProjectConfig` populated JSON + null on no-model) + sub-plan #2b `loadModelForPlate` smoke (single-plate match + plateIdx=-1 all-plates alias) + F85 re-add regression (`addModelForPlate_readdWithSamePlate_givesConsistentObjectCount`) + F77 `addModel` object count + bounding boxes (2 STLs) + F77 `setModelScale` bbox-invalidation regression + F85 `addModelForPlate(plateIdx=2)` non-zero-plate coverage
- `native/NativePlateDataTest.kt` (5) — Phase 1 sub-plan #2 per-plate JNI accessors (`nativeGetPlateCount`, `nativeGetPlateData`): no-model null/zero, colored_3DBenchy single-plate shape, Buzz multi-plate positional sanity + OOR guard, flippy painted fixture customGcode non-empty
- `native/NativeObjectExtruderMapTest.kt` (3) — Phase 1 sub-plan #4 full-objects JNI accessor (`nativeGetObjectExtruderMap`): no-model null, colored_3DBenchy merged component-ref objects, Flarewing array length matches `nativeGetObjectCount`
- `slicing/SlicingIntegrationTest.kt` (50) — STL/3MF load→slice, temps, layer count, metadata, SlicingOverrides E2E, F57 rotation smoke test, rotation preview mesh invalidation, multi-object group rotation distance preservation, rotation cache skip, embedded rotation preservation, B55 slice cancel, v1.5.63 nozzle temp JNI path (PLA=220, PETG=235), B73 scale-down placement correctness, B75 parked extruder cooldown, B79 tree support type + filament type for STL, brim_type no_brim guard, resolveInto→JNI chain, B99 support/interface filament G-code guards including app-placed Benchy STL with PETG support E2/interface E3, F71 tetrahedron EXCLUDE_OBJECT_DEFINE in G-code, B107 bed temp no +5 bump, B106 machine_start_gcode injection, B106 send-time E3 remap T0→T2, B108 articulated fish scale-down model-on-bed, B108 skywing multi-object per-instance Z offsets bed-snapped after scale, F77 two-STL place-and-slice bed-bounds E2E
- `slicing/BambuPipelineIntegrationTest.kt` (41) — Multi-plate, dual/4-colour, sanitization, position-based plate extraction, B23 extruder map after restructure, per-part extruder parsing, B54 modifier volume subtype preservation, B82 per-plate layer-tool chip count (standard + painted flippy all plates), B99 Leo support fixture support/interface PETG regression, B100 layer_height sentinel respects embedded profile (die-single-colour.3mf), B104 single-plate Bambu plate-filter regression (Oreo+Proj+1.3mf), B120 jons-bug.3mf plate 2 detects both PETG+TPU filaments
- `slicing/SemmSlicingTest.kt` (11) — SEMM (paint data) slicing pipeline: 2-extruder + 4-extruder assertions, H2C benchy 7-colour G-code tool counts, SEMM tool remap guard, B64 Flarewing Dragon colour permutation remap, B99 support/interface PETG crash guards for colored and H2C Benchy, B122 crash regression for H2C shoe (TPU model + PLA support/interface) at 20% and 30% scale (T1>0 confirmed at 30%)
- `slicing/SensoryTwistSupportsTest.kt` (1) — B77 Sensory Twist Ball: paint_supports + per-object enable_support=1 emits Support features in G-code
- `slicing/NegativeVolumePreservationTest.kt` (1) — B137: negative/modifier volumes survive process()+embed() on a >64MB-main-model compound 3MF (streaming sanitize path). Asserts embedded model_settings.config keeps both `subtype="negative_part"` entries (RED=0 pre-fix) so the native BBS importer subtracts them instead of slicing solid
- `slicing/MixSlotNWayBlendGateTest.kt` (2) — M4 N-way engine gate: 3-component mix cycles 3 tools by weight; 4-component mix uses all four tools
- `slicing/TopSurfaceMixWipeTowerTest.kt` (2) — F97 wipe-tower-ON gate: wipe tower planned, dual-object plate, one object assigned a 2-component mix; at least one layer's `;TYPE:Top surface` block extrudes BOTH component tools (T2 + T3)
- `slicing/TopSurfaceMixModesTest.kt` (5) — F97 per-mix mode gates: proportional in-line boundary runs present; dither long runs absent (>2× dash = 0 for dither, >0 for stripes control); fine top lines narrower width; ironing glaze emits ironing tools; stripes control has long runs (correctness baseline)
- `slicing/StlMixPrimeTowerTest.kt` (1) — B145 gate: single-object STL + mix assigned → prime tower PRESENT when enabled (before fix, normalize_fdm_2 disabled it because extruders().size()==1)
- `slicing/PaintedMixTopSurfaceTest.kt` (2) — B146/B147 gate: model painted to a mix via Smart Paint (no nativeSetVolumeExtruder) must split the top surface within-layer for DITHER mode (B146) and STRIPES mode (B147); gate = >=1 layer with BOTH T2+T3 in `;TYPE:Top surface` blocks
- `MatchAColourE2ETest.kt` (1) — pick-a-colour end-to-end gate: a mix suggested by `MixColourMatcher.bestMix` slices into G-code using EXACTLY the suggested filaments (every suggested tool prints; non-suggested tools absent)
- `slicing/GoatDedupeSemmTest.kt` (1) — B76 Goat: user mapping [0,1,2,2] preserves all 4 paint states in embed; post-remap T3 absorbs into T2
- `slicing/ProfileEmbedderIntegrationTest.kt` (15) — ZIP validity, config keys, full embed→slice pipeline, re-embed regression guard (B24), sub-plan #2b plate-filtered `custom_gcode_per_layer.xml` (legacy drop + `plateId` single-plate filter)
- `slicing/BambuPlateStateRegressionTest.kt` (5) — Tier A regression tests for the 6 PM-reported plate state bugs: Dragon plate 3 / F1 calendar extruder counts (#1/#2), hanging file translate preserved through slice (#3), H2C benchy multi-tool G-code (#5), Buzz cold-load perf gate (#6)
- `slicing/BambuFixtureHarnessTest.kt` (6) — Tier B data-driven harness: one `@Test` per fixture so Orchestrator gives each its own process (slicing accumulates native memory; combining all 6 in one method OOMs). Validates extruder count, paint flag, per-tool G-code counts, and bounding box ceiling for Dragon Scale, Button-for-S-trousers, colored Benchy, Shashibo, slip-slide-spin, and flippy+flappy fixtures. 2026-05-03 note: current branch shows direct-harness hangs/crashes on Shashibo after release-equivalent harness restore; treat as a blocker/regression until explained, not an accepted skip. Also note the historical harness caveat: fixture JSON says `plateIndex`, but this class passes that value directly as `plateId`; release `v2.0.0` therefore passed these fixtures without proving true app plate 5 / plate 3 coverage. Real app-path Shashibo plate 5 coverage lives in `PreparePreviewViewModelTest#shashiboPlate5_selectPlate_appPathLoadsMultiExtruderPreparePreview`.
- `gcode/GcodeThumbnailInjectorTest.kt` (8) — 3MF image extraction, thumbnail blocks, G-code injection
- `viewer/NativePreparePreviewTest.kt` (20) — native Prepare preview regressions: dual-colour, painted, old asset, selected multi-plate spread, Dragon plate 3 colour preservation, H2C benchy full/decimated 7-index preservation + green recolor + interleaving guard, layer-tool Z-band recolor, triangle count cap, B51 old.3mf bounding box + Korok orientation, B72 multi-instance post-slice bounds, B78 Shashibo plate 5 file-scale+centre preservation on fresh load + post-slice dirty-path reset, B136 paint-preview SOLID (colored Benchy capped-count ≈ full-count, not stride-subsampled dots) + paint-preview bounds-sane (H2C Benchy AABB span <500mm, not QEM-exploded off-screen), F95 negative-volume fixture emits translucent modifier block (start≥0, tail triangles, recolor alpha<1) + dual-colour control reports modifier-block-start = -1
- `PreparePreviewViewModelTest.kt` (23) — Dragon plate 3 end-to-end Prepare state, slice-output colour coverage, H2C benchy full pipeline green verification, B47 colorMapping-before-ModelLoaded ordering contract, B83 plate-switch objectIds stable-source fix, B98/B78 Shashibo plate 5 app-path Prepare preview guard, entry-point equivalence for `loadModel(uri)` vs `loadModelFromFile(file)`, B86 S-Buttons user-like presets (E2=white/E4=pink) 4-distinct-colour guard, B92 Buzz plate 8 Prepare/Preview colour agreement with explicit slicerColorOrder permutation, B93 Buzz multi-plate cold load skips full-file embedProfile, B94 Spiderman drag-to-right preserved through slice, B92.1 parsedGcode + canonical preview palette: Buzz plate 8 surfaces canonical T5+T9 (no orphan slot-1/2 moves) and `normalizeGcodePreviewColors` resolves palette[5]=red + palette[9]=white, F73 plate change invalidates plates-available and cache, slip-slide plate 3 four-colour preview, B120 jons-bug plate 2 detects both PETG+TPU filaments via ViewModel path, B124 Button-for-S-trousers single-file load keeps hasMultipleDistinctObjects=false with multi-volume bboxes, B124 drag-to-right preserved through slice, B125 H2C shoe single-colour supportFilament=OVERRIDE(2) produces T1 in G-code (embedProfile targetCount expansion), F92 autoArrangeAll two-object no-overlap+on-bed, F92 autoArrangeAll single-file multi-object guard (does not enter multi-object placement → no N×N instance explosion)
- `ui/MakerWorldBrowserUtilsInstrumentedTest.kt` (6) — resolveDownloadFilename with URLUtil, RFC 5987, path traversal sanitization
- `B131B132B133DiagnosticTest.kt` (3 active + 1 ignored) — Diagnostic + regression suite for the 2026-05-31 Discord report cluster. B131 baseline state-snapshot (Ghostface single-object load) + B131 follow-up `@Ignore`d (fits-on-bed assertion currently fails: confirms decimation cap bypass returning 3.7M tris + mesh-Y-range past 270mm bed edge — un-ignore once B131 is fixed). B132a (Oreo wide-bbox copy-cap rejection: slice errors with "Only N copy fits" message instead of silently producing 1 cookie). B132b (post-`splitObject()` state consistency: `_copyCount=1`, `_multiObjectPositions` mirrors `customObjectPositions`).

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
- `ExtruderPickerRow` composable — row of 4 extruder chips (E1-E4) with color circles for single-color model extruder selection on Prepare screen
- `selectedExtruder` StateFlow on SlicerViewModel — tracks which extruder is selected for single-color models; triggers live recolor of 3D preview
- `objectExtruderMap` on `ThreeMfInfo` — `Map<String, Int>` of per-object extruder assignments parsed from `model_settings.config`; feeds the native per-triangle colouring path for multi-extruder Bambu models
- `NativeLibrary.loadModelForPlate(path, plateIdx)` (Phase 1 sub-plan #2b) — plate-aware 3MF loader. `plateIdx = -1` → BBS `plate_id=0` (all plates, same as `loadModel`); `plateIdx >= 0` → BBS `plate_id = plateIdx + 1` (1-based). Used by `SlicerViewModel.selectPlate` so the BBS importer filters `m_plater_data[plate_id].obj_inst_map` at load time (`bbs_3mf.cpp:1921`), eliminating the need for Kotlin to feed a pre-extracted single-plate 3MF to the native slicer.
- `ProfileEmbedder.embed(..., plateId: Int?)` — when `plateId` is supplied (from `SlicerViewModel.selectPlate`), `Metadata/custom_gcode_per_layer.xml` is filtered to the target plate via `BambuSanitizer.filterCustomGcodePerLayer` instead of being dropped. Keeps sub-plan #3's `LayerToolPauseInjector` XML fallback plate-scoped on painted multi-plate fixtures.
- `ThreeMfPlate.filamentIndices` vs `ThreeMfPlate.filamentMapSlots` (B120) — two semantically distinct fields parsed from `filament_maps` in `model_settings.config`. `filamentIndices` stores **1-indexed file-filament POSITIONS** of non-zero entries (for `computePlateFileIndices` which does `it - 1` to get 0-indexed canonical). `filamentMapSlots` stores **unique non-zero AMS slot VALUES** (physical extruder IDs for enrichment: `BambuPlateStateEnrichment`, `buildThreeMfInfoFromNative`, `mergeThreeMfInfoForPlate`, `buildSelectedPlateInfo`). For `filament_maps = "1 1"`: `filamentIndices = {1, 2}`, `filamentMapSlots = {1}`. Never pass `filamentIndices` to enrichment — it over-counts extruders when multiple file-filaments share one AMS slot.
- `ThreeMfPlate.hasPaintData` (Phase 1 sub-plan #2c) — per-plate paint flag populated by `ThreeMfParser.parse` / `parseForPlateSelection` from the `computeVisualColorCountByPlate` pass. Consumed by `SlicerViewModel.mergeThreeMfInfoForPlate` (B81 guard) and `SlicerViewModel.buildSelectedPlateInfo` — lets the plate-selection path derive plate-local paint state without running `BambuSanitizer.extractPlate` + `parseForPlateSelection` on disk.
- `SlicerViewModel.buildSelectedPlateInfo(sourceInfo, plateId)` (Phase 1 sub-plan #2c) — synthesises a single-plate `ThreeMfInfo` view from the stable source parse. Replaces the pre-#2c `BambuSanitizer.extractPlate` → `restructurePlateFile` → `ThreeMfParser.parseForPlateSelection(plateFile)` chain in `selectPlate`. `extractPlate` + `restructurePlateFile` are `@Deprecated` — production no longer reaches them; only dedicated Kotlin-pipeline regression tests still exercise them.

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

> **CRITICAL: Commit orca submodule source changes BEFORE deploying a new `.so`.**
> In 2026-05 the F71 patch (GCode.cpp EXCLUDE_OBJECT_DEFINE for all gflavors) was
> applied as uncommitted modifications, the .so was built and committed, but the
> source change was never committed to the orca fork. When v2.3.3 was rebased, the
> patch was silently lost — only caught by chance via test regression. **Two
> protections are in place; both ride on the same submodule-clean invariant:**
>
> 1. **`scripts/install-hooks.sh`** — run once per clone. Installs a pre-commit
>    hook that refuses to commit if `libprusaslicer-jni.so` is staged AND the
>    orca submodule has uncommitted changes.
> 2. **`scripts/rebuild-native-so.sh`** — wrapper for the rebuild flow that
>    refuses to copy the freshly-built .so into `jniLibs/` when the submodule
>    is dirty. Use this instead of manual `ninja + llvm-strip + cp` whenever
>    you can (it also runs the size / clang-17 / 16KB-align / JNI-count checks
>    automatically).

> **CRITICAL: Must use NDK 26 (Clang 17).** NDK 25 (Clang 14) produces different code generation
> for OrcaSlicer's paint segmentation, causing B62 regression (H2C benchy 436 vs 840 tool changes).
> Always verify the compiler: `llvm-readelf -p .comment libprusaslicer-jni.so` must show `clang version 17`.

> **CRITICAL: Always build with Release optimization.** Debug builds (`-O0`) produce a ~83MB `.so`
> (vs ~20MB Release) that is 3-5x slower and causes native OOM crashes on heavy multi-colour models.

> **CRITICAL: orcaslicer submodule must be initialised in the build worktree.**
> Worktrees do not auto-clone submodules — phase2 / refactor / hotfix worktrees
> typically have an empty `app/src/main/cpp/orcaslicer/` directory and only the
> .gitlink is tracked. Before any rebuild from a fresh worktree:
>
> ```bash
> git submodule update --init --recursive app/src/main/cpp/orcaslicer
> ```
>
> If you skip this, ninja silently links pre-cached `.o` files from a different
> worktree's source state and the `.so` may be missing JNI symbols (Buzz plate 8
> 2026-04-30 incident: rebuild from parent worktree with phase2's `.cxx` cache
> produced a `.so` without `nativeGetAllVolumeExtruders`, causing
> `UnsatisfiedLinkError` mid-load). Always rebuild from the worktree whose source
> matches the branch you intend to ship.

### Isolated worktree builds — one command (preferred for worktrees)

Historically only the `u1-slicer-orca` checkout could build the `.so`, because a
fresh worktree is missing THREE build inputs and the old workflow borrowed them:

1. the `orcaslicer/` submodule (gitlink only — empty until cloned);
2. ~1.8 GB of prebuilt arm64 deps under `extern/*/{include,lib}` — these are
   **gitignored binaries**, never committed, so checkouts don't get them;
3. an NDK26/Release `.cxx` cache, which binds to its own source tree.

`scripts/setup-worktree-native.sh` provisions all three so any worktree builds
on its own, with no borrowing:

```bash
scripts/setup-worktree-native.sh <worktree-path>   # defaults to the current worktree
scripts/rebuild-native-so.sh \
  <worktree-path>/app/.cxx/Release/<name>/arm64-v8a   # build + strip + deploy + verify
```

> **Tearing a worktree down? Run this FIRST:**
> ```bash
> scripts/setup-worktree-native.sh --teardown <worktree-path>
> ```
> It unlinks the extern junctions so the subsequent `git worktree remove` /
> `rm -rf` cannot recurse THROUGH them and silently delete the shared deps
> cache that every other worktree depends on. The cache is left untouched.

What it does: clones the orcaslicer submodule **from the `taylormadearmy/OrcaSlicer`
fork** (the pin lives there, and engine patches like ColorMix are fork-only) and
checks out the pinned SHA; creates Windows directory **junctions** from the
worktree's `extern/<dep>/{include,lib}` into a shared neutral deps cache
(`U1_DEPS_CACHE`, default `D:/projects/u1-native-deps-cache/extern`) so the 1.8 GB
is shared, not duplicated, and the junctions sit exactly on the gitignored paths
(no `git status` noise); copies the small `*_stub` shim dirs if the checkout
lacked them; then fresh-configures a worktree-local NDK26/Release build dir.

The shared cache is the single source of truth for every build input that is NOT
in git: the big prebuilt `extern/*/{include,lib}` (junctioned) AND the small
`*_stub` shim dirs (copied — their `include/` subdirs are caught by the
`extern/*/include/` gitignore rule, so they can't live in git without fighting the
negation; treating them like the big deps keeps provisioning uniform). The cache
is populated once from a known-good `extern/` — the prebuilt deps are NOT rebuilt
from source (`scripts/build_deps.sh` is the slow from-scratch path; the cache copy
is the fast one).

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
3. **CRITICAL when building from a worktree**: the existing build dir is bound to a specific source directory (see `CMAKE_HOME_DIRECTORY` in `CMakeCache.txt`). If you only modified `sapil_print.cpp` in the worktree but the worktree has additional/modified files in `app/src/main/cpp/src/` that the bound source tree is missing (Phase 1+ added `sapil_bambu_*.cpp/h`, `sapil_diagnostics.cpp/h` changes, `sapil_model.cpp` changes, `slicer_wrapper.cpp` changes, plus `CMakeLists.txt` and `include/sapil.h`), the resulting `.so` will be missing native methods and instrumented tests will fail with `UnsatisfiedLinkError`. **Always copy the full set of worktree-modified files (`diff -rq` between `cpp/src` and `cpp/include` and `CMakeLists.txt`) into the bound source tree before running ninja, then `cmake .` to rescan globs, then `ninja -j1`, then restore the source tree afterwards.** The pre-existing pattern of "copy only sapil_print.cpp, build, restore" is unsafe across worktrees with multi-file native diffs.
4. Run `ninja -j1` in the directory (OOMs at `-j2`+)
5. Strip: `$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/*/bin/llvm-strip --strip-unneeded libprusaslicer-jni.so`
6. Copy to `app/src/main/jniLibs/arm64-v8a/`
7. **Verify size**: stripped Release `.so` should be ~19-21MB. If it's 50MB+, you built with Debug — redo.
8. **Verify compiler**: `llvm-readelf -p .comment libprusaslicer-jni.so` must show `clang version 17.0.2`.
9. **Verify JNI symbol completeness**: `llvm-readelf --dyn-syms libprusaslicer-jni.so | grep Java_com_u1_slicer_NativeLibrary | wc -l` should match the count of `external fun` declarations in `app/src/main/java/com/u1/slicer/NativeLibrary.kt`. A mismatch means the build dropped JNI methods (often: a worktree-only source file wasn't picked up by CMake — see step 3).
10. `./gradlew clean installDebug` — incremental builds may cache old APK

### Fresh build (when no existing build dir works)

Create a new build directory configured directly for Release with NDK 26:

```bash
CMAKE=D:/Android/Sdk/cmake/3.22.1/bin/cmake.exe
NDK=D:/Android/Sdk/ndk/26.1.10909125
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
  -DCMAKE_MAKE_PROGRAM=D:/Android/Sdk/cmake/3.22.1/bin/ninja.exe \
  -DCMAKE_BUILD_TYPE=Release \
  -B"$BUILD_DIR" \
  -GNinja \
  -DSLICER_BACKEND=orca \
  -DANDROID_STL=c++_shared
```
Then follow steps 3-8 from "Using an existing build directory" above.
