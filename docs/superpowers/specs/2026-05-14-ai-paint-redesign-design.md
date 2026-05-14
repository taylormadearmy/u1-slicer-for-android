# F54 AI Paint — Redesign Spec

**Date:** 2026-05-14
**Branch:** `feature/f54-ai-paint`
**Supersedes:** fix32 / fix33 ad-hoc iterations on the existing pipeline

## Goal

Replace the topology+AI-grouping pipeline with one that **reads the model's own structure first** (paint state, volumes, objects), uses topology only as a fallback for raw STL/OBJ, and surfaces the result as a single expandable tree the user can reassign, recolour, and overpaint. AI becomes optional, decorative, gated as Experimental, and never determines segmentation shape.

Brainstorm inputs that drove this design live alongside it at:
- `.superpowers/brainstorm/data-inventory.md`
- `.superpowers/brainstorm/segmentation-strategy.md`
- `.superpowers/brainstorm/ux-proposals.md`

## Why now

Current pipeline is non-deterministic — same goat sometimes shows part-coloured segments, sometimes flat Z-bands, depending on whether AI grouping parsed. The codebase already extracts per-object, per-volume, per-triangle paint metadata via JNI (`nativeGetAllVolumeExtruders`, `nativeGetPaintStateCounts`, `nativeGetObjectExtruderMap`) — none of which AI Paint touches today. Topology flood-fill merges parts that meet at shared surfaces (Benchy hull + cabin become one component), defeating the purpose. We are reinventing semantic structure that the file already labels.

## Decisions

| # | Decision | Notes |
|---|---|---|
| 1 | **H2C / pre-painted 7-state** | Show all states in the tree, let user map to slots manually. No automatic folding. |
| 2 | **Recursive subdivision** | Ship in v1. When one topology component covers > 60% of triangles, recursively split it with spatial K-means and surface the sub-regions as children. |
| 3 | **Z-bands** | Fallback only. Bands only appear when branches A–E all yielded < 2 segments. No "supplement" mode. |
| 4 | **AI failure** | Surface a small "AI naming unavailable" chip on the result screen so users distinguish "AI is off" from "AI failed". |
| 5 | **Brush strokes** | Create a new child row under the affected parent ("Custom selection · 200 tri"). Visible in the tree, reassignable / undoable like any other row. |
| 6 | **AI gating** | Settings adds `aiNamingEnabled` toggle on the AI Paint section, marked Experimental, defaulting **off**. When off, no AI calls are made — pipeline returns deterministic regions only. |

## Architecture

### 1. Segmentation cascade

`AiPaintViewModel.runPipeline` walks the branches top-down and stops at the first one that yields ≥ 2 regions. Source-of-segmentation is tracked on each region so the UI can surface it.

```
A. Existing paint state    (nativeGetPaintStateCounts >= 2 states)
B. Per-volume extruder     (nativeGetAllVolumeExtruders has distinct extruders across volumes)
C. Per-object split        (nativeGetObjectCount >= 2 on the selected plate)
D. Triangle indices        (preview mesh extruderIndices distinct >= 2)  [safety net]
E. Topology + recursion    (MeshSegmenter; if one component > 60% triangles → spatial K-means on just that component)
F. Z-bands                 (12 equal-height slices)
```

Output of each branch: `(triangleSegments: ByteArray, regions: List<AiRegion>, source: SegmentationSource)`.

`SegmentationSource` is a new enum: `PAINT_STATE | VOLUME | OBJECT | TRIANGLE_INDEX | TOPOLOGY | TOPOLOGY_RECURSIVE | Z_BAND`. Tagged on every region for diagnostics and (later) UI hints.

#### Branch A — Existing paint state

- **Precondition:** any volume has `isMmPainted == true` or its `nativeGetPaintStateCounts(kind=0)` sums to > 0 across > 1 distinct state.
- **Output:** one region per distinct paint state. Region's `componentIds` is the list of triangles in that state (per-triangle map already exists via the native pipeline that feeds `MeshData.extruderIndices`).
- **Slot mapping:** identity (state k → slot `(k - 1) % 4`); user remaps manually in the tree.
- **Worst case (H2C 7+ states):** 7 distinct rows in the tree, each on slots 0–3 by modulo. User maps. No automatic folding (decision #1).

#### Branch B — Per-volume extruder

- **Precondition:** `nativeGetAllVolumeExtruders` returns > 1 distinct extruder value across the plate's volumes.
- **Output:** one region per `(objectIndex, volumeIndex)` pair. Triangle→volume mapping comes from the native preview mesh build order (volumes are concatenated in order; we compute per-volume triangle ranges from `nativeGetVolumeCount` × cumulative volume triangle counts).
- **Slot mapping:** identity to the volume's declared extruder.
- **Tree shape:** when an object has > 1 volume, the object becomes a parent row with each volume as a child.

#### Branch C — Per-object split

- **Precondition:** `nativeGetObjectCount() ≥ 2` AND that count survives plate filtering (`nativeGetPlateData(plate).objectInstanceMap`).
- **Output:** one region per object. Labels come from `ThreeMfObject.name` (already parsed; native side via `nativeGetObjectExtruderMap` `.name`).
- **Slot mapping:** object's declared `extruder` if present; else descending coverage → round-robin slot index.

#### Branch D — Triangle indices (safety net)

- **Precondition:** branches A–C didn't fire but `getPreparePreviewMesh().extruderIndices` distinct ≥ 2.
- **Output:** one region per distinct index value.
- Defensive — should rarely fire in practice but keeps us honest if the native pipeline computes something the cascade missed.

#### Branch E — Topology + recursive subdivision

- **Precondition:** STL or single-volume 3MF where branches A–D yielded < 2 segments. `MeshSegmenter.segmentByTopologyOrSpatial` yields `numComponents ≥ 2`.
- **Output baseline:** one region per topology component, capped to `TARGET_SEGMENTS = 12` (descending coverage; smaller components merge into nearest neighbour by centroid).
- **Recursive subdivision (decision #2):** when any single component covers > 60% of triangles AFTER the baseline cap, recursively split just that component via spatial K-means into 4–8 sub-regions. Sub-regions become children of the dominant component's tree row. Caps total tree depth at 3.
- **Tree shape:** top-level row "Model" (or filename root); children = topology components; sub-regions appear as grandchildren under the dominant component.

#### Branch F — Z-bands (last resort)

- **Precondition:** every other branch yielded < 2 segments. Single-shell STLs (cat pot, simple vase).
- **Output:** `TARGET_SEGMENTS = 12` equal-height Z-bands.
- **Slot mapping:** round-robin `% 4`.
- Default labels: `"Band 1"`..`"Band 12"`. AI naming optional on top.

### 2. AI's reduced role

AI is opt-in, never determines segmentation shape. Three things AI can do:

1. **Name regions.** Given a shaded reference render + a region-coloured render + the region count, return `List<NamedColour>` (label + suggested colour). If parsing fails: keep deterministic defaults and show the "AI naming unavailable" chip.
2. **Palette suggestion.** Hex per region. Always overridden by the user's loaded printer filament colours when those exist.
3. **Manual "Split this region" button.** When any region exceeds ~60% of triangles, expose a "Split with AI" affordance that runs spatial K-means on just that region + AI naming. **Manual only, never automatic.**

Drop the `runTopologyGroupingPath` entirely. The grouping was the flaky bit.

### 3. UI — single expandable tree

One `LazyColumn`. All rows share the same shape: `chevron · swatch · label · % · slot-chips`. Indentation shows hierarchy.

#### Row anatomy

```
[chevron] [swatch] Hull            71%  [1][2][3][4]
```

- `chevron` (▸/▾): only on rows with children. Tap to expand/collapse.
- `swatch`: 36dp colour box. For a leaf, the slot's current colour. For a parent with mixed children, dominant-slot colour with a diagonal stripe of the second-most-common slot (decision: stripe, not badge — confirmed during synthesis).
- `label`: region name (object name / volume label / "Band N" / "Custom selection · 200 tri" / AI-suggested).
- `%`: coverage fraction of total triangles.
- `slot-chips`: row of 4 small swatches showing current vs available slots. Tap to reassign.

#### Behaviour

- **Tap a slot chip on a parent** → cascade-reassign every cascade-tree leaf under it to that slot. Brush-stroke "Custom selections" (which live under their own root-level group, see below) are NOT swept up — they remain on their independently-assigned slots so cascade-reassign never destroys manual paint work. Show `Snackbar`: "Reassigned 12 regions → Slot 2 · Undo".
- **Tap a slot chip on a leaf** → reassign just that leaf.
- **Tap the swatch** → open existing HSV picker; updates the slot's colour (not the region's only).
- **Long-press a parent** → "Select all in viewer" pushes its triangles into Lasso selection so the user can prune.
- **Auto-expand** the whole tree when total leaf count ≤ 8 (so Benchy and small Bambu files look flat by default).
- **Auto-collapse to depth 1** when total leaf count > 20 (so a 60-volume Bambu file isn't a wall of text).
- **Tap target floor:** 32dp at every depth. Indent by 12dp per level (small enough that depth-3 still fits on a phone).

#### Mockups

```
Goat-on-base.stl                  Benchy.3mf
+------------------------------+  +------------------------------+
| [3D viewer]                  |  | [3D viewer]                  |
+------------------------------+  +------------------------------+
| Paint  Lasso     [1][2][3][4]|  | Paint  Lasso     [1][2][3][4]|
+------------------------------+  +------------------------------+
| v [#] Goat (whole) 100% [mix]|  | v [#] Benchy   100%   [mix]  |
|   v [O] Body         63% [1] |  |   > [O] Hull         71% [1] |
|     . horns          4%  [3] |  |   > [O] Cabin        18% [2] |
|     . hooves L       2%  [4] |  |   > [O] Smokestack   6%  [3] |
|     . hooves R       2%  [4] |  |   > [O] Flag         3%  [4] |
|     . tail           3%  [2] |  |   > [O] Window glass 2%  [1] |
|   > [O] Base         37% [1] |  +------------------------------+
+------------------------------+
```

#### Custom selections from the brush (decision #5)

Brush strokes and lasso commits append children under a **single root-level `"Custom selections"` group**, NOT nested under cascade-tree parents. This avoids two ambiguities:

- a stroke that crosses Hull and Cabin would otherwise need to split into per-parent children
- a cascade-reassign on Hull would otherwise sweep up brush work the user did inside Hull

With the root-level group, custom selections are clearly separate user intent that overlays the cascade. Behaviour:

- Each brush stroke / lasso commit appends one row: `"Custom selection · <triangle-count>"`
- Each child has its own slot chips, swatch, % coverage; reassignable / undoable like any other leaf
- Custom-selection rows are derived from a side-channel `customSelections: List<TriangleRange>` on the result state; rebuilding the tree never collapses them back into the cascade
- Tapping the "Custom selections" parent's slot chip cascade-reassigns every brush stroke
- A "Clear all" action on the parent flattens the custom selections back into the cascade (triangles revert to their original segment's slot)

#### AI failure chip (decision #4)

When `aiPaintProvider != null && aiNamingEnabled == false` AND AI naming returned null/parse-failed, show a compact info chip in the result screen header:

```
[ⓘ AI naming unavailable — using default labels]
```

Tap → small tooltip with the model that was tried and the error. Doesn't block anything; the tree is fully usable with default labels.

### 4. Data model

`AiPaintResultState` (existing) gains:

```kotlin
data class AiPaintResultState(
    // ... existing fields ...
    val tree: List<AiRegionNode>,           // root nodes; each has children: List<AiRegionNode>
    val source: SegmentationSource,         // which branch produced this state
    val aiNamingFailed: Boolean = false,    // drives the failure chip
    val aiModelTried: String? = null,       // diagnostic for the chip tooltip
)

data class AiRegionNode(
    val region: AiRegion,                   // unchanged shape; label/colour/slot/coverage live here
    val children: List<AiRegionNode> = emptyList(),
    val expanded: Boolean = true,
    val nodeSource: SegmentationSource,     // PAINT_STATE | VOLUME | OBJECT | … | BRUSH
    val triangleIds: IntArray,              // explicit per-node triangle membership; supersedes componentIds for tree ops
)
```

`AiRegion` itself stays largely intact. The flat `regions: List<AiRegion>` on `AiPaintResultState` is removed in favour of `tree` (consumers walk the tree).

### 5. Pipeline rewrite

`AiPaintViewModel.runPipelineInternal`:

```
1. Read native snapshot (paint state, volume extruders, object list, plate filter).
2. Run cascade A→F; stop at first branch with ≥ 2 segments.
3. Build the AiRegionNode tree from the branch's output.
   - Branch A: flat (root → states).
   - Branch B: root → objects → volumes (only nest when an object has > 1 volume).
   - Branch C: flat (root → objects).
   - Branch D: flat.
   - Branch E: root → components → sub-regions (when recursion fired).
   - Branch F: flat (root → bands).
4. Optional AI naming (if provider+key set AND aiNamingEnabled = false):
   - Render shaded + region-coloured views.
   - Call AiLabelClient.labelSegments(provider, key, [shaded, regions], leafCount).
   - On success: apply labels + suggested colours to leaves.
   - On failure: set aiNamingFailed = true, aiModelTried = lastModelTried.
5. Apply printer-slot colour overrides (userColour) on regions matching slot index.
6. Write painted 3MF via PaintedMeshWriter (unchanged).
```

### 6. Slot mapping helper

Common helper used by every branch:

```kotlin
fun assignSlots(regions: List<AiRegion>, declaredExtruders: List<Int?>): List<AiRegion> = when {
    regions.size <= 4 -> regions.mapIndexed { i, r -> r.copy(slot = i) }
    declaredExtruders.any { it != null } -> regions.mapIndexed { i, r ->
        r.copy(slot = (declaredExtruders[i] ?: i) % 4)
    }
    else -> regions
        .sortedByDescending { it.coverageFraction }
        .mapIndexed { i, r -> r.copy(slot = i % 4) }
}
```

User can always remap via the tree's chips.

### 7. Files & boundaries

**New / heavily rewritten:**
- `aipaint/SegmentationCascade.kt` — pure logic: takes a native snapshot + topology results, returns `(triangleSegments, tree, source)`.
- `aipaint/AiRegionNode.kt` — tree data model.
- `aipaint/TopologyRecursion.kt` — spatial K-means recursion on a dominant component.
- `ui/AiPaintTree.kt` — the new tree composable (replaces the LazyColumn region rows in `AiPaintResultScreen.kt`).

**Modified:**
- `aipaint/AiPaintViewModel.kt` — pipeline gutted and rewritten around `SegmentationCascade`. `runTopologyGroupingPath` removed.
- `aipaint/AiLabelClient.kt` — `labelGroups` removed. `labelSegments` stays but always operates on deterministic regions.
- `aipaint/AiRegion.kt` — minor changes for `SegmentationSource`.
- `ui/AiPaintResultScreen.kt` — region list replaced by `AiPaintTree`. Result-state consumers updated.
- `ui/SettingsScreen.kt` — "AI naming (experimental)" toggle on the AI Paint section.
- `data/SettingsRepository.kt` — `aiNamingEnabled: Flow<Boolean>` + setter; default false.

**Removed:**
- `aipaint/AiPaintViewModel.runTopologyGroupingPath`
- `AiLabelClient.buildGroupPrompt`, `labelGroups`, `parseGroupJson`, `componentDisplayColors`, `hsvToArgb` (only used by component rendering)
- Tests covering the dropped methods

**Untouched:**
- `aipaint/MeshSegmenter.kt` — still used by branch E for the topology pass
- `aipaint/AiPaintRenderer.kt` — still used for shaded + region-coloured renders
- `aipaint/PaintedMeshWriter.kt` — output format unchanged
- `aipaint/AiPaintMeshBuilder.kt` — unchanged
- 3D viewer wiring in `AiPaintResultScreen.kt` — unchanged

### 8. Error handling

- **Native data missing.** Each native call is null/empty-checked. If a branch's precondition can't be evaluated due to missing data, it's skipped (not an error). Cascade always reaches branch F.
- **Empty mesh.** Existing `"Could not read model geometry."` error case stays. No tree built.
- **AI failure.** Silent at the pipeline level; surfaced via the failure chip in UI. Default labels remain.
- **Recursion overflow.** Hard cap of depth 3 on the tree. K-means recursion only fires once per branch-E run.
- **Pre-existing paint with > 16 states.** Branch A reads state counts up to `EnforcerBlockerType::ExtruderMax = 16`. States beyond 16 land in state 0 (unpainted) by native convention — no special handling needed.

### 9. Testing

#### Unit tests (`app/src/test/`)

- `aipaint/SegmentationCascadeTest.kt` — one test per branch precondition (A through F), verifying the correct branch fires on fixture-shaped fake inputs.
- `aipaint/AiRegionNodeTest.kt` — tree construction shape for each branch, depth caps, leaf-count auto-expand/collapse.
- `aipaint/TopologyRecursionTest.kt` — dominant-component detection (> 60% threshold), K-means sub-region counts.
- `aipaint/AiLabelClientTest.kt` — `labelSegments` parsing already covered; remove tests for dropped methods.

#### Instrumented tests (`app/src/androidTest/`)

- `aipaint/SegmentationCascadeIntegrationTest.kt` — one `@Test` per fixture, end-to-end:
  - `colored_3DBenchy.3mf` → Branch A (paint state), 4 distinct rows
  - `Dragon Scale infinity.3mf` plate 3 → Branch B, 3 volume rows
  - Multi-object Bambu plate → Branch C
  - `3DBenchy.stl` → Branch E with recursion (large hull subdivided)
  - Cat pot STL → Branch F (Z-bands)
  - H2C Benchy → Branch A with 7 rows (no folding)
- Each test asserts: cascade source enum, tree shape, leaf count, root coverage = 100%.

#### UI tests (Composable)

- `ui/AiPaintTreeTest.kt` — Compose UI tests for cascade-reassign, expand/collapse, mixed-parent swatch rendering, brush-stroke child rows.

Counts to update on completion: `CLAUDE.md` and `.worktrees/f54-ai-paint/CLAUDE.md` test totals.

### 10. Out of scope (follow-ups, not v1)

- Z-bands as a "more granular" supplement under volumes (decision #3 deferred).
- AI mode that does anything beyond naming + palette (e.g. semantic regrouping of topology results — explicitly killed).
- Per-volume filament material-type overrides (sits at the Prepare-screen layer, separate work).
- Pre-painted models with seam paint (`supported_facets`) — branch A reads MMU only; seam paint stays a SlicerViewModel concern.
- Saved palette presets across models.

### 11. Migration

Implemented as a single cohesive change. No flag-gating, no staged shipping. The old pipeline (`runTopologyGroupingPath`, `runZBandPath`, the flat region list, the toggle-related state fields) is removed once the new pipeline + tree composable + settings toggle compile and pass tests. A debug APK ships only after the full feature is in place.

Within the implementation plan, the work decomposes into independently-testable units (cascade branches, tree composable, settings, custom-selections, AI naming) so unit + instrumented tests gate each unit before the screen is wired up — but no intermediate APK is staged for on-device review.

## Out-of-design considerations

- **Native rebuild?** No. All accessors required (`nativeGetAllVolumeExtruders`, `nativeGetPaintStateCounts`, `nativeGetObjectExtruderMap`, `nativeGetPlateData`) already ship in `libprusaslicer-jni.so`. Verified in `data-inventory.md`.
- **Backward compatibility on cached results.** `AiPaintViewModel.isSamePainting` continues to match by basename strip; the on-disk 3MF format (paint_color encoding) is unchanged.

## Open follow-ups for after implementation plan

- Confirm fixture availability for every cascade branch in `app/src/androidTest/assets/`. The data inventory lists the Benchy and Dragon Scale; multi-object plate + cat-pot single-shell may need fixture additions.
- **Triangle→volume attribution** for Branch B. The spec assumes per-volume triangle ranges can be derived from accumulating `nativeGetVolumeCount` × per-volume triangle counts in mesh build order. Verify against `colored_3DBenchy` / Dragon Scale fixtures during plan execution; if the order isn't stable, add a JNI accessor that returns a per-triangle volume index directly. (Flagged in `data-inventory.md` §"Open questions".)
- Decide whether the "AI naming unavailable" chip should include a "Retry" button.
