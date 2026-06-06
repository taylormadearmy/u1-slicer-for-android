# Full-Spectrum M3 Phase C — Target-Colour Picker + M4 Integration

**Date:** 2026-06-06
**Status:** Architecture-level design (detail filled in when Phases A + B ship)
**Parent:** [`2026-05-26-full-spectrum-roadmap.md`](2026-05-26-full-spectrum-roadmap.md)
**Depends on:** [Phase A](2026-06-06-full-spectrum-m3-phase-a-manual-pair-mixer-design.md) and **M4** (Prusa fdm-mixer integration; see below)
**Sibling:** [Phase B](2026-06-06-full-spectrum-m3-phase-b-smart-paint-mix-design.md)

## Goal

User picks an RGB target colour; app finds the best (componentA, componentB,
mixBPercent) tuple that approximates it from the currently loaded physical
filaments. The pair-mixer dialog from Phase A gains a "Target colour" tab as
an alternative to the manual A/B/% picker.

## M4 integration (a prerequisite, not a sibling)

`prusa-fdm-mixer` (MIT C++17 library at `github.com/prusa3d/prusa-fdm-mixer`)
predicts the perceived colour of a layer-cycle blend of two filaments. Without
it, naive RGB interpolation (Phase B v1) is a poor predictor — Phase C requires
the accurate version.

Integration plan:

1. **Bundle as a submodule.** Add at `app/src/main/cpp/extern/prusa-fdm-mixer`,
   build static lib at C++ stage alongside libslic3r.
2. **JNI wrapper.** Add to `slicer_wrapper.cpp`:
   - `predictBlendedColor(aRgb: Int, bRgb: Int, mixBPercent: Int): Int`
     → calls fdm-mixer's `predict()` C++17 entry point, returns ARGB.
   - `findBestBlend(targetRgb: Int, candidatePairs: IntArray): IntArray`
     → returns `[bestA_index, bestB_index, bestMixPercent]`. Brute-force search
     over `candidatePairs` × `mixBPercent ∈ [0, 5, 10, ..., 100]` (21 ratios);
     pick lowest ΔE to target. Sub-100ms for 4-extruder, 6-pair, 21-ratio
     search.
3. **Native rebuild required.** New `.so` shipped with this phase.

## Architecture

```
CreateMixSlotDialog (from Phase A)
  └── tabs: [ Pair + Ratio ] | [ Target Colour ]   (new tab)

[Target Colour] tab:
  ┌─────────────────────────────────────────┐
  │ HSV picker (existing HsvColorPicker)     │
  │   → targetRgb                            │
  └───────────────┬─────────────────────────┘
                  │ on confirm
                  ▼
  ┌─────────────────────────────────────────┐
  │ NativeLibrary.findBestBlend(             │
  │     targetRgb, candidatePairs)           │
  │   → (bestA, bestB, bestMixPercent)       │
  └───────────────┬─────────────────────────┘
                  │
                  ▼
  ┌─────────────────────────────────────────┐
  │ predictedColor = predictBlendedColor(    │
  │     A_rgb, B_rgb, bestMixPercent)        │
  │   → shown as swatch next to target       │
  │     (so user sees "your target vs        │
  │      best the printer can hit")          │
  └─────────────────────────────────────────┘
```

## UI

- **Dialog tabs:** "Pair + Ratio" (Phase A's view), "Target Colour" (new).
- **HSV picker:** reuse existing `HsvColorPicker` (already shipped per
  `FilamentColorEditDialog.kt`).
- **Side-by-side swatches:** target colour | best-blend prediction. With a small
  ΔE label ("ΔE 4.2 — close match") so user knows quality.
- **Hand-off to Pair + Ratio:** after target → result, the dialog auto-switches
  back to the Pair + Ratio tab pre-filled with the found A, B, percent. User
  can fine-tune or just Create.

## Components

- **`prusa-fdm-mixer` submodule** (new build dependency).
- **JNI accessors** in `slicer_wrapper.cpp`:
  `predictBlendedColor`, `findBestBlend`. Trivial wrappers around the C++17
  library's `predict()` and a brute-force search.
- **Kotlin entries** in `NativeLibrary.kt`:
  ```kotlin
  external fun predictBlendedColor(aRgb: Int, bRgb: Int, mixBPercent: Int): Int
  external fun findBestBlend(targetRgb: Int, candidatePairs: IntArray): IntArray
  ```
- **`TargetColourTab` Composable** (new): HSV picker + dual swatch + confirm
  button. Renders inside `CreateMixSlotDialog`.

## Data flow

1. User taps "Target Colour" tab in dialog.
2. HSV picker emits `targetRgb` on confirm.
3. JNI `findBestBlend` runs (sub-100ms on Pixel 8a).
4. JNI `predictBlendedColor` runs for the chosen pair+ratio.
5. UI shows target swatch + predicted swatch + ΔE.
6. User confirms → dialog switches to Pair + Ratio tab pre-filled.
7. Standard Phase A Create flow.

## Tests

- **JVM (mocked JNI):** `TargetColourTabTest` for layout + interaction.
- **JVM (native, via instrumented):** `NativeLibraryFdmMixerTest`:
  - `predictBlendedColor_returnsKnownValue_forBlueYellow50`
  - `findBestBlend_returnsExactPair_forExactlyAchievableTarget`
  - `findBestBlend_returnsClosestPair_forApproximateTarget`
  - `findBestBlend_runtimeUnder100ms_4extruders_21ratios`
- **Instrumented:** `targetColourPicker_endToEnd_createsCorrectMixRow` — pick
  a target, confirm, verify the resulting `MixedFilamentRow` has the expected
  componentA, componentB, mixBPercent.

## Out of scope (deferred to Phase D+)

- **More than 2 components per mix.** PR #375 supports gradient mixes with 3+
  components; not exposed in Phase C.
- **Per-region target colour in Smart Paint.** Smart Paint integrates with the
  existing mix list (Phase B); it doesn't directly call the target picker. v2
  could add "use target colour for region" → AI creates mix via the picker.
- **Saving target-colour history.** No "recent colours" list in v1.

## Native rebuild

**Required.** New submodule + JNI accessors. Same procedure as Stages 1 + 2,
using `scripts/rebuild-native-so.sh` after the submodule init.

## Risks

- **fdm-mixer C++17 conflicts with our existing C++14/17 build.** Mitigate by
  pinning a known-compatible commit (Phase C plan includes the SHA selection).
- **Library size impact on `.so`.** prusa-fdm-mixer is small (~5K LOC); expect
  `.so` to grow by ~500KB.
- **Prediction accuracy depends on filament-library data.** prusa-fdm-mixer
  ships HueForge / OpenPrintTag databases. For filaments not in those
  databases, prediction reverts to RGB heuristic (worse than v1 Phase B's
  naive linear blend? — needs evaluation at implementation time).
- **Brute force may be too slow on lower-end devices.** Profile before ship; if
  Pixel 5 < 200ms, accept; otherwise reduce ratio granularity to 10%.

## Acceptance criteria

1. From the CreateMixSlotDialog, switching to the Target Colour tab lets the
   user pick an RGB target via HSV picker.
2. Confirming a target produces a `MixedFilamentRow` matching the JNI
   `findBestBlend` output.
3. The predicted-colour swatch is displayed next to the target swatch with a
   ΔE label.
4. The rebuilt `.so` includes the fdm-mixer code, builds cleanly, passes the
   existing test suite + new tests, and is the same OR smaller than the prior
   `.so` plus ≤ 1 MB.
5. Phase A and Phase B continue to work unchanged when the user prefers the
   manual Pair + Ratio tab.
