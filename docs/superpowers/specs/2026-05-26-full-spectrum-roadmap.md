# Full-Spectrum (Optically-Blended) Colour — Strategy & Roadmap

**Date:** 2026-05-26
**Status:** Strategy / roadmap (no implementation committed yet)
**Backlog:** F14 (GitHub #18) — re-scoped by this doc. Cross-links D1 (engine upgrade process).

## 1. Goal & framing

Add full-spectrum (optically-blended) colour to U1-Slicer — producing perceived
intermediate colours (e.g. blue + yellow → green) that the four loaded filaments
cannot achieve individually.

Every candidate source uses the **same physical technique**: **layer/line
alternation across the toolheads**, exploiting human visual halftoning at normal
viewing distance. There is **no mixing hotend** involved in any of them.

The decisive hardware fact: the **Snapmaker U1 is a toolchanger** with four
independent extruders/nozzles. Tool changes are mechanical, so alternation costs
**print time, not purge waste** — no prime/wipe tower is required for the colour
blending itself. This is exactly the architecture Prusa identifies as *ideal* for
their ColorMix (toolchangers like the Prusa XL). The only physical prerequisite
is **accurate XY nozzle-offset calibration** so alternating layers register on top
of one another.

This supersedes the original F14 blocker ("ratdoux fork v0.9.4 alpha, untested on
hardware, wait for v1.0"): the capability is now appearing in **Snapmaker's own
Orca fork**, and the per-layer-purge concern that would have plagued a
single-nozzle machine does not apply to a toolchanger.

## 2. Architecture invariants (true regardless of source)

- We **never** use OrcaSlicer's GUI. We drive the slicing core through SAPIL/JNI
  with our own Jetpack Compose UI. So whichever engine ships the capability, the
  U1-Slicer **UX and config-key plumbing are always ours to build.**
- New slicer settings must be wired through **both** paths in `sapil_print.cpp`
  (`applyConfigToPrusa()` fallback + `profile_keys[]` whitelist) and, if
  user-controllable, `buildProfileOverrides()` in `SlicerViewModel.kt`. See the
  "Profile Key Pipeline" checklist in `CLAUDE.md`.
- Native `.so` rebuilds are pre-authorised and follow the existing
  NDK-26 / Release / size + compiler-verification checklist in `CLAUDE.md` and
  `ENGINE_UPGRADE_GUIDE.md`.

## 3. Engine-source decision matrix

> This remains an **explicit decision point**. Recommendation given, final pick to
> be confirmed at M0.

| Source | What it provides | What we still build | Long-term cost |
|---|---|---|---|
| **Snapmaker's own Orca fork** *(recommended)* | Full-spectrum capability arrives on a submodule bump from 2.2.4 to the newer Snapmaker Orca. Same base, same patch surface (~2,400 lines), one engine to track. | Our Compose UI + config-key wiring. | Lowest — stays on the vendor mainline we already patch. |
| **ratdoux/OrcaSlicer-FullSpectrum** | The original F14 target; a fork of Snapmaker Orca 2.2.4 adding pseudo-extruder alternation. | Same UI work. | Likely **redundant** now; perpetual rebase of our patches onto a diverging third-party fork. |
| **Prusa `prusa-fdm-mixer`** | MIT C++17 / TS library that **predicts the perceived colour from a layer ratio**. Not toolpath generation. | Everything else. | N/A as primary engine — it is the **colour-accuracy layer** (see M4), not the slicer. |

**Recommendation:** Snapmaker's own fork as the engine; Prusa's mixer as the
colour-prediction layer on top (M4). ratdoux retired unless M0 shows Snapmaker's
native support is unusable.

## 4. Milestones / sub-projects

Each milestone gets its own spec → plan → implementation cycle when reached.

### M0 — Verify capability (gate)
Confirm the chosen Snapmaker Orca fork **actually contains** full-spectrum, and
that it is reachable **through config keys / SAPIL** — not locked behind their
desktop GUI workflow. Identify the new config key names and the expected input
(target colour? per-region assignment? CMYKW filament roles?). Cheapest possible
check; **nothing else proceeds until this passes.**

#### M0 desk-research findings (2026-05-26)
- **Capability confirmed in Snapmaker's own fork.** PR
  [**#375 "Feat: mix filament"**](https://github.com/Snapmaker/OrcaSlicer/pull/375)
  merged to `main` on 2026-05-26. Adds `MixedFilament` / `MixedFilamentManager`
  classes, gradient transitions, **pointillism same-layer mixing**, auto + manual
  pattern modes, local-Z dithering, height-weighted cadence, configurable mix
  ratios. Touches `Print.cpp`, `PrintApply.cpp`, `TriangleSelector.cpp`,
  `ObjColorPanel`, and a new `MixedFilamentDialog`.
- **Convergence signal:** it integrates a **`FilamentMixer`** library — the *same*
  component the ratdoux fork moved to in its v0.8 pre-release. Snapmaker's native
  path and ratdoux have effectively converged on one colour engine. **This further
  retires the ratdoux option** — no reason to track a third-party fork for tech
  Snapmaker now ships.
- **Control-surface (the open M0 question):** *partially* answered. There **is** a
  `PrintConfig` layer (new mixed-filament options) — promising for headless SAPIL
  use — **but** the primary UX is the GUI `MixedFilamentDialog`, and per-region
  assignment goes through `TriangleSelector` (paint-style). **Still to verify
  hands-on:** how much mixing state serialises into `project_settings.config` /
  model data we can drive headless vs. how much lives only in the GUI dialog.
- **Architectural fit:** the `TriangleSelector` / `ObjColorPanel` paint machinery
  is the *same* family we already handle in Phase 1 (objectExtruderMap, paint
  states, native paint-state accessors). Our existing native plumbing likely
  extends to this rather than needing a parallel system.
- **Version / divergence cost:** #375 is on **`main` only, not in a tagged
  release** (latest tag is the 2.3.x line; we are pinned at 2.2.4 `f11a7bf`).
  Adopting it means a **large submodule jump** (~thousands of commits since 2.2.4)
  and re-applying ~2,400 lines of Android patches against substantially changed
  upstream — the dominant cost of M1, and a bleeding-edge stability risk until
  Snapmaker tags a release containing #375.

#### M0 source verification — PASS (2026-05-26)
Inspected PR #375 source at merge commit `ac3dafe`. **The mixing is fully
config-driven and headless-reachable through SAPIL** — no GUI dependency in the
core slicing path:

- **The recipe is a single config string.** `PrintConfig.cpp` adds
  **`mixed_filament_definitions` (`coString`)** — the serialized output of
  `MixedFilamentManager::serialize_custom_entries()`. `bbs_3mf.cpp` / the print
  path reads it back: `config.option<ConfigOptionString>("mixed_filament_definitions")`
  → `MixedFilamentManager::load_custom_entries(...)` → rebuilds the virtual
  filaments and slices. Set this key in `project_settings.config` and the engine
  reproduces the blend with no GUI involved.
- **~15 scalar tuning keys**, all plain `ConfigOptionDef`s we can whitelist:
  `mixed_color_layer_height_a/b`, `mixed_filament_gradient_mode`,
  `mixed_filament_height_lower_bound/upper_bound`, `mixed_filament_advanced_dithering`,
  `mixed_filament_pointillism_pixel_size/line_gap`,
  `mixed_filament_component_bias_enabled`, `mixed_filament_surface_indentation`,
  `mixed_filament_region_collapse`, plus a `dithering_*` / `dithering_local_z_*`
  family (`dithering_z_step_size`, `dithering_local_z_mode`, ...).
- **Virtual filament IDs** are numbered `num_physical + 1` (so IDs 5,6,7,8 on our
  4-extruder U1). They are assigned to objects/regions exactly like physical
  extruders — i.e. through the **same paint / `objectExtruderMap` machinery we
  already handle from Phase 1** (`TriangleSelector` for per-region, object extruder
  for whole-object). No new assignment channel needed.
- **Data model** (`MixedFilament` struct): `component_a/b` (1-based physical IDs),
  `ratio_a/b` cadence, `mix_b_percent`, three `DistributionMode`s
  (LayerCycle / SameLayerPointillisme / Simple), gradient component lists +
  weights, manual pattern strings, per-row Local-Z cap, surface offsets.
- **Convergence confirmed:** uses a `FilamentMixer` library for display-colour
  blending (Blue+Yellow→Green) — the same engine ratdoux adopted. ratdoux is now
  firmly retired.

**Residual (not gating the engine decision):** a confirmatory real-U1 print
(quality + nozzle-offset registration) — folded into **M2**, not M0. M0 is
satisfied: the capability exists in the vendor fork and is fully reproducible
headless via config. The dominant remaining cost is the **submodule jump from
2.2.4 `f11a7bf` to a post-#375 commit** and re-applying the Android patch set
(**M1**).

### M1 — Engine bump
Move the `app/src/main/cpp/orcaslicer` submodule to the target Snapmaker commit,
re-apply the Android patch catalogue (per `ENGINE_UPGRADE_GUIDE.md`), rebuild the
`.so`, and confirm the **full existing test suite stays green** (1367 unit + 345
instrumented). Closes/advances D1.

### M2 — Feasibility slice
Drive a full-spectrum slice through SAPIL from a test config. Inspect the G-code
(tool changes per layer, time estimate, registration). Validate with **one real
U1 print** to confirm blended colours read correctly and nozzle offsets register.
This is the go/no-go on print quality.

### M3 — Compose UI (thin)
Target-colour picker → engine recipe across the four loaded filaments. Surface a
print-time estimate up front. Clearly-labelled mode. Wire the M0 config keys
through `applyConfigToPrusa()` + `profile_keys[]` + `buildProfileOverrides()`.
Add unit tests for any new parsing/mapping logic.

### M3a — Smart Paint integration (designed with M3, built with or after it)
Full-spectrum's biggest product win is on **Smart Paint** (F54), not the standalone
colour picker. Smart Paint is today **hard-capped at 4 colours**: each segment is
assigned a physical filament **slot `0..3`** ([`AiRegion.kt:12`,`:35`]), and when the
AI finds >4 regions they collapse onto those 4 slots. Mixed filaments remove exactly
that ceiling — virtual IDs `5,6,7,8…` are just more extruder IDs written through the
**same per-triangle slot → 3MF mechanism** Smart Paint already uses
(`PaintedMeshWriter`).

**Decision to lock now (foundational, cheap now / expensive to retrofit):** the
per-triangle slot and the segment→slot assignment must be **widened from `0..3` to
carry virtual filament IDs (≥4)**. This ripples through `AiRegion`,
`PaintedMeshWriter`, the slot-reassignment chips, and the preview palette. M3 must
**not** design a parallel colour system Smart Paint can't see — both the picker and
Smart Paint resolve to the same `{physical 1..N} ∪ {virtual N+1…}` palette and the
same assignment substrate.

**Deferred to build time (M3a, not now):**
- **Palette breadth for the matcher** — how many mixes to expose to Smart Paint's
  nearest-colour match (all 6 auto pairs? gradients too?). Too many makes matching
  noisy; needs tuning. Pairs nicely with M4's perceived-colour prediction.
- **Print-cost transparency** — a painted *mixed* region prints by per-layer
  alternation, so Smart Paint could silently multiply tool changes / print time.
  Needs a cost indicator (e.g. tool-change count or time delta) when mixes are in play.
- **Registration dependency** — painted mixed regions rely on the user's XY
  nozzle-offset calibration (shared M2/M3 risk).

This item does **not** gate engine adoption (M0–M2 are colour-source agnostic);
it gates only how M3's UI is shaped. Capture the data-model decision in the M3 spec.

### M4 — Colour-accuracy fast-follow
Integrate **`prusa-fdm-mixer`** (MIT, C++17) so the picker predicts the *perceived*
colour from a given layer ratio — honest "achievable colour" feedback rather than a
naive blend. This is the one genuinely hard sub-problem (4-filament mix → what the
eye sees) and Prusa open-sourced exactly it.

### M5 — Stretch
Preview rendering of blended colours in the 3D/G-code preview; saved palettes /
presets.

## 5. Key risks & unknowns

- **GUI-gated capability** — full-spectrum may only be exposed in Snapmaker's
  desktop UI, not via config. **M0 retires this risk** before any engine work.
- **Calibration dependency** — blended colour quality depends on the user's XY
  nozzle-offset calibration; poor calibration → visible mis-registration. Document
  the prerequisite; consider a UI warning.
- **Print-time blow-up** — per-layer tool changes multiply print time. Estimate it
  and show the user up front (M3).
- **Colour fidelity without M4** — a naive layer-ratio blend may not match the
  target colour; M4 addresses this.
- **Filament-role assumptions** — CMYKW-style models assume specific filaments
  loaded; we have four arbitrary user filaments. The colour decomposition must work
  from whatever is loaded, or guide the user on what to load.

## 6. Backlog / issue sync

- Re-scope **F14** in `BACKLOG.md` from "ratdoux fork, blocked on v1.0" to "track
  this roadmap"; link this doc.
- Keep **GitHub #18** in sync with the re-scope.
- Cross-link **D1** (engine upgrade process) — M1 exercises it.

## 7. Decision rejected

**"Own the full-spectrum logic in our own layer"** (treat the engine as a dumb
multi-tool toolpath generator and implement colour decomposition ourselves):
maximum control and fork-independence, but re-solves what the fork already does
plus a large amount of new native code. Over-engineering given we already drive the
core through SAPIL. Rejected unless M0 shows no usable engine support anywhere.
