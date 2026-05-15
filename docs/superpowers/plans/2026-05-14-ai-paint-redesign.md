# F54 AI Paint Redesign — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the topology+AI-grouping AI Paint pipeline with a 6-branch cascade that reads the model's own structure (paint state → volumes → objects → topology+recursion → Z-bands), surfaced as an expandable tree with cascade-reassign and AI as opt-in naming-only enrichment.

**Architecture:** Pure-function branches (A–F) each accept the loaded native snapshot + topology results and return `(triangleSegments, AiRegionNode tree, SegmentationSource)`. `SegmentationCascade` orchestrates branch-firing top-down. Tree rendered by `AiPaintTree` (Compose) with cascade-reassign on parent slot chips, diagonal-stripe mixed-parent swatches, and brush strokes appended to a root-level "Custom selections" group. AI naming is a post-pass on tree leaves gated by `aiNamingEnabled` (default false, Experimental in Settings).

**Tech Stack:** Kotlin 1.9.22, Jetpack Compose, Material3, JNI native accessors (already exist), existing OkHttp `AiLabelClient`.

**Working directory:** `d:/projects/u1-slicer-orca/.worktrees/f54-ai-paint/` (the f54-ai-paint worktree). All paths in this plan are relative to that root unless absolute.

**Reference spec:** [`docs/superpowers/specs/2026-05-14-ai-paint-redesign-design.md`](../specs/2026-05-14-ai-paint-redesign-design.md). When the plan says "see spec §N", it's referring to that file.

**Test patterns:**
- JVM unit tests: `./gradlew testDebugUnitTest --tests com.u1.slicer.aipaint.<ClassName> --no-daemon`
- Single method: `./gradlew testDebugUnitTest --tests com.u1.slicer.aipaint.<ClassName>.<methodName> --no-daemon`
- Instrumented: `ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.aipaint.<ClassName> --no-daemon`
- Full build: `./gradlew assembleDebug --no-daemon`

---

## File Structure

### Create

| File | Responsibility |
|---|---|
| `app/src/main/java/com/u1/slicer/aipaint/SegmentationSource.kt` | Enum tagging the origin of every region (`PAINT_STATE`, `VOLUME`, `OBJECT`, `TRIANGLE_INDEX`, `TOPOLOGY`, `TOPOLOGY_RECURSIVE`, `Z_BAND`, `BRUSH`). |
| `app/src/main/java/com/u1/slicer/aipaint/AiRegionNode.kt` | Tree node data class + flatten/visit helpers + tree builder for cascade results. |
| `app/src/main/java/com/u1/slicer/aipaint/SegmentationCascade.kt` | Orchestrates branches A–F. Private branch functions (paintStateBranch / volumeBranch / etc.) live alongside since they share helpers. |
| `app/src/main/java/com/u1/slicer/aipaint/TopologyRecursion.kt` | Spatial K-means subdivision of the dominant component (> 60% triangle threshold). |
| `app/src/main/java/com/u1/slicer/aipaint/CustomSelections.kt` | Side-channel data model + builder for the root-level "Custom selections" tree group. |
| `app/src/main/java/com/u1/slicer/ui/AiPaintTree.kt` | Top-level LazyColumn over a flattened tree with expand/collapse + auto-expand/collapse rules. |
| `app/src/main/java/com/u1/slicer/ui/AiPaintTreeRow.kt` | Single tree row (chevron · swatch · label · % · slot chips). |
| `app/src/main/java/com/u1/slicer/ui/MixedSlotSwatch.kt` | Compose Canvas drawing a diagonal-stripe two-tone swatch for mixed-parent rows. |
| `app/src/test/java/com/u1/slicer/aipaint/SegmentationSourceTest.kt` | Enum value sanity. |
| `app/src/test/java/com/u1/slicer/aipaint/AiRegionNodeTest.kt` | Tree shape, depth caps, flatten ordering. |
| `app/src/test/java/com/u1/slicer/aipaint/SegmentationCascadeTest.kt` | One test per branch precondition using fake inputs. |
| `app/src/test/java/com/u1/slicer/aipaint/TopologyRecursionTest.kt` | Dominant detection + sub-region counts. |
| `app/src/test/java/com/u1/slicer/aipaint/CustomSelectionsTest.kt` | Append, clear, "Custom selections" parent existence. |
| `app/src/androidTest/java/com/u1/slicer/aipaint/SegmentationCascadeIntegrationTest.kt` | One `@Test` per fixture (Benchy STL, colored_3DBenchy, Dragon Scale plate 3, multi-object Bambu, H2C Benchy, cat pot/simple shell). |

### Modify

| File | Change |
|---|---|
| `app/src/main/java/com/u1/slicer/aipaint/AiPaintViewModel.kt` | Pipeline gutted: `runPipelineInternal` calls `SegmentationCascade.run(...)`. Old `runTopologyGroupingPath` removed; `runZBandPath` removed (folded into branch F). State exposes `tree`, `source`, `aiNamingFailed`, `aiModelTried`, `customSelections`. `setSegmentSlot` / `moveComponent` / `paintTriangles` / `commitSelection` updated to work against tree + custom selections. |
| `app/src/main/java/com/u1/slicer/aipaint/AiRegion.kt` | `AiPaintResultState` field overhaul: add `tree: List<AiRegionNode>`, `source: SegmentationSource`, `aiNamingFailed: Boolean`, `aiModelTried: String?`, `customSelections: List<CustomSelection>`; remove `regions: List<AiRegion>` (consumers walk tree). |
| `app/src/main/java/com/u1/slicer/aipaint/AiLabelClient.kt` | Drop `buildGroupPrompt`, `labelGroups`, `parseGroupJson`, `componentDisplayColors`, `hsvToArgb`. Keep `labelSegments` (now called from cascade post-pass on tree leaves). |
| `app/src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt` | Replace the flat `LazyColumn(items(result.regions))` block with `AiPaintTree(...)`. Wire AI failure chip in header. Existing paint/lasso/swatch UI untouched. |
| `app/src/main/java/com/u1/slicer/ui/SettingsScreen.kt` | Add "AI naming (experimental)" toggle in the AI Paint section. |
| `app/src/main/java/com/u1/slicer/data/SettingsRepository.kt` | Add `aiNamingEnabled: Flow<Boolean>` + setter; default false. |
| `app/src/main/java/com/u1/slicer/navigation/NavGraph.kt` | Wire the new cascade-reassign / brush-stroke / custom-selections callbacks if the screen signature changes. |

### Delete

| File | Reason |
|---|---|
| `app/src/test/java/com/u1/slicer/aipaint/AiLabelClientTest.kt` `buildGroupPrompt` / `parseGroupJson` / `componentDisplayColors` tests | Source methods removed. (Keep the file; trim its contents.) |

---

## Task 1: Add `SegmentationSource` enum

**Files:**
- Create: `app/src/main/java/com/u1/slicer/aipaint/SegmentationSource.kt`
- Create: `app/src/test/java/com/u1/slicer/aipaint/SegmentationSourceTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/java/com/u1/slicer/aipaint/SegmentationSourceTest.kt
package com.u1.slicer.aipaint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentationSourceTest {
    @Test
    fun `all expected sources are defined`() {
        val names = SegmentationSource.entries.map { it.name }.toSet()
        assertTrue(names.containsAll(setOf(
            "PAINT_STATE", "VOLUME", "OBJECT", "TRIANGLE_INDEX",
            "TOPOLOGY", "TOPOLOGY_RECURSIVE", "Z_BAND", "BRUSH",
        )))
    }

    @Test
    fun `displayLabel returns a human-readable string`() {
        assertEquals("Painted", SegmentationSource.PAINT_STATE.displayLabel)
        assertEquals("Per-volume", SegmentationSource.VOLUME.displayLabel)
        assertEquals("Per-object", SegmentationSource.OBJECT.displayLabel)
        assertEquals("Height bands", SegmentationSource.Z_BAND.displayLabel)
    }
}
```

- [ ] **Step 2: Run test, verify fails**

`./gradlew testDebugUnitTest --tests com.u1.slicer.aipaint.SegmentationSourceTest --no-daemon`
Expected: FAIL — `SegmentationSource` unresolved.

- [ ] **Step 3: Implement the enum**

```kotlin
// app/src/main/java/com/u1/slicer/aipaint/SegmentationSource.kt
package com.u1.slicer.aipaint

/**
 * Tags every region with the cascade branch that produced it. Surfaced in diagnostics and
 * (later) UI hints — also used by tests to assert which branch fired on which fixture.
 */
enum class SegmentationSource(val displayLabel: String) {
    PAINT_STATE("Painted"),
    VOLUME("Per-volume"),
    OBJECT("Per-object"),
    TRIANGLE_INDEX("Triangle indices"),
    TOPOLOGY("Topology"),
    TOPOLOGY_RECURSIVE("Topology + sub-regions"),
    Z_BAND("Height bands"),
    BRUSH("Brush stroke"),
}
```

- [ ] **Step 4: Run test, verify passes**

Same command as Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/aipaint/SegmentationSource.kt \
        app/src/test/java/com/u1/slicer/aipaint/SegmentationSourceTest.kt
git commit -m "F54 plan task 1: SegmentationSource enum"
```

---

## Task 2: Add `AiRegionNode` tree data model

**Files:**
- Create: `app/src/main/java/com/u1/slicer/aipaint/AiRegionNode.kt`
- Create: `app/src/test/java/com/u1/slicer/aipaint/AiRegionNodeTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/java/com/u1/slicer/aipaint/AiRegionNodeTest.kt
package com.u1.slicer.aipaint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRegionNodeTest {

    private fun leaf(id: Int, slot: Int = id % 4, label: String = "Leaf $id", triangleIds: IntArray = intArrayOf(id)): AiRegionNode =
        AiRegionNode(
            region = AiRegion(id = id, label = label, suggestedColour = "#888888", slot = slot),
            children = emptyList(),
            nodeSource = SegmentationSource.OBJECT,
            triangleIds = triangleIds,
        )

    @Test
    fun `flatten visits root then children in order`() {
        val root = AiRegionNode(
            region = AiRegion(id = 0, label = "Root", suggestedColour = "#000000"),
            children = listOf(leaf(1), leaf(2), leaf(3)),
            nodeSource = SegmentationSource.OBJECT,
            triangleIds = intArrayOf(1, 2, 3),
        )
        val flat = root.flatten()
        assertEquals(listOf("Root", "Leaf 1", "Leaf 2", "Leaf 3"), flat.map { it.region.label })
    }

    @Test
    fun `flatten respects depth limit`() {
        val deep = AiRegionNode(
            region = AiRegion(id = 0, label = "L0", suggestedColour = "#000000"),
            children = listOf(AiRegionNode(
                region = AiRegion(id = 1, label = "L1", suggestedColour = "#111111"),
                children = listOf(AiRegionNode(
                    region = AiRegion(id = 2, label = "L2", suggestedColour = "#222222"),
                    children = listOf(leaf(3, label = "L3")),
                    nodeSource = SegmentationSource.VOLUME,
                    triangleIds = intArrayOf(3),
                )),
                nodeSource = SegmentationSource.OBJECT,
                triangleIds = intArrayOf(3),
            )),
            nodeSource = SegmentationSource.OBJECT,
            triangleIds = intArrayOf(3),
        )
        val depths = deep.flatten().map { (_, depth) -> depth }
        assertEquals(listOf(0, 1, 2, 3), depths)
    }

    @Test
    fun `leafCount counts only nodes without children`() {
        val root = AiRegionNode(
            region = AiRegion(id = 0, label = "Root", suggestedColour = "#000000"),
            children = listOf(leaf(1), leaf(2), AiRegionNode(
                region = AiRegion(id = 3, label = "Inner", suggestedColour = "#333333"),
                children = listOf(leaf(4), leaf(5)),
                nodeSource = SegmentationSource.OBJECT,
                triangleIds = intArrayOf(4, 5),
            )),
            nodeSource = SegmentationSource.OBJECT,
            triangleIds = intArrayOf(1, 2, 4, 5),
        )
        assertEquals(4, root.leafCount())
    }

    @Test
    fun `dominantSlot returns the slot with the most triangles in children`() {
        val root = AiRegionNode(
            region = AiRegion(id = 0, label = "Root", suggestedColour = "#000000"),
            children = listOf(
                leaf(1, slot = 0, triangleIds = IntArray(100) { it }),
                leaf(2, slot = 1, triangleIds = IntArray(50) { it + 100 }),
                leaf(3, slot = 0, triangleIds = IntArray(25) { it + 150 }),
            ),
            nodeSource = SegmentationSource.OBJECT,
            triangleIds = IntArray(175) { it },
        )
        // slot 0 has 100+25 = 125 triangles, slot 1 has 50. Dominant = 0.
        assertEquals(0, root.dominantSlot())
    }

    @Test
    fun `secondarySlot returns the slot with the second-most triangles, null if pure`() {
        val mixed = AiRegionNode(
            region = AiRegion(id = 0, label = "Root", suggestedColour = "#000000"),
            children = listOf(
                leaf(1, slot = 0, triangleIds = IntArray(100) { it }),
                leaf(2, slot = 1, triangleIds = IntArray(50) { it + 100 }),
            ),
            nodeSource = SegmentationSource.OBJECT,
            triangleIds = IntArray(150) { it },
        )
        assertEquals(1, mixed.secondarySlot())

        val pure = AiRegionNode(
            region = AiRegion(id = 0, label = "Root", suggestedColour = "#000000"),
            children = listOf(leaf(1, slot = 2, triangleIds = IntArray(100) { it })),
            nodeSource = SegmentationSource.OBJECT,
            triangleIds = IntArray(100) { it },
        )
        assertTrue(pure.secondarySlot() == null)
    }
}
```

- [ ] **Step 2: Run test, verify fails**

`./gradlew testDebugUnitTest --tests com.u1.slicer.aipaint.AiRegionNodeTest --no-daemon`
Expected: FAIL — `AiRegionNode` unresolved.

- [ ] **Step 3: Implement the data class + helpers**

```kotlin
// app/src/main/java/com/u1/slicer/aipaint/AiRegionNode.kt
package com.u1.slicer.aipaint

/**
 * One row in the segmentation tree. Carries an [AiRegion] (label/colour/slot/coverage),
 * optional children for nested groups, the [SegmentationSource] that produced it, and the
 * explicit triangle membership used by paint/lasso ops + cascade-reassign.
 */
data class AiRegionNode(
    val region: AiRegion,
    val children: List<AiRegionNode> = emptyList(),
    val nodeSource: SegmentationSource,
    val triangleIds: IntArray,
    val expanded: Boolean = true,
) {
    val isLeaf: Boolean get() = children.isEmpty()

    /** Depth-first flatten with depth annotations. Caller uses this to render a LazyColumn. */
    fun flatten(): List<Pair<AiRegionNode, Int>> {
        val out = mutableListOf<Pair<AiRegionNode, Int>>()
        fun visit(node: AiRegionNode, depth: Int) {
            out.add(node to depth)
            for (c in node.children) visit(c, depth + 1)
        }
        visit(this, 0)
        return out
    }

    /** Total leaf count under this node (including self if leaf). */
    fun leafCount(): Int =
        if (isLeaf) 1 else children.sumOf { it.leafCount() }

    /** Slot with the most triangles across all leaves under this node. Tie → lower slot index. */
    fun dominantSlot(): Int =
        slotHistogram().maxByOrNull { it.value }?.key ?: region.slot

    /** Slot with the second-most triangles; null when all leaves share a single slot. */
    fun secondarySlot(): Int? {
        val hist = slotHistogram()
        if (hist.size < 2) return null
        return hist.entries.sortedByDescending { it.value }[1].key
    }

    private fun slotHistogram(): Map<Int, Int> {
        val counts = mutableMapOf<Int, Int>()
        fun visit(node: AiRegionNode) {
            if (node.isLeaf) {
                counts.merge(node.region.slot, node.triangleIds.size) { a, b -> a + b }
            } else {
                node.children.forEach(::visit)
            }
        }
        visit(this)
        return counts
    }

    // ByteArray-shaped helpers rely on identity equality; declare consistent equals/hashCode
    // so Compose recomposition + List equality use reference identity, not deep content compare.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
```

- [ ] **Step 4: Run test, verify passes**

Same command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/aipaint/AiRegionNode.kt \
        app/src/test/java/com/u1/slicer/aipaint/AiRegionNodeTest.kt
git commit -m "F54 plan task 2: AiRegionNode tree data model"
```

---

## Task 3: Add `CustomSelection` side-channel data model

**Files:**
- Create: `app/src/main/java/com/u1/slicer/aipaint/CustomSelections.kt`
- Create: `app/src/test/java/com/u1/slicer/aipaint/CustomSelectionsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/java/com/u1/slicer/aipaint/CustomSelectionsTest.kt
package com.u1.slicer.aipaint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CustomSelectionsTest {

    @Test
    fun `empty list builds no custom-selections group`() {
        val node = CustomSelections.buildGroup(emptyList())
        assertNull(node)
    }

    @Test
    fun `single selection builds a parent with one child`() {
        val selections = listOf(
            CustomSelection(id = 0, slot = 1, triangleIds = intArrayOf(1, 2, 3, 4, 5)),
        )
        val group = CustomSelections.buildGroup(selections)
        assertNotNull(group)
        assertEquals(1, group!!.children.size)
        assertEquals("Custom selection · 5 tri", group.children[0].region.label)
        assertEquals(1, group.children[0].region.slot)
    }

    @Test
    fun `multiple selections build siblings`() {
        val selections = listOf(
            CustomSelection(id = 0, slot = 0, triangleIds = intArrayOf(1, 2)),
            CustomSelection(id = 1, slot = 2, triangleIds = intArrayOf(3, 4, 5)),
        )
        val group = CustomSelections.buildGroup(selections)!!
        assertEquals(2, group.children.size)
        assertEquals("Custom selection · 2 tri", group.children[0].region.label)
        assertEquals("Custom selection · 3 tri", group.children[1].region.label)
    }

    @Test
    fun `group parent uses Custom selections label and BRUSH source`() {
        val sel = listOf(CustomSelection(id = 0, slot = 0, triangleIds = intArrayOf(1)))
        val group = CustomSelections.buildGroup(sel)!!
        assertEquals("Custom selections", group.region.label)
        assertEquals(SegmentationSource.BRUSH, group.nodeSource)
    }
}
```

- [ ] **Step 2: Run test, verify fails**

`./gradlew testDebugUnitTest --tests com.u1.slicer.aipaint.CustomSelectionsTest --no-daemon`
Expected: FAIL — symbols unresolved.

- [ ] **Step 3: Implement**

```kotlin
// app/src/main/java/com/u1/slicer/aipaint/CustomSelections.kt
package com.u1.slicer.aipaint

/**
 * A single brush stroke / lasso commit. The id is a monotonically-increasing tag so the screen
 * can refer back to the row. slot is the physical filament slot the user assigned. triangleIds
 * is the explicit set of triangles painted in this stroke.
 */
data class CustomSelection(
    val id: Int,
    val slot: Int,
    val triangleIds: IntArray,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

object CustomSelections {
    /**
     * Build the root-level "Custom selections" tree node from the user's accumulated brush
     * strokes. Returns null when there are no strokes so the tree doesn't show an empty group.
     */
    fun buildGroup(selections: List<CustomSelection>): AiRegionNode? {
        if (selections.isEmpty()) return null
        val children = selections.map { sel ->
            AiRegionNode(
                region = AiRegion(
                    id = sel.id,
                    label = "Custom selection · ${sel.triangleIds.size} tri",
                    suggestedColour = "#888888",
                    slot = sel.slot,
                ),
                children = emptyList(),
                nodeSource = SegmentationSource.BRUSH,
                triangleIds = sel.triangleIds,
            )
        }
        val totalTris = selections.sumOf { it.triangleIds.size }
        return AiRegionNode(
            region = AiRegion(
                id = -1,
                label = "Custom selections",
                suggestedColour = "#888888",
                slot = children.firstOrNull()?.region?.slot ?: 0,
            ),
            children = children,
            nodeSource = SegmentationSource.BRUSH,
            triangleIds = IntArray(totalTris).also { out ->
                var p = 0
                for (s in selections) {
                    System.arraycopy(s.triangleIds, 0, out, p, s.triangleIds.size)
                    p += s.triangleIds.size
                }
            },
        )
    }
}
```

- [ ] **Step 4: Run test, verify passes**

Same command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/aipaint/CustomSelections.kt \
        app/src/test/java/com/u1/slicer/aipaint/CustomSelectionsTest.kt
git commit -m "F54 plan task 3: CustomSelection + 'Custom selections' tree group"
```

---

## Task 4: Refactor `AiPaintResultState` for tree shape

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/aipaint/AiRegion.kt`
- Modify (compile-only patch, full rewrites later): `app/src/main/java/com/u1/slicer/aipaint/AiPaintViewModel.kt`, `app/src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt`, `app/src/main/java/com/u1/slicer/navigation/NavGraph.kt`

- [ ] **Step 1: Update `AiPaintResultState`**

```kotlin
// app/src/main/java/com/u1/slicer/aipaint/AiRegion.kt — replace the existing AiPaintResultState
data class AiPaintResultState(
    /** Root-level nodes. Today the cascade emits exactly one cascade root + an optional
     *  "Custom selections" sibling. Tree is the source of truth for what the screen renders. */
    val tree: List<AiRegionNode>,
    /** Which cascade branch produced the segmentation. */
    val source: SegmentationSource,

    val paintedModelPath: String,
    val sourceModelPath: String,
    val previewBitmap: android.graphics.Bitmap? = null,

    val trianglePositions: FloatArray = FloatArray(0),
    /** Per-triangle SEGMENT id (matches the cascade-tree leaf id that originally claimed the
     *  triangle; mutated only when the user does a "Clear all custom selections" flatten). */
    val triangleSegments: ByteArray = ByteArray(0),
    /** Per-triangle SLOT (0..3). Mutated by paint/lasso/cascade-reassign. Written to the 3MF. */
    val triangleRegions: ByteArray = ByteArray(0),

    val highlightComponentId: Int? = null,
    val canUndo: Boolean = false,

    /** Optional AI-naming side state — drives the failure chip. */
    val aiNamingFailed: Boolean = false,
    val aiModelTried: String? = null,

    /** Brush / lasso commits accumulated in this session. Rendered as a root-level group. */
    val customSelections: List<CustomSelection> = emptyList(),
) {
    // Existing identity-equality shim is preserved — tree + arrays compare by reference.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)

    /** Convenience: flat list of leaf regions, used by code that wants to iterate everything
     *  the user can recolour. Skips the cascade root and the "Custom selections" group parent. */
    val leafRegions: List<AiRegion>
        get() = tree.flatMap { root -> root.flatten().filter { (n, _) -> n.isLeaf }.map { it.first.region } }
}
```

- [ ] **Step 2: Stub the ViewModel + screen + NavGraph against the new shape**

This step is a compile fix: every reference to `state.regions`, `state.componentIds`, `state.numComponents`, `state.componentToRegion`, `state.aiTriangleRegions`, `state.aiRegions`, `state.zBandTriangleRegions`, `state.zBandRegions`, `state.showingZBands`, `state.usedAiFallback`, `state.fallbackReason` must compile. Replace with minimal stand-ins that read from the tree.

In `AiPaintViewModel.kt`:
- Replace every `state.regions` with `state.leafRegions` (read-only).
- Anywhere a write to `regions` happens (e.g. `updateRegionColour`, `setSegmentSlot`), tree-walk: rebuild the tree with the targeted node's region.copy(userColour = …) or slot = ….
- Delete fields the new state doesn't carry — `componentIds`, `numComponents`, `componentToRegion`, the AI/Z-band toggle fields. Any function using them gets re-implemented in later tasks (Task 13). For now: stub these functions to throw `TODO()` so the file compiles; the cascade comes online before anything in the app calls them.
- `moveComponent`, `paintTriangles`, `commitSelection`, `toggleZBands`, `setSegmentSlot` — replace bodies with `TODO("rewired in Task 13")` and leave existing function signatures.

In `ui/AiPaintResultScreen.kt`:
- Replace `result.regions` with `result.leafRegions` in the LazyColumn call.
- Remove any reference to dropped fields (`showingZBands`, `usedAiFallback`, `fallbackReason`, `aiTriangleRegions`, `zBandTriangleRegions`).
- Leave the LazyColumn rendering the flat leaf list for now (replaced in Task 14).

In `NavGraph.kt`: remove `onToggleZBands` wire-up (already gone in fix33; verify still gone).

- [ ] **Step 3: Run a full debug build to confirm everything compiles**

```bash
./gradlew assembleDebug --no-daemon
```

Expected: BUILD SUCCESSFUL. Warnings about TODO calls are acceptable; errors are not.

- [ ] **Step 4: Run all unit tests to make sure existing tests still build**

```bash
./gradlew testDebugUnitTest --no-daemon
```

Expected: PASS overall. The earlier `AiLabelClientTest` already covers the post-fix33 surface; if any tests reference removed state fields, delete those tests (they were testing dropped behaviour). Drop `SliceStalenessTest` references to AI Paint only if applicable; this plan does NOT modify staleness state.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/aipaint/AiRegion.kt \
        app/src/main/java/com/u1/slicer/aipaint/AiPaintViewModel.kt \
        app/src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt \
        app/src/main/java/com/u1/slicer/navigation/NavGraph.kt
git commit -m "F54 plan task 4: refactor AiPaintResultState to tree shape (stubs for ops)"
```

---

## Task 5: Z-band branch (Branch F) as pure function

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/aipaint/SegmentationCascade.kt` (create new)
- Create: `app/src/test/java/com/u1/slicer/aipaint/SegmentationCascadeTest.kt`

- [ ] **Step 1: Write a failing test for the Z-band branch**

```kotlin
// app/src/test/java/com/u1/slicer/aipaint/SegmentationCascadeTest.kt
package com.u1.slicer.aipaint

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentationCascadeTest {

    /** Synthetic triangle list: N triangles arranged at increasing Z. Used for Z-band tests. */
    private fun ladderPositions(triCount: Int): FloatArray {
        val out = FloatArray(triCount * 9)
        for (t in 0 until triCount) {
            val z = t.toFloat()
            val b = t * 9
            // three vertices at z, z, z (degenerate-but-fine for centroid computation)
            for (v in 0 until 3) {
                out[b + v * 3 + 0] = 0f
                out[b + v * 3 + 1] = 0f
                out[b + v * 3 + 2] = z
            }
        }
        return out
    }

    @Test
    fun `zBand branch produces TARGET_SEGMENTS leaves with monotonic z`() {
        val tris = 240
        val result = SegmentationCascade.zBandBranch(
            ladderPositions(tris),
            bandCount = 12,
        )
        assertEquals(SegmentationSource.Z_BAND, result.source)
        assertEquals(1, result.tree.size) // single root
        val root = result.tree.first()
        assertEquals(12, root.children.size)
        // Each band gets exactly tris / 12 = 20 triangles.
        root.children.forEachIndexed { i, child ->
            assertEquals("band $i triangle count", 20, child.triangleIds.size)
        }
        // triangleSegments labels triangles 0..bandCount-1 in monotonic order.
        for (t in 0 until tris) {
            val expectedBand = (t / 20).coerceAtMost(11)
            assertEquals("triangle $t belongs to band $expectedBand",
                expectedBand, result.triangleSegments[t].toInt() and 0xFF)
        }
    }

    @Test
    fun `zBand assigns slots round-robin`() {
        val result = SegmentationCascade.zBandBranch(ladderPositions(24), bandCount = 12)
        val slots = result.tree.first().children.map { it.region.slot }
        assertEquals(listOf(0,1,2,3,0,1,2,3,0,1,2,3), slots)
    }
}
```

- [ ] **Step 2: Run test, verify fails**

`./gradlew testDebugUnitTest --tests com.u1.slicer.aipaint.SegmentationCascadeTest --no-daemon`
Expected: FAIL — `SegmentationCascade` unresolved.

- [ ] **Step 3: Create the cascade file with `zBandBranch`**

```kotlin
// app/src/main/java/com/u1/slicer/aipaint/SegmentationCascade.kt
package com.u1.slicer.aipaint

/** Output of one cascade branch (or the cascade as a whole). */
data class CascadeResult(
    val tree: List<AiRegionNode>,         // typically one cascade root; may include the
                                          // "Custom selections" group as a sibling later
    val triangleSegments: ByteArray,      // per-triangle leaf-id mapping; ByteArray so it
                                          // round-trips with state.triangleSegments
    val source: SegmentationSource,
)

object SegmentationCascade {

    const val TARGET_SLOTS = 4

    /** Default labels for Z-bands. Generic so a Benchy in fallback mode doesn't show
     *  "Hooves" — AI naming (if enabled) overrides these. */
    internal val Z_BAND_LABELS: List<String> = List(12) { "Band ${it + 1}" }
    internal val Z_BAND_COLOURS: List<String> = listOf(
        "#37474F", "#5D4037", "#795548", "#1E88E5", "#43A047",
        "#00ACC1", "#FB8C00", "#8E24AA", "#E53935", "#EC407A",
        "#FFEB3B", "#FFFFFF",
    )

    /** Branch F — equal-width Z-band segmentation. Always succeeds. */
    fun zBandBranch(positions: FloatArray, bandCount: Int = 12): CascadeResult {
        val triCount = positions.size / 9
        val bands = ByteArray(triCount)

        if (triCount == 0 || bandCount <= 0) {
            return CascadeResult(emptyList(), bands, SegmentationSource.Z_BAND)
        }
        var minZ = Float.POSITIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        for (t in 0 until triCount) {
            val b = t * 9
            val cz = (positions[b + 2] + positions[b + 5] + positions[b + 8]) / 3f
            if (cz < minZ) minZ = cz
            if (cz > maxZ) maxZ = cz
        }
        val span = (maxZ - minZ).coerceAtLeast(1e-3f)
        for (t in 0 until triCount) {
            val b = t * 9
            val cz = (positions[b + 2] + positions[b + 5] + positions[b + 8]) / 3f
            val band = ((cz - minZ) / span * bandCount).toInt().coerceIn(0, bandCount - 1)
            bands[t] = band.toByte()
        }

        // Group triangle indices by band so the tree carries explicit membership.
        val perBand = Array(bandCount) { mutableListOf<Int>() }
        for (t in 0 until triCount) perBand[bands[t].toInt() and 0xFF].add(t)

        val children = (0 until bandCount).map { i ->
            val tris = perBand[i].toIntArray()
            AiRegionNode(
                region = AiRegion(
                    id = i,
                    label = Z_BAND_LABELS.getOrElse(i) { "Band ${i + 1}" },
                    suggestedColour = Z_BAND_COLOURS.getOrElse(i) { "#888888" },
                    coverageFraction = tris.size.toFloat() / triCount,
                    slot = i % TARGET_SLOTS,
                ),
                children = emptyList(),
                nodeSource = SegmentationSource.Z_BAND,
                triangleIds = tris,
            )
        }
        val root = AiRegionNode(
            region = AiRegion(
                id = -1,
                label = "Model",
                suggestedColour = "#888888",
                coverageFraction = 1f,
            ),
            children = children,
            nodeSource = SegmentationSource.Z_BAND,
            triangleIds = IntArray(triCount) { it },
        )
        return CascadeResult(listOf(root), bands, SegmentationSource.Z_BAND)
    }
}
```

- [ ] **Step 4: Run test, verify passes**

Same command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/aipaint/SegmentationCascade.kt \
        app/src/test/java/com/u1/slicer/aipaint/SegmentationCascadeTest.kt
git commit -m "F54 plan task 5: SegmentationCascade.zBandBranch (Branch F)"
```

---

## Task 6: Topology branch (Branch E) — wraps MeshSegmenter

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/aipaint/SegmentationCascade.kt`
- Modify: `app/src/test/java/com/u1/slicer/aipaint/SegmentationCascadeTest.kt`

- [ ] **Step 1: Add a test for the topology branch (small-component baseline)**

Append to `SegmentationCascadeTest.kt`:

```kotlin
    @Test
    fun `topology branch yields one leaf per component when balanced`() {
        // 3 disjoint triangle clusters, each on its own connected island.
        // Build a positions array with 3 widely-separated triangle clusters of 30 each.
        val positions = FloatArray(90 * 9)
        for (cluster in 0 until 3) {
            val cx = cluster * 100f
            for (t in 0 until 30) {
                val b = (cluster * 30 + t) * 9
                positions[b + 0] = cx; positions[b + 1] = t * 0.01f; positions[b + 2] = 0f
                positions[b + 3] = cx + 1f; positions[b + 4] = t * 0.01f; positions[b + 5] = 0f
                positions[b + 6] = cx; positions[b + 7] = t * 0.01f + 1f; positions[b + 8] = 0f
            }
        }
        val r = SegmentationCascade.topologyBranch(positions)
        // Either 3 components (if MeshSegmenter's flood-fill picks up the clusters) or the
        // synthetic geometry triggers spatial K-means. Either way: result must have ≥ 2 leaves.
        assertTrue("topology branch must yield ≥ 2 leaves on disjoint clusters",
            r.tree.first().children.size >= 2)
        // Source tag must be one of the topology family.
        assertTrue(r.source == SegmentationSource.TOPOLOGY ||
                   r.source == SegmentationSource.TOPOLOGY_RECURSIVE)
    }
```

- [ ] **Step 2: Run test, verify fails**

`./gradlew testDebugUnitTest --tests com.u1.slicer.aipaint.SegmentationCascadeTest --no-daemon`
Expected: FAIL — `topologyBranch` unresolved.

- [ ] **Step 3: Add the topology branch**

Append to `SegmentationCascade.kt`:

```kotlin
    /** Triangle-share threshold for recursive sub-division: when ONE component owns more than
     *  this fraction of total triangles, we K-means-split it into sub-regions. */
    private const val DOMINANT_THRESHOLD = 0.60f

    /** Maximum leaves the cascade emits. The original UI cap; tree depth caps independently. */
    private const val TARGET_LEAVES = 12

    /** Branch E — topology flood-fill, with recursion on the dominant component. */
    fun topologyBranch(positions: FloatArray): CascadeResult {
        val (componentIds, numComponents) =
            MeshSegmenter.segmentByTopologyOrSpatial(positions)
        val triCount = positions.size / 9
        if (numComponents < 2) {
            return CascadeResult(emptyList(), ByteArray(triCount), SegmentationSource.TOPOLOGY)
        }

        // Triangle counts per component → descending order; keep top TARGET_LEAVES.
        val triByComp = Array(numComponents) { mutableListOf<Int>() }
        for (t in 0 until triCount) triByComp[componentIds[t]].add(t)
        val sortedComps = (0 until numComponents).sortedByDescending { triByComp[it].size }

        // Detect dominant component for recursion.
        val largestSize = triByComp[sortedComps.first()].size
        val largestFraction = largestSize.toFloat() / triCount
        val shouldRecurse = largestFraction > DOMINANT_THRESHOLD

        val baseLeaves = sortedComps.take(TARGET_LEAVES).mapIndexed { i, comp ->
            val tris = triByComp[comp].toIntArray()
            AiRegionNode(
                region = AiRegion(
                    id = i,
                    label = "Region ${i + 1}",
                    suggestedColour = paletteFor(i),
                    coverageFraction = tris.size.toFloat() / triCount,
                    slot = i % TARGET_SLOTS,
                ),
                children = emptyList(),
                nodeSource = SegmentationSource.TOPOLOGY,
                triangleIds = tris,
            )
        }

        val children: List<AiRegionNode> = if (shouldRecurse) {
            // Replace the dominant leaf with one whose children are K-means sub-regions.
            val dominant = baseLeaves.first()
            val subRegions = TopologyRecursion.subdivide(
                positions = positions,
                triangleIds = dominant.triangleIds,
                kMeansK = 8,
                startId = TARGET_LEAVES,
            )
            listOf(dominant.copy(
                children = subRegions,
                nodeSource = SegmentationSource.TOPOLOGY_RECURSIVE,
            )) + baseLeaves.drop(1)
        } else {
            baseLeaves
        }

        // Triangle → segment id map. For recursive children, the SUB-region id wins.
        val triangleSegments = ByteArray(triCount)
        children.forEach { topChild ->
            if (topChild.children.isEmpty()) {
                topChild.triangleIds.forEach { t -> triangleSegments[t] = topChild.region.id.toByte() }
            } else {
                topChild.children.forEach { sub ->
                    sub.triangleIds.forEach { t -> triangleSegments[t] = sub.region.id.toByte() }
                }
            }
        }

        val root = AiRegionNode(
            region = AiRegion(
                id = -1,
                label = "Model",
                suggestedColour = "#888888",
                coverageFraction = 1f,
            ),
            children = children,
            nodeSource = if (shouldRecurse) SegmentationSource.TOPOLOGY_RECURSIVE else SegmentationSource.TOPOLOGY,
            triangleIds = IntArray(triCount) { it },
        )
        return CascadeResult(
            tree = listOf(root),
            triangleSegments = triangleSegments,
            source = root.nodeSource,
        )
    }

    private val PALETTE = listOf(
        "#E53935", "#1E88E5", "#43A047", "#FB8C00",
        "#8E24AA", "#00ACC1", "#F4511E", "#6D4C41",
        "#EC407A", "#FFEB3B", "#FFFFFF", "#37474F",
    )

    internal fun paletteFor(i: Int): String = PALETTE[i % PALETTE.size]
```

- [ ] **Step 4: Run test, verify fails on `TopologyRecursion`**

Expected: FAIL — `TopologyRecursion` unresolved. We implement it in Task 7.

- [ ] **Step 5: Skip the commit for now**

Continue to Task 7. We commit the combined branch E + topology recursion in Task 7's commit.

---

## Task 7: `TopologyRecursion.subdivide` (spatial K-means on a dominant component)

**Files:**
- Create: `app/src/main/java/com/u1/slicer/aipaint/TopologyRecursion.kt`
- Create: `app/src/test/java/com/u1/slicer/aipaint/TopologyRecursionTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/java/com/u1/slicer/aipaint/TopologyRecursionTest.kt
package com.u1.slicer.aipaint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopologyRecursionTest {

    /** 80 triangles laid out along Z 0..79; subdividing into K=8 should yield 8 evenly-spaced
     *  Z bands of 10 triangles each (within K-means' rounding). */
    @Test
    fun `subdivide returns K sub-regions covering all input triangles`() {
        val positions = FloatArray(80 * 9)
        for (t in 0 until 80) {
            val b = t * 9
            val z = t.toFloat()
            for (v in 0 until 3) {
                positions[b + v * 3 + 0] = 0f
                positions[b + v * 3 + 1] = 0f
                positions[b + v * 3 + 2] = z
            }
        }
        val all = IntArray(80) { it }
        val subs = TopologyRecursion.subdivide(positions, all, kMeansK = 8, startId = 12)
        assertEquals(8, subs.size)
        val coveredTriangles = subs.flatMap { it.triangleIds.toList() }.toSet()
        assertEquals(80, coveredTriangles.size)
        // IDs assigned sequentially from startId
        assertEquals((12 until 20).toList(), subs.map { it.region.id })
        // Every sub-region tagged TOPOLOGY_RECURSIVE
        assertTrue(subs.all { it.nodeSource == SegmentationSource.TOPOLOGY_RECURSIVE })
    }

    @Test
    fun `subdivide returns single-region for tiny input`() {
        val positions = FloatArray(3 * 9) // 3 triangles
        val subs = TopologyRecursion.subdivide(positions, intArrayOf(0, 1, 2), kMeansK = 8, startId = 12)
        // Can't subdivide 3 triangles into 8 useful clusters; expect ≤ inputCount sub-regions,
        // each non-empty.
        assertTrue("expected at most 3 sub-regions on 3-triangle input", subs.size <= 3)
        assertTrue("every sub-region must be non-empty",
            subs.all { it.triangleIds.isNotEmpty() })
    }
}
```

- [ ] **Step 2: Run test, verify fails**

`./gradlew testDebugUnitTest --tests com.u1.slicer.aipaint.TopologyRecursionTest --no-daemon`
Expected: FAIL — `TopologyRecursion` unresolved.

- [ ] **Step 3: Implement**

```kotlin
// app/src/main/java/com/u1/slicer/aipaint/TopologyRecursion.kt
package com.u1.slicer.aipaint

import kotlin.math.min
import kotlin.math.sqrt

/**
 * Spatial K-means clustering for the recursive sub-division of a dominant topology component.
 * Operates on per-triangle centroids; K bounded by the input size. Sub-regions are emitted as
 * AiRegionNode leaves tagged TOPOLOGY_RECURSIVE.
 */
object TopologyRecursion {

    fun subdivide(
        positions: FloatArray,
        triangleIds: IntArray,
        kMeansK: Int,
        startId: Int,
    ): List<AiRegionNode> {
        val n = triangleIds.size
        if (n == 0) return emptyList()
        val k = min(kMeansK, n)

        val centroids = Array(n) { i ->
            val t = triangleIds[i]
            val b = t * 9
            floatArrayOf(
                (positions[b + 0] + positions[b + 3] + positions[b + 6]) / 3f,
                (positions[b + 1] + positions[b + 4] + positions[b + 7]) / 3f,
                (positions[b + 2] + positions[b + 5] + positions[b + 8]) / 3f,
            )
        }

        // Farthest-point seeding for determinism (no random).
        val means = Array(k) { FloatArray(3) }
        means[0] = centroids[0].copyOf()
        for (s in 1 until k) {
            var bestIdx = 0; var bestDist = -1f
            for (i in 0 until n) {
                var minD = Float.POSITIVE_INFINITY
                for (j in 0 until s) {
                    val dx = centroids[i][0] - means[j][0]
                    val dy = centroids[i][1] - means[j][1]
                    val dz = centroids[i][2] - means[j][2]
                    val d = dx * dx + dy * dy + dz * dz
                    if (d < minD) minD = d
                }
                if (minD > bestDist) { bestDist = minD; bestIdx = i }
            }
            means[s] = centroids[bestIdx].copyOf()
        }

        // Lloyd's iteration — bounded (8 rounds is plenty for k ≤ 12 on small inputs).
        val labels = IntArray(n)
        for (iter in 0 until 8) {
            var changed = false
            for (i in 0 until n) {
                var bestK = 0; var bestD = Float.POSITIVE_INFINITY
                for (j in 0 until k) {
                    val dx = centroids[i][0] - means[j][0]
                    val dy = centroids[i][1] - means[j][1]
                    val dz = centroids[i][2] - means[j][2]
                    val d = dx * dx + dy * dy + dz * dz
                    if (d < bestD) { bestD = d; bestK = j }
                }
                if (labels[i] != bestK) { labels[i] = bestK; changed = true }
            }
            if (!changed) break
            // Update means.
            val sums = Array(k) { FloatArray(3) }
            val counts = IntArray(k)
            for (i in 0 until n) {
                val l = labels[i]
                sums[l][0] += centroids[i][0]
                sums[l][1] += centroids[i][1]
                sums[l][2] += centroids[i][2]
                counts[l]++
            }
            for (j in 0 until k) {
                if (counts[j] > 0) {
                    means[j][0] = sums[j][0] / counts[j]
                    means[j][1] = sums[j][1] / counts[j]
                    means[j][2] = sums[j][2] / counts[j]
                }
            }
        }

        // Group by label; drop empty clusters.
        val grouped = Array(k) { mutableListOf<Int>() }
        for (i in 0 until n) grouped[labels[i]].add(triangleIds[i])
        val nonEmpty = grouped.filter { it.isNotEmpty() }

        return nonEmpty.mapIndexed { i, tris ->
            AiRegionNode(
                region = AiRegion(
                    id = startId + i,
                    label = "Sub-region ${i + 1}",
                    suggestedColour = SegmentationCascade.paletteFor(startId + i),
                    coverageFraction = tris.size.toFloat() / (positions.size / 9),
                    slot = (startId + i) % SegmentationCascade.TARGET_SLOTS,
                ),
                children = emptyList(),
                nodeSource = SegmentationSource.TOPOLOGY_RECURSIVE,
                triangleIds = tris.toIntArray(),
            )
        }
    }
}
```

- [ ] **Step 4: Run both topology tests**

```bash
./gradlew testDebugUnitTest --tests com.u1.slicer.aipaint.TopologyRecursionTest \
                            --tests com.u1.slicer.aipaint.SegmentationCascadeTest --no-daemon
```

Expected: PASS for both classes.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/aipaint/SegmentationCascade.kt \
        app/src/main/java/com/u1/slicer/aipaint/TopologyRecursion.kt \
        app/src/test/java/com/u1/slicer/aipaint/SegmentationCascadeTest.kt \
        app/src/test/java/com/u1/slicer/aipaint/TopologyRecursionTest.kt
git commit -m "F54 plan task 6+7: topology branch + recursive subdivision"
```

---

## Task 8: Per-object branch (Branch C)

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/aipaint/SegmentationCascade.kt`
- Modify: `app/src/test/java/com/u1/slicer/aipaint/SegmentationCascadeTest.kt`

The branch reads:
- `NativeLibrary.nativeGetObjectCount()` and `nativeGetObjectExtruderMap()` for object names + extruders
- `NativeLibrary.nativeGetPlateData(plateIdx)` to filter to the active plate

Because the cascade is pure-function-testable, we accept its data as injected `ObjectInfo` rather than calling JNI directly. The ViewModel will assemble the input from native calls in Task 12.

- [ ] **Step 1: Add the test**

Append to `SegmentationCascadeTest.kt`:

```kotlin
    private fun objectInfo(id: Long, name: String, extruder: Int?, triCount: Int): SegmentationCascade.ObjectInfo =
        SegmentationCascade.ObjectInfo(
            objectId = id,
            name = name,
            extruder = extruder,
            triangleIds = IntArray(triCount) { it },
        )

    @Test
    fun `objectBranch produces one leaf per object`() {
        val objects = listOf(
            objectInfo(1, "Hull", 1, 100),
            objectInfo(2, "Cabin", 2, 50),
            objectInfo(3, "Smokestack", 3, 25),
        )
        val r = SegmentationCascade.objectBranch(totalTriangles = 175, objects = objects)
        assertEquals(SegmentationSource.OBJECT, r.source)
        val root = r.tree.first()
        assertEquals(3, root.children.size)
        assertEquals(listOf("Hull", "Cabin", "Smokestack"),
            root.children.map { it.region.label })
        assertEquals(listOf(0, 1, 2),  // extruder 1/2/3 → slot 0/1/2 (1-indexed extruder)
            root.children.map { it.region.slot })
    }

    @Test
    fun `objectBranch null when only one object`() {
        val r = SegmentationCascade.objectBranch(
            totalTriangles = 100,
            objects = listOf(objectInfo(1, "Solo", null, 100)),
        )
        assertTrue(r.tree.isEmpty())
    }

    @Test
    fun `objectBranch falls back to round-robin slots when extruder missing`() {
        val r = SegmentationCascade.objectBranch(
            totalTriangles = 300,
            objects = listOf(
                objectInfo(1, "A", null, 100),
                objectInfo(2, "B", null, 100),
                objectInfo(3, "C", null, 100),
            ),
        )
        val slots = r.tree.first().children.map { it.region.slot }
        assertEquals(listOf(0, 1, 2), slots)
    }
```

- [ ] **Step 2: Run, verify fails**

`./gradlew testDebugUnitTest --tests com.u1.slicer.aipaint.SegmentationCascadeTest --no-daemon`
Expected: FAIL — `objectBranch` / `ObjectInfo` unresolved.

- [ ] **Step 3: Add `ObjectInfo` + `objectBranch`**

Append to `SegmentationCascade.kt`:

```kotlin
    /** One per-plate object, as fed into the cascade. The triangleIds list MUST be exhaustive
     *  and disjoint across all objects on the plate; the cascade does no deduplication. */
    data class ObjectInfo(
        val objectId: Long,
        val name: String,
        /** 1-based extruder index from `model_settings.config`; null when undeclared. */
        val extruder: Int?,
        val triangleIds: IntArray,
    )

    /** Branch C — one tree leaf per object on the selected plate. */
    fun objectBranch(totalTriangles: Int, objects: List<ObjectInfo>): CascadeResult {
        if (objects.size < 2) {
            return CascadeResult(emptyList(), ByteArray(totalTriangles), SegmentationSource.OBJECT)
        }
        val triangleSegments = ByteArray(totalTriangles)
        val children = objects.mapIndexed { i, obj ->
            val slot = obj.extruder?.let { (it - 1).coerceIn(0, TARGET_SLOTS - 1) }
                ?: (i % TARGET_SLOTS)
            obj.triangleIds.forEach { t ->
                if (t in 0 until totalTriangles) triangleSegments[t] = i.toByte()
            }
            AiRegionNode(
                region = AiRegion(
                    id = i,
                    label = obj.name,
                    suggestedColour = paletteFor(i),
                    coverageFraction = obj.triangleIds.size.toFloat() / totalTriangles,
                    slot = slot,
                ),
                children = emptyList(),
                nodeSource = SegmentationSource.OBJECT,
                triangleIds = obj.triangleIds,
            )
        }
        val root = AiRegionNode(
            region = AiRegion(
                id = -1, label = "Model", suggestedColour = "#888888", coverageFraction = 1f,
            ),
            children = children,
            nodeSource = SegmentationSource.OBJECT,
            triangleIds = IntArray(totalTriangles) { it },
        )
        return CascadeResult(listOf(root), triangleSegments, SegmentationSource.OBJECT)
    }
```

- [ ] **Step 4: Run, verify passes**

Same command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/aipaint/SegmentationCascade.kt \
        app/src/test/java/com/u1/slicer/aipaint/SegmentationCascadeTest.kt
git commit -m "F54 plan task 8: SegmentationCascade.objectBranch (Branch C)"
```

---

## Task 9: Per-volume branch (Branch B)

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/aipaint/SegmentationCascade.kt`
- Modify: `app/src/test/java/com/u1/slicer/aipaint/SegmentationCascadeTest.kt`

- [ ] **Step 1: Add the test**

Append to `SegmentationCascadeTest.kt`:

```kotlin
    private fun volumeInfo(objId: Long, objName: String, volumes: List<Triple<Int?, Int, Int>>): SegmentationCascade.ObjectVolumes {
        // volumes = list of (extruder?, firstTri, triCount)
        val vols = volumes.mapIndexed { idx, (ext, first, count) ->
            SegmentationCascade.VolumeInfo(
                volumeIndex = idx,
                extruder = ext,
                triangleIds = IntArray(count) { first + it },
            )
        }
        return SegmentationCascade.ObjectVolumes(objId, objName, vols)
    }

    @Test
    fun `volumeBranch nests volumes under an object when more than one`() {
        val obj = volumeInfo(1L, "Dragon", listOf(
            Triple(1, 0, 100),
            Triple(2, 100, 50),
            Triple(3, 150, 25),
        ))
        val r = SegmentationCascade.volumeBranch(totalTriangles = 175, objects = listOf(obj))
        assertEquals(SegmentationSource.VOLUME, r.source)
        val root = r.tree.first()
        // 1 object with 3 volumes → 1 child (the object) with 3 grandchildren.
        assertEquals(1, root.children.size)
        assertEquals("Dragon", root.children.first().region.label)
        assertEquals(3, root.children.first().children.size)
    }

    @Test
    fun `volumeBranch flattens to leaves when each object has one volume`() {
        val objs = listOf(
            volumeInfo(1L, "A", listOf(Triple(1, 0, 50))),
            volumeInfo(2L, "B", listOf(Triple(2, 50, 50))),
        )
        val r = SegmentationCascade.volumeBranch(totalTriangles = 100, objects = objs)
        // 2 single-volume objects → 2 leaves, no nesting.
        val root = r.tree.first()
        assertEquals(2, root.children.size)
        assertEquals(true, root.children.all { it.children.isEmpty() })
    }

    @Test
    fun `volumeBranch null when only one volume across all objects`() {
        val obj = volumeInfo(1L, "Solo", listOf(Triple(1, 0, 100)))
        val r = SegmentationCascade.volumeBranch(totalTriangles = 100, objects = listOf(obj))
        assertTrue(r.tree.isEmpty())
    }
```

- [ ] **Step 2: Run, verify fails**

`./gradlew testDebugUnitTest --tests com.u1.slicer.aipaint.SegmentationCascadeTest --no-daemon`
Expected: FAIL — `volumeBranch` / `ObjectVolumes` unresolved.

- [ ] **Step 3: Implement**

Append to `SegmentationCascade.kt`:

```kotlin
    data class VolumeInfo(
        val volumeIndex: Int,
        val extruder: Int?,
        val triangleIds: IntArray,
    )
    data class ObjectVolumes(
        val objectId: Long,
        val objectName: String,
        val volumes: List<VolumeInfo>,
    )

    /** Branch B — per-volume extruder. Nests volumes under their object when > 1 volume. */
    fun volumeBranch(totalTriangles: Int, objects: List<ObjectVolumes>): CascadeResult {
        val totalVolumes = objects.sumOf { it.volumes.size }
        if (totalVolumes < 2) {
            return CascadeResult(emptyList(), ByteArray(totalTriangles), SegmentationSource.VOLUME)
        }
        val triangleSegments = ByteArray(totalTriangles)
        var nextLeafId = 0

        val rootChildren = mutableListOf<AiRegionNode>()
        for (obj in objects) {
            if (obj.volumes.size == 1) {
                // Flat leaf — object IS the volume.
                val v = obj.volumes.first()
                val slot = v.extruder?.let { (it - 1).coerceIn(0, TARGET_SLOTS - 1) }
                    ?: (nextLeafId % TARGET_SLOTS)
                val id = nextLeafId++
                v.triangleIds.forEach { t ->
                    if (t in 0 until totalTriangles) triangleSegments[t] = id.toByte()
                }
                rootChildren += AiRegionNode(
                    region = AiRegion(
                        id = id,
                        label = obj.objectName,
                        suggestedColour = paletteFor(id),
                        coverageFraction = v.triangleIds.size.toFloat() / totalTriangles,
                        slot = slot,
                    ),
                    children = emptyList(),
                    nodeSource = SegmentationSource.VOLUME,
                    triangleIds = v.triangleIds,
                )
            } else {
                // Multi-volume object: parent + per-volume children.
                val volChildren = obj.volumes.map { v ->
                    val slot = v.extruder?.let { (it - 1).coerceIn(0, TARGET_SLOTS - 1) }
                        ?: (nextLeafId % TARGET_SLOTS)
                    val id = nextLeafId++
                    v.triangleIds.forEach { t ->
                        if (t in 0 until totalTriangles) triangleSegments[t] = id.toByte()
                    }
                    AiRegionNode(
                        region = AiRegion(
                            id = id,
                            label = "${obj.objectName} · volume ${v.volumeIndex + 1}",
                            suggestedColour = paletteFor(id),
                            coverageFraction = v.triangleIds.size.toFloat() / totalTriangles,
                            slot = slot,
                        ),
                        children = emptyList(),
                        nodeSource = SegmentationSource.VOLUME,
                        triangleIds = v.triangleIds,
                    )
                }
                val objTris = obj.volumes.flatMap { it.triangleIds.toList() }.toIntArray()
                rootChildren += AiRegionNode(
                    region = AiRegion(
                        id = -1,
                        label = obj.objectName,
                        suggestedColour = paletteFor(0),
                        coverageFraction = objTris.size.toFloat() / totalTriangles,
                        slot = volChildren.first().region.slot,
                    ),
                    children = volChildren,
                    nodeSource = SegmentationSource.VOLUME,
                    triangleIds = objTris,
                )
            }
        }
        val root = AiRegionNode(
            region = AiRegion(
                id = -2, label = "Model", suggestedColour = "#888888", coverageFraction = 1f,
            ),
            children = rootChildren,
            nodeSource = SegmentationSource.VOLUME,
            triangleIds = IntArray(totalTriangles) { it },
        )
        return CascadeResult(listOf(root), triangleSegments, SegmentationSource.VOLUME)
    }
```

- [ ] **Step 4: Run, verify passes**

Same command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/aipaint/SegmentationCascade.kt \
        app/src/test/java/com/u1/slicer/aipaint/SegmentationCascadeTest.kt
git commit -m "F54 plan task 9: SegmentationCascade.volumeBranch (Branch B)"
```

---

## Task 10: Paint-state branch (Branch A) and triangle-index safety net (Branch D)

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/aipaint/SegmentationCascade.kt`
- Modify: `app/src/test/java/com/u1/slicer/aipaint/SegmentationCascadeTest.kt`

- [ ] **Step 1: Add tests for both branches**

Append to `SegmentationCascadeTest.kt`:

```kotlin
    @Test
    fun `paintStateBranch produces one leaf per distinct state`() {
        // 30 triangles painted in states 1,2,3 (10 each).
        val perTriState = ByteArray(30) { i -> ((i / 10) + 1).toByte() }
        val r = SegmentationCascade.paintStateBranch(perTriState)
        assertEquals(SegmentationSource.PAINT_STATE, r.source)
        val root = r.tree.first()
        assertEquals(3, root.children.size)
        // Slot mapping: state k → slot (k-1) % 4. State 1→0, 2→1, 3→2.
        assertEquals(listOf(0, 1, 2), root.children.map { it.region.slot })
    }

    @Test
    fun `paintStateBranch handles seven H2C states without folding`() {
        // States 1..7 each painted on 10 triangles.
        val perTriState = ByteArray(70) { i -> ((i / 10) + 1).toByte() }
        val r = SegmentationCascade.paintStateBranch(perTriState)
        val root = r.tree.first()
        assertEquals(7, root.children.size)
        // Slot mapping wraps via modulo: states 1..7 → slots 0,1,2,3,0,1,2.
        assertEquals(listOf(0,1,2,3,0,1,2), root.children.map { it.region.slot })
    }

    @Test
    fun `paintStateBranch null when only one state present`() {
        val perTriState = ByteArray(50) { 1 } // all state 1
        val r = SegmentationCascade.paintStateBranch(perTriState)
        assertTrue(r.tree.isEmpty())
    }

    @Test
    fun `triangleIndexBranch fires when preview indices distinct`() {
        val perTriIndex = ByteArray(20) { i -> (i % 3).toByte() }
        val r = SegmentationCascade.triangleIndexBranch(perTriIndex)
        assertEquals(SegmentationSource.TRIANGLE_INDEX, r.source)
        assertEquals(3, r.tree.first().children.size)
    }
```

- [ ] **Step 2: Run, verify fails**

`./gradlew testDebugUnitTest --tests com.u1.slicer.aipaint.SegmentationCascadeTest --no-daemon`
Expected: FAIL — `paintStateBranch` / `triangleIndexBranch` unresolved.

- [ ] **Step 3: Implement**

Append to `SegmentationCascade.kt`:

```kotlin
    /** Branch A — pre-painted (MMU / H2C / SEMM). perTriangleState[t] = paint state 0..16
     *  (0 = unpainted; we ignore those when building leaves but include them as "state 0" if
     *  the user has unpainted triangles). */
    fun paintStateBranch(perTriangleState: ByteArray): CascadeResult {
        val triCount = perTriangleState.size
        val grouped = mutableMapOf<Int, MutableList<Int>>()
        for (t in 0 until triCount) {
            val s = perTriangleState[t].toInt() and 0xFF
            grouped.getOrPut(s) { mutableListOf() }.add(t)
        }
        if (grouped.size < 2) {
            return CascadeResult(emptyList(), perTriangleState.copyOf(), SegmentationSource.PAINT_STATE)
        }
        val sortedStates = grouped.keys.sorted()
        val triangleSegments = perTriangleState.copyOf()
        val children = sortedStates.mapIndexed { i, state ->
            val tris = grouped[state]!!.toIntArray()
            val slot = if (state == 0) i % TARGET_SLOTS else ((state - 1) % TARGET_SLOTS)
            AiRegionNode(
                region = AiRegion(
                    id = i,
                    label = if (state == 0) "Unpainted" else "Paint state $state",
                    suggestedColour = paletteFor(i),
                    coverageFraction = tris.size.toFloat() / triCount,
                    slot = slot,
                ),
                children = emptyList(),
                nodeSource = SegmentationSource.PAINT_STATE,
                triangleIds = tris,
            )
        }
        val root = AiRegionNode(
            region = AiRegion(
                id = -1, label = "Model", suggestedColour = "#888888", coverageFraction = 1f,
            ),
            children = children,
            nodeSource = SegmentationSource.PAINT_STATE,
            triangleIds = IntArray(triCount) { it },
        )
        return CascadeResult(listOf(root), triangleSegments, SegmentationSource.PAINT_STATE)
    }

    /** Branch D — distinct preview-mesh extruder indices. Safety net for cases where the
     *  native pipeline computed per-triangle colouring that A–C didn't capture. */
    fun triangleIndexBranch(perTriangleIndex: ByteArray): CascadeResult {
        val triCount = perTriangleIndex.size
        val grouped = mutableMapOf<Int, MutableList<Int>>()
        for (t in 0 until triCount) {
            val s = perTriangleIndex[t].toInt() and 0xFF
            grouped.getOrPut(s) { mutableListOf() }.add(t)
        }
        if (grouped.size < 2) {
            return CascadeResult(emptyList(), perTriangleIndex.copyOf(), SegmentationSource.TRIANGLE_INDEX)
        }
        val sortedKeys = grouped.keys.sorted()
        val triangleSegments = perTriangleIndex.copyOf()
        val children = sortedKeys.mapIndexed { i, idx ->
            val tris = grouped[idx]!!.toIntArray()
            AiRegionNode(
                region = AiRegion(
                    id = i,
                    label = "Region ${i + 1}",
                    suggestedColour = paletteFor(i),
                    coverageFraction = tris.size.toFloat() / triCount,
                    slot = idx % TARGET_SLOTS,
                ),
                children = emptyList(),
                nodeSource = SegmentationSource.TRIANGLE_INDEX,
                triangleIds = tris,
            )
        }
        val root = AiRegionNode(
            region = AiRegion(
                id = -1, label = "Model", suggestedColour = "#888888", coverageFraction = 1f,
            ),
            children = children,
            nodeSource = SegmentationSource.TRIANGLE_INDEX,
            triangleIds = IntArray(triCount) { it },
        )
        return CascadeResult(listOf(root), triangleSegments, SegmentationSource.TRIANGLE_INDEX)
    }
```

- [ ] **Step 4: Run, verify passes**

Same command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/aipaint/SegmentationCascade.kt \
        app/src/test/java/com/u1/slicer/aipaint/SegmentationCascadeTest.kt
git commit -m "F54 plan task 10: paintStateBranch (A) + triangleIndexBranch (D)"
```

---

## Task 11: Cascade orchestrator `run(...)`

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/aipaint/SegmentationCascade.kt`
- Modify: `app/src/test/java/com/u1/slicer/aipaint/SegmentationCascadeTest.kt`

- [ ] **Step 1: Add tests for cascade ordering**

Append to `SegmentationCascadeTest.kt`:

```kotlin
    @Test
    fun `run picks paint state first when present`() {
        val input = SegmentationCascade.Input(
            positions = ladderPositions(30),
            perTrianglePaintState = ByteArray(30) { i -> ((i / 10) + 1).toByte() },
            volumes = emptyList(),
            objects = emptyList(),
            perTriangleIndex = ByteArray(0),
        )
        assertEquals(SegmentationSource.PAINT_STATE, SegmentationCascade.run(input).source)
    }

    @Test
    fun `run picks volume branch over object branch when both present`() {
        val obj1 = volumeInfo(1L, "Object", listOf(
            Triple(1, 0, 50),
            Triple(2, 50, 50),
        ))
        val input = SegmentationCascade.Input(
            positions = ladderPositions(100),
            perTrianglePaintState = ByteArray(100),       // all unpainted
            volumes = listOf(obj1),
            objects = listOf(SegmentationCascade.ObjectInfo(1L, "Object", null, IntArray(100) { it })),
            perTriangleIndex = ByteArray(100),
        )
        assertEquals(SegmentationSource.VOLUME, SegmentationCascade.run(input).source)
    }

    @Test
    fun `run picks object branch over topology when both present`() {
        val input = SegmentationCascade.Input(
            positions = ladderPositions(100),
            perTrianglePaintState = ByteArray(100),
            volumes = emptyList(),
            objects = listOf(
                SegmentationCascade.ObjectInfo(1L, "A", null, intArrayOf(0, 1, 2, 3, 4)),
                SegmentationCascade.ObjectInfo(2L, "B", null, IntArray(95) { it + 5 }),
            ),
            perTriangleIndex = ByteArray(100),
        )
        assertEquals(SegmentationSource.OBJECT, SegmentationCascade.run(input).source)
    }

    @Test
    fun `run falls all the way to Z-bands when no branch fires`() {
        val input = SegmentationCascade.Input(
            positions = ladderPositions(120),
            perTrianglePaintState = ByteArray(120),
            volumes = emptyList(),
            objects = emptyList(),
            perTriangleIndex = ByteArray(120),
        )
        val r = SegmentationCascade.run(input)
        // Smooth ladder: topology probably finds 1 component → falls through to Z-bands.
        assertTrue(
            "expected TOPOLOGY*, or Z_BAND: got ${r.source}",
            r.source == SegmentationSource.TOPOLOGY ||
            r.source == SegmentationSource.TOPOLOGY_RECURSIVE ||
            r.source == SegmentationSource.Z_BAND,
        )
    }
```

- [ ] **Step 2: Run, verify fails**

`./gradlew testDebugUnitTest --tests com.u1.slicer.aipaint.SegmentationCascadeTest --no-daemon`
Expected: FAIL — `Input` / `run` unresolved.

- [ ] **Step 3: Implement the orchestrator**

Append to `SegmentationCascade.kt`:

```kotlin
    /** Bundle every input the cascade needs from the loaded native snapshot. The ViewModel
     *  assembles this from JNI calls; tests construct it directly. */
    data class Input(
        val positions: FloatArray,
        /** Per-triangle MMU paint state (0 = unpainted, 1..16 = state index). */
        val perTrianglePaintState: ByteArray,
        /** Branch B input: per-object volume listings with extruders. */
        val volumes: List<ObjectVolumes>,
        /** Branch C input: per-object summary. */
        val objects: List<ObjectInfo>,
        /** Branch D input: native preview mesh extruder indices. */
        val perTriangleIndex: ByteArray,
    )

    /** Walk branches A → F top-down; return the first non-trivial result. */
    fun run(input: Input): CascadeResult {
        val triCount = input.positions.size / 9

        // A — paint state
        if (input.perTrianglePaintState.size == triCount && triCount > 0) {
            val r = paintStateBranch(input.perTrianglePaintState)
            if (r.tree.isNotEmpty()) return r
        }
        // B — per-volume
        if (input.volumes.sumOf { it.volumes.size } >= 2) {
            val r = volumeBranch(triCount, input.volumes)
            if (r.tree.isNotEmpty()) return r
        }
        // C — per-object
        if (input.objects.size >= 2) {
            val r = objectBranch(triCount, input.objects)
            if (r.tree.isNotEmpty()) return r
        }
        // D — triangle indices (safety net)
        if (input.perTriangleIndex.size == triCount && triCount > 0) {
            val r = triangleIndexBranch(input.perTriangleIndex)
            if (r.tree.isNotEmpty()) return r
        }
        // E — topology + recursion
        val topo = topologyBranch(input.positions)
        if (topo.tree.isNotEmpty()) return topo
        // F — Z-bands (last resort, always succeeds)
        return zBandBranch(input.positions)
    }
```

- [ ] **Step 4: Run, verify passes**

Same command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/aipaint/SegmentationCascade.kt \
        app/src/test/java/com/u1/slicer/aipaint/SegmentationCascadeTest.kt
git commit -m "F54 plan task 11: SegmentationCascade.run orchestrator"
```

---

## Task 12: ViewModel pipeline cutover

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/aipaint/AiPaintViewModel.kt`

The ViewModel composes the cascade input from JNI snapshots, calls `SegmentationCascade.run`, builds the final `AiPaintResultState`, and runs the AI-naming post-pass when `aiNamingEnabled`.

- [ ] **Step 1: Wire the cascade input assembly**

Replace the body of `runPipelineInternal` in `AiPaintViewModel.kt` with the new pipeline. The full skeleton:

```kotlin
// app/src/main/java/com/u1/slicer/aipaint/AiPaintViewModel.kt (in runPipelineInternal)

private fun runPipelineInternal(
    sourceModelPath: String,
    native: NativeLibrary,
    printerColours: List<String>?,
) {
    viewModelScope.launch {
        _uiState.value = AiPaintUiState.Running(1, "Reading model geometry…")
        try {
            val providerName = settings.aiPaintProvider.first()
            val apiKey = settings.aiPaintApiKeyFor(providerName).first()
            val provider = AiPaintProvider.fromId(providerName)
            val aiEnabled = settings.aiNamingEnabled.first()

            val mesh = native.getPreparePreviewMesh(
                maxTriangles = NativePreviewMesh.MAX_DECIMATED_TRIANGLES
            ) ?: run {
                _uiState.value = AiPaintUiState.Error("Could not read model geometry.")
                return@launch
            }
            val positions = mesh.trianglePositions
            val triCount = positions.size / 9

            _uiState.value = AiPaintUiState.Running(2, "Finding parts of the model…")
            val input = withContext(Dispatchers.Default) {
                buildCascadeInput(native, positions, triCount, mesh)
            }
            val cascadeResult = withContext(Dispatchers.Default) {
                SegmentationCascade.run(input)
            }
            Log.i("AiPaint", "Cascade fired: ${cascadeResult.source.name} → " +
                "${cascadeResult.tree.firstOrNull()?.leafCount() ?: 0} leaves")

            // AI naming (optional, opt-in).
            val (tree, aiFailed, modelTried) = if (aiEnabled && (!provider.requiresKey || apiKey.isNotBlank())) {
                _uiState.value = AiPaintUiState.Running(3, "Asking AI to name the parts…")
                applyAiNaming(provider, apiKey, positions, cascadeResult.tree)
            } else {
                Triple(cascadeResult.tree, false, null)
            }

            _uiState.value = AiPaintUiState.Running(4, "Writing painted model…")

            // Per-triangle slot from tree leaves' slot assignment.
            val triangleRegions = ByteArray(triCount)
            val leafByTri = IntArray(triCount) { -1 }
            tree.forEach { root ->
                root.flatten().forEach { (node, _) ->
                    if (node.isLeaf) {
                        node.triangleIds.forEach { t ->
                            if (t in 0 until triCount) {
                                triangleRegions[t] = node.region.slot.toByte()
                                leafByTri[t] = node.region.id
                            }
                        }
                    }
                }
            }
            val triangleSegments = ByteArray(triCount) {
                leafByTri[it].coerceIn(0, 255).toByte()
            }

            // 3MF write (existing PaintedMeshWriter, slots view as before).
            val slotsView = (0 until SegmentationCascade.TARGET_SLOTS).map { s ->
                AiRegion(
                    id = s,
                    label = "Slot ${s + 1}",
                    suggestedColour = printerColours?.getOrNull(s) ?: "#888888",
                    userColour = printerColours?.getOrNull(s)?.takeIf(::isValidHex),
                    slot = s,
                )
            }
            val outFile = java.io.File(app.cacheDir, "ai_paint_${System.currentTimeMillis()}.3mf")
            val slotIdsForFile = IntArray(triCount) { triangleRegions[it].toInt() and 0xFF }
            PaintedMeshWriter.write(
                positions, slotIdsForFile, slotsView, outFile,
                printerColours = printerColours,
            )

            _uiState.value = AiPaintUiState.Result(
                AiPaintResultState(
                    tree = tree,
                    source = cascadeResult.source,
                    paintedModelPath = outFile.absolutePath,
                    sourceModelPath = sourceModelPath,
                    trianglePositions = positions,
                    triangleSegments = triangleSegments,
                    triangleRegions = triangleRegions,
                    aiNamingFailed = aiFailed,
                    aiModelTried = modelTried,
                    customSelections = emptyList(),
                )
            )
        } catch (e: Exception) {
            _uiState.value = AiPaintUiState.Error(e.message ?: "Unknown error")
        }
    }
}

private fun buildCascadeInput(
    native: NativeLibrary,
    positions: FloatArray,
    triCount: Int,
    mesh: NativePreviewMesh,
): SegmentationCascade.Input {
    // ---- A: per-triangle paint state from preview mesh.extruderIndices (when paint state was
    //         already baked into the mesh — see B47 / H2C path). For STL/non-painted models the
    //         array stays all-zero, which the cascade will skip.
    val perTriPaint = mesh.extruderIndices ?: ByteArray(triCount)

    // ---- B: per-volume listing from native JSON.
    val volumeJson = runCatching { native.nativeGetAllVolumeExtruders() }.getOrNull()
    val objectVolumes = parseObjectVolumes(volumeJson, perTriPaint, mesh.volumeRanges)

    // ---- C: per-object summary from native JSON.
    val objectJson = runCatching { native.nativeGetObjectExtruderMap() }.getOrNull()
    val objects = parseObjectInfos(objectJson, triCount, mesh.volumeRanges)

    // ---- D: defensive — use the same extruderIndices the renderer would use.
    val perTriIndex = mesh.extruderIndices ?: ByteArray(triCount)

    return SegmentationCascade.Input(
        positions = positions,
        perTrianglePaintState = perTriPaint,
        volumes = objectVolumes,
        objects = objects,
        perTriangleIndex = perTriIndex,
    )
}

/** Parse the JSON returned by NativeLibrary.nativeGetAllVolumeExtruders() into the cascade
 *  input shape. Returns empty list when the JSON is missing or empty. The triangle-range
 *  attribution uses `mesh.volumeRanges` (cumulative volume triangle counts) — see open
 *  follow-up in the spec for verification. */
private fun parseObjectVolumes(
    json: String?,
    perTrianglePaint: ByteArray,
    volumeRanges: List<IntRange>?,
): List<SegmentationCascade.ObjectVolumes> {
    if (json.isNullOrBlank()) return emptyList()
    val ranges = volumeRanges ?: return emptyList()
    val arr = runCatching { org.json.JSONArray(json) }.getOrNull() ?: return emptyList()
    val out = mutableListOf<SegmentationCascade.ObjectVolumes>()
    var volumeCursor = 0
    for (o in 0 until arr.length()) {
        val obj = arr.getJSONObject(o)
        val objId = obj.optLong("objectIndex").coerceAtLeast(o.toLong())
        val objName = obj.optString("objectName", "Object ${o + 1}").takeIf { it.isNotBlank() }
            ?: "Object ${o + 1}"
        val volsArr = obj.optJSONArray("volumes") ?: continue
        val vols = mutableListOf<SegmentationCascade.VolumeInfo>()
        for (v in 0 until volsArr.length()) {
            val vobj = volsArr.getJSONObject(v)
            val ext = vobj.optInt("extruder", -1).takeIf { it > 0 }
            val range = ranges.getOrNull(volumeCursor) ?: continue
            vols += SegmentationCascade.VolumeInfo(
                volumeIndex = v,
                extruder = ext,
                triangleIds = (range.first..range.last).toList().toIntArray(),
            )
            volumeCursor++
        }
        if (vols.isNotEmpty()) out += SegmentationCascade.ObjectVolumes(objId, objName, vols)
    }
    return out
}

/** Parse the JSON returned by NativeLibrary.nativeGetObjectExtruderMap() into the cascade
 *  input shape. Cumulative triangle ranges per object come from mesh.volumeRanges grouped by
 *  the parseObjectVolumes pass. When ranges are missing we return an empty list (forces the
 *  cascade to skip Branch C). */
private fun parseObjectInfos(
    json: String?,
    triCount: Int,
    volumeRanges: List<IntRange>?,
): List<SegmentationCascade.ObjectInfo> {
    if (json.isNullOrBlank() || volumeRanges.isNullOrEmpty()) return emptyList()
    val arr = runCatching { org.json.JSONArray(json) }.getOrNull() ?: return emptyList()
    val ranges = volumeRanges
    var rangeCursor = 0
    val out = mutableListOf<SegmentationCascade.ObjectInfo>()
    for (o in 0 until arr.length()) {
        val obj = arr.getJSONObject(o)
        val id = obj.optLong("objectId", o.toLong())
        val name = obj.optString("name", "Object ${o + 1}").ifBlank { "Object ${o + 1}" }
        val extruder = obj.optInt("extruder", -1).takeIf { it > 0 }
        // Best effort: take the next object-sized range chunk. This is the open-follow-up;
        // a future per-triangle volume-index accessor would simplify this.
        val volumeCount = obj.optInt("volumeCount", 1).coerceAtLeast(1)
        val objRanges = (0 until volumeCount).mapNotNull { ranges.getOrNull(rangeCursor + it) }
        rangeCursor += volumeCount
        if (objRanges.isEmpty()) continue
        val tris = objRanges.flatMap { (it.first..it.last).toList() }.toIntArray()
        out += SegmentationCascade.ObjectInfo(
            objectId = id,
            name = name,
            extruder = extruder,
            triangleIds = tris,
        )
    }
    return out
}
```

> ### Note on `mesh.volumeRanges`
>
> `NativePreviewMesh.volumeRanges` does not exist today. Add it to `NativePreviewMesh` as a non-null
> `List<IntRange>? = null` field populated from the native build by accumulating triangle counts in
> volume order. If that requires a JNI rebuild (cheap — header-only ABI change), follow the
> "Native Rebuild" instructions in `CLAUDE.md`. If the existing JNI already exposes per-volume
> triangle counts via another path, prefer that; otherwise:
>
> 1. Add to `app/src/main/java/com/u1/slicer/viewer/NativePreviewMesh.kt`:
>
>    ```kotlin
>    val volumeRanges: List<IntRange>? = null,
>    ```
>
> 2. Populate from the native preview mesh build (search `getPreparePreviewMesh` callers). The
>    existing native pipeline already iterates volumes in order to build the unified mesh; the
>    accumulated `triangleStart..triangleStart + volumeTriCount - 1` is what we need.

- [ ] **Step 2: Re-implement the per-tree ops the screen calls into**

Replace the stubbed `paintTriangles`, `commitSelection`, `setSegmentSlot`, `moveComponent`, `updateRegionColour`, `undo`, `beginUndoCheckpoint` with tree-aware bodies:

```kotlin
fun paintTriangles(triangleIndices: List<Int>, toSlot: Int) {
    if (triangleIndices.isEmpty()) return
    val current = _uiState.value as? AiPaintUiState.Result ?: return
    val state = current.state
    if (toSlot !in 0 until SegmentationCascade.TARGET_SLOTS) return
    val newTriRegions = state.triangleRegions.copyOf()
    val slot = toSlot.toByte()
    for (t in triangleIndices) if (t in newTriRegions.indices) newTriRegions[t] = slot
    val sel = state.customSelections + CustomSelection(
        id = (state.customSelections.maxOfOrNull { it.id } ?: -1) + 1,
        slot = toSlot,
        triangleIds = triangleIndices.toIntArray(),
    )
    _uiState.value = AiPaintUiState.Result(
        state.copy(
            triangleRegions = newTriRegions,
            customSelections = sel,
            canUndo = true,
        )
    )
}

fun commitSelection(triangleIndices: List<Int>, toSlot: Int) = paintTriangles(triangleIndices, toSlot)

fun setSegmentSlot(segmentId: Int, newSlot: Int) {
    val current = _uiState.value as? AiPaintUiState.Result ?: return
    val state = current.state
    if (newSlot !in 0 until SegmentationCascade.TARGET_SLOTS) return
    pushUndo(state.triangleRegions)
    val newTriRegions = state.triangleRegions.copyOf()
    // Rebuild tree with the targeted node's slot updated; propagate to triangleRegions.
    val newTree = state.tree.map { root -> reassignSlot(root, segmentId, newSlot, newTriRegions) }
    _uiState.value = AiPaintUiState.Result(
        state.copy(tree = newTree, triangleRegions = newTriRegions, canUndo = true)
    )
}

fun cascadeReassign(nodeIdPath: List<Int>, newSlot: Int) {
    val current = _uiState.value as? AiPaintUiState.Result ?: return
    val state = current.state
    pushUndo(state.triangleRegions)
    val newTriRegions = state.triangleRegions.copyOf()
    val newTree = state.tree.map { root -> reassignSubtree(root, nodeIdPath, newSlot, newTriRegions) }
    _uiState.value = AiPaintUiState.Result(
        state.copy(tree = newTree, triangleRegions = newTriRegions, canUndo = true)
    )
}

fun moveComponent(componentId: Int, toRegion: Int) = setSegmentSlot(componentId, toRegion)

fun updateRegionColour(regionId: Int, hexColour: String) {
    val current = _uiState.value as? AiPaintUiState.Result ?: return
    val newTree = current.state.tree.map { root -> recolorNode(root, regionId, hexColour) }
    _uiState.value = AiPaintUiState.Result(current.state.copy(tree = newTree))
}

private fun pushUndo(snapshot: ByteArray) {
    undoStack.addLast(snapshot.copyOf())
    while (undoStack.size > 50) undoStack.removeFirst()
}

fun beginUndoCheckpoint() {
    val current = _uiState.value as? AiPaintUiState.Result ?: return
    pushUndo(current.state.triangleRegions)
    if (!current.state.canUndo) {
        _uiState.value = AiPaintUiState.Result(current.state.copy(canUndo = true))
    }
}

fun undo() {
    val current = _uiState.value as? AiPaintUiState.Result ?: return
    val state = current.state
    val prev = undoStack.removeLastOrNull() ?: return
    if (prev.size != state.triangleRegions.size) return
    _uiState.value = AiPaintUiState.Result(
        state.copy(
            triangleRegions = prev,
            canUndo = undoStack.isNotEmpty(),
        )
    )
}

/** Recursive tree rewriter — finds the node whose region.id == targetId and updates its slot.
 *  Also rewrites triangleRegions for that node's triangleIds. */
private fun reassignSlot(node: AiRegionNode, targetId: Int, newSlot: Int, out: ByteArray): AiRegionNode {
    if (node.region.id == targetId) {
        node.triangleIds.forEach { t -> if (t in out.indices) out[t] = newSlot.toByte() }
        return node.copy(region = node.region.copy(slot = newSlot))
    }
    return node.copy(children = node.children.map { reassignSlot(it, targetId, newSlot, out) })
}

/** Recursive tree rewriter — finds the node at path[last] and sets the slot of EVERY leaf
 *  under it. Used by cascade-reassign on parent rows. */
private fun reassignSubtree(node: AiRegionNode, path: List<Int>, newSlot: Int, out: ByteArray): AiRegionNode {
    if (path.isEmpty()) return node
    if (node.region.id == path.last()) {
        // Reassign every leaf under this node.
        fun visit(n: AiRegionNode): AiRegionNode {
            if (n.isLeaf) {
                n.triangleIds.forEach { t -> if (t in out.indices) out[t] = newSlot.toByte() }
                return n.copy(region = n.region.copy(slot = newSlot))
            }
            return n.copy(children = n.children.map(::visit))
        }
        return visit(node).let { rewritten ->
            // Also update parent's own slot to dominant child.
            rewritten.copy(region = rewritten.region.copy(slot = newSlot))
        }
    }
    return node.copy(children = node.children.map { reassignSubtree(it, path, newSlot, out) })
}

private fun recolorNode(node: AiRegionNode, targetId: Int, hex: String): AiRegionNode {
    if (node.region.id == targetId) return node.copy(region = node.region.copy(userColour = hex))
    return node.copy(children = node.children.map { recolorNode(it, targetId, hex) })
}
```

- [ ] **Step 3: Add the AI-naming post-pass**

Replace `applyAiNaming` placeholder:

```kotlin
private suspend fun applyAiNaming(
    provider: AiPaintProvider,
    apiKey: String,
    positions: FloatArray,
    tree: List<AiRegionNode>,
): Triple<List<AiRegionNode>, Boolean, String?> {
    val leaves = tree.flatMap { it.flatten().filter { (n, _) -> n.isLeaf }.map { it.first } }
    if (leaves.isEmpty()) return Triple(tree, false, null)

    val shaded = withContext(Dispatchers.Default) {
        AiPaintRenderer.renderShaded(positions, 512, 512, CameraAngle.RIGHT_ISO)
    }
    val perTriRegion = IntArray(positions.size / 9)
    leaves.forEachIndexed { idx, leaf ->
        leaf.triangleIds.forEach { t -> if (t in perTriRegion.indices) perTriRegion[t] = idx }
    }
    val palette = leaves.mapIndexed { i, _ ->
        runCatching { android.graphics.Color.parseColor(SegmentationCascade.paletteFor(i)) }
            .getOrDefault(android.graphics.Color.GRAY)
    }.toIntArray()
    val banded = withContext(Dispatchers.Default) {
        AiPaintRenderer.renderRegions(positions, perTriRegion, palette, 512, 512, CameraAngle.RIGHT_ISO)
    }
    val names = AiLabelClient.labelSegments(provider, apiKey, listOf(shaded, banded), leaves.size)
        ?: return Triple(tree, true, AiLabelClient.lastModel)

    if (names.size != leaves.size) return Triple(tree, true, AiLabelClient.lastModel)
    // Apply names back to leaves.
    val nameById: Map<Int, NamedColour> = leaves.mapIndexed { i, leaf -> leaf.region.id to names[i] }.toMap()
    val renamed = tree.map { root -> applyNames(root, nameById) }
    return Triple(renamed, false, AiLabelClient.lastModel)
}

private fun applyNames(node: AiRegionNode, names: Map<Int, NamedColour>): AiRegionNode {
    val named = names[node.region.id]
    val updated = if (named != null) {
        node.region.copy(label = named.label, suggestedColour = named.colour)
    } else node.region
    return node.copy(
        region = updated,
        children = node.children.map { applyNames(it, names) },
    )
}
```

- [ ] **Step 4: Build the project**

```bash
./gradlew assembleDebug --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run unit tests; verify nothing regressed**

```bash
./gradlew testDebugUnitTest --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/u1/slicer/aipaint/AiPaintViewModel.kt \
        app/src/main/java/com/u1/slicer/viewer/NativePreviewMesh.kt
git commit -m "F54 plan task 12: ViewModel pipeline cutover to SegmentationCascade"
```

---

## Task 13: Settings — `aiNamingEnabled` toggle

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/data/SettingsRepository.kt`
- Modify: `app/src/main/java/com/u1/slicer/ui/SettingsScreen.kt`

- [ ] **Step 1: Add the flow + setter to `SettingsRepository`**

Find the existing `aiPaintProvider: Flow<String>` declaration; immediately below it, add:

```kotlin
private val AI_NAMING_ENABLED = booleanPreferencesKey("ai_naming_enabled")

val aiNamingEnabled: Flow<Boolean> = dataStore.data.map { it[AI_NAMING_ENABLED] ?: false }

suspend fun saveAiNamingEnabled(enabled: Boolean) {
    dataStore.edit { it[AI_NAMING_ENABLED] = enabled }
}
```

- [ ] **Step 2: Wire the toggle into `SettingsScreen`**

In `SettingsScreen.kt`, find the AI Paint section (where `aiPaintProvider` is rendered). Add the toggle row immediately above it. Pattern:

```kotlin
val aiNamingEnabled by viewModel.aiNamingEnabled.collectAsState(initial = false)
ListItem(
    headlineContent = { Text("AI naming (experimental)") },
    supportingContent = {
        Text("Send rendered views to the AI for label + colour suggestions.")
    },
    trailingContent = {
        Switch(
            checked = aiNamingEnabled,
            onCheckedChange = { viewModel.saveAiNamingEnabled(it) },
        )
    },
)
```

Add `aiNamingEnabled` to the screen's view model accessor (likely `SlicerViewModel` or a dedicated `SettingsViewModel`) — match the same flow + setter pattern already used by `aiPaintProvider`. If the setter is named `saveAiPaintProvider`, name the new one `saveAiNamingEnabled` for consistency.

- [ ] **Step 3: Build and verify the screen compiles**

```bash
./gradlew assembleDebug --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run unit tests to check no regression**

```bash
./gradlew testDebugUnitTest --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/data/SettingsRepository.kt \
        app/src/main/java/com/u1/slicer/ui/SettingsScreen.kt \
        app/src/main/java/com/u1/slicer/SlicerViewModel.kt
git commit -m "F54 plan task 13: aiNamingEnabled setting + Experimental toggle"
```

---

## Task 14: Mixed-slot diagonal swatch composable

**Files:**
- Create: `app/src/main/java/com/u1/slicer/ui/MixedSlotSwatch.kt`

- [ ] **Step 1: Implement the composable**

```kotlin
// app/src/main/java/com/u1/slicer/ui/MixedSlotSwatch.kt
package com.u1.slicer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A two-tone swatch: the dominant slot's colour fills the whole square; the secondary slot's
 * colour is drawn as a diagonal stripe (upper-right corner triangle). Falls back to a single
 * colour when only one slot is present in the underlying leaves.
 */
@Composable
fun MixedSlotSwatch(
    primary: Color,
    secondary: Color?,
    size: Dp = 36.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(size)
            .background(primary, MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) {
        if (secondary != null) {
            Canvas(modifier = Modifier.size(size)) {
                val w = this.size.width
                val h = this.size.height
                val path = Path().apply {
                    moveTo(w * 0.55f, 0f)
                    lineTo(w, 0f)
                    lineTo(w, h * 0.45f)
                    close()
                }
                drawPath(path, secondary)
            }
        }
    }
}
```

- [ ] **Step 2: Build to verify it compiles**

```bash
./gradlew assembleDebug --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/u1/slicer/ui/MixedSlotSwatch.kt
git commit -m "F54 plan task 14: MixedSlotSwatch composable"
```

---

## Task 15: `AiPaintTreeRow` composable

**Files:**
- Create: `app/src/main/java/com/u1/slicer/ui/AiPaintTreeRow.kt`

- [ ] **Step 1: Implement**

```kotlin
// app/src/main/java/com/u1/slicer/ui/AiPaintTreeRow.kt
package com.u1.slicer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.u1.slicer.aipaint.AiRegionNode

@Composable
fun AiPaintTreeRow(
    node: AiRegionNode,
    depth: Int,
    onToggleExpand: () -> Unit,
    onTapSwatch: () -> Unit,
    onPickSlot: (slot: Int) -> Unit,
    slotPalette: List<Color>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = (12 * depth).dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
            .heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Chevron — only on parents with children.
        if (node.children.isNotEmpty()) {
            Icon(
                imageVector = if (node.expanded) Icons.Default.KeyboardArrowDown
                              else Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .clickable { onToggleExpand() },
            )
            Spacer(Modifier.width(6.dp))
        } else {
            Spacer(Modifier.width(26.dp))
        }

        // Swatch (mixed for parents, single for leaves).
        val primary = remember(node) {
            val argb = runCatching { android.graphics.Color.parseColor(node.region.effectiveColour) }
                .getOrDefault(android.graphics.Color.GRAY)
            Color(argb)
        }
        val secondary = if (node.isLeaf) null else node.secondarySlot()?.let { slotPalette.getOrNull(it) }
        MixedSlotSwatch(
            primary = primary,
            secondary = secondary,
            size = 32.dp,
            modifier = Modifier.clickable { onTapSwatch() },
        )
        Spacer(Modifier.width(10.dp))

        // Label + coverage %.
        Column(modifier = Modifier.weight(1f)) {
            Text(node.region.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${"%.0f".format(node.region.coverageFraction * 100)}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 4 slot chips.
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            slotPalette.forEachIndexed { slot, color ->
                val isActive = node.region.slot == slot
                Box(
                    Modifier
                        .size(if (isActive) 24.dp else 20.dp)
                        .background(color, MaterialTheme.shapes.small)
                        .clickable { onPickSlot(slot) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isActive) {
                        Text("✓", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Build to verify**

```bash
./gradlew assembleDebug --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/u1/slicer/ui/AiPaintTreeRow.kt
git commit -m "F54 plan task 15: AiPaintTreeRow composable"
```

---

## Task 16: `AiPaintTree` composable with expand/collapse + auto-rules

**Files:**
- Create: `app/src/main/java/com/u1/slicer/ui/AiPaintTree.kt`

- [ ] **Step 1: Implement**

```kotlin
// app/src/main/java/com/u1/slicer/ui/AiPaintTree.kt
package com.u1.slicer.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.u1.slicer.aipaint.AiRegionNode

/**
 * Top-level Compose surface for the segmentation tree. Flattens [tree] into a LazyColumn,
 * tracks per-node expand state, and bridges row callbacks to the screen-level handlers.
 *
 * Auto-rules:
 *   - leafCount ≤ 8 → start fully expanded
 *   - leafCount > 20 → start collapsed to depth 1 (root visible, children hidden)
 */
@Composable
fun AiPaintTree(
    tree: List<AiRegionNode>,
    slotPalette: List<Color>,
    onTapSwatch: (nodeId: Int) -> Unit,
    onPickSlot: (path: List<Int>, slot: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalLeaves = remember(tree) { tree.sumOf { it.leafCount() } }
    val initialExpand = remember(tree) {
        when {
            totalLeaves <= 8 -> ExpandPolicy.AllExpanded
            totalLeaves > 20 -> ExpandPolicy.Depth1
            else -> ExpandPolicy.AllExpanded
        }
    }
    val expanded = remember(tree, initialExpand) {
        mutableStateMapOf<Int, Boolean>().also {
            applyInitial(tree, initialExpand, it, depth = 0)
        }
    }
    val flat = remember(tree, expanded.toMap()) {
        tree.flatMap { root -> flattenVisible(root, expanded, 0, mutableListOf(), parentPath = emptyList()) }
    }
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(flat, key = { (node, _, _) -> node.region.id }) { (node, depth, path) ->
            AiPaintTreeRow(
                node = node.copy(expanded = expanded[node.region.id] ?: true),
                depth = depth,
                onToggleExpand = { expanded[node.region.id] = !(expanded[node.region.id] ?: true) },
                onTapSwatch = { onTapSwatch(node.region.id) },
                onPickSlot = { slot -> onPickSlot(path, slot) },
                slotPalette = slotPalette,
            )
            HorizontalDivider()
        }
    }
}

private enum class ExpandPolicy { AllExpanded, Depth1 }

private fun applyInitial(
    nodes: List<AiRegionNode>,
    policy: ExpandPolicy,
    out: MutableMap<Int, Boolean>,
    depth: Int,
) {
    nodes.forEach { node ->
        val shouldExpand = when (policy) {
            ExpandPolicy.AllExpanded -> true
            ExpandPolicy.Depth1 -> depth < 1
        }
        out[node.region.id] = shouldExpand
        applyInitial(node.children, policy, out, depth + 1)
    }
}

private fun flattenVisible(
    node: AiRegionNode,
    expanded: Map<Int, Boolean>,
    depth: Int,
    @Suppress("UNUSED_PARAMETER") scratch: MutableList<Triple<AiRegionNode, Int, List<Int>>>,
    parentPath: List<Int>,
): List<Triple<AiRegionNode, Int, List<Int>>> {
    val path = parentPath + node.region.id
    val out = mutableListOf<Triple<AiRegionNode, Int, List<Int>>>()
    out += Triple(node, depth, path)
    if (expanded[node.region.id] != false) {
        node.children.forEach { c ->
            out += flattenVisible(c, expanded, depth + 1, scratch, path)
        }
    }
    return out
}
```

- [ ] **Step 2: Build**

```bash
./gradlew assembleDebug --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/u1/slicer/ui/AiPaintTree.kt
git commit -m "F54 plan task 16: AiPaintTree composable with auto-expand/collapse"
```

---

## Task 17: Result-screen wire-up (replace LazyColumn with AiPaintTree)

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt`
- Modify: `app/src/main/java/com/u1/slicer/navigation/NavGraph.kt`

- [ ] **Step 1: Replace the region LazyColumn**

In `AiPaintResultScreen.kt`, find the existing `LazyColumn(Modifier.weight(1f)) { items(result.leafRegions) { ... } }` block (or the temporary stub left by Task 4). Replace with:

```kotlin
val slotPalette = remember(result.tree) {
    // The four canonical slot colours, derived from the tree's depth-1 (or root) leaves
    // sorted by their slot field. Falls back to a default palette when slot coverage is
    // sparse.
    val byTris = result.leafRegions.groupBy { it.slot }
    (0 until com.u1.slicer.aipaint.SegmentationCascade.TARGET_SLOTS).map { slot ->
        val rep = byTris[slot]?.firstOrNull()
        val hex = rep?.effectiveColour ?: "#888888"
        androidx.compose.ui.graphics.Color(
            runCatching { android.graphics.Color.parseColor(hex) }
                .getOrDefault(android.graphics.Color.GRAY)
        )
    }
}

if (result.aiNamingFailed) {
    AiNamingFailureChip(
        modelTried = result.aiModelTried,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

AiPaintTree(
    tree = result.tree + listOfNotNull(
        com.u1.slicer.aipaint.CustomSelections.buildGroup(result.customSelections)
    ),
    slotPalette = slotPalette,
    onTapSwatch = { nodeId -> editSlotColour = nodeId },
    onPickSlot = { path, slot ->
        // If the node has children → cascade-reassign. Else point-reassign.
        viewModel.cascadeReassign(path, slot)
    },
    modifier = Modifier.weight(1f),
)
```

Add the helper composable `AiNamingFailureChip` to the bottom of the file:

```kotlin
@Composable
private fun AiNamingFailureChip(modelTried: String?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "AI naming unavailable — using default labels" +
                (modelTried?.let { " ($it)" } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}
```

Imports needed (add to the top of the file if missing):

```kotlin
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.draw.clip
```

- [ ] **Step 2: Wire the new ViewModel callbacks via NavGraph**

In `NavGraph.kt`, find the `AiPaintResultScreen(...)` call inside the AI Paint route. Pass through:

```kotlin
viewModel = aiVm,    // pass the ViewModel; the screen reads viewModel.uiState
```

If the screen still takes separate callbacks, replace them with:

- `onSetSegmentSlot = { segId, slot -> aiVm.setSegmentSlot(segId, slot) }` → stays.
- `onMoveComponent` → can be dropped now that tap-to-move funnels through `setSegmentSlot`.
- Add `onCascadeReassign = { path, slot -> aiVm.cascadeReassign(path, slot) }`.

Use whichever pattern the existing call already follows; consistency over re-architecting.

- [ ] **Step 3: Build**

```bash
./gradlew assembleDebug --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Install on device + manual smoke**

```bash
ANDROID_SERIAL=43211JEKB16931 adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Smoke test on the device:
1. Load `3DBenchy.stl` → AI Paint screen → tree should show "Model" root with up to 12 topology leaves (sub-regions if recursion fired).
2. Load `colored_3DBenchy.3mf` → tree should show paint-state leaves; the source = PAINT_STATE.
3. Load a multi-volume Bambu (e.g. Dragon Scale plate 3) → tree should show "Object · volume N" leaves nested under their parent.

Note: if Branch B's per-volume triangle attribution doesn't line up with the rendered mesh (the open follow-up from spec §12), some leaves may colour the wrong triangles. That's expected and addressed in Task 18.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt \
        app/src/main/java/com/u1/slicer/navigation/NavGraph.kt
git commit -m "F54 plan task 17: replace LazyColumn with AiPaintTree + AI failure chip"
```

---

## Task 18: Verify (and fix if needed) triangle→volume attribution

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/viewer/NativePreviewMesh.kt`
- Possibly: `app/src/main/cpp/src/sapil_*.cpp` + Kotlin JNI binding if a new accessor is needed

**Goal:** confirm that the `volumeRanges` list assembled in Task 12 actually matches the per-volume triangle layout of the unified preview mesh, OR add a native accessor that returns per-triangle volume index directly.

- [ ] **Step 1: Write an instrumented test**

Create `app/src/androidTest/java/com/u1/slicer/aipaint/VolumeAttributionTest.kt`:

```kotlin
package com.u1.slicer.aipaint

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.viewer.NativePreviewMesh
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VolumeAttributionTest {

    /** Load Dragon Scale plate 3 (1 object, 3 volumes); confirm cumulative volume triangle
     *  counts match the unified preview mesh size and yield disjoint, exhaustive ranges. */
    @Test
    fun dragonScalePlate3_volumeRangesCoverFullMesh() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = "Dragon Scale infinity.3mf"
        val outFile = File(ctx.cacheDir, fixture)
        ctx.assets.open(fixture).use { input ->
            outFile.outputStream().use { input.copyTo(it) }
        }

        val lib = NativeLibrary()
        assertTrue(lib.loadModelForPlate(outFile.absolutePath, plateIdx = 2))

        val mesh = lib.getPreparePreviewMesh(maxTriangles = NativePreviewMesh.MAX_DECIMATED_TRIANGLES)
        assertNotNull(mesh)
        val triCount = mesh!!.trianglePositions.size / 9

        val ranges = mesh.volumeRanges
        assertNotNull("volumeRanges must be populated after fix33→fix34", ranges)
        val r = ranges!!
        assertTrue("must have ≥ 2 ranges for Dragon Scale plate 3", r.size >= 2)

        // Disjoint + exhaustive: ranges sum to triCount; no overlaps.
        val sum = r.sumOf { it.last - it.first + 1 }
        assertEquals(triCount, sum)
        val sorted = r.sortedBy { it.first }
        for (i in 1 until sorted.size) {
            assertEquals(
                "ranges must be contiguous: ${sorted[i - 1]} → ${sorted[i]}",
                sorted[i - 1].last + 1, sorted[i].first
            )
        }
    }
}
```

- [ ] **Step 2: Run on device**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.aipaint.VolumeAttributionTest \
    --no-daemon
```

- [ ] **Step 3: Implement the fix based on what the test reveals**

Three cases:

**Case A — test passes:** `volumeRanges` already exposed and correct. Nothing to do. Move on.

**Case B — `volumeRanges` is null:** `NativePreviewMesh` needs the field populated. Locate where `getPreparePreviewMesh` is built in `app/src/main/cpp/src/sapil_preview_mesh.cpp` (or equivalent). Accumulate per-volume triangle counts and return them as a flat `int[2*N]` array; expose via a new JNI accessor `nativeGetPreviewMeshVolumeRanges()` returning `IntArray`. Parse into `List<IntRange>` Kotlin-side.

A native rebuild IS required for this case. Follow the rebuild checklist in `CLAUDE.md`:

```bash
$ANDROID_NDK_HOME=...   # NDK 26
cd app/.cxx/Debug/<existing-build-dir>/arm64-v8a
# Copy worktree-modified files into the bound source tree first (see CLAUDE.md§"CRITICAL when building from a worktree")
cmake .
ninja -j1
# Strip + copy to jniLibs/arm64-v8a/
```

Verify size ~20MB, compiler clang 17, JNI symbol count matches.

**Case C — ranges exist but don't cover the mesh:** the unified preview mesh has been decimated or merged. Either:
- Adjust the cascade to operate on the pre-decimated mesh (preferred — see `NativePreviewMesh.MAX_DECIMATED_TRIANGLES`), OR
- Add a JNI accessor that returns per-triangle volume index post-decimation.

Pick whichever is less invasive given what the test reveals.

- [ ] **Step 4: Re-run the instrumented test until it passes**

- [ ] **Step 5: Commit**

```bash
git add -p     # be explicit about what's changing
git commit -m "F54 plan task 18: volume triangle attribution verified (and fixed if needed)"
```

---

## Task 19: Clean up `AiLabelClient`

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/aipaint/AiLabelClient.kt`
- Modify: `app/src/test/java/com/u1/slicer/aipaint/AiLabelClientTest.kt`

- [ ] **Step 1: Remove unused methods**

In `AiLabelClient.kt`, delete:
- `buildGroupPrompt`
- `labelGroups`
- `parseGroupJson`
- `componentDisplayColors`
- `hsvToArgb`

Keep:
- `labelSegments`
- `buildLabelPrompt`
- `parseLabelJson`
- `buildRequest`, `buildOpenAiStyleRequest`, `buildGeminiRequest`, `buildClaudeRequest`, `extractTextFromResponse`, `bitmapToJpeg`
- `NamedColour`
- `lastRaw`, `lastModel`

- [ ] **Step 2: Update `AiLabelClientTest`**

Remove every test that referenced a deleted method. Keep tests for `parseLabelJson`, `buildLabelPrompt`, provider key handling.

- [ ] **Step 3: Run unit tests**

```bash
./gradlew testDebugUnitTest --tests com.u1.slicer.aipaint.AiLabelClientTest --no-daemon
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/u1/slicer/aipaint/AiLabelClient.kt \
        app/src/test/java/com/u1/slicer/aipaint/AiLabelClientTest.kt
git commit -m "F54 plan task 19: drop unused topology-grouping AI methods"
```

---

## Task 20: Integration tests — fixture coverage

**Files:**
- Create: `app/src/androidTest/java/com/u1/slicer/aipaint/SegmentationCascadeIntegrationTest.kt`

- [ ] **Step 1: Write fixture-end-to-end tests**

```kotlin
// app/src/androidTest/java/com/u1/slicer/aipaint/SegmentationCascadeIntegrationTest.kt
package com.u1.slicer.aipaint

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.viewer.NativePreviewMesh
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SegmentationCascadeIntegrationTest {

    private fun copyAsset(name: String): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(ctx.cacheDir, name)
        ctx.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    private fun loadAndRunCascade(file: File, plateIdx: Int = -1): CascadeResult {
        val lib = NativeLibrary()
        assertTrue(lib.loadModelForPlate(file.absolutePath, plateIdx = plateIdx))
        val mesh = lib.getPreparePreviewMesh(maxTriangles = NativePreviewMesh.MAX_DECIMATED_TRIANGLES)
        assertNotNull(mesh)
        // Build an Input using the same logic the ViewModel uses; for testing we inline the
        // construction. The actual ViewModel path is covered by the on-device smoke tests.
        val triCount = mesh!!.trianglePositions.size / 9
        val perTriPaint = mesh.extruderIndices ?: ByteArray(triCount)
        val volumeJson = runCatching { lib.nativeGetAllVolumeExtruders() }.getOrNull()
        val objectJson = runCatching { lib.nativeGetObjectExtruderMap() }.getOrNull()
        val ranges = mesh.volumeRanges ?: emptyList()
        val volumes = parseObjectVolumesForTest(volumeJson, ranges)
        val objects = parseObjectInfosForTest(objectJson, triCount, ranges)
        return SegmentationCascade.run(
            SegmentationCascade.Input(
                positions = mesh.trianglePositions,
                perTrianglePaintState = perTriPaint,
                volumes = volumes,
                objects = objects,
                perTriangleIndex = mesh.extruderIndices ?: ByteArray(triCount),
            )
        )
    }

    @Test
    fun coloredBenchy_paintStateBranch() {
        val r = loadAndRunCascade(copyAsset("colored_3DBenchy (1).3mf"))
        assertEquals(SegmentationSource.PAINT_STATE, r.source)
        assertTrue("expected ≥ 2 leaves, got ${r.tree.firstOrNull()?.leafCount() ?: 0}",
            (r.tree.firstOrNull()?.leafCount() ?: 0) >= 2)
    }

    @Test
    fun h2cBenchy_paintStateBranch_7States() {
        val r = loadAndRunCascade(copyAsset("3DBenchy-H2C-Multi-Color.3mf"))
        assertEquals(SegmentationSource.PAINT_STATE, r.source)
        val leafCount = r.tree.firstOrNull()?.leafCount() ?: 0
        assertTrue("expected ≥ 7 H2C leaves, got $leafCount", leafCount >= 7)
    }

    @Test
    fun dragonScalePlate3_volumeBranch() {
        val r = loadAndRunCascade(copyAsset("Dragon Scale infinity.3mf"), plateIdx = 2)
        assertEquals(SegmentationSource.VOLUME, r.source)
    }

    @Test
    fun rawBenchyStl_topologyOrZBands() {
        val r = loadAndRunCascade(copyAsset("3DBenchy.stl"))
        assertTrue(
            "expected topology* or Z-band, got ${r.source}",
            r.source in listOf(
                SegmentationSource.TOPOLOGY,
                SegmentationSource.TOPOLOGY_RECURSIVE,
                SegmentationSource.Z_BAND,
            )
        )
    }

    // Helpers — duplicate of the ViewModel parsers, kept local to keep test deterministic.
    // Reusing AiPaintViewModel's private parsers would couple the test to its lifecycle.

    private fun parseObjectVolumesForTest(json: String?, ranges: List<IntRange>): List<SegmentationCascade.ObjectVolumes> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = runCatching { org.json.JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<SegmentationCascade.ObjectVolumes>()
        var volumeCursor = 0
        for (o in 0 until arr.length()) {
            val obj = arr.getJSONObject(o)
            val objId = obj.optLong("objectIndex").coerceAtLeast(o.toLong())
            val name = obj.optString("objectName", "Object ${o + 1}").ifBlank { "Object ${o + 1}" }
            val volsArr = obj.optJSONArray("volumes") ?: continue
            val vols = mutableListOf<SegmentationCascade.VolumeInfo>()
            for (v in 0 until volsArr.length()) {
                val vobj = volsArr.getJSONObject(v)
                val ext = vobj.optInt("extruder", -1).takeIf { it > 0 }
                val range = ranges.getOrNull(volumeCursor) ?: continue
                vols += SegmentationCascade.VolumeInfo(
                    volumeIndex = v,
                    extruder = ext,
                    triangleIds = (range.first..range.last).toList().toIntArray(),
                )
                volumeCursor++
            }
            if (vols.isNotEmpty()) out += SegmentationCascade.ObjectVolumes(objId, name, vols)
        }
        return out
    }

    private fun parseObjectInfosForTest(json: String?, triCount: Int, ranges: List<IntRange>): List<SegmentationCascade.ObjectInfo> {
        if (json.isNullOrBlank() || ranges.isEmpty()) return emptyList()
        val arr = runCatching { org.json.JSONArray(json) }.getOrNull() ?: return emptyList()
        var cursor = 0
        val out = mutableListOf<SegmentationCascade.ObjectInfo>()
        for (o in 0 until arr.length()) {
            val obj = arr.getJSONObject(o)
            val id = obj.optLong("objectId", o.toLong())
            val name = obj.optString("name", "Object ${o + 1}").ifBlank { "Object ${o + 1}" }
            val ext = obj.optInt("extruder", -1).takeIf { it > 0 }
            val volCount = obj.optInt("volumeCount", 1).coerceAtLeast(1)
            val objRanges = (0 until volCount).mapNotNull { ranges.getOrNull(cursor + it) }
            cursor += volCount
            if (objRanges.isEmpty()) continue
            out += SegmentationCascade.ObjectInfo(
                objectId = id, name = name, extruder = ext,
                triangleIds = objRanges.flatMap { (it.first..it.last).toList() }.toIntArray(),
            )
        }
        return out
    }
}
```

- [ ] **Step 2: Run on device**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.aipaint.SegmentationCascadeIntegrationTest \
    --no-daemon
```

Expected: all four tests PASS.

- [ ] **Step 3: If any test fails**

Read the cascade source enum reported and reason backward:
- Wrong branch fired → check the precondition logic in `SegmentationCascade.run`
- Branch fired but leaf count low → check the parse helpers (volumeRanges, JSON fields)
- The expected branch's preconditions weren't met → either fixture-specific (e.g. paint state field zeroed out) or a JNI gap

Fix root cause, re-run.

- [ ] **Step 4: Commit**

```bash
git add app/src/androidTest/java/com/u1/slicer/aipaint/SegmentationCascadeIntegrationTest.kt
git commit -m "F54 plan task 20: cascade fixture integration tests"
```

---

## Task 21: Full build + install + smoke

- [ ] **Step 1: Full unit suite**

```bash
./gradlew testDebugUnitTest --no-daemon
```

Expected: PASS.

- [ ] **Step 2: Full instrumented suite**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest --no-daemon
```

Expected: PASS. If a pre-existing instrumented test (e.g. Shashibo harness) fails, treat as a blocker per `CLAUDE.md` (no known pre-existing failures).

- [ ] **Step 3: Install debug APK on Pixel 8a + on-device smoke**

```bash
ANDROID_SERIAL=43211JEKB16931 adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Manual smoke checklist:
1. AI Paint provider Settings — verify the "AI naming (experimental)" toggle exists and starts OFF.
2. Load `3DBenchy.stl` → AI Paint → result screen should show tree with topology / recursion / Z-band leaves.
3. Load `colored_3DBenchy.3mf` → tree should show paint-state leaves; the 4-slot chips should be tappable per leaf.
4. Cascade-reassign smoke: on a multi-leaf tree, tap a parent's slot chip → all child leaves should flip to that slot; Undo snackbar appears; tapping Undo reverts.
5. Brush smoke: in Paint mode, paint over a few triangles → after the stroke ends, a new row appears under "Custom selections"; tapping its swatch opens HSV picker; tapping a slot chip reassigns it.
6. AI naming smoke: enable the toggle in Settings, run AI Paint with Gemini key configured → leaves should rename to semantic names (e.g. "Hull", "Cabin"). Disable the toggle → leaves revert to deterministic defaults (`Region 1..N` / `Band 1..N`).

- [ ] **Step 4: Stage the release APK to G Drive**

```bash
cp app/build/outputs/apk/debug/app-debug.apk "G:/My Drive/claude/u1-slicer-f54-redesign-debug.apk"
```

- [ ] **Step 5: Commit any final fixups + push branch**

```bash
git status                   # confirm nothing dangling
# If there are stragglers — fix or remove — and commit
git push                     # push the feature branch
```

---

## Self-Review (post-write)

Run through the spec sections in order; confirm every requirement maps to a task:

| Spec section | Task(s) | Notes |
|---|---|---|
| §Decisions table — H2C 7-state | T10 (paintStateBranch), T20 (H2C fixture test) | Test asserts ≥ 7 leaves. |
| §Decisions — Recursion | T7 (TopologyRecursion), T6 (topologyBranch wires it) | |
| §Decisions — Z-bands fallback only | T5 + T11 (cascade ordering) | Run terminates at branch F only when A–E yielded no segments. |
| §Decisions — AI failure chip | T17 (AiNamingFailureChip composable) | |
| §Decisions — Brush child rows | T3 (CustomSelections), T12 (paintTriangles wiring), T17 (tree includes group) | |
| §Decisions — AI gating | T13 (settings toggle + repository flow) | |
| §1 Cascade (6 branches) | T5, T6+7, T8, T9, T10 (×2 branches), T11 | All 6 branches + orchestrator. |
| §2 AI's reduced role | T12 (applyAiNaming), T19 (cleanup of dropped methods) | |
| §3 UI: expandable tree | T14, T15, T16, T17 | MixedSwatch + Row + Tree + Screen wire-up. |
| §4 Data model | T1, T2, T3, T4 | Enum + node + selections + state refactor. |
| §5 Pipeline rewrite | T12 | |
| §6 Slot mapping helper | Embedded in branches T8, T9, T10 | Each branch applies its own slot policy; central helper not needed once branches exist. |
| §7 Files & boundaries | All tasks correspond. | |
| §8 Error handling | T11 (cascade fallback), T17 (AI failure chip), T18 (attribution verification) | |
| §9 Testing — unit | T1, T2, T3, T5, T7, T8, T9, T10, T11, T19 | |
| §9 Testing — instrumented | T18, T20 | |
| §9 Testing — Compose UI | Compose UI tests aren't included as a separate task (no Compose UI test harness in this project per CLAUDE.md §ui/ModelInfoDialogScrollTest); functional coverage comes from manual smoke + the unit tests on AiRegionNode behaviour. | |
| §10 Out of scope | n/a (intentional non-goals) | |
| §11 Migration | T21 (single ship at end) | |
| §12 Open follow-ups — triangle attribution | T18 | |

**Placeholder scan:** searched plan for "TBD", "TODO", "later", "appropriate", "etc." — none found in plan steps (TODO appears only in Task 4 stubs which are intentionally replaced in Task 12, and "etc." appears only in commentary). ✓

**Type-name consistency:** `AiRegionNode` (T2) ↔ used in T3, T5–T11, T12, T15–T17. `SegmentationSource` enum values match across tasks. `CascadeResult` shape consistent (tree, triangleSegments, source). `Input` data class fields stable. ✓

**Spec coverage:** every section has a task; one note added under §9 about Compose UI tests being deferred (no harness exists).

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-14-ai-paint-redesign.md`. Two execution options:

**1. Subagent-Driven (recommended)** — fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — execute tasks in this session using executing-plans, batch execution with checkpoints.

The user has indicated no intermediate-ship pauses are needed; Inline Execution can run end-to-end with task-boundary checkpoints rather than ship gates.
