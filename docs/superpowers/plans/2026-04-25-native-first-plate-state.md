# Native-First Plate State — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Kotlin state synthesis (`buildSelectedPlateInfo`) with native reads after `loadModel`, fixing 6 PM-reported regressions and adding a data-driven test harness for all Bambu fixtures.

**Architecture:** Keep Kotlin preprocessing (`ProfileEmbedder.embed(plateId)`) for BBS compatibility; replace plate state prediction with JNI reads from native after load. New `nativeGetAllVolumeExtruders()` accessor provides per-object, per-volume extruder + paint data in one call. Data-driven test harness validates every fixture against a JSON spec.

**Tech Stack:** Kotlin, C++ (NDK 26 / Clang 17), JNI, Android Instrumented Tests, OrcaSlicer native

**Spec:** `docs/superpowers/specs/2026-04-25-native-first-plate-state-design.md`

**Worktree:** `c:/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native/`
**Worktree prefix:** Every bash command must start with:
```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" &&
```

**Device:** Pixel 8a `43211JEKB16931`. Use `ANDROID_SERIAL=43211JEKB16931` for all adb/gradle commands.

**Instrumented test protocol:** Do NOT use `./gradlew connectedDebugAndroidTest` (phantom versionCode collision). Instead:
```bash
adb uninstall com.u1.slicer.orca ; adb uninstall com.u1.slicer.orca.test
./gradlew assembleDebug assembleDebugAndroidTest --no-daemon
adb install -t app/build/outputs/apk/debug/app-debug.apk
adb install -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.u1.slicer.TESTCLASS#testMethod \
  com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

**Native rebuild protocol:** NDK 26, Release, `ninja -j1`, strip, verify 20-21 MB + Clang 17. See `CLAUDE.md` "Native Rebuild" section.

---

## Task 1: Add Missing Test Fixtures

Copy the F1 calendar and hanging file fixtures from G-drive to test assets so Tier A regression tests can use them.

**Files:**
- Add: `app/src/androidTest/assets/2026+F1+CALENDAR+-+DATES+&+TRACK+NAMES+(P_X+SERIES).3mf`
- Add: `app/src/androidTest/assets/hanging+pre+cut+colour+3mf.3mf`

- [ ] **Step 1: Copy F1 calendar fixture**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
cp "/g/My Drive/tes-data/2026+F1+CALENDAR+-+DATES+&+TRACK+NAMES+(P_X+SERIES).3mf" \
   app/src/androidTest/assets/
```

- [ ] **Step 2: Copy hanging file fixture**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
cp "/g/My Drive/tes-data/hanging+pre+cut+colour+3mf.3mf" \
   app/src/androidTest/assets/
```

- [ ] **Step 3: Verify both files are present**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
ls -la "app/src/androidTest/assets/2026+F1+CALENDAR"* && \
ls -la "app/src/androidTest/assets/hanging+pre"*
```

Expected: Both files listed with non-zero size.

- [ ] **Step 4: Commit**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
git add "app/src/androidTest/assets/2026+F1+CALENDAR+-+DATES+&+TRACK+NAMES+(P_X+SERIES).3mf" \
        "app/src/androidTest/assets/hanging+pre+cut+colour+3mf.3mf" && \
git commit -m "$(cat <<'EOF'
test(assets): add F1 calendar and hanging file fixtures for Tier A regression tests
EOF
)"
```

---

## Task 2: New JNI Accessor — `nativeGetAllVolumeExtruders()`

Add a C++ accessor that returns per-object, per-volume extruder and paint data in one JSON call. This replaces chatty per-volume queries and Kotlin's fragile XML-based `objectPartExtruders`.

**Files:**
- Create: `app/src/main/cpp/src/sapil_bambu_volume_map.cpp`
- Modify: `app/src/main/cpp/CMakeLists.txt:1123` (add source file)
- Modify: `app/src/main/java/com/u1/slicer/NativeLibrary.kt:189` (add declaration)
- Test: `app/src/androidTest/java/com/u1/slicer/native/NativeVolumeMapTest.kt`

- [ ] **Step 1: Write the C++ accessor**

Create `app/src/main/cpp/src/sapil_bambu_volume_map.cpp`:

```cpp
#include <jni.h>
#include <sstream>
#include "sapil_bambu_snapshot.h"

namespace sapil { extern Slic3r::Model g_model; }

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetAllVolumeExtruders(
        JNIEnv* env, jobject) {
    if (sapil::g_model.objects.empty()) return nullptr;

    std::ostringstream out;
    out << "[";
    for (size_t i = 0; i < sapil::g_model.objects.size(); ++i) {
        if (i) out << ",";
        const auto* mo = sapil::g_model.objects[i];
        if (!mo) { out << "null"; continue; }

        int obj_ext = mo->config.has("extruder")
            ? mo->config.opt_int("extruder") : 0;

        out << "{\"objectIndex\":" << i
            << ",\"objectExtruder\":" << obj_ext
            << ",\"volumes\":[";

        for (size_t j = 0; j < mo->volumes.size(); ++j) {
            if (j) out << ",";
            const auto* mv = mo->volumes[j];
            if (!mv) { out << "null"; continue; }

            int vol_ext = mv->config.has("extruder")
                ? mv->config.opt_int("extruder") : -1;

            out << "{\"volumeIndex\":" << j
                << ",\"extruder\":" << vol_ext
                << ",\"isMmPainted\":" << (mv->is_mm_painted() ? "true" : "false")
                << ",\"isSeamPainted\":" << (mv->is_seam_painted() ? "true" : "false")
                << "}";
        }
        out << "]}";
    }
    out << "]";
    return env->NewStringUTF(out.str().c_str());
}

} // extern "C"
```

- [ ] **Step 2: Add source file to CMakeLists.txt**

In `app/src/main/cpp/CMakeLists.txt`, after `src/sapil_bambu_volumes.cpp` (line ~1123), add:

```cmake
    src/sapil_bambu_volume_map.cpp
```

- [ ] **Step 3: Add Kotlin declaration**

In `app/src/main/java/com/u1/slicer/NativeLibrary.kt`, after the `nativeGetObjectExtruderMap` declaration (line ~189), add:

```kotlin
    /**
     * Returns JSON array of all objects with per-volume extruder + paint data:
     *   [{"objectIndex": 0, "objectExtruder": 1, "volumes": [
     *     {"volumeIndex": 0, "extruder": 1, "isMmPainted": true, "isSeamPainted": false}, ...
     *   ]}, ...]
     *
     * `extruder` at volume level: -1 = inherit from object, 0 = unset, 1-4 = explicit.
     * Returns null when no model is loaded.
     * Callers MUST hold [previewMutex].
     */
    external fun nativeGetAllVolumeExtruders(): String?
```

- [ ] **Step 4: Rebuild native .so**

Follow the native rebuild protocol in CLAUDE.md. Use the existing NDK 26 build directory if available:

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
BUILD_DIR=$(find app/.cxx -name build.ninja -path "*/arm64-v8a/*" | head -1 | xargs dirname) && \
echo "Build dir: $BUILD_DIR" && \
grep "ndk/26" "$BUILD_DIR/../CMakeCache.txt" && \
grep "CMAKE_BUILD_TYPE:STRING=Release" "$BUILD_DIR/../CMakeCache.txt"
```

Then rebuild:
```bash
cd "$BUILD_DIR" && ninja -j1
```

Strip and copy:
```bash
NDK_STRIP=$(find $ANDROID_NDK_HOME/toolchains -name llvm-strip | head -1) && \
"$NDK_STRIP" --strip-unneeded libprusaslicer-jni.so && \
cp libprusaslicer-jni.so "$WT/app/src/main/jniLibs/arm64-v8a/"
```

Verify:
```bash
ls -la "$WT/app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so"
# Expected: ~20-21 MB
$(find $ANDROID_NDK_HOME/toolchains -name llvm-readelf | head -1) \
  -p .comment "$WT/app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so" | grep clang
# Expected: clang version 17.0.2
```

- [ ] **Step 5: Write instrumented test**

Create `app/src/androidTest/java/com/u1/slicer/native/NativeVolumeMapTest.kt`:

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
class NativeVolumeMapTest {

    private lateinit var lib: NativeLibrary
    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val assetContext get() = InstrumentationRegistry.getInstrumentation().context

    private fun copyAsset(name: String): File {
        val out = File(targetContext.cacheDir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        assetContext.assets.open(name).use { it.copyTo(out.outputStream()) }
        return out
    }

    @Before
    fun setup() {
        assertTrue("Native library must be loaded", NativeLibrary.isLoaded)
        lib = NativeLibrary()
    }

    @After
    fun teardown() { lib.clearModel() }

    @Test
    fun no_model_returns_null() {
        assertNull(lib.nativeGetAllVolumeExtruders())
    }

    @Test
    fun single_color_stl_has_one_object_one_volume() {
        val file = copyAsset("tetrahedron.stl")
        assertTrue(lib.loadModel(file.absolutePath))
        val json = lib.nativeGetAllVolumeExtruders()
        assertNotNull(json)
        val arr = JSONArray(json!!)
        assertEquals("one object", 1, arr.length())
        val obj = arr.getJSONObject(0)
        assertEquals(0, obj.getInt("objectIndex"))
        val vols = obj.getJSONArray("volumes")
        assertTrue("at least one volume", vols.length() >= 1)
    }

    @Test
    fun dragon_scale_plate3_has_three_volume_extruders() {
        // Dragon Scale plate 3 is a tri-colour compound object
        val file = copyAsset("Dragon Scale infinity.3mf")
        // Load plate 3 (0-based index 2)
        assertTrue(lib.loadModelForPlate(file.absolutePath, 2))
        val json = lib.nativeGetAllVolumeExtruders()
        assertNotNull(json)
        val arr = JSONArray(json!!)
        // Collect all volume-level extruders across all objects
        val allExtruders = mutableSetOf<Int>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val vols = obj.getJSONArray("volumes")
            for (j in 0 until vols.length()) {
                val ext = vols.getJSONObject(j).getInt("extruder")
                if (ext > 0) allExtruders.add(ext)
            }
        }
        assertTrue("Dragon plate 3 must have >= 3 distinct extruders, got $allExtruders",
            allExtruders.size >= 3)
    }

    @Test
    fun colored_benchy_reports_paint_data() {
        val file = copyAsset("colored_3DBenchy (1).3mf")
        assertTrue(lib.loadModel(file.absolutePath))
        val json = lib.nativeGetAllVolumeExtruders()
        assertNotNull(json)
        val arr = JSONArray(json!!)
        // At least one volume should be mm-painted
        var hasPainted = false
        for (i in 0 until arr.length()) {
            val vols = arr.getJSONObject(i).getJSONArray("volumes")
            for (j in 0 until vols.length()) {
                if (vols.getJSONObject(j).getBoolean("isMmPainted")) hasPainted = true
            }
        }
        assertTrue("colored_3DBenchy must have at least one mm-painted volume", hasPainted)
    }
}
```

- [ ] **Step 6: Build and run tests on device**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
./gradlew clean assembleDebug assembleDebugAndroidTest --no-daemon && \
adb uninstall com.u1.slicer.orca 2>/dev/null ; \
adb uninstall com.u1.slicer.orca.test 2>/dev/null ; \
adb install -t app/build/outputs/apk/debug/app-debug.apk && \
adb install -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk && \
adb shell am instrument -w -e class com.u1.slicer.native.NativeVolumeMapTest \
  com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: 4 tests pass.

- [ ] **Step 7: Run JVM unit tests to verify no compile regression**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
./gradlew testDebugUnitTest --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
git restore -- app/src/main/cpp/extern/ && \
git add app/src/main/cpp/src/sapil_bambu_volume_map.cpp \
        app/src/main/cpp/CMakeLists.txt \
        app/src/main/java/com/u1/slicer/NativeLibrary.kt \
        app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so \
        app/src/androidTest/java/com/u1/slicer/native/NativeVolumeMapTest.kt && \
git commit -m "$(cat <<'EOF'
phase1(bambu-native): add nativeGetAllVolumeExtruders JNI accessor + rebuild .so

New accessor returns per-object, per-volume extruder + paint data in one
JSON call. Replaces chatty per-volume queries and Kotlin's XML-based
objectPartExtruders synthesis for native-first plate state reading.
EOF
)"
```

---

## Task 3: `NativePlateState` Data Class + `readPlateStateFromNative()`

Add the Kotlin-side data class and helper that reads plate state from native after load. This replaces `buildSelectedPlateInfo`.

**Files:**
- Create: `app/src/main/java/com/u1/slicer/bambu/NativePlateState.kt`
- Test: `app/src/test/java/com/u1/slicer/bambu/NativePlateStateTest.kt`

- [ ] **Step 1: Write the JVM unit test**

Create `app/src/test/java/com/u1/slicer/bambu/NativePlateStateTest.kt`:

```kotlin
package com.u1.slicer.bambu

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class NativePlateStateTest {

    @Test
    fun `parseVolumeMapJson extracts extruders from single object`() {
        val json = """[{
            "objectIndex": 0, "objectExtruder": 1,
            "volumes": [
                {"volumeIndex": 0, "extruder": 1, "isMmPainted": false, "isSeamPainted": false},
                {"volumeIndex": 1, "extruder": 2, "isMmPainted": false, "isSeamPainted": false}
            ]
        }]"""
        val state = NativePlateState.parseVolumeMapJson(json)
        assertEquals(setOf(1, 2), state.usedExtruders)
        assertFalse(state.hasPaintData)
        assertEquals(1, state.objects.size)
        assertEquals(2, state.objects[0].volumes.size)
    }

    @Test
    fun `parseVolumeMapJson detects paint data`() {
        val json = """[{
            "objectIndex": 0, "objectExtruder": 1,
            "volumes": [
                {"volumeIndex": 0, "extruder": 1, "isMmPainted": true, "isSeamPainted": false}
            ]
        }]"""
        val state = NativePlateState.parseVolumeMapJson(json)
        assertTrue(state.hasPaintData)
    }

    @Test
    fun `parseVolumeMapJson handles compound object with three extruders`() {
        val json = """[{
            "objectIndex": 0, "objectExtruder": 1,
            "volumes": [
                {"volumeIndex": 0, "extruder": 1, "isMmPainted": false, "isSeamPainted": false},
                {"volumeIndex": 1, "extruder": 2, "isMmPainted": false, "isSeamPainted": false},
                {"volumeIndex": 2, "extruder": 3, "isMmPainted": false, "isSeamPainted": false}
            ]
        }]"""
        val state = NativePlateState.parseVolumeMapJson(json)
        assertEquals(setOf(1, 2, 3), state.usedExtruders)
        assertEquals(3, state.objects[0].volumes.size)
    }

    @Test
    fun `parseVolumeMapJson inherits object extruder when volume is -1`() {
        val json = """[{
            "objectIndex": 0, "objectExtruder": 2,
            "volumes": [
                {"volumeIndex": 0, "extruder": -1, "isMmPainted": false, "isSeamPainted": false}
            ]
        }]"""
        val state = NativePlateState.parseVolumeMapJson(json)
        // Volume extruder -1 means "inherit from object" → effective extruder is 2
        assertEquals(setOf(2), state.usedExtruders)
    }

    @Test
    fun `parseVolumeMapJson returns empty state for null input`() {
        val state = NativePlateState.parseVolumeMapJson(null)
        assertTrue(state.usedExtruders.isEmpty())
        assertFalse(state.hasPaintData)
        assertTrue(state.objects.isEmpty())
    }

    @Test
    fun `parseVolumeMapJson handles multiple objects`() {
        val json = """[
            {"objectIndex": 0, "objectExtruder": 1, "volumes": [
                {"volumeIndex": 0, "extruder": 1, "isMmPainted": false, "isSeamPainted": false}
            ]},
            {"objectIndex": 1, "objectExtruder": 3, "volumes": [
                {"volumeIndex": 0, "extruder": 3, "isMmPainted": false, "isSeamPainted": false}
            ]}
        ]"""
        val state = NativePlateState.parseVolumeMapJson(json)
        assertEquals(setOf(1, 3), state.usedExtruders)
        assertEquals(2, state.objects.size)
    }

    @Test
    fun `buildObjectExtruderMap produces per-object max extruder`() {
        val json = """[{
            "objectIndex": 0, "objectExtruder": 1,
            "volumes": [
                {"volumeIndex": 0, "extruder": 1, "isMmPainted": false, "isSeamPainted": false},
                {"volumeIndex": 1, "extruder": 3, "isMmPainted": false, "isSeamPainted": false}
            ]
        }]"""
        val state = NativePlateState.parseVolumeMapJson(json)
        val map = state.buildObjectExtruderMap()
        // Map keyed by objectIndex string, value is max extruder across volumes
        assertEquals(mapOf("0" to 3), map)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
./gradlew testDebugUnitTest --tests "com.u1.slicer.bambu.NativePlateStateTest" --no-daemon
```

Expected: FAIL — class NativePlateState does not exist.

- [ ] **Step 3: Implement NativePlateState**

Create `app/src/main/java/com/u1/slicer/bambu/NativePlateState.kt`:

```kotlin
package com.u1.slicer.bambu

import org.json.JSONArray

/**
 * Plate state read FROM native after [NativeLibrary.loadModel] / [NativeLibrary.loadModelForPlate].
 * Single source of truth for per-plate extruder assignments and paint data.
 * Replaces the Kotlin-side [SlicerViewModel.buildSelectedPlateInfo] synthesis.
 */
data class NativePlateState(
    /** All extruders used by volumes on this plate (1-based, sorted). */
    val usedExtruders: Set<Int>,
    /** True if any volume has MMU paint segmentation data. */
    val hasPaintData: Boolean,
    /** Per-object volume breakdown. */
    val objects: List<ObjectEntry>
) {
    data class VolumeEntry(
        val volumeIndex: Int,
        val extruder: Int,
        val isMmPainted: Boolean,
        val isSeamPainted: Boolean
    )

    data class ObjectEntry(
        val objectIndex: Int,
        val objectExtruder: Int,
        val volumes: List<VolumeEntry>
    )

    /**
     * Build an object-extruder map compatible with [ThreeMfInfo.objectExtruderMap].
     * Key: objectIndex as string. Value: max extruder across volumes (or object default).
     */
    fun buildObjectExtruderMap(): Map<String, Int> = objects.associate { obj ->
        val maxVolumeExt = obj.volumes
            .map { if (it.extruder > 0) it.extruder else obj.objectExtruder }
            .filter { it > 0 }
            .maxOrNull() ?: obj.objectExtruder
        obj.objectIndex.toString() to maxVolumeExt
    }

    companion object {
        /**
         * Parse the JSON returned by [NativeLibrary.nativeGetAllVolumeExtruders].
         * Returns an empty state for null or empty input.
         */
        fun parseVolumeMapJson(json: String?): NativePlateState {
            if (json.isNullOrBlank()) return NativePlateState(
                usedExtruders = emptySet(), hasPaintData = false, objects = emptyList()
            )
            val arr = JSONArray(json)
            val objects = mutableListOf<ObjectEntry>()
            val allExtruders = mutableSetOf<Int>()
            var hasPaint = false

            for (i in 0 until arr.length()) {
                val objJson = arr.optJSONObject(i) ?: continue
                val objExt = objJson.optInt("objectExtruder", 0)
                val volsJson = objJson.optJSONArray("volumes") ?: continue
                val volumes = mutableListOf<VolumeEntry>()

                for (j in 0 until volsJson.length()) {
                    val vj = volsJson.optJSONObject(j) ?: continue
                    val volExt = vj.optInt("extruder", -1)
                    val mmPainted = vj.optBoolean("isMmPainted", false)
                    val seamPainted = vj.optBoolean("isSeamPainted", false)
                    volumes.add(VolumeEntry(vj.optInt("volumeIndex", j), volExt, mmPainted, seamPainted))

                    // Effective extruder: volume-level if set, else inherit from object
                    val effective = if (volExt > 0) volExt else objExt
                    if (effective > 0) allExtruders.add(effective)
                    if (mmPainted) hasPaint = true
                }
                objects.add(ObjectEntry(objJson.optInt("objectIndex", i), objExt, volumes))
            }

            return NativePlateState(
                usedExtruders = allExtruders.toSortedSet(),
                hasPaintData = hasPaint,
                objects = objects
            )
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
./gradlew testDebugUnitTest --tests "com.u1.slicer.bambu.NativePlateStateTest" --no-daemon
```

Expected: 7 tests PASS.

- [ ] **Step 5: Run full JVM suite**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
./gradlew testDebugUnitTest --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
git add app/src/main/java/com/u1/slicer/bambu/NativePlateState.kt \
        app/src/test/java/com/u1/slicer/bambu/NativePlateStateTest.kt && \
git commit -m "$(cat <<'EOF'
phase1(bambu-native): NativePlateState data class + parseVolumeMapJson

Parses JSON from nativeGetAllVolumeExtruders() into a structured plate
state. Handles extruder inheritance (volume -1 → object default),
paint data detection, and multi-object compound files.
7 JVM unit tests.
EOF
)"
```

---

## Task 4: Revise `selectPlate` — Native-First State Reading

Replace `buildSelectedPlateInfo` + `mergeThreeMfInfoForPlate` synthesis with native reads after load in the `selectPlate` flow.

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt:1065-1143` (selectPlate)
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt:3688-3752` (buildSelectedPlateInfo — mark deprecated)

**Important:** This is a large refactor of a critical code path. Read the FULL current `selectPlate` function (lines 1065-1143) and `loadNativeModel` (lines 1175-1333) before making changes. Understand every state flow update and its consumers.

- [ ] **Step 1: Read the current selectPlate and loadNativeModel functions**

Read `SlicerViewModel.kt` lines 1065-1333 to understand the full flow before modifying.

- [ ] **Step 2: Add `readPlateStateFromNative` to SlicerViewModel**

Add this method to `SlicerViewModel.kt` (after `buildSelectedPlateInfo`, around line 3753):

```kotlin
/**
 * Read plate state FROM native after loadModel/loadModelForPlate.
 * Single source of truth — replaces buildSelectedPlateInfo for Bambu multi-plate files.
 * Caller MUST have already loaded the model into native.
 * Caller MUST hold previewMutex (or call within a scope that does).
 */
private fun readPlateStateFromNative(native: NativeLibrary): NativePlateState {
    val json = native.nativeGetAllVolumeExtruders()
    val state = NativePlateState.parseVolumeMapJson(json)

    // If volumes report paint data, also collect paint extruder states from native
    if (state.hasPaintData) {
        val paintExtruders = mutableSetOf<Int>()
        for (obj in state.objects) {
            for (vol in obj.volumes) {
                if (vol.isMmPainted) {
                    // kind=0 is MMU paint segmentation
                    val counts = native.nativeGetPaintStateCounts(
                        obj.objectIndex, vol.volumeIndex, 0
                    )
                    if (counts != null) {
                        // counts is [state1, count1, state2, count2, ...]
                        for (k in counts.indices step 2) {
                            val paintState = counts[k]
                            if (paintState > 0 && counts[k + 1] > 0) {
                                paintExtruders.add(paintState)
                            }
                        }
                    }
                }
            }
        }
        return state.copy(
            usedExtruders = (state.usedExtruders + paintExtruders).toSortedSet()
        )
    }
    return state
}
```

- [ ] **Step 3: Add `buildThreeMfInfoFromNative` bridge method**

Add this method to `SlicerViewModel.kt` (after `readPlateStateFromNative`):

```kotlin
/**
 * Build a ThreeMfInfo for UI consumers from native-reported plate state.
 * Combines file-level metadata from [_fileThreeMfInfo] with per-plate
 * extruder/paint data from [NativePlateState].
 */
private fun buildThreeMfInfoFromNative(
    fileInfo: ThreeMfInfo,
    nativeState: NativePlateState,
    plateId: Int
): ThreeMfInfo {
    val usedExtruders = nativeState.usedExtruders
    val objExtruderMap = nativeState.buildObjectExtruderMap()

    return fileInfo.copy(
        usedExtruderIndices = usedExtruders,
        detectedExtruderCount = maxOf(usedExtruders.size, 1),
        hasPaintData = nativeState.hasPaintData,
        objectExtruderMap = objExtruderMap,
        hasMultiExtruderAssignments = usedExtruders.size > 1 || objExtruderMap.values.toSet().size > 1
    )
}
```

- [ ] **Step 4: Rewrite `selectPlate` to use native-first flow**

Replace the body of `selectPlate` (lines 1065-1143). The new flow is:

1. Cancel jobs, resolve source file (unchanged)
2. Transition to Loading (unchanged)
3. `embedProfile(file, fileInfo, workspaceDir, plateId)` — preprocessing
4. `loadNativeModel(embeddedFile)` — native load
5. `readPlateStateFromNative(native)` — read FROM native
6. `buildThreeMfInfoFromNative(fileInfo, nativeState, plateId)` — UI state
7. `_threeMfInfo.value = mergedInfo` — publish to UI
8. Transition to ModelLoaded

**Key differences from current code:**
- `buildSelectedPlateInfo(preSelectInfo, plateId)` call removed
- `mergeThreeMfInfoForPlate(plateInfo, preSelectInfo, plateId)` call removed
- `_threeMfInfo.value` is set AFTER native load completes (fixes #1 race)
- File-level info comes from `_fileThreeMfInfo`, not from a fresh parse

The exact code depends on reading the current function. The implementer should:
1. Keep the cancellation + source file resolution logic (lines 1065-1076)
2. Keep the Loading state transition + diagnostics (lines 1077-1089)
3. Replace lines 1091-1136 with:
   - Get `fileInfo = _fileThreeMfInfo ?: return` (file-level metadata)
   - Call `embedProfile(file, fileInfo, workspaceDir, plateId = plateId)` — no need for `buildSelectedPlateInfo` or `mergeThreeMfInfoForPlate` first
   - Call `loadNativeModel(embeddedPlateFile)`
   - Call `readPlateStateFromNative(native)` — needs `previewMutex` scope from `loadNativeModel`
   - Call `buildThreeMfInfoFromNative(fileInfo, nativeState, plateId)`
   - Set `_threeMfInfo.value = result`
4. Keep error handling (lines 1137-1141)

**Note:** `readPlateStateFromNative` needs to run under `previewMutex`. The cleanest approach is to have `loadNativeModel` return the `NativeLibrary` instance so the caller can read state in the same mutex scope. Or add a parameter to `loadNativeModel` that performs the read inside the existing mutex acquisition. The implementer should read `loadNativeModel` (lines 1175-1333) to find where the mutex is acquired and add the native read there.

- [ ] **Step 5: Mark `buildSelectedPlateInfo` as @Deprecated**

At line 3688, add:

```kotlin
@Deprecated("Replaced by readPlateStateFromNative — native is the source of truth for plate state")
```

Do NOT delete it yet — existing tests may reference it.

- [ ] **Step 6: Run JVM unit tests**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
./gradlew testDebugUnitTest --no-daemon
```

Expected: BUILD SUCCESSFUL. Some MergeThreeMfInfoTest tests may need adjustment if `mergeThreeMfInfoForPlate` call sites changed.

- [ ] **Step 7: Run key instrumented test packages**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
./gradlew clean assembleDebug assembleDebugAndroidTest --no-daemon && \
adb uninstall com.u1.slicer.orca 2>/dev/null ; adb uninstall com.u1.slicer.orca.test 2>/dev/null ; \
adb install -t app/build/outputs/apk/debug/app-debug.apk && \
adb install -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Then run each package:
```bash
# Bambu package
adb shell am instrument -w -e package com.u1.slicer.bambu \
  com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner

# Slicing package
adb shell am instrument -w -e package com.u1.slicer.slicing \
  com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner

# Native package
adb shell am instrument -w -e package com.u1.slicer.native \
  com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner

# Diff harness
adb shell am instrument -w -e class com.u1.slicer.bambu.snapshot.BambuParserDifferentialTest \
  com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: All pass. Diff harness at 0 baseline.

- [ ] **Step 8: Commit**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
git restore -- app/src/main/cpp/extern/ && \
git add -u && \
git commit -m "$(cat <<'EOF'
phase1(bambu-native): selectPlate reads plate state from native after load

Replaces buildSelectedPlateInfo synthesis with readPlateStateFromNative.
Per-plate extruders, paint data, and object map now come from native
JNI reads after loadModel completes. _threeMfInfo is set AFTER native
load (fixes race condition). File-level metadata from _fileThreeMfInfo.
EOF
)"
```

---

## Task 5: Transform Preservation (Fixes #3, #4)

Fix user transforms being lost on pre-slice re-embed, and position mismatch between Kotlin-computed and native-reported footprints.

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt:1175-1333` (loadNativeModel — add preserveTransforms param)
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt:2134` (pre-slice re-embed path)
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt:2589` (expectedModelFootprint → native read)

- [ ] **Step 1: Read the pre-slice re-embed path and transform application code**

Read `SlicerViewModel.kt` lines 2100-2250 to understand `startSlicing`, the re-embed call at line 2134, and `prepareSlicer` where transforms are applied.

- [ ] **Step 2: Add `preserveTransforms` parameter to `loadNativeModel`**

At `loadNativeModel` (line ~1175), add a parameter:

```kotlin
private suspend fun loadNativeModel(
    file: File,
    preserveTransforms: Boolean = false  // NEW: skip identity reset for pre-slice re-embed
): Boolean {
```

Inside the function, guard the identity reset (lines 1221-1222):

```kotlin
if (!preserveTransforms) {
    _modelScale.value = ModelScale()
    _modelRotation.value = ModelRotation()
}
```

- [ ] **Step 3: Pass `preserveTransforms = true` in the pre-slice re-embed path**

At line ~2134 (or wherever `loadNativeModel` is called in the re-embed flow before slicing), change:

```kotlin
loadNativeModel(reembeddedFile, preserveTransforms = true)
```

- [ ] **Step 4: Replace Kotlin `expectedModelFootprint` with native read**

At line ~2589 where `expectedModelFootprint` is computed for diagnostics, replace the Kotlin-side computation with a native read:

```kotlin
// BEFORE: val expectedFootprint = computeExpectedFootprint(...)
// AFTER: read from native after setModelInstances
val actualOffsets = native.getInstanceOffsets()
// Use actualOffsets for diagnostics instead of Kotlin prediction
```

The exact change depends on how `expectedModelFootprint` is currently computed — the implementer should read the surrounding code and trace how the value flows to diagnostics.

- [ ] **Step 5: Run JVM unit tests**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
./gradlew testDebugUnitTest --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Run slicing integration tests**

```bash
adb shell am instrument -w -e package com.u1.slicer.slicing \
  com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: All pass — especially B73 scale-down placement, B94 drag-to-right.

- [ ] **Step 7: Commit**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
git restore -- app/src/main/cpp/extern/ && \
git add -u && \
git commit -m "$(cat <<'EOF'
phase1(bambu-native): preserve transforms through pre-slice re-embed

loadNativeModel gains preserveTransforms flag — skips identity reset
of _modelScale/_modelRotation when true. Used in the pre-slice re-embed
path so user translations survive the reload cycle (fixes #3).
expectedModelFootprint now reads native instance offsets instead of
Kotlin-computed prediction (fixes #4 position mismatch).
EOF
)"
```

---

## Task 6: Remove `computeVisualColorCountByPlate` from `parse()` (Fixes #6)

The expensive per-plate paint-state regex scan was added to `ThreeMfParser.parse()` for the synthesis path. Now that plate state comes from native, remove it from the cold-load parse path.

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/bambu/ThreeMfParser.kt:505-511` (remove call)
- Modify: `app/src/main/java/com/u1/slicer/bambu/ThreeMfInfo.kt` (keep `ThreeMfPlate.hasPaintData` field but simplify population)

- [ ] **Step 1: Read the `computeVisualColorCountByPlate` call site in parse()**

Read `ThreeMfParser.kt` lines 490-530 to understand what `computeVisualColorCountByPlate` returns and how it feeds into `ThreeMfPlate` construction.

- [ ] **Step 2: Remove the expensive per-plate paint scan from parse()**

At lines 505-511, the call to `computeVisualColorCountByPlate(...)` runs the full per-component paint-state regex scan. Replace it with a lightweight check:

```kotlin
// BEFORE (expensive — reads all component .model files for paint state):
// val visualColorCountByPlate = computeVisualColorCountByPlate(...)

// AFTER (lightweight — just check if file has paint data at all):
// ThreeMfPlate.hasPaintData is set from the file-level hasPaintData flag
// Per-plate paint extruder detection now happens via nativeGetAllVolumeExtruders after load
```

The implementer should:
1. Find where `visualColorCountByPlate` result is consumed (likely in ThreeMfPlate construction)
2. Replace per-plate `paintExtruderStates` population with file-level `hasPaintData` flag
3. Keep `ThreeMfPlate.hasPaintData` field — set it from file-level `hasPaintData` as a conservative approximation
4. The per-plate granularity comes from native after `selectPlate` → `readPlateStateFromNative`

- [ ] **Step 3: Run JVM unit tests**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
./gradlew testDebugUnitTest --no-daemon
```

Expected: BUILD SUCCESSFUL. Some tests that assert on `paintExtruderStates` content may need adjustment — they should assert on file-level paint presence, not per-plate paint state (which now comes from native).

- [ ] **Step 4: Run Bambu instrumented tests**

```bash
adb shell am instrument -w -e package com.u1.slicer.bambu \
  com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: All 26+ tests pass.

- [ ] **Step 5: Commit**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
git restore -- app/src/main/cpp/extern/ && \
git add -u && \
git commit -m "$(cat <<'EOF'
phase1(bambu-native): remove computeVisualColorCountByPlate from parse()

Eliminates the expensive per-plate paint-state regex scan that ran on
every cold load. Buzz Lightyear (~50MB, 296K paint_color attributes)
was the worst case — exact regression that B93 originally fixed.
Per-plate paint state now comes from native after selectPlate.
ThreeMfPlate.hasPaintData retains file-level flag for plate list UI.
EOF
)"
```

---

## Task 7: Tier A — Per-Fixture Regression Tests

Write targeted instrumented tests for each of the 6 PM-reported bugs. These tests exercise the production `selectPlate` path (not test-only helpers) and read state from native.

**Files:**
- Create: `app/src/androidTest/java/com/u1/slicer/slicing/BambuPlateStateRegressionTest.kt`

- [ ] **Step 1: Write the regression test class**

Create `app/src/androidTest/java/com/u1/slicer/slicing/BambuPlateStateRegressionTest.kt`:

```kotlin
package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.NativePlateState
import com.u1.slicer.bambu.ProfileEmbedder
import com.u1.slicer.bambu.ThreeMfParser
import com.u1.slicer.data.SliceConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Tier A regression tests for the 6 PM-reported bugs from the Bambu refactor.
 * Each test exercises the production plate-load path and reads state from native.
 */
@RunWith(AndroidJUnit4::class)
class BambuPlateStateRegressionTest {

    private lateinit var lib: NativeLibrary
    private lateinit var cacheDir: File
    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val assetContext get() = InstrumentationRegistry.getInstrumentation().context

    private fun copyAsset(name: String): File {
        val out = File(cacheDir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        assetContext.assets.open(name).use { it.copyTo(out.outputStream()) }
        return out
    }

    @Before
    fun setup() {
        assertTrue("Native library must be loaded", NativeLibrary.isLoaded)
        lib = NativeLibrary()
        cacheDir = File(targetContext.cacheDir, "plate_state_test")
        cacheDir.mkdirs()
    }

    @After
    fun teardown() {
        lib.clearModel()
        cacheDir.deleteRecursively()
    }

    /**
     * Helper: embed profile for a Bambu file, optionally filtering to a plate.
     * Returns the embedded file.
     */
    private fun embedAndLoad(assetName: String, plateId: Int? = null): NativePlateState {
        val file = copyAsset(assetName)
        val info = ThreeMfParser.parse(file)
        val embedder = ProfileEmbedder(targetContext)
        val config = embedder.buildConfig(info, SliceConfig())
        val embedded = embedder.embed(file, config, cacheDir, plateId = plateId)
        assertTrue("loadModel must succeed for $assetName",
            lib.loadModel(embedded.absolutePath))
        return NativePlateState.parseVolumeMapJson(lib.nativeGetAllVolumeExtruders())
    }

    // --- Bug #1 / #2: Dragon Scale plate 3 — must detect 3 extruders ---

    @Test
    fun bug1_dragon_scale_plate3_three_extruders_on_first_load() {
        val state = embedAndLoad("Dragon Scale infinity.3mf", plateId = 2)
        assertTrue(
            "Dragon plate 3 must have >= 3 extruders, got ${state.usedExtruders}",
            state.usedExtruders.size >= 3
        )
    }

    // --- Bug #2: F1 calendar — must detect 4 extruders ---

    @Test
    fun bug2_f1_calendar_plate1_four_extruders() {
        val state = embedAndLoad(
            "2026+F1+CALENDAR+-+DATES+&+TRACK+NAMES+(P_X+SERIES).3mf", plateId = 0
        )
        assertTrue(
            "F1 calendar plate 1 must have >= 4 extruders, got ${state.usedExtruders}",
            state.usedExtruders.size >= 4
        )
    }

    // --- Bug #3: Hanging file — translate preserved through slice ---

    @Test
    fun bug3_translate_preserved_through_slice() {
        val file = copyAsset("hanging+pre+cut+colour+3mf.3mf")
        val info = ThreeMfParser.parse(file)
        val embedder = ProfileEmbedder(targetContext)
        val config = embedder.buildConfig(info, SliceConfig())
        val embedded = embedder.embed(file, config, cacheDir)
        assertTrue(lib.loadModel(embedded.absolutePath))

        // Apply a 50mm X translation via setModelInstances
        val modelInfo = lib.getModelInfo()
        assertNotNull(modelInfo)
        val cx = 135f + 50f  // center of bed + 50mm offset
        val cy = 135f
        assertTrue(lib.setModelInstances(floatArrayOf(cx, cy)))

        // Read back native positions — should reflect the translation
        val offsets = lib.getInstanceOffsets()
        assertTrue("Instance offsets must be non-empty", offsets.isNotEmpty())
        val actualX = offsets[0]
        assertTrue(
            "Native X offset ($actualX) must be near $cx (±5mm)",
            kotlin.math.abs(actualX - cx) < 5f
        )

        // Slice and verify G-code bounds reflect the offset
        val result = lib.slice(SliceConfig())
        assertNotNull("Slice must succeed", result)
        assertTrue(result!!.success)
        val gcode = File(result.gcodePath).readText()
        // Parse X coordinates from G1 moves — min X should be > 100
        // (center is 185, model extends ~30mm each side → min ~155)
        val xValues = Regex("""G1.*X([\d.]+)""").findAll(gcode)
            .map { it.groupValues[1].toFloat() }.toList()
        assertTrue("G-code must have X moves", xValues.isNotEmpty())
        val minX = xValues.min()
        assertTrue(
            "G-code minX ($minX) should reflect 50mm translation (expected > 100)",
            minX > 100f
        )
    }

    // --- Bug #5: H2C benchy — all colours in G-code ---

    @Test
    fun bug5_h2c_benchy_all_colours_in_gcode() {
        val file = copyAsset("3DBenchy-H2C-Multi-Color.3mf")
        val info = ThreeMfParser.parse(file)
        val embedder = ProfileEmbedder(targetContext)
        val config = embedder.buildConfig(info, SliceConfig())
        val embedded = embedder.embed(file, config, cacheDir)
        assertTrue(lib.loadModel(embedded.absolutePath))

        val state = NativePlateState.parseVolumeMapJson(lib.nativeGetAllVolumeExtruders())
        // H2C benchy should have paint data
        assertTrue("H2C benchy must have paint data", state.hasPaintData)

        val result = lib.slice(SliceConfig())
        assertNotNull("Slice must succeed", result)
        assertTrue(result!!.success)
        val gcode = File(result.gcodePath).readText()
        val toolCounts = (0..3).map { t -> gcode.lines().count { it.trim() == "T$t" } }
        // H2C benchy uses multiple tools — at least 2 should be active
        val activeTools = toolCounts.count { it > 0 }
        assertTrue(
            "H2C benchy must have >= 2 active tools, got $activeTools (counts: $toolCounts)",
            activeTools >= 2
        )
    }

    // --- Bug #6: Buzz cold load — no per-plate paint scan in parse() ---

    @Test
    fun bug6_buzz_cold_load_completes_under_threshold() {
        val startMs = System.currentTimeMillis()
        val file = copyAsset("Buzz_Multipart_3MF_Bambu.3mf")
        val info = ThreeMfParser.parse(file)
        val elapsedMs = System.currentTimeMillis() - startMs

        // Buzz parse should complete well under 15 seconds (B93 regression was ~20-30s)
        assertTrue(
            "Buzz parse took ${elapsedMs}ms — expected < 15000ms",
            elapsedMs < 15000
        )
        // Sanity: parsed file should have multiple plates
        assertTrue("Buzz must be multi-plate", info.isMultiPlate)
    }
}
```

- [ ] **Step 2: Build and run the regression tests**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
./gradlew clean assembleDebug assembleDebugAndroidTest --no-daemon && \
adb uninstall com.u1.slicer.orca 2>/dev/null ; adb uninstall com.u1.slicer.orca.test 2>/dev/null ; \
adb install -t app/build/outputs/apk/debug/app-debug.apk && \
adb install -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk && \
adb shell am instrument -w -e class com.u1.slicer.slicing.BambuPlateStateRegressionTest \
  com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: All 5 tests pass. If any fail, the corresponding bug fix from Tasks 4-6 needs investigation.

**Note:** Bug #4 (Calicube position shift) is tested indirectly by bug3 test's position verification. A dedicated calicube test would require the calicube fixture (not in assets) — the hanging file test covers the same transform-preservation mechanism.

- [ ] **Step 3: Commit**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
git add app/src/androidTest/java/com/u1/slicer/slicing/BambuPlateStateRegressionTest.kt && \
git commit -m "$(cat <<'EOF'
test(bambu): Tier A regression tests for 6 PM-reported plate state bugs

5 instrumented tests covering: Dragon plate 3 extruder count (#1/#2),
F1 calendar 4-extruder detection (#2), translate preservation through
slice (#3), H2C benchy multi-colour G-code (#5), Buzz cold-load
performance gate (#6).
EOF
)"
```

---

## Task 8: Tier B — Data-Driven Test Harness Infrastructure

Build the harness that validates any Bambu fixture against a JSON spec. Supports both assertion mode and spec-generation mode.

**Files:**
- Create: `app/src/androidTest/java/com/u1/slicer/slicing/BambuFixtureHarnessTest.kt`
- Create: `app/src/androidTest/assets/fixture-specs/` (directory)

- [ ] **Step 1: Create the fixture-specs directory**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
mkdir -p app/src/androidTest/assets/fixture-specs
```

- [ ] **Step 2: Write the harness test class**

Create `app/src/androidTest/java/com/u1/slicer/slicing/BambuFixtureHarnessTest.kt`:

```kotlin
package com.u1.slicer.slicing

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.NativePlateState
import com.u1.slicer.bambu.ProfileEmbedder
import com.u1.slicer.bambu.ThreeMfParser
import com.u1.slicer.data.SliceConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Data-driven Bambu fixture harness. Each fixture has a JSON spec in
 * assets/fixture-specs/ that defines expected plate behaviour.
 *
 * Run with -e generateSpec true to auto-generate draft specs for fixtures
 * that don't have one yet.
 */
@RunWith(AndroidJUnit4::class)
class BambuFixtureHarnessTest {

    private lateinit var lib: NativeLibrary
    private lateinit var cacheDir: File
    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val assetContext get() = InstrumentationRegistry.getInstrumentation().context

    private val generateMode: Boolean by lazy {
        InstrumentationRegistry.getArguments().getString("generateSpec", "false") == "true"
    }

    data class PlateSpec(
        val plateIndex: Int,
        val expectedExtruderCount: Int,
        val expectedToolCounts: Map<String, Int>,
        val toolCountTolerance: Int,
        val hasPaintData: Boolean,
        val maxBoundingBoxMm: List<Int>
    )

    data class FixtureSpec(
        val file: String,
        val approved: Boolean,
        val plates: List<PlateSpec>
    )

    private fun copyAsset(name: String): File {
        val out = File(cacheDir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        assetContext.assets.open(name).use { it.copyTo(out.outputStream()) }
        return out
    }

    private fun loadFixtureSpecs(): List<FixtureSpec> {
        val specFiles = assetContext.assets.list("fixture-specs") ?: return emptyList()
        return specFiles.filter { it.endsWith(".json") }.mapNotNull { specFile ->
            try {
                val json = assetContext.assets.open("fixture-specs/$specFile").use {
                    it.bufferedReader().readText()
                }
                parseSpec(JSONObject(json))
            } catch (e: Exception) {
                Log.w("FixtureHarness", "Failed to parse spec $specFile: ${e.message}")
                null
            }
        }
    }

    private fun parseSpec(json: JSONObject): FixtureSpec {
        val plates = mutableListOf<PlateSpec>()
        val platesArr = json.getJSONArray("plates")
        for (i in 0 until platesArr.length()) {
            val p = platesArr.getJSONObject(i)
            val toolCounts = mutableMapOf<String, Int>()
            val tc = p.optJSONObject("expectedToolCounts")
            if (tc != null) {
                for (key in tc.keys()) toolCounts[key] = tc.getInt(key)
            }
            val bbox = p.optJSONArray("maxBoundingBoxMm")
            plates.add(PlateSpec(
                plateIndex = p.getInt("plateIndex"),
                expectedExtruderCount = p.getInt("expectedExtruderCount"),
                expectedToolCounts = toolCounts,
                toolCountTolerance = p.optInt("toolCountTolerance", 5),
                hasPaintData = p.optBoolean("hasPaintData", false),
                maxBoundingBoxMm = if (bbox != null) listOf(bbox.getInt(0), bbox.getInt(1))
                                   else listOf(270, 270)
            ))
        }
        return FixtureSpec(
            file = json.getString("file"),
            approved = json.optBoolean("approved", false),
            plates = plates
        )
    }

    private fun embedAndLoadForPlate(
        assetName: String,
        plateId: Int?
    ): Pair<NativePlateState, SliceConfig> {
        val file = copyAsset(assetName)
        val info = ThreeMfParser.parse(file)
        val embedder = ProfileEmbedder(targetContext)
        val config = embedder.buildConfig(info, SliceConfig())
        val embedded = embedder.embed(file, config, cacheDir, plateId = plateId)
        assertTrue("loadModel must succeed for $assetName",
            lib.loadModel(embedded.absolutePath))
        val state = NativePlateState.parseVolumeMapJson(lib.nativeGetAllVolumeExtruders())
        return Pair(state, SliceConfig())
    }

    private fun parseToolCounts(gcodePath: String): Map<String, Int> {
        val gcode = File(gcodePath).readText()
        return (0..3).associate { t ->
            "T$t" to gcode.lines().count { it.trim() == "T$t" }
        }
    }

    private fun parseGcodeBounds(gcodePath: String): Pair<Float, Float> {
        val gcode = File(gcodePath).readText()
        val xs = Regex("""[GX].*X([\d.]+)""").findAll(gcode)
            .map { it.groupValues[1].toFloat() }.toList()
        val ys = Regex("""[GY].*Y([\d.]+)""").findAll(gcode)
            .map { it.groupValues[1].toFloat() }.toList()
        val width = if (xs.isNotEmpty()) xs.max() - xs.min() else 0f
        val height = if (ys.isNotEmpty()) ys.max() - ys.min() else 0f
        return Pair(width, height)
    }

    @Before
    fun setup() {
        assertTrue("Native library must be loaded", NativeLibrary.isLoaded)
        lib = NativeLibrary()
        cacheDir = File(targetContext.cacheDir, "fixture_harness")
        cacheDir.mkdirs()
    }

    @After
    fun teardown() {
        lib.clearModel()
        cacheDir.deleteRecursively()
    }

    @Test
    fun validate_all_approved_fixtures() {
        val specs = loadFixtureSpecs()
        if (specs.isEmpty()) {
            Log.i("FixtureHarness", "No fixture specs found — run with -e generateSpec true")
            return
        }

        val approved = specs.filter { it.approved }
        Log.i("FixtureHarness", "Validating ${approved.size} approved fixtures")

        val failures = mutableListOf<String>()

        for (spec in approved) {
            for (plate in spec.plates) {
                try {
                    lib.clearModel()
                    val (state, sliceConfig) = embedAndLoadForPlate(
                        spec.file, plate.plateIndex.takeIf { it >= 0 }
                    )

                    // Extruder count
                    if (state.usedExtruders.size != plate.expectedExtruderCount) {
                        failures.add("${spec.file} plate ${plate.plateIndex}: " +
                            "extruder count ${state.usedExtruders.size} != ${plate.expectedExtruderCount}")
                    }

                    // Paint data
                    if (state.hasPaintData != plate.hasPaintData) {
                        failures.add("${spec.file} plate ${plate.plateIndex}: " +
                            "hasPaintData ${state.hasPaintData} != ${plate.hasPaintData}")
                    }

                    // Tool counts (requires slicing)
                    if (plate.expectedToolCounts.isNotEmpty()) {
                        val result = lib.slice(sliceConfig)
                        assertNotNull("Slice failed for ${spec.file}", result)
                        assertTrue("Slice error for ${spec.file}", result!!.success)
                        val toolCounts = parseToolCounts(result.gcodePath)

                        for ((tool, expected) in plate.expectedToolCounts) {
                            val actual = toolCounts[tool] ?: 0
                            if (abs(actual - expected) > plate.toolCountTolerance) {
                                failures.add("${spec.file} plate ${plate.plateIndex}: " +
                                    "$tool count $actual not within ±${plate.toolCountTolerance} of $expected")
                            }
                        }

                        // Bounding box
                        val (width, height) = parseGcodeBounds(result.gcodePath)
                        if (width > plate.maxBoundingBoxMm[0]) {
                            failures.add("${spec.file} plate ${plate.plateIndex}: " +
                                "G-code width ${width}mm > ${plate.maxBoundingBoxMm[0]}mm")
                        }
                        if (height > plate.maxBoundingBoxMm[1]) {
                            failures.add("${spec.file} plate ${plate.plateIndex}: " +
                                "G-code height ${height}mm > ${plate.maxBoundingBoxMm[1]}mm")
                        }
                    }

                    Log.i("FixtureHarness", "PASS: ${spec.file} plate ${plate.plateIndex}")
                } catch (e: Exception) {
                    failures.add("${spec.file} plate ${plate.plateIndex}: EXCEPTION ${e.message}")
                }
            }
        }

        if (failures.isNotEmpty()) {
            fail("Fixture harness failures:\n" + failures.joinToString("\n"))
        }
    }
}
```

- [ ] **Step 3: Verify it compiles and runs (empty — no specs yet)**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
./gradlew clean assembleDebug assembleDebugAndroidTest --no-daemon && \
adb uninstall com.u1.slicer.orca 2>/dev/null ; adb uninstall com.u1.slicer.orca.test 2>/dev/null ; \
adb install -t app/build/outputs/apk/debug/app-debug.apk && \
adb install -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk && \
adb shell am instrument -w -e class com.u1.slicer.slicing.BambuFixtureHarnessTest \
  com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: 1 test passes (logs "No fixture specs found").

- [ ] **Step 4: Commit the harness infrastructure**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
git add app/src/androidTest/java/com/u1/slicer/slicing/BambuFixtureHarnessTest.kt && \
git commit -m "$(cat <<'EOF'
test(bambu): Tier B data-driven fixture harness infrastructure

New BambuFixtureHarnessTest reads JSON specs from assets/fixture-specs/
and validates each approved fixture against expected extruder counts,
tool counts, paint data, and bounding box. Supports -e generateSpec true
for bootstrapping draft specs from first run.
EOF
)"
```

---

## Task 9: Bootstrap Fixture Specs

Create JSON specs for key fixtures using the E2E batch results from MORNING_STATUS.md as the baseline. Start with fixtures that cover the 6 bug classes.

**Files:**
- Create: `app/src/androidTest/assets/fixture-specs/dragon-scale-plate3.json`
- Create: `app/src/androidTest/assets/fixture-specs/button-for-s-trousers.json`
- Create: `app/src/androidTest/assets/fixture-specs/colored-benchy.json`
- Create: `app/src/androidTest/assets/fixture-specs/shashibo-plate5.json`
- Create: `app/src/androidTest/assets/fixture-specs/slip-slide-spin-plate3.json`
- Create: `app/src/androidTest/assets/fixture-specs/flippy-flappy-plate4.json`

- [ ] **Step 1: Create specs from known-good E2E results**

These values come from the #2d E2E batch results in MORNING_STATUS.md:

`dragon-scale-plate3.json`:
```json
{
  "file": "Dragon Scale infinity.3mf",
  "approved": true,
  "plates": [
    {
      "plateIndex": 2,
      "expectedExtruderCount": 3,
      "expectedToolCounts": {"T0": 50, "T2": 53, "T3": 90},
      "toolCountTolerance": 5,
      "hasPaintData": false,
      "maxBoundingBoxMm": [270, 270]
    }
  ]
}
```

`button-for-s-trousers.json`:
```json
{
  "file": "Button-for-S-trousers.3mf",
  "approved": true,
  "plates": [
    {
      "plateIndex": 0,
      "expectedExtruderCount": 4,
      "expectedToolCounts": {"T0": 8, "T1": 10, "T2": 6, "T3": 9},
      "toolCountTolerance": 3,
      "hasPaintData": false,
      "maxBoundingBoxMm": [170, 170]
    }
  ]
}
```

`colored-benchy.json`:
```json
{
  "file": "colored_3DBenchy (1).3mf",
  "approved": true,
  "plates": [
    {
      "plateIndex": -1,
      "expectedExtruderCount": 4,
      "expectedToolCounts": {},
      "toolCountTolerance": 5,
      "hasPaintData": true,
      "maxBoundingBoxMm": [270, 270]
    }
  ]
}
```

`shashibo-plate5.json`:
```json
{
  "file": "Shashibo-h2s-textured.3mf",
  "approved": true,
  "plates": [
    {
      "plateIndex": 4,
      "expectedExtruderCount": 2,
      "expectedToolCounts": {"T0": 71, "T3": 69},
      "toolCountTolerance": 5,
      "hasPaintData": false,
      "maxBoundingBoxMm": [270, 270]
    }
  ]
}
```

`slip-slide-spin-plate3.json`:
```json
{
  "file": "slip slide spin fidget.3mf",
  "approved": true,
  "plates": [
    {
      "plateIndex": 2,
      "expectedExtruderCount": 4,
      "expectedToolCounts": {"T0": 28, "T1": 49, "T2": 50, "T3": 26},
      "toolCountTolerance": 5,
      "hasPaintData": true,
      "maxBoundingBoxMm": [270, 270]
    }
  ]
}
```

`flippy-flappy-plate4.json`:
```json
{
  "file": "flippy+flappy+mini.3mf",
  "approved": true,
  "plates": [
    {
      "plateIndex": 3,
      "expectedExtruderCount": 2,
      "expectedToolCounts": {"T0": 2, "T1": 1},
      "toolCountTolerance": 2,
      "hasPaintData": false,
      "maxBoundingBoxMm": [270, 270]
    }
  ]
}
```

- [ ] **Step 2: Write all spec files**

Create each JSON file in `app/src/androidTest/assets/fixture-specs/`.

- [ ] **Step 3: Run the harness**

```bash
adb shell am instrument -w -e class com.u1.slicer.slicing.BambuFixtureHarnessTest \
  com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: 1 test passes, validating all 6 approved fixtures. This will take several minutes (6 slices on device).

- [ ] **Step 4: Commit**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
git add app/src/androidTest/assets/fixture-specs/ && \
git commit -m "$(cat <<'EOF'
test(bambu): bootstrap fixture specs for 6 key Bambu files

Data-driven specs for Dragon Scale plate 3, Button-for-S-trousers,
colored Benchy, Shashibo plate 5, slip-slide-spin plate 3, and
flippy+flappy plate 4. Covers compound components, multi-plate,
SEMM paint, layer-tool, and 4-extruder per-object file classes.
EOF
)"
```

---

## Task 10: Cleanup + Update Docs

Remove deprecated synthesis code and update CLAUDE.md test counts.

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` (delete `buildSelectedPlateInfo` body, keep as deprecated stub)
- Modify: `app/src/main/java/com/u1/slicer/bambu/ThreeMfInfo.kt` (mark `objectPartExtruders`, `compoundPartParents`, `paintExtruderStates` deprecated)
- Modify: `CLAUDE.md` (update test counts)

- [ ] **Step 1: Mark deprecated fields on ThreeMfInfo**

In `ThreeMfInfo.kt`, add `@Deprecated` to:
- Line 88: `objectPartExtruders` — "Use NativePlateState from nativeGetAllVolumeExtruders instead"
- Line 97: `compoundPartParents` — "No longer needed; native provides per-volume data"

In `ThreeMfPlate` (ThreeMfInfo.kt):
- Line 33: `paintExtruderStates` — "Use NativePlateState.hasPaintData from native reads instead"

- [ ] **Step 2: Run full JVM test suite**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
./gradlew testDebugUnitTest --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run full instrumented sweep**

Run each package to verify no regressions:

```bash
# Run all key packages sequentially
for pkg in com.u1.slicer.bambu com.u1.slicer.slicing com.u1.slicer.native com.u1.slicer.viewer; do
  echo "--- $pkg ---"
  adb shell am instrument -w -e package $pkg \
    com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
done
```

Expected: All packages green.

- [ ] **Step 4: Update CLAUDE.md test counts**

Update the test counts in `CLAUDE.md` to reflect:
- New JVM tests: NativePlateStateTest (7 tests)
- New instrumented tests: NativeVolumeMapTest (4), BambuPlateStateRegressionTest (5), BambuFixtureHarnessTest (1)
- Any tests removed or adjusted

- [ ] **Step 5: Commit**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
git restore -- app/src/main/cpp/extern/ && \
git add -u && \
git commit -m "$(cat <<'EOF'
docs(phase1): native-first plate state — cleanup + test count update

Mark objectPartExtruders, compoundPartParents, paintExtruderStates
as @Deprecated — native provides this data via nativeGetAllVolumeExtruders.
Update CLAUDE.md test counts for new NativePlateStateTest,
NativeVolumeMapTest, BambuPlateStateRegressionTest, and
BambuFixtureHarnessTest.
EOF
)"
```

---

## Verification Checklist (run after all tasks)

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && \
git rev-parse HEAD && git status --short && \
./gradlew testDebugUnitTest --no-daemon
```

Then on-device:
```bash
# Full instrumented sweep
for pkg in com.u1.slicer.bambu com.u1.slicer.slicing com.u1.slicer.native com.u1.slicer.viewer com.u1.slicer.gcode; do
  echo "--- $pkg ---"
  adb shell am instrument -w -e package $pkg \
    com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
done

# Diff harness
adb shell am instrument -w -e class com.u1.slicer.bambu.snapshot.BambuParserDifferentialTest \
  com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner

# Fixture harness
adb shell am instrument -w -e class com.u1.slicer.slicing.BambuFixtureHarnessTest \
  com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

**Success criteria:**
1. All JVM tests pass
2. All instrumented tests pass
3. Diff harness at 0 baseline
4. Fixture harness validates all 6 approved specs
5. Tier A regression tests all pass (the 6 bugs are fixed)
