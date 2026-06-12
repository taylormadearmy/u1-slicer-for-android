# Per-mix top-surface mixing modes (Proportional / Dither / Fine lines / Ironing glaze)

**Date:** 2026-06-12. **Approved by Kevin** ("let's build all 3 … behind settings … at the mix
level so I can test them on the same print"). Builds on the shipped-on-branch v1 top-surface
mixer (`feature/colormix-topsurface`, engine submodule `colormix-topsurface` @ae237449).

## Goal

Make top-surface mixing visually better, with every enhancement OFF by default and
configurable **per mix row**, so multiple objects on one plate (each assigned a different
mix) can A/B different modes in a single print.

## Settings (per MixedFilamentRow)

| Setting | Values | Default | Effect |
|---|---|---|---|
| `topMixMode` | STRIPES / PROPORTIONAL / DITHER | STRIPES | How top lines are divided between components |
| `fineTopLines` | bool | false | Region's `top_surface_line_width` → nozzle/2 |
| `ironingGlaze` | bool | false | Force ironing on the region; ironing pass split across components |

STRIPES = exactly today's v1 behaviour (per-line round-robin). All defaults off ⇒ output
byte-equivalent to current branch.

## Data flow

1. **Kotlin**: 3 new fields on `MixedFilamentRow` (+ JSON persistence for project/library
   storage). `MixedFilamentManager.serialize` appends new tagged tokens to each row of
   `mixed_filament_definitions`: `t<0|1|2>` (mode), `f<0|1>` (fine lines), `i<0|1>` (glaze).
   Absent tokens parse as defaults on both sides (backwards/forwards compatible).
2. **UI**: mix editor gains a "Top surface mixing (BETA)" section — a 3-chip mode picker +
   two toggles. No other surfaces change.
3. **Engine** (`MixedFilament` parse): new fields `top_mix_mode`, `fine_top_lines`,
   `ironing_glaze`, parsed from the same tokens, defaulting to 0. The tokenizer must
   tolerate unknown/missing tags (verify; fix if strict).

## Engine behaviour

All modes reuse the v1 machinery: per-tool buckets → `by_extruder` islands →
ToolOrdering registration → "never route to an unplanned tool" gate. No mid-layer
`set_extruder`. Non-mix regions and flag-off mixes are untouched.

- **PROPORTIONAL**: each top line splits at the cumulative-weight boundary (e.g. 70/30 →
  first 70% of the line in A, rest in B; N-way generalises by cumulative weights). The
  boundary position staggers line-to-line (brick phase, derived from line index) so weights
  read as smooth tone. Implemented as a new polyline splitter callback feeding the shared
  `split_extrusion_collection_with_polyline_splitter`, with piece→tool assignment by
  within-line position instead of sequence index.
- **DITHER**: each line chops into dashes (engine constant `k_dither_dash_len` ≈ 3 mm,
  scaled; not user-exposed in v1) and each dash maps to a component via a deterministic
  position-based halftone: 4×4 Bayer threshold sampled at the dash midpoint on a cell grid
  (cell = 2× line width), threshold compared against the cumulative weight distribution.
  Weights become dot density; pattern is stable across re-slices (no RNG).
- **FINE TOP LINES**: at region-config derivation (the same spot where a volume's extruder
  override lands on the region — `PrintObject.cpp` ~3216 area), when the region's solid
  infill filament is a mix with the flag, override `top_surface_line_width` to
  `nozzle_diameter / 2`. Pure config change; composes with any mode.
- **IRONING GLAZE**: same derivation point force-enables ironing for the region
  (`ironing_type` = top surfaces) if off. At GCode time, ironing extrusions
  (`erIroning` role) of a glaze-mix region are split per-line across components with the
  sequence phase offset by +1 relative to the printed stripes (v1 makes no spatial
  registration guarantee — alternation only). ToolOrdering's registration gate extends to
  "layer has erIroning fills for a glaze-mix region". Composes with any mode.

**Implementation note (verify during recon, fix if wrong):** ironing extrusions are
assumed to live in `layerm->fills` with role `erIroning`, reachable by the same
collection-level routing as top solid infill. If ironing is emitted via a different path,
the glaze design needs re-anchoring before implementation.

## Safety invariants (unchanged from v1)

- Split only when every component tool is planned in the layer (`has_extruder(tool-1)`);
  otherwise fall back to single-tool emission.
- `extruder_override == 0` and `!is_anything_overridden` gates kept.
- ToolOrdering over-registration allowed (purge-only visit); under-registration impossible
  (emitted tools ⊆ enumerated components, same row lookup both sides).

## Testing (red-green; reds all written and confirmed BEFORE engine work)

Fixture: `calib-cube-10-dual-colour-merged.3mf`, wipe tower ON, Pixel 8a (43211JEKB16931).

- **Unit (JVM)**: token serialize/parse round-trip incl. absent-token defaults and
  mixed old/new strings; MixedFilamentRow JSON persistence round-trip; UI structural
  guards for the new editor section.
- **Instrumented (new class `TopSurfaceMixModesTest`, helpers from
  `TopSurfaceMixWipeTowerTest`)**:
  - PROPORTIONAL: some single `;TYPE:Top surface` line-pair shows both tools within one
    line's extent — gate: a layer's top blocks contain both component tools AND
    tool-change count inside top blocks ≥ 2× the STRIPES baseline is too brittle; instead
    assert per-line split evidence: ≥1 layer where both tools appear AND the number of
    distinct extrusion runs exceeds the line count of the STRIPES control slice.
    (Implementer may refine the discriminator; it must be RED on stripes output.)
  - DITHER: ≥1 top layer where alternation count inside top blocks ≥ 4× the stripes
    baseline for the same model (dash-level alternation).
  - FINE LINES: top-surface `;WIDTH:` (or `; LINE_WIDTH`) annotations / extrusion E-per-mm
    in top blocks ≈ half the default-width control.
  - GLAZE: G-code contains `;TYPE:Ironing` blocks for the mixed object and both component
    tools extrude inside them.
  - CONTROL: a STRIPES-mode mix and a no-mix slice remain byte-pattern-equivalent to
    today's behaviour (reuse existing tests; they must stay green).
- **Regression battery**: full JVM unit suite, MixSlot* ×7, SemmSlicingTest,
  SlicingIntegrationTest, TopSurfaceMixWipeTowerTest.

## Deliverable

One release APK from `feature/colormix-topsurface` staged to `G:\My Drive\claude\`
once all gates are green. No push/merge/release.

## Out of scope (v1 of these modes)

- User-exposed dash-size / cell-size knobs (engine constants for now).
- Spatially registered glaze (half-pitch geometric offset) — alternation-phase only.
- Outer-wall or non-top surface mixing.
- Preview visualisation of the modes (G-code viewer already shows per-tool colours).
