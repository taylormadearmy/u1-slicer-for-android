# Phase 1 sub-plan #5 — Project-level config (fileVersion, isBbl, filamentColours) via JNI accessor (design notes)

**Date:** 2026-04-23
**Scope:** Pre-flight research for exposing `fileVersion`, `isBbl`, project `filamentColours`, `filamentSettingsIds`, and `filamentIds` via a single new JNI accessor backed by the already-populated `g_is_bbl`, `g_file_version`, and `getModelConfig()`, letting `KotlinBambuSnapshot` converge with the native snapshot on these fields.
**Status:** Research only. No code touched. Based on commit 3f6ff20 (post sub-plan #1).

---

## 1. Kotlin production call sites of filament-colour / version extraction

### `ThreeMfParser.kt` — current line ranges (post commit 3f6ff20)

The file is 1187 lines long. The roadmap cited ~916-960 and ~997-1010; both regions shifted slightly. Actual current positions:

- **Lines 920-943** — `detectColorsFromJsonSettings(inputStream, colors)`: reads `project_settings.config` as a `JSONObject`, extracts `filament_colour` array first (per-filament), falls back to `extruder_colour` array (per-slot) if colours list is still empty. Each value is matched by regex `#[0-9A-Fa-f]{6,8}`, truncated to 7 chars (`#RRGGBB`).
- **Lines 949-971** — `detectColorsFromSlic3rConfig(inputStream, colors)`: handles semicolon-delimited INI (PrusaSlicer `Slic3r_PE.config`). Looks for a line starting with `extruder_colour` or `filament_colour`, splits on `;`, extracts hex values.
- **Lines 999-1017** — `detectColorsFromIniConfig(inputStream, colors)`: OrcaSlicer/generic `config.ini` INI variant using a multi-match regex instead of a single-line break.

### Colour-source priority order (lines 253-315)

All four detectors are tried in strict sequential order; each is skipped if `detectedColors` is already non-empty:

1. `Metadata/filament_sequence.json` — Bambu per-filament sequence (regex over `"color":"#..."`)
2. `Metadata/project_settings.config` — JSON format (`filament_colour` > `extruder_colour`)
3. `Metadata/Slic3r_PE.config` — PrusaSlicer INI (only used when it has multi-extruder assignments or paint data)
4. `config.ini` / `Metadata/config.ini` — OrcaSlicer generic INI

After all four: if `layerToolColors` is non-empty and `detectedColors.size <= 1`, the layer-tool colours replace the whole list (line 313).

Then at lines 386-391: if `detectedColors` is still empty and `extruderCount > 1`, the list is padded with a default palette; if `detectedColors.size < extruderCount`, it is padded to match.

`isBambu` is detected **before** any colour parsing (line 88), using a cheap zip-entry set membership check against `BAMBU_MARKERS` (5 entries including `Metadata/model_settings.config`). This is pure O(1) after the entry-name scan that must happen anyway — it is correctly "light" as the roadmap claims.

### What `detectedColors` feeds

`detectedColors` is set on `ThreeMfInfo.detectedColors`. Production consumers in `app/src/main/java/`:

| Caller | Site | Path classification |
|---|---|---|
| `KotlinBambuSnapshot.kt:47` | `filamentColours = info.detectedColors.toList()` for every plate | snapshot / diff harness |
| `MainActivity.kt:790` | passed to `ExtruderColorsRow` for display | UI display (cold, on file open) |
| `MainActivity.kt:999` | passed into info dialog colour display | UI display (cold) |
| `MainActivity.kt:2032-2033` | `InfoRow("Colors", ...)` in model info panel | UI display (cold) |
| `MainActivity.kt:2831-2832` | another `InfoRow("Colors", ...)` | UI display (cold) |
| `SlicerViewModel.kt:851,1016` | logged only | diagnostics |
| `SlicerViewModel.kt:865,1028` | `"detectedColors" to origInfo.detectedColors` in event map | diagnostics |
| `SlicerViewModel.kt:1241,1248` | `buildAutoExtruderMapping(rawMapping, mfInfo.detectedColors.size)` | **hot path** — slice prep |
| `SlicerViewModel.kt:1386` | passed into extruder presets state | prepare/slice config |
| `SlicerViewModel.kt:1529` | drives colour-picker initialization | prepare UI |
| `SlicerViewModel.kt:3354,3445,3469,3474,3527,3529,3532` | colour fallback / merge logic in multi-plate case | slice orchestration |
| `debug/TestCommandReceiver.kt:241` | diagnostic log | internal debug only |

**Conclusion:** `detectedColors` is on hot paths — it feeds `buildAutoExtruderMapping` during slice prep and the colour-picker initialization. The snapshot/diff path (`KotlinBambuSnapshot.kt:47`) is a read-only consumer; sub-plan #5 replaces only that snapshot consumer. The production slice prep consumers remain on the Kotlin-parsed path and must not be touched by this sub-plan.

`isBambu` production consumers in `app/src/main/java/`:

| Caller | Site | Nature |
|---|---|---|
| `BambuSanitizer.kt:87` | decides whether to sanitize | **hot path** — called in slice prep |
| `ProfileEmbedder.kt:201` | metadata-preserve guard | slice prep |
| `MainActivity.kt:2823` | gates Bambu-specific info display | UI, cold |
| `ModelExportArtifacts.kt:34,76` | export artifact gating | export, cold |
| `SlicerViewModel.kt:850,860,1015,1023,1657,1664,1727,1730,1741,3351,3552,3585` | slice pipeline decisions, logging | **hot path** |

`BambuSanitizer.kt:87` calls `ThreeMfParser.parse(inputFile).isBambu` when the caller passes `isBambu = null` — a full parse on an uncached file. All production call sites that reach this pass `isBambu = origInfo.isBambu` (already parsed), so the re-parse is only the fallback. Still: **any new JNI accessor must not become the primary `isBambu` check on the production slice path** — see Section 5.

---

## 2. C++ side — what is already built

### `g_is_bbl`, `g_file_version` — externability

`app/src/main/cpp/src/sapil_model.cpp` lines 41-42:

```cpp
bool g_is_bbl = false;           // exposed to sapil_bambu_snapshot.cpp
Slic3r::Semver g_file_version;   // exposed to sapil_bambu_snapshot.cpp
```

Neither has `static`. Both are declared `extern` in `sapil_bambu_snapshot.cpp` lines 42-43 and are already used there. They are externally linkable with no Phase 0 changes needed. The comment "exposed to sapil_bambu_snapshot.cpp" confirms intent.

`g_model_config` at line 36 is declared `static Slic3r::DynamicPrintConfig g_model_config;` — it is **file-scope private**. A new TU cannot extern it directly. It is accessed via the non-static public function `getModelConfig()` at line 644:

```cpp
Slic3r::DynamicPrintConfig& getModelConfig() {
    return g_model_config;
}
```

`sapil_bambu_snapshot.cpp` lines 48 declares this as `extern Slic3r::DynamicPrintConfig& getModelConfig();` and calls it at line 322. A new TU `sapil_bambu_project.cpp` can use exactly the same `extern` declaration. No Phase 0 work needed; the accessor pattern is already established.

### The project-config block (lines 322-334)

```cpp
// sapil_bambu_snapshot.cpp lines 308-334
out << "\"isBbl\":" << (g_is_bbl ? "true" : "false") << ",";
out << "\"fileVersion\":\"" << json_escape(g_file_version.valid() ? g_file_version.to_string() : "") << "\",";

const auto& project_cfg = getModelConfig();
const auto* project_colours = project_cfg.opt<Slic3r::ConfigOptionStrings>("filament_colour");
const auto* project_filament_ids = project_cfg.opt<Slic3r::ConfigOptionStrings>("filament_ids");
const auto* project_filament_settings_id =
    project_cfg.opt<Slic3r::ConfigOptionStrings>("filament_settings_id");

out << "\"plates\":[";
for (size_t i = 0; i < g_plate_data_list.size(); ++i) {
    if (i) out << ",";
    if (g_plate_data_list[i] == nullptr) { out << "null"; continue; }
    append_plate(out, *g_plate_data_list[i],
                 project_colours, project_filament_ids, project_filament_settings_id);
}
```

A `nativeGetProjectConfig()` function is essentially a standalone extraction of this same block — `g_is_bbl`, `g_file_version`, and the three `ConfigOptionStrings` pointers — into a self-contained JSON string. All five pieces are already read in one place, and all five externs are in scope.

### `append_plate` cascade for `filamentSettingsIds` (lines 139-154)

```cpp
// filamentSettingsIds: prefer PlateData's per-filament `filament_id`
// when present, else fall back to the project-level `filament_settings_id` array
// (user-preset names), else `filament_ids`.
out << "\"filamentSettingsIds\":[";
if (!p.slice_filaments_info.empty()) {
    for (size_t j = 0; j < p.slice_filaments_info.size(); ++j) { ... }
} else {
    const Slic3r::ConfigOptionStrings* fallback =
        project_filament_settings_id != nullptr ? project_filament_settings_id : project_filament_ids;
    if (fallback != nullptr) { ... }
}
```

Cascade priority: `slice_filaments_info[j].filament_id` (plate-level) > `filament_settings_id` (project) > `filament_ids` (project). `filament_settings_id` wins over `filament_ids` when both are present.

---

## 3. JSON shape + Kotlin data class impact

### Proposed JSON shape for `nativeGetProjectConfig()`

```json
{
  "isBbl": true,
  "fileVersion": "01.09.01.52",
  "filamentColours": ["#FFAABB", "#001122", ...],
  "filamentSettingsIds": ["Bambu PLA Basic @BBL X1C 0.4 nozzle", ...],
  "filamentIds": ["GFB98", ...]
}
```

This is a direct extraction of the five fields already computed in `bambu_snapshot_json()` before the plate loop starts. No per-plate logic is needed in the new function.

### Mapping to `BambuFileSnapshot`

- `isBbl` → `BambuFileSnapshot.isBbl: Boolean` (root field, direct assignment)
- `fileVersion` → `BambuFileSnapshot.fileVersion: String` (root field, currently hardcoded `""`)
- `filamentColours` / `filamentSettingsIds` / `filamentIds` → `PlateSnapshot.filamentColours` / `PlateSnapshot.filamentSettingsIds` as a **file-level fallback**

Sub-plan #5 populates the root fields immediately. The plate fields are more nuanced:

**Current Kotlin path** (`KotlinBambuSnapshot.kt:47`): every `PlateSnapshot` gets `filamentColours = info.detectedColors.toList()` — a copy of the file-wide detected list.

**Native path** (`append_plate` lines 122-134): each plate gets `filamentColours` from `slice_filaments_info` when non-empty, otherwise from `project_colours`. This means:
- For **unsliced files** (no `slice_filaments_info`), native emits the project-level array (from `filament_colour`), Kotlin emits the `detectedColors` array (from the priority-chain parser). These are the same underlying source but are parsed independently and may produce different list sizes or hex formats.
- For **sliced files** (has `slice_filaments_info`), native emits the per-plate slice colour list, Kotlin emits the file-wide `detectedColors`. These are genuinely different: native is per-plate, Kotlin is a file-level union. This is the root cause of the 23 `plates[*].filamentColours.size` disagreements.

The `known-disagreements.json` entries confirm this: all 23 `filamentColours.size` entries are marked `"Kotlin gap - Phase 1 closes by deletion"`. The 3 content diffs on `colored_3DBenchy (1).3mf` are `"intentional - RGB vs RGBA hex"` (the native `colour_to_hex` preserves the raw stored format including 8-char RGBA strings, while Kotlin's regex truncates to 7).

**Sub-plan #5 scope and sub-plan #2 interaction:**

Sub-plan #5 makes the Kotlin path emit `filamentColours` from the project-level `filament_colour` config (via the new JNI accessor) instead of from `detectedColors`. This converges native and Kotlin for **unsliced files**, closing the 23 `.size` disagreements. For **sliced files**, per-plate `slice_filaments_info` data is still needed — that is sub-plan #2's job. Sub-plan #5 should wire the project-level data as the fallback exactly as the native side does: emit project colours per plate, and let sub-plan #2 override each plate's list with its own `slice_filaments_info` in the next sub-plan.

The 3 RGBA content diffs on `colored_3DBenchy` survive sub-plan #5 unless the Kotlin consumer also strips the alpha byte; that normalisation is tracked in the same `known-disagreements.json` entry already and should be addressed together with the colour list swap.

**Smallest coherent scope for sub-plan #5:**

1. Add `nativeGetProjectConfig()` returning JSON string (or granular accessors — see Section 4).
2. In `KotlinBambuSnapshot.snapshot()`: replace `fileVersion = ""` with `projectConfig.fileVersion` and `isBbl = info.isBambu` with `projectConfig.isBbl`.
3. Replace `filamentColours = info.detectedColors.toList()` with `filamentColours = projectConfig.filamentColours` for each plate (uniform project-level list; sub-plan #2 will later override with per-plate data).
4. Populate `filamentSettingsIds = projectConfig.filamentSettingsIds` (currently `emptyList()`).

This closes 20 (`fileVersion`) + 23 (`filamentColours.size`) = **43 baseline diff entries** directly, and unblocks the remaining 3 RGBA content diffs for normalisation. The roadmap figure of "46 entries" is exactly 20 + 26 (which includes the 3 content diffs); those 3 become closeable once the colour list is sourced from native.

---

## 4. Option shape for the JNI accessor

### Option A — single JSON blob: `nativeGetProjectConfig(): String?`

```kotlin
external fun nativeGetProjectConfig(): String?
// Returns: {"isBbl":bool,"fileVersion":"...","filamentColours":[...],"filamentSettingsIds":[...],"filamentIds":[...]}
// Returns null if model not loaded.
```

C++: ~20 lines, parallel to how `bambu_snapshot_json()` opens with the same five variables. Kotlin side: one JSON parse call via `JSONObject(json)`, then extract each field.

**Pros:** single JNI round-trip; matches roadmap proposal; Kotlin already uses `org.json` extensively (no new dep); entire project config is atomic (the five fields are always consistent because they come from the same `getModelConfig()` call); easy to extend with new fields.
**Cons:** one extra `JSONObject` allocation per snapshot call.

### Option B — granular accessors (five separate `external fun` declarations)

```kotlin
external fun nativeGetProjectIsBbl(): Boolean
external fun nativeGetProjectFileVersion(): String
external fun nativeGetProjectFilamentColours(): Array<String>?
external fun nativeGetProjectFilamentSettingsIds(): Array<String>?
external fun nativeGetProjectFilamentIds(): Array<String>?
```

Sub-plan #1 chose this style for the volumes path (`nativeGetObjectCount`, `nativeGetVolumeCount`, etc.).

**Pros:** matches sub-plan #1's established style; each accessor is independently null-safe.
**Cons:** five JNI symbols, five C++ stubs, more boilerplate for what is logically one data structure; for string-array returns on Android JNI the marshalling is wordier than a JSON string; the granular style was motivated in sub-plan #1 by the need to iterate over a variable-length list of objects/volumes — that driver is absent here (project config is a fixed record). Five JNI calls where one will do is harder to reason about atomicity (in theory a concurrent `loadModel` between calls could mix versions — in practice the Kotlin `previewMutex` prevents this, but the single-call form makes the invariant self-evident).

### Option C — reuse `nativeDumpBambuModel()` and extract fields client-side

Parse the full snapshot JSON returned by `nativeDumpBambuModel(path)` in `KotlinBambuSnapshot` and pull out `isBbl`, `fileVersion`, `plates[*].filamentColours[*]` from the existing JSON.

**Pros:** zero new JNI symbols; composes with existing infrastructure.
**Cons:** `nativeDumpBambuModel` does a fresh `loadModel` internally (line 146 of `slicer_wrapper.cpp`), so calling it from `KotlinBambuSnapshot.snapshot()` which has already called `native.loadModel()` causes a duplicate load and double latency; also the full blob is large and parsing it to discard everything except five fields is wasteful; tightly couples sub-plan #5 to sub-plan #2's JSON shape. Not viable.

**Recommendation: Option A (single JSON blob).**

The five fields are a fixed record sourced from a single `getModelConfig()` call, which makes a single JSON blob semantically cleaner than five granular accessors. Option B's style advantage from sub-plan #1 does not apply here: sub-plan #1 needed variable-length iteration (one accessor per step of the `objects × volumes` loop), whereas here the caller extracts a fixed 5-field struct. The JSON approach is already proven (the existing `nativeDumpBambuModel` flow), avoids five separate boilerplate stubs, and the `org.json` allocation cost at snapshot time is negligible. Name it `nativeGetProjectConfig()` to parallel sub-plan #2's forthcoming `nativeGetPlateConfig()`.

---

## 5. Risks + open questions

### `isBambu` fast-path latency

The current Kotlin `ThreeMfParser.isBambu` is a O(N) zip-entry name scan — typically microseconds. Any production caller that exercises `isBambu` on a cold file should not route through a JNI `loadModel()`. The following production sites call `ThreeMfParser.parse(...).isBambu` (directly or via `origInfo.isBambu`):

- `BambuSanitizer.kt:87` — **hot path** (called in slice prep)
- `SlicerViewModel.kt:1657, 1664, 1727, 1730` — **hot path** (slice orchestration)
- `SlicerViewModel.kt:3585` — **hot path** (preview file resolution)

None of these go through `KotlinBambuSnapshot`. The new JNI accessor is only called from `KotlinBambuSnapshot.snapshot()`, which already holds `previewMutex` and has already paid the `loadModel()` cost. **Production callers of `isBambu` must remain on the Kotlin fast path.** The `BambuFileSnapshot.isBbl` field populated by sub-plan #5 is for the diff harness only; no production code should be redirected to it.

### `fileVersion` format normalization

`Semver::to_string()` (Semver.hpp lines 153-163) uses BambuStudio's custom four-part format:

```
"major.minor.(patch/100).(patch%100)"
// e.g. patch=152 → "01.09.01.52"
```

This differs from standard semver (`"1.9.1"`) and from `to_string_sf()` which uses three parts. The Kotlin side currently emits `""` unconditionally, so any non-empty string from native is already a win. The risk is not divergence today but future proofing: if Kotlin ever attempts to populate `fileVersion` by parsing the XML `<metadata name="BambuStudio:3mfVersion">` entry, it will see a value like `"01.09.01.52"` which `Semver::to_string()` also produces (assuming the stored string parses back correctly via `Semver(str)`). The diff harness will agree as long as both sides use `to_string()` or both use the raw string. Recommendation: always take the value verbatim from the native side and never attempt to re-parse it in Kotlin — treat it as an opaque string for display and harness comparison only.

### Colour hex format — case and RGBA stripping

`colour_to_hex()` (sapil_bambu_snapshot.cpp lines 81-85) only prefixes `#` if missing; it does not normalize case. The BBS 3MF importer stores colours in whatever case the XML attribute carries — typically uppercase (`#FFAABB`). Kotlin's `detectColorsFromJsonSettings` uses regex `.take(7)` which truncates RGBA but does not change case. Neither side applies `uppercase()` or `lowercase()`. No `.toUpperCase()` / `.lowercase()` calls appear anywhere in `app/src/main/java/com/u1/slicer/bambu/`. **There is no case normalization on either side.** If a file stores colours in mixed case, both sides will emit identically un-normalized strings, so the diff harness will agree. This is safe.

The 3 content diffs on `colored_3DBenchy (1).3mf` are RGBA vs RGB: native emits `#RRGGBBAA` (8 chars, raw from `slice_filaments_info[j].color`), Kotlin emits `#RRGGBB` (7 chars, truncated by `.take(7)`). When sub-plan #5 switches the Kotlin plate colour source to the native JSON (which also goes through `colour_to_hex` → returns the raw value), these 3 diffs survive if the project-level `filament_colour` entries are also 8-char. The sub-plan #5 implementer should confirm whether the project-level config stores 6-char or 8-char values for this fixture (expected: 6-char in project config, 8-char only in `slice_filaments_info`). If confirmed, the 3 benchy content diffs belong to sub-plan #2 (per-plate `slice_filaments_info`), not sub-plan #5.

### `filamentSettingsIds` ordering

Confirmed cascade (lines 139-154): `slice_filaments_info[j].filament_id` (plate-level) preferred; when empty, `filament_settings_id` beats `filament_ids`. Sub-plan #5 exposes the project-level arrays in the JSON blob. The Kotlin side should replicate the same fallback logic: use `filamentSettingsIds` from the JSON (which already reflects the correct `filament_settings_id > filament_ids` preference), and later sub-plan #2 will override with per-plate data. No additional ordering logic is needed in Kotlin — the C++ `nativeGetProjectConfig` function can do the selection itself before emitting, or the Kotlin caller can pick `filamentSettingsIds` directly (the blob already encodes the winner).

### Shared-load ObjectID concern from sub-plan #1

Sub-plan #1 encountered ObjectID drift when two consecutive `loadModel` calls rotated the IDs, requiring careful stable-ID mapping. **This concern does not apply here.** `isBbl`, `fileVersion`, `filament_colour`, `filament_settings_id`, and `filament_ids` are all file-level constants written once by the BBS 3MF importer into `g_is_bbl`, `g_file_version`, and `g_model_config` — they are invariant across any number of `setModelRotation` or `setModelInstances` calls, and are reset only at the top of each `loadModel`. `KotlinBambuSnapshot.snapshot()` already holds `previewMutex` and calls `loadModel` once at the start of `readVolumesViaNative`; `nativeGetProjectConfig()` is called after that `loadModel` and before any other mutation, so there is no ID drift vector.

### Interaction with sub-plan #2

Sub-plan #2 replaces `BambuSanitizer.extractPlate` entirely and will drive `PlateSnapshot.filamentColours` from per-plate `slice_filaments_info`. Sub-plan #5 should wire `filamentColours` from the project-level JSON as a fallback (matching native behaviour for unsliced files) and leave a clear comment marking the field as "per-plate override pending sub-plan #2". This way sub-plan #2 can simply replace the `filamentColours = projectConfig.filamentColours` assignment with a per-plate lookup without any interaction. Sub-plan #5 does not touch `BambuSanitizer`; sub-plan #2 does not need to touch `fileVersion` or `isBbl`; the two sub-plans are fully orthogonal.

The smallest sensible scope for sub-plan #5 that leaves sub-plan #2 unimpeded: **only change `KotlinBambuSnapshot.kt` and add one C++ function + one JNI stub**. Do not modify `ThreeMfParser`, `BambuSanitizer`, `SlicerViewModel`, or any production code path.

---

## TL;DR for the next-session implementer

1. Add `nativeGetProjectConfig(): String?` to `NativeLibrary.kt` + a corresponding `Java_com_u1_slicer_NativeLibrary_nativeGetProjectConfig` in a new or existing JNI TU. The C++ body is ~20 lines: extract `g_is_bbl`, `g_file_version`, and the three `ConfigOptionStrings` from `getModelConfig()` — verbatim the same five lines already at `sapil_bambu_snapshot.cpp:308-326`. All required externs are already declared in `sapil_bambu_snapshot.cpp`; duplicate them in the new TU or extract to a shared header.
2. In `KotlinBambuSnapshot.snapshot()` (currently ~75 lines): call `nativeGetProjectConfig()` after `readVolumesViaNative` (still inside the same `previewMutex` scope — no second `loadModel` needed), parse the JSON, then:
   - Replace `isBbl = info.isBambu` with `isBbl = projectConfig.isBbl`
   - Replace `fileVersion = ""` with `fileVersion = projectConfig.fileVersion`
   - Replace `filamentColours = info.detectedColors.toList()` with `filamentColours = projectConfig.filamentColours` (per plate, uniform project list)
   - Populate `filamentSettingsIds = projectConfig.filamentSettingsIds` (currently `emptyList()`)
3. Do **not** route any production `isBambu` usage through the new accessor — those must remain on the Kotlin fast-path `ThreeMfParser.parse(...).isBambu`.
4. Expected baseline delta: **43 entries closed** (20 `fileVersion` + 23 `filamentColours.size`); 3 RGBA content diffs on `colored_3DBenchy` remain intentional until sub-plan #2 handles per-plate colour normalisation. Rebuild native `.so` following the NDK 26 / Release checklist in `CLAUDE.md`.
