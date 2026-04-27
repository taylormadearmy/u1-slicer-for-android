# 2026-04-27 — Instrumented test architectural fix plan

After Phase 2 §4 Steps 1–10 landed and the four user-reported bugs (Calicube /
Buzz plate 9 / flippy plate 4 / S-Buttons) were fixed, six instrumented tests
remained red on `feature/phase2-canonical-filaments` @ `cfd2643`. The user's
direction was explicit:

> not happy with allowing 'pre-existing' through. The whole point of this
> refactor is to remove issues like this. we need everything to be green. But
> would like it thought through at the high level rather than fixing things on
> a file by file basis.

This document captures the unified architectural fix.

## Phase 2 G-code / palette contract — first principles

The slicer (native OrcaSlicer) emits canonical T-indices in **fileIndex space**
— the same 0..N-1 index referenced by `filament_colour`,
`paint_color` / `mmu_segmentation` triangle attributes, and the user's
`CanonicalFilamentList`. Slot translation only happens at print-time via
`PrintTimeRemap.applyPrintTimeRemap` when the file's filaments are mapped onto
the printer's physical 4 slots.

Implications for plate state:

- `detectedColors` for a plate = canonical filament colours filtered to the
  fileIndices actually used on this plate.
- `usedExtruderIndices` for a plate = canonical fileIndices used (1-based for
  legacy compatibility with the embed pipeline).
- No slice-time remap helpers should compute or transform indices — those
  belonged to the pre-Phase-2 slot-aware-slicer model.

## Test failure audit

| # | Test | Category | Root cause |
|---|------|----------|------------|
| 1 | `b83_paintedFlippy_selectPlate5AfterPlate4_hasTwoChips` | PRODUCTION-STALE | `buildThreeMfInfoFromNative` runs heuristic plate-narrowing instead of canonical lookup |
| 2 | `slicerColorOrder` / `semmColorPermutation` flow assertions | TEST-STALE | Asserts retired StateFlows; canonical contract has no slice-time permutation |
| 3 | `buzzLightyear_plateSwitch_preparePreviewReflectsCurrentPlatePalette` | PRODUCTION-STALE | Same as #1 — production-side narrowing collapses plate 9 high-index paint state |
| 4 | `extruder=1 colorMapping post-remap` | TEST-STALE | Asserts compact 2-entry mapping shape; canonical contract preserves full-length mapping |
| 5 | `h2cBenchy green index 5 fixture-arithmetic` | TEST-BUG | Hardcoded `idx == 5` constant doesn't track canonical |
| 6 | B95 plate 9 paint state slicing | TEST-STALE / TEST-BUG | Mixes deprecated `extractPlate`/`restructurePlateFile` with canonical asserts |

Tests #1 + #3 ladder up to ONE production gap. Tests #2 + #4 are stale.
Tests #5 + #6 are test-side bugs.

## Unified fix — Groups A / B / C

### Group A — production: canonical-driven plate narrowing

Replace the heuristic narrowing in `SlicerViewModel.buildThreeMfInfoFromNative`
(and the shadow legacy `mergeThreeMfInfoForPlate` kept for testability) with a
canonical lookup:

1. Derive `usedFileIndices: Set<Int>` (0-based) for the plate from the union of:
   - `nativeState.usedExtruders` minus 1 (1-based → fileIndex)
   - `sourcePlate.objectIds` mapped through `fileInfo.objectExtruderMap` minus 1
   - `sourcePlate.filamentIndices` minus 1
   - `sourcePlate.layerToolExtruders` minus 1 (only when `hasLayerToolChanges`)
2. Look up colours via `canonicalFilamentList.filaments[fileIndex].color`.
3. Drop the layer-tool-only / Hueforge / paint-data branching that exists today
   to compensate for the heuristic — canonical lookup is invariant under those
   shapes.

The canonical list already absorbs:
- B95 high-index paint states (folded in `paintStateMap`)
- AMS2 fold (5..8 → 1..4)
- Compound object per-part palettes (`objectPartExtruders` walked at load time)
- Layer-tool synthetic entries (`FilamentSource.LAYER_TOOL`)

So Group A removes ~150 lines of compensating heuristics from
`buildThreeMfInfoFromNative` and replaces them with ~30 lines of canonical
lookup. This automatically fixes tests #1 and #3.

### Group B — production: delete legacy slice-time remap

The Phase 2 G-code contract (slicer emits fileIndex-space T-codes;
`applyPrintTimeRemap` is the only translator) makes the following dead:

- `composeSemmRemap` / `computeSemmColorPermutation`
- `computeExpandedGcodeRemap` / `computeSlicerColorOrder`
- `buildCompactExtruderRemap` / `computeEmbedTargetCount`
- `_slicerColorOrder` / `_semmColorPermutationFlow` / `_gcodeUsesPhysicalSlots`
  StateFlows
- `skipSliceTimeRemap` branches throughout
- `GcodeToolRemapper.remap(path, list)` — `remapLine` stays for `PrintTimeRemap`

Deleting these removes the surface area that tests #2 and #4 are pinning to.

### Group C — tests: align with canonical contract

After A + B land:

- **Test #2** — drop assertions on retired StateFlows; keep set-equality on
  `detectedColors`.
- **Test #4** — accept full-length `colorMapping` (canonical preserves all
  entries; the old contract collapsed to compact slicer T-indices). Read the
  `.remapped` sibling file for orphan-extruder check (PrintTimeRemap output).
- **Test #5** — find the green `fileIndex` via `canonicalFilamentList` rather
  than hardcoded `idx == 5`.
- **Test #6** — rewrite to drive the SlicerViewModel pipeline + `selectPlate`
  rather than the deprecated `extractPlate` + `restructurePlateFile` chain;
  assert against the canonical G-code path.

## Order of operations

1. **Land Group A first.** Don't touch tests. Expectation: tests #1 and #3 go
   green automatically. If anything else surfaces, it's a smaller, cleaner
   production gap.
2. **Land Group B.** Only after A is green. Tests #2 + #4 will go red on the
   missing symbols — that's the signal they're stale.
3. **Land Group C.** Update the four test files to the canonical contract.
4. **Confidence check.** Unit + smoke-10 + E2E smoke-7.
5. **Full instrumented sweep.** All 220 tests green on Pixel 8a with
   Orchestrator.
6. **Code review subagent.** Independent pass on the diff.

## Why this beats file-by-file fixes

A file-by-file approach would:

- Patch each test's assertion (treating the symptom).
- Leave the legacy slice-time remap surface in place "for compatibility".
- Keep `buildThreeMfInfoFromNative`'s 150-line heuristic — every future
  paint/layer-tool/compound-object edge case adds another conditional.

The canonical-driven approach instead:

- Removes the heuristic at its source.
- Deletes the contract surface that pins the stale tests.
- Lets the canonical filament list be the single source of truth — what
  Phase 2 was always supposed to deliver.

The user's no-tech-debt direction (`docs/superpowers/reviews/2026-04-26-phase2-architecture-review.md`
§0) is the same direction this plan follows.
