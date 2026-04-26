# Phase 1 Sub-Plan #2 — Per-plate `PlateData` (snapshot-only) via JNI

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close ~115 baseline entries (`plates[*].plateIndex`, `plates[*].objectInstanceMap`, `plates[*].filamentSettingsIds.size`, `plates[*].plateConfig`, `plates.size`) by sourcing `KotlinBambuSnapshot.plates` from two new JNI accessors backed by Phase 0's existing `append_plate` JSON emitter. **Snapshot-only — the slice-time `BambuSanitizer.extractPlate` path is deliberately untouched** (deferred to a later `#2b`).

**Architecture:** One new C++ TU `sapil_bambu_plate.cpp` owns `Java_com_u1_slicer_NativeLibrary_nativeGetPlateData` (returns the Phase 0 `append_plate` JSON for a given plate index) and `Java_com_u1_slicer_NativeLibrary_nativeGetPlateCount`. Both reads of `sapil::g_plate_data_list` + `sapil::getModelConfig()`. `append_plate` is promoted from `sapil_bambu_snapshot.cpp`'s anonymous namespace into `namespace sapil` (declared in `sapil_bambu_snapshot.h`) so the new TU can reuse it without duplication — following exactly the pattern sub-plan #5 used for `json_escape`/`colour_to_hex`. `KotlinBambuSnapshot.snapshot` consumes the plate JSON inside the existing `previewMutex + loadModel` scope and fills `plateIndex`, `objectInstanceMap`, `filamentSettingsIds`, `filamentColours`, `plateConfig` directly from the native shape. No production code (BambuSanitizer / ThreeMfParser / SlicerViewModel) changes.

**Tech Stack:** Kotlin 1.9.22, `org.json.JSONObject` (Android API), Android NDK 26 / Clang 17, CMake + Ninja (`-j1`), JUnit4 + AndroidJUnit4, OrcaSlicer 2.2.4 libslic3r (`Model`, `PlateData`, `ConfigOptionStrings`), kotlinx-coroutines `Mutex`.

---

## Operating rules (non-negotiable — see `feedback-bambu-refactor-gotchas.md`)

- **Worktree:** `c:/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native/`. Every Bash call starts at the MAIN repo CWD. Prefix each command with `WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ...`.
- **DEX:** androidTest methods must use `snake_case_names()` or method names without spaces. No backticked-with-spaces. JVM unit tests may use backticked names.
- **androidTest fixtures:** `InstrumentationRegistry.getInstrumentation().context.assets.open(name)` (test APK); copy to `targetContext.cacheDir`.
- **Device:** Pixel 8a `43211JEKB16931` — phantom `versionCode 257`. Use manual adb uninstall + install cycle before instrumentation.
- **`extern/` line-ending dirtying:** `git restore -- app/src/main/cpp/extern/` BEFORE any `git add` after a native build.
- **Native rebuild:** NDK 26 / Clang 17 / Release / ~20MB stripped. Incremental build completes in 2-15 min foreground at `-j1`. Verify `llvm-readelf -p .comment` shows `clang version 17.0.2`; size 19-21MB; `--dyn-syms` lists the new symbols.
- **Mutex discipline:** accessors are pure reads of `g_plate_data_list` — no internal locking. `KotlinBambuSnapshot` holds `previewMutex` around `loadModel` + the full sequence of accessor calls.

---

## File structure

**New files:**

| Path | Responsibility |
|---|---|
| `app/src/main/cpp/src/sapil_bambu_plate.cpp` | Two JNI entry points: `nativeGetPlateCount()` and `nativeGetPlateData(plateIndex)`. Pure reads of `g_plate_data_list` + `getModelConfig()`; calls `sapil::append_plate` to build the JSON payload. |
| `app/src/androidTest/java/com/u1/slicer/native/NativePlateDataTest.kt` | Instrumented smoke tests on Flarewing-Dragon, colored_3DBenchy, Dragon Scale. |

**Modified files:**

| Path | Change |
|---|---|
| `app/src/main/cpp/src/sapil_bambu_snapshot.h` | Declare `sapil::append_plate` so `sapil_bambu_plate.cpp` can reuse it. |
| `app/src/main/cpp/src/sapil_bambu_snapshot.cpp` | Move `append_plate` from the anonymous namespace into `namespace sapil` (unchanged body). |
| `app/src/main/cpp/CMakeLists.txt` | Add `src/sapil_bambu_plate.cpp`. |
| `app/src/main/java/com/u1/slicer/NativeLibrary.kt` | Declare `external fun nativeGetPlateCount(): Int` and `external fun nativeGetPlateData(plateIndex: Int): String?`. |
| `app/src/main/java/com/u1/slicer/bambu/snapshot/KotlinBambuSnapshot.kt` | Call the new accessors inside `readNativeData`; build `PlateSnapshot`s from native JSON (replaces the current `info.plates.map { ... }` loop). |
| `app/src/androidTest/java/com/u1/slicer/bambu/snapshot/KotlinBambuSnapshotTest.kt` | Update the `colored_3DBenchy` assertions for post-#2 values: `plateIndex=0` (was 1), `objectInstanceMap` still {objectId=2/4, instanceId=0} if native agrees — pin by asserting size + structure. |
| `app/src/androidTest/assets/diagnostics/known-disagreements.json` | Remove the 115 stale entries (driven by diff-suite stale report). |
| `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so` | Stripped Release rebuild. |
| `CLAUDE.md` | Bump instrumented test count; add `NativePlateDataTest.kt` description line. |
| `docs/superpowers/plans/2026-04-23-phase1-kickoff-handoff.md` | Append "Sub-plan #2 LANDED" section. |

**Deliberately NOT touched:**

- `BambuSanitizer.kt`, `ThreeMfParser.kt`, `SlicerViewModel.kt`, `ProfileEmbedder.kt` — slice-time migration deferred. Closure of the 115 entries is done purely by changing the snapshot source.
- 20 `objects.size` entries — sub-plan #4.
- 11 `customGcode[*]` entries — sub-plan #3.
- 3 or 4 residual filamentColours content diffs — fall out naturally when per-plate values come from `slice_filaments_info` via `append_plate` (may close with sub-plan #2 too; inspect stale report).

---

## Accessor design

```kotlin
// NativeLibrary.kt additions (after nativeGetProjectConfig):

/**
 * Number of plates in g_plate_data_list. Returns 0 when no model is loaded.
 * Callers MUST hold [previewMutex] for any loadModel + accessor sequence.
 */
external fun nativeGetPlateCount(): Int

/**
 * Returns the Phase 0 `append_plate` JSON for the plate at the given 0-based
 * index — a JSON object with:
 *   {
 *     "plateIndex":          int,            // p.plate_index (0-based)
 *     "filamentColours":     ["#RRGGBB", ...],
 *     "filamentSettingsIds": ["...", ...],
 *     "objectInstanceMap":   [{"objectId":..., "instanceId":...}, ...],
 *     "customGcode":         [{"printZ":..., "type":"...", "extruder":..., "color":"..."}, ...],
 *     "plateConfig":         {"key":"value", ...}       // opt_serialize'd DynamicConfig
 *   }
 *
 * Returns null when:
 *   - no model is loaded, or
 *   - plateIndex is out of range, or
 *   - the plate pointer in g_plate_data_list is null.
 */
external fun nativeGetPlateData(plateIndex: Int): String?
```

C++ (`sapil_bambu_plate.cpp`):

```cpp
// sapil_bambu_plate.cpp
//
// Phase 1 sub-plan #2: per-plate PlateData JNI accessors. Pure reads of
// g_plate_data_list and getModelConfig()'s project-level palette fallback
// (so `append_plate`'s cascade matches Phase 0). Callers hold
// NativeLibrary.previewMutex on the Kotlin side.

#include <jni.h>

#include <sstream>
#include <string>

#include "libslic3r/Config.hpp"
#include "libslic3r/Format/bbs_3mf.hpp"  // PlateDataPtrs
#include "libslic3r/Model.hpp"

#include "sapil_bambu_snapshot.h"  // sapil::append_plate

namespace sapil {
extern Slic3r::Model g_model;
extern Slic3r::PlateDataPtrs g_plate_data_list;
extern Slic3r::DynamicPrintConfig& getModelConfig();
} // namespace sapil

extern "C" {

JNIEXPORT jint JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetPlateCount(JNIEnv*, jobject) {
    return static_cast<jint>(sapil::g_plate_data_list.size());
}

JNIEXPORT jstring JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetPlateData(
        JNIEnv* env, jobject, jint plateIndex) {
    if (plateIndex < 0) return nullptr;
    if (sapil::g_model.objects.empty()) return nullptr;
    const auto& plates = sapil::g_plate_data_list;
    // plate_index is 0-based inside g_plate_data_list after BBS importer normalisation
    // (bbs_3mf.cpp ~line 1485: plate->plate_index = raw-1). We match callers by
    // positional index into the vector — this also matches Phase 0's
    // bambu_snapshot_json loop over `g_plate_data_list[i]`.
    if (static_cast<size_t>(plateIndex) >= plates.size()) return nullptr;
    const Slic3r::PlateData* p = plates[plateIndex];
    if (p == nullptr) return nullptr;

    const auto& cfg = sapil::getModelConfig();
    const auto* colours = cfg.opt<Slic3r::ConfigOptionStrings>("filament_colour");
    const auto* settings_ids = cfg.opt<Slic3r::ConfigOptionStrings>("filament_settings_id");
    const auto* filament_ids = cfg.opt<Slic3r::ConfigOptionStrings>("filament_ids");

    std::ostringstream out;
    sapil::append_plate(out, *p, colours, filament_ids, settings_ids);
    return env->NewStringUTF(out.str().c_str());
}

} // extern "C"
```

---

## Tasks

### Task 1: Promote `append_plate` to `sapil::append_plate`

**Files:**
- Modify: `app/src/main/cpp/src/sapil_bambu_snapshot.h`
- Modify: `app/src/main/cpp/src/sapil_bambu_snapshot.cpp`

- [ ] **Step 1: Declare `sapil::append_plate` in the header**

Open `app/src/main/cpp/src/sapil_bambu_snapshot.h`. Under the existing `sapil::json_escape` / `sapil::colour_to_hex` declarations, add the required forward-declares + function signature. You must forward-declare the libslic3r types the header touches so callers needn't transitively include `bbs_3mf.hpp`:

```cpp
#include <sstream>

namespace Slic3r {
    class ModelVolume;
    class FacetsAnnotation;
    class ConfigOptionStrings;
    struct PlateData;
}

// existing sapil:: declarations for count_paint_states, json_escape, colour_to_hex ...

/**
 * Emit the Phase 0 `append_plate` JSON body (braces included) for one
 * PlateData. Project-level palette pointers are injected so `filamentColours`
 * and `filamentSettingsIds` cascade exactly as in bambu_snapshot_json.
 *
 * Shared with Phase 1 sub-plan #2 JNI accessor (sapil_bambu_plate.cpp).
 */
void append_plate(std::ostringstream& out,
                  const Slic3r::PlateData& p,
                  const Slic3r::ConfigOptionStrings* project_colours,
                  const Slic3r::ConfigOptionStrings* project_filament_ids,
                  const Slic3r::ConfigOptionStrings* project_filament_settings_id);
```

- [ ] **Step 2: Move `append_plate` out of the anonymous namespace**

In `app/src/main/cpp/src/sapil_bambu_snapshot.cpp`, find `void append_plate(...)` (currently around line 114, inside the anonymous namespace). Cut the function (opening `void append_plate(...)` through matching closing `}`) and paste it above the `namespace {` opening, directly after `colour_to_hex`. Do not modify the body.

After: the `namespace sapil { ... }` outer block contains `json_escape`, `colour_to_hex`, and `append_plate` before the inner `namespace { ... }` that still holds `custom_gcode_type_name`, `append_object`, `append_volume`, and the `count_paint_states` placeholder.

Callers in the anonymous namespace (`bambu_snapshot_json`) call unqualified `append_plate` — still resolves via lookup.

- [ ] **Step 3: Commit**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  git restore -- app/src/main/cpp/extern/ && \
  git add app/src/main/cpp/src/sapil_bambu_snapshot.h app/src/main/cpp/src/sapil_bambu_snapshot.cpp && \
  git commit -m "phase1(bambu-native): promote append_plate to sapil namespace

Pre-extraction for sub-plan #2 (per-plate PlateData accessor). Moves
append_plate out of sapil_bambu_snapshot.cpp's anonymous namespace into
namespace sapil so a new sapil_bambu_plate.cpp translation unit can
reuse it without duplication. Body unchanged; the caller
bambu_snapshot_json still resolves append_plate via unqualified lookup
inside the anonymous namespace.

No behavioural change — refactor only, pending sub-plan #2's native
rebuild."
```

---

### Task 2: Create `sapil_bambu_plate.cpp` + wire CMake

**Files:**
- Create: `app/src/main/cpp/src/sapil_bambu_plate.cpp`
- Modify: `app/src/main/cpp/CMakeLists.txt`

- [ ] **Step 1: Write the new TU**

Create `app/src/main/cpp/src/sapil_bambu_plate.cpp` with exactly the body shown in the "Accessor design" section above.

- [ ] **Step 2: Add to CMakeLists.txt**

Open `app/src/main/cpp/CMakeLists.txt`. Find the line adding `src/sapil_bambu_project.cpp` (added by sub-plan #5). Append `src/sapil_bambu_plate.cpp` after it:

```cmake
    src/sapil_bambu_volumes.cpp
    src/sapil_bambu_project.cpp
    src/sapil_bambu_plate.cpp
)
```

- [ ] **Step 3: Commit (build deferred to Task 5)**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  git restore -- app/src/main/cpp/extern/ && \
  git add app/src/main/cpp/src/sapil_bambu_plate.cpp app/src/main/cpp/CMakeLists.txt && \
  git commit -m "phase1(bambu-native): add nativeGetPlateCount + nativeGetPlateData JNI (sub-plan #2)

New TU sapil_bambu_plate.cpp reuses sapil::append_plate (promoted in the
prior commit) to emit the Phase 0 per-plate JSON payload. Two JNI entry
points: nativeGetPlateCount returns g_plate_data_list.size(),
nativeGetPlateData(plateIndex) returns the append_plate JSON or null for
out-of-range / no-model / null-slot.

Native .so rebuild pending."
```

---

### Task 3: Declare the `external fun`s in `NativeLibrary.kt`

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/NativeLibrary.kt`

- [ ] **Step 1: Add the two declarations after `nativeGetProjectConfig`**

```kotlin
    /**
     * Number of plates in g_plate_data_list. Returns 0 when no model is loaded.
     * Callers MUST hold [previewMutex] for any loadModel + accessor sequence.
     */
    external fun nativeGetPlateCount(): Int

    /**
     * Returns the Phase 0 append_plate JSON for the plate at the given 0-based
     * index. Fields: plateIndex, filamentColours, filamentSettingsIds,
     * objectInstanceMap [{objectId,instanceId}], customGcode, plateConfig.
     *
     * Returns null when no model is loaded, plateIndex is out of range, or
     * the plate slot is null. Callers MUST hold [previewMutex].
     */
    external fun nativeGetPlateData(plateIndex: Int): String?
```

- [ ] **Step 2: Commit**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  git restore -- app/src/main/cpp/extern/ && \
  git add app/src/main/java/com/u1/slicer/NativeLibrary.kt && \
  git commit -m "phase1(bambu-native): declare nativeGetPlateCount + nativeGetPlateData (sub-plan #2)

Kotlin bindings for the new JNI entries added in the prior commit. No
caller yet — wired into KotlinBambuSnapshot in the next commit after
native rebuild."
```

---

### Task 4: Wire `KotlinBambuSnapshot` to the new accessors

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/bambu/snapshot/KotlinBambuSnapshot.kt`

- [ ] **Step 1: Extend `NativeData` + parse plate payloads**

In `KotlinBambuSnapshot`, extend the private `NativeData` class and add a `NativePlate` data class representing the parsed JSON:

```kotlin
    private data class NativeData(
        val volumes: List<VolumeSnapshot>,
        val projectConfig: ProjectConfig?,
        val plates: List<NativePlate>?,  // null when loadModel failed
    )

    private data class NativePlate(
        val plateIndex: Int,
        val filamentColours: List<String>,
        val filamentSettingsIds: List<String>,
        val objectInstanceMap: List<ObjectInstance>,
        val customGcode: List<CustomGcodeEntry>,
        val plateConfig: Map<String, String>,
    )
```

- [ ] **Step 2: Populate `plates` inside `readNativeData`**

Extend `readNativeData` after the volumes loop + `projectConfig` read, still inside `previewMutex.withLock`:

```kotlin
        val plateCount = native.nativeGetPlateCount()
        val nativePlates = buildList<NativePlate> {
            for (pi in 0 until plateCount) {
                val json = native.nativeGetPlateData(pi) ?: continue
                parseNativePlate(json)?.let { add(it) }
            }
        }
        NativeData(volumes = volumes, projectConfig = projectConfig, plates = nativePlates)
```

Also in the `!loadModel` branch return `NativeData(emptyList(), null, null)`.

- [ ] **Step 3: Add the JSON → NativePlate parser**

Below `parseProjectConfig`, add:

```kotlin
    private fun parseNativePlate(json: String): NativePlate? {
        return try {
            val obj = JSONObject(json)
            NativePlate(
                plateIndex = obj.optInt("plateIndex", -1),
                filamentColours = readStringArray(obj, "filamentColours"),
                filamentSettingsIds = readStringArray(obj, "filamentSettingsIds"),
                objectInstanceMap = readObjectInstanceMap(obj),
                customGcode = readCustomGcode(obj),
                plateConfig = readPlateConfig(obj),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun readObjectInstanceMap(obj: JSONObject): List<ObjectInstance> {
        val arr = obj.optJSONArray("objectInstanceMap") ?: return emptyList()
        return List(arr.length()) { i ->
            val o = arr.optJSONObject(i)
            ObjectInstance(
                objectId = o?.optInt("objectId", -1) ?: -1,
                instanceId = o?.optInt("instanceId", 0) ?: 0,
            )
        }
    }

    private fun readCustomGcode(obj: JSONObject): List<CustomGcodeEntry> {
        val arr = obj.optJSONArray("customGcode") ?: return emptyList()
        return List(arr.length()) { i ->
            val o = arr.optJSONObject(i)
            CustomGcodeEntry(
                printZ = o?.optDouble("printZ", 0.0) ?: 0.0,
                type = o?.optString("type", "") ?: "",
                extruder = o?.optInt("extruder", 0) ?: 0,
                color = o?.optString("color", "") ?: "",
            )
        }
    }

    private fun readPlateConfig(obj: JSONObject): Map<String, String> {
        val o = obj.optJSONObject("plateConfig") ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            result[k] = o.optString(k, "")
        }
        return result
    }
```

- [ ] **Step 4: Build `PlateSnapshot`s from `NativeData.plates` in `snapshot()`**

Replace the current `plates = info.plates.map { plate -> PlateSnapshot(...) }` block with native-sourced data:

```kotlin
        // Sub-plan #2: plates sourced from native PlateData via nativeGetPlateData.
        // Fallback: when loadModel failed (corrupt file) we retain the Kotlin
        // ThreeMfParser-derived plate list so the snapshot is still populated.
        val plates: List<PlateSnapshot> = nativeData.plates?.map { np ->
            PlateSnapshot(
                plateIndex = np.plateIndex,
                filamentColours = np.filamentColours,
                filamentSettingsIds = np.filamentSettingsIds,
                objectInstanceMap = np.objectInstanceMap,
                customGcode = np.customGcode,
                plateConfig = np.plateConfig,
            )
        } ?: info.plates.map { plate ->
            // Fallback path only — loadModel returned false.
            PlateSnapshot(
                plateIndex = plate.plateId,
                filamentColours = nativeData.projectConfig?.filamentColours.orEmpty(),
                filamentSettingsIds = nativeData.projectConfig?.filamentSettingsIds.orEmpty(),
                objectInstanceMap = plate.objectIds
                    .map { ObjectInstance(objectId = parseObjectId(it), instanceId = 0) },
                customGcode = customGcodeByPlate[plate.plateId].orEmpty(),
                plateConfig = emptyMap(),
            )
        }
```

Remove the separate `plateFilamentColours` / `plateFilamentSettingsIds` lookups that sub-plan #5 introduced — they are redundant now. The `customGcodeByPlate` + `parseObjectId` helpers stay for the fallback branch only.

- [ ] **Step 5: Verify compile**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  ./gradlew :app:compileDebugKotlin --no-daemon 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  git restore -- app/src/main/cpp/extern/ && \
  git add app/src/main/java/com/u1/slicer/bambu/snapshot/KotlinBambuSnapshot.kt && \
  git commit -m "phase1(bambu-native): source plates list from JNI (sub-plan #2)

KotlinBambuSnapshot.snapshot now builds PlateSnapshot list from the new
nativeGetPlateCount + nativeGetPlateData accessors, inside the existing
previewMutex + loadModel scope. Fields previously fabricated or left
empty by the Kotlin path are now native-sourced:

  - plateIndex      = p.plate_index (0-based; Kotlin was 1-based)
  - objectInstanceMap = PlateData::objects_and_instances (pairs with real
    instanceId; Kotlin fabricated 0)
  - filamentSettingsIds = PlateData::slice_filaments_info.filament_id or
    project filament_settings_id (Kotlin was empty)
  - filamentColours = PlateData::slice_filaments_info.color or project
    filament_colour (Kotlin was uniform project palette)
  - customGcode     = g_model.plates_custom_gcodes (keyed by plate_index)
  - plateConfig     = PlateData::config.opt_serialize() per key

The ThreeMfParser-sourced fallback path remains in place for corrupt
files where loadModel returns false.

Production slice-time consumers (BambuSanitizer.extractPlate,
ThreeMfParser.parseForPlateSelection, SlicerViewModel.mergeThreeMfInfo*)
are deliberately untouched — slice-time migration is scope #2b.

Native .so rebuild pending."
```

---

### Task 5: Rebuild the native `.so`

**Files:**
- Modify (generated): `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so`

- [ ] **Step 1: Build (foreground, `-j1`)**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  C:/Users/kevin/AppData/Local/Android/Sdk/cmake/3.22.1/bin/ninja.exe \
    -C app/.cxx/Debug/ndk26release/arm64-v8a -j1 prusaslicer-jni 2>&1 | tail -30
```

Expected: ends with `Linking CXX shared library .../libprusaslicer-jni.so`. OOM → re-run. Symbol not found → re-check Task 1 promotion.

- [ ] **Step 2: Strip + verify**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  STRIP=$(ls C:/Users/kevin/AppData/Local/Android/Sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/*/bin/llvm-strip | head -1) && \
  READELF=$(ls C:/Users/kevin/AppData/Local/Android/Sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/*/bin/llvm-readelf | head -1) && \
  SO=app/.cxx/Debug/ndk26release/arm64-v8a/libprusaslicer-jni.so && \
  "$STRIP" --strip-unneeded "$SO" && \
  ls -l "$SO" && \
  "$READELF" -p .comment "$SO" | grep -i clang | head -1 && \
  "$READELF" --dyn-syms "$SO" | grep -c nativeGetPlateData
```

Expected: ~19-21 MB, `clang version 17.0.2`, symbol grep ≥ 1.

- [ ] **Step 3: Install into jniLibs + commit**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  cp app/.cxx/Debug/ndk26release/arm64-v8a/libprusaslicer-jni.so \
     app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so && \
  git restore -- app/src/main/cpp/extern/ && \
  git add app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so && \
  git commit -m "phase1(bambu-native): rebuild .so with per-plate accessors (sub-plan #2)

NDK 26 / Clang 17 / Release / stripped. Exports
Java_com_u1_slicer_NativeLibrary_nativeGetPlateCount and
Java_com_u1_slicer_NativeLibrary_nativeGetPlateData alongside the
sub-plan #1 and #5 JNI surfaces."
```

---

### Task 6: Update `KotlinBambuSnapshotTest` assertions

**Files:**
- Modify: `app/src/androidTest/java/com/u1/slicer/bambu/snapshot/KotlinBambuSnapshotTest.kt`

- [ ] **Step 1: Capture post-#2 values**

`colored_3DBenchy` values change where native's data differs from Kotlin's parsed data. Expected post-#2:
- `plateIndex`: `0` (was `1` — Kotlin was 1-based, native is 0-based).
- `objectInstanceMap`: likely `[{objectId=2, instanceId=0}, {objectId=4, instanceId=0}]` — native may include non-zero instanceIds if the model has multi-instance, but colored_3DBenchy is single-instance. Assert structure with `.size` + sample.
- `filamentColours`: may still be `[#0086D6FF, #FB0207, #F4EE2AFF, #E2DEDBFF]` if `slice_filaments_info` is empty for this plate, OR may change to a per-plate subset. Inspect the baseline stale report to see what native emitted for this plate in the differential run.
- `filamentSettingsIds`: non-empty.
- `plateConfig`: may or may not be empty — colored_3DBenchy likely has no per-plate overrides; expect empty.

Replace the prior assertions carefully. The easiest approach: loosen to structural + positive assertions, document why.

```kotlin
        // Sub-plan #2: plateIndex flipped from 1-based (Kotlin) to 0-based (native).
        assertEquals(0, plate.plateIndex)

        // Sub-plan #2: filamentColours still sourced from project config for this
        // fixture (slice_filaments_info may or may not override; snapshot harness
        // covers the exact values). Keep the structural check.
        assertTrue(
            "expected non-empty filamentColours, got ${plate.filamentColours}",
            plate.filamentColours.isNotEmpty()
        )

        // Sub-plan #2: filamentSettingsIds non-empty.
        assertTrue(
            "expected filamentSettingsIds non-empty post sub-plan #2, got ${plate.filamentSettingsIds}",
            plate.filamentSettingsIds.isNotEmpty()
        )

        // Sub-plan #2: objectInstanceMap now sourced from PlateData::objects_and_instances.
        // colored_3DBenchy is a dual-colour single-instance fixture — expect 2 entries,
        // each with instanceId in 0..1 (Slic3r's instance IDs are small non-negative ints).
        assertEquals(2, plate.objectInstanceMap.size)
        for (oi in plate.objectInstanceMap) {
            assertTrue("objectId should be positive, got ${oi.objectId}", oi.objectId > 0)
            assertTrue("instanceId should be non-negative, got ${oi.instanceId}", oi.instanceId >= 0)
        }

        // Sub-plan #2: customGcode remains empty for this single-plate fixture (no
        // custom_gcode_per_layer.xml entries).
        assertEquals(emptyList<CustomGcodeEntry>(), plate.customGcode)

        // plateConfig: colored_3DBenchy has no per-plate overrides — expect empty.
        // If this fails with non-empty content, add the expected keys to the diff
        // harness baseline rather than loosen here.
        assertEquals(emptyMap<String, String>(), plate.plateConfig)
```

Delete the old `plateIndex = 1`, old `objectInstanceMap = listOf(...)`, old `filamentColours = listOf(...)` assertions. Keep the test otherwise.

- [ ] **Step 2: Assemble + install + run**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  adb -s 43211JEKB16931 uninstall com.u1.slicer.orca; \
  adb -s 43211JEKB16931 uninstall com.u1.slicer.orca.test; \
  ./gradlew assembleDebug assembleDebugAndroidTest --no-daemon 2>&1 | tail -4 && \
  adb -s 43211JEKB16931 install -r -d app/build/outputs/apk/debug/app-debug.apk && \
  adb -s 43211JEKB16931 install -r -d app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk && \
  adb -s 43211JEKB16931 shell am instrument -w -r \
    -e class com.u1.slicer.bambu.snapshot.KotlinBambuSnapshotTest \
    com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tail -15
```

Expected: `OK (1 test)`. If fails on `plateIndex=0` assertion, confirm native is 0-based by reading `sapil_bambu_snapshot.cpp:123` comment.

- [ ] **Step 3: Commit**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  git restore -- app/src/main/cpp/extern/ && \
  git add app/src/androidTest/java/com/u1/slicer/bambu/snapshot/KotlinBambuSnapshotTest.kt && \
  git commit -m "phase1(bambu-native): update KotlinBambuSnapshotTest for sub-plan #2 plate data

plateIndex flipped 1 → 0 (0-based native convention); objectInstanceMap
and filamentSettingsIds now asserted for size + structure rather than
hard-coded values because native may return different per-plate data
than the previous Kotlin fabrication."
```

---

### Task 7: Add `NativePlateDataTest` smoke coverage

**Files:**
- Create: `app/src/androidTest/java/com/u1/slicer/native/NativePlateDataTest.kt`

- [ ] **Step 1: Write the instrumented test**

```kotlin
package com.u1.slicer.native

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 1 sub-plan #2: JNI smoke tests for the per-plate accessors.
 * Loads a few representative Bambu fixtures and asserts that
 * nativeGetPlateCount / nativeGetPlateData return the expected shapes.
 */
@RunWith(AndroidJUnit4::class)
class NativePlateDataTest {

    private lateinit var lib: NativeLibrary

    @Before
    fun setup() {
        assertTrue(NativeLibrary.isLoaded)
        lib = NativeLibrary()
    }

    @After
    fun teardown() {
        lib.clearModel()
    }

    private fun copyFixture(name: String): File {
        val assetContext = InstrumentationRegistry.getInstrumentation().context
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val f = File(targetContext.cacheDir, "plate_fixture_${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
        assetContext.assets.open(name).use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    @Test
    fun native_get_plate_count_returns_zero_when_no_model_loaded() {
        lib.clearModel()
        assertEquals(0, lib.nativeGetPlateCount())
    }

    @Test
    fun native_get_plate_data_returns_null_when_no_model_loaded() {
        lib.clearModel()
        assertNull(lib.nativeGetPlateData(0))
    }

    @Test
    fun native_get_plate_data_returns_single_plate_for_colored_benchy() {
        val fixture = copyFixture("colored_3DBenchy (1).3mf")
        try {
            assertTrue(lib.loadModel(fixture.absolutePath))
            assertEquals(1, lib.nativeGetPlateCount())
            val json = lib.nativeGetPlateData(0)
            assertNotNull("plate 0 JSON should be non-null for colored_3DBenchy", json)
            val obj = JSONObject(json!!)
            assertEquals(0, obj.getInt("plateIndex"))
            // Dual-colour benchy: 2 objects referenced on plate.
            val instances = obj.getJSONArray("objectInstanceMap")
            assertEquals(2, instances.length())
            // Palette must be non-empty (sub-plan #5 proves this already, but include
            // here for sub-plan #2 regression coverage against the promoted emitter).
            assertTrue(obj.getJSONArray("filamentColours").length() > 0)
        } finally {
            fixture.delete()
        }
    }

    @Test
    fun native_get_plate_data_returns_per_plate_entries_for_multi_plate_buzz() {
        val fixture = copyFixture("Buzz_Multipart_3MF_Bambu.3mf")
        try {
            assertTrue(lib.loadModel(fixture.absolutePath))
            val count = lib.nativeGetPlateCount()
            assertTrue("Buzz multi-plate fixture should have > 1 plate, got $count", count > 1)
            for (pi in 0 until count) {
                val json = lib.nativeGetPlateData(pi)
                assertNotNull("plate $pi JSON should be non-null", json)
                val obj = JSONObject(json!!)
                assertEquals("plateIndex should match positional index", pi, obj.getInt("plateIndex"))
            }
            // Out-of-range returns null.
            assertNull(lib.nativeGetPlateData(count))
            assertNull(lib.nativeGetPlateData(-1))
        } finally {
            fixture.delete()
        }
    }

    @Test
    fun native_get_plate_data_emits_custom_gcode_for_layer_tool_fixture() {
        // flippy+flappy+mini-with-plate-painted has plate-level custom_gcode_per_layer entries.
        val fixture = copyFixture("flippy+flappy+mini-with-plate-painted.3mf")
        try {
            assertTrue(lib.loadModel(fixture.absolutePath))
            val count = lib.nativeGetPlateCount()
            assertTrue(count >= 1)
            // At least one plate should emit a non-empty customGcode list.
            var sawCustomGcode = false
            for (pi in 0 until count) {
                val json = lib.nativeGetPlateData(pi) ?: continue
                val obj = JSONObject(json)
                if (obj.getJSONArray("customGcode").length() > 0) {
                    sawCustomGcode = true
                    break
                }
            }
            assertTrue("expected at least one plate with customGcode entries", sawCustomGcode)
        } finally {
            fixture.delete()
        }
    }
}
```

- [ ] **Step 2: Run the five new tests**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  ./gradlew assembleDebugAndroidTest --no-daemon 2>&1 | tail -3 && \
  adb -s 43211JEKB16931 install -r -d app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk && \
  adb -s 43211JEKB16931 shell am instrument -w -r \
    -e class com.u1.slicer.native.NativePlateDataTest \
    com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tail -15
```

Expected: `OK (5 tests)`.

- [ ] **Step 3: Commit**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  git restore -- app/src/main/cpp/extern/ && \
  git add app/src/androidTest/java/com/u1/slicer/native/NativePlateDataTest.kt && \
  git commit -m "phase1(bambu-native): test nativeGetPlateCount + nativeGetPlateData (sub-plan #2)

Five smoke tests on Pixel 8a: no-model-loaded null/zero, colored_3DBenchy
single-plate with 2 object instances, Buzz multi-plate positional index
sanity + out-of-range null, flippy painted fixture customGcode non-empty."
```

---

### Task 8: Prune baseline + rerun differential

**Files:**
- Modify: `app/src/androidTest/assets/diagnostics/known-disagreements.json`

- [ ] **Step 1: Capture the stale report**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  adb -s 43211JEKB16931 shell am instrument -w -r \
    -e class com.u1.slicer.bambu.snapshot.BambuParserDifferentialTest \
    com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | \
    tee /tmp/diff-subplan2.txt | tail -20
```

Expected: many failures with "Baseline has N stale entries" and possibly some "Unexpected diffs" — investigate the latter before proceeding.

- [ ] **Step 2: Parse and prune**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
python3 << 'EOF'
import re, json
# On Windows the bash /tmp path differs — use cygpath or absolute.
try:
    with open('/tmp/diff-subplan2.txt') as f:
        text = f.read()
except FileNotFoundError:
    import os
    with open(os.environ.get('TEMP', r'C:\Users\kevin\AppData\Local\Temp') + r'\diff-subplan2.txt') as f:
        text = f.read()

# First: guard against any unexpected diffs — if present, abort.
unexpected = re.compile(
    r'Unexpected diffs for (.+?) \((\d+)\):\n((?:  \S.+\n(?:    (?:kotlin|native) = .+\n)+)+)',
    re.MULTILINE
)
u_matches = unexpected.findall(text)
if u_matches:
    print("ABORT: unexpected diffs present. Investigate before pruning.")
    for fixture, count, block in u_matches:
        paths = re.findall(r'^  (\S.*?)\n', block, re.MULTILINE)
        print(f"  {fixture}: {count} unexpected")
        for p in paths: print(f"    - {p}")
    raise SystemExit(1)

stale = re.compile(
    r'Baseline has (\d+) stale entries for (.+?) \(listed in known-disagreements\.json but no longer produce a diff\):\n((?:  - .+\n)+)',
    re.MULTILINE
)
seen = {}
for count, fixture, paths_block in stale.findall(text):
    paths = [line.strip('- \n') for line in paths_block.strip().split('\n')]
    seen[fixture] = paths

baseline = 'app/src/androidTest/assets/diagnostics/known-disagreements.json'
with open(baseline) as f:
    data = json.load(f)
fixtures = data['fixtures']
removed = 0
for fixture, stale_paths in seen.items():
    if fixture not in fixtures: continue
    stale_set = set(stale_paths)
    kept = [e for e in fixtures[fixture] if e['path'] not in stale_set]
    removed += len(fixtures[fixture]) - len(kept)
    if kept: fixtures[fixture] = kept
    else: del fixtures[fixture]
data['fixtures'] = fixtures
with open(baseline, 'w') as f:
    json.dump(data, f, indent=2); f.write('\n')
print(f"removed {removed}")
print(f"remaining: {sum(len(v) for v in fixtures.values())} entries across {len(fixtures)} fixtures")
EOF
```

Expected: `removed ~115`, remaining ≈ 35.

- [ ] **Step 3: Rerun differential to confirm green**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  ./gradlew assembleDebugAndroidTest --no-daemon 2>&1 | tail -3 && \
  adb -s 43211JEKB16931 install -r -d app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk && \
  adb -s 43211JEKB16931 shell am instrument -w -r \
    -e class com.u1.slicer.bambu.snapshot.BambuParserDifferentialTest \
    com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tail -6
```

Expected: `OK (21 tests)`.

- [ ] **Step 4: Commit**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  git restore -- app/src/main/cpp/extern/ && \
  git add app/src/androidTest/assets/diagnostics/known-disagreements.json && \
  git commit -m "phase1(bambu-diff): prune ~115 baseline entries closed by sub-plan #2

plates[*] entries closed: plateIndex (54), objectInstanceMap (54),
filamentSettingsIds.size (4), plateConfig (2), plates.size (1) — and any
per-plate filamentColours/content diffs that the native slice_filaments_info
emission fixes.

Remaining baseline: ~35 entries (20 objects.size + 11 customGcode entries
+ ~4 residual diffs). Sub-plans #4 and #3 close the remainder."
```

---

### Task 9: Full regression — unit + Bambu instrumented package

- [ ] **Step 1: JVM unit tests**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  ./gradlew testDebugUnitTest --no-daemon 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Full Bambu instrumented package**

```bash
adb -s 43211JEKB16931 shell am instrument -w -r \
  -e package com.u1.slicer.bambu \
  com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tail -8
```

Expected: `OK` with no failures.

- [ ] **Step 3: Full `NativeLibraryCorrectnessTest` + new `NativePlateDataTest`**

```bash
adb -s 43211JEKB16931 shell am instrument -w -r \
  -e class com.u1.slicer.native.NativeLibraryCorrectnessTest,com.u1.slicer.native.NativePlateDataTest \
  com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tail -6
```

Expected: `OK (17 tests)` (12 correctness + 5 plate data).

---

### Task 10: Update docs + handoff

**Files:**
- Modify: `docs/superpowers/plans/2026-04-23-phase1-kickoff-handoff.md` (append "Sub-plan #2 LANDED" section)
- Modify: `CLAUDE.md` — bump instrumented test count; add `native/NativePlateDataTest.kt` line

- [ ] **Step 1: Append "Sub-plan #2 LANDED" with baseline delta, closure breakdown, tests, deferred follow-ups.**

- [ ] **Step 2: Bump `CLAUDE.md` test count line (`208` → `213`) and add `native/NativePlateDataTest.kt (5)` entry under instrumented tests.**

- [ ] **Step 3: Commit**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  git restore -- app/src/main/cpp/extern/ && \
  git add docs/superpowers/plans/2026-04-23-phase1-kickoff-handoff.md CLAUDE.md && \
  git commit -m "docs(phase1): sub-plan #2 landed — per-plate PlateData via JNI"
```

---

## Exit criteria

- [ ] `BambuParserDifferentialTest` 21/21 green, baseline ≈ 35 entries.
- [ ] `NativePlateDataTest` 5/5 green.
- [ ] `NativeLibraryCorrectnessTest` 12/12 green.
- [ ] `KotlinBambuSnapshotTest` 1/1 green.
- [ ] Bambu instrumented package green.
- [ ] JVM unit tests green.
- [ ] `libprusaslicer-jni.so` ~20 MB, Clang 17, exports `nativeGetPlateCount` + `nativeGetPlateData`.
- [ ] Handoff doc has LANDED appendix; `CLAUDE.md` test counts bumped.
- [ ] No production code (BambuSanitizer / ThreeMfParser / SlicerViewModel / ProfileEmbedder) modified.

## Scope firewall (from overnight runner instructions)

Sub-plan #2 must NOT delete or migrate `BambuSanitizer.extractPlate` in this sub-plan. Slice-time migration is scope #2b — future work, not tonight. If you find yourself editing anywhere outside the files in the "Modified files" table, stop and flag it.
