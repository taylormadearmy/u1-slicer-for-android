# Pick-a-colour mix matching — design

**Date:** 2026-06-09
**Branch:** `feature/pick-a-colour` (worktree off `main` @ e1790a4)
**Status:** Design — awaiting user review
**Follows:** M4 N-way colour mixing (shipped v3.0.0-beta). This is the next full-spectrum milestone.

---

## 1. Summary

Today a user builds a mix by hand — choose 2–4 filaments + weights, and the app shows the resulting blended colour (forward: mix → colour). That requires thinking like a paint-mixer. This feature inverts it: **the user picks the colour they want, and the app suggests which loaded filaments + ratio get closest** (reverse: colour → mix).

It rests on **accurate forward colour prediction**, so it ships in two linked pieces:
1. **Accurate forward prediction** — replace the crude linear-sRGB `naiveBlendHex` with a Kotlin port of **`prusa-fdm-mixer`** (Prusa's MIT-licensed, FDM-aware "ColorMix" model). This alone makes every existing mix swatch + the 3D preview show the *true* blended colour — a self-contained win and the foundation for matching.
2. **"Match a colour"** — a button inside the existing Create-Mix dialog that opens the colour picker, runs a reverse search, and auto-fills the editor with the closest-predicted mix.

All Kotlin/UI — **no native change, no `.so` rebuild**.

## 2. Goals / Non-goals

**Goals**
- Accurate forward prediction of a mix's blended colour (port prusa-fdm-mixer to Kotlin).
- "Match a colour" in the Create-Mix dialog: pick target → closest mix auto-fills the editable weight bar.
- A colour-count selector (2 / 3 / 4) so the user controls the print-quality-vs-accuracy trade-off per match (default 2); the suggestion updates live as the count changes.
- An honest closeness badge (ΔE → Good / OK / Weak); never blocks; surfaces a single-filament alternative when one is actually closer than any mix.

**Non-goals (deferred)**
- **Colour calibration** (photographing/measuring printed swatches → per-filament/per-printer correction). v1 is "suggested / preview," not "guaranteed colour."
- **Sampling a colour from a photo or the 3D model** — target input is the existing HSV picker + hex entry only.
- A separate standalone "Pick a colour" screen (chosen: integrated into Create-Mix; standalone is a possible later add).
- Calibrated/measured spectral data — we work from per-filament hex colours only.

## 3. Decisions (from brainstorm)

- **Entry shape:** integrated — a "🎯 Match a colour" button in `CreateMixSlotDialog` (not a standalone flow).
- **Result presentation:** single best suggestion auto-filled into the existing weight-bar editor (not a ranked list), with a closeness badge. The user tweaks via the editor.
- **Colour count:** user-selected 2 / 3 / 4 (default 2). The matcher returns the closest mix using exactly that many filaments; toggling the count re-runs and updates the suggestion + badge live. (1 isn't a "mix" — but if a single loaded filament is closer than any mix, surface it as a note.)
- **Target input:** reuse the existing F64 HSV picker (`FilamentColorEditDialog` pattern) + hex entry.
- **Framing:** predicted swatch labelled as a preview; feature under the existing **BETA** pill.

## 4. Architecture

Two small, pure, isolated units + light UI wiring. Reuses the M4 `MixedFilamentRow` (N-component list + weights), the M4 weight-bar editor in `CreateMixSlotDialog`, `ColourMatch` (ΔE/LAB), and the existing HSV picker.

### 4.1 `FilamentMixPredictor` (new, pure Kotlin) — forward model
- `fun predict(componentHexes: List<String>, weights: List<Int>): String` — predicted blended hex.
- A faithful Kotlin port of the real `prusa-fdm-mixer` algorithm. **The port must be done from the actual source** (`prusa3d/prusa-fdm-mixer`, `cpp/` single-header or the TS reference) — not from the summary — and pinned by a unit test to the repo's own reference input→output vectors.
- **N-way caveat (verify during port):** confirm whether prusa-fdm-mixer predicts arbitrary N-component blends or is fundamentally 2-colour. If 2-colour-only, predict an N-way mix by **pairwise folding** weighted by accumulated proportion (mirrors the engine's `blend_color_multi`); this is approximate and order-dependent — acceptable for a "suggested/preview" feature, but documented as a known limitation for 3–4-colour matches. Record what the source actually supports.
- Replaces `naiveBlendHexMulti` as the mix-colour source at the three display sites: `SlicerViewModel` (slotPalette / loadedModelMixColors), `NavGraph` (mixDisplayColoursProvider), `AiPaintResultScreen` (slotPalette). `ColourMatch.naiveBlendHex*` may remain for any non-mix caller but is no longer the mix predictor.

### 4.2 `MixColourMatcher` (new, pure Kotlin) — reverse search
- `fun bestMix(target: String, loadedFilaments: List<String>, count: Int): MixSuggestion` where `MixSuggestion(componentIndices: List<Int>, weights: List<Int>, predictedHex: String, deltaE: Double)`.
- Brute-force: enumerate filament subsets of size `count` from the loaded filaments (1-based indices), × a coarse weight grid (compositions summing to 100 in `count` parts, ~5% steps), evaluate `ColourMatch.deltaE76(target, FilamentMixPredictor.predict(...))`, keep the minimum, then a fine local refine (±a few % around the winner). Tractable: ≤~1k predictor calls per query, sub-millisecond.
- `fun closestSingleFilament(target, loadedFilaments): Pair<Int, Double>` (index, ΔE) — reuses `ColourMatch.closestSlot`/`closestDistance` for the "a plain filament is closer" note.
- Pure and deterministic → fully unit-testable.

### 4.3 UI wiring (`CreateMixSlotDialog`)
- Add a "🎯 Match a colour" button near the top of the dialog.
- Tapping it opens the existing HSV picker for the target colour, plus a **2/3/4 count selector** (default 2).
- On target/count change: call `MixColourMatcher.bestMix(...)`, set the dialog's existing `components`/`weights` state from the result (auto-fill), and show a closeness badge (`deltaE` → Good ≤~3 / OK ~3–8 / Weak >~8 — thresholds tunable). If `closestSingleFilament` beats the best mix, show a small "filament E_n alone is closer" note.
- The existing weight-bar editor renders the filled-in result; the user can tweak, then Create as normal. No new editor.

## 5. Data flow
```
user taps "Match a colour"
  → HSV picker → target hex  +  count selector (2/3/4)
  → MixColourMatcher.bestMix(target, loadedFilamentHexes, count)
        (loops MixColourMatcher → FilamentMixPredictor.predict → ColourMatch.deltaE76)
  → MixSuggestion(components, weights, predictedHex, deltaE)
  → CreateMixSlotDialog state := components/weights ; badge := quality(deltaE)
  → user tweaks via weight bar → Create → normal MixedFilamentRow (assigned/sliced as today)
```

## 6. Error handling / edge cases
- **Out-of-gamut target** (best ΔE large): still auto-fills the closest; badge reads "Weak"; never blocks.
- **Single filament closer than any mix:** surfaced as a note (don't force a pointless mix).
- **Fewer loaded filaments than the chosen count** (e.g. count=4 but 3 loaded): cap `count` to the number of loaded filaments; selector disables unavailable counts.
- **Predictor on degenerate input** (1 colour / empty): returns that colour / guarded.

## 7. Testing
- **Unit — `FilamentMixPredictor`:** port correctness pinned to prusa-fdm-mixer reference vectors; sanity (blue+yellow → green-ish, not grey); 2-colour endpoints; N-way fold behaviour (whatever the source dictates).
- **Unit — `MixColourMatcher`:** a known mix's predicted colour is recovered as the best match for that target; ΔE ordering correct; `count` respected; single-filament fallback; performance/tractability (a query stays well under budget).
- **Unit — display swap:** the three sites use `FilamentMixPredictor`; a 3-colour mix's displayed colour differs from the old naive average.
- **Structural guard:** `CreateMixSlotDialog` contains the Match-a-colour button + the 2/3/4 count selector + wires the matcher.
- **Instrumented/E2E (device):** open Create-Mix → Match a colour → pick a target → suggestion fills the editor → Create → assign → slice → G-code blends the suggested filaments. No physical print (upload-only if any send path is touched).

## 8. Sequencing
- **Step 1 — forward prediction:** port prusa-fdm-mixer → `FilamentMixPredictor`; swap the 3 display sites; tests. Ships accurate swatch/preview colours on its own (releasable independently).
- **Step 2 — matcher + UI:** `MixColourMatcher`; the Match-a-colour button + count selector + HSV picker wiring in `CreateMixSlotDialog`; tests + device E2E.

## 9. Future work (captured, not in scope)
- Colour calibration (printed-swatch capture → per-filament/per-printer correction) — required before any "exact colour" promise.
- Sample-a-colour from a photo or the 3D model as target input.
- Standalone "Pick a colour" entry (the brainstorm's option B) if the integrated entry proves too buried.

## 10. Branch & release
- Work on `feature/pick-a-colour` (worktree off `main` @ e1790a4). No native change; no `.so` rebuild.
- Pure-Kotlin → can ship in a normal version bump; no GitHub release/tag without explicit user authorization; `gh auth switch -u taylormadearmy` before any push.
