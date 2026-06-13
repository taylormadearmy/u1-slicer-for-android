# Recon: extending mixed-filament SAME_LAYER_DOTS split to top solid infill

Date: 2026-06-11
Source examined (read-only): `D:\projects\u1-slicer-for-android\.claude\worktrees\engine-base-rebuild\app\src\main\cpp\orcaslicer\src\libslic3r\` — submodule @ `9d6c160a` ("fix(M3-B): suppress H2C paint-state fold when virtual mix filaments are active"). The `colormix-own` worktree's submodule is the identical, clean `9d6c160a` checkout, so everything below applies verbatim to the change target.

All paths below are relative to `src/libslic3r/` unless prefixed.

---

## Headline finding (read this first)

**In base 9d6c160a, SAME_LAYER_DOTS is fully dormant — at four independent switch points.** The on-device evidence (dots-mix splitting body infill into by-tool islands) cannot have been produced by a clean build of this tree; it came from the shipped `.so` (built from the orphaned engine commit) or a locally patched build. The switch points:

1. **Parse-time coercion** — `distribution_mode == 1` never survives config parsing. `MixedFilament.cpp:855-863`:
   ```cpp
   static int normalize_distribution_mode_without_pointillism(int distribution_mode, const std::string &gradient_component_ids)
   {
       const int clamped_mode = clamp_int(distribution_mode, int(MixedFilament::LayerCycle), int(MixedFilament::Simple));
       if (clamped_mode != int(MixedFilament::SameLayerPointillisme))
           return clamped_mode;
       const size_t gradient_count = MixedFilamentManager::decode_gradient_component_ids(gradient_component_ids, 0).size();
       return gradient_count >= 3 ? int(MixedFilament::LayerCycle) : int(MixedFilament::Simple);
   }
   ```
   Applied in `parse_mixed_filament_entry` at `MixedFilament.cpp:614`, and via `disable_pointillism_mode(mf)` (`MixedFilament.cpp:865-869`) at the row-load/rebuild sites `MixedFilament.cpp:1988, 2018, 2183, 2219`. So a `mixed_filament_definitions` row carrying `m1` (dots) becomes LayerCycle (≥3 gradient components) or Simple before any pipeline code can see it. **Any dots work must first remove this coercion**, otherwise `mixed_row->distribution_mode == SameLayerPointillisme` is unreachable everywhere.
2. **GCode-time sequence builder is `#if 0`** — `pointillism_sequence_for_row_for_gcode`, `GCode.cpp:4019-4086` (`#if 0` at 4021, hard `return {};` at 4085). The split plumbing that consumes it (below) is compiled and live, but always receives an empty sequence and never fires.
3. **Slice-time stripe segmentation is `#if 0` and orphaned** — `pointillism_sequence_for_row` (`PrintObjectSlice.cpp:1790-1859`, `#if 0` at 1792) and `apply_pointillism_mixed_segmentation` (`PrintObjectSlice.cpp:2308-2514`, `#if 0` at 2310). Additionally `apply_pointillism_mixed_segmentation` **has no caller anywhere** (only its definition at 2308 exists; `apply_mm_segmentation` at `PrintObjectSlice.cpp:5261` is called instead).
4. **PrintApply gate is `#if 0`** — `same_layer_pointillism_enabled` (`PrintApply.cpp:1215-1224`) carries the comment `// Deprecated: same-layer pointillism is disabled and will be removed.` and always returns false, so the painted-channel expansion for same-layer mode (`PrintApply.cpp:1957+`, `expanded_all_channels_for_same_layer`) never triggers.

The plan's framing is consistent with this: it already says not to rely on the `#if 0` sequence builder. The practical consequence is that the work is not just "extend the split to top solid" — it is "supply a live dots path (new sequence source + un-coerce mode 1) and make it cover top solid infill", reusing the live, proven routing plumbing described below.

---

## Q1 — How the dots split + island routing works in GCode.cpp (the plumbing that exists today)

All of the following is **compiled and live** except the sequence source. Flow inside `GCode::process_layer`:

**1. Per-collection configured filament (raw, can be a virtual mix id)** — `configured_filament_id_1based`, `GCode.cpp:4998-5027`:
```cpp
if (entity_type == GCode::ObjectByExtruder::Island::Region::INFILL) {
    if (layer_tools.extruder_override != 0)
        return layer_tools.extruder_override;
    const ExtrusionRole role = entities.entities.empty() ? erNone : entities.entities.front()->role();
    if (role == erSolidInfill && std::abs(region.config().sparse_infill_density.value - 100.) < EPSILON)
        return raw_sparse_infill_filament_id_1based();
    if (is_solid_infill(role))
        return unsigned(region.config().solid_infill_filament.value);   // ← erTopSolidInfill lands here
    return raw_sparse_infill_filament_id_1based();
}
return ... region.config().wall_filament.value ...;
```
Note this returns the **raw region config value** — no `resolve_mixed`, no clamping — so a virtual mix id survives to this point, including for `erTopSolidInfill`.

**2. Sequence lookup (cached per filament id)** — `pointillism_sequence_for_filament`, `GCode.cpp:5043-5061`. Calls `layer_tools.mixed_mgr->is_mixed(...)` then `pointillism_sequence_for_row_for_gcode(*mixed_row, num_physical)` (the `#if 0` function — always `{}` today), and requires ≥2 unique physical extruders (`unique_extruder_count_for_gcode`, `GCode.cpp:4002-4017`).

**3. The split trigger inside the entity loop** — `GCode.cpp:5580-5621`. Crucially this sits inside the loop over **both** entity types (`{INFILL, PERIMETERS}`, `GCode.cpp:5474-5477`) and has **no role gate**:
```cpp
const unsigned int configured_filament_id = configured_filament_id_1based(entity_type, *filtered_extrusions, region);
const std::vector<unsigned int>* pointillism_sequence =
    is_anything_overridden ? nullptr : pointillism_sequence_for_filament(configured_filament_id);   // 5581-5582
if (pointillism_sequence != nullptr) {
    ...
    const size_t sequence_phase = ... layer_tools.layer_index % sequence size ...;                  // 5586-5587
    if (split_extrusion_collection_for_pointillism_paths(*filtered_extrusions, *pointillism_sequence,
            layer_tools.num_physical, pointillism_segment_len_scaled, pointillism_line_gap_scaled,
            sequence_phase, split_by_extruder, split_stats) && split_stats.bucket_count >= 2) {     // 5588-5596
        for (size_t extruder_idx = 0; extruder_idx < split_by_extruder.size(); ++extruder_idx) {
            ...
            std::vector<ObjectByExtruder::Island>& islands =
                object_islands_by_extruder(by_extruder, unsigned(extruder_idx), layer_to_print_idx, layers.size(), n_slices + 1);  // 5605-5606
            ... islands[island_idx].by_region[region.print_region_id()].append(entity_type, split_ptr, nullptr); // 5613
        }
        continue;                                                                                   // 5618 — skips normal single-tool routing
    }
    ++pointillism_path_split_fallbacks;                                                             // 5620 — falls through to single tool
}
```
Note `is_anything_overridden` (wipe-into-object/infill) disables the dots split wholesale (5582).

**4. The splitter itself (ACTIVE helper)** — `split_extrusion_collection_for_pointillism_paths`, `GCode.cpp:4159-4236`:
- flattens the source collection (`source.flatten(false)`, 4203), walks every `ExtrusionPath`/`ExtrusionMultiPath`/`ExtrusionLoop`,
- chops each polyline into fixed-length pieces via `split_polyline_by_length_for_pointillism` (`GCode.cpp:4088-4120`),
- trims each piece's ends for a visual gap via `trim_polyline_for_pointillism_gap` (`GCode.cpp:4122-4146`),
- assigns pieces round-robin through `sequence[sequence_idx % sequence.size()]` (4214), advancing the index even for dropped pieces (4211) so the pattern stays spatially coherent,
- buckets pieces into `out_by_extruder[extruder_id - 1]` — **0-based physical extruder buckets** (4192),
- tags each output path `inset_idx = k_pointillism_path_inset_marker` (-7777, `GCode.cpp:4157, 4198`), consumed at `GCode.cpp:7312-7320` to skip accel/jerk switching on very short pieces.

**5. Per-component island creation** — `object_islands_by_extruder` (`GCode.cpp:4286-4297`) lazily creates the `ObjectByExtruder::Island` vector for a (0-based) extruder key in the `by_extruder` map (`GCode.cpp:4982`), and the split collection is appended into `islands[island].by_region[region_id]` with the original entity type (5613). From there it is emitted by the standard machinery.

**6. How the wipe tower stays in sync** — three-link chain:
- ToolOrdering registers all tools a layer needs in `LayerTools::extruders` (see Q4); after `reorder_extruders` they are 0-based (`ToolOrdering.cpp:900-905`).
- Wipe tower planning replays exactly that list: `Print.cpp:3233-3262` — `for (auto &layer_tools : ...layer_tools()) { ... for (const auto extruder_id : layer_tools.extruders) { ... wipe_tower.plan_toolchange(...); current_extruder_id = extruder_id; } }`.
- GCode emission replays it again: `GCode.cpp:5817-5821` builds `layer_extruders = layer_tools.extruders` (plus stragglers, see Q6 risk), and the main loop `GCode.cpp:6208-6243` calls `m_wipe_tower->tool_change(*this, extruder_id, ...)` per tool, consuming pre-planned `ToolChangeResult`s in order (`m_tool_change_idx`, `GCode.cpp:1256-1261`). `WipeTowerIntegration::append_tcr` asserts the emitted tool matches the planned one (`GCode.cpp:431-436`).

So the dots design is wipe-tower-safe *because the split only routes to extruders that ToolOrdering already planned*. The split itself never invents inline toolchanges — components ride the normal per-layer by-extruder pass.

**The PROVEN live template (perimeter grouped-manual-pattern)** — the same routing pattern, but actually reachable today: `GCode.cpp:5623-5678`. Gate: `entity_type == PERIMETERS` (5626) and `grouped_manual_pattern_mixed_filament_id` (`GCode.cpp:5062-5083`, requires a comma in the normalized manual pattern). Splitter: `split_extrusion_collection_for_multi_perimeter_pattern` (`GCode.cpp:4238-4284`, buckets whole entities by `mixed_mgr.resolve_perimeter(...)` per `inset_idx`). Routing: identical `object_islands_by_extruder` + `by_region[...].append(...)` + `continue` shape (5646-5665); `bucket_count == 1` collapses to a plain `correct_extruder_id` (5667-5676, 0-based note at 5671).

## Q2 — Why erTopSolidInfill is excluded today

**There is no role gate.** Nothing in `GCode.cpp`, `Fill/Fill.cpp`, or `PrintRegion` filters `erTopSolidInfill` out of the mix split. The exclusion in this tree is the feature-wide kill described in the headline (parse coercion + `#if 0` ×3). If the sequence builder were live and mode 1 survived parsing, the splitter at `GCode.cpp:5580-5621` **would already split top solid infill**, because:
- `configured_filament_id_1based` returns `region.config().solid_infill_filament.value` for `is_solid_infill(role)` (`GCode.cpp:5022-5023`), and `is_solid_infill(erTopSolidInfill)` is true (`ExtrusionEntity.hpp:89-96`);
- the mix id reaches `solid_infill_filament` (Q3);
- the split block is keyed only on `configured_filament_id`, not on role or entity type.

Implication for the observed device asymmetry (body splits, top doesn't): that asymmetry is a property of the *shipped/orphaned* engine variant, not reproducible from this source. Whatever that variant did (role-gated splitter, different `solid_infill_filament` resolution, or a slice-time stripe mechanism that spared top shells), it is not in 9d6c160a. Plan accordingly: building the top-surface split here means building the dots path itself here, with top solid in scope from day one.

One subtle role quirk to keep in mind: internal solid infill at 100% sparse density is deliberately routed to the *sparse* filament chain (`GCode.cpp:5020-5021`, mirrored at `ToolOrdering.cpp:90-93` `internal_solid_infill_uses_sparse_filament`). `erTopSolidInfill` is not affected by that branch.

## Q3 — Does the mix virtual id reach `solid_infill_filament`? YES (both assignment paths), and where the only clamp lives

**Path A — per-volume `extruder` override:**
- `region_config_from_model_volume` (`PrintObject.cpp:3252-3283`) builds the region config; `apply_to_print_region_config` (`PrintObject.cpp:3214-3249`) copies the volume's `extruder` key into **all three** filament fields:
  ```cpp
  auto *opt_extruder = in.opt<ConfigOptionInt>(key_extruder);
  if (opt_extruder)
      if (int extruder = opt_extruder->value; extruder != 0) {
          out.sparse_infill_filament.value = extruder;
          out.solid_infill_filament.value  = extruder;     // PrintObject.cpp:3222
          out.wall_filament.value          = extruder;
      }
  ```
- **The only clamp**: `clamp_exturder_to_default` (`PrintObject.cpp:3189-3194`) resets `opt.value > num_total_filaments` to 1, applied to all three fields at `PrintObject.cpp:3271-3273`. The bound passed in is **`num_total_filaments`** (= physical + enabled mixed rows, `PrintApply.cpp:1458` `m_mixed_filament_mgr.total_filaments(num_extruders)`), at both call sites: `verify_update_print_object_regions(..., num_total_filaments, ...)` (`PrintApply.cpp:1965-1968`) and `generate_print_object_regions(..., num_total_filaments, ...)` (`PrintApply.cpp:1987-1994`). So a virtual id ≤ physical+enabled_mixed **passes the clamp intact**. (The header comment at `PrintObject.cpp:3187-3188` confirms intent: "physical + virtual/mixed".) Caveat: if the app ships a mix id while the engine's `MixedFilamentManager` has zero enabled rows (e.g. `mixed_filament_definitions` not parsed yet), the clamp silently rewrites it to extruder 1.

**Path B — Smart-Paint painted regions:** `PrintApply.cpp:1070-1090` (creation) and `PrintApply.cpp:820-844` (verification) assign the painted state id directly, **no clamp at all**:
```cpp
cfg.wall_filament.value    = painted_extruder_id;
cfg.solid_infill_filament.value = painted_extruder_id;   // PrintApply.cpp:1073
cfg.sparse_infill_filament.value       = painted_extruder_id;
```
Painted state ids within the virtual range route through the "literal path" with mixed-component expansion in `Print::apply` (`PrintApply.cpp:1860-1871`; the H2C fold at 1871-1888 only catches states *above* the virtual range).

**Downstream consumption — where the virtual id is and isn't collapsed:**
- GCode `configured_filament_id_1based` (`GCode.cpp:5022-5023`): returns the raw value — **virtual id intact**. This is what the splitter keys on.
- GCode `configured_extruder_id` → `layer_tools.solid_infill_filament(region)` (`GCode.cpp:5036-5037`): **collapses** to one physical tool — `LayerTools::solid_infill_filament` (`ToolOrdering.cpp:315-321`) runs `resolve_mixed_1based(id) - 1`, i.e. `MixedFilamentManager::resolve` (`MixedFilament.cpp:~2287-2326`: gradient-sequence by layer, height-weighted cadence, or ratio cadence → returns `component_a`/`component_b`/a gradient component). This single physical tool is the `correct_extruder_id` fallback used when the split doesn't fire.
- ToolOrdering registration (`ToolOrdering.cpp:798`) likewise registers only the resolved single component (Q4).

So: nothing between region config and `configured_filament_id` clamps the virtual id away in this tree. The collapse happens (by design) only in the `resolve_mixed` helpers.

## Q4 — ToolOrdering::collect_extruders: current registration and the needed mirror

`collect_extruders` (`ToolOrdering.cpp:660-833`).

**Perimeter mix registration (the model to copy)** — `ToolOrdering.cpp:739-769`:
```cpp
const unsigned int configured_wall = (extruder_override == 0) ? region.config().wall_filament.value : extruder_override;
unsigned int       wall_ext        = resolve_mixed(configured_wall, layerCount, ...);
const unsigned int grouped_id      = grouped_manual_pattern_mixed_filament_id_for_layer(layer_tools, configured_wall);  // 742-743
if (grouped_id != 0) {
    const std::vector<unsigned int> ordered =
        m_mixed_mgr->ordered_perimeter_extruders(grouped_id, m_num_physical, layerCount, ...);                          // 745-750
    if (!ordered.empty()) {
        if (ordered.size() >= 2)
            layer_tools.preserve_extruder_order = true;                                                                  // 752-753
        for (unsigned int extruder_id : ordered) {
            layer_tools.extruders.emplace_back(extruder_id);                                                             // 754-755 (1-based here)
            ...firstLayerExtruders bookkeeping...
        }
    } else { layer_tools.extruders.emplace_back(wall_ext); ... }
} else { layer_tools.extruders.emplace_back(wall_ext); ... }
```

**Infill registration today (single-tool)** — `ToolOrdering.cpp:775-812`. Role scan (778-787) sets `has_solid_infill` via `is_solid_infill(role)` (784, includes `erTopSolidInfill`), then:
```cpp
if (something_nonoverriddable || !m_print_config_ptr) {
    if (extruder_override == 0) {
        if (has_solid_infill)
            layer_tools.extruders.emplace_back(layer_tools.solid_infill_filament(region) + 1);   // 797-798
        if (has_sparse_infill)
            layer_tools.extruders.emplace_back(layer_tools.sparse_infill_filament(region) + 1);  // 800-801
    } else if (...) { layer_tools.extruders.emplace_back(resolve_mixed(extruder_override, ...)); }  // 803-808
}
```
`solid_infill_filament(region)` collapses the mix to **one** component per layer via `resolve_mixed_1based` (`ToolOrdering.cpp:315-321`; `resolve_mixed_with_layer_heights` at `ToolOrdering.cpp:30-69`). That is the registration gap: with a live dots split, the *other* components are never planned.

**What to add (mirror of 744-764), inside the same block at ~795-810:** when `extruder_override == 0 && has_solid_infill` and the configured solid-infill id (`region.config().solid_infill_filament.value` — raw, not resolved) is a dots-mode mix with a valid ≥2-component sequence, emplace **each unique component (1-based)** into `layer_tools.extruders` instead of (or in addition to) the single resolved id. A shared sequence helper should serve both this and the GCode splitter so planning and emission can never disagree. Same treatment for `has_sparse_infill` if body infill is in scope. Dedup is automatic: `sort_remove_duplicates(layer.extruders)` at `ToolOrdering.cpp:824` (or `remove_duplicates_preserve_order` at 822 when `preserve_extruder_order` was set by the perimeter path). Registration here is 1-based; the global 0-based reindex happens at the end of `reorder_extruders` (`ToolOrdering.cpp:900-905`).

`preserve_extruder_order`: only needed if the dots components must print in a specific order within the layer (the perimeter grouped path needs it; dots does not obviously need it — segments are interleaved spatially, not sequentially). Leaving it unset lets `reorder_extruders` minimise toolchanges. Setting it from two different paths is harmless (it's a bool).

## Q5 — Where top-surface extrusions become a distinguishable collection

**Role assignment** — `Layer::group_fills` (`Fill/Fill.cpp`), role chosen per surface at `Fill.cpp:884-898`:
```cpp
params.extrusion_role = erInternalInfill;
if (is_bridge) { ... }
else if (surface.is_solid()) {
    if (surface.is_top())          params.extrusion_role = erTopSolidInfill;   // Fill.cpp:892
    else if (surface.is_bottom())  params.extrusion_role = erBottomSurface;
    else                           params.extrusion_role = erSolidInfill;
}
```
Surfaces are grouped into `SurfaceFill`s keyed by (region group, `SurfaceFillParams` — which includes the role, pattern, flow, speed: `Fill.cpp:837-986`); expolygons are mutually clipped per group (`Fill.cpp:1015-1028`).

**Emission granularity** — `Layer::make_fills` iterates `surface_fills`, and **per ExPolygon (island) within each SurfaceFill** calls the filler (`Fill.cpp` ~1279-1303; per-expolygon loop visible as `for (ExPolygon& expoly : surface_fill.expolygons)` with `surface_fill.surface.expolygon = std::move(expoly)` then `f->fill_surface_extrusion(&surface_fill.surface, params, m_regions[surface_fill.region_id]->fills.entities)` at `Fill.cpp:1300-1302`). `Fill::fill_surface_extrusion` (`Fill/FillBase.cpp:132-190`) allocates **one new `ExtrusionEntityCollection` per call** (`out.push_back(eec = new ExtrusionEntityCollection())`, FillBase.cpp:159-160) whose child paths all carry `params.extrusion_role`.

So `layerm->fills.entities` is a flat list of **per-island, role-uniform collections** — exactly what the GCode loop iterates at `GCode.cpp:5476-5480` (`for (const ExtrusionEntity* ee : layerm->fills.entities)` … `static_cast<const ExtrusionEntityCollection*>(ee)`). A GCode-time splitter can select exactly the top-surface islands with `is_top_surface(filtered_extrusions->entities.front()->role())` (`ExtrusionEntity.hpp:84-86`: `role == erTopSolidInfill`). Granularity is therefore: **per object → per layer → per region → per island → per role-group**. (Edge cases sharing this list: thin-fill collections at `Fill.cpp:1310-1315` — role from the thin fill, not top; ironing collections at `Fill.cpp:1659-1661` with `no_sort=true`, role `erIroning` — `is_solid_infill(erIroning)` is true, so role-gate with `erTopSolidInfill` specifically, not `is_solid_infill`, if ironing must stay single-tool.)

## Q6 — Recommended minimal insertion points

**(a) GCode-time split + island routing for top solid infill of a mix region**

The routing skeleton already exists and is role-agnostic. Minimal plan:
1. **Un-coerce mode 1**: remove/condition `normalize_distribution_mode_without_pointillism` at `MixedFilament.cpp:614` and the `disable_pointillism_mode` calls at `MixedFilament.cpp:1988, 2018, 2183, 2219` so `distribution_mode == SameLayerPointillisme` survives `load_custom_entries` (entered from `PrintApply.cpp:1442`).
2. **Provide a live sequence source**: a new function (e.g. in `MixedFilamentManager` so ToolOrdering and GCode share it) building the component sequence from `component_a/b` + `mix_b_percent`, or `gradient_component_ids` + `gradient_component_weights` — do **not** resurrect the `#if 0` body at `GCode.cpp:4019-4086` as-is; mirror its validation rules (≥2 unique components within `num_physical`, cycle cap) but route both consumers through one implementation.
3. **Hook point**: the existing block at `GCode.cpp:5580-5621` already covers `erTopSolidInfill` collections once `pointillism_sequence_for_filament` returns a sequence (because `configured_filament_id_1based` hands it `solid_infill_filament`, Q2/Q3). If the desired scope is **top solid only** (leaving body infill to whatever ships separately), add a role gate before invoking the splitter: compute `const ExtrusionRole role = filtered_extrusions->entities.front()->role()` (as at `GCode.cpp:5019`) and require `role == erTopSolidInfill` for INFILL entities. The split/route/`continue` body should be copied structurally from the proven perimeter template at `GCode.cpp:5637-5666` / dots template at `GCode.cpp:5588-5618` — including the `bucket_count >= 2` guard, the `bucket_count == 1` collapse to `correct_extruder_id` (`GCode.cpp:5667-5676`, **0-based** bucket index), and ownership transfer of the split collections into `local_z_clipped_collections` (`GCode.cpp:5604`) so they outlive emission.
4. Keep the `is_anything_overridden` bypass (`GCode.cpp:5582,5625`) — wipe-into-infill overrides and entity splitting must not mix.

**(b) ToolOrdering registration** — as detailed in Q4: extend the infill block at `ToolOrdering.cpp:795-810` to register all dots components (1-based) for `has_solid_infill` (and `has_sparse_infill` if body infill is also split by this mechanism), keyed off the **raw** `region.config().solid_infill_filament.value` and the same shared sequence helper.

**Double-registration / divergence risks (the `append_tcr` assert):**
- The assert: `GCode.cpp:431-436` — `WipeTowerIntegration::append_tcr` throws when the emitted toolchange's tool differs from the planned `tcr.new_tool`. Planning source: `Print.cpp:3233-3262` iterates `layer_tools.extruders` exactly. Emission source: `GCode.cpp:6208-6243` iterates `layer_extruders`.
- **Critical risk — under-registration**: `GCode.cpp:5817-5821` appends any `by_extruder` key missing from `layer_tools.extruders` to the **end** of `layer_extruders`. So if the splitter routes top-solid pieces to a component that ToolOrdering didn't register, GCode will still try to emit it, the wipe tower has no planned `tcr` for it, and the realignment helper (`GCode.cpp:944-980`) can only re-order *within planned* changes — result: the `append_tcr` throw (or, depending on path, `GCode.cpp:694-698`, the `append_tcr2` twin). **Registration in collect_extruders is mandatory, not an optimisation.**
- **Benign over-registration**: if ToolOrdering registers components but the GCode split falls back single-tool for every island on a layer (`split_stats.bucket_count < 2` → fallback at `GCode.cpp:5620`), the planned extra toolchange still gets emitted by the `6208` loop as a purge-only visit (matching `tcr` exists → no assert; just wasted filament/time). Same behaviour class as the existing perimeter grouped path, which also plans from a layer-level prediction (`ToolOrdering.cpp:745-750`) that GCode may not fully realise.
- **Duplicate component registration** across wall/sparse/solid paths is harmless: dedup at `ToolOrdering.cpp:820-824`.
- **Do not register the virtual id itself** into `layer_tools.extruders` — only physical components. Everything downstream (`filament_soluble.get_at(id-1)` at `ToolOrdering.cpp:887`, wipe volume matrix indexing at `Print.cpp:3244`, `m_writer` tool ids) indexes physical arrays.
- Index-base traps: `layer_tools.extruders` is 1-based during `collect_extruders`, decremented once in `reorder_extruders` (`ToolOrdering.cpp:900-905`); split buckets and `by_extruder` keys are 0-based (`GCode.cpp:4192, 5606`); `LayerTools::solid_infill_filament` returns 0-based (`ToolOrdering.cpp:320`), hence the `+ 1` at `ToolOrdering.cpp:798`.

**(c) Decimation guard**: `unique_extruder_count_for_gcode` (`GCode.cpp:5055-5056`) clears the cached sequence if it collapses to <2 physical tools — keep that exact guard in the new source so single-component "mixes" never enter the split path.

## Q7 — LAYER_CYCLE vs SAME_LAYER_DOTS: where the mode branches, and applicability of a top-surface split

**Mode branch points:**
- Parse/normalize: `MixedFilament.cpp:534-536` (clamp to [LayerCycle..Simple]), `:614` + `:855-869` (the dots-killing coercion), serialization writes the clamped mode (`MixedFilament.cpp:2030`).
- Resolution: `MixedFilamentManager::resolve` (`MixedFilament.cpp:~2287-2326`) implements LayerCycle (and Simple) semantics — gradient sequence indexed by `layer_index`, height-weighted cadence, or `ratio_a/ratio_b` layer cadence; **one physical component per (row, layer)**. Dots rows never reach a dedicated branch in `resolve` (and after coercion, can't exist).
- Dots-only branches (all currently disabled): `GCode.cpp:4022`, `PrintObjectSlice.cpp:1796` and `:2340`, `PrintApply.cpp:1220`; plus live "keep virtual identity" / "no surface offset" guards that *would* apply to dots rows: `MixedFilament.cpp:2376` (`effective_painted_region_filament_id` returns the virtual id unchanged for dots so painted regions don't merge), `MixedFilament.cpp:2410` (`component_surface_offset` returns 0 for dots).

**Applicability:** a top-surface split keyed on a per-layer component sequence is **dots-specific by design**. LAYER_CYCLE's contract is "one component per layer", and that already works for top solid infill today through the live collapse chain: `LayerTools::solid_infill_filament` → `resolve_mixed_1based` → cadence (`ToolOrdering.cpp:315-321`, `MixedFilament.cpp:2317-2326`), both for registration (`ToolOrdering.cpp:798`) and for GCode's `correct_extruder_id` (`GCode.cpp:5036-5037`). Applying an intra-layer split to a LayerCycle row would change that mode's meaning, not extend it. Recommendation: gate the new sequence source strictly on `distribution_mode == int(MixedFilament::SameLayerPointillisme)` (as the `#if 0` builders did at `GCode.cpp:4022` / `PrintObjectSlice.cpp:1796`), so LAYER_CYCLE rows keep flowing through `resolve` untouched, in both ToolOrdering and GCode. The split mechanism itself (split → bucket → island-route) is mode-agnostic plumbing; only the sequence source is mode-aware.

---

## Appendix — quick-reference anchor table

| Concern | File:line |
|---|---|
| Dots mode enum | `MixedFilament.hpp:26-30` |
| Parse-time mode-1 coercion | `MixedFilament.cpp:614, 855-869` (call sites 1988, 2018, 2183, 2219) |
| GCode dots sequence builder (`#if 0`) | `GCode.cpp:4019-4086` |
| Polyline splitter / gap trim (live) | `GCode.cpp:4088-4120, 4122-4146` |
| Collection splitter (live) | `GCode.cpp:4159-4236` |
| Pointillism path marker (-7777) | `GCode.cpp:4154-4157`, consumed 7312-7320 |
| Perimeter pattern splitter (live, proven) | `GCode.cpp:4238-4284` |
| `object_islands_by_extruder` | `GCode.cpp:4286-4297` |
| `configured_filament_id_1based` | `GCode.cpp:4998-5027` |
| `configured_extruder_id` (collapsing) | `GCode.cpp:5029-5041` |
| Sequence cache lambda | `GCode.cpp:5043-5061` |
| Dots split + routing block | `GCode.cpp:5580-5621` |
| Perimeter mix split + routing block | `GCode.cpp:5623-5678` |
| Straggler-tool append (assert risk) | `GCode.cpp:5817-5821` |
| Per-extruder emission + wipe tower tool_change | `GCode.cpp:6208-6243` |
| `append_tcr` assert | `GCode.cpp:431-436` (twin at 694-698) |
| Wipe tower realignment helper | `GCode.cpp:944-980` |
| Wipe tower planning from LayerTools | `Print.cpp:3233-3262` |
| `collect_extruders` perimeter mix registration | `ToolOrdering.cpp:739-769` |
| `collect_extruders` infill registration | `ToolOrdering.cpp:775-812` |
| `LayerTools::solid_infill_filament` collapse | `ToolOrdering.cpp:315-321` |
| 1-based → 0-based reindex | `ToolOrdering.cpp:900-905` |
| Volume `extruder` → 3 filament fields | `PrintObject.cpp:3214-3224` |
| The only clamp (`num_total_filaments`) | `PrintObject.cpp:3189-3194, 3271-3273`; bound from `PrintApply.cpp:1458, 1965-1968, 1987-1994` |
| Painted region filament assignment (no clamp) | `PrintApply.cpp:1070-1090` (verify: 820-844) |
| Top-surface role assignment | `Fill/Fill.cpp:884-898` (erTopSolidInfill at 892) |
| Per-island collection creation | `Fill/FillBase.cpp:132-190` (new EEC at 159-160) |
| `is_solid_infill` / `is_top_surface` | `ExtrusionEntity.hpp:89-96 / 84-86` |
| Slice-time stripe splitter (`#if 0`, orphaned) | `PrintObjectSlice.cpp:2186-2296, 2308-2514` |
| PrintApply same-layer gate (`#if 0`) | `PrintApply.cpp:1215-1224` |
| `mixed_filament_definitions` load | `PrintApply.cpp:1415-1416, 1442`; key def `PrintConfig.cpp:4324` |
