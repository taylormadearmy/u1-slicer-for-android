# Backlog

Open bugs, features, and investigations. Everything else is done — see git log.

## Open Bugs

### B136: Painted/MMU Prepare preview renders as scattered dots / empty plate (GitHub #168) — FIXED v2.10.14, RELEASED v2.10.14 (released 2026-06-04)
- **Symptom**: Multi-colour/painted files (colored Benchy, H2C Benchy/Nancy, axolotl) over the preview triangle cap rendered in the Prepare preview as scattered "loads of dots" instead of a solid model. A follow-up QEM attempt made it worse on some files — H2C Benchy showed a **totally empty plate**.
- **Reported by**: Kevin (manual testing), 2026-06-03/04. Introduced by **B131** (v2.10.2, commit `21c8d0f`) which capped the MMU/paint preview via stride-skip decimation (keep every Nth facet) to fix the Ghostface 3.7M-triangle off-bed bug. Stride-skip scatters disconnected facets → dots.
- **Failed first fix (QEM per state)**: routing each paint state through `its_quadric_edge_collapse` exploded vertices (paint states are arbitrary, frequently **non-manifold** triangle subsets — QEM produced ~±30000mm vertices → off-screen → empty plate) and eroded patch boundaries (holes).
- **Fix (`sapil_model.cpp`)**: keep the **full solid mesh** (`stride=1`) for the MMU/paint path on normal painted files; only stride-skip genuinely enormous Hueforge-class volumes (**global** `total_tris > 2,000,000`, e.g. Ghostface 3.7M) where an uncapped upload would stall the UI and the B131 cap test requires bounding. Threshold keys on global total (Ghostface spreads 3.7M across many small painted volumes, so a per-volume threshold never tripped). Native `.so` rebuilt NDK 26 / Release / 20.9 MB / clang 17.0.2 / 50 JNI symbols.
- **Residual**: >2M painted ("Hueforge-class") files still preview sparse via the bounded fallback (on-bed, capped). A solid colour-preserving decimation for huge paint files (QEM the full manifold mesh + re-derive paint colours) is a tracked follow-up.
- **Tests (the metrics the old suite lacked — colour/count/cap/bounds all passed while the surface was destroyed)**: `NativePreparePreviewTest#getPreparePreviewMesh_paintPreviewIsSolid_notSubsampled` (colored Benchy capped-count ≈ full-count, ratio ≥0.9; broken build gave 0.12) + `..._paintPreviewBoundsAreSane_h2cBenchy` (AABB span <500mm; broken build gave 32764mm). Both verified RED on the broken `.so`, GREEN after. E2E skill gained a logcat-based paint-preview-integrity check so this class can't slip past screenshots again.
- **Issue**: https://github.com/taylormadearmy/u1-slicer-for-android/issues/169

### B135: CopyArrangeCalculator.placeAdditionalObject can place pieces off-bed when row+below grid is exhausted (GitHub #167) — NEW 2026-06-03
- **Symptom**: After splitting a multi-volume model and adding multiple per-object duplicates (per-object Copies slider, v2.10.4+), the placement helper places later pieces past the 270mm bed edge in Y when the "right of existing" branch fails and the "below all existing" branch also overflows.
- **Reproducer**: Oreo (`OreoProj1.3mf`) plate 1 — split object 0, then call `duplicateObject(2)` 4 times. Pieces 6 and 7 land at Y ≈ 274mm. Slicer then errors with `"impossible coordinates"`.
- **Discovered**: 2026-06-03 v2.10.13 full instrumented sweep — `B131B132B133DiagnosticTest#b132c_oreo_splitThenDuplicate_sliceReplayProducesCorrectObjectCount` failed with 4 dupes; relaxed to 2 dupes to keep the regression guard for v2.10.12's `_duplicateOps` replay logic without depending on the layout fix.
- **Not a release blocker**: end-user manual flow (the v2.10.12 user-verified repro) doesn't hit this — depends on starting bed layout. The slicer's "impossible coordinates" error is a graceful refusal, not a crash.
- **Right fix**: F92 Auto-arrange (already in BACKLOG) — add a bed-fit check + auto-arrange fallback in `placeAdditionalObject`, or fall through to grid-from-scratch when neither right nor below fits. Until then, the per-object Copies slider can produce slice errors at high counts on dense layouts.
- **File**: [CopyArrangeCalculator.kt:375-417](app/src/main/java/com/u1/slicer/model/CopyArrangeCalculator.kt#L375-L417).

### B133: Chubby Darth Vader MULTI_COL — Filaments 3 & 4 show "NONE" material on load; Split-to-Parts collapses paint state to single colour (GitHub #165) — INVESTIGATED, NOT REPRODUCED PROGRAMMATICALLY
- **Symptom (NONE materials)**: Load `Chubby_Darth_Vader_MULTI_COL...3mf` (4-filament file). Prepare's Filaments panel shows 4 entries with correct colours (`#000000` / `#FFFFFF` / `#EBD9C4` / `#804000`) but slots 3 + 4 show material type literally **"NONE"** while slots 1 + 2 show PLA. Both NONE rows still show 220°C nozzle temp, so a fallback is being applied silently but the material label never resolves.
- **Symptom (Split colour loss)**: On the same file, Split-to-Parts strips per-volume paint/colour assignments — the cape (black with white "I AM YOUR FATHER" text), the skin-tone face, and the brown pants all render as a single white filament after split. Filament panel header still reads "(4)" but every split part draws on Filament 1.
- **Reported by**: Kevin (Discord screenshots, Pixel 8a), v2.10.1, 2026-05-31.
- **2026-06-01 empirical findings** (on-device Pixel 8a, ViewModel-level inspection): the symptom is **NOT reproducible at the ViewModel data layer** on v2.10.1. After load: `canonicalFilamentList.filaments.size = 4`; every entry has `source=FILE_COLOUR`, `materialType="PLA"`, and a distinct hex colour matching the screenshot. `displayedFilamentMaterials = [PLA/220C, PLA/220C, PLA/220C, PLA/220C]`. `colorMapping = [0,1,2,3]`. So either the bug was fixed between the screenshot and v2.10.1 (B128's resolver landed v2.9.0), OR the "NONE" rendering is in a Compose path that doesn't observe `displayedFilamentMaterials` (some intermediate state during pre-resolution that stabilises). The split-colour-loss half remains unverified — native `ModelObject::split` is suspected to drop per-triangle paint state, but reproducing it requires either a Compose UI harness or visual inspection of the running app.
- **Next steps when picked up**: (1) Visual on-device repro on v2.10.1 with the actual file — record + screenshot the Filaments panel during the load animation to catch any "NONE" frame; (2) If reproducible, find which Compose composable renders the label and confirm whether it observes `displayedFilamentMaterials` or another StateFlow; (3) For split colour loss, add a `NativePreparePreviewTest` that calls split + reads `extruderIndices` byte array post-split to confirm whether paint state survives.
- **Distinct from B128**: B128 (FIXED v2.9.0, `jons-bug.3mf`) was a slot showing the slot-preset's default material instead of the file's declared material. B133 is two different and worse symptoms — literal "NONE" (no material at all), plus a Split-path paint-state-loss not in B128's scope.
- **Investigation hints (NONE)**: Check whether `CanonicalFilamentList` / `BambuCanonicalList` returns `materialType` populated for all 4 entries on this file (vs only 2). Trace `resolvePerFilamentTypeAndTemp`'s FILE arm for slots 3+4 — does `paintStateMap` size or canonical-list size cut off at 2 even though `filament_colour` has 4 entries? `PrintSetupSection` rendering behaviour when `material == null`.
- **Investigation hints (Split)**: v2.10.1 commit `70420cb` fixed the Split gate for single-volume multi-island meshes, but the split path may not carry forward `objectExtruderMap` and per-volume `paint_color` attributes. After Split: does `getObjectExtruderMap()` return per-volume extruder indices, or does every split volume map to extruder 0? Does the native preview mesh's `extruderIndices` byte array preserve per-extruder index after split?
- **Test asset**: `Chubby_Darth_Vader_MULTI_COL...3mf` — not in repo. Likely in Kevin's working tree / Drive.
- **Issue**: https://github.com/taylormadearmy/u1-slicer-for-android/issues/165
- **Source**: Discord, 2026-05-31 (Kevin) — https://discord.com/channels/1086575708903571536/1484249705042153633/1510628426099200120

### B132: Oreo 3MF — Split + extra copies don't appear physically on bed; slice ignores custom placement and reverts to original layout (GitHub #164) — FIXED v2.10.2 → v2.10.12, RELEASED v2.10.13 (user-verified on Pixel 8a 2026-06-02; release published 2026-06-03)
- **v2.10.3 follow-up** (B132c — crash fix): user reproduced an `ArrayIndexOutOfBoundsException: length=9; index=9` at `splitMeshByObjects` after split + make-copies on Oreo. Device log showed `applyPlacementPositions: 5 objects` for a 3-object model, with native rejecting via `setObjectPositions: positions count 5 != object count 3`. Kotlin `_multiObjectPositions` was corrupted past the model state, then the rotation `LaunchedEffect` indexed `perObjectSizes` past its length. Three defensive fixes layered:
  1. `SlicerViewModel.applyPlacementPositions` — validate `positions.size / 2 == nativeGetObjectCount()` before any state mutation; log + return early on mismatch.
  2. `MainActivity` rotation `LaunchedEffect` gate — tighten `multiPos.size >= (perObjectSizes.size / 3) * 2` to `==` so an over-long positions array no longer enters `splitMeshByObjects`.
  3. `ModelRenderer.splitMeshByObjects` — return `null` if `sizes.size < objectCount * 3` instead of indexing OOB; the caller's fallback (`drawModel`) handles a null result.
- **Verified**: `b132c_applyPlacementPositions_mismatchedCount_doesNotCorruptState` PASSES (guard rejects 5-position array for 3-object model). All 7 B131B132B133 diagnostic tests green.

- **Symptom**: On `OreoProj1.3mf` (same file family as B101 / B102), after the v2.10.1 Split-to-Parts gate fix, Jon reports a four-step cascade:
  1. Could not make more than one copy of the Oreo on the plate **because of the initial layout** (parts spread wide).
  2. Used **Split to Parts** to test — registered more than one copy in the UI — but the **extra copies did not appear physically** on the bed.
  3. Sliced anyway hoping copies would materialise → G-code contained only one cookie.
  4. Slicing also **ignored Jon's custom placement and reverted to the original layout**.
- **Reported by**: Jon (Discord), v2.10.1, 2026-05-31.
- **2026-06-01 empirical findings** (on-device diagnostic, Pixel 8a):
  - **#1 confirmed**: Oreo's combined-bounding-box footprint is **170×171mm** (after B104 plate filter strips off-bed garbage). `CopyArrangeCalculator.calculate(170, 171, 2)` silently returns 1 position — only 1 copy fits on a 270mm bed in a row. `copyBedWarning` text surfaces this ("max 1 for this size") but the slice proceeded anyway with 1 cookie.
  - **#2 confirmed**: After `splitObject()`, `hasMultipleDistinctObjectsVar=true` ✓ but `_multiObjectPositions` StateFlow stayed **null** — only the private `customObjectPositions` was updated. So the InlineModelPreview's gate at `MainActivity.kt:3356` (`multiPos != null`) stayed closed, and `splitMeshByObjects` was never called. Also: `_copyCount` was not reset on split, so a user's pre-split copy count silently persisted into multi-object mode (where copies are ignored entirely).
  - **#3 (slice ignored placement)**: not yet empirically reproduced. The code path at `startSlicing` line 4434 (`setObjectPositions(custom)`) DOES re-apply `customObjectPositions` after the re-embed/replay steps, so in theory drags survive the slice. Needs visual on-device verification with deliberate post-split drag.
- **2026-06-01 fixes in working tree** (not yet committed/released):
  - **B132a fix** — `SlicerViewModel.startSlicing` adds a pre-slice cap-check: if `_copyCount > 1` and `CopyArrangeCalculator.maxCopies` reports fewer than the requested count, the slice errors with "Only N copy of this model fits on the 270×270mm bed (model footprint XxYmm). Either reduce Copies to N or scale the model down." Prevents silent loss of copies. Verified by `B131B132B133DiagnosticTest#b132a_oreo_preSplit_copyCount2_errorsBecauseCopiesDontFit`.
  - **B132b fix** — `SlicerViewModel.splitObject` now (a) sets `_multiObjectPositions.value = positions` to mirror the private `customObjectPositions` so the renderer's gate opens, (b) resets `_copyCount = 1` and `_copyBedWarning = null` to clear stale state from pre-split (copies don't apply in multi-object mode anyway). Verified by `B131B132B133DiagnosticTest#b132b_oreo_splitObject_resetsCopyCount_andPublishesMultiObjectPositions`.
- **Residual / not addressed**: (a) Jon's "placement reverted" symptom needs visual verification — the slice path should preserve drags but user reports otherwise; (b) the UX gap that "copies don't compound in multi-object mode" is now made explicit (count resets to 1) but a future enhancement could let copies multiply each split piece (2 copies × 5 pieces = 10 instances).
- **Distinct from related work**: B101 (FIXED v2.0.2) covered load-time placement. B94 (v1.6.10) covers single-object STL drag preservation. F77 added multi-file STL copies. v2.10.1 commit `70420cb` fixed the Split gate; this is the **post-Split** state-consistency layer.
- **Issue**: https://github.com/taylormadearmy/u1-slicer-for-android/issues/164
- **Source**: Discord, 2026-05-31 (Jon) — https://discord.com/channels/1086575708903571536/1484249705042153633/1510646439699681401

### B131: Ghostface Pokemon card 3MF not visible on Prepare tab — slice/preview work, model can't be moved to clear purge tower (GitHub #161) — FIXED v2.10.2, RELEASED v2.10.13 (release published 2026-06-03)
- **Symptom**: Loading `GhostfacePokemoncard.3mf` (8.6 MB) shows the model on the Preview tab and slices successfully, but on the **Prepare tab the model never appears** — only the wipe/purge tower is visible.
- **Reported by**: Jon (Discord), v2.10.1, 2026-05-31.
- **Root causes (empirically confirmed `B131B132B133DiagnosticTest`, Pixel 8a 2026-06-01)**:
  1. **Decimation cap bypassed for MMU/paint-state files**: `getPreparePreviewMesh(MAX_DECIMATED_TRIANGLES=100_000)` returned **3,727,790** triangles. The MMU path in `sapil_model.cpp` hard-coded `stride=1` when emitting per-state triangles (round-robin interleave), so Hueforge-style files with paint data on every volume bypassed the cap entirely. Ghostface is 147×223×3mm with paint state per Z layer.
  2. **Mesh world-coord vs `loadTimeInstanceOffsets` mismatch**: mesh AABB starts at world `(47.5, 18.8)` (vertices are in world coords after instance transform), but `getInstanceOffsets()` returned `(116.3, 137.2)` — the BBS instance reference point (centroid-ish), not the mesh's bottom-left. The renderer's `drawModelAt(mesh, x, y)` expects `(x, y)` = mesh bottom-left, so it shifted Ghostface's Y from 18..242 to 137..361 — **~91mm past the 270mm bed edge**. User perceived "model doesn't appear" because most of the model rendered above the bed visual reference.
- **Fix**:
  1. **`sapil_model.cpp` MMU stride** (native, [line 492+](app/src/main/cpp/src/sapil_model.cpp#L492)): compute per-volume MMU stride via `std::accumulate` of all paint-state facet counts, pass `mmu_stride` to each `appendItsPreviewMesh` call instead of `1`. Reduces Ghostface from 3.7M → ~152K triangles (24× reduction). Native rebuilt NDK 26 / Release / 20.9 MB / Clang 17.0.2 / 49 JNI symbols matching `external fun` count.
  2. **`SlicerViewModel._loadTimeInstanceOffsets` derivation**: prefer `nativeGetObjectWorldAABBMins()` over `getInstanceOffsets()` when (a) single-instance, (b) worldMins are non-negative (on-bed), (c) worldMins differ from naturalOffsets by >1mm. Otherwise fall back to `naturalOffsets` (preserves Benchy/STL behaviour where worldMins are negative and naturalOffsets are `(0, 0)`; also unchanged for multi-instance Bambu like Oreo).
- **Verified**: `b131_ghostface_decimatedMeshFitsBedFromInstanceOffset` PASSES (Y range 18.8..242.5, on-bed). `b131_benchy_stl_offsetsAgreeWithMeshWorldMin` PASSES (no regression). Spot-checks: Dragon plate 3 multi-color slice PASSES, tetrahedron STL slice PASSES.
- **Test asset committed**: `app/src/androidTest/assets/GhostfacePokemoncard.3mf` (8.6 MB).
- **Issue**: https://github.com/taylormadearmy/u1-slicer-for-android/issues/161
- **Source**: Discord, 2026-05-31 (Jon) — https://discord.com/channels/1086575708903571536/1484249705042153633/1510693402684166285

### B130: LongOpService foreground-service-did-not-start-in-time crash under load (GitHub #158) — PARTIAL FIX v2.9.2, residual OPEN
- **Symptom**: App crashes with `ForegroundServiceDidNotStartInTimeException` for `com.u1.slicer.LongOpService` — `startForegroundService()` called but `startForeground()` not reached within Android's 5s watchdog. Happens when a long op (load/slice/save/share/send) starts while the phone is thermally throttled / memory-pressured (hot or busy phone, large files).
- **Surfaced**: v2.9.0 2h instrumented sweep (failing test varies run-to-run — `flippy_layerToolOnly...`, `snapshot_coloredBenchy_semm`, `dragonPlate3_selectPlate...`; all PASS in isolation on an idle device); user suspects seeing it in real use. Not v2.9.0-specific — the LongOpService wrap dates to F90 (v2.7.x). v2.10.1 2h sweep (2026-06-01) showed the same residual: 386/388 PASS overall, both failures FGS-class — `buttonForSTrousers_dragToRight_preservesPositionThroughSlice` (PASSED on isolated re-run, pure environmental flake) and `sButtons_plate1_withUserLikePresetsWhiteE2PinkE4_showsFourDistinctColors` (failed in sweep with a 30s DataStore-presets wait timeout, failed in isolation with the FGS exception — same underlying watchdog race manifesting as different stalls depending on what ran before).
- **Two distinct causes:**
  1. **Deterministic (FIXED v2.9.2)**: `onStartCommand`'s `ACTION_STOP` and empty-stage paths returned after `stopForeground()`/`stopSelf()` **without** first calling `startForeground()`, so a stop/empty command arriving as the start crashed under rapid start/stop churn. Fix: promote to foreground on *every* `onStartCommand` path before stopping. Structural guard in `LongOpServiceStackTest`.
  2. **Residual (still OPEN)**: under sustained heavy load the OS is too slow to even *deliver* `onStartCommand` within 5s of `startForegroundService()` — the watchdog trips before our code runs, so no `onStartCommand` change can fix it. The v2.9.2 run still showed 1 such flake (different test, passes in isolation). **Mitigation = debounce the FGS start**: don't call `startForegroundService` for ops expected to finish in <~400ms (extend the F90 v2.7.1 preview-prep debounce to all `LongOpService.start()` callers), so the short `loadModel`/`selectPlate` churn never arms the watchdog. Bigger redesign of the start/stop companion — own follow-up.
- **Severity**: rare real-world crash; no correctness/data-loss impact (F89 session-resume covers process death). v2.9.2 reduces exposure; residual remains until the debounce mitigation.
- **Issue**: https://github.com/taylormadearmy/u1-slicer-for-android/issues/158 (stays open for the residual)

### B129: Moving/rotating the model resets the G-code preview layer slider to the top of the print (GitHub #157) — FIXED v2.9.0 (full-screen viewer) + v2.9.1 (inline Preview-tab viewer)
- **Symptom**: After slicing, open the sliced G-code preview and move the layer slider to some position. Then move or rotate the model on the build plate. The G-code preview's layer slider jumps back to the top of the print.
- **Reported by**: Kevin, v2.8.0.
- **Root cause (two layers)**: (1) `GcodeViewer3DScreen` held `minLayer`/`maxLayer` in plain `remember` — Compose Navigation pops the `GCODE_VIEWER_3D` destination when you go to Prepare, destroying its saved state, so re-entry re-initialised to the full range. (2) On re-entry the `LaunchedEffect` re-ran `GcodeRenderer.uploadGcode`, which unconditionally resets the renderer's `maxLayer` to the top.
- **Fix**: Hoisted the range to `SlicerViewModel.previewLayerRange: StateFlow<Pair<Int,Int>?>`. It is reset to `null` (full range) only via `setParsedGcodeWithRangeReset`, called at every `_parsedGcode` assignment/clear site, so a genuinely new/cleared slice opens at the top while plain navigation preserves the position. The screen seeds from it via the pure `resolveInitialLayerRange(saved, layerCount)` (clamped), persists slider moves via `onLayerRangeChange`, and re-applies the range to the renderer after `uploadGcode`. NavGraph wires both directions.
- **v2.9.1 follow-up (inline viewer)**: v2.9.0 fixed only the full-screen viewer. The **inline** Preview-tab viewer (`InlineGcodePreview` in `MainActivity`) had the same bug — its `maxLayer` slider was plain `remember` state reset to the top on every rebuild. Fixed by sharing the same `previewLayerRange` (seed via `resolveInitialLayerRange`, persist via `setPreviewLayerRange`). The remembered range is applied only AFTER the async G-code upload completes (`viewerLoading` flips) — applying it synchronously after `setGcode` raced the GL upload and crashed (`totalLayers` still 0 → `coerceIn(0,-1)`); `GcodeRenderer.min/maxLayer` setters now also clamp the upper bound to ≥0 as defense in depth. Device-verified on Pixel 8a (slider holds 25/50 across Prepare↔Preview with rotation; no crash).
- **Tests**: 7 unit cases for `resolveInitialLayerRange` (`GcodeLayerRangeTest`); 6 structural wiring guards (`GcodeViewer3DScreenLayerRangeTest`) including "all `_parsedGcode.value =` assignments route through the reset helper" + the inline-viewer wiring guard. (No automated Compose UI harness exists in this project; the move→rotate→return behaviour is covered by manual E2E.)
- **Issue**: https://github.com/taylormadearmy/u1-slicer-for-android/issues/157

### B128: 3MF default filament/material types not populated on load — slots 2+ show "none" (GitHub #156) — FIXED v2.9.0
- **Symptom**: Load a 3MF that declares 3 colours/materials. On first load only slot 1 is assigned a material type (e.g. "PETG"); slots 2 and 3 show the slot-preset default. Manually changing the material per slot works fine.
- **Reported by**: DC15 (Discord), v2.8.0.
- **Root cause**: On multi-colour load the per-filament material shown in `PrintSetupSection` (and the value the slice used) came from the auto colour-matched slot preset (`MainActivity.kt:3945`, `resolvePerFilamentTypeAndTemp` priority override → slot → file). The file's own `filament_type` array — parsed correctly into `CanonicalFilamentList.materialType` by `BambuCanonicalList` — was never authoritative. So a filament showed whatever material its matched physical slot happened to carry.
- **Fix (scoped to declared filaments)**: `resolvePerFilamentTypeAndTemp` now prefers the file's declared material (`override → FILE → slot preset → "PLA"`) **only** for a genuinely declared spool: `FilamentSource.FILE_COLOUR`, the file is multi-colour, `paintStateMap` is empty, and the filament owns its physical slot (injective mapping). Paint-fold (SEMM/H2C), support/interface, single-colour, and slot-collision cases keep mapped-slot authority — so **B99/B125** (support filament on a different-material slot) and **B118** (single-colour PETG-slot) do not regress. Nozzle temp follows the resolved material's default unless the mapped slot's preset material matches (then its tuned profile temp survives).
- **Consistency**: display (`SlicerViewModel.displayedFilamentMaterials` → `PrintSetupSection`), slice (`buildPerFilamentTypeAndTemp` → embed `filament_type`/`nozzle_temperature`), G-code header patch (`resolveFilamentTypesForHeaderPatch`), and the Map & Print "Sliced as X" mismatch check (`FilamentMappingDialog` fed `sliceTimeMaterials`) all run off the one resolver, so they can't diverge (no B118-class false warnings).
- **Constraint honoured**: manual material override still wins everywhere; "Reset to default" returns to the file's declared material for declared filaments.
- **Tests**: rewrote 1 + added 5 `PerFilamentResolverTest` cases (file-wins, collision-keeps-slot, paint-fold-keeps-slot, single-colour B118 guard, file==slot temp), updated 1 `FilamentTypeHeaderPatchTest` case, added `PrintSetupSectionMaterialTest` (display wiring), and instrumented `BambuPipelineIntegrationTest#b128_jonsBug_declaredFileMaterialsDriveResolvedTypes` (real `jons-bug.3mf` PETG+TPU → file materials win on-device). All green; 104-test slicing regression suite still passes.
- **Issue**: https://github.com/taylormadearmy/u1-slicer-for-android/issues/156
- **Source**: Discord, 2026-05-26 (DC15) — https://discord.com/channels/1086575708903571536/1484249705042153633/1508932542147854418

### B127: LayerToolPauseInjector drops layer-tool swaps for canonical fileIdx ≥ 4 (GitHub #145) — FIXED v2.5.0
- **Reproduced 2026-05-23** on `JapaneseWave.3mf` (7-filament Hueforge): XML had `extruder="5"/"6"/"7"` swaps but the output G-code had only the native T4/T5/T6 lines and no injected ones — `PAUSE_PRINT` fired but no tool switch.
- **Fix**: change `LayerToolPauseInjector.kt:134` guard from `if (toolIndex in 1..3)` to `if (toolIndex >= 1)`. The spurious upper bound was a typo; the T0 skip intent (T0 = starting tool, no switch needed) is preserved.
- **No colorMapping plumbing needed**: the injector writes canonical T-indices; `PrintTimeRemap` at upload time handles canonical → physical translation via colorMapping. Same path as the slicer's per-tool transitions.
- **Verified post-fix on JapaneseWave**: T4/T5/T6 each now have 2 lines (native + injected), M109 T4/T5/T6 emitted, `; layer_tool extruder` comment count goes 3 → 6. PAUSE_PRINT count unchanged at 7 (one initial + 6 swaps).
- **Tests**: 1 new unit test `b127 injector writes T-line for toolIndex 4 5 6 (7-filament Hueforge)` with synthetic XML in `LayerToolPauseInjectorTest.kt`. `JapaneseWave.3mf` (41 MB, 7-filament Hueforge) is intentionally NOT committed to the repo due to size; it lives at `G:/My Drive/tes-data/JapaneseWave.3mf` and can be copied locally for manual verification or future instrumented coverage.

### B125: H2C shoe support filament row missing — support_filament not emitted when supports=USE_FILE + hasSourceConfig=true (GitHub #144) — FIXED v2.2.14 (filament keys) / v2.2.15 (sub-settings) / v2.2.16 (root cause: targetCount too low)
- **Symptom**: Load `1890038_xav01_H2C_279_104.3mf` with TPU as model filament and PLA as support/interface-only. The Prepare preview and Filament Mapping dialog show only 1 filament row instead of 2 (model + support). The sliced G-code also only contains T0 (support runs on same extruder as model rather than a dedicated PLA extruder).
- **Reported by**: Kevin (screenshots, Pixel 8a, v2.2.13). Recurring report — first raised multiple sessions ago.
- **Root cause (gate)**: `buildProfileOverridesImpl` in `SlicerViewModel.kt` has an early-exit gate: `if (ov.supports.mode != OverrideMode.USE_FILE || !hasSourceConfig)`. For Bambu 3MF files (`hasSourceConfig=true`) where the user has not explicitly overridden the supports toggle (remains `USE_FILE`), the ENTIRE block — including `support_filament` and `support_interface_filament` — is skipped.
- **Root cause (targetCount — v2.2.16)**: Even after the gate fix, `embedProfile()` computed `targetCount = maxOf(computedTarget, canonicalSize)`. For a single-colour model, both are 1, so `targetCount = 1`. `ProfileEmbedder.buildConfig(targetExtruderCount = 1)` calls `normalizePerFilamentArrays(config, 1)` which truncates `filament_type` and `nozzle_temperature` to 1 entry and sets `extruder_count = 1`. OrcaSlicer silently ignores `support_filament = 2` because extruder 2 is out of range with only 1 extruder declared.
- **Fix (v2.2.14)**: Move `support_filament` / `support_interface_filament` emission outside the `ov.supports` gate. Emit whenever `ov.supportFilament.mode == OverrideMode.OVERRIDE` (explicit user choice) OR when `supportBlockActive` (non-Bambu path unchanged).
- **Fix (v2.2.15)**: Extend to all support sub-settings. Extract `supportBlockActive` before the gate. Keep `enable_support` gated as before (B10). Each sub-setting now emits when `supportBlockActive || ov.<field>.mode == OVERRIDE`.
- **Fix (v2.2.16)**: In `embedProfile()`, expand `targetCount` to also take `maxSupportSlot = max(supportFilament, supportInterfaceFilament)` from the override values when mode == OVERRIDE. Formula: `targetCount = maxOf(computedTarget, canonicalSize, maxSupportSlot)`. Ensures single-colour models get `extruder_count >= supportFilament` so OrcaSlicer routes support to the correct extruder.
- **Tests**: 3 unit tests in `SlicingOverridesTest.kt` (v2.2.14, filament keys) + 3 unit tests (v2.2.15, sibling settings) + 1 instrumented ViewModel test in `PreparePreviewViewModelTest.kt` (`h2cShoe_supportFilamentOverride_embedsCorrectExtruderCount_and_producesT1InGcode`, v2.2.16, true end-to-end regression).
- **Issue**: https://github.com/taylormadearmy/u1-slicer-for-android/issues/144

### B124: Button-for-S-trousers cannot be moved on bed — single multi-volume 3MF triggers multi-object renderer mode (GitHub #143) — FIXED v2.2.14
- **Symptom**: Load `Button-for-S-trousers.3mf`. The model appears on the Prepare screen but cannot be dragged to a new position. The position shown in the Prepare preview also differs from where it actually slices.
- **Reported by**: Kevin (screenshots, Pixel 8a, v2.2.13).
- **Root cause**: `objectBoundingBoxes` is populated from `native.getObjectBoundingBoxes()` after every load — for a single 40-volume 3MF this returns 120 floats (40 × [sizeX, sizeY, sizeZ]). The `InlineModelPreview` call passed `perObjectSizes = objectBoundingBoxes` unconditionally. Inside `ModelRenderer`, `multiObjectMode = perObjectSizes.size / 3 > 1` → `true` for 40 objects. But `hasMultipleDistinctObjects` is `false` (single file), so `objectMeshRanges` is never populated (no `splitMeshByObjects` ran). When `multiObjectMode=true` and `ranges==null`, `ModelRenderer.onDrawFrame` falls back to `drawModel(mesh, color)` at world origin — `instancePositions` is ignored entirely. Drag fires `onPositionsChanged` but the rendered position never changes, and the slice position (from `applyPlacementPositions` which IS gated on `hasMultipleDistinctObjects`) is also wrong.
- **Fix**: Expose `hasMultipleDistinctObjects` as a `StateFlow<Boolean>` in `SlicerViewModel`. In `MainActivity`, collect this StateFlow and gate `perObjectSizes = if (hasMultipleDistinctObjects) objectBoundingBoxes else floatArrayOf()`. Single-file loads now get `perObjectSizes = floatArrayOf()` → `multiObjectMode = false` → renderer uses `drawModelAt(mesh, px, py)` which correctly positions the mesh using `instancePositions`. All internal ViewModel reads of the flag renamed to `hasMultipleDistinctObjectsVar` to avoid shadowing the public StateFlow.
- **Tests**: 1 structural source-grep test in `InlineModelPreviewRotationKeysTest.kt` (`inlineModelPreview_perObjectSizesGatedOnHasMultipleDistinctObjects`), 1 ViewModel state test in `PreparePreviewViewModelTest.kt` (`buttonForSTrousers_singleFile_hasMultipleDistinctObjectsFalse_withMultiVolumeBboxes`), and 1 drag+slice end-to-end test in `PreparePreviewViewModelTest.kt` (`buttonForSTrousers_dragToRight_preservesPositionThroughSlice`). Multi-object behaviour (F77, multi-file STL) is unchanged since `hasMultipleDistinctObjects` is still set `true` via `doAddFile`.
- **Issue**: https://github.com/taylormadearmy/u1-slicer-for-android/issues/143

### B121: Filament Mapping dialog shows only model filament when STL is sliced with support on a different extruder (GitHub #142) — FIXED v2.2.10
- **Symptom**: Slice an STL with the model on E1 (TPU) and support on E2 (PLA). Slice Summary correctly shows 2 extruders (TPU + PLA) and the G-code preview renders both. But tapping Map & Print / Map & Upload shows only "Filament 1 · TPU (STL default)" in the Filament Mapping dialog — the PLA support row is absent. The user sends a remapped G-code with only E1 mapped; the support extruder is not remapped.
- **Reported by**: Kevin (screenshots 2026-05-19, v2.2.9).
- **Screenshots**: Show 1-row mapping dialog (E1·TPU) vs slice summary listing "Filament 1·TPU 6492mm + Filament 2·PLA 4792mm".
- **Root cause — two bugs:**
  1. `computePlateFileIndices()` (MainActivity.kt:951–955) returns `null` when any active G-code canonical index ≥ `canonicalSize`. For a single-colour STL (canonicalSize=1) sliced with support on canonical index 1, `nonZero.any { it >= 1 }` is true → null. In the `plateNarrowed` remember block (line 753), `null` is treated as "no narrowing needed" and falls back to `full to (0 until full.size).toList()` — a 1-entry list. Dialog gets 1 row.
  2. Even if the dialog somehow showed 2 rows, the `onConfirm` expansion guard `fileIdx in 0 until canonicalSize` (line 808) would silently drop the support slot mapping (fileIdx=1 ≥ canonicalSize=1). Only the model slot would reach `applyPrintTimeRemap`.
- **Siblings affected by the same pattern:**
  - **Multi-file STL load** (F77): N STL models → canonicalSize=N. Support configured on slot N+1 → canonical index N ≥ N → same null → same missing row.
  - **3MF with support beyond declared filaments**: if user manually sets support/interface to a slot beyond the 3MF canonical list, same null path.
  - **Mismatch check false positive**: once dialog is fixed to show the support row, the existing `sliceTimeSlot = sliceTimeColorMapping?.getOrNull(displayFileIndex) ?: 0` falls back to slot 0 (E1) for the support row (index 1 is beyond the size-1 colorMapping). This shows a spurious "Sliced as TPU but slot has PLA" warning even when the user correctly maps support to E2.
- **Fix outline:**
  1. Add `SUPPORT_FILAMENT` to `FilamentSource` enum + `sourceShortLabel` case ("support").
  2. Extract `buildWideGcodeMapping(canonical, perExtruderFilamentMm, extruderPresets)` — when G-code has active indices ≥ canonicalSize, synthesise `FilamentEntry` for those slots from extruder presets with `source=SUPPORT_FILAMENT`.
  3. In `plateNarrowed`, when `computePlateFileIndices` returns null, try `buildWideGcodeMapping` before falling back to the 1-entry list.
  4. In `onConfirm`, derive `expandedSize = max(canonicalSize, maxFileIdx + 1)` and change guard to `fileIdx in 0 until expandedSize`.
  5. In `FilamentMappingDialog`, change `?: 0` to `?: displayFileIndex` for `sliceTimeSlot` so the support row's mismatch check uses the canonical index as the default physical slot (avoiding the false E1/TPU fallback).
- **Tests**: unit test for `buildWideGcodeMapping` (pure function); existing `SlicedWithMaterialTest.kt` already covers B118 mismatch; add a case covering the `?: displayFileIndex` change.
- **Issue**: https://github.com/taylormadearmy/u1-slicer-for-android/issues/142
- **Source**: Kevin (Discord/screenshots), 2026-05-19.

### B120: Multi-plate Bambu 3MF with TPU on plate 2 slices "as one thing" + material/temps warning on v2.2.6 (GitHub #141) — FIXED v2.2.10
- **Symptom**: Loading a multi-plate Bambu 3MF where plate 2 is a TPU end piece, selecting plate 2, and slicing produces a material/temperatures mismatch warning. Jon described it as the app "slicing as one thing" — implying either the plate filter is not being applied (all plates' objects are included) or the material type detection for the plate is wrong (e.g. TPU plate detected as PLA), triggering the Map & Print warning cascade. Jon had a 30+ hour print depending on this and did not send it.
- **Reported by**: Jon (Discord), v2.2.6 (versionCode TBD). 2026-05-18.
- **Jon's words**: "I seem to be having the slice as one thing and it warning about materials and temps problem on 2.2.6. … Printing plate 2, the end piece in TPU." + "This is on 2.2.6 as well. I assumed that is what was just fixed. Afraid to send for a 30+ hour print."
- **Artifacts**: `.3mf` file (Discord 2026-05-18T00:53 UTC), 2 screenshots (`Screenshot_20260517-204856.png`, `Screenshot_20260517-204915.png`), `clipper_investigation_bundle.txt`, `output.share.gcode` (Discord 2026-05-19T13:02–13:03 UTC).
- **Root cause**: `ThreeMfParser.kt:1158–1163` parsed `filament_maps = "1 1"` by collecting the VALUES ("1", "1") as filament indices, calling `.filter { it > 0 }.toSet()` to get `{1}`, then mapping via `it - 1` to canonical index `{0}` (PETG only). But `filament_maps` values are AMS slot assignments, not filament indices. The active filaments for the plate are the POSITIONS with non-zero slot values: for `"1 1"`, positions 0 and 1 both have non-zero values → both PETG (canonical 0) and TPU (canonical 1) are active. Old parse: `{0}` only → plate 2 appeared PETG-only → Prepare screen showed only 1 chip → override flowed to canonical 0 (PETG), not canonical 1 (TPU) → slicer produced TPU G-code but dialog reported PETG → warning fire.
- **Fix**: Two-part fix in `ThreeMfParser.kt` + consumer updates:
  1. `ThreeMfPlate` gains `filamentMapSlots: Set<Int>` = unique non-zero AMS slot VALUES (physical extruder IDs for enrichment). `filamentIndices` now stores 1-indexed POSITIONS of non-zero entries (file-filament IDs for `computePlateFileIndices`). For `"1 1"`: `filamentIndices = {1, 2}` (both positions active), `filamentMapSlots = {1}` (one physical slot used).
  2. All enrichment / extruder-set-building consumers (`BambuPlateStateEnrichment.enrich`, `buildThreeMfInfoFromNative`, `mergeThreeMfInfoForPlate`, `buildSelectedPlateInfo`) now read `filamentMapSlots`. `computePlateFileIndices` keeps reading `filamentIndices`. This separation prevents the B120 fix from inflating enriched extruder counts for files where multiple file-filaments map to the same AMS slot (flippy painted plate 5 / F1 calendar plate 1 regressions in the first fix attempt).
- **Regression guards**: 
  - Unit test in `ComputePlateFileIndicesTest.kt` (`B120 filament_maps same-slot assignment detects both file filaments`): `filamentIndices = setOf(1, 2)` → `computePlateFileIndices` returns `[0, 1]`.
  - Instrumented test in `BambuPipelineIntegrationTest.kt` (`b120_jonsBug_plate2_detectsBothFilaments`): parses Jon's actual file, asserts plate 2 `filamentIndices` contains both 1 and 2, and `computePlateFileIndices` returns `[0, 1]`.
  - Jon's `jons-bug.3mf` added to `app/src/androidTest/assets/`.
- **Issue**: https://github.com/taylormadearmy/u1-slicer-for-android/issues/141
- **Source**: Discord, 2026-05-18 (Jon).

### B119: Buzz Lightyear cold load is 92–93s — predates F54, perf regression unbisected (GitHub #137) — OPEN
- **Symptom**: `PreparePreviewViewModelTest#buzzLightyear_coldLoad_skipsFullFileEmbedOnMultiPlate` consistently runs 92.5–93.0s on Pixel 8a (43211JEKB16931), well over the original 90s budget. Logcat breakdown shows nearly all of the time is in `BambuSanitizer.process()` + the initial `ThreeMfParser.parse()` of the 73 MB Buzz file (10 plates, 296k paint_color attributes).
- **Discovered**: v2.2.4 instrumented sweep, 2026-05-17. Test budget caught a slowdown that had been quietly present for many releases.
- **Bisection**: built worktrees at f639561 (B108, pre-F54) and 435ef9e (v2.2.0 F54 merge). Both run at 92.7–93.2s on the same device today. The slowdown predates F54 — it appeared somewhere between v1.6.11 (where the 42s baseline was set by B93 phase 2, and the 90s budget calibrated with 2x margin) and the B108 era. Native `.so` size between v1.6.11 (20,764,520 bytes) and current (20,809,192 bytes) differs by only 44 KB — small structural difference, large timing difference.
- **Workaround**: B93 budget recalibrated from 90s to 110s in v2.2.4 (`PreparePreviewViewModelTest.kt:1466`) with comment explaining the long-standing slowdown and pointing here. Functional behaviour is unaffected — the file loads correctly and the user does see the plate selector; it's just slower than the 2026-04-29 measurement.
- **What needs investigating**: bisect ~50 commits between v1.6.11 (b285a8e) and B108 (f639561) to find the commit that introduced the slowdown. Likely candidates from the bambu-native Phase 1/2 refactor work, but no specific commit identified. Could also be a device-state shift (Pixel 8a battery wear, storage fragmentation, Android OS update) — re-measure on a different Pixel 8a if available, and compare cold-load on a clean factory-reset device.
- **Tests**: B93 test still guards regression with a 110s ceiling; if the slowdown gets worse, the test will catch it again. Once the bisect finds and fixes the root cause, lower the budget back toward the historical 42s baseline.

### B118: Map & Print dialog reports wrong "Sliced as PLA" warning for single-colour 3MFs with PETG slot preset — FIXED v2.2.4 (no GH issue filed; Discord-reported by DC15)
- **Symptom**: DC15 (Discord 2026-05-16): load `Jumping_frog.3mf` (single-colour PLA-declared 3MF) with E1 slot preset = PETG, no explicit material chip tap on Prepare. The Filament panel shows PETG / 235°C (slot preset fallback), so the user assumes it's set. Slice → Map & Print → maps to a PETG slot → dialog warns "Sliced as PLA but slot has PETG", reversed from reality.
- **Reported by**: DC15 (Discord), v2.2.3 (versionCode 276).
- **Root cause**: `FilamentMappingDialog.kt:142` computed `sliceTimeSlot = sliceTimeColorMapping?.getOrNull(displayFileIndex)` with NO slot-0 default. For single-colour files `sliceTimeColorMapping` is null, so `sliceTimeSlot = null`, the dialog skipped the slot-preset lookup, and `slicedWithMaterial` fell through to the file's declared material ("PLA"). But the slicer's `resolvePerFilamentTypeAndTemp` at `PerFilamentResolver.kt:52` defaults `slot = colorMapping?.getOrNull(i) ?: 0`, so the actual slice used slot 0's preset (PETG). Two cascades diverged on the null-mapping case.
- **Fix**: Added the matching `?: 0` default at `FilamentMappingDialog.kt:151` so the dialog mirrors the resolver. Also extracted the dialog's String-cascade into a top-level `resolveSlicedWithMaterial` helper for unit testability.
- **Tests**: 7 new unit tests in `SlicedWithMaterialTest.kt` covering the cascade (override → sliceTimeSlot → fileDeclared), the DC15 single-colour PETG-slot reconstruction, explicit-override behaviour, and a multi-colour mapped-slot regression guard.

### B109: Rotated model can't be placed across the full bed — constrained to pre-rotation footprint (GitHub #135) — FIXED v2.2.6
- **Symptom**: After rotating a model (e.g. Dragon Scale 90°), the drag placement is constrained to a smaller area than the bed — the model cannot be moved to the right-hand side or other edges. More pronounced when the model is also scaled up.
- **Reported by**: Kevin (Discord), v2.1.2 (versionCode 272). Reopened twice during v2.2.x: once on v2.2.4 (Compose recomposition gap, 90° still wrong), once on v2.2.5 (45° still constrained on non-box meshes like Dragon Scale, worse at scale).
- **Three nested root causes**:
   1. **Stale clamp** (v2.0–v2.1.2): `InlineModelPreview` drag clamp used `modelSizeX/Y * scale` (load-time AABB) for `coerceIn` bounds. After rotation the footprint changed but bounds stayed at load-time values. Same issue in `getPlacementPositions()` and `setCopyCount()`.
   2. **Compose recomposition gap** (v2.2.0–v2.2.4): the first fix added an `effPlaceSizeX/Y` `remember` block correctly keyed on `modelRotation`, but the `LaunchedEffect` that wires `v.onObjectMoved` did not list those values as keys. The lambda closed over the pre-rotation footprint until some unrelated key (objPositions, towerX, …) changed.
   3. **Box-rotation over-estimate** (v2.2.0–v2.2.5): `CopyArrangeCalculator.computeRotatedFootprint` rotates the load-time **box** AABB. For non-box meshes (Dragon Scale, organic shapes), the rotated MESH AABB is smaller than the rotated BOX AABB — box-rotation leaves air in the AABB corners where the actual mesh doesn't extend. At 90° the two agree (axis swap, no air); at 45° the box envelope is up to ~40% larger than reality. The renderer draws the model using the true rotated mesh AABB (`mesh.maxX-mesh.minX`), so any clamp / auto-center / bed-warning computed from the box approximation visibly disagrees with the rendered model.
- **Fix layers shipping in v2.2.6**:
   - **Layer A** (commit 63e8e2a, 2026-05-12, shipped from v2.2.0 onwards — the original "v2.1.3" BACKLOG label was wrong, no such release tag exists): added `CopyArrangeCalculator.computeRotatedFootprint(sizeX, sizeY, sizeZ, rxDeg, ryDeg, rzDeg)` and wired it through `InlineModelPreview` drag clamp, `LargePreviewFallback`, `getPlacementPositions()`, `setCopyCount()`. Added `modelSizeZ` for X-axis tilts.
   - **Layer B** (v2.2.6 review fix #1): added `effPlaceSizeX, effPlaceSizeY, wipeTowerWidth, wipeTowerDepth` to the placement `LaunchedEffect`'s key list so the drag callback (object + wipe tower) re-captures on rotation or prime-tower-width change.
   - **Layer C** (v2.2.6 review fix #2): introduced `CopyArrangeCalculator.effectivePlacementFootprint(rotatedMeshSizeXY, …)` — a single pure helper that prefers the live native preview-mesh AABB and falls back to the box approximation when no mesh has been fetched yet. The mesh AABB matches what the renderer draws (the renderer reads `mesh.maxX-mesh.minX`), so clamps and auto-centers agree pixel-for-pixel.
   - **Layer D** (v2.2.6 review fix #3): plumbed the mesh AABB back to `SlicerViewModel` via a `rotatedMeshSizeXY: StateFlow<Pair<Float, Float>?>` — populated from `onMeshCached` after each native preview fetch, cleared in `invalidatePrepareMeshCache()` so a stale value never leaks across a rotation change. `getPlacementPositions()` and `setCopyCount()` consume it via the helper, so auto-center and bed-warning now use the same true-mesh-AABB math as the drag clamp. PrepareScreen observes the StateFlow via `collectAsState` so a mesh refresh triggers a recomposition that re-evaluates `getPlacementPositions()` with the refined bounds.
- **STL behaviour** (clarification — earlier BACKLOG drafts had this wrong): both STL and 3MF go through the same `getPreparePreviewMesh()` native path after `setModelRotation`. Native bakes rotation into the vertices it returns (`its_transform(its, instance_matrix, …)` in `sapil_model.cpp:516,560`), so the renderer just translates+scales — no rotation matrix in `ModelRenderer.drawModelAt`. The mesh-AABB-aware fix applies equally to STL.
- **Tests**: 5 unit tests in `CopyArrangeCalculatorTest` for `computeRotatedFootprint` (zero/90°/45°/symmetry/180° rotation cases) guard the box-rotation math; 6 unit tests for `effectivePlacementFootprint` guard mesh-vs-fallback selection, scale handling, and the Dragon-Scale-class divergence. 2 minimal structural-grep tests in `InlineModelPreviewRotationKeysTest` guard the Compose-only LaunchedEffect key contract (one for `effPlaceSizeX/Y`, one for `wipeTowerWidth/Depth`).

### B108: Scale-down doesn't re-anchor model to bed — empty initial layer / model floats in air (GitHub #134) — FIXED v2.2.0
- **Symptom**: Scaling a model to 60–90% before slicing triggered *"One object has empty initial layer and can't be printed. Please Cut the bottom or enable supports."* Model also appeared way too small (embedded 5.083× scale was being overwritten by user scale instead of multiplied).
- **Reported by**: DC15 (Discord), v2.1.2 (versionCode 272). Model: `Articulated+Fish+(3).3mf` (MakerWorld, 2.21 MB).
- **Root cause**: `setModelScale()` called `inst->set_scaling_factor(user_scale)` absolutely, overwriting the file's embedded 5.083× scale with 0.6 (producing a 4mm model instead of 21mm). The Z offset (17.5mm) was then unchanged, so world-space bottom = −3.44 × 0.6 + 17.5 = 15.4mm above bed. The multi-object `setModelInstances` path sets `delta.z = 0` (no Z correction for multi-object groups), leaving the model floating.
- **Fix** (`sapil_arrange.cpp`): (1) Snapshot per-instance load-time scaling factors on the first `setModelScale()` call after model load; apply user scale multiplicatively (`effective_sf = loadtime_sf × user_scale`). (2) After updating all instances, recompute the world AABB and apply a Z correction so the group bottom lands at z=0. Reset the snapshot in `sapil_model.cpp` on both `loadModel` and `clearModel`.
- **Follow-up fix** (multi-object gap): the initial fix used a single group-wide Z correction (`-postScaleBB.min.z()`), which only snapped the lowest object to the bed. Multi-object 3MFs where build items have different Z translations (e.g. `skywing-seawing-silkwing.3mf`: 2.89mm vs 16.75mm) still produced `[obj N] empty initial layer` because the higher object stayed floating. Now each instance's Z is snapped independently using its own world AABB.
- **Tests**: `SlicingIntegrationTest#b108_articulatedFish_scaledTo60pct_slicesWithModelOnBed`, `SlicingIntegrationTest#b108_skywingMultiObject_perInstanceZOffsets_bedSnappedAfterScale`; B73 scale-placement regression and `SetModelInstancesOffsetTest` (7 tests) still pass.
- **Related**: distinct from B73 (fixed v1.5.65, XY drift on scale); this is Z re-snap + multiplicative scale from embedded file transform.

### B107: STL bed temp silently bumped +5°C above user setting (GitHub #123) — FIXED v2.2.0
- **Symptom**: User sets bedTemp to 65°C, printer bed runs at 70°C for the entire print.
- **Root cause**: `applyConfigToPrusa()` hardcoded `hot_plate_temp_initial_layer = bedTemp + 5`. The Snapmaker U1 `machine_start_gcode` uses `{bed_temperature_initial_layer_single}` which resolves to 70°C. No subsequent M190 drops the bed back to 65°C. Bambu 3MF files with embedded profiles were unaffected (profile_keys[] overrides the value).
- **Fix**: Removed +5. Both `hot_plate_temp` and `hot_plate_temp_initial_layer` now use `config.bed_temp` exactly. Native `.so` rebuilt.
- **Tests**: `SlicingIntegrationTest#b107_stlSlice_bedTemp65_initialLayerNotBumped`.
- **Issue**: https://github.com/taylormadearmy/u1-slicer-for-android/issues/123

### B106: STL print with non-E1 extruder selected sends wrong extruder + missing PRINT_START (GitHub #122) — FIXED v2.2.0
- **Symptom 1**: Slicing an STL with E3 selected in Filament Mapping → G-code contains T0 (E1) tool changes instead of T2 (E3). Wrong extruder heats and prints. E4 temp anomaly reported on physical printer.
- **Symptom 2**: STL G-code starts with bare `G28` instead of PRINT_START + SM_PRINT_AUTO_FEED + SM_PRINT_FLOW_CALIBRATE macros, causing print failure.
- **Root cause (Bug 1)**: `resolveCanonicalExportMapping()` returned `null` (identity, no rewrite) when `canonicalSize == 0` (STL files have no canonical filament list). T0 was never rewritten to T2 at send time.
- **Root cause (Bug 2)**: STL files have no embedded Snapmaker profile (`is_snapmaker_profile = false`). `profile_keys[]` whitelist only applies when the embedded profile contains PRINT_START. `machine_start_gcode` stayed as OrcaSlicer's bare `G28` default.
- **Fix (Bug 1)**: `PrintTimeRemap.resolveCanonicalExportMapping`: when `canonicalSize == 0` and `selectedExtruder != 0`, return `listOf(slot)` so T0 → T(slot) at send time.
- **Fix (Bug 2)**: Added `machineStartGcode`/`machineEndGcode` to `SliceConfig` (Kotlin + C++ struct + JNI bridge). `applyConfigToPrusa()` applies them when `!has_embedded_profile`. Kotlin reads from `assets/orca_profiles/printer/snapmaker_u1.json` for raw STL slices. Required native `.so` rebuild.
- **Tests**: `CanonicalExportMappingTest` — 4 B106 tests: E1 identity (null), E2/E3/E4 slot remap for STL non-canonical path.
- **Issue**: https://github.com/taylormadearmy/u1-slicer-for-android/issues/122

### B105: Single-slot STL slice emits multi-element nozzle_temperature / filament_type arrays (GitHub #121) — FIXED v2.2.0
- **Symptom**: Slicing an STL with a single extruder slot active produced G-code with incorrectly sized `nozzle_temperature` and `filament_type` header arrays (more than 1 element for a 1-extruder slice).
- **Root cause**: `buildProfileOverrides()` did not clamp array sizes to 1 when extruder count was 1 (single-slot STL path).
- **Fix**: Added 1-element guard for `nozzle_temperature` and `filament_type` in the single-slot case.
- **Tests**: `SlicingOverridesTest` B105 single-slot guard; `FilamentTypeHeaderPatchTest` B105 resolveNonCanonicalHeaderPatchTypes.
- **Issue**: https://github.com/taylormadearmy/u1-slicer-for-android/issues/121

### B104: Single-plate Bambu files fail to slice after re-embed includes off-plate garbage objects (GitHub #119) — FIXED v2.1.1
- **Symptom**: `OreoProj+1.3mf` fails with "No layers were detected." after auto-color-mapping triggers a re-embed at slice time. The slicer loads a 987×510×1268mm model and aborts with "Model too large for bed."
- **Root cause**: The 3MF has 5 build items but only 2 are on the print plate. Initial load correctly applies `filterModelToPlate` via `prepareImportedModelArtifacts(plateId = firstPlateId)`. But `_currentPlateId` is never updated for single-plate files (they skip the plate selector, so `recoveryPlateId` stays at `-1`). When re-embed triggers at slice time, `reembedPlateId = null` → no plate filter → all 5 objects included → oversized bounding box → abort.
- **Fix**: In both `loadModel(uri)` and `loadModelFromFile(file)`, after the single-plate Bambu path: capture `firstPlateId` from `prepared.mergedInfo.plates.first().plateId`, then set `recoveryPlateId = firstPlateId` after `loadNativeModel()`. Ensures re-embeds use the same plate filter as the initial load.
- **Test**: `BambuPipelineIntegrationTest#b104_oreoProj_singlePlateBambu_withPlateFilter_slicesSuccessfully` — new instrumented test with `Oreo+Proj+1.3mf` asset.
- **Issue**: https://github.com/taylormadearmy/u1-slicer-for-android/issues/119
- **Related**: B101 (#116) — same file, initial placement (off-plate objects, same cause).

### B103: Filament Mapping dialog shows false mismatch warning / misses real remapping conflicts (GitHub #118) — FIXED v2.0.3
- **False positive symptom**: Slot presets include PETG (e.g. E2=PETG) but file declares PLA → dialog shows red "Slot loaded as PETG, filament needs PLA" warning even though the slicer used PETG temperatures (slot preset wins over file material in `resolvePerFilamentTypeAndTemp`). No actual conflict.
- **False negative symptom**: User manually re-maps a filament in the dialog to a slot with a different material than it was sliced with (e.g. sliced as PETG via E2, user picks E1=PLA) → no warning shown, but G-code temperatures don't match the loaded spool.
- **Root cause**: Mismatch check compared raw file-declared `entry.materialType` against the selected slot's material, ignoring that `resolvePerFilamentTypeAndTemp` prefers the slice-time slot preset over file-declared material, and that the user can re-map after slicing.
- **Fix**: Reconstructs the sliced-with material (Prepare override → slice-time slot preset → file-declared) and warns when the dialog-picked slot differs from that. Warning text: "Sliced as X but slot has Y — temps may not match".
- **Issue**: https://github.com/taylormadearmy/u1-slicer-for-android/issues/118

### B102: Sliced G-code requires phantom PLA in slot 1 when user only intended PETG (GitHub #117) — FIXED v2.0.2
- **Symptom**: Job sliced with all material set to PETG prints correctly the first time (heaters reach PETG temps). Restarting the same uploaded G-code from the printer UI: Filament Setup demands PLA in slot 1 alongside the PETG already loaded. User cannot start the print without loading PLA they don't want.
- **User-facing impact**: Re-running an already-uploaded job from the printer is blocked. Workaround for the first run is unclear — heater temps may have been correct only because the user happened to have PLA-equivalent temps on a different slot, not because the slicer honoured the override.
- **File**: `OreoProj1.3mf` (same file as B101 / #116). Diagnostics show `extruderCount: 2`, `colorMapping: [2,3]`, `toolRemapSlots: [2,3]` — neither colour mapped to slot 0 / E1, yet the printer demands PLA in slot 1.
- **Printer dialog evidence**: Filament Setup shows `PLA · 3.6 g · slot 1` and `PETG · 9.0 g · slot 3` — the 3.6 g PLA suggests real extrusion (likely prime/wipe/purge) is being attributed to a canonical default tool the user never chose, OR the `; filament_type =` header is not patched for all canonical slots.
- **User comment**: "old issue cropping up" — implies a regression of B63 (`fixFilamentTypeHeader`) territory.
- **Likely area**: `SlicerViewModel.fixFilamentTypeHeader` / `resolveFilamentTypesForHeaderPatch` for header-line coverage of unused canonical slots; `sapil_print.cpp` `config.filament_types[]` plumbing; prime-tower/wipe tool-index emission relative to `colorMapping`.
- **Investigation should differentiate**: (a) header-only mismatch vs (b) body-level extrusion against an unused canonical slot.
- **Issue**: https://github.com/taylormadearmy/u1-slicer-for-android/issues/117
- **Source**: Discord, 2026-05-03 (Jon).

### B101: OreoProj1.3mf opens with items off the plate; switching to the only plate fixes placement (GitHub #116) — FIXED v2.0.2
- **Symptom**: Opening `OreoProj1.3mf` (Bambu Studio export, single plate) renders the model parts off the build plate in the Prepare viewer. Tapping Change plate → select plate 1 (the only available plate) reloads the model with parts correctly on the plate.
- **User-facing impact**: First-load placement is wrong; user must manually switch plates to recover. Also blocks copies/auto-arrange for this file (Jon: "I am unable to print more than one copy because of the initial placement and the way it sees the wide space as one object").
- **File**: 5 volumes / 155k triangles, ~170 × 171 × 5.8 mm (flat cookie shape).
- **Likely area**: divergence between `loadModelFromFile` initial-load path (single-plate fast path via `prepareImportedModelArtifacts`, plate-scoping and `loadModelForPlate` callback skipped) and `selectPlate` (full plate-scoping + `buildSelectedPlateInfo` + `readPlateStateFromNative` re-derives placements). Need to verify whether the BBS plate's `obj_inst_map` carries a placement transform that the initial-load path skips.
- **Possible regression source**: B98 / B93 single-plate fast path that bypasses `embedProfile` for multi-plate files — may also bypass plate-transform application for single-plate files where it is still needed.
- **Issue**: https://github.com/taylormadearmy/u1-slicer-for-android/issues/116
- **Source**: Discord, 2026-05-03 (Jon).

### B100: Layer-height override appears ignored — same 197 layers at 0.12mm and 0.2mm (GitHub #115) — FIXED v2.0.2
- **Symptom**: Reported on `L-ONE0.12mm.3mf`. File lists itself as 0.12 mm in UI → slices to 197 layers. Override layer height to 0.2 mm → also 197 layers. Expected ~118 layers at 0.2 mm.
- **User-facing impact**: Layer-height override silently no-ops (or both paths converge on the same height for non-obvious reasons). Print quality / time estimates wrong, and the override UI gives users false control.
- **Likely area**: `SlicingOverrides.resolveInto`, `SlicerViewModel.buildProfileOverrides` (does override emit both `layer_height` and `initial_layer_print_height`?), `sapil_print.cpp` `applyConfigToPrusa` + `profile_keys[]` whitelist (the 3MF's embedded `project_settings.config` may overwrite the Kotlin override at slice time).
- **Smoking gun to chase**: compare `; layer_height = …` and `; initial_layer_print_height = …` header lines in the two G-codes Jon shared (NextCloud links in the issue) — if both say 0.12, override never reached the engine; if 0.20 says 0.2 but layer count is unchanged, layer count is being computed from a different source (e.g. cached estimate).
- **Issue**: https://github.com/taylormadearmy/u1-slicer-for-android/issues/115
- **Source**: Discord, 2026-05-02 (Jon).

### B99: Dissimilar support filament/material mapping is wrong (GitHub #113) — FIXED v2.0.1
- **Symptom**: Selecting a model filament by material does not keep the UI and tool/head mapping aligned. Reported example: TPU is loaded in tool 3, but choosing TPU still displays "filament 1".
- **Support/interface symptom**: When support or support-interface filament is set to a different filament number than the model, the support settings UI does not expose the corresponding filament type. After slicing, the mapping only shows one filament/material type.
- **User-facing impact**: Models that intentionally use different materials for model/support/interface can slice or present mapping with the wrong filament identity, especially TPU model + PLA support/interface workflows.
- **Likely area**: Phase 2 canonical filament list + Prepare-screen filament override flow. Check `PrintSetupSection`, `FilamentMappingDialog`, `applyOverridesToCanonical`, support/interface override propagation, and post-slice summary/mapping population.
- **First repro target**: Create or identify an STL/3MF where model uses TPU on file/tool 3 and support/interface uses PLA on file/tool 4; verify Prepare labels, support filament picker, slice-time config, and post-slice mapping all preserve distinct material types.
- **2026-05-03 Benchy STL summary fix**: User repro `3DBenchy.stl` with Support Filament `E2 · PETG` and Interface Filament `E3 · PETG` generated valid multi-extruder G-code (`support_filament = 2`, `support_interface_filament = 3`, `filament used [mm] = 3950.12, 1898.68, 24.64`) but Slice Summary showed only `Filament 1 · PLA`. Root cause was `computePlateFileIndices()` filtering post-slice active G-code slots to `canonicalSize=1` for STL. Fix returns `null` when post-slice G-code has active slots wider than the STL canonical list, allowing `SliceCompleteSummaryCard` to render the raw G-code slots instead of hiding support/interface usage.
- **2026-05-03/04 review follow-up**: Fixed header patch sizing for Bambu canonical lists where support/interface filament slots exceed canonical size; fixed sparse support-option mapping collision; made STL support slices auto-enable prime tower via `resolvePrimeTower()` after support-driven extruder-count expansion; added `SliceConfig.filamentTypes` JNI/native plumbing so raw STL multi-extruder support/interface slices pass per-slot material types into OrcaSlicer before slicing instead of only patching the G-code header afterward; tightened B99 tests to assert exact header lines and positive support-slot extrusion.
- **2026-05-03 tests**: Red-green JVM guard `ComputePlateFileIndicesTest#STL support-driven extruders wider than canonical fall back to raw gcode slots`; on-device guard `SlicingIntegrationTest#benchy_stl_appPlaced_supportPetgE2_interfacePetgE3_slicesSuccessfully` passes on Pixel 8a. Review follow-up also passes full JVM unit tests plus targeted Pixel 8a `BambuPipelineIntegrationTest#leoSupport_plate1_supportPetgE3_interfacePetgE4_slicesSuccessfully` and `SemmSlicingTest#coloredBenchy_supportPetgE3_interfacePetgE4_slicesSuccessfully`.
- **2026-05-04 release**: Fixed in `v2.0.1`. Full 27-scenario batch manual E2E passed on Pixel 8a, with every exported G-code file reporting `export_T4_T9: 0`.
- **Issue**: https://github.com/taylormadearmy/u1-slicer-for-android/issues/113
- **Source**: GitHub #113, 2026-05-01.

### B98: Performance investigation plan for large-model load, preview readiness, and safe native optimisation (GitHub #114) — OPEN
- **Goal**: Identify and implement user-visible performance wins without weakening stability, colour/material accuracy, Bambu settings fidelity, or device safety.
- **Plan**: `PERFORMANCE_INVESTIGATION_PLAN.md` on branch `codex/performance-investigation-plan`.
- **Issue**: https://github.com/taylormadearmy/u1-slicer-for-android/issues/114
- **Context**: The pre-move native real-TBB PoC showed measurable upside: two representative tests were about 20% faster, and the four-test mini-benchmark improved from `392.977s` to `347.353s` (~13.1%) while 873 JVM tests, 200 instrumented tests, and a 77-test high-risk colour/stability batch passed. The later post-move PoC did not invalidate that evidence; it failed to reproduce a clean comparable benchmark, and the old Shashibo harness must not be used as the basis for accepting or rejecting performance work. Known implementation hazard: static `global_control` crashed during setup.
- **2026-05-03 release comparison**: Release `v2.0.0` targeted fixture runs pass for Dragon Scale, Shashibo, colored Benchy, and slip-slide-spin, but the historical direct fixture harness is ambiguous: fixture JSON says `plateIndex`, while `BambuFixtureHarnessTest` passes that value directly to `ProfileEmbedder.embed(..., plateId = ...)`. For example `shashibo-plate5.json` has `plateIndex=4`, and logs show the harness filters `plate 4`, so the release pass does not prove true app-path Shashibo plate 5 coverage. Current real app-path Shashibo plate 5 coverage is `PreparePreviewViewModelTest#shashiboPlate5_selectPlate_appPathLoadsMultiExtruderPreparePreview` and it passes.
- **2026-05-03 current-branch blocker**: After restoring the direct harness to release-equivalent behavior, current branch still crashes `fixture_shashibo_plate5` under low-memory pressure after `Print validation: OK` and `Estimated completion time: 8h 37m 32s`; release `v2.0.0` passes the same targeted fixture quickly. Temporarily swapping in the release native `.so` did not fix the current-branch crash, so the remaining suspect is current Kotlin/config/prep behavior rather than the rebuilt native binary alone. Do not skip or normalize this failure without an explicit release decision.
- **2026-05-03 connected-suite context**: `PreparePreviewViewModelTest#b83_paintedFlippy_selectPlate5AfterPlate4_hasTwoChips` fails the same way on release `v2.0.0` and current branch (`expected 2 chips, got [#F4D976]`), so it is not introduced by B99. `PreparePreviewViewModelTest#buzzLightyear_plateSwitch_preparePreviewReflectsCurrentPlatePalette` passes targeted on both release and current; its full-suite timeout appears suite-order/device-pressure sensitive, not a deterministic current-branch product failure. Keep both visible in the regression lane until resolved.
- **Initial focus**: large model loading, time to first usable preview, Bambu metadata/settings paths, and only then narrow slicing hotspots with fixture-level canaries.
- **First milestone**: add/capture stage timing on `main` for representative large fixtures and identify the largest load/preview bottleneck before implementing optimisations.

### B97: H2C state-fold helper has no provenance check — defensive cleanup (GitHub #150)
- **Symptom**: `TriangleSelector::h2c_state_matches` (orcaslicer fork, `src/libslic3r/TriangleSelector.cpp:1428`) returns true for `actual = query+4` whenever `query in [1..4]` regardless of whether the file is genuinely H2C (4-slot dual-AMS) or a normal multi-filament Bambu file with > 4 declared filaments.
- **User-facing impact**: NONE currently. Buzz plate 8 (the original trigger of this concern) was the multi-state-variant manifestation only — saved G-code is clean (verified 2026-04-30, `filament_used_mm[1]=0` with state-6 painted geometry not bleeding into bucket 2). The slicer's downstream merge/projection step in `MultiMaterialSegmentation` collapses the duplicate-state buckets so phantom extrusion never reaches the output.
- **Latent risk**: a future file shape that paints with state in 5..8 in a slicer code path that does NOT collapse via `merge_segmented_layers` could surface phantom extrusion. Specifically files with `filament_colour.size() > 4` where the user maps states 5..8 to physically distinct slots.
- **Proposed fix**: thread an `h2c_active` flag through TriangleSelector (set by the BBS importer when `filament_settings_id` matches `@BBL H2C` or similar — see `SlicerViewModel.kt:4722` for the Kotlin H2C detection markers) and gate the fold on it. The current fold then activates only on real H2C files.
- **Not a ship blocker**: defensive only; covered by guard tests on Buzz plate 8 G-code (`buzzLightyear_plate8_prepareAndPreviewColoursAgreeByRegionSize` C1 assertion) and the 18-test instrumented sweep including H2C colored_3DBenchy.
- **Source**: surfaced in the 2026-04-30 code review (post-Buzz fix) as concern C1+C2.

### B96: SEMM-painted files emit canonical-fileIndex T-indices instead of physical-slot — pre-existing, amplified by Phase 2 (GitHub #149) — OPEN
- **Symptom**: SEMM (paint-state) painted files produce G-code with T-index spread that doesn't match desktop Snapmaker Orca's output. Most extreme on `colored_3DBenchy (1).3mf`:
  - Desktop Snapmaker Orca: `T0=3, T1=4, T2=2` (3 tools used for the 3 visible paint regions)
  - v1.6.13 Android: `T0=2, T1=5, T2=3, T3=2` (4 tools, T3 unused in desktop)
  - v1.7.0-dev Phase 2 narrowed gate: `T0=15, T1=19, T2=19, T3=0, T4-T9=73` (10× more transitions, spread to canonical 10-wide)
- **Distinct from B62/B92/B95**: those were paint-state decoding/permutation issues with concrete fixes. B96 is the slicer-side T-index spread itself — the slicer emits per-canonical-slot transitions even where no paint regions are assigned to those slots.
- **User-facing impact**: Send → printer still works (PrintTimeRemap converts canonical → physical at upload time). Save Gcode + Share Gcode also work in Phase 2 after the 2026-04-28 fix that routes them through the same remap. The amplification inflates wipe-tower waste for SEMM files (more transitions than the model actually needs) but does not cause print failure.
- **Other affected fixtures (verified in 2026-04-28 E2E batch)**:
  - `old.3mf` (legacy SEMM 2-colour): 1.9× transition amplification (782 → 1496 lines)
  - `PrusaSlicer-printables-Korok_mask_4colour.3mf`: T4 added to body (5-wide canonical)
  - `skywing-seawing-silkwing.3mf`: 2× transition amplification (11 → 22)
- **Per-object and layer-tool paths are NOT affected** (verified clean on Dragon Scale plate 3, slip-slide plate 3, Button-for-S-trousers, foldy+coaster, calib-cube, Shashibo plate 5).
- **Pre-existing**: confirmed via desktop reference (`G:/My Drive/Logs/colour_3DBenchy_PLA_1h15m.gcode`) and by direct snapshot comparison of v1.6.13 vs Phase 2 narrowed-gate harness output. v1.6.13 was already wrong vs desktop; Phase 2 amplifies the same underlying bug because the canonical-list expansion sizes per-extruder arrays wider, and SEMM segmentation iterates them.
- **Investigation not started**: needs to identify why OrcaSlicer's `multi_material_segmentation_by_painting()` emits transitions for canonical slots with no painted triangles. May be in OrcaSlicer's wipe-tower / per-extruder per-layer purge cycle path. Track in a separate branch from Phase 2.
- **Not a Phase 2 ship blocker**: Send-to-printer + Save/Share remap paths are correct; print correctness preserved. Cosmetic / waste-of-filament issue only.
- **Source**: Surfaced during 2026-04-28 G-code differential investigation; user reports `colored_3DBenchy` "has been a trouble file forever".

### B95: Buzz plate 9 paint state dropped by slicer — only T0 in G-code (GitHub #102) — FIXED v1.6.13
- **Symptom**: On v1.6.10, Buzz Lightyear plate 9 Prepare showed 2 distinct colours correctly (peach #FFD6C1 + white #FFFFFF per the B90 detection fix), but the sliced G-code contained only `T0` (3 tool changes across 605 layers) with no `T1`/`T2`/`T3`. Slice summary reported a single extruder; G-code 3D preview rendered the whole model in the renderer's default slot-0 colour.
- **Distinct from B92**: B92 was about Prepare ↔ Preview palette alignment on plates where OrcaSlicer's print order disagreed with detectedColors. Plate 9's slicer never emitted the paint state as a tool change, so this is a paint-segmentation / embedder issue upstream of any palette alignment.
- **v1.6.11 investigation** — tried bumping `computeEmbedTargetCount` to `max(usedExtruderIndices)` (sized via the wrong heuristic) so the embedded `filament_colour` had 10 entries — but the slicer STILL emitted only `T0`. The attempt also regressed plate 8's Preview palette because `semmColorPermutation` expanded to 10 entries confused `normalizeGcodePreviewColors`'s B92 loop. Reverted.
- **Root cause**: plate 9's `paint_color` attribute values are **bit-packed** (e.g. `"8C"` = state 11, `"3C"` = state 6). OrcaSlicer's `TriangleSelector::deserialize` reads the hex string RIGHT-TO-LEFT, each char contributing 4 bits LSB-first, with an extended-state marker (`0b1100`) and continuation nibble. The Kotlin `paintCharToState` first-char heuristic disagreed: it returned 8 and 3 for those values. So `max(usedExtruderIndices)` undercounted the real max state, and v1.6.11's bump to size 10 was still one short of the state-11 paint region that the slicer's segmentation would address.
- **Fix (v1.6.13)** —
  - **`PaintColorDecoder`** (new, `app/src/main/java/com/u1/slicer/bambu/PaintColorDecoder.kt`): faithful Kotlin port of `TriangleSelector::deserialize` that walks each triangle's bit-packed encoding and returns the set of leaf paint states. 22 JVM unit tests cover small (`"4"`→{1}, `"8"`→{2}), extended (`"0C"`→{3}…`"DC"`→{16}, `"8C"`→{11}, `"3C"`→{6}), split-tree, and edge cases (empty, malformed, lowercase, extended-without-continuation).
  - **`ThreeMfParser`**: replaced the first-char `paintCharToState` heuristic with `PaintColorDecoder.decodeStates(spec)` in both the file-level `parse` and the per-plate `computeVisualColorCountByPlate` path, so `usedExtruderIndices` reflects every state the native slicer will actually see.
  - **`computeEmbedTargetCount`**: gained `maxSourceFilamentIndex` parameter (defaults to 0); when paint data references higher 1-based filament indices than the user's distinct-slot count, the function returns `max(baseSize, maxSourceFilamentIndex)` so `multi_material_segmentation_by_painting()` can address every state.
  - **`computeExpandedGcodeRemap`** (new): when the embed was bumped, the slicer emits `T<filament-1>` for each used filament instead of compact `T0..T(N-1)`. This function returns a list mapping each emitted T-index back to the user's physical slot via `colorMapping`. Out-of-band T-indices fall back to identity-clamped slots so stray emissions don't wrap to slot 3 via `GcodeParser`'s `coerceIn(0, 3)`.
  - **`normalizeGcodePreviewColors`**: gained `useDirectSlots` flag. When the expanded remap is in use, the parsed G-code's `move.extruder` already carries physical-slot indices, so the legacy `semmColorPermutation` / `slicerColorOrder` swap branches must be bypassed and `activeExtruderColors` used directly.
  - **`SlicerViewModel.startSlicing`**: computes `expandedRemap` with the bumped `embeddedFilamentCount` and uses it as the post-slice `composedRemap` (taking precedence over the legacy `composeSemmRemap`), and exposes `gcodeUsesPhysicalSlots: StateFlow<Boolean>` that the inline preview + full G-code viewer plumb to `normalizeGcodePreviewColors(useDirectSlots = …)`.
- **Plate 9 result**: slicer now emits T9 (121 lines, object default = filament 10 = white) + T10 (119 lines, paint state = filament 11 = peach); after expanded remap → `T0=121, T1=119` in the post-slice G-code. `B95Plate9PaintStateTest.plate9_slicedGcodeHasTwoTools` (unignored) now PASSES.
- **No regression on plate 8** (`buzzLightyear_plate8_prepareAndPreviewColoursAgreeByRegionSize`): with the embed bumped to 10 the slicer emits T2 (filament 3 = paint) + T9 (filament 10 = body); expanded remap collapses to T0 + T3, the renderer paints `extruderColors[0]=red` + `extruderColors[3]=white` directly via `useDirectSlots`. Existing test still asserts the OLD swap-path math succeeds (and it does, because the math is still correct in isolation), and the new `useDirectSlots` path is verified by the v1.6.12 `buzzPlate8_parsedGcodeMustReflectPostRemapToolIndices` regression guard.
- **Tests**: 22 new unit tests in `PaintColorDecoderTest`; `B95Plate9PaintStateTest.plate9_slicedGcodeHasTwoTools` unignored.
- **Test file**: `Buzz_Multipart_3MF_Bambu.3mf` plate 9 (in `app/src/androidTest/assets/`).
- **Source**: Discord user DC15, 2026-04-23

### B94: User drag-to-move object position lost after slicing (GitHub #99) — REGRESSION GUARD v1.6.10
- **Symptom (reported)**: User drags object to a new bed position on Prepare → slice → Preview shows the object back at the default (front-centre) position. Reproduces on single-object Spiderman file. Wipe tower IS preserved.
- **Investigation**: New instrumented test `spiderman_dragToRight_preservesPositionThroughSlice` calls `applyPlacementPositions(dragPositions)` directly, slices, and parses the G-code's X extents. Passes on Pixel 8a — the ViewModel → native `setModelInstances(custom)` path correctly propagates the drag offset through re-embed and slice, and the G-code reflects the drag destination (right edge ~270mm for dragX chosen to push the bottom-left to the far-right edge).
- **Status**: Regression guard landed; not reproducing at VM level on Pixel 8a post-v1.6.10. If DC15 reproduces again after v1.6.10 installs, the bug is UI-specific — the drag handler path may not always reach `applyPlacementPositions` under certain tab-nav or state-change sequences, or the 3D viewer's camera framing masks correct G-code output. Follow-up investigation in that case.
- **Test file**: `spiderman-hanging-pre-cut.3mf` (ASCII-safe copy of `hanging+pre+cut+colour+3mf.3mf` from G-Drive `tes-data/`) in `app/src/androidTest/assets/`
- **Source**: Discord user DC15 on 2026-04-22

### B93: Buzz Lightyear 73MB 3MF cold load ~100s + plate switch ~36s (GitHub #97) — FIXED v1.6.11 (phases 1+2)
- **Symptom**: 73MB multi-plate Bambu 3MF takes ~100s from LOAD_FILE to plate selector, then ~30-40s per plate selection on Pixel 8a. Distinct from B91 (Skywing), which was `parseForPlateSelection` specific.
- **Fix (phase 1, v1.6.10)**: `prepareImportedModelArtifacts` short-circuits the full-file `embedProfile` call when `origInfo.isMultiPlate` and there are 2+ plates. Pre-plate-selection state (`currentModelFile`, export artifacts) falls back to the sanitized processed file, which is acceptable since export of a multi-plate file without a selected plate was already ambiguous.
- **Fix (phase 2, v1.6.11)**: `ThreeMfParser.parse` skips the per-component paint-state scan entirely for multi-plate files. `parseForPlateSelection()` re-runs this scan per plate on `selectPlate()`, so the full-file scan's `paintStateCount` → `detectedExtruderCount` is redundant for multi-plate shapes. On Buzz the phase 2 skip removes another ~20s from cold load.
- **Combined result on Pixel 8a**: Buzz Lightyear plate selector visible in ~42s (was ~100s before v1.6.10, ~62s after phase 1).
- **Regression guard**: `buzzLightyear_coldLoad_skipsFullFileEmbedOnMultiPlate` — asserts plate selector appears within 90s (was 180s in v1.6.10) AND `currentModelPath` points at a `sanitized_*.3mf` artifact (NOT `embedded_sanitized_*.3mf`).
- **Phase 3 deferred**: parallelising `BambuSanitizer.process` component reads. Revisit only if cold load remains unacceptable.
- **Test file**: `Buzz_Multipart_3MF_Bambu.3mf` in `app/src/androidTest/assets/`
- **Source**: Surfaced during v1.6.9 manual testing on 2026-04-22

### B92: Prepare ↔ Preview colour swap on per-object + paint-state SEMM plates (GitHub #96) — FIXED v1.6.10 (palette ordering) + v1.6.12 (parsed-G-code timing)
- **Symptom**: On Bambu SEMM plates that combine a per-object default extruder with a *higher* source filament index than any paint state in use (Buzz plate 7, 8, 4, 9), Prepare and the G-code Preview render the same triangles in *swapped* colours. Pre-v1.6.9 both were wrong in compatible ways (Prepare clamped, Preview off); the B88 fix made Prepare correct and exposed the Preview-side mismatch.
- **Root cause (v1.6.10 fix)**: `normalizeGcodePreviewColors` indexes its palette by `detectedColors` order (filament-index ascending), but OrcaSlicer emits T0 for the object's default extruder first, then paint states ascending. The two orderings disagree whenever the object default has a higher source filament than any paint state.
- **Fix (Option 2 — Preview-side only, v1.6.10)**: New `computeSlicerColorOrder` helper derives the slicer's print-order → detectedColors-index permutation from `usedExtruderIndices` + `objectExtruderMap`. `applyMultiColorAssignments` stores the result on `SlicerViewModel.slicerColorOrder` (StateFlow). `normalizeGcodePreviewColors` gains two optional params (`semmColorPermutation`, `slicerColorOrder`); when both are non-null it maps each physical T-index back through `semmPerm → slicerColorOrder → colorMapping → extruderColors` so the Preview palette matches Prepare's compact-index palette. Identity cases (no object default, default already at detectedColors[0], H2C, non-paint) fall back to the direct-slot palette and the existing contract.
- **Re-surfaced after v1.6.10/v1.6.11**: User taylormadearmy reproduced DC15's original blue-stripes screenshot on Pixel 8a with **default presets** (E1=red, E2=green, E3=blue, E4=white); colorMapping auto-resolved to `[0, 3]` for Buzz plate 8. Prepare correctly rendered RED/WHITE; Preview painted the stripes **sky blue**.
- **Root cause (v1.6.12 fix)**: `SlicerViewModel.startSlicing` parsed the G-code (via `validateSliceOutput → GcodeParser.parse`) BEFORE calling `GcodeToolRemapper.remap`, then exposed the resulting `ParsedGcode` via `_parsedGcode` to the Preview screen. With `composedRemap=[0,3]` the file was rewritten T1→T3, but the StateFlow already held moves tagged with the pre-remap compact extruder index `1`. The `GcodeRenderer` painted those moves with its default slot-1 palette colour `(0.2, 0.7, 1.0)` = sky blue — the user's exact screenshot — because `activeExtruderColors` for `colorMapping=[0,3]` was `[red, "", "", white]` and `setExtruderColors` skips blanks.
- **Fix (v1.6.12)**: Move `GcodeToolRemapper.remap(...)` to run **before** `validateSliceOutput(...)` so the parsed `ParsedGcode` reflects post-remap T-indices. The Preview renderer now sees only physical slot indices that the user mapped (slots 0 and 3 for the [0,3] case), and the renderer's untouched default palette never leaks through.
- **Tests**: v1.6.10 added 9 unit tests in `SlicerColorOrderTest.kt`, 3 cases in `PreviewColorNormalizationTest`, and the instrumented `buzzLightyear_plate8_prepareAndPreviewColoursAgreeByRegionSize` (asserts `slicerColorOrder=[1,0]` and per-T<n> Preview palette match). v1.6.12 adds `buzzPlate8_parsedGcodeMustReflectPostRemapToolIndices` — loads Buzz plate 8 with default presets, slices end-to-end, then asserts (1) `viewModel.parsedGcode.value` has zero moves tagged with extruder slots {1, 2} and (2) the post-remap G-code file contains no executable T1 / M104 T1 / SM_ EXTRUDER=1 / SM_ INDEX=1 patterns in non-comment text.
- **Test file**: `Buzz_Multipart_3MF_Bambu.3mf` plate 8 (also plates 4, 7, 9)
- **Source**: v1.6.9 manual testing 2026-04-22 (DC15); re-surfaced 2026-04-23 (taylormadearmy on default presets)

### B91: parseForPlateSelection takes 3m31s on dense SEMM (Skywing Dragon) — FIXED v1.6.9 (GitHub #94)
- **Symptom**: Selecting a plate on dense painted models (Skywing: 162K `paint_color` attributes across 35 component model files) stalled the UI for ~3.5 minutes. No user-visible log activity between `restructurePlateFile` and the next SlicerVM event.
- **Root cause**: `computeVisualColorCountByPlate` read every paint_color via per-line regex into an unbounded `LinkedHashSet`.
- **Fix**: Callback-based `streamCollectPaintSpecs` + `EarlyExit` sentinel. Once 32 unique specs and ≥2 states observed, complex encoding is confirmed and the reader stops. Simple-encoded models still collect the full set. Skywing plate 1: 3m31s → ~2s.
- **Source**: Surfaced during B87/B88 investigation 2026-04-22
- **Test file**: `skywing-seawing-silkwing.3mf` on G-Drive

### B90: Buzz plate 9 detectedColors reports filaments 1-2 instead of actual high-index filaments — FIXED v1.6.9 (GitHub #93)
- **Symptom**: Buzz Lightyear plate 9 — objects use filament 10 (white) with paint state 8 (peach), but `detectedColors` reported `[#000000, #0086D6]` (filaments 1-2). After B88 compaction fix the preview had 2 distinct colours but the wrong slot presets.
- **Root cause**: `parseForPlateSelection` synthesised `effectiveExtruders=(1..visualCount)` whenever paint visual count exceeded unique object extruders. Worked for low-index cases; dropped high indices.
- **Fix**: When max(objectExtruders) > visualCount, use union of actual object extruders + paint states. Low-index cases keep the synthetic range (preserves B84 slip-slide four-colour chip count).
- **Tests**: `buzzLightyear_plateSwitch_preparePreviewReflectsCurrentPlatePalette` asserts plate 9's detectedColors includes both `#FFFFFF` and `#FFD6C1`.
- **Source**: Surfaced during B87/B88 investigation 2026-04-22

### B89: Prepare preview Info (I) menu not scrollable — buttons inaccessible — FIXED v1.6.9 (GitHub #92)
- **Symptom**: The Info (ℹ︎) menu on the Prepare preview is not scrollable. Long file titles or portrait orientation push the action buttons off-screen and they cannot be reached.
- **Fix**: Wrap the menu body in a vertical scroll container so content reflows regardless of length/orientation.
- **Source**: User report, 2026-04-21

### B88: Multi-colour mapping inconsistent in Prepare preview across plates (Buzz Lightyear) — FIXED v1.6.9 (GitHub #89)
- **Symptom**: On a multi-plate Bambu 3MF, Prepare preview does not reliably reflect per-part 2-colour assignments. Fresh load → Prepare shows single colour, G-code preview correct. Plate 9 → Prepare shows one colour (apparently from previous plate), G-code preview also single colour but different from Prepare. Switching back to plate 8 behaves as first sequence.
- **Root cause**: TBD — possibly colour mapping leaking across plate switches or between load and preview-effect emission. May relate to B83 (plate-switch objectIds) / B86 (DataStore presets race).
- **Source**: Discord user DC15 on v1.6.7, 2026-04-21
- **Test file**: TBD — MakerWorld model 2602980 (Buzz Lightyear Multipart Fanart); not yet on G-Drive

### B87: Black layers in Prepare preview missing from G-code preview after slicing — FIXED v1.6.9 (GitHub #88)
- **Symptom**: Skywing Seawing Hybrid Dragon 3MF — bottom layers shown as black in Prepare screen do not appear black in the G-code preview after slicing. The actual print matches the incorrect G-code preview (no black on bottom layers). Reproducible at both 70% and 100% scale; eye components show correct colours.
- **Root cause**: TBD — confirmed file-specific.
- **Source**: Discord user DC15, 2026-04-20
- **Test file**: `天翼，海翼，丝翼拆件多色.3mf` (test-data folder on G-Drive); MakerWorld model 2661371

### B86: S-Buttons Prepare preview shows 3 colours instead of 4 — FIXED v1.6.1 (GitHub #85)
- **Symptom**: `Button-for-S-trousers.3mf` Prepare preview intermittently showed yellow, white, blue but not pink (E4). G-code preview after slicing showed all 4 correctly.
- **Root cause**: DataStore race in `SlicerViewModel.loadNativeModel`. `extruderPresets.value` read the StateFlow's initial placeholder (`defaultExtruderPresets()` = red/green/blue/white) before DataStore had emitted the actual stored user presets. `findClosestExtruder` then mapped S-Buttons detected colours against the defaults, producing a non-identity `colorMapping`. Later `refreshMappedPreviewColors` updated `activeExtruderColors` to the correct user colours but never updated `colorMapping`, so the Compose palette `colorMapping.map { slot → extruderColors[slot] }` incorrectly assigned E4 objects (slot 3) to `extruderColors[colorMapping[3]]` which pointed at white (E2's slot).
- **Fix**: `loadNativeModel` now calls `settingsRepo.extruderPresets.first()` instead of `extruderPresets.value`, suspending until DataStore emits the real stored presets.
- **Tests**: `sButtons_plate1_withUserLikePresetsWhiteE2PinkE4_showsFourDistinctColors` in `PreparePreviewViewModelTest`.
- **Source**: User report 2026-04-20

### B79: STL UI settings ignored — extruder selection, support type, and likely others — FIXED v1.6.2 (GitHub #84)
- **Symptom**: Multiple UI settings are silently ignored when slicing STL files:
  - Selecting a non-E1 extruder always slices on E1 (filament run-out if E1 is empty)
  - Setting support type to "tree" uses regular supports instead
  - Likely other settings (wall count, infill density, brim type, etc.) affected too
- **3MF files work correctly** — STL-only
- **Root cause**: The slicer has two paths for settings reaching the native engine (see CLAUDE.md "Profile Key Pipeline"):
  - **Path 1** `applyConfigToPrusa()`: hardcoded fallbacks, always applied
  - **Path 2** `profile_keys[]` whitelist: only applied when `is_snapmaker_profile = true` (i.e. an embedded Snapmaker profile is present — never true for STL)
  - UI overrides from `buildProfileOverrides()` go through Path 2. Any setting not also covered by a hardcoded fallback in Path 1 is silently dropped for STL files.
  - Additionally, `SlicingOverrides.resolveInto()` was missing `supportType` and `supportAngle`, so those overrides never reached `SliceConfig` even before JNI.
- **Fix**: Added to `applyConfigToPrusa()` for `!has_embedded_profile` (STL) path:
  - `support_type` — mapped from `config.support_type` string → `SupportType` enum (normal/tree auto/manual variants)
  - `filament_type` — per-extruder string array from `config.filament_type`
  - `brim_type` — derived from `config.brim_width` (0 → `no_brim`, >0 → `outer_only`) to suppress `btAutoBrim` default
  - Fixed `SlicingOverrides.resolveInto()` to include `supportType` and `supportAngle`
- **Tests**: 4 new instrumented tests in `SlicingIntegrationTest` (`benchy_stl_treeSupportType_producesTreeSupportInGcode`, `tetrahedron_stl_filamentType_petg_appearsInGcode`, `tetrahedron_stl_zeroBrimWidth_producesNoBrimType`, `tetrahedron_stl_slicingOverrides_supportType_resolveIntoChain` — the last two added post-release to cover brim_type and the full resolveInto→JNI chain); 5 new unit tests in `SlicingOverridesTest` for `resolveInto` supportType/supportAngle OVERRIDE/ORCA_DEFAULT/USE_FILE modes.
- **Source**: Reddit u/NismoStroke0027 (extruder, 2026-04-20); user report (tree supports, 2026-04-20)

### B78: Shashibo plate 5 Prepare preview oversized + off-centre — FIXED v1.5.70
- **Symptom**: `Shashibo-h2s-textured.3mf` plate 5 showed a Prepare preview with the pyramid filling ~50–60 % of the bed, centred — while v1.5.64 and earlier showed the same model at ~28 % size centred around the plate origin. Slice output was always correct.
- **Root cause**: The B73 fix (v1.5.65 commit bc2c76d) unconditionally called `setModelScale(1f, 1f, 1f)` + `setModelInstances(floatArrayOf(135f, 135f))` before every `getPreparePreviewMesh()`. `setModelScale` is destructive — it overwrites the instance's scaling factor — so it wiped the Shashibo plate's baked 0.6 build-transform scale even on fresh load where no scale reset was needed. `setModelInstances` also forced world.min to bed centre instead of preserving the natural load-time offset.
- **Fix**: Snapshot the load-time instance offsets in `SlicerViewModel.loadTimeInstanceOffsets` right after `loadModel`, and add a `nativeSliceStateDirty` flag that flips true only when `prepareSlicer()` has clobbered native scale/instance state. `InlineModelPreview` skips the B72/B73 reset entirely on fresh loads; when dirty, it uses `setModelInstances(loadTimeInstanceOffsets)` instead of the hard-coded `(135, 135)` so subsequent resets still preserve the plate's original XY.
- **Tests**: 3 new instrumented tests in `NativePreparePreviewTest`: `shashiboPlate5_preservesFileNaturalScaleAndCentre`, `shashiboPlate5_afterSliceState_restoresSingleInstance`, `shashiboPlate5_naturalLoadBaseline`. Existing B72 calicube reset test + Dragon plate 3 + Sydney Buttons + Korok orientation tests all still pass.
- **Confidence**: 807 unit + 177 instrumented + 7/7 E2E smoke-7 + manual Shashibo plate 5 visual match vs v1.5.48 reference.
- **Handoff spec**: `docs/superpowers/specs/2026-04-17-b78-shashibo-prepare-oversize.md`
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

### B72: Prepare preview corrupted (shattered mesh) after scale + copies + slice (GitHub #78) — FIXED v1.5.70
- After increasing model scale, increasing copy count, slicing, then returning to Prepare tab, the preview mesh looks geometrically shattered. The slice output is correct.
- **Root cause**: `setModelInstances()` is called during `prepareSlicer()` with N grid positions. The scale change clears the prepare-preview cache but the cache is never repopulated (LaunchedEffect key=modelRotation doesn't change). On tab return the composable is recreated with a null cache, triggering a fresh `getPreparePreviewMesh()` on the post-slice native state which has N instances set — returning all N copies baked in world-space. The GL renderer then also applies instancePositions for N copies → N×N corruption.
- **Fix**: Reset to single centred instance (`setModelInstances(floatArrayOf(135f, 135f))`) before calling `getPreparePreviewMesh()` in the Prepare preview LaunchedEffect.
- **Test**: `NativePreparePreviewTest.getPreparePreviewMesh_afterMultiInstanceSliceState_singleInstanceResetGivesCorrectBounds`

### B68: Printer offline notification shown during printing — misleading text (GitHub #75) — FIXED v1.5.67
- While a print is actively in progress, the app shows a "printer offline" notification
- May be unavoidable (Android limits background WebSocket connections), but text is misleading — implies the printer went offline rather than that the app lost its monitoring connection
- **Suggested fix**: Change notification text to "Press to connect to see printer status" (or similar)
- **Source**: Discord user Jon (2026-04-14)

### B67: Import configuration only partially connects printer — camera doesn't show (GitHub #74) — FIXED v1.5.67
- After importing a printer configuration (QR code / settings import), printer appears connected but live camera feed does not load
- User must navigate to Printer Settings and tap Connect manually to fully connect
- **Expected**: Import should result in a fully connected printer (camera + status working)
- **Source**: Discord user Jon (2026-04-14)

### B63: Reprint G-code sends PLA filament type instead of actual loaded material (GitHub #73) — FIXED v1.5.56
- Root cause: `filament_type` not in native `profile_keys[]` whitelist; slicer always emitted PLA in G-code header despite correct embedded profile
- Fix: post-slice G-code header patch — `fixFilamentTypeHeader()` replaces `; filament_type = PLA` with actual per-extruder material types from extruder presets
- **Source**: Discord user Jon (2026-04-14)

### B58: SEMM painted model preview colours don't match sliced output or desktop OrcaSlicer (GitHub #60) — FIXED v1.6.13 (PaintColorDecoder), reverified v2.0.0
- For `colored_3DBenchy (1).3mf` (4-colour SEMM), the Prepare preview, G-code preview, and desktop OrcaSlicer all show different colours
- **Prepare screen**: Only 2 colour chips shown; model renders mostly white/gray — 2 of 4 paint zones missing
- **G-code preview**: More colours visible in toolpath render but different distribution from desktop reference
- Not a slicing correctness issue (all 4 extruders active in G-code), but gives user a misleading picture
- **Affects**: All SEMM painted models (`hasPaintData=true`)
- **2026-04-23 v1.6.13 manual check**: post-decoder, `colored_3DBenchy (1).3mf` reports `colors=4, mapping=[0, 1, 2, 3]`, all 4 colour chips visible (Color 1 blue, Color 2 red, Color 3 yellow, Color 4 white) and the Prepare 3D mini-preview shows all 4 colours on the Benchy. Symptom appears resolved by the new `PaintColorDecoder` correctly identifying all paint states.
- **2026-05-01 v2.0.0 E2E confirmation**: full 16-fixture E2E batch on Pixel 8a — `colored_3DBenchy (1).3mf` shows 4 colour chips, 3 of 4 canonical T-indices used in saved G-code (T3=0 because that paint state is not actually present in the geometry, model-correct). Prepare/Preview palette agreement verified separately by `buzzLightyear_plate8_prepareAndPreviewColoursAgreeByRegionSize` regression guard.
- **GitHub #60**: ready to close.
- ~~**When fixed**: restore CP TOOLCHANGE~27 assertion in the `colored_3DBenchy (1).3mf` E2E check~~ — leave suppressed (CP TOOLCHANGE count is genuinely variable across SEMM session-by-session, not a regression signal).

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

## Open Architecture

### A1: v2.1.0 hardening release — typed G-code path classes + Send-side multi-tool defence (GitHub #151)
- **Status**: NOT STARTED. Estimated ~2–3 days. Defense-in-depth from Phase 2 reviews; not a correctness blocker but makes the bug class structurally harder to re-open.
- **Scope**:
  - **B.1 finish — typed value classes end-to-end.** Make `PhysicalGcodePath` constructor `internal`. Expose explicit factories: `fromRemap(physical)`, `fromVerifiedLegacy(file)`, `fromIdentityCopy(file)`. Drop the public `PhysicalGcodePath.of(file)` shortcut. Thread `CanonicalGcodePath` / `PhysicalGcodePath` through `prepareExportableGcode*`, `saveGcodeTo`, `shareGcode`, `shareJobGcode`. Compiler then enforces "anything sent to printer went through a typed boundary".
  - **Source-T defence on Send.** Before sending, scan source G-code for `^T(\d+)`. If any T ≥ 4 appears AND canonical lookup returned `Absent`, block the send with a clear error.
  - **Phase 2.6 carry-over.** STL canonical-list defaults from `PrinterViewModel.syncFilaments` (currently uses stale local `extruderPresets`).
- **Tests**: Red test for Absent-misclassification (synthesise canonical G-code with T4+, force null canonical, verify Send blocked); regex test for multi-digit T (T10/T11); factory-correctness test for `PhysicalGcodePath`.
- **Out of scope** (deferred to A2): anything requiring native rebuild; anything requiring a second printer profile.
- **Pre-tag checklist**: sweep green at HEAD; JVM tests green; focused E2E batch on Send/Save/Share/Jobs paths + "block-on-Absent-multitool" manual test; version bump to 2.1.0 / versionCode 261; merge to main; build release APK; cut tag.
- **Source**: [`docs/REFACTOR_STATUS.md`](docs/REFACTOR_STATUS.md) §v2.1.0.

### A2: v3.0.0 multi-printer via Orca profile import (GitHub #152)
- **Status**: NOT STARTED. Estimated weeks. Needs a brainstorming-skill design session + spec doc before any code.
- **Driver**: support multiple printers in a single Android app, profile-driven via OrcaSlicer profile import. Replaces the hardcoded U1 assumptions threaded through `applyConfigToPrusa`, the `profile_keys[]` whitelist, the `is_snapmaker_profile` heuristic, and the 4-slot `coerceIn(0, 3)` clamps.
- **Scope**:
  - **Profile import system.** Read `.orca_printer`, `.orca_filament`, `.orca_process` JSON. Handle 3MF-embedded variants. Validation (schema, required keys, conflict detection).
  - **Profile merge logic (Kotlin).** Build a fully-resolved `Map<String, Any>` per active printer: base printer ⊕ active process ⊕ active filament per slot ⊕ user overrides ⊕ 3MF-embedded params.
  - **JNI passthrough.** New `nativeApplyResolvedConfig(json)` consumes the resolved map. Replaces `applyConfigToPrusa`'s hardcoded values, the `profile_keys[]` whitelist, and the `is_snapmaker_profile` heuristic.
  - **B.2 — config pipeline inversion.** Free byproduct of the JNI passthrough.
  - **B.3 — PRINT_START heuristic obsoleted.** Profile carries explicit printer ID metadata.
  - **Slot-count parameterisation.** `coerceIn(0, slotCount-1)` instead of hardcoded `0..3`. `meshAlignedFilamentColors` mod-N fallback uses `slotCount` from active profile.
  - **UI.** Settings screen for imported profiles; active-profile indicator; migration of v2.x users to a pre-imported U1 profile.
  - **First non-U1 printer profile + verification.** Real second printer; differential vs U1 must show only kinematics/extruder/bed differences from the profile delta.
- **Relationship to F78** (multi-printer support): F78 is the user-facing "configure and switch between multiple printers" feature; A2 is the architectural foundation that makes it possible. F78 lands on top of A2.
- **Source**: [`docs/REFACTOR_STATUS.md`](docs/REFACTOR_STATUS.md) §v3.0.0.

### A3: Clipper post-upgrade native failure — APPARENTLY RESOLVED
- **History**: Long-running native-geometry investigation into poisoned-Clipper polygons after app upgrade. Documented in [`CLIPPER_UPGRADE_INVESTIGATION.md`](CLIPPER_UPGRADE_INVESTIGATION.md).
- **Status (2026-05-22)**: not observed in any release since the v1.5.0 native rebuild (B38 fix). v2.x runs through E2E batches and instrumented sweeps clean. Considering resolved; doc retained for forensic reference.
- **If symptom returns**: poisoned-Clipper coordinates (`Long.MIN_VALUE` / `Long.MAX_VALUE`) in slicing output, intermittent across reinstalls on Pixel 9a more than Pixel 8a. Re-open this entry and the investigation doc.

### A5: SEMM canonical filament count leaks into Map & Print dialog + Slice Summary (GitHub #160)
- **Status**: NOT STARTED. Pre-existing post-v2.2.18 behaviour, shipped through v2.3.0 → v2.9.2 → v2.9.3.
- **Symptom**: On `colored_3DBenchy (1).3mf` (and other SEMM-painted files with canonical spread > physical count) Prepare shows `Filaments(4)` with 4 chips, but Slice Summary shows 9 per-extruder rows and Map & Print dialog header reads "Assign each of the **9** filaments to a physical extruder". G-code is correct (canonical T0..T9 → PrintTimeRemap collapses to physical at upload); only the UI/summary layer surfaces the canonical count.
- **Why it matters**: Map & Print asks the user to map 9 things that collapse to 4 physical slots anyway — confusing. Slice Summary mm/g figures are split across canonical positions that don't correspond to physically loadable spools.
- **Provenance**: introduced 2026-05-22 (`6d71183`, SEMM-fold removal v2.2.18). Confirmed via v2.9.0..HEAD geographic isolation that the v2.9.3 release does not touch any of `FilamentMapping`/`displayedFilamentMaterials`/`sliceSummary`/`perExtruderFilamentMm`/`PerExtruderRow` code paths.
- **Suggested fix**: fold `displayedFilamentMaterials` and `perExtruderFilamentMm` through `colorMapping` → physical-slot reduction before feeding the Slice Summary / Map & Print dialog, mirroring the G-code remap that already happens at send time. Decide whether canonical view is ever needed (probably not, given the user only has 4 physical spools).
- **Tests**: instrumented regression on `colored_3DBenchy (1).3mf` asserting `displayedFilamentMaterials.size == 4` and matching dialog row count. Update Universal post-slice rubric in `e2e-testing.md` to assert dialog count == physical slot count.
- **Out of scope**: any change to canonical G-code emission.

### A4: Reusable GL-viewer camera-state test harness (GitHub #159)
- **Status**: NOT STARTED. Estimated ~1.5 days (Tier 1 + Tier 2). Hardening, not a correctness blocker.
- **Driver**: The Smart Paint camera-reset regression (v2.9.3) was a *behavioural* bug — painting reset the 3D viewer orientation — but the project has no way to assert "operation X must not disturb the camera" except brittle source-grep guards (`AiPaintViewerCameraResetTest`, `InlineModelPreviewRotationKeysTest`). The same class of bug recurs across the app (B49 prepare-preview cache, B109 rotation, B129 G-code slider persistence): an in-place edit unexpectedly resets view/preview state. A shared harness would catch these behaviourally instead of by text-matching.
- **Why it generalises**: `ModelViewerView` (Smart Paint *and* Prepare inline preview), `GcodeViewerView` (G-code preview), and `BaseGLViewerView` all share one `Camera` with `snapshot()` / `restore()` / `CameraViewState`. `androidx.compose.ui:ui-test-junit4` + `ui-test-manifest` are already declared in `app/build.gradle` but unused — no `createComposeRule` anywhere — so the Compose UI harness is wired but unproven.
- **Scope**:
  - **Tier 1 — pure-logic JVM test.** Extract the camera-reset *decision* out of `ModelRenderer.onDrawFrame` (currently GL-coupled) into a pure function, e.g. `cameraActionOnMeshUpload(preserve, hasMesh): Reset | Keep`. Unit-test the contract (`preserve=true ⇒ Keep`) in the fast JVM suite. Benefits every screen since all render through `ModelRenderer`.
  - **Tier 2 — reusable instrumented harness (the real win).** One helper, e.g. `renderViewerAndSnapshot(view, mesh) { op -> ... }`, that mounts a GL viewer on-device, waits N frames via a `CountDownLatch` on a render callback (never `Thread.sleep` — that is the flakiness trap), runs `op`, and returns `camera.snapshot()`. First consumer: Smart Paint (`updateExtruderIndices` must not move the camera). Fast-follow consumers: Prepare preview (rotate/scale/recolor/prime-tower toggle) and `GcodeViewerView` (layer-slider drag, colour-mode switch).
  - **Tier 3 (optional, deferred).** Full Compose UI instrumented test via the already-present `createComposeRule`, mounting `AiPaintViewer` and firing a paint callback. Most faithful to the actual regression (the `remember` key wiring) but Compose-idle ↔ GL-render-thread sync is fiddly and the path is unproven; only invest if Smart Paint keeps churning.
- **Out of scope**: any native rebuild; replacing the existing source-grep guards (they stay as cheap belt-and-braces).
- **Note**: while here, correct the stale "no Compose UI harness in project" line in `CLAUDE.md` — the dependency exists, it is just unused.

## Open Features

### F94: Immediate "Preparing G-code" indicator during large-file send — bridge the silent gap before "Uploading G-code" appears (GitHub #166) — DONE v2.10.14 (released 2026-06-04)
- **Released 2026-06-04** as v2.10.14. `PrinterViewModel.SendingState.Preparing` + `beginSendPreparing()` set synchronously on the Main thread at all three send-confirm sites in `MainActivity` (UploadOnly, PrintAndUpload, Absent), the instant the user confirms — before any IO. `PrinterScreen` renders a "Preparing G-code…" card (reusing the existing Uploading-card pattern) that the existing Uploading/PrintStarted/UploadComplete arms take over from. A `catch (CancellationException → rethrow) / catch (Throwable → reportSendError via NonCancellable+Main; return@launch)` on each prep block surfaces failures as `SendingState.Error` instead of a stuck banner. Guard: `ui/SendPreparingBannerTest` (4 source-grep cases — no Compose UI harness in project).
- **Request**: Show an immediate top-of-Printer-screen banner the moment the user taps a send action (Send, Map & Print, Map & Upload, Upload Only, Send & Print, …) and keep it visible through the file prep + upload phases. Today there's a perceptible silent gap between tapping send and the "Uploading G-code" state appearing on a large file — the user has no feedback that anything is happening.
- **Reporter's words**: *"Just as a nitpick for the 'wtf are you doing' large files. Any chance it could post something at the top of the printer screen there while it's preparing the file. It eventually showed up as uploading gcode but I didn't get that immediate satisfaction to know it was working 😛"*
- **Reported by**: User (Discord), 2026-05-30 (pre-v2.10.1).
- **What the user gets today**: `LongOpService` (v2.9.0 wrap of save/share/send for the 0-byte freezer fix) emits a foreground-service notification, but it only flips to "Uploading…" once the upload phase begins; the **prep phase** (file load, slot remap rewrite, header patch, temp write) is silent or generic. The notification also lives in the system shade, not on the Printer screen where the user is looking.
- **Design notes**: Surface a top-of-Printer-screen banner that appears synchronously on send-action tap and stays through "Preparing…" → "Uploading X%" → "Starting print…" transitions. The state already flows through `LongOpService` (post v2.9.0 wrap); this FR is the in-app surface, not the system-notification surface. Possible implementation: thin StateFlow on `PrinterViewModel` mirroring `LongOpService.topStage` for send/upload-class labels, plus a Compose banner anchored to the top of `PrinterScreen` keyed off it.
- **Related work**: v2.9.0 save-gcode-0byte-fix (LongOpService wrap of send/share/save), F90 / v2.7.1 (LongOpService coverage), B130 (LongOpService FGS watchdog — different concern, not UX).
- **Issue**: https://github.com/taylormadearmy/u1-slicer-for-android/issues/166
- **Source**: Discord, 2026-05-30 — https://discord.com/channels/1086575708903571536/1484249705042153633/1510490527399804938

### F93: Per-part / per-volume copies — clone individual parts, not the whole model (GitHub #163) — DONE v2.10.13 (released 2026-06-03)
- **Released 2026-06-03** as part of v2.10.13. New `nativeDuplicateObject` C++ API (deep-copies the source `ModelObject` via `Slic3r::Model::add_object`, then `onSplitObjectReshape` to refresh per-object state) bound to Kotlin `NativeLibrary.nativeDuplicateObject(objIdx: Int): Int`. `SlicerViewModel.duplicateObject(objIdx)` wraps it in `viewModelScope.launch + previewMutex.withLock` to serialise against in-flight preview fetches and tracks dupes in `_duplicateOps: MutableStateFlow<List<Int>>` so the slice path can replay them after pose replay (`startSlicing` Step 5). UI: per-object EditPanel Scale tab exposes a 1–16 Copies slider + label that commits on `onValueChangeFinished` (rapid-fire slides without serialisation caused SIGSEGV in early v2.10.x). EditPanel rewrite gates the section behind a tinted Card + BETA pill so the per-part scope is unmistakable.
- **Original request** (Jon, Discord 2026-05-31, v2.10.1): clone an individual part/volume within a multi-volume 3MF — example Oreo cookie has two black shells + one cream centre; wanted 2 cream centres alongside the originals.
- **Known follow-up**: high duplicate counts on dense layouts hit B135 — `CopyArrangeCalculator.placeAdditionalObject` row-wrap can place pieces past the 270mm bed edge, slicer rejects gracefully with "impossible coordinates". F92 Auto-arrange is the structural fix.
- **Tests**: `B131B132B133DiagnosticTest` covers post-split state consistency, `_duplicateOps` tracking, and the Oreo split → duplicate → slice replay chain (2-dupe variant; 4-dupe variant blocked on B135).
- **Issue**: https://github.com/taylormadearmy/u1-slicer-for-android/issues/163
- **Source**: Discord, 2026-05-31 (Jon) — https://discord.com/channels/1086575708903571536/1484249705042153633/1510649215510909018

### F92: Auto-arrange — match auto-orient parity (Arrange on Bed) (GitHub #162) — DONE v2.10.14 (released 2026-06-04)
- **Released 2026-06-04** as v2.10.14. "Auto-arrange all" in the Prepare preview ⋮ menu (beside "Auto-orient all"). Pure `CopyArrangeCalculator.autoArrange(boxes, reservedRect, incoming, …)` shelf-packs object footprints clear of the **pinned** wipe-tower keep-out (translation-only — keeps rotations), on-bed guaranteed (overflow reported, never placed off-bed). `SlicerViewModel.autoArrangeAll()`/`launchAutoArrangeAll()` gather native bounding boxes + the tower footprint (reserved when `resolvePrimeTower` is true, keyed off the effective slot count) and apply via the existing `applyPlacementPositions` (multi-object native sync vs single-object). **Gated on `hasMultipleDistinctObjectsVar`** — a single-file model that reports >1 native object (e.g. multi-volume 3MF) would otherwise feed N positions to `setModelInstances` and explode into N×N instances (caught in final review). Also closes **B135** off-bed overflow (autoArrange is on-bed-guaranteed). Tests: 5 `CopyArrangeCalculatorTest.autoArrange` cases + 2 instrumented `PreparePreviewViewModelTest` (two-object no-overlap; single-file-multi-object guard).
- **Request**: Add an **Auto Arrange** button alongside the existing **Auto Orient** button. The slicer should programmatically lay out objects on the bed so they don't collide with each other or with the wipe/purge tower — matching desktop Orca's "Arrange on Bed".
- **Motivation**: A user printed a large object that visually merged with the wipe tower because they didn't notice the overlap. Manually placing each object/copy on a small phone screen is error-prone, and the split-to-parts / copies flows make manual arrangement even harder. Reporter: *"I see there is an auto orient, is there an auto arrange I am missing? I printed a large object that merged with my print tower because I didn't pay attention."*
- **Reported by**: User (Discord), v2.10.1, 2026-05-31.
- **Design notes**: Infrastructure for grid placement already exists — `CopyArrangeCalculator.buildMultiObjectPositions` (F77) computes a centred grid with row-wrap; `wipeTowerClampBounds` bounds the wipe-tower footprint. An Auto Arrange button should be a UI entry point onto that math, generalised to mixed object sizes (`effectivePlacementFootprint` from B109 v2.2.6). Must respect wipe-tower bed footprint when Prime Tower is enabled, skirt clearance, and per-object rotation. Open question: should Auto Arrange include rotation choices or strictly translation? Desktop Orca offers both; phone UX may want translation-only by default.
- **Related work**: F77 (multi-file copies + centred grid), B109 (effective placement footprint), B124 (single-file multi-volume placement).
- **Issue**: https://github.com/taylormadearmy/u1-slicer-for-android/issues/162
- **Source**: Discord, 2026-05-31 — https://discord.com/channels/1086575708903571536/1484249705042153633/1510649792508854272

### F87 + F91 — Orca profile import + filament library expansion (GitHub #147, #155) — DONE v2.8.0
- **Released 2026-05-26** as v2.8.0 (versionCode 298). 1362 JVM unit + 345 instrumented + 16/16 E2E PASS on Pixel 8a; every established baseline matched exactly. Plan: `docs/superpowers/specs/2026-05-25-f87-f91-orca-profiles-plan.md`.

#### F87 — Process profile import (GitHub #147)
- **What**: Settings → "Process Profiles" → import `.orca_process` / `.json`. Imported profiles persist in DataStore; one can be marked active. At slice time, the active profile's keys layer between the bundled Snapmaker process defaults and the user's Prepare-screen overrides.
- **Implementation**: `ProcessProfile` data class + `ProcessProfilesRepository` (DataStore JSON, modelled on `PrintersRepository`); `ProcessProfileParser` handles single object / array / `{ process_profiles: [...] }` wrapper, drops provenance keys (`type`, `name`, `inherits`, `compatible_printers`, …); `ProfileEmbedder.buildConfig(processProfileKeys = …)` slots the keys after the bundled process JSON and before `filamentSettings`/`overrides`; `SettingsBackup` bumped to v3 with v1/v2 backwards-compat.
- **UI**: Settings card showing imported count + active profile, dedicated `ProcessProfilesScreen` for import/select/rename/delete.
- **Tests**: 17 new unit tests (`ProcessProfileParserTest`, `ProcessProfilesConfigTest`, `SettingsBackupTest` v3 cases).
- **Source**: Kevin, 2026-05-23 (post-v2.4.0).

#### F91 — Filament library expansion (GitHub #155)
- **What**: `FilamentProfile` grows 16 nullable columns mapped 1:1 to OrcaSlicer per-filament keys: nozzle/bed initial-layer temps, flow ratio, max volumetric speed, cost, fan min/max + overhang + auxiliary cooling, slow-down layer time / min speed, close-fan-first-N layers, full-fan-from layer, pressure advance enable + value, minimal purge on wipe tower. Null = "library default" (slicer uses bundled profile / OrcaSlicer default).
- **Implementation**:
  1. Storage — Room `MIGRATION_6_7` adds nullable `ALTER TABLE` columns; round-tripped via `SettingsBackup`.
  2. UI — `FilamentEditDialog` becomes scrollable with sections: Temperatures, Retraction, Flow & limits, Cooling, Pressure advance, Wipe tower. Optional fields show "default" placeholder when null.
  3. Slice path — `buildFilamentLibrarySettings(slotCount, usedSlots, presets, filaments)` resolves each slot's linked `FilamentProfile` and emits per-slot arrays for each key with at least one non-null value. Empty slots forward/back-fill from neighbours so arrays stay length-`slotCount` (OrcaSlicer crashes on length-mismatched per-extruder arrays). When no slot has a value for a given field, the key is omitted entirely so the bundled profile / OrcaSlicer default stands.
  4. Override interaction — file → bundled profile → process profile (F87) → library → user overrides; later wins.
- **Parser**: `parseFilamentJson` reads new keys from both OrcaSlicer Bambu shape and simple JSON shape; absent keys parse as null.
- **Tests**: 21 new unit tests (`BuildFilamentLibrarySettingsTest`, `FilamentJsonImportExtendedTest`, `SettingsBackupTest` v3 extended-filament cases).
- **Out of scope**: per-print process-profile settings (F87 above); printer-level settings (A2); per-feature speeds (already in `SlicingOverrides`).
- **Source**: Kevin, 2026-05-24.

#### Native clamp gap — FIXED 2026-05-25
Originally surfaced while writing on-device F87/F91 verification: `applyConfigToPrusa` in `sapil_print.cpp` ran AFTER the `profile_keys[]` loop and unconditionally overwrote many keys with U1 hardware defaults, so embed-routed imported values were silently lost. Same gap also broke `SlicingOverrides.seamPosition` etc.

**Fix landed 2026-05-25**:
- `sapil_print.cpp`: gate `sparse_infill_pattern`, `sparse_infill_density`, `filament_max_volumetric_speed`, `fan_min_speed`, `fan_max_speed`, `overhang_fan_speed`, `slow_down_layer_time`, `slow_down_min_speed`, `seam_position`, `reduce_infill_retraction` on `!has_embedded_profile`. When Kotlin's `ProfileEmbedder` produces a Snapmaker-authored embed (`is_snapmaker_profile=true`), the embed's values win. Raw STL with no embed keeps the U1 hardware defaults exactly as before.
- `sapil_print.cpp`: add `filament_cost` to `profile_keys[]` so library cost values reach the slicer.
- `ProfileEmbedder.kt` preserve path (Bambu): overlay the U1-safe cooling/flow values from bundled `pla.json` (`U1_FILAMENT_SAFETY_OVERLAY` set) on top of `sourceConfig` BEFORE `filamentSettings` + `overrides`. This prevents Bambu's potentially-wrong-for-U1 values (e.g. PETG `fan_min_speed=60`) from reaching the slicer when no library override exists; library overrides still win because they're layered after.
- `wall_generator` stays clamped to Arachne: surfaced when un-gating that OrcaSlicer 2.2.4's classic perimeter generator SIGSEGVs on the U1 config (PerimeterGenerator::process_classic / MultiPoint dtor). Pre-existing OrcaSlicer bug. Re-clamping until fixed upstream.

**Native build**: NDK 26 / Release / 20.8 MB stripped / Clang 17.0.2 / 34 JNI symbols (matches `external fun` count). Verified on Pixel 8a.

**Verified working F87 keys**: `layer_height` (sentinel-gated), `wall_loops`, `top_shell_layers`, `bottom_shell_layers`, `top_surface_pattern`, `bottom_surface_pattern`, `seam_position`, `sparse_infill_density`, `sparse_infill_pattern`, `reduce_infill_retraction`, `ironing_type`, support settings, prime-tower settings. `wall_generator` accepted but stays Arachne (see above).

**Verified working F91 keys** (all 15 fields):
- Per-extruder cooling: `fan_min_speed`, `fan_max_speed`, `overhang_fan_speed`, `additional_cooling_fan_speed`, `slow_down_layer_time`, `slow_down_min_speed`, `close_fan_the_first_x_layers`, `full_fan_speed_layer`.
- Per-extruder flow: `filament_flow_ratio`, `filament_max_volumetric_speed`.
- Per-extruder PA: `enable_pressure_advance`, `pressure_advance`.
- Per-extruder cost/temps: `filament_cost`, `nozzle_temperature_initial_layer`, `hot_plate_temp_initial_layer`.
- Wipe tower: `filament_minimal_purge_on_wipe_tower`.

**Regression coverage 2026-05-25** (all on Pixel 8a):
- 4/4 new F87+F91 instrumented tests
- 32/32 `ProfileEmbedderIntegrationTest`
- 41/41 `BambuPipelineIntegrationTest`
- 52/52 `SlicingIntegrationTest`
- Full JVM unit suite

#### Remaining for ship
- ✅ Native rebuild + .so committed.
- ✅ All instrumented + unit tests pass on Pixel 8a (post-rebuild).
- Manual UI verification on device: import a process profile via Settings → Process Profiles, slice a model, confirm the profile's keys reach the G-code header.
- Manual UI verification: edit a library filament with `pressure_advance=0.05` + `fan_min_speed=80`, slice, confirm header shows both.
- Version bump + GitHub release (after manual verifications pass).

### F90: Foreground-service coverage for all long-running operations (GitHub #154) — DONE v2.7.1
- Shipped v2.7.0. `SlicingService` renamed to `LongOpService` with stack-based stage labels: nested `start(stage)` pushes; `stop()` pops; the persistent notification always renders the top-of-stack. `update(progress, stage?)` replaces the top frame so the native slicer progress listener keeps its existing rename-on-tick behaviour. Wrap points: `loadModel(uri)`, `loadModelFromFile`, `selectPlate`, `addModelFromFile[ForPlate]`, `confirmAddPlate`, `startSlicing`. Each wrap is start/stop-paired via try/finally so cancellation/exception pop the stage. Manifest `foregroundServiceType="specialUse"` retained (sideloaded; no Play subtype review); subtype updated to "3D model preparation, loading and slicing". 9 unit tests in `LongOpServiceStackTest.kt`.
- **v2.7.1 follow-up**: preview-prep wrap added — the `InlineModelPreview` `LaunchedEffect` that calls `lib.getPreparePreviewMesh(...)` on `Dispatchers.IO` (30+ seconds on heavy models per the in-code comment) now pushes `"Preparing preview"` onto the LongOpService stack with paired stop in `finally`. The wrap sits AFTER the 300 ms debounce: putting it before caused `ForegroundServiceDidNotStartInTimeException` under rotation-slider drag — each cancelled-mid-debounce LaunchedEffect still fired `startForegroundService`, and Android's per-call 5-second watchdog can't be satisfied during the rapid cancel/restart churn. Reproduced + fixed on Pixel 8a: 16 rapid drags on the Y-tilt slider no longer crash. 3 source-grep structural tests in `PreparePreviewLongOpWrapTest.kt` guard the contract (wrap exists, finally pop, start-after-debounce ordering).
- **Caveat**: Android can still kill foreground services under extreme memory pressure; F89's resume path stays as the safety net.
- **Source**: Kevin, 2026-05-24 (post-F89 conversation: "having notifications active while things happen in the background make it less likely Android might kill it in the background right?"). Preview-prep gap surfaced + resolved 2026-05-24.

### F89: Persist in-progress session + auto-resume on launch (GitHub #153) — DONE v2.6.0
- **What**: when Android kills the app (low memory, swipe-from-recents), the user loses their loaded model, plate selection, transforms, copies, F77 additional files, and in-flight overrides. Persist these to DataStore and offer a "Resuming MyModel.3mf…" banner on next launch with one-tap accept / start-fresh dismiss.
- **Why**: large-model workflows (Buzz, multi-plate Bambu) take 90+ seconds to cold-load plus 3+ minutes to slice. Losing all of that to an OS process kill is painful UX.
- **Implementation (~1–2 days, pure Kotlin)**:
  - New `SessionStateRepository` (DataStore JSON, modeled on `PrintersRepository`).
  - `SlicerViewModel` writes session state on each relevant mutation (load, plate select, scale, rotation, copies, add-to-bed, slice complete, clear).
  - `MainActivity` checks for an existing session on `onCreate`; shows the resume banner when the source file is still accessible. Reuses the F61 "Re-open from Jobs" code path for the actual re-load.
  - Graceful fallback when the source file was evicted from cache.
- **What NOT to persist** (already covered): slice result G-code (Room SliceJob), filament library / slot presets (DataStore), Smart Paint painted artifacts (cache dir).
- **Option 2 (potential follow-up, NOT in F89)**: if F89 + B98 perf wins still leave the 90s cold-load painful, consider a native model-state snapshot to cut Buzz reload to ~5–15s. Weeks of native C++ work, coupled to the orcaslicer fork; defer until measured pain. Don't speculatively build.
- **Source**: Kevin, 2026-05-23.

### F88: Save Gcode + Share Gcode should preserve original model name (GitHub #148) — DONE v2.5.0
- Shipped. New `ModelFileNaming.baseName(modelName, fallback)` helper in `app/src/main/java/com/u1/slicer/data/`. `MainActivity.gcodeSaveLauncher.launch(...)` now suggests `${modelBaseName}.gcode`; `SlicerViewModel.shareGcode()` + `shareJobGcode(job)` use the model name (with `.share.gcode` infix retained internally). 6 unit tests in `ModelFileNamingTest`.
- **Smart Paint follow-up (also v2.5.0)**: after accepting a Smart Paint result, the painted 3MF lives in `cacheDir/ai_paint_<timestamp>.3mf`. Without this fix, `currentModelName` would become "ai_paint_<ts>.3mf" and Save/Share/Send would produce "ai_paint_<ts>.gcode" instead of the original name. Threaded `sourceDisplayName` through `AiPaintResultState` → `runPipeline` → accept-painting via a new `loadModelFromFile(file, preserveDisplayName)` overload on `SlicerViewModel`. Filename now stays as the user's original model name across the full Smart Paint round-trip.

### F87: Import process profiles from JSON, pick at slice time (GitHub #147)
- **What**: Settings → new "Process profiles" section with "Import from JSON" (OrcaSlicer `.orca_process` / `.json`). Imported profiles listed with nicknames; rename / delete supported. Prepare screen gets a "Process profile" dropdown that applies the profile's keys to the slice; individual overrides still win on top.
- **Why**: User has standard presets they want to reuse across many prints (e.g. "0.16 fine" with tuned speed / walls / supports). Per-print manual override re-entry is friction.
- **Persistence**: DataStore alongside existing filament library. `SettingsBackup` schema bump to include profiles (forward-compat: importing a v2 backup just doesn't see the section).
- **Out of scope**: in-app profile editing (UI for 100+ keys is non-trivial); filament / printer profile import (those are A2).
- **Relationship to A2**: A2 covers full Orca profile import (printer + filament + process) as part of v3.0.0 multi-printer architecture. F87 is the focused process-only first slice that can ship independently. A2 may later subsume or expand it.
- **Source**: Kevin, 2026-05-23 (post-v2.4.0).

### F86: Prepare page — indicate overridden settings + reset button (GitHub #146) — DONE v2.3.0
- Shipped. `OverrideRow` shows a primary-colour dot when the row has a user override; section header shows "N modified" badge; "Reset all overrides (N)" affordance at the top of the accordion clears every field back to `USE_FILE`. Override counts exposed as member functions on `SlicingOverrides` with reflective bucketing-guard test. GitHub #146 closed.

### F85: Plate selector when adding a 3MF to the bed (GitHub #140) — DONE v2.2.7
- Add-to-bed now shows the plate-selector dialog for multi-plate 3MF files; only the chosen plate is loaded. Shipped via `nativeAddModelForPlate` (commits 3c94b56 / f6d25fa). GitHub #140 closed.

### F84: Upload filename should preserve the original model name (GitHub #138) — DONE
- Shipped. `PrinterViewModel.sendAndPrint` / `sendUploadOnly` call `PrinterRepository.resolveUploadBaseName(modelName, file.name)`; `MainActivity` passes the model name through. Printer file browser shows the original model name with the epoch suffix. GitHub #138 closed.

### F83: Scale model by absolute dimension (mm) + non-uniform XYZ scaling (GitHub #136) — DONE v2.2.7
- Shipped. Prepare screen has mm/% toggle, uniform/non-uniform toggle, per-axis X/Y/Z sliders + text fields, `ModelScaleConverter` (unit-tested) for mm↔scale conversion, and an "Exceeds 270 mm bed on X/Y/Z" warning. Reset-to-100% button included. GitHub #136 closed.

### F82: Idle-state printer controls on Printer tab (GitHub #133) — DONE v2.3.0 (temps + custom G-code)
- Shipped (safe scope: temperature controls + custom G-code box only — no head motion). Bed and per-extruder nozzle temps editable any time the printer is connected (was previously gated on printing/paused). Cooldown button sends `TURN_OFF_HEATERS`. Custom G-code card appears when idle + connected with a "you own the risk" warning. Skipped per safety: homing (G28), manual XYZ moves, undock/dock, filament load/unload. Could be added in a follow-up after motion-safety design.
- **Closed scope**: GitHub #133 closed. A follow-up issue (currently unfiled) could cover the motion-control half once a safety story exists.

### F81: Add notifications for all loading stages (GitHub #120) — DONE
- Notifications wired for long-running background operations (model load, Bambu sanitize/embed pipeline, Prepare preview ready). Shipped (commit c3ba402). GitHub #120 closed.

### F80: Off-LAN printing via Snapmaker cloud MQTT — DONE, awaiting Snapmaker permission (GitHub #112)
- **Status**: technical investigation + working implementation complete. Cloud-MQTT subscribe verified (`${sn}/status,/notification,/response`, 3 of 4 topics GRANTED — cracked via blutter decompile of mobile `libapp.so`). 4-step WAN upload flow (create → S3 PUT → completed → `server.files.pull` MQTT) verified on a 17 MB file 2026-05-11. See [F80 cloud-MQTT resolution](memory/project-f80-cloud-mqtt-blocker.md) and [F80 off-LAN upload resolution](memory/project-f80-off-lan-upload.md) memory notes.
- **Blocker before shipping**: awaiting permission from Snapmaker to use their cloud endpoints from a third-party app. Holding the code on a branch; not enabling in production until we have an explicit OK.
- **Related**: #16 (F45) Bambu printer support

### F79: Colour selector improvements (GitHub #111) — DONE v2.5.0
- Shipped. Two reported bugs fixed:
  1. `HsvPickerSnap.snapOnHueChange` snaps saturation (and value when 0) to 1.0 when the user drags the hue strip while currently in an achromatic state. Fixes "hue drag does nothing when starting from white".
  2. Bigger high-contrast thumbs on both hue strip and SV box (triple-ring black/white/black pattern). Fixes "can't see where the current colour is on open".
  - 4 unit tests in `HsvColorPickerTest.kt`. GitHub #111 closed.

### F78: Multi-printer support — configure and switch between multiple printers (GitHub #110) — DONE v2.4.0
- Shipped. Multiple Moonraker URLs supported via `PrintersRepository` + JSON-in-DataStore `PrintersConfig`. Chip at top of Printer tab opens a `ModalBottomSheet` of all configured printers; switching rebinds `MoonrakerClient.baseUrl` on the existing `PrinterRepository`. Settings has Printers section for add / edit / delete / test-connection. Per-printer extruder slot presets (slot UI reads from the active printer). Notifications prefixed with active printer's nickname when >1 configured. Send dialog title shows "Send to <nickname>" subtitle when >1 configured. Migration on first launch of v2.4.0 reads legacy `printer_url` + `extruder_presets` into a "Printer 1" entry. `SettingsBackup` schema bumped to VERSION=2 with bidirectional v1/v2 compat. GitHub #110 closed.

### F77: Add multiple files to the print bed independently (GitHub #109) — DONE v2.2.7
- **Redesigned** from original flat-binary-STL-combiner approach to an additive JNI loading model that preserves the primary file's embedded settings and allows independent per-object movement.
- **Primary load**: works unchanged — any supported format (STL, 3MF, OBJ, STEP). Settings/profiles from the primary file are preserved.
- **"Add to bed" button**: appears below the 3D viewer once a model is loaded. Opens a file picker; adds the selected file as a new independent object via `NativeLibrary.addModel()`. Preserves all primary file's config. Multiple files can be added sequentially.
- **Independent movement**: each object can be dragged to any bed position independently. Drag clamping uses per-object bounding boxes (`NativeLibrary.getObjectBoundingBoxes()`).
- **Slice path**: `prepareSlicer()` uses `NativeLibrary.setObjectPositions()` instead of `setModelInstances()` when `hasMultipleDistinctObjects` is true.
- **Grid packing**: initial positions from `CopyArrangeCalculator.buildMultiObjectPositions()` — row-packing with 5mm margin. Pure-Kotlin, unit-tested.
- **Native additions**: `addModel(path)`, `getObjectBoundingBoxes()`, `setObjectPositions(positions)` — new JNI methods in `sapil_model.cpp` + `sapil_arrange.cpp` + `slicer_wrapper.cpp`.
- **Bug fixed (2026-05-18)**: When primary is a multi-extruder 3MF, `prepareSlicer()` was re-embedding and reloading the native model before slice, losing the added objects. Fix: `additionalModelFiles` list tracks each added file; after embed reload, all are re-added via `addModel()` before `setObjectPositions()`. Manual E2E confirmed all 4 combos pass (3MF+3MF, STL+3MF, 3MF+STL, STL+STL). `addModelFromFile(File)` added to `SlicerViewModel`; `ADD_FILE` broadcast added to `TestCommandReceiver` for ADB-driven testing.
- **Still to do**: per-object extruder assignment UI (currently all objects slice with primary file's extruder config); instrumented test for addModelFile slice pipeline.
- **Tests**: `CopyArrangeCalculatorTest` — 5 `buildMultiObjectPositions` cases (empty, single, two-in-row, row-wrap, row-height tracking); `InlineModelPreviewRotationKeysTest` — `perObjectSizes` key guard added.

### F75: Prime tower should default to back of plate (GitHub #90) — DONE
- Shipped. `CopyArrangeCalculator.computeWipeTowerPosition` lists back-of-plate candidates (top-center → top-left → top-right) before the rest of the bed; when the model occupies the back, front candidates still beat them on raw clearance so the model dictates placement. Embedded 3MF position still wins when present. GitHub #90 closed.

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
- **2026-05-24 design drafted**: decomposed into six sub-projects A→F. Inspired by [bambuddy](https://github.com/maziggy/bambuddy) (Python+TS daemon) — used as wire-protocol reference, not embedded. Scope: LAN + Developer Mode only (matches earlier viability note); off-LAN via optional bambuddy relay (sub-project F).
  - **A** — printer transport abstraction (refactor only, no user-visible change)
  - **B** — Bambu LAN read-only (MQTT-TLS push reports, AMS inventory, in-app MJPEG camera). Designed together with A.
  - **C** — Bambu LAN passthrough send (FTPS upload + MQTT print command; "Send original to Bambu" button, source must be Bambu 3MF)
  - **D** — SSDP discovery in add-printer dialog
  - **E** — Slice-for-Bambu (bundle Bambu machine profiles into native engine; "Slice for Bambu & Send" button)
  - **F** — Optional bambuddy relay for off-LAN access
- **External release blocked** until at least C ships — A+B alone don't deliver enough user value to justify the new surface area.
- Specs:
  - Roadmap: [`docs/superpowers/specs/2026-05-24-bambu-integration-roadmap.md`](docs/superpowers/specs/2026-05-24-bambu-integration-roadmap.md)
  - A+B design: [`docs/superpowers/specs/2026-05-24-bambu-ab-design.md`](docs/superpowers/specs/2026-05-24-bambu-ab-design.md)

### F14: Full-spectrum / mixed-colour support (GitHub #18)
- Optically-blended colours via layer alternation across the U1's 4 toolheads
  (e.g. Blue+Yellow→Green). Toolchanger → no purge tower, only XY-offset calibration.
- **Re-scoped 2026-05-26.** Original ratdoux/OrcaSlicer-FullSpectrum plan retired:
  Snapmaker shipped native support in their own fork (PR #375 "Feat: mix filament",
  merged to `main` 2026-05-26), built on the same `FilamentMixer` library ratdoux adopted.
- **M0 verified PASS** (source-read): fully config-driven, headless-reachable via SAPIL —
  recipe is one `coString` key `mixed_filament_definitions`; virtual filament IDs
  (`num_physical+1` = 5..8) assigned via our existing paint/`objectExtruderMap` path.
- Marquee win is on Smart Paint (F54), lifting its 4-colour ceiling.
- Dominant cost = submodule jump 2.2.4 `f11a7bf` → post-#375 `main` + SAPIL API-compat;
  must re-apply B38 init patch (verified still needed). Requires native .so rebuild.
- Roadmap + M0/M1 analysis + config-key catalog: [`docs/superpowers/specs/2026-05-26-full-spectrum-roadmap.md`](docs/superpowers/specs/2026-05-26-full-spectrum-roadmap.md)

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

### F54: AI colouring for single colour prints (GitHub #33) — DONE v2.2.0
- Shipped as Smart Paint / AI Paint on the Prepare screen (commit 435ef9e, v2.2.0). Per-region segmentation + manual paint/lasso editing + Parts ⇄ Regions toggle. GitHub #33 closed.

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
- **F94 + F92 + B136**: v2.10.14 — "Preparing G-code" banner on the Printer screen the instant a send is confirmed (cancellation-safe, prep failures surfaced as Error); "Auto-arrange all" solid-mesh shelf packer that clears the pinned wipe tower (on-bed guaranteed, gated on multi-object mode to avoid an N×N instance explosion on single-file multi-volume models); and the B136 paint-preview dots/empty-plate fix (MMU mesh kept solid; stride only for >2M Hueforge files). New paint-preview integrity tests (solid-count + bounds-sane) + E2E logcat check close the regression-detection gap. RELEASED v2.10.14 (2026-06-04). Issues #166, #162, #169 closed. (B135 off-bed overflow on the per-object Copies slider remains open — `placeAdditionalObject` is unchanged; Auto-arrange is the on-bed-guaranteed workaround.)
- **B131 + B132 + F93**: v2.10.13 Oreo/Ghostface cluster — Ghostface 3MF visible on Prepare (MMU stride bypass in `sapil_model.cpp` + `loadTimeInstanceOffsets` derivation prefers `nativeGetObjectWorldAABBMins`), Oreo Split + per-object Copy cascade (5 sub-fixes: pre-slice copy-cap error, `splitObject` state publish, `applyPlacementPositions` count guard, drawn-min anchoring, `_duplicateOps` slice-time replay), per-object Copy slider in EditPanel (new `nativeDuplicateObject` native API), BETA-pill EditPanel rewrite — RELEASED v2.10.13 (2026-06-03). Issues #161, #164, #163 closed.
- **F76**: MakerWorld manual cookie paste UI removed — browser-capture path (WebView login) unchanged; `makerWorldCookiesEnabled` DataStore key removed; old backup keys ignored on import — DONE v2.1.0. Issue #108 closed.
- **F72**: Object skip UI on Printer screen — bed canvas with polygon outlines + text list; `EXCLUDE_OBJECT` sent via `sendGcode`; skipped objects greyed in both views — DONE v2.1.0. Issue #83 closed.
- **F71**: `exclude_object = true` added to `applyConfigToPrusa()` — `EXCLUDE_OBJECT_DEFINE/START/END` markers now emitted in all sliced G-code; also patched `GCode.cpp` to emit EXCLUDE_OBJECT_DEFINE syntax for Marlin flavor — DONE v2.1.0. Issue #82 closed.
- **F74**: Finer model scaling — continuous scale slider + editable percentage text field replacing 10%-step buttons — DONE v1.6.8. Issue #87 closed.
- **F73**: Multi-plate navigation — "Change plate" chip on Prepare screen returns to plate picker without reloading file; plate-switch race conditions fixed across v1.6.4–v1.6.8 — DONE v1.6.8. Issue #86 closed.
- **C2**: Phase 1 review action items (Tier B fixture specs, PlateStateEnrichment consolidation, sapil_arrange AABB fix, auto-centre tolerance, coverage pin tests) — all items completed across v1.7.0-dev commits.
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
