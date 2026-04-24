# Phase 1 Sub-Plan #2b — `BambuSanitizer.extractPlate` → native migration — Design Notes

**Date:** 2026-04-24
**Branch:** `refactor/bambu-via-native-loader`
**Status:** Design notes only — no code changes.
**Predecessor plans:** `2026-04-23-phase1-roadmap.md` (roadmap lines 51, 93, 108), `2026-04-23-phase1-per-plate-data-design-notes.md` (sub-plan #2, snapshot-only Option A), `2026-04-24-phase1-layer-tool-pause-injector-design-notes.md` + `…-layer-tool-pause-injector.md` (sub-plan #3, dual-path pattern).

---

## Executive summary — Recommendation

**Option A — thread `plate_id` through `NativeLibrary.loadModel(path, plateId)` / `SlicerEngine::loadModel(path, plate_id)` and call `Model::read_from_file(…, plate_id=N)` on the native side.** The OrcaSlicer BBS importer already accepts a `plate_id` argument and filters objects at load time (`bbs_3mf.cpp:1921-1940`, the "only load objects in plate_id" branch). With that single signature change, `BambuSanitizer.extractPlate` + its on-disk ZIP rewrite pass disappears from the production slice path in `SlicerViewModel.selectPlate`. Non-Bambu and single-plate loads continue to pass `plate_id = 0` (the "load all" default).

**Why not Option B (in-memory filter inside `slice()`):** Bambu `PlateData::obj_inst_map` is keyed by the XML `object_id`, while `g_model.objects[i]` uses Slic3r's runtime `ObjectBase::id().id`. Phase 0 sub-plan #4 explicitly called this "unresolved" and it is the same identity gap that blocked `ObjectSnapshot.objectId` reverse-mapping. Option B would require us to solve that mapping for slice-time correctness; Option A sidesteps it because BBS does the filter at ingestion, before `ModelObject`s are allocated.

**Why not "keep extractPlate, read from loaded g_model":** The remaining ZIP-rewrite responsibilities (strip `<assemble>`, renumber custom_gcode plate_info, filter `<build>`, synthesize `model_settings.config`) are all things BBS already does internally on a `plate_id > 0` load. Re-implementing them over native accessors is strictly additive complexity.

**Pre-requisite for Option A: native rebuild** (NDK 26 / Release / stripped). Non-negotiable, but small — only `SlicerEngine::loadModel` signature and its `Model::read_from_file` call site change. Authorized by the operator brief.

**Blast radius:** one Kotlin call-site (`SlicerViewModel.selectPlate:1091-1145`), one native signature + call site (`sapil_model.cpp:117`). `BambuSanitizer.extractPlate`, `BambuSanitizer.restructurePlateFile`, and `BambuSanitizer.buildSyntheticModelConfig` become dead code — but **we do not delete them in #2b**. They still have ~23 test-only callers in `BambuPipelineIntegrationTest`, `NativePreparePreviewTest`, `ProfileEmbedderIntegrationTest`, and `B95Plate9PaintStateTest`; pruning those tests is a follow-up.

**The scope firewall that matters most:** this sub-plan does **not** delete any Kotlin API surface in `BambuSanitizer` and does **not** remove any test that currently exercises `extractPlate` / `restructurePlateFile`. It removes exactly one production call-site. Follow-up cleanup is deferred.

---

## 1. Current architecture (As-Is)

### 1a. Every production and test caller of `BambuSanitizer.extractPlate`

Canonical grep (worktree): `extractPlate(`. Excludes docs/plans.

| File:line | Caller context | Production? |
|---|---|---|
| `app/src/main/java/com/u1/slicer/SlicerViewModel.kt:1105` | `selectPlate(plateId)` coroutine inside `SlicerViewModel` | **YES — only production call** |
| `app/src/androidTest/java/com/u1/slicer/slicing/BambuPipelineIntegrationTest.kt:131, 337, 367, 400, 437, 508, 559, 613, 673, 1028, 1047, 1074, 1102, 1134, 1213, 1288, 1327, 1349` | 18 calls across the 34 Bambu pipeline tests — plate-selection regression gates | Test-only |
| `app/src/androidTest/java/com/u1/slicer/viewer/NativePreparePreviewTest.kt:152, 208, 567, 607, 686` | 5 calls for Prepare-preview plate selection (multi-plate painted fixtures) | Test-only |
| `app/src/androidTest/java/com/u1/slicer/slicing/ProfileEmbedderIntegrationTest.kt:463, 490` | 2 calls for embed→slice end-to-end tests | Test-only |
| `app/src/androidTest/java/com/u1/slicer/slicing/B95Plate9PaintStateTest.kt:94, 130` | 2 calls for Buzz plate 9 bit-packed paint-state coverage | Test-only |

**Production callers: exactly one.** Everything else is test wiring that sets up a pre-extracted plate file for an assertion.

`restructurePlateFile` has the same pattern: one production caller (`SlicerViewModel.kt:1112`), 18 test callers.

### 1b. Zip-entry rewrite responsibilities of `extractPlate`

From `BambuSanitizer.kt:1519-1643`:

| Input entry | Rewrite action | Purpose | Can BBS importer do this for us? |
|---|---|---|---|
| `3D/3dmodel.model` | `filterModelToPlate` → strip non-plate `<build><item>` rows + optional re-centre + `stripUnreferencedResources` (BFS over `<component>` refs to drop orphan `<object>` blocks) | OrcaSlicer instantiates only objects present in `<build>`; stripping orphan `<resources>/<object>` entries keeps file lean | **YES** — `_load_model_from_file(..., plate_id=N)` loads only objects whose XML ids appear in `plater_data[N].obj_inst_map`, which maps 1:1 to the `<build>` filter. See `bbs_3mf.cpp:1921-1940`. |
| `Metadata/model_settings.config` | `stripAssembleSection` + `stripUnreferencedConfigObjects(referencedIds)` | `<assemble_item>` entries reference all plates' objects; OrcaSlicer's `_handle_start_assemble_item` aborts if any target id is missing from `m_objects` | **YES** — skipped when `plate_id > 0` because the non-selected objects never appear in `m_objects`. Verify: BBS loader ignores missing assemble_item targets silently in this case. If not, we add a small guard. |
| `Metadata/Slic3r_PE_model.config` | `stripUnreferencedConfigObjects(referencedIds)` | Same as above, PrusaSlicer-format parallel | Same as above. |
| `Metadata/custom_gcode_per_layer.xml` | `filterCustomGcodePerLayer(targetPlateId)` — keep only the `<plate><plate_info id=N/></plate>` block, rewrite to `id=1` | The BBS loader stores customGcode keyed by 0-based `plate_index`; with `plate_id > 0` filtering, other plates' entries simply don't load | **YES** — `bbs_3mf.cpp:3066-3129` populates `m_model->plates_custom_gcodes[plate_id - 1]` only for plates actually loaded. |
| Component `.model` files | Raw copy | Meshes referenced via `<component p:path=…>` links from the main model | **YES** — component files continue to be ingested on demand by `Model::read_from_file` regardless of `plate_id`. |
| All other entries | Raw copy | Thumbnails, relationships, content types, etc. | Not relevant post-migration — native doesn't consume them. |
| Synthesized `Metadata/model_settings.config` (if missing) | `buildSyntheticModelConfig(objectExtruderMap)` | When input is a Bambu 3MF with no model_settings.config but Kotlin derived per-object extruder assignments | Edge case. `ThreeMfInfo.objectExtruderMap` is the Kotlin side's XML-id-keyed map. For a BBS load with `plate_id=N`, per-object extruder comes from the 3MF's native `<metadata key="extruder">` parsing inside `bbs_3mf.cpp`. Verify with `SemmSlicingTest` + `BambuPipelineIntegrationTest#per_part_extruder_parsing`. |

### 1c. Data flow: user-picks-plate → slice → G-code (current)

```
user taps plate N
  ↓
SlicerViewModel.selectPlate(N) coroutine (Dispatchers.IO)
  ↓
[Kotlin disk IO]
  BambuSanitizer.extractPlate(sourceFile, N, workspaceDir, …)
    → plate${N}_<name>.3mf (single-plate filtered 3MF, on disk)
  ↓
BambuSanitizer.restructurePlateFile(plateFile, workspaceDir)
  → restructured_plate${N}_<name>.3mf (component-inlined if multi-color)
  ↓
ThreeMfParser.parseForPlateSelection(plateFile) — lightweight metadata
  ↓
SlicerViewModel.mergeThreeMfInfoForPlate(plateInfo, fileInfo, plateId)
  ↓
SlicerViewModel.embedProfile(plateFile, mergedInfo, workspaceDir)
  → embedded_plate${N}_<name>.3mf (Snapmaker profile injected)
  ↓
loadNativeModel(embeddedPlateFile)
  → NativeLibrary.loadModel(path)  — no plateIdx param
  → SlicerEngine::loadModel(path)   — no plate_id param
  → Model::read_from_file(path, …, plate_id=0)  — loads ALL plates (but file only has one)
  ↓
[user taps "slice"]
  ↓
SlicerEngine::slice(config) → print.apply(g_model, dpc) → print.process() → output.gcode
```

**Key observation:** by the time `slice()` runs, `g_model.objects` already contains only the selected plate's objects because extractPlate pre-filtered the file. Native slicing has no plate concept — it slices whatever's in `g_model`.

### 1d. Ancillary Kotlin code paths that read from the source file and are potentially affected

Out of scope for #2b but worth noting so the plan doesn't break them:

- `ThreeMfParser.parseForPlateSelection(plateFile)` — consumes the extractPlate output for `model_settings.config`. Post-migration the embedded-by-ProfileEmbedder file still contains the config; parseForPlateSelection is still called. Not touched by #2b.
- `SlicerViewModel.mergeThreeMfInfoForPlate(plateInfo, preSelectInfo, plateId)` — pure Kotlin composition, no 3MF IO. Not touched.
- `ProfileEmbedder.embed(plateFile, …)` — consumes a single-plate file today. **If we feed it the multi-plate source**, it re-writes all plates' meshes; larger output but correct. See Risk 6.
- `LayerToolPauseInjector.injectFrom3mf(gcodePath, model3mf, plateIdx, getPlateData)` — already migrated in sub-plan #3 to handle plate indices. Accepts the source 3MF today; post-#2b, production passes the same source. Not touched.

---

## 2. Native state already available

### 2a. What `Model::read_from_file(plate_id=N)` already does (OrcaSlicer BBS importer)

From `app/src/main/cpp/orcaslicer/src/libslic3r/Model.cpp:229-355`:

- Signature: `Model::read_from_file(input_file, config, config_substitutions, options, plate_data, project_presets, is_xxx, file_version, proFn, stlFn, project, plate_id, objFn)`.
- Default `plate_id = 0` means "load all plates" — what `SlicerEngine::loadModel` does today.
- When `plate_id > 0`:
  - `load_bbs_3mf(input_file, …, plate_id)` is called (Model.cpp:311).
  - Inside `_load_model_from_file`, after `m_plater_data` is populated, the loader iterates `m_objects` and checks `current_plate_data->obj_inst_map.find(object.first.second)` (bbs_3mf.cpp:1921-1940). Objects whose XML id is not in the target plate's `obj_inst_map` are skipped — they never become `ModelObject`s in `m_model->objects`.
  - `plates_custom_gcodes` is populated from the XML custom_gcode_per_layer block for all plates regardless (bbs_3mf.cpp:3066-3129), but since the map is keyed by `plate_index`, non-target plates' entries are just dead weight — not observed by callers looking for plate N.

**Convention:** BBS `plate_id` is **1-based** throughout `bbs_3mf.cpp` (`plate_id > 0 && plate_id <= m_plater_data.size()`). The `PlateData::plate_index` field stored **inside** that data is 0-based (`bbs_3mf.cpp:1486`, `plate->plate_index = raw-1`). The diff-harness-facing `nativeGetPlateCount/nativeGetPlateData` convention is **0-based** (per sub-plan #2). At the JNI boundary for #2b, we convert.

### 2b. What cannot be lifted from existing JNI surface

- No existing C++ accessor lets a caller slice *plate N* from a `g_model` populated with all plates. `slice()` always takes `getGlobalModel()` wholesale. Option B would need a new accessor. Option A obviates it.

### 2c. `Print::apply(model, dpc)` behaviour

From `sapil_print.cpp:901-961`:
- `model = getGlobalModel()` — raw reference to `g_model`.
- `print.apply(model, dpc)` is called once with the full model. OrcaSlicer's `Print::apply` then iterates `model.objects` and materialises `PrintObject`s from each.
- **Behaviour when `model.objects` is a proper subset of the original 3MF's objects:** this is exactly what happens today post-extractPlate — Print::apply handles it fine. Option A preserves this contract (only the set of objects changes; `Print::apply` still sees a clean `g_model`).

### 2d. `g_model` state management across plate switches

From `sapil_model.cpp:117-287`:
- `loadModel` calls `Slic3r::release_PlateData_list(g_plate_data_list)` then reconstructs `g_model` from the file via `Model::read_from_file`. Each call is a full reset.
- Per-plate switch today: extract → restructure → embed → loadModel. That's one disk-write-disk-read cycle plus one native parse per switch.
- Option A post-migration: embed → loadModel(path, plateId). One disk write (embed), one disk read (native parse). **Net win: one fewer disk write per plate switch**, plus the embed file is smaller because it doesn't need to be a filtered-to-one-plate artefact — but realistically the embed step today emits `embedded_plate${N}_…` already, so file size parity is approximately neutral.

---

## 3. The gap — what must change for Option A

Complexity ranking, smallest-to-largest:

1. **(smallest) `SlicerEngine::loadModel` signature** — grows an `int plate_id = 0` parameter, threaded straight into `Model::read_from_file`. **~3-line C++ change.** No new C++ logic; no new data structure; no new JSON emission.
2. **`NativeLibrary.loadModel` signature** — grows an `int plateIdx = -1` parameter (Kotlin convention: -1 ≡ all plates, ≥0 ≡ specific plate, converted to `plate_id + 1` at the JNI boundary). **One external-fun declaration + one JNI-C wrapper.**
3. **`SlicerViewModel.selectPlate` production call-site** — replace the `extractPlate + restructurePlateFile` + `parseForPlateSelection + embedProfile(plateFile, …)` chain with `parseForPlateSelection(sourceFile) + embedProfile(sourceFile, …) + native.loadModel(embeddedFile, plateId - 1)`. **~15 lines of Kotlin delta**.
4. **Native rebuild** — per `CLAUDE.md`'s "Native Rebuild" checklist. NDK 26, Release, `-j1`, strip, verify 19-21 MB + clang 17.0.2. Uses the existing build dir at `app/.cxx/Debug/ndk26release/arm64-v8a/`. Incremental; 2-15 min.

### 3a. What does NOT change (by design)

- `BambuSanitizer.extractPlate`, `restructurePlateFile`, `buildSyntheticModelConfig`, and their private helpers **stay in place, unchanged**. Still linked in, still exercised by 23 test callers. Deletion is a follow-up clean-up (post-v1.7.0 or as a separate sub-plan #2c).
- `ThreeMfParser.parseForPlateSelection` stays. Still consumes a 3MF file (now the source/embedded file, not the extracted one) and still populates the plate-level `ThreeMfInfo` used for the merge.
- `BambuSanitizer.process` (the pre-load sanitiser) stays. It runs BEFORE the select-plate path and does global nil-replacement / array-normalisation / paint-data preservation that is orthogonal to plate extraction.
- `SlicerViewModel.mergeThreeMfInfoForPlate` stays. Pure Kotlin data composition.
- `ProfileEmbedder.embed` takes a different input file (multi-plate source instead of single-plate extracted) but the API surface is unchanged. Internal behaviour is "clean XML + inject Snapmaker profile config", which is plate-agnostic.
- Sub-plan #3's `LayerToolPauseInjector` dual-path. `plateIdx = currentPlateId - 1` threading already in place; just keep passing it from `SlicerViewModel.kt:2353` unchanged.

### 3b. What does `restructurePlateFile` do that Option A might miss?

`restructurePlateFile` at `BambuSanitizer.kt:1668-1758` inlines component-mesh `.model` files into `3D/3dmodel.model` when the extracted plate has multi-color components. It's a workaround for OrcaSlicer's inability-as-we-observed to handle per-volume extruder assignment across component refs in all paths.

Open question: **does BBS `plate_id`-filtered load path produce per-volume extruder assignment correctly for multi-colour component files?**

Evidence suggests **yes** in the normal case: `parseModelSettingsExtruders` at `BambuSanitizer.kt:…` walks `<object>/<part>` pairs and resolves per-volume extruders from the config. Orca's `_BBS_3MF_Importer` does the same inside `bbs_3mf.cpp` (fuller coverage, handles component refs natively). The bug that created `restructurePlateFile` was specific to the flow where Kotlin wrote a partial `model_settings.config` that confused Orca's per-plate matching. With native `plate_id`-filtered load, `model_settings.config` is consumed in full by the BBS loader — no per-plate Kotlin rewrite happens.

**Test gate:** `BambuPipelineIntegrationTest#dragon_scale_multi_color_plate` (1047-1055) and the Flarewing, Shashibo, flippy multi-color fixtures. If the G-code tool counts differ post-migration, `restructurePlateFile` or an equivalent must be kept invoked — but it can no longer run on the filtered plateFile; it would need to run on the native-loaded state. **If the test regresses, the migration falls back to Option A-prime: keep extractPlate → restructurePlateFile for multi-color files but use native `plate_id` for single-color files.** Covered in Risk 4.

---

## 4. Migration strategies — option matrix

| Option | Summary | Native rebuild? | Disk-IO per plate switch | Risk | Rollback |
|---|---|---|---|---|---|
| **A — thread `plate_id` through loadModel** | `SlicerEngine::loadModel(path, plate_id=N)` passes through to `Model::read_from_file(plate_id=N)`. Kotlin skips extractPlate + restructurePlateFile in production. | **YES (small)** | Down from 2 writes + 1 read to 1 write (embed) + 1 read (load) | Medium: restructure may be needed for multi-color; BBS loader behaviour on edge cases (missing assemble targets, synthetic model_settings) unverified | `git revert` of production call-site commit (leaves native rebuild in place; harmless, it's a pure extension of the signature) |
| **A-prime — Option A for single-colour plates only; keep extractPlate+restructure for multi-colour** | Branch on `info.hasMultiExtruderAssignments || info.hasPaintData`: single-colour single-object plates go native; multi-colour keeps Kotlin path. | YES (same size as A) | Partial | Lower than A (preserves the known-good multi-colour path) | Same as A |
| **B — in-memory filter inside `slice()` / new `slicePlate(N)` entry** | Keep load-all-plates; add `slicePlate(plateIdx)` that temporarily filters `g_model.objects` by `PlateData::obj_inst_map` key match | YES (larger C++ change) | Down from 2 writes + 1 read to 0 writes + 1 read (load once, slice many plates) | **HIGH**: requires XML-id ↔ runtime-ObjectID mapping (unresolved from sub-plan #4). Risk that `Print::apply` misbehaves on a pre-filtered subset. | Kotlin side reverts trivially; C++ side needs revert commit. |
| **C — Kotlin-side "extractPlate from g_model"** | After `loadModel(sourceFile, 0)`, query `nativeGetPlateData(N)` + synthesize plate-filtered 3MF on disk from native accessors | NO | Still 1 write + 1 read (synthesised file + native re-load) | **HIGHEST**: re-implements BBS importer in Kotlin-on-top-of-JNI. Cost comparable to #2b's whole budget; minimal gain. | N/A |
| **D — dual-path** | Run extractPlate + Option A side-by-side; compare G-code outputs | YES (Option A rebuild) | 3× (both paths run) | Low blast radius but no convergence signal at the G-code level — G-code is stochastic with TBB / print options | N/A |

**Recommendation: Option A, with Option A-prime as a hot patch if multi-colour regressions surface.**

### 4a. Dual-path analogy to sub-plan #3

Sub-plan #3 locked in a permanent dual-path (native primary, XML fallback on null/empty). Analogue here would be: try `native.loadModel(path, plateId)`; if the native load produces 0 objects or a malformed state (detected via `getModelInfo().triangle_count == 0` or a new diagnostic), fall back to `extractPlate + loadModel(plateFile, 0)`. The fallback keeps the extractPlate code path covered and gives us an emergency escape hatch in the (unlikely) case BBS plate_id-filter drops objects that the Kotlin path would have kept.

**This is the resilient design.** It costs one extra conditional branch and no extra disk IO on the happy path. Plan includes it under Task sequence step 4.

### 4b. Interaction with sub-plan #3

Sub-plan #3 hit Risk 3 in the original design notes: `g_model.plates_custom_gcodes` goes null post-slice, forcing the XML fallback to be permanent. The XML fallback opens the **source 3MF** (`sourceModelFile` or `currentModelFile`) to read `custom_gcode_per_layer.xml` — this is file path-bound, not model-state-bound. Post-#2b, the file paths in use are:

- `sourceModelFile = embeddedPlateFile` today (set at `SlicerViewModel.kt:1138`). Post-migration, `sourceModelFile` is still set to the embedded file (which is now derived from the **source** not the extracted plate — so it still contains `custom_gcode_per_layer.xml` with all plates). LayerToolPauseInjector's `filterCustomGcodePerLayer` (when XML path runs) will see all-plates XML. It currently calls non-plate-aware `parseLayerToolSegments` which processes every `<layer>` regardless of `<plate_info id=…>`. Pre-#2b: it sees only plate N's entries because extractPlate pre-filtered them. **Post-#2b: it sees all plates' entries and would inject pauses for all of them into the (single-plate) G-code.** That's a silent correctness bug for multi-plate files.

**Mitigation required in #2b:** either
- (m1) filter `custom_gcode_per_layer.xml` at `embedProfile` time to the selected plate (plate-aware equivalent of `filterCustomGcodePerLayer`) — this is the simplest fix; one ~20-line addition to `ProfileEmbedder.embed` or a side-helper; OR
- (m2) thread `plateIdx` into `LayerToolPauseInjector.extractPauseTargets` and use `parseLayerToolCustomGcodeXmlPerPlate` there instead of `parseLayerToolSegments`.

(m1) is better because it keeps `LayerToolPauseInjector`'s scope firewall in sub-plan #3 untouched (we do NOT touch `LayerToolPauseInjector`, per the operator brief).

**Upshot:** if #2b lands (m1), the XML fallback stays correct for multi-plate sources because the embedded file's XML is already plate-filtered. If we forget (m1), every painted multi-plate fixture (flippy, Shashibo, Dragon, S-Buttons, Buzz plate 9 painted) will inject extra pauses. This is the single biggest correctness trap of sub-plan #2b and **must be covered by the plan as its own task with a regression test**.

### 4c. Can sub-plan #3's XML fallback retire as a result?

No. The XML fallback fires when `nativeGetPlateData(plateIdx)` returns null or empty; that's still true post-#2b because the post-slice null behaviour (Risk 3 of sub-plan #3) is caused by `g_model` mutation during `Print::process()`, not by how the model was loaded. The fallback stays. Call out as a follow-up in Section 9.

---

## 5. Test strategy

### 5a. Primary regression gates (must stay green)

| Test class | Count | What it guards |
|---|---:|---|
| `BambuPipelineIntegrationTest` | 34 | Plate selection, position-based plate extraction, B23 extruder map after restructure, per-part extruder parsing, B54 modifier volume subtype preservation, B82 per-plate layer-tool chip count |
| `SemmSlicingTest` | 5 | SEMM (paint) slicing pipeline — 2-extruder, 4-extruder, H2C benchy 7-colour G-code, SEMM tool remap, B64 Flarewing colour permutation |
| `GoatDedupeSemmTest` | 1 | B76 Goat paint-state dedupe |
| `SensoryTwistSupportsTest` | 1 | B77 paint_supports + enable_support |
| `B95Plate9PaintStateTest` | 2 | Buzz plate 9 bit-packed paint state drop fix |
| `ProfileEmbedderIntegrationTest` | 13 | Full embed→slice→inject pipeline incl. `flippyFlappyMini_fullPipeline_emitsLayerChangePauseGcode` (the one that hit sub-plan #3's Risk 3) |
| `NativePreparePreviewTest` | 16 | Multi-plate Prepare mesh regressions (Buzz, Shashibo, Korok, Dragon, calicube, 3DBenchy, old.3mf, flippy) |
| `PreparePreviewViewModelTest` | 14 | End-to-end Prepare state for Dragon plate 3, H2C benchy, B83 plate-switch objectIds stable-source, B86 S-Buttons 4-distinct-colour, B92.1 parsedGcode StateFlow |
| `BambuParserDifferentialTest` | 21 | Diff-harness baseline — must stay at 0 entries |
| `NativePlateDataTest` | 5 | `nativeGetPlateData` accessor smoke (plate count, colored_3DBenchy single-plate, Buzz multi-plate, flippy painted) |
| `NativeObjectExtruderMapTest` | 3 | `nativeGetObjectExtruderMap` accessor smoke |
| `NativeLibraryCorrectnessTest` | 12 | JNI symbol sanity incl. `nativeGetProjectConfig` |

Total primary gate: **127 instrumented tests**.

### 5b. Required-green fixtures

All of these must produce identical G-code (or at least equivalent tool-count invariants) before and after migration:

- `Buzz Lightyear multi-plate.3mf` — plate 8 (bit-packed paint) + plate 9 (B90/B95 bit-packed paint state drop)
- `Flarewing Dragon.3mf` — SEMM colour permutation (B64)
- `flippy+flappy+mini.3mf` — painted + layer-tool
- `Shashibo.3mf` — painted plate 5 (B78)
- `Korok Mask.3mf` — non-Bambu painted (B51)
- `calicube.3mf` — basic multi-colour Bambu
- `colored_3DBenchy.3mf` — canonical Bambu multi-material
- `Dragon Scale.3mf` — older-format shared-component multi-plate
- `Sensory Twist Ball.3mf` — B77 paint_supports
- `Goat.3mf` — B76 H2C paint-state folding
- `S-Buttons.3mf` — B86 user-like presets
- `old.3mf` — B51 bounding box regression guard
- `H2C benchy 7-color.3mf` — full-pipeline colour preservation

### 5c. New test needed

**Exactly one new instrumented test, in `BambuPipelineIntegrationTest`:**

`nativePlateIdFilter_loadModelWithPlateId2_objectCountMatchesPlate2` — loads a known multi-plate fixture (colored_3DBenchy or calicube), calls `lib.loadModel(path, 1)` (plateId=1 ≡ 0-based, which is BBS `plate_id=2`), asserts `lib.getModelInfo().volume_count` matches the expected number of volumes for plate 2. This directly exercises the new signature and its 1-based↔0-based conversion, in isolation from the SlicerViewModel coroutine.

**Optional** (but recommended for m1 coverage):

`multiPlatePaintedCustomGcode_embeddedFileContainsOnlyTargetPlateLayers` — for a painted multi-plate fixture, after `embedProfile(sourceFile, info, outDir)` with a selected plate index, open the embedded file and assert `custom_gcode_per_layer.xml` contains exactly the target plate's `<plate>` block. Guards against the Section 4b (m1) regression.

### 5d. Tests that stay unchanged

All 23 test callers of `extractPlate` / `restructurePlateFile` in `BambuPipelineIntegrationTest`, `NativePreparePreviewTest`, `ProfileEmbedderIntegrationTest`, `B95Plate9PaintStateTest` **stay unchanged**. They exercise the Kotlin plate-extraction path in isolation and validate invariants of the extractPlate output. That path is still linked in. We don't delete it.

If #2b later proves robust in v1.7.0+, a sub-plan #2c deletes `extractPlate` + `restructurePlateFile` + their tests. Not in scope here.

### 5e. Tests that SHOULD fail if the migration breaks correctness

- `BambuPipelineIntegrationTest#end_to_end_plate_slice_produces_correct_tool_counts_for_plate_5_of_multi_color` (or equivalent, grep for "plate, 5" + per-part extruder) — every painted multi-plate G-code regression lands here.
- `SemmSlicingTest#flarewingDragon_semmPermutation_remapsGcodeToolIndices` — any SEMM regression.
- `ProfileEmbedderIntegrationTest#flippyFlappyMini_fullPipeline_emitsLayerChangePauseGcode` — sub-plan #3's guard; fires if pauses end up wrong (Section 4b (m1) regression directly trips this).

---

## 6. Known risks

### Risk 1 — BBS `plate_id`-filtered load misses objects that Kotlin extractPlate would keep

**Severity:** High if it happens on any named fixture.
**Scenario:** an older-format 3MF (Dragon Scale / Shashibo style, `hasPlateJsons=false`) has build items that share component refs across plates. BBS loader's object-filter gate at `bbs_3mf.cpp:1936` uses `current_plate_data->obj_inst_map` which is built from the XML. If the XML file doesn't name plate-N's objects in `<plater/<instance object_id=…>` tags, they get dropped.
**Mitigation:** Task 2 in the plan runs the two "shared component" fixtures (Dragon Scale, Shashibo) on the native-filtered load and compares to pre-migration G-code or known-good tool counts. If either regresses, fall back to Option A-prime.
**Acceptance gate:** `BambuPipelineIntegrationTest#*dragonScale*` + `*shashibo*` must stay green.

### Risk 2 — Multi-colour per-volume extruder assignment regression

**Severity:** Medium.
**Scenario:** `restructurePlateFile` currently inlines component meshes and writes a compound-object `model_settings.config` for OrcaSlicer's per-volume extruder path. If the native BBS loader doesn't produce equivalent per-volume assignment from the raw-file `model_settings.config`, painted multi-colour plates would regress.
**Evidence:** OrcaSlicer's own UI slices multi-colour multi-plate files without any Kotlin restructure step. So the native path is known to work upstream. The Kotlin path exists because of an earlier workaround, not a fundamental need.
**Mitigation:** `BambuPipelineIntegrationTest` (multi-colour restructure test) + `SemmSlicingTest` run in the plan's task 4 verification. If any tool count off-by-even-one, Option A-prime kicks in.

### Risk 3 — Post-slice `g_model` mutation (sub-plan #3 precedent)

**Severity:** Low for #2b (doesn't break slice itself; may cause sub-plan #3 XML fallback to trigger more often).
**Scenario:** `Print::process()` mutates `g_model` state. Post-migration, `g_model` is loaded fresh on each plate selection, then sliced once. The post-slice state is irrelevant to future plate selections (they clear + re-load). This is the same behaviour as pre-migration. No new exposure.
**Mitigation:** none needed; surface signal is already caught by sub-plan #3's dual-path.

### Risk 4 — `custom_gcode_per_layer.xml` cross-plate contamination in embedded file

**Severity:** HIGH — silent G-code corruption on painted multi-plate fixtures.
**Scenario:** extractPlate filters `custom_gcode_per_layer.xml` to the target plate and renumbers `plate_info id` to 1. Post-migration the source file flows through `ProfileEmbedder.embed` as-is. `ProfileEmbedder.embed` drops `custom_gcode_per_layer.xml` entirely today for native slice (`ProfileEmbedder.kt:573-575`). Good — that means the **embedded file** has no `custom_gcode_per_layer.xml`. Therefore `LayerToolPauseInjector`'s XML-fallback path also sees no entries, and the native path is forced (per sub-plan #3).
**But:** `sourceModelFile` in `SlicerViewModel.kt:2347` is the **source/selected plate file** — today that's the embedded plate file (which had layer-tool XML stripped by embed). Post-migration, `sourceModelFile = embeddedPlateFile` = the **source**-derived embedded file, same strip behaviour.
**Wait — re-check:** the injector reads `layerToolMetadataFile` which defaults to `sourceModelFile ?: currentModelFile`. These are set at `SlicerViewModel.kt:1117` (`sourceModelFile = plateFile` — the **restructured, not-yet-embedded** file today, which still contains custom_gcode_per_layer.xml). Post-migration, if we drop `plateFile` entirely, `sourceModelFile` gets set to the embedded file (custom_gcode stripped) — and the injector XML fallback dies, leaving only the native path. **Which is the Risk 3 null-post-slice scenario from sub-plan #3**: native returns null, XML has nothing, no pauses injected, painted multi-plate prints wrong.
**Mitigation:** EITHER
- (m1) have `embedProfile` keep `custom_gcode_per_layer.xml` in the embedded file but filter to the target plate (equivalent to extractPlate's filterCustomGcodePerLayer). Adds ~20 Kotlin lines to `ProfileEmbedder.embed` under a `plateId: Int?` parameter. LayerToolPauseInjector's XML fallback keeps working.
- (m2) keep `sourceModelFile` = the raw multi-plate source file (not embedded), and thread plateIdx through the injector to a plate-aware XML parser. This is closer to what extractPlate did anyway.

**(m1) is cleanest because it doesn't change `LayerToolPauseInjector` (scope firewall).** Plan task 3.
**Plan-mandatory regression test:** `ProfileEmbedderIntegrationTest#flippyFlappyMini_fullPipeline_emitsLayerChangePauseGcode` + analogous multi-plate fixture.

### Risk 5 — Embedded file size increase (multi-plate source gets embedded)

**Severity:** Low.
**Scenario:** today `embedded_plate${N}_<name>.3mf` contains only plate N's meshes (extracted+restructured first). Post-migration it contains all plates' meshes; only the `<build>` filter happens at native load time. For Buzz Lightyear (~50 MB, 9 plates), the embedded file grows from ~6 MB to ~50 MB per plate switch.
**Mitigation:** acceptable in exchange for one fewer disk-write step. If profiling shows the embed pass is now the bottleneck, a follow-up can stream-clean + plate-filter inside embed. Not #2b scope.

### Risk 6 — `ProfileEmbedder.embed` compatibility with multi-plate input

**Severity:** Low.
**Scenario:** embed's `when` branches don't mention a "multi-plate" mode but they operate on a per-entry basis and don't assume single-plate. The only plate-sensitive strip is `custom_gcode_per_layer.xml` (Risk 4). Other entries (Bambu-cleaned main model, component files, project_settings.config) are plate-agnostic.
**Mitigation:** covered by Risk 4 mitigation (m1).

### Risk 7 — Native rebuild failure mode

**Severity:** Low (recoverable).
**Scenario:** the existing build dir `app/.cxx/Debug/ndk26release/arm64-v8a/` may have stale CMakeCache state. If incremental build fails, fresh build takes 30-60 min.
**Mitigation:** per `CLAUDE.md` "Native Rebuild" section. Plan includes the full recipe. Abort criterion: two rebuild attempts failed.

### Risk 8 — `plateObjectIds` / `objectExtruderMap` parameters go unused

**Severity:** Cosmetic.
**Scenario:** post-migration `selectPlate` no longer computes `hasPlateJsons`, `plateObjectIds`, `plateExtruderMap`. Those fields of `ThreeMfInfo` stay populated (file-level parse still reads them). No dead code in `ThreeMfInfo`; just dead code in the production flow. Follow-up can prune in #2c.

### Risk 9 — B78 load-time instance offsets mismatch

**Severity:** Low.
**Scenario:** `loadNativeModel` at line 1205 snapshots `native.getInstanceOffsets()` post-load. Today those are plate-N's pre-filtered offsets. Post-migration BBS `plate_id=N` loads only plate N's instances — should produce equivalent offsets. Verify by `NativePreparePreviewTest#B78_Shashibo*`.
**Mitigation:** test gate.

---

## 7. Scope firewall for sub-plan #2b

Sub-plan #2b **must NOT touch:**
- `app/src/main/java/com/u1/slicer/gcode/LayerToolPauseInjector.kt` — sub-plan #3's recently-landed dual-path. Keep the getPlateData function-reference contract intact.
- `app/src/main/java/com/u1/slicer/bambu/LayerToolCustomGcodeXml.kt` — non-injector callers in `ThreeMfParser` and viewer tests still use `parseLayerToolSegments` and `parseLayerToolCustomGcodeXmlPerPlate`.
- `app/src/main/java/com/u1/slicer/bambu/ThreeMfParser.kt` — `parseLayerToolSegments` definitions, `parseLayerToolCustomGcodeXml` definitions, `parseForPlateSelection` (we call it but don't modify it).
- `app/src/main/cpp/extern/` — vendored libraries. Use `git restore --` before every stage.
- The diff-harness `known-disagreements.json` — should stay at 0 entries (no snapshot field changes). If it moves, something is wrong.

Sub-plan #2b **may touch:**
- `app/src/main/cpp/src/sapil_model.cpp` — `SlicerEngine::loadModel` signature + `Model::read_from_file` call.
- `app/src/main/cpp/include/sapil.h` — mirror the signature change.
- `app/src/main/cpp/src/sapil_jni.cpp` (or equivalent JNI wrapper for loadModel) — add the `plateIdx` parameter.
- `app/src/main/java/com/u1/slicer/NativeLibrary.kt` — `external fun loadModel(path: String, plateIdx: Int = -1): Boolean` (default keeps existing Java callers binary-compatible if Kotlin overloads correctly; realistically all call sites need updating because the Kotlin external declaration is not defaulted-in-JNI).
- `app/src/main/java/com/u1/slicer/SlicerViewModel.kt:1091-1145` — `selectPlate` coroutine. Replace extractPlate + restructurePlateFile + parseForPlateSelection(plateFile) chain with parseForPlateSelection(sourceFile) + embedProfile(sourceFile, plateId) + native.loadModel(embeddedFile, plateId - 1).
- `app/src/main/java/com/u1/slicer/bambu/ProfileEmbedder.kt` — `embed(inputFile, config, outputDir, info, extruderRemap, plateId: Int? = null)`. When `plateId != null`, keep `custom_gcode_per_layer.xml` but filter to plateId via a reused `BambuSanitizer.filterCustomGcodePerLayer` helper (already exists at `BambuSanitizer.kt:1624`, private — widen to internal).
- `app/src/main/java/com/u1/slicer/bambu/BambuSanitizer.kt:1624` — widen `filterCustomGcodePerLayer` from `private` to `internal` so ProfileEmbedder can call it. No behaviour change.
- **Every other call-site of `NativeLibrary.loadModel`** (verify grep: there are a handful in `SlicerViewModel` + test code). Add `plateIdx = -1` or equivalent to each. This is a signature-change-propagation task.

Sub-plan #2b scope-flags for future:
- Deleting `extractPlate` / `restructurePlateFile` and their 23 test callers → **FOLLOW-UP** (sub-plan #2c, post-v1.7.0).
- Retiring sub-plan #3's XML fallback → **FOLLOW-UP** (depends on `Print::process()` not clobbering `g_model.plates_custom_gcodes`; that's a C++ investigation, separate sub-plan).
- `nativeGetProjectConfig` emitting `machine_pause_gcode` / `nozzle_temperature` → **FOLLOW-UP** (retires LayerToolPauseInjector's zip re-open; orthogonal).

---

## 8. Suggested commit sequence

Each commit is individually revertable. Target sequence is 5 commits (plus the rebuild as its own commit when the source change touches `.so`-generating code).

1. **`phase1(bambu-native): thread plate_id through SlicerEngine::loadModel + JNI + NativeLibrary` + rebuild**
   Source: `sapil.h`, `sapil_model.cpp`, JNI wrapper, `NativeLibrary.kt`. Binary: `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so` (stripped Release). **One commit** — source change + binary must match to keep the diff harness installable. Tests: `NativeLibraryCorrectnessTest` (12) + new smoke test for plate_id filtering.

2. **`phase1(bambu-native): filterCustomGcodePerLayer visibility internal for ProfileEmbedder access`**
   One-line widening. Trivial. No test impact.

3. **`phase1(bambu-native): ProfileEmbedder.embed gains plateId and filters custom_gcode_per_layer.xml`**
   Adds `plateId: Int? = null` param + internal-use of `BambuSanitizer.filterCustomGcodePerLayer`. Tests: new instrumented test in `ProfileEmbedderIntegrationTest` asserting plate-filtered customGcode in embedded output. `flippyFlappyMini_fullPipeline_emitsLayerChangePauseGcode` must continue to pass.

4. **`phase1(bambu-native): SlicerViewModel.selectPlate uses native plate_id filter (sub-plan #2b)`**
   Drops extractPlate + restructurePlateFile calls from production `selectPlate`. Keeps them linked in for tests. Tests gated: full `BambuPipelineIntegrationTest` + `SemmSlicingTest` + `GoatDedupeSemmTest` + `B95Plate9PaintStateTest` + `ProfileEmbedderIntegrationTest` + `NativePreparePreviewTest` + `PreparePreviewViewModelTest` + `SensoryTwistSupportsTest`.

5. **`docs(phase1): sub-plan #2b landed` + `docs(CLAUDE.md): test counts + convention note`**
   Append "Follow-up landed" to `MORNING_STATUS.md`. Bump `CLAUDE.md` test counts for the new tests (likely +1 instrumented for the plate_id smoke test, +1 for the embed plate-filter guard).

Checkpoint gate after commit 4: if any test fails three fix attempts, abort per the plan's Hard Abort Criteria (mirrors sub-plan #3).

---

## 9. Open questions

1. **Does `Model::read_from_file(…, plate_id=N)` populate `ModelObject.input_file` correctly?** `Model.cpp:342` sets `o->input_file = input_file` after load. Post-migration `input_file` is the source/embedded multi-plate file, not a per-plate derivative. If any downstream code keys off `input_file` for plate identity, it needs revisiting. Quick check: grep for `input_file` references; escalate if any.

2. **Does `ProfileEmbedder.embed` need to strip non-target plates' meshes from `3D/3dmodel.model` to keep embedded-file size manageable?** Section 4b Risk 5 calls this out as Low-severity. If profiling after Task 4 shows a >2× cold-load latency regression on Buzz (nine plates, large), consider plate-filtering in embed too (a Kotlin port of extractPlate's `filterModelToPlate`). Deferred unless measured.

3. **BBS loader's behaviour on `model_settings.config` with `<assemble_item>` references to objects that don't load under `plate_id=N`**: the comment in `stripAssembleSection` (`BambuSanitizer.kt:1818-1830`) says "_handle_start_assemble_item fails with 'can not find object' when an assemble_item references an object_id that is not present in m_objects." If BBS loader on `plate_id > 0` skips assemble_items for non-loaded objects cleanly, great. If it errors, we need an equivalent strip-or-filter — which would force a C++ change or a `model_settings.config` rewrite inside `ProfileEmbedder.embed`. **Plan's Task 1 verification gate must include a manual run against a Bambu 3MF with assemble_items (Dragon Scale, Shashibo) before the migration commit.** If the BBS loader errors, the plan reverts to Option A-prime.

4. **`g_model_preview_extruders` populated by `parsePreviewExtrudersFromModelConfig`** (`sapil_model.cpp:80-114, 198-214`) — sources per-object preview extruders from the raw model_settings.config ZIP entry. Does this logic need plate-scoped filtering when the load is plate-id filtered? If `m_objects.size()` is now plate-N's object count (not all-plates), `g_model_preview_extruders` must match positionally. Needs verification.

5. **`cancelPlateSelection`** (`SlicerViewModel.dismissPlateSelector`) currently clears `currentModelFile` and `sourceModelFile`. Post-migration, those fields hold the embedded file. Cancellation behaviour should be unchanged. No plan action, but flagged.

6. **Worktree `.so` installability check** — the plan should include a `ls -la` + `llvm-readelf -p .comment` step in Pre-flight after the native rebuild commit, not just before. Belt-and-braces against a wrong-strip.

---

## Appendix A — Approximate line-count estimate

| File | Estimated delta |
|---|---:|
| `sapil_model.cpp` | +3 / -1 |
| `sapil.h` (or `SlicerEngine` declaration header) | +1 / -1 |
| `sapil_jni.cpp` (JNI wrapper for loadModel) | +3 / -1 |
| `NativeLibrary.kt` (external fun loadModel) | +2 / -1 |
| `SlicerViewModel.kt` (selectPlate + signature propagation) | +10 / -15 |
| `ProfileEmbedder.kt` (embed plateId param + custom_gcode filter) | +20 / -0 |
| `BambuSanitizer.kt` (filterCustomGcodePerLayer visibility) | +1 / -1 |
| New instrumented test for plate_id filter smoke | +~40 |
| New instrumented test for embed plate-filtered customGcode | +~40 |
| `.so` rebuild (binary change, not a Kotlin-LOC delta) | ~20 MB binary |
| `CLAUDE.md` | +3 / -3 |
| `MORNING_STATUS.md` append | +~40 |
| **Net** | **~80-100 Kotlin LOC delta, ~10 C++ LOC delta, 1 native rebuild** |

Compared to sub-plan #3's ~90 Kotlin LOC delta, #2b is comparable in scope but touches C++ and needs the rebuild. That's the single meaningful difference in risk cost.

---

## Appendix B — Hard Abort criteria summary (to be carried into the plan)

- Task 1 rebuild verify: `.so` is not Release or not NDK 26 → abort, re-run rebuild recipe.
- Task 1 smoke test: `NativeLibraryCorrectnessTest` regresses → abort, debug the JNI wrapper.
- Task 4: `ProfileEmbedderIntegrationTest#flippyFlappyMini_fullPipeline_emitsLayerChangePauseGcode` fails → Risk 4 mitigation m1 is broken; stop, escalate.
- Task 4: `BambuPipelineIntegrationTest` plate-selection regresses on three fix attempts → fall back to Option A-prime, escalate.
- Task 4: `SemmSlicingTest` or `GoatDedupeSemmTest` regresses → multi-colour invariant broken; abort.
- Any test outside `slicing/`, `bambu/`, `native/`, `viewer/`, `gcode/` packages regresses → out-of-scope edit leaked; revert stray change.
- Pixel 8a install cycle fails twice in a row after `adb kill-server; adb start-server` → WIP commit + escalate.
