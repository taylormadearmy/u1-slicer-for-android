# Clipper Post-Upgrade Investigation

> **Status (2026-05-22): APPARENTLY RESOLVED.** Not observed in any release since the v1.5.0 native rebuild (B38). v2.x E2E batches and instrumented sweeps run clean. Retained as forensic reference. If poisoned-Clipper coordinates (`Long.MIN_VALUE` / `Long.MAX_VALUE`) reappear in slicing output, re-open BACKLOG entry A3 and this doc.

## Summary

This document captures the current state of the long-running investigation into the intermittent-but-often-reproducible post-upgrade slicing failure in U1 Slicer for Android.

The short version:

- The bug is real and reproducible.
- It is strongly associated with installing/upgrading the app over itself and then slicing soon after.
- It reproduces much more reliably on a Pixel 9a than on a Pixel 8a.
- It lives in native C++ geometry handling, not in normal Kotlin UI state.
- The most concrete failing path found so far is `GCode.head_wrap_detect_zone.union`.
- The core visible native failure has consistently been a poisoned Clipper polygon with sentinel coordinates such as `Long.MIN_VALUE` / `Long.MAX_VALUE`.
- Even after skipping that head-wrap union on empty zones (`v1.4.53`), we still saw wrong-success output cases where slicing "succeeds" but the model appears missing.
- `v1.4.55` tightened output validation enough to fail the bad slice explicitly:
  - `slice_output_invalid`
  - `parsedSuspiciousModelExtrudeMoves=9166`
  - impossible model extrusion coordinates such as `y=-9223372474941440`
- Current investigation focus is now the native geometry producer that emits invalid model-bearing extrusion, not just Clipper exception sites.
- `v1.4.56` showed the bad output is intermittent across retries on the same build:
  - several attempts across separate reinstalls can succeed
  - a later reinstall attempt can still produce invalid model extrusion with impossible coordinates
  - that points toward stateful or nondeterministic native geometry generation rather than a single broken import path or one poisoned in-memory slice session

### v1.4.70 - v1.4.75

- Release-only Pixel 6 / Pixel 9a loop on `pm install -r`:
  - the bad output still reproduces on release
  - the bad block still lands immediately before `; FEATURE: Outer wall`
  - the failure shape alternates between sentinel-style coordinates and finite-but-wrong Y coordinates around `59542..59552`
  - the new writer / first-layer breadcrumbs still do not surface in exported bundles
  - the current trace work is now focused on the exact pre-`Outer wall` emitter, because the present hooks are still too high-level
- `v1.4.70` through `v1.4.75` confirmed the release-only Pixel 6 loop is still reproducing the issue:
  - `pm install -r` over-top is the best autonomous repro path
  - the bad block still appears immediately before `; FEATURE: Outer wall`
  - the failure alternates between sentinel-style coordinates and finite but wrong Y coordinates around `59542..59552`
  - the new writer / first-layer breadcrumbs still are not surfacing in exported diagnostics bundles

## User-Visible Pattern

Observed behavior over many repro cycles:

- After upgrading/installing the app over itself, the first slice may fail.
- Failure modes seen:
  - `Coordinate outside allowed range`
  - `Slicing failed: geometry overflow`
  - empty or wrong-looking slice result
  - prime tower / wipe tower visible but model missing
- Historically, repeated resets could sometimes make the problem go away.
- Force-stopping before install often avoids the issue.
- Reinstall-over-the-top while the app is running was one of the strongest repro paths.
- Release builds are the best repro target. Debug builds make the bug much harder to trigger and are not reliable for reproducing the failure.

## Important Repro Findings

### Process / device findings

- Pixel 9a is a much stronger repro device than Pixel 8a.
- Reproducing on Pixel 9a but not consistently on Pixel 8a suggests:
  - device/runtime sensitivity
  - native undefined behavior
  - architecture / compiler / memory-layout sensitivity

### Upgrade / reset findings

- The issue is post-upgrade biased, but not purely a stale-process problem.
- A stale running process was part of the issue, but not the whole issue.
- We added runtime APK-change detection and cold restart handling, and the bug still reproduced afterward.
- `Reset App State` helped often, but not deterministically.
- That means simple file/cache cleanup alone is not the root cause.

### File / memory findings

- Not a straightforward RAM pressure issue:
  - `lowMemory=false`
  - heaps looked healthy in failing bundles
- Not a raw model file issue:
  - same raw `.3mf` hash across failing and succeeding runs
- Not obviously an embedded profile-config corruption issue:
  - key config hashes remained stable in many failing/succeeding comparisons

## Core Native Failure Signature

Across many builds, the native failure repeatedly looked like:

- `clipper_invalid_path_detected`
- source:
  - `clipper.AddPathInternal.input`
- operation labels evolved over time from generic to specific
- poisoned path shapes:
  - 4-point rectangle-like polygon
  - 6-point polygon variant
- invalid coordinates:
  - `y = -9223372036854775808`
  - `y =  9223372036854775807`

Typical failing 4-point variant:

- two bad points on one edge
- two sane points on the opposite edge
- looked synthetic, not mesh-derived

This strongly suggested helper geometry, not model triangles.

## Investigation Timeline

### Early hypotheses that were tested

1. Embedded WebView/auth work
- unrelated

2. Generic app cache / files
- partially relevant to recovery
- not root cause

3. Upgrade cleanup too weak
- improved cleanup
- did not eliminate the failure

4. Stale process after upgrade
- definitely part of the story
- but bug still happened in a fresh restarted process afterward

5. Wipe tower / prime tower helper geometry
- some experiments pointed this way
- later instrumentation ruled out several wipe-tower-specific paths

### Recovery / mitigation experiments

These were tried:

- stronger upgrade purge
- `Reset App State`
- APK-change detection while running
- first-slice workaround experiments
- wipe tower suppression experiments
- output validation

Findings:

- some mitigations changed the failure mode
- none removed the root cause
- certain workarounds produced misleading “success” results

## Diagnostic Builds and What They Taught Us

This is the condensed history of the important narrowing steps.

### v1.4.35

- Added early native overflow guards.
- First promising build where one first-post-upgrade slice succeeded.
- Not a full fix.

### v1.4.36

- Instrumented path-level invalid polygon detection.
- Showed the poisoned polygon was already present at `clipper.AddPathInternal.input`.

### v1.4.37

- Added bounds fallback around helper-rectangle assumptions.
- Ruled out one `GetBounds()`-based theory.

### v1.4.38 - v1.4.42

- Explored wipe-tower-related theories and targeted instrumentation.
- Important for ruling out several wrong suspects.
- Did not land on the true caller.

### v1.4.43 - v1.4.49

- Added caller labeling and progressively preserved more specific labels.
- This narrowed the failure from generic Clipper union down to:
  - `ClipperUtils.union_.polygons`

### v1.4.50 - v1.4.51

- Added native trace / flight-recorder style diagnostics.
- The separate trace flush path did not pay off as much as hoped.
- But `.51` gave the real breakthrough label:
  - `GCode.head_wrap_detect_zone.union`

### v1.4.52

- Added targeted fallback around `GCode.head_wrap_detect_zone.union`.
- If that helper path throws, we now log:
  - `head_wrap_detect_zone_fallback`
- and continue with:
  - `in_head_wrap_detect_zone=false`

This turned a hard failure into a survivable path, but it also revealed another issue:

- one run produced a wrong-looking output
- diagnostics showed the G-code still had many non-prime extrusions
- so the result was not literally empty at G-code level

Important clue from `.52`:

- `headWrapPointCount=0`

That meant we were still attempting risky helper geometry work even when the head-wrap zone itself was empty.

### v1.4.53

- Short-circuits the head-wrap detect-zone union when the zone is empty
- Adds deeper upstream diagnostics:
  - `head_wrap_detect_zone_inputs`
  - `head_wrap_detect_zone_skipped`
  - existing `head_wrap_detect_zone_fallback`

This is the current investigation build at the time of writing.

### v1.4.54 - v1.4.55

- Added stricter G-code output validation so helper-only or impossible-coordinate outputs no longer pass as success.
- Added model-bearing extrusion bounds and suspicious-coordinate diagnostics.
- `v1.4.55` proved the bad result is still produced by the native pipeline, but it is now caught as invalid rather than looking like a successful slice.

### v1.4.56

- Same output-validation work as `.55`, but now exercised over many retries on the same install.
- Confirmed that the invalid output can appear only after several successful slices across separate reinstalls.
- This makes the remaining bug look more like a stateful native geometry producer or nondeterministic native corruption than a deterministic model-load problem.

## Strongest Current Conclusion

The most concrete, defensible current conclusion is:

- one real failing consumer is `GCode.head_wrap_detect_zone.union`
- the poisoned polygon appears to be synthetic helper geometry
- it is likely being built from one or more of:
  - object instance bounding-box projections
  - `print.first_layer_convex_hull()`
  - translated plate/print-space helper geometry

Important nuance:

- `GCode.head_wrap_detect_zone.union` may be the first place the poison is *consumed*
- it may or may not be the original source of the bad coordinate

Right now, the best upstream suspects are:

1. object instance projection rectangles built from `instance.get_bounding_box()`
2. `print.first_layer_convex_hull()`
3. translated helper geometry in the head-wrap detect-zone placeholder path
4. later native geometry that intermittently emits impossible model-bearing extrusion
4. later native geometry that emits impossible model-bearing extrusion even when the head-wrap helper path is skipped

## Known Good / Known Bad Signals

### Known bad signals

- `clipper_invalid_path_detected`
- `Coordinate outside allowed range`
- `clipper_coordinate_out_of_range`
- sentinel coordinates:
  - `Long.MIN_VALUE`
  - `Long.MAX_VALUE`

### Known useful labels

- `ClipperUtils.union_.polygons`
- `GCode.head_wrap_detect_zone.union`

### Known useful diagnostics events

- `slice_geometry_snapshot`
- `slice_process_snapshot`
- `slice_file_snapshot`
- `slice_native_model_snapshot`
- `clipper_invalid_path_detected`
- `head_wrap_detect_zone_inputs`
- `head_wrap_detect_zone_skipped`
- `head_wrap_detect_zone_fallback`
- `slice_output_validation`
- `slice_output_invalid`
- `parsedSuspiciousModelExtrudeMoves`
- `parsedSuspiciousModelSamples`

## Current Practical Strategy

There are now two parallel goals:

### 1. Contain the user pain

- avoid hard app failure where possible
- prefer explicit fallback to bogus “success”
- short-circuit risky helper paths when inputs are obviously empty/invalid

### 2. Find the source fix

- inspect the actual geometry producers for the head-wrap helper path
- determine whether the poison originates in:
  - bbox projection creation
  - first-layer convex hull creation
  - translation / coordinate conversion

## Recommended Next Steps

If investigation continues, these are the best next steps in order:

1. Use `v1.4.53` on the Pixel 9a and capture a fresh diagnostics bundle.

2. Compare:
   - `head_wrap_detect_zone_inputs`
   - `head_wrap_detect_zone_skipped`
   - `head_wrap_detect_zone_fallback`

3. Decide from that whether the poisoned geometry is already present in:
   - the per-instance bbox projections
   - the first-layer convex hull
   - or only when combining them

4. If still needed, instrument upstream producers directly:
   - `Print::finalize_first_layer_convex_hull()`
   - `Print::first_layer_islands()`
   - `PrintInstance::get_bounding_box()` consumers in the G-code head-wrap path

5. Keep Pixel 9a as the primary repro device.

## Important Local Build Notes

- Full Ninja rebuilds on this repo often hit NDK/LLVM memory issues on Windows.
- Targeted native rebuilds have been more reliable:
  - compile only changed translation units
  - update static archives
  - relink `libprusaslicer-jni.so`
  - copy into `app/src/main/jniLibs/arm64-v8a/`
  - package with Gradle

## Latest Update v1.4.58

The newest investigation build now tags suspicious model-bearing extrusion samples with their source G-code line numbers.

That means the next useful bundle should be able to answer:

- which line first went bad
- whether the bad move is tied to a specific feature stream
- whether the impossible coordinate appears right at the first model move or only later in the output

## Latest Update v1.4.62

The next build adds a first-layer emission breadcrumb at the G-code writer boundary:

- `first_layer_extrude_snapshot`

The goal is to tell whether the bad `OTHER` block is already present in the path being emitted, or whether it is introduced later in the layer-start output.

The latest bundle confirmed that the first clearly bad emitted block starts around G-code line `695` and is still labeled `featureType=OTHER`.

The raw window around that block was:

- `; LAYER_HEIGHT: 0.3`
- `G1 F3000`
- `G1 X115.808 Y18140369.165 E.46533`
- `G1 X115.808 Y18140360.601 E.46533`
- `G1 X124.372 Y18140360.601 E.46533`
- `G1 X124.372 Y18140369.125 E.46315`

That is now the primary source target: a synthetic-looking `OTHER` geometry block with impossible Y coordinates, not a head-wrap helper zone and not a raw model load issue.

## Latest Update v1.4.60

The next build carries the raw feature label through suspicious model moves, so the next bundle should tell us whether the bad `OTHER` block is truly unlabeled or just being misclassified by the parser.

## Current Versions Referenced

- current investigation branch point in this note: `v1.4.75`
- current Android app build markers:
- `versionName = 1.4.75`
- `versionCode = 164`

## Bottom Line

We are not out of ideas.

The investigation has progressed from:

- “mysterious post-upgrade native crash”

to:

- “specific synthetic helper geometry path in native G-code generation, most concretely around `GCode.head_wrap_detect_zone.union`, with a likely upstream source in bbox / first-layer helper geometry”

That is a much better place to be, and this file exists so that if context is lost, we do not have to rediscover all of that from scratch.

## Addendum v1.4.59

The current primary target is the `OTHER` feature block around G-code line `695`.

The important raw lines from the latest repro are:

- `; LAYER_HEIGHT: 0.3`
- `G1 F3000`
- `G1 X115.808 Y18140369.165 E.46533`
- `G1 X115.808 Y18140360.601 E.46533`
- `G1 X124.372 Y18140360.601 E.46533`
- `G1 X124.372 Y18140369.125 E.46315`

That block is the best place to look next for the source of the impossible coordinates.

## Addendum v1.4.75

The current release-only loop on Pixel 6 / Pixel 9a is now on `v1.4.75`.

What the newest release bundles show:

- `pm install -r` over-top is still the best autonomous repro path.
- The bad slice still reproduces on release.
- The bad block still appears immediately before `; FEATURE: Outer wall`.
- The failure alternates between:
  - sentinel-style coordinates (`922337...`)
  - finite-but-wrong Y coordinates around `59542..59552`
- The new writer / first-layer breadcrumbs still do not surface in exported bundles.
- The best current comparison is the same helper-like rectangle block with identical X coordinates and divergent Y generation:
  - bad run: `G1 X115.808 Y9223372036854775.807 ...`
  - good run: `G1 X115.808 Y138.754 ...` / `G1 X115.808 Y130.19 ...`
- So the remaining bug is very likely in the Y-coordinate generation for that pre-`Outer wall` helper block, not in the overall shape of the block itself.

So the next useful work is still to move the instrumentation even closer to the exact pre-`Outer wall` emitter, or to identify why that emitter bypasses the current hooks entirely.

## Addendum v1.4.76 — Root Cause Found: WipeTower2 Internal State

### Session: 2026-03-26

This session conclusively identified the source of the bad coordinates.

### What was tried

1. **HWASan (Hardware Address Sanitizer)**: Built the entire native library with `-fsanitize=hwaddress`. HWASan crashed during its own `__hwasan_init` on Pixel 9a, Pixel 8a, and Pixel 6 — the `wrap.sh` + `LD_PRELOAD` approach is incompatible with modern Android's zygote fork model.

2. **ASan (Address Sanitizer)**: Rebuilt with `-fsanitize=address` and NDK 26/23 runtimes. Both crashed with `SIGILL` (`ILL_ILLOPN`) in `__interceptor_prctl` — the ASan runtime uses instructions not available on these Pixel devices.

3. **Native backtrace capture**: Added coordinate bounds checking in `GCodeWriter::travel_to_xy`, `extrude_to_xy`, and `extrude_to_xyz`. When coordinates exceed 500mm (well beyond the 270mm bed), a native backtrace is captured via `<unwind.h>` + `dladdr()` and logged as a `gcode_coordinate_violation` diagnostics event.

### What the backtrace revealed

The backtrace is identical across all violations:

```
Java_com_u1_slicer_NativeLibrary_slice
  → sapil::SlicerEngine::slice
    → Print::export_gcode
      → GCode::do_export → _do_export → process_layers → process_layer
        → WipeTowerIntegration::tool_change
          → WipeTowerIntegration::append_tcr   ← converts ToolChangeResult to G-code
            → GCodeWriter::travel_to_xy        ← caught with bad Y
```

### Config is NOT corrupted

Three validation checkpoints were added to `sapil_print.cpp`:
- **after_apply**: `wipe_tower_y = 230` ✓
- **after_process**: `wipe_tower_y = 230` ✓
- **before_export**: `wipe_tower_y = 230` ✓

The `DynamicPrintConfig` is never corrupted. The `WipeTowerIntegration` constructor reads the correct value.

### The corruption is inside WipeTower2

The `WipeTower::ToolChangeResult` struct returned by `WipeTower2::tool_change()` contains `start_pos` and `end_pos` with corrupted Y coordinates. In the latest repro, the Y value was literally `inf` (infinity), confirming this is a **float math overflow**, not memory corruption.

The `ToolChangeResult` is built by `WipeTower2::construct_tcr()` (WipeTower2.cpp:1384), which reads positions from `WipeTowerWriter2`:
```cpp
result.start_pos = writer.start_pos_rotated();
result.end_pos   = priming ? writer.pos() : writer.pos_rotated();
```

The `WipeTowerWriter2::rotate()` function (line 1370) uses `m_y_shift` and `m_wipe_tower_depth` for coordinate transformation. If either value overflows or becomes NaN/inf during the toolchange sequence, all subsequent rotated positions become infinity.

`m_y_shift` is set in `tool_change()` (line 1693):
```cpp
.set_y_shift(m_y_shift + (m_layer_info->depth - m_layer_info->toolchanges_depth()))
```

### Key observations across repros

| Repro | Bad Y value | Type |
|-------|-------------|------|
| v1.4.76 bundle (2) | `-57690` | large negative float |
| v1.4.76 bundle (3) | `inf` | float infinity |
| Earlier bundles | `9223372036854775807` | Long.MAX_VALUE (Clipper path) |
| Earlier bundles | `18140369` | large positive |
| Earlier bundles | `59542..59552` | moderate positive |

The alternation between infinity and large finite values is consistent with float overflow in different code paths through the wipe tower geometry.

### Next steps

1. Instrument `WipeTower2::tool_change()` to log `m_y_shift`, `m_layer_info->depth`, `toolchanges_depth()`, and the writer position at entry and exit
2. Check `m_filpar` vector bounds access in `toolchange_Unload`/`toolchange_Load`/`toolchange_Wipe`
3. Check `m_depth_traversed` accumulation for overflow
4. Check `cleaning_box` coordinates for NaN/inf propagation

### Further narrowing: WipeTower2 finish_layer, stored TCR corruption

After adding `bool m_isBBLPrinter = false;` (Print.hpp:1035), the code path switched from `WipeTower` (BBL) to `WipeTower2` (correct for Snapmaker). The bug persisted but through a different call path:

- **Before fix**: `append_tcr` → `travel_to_xy` (WipeTower/BBL path)
- **After fix**: `append_tcr2` → `travel_to_xy` (WipeTower2 path)

Instrumentation of `WipeTower2::finish_layer()` showed **all internal state is correct at generation time**:
- `m_y_shift = 0`, `current_depth = 0`, `layer_depth = 2`, `toolchanges_depth = 2`
- `any_bad = false` for every single layer
- The `ToolChangeResult` objects are generated with valid coordinates during `print.process()`

But when `WipeTowerIntegration::tool_change()` consumes the stored results during `export_gcode()`, the `end_pos.y()` field contains `-inf`.

**This confirms genuine memory corruption of the stored `ToolChangeResult` data** between `print.process()` (generation) and `export_gcode()` (consumption). The `m_wipe_tower_data.tool_changes` vector or its contents are being overwritten by something in between.

The `m_isBBLPrinter` uninitialized-bool bug was a real secondary issue (wrong wipe tower selected in release builds), but the primary corruption is in the TCR storage lifetime.

### Recommended next investigation step

Add a validation checksum to `ToolChangeResult` objects:
1. At generation time (`construct_tcr`): compute a hash of `start_pos`, `end_pos`, `gcode.size()`
2. Store the hash in the TCR struct
3. At consumption time (`append_tcr`/`append_tcr2`): verify the hash
4. If mismatch: log which fields changed, providing a fingerprint of the corruption

### Build artifacts from this session

- `CMakeLists.txt`: Added `ENABLE_HWASAN` and `ENABLE_ASAN` cmake options (both OFF by default)
- `GCodeWriter.cpp`: Coordinate bounds check with native backtrace capture
- `GCode.hpp`: `WipeTowerIntegration::validated_wipe_tower_pos()` clamp (won't trigger since config is fine)
- `sapil_print.cpp`: Three `wipe_tower_y_checkpoint` events
- `clipper.hpp`: Replaced `using` declarations with forward declarations only (build fix)
- `CutSurface.cpp`: Qualified `ClipperLib::PolyFillType` with `Slic3r::` prefix (build fix)
- `Brim.cpp`: Qualified `Polygon`/`Point` types with `Slic3r::` prefix (build fix)
- `Print.hpp`: `bool m_isBBLPrinter = false;` — fixed uninitialized bool that selected wrong wipe tower in release builds
- `WipeTower2.cpp`: `finish_layer()` and `tool_change()` state logging, `construct_tcr()` bad-position logging
- `sapil_print.cpp`: Three `wipe_tower_y_checkpoint` validation events (after_apply, after_process, before_export)

## Addendum — TCR Checksum/Canary Instrumentation

### Session: 2026-03-26 (second session)

Added a checksum/canary system to `ToolChangeResult` to confirm and fingerprint the memory corruption between `process()` and `export_gcode()`.

### What was added

**`WipeTower.hpp` — ToolChangeResult struct** (Android-only, `#ifdef __ANDROID__`):
- `canary_head` / `canary_tail`: Two `0xDEADBEEF` sentinels bracketing the diagnostic fields
- `checksum`: FNV-1a 32-bit hash over `print_z`, `layer_height`, `start_pos`, `end_pos`, `elapsed_time`, `initial_tool`, `new_tool`, `priming`, `gcode.size()`
- `snap_start_y`, `snap_end_y`, `snap_gcode_sz`: Snapshots of the values most likely to show corruption
- `seal()`: Writes canaries + snapshots + checksum. Called at generation time.
- `verify(caller)`: Recomputes checksum, compares canaries and snapshots, returns JSON diagnostic string if any mismatch. Returns empty string if OK or if never sealed.
- Non-Android builds get no-op `seal()` / `verify()`.

**Seal points** (generation time, during `print.process()`):
- `WipeTower2::construct_tcr()` — seals every TCR after position/gcode assignment
- `WipeTower::construct_tcr()` — seals in BBL path too
- `merge_tcr()` in `WipeTower2.cpp` — re-seals after merging finish_layer with tool_change

**Verify points** (consumption time, during `export_gcode()`):
- `WipeTowerIntegration::append_tcr2()` — verifies each TCR just before consuming it (WipeTower2 path)
- `WipeTowerIntegration::append_tcr()` — verifies in BBL path too
- `pre_export_verify` — verifies ALL stored TCRs right before `WipeTowerIntegration` is constructed
- `post_generate_verify` — verifies ALL stored TCRs right after `wipe_tower.generate()` completes (in `Print.cpp`)

All verification failures emit `tcr_corruption_detected` native diagnostics events.

### What the next bundle will tell us

The diagnostics event `tcr_corruption_detected` will appear with one of four `caller` values:

| Caller | Meaning |
|--------|---------|
| `post_generate_verify` | Corruption happened during or immediately after `generate()` — the vector storage itself is suspect |
| `pre_export_verify` | Corruption happened between `process()` and `export_gcode()` — something overwrites the `m_wipe_tower_data` storage between phases |
| `append_tcr2` | Corruption happened during `export_gcode()` — possibly during layer iteration |
| `append_tcr` | Same as above but BBL code path |

The JSON payload tells us exactly which fields changed:
- `canary_head_ok` / `canary_tail_ok`: If a canary is trashed, memory overwrite touched the canary region
- `checksum_ok`: If canaries are OK but checksum differs, a field value changed without overwriting the struct boundary
- `start_y_match` / `end_y_match`: The Y coordinates we know go bad — direct comparison of sealed vs current value
- `gcode_sz_match`: If the gcode string was reallocated or corrupted

### Key scenarios

1. **Canaries trashed**: Buffer overwrite from an adjacent allocation — likely a `std::vector` realloc or out-of-bounds write
2. **Canaries OK, checksum bad, end_y changed**: Targeted field corruption — possibly a dangling pointer/reference or a use-after-move
3. **All OK at post_generate, bad at pre_export**: Corruption happens between `process()` and `export_gcode()` — focus on what runs in between
4. **All OK at pre_export, bad at append_tcr2**: Corruption during export_gcode layer iteration — focus on concurrent access or iterator invalidation

### Files changed

- `WipeTower.hpp`: Added canary/checksum fields and methods to `ToolChangeResult`
- `WipeTower2.cpp`: Added `seal()` calls in `construct_tcr()` and `merge_tcr()`
- `WipeTower.cpp`: Added `seal()` call in BBL `construct_tcr()`
- `GCode.cpp`: Added `verify()` calls in `append_tcr()`, `append_tcr2()`, and `pre_export_verify`; added `tcr_append_state` logging and `pre_travel_to_tower`/`pre_phony_move` checks
- `Print.cpp`: Added `verify()` call in `post_generate_verify`; added `tcr_canary_active` marker event

## ROOT CAUSE FOUND AND FIXED — 2026-03-26

### The bug: `Print::m_origin` (Vec3d) was uninitialized

`Print.hpp:1059` declared `Vec3d m_origin;` with **no initializer**. On Android, `set_plate_origin()` is never called (it's only called from the desktop GUI's `PartPlate.cpp`). In release builds, `m_origin` reads whatever garbage is in memory — which intermittently contained `-inf` in the Y component.

This `-inf` propagated through:
1. `WipeTowerIntegration` constructor stores `m_plate_origin = plate_origin` (from `print.get_plate_origin()`)
2. `append_tcr2()` computes `plate_origin_2d(m_plate_origin(0), m_plate_origin(1))`
3. Every travel move adds `plate_origin_2d` to wipe tower coordinates: `end_pos + plate_origin_2d`
4. Adding anything to `-inf` produces `-inf`
5. `GCodeWriter::travel_to_xy()` emits `Y-9223372036854775.808` (float-to-string of -inf)

### The fix

```cpp
// Print.hpp:1059 — before:
Vec3d   m_origin;

// Print.hpp:1059 — after:
Vec3d   m_origin = Vec3d::Zero();
```

### Why it only appeared in release builds

C++ does not zero-initialize non-static member variables without explicit initializers. Debug builds typically zero-fill heap/stack memory, masking the bug. Release builds with optimizations leave memory indeterminate, so `m_origin` reads whatever was previously in that memory location.

### Why it was intermittent and post-upgrade biased

The value of uninitialized memory depends on:
- What previously occupied that heap address
- Memory allocator behavior (which changes after app upgrades when the runtime reinitializes)
- Device-specific allocator implementations (explains Pixel 9a vs Pixel 8a difference)

After `pm install -r`, the app's memory layout changes subtly. Sometimes `m_origin`'s location happened to contain zeros (success). Sometimes it contained `-inf` or other garbage (failure). This is classic undefined behavior.

### Same class of bug as `m_isBBLPrinter`

`Print::m_isBBLPrinter` (fixed earlier in this investigation) was also an uninitialized `bool` that selected the wrong wipe tower in release builds. Both bugs are uninitialized members in `Print` that are harmless in debug and catastrophic in release.

### Additional preventive fixes

Audited all Print-related classes for uninitialized members and fixed:
- `Print.hpp`: `Vec3d m_origin = Vec3d::Zero();` — **the root cause**
- `Print.hpp`: `FakeWipeTower` — initialized all float/Vec members to zero
- `WipeTower.hpp`: `size_t m_cur_layer_id = 0;` — BBL path, low risk but fixed

### Verification

After the fix, 20+ consecutive `pm install -r` upgrade cycles on Pixel phone all produced clean slices with:
- `plate_origin_y: 0.000000`
- `parsedSuspiciousModelExtrudeMoves: 0`
- `gcode_coordinate_violation: 0`
- `overlapsExpectedModelFootprint: true`

### How the investigation narrowed to this

1. **Backtrace** (v1.4.76 session 1) → `append_tcr2` → `travel_to_xy` with bad Y
2. **Config checkpoints** → `wipe_tower_y` config was always correct
3. **TCR canary/checksum** → stored `ToolChangeResult` data was never corrupted
4. **Transform check** → rotation + `m_wipe_tower_pos` transform produced valid coordinates
5. **`tcr_append_state` logging** → revealed `plate_origin_y: -inf` from the very first call
6. **Source inspection** → `m_origin` had no initializer and `set_plate_origin()` is never called on Android

## Instrumentation Removed in v1.4.77 Cleanup

The following investigation-specific instrumentation was removed after the root cause was found. Documented here so it can be re-added if a similar issue arises.

### Kept (permanent safety nets)

- **`GCodeWriter.cpp`**: `gcode_coordinate_violation` — bounds check in `travel_to_xy`/`extrude_to_xy` with native backtrace via `<unwind.h>` + `dladdr()`. Fires when any coordinate exceeds 500mm. Cheap, catches any future regression.
- **`sapil_print.cpp`**: `wipe_tower_y_checkpoint` — validates `wipe_tower_y` config at 3 phases (after_apply, after_process, before_export). Lightweight config sanity check.
- **`WipeTower2.cpp`**: `wipe_tower_finish_layer_state` / `wipe_tower_tool_change_state` / `wipe_tower_tcr_bad_position` — state logging in WipeTower2 generation. Fires only when bad positions are detected.

### Removed (investigation scaffolding)

#### 1. TCR Canary/Checksum System (WipeTower.hpp)

Added `#ifdef __ANDROID__` block to `ToolChangeResult` struct with:
- `canary_head` / `canary_tail` — `0xDEADBEEF` sentinel uint32_t values
- `checksum` — FNV-1a 32-bit hash over critical fields
- `snap_start_y`, `snap_end_y`, `snap_gcode_sz` — value snapshots
- `seal()` — writes canaries + checksum after TCR generation
- `verify(caller)` — recomputes checksum, returns JSON diagnostic if mismatch
- Non-Android: no-op stubs

**To re-add:** Add the fields after `force_travel` in `ToolChangeResult`, call `seal()` in `construct_tcr()` and `merge_tcr()`, call `verify()` in `append_tcr()`/`append_tcr2()`.

**Finding:** TCR data was never corrupted. The bug was in `m_plate_origin`, not the stored TCR structs.

#### 2. seal() Calls (WipeTower2.cpp, WipeTower.cpp)

- `WipeTower2::construct_tcr()` — `result.seal()` before return
- `merge_tcr()` in WipeTower2.cpp — `out.seal()` before return
- `WipeTower::construct_tcr()` — `result.seal()` before return

#### 3. tcr_append_state Logging (GCode.cpp)

Lambda `log_append_tcr2_state` in `append_tcr2()` that logged full WipeTowerIntegration state:
- `m_wipe_tower_pos`, `m_wipe_tower_rotation`, `plate_origin_2d`, `gcodegen.origin()`, writer position, raw + transformed TCR positions
- Fired on first 3 calls (baseline) and whenever bad coordinates detected
- Callsites: `entry`, `pre_travel_to_tower`, `pre_phony_move`

**Key finding from this instrumentation:** `plate_origin_y: -inf` from the very first call, leading directly to the root cause.

#### 4. Verification Checkpoints (GCode.cpp, Print.cpp)

- `post_generate_verify` in Print.cpp — verified all TCR checksums after `wipe_tower.generate()`
- `tcr_canary_active` in Print.cpp — marker event confirming canary build was running
- `pre_export_verify` in GCode.cpp — verified all TCR checksums before `WipeTowerIntegration` construction
- `verify()` calls in `append_tcr()` and `append_tcr2()` — per-TCR checksum verification at consumption

#### 5. tcr_transform_violation Check (GCode.cpp)

Check after `transform_wt_pt()` that fired if transformed `start_pos`/`end_pos` had bad Y. Never fired — confirmed the transform was fine and narrowed the bug to `plate_origin_2d`.
