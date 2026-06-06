# Full-Spectrum M3 Phase B — Smart Paint Mix Integration

**Date:** 2026-06-06
**Status:** Reshaped by the 2026-06-06 brainstorm with Kevin. The "Scope decisions" section
directly below is **authoritative** and supersedes any conflicting text in the original
sections further down (notably the AI-driven `ColourPaletteResolver` framing).
**Parent:** [`2026-05-26-full-spectrum-roadmap.md`](2026-05-26-full-spectrum-roadmap.md) — supersedes the §M3a sketch
**Depends on:** [Phase A](2026-06-06-full-spectrum-m3-phase-a-manual-pair-mixer-design.md) must ship first
**Sibling:** [Phase C](2026-06-06-full-spectrum-m3-phase-c-target-colour-picker-design.md)

## Scope decisions — 2026-06-06 brainstorm (AUTHORITATIVE — read before the sections below)

These were decided with Kevin and **override** the original AI-centric framing in the rest of
this document. Where older sections conflict (e.g. an automatic `ColourPaletteResolver` driven
by AI-suggested colours), the decisions here win. A future implementation agent must follow
this section.

### Workflow — full-spectrum is a separate, opt-in, PRE-SLICE workspace

- **Mixes are a pre-slice decision, never a send-time one.** A mix slot prints by alternating
  two filaments layer-by-layer; the engine builds those toolpaths *during slicing* from
  `mixed_filament_definitions`. So "use a mix for this region" must be baked into the model +
  slice config **before** the slice runs.
- **Regular printing is untouched.** The existing flow assigns colours → physical extruders at
  **send/upload time**; it is reliable and stays exactly as-is. We do **not** retrofit mixes
  into it (that would force a re-slice at send time and destabilise the hard-won reliable path).
- **Full-spectrum is an additional workflow on top**, parallel to regular printing and modelled
  on Smart Paint (which already bakes a painted 3MF pre-slice, then hands off to the normal
  flow). It branches off after Load, does all colour/mix assignment pre-slice, then **rejoins
  the shared slice → preview → send path**. Send-time mapping for these jobs only confirms the
  already-fixed physical slots.
- **Surface = evolve Smart Paint** into the full-spectrum workspace. Do **not** build a second
  parallel screen. Mix slots become selectable in its picker. Keep the name "Smart Paint" for
  now (rename later if desired). The AI naming/colour-suggestion is demoted to one *optional*
  input — it is not the headline and is currently unreliable.
- **Entry is EXPLICIT.** The user chooses to open a model in full-spectrum / Smart Paint. No
  auto-detection / auto-offer on import in Phase B (can be added later if wanted).

### What Phase B builds — two cases, one surface

Both cases converge on the same step: *regions → assign each to a physical OR mix slot → bake → slice*.

1. **Manual mix assignment (self-painted / AI-segmented models):** the user paints or segments,
   then assigns any region to a physical or mix slot via the sectioned picker. Mixes are created
   manually with Phase A's `CreateMixSlotDialog`.
2. **Imported coloured models:** the model's embedded per-region colours seed the regions, and
   each region **auto-assigns to the closest colour that ALREADY exists** — a physical filament
   or a mix the user has already created. The user then reassigns / creates mixes by hand.

### The "automatic" boundary — IN vs OUT for Phase B

- **IN (Phase B):** auto-assign each region to the nearest colour in a **fixed** palette =
  {physical filaments} ∪ {mixes the user has already created}. A colour-distance match against
  *known* colours only. A mix's predicted display colour uses naive RGB blending (good enough
  for matching + swatches).
- **OUT (deferred to Phase C; needs M4):** automatically **inventing/creating** mixes to hit
  arbitrary target colours — the reverse search "given a target colour, which two filaments +
  what ratio get closest?". Inaccurate without the M4 colour-prediction library
  (`prusa-fdm-mixer`), so it waits. **Do not build the reverse-search in Phase B.**
- Practical consequence: importing a coloured model with no pre-made mixes falls back to
  closest-physical (today's behaviour) until the user creates the mixes; then regions auto-snap
  onto them. "Import and it builds all the mixes for you" is Phase C.

### Q1 — no-mix behaviour is unchanged (no regression)

When there are zero mix slots, Smart Paint behaves exactly as today (its existing structural
slot assignment). Colour-matching against the wider palette only switches on once at least one
mix exists. The original spec's "always ΔE-resolve, even physical-only" idea is **not** adopted.

### Still open (being brainstormed; will be folded into the design below)

- Slot-picker migration scope: per-region reassignment only, vs also migrating the Paint/Lasso
  active-slot palette row so the user can manually *paint* with a mix.
- Print-cost banner: exact wording / prominence.

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
