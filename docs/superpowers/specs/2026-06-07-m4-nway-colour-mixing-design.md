# M4 — N-way colour mixing (2–4 components per mix)

**Date:** 2026-06-07
**Branch:** `feature/m4-nway-mixes` (worktree off `feature/prepare-ux-unified-selector` @ aae2749)
**Status:** Design — awaiting user review

---

## 1. Summary

Today a "mix slot" blends exactly **two** physical filaments at a ratio. M4 extends a mix to **2–4 components**, each with its own weight, blended by the OrcaSlicer engine layer-by-layer in proportion. Everywhere a mix currently appears — the create/edit dialog, the Prepare parts panel, the Smart Paint tree, the filament list, and the 3D preview — it scales to N colours.

The OrcaSlicer engine **already supports N-way blends end-to-end**; the app currently feeds it two empty token slots where the component-list and weights belong. M4 is therefore a **Kotlin + UI change with no native C++ change and no `.so` rebuild** (verified — see §3 and §8).

## 2. Goals / Non-goals

**Goals**
- Create/edit mixes of 2, 3, or 4 components (capped at the number of physical extruders, max 4).
- Per-component weights that always total 100%, defaulting to an even split for new components.
- A drag-to-rebalance proportional bar with tap-to-type exact percentages.
- N-segment mix swatches everywhere a mix is shown; N-colour blended preview colour.
- Fold in the three 2026-06-07 code-review carry-ins (§6).
- Backward compatibility: every saved 2-way mix keeps working, unchanged.

**Non-goals**
- Reverse colour-search ("target colour → which filaments + ratio") — separate later milestone.
- Auto-generating a palette of mixes — planned as the immediate fast-follow **M5** (§9), bundled into the same public release, not part of M4.
- Matching the engine's exact pigment-blend math on screen — the app keeps its own colour-blend approximation (§5), generalized to N. Engine-accurate on-screen preview is possible future work.
- Any change to regular (non-mix) printing.

## 3. Why no native change is needed (verified)

`MixedFilament.{hpp,cpp}` already implements the full N-way path:
- The `MixedFilament` struct carries `gradient_component_ids` + `gradient_component_weights` alongside the legacy 2-component fields.
- `encode/decode_gradient_component_ids` support up to `kMaxPhysicalFilaments = 64`; format is compact (`"123"`) or extended (`"1/12/3"`).
- `build_weighted_gradient_sequence(ids, weights)` builds a Bresenham-weighted per-layer tool cadence for N components (cycle capped at 48 layers).
- `blend_color_multi(...)` computes an N-colour blended preview colour.
- `load_custom_entries` / `parse_row_definition` parse the `g<ids>` and `w<weights>` tokens from the recipe string.
- **Slice-time tool selection** (`resolve()`, MixedFilament.cpp:2287-2296): fires the N-way path when `distribution_mode != Simple && gradient_ids.size() >= 3`, returning a tool from the weighted sequence. `ordered_perimeter_extruders` falls back to `resolve()`, so it inherits N-way behaviour.

**Distribution-mode trigger — confirmed safe.** Native enum: `LayerCycle=0, SameLayerPointillisme=1, Simple=2`. The Kotlin serializer only emits mode `0` or `1`, never `2`, so `use_simple_mode` is always false and the N-way branch activates as soon as 3+ ids are present.

**The single dormant seam:** `MixedFilamentManager.serializeRow` currently emits bare `g` and `w` (empty). Populating them is what wakes the path.

## 4. Data model & serialization

### 4.1 `MixedFilamentRow`
Component list becomes the **single source of truth**:
```kotlin
data class MixedFilamentRow(
    val id: Long,
    val components: List<Int>,   // 2..4 entries, 1-based physical filament index, distinct
    val weights: List<Int>,      // same size as components, each >0, sum == 100
    val distributionMode: MixDistributionMode,
    val label: String,
    val inLibrary: Boolean,
) {
    // Derived read-only views for not-yet-generalized 2-way consumers (NOT stored):
    val componentA: Int get() = components.getOrElse(0) { 1 }
    val componentB: Int get() = components.getOrElse(1) { componentA }
    val mixBPercent: Int get() = weights.getOrElse(1) { 0 }
}
```
Invariants enforced on construction/edit: `2 <= components.size <= maxComponents`, components distinct, `weights.size == components.size`, each weight `>= 1`, `weights.sum() == 100`. `maxComponents = min(4, numPhysicalLoaded)`.

### 4.2 JSON schema & migration
New canonical fields `components: [int]` + `weights: [int]` added to the SessionState and SettingsRepository (`encode/decodeLibraryMixes`) serializers.
- **Write:** always the new list form.
- **Read:** if `components`/`weights` are present, use them. If absent (legacy row), reconstruct from `componentA`/`componentB`/`mixBPercent`: `components = [A, B]`, `weights = [100 - mixBPercent, mixBPercent]`. Round-trip and legacy-migration covered by unit tests.

### 4.3 Recipe serialization
`serializeRow` populates the gradient tokens for **all** mixes (2-way included, for one consistent path):
- `g<encoded ids>` via the compact/extended encoder mirroring the native format (`g123`, `g1/12/3`).
- `w<weights>` joined by `/` (`w50/30/20`).
The legacy `a,b,mix_b_percent` fields stay populated (= `components[0]`, `components[1]`, `weights[1]`) so the engine's 2-way fallback paths remain valid; the engine prefers the N-way path when ids ≥ 3.

### 4.4 Label
Generalize the auto-label from `"E1+E3 @ 50%"` to list form, e.g. `"E1+E2+E3"` (weights visible in the editor/swatch rather than crammed into the label). User-renamed labels preserved.

## 5. Create/edit dialog (drag-bar + tap-to-type)

`CreateMixSlotDialog` reworked:
- **Proportional bar** with up to 4 segments sized to weights, in component order. Draggable dividers between segments rebalance the two adjacent weights (others unchanged) by eye.
- **Tap a segment's %** → inline numeric entry; on commit that component locks to the typed value and the *other* components scale proportionally to refill the remaining budget so the total stays 100%.
- **Colour chips** below the bar: tap a chip to change which physical filament that component is (picker limited to loaded filaments, excluding already-used ones).
- **"+ Add"** appends a component at an even share (existing components trim proportionally); disabled at `maxComponents`.
- **Remove** a component (each removable down to a floor of 2); a component can't be dragged/typed to 0% — removal is the way out.
- Existing **distribution-mode toggle** (Layer cycle / Same-layer dots) retained.
- Editing an existing mix pre-loads its components/weights.

Rebalancing math lives in a **pure, unit-tested helper** (e.g. `MixWeights`) so the gesture/typing UI stays thin: `rebalanceAfterDrag`, `rebalanceAfterType`, `addEven`, `remove`, all guaranteeing the sum-100 + min-1 invariants.

## 6. The slot-id collision fix + carry-ins

### 6.1 #2 — slot-id collision (correctness)
Mix slot ids must be based on `maxOf(numPhysical, canonicalFilamentCount)` consistently, so a 3MF declaring more than 4 canonical filaments cannot collide a mix slot onto a real filament slot. Apply in **all four** slot-id sites found in exploration:
- `PartsPanel` `FilamentChooserDialog` mix offset (`numPhysical + idx + 1`).
- `SlicerViewModel.startSlicing` `mixPhysicalBase`.
- `FilamentMixChipRow.mixSlotId(index, numPhysical)`.
- `AiPaintTreeRow` mix-chip `slot = numPhysical + idx`.
Reproduce with a unit test (>4 canonical filaments → mix slot must not equal any physical slot). The preview palette is already floored at palette size.

### 6.2 #3 — `FilamentMixChipRow`
Currently uncalled. **Adopt it as the single shared N-way mix selector** (it already encodes the slot-id math) and delete the duplicated inline chip code in the per-surface selectors; if adoption fights the per-surface layouts, delete the component outright. Either way: no orphan code remains.

### 6.3 #4 / #5 — Smart Paint mix-edit
Wire the dead `onEditMix`: long-press a mix chip in the Smart Paint tree, and tap a mix-leaf's leading swatch, both open the (N-way) edit dialog. Structural/behavioural test for the wiring.

## 7. N-segment swatch & preview

- `MixedSlotSwatch` generalized from the 2-way left/right split to **N proportional segments** (weights, component order). The existing `secondaryFraction == null` "diagonal stripe" fallback (mixed-across-slots) is preserved for the no-single-percentage case.
- All swatch call sites (dialog preview, parts panel, Smart Paint chips, filament list) pass the component+weight lists.
- **Preview colour:** generalize `ColourMatch.naiveBlendHex` (2-colour sRGB lerp) to an N-colour weighted blend. Used for the 3D-preview slot palette and swatch tint, exactly as today, just for N. (Not the engine's pigment math — see Non-goals.)

## 8. Verification — the gate

**Step 0 — slice spike (before any UI work).** Construct a recipe with 3 components + non-even weights, slice a real model through the bundled engine binary, and **count G-code tool changes** — assert all three tools appear and cycle in rough proportion to the weights. This proves the engine path end-to-end through the app's actual code.

**Contingency (num_physical).** Per the prior investigation, the engine can mis-treat a mix as a plain filament if its physical-filament count is inflated (`num_physical` must remain 4). The prepare-ux HEAD includes a related "renders blend in Prepare" fix, so this is likely already resolved — but the spike is the proof. **If the spike shows no blending,** the `num_physical` correctness fix enters M4 scope (Kotlin first — avoid inflating `filament_colour`/`filament_count`; native only if unavoidable, following the NDK-26 / Clang-17 / Release / ~20MB / JNI-symbol checklist in CLAUDE.md). Outcome reported before building UI either way.

**Red-green TDD** (per CLAUDE.md):
- *Unit:* row schema round-trip + legacy 2-way migration; `serializeRow` emits correct `g`/`w` tokens for 2/3/4 components; `MixWeights` rebalance/add/remove invariants; N-colour `naiveBlendHex`; slot-id collision (#2).
- *Instrumented:* 3-colour and 4-colour slices asserting tool cadence and bounds; Smart Paint `onEditMix` wiring; N-segment swatch recolor.
- Extend existing mix tests rather than duplicate (`MixedFilamentManagerTest`, `MixSlotPaintRoundTripTest`, `MixSlotSliceIntegrationTest`, swatch/parts tests). Note: `MixSlotSliceIntegrationTest` currently only checks the recipe string appears — the new spike test is the first to actually assert blending.

**Device E2E** at the end via the confidence-check / smoke flow. **No physical prints** — upload-only ("Map & Upload" / "Upload Only") if any send path is exercised.

## 9. Future work (captured, not in M4)

- **M5 — auto-generate a mix palette** (immediate fast-follow, same public release). Open design questions for its own short brainstorm: which set (pairwise 50/50, multiple ratios, tri-blends), default count, and *how to avoid flooding the mix list* (a "generate palette" action with preview + sensible default, not a dump). Shares the "compute blends from the palette" machinery with reverse-search.
- **Reverse colour-search** (later milestone): target colour → components + ratio.
- **Engine-accurate on-screen preview**: optionally replace the Kotlin sRGB blend with the engine's pigment blend (would need a small JNI accessor).

## 10. Branch & release

- All work in `D:\projects\u1-slicer-for-android`, worktree `feature/m4-nway-mixes` off `feature/prepare-ux-unified-selector` @ aae2749. prepare-ux stays unmerged.
- New worktree inherits prepare-ux's un-initialized `orcaslicer` submodule — acceptable (no native change expected). Init it only if the §8 contingency triggers.
- When M4 is complete: one full instrumented-test gate, then **merge prepare-ux + M4 together as a single bundle**. M5 lands right after and ships in the **same public release**.
- No GitHub release/public tag without explicit user authorization. Run `gh auth switch -u taylormadearmy` before any push/tag. Keep BACKLOG ↔ GitHub-issue parity for M4/M5 entries.
