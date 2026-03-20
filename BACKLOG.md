# Backlog

Open bugs, features, and investigations. Everything else is done — see git log.

## Open Bugs

### B18: OOM on large/complex 3MF files
- Reproduce with: `C:\Users\kevin\Downloads\test-data\2026+F1+CALENDAR+-+DATES+&+TRACK+NAMES+(P_X+SERIES).3mf`
- This file is ~103 MB compressed and contains a `3D/Objects/object_9.model` entry that expands to ~680 MB
- Investigate memory usage across import, sanitize, embed, and native load for giant component-model files

### B31: First slip/slide slice can hit a native Clipper range crash
- Repro from `G:/My Drive/tes-data/clipper_investigation_bundle (1).txt`
- First slice attempt on `slip slide spin fidget.3mf` failed with `Coordinate outside allowed range`
- The failure path shows `hasCustomPlacement=true` and a wipe tower near the edge, so the placement/wipe-tower interaction needs another pass

### B32: Printer still shows the generic printer image after upload
- Repro from `G:/My Drive/tes-data/output (2).gcode`
- After upload, the printer falls back to its default/generic image instead of the file thumbnail
- Suspected cause: Moonraker/Klipper thumbnail handling or upload metadata caching still needs investigation

## Open Features

### F14: Mixed-colour / pseudo-extruder support (FullSpectrum fork)
- Source: ratdoux/OrcaSlicer-FullSpectrum — fork of Snapmaker Orca 2.2.4
- Produces optically-blended colours via layer-cycle alternation (e.g. Blue+Yellow→Green)
- **Blocked**: upstream was v0.9.4 alpha as of 2026-03-12, untested on real hardware
- Wait for v1.0 / hardware-verified release before porting
- Requires native .so rebuild

### F27: Cancel slicing mid-operation
- No way to abort a slice once started — user must wait or force-kill
- Requires native cancellation support (check OrcaSlicer for a cancel flag in the slicing loop)

### F28: Show prime tower filament usage on Preview page
- Display per-extruder filament consumed by wipe tower (purge waste) in slice results card
- Parse from G-code or OrcaSlicer output metadata if available

## Closed (recent)
See git log for full history. Most recent fixes:
- **B32**: Thumbnail format switched to vanilla Klipper format (no `THUMBNAIL_BLOCK_START/END` wrappers, 76-char base64 lines) — matches u1-slicer-bridge which is confirmed working on Snapmaker hardware; the THUMBNAIL_BLOCK_START format added in v1.4.10 requires newer Moonraker and was causing the printer to show its default icon instead of the job preview — FIXED v1.4.11
- **B30**: Uploaded printer thumbnails now mirror Orca's native `THUMBNAIL_BLOCK_START/END` wrapping and line length so Moonraker/Klipper recognizes the embedded preview instead of showing the default icon
- **I2**: First post-update Clipper "Coordinate outside allowed range" failure hardened again — FIXED v1.4.1
- **Native Prepare Preview**: Prepare preview now uses native/Orca-backed mesh export instead of the old Kotlin-only path — DONE v1.4.0
- **B24**: Stale slice config (skirt/prime tower not updating on re-slice) — FIXED v1.3.42
- **B22/B23**: Multi-color preview race + extruder map mismatch — FIXED v1.3.37
- **F3/F25**: Per-vertex multi-color preview + extruder picker — DONE v1.3.36
