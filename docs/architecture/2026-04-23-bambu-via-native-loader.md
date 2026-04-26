# Bambu File Handling: Refactor to Native-Loader Source-of-Truth

**Date:** 2026-04-23
**Branch:** `refactor/bambu-via-native-loader`
**Status:** Proposed — awaiting plan approval before execution
**Authors:** Kevin + Claude (synthesised from 4 parallel research agents)

---

## TL;DR

We keep getting bitten by Bambu `.3mf` edge cases (B23, B47, B49, B51, B52, B54, B55, B56, B57, B59, B60, B62, B63, B64, B71, B72, B73, B75, B76, B77, B78, B79, B82, B83, B86, B88, B89, B92, B93, B94, B95). The pattern is structural: we parse Bambu files in **both** Kotlin and the native C++ engine, with no validation between them, and the bugs live in the gap.

**Surprise finding:** the C++ engine already calls `Model::read_from_file` (see `app/src/main/cpp/src/sapil_model.cpp:135`), which means the upstream Bambu loader has already populated `g_model` with `mmu_segmentation_facets`, `plates_custom_gcodes`, per-object extruders, `PlateData`, `paint_supports`, etc. We're not missing engine work — we're just not reading it back. Most of our Kotlin parsing is redundant.

**Direction:**
1. **Phase 0 — Differential test harness** (~1-2 weeks). For every Bambu file in our corpus, snapshot key facts via both Kotlin and C++ paths and assert they agree. This catches existing drift today and is the regression net for everything that follows.
2. **Phase 1 — Shrink Kotlin, expose C++** (~3-5 weeks, incremental). Add small JNI accessors that read fields already living in `g_model`. Delete the Kotlin parsers one subsystem at a time, gated by the diff harness.

We are NOT switching engines (BambuStudio fork would lose Snapmaker U1 patches), shelling out to a CLI (Android subprocess pain), or building a normalize-first IR (the in-memory `Model` already is one).

---

## Problem

The recurring B-series Bambu bugs are not unrelated incidents — they are symptoms of one architectural property: **two parsers, no contract between them.**

### The five recurring root-cause patterns

(From the diagnostic agent's review of recent commits and current source.)

1. **Duplication of paint-state decoding.** `ThreeMfParser` streams `paint_color` attributes from component `.model` files in Kotlin and decodes the first-character hex to count states. The native engine independently re-decodes during `multi_material_segmentation_by_painting()`. When they disagree on AMS2 folding or high-index states, slots silently drop. **B95 is exactly this** — Kotlin detects states `[0, 8]`, embedder sizes `filament_colour` to 2 slots, native finds state 8 outside the range and emits no `T8`.

2. **Mismatched extruder-count inference.** Five different code paths each compute "how many extruders are active": `ThreeMfParser` (synthetic range), `ProfileEmbedder.computeEmbedTargetCount` (distinct slots), `mergeThreeMfInfoForPlate` (plate-specific heuristics), `GcodeNormalizer` (tool-change scanning), `DataStore` (preset slots). When they disagree, the preview palette doesn't match the embedded `filament_colour`, which doesn't match native slicer output. (B44, B48, B76, B90, B93, B95.)

3. **Plate extraction loses metadata.** `selectPlate()` calls `restructurePlateFile`, which extracts a single plate into a new 3MF. The extracted plate may have lost per-plate metadata (per-plate layer-tool, `plate_N.json`, filament_maps). `parseForPlateSelection` then reverse-engineers "what is this plate's palette?" from incomplete sources. (B82, B83, B86, B93, B94.)

4. **Colour mapping applied at three stages with different semantics.** Prepare palette (UI display) → embed `filament_colour` array → post-slice remap (per-format, e.g. `composeSemmRemap` for H2C, `semmColorPermutation` for SEMM). Each path has its own assumptions. (B64, B86, B92.)

5. **File-format assumptions drift from Bambu Studio output.** Code assumes `paint_supports` is encoded inside `paint_color`, that per-object extruder assignments are always written, that paint states are compact `1..4`. Bambu Studio writes files that violate each of these. (B57, B77, B90, B95.)

### The architectural smell

The Kotlin parser tries to detect every Bambu variation and feed a `ThreeMfInfo` struct to the embedder, which then infers what to embed. The native slicer independently parses the 3MF it receives and may make contradictory decisions. **There is no validation between these layers.** Mismatches are silent. Every fix has been a surgical addition to a specific code path, which increases the surface area for the next regression.

---

## Mental model: what Bambu's format actually means

(From the Bambu Studio source-research agent.)

A few non-obvious facts that explain why our naive parsing keeps failing:

- **`paint_color` is misnamed.** It is *not* an RGB or even an extruder index. It is a bit-packed BSP subdivision tree whose leaves are `EnforcerBlockerType` values (1..32), serialized via `TriangleSelector::serialize`. State 0 means *unpainted, inherit*, not "extruder 0".
- **Up to 32 paint states are encodable, but physical extruders are 4.** H2C/Hueforge files ship with 5-8 distinct states intentionally, expecting the target slicer to fold them. **There is no authoritative "this is H2C" flag in the file** — it must be derived (`max_paint_state > filament_count`).
- **Extruder assignment is hierarchical with fallthrough.** Plate `filament_maps` → object-level `extruder` (in `model_settings.config`) → volume-level `extruder` (override) → triangle-level paint state. Lower levels override higher levels only where explicitly present. Skipping a tier produces B47/B82/B86/B92-class bugs.
- **Plates are the load unit, not files.** A multi-plate `.3mf` is N print jobs with different `filament_maps`, different active-object subsets, different `custom_gcode_per_layer` lists. Indexing by global object_id alone is wrong.
- **The writer is lenient; the loader is strict-then-repair.** Bambu's `_BBS_3MF_Importer` runs a sanitise pass on every load (clamps invalid extruder IDs, drops empty volume-level extruder when single-volume, reconciles slice_info object lists against model_settings). Our Kotlin sanitiser partially mirrors this; the native engine does it fully.

The implication: **if we want a parser that handles every Bambu file Bambu can write, the only one that exists is Bambu's own loader.** OrcaSlicer ships a fork of it as `_BBS_3MF_Importer` in `bbs_3mf.cpp`, and we already link it.

---

## The surprise: we already parse it twice

(From the OrcaSlicer source-research agent.)

`app/src/main/cpp/src/sapil_model.cpp:135` calls `Model::read_from_file`. That function transitively invokes `_BBS_3MF_Importer::load_model_from_file`, which:

- Parses every entry in the zip (via miniz + Expat SAX).
- Decodes per-triangle paint into `ModelVolume::mmu_segmentation_facets` (a `TriangleSelector::TriangleSplittingData`).
- Reads `Metadata/model_settings.config` per-object/per-volume into `ModelObject::config` and `ModelVolume::config`.
- Reads `Metadata/project_settings.config` into a `DynamicPrintConfig`.
- Reads `Metadata/slice_info.config` per plate into `PlateData{plate_index, slice_filaments_info, config, gcode_file, thumbnails, ...}`.
- Reads `Metadata/custom_gcode_per_layer.xml` into `Model::plates_custom_gcodes` (map<plate_index, `CustomGCode::Info`>).
- Runs the strict-then-repair sanitise pass (clamps extruder IDs, drops volume-level extruder for single-volume objects, splits multi-instance objects for non-BBS 3MF).

**All of this is already in memory in the `g_model` global after load.** We're then re-parsing the same bytes in Kotlin to derive worse approximations of the same facts.

The reusable C++ APIs (already linked in the `.so`, no upstream port required):

| API | What it gives us |
|---|---|
| `Model::read_from_file` (already called) | Full parse populates `g_model`. |
| `Model::plates_custom_gcodes` | Per-plate layer-tool events, parsed and validated. |
| `ModelVolume::mmu_segmentation_facets` | Per-triangle painted extruder, decoded. |
| `FacetsAnnotation::get_facets(mv, EnforcerBlockerType)` | Returns per-extruder mesh slice as `indexed_triangle_set`. |
| `ModelVolume::is_mm_painted()` / `is_seam_painted()` / `is_fuzzy_skin_painted()` | Quick flags. |
| `PlateData::slice_filaments_info` | Per-plate filament colours, types, usage. |
| `PlateData::config` | Per-plate config overrides. |
| `bbs_3mf_get_thumbnail(path)` | Single-call thumbnail extraction. |

Most of `bambu/ThreeMfParser`, `bambu/BambuSanitizer` (the parts that mirror Bambu's repair pass), `bambu/LayerToolCustomGcodeXml`, `viewer/ThreeMfMeshParser`, and most of `MergeThreeMfInfo` would become deletable once we expose these via JNI.

---

## Alternatives considered

(Full menu in the brainstorming agent's report.)

| # | Option | Verdict |
|---|---|---|
| 1 | **Differential test harness** | **Adopting (Phase 0).** Cheap, addresses the exact bug class, prerequisite for everything else. |
| 2 | Normalize-first canonical IR | Skipped. The in-memory `Model` already *is* the canonical IR — building a Kotlin copy adds ceremony without addressing root cause. |
| 3 | **Single source of truth — Kotlin shrinks** | **Adopting (Phase 1).** The brainstorm agent estimated 6-8 weeks of "significant C++ work"; the Orca-research agent showed the C++ already does the work, so real cost is ~3-5 weeks of small JNI accessors + Kotlin deletion. |
| 4 | Round-trip via plain 3MF + sidecar | Skipped. Same shape as #2 with extra I/O; sidecar schema becomes another thing to drift. |
| 5 | Property-based / fuzz testing | Deferred. Most value already captured by #1 against real fixtures; revisit later if synthetic variants would catch bugs the corpus doesn't. |
| 6 | Shell out to OrcaSlicer CLI | Rejected. Android subprocess + SELinux pain; we'd still maintain a forked CLI for U1 patches. |
| 7 | Switch engine to BambuStudio fork | Rejected. Would re-port every Snapmaker U1 patch onto a different upstream; trades a Kotlin-side bug class for printer-side bugs. |

---

## Chosen direction

### Phase 0 — Differential test harness (this plan)

**Goal:** for every `.3mf` in our test corpus, produce a snapshot of key facts via the **Kotlin parsing path** and the **C++ loader path**, and assert they agree.

**Snapshot fields (initial):**

- File-level: `is_bbl`, file version, plate count.
- Per plate: object-instance map, `filament_colour[]`, `filament_settings_id[]`, plate config overrides, custom-gcode-per-layer entries `(z, type, extruder, color)`.
- Per object: 1-based extruder, name, source path.
- Per volume: 1-based extruder (where set), `mmu_segmentation` paint-state set (which of `1..32` appear and triangle counts), `paint_supports` state set, `is_mm_painted` / `is_seam_painted` flags.

**Mechanism:**

- New JNI: `nativeDumpBambuModel(modelPath: String): String?` — after `Model::read_from_file`, walk `g_model` and emit JSON.
- New Kotlin: `snapshotFromKotlinParsers(file): BambuFileSnapshot` — compose existing parsers into one snapshot.
- New diff: `BambuSnapshotDiff.diff(kotlin, native): List<Disagreement>` — per-field comparison with paths.
- New instrumented test: `BambuParserDifferentialTest` — enumerates fixture corpus, runs both, asserts agreement against a known-disagreements baseline.

**Deliverable:** a green test on every fixture except those documented in `known-disagreements.json` with a recorded reason. The baseline becomes the to-do list for Phase 1.

### Phase 1 — JNI accessors + Kotlin deletion (separate plans, one per subsystem)

Order by leverage (highest first):

1. **Painted facets → preview mesh.** Add `nativeGetPaintedFacets(objectIdx, volumeIdx, slot)` (uses `FacetsAnnotation::get_facets`). Replace `ThreeMfMeshParser` paint extraction. Fixes B47/B82/B86/B88/B92 class.
2. **Per-plate `PlateData`.** Add `nativeGetPlateData(plateIdx)`. Replace `BambuSanitizer.extractPlate` re-parse + `parseForPlateSelection`. Fixes B82/B83/B93.
3. **Custom gcode per layer.** Add `nativeGetCustomGcodePerLayer(plateIdx)`. Delete `LayerToolCustomGcodeXml` parser.
4. **Object extruder map.** Add `nativeGetObjectExtruderMap()`. Replace merge heuristics in `MergeThreeMfInfo`.
5. **Project config + filament colours.** Add `nativeGetProjectConfig()`. Replace `BambuSanitizer`'s embed-side logic.

Each step is independently shippable behind a feature flag (`useNativeBambuLoader`), gated by the diff harness staying green. Each gets its own plan written when we get to it (so we can use what Phase 0 reveals).

**Phase 1 sub-plan #1 scope update (2026-04-23, post-Phase-0 pre-flight research):** the production hot-path preview (`InlineModelPreview` in `MainActivity.kt`) already went fully native in the B46 fix. The only remaining production caller of `ThreeMfMeshParser.parse` is `ModelViewerScreen.kt:42`, which doesn't use the paint data — it renders with the default grey. So sub-plan #1 is fundamentally a **diff-harness closure job**, not a production refactor. Start with a **counts-only** JNI accessor (Option C in `docs/superpowers/plans/2026-04-23-phase1-painted-facets-design-notes.md`) — ~15 lines of C++ lifted from `sapil_bambu_snapshot.cpp`'s `count_paint_states` — which closes the ~420 `volumes[N]` baseline entries without touching any render path. Retiring `ThreeMfMeshParser` entirely + migrating `ModelViewerScreen` to the native preview path is a later cleanup, possibly bundled with sub-plan #2 or done as its own commit.

---

## Success metrics

- **Phase 0 done when:** the diff harness is green on every fixture in `app/src/androidTest/assets/` (or every disagreement is recorded with a documented reason).
- **Phase 1 step done when:** the corresponding Kotlin parser is deleted, the diff harness is still green, all existing instrumented + unit tests still pass, and the next 1-month run produces zero B-series bugs in that subsystem.
- **Whole programme done when:** a new Bambu file shape that breaks an existing user can be diagnosed by running the diff harness on it (which produces a precise mismatch report instead of a silent slice failure), AND the fix is in the C++ loader (or, rarely, in the JNI accessor) — never in a new ad-hoc Kotlin parser.

---

## Risks and open questions

1. **JNI surface growth.** Every Kotlin question we move to native adds a JNI call. Risk: surface area grows faster than estimated, or per-call overhead matters for tight UI loops. *Mitigation:* batch reads in single calls (e.g. `nativeGetPlateData` returns one JSON for the whole plate, not per-field), measure preview latency before committing to deletion.
2. **Native rebuild cadence.** Phase 1 requires rebuilding the `.so` for each new accessor. Mitigation: batch accessors per native rebuild — add 2-3 at a time.
3. **`Model` is process-global.** `g_model` is a singleton; concurrent file loads would clobber it. *Mitigation:* this is already a constraint; document it explicitly and add an assertion. Long-term, consider a lookup-by-handle if it matters.
4. **JVM unit-test coverage shrinks.** Code that moves to C++ can no longer be tested in JVM unit tests, only instrumented tests (which are slower and need an emulator/device). *Mitigation:* the diff harness IS the new test surface; we trade granular JVM tests for end-to-end agreement tests on real files.
5. **What if the C++ parser is wrong for a given file?** It's not infallible. If the diff harness shows a disagreement and we can prove the C++ side is wrong, the fix lives in the C++ loader (upstream Orca, or our patch on top). *Mitigation:* the diff harness makes this case visible instead of silent.
6. **Phase 0 may itself reveal that some Kotlin parsers are MORE correct than C++.** That's a useful finding — it tells us where to invest. Plan accordingly: don't pre-commit to "delete all Kotlin parsers"; commit to "delete every Kotlin parser whose answers agree with C++, and investigate the rest."

---

## References

- Strategy synthesis: this document.
- Diagnostic of recurring patterns: agent A report (in conversation transcript).
- OrcaSlicer loader source: agent B report. Key files: [`src/libslic3r/Format/bbs_3mf.cpp`](https://github.com/SoftFever/OrcaSlicer/blob/main/src/libslic3r/Format/bbs_3mf.cpp), [`src/libslic3r/Model.hpp`](https://github.com/SoftFever/OrcaSlicer/blob/main/src/libslic3r/Model.hpp).
- Bambu file-format mental model: agent C report. Key files: [`BambuStudio bbs_3mf.cpp`](https://github.com/bambulab/BambuStudio/blob/master/src/libslic3r/Format/bbs_3mf.cpp), [`Model.hpp`](https://github.com/bambulab/BambuStudio/blob/master/src/libslic3r/Model.hpp), [`TriangleSelector.cpp`](https://github.com/bambulab/BambuStudio/blob/master/src/libslic3r/TriangleSelector.cpp).
- Alternatives menu: agent D report.
- Local entry point for native loader: [`app/src/main/cpp/src/sapil_model.cpp:135`](app/src/main/cpp/src/sapil_model.cpp#L135).
- Phase 0 implementation plan: [`docs/superpowers/plans/2026-04-23-bambu-diff-test-harness.md`](../superpowers/plans/2026-04-23-bambu-diff-test-harness.md).
