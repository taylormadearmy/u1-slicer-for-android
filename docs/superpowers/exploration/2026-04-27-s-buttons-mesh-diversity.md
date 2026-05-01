# Phase 2 — S-Buttons mesh diversity (file → 4 colours, mesh → 2)

Date: 2026-04-27
Worktree: `.worktrees/phase2-mesh-diversity` (detached HEAD at 1dc18d7)
APK: `G:/My Drive/claude/u1-slicer-phase2-fix-mesh-diversity.apk`

## The bug

`Button-for-S-trousers.3mf` plate 1 is a per-object 4-extruder layout. Plate 1
declares 12 file filaments (15 raw, 12 after the parser de-duplicates colour
hex strings) and the four "Assembly" objects on plate 1 use **distinct** file
extruders 1, 2, 3, 4 (mapping to file filaments grey / dark brown / red /
green respectively).

The user-reported symptom on a recent device run:

```
paletteSize=12 canonical=true meshAligned=12 hasMeshColors=true
```

— palette is correctly file-wide and complete — yet the 3D Prepare body
renders only ~2 distinct colours (brown + grey). The expected red and green
buttons are missing.

## Root cause

The bug is **not** in native (`getPreparePreviewMesh` / Bambu importer / model
parsing) and not in the Kotlin recolour glue. Native produces the correct
mesh; the Phase 2 canonical-palette recolour produces the correct RGBAs.

The bug is **upstream of native**, in `embedProfile`. Walk-through:

1. `selectPlate(1)` → `embedProfile(file, info, dir, plateId = 1)`.
2. `embedProfile` calls `buildCompactExtruderRemap(info, colorMapping)` to
   build a 1-based source→target extruder remap. With `colorMapping` produced
   by `findClosestExtruder` against the user's slot presets, this remap can
   collapse multiple source extruders onto fewer slots — e.g. a user whose
   slots are {red, white, blue, yellow} and a plate that uses {grey, brown,
   red, green} can yield `colorMapping = [1, 1, 0, 0, ...]` (grey+brown both
   closest to white, red+green both closest to red), producing the collapsing
   remap `{1→2, 2→2, 3→1, 4→1}`.
3. The remap reaches `ProfileEmbedder.embed(..., extruderRemap, plateId)`,
   which routes to `remapModelSettingsExtruders(model_settings_xml,
   extruderRemap)` for Bambu files (or `convertToModelSettings` for
   PrusaSlicer Slic3r_PE files). Both rewrite `<metadata key="extruder"
   value="N"/>` to the remapped slot.
4. The embedded model_settings.config now has only 2 distinct extruder values
   for plate 1's 4 buttons. BBS loads it; native's
   `g_model.objects[i]->config["extruder"]` resolves to two distinct values;
   `getPreparePreviewMesh` emits a mesh with only 2 distinct
   `extruderIndices`.
5. Phase 2's canonical palette still has 12 entries, but the mesh only
   addresses the first 2 → only 2 distinct RGBAs render.

The collapse used to be **correct** in the pre-Phase-2 world because:

- `_gcodeUsesPhysicalSlots` was `true` and `GcodeToolRemapper` ran post-slice
  with the same compact remap, baking the user's slot mapping into the G-code
  at slice time.
- The 3D Prepare palette was the slot palette (`activeExtruderColors`), so
  collapsing 4 source extruders onto 2 slots produced 2 (correct) Prepare
  colours matching the user's slot choices.

Phase 2 changed both: the Prepare palette is now **file-filament-indexed**
(every file colour is a chip on the dialog), and the slice-time tool remap
is suppressed (`skipSliceTimeRemap = true`, `_gcodeUsesPhysicalSlots = false`)
in favour of `PrintTimeRemap` — the slot mapping is applied at
send-to-printer time, not at slice time.

The embed-time remap survived this contract change but became wrong: it
collapses the model's per-object diversity that the new file-filament-indexed
palette relies on.

## The fix

`SlicerViewModel.embedProfile` now passes `extruderRemap = null` to
`ProfileEmbedder.embed`. The embedded model_settings.config keeps the
source's distinct extruder values (1..4 for S-Buttons plate 1's four
buttons). The slicer emits canonical T-indices from the unmodified extruder
layout. The user's slot mapping is applied at print time by `PrintTimeRemap`,
which already runs unconditionally on the send path.

`buildCompactExtruderRemap` is left in place — it has unit tests that pin
its semantics, and it could become useful again if a future feature needs an
embed-time remap (e.g. for a slicing-correctness reason that doesn't apply
post-slice). The production caller no longer invokes it.

### Files changed

- `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` — `embedProfile`:
  `val extruderRemap: Map<Int, Int>? = null` (with reasoning comment).
  `buildCompactExtruderRemap` is no longer called from this site.

- `app/src/test/java/com/u1/slicer/bambu/ProfileEmbedderTest.kt` — new test
  `extruderRemap collapses extruders when applied to per-object model` pins
  both behaviours: (a) collapsing remap reduces 4 → 2 distinct values
  (regression guard against a future re-enabling of the embed remap),
  (b) `null` preserves all 4 distinct extruders.

## Coverage

- **Bug case (S-Buttons plate 1)**: with `colorMapping = [1, 1, 0, 0, ...]`
  the old logic produced 2 distinct values in the embedded
  model_settings.config. The fix keeps all 4. The mesh's
  `extruderIndices` (read from `volume->extruder_id()` falling back to
  object-level `mo->config["extruder"]`) now spans 0..3 → palette[0..3] =
  grey, brown, red, green → 4-distinct rendering.

- **Identity-mapping cases (Calicube, identity-slot users)**: old behaviour
  was identity remap `{1→1, 2→2, 3→3, 4→4}` — i.e. a no-op. New behaviour
  (null) is also a no-op. No change.

- **Paint plates (Buzz plate 9, H2C benchy, SEMM)**:
  `buildCompactExtruderRemap` returned `null` for `hasPaintData = true`.
  No change.

- **Layer-tool plates (flippy plate 4, Hueforge)**:
  `buildCompactExtruderRemap` returned `null` for `hasLayerToolChanges &&
  !hasPaintData`. No change.

- **Slicing correctness**: the slicer continues to see the per-object
  extruder layout as authored by the file. T-indices in G-code are the
  file's source extruder values minus one. `PrintTimeRemap` translates these
  to the user's slot when sending to the printer. Equivalent to the
  pre-fix path with identity remap; only the collapse path differs.

- **JVM unit suite**: 981 tests still green (build successful with no failures).

- **Instrumented coverage**: per task constraints I did not run
  instrumented tests (sibling agent has the device). The existing
  `BambuFixtureHarnessTest.fixture_button_for_s_trousers` and
  `PreparePreviewViewModelTest.sButtons_plate1_*` tests should continue to
  pass — they assert on `enrichedUsedExtruders` (which already includes
  Kotlin's `objectPartExtruders` fallback) and on
  `info.detectedColors.size >= 4`, both of which are unaffected by the
  embed-time remap.

## Risk

Low. The change strictly removes a transformation that:
- Was a no-op for the common identity case.
- Was already returning `null` for paint and layer-tool files.
- Was only non-trivial for users whose slot presets collapse colours, where
  the existing slice-time remap suppression made it actively harmful.

The slicer's `is_extruder_used[N]` accounting now reflects the file's source
extruders (1..4 for S-Buttons) instead of the collapsed targets (1..2). This
matches the pre-Phase-2 PrusaSlicer-style "compact" intent for files with
non-collapsing colorMappings, and produces a wider but still-bounded
filament_colour array for those with collapsing colorMappings. The slicer
ignores filament_colour entries past the highest used filament index, so
the wider array is harmless.

The two `<plate>` blocks for plates 2 and 3 (referencing object IDs that
have been stripped from the embedded file) remain in `model_settings.config`
after `stripUnreferencedConfigObjects`, but BBS treats unresolved
`obj_inst_map` entries as warnings, not errors (bbs_3mf.cpp:2174-2185), so
this is unchanged behaviour.

## Native rebuild

**Not required.** All changes are Kotlin-only.

## Verification steps for the user

On-device verification (sibling agent has the device while this writeup is
being prepared):

1. Install the APK at `G:/My Drive/claude/u1-slicer-phase2-fix-mesh-diversity.apk`.
2. Load `Button-for-S-trousers.3mf`. Select plate 1.
3. The Prepare 3D body should render the four button grids in **four
   distinct file-filament colours**: grey (`#A6A9AA`), dark brown
   (`#6F5034`), red (`#9D2235`), green (`#3F8E43`).
4. Logcat should show `paletteSize=12 canonical=true meshAligned=12
   hasMeshColors=true` (unchanged) and the recolour effect log line should
   indicate the mesh has at least 4 distinct extruder indices in the
   `toMeshData triangles=... indices={0=..., 1=..., 2=..., 3=...}` line
   from `NativePreviewMesh`.

Regressions to watch on existing fixtures: Calicube (2-colour per-object,
identity mapping), Buzz plate 9 (paint), flippy plate 4 (layer-tool dual-
band), Dragon Scale plate 3 (compound paint), and the harness fixture set.
None of these exercise the collapsing-remap path, so they should be
unaffected.

## What I did NOT change

- Native source under `app/src/main/cpp/` is untouched. The
  `parsePreviewExtrudersFromModelConfig` regex fallback (used only when BBS
  returns empty `project_settings.config`) and the
  `getPreparePreviewMesh` extruder lookup loop are correct as-is.
- `NativePreviewMesh.kt` is unchanged. The Approach A1 contract from
  `cee1bbb` (preserve raw file-filament-indexed extruder indices, no Kotlin
  compaction) is preserved.
- `MeshAlignedFilamentColors` / `resolvedFilamentColors` are unchanged.
- `buildCompactExtruderRemap` is unchanged (kept for future use; its unit
  tests still pass).
