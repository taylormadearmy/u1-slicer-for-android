# M4 — N-way colour mixing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend a "mix slot" from exactly two physical filaments to 2–4 weighted components, blended by the engine layer-by-layer, with a drag-to-rebalance + tap-to-type editor and N-segment swatches everywhere.

**Architecture:** Kotlin + Compose only — no native change. The OrcaSlicer engine already implements N-way blends (`MixedFilament.cpp` `resolve()` routes to `build_weighted_gradient_sequence` when `gradient_ids >= 3` and `distribution_mode != Simple`). The single dormant seam is `MixedFilamentManager.serializeRow`, which currently emits empty `g`/`w` tokens; populating them with `g<ids>` + `w<weights>` activates the path. All weight math lives in a pure, unit-tested `MixWeights` helper so the gesture UI stays thin. The `MixedFilamentRow` component list is the single source of truth; legacy 2-way fields become derived read-only accessors for backward compatibility.

**Tech Stack:** Kotlin 1.9.22, Jetpack Compose / Material3, JUnit4 + Robolectric (JVM unit tests), AndroidJUnit4 + Orchestrator (instrumented), `org.json` for persistence, OrcaSlicer C++ via JNI (unchanged, pre-built `.so`).

**Worktree:** `D:\projects\u1-slicer-for-android\.claude\worktrees\m4-nway-mixes` (branch `feature/m4-nway-mixes`, off `feature/prepare-ux-unified-selector` @ aae2749). Run all commands from this worktree.

**Spec:** `docs/superpowers/specs/2026-06-07-m4-nway-colour-mixing-design.md`

---

## File Structure

**Create:**
- `app/src/main/java/com/u1/slicer/data/MixWeights.kt` — pure weight math (rebalance/add/remove/normalize, id+weight token encoding). Single responsibility: integer weights summing to 100.
- `app/src/test/java/com/u1/slicer/data/MixWeightsTest.kt` — unit tests for the above.
- `app/src/test/java/com/u1/slicer/data/MixedFilamentRowMigrationTest.kt` — legacy↔N-component conversion tests.
- `app/src/androidTest/java/com/u1/slicer/slicing/MixSlotNWayBlendGateTest.kt` — the 3- and 4-component slice gate (engine proof).

**Modify:**
- `app/src/main/java/com/u1/slicer/data/MixedFilamentRow.kt` — list-based fields + derived accessors + N-way `autoLabel`.
- `app/src/main/java/com/u1/slicer/data/MixedFilamentManager.kt` — N-component `add`/`edit`; `serializeRow` populates `g`/`w`.
- `app/src/main/java/com/u1/slicer/data/SessionState.kt` — read/write `components`/`weights` with legacy fallback.
- `app/src/main/java/com/u1/slicer/data/SettingsRepository.kt` — same for library mixes.
- `app/src/main/java/com/u1/slicer/aipaint/ColourMatch.kt` — `naiveBlendHexMulti` N-colour blend.
- `app/src/main/java/com/u1/slicer/ui/MixedSlotSwatch.kt` — N-segment proportional bar.
- `app/src/main/java/com/u1/slicer/ui/CreateMixSlotDialog.kt` — drag-bar + tap-to-type editor.
- `app/src/main/java/com/u1/slicer/ui/FilamentMixChipRow.kt` — N-segment chip; adopt as shared selector or retire.
- `app/src/main/java/com/u1/slicer/ui/PartsPanel.kt` — N-segment swatch; slot-id base fix (#2).
- `app/src/main/java/com/u1/slicer/ui/AiPaintTreeRow.kt` — N-segment chip; slot-id base fix; long-press edit (#4/#5).
- `app/src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt` — mix-leaf swatch tap → edit (#4/#5).
- `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` — N-component create/edit call-throughs; `mixPhysicalBase` fix (#2).
- Existing tests updated where signatures change: `MixedFilamentRowTest.kt`, `MixedFilamentManagerTest.kt`, `MixSlotBlendVerificationTest.kt` (helper reuse only).

**Existing helpers to reuse (do not reinvent):**
- `MixSlotOrdering.activeOrder(projectMixes, libraryMixes, numPhysical)` — active mix ordering.
- `MixSlotBlendVerificationTest.box(...)` pattern + `PaintedMeshWriter.write(...)` — for the gate test.
- `FilamentMixChipRow.physicalSlotId/mixSlotId` companion — slot-id invariant SSOT.

---

## Task 1: `MixWeights` pure helper

**Files:**
- Create: `app/src/main/java/com/u1/slicer/data/MixWeights.kt`
- Test: `app/src/test/java/com/u1/slicer/data/MixWeightsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MixWeightsTest {
    @Test fun even_splitsToHundredWithRemainderOnFirst() {
        assertEquals(listOf(34, 33, 33), MixWeights.even(3))
        assertEquals(listOf(50, 50), MixWeights.even(2))
        assertEquals(listOf(25, 25, 25, 25), MixWeights.even(4))
    }

    @Test fun normalize_scalesToHundredAndKeepsMinOne() {
        assertEquals(100, MixWeights.normalize(listOf(1, 1, 1)).sum())
        assertEquals(100, MixWeights.normalize(listOf(60, 30, 10)).sum())
        // No component may drop to 0.
        assertEquals(true, MixWeights.normalize(listOf(99, 1, 1)).all { it >= 1 })
    }

    @Test fun rebalanceAfterType_lockedValueExactOthersScaleToFill() {
        // Type index 0 = 60 over [33,33,34] -> others share remaining 40 proportionally.
        val out = MixWeights.rebalanceAfterType(listOf(33, 33, 34), index = 0, value = 60)
        assertEquals(60, out[0])
        assertEquals(100, out.sum())
        assertEquals(true, out.all { it >= 1 })
    }

    @Test fun rebalanceAfterType_clampsAndLeavesMinOneForOthers() {
        // Typing 100 cannot starve the others below 1 each.
        val out = MixWeights.rebalanceAfterType(listOf(50, 50), index = 0, value = 100)
        assertEquals(99, out[0])
        assertEquals(1, out[1])
        assertEquals(100, out.sum())
    }

    @Test fun rebalanceAfterDrag_movesBudgetBetweenTwoAdjacentOnly() {
        // Drag divider between 0 and 1: give 10 from idx1 to idx0; idx2 untouched.
        val out = MixWeights.rebalanceAfterDrag(listOf(30, 40, 30), leftIndex = 0, leftValue = 40)
        assertEquals(40, out[0])
        assertEquals(30, out[1])
        assertEquals(30, out[2])
        assertEquals(100, out.sum())
    }

    @Test fun addEven_appendsAndTrimsExistingProportionally() {
        val out = MixWeights.addEven(listOf(50, 50))   // add 3rd -> ~33 each
        assertEquals(3, out.size)
        assertEquals(100, out.sum())
        assertEquals(true, out.all { it >= 1 })
    }

    @Test fun remove_dropsIndexAndRenormalizes() {
        val out = MixWeights.remove(listOf(60, 30, 10), index = 2)
        assertEquals(2, out.size)
        assertEquals(100, out.sum())
    }

    @Test fun encodeIds_compactUnderTenElseSlash() {
        assertEquals("123", MixWeights.encodeIds(listOf(1, 2, 3)))
        assertEquals("1/12/3", MixWeights.encodeIds(listOf(1, 12, 3)))
        assertEquals("/12", MixWeights.encodeIds(listOf(12)))
    }

    @Test fun encodeWeights_slashJoined() {
        assertEquals("50/30/20", MixWeights.encodeWeights(listOf(50, 30, 20)))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.u1.slicer.data.MixWeightsTest" --no-daemon`
Expected: FAIL — `MixWeights` unresolved reference.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.u1.slicer.data

/**
 * Pure integer-weight math for N-way mixes. Every public function returns a list that
 * sums to exactly 100 with every element >= 1 (a component is removed, never zeroed).
 * No Android dependencies — fully JVM-unit-testable.
 *
 * Mirrors the native encoding in `libslic3r/MixedFilament.cpp`:
 *   ids     -> compact "123" when all <= 9, else slash form "1/12/3" (single >9 id -> "/12")
 *   weights -> slash-joined "50/30/20"
 */
object MixWeights {

    /** Even split of [n] components summing to 100; any remainder lands on the first entries. */
    fun even(n: Int): List<Int> {
        require(n >= 1)
        val base = 100 / n
        val rem = 100 - base * n
        return (0 until n).map { base + if (it < rem) 1 else 0 }
    }

    /** Scale arbitrary positive weights to sum 100 with a floor of 1 per element. */
    fun normalize(weights: List<Int>): List<Int> {
        require(weights.isNotEmpty())
        val clamped = weights.map { it.coerceAtLeast(1) }
        val total = clamped.sum()
        if (total == 100) return clamped
        // Largest-remainder apportionment to hit exactly 100.
        val scaled = clamped.map { it * 100.0 / total }
        val floors = scaled.map { kotlin.math.floor(it).toInt().coerceAtLeast(1) }
        var deficit = 100 - floors.sum()
        val order = scaled.indices.sortedByDescending { scaled[it] - kotlin.math.floor(scaled[it]) }
        val out = floors.toMutableList()
        var i = 0
        while (deficit > 0 && order.isNotEmpty()) { out[order[i % order.size]] += 1; deficit--; i++ }
        while (deficit < 0) {
            val victim = out.indices.filter { out[it] > 1 }.maxByOrNull { out[it] } ?: break
            out[victim] -= 1; deficit++
        }
        return out
    }

    /** Lock [index] to [value] (clamped so others keep >=1); other elements scale to fill the rest. */
    fun rebalanceAfterType(weights: List<Int>, index: Int, value: Int): List<Int> {
        val n = weights.size
        if (n == 1) return listOf(100)
        val maxForIndex = 100 - (n - 1)            // leave >=1 for each other component
        val locked = value.coerceIn(1, maxForIndex)
        val remaining = 100 - locked
        val others = weights.indices.filter { it != index }
        val otherSum = others.sumOf { weights[it] }.coerceAtLeast(1)
        val scaled = others.map { (weights[it] * remaining.toDouble() / otherSum) }
        val floors = scaled.map { kotlin.math.floor(it).toInt().coerceAtLeast(1) }
        var deficit = remaining - floors.sum()
        val ord = scaled.indices.sortedByDescending { scaled[it] - kotlin.math.floor(scaled[it]) }
        val otherOut = floors.toMutableList()
        var i = 0
        while (deficit > 0 && ord.isNotEmpty()) { otherOut[ord[i % ord.size]] += 1; deficit--; i++ }
        while (deficit < 0) {
            val victim = otherOut.indices.filter { otherOut[it] > 1 }.maxByOrNull { otherOut[it] } ?: break
            otherOut[victim] -= 1; deficit++
        }
        val out = weights.toMutableList()
        out[index] = locked
        others.forEachIndexed { k, oi -> out[oi] = otherOut[k] }
        return out
    }

    /** Drag the divider after [leftIndex]: set that element to [leftValue]; the budget moves to/from
     *  its immediate right neighbour only (others untouched). */
    fun rebalanceAfterDrag(weights: List<Int>, leftIndex: Int, leftValue: Int): List<Int> {
        require(leftIndex in 0 until weights.size - 1)
        val pairSum = weights[leftIndex] + weights[leftIndex + 1]
        val left = leftValue.coerceIn(1, pairSum - 1)
        val out = weights.toMutableList()
        out[leftIndex] = left
        out[leftIndex + 1] = pairSum - left
        return out
    }

    /** Append a new component at an even share; existing weights renormalize to make room. */
    fun addEven(weights: List<Int>): List<Int> {
        val n = weights.size + 1
        val target = (100.0 / n)
        val existing = weights.map { (it * (100 - target) / 100.0) }
        val combined = existing + target
        return normalize(combined.map { kotlin.math.round(it).toInt() })
    }

    /** Remove [index] and renormalize the rest. Caller guarantees result keeps >= 2 components. */
    fun remove(weights: List<Int>, index: Int): List<Int> =
        normalize(weights.filterIndexed { i, _ -> i != index })

    fun encodeIds(ids: List<Int>): String {
        val extended = ids.any { it > 9 }
        if (extended && ids.size == 1) return "/" + ids[0]
        return if (extended) ids.joinToString("/") else ids.joinToString("")
    }

    fun encodeWeights(weights: List<Int>): String = weights.joinToString("/")
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.u1.slicer.data.MixWeightsTest" --no-daemon`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/data/MixWeights.kt app/src/test/java/com/u1/slicer/data/MixWeightsTest.kt
git commit -m "feat(M4): pure MixWeights helper (rebalance/add/remove/encode)"
```

---

## Task 2: `MixedFilamentRow` N-component model + migration

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/data/MixedFilamentRow.kt`
- Test: `app/src/test/java/com/u1/slicer/data/MixedFilamentRowMigrationTest.kt` (create)
- Test: `app/src/test/java/com/u1/slicer/data/MixedFilamentRowTest.kt` (update existing if it constructs rows positionally)

- [ ] **Step 1: Write the failing test**

```kotlin
package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MixedFilamentRowMigrationTest {
    @Test fun derivedAccessors_matchFirstTwoComponents() {
        val row = MixedFilamentRow(
            id = 1, components = listOf(1, 3, 4), weights = listOf(50, 30, 20),
            distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
            label = "x", inLibrary = false,
        )
        assertEquals(1, row.componentA)
        assertEquals(3, row.componentB)
        assertEquals(30, row.mixBPercent)   // weight of component B
    }

    @Test fun fromLegacy_reconstructsComponentsAndWeights() {
        val row = MixedFilamentRow.fromLegacy(
            id = 7, componentA = 1, componentB = 2, mixBPercent = 30,
            distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
            label = "L", inLibrary = true,
        )
        assertEquals(listOf(1, 2), row.components)
        assertEquals(listOf(70, 30), row.weights)
        assertEquals(true, row.inLibrary)
    }

    @Test fun autoLabel_listForm() {
        assertEquals("E1+E2+E3", MixedFilamentRow.autoLabel(listOf(1, 2, 3)))
        assertEquals("E1+E3", MixedFilamentRow.autoLabel(listOf(1, 3)))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.u1.slicer.data.MixedFilamentRowMigrationTest" --no-daemon`
Expected: FAIL — `components` / `fromLegacy` / list `autoLabel` unresolved.

- [ ] **Step 3: Replace `MixedFilamentRow.kt` with the list model**

```kotlin
package com.u1.slicer.data

/**
 * One row of the full-spectrum mix recipe. Maps to one virtual filament slot the engine
 * creates by blending [components] (2..4 physical filaments) at [weights] (sum 100).
 *
 * [components] / [weights] are the single source of truth. The 2-way accessors
 * (componentA/componentB/mixBPercent) are DERIVED read-only views for code not yet
 * generalized to N — they are never stored.
 *
 * Indices are 1-based to match the engine's filament numbering.
 */
data class MixedFilamentRow(
    val id: Long,
    val components: List<Int>,                    // 2..4 entries, 1-based, distinct
    val weights: List<Int>,                       // same size; each >= 1; sum == 100
    val distributionMode: MixDistributionMode,
    val label: String,
    val inLibrary: Boolean,
) {
    init {
        require(components.size in 2..4) { "a mix has 2..4 components, got ${components.size}" }
        require(weights.size == components.size) { "weights must match components" }
    }

    val componentA: Int get() = components.getOrElse(0) { 1 }
    val componentB: Int get() = components.getOrElse(1) { componentA }
    /** Share (0..100) of the second component — preserves the legacy 2-way meaning. */
    val mixBPercent: Int get() = weights.getOrElse(1) { 0 }

    enum class MixDistributionMode {
        /** Whole-layer alternation: each layer uses one component per the weighted cadence. */
        LAYER_CYCLE,
        /** Same-layer dot pattern: each layer interleaves components in XY. */
        SAME_LAYER_DOTS,
    }

    companion object {
        /** Default label, list form. Example: autoLabel([1,2,3]) -> "E1+E2+E3". */
        fun autoLabel(components: List<Int>): String =
            components.joinToString("+") { "E$it" }

        /** Build a row from legacy 2-way fields (used by JSON readers for old saves). */
        fun fromLegacy(
            id: Long,
            componentA: Int,
            componentB: Int,
            mixBPercent: Int,
            distributionMode: MixDistributionMode,
            label: String,
            inLibrary: Boolean,
        ): MixedFilamentRow {
            val p = mixBPercent.coerceIn(0, 100)
            return MixedFilamentRow(
                id = id,
                components = listOf(componentA, componentB),
                weights = listOf(100 - p, p),
                distributionMode = distributionMode,
                label = label,
                inLibrary = inLibrary,
            )
        }
    }
}
```

- [ ] **Step 4: Fix existing `MixedFilamentRowTest.kt` constructors**

Open `app/src/test/java/com/u1/slicer/data/MixedFilamentRowTest.kt`. Any positional `MixedFilamentRow(id, componentA=…, componentB=…, mixBPercent=…, …)` construction must become the list form, e.g.:

```kotlin
// before: MixedFilamentRow(1, 1, 3, 50, LAYER_CYCLE, "E1+E3 @ 50%", false)
MixedFilamentRow(
    id = 1, components = listOf(1, 3), weights = listOf(50, 50),
    distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
    label = MixedFilamentRow.autoLabel(listOf(1, 3)), inLibrary = false,
)
```
Update any assertion expecting the old `"E1+E3 @ 50%"` label to the new list form `"E1+E3"`.

- [ ] **Step 5: Run both test classes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.u1.slicer.data.MixedFilamentRow*" --no-daemon`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/u1/slicer/data/MixedFilamentRow.kt app/src/test/java/com/u1/slicer/data/MixedFilamentRow*.kt
git commit -m "feat(M4): MixedFilamentRow becomes N-component (list SSOT + legacy accessors)"
```

---

## Task 3: `MixedFilamentManager` N-component add/edit + `serializeRow` tokens

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/data/MixedFilamentManager.kt`
- Test: `app/src/test/java/com/u1/slicer/data/MixedFilamentManagerTest.kt`

- [ ] **Step 1: Add failing tests**

```kotlin
@Test fun addN_threeComponents_serializesGradientTokens() {
    val mgr = MixedFilamentManager({ emptyList() }, { emptyList() }, {}, {})
    mgr.addN(
        components = listOf(1, 2, 3), weights = listOf(50, 30, 20),
        distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
    )
    val recipe = mgr.serialize(numPhysicalFilaments = 4)
    // Tokens populated: g<ids> and w<weights>.
    assert(recipe.contains(",g123,")) { "ids token missing in: $recipe" }
    assert(recipe.contains(",w50/30/20,")) { "weights token missing in: $recipe" }
    // Legacy a,b,mix_b_percent stay populated from components[0],[1],weights[1].
    assert(recipe.startsWith("1,2,1,1,30,")) { "legacy prefix wrong: $recipe" }
}

@Test fun addTwoWay_stillWorksViaOverload() {
    val mgr = MixedFilamentManager({ emptyList() }, { emptyList() }, {}, {})
    mgr.add(componentA = 1, componentB = 2, mixBPercent = 50,
        distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
    val recipe = mgr.serialize(4)
    assert(recipe.contains(",g12,")) { recipe }
    assert(recipe.contains(",w50/50,")) { recipe }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.u1.slicer.data.MixedFilamentManagerTest" --no-daemon`
Expected: FAIL — `addN` unresolved; recipe lacks tokens.

- [ ] **Step 3: Implement `addN`, refactor `add`/`edit`, populate `serializeRow`**

Replace the `add`, `edit`, and `serializeRow` members. Add `addN` and an `editN`; keep `add`/`edit` as thin 2-way overloads delegating to the N versions so existing callers compile.

```kotlin
fun addN(
    components: List<Int>,
    weights: List<Int>,
    distributionMode: MixedFilamentRow.MixDistributionMode,
): MixedFilamentRow {
    require(components.size in 2..4) { "2..4 components" }
    require(components.distinct().size == components.size) { "components must be distinct" }
    val w = MixWeights.normalize(weights)
    val row = MixedFilamentRow(
        id = nextId(), components = components, weights = w,
        distributionMode = distributionMode,
        label = MixedFilamentRow.autoLabel(components), inLibrary = false,
    )
    _projectMixes.value = _projectMixes.value + row
    saveProject(_projectMixes.value)
    return row
}

fun add(
    componentA: Int, componentB: Int, mixBPercent: Int,
    distributionMode: MixedFilamentRow.MixDistributionMode,
): MixedFilamentRow {
    require(componentA != componentB) { "componentA must differ from componentB" }
    require(mixBPercent in 0..100) { "mixBPercent must be 0..100" }
    return addN(listOf(componentA, componentB), listOf(100 - mixBPercent, mixBPercent), distributionMode)
}

fun editN(
    id: Long,
    components: List<Int>,
    weights: List<Int>,
    distributionMode: MixedFilamentRow.MixDistributionMode,
    label: String? = null,
) {
    require(components.size in 2..4) { "2..4 components" }
    require(components.distinct().size == components.size) { "components must be distinct" }
    val w = MixWeights.normalize(weights)
    fun patch(existing: MixedFilamentRow) =
        if (existing.id != id) existing
        else existing.copy(
            components = components, weights = w, distributionMode = distributionMode,
            label = label ?: MixedFilamentRow.autoLabel(components),
        )
    _projectMixes.value = _projectMixes.value.map(::patch)
    _libraryMixes.value = _libraryMixes.value.map(::patch)
    saveProject(_projectMixes.value)
    saveLibrary(_libraryMixes.value)
}

fun edit(
    id: Long, componentA: Int, componentB: Int, mixBPercent: Int,
    distributionMode: MixedFilamentRow.MixDistributionMode, label: String? = null,
) {
    require(componentA != componentB) { "componentA must differ from componentB" }
    require(mixBPercent in 0..100) { "mixBPercent must be 0..100" }
    editN(id, listOf(componentA, componentB), listOf(100 - mixBPercent, mixBPercent), distributionMode, label)
}
```

Replace `serializeRow` to populate the gradient tokens:

```kotlin
private fun serializeRow(r: MixedFilamentRow): String {
    val distMode = when (r.distributionMode) {
        MixedFilamentRow.MixDistributionMode.LAYER_CYCLE -> 0
        MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS -> 1
    }
    val ids = MixWeights.encodeIds(r.components)          // e.g. "123" or "1/12/3"
    val weights = MixWeights.encodeWeights(r.weights)     // e.g. "50/30/20"
    // a,b = first two components; mix_b_pct = weight of component B (legacy 2-way fallback fields).
    // gradient tokens g<ids>,w<weights> drive the N-way engine path when ids.size >= 3.
    return "${r.componentA},${r.componentB},1,1,${r.mixBPercent},0," +
        "g$ids,w$weights,m$distMode,z0,xa0,xb0,d0,o0,u${r.id}"
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.u1.slicer.data.MixedFilamentManagerTest" --no-daemon`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/data/MixedFilamentManager.kt app/src/test/java/com/u1/slicer/data/MixedFilamentManagerTest.kt
git commit -m "feat(M4): N-component add/edit + serializeRow emits g/w gradient tokens"
```

---

## Task 4: ENGINE GATE — 3- and 4-component slice blend (instrumented)

This is the spec §8 proof, modeled on the existing `MixSlotBlendVerificationTest`. **Run it before building any UI.** Requires a connected arm64 device with the bundled `.so`.

**Files:**
- Create: `app/src/androidTest/java/com/u1/slicer/slicing/MixSlotNWayBlendGateTest.kt`

- [ ] **Step 1: Write the gate test**

```kotlin
package com.u1.slicer.slicing

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.aipaint.AiRegion
import com.u1.slicer.aipaint.PaintedMeshWriter
import com.u1.slicer.data.MixedFilamentManager
import com.u1.slicer.data.MixedFilamentRow
import com.u1.slicer.data.SliceConfig
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * M4 HEADLINE GATE — proves a 3+ component mix ACTUALLY BLENDS at slice time using the
 * engine's weighted gradient sequence (not the 2-way a/b fallback). One tall box painted
 * entirely to a single 3-component mix slot (E1+E2+E3, weights 50/30/20). Asserts all three
 * component tools appear, the off-palette tool (T3/E4) never appears, and usage is roughly
 * proportional to the weights. A non-N-way result would use only T0/T1 (the a/b pair).
 * Do NOT weaken these assertions.
 */
@RunWith(AndroidJUnit4::class)
class MixSlotNWayBlendGateTest {
    private lateinit var lib: NativeLibrary
    private lateinit var out3mf: File

    @Before fun setup() {
        assertTrue("Native library must be loaded (arm64)", NativeLibrary.isLoaded)
        lib = NativeLibrary(); lib.clearModel()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        out3mf = File(ctx.cacheDir, "mix_nway_${System.currentTimeMillis()}.3mf")
    }
    @After fun teardown() { lib.clearModel(); out3mf.delete() }

    private fun box(w: Float, d: Float, h: Float): FloatArray {
        val x0=0f; val x1=w; val y0=0f; val y1=d; val z0=0f; val z1=h
        return floatArrayOf(
            x0,y0,z0, x1,y1,z0, x1,y0,z0,  x0,y0,z0, x0,y1,z0, x1,y1,z0,
            x0,y0,z1, x1,y0,z1, x1,y1,z1,  x0,y0,z1, x1,y1,z1, x0,y1,z1,
            x0,y0,z0, x1,y0,z0, x1,y0,z1,  x0,y0,z0, x1,y0,z1, x0,y0,z1,
            x0,y1,z0, x1,y1,z1, x1,y1,z0,  x0,y1,z0, x0,y1,z1, x1,y1,z1,
            x0,y0,z0, x0,y0,z1, x0,y1,z1,  x0,y0,z0, x0,y1,z1, x0,y1,z0,
            x1,y0,z0, x1,y1,z1, x1,y0,z1,  x1,y0,z0, x1,y1,z0, x1,y1,z1,
        )
    }

    private fun makeConfig(recipe: String) = SliceConfig(
        layerHeight = 0.2f, firstLayerHeight = 0.2f, perimeters = 2,
        topSolidLayers = 3, bottomSolidLayers = 3, fillDensity = 0.15f, fillPattern = "gyroid",
        printSpeed = 150f, travelSpeed = 200f, firstLayerSpeed = 50f,
        nozzleTemp = 220, bedTemp = 65, nozzleDiameter = 0.4f, filamentDiameter = 1.75f,
        retractLength = 0.8f, retractSpeed = 45f, extruderCount = 4,
        extruderTemps = IntArray(4) { 220 }, wipeTowerEnabled = false,
        mixedFilamentDefinitions = recipe,
    )

    @Test fun threeComponentMix_blendsAllThreeToolsByWeight() {
        val positions = box(12f, 12f, 10f)            // ~50 layers @ 0.2mm
        val triCount = positions.size / 9
        val regionIds = IntArray(triCount) { 4 }       // mix slot 4 -> paint state 5
        val regions = (0..3).map { s -> AiRegion(id = s, label = "Slot ${s+1}", suggestedColour = "#888888", slot = s) }
        val printerColours = listOf("#FF0000", "#00FF00", "#0000FF", "#FFFF00")
        PaintedMeshWriter.write(positions, regionIds, regions, out3mf, printerColours, listOf("#806633"))
        assertTrue("3MF written", out3mf.length() > 0)

        val mgr = MixedFilamentManager({ emptyList() }, { emptyList() }, {}, {})
        mgr.addN(listOf(1, 2, 3), listOf(50, 30, 20), MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
        val recipe = mgr.serialize(4)
        assertTrue("recipe non-empty", recipe.isNotEmpty())

        assertTrue("loadModel", lib.loadModel(out3mf.absolutePath))
        val result = lib.slice(makeConfig(recipe)); assertNotNull(result); result!!
        assertTrue("slice ok: '${result.errorMessage}'", result.success)

        val gcode = File(result.gcodePath).readText()
        val counts = IntArray(8)
        Regex("""^T(\d+)\b""").let { rx ->
            gcode.lineSequence().forEach { l -> rx.find(l.trim())?.let { val t = it.groupValues[1].toInt(); if (t in 0..7) counts[t]++ } }
        }
        val diag = "T0=${counts[0]} T1=${counts[1]} T2=${counts[2]} T3=${counts[3]}"
        Log.i("MixNWayGate", diag)

        assertTrue("GATE: component E1 (T0) must print. $diag", counts[0] > 0)
        assertTrue("GATE: component E2 (T1) must print. $diag", counts[1] > 0)
        assertTrue("GATE: component E3 (T2) must print — proves 3-way path, not a/b fallback. $diag", counts[2] > 0)
        assertTrue("GATE: uninvolved E4 (T3) must NOT print. $diag", counts[3] == 0)
        // Weight order 50/30/20 -> T0 used at least as much as T1, T1 at least as much as T2.
        assertTrue("GATE: usage should track weights (T0>=T1>=T2). $diag", counts[0] >= counts[1] && counts[1] >= counts[2])
    }
}
```

- [ ] **Step 2: Run the gate**

Run: `ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.slicing.MixSlotNWayBlendGateTest --no-daemon`
Expected: PASS. **If it fails with only T0/T1 used (T2==0):** the engine took the 2-way fallback — re-verify Task 3's `serializeRow` emits `g123,w50/30/20` and that `distMode` is `0` (not `2`/Simple). Per the spec §8 contingency, if blending is genuinely absent, STOP and report before proceeding to UI.

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/java/com/u1/slicer/slicing/MixSlotNWayBlendGateTest.kt
git commit -m "test(M4): engine gate — 3-component mix blends all three tools by weight"
```

---

## Task 5: JSON persistence — `components`/`weights` with legacy fallback

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/data/SettingsRepository.kt` (`encodeLibraryMixes`/`decodeLibraryMixes`, ~256-292)
- Modify: `app/src/main/java/com/u1/slicer/data/SessionState.kt` (mix write ~184-196, read ~267-281)
- Test: `app/src/test/java/com/u1/slicer/data/SettingsBackupTest.kt` (add cases) and `app/src/test/java/com/u1/slicer/data/SessionStateTest.kt` (add cases)

- [ ] **Step 1: Add failing round-trip + legacy tests**

In `SettingsBackupTest.kt`:
```kotlin
@Test fun libraryMixes_nway_roundTrip() {
    val row = MixedFilamentRow(99, listOf(1,2,3), listOf(50,30,20),
        MixedFilamentRow.MixDistributionMode.LAYER_CYCLE, "E1+E2+E3", true)
    val decoded = SettingsRepository.decodeLibraryMixes(SettingsRepository.encodeLibraryMixes(listOf(row)))
    org.junit.Assert.assertEquals(listOf(1,2,3), decoded[0].components)
    org.junit.Assert.assertEquals(listOf(50,30,20), decoded[0].weights)
}

@Test fun libraryMixes_legacyJson_migrates() {
    val legacy = """[{"id":5,"componentA":1,"componentB":2,"mixBPercent":30,
        "distributionMode":"LAYER_CYCLE","label":"E1+E2 @ 30%","inLibrary":true}]""".trimIndent()
    val decoded = SettingsRepository.decodeLibraryMixes(legacy)
    org.junit.Assert.assertEquals(listOf(1,2), decoded[0].components)
    org.junit.Assert.assertEquals(listOf(70,30), decoded[0].weights)
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.u1.slicer.data.SettingsBackupTest" --no-daemon`
Expected: FAIL — decode ignores `components`/`weights`.

- [ ] **Step 3: Update `encodeLibraryMixes` / `decodeLibraryMixes`**

In `SettingsRepository.kt`, replace the put-block in `encodeLibraryMixes` to add the canonical arrays (keep legacy keys for forward/back compat):
```kotlin
arr.put(org.json.JSONObject().apply {
    put("id", r.id)
    put("components", org.json.JSONArray(r.components))
    put("weights", org.json.JSONArray(r.weights))
    // legacy 2-way mirror so older builds still read these rows
    put("componentA", r.componentA)
    put("componentB", r.componentB)
    put("mixBPercent", r.mixBPercent)
    put("distributionMode", r.distributionMode.name)
    put("label", r.label)
    put("inLibrary", r.inLibrary)
})
```
Replace the row construction in `decodeLibraryMixes`:
```kotlin
val o = arr.getJSONObject(i)
val mode = MixedFilamentRow.MixDistributionMode.valueOf(o.getString("distributionMode"))
val comps = o.optJSONArray("components")
if (comps != null) {
    val weightsArr = o.getJSONArray("weights")
    MixedFilamentRow(
        id = o.getLong("id"),
        components = (0 until comps.length()).map { comps.getInt(it) },
        weights = (0 until weightsArr.length()).map { weightsArr.getInt(it) },
        distributionMode = mode, label = o.getString("label"), inLibrary = o.getBoolean("inLibrary"),
    )
} else {
    MixedFilamentRow.fromLegacy(
        id = o.getLong("id"), componentA = o.getInt("componentA"),
        componentB = o.getInt("componentB"), mixBPercent = o.getInt("mixBPercent"),
        distributionMode = mode, label = o.getString("label"), inLibrary = o.getBoolean("inLibrary"),
    )
}
```

- [ ] **Step 4: Mirror the same change in `SessionState.kt`**

Apply the identical add-arrays-on-write and prefer-arrays-else-legacy-on-read logic to the `projectMixes` writer (~184-196) and reader (~267-281). The reader currently builds `MixedFilamentRow(id=…, componentA=…, …)`; replace with the same `comps != null ? … : fromLegacy(...)` branch.

- [ ] **Step 5: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.u1.slicer.data.SettingsBackupTest" --tests "com.u1.slicer.data.SessionStateTest" --no-daemon`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/u1/slicer/data/SettingsRepository.kt app/src/main/java/com/u1/slicer/data/SessionState.kt app/src/test/java/com/u1/slicer/data/SettingsBackupTest.kt app/src/test/java/com/u1/slicer/data/SessionStateTest.kt
git commit -m "feat(M4): persist N-component mixes with legacy 2-way fallback"
```

---

## Task 6: Slot-id collision fix (#2)

Base mix slot ids on `maxOf(numPhysical, canonicalCount)` so a 3MF declaring >4 canonical filaments can't collide a mix onto a physical slot. The slot-id SSOT is `FilamentMixChipRow.mixSlotId`.

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/ui/FilamentMixChipRow.kt` (companion `mixSlotId`)
- Modify: `app/src/main/java/com/u1/slicer/ui/PartsPanel.kt` (mix offset `numPhysical + idx + 1`)
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` (`mixPhysicalBase`)
- Modify: `app/src/main/java/com/u1/slicer/ui/AiPaintTreeRow.kt` (`slot = numPhysical + idx`)
- Test: `app/src/test/java/com/u1/slicer/ui/MixSelectorAugmentationTest.kt` (add collision case)

- [ ] **Step 1: Add failing test**

```kotlin
@Test fun mixSlotId_basedOnMaxOfPhysicalAndCanonical_noCollision() {
    // 6 canonical filaments declared, 4 physical extruders. First mix must land at slot 6,
    // never 4 or 5 (which are real canonical filament slots).
    val base = maxOf(4, 6)
    org.junit.Assert.assertEquals(6, FilamentMixChipRow.mixSlotId(0, base))
    org.junit.Assert.assertEquals(7, FilamentMixChipRow.mixSlotId(1, base))
}
```

- [ ] **Step 2: Run to verify failure** (passes only if callers pass the right base — see below)

Run: `./gradlew :app:testDebugUnitTest --tests "com.u1.slicer.ui.MixSelectorAugmentationTest" --no-daemon`
Expected: PASS for the helper itself (the helper already adds `numPhysical + index`); the FIX is ensuring every CALLER passes `maxOf(numPhysical, canonicalCount)` as that base. The test documents the contract.

- [ ] **Step 3: Update the four call sites to pass `maxOf(numPhysical, canonicalCount)`**

Define the canonical count once where each site computes `numPhysical`. The canonical filament count is the size of the project's canonical filament list (the same list the preview palette uses). Grep each anchor and wrap:

- `SlicerViewModel.startSlicing` — `val mixPhysicalBase = if (anyMixAssigned) numPhysical else 0` → use `maxOf(numPhysical, canonicalFilamentCount)` in place of `numPhysical`.
- `PartsPanel.kt` `FilamentChooserDialog` — `val mixSlot1Based = numPhysical + idx + 1` → `maxOf(numPhysical, canonicalCount) + idx + 1`.
- `AiPaintTreeRow.kt` — `val slot = numPhysical + idx` → `maxOf(numPhysical, canonicalCount) + idx`.
- `FilamentMixChipRow` callers (after Task 8 adoption) pass the same base.

Where a site has no canonical count in scope, thread the already-available canonical filament list size (preview palette size) in; if genuinely unavailable, `numPhysical` remains the floor (no regression).

- [ ] **Step 4: Build + run the mix UI test suite**

Run: `./gradlew :app:testDebugUnitTest --tests "com.u1.slicer.ui.*" --tests "com.u1.slicer.data.Mix*" --no-daemon`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "fix(M4/#2): base mix slot ids on max(physical, canonical) to avoid slot collision"
```

---

## Task 7: N-colour preview blend (`ColourMatch`)

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/aipaint/ColourMatch.kt`
- Test: `app/src/test/java/com/u1/slicer/aipaint/ColourMatchTest.kt` (create if absent, else extend)

- [ ] **Step 1: Add failing test**

```kotlin
@Test fun naiveBlendHexMulti_weightedAverage() {
    // Equal black+white -> mid grey.
    org.junit.Assert.assertEquals("#808080",
        ColourMatch.naiveBlendHexMulti(listOf("#000000", "#FFFFFF"), listOf(50, 50)))
    // Pure red dominant.
    val out = ColourMatch.naiveBlendHexMulti(listOf("#FF0000", "#00FF00", "#0000FF"), listOf(80, 10, 10))
    org.junit.Assert.assertEquals('#', out[0])
    // 2-colour case must match the legacy naiveBlendHex(a,b,pB).
    org.junit.Assert.assertEquals(
        ColourMatch.naiveBlendHex("#112233", "#445566", 30),
        ColourMatch.naiveBlendHexMulti(listOf("#112233", "#445566"), listOf(70, 30)))
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.u1.slicer.aipaint.ColourMatchTest" --no-daemon`
Expected: FAIL — `naiveBlendHexMulti` unresolved.

- [ ] **Step 3: Implement**

Add to the `ColourMatch` object:
```kotlin
/** Weighted sRGB average of N hex colours. [weights] need not sum to 100 (normalized here). */
fun naiveBlendHexMulti(colours: List<String>, weights: List<Int>): String {
    require(colours.size == weights.size && colours.isNotEmpty())
    val total = weights.sumOf { it.coerceAtLeast(0) }.coerceAtLeast(1).toDouble()
    var r = 0.0; var g = 0.0; var b = 0.0
    colours.forEachIndexed { i, hex ->
        val (cr, cg, cb) = parse(hex)
        val t = weights[i].coerceAtLeast(0) / total
        r += cr * t; g += cg * t; b += cb * t
    }
    return fmt(Math.round(r).toInt(), Math.round(g).toInt(), Math.round(b).toInt())
}
```

- [ ] **Step 4: Run tests** → Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/aipaint/ColourMatch.kt app/src/test/java/com/u1/slicer/aipaint/ColourMatchTest.kt
git commit -m "feat(M4): N-colour weighted preview blend (naiveBlendHexMulti)"
```

---

## Task 8: N-segment `MixedSlotSwatch` + call sites

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/ui/MixedSlotSwatch.kt`
- Test: `app/src/test/java/com/u1/slicer/ui/MixedSlotSwatchTest.kt` (create — structural/segment-fraction math)

- [ ] **Step 1: Add failing test for the segment-fraction helper**

```kotlin
package com.u1.slicer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MixedSlotSwatchTest {
    @Test fun segmentFractions_cumulativeOffsets() {
        // weights 50/30/20 -> segment widths as fractions, left offsets 0,.5,.8
        val offs = mixSegmentOffsets(listOf(50, 30, 20))
        assertEquals(listOf(0f, 0.5f, 0.8f), offs.map { it.first })
        assertEquals(listOf(0.5f, 0.3f, 0.2f), offs.map { it.second })
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.u1.slicer.ui.MixedSlotSwatchTest" --no-daemon`
Expected: FAIL — `mixSegmentOffsets` unresolved.

- [ ] **Step 3: Add an N-segment overload + the pure offset helper**

In `MixedSlotSwatch.kt`, add a top-level helper and a new composable overload (keep the existing 2-arg composable for the corner-stripe "multiple slots" case):
```kotlin
/** (leftOffsetFraction, widthFraction) per segment from integer weights. */
fun mixSegmentOffsets(weights: List<Int>): List<Pair<Float, Float>> {
    val total = weights.sum().coerceAtLeast(1).toFloat()
    var acc = 0f
    return weights.map { w ->
        val frac = w / total
        val pair = acc to frac
        acc += frac
        pair
    }
}

@Composable
fun MixedSlotSwatch(
    colours: List<Color>,         // one per component, in order
    weights: List<Int>,
    size: Dp = 36.dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(size).clip(MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val w = this.size.width; val h = this.size.height
            mixSegmentOffsets(weights).forEachIndexed { i, (off, frac) ->
                drawRect(color = colours.getOrElse(i) { Color.Gray },
                    topLeft = Offset(w * off, 0f), size = Size(w * frac, h))
            }
        }
    }
}
```

- [ ] **Step 4: Point mix call sites at the N-segment overload**

Update `CreateMixSlotDialog` preview (Task 9), `FilamentMixChipRow.MixChip` (Task 10), `PartsPanel` mix rows, and `AiPaintResultScreen`/`AiPaintTreeRow` mix chips to call `MixedSlotSwatch(colours = row.components.map { palette[it-1] }, weights = row.weights, …)`. (Each is done in its own task below; this task only adds the overload + helper and migrates `PartsPanel`'s mix rows as the first consumer.)

- [ ] **Step 5: Run tests + build**

Run: `./gradlew :app:testDebugUnitTest --tests "com.u1.slicer.ui.MixedSlotSwatchTest" --no-daemon && ./gradlew :app:assembleDebug --no-daemon`
Expected: PASS + build succeeds.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(M4): N-segment MixedSlotSwatch overload + PartsPanel mix rows"
```

---

## Task 9: Create/edit dialog — drag-bar + tap-to-type

The math is all in `MixWeights`; the dialog is a thin view over `components`/`weights` state.

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/ui/CreateMixSlotDialog.kt`
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` + the dialog call sites (NavGraph / AiPaintResultScreen) to pass the N-way confirm callback.
- Test: `app/src/test/java/com/u1/slicer/ui/CreateMixSlotDialogLogicTest.kt` (create — exercises the state-holder, not Compose UI)

- [ ] **Step 1: Add failing logic test for the dialog state holder**

```kotlin
package com.u1.slicer.ui

import com.u1.slicer.data.MixWeights
import org.junit.Assert.assertEquals
import org.junit.Test

class CreateMixSlotDialogLogicTest {
    @Test fun addComponent_thenTypeWeight_keepsSumHundred() {
        var comps = listOf(1, 2); var weights = listOf(50, 50)
        // add E3
        comps = comps + 3; weights = MixWeights.addEven(weights)
        assertEquals(3, comps.size); assertEquals(100, weights.sum())
        // type 60 on first
        weights = MixWeights.rebalanceAfterType(weights, 0, 60)
        assertEquals(60, weights[0]); assertEquals(100, weights.sum())
    }

    @Test fun removeComponent_floorOfTwo() {
        val comps = listOf(1, 2, 3); val weights = listOf(40, 40, 20)
        val (c2, w2) = removeMixComponent(comps, weights, index = 2)
        assertEquals(listOf(1, 2), c2); assertEquals(100, w2.sum())
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.u1.slicer.ui.CreateMixSlotDialogLogicTest" --no-daemon`
Expected: FAIL — `removeMixComponent` unresolved.

- [ ] **Step 3: Add the pure helper + rework the dialog**

Add the helper near the top of `CreateMixSlotDialog.kt`:
```kotlin
/** Remove the component at [index] (no-op below 3 components) and renormalize weights. */
fun removeMixComponent(components: List<Int>, weights: List<Int>, index: Int): Pair<List<Int>, List<Int>> {
    if (components.size <= 2) return components to weights
    val c = components.filterIndexed { i, _ -> i != index }
    val w = MixWeights.remove(weights, index)
    return c to w
}
```

Rework the composable signature + body. The confirm callback now carries the lists:
```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateMixSlotDialog(
    physicalFilamentColours: List<Color>,
    physicalFilamentLabels: List<String>,
    editingRow: MixedFilamentRow? = null,
    onConfirmN: (components: List<Int>, weights: List<Int>,
        distributionMode: MixedFilamentRow.MixDistributionMode) -> Unit,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val maxComponents = minOf(4, physicalFilamentColours.size)
    var components by remember {
        mutableStateOf(editingRow?.components ?: listOf(1, 2.coerceAtMost(physicalFilamentColours.size)))
    }
    var weights by remember { mutableStateOf(editingRow?.weights ?: MixWeights.even(components.size)) }
    var typingIndex by remember { mutableStateOf(-1) }
    var typingText by remember { mutableStateOf("") }
    val isEditing = editingRow != null
    val canConfirm = components.distinct().size == components.size && components.size >= 2

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEditing) "Edit Mix" else "Create Mix") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // --- Proportional draggable bar ---
                MixWeightBar(
                    colours = components.map { physicalFilamentColours.getOrNull(it - 1) ?: Color.Gray },
                    weights = weights,
                    onDragDivider = { leftIndex, newLeft ->
                        weights = MixWeights.rebalanceAfterDrag(weights, leftIndex, newLeft)
                    },
                )
                // --- Per-component rows: chip (change filament) + % (tap to type) + remove ---
                components.forEachIndexed { idx, slot ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // filament chooser chip (cycles to next unused physical slot on tap)
                        FilamentChip(
                            colour = physicalFilamentColours.getOrNull(slot - 1) ?: Color.Gray,
                            label = physicalFilamentLabels.getOrNull(slot - 1) ?: "E$slot",
                            selected = false,
                            onClick = {
                                val used = components.toSet()
                                val next = (1..physicalFilamentColours.size).firstOrNull { it !in used || it == slot }
                                if (next != null) components = components.toMutableList().also { it[idx] = next }
                            },
                        )
                        Spacer(Modifier.weight(1f))
                        if (typingIndex == idx) {
                            OutlinedTextField(
                                value = typingText, onValueChange = { typingText = it.filter(Char::isDigit).take(3) },
                                singleLine = true, modifier = Modifier.width(72.dp),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                                    weights = MixWeights.rebalanceAfterType(weights, idx, typingText.toIntOrNull() ?: weights[idx])
                                    typingIndex = -1
                                }),
                            )
                        } else {
                            TextButton(onClick = { typingIndex = idx; typingText = weights[idx].toString() }) {
                                Text("${weights[idx]}%")
                            }
                        }
                        if (components.size > 2) {
                            TextButton(onClick = {
                                val (c, w) = removeMixComponent(components, weights, idx)
                                components = c; weights = w; typingIndex = -1
                            }) { Text("✕") }
                        }
                    }
                }
                // --- Add component ---
                if (components.size < maxComponents) {
                    TextButton(onClick = {
                        val used = components.toSet()
                        val next = (1..physicalFilamentColours.size).firstOrNull { it !in used } ?: return@TextButton
                        weights = MixWeights.addEven(weights); components = components + next
                    }) { Text("+ Add colour") }
                }
                // --- Distribution mode (unchanged) ---
                var distributionMode by remember {
                    mutableStateOf(editingRow?.distributionMode ?: MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DistributionChip("Layer alternation",
                        distributionMode == MixedFilamentRow.MixDistributionMode.LAYER_CYCLE) {
                        distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE }
                    DistributionChip("Same-layer dots",
                        distributionMode == MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS) {
                        distributionMode = MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS }
                }
                // confirm needs distributionMode in scope — hoist it above the Column in the real edit.
            }
        },
        confirmButton = {
            TextButton(enabled = canConfirm, onClick = {
                onConfirmN(components, weights, MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
                onDismiss()
            }) { Text(if (isEditing) "Save" else "Create") }
        },
        dismissButton = {
            if (isEditing && onDelete != null)
                TextButton(onClick = { onDelete(); onDismiss() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
            else TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
```

> **Implementation note for the engineer:** hoist `distributionMode` to the top of the composable (above the `text` lambda) so the confirm button passes the live value rather than the literal `LAYER_CYCLE` shown above. Then add the `MixWeightBar` composable: a `Canvas` row of segments using `mixSegmentOffsets(weights)` for widths, with `detectHorizontalDragGestures` mapping the dragged divider's x to a new left-weight via `(x / width * 100).toInt()` and calling `onDragDivider(leftIndex, newLeft)`. Identify which divider is grabbed by nearest cumulative offset. Keep all arithmetic delegated to `MixWeights` — the canvas only converts pixels↔percent.

- [ ] **Step 4: Update dialog call sites for the new `onConfirmN` signature**

In `SlicerViewModel`, add `createMixN(components, weights, mode)` → `mixedFilamentManager.addN(...)` and `editMixN(id, components, weights, mode)` → `editN(...)`. Wire `NavGraph`/`AiPaintResultScreen` `CreateMixSlotDialog(...)` usages to call these via `onConfirmN`, replacing the old `onConfirm(a,b,pB,mode)`.

- [ ] **Step 5: Run logic tests + build**

Run: `./gradlew :app:testDebugUnitTest --tests "com.u1.slicer.ui.CreateMixSlotDialogLogicTest" --no-daemon && ./gradlew :app:assembleDebug --no-daemon`
Expected: PASS + build succeeds.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(M4): N-component create/edit dialog (drag-bar + tap-to-type)"
```

---

## Task 10: Adopt/retire `FilamentMixChipRow` (#3) + remaining swatch sites

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/ui/FilamentMixChipRow.kt`
- Modify: any per-surface inline chip code the exploration found duplicating it.

- [ ] **Step 1: Update `MixChip` rendering to N-segments**

In `FilamentMixChipRow.kt`, change the mix-chip block (lines ~85-98) to build colour+weight lists and call the N-segment swatch:
```kotlin
mixes.forEachIndexed { idx, row ->
    val slotId = FilamentMixChipRow.mixSlotId(idx, numPhysical)
    val selected = selectedSlot == slotId
    val colours = row.components.map { physicalColours.getOrNull(it - 1) ?: Color.Gray }
    MixChipN(colours = colours, weights = row.weights, selected = selected,
        onClick = { onSelect(slotId) }, onLongClick = { onEditMix(row) })
}
```
Add a `MixChipN` private composable mirroring `MixChip` but rendering `MixedSlotSwatch(colours, weights, …)`. Delete the old 2-tone `MixChip` once unused.

- [ ] **Step 2: Decide adopt-vs-delete**

If the per-surface selectors (PartsPanel / AiPaint) can use `FilamentMixChipRow` directly, replace their inline chip code with a call to it and delete the duplication. If their layouts diverge materially, delete `FilamentMixChipRow` (composable) and keep only the `object` companion (slot-id SSOT). Either outcome: no uncalled composable remains. Record the choice in the commit message.

- [ ] **Step 3: Build + run UI tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.u1.slicer.ui.*" --no-daemon && ./gradlew :app:assembleDebug --no-daemon`
Expected: PASS + build succeeds.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor(M4/#3): N-segment mix chips; resolve FilamentMixChipRow orphan"
```

---

## Task 11: Smart Paint mix-edit wiring (#4/#5)

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/ui/AiPaintTreeRow.kt` (long-press a mix chip)
- Modify: `app/src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt` (tap a mix-leaf's swatch)
- Test: `app/src/test/java/com/u1/slicer/ui/MixSelectorAugmentationTest.kt` (add structural guard)

- [ ] **Step 1: Add failing structural guard**

```kotlin
@Test fun smartPaint_mixChips_wireOnEditMix() {
    val src = java.io.File("src/main/java/com/u1/slicer/ui/AiPaintTreeRow.kt").readText()
    assert(src.contains("onLongClick") && src.contains("onEditMix")) {
        "AiPaintTreeRow mix chip must wire long-press to onEditMix"
    }
}
```
(Project convention: source-grep structural guards where no Compose UI harness exists — see `ModelInfoDialogScrollTest`.)

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.u1.slicer.ui.MixSelectorAugmentationTest" --no-daemon`
Expected: FAIL — `onLongClick` absent on the tree mix chip.

- [ ] **Step 3: Wire the dead `onEditMix`**

In `AiPaintTreeRow.kt`, give the mix-chip `Box` a `combinedClickable(onClick = { onPickSlot(slot) }, onLongClick = { activeMixes.getOrNull(idx)?.let { onEditMix(it) } })` (mirroring `FilamentMixChipRow.MixChip`). Ensure `onEditMix` is threaded through the row's params (it already is per exploration). In `AiPaintResultScreen.kt`, the non-paint-mode mix-leaf tap already routes `slot >= numPhysical → onEditMix(...)` — confirm the leading swatch tap reaches that branch (not a no-op); if the swatch has its own `clickable`, route it to the same `onEditMix`.

- [ ] **Step 4: Run guard + build** → Expected: PASS + build.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "fix(M4/#4#5): Smart Paint long-press + swatch-tap open the mix editor"
```

---

## Task 12: 4-component slice gate + full verification sweep

**Files:**
- Modify: `app/src/androidTest/java/com/u1/slicer/slicing/MixSlotNWayBlendGateTest.kt` (add 4-component case)

- [ ] **Step 1: Add 4-component test method**

```kotlin
@Test fun fourComponentMix_blendsAllFourTools() {
    val positions = box(12f, 12f, 12f)
    val triCount = positions.size / 9
    val regionIds = IntArray(triCount) { 4 }
    val regions = (0..3).map { s -> AiRegion(id = s, label = "Slot ${s+1}", suggestedColour = "#888888", slot = s) }
    PaintedMeshWriter.write(positions, regionIds, regions, out3mf,
        listOf("#FF0000", "#00FF00", "#0000FF", "#FFFF00"), listOf("#7F7F40"))
    val mgr = MixedFilamentManager({ emptyList() }, { emptyList() }, {}, {})
    mgr.addN(listOf(1, 2, 3, 4), listOf(40, 30, 20, 10), MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
    assertTrue(lib.loadModel(out3mf.absolutePath))
    val result = lib.slice(makeConfig(mgr.serialize(4))); assertNotNull(result); result!!
    assertTrue("slice ok: '${result.errorMessage}'", result.success)
    val gcode = File(result.gcodePath).readText()
    val counts = IntArray(8)
    Regex("""^T(\d+)\b""").let { rx -> gcode.lineSequence().forEach { l -> rx.find(l.trim())?.let { val t = it.groupValues[1].toInt(); if (t in 0..7) counts[t]++ } } }
    val diag = "T0=${counts[0]} T1=${counts[1]} T2=${counts[2]} T3=${counts[3]}"
    assertTrue("GATE: all four component tools must print. $diag",
        counts[0] > 0 && counts[1] > 0 && counts[2] > 0 && counts[3] > 0)
}
```

- [ ] **Step 2: Run the full JVM unit suite + the gate on device**

Run:
```bash
./gradlew :app:testDebugUnitTest --no-daemon
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.slicing.MixSlotNWayBlendGateTest --no-daemon
```
Expected: all JVM unit tests PASS; both gate methods PASS.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "test(M4): 4-component slice gate; full unit suite green"
```

---

## Task 13: Backlog/docs + confidence check

- [ ] **Step 1: BACKLOG + GitHub parity**

Add M4 (N-way mixing) and M5 (auto-generate mix palette — future) entries to `BACKLOG.md` with matching GitHub issues (`(GitHub #N)` in the heading). Per CLAUDE.md, BACKLOG ↔ issue parity is required.

- [ ] **Step 2: Update test counts**

Update the unit/instrumented test counts in `CLAUDE.md` and `README.md` to reflect the new test classes/methods.

- [ ] **Step 3: Run the confidence check**

Invoke the `u1-slicer-confidence-check` skill (unit + smoke-10 instrumented + E2E smoke-7). **No physical prints** — upload-only if a send path is exercised.

- [ ] **Step 4: Commit**

```bash
git add BACKLOG.md CLAUDE.md README.md
git commit -m "docs(M4): backlog/issue parity + test counts"
```

---

## Self-Review (completed against spec)

- **Spec §3 (no native change):** verified directly in code — Task 3 emits `g`/`w`; Task 4 proves the engine path. ✓
- **Spec §4 (data model + migration):** Tasks 2 (row), 3 (manager+serialize), 5 (JSON legacy fallback). ✓
- **Spec §5 (dialog):** Task 9 (drag-bar + tap-to-type, add/remove, weights via `MixWeights`). ✓
- **Spec §6 (#2 slot-id, #3 chip row, #4/#5 Smart Paint):** Tasks 6, 10, 11. ✓
- **Spec §7 (swatch + preview):** Tasks 7 (blend), 8 (swatch). ✓
- **Spec §8 (verification gate + contingency):** Task 4 (3-comp), Task 12 (4-comp + full sweep); contingency note carried into Task 4 Step 2. ✓
- **Spec §9 (M5 future):** Task 13 BACKLOG entry. ✓
- **Type consistency:** `addN`/`editN`/`onConfirmN`/`naiveBlendHexMulti`/`mixSegmentOffsets`/`removeMixComponent`/`MixWeights.*` used consistently across tasks; `components: List<Int>` + `weights: List<Int>` are the canonical names throughout. ✓
- **Known soft spots (flagged, not placeholders):** the `MixWeightBar` canvas gesture code (Task 9) is described precisely but is the one piece requiring in-editor iteration on touch; the slot-id `canonicalCount` source (Task 6) must be threaded from each call site's existing canonical-filament list — `numPhysical` remains a safe floor if unavailable.
