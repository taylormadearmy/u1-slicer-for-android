# Phase 1 sub-plan #1 — Painted facets → preview mesh (design notes)

**Date:** 2026-04-23
**Scope:** Pre-flight research for replacing `ThreeMfMeshParser` paint extraction with a JNI accessor backed by `FacetsAnnotation::get_facets`.
**Status:** Research only. No code touched.

---

## 1. Production call sites of `ThreeMfMeshParser` paint extraction

Only **two** production callers of `ThreeMfMeshParser.parse(...)`:

1. **`app/src/main/java/com/u1/slicer/ui/ModelViewerScreen.kt:42`** — inside `LaunchedEffect(modelFilePath)`, dispatched on `Dispatchers.IO`.
   - Method: `ThreeMfMeshParser.parse(file)` (no `extruderMap`, no `detectedColorCount`).
   - Used by: standalone full-screen 3D preview reached via `navigation/NavGraph.kt:106` (the `ModelViewerScreen` route). Not the main Prepare/Slice tabs.
   - What the return feeds: a bare `MeshData` passed straight to `ModelViewerView.setMesh(m)`. No colour mapping, no recolour palette — the mesh's default per-vertex grey (0.7,0.7,0.7,1.0) is what's shown. **Paint data is actually ignored by this caller** even though the parser still does the full BSP walk to extract it. Cold one-shot path (fires once per `modelFilePath`).

2. **`app/src/main/java/com/u1/slicer/MainActivity.kt`** — **no live call.** All `ThreeMfMeshParser` mentions in this file are comments explaining that the path was abandoned (B46). The `InlineModelPreview` parse effect at `MainActivity.kt:2279-2306` explicitly returns `null` for `.3mf` (`// rotation effect owns this fetch for all 3MF`) and the rotation effect calls the native `getPreparePreviewMesh` instead.

Everything else that uses `ThreeMfMeshParser` lives under `app/src/androidTest/` or `app/src/test/` (e.g. `ThreeMfMeshParserTest.kt`, `ProfileEmbedderIntegrationTest.kt:393`) — these are harness-level callers, not production, and can migrate independently.

**Conclusion:** There is effectively one production consumer — `ModelViewerScreen.kt`, which doesn't currently colour the mesh. The hot-path preview (`InlineModelPreview`) has already moved to native. This is the least-risky JNI cut-over the diff harness could ask for.

The stale misleading comment at `MainActivity.kt:2191` ("Use Kotlin ThreeMfMeshParser only for painted/SEMM models") should be removed as a drive-by in this sub-plan.

---

## 2. `FacetsAnnotation::get_facets` API

Declared: `app/src/main/cpp/orcaslicer/src/libslic3r/Model.hpp:727-743`
Defined:  `app/src/main/cpp/orcaslicer/src/libslic3r/Model.cpp:3377-3414`

Two overloads plus a strict variant plus a predicate:

```cpp
// Single state — returns the mesh of all triangles assigned to `type`.
indexed_triangle_set get_facets(const ModelVolume& mv, EnforcerBlockerType type) const;

// Batched — fills one indexed_triangle_set per state; size == num_states.
void get_facets(const ModelVolume& mv, std::vector<indexed_triangle_set>& facets_per_type) const;

// Strict single-state: only triangles whose *stored* state matches exactly, without
// propagating parent state onto unsplit children. Slightly smaller output than get_facets.
indexed_triangle_set get_facets_strict(const ModelVolume& mv, EnforcerBlockerType type) const;

// Cheap predicate — does *any* triangle carry this state? No mesh allocation.
bool has_facets(const ModelVolume& mv, EnforcerBlockerType type) const;
```

`EnforcerBlockerType` (`TriangleSelector.hpp:13-38`): scoped `int8_t` enum. `NONE=0` is "unpainted, inherit from volume"; `ENFORCER=1` (= `Extruder1`), `BLOCKER=2` (= `Extruder2`), then `Extruder3..Extruder16`, with `ExtruderMax=Extruder16`. Two annotations live on a `ModelVolume`:
- `mmu_segmentation_facets` — extruder / colour painting (what sub-plan #1 needs).
- `supported_facets` — paint-supports; state 1 = ENFORCER, state 2 = BLOCKER.

`indexed_triangle_set` is the standard Slic3r struct: `{ std::vector<stl_vertex> vertices; std::vector<Vec3i> indices; }` — i.e. flat vertex array + flat triangle index array. No normals; the caller computes them (as `ThreeMfMeshParser.buildMeshData` already does).

**Performance.** `get_facets` reconstructs a `TriangleSelector` from scratch on every call:

```cpp
TriangleSelector selector(mv.mesh());       // allocates full triangle tree
selector.deserialize(m_data, false);        // walks the BSP bitstream
return selector.get_facets(type);
```

That's O(triangles in the volume × BSP depth) per state, per call, with a full tree allocation each time. For the 16-state loop in `sapil_bambu_snapshot.cpp:243-249` this is 16× deserialisation. The batched overload `get_facets(mv, facets_per_type)` builds the selector **once** and emits all states in a single traversal — strictly cheaper when we want ≥2 states. No caching on the FacetsAnnotation side; `TriangleSplittingData` is the serialised form and must be walked anew.

`has_facets` is O(data.triangles_to_split.size()) with no allocation (`TriangleSelector::has_facets` static) — the Phase 0 snapshot uses it as a cheap 16× filter before paying `get_facets`.

`get_facets_strict` vs `get_facets`: `get_facets` propagates a parent's painted state to every leaf the caller might expect to be painted (useful for rendering filled regions); `get_facets_strict` emits only leaves explicitly stamped with that state. For **preview rendering** we want `get_facets` (matches what `getPreparePreviewMesh` already uses at `sapil_model.cpp:400, 467`). Use `_strict` only for audit-style diffs where "did the writer literally stamp this triangle?" matters.

---

## 3. JNI accessor design proposal

Three shapes considered:

### Option A — one state per call (matches Phase 0 snapshot)

```kotlin
external fun nativeGetPaintedFacets(
    objectIndex: Int, volumeIndex: Int, state: Int
): FloatArray?  // flat xyz vertices for painted triangles; null if none/OOR
```
C++: `auto its = mv.mmu_segmentation_facets.get_facets(mv, EBT); copy vertices[indices[i]] into jfloat[]`.
- **Pros:** simplest JNI glue; no new struct shape; mirrors `has_facets`+`get_facets` pairing in the snapshot; makes `KotlinBambuSnapshot.volumes[N].paintStateSet` populatable in one loop.
- **Cons:** 16 JNI round-trips and 16 TriangleSelector rebuilds per volume. Acceptable for snapshot (64 calls for a typical 4-volume file) but terrible if production preview ever calls it per-frame.

### Option B — batched: one call, all states

```kotlin
data class PaintedFacetSet(
    val states: IntArray,           // length N
    val triangleCounts: IntArray,   // length N (parallel to states)
    val vertices: FloatArray,       // concatenated xyz; boundaries derived from triangleCounts
)
external fun nativeGetPaintedFacetSet(
    objectIndex: Int, volumeIndex: Int
): PaintedFacetSet?
```
C++: single `selector.get_facets(facets_per_type)` (batched overload); flatten into parallel arrays; return via JNI handle object.
- **Pros:** one TriangleSelector rebuild per volume; matches how `sapil_model.cpp:402` already calls the batched form; closes the paintStateSet diff in one shot; future-proofs for per-state preview decimation.
- **Cons:** JNI marshalling of a struct-of-arrays is slightly more C++; null-safety story (how do we distinguish "no paint" from "failure") needs to be crisp — propose: returning an object with `states=[]` means no paint, returning `null` means "volume index out of range".

### Option C — only counts (skip vertex bytes entirely)

```kotlin
external fun nativeGetPaintStateCounts(
    objectIndex: Int, volumeIndex: Int
): IntArray?  // packed [state0,count0, state1,count1, ...]
```
- **Pros:** tiny JNI payload; 100% sufficient to close the `paintStateSet` baseline diff in `KotlinBambuSnapshot.volumes`.
- **Cons:** doesn't unblock the preview-mesh replacement. Still need Option A/B later for `ThreeMfMeshParser` retirement.

**Recommendation: start with Option C, add Option B as follow-up.**

The diff-harness goal says close the `volumes[N].paintStateSet` baseline, which is purely counts. Option C is ~15 lines of C++ — literally extracting `count_paint_states()` from `sapil_bambu_snapshot.cpp:239-252` behind a new JNI entry point — and makes `KotlinBambuSnapshot.snapshot()` self-sufficient for the volumes field. Option B can then ride on top when we actually delete `ThreeMfMeshParser`. Option A is never the answer because the batched form is strictly cheaper.

If the implementer disagrees and wants to unify: do Option B straight away, with a fast `sizeHint=0` mode that skips vertex copy. That's the "optimise later" posture the main plan asks for, delayed by one sub-plan.

---

## 4. Integration points for the production callers

**For `KotlinBambuSnapshot.kt:92` (the immediate win):**
- Wrap the native model load under `NativeLibrary.previewMutex.withLock { … }` (Phase 0 already does this for the snapshot path — see `NativeBambuSnapshot.kt:24`).
- After `loadModel`, iterate `g_model.objects[].volumes[]` via a new JNI pair: `nativeGetVolumeCount(objectIndex)` plus Option C's `nativeGetPaintStateCounts(oi, vi)`. These mirror the C++ `append_volume` loop.
- The volumes list becomes a straight translation. `paintSupportsStateSet` comes along for free if the same JNI accepts a "which annotation" selector (`0 = mmu, 1 = supports`).
- No production rendering code is touched. The diff harness flips from "known gap: volumes list empty" to "agreement".

**For `ModelViewerScreen.kt:42`:**
- Long-term: call `nativeLoadModel(path)` + `nativeGetPreparePreviewMesh()` (the same path `InlineModelPreview` already uses for 3MF), then drop the `ThreeMfMeshParser.parse` branch entirely.
- Short-term: leave it alone in sub-plan #1. The caller doesn't use paint data anyway, so there is zero diff-harness signal to drive the change. Folded into a later sub-plan that retires `ThreeMfMeshParser` outright.

**Smallest first step that moves the baseline:** Option C + wire `KotlinBambuSnapshot.volumes` to produce the same `VolumeSnapshot` shape the native side already emits. That closes ~420 of 664 baseline diff entries without touching any render path.

---

## 5. Risks + open questions

- **`g_model` must be loaded first.** JNI accessor is only valid after `SlicerEngine::loadModel` succeeded. Enforce via the same pattern as `nativeDumpBambuModel` at `slicer_wrapper.cpp:138-153` (re-load under `previewMutex`, fail to `null`). Failure mode: caller gets `null` and records an empty `VolumeSnapshot` list — identical to today's behaviour, so the diff harness doesn't regress.
- **Non-Bambu / no paint data.** `mmu_segmentation_facets.empty()` is the canonical check; `has_facets` is false for every state when empty. Option C returns empty `IntArray`, Option B returns an object with `states=[]`. Already modelled correctly in `count_paint_states`.
- **Per-call cost.** Phase 0 Task 7 reports ~15s across 4 tests with 16-state-loop + `has_facets` filter. For snapshot that's fine (one-shot at diff time). For production preview it would not be — but preview doesn't use this path. Batched Option B keeps the budget in check if it ever becomes hot.
- **`previewMutex` discipline.** The JNI accessor **must not** acquire the Kotlin-side `NativeLibrary.previewMutex` itself — it's a coroutine Mutex owned on the Kotlin side. Caller holds it (as `NativeBambuSnapshot.snapshot` already does). C++ side can assume serialised access.
- **Component-ref geometry quirk.** `ThreeMfMeshParser` walks 3MF component refs manually (`collectMeshes` at `ThreeMfMeshParser.kt:395-436`) for benchy-style files where `info.objects` is empty at the Kotlin level. The native loader already resolves components into `g_model.objects[].volumes[]` — so the JNI accessor side-steps the issue, and the Kotlin edge case disappears together with the parser. No fresh handling needed on the Kotlin side.
- **State indexing convention.** C++ emits state as the raw `EnforcerBlockerType` integer (1..16). Kotlin snapshot should preserve that exact key; the existing native JSON already does (`sapil_bambu_snapshot.cpp:279` `kv.first`). Diff harness is keyed on these strings, so don't fold/remap on the Kotlin side before emitting.
- **ABI drift.** The JNI shim adds a function, which is additive and NDK-safe; no existing signatures change. Still needs a native rebuild — follow the NDK 26 / Release checklist in the root `CLAUDE.md` and verify `~20MB` stripped size + clang 17 in `.comment`.
- **Test coverage gap.** There is no existing test that asserts `paintStateSet` matches native. Sub-plan #1 should add one before deletion-day. Suggested: extend `NativeLibraryCorrectnessTest` with a small 3MF fixture (Flarewing Dragon plate 3 is already in-corpus and paint-heavy) and assert the JNI counts match the pre-existing `sapil_bambu_snapshot.cpp` counts exactly.
- **Open question: `supported_facets`.** The main plan lumps "paint supports" together with "paint states" as part of Phase 1's scope. Option C can expose both via a `kind` arg; worth deciding in sub-plan #1 even if only `mmu` is implemented first, so the JNI name doesn't need to churn later.

---

## TL;DR for the next-session implementer

1. Add `nativeGetPaintStateCounts(objectIndex, volumeIndex, kind)` (Option C) + a `nativeGetVolumeCount(objectIndex)` helper.
2. Populate `KotlinBambuSnapshot.volumes` from those two calls under `previewMutex`. Mirror `sapil_bambu_snapshot.cpp` field-for-field.
3. Leave `ThreeMfMeshParser` untouched — deletion is a later sub-plan. Just close the baseline diff entries and ship.
