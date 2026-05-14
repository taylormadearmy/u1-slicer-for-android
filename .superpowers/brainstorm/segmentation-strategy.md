# AI Paint: Segmentation Cascade Proposal

## TL;DR

Promote the model's own structure (paint state → volumes → objects) to the top of the cascade. Topology and Z-bands stay as fallbacks for raw STLs. Default the user-facing path to "Smart Segments" (deterministic, free, instant). Demote AI to an optional **naming + palette** assistant on top of whatever segmentation produced the regions. No AI call required to ship a usable painted 3MF.

## 1. What data we have

At entry to `AiPaintViewModel.runPipeline`, the model is already loaded via `loadModelForPlate`, plate is resolved (`selectPlate` ran upstream), and the native snapshot is queryable. We are not using any of this today.

Native accessors (all under `previewMutex`, `NativeLibrary.kt:100-201`):

- `nativeGetObjectCount()` / `nativeGetVolumeCount(obj)` — object/volume topology
- `nativeGetObjectExtruderMap()` — JSON `[{objectId, name, extruder, sourcePath}, ...]`
- `nativeGetAllVolumeExtruders()` — JSON of per-object, per-volume `{extruder, isMmPainted, isSeamPainted}`
- `nativeGetPaintStateCounts(obj, vol, kind=0)` — packed `[state, count, ...]` for `mmu_segmentation_facets` (paint states 1..N)
- `nativeGetVolumeScalars(obj, vol)` — `[extruder, isMmPaintedBool, isSeamPaintedBool]`
- `nativeGetPlateData(plateIdx)` — `objectInstanceMap`, `filamentColours`, `customGcode`

Kotlin side: `ThreeMfInfo.objectExtruderMap`, `volumeExtruders`, plate-level `hasPaintData`, `filamentColours`. The native parse is the source of truth post-Phase-1.

`getPreparePreviewMesh()` already returns per-triangle `extruderIndices` for painted / multi-volume models (see `MeshData.recolor`, `NativePreviewMesh`). **The painted-mesh segmentation for H2C / SEMM / per-volume 3MFs is already computed by the native pipeline — we just need to read it.**

## 2. Proposed cascade

Branches are tried top-down. Each branch produces `(triangleSegments: ByteArray, regions: List<AiRegion>)`. AI is a separate, optional post-processor.

```
loadedModelStructure(native):
  paint = nativeGetPaintStateCounts() summed across all volumes
  volumes = nativeGetAllVolumeExtruders()
  objects = nativeGetObjectExtruderMap()

if paint.states.size >= 2:                   → Branch A: Respect existing paint
elif volumes.flatMap { extruders }.distinct().size >= 2:
                                              → Branch B: Per-volume extruder
elif objects.size >= 2:                      → Branch C: Per-object split
else if mesh.extruderIndices distinct >= 2:  → Branch D: Use native triangle indices
else:
  topo = MeshSegmenter.segmentByTopology(...)
  if topo.numComponents >= MIN_TOPO_COMPONENTS (e.g. 4):
                                              → Branch E: Topology components
  else:
                                              → Branch F: Z-bands (current fallback)
```

### Branch A — Pre-painted (H2C / SEMM / hand-painted)

- **Precondition:** any volume has `isMmPainted == true` or paint state count ≥ 2.
- **Produces:** N regions where N = number of distinct paint states across the model. Regions seeded from `filamentColours[stateIndex - 1]`.
- **Slot mapping:** identity (state k → slot (k-1) % 4). State already has user intent.
- **Worst case:** model already paints 7 colours (H2C Benchy). Fold via `% 4` and surface a warning in the result screen ("This model was painted for 7 filaments; folded to your 4 slots — review and remap"). No segmentation work needed.
- **Why first:** ignoring existing paint is destructive and confusing; this was the user's spec for F54 from day one.

### Branch B — Per-volume extruder (Bambu compound parts)

- **Precondition:** `nativeGetAllVolumeExtruders` yields distinct extruder IDs across volumes (e.g. Dragon Scale: 5 volumes on 4 extruders inside 1 object).
- **Produces:** one region per **distinct extruder value** across volumes. Each region has its volume(s)'s triangles. Triangle→volume mapping comes from native mesh build (the same path that powers Prepare preview colouring).
- **Slot mapping:** identity to the extruder slots the file already declares.
- **Worst case:** 5 volumes all on the same extruder → degenerate (1 region). Fall through to Branch C.
- **Notes:** This is the Goat-on-base STL hybrid case answer when the file is a 3MF — if the goat and base are separate volumes, this branch fires before topology and gives perfect separation.

### Branch C — Per-object split (multi-object plate)

- **Precondition:** `nativeGetObjectCount() ≥ 2` for the selected plate (filter via `nativeGetPlateData(plate).objectInstanceMap`).
- **Produces:** one region per object, labelled with the object's name (already in `ThreeMfObject.name`). Triangle→object derived from mesh build order or `nativeGetObjectExtruderMap` ordering.
- **Slot mapping:** if object has explicit `extruder`, use it; else round-robin by object index modulo 4.
- **Worst case:** 1 plate with 5 copies of the same object → 5 regions of identical name. Dedup by name and fall through to Branch D or E. Multi-instance on a single object → treat as 1 region.

### Branch D — Native triangle extruder indices

- **Precondition:** `getPreparePreviewMesh().extruderIndices` shows ≥ 2 distinct values but Branches A–C didn't fire (rare; defensive).
- **Produces:** regions = distinct triangle index values.
- This is essentially a safety net so we never lose colour information the native pipeline already computed.

### Branch E — Topology components (current `segmentByTopologyOrSpatial`)

- **Precondition:** STL or single-object 3MF without paint / per-volume diversity. `numComponents ≥ 4` after dihedral flood-fill + merge.
- **Produces:** up to 32 components → grouped down to `TARGET_SEGMENTS` (12).
- **Worst case:** Benchy hull and cabin merged across the smooth joint → 1 huge component covering 90% of the mesh. `segmentByTopologyOrSpatial` already detects this (`dominantThreshold = 0.7`) and falls through to spatial K-means (32 chunks).
- **Hybrid (goat-on-base STL):** one component is 90% of triangles. The 70% guard kicks in → K-means. This works but loses semantics. **Improvement:** when one component dominates, recursively segment **just the dominant component** by spatial K-means (e.g. 8 sub-regions) and leave the smaller components as their own regions. This preserves "the goat's separate eye" while still subdividing the body.

### Branch F — Z-bands (current fallback)

- **Precondition:** topology degenerate (smooth vase, single-shell figurine).
- **Produces:** `TARGET_SEGMENTS = 12` equal-height bands.
- **Slot mapping:** round-robin `% 4`.
- Unchanged from today.

## 3. Hybrid case answers

| Case | Branch | Behaviour |
|---|---|---|
| Goat-on-base STL (one giant component) | E w/ recursion + F | Dominant-component subdivide, base stays own region |
| 3MF: 1 object / 5 volumes | B | 5 regions, one per volume |
| 3MF: 5 objects / 1 volume each | C | 5 regions, named by object |
| Multi-plate Bambu | n/a | Plate already selected at AI Paint entry; cascade runs on the loaded plate only |
| Pre-painted (H2C/SEMM) | A | Existing paint preserved, folded onto 4 slots |
| Painted + per-volume mixed | A wins | Paint state is more user-intentional than volume split |

## 4. AI's reduced role (recommendation)

Default the entry to **Smart Segments** (deterministic cascade above). AI becomes an opt-in *enrichment* applied on top of whatever segmentation ran:

1. **Naming** — send shaded render + region-coloured render + region count → AI returns `List<String>` of labels. If parse fails: fall back to `"Region 1..N"` / object names / band names. (Same shape as `AiLabelClient.labelSegments` today, but with deterministic regions as input.)
2. **Palette suggestion** — AI proposes hex colours per region for the *suggested* palette. Always overridden by printer slot colours when those are loaded.
3. **Optional subdivision** — when one region exceeds e.g. 60% of triangles, offer a "Split this region with AI" button that runs spatial K-means + AI naming on just that region. Manual, never automatic.

Drop the topology-grouping AI call entirely (current `runTopologyGroupingPath`). Components → 12 segments grouping is the part that's been flaky. Round-robin or descending-coverage assignment is good enough and free.

Mark AI as **Experimental** in Settings (`SettingsRepository.aiPaintProvider`) and add a "Use smart segments only" toggle.

## 5. Slot mapping (all branches)

Common helper. Given N regions and 4 slots:

```
if N <= 4: identity
elif anyBranchProvidedExtruders: use those, fold > 4 with modulo
else: descending coverage → slot index round-robin
```

Always allow the result screen's slot-picker row to remap.

## 6. Open questions

1. **Threshold tuning** — Branch E `MIN_TOPO_COMPONENTS = 4` vs current `>= 4`-with-AI gate. Should we lower to 2 (a Benchy hull split from rudder gives 2 components — useful even without AI)?
2. **Recursive subdivision** — Branch E improvement is a non-trivial code change; ship cascade first, recursion next iteration?
3. **AI failure UX** — today AI failure is silent (logs only). Should the result screen show a small "AI naming unavailable" chip when the deterministic fallback was used? Important to distinguish "AI is off" from "AI failed".
4. **Pre-painted folding policy** — when H2C declares 7 states, do we (a) fold by modulo, (b) cluster colours by hue and pick 4 representative states, or (c) refuse and ask the user to remap manually? Today H2C handling lives in `SlicerViewModel`; AI Paint should match.
5. **Confidence in `nativeGetAllVolumeExtruders` triangle attribution** — Branch B/D need a stable triangle→volume map. Is there an accessor that gives us per-triangle volume index directly, or do we infer from offset accumulation of `nativeGetVolumeCount` × triangle counts?
6. **STL files with embedded "parts" via separate shells** — STLs can be multi-solid (rare, but exists). Does `nativeGetVolumeCount` populate for raw STL imports, or do we have to detect via topology only? Code path needs verification.

## 7. Files / functions referenced

- `app/src/main/java/com/u1/slicer/aipaint/AiPaintViewModel.kt` — main pipeline
- `app/src/main/java/com/u1/slicer/aipaint/MeshSegmenter.kt` — topology + spatial K-means
- `app/src/main/java/com/u1/slicer/aipaint/AiLabelClient.kt` — current AI calls
- `app/src/main/java/com/u1/slicer/NativeLibrary.kt:100-201` — native accessors
- `app/src/main/java/com/u1/slicer/bambu/ThreeMfInfo.kt` — `objectExtruderMap`, `volumeExtruders`, plate state
- `app/src/main/java/com/u1/slicer/bambu/NativePlateState.kt` — Kotlin wrapper over `nativeGetAllVolumeExtruders`
- `app/src/main/java/com/u1/slicer/viewer/NativePreviewMesh.kt` — `extruderIndices` per triangle
