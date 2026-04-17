# Backlog

Open bugs, features, and investigations. Everything else is done — see git log.

## Open Bugs

### B78: Shashibo plate 5 Prepare preview oversized + off-centre — PRE-EXISTING REGRESSION (since v1.5.65)
- **Symptom**: `Shashibo-h2s-textured.3mf` plate 5 shows a Prepare preview with the pyramid filling ~50–60 % of the bed, centred — while earlier versions (up to v1.5.64) showed the same 77×82 mm model at the correct ~28 % size in the upper-left quadrant (the plate's original position). Slice output is correct (551 CP TOOLCHANGE, T0=71, T1=69 — matches historical baseline).
- **Root cause**: The B73 fix (v1.5.65 commit bc2c76d) added `lib.setModelInstances(floatArrayOf(135f, 135f))` in `MainActivity.kt:2288` before `getPreparePreviewMesh()`. This re-centres the native model to bed centre for all plates, overriding plate-defined positions. The GL camera auto-fit then zooms tighter because the mesh is closer to the camera's lookAt point.
- **Bisect**: PASS on v1.5.48, v1.5.55, v1.5.60, v1.5.64. FAIL on v1.5.65+ (all the way to current main).
- **Handoff spec**: `docs/superpowers/specs/2026-04-17-b78-shashibo-prepare-oversize.md`
- **Priority**: visual-only; slicing works correctly. Queue for v1.5.70.
- **Source**: surfaced during v1.5.69 E2E batch on 2026-04-17

### B76: Goat ( Gray ).3mf — horns print in E1 filament when E4 set to match E3 — FIXED v1.5.69
- **Symptom**: 4-extruder per-object Bambu model with paint_color triangles. If the user sets E4's colour to match E3's (colour mapping `[0,1,2,2]`), the parts that should print on E3 instead come out in E1's filament. Works correctly with all 4 extruders different.
- **Root cause**: `computeEmbedTargetCount` returned `distinctSlots` (3) for non-H2C SEMM with duplicate-slot mapping. The 3MF was re-embedded with only 3 filament slots, silently dropping the 4th paint state. Per-object parts with `extruder="4"` landed on an out-of-range filament.
- **Fix**: `computeEmbedTargetCount` now uses `colorMapping.size` when the model is hybrid (paint + per-object extruder assignments) AND the user has collapsed exactly one state (`size - distinct == 1`). Pure SEMM models keep the distinct slot count so the post-slice remap stays accurate (regression guard for `old.3mf` after the initial over-broad fix).
- **Tests**: 1 new unit test (`B76 Goat — 4 colours 3 distinct slots uses full size`); 3 existing `computeEmbedTargetCount` tests updated to assert unified behaviour; 1 instrumented test (`GoatDedupeSemmTest`) verifies T0-T3 all present pre-remap and T3 absorbs into T2.
- **Source**: Discord user Jon (2026-04-16)
- **Test file**: `Goat ( Gray ).3mf`

### B77: Bambu 3MF per-object overrides dropped by BambuSanitizer (Sensory Twist Ball supports missing) — FIXED v1.5.69
- **Symptom**: Sensory Twist Ball 3MF prints with zero supports in U1 Slicer despite 2870 `paint_supports="4"` triangles on the mesh and per-object `enable_support=1` override in `model_settings.config`. Bambu Studio slices the same file with 334 Support + Support interface features.
- **Root cause**: `BambuSanitizer`'s "no extruder-based rewrite needed" branch (taken for single-object single-extruder files) was a literal no-op — the source `model_settings.config` was buffered but never written to the sanitized output. OrcaSlicer had no per-object overrides to apply and fell back to the project-level `enable_support=0`.
- **Fix**: Write the buffered `modelSettingsContent` through verbatim in that branch so OrcaSlicer's per-object config layer sees all the overrides set via Bambu Studio's Objects tab.
- **Tests**: 2 new unit tests (`BambuSanitizerMetadataPreservationTest`); 1 instrumented test (`SensoryTwistSupportsTest`) verifying the full slice produces >0 Support features from paint_supports + per-object enable_support=1.
- **Source**: Discord user DC15 (2026-04-16)
- **Test file**: `SENSORY+TWIST+BALL+FIDGETS+optimised.3mf`
- **Known limitation**: Per-object overrides on multi-extruder files that *also* need restructuring (non-trivial compound objects) are still handled via the rewrite path and may drop non-extruder metadata. Follow-up for a future release.

### B62: H2C SEMM segmentation regression — colours only on top surfaces, not sides (GitHub #69) — FIXED v1.5.51
- **Root cause**: The B55 cancel `.so` rebuild used NDK 25 (Clang 14) instead of NDK 26 (Clang 17). Clang 14
  generates different code for OrcaSlicer's `MultiMaterialSegmentation.cpp` floating-point paint segmentation,
  producing 436 tool changes (colours only on tops) vs 840 (full side-wall coverage) from Clang 17.
- **Fix**: Rebuilt `.so` with NDK 26. Updated build docs (CLAUDE.md + README.md) to require NDK 26 with
  compiler verification step (`llvm-readelf -p .comment` must show `clang version 17`). Strengthened
  `SemmSlicingTest` to assert >600 total tool changes, catching future NDK regressions.
- **Tests**: 753 unit + 163 instrumented + 17/17 E2E PASS. H2C benchy: 838 tool changes.

### ~~B66: Color picker slider resets hue when adjusting shade (GitHub #70)~~ FIXED v1.5.55
- Stale closure in HSV picker caused hue to reset when shade was adjusted
- **Fix**: Fixed stale closure in `fix(B66,B65,B63)` commit; shipped in v1.5.55

### ~~B65: Copies stuck at 1 on multi-colour 3MF — Flarewing Dragon (GitHub #71)~~ FIXED v1.5.55
- Copy count cap used unscaled model dimensions and hard-blocked instead of warned
- **Fix**: Fixed in `fix(B66,B65,B63)` commit; shipped in v1.5.55

### B64: SEMM colour mapping not applied to G-code — wrong colours printed (GitHub #72) — FIXED v1.5.52
- User's colour-to-extruder mapping (e.g. Color 1→E4) was displayed in UI but never applied to the G-code for SEMM paint models
- **Root cause**: `applyMultiColorAssignments` checked if `usedSlots` was identity `[0,1,2,3]` and set `toolRemapSlots=null`, ignoring the permutation ORDER of the colorMapping. OrcaSlicer outputs T0-T3 matching the 3MF filament order, not the user's assignment
- **Fix**: Added dedicated `semmColorPermutation` field (separate from `toolRemapSlots`) that records the colour order permutation for SEMM models. Applied post-slice via `composeSemmRemap()` + `GcodeToolRemapper`. H2C models untouched.
- **Tests**: 11 unit tests (`SemmColorPermutationTest`), 1 instrumented test (Flarewing Dragon permutation remap). 764 unit + 164 instrumented all pass.
- **Test file**: Flarewing Dragon 4-colour 3MF
- **Source**: Discord user Jon (2026-04-14)
- **Related**: May improve B58 (#60) G-code preview colour mismatch as a side effect

### ~~B73: Scale-down produces wrong slice position + double-scaled Prepare preview (GitHub #79)~~ FIXED v1.5.65
- **Slice position**: Scaling a model down before slicing places the G-code at the wrong position on the bed (shifted back/right). Models appear correctly placed on screen but print at an offset location.
- **Prepare preview double-scale**: After slicing at a reduced scale, returning to the Prepare tab shows the preview mesh at the wrong (much smaller) size. The GL renderer and the native mesh both apply the scale factor independently → s² visual size instead of s.
- **Root cause (slice position)**: `setModelInstances()` single-object branch computed `offset = pos - meshBB.min`, ignoring the instance scale. The correct formula is `offset = pos - scale * meshBB.min`. For any model with a non-zero mesh origin and scale ≠ 1.0, the model lands at the wrong world position.
- **Root cause (preview double-scale)**: `prepareSlicer()` calls `native.setModelScale(s)`, permanently scaling the native model geometry. `getPreparePreviewMesh()` returns the already-scaled mesh. The GL renderer also applies `renderer.modelScale = s` → double-scaled (s²) visual size.
- **Fix**: (1) `sapil_arrange.cpp` — use `trafo.get_scaling_factor()` to multiply meshBB.min before offset computation. (2) `MainActivity.kt` — add `lib.setModelScale(1f, 1f, 1f)` before `getPreparePreviewMesh()` in the Prepare preview LaunchedEffect (alongside the existing B72 instance reset).
- **Tests**: `SlicingIntegrationTest.threeMf_scaledDown50pct_gcodeIsCenteredOnBed`, `SlicingIntegrationTest.setModelInstances_withScale_placesInstanceAtCorrectOffset`

### B72: Prepare preview corrupted (shattered mesh) after scale + copies + slice (GitHub #78)
- After increasing model scale, increasing copy count, slicing, then returning to Prepare tab, the preview mesh looks geometrically shattered. The slice output is correct.
- **Root cause**: `setModelInstances()` is called during `prepareSlicer()` with N grid positions. The scale change clears the prepare-preview cache but the cache is never repopulated (LaunchedEffect key=modelRotation doesn't change). On tab return the composable is recreated with a null cache, triggering a fresh `getPreparePreviewMesh()` on the post-slice native state which has N instances set — returning all N copies baked in world-space. The GL renderer then also applies instancePositions for N copies → N×N corruption.
- **Fix**: Reset to single centred instance (`setModelInstances(floatArrayOf(135f, 135f))`) before calling `getPreparePreviewMesh()` in the Prepare preview LaunchedEffect.
- **Test**: `NativePreparePreviewTest.getPreparePreviewMesh_afterMultiInstanceSliceState_singleInstanceResetGivesCorrectBounds`

### B68: Printer offline notification shown during printing — misleading text (GitHub #75)
- While a print is actively in progress, the app shows a "printer offline" notification
- May be unavoidable (Android limits background WebSocket connections), but text is misleading — implies the printer went offline rather than that the app lost its monitoring connection
- **Suggested fix**: Change notification text to "Press to connect to see printer status" (or similar)
- **Source**: Discord user Jon (2026-04-14)

### B67: Import configuration only partially connects printer — camera doesn't show (GitHub #74)
- After importing a printer configuration (QR code / settings import), printer appears connected but live camera feed does not load
- User must navigate to Printer Settings and tap Connect manually to fully connect
- **Expected**: Import should result in a fully connected printer (camera + status working)
- **Source**: Discord user Jon (2026-04-14)

### B63: Reprint G-code sends PLA filament type instead of actual loaded material (GitHub #73) — FIXED v1.5.56
- Root cause: `filament_type` not in native `profile_keys[]` whitelist; slicer always emitted PLA in G-code header despite correct embedded profile
- Fix: post-slice G-code header patch — `fixFilamentTypeHeader()` replaces `; filament_type = PLA` with actual per-extruder material types from extruder presets
- **Source**: Discord user Jon (2026-04-14)

### B58: SEMM painted model preview colours don't match sliced output or desktop OrcaSlicer (GitHub #60)
- For `colored_3DBenchy (1).3mf` (4-colour SEMM), the Prepare preview, G-code preview, and desktop OrcaSlicer all show different colours
- **Prepare screen**: Only 2 colour chips shown; model renders mostly white/gray — 2 of 4 paint zones missing
- **G-code preview**: More colours visible in toolpath render but different distribution from desktop reference
- Not a slicing correctness issue (all 4 extruders active in G-code), but gives user a misleading picture
- **Affects**: All SEMM painted models (`hasPaintData=true`)
- **When fixed**: restore CP TOOLCHANGE~27 assertion in the `colored_3DBenchy (1).3mf` E2E check (skill file + memory `e2e-testing.md`) — it was suppressed due to this bug

### B54: Modifier volumes rendered as solid geometry in Prepare preview (GitHub #55) — FIXED
- **Root cause**: `BambuSanitizer.buildOrcaModelConfig()` hardcoded `subtype="normal_part"` for all `<part>` entries, overwriting `"modifier_part"` from the original 3MF. Also `needsModelConfig` only checked `extruder > 1`, so single-colour files with modifiers got no config at all. OrcaSlicer's BBS loader then defaulted all volumes to `MODEL_PART`, making the modifier cube appear as solid geometry.
- **Fix**: Added `subtype` field to `PartInfo` data class; parse `subtype` attribute from `<part>` elements in `parseModelSettingsExtruders`; preserve through `buildOrcaModelConfig`; expand `needsModelConfig` to trigger when any part has non-normal subtype; attach compound component IDs for modifier files. Also preserves subtype during `restructureForMultiColor` inlining, setting `type="other"` in the 3D model XML for non-model-part volumes.
- **Tests**: 1 new instrumented test in `BambuPipelineIntegrationTest.kt` — verifies config preserves `modifier_part` and native preview has 15,642 tris (model only, modifier excluded)
- **Affects**: u1-auxiliary-fan-cover-hex_mw.3mf, citystep (any 3MF with modifier/settings-override volumes)

### B55: Crash / freeze when loading new model while large preview QEM is running (GitHub #57) — FIXED v1.5.49
- **Root cause**: `clearModel()` acquired `previewMutex` while `getPreparePreviewMesh()` QEM decimation was running (30+ seconds for large models), either causing SIGSEGV or 30-second stall
- **Fix**: Added `std::atomic<bool> g_preview_cancel` flag checked every QEM iteration via `its_quadric_edge_collapse`'s `throw_on_cancel` callback + volume loop + MMU interleave loop. `cancelPreviewMesh()` JNI method sets the flag; `clearModel()` calls it before acquiring mutex. QEM bails out in microseconds.
- **Also fixed**: Slice cancellation upgraded from soft cancel (native runs to completion, result discarded) to hard cancel via OrcaSlicer's built-in `Print::cancel()` + `throw_if_canceled()` mechanism. New `SlicerState.Cancelling` shows honest "Cancelling..." UX until native confirms stop. `g_slice_cancel` atomic flag handles cancellation during config setup before `print.process()` starts.
- **Tests**: 5 unit tests in `SliceCancelTest.kt`, 1 instrumented test in `SlicingIntegrationTest.kt`

### B52: Crash at end of slicing citystep_A1_274_102.3mf (GitHub #51) — FIXED
- **Root cause**: Two OOM sources in post-slice G-code processing for very large files (115 MB, 3.7M moves):
  1. `buildSuspiciousModelLineContexts()` called `File.readLines()` loading the entire G-code file into a `List<String>` (~200 MB heap)
  2. `GcodeParser.parse()` stored all 3.7M `GcodeMove` objects (~220 MB) with no cap
- **Fix**: (a) Extracted `buildSuspiciousModelLineContexts` to a streaming implementation that reads only the ±2 line windows around suspicious samples; (b) Added `maxMoves` cap (default 2M) to `GcodeParser.parse()` — moves beyond the cap are still counted for summary/validation but not stored, keeping heap under control; layer count and filament tracking remain accurate even when capped
- **Tests**: 6 new unit tests in `SuspiciousLineContextTest.kt` (streaming correctness, window clamping, large file smoke); 4 new unit tests in `GcodeParserTest.kt` (move cap, stride distribution, uncapped baseline, per-extruder filament tracking with cap)

### ~~B51: SEMM Prepare preview broken — wrong orientation, tiny, split geometry (old.3mf, Korok mask)~~ FIXED
- **Root cause**: B46 removed `instance_matrix` from the MMU path in `getPreparePreviewMesh()` ("Kotlin handles bed positioning") but kept it in the non-MMU path. This left SEMM volumes in model-local coords while non-MMU volumes were in world coords. old.3mf's instance had a rotation that the missing transform didn't apply, making the model lie flat (Z=21.6mm instead of 126.8mm).
- **Fix**: restored `its_transform(its, instance_matrix, true)` in the MMU path + reverted B48 manual extraction back to `appendItsPreviewMesh()` for consistency. 
- **Tests**: 3 new instrumented tests in `NativePreparePreviewTest.kt` — old.3mf bounding box (Z >= 50mm) + Korok mask orientation (Z < 20mm, XY >= 50mm)

### ~~B48: H2C benchy — slicer + Prepare preview + cache~~ FIXED
- **Slicer FIXED**: `computeEmbedTargetCount()` uses `colorMapping.size` (7) not `distinct().size` (4). G-code now has T0=120, T1=239, T2=242, T3=121.
- **Prepare preview FIXED**: shader `uniform int` → `uniform float` (Mali-G715 bug), config order restored (embedded profile → applyConfigToPrusa), MMU triangles interleaved round-robin so all 7 colours survive VBO truncation.
- **Prepare preview cache FIXED**: `cachedPrepareMesh` on ViewModel + native `g_preview_mesh_valid` provide instant reload on tab switch.
- **G-code preview colours**: tracked separately as B50.
- Long-term MMU decimation tracked in taylormadearmy/u1-slicer-for-android#50

### B50: G-code preview colours don't match Prepare preview for SEMM models (GitHub #53)
- After slicing H2C benchy, the G-code preview shows all 4 colours (red, green, blue, white) but some are swapped compared to the Prepare preview
- The Prepare preview uses `colorMapping` (model-colour→slot) to assign colours to paint state indices — this matches the Bambu reference
- The G-code preview uses physical tool indices (T0-T3) which are assigned internally by OrcaSlicer's `multi_material_segmentation_by_painting()`. This mapping is opaque — we don't control which model colour ends up on which physical tool
- **Root cause**: the slicer's paint-state→tool mapping is different from our Prepare preview's model-colour→slot mapping. The colourMapping describes intent (model colour 5 → extruder slot 1 = green), but the slicer may assign those triangles to T2 instead of T1
- **Orange leak**: when all extruders set to blue, T0 sometimes shows orange (GcodeRenderer default colour at `GcodeRenderer.kt:68`). Likely `setExtruderColors` receives blank/empty entries that don't override the defaults
- **Fix needed**: either (a) extract the slicer's actual tool→model-colour mapping from the G-code (e.g. from filament_colour comments) and use it for G-code preview colours, or (b) accept the slicer's mapping and colour the G-code preview based on what's in the G-code
- Prepare preview is correct — this is only a G-code preview issue

### ~~B49: Prepare preview slow reload after G-code view~~ FIXED v1.5.38
- `cachedPrepareMesh` on ViewModel + native `g_preview_mesh_valid` cache provide instant reload on tab switch
- Confirmed fixed on device v1.5.39

### ~~B45: Prepare preview for painted/SEMM 3MF models looks broken — wireframe/sparse rendering (GitHub #49)~~ FIXED v1.5.38
- B46 switched painted models to native `getPreparePreviewMesh()` path with stride=1 (no decimation), producing solid previews
- E2E confirms solid rendering for colored_3DBenchy, H2C benchy, old.3mf, Korok mask

### ~~B42: G-code preview tubes appear as flat rectangles, not rounded tubes (GitHub #46)~~ FIXED v1.5.37
- Hex cross-section (18 vertices) replaced box cross-section; E2E F59 confirms solid tube rendering

### ~~B43: G-code preview lighting too dark — models appear almost black (GitHub #47)~~ FIXED v1.5.37
- AMBIENT 0.20→0.35, DIFFUSE_TOP 0.65→0.75; E2E confirms visible coloured ribbons

### ~~B44: colored_3DBenchy.3mf shows only 3 colours instead of 4 (GitHub #48)~~ FIXED v1.5.38
- `computeEmbedTargetCount()` fixed + TriangleSelector H2C fold; E2E confirms T0=2 T1=5 T2=3 T3=2 (all 4 extruders)

### B40: F60 Jobs tab G-code viewer shows "No slice results" after kill + reopen (GitHub #44) — FIXED v1.5.34
- Root cause: gcode saved to transient path lost on process kill; `_gcodePreview`/`_state` reset to empty
- Fix: save gcode to durable per-job path `files/jobs/<id>/output.gcode`; delete files on job removal

### B39: Printer offline notification fires on transient WiFi blips during print (GitHub #43) — FIXED v1.5.34
- Fix: grace-period counter; only transition to offline after N consecutive failures (~3, ≈15–30 s)

### B41: 3MF files with embedded build-item rotation show wrong orientation in Prepare preview — FIXED v1.5.35
- Root cause: `setModelRotation(0,0,0)` overwrote the instance's embedded rotation (e.g. 90° Z from F1 calendar 3MF build-item transform)
- Fix: capture base rotations on first call, compose user rotation on top
- Also fixed: preview flipping 90° on tab switch (race condition between setModelRotation and getPreparePreviewMesh)

### B38: Post-upgrade native slicing failure — FIXED v1.5.0
- Root cause: `Print::m_origin` (Vec3d) uninitialized — on Android `set_plate_origin()` is never called, so release builds read garbage (sometimes `-inf`) for the Y component, corrupting all wipe tower travel moves
- Secondary: `Print::m_isBBLPrinter` (bool) uninitialized — release builds sometimes selected the wrong BBL wipe tower
- Fix: default initializers for both members + audit of other uninitialized fields in Print-related classes
- Full investigation history in [`CLIPPER_UPGRADE_INVESTIGATION.md`](CLIPPER_UPGRADE_INVESTIGATION.md)

### B34: Printer light button icon confused for app theme toggle (GitHub #7) — FIXED
- The button used `Icons.Default.LightMode` (sun) / `Icons.Default.DarkMode` (moon) — identical to Android's theme-switch icons
- Fixed: changed to `Icons.Default.Lightbulb` (yellow when on, dimmed when off) — clearly a physical light, not a UI theme toggle
- There is no app-level light/dark theme switch; the app is dark-only

### B18: OOM on large/complex 3MF files (GitHub #17) — FIXED v1.4.27
- ZIP entry size preflight rejects obviously oversized archives before sanitize/embed/native load
- Large models fall back to simplified top-down bed preview instead of crashing
- Both known repros (F1 calendar 103 MB, super clean.3mf 58 MB) verified fixed on Pixel 8a
- Issue #17 closed.

### B31: First slip/slide slice can hit a native Clipper range crash — FIXED v1.4.20
- Root cause: ARM64 FCVTZS saturation producing INT64_MIN/MAX in Clipper `IntersectPoint` vertical-edge paths and `Round<int64_t>()` for large-but-finite doubles
- Kotlin fix: clamp wipe tower to bed bounds before slicing; persist clipper recovery flag across restart to prevent crash-loop
- Native fix: overflow guards in `IntersectPoint` (vertical-edge + general-case `Dx*q+b`) and `Round()` (large double detection), all falling back to scanbeam position
- v1.4.21: clear stale clipper markers on APK upgrade to prevent false crash report after updating

### B36: MakerWorld download/loading text is unclear — FIXED v1.4.22
- Status messages during MakerWorld import were confusing (e.g. "Loading Downloading from MakerWorld……")
- Fixed: each loading state now provides a complete display message ("Downloading from MakerWorld…", "Loading model.3mf…", "Preparing model…")

### B39: Plus/home screen shown during slow model load, causing confusion and double-loads (GitHub #30) — FIXED v1.5.13
- On large files or slower devices, the plus screen remained visible while the model loaded in the background
- Fix: `setLoadingFromPicker()` emits `SlicerState.Loading` immediately on file selection before filename resolution
- Issue #30 closed.

### B37: Hueforge filament pauses / colour changes are dropped when sliced in-app (GitHub #21) — FIXED v1.5.x
- Root cause: layer-tool pause injection pipeline was not implemented; `custom_gcode_per_layer.xml` layer-change data was ignored
- Fix: full layer-tool pipeline implemented in v1.5.1–v1.5.11: detects `hasLayerToolChanges`, extracts per-layer extruder assignments from XML, injects `PAUSE_PRINT` + `M109 S{temp} T{n}` at the correct layer heights post-slice
- Issue #21 closed. Follow-up UX parity (preview colours, summary usage) tracked under F46/F47.

## Open Cleanup

### C1: Remove dead warm-reload and upgrade-guard machinery — FIXED v1.5.13
- Removed `sessionHasPostUpgradeGuard`, `firstSliceAfterUpgradeRecorded`, `markSliceSucceeded()`, and `post_upgrade_slice_settled` event from `DiagnosticsStore`
- `clipperRetryAttempted` left untouched — still active for non-upgrade Clipper errors
- Issue #19 closed.

## Open Features

### F70: Check for Updates (GitHub #68) — DONE v1.5.49
- "Check for Updates" button in Settings About section; queries GitHub Releases API (`/releases/latest`), compares to `BuildConfig.VERSION_NAME`, shows inline result
- Download link shown when update is available; Idle → Checking → UpToDate/Available/Error state machine
- Tests: 12 unit tests in `UpdateCheckerTest.kt`
- Issue #68 closed.

### F69: 3D viewer thread-safety hardening (GitHub #67) — DONE v1.5.47
- `@Volatile` on all Camera scalar fields; `pendingCameraState` on ModelRenderer consumed in `onDrawFrame`; `resetView()` and `applyCameraState()` routed through GL thread; `modelScale` immutable-array contract documented; Camera scalars converted to `Double` (downcast to Float at shader upload)
- Issue #67 closed.

### F49: Reset-view button on Prepare and Preview 3D viewers (GitHub #31) — FIXED v1.5.15
- FilterCenterFocus icon at bottom-end of both Prepare and Preview 3D viewers
- Clears shared camera state to reset to default view
- Issue #31 closed.

### F46: Prepare preview colours for layer-tool / Hueforge models (GitHub #26) — FIXED v1.5.13
- 3D mesh preview now recoloured by Z-band using `MeshData.recolorByZBands()` when `layerToolOnly=true`
- `parseLayerToolSegments()` shared parser extracted to `LayerToolCustomGcodeXml.kt`; `LayerToolPauseInjector` delegates to it
- Issue #26 closed.

### F47: Slice summary filament usage for layer-tool / pause-based prints (GitHub #27) — FIXED v1.5.x
- `GcodeParser` accepts `colorSegmentsByPausePrint=true` which assigns filament mm to extruder indices by counting `PAUSE_PRINT` markers instead of T commands
- Set automatically in `SlicerViewModel` when layer-tool pause injection ran — summary correctly shows per-extruder breakdown for Hueforge prints
- Issue #27 closed.

### F48: Better Prepare preview for very large 3MF models (GitHub #29) — DONE v1.5.22
- Native C++ per-volume QEM decimation with 10-second time budget — large volumes get QEM'd until deadline, then remaining fall back to stride
- Small volumes (<1000 tris) pass through untouched — preserves base plates, frames, and other low-poly construction geometry
- Degenerate triangle filter: zero-area triangles skipped before stride counting so spatial sampling is even
- Flat model detection (height/footprint <5%): auto-selects 500K triangle budget (vs 100K default)
- Preview mesh caching: result cached after first computation, returned instantly on tab switch; invalidated on model load/clear/scale/instances change
- F1 calendar (8M triangles): loads in ~90s, QEM preview ~500K tris with visible track outlines and text
- Kotlin path (painted/SEMM models) still uses stride decimation; cap at 500K so typical painted models pass through untouched
- **Remaining (low priority):** painted models >500K tris still use stride; could route through native path long-term
- Track: [`#29`](https://github.com/taylormadearmy/u1-slicer-for-android/issues/29)

### F45: Bambu printer support (GitHub #16)
- Add support for Bambu Lab printers (communication protocol differs from Moonraker)
- Consider allowing upload of arbitrary OrcaSlicer printer configs
- Backburner: Bambu's 2025 auth changes appear to block third-party LAN print-start on secured firmware unless the printer is put into `Developer Mode`
- Likely viable scope is only `LAN + Developer Mode`; stock secured firmware support is probably not realistic for this Android app
- Monitoring-only support may be possible without Developer Mode, but direct send/start is the critical blocker
- Significant scope — needs investigation of Bambu MQTT/cloud protocol and an explicit product decision on whether `Developer Mode` is acceptable

### F14: Mixed-colour / pseudo-extruder support (FullSpectrum fork) (GitHub #18)
- Source: ratdoux/OrcaSlicer-FullSpectrum — fork of Snapmaker Orca 2.2.4
- Produces optically-blended colours via layer-cycle alternation (e.g. Blue+Yellow→Green)
- **Blocked**: upstream was v0.9.4 alpha as of 2026-03-12, untested on real hardware
- Wait for v1.0 / hardware-verified release before porting
- Requires native .so rebuild

### F50: Printer temperature control during printing (GitHub #22) — FIXED v1.5.15
- Inline temperature editing on TempTile — tap edit icon to adjust bed/extruder temps mid-print
- Sends Moonraker `SET_HEATER_TEMPERATURE` G-code; clamped to safe ranges (bed 0-120°C, extruders 0-300°C)
- Issue #22 closed.

### F51: Fullscreen printer camera feed (GitHub #23) — FIXED v1.5.15
- Fullscreen button on camera feed opens a Dialog filling the screen; MJPEG polling continues uninterrupted
- Issue #23 closed.

### F52: Colour-by-layer swaps preview for single-colour prints (GitHub #24)
- On the Preview screen, show colour changes for single-colour prints that use layer swaps (manual filament swap at layer)
- Related to the layer-tool pipeline but for single-extruder machines
- Track: [`#24`](https://github.com/taylormadearmy/u1-slicer-for-android/issues/24)

### F53: Expanded notification coverage (GitHub #13) — FIXED v1.5.15
- 9 event types: slice complete/failed, upload complete, print started/paused/complete/failed, printer offline
- Background-only gating via ProcessLifecycleOwner; deep-link navigation to relevant tab on tap
- Issue #13 closed.

### F54: AI colouring for single colour prints (GitHub #33)
- Automatically generate multi-colour designs from single-colour STL/3MF models using AI
- When a user loads a single-colour model, offer an option to generate colour assignments (per-face or per-region)
- Scope TBD: region segmentation approach, on-device vs cloud inference, user control (accept/reject/edit), output format (per-triangle extruder assignment compatible with existing SEMM pipeline)
- Track: [`#33`](https://github.com/taylormadearmy/u1-slicer-for-android/issues/33)

### F55: Draft slice mode — slice with simplified mesh for fast iteration (GitHub #34)
- Large models (1M+ triangles) can take a long time to slice; for iterative workflows the user does not need full precision
- Add a "Draft" toggle on the Prepare screen: when enabled, model is simplified to ~100K triangles using `its_quadric_edge_collapse` (QEM — same algorithm as the F48 preview fix) before slicing
- Non-destructive: original mesh untouched; simplification applied transiently per-slice
- Potentially 10-17x faster for very large models; G-code slightly less accurate (small surface details lost)
- UI must clearly label output as draft; not suitable for final production slices
- Track: [`#34`](https://github.com/taylormadearmy/u1-slicer-for-android/issues/34)

### F56: Show loading indicator / warning when a very large model is detected (GitHub #35) — DONE v1.5.24
- "Large model — this may take a moment…" shown when file >50MB; "Large model — preview may take a moment…" shown after load when triangle count >500K
- Track: [`#35`](https://github.com/taylormadearmy/u1-slicer-for-android/issues/35)

### F62: G-code viewer — fit camera to content on load (GitHub #42) — DONE v1.5.47
- Camera now computes XY bounding box of all G-code moves (extrusion + travel, including prime towers); targets centroid, distance = max(spanX, spanY) * 2.0 with 20mm padding; falls back to plate-centred 500mm for empty gcode
- Issue #42 closed.

### F66: Split to objects and auto-rotate for placement (GitHub #56)
- 3MF files exported from generators (e.g. Skadis shelf generator on MakerWorld) output assembled scenes with parts in place — slicing as-is requires heavy supports; user needs to split the assembly into individual objects and auto-orient each for optimal bed placement
- Split: decompose multi-body 3MF into separate placeable objects
- Auto-rotate: orient each object to minimise support requirement (largest flat face down, or similar heuristic)

### F63: MMU preview triangle cap + long-term QEM colour preservation (GitHub #50)
- H2C benchy produces 2M triangles from `get_facets()` MMU splitting → 226MB VBO
- Short-term: add a safe triangle cap (~1M tris) with stride on the interleaved output
- Long-term: QEM on original volume mesh with per-face colour preservation (centroid matching only recovers indices 0-3 currently)
- Related to B48 interleaving fix; currently no decimation for MMU meshes

### D1: Document slicer engine upgrade process (GitHub #25)
- Write a guide covering how to update the OrcaSlicer submodule to a new version (Snapmaker Orca or FullSpectrum fork)
- Should document: submodule pin process, our Android-specific patches that must be re-applied (`#ifdef __ANDROID__` diagnostics, initializer fixes, build fixes for clipper.hpp/Brim.cpp/CutSurface.cpp), SAPIL JNI interface contract, config key differences, native rebuild workflow
- Include a checklist for testing after an engine upgrade
- Useful for both routine Snapmaker Orca updates and the eventual FullSpectrum fork evaluation (F14)

## Closed (recent)
See git log for full history. Most recent fixes:
- **B61**: Support settings from Bambu files silently dropped — `needsPreserve` didn't trigger for single-color files with `enable_support=1` in sourceConfig; added `sourceHasSupports` condition — FIXED v1.5.50.
- **B55**: QEM preview crash/freeze + slice cancel upgrade — native `cancelPreviewMesh()` with atomic flag in QEM loop, native `cancelSlice()` via `Print::cancel()` + `throw_if_canceled()`, honest "Cancelling..." UX — FIXED v1.5.49.
- **F70**: Check for Updates button in Settings — queries GitHub Releases API, shows version comparison inline, download link when update available — DONE v1.5.49.
- **F69**: 3D viewer thread-safety hardening — `@Volatile` Camera fields, `pendingCameraState` for GL-thread-safe camera mutations, Double-precision scalars — DONE v1.5.47.
- **F62**: G-code viewer fits camera to content bounding box on load — small models fill the view, prime towers stay in frame — DONE v1.5.47.
- **B47**: S-Buttons multi-colour 3MF intermittently loses a colour on first load — race condition where `_colorMapping` was emitted after `_state = ModelLoaded` in `loadNativeModel`; moved entire multi-color setup block before the ModelLoaded emission so the UI always sees a consistent snapshot — FIXED v1.5.46.
- **B60**: B57 regression — `hasPaintSupports` dropped by both `mergeThreeMfInfo()` and `mergeThreeMfInfoForPlate()`, causing supports to be disabled for citystep. Fixed by forwarding `origInfo.hasPaintSupports` / `sourceInfo.hasPaintSupports` in both merge functions — FIXED v1.5.44.
- **B59b**: Prime tower Prepare preview stale after toggle — `togglePrimeTower()` didn't call `invalidatePrepareMeshCache()`; `LaunchedEffect` was missing `wipeTowerVisible` key and had no `else` branch to clear the rect — FIXED v1.5.44.
- **F68**: Single-colour mode missed by F65 material label — `perExtruderFilamentMm.size > 1` guard in `SliceCompleteSummaryCard` excluded single-extruder prints; changed to `isNotEmpty()` — DONE v1.5.44.
- **F67**: Stale slice indicator — added `_sliceStale` `MutableStateFlow<Boolean>` to `SlicerViewModel`; set `true` by all user-initiated config mutators, `false` at start of `startSlicing()` and in `clearModel()`; `StaleSliceBanner` shown above Slice button when stale result is present — DONE v1.5.44. Follow-up v1.5.45: also observes `extruderPresets.drop(1)` so changes from the Printer tab also mark the slice stale.
- **F64**: HSV colour picker (hue strip + saturation/value box) added to extruder colour edit dialog on Printer screen — DONE v1.5.43.
- **F65**: Material type label (PLA, PETG, etc.) shown next to each extruder swatch on G-code Preview page — DONE v1.5.43.
- **B53**: Prime tower toggle correctly bypasses multi-extruder guard via `computeTogglePrimeTower()` using OVERRIDE mode — FIXED v1.5.43.
- **B57**: Single-color Bambu 3MF with support painting uses embedded config (hasPaintSupports triggers needsPreserve in ProfileEmbedder) — FIXED v1.5.43.
- **B59**: Multi-color model Filament label always shows PLA regardless of extruder materials — FIXED v1.5.42. `resolveFilamentTypeLabel(usedSlots, presets)` helper wired into all three paths (single-color initial load, `applyMultiColorAssignments`, layer-tool branch); returns "Mixed" when active extruders have different materials.
- **B56**: Selecting non-E1 extruder doesn't update filament type for display or slicing — FIXED v1.5.41. `updateSingleColorExtruder()` now reads `materialType` from the selected `ExtruderPreset` and includes `filamentType` in `config.copy()`.
- **B41**: 3MF embedded rotation preservation + tab-switch preview cache fix — FIXED v1.5.35
- **B40/B39**: Jobs gcode durable path + printer offline grace period — FIXED v1.5.34
- **F59/F#39**: G-code preview tube width scaled up for visibility (halfWidth 0.225→0.75, halfHeight 0.1→0.2) — DONE v1.5.32; miter joins, Blinn-Phong lighting, proper 0.42mm proportions — v1.5.35
- **F60/F#40**: Jobs tab "View G-code" icon — parses saved G-code on IO thread, navigates to 3D viewer; graceful toast if file missing — DONE v1.5.32
- **F61/F#41**: Jobs tab "Re-open model" icon — source 3MF copied to durable `files/jobs/<id>/` at slice time (Room v2 migration adds `sourcePath`); `jobs/` dir protected from upgrade clearing; `reopenJobToEdit()` reloads model to Prepare screen — DONE v1.5.32
- **F60/F#36**: Prepare preview prime tower now reacts to primeTowerWidth override — `resolveWipeTowerWidth()` returns active override or config default — DONE v1.5.30
- **F57/F#37**: Model rotation on all three axes from Prepare screen with live 3D preview; rotation persists to slice; prime tower rotation also included — DONE v1.5.26/v1.5.28
- **F58/F#38**: Prime tower width (`prime_tower_width`) and rotation angle (`wipe_tower_rotation_angle`) exposed in Prepare screen; threaded through native profile keys pipeline — DONE v1.5.26/v1.5.28
- **F56/F#35**: Large model loading warning — "Large model — this may take a moment…" on file >50MB; "preview may take a moment…" on triangle count >500K — DONE v1.5.24
- **F#36**: Prime tower footprint accuracy — Prepare preview now shows correct rectangular wipe tower (estimated depth via OrcaSlicer height→depth heuristic) instead of square — DONE v1.5.24
- **B#37 regression**: Wipe tower Y-clamp mismatch introduced in v1.5.24 — pre-slice clamp and drag clamp now both use `WipeTowerDepthEstimator.estimateDepth()` for the Y axis; auto-placement `computeWipeTowerPosition()` uses correct rectangular footprint — FIXED v1.5.25
- **F#38**: Reset & Retry after Clipper slicing error — error card now shows "Reset & Retry" button which reloads the native model without requiring the user to re-pick the file — DONE v1.5.25
- **F44**: Print progress notifications — printer polling now updates an ongoing Android notification for active and paused prints with current progress, without needing a separate foreground polling service — DONE v1.4.27-dev
- **Backup import skirt regression**: importing an older settings backup could restore stale `skirtLoops=1` into the live in-memory config for the current session, causing unexpected skirts despite the repository default being forced to 0; imports are now normalized and covered by a regression test — FIXED v1.4.26
- **F41/F42/F43**: Added Reduce Infill Retraction toggle, Wall Generator + Seam Position overrides, native fallback/profile-key support, and inline embedded 3MF file values on Prepare override rows via `sourceConfig` threading — DONE v1.4.26
- **F40**: MakerWorld in-app browser — browse, log in via Bambu SSO, download 3MF/STL directly into slicer; auth cookies silently extracted for share-URL pipeline; cookie info dialog + file import as fallback — DONE v1.4.23
- **B36**: MakerWorld download/loading text was confusing ("Loading Downloading from MakerWorld……") — each state now provides complete display message — FIXED v1.4.22
- **F37**: File picker accepted any file — now validates extension after selection and rejects unsupported types with a clear error message (`*/*` kept in MIME types since Android file managers don't recognize `model/*`) — DONE v1.4.22
- **F38**: G-code preview upgraded to box-tube geometry (top + left + right faces) with bottom-to-top brightness gradient matching u1-slicer-bridge quality — DONE v1.4.22
- **F39**: Travel move toggle added to inline G-code preview on Preview screen, brighter travel line color — DONE v1.4.22
- **B31/F35**: Clipper coordinate overflow crash on multi-colour SEMM models — native overflow guards in `IntersectPoint` and `Round()`, Kotlin wipe tower clamping, crash-loop prevention, stale marker cleanup on APK upgrade — FIXED v1.4.20/v1.4.21
- **Cookie file import**: CookieInfoDialog with browser export + file transfer instructions, stream handling fixes — DONE v1.4.23 (included in F40)
- **F36 (bed temp)**: Editable bed temp field below plate type selector — DONE v1.4.19
- **Color bug (SEMM)**: SEMM models with non-identity color remapping on Prepare screen now produce correct G-code T-commands — fixed `isIdentity` logic and suppressed extruderRemap in model_settings.config for paint-data models — FIXED v1.4.18
- **B33**: Print progress stuck at 0% — `virtual_sdcard.progress` is now used as the primary progress source (falls back to `print_stats.progress` on older firmware) — FIXED v1.4.18
- **B35**: Upload-only completion card now shows a hint: "To print, tap Print on the Preview screen or select the file on your printer." — FIXED v1.4.18
- **F36**: Plate type selector (Textured PEI, Smooth PEI, Cool Plate, Engineering Plate) in slice settings Temperature section — auto-adjusts bed temp per filament material — DONE v1.4.18
- **F27**: Cancel slicing button — soft cancel returns to ModelLoaded immediately; native slice runs to completion in background and result is discarded — DONE v1.4.18
- **Upload-only bug**: "Print started!" was shown after upload-only; now shows "Uploaded successfully!" — FIXED
- **F28**: Prime tower waste in Slice Summary — amber "Prime Tower Waste" row in SliceCompleteSummaryCard — DONE
- **F33**: Feature-type color mode in 3D G-code viewer — Palette toggle in toolbar, 12-color feature palette — DONE
- **F29**: Prompt to slice when navigating to Preview with no result — "Slice Now" button on empty Preview when model is loaded — DONE
- **F30**: More support settings — XY distance, interface pattern/spacing, support speed, tree branch angle/distance/diameter — DONE
- **F31**: More infill/shell settings — top/bottom shell layers, top/bottom surface pattern, infill speed, expanded infill pattern list — DONE
- **F32**: Settings accordion opens on "Layer & Infill" by default on Prepare screen — DONE
- **F34**: Remote screen probe + button — auto-detects paxx12 firmware via HEAD `/screen/`, shows button in Printer screen when available — DONE
- **B32**: Thumbnail format switched to vanilla Klipper format (no `THUMBNAIL_BLOCK_START/END` wrappers, 76-char base64 lines) — matches u1-slicer-bridge which is confirmed working on Snapmaker hardware; the THUMBNAIL_BLOCK_START format added in v1.4.10 requires newer Moonraker and was causing the printer to show its default icon instead of the job preview — FIXED v1.4.11
- **B30**: Uploaded printer thumbnails now mirror Orca's native `THUMBNAIL_BLOCK_START/END` wrapping and line length so Moonraker/Klipper recognizes the embedded preview instead of showing the default icon
- **I2**: First post-update Clipper "Coordinate outside allowed range" failure hardened again — FIXED v1.4.1
- **Native Prepare Preview**: Prepare preview now uses native/Orca-backed mesh export instead of the old Kotlin-only path — DONE v1.4.0
- **B24**: Stale slice config (skirt/prime tower not updating on re-slice) — FIXED v1.3.42
- **B22/B23**: Multi-color preview race + extruder map mismatch — FIXED v1.3.37
- **F3/F25**: Per-vertex multi-color preview + extruder picker — DONE v1.3.36
