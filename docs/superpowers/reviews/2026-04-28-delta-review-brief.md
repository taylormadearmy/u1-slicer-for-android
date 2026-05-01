# Delta Review Brief — Round 2

**Repository:** `taylormadearmy/u1-slicer-for-android`
**Branch:** `feature/phase2-canonical-filaments`
**Scope:** commits `df1817e..d371e31` (4 commits, ~600 LOC including handoff spec)
**Prior review:** `docs/superpowers/reviews/2026-04-28-adversarial-review-v1.6.13-to-phase2.md` (and `revews1and2.md`)
**Date:** 2026-04-28

---

## Why this is a delta review, not a full pass

The first round of reviews (3 models, full Phase 1 + Phase 2 scope) surfaced **5 P1 issues + 3 P2 issues + 3 architectural pivots**. This round of changes claims to address all of them. Your job: verify, not re-read everything.

---

## What you're looking at

```
df1817e (Phase 2 narrowed gate + reviewer round 1 baseline)
436f6cc fix(phase2): close 3 P1 export leaks (Send race / Save+Share / Jobs share)
09b2daf fix(phase2): close 4 P1/P2 boundary leaks (cache, paint-state, T-parser, palette)
f8f1fc0 refactor(phase2): B.1 — CanonicalGcodePath / PhysicalGcodePath value classes
d371e31 refactor(phase2): B.3 partial + B.2/B.3 handoff specs
```

Local clone setup:
```bash
git clone https://github.com/taylormadearmy/u1-slicer-for-android.git
cd u1-slicer-for-android
git fetch
git checkout feature/phase2-canonical-filaments
git log df1817e..HEAD                # the delta
git diff df1817e..HEAD                # the actual changes
```

---

## Per-finding verification checklist

For each finding from the prior round, verify three things:

1. **Fix is actually in the diff** (point at the file:line that addresses it).
2. **Fix is structurally correct** (does it close the bug class, not just the one reported case?).
3. **No regression introduced** (did the fix break anything else?).

### P1 findings to verify

#### P1.1 — Send dialog race (canonical lookup race)

Original finding: `produceState` initialValue `null` was treated as "no canonical list", so a fast Send-tap before async lookup completed sent raw canonical G-code straight to the printer.

Fix should: distinguish **Loading** from **Loaded(null)** explicitly; Loading must NOT fall through to the unchanged-send path.

Where to look:
- `app/src/main/java/com/u1/slicer/MainActivity.kt:656-768` (the rewritten Send dialog block)
- `app/src/main/java/com/u1/slicer/MainActivity.kt:~755` (the `CanonicalLookup` sealed class definition)

Specific question: is the `Loading` branch a no-op that keeps `pendingMappingSend` alive? If a user taps Send on a 73 MB Buzz file, the canonical lookup takes seconds — does the dialog stay alive throughout?

#### P1.2 — Save / Share with plate-narrowed `_colorMapping`

Original finding: `prepareExportableGcode` used `_colorMapping.value` directly. For plate-narrowed mappings (e.g. `[0,1]` for a 2-colour plate of a 10-filament file) the remap was 2-wide and didn't cover canonical T2-T9. Single-colour-with-`_selectedExtruder` was also unhandled.

Fix should: handle all four input cases — full canonical mapping, plate-narrowed mapping, single-colour with selected slot, no canonical context.

Where to look:
- `app/src/main/java/com/u1/slicer/gcode/PrintTimeRemap.kt` — `resolveCanonicalExportMapping(...)` top-level function (the four-case resolver).
- `app/src/test/java/com/u1/slicer/gcode/CanonicalExportMappingTest.kt` — 10 unit tests covering the cases.
- `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` — `prepareExportableGcode`, `prepareExportableGcodeWithMapping`, `resolveExportMapping` (the plumbing).

Specific question: is the case where `colorMapping.size > canonicalSize` (stale mapping outliving a fixture switch) handled? Test asserts truncation; verify the impl matches.

#### P1.3 — Job-history share bypass

Original finding: `shareJobGcode(job)` shared the stored canonical-space G-code directly via FileProvider — the "4th-path leak".

Fix should: route Jobs share through the same export-mapping helper as Send/Save/Share.

Where to look:
- `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` — rewritten `shareJobGcode` (around line 3354).
- `app/src/main/java/com/u1/slicer/data/SliceJob.kt` — schema v5 adds `canonicalListSize: Int?` and `colorMappingCsv: String?`.
- `app/src/main/java/com/u1/slicer/data/AppDatabase.kt` — `MIGRATION_4_5`.
- The slicing path that populates the new metadata: `SlicerViewModel.kt` near line 2915 in the `sliceJobDao.insert(SliceJob(...))` call.

Specific question: pre-Phase-2 jobs (where `canonicalListSize == null`) are intended to share as-is (their stored G-code is already physical-slot from v1.6.13-era). Is that branch correct, or could it inadvertently apply identity-mod-4 to a physical-slot file and corrupt it?

#### P1.4 — Canonical filament cache cross-load leak

Original finding: `getCanonicalFilamentList()` returned `_canonicalFilamentList.value` blindly without checking whether it belonged to the current source file. Loading file B after file A could embed/map B using A's canonical list.

Fix should: track cache identity (e.g. by source path); clear the cache + overrides synchronously at the start of every load entry point.

Where to look:
- `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` — `canonicalCacheSourcePath: String?` (volatile field, around line 220), `beginNewModelLoad()` helper, updated `getCanonicalFilamentList()` source-path check, both `loadModel(uri)` and `loadModelFromFile(file)` entry points calling `beginNewModelLoad()` synchronously.

Specific question: are there any third load entry point that bypasses `beginNewModelLoad`? (Recovery paths, reloadFromCurrentFile, deep-link handlers, etc.)

#### P1.5 — Native plate-state misses paint-only colours

Original finding: `readPlateStateFromNative` only queried `nativeGetPaintStateCounts` when both `parsed.hasPaintData` AND `vol.isMmPainted` were true. The `PlateStateEnrichment.kt` test helper documents SEMM cases (slip-slide-spin plate 3 was the canary) where volumes return `isMmPainted=false` but still produce paint-state counts when queried.

Fix should: probe every volume unconditionally; ignore nulls / empties; also promote `hasPaintData` when paint data is found this way (otherwise downstream `B81` plate-classification + canonical-list construction stays wrong).

Where to look:
- `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` — `readPlateStateFromNative()` around line 3946.

Specific question: the comment in the prior fix says "extra JNI call per volume on non-painted files. Acceptable — typical fixtures have under ~50 volumes." Is that actually acceptable on the heaviest fixture (Skywing 35 component models, Goat 48 objects)? Worth measuring before accepting.

### P2 findings to verify

#### P2.6 — `GcodeParser` ignores multi-digit T-index commands

Where to look:
- `app/src/main/java/com/u1/slicer/gcode/GcodeParser.kt:260-285`.
- `app/src/test/java/com/u1/slicer/gcode/GcodeParserTest.kt` — two new tests `parse multi-digit T-index attributes extrusion to high tool` + `parse T15 single command attributes extrusion to high tool not T0`.

Specific question: the safety cap stays at 31. Is that intentionally above any realistic file? Buzz plate 9 reaches T11; H2C benchy reaches T6. 31 looks fine but worth confirming there's no fixture that would clamp.

#### P2.7 — G-code preview palette capped at 4

Where to look:
- `app/src/main/java/com/u1/slicer/MainActivity.kt:4054-4118` — rewritten `normalizeGcodePreviewColors`.
- `app/src/test/java/com/u1/slicer/PreviewColorNormalizationTest.kt` — Dragon test reframed for canonical semantics + new high-T canonical guard.

Specific question: when both `resolvedFilamentColors` is non-empty AND `colorMapping` is non-empty, the impl prefers slot preset over file colour. Is that the right ordering for the user's mental model? The reviewer's recommendation was canonical-fileIndex ordering; the slot-preset preference came from the original "missing red" fix in commit `7fca77b`. Verify these don't conflict.

#### P2.7b — Sparse non-MMU `meshAlignedFilamentColors` mismatch

Where to look:
- `app/src/main/java/com/u1/slicer/SlicerViewModel.kt:571-635` — rewritten `meshAlignedFilamentColors` flow with `_layerToolOnly` + `_threeMfInfo` dependencies.

Specific question: the sparse non-MMU branch derives sorted-unique source extruders from `objectExtruderMap`. Is `_threeMfInfo.value`'s `objectExtruderMap` always plate-narrowed correctly post-`selectPlate` for multi-plate files? Or could the file-wide objectExtruderMap leak in?

### Architectural pivots to verify

#### B.1 — CanonicalGcodePath / PhysicalGcodePath value classes

Where to look:
- `app/src/main/java/com/u1/slicer/gcode/GcodePathTypes.kt` — both value classes defined.
- `app/src/main/java/com/u1/slicer/printer/PrinterViewModel.kt:246-281` — `sendAndPrint` and `sendUploadOnly` now take `PhysicalGcodePath`.
- `app/src/main/java/com/u1/slicer/gcode/PrintTimeRemap.kt` — typed overload of `applyPrintTimeRemap`.
- `app/src/main/java/com/u1/slicer/MainActivity.kt:714-768` — Send flow updated to use the typed boundary.

Specific question: this is the **most load-bearing** verification of the round. The pivot's value comes from completeness — if even one path bypasses the typed boundary, the bug class re-opens. Is the wrap-from-File pattern (`PhysicalGcodePath.of(file)`) inherently bypassable, and should the `String` constructor be made `internal`?

The commit message explicitly notes:
> Migration is intentionally minimal: ... `saveGcodeTo` / `shareGcode` / `shareJobGcode` continue to use the file-based `prepareExportableGcode` helper which works in raw `File` space; threading the value classes through there would require touching every Save/Share/Jobs call site without changing the runtime behaviour the prior commits already locked in.

Is "the prior commits locked in the runtime behaviour" a strong enough guarantee, or should the value classes thread through Save/Share/Jobs too?

### B.2 + B.3 status (handoff spec, not implementation)

`docs/superpowers/specs/2026-04-28-b2-b3-handoff.md` documents:
- B.2 (config inversion) — multi-day refactor; estimated scope, migration plan, risk assessment, `GcodeBaselineDiffTest`-anchored chunking.
- B.3 finish — half-day scope; Path A (register `snapmaker_authored_profile` as ConfigOptionString in OrcaSlicer's PrintConfig.cpp) recommended.

Verify the handoff spec is sufficient for a fresh-session implementer to pick up without re-deriving the architectural decisions.

---

## Output format

For each finding above, return:

```
P1.1 Send race: ADDRESSED | NOT-ADDRESSED | NEW-CONCERN
  Where: <file:line if addressed; description if new concern>
  Notes: <one sentence>

...

B.1 value classes: COMPLETE | PARTIAL | NEW-CONCERN
  Notes: <one sentence; flag if you think Save/Share/Jobs paths should also be type-gated>

B.2/B.3 handoff: SUFFICIENT | INSUFFICIENT
  Notes: <one sentence>
```

If you find any **new** issues introduced by the delta (regression, new bug class), report them under a separate `NEW FINDINGS` section with the same severity rubric (P1 / P2 / architectural).

Keep the response under 1000 words total. The intent is fast verification, not re-reviewing the whole codebase.
