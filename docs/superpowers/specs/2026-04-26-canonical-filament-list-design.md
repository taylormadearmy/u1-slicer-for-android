# Canonical filament list refactor — Phase 2 design (skeleton)

**Status:** design skeleton, not yet executable. Phase 2 follow-up to the
v1.7.0 Bambu refactor (`refactor/bambu-via-native-loader`).

**Author:** captured 2026-04-26 from a session-end conversation. The
v1.7.0 fixes (H2C, F1 calendar, Buttons race, calicube, hanging, Buzz
cold load) are stable; this doc plans the structural follow-up that
should make whole bug *classes* impossible-by-construction.

**Goal in one sentence:** treat the file's `filament_colour` array as the
single source of truth for "what filaments exist in this file", and let
the user's only colour-related decision be a 1↔1 mapping from
file-filament-index to a physical extruder slot.

---

## 1. Why

### What we have today

The slicer already emits G-code referencing filament indices (`T0..T(N-1)`),
and we already have a `colorMapping` field that maps file-index → physical
slot. So the bones of the desktop-Orca model are present. What we've
layered on top is the noisy bit:

- `findClosestExtruder` auto-suggests a mapping from the user's preset
  colours. Convenience, but the source of the F1 / Buttons regression
  class — when the user's presets don't span the file's palette, the
  auto-mapping collapses to too few slots.
- Four overlapping ways to recover "what extruders does this plate use"
  for embed-prep:
  - `objectPartExtruders` (compound objects via `<part>` extruder metadata)
  - `compoundPartParents` (mapping parts back to their parent for plate
    membership)
  - `paintExtruderStates` (decoded from `paint_color` triangle attributes
    via `PaintColorDecoder`)
  - `objectExtruderMap` (object-level default extruder)
- `computeEmbedTargetCount` heuristics resolving disagreements between
  those four (B48 H2C, B76 hybrid-single-dedup, B95 high-index source).
- Post-slice `computeExpandedGcodeRemap` patching up the result so
  every emitted slicer-T lands on the user's chosen slot.

These layers exist because we couldn't trust the file's filament list
across all the variations (multi-plate, compound parts, paint state
encodings, layer-tool files, AMS2 folding). We synthesise an answer
each time, and the synthesis paths can disagree.

### What this refactor changes

Make the file's filament list canonical. Normalise at load time into a
unified shape. Make `colorMapping` the only user-facing colour decision.
Drop the synthesis layer.

The four pre-slice bugs the v1.7.0 fixes addressed (H2C, F1, Buttons
race, ambient under-counts) all share the same root: the embed pipeline
disagreed with the slicer pipeline about how many filaments the file
has. After this refactor, that disagreement is structurally impossible
because both pipelines read the same canonical list.

### Non-goals

- Removing user convenience. `findClosestExtruder` stays as the **initial
  suggestion** the user can accept or edit; it just no longer feeds the
  slicing pipeline directly.
- Changing the four-physical-extruder constraint of Snapmaker U1. The
  mapping handles N→4; that part of the architecture is unchanged.
- Native rewrites. The slicer already speaks filament indices; we don't
  need to teach it anything new.

---

## 2. Phasing

### Phase 2.0 — UX exploration (gate; do this FIRST, before any code)

This is the part that's easy to skip and easy to regret. The user
interaction model for "map file filaments to physical slots" is the
load-bearing UX of the entire refactor — get it wrong and we'll either
break casual flows (load → slice with no clicks) or build a UI no one
wants to touch.

Questions for this phase to answer:

1. **Initial state**: when a multi-colour file loads, what does the
   user see? Auto-suggested mapping with a clear "edit" affordance?
   An empty mapping that demands attention? Something else entirely?
2. **Edit affordance**: where does the mapping UI live? Inline on the
   prepare screen (today's pattern, tucked under the model)? A dialog?
   A dedicated step in a wizard-like flow? Different on first load vs
   subsequent reloads?
3. **Persistence**: file-level (this file always maps colour 3 to E2),
   session-level (forget on app close), per-printer-preset (E1=red so
   any "red-ish" file colour goes there)? Mix?
4. **Sparse-preset flow**: today's biggest UX failure mode (user has
   only 2 presets configured, file has 5 colours). What does the right
   thing look like? Force preset configuration first? Distribute
   round-robin with a warning? Distribute round-robin silently?
5. **Validation surface**: what does the user see if their mapping has
   a colour with no slot, or two file colours mapped to the same slot
   on a same-layer print? Is the mapping permitted but flagged, or
   blocked?
6. **First-time user flow**: someone with no presets configured opens
   their first multi-colour file. What's the on-ramp?
7. **Single-colour file flow**: do we still surface the mapping UI?
   How do single-colour files compare to one-of-N file colour usage?
8. **STL / non-Bambu flow**: STL has no filament list. Do we show a
   degenerate single-row mapping, or skip the UI?
9. **Layer-tool / Hueforge files**: these use a different mechanism
   (custom_gcode_per_layer.xml). Does the user see them as "1 filament,
   N tool changes" or "N filaments treated specially"?

**Output of Phase 2.0:** a Figma / sketch / wireframe set covering each
question, plus a short brief naming the chosen interaction. Reviewed by
the user before any code starts.

**Why this gates everything:** the data model below is dictated by what
the UX needs to support. Designing the data model first and then
fitting UX onto it is how we got the current synthesis layer.

### Phase 2.1 — Canonical data model + load-time normalize

Build the load-time pipeline that produces a `CanonicalFilamentList` from
any supported input file. Single integration point per file format
(Bambu 3MF, PrusaSlicer 3MF, STL, generic 3MF). Output structure to be
decided in 2.0 review but probably:

```
data class CanonicalFilamentList(
    val filaments: List<FilamentEntry>,  // file-index 0..N-1
    val plates: List<PlateRef>,           // which filaments each plate uses
    val paintStateMap: Map<Int, Int>      // raw state -> filament-index
                                          //   (handles B95 high-index, AMS2 fold)
)

data class FilamentEntry(
    val fileIndex: Int,                   // 0-based, matches slicer T-index
    val color: String,                    // hex
    val materialType: String?,            // "PLA", "PETG", null if unknown
    val source: FilamentSource            // FILE_COLOUR / PAINT_DERIVED / DEFAULT
)
```

The point of `paintStateMap` is to absorb the B95 / AMS2 / H2C
weirdness at one place: the file says paint_color="8C", we decode to
state 11, the map says state 11 → filament index 3. Everywhere else
just uses `fileIndex`.

### Phase 2.2 — `colorMapping` becomes the only contract

`colorMapping: List<Int>` of size `filaments.size`, where
`colorMapping[fileIndex] = physicalSlot in 0..3`. Drop:

- `objectPartExtruders`, `compoundPartParents`, `paintExtruderStates`
  on `ThreeMfInfo` and `ThreeMfPlate`. They've been `@Deprecated`
  through this branch already; finish the job.
- `buildSelectedPlateInfo` and the entire embed-prep synthesis path
  inside `SlicerViewModel.selectPlate`.
- The B47 colorMapping init block in `loadNativeModel` becomes:
  ```
  _colorMapping.value = (0 until filaments.size).map { i ->
      currentMapping[i] ?: findClosestExtruder(filaments[i].color)?.index ?: 0
  }
  ```
  Initial suggestion only; never touched again unless the user edits it.

### Phase 2.3 — Embed pipeline simplification

`computeEmbedTargetCount` collapses to:

```
fun computeEmbedTargetCount(filaments: List<FilamentEntry>) = filaments.size
```

`computeExpandedGcodeRemap` collapses to:

```
fun computeRemap(colorMapping: List<Int>) = colorMapping  // verbatim
```

`embedProfile` writes the file's filament_colour list verbatim, no
bumps for B95, no special H2C path. The slicer emits T0..T(N-1) and
the post-process maps to physical slots via `colorMapping`.

### Phase 2.4 — Migration + compat

- Settings backup format: bumps a version. Old backups load with the
  legacy synthesis path producing a one-shot `CanonicalFilamentList`,
  then save in the new format on first slice.
- Any in-flight slice jobs at upgrade time are abandoned (existing
  upgrade-detector behaviour); no special migration code needed.
- `BambuParserDifferentialTest` baseline at 0 — the load-time
  normalize MUST keep it there. Add per-file unit tests for the
  normalize step.

### Phase 2.5 — Tier A regression sweep + documentation

- Re-run all six PM-bug regression tests. Specifically construct
  failing inputs for each of the four pre-slice bug classes and verify
  the new architecture short-circuits them at the type level (i.e. the
  shape that produced the bug isn't reachable).
- Update `CLAUDE.md` Architecture section.
- Retire `BACKLOG.md` entries for any bug class made impossible by the
  refactor.

---

## 3. Code surface inventory

Files that change substantially (Phase 2.1-2.3):

- `SlicerViewModel.kt`:
  - `selectPlate`, `loadNativeModel`, `embedProfile`, B47 init,
    `buildThreeMfInfoFromNative`, `buildSelectedPlateInfo`,
    `computeEmbedTargetCount`, `computeExpandedGcodeRemap`,
    `computeSemmColorPermutation`, `composeSemmRemap`.
- `bambu/ThreeMfParser.kt`:
  - `parse`, `parseForPlateSelection`, `computeVisualColorCountByPlate`,
    `scanComponentForPaintInfo`. The B95 PaintColorDecoder integration
    moves into the load-time normalize step.
- `bambu/ThreeMfInfo.kt`:
  - Drop `objectPartExtruders`, `compoundPartParents`,
    `paintExtruderStates`, `objectExtruderMap` (all `@Deprecated` today).
  - Add `filamentList: CanonicalFilamentList`.
- `bambu/ProfileEmbedder.kt`:
  - `buildConfig`, `embed` simplify; no `extruderRemap` parameter
    needed because file emits its own filament list verbatim.
- `bambu/NativePlateState.kt`:
  - Stays as a runtime read of g_model state; the post-load enrichment
    helper (`SlicerViewModel.buildThreeMfInfoFromNative` /
    `androidTest/.../PlateStateEnrichment.kt`) becomes much smaller.
- `gcode/GcodeToolRemapper.kt`:
  - Single-path: rewrite `T<n>` to `T<colorMapping[n]>`. Drop the
    semmColorPermutation / toolRemapSlots branches.

UI:

- `ui/MultiColorDialog.kt` and the inline mapping UI on the prepare
  screen — Phase 2.0 will reshape these. `ensureMultiSlotMapping` and
  `redistributeDuplicateSlots` likely retire.

Tests:

- `MultiColorMappingTest`, `MultiColorMappingMoreThan4Test`,
  `ExpandedGcodeRemapTest`, `SemmColorPermutationTest`,
  `SlicerColorOrderTest`, `SemmSlicingTest`, large parts of
  `BambuPipelineIntegrationTest` (B23, B82, B92), `MergeThreeMfInfoTest`
  — many of these test the synthesis paths the refactor removes.
  Replacement tests assert on the canonical list shape.

Native (no changes expected):

- The slicer already emits T-indices for filaments. We're not asking
  it to do anything new.

---

## 4. Open questions for the design author

- **STL files**: do we even show the colour mapping UI? Probably no
  (single-colour by definition). UX call.
- **Layer-tool files (Hueforge)**: the file's filament_colour is often
  size 1, but `custom_gcode_per_layer.xml` carries the per-layer colour
  changes. Is layer-tool a separate `FilamentSource`, or a synthetic
  expansion of the filament list at load time?
- **Per-plate filament narrowing**: the v1.7.0 fix keeps `detectedColors`
  file-wide. In the new model, do we still surface per-plate "active
  filaments" anywhere, or always show the full file list?
- **Compound objects**: Dragon Scale's per-part extruder metadata
  becomes a load-time normalize concern. Does it ever conflict with
  the file's filament_colour, and if so, who wins?
- **`hasMultiExtruderAssignments`**: this flag drives ProfileEmbedder's
  preserve-vs-rebuild path. After the refactor, is the canonical
  filament list always sufficient, or do we still need the flag?
- **Print-time mapping**: do we let the user change the mapping AFTER
  slicing (between slice and send-to-printer), or is mapping fully
  resolved at slice time? Affects whether `colorMapping` is a slice
  input or a print input.

---

## 5. Risk + size

- **Effort**: 2-3 weeks of focused work after Phase 2.0 design lands.
  Most of the surface is Kotlin; native untouched.
- **Risk**: high if rushed, low if Phase 2.0 produces a clear UX
  brief first. The synthesis layer being deleted is what produces
  the simplification; doing the deletion without confidence in what
  replaces it is what burns time.
- **Test coverage**: ~30 existing tests reshape. Net test count
  probably similar; assertions get stronger because they test
  invariants instead of synthesis paths.

---

## 6. Decision log

- **2026-04-26**: design captured. Phase 2.0 (UX exploration) is the
  next concrete step. No code changes scheduled until that phase
  produces a brief.
