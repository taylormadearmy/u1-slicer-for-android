# Backlog

Open bugs, features, and investigations. Everything else is done — see git log.

## Open Bugs

### B38: Post-upgrade native Clipper/helper-geometry failure on first slice - INVESTIGATING
- Strongly associated with install/upgrade-over-the-top flows, especially on Pixel 9a and now reliably on Pixel 6 release repro loops
- Historically presented as `Coordinate outside allowed range`, geometry overflow, or visually wrong first slice after upgrade
- Current best concrete failing path is still `GCode.head_wrap_detect_zone.union`, but the latest release bundles also show the bad pre-`Outer wall` block as unlabeled `OTHER` geometry with impossible model-bearing coordinates
- Latest release-side loop (`v1.4.74`-`v1.4.75`) confirmed:
  - the bad result is still rejected as invalid
  - the same suspicious block persists around lines ~694-703
  - the new writer / first-layer breadcrumbs still are not surfacing in exported diagnostics bundles
  - failure shape alternates between sentinel-style and finite but wrong Y coordinates
  - the block shape is stable, but the Y coordinate generation differs between good and bad runs
- Deep investigation notes, repro history, and narrowed hypotheses live in [`CLIPPER_UPGRADE_INVESTIGATION.md`](CLIPPER_UPGRADE_INVESTIGATION.md)

### B34: Printer light button icon confused for app theme toggle (GitHub #7) — FIXED
- The button used `Icons.Default.LightMode` (sun) / `Icons.Default.DarkMode` (moon) — identical to Android's theme-switch icons
- Fixed: changed to `Icons.Default.Lightbulb` (yellow when on, dimmed when off) — clearly a physical light, not a UI theme toggle
- There is no app-level light/dark theme switch; the app is dark-only

### B18: OOM on large/complex 3MF files (GitHub #17)
- Reproduce with: `C:\Users\kevin\Downloads\test-data\2026+F1+CALENDAR+-+DATES+&+TRACK+NAMES+(P_X+SERIES).3mf`
- This file is ~103 MB compressed and contains a `3D/Objects/object_9.model` entry that expands to ~680 MB
- Also reproduced via MakerWorld WebView download: `super clean.3mf` (58 MB compressed, native tried to allocate 331 MB → OOM with 187 MB free)
- Investigate memory usage across import, sanitize, embed, and native load for giant component-model files
- Mitigations landed in `v1.4.27-dev`:
  - Prepare now falls back to a simplified top-down bed preview for large models so model footprints and the wipe tower can still be moved when the full mesh preview is skipped
  - 3MF imports now preflight ZIP entry sizes and reject obviously oversized embedded component archives before sanitize/embed/native load
  - Manual batch coverage now includes `2026+F1+CALENDAR+-+DATES+&+TRACK+NAMES+(P_X+SERIES).3mf` and `super clean.3mf` as the two large-file regressions that should keep being checked on-device
- Still open: genuinely large-but-not-rejected archives may still pressure memory during sanitize or native load, so the issue is reduced but not fully closed

### B31: First slip/slide slice can hit a native Clipper range crash — FIXED v1.4.20
- Root cause: ARM64 FCVTZS saturation producing INT64_MIN/MAX in Clipper `IntersectPoint` vertical-edge paths and `Round<int64_t>()` for large-but-finite doubles
- Kotlin fix: clamp wipe tower to bed bounds before slicing; persist clipper recovery flag across restart to prevent crash-loop
- Native fix: overflow guards in `IntersectPoint` (vertical-edge + general-case `Dx*q+b`) and `Round()` (large double detection), all falling back to scanbeam position
- v1.4.21: clear stale clipper markers on APK upgrade to prevent false crash report after updating

### B36: MakerWorld download/loading text is unclear — FIXED v1.4.22
- Status messages during MakerWorld import were confusing (e.g. "Loading Downloading from MakerWorld……")
- Fixed: each loading state now provides a complete display message ("Downloading from MakerWorld…", "Loading model.3mf…", "Preparing model…")

### B37: Hueforge filament pauses / colour changes are dropped when sliced in-app (GitHub #21)
- Repro model from issue: `https://makerworld.com/models/402958?appSharePlatform=copy`
- Reported behavior: the source 3MF includes the required colour changes at specific layer heights, but after slicing in the app those pauses/tool changes are not emitted
- Visible symptom: Slice Summary / filament usage attributes the whole print to nozzle 2 and excludes nozzles 3 and 4
- Investigate whether Hueforge-style layer-based filament swaps are being lost during 3MF import, profile embed/sanitize, native config translation, or G-code post-processing
- Need a regression asset/test once root cause is understood, since this is a real multi-colour workflow rather than a standard per-object extruder assignment case

## Open Cleanup

### C1: Remove dead warm-reload and upgrade-guard machinery
- `shouldWarmReloadAfterUpgrade()` now always returns false (disabled in v1.4.24)
- Partial cleanup landed in `v1.4.27-dev`:
  - removed dead `shouldWarmReloadAfterUpgrade()` helpers from `SlicerViewModel`
  - removed dead `warmReloadNativeModelAfterUpgrade()` path
  - removed the obsolete warm-reload test
- Code to remove or simplify:
  - `post_upgrade_slice_settled` guard settling logic in slice path — now diagnostics-only, audit if still worth keeping
  - `sessionHasPostUpgradeGuard` in `DiagnosticsStore` — now diagnostics-only, audit if anything else depends on it
  - `clipperRetryAttempted` and `clipper_recovery_restart` machinery — may still be needed for non-upgrade Clipper errors, audit before removing
- **Caution**: `clipperRetryAttempted` may protect against Clipper edge cases unrelated to upgrades — verify before removing

## Open Features

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
