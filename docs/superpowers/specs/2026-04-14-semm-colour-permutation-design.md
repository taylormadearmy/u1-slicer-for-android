# B64/B58: SEMM Colour Mapping Permutation Fix

**Date:** 2026-04-14
**Issues:** GitHub #72 (B64), #60 (B58 — check side-effect improvement)
**Status:** Design approved

## Problem

When a user loads a painted (SEMM) multi-colour 3MF and assigns model colours to
physical extruders on the Prepare screen, the assignment is displayed in the UI but
never applied to the sliced G-code output. The slicer emits T0–T3 based on the 3MF's
original `filament_colour` order, and no post-processing remap occurs.

**Example:** Flarewing Dragon 4-colour SEMM model.
- 3MF filament order: T0=dark blue (body), T1=light blue, T2=black, T3=white
- User assigns: Color 1→E4(red), Color 2→E1(black), Color 3→E3(blue), Color 4→E2(white)
- Expected: body prints with E4 (red filament)
- Actual: body prints with E1 (black filament) — T0 sent unremapped

### Root cause

In `SlicerViewModel.applyMultiColorAssignments()`, the code determines whether G-code
post-processing is needed by checking if `usedSlots` (sorted unique slot indices) equals
the identity `[0,1,2,3]`. When all 4 extruders are used, this check passes regardless
of the **order** of the mapping. A permutation like `[3,0,2,1]` (Color 1→E4, etc.)
has `usedSlots = [0,1,2,3]` which looks like identity, so `toolRemapSlots` is set to
`null` and no G-code remap happens.

## Design

### Approach: Dedicated SEMM colour permutation (separate from toolRemapSlots)

`toolRemapSlots` serves multiple purposes: embed-time profile config, temperature
assignment, wipe tower positioning, G-code preview colouring, and the post-slice tool
remap. Overloading it with a permutation would require auditing and potentially adjusting
every downstream consumer — wide blast radius and regression risk across many file types.

Instead, add a dedicated field that is only consumed in one place: the post-slice
G-code rewrite.

### New field

```kotlin
// SlicerViewModel.kt
private var semmColorPermutation: List<Int>? = null
```

**Semantics:** When non-null, this is a list where index = slicer's T-index,
value = physical extruder slot. E.g. `[3, 0, 2, 1]` means T0→T3, T1→T0, T2→T2, T3→T1.

**When set:** In `applyMultiColorAssignments()`, for normal SEMM models only
(`hasPaintData=true`, not H2C, not layer-tool), when the `colorMapping` is a
non-identity permutation.

**When null (no remap):**
- H2C models (>4 model colours folded to 4 extruders) — their pipeline already
  produces correct physical tool indices
- Non-SEMM models (per-object multi-colour, layer-tool)
- Single-colour models
- Identity permutation `[0,1,2,3]` — no remap needed

**Reset:** Set to `null` on every new file load, same locations as `toolRemapSlots`.

### Post-slice application

In the post-slice G-code rewrite step (after `GcodeToolRemapper.remap()` for
`toolRemapSlots`), apply `semmColorPermutation` if non-null:

```kotlin
// After existing toolRemapSlots remap
val semmPerm = semmColorPermutation
if (semmPerm != null) {
    GcodeToolRemapper.remap(result.gcodePath, semmPerm)
    Log.i("SlicerVM", "Post-processed G-code: SEMM colour permutation $semmPerm")
}
```

The existing `GcodeToolRemapper.remap()` already handles arbitrary index→slot mappings,
including permutations. No changes needed to the remapper itself.

**Composition with toolRemapSlots:** For SEMM models using all 4 slots (the common
case), `toolRemapSlots` will be null (identity) and only `semmColorPermutation` runs.

However, for SEMM models using sparse slots (e.g. only E1+E3, `usedSlots = [0, 2]`),
`toolRemapSlots` is `[0, 2]` (compaction: T0→T0, T1→T2) and `semmColorPermutation`
could also be non-null (permutation within those slots). Running both sequentially
would be wrong — the second remap would operate on already-remapped indices.

**Solution:** At the post-slice remap site, compose both into a single remap when
both are present. The composed remap maps each compact T-index to its final physical
slot in one step:

```kotlin
val slots = toolRemapSlots
val semmPerm = semmColorPermutation
val composedRemap: List<Int>? = when {
    slots != null && semmPerm != null -> {
        // Compose: compact index → physical slot via toolRemap,
        // then physical slot → permuted physical slot via semmPerm.
        // semmPerm is indexed by model colour index (same as compact T-index).
        // Each entry is the target physical slot. So the composed result is
        // simply semmPerm — it already maps compact T-index → physical slot.
        semmPerm
    }
    slots != null -> slots
    semmPerm != null -> semmPerm
    else -> null
}
if (composedRemap != null) {
    GcodeToolRemapper.remap(result.gcodePath, composedRemap)
}
```

This replaces the current `toolRemapSlots`-only remap at the post-slice site.

### Changes to applyMultiColorAssignments()

In the normal SEMM branch (line ~1212), after computing `toolRemapSlots`:

```kotlin
// Existing: toolRemapSlots handles sparse slot compaction
// New: semmColorPermutation handles colour order permutation
semmColorPermutation = if (hasPaintData && !isH2cStyle) {
    val isIdentityOrder = colorMapping == (0 until colorMapping.size).toList()
    if (isIdentityOrder) null else colorMapping
} else null
```

### What does NOT change

- `toolRemapSlots` — unchanged semantics, unchanged consumers
- `buildCompactExtruderRemap()` — still returns null for SEMM (no 3MF XML remap)
- `embedProfile()` — unchanged, still uses `toolRemapSlots` for config
- Temperature assignment — unchanged, still indexed by `usedSlots`
- H2C pipeline — untouched
- Layer-tool pipeline — untouched
- Per-object multi-colour pipeline — untouched
- `GcodeToolRemapper` — no changes needed

### Reset locations

`semmColorPermutation = null` must be added to every location that resets
`toolRemapSlots = null`:

- New file load paths (lines ~643, 764, 928, 1012, 1110, 1154)
- Single-colour extruder selection (line ~1318)
- Model clear/reset (line ~2753 area)

## Testing

### Red-green TDD

**Unit tests** (new, in `SlicerViewModelTest` or standalone):
1. `semmColorPermutation` is null when colorMapping is identity `[0,1,2,3]`
2. `semmColorPermutation` is `[3,0,2,1]` when colorMapping is `[3,0,2,1]`
3. `semmColorPermutation` is null for H2C models (even with non-identity mapping)
4. `semmColorPermutation` is null for non-SEMM models
5. Sparse slots + permutation: colorMapping `[2,0]` (E3+E1) produces composed remap
   T0→2, T1→0 — `semmColorPermutation` subsumes `toolRemapSlots` for SEMM models

**Instrumented test** (new, in `SemmSlicingTest.kt`):
- Load Flarewing Dragon 3MF (4-colour SEMM, 160×169mm)
- Embed profile with a known non-identity colour permutation
- Slice
- Apply `GcodeToolRemapper.remap()` with the permutation
- Grep the G-code: verify T0's filament usage (the body) was remapped to the
  expected physical slot

**Regression guards** (existing tests must still pass):
- All 753 unit tests
- All 163 instrumented tests
- Specifically: `SemmSlicingTest` H2C benchy (>600 tool changes, 4 distinct tools)
- Specifically: `SemmSlicingTest` coloured 3DBenchy tests

### Test asset

Flarewing Dragon 3MF: `G:\My Drive\tes-data\Flarewing-Dragon_100%_4FilamentMulticolor_v1.1.3mf`
Copy to `app/src/androidTest/assets/` for instrumented tests.

## B58 Side-effect Check

After implementing the G-code permutation fix, check whether the G-code preview
colour mismatch (#60 / B58) improves. The G-code preview reads tool indices from the
sliced output, so if those indices are now correct, the preview should show correct
colours too.

The Prepare preview (pre-slice) part of B58 may still need a separate fix since it
colours the native mesh based on paint state parsing, not G-code tool indices. Defer
to a follow-up if needed.

## Follow-up: Cross-pipeline Audit

The pattern found here — "identity check on sorted unique values misses permutation
order" — could exist in other pipelines. After this fix ships, audit:

- H2C pipeline: does the paint-state folding respect colour order?
- Per-object pipeline: does `buildCompactExtruderRemap` handle permutations correctly?
- Layer-tool pipeline: does the pause injector respect colour order?

This is investigative only — no code changes unless issues are found.
