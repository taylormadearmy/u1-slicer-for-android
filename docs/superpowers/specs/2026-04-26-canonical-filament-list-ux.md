# Canonical filament list — Phase 2.0 UX brief

**Status:** decision-log ready. The 9 open UX questions in the Phase 2 design
skeleton have been resolved through interactive review with the user
(2026-04-26). This doc captures the resulting brief.

**Companion to:**
[`2026-04-26-canonical-filament-list-design.md`](2026-04-26-canonical-filament-list-design.md)
(the Phase 2 skeleton — read for *why*; this doc is *what we're building*).

**Branch:** `feature/phase2-canonical-filaments`. No production code, tests, or
Compose previews change in this commit.

---

## §0 TL;DR

The big shift from the skeleton's working assumption: **slot mapping moves
out of slice time and into print time**, mirroring Snapmaker desktop's
"Print Preprocessing" dialog. The slicer emits G-code that uses the file's
own filament indices verbatim (`T0..T(N-1)`); the user picks "filament 1 →
physical slot X" at the moment they Send to printer, not when they slice.

This is a structurally bigger reshape than the skeleton anticipated —
**most of the Kotlin synthesis layer goes away** — because the bug class
(B82, B86, B92, B95) is silent slice-time auto-mapping, and removing the
slice-time mapping makes the whole class impossible by construction.

### Headline decisions

| Decision | What |
|---|---|
| Slice contract | File's filaments emitted verbatim. No colour remapping at slice time. |
| User mapping point | At Send to Printer, via a dialog called **"Filament mapping"**. |
| Prepare screen | Editable filament list (matches desktop OrcaSlicer / Bambu Studio Filament panel). Defaults from the file (3MF) or printer-loaded data (STL via `syncFilaments`). Each row: colour swatch + material-type dropdown. **Overrides drive slicing** — the slicer uses the user's chosen material/colour, not the file's defaults. Overrides persist per file. **No slot picker on Prepare** — slots are picked at Send time. |
| Send button | Two-stage label: *"Map & Print"* / *"Map & Upload"* (telegraphs the upcoming dialog). |
| Dialog scope | Appears for every file. 1 row for STL/single-colour, N rows for multi-colour. |
| Auto-suggest source | User's saved extruder presets, via colour-distance match. Same logic as today's `findClosestExtruder`, just at the new moment. |
| Persistence | Per-file mapping remembered across reopens. Always-visible "Auto-suggest" button to reset. |
| Unused filaments | Hidden from the dialog (matches today's `detectedColors` behaviour). |
| First-time user | Seeded defaults (red/green/blue/white) + banner: *"Set your extruder colours in Settings to get better suggestions."* |
| Overlap mapping | Per-overlap inline picker: Pause / Same colour / Reslice merged (beta). Uses parsed G-code for pause-count estimate. |
| Printer-side sync | Already implemented (`PrinterViewModel.syncFilaments`). Phase 3 just adds an auto-prompt at the right moment. |

### Headline simplification

The skeleton imagined the canonical-list refactor as a Kotlin-side rework
that kept slice-time mapping. With the user-facing decision to push mapping
to print time, the simplifications go further than the skeleton's section 2
described:

- **Drop slice-time `colorMapping` entirely.** The slicer reads the file's
  filament list and emits `T0..T(N-1)` against it. Done.
- **Drop `computeEmbedTargetCount` heuristics.** Embed count = file's
  filament count; nothing more clever.
- **Drop `computeExpandedGcodeRemap`, `composeSemmRemap`,
  `computeSemmColorPermutation`, `slicerColorOrder`.** None needed —
  G-code post-processing happens at upload time in a single small
  `applyPrintTimeRemap(gcodePath, mapping)` step.
- **Collapse the four-overlapping-source synthesis** (`objectPartExtruders`,
  `compoundPartParents`, `paintExtruderStates`, `objectExtruderMap`) into
  the canonical list. Their *role as separate synthesis sources* goes away;
  their underlying data moves into per-entry metadata on `FilamentEntry`,
  where the load-time normalize and the future merge feature both consume
  it. (The merge feature is pre-slice; it needs the per-object/per-volume
  extruder data to rewrite the file. What it does NOT need is post-slice
  T-index disambiguation — see §4 Step 2 sidebar.)

What's left at slice time is straightforward: parse the file → produce a
canonical filament list → embed it → slice → emit G-code. The print-time
remap is the only place colour-to-slot logic lives, and it's a 5-line
function: `gcode.lines().map { line -> if (line is "T<n>") "T${mapping[n]}" else line }`.

---

## §1 End-to-end flow

```
┌─────────────────────────────────────────────────────────────────────┐
│ 1. Load file (STL, 3MF, layer-tool, SEMM)                           │
│      ↓                                                               │
│ 2. Load-time normalize → CanonicalFilamentList(filaments=[…])        │
│      Hides unused filaments. Used: 1..N entries.                     │
│      ↓                                                               │
│ 3. Prepare screen                                                    │
│      Shows read-only chip strip:                                     │
│        ●red(1) → E2  ●green(2) → E1  ●blue(3) → E3  …               │
│      Caption: "Final slot mapping on Send →"                         │
│      Auto-suggest derived from extruder presets via findClosest.     │
│      ↓ user adjusts placement, scale, settings                       │
│      ↓ Slice button                                                  │
│ 4. Slice                                                             │
│      Embed file's filament list verbatim into project_settings.config│
│      Slicer emits T0..T(N-1) referencing those file indices          │
│      G-code is canonical (file-relative), not user-mapped            │
│      ↓                                                               │
│ 5. Preview (G-code) — exactly as today, painted with auto-suggested  │
│      slot colours                                                    │
│      ↓ Send button: "Send → Assign Slots → Print"                    │
│ 6. Filament mapping dialog                                           │
│      One row per used filament. Each row:                            │
│        - File-colour swatch + label                                  │
│        - Slot picker (E1/E2/E3/E4) with current preset colour shown  │
│        - Auto-suggest defaults from extruder presets                 │
│        - Per-file remembered mapping if available                    │
│      Always-visible "Auto-suggest" reset button                       │
│      Where overlap is chosen, inline picker appears for that pair    │
│      [ Cancel ]  [ Send to Printer ]                                 │
│      ↓                                                               │
│ 7. applyPrintTimeRemap(gcodePath, mapping)                           │
│      Single small post-process: T<n> → T<mapping[n]>                 │
│      Writes to a transient remapped.gcode                            │
│      ↓                                                               │
│ 8. Upload to printer (existing PrinterViewModel.sendAndPrint path)   │
└─────────────────────────────────────────────────────────────────────┘
```

The shape: **the slicer never sees the user's slot choice.** That choice
exists only in `colorMapping`, which gets applied as a G-code post-process
right before upload. Slice cache is canonical; re-mapping is free (it's a
text rewrite of T-indices, no re-slice required).

---

## §2 Wireframes — touch surfaces

### S1 — Prepare screen, multi-colour file (5 colours used)

```
┌────────────────────────────────────────────┐
│  [3D model preview]                        │
└────────────────────────────────────────────┘
 ╭─ Filaments (5) ──────────────────────╮
 │  ●red(1) → E2 ●red                   │
 │  ●green(2) → E1 ●green               │
 │  ●blue(3) → E3 ●blue                 │
 │  ●yellow(4) → E3 ●blue   ⚠           │
 │  ●black(5) → E4 ●white               │
 │                                       │
 │  Final slot mapping on Send →        │
 ╰───────────────────────────────────────╯
 ╭─ Scale, Copies & Rotation ───────────╮
 │  [today's controls]                   │
 ╰───────────────────────────────────────╯
 ╭─ Settings ───────────────────────────╮
 │  [today's ConfigCard]                 │
 ╰───────────────────────────────────────╯

[ Slice ]  ← sticky button at top
```

- Read-only chip strip. No interaction, no slot picker.
- Each row: `●file-colour(N)` + auto-suggested slot + slot's preset colour.
- The `⚠` chip on row 4 is not strictly necessary at this stage (the user
  hasn't committed yet) — it's the same chip pattern shown on Send. Optional;
  tighten in implementation review.
- Caption *"Final slot mapping on Send →"* tells the user the picker is
  coming.

### S2 — Prepare screen, single-colour file or STL (1 filament)

```
 ╭─ Filament ───────────────────────────╮
 │  ●red(1) → E2 ●red                   │
 │  Final slot mapping on Send →        │
 ╰───────────────────────────────────────╯
```

Same shape as multi-colour, just one row. STLs synthesise a 1-row entry
keyed to the user's currently-selected extruder preset (today's
`ExtruderPickerRow` retires; the read-only chip strip replaces it).

### S3 — Send button (bottom of Preview)

Two-stage hint, vertical stack inside the button:

```
┌───────────────────────────────────────┐
│       Send                            │
│       Assign Slots → Print            │
└───────────────────────────────────────┘
```

Or single line if vertical space is tight: *"Send → Assign Slots → Print"*.
The point: the user can see "Send" doesn't go straight to upload.

### S4 — Filament mapping dialog (multi-colour, no overlap)

```
┌────────────────────────────────────────────┐
│ Filament mapping                       ✕   │
├────────────────────────────────────────────┤
│  File filament      Physical slot          │
│                                            │
│  ●red(1)            [ E2 ●red       ▾ ]    │
│  ●green(2)          [ E1 ●green     ▾ ]    │
│  ●blue(3)           [ E3 ●blue      ▾ ]    │
│  ●yellow(4)         [ E4 ●white     ▾ ]    │
│  ●black(5)          [ E4 ●white     ▾ ]    │ ⚠
│                                            │
│  ⚠ E4 chosen for filaments 4 and 5.        │
│    What should happen?                     │
│      ◯ Pause between swaps (≈ 8 pauses)    │
│      ◯ Print both in the same colour       │
│      ◯ Reslice with these merged (beta)    │
│                                            │
│  [ ↻ Auto-suggest ]                        │
│                                            │
│  [ Cancel ]              [ Send to Printer ] │
└────────────────────────────────────────────┘
```

- Each row: file-colour swatch + filament index + slot picker.
- Slot picker shows the slot's current preset colour (so the user can
  visually match without reading the label).
- Auto-suggest reset button always present.
- When two rows pick the same slot, the inline 3-option picker appears
  attached to that pair. Pause-count is computed from the parsed G-code
  (we already have this number — it's `gcode.tool_changes_for(t1, t2)`).

### S5 — Filament mapping dialog (single-colour)

```
┌────────────────────────────────────────────┐
│ Filament mapping                       ✕   │
├────────────────────────────────────────────┤
│  File filament      Physical slot          │
│                                            │
│  ●red(1)            [ E2 ●red       ▾ ]    │
│                                            │
│  [ ↻ Auto-suggest ]                        │
│                                            │
│  [ Cancel ]              [ Send to Printer ] │
└────────────────────────────────────────────┘
```

Same dialog, one row. STL files render with a generic "STL geometry" label
in place of the file-colour swatch (or use the user's selected preset
colour as the swatch — implementation detail, see §3 risks).

### S6 — Filament mapping dialog (first-time user, no presets)

```
┌────────────────────────────────────────────┐
│ Filament mapping                       ✕   │
├────────────────────────────────────────────┤
│  ⓘ  Set your extruder colours in Settings  │
│      to get better suggestions.            │
│                                            │
│  ●red(1)            [ E1 ●red       ▾ ]    │
│  ●green(2)          [ E2 ●green     ▾ ]    │
│  ●blue(3)           [ E3 ●blue      ▾ ]    │
│                                            │
│  [ ↻ Auto-suggest ]                        │
│  [ Cancel ]              [ Send to Printer ] │
└────────────────────────────────────────────┘
```

Banner is informational only — never blocks Send. Defaults
(red/green/blue/white) come from `defaultExtruderPresets()`, identical to
today's behaviour.

### S7 — Migration UX (v1.6.13 → v1.7.0 first launch)

```
┌─────────────────────────────────────────┐
│  What's new in v1.7.0                   │
│                                         │
│  Filament mapping moved.                │
│                                         │
│  Picking which physical slot prints     │
│  each colour now happens when you Send  │
│  to the printer, not when you slice.    │
│                                         │
│  Your extruder colours and printer      │
│  setup are unchanged.                   │
│                                         │
│  [ Got it ]                             │
└─────────────────────────────────────────┘
```

One-time DataStore-gated sheet. Existing files that re-load through the new
pipeline will produce equivalent G-code with the new mapping step;
in-flight slice jobs at upgrade time are abandoned (existing
upgrade-detector behaviour).

---

## §3 Decision log — Q1 through Q9

Each question from the skeleton's section 2.0, with the resolved answer.

### Q1. Initial state when a multi-colour file loads

**Resolved:** auto-mapping moves to print time. On load, the Prepare screen
shows a read-only chip strip with auto-suggested slots derived from
extruder presets. No auto-mapping happens *during slicing*; the suggestion
is just visual, for confidence.

**Why:** the bug class (B82, B86, B92, B95) is silent slice-time auto-map.
Removing the slice-time auto-map removes the bug class. Print-time mapping
makes the suggestion explicit at the moment of commitment.

### Q2. Edit affordance location

**Resolved:** dedicated **"Filament mapping"** dialog at Send to Printer
time. Mirrors Snapmaker desktop's "Print Preprocessing" pattern.

**Why:** matches the Snapmaker desktop flow the user identified as the
target (NOT mainline OrcaSlicer's implicit-by-order pattern). Concentrates
the colour decision at the moment it actually matters — when the file is
about to upload.

### Q3. Persistence scope

**Resolved:** per-file mapping remembered across reopens. Auto-suggest
reset button always available in the dialog.

**Why:** "same setup as last week" is the common case; remembering reduces
friction. The reset button covers cases where the user has changed
extruder presets and wants to recompute.

**Implementation:** small DataStore map (file hash → `colorMapping`).
File hash, not path, so a renamed file still matches. Stale entries (file
no longer present) garbage-collected on app launch.

### Q4. Sparse-preset flow (file palette > preset palette)

**Resolved:** auto-suggest at print time uses the same colour-distance
matching as today's `findClosestExtruder`. When file palette > preset
palette, the user sees the suggestion and overrides per row. No special
sparse-handling logic — the dialog gives the user direct control.

**Why:** the "ensureMultiSlotMapping rescue / redistributeDuplicateSlots
collapse-detection" logic exists because today's auto-map runs silently;
once the user sees the suggestion explicitly, no rescue is needed. Drop
the rescue logic with the synthesis layer.

### Q5. Validation surface (collisions / unmapped)

**Resolved:** overlap (two file colours → same physical slot) is a valid
choice; the dialog presents three options inline when overlap is detected:

1. **Pause between swaps** — manual filament swap, suitable for layer-tool
   files where colour changes are Z-banded. Pause-count estimate shown.
2. **Print both in the same colour** — no pauses; both file colours print
   as whatever is loaded in that slot.
3. **Reslice with these merged (beta)** — surfaces the pre-slice merge
   feature for SEMM/paint-segmentation files where pausing is impractical.

**Why:** overlap suitability depends on file shape (paint-segmentation vs
layer-tool). The pause-count estimate from the parsed G-code is the most
useful signal — 5 pauses is fine, 1425 is obviously impractical. Letting
the user pick the behaviour respects their intent (B76 Goat-style fold)
without us having to classify file types.

**Notes:**
- The pause-count estimate is computed from the existing parsed G-code
  per pair of T-indices.
- The "Reslice with these merged" option is the entry point for the
  pre-slice merge feature — keeps it discoverable without needing a
  separate Advanced toggle.

### Q6. First-time user (no presets configured)

**Resolved:** seed defaults (red/green/blue/white via
`defaultExtruderPresets()`) so the dialog has something to auto-suggest
against. Informational banner in the dialog: *"Set your extruder colours in
Settings to get better suggestions."* Never blocks Send.

**Why:** lowest friction for first-install ("just slice and send").
Banner provides discoverability without forcing setup.

### Q7. Single-colour file flow

**Resolved:** dialog appears for every file, single-colour included. One
row, one slot picker, Apply button. No special-case path.

**Why:** the dialog is the explicit "this is going to print on slot X"
moment. Single-colour files need that confirmation just as much as
multi-colour. The cost is one click on Send, which is a reasonable price
for "the user always knows which slot will be used."

`ExtruderPickerRow` retires; the read-only chip strip on Prepare and the
mapping dialog replace it.

### Q8. STL / non-Bambu (no embedded filament list)

**Resolved:** STL synthesises a 1-row `CanonicalFilamentList` at load
time. Source field is `STL_DEFAULT`. Dialog appears with one row, picker
for slot selection. UI is identical to single-colour 3MF.

**Why:** consistent shape across all file types. Same code path. STL's
"colour" comes from the slot's preset (matches today's
`ExtruderPickerRow` semantics).

### Q9. Layer-tool / Hueforge files

**Resolved:** synthetic N-entry expansion at load time. One filament entry
per layer-tool segment. Dialog is identical to multi-colour SEMM.

**Why:** runtime distinction (Z-band activation vs paint segmentation vs
object default) is a renderer/embed concern, not a UI concern. The user
sees N filaments to map, exactly like multi-colour. The "pause between
swaps" option in the overlap UX is most useful here — layer-tool files
are precisely where pause-and-swap is practical.

---

## §4 Order of operations for Phase 2.1+

Concept-agnostic order is replaced with a simpler, decision-locked
sequence. Each step lands independently.

### Step 1 — Data model + load-time normalize (Phase 2.1)
Land `CanonicalFilamentList`, `FilamentEntry`, `paintStateMap`, plus the
per-format `normalizeAtLoad` functions. Replace `MergeThreeMfInfoTest`-style
synthesis tests with assertions on the canonical shape.

### Step 2 — Slicer reads canonical list verbatim (Phase 2.2)
- Embed pipeline writes file's `filament_colour` verbatim — no count bump,
  no special H2C path.
- `colorMapping` is no longer a slice-time concern. The native slicer
  emits `T0..T(N-1)` against the file's filament indices.
- **Move** `objectPartExtruders`, `compoundPartParents`,
  `paintExtruderStates`, `objectExtruderMap` *off* `ThreeMfInfo` and
  *into* the canonical list's per-entry metadata. The data itself stays;
  it's load-bearing for the load-time normalize and for the future merge
  feature's per-object rewrite. What goes away is its role as a separate
  synthesis source.
- **Drop entirely** the post-slice T-index synthesis:
  `computeEmbedTargetCount`, `computeExpandedGcodeRemap`, `composeSemmRemap`,
  `computeSemmColorPermutation`, `slicerColorOrder`. These solve "what
  did the slicer emit and what does it mean for user slots?" — a problem
  that doesn't exist once T-indices and file indices are identical.

### Step 2 sidebar — what survives for the future merge feature

The merge feature (Phase 2.6+) is a **pre-slice rewrite**, not a post-slice
synthesis, so it needs different machinery:

| Need | Where | Status |
|---|---|---|
| Decode paint_color triangle bit-packing | `PaintColorDecoder` | Exists (v1.6.13) |
| Re-encode paint_color triangles after merge | `PaintColorEncoder` | New code (Phase 2.6) |
| Rewrite per-object `extruder=N` in `model_settings.config` | New helper | Phase 2.6 |
| Rewrite per-volume extruder | `BambuSanitizer`-style | Mostly exists |
| Rebuild canonical list with N-1 entries | Load-time normalize | Phase 2.1 |

What does NOT survive for the merge feature: the post-slice synthesis
listed above. The slicer just emits `T0..T(N-2)` against the rewritten
4-filament list; the print-time remap is verbatim.

### Step 3 — Print-time remap (Phase 2.3)
- `applyPrintTimeRemap(gcodePath, colorMapping): Path` — single small
  function. Reads G-code, rewrites every `T<n>` line to `T<colorMapping[n]>`,
  also rewrites `M104 T<n>`, `M109 T<n>`, `SM_ EXTRUDER=<n>`, `SM_ INDEX=<n>`
  patterns. Writes to `${gcodePath}.remapped`.
- Hooked into `PrinterRepository.sendAndPrint` — between
  filament-mapping confirm and the actual upload.

### Step 4 — UI: Prepare-screen chip strip + Send button reshape (Phase 2.4a)
- Replace `PrintSetupSection` with a read-only chip strip.
- Add caption *"Final slot mapping on Send →"*.
- Reshape Send button to two-stage label.
- Retire `ExtruderPickerRow` (single-colour falls into the same chip
  strip path).

### Step 5 — UI: Filament mapping dialog (Phase 2.4b)
- New `FilamentMappingDialog` Compose component.
- Renders one row per used filament with file-colour swatch + slot picker.
- Auto-suggest button, reset behaviour.
- Per-file mapping persistence (DataStore).
- Overlap detection + 3-option inline picker (Pause / Same colour /
  Reslice merged).
- "Reslice merged" option is gated behind a "beta" flag for v1.7.0 — UI
  surface present, action triggers a "Coming soon" toast until Phase 2.6
  lands the actual merge implementation.

### Step 6 — Migration + first-launch (Phase 2.5)
- "What's new in v1.7.0" sheet, DataStore-gated.
- Settings backup format bump (canonical-list shape).
- Old backups load through a one-shot legacy adapter.
- Upgrade-detector clears in-flight slice jobs (already does).

### Step 7 — Tier-A regression sweep + retire bug entries (Phase 2.5)
- Re-run `BambuPlateStateRegressionTest` + `BambuFixtureHarnessTest` + E2E
  smoke-7.
- Retire B82/B86/B92/B94/B95 entries (or update to "impossible-by-construction
  since v1.7.0").
- Update `CLAUDE.md` Architecture section.

### Phase 2.6+ (post-v1.7.0)
- **Pre-slice merge implementation.** The "Reslice merged" option's
  scaffolding is in place from Step 5; this phase wires it up:
  user-chosen merges feed back into the canonical list at load, slicer
  sees N-1 filaments, fewer tool changes.
- **Printer-side auto-sync.** The `PrinterViewModel.syncFilaments` flow
  already exists; this phase adds an auto-prompt at file-load or
  pre-Send: *"Printer reports E2 = orange (your settings: red). Sync?"*
  Optional auto-apply gated by user preference.

---

## §5 Open risks

1. **STL "file colour" is undefined.** STL geometry has no embedded colour;
   the canonical list synthesises a 1-row entry whose colour comes from
   the user's currently-selected extruder preset. If the user later
   changes that preset, the chip strip / dialog should reflect the new
   colour. Implementation note: the `FilamentEntry.color` for `STL_DEFAULT`
   should be a derived value, not a snapshot — re-read on each render.

2. **Compound objects with per-part extruder metadata.** Today's
   `objectPartExtruders` carries part-level extruder assignments that may
   not match the file-level filament list (B23, Dragon Scale). The
   canonical list's `paintStateMap` needs an analogous mechanism to absorb
   per-part overrides at load time, normalised into the filament list.
   **Impact:** Phase 2.1 normalize step.

3. **Profile embed contract.** OrcaSlicer reads `filament_colour` of size
   N. With the slice-time canonical list anchored to file size, this
   simplifies — no bump logic. But the maximum N is now a hard
   architectural ceiling. Layer-tool synthetic expansion of N=20 means
   the embed must handle that. Recommend asserting a maximum at the
   normalize step (e.g. N ≤ 32 — well above any realistic file).

4. **Per-plate paint-state vs file-level filament list.** Buzz plate 7/8/9
   each surface different paint state subsets. The canonical list is
   file-scoped; the user sees the file's full palette regardless of
   plate. UI choice for the chip strip and dialog: grey out
   filaments-not-used-on-this-plate, or hide them?
   **Recommendation:** hide them (matches today's `detectedColors`
   per-plate scope) but track the full file list internally for embed.
   **Impact:** Phase 2.1 normalize step + Phase 2.4a chip strip rendering.

5. **`hasMultiExtruderAssignments` flag retention.** ProfileEmbedder uses
   this to decide preserve-vs-rebuild on `model_settings.config`. After
   the refactor, the canonical list always carries per-object assignments,
   so the flag becomes a deprecation candidate. Schedule its removal in
   Phase 2.5.

6. **Recolouring vs rebinding — the index-stability invariant.** Filament
   3 stays filament 3 even when the user changes its colour. The
   canonical list must preserve this — `findClosestExtruder` is the
   *seed* for new mappings only; the per-file remembered mapping (Q3)
   never re-runs colour matching once the user has touched a row.
   **Implementation:** per-file mapping has a "user-edited" flag;
   Auto-suggest reset is the only path that re-runs the closest-colour
   logic.

7. **Pause-count estimate accuracy.** The "≈ N pauses" number in the
   overlap inline picker comes from the parsed G-code's per-T-pair tool
   change count. If the user changes the overlap mapping, the pause count
   for *that pair* needs to be recomputed. This is cheap (the parsed
   G-code is in memory at that point) but a contract worth asserting.
   **Impact:** Phase 2.4b dialog logic.

---

## §6 Question-count audit (revisited)

The skeleton listed 9 questions; the interactive review surfaced two more
(both resolved here):

- **Q10. Print-time mapping** — yes; this becomes the spine of the
  design. (Resolved: dialog at Send to Printer.)
- **Q11. Overlap behaviour** — surfaced when discussing >4-colour files
  on a 4-slot printer. Three options per overlap pair.

The skeleton's Q3 (persistence) genuinely splits into two — *project state*
(per-file mapping) and *printer state* (loaded-spool readback). Project
state is resolved here (Q3 → per-file remember). Printer-state readback is
deferred to Phase 2.6+ via the existing `PrinterViewModel.syncFilaments`
flow.

---

## Appendix — terminology

| App term | Meaning |
|---|---|
| **Filament** | One entry in the canonical list (1..N from the file, after hiding unused). Index matches G-code `T<index>`. |
| **Slot** / **Extruder** | One of the 4 physical hardware extruders (E1-E4). |
| **Mapping** | The 1↔1 from filament-index to physical slot. Set by user at Send time. |
| **Source** | Provenance of a `FilamentEntry` (`FILE_COLOUR / PAINT_DERIVED / OBJECT_DEFAULT / LAYER_TOOL / STL_DEFAULT`). |
| **Auto-suggest** | The colour-distance match that seeds the mapping from extruder presets. Reset button always available. |
| **Overlap** | When two filaments map to the same slot. Three behaviours: Pause / Same colour / Reslice merged. |

User-facing copy uses **"Filament" / "Slot"** consistently; never
**"colour" / "extruder"** as the identity term, because filament 3 stays
filament 3 even when its colour is edited.

---

## §7 Prepare-screen reshape (added 2026-04-26)

Captured during the Phase 2.4 smoke-test session — the original brief
under-described what should happen on Prepare once the slot picker
moves to Send. The directive crystallised: **mirror desktop
OrcaSlicer / Bambu Studio's Filament panel.**

### What Prepare shows

A vertical list of filament rows, one per filament in the file's
canonical list. Each row:

```
[colour swatch ●]  Filament 1   [PLA ▾]   [edit colour]
[colour swatch ●]  Filament 2   [PETG ▾]  [edit colour]
…
```

- **Colour swatch** — tappable; opens a colour picker. Defaults from
  the file's `filament_colour` (3MF) or the printer's currently-loaded
  filament data (STL via `PrinterViewModel.syncFilaments`).
- **Material-type dropdown** — PLA / PETG / ABS / TPU / etc.
  Defaults from the file's `filament_type` (3MF) or printer-loaded
  material (STL).
- **No slot picker** — slot mapping happens at Send time only.
- **Read-only count** — N rows match the file's filament count.
  Adding/removing is deferred (Phase 3+ if requested).

### Where overrides go

**Per-file, persisted.** When the user reopens a file, their previous
overrides are restored. Storage key is the file hash (matches the
mapping persistence in §1 Q3).

If a 3MF declares "Filament 1 = yellow PLA" but the user is loading
green PETG, they override on Prepare → green PETG colour and PETG
material. The slicer then bakes 240° (PETG temp) into the G-code
instead of 220° (PLA), and the overridden colour is what the
Filament mapping dialog shows when picking slots.

### Why this matches desktop precedent

OrcaSlicer and Bambu Studio's Filament panel works exactly this way —
the file declares filaments; the user can swap any of them out for a
different material/colour before slicing; the slicer uses the user's
choices. The U1 just reuses that mental model on a phone form factor.

### Mismatched material handling

When the user maps a filament to a slot in the Send dialog, and the
slot's `ExtruderPreset.materialType` differs from the filament's
(possibly overridden) material, surface a small chip on the dialog
row: *"Slot loaded as PLA, but this filament is PETG"*. Doesn't block
Send — the user owns the loaded-spool truth.

Going further (a material override in the Send dialog) is Phase 3 if
beta surfaces a real need.

### Implementation queue

1. Reshape `PrintSetupSection` Compose card to editable rows
   (colour picker + material-type dropdown). Replaces today's
   colour-to-slot dropdown.
2. Wire overrides into `CanonicalFilamentList` — overrides override
   `FilamentEntry.color` / `materialType` for slicing.
3. Per-file override persistence (file hash → `Map<Int, Override>`).
4. STL flow: pull printer-loaded filament data into the canonical
   list as defaults via the existing `syncFilaments` pipeline.
5. Material-mismatch chip in Filament mapping dialog rows.

---

**End of UX brief.** Implementation begins at Phase 2.1 (data model +
load-time normalize) per §4.
