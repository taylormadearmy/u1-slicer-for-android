# Full-Spectrum M3 Phase B — Smart Paint × Mix Slots

**Date:** 2026-06-06 (reshaped by brainstorm same day)
**Status:** Design approved by Kevin 2026-06-06. Ready for implementation plan.
**Parent:** [`2026-05-26-full-spectrum-roadmap.md`](2026-05-26-full-spectrum-roadmap.md) — supersedes the §M3a sketch
**Depends on:** [Phase A](2026-06-06-full-spectrum-m3-phase-a-manual-pair-mixer-design.md) must ship first
**Sibling:** [Phase C](2026-06-06-full-spectrum-m3-phase-c-target-colour-picker-design.md)

> **History note.** An earlier draft of this spec framed Phase B around an automatic
> `ColourPaletteResolver` that matched *AI-suggested* colours. The 2026-06-06 brainstorm
> rejected that framing (the AI suggestions are optional and unreliable). This document has
> been rewritten to the approved design. The "Scope decisions" section is the authoritative
> summary; the sections below elaborate it.

## Scope decisions — 2026-06-06 brainstorm (AUTHORITATIVE)

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
  auto-detection / auto-offer on import in Phase B.

### What Phase B builds — two cases, one surface

Both converge on: *regions → assign each to a physical OR mix slot → bake → slice.*

1. **Manual mix assignment (self-painted / AI-segmented models):** the user paints/segments,
   then assigns any region to a physical or mix slot via the sectioned picker, and can paint or
   lasso directly **with** a mix (brush palette includes mixes). Mixes are created manually with
   Phase A's `CreateMixSlotDialog`.
2. **Imported coloured models:** the model's embedded per-region colours seed the regions, and
   each region **auto-assigns to the closest colour that ALREADY exists** — a physical filament
   or a mix the user has already created. The user then reassigns / creates mixes by hand.

### The "automatic" boundary — IN vs OUT for Phase B

- **IN (Phase B):** auto-assign each region to the nearest colour in a **fixed** palette =
  {physical filaments} ∪ {mixes the user has already created}. A colour-distance match against
  *known* colours only. A mix's predicted display colour uses naive RGB blending.
- **OUT (deferred to Phase C; needs M4):** automatically **inventing/creating** mixes to hit
  arbitrary target colours — the reverse search "given a target colour, which two filaments +
  what ratio get closest?". Inaccurate without M4 (`prusa-fdm-mixer`). **Do not build the
  reverse-search in Phase B.**
- Practical consequence: importing a coloured model with no pre-made mixes falls back to
  closest-physical (today's behaviour) until the user creates the mixes; then regions auto-snap
  onto them.

### No-mix behaviour is unchanged (no regression)

When there are zero mix slots, Smart Paint behaves exactly as today (its existing structural
slot assignment). Colour-matching against the wider palette only switches on once at least one
mix exists.

## Goal

A U1 owner can produce a full-spectrum print entirely in-app: open a model in Smart Paint,
assign any region (painted, segmented, or imported-with-embedded-colours) to a physical filament
**or a mix slot**, see the blend in the preview, and slice — with the regular print workflow
left completely untouched.

## Phase A baseline — what exists vs what is still capped

Verified in `feature/m3-phase-a-mix-slots` @ `77ae127` on 2026-06-06:

**Already done (reuse, do not rebuild):**
- `MixedFilamentRow` + `MixedFilamentManager` (`add/edit/delete/promoteToLibrary/serialize`),
  project + library `StateFlow`s, persistence via `SettingsRepository`
  (keys `PROJECT_MIXES` / `LIBRARY_MIXES`), seeded synchronously at startup.
- `CreateMixSlotDialog` + `MixedSlotSwatch` (two-tone swatch).
- `SectionedSlotPicker` composable (PHYSICAL / THIS PROJECT / LIBRARY) — **currently unused**;
  emits slot id `numPhysical + index`.
- Recipe → engine wiring: `SlicerViewModel` (~`:5105`) sets
  `SliceConfig.mixedFilamentDefinitions = mixedFilamentManager.serialize(numPhysical)`, dumped
  to config at `~:7975`.

**NOT done despite the Phase A spec listing it — this is Phase B's foundation:**
- The paint pipeline is still **hard-capped at 4 slots**:
  - `SegmentationCascade.TARGET_SLOTS = 4` (`:17`), used as the cap in ~15 sites.
  - Region-reassignment guards reject slots ≥ 4: `AiPaintViewModel` `:486`, `:509`, `:527`
    (`if (newSlot !in 0 until TARGET_SLOTS) return`).
  - `PaintedMeshWriter` has four paint codes (`PAINT_COLOR` `:18`) and clamps every triangle to
    `0..3` (`:138`).
  - The preview palette is built as exactly 4 entries: `AiPaintResultScreen` `:131`,
    `AiPaintViewer` `:59`.

## Architecture

```
   Load model ──▶ (user explicitly opens Smart Paint) ──▶ FULL-SPECTRUM WORKSPACE
                                                            │
        regions sourced from:  paint · AI/topology segment · embedded file colours
                                                            │
                         each region → physical OR mix slot (manual; auto-assign for imports)
                                                            │
        ┌───────────────────────────────────────────────────────────────────────┐
        │ FOUNDATION (slots may exceed 4 when mixes exist)                        │
        │  picker index ──▶ region.slot / triangle slot byte ──▶ 3MF paint code   │
        │                 ──▶ engine virtual filament id (num_physical + n)       │
        └───────────────────────────────────────────────────────────────────────┘
                                                            │
            PaintedMeshWriter → ai_paint_<ts>.3mf  +  MixedFilamentManager.serialize()
                                                            │
                          loadModelFromFile(painted 3MF)  → SliceConfig (recipe baked in)
                                                            │
                            slice ─▶ preview (blended) ─▶ send (slots already fixed)
```

**The end-to-end slot-id invariant (the #1 correctness concern).** A region painted with the
*k*-th mix must reference the *same* virtual filament the engine reconstructs from
`mixed_filament_definitions`. The chain must agree:

`SectionedSlotPicker` emits `numPhysical + index` → stored as `AiRegion.slot` / per-triangle
slot byte → `PaintedMeshWriter` encodes that byte as the 3MF paint facet code →
the engine maps paint code *n* to filament id *n* → `MixedFilamentManager.serialize(numPhysical)`
assigns virtual ids in the SAME order (project rows first at `numPhysical+1…`, then library).

The picker's combined-list order, the manager's serialize order, and the painted byte values
must be derived from one shared ordering. Tests assert this invariant directly.

**`numPhysical` must mean one thing everywhere.** The painted slot byte, the picker's
`numPhysical + index`, and the engine's virtual-id base all hinge on `numPhysical`. It must be
the *same* value Phase A already passes to `MixedFilamentManager.serialize(numPhysical)` at
`SlicerViewModel ~:5105` (the engine's physical filament count) — not a separately-derived or
hardcoded `4`. Confirm that convention during F1 and thread the single value through; a mismatch
silently shifts every virtual id.

## Components

### Foundation

- **F1 — Widen the slot cap.** Introduce a dynamic ceiling = `numPhysical + activeMixCount`
  instead of the constant `4`. Update the reassignment guards (`AiPaintViewModel:486/509/527`)
  and the cascade default-slot logic (`SegmentationCascade`) so slots ≥ 4 are accepted **when
  mixes exist**. With zero mixes the effective ceiling is 4 → behaviour identical to today.
  `SegmentationCascade.TARGET_SLOTS` stays the *physical* count; a separate notion carries the
  extended palette size where needed.
- **F2 — `PaintedMeshWriter` encodes slots ≥ 4.** Replace the fixed 4-element `PAINT_COLOR`
  array + `coerceIn(0,3)` with an encoding that maps slot byte *n* → the 3MF paint facet code
  for filament *n*, for *n* up to `numPhysical + mixCount`. Verify the higher-index paint-code
  encoding against the engine's `TriangleSelector` (the existing H2C paint path already uses
  states beyond 4 — see the H2C paint-state-folding work). The painted file must round-trip:
  load it back and the per-triangle filament ids match what was written.
- **F3 — Extend the preview palette.** The 3D viewer + `MeshData.recolor` must accept a palette
  of `numPhysical + mixCount` colours (today exactly 4). Mix entries use a **naive RGB blend**
  of their two components at the mix ratio for display. Touch `AiPaintResultScreen:131`
  (`slotPalette`), `AiPaintViewer:59` (`slotPaletteFloats`), and the recolor path.

### Features

- **C1 — `SectionedSlotPicker` integration.** Use it in two places on the Smart Paint result
  screen: (a) **per-region** assignment (replacing the `HighlightSlotPicker` overlay's slot
  list for assignment), and (b) the **Paint/Lasso brush** active-slot palette (Option 2 — the
  brush can carry a mix). Both surfaces show PHYSICAL / THIS PROJECT / LIBRARY and emit the same
  combined-order slot id. `+ Add` opens `CreateMixSlotDialog`; long-press / pencil edits.
- **C2 — `MixedSlotSwatch` rendering.** Render the two-tone swatch wherever a slot id ≥
  `numPhysical` appears: region rows, brush palette chip, picker chips. (Component already
  exists; just wire it into the new surfaces.)
- **C3 — Closest-colour matcher.** New pure Kotlin function: given a target hex and the fixed
  palette `{physical colours} ∪ {mix predicted colours}`, return the nearest slot id by CIELAB
  ΔE. Deterministic, unit-tested. Used by C4 (and available to future auto-assign callers).
- **C4 — Import case (seed regions from embedded colours).** When the user opens a model that
  already carries per-region colours / per-object extruders, build the Smart Paint regions from
  those embedded colours (a new segmentation source) instead of AI/topology, then run C3 to
  auto-assign each region to the closest existing slot. Reuses the existing per-object /
  per-triangle colour machinery (`objectExtruderMap`, native paint-state accessors) as the
  region source.
- **C5 — Print-cost banner.** On the result screen, when ≥ 1 region uses a mix slot, show an
  honest qualitative banner: **"N regions use mix slots — this adds tool changes and print
  time."** No fabricated hour estimate (the real figure comes from the post-slice time
  estimate, which already reflects the extra tool changes).

### Import unmatched-colour feedback

When the import auto-assign (C4) falls back to closest-physical for colours with no good match
in the existing palette, show a one-line note: **"N colours had no close match — create a mix
to improve them."** Gentle nudge, no auto-action (consistent with the explicit / no-magic
decision). "Close match" threshold is a ΔE cutoff chosen at implementation time.

## Data flow

**Manual assignment**
```
open Smart Paint → paint/segment → regions
  tap region chip → SectionedSlotPicker → pick physical or mix (or + Add a mix)
    region.slot = chosen id (≥4 for a mix); per-triangle bytes updated
  OR Paint/Lasso with a mix-selected brush → triangles painted to that mix id
accept → PaintedMeshWriter writes ai_paint_<ts>.3mf (slot bytes ≥4 encoded)
       → loadModelFromFile(painted 3MF); SliceConfig.mixedFilamentDefinitions = serialize()
slice → preview (blended) → send (physical slots already fixed)
```

**Import**
```
open coloured model in Smart Paint → regions seeded from embedded colours
  C3 auto-assigns each region → closest of {physical ∪ existing mixes}
  unmatched colours → closest physical + "N colours had no close match" note
user reassigns / creates mixes as desired → (same accept → slice path as above)
```

## Testing

Red-green TDD per `CLAUDE.md`. New tests:

**JVM unit**
- `ClosestColourMatcherTest` (C3): empty mixes, mix closer than any physical, library mix
  preferred when closest, ΔE tie-break determinism, hex parsing.
- `NaiveBlendTest` (F3): two-component RGB blend at ratio endpoints + midpoints.
- `SlotIdOrderingTest` (invariant): the picker combined-order, `serialize()` virtual-id order,
  and painted byte values all derive from one ordering for representative project+library sets.
- `PaintedMeshWriterSlotEncodingTest` (F2): slots 0..N encode to distinct paint codes; round
  values ≥4 survive (no `coerceIn(0,3)` truncation).
- Cost-banner + unmatched-note helper logic (counts, thresholds).

**Compose UI**
- `SectionedSlotPicker` in-screen: sections render/hide; tap selects; `+ Add` opens dialog;
  long-press edits; brush palette includes mixes.
- `MixedSlotSwatch` renders for a region whose slot ≥ numPhysical.

**Instrumented (`app/src/androidTest/`)**
- `smartPaint_assignMixSlot_painted3mfCarriesVirtualId`: load fixture, configure a project
  mix, assign a region to it, write the 3MF, assert ≥1 triangle's filament id ≥ 4 and that it
  matches the serialize() virtual id.
- `smartPaint_mixRegion_slicesWithRecipe`: the painted 3MF + recipe slices and the G-code
  config dump contains the mix definition (mirrors Phase A's Stage-2 grep).
- `importColouredModel_autoAssignsToExistingPalette`: open a multi-colour model with one
  pre-made matching mix; assert the matching region lands on the mix id and unmatched regions
  fall back to closest physical.
- Regression: with zero mixes, Smart Paint slot assignment + painted 3MF are byte-identical to
  pre-Phase-B behaviour (no-regression guard).

## Native rebuild

**Not required.** The engine (v2.3.3, mix-filament capable) and the SAPIL marshalling are
untouched; Phase A already wired `mixed_filament_definitions`. F2's 3MF paint encoding is in
Kotlin (`PaintedMeshWriter`). If F2 verification reveals the engine cannot decode paint codes
beyond a certain index headlessly, that becomes a flagged risk + a separate decision — not an
assumed rebuild.

## Risks

- **Slot-id invariant drift** — the single biggest risk. Mitigated by `SlotIdOrderingTest` +
  the round-trip instrumented test, and by deriving picker/serialize/paint ordering from one
  shared source.
- **3MF paint-code encoding for slots ≥ 4** — must match the engine's expectation. De-risk
  early (F2 first, with a load-back round-trip assertion) before building features on top.
- **Naive blend mispredicts colour** — accepted for v1 display + matching; Phase C/M4 improves.
- **Import region-seeding fidelity** — embedded-colour models vary (Bambu paint, per-object
  extruder, H2C). Start with the common per-object/per-triangle colour sources already handled
  elsewhere; document any model class not yet covered rather than silently mis-seeding.
- **Mix-set churn during a session** — snapshot the active mix set when entering the workspace;
  reuse it for the session so ids stay stable.

## Acceptance criteria

1. With ≥ 1 mix slot, a region of a multi-coloured fixture can be assigned a mix slot and the
   written 3MF carries a per-triangle filament id ≥ 4 that matches the engine recipe's virtual
   id; it slices and the G-code config dump contains the mix definition.
2. The Paint/Lasso brush can paint/lasso directly with a mix slot (Option 2).
3. `MixedSlotSwatch` renders for any region/brush/picker entry whose slot ≥ numPhysical.
4. Opening a coloured model seeds regions from its embedded colours and auto-assigns each to the
   closest existing physical-or-mix colour; unmatched colours fall back to closest physical with
   the "N colours had no close match" note.
5. The print-cost banner appears when ≥ 1 region uses a mix.
6. With zero mixes, Smart Paint behaviour is unchanged (no-regression guard passes).
7. Regular (non-full-spectrum) printing is untouched; full Phase A test suite + new tests pass.
8. Renders cleanly in dark mode (the only theme U1 Slicer ships).
