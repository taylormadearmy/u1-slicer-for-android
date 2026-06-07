# Prepare UX Consolidation — One Filament-or-Mix Selector Everywhere

**Date:** 2026-06-07
**Status:** Design approved by Kevin 2026-06-07 (visual brainstorm). Ready for implementation plan.
**Context:** After M3 Phase B (full-spectrum mixes) landed, the Prepare / Smart Paint UI accreted
several filament/mix controls that are redundant, inconsistent, and (in Smart Paint) cover the
model. This consolidates them into one mix-aware chip selector reused everywhere. **Gate: this
ships before any public release of full-spectrum** (the feature is "hard to use" until it lands).
Base: `main` @ `a4038f6` (Phase B blend fix + prime-tower-toggle removal merged).

## Issues (from on-device testing, screenshots in `My Drive/logs` 2026-06-07)

1. **STL Prepare shows two "Filament" cards** — one with material/temp, a second "Filament" card
   captioned "Slot mapping happens when you tap Send". Two things both called "Filament".
2. **3MF Prepare separates "Filaments (N)" and "Mix slots (M)"** into two distinct cards.
3. **Object/part filament assigner offers only physical filaments** (E1–E4) — no mixes.
4. **Smart Paint per-region assignment uses a "Move to slot" overlay that covers the model**
   (the Phase B `SectionedSlotPicker` overlay). The persistent per-row chip selector already
   exists; the overlay is redundant and obscures the preview.

## The unifying design

One reusable **filament-or-mix chip selector**: a horizontal row of chips =
`{physical filaments: colour circle, labelled E1..En}` + `{active mixes: two-tone MixedSlotSwatch,
labelled by the mix}` + a trailing **`+`** chip (create mix → Phase A's `CreateMixSlotDialog`).
The current selection is ticked. **Horizontal scroll** on overflow (Kevin's choice). Tapping a
chip assigns. This one control replaces the per-surface variants.

Used in three places:
- **Prepare → combined Filaments card** (as a list, with material/temp on physical rows).
- **Object/part assigner** (#3) — one selector per object/part row.
- **Smart Paint** (#4) — one selector per region row, plus the Paint/Lasso **Brush** row.

## Components

### 1. Combined "Filaments" card (Prepare) — issues #1 + #2
One card, identical for STL and 3MF:
- Header: **Filaments (N)** + a **`+`** (add mix) + expand chevron.
- One caption: *"Tap a chip to change material. Slots are mapped to your spools at Send."*
  (folds the two old captions into one).
- **Physical rows:** colour swatch · `E{i} · Filament {i}` · material chip (tap to change) · temp.
- **"Mixes" subsection** (divider label; Kevin chose subsection over interleaved): two-tone
  swatch · auto-label (`E1+E2 @ 50%`) · edit (long-press / pencil). `+ Add mix slot` row.
- **Removes** the duplicate STL "Filament" card and the separate "Mix slots" card.
- Prime-tower toggle is NOT here (already removed; it's a slice setting → Slice Settings + Settings).

### 2. `FilamentMixChipRow` — shared mix-aware per-row chip selector (NEW composable)
The single control behind #3 and #4 (and the chip semantics of the Filaments card). Generalises
the existing `SectionedSlotPicker` chips + `SlotPaletteRow` into one composable:
```
FilamentMixChipRow(
    physicalColours: List<Color>, physicalLabels: List<String>,
    mixes: List<MixedFilamentRow>,                 // active mixes (MixSlotOrdering order)
    selectedSlot: Int,                              // current assignment; -1 = none
    onSelect: (slot: Int) -> Unit,                  // slot id: physical idx, or numPhysical+mixIdx
    onCreateMix: () -> Unit,                         // the "+" → CreateMixSlotDialog
    onEditMix: (MixedFilamentRow) -> Unit = {},
)
```
- Physical chips (circle) → slot `idx`. Mix chips (two-tone) → `numPhysical + mixIndex`
  (the Phase B slot-id invariant via `MixSlotOrdering`). `+` chip → `onCreateMix`.
- Current slot ticked. Row is horizontally scrollable.
- `numPhysical = SegmentationCascade.TARGET_SLOTS` (4) — the Phase B fixed-physical-base convention.

### 3. Object/part assigner uses `FilamentMixChipRow` — issue #3
Wherever an object/part is assigned a filament (the multi-object / "Reassign filaments" surface),
render `FilamentMixChipRow` per object so a mix is selectable. Assigning a mix to a whole object
writes the mix's virtual slot through the same per-object → slot path physical assignment uses.

### 4. Smart Paint: delete the overlay, use `FilamentMixChipRow` — issue #4
- **Remove** the per-region `SectionedSlotPicker` overlay (added in Phase B Task 6) that covers
  the viewer. The model stays fully visible.
- Each region row uses `FilamentMixChipRow` (current ticked; tap to assign). Selecting a region
  (list row tap **or** model tap) highlights it but assignment is always "tap the row's chip" —
  so it works identically from the list and from the model.
- The **Brush** row (Paint/Lasso active slot, today `SlotPaletteRow`) becomes `FilamentMixChipRow`
  too, gaining the **`+`** (Kevin's annotation) so you can create + paint with a mix.

## Out of scope
- AI/auto colour-matching (Phase B C4 PART B) — separate, still deferred.
- Any new mix capability — this only surfaces the existing mix model consistently.
- Native engine — none touched (pure Compose/Kotlin).

## Reuse / implementation notes
- Consolidate `SectionedSlotPicker` (PHYSICAL/THIS PROJECT/LIBRARY sectioned variant) and
  `SlotPaletteRow` into `FilamentMixChipRow`. The sectioned variant may be retired if the chip
  row replaces all its uses; confirm no other caller before deleting.
- Mix display colour = naive RGB blend (`ColourMatch.naiveBlendHex`), as in Phase B.
- `HighlightSlotPicker` is already orphaned after Phase B; remove it if still unused.

## Testing
- **Unit / source-grep guards:** combined Filaments card renders physical + mixes + `+`;
  `FilamentMixChipRow` emits the correct slot id for a mix chip (`numPhysical+idx`) and is
  horizontally scrollable; Smart Paint result screen no longer references the covering overlay;
  object assigner renders `FilamentMixChipRow`.
- **Behavioural where possible:** chip-id → slot mapping matches `MixSlotOrdering`.
- **Regression:** with zero mixes, every surface renders exactly the pre-existing physical-only
  chips (no visual/behaviour change); per-region + per-object physical assignment unchanged.
- **Device smoke (E2E):** load STL → one Filaments card; load dual-colour 3MF → one card with
  Filaments + Mixes; Smart Paint → no overlay, assign a region (from list and from model) to a
  mix; object model → assign a part to a mix; each then slices.

## Acceptance criteria
1. STL **and** 3MF Prepare show a single **Filaments** card (physical + mixes) — no duplicate
   "Filament" card, no separate "Mix slots" card.
2. The object/part assigner can assign a mix to an object.
3. Smart Paint: no control covers the model; per-region **and** brush assignment use the
   mix-aware chip row; the `+` opens the create-mix dialog.
4. A single `FilamentMixChipRow` composable backs all three surfaces.
5. No regression to physical-only / no-mix flows; existing tests stay green.
6. Renders cleanly in dark mode (the only theme).
