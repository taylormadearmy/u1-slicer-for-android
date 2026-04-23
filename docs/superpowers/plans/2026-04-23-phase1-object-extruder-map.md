# Phase 1 Sub-Plan #4 — Object extruder map (snapshot-only) via JNI

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the 20 remaining `objects.size` baseline entries by sourcing `BambuFileSnapshot.objects` from a new snapshot-only JNI accessor backed by Phase 0's existing `append_object` emitter. Production code remains untouched — all slice-time consumers of `ThreeMfInfo.objectExtruderMap` stay on their XML-keyed path.

**Architecture:** One new C++ TU `sapil_bambu_objects.cpp` owns `Java_com_u1_slicer_NativeLibrary_nativeGetObjectExtruderMap`, returning a JSON array `[{objectId, name, extruder, sourcePath}]` built by looping `g_model.objects` and calling `sapil::append_object`. `append_object` (and its sibling `append_volume`) are promoted from `sapil_bambu_snapshot.cpp`'s anonymous namespace to `namespace sapil`, matching the `append_plate` promotion from sub-plan #2. Kotlin `KotlinBambuSnapshot.snapshot` consumes the JSON under the existing `previewMutex + loadModel` scope and builds `ObjectSnapshot`s with runtime ObjectID as `objectId`.

**Tech Stack:** Kotlin 1.9.22, `org.json.JSONArray`/`JSONObject`, Android NDK 26 / Clang 17, CMake + Ninja (`-j1`), JUnit4 + AndroidJUnit4.

---

## Operating rules

Same as sub-plans #5 / #2. Worktree, DEX, assets, device install cycle, `extern/` restore before commits, `ninja -j1` incremental build, strip + verify .so. See `feedback-bambu-refactor-gotchas.md`.

---

## File structure

**New files:**

| Path | Responsibility |
|---|---|
| `app/src/main/cpp/src/sapil_bambu_objects.cpp` | One JNI entry: `nativeGetObjectExtruderMap` returning `[{objectId,name,extruder,sourcePath}, ...]`. |
| `app/src/androidTest/java/com/u1/slicer/native/NativeObjectExtruderMapTest.kt` | 3 instrumented smoke tests. |

**Modified files:**

| Path | Change |
|---|---|
| `app/src/main/cpp/src/sapil_bambu_snapshot.h` | Declare `sapil::append_object`. |
| `app/src/main/cpp/src/sapil_bambu_snapshot.cpp` | Move `append_object` out of the anonymous namespace into `namespace sapil` (unchanged body). |
| `app/src/main/cpp/CMakeLists.txt` | Add `src/sapil_bambu_objects.cpp`. |
| `app/src/main/java/com/u1/slicer/NativeLibrary.kt` | Add `external fun nativeGetObjectExtruderMap(): String?`. |
| `app/src/main/java/com/u1/slicer/bambu/snapshot/KotlinBambuSnapshot.kt` | Extend `NativeData` with `objects: List<ObjectSnapshot>?`; source `BambuFileSnapshot.objects` from the new accessor; retain the `info.objects`-based fallback for `!loadModel`. |
| `app/src/androidTest/java/com/u1/slicer/bambu/snapshot/KotlinBambuSnapshotTest.kt` | Replace `assertEquals(emptyList, snapshot.objects)` with structural checks (the fixture is a component-ref 3MF; native emits a non-empty list now). |
| `app/src/androidTest/assets/diagnostics/known-disagreements.json` | Remove 20 `objects.size` entries (one per fixture). |
| `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so` | Stripped Release rebuild. |
| `CLAUDE.md` | Bump instrumented test count; add `NativeObjectExtruderMapTest.kt` line. |
| `docs/superpowers/plans/2026-04-23-phase1-kickoff-handoff.md` | Append "Sub-plan #4 LANDED" section. |

**Deliberately NOT touched:**

- `ThreeMfInfo.objectExtruderMap` and every production consumer (`SlicerViewModel`, `ProfileEmbedder`, `BambuSanitizer`) — they stay on the XML-id-keyed Kotlin path. ObjectID identity is a snapshot-level concern only.

---

## Accessor design

```kotlin
// NativeLibrary.kt addition, after nativeGetPlateData:

/**
 * Returns a JSON array of every ModelObject in g_model:
 *   [
 *     {"objectId": <runtime-size_t>, "name": "...", "extruder": <int>, "sourcePath": "..."},
 *     ...
 *   ]
 *
 * `extruder` follows the Phase 0 contract (0 = inherit/unset, else the
 * per-object override). `objectId` is Slic3r's runtime ObjectID (process-local
 * size_t) — it is NOT the XML object id. Production code that needs XML-id
 * keyed maps should continue to read ThreeMfInfo.objectExtruderMap.
 *
 * Returns null when no model is loaded. Callers MUST hold [previewMutex].
 */
external fun nativeGetObjectExtruderMap(): String?
```

C++ (`sapil_bambu_objects.cpp`):

```cpp
// sapil_bambu_objects.cpp
//
// Phase 1 sub-plan #4: full object list JNI accessor. Pure read of
// g_model.objects; reuses sapil::append_object (promoted from the
// sapil_bambu_snapshot.cpp anonymous namespace). Callers hold
// NativeLibrary.previewMutex; C++ assumes serialised access.

#include <jni.h>

#include <sstream>
#include <string>

#include "libslic3r/Model.hpp"

#include "sapil_bambu_snapshot.h"  // sapil::append_object

namespace sapil {
extern Slic3r::Model g_model;
} // namespace sapil

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetObjectExtruderMap(
        JNIEnv* env, jobject) {
    if (sapil::g_model.objects.empty()) return nullptr;
    std::ostringstream out;
    out << "[";
    for (size_t i = 0; i < sapil::g_model.objects.size(); ++i) {
        if (i) out << ",";
        const Slic3r::ModelObject* mo = sapil::g_model.objects[i];
        if (mo == nullptr) {
            out << "null";
            continue;
        }
        sapil::append_object(out, *mo);
    }
    out << "]";
    return env->NewStringUTF(out.str().c_str());
}

} // extern "C"
```

---

## Tasks

### Task 1: Promote `append_object` to `sapil::append_object`

**Files:**
- Modify: `app/src/main/cpp/src/sapil_bambu_snapshot.h`
- Modify: `app/src/main/cpp/src/sapil_bambu_snapshot.cpp`

- [ ] **Step 1: Declare in the header**

In `app/src/main/cpp/src/sapil_bambu_snapshot.h`, add a forward-declare for `ModelObject` and the function signature, next to `append_plate`:

```cpp
namespace Slic3r {
    class ModelVolume;
    class FacetsAnnotation;
    class ConfigOptionStrings;
    struct PlateData;
    class ModelObject;  // ADD
}
```

Inside `namespace sapil { ... }`, after `append_plate`:

```cpp
/**
 * Emit the Phase 0 per-object JSON body (braces included) for one ModelObject.
 * Matches the BambuFileSnapshot contract: objectId = runtime ObjectID,
 * extruder 0 = inherit.
 *
 * Shared with Phase 1 sub-plan #4 JNI accessor (sapil_bambu_objects.cpp).
 */
void append_object(std::ostringstream& out, const Slic3r::ModelObject& mo);
```

- [ ] **Step 2: Move `append_object` out of the anonymous namespace**

In `sapil_bambu_snapshot.cpp`, find `void append_object(std::ostringstream& out, const Slic3r::ModelObject& mo)` (currently inside the anon namespace, after `append_plate`'s new position). Cut the function and paste it immediately after `append_plate`, inside `namespace sapil` at file scope. Body unchanged.

The caller in `bambu_snapshot_json` still resolves it via unqualified lookup.

- [ ] **Step 3: Commit**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  git restore -- app/src/main/cpp/extern/ && \
  git add app/src/main/cpp/src/sapil_bambu_snapshot.h app/src/main/cpp/src/sapil_bambu_snapshot.cpp && \
  git commit -m "phase1(bambu-native): promote append_object to sapil namespace

Pre-extraction for sub-plan #4 (object extruder map accessor). Moves
append_object out of sapil_bambu_snapshot.cpp's anonymous namespace into
namespace sapil so a new sapil_bambu_objects.cpp translation unit can
reuse it without duplication. Body unchanged.

No behavioural change — refactor only, pending sub-plan #4's native rebuild."
```

---

### Task 2: Create `sapil_bambu_objects.cpp` + CMake wiring

**Files:**
- Create: `app/src/main/cpp/src/sapil_bambu_objects.cpp`
- Modify: `app/src/main/cpp/CMakeLists.txt`

- [ ] **Step 1: Write the new TU**

Use the body from "Accessor design" above.

- [ ] **Step 2: CMakeLists.txt append**

Append `src/sapil_bambu_objects.cpp` after `src/sapil_bambu_plate.cpp`.

- [ ] **Step 3: Commit**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  git restore -- app/src/main/cpp/extern/ && \
  git add app/src/main/cpp/src/sapil_bambu_objects.cpp app/src/main/cpp/CMakeLists.txt && \
  git commit -m "phase1(bambu-native): add nativeGetObjectExtruderMap JNI entry (sub-plan #4)

New TU sapil_bambu_objects.cpp reuses sapil::append_object to emit the
full g_model.objects JSON array. Returns null when no model is loaded.

Native .so rebuild pending."
```

---

### Task 3: Declare `nativeGetObjectExtruderMap` in `NativeLibrary.kt`

- [ ] **Step 1:** Add the external fun + KDoc after `nativeGetPlateData`.

- [ ] **Step 2: Commit.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  git restore -- app/src/main/cpp/extern/ && \
  git add app/src/main/java/com/u1/slicer/NativeLibrary.kt && \
  git commit -m "phase1(bambu-native): declare nativeGetObjectExtruderMap (sub-plan #4)"
```

---

### Task 4: Wire `KotlinBambuSnapshot` to the new accessor

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/bambu/snapshot/KotlinBambuSnapshot.kt`

- [ ] **Step 1: Extend `NativeData`**

```kotlin
    private data class NativeData(
        val volumes: List<VolumeSnapshot>,
        val projectConfig: ProjectConfig?,
        val plates: List<NativePlate>?,
        val objects: List<ObjectSnapshot>?,  // null when loadModel failed
    )
```

Update both `NativeData(volumes = emptyList(), projectConfig = null, plates = null)` construction in the `!loadModel` branch to include `objects = null`.

- [ ] **Step 2: Read objects inside `readNativeData`**

After the `plates` buildList, before returning `NativeData(...)`:

```kotlin
        val objects = parseObjectArray(native.nativeGetObjectExtruderMap())
        NativeData(
            volumes = volumes,
            projectConfig = projectConfig,
            plates = plates,
            objects = objects,
        )
```

- [ ] **Step 3: Add the parser**

```kotlin
    private fun parseObjectArray(json: String?): List<ObjectSnapshot>? {
        if (json.isNullOrEmpty()) return null
        return try {
            val arr = org.json.JSONArray(json)
            List(arr.length()) { i ->
                val o = arr.optJSONObject(i) ?: return@List ObjectSnapshot(
                    objectId = -1, name = "", extruder = 0, sourcePath = ""
                )
                ObjectSnapshot(
                    // Runtime ObjectID is size_t on native, arrives as JSON number.
                    // Int truncation is safe in practice (IDs fit easily) and matches
                    // VolumeSnapshot.objectId's Int contract established by sub-plan #1.
                    objectId = o.optLong("objectId", -1L).toInt(),
                    name = o.optString("name", ""),
                    extruder = o.optInt("extruder", 0),
                    sourcePath = o.optString("sourcePath", ""),
                )
            }
        } catch (_: Exception) {
            null
        }
    }
```

- [ ] **Step 4: Use `nativeData.objects` in `snapshot()`**

Replace the current `objects = info.objects.map { obj -> ... }` block with:

```kotlin
        // Sub-plan #4: objects sourced from native g_model.objects via
        // nativeGetObjectExtruderMap. The runtime ObjectID does NOT match the
        // XML id that Kotlin parsed, which is why the diff-harness baseline
        // had 20 `objects.size` entries: Kotlin filters <object> elements to
        // those with > 0 inline vertices, while native's list includes every
        // object after Slic3r's merge of component-refs. Closing the diffs
        // requires sourcing from native. Production code still reads
        // ThreeMfInfo.objectExtruderMap for its XML-id keyed usage.
        val objects: List<ObjectSnapshot> = nativeData.objects ?: info.objects.map { obj ->
            val extruder = info.objectExtruderMap[obj.objectId] ?: 0
            ObjectSnapshot(
                objectId = parseObjectId(obj.objectId),
                name = obj.name,
                extruder = extruder,
                sourcePath = "",
            )
        }
```

- [ ] **Step 5: Verify Kotlin compiles**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  ./gradlew :app:compileDebugKotlin --no-daemon 2>&1 | tail -10
```

- [ ] **Step 6: Commit**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
  git restore -- app/src/main/cpp/extern/ && \
  git add app/src/main/java/com/u1/slicer/bambu/snapshot/KotlinBambuSnapshot.kt && \
  git commit -m "phase1(bambu-native): source objects list from JNI (sub-plan #4)

BambuFileSnapshot.objects now built from nativeGetObjectExtruderMap under
the existing previewMutex + loadModel scope. Runtime ObjectID fills
ObjectSnapshot.objectId (was XML-id-parsed int). Closes the 20
objects.size baseline entries caused by Kotlin filtering <object>
elements to those with > 0 inline vertices while native's g_model.objects
contains every object after component-ref merge.

Production consumers of ThreeMfInfo.objectExtruderMap are untouched.
Native .so rebuild pending."
```

---

### Task 5: Native rebuild + install

Same pattern as sub-plans #5/#2. `ninja -j1`, strip, verify clang 17 + size + dyn-syms `nativeGetObjectExtruderMap`, copy to `jniLibs/`, commit.

---

### Task 6: Update `KotlinBambuSnapshotTest` for post-#4 objects

**Files:**
- Modify: `app/src/androidTest/java/com/u1/slicer/bambu/snapshot/KotlinBambuSnapshotTest.kt`

- [ ] **Step 1: Replace `assertEquals(emptyList, snapshot.objects)` with structural checks**

```kotlin
        // Phase 1 sub-plan #4: objects sourced from native g_model.objects via
        // nativeGetObjectExtruderMap. colored_3DBenchy is a component-ref 3MF
        // (Kotlin filtered it to 0 inline objects); native merges the refs, so
        // the list is non-empty here. Check structure, not specific content —
        // the diff-harness baseline is the authority on exact values.
        assertTrue(
            "expected non-empty objects list post sub-plan #4, got ${snapshot.objects.size}",
            snapshot.objects.isNotEmpty()
        )
        for (o in snapshot.objects) {
            assertTrue("objectId should be > 0 (runtime ObjectID), got ${o.objectId}", o.objectId > 0)
            assertTrue("extruder should be >= 0, got ${o.extruder}", o.extruder >= 0)
        }
```

- [ ] **Step 2: Run test**

```bash
./gradlew assembleDebug assembleDebugAndroidTest --no-daemon && \
  adb -s 43211JEKB16931 install -r -d app/build/outputs/apk/debug/app-debug.apk && \
  adb -s 43211JEKB16931 install -r -d app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk && \
  adb -s 43211JEKB16931 shell am instrument -w -r \
    -e class com.u1.slicer.bambu.snapshot.KotlinBambuSnapshotTest \
    com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tail -10
```

Expected: OK (1 test).

- [ ] **Step 3: Commit**

```bash
git commit -m "phase1(bambu-native): update KotlinBambuSnapshotTest for sub-plan #4 objects"
```

---

### Task 7: Add `NativeObjectExtruderMapTest`

**Files:**
- Create: `app/src/androidTest/java/com/u1/slicer/native/NativeObjectExtruderMapTest.kt`

- [ ] **Step 1: Write 3 tests**

```kotlin
package com.u1.slicer.native

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeObjectExtruderMapTest {

    private lateinit var lib: NativeLibrary

    @Before fun setup() { assertTrue(NativeLibrary.isLoaded); lib = NativeLibrary() }
    @After fun teardown() { lib.clearModel() }

    private fun copyFixture(name: String): File {
        val assetContext = InstrumentationRegistry.getInstrumentation().context
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val f = File(
            targetContext.cacheDir,
            "obj_fixture_" + name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        )
        assetContext.assets.open(name).use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    @Test
    fun native_get_object_extruder_map_returns_null_when_no_model_loaded() {
        lib.clearModel()
        assertNull(lib.nativeGetObjectExtruderMap())
    }

    @Test
    fun native_get_object_extruder_map_returns_merged_objects_for_colored_benchy() {
        val fixture = copyFixture("colored_3DBenchy (1).3mf")
        try {
            assertTrue(lib.loadModel(fixture.absolutePath))
            val json = lib.nativeGetObjectExtruderMap()
            assertNotNull(json)
            val arr = JSONArray(json!!)
            assertTrue("component-ref fixture should have >= 1 merged object, got ${arr.length()}", arr.length() >= 1)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                assertTrue(o.getLong("objectId") > 0L)
                assertTrue(o.has("name"))
                assertTrue(o.has("extruder"))
            }
        } finally {
            fixture.delete()
        }
    }

    @Test
    fun native_get_object_extruder_map_matches_native_get_object_count() {
        val fixture = copyFixture("Flarewing-Dragon_100%_4FilamentMulticolor_v1.1.3mf")
        try {
            assertTrue(lib.loadModel(fixture.absolutePath))
            val json = lib.nativeGetObjectExtruderMap()
            assertNotNull(json)
            val arr = JSONArray(json!!)
            assertEquals("object array length must match nativeGetObjectCount",
                lib.nativeGetObjectCount(), arr.length())
        } finally {
            fixture.delete()
        }
    }
}
```

- [ ] **Step 2: Run**

```bash
./gradlew assembleDebugAndroidTest --no-daemon && \
  adb -s 43211JEKB16931 install -r -d app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk && \
  adb -s 43211JEKB16931 shell am instrument -w -r \
    -e class com.u1.slicer.native.NativeObjectExtruderMapTest \
    com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tail -6
```

Expected: OK (3 tests).

- [ ] **Step 3: Commit.**

---

### Task 8: Prune baseline + rerun differential

Same pattern as sub-plans #5/#2. Run diff suite, parse stale entries (should be 20 `objects.size`), prune, rerun, commit.

Expected post-prune baseline: **~0 entries** (the 20 `objects.size` are the last plate/object-level entries; nothing else remains in the baseline).

---

### Task 9: Regression — unit + Bambu package + all native tests

Same as sub-plans #5/#2.

---

### Task 10: Update docs + handoff

Append "Sub-plan #4 LANDED" section. Bump `CLAUDE.md` test counts. Commit.

---

## Exit criteria

- [ ] `BambuParserDifferentialTest` 21/21 green with baseline ~0 entries.
- [ ] `NativeObjectExtruderMapTest` 3/3 green.
- [ ] All prior native + bambu tests still green.
- [ ] `.so` rebuilt with `nativeGetObjectExtruderMap` exported, ~20MB, Clang 17.
- [ ] No production code outside the snapshot touched.
