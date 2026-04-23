# Phase 1 Kickoff — Fresh-Session Handoff

**For:** the next fresh session picking up Phase 1 sub-plan #1 ("Painted facets → preview mesh").
**Prior session:** set up the worktree, completed Phase 0 (10 tasks, all committed and reviewed), rebased onto v1.6.13, applied review follow-ups.
**Current branch HEAD:** will be shown by `git log -1 --oneline` in the worktree — should be a cleanup commit after Phase 0's `b245ac7` baseline.

## Before you start — read these in order

1. **Memory entries** (in `C:\Users\kevin\.claude\projects\c--Users-kevin-projects-u1-slicer-orca\memory\`):
   - `project-bambu-refactor.md` — overall refactor state, priority order, file paths
   - `feedback-bambu-refactor-gotchas.md` — operating gotchas (these WILL bite you otherwise)
2. **Strategy doc:** `docs/architecture/2026-04-23-bambu-via-native-loader.md` — why the refactor, what's chosen, what's rejected
3. **Phase 0 plan:** `docs/superpowers/plans/2026-04-23-bambu-diff-test-harness.md` — the completed plan; read "Task 4" (JNI pattern), "Task 5" (per-plate expansion pattern), "Task 7" (per-volume / FacetsAnnotation usage)
4. **Phase 1 design notes for sub-plan #1:** `docs/superpowers/plans/2026-04-23-phase1-painted-facets-design-notes.md` — pre-flight research dispatched in the prior session; read this first for sub-plan #1
5. **Phase 1 roadmap:** `docs/superpowers/plans/2026-04-23-phase1-roadmap.md` — skeletons for sub-plans #2-#5
6. Your `CLAUDE.md` workspace reminders and `CLAUDE.local.md` if present

## Critical operating rules (also in memory, repeating here)

- **Worktree:** `c:/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native/`. **NOT** the main repo at `c:/Users/kevin/projects/u1-slicer-orca/`.
- **Bash CWD gotcha:** every Bash starts at main-repo CWD. Use `cd /c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native &&` inside each call, or absolute paths with a `WT=` prefix at the top. State doesn't persist across calls.
- **DEX:** androidTest methods use `snake_case_names()`, no backticked spaces.
- **Assets:** instrumented tests use `InstrumentationRegistry.getInstrumentation().context.assets` (test APK), NOT `targetContext.assets`.
- **Install:** Pixel 8a `43211JEKB16931` has phantom v257. Use `adb uninstall ... ; adb install -r -d ... ; adb shell am instrument ...`.
- **Build: `git restore -- app/src/main/cpp/extern/` ALWAYS before staging commits** (build dirties line endings on vendored docs).
- **Native rebuild:** NDK 26 / Clang 17 / Release / ~20MB stripped. Incremental is 2-15 min; never ship unstripped (516MB) or Debug (80MB).

## Current branch state summary

Branch `refactor/bambu-via-native-loader` off v1.6.13 (`26e6cf2` on origin/main). Phase 0 committed 15 commits, then review follow-ups landed:

- `phase0(bambu-diff): drop redundant loadModel + stale-baseline detection` (cleanup #1 + #5)
- `phase0(bambu-diff): re-categorise 16 'Kotlin bug' entries as intentional` (cleanup from audit C)
- `phase0(bambu-diff): collapse volumes[N] diffs when one side empty` (cleanup #4 — if landed; if not, land it in your session first)
- `docs(phase1): roadmap for sub-plans #2-#5`
- `docs(phase1): painted facets design notes` (sub-plan #1 research)

If any of these aren't in `git log`, the prior session ran out of time — land them first using the details in the cleanup section of `docs/architecture/2026-04-23-bambu-via-native-loader.md` or the in-progress-cleanup notes in the `/tmp/` area (check `/tmp/baseline-current.json` for any mid-flight state).

Diff harness is **GREEN** at 21/21 fixtures with 664 entries across the baseline (count may be lower after cleanup #4 collapses volumes[N] entries).

## Your task: Phase 1 sub-plan #1 — Painted facets → preview mesh

**Scope is smaller than the strategy doc suggested.** The prior session's pre-flight research (agent A, findings in `2026-04-23-phase1-painted-facets-design-notes.md`) found that **production hot paths have already moved off `ThreeMfMeshParser`** (B46). The only remaining production caller is `ModelViewerScreen.kt:42`, and it doesn't even use the paint data — the mesh renders with the default grey. So sub-plan #1 is fundamentally a **diff-harness closure job**, not a production refactor.

**Recommended first step from the design doc:** Option C — a **counts-only** JNI accessor (`nativeGetVolumePaintCounts(objectIdx, volumeIdx) → Map<Int, Int>` or similar). ~15 lines of C++ lifted from `sapil_bambu_snapshot.cpp`'s `count_paint_states` helper, behind a new JNI entry. Populates `KotlinBambuSnapshot.volumes[N].paintStateSet`. Closes ~420 baseline entries without touching any production render path.

**Goal:** close ~420 baseline entries (`volumes[N]` paths) via a counts-only JNI accessor; retiring `ThreeMfMeshParser` (and fixing `ModelViewerScreen.kt` to use native preview mesh) is a follow-up, possibly bundled with sub-plan #2 or done in a separate cleanup commit.

### Order of operations

1. **Read the design notes** (`docs/superpowers/plans/2026-04-23-phase1-painted-facets-design-notes.md`) — the prior session's research agent documented:
   - All production call sites of `ThreeMfMeshParser` paint extraction
   - `FacetsAnnotation::get_facets` API signature + performance
   - 2-3 candidate JNI accessor shapes, with a recommendation
   - Integration points for each production caller
   - Open risks

2. **Write the detailed TDD plan** using the `superpowers:writing-plans` skill. The design doc gives you the scope; you break it into bite-sized TDD tasks.

3. **Set up worktree** — already set up; verify with `git log -1 --oneline` matching the expected head.

4. **Execute the plan** using `superpowers:subagent-driven-development` (recommended — worked cleanly for Phase 0).

5. **Between sub-plan steps** — the diff harness is your acceptance gate. After each step, re-run the corpus. Some baseline entries should close; if unexpected new diffs appear, stop and investigate.

### Starting baseline expectations

Closing 420 `volumes[N]` entries is the headline. But some may persist as real issues:
- Benchy-like files with component-ref geometry may continue to show Kotlin-side `objects` gaps if the Kotlin path still relies on `info.objects.size`
- Paint-supports (state 1/2 on `supported_facets`) is a separate concern — sub-plan #1 can choose to include or defer

### Sub-plan #1 is the biggest Phase 1 task — expect multi-day

Rough shape: ~15 TDD steps, 1-2 native rebuilds (first for the JNI accessor, second if C++ expansion needed), plus production code replacement in the preview renderer.

## After sub-plan #1 lands

Update `project-bambu-refactor.md` memory with the baseline-closure count. Then dispatch fresh session for sub-plan #2 (per-plate PlateData) — design notes already sketched in the roadmap doc; that session writes its own design + plan.

## Decision points for you

- **Want to wrap pre-flight research differently?** The prior session dispatched research agents that saved findings to docs. If the design docs don't exist or are incomplete, re-dispatch.
- **Hit a C++ blocker?** (e.g. the API you need isn't exposed by upstream Orca.) Escalate with a specific question — don't rewrite the engine.
- **Diff harness shows more disagreements than expected after your change?** Stop, investigate. Could be (a) your JNI accessor emits a field Kotlin doesn't read, (b) production code change broke the snapshot path, (c) baseline categorisation was wrong. The previous session's audit entry (C subagent) re-tagged 16 entries — reference their commit message for the pattern.

## Contact / artefacts

- Prior-session artefacts in `/tmp/`:
  - `/tmp/diff-corpus-postB95.log` — post-rebase corpus run (reference)
  - `/tmp/diff-corpus-empty-postB95.log` — corpus with empty baseline (raw diff output)
  - `/tmp/baseline-current.json` — backup of pre-cleanup baseline (probably safe to delete once cleanup #4 lands)
  - `/tmp/diff-corpus-baseline.txt`, `/tmp/diff-per-fixture-clean.txt` — from Task 9 (may have been cleaned)

- Key Phase 0 commits to grep history (post-rebase SHAs will differ from my list above — use `git log --grep=phase0` to find them by message).

## Sub-plan #1 status: LANDED (2026-04-23)

Baseline closure:
- Pre-sub-plan-#1 total: 265 entries (post-cleanup-#4).
- Post-sub-plan-#1 total: 242 entries.
- Closed in this sub-plan: 23 = 21 `volumes.size` entries (the headline) + 2 ID-keyed `objects[N]` entries that became stale once `BambuSnapshotDiff.diffObjects` switched to positional matching.

Changes shipped:
- Five counts-only JNI accessors on `NativeLibrary`: `nativeGetObjectCount`, `nativeGetVolumeCount`, `nativeGetObjectModelId`, `nativeGetVolumeScalars`, `nativeGetPaintStateCounts` (kind 0 mmu / 1 supports). All pure reads of `g_model`; callers hold `NativeLibrary.previewMutex`.
- `sapil::count_paint_states` promoted from anonymous namespace in `sapil_bambu_snapshot.cpp` to a public helper declared in `sapil_bambu_snapshot.h` so Phase 0's JSON emitter and the new JNI accessors share one implementation.
- `KotlinBambuSnapshot.snapshot` gained a `suspend` signature + `NativeLibrary` param; populates `volumes` by walking the new accessors under `previewMutex`.
- `BambuSnapshotDiff.diffObjects` + `diffVolumes` switched from `(objectId, volumeIndex)` map matching to positional zip matching. Reason: Slic3r runtime `ObjectID` is reassigned per `Model::read_from_file`, so the two snapshot paths (each of which triggers its own load) never agree on ID by construction. The per-field checks (name / extruder / paint counts / flags) are what's semantically meaningful.

Tests:
- 6 new `NativeLibraryCorrectnessTest` cases (4 Flarewing-Dragon based + 1 empty-model guard + 1 `kind=1` supports structural smoke), all green on Pixel 8a.
- `KotlinBambuSnapshotTest` upgraded with volumes assertions (volume list non-empty, objectId > 0, at least one `isMmPainted`).
- Full differential suite 21/21 green.
- Full Bambu instrumented suite 26/26 green (no regression).
- JVM unit suite green.

Out of scope (explicitly deferred):
- `ThreeMfMeshParser` retirement — `ModelViewerScreen.kt:42` remains the last production caller but doesn't use paint data; bundling retirement with a later sub-plan.
- `ObjectSnapshot`/`VolumeSnapshot` renaming `objectId` → `objectIndex` — the semantics have shifted (positional diff, not ID identity), but the field name stays for now to keep the Phase 0 JSON contract. Worth a follow-up cleanup.

Next: Sub-plan #5 (project config + filament colours) per roadmap — closes 46 entries and establishes the `DynamicPrintConfig` accessor pattern #2 needs.

## Sub-plan #5 status: LANDED (2026-04-23)

Baseline closure:
- Pre-sub-plan-#5 total: 242 entries.
- Post-sub-plan-#5 total: 150 entries.
- Closed: 92 (larger than the roadmap's 46 estimate — see breakdown below).

Closure breakdown (per differential-suite stale-entry report — zero unexpected diffs):
- 20 `fileVersion` entries (one per fixture).
- 19 `plates[*].filamentColours.size` entries.
- 50 `plates[*].filamentSettingsIds.size` entries — larger than predicted because the Kotlin snapshot path used to emit `emptyList()` for this field; populating from project-level `filament_settings_id` / `filament_ids` closed the entire gap at once. The roadmap under-counted because it scoped `filament_settings_id` to sub-plan #2's per-plate work, but the project-level fallback covers the unsliced-plate case for free.
- 3 `plates[0].filamentColours[N]` content entries on `colored_3DBenchy` — the RGBA vs RGB hex-format diffs. Both sides now flow through `sapil::colour_to_hex`, so the 8-char native values also reach Kotlin (where the regex-based detector was truncating to 7 chars). Closed incidentally.

Changes shipped:
- New JNI accessor `NativeLibrary.nativeGetProjectConfig(): String?` returning a JSON blob with `isBbl`, `fileVersion`, `filamentColours`, `filamentSettingsIds`, `filamentIds`. Pure read of `g_is_bbl`, `g_file_version`, and `getModelConfig()`.
- New C++ TU `sapil_bambu_project.cpp` owns the JNI entry point.
- `sapil::json_escape` and `sapil::colour_to_hex` promoted from the anonymous namespace in `sapil_bambu_snapshot.cpp` to `namespace sapil` so the new TU can reuse them without duplication.
- `KotlinBambuSnapshot.snapshot` parses the new JSON under the existing `previewMutex + loadModel` scope (method renamed `readVolumesViaNative` → `readNativeData`) and maps the five fields into `BambuFileSnapshot.isBbl`, `BambuFileSnapshot.fileVersion`, `PlateSnapshot.filamentColours` (uniform project palette per plate), and `PlateSnapshot.filamentSettingsIds`. Kotlin fallbacks retained when native `loadModel` fails (corrupt file).

Tests:
- `BambuParserDifferentialTest` 21/21 green against pruned baseline.
- `NativeLibraryCorrectnessTest` 12/12 green (including 2 new `nativeGetProjectConfig` tests).
- `KotlinBambuSnapshotTest` 1/1 green with updated assertions (8-char hex values, non-empty fileVersion + filamentSettingsIds).
- Full Bambu instrumented package 26/26 green.
- JVM unit suite green.

Out of scope (deferred):
- Per-plate `slice_filaments_info` override for `PlateSnapshot.filamentColours` — sub-plan #2's job.
- `ThreeMfMeshParser` retirement — still deferred.
- Production `isBambu` / `detectedColors` call sites remain on the Kotlin fast path; the new accessor is snapshot-only.

Next: Sub-plan #2 (per-plate PlateData) per roadmap — the largest sub-plan, targeting the remaining plate-level entries: `plateIndex`, `objectInstanceMap`, any surviving filamentColours per-plate content diffs, and `plateConfig`.
