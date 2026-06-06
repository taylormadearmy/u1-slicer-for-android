# Full-Spectrum M3 Phase B — Smart Paint × Mix Slots — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a U1 owner assign any Smart Paint region (painted, segmented, or imported-with-embedded-colours) to a physical filament **or a mix slot**, see the blend in preview, and slice — with regular printing untouched.

**Architecture:** Full-spectrum is a pre-slice workspace built by evolving the existing Smart Paint pipeline. Phase A already delivered the mix data model, dialog, persistence, and the `serialize() → SliceConfig.mixedFilamentDefinitions` wiring. Phase B breaks the paint pipeline's hard 4-slot ceiling (slot cap, 3MF paint encoding, preview palette), then layers the features on top: sectioned picker (per-region + brush), mix swatches, a CIELAB closest-colour matcher, import region-seeding with auto-assign, and a print-cost banner.

**Tech Stack:** Kotlin 1.9.22, Jetpack Compose + Material3, JUnit4 (JVM), AndroidX Test + Orchestrator (instrumented). No native rebuild.

**Spec:** [`docs/superpowers/specs/2026-06-06-full-spectrum-m3-phase-b-smart-paint-mix-design.md`](../specs/2026-06-06-full-spectrum-m3-phase-b-smart-paint-mix-design.md) — its "Scope decisions" section is authoritative.

**The #1 invariant (read before any task):** A region painted with the *k*-th mix must reference the same virtual filament the engine reconstructs from `mixed_filament_definitions`. The chain:
`picker slot id = numPhysical + k` (0-based) → stored as `AiRegion.slot` / per-triangle byte → `PaintedMeshWriter` encodes paint state `= slot + 1` → engine filament id `= slot + 1` → `MixedFilamentManager.serialize(numPhysical)` assigns virtual id `numPhysical + 1 + k`. Since `slot = numPhysical + k`, `slot + 1 = numPhysical + k + 1` = the engine virtual id. **They agree only if the picker's combined-mix order and serialize()'s order are the SAME ordering.** Task 1 makes that one shared function; every later task depends on it.

`numPhysical` everywhere means the value Phase A already passes to `serialize(numPhysical)` at `SlicerViewModel.kt ~:5105` (the engine physical-filament count), not a hardcoded 4.

---

## File structure

**New files:**
- `app/src/main/java/com/u1/slicer/data/MixSlotOrdering.kt` — single source of truth: combined ordered mix list + slot-id ↔ row mapping. Used by the manager, the picker, and the import auto-assign.
- `app/src/main/java/com/u1/slicer/aipaint/ColourMatch.kt` — pure colour helpers: `naiveBlendHex`, sRGB→Lab, `deltaE76`, `closestSlot`.
- `app/src/test/java/com/u1/slicer/data/MixSlotOrderingTest.kt`
- `app/src/test/java/com/u1/slicer/aipaint/ColourMatchTest.kt`
- `app/src/test/java/com/u1/slicer/aipaint/PaintedMeshWriterSlotEncodingTest.kt`
- `app/src/androidTest/java/com/u1/slicer/slicing/MixSlotPaintRoundTripTest.kt` (F2 native round-trip + slice)
- `app/src/androidTest/java/com/u1/slicer/aipaint/SmartPaintMixIntegrationTest.kt` (C1/C4 end-to-end)

**Modified files:**
- `app/src/main/java/com/u1/slicer/data/MixedFilamentManager.kt` — `serialize()` delegates to `MixSlotOrdering`; expose `activeMixCount(numPhysical)`.
- `app/src/main/java/com/u1/slicer/aipaint/PaintedMeshWriter.kt` — `encodePaintColor(slot)` replaces the 4-element `PAINT_COLOR` + `coerceIn(0,3)`; `buildProjectSettings` emits physical+mix `filament_colour`.
- `app/src/main/java/com/u1/slicer/aipaint/AiPaintViewModel.kt` — widen the three slot guards (`:486`, `:509`, `:527`) to a dynamic ceiling; hold the active mix snapshot.
- `app/src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt` — extend `slotPalette`/`slotPaletteFloats`; swap `HighlightSlotPicker` assignment + `SlotPaletteRow` for the sectioned/mix-aware surfaces; add the cost banner + unmatched note.
- `app/src/main/java/com/u1/slicer/ui/AiPaintTreeRow.kt` — render `MixedSlotSwatch` for slots ≥ numPhysical.
- `app/src/main/java/com/u1/slicer/MainActivity.kt` (~`:1524`) — pass `projectMixes`/`libraryMixes`/`numPhysical` into `AiPaintResultScreen`; route imported-coloured-model entry.

---

## Task 1: MixSlotOrdering — one shared ordering (the invariant)

**Files:**
- Create: `app/src/main/java/com/u1/slicer/data/MixSlotOrdering.kt`
- Create: `app/src/test/java/com/u1/slicer/data/MixSlotOrderingTest.kt`
- Modify: `app/src/main/java/com/u1/slicer/data/MixedFilamentManager.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.u1.slicer.data

import com.u1.slicer.data.MixedFilamentRow.MixDistributionMode.LAYER_CYCLE
import org.junit.Assert.assertEquals
import org.junit.Test

class MixSlotOrderingTest {
    private fun row(id: Long, a: Int, b: Int, lib: Boolean) =
        MixedFilamentRow(id, a, b, 50, LAYER_CYCLE, "E$a+E$b @ 50%", lib)

    @Test fun projectFirst_thenLibrary_skippingDupesAndMissingExtruders() {
        val project = listOf(row(1, 1, 2, false), row(2, 1, 3, false))
        val library = listOf(
            row(2, 1, 3, true),   // dupe of a project id → skipped
            row(3, 1, 4, true),   // valid for 4 physical
            row(4, 1, 5, true),   // references E5 > numPhysical → skipped
        )
        val ordered = MixSlotOrdering.activeOrder(project, library, numPhysical = 4)
        assertEquals(listOf(1L, 2L, 3L), ordered.map { it.id })
        // slot id for the k-th mix is numPhysical + k (0-based)
        assertEquals(4, MixSlotOrdering.slotIdFor(ordered, 0, numPhysical = 4)) // id 1 → slot 4
        assertEquals(6, MixSlotOrdering.slotIdFor(ordered, 2, numPhysical = 4)) // id 3 → slot 6
    }

    @Test fun emptyWhenNoMixes() {
        assertEquals(emptyList<MixedFilamentRow>(),
            MixSlotOrdering.activeOrder(emptyList(), emptyList(), 4))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.u1.slicer.data.MixSlotOrderingTest"`
Expected: FAIL — `MixSlotOrdering` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.u1.slicer.data

/**
 * Single source of truth for the ordered list of *active* mix slots and their slot ids.
 * The painted slot byte, the SectionedSlotPicker chip ids, and
 * MixedFilamentManager.serialize()'s virtual-filament order MUST all derive from here,
 * or a region painted with mix k references a different engine filament than the recipe
 * defines (see the plan's #1 invariant).
 */
object MixSlotOrdering {
    /**
     * Project rows first (in order), then library rows that (a) are not already present by
     * id in the project list and (b) reference only physical filaments ≤ numPhysical.
     * Mirrors MixedFilamentManager.serialize()'s iteration exactly.
     */
    fun activeOrder(
        projectMixes: List<MixedFilamentRow>,
        libraryMixes: List<MixedFilamentRow>,
        numPhysical: Int,
    ): List<MixedFilamentRow> {
        val out = ArrayList<MixedFilamentRow>(projectMixes.size + libraryMixes.size)
        out.addAll(projectMixes)
        val projectIds = projectMixes.mapTo(HashSet()) { it.id }
        for (r in libraryMixes) {
            if (r.id in projectIds) continue
            if (r.componentA > numPhysical || r.componentB > numPhysical) continue
            out.add(r)
        }
        return out
    }

    /** 0-based slot id (= per-triangle paint byte) for the index-th entry of [ordered]. */
    fun slotIdFor(ordered: List<MixedFilamentRow>, index: Int, numPhysical: Int): Int =
        numPhysical + index

    /** Inverse: the [ordered] index for a slot id, or -1 if it's a physical slot / out of range. */
    fun indexForSlot(slotId: Int, numPhysical: Int, orderedSize: Int): Int {
        val idx = slotId - numPhysical
        return if (idx in 0 until orderedSize) idx else -1
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.u1.slicer.data.MixSlotOrderingTest"`
Expected: PASS.

- [ ] **Step 5: Refactor `MixedFilamentManager.serialize()` to delegate, and add `activeMixCount`**

In `MixedFilamentManager.kt`, replace the body of `serialize(numPhysicalFilaments: Int)` (currently the project-then-library loop) with:

```kotlin
    fun serialize(numPhysicalFilaments: Int): String =
        MixSlotOrdering.activeOrder(_projectMixes.value, _libraryMixes.value, numPhysicalFilaments)
            .joinToString(";") { serializeRow(it) }

    /** Number of active mix slots for the current project given [numPhysicalFilaments]. */
    fun activeMixCount(numPhysicalFilaments: Int): Int =
        MixSlotOrdering.activeOrder(_projectMixes.value, _libraryMixes.value, numPhysicalFilaments).size

    /** The active ordering — for the picker and import auto-assign. */
    fun activeOrder(numPhysicalFilaments: Int): List<MixedFilamentRow> =
        MixSlotOrdering.activeOrder(_projectMixes.value, _libraryMixes.value, numPhysicalFilaments)
```

Keep `serializeRow(...)` unchanged. Note `serializeRow` uses `u${r.id}` for the unique token — the engine maps recipe entries to filament ids positionally (entry order = virtual id order), which is what `activeOrder` now guarantees.

- [ ] **Step 6: Run existing manager tests to confirm no regression**

Run: `./gradlew testDebugUnitTest --tests "com.u1.slicer.data.MixedFilamentManagerTest"`
Expected: PASS (serialize output order is unchanged from Phase A — same project-then-library iteration, now centralised).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/u1/slicer/data/MixSlotOrdering.kt \
        app/src/test/java/com/u1/slicer/data/MixSlotOrderingTest.kt \
        app/src/main/java/com/u1/slicer/data/MixedFilamentManager.kt
git commit -m "feat(M3-B): MixSlotOrdering — shared mix slot ordering (serialize/picker/paint invariant)"
```

---

## Task 2: ColourMatch — naive blend + CIELAB closest-colour matcher

**Files:**
- Create: `app/src/main/java/com/u1/slicer/aipaint/ColourMatch.kt`
- Create: `app/src/test/java/com/u1/slicer/aipaint/ColourMatchTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.u1.slicer.aipaint

import org.junit.Assert.assertEquals
import org.junit.Test

class ColourMatchTest {
    @Test fun naiveBlend_endpoints_andMidpoint() {
        assertEquals("#0000FF", ColourMatch.naiveBlendHex("#0000FF", "#FFFF00", 0))   // all A
        assertEquals("#FFFF00", ColourMatch.naiveBlendHex("#0000FF", "#FFFF00", 100)) // all B
        assertEquals("#808080", ColourMatch.naiveBlendHex("#0000FF", "#FFFF00", 50))  // midpoint
    }

    @Test fun closestSlot_picksNearestByDeltaE() {
        // palette: E1 blue, E2 yellow, mix(blue+yellow)=green-ish at slot 2
        val palette = listOf("#0000FF", "#FFFF00", "#808040")
        assertEquals(1, ColourMatch.closestSlot("#FFEE10", palette)) // near yellow → slot 1
        assertEquals(0, ColourMatch.closestSlot("#1010EE", palette)) // near blue   → slot 0
    }

    @Test fun closestSlot_emptyPaletteReturnsZero() {
        assertEquals(0, ColourMatch.closestSlot("#123456", emptyList()))
    }

    @Test fun deltaE_identicalIsZero() {
        assertEquals(0.0, ColourMatch.deltaE76("#3FA34D", "#3FA34D"), 1e-6)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.u1.slicer.aipaint.ColourMatchTest"`
Expected: FAIL — `ColourMatch` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.u1.slicer.aipaint

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Pure colour helpers for Phase B. Naive RGB blend predicts a mix's display colour (good
 * enough for matching + swatches; M4 replaces it with prusa-fdm-mixer). CIELAB ΔE76 ranks
 * how close a target colour is to each palette entry.
 */
object ColourMatch {
    private fun parse(hex: String): Triple<Int, Int, Int> {
        val h = hex.removePrefix("#")
        val v = h.toLong(16).toInt()
        return Triple((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)
    }
    private fun fmt(r: Int, g: Int, b: Int) =
        "#%02X%02X%02X".format(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))

    /** Linear interpolation in sRGB space. [pB] is 0..100, the share of [b]. */
    fun naiveBlendHex(a: String, b: String, pB: Int): String {
        val (ar, ag, ab) = parse(a); val (br, bg, bb) = parse(b)
        val t = pB.coerceIn(0, 100) / 100.0
        return fmt(
            Math.round(ar * (1 - t) + br * t).toInt(),
            Math.round(ag * (1 - t) + bg * t).toInt(),
            Math.round(ab * (1 - t) + bb * t).toInt(),
        )
    }

    private fun srgbToLin(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }
    private fun lab(hex: String): DoubleArray {
        val (R, G, B) = parse(hex)
        val r = srgbToLin(R); val g = srgbToLin(G); val b = srgbToLin(B)
        var x = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047
        var y = (r * 0.2126 + g * 0.7152 + b * 0.0722) / 1.0
        var z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883
        fun f(t: Double) = if (t > 0.008856) t.pow(1.0 / 3.0) else 7.787 * t + 16.0 / 116.0
        x = f(x); y = f(y); z = f(z)
        return doubleArrayOf(116 * y - 16, 500 * (x - y), 200 * (y - z))
    }

    /** CIE76 ΔE between two hex colours. 0 = identical. */
    fun deltaE76(a: String, b: String): Double {
        val la = lab(a); val lb = lab(b)
        val dl = la[0] - lb[0]; val da = la[1] - lb[1]; val db = la[2] - lb[2]
        return sqrt(dl * dl + da * da + db * db)
    }

    /** Index of the palette entry nearest [target] by ΔE76. Returns 0 for an empty palette. */
    fun closestSlot(target: String, palette: List<String>): Int {
        if (palette.isEmpty()) return 0
        var best = 0; var bestD = Double.MAX_VALUE
        palette.forEachIndexed { i, hex ->
            val d = deltaE76(target, hex)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /** ΔE from [target] to its closest palette entry — used for the "no close match" threshold. */
    fun closestDistance(target: String, palette: List<String>): Double {
        if (palette.isEmpty()) return Double.MAX_VALUE
        return palette.minOf { deltaE76(target, it) }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.u1.slicer.aipaint.ColourMatchTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/aipaint/ColourMatch.kt \
        app/src/test/java/com/u1/slicer/aipaint/ColourMatchTest.kt
git commit -m "feat(M3-B): ColourMatch — naive RGB blend + CIELAB closest-colour matcher"
```

---

## Task 3: F1 — widen the slot cap in AiPaintViewModel

The three guards `paintTriangles` (`:486`), `setSegmentSlot` (`:509`), `cascadeReassign` (`:527`) currently reject `slot !in 0 until TARGET_SLOTS` (4). Widen to `numPhysical + activeMixCount`. With zero mixes the ceiling is `numPhysical` → behaviour identical to today (no-regression).

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/aipaint/AiPaintViewModel.kt`
- Test: `app/src/test/java/com/u1/slicer/aipaint/AiPaintSlotCeilingTest.kt` (create)

- [ ] **Step 1: Read the ViewModel's constructor + how it can reach the mix manager / numPhysical**

Run: open `app/src/main/java/com/u1/slicer/aipaint/AiPaintViewModel.kt` and confirm how it is constructed (it currently has no MixedFilamentManager reference). Confirm the numPhysical source — the same value `SlicerViewModel` passes to `serialize()` (`~:5105`). Decide the threading: add a `slotCeilingProvider: () -> Int` constructor param (default `{ SegmentationCascade.TARGET_SLOTS }`) supplied by the owner (MainActivity/SlicerViewModel) as `{ numPhysical + mixManager.activeMixCount(numPhysical) }`. This keeps the ViewModel decoupled from the manager.

- [ ] **Step 2: Write the failing test**

```kotlin
package com.u1.slicer.aipaint

import org.junit.Assert.assertEquals
import org.junit.Test

/** Source-level guard: the three slot guards use the dynamic ceiling, not the bare constant. */
class AiPaintSlotCeilingTest {
    private val src = java.io.File(
        "src/main/java/com/u1/slicer/aipaint/AiPaintViewModel.kt"
    ).readText()

    @Test fun guardsUseDynamicCeiling_notTargetSlots() {
        // No guard should still hard-cap reassignment at TARGET_SLOTS.
        val badGuards = Regex("""!in 0 until TARGET_SLOTS""").findAll(src).count()
        assertEquals(0, badGuards)
    }

    @Test fun slotCeilingProviderExists() {
        assert(src.contains("slotCeiling")) { "expected a slotCeiling provider/field" }
    }
}
```

(Source-grep guard — this codebase already uses source-grep tests where a Compose/VM harness is impractical; see CLAUDE.md test list e.g. `LocaleNumberWiringTest`.)

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.u1.slicer.aipaint.AiPaintSlotCeilingTest"`
Expected: FAIL — guards still say `!in 0 until TARGET_SLOTS`.

- [ ] **Step 4: Implement**

Add the provider to the `AiPaintViewModel` constructor (default preserves today's behaviour):

```kotlin
    // Upper bound (exclusive) for a valid slot id = numPhysical + active mix count.
    // Defaults to the physical count so tests / callers that don't supply mixes behave as before.
    private val slotCeiling: () -> Int = { SegmentationCascade.TARGET_SLOTS },
```

Replace each guard:

```kotlin
        // paintTriangles (was :486)
        if (toSlot !in 0 until slotCeiling()) return
        // setSegmentSlot (was :509)
        if (newSlot !in 0 until slotCeiling()) return
        // cascadeReassign (was :527)
        if (newSlot !in 0 until slotCeiling()) return
```

Then at the construction site (MainActivity / SlicerViewModel, wherever `AiPaintViewModel` is built), pass:

```kotlin
        slotCeiling = { numPhysical + mixedFilamentManager.activeMixCount(numPhysical) },
```

where `numPhysical` is the same value used at `SlicerViewModel ~:5105`. If the ViewModel is built before `numPhysical` is known, capture it via the existing config/state the screen already reads.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.u1.slicer.aipaint.AiPaintSlotCeilingTest"`
Expected: PASS.

- [ ] **Step 6: Build to confirm the new constructor param compiles at all call sites**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/u1/slicer/aipaint/AiPaintViewModel.kt \
        app/src/test/java/com/u1/slicer/aipaint/AiPaintSlotCeilingTest.kt
git commit -m "feat(M3-B/F1): widen AiPaint slot guards to numPhysical+mixCount ceiling"
```

---

## Task 4: F2 — PaintedMeshWriter encodes slots ≥ 4 (HIGH RISK — de-risk first)

The `paint_color` codes are an OrcaSlicer TriangleSelector bitstream (documented at `PaintedMeshWriter.kt:12-18`): state 1→`4`, 2→`8`, 3→`0C`, 4→`1C`. The extended pattern for states ≥3 is `"${(state-3).hex}C"` (5→`2C`, 6→`3C`, 7→`4C`, 8→`5C`). **This must be verified against the engine by a native round-trip (Step 5/6), not assumed.**

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/aipaint/PaintedMeshWriter.kt`
- Test: `app/src/test/java/com/u1/slicer/aipaint/PaintedMeshWriterSlotEncodingTest.kt` (create)
- Test (native): `app/src/androidTest/java/com/u1/slicer/slicing/MixSlotPaintRoundTripTest.kt` (create)

- [ ] **Step 1: Write the failing unit test**

```kotlin
package com.u1.slicer.aipaint

import org.junit.Assert.assertEquals
import org.junit.Test

class PaintedMeshWriterSlotEncodingTest {
    @Test fun matchesDocumentedStates1to4() {
        assertEquals("4",  PaintedMeshWriter.encodePaintColor(0))
        assertEquals("8",  PaintedMeshWriter.encodePaintColor(1))
        assertEquals("0C", PaintedMeshWriter.encodePaintColor(2))
        assertEquals("1C", PaintedMeshWriter.encodePaintColor(3))
    }
    @Test fun extendsToMixStates5to8() {
        assertEquals("2C", PaintedMeshWriter.encodePaintColor(4)) // engine filament 5
        assertEquals("3C", PaintedMeshWriter.encodePaintColor(5))
        assertEquals("4C", PaintedMeshWriter.encodePaintColor(6))
        assertEquals("5C", PaintedMeshWriter.encodePaintColor(7))
    }
    @Test fun distinctCodesNoTruncation() {
        val codes = (0..11).map { PaintedMeshWriter.encodePaintColor(it) }
        assertEquals(codes.size, codes.toSet().size) // all distinct — no coerceIn(0,3) collapse
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.u1.slicer.aipaint.PaintedMeshWriterSlotEncodingTest"`
Expected: FAIL — `encodePaintColor` unresolved.

- [ ] **Step 3: Implement the encoder and use it**

In `PaintedMeshWriter.kt`, add (and keep `PAINT_COLOR` deleted or unused):

```kotlin
    /**
     * Leaf-triangle paint_color code for a 0-based [slot] (engine paint state = slot + 1).
     * States 1–2 are direct (state<<2); states ≥3 use the extended escape: rightmost nibble
     * 0xC marks "extended", the next nibble is (state-3). Verified by MixSlotPaintRoundTripTest.
     * Single-nibble extended range covers states 3..18 (slots 2..17) — beyond that needs a
     * longer encoding (not expected in Phase B; guarded below).
     */
    fun encodePaintColor(slot: Int): String {
        val state = slot.coerceAtLeast(0) + 1
        return when {
            state <= 2 -> (state shl 2).toString(16).uppercase()      // 1->"4", 2->"8"
            state - 3 <= 0xF -> "${(state - 3).toString(16).uppercase()}C" // 3->"0C" … 18->"FC"
            else -> throw IllegalArgumentException("paint slot $slot exceeds single-nibble range")
        }
    }
```

Replace the use at `:138`:

```kotlin
            val paint = encodePaintColor(regionIds[i])
```

- [ ] **Step 4: Run unit test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.u1.slicer.aipaint.PaintedMeshWriterSlotEncodingTest"`
Expected: PASS.

- [ ] **Step 5: Extend `buildProjectSettings` to size `filament_colour` to physical+mix**

The painted 3MF's `filament_colour` array must have an entry for every slot used (physical + mix display colours) so the canonical filament list sizes correctly and the engine's segmentation doesn't collapse. Add an optional param and have `write()` pass it:

```kotlin
    // write(...) new param:
    //   mixDisplayColours: List<String> = emptyList()  // naive-blend hex, one per active mix, in MixSlotOrdering order
    // buildProjectSettings: append mixDisplayColours after the physical colours so index
    //   numPhysical+k = mix k. filament_count = numPhysical + mixDisplayColours.size.
```

Concretely, change `buildProjectSettings(regions, printerColours)` to also accept `mixDisplayColours: List<String>` and build `filament_colour` as: physical slot colours (from `printerColours`, length numPhysical) followed by `mixDisplayColours`. Keep `filament_type`/`filament_settings_id` length in sync (PLA/Generic PLA for the mix entries is fine — the recipe drives behaviour). The caller (AiPaintViewModel accept-paint path) passes `mixManager.activeOrder(numPhysical).map { ColourMatch.naiveBlendHex(physical[it.componentA-1], physical[it.componentB-1], it.mixBPercent) }`.

- [ ] **Step 6: Write the native round-trip instrumented test (the real verification)**

```kotlin
package com.u1.slicer.slicing
// MixSlotPaintRoundTripTest:
// 1. Build a small two-triangle positions array; regionIds = [4, 5] (slots 4 & 5 = first two mixes
//    with numPhysical=4).
// 2. PaintedMeshWriter.write(pos, regionIds, regions(size 6), out3mf,
//      printerColours = 4 physical hexes, mixDisplayColours = 2 blended hexes).
// 3. Load out3mf via NativeLibrary.loadModel(path); read back per-triangle filament ids using the
//    existing native paint-state accessor used elsewhere (see NativePreparePreviewTest for the
//    accessor pattern). Assert triangle 0 → filament id 5, triangle 1 → filament id 6
//    (engine ids = slot+1). If the assert fails, the extended encoding is wrong — consult
//    orcaslicer TriangleSelector serialization in the submodule before changing the encoder.
```

Implement it following the load + accessor pattern in `app/src/androidTest/.../viewer/NativePreparePreviewTest.kt`.

- [ ] **Step 7: Run the round-trip on device**

Run: `ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.slicing.MixSlotPaintRoundTripTest"`
Expected: PASS. **If it fails, STOP and fix the encoder against the engine source before proceeding — every later feature rides on this.**

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/u1/slicer/aipaint/PaintedMeshWriter.kt \
        app/src/test/java/com/u1/slicer/aipaint/PaintedMeshWriterSlotEncodingTest.kt \
        app/src/androidTest/java/com/u1/slicer/slicing/MixSlotPaintRoundTripTest.kt
git commit -m "feat(M3-B/F2): encode paint_color for mix slots >=4 + native round-trip verification"
```

---

## Task 5: F3 — extend the preview palette with mix colours

`AiPaintResultScreen.kt:130-147` builds `slotPalette`/`slotPaletteFloats` as exactly `TARGET_SLOTS` (4) entries. Extend to `numPhysical` physical colours + one naive-blend colour per active mix. `MeshData.recolor` already handles a longer palette (`coerceAtMost(lastIndex)`), so no `MeshData` change is needed.

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt`
- Test: `app/src/test/java/com/u1/slicer/ui/AiPaintPaletteWiringTest.kt` (create — source-grep guard)

- [ ] **Step 1: Write the failing source-grep test**

```kotlin
package com.u1.slicer.ui

import org.junit.Assert.assertFalse
import org.junit.Test

class AiPaintPaletteWiringTest {
    private val src = java.io.File("src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt").readText()

    @Test fun slotPaletteNotHardCappedAtTargetSlots() {
        // The palette must include mix colours, so it can't be built solely from 0 until TARGET_SLOTS.
        assertFalse(
            "slotPalette still iterates only physical TARGET_SLOTS",
            src.contains("(0 until com.u1.slicer.aipaint.SegmentationCascade.TARGET_SLOTS).map { slot ->")
        )
    }
    @Test fun paletteUsesMixColours() {
        assert(src.contains("naiveBlendHex") || src.contains("mixDisplayColours"))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.u1.slicer.ui.AiPaintPaletteWiringTest"`
Expected: FAIL.

- [ ] **Step 3: Implement — extend the palette builder**

`AiPaintResultScreen` must receive the active mixes + physical colours. Add composable params: `projectMixes: List<MixedFilamentRow>`, `libraryMixes: List<MixedFilamentRow>`, `numPhysical: Int` (default sensible). Replace the `slotPalette` builder:

```kotlin
                    val activeMixes = remember(projectMixes, libraryMixes, numPhysical) {
                        com.u1.slicer.data.MixSlotOrdering.activeOrder(projectMixes, libraryMixes, numPhysical)
                    }
                    val slotPalette: List<Color> = remember(filamentColours, activeMixes, numPhysical) {
                        val physical = (0 until numPhysical).map { slot ->
                            val hex = filamentColours.getOrNull(slot) ?: "#888888"
                            Color(runCatching { android.graphics.Color.parseColor(hex) }
                                .getOrDefault(android.graphics.Color.GRAY))
                        }
                        val mixes = activeMixes.map { row ->
                            val a = filamentColours.getOrNull(row.componentA - 1) ?: "#888888"
                            val b = filamentColours.getOrNull(row.componentB - 1) ?: "#888888"
                            val hex = com.u1.slicer.aipaint.ColourMatch.naiveBlendHex(a, b, row.mixBPercent)
                            Color(runCatching { android.graphics.Color.parseColor(hex) }
                                .getOrDefault(android.graphics.Color.GRAY))
                        }
                        physical + mixes
                    }
```

`slotPaletteFloats` derivation stays as-is (it maps over `slotPalette`).

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.u1.slicer.ui.AiPaintPaletteWiringTest"`
Expected: PASS.

- [ ] **Step 5: Thread the new params from MainActivity (~:1524)**

At the `AiPaintResultScreen(...)` call site in `MainActivity.kt`, pass `projectMixes = mixManager.projectMixes.collectAsState().value`, `libraryMixes = mixManager.libraryMixes.collectAsState().value`, `numPhysical = <same value as serialize() at SlicerViewModel ~:5105>`. Build with `./gradlew compileDebugKotlin`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt \
        app/src/main/java/com/u1/slicer/MainActivity.kt \
        app/src/test/java/com/u1/slicer/ui/AiPaintPaletteWiringTest.kt
git commit -m "feat(M3-B/F3): extend AiPaint preview palette with naive-blend mix colours"
```

---

## Task 6: C1a — SectionedSlotPicker for per-region assignment

Replace the `HighlightSlotPicker`'s slot list (`AiPaintResultScreen.kt:267-283`) — which only offers 4 physical chips — with `SectionedSlotPicker` (PHYSICAL / THIS PROJECT / LIBRARY). It already emits `numPhysical + index` ids matching `MixSlotOrdering`.

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt`
- Test: `app/src/test/java/com/u1/slicer/ui/SectionedPickerWiringTest.kt` (create — source-grep)

- [ ] **Step 1: Failing source-grep test** — assert `AiPaintResultScreen.kt` references `SectionedSlotPicker(` and passes `projectMixes`/`libraryMixes`/`onCreateMix`. (Pattern as in Task 5's test.)

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement** — in the `if (highlightedNode != null)` block, render `SectionedSlotPicker(physicalColours = slotPalette.take(numPhysical), physicalLabels = (1..numPhysical).map { "E$it" }, projectMixes = projectMixes, libraryMixes = libraryMixes.filter { it.componentA <= numPhysical && it.componentB <= numPhysical }, selectedSlot = highlightedNode.region.slot, onSelect = { slot -> onSetSegmentSlot(highlightedNode.region.id, slot) }, onCreateMix = { onCreateMix() }, onEditMix = { onEditMix(it) })`. Add `onCreateMix`/`onEditMix` lambdas to the screen signature; wire them in MainActivity to open `CreateMixSlotDialog` (Phase A dialog). The library filter MUST match `MixSlotOrdering.activeOrder`'s skip rule so ids line up.

- [ ] **Step 4: Run → PASS.**

- [ ] **Step 5: Build** `./gradlew compileDebugKotlin` → SUCCESS.

- [ ] **Step 6: Commit** `feat(M3-B/C1a): per-region SectionedSlotPicker with mix slots`.

---

## Task 7: C1b — mixes in the Paint/Lasso brush palette (Option 2)

The brush palette (`SlotPaletteRow`, `AiPaintResultScreen.kt:332`) offers only `slotPalette`'s physical chips for the active paint slot. Since `slotPalette` now includes mix colours (Task 5), `SlotPaletteRow` already iterates the full palette — verify it renders the mix entries and that tapping a mix sets `paintActiveRegion` to a slot id ≥ numPhysical (so the brush paints a mix).

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt` (and `SlotPaletteRow` if it caps at 4)
- Test: extend `SectionedPickerWiringTest` or add a source-grep that `SlotPaletteRow` iterates `slotPalette` (not a fixed 4).

- [ ] **Step 1: Read `SlotPaletteRow`** (same file or its definition) and confirm whether it iterates `slotPalette` fully or hardcodes 4. Write a failing test asserting it uses `slotPalette.size`.
- [ ] **Step 2: Run → FAIL (if capped).**
- [ ] **Step 3: Implement** — make `SlotPaletteRow` iterate the full `slotPalette`; render mix entries with `MixedSlotSwatch` (primary=componentA colour, secondary=componentB colour) instead of a plain circle when the index ≥ numPhysical. Ensure `paintActiveRegion` can hold ≥ numPhysical (it's an Int; the Task 3 ceiling already allows it through `paintTriangles`).
- [ ] **Step 4: Run → PASS.**
- [ ] **Step 5: Build → SUCCESS.**
- [ ] **Step 6: Commit** `feat(M3-B/C1b): brush palette can paint with mix slots`.

---

## Task 8: C2 — MixedSlotSwatch in region rows

Region rows (`AiPaintTreeRow.kt`) show a slot swatch per region. For slots ≥ numPhysical, render `MixedSlotSwatch` (two-tone) instead of a single-colour circle.

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/ui/AiPaintTreeRow.kt`
- Test: `app/src/test/java/com/u1/slicer/ui/AiPaintTreeRowMixSwatchTest.kt` (source-grep that the row references `MixedSlotSwatch` and branches on `slot >= numPhysical`).

- [ ] **Step 1: Read `AiPaintTreeRow.kt`** to find the current swatch rendering + how it receives the slot/colour. Write the failing source-grep test.
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement** — pass `numPhysical` + the active mix list (or a `slotId -> (primary,secondary)?` lookup) into the row; when `region.slot >= numPhysical`, render `MixedSlotSwatch(primary = physical[componentA-1], secondary = physical[componentB-1])` using the mix at `MixSlotOrdering.indexForSlot(region.slot, numPhysical, activeMixes.size)`.
- [ ] **Step 4: Run → PASS.** **Step 5: Build → SUCCESS.** **Step 6: Commit** `feat(M3-B/C2): MixedSlotSwatch in region rows`.

---

## Task 9: C5 — print-cost banner

When ≥ 1 leaf region has `slot >= numPhysical`, show an honest banner above the tree: "N regions use mix slots — this adds tool changes and print time." No fabricated hour figure.

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt`
- Test: `app/src/test/java/com/u1/slicer/ui/MixCostBannerTest.kt` (pure helper test).

- [ ] **Step 1: Write the failing test** for a pure helper:

```kotlin
package com.u1.slicer.ui
import org.junit.Assert.assertEquals
import org.junit.Test
class MixCostBannerTest {
    @Test fun countsRegionsOnMixSlots() {
        // slots: [0,1,5,5,2] with numPhysical=4 → 2 regions use mixes
        assertEquals(2, mixRegionCount(listOf(0,1,5,5,2), numPhysical = 4))
    }
    @Test fun zeroWhenNoMixSlots() {
        assertEquals(0, mixRegionCount(listOf(0,1,2,3), numPhysical = 4))
    }
}
```

- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement** `fun mixRegionCount(slots: List<Int>, numPhysical: Int) = slots.count { it >= numPhysical }` (top-level in the screen file or a helpers file), and render the banner Composable when `mixRegionCount(leafRegions.map { it.slot }, numPhysical) > 0`. Use the string: `"$n regions use mix slots — this adds tool changes and print time."`
- [ ] **Step 4: Run → PASS. Step 5: Build → SUCCESS. Step 6: Commit** `feat(M3-B/C5): print-cost banner when mixes are in use`.

---

## Task 10: C4 — import seeding from embedded colours + auto-assign + unmatched note

When the user explicitly opens a model that carries embedded per-object/per-triangle colours, seed Smart Paint regions from those colours (a new segmentation source) and auto-assign each region to the closest existing slot via `ColourMatch.closestSlot` over `{physical colours} + {active mix display colours}`. Regions whose closest distance exceeds a ΔE threshold fall back to closest physical and increment the "no close match" count.

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/aipaint/SegmentationCascade.kt` (or add a sibling builder) — a builder that turns embedded per-object extruders/colours into leaf regions.
- Modify: `app/src/main/java/com/u1/slicer/aipaint/AiPaintViewModel.kt` — auto-assign pass after seeding; expose an `unmatchedColourCount` in the result state.
- Modify: `app/src/main/java/com/u1/slicer/aipaint/AiRegion.kt` — add `val unmatchedColourCount: Int = 0` to `AiPaintResultState`.
- Modify: `app/src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt` — render the note when `unmatchedColourCount > 0`.
- Test: `app/src/test/java/com/u1/slicer/aipaint/ImportAutoAssignTest.kt` (pure logic for the auto-assign + threshold).
- Test (instrumented): folded into Task 11's `SmartPaintMixIntegrationTest`.

- [ ] **Step 1: Read** `SegmentationCascade.kt` lines around `:60-160` (it already reads `obj.extruder` / `v.extruder` from embedded data — see the `obj.extruder?.let { (it-1).coerceIn(...) }` paths). This is the seam: those embedded extruder/colour reads become the region source for the import case.

- [ ] **Step 2: Write the failing pure-logic test**

```kotlin
package com.u1.slicer.aipaint
import org.junit.Assert.assertEquals
import org.junit.Test
class ImportAutoAssignTest {
    // autoAssign returns (slotPerRegion, unmatchedCount)
    @Test fun assignsClosestExistingSlot_andCountsUnmatched() {
        val targets = listOf("#0000FF", "#FFFF00", "#FF00FF") // blue, yellow, magenta
        val palette = listOf("#0000FF", "#FFFF00")            // only blue+yellow exist
        val (slots, unmatched) = autoAssignRegions(targets, palette, deltaThreshold = 25.0)
        assertEquals(listOf(0, 1, /*magenta→closest*/ 0), slots) // magenta nearest blue here
        assertEquals(1, unmatched)                               // magenta exceeded threshold
    }
}
```

- [ ] **Step 3: Run → FAIL.**

- [ ] **Step 4: Implement** the pure helper (in `ColourMatch.kt` or a new `ImportAutoAssign.kt`):

```kotlin
fun autoAssignRegions(
    targets: List<String>,
    palette: List<String>,
    deltaThreshold: Double = 25.0,
): Pair<List<Int>, Int> {
    var unmatched = 0
    val slots = targets.map { t ->
        if (ColourMatch.closestDistance(t, palette) > deltaThreshold) unmatched++
        ColourMatch.closestSlot(t, palette)
    }
    return slots to unmatched
}
```

(ΔE76 ≈ 25 is a reasonable "noticeably different" cutoff for v1; tune during device testing.)

- [ ] **Step 5: Run → PASS.**

- [ ] **Step 6: Wire it into the import path** — add a segmentation source that builds regions from embedded colours, then in the ViewModel run `autoAssignRegions(regionTargetColours, physicalColours + activeMixDisplayColours)` and set each region's slot, storing `unmatchedColourCount` in `AiPaintResultState`. Render the note in `AiPaintResultScreen` when `> 0`: `"$n colours had no close match — create a mix to improve them."` Add `MainActivity` routing so opening a coloured model in Smart Paint uses this source (explicit entry — no auto-detect).

- [ ] **Step 7: Build → SUCCESS. Commit** `feat(M3-B/C4): import region seeding + auto-assign to existing palette + unmatched note`.

---

## Task 11: Integration + regression instrumented tests

**Files:**
- Create: `app/src/androidTest/java/com/u1/slicer/aipaint/SmartPaintMixIntegrationTest.kt`

- [ ] **Step 1: Write the tests** (follow load/slice patterns in `SlicingIntegrationTest.kt` + native accessor pattern in `NativePreparePreviewTest.kt`):
  - `assignMixSlot_painted3mfCarriesVirtualId`: configure one project mix; build regions; assign one region to the mix slot (`numPhysical+0`); `PaintedMeshWriter.write(...)`; load painted 3MF; assert ≥1 triangle filament id == `numPhysical+1` and equals the serialize() virtual id.
  - `mixRegion_slicesWithRecipe`: set `SliceConfig.mixedFilamentDefinitions = manager.serialize(numPhysical)`, slice the painted 3MF; assert the G-code config dump contains the mix definition (grep `mixed_filament_definitions`, mirror Phase A's `SliceConfigMixedFilamentWiringTest`/Stage-2 test).
  - `importColouredModel_autoAssignsToExistingPalette`: open a multi-colour fixture with one pre-made matching mix; assert the matching region lands on the mix slot id and an unmatched region falls back to a physical slot with `unmatchedColourCount >= 1`.
  - `noMix_paintPipelineUnchanged`: with zero mixes, the painted 3MF for a fixture is byte-identical (same paint codes) to pre-Phase-B output — the no-regression guard.

- [ ] **Step 2: Run on device**

Run: `ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.aipaint.SmartPaintMixIntegrationTest"`
Expected: PASS.

- [ ] **Step 3: Commit** `test(M3-B): integration + no-regression guards for mix slots`.

---

## Final verification (before requesting review)

- [ ] Full JVM suite: `./gradlew testDebugUnitTest` → all green.
- [ ] Full instrumented sweep (single-device pin): `ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest --no-daemon` → BUILD SUCCESSFUL. (Heavy `GcodeBaselineDiffTest` snapshots make this ~2.5h; gradle prints only "Starting N tests" until the end — monitor device logcat `TestRunner` for progress, not the host stdout.)
- [ ] Manual device smoke: open a model in Smart Paint, create a mix, paint a region with it, confirm the preview shows the blend, slice, confirm G-code has the recipe. (Use **Map & Upload / Upload Only** if testing send — never Map & Print.)

---

## Self-review notes (author)

- **Spec coverage:** F1→Task 3, F2→Task 4, F3→Task 5, C1→Tasks 6+7, C2→Task 8, C3→Task 2, C4→Task 10, C5→Task 9, the slot-id invariant→Task 1, tests/acceptance→Task 11 + per-task tests. All spec sections map to a task.
- **Naming consistency:** `MixSlotOrdering.activeOrder`, `ColourMatch.closestSlot/naiveBlendHex/deltaE76/closestDistance`, `PaintedMeshWriter.encodePaintColor`, `AiPaintViewModel.slotCeiling`, `mixRegionCount`, `autoAssignRegions`, `unmatchedColourCount` — used identically across tasks.
- **Known in-situ reads:** Tasks 6–10 touch Compose/ViewModel wiring whose exact insertion points depend on surrounding code; each step names the file + region to read first. This is intended for subagent-driven execution where the agent has the codebase open.
- **Highest risk:** Task 4 (paint-code encoding for slots ≥4). Gated by a native round-trip; do not proceed past it on a failure.
