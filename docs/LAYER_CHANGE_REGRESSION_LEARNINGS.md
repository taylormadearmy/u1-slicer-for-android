# Layer-change / slice performance — learnings (March 2026)

This document records **runtime evidence** and **engineering decisions** so work can continue from a **v1.5.0 baseline** without losing context.

## Baseline release

- **v1.5.0** git commit: `a554c36` (`release: v1.5.0 — fix post-upgrade slicing failure (uninitialized m_origin)`)
- **App version** (that commit / release): `versionName 1.5.0`, `versionCode 166`

## User-reported behaviour

- On **v1.5.0** (e.g. Pixel 9a): slicing the Hueforge-style sample (`flippy+flappy+mini.3mf` small dual-colour plate) completes in **~7 s** total; the **“90% / Generating G-code”** phase ~**1 s**.
- On **experimental builds** (e.g. Pixel 8a): same class of job took **~400+ s** `native.slice()` wall time; UI stuck at **90%** for **many minutes**.

## What we measured (NDJSON session `e8fc2f`)

From `.cursor/debug-e8fc2f.log` on a completed slow run:

| Metric | Approx. value |
|--------|----------------|
| **`native.slice()` total** | **~413 s** (`durationMs` ≈ 412918) |
| **Start → first “90% / Generating G-code”** | **~88 s** (toolpath / `print.process()` region) |
| **Gap at 90% before first “Generating G-code: layer 1”** | **~304 s** |
| **Per-layer G-code lines (“layer 1” … “layer 16”)** | **~2 s** |
| **Kotlin post** (pause inject + full G-code parse + validation) | **&lt; 1 s** |

**Conclusion:** The regression is **primarily native** (slice + export), **not** Kotlin pause injection or `GcodeParser` validation.

## Native code note (UI stuck at 90%)

In `sapil_print.cpp`, progress jumps to **90%** immediately before `print.export_gcode(...)`, and **no** progress updates fire until export advances. A long stall at **90%** can therefore mean **real work inside export** (or before first layer callback), not necessarily a Kotlin hang.

## UX gaps (separate from performance)

- **G-code preview** can show multi-colour using pause segments while the slicer still emits **single-filament** G-code.
- **Slice summary “Per extruder”** uses `; filament used [mm]` from Orca — single-tool jobs show **one** extruder; users expect **parity** with multi-material UX (needs **app-side aggregation** by pause segment or metadata).

## Suggested direction

1. **Branch from v1.5.0** (`a554c36`) for fixes; keep experimental work on an **archive branch**.
2. **Bisect / diff** native slice + `export_gcode` path vs experiment: find what introduced the **~300 s** pre-layer-1 stall.
3. Re-apply **minimal Kotlin** (preview/summary) **after** native performance matches v1.5.0 on the same device/file.

## Archive branch

Experimental work is preserved on branch **`archive/layer-change-experiment-2026-03`** (see git history).

## Fix branch

Work from stable baseline: **`fix/slice-perf-from-v1.5.0-baseline`** (from `a554c36`).

## Diff snapshot: v1.5.0 (`a554c36`) vs experiment archive (`archive/layer-change-experiment-2026-03`)

Quick comparison for the **native slice entrypoints** (not exhaustive):

| Path | Notes |
|------|--------|
| `app/src/main/cpp/src/sapil_print.cpp` | Small diff: conditional `single_extruder_multi_material` when embedded profile exists; `profile_keys[]` adds `machine_pause_gcode`, `single_extruder_multi_material_priming`, `manual_filament_change`. |
| `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so` | **Binary differs strongly** (size change in working tree between branches — treat as **rebuild/replace**, not a few-line source tweak). Any performance regression may be **in the shipped `.so`**, not only Kotlin. |

Next step on `fix/slice-perf-from-v1.5.0-baseline`: reproduce **~7 s** slice on the same 3MF/device, then **binary-search** which change (sources + `.so` + embedded profile pipeline) reintroduces the multi-minute `native.slice()`.
