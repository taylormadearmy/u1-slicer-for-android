# Canonical filament list — Phase 2.0 UX exploration

**Status:** option menu. The 9 open UX questions from the Phase 2 design
skeleton are addressed below as menus of 2-3 options each, with a flagged
recommendation. **No decisions are made by this document.** Each call is the
user's to make; the agent's job here is to make picking easy.

**Companion to:**
[`2026-04-26-canonical-filament-list-design.md`](2026-04-26-canonical-filament-list-design.md)
(the Phase 2 skeleton — read that first for *why*; this doc covers *how the
UI feels*).

**Branch:** `feature/phase2-canonical-filaments`. No production code, tests, or
Compose previews change in this commit.

---

## Reading order

1. Skim **§0 TL;DR** for the recommended-option summary.
2. Read **§1 Question-by-question option menus** at whatever depth you want;
   each question is independent.
3. **§2 Wireframes** — three end-to-end concepts (A: inline / B: dialog-at-print
   / C: fixed-4) covering all the touch surfaces in one go.
4. **§3 Desktop precedent** — comparison table of Orca / Bambu / Prusa / Cura
   for each question.
5. **§4 Order-of-operations** for Phase 2.1+ once a concept is picked.
6. **§5 Open risks** the wireframes don't resolve.
7. **§6 Question-count audit** — three structural surprises that suggest the
   "9 questions" should arguably be **10**.

---

## §0 TL;DR — recommended-option summary

| # | Question | Options | Recommendation (your call) |
|---|---|---|---|
| 1 | Initial state on multi-colour load | (A) auto-mapped + edit affordance; (B) empty + demand attention; (C) auto if presets cover, banner if sparse | **C** — explicit user attention only when needed |
| 2 | Edit affordance location | (A) inline always-visible card on Prepare; (B) modal dialog; (C) hybrid | **A** — matches today's Print Setup card and Orca/Bambu pattern |
| 3 | Persistence scope | (A) file-only; (B) session-only; (C) file + per-printer-preset fallback; (D) Bambu-style three-layer | **C** — D is cleaner but assumes a printer-side AMS sync the U1 doesn't have |
| 4 | Sparse-preset flow | (A) block file load; (B) silent round-robin (today); (C) round-robin + banner; (D) silent + hard-block slice | **C** — banner only when there's an actual mismatch; no UI when not needed |
| 5 | Validation surface (collisions / unmapped) | (A) flag but allow; (B) hard-block; (C) silent (today) | **A** — same-slot is sometimes intentional (user wants to fold colours) |
| 6 | First-time user (no presets) | (A) force preset wizard; (B) implicit "use file colours" presets; (C) empty rows + "set up extruders first" banner | **B** — least-friction; lets the user ship a print on first install |
| 7 | Single-colour file flow | (A) hide mapping UI (today); (B) show 1-row canonical list with extruder picker; (C) degenerate 1-entry list | **B** — consistent surface; one architecture for all file shapes |
| 8 | STL / non-Bambu | (A) show degenerate 1-row canonical list; (B) keep today's ExtruderPickerRow only; (C) ExtruderPickerRow IS the canonical list for STL | **A** — STL becomes a 1-row `CanonicalFilamentList`; same code path |
| 9 | Layer-tool / Hueforge | (A) synthetic N filaments expanded at load; (B) single filament + "Layer Color Plan" sub-UI; (C) N filaments but read-only | **A** — simplest data model; runtime activation (Z-band vs paint) is a renderer detail |

**Cross-cutting recommendation:** Wireframe **Concept A (inline always-visible)**
is the natural home for these picks. Concept B (Bambu-style) is a fine fallback
if the team wants stronger separation between project state and print state;
Concept C (PrusaSlicer-style fixed-4) is the simplest data model but the most
disruptive UI shift from v1.6.13. See §2.

---

## §1 Question-by-question option menus

For each question: 2-3 options, with **trade-offs** and **complexity tag**
(`Δlow / Δmed / Δhigh` indicating how far the option deviates from today's
v1.6.13 UX). The recommendation is flagged but not pushed.

### Q1. Initial state when a multi-colour file loads

**Today (v1.6.13):** `findClosestExtruder` is run, `ensureMultiSlotMapping` /
`redistributeDuplicateSlots` rescue collapses, `MultiColorDialog` may pop up
(only on first load of a multi-colour file).

| | Option | Trade-off | Δ |
|---|---|---|---|
| A | Auto-suggested mapping with prominent "Edit Mapping" affordance. Always silent on load. | Lowest friction. Bug class survives — the user can slice without ever realising the auto-map collapsed. | low |
| B | Empty mapping. Banner: "Tap each filament to assign an extruder slot." Slice button disabled until all rows mapped. | Forces explicit user action — eliminates B86/F1 silent-collapse class entirely. Adds a click on every multi-colour load, even when the auto-map would have been correct. | high |
| C | Auto-map silently when presets cover the palette (≥ N distinct presets for N file colours). Show a "Review mapping" banner only when the auto-map collapsed (sparse presets, duplicate matches). | Targets the actual failure mode without taxing the common case. Requires a "did the auto-map collapse?" predicate (we already have `ensureMultiSlotMapping`'s detection). | med |

**Recommendation: C.** B86/F1/B92 all share the shape "the auto-map silently
produced a degenerate result and the user shipped." C surfaces only that
degenerate result; correct auto-maps stay invisible.

---

### Q2. Edit affordance location

**Today:** Inline `PrintSetupSection` card on Prepare (per-colour rows with
extruder dropdowns), plus a `MultiColorDialog` modal triggered by an "I"
(info) button → "Reassign Multi-Color".

| | Option | Trade-off | Δ |
|---|---|---|---|
| A | Inline-always card on Prepare (canonical-list view). Dialog retired or kept only as a "Help me redo this" affordance. | Matches Orca/Bambu's always-visible filament panel. Card grows with N — needs scrolling at N>4. Reuses today's `PrintSetupSection` pattern. | low |
| B | Dialog-only ("Filament Setup"). Single click on a Prepare-screen pill opens the modal. | Cleaner Prepare screen; clear "I am editing the filament list" mode. Adds a click for every change. Diverges from desktop precedent. | med |
| C | Hybrid: inline rows for quick slot reassignment, "Edit filaments…" link opens detail view for material type / colour edits / add / remove. | Matches Bambu's "+ for filament management vs row-tap for slot pick" split. Extra surface to maintain. | med |

**Recommendation: A.** Today's `PrintSetupSection` is already 80% of A; finishing
the job is the smallest landable slice. Defer C until a real "I want to edit a
filament's hex / type from inside the slicer" need surfaces — for now the
canonical list is *read* from the file, only the mapping is *written*.

---

### Q3. Persistence scope

**Today:** `colorMapping` lives on `SlicerViewModel` as a `StateFlow<List<Int>?>`;
it's regenerated from `findClosestExtruder` on every fresh `loadNativeModel`.
Nothing persists between app launches except the user's `ExtruderPreset` colours
(which feed `findClosestExtruder` as inputs).

| | Option | Trade-off | Δ |
|---|---|---|---|
| A | File-level only (3MF). On reopen, restore the mapping the user last set. New files start from `findClosestExtruder` suggestion. | Matches Orca/Bambu/Prusa — they store the filament list in the .3mf project. Clear ownership. Doesn't help when the user reloads from MakerWorld vs from disk; the file identity is just the path. | med |
| B | Session-only. Forget on app close. | Closest to today. Sidesteps "what is file identity" but means the user re-does the mapping on every reload of the same file. | low |
| C | File + per-printer-preset fallback. Mapping persists per file (keyed by file hash or 3MF metadata); when no per-file record exists, derive from extruder presets via `findClosestExtruder`. | Works the way users actually behave: "I always print red on E2." Falls back to today's auto-map for new files. Needs a tiny persistent map (file hash → mapping). | med |
| D | Three-layer (Bambu pattern): (1) project state in 3MF, (2) per-printer "current spool loadout" in extruder presets, (3) mapping reconciles them at slice time. | Cleanest model; matches Bambu's separation of "what the project wants" vs "what's in the slot right now." Premise of layer 2 is a printer-side AMS sync we don't have on Snapmaker U1. | high |

**Recommendation: C.** D is right for a future where the U1 reports its loaded
filaments (Moonraker has the data — phase 3+). For Phase 2, C is the simplest
thing that fixes the bug class (B86/F1: the silent regenerate-on-every-load
loses the user's previous mapping intent).

---

### Q4. Sparse-preset flow (file palette > preset palette)

**Today:** `ensureMultiSlotMapping` detects the collapse and `redistributeDuplicateSlots`
silently spreads to unused slots. F1 calendar shipped a bug because the
distribution was correct *but* the user didn't know it had happened.

| | Option | Trade-off | Δ |
|---|---|---|---|
| A | Block file load. "You have 2 extruder colours configured but this file uses 5. Set up E3-E5 first." Settings-deep-link button. | Eliminates the bug class hard. Prohibitive for a casual user opening a MakerWorld file to see what it looks like. | high |
| B | Silent round-robin (today). | No friction. Bug class survives. | low |
| C | Round-robin distribute, with an in-card banner: "3 of 5 colours matched your extruders. The other 2 were assigned to E3 and E4 — review below." Tap-to-acknowledge. | Banner appears only when there *is* a sparse mismatch. Doesn't block anything. Makes the silent failure visible. | med |
| D | Distribute, then hard-block the slice button until the user has tapped each redistributed row at least once. | Strongest "you must look at this" without blocking file load. Annoying when distribution was already what the user wanted. | high |

**Recommendation: C.** Same shape as Q1.C: invisible when correct, visible
when not. Pairs naturally with Q1.C's "review mapping" banner — they can be
the same banner.

---

### Q5. Validation surface (collisions / unmapped)

**Today:** Mapping is permitted with no warning. Two file colours mapping to
the same physical slot is allowed and sometimes intentional (B76 Goat —
`[0,1,2,2]` is what the user wanted).

| | Option | Trade-off | Δ |
|---|---|---|---|
| A | Permitted, flagged with a chip ("E3 used by 2 colours — they will print as the same filament"). Slice still works. | Honours intentional folds (Goat), surfaces accidental ones. Familiar pattern (Orca shows a similar caution). | med |
| B | Hard-block: refuse to slice with collisions. Settings → "Allow filament folding" toggle for power users. | Catches accidents. Annoying for B76-style intentional folds. | high |
| C | Silent (today). | No friction. Misuse undetected until print. | low |

**Recommendation: A.** Single-mapping-row chip with the conflict text; slice
button stays enabled. Matches the rest of the app's "warn don't block"
philosophy (e.g. `copyBedWarning`).

---

### Q6. First-time user (no presets configured)

**Today:** `defaultExtruderPresets()` provides red/green/blue/white as
placeholder. `findClosestExtruder` runs against those. New user can slice
immediately but with the wrong colours showing in Preview.

| | Option | Trade-off | Δ |
|---|---|---|---|
| A | Force the preset wizard before any file load. "Set up your extruders first." | Cleanest first-print outcome. High friction; user can't open a file just to look. | high |
| B | Implicit "use file colours" presets — when no user-edited presets exist, the canonical filament list seeds the extruder presets directly (filament 1 colour → E1 colour, etc.). User can override later. Banner: "Using this file's colours for your extruders. Edit in Settings." | Lowest friction. One-click slice on first install. The file's colours become the defaults until the user changes them. Easy to revert. | med |
| C | Show empty mapping rows. Banner: "Set up your extruders in Settings first" with a deep-link. File loads, but slice is disabled. | Forces the user to confront the setup once, then everything works. Friction is concentrated. | med |

**Recommendation: B.** "Just slice" wins the first-impression test. The
explicit banner means the user knows the implicit default exists and how to
change it.

---

### Q7. Single-colour file flow

**Today:** No mapping UI; instead `ExtruderPickerRow` (E1-E4 chips) lets the
user pick which physical slot to print on. `selectedExtruder` StateFlow drives
this.

| | Option | Trade-off | Δ |
|---|---|---|---|
| A | Hide canonical-list UI, keep `ExtruderPickerRow` (today). | Familiar. Maintains a separate code path forever. | low |
| B | Show 1-row canonical list. Row contains the file's single colour + extruder slot dropdown. `ExtruderPickerRow` retires. | Single architecture for all files. Slightly more visual weight for the simple case. | med |
| C | Show degenerate 1-entry list and `ExtruderPickerRow` together. | Worst of both. | low |

**Recommendation: B.** The whole point of Phase 2 is one canonical list — a
1-row list for single-colour files is the architecture honouring its own
contract. The visual weight is acceptable; we can render the 1-row case
slimmer (label "Filament" not "Filaments", no add/remove affordance).

---

### Q8. STL / non-Bambu (no embedded filament list)

**Today:** STL falls into the single-colour path and uses `ExtruderPickerRow`.
There is no `filament_colour` to derive a canonical list from.

| | Option | Trade-off | Δ |
|---|---|---|---|
| A | Synthesise a 1-row `CanonicalFilamentList` at load time: `FilamentEntry(fileIndex=0, color=preset.E1.color, materialType=preset.E1.materialType, source=STL_DEFAULT)`. UI is identical to single-colour 3MF. | One architecture. Source field carries the "no real file palette" provenance. | med |
| B | Skip canonical-list UI for STL — keep `ExtruderPickerRow` and a separate code path. | Simpler today; bigger debt. | low |
| C | Hybrid: show `ExtruderPickerRow` and label it "Extruder for this file" — but back it with a 1-row canonical list internally. | Renames the surface without retiring it. Saves one Compose component, doesn't change behaviour. | low |

**Recommendation: A.** Same data shape, same code path. The renderer reads
`filaments[0].color` from the preset because the source is `STL_DEFAULT`; this
naturally tracks preset colour edits (which `ExtruderPickerRow` already does
today via `extruderPresets[i].color`).

---

### Q9. Layer-tool / Hueforge files

**Today:** `layerToolOnly` flag drives a separate render path. `customGcodePerLayer.xml`
specifies tool changes by Z-band. `detectedColors` may show 1 entry (the
file's nominal filament) but the file actually uses N at print time.

| | Option | Trade-off | Δ |
|---|---|---|---|
| A | Synthetic expansion at load: `CanonicalFilamentList` has N entries (one per layer-tool segment), with `source = LAYER_TOOL`. UI is identical to multi-colour 3MF. The runtime distinction (Z-band vs paint vs object-default) lives in the renderer/embed pipeline, not the UI. | One UI for all multi-tool files. The Layer-Color-Plan structure (which Z-bands map to which filament) is preserved as metadata on the entries (`layerRange: ClosedFloatingPointRange<Float>?`). | med |
| B | Single canonical-list entry plus a sub-UI ("Layer Color Plan") showing "Layer 0-12: filament 1; layer 12-25: filament 2; …" with a slot picker per row. | Honest UX — reflects the actual file shape. Adds a layer-tool-only sub-component. | high |
| C | N filament entries but the canonical list is read-only for layer-tool files (the file dictates the order); user only chooses which physical slot the file's filament 1 starts on (the rest follow by index). | Conservative; matches today's behaviour. Users can't reassign individual layer colours. | low |

**Recommendation: A.** The layer-tool UI gripe today is "I can't tell what's
going to happen" — exposing N rows in the same canonical list (each with its
slot picker) makes that visible. The Z-band metadata is a nice-to-have; the
basic mapping works without it.

---

## §2 Wireframes — three end-to-end concepts

ASCII mockups at ~46-column width (matches a Pixel 8a portrait layout
roughly). Each concept covers all five touch surfaces named in the brief.

### Concept A — "Inline always-visible canonical list" (recommended)

The canonical list lives on the Prepare screen as the existing
`PrintSetupSection` card, generalised. Mapping is per-row inline. Migration is
a one-shot best-effort import on first launch of v1.7.0.

#### A.1 — Filament library / canonical list editing surface

```
┌────────────────────────────────────────────┐
│  [3D model preview]                        │
└────────────────────────────────────────────┘
 ╭─ Print Setup ────────────────────────╮
 │ Filaments in this file (5)           │
 │                                      │
 │ ① ●red    PLA  → [E2 ●red    ▾] 220° │
 │ ② ●green  PLA  → [E1 ●green  ▾] 220° │
 │ ③ ●blue   PETG → [E3 ●blue   ▾] 235° │
 │ ④ ●yellow PLA  → [E3 ●blue   ▾] 235° │ ⚠
 │ ⑤ ●black  PLA  → [E4 ●white  ▾] 220° │
 │                                      │
 │ ⚠ E3 used by 2 colours — they will   │
 │   print as the same filament. (Q5.A) │
 │                                      │
 │ ─────────────────────────────────── │
 │ ⚙ Auto-map  □ Prime tower            │
 ╰──────────────────────────────────────╯
```

- **Each row** is one entry in the file's canonical list. ① is the file-index
  (1-based for the user; 0-based internally — matches `T0..T(N-1)`).
- **Left swatch** is the file's colour. **Right dropdown** picks the physical
  slot, with the slot's preset colour shown to confirm what's actually
  loaded.
- **Temperature** comes from the slot's filament profile (today's behaviour).
- **Conflict chip** (⚠) appears when two file colours map to the same slot
  (Q5.A).

#### A.2 — Prepare screen with canonical list visible

(See A.1 — the canonical list IS a Prepare-screen card. The 3D preview reads
`colorMapping` from the same source the card writes to.)

#### A.3 — Mapping point

**Per-row dropdown, write-through.** Changing a row immediately updates
`colorMapping` and triggers `refreshMappedPreviewColors`. No "Apply" button —
the mapping is the canonical UI surface, not a transient choice.

The mapping happens on the Prepare screen pre-slice. There is no
print-time AMS dialog (Bambu pattern) because Snapmaker U1 doesn't do
print-time spool selection.

#### A.4 — Paint-state vs object-default coexistence

Both shapes feed the same canonical list at load time:

- Object-default extruders contribute a `FilamentEntry` per distinct extruder
  index (B95 high-index normalised via `paintStateMap`).
- Paint states contribute a `FilamentEntry` per state (folded via
  `PaintColorDecoder` so state 11 = entry 11, not a new entry).

The user sees one unified list. The `source` field
(`OBJECT_DEFAULT / PAINT_DERIVED / FILE_COLOUR / LAYER_TOOL / STL_DEFAULT`) is
hidden by default; long-press a row to see provenance ("Filament 4 — paint
state 8 from object 'lid'").

#### A.5 — Migration UX (v1.6.13 → v1.7.0 first launch)

```
 ┌────────────────────────────────────┐
 │  What's new in v1.7.0              │
 │                                    │
 │  Filaments are now first-class.    │
 │  Your extruder presets carried     │
 │  over; existing files will         │
 │  re-derive their mapping the next  │
 │  time you open them.               │
 │                                    │
 │  No print jobs in flight will be   │
 │  affected — they were already      │
 │  abandoned by the upgrade detector.│
 │                                    │
 │  [   Got it   ]                    │
 └────────────────────────────────────┘
```

- Settings backup format bumps to v3 (canonical list shape). Old v2 backups
  load through a one-shot legacy adapter that runs `findClosestExtruder` once
  to seed the canonical list per file, then saves new format.
- Existing `SliceJob` rows survive — they reference embedded G-code, not a
  mapping. Re-slicing an old job goes through the new pipeline; the job
  history doesn't lie.
- No DataStore migration (the existing `ExtruderPreset` shape is unchanged).

---

### Concept B — "Bambu-style mapping dialog at slice time"

Canonical list lives in a dedicated **Filament Setup** dialog. Prepare screen
shows a compact summary; tapping it opens the dialog. Slicing pops the dialog
if the mapping has unresolved conflicts (Q5.A → "must resolve before slice").

#### B.1 — Filament library / canonical list editing surface

```
[Prepare screen]
 ╭─ Filaments ──────────────────────────╮
 │ 5 filaments → 4 extruders            │
 │ E1 ●green  E2 ●red  E3 ●blue (×2)    │
 │                          [Setup ▸]   │
 ╰──────────────────────────────────────╯

[Filament Setup dialog]
 ┌────────────────────────────────────┐
 │ Filament Setup                  ✕  │
 ├────────────────────────────────────┤
 │  File filaments       Slot         │
 │                                    │
 │  ●red    Color 1  →   ○E1 ◉E2 ○E3  │
 │  ●green  Color 2  →   ◉E1 ○E2 ○E3  │
 │  ●blue   Color 3  →   ○E1 ○E2 ◉E3  │
 │  ●yellow Color 4  →   ○E1 ○E2 ◉E3  │
 │  ●black  Color 5  →   ○E1 ○E2 ○E3 ◉E4│
 │                                    │
 │  ⚠ E3 used by 2 colours            │
 │                                    │
 │      [ Cancel ]  [ Apply ]         │
 └────────────────────────────────────┘
```

#### B.2 — Prepare screen

The Prepare screen carries a one-line **Filaments** summary card with chips for
the four physical slots showing which colours land there. Tap → dialog.

#### B.3 — Mapping point

**Inside the dialog only.** Slicing pre-flight checks for unresolved
conflicts (Q5.B-style hard block); if any exist, the dialog opens automatically
with a banner.

#### B.4 — Paint-state vs object-default

Same back-end as Concept A — the dialog renders a flat canonical list,
labelling rows by `source` if needed.

#### B.5 — Migration UX

Same "What's new" screen as A. Plus: on first multi-colour file load post-upgrade,
the dialog auto-opens once, showing the auto-mapped values for confirmation.
After confirmation, future loads of that file skip the auto-open.

**Trade-off vs A:** stronger separation of "I'm setting up the print" from
"I'm reading a model"; weaker "what is going to happen" affordance because
the mapping isn't always on screen. Bambu users will feel at home; Orca users
will miss the always-visible filament panel.

---

### Concept C — "PrusaSlicer-style fixed 4-slot list"

Canonical list is **always exactly 4 entries**, mirroring the U1's hardware.
File-filament-N>4 triggers a **load-time fold step**: the user explicitly
chooses how to combine 5+ file colours into 4 physical slots.

#### C.1 — Filament library / canonical list editing surface

```
[Settings → Extruders]  (canonical list)
 ╭──────────────────────────────────────╮
 │  E1 ●green  PLA  220°    [edit]      │
 │  E2 ●red    PLA  220°    [edit]      │
 │  E3 ●blue   PETG 235°    [edit]      │
 │  E4 ●white  PLA  220°    [edit]      │
 ╰──────────────────────────────────────╯
```

The list IS the printer's slot loadout. There is no project-scoped filament
list — files are folded into this list at load.

#### C.2 — Prepare screen

```
 ╭─ Print Setup ─────────────────────────╮
 │ This file uses 5 colours; folded      │
 │ into your 4 extruders:                │
 │                                       │
 │ File ●red    →  E2 ●red               │
 │ File ●green  →  E1 ●green             │
 │ File ●blue   →  E3 ●blue              │
 │ File ●yellow →  E3 ●blue (folded)     │
 │ File ●black  →  E4 ●white             │
 │                                       │
 │  [ Re-fold colours ]                  │
 ╰───────────────────────────────────────╯
```

#### C.3 — Mapping point

**At load time, via a fold dialog** when N>4. After fold, the per-row
re-mapping happens inline like Concept A but the list is anchored to the 4
extruders, not the file's N.

```
[Fold dialog — appears once on N>4 load]
 ┌────────────────────────────────────┐
 │ Fold 5 colours into 4 extruders    │
 │                                    │
 │ ●red    →   ○E1 ◉E2 ○E3 ○E4        │
 │ ●green  →   ◉E1 ○E2 ○E3 ○E4        │
 │ ●blue   →   ○E1 ○E2 ◉E3 ○E4        │
 │ ●yellow →   ○E1 ○E2 ◉E3 ○E4   (=blue) │
 │ ●black  →   ○E1 ○E2 ○E3 ◉E4        │
 │                                    │
 │  [ Apply ]                         │
 └────────────────────────────────────┘
```

#### C.4 — Paint-state vs object-default

All collapsed into the 4-slot list at load. Provenance is preserved on the
fold-dialog rows (so the user can see "this is paint state 8 from object
'lid'" before deciding which slot it goes to).

#### C.5 — Migration UX

`Settings → Extruders` is identical to today's `ExtruderPreset` UI — no migration
needed for the printer-scoped list. Existing files re-fold on next open.

**Trade-off vs A:** simplest data model (4 slots, period); biggest UX shift
from v1.6.13 (the file-level filament list disappears as a user-facing concept).
Conceptually clean — every G-code emit is `T0..T3`, never wider — but loses the
desktop-Orca pattern the user said is the model.

---

## §3 Desktop precedent — comparison table

| # | Question | OrcaSlicer | Bambu Studio | PrusaSlicer | Cura | Current U1 (v1.6.13) |
|---|---|---|---|---|---|---|
| 1 | Initial state | Auto-suggested per-file profile, list always visible | Auto-mapped via AMS dialog, modal at print time | Filament dropdowns auto-populated from project | Per-extruder material from machine profile (no project list) | Auto-map via `findClosestExtruder` + dialog on first load |
| 2 | Edit affordance | Inline filament panel, always visible | Inline + AMS dialog at print | Top-right `Filament:` dropdowns | Top-bar extruder flyout | Inline `PrintSetupSection` + dialog from "I" menu |
| 3 | Persistence | In .3mf project (per file) | .3mf project (mapping recomputed at print) | .3mf + system filament pool | Per-machine extruder loadout | Session-only (regenerated each load) |
| 4 | Sparse filaments / N>slots | Multi-tool: must reduce; SEMM: AMS dialog folds | AMS dialog allows N>slots with manual swap mid-print | Cannot exceed by construction | Slice-time error | Silent round-robin via `ensureMultiSlotMapping` |
| 5 | Validation | Allow with warning | Allow with warning, AMS dialog flags | Permitted | Slice-time error | Permitted, no warning |
| 6 | First-run / no presets | Default starter filaments after wizard | Default 4-filament starter | Configuration Wizard | Add-a-Printer wizard pre-loads | `defaultExtruderPresets()` (red/green/blue/white) |
| 7 | Single-colour file | Same panel, 1 row | Same panel, 1 row | Same dropdown | Same top-bar | `ExtruderPickerRow` (separate component) |
| 8 | STL / no embedded list | Filament panel still shown | Same | Same dropdown count | Per-extruder material applies | `ExtruderPickerRow` (single-colour path) |
| 9 | Layer-tool / Hueforge | Treated as N filaments (mmu_segmentation indices) | Same | "Multi-Material painting" indices | Not a first-party concept | `layerToolOnly` flag, separate render path |

**Key takeaway:** desktop slicers all use **index-based identity** (filament 1
is filament 1 regardless of colour) and the user manages list ordering, not
RGB matching. The U1's current nearest-RGB auto-map has no analogue on
desktop. **Phase 2's direction is correct**; the only call left is which
visual envelope (A/B/C) to deliver it in.

---

## §4 Recommended order of operations for Phase 2.1+

Once a concept (A/B/C) is picked, this is the smallest landable slice
sequence. Each step is independently shippable behind a feature flag.

> All recommendations assume **Concept A** is picked. Notes call out where
> the order changes for B or C.

### Step 1 — Data model + load-time normalize (Phase 2.1)
**Land:** `CanonicalFilamentList`, `FilamentEntry`, `paintStateMap`, plus a
single `normalizeAtLoad(file: File): CanonicalFilamentList` per file format
(Bambu 3MF, PrusaSlicer 3MF, STL, generic 3MF, layer-tool 3MF).

**Why first:** every other step reads from this. Without it, the synthesis
layer can't be deleted because there's nothing to read instead.

**Test surface:** unit tests per format + the existing `MergeThreeMfInfoTest`
shape, replaced with assertions on the canonical list rather than the
synthesis path.

**Acceptance:** the new pipeline produces the same canonical shape on every
fixture in `BambuFixtureHarnessTest.kt`. Synthesis layer code untouched yet.

### Step 2 — `colorMapping` becomes the only contract (Phase 2.2)
**Land:** the B47 init block, `loadNativeModel`'s `colorMapping` regenerate,
`applyMultiColorAssignments` all read/write `colorMapping` of size
`filaments.size`. Drop `objectPartExtruders`, `compoundPartParents`,
`paintExtruderStates` from `ThreeMfInfo` (already `@Deprecated`).

**Why second:** until `colorMapping` is the only thing UI and embed pipelines
read, we can't trust the canonical list as a source of truth.

**Acceptance:** PreparePreview tests still pass. Tier-A PM-bug regression
suite still green.

### Step 3 — Embed pipeline simplification (Phase 2.3)
**Land:** `computeEmbedTargetCount = filaments.size`,
`computeRemap = colorMapping`, `embedProfile` writes the file's
`filament_colour` verbatim. Delete `composeSemmRemap`, the
`semmColorPermutation` synthesis, `slicerColorOrder`, the B95-bump branch.

**Why third:** this is where the bug class actually dies — once the embed
pipeline reads the same canonical list the renderer reads, B82/B86/B92/B95
become structurally impossible.

**Acceptance:** Buzz plate 8 + plate 9 + Flarewing + colored Benchy + Goat
all slice with the new pipeline producing equivalent G-code. The "v1.6.13
chip-count regression we'll live with" can be re-checked here — if Phase 2.3
fixes it for free, even better.

### Step 4 — UI: canonical list inline (Concept A)
**Land:** `PrintSetupSection` reshapes to read from `CanonicalFilamentList`.
`MultiColorDialog` retires; single-row case (`ExtruderPickerRow`) folds into
the canonical list (Q7.B / Q8.A).

**Why fourth:** UI follows data. With Steps 1-3 in, the UI just renders the
canonical list; if Steps 1-3 are missing, UI changes paper over the synthesis
layer rather than replacing it.

**Acceptance:** the conflict chip (Q5.A), sparse banner (Q1.C / Q4.C), and
implicit-presets banner (Q6.B) appear in the right circumstances, verified by
unit tests on the predicate functions and a Compose UI test on the chip
visibility.

> **For Concept B:** Step 4 splits — first land the inline summary card
> (one-line state), then land the dialog separately. Two PRs.
>
> **For Concept C:** Step 4 includes the fold dialog and the load-time fold
> path, which is a bigger lift. The `Settings → Extruders` page is largely
> unchanged.

### Step 5 — Persistence + migration (Phase 2.4)
**Land:** per-file mapping store (Q3.C — file hash → `colorMapping`).
Settings backup v3 schema, with a v2-load adapter. Upgrade-detector clears
in-flight slice jobs (already does). "What's new in v1.7.0" sheet (one-shot,
DataStore-gated).

### Step 6 — Tier-A regression sweep + retire bug entries (Phase 2.5)
**Land:** Re-run `BambuPlateStateRegressionTest` + `BambuFixtureHarnessTest`
+ E2E smoke-7. Retire B82/B86/B92/B94/B95 BACKLOG entries (or update them to
"impossible-by-construction since v1.7.0"). Update `CLAUDE.md` Architecture.

---

## §5 Open risks the wireframes don't resolve

1. **Print-time slot loadout is not in scope.** All three concepts assume the
   user knows which physical spool is in which slot. Bambu's AMS dialog
   reconciles project state to *current* AMS state; we don't have a current-AMS
   readout. If a user keeps presets static but reloads spools physically, the
   mapping is stale and the print prints the wrong colours. Mitigation
   (deferred): query Moonraker for filament types per slot if the printer
   reports them; flag mismatches.

2. **Compound objects with per-part extruder metadata.** Today,
   `objectPartExtruders` carries part-level extruder assignments that don't
   match the file-level `filament_colour` list (B23, Dragon Scale). The
   canonical list assumes a flat per-file palette; per-part overrides fold
   into it as derived entries via `objectExtruderMap`. Risk: a part overrides
   to a higher index than the file's palette covers (the B95 shape). The
   skeleton's `paintStateMap` covers paint states; it needs an analogous
   `partExtruderMap` for compound objects, or those assignments need to be
   normalised into the filament list at load time. **Specifically affects
   Phase 2.1 normalize step.**

3. **Profile embed constraints.** OrcaSlicer's native embed reads
   `filament_colour` and `filament_settings_id` arrays of size N from
   `project_settings.config`. If the Phase 2 canonical list grows beyond the
   embed write surface (e.g. layer-tool synthetic expansion of N=20), the
   embed path needs to either truncate (data loss) or fold (semantic change).
   Need a clear contract: "the canonical list size IS the embed list size."
   B95's bumping logic disappears, but the maximum N becomes a hard
   architectural ceiling. Recommend documenting the ceiling at the embed
   layer and asserting it in the load-time normalize step.

4. **Per-plate paint-state collision with file-level filament list.** Buzz
   plate 7/8/9 each surface different paint state subsets. Today, the
   per-plate scan informs `detectedColors` which is plate-scoped; the
   skeleton suggests file-scoped `filaments` always. This is a deliberate
   trade — the user sees the file's full palette regardless of plate — but
   means the canonical list shows entries that aren't used on the current
   plate. UI choice: grey out unused-on-this-plate rows, or hide them?
   **Affects §1 Q1 and the Prepare-screen layout in §2.**

5. **`hasMultiExtruderAssignments` flag retention.** ProfileEmbedder uses this
   to decide preserve-vs-rebuild on `model_settings.config`. Post-refactor it
   may be unnecessary (the canonical list always carries per-object
   assignments) but eliminating it is a separate audit. Recommend keeping it
   as a deprecation candidate and flipping it dead in Phase 2.4.

6. **Recolouring vs rebinding — the index-stability invariant.** Desktop
   slicers' big advantage: filament 3 stays filament 3 even if the user
   changes its colour. The canonical list must preserve this — so
   `findClosestExtruder` is the *seed* for new mappings only and never
   re-runs on user-edited mappings. Risk: a future "auto-map all" button
   silently re-runs `findClosestExtruder` and clobbers the user's manual
   edits. **Add a per-file "user has edited the mapping" flag to gate any
   auto-map operation.**

---

## §6 Question-count audit — three structural surprises

The brief said "the 9 open questions might turn out to be 6 or 12; flag that."
Here's what closer reading suggests:

### S1. Q3 (persistence) is two questions disguised as one
After looking at Bambu's three-layer model (project state vs printer state
vs print-time reconciliation), Q3 collapses two separate decisions:

- **Q3a — project state persistence:** does the user's mapping decision survive
  closing and reopening the file?
- **Q3b — printer state read-back:** does the slicer know what's *currently
  loaded* in each physical slot (separate from what the user *thinks* is
  loaded via extruder presets)?

Q3a is what the menu in §1.Q3 actually answers. Q3b is the deferred-to-Phase-3
question. Recommend rewording Q3 in the design skeleton to make this split
explicit.

### S2. Q1 and Q4 overlap — could be one question with two failure modes
"What does the user see on first load?" (Q1) and "What does the user see
when presets are sparse?" (Q4) are arguably the same question with two
sub-cases (well-covered presets vs sparse). The recommended Q1.C and Q4.C
share the same "show banner only when degenerate" mechanism. They could be
merged into "Auto-map disclosure policy" without loss.

### S3. A 10th question lurks in the skeleton's section 4
The skeleton's "Open questions for the design author" includes
**"Print-time mapping: do we let the user change the mapping AFTER slicing,
between slice and send-to-printer?"** — this is a real UX question with
options of its own:

- **Option A:** No. Mapping is fully baked at slice time. (Today's behaviour.)
- **Option B:** Yes. The Print/Jobs screen has a "Re-map for this print"
  button that re-slices.
- **Option C:** Halfway. Mapping is baked into the G-code, but a
  print-time confirmation card lets the user abort and re-slice if the
  loaded spools don't match.

Recommend treating this as a 10th question after the user picks a concept;
the answer is straightforward (**A** for Phase 2; **C** is a Phase 3 stretch
once Moonraker spool readback exists). Mention exists here so the user
doesn't get blindsided in implementation review.

---

## Appendix — terminology cheat-sheet

For consistency in the implementation phase, the design skeleton's terms
adopted here:

| App term | Meaning | Maps to |
|---|---|---|
| **Filament** | One entry in the canonical list (1..N from the file) | Orca/Bambu "Filament", PrusaSlicer "Filament dropdown row" |
| **Slot / Extruder** | One of the 4 physical hardware extruders (E1-E4) | Orca multi-tool "extruder", Bambu "AMS slot", Prusa "extruder", Cura "extruder train" |
| **Mapping** | The 1↔1 from filament-index to physical slot | Bambu "AMS Mapping", others implicit by ordering |
| **Source** | Provenance of a `FilamentEntry` | New concept, no desktop analogue |
| **Canonical list** | The full per-file `List<FilamentEntry>` | Orca/Bambu "Filament panel", Prusa "Filament dropdowns" |

User-facing language should prefer **"Filament" / "Slot"** over
**"colour" / "extruder"** to:

- match the desktop precedent the user said is the model,
- avoid the colour-is-identity bug class (filament 3 stays filament 3 even
  when its colour changes),
- match the G-code's `T<index>` semantics (which are filament-indexed,
  not colour-indexed).

The `MultiColorDialog` filename and copy ("Multi-Color Detected") still work
in the canonical-list world; rename them only if Concept B (dialog) is picked
and the dialog grows responsibilities beyond colour mapping.

---

**End of design exploration.** Pick a concept, pick the per-question options
that suit, then move to Phase 2.1 (data model) per §4.
