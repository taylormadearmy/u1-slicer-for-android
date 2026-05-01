# U1 Slicer — Architectural Review Brief (since v1.6.13)

**Repository:** `taylormadearmy/u1-slicer-for-android`
**Review target branch:** `feature/phase2-canonical-filaments` (HEAD `df1817e`)
**Baseline:** `v1.6.13` (tag) — currently the public release
**Phase 1 merge point:** `origin/main` (which is `v1.7.0-dev`, versionCode 259, internal only — never released)
**Total commits under review:** 164 (100 Phase 1 + 74 Phase 2; ~10 shared infra)

---

## What changed at the architecture level

Since v1.6.13 the U1 Slicer has executed a two-phase architectural pivot. Both phases are coherent steps in a single migration: away from a Kotlin-heavy "slot-aware" model (where the user picks physical extruder slots before slicing) toward a **native-first read pipeline + canonical filament-list UX with print-time slot mapping** that mirrors how desktop slicers (PrusaSlicer, Bambu Studio, the desktop Snapmaker Orca that this app embeds) work.

### Phase 1 — native-first read pipeline (merged to main as `v1.7.0-dev`, never publicly released)

Moved Bambu / Snapmaker / Generic-3MF reading from Kotlin parsers to the embedded native C++ slicer (Snapmaker Orca 2.2.4) via per-area JNI accessors. The native importer (`bbs_3mf.cpp`) now sources the ground-truth state and Kotlin queries it via narrow JNI surfaces:

- `nativeGetProjectConfig` — `project_settings.config` JSON
- `nativeGetPlateCount` / `nativeGetPlateData` — per-plate metadata
- `nativeGetObjectCount` / `nativeGetVolumeCount` / `nativeGetObjectModelId` / `nativeGetVolumeScalars` / `nativeGetObjectExtruderMap` / `nativeGetAllVolumeExtruders` — object/volume identity + extruder assignments
- `nativeGetPaintStateCounts` / `nativeGetPaintStateSets` — paint state per volume (decoded faithfully via `PaintColorDecoder`)
- `loadModelForPlate(path, plateIdx)` — plate-aware loader using BBS importer's `plate_id` filter
- Layer-tool segments via native JSON (with XML scrape as permanent fallback)

Several Kotlin parsers were retired or @Deprecated as part of this:
- `ThreeMfMeshParser` — fully deleted (33 tests dropped)
- `BambuSanitizer.extractPlate` + `restructurePlateFile` — @Deprecated, no longer called from production (only Kotlin-pipeline regression tests still exercise them)
- Parts of `ThreeMfParser` — multi-plate paint scan skipped; per-plate path delegates to native

The migration was anchored by **Phase 0**: a per-fixture diff harness (`BambuFileSnapshot` Kotlin vs `NativeBambuSnapshot` C++) that compared every read field across 16+ Bambu/Snapmaker test fixtures. Each Phase 1 sub-plan closed a chunk of baseline diff entries; the harness only goes green when native reads match Kotlin reads byte-for-byte (modulo the deliberately-different concerns).

### Phase 2 — canonical filament list + print-time slot mapping (this branch, not yet merged)

Reframed the user's filament mental model from "which physical slot N (out of 4)" to "this is the file's filament N — pick a slot at Send time." The architectural primitives:

- **`CanonicalFilamentList`** — file-relative filament identity (one entry per `filament_colour` in the 3MF, per-paint-state entry for SEMM, plus synthetic singletons for STL/layer-tool). Bambu / PrusaSlicer / STL / Hueforge each have a reader that produces this list.
- **`applyPrintTimeRemap`** — non-destructive G-code rewriter that takes a `colorMapping: List<Int>` (canonical fileIndex → physical slot) and produces a remapped copy.
- **Filament Mapping dialog** at Send time — interposes between "Send to printer" and the actual upload, lets the user confirm which canonical filament goes to which physical slot.
- **Prepare-screen reshape** — the slot picker is gone; the Prepare card is now an editable list of file filaments where the user can override material type and colour per file-filament.
- **Slicer emits canonical-fileIndex T-indices** — `T<fileIndex>` not `T<physicalSlot>`. The `applyPrintTimeRemap` step at Send / Save / Share converts to physical.

The Phase 2 work also killed an entire bug class: `applyFilamentOverridesToPresets` (the cascade root) was retired. Where v1.6.13 had user overrides cascading through preset state, Phase 2 keeps overrides scoped to file filaments and only resolves to slots at Send. The cascade-detector regression test (`Phase2AlignmentTest`) confirms a PETG override on Filament 1 produces `nozzle_temperature = 235,220,...` with PETG only at index 0 — not bleeding to index 4 (which auto-mapped to slot 0 in default mapping pre-Phase-2).

The native-side `applyConfigToPrusa` parallel-write pattern was audited and a structural gate was added (and then narrowed twice based on an end-to-end G-code differential against v1.6.13). The current state: only `nozzle_temperature` + `nozzle_temperature_initial_layer` are gated by `is_snapmaker_profile`; all other per-filament keys (`hot_plate_temp`, fan curves, retraction, volumetric speed, slow_down, flush) write U1 hardware defaults unconditionally so Bambu/PrusaSlicer-prepared files don't silently corrupt prints with non-U1 values.

### What's verified end-to-end

- **Instrumented sweep:** 277/278 PASS at `0629b26` (penultimate commit; 1 expected SKIP). Final commit `df1817e` is +helper +2 call sites — sweep at HEAD is recommended before public release but unit-tests pass and the change doesn't touch any architectural surface.
- **G-code differential vs v1.6.13** across 5 representative fixtures (`GcodeBaselineDiffTest`): zero print-impact diff after gate narrowing; remaining differences are structural canonical-array-size only (10-wide vs 4-wide for SEMM files).
- **22/22 manual E2E fixtures** (Pixel 8a 43211JEKB16931, full results in `~/.claude/projects/.../memory/e2e-results-history.md` 2026-04-28 entry): 21 PASS + 1 PASS-with-pre-existing-notes.

### Known follow-ups (documented in BACKLOG.md)

- **B96 — SEMM T-index amplification** — pre-existing OrcaSlicer behaviour amplified by Phase 2's canonical-list expansion. `colored_3DBenchy` slice produces 126 transitions vs desktop's 9. Doesn't break prints (PrintTimeRemap handles canonical → physical at all 3 export paths) but inflates wipe-tower waste. Fix lives outside Phase 2 scope.
- **Dead `profile_keys[]` entries** in `sapil_print.cpp` — keys whose values are loaded by the Snapmaker-profile gate then immediately overwritten by `applyConfigToPrusa`. Harmless but worth tidying in a follow-up commit.

---

## Recommended review chunking — 10 PRs covering both phases

Reviewers should pick chunks based on their expertise — but every reviewer should at least skim the narrative above plus chunks 1, 7, 9 (the architectural load-bearing ones).

### Chunk-by-chunk

**1. Phase 0 — Bambu diff harness foundation** (14 commits)
- Range: `dff993b..e4f910c`
- The methodology proof. Every Phase 1 sub-plan closes a slice of this baseline.
- Reviewer focus: harness honesty (does it actually compare the right things?); the `KotlinBambuSnapshot` ↔ `NativeBambuSnapshot` contract.

**2. Phase 1 — Native Bambu reads (sub-plans #5/#2/#4)** (14 commits)
- Range: `187067c..73abff1`
- `nativeGetProjectConfig` + per-plate accessors + objects-via-JNI. Closes 242 baseline diff entries (130 + 92 + 20).
- Reviewer focus: JNI contract correctness; do native reads match Kotlin reads for every field consumed downstream?

**3. Phase 1 — ThreeMfMeshParser retirement** (6 commits)
- Range: `c82a420..4cee7b6`
- Native preview mesh becomes single source of truth for 3MF mesh geometry.
- Reviewer focus: are there any 3MF mesh consumers still expecting Kotlin-parsed output?

**4. Phase 1 — LayerToolPauseInjector dual-path (sub-plan #3)** (5 commits)
- Range: `f634c47..95229c1`
- Native-JSON path with XML scrape as permanent fallback for layer-tool / Hueforge files.
- Reviewer focus: fallback semantics — when does each path fire? Are both correct?

**5. Phase 1 — `loadModelForPlate` migration (sub-plans #2b/#2c/#2d)** (14 commits)
- Range: `9f128ef..bed0823`
- `selectPlate` no longer calls Kotlin `extractPlate` / `restructurePlateFile`; native plate filter drives plate selection end-to-end.
- Reviewer focus: are the deprecated Kotlin paths fully de-facto retired in production? `B81` regression guard still holds?

**6. Phase 1 — NativePlateState + Tier A/B regression tests + post-merge stabilisation** (~30 commits)
- Range: `5a93612..8f152de` (with stabilisation interspersed: `c41bfa9`, `ea420ea`, `d0fae54`, `bf3fec9`, `bba054a`, `cd12a4e`, `54dce6c`, `89d9dae`, `9adbae6`, `d7b0a31`, `6c8126a`, `93b29f1`, `4b37c61`, `a4fe1a9`, `a3c64a6`, `64cf55a`, `16a43bf`, `f011e9b`, `6bfc3e0`, `5cc1ac4`, `c05a189`, `418070d`, `e9c010d`, `fcd19b7`, `08c5927`, `8a2f165`, `3fdfa06`, `7b44dc5`)
- `NativePlateState` data class + parseVolumeMapJson + Buzz cold-load fixes + F1 calendar mapping + raw_bounding_box cache bypass + computeExpandedGcodeRemap distinctSlots fix + Tier A/B fixture harness + per-fixture @Test methods.
- Reviewer focus: regression-test discipline; is the harness actually load-bearing? Are the Tier A bug-reproductions tight?

**7. Phase 2.0/2.1 — canonical filament list foundation** (10 commits)
- Range: `495288a..dbf56a7`
- `CanonicalFilamentList` data classes + Bambu / PrusaSlicer / STL / Hueforge readers + dispatcher.
- Reviewer focus: data-model correctness across all 4 file flavours; reader robustness (paint state folding, file_colour parsing, STL singleton, layer-tool synthetic expansion).

**8. Phase 2.3–2.8 — UI surface (Send dialog + Prepare reshape)** (12 commits)
- Range: `4dd391c..954b848`
- `applyPrintTimeRemap` helper + Filament Mapping dialog + Prepare-card reshape (filament-list + tap-to-edit material + tap-to-edit colour) + override propagation + material-mismatch advisory chip.
- Reviewer focus: UX coherence with desktop slicers; dialog flow correctness; Compose patterns.

**9. Phase 2 — slice pipeline canonical overhaul + display correctness + plate narrowing** (~30 commits)
- Range: `4ea1724..f71e9e9` (the architectural meat — interleaves slice, display, and plate concerns)
- Kill `applyFilamentOverridesToPresets` cascade root; retire `extCount` (split into `slotCount` + `filamentCount`); canonical T-indices in slice; per-filament `filament_type`/`nozzle_temperature`; `GcodeParser` T-index uncap; 3D + G-code Preview palette canonical-driven; `meshAlignedFilamentColors`; mesh-uncompact; plate filament chips on selector; canonical-driven plate narrowing; `refreshCanonicalFilamentList` reads `rawInputFile`.
- Reviewer focus: cascade bug fix completeness (`Phase2AlignmentTest` cascade detector); slice-output correctness across SEMM / per-object / layer-tool / STL paths; plate-switch semantics; revert/reapply discipline (one fix was reverted then reapplied — check why).

**10. Phase 2 — native gate audit + Group B delete + Save/Share remap finalisation** (~15 commits)
- Range: `e3d0272..df1817e`
- `applyConfigToPrusa` parallel-write audit + structural native gate + 4 review-surfaced cascade-pattern bug fixes + Group B legacy slice-time remap deletion (-1,263 LOC) + native rebuild fix (worktree-vs-main-repo source mismatch) + gate narrowing (twice — first to material-tuned, then to nozzle-temp-only based on G-code differential) + Save/Share PrintTimeRemap fix.
- Reviewer focus: gate decision rationale (which keys clamp to U1 defaults / which flow from embed); G-code differential methodology; ship-readiness of Save/Share; Group B deletion safety (-1,263 LOC of removed code — is anything in production still referencing it?).

---

## Cross-cutting concerns reviewers should chase

These don't fit cleanly into one chunk — flag them across the review:

- **Where exactly is the kotlin↔native boundary now?** Is it consistent? Are there places where Kotlin still parses something the native side could authoritatively answer?
- **Test honesty.** Many tests were rewritten during Phase 2 (B95, h2cBenchy, Phase2AlignmentTest). Do the rewritten tests still assert the originally-intended behaviour, or did the assertion semantic drift?
- **`is_snapmaker_profile` semantic.** Phase 2's gate uses this flag (start G-code contains "PRINT_START"). Is the flag detection robust against mis-tagging? What happens if a Bambu file's start G-code happens to contain that substring?
- **Canonical-fileIndex space leakage.** Phase 2 emits canonical-space T-indices. The 3 export paths (Send, Save, Share) all remap. Is there any 4th export path or downstream consumer that bypasses remap?
- **B48 padding interaction with canonical lists.** B48 padding extends per-extruder vectors using the last value. With Phase 2's canonical-list of 10 entries, what happens when only 4 are actually used? (See B96 BACKLOG entry — this is the SEMM amplification's likely cause.)

---

## What reviewers do NOT need to focus on

- **Print quality / colour calibration** — out of scope for an architectural review; covered by the 22-fixture E2E batch results.
- **Public release readiness** — Phase 2 is on a feature branch; v1.6.13 remains the public release. Phase 1 (`v1.7.0-dev`) was internal-only.
- **The Save/Share UX itself** — the fix routes through the existing `colorMapping` StateFlow without adding a new dialog. UX-design feedback can wait until Phase 2 ships and we get user data on whether the implicit-mapping behaviour is intuitive enough.

---

## How to set up locally

```bash
git clone https://github.com/taylormadearmy/u1-slicer-for-android.git
cd u1-slicer-for-android
git fetch
git checkout feature/phase2-canonical-filaments

# Diff against v1.6.13 baseline:
git log v1.6.13..HEAD --oneline | wc -l   # 164

# Per chunk:
git log <range> --oneline
git diff <range>
```

Build / test:
```bash
./gradlew testDebugUnitTest                # JVM unit tests (~30s, ~870)
./gradlew connectedDebugAndroidTest        # device tests (~2h, 277/278 last run)
```

For native investigation:
- `app/src/main/cpp/` — Snapmaker Orca 2.2.4 fork
- `app/src/main/cpp/src/sapil_print.cpp` — JNI entry points + `applyConfigToPrusa` + `profile_keys[]` whitelist
- `app/src/main/cpp/src/sapil_bambu_*.cpp` — Bambu reading (Phase 1 native accessors)
- `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so` — pre-built; verify SHA-1 matches a fresh rebuild if you suspect drift
