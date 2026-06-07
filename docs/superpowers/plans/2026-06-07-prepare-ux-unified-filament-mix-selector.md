# Prepare UX Consolidation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use `- [ ]` checkboxes.

**Goal:** One reusable mix-aware chip selector (`FilamentMixChipRow`) used everywhere a filament is chosen, plus a single combined Filaments card — resolving the 4 on-device UX issues. Pure Compose/Kotlin, no engine changes.

**Architecture:** Build the shared `FilamentMixChipRow` first, then swap it into each surface (Smart Paint, object assigner, Filaments card), delete the covering overlay + the now-dead pickers. Base: `main` @ `a4038f6` (worktree `feature/prepare-ux-unified-selector`).

**Spec:** [`docs/superpowers/specs/2026-06-07-prepare-ux-unified-filament-mix-selector-design.md`](../specs/2026-06-07-prepare-ux-unified-filament-mix-selector-design.md)

**Invariant (from Phase B):** a mix chip's slot id = `numPhysical + mixIndex` where `numPhysical = SegmentationCascade.TARGET_SLOTS` (4) and mix order = `MixSlotOrdering.activeOrder(...)`. Every surface must keep this so painted/assigned mix ids match the engine recipe.

**Working dir:** `D:/projects/u1-slicer-for-android/.claude/worktrees/prepare-ux` · device `43211JEKB16931` (`ANDROID_SERIAL`) · never start a physical print.

---

## File structure
- **New:** `app/src/main/java/com/u1/slicer/ui/FilamentMixChipRow.kt` — the shared selector.
- **New tests:** `app/src/test/java/com/u1/slicer/ui/FilamentMixChipRowTest.kt`, plus source-grep guards per surface.
- **Modify:** `AiPaintResultScreen.kt` (remove overlay; brush + region rows use the row), `AiPaintTreeRow.kt` (per-region chips), `MainActivity.kt` (combined Filaments card; remove duplicate card; `FilamentMappingDialog` call), `ui/FilamentMappingDialog.kt` (#3).
- **Retire (after callers gone):** `ui/SectionedSlotPicker.kt`, `ui/HighlightSlotPicker.kt`.

---

## Task 1: `FilamentMixChipRow` shared composable + slot-id test

**Files:** Create `FilamentMixChipRow.kt`, `FilamentMixChipRowTest.kt`.

- [ ] **Step 1 — failing test** (`FilamentMixChipRowTest.kt`): a pure helper for the slot-id mapping the row uses, so the invariant is unit-tested (not just Compose):
```kotlin
package com.u1.slicer.ui
import com.u1.slicer.data.MixedFilamentRow
import com.u1.slicer.data.MixedFilamentRow.MixDistributionMode.LAYER_CYCLE
import org.junit.Assert.assertEquals
import org.junit.Test
class FilamentMixChipRowTest {
    private fun mix(id: Long, a: Int, b: Int) = MixedFilamentRow(id, a, b, 50, LAYER_CYCLE, "E$a+E$b @ 50%", false)
    @Test fun physicalChipSlotIds_areTheirIndex() {
        assertEquals(0, FilamentMixChipRow.physicalSlotId(0))
        assertEquals(3, FilamentMixChipRow.physicalSlotId(3))
    }
    @Test fun mixChipSlotId_isNumPhysicalPlusIndex() {
        val mixes = listOf(mix(1,1,2), mix(2,1,3))
        assertEquals(4, FilamentMixChipRow.mixSlotId(0, numPhysical = 4))
        assertEquals(5, FilamentMixChipRow.mixSlotId(1, numPhysical = 4))
    }
}
```
- [ ] **Step 2 — run, verify FAIL.** `./gradlew testDebugUnitTest --tests "com.u1.slicer.ui.FilamentMixChipRowTest"`
- [ ] **Step 3 — implement.** Read `SectionedSlotPicker.kt` + `SlotPaletteRow` (in `AiPaintViewer.kt`) for the existing chip rendering (physical circle, `MixedSlotSwatch` two-tone, selection ring). Create `FilamentMixChipRow`:
```kotlin
@Composable
fun FilamentMixChipRow(
    physicalColours: List<Color>, physicalLabels: List<String>,
    mixes: List<MixedFilamentRow>,
    selectedSlot: Int,
    onSelect: (slot: Int) -> Unit,
    onCreateMix: () -> Unit,
    onEditMix: (MixedFilamentRow) -> Unit = {},
    modifier: Modifier = Modifier,
)  // a horizontally-scrollable Row (Modifier.horizontalScroll): physical circle chips
   // (slot = index, label E{i}), then mix two-tone chips (slot = numPhysical + idx, long-press = onEditMix),
   // then a dashed "+" chip = onCreateMix. The selectedSlot chip shows a primary-colour ring + tick.
   // companion: fun physicalSlotId(idx) = idx; fun mixSlotId(idx, numPhysical) = numPhysical + idx
```
numPhysical = `physicalColours.size` (caller passes `slotPalette.take(TARGET_SLOTS)` / the physical colours). Reuse `MixedSlotSwatch`. Mix display colour for the swatch = `physicalColours[componentA-1]` / `[componentB-1]` (same indexing as `SectionedSlotPicker.MixSlotChip`).
- [ ] **Step 4 — run, verify PASS.**
- [ ] **Step 5 — commit.** `feat(ux): FilamentMixChipRow — shared mix-aware chip selector (physical+mix+add, h-scroll)`

---

## Task 2: Smart Paint — delete overlay, use `FilamentMixChipRow` (issue #4)

**Files:** Modify `AiPaintResultScreen.kt`, `AiPaintTreeRow.kt`. Test: `app/src/test/java/com/u1/slicer/ui/SmartPaintNoOverlayTest.kt` (source-grep).

- [ ] **Step 1 — failing source-grep test:** assert `AiPaintResultScreen.kt` no longer references `SectionedSlotPicker(` and DOES reference `FilamentMixChipRow(`. (Multi-candidate path helper like existing source-grep tests.)
- [ ] **Step 2 — run → FAIL.**
- [ ] **Step 3 — implement.**
  - READ `AiPaintResultScreen.kt` lines ~280-460 (the `if (highlightedNode != null) { ... SectionedSlotPicker ... }` overlay block at ~288-345, the `SlotPaletteRow(...)` brush at ~431, and the `AiPaintTree(...)` call at ~451) and `AiPaintTreeRow.kt` (the per-region row).
  - **Delete the `highlightedNode` overlay block** (the SectionedSlotPicker overlay that covers the viewer). Keep `onHighlightComponent` highlight behaviour (model tap / row tap still highlights), but no popup.
  - **Per-region chips:** ensure each region row renders a `FilamentMixChipRow` (physical + mixes + "+", `selectedSlot = region.slot`, `onSelect = { onSetSegmentSlot(region.id, it) }`). If `AiPaintTreeRow` already shows per-row physical chips, replace that chip group with `FilamentMixChipRow`; else add it. Thread `projectMixes`/`libraryMixes`/`numPhysical`/`onCreateMix`/`onEditMix` down (already partly threaded from Phase B).
  - **Brush row:** replace `SlotPaletteRow(...)` with `FilamentMixChipRow(...)` for the Paint/Lasso active slot (`selectedSlot = paintActiveRegion`, `onSelect = { paintActiveRegion = it }`), giving the brush mixes + "+".
  - Wire `onCreateMix` to the existing `CreateMixSlotDialog` already hosted in NavGraph (Phase B).
- [ ] **Step 4 — test → PASS; `./gradlew compileDebugKotlin` → SUCCESS.**
- [ ] **Step 5 — commit.** `feat(ux/#4): Smart Paint uses FilamentMixChipRow per-region + brush; remove covering overlay`

---

## Task 3: Combined "Filaments" card on Prepare (issues #1 + #2)

**Files:** Modify `MainActivity.kt`. Test: `app/src/test/java/com/u1/slicer/ui/CombinedFilamentsCardTest.kt` (source-grep).

- [ ] **Step 1 — READ** `MainActivity.kt`: the filaments card (~4240-4280, header "Filaments (N)"/"Filament" + caption), the **second "Filament" card** (~5075-5090, "Slot mapping happens when you tap Send →") which is the STL duplicate (#1), and `PrepareMixSlotsSection` (def ~4819, called ~1780). Map which composable renders on STL vs 3MF and how they're invoked.
- [ ] **Step 2 — failing source-grep test:** assert there is exactly one "Slot mapping" caption path and that the filaments card region references mixes (e.g. `PrepareMixSlotsSection` is folded in or the card lists mixes). Concretely: assert the standalone duplicate card text block is gone and the Mixes content lives within the filaments card.
- [ ] **Step 3 — implement.** Merge into ONE card:
  - Keep the primary filaments card (physical rows: swatch · `E{i} · Filament {i}` · material chip · temp). Use one caption: "Tap a chip to change material. Slots are mapped to your spools at Send."
  - **Remove the duplicate "Filament" card** (~5080 block) — its only unique content is the "slot mapping at Send" note, now in the single caption.
  - **Fold `PrepareMixSlotsSection`** into the same card as a **"Mixes" subsection** (divider label + mix rows via `MixedSlotSwatch` + `+ Add mix`), instead of a separate top-level card. Reuse `PrepareMixSlotsSection`'s add/edit wiring (`mixedFilamentManager`); just render it inside the filaments card after a "Mixes" divider rather than as its own card. (If cleanly extracting is hard, keep `PrepareMixSlotsSection` as a composable but render it inside the filaments card container and drop its standalone card chrome.)
  - Card header gets a `+` (add mix) shortcut = open `CreateMixSlotDialog`.
- [ ] **Step 4 — test → PASS; compile → SUCCESS.**
- [ ] **Step 5 — commit.** `feat(ux/#1+#2): single Filaments card (physical + Mixes subsection); drop duplicate STL card`

---

## Task 4: Object/part assigner offers mixes (issue #3)

**Files:** Modify `ui/FilamentMappingDialog.kt` (+ its call in `MainActivity.kt` ~889 if params change). Test: `app/src/test/java/com/u1/slicer/ui/FilamentMappingMixTest.kt` (source-grep).

- [ ] **Step 1 — READ** `ui/FilamentMappingDialog.kt` — how it renders the per-object/per-colour → slot picker today (physical only). Identify the per-row picker.
- [ ] **Step 2 — failing source-grep test:** assert `FilamentMappingDialog.kt` references `FilamentMixChipRow` (or otherwise offers mix slots).
- [ ] **Step 3 — implement.** Replace the per-object physical-only picker with `FilamentMixChipRow` (physical + mixes + "+"). Thread `projectMixes`/`libraryMixes`/`numPhysical`/`onCreateMix` into the dialog (from `mixedFilamentManager` at the call site). Selecting a mix sets that object's slot to `numPhysical+idx` through the same mapping path physical assignment uses (verify the mapping accepts ids ≥ numPhysical — it's an `Int` slot; the slice already handles mix ids).
- [ ] **Step 4 — test → PASS; compile → SUCCESS.**
- [ ] **Step 5 — commit.** `feat(ux/#3): object/part assigner (FilamentMappingDialog) offers mix slots`

---

## Task 5: Retire dead pickers + regression guard

**Files:** Delete `ui/SectionedSlotPicker.kt`, `ui/HighlightSlotPicker.kt` (if no remaining callers). Update/keep their tests.

- [ ] **Step 1 — confirm no callers:** `grep -rn "SectionedSlotPicker(\|HighlightSlotPicker(" app/src/main` → must be empty after Tasks 2-4. If any remain, fix the caller first.
- [ ] **Step 2 — delete the two files** + remove now-orphaned tests that test ONLY those composables (e.g. `SectionedPickerWiringTest` if it only guarded the old overlay — replace its intent with the new `SmartPaintNoOverlayTest`). Do NOT delete tests that still assert live behaviour.
- [ ] **Step 3 — regression:** `./gradlew testDebugUnitTest` (full) → green. Update any test referencing the deleted composables.
- [ ] **Step 4 — compile + commit.** `refactor(ux): retire SectionedSlotPicker + HighlightSlotPicker (replaced by FilamentMixChipRow)`

---

## Task 6: Verify on device + combined APK

- [ ] **Step 1 — full JVM:** `./gradlew testDebugUnitTest` → green.
- [ ] **Step 2 — targeted instrumented** (the surfaces that touch slicing): `ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.slicing.MixSlotRealLoadPathBlendTest"` (still green — UI change must not break the blend path) + any AiPaint instrumented tests.
- [ ] **Step 3 — device smoke (manual / E2E subagent):** load STL → one Filaments card; dual-colour 3MF → one card with Filaments + Mixes; Smart Paint → no overlay, assign a region (list + model) to a mix, paint with a mix via brush; object model → assign a part to a mix; each slices. Use Map & Upload only — never Map & Print.
- [ ] **Step 4 — build combined APK** (`assembleRelease`) → `G:/My Drive/claude/`, report the path.

---

## Self-review
- **Coverage:** #1+#2→Task 3, #3→Task 4, #4→Task 2, shared control→Task 1, cleanup→Task 5, verify→Task 6. All spec acceptance criteria mapped.
- **Invariant:** Task 1 unit-tests the slot-id mapping; every surface passes `numPhysical=TARGET_SLOTS` + `MixSlotOrdering` order.
- **No-regression:** with zero mixes, `FilamentMixChipRow` renders exactly the physical chips (Task 1), and the full suite + the blend instrumented test gate it (Tasks 5-6).
- **Compose wiring caveat:** Tasks 2-4 name the exact files/line-regions to read first; insertion is done in-situ by the implementer (same approach as Phase B's UI tasks).
