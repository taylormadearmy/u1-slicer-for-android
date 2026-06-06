# Full-Spectrum M3 Phase A — Manual Pair-Mixer + Slot Widening

**Date:** 2026-06-06
**Status:** Design approved (UX choices via visual companion); ready for implementation plan
**Parent roadmap:** [`2026-05-26-full-spectrum-roadmap.md`](2026-05-26-full-spectrum-roadmap.md)
**Sibling specs (deferred):**
- [`2026-06-06-full-spectrum-m3-phase-b-smart-paint-mix-design.md`](2026-06-06-full-spectrum-m3-phase-b-smart-paint-mix-design.md)
- [`2026-06-06-full-spectrum-m3-phase-c-target-colour-picker-design.md`](2026-06-06-full-spectrum-m3-phase-c-target-colour-picker-design.md)

## Context

Stages 1 + 2 (merged to main as `2964b28`) bumped the engine to v2.3.3 + wired
`SliceConfig.mixedFilamentDefinitions` through to the engine config. The
[PeggyPalette test](https://example.com/peggy-2026-06-06) proved a real-world
full-spectrum 3MF slices correctly end-to-end. The user-facing gap: there is no
in-app way to *create* a mix recipe — users today need a pre-made 3MF (desktop
OrcaSlicer export, MakerWorld download, etc.).

Phase A closes that gap with the smallest UI that's actually useful: a manual
pair-mixer where the user picks two physical filaments + a blend ratio, and the
app constructs the engine recipe. Phase A is the foundation for Phases B (Smart
Paint integration) and C (target-colour picker) — both stack on the data-model
work in Phase A.

## Goal

Any U1 owner can create a mixed-filament slot inside the app, assign it to
model parts or regions, and slice a full-spectrum print **without ever needing
desktop OrcaSlicer or a pre-made 3MF**.

## Scope (in)

- **Slot data-model widening.** The per-triangle slot byte and the
  `AiRegion.slot: Int` field — currently constrained to `0..3` (E1–E4) — widen
  to carry virtual filament IDs (`≥4` = mix slots). Every site that reads/writes
  the slot value updates.
- **Kotlin mix-slot data model.** `MixedFilamentRow` data class +
  `MixedFilamentManager` that serializes to the engine's recipe-string format
  (consumed by `SliceConfig.mixedFilamentDefinitions`).
- **Three entry points** for creating a mix slot (per the user's "all three"
  placement choice):
  1. In the Filaments tab — a "+ Mix slot" row after the 4 physical filaments.
  2. On the Prepare screen — a "Mix slots" expandable section.
  3. From the slot picker (just-in-time, when assigning a region/part to a filament).
- **Create-mix dialog.** Single-screen layout (visual choice "B"): two filament
  pickers + ratio slider + visible distribution-mode toggle ("Layer alternation"
  vs "Same-layer dots") + live preview swatch + print-cost tag.
- **Sectioned slot picker** (visual choice "C"). Three labelled sections in
  every slot-assignment surface: **PHYSICAL** (E1–E4), **THIS PROJECT** (mix
  slots created in current project), **LIBRARY** (mix slots promoted across
  projects via a star button).
- **Persistence:** project-scoped mix slots via `SessionState` DataStore;
  library mix slots via a separate DataStore key. Both round-trip across app
  restart.
- **Edit / delete** existing mix slots: tap a slot to re-open the dialog
  pre-filled; "Delete" inside the dialog. Star toggles project ↔ library.
- **Recipe emission:** `MixedFilamentManager.serialize()` produces the engine's
  12-token format (`a,b,_,_,mix_b_percent,_,g,w,m2,d<dist>,o0,u<seq>`). Plumbed
  into `SliceConfig.mixedFilamentDefinitions` at slice time.

## Scope (out — deferred to Phase B/C or later)

- **Smart Paint AI suggesting mix slots** — Phase B.
- **Target-colour picker (RGB → pair + ratio)** — Phase C (needs M4).
- **Distribution modes beyond LayerCycle + SameLayerPointillism** — `Simple`, gradient,
  manual-pattern, per-row Local-Z, surface offsets, component bias are all
  encodable in the recipe but **not exposed in the dialog for v1**. The recipe
  serializer emits sensible defaults for these fields.
- **In-app library import/export** (e.g. "share my mix palette"). User-only library is
  enough for v1.
- **3MF embedding of the project's mix list on save** — slice still works because the
  recipe lives in `SliceConfig`, but if the user *saves* the model 3MF, the mix list
  isn't currently round-tripped to disk. Add later when 3MF save is otherwise wired.
- **Print-time estimate impact from mixes** — the existing time estimator already
  reflects total tool changes; we surface it but don't compute a "with vs without"
  delta in Phase A.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│ COMPOSE UI (3 entry points)                                          │
│  • FilamentScreen          + Mix slot row                            │
│  • PrepareScreen           Mix slots expandable section              │
│  • HighlightSlotPicker     + Mix chip in sectioned picker            │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ all 3 open the same dialog
                               ▼
                ┌──────────────────────────────┐
                │ CreateMixSlotDialog          │
                │  (Composable)                │
                └───────────────┬──────────────┘
                                │ user confirms
                                ▼
                ┌──────────────────────────────┐
                │ MixedFilamentManager         │
                │  (Kotlin, in SlicerViewModel)│
                │   - addMix(row)              │
                │   - editMix(id, row)         │
                │   - deleteMix(id)            │
                │   - promoteToLibrary(id)     │
                │   - serialize(): String      │
                └───────────────┬──────────────┘
                                │ DataStore
                                ▼
                ┌──────────────────────────────┐
                │ SessionState (project mixes) │
                │ AppSettings (library mixes)  │
                └──────────────────────────────┘

                                │ at slice time
                                ▼
                ┌──────────────────────────────┐
                │ SliceConfig                  │
                │   mixedFilamentDefinitions   │
                │   = manager.serialize()      │
                └──────────────────────────────┘
                                │
                                ▼
                  (Stage 2's existing wiring)
                  JNI → applyConfigToPrusa → engine
```

The slot picker reads from MixedFilamentManager's current project + library
lists to render the sectioned chip rows. Tapping a non-physical chip selects
the virtual filament ID for the slot byte (≥4).

## Data model

### `MixedFilamentRow` (Kotlin data class)

```kotlin
data class MixedFilamentRow(
    val id: Long,                    // stable per-mix UUID (millis-since-epoch + monotonic)
    val componentA: Int,             // 1-based physical filament index (1..extruderCount)
    val componentB: Int,             // 1-based, must differ from componentA
    val mixBPercent: Int,            // 0..100; the share of component B
    val distributionMode: MixDistributionMode,
    val label: String,               // auto-derived "E1+E3 @ 50%" or user-renamed
    val inLibrary: Boolean,          // false = project-scoped, true = persistent across projects
) {
    enum class MixDistributionMode { LAYER_CYCLE, SAME_LAYER_DOTS }
}
```

### `MixedFilamentManager` (Kotlin)

```kotlin
class MixedFilamentManager(
    private val sessionStateRepo: SessionStateRepository,
    private val appSettingsRepo: SettingsRepository,
) {
    val projectMixes: StateFlow<List<MixedFilamentRow>>      // mixes for the current loaded project
    val libraryMixes: StateFlow<List<MixedFilamentRow>>      // mixes saved across projects

    fun add(row: MixedFilamentRow)
    fun edit(id: Long, row: MixedFilamentRow)
    fun delete(id: Long)
    fun promoteToLibrary(id: Long)                            // copies project → library
    fun demoteFromLibrary(id: Long)                           // removes library copy

    /**
     * Engine recipe string. Virtual IDs are assigned in row order:
     * project mixes first (IDs num_physical+1 … num_physical+P),
     * library mixes after (IDs num_physical+P+1 … ).
     *
     * Output format (PR #375 12-token):
     *   "a,b,_,_,mix_b_percent,_,g,w,m2,d<dist>,o0,u<seq>;…"
     */
    fun serialize(numPhysicalFilaments: Int): String
}
```

### Slot byte widening

Today, several sites assume `slot: Int` lies in `0..3`. Audit + widen:

- `AiRegion.slot: Int` — already typed as `Int`, no code change needed at field level. **Update commentary** (currently says "0..3").
- `PaintedMeshWriter` — verify it doesn't clamp slot to `0..3`; if it does, widen.
- Per-triangle slot byte (`MeshData.extruderIndices: ByteArray`) — bytes hold values up to 255; no widening needed at byte level. **Update callers** that compare `< 4` or `>= 4` for clamping logic.
- Preview palette: `MeshData.recolor(extruderColors: List<Color>)` — must accept ≥ 4 entries (today supports 4). Extend the `extruderColors` list dynamically based on project + library mix count.
- Slot-reassignment chip row (currently 4 chips) — refactor into the sectioned picker (see UI section).

## UI components

### `MixSlotSwatch` (extend existing `MixedSlotSwatch.kt`)

Already implemented as a two-tone swatch (primary fill + diagonal triangle of
secondary). Reuse without changes. Optional addition: a small numeric badge
overlay showing the mix-B percent (e.g. "50") for context.

### `CreateMixSlotDialog` (new Composable)

Single-screen Material 3 dialog. Layout from visual choice B:

- **Title:** `"Create Mix Slot"` (or `"Edit Mix Slot"` when editing).
- **Component pickers:** two rows of physical-filament chips (E1–E4). One row labelled "Component A", one "Component B". Tap to select. Disable B's chip for whichever component A is currently using.
- **Ratio slider:** 0–100% with a default 50%. Live label: "50% E3 (Yellow)". Snap points at 25 / 33 / 50 / 67 / 75.
- **Distribution toggle:** two segmented buttons — "Layer alternation" (default) and "Same-layer dots".
- **Live preview:** a `MixSlotSwatch` rendering at component-A and component-B's current colours.
- **Cost tag:** small subtitle "Adds ~Nh print time" (heuristic: estimate based on
  mix rows; v1 uses a flat-rate guess derived from mix count × average layer time).
- **Actions:** `Cancel` (left), `Delete` (when editing), `Create`/`Save` (right). Right action disabled if `componentA == componentB`.

### Slot-picker section refactor

Existing `HighlightSlotPicker` becomes a `SectionedSlotPicker` with three named
sections (visual choice C). Each section renders chips horizontally; the
section is hidden if empty (e.g. no library mixes yet → no LIBRARY section).

- **PHYSICAL** — circular colour chips, label `E1`/`E2`/`E3`/`E4`.
- **THIS PROJECT** — `MixSlotSwatch` chips, label is the mix's auto-derived
  `"E1+E3 @ 50%"` or user-renamed string. Tap = select. Long-press = edit
  dialog. Trailing `+ Add` chip opens the dialog in create mode.
- **LIBRARY ★** — same chip style as project, distinguished only by section
  label. Library mixes only render if `componentA` and `componentB` are both
  ≤ `extruderCount` (otherwise hidden silently; v1 doesn't surface mismatched
  mixes).

The `SectionedSlotPicker` is used in:
- AiPaintResultScreen (per-region slot assignment)
- FilamentMappingDialog (canonical → physical mapping; mix slots act as
  alternative physical assignments)
- Any future surface needing slot assignment

### Filament-tab "+ Mix slot" row

Appended below the 4 physical filament rows in `FilamentScreen.kt`. Same dialog
as above. Mix slots created here default to project scope; the star toggle
inside the dialog promotes to library.

### Prepare-screen "Mix slots" section

Below the filament strip on the Prepare screen, an expandable section showing
mix-slot count + an "Add" affordance. Tapping the count expands to show the
list (project + library); tapping `+ Add` opens the dialog.

## Data flow

**Create:**
```
User taps + Mix slot (any entry point)
  → CreateMixSlotDialog opens
  → User picks A, B, ratio, mode
  → On Create:
    - MixedFilamentRow constructed
    - MixedFilamentManager.add(row)
    - SessionState DataStore updates (project mixes flow)
    - StateFlow recomposes SectionedSlotPicker
    - SliceConfig.mixedFilamentDefinitions recomputed (StateFlow → ViewModel)
```

**Assign mix to region/part:**
```
User taps a region in Smart Paint result
  → SectionedSlotPicker shown
  → User taps mix chip (e.g. M1, virtual ID 5)
  → AiRegion.slot = 5
  → PaintedMeshWriter writes slot byte 5 into the 3MF
  → Slice loads 3MF, sees slot 5 on triangles
  → Engine has virtual filament 5 from mixed_filament_definitions
  → G-code emits layer-alternated tool changes
```

**Edit:**
```
User long-presses an existing mix chip
  → CreateMixSlotDialog opens in Edit mode, pre-filled
  → On Save: MixedFilamentManager.edit(id, newRow)
  → If components changed: all triangle assignments referencing this slot
    silently inherit the new components (mix's slot ID is stable; only its
    composition changes).
```

**Promote to library:**
```
User taps ★ on a project mix
  → MixedFilamentManager.promoteToLibrary(id)
  → Row.inLibrary = true; appears in LIBRARY section of picker
  → If user demotes later: row returns to project section
```

**Filament swap (E1 changes from Blue to Green):**
```
User changes E1's profile in Filaments tab
  → Mix slot row's componentA index (1) is unchanged
  → MixSlotSwatch re-renders with the new colour at index 1
  → Slice still uses E1 = whatever-it-is-now in the engine
  → User sees the colour change immediately in all mix chips referencing E1
```

This is the "indices, not colours" model — v1 trades colour stability for
implementation simplicity. v2 (post-Phase-A user feedback) can add
intended-colour tracking if it turns out to matter.

## Error handling

- **A == B in dialog:** Create button disabled with subtitle "Pick two different filaments."
- **`mixBPercent` out of bounds:** Slider clamps to 0–100; no validation needed downstream.
- **Library mix references a slot > current extruder count** (e.g. mix uses E4 but project only has 2 extruders loaded): mix is silently hidden from the picker. Surfaced in v2 as "Requires 4 extruders" warning.
- **Mix slot count > 32:** Soft cap; show a toast and refuse to add. (PR #375's manager supports up to 64; we cap lower for UI sanity.)
- **Recipe parser rejection:** If the engine rejects our serialised recipe at slice time, the slice fails with an error message (existing slice-error path). Tested in instrumented tests.
- **DataStore write failure:** Standard repository error path (toast + retry); mix slot reverts to last-known-good state.

## Testing

### JVM unit tests (`app/src/test/`)

- `MixedFilamentRowTest` — construction, equality, copy.
- `MixedFilamentManagerTest`:
  - `add_appendsToProjectList`
  - `edit_replacesAtSameId_preservesPosition`
  - `delete_removesFromBothListsIfPresent`
  - `promoteToLibrary_copiesProjectToLibrary_andSetsFlag`
  - `demoteFromLibrary_removesLibraryCopy`
  - `serialize_emitsLegacyFormat_singleMix`
  - `serialize_emitsLegacyFormat_multipleMixes`
  - `serialize_assignsVirtualIds_sequentialFromNumPhysicalPlus1`
  - `serialize_libraryAndProject_concatenatedInOrder`
  - `serialize_layerAlternationVsSameLayerDots_emitsDifferentDistMode`
  - `serialize_skipsLibraryRowsReferencingMissingExtruders`
  - `autoLabel_format_componentAndPercent`
  - `roundtrip_throughEngineParser_recipeStringIsReplayable`
- `SessionStateMixSlotPersistenceTest` — DataStore round-trip (write → read → write again).
- `AppSettingsLibraryMixPersistenceTest` — same for library DataStore.
- `SliceConfigMixedFilamentDefinitionsWiringTest` — confirms SlicerViewModel
  emits `serialize()` output into `SliceConfig.mixedFilamentDefinitions` before
  invoking `slice()`.

### Compose UI tests

- `CreateMixSlotDialogTest`:
  - Renders all expected widgets
  - "Create" button disabled when A == B
  - Slider snap points fire correct mixBPercent values
  - Distribution toggle switches mode
  - Live preview swatch reflects current A/B/percent
- `SectionedSlotPickerTest`:
  - Three sections render correctly with appropriate counts
  - Sections hide when empty (no project mixes, no library mixes)
  - Tap selects a chip; long-press opens edit dialog
  - "+ Add" chip opens create dialog

### Instrumented tests (`app/src/androidTest/`)

- `mixedFilament_userCreatesPair_recipeReachesGcodeHeader` — end-to-end from
  dialog interaction to G-code config-dump (mirrors Stage 2's instrumented
  test but driven by UI).
- `mixedFilament_savedToLibrary_persistsAcrossModelLoad` — load model A,
  create + promote a mix, load model B, mix is in library section of B's
  picker.

## Native rebuild

**Not required.** All Phase A work is Kotlin + Compose. The engine (already
v2.3.3 with mix-filament support) and SAPIL marshalling (Stage 2's
`mixed_filament_definitions` field) are untouched. The existing `.so` shipped
in `2964b28` is final.

## Risks

- **`serialize()` format drift.** PR #375 added many fields (gradient, manual
  pattern, surface offsets, local-Z). We emit a subset and rely on the engine
  to default the rest. If upstream tightens the parser to require more fields,
  v1 recipes break. Mitigation: instrumented round-trip test fires on every
  CI run.
- **DataStore migration.** Adding two new keys (`projectMixes`,
  `libraryMixes`) — existing users have neither, so default-empty is correct.
  No migration code needed; just feature-detect.
- **Long-press conflict with existing slot-picker behaviour.** Today the slot
  picker may use long-press for something (selection state? deletion?). Audit
  `HighlightSlotPicker` before binding long-press to "edit mix"; switch to a
  trailing pencil icon if long-press is already taken.
- **Library mixes referencing non-existent slot indices.** Hidden silently in
  v1 — but the user may wonder where their mix went. Phase A ships with a
  small toast on Filaments tab load when N library mixes were hidden.

## Acceptance criteria

1. From any of the three entry points (Filaments tab, Prepare screen, slot
   picker), a user can create a mix slot in ≤ 4 taps.
2. The mix slot appears in the sectioned slot picker's THIS PROJECT section
   immediately.
3. Promoting a mix to library moves it to the LIBRARY section, and the mix
   appears in the next loaded project's LIBRARY section without re-creation.
4. Slicing with mix slot(s) assigned to model regions emits an engine recipe
   string in the G-code that contains the mix definitions (asserted via
   the existing config-dump grep pattern from Stage 2's instrumented test).
5. All existing JVM unit tests (1,481+) and instrumented tests (406+) still
   pass; new tests bring the total to ~1,500+ / ~410+.
6. Compose UI renders cleanly in dark mode (the only theme U1 Slicer ships).

## Open questions deferred to implementation

- Exact `mixBPercent` snap points — chosen at implementation time. v1 starts
  at `[0, 25, 33, 50, 67, 75, 100]`.
- Whether to show **all** library mixes in the picker by default vs filtering
  to "valid for current filaments" only — defaulting to "valid only" with a
  small "X hidden" toast on first load.
- Hand-off path for the future Phase B Smart Paint integration: how does AI
  reach the mix list to propose a mix? — see Phase B spec.
