# Phase 1 Sub-Plan #5 — Project config (fileVersion, isBbl, filamentColours) via JNI

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close 43 baseline entries in `known-disagreements.json` (20 `fileVersion` + 23 `plates[*].filamentColours.size`) by sourcing those three fields in `KotlinBambuSnapshot` from a new single-JSON-blob JNI accessor that reads `g_is_bbl`, `g_file_version`, and project-level `filament_colour` / `filament_settings_id` / `filament_ids` from `g_model`.

**Architecture:** One new C++ TU `sapil_bambu_project.cpp` owns `Java_com_u1_slicer_NativeLibrary_nativeGetProjectConfig` which emits the 5-field JSON blob already computed at `sapil_bambu_snapshot.cpp:308-326`. The existing plate-palette fallback emitter in `append_plate` is unchanged — this sub-plan only adds an independent project-level reader, reusing the established `getModelConfig()` accessor pattern and the existing `colour_to_hex` / `json_escape` helpers (promoted or duplicated). `KotlinBambuSnapshot.snapshot` gains one more read under the existing `previewMutex + loadModel` scope and maps the parsed JSON into `BambuFileSnapshot.isBbl`, `BambuFileSnapshot.fileVersion`, and uniform `PlateSnapshot.filamentColours` / `PlateSnapshot.filamentSettingsIds`. No production code path outside the snapshot is touched.

**Tech Stack:** Kotlin 1.9.22, `org.json.JSONObject` (Android API), Android NDK 26 / Clang 17, CMake + Ninja (`-j1`), JUnit4 + AndroidJUnit4, OrcaSlicer 2.2.4 libslic3r (`Model`, `DynamicPrintConfig`, `ConfigOptionStrings`, `Semver`), kotlinx-coroutines `Mutex`.

---

## Operating rules (non-negotiable — see `feedback-bambu-refactor-gotchas.md`)

- **Worktree:** `c:/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native/`. Every Bash call starts at the MAIN repo CWD. Prefix each command with `WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ...`.
- **DEX:** androidTest methods must use `snake_case_names()`. No backticked spaces. JVM unit tests under `app/src/test/` may use backticked names.
- **androidTest fixtures:** `InstrumentationRegistry.getInstrumentation().context.assets.open(name)` (test APK). Copy to `targetContext.cacheDir` for a seekable file path. See `KotlinBambuSnapshotTest.kt:33-45`.
- **Device:** Pixel 8a `43211JEKB16931` — phantom `versionCode 257`. `./gradlew connectedDebugAndroidTest` fails with `INSTALL_FAILED_VERSION_DOWNGRADE`. Use manual adb:

```bash
adb -s 43211JEKB16931 uninstall com.u1.slicer.orca; adb -s 43211JEKB16931 uninstall com.u1.slicer.orca.test
./gradlew assembleDebug assembleDebugAndroidTest --no-daemon
adb -s 43211JEKB16931 install -r -d app/build/outputs/apk/debug/app-debug.apk
adb -s 43211JEKB16931 install -r -d app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s 43211JEKB16931 shell am instrument -w -r -e class <FQN>#<method> com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

- **`extern/` line-ending dirtying:** After ANY native build, run `git restore -- app/src/main/cpp/extern/` BEFORE `git add`.
- **Native rebuild:** NDK 26 / Clang 17 / Release / ~20MB stripped. Incremental (one new `.cpp`) should take 2-15 min. Use `ninja -j1`. Strip with `llvm-strip --strip-unneeded`. Verify `llvm-readelf -p .comment` shows `clang version 17.0.2`, size 19-21MB.
- **Accessor mutex discipline:** The new JNI accessor is a pure read of `g_model` state and MUST NOT acquire any lock. `KotlinBambuSnapshot.snapshot` holds `previewMutex` around `loadModel` + `readVolumesViaNative` today; the new accessor runs inside the same mutex scope.

---

## File structure

**New files:**

| Path | Responsibility |
|---|---|
| `app/src/main/cpp/src/sapil_bambu_project.cpp` | One JNI entry point returning a single JSON blob with `isBbl`, `fileVersion`, `filamentColours`, `filamentSettingsIds`, `filamentIds`. Pure read of `g_is_bbl`, `g_file_version`, `getModelConfig()`. |

**Modified files:**

| Path | Change |
|---|---|
| `app/src/main/cpp/src/sapil_bambu_snapshot.h` | Declare `sapil::json_escape` and `sapil::colour_to_hex` so `sapil_bambu_project.cpp` can reuse them instead of duplicating. |
| `app/src/main/cpp/src/sapil_bambu_snapshot.cpp` | Move `json_escape` and `colour_to_hex` out of the anonymous namespace into `namespace sapil` (unchanged bodies). |
| `app/src/main/cpp/CMakeLists.txt` | Add `src/sapil_bambu_project.cpp` to the source list next to `src/sapil_bambu_volumes.cpp`. |
| `app/src/main/java/com/u1/slicer/NativeLibrary.kt` | One new `external fun nativeGetProjectConfig(): String?` with KDoc. |
| `app/src/main/java/com/u1/slicer/bambu/snapshot/KotlinBambuSnapshot.kt` | Call `nativeGetProjectConfig()` inside the existing `previewMutex + loadModel` scope; parse the JSON; populate `isBbl`, `fileVersion`, and per-plate `filamentColours` / `filamentSettingsIds` from it. |
| `app/src/androidTest/java/com/u1/slicer/bambu/snapshot/KotlinBambuSnapshotTest.kt` | Update assertions: `fileVersion` now non-empty; `plate.filamentColours` now sourced from project config (same values on colored_3DBenchy — the project palette is the same `[#0086D6, #FB0207, #F4EE2A, #E2DEDB]`); `filamentSettingsIds` non-empty. |
| `app/src/androidTest/java/com/u1/slicer/native/NativeLibraryCorrectnessTest.kt` | Add 2 instrumented smoke tests exercising `nativeGetProjectConfig` on Flarewing-Dragon + empty-model guard. |
| `app/src/androidTest/assets/diagnostics/known-disagreements.json` | Remove the 20 `fileVersion` entries + 23 `plates[*].filamentColours.size` entries (one per fixture where present). |
| `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so` | Stripped Release rebuild output. |
| `CLAUDE.md` | Bump instrumented-test count line if count changes. |
| `docs/superpowers/plans/2026-04-23-phase1-kickoff-handoff.md` | Append one-line "Sub-plan #5 complete — baseline shrunk to X entries". |

**Deliberately NOT touched (out of scope):**

- `ThreeMfParser.kt` — production `detectColors*` paths stay. `detectedColors` feeds hot-path slice prep that this sub-plan must not disturb.
- `BambuSanitizer.kt`, `SlicerViewModel.kt`, `MainActivity.kt` — no production consumer of `isBambu` or `detectedColors` redirects to the new accessor.
- Per-plate `slice_filaments_info` override — sub-plan #2's job. We emit the uniform project-level palette for every plate; sub-plan #2 will overwrite per-plate.
- RGBA vs RGB hex format — 3 baseline entries on `colored_3DBenchy` stay intentional until sub-plan #2.

---

## Accessor design (Option A — single JSON blob)

```kotlin
// NativeLibrary.kt addition, placed after nativeGetPaintStateCounts:

/**
 * Returns a JSON object with five fields read from g_model's project-level
 * config after a successful loadModel:
 *   {
 *     "isBbl":              bool,       // g_is_bbl
 *     "fileVersion":        "x.y.z",    // g_file_version.valid() ? to_string() : ""
 *     "filamentColours":    ["#RRGGBB", ...],   // getModelConfig().filament_colour
 *     "filamentSettingsIds":["Preset name", ...], // filament_settings_id > filament_ids
 *     "filamentIds":        ["GFB98", ...]      // raw filament_ids
 *   }
 *
 * Returns null if no model is loaded. Pure read — callers hold
 * NativeLibrary.previewMutex, same contract as the sub-plan #1 volume accessors.
 *
 * This is a *project-level* read — it does not consult PlateData's
 * slice_filaments_info. The Kotlin snapshot currently reads the same data from
 * the 3MF zip via ThreeMfParser.detectedColors; this accessor lets the snapshot
 * converge with the native path for file-level and unsliced-plate fields.
 */
external fun nativeGetProjectConfig(): String?
```

C++ body (`sapil_bambu_project.cpp`):

```cpp
// sapil_bambu_project.cpp
//
// Phase 1 sub-plan #5: project-level config JNI accessor. Pure read of
// g_is_bbl, g_file_version, and getModelConfig()'s filament_colour /
// filament_settings_id / filament_ids strings. Returns the same JSON shape
// the Kotlin snapshot path will parse. Phase 0 already made g_is_bbl and
// g_file_version externally linkable; getModelConfig() is the established
// public accessor pattern (see sapil_bambu_snapshot.cpp).

#include <jni.h>

#include <sstream>
#include <string>

#include "libslic3r/Config.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/Semver.hpp"

#include "sapil_bambu_snapshot.h"  // for sapil::json_escape, sapil::colour_to_hex

namespace sapil {
extern Slic3r::Model g_model;
extern bool g_is_bbl;
extern Slic3r::Semver g_file_version;
extern Slic3r::DynamicPrintConfig& getModelConfig();
} // namespace sapil

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetProjectConfig(JNIEnv* env, jobject) {
    // Match the sub-plan #1 "no model loaded → null" contract.
    if (sapil::g_model.objects.empty()) return nullptr;

    std::ostringstream out;
    out << "{";
    out << "\"isBbl\":" << (sapil::g_is_bbl ? "true" : "false") << ",";
    // Semver::valid() is false for default-constructed / invalid — emit "" to
    // match the Kotlin snapshot path's empty-string contract.
    out << "\"fileVersion\":\""
        << sapil::json_escape(sapil::g_file_version.valid() ? sapil::g_file_version.to_string() : "")
        << "\",";

    const auto& cfg = sapil::getModelConfig();
    const auto* colours = cfg.opt<Slic3r::ConfigOptionStrings>("filament_colour");
    const auto* settings_ids = cfg.opt<Slic3r::ConfigOptionStrings>("filament_settings_id");
    const auto* filament_ids = cfg.opt<Slic3r::ConfigOptionStrings>("filament_ids");

    out << "\"filamentColours\":[";
    if (colours != nullptr) {
        for (size_t i = 0; i < colours->values.size(); ++i) {
            if (i) out << ",";
            out << "\"" << sapil::json_escape(sapil::colour_to_hex(colours->values[i])) << "\"";
        }
    }
    out << "],";

    // Match append_plate's fallback order: prefer filament_settings_id,
    // else filament_ids. The Kotlin consumer reads this list verbatim.
    out << "\"filamentSettingsIds\":[";
    const Slic3r::ConfigOptionStrings* settings_fallback =
        settings_ids != nullptr ? settings_ids : filament_ids;
    if (settings_fallback != nullptr) {
        for (size_t i = 0; i < settings_fallback->values.size(); ++i) {
            if (i) out << ",";
            out << "\"" << sapil::json_escape(settings_fallback->values[i]) << "\"";
        }
    }
    out << "],";

    out << "\"filamentIds\":[";
    if (filament_ids != nullptr) {
        for (size_t i = 0; i < filament_ids->values.size(); ++i) {
            if (i) out << ",";
            out << "\"" << sapil::json_escape(filament_ids->values[i]) << "\"";
        }
    }
    out << "]";
    out << "}";

    return env->NewStringUTF(out.str().c_str());
}

} // extern "C"
```

---

## Tasks

### Task 1: Promote `json_escape` and `colour_to_hex` to shared helpers

**Files:**
- Modify: `app/src/main/cpp/src/sapil_bambu_snapshot.h`
- Modify: `app/src/main/cpp/src/sapil_bambu_snapshot.cpp:50-85`

- [ ] **Step 1: Add declarations to the header**

Open `app/src/main/cpp/src/sapil_bambu_snapshot.h`. Find the existing `sapil::count_paint_states` declaration; add `json_escape` and `colour_to_hex` next to it inside `namespace sapil`:

```cpp
namespace sapil {
    // Existing:
    std::map<int, int> count_paint_states(const Slic3r::ModelVolume& mv,
                                          const Slic3r::FacetsAnnotation& facets);

    // New for sub-plan #5: lifted from sapil_bambu_snapshot.cpp's anon namespace
    // so JNI translation units sharing g_model reads can reuse them.
    std::string json_escape(const std::string& s);
    std::string colour_to_hex(const std::string& raw);
} // namespace sapil
```

Ensure `<string>` is included in the header (it may already be). If missing:
```cpp
#include <string>
```

- [ ] **Step 2: Move the two function definitions out of the anonymous namespace**

In `app/src/main/cpp/src/sapil_bambu_snapshot.cpp` the anonymous namespace begins at line 50 (`namespace {`). Cut the **two functions** `json_escape` (lines ~52-77) and `colour_to_hex` (lines ~81-85) out of that block and paste them inside `namespace sapil { ... }` (the outer namespace that already holds `count_paint_states` at line 287).

Before:
```cpp
namespace sapil {
// extern declarations ...
namespace {
std::string json_escape(const std::string& s) { /* body */ }
std::string colour_to_hex(const std::string& raw) { /* body */ }
const char* custom_gcode_type_name(int type) { /* ... */ }
// ... rest of anonymous namespace (append_plate, append_object, append_volume)
} // namespace
```

After:
```cpp
namespace sapil {
// extern declarations ...

std::string json_escape(const std::string& s) { /* body unchanged */ }
std::string colour_to_hex(const std::string& raw) { /* body unchanged */ }

namespace {
const char* custom_gcode_type_name(int type) { /* ... */ }
// ... rest of anonymous namespace
} // namespace
```

Callers inside the anonymous namespace (`append_plate` etc.) call unqualified `json_escape` and `colour_to_hex` — those calls remain valid because unqualified lookup from the anonymous namespace inside `namespace sapil` finds `sapil::json_escape`.

- [ ] **Step 3: Verify the move compiles (JVM unit tests don't exercise C++, so rely on CMake/Ninja compile check)**

Defer verification to Task 5's native rebuild — no targeted build for this tiny refactor.

- [ ] **Step 4: Commit**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  git restore -- app/src/main/cpp/extern/ && \
  git add app/src/main/cpp/src/sapil_bambu_snapshot.h app/src/main/cpp/src/sapil_bambu_snapshot.cpp && \
  git commit -m "phase1(bambu-native): promote json_escape + colour_to_hex to sapil namespace

Pre-extraction for sub-plan #5 (project config accessor). Moves the two
helpers out of sapil_bambu_snapshot.cpp's anonymous namespace into
namespace sapil so a new sapil_bambu_project.cpp translation unit can
reuse them without duplication. Bodies unchanged; callers inside the
anonymous namespace still resolve via unqualified lookup.

No behavioural change — refactor only, pending sub-plan #5's native rebuild."
```

---

### Task 2: Add `sapil_bambu_project.cpp` with the JNI entry point

**Files:**
- Create: `app/src/main/cpp/src/sapil_bambu_project.cpp`
- Modify: `app/src/main/cpp/CMakeLists.txt` (source list)

- [ ] **Step 1: Create the new translation unit**

Write `app/src/main/cpp/src/sapil_bambu_project.cpp` with exactly the body shown in the "Accessor design" section above. Confirm it:
- uses `sapil::json_escape` / `sapil::colour_to_hex` (from Task 1)
- externs `sapil::g_model`, `sapil::g_is_bbl`, `sapil::g_file_version`, `sapil::getModelConfig()`
- returns `nullptr` when `sapil::g_model.objects.empty()` (no model loaded)
- exports symbol `Java_com_u1_slicer_NativeLibrary_nativeGetProjectConfig`

- [ ] **Step 2: Add to CMakeLists.txt**

Open `app/src/main/cpp/CMakeLists.txt`. Find the line adding `src/sapil_bambu_volumes.cpp` to the slicer library sources. Add `src/sapil_bambu_project.cpp` immediately after it. E.g., find:

```cmake
    src/sapil_bambu_volumes.cpp
```

Change to:

```cmake
    src/sapil_bambu_volumes.cpp
    src/sapil_bambu_project.cpp
```

- [ ] **Step 3: Commit (build will happen in Task 5)**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  git restore -- app/src/main/cpp/extern/ && \
  git add app/src/main/cpp/src/sapil_bambu_project.cpp app/src/main/cpp/CMakeLists.txt && \
  git commit -m "phase1(bambu-native): add nativeGetProjectConfig JNI entry (sub-plan #5)

New TU sapil_bambu_project.cpp emits one JSON blob with g_is_bbl,
g_file_version, and project-level filament_colour / filament_settings_id
/ filament_ids — the same five fields already emitted inline at
sapil_bambu_snapshot.cpp:308-326. Reuses sapil::json_escape /
sapil::colour_to_hex promoted in the prior commit.

Native .so rebuild pending — follow-up commit."
```

---

### Task 3: Declare `nativeGetProjectConfig` in `NativeLibrary.kt`

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/NativeLibrary.kt` (after sub-plan #1's `nativeGetPaintStateCounts` declaration)

- [ ] **Step 1: Add the external fun with KDoc**

Find the `nativeGetPaintStateCounts(` declaration (around line 124). Immediately after its closing `): IntArray?` line, add:

```kotlin
    /**
     * Returns a JSON object with five project-level fields read from g_model after
     * a successful [loadModel]:
     *   {
     *     "isBbl":               bool,                  // g_is_bbl
     *     "fileVersion":         "x.y.z" | "",          // g_file_version.to_string() when valid, else ""
     *     "filamentColours":     ["#RRGGBB", ...],      // project config: filament_colour
     *     "filamentSettingsIds": ["Preset name", ...],  // filament_settings_id > filament_ids (first non-null)
     *     "filamentIds":         ["GFB98", ...]         // project config: filament_ids (raw)
     *   }
     *
     * Returns null if no model is loaded (same contract as the sub-plan #1
     * volume accessors). Callers MUST hold [previewMutex] for the duration of
     * any loadModel + accessor sequence.
     */
    external fun nativeGetProjectConfig(): String?
```

- [ ] **Step 2: Commit**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  git restore -- app/src/main/cpp/extern/ && \
  git add app/src/main/java/com/u1/slicer/NativeLibrary.kt && \
  git commit -m "phase1(bambu-native): declare nativeGetProjectConfig external fun (sub-plan #5)

Kotlin binding for the new JNI entry added in the prior commit. No caller
yet — wired into KotlinBambuSnapshot in the next commit after native rebuild."
```

---

### Task 4: Wire `KotlinBambuSnapshot.snapshot` to the new accessor

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/bambu/snapshot/KotlinBambuSnapshot.kt`

- [ ] **Step 1: Rename `readVolumesViaNative` to `readNativeData` and extend its return type**

Introduce a private data class inside `KotlinBambuSnapshot` holding the two native-sourced blocks:

```kotlin
private data class NativeData(
    val volumes: List<VolumeSnapshot>,
    val projectConfig: ProjectConfig?,
)

private data class ProjectConfig(
    val isBbl: Boolean,
    val fileVersion: String,
    val filamentColours: List<String>,
    val filamentSettingsIds: List<String>,
    val filamentIds: List<String>,
)
```

Add these just under the `object KotlinBambuSnapshot {` opening brace, before `parseObjectId`.

- [ ] **Step 2: Rename the method and return the new type**

Change the function signature and body:

```kotlin
    private suspend fun readNativeData(
        file: File,
        native: NativeLibrary,
    ): NativeData = NativeLibrary.previewMutex.withLock {
        if (!native.loadModel(file.absolutePath)) {
            return@withLock NativeData(volumes = emptyList(), projectConfig = null)
        }
        val objectCount = native.nativeGetObjectCount()
        val volumes = buildList {
            for (oi in 0 until objectCount) {
                val objectModelId = native.nativeGetObjectModelId(oi).toInt()
                val volumeCount = native.nativeGetVolumeCount(oi)
                for (vi in 0 until volumeCount) {
                    val scalars = native.nativeGetVolumeScalars(oi, vi) ?: continue
                    val extruder = if (scalars[0] == -1) null else scalars[0]
                    val mmPacked = native.nativeGetPaintStateCounts(oi, vi, 0) ?: intArrayOf()
                    val supPacked = native.nativeGetPaintStateCounts(oi, vi, 1) ?: intArrayOf()
                    add(
                        VolumeSnapshot(
                            objectId = objectModelId,
                            volumeIndex = vi,
                            extruder = extruder,
                            paintStateSet = unpackStateCounts(mmPacked),
                            paintSupportsStateSet = unpackStateCounts(supPacked),
                            isMmPainted = scalars[1] != 0,
                            isSeamPainted = scalars[2] != 0,
                        )
                    )
                }
            }
        }
        val projectConfig = parseProjectConfig(native.nativeGetProjectConfig())
        NativeData(volumes = volumes, projectConfig = projectConfig)
    }

    private fun parseProjectConfig(json: String?): ProjectConfig? {
        if (json.isNullOrEmpty()) return null
        return try {
            val obj = org.json.JSONObject(json)
            ProjectConfig(
                isBbl = obj.optBoolean("isBbl", false),
                fileVersion = obj.optString("fileVersion", ""),
                filamentColours = readStringArray(obj, "filamentColours"),
                filamentSettingsIds = readStringArray(obj, "filamentSettingsIds"),
                filamentIds = readStringArray(obj, "filamentIds"),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun readStringArray(obj: org.json.JSONObject, key: String): List<String> {
        val arr = obj.optJSONArray(key) ?: return emptyList()
        return List(arr.length()) { arr.optString(it, "") }
    }
```

- [ ] **Step 3: Use the NativeData in `snapshot()`**

Replace the current `snapshot` body (lines ~37-75) so it consumes `NativeData`:

```kotlin
    suspend fun snapshot(file: File, native: NativeLibrary): BambuFileSnapshot {
        if (!file.exists() || !file.name.endsWith(".3mf", ignoreCase = true)) {
            return empty(file.name)
        }
        val info = ThreeMfParser.parse(file)
        val customGcodeByPlate = readCustomGcodeByPlate(file)
        val nativeData = readNativeData(file, native)

        // Sub-plan #5: project-level palette is the uniform per-plate fallback
        // until sub-plan #2 overrides with PlateData.slice_filaments_info.
        val plateFilamentColours = nativeData.projectConfig?.filamentColours ?: emptyList()
        val plateFilamentSettingsIds = nativeData.projectConfig?.filamentSettingsIds ?: emptyList()

        val plates = info.plates.map { plate ->
            PlateSnapshot(
                plateIndex = plate.plateId,
                filamentColours = plateFilamentColours,
                filamentSettingsIds = plateFilamentSettingsIds,
                objectInstanceMap = plate.objectIds
                    .map { ObjectInstance(objectId = parseObjectId(it), instanceId = 0) },
                customGcode = customGcodeByPlate[plate.plateId].orEmpty(),
                plateConfig = emptyMap(),
            )
        }
        val objects = info.objects.map { obj ->
            val extruder = info.objectExtruderMap[obj.objectId] ?: 0
            ObjectSnapshot(
                objectId = parseObjectId(obj.objectId),
                name = obj.name,
                extruder = extruder,
                sourcePath = "",
            )
        }

        return BambuFileSnapshot(
            source = file.name,
            isBbl = nativeData.projectConfig?.isBbl ?: info.isBambu,
            fileVersion = nativeData.projectConfig?.fileVersion ?: "",
            plates = plates,
            objects = objects,
            volumes = nativeData.volumes,
        )
    }
```

Note the `?: info.isBambu` fallback on `isBbl` — if the native loadModel failed (projectConfig null), we retain the Kotlin-side detection. This preserves the current behaviour on malformed files.

- [ ] **Step 4: Verify the file still compiles**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  ./gradlew :app:compileDebugKotlin --no-daemon 2>&1 | tail -40
```

Expected: BUILD SUCCESSFUL. If errors, fix them before proceeding.

- [ ] **Step 5: Commit**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  git restore -- app/src/main/cpp/extern/ && \
  git add app/src/main/java/com/u1/slicer/bambu/snapshot/KotlinBambuSnapshot.kt && \
  git commit -m "phase1(bambu-native): source isBbl/fileVersion/filamentColours from JNI (sub-plan #5)

KotlinBambuSnapshot.snapshot now parses nativeGetProjectConfig's JSON
under the existing previewMutex + loadModel scope and maps the five
fields into BambuFileSnapshot.isBbl, BambuFileSnapshot.fileVersion,
PlateSnapshot.filamentColours (uniform project palette per plate),
and PlateSnapshot.filamentSettingsIds. Previous sources:

  - isBbl           = info.isBambu            (Kotlin ZIP markers)
  - fileVersion     = \"\"                      (not parsed)
  - filamentColours = info.detectedColors     (priority-chain parser)
  - filamentSettingsIds = emptyList()         (not parsed)

Kotlin fallbacks remain when native loadModel fails (corrupt file).
Native .so rebuild pending — tests gated on Task 5."
```

---

### Task 5: Rebuild the native `.so`

**Files:**
- Modify (generated): `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so`

- [ ] **Step 1: Verify build dir state**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  ls app/.cxx/Debug/ndk26release/arm64-v8a/build.ninja && \
  grep CMAKE_BUILD_TYPE app/.cxx/Debug/ndk26release/arm64-v8a/CMakeCache.txt && \
  grep 'ndk/26' app/.cxx/Debug/ndk26release/arm64-v8a/CMakeCache.txt | head -2
```

Expected: `build.ninja` exists, `CMAKE_BUILD_TYPE:STRING=Release`, NDK path contains `26.1.10909125`.

If the build dir is missing, follow the "Fresh build" checklist in `CLAUDE.md` "Native Rebuild" section to create it.

- [ ] **Step 2: Incremental build**

Run in the foreground — incremental rebuild (one new TU + a trivial header/anon-namespace shuffle in an existing TU) completes in 2-15 min, well within bash timeout. `-j1` to avoid OOM.

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  C:/Users/kevin/AppData/Local/Android/Sdk/cmake/3.22.1/bin/ninja.exe \
    -C app/.cxx/Debug/ndk26release/arm64-v8a -j1 prusaslicer-jni 2>&1 | tail -30
```

Expected: ends with `Linking CXX shared library ... libprusaslicer-jni.so`. If it OOMs, re-run; if it fails to find a symbol (`sapil::json_escape`), recheck Task 1's move.

- [ ] **Step 3: Strip and verify**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  STRIP=$(ls C:/Users/kevin/AppData/Local/Android/Sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/*/bin/llvm-strip) && \
  READELF=$(ls C:/Users/kevin/AppData/Local/Android/Sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/*/bin/llvm-readelf) && \
  SO=app/.cxx/Debug/ndk26release/arm64-v8a/libprusaslicer-jni.so && \
  "$STRIP" --strip-unneeded "$SO" && \
  ls -l "$SO" && \
  "$READELF" -p .comment "$SO" | grep -i clang && \
  "$READELF" --dyn-syms "$SO" | grep -c nativeGetProjectConfig
```

Expected:
- Size ≈ 19-21 MB (if 80MB+ → Debug build, abort; if 500MB+ → unstripped, re-run strip)
- `.comment` line contains `clang version 17.0.2`
- Grep count ≥ 1 for `nativeGetProjectConfig`

- [ ] **Step 4: Copy into jniLibs**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  cp app/.cxx/Debug/ndk26release/arm64-v8a/libprusaslicer-jni.so \
     app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so && \
  ls -l app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so
```

Expected: 19-21 MB file mtime-stamped just now.

- [ ] **Step 5: Commit the rebuilt `.so`**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  git restore -- app/src/main/cpp/extern/ && \
  git add app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so && \
  git commit -m "phase1(bambu-native): rebuild .so with nativeGetProjectConfig (sub-plan #5)

NDK 26 / Clang 17 / Release / stripped. Exports the new JNI symbol
Java_com_u1_slicer_NativeLibrary_nativeGetProjectConfig alongside the
sub-plan #1 volume accessors."
```

---

### Task 6: Update `KotlinBambuSnapshotTest` assertions

**Files:**
- Modify: `app/src/androidTest/java/com/u1/slicer/bambu/snapshot/KotlinBambuSnapshotTest.kt`

- [ ] **Step 1: Update the `fileVersion` assertion**

Replace:
```kotlin
        // fileVersion is not exposed by the current Kotlin parsers — Task 2 leaves it empty.
        assertEquals("", snapshot.fileVersion)
```

With:
```kotlin
        // Phase 1 sub-plan #5: fileVersion sourced from native (g_file_version.to_string()).
        // colored_3DBenchy has a BambuStudio:3mfVersion metadata entry — expect non-empty.
        assertTrue(
            "expected non-empty fileVersion post sub-plan #5, got '${snapshot.fileVersion}'",
            snapshot.fileVersion.isNotEmpty()
        )
```

- [ ] **Step 2: Keep the `filamentColours` assertion but update the rationale comment**

Replace the comment block + assertion at lines ~67-75 with:
```kotlin
        // Phase 1 sub-plan #5: plate.filamentColours now sourced from the
        // project-level filament_colour array via nativeGetProjectConfig. The
        // previously-captured Kotlin values came from exactly the same
        // project_settings.config JSON, so the four hex values are unchanged.
        // Sub-plan #2 will override per-plate when slice_filaments_info is set.
        assertEquals(
            listOf("#0086D6", "#FB0207", "#F4EE2A", "#E2DEDB"),
            plate.filamentColours
        )
```

- [ ] **Step 3: Update the `filamentSettingsIds` assertion**

Replace:
```kotlin
        // Kotlin doesn't parse filament_settings_id today — left empty so the
        // diff harness surfaces the gap vs the native loader.
        assertEquals(emptyList<String>(), plate.filamentSettingsIds)
```

With:
```kotlin
        // Phase 1 sub-plan #5: filamentSettingsIds sourced from project config
        // (filament_settings_id with filament_ids fallback). Non-empty for
        // colored_3DBenchy since the file has a 4-slot project palette.
        assertTrue(
            "expected filamentSettingsIds non-empty post sub-plan #5, got ${plate.filamentSettingsIds}",
            plate.filamentSettingsIds.isNotEmpty()
        )
```

- [ ] **Step 4: Install, run the test, confirm green**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  adb -s 43211JEKB16931 uninstall com.u1.slicer.orca; \
  adb -s 43211JEKB16931 uninstall com.u1.slicer.orca.test; \
  ./gradlew assembleDebug assembleDebugAndroidTest --no-daemon 2>&1 | tail -10 && \
  adb -s 43211JEKB16931 install -r -d app/build/outputs/apk/debug/app-debug.apk && \
  adb -s 43211JEKB16931 install -r -d app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk && \
  adb -s 43211JEKB16931 shell am instrument -w -r \
    -e class com.u1.slicer.bambu.snapshot.KotlinBambuSnapshotTest \
    com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tail -20
```

Expected: `OK (1 test)`.

- [ ] **Step 5: Commit**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  git restore -- app/src/main/cpp/extern/ && \
  git add app/src/androidTest/java/com/u1/slicer/bambu/snapshot/KotlinBambuSnapshotTest.kt && \
  git commit -m "phase1(bambu-native): update KotlinBambuSnapshotTest for sub-plan #5 project config

fileVersion and filamentSettingsIds are now non-empty; filamentColours
assertion retains the same hex values because the project-level palette
is the same JSON source the Kotlin priority-chain detector was using."
```

---

### Task 7: Add NativeLibraryCorrectnessTest coverage for `nativeGetProjectConfig`

**Files:**
- Modify: `app/src/androidTest/java/com/u1/slicer/native/NativeLibraryCorrectnessTest.kt`

- [ ] **Step 1: Add two new `@Test` methods**

Find an existing `@Test` in the file (e.g., a sub-plan #1 paint-state-counts test) and add the following two tests after it, using the same fixture-load pattern as the existing tests:

```kotlin
    @Test
    fun native_get_project_config_returns_non_empty_json_for_flarewing_dragon() = runBlocking {
        val fixture = copyAssetToCache("Flarewing-Dragon_100%_4FilamentMulticolor_v1.1.3mf")
        val native = NativeLibrary()
        NativeLibrary.previewMutex.withLock {
            assertTrue(native.loadModel(fixture.absolutePath))
            val json = native.nativeGetProjectConfig()
            assertNotNull("nativeGetProjectConfig should return non-null for valid Bambu 3MF", json)
            val obj = org.json.JSONObject(json!!)
            assertTrue("isBbl should be true for Bambu 3MF", obj.getBoolean("isBbl"))
            val version = obj.getString("fileVersion")
            assertTrue(
                "fileVersion should be non-empty for this fixture (has BambuStudio:3mfVersion), got '$version'",
                version.isNotEmpty()
            )
            val colours = obj.getJSONArray("filamentColours")
            assertTrue(
                "filamentColours should be non-empty for a 4-colour fixture, got length ${colours.length()}",
                colours.length() > 0
            )
            // Every entry starts with '#'.
            for (i in 0 until colours.length()) {
                val hex = colours.getString(i)
                assertTrue(
                    "filamentColours[$i]='$hex' should start with '#'",
                    hex.startsWith("#")
                )
            }
        }
    }

    @Test
    fun native_get_project_config_returns_null_when_no_model_loaded() = runBlocking {
        val native = NativeLibrary()
        NativeLibrary.previewMutex.withLock {
            native.clearModel()
            assertNull(
                "nativeGetProjectConfig must return null before any loadModel / after clearModel",
                native.nativeGetProjectConfig()
            )
        }
    }
```

If `copyAssetToCache` / `assertNotNull` / `assertNull` / `runBlocking` / `withLock` imports aren't already present, add them at the top of the file:

```kotlin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
```

(Only add the ones missing — check the file first.)

- [ ] **Step 2: Run the two new tests**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  ./gradlew assembleDebugAndroidTest --no-daemon 2>&1 | tail -5 && \
  adb -s 43211JEKB16931 install -r -d app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk && \
  adb -s 43211JEKB16931 shell am instrument -w -r \
    -e class com.u1.slicer.native.NativeLibraryCorrectnessTest#native_get_project_config_returns_non_empty_json_for_flarewing_dragon,com.u1.slicer.native.NativeLibraryCorrectnessTest#native_get_project_config_returns_null_when_no_model_loaded \
    com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tail -15
```

Expected: `OK (2 tests)`.

- [ ] **Step 3: Commit**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  git restore -- app/src/main/cpp/extern/ && \
  git add app/src/androidTest/java/com/u1/slicer/native/NativeLibraryCorrectnessTest.kt && \
  git commit -m "phase1(bambu-native): test nativeGetProjectConfig accessor (sub-plan #5)

Two instrumented tests on Pixel 8a: Flarewing-Dragon returns non-empty
JSON with isBbl=true, non-empty fileVersion, and all #-prefixed hex
colours; no-model-loaded returns null."
```

---

### Task 8: Prune baseline entries and run differential suite

**Files:**
- Modify: `app/src/androidTest/assets/diagnostics/known-disagreements.json`

- [ ] **Step 1: Inspect current baseline entries for the two paths**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  python3 -c "
import json
with open('app/src/androidTest/assets/diagnostics/known-disagreements.json') as f:
    data = json.load(f)
fixtures = data['fixtures']
totals = {'fileVersion': 0, 'filamentColours.size': 0, 'filamentColours.content': 0, 'other': 0}
for name, entries in fixtures.items():
    for e in entries:
        p = e['path']
        if p == 'fileVersion':
            totals['fileVersion'] += 1
        elif p.endswith('filamentColours.size'):
            totals['filamentColours.size'] += 1
        elif 'filamentColours[' in p:
            totals['filamentColours.content'] += 1
        else:
            totals['other'] += 1
print(totals)
print('total entries:', sum(len(v) for v in fixtures.values()))
"
```

Expected: `{'fileVersion': 20, 'filamentColours.size': 23, 'filamentColours.content': 3, 'other': 196}` approximately. Total should be 242 (post sub-plan #1).

- [ ] **Step 2: Remove the 43 now-closed entries**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  python3 -c "
import json
path = 'app/src/androidTest/assets/diagnostics/known-disagreements.json'
with open(path) as f:
    data = json.load(f)
fixtures = data['fixtures']
removed = 0
for name in list(fixtures.keys()):
    kept = []
    for e in fixtures[name]:
        p = e['path']
        if p == 'fileVersion' or p.endswith('filamentColours.size'):
            removed += 1
            continue
        kept.append(e)
    if kept:
        fixtures[name] = kept
    else:
        del fixtures[name]
data['fixtures'] = fixtures
with open(path, 'w') as f:
    json.dump(data, f, indent=2)
    f.write('\n')
print('removed', removed, 'entries')
print('remaining fixtures:', len(fixtures))
total = sum(len(v) for v in fixtures.values())
print('remaining total:', total)
"
```

Expected: `removed 43 entries`, `remaining total: 199`.

- [ ] **Step 3: Run the full differential suite**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  adb -s 43211JEKB16931 shell am instrument -w -r \
    -e class com.u1.slicer.bambu.snapshot.BambuParserDifferentialTest \
    com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tail -40
```

Expected: `OK (21 tests)`. If failures mention `Unexpected diffs`, triage the new path — probably RGBA vs RGB colour format on plate 0 of `colored_3DBenchy`. Capture the failing paths and add back to the baseline only if they are intentional sub-plan-#2 residues.

Expected `Baseline has N stale entries` failures: if fewer than 43 entries closed, some fixtures didn't have the path (e.g., non-Bambu 3MF has no `fileVersion` entry). That's fine — the python script only removes entries that were present.

- [ ] **Step 4: Commit baseline prune**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  git restore -- app/src/main/cpp/extern/ && \
  git add app/src/androidTest/assets/diagnostics/known-disagreements.json && \
  git commit -m "phase1(bambu-diff): prune 43 baseline entries closed by sub-plan #5

  - 20 fileVersion entries (now sourced from g_file_version via JNI)
  - 23 plates[*].filamentColours.size entries (project palette sourced
    from getModelConfig() via JNI, uniform per plate)

Remaining baseline: ~199 entries. Next sub-plan (#2) closes plates[*]
fields; sub-plan #2 will also resolve the 3 residual filamentColours
content diffs (RGBA vs RGB on colored_3DBenchy) when it promotes
PlateData.slice_filaments_info per-plate colour resolution."
```

---

### Task 9: Full regression — unit tests + Bambu instrumented package

**Files:** (no edits — verification only)

- [ ] **Step 1: JVM unit tests**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  ./gradlew testDebugUnitTest --no-daemon 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Full Bambu instrumented package**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  adb -s 43211JEKB16931 shell am instrument -w -r \
    -e package com.u1.slicer.bambu \
    com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tail -30
```

Expected: `OK` with no failures. If something fails, triage — snapshot data class may have been touched elsewhere.

- [ ] **Step 3: Full differential suite (already done in Task 8, confirm still green)**

Already green from Task 8. If any intervening commit moved things, rerun:

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  adb -s 43211JEKB16931 shell am instrument -w -r \
    -e class com.u1.slicer.bambu.snapshot.BambuParserDifferentialTest \
    com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tail -5
```

---

### Task 10: Update docs + handoff appendix

**Files:**
- Modify: `docs/superpowers/plans/2026-04-23-phase1-kickoff-handoff.md` (append)
- Modify: `CLAUDE.md` — bump instrumented test count if `NativeLibraryCorrectnessTest` gained 2 tests (currently 10 → 12)

- [ ] **Step 1: Append the "Sub-plan #5 status: LANDED" section**

Add this at the end of `docs/superpowers/plans/2026-04-23-phase1-kickoff-handoff.md`:

```markdown

## Sub-plan #5 status: LANDED (2026-04-23)

Baseline closure:
- Pre-sub-plan-#5 total: 242 entries.
- Post-sub-plan-#5 total: ~199 entries.
- Closed: 43 = 20 `fileVersion` + 23 `plates[*].filamentColours.size`.

Changes shipped:
- New JNI accessor `NativeLibrary.nativeGetProjectConfig(): String?` returning a JSON blob with `isBbl`, `fileVersion`, `filamentColours`, `filamentSettingsIds`, `filamentIds`. All pure reads of `g_is_bbl`, `g_file_version`, and `getModelConfig()`.
- New C++ TU `sapil_bambu_project.cpp` owns the JNI entry point.
- `sapil::json_escape` and `sapil::colour_to_hex` promoted from the anonymous namespace in `sapil_bambu_snapshot.cpp` to `namespace sapil` so the new TU can reuse them without duplication.
- `KotlinBambuSnapshot.snapshot` parses the new JSON under the existing `previewMutex + loadModel` scope and maps the five fields into `BambuFileSnapshot.isBbl`, `BambuFileSnapshot.fileVersion`, `PlateSnapshot.filamentColours` (uniform project palette per plate), and `PlateSnapshot.filamentSettingsIds`. Kotlin fallbacks retained when native `loadModel` fails (corrupt file).

Out of scope (deferred):
- Per-plate `slice_filaments_info` override for `PlateSnapshot.filamentColours` — sub-plan #2's job.
- 3 RGBA content diffs on `colored_3DBenchy` plate 0 — will fall out of sub-plan #2's per-plate colour normalisation.

Tests: differential suite 21/21; NativeLibraryCorrectnessTest 12/12; KotlinBambuSnapshotTest 1/1; Bambu instrumented package green; JVM unit tests green.

Next: Sub-plan #2 (per-plate PlateData) per roadmap — the largest sub-plan, 165 baseline entries across `plateIndex`, `objectInstanceMap`, `filamentSettingsIds`, `plateConfig`.
```

- [ ] **Step 2: Bump CLAUDE.md test count**

Open `CLAUDE.md` (both the main repo and worktree copy — do the worktree's). Find the line:

```
- `native/NativeLibraryCorrectnessTest.kt` (10) — JNI correctness checks + Phase 1 sub-plan #1 accessors ...
```

Change `(10)` to `(12)` and append to the trailing description:
`..., sub-plan #5 accessor (nativeGetProjectConfig)`

Also find the top-line count `### Instrumented tests (\`app/src/androidTest/\`) - N tests across 18 classes` and bump `N` by 2.

- [ ] **Step 3: Commit**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  git restore -- app/src/main/cpp/extern/ && \
  git add docs/superpowers/plans/2026-04-23-phase1-kickoff-handoff.md CLAUDE.md && \
  git commit -m "docs(phase1): sub-plan #5 landed — project config via JNI closes 43 entries"
```

---

## Exit criteria

- [ ] `BambuParserDifferentialTest` 21/21 green with baseline at ~199 entries (down from 242).
- [ ] `NativeLibraryCorrectnessTest` 12/12 green (including 2 new `nativeGetProjectConfig` tests).
- [ ] `KotlinBambuSnapshotTest` 1/1 green.
- [ ] Full Bambu instrumented package green.
- [ ] `testDebugUnitTest` green.
- [ ] `libprusaslicer-jni.so` ≈ 20 MB, Clang 17, exports `Java_com_u1_slicer_NativeLibrary_nativeGetProjectConfig`.
- [ ] Handoff doc has Sub-plan #5 LANDED appendix; `CLAUDE.md` test count bumped.
- [ ] All commits have `git restore -- app/src/main/cpp/extern/` applied (no vendored-doc noise).
