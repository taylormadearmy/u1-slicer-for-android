# Backlog

Open bugs, features, and investigations. Everything else is done — see git log.

## Open Bugs

### B51: SEMM Prepare preview broken — wrong orientation, tiny, split geometry (old.3mf, Korok mask)
- **Regression from v1.5.38**: confirmed working in the release build, broken in current `main`
- **old.3mf**: Prepare preview shows model lying down, tiny, and broken into two separate pieces. G-code preview (after slicing) is correct — full figure, 1163 layers, correct orientation
- **Korok mask**: Prepare preview shows mask standing upright instead of lying flat on the bed. G-code preview is correct — 18 layers, flat
- **Root cause (likely)**: the B48 changes to `sapil_model.cpp` MMU interleaved triangle emission. The round-robin interleaving or the volume transform (`volume->get_matrix()`) may be applying transforms differently than the pre-B48 sequential emission
- **What changed in B48**: `getPreparePreviewMesh()` MMU path was rewritten from sequential per-state emission with stride to round-robin interleaved emission with flat position arrays. The volume transform is applied per-state before storing positions, but the instance transform is skipped (handled by Kotlin). Something in this new path is producing wrong geometry for non-H2C SEMM models
- **Key observation**: H2C benchy preview looks correct. old.3mf and Korok mask are broken. The difference may be in how many volumes/objects each has, or how their transforms are structured
- **Test files**: `old.3mf` and `PrusaSlicer-printables-Korok_mask_4colour.3mf` in `androidTest/assets/`
- **Screenshots**: `G:\My Drive\Logs\Screenshot (7 Apr 2026 14 35 *.png)` — old.3mf broken prepare + correct gcode

### B48: H2C benchy — FIXED (slicer + Prepare preview), G-code preview colours still wrong
- **Slicer FIXED**: `computeEmbedTargetCount()` uses `colorMapping.size` (7) not `distinct().size` (4). G-code now has T0=120, T1=239, T2=242, T3=121.
- **Prepare preview FIXED**: shader `uniform int` → `uniform float` (Mali-G715 bug), config order restored (embedded profile → applyConfigToPrusa), MMU triangles interleaved round-robin so all 7 colours survive VBO truncation.
- **G-code preview colours NOT FIXED**: sliced G-code preview does not show correct per-tool colours. Separate issue.
- **Prepare preview cache NOT FIXED**: returning to Prepare after viewing G-code shows "preparing preview" for a long time instead of instant cache hit. See B49.
- Long-term MMU decimation tracked in taylormadearmy/u1-slicer-for-android#50

### B50: G-code preview colours don't match Prepare preview for SEMM models
- After slicing H2C benchy, the G-code preview shows all 4 colours (red, green, blue, white) but some are swapped compared to the Prepare preview
- The Prepare preview uses `colorMapping` (model-colour→slot) to assign colours to paint state indices — this matches the Bambu reference
- The G-code preview uses physical tool indices (T0-T3) which are assigned internally by OrcaSlicer's `multi_material_segmentation_by_painting()`. This mapping is opaque — we don't control which model colour ends up on which physical tool
- **Root cause**: the slicer's paint-state→tool mapping is different from our Prepare preview's model-colour→slot mapping. The colourMapping describes intent (model colour 5 → extruder slot 1 = green), but the slicer may assign those triangles to T2 instead of T1
- **Orange leak**: when all extruders set to blue, T0 sometimes shows orange (GcodeRenderer default colour at `GcodeRenderer.kt:68`). Likely `setExtruderColors` receives blank/empty entries that don't override the defaults
- **Fix needed**: either (a) extract the slicer's actual tool→model-colour mapping from the G-code (e.g. from filament_colour comments) and use it for G-code preview colours, or (b) accept the slicer's mapping and colour the G-code preview based on what's in the G-code
- Prepare preview is correct — this is only a G-code preview issue

### B49: Prepare preview slow reload after G-code view (FIXED)
- After slicing and viewing G-code, returning to Prepare preview shows "preparing preview" spinner instead of instant reload from cache
- Previously this was fast because the native preview mesh was cached (`g_preview_mesh_valid`)
- Likely cause: slicing or G-code view invalidates the preview cache, forcing a full reload of the 2M-triangle interleaved mesh

### B47: S-Buttons multi-colour 3MF intermittently loses a colour on first load
- Button-for-S-trousers.3mf (4-colour non-painted 3MF) sometimes shows only 3 colours on the Prepare preview after loading
- Color 4 is missing initially, but loading the file a second time (or switching tabs and returning) shows all 4 colours correctly
- Likely a race condition where `colorMapping` is not yet populated when the main LaunchedEffect fires
- The LaunchedEffect keys on `colorMapping?.size` which should re-fire when mapping arrives, but timing between `ThreeMfInfo` parsing, `colorMapping` StateFlow emission, and the composable recomposition may allow a window where the preview renders with an incomplete mapping
- Intermittent — unable to reproduce reliably after initial observation
- Not related to B46 painted model fix (this affects non-painted multi-colour 3MF)

### B45: Prepare preview for painted/SEMM 3MF models looks broken — wireframe/sparse rendering (GitHub #49)
- colored_3DBenchy and similar H2C/painted models show very sparse, wireframe-like preview on the Prepare tab
- Triangles appear to be missing, showing gaps through the mesh — model looks "see-through"
- This is a regression — earlier versions showed these models as solid coloured previews
- Affects `ThreeMfMeshParser.parse()` path (painted models) or `getPreparePreviewMesh()` path
- Not related to the gcode viewer instanced tubes change (B42/B43)
- Needs investigation: compare with v1.5.34 or v1.5.33 to find when it regressed

### B42: G-code preview tubes appear as flat rectangles, not rounded tubes (GitHub #46)
- The instanced tube geometry (v1.5.37) uses a box cross-section (top + left + right faces) which looks rectangular rather than cylindrical when zoomed in
- SliceBeam/libvgcode use an 8-vertex hexagonal cross-section that looks rounder
- Consider increasing vertex count per instance (e.g. 6 faces / 36 vertices) or using a diamond cross-section for a rounder appearance
- Low priority — functional but cosmetic

### B43: G-code preview lighting too dark — models appear almost black (GitHub #47)
- The instanced tube shader's lighting produces very dark shadows, especially on side faces
- The `AMBIENT = 0.20` is too low for the small tube geometry — side faces pointing away from lights render nearly black
- Fix: increase ambient to ~0.35–0.40, or add a hemisphere ambient term
- Compare with the Prepare tab model viewer which has brighter lighting

### B44: colored_3DBenchy.3mf shows only 3 colours instead of 4 (GitHub #48)
- The model has 4 paint colours but the slice output only uses 3 extruders
- Visible in Prepare screen: Color 1 (blue) and Color 2 (red) shown, but the model visually has 4 distinct regions
- May be an extruder compaction issue where two source colours map to the same physical extruder
- Needs investigation: check `detectedExtruders`, `extruderRemap`, and paint_color data in the 3MF

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

### F58: More prime tower settings (GitHub #38)
- Expose prime tower width (`prime_tower_width`) and other prime tower options from the Prepare screen
- Purge volume per extruder and prime tower brim also candidates
- Keys must be threaded through `buildProfileOverrides()` and added to native `profile_keys[]` / `applyConfigToPrusa()` in `sapil_print.cpp`
- Track: [`#38`](https://github.com/taylormadearmy/u1-slicer-for-android/issues/38)

### F62: G-code viewer — fit camera to content on load
- Default camera distance is fixed at 500mm regardless of model size — small models (tetrahedron, buttons) appear as tiny specks at default zoom
- `GcodeRenderer.kt` lines 132/169: `camera.distance = 500f` hardcoded
- Fix: after G-code is uploaded to GPU, compute the bounding box of all move positions and set `camera.distance` and `camera.target` to frame the content
- Same issue applies when opening via "View G-code" from Jobs tab (F60)
- Confirmed by E2E screenshot: tetrahedron visible only as a 2px speck at default zoom after v1.5.32 tube width fix

### D1: Document slicer engine upgrade process
- Write a guide covering how to update the OrcaSlicer submodule to a new version (Snapmaker Orca or FullSpectrum fork)
- Should document: submodule pin process, our Android-specific patches that must be re-applied (`#ifdef __ANDROID__` diagnostics, initializer fixes, build fixes for clipper.hpp/Brim.cpp/CutSurface.cpp), SAPIL JNI interface contract, config key differences, native rebuild workflow
- Include a checklist for testing after an engine upgrade
- Useful for both routine Snapmaker Orca updates and the eventual FullSpectrum fork evaluation (F14)

## Closed (recent)
See git log for full history. Most recent fixes:
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
