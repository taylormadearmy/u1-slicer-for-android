# U1 Slicer — Release Roadmap v2.0 → v2.1 → v3.0

Date: 2026-04-28
Branch: `feature/phase2-canonical-filaments` (HEAD `0d6831a` at time of writing)
Status: **canonical roadmap document** — preserves intent + sequencing in case of session loss.

---

## TL;DR for a fresh agent

1. Current public release is `v1.6.13`. Branch `feature/phase2-canonical-filaments` carries Phase 1 (Bambu native-first reads, on `main` as `v1.7.0-dev`) PLUS Phase 2 (canonical filament list architecture). All 4 review rounds are clean. **DO NOT cut a v1.7.0 tag** — we're renumbering.
2. The next public tag is **`v2.0.0`** (skip v1.7 entirely). Reflects the actual scope: major architectural refactor (canonical filaments + native-first 3MF reads + Prepare reshape + schema migrations).
3. After v2.0.0 ships there's a **`v2.1.0` hardening release** that lands the deferred Reviewer 1 defense-in-depth items as a single coherent bundle.
4. Then **`v3.0.0` is the multi-printer epic** — Orca profile import, config inversion, slot-count parameterisation. Probably weeks of work; gets its own design pass.

The renumbering is one-time. There is no v1.7 / v1.8.

---

## Why renumber

Reasons for `v1.6.13 → v2.0.0` (not v1.7.0):

- **SemVer correctness.** Phase 2 changes the slicer's internal T-index space (canonical fileIndex vs physical slot), reshapes the Prepare screen UX, introduces print-time slot mapping, migrates Room schema (v4 → v6), and replaces large parts of the Bambu read pipeline. That's major-version material, not a minor-version bump.
- **User-perceived change.** Existing users will see a different Prepare screen, a new mapping dialog at Send, different error states for partial canonical lookups. Setting expectation via major version is honest.
- **Phase 3 will be another major.** Multi-printer support is clearly v3.0.0. Phase 2 staying on v1.x makes the v1 → v2 jump confusing; it should already be on v2.x by then.

---

## v2.0.0 — Phase 2 ships

**Branch / source:** `feature/phase2-canonical-filaments`. Tag from current HEAD after the pre-tag checklist below passes.

**Headline narrative:**
> "Major refactor of the slicing pipeline. The Prepare screen now shows the file's filaments as a directly-editable list; physical-slot mapping happens at Send time via a new Filament Mapping dialog. Bambu 3MF reading moved from Kotlin parsers to the embedded native slicer for accuracy and speed. Per-filament material/colour overrides flow end-to-end from Prepare through slice and into G-code without cascade leaks. Multi-plate plate-switching now reuses native plate state instead of re-extracting in Kotlin. Schema migrated from v4 to v6 (per-job canonical filament metadata + selected-slot persistence). All 4 review rounds (8 P1/P2 findings + 1 architectural pivot) addressed."

**Scope (in):**
- All commits since `v1.6.13` on `feature/phase2-canonical-filaments`. ~165 commits, two phases:
  - Phase 1 (already on `main` as `v1.7.0-dev` internal): native-first Bambu reads. Sub-plans #2/#2b/#2c/#2d/#3/#4/#5. ThreeMfMeshParser retired. NativePlateState + Tier A/B regression tests. Phase 0 diff harness.
  - Phase 2 (this branch): canonical filament list, Prepare reshape, Send mapping dialog, native gate narrowed to nozzle_temperature only, applyConfigToPrusa cascade kill, B.1 type-safe send boundary (partial), 4 review rounds of fixes.

**Scope (out — explicitly deferred):**
- Reviewer 1's "B.1 finish" hardening (constructor gating, full Save/Share/Jobs type-threading). → v2.1.0
- Source-T-index defence-in-depth on Send. → v2.1.0
- Absent-misclassification regression test. → v2.1.0
- B.2 (config pipeline inversion). → v3.0.0 (folds into Orca profile import)
- B.3 (PRINT_START heuristic native-side replacement). → v3.0.0 (obsoleted by profile metadata)
- Slot-count parameterisation (Phase 2 hardcoded `0..3`). → v3.0.0 (need second printer profile to design against)

**Pre-tag checklist** (must complete before pushing v2.0.0 tag):

1. **Instrumented sweep at HEAD** — green. Currently running as background task `bb4b8sdaf` at commit `6074819` (one commit behind HEAD). The HEAD diff over `6074819` is `02a8653` (F2/F7 read-path fixes, Kotlin-only) + `0d6831a` (review4 doc). The sweep at `6074819` is sufficient signal because the F2/F7 changes don't touch any code path the instrumented tests exercise (they affect live preview palette + export-mapping fallback, neither covered by JUnit assertions today).
2. **JVM unit tests at HEAD** — green. Already verified after each commit; re-run before tag.
3. **Full 22-fixture E2E batch at HEAD** — pass. Use `scripts/run-instrumented-sharded.sh` if Pixel 6 is available, else single-device ANDROID_SERIAL=43211JEKB16931. Compare against `e2e-results-history.md` 2026-04-28 entry baseline. Record results in a new dated history section.
4. **G-code differential vs v1.6.13** — clean. Run `GcodeBaselineDiffTest` against v1.6.13 baseline snapshots; only structural canonical-array-size diffs should remain.
5. **Version bump:**
   - `app/build.gradle`: `versionName "2.0.0"`, `versionCode 260` (incrementing from 259's v1.7.0-dev internal).
   - `README.md`: update current-release line.
   - `CLAUDE.md`: update current-release line + test counts if any drifted.
6. **Branch merge:** rebase or merge `feature/phase2-canonical-filaments` to `main`. Phase 1 history (commits `dff993b..8f152de`) already on main — Phase 2 commits are linear on top. Likely a fast-forward; if not, merge commit is fine.
7. **Build release APK:** `./gradlew assembleRelease --no-daemon`, rename to `u1-slicer-v2.0.0.apk`.
8. **Cut GitHub release** with the headline narrative + open-items disclosure (B.1 finish, source-T defence, Phase 3 multi-printer all on the public roadmap).
9. **Update memory:** add entry to `~/.claude/projects/c--Users-kevin-projects-u1-slicer-orca/memory/` describing the v2.0.0 cut + roadmap.

**Estimated time:** half a day from "sweep returns green" to "tag pushed", assuming E2E batch is clean.

---

## v2.1.0 — Hardening release

**Driver:** Reviewer 1's defense-in-depth backlog. None of these are correctness blockers (round 2/3 fixes closed the actual bugs); they make the bug class structurally impossible to re-open.

**Scope (in):**

### B.1 finish — value-class type safety end-to-end
1. Make `PhysicalGcodePath` constructor `internal`. Expose factory functions:
   - `PhysicalGcodePath.fromRemap(physical: PhysicalGcodePath)` — internal, only used by `applyPrintTimeRemap` typed overload.
   - `PhysicalGcodePath.fromVerifiedLegacy(file: File)` — explicit "I know this file is already physical-slot space (pre-Phase-2 / v1.6.13 era)". Used by `shareJobGcode` for null-canonical jobs.
   - `PhysicalGcodePath.fromIdentityCopy(file: File)` — explicit "this is the result of a no-mapping copy". Used by Absent-canonical-lookup path.
   - Drop the public `PhysicalGcodePath.of(file)` shortcut.
2. Thread `CanonicalGcodePath` / `PhysicalGcodePath` through:
   - `SlicerViewModel.prepareExportableGcode(...)` — takes `CanonicalGcodePath`, returns `PhysicalGcodePath`.
   - `SlicerViewModel.prepareExportableGcodeWithMapping(...)` — same.
   - `SlicerViewModel.saveGcodeTo(uri: Uri)` — internal pipe is typed, even though `Uri` boundary stays.
   - `SlicerViewModel.shareGcode()` + `shareJobGcode(job)` — same.
   - `MainActivity.kt` Send dialog block — already typed for direct printer calls; verify no raw-string leaks remain.
3. Add a top-level invariant: any code path that produces a file the printer will read MUST go through one of these factories. The compiler enforces it.

### Source-T defence-in-depth on Send
1. Before sending, scan the source G-code for `^T(\d+)` lines.
2. If any T-index ≥ 4 appears AND the canonical lookup didn't return Present (i.e. we're in Absent fallback path), block the send with a clear error: "Multi-tool G-code requires canonical filament list. Re-load the source file."
3. Allow override via explicit user confirmation? Probably not — false positive rate would be near zero (Absent + T4+ implies a corrupted or third-party-generated file).

### Tests added
1. **Red test for Absent-misclassification:** synthesise a canonical-source G-code with T4+ in body, force `getCanonicalFilamentList()` to return null, attempt Send → assert blocked / errored, NOT printed.
2. **Test for source-T detection on Send:** verify the regex catches T10/T11 multi-digit.
3. **Test for `PhysicalGcodePath` factory correctness:** verify factory paths produce valid output, raw-string construction is impossible.

### Cleanup that's easy while we're here
- Remove `diff.patch` and `sapil.patch` files left in worktree (not committed but cluttering).
- Sweep CLAUDE.md / E2E_TESTING.md for stale test counts after v2.1.0.

**Scope (out):**
- Anything that requires native rebuild (folded into v3.0.0).
- Anything that requires a second printer profile (folded into v3.0.0).

**Pre-tag checklist** (same shape as v2.0.0):
1. Sweep green at HEAD.
2. JVM tests green at HEAD.
3. E2E batch — focused on Send/Save/Share/Jobs paths plus a "block-on-Absent-multitool" manual test.
4. Version bump to 2.1.0 / versionCode 261.
5. Merge to main, build release APK, cut tag.

**Estimated time:** 2-3 days focused work + verification. Could be a single agent session if context allows; or split across two sessions (B.1 finish in one, source-T + tests in another).

---

## v3.0.0 — Multi-printer via Orca profile import

**Driver:** Strategic roadmap clarification 2026-04-28: support for multiple printers, single Android app, profile-driven via OrcaSlicer profile import.

This is a **proper feature epic**, not a refactor. Probably weeks of work. Will need its own brainstorming-skill design session and spec doc before any code is touched.

**Scope (in):**

### Profile import system
- Read `.orca_printer` JSON (machine kinematics, bed type, nozzle, etc.).
- Read `.orca_filament` JSON (per-material temperature, retraction, fan).
- Read `.orca_process` JSON (layer height, walls, infill, support strategy).
- Handle 3MF-embedded variants (`Metadata/printer_settings.config`, etc.).
- Profile validation: schema check, required keys present, conflict detection.

### Profile merge logic (Kotlin-side)
- Build a fully-resolved `Map<String, Any>` per active printer:
  ```
  Base printer profile  (.orca_printer)
    + Active process profile (.orca_process)
    + Active filament profile (.orca_filament) per slot
    + User overrides from settings
    + 3MF-embedded canonical params
  ```
- Order matters; later layers override earlier.
- Output: a single config map ready for JNI passthrough.

### JNI passthrough
- New accessor: `nativeApplyResolvedConfig(json: String)` consumes the resolved JSON map and applies each key to the engine's `DynamicPrintConfig`. Type-safe per-key dispatch (string vs int vs float vs array) inside the C++ side, no defaulting.
- Removes the need for `applyConfigToPrusa`'s hardcoded values entirely.
- Removes the need for `profile_keys[]` whitelist (every key in the resolved map is whitelisted by construction).
- Removes `is_snapmaker_profile` heuristic (the profile carries explicit printer ID metadata).

### B.2 — config pipeline inversion (folded in)
This is essentially what the JNI passthrough achieves. Reviewer 1's original ask becomes a free byproduct of the multi-printer architecture.

### B.3 — PRINT_START heuristic obsoleted
The profile carries explicit printer identification. No more substring matching.

### Phase 2 surface parameterisation
- `resolveCanonicalExportMapping` clamp `coerceIn(0, slotCount-1)` instead of `0..3`.
- `meshAlignedFilamentColors` mod-N fallback uses `slotCount` from active profile.
- `GcodeRenderer` palette already dynamic; verify cap (currently 32 in `GcodeParser`) is sufficient.
- `SliceJob.colorMappingCsv` schema unchanged — already free-form.

### UI for printer profile management
- Settings screen: list imported profiles, mark one active.
- Import flow: file picker for `.orca_*` files; show validation errors.
- Active-profile indicator: the user always knows which printer the slice will produce G-code for.
- Migration: existing v2.x users automatically get the U1 profile pre-imported as the active default.

### First non-U1 printer profile + verification
- Import a real second printer profile.
- Slice the same model with both active.
- `GcodeBaselineDiffTest` extended to cover the second printer.
- Differential between printers must show ONLY the kinematics / extruder / bed differences expected from the profile delta.

**Scope (out):**
- Per-printer-specific UI features (e.g. printer-specific Send transports). Stays orthogonal.
- Profile editor (creating profiles in-app). Import-only is fine for v3.0.0; in-app editing can be v3.x.

**Pre-design steps:**
1. Brainstorming-skill session: profile schema, merge order, validation rules, UI shape, migration story.
2. Write spec doc in `docs/superpowers/specs/`.
3. Write implementation plan via writing-plans skill, anchored to `GcodeBaselineDiffTest`-passing chunks.
4. Then: chunked implementation per the plan.

**Estimated time:** weeks. Don't try to scope it more tightly without the design pass.

---

## Decision log

**Why v2.0 → v2.1 → v3.0 instead of v2.0 → v3.0 directly:**
The hardening items (B.1 finish, source-T defence) are bounded (2-3 days), independent of Phase 3, and meaningful as their own release. Bundling them into v3.0.0 would make v3.0.0 even bigger; doing them as v2.1.0 gives the v2.x line time to soak in production while v3.0 is being designed.

**Why hardening as a single bundle vs incremental v2.1 / v2.2 / v2.3:**
The items share context (type-safety boundary, defense-in-depth around Send) and are small enough to fit in one focused session. Single bundle avoids release ceremony overhead.

**Why slot-count parameterisation defers to v3.0:**
Reviewer 1's caution about premature abstraction. Without a second concrete printer profile to design against, the abstraction risks getting the shape wrong. v3.0 has a real second printer to verify against.

**Why B.2 / B.3 fold into v3.0 instead of staying as their own pivots:**
Once Orca profile import is the multi-printer strategy, B.2 (config inversion) IS the architecture rather than cleanup, and B.3 (PRINT_START replacement) is automatically obsoleted by profile metadata. They're free byproducts.

**Why no v1.7 / v1.8:**
Honest semver. Phase 2 is a major version on user-perceived change alone (Prepare reshape, Send dialog, schema migration, slicer pipeline rewrite). Multi-printer is clearly major. Skipping the intermediate to keep the version line clean: v1 = pre-Phase-2 era, v2 = canonical filaments era, v3 = multi-printer era.

---

## "Pick up here if context lost" — fresh-agent quick-start

If you're a fresh agent and the prior session is gone:

1. **Read this doc top to bottom.**
2. **Verify current state:**
   ```bash
   cd c:/Users/kevin/projects/u1-slicer-orca/.worktrees/phase2-canonical
   git log --oneline -5    # confirm HEAD is at or after `0d6831a`
   git status              # should be clean
   ```
3. **Check what's pending:**
   - Sweep at HEAD green? Look at `c:/Users/kevin/AppData/Local/Temp/claude/c--Users-kevin-projects-u1-slicer-orca/d0b35d12-27f6-4657-a1f8-7ae5ff82d2d2/tasks/*.output`
   - E2E batch at HEAD done? Look at `c:/tmp/e2e-results-phase2/` for the latest dated batch.
   - v2.0.0 version bump in `app/build.gradle`? Should be `versionName "2.0.0"`, `versionCode 260`.
4. **Identify next step from the v2.0.0 pre-tag checklist** (above) and execute.
5. **Don't merge to main without user confirmation.** "Tag from HEAD" is final-step-only.

Reviews referenced (all in `docs/superpowers/reviews/`):
- `2026-04-26-phase2-architecture-review.md` — pre-Phase-2 review
- `2026-04-28-architectural-review-brief.md` — round 1 brief
- `revews1and2.md` (note typo, original two reviews from round 1)
- `2026-04-28-adversarial-review-v1.6.13-to-phase2.md` — round 1 reviewer 3 detailed
- `2026-04-28-delta-review-brief.md` — round 2 brief
- `review2.md` — round 2 verdicts (3 reviewers)
- `review3.md` — round 3 verdicts (3 reviewers)
- `review4.md` — round 4 closeout

Specs referenced:
- `docs/superpowers/specs/2026-04-28-canonical-export-mapping-helper-design.md` — original Group A design
- `docs/superpowers/specs/2026-04-28-b2-b3-handoff.md` — B.2/B.3 handoff (now folds into v3.0.0)
- this doc — the overall roadmap

---

## Status as of 2026-04-28 17:30

- HEAD: `0d6831a` (review4 doc commit)
- Branch pushed to origin: yes
- Sweep status: `bb4b8sdaf` running at `6074819` (one commit behind HEAD, F2/F7 + docs delta)
- All review rounds: closed clean
- Next action: wait for sweep, run E2E batch at HEAD, version bump to 2.0.0, cut release
