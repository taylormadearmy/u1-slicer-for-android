# Bambu Refactor — Status as of v2.0.0 (2026-05-01)

Living status of the multi-phase Bambu file-handling refactor. Updated when a phase ships or scope changes.

For background see [`docs/architecture/2026-04-23-bambu-via-native-loader.md`](architecture/2026-04-23-bambu-via-native-loader.md) (the original strategy doc), the per-phase plans in [`docs/superpowers/plans/`](superpowers/plans/), and the v2 → v3 release roadmap at [`docs/superpowers/specs/2026-04-28-v2-v3-release-roadmap.md`](superpowers/specs/2026-04-28-v2-v3-release-roadmap.md).

---

## TL;DR

- **Phase 0 (differential test harness):** ✅ DONE
- **Phase 1 (Bambu via native loader, sub-plans 1–5):** ✅ DONE — merged to `main` 2026-04-26 as `v1.7.0-dev`
- **Phase 2 (canonical filament list architecture):** ✅ DONE — shipped as `v2.0.0` 2026-05-01
- **Phase 2.0 (UX exploration):** ✅ DONE — direction settled in `docs/superpowers/specs/2026-04-26-canonical-filament-list-ux.md` §7
- **Phase 2.6 (Prepare-screen reshape):** ✅ DONE in v2.0.0 (4 of 5 spec items; STL-default-from-syncFilaments deferred to v2.1.0)
- **v2.1.0 (hardening release):** ⏸ NOT STARTED — bounded ~2-3 days. Tracked in [`BACKLOG.md`](../BACKLOG.md) as **A1**.
- **v3.0.0 (multi-printer epic):** ⏸ NOT STARTED — weeks; needs design pass first. Tracked in [`BACKLOG.md`](../BACKLOG.md) as **A2**.
- **Phase 3+ adjacent platform work:** 🔭 ROADMAP — see GitHub #16, #18, #33

The "core" refactor (kill duplicate Kotlin parsing of Bambu files; native loader is single source of truth; canonical filaments come from the file; mapping happens at Send time) is **complete and shipping** in v2.0.0. What remains is hardening (v2.1) + a separate multi-printer feature epic (v3.0).

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

**Status:** DONE — shipped as `v2.0.0` 2026-05-01. Tag `v2.0.0` at commit `6b1748e`; `main` at merge commit `f24001d`.
**Outcome:** The canonical (file-wide, fileIndex-keyed) filament list is the source of truth for:

- Prepare 3D preview palette (`meshAlignedFilamentColors`, canonical-aligned)
- Filament Mapping dialog row labels (canonical fileIdx)
- Slice Summary chip strip (`computePlateFileIndices` from G-code footer)
- G-code save/share output (canonical T-indices, not physical slot)

Slice-time tool remap (`semmColorPermutation`, `slicerColorOrder`, `composeSemmRemap`, `computeExpandedGcodeRemap`) is **retired**. The slicer emits canonical T-indices and `PrintTimeRemap` (Send-time, runtime) translates to physical slots when the user prints.

**Bug-class siblings closed in v2.0.0:**

- Border Collie / Buzz plate 1 chip-label off-by-T-index → footer-line is single source of truth
- Shashibo plate 5 phantom-3 chip → enrichedExtruders trusts native when non-empty
- Slip-slide-spin plate 3 4-chip narrowing → canonical fileIdx narrowing
- Buzz plate 8 mesh palette swap (DC15 report) → `TriangleSelector::get_facets` multi-state H2C-fold removed (submodule `06f5c3677e` + `bd66b99b2d`)
- Map & Print / Map & Upload silent failure → `rememberCoroutineScope` hoisted to parent composable as `sendActionScope`

**Backlogged from Phase 2 review:**

- B96: SEMM canonical-T-index spread (cosmetic wipe-tower waste, not a print-correctness bug)
- B97: H2C state-fold lacks provenance check (latent; not observed in any current fixture)

---

## Phase 2.0 — UX exploration

**Status:** DONE
**Output:** [`docs/superpowers/specs/2026-04-26-canonical-filament-list-ux.md`](superpowers/specs/2026-04-26-canonical-filament-list-ux.md) §7. The "Prepare becomes editable filament list mirroring desktop Orca / Bambu Studio's Filament panel" direction crystallised during the Phase 2.4 smoke-test session and is captured in the spec. All 9 design questions resolved.

---

## Phase 2.6 — Prepare-screen reshape

**Status:** DONE in v2.0.0 — 4 of 5 spec items shipped; 1 item (STL printer-loaded defaults) carries forward into v2.1.0.

| # | Spec item (§7 of UX brief) | Status |
|---|---|---|
| 1 | Reshape `PrintSetupSection` Compose card to editable rows (colour picker + material-type dropdown) | ✅ DONE in v2.0.0 |
| 2 | Wire overrides into `CanonicalFilamentList` so they drive slicing | ✅ DONE — `applyOverridesToCanonical` + `FilamentOverride(color, materialType)` |
| 3 | Per-file override persistence (file-hash keyed) | ✅ DONE — `viewModel.filamentOverrides` StateFlow |
| 4 | STL flow: defaults from `PrinterViewModel.syncFilaments` | ⚠️ PARTIAL — currently uses local `extruderPresets` (DataStore), not fresh printer-sync data. Fold into v2.1.0 |
| 5 | Mismatched-material chip in Filament mapping dialog rows | ✅ DONE — `FilamentMappingDialog.kt:227` (labelled "Phase 2.8" in code) |

New code shipped in v2.0.0:
- `app/src/main/java/com/u1/slicer/ui/FilamentColorEditDialog.kt` (Phase 2.6c colour picker)
- `app/src/main/java/com/u1/slicer/data/PerFilamentResolver.kt`
- `app/src/main/java/com/u1/slicer/data/CanonicalFilamentList.kt` + `CanonicalListAtLoad.kt`
- `applyOverridesToCanonical(canonical, overrides)` helper
- `PrintSetupSection` editable rows in `MainActivity.kt:3452`

---

## v2.1.0 — Hardening release

**Status:** NOT STARTED. Estimated ~2-3 days.
**Driver:** Reviewer 1's defense-in-depth backlog from Phase 2 reviews + the STL defaults polish from Phase 2.6 item 4. None are correctness blockers; they make the bug class structurally harder to re-open.

**Scope (in):**
- **B.1 finish — typed value classes end-to-end.** Make `PhysicalGcodePath` constructor `internal`. Expose explicit factories: `fromRemap(physical)`, `fromVerifiedLegacy(file)`, `fromIdentityCopy(file)`. Drop the public `PhysicalGcodePath.of(file)` shortcut. Thread `CanonicalGcodePath` / `PhysicalGcodePath` through `prepareExportableGcode*`, `saveGcodeTo`, `shareGcode`, `shareJobGcode`. Compiler then enforces "anything sent to printer went through a typed boundary".
- **Source-T defence on Send.** Before sending, scan source G-code for `^T(\d+)`. If any T ≥ 4 appears AND canonical lookup returned `Absent`, block the send with a clear error.
- **Tests.** Red test for Absent-misclassification (synthesise canonical G-code with T4+, force null canonical, verify Send blocked); regex test for multi-digit T (T10/T11); factory-correctness test for `PhysicalGcodePath`.
- **Phase 2.6 carry-over.** STL canonical-list defaults from `PrinterViewModel.syncFilaments` (currently uses stale local `extruderPresets`).

**Scope (out — explicitly deferred):**
- Anything requiring native rebuild → v3.0.0
- Anything requiring a second printer profile → v3.0.0

**Pre-tag checklist:** sweep green at HEAD, JVM tests green, focused E2E batch on Send/Save/Share/Jobs paths + a "block-on-Absent-multitool" manual test, version bump to 2.1.0 / versionCode 261, merge to main, build release APK, cut tag.

---

## v3.0.0 — Multi-printer via Orca profile import

**Status:** NOT STARTED. Estimated weeks. Needs its own brainstorming-skill design session + spec doc before any code.

**Driver:** Strategic roadmap clarification 2026-04-28 — support multiple printers in a single Android app, profile-driven via OrcaSlicer profile import. Folds in:

- **Profile import system.** Read `.orca_printer`, `.orca_filament`, `.orca_process` JSON. Handle 3MF-embedded variants. Validation (schema, required keys, conflict detection).
- **Profile merge logic (Kotlin).** Build a fully-resolved `Map<String, Any>` per active printer: base printer ⊕ active process ⊕ active filament per slot ⊕ user overrides ⊕ 3MF-embedded params.
- **JNI passthrough.** New `nativeApplyResolvedConfig(json)` consumes the resolved map. Replaces `applyConfigToPrusa`'s hardcoded values, the `profile_keys[]` whitelist, and the `is_snapmaker_profile` heuristic.
- **B.2 — config pipeline inversion.** Free byproduct of the JNI passthrough.
- **B.3 — PRINT_START heuristic obsoleted.** Profile carries explicit printer ID metadata.
- **Slot-count parameterisation.** `coerceIn(0, slotCount-1)` instead of hardcoded `0..3`. `meshAlignedFilamentColors` mod-N fallback uses `slotCount` from active profile.
- **UI.** Settings screen for imported profiles; active-profile indicator; migration of v2.x users to a pre-imported U1 profile.
- **First non-U1 printer profile + verification.** Real second printer; differential vs U1 must show only kinematics/extruder/bed differences from the profile delta.

---

## Phase 3+ — Roadmap

These are adjacent feature epics that depend on or follow from the refactor but are not part of "the refactor" proper:

| GH # | Topic | Status |
|---|---|---|
| #16 | Bambu printer support (talk to a Bambu printer, not just Bambu *files*) | Roadmap |
| #18 | FullSpectrum fork — mixed-colour / pseudo-extruder | Roadmap |
| #33 | AI-assisted colouring for single-colour prints | Roadmap |
| #56 | F66 Split to objects + auto-rotate | Roadmap |

These don't block v2.1 / v3.0 and are tracked as feature requests.

---

## What did NOT change in v2.0.0

- The 4-extruder cap on the Snapmaker U1 is unchanged. Phase 2 does NOT add support for >4 physical slots; it just stops conflating canonical filament fileIdx with physical slot inside the slicer. Slot-count parameterisation lives in v3.0.0 once there's a real second printer to design against.
- The orcaslicer submodule pinning. Submodule remains at `bd66b99b2d` (Snapmaker Orca 2.2.4 fork + Android-specific patches + post-Buzz H2C-fold fix). Upstream upgrades remain a separate consideration.
