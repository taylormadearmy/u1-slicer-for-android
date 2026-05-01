# Phase 2 G-code Preview slot-collision fix

**Date:** 2026-04-27
**Worktree:** `.worktrees/phase2-gcode-preview` (detached HEAD on `1dc18d7`)
**Asset:** `Button-for-S-trousers.3mf` plate 1 (12 file filaments → 4 physical slots)

## User-perceived bug

> "G-code Preview after slice shows 3 colours not 4 (missing red)."

The Prepare 3D preview correctly shows 4 distinct file colours after the
`phase2-mesh-diversity` sibling fix. The G-code Preview that follows the
slice was rendering only three visually distinct colours — slot 0 looked
brown (or near-orange) instead of the user's expected E1 red.

The framing in the prompt was "red collapses to brown". Verified: the
mechanism is that `normalizeGcodePreviewColors`'s canonical-driven branch
picked the **first file filament** assigned to the slot (in colorMapping
list order) and treated its hex colour as the slot's palette entry. With
12 file filaments collapsing onto 4 slots via
`findClosestExtruder` + `redistributeDuplicateSlots`, the
"first by closest-match" filament for slot 0 is whichever the user's red
preset matched best — typically a brownish near-red. The G-code Preview
renderer then painted **all** T0 segments in that brown, so slot 0
visually renders as brown for the user, not red.

The "missing red" framing is shorthand for this collapse: red was the
slot's *intended* preset colour but never reached the renderer because
the file-filament hex won. Two distinct file filaments mapping to slot 0
both render brown, so a user counting visually distinct colours sees 3
where they expected 4.

## Root cause — indexing key mismatch

`MainActivity.kt::normalizeGcodePreviewColors` returns a 4-entry palette
the renderer addresses via `extruderColors[move.extruder]`. The pre-fix
canonical-driven branch was:

```kotlin
for (slot in 0..3) {
    val firstFileIdx = colorMapping.indexOfFirst { it == slot }
    if (firstFileIdx >= 0) {
        normalized[slot] = resolvedFilamentColors[firstFileIdx]
    }
}
return normalized
```

Two problems:

1. **Collision losing slot preset.** When N file filaments collapse onto
   slot s, `firstFileIdx` is the *first-occurrence* file filament for s.
   Its hex (`resolvedFilamentColors[firstFileIdx]`) wins — so the slot's
   loaded preset colour (`extruderColors[s]`) never reaches the
   renderer. For S-Buttons plate 1 with default presets, slot 0's first
   match is a brownish colour.
2. **Wrong indexing key.** `move.extruder` in the parsed G-code is the
   **compact slicer T-index** (0..N-1), NOT the physical slot index.
   With Phase 2.5's `skipSliceTimeRemap = true` (SlicerViewModel.kt
   L2823–2833), the on-disk G-code retains the slicer's compact tool
   indices until upload-time `PrintTimeRemap` rewrites them. The embed
   step's `buildCompactExtruderRemap` reorders file filaments into
   compact slots using
   `compactSlotOrder = colorMapping.distinct().sorted()`. So compact c
   maps to physical slot `compactSlotOrder[c]`, NOT slot c — the two
   coincide only when the user's mapping uses all four slots
   contiguously {0,1,2,3}.

For the contiguous-4 case (Dragon plate 3, S-Buttons after
`redistributeDuplicateSlots`) the indexing happened to work by accident.
For non-contiguous slot picks (user picks E1, E3, E4 → mapping uses
{0,2,3}), `normalized[1]` was left at `extruderColors[1]`'s init value
(slot 1 = E2 preset) but the renderer's compact T1 actually represents
slot 2 (E3) — the historical "GcodeRenderer paints with default-palette
colour for the unmapped slot" sky-blue bug.

## Chosen fix

Drive the canonical-driven palette by `compactSlotOrder` indexed by the
**compact slicer T-index**:

```kotlin
val compactSlotOrder =
    colorMapping.distinct().sorted().filter { it in 0..3 }.take(4)
compactSlotOrder.forEachIndexed { compactIdx, slot ->
    val slotPreset = extruderColors.getOrNull(slot).orEmpty()
    val resolved = if (slotPreset.isNotBlank()) {
        slotPreset
    } else {
        val firstFileIdx = colorMapping.indexOfFirst { it == slot }
        resolvedFilamentColors.getOrNull(firstFileIdx).orEmpty()
    }
    if (resolved.isNotBlank()) normalized[compactIdx] = resolved
}
```

For each compact c the renderer will emit, `normalized[c]` is the
loaded preset colour of the slot that compact c represents. This
matches Phase 2 §3 UX brief's "Same colour" overlap option semantics:
*"both file colours print as whatever is loaded in that slot."* The
user reads slot 0 as "E1 red" in the Prepare row caption ("→ E1 ●red"),
and the G-code Preview now agrees.

The file-filament fallback only fires when the slot preset is blank
(test/debug configurations) — preserves the canonical-Prepare-Preview
agreement contract for STL-from-canonical-list paths where the slot
preset was never populated.

The `useDirectSlots` early-exit moved BEFORE the canonical-driven
branch so B95's expanded-remap pipeline (where the on-disk G-code
already uses physical slot indices) gets direct slot colours.

## Coverage

Helps:

- **S-Buttons plate 1** (per-object 4-extruder, 12 file filaments
  collapsed onto 4 slots) — the reported case. Compact c → slot c
  identity; slot preset wins over file filament hex, so brown no
  longer hides red.
- **Any per-object Bambu file with non-contiguous slot picks** (user
  excludes a slot, e.g. {0, 2, 3}). Pre-fix rendered the gap-slot in
  the wrong colour; now compact c indexes into compactSlotOrder and
  the right slot preset renders.
- **Files where the slot preset disagrees with the file's filament
  hex.** Pre-fix used the file's hex (brown for S-Buttons slot 0);
  post-fix uses the slot preset (the colour the user has loaded).

Does not change:

- **STL / single-colour paths.** `colorMapping=null` short-circuits;
  init-loop palette unchanged.
- **Direct-slot pipeline (B95 expanded remap).** `useDirectSlots=true`
  early-exits before the canonical branch; behaviour identical.
- **SEMM with semmColorPermutation passed but no resolvedFilamentColors.**
  Only the existing test path; production always passes
  resolvedFilamentColors so canonical-branch handles SEMM too with the
  new compactSlotOrder indexing.
- **Layer-tool / Hueforge.** Recolor goes via `recolorByZBands` from
  ModelRenderer — separate code path, unaffected.

## Risk

- **Visual change for files where slot preset ≠ file filament hex.**
  If a user has loaded a preset whose hex differs from the file's
  declared filament colour, the Preview now shows the preset, not the
  file's. This is the documented Phase 2 §3 semantic: "the slot prints
  what's loaded", and in practice these will agree more often than not
  because Prepare's auto-suggest uses colour-distance match.
- **Existing instrumented test
  `B92 Buzz plate 8 Prepare/Preview colour agreement`.**
  Pre-fix that test ran against `normalizeGcodePreviewColors` without
  `resolvedFilamentColors` (forcing branch 3), then asserted physical-slot
  indexed palette. Branch 3's output is unchanged. In production
  `resolvedFilamentColors` is always passed, so the canonical branch
  handles Buzz plate 8 — and with the new indexing, compact c → slot
  `compactSlotOrder[c]` = same slot the user expects. Verified:
  `compactSlotOrder = [0, 3]` for Buzz plate 8's `colorMapping=[0,3]`,
  so compact 0 → slot 0 = red preset, compact 1 → slot 3 = white
  preset. Renderer-emitted T0 paints red, T1 paints white. Matches
  Prepare. (B92 instrumented test exercises the off-canonical path
  for resilience; both paths now produce the same intent.)
- **Filament-fallback when slot preset blank.** New edge case — uses
  `resolvedFilamentColors[firstFileIdx]`. Only triggers when the user
  has somehow ended up with a blank slot preset; should be rare and
  the previous code already had similar fallback behaviour.

## JVM unit tests added

In `app/src/test/java/com/u1/slicer/PreviewColorNormalizationTest.kt`:

1. `normalizeGcodePreviewColors collision case prefers slot preset over
   first-file-filament` — pins the S-Buttons fix.
2. `normalizeGcodePreviewColors non-contiguous slots maps compact c to
   compactSlotOrder slot` — pins the {0,2,3} indexing case.
3. `normalizeGcodePreviewColors Dragon-style 4-distinct-slots renders
   preset colours` — regression guard for the contiguous case that
   coincidentally worked pre-fix.
4. `normalizeGcodePreviewColors falls back to file filament when slot
   preset blank` — pins the test/debug edge case.
5. `normalizeGcodePreviewColors with useDirectSlots returns direct slot
   palette` — pins the B95 short-circuit.

## Result

- Full JVM unit test suite: **986 tests pass.**
- APK built (`assembleRelease`) and copied to
  `G:/My Drive/claude/u1-slicer-phase2-fix-gcode-preview.apk`.
- No native rebuild needed (Kotlin-only change).
