# Adversarial Review: v1.6.13 to Phase 2 Canonical Filaments

Date: 2026-04-28

Scope:
- Diff reviewed: `v1.6.13..HEAD`
- Worktree: `.worktrees/phase2-canonical`
- Branch: `feature/phase2-canonical-filaments`
- Focus: canonical filament/index-space changes, print-time remap, export/share paths, preview coloring, native plate state, and cache lifetime.

Review stance:
- The architectural brief is useful as a hypothesis map, but it is leading. It asserts conclusions such as coherent index-space separation and safe print-impact behavior before proving every export/send/preview path follows the same contract.
- I treated the brief as context to distrust rather than as a checklist.
- Tests were not run during this review pass.

## Findings

### P1: Send can bypass print-time remap before the dialog loads

File: `app/src/main/java/com/u1/slicer/MainActivity.kt`

Range: `664-741`

`produceState` starts with `rawCanonical = null`, and the branch below treats that initial loading value as a permanent "no canonical list" fallback. On the first composition after Send or Upload Only, the code can immediately call `sendAndPrint(pending.gcodePath)` or `sendUploadOnly(pending.gcodePath)`, clear `pendingMappingSend`, and navigate away before the IO lookup completes.

Impact:
- Canonical multi-filament G-code can be uploaded unchanged.
- The filament mapping dialog may never appear.
- High canonical T indices such as `T4`, `T9`, or `T10` can reach the printer path without `applyPrintTimeRemap`.

Suggested fix:
- Model canonical lookup as a three-state value: loading, loaded-null, loaded-list.
- While loading, keep the dialog/pending state alive and show a progress UI.
- Only use the unchanged-send fallback after lookup has completed and definitively returned null.

### P1: Save/Share remap can use the wrong index space

File: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`

Range: `3657-3667`

`prepareExportableGcode()` remaps with `_colorMapping`, but `_colorMapping` is often a plate/UI narrowed mapping rather than a full canonical `fileIndex -> physicalSlot` mapping. The path also falls through for single-color jobs because `_colorMapping` is null while the selected physical slot only lives in `toolRemapSlots` and `_selectedExtruder`.

Impact:
- Save/Share can leave high canonical T commands unchanged.
- A plate using high file filaments can export invalid printer-facing G-code.
- Single-color files selected for E2/E3/E4 can export as E1/T0.

Suggested fix:
- Build export mappings from the canonical filament list plus the confirmed or default slot assignment, not from UI-narrowed `_colorMapping`.
- Add an explicit single-filament mapping path: `T0 -> _selectedExtruder`.
- Reuse the same mapping builder as Send once that path is corrected.

### P1: Job history share bypasses canonical remap

File: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`

Range: `3354-3371`

`shareJobGcode()` shares the durable stored G-code directly. Phase 2 intentionally stores slices in canonical T-index space, so the Jobs tab is another export surface that bypasses `applyPrintTimeRemap`.

Impact:
- Previously sliced jobs can be shared/uploaded in a form the printer-facing path is not supposed to consume.
- High canonical T indices and single-color non-E1 selections can leak from job history.

Suggested fix:
- Store enough mapping metadata with `SliceJob` to reproduce the print/export mapping later, or require the mapping dialog before sharing a historical job.
- Route job-history sharing through the same corrected export remap helper.

### P1: Canonical filament cache can leak across loads

File: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`

Range: `645-652`

`getCanonicalFilamentList()` immediately returns `_canonicalFilamentList.value` if non-null without verifying it belongs to the current source file. Fresh `loadModel(uri)` and `loadModelFromFile(file)` paths can call `prepareImportedModelArtifacts()` and `embedProfile()` before the async refresh publishes the new canonical list.

The picker path also lacks the `_filamentOverrides.value = emptyMap()` clear that was added to `loadModelFromFile()`.

Impact:
- Loading file B after file A can embed or map file B using file A's canonical list.
- Per-filament overrides from file A can leak into file B through the common Android file-picker route.
- This affects slicing, preview palettes, material/nozzle temperature arrays, and print-time mapping.

Suggested fix:
- Track canonical cache identity, for example `(sourcePath, CanonicalFilamentList)`.
- Clear `_canonicalFilamentList` synchronously at the start of every new load before any embed/prepare call can query it.
- Clear `_filamentOverrides` in `loadModel(uri)` too.

### P1: Native plate state can miss paint-only colors

File: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`

Range: `3831-3857`

`readPlateStateFromNative()` only queries `nativeGetPaintStateCounts()` when `parsed.hasPaintData` is true, and then only for volumes where `vol.isMmPainted` is true. The test helper `PlateStateEnrichment.kt` documents a real class of SEMM files where volumes can report `isMmPainted=false` but still return non-empty paint-state counts when queried.

Because `buildThreeMfInfoFromNative()` now trusts this native state and does not union source plate `paintExtruderStates`, affected plates can be under-colored or classified as non-painted.

Impact:
- Detected colors can be incomplete.
- Slot mapping suggestions can be wrong.
- Preview and export/send mappings can diverge from what the slicer emits.

Suggested fix:
- Query `nativeGetPaintStateCounts()` for every volume when the source file or source plate indicates paint data, not only when the native volume flag says so.
- Union source plate `paintExtruderStates` as a fallback when available.
- Add an end-to-end regression for the documented SEMM case.

### P2: Parser still ignores multi-digit tool commands

File: `app/src/main/java/com/u1/slicer/gcode/GcodeParser.kt`

Range: `260-266`

The parser now grows `computedPerExtruderMm` up to 32 entries, but the T-command scanner still requires `cmdLen == 2`. That only accepts `T0` through `T9`. `T10`, `T11`, and above are ignored, so extrusion after those commands is attributed to the previous active tool.

Impact:
- Per-filament usage summaries are wrong for canonical files with ten or more tools.
- G-code preview coloring can be wrong after `T10+`.
- This contradicts the Phase 2 comments that mention Buzz plate 9 and 11-filament shapes.

Suggested fix:
- Parse all digits after `T` until whitespace or comment.
- Keep the safety cap at 31 if desired, but apply it after parsing the full integer.
- Add a unit test with `T10` and extrusion after it.

### P2: Sparse non-MMU Prepare preview uses the wrong palette

Files:
- `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`
- `app/src/main/cpp/src/sapil_model.cpp`

Ranges:
- `SlicerViewModel.kt:531-539`
- `sapil_model.cpp:587-588`

`meshAlignedFilamentColors` now emits the full canonical palette. Native, however, still compacts preview indices for non-MMU meshes with `compactPreviewIndices(out)`. For sparse non-MMU plates using file filaments such as 3 and 4, the mesh indices become 0 and 1 while the full canonical palette index 0 and 1 still refer to file filaments 1 and 2.

Impact:
- Prepare preview can show the wrong object colors even though the canonical model is otherwise correct.
- The issue is limited to visual correctness, but it can mislead users assigning filaments.

Suggested fix:
- Either stop compacting non-MMU native preview indices when the Kotlin side supplies a full canonical palette, or make `meshAlignedFilamentColors` truly mesh-aligned by reordering canonical colors to the compacted native index order.
- Add a sparse per-object fixture using non-consecutive source extruders.

### P2: G-code preview palette is still capped to four colors

File: `app/src/main/java/com/u1/slicer/MainActivity.kt`

Range: `4012-4055`

`normalizeGcodePreviewColors()` always creates a four-entry palette and maps canonical preview colors through physical slots. Phase 2 keeps sliced G-code in file-index space, so `T4` and higher are valid preview indices. The renderer later clamps move extruders to the last palette entry, so high-index tools are painted incorrectly.

Impact:
- 3D G-code preview is wrong for canonical slices with more than four file filaments.
- High-index tools collapse visually onto the last displayed color.

Suggested fix:
- Build the preview palette in canonical file-index space when `resolvedFilamentColors` is available.
- Let the palette length grow to the highest parsed T index or canonical list size.
- Keep physical-slot palettes only for legacy/non-canonical preview paths.

## Cross-Cutting Risks

1. Mapping state is duplicated.

   `_colorMapping`, `toolRemapSlots`, `_selectedExtruder`, dialog-local `mapping`, canonical filament list, and job-history data all represent overlapping pieces of the same logical mapping. Several bugs come from one path using a stale or narrowed representation while another path uses canonical file-index space.

2. "Null" is overloaded.

   A null canonical list currently means "loading", "not a canonical file", "cache not refreshed yet", and "no model". The Send bypass bug is the most severe consequence.

3. Preview and print paths are no longer the same index space.

   Prepare preview, G-code preview, on-disk G-code, Send, Save, Share, and Jobs each make local assumptions about whether indices are physical slots, compact mesh indices, or canonical file indices. These need one shared contract per surface.

4. Cache identity is implicit.

   `_canonicalFilamentList` is cached as a bare value. Without a source identity, it can outlive the file it describes.

## Recommended Fix Order

1. Fix Send loading-state fallback so canonical lookup cannot be bypassed.
2. Introduce one shared canonical export/send mapping builder, including single-filament selected-slot behavior.
3. Route Save, Share, and Jobs through that helper.
4. Add source identity and synchronous clearing to canonical cache; clear overrides in the picker path.
5. Fix native paint-state enrichment to query all relevant volumes and union source paint states.
6. Fix `GcodeParser` multi-digit T parsing.
7. Fix Prepare preview palette alignment for sparse non-MMU meshes.
8. Fix G-code preview palette length and index space.

## Suggested Regression Coverage

- Send path waits for canonical lookup instead of falling back during loading.
- Save/Share remap high canonical `T9` with a full canonical mapping.
- Save/Share single-color selected E3 remaps `T0 -> T2`.
- Job-history share either prompts/remaps or refuses canonical-space export without mapping metadata.
- Loading file B after file A cannot observe file A's canonical list or overrides.
- Paint-only SEMM volume with `isMmPainted=false` still contributes paint states.
- `GcodeParser` handles `T10` and attributes following extrusion to extruder 10.
- Sparse non-MMM/non-MMU object assignments preserve correct Prepare preview colors.
- G-code preview renders at least 11 canonical tools without clamping to four colors.
