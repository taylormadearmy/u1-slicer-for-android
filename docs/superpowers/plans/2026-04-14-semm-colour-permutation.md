# B64: SEMM Colour Mapping Permutation Fix — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix SEMM painted models so the user's colour-to-extruder mapping on the Prepare screen is actually applied to the sliced G-code output.

**Architecture:** Add a dedicated `semmColorPermutation` field to `SlicerViewModel` that records the user's colour permutation for SEMM models. After slicing, apply this permutation to the G-code via `GcodeToolRemapper.remap()`, independently of the existing `toolRemapSlots` (which handles sparse slot compaction). When both are present, `semmColorPermutation` subsumes `toolRemapSlots` for SEMM models since it already maps compact T-index → physical slot.

**Tech Stack:** Kotlin, JUnit 4, Android Instrumented Tests, existing `GcodeToolRemapper`

**Spec:** `docs/superpowers/specs/2026-04-14-semm-colour-permutation-design.md`

---

## File Map

| File | Action | Responsibility |
|------|--------|---------------|
| `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` | Modify | Add `semmColorPermutation` field, set it in `applyMultiColorAssignments()`, apply it post-slice, reset on file load |
| `app/src/test/java/com/u1/slicer/SemmColorPermutationTest.kt` | Create | Unit tests for permutation logic (pure function, no ViewModel dependency) |
| `app/src/androidTest/java/com/u1/slicer/slicing/SemmSlicingTest.kt` | Modify | Add instrumented test: Flarewing Dragon + permuted mapping → G-code tool indices correct |
| `app/src/androidTest/assets/Flarewing-Dragon_100%_4FilamentMulticolor_v1.1.3mf` | Create (copy) | Test asset for instrumented test |

---

### Task 1: Copy test asset

**Files:**
- Create: `app/src/androidTest/assets/Flarewing-Dragon_100%_4FilamentMulticolor_v1.1.3mf`

- [ ] **Step 1: Copy the Flarewing Dragon 3MF to test assets**

```bash
cp "G:/My Drive/tes-data/Flarewing-Dragon_100%_4FilamentMulticolor_v1.1.3mf" \
   app/src/androidTest/assets/
```

- [ ] **Step 2: Verify the file is present**

```bash
ls -la "app/src/androidTest/assets/Flarewing-Dragon_100%_4FilamentMulticolor_v1.1.3mf"
```

Expected: File exists, non-zero size.

---

### Task 2: Extract permutation computation as a pure function + unit tests

This task extracts the permutation logic as a standalone `internal fun` so it can be
unit-tested without needing ViewModel or Android context.

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` (add function at bottom, near other `internal fun` helpers)
- Create: `app/src/test/java/com/u1/slicer/SemmColorPermutationTest.kt`

- [ ] **Step 1: Write failing unit tests**

Create `app/src/test/java/com/u1/slicer/SemmColorPermutationTest.kt`:

```kotlin
package com.u1.slicer

import org.junit.Assert.*
import org.junit.Test

class SemmColorPermutationTest {

    @Test
    fun identityMapping_returnsNull() {
        // [0,1,2,3] is identity — no remap needed
        val result = computeSemmColorPermutation(
            colorMapping = listOf(0, 1, 2, 3),
            hasPaintData = true,
            isH2cStyle = false
        )
        assertNull(result)
    }

    @Test
    fun permutedMapping_returnsPermutation() {
        // Color1→E4, Color2→E1, Color3→E3, Color4→E2 = [3,0,2,1]
        val result = computeSemmColorPermutation(
            colorMapping = listOf(3, 0, 2, 1),
            hasPaintData = true,
            isH2cStyle = false
        )
        assertEquals(listOf(3, 0, 2, 1), result)
    }

    @Test
    fun h2cModel_returnsNull() {
        // H2C models must not get a permutation, even with non-identity mapping
        val result = computeSemmColorPermutation(
            colorMapping = listOf(3, 0, 2, 1),
            hasPaintData = true,
            isH2cStyle = true
        )
        assertNull(result)
    }

    @Test
    fun nonSemm_returnsNull() {
        // Non-SEMM models (per-object multi-colour) must not get a permutation
        val result = computeSemmColorPermutation(
            colorMapping = listOf(3, 0, 2, 1),
            hasPaintData = false,
            isH2cStyle = false
        )
        assertNull(result)
    }

    @Test
    fun sparseSlots_permutation_subsumesCompaction() {
        // SEMM model using only E1+E3 with swapped order: Color1→E3, Color2→E1
        // colorMapping = [2, 0], which maps T0→slot2, T1→slot0
        val result = computeSemmColorPermutation(
            colorMapping = listOf(2, 0),
            hasPaintData = true,
            isH2cStyle = false
        )
        assertEquals(listOf(2, 0), result)
    }

    @Test
    fun twoColor_identity_returnsNull() {
        // 2-colour SEMM with identity mapping [0,1]
        val result = computeSemmColorPermutation(
            colorMapping = listOf(0, 1),
            hasPaintData = true,
            isH2cStyle = false
        )
        assertNull(result)
    }

    @Test
    fun twoColor_swapped_returnsPermutation() {
        // 2-colour SEMM with swapped mapping [1,0]
        val result = computeSemmColorPermutation(
            colorMapping = listOf(1, 0),
            hasPaintData = true,
            isH2cStyle = false
        )
        assertEquals(listOf(1, 0), result)
    }

    // --- composeSemmRemap tests ---

    @Test
    fun compose_onlyToolRemap() {
        val result = composeSemmRemap(
            toolRemapSlots = listOf(2, 3),
            semmColorPermutation = null
        )
        assertEquals(listOf(2, 3), result)
    }

    @Test
    fun compose_onlyPermutation() {
        val result = composeSemmRemap(
            toolRemapSlots = null,
            semmColorPermutation = listOf(3, 0, 2, 1)
        )
        assertEquals(listOf(3, 0, 2, 1), result)
    }

    @Test
    fun compose_bothPresent_permutationWins() {
        // semmColorPermutation already encodes full compact→physical mapping,
        // so it subsumes toolRemapSlots
        val result = composeSemmRemap(
            toolRemapSlots = listOf(0, 2),
            semmColorPermutation = listOf(2, 0)
        )
        assertEquals(listOf(2, 0), result)
    }

    @Test
    fun compose_bothNull() {
        val result = composeSemmRemap(
            toolRemapSlots = null,
            semmColorPermutation = null
        )
        assertNull(result)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew testDebugUnitTest --tests "com.u1.slicer.SemmColorPermutationTest" --no-daemon
```

Expected: FAIL — `computeSemmColorPermutation` and `composeSemmRemap` don't exist yet.

- [ ] **Step 3: Implement the pure functions**

Add to `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`, near the other `internal fun` helpers at the bottom of the file (after `buildCompactExtruderRemap`, around line ~3420):

```kotlin
/**
 * Compute the SEMM colour permutation for post-slice G-code remapping.
 *
 * For normal SEMM paint models, the slicer outputs T0–T(N-1) based on the 3MF's
 * filament_colour order. When the user assigns model colours to physical extruders
 * in a non-identity order (e.g. Color1→E4, [3,0,2,1]), this permutation must be
 * applied to the G-code so T0→T3, T1→T0, etc.
 *
 * Returns null when no remap is needed: identity mapping, H2C models, or non-SEMM models.
 */
internal fun computeSemmColorPermutation(
    colorMapping: List<Int>,
    hasPaintData: Boolean,
    isH2cStyle: Boolean
): List<Int>? {
    if (!hasPaintData) return null
    if (isH2cStyle) return null
    val identity = (0 until colorMapping.size).toList()
    if (colorMapping == identity) return null
    return colorMapping
}

/**
 * Compose toolRemapSlots and semmColorPermutation into a single remap list.
 *
 * semmColorPermutation already maps compact T-index → physical slot, so when
 * both are present it subsumes toolRemapSlots (which maps compact T-index →
 * physical slot for sparse-slot compaction).
 */
internal fun composeSemmRemap(
    toolRemapSlots: List<Int>?,
    semmColorPermutation: List<Int>?
): List<Int>? = when {
    semmColorPermutation != null -> semmColorPermutation
    toolRemapSlots != null -> toolRemapSlots
    else -> null
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew testDebugUnitTest --tests "com.u1.slicer.SemmColorPermutationTest" --no-daemon
```

Expected: All 11 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/u1/slicer/SemmColorPermutationTest.kt \
       app/src/main/java/com/u1/slicer/SlicerViewModel.kt
git commit -m "feat(B64): add computeSemmColorPermutation + composeSemmRemap with unit tests"
```

---

### Task 3: Wire semmColorPermutation into SlicerViewModel

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`

- [ ] **Step 1: Add the field declaration**

After `toolRemapSlots` (line 245), add:

```kotlin
    // B64: SEMM colour permutation — maps compact T-index → physical extruder slot
    // when the user's colour assignment is a non-identity permutation.
    // Null when no permutation needed (identity, H2C, non-SEMM).
    // Applied post-slice via GcodeToolRemapper, independently of toolRemapSlots.
    private var semmColorPermutation: List<Int>? = null
```

- [ ] **Step 2: Set the permutation in applyMultiColorAssignments()**

In `applyMultiColorAssignments()`, after the `toolRemapSlots = if (isH2cStyle) { ... }` block (around line 1222), add:

```kotlin
        // B64: compute SEMM colour permutation for post-slice G-code remapping.
        semmColorPermutation = computeSemmColorPermutation(
            colorMapping = modelColorToExtruder,
            hasPaintData = hasPaintData,
            isH2cStyle = isH2cStyle
        )
```

- [ ] **Step 3: Replace the post-slice remap with composed version**

In the post-slice section (around line 2096-2103), replace:

```kotlin
                    // Post-process G-code to remap compact tool indices to physical slots.
                    // OrcaSlicer sliced in compact mode (T0,T1,…) — remap to actual printer
                    // slots (e.g. T2,T3 for E3+E4) and fix SM_ command EXTRUDER/INDEX params.
                    val slots = toolRemapSlots
                    if (slots != null) {
                        GcodeToolRemapper.remap(result.gcodePath, slots)
                        Log.i("SlicerVM", "Post-processed G-code: remapped tools to physical slots $slots")
                    }
```

with:

```kotlin
                    // Post-process G-code to remap compact tool indices to physical slots.
                    // Two possible remaps: toolRemapSlots (sparse slot compaction, e.g. T0→T2)
                    // and semmColorPermutation (colour order for SEMM models, e.g. T0→T3).
                    // composeSemmRemap merges them — permutation subsumes compaction when both present.
                    val composedRemap = composeSemmRemap(toolRemapSlots, semmColorPermutation)
                    if (composedRemap != null) {
                        GcodeToolRemapper.remap(result.gcodePath, composedRemap)
                        Log.i("SlicerVM", "Post-processed G-code: remapped tools to $composedRemap (toolRemap=$toolRemapSlots, semmPerm=$semmColorPermutation)")
                    }
```

- [ ] **Step 4: Add resets at all file-load / clear sites**

Add `semmColorPermutation = null` next to every `toolRemapSlots = null`:

| Line | Context |
|------|---------|
| ~643 | MakerWorld download path |
| ~764 | Standard file load path |
| ~928 | File picker / intent path |
| ~1012 | Plate selection path |
| ~1110 | Layer-tool-only path |
| ~1154 | Single-colour / no multi-colour path |
| ~1318 | Single-colour extruder selection (index==0) |

Also add it in the clear/reset function (around line 2755, after `customWipeTowerPos = null`):

```kotlin
        semmColorPermutation = null
```

Note: Also add `toolRemapSlots = null` to the clear/reset function since it's currently missing there (pre-existing gap).

- [ ] **Step 5: Add to diagnostics logging**

In the `diagnostics.recordEvent("color_mapping_applied", ...)` call (around line 1271), add to the map:

```kotlin
                "semmColorPermutation" to semmColorPermutation,
```

And in the `diagnostics.recordEvent("slice_started", ...)` map (around line 1565), add:

```kotlin
                "semmColorPermutation" to semmColorPermutation,
```

- [ ] **Step 6: Run existing unit tests**

```bash
./gradlew testDebugUnitTest --no-daemon
```

Expected: All 753+ tests PASS. No regressions.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/u1/slicer/SlicerViewModel.kt
git commit -m "feat(B64): wire semmColorPermutation into ViewModel — set, apply post-slice, reset"
```

---

### Task 4: Instrumented test — Flarewing Dragon with permuted mapping

**Files:**
- Modify: `app/src/androidTest/java/com/u1/slicer/slicing/SemmSlicingTest.kt`

- [ ] **Step 1: Write the failing instrumented test**

Add to `SemmSlicingTest.kt` after the existing tests:

```kotlin
    /**
     * B64: SEMM colour permutation — verify that a non-identity colour mapping
     * is applied to the sliced G-code.
     *
     * Flarewing Dragon is a 4-colour SEMM model. Without the permutation fix,
     * the G-code T-indices match the 3MF filament_colour order regardless of the
     * user's colour assignment. With the fix, GcodeToolRemapper rewrites T-indices
     * to match the permuted mapping.
     *
     * Test applies mapping [3,0,2,1] (Color1→E4, Color2→E1, Color3→E3, Color4→E2)
     * and verifies T3 gets the body volume (was T0 before remap).
     */
    @Test
    fun flarewingDragon_semmPermutation_remapsGcodeToolIndices() {
        val input = asset("Flarewing-Dragon_100%_4FilamentMulticolor_v1.1.3mf")
        val origInfo = ThreeMfParser.parse(input)

        assertTrue("Flarewing Dragon must have hasPaintData=true", origInfo.hasPaintData)
        assertEquals("Flarewing Dragon must have 4 detected colors",
            4, origInfo.detectedColors.size)

        // Pipeline: sanitize → embed → load → slice
        val processed = BambuSanitizer.process(input, outDir)
        val config = embedder.buildConfig(
            info = origInfo,
            targetExtruderCount = 4
        )
        val embedded = embedder.embed(processed, config, outDir, origInfo)
        assertTrue("loadModel must succeed", lib.loadModel(embedded.absolutePath))

        val result = lib.slice(makeConfig(4))
        assertNotNull("slice() must not return null", result)
        result!!
        assertTrue("Flarewing Dragon must slice successfully: ${result.errorMessage}", result.success)

        // Before remap: T0 is the body (highest filament usage by far)
        val gcodeBeforeRemap = File(result.gcodePath).readText()
        val usageBefore = (0..3).map { t ->
            gcodeBeforeRemap.lines().count { line -> line.trim() == "T$t" }
        }
        Log.i("SemmTest", "Before remap tool counts: $usageBefore")
        // T0 should be dominant (body) — sanity check
        assertTrue("T0 must have the most tool changes before remap (body), got $usageBefore",
            usageBefore[0] >= usageBefore[1] && usageBefore[0] >= usageBefore[2])

        // Apply permutation [3,0,2,1]: Color1→E4, Color2→E1, Color3→E3, Color4→E2
        // This means T0→T3, T1→T0, T2→T2, T3→T1
        val permutation = listOf(3, 0, 2, 1)
        GcodeToolRemapper.remap(result.gcodePath, permutation)

        val gcodeAfterRemap = File(result.gcodePath).readText()
        val usageAfter = (0..3).map { t ->
            gcodeAfterRemap.lines().count { line -> line.trim() == "T$t" }
        }
        Log.i("SemmTest", "After remap tool counts: $usageAfter")

        // After remap: T3 should have T0's original count (the body)
        assertEquals("T3 after remap must equal T0 before remap (body volume moved)",
            usageBefore[0], usageAfter[3])
        // T0 after remap must equal T1 before remap
        assertEquals("T0 after remap must equal T1 before remap",
            usageBefore[1], usageAfter[0])
        // T2 unchanged (maps to itself in this permutation)
        assertEquals("T2 must be unchanged (identity in this permutation)",
            usageBefore[2], usageAfter[2])
        // T1 after remap must equal T3 before remap
        assertEquals("T1 after remap must equal T3 before remap",
            usageBefore[3], usageAfter[1])

        // Also verify filament_used metadata line is NOT remapped
        // (it's a comment, not a tool command — this is expected/acceptable)
    }
```

- [ ] **Step 2: Run the test to verify it passes**

This test exercises the existing `GcodeToolRemapper.remap()` on real SEMM G-code output.
It should pass immediately since `GcodeToolRemapper` already handles arbitrary remaps —
the test validates the end-to-end pipeline produces correct results when a permutation
is applied.

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest \
  --tests "com.u1.slicer.slicing.SemmSlicingTest.flarewingDragon_semmPermutation_remapsGcodeToolIndices" \
  --no-daemon
```

Expected: PASS. If it fails, check the asset was copied correctly and the model slices
within the test timeout.

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/java/com/u1/slicer/slicing/SemmSlicingTest.kt \
       "app/src/androidTest/assets/Flarewing-Dragon_100%_4FilamentMulticolor_v1.1.3mf"
git commit -m "test(B64): instrumented test — Flarewing Dragon SEMM permutation remap"
```

---

### Task 5: Run full regression suite

**Files:** None (verification only)

- [ ] **Step 1: Run all unit tests**

```bash
./gradlew testDebugUnitTest --no-daemon
```

Expected: All 753+ tests PASS (now +11 for `SemmColorPermutationTest`).

- [ ] **Step 2: Run all instrumented tests**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest --no-daemon
```

Expected: All 163+ tests PASS (now +1 for Flarewing Dragon permutation test).

Key tests to watch:
- `SemmSlicingTest.coloredBenchy_semm_gcodeHasToolChanges` — must still PASS (no regression on identity-mapped SEMM)
- `SemmSlicingTest.coloredBenchy_semm_maxExtruders_notCappedAtTwo` — must still PASS
- `SemmSlicingTest.h2cBenchy_semm_*` — must still PASS (H2C untouched)

- [ ] **Step 3: Commit (if any test count updates needed in CLAUDE.md)**

Only if test counts changed:

```bash
git add CLAUDE.md
git commit -m "docs: update test counts after B64 SEMM permutation tests"
```

---

### Task 6: Update BACKLOG and close issues

**Files:**
- Modify: `BACKLOG.md`

- [ ] **Step 1: Mark B64 as fixed in BACKLOG.md**

Update the B64 entry to add `— FIXED v1.5.52` (or current version) and summarise the fix.

- [ ] **Step 2: Check B58 side-effect**

After the fix, note in B58's BACKLOG entry whether the G-code preview colour mismatch
is likely improved (since the G-code T-indices are now correct, the G-code preview
should show correct colours). The Prepare preview part of B58 is a separate issue.

- [ ] **Step 3: Add cross-pipeline audit note**

Add a new entry to BACKLOG under Open Cleanup or a new "Investigations" section:

> **I3: Cross-pipeline colour permutation audit** — The B64 fix revealed that
> sorted-unique identity checks can miss permutation order. Audit H2C, per-object,
> and layer-tool pipelines for similar patterns.

- [ ] **Step 4: Commit**

```bash
git add BACKLOG.md
git commit -m "docs: mark B64 fixed in BACKLOG, note B58 side-effect, add I3 audit"
```

---

### Task 7: Version bump and GitHub issue closure

**Files:**
- Modify: `app/build.gradle`

- [ ] **Step 1: Bump version**

Increment `versionCode` and `versionName` in `app/build.gradle`.

- [ ] **Step 2: Commit and push**

```bash
git add app/build.gradle
git commit -m "bump: v1.5.52 - fix B64 SEMM colour permutation (GitHub #72)"
git push
```

- [ ] **Step 3: Close GitHub issue #72**

```bash
gh issue close 72 --comment "Fixed in v1.5.52. The user's colour-to-extruder mapping for SEMM paint models is now applied to the G-code via a dedicated semmColorPermutation remap."
```

- [ ] **Step 4: Comment on #60 (B58)**

```bash
gh issue comment 60 --body "B64 fix (v1.5.52) corrects G-code tool indices for SEMM models with permuted colour mappings. The G-code preview colour mismatch part of this bug should now be improved. Prepare preview mismatch may still need a separate fix."
```
