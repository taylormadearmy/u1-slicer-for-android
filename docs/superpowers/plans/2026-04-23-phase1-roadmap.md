# Phase 1 Roadmap — Bambu Via Native Loader

**Date:** 2026-04-23
**Branch:** `refactor/bambu-via-native-loader`
**Status:** Forward-looking skeleton — each sub-plan below gets its own detailed TDD plan when work begins.

---

## Purpose

Phase 0 (the diff harness in [`2026-04-23-bambu-diff-test-harness.md`](2026-04-23-bambu-diff-test-harness.md)) is green. The baseline at `app/src/androidTest/assets/diagnostics/known-disagreements.json` records 664 field disagreements across 21 fixtures. Every disagreement is a Kotlin parser doing work the native loader already does (or vice versa). Phase 1 closes the baseline one subsystem at a time, adding small JNI accessors that read fields already living in `g_model`, populating [`KotlinBambuSnapshot`](../../../app/src/main/java/com/u1/slicer/bambu/snapshot/KotlinBambuSnapshot.kt)'s currently-empty fields from them, and deleting the redundant Kotlin parsers.

**Baseline distribution (from counting `known-disagreements.json` by path segment):**

| Bucket                           | Entries |
|----------------------------------|--------:|
| `volumes[*]` (painted facets)    |     420 |
| `plates[*].plateIndex`           |      54 |
| `plates[*].objectInstanceMap`    |      54 |
| `plates[*].filamentSettingsIds`  |      54 |
| `plates[*].filamentColours`      |      26 |
| `objects.size`                   |      20 |
| `fileVersion`                    |      20 |
| `plates[*].customGcode`          |      11 |
| `plates[*].plateConfig`          |       2 |
| `objects[*]`                     |       2 |
| `plates.size`                    |       1 |
| **Total**                        | **664** |

**Sub-plan #1** — "Painted facets → preview mesh" — gets its own design doc + TDD plan in a dedicated session. It closes the 420 `volumes[*]` entries. The four sub-plans below cover the remaining 244.

**Effort calibration against Phase 0.** Task 5 of Phase 0 (per-plate native dump) and Task 7 (per-volume paint state dump) were each roughly M — a targeted walk over a well-documented Orca data structure, a new JSON emitter section, a matching Kotlin populator update, one native rebuild. The small Kotlin-only tasks (snapshot model, diff) were S. Phase 1 sub-plans that mirror an existing Phase 0 task are M; sub-plans that additionally delete a large Kotlin parser and migrate its callers are L.

---

## Dependency ordering (recommended execution order)

1. **#1 Painted facets** — gates #2 indirectly because `ThreeMfMeshParser` and `mergeThreeMfInfoForPlate` both read paint info; decoupling mesh coloring from Kotlin-parsed paint data first makes everything downstream safer. (Separate plan.)
2. **#5 Project config + filament colours** — smallest, cheapest, closes 46 entries (`filamentColours` + `fileVersion` + misc) and establishes the JNI pattern for reading `Model::config` (the project-level `DynamicPrintConfig`). Recommended **before** #2 despite the strategy doc's listed order, because #2's plate-level filament fallback already reads the project config — landing #5 first means #2's accessor is simpler.
3. **#2 Per-plate PlateData** — the big one: 164+ entries across five different plate fields. Depends on #5 so plate-level filament data can defer to project-level fallback without re-parsing.
4. **#4 Object extruder map** — small (22 entries between `objects.size` and `objects[*]`) but touches every merge path; easier once #2 has established the plate→object relationship over JNI.
5. **#3 Custom gcode per layer** — last because its only remaining Kotlin consumer (`LayerToolPauseInjector`) still needs `topZ` at slice time; the accessor is mechanical but we should leave it for when the other plate-scoped work is already land.

(See "reordering vs strategy doc priority" in the report at the end.)

---

## Sub-plan #2: Per-plate `PlateData`

**Closes baseline entries:** ~165 (54 `plateIndex` + 54 `objectInstanceMap` + 54 `filamentSettingsIds` + 2 `plateConfig` + 1 `plates.size`)
**Production code touched:** `BambuSanitizer.extractPlate`, `ThreeMfParser.parseForPlateSelection`, `ThreeMfParser.parse` (plate objectIds loop), `SlicerViewModel.mergeThreeMfInfoForPlate`
**Estimated effort:** L — three Kotlin code paths consume plate data today (restructuring for slice, UI preview, merge); each needs migration plus a large native rebuild.
**Dependencies:** #1 (painted facets, so mesh coloring no longer reaches into plate re-parsing), #5 (so plate filament fallback reads the already-exposed project config).

### JNI accessor signature

Phase 0 already emits full per-plate data into the snapshot JSON via `append_plate` in `sapil_bambu_snapshot.cpp`. Sub-plan #2 promotes that payload from "diff-only artifact" to "production API":

```kotlin
// NativeLibrary.kt
external fun nativeGetPlateData(plateIndex: Int): String?  // returns JSON or null if plate absent
external fun nativeGetPlateCount(): Int
```

C++ sketch (`sapil_bambu_plate.cpp`, new file, with helpers reused from `sapil_bambu_snapshot.cpp`):

```cpp
std::string plate_data_json(int plate_index) {
    for (const auto* p : g_plate_data_list) {
        if (p && p->plate_index == plate_index) {
            std::ostringstream out;
            append_plate(out, *p, project_colours, project_filament_ids, project_filament_settings_id);
            return out.str();
        }
    }
    return "";
}
```

This reuses the exact emitter Phase 0 already ships — no new JSON shape, no new parser. Promote `append_plate` from anonymous namespace in `sapil_bambu_snapshot.cpp` to a linkable helper in `sapil_bambu_snapshot.h`, or move it into a shared `sapil_bambu_common.cpp`.

### Kotlin populator change

`KotlinBambuSnapshot.kt` currently leaves `plateConfig` and `filamentSettingsIds` empty and fabricates `objectInstanceMap` from `ThreeMfPlate.objectIds` with instance id 0. After #2, `KotlinBambuSnapshot` either (a) converges with `NativeBambuSnapshot` by calling the same JNI, or (b) is retired entirely for these fields and `BambuParserDifferentialTest` records expected agreement. Fields populated:

- `PlateSnapshot.plateIndex` — from JSON `plateIndex` (closes 54).
- `PlateSnapshot.objectInstanceMap` — from JSON `objectInstanceMap` (closes 54, including the missing `instanceId`).
- `PlateSnapshot.filamentSettingsIds` — from JSON `filamentSettingsIds` (closes 54).
- `PlateSnapshot.plateConfig` — from JSON `plateConfig` (closes 2).

### Production code deletions

- `BambuSanitizer.extractPlate` (`BambuSanitizer.kt:1519-1644`) — the Kotlin re-zip-and-repair pass. Native now owns plate selection via `Model::read_from_file` + plate index. Replace callers in `SlicerViewModel` and `ProfileEmbedder` with a native "load-plate-N" path. **Cannot fully delete** until the profile embedder's ZIP-rewriting responsibilities move native too (this is really about splitting `extractPlate` into "extract for slice" vs "extract for preview").
- `ThreeMfParser.parseForPlateSelection` (`ThreeMfParser.kt:423-547`) — the "what does this plate look like without doing a full parse?" fast path. After #2, callers ask native directly.
- The per-plate `objectIds` loop in `ThreeMfParser.parse` (around `ThreeMfParser.kt:240-260`) — redundant with native `objects_and_instances`.
- `ThreeMfInfo.plates` downstream of parse can be populated from `nativeGetPlateData` rather than re-parsed from the ZIP.

### Tests

- **Update:** `MergeThreeMfInfoTest.kt` (49 unit tests) — tests currently exercise `mergeThreeMfInfoForPlate` against hand-built `ThreeMfInfo` fixtures. Many assertions still apply (the semantics of merge, not the parsing), but any test that constructs `ThreeMfPlate` objects to simulate "the parser produced X" becomes a test that the merge consumes the native-shaped data correctly.
- **Update:** `BambuPipelineIntegrationTest.kt` (34 instrumented) — B23/B82/B83/B86 plate-selection tests must keep passing on both the old-parser path (while flag off) and the native path. Until the flag flips permanently, dual-run critical cases.
- **Add:** `NativePlateDataTest.kt` (instrumented) — thin smoke over `nativeGetPlateData` on the diff corpus, asserting agreement with the Phase 0 snapshot for every fixture. If `BambuParserDifferentialTest` is still green post-migration, this is technically redundant — but having a targeted accessor test speeds up iteration.
- **Diff-harness regression:** the 165 entries listed above should drop from `known-disagreements.json` in a single commit. Any fixture where they *don't* drop is a bug in the new wiring.

### Risks

- **Phase 0 already found `plateIndex` mismatches (54 entries).** Native's `PlateData::plate_index` is 0-based after the BBS importer normalisation (per `sapil_bambu_snapshot.cpp:166` comment); Kotlin emits 1-based. We need to pick one convention at the JNI boundary and stick to it. Recommend: keep native as source of truth (0-based internally) and translate at the API edge if UI code wants 1-based.
- **Per-plate restructuring is on the slice-time critical path.** `BambuSanitizer.extractPlate` writes a fresh 3MF to disk so the native slicer can load just one plate. Replacing this with "native holds all plates in `g_model`, select one for slice" requires `sapil_print.cpp` to gain a "slice plate N" entry point. That's a native rebuild + possibly a behaviour change to `Print::apply`. Prototype early to de-risk.
- **B93 latency gain depends on not re-running `Model::read_from_file` per plate.** If the new accessor triggers a re-parse, we lose Buzz's cold-load optimisation. Make the accessor purely a JSON emitter over existing `g_model` state.
- **`plateConfig` has only 2 disagreements** because Kotlin returns empty and native returns empty for most fixtures — true overrides are rare. The 2 outliers (`plates[*].plateConfig[print_sequence]`) suggest a single-fixture case with explicit overrides. Don't over-engineer the `plateConfig` path until we know which fixtures actually use it.

---

## Sub-plan #3: Custom gcode per layer

**Closes baseline entries:** ~11 (plus whatever deeper disagreements on content drop out once emission converges)
**Production code touched:** `LayerToolCustomGcodeXml.kt` (the whole file: 78 lines), `LayerToolPauseInjector.kt` (one call site), `ThreeMfParser.kt` (`parseLayerToolCustomGcodeXml` and `parseLayerToolCustomGcodeXmlPerPlate` call sites around line 245-250 and 511), `KotlinBambuSnapshot.readCustomGcodeByPlate`
**Estimated effort:** S — small parser, few call sites, accessor mirrors Phase 0's existing emission.
**Dependencies:** #2 recommended first (so plate-scoped access is already established), but not strictly required.

### JNI accessor signature

Phase 0's snapshot already emits `plates[*].customGcode` from `g_model.plates_custom_gcodes`. Sub-plan #3 adds a plate-keyed accessor:

```kotlin
// NativeLibrary.kt
external fun nativeGetCustomGcodePerLayer(plateIndex: Int): String?  // JSON array of CustomGcodeEntry
```

C++ sketch: the relevant emission in `sapil_bambu_snapshot.cpp:170-191` already exists. Extract it into a standalone function keyed by `plate_index`, returning just the `[...]` array. Plate index keying matches Phase 0's confirmation that `Model::plates_custom_gcodes` uses 0-based plate index.

### Kotlin populator change

`KotlinBambuSnapshot.readCustomGcodeByPlate` currently re-reads `Metadata/custom_gcode_per_layer.xml` directly and runs regex over the attributes. Post-#3 it either:
- Delegates to `NativeBambuSnapshot` (both paths call the same JNI and agreement becomes tautological — `BambuParserDifferentialTest` records this as "both paths identical"), or
- Is retired for this field and the snapshot data class field is sourced only from native.

Fields populated:
- `PlateSnapshot.customGcode` — 11 disagreements close (these are currently entries where Kotlin's regex captures or the native enum-name mapping disagree, see `custom_gcode_type_name` note in `sapil_bambu_snapshot.cpp:88-107`). Decide type-name normalisation (canonical enum names like `"ToolChange"` vs raw XML type `"2"`) at the JNI boundary.

### Production code deletions

- `LayerToolCustomGcodeXml.kt` — delete the whole file (`parseLayerToolCustomGcodeXml`, `parseLayerToolCustomGcodeXmlPerPlate`, `parseLayerToolSegments`, `LayerToolCustomGcodeXmlInfo`, `LayerToolSegment`). 78 lines gone.
- In `ThreeMfParser.kt`, the call sites at lines 245, 248, 250-251, 511 — replace with a single native call per plate.
- `KotlinBambuSnapshot.readCustomGcodeByPlate` (the XML re-read) becomes obsolete.

**Cannot fully delete** `parseLayerToolSegments` until `LayerToolPauseInjector.kt:136` migrates — this is the real production consumer at slice time (it injects `M600` pauses based on layer-tool entries). The replacement: have `LayerToolPauseInjector` call `nativeGetCustomGcodePerLayer` and map `CustomGcodeEntry(printZ, extruder)` to `PauseTarget`. Straightforward once the accessor exists.

### Tests

- **Update:** `LayerToolCustomGcodeXmlTest.kt` (3 unit tests) — delete along with the parser, or rewrite to assert `NativeBambuSnapshot` parses the JSON correctly (but that's already covered by Phase 0).
- **Update:** `LayerToolPauseInjectorTest.kt` (9 unit tests) — these test `PAUSE_PRINT` injection logic, not XML parsing. Should survive the migration by feeding `PauseTarget`s directly instead of constructing from XML. Refactor test setup, keep assertions.
- **Add:** `NativeCustomGcodeAccessorTest.kt` (instrumented) — smoke test the new accessor on `Flarewing-Dragon` / layer-tool fixtures.
- **Diff-harness regression:** 11 entries drop.

### Risks

- **Type-name normalisation asymmetry.** Phase 0 deliberately left this as a known disagreement: Kotlin emits raw XML `type="1"`/`"2"` strings; native emits canonical enum names `"ColorChange"`/`"ToolChange"`. When we collapse to one path this has to resolve. The native names are the BambuStudio canonical form; pick those. Existing Kotlin consumers (`LayerToolPauseInjector`) should be fine because they only care about which entries are tool changes, not the literal string.
- **`LayerToolPauseInjector` runs at slice time, not load time.** By the time it runs, `g_model` is populated. Good: the native accessor works. Bad: if `g_model` gets clobbered between load and slice (it's a singleton per the strategy doc's risk #3), we get stale data. Add an assertion that the filename matches the expected file.
- **`parseLayerToolSegments` has a slightly different output shape** than `parseLayerToolCustomGcodeXml` (it returns `List<LayerToolSegment>` with `topZ` + `extruderBambu` rather than XML-wide flags). The native accessor covers the `LayerToolSegment` shape directly (printZ + extruder from `CustomGcodeEntry`); the `LayerToolCustomGcodeXmlInfo` roll-up of "has-tool-changes / colors / extruders across entire XML" is derived. Easy to derive post-JNI in Kotlin, no accessor needed.

---

## Sub-plan #4: Object extruder map

**Closes baseline entries:** ~22 (20 `objects.size` + 2 `objects[*]`)
**Production code touched:** `ThreeMfParser.kt` (object-extruder extraction, around lines 408 and 546 where `objectExtruderMap = extruderAssignments.toMap()` is built), `ThreeMfInfo.kt` (the `objectExtruderMap` field itself; possibly keep the type signature), `SlicerViewModel.mergeThreeMfInfo` (uses the map), `ProfileEmbedder.kt` (uses the map for embed), `BambuSanitizer.buildSyntheticModelConfig` (`BambuSanitizer.kt:1645`, consumes the map)
**Estimated effort:** M — small accessor, but the map is read from four call sites each with its own assumptions.
**Dependencies:** #1 recommended first (so mesh coloring doesn't reach into `objectExtruderMap`).

### JNI accessor signature

Phase 0 already emits `objects[*]` with `objectId`, `name`, `extruder`, `sourcePath`. For #4 we need plate-scoped access (an object's effective extruder can be overridden at the plate level):

```kotlin
// NativeLibrary.kt
external fun nativeGetObjectExtruderMap(): String?  // JSON array of {objectId, name, extruder}
```

C++ sketch: `sapil_bambu_snapshot.cpp:216-227` (`append_object`) already emits the shape. Promote to a top-level function `std::string object_extruder_map_json()` returning `[...]`. `extruder` already follows the BambuFileSnapshot contract (0 = unset/inherit, 1-based otherwise) per Phase 0's comment in `append_object`.

Question to resolve during planning: **is the map key the runtime `ObjectBase::id().id` (what Phase 0 emits) or the XML object id string (what Kotlin emits)?** Kotlin's `objectExtruderMap: Map<String, Int>` in `ThreeMfInfo.kt:60` is string-keyed using the XML id. Native's `g_model.objects[i]->id().id` is a process-local `size_t`. They **will not match** — this is one source of the `objects.size`/`objects[*]` disagreements. Decision needed before coding: either add an `input_file` / original-XML-id field to `ModelObject` (we'd need to see if BBS already stores it) or translate at the JNI boundary.

### Kotlin populator change

`KotlinBambuSnapshot` currently populates `objects` from `info.objectExtruderMap.entries` + `info.objects`. Post-#4 either both paths call the JNI, or Kotlin path is retired for this field.

Fields populated:
- `ObjectSnapshot.objectId` — native ObjectID vs XML id; normalise (closes 20 `objects.size` and 2 `objects[*]`).
- `ObjectSnapshot.extruder` — already matches per Phase 0's observation that 0=inherit works on both sides.

### Production code deletions

- The extruder-assignment extraction loops in `ThreeMfParser.kt:408` and `ThreeMfParser.kt:546` — the regex+stringify path that reads `<metadata key="extruder" value="N"/>` from `model_settings.config`. Deletable.
- `BambuSanitizer.buildSyntheticModelConfig` (`BambuSanitizer.kt:1645-...`) — this rewrites `model_settings.config` during plate extraction using the Kotlin-parsed `objectExtruderMap`. Once native owns plate extraction (depends on sub-plan #2), this can go. Until then: keep but source the map from native.
- `ThreeMfInfo.objectExtruderMap` field — keep the shape but populate from native.

**Cannot fully delete** the map itself — every downstream consumer (preview coloring, merge, embed) reads it. We migrate the *source* not the *shape*. Shape deletion would be a follow-up, but given the dataflow works fine with a populated map, probably not worth it.

### Tests

- **Update:** `ThreeMfParserTest.kt` (12) — any test that asserts parsed-map contents moves to asserting native-map contents. Smaller blast radius than #2.
- **Update:** `BambuSanitizerTest.kt` (25) and `ProfileEmbedderTest.kt` (5) — these construct `ThreeMfInfo`s with synthetic `objectExtruderMap`s for embedder testing. Should survive unchanged if we keep the map shape and just change where it's sourced.
- **Update:** `MergeThreeMfInfoTest.kt` (49) — similar.
- **Add:** `NativeObjectExtruderAccessorTest.kt` (instrumented).
- **Diff-harness regression:** 22 entries drop.

### Risks

- **ObjectID identity is the core risk.** If we can't map Slic3r's runtime ObjectID to the XML object id deterministically, every callsite that stores a `Map<String, Int>` keyed by XML id must migrate to a new key type. That's a wider refactor than 22 baseline entries suggest. Mitigation: verify during planning session whether `ModelObject::input_file` or an added `int xml_object_id` is already populated by `_BBS_3MF_Importer`.
- **Plate-level overrides.** Native actually carries plate-scoped filament_maps that can override per-object extruder. Our current accessor is plate-agnostic. If a fixture has `filament_maps = "2,1"` overriding object 1's extruder=1 to extruder=2 on plate 3, `nativeGetObjectExtruderMap()` alone won't show it. Either expose plate-scoped and require callers to pass plate context, or expose both and let callers compose. Recommend: plate-scoped (`nativeGetObjectExtruderMap(plateIndex: Int)`) once #2 has plate-scoped accessors in shape.
- **`buildSyntheticModelConfig` runs at slice time.** If we break it before native plate extraction lands (#2), we can't slice Bambu files. Keep it working until #2 ships.

---

## Sub-plan #5: Project config + filament colours

**Closes baseline entries:** ~46 (20 `fileVersion` + 26 `plates[*].filamentColours`)
**Production code touched:** `ThreeMfParser.kt` (the `filament_colour`/`extruder_colour` JSON extraction around lines 916-960, and the INI fallback around lines 997-1010), `KotlinBambuSnapshot.filamentColours` population (currently sources from `info.detectedColors`), downstream consumers of `ThreeMfInfo.detectedColors`.
**Estimated effort:** S-to-M — the parsing Kotlin replaces is well-scoped and native already has it in the first-class place (`g_model` project config via `getModelConfig()`, already referenced by Phase 0's `sapil_bambu_snapshot.cpp:322`).
**Dependencies:** None strictly. Recommended **first** among #2-#5 because the accessor pattern it establishes (reading `DynamicPrintConfig` from `g_model`) is what #2's plate-filament fallback already relies on.

### JNI accessor signature

```kotlin
// NativeLibrary.kt
external fun nativeGetProjectConfig(): String?
//   Returns JSON: { "isBbl": bool, "fileVersion": "x.y.z", "filamentColours": ["#RRGGBB", ...],
//                   "filamentSettingsIds": [...], "filamentIds": [...] }
```

C++ sketch: already half-implemented. `sapil_bambu_snapshot.cpp:322-326` already reads the three `ConfigOptionStrings` from `getModelConfig()` for the fallback path. `g_is_bbl` and `g_file_version` are already captured by `SlicerEngine::loadModel`. Factor into a new `std::string project_config_json()` in a small `sapil_bambu_project.cpp`.

### Kotlin populator change

`KotlinBambuSnapshot` currently returns `fileVersion = ""` unconditionally and sources `filamentColours` from `ThreeMfInfo.detectedColors` (a file-wide aggregate, not per-plate). Post-#5:

- `BambuFileSnapshot.fileVersion` — closes 20.
- `BambuFileSnapshot.isBbl` — no current disagreement, but convergence removes a divergent Kotlin code path.
- `PlateSnapshot.filamentColours` — when the plate has no `slice_filaments_info` (non-sliced 3MF), sourcing from project config matches native's fallback. Closes 26.

This depends on #2 to actually flow into `PlateSnapshot` (where filamentColours lives), or it stays partial. Acceptable: #5 closes the file-level disagreements (`fileVersion`, `isBbl`) and lays the accessor; #2 picks up the plate-level fallback.

### Production code deletions

- `ThreeMfParser` `filament_colour`/`extruder_colour` JSON extraction (~lines 916-960). The regex + JSON optJSONArray path is ~45 lines.
- `ThreeMfParser` INI fallback for `filament_colour = #hex;#hex` lines (~lines 997-1010). Another ~15 lines.
- `ThreeMfInfo.detectedColors` field can stay (consumers are many) but be populated from native.

**Cannot fully delete** `ThreeMfParser.parse` for version detection — `isBbl` detection is cheap and runs early. Native's `g_is_bbl` requires `Model::read_from_file` to have run. For the snapshot diff that's fine; for UI fast-path "is this a Bambu file?" detection, keep a lightweight Kotlin check or require the full native load. Prototype cost first.

### Tests

- **Update:** `ThreeMfParserTest.kt` (12) — any test that exercises colour extraction from a file. Retain behavioural assertions; change source of data.
- **Update:** `BambuSanitizerTest.kt` (25) — `filament_colour` rewriting during embed. Should be untouched if we keep the consumer shape.
- **Diff-harness regression:** 46 entries drop.

### Risks

- **`isBbl` cheap detection path.** The whole pipeline branches on "is this Bambu?" very early. If we force a full `Model::read_from_file` to answer that, cold-load latency on non-Bambu 3MFs (not currently in the corpus but common in the wild) gets worse. Mitigation: keep a 4-byte ZIP header check or `[Content_Types].xml`-inspect path in Kotlin for the fast-path, reserve `nativeGetProjectConfig()` for post-load inspection. This is low-risk because the current Kotlin check is already light.
- **Colour hex format.** Native emits `#RRGGBB` already (Phase 0 code ensures prefix). Kotlin may emit bare `RRGGBB` or lowercase. Diff harness would have caught this — check baseline entries before code.
- **`filamentSettingsIds` is actually #2's responsibility.** It's per-plate and already in the `PlateData` fallback. Sub-plan #5 only exposes the project-level array as the fallback source; per-plate population lands in #2.

---

## Summary table

| Sub-plan | Closes | Effort | Recommended order | Key risk |
|---|---:|:---:|:---:|---|
| #1 Painted facets → preview mesh | 420 | L | 1st (separate plan) | ObjectID identity; mesh re-upload path |
| #5 Project config + filament colours | 46 | S/M | 2nd | `isBbl` fast-path latency |
| #2 Per-plate PlateData | 165 | L | 3rd | plateIndex 0/1-based; slice-time restructure |
| #4 Object extruder map | 22 | M | 4th | ObjectID identity; plate-scoped overrides |
| #3 Custom gcode per layer | 11 | S | 5th | Type-name normalisation; slice-time reads |
| **Total (sub-plans #2-#5)** | **244** | — | — | — |

---

## Cross-cutting considerations

- **Native rebuild cadence.** #5 and #3 are small enough to piggyback on #2's native rebuild. Schedule: rebuild once for `append_plate` / `object_extruder_map_json` / `project_config_json` / `custom_gcode_json` extractions, then Kotlin-side migrations can land incrementally against that one rebuild.
- **Feature flag.** Strategy doc mentions `useNativeBambuLoader`. Suggest introducing it for #2 (highest-blast-radius sub-plan), not for #3/#4/#5 (each of those has small enough blast radius to land without a flag).
- **`known-disagreements.json` bookkeeping.** Every sub-plan lands with a commit that deletes the corresponding entries from the baseline. The diff harness stays green throughout. If a sub-plan reveals new disagreements, add them to the baseline with a recorded reason before flipping green, per the Phase 0 process.
- **JNI surface growth monitoring.** Per strategy doc risk #1, watch per-call overhead. None of #2-#5 is on a tight UI loop (all load-time or slice-time), so overhead should be negligible.
