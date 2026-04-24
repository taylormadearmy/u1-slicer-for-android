# ThreeMfMeshParser Retirement — Phase 1 Cleanup Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete the Kotlin `ThreeMfMeshParser` (~850 lines) plus its 33 tests (29 JVM + 4 instrumented), now that Phase 1 sub-plans #1/#5/#2/#4 have closed the diff-harness baseline to zero. Migrate its one production caller (`ModelViewerScreen.kt:42`) to the native preview mesh path that already backs the main Prepare screen.

**Architecture:** `ModelViewerScreen` receives `modelFilePath` through navigation from the Prepare screen, so the native `g_model` is typically already populated when it opens. The migration loads the model natively under `NativeLibrary.previewMutex` (idempotent; matches `KotlinBambuSnapshot.snapshot`), pulls `getPreparePreviewMesh()`, and converts via the existing `NativePreviewMesh.toMeshData()` (B88 compaction contract — already shipped, already tested). After the caller migrates the parser file and all test references are deletable. `ProfileEmbedderIntegrationTest#flippyFlappyMini_previewMeshUsesLayerChangeColours` migrates to assert the same B82 guarantee via native.

**Tech Stack:** Kotlin 1.9.22, `NativeLibrary.previewMutex` (kotlinx-coroutines Mutex), `NativePreviewMesh` → `MeshData` (10-float vertex format + `extruderIndices` ByteArray), Compose UI (`ModelViewerScreen`).

---

## Context (read before starting)

- Phase 1 diff harness is green at **0 entries**. Sub-plans landed: `docs/superpowers/plans/2026-04-23-phase1-{project-config,per-plate-data,object-extruder-map,painted-facets}.md`.
- Sub-plan #1 LANDED notes (`docs/superpowers/plans/2026-04-23-phase1-kickoff-handoff.md` → "Sub-plan #1 status") explicitly deferred this retirement: "ThreeMfMeshParser NOT deleted — last production caller doesn't use paint data; retirement deferred to a later sub-plan."
- Sub-plan #1's design-notes doc (`docs/superpowers/plans/2026-04-23-phase1-painted-facets-design-notes.md`) has the fuller analysis of the parser's call sites (the prior research agent already did this work).
- Operating rules identical to the earlier sub-plans — worktree path, DEX snake_case, `extern/` restore, NDK 26 native rebuild checklist. See `feedback-bambu-refactor-gotchas.md`.

**No native rebuild needed.** This plan is pure Kotlin + test deletions.

---

## File structure

**Modified files:**

| Path | Change |
|---|---|
| `app/src/main/java/com/u1/slicer/ui/ModelViewerScreen.kt` | Line 42: swap `ThreeMfMeshParser.parse(file)` for a native-preview path that holds `previewMutex`, calls `native.loadModel`, reads `native.getPreparePreviewMesh()`, and converts via `toMeshData()`. |
| `app/src/androidTest/java/com/u1/slicer/slicing/ProfileEmbedderIntegrationTest.kt` | `flippyFlappyMini_previewMeshUsesLayerChangeColours` (~lines 389-407): assert the same B82 multi-colour-indices guarantee via `native.getPreparePreviewMesh()` instead of the Kotlin parser. |
| `app/src/main/java/com/u1/slicer/MainActivity.kt` | Lines 2191, 2288, 2363: drop the dangling `// ThreeMfMeshParser` comments (referring to a class that no longer exists). |
| `app/src/main/java/com/u1/slicer/bambu/ThreeMfParser.kt` | Line 1101: drop the trailing `// coloring in ThreeMfMeshParser` comment. |
| `app/src/main/java/com/u1/slicer/viewer/NativePreviewMesh.kt` | Line 151: rewrite the `Triangle cap for the Kotlin ThreeMfMeshParser path` comment to describe the native-only reality. |
| `CLAUDE.md` | Remove the `ThreeMfMeshParserTest` lines from the unit and instrumented test tables; update class counts. |

**Deleted files:**

| Path | Notes |
|---|---|
| `app/src/main/java/com/u1/slicer/viewer/ThreeMfMeshParser.kt` | The subject — ~850 lines. |
| `app/src/test/java/com/u1/slicer/viewer/ThreeMfMeshParserTest.kt` | 29 JVM unit tests — all exercise the deleted class. |
| `app/src/androidTest/java/com/u1/slicer/viewer/ThreeMfMeshParserTest.kt` | 4 instrumented tests — all exercise the deleted class. |

**Deliberately NOT touched:**

- Any native code (`.cpp`, `.so`). This is pure Kotlin.
- `KotlinBambuSnapshot.kt`, `NativeLibrary.kt`, the new sub-plan C++ TUs — all stable post-Phase 1.
- `BambuSanitizer.kt`, `ThreeMfParser.parseLayerToolSegments` / `parseLayerToolCustomGcodeXml*` — those are sub-plan #3's territory (`LayerToolPauseInjector` migration), NOT this plan's scope.

---

## Migration design

**Current** (`ModelViewerScreen.kt:38-45`):

```kotlin
val file = File(modelFilePath)
mesh = when {
    file.name.endsWith(".stl", ignoreCase = true) -> StlParser.parse(file)
    file.name.endsWith(".3mf", ignoreCase = true) ->
        com.u1.slicer.viewer.ThreeMfMeshParser.parse(file)
    else -> null
}
```

**After**:

```kotlin
val file = File(modelFilePath)
mesh = when {
    file.name.endsWith(".stl", ignoreCase = true) -> StlParser.parse(file)
    file.name.endsWith(".3mf", ignoreCase = true) -> {
        // Sub-plan #1 retirement: the native preview mesh path replaces the
        // Kotlin ThreeMfMeshParser. Load-then-read under previewMutex so we
        // don't race with the Prepare screen's concurrent slicing / rotation
        // actions. loadModel is idempotent for the same file.
        val native = NativeLibrary()
        NativeLibrary.previewMutex.withLock {
            if (native.loadModel(file.absolutePath)) {
                native.getPreparePreviewMesh()?.toMeshData()
            } else null
        }
    }
    else -> null
}
```

The existing `LaunchedEffect` is already a coroutine body, so `withLock` composes cleanly. `NativePreviewMesh.toMeshData()` returns a `MeshData` with `extruderIndices` populated (B88 compaction contract) — satisfies the viewer's per-triangle colouring needs.

---

## Tasks

### Task 1: Migrate `ModelViewerScreen.kt:42`

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/ui/ModelViewerScreen.kt`

- [ ] **Step 1:** Add imports for `NativeLibrary` and `withLock`:

```kotlin
import com.u1.slicer.NativeLibrary
import kotlinx.coroutines.sync.withLock
```

- [ ] **Step 2:** Replace the `when` branch for `.3mf` files with the native path (full block shown in "Migration design" above). Retain the try/catch and `Log.e` as they are; the `when` body still runs inside the existing `withContext(Dispatchers.IO) { try { ... } }` block.

- [ ] **Step 3:** Drop the now-unused `com.u1.slicer.viewer.ThreeMfMeshParser` import if it exists (it doesn't — the current call uses a fully-qualified name).

- [ ] **Step 4:** Compile-check:

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  ./gradlew :app:compileDebugKotlin --no-daemon 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL. If it fails to resolve `NativeLibrary` or `previewMutex`, confirm imports.

- [ ] **Step 5:** Commit:

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  git restore -- app/src/main/cpp/extern/ && \
  git add app/src/main/java/com/u1/slicer/ui/ModelViewerScreen.kt && \
  git commit -m "cleanup(viewer): ModelViewerScreen uses native preview mesh for 3MF

Final production caller of ThreeMfMeshParser migrates to
NativeLibrary.getPreparePreviewMesh(), reached under previewMutex so we
don't race Prepare-screen concurrent actions. Prepare sub-plan #1's
handoff deferred this retirement; now it unblocks the parser deletion
in the next commit."
```

---

### Task 2: Migrate the B82 instrumented test

**Files:**
- Modify: `app/src/androidTest/java/com/u1/slicer/slicing/ProfileEmbedderIntegrationTest.kt`

The existing test at lines 389-407 asserts that `ThreeMfMeshParser.parse` produces a mesh with `hasPerVertexColor = true` and multiple `extruderIndices` for `flippy+flappy+mini.3mf`. The native equivalent is exactly what `NativePreparePreviewTest.kt` already exercises for other fixtures. We keep this test as a B82 guard but re-source the data.

- [ ] **Step 1:** Replace the test body. The new body should:
  1. Load `flippy+flappy+mini.3mf` into native via `lib.loadModel(...)` (same pattern as sibling tests in the file).
  2. Call `lib.getPreparePreviewMesh()` and convert via `toMeshData()`.
  3. Assert `mesh!!.extruderIndices` is non-null.
  4. Assert the distinct set of `extruderIndices` values contains > 1 entry (the B82 guarantee).

New body:

```kotlin
    @Test
    fun flippyFlappyMini_previewMeshUsesLayerChangeColours() {
        val input = asset("flippy+flappy+mini.3mf")
        assertTrue(lib.loadModel(input.absolutePath))
        val mesh = lib.getPreparePreviewMesh()?.toMeshData()

        assertNotNull("native preview mesh should be non-null", mesh)
        mesh!!
        val indices = mesh.extruderIndices
        assertNotNull(
            "preview mesh should carry per-triangle extruder indices",
            indices
        )
        val distinct = indices!!.toSet()
        assertTrue(
            "layer-change preview mesh should contain multiple extruder indices, got $distinct",
            distinct.size > 1
        )
    }
```

- [ ] **Step 2:** Drop the `ThreeMfParser.parse(input)` call and the `info.detectedColors.size` argument threading — neither is needed on the native path.

- [ ] **Step 3:** Drop the now-unused import of `com.u1.slicer.viewer.ThreeMfMeshParser` if present, and `com.u1.slicer.bambu.ThreeMfParser` if it's only used by this test (check first — likely used elsewhere in the file).

- [ ] **Step 4:** Run the test on-device (Pixel 8a `43211JEKB16931`):

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  adb -s 43211JEKB16931 uninstall com.u1.slicer.orca; \
  adb -s 43211JEKB16931 uninstall com.u1.slicer.orca.test; \
  ./gradlew assembleDebug assembleDebugAndroidTest --no-daemon 2>&1 | tail -3 && \
  adb -s 43211JEKB16931 install -r -d app/build/outputs/apk/debug/app-debug.apk && \
  adb -s 43211JEKB16931 install -r -d app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk && \
  adb -s 43211JEKB16931 shell am instrument -w -r \
    -e class com.u1.slicer.slicing.ProfileEmbedderIntegrationTest#flippyFlappyMini_previewMeshUsesLayerChangeColours \
    com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tail -15
```

Expected: `OK (1 test)`. If the native path yields a single-extruder mesh (B82 guarantee broken), stop and escalate — the retirement plan must not erase this regression guard.

- [ ] **Step 5:** Commit:

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  git restore -- app/src/main/cpp/extern/ && \
  git add app/src/androidTest/java/com/u1/slicer/slicing/ProfileEmbedderIntegrationTest.kt && \
  git commit -m "test(viewer): migrate B82 preview-mesh guard to native path

flippyFlappyMini_previewMeshUsesLayerChangeColours no longer constructs
its own ThreeMfMeshParser output; instead loads the fixture natively and
reads NativeLibrary.getPreparePreviewMesh().toMeshData(). The B82
guarantee — layer-change preview produces multiple extruder indices —
is the same."
```

---

### Task 3: Delete `ThreeMfMeshParser.kt` + the two test files

**Files:**
- Delete: `app/src/main/java/com/u1/slicer/viewer/ThreeMfMeshParser.kt`
- Delete: `app/src/test/java/com/u1/slicer/viewer/ThreeMfMeshParserTest.kt`
- Delete: `app/src/androidTest/java/com/u1/slicer/viewer/ThreeMfMeshParserTest.kt`

- [ ] **Step 1:** Re-grep to confirm no remaining callers:

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  git grep -n 'ThreeMfMeshParser\b' -- 'app/src/main/**' ':(exclude)app/src/main/java/com/u1/slicer/viewer/ThreeMfMeshParser.kt'
```

Expected output: only comments in `MainActivity.kt` (lines 2191, 2288, 2363), `ThreeMfParser.kt:1101`, and `NativePreviewMesh.kt:151` — no live code references. If anything else appears, STOP and investigate before deleting.

- [ ] **Step 2:** Delete the three files:

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  git rm app/src/main/java/com/u1/slicer/viewer/ThreeMfMeshParser.kt \
         app/src/test/java/com/u1/slicer/viewer/ThreeMfMeshParserTest.kt \
         app/src/androidTest/java/com/u1/slicer/viewer/ThreeMfMeshParserTest.kt
```

- [ ] **Step 3:** Compile-check and run the JVM unit suite to confirm nothing else depended on these:

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  ./gradlew testDebugUnitTest --no-daemon 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL. If another unit test references `ThreeMfMeshParser` (unlikely — the grep in Step 1 would have caught it), resolve before proceeding.

- [ ] **Step 4:** Commit:

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  git restore -- app/src/main/cpp/extern/ && \
  git commit -m "cleanup(viewer): delete ThreeMfMeshParser and its 33 tests

~850 lines of Kotlin + 29 JVM + 4 instrumented tests go. ModelViewerScreen
(the last production caller) migrated in the prior commit; the B82
per-triangle-colour regression guard in ProfileEmbedderIntegrationTest
moved to the native path.

Phase 1 Kotlin surface shrinks: all 3MF mesh data for production is now
sourced from g_model via NativeLibrary.getPreparePreviewMesh()."
```

---

### Task 4: Clean up dangling comments

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/MainActivity.kt` (lines 2191, 2288, 2363)
- Modify: `app/src/main/java/com/u1/slicer/bambu/ThreeMfParser.kt:1101`
- Modify: `app/src/main/java/com/u1/slicer/viewer/NativePreviewMesh.kt:151`

Each of these is a comment mentioning `ThreeMfMeshParser` that now describes a deleted class. Read each line in context and either delete the comment or rewrite it to describe the surviving code.

- [ ] **Step 1:** For each of the five sites, read 3-5 lines of surrounding context (`Read` with a small window) so you keep any genuinely useful information. Example for `NativePreviewMesh.kt:151`:

  Before:
  ```kotlin
  /**
   * Triangle cap for the Kotlin ThreeMfMeshParser path (painted/SEMM models).
   * ...
   */
  ```
  After:
  ```kotlin
  /**
   * Triangle cap for the native preview mesh returned by
   * NativeLibrary.getPreparePreviewMesh — painted/SEMM multi-colour models
   * benefit most from the decimation budget.
   * ...
   */
  ```

  (Adjust to match whatever the original comment actually says — the one-liner above is illustrative, not literal.)

- [ ] **Step 2:** Compile-check (comments don't affect compile; this just verifies nothing was mangled):

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  ./gradlew :app:compileDebugKotlin --no-daemon 2>&1 | tail -5
```

- [ ] **Step 3:** Commit:

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  git restore -- app/src/main/cpp/extern/ && \
  git add app/src/main/java/com/u1/slicer/MainActivity.kt \
          app/src/main/java/com/u1/slicer/bambu/ThreeMfParser.kt \
          app/src/main/java/com/u1/slicer/viewer/NativePreviewMesh.kt && \
  git commit -m "cleanup(viewer): drop dangling ThreeMfMeshParser comments

Five comment-only sites referencing the deleted class: three explanatory
notes in MainActivity, one 'coloring in ThreeMfMeshParser' tail in
ThreeMfParser, and one triangle-cap rationale in NativePreviewMesh
rewritten to describe the native path."
```

---

### Task 5: Regression — full test sweep

No new code paths, but confirm the deletions didn't break anything reachable through the Compose UI / B82 guards.

- [ ] **Step 1:** JVM unit tests (fast):

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  ./gradlew testDebugUnitTest --no-daemon 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2:** Full Bambu instrumented package (slice-path smoke):

```bash
adb -s 43211JEKB16931 shell am instrument -w -r -e package com.u1.slicer.bambu \
  com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tail -6
```

Expected: `OK (26 tests)`.

- [ ] **Step 3:** Viewer + native package (preview mesh smoke):

```bash
adb -s 43211JEKB16931 shell am instrument -w -r \
  -e class com.u1.slicer.viewer.NativePreparePreviewTest \
  com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tail -6
```

Expected: `OK (16 tests)`.

- [ ] **Step 4:** Diff harness (regression guard — should still be 0 baseline):

```bash
adb -s 43211JEKB16931 shell am instrument -w -r \
  -e class com.u1.slicer.bambu.snapshot.BambuParserDifferentialTest \
  com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tail -6
```

Expected: `OK (21 tests)`.

- [ ] **Step 5:** ProfileEmbedderIntegrationTest (the migrated B82 case + siblings):

```bash
adb -s 43211JEKB16931 shell am instrument -w -r \
  -e class com.u1.slicer.slicing.ProfileEmbedderIntegrationTest \
  com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tail -6
```

Expected: `OK (13 tests)` — down one from 14 if you folded the migrated test, same if you kept it at 14. The B82 assertion must still pass.

---

### Task 6: Update `CLAUDE.md` test counts and class inventory

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1:** Remove the JVM test line:

  ```
  - `viewer/ThreeMfMeshParserTest.kt` (29) - 3MF mesh parsing, ...
  ```

- [ ] **Step 2:** Remove the instrumented test line:

  ```
  - `viewer/ThreeMfMeshParserTest.kt` (4) - 3MF mesh parsing, transform resolution, per-triangle color extraction, calicube extruder indices
  ```

- [ ] **Step 3:** Update the headline counts:
  - JVM unit tests line: `839` → `810` (−29) and total-classes count `58` → `57`.
  - Instrumented tests line: `209 tests across 20 classes` → `205 tests across 19 classes` (−4 tests, −1 class).
  - `./gradlew testDebugUnitTest` comment: `873` → `844` (−29).
  - `./gradlew connectedDebugAndroidTest` comment: `216` → `212` (−4).

  (Double-check by running `./gradlew testDebugUnitTest --no-daemon` and counting — the adjustments above are approximate; use the actual count.)

- [ ] **Step 4:** Remove the `ThreeMfMeshParser.MeshWithContext` bullet in the "Key Conventions" section (around line 167 in the current `CLAUDE.md`) — the whole data class is gone.

- [ ] **Step 5:** Commit:

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  git restore -- app/src/main/cpp/extern/ && \
  git add CLAUDE.md && \
  git commit -m "docs: remove ThreeMfMeshParser references after retirement"
```

---

### Task 7: Append a MORNING_STATUS entry (if the file still exists)

**Files:**
- Modify: `MORNING_STATUS.md` (if present at the worktree root; otherwise skip).

The overnight run left `MORNING_STATUS.md` untracked at the worktree root. If the human has already acted on it and deleted it, skip this task. Otherwise append a single paragraph:

```markdown

## Follow-up landed (2026-04-24)

ThreeMfMeshParser retirement completed in 6 commits following plan
`docs/superpowers/plans/2026-04-24-threemf-mesh-parser-retirement.md`.
~850 lines of Kotlin + 33 tests removed. Production caller
(`ModelViewerScreen.kt:42`) migrated to `NativeLibrary.getPreparePreviewMesh`
under `previewMutex`. B82 layer-change preview regression guard moved to
the native path. Diff harness still green at 0 entries.
```

No commit for this — it's untracked.

---

## Exit criteria

- [ ] `ThreeMfMeshParser.kt` and its two test files are deleted.
- [ ] `ModelViewerScreen.kt` loads 3MF preview through native.
- [ ] `ProfileEmbedderIntegrationTest#flippyFlappyMini_previewMeshUsesLayerChangeColours` passes via the native path.
- [ ] Full JVM unit suite green.
- [ ] Bambu instrumented package green.
- [ ] `NativePreparePreviewTest` green.
- [ ] `BambuParserDifferentialTest` still green at 0 baseline entries.
- [ ] `git grep 'ThreeMfMeshParser' -- 'app/src/**'` returns zero matches.
- [ ] `CLAUDE.md` test counts reflect the deletions.

## Scope firewall

Do NOT in this plan:
- Touch `LayerToolPauseInjector.kt`, `LayerToolCustomGcodeXml.kt`, or their callers — that's the sub-plan #3 production migration, a separate (larger) effort.
- Touch `BambuSanitizer.extractPlate` or `SlicerViewModel.mergeThreeMfInfoForPlate` — sub-plan #2b.
- Rebuild the native `.so`. No C++ changes are required.
- Merge the branch, push to remote, or create releases.

If you catch yourself editing anything outside the file table above, stop and escalate.

---

## After this plan lands

Suggested sequencing for remaining Phase 1 work (not part of this plan):

1. **Rename `ObjectSnapshot.objectId` / `VolumeSnapshot.objectId`** to reflect post-sub-plan-#1 reality (positional / runtime-id rather than identity). Pure refactor; touches the Phase 0 JSON contract so `BambuSnapshotDiff` must match. ~S effort.
2. **Sub-plan #3 production migration**: delete `LayerToolCustomGcodeXml.kt`, migrate `LayerToolPauseInjector.kt:136` to read from `nativeGetPlateData` output. Slice-time code — dispatch a research agent first to map all call sites. ~M effort.
3. **Sub-plan #2b**: migrate `BambuSanitizer.extractPlate` off its re-zip-and-repair Kotlin path to a native "select plate N for slice" entry point in `sapil_print.cpp`. Biggest remaining risk; needs a new native entry point and `Print::apply` analysis. ~L effort.

These are candidates, not commitments — run each past the human before starting, because `#2b` in particular requires design discussion.
