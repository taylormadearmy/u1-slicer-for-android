# Full-Spectrum M3 Phase B — Smart Paint Mix Integration

**Date:** 2026-06-06
**Status:** Architecture-level design (detail filled in when Phase A ships)
**Parent:** [`2026-05-26-full-spectrum-roadmap.md`](2026-05-26-full-spectrum-roadmap.md) — supersedes the §M3a sketch
**Depends on:** [Phase A](2026-06-06-full-spectrum-m3-phase-a-manual-pair-mixer-design.md) must ship first
**Sibling:** [Phase C](2026-06-06-full-spectrum-m3-phase-c-target-colour-picker-design.md)

## Goal

Smart Paint's AI region-assignment step extends from "pick the closest of 4
physical filaments" to "pick the closest of physical + mix slots". Smart Paint
stops being capped at 4 colours.

## Architecture

Phase A widens `AiRegion.slot: Int` to carry virtual filament IDs (≥4) and
delivers a `MixedFilamentManager` exposing `projectMixes` and `libraryMixes`
state flows. Phase B builds on both:

```
AiPaintViewModel (existing)
  ├── ai_segments_with_target_colours: List<AiRegion>     (from AI service)
  └── needs to assign each region to a slot
        │
        ▼
ColourPaletteResolver  (NEW)                              ← Phase B core
  inputs:
    - region.suggestedColour
    - physicalFilamentColours: List<Color>                (size 1..4)
    - projectMixes: List<MixedFilamentRow> + predicted blended colours
    - libraryMixes: List<MixedFilamentRow> + predicted blended colours
  output:
    - bestSlot: Int  (0..N where N = num_physical + num_mixes - 1)
        │
        ▼
AiRegion.slot updated; existing rendering + slice path picks up the new ID.
```

The "predicted blended colour" for a mix is computed once per mix-set change.
v1 uses a **naive linear RGB interpolation** (`(1-p) × A + p × B`) — known to
be a poor predictor of real perceptual blend, but accurate enough to demo. Phase
C replaces this with `prusa-fdm-mixer` (the M4 dependency) for an accurate
prediction.

## Components

- **`ColourPaletteResolver`** (new, Kotlin pure function with unit tests). Takes
  the inputs above and returns the closest slot by ΔE in CIELAB. Trivially
  testable with deterministic colour fixtures.
- **`AiPaintResultScreen`** (modify): each row already shows a swatch + slot
  chip; the swatch component swaps from a single-colour circle to a
  `MixedSlotSwatch` when `region.slot >= num_physical`.
- **Print-cost banner** (new, on AiPaintResultScreen): "N regions use mix
  slots. Adds approximately Xh print time." Heuristic uses count × per-mix
  cost estimate from Phase A's dialog.
- **Mix override**: tapping the slot chip on a region opens the
  `SectionedSlotPicker` from Phase A. Same picker; same UX.

## Out of scope (deferred)

- **Auto-create mixes from AI suggestion.** When AI finds a target colour
  that has no good match in physical + existing mixes, v1 of Phase B picks
  the closest available (even if poor). v2 would auto-propose "create new mix
  E1+E3 @ 33%" with a prompt. Defer until users tell us they want it.
- **Multi-mix-per-region** (interleaving multiple mixes on a single AI region).
  Not how Smart Paint works today (one slot per region); ignore.

## Data flow

1. AI produces `List<AiRegion>` with `suggestedColour` hex per region.
2. `MixedFilamentManager` exposes current mix sets; `ColourPaletteResolver`
   computes predicted blends.
3. For each region, resolver picks `bestSlot` (could be physical or virtual).
4. UI renders region tree with mixed-swatch chips where applicable.
5. User can override per-region via the slot picker.
6. On `PaintedMeshWriter.write()`, per-triangle slot bytes carry virtual IDs.
7. Slice: existing Stage 2 pipeline + Phase A's `serialize()` produce the
   correct recipe in `mixed_filament_definitions`.

## Tests

- **JVM:** `ColourPaletteResolverTest` covering empty mix set, mix-better-than-physical, library mix preferred over physical, ΔE tie-break.
- **Compose:** `AiPaintResultScreen_mixSwatch_rendersForVirtualSlot`.
- **Instrumented:** `smartPaint_assignsMixSlotWhenClosest_mixedRegionsHaveCorrectSlotBytes` — load a Smart Paint test fixture, configure a project mix, run AI, assert at least one region's slot byte ≥ 4 in the written 3MF.

## Native rebuild

Not required. Pure Kotlin + Compose.

## Risks

- **Naive blending mispredicts colour distance.** AI may pick suboptimal
  mixes. v1 is acceptable as a "Smart Paint can do more colours now" win;
  Phase C improves prediction.
- **CIELAB distance vs perceptual difference.** Standard tradeoff; CIELAB is
  the right v1 choice.
- **Mix set churn during AI run.** If the user adds/deletes a mix while AI is
  resolving, the result may be stale. Snapshot mix state at AI start; reuse
  for the whole run.

## Acceptance criteria

1. With ≥ 1 project mix slot, Smart Paint can assign that mix slot to at
   least one region of a multi-coloured benchy fixture.
2. `MixedSlotSwatch` renders for any region whose slot is ≥ `num_physical`.
3. Tapping the slot chip opens `SectionedSlotPicker` (from Phase A) and
   reassignment updates the region.
4. Print-cost banner shows when ≥ 1 region uses a mix.
5. No regression in Smart Paint's existing 4-colour behaviour when no mixes
   exist.
