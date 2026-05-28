# v2.10.0 — F66: Split to Objects, Split to Parts, Auto-Orient

**Status:** Draft. APK staged locally. **Not yet published — requires user authorization before `gh release create`.**

## What's new

This release brings desktop-Orca-equivalent split + orient to the Prepare screen, plus the per-object selection model that both operations require.

### Tap to select an object on the bed

Tap any object on the 3D bed and it gets highlighted. A new Edit panel appears below the preview with controls scoped to that one object (Auto-orient, Split, Reset, Delete via Parts panel). Tap the empty bed background — or the × in the panel header — to clear the selection and return to bed-wide controls.

Pan, tilt, and zoom gestures are unchanged. The existing tap-vs-drag dispatcher inside the 3D viewer already filtered them out (movement threshold + 300 ms tap timeout), so we just wired into the existing pipeline — no new gesture conflicts.

### Auto-Orient (single object or whole bed)

- **Selected one object?** Tap **Auto-orient** — the engine analyses every face and rotates the object so its most stable face sits flat on the bed. Useful for STLs that loaded on their side.
- **Nothing selected?** Tap **Auto-orient all** — the same operation runs on every object on the bed.

Both are wrapped in the existing foreground-service progress indicator (the same spinner you see during slicing), since auto-orient can take a few seconds on heavy meshes.

### Split to Objects

Take a MakerWorld-style assembly with multiple parts merged into one 3MF object, tap **Split to Objects**, and each disconnected piece becomes its own item on the bed. Drag them apart, rotate them independently, slice. The Skadis-shelf case in particular — the main reason this feature was requested.

The Split button is only enabled when the engine detects that an object actually has multiple disconnected pieces. Pressing it on a single solid model does nothing.

### Split to Parts (for multi-volume 3MFs)

When the selected object has multiple volumes (sub-meshes within one item — common in 3MFs authored for multi-material printing), a **Split to Parts** button appears. It exposes each volume as a row in the new Parts panel where you can assign different filament slots per part — so a "body" volume can be on E1 (white) and an "accents" volume on E2 (red), then slice with both colours.

### Reset rotation / Reset scale

After auto-orient, manual rotation, or scale changes, a **Reset rotation** / **Reset scale** button appears whenever the current value differs from the object's load-time pose. One tap restores the object to the rotation/scale it had right after loading (preserving any rotation a 3MF file declared). Bed-wide variants (**Reset all rotations** / **Reset all scales**) appear when nothing is selected. Position and paint are untouched by Reset — only rotation or only scale.

### Sessions resume with everything

If you close and reopen the app mid-edit, the bed comes back exactly as you left it: selected object, per-object rotations + scales, every Split operation replayed in order, every per-part filament assignment, and per-volume extruder overrides. The F89 session schema bumped to v3 to carry this; older v2 sessions return null and clear (consistent with existing F89 behaviour).

### Per-object rotation and scale dials

When an object is selected, the Edit panel exposes three rotation sliders (X / Y / Z, 0–360°) plus a uniform scale slider (10–400%). Sliders drive the selected object only; the existing bed-wide Rotate/Scale chips outside the panel are unchanged.

### Tap discoverability

Tap-to-select works on every model — single-island STLs, single-3MF multi-volume files (e.g. button packs, dual-colour cali cubes), and multi-file scenes. When the renderer has per-object mesh ranges (multi-file load or post-split scene) the tapped object is picked precisely; otherwise the lone object is selected and the Edit panel reveals its scoped controls.

### Known limitations in this release

- **Compose UI gestures are not covered by an automated harness in this project.** Engine-level and ViewModel-level paths have full test coverage; gesture quality was verified manually on the Pixel 8a smoke install.

## Test coverage added in this release

- **14 instrumented native contract tests** (run on Pixel 8a):
  splittability probes, splitObject with single-island and multi-island inputs, auto-orient produces a bed-resting pose, per-object rotation/scale round-trip in degrees and per-axis, per-object isolation (set 0 → object 1 untouched), object name and per-volume extruder round-trip, the **paint-preservation regression** (the BBS-fork's `ModelObject::split` confirmed to keep `mmu_segmentation_facets` through a split — gating this release).
- **30 new JVM unit tests**: `ObjectSelection` state machine (11), `PerObjectPose` + remap helper (9), `SessionState` v3 round-trip (10).
- **All 14 existing `SessionStateTest` cases** still pass with the v3 bump (their JSON literals were updated from `"version":2` → `"version":3` so they continue to test what they claim — never weakening an assertion).

## Files

| Artefact | Path |
|---|---|
| Release APK | `app/build/outputs/apk/release/app-release.apk` → staged copy as `u1-slicer-v2.10.0.apk` |
| Native `.so` | `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so` (20.9 MB stripped Release, Clang 17.0.2, NDK 26) |
| Spec | `docs/superpowers/specs/2026-05-28-f66-split-and-auto-orient-design.md` |
| Plan | `docs/superpowers/plans/2026-05-28-f66-split-and-auto-orient.md` |
| GitHub issue | [#56](https://github.com/taylormadearmy/u1-slicer-for-android/issues/56) — close on publication |

## Publication checklist (do NOT execute without user authorization)

1. ☐ Manual E2E batch on Pixel 8a (load + slice + send) — confirm no regressions outside F66.
2. ☐ Tag and push: `gh release create v2.10.0 u1-slicer-v2.10.0.apk --title "v2.10.0" --notes-file RELEASE_NOTES_v2.10.0.md`
3. ☐ Move F66 to **Closed (recent)** in `BACKLOG.md`.
4. ☐ Close GitHub issue #56.
