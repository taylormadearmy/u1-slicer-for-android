# Phase 1 Sub-Plan #3 — LayerToolPauseInjector Migration Design Notes

**Date:** 2026-04-24
**Branch:** `refactor/bambu-via-native-loader`
**Status:** Design notes only — no code changes.

---

## 1. Current Architecture (As-Is)

### Entry points that call `LayerToolPauseInjector`

| File | Line | Call | Context |
|------|------|------|---------|
| `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` | 2353 | `LayerToolPauseInjector.injectFrom3mf(result.gcodePath, it)` | Post-slice, inside the `result.success` branch. `it` is `layerToolMetadataFile` — resolved as `sourceModelFile` if present, else `currentModelFile`. |
| `app/src/androidTest/java/com/u1/slicer/slicing/ProfileEmbedderIntegrationTest.kt` | 402 | `LayerToolPauseInjector.injectFrom3mf(result.gcodePath, sourceAsset)` | Integration test for `flippyFlappyMini_fullPipeline_emitsLayerChangePauseGcode`. |

### Every caller of `LayerToolCustomGcodeXml` parse helpers

| Function | Callers (file:line) |
|----------|---------------------|
| `parseLayerToolCustomGcodeXml(xml)` | `ThreeMfParser.kt:245` (full-file parse), `ThreeMfParser.kt:511` (per-plate path), `LayerToolPauseInjectorTest.kt` (indirectly via injector) |
| `parseLayerToolCustomGcodeXmlPerPlate(xml)` | `ThreeMfParser.kt:251` |
| `parseLayerToolSegments(xml)` | `LayerToolPauseInjector.kt:136` (via `extractPauseTargets`), `ThreeMfParser.kt:248`, `NativePreparePreviewTest.kt:9` (import only, recolorByZBands path), `PreviewColorNormalizationTest.kt` (4 unit tests) |

### The zip→parse→inject path

1. **Zip open** — `LayerToolPauseInjector.injectFrom3mf` (line 25):
   ```kotlin
   val pauseCommand = ZipFile(model3mf).use { zip ->
       zip.getEntry("Metadata/custom_gcode_per_layer.xml")?.let { entry ->
           val xml = zip.getInputStream(entry).bufferedReader().readText()
           pauseTargets += extractPauseTargets(xml)   // calls parseLayerToolSegments
       }
       zip.getEntry("Metadata/project_settings.config")?.let { entry ->
           val json = ...
           nozzleTemps = parseNozzleTemperatures(json)   // reads nozzle_temperature array
           Regex(..."machine_pause_gcode"...).find(json)...
       }
   } ?: "M400 U1"
   ```
   The single `ZipFile.use` block at line 27 unzips, reads both entries, then closes the zip.

2. **XML→representation** — `extractPauseTargets(xml)` (line 136) calls `parseLayerToolSegments(xml)` which returns `List<LayerToolSegment>`. Each entry is a `data class LayerToolSegment(val topZ: Float, val extruderBambu: Int)` (defined at `ThreeMfInfo.kt:30`). These are converted to internal `PauseTarget(topZ, extruderBambu)` — a private data class at line 22.

3. **Project settings** — `machine_pause_gcode` string and `nozzle_temperature` array are also read from `project_settings.config` during the same zip open.

4. **Inject** — sorted `PauseTarget` list is consumed by a line-streaming pass over the G-code file (line 54 onward).

### Test fixtures exercising this path

| Class | Test method | Summary |
|-------|-------------|---------|
| `LayerToolPauseInjectorTest` | `injectFrom3mf inserts pause before first layer above target top_z` | Canonical pause+T-index+M109 injection at Z boundary |
| `LayerToolPauseInjectorTest` | `injectFrom3mf preserves nozzle_temperature array indexes with nil entries` | `nil` skipping in `nozzle_temperature` array |
| `LayerToolPauseInjectorTest` | `injectFrom3mf does nothing when no custom layer metadata exists` | No-op when entry absent |
| `LayerToolPauseInjectorTest` | `injectFrom3mf falls back to default pause command when source has no project settings` | `M400 U1` default |
| `LayerToolPauseInjectorTest` | `injectFrom3mf uses gcode temp fallback when project settings missing` | M104/M109 scan in G-code |
| `LayerToolPauseInjectorTest` | `injectFrom3mf uses current tool for M109 without T when project settings missing` | Current-tool tracking for bare `M109 Sn` |
| `LayerToolPauseInjectorTest` | `injectFrom3mf falls back to SM_PRINT_START_LINE target temp` | `SM_PRINT_START_LINE INDEX=N TARGET_TEMP=T` fallback |
| `LayerToolPauseInjectorTest` | `injectFrom3mf falls back to last seen M109 temp when tool-specific missing` | Last-seen-temp fallback |
| `LayerToolPauseInjectorTest` | `injectFrom3mf skips when native CP toolchange workflow already exists` | Guard against double-injection on SEMM G-code |
| `PreviewColorNormalizationTest` | 4 tests for `parseLayerToolSegments` | Parser unit tests for type/topZ/extruder extraction |
| `ProfileEmbedderIntegrationTest` | `flippyFlappyMini_fullPipeline_emitsLayerChangePauseGcode` | End-to-end: embed→slice→inject→assert pause+T1 in G-code |

---

## 2. Native Payload Already Available (Post-Sub-Plan #2)

### Relevant C++ serialization block (from `sapil_bambu_snapshot.cpp:177-200`, promoted to `append_plate`)

```cpp
out << "\"customGcode\":[";
auto it = g_model.plates_custom_gcodes.find(p.plate_index);
if (it != g_model.plates_custom_gcodes.end()) {
    const auto& items = it->second.gcodes;
    for (size_t j = 0; j < items.size(); ++j) {
        if (j) out << ",";
        const auto& g = items[j];
        char zbuf[32];
        std::snprintf(zbuf, sizeof(zbuf), "%.17g", g.print_z);
        out << "{"
            << "\"printZ\":" << zbuf << ","
            << "\"type\":\"" << json_escape(custom_gcode_type_name(static_cast<int>(g.type))) << "\","
            << "\"extruder\":" << g.extruder << ","
            << "\"color\":\"" << json_escape(g.color) << "\""
            << "}";
    }
}
out << "],";
```

This is called by `nativeGetPlateData(plateIdx)` via `sapil_bambu_plate.cpp`. `nativeGetPlateData` is declared at `NativeLibrary.kt:162` and already ships in the current `.so`.

### `type` values

The C++ emitter returns canonical enum names via `custom_gcode_type_name`:
```
"ColorChange", "PausePrint", "ToolChange", "Template", "Custom", "Unknown"
```
The Kotlin XML parser (`parseLayerToolSegments`) reads and passes through the raw XML attribute value `"1"` (ColorChange) or `"2"` (ToolChange). The injector's `extractPauseTargets` calls `parseLayerToolSegments` which only accepts `type == "1"` or `type == "2"`. After migration, the consumer must accept `"ColorChange"` or `"ToolChange"` instead.

### Field presence

| XML attribute | Native JSON field | Notes |
|---------------|-------------------|-------|
| `top_z` | `printZ` (Double, `%.17g`) | Kotlin uses `Float` for `topZ`; native uses `Double`. Narrowing conversion needed. |
| `type` | `type` | String, but different encoding: XML `"1"`/`"2"` vs native `"ColorChange"`/`"ToolChange"` |
| `extruder` | `extruder` | Int, 1-based in both |
| `color` | `color` | String (hex) |
| `extra` | **NOT PRESENT** | XML has `extra=""` attribute. The injector never reads `extra` — not a gap. |
| `gcode` | **NOT PRESENT** | XML has `gcode="M601"` / `gcode="tool_change"` attribute. The injector never reads `gcode` — not a gap. |

The injector also reads two fields from `project_settings.config` that are **not** in `nativeGetPlateData`:
- `machine_pause_gcode` — the U1 pause command string (e.g. `"M400 U1"`).
- `nozzle_temperature` — array of per-extruder temps (ints).

Neither field is in the native plate or project JSON payloads today. The `nativeGetProjectConfig` response (from `sapil_bambu_project.cpp`) does not include them. The injector currently reads these from the source 3MF zip directly, which is a separate concern from the customGcode list. See Section 5 — this is a real gap if the plan wants to eliminate the zip re-open entirely.

---

## 3. Migration Strategies

| Option | What Changes | No-Rebuild? | Risk | Rollback |
|--------|-------------|-------------|------|----------|
| **A — Direct swap** | Replace `ZipFile` + `parseLayerToolSegments` in `LayerToolPauseInjector` with `nativeGetPlateData(plateIdx)` JSON decode. Delete `LayerToolCustomGcodeXml.kt`. | Yes — `nativeGetPlateData` already ships. | High: type-string mismatch (`"1"` vs `"ColorChange"`), `Float` vs `Double` topZ, `machine_pause_gcode` + `nozzle_temperature` still need zip for now. Silent wrong-G-code if type comparison breaks. | `git revert` — no native change. |
| **B — Dual-path with comparison** | Parse BOTH (XML + native JSON); assert they agree in debug builds; emit native-derived result in prod. Run for 1-2 releases. Delete XML path in a follow-up commit. | Yes. | Low production risk; adds ~40 lines of comparison logic. Catches any native disagreement at test time. | Drop the assertion branch and fall back to XML path with one-line toggle. |
| **C — Feature flag** | Gate on a `DataStore` boolean `useNativePauseInjector`. Default `false`. Flip in alpha, delete flag+XML path later. | Yes. | Lowest risk; adds DataStore dependency to `LayerToolPauseInjector` (currently stateless object). Permanent dead-code risk if flag never flipped. | Flip flag to `false` in settings. |

**Files that change for all three options:**
- `app/src/main/java/com/u1/slicer/gcode/LayerToolPauseInjector.kt` — primary target.
- `app/src/main/java/com/u1/slicer/bambu/LayerToolCustomGcodeXml.kt` — deleted in Option A immediately; kept for Options B and C until confidence phase completes.
- `app/src/test/java/com/u1/slicer/gcode/LayerToolPauseInjectorTest.kt` — test 3MF fixtures hardcode XML `type="1"`/`"2"` — must be updated for Option A.

**Native `.so` rebuild needed?** No for all three options. `nativeGetPlateData` already ships.

**Recommendation: Option B (dual-path with comparison).** See Section 5 for rationale — the type-string mismatch and `machine_pause_gcode`/`nozzle_temperature` gap make Option A risky for a single-commit swap. Option B lets the integration test catch any disagreement before the XML path is deleted.

---

## 4. Test Strategy

### Can `LayerToolPauseInjectorTest.kt` (9 unit tests) be reused as-is?

No, not without changes. Each test constructs a synthetic 3MF zip with `Metadata/custom_gcode_per_layer.xml` and calls `injectFrom3mf(gcodePath, model3mf: File)`. The current signature takes a `File` (the 3MF) and opens it internally. After Option B migration, the injector will need either:
- The same `File` argument (still works if the zip-open for `machine_pause_gcode`/`nozzle_temperature` is kept), or
- A new overload that accepts pre-decoded data.

If the zip-open for `machine_pause_gcode` and `nozzle_temperature` is kept (recommended for this migration since native doesn't expose them yet), the 9 unit tests can be reused with only the type-string comparison updated in the XML helper. If the signature changes to remove the `File` argument, all 9 tests need new fixture construction.

**Safest path:** keep `injectFrom3mf(gcodePath, model3mf: File)` as the external signature. Internally, extract the native route as a separate private helper that can be activated by option B's dual-path. All 9 tests keep passing unchanged until the XML path is deleted.

### Dual-path divergence assertion skeleton (Option B)

```kotlin
// In LayerToolPauseInjector, after both paths produce targets:
private fun extractPauseTargetsFromNative(
    plateIdx: Int,
    native: NativeLibrary
): List<PauseTarget> {
    val json = native.nativeGetPlateData(plateIdx) ?: return emptyList()
    val plate = JSONObject(json)
    val arr = plate.optJSONArray("customGcode") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.getJSONObject(i)
        val type = o.optString("type")
        if (type != "ColorChange" && type != "ToolChange") return@mapNotNull null
        val topZ = o.optDouble("printZ", 0.0).toFloat()
        val extruder = o.optInt("extruder", 1)
        PauseTarget(topZ, extruder)
    }.sortedBy { it.topZ }
}

// Divergence check in debug/test builds:
if (BuildConfig.DEBUG) {
    check(xmlTargets == nativeTargets) {
        "LayerToolPauseInjector: XML/native mismatch: xml=$xmlTargets native=$nativeTargets"
    }
}
```

This assertion will fire in any instrumented test run that calls `injectFrom3mf` with a Bambu file whose custom gcode list disagrees between paths.

### Does an instrumented integration test exercise slice→pause→verified G-code?

Yes: `ProfileEmbedderIntegrationTest.kt:390` (`flippyFlappyMini_fullPipeline_emitsLayerChangePauseGcode`). It:
1. Loads `flippy+flappy+mini.3mf` via `fullPipeline`.
2. Slices.
3. Calls `LayerToolPauseInjector.injectFrom3mf(result.gcodePath, sourceAsset)`.
4. Asserts the resulting G-code contains `M400 U1`, `; PAUSE_PRINT`, `; layer_tool extruder 2`, and `; single_extruder_multi_material = 0`.

This test is the primary regression guard for Option B's dual-path — it will catch both "no pauses injected" and "wrong extruder" regressions.

---

## 5. Known Risks

### Risk 1: `machine_pause_gcode` and `nozzle_temperature` are not in native payload

The injector reads two config values from `project_settings.config` inside the same `ZipFile.use` block as the customGcode XML:
- `machine_pause_gcode` — determines the pause command string (default `"M400 U1"`).
- `nozzle_temperature` — per-extruder int array, preferred over G-code fallbacks.

Neither is in `nativeGetPlateData` or `nativeGetProjectConfig`. The migration plan **must** either:
1. Keep the zip re-open solely for these two fields (sub-optimal but safe), or
2. Extend `nativeGetProjectConfig` to emit `machine_pause_gcode` and `nozzle_temperature` (requires no C++ rebuild — these keys are already in `getModelConfig()`'s `DynamicPrintConfig` via `profile_keys[]`; it's just a matter of reading them).

Option 2 is preferred for a clean migration. It's a ~10-line addition to `sapil_bambu_project.cpp` and does NOT need a native rebuild for the customGcode path — only for eliminating the zip re-open.

**If a native rebuild is deferred:** split the migration into two commits — (A) swap customGcode source to native; (B) swap `machine_pause_gcode`/`nozzle_temperature` source after native rebuild.

### Risk 2: Slice-time call — silent wrong G-code is the failure mode

`injectFrom3mf` runs post-slice, directly modifying the `.gcode` file on disk. A regression produces silently wrong output:
- Missing pauses → user's layer-change print starts wrong colour.
- Wrong extruder index → wrong T-command emitted → wrong filament loaded.
- Extra pauses → print stops unexpectedly.

These failure modes are worse than a viewer glitch because they happen on physical hardware with no error message. The `ProfileEmbedderIntegrationTest` integration test is the only automated gate; the 9 unit tests only cover the injection logic, not the data source.

**Output invariants the migration must preserve:**
- For each `CustomGCode::Type::ColorChange` / `ToolChange` entry at `topZ`, a `; PAUSE_PRINT\n<pauseCommand>\n` is injected before the first `;LAYER_CHANGE` line whose `;Z:N` is strictly above `topZ`.
- The T-command uses `extruderBambu - 1` (0-based tool index).
- An `M109 S<temp> T<toolIndex>` follows the T-command.
- Injection is skipped entirely when `; CP TOOLCHANGE START` is present (SEMM path).

### Risk 3: `g_model` is loaded during the slice call — but is it after?

In `SlicerViewModel.kt:2353`, `injectFrom3mf` is called after `native.slice(sliceConfig)` returns, still inside the slicing coroutine, with no intervening `clearModel()`. The model is still loaded at this point. `nativeGetPlateData(plateIdx)` would therefore return valid data if called here.

However, `injectFrom3mf` currently opens the **source** 3MF file (`sourceModelFile` or `currentModelFile`), not the sliced output. The current approach is "read fresh from file" — independent of whether `g_model` is loaded. Migrating to native reads changes the data source from "the file on disk at injection time" to "the g_model state at injection time."

Implication: if `g_model` is cleared between slice and inject (e.g., by another concurrent operation grabbing `previewMutex`), `nativeGetPlateData` returns `null`. The caller must handle `null` → fall back to XML or skip injection. This is not a current risk (the slicing coroutine holds exclusive flow), but it is an architectural coupling that Option A (direct swap) introduces silently.

Option B (dual-path) surfaces this via the divergence assertion.

### Risk 4: Plate index selection

The current injector does NOT pass a plate index — it opens the source 3MF and reads the global `Metadata/custom_gcode_per_layer.xml` for all plates. `parseLayerToolSegments` processes all `<layer>` entries regardless of `<plate_info id="">`. This means it currently picks up custom gcode from ALL plates, not just the active one.

`nativeGetPlateData(plateIdx)` is per-plate. The migration must pass the correct 0-based `plateIdx` for the active plate. In `SlicerViewModel.kt`, the active plate is tracked by `_currentPlateIndex` (or equivalent). Verify the call site has access to it — or, for a first migration, always pass `plateIdx = 0` since most users slice plate 0.

`parseLayerToolCustomGcodeXmlPerPlate` (the per-plate XML variant) exists and is called by `ThreeMfParser.kt:251`, but `LayerToolPauseInjector` does NOT use it — it calls the non-plate-aware `parseLayerToolSegments`. The native migration is therefore an improvement in accuracy (per-plate) as well as a code simplification.

---

## 6. Scope Firewall for Sub-Plan #3

Sub-plan #3 **must NOT touch:**
- `BambuSanitizer.extractPlate` — that is #2b scope.
- `ThreeMfParser.parseLayerToolSegments` / `ThreeMfParser.parseLayerToolCustomGcodeXmlPerPlate` — these serve `ThreeMfInfo.layerToolSegments`, used by `recolorByZBands` in the preview viewer. Separate concern.
- Native `.so` rebuild — not needed for the customGcode data path. If `machine_pause_gcode`/`nozzle_temperature` native emission is added, that requires a rebuild but is a separate, optional commit.
- `SlicerViewModel`'s plate selection logic, `BambuPipelineIntegrationTest`, `SemmSlicingTest`, `GoatDedupeSemmTest`.
- `PreviewColorNormalizationTest.kt` — exercises `parseLayerToolSegments` for the viewer's Z-band recolor path. Do not delete `parseLayerToolSegments` until that path is separately migrated.

---

## 7. Suggested Commit Sequence

1. **Add `extractPauseTargetsFromNativeJson(plateJson: String): List<PauseTarget>`** as a private static helper in `LayerToolPauseInjector`. Parses `nativeGetPlateData` JSON customGcode array. Add unit test that calls it directly with a hand-crafted JSON string (verifies `"ColorChange"`/`"ToolChange"` filtering and `Float` conversion). No behaviour change yet.

2. **Wire dual-path in `injectFrom3mf`**: after existing `extractPauseTargets(xml)` call, call `native.nativeGetPlateData(plateIdx)` and compare. Gate divergence assertion on `BuildConfig.DEBUG`. `injectFrom3mf` gains a `native: NativeLibrary` parameter (breaking change — update both call sites: `SlicerViewModel.kt:2353` and `ProfileEmbedderIntegrationTest.kt:402`). Emit native-derived targets in production path. Zip re-open for `machine_pause_gcode` and `nozzle_temperature` is retained.

3. **Run `ProfileEmbedderIntegrationTest` green** on device. Confirm dual-path assertion does not fire for `flippy+flappy+mini.3mf`.

4. **Delete XML parse from `injectFrom3mf`**: remove `extractPauseTargets`, remove the `parseLayerToolSegments` import, remove the divergence fallback. `LayerToolCustomGcodeXml.kt`'s `parseLayerToolSegments` now has zero callers from the injector. Update unit tests: replace synthetic `custom_gcode_per_layer.xml` fixture construction with synthetic native JSON strings (or keep 3MF fixtures and verify via the native path end-to-end).

5. **Delete `LayerToolCustomGcodeXml.kt`** if `parseLayerToolCustomGcodeXml`, `parseLayerToolCustomGcodeXmlPerPlate`, and `parseLayerToolSegments` have no remaining callers outside `ThreeMfParser.kt`. If `ThreeMfParser.kt` still uses them (lines 245/248/251/511), leave the file but remove `parseLayerToolSegments` from it (move remaining functions to `ThreeMfParser.kt` or a new `bambu/` file). Update `CLAUDE.md` test counts.

6. **(Optional — separate native rebuild commit)** Extend `nativeGetProjectConfig` to emit `machine_pause_gcode` and `nozzle_temperature`. Remove the `project_settings.config` zip re-open from `injectFrom3mf`. Requires NDK 26 / Release rebuild checklist.
