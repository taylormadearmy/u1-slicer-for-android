# Handoff: v2.0.0 bug-class siblings + instrumented re-test

**Date:** 2026-04-29
**Branch:** `feature/phase2-canonical-filaments` (head `d60a61f`)
**Worktree:** `c:/Users/kevin/projects/u1-slicer-orca/.worktrees/phase2-canonical`
**Release APK on G drive:** `G:/My Drive/claude/u1-slicer-v2.0.0.apk` (built from `5442e48`, predates the docs-only `d60a61f`)

## Where we left off

v2.0.0 manual verification surfaced 3 visual bug classes (canonical-space leak / dialog row count / summary chip count). I shipped fixes in `5442e48` + an earlier export-mapping commit, plus a universal post-slice rubric in `E2E_TESTING.md`. Re-running the rubric across the **smoke-7** subset on Pixel 8a found 2 NEW siblings the original fix didn't catch.

## Open issues (priority order)

### 1. `colored_3DBenchy_1.3mf` — single-plate SEMM dialog leak (Bug 2 + Bug 3 sibling)

**Result file:** `c:/tmp/e2e-results/v2.0.0-rubric-colored-benchy.txt`

**Symptom:** Single-plate SEMM Benchy. canonicalListSize=10, plate uses 4 filaments. `Filaments(4)` panel correct. But:
- Filament Mapping dialog shows **10 rows** ("Assign each of the 10 filaments...") with rows 5–10 being grey unused canonical entries. Should be 4.
- Slice Summary shows **9 chips** (Filaments 1–9). Should be 4.

**Bug 1 + T-index check both PASS** — gcode preview colours match Prepare; share gcode T0–T3 only.

**Likely root cause:** `MainActivity.computePlateFileIndices(info, plateId, canonicalSize)` early-exits when `plateId < 0`:

```kotlin
internal fun computePlateFileIndices(...): List<Int>? {
    if (info == null || plateId < 0 || canonicalSize <= 0) return null
    ...
}
```

For single-plate files (no plate selector), `recoveryPlateId` stays -1, so narrowing skips entirely and the dialog falls back to file-wide canonical.

**Probable fix:** Drop the `plateId < 0` early-exit. When `plateId < 0`, skip the `plate.filamentIndices` lookup but fall through to the `info.usedExtruderIndices` fallback (which is plate-narrowed for single-plate files via `mergeThreeMfInfoForPlate`).

```kotlin
internal fun computePlateFileIndices(
    info: ThreeMfInfo?,
    plateId: Int,
    canonicalSize: Int,
): List<Int>? {
    if (info == null || canonicalSize <= 0) return null
    val plate = if (plateId >= 0) info.plates.firstOrNull { it.plateId == plateId } else null
    if (plate != null && plate.filamentIndices.isNotEmpty()) {
        val zeroIndexed = plate.filamentIndices.map { it - 1 }
            .filter { it in 0 until canonicalSize }.distinct().sorted()
        if (zeroIndexed.isNotEmpty()) return zeroIndexed
    }
    val fromUsed = info.usedExtruderIndices.filter { it > 0 }
        .map { it - 1 }.filter { it in 0 until canonicalSize }.distinct().sorted()
    return if (fromUsed.isNotEmpty()) fromUsed else null
}
```

Verify after fix:
- colored_3DBenchy: dialog shows 4 rows, summary shows 4 chips
- old.3mf: still shows 6 rows / chips (no change since canonical=plate=6)
- Dragon plate 1 / Button-S: still 2 / 4 rows (multi-plate path unchanged)

### 2. `old.3mf` — Slice Summary missing one chip (could be expected)

**Result file:** `c:/tmp/e2e-results/v2.0.0-rubric-old-3mf.txt`

**Symptom:** 6-filament file. `Filaments(6)` correct. Filament Mapping dialog shows 6 rows correct. Mapping `[2,3,0,1,0,3]` (collisions). But Slice Summary shows **5 chips** (white #FFFFFF missing).

**Investigation step:** Check `; filament used [mm]` in the on-device gcode for old.3mf:

```bash
adb -s 43211JEKB16931 shell "run-as com.u1.slicer.orca grep '^; filament used \[mm\]' files/jobs/<id>/output.gcode"
```

If the white slot has `0.00`, the chip strip filtering it out is **expected behaviour** — declared filament unused in the slice geometry, no chip needed. Update the rubric in `E2E_TESTING.md` to clarify "chip count = plate filament count *that the slicer actually used*".

If white shows non-zero usage, then it's a real Bug 3 sibling and needs the chip strip to render zero-mm filaments too.

### 3. Instrumented test run on Pixel 6 #1 — environmental noise

**Log:** `c:/tmp/instrumented-pixel6.log`

**Result:** 31 FAILED + process crash. Most failures look environmental:
- `ENOSPC (No space left on device)` — Pixel 6 ran out of storage mid-run
- `EACCES (Permission denied)` on `/sdcard/Download/u1-slicer-baselines-uid10000` — cross-UID permission issue (see `memory/feedback-e2e-file-push.md`)
- Pixel 6 perf gates (Buzz cold load 97s vs 90s gate) — known per `memory/feedback-flarewing-test-flake.md`

Real-looking failures worth investigating:
- `BambuParserDifferentialTest` (sensoryTwistBall, dragonScale2c, foldyCoaster, buzzMultipart, flarewingDragon) — `Unexpected diffs ... fileVersion` + `plates[0].filamentColours.size`. Snapshot drift or genuine parser regression.
- `NativeDumpSmokeTest > nativeDumpBambuModel returned null` — JNI symbol issue? Could be Pixel 6 install incomplete or genuine regression.

**Recommended next step:** Run instrumented suite on Pixel 8a (set up & known good):

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest --no-daemon
```

Pixel 8a doesn't have the storage / cross-UID issues that polluted the Pixel 6 run. If failures persist there, they're real.

## What's already verified (don't redo)

| Fixture | What was checked | Result |
|---------|------------------|--------|
| calicube | Universal rubric (post-canonical-palette fix) | PASS — file at `c:/tmp/e2e-results/v2.0.0-rubric-calicube.txt` + earlier `v2.0.0-fix-calicube.txt` |
| 3DBenchy.stl | Check 4 only (single-colour STL) | PASS |
| Dragon Scale plate 1 | Full rubric + share gcode T-index check + DB CSV check | PASS — `v2.0.0-fix-dragon-plate1.txt` |
| Dragon Scale plate 3 | Full rubric | PASS — `v2.0.0-rubric-dragon-plate3.txt` |
| flippy plate 4 | Full rubric (layer-tool) | PASS — `v2.0.0-rubric-flippy-plate4.txt` |
| Button-for-S-trousers | Full rubric (single-plate, canonical=15, plate=4) | PASS — `v2.0.0-fix-button-s.txt` |

JVM unit tests: 980 green at `5442e48`. Includes new tests `SliceJobMappingResolutionTest` (8), `CanonicalExportLeakGuardTest` (6), `PreviewColorNormalizationTest` extensions (12 → 14).

## Useful entry points for the new session

- **Screenshot helper:** `c:/tmp/ss.sh <output.png>` — driver auto-downscales to ≤1500px to stay under the per-image dimension limit.
- **uiautomator dump:** Use this for button coords, not pixel-counting:
  ```bash
  MSYS_NO_PATHCONV=1 adb -s 43211JEKB16931 shell uiautomator dump /sdcard/window.xml
  MSYS_NO_PATHCONV=1 adb -s 43211JEKB16931 pull /sdcard/window.xml c:/tmp/window.xml
  grep -oE '(text="[^"]*"|content-desc="[^"]*")[^>]*bounds="[^"]*"' c:/tmp/window.xml | grep -iE "share|save|map|print|cancel|preview"
  ```
- **TestCommandReceiver broadcasts:** `LOAD_FILE`, `SELECT_PLATE --ei plate N`, `SLICE`, `DUMP_STATE`, `CHECK_GCODE` — see `memory/e2e-testing.md`.
- **Universal rubric procedure:** top of `E2E_TESTING.md`. Apply on every multi-colour file.
- **Pixel 8a serial:** `43211JEKB16931`. APK already installed (debug build of `d60a61f`), fixtures already pushed: Dragon, Button-S, calib-cube, 3DBenchy.stl, colored_3DBenchy_1.3mf, flippy_flappy_mini.3mf, old.3mf.

## Suggested first 5 minutes of new session

1. Read this file
2. Read `c:/tmp/e2e-results/v2.0.0-rubric-colored-benchy.txt` and `v2.0.0-rubric-old-3mf.txt`
3. Read `MainActivity.kt:878` (`computePlateFileIndices`)
4. Pull old.3mf gcode `; filament used [mm]` line to decide whether issue 2 needs a fix or just a rubric clarification
5. Decide: fix issue 1 → rebuild → re-test colored_3DBenchy + Dragon plate 1 (regression check) → commit. Total ≈ 30 min.

## What's on hold

- GitHub release for v2.0.0 (per Kevin's earlier instruction — wait for explicit sign-off)
- Release APK on G drive is from `5442e48`; rebuild after issue 1 fix lands
- Instrumented test re-run on Pixel 8a (recommended before release)
