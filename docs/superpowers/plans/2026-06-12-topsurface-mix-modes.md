# Per-Mix Top-Surface Mixing Modes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Per-mix-row settings (Proportional / Dither modes, Fine top lines, Ironing glaze) that improve top-surface colour mixing, all defaulting to today's behaviour.

**Architecture:** New tagged tokens in the existing `mixed_filament_definitions` recipe string carry three per-row fields from Kotlin to the engine. The engine dispatches the existing wipe-tower-safe top-surface split (by-tool islands + ToolOrdering registration + planned-tools gate) through mode-specific polyline splitters/assigners, plus region-config overrides for line width and ironing.

**Tech Stack:** Kotlin/Compose (app), C++ OrcaSlicer fork (engine submodule `colormix-topsurface`), instrumented tests on Pixel 8a `43211JEKB16931`, `scripts/rebuild-native-so.sh` for the `.so`.

**Spec:** `docs/superpowers/specs/2026-06-12-topsurface-mix-modes-design.md` (approved).

**Worktree:** `D:\projects\u1-slicer-for-android\.claude\worktrees\colormix-own`, branch `feature/colormix-topsurface`. Engine submodule at `app/src/main/cpp/orcaslicer`, branch `colormix-topsurface`. Build dir `app/.cxx/Release/colormix-own/arm64-v8a`. Gradle ALWAYS `--no-daemon`; device ALWAYS `ANDROID_SERIAL=43211JEKB16931`; never device NE12442001324.

**Build-cycle strategy (deviation from strict per-task TDD, deliberate):** each engine
rebuild is ~15–25 min. Therefore ALL instrumented red tests are written and confirmed RED
first (Task 4), then all engine work lands (Tasks 5–7), then ONE rebuild verifies all
greens (Task 8). Kotlin-side tasks keep strict red-green (JVM tests are cheap).

---

### Task 1: Kotlin data model + recipe serialization

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/data/MixedFilamentRow.kt`
- Modify: `app/src/main/java/com/u1/slicer/data/MixedFilamentManager.kt` (`serializeRow`, line ~151; plus `addN`/`editN` plumbing if fields are constructor params)
- Test: `app/src/test/java/com/u1/slicer/data/MixTopSurfaceSettingsTest.kt` (new)

- [ ] **Step 1: Write the failing tests** (new file):

```kotlin
package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MixTopSurfaceSettingsTest {

    private fun mgr() = MixedFilamentManager(
        loadProject = { emptyList() }, loadLibrary = { emptyList() },
        saveProject = {}, saveLibrary = {},
    )

    @Test
    fun defaults_serializeAsZeroTokens() {
        val m = mgr()
        m.addN(listOf(1, 2), listOf(50, 50), MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
        val row = m.serialize(numPhysicalFilaments = 4)
        assertTrue("default row must carry t0,f0,i0: $row", row.contains(",t0,f0,i0,"))
    }

    @Test
    fun topMixSettings_roundTripThroughSerialize() {
        val m = mgr()
        m.addN(listOf(1, 2), listOf(70, 30), MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
        val id = m.projectMixes.value.first().id
        m.updateTopSurfaceSettings(
            id,
            topMixMode = MixedFilamentRow.TopMixMode.DITHER,
            fineTopLines = true,
            ironingGlaze = true,
        )
        val row = m.serialize(numPhysicalFilaments = 4)
        assertTrue("dither+fine+glaze tokens expected: $row", row.contains(",t2,f1,i1,"))
        val updated = m.projectMixes.value.first()
        assertEquals(MixedFilamentRow.TopMixMode.DITHER, updated.topMixMode)
        assertTrue(updated.fineTopLines)
        assertTrue(updated.ironingGlaze)
    }

    @Test
    fun proportionalMode_serializesT1() {
        val m = mgr()
        m.addN(listOf(1, 2), listOf(50, 50), MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
        m.updateTopSurfaceSettings(
            m.projectMixes.value.first().id,
            topMixMode = MixedFilamentRow.TopMixMode.PROPORTIONAL,
            fineTopLines = false, ironingGlaze = false,
        )
        assertTrue(m.serialize(4).contains(",t1,f0,i0,"))
    }
}
```

(If `projectMixes` is named differently — check the manager's public StateFlows; the
existing tests `MixSlot*Test` show the accessor — adjust accordingly, keeping assertions.)

- [ ] **Step 2: Run, verify RED**: `./gradlew testDebugUnitTest --no-daemon --tests "com.u1.slicer.data.MixTopSurfaceSettingsTest"` → compile failure (missing enum/fields/method).
- [ ] **Step 3: Implement.** In `MixedFilamentRow.kt`: add to the data class (with defaults so all existing call sites compile unchanged):

```kotlin
val topMixMode: TopMixMode = TopMixMode.STRIPES,
val fineTopLines: Boolean = false,
val ironingGlaze: Boolean = false,
```

and inside the companion-level enums area:

```kotlin
/** How a mix divides its TOP-surface lines between components (BETA). */
enum class TopMixMode {
    /** v1 behaviour: whole lines round-robin across components. */
    STRIPES,
    /** Each line splits at the cumulative-weight boundary, brick-staggered. */
    PROPORTIONAL,
    /** Lines chopped to dashes assigned by position-based halftone. */
    DITHER,
}
```

In `MixedFilamentManager.kt`:
1. `serializeRow` emits the tokens immediately after `o0` and before `u`:

```kotlin
val topMode = when (r.topMixMode) {
    MixedFilamentRow.TopMixMode.STRIPES -> 0
    MixedFilamentRow.TopMixMode.PROPORTIONAL -> 1
    MixedFilamentRow.TopMixMode.DITHER -> 2
}
return "${r.componentA},${r.componentB},1,1,${r.mixBPercent},0," +
    "g$ids,w$weights,m$distMode,z0,xa0,xb0,d0,o0," +
    "t$topMode,f${if (r.fineTopLines) 1 else 0},i${if (r.ironingGlaze) 1 else 0},u${r.id}"
```

2. Add the mutator (mirror the style of existing `editN`/update methods — copy-on-write the row in the right StateFlow, project or library, persist via the save lambda):

```kotlin
fun updateTopSurfaceSettings(
    id: String,
    topMixMode: MixedFilamentRow.TopMixMode,
    fineTopLines: Boolean,
    ironingGlaze: Boolean,
)
```

3. JSON persistence: find where `MixedFilamentRow` is written/read for project/library storage (the `loadProject`/`saveProject` lambdas' callers — grep `MixedFilamentRow(` in `data/`). Add the three fields with absent-key defaults (STRIPES/false/false) so old stored libraries load unchanged. Extend the round-trip test in whatever existing storage test covers rows (grep `MixedFilamentStorage` / `MixLibrary` tests) with one case for the new fields.
- [ ] **Step 4: Run, verify GREEN** (same command), plus the neighbouring suites: `--tests "com.u1.slicer.data.*Mix*"`.
- [ ] **Step 5: Commit** `feat(mix): per-row top-surface settings (mode/fine/glaze) + recipe tokens`.

### Task 2: Mix editor UI section

**Files:**
- Modify: the mix editor composable — find it: `grep -rn "distributionMode" app/src/main/java/com/u1/slicer/ui/` (the dialog/screen where a mix's components, weights and mode are edited; M4 added it).
- Test: `app/src/test/java/com/u1/slicer/ui/TopSurfaceMixSettingsUiTest.kt` (new, source-grep structural style — see `ModelInfoDialogScrollTest.kt` for the pattern).

- [ ] **Step 1: Write failing structural test** asserting: (a) the mix editor source contains a section headed `Top surface mixing` with a `BETA` marker; (b) it renders three mode options labelled `Stripes`, `Proportional`, `Dither` bound to `TopMixMode`; (c) two toggles bound to `fineTopLines` / `ironingGlaze` labelled `Fine top lines` and `Ironing glaze`; (d) the confirm/save path calls `updateTopSurfaceSettings`. Structural test = read the source file as text from the test (JVM) and assert the patterns; mirrors `ModelInfoDialogScrollTest`.
- [ ] **Step 2: RED**, **Step 3: implement the section** (Material3, follow the existing editor's row/chip idioms; BETA pill style copied from the existing mix BETA pills added in M4), **Step 4: GREEN** + run `ui.*` unit tests, **Step 5: Commit** `feat(mix): Top surface mixing (BETA) section in mix editor`.

### Task 3: Engine recipe parse (tokens → MixedFilament fields)

**Files:**
- Modify: `app/src/main/cpp/orcaslicer/src/libslic3r/MixedFilament.hpp` (struct fields), `MixedFilament.cpp` (deserialize).

No engine test harness exists; correctness is proven by Task 4's instrumented reds going green in Task 8. Do NOT build yet.

- [ ] **Step 1: Add fields** to the `MixedFilament` struct (near `distribution_mode`):

```cpp
// BETA per-row top-surface mixing settings (tokens t/f/i; default 0 = v1 stripes).
int  top_mix_mode    = 0;  // 0 stripes, 1 proportional, 2 dither
bool fine_top_lines  = false;
bool ironing_glaze   = false;
```

- [ ] **Step 2: Parse.** Find the deserialize loop (`MixedFilament.cpp` ~2150–2230, the code that reads `m`, `z`, `xa`, `xb`, `d`, `o`, `u` tags). FIRST verify how unknown tags are treated; the parser must skip unrecognised tags without aborting the row (if it is strict, make it tolerant). Then add tag handling mirroring the existing style:

```cpp
else if (tok.size() >= 2 && tok[0] == 't')
    mf.top_mix_mode = clamp_int(atoi(tok.c_str() + 1), 0, 2);
else if (tok.size() >= 2 && tok[0] == 'f')
    mf.fine_top_lines = atoi(tok.c_str() + 1) != 0;
else if (tok.size() >= 2 && tok[0] == 'i')
    mf.ironing_glaze = atoi(tok.c_str() + 1) != 0;
```

Apply to BOTH deserialize paths if there are two (the ~2183 and ~2219 regions both construct rows). Confirm `disable_pointillism_mode` / serialization-side functions don't strip the new fields (they only touch distribution_mode — leave them alone). Also check the ENGINE-side `serialize_custom_entries` (~2219 area): it re-emits rows; add the three tokens there too so engine round-trips preserve them.
- [ ] **Step 3: Commit (submodule)** `feat(colormix): parse per-row top-surface mode/fine/glaze tokens`.

### Task 4: Instrumented RED tests (all four behaviours)

**Files:**
- Create: `app/src/androidTest/java/com/u1/slicer/slicing/TopSurfaceMixModesTest.kt`
- Modify: `app/src/androidTest/java/com/u1/slicer/slicing/SurfaceColorMixTestSupport.kt` (recipe builder must accept the new settings)

- [ ] **Step 1: Extend `SurfaceColorMixTestSupport.buildRecipeAndSlot`** with parameters `topMixMode: MixedFilamentRow.TopMixMode = STRIPES, fineTopLines: Boolean = false, ironingGlaze: Boolean = false`, applied via `updateTopSurfaceSettings` before `serialize`.
- [ ] **Step 2: Write the test class.** Reuse `TopSurfaceMixWipeTowerTest`'s helpers (copy or factor into the support object): asset copy, `makeConfig` (wipe tower ON), `isExtrudingMove`, per-layer scanning. New scanning helpers needed:

```kotlin
/** Tool-change count strictly inside ;TYPE:Top surface blocks (existing helper). */
/** Count of contiguous same-tool extrusion runs inside top blocks of one layer. */
/** Tools extruding inside ;TYPE:Ironing blocks across the file. */
/** Median ;WIDTH: value (Orca emits ;WIDTH:<mm> annotations) inside top blocks; if absent, derive width from E-per-XY-mm ratio. Check a stripes-control slice FIRST to learn which annotation exists. */
```

Tests (each slices the calib cube, ONE object assigned the mix — slots [3,4], weights [50,50] unless stated):
1. `proportional_topLinesSplitWithinLine`: mode PROPORTIONAL. Gate: in ≥1 layer, top blocks contain both T2 and T3 AND the same-tool run count exceeds that layer's run count in a STRIPES control slice of the same model (slice both in the test; stripes runs ≈ line count, proportional ≈ 2× since every line has two pieces). Must be RED (engine ignores t1 → behaves as stripes; identical run counts).
2. `dither_topAlternationFarExceedsStripes`: mode DITHER. Gate: total tool changes inside top blocks ≥ 3× the stripes control's count. RED today.
3. `fineTopLines_halvesTopSurfaceWidth`: STRIPES + fineTopLines. Gate: median top-block width ≤ 0.6× the control's. RED today.
4. `ironingGlaze_bothToolsInsideIroningBlocks`: STRIPES + glaze (70/30 weights to also exercise weighting). Gate: file contains `;TYPE:Ironing` for the mixed slice AND ≥2 distinct tools extrude inside ironing blocks. RED today (no ironing emitted at all without the flag — assert and report whether ironing appears).
5. `defaults_unchanged_stripesStillGreen`: STRIPES + all flags off must satisfy the EXISTING TopSurfaceMixWipeTowerTest gate (both tools in ≥1 top layer) — stays GREEN before and after (drift guard).
- [ ] **Step 3: Run on device, confirm 1–4 RED / 5 GREEN**: `ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest --no-daemon "-Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.slicing.TopSurfaceMixModesTest"`. Paste each failure message into the report; failures must be on the GATES, not crashes/config errors.
- [ ] **Step 4: Commit** `test(colormix): red — per-mix top-surface mode gates (proportional/dither/fine/glaze)`.

### Task 5: Engine — proportional + dither modes

**Files:**
- Modify (submodule): `src/libslic3r/GCode.cpp` only.

Anchors (current HEAD ae237449): sequence builder `top_surface_mix_sequence_for_row` ~4100; shared splitter `split_extrusion_collection_with_polyline_splitter` ~4198; direction-change splitter ~4290; top-surface wrapper ~4365; cached lambda `top_surface_mix_sequence_for_filament` ~5215; routing branch ~5808 (gated `erTopSolidInfill`).

- [ ] **Step 1: Thread the row through.** The routing branch currently only has the sequence. It also needs the `MixedFilament*` row (for `top_mix_mode` + weights). Extend the cached lambda to return (or cache alongside) the row pointer, or add a sibling `top_surface_mix_row_for_filament` lookup with the same `is_mixed` + enabled gates.
- [ ] **Step 2: PROPORTIONAL (`top_mix_mode == 1`).** New splitter + assigner replacing the per-piece sequence assignment for this mode:
  - Compute cumulative weight fractions `c_0..c_k` for the sequence's unique tools in first-appearance order using the row weights (`decode_gradient_component_weights_for_gcode`; fall back ratio_a/ratio_b).
  - For each polyline (one straight top line after direction-change splitting — REUSE the existing direction-change splitter first, then subdivide): split each line at length fractions `c_i`, with a per-line brick phase: `phase_offset = (line_index % 4) * 0.25` applied as a cyclic shift of the boundary positions (boundaries at `frac(c_i + phase_offset)`, pieces map to the component owning that fraction interval). Piece→tool by interval, NOT by round-robin index.
  - Keep `k_min_direction_segment_len`-style degenerate guards: a piece shorter than 2× line width merges into its neighbour rather than emitting a sliver.
- [ ] **Step 3: DITHER (`top_mix_mode == 2`).** After direction-change splitting, chop each line into dashes of `k_dither_dash_len = scaled<double>(3.0)` (const double, NOT constexpr — `SCALING_FACTOR` is runtime). Assign each dash by halftone:

```cpp
static const int k_bayer4[4][4] = {{0,8,2,10},{12,4,14,6},{3,11,1,9},{15,7,13,5}};
// cell = 2 * top line width (scaled); midpoint of the dash:
const double cell = 2.0 * line_width_scaled;
const int bx = int(std::floor(mid.x() / cell)) & 3;
const int by = int(std::floor(mid.y() / cell)) & 3;
const double threshold = (k_bayer4[by][bx] + 0.5) / 16.0;  // [0,1)
// pick the component whose cumulative weight interval contains `threshold`
```

  Deterministic, position-stable, weights = density. Same sliver-merge guard.
- [ ] **Step 4: Dispatch.** In the routing branch: mode 0 → existing per-line round-robin path unchanged (byte-identical); mode 1/2 → the new splitters. All modes share bucket routing, the planned-tools gate, stats counters (add `top_surface_mix_mode` to the per-layer debug log).
- [ ] **Step 5: Commit (submodule)** `feat(colormix): proportional + dither top-surface modes`.

### Task 6: Engine — fine top lines + ironing glaze (region config derivation)

**Files:**
- Modify (submodule): `src/libslic3r/PrintObject.cpp` (region config derivation ~3216 where volume extruder overrides land), `src/libslic3r/GCode.cpp` (ironing routing), `src/libslic3r/GCode/ToolOrdering.cpp` (ironing registration gate).

- [ ] **Step 1: Recon ironing (BLOCKING check).** Verify ironing extrusions are produced into `layerm->fills` with role `erIroning` and that a region-level config (`ironing_type`/`ironing` keys — check `PrintConfig.cpp`) turns them on per-region. If ironing lives elsewhere (e.g. emitted in a separate fills collection or only via global config), STOP and report NEEDS_CONTEXT with the actual mechanism before coding.
- [ ] **Step 2: Region overrides.** At the derivation point where a volume's `extruder` becomes the region's filaments (PrintObject.cpp ~3216): when the resolved solid-infill filament id `is_mixed` and the row has `fine_top_lines`, set the region config's `top_surface_line_width` to `nozzle_diameter / 2` (use the config's existing accessor types — it's a `ConfigOptionFloatOrPercent`; set absolute mm). When the row has `ironing_glaze` and region ironing is off, enable it (set `ironing_type` to top-surfaces equivalent). Mirror existing override idioms in that function. Guard: only when the mix row exists and is enabled.
- [ ] **Step 3: Ironing split + routing (GCode.cpp).** Extend the top-surface routing branch's role gate: `erTopSolidInfill` always; `erIroning` ADDITIONALLY when the row has `ironing_glaze`. For ironing pieces use per-line round-robin (mode-independent) with `sequence_phase + 1` relative to the print stripes (alternation offset per spec). Same planned-tools gate.
- [ ] **Step 4: ToolOrdering.** In `collect_extruders` (~810 area, next to `has_top_solid_infill`): also detect `has_ironing = any fill front role == erIroning`; register all mix components when `(has_top_solid_infill) || (has_ironing && row->ironing_glaze)`. Reuse `top_surface_mix_component_tools`.
- [ ] **Step 5: Commit (submodule)** `feat(colormix): fine top lines + ironing glaze region overrides and routing`.

### Task 7: Rebuild + green loop

- [ ] **Step 1:** `bash scripts/rebuild-native-so.sh /d/projects/u1-slicer-for-android/.claude/worktrees/colormix-own/app/.cxx/Release/colormix-own/arm64-v8a` — run from the WORKTREE root (the dirty-submodule guard resolves paths from cwd). Verify: ~20 MB, clang 17.0.2, LOAD 0x4000, 51/51 JNI symbols.
- [ ] **Step 2:** Run `TopSurfaceMixModesTest` on the Pixel 8a; iterate engine fixes (small commits) until 1–4 GREEN and 5 still GREEN. Each iteration: fix → commit submodule → rebuild → rerun.
- [ ] **Step 3:** Commit gitlink + `.so`: `feat(colormix): top-surface mix modes — engine gitlink + rebuilt .so`.

### Task 8: Regression battery + APK

- [ ] **Step 1:** Full JVM unit suite: `./gradlew testDebugUnitTest --no-daemon` → green.
- [ ] **Step 2:** Per-class instrumented loop on Pixel 8a (sequential, copying each result XML): `TopSurfaceMixWipeTowerTest`, `TopSurfaceMixModesTest`, `MixSlotBlendVerificationTest`, `MixSlotNWayBlendGateTest`, `MixSlotObjectAssignBlendGateTest`, `MixSlotObjectAssignDiagnosticTest`, `MixSlotPaintRoundTripTest`, `MixSlotRealLoadPathBlendTest`, `MixSlotSliceIntegrationTest`, `SemmSlicingTest`, `SlicingIntegrationTest` → all green.
- [ ] **Step 3:** `./gradlew assembleRelease --no-daemon`; copy to `G:/My Drive/claude/u1-slicer-colormix-modes-beta-<shortsha>.apk`.
- [ ] **Step 4:** BACKLOG entry for the feature (mark as ON-BRANCH, unreleased), memory update, report. NO push/merge/release.
