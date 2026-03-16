# Backlog

Open bugs, features, and investigations. Everything else is done — see git log.

## Open Bugs

### B18: OOM on large/complex 3MF files
- Reproduce with: `app/src/androidTest/assets/2026+F1+CALENDAR+...3mf`
- Investigate memory usage during load/sanitize/parse pipeline for this file

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
- **B24**: Stale slice config (skirt/prime tower not updating on re-slice) — FIXED v1.3.42
- **I2**: Clipper "Coordinate outside allowed range" — FIXED v1.3.29 + v1.3.42
- **B22/B23**: Multi-color preview race + extruder map mismatch — FIXED v1.3.37
- **F3/F25**: Per-vertex multi-color preview + extruder picker — DONE v1.3.36
