# F66 — Split to Objects, Split to Parts, and Auto-Orient

**Status:** Design approved 2026-05-28. Implementation plan to follow.
**Tracking:** GitHub #56 (BACKLOG.md F66)
**Target release:** v2.10.0 (bundled)

## Problem

Users (commonly via Skadis-shelf and similar MakerWorld 3MFs) bring assemblies
where multiple parts are positioned together as a single object. Slicing as-is
demands heavy supports and produces poor results. Desktop OrcaSlicer solves this
with "Split to Objects" (decomposes a model into its disconnected pieces, each
independently placeable), "Split to Parts" (decomposes a single mesh into
multiple sub-volumes within one object so each can be on a different filament),
and "Auto-Orient" (rotates an object so its most stable face is on the bed).

U1 Slicer today has no equivalent — load is take-it-or-leave-it, and rotation
acts on the whole bed at once.

F66 brings desktop-equivalent split + orient to U1 Slicer, plus the per-object
selection model that both operations require.

## Approach

OrcaSlicer's native engine already implements `ModelObject::split()`,
`ModelVolume::split(max_extruders)`, and `Slic3r::orientation::orient(...)`.
F66 is principally JNI wrappers, a per-object pose data model in the
ViewModel, per-object pose support in the F77 multi-object renderer, a
selection model, a reshaped Edit panel, and a Parts panel for per-volume
filament assignment.

All of it lands in a single bundled release (v2.10.0). Internal sequencing is
specified at the bottom of this document.

## Sections

### 1. Selection model and Edit panel UX

**Selection state on `SlicerViewModel`:**
- `selectedObjectIndex: StateFlow<Int?>` — `null` means "no selection,
  controls act bed-wide" (current behaviour; no regression for single-STL).
- `selectedVolumeIndex: StateFlow<Int?>` — `null` means "object as a whole";
  non-null only while the user is inside the Parts panel.

**Tap-to-select via existing dispatcher:**
The existing tap/drag/pinch disambiguation in
[ModelViewerView.kt:350–362](app/src/main/java/com/u1/slicer/viewer/ModelViewerView.kt#L350-L362)
already filters pan, tilt, and zoom (`!wasDragging && !tapMovedTooFar && dt <
300L`) before invoking `onTriangleTapped` / `onEmptyTap`. The Prepare-screen
viewer instance does not wire either today. F66 wires both:
- `onTriangleTapped { triIdx → }`: look up the owning object via F77's
  `objectMeshRanges` (populated by `splitMeshByObjects`); set
  `selectedObjectIndex` to that index.
- `onEmptyTap { }`: set `selectedObjectIndex = null`.

No new gesture code; the existing pattern is reused.

**Selection highlight:**
Second draw pass on the selected object's mesh range with a constant tint
(Material3 secondary) added in the fragment shader behind a `u_highlight`
uniform. Theme-aware (light/dark). Cheaper than a stencil-outline pass and
sufficient for touch-screen scale.

**Edit panel — two visual states driven by `selectedObjectIndex`:**

*Nothing selected (default):*
- Existing bed-wide Rotate / Scale controls (unchanged behaviour)
- `Auto-Orient All` button
- `Reset all rotations` button (visible when any object's rotation ≠ load-time)
- `Reset all scales` button (visible when any object's scale ≠ load-time)
- `Objects on bed (N)` collapsible list as a fallback selector for hard-to-tap
  small parts (taps a row → sets selection)

*Object selected:*
- Header: `Selected: <name> (i/N)` with a `×` to deselect
- Per-object Rotate / Scale dials (read from `perObjectRotations[idx]` /
  `perObjectScales[idx]`)
- `Split to Objects` — enabled iff `nativeIsObjectSplittable(idx)`
- `Split to Parts` — enabled iff the object has at least one splittable volume
- `Auto-Orient` (this object only)
- `Reset rotation` — visible when current ≠ load-time
- `Reset scale` — visible when current ≠ load-time
- `Delete`
- `Parts (N)` collapsible (Section 4) — visible iff `volumeCount > 1`

**Deselect rules:**
- Loading a new file, switching plates, slicing, or pressing `×` → deselect.
- After Split-to-Objects, the N new objects are added and the first is
  auto-selected (matches desktop Orca).

### 2. Native JNI surface

All new methods on `NativeLibrary.kt`, implemented in `sapil_print.cpp`. Native
rebuild required (NDK 26 / Clang 17 / Release / `~20 MB` size check / JNI
symbol completeness check per `CLAUDE.md`).

**Probes (cheap; for enable/disable):**
- `nativeIsObjectSplittable(objIdx: Int): Boolean` — `parts_count() > 1` plus a
  connected-component probe on the unified mesh.
- `nativeIsVolumeSplittable(objIdx: Int, volIdx: Int): Boolean` — reads
  `ModelVolume::m_is_splittable`.

**Split operations:**
- `nativeSplitObject(objIdx: Int): IntArray?` — calls
  `ModelObject::split(&new_objects)`, removes the original, inserts the new
  objects at the same starting index. Returns `[removedIdx, addedCount]` so the
  ViewModel can remap per-object state precisely. Returns `null` if the object
  has only one connected component.
- `nativeSplitVolume(objIdx: Int, volIdx: Int): Int` — calls
  `ModelVolume::split(maxExtruders)`; returns the new volume count.

**Auto-orient:**
- `nativeAutoOrientObject(objIdx: Int): DoubleArray?` — wraps
  `Slic3r::orientation::orient(...)` for one object, applies the result to its
  instance rotation, returns the new Euler `[x, y, z]` in radians. TBB-parallel
  internally; called from a worker thread, wrapped in `LongOpService` (F90).
- `nativeAutoOrientAll(): Int` — iterates every object; returns succeeded count.

**Per-object transforms (additive — bed-wide `setModelRotation` /
`setModelScale` retained for the no-selection path):**
- `nativeSetObjectRotation(objIdx, x, y, z)` — `instances[0]->set_rotation(...)`
  in radians (Orca convention; ViewModel converts from degrees).
- `nativeGetObjectRotation(objIdx): DoubleArray` — current Euler.
- `nativeSetObjectScale(objIdx, sx, sy, sz)` — `instances[0]->set_scaling_factor(...)`.
- `nativeGetObjectScale(objIdx): DoubleArray` — current scaling factor.
- `nativeGetObjectName(objIdx): String?` — display label for the Edit panel
  header. Implementation may already exist via `nativeGetObjectModelId`;
  confirm during step 1.

**Per-part filament (Section 4):**
- `nativeGetVolumeName(objIdx, volIdx): String?` — display label.
- `nativeGetVolumeExtruder(objIdx, volIdx): Int` — current 1-indexed extruder
  slot.
- `nativeSetVolumeExtruder(objIdx, volIdx, slot: Int)` — assigns the
  per-volume `extruder` config.

**Threading model:** model access is single-threaded. All JNI calls serialize on
the existing lock. `orient()` itself is TBB-parallel internally and is
acceptable since concurrent JNI calls don't occur.

**Object identity stability across split:**
After `nativeSplitObject(5)` returning `[5, 3]`, the ViewModel runs:
- Per-object maps (rotations, scales, positions): drop key `5`, shift keys
  `>5` up by `2` (net +2: 1 removed, 3 added), insert defaults for new keys
  `5, 6, 7`.
- Selection: auto-set to `5` (first new object).

**Paint preservation across split:** Documented BBS-fork behaviour preserves
`mmu_segmentation_facets` per resulting island. Verified by a dedicated test
(Section 6) on a painted multi-island fixture. If the test fails, the fix lives
in our existing Android-specific C++ patch set.

### 3. Per-object pose data model and renderer

**ViewModel state (additive to existing global rotation/scale):**
- `perObjectRotations: StateFlow<Map<Int, ModelRotation>>`
- `perObjectScales: StateFlow<Map<Int, Vec3>>`
- `loadTimeRotations: Map<Int, ModelRotation>` — snapshot taken at load /
  post-split so `Reset rotation` knows where to restore to.
- `loadTimeScales: Map<Int, Vec3>` — same for scale.
- Existing `customObjectPositions` (F77) already keys positions per-object.

**Action methods:**
- `setObjectRotation(objIdx, rot)` → updates state + `nativeSetObjectRotation`.
- `setObjectScale(objIdx, scale)` → updates state + `nativeSetObjectScale`.
- `resetObjectRotation(objIdx)` → sets `perObjectRotations[objIdx]` back to
  `loadTimeRotations[objIdx]`; same for scale.
- `resetAllRotations()` / `resetAllScales()` — bed-wide variants.
- `splitObject(objIdx)` → calls `nativeSplitObject`, applies remap, selects
  first new object.
- `splitVolume(objIdx, volIdx)`, `autoOrientObject(objIdx)`,
  `autoOrientAll()`, `setVolumeExtruder(objIdx, volIdx, slot)`.

Auto-orient operations route through `LongOpService` (F90) since `orient()` can
take seconds on heavy meshes.

**Renderer (F77 multi-object path):**

Today's F77 renderer accepts `instancePositions: FloatArray` (XY per object)
and draws each instance via `drawModelAt(mesh, px, py)`. F66 extends this to:
- `instanceRotations: FloatArray` (3 floats per object — Euler XYZ in radians)
- `instanceScales: FloatArray` (3 floats per object — sx, sy, sz)

Each instance composes its own model matrix (translate × rotate × scale) before
the `drawModel` call. Throughput cost: N small draw calls instead of one
batched call. For ≤50 objects (a Skadis assembly upper bound) this is well
under 1 ms/frame; the cliff lives somewhere in the high hundreds where we'd
surface a "too many parts to preview smoothly" warning. Not relevant in
practice.

**Selection highlight pass:**
After the main scene pass, draw the selected object's range a second time with
`u_highlight = (r, g, b, alpha)` set in the shader (added to fragment colour
post-lighting). Other objects render unchanged.

**Hit-test (Section 1):**
World-space ray-cast on the same transformed positions the renderer uses
(`pickingPositions` updated when `instanceRotations` / `instanceScales`
change). Solves the "selection still hits the rotated mesh, not the
load-time mesh" concern automatically.

**Reset semantics:**
- `Reset rotation` restores `perObjectRotations[idx] =
  loadTimeRotations[idx]`. Position, scale, paint preserved.
- `Reset scale` restores `perObjectScales[idx] = loadTimeScales[idx]`.
  Position, rotation, paint preserved.
- Reset-all variants iterate every object on the bed.
- No multi-level undo history. Once reset, prior rotation/scale is lost. A
  proper undo stack is out of scope for F66; if real-world use proves the
  Reset-to-load-time pattern insufficient, that becomes its own feature.

### 4. Parts panel — per-part filament assignment

**Visibility rule:** The Parts panel appears in the Edit panel only when the
selected object has more than one volume. Single-volume STLs / 3MFs see no
panel.

**Layout (collapsible row in the Edit panel):**

```
Parts (3)                               ⌄
┌───────────────────────────────────────┐
│ ● body        ● E1  white  PETG    > │
│ ● accents     ● E2  red    PETG    > │
│ ● logo        ● E3  black  PETG    > │
└───────────────────────────────────────┘
```

Each row: part name (from `nativeGetVolumeName`), current extruder slot chip
(colour + label), tap to change. The chooser is the same Material3 filament
sheet used elsewhere on the Prepare screen — lists slots E1..E4 with their
loaded filament label. Pick a slot → `setVolumeExtruder(...)`. Preview tints
that part to match the slot's colour immediately.

**Default after Split to Parts:** All N new parts inherit the parent's
extruder. No visual change in the preview right after splitting; the user
assigns intentionally.

**Slice integration:** Per-volume extruders flow through the existing
`ProfileEmbedder` path (the same path that handles Bambu files which declare
per-part extruders natively). Map & Print dialog continues to show one row per
filament actually used.

**Coexistence with Smart Paint:** Per-part is the coarser, faster alternative
to painting. Both can coexist on one object — painted triangles win on their
own triangles; unpainted triangles fall back to their part's extruder. Matches
desktop Orca.

**Reset for per-part assignments:** A `Reset part filaments` button on the
Parts panel header restores every part to its load-time assignment (for 3MFs
that declared per-part extruders) or to the parent's extruder (for parts
created via Split to Parts).

**Hardware limit reminder:** Snapmaker U1 has four extruders. Any number of
parts is fine; many parts on the same extruder is the common case.

### 5. Persistence (F89 session resume)

F89's existing schema persists source file, plate index, rotation, scale,
position, and F77 additional files. F66 extends the schema (version bump):

**New fields:**
- `selectedObjectIndex: Int?`
- `selectedVolumeIndex: Int?`
- `perObjectRotations: Map<Int, [x, y, z]>`
- `perObjectScales: Map<Int, [sx, sy, sz]>`
- `perObjectPositions: Map<Int, [x, y]>` — already exists via
  `customObjectPositions`; brought under the same per-object map for clarity.
- `perVolumeExtruders: Map<"objIdx:volIdx", slot: Int>`
- `splitObjectOperations: List<Int>` — load-time-index-ordered list of objects
  that were split.
- `splitVolumeOperations: List<"objIdx:volIdx">` — volumes that were split,
  in load-time-index order.

**Split state replay (not snapshot):**
On resume, replay the recorded operations against the freshly loaded model.
`ModelObject::split` and `ModelVolume::split` are deterministic on a given
mesh, so the replay produces the same parts every time. After replay, per-object
state is re-applied from the persisted maps.

**Edge cases (consistent with existing F89 behaviour):**
- Source file missing → existing toast + clear session.
- Source file changed externally → replay completes but anchored per-part
  state may land on different parts. Acceptable risk (same profile as the
  existing per-file rotation persistence).
- Older app version reading newer F66 session data → F89's existing rule
  applies: unknown future schema version returns `null` and the session offer
  clears, no resume. Acceptable because users upgrade forward; a downgrade
  loses one session, not data on disk.

### 6. Testing

**Layer 1 — JVM unit tests (`app/src/test/`):**
- `NativeLibraryCorrectnessTest`: `nativeIsObjectSplittable` true/false on
  known fixtures; `nativeSplitObject` returns expected `[removedIdx,
  addedCount]` on a two-island STL; `nativeAutoOrientObject` produces a
  rotation that puts the largest flat face down on a shoe-like fixture.
- `PaintPreservationOnSplitTest`: load a painted multi-island fixture, split,
  verify each new piece's paint state matches its pre-split triangles.
- `PerObjectTransformIsolationTest`: rotate object 1, verify object 2's
  rotation is unchanged; same for scale.
- `SelectionStateMachineTest`: tap → select; tap empty → null; file load →
  null; slice → null; split → first-new-object selected.
- `SplitRemapTest`: set state on objects 0..7, split object 5 (3 pieces),
  verify objects 0..4 stable, new pieces 5/6/7 default, old 6/7 shifted to
  8/9 and stable.
- `ResetTransformTest`: rotate/scale, then reset, verify match to load-time
  baseline.
- `F89SessionRoundTripF66Test`: full edited session → save → reload →
  identical state.

**Layer 2 — Instrumented tests (`app/src/androidTest/`):**
- `SplitAndOrientIntegrationTest`: real Skadis-class 3MF → split → auto-orient
  all → slice → G-code contains expected object count and per-object
  positions.
- `PaintedSplitSliceTest`: painted multi-island fixture → split → slice →
  G-code tool changes consistent with pre-split paint distribution.
- `PerPartFilamentSliceTest`: multi-part 3MF → assign parts to E1/E2/E3 via
  ViewModel → slice → G-code T-tool counts match assignment.
- `F89SessionResumeF66Test`: persist full edited session via DataStore, reload
  ViewModel, verify state.

**Layer 3 — Manual on-device (added to E2E batch checklist):**
- Tap-to-select hits the right object on a busy bed (≥6 parts).
- Selection highlight visible on light and dark themes.
- Pan / tilt / zoom unaffected by selection state.
- Rotate/scale dials affect only the selected object in real time.
- Auto-Orient long-op spinner appears for `>300 ms` operations.

**Known automated-testing gaps:**
- Aesthetic judgement of auto-orient results — mitigated by Reset.
- Compose gesture quality — covered by manual checks (project has no Compose
  UI gesture harness; documented gap in CLAUDE.md).
- Beds with `>50` parts — not exhaustively tested; informally bounded by
  Skadis-scale (≈12 parts).

### 7. Sequencing and estimate

Single bundled release (v2.10.0). Internal step order, each independently
verifiable before the next:

1. **Native JNI surface + JVM tests** (Section 2). Rebuild and stage the new
   `libprusaslicer-jni.so`. Tests cover engine-level correctness end-to-end.
2. **ViewModel per-object pose plumbing + F89 schema bump + ViewModel tests**
   (Sections 3 + 5). State model fully functional headless.
3. **Renderer per-object pose + selection highlight + tap-to-select wiring**
   (Sections 1 + 3 renderer). Mid-point sanity check: app already usable with
   the existing controls; selection works.
4. **Edit panel reshape + Auto-Orient + Reset + Split buttons** (Sections 1 +
   3 reset).
5. **Parts panel + per-part filament assignment** (Section 4).
6. **Instrumented end-to-end tests + manual E2E batch + release** (Section 6).

**Estimate:** five to seven working days of focused effort, weighted toward
steps 1–3 (engine + state + renderer).

## Out of scope (deferred)

- Multi-level undo across all Prepare actions. Reset-to-load-time is the
  F66 substitute. Promote to a separate feature if real-world use shows it's
  insufficient.
- Lasso multi-select. Single-object selection only.
- Group transforms (rotate/scale several selected objects together). Single
  object at a time.
- Auto-arrange on the bed after split. Today F77 places objects in a centred
  grid; that placement stays. A "rearrange after split" pass could land later.
- Convert a per-part assignment back into Smart Paint markings, or vice versa.
  They coexist by file but no conversion is offered.
