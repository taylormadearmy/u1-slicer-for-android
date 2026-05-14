# F54 AI Paint — Data Inventory

Read-only research, 2026-05-14. Scope: what part/volume/object metadata is already available across every model format U1 Slicer supports, and what the codebase already extracts via JNI. Goal: replace AI visual grounding with the model's own semantic structure as the segmentation source.

---

## File formats

### 1. Bambu 3MF (richest source)

Parsed at two levels: **Kotlin** (`bambu/ThreeMfParser.kt`) and **native** (BBS importer in `app/src/main/cpp/orcaslicer/.../bbs_3mf.cpp` via `Model::read_from_file` in `sapil_model.cpp:121-167`). A Bambu 3MF is detected by the presence of any `Metadata/model_settings.config`, `slice_info.config`, `filament_sequence.json`, `project_settings.config`, or `custom_gcode_per_layer.xml` entry (`ThreeMfParser.kt:32-38`).

Per-format structures available:

- **3D/3dmodel.model** — root XML: `<object id>` with nested `<vertex>` / `<triangle>` (counts go into `ThreeMfObject`), `<component p:path>` references to sub-`.model` files (parsed by `parseComponentPaths`, 684), `<build>/<item>` with `objectid`, `printable`, 12-float affine `transform`.
- **3D/Objects/*.model** — per-object component meshes carrying the actual triangles plus any `paint_color="…"`, `mmu_segmentation="…"`, `paint_supports="…"` attributes (Bambu stores paint on triangles in the component files, not in the root; `ThreeMfParser.kt:150-177`).
- **Metadata/model_settings.config** — XML with `<plate>` → `<metadata key="plater_id"/"plater_name"/"filament_maps"/>`, `<model_instance>` → `<metadata key="object_id"/>`, `<object id>` → `<metadata key="name"/"extruder"/>`, and compound `<part id>` children each with their own `extruder` metadata (`parseModelSettingsConfig`, 1092). Yields: plate names, plate→object mapping, plate→filament index set, per-object names, per-object default extruder, per-part extruder, parent-of-part mapping, plus tracked extras (`enable_support`, `support_type`, `seam_position`, `layer_height` — B77, preserved by `BambuSanitizer`).
- **Metadata/project_settings.config** — JSON. `filament_colour[]`, `extruder_colour[]`, `filament_settings_id[]`, `filament_ids[]`, plus the full embedded slicer profile (only those keys in the C++ `profile_keys[]` whitelist take effect).
- **Metadata/filament_sequence.json** — flat list of `{color}` entries.
- **Metadata/custom_gcode_per_layer.xml** — per-layer tool/colour entries (Hueforge-style colour-by-layer). `parseLayerToolCustomGcodeXml` + `parseLayerToolCustomGcodeXmlPerPlate` (`bambu/LayerToolCustomGcodeXml.kt`).
- **Metadata/plate_N.json**, **plate_N.png** — multi-plate plate descriptors + thumbnails.
- **Slic3r_PE.config / Slic3r_PE_model.config** — PrusaSlicer-flavour fallback (semicolon-delimited INI plus per-object `extruder` metadata) consulted only when newer keys are missing.

The Kotlin side aggregates everything into `ThreeMfInfo` (`bambu/ThreeMfInfo.kt`) with `objects: List<ThreeMfObject>`, `plates: List<ThreeMfPlate>`, `objectExtruderMap`, `objectPartExtruders`, `compoundPartParents`, `usedExtruderIndices`, `volumeExtruders`, `detectedColors`, `hasPaintData`, `hasPaintSupports`, `hasLayerToolChanges`, `layerToolSegments`. After native load the same information is re-derived (and trusted) from the JNI accessors below; Kotlin XML parsing is deprecated for everything except embed-prep and the fallback OBJ-only handler.

### 2. "Snapmaker" 3MF

No distinct format. A Snapmaker 3MF is a Bambu-shaped 3MF whose embedded `project_settings.config` carries `printer_settings_id = "Snapmaker U1 (0.4 nozzle) - multiplate"` plus a `PRINT_START` machine_start_gcode marker (used by `sapil_print.cpp` `is_snapmaker_profile` detection). All structural rules from §1 apply identically. The U1 Slicer's `ProfileEmbedder` rewrites the profile portion before slicing but does not change the object / volume / paint structure.

### 3. STL (`viewer/StlParser.kt`)

Raw triangle soup only. Binary STL: 80-byte header, uint32 triangle count, 50-byte records (normal + 3 vertices + 2-byte attribute). ASCII STL: `facet normal … vertex …` text. The parser keeps positions, normals, an axis-aligned bounding box, **no extruder index, no group, no name, no material, no paint data** (`StlParser.kt:72,128` — `extruderIndices = null`). The native loader (`Model::read_from_file`, `sapil_model.cpp:135`) handles `.stl` and produces a single ModelObject with one ModelVolume. There is no per-part decomposition available from the file itself.

### 4. OBJ

No Kotlin OBJ parser exists. Loading is delegated entirely to the native side: `sapil_model.cpp:135` accepts `.obj` and routes it through `Slic3r::Model::read_from_file`. PrusaSlicer's OBJ reader honours `g`/`o` group/object headers and `usemtl`/`mtllib` material references; the resulting `Model` may contain multiple `ModelObject` and `ModelVolume` instances if the file uses groups, but no MTL colours, no extruder assignments, and no paint data persist. From the app's perspective OBJ surfaces with the same shape as STL (one or many volumes, no extruder metadata) and is treated identically downstream — the only structural cue is whatever the native reader put into `g_model.objects[*].volumes[*]`.

### 5. STEP

`.step` / `.stp` accepted at `sapil_model.cpp:135` and again delegated to `Model::read_from_file`, which uses OrcaSlicer's OpenCASCADE-based STEP reader. STEP is tessellated to triangles at load time; the original B-Rep / assembly hierarchy collapses to one ModelObject per solid (multi-solid STEP can therefore yield multiple ModelVolumes). No colour, material, or paint attributes — the app sees only volume counts + meshes, the same as OBJ.

---

## Native JNI accessors (read-only g_model walkers)

All require `NativeLibrary.previewMutex` held across `loadModel` + accessor sequences.

| Accessor (`NativeLibrary.kt`) | Returns | Populated when |
| --- | --- | --- |
| `loadModel(path)` / `loadModelForPlate(path, plateIdx)` | Boolean. Loads file into `g_model`; plateIdx≥0 filters to BBS `plate_id = plateIdx+1`. | Any supported file (.stl/.3mf/.obj/.step/.stp). |
| `getModelInfo()` → `ModelInfo` | filename, format, sizeX/Y/Z (mm), triangleCount, volumeCount, isManifold | After successful load. |
| `nativeGetObjectCount()` | Int — `g_model.objects.size()` | After load. 0 otherwise. |
| `nativeGetVolumeCount(objectIndex)` | Int — `objects[i]->volumes.size()` | After load. |
| `nativeGetObjectModelId(objectIndex)` | Long — Slic3r runtime `ObjectID` (not XML id) | After load. |
| `nativeGetVolumeScalars(oi, vi)` | IntArray `[extruder|-1, isMmPainted, isSeamPainted]` | Per volume after load. |
| `nativeGetPaintStateCounts(oi, vi, kind)` | IntArray `[state, count, …]`; kind 0 = `mmu_segmentation_facets`, kind 1 = `supported_facets` | Per volume; counts states 1..16 (`EnforcerBlockerType::ExtruderMax`). |
| `nativeGetAllVolumeExtruders()` | JSON: `[{objectIndex,objectExtruder,volumes:[{volumeIndex,extruder,isMmPainted,isSeamPainted}]}]` (`sapil_bambu_volume_map.cpp`) | After load. One-shot for all per-volume extruder + paint flags. |
| `nativeGetObjectExtruderMap()` | JSON: `[{objectId, name, extruder, sourcePath}]` (`sapil_bambu_objects.cpp` via `append_object`) | After load. |
| `nativeGetPlateCount()` | Int — `g_plate_data_list.size()` | After load of Bambu 3MF (0 for STL/OBJ/STEP). |
| `nativeGetPlateData(plateIndex)` | JSON: `{plateIndex, filamentColours[], filamentSettingsIds[], objectInstanceMap[{objectId,instanceId}], customGcode[{printZ,type,extruder,color}], plateConfig{k:v}}` (`sapil_bambu_plate.cpp` → `append_plate`) | After load of Bambu 3MF. |
| `nativeGetProjectConfig()` | JSON: `{isBbl, fileVersion, filamentColours[], filamentSettingsIds[], filamentIds[]}` | After load of Bambu 3MF. |
| `nativeDumpBambuModel(path)` | Full Phase 0 snapshot JSON: plates, objects, volumes — including `paintStateSet`, `paintSupportsStateSet` per volume (`sapil_bambu_snapshot.cpp:append_volume`). Re-loads from disk for clean state. | Any path; informational/diagnostics. |
| `setModelScale / setModelRotation / setModelInstances / getInstanceOffsets / cancelPreviewMesh / cancelSlice / getPreparePreviewMesh / slice / getGcodePreview / loadProfile / getCoreVersion / configureDiagnostics / getDiagnosticsState` | Not part-metadata accessors — listed for completeness. | — |

Key native takeaway: **`ModelVolume` is the atomic per-part unit**. Each one carries its own `config["extruder"]`, `mmu_segmentation_facets` (per-triangle paint state 0..16), `supported_facets` (enforcer/blocker), and triangle mesh — exactly the unit AI Paint should treat as a "segment".

---

## Fixture examples

Counts from `app/src/androidTest/assets/` and `fixture-specs/`. "Objects/volumes" are post-load `g_model` counts; "plates" are `g_plate_data_list.size()` (0 for non-Bambu).

| File | Format | Size | Objects | Volumes/obj | Plates | Paint | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `3DBenchy.stl` | STL bin | 10.8 MB | 1 | 1 | 0 | none | No semantic structure — single-shell. Pure topology candidate. |
| `colored_3DBenchy (1).3mf` | Bambu 3MF | 5.0 MB | Several merged component-ref objects (see `NativeObjectExtruderMapTest`) | Multiple per object | 1 | **yes** (MMU paint, 4 states; H2C variant `3DBenchy-H2C-Multi-Color.3mf` 4.7 MB has 7+ states folded to 4) | Object-level `extruder` per part; paint also on triangles. |
| `Dragon Scale infinity.3mf` | Bambu 3MF, multi-plate | 3.4 MB | Compound object with multiple `<part>` children (one part per extruder per plate, e.g. plate 3 = 1 object / 3 parts) | 3+ | Multi (old virtual-plate format, no `plate_N.json`) | none on plate 3 | Per-part extruders only — `objectExtruderMap` collapses to one; `objectPartExtruders` / `nativeGetAllVolumeExtruders` are required. |
| `Shashibo-h2s-textured.3mf` | Bambu 3MF, multi-plate | (large) | Many | Mixed | 5+ (target plate 5) | none on plate 5 | 2-extruder via per-object assignment; large; cold-load stress fixture. |
| `Buzz_Multipart_3MF_Bambu.3mf` | Bambu 3MF, multi-plate | 73.3 MB | 80+ component `.model` entries, ~296K `paint_color` attributes (see `ThreeMfParser` perf notes around line 226) | Many | 8+ | mixed — plate 8 is painted, others vary | Both `model_settings.config` per-object/part assignments AND triangle paint states. |
| `slip slide spin fidget.3mf` (plate 3) | Bambu 3MF | — | 1 object | 1 volume | Multi | **yes**, 4 paint regions | SEMM-painted single object; object-level extruder collapses to 1 while paint decode reveals full palette — drives `paintExtruderStates` field. |

---

## Recommendation — segmentation priority order

When both Bambu objects/volumes/paint AND pure topology are available, drive AI Paint segmentation in this priority order:

1. **Per-triangle paint annotations** (`mmu_segmentation_facets`, `supported_facets`) — `nativeGetPaintStateCounts` / `nativeGetAllVolumeExtruders`. This is already a per-triangle segmentation map authored by the designer; no AI needed. Use directly as base layer.
2. **`ModelVolume`** — every volume is an authored, semantically meaningful sub-part with its own mesh + extruder. `nativeGetAllVolumeExtruders` returns the full per-volume table in one call. For Bambu compound objects (Dragon Scale, painted fidgets), volumes are exactly the "parts" the user expects.
3. **`ModelObject`** — `nativeGetObjectCount` / `nativeGetObjectExtruderMap`. Coarser than volumes but always present after load; use as the fallback partition when a file has only one volume per object.
4. **Topology-based segmentation** (mesh-graph / shell separation / curvature clustering done in-app) — only when none of 1–3 yields more than one segment. This is the STL / single-volume OBJ / single-solid STEP case. The cat-pot-style single-shell STL has nothing else to fall back on.

Why this order: layers 1–3 carry the **designer's intent** — there is no benefit to having a model reinvent structure that the file already labels. Topology should be a last resort because two adjacent shells fused by boolean ops cannot be reliably separated from geometry alone, while paint/volume metadata makes that distinction explicit. The native accessors already exist and are exercised by 17+ instrumented tests; no new C++ work is needed to consume them.

---

## Open questions / unknowns

- **OBJ groups (`g`/`o`) and `usemtl`** — confirmed routed through native, but it's unverified whether PrusaSlicer's OBJ reader emits one `ModelVolume` per group or merges everything. Worth a fixture probe (no group-style OBJ exists under `androidTest/assets/`).
- **STEP multi-solid behaviour** — no STEP fixture in the test corpus. Need a multi-solid STEP to confirm whether the native loader emits one ModelObject per solid or one ModelVolume per solid.
- **Snapmaker-authored 3MF** — we never write a true Snapmaker-native 3MF (only embedded profile into Bambu-shape). No format-specific schema work is required for AI Paint.
- **`ModelVolume::source.*`** — `ModelObject::input_file` is captured by `append_object` as `sourcePath`, but per-volume provenance (e.g. which `.obj`/`.stl` a volume came from in a multi-part Bambu) is not surfaced through current JNI. May matter for AI Paint's "this volume came from file X" UX.
- **AMF** — declared unsupported by `sapil_model.cpp:135` (only `stl/3mf/obj/step/stp`). If a user opens an AMF (which can carry per-region extruder + materials), the app rejects it at load. Confirm this is intentional before designing UX.
- **OBJ MTL palette** — even if PrusaSlicer reads `usemtl`, it discards material colour. AI Paint may want a recovery path that reads the `.mtl` separately for a colour hint.

