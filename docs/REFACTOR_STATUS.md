# Bambu Refactor — Status as of v2.0.0 (2026-05-01)

Living status of the multi-phase Bambu file-handling refactor. Updated when a phase ships or scope changes.

For background see [`docs/architecture/2026-04-23-bambu-via-native-loader.md`](architecture/2026-04-23-bambu-via-native-loader.md) (the original strategy doc) and the per-phase plans in [`docs/superpowers/plans/`](superpowers/plans/).

---

## TL;DR

- **Phase 0 (differential test harness):** ✅ DONE
- **Phase 1 (Bambu via native loader, sub-plans 1–5):** ✅ DONE — merged to `main` 2026-04-26 as `v1.7.0-dev`
- **Phase 2 (canonical filament list):** ✅ DONE — currently shipping as `v2.0.0` (this branch, `feature/phase2-canonical-filaments`)
- **Phase 2.0 (UX exploration):** ⏸ NOT STARTED — gate for Phase 2.6 implementation
- **Phase 2.6 (Prepare-screen reshape):** ⏸ NOT STARTED — depends on Phase 2.0 outcomes
- **Phase 3+ (multi-printer support, FullSpectrum, mixed-colour, etc.):** 🔭 ROADMAP — see GitHub #16, #18, #33

The "core" refactor (kill duplicate Kotlin parsing of Bambu files; native loader is single source of truth) is **complete and shipping** in v2.0.0. Future phases are UX evolution and adjacent platform support, not architectural debt.

---

## Phase 0 — Differential test harness

**Status:** DONE
**Plan:** [`docs/superpowers/plans/2026-04-23-bambu-diff-test-harness.md`](superpowers/plans/2026-04-23-bambu-diff-test-harness.md)
**Outcome:** A snapshot harness compares Kotlin and C++ parses of every Bambu fixture in `app/src/androidTest/assets/`. Disagreements are tracked in `known-disagreements.json`. Each Phase 1 sub-plan landed by deleting its corresponding entries from the baseline as the Kotlin path was retired.

---

## Phase 1 — Bambu via native loader

**Status:** DONE — all 5 sub-plans landed and merged to `main` as `v1.7.0-dev`.
**Plan:** [`docs/superpowers/plans/2026-04-23-phase1-roadmap.md`](superpowers/plans/2026-04-23-phase1-roadmap.md)

| Sub-plan | What it killed | Status |
|---|---|---|
| #1 Painted facets via native preview mesh | `ThreeMfMeshParser.parse` paint state walk | ✅ DONE |
| #2 Per-plate `PlateData` from native | Kotlin `restructurePlateFile` + per-plate parse | ✅ DONE |
| #3 Custom gcode per layer | Kotlin `custom_gcode_per_layer.xml` parser | ✅ DONE |
| #4 Object extruder map | Kotlin `objectExtruderMap` synthesis | ✅ DONE |
| #5 Project config + filament colours | Kotlin `project_settings.config` parser | ✅ DONE |

The duplicate Kotlin parsing path is gone. `ThreeMfParser` is now used only for the pre-load plate selector (cheap path that doesn't need the full native importer to decide what plates exist) and for paths the native importer doesn't cover (single-volume `objectExtruderMap` synthesis under specific conditions, layer-tool extruder enumeration when native returns empty for pure layer-tool plates).

---

## Phase 2 — Canonical filament list

**Status:** DONE — shipping as `v2.0.0` from this branch.
**Outcome:** The canonical (file-wide, fileIndex-keyed) filament list is the source of truth for:

- Prepare 3D preview palette (`meshAlignedFilamentColors`, canonical-aligned)
- Filament Mapping dialog row labels (canonical fileIdx)
- Slice Summary chip strip (`computePlateFileIndices` from G-code footer)
- G-code save/share output (canonical T-indices, not physical slot)

Slice-time tool remap (`semmColorPermutation`, `slicerColorOrder`, `composeSemmRemap`) is **retired**. The slicer emits canonical T-indices and `PrintTimeRemap` (Send-time, runtime) translates to physical slots when the user prints.

**Bug-class siblings closed in v2.0.0:**

- Border Collie / Buzz plate 1 chip-label off-by-T-index → footer-line is single source of truth
- Shashibo plate 5 phantom-3 chip → enrichedExtruders trusts native when non-empty
- Slip-slide-spin plate 3 4-chip narrowing → canonical fileIdx narrowing
- Buzz plate 8 mesh palette swap (DC15 report) → `TriangleSelector::get_facets` multi-state H2C-fold removed (submodule `06f5c3677e` + `bd66b99b2d`)

**Backlogged from Phase 2 review:**

- B96: SEMM canonical-T-index spread (cosmetic wipe-tower waste, not a print-correctness bug)
- B97: H2C state-fold lacks provenance check (latent; not observed in any current fixture)

---

## Phase 2.0 — UX exploration

**Status:** NOT STARTED
**Why blocked:** The desktop Orca / Bambu Studio Prepare experience makes per-filament edits inline (override colour, override material type, drag to reorder, etc.). The Android app currently mixes "what's in the file" (canonical filament list) with "what physical slot is this going to" (slot picker). Phase 2.6 needs a clear UX direction before code lands.

**Memory note** (per `~/.claude/projects/c--Users-kevin-projects-u1-slicer-orca/memory/project-prepare-screen-reshape.md`):
> Prepare becomes editable filament list (colour + material type), mirroring desktop Orca; overrides drive slicing; slot picker stays at Send time.

That note is the working hypothesis. Phase 2.0 = validate the hypothesis with mockups / interaction flows / printability check before we touch Compose code. Owner: Kevin.

---

## Phase 2.6 — Prepare-screen reshape

**Status:** NOT STARTED — gated on Phase 2.0
**Scope:** Prepare screen becomes the canonical filament list editor. User edits filament colour and material type inline; overrides feed into the slicer; the slot picker (which physical extruder slot each filament prints from) moves entirely to Send time.

**Pre-requisites:**
- Phase 2.0 design direction
- Possibly `FilamentOverrideStore` extension to cover material type (currently only colour)

**Risk:** the Prepare screen is currently the most-trafficked surface; a UX regression here would be felt immediately by every user. Stage with feature flag if introducing big visual change.

---

## Phase 3+ — Roadmap

These are larger initiatives that depend on or follow from the refactor but are not part of "the refactor" proper:

| GH # | Topic | Status |
|---|---|---|
| #16 | Bambu printer support (talk to a Bambu printer, not just Bambu *files*) | Roadmap |
| #18 | FullSpectrum fork — mixed-colour / pseudo-extruder | Roadmap |
| #33 | AI-assisted colouring for single-colour prints | Roadmap |
| #56 | F66 Split to objects + auto-rotate | Roadmap |

These don't block the v2.0.0 ship and are tracked as feature requests, not refactor work.

---

## What's NOT changing in v2.0.0

- Public release line stays on `release/v1.6.x` until v2.0.0 is shipped (this commit). Once tagged + released, `main` and `release/v2.x` will diverge from `release/v1.6.x` going forward.
- The 4-extruder cap on the Snapmaker U1 is unchanged. Phase 2 does NOT add support for >4 physical slots; it just stops conflating canonical filament fileIdx with physical slot inside the slicer.
- The orcaslicer submodule pinning. The submodule remains at `bd66b99b2d` (Snapmaker Orca 2.2.4 fork + Android-specific patches + post-Buzz H2C-fold fix). Upstream upgrades are still a separate consideration and are not part of this refactor.
