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

#### M1 pre-flight analysis (2026-05-26)
Cross-referenced our Android patch surface (per `ENGINE_UPGRADE_GUIDE.md` patch
catalog) against the files PR #375 rewrote, and spot-checked current `main`.

**Reframe — the dominant M1 cost is SAPIL API-compat, not patch re-merge.** The
bulk of our native code lives in standalone files *outside* the submodule
(`app/src/main/cpp/src/sapil_*.cpp`, `slicer_wrapper.cpp`) — these never conflict
textually, but they call into libslic3r classes (`Print`, `Model`, `PrintConfig`,
`PresetBundle`, `Config`) that **#375 changed**. M1's real work is fixing compile
breakage in our wrapper against the new libslic3r API, not merging diffs.

**Our actual in-submodule patch surface is small and mostly isolated:**

| Patch | File | Collides with #375? | Action |
|---|---|---|---|
| B38 init: `m_origin`, `m_isBBLPrinter`, `FakeWipeTower` | `libslic3r/Print.hpp` | **Yes** | **Must re-apply** — verified still needed (see below) |
| B38 init: `m_cur_layer_id` | `libslic3r/GCode/WipeTower.hpp` | No | Re-apply, clean — verified still needed |
| Build: NDK type qualification | `CutSurface.cpp`, `Brim.cpp`, `clipper.hpp`, `NSVGUtils.cpp`, STL includes | No (not in #375) | Re-apply, expect clean; re-verify against full drift |
| Diagnostics (`#ifdef __ANDROID__`) | `GCode.cpp`, `Print.cpp`, `WipeTower2.cpp`, `Snapmaker_Orca.cpp` | **Yes** | Defer — optional safety nets; re-add post-green |
| Heavy diag (GUI) | `slic3r/GUI/PartPlate.cpp` | **Yes** | **Drop** — we don't use the GUI |
| Heavy diag | `deps_src/clipper/clipper.cpp`, `ClipperUtils.cpp` | No | Optional; re-add only if investigating |

**B38 verified still required (not upstreamed).** On current `main`:
`Print.hpp:1124` is `Vec3d m_origin;` (no initializer), `:1100` is
`bool m_isBBLPrinter;`, `WipeTower.hpp:307` is `size_t m_cur_layer_id;` — all still
uninitialized. The members are unchanged, so re-application is mechanical (add the
initializers). This is the one **must-fix** that gates a correct release build; the
rest of the catalog is either upstream-clean, optional, or droppable.

**Caveat:** #375 is only the latest PR. The full drift is 2.2.4 (`f11a7bf`) →
post-#375 `main` (thousands of commits), which touches far more than #375's 114
files. This analysis bounds the *#375-specific* collisions and confirms the
must-fix set; the full re-apply still needs the real `git apply --3way` pass in M1
with the submodule checked out.

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

## Appendix A — Mixed-filament config keys (M3 wiring catalog)

Read from PR #375 `PrintConfig.cpp` at merge commit `ac3dafe`. All are FFF print
config options, so they serialize into `project_settings.config` in the 3MF. M3
wiring per the `CLAUDE.md` "Profile Key Pipeline": **every key below must be added
to `profile_keys[]`** so an embedded full-spectrum profile can drive it. Engine
defaults are all "off / 0" (no mixing) — so raw STL files with no profile stay
single-colour and **no `applyConfigToPrusa()` fallback is needed** unless we want
to change a default. The subset our M3 UI controls also goes through
`buildProfileOverrides()`.

| Key | Type | Engine default | Role | M3 user-controlled? |
|---|---|---|---|---|
| `mixed_filament_definitions` | coString | `""` | **The recipe.** Serialized `MixedFilamentManager` rows. Built by our UI. | **Yes — primary** |
| `mixed_filament_gradient_mode` | coBool | false | 0=layer-cycle weighted, 1=height weighted | Yes |
| `mixed_filament_height_lower_bound` | coFloat | 0.04 | Local-Z sublayer min height | Maybe (advanced) |
| `mixed_filament_height_upper_bound` | coFloat | 0.16 | Local-Z sublayer max height | Maybe (advanced) |
| `mixed_filament_advanced_dithering` | coBool | false | Ordered-dither cadence (experimental) | Advanced toggle |
| `mixed_filament_component_bias_enabled` | coBool | false | Per-pair apparent-colour bias | Advanced toggle |
| `mixed_filament_surface_indentation` | coFloat | 0.0 | XY surface offset for mixed regions | No (default) |
| `mixed_filament_region_collapse` | coBool | false | Merge adjacent mixed regions | No (default) |
| `mixed_color_layer_height_a` | coFloat | 0.0 | Dithering cadence height, component A | No (derived) |
| `mixed_color_layer_height_b` | coFloat | 0.0 | Dithering cadence height, component B | No (derived) |
| `mixed_filament_pointillism_pixel_size` | coFloat | 0.0 | Same-layer pointillisme pixel size | Advanced |
| `mixed_filament_pointillism_line_gap` | coFloat | 0.0 | Same-layer pointillisme line gap | Advanced |
| `dithering_z_step_size` | coFloat | 0.0 | Layer height in dithered Z zones | Advanced |
| `dithering_local_z_mode` | coBool | false | Enable Local-Z dithering pipeline | Advanced toggle |
| `dithering_local_z_whole_objects` | coBool | false | Apply Local-Z to whole objects | Advanced |
| `dithering_local_z_infill` | coBool | false | Apply Local-Z to infill | Advanced |
| `dithering_local_z_direct_multicolor` | coBool | false | Direct multicolour Local-Z | Advanced |
| `dithering_step_painted_zones_only` | coBool | false | Restrict Z-step to painted zones | Advanced |
| `local_z_wipe_tower_purge_lines` | coFloat | 3.0 | Purge lines when Local-Z + prime tower | No (default) |

**Bottom line for M3:** the minimum viable wiring is **one key** —
`mixed_filament_definitions` (built by our colour-mix UI) — plus assigning the
virtual filament IDs to objects/regions via the existing paint path. Everything
else is optional tuning exposed progressively. Verify any bool defaults without an
explicit `set_default_value` against `PrintConfig.cpp` when wiring.
