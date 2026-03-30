# Backlog

Open bugs, features, and investigations. Everything else is done — see git log.

## Open Bugs

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

### F48: Better Prepare preview for very large 3MF models (GitHub #29)
- Very large models fall back to a simplified top-down bed footprint preview (loses 3D perspective and colour detail)
- Investigate: LOD/mesh decimation for a lower-poly 3D preview, budget-aware simplification on import, or streaming coarse-then-refine
- Must not regress B18 OOM protections
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

### D1: Document slicer engine upgrade process
- Write a guide covering how to update the OrcaSlicer submodule to a new version (Snapmaker Orca or FullSpectrum fork)
- Should document: submodule pin process, our Android-specific patches that must be re-applied (`#ifdef __ANDROID__` diagnostics, initializer fixes, build fixes for clipper.hpp/Brim.cpp/CutSurface.cpp), SAPIL JNI interface contract, config key differences, native rebuild workflow
- Include a checklist for testing after an engine upgrade
- Useful for both routine Snapmaker Orca updates and the eventual FullSpectrum fork evaluation (F14)

## Closed (recent)
See git log for full history. Most recent fixes:
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
