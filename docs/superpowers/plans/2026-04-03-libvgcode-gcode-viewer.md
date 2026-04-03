# libvgcode G-Code Viewer Integration Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the custom Kotlin `GcodeRenderer` with PrusaSlicer's `libvgcode` library to get instanced tube rendering for all g-code files (no more thin-line fallback for large files), plus free feature-type/speed/height/width coloring modes.

**Architecture:** libvgcode is a self-contained C++ library (6.5K LOC, zero external dependencies) that manages its own OpenGL ES rendering internally. We build it as a separate `.so` (`libvgcode-jni.so`), bridge it via JNI, and replace `GcodeRenderer`'s `uploadGcode`/`drawToolpaths` with calls to libvgcode's `load()`/`render()`. Our Kotlin `GcodeParser` continues to parse g-code, and we batch-transfer the parsed moves to native via JNI. The existing `Camera`, `BedDrawable`, touch gestures, and Compose UI remain unchanged.

**Tech Stack:** C++17, OpenGL ES 3.0, JNI, Kotlin, Jetpack Compose

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `app/src/main/cpp/libvgcode/` | Create (copy) | PrusaSlicer's libvgcode source files (~46 files) |
| `app/src/main/cpp/libvgcode/CMakeLists.txt` | Create | Build libvgcode as static lib for Android ES |
| `app/src/main/cpp/vgcode_jni/vgcode_bridge.cpp` | Create | JNI bridge: create/init/load/render/shutdown the Viewer |
| `app/src/main/cpp/vgcode_jni/CMakeLists.txt` | Create | Build `libvgcode-jni.so` linking static libvgcode + GLESv3 |
| `app/src/main/java/com/u1/slicer/viewer/VGCodeNative.kt` | Create | Kotlin JNI declarations for the bridge |
| `app/src/main/java/com/u1/slicer/viewer/GcodeRenderer.kt` | Modify | Replace custom tube/line rendering with libvgcode calls |
| `app/src/main/java/com/u1/slicer/viewer/GcodeViewerView.kt` | Modify | Minor — expose new view type switching |
| `app/src/main/java/com/u1/slicer/ui/GcodeViewer3DScreen.kt` | Modify | Minor — add view type toggle UI |
| `app/build.gradle` | Modify | Add CMake build config for vgcode_jni |
| `app/src/test/java/com/u1/slicer/viewer/VGCodeDataMappingTest.kt` | Create | Tests for Kotlin→PathVertex data conversion |

## Design Notes

### Data flow

```
GcodeParser.parse(file) → ParsedGcode (Kotlin)
    ↓
GcodeRenderer.uploadGcode() → batch-packs moves into FloatArray
    ↓ JNI
vgcode_load(ptr, positions, heights, widths, feedrates, roles, extruders, layerIds, toolColors)
    ↓ C++
Builds GCodeInputData → viewer.load(std::move(data))
    ↓ GL
Instanced rendering — all moves get tubes
```

### JNI data transfer strategy

Rather than creating JNI objects per-move (expensive), we pack move data into parallel primitive arrays in Kotlin and pass them in a single JNI call:

- `float[] positions` — 3 floats per move (x, y, z) — **end position** of each segment
- `float[] heights` — layer height per move (we use a fixed value from parsed layer)
- `float[] widths` — extrusion width per move (fixed 0.42 for now)
- `float[] feedrates` — 0 for now (parser doesn't extract)
- `byte[] moveTypes` — EMoveType ordinal per move
- `byte[] roles` — EGCodeExtrusionRole ordinal per move
- `byte[] extruderIds` — extruder index per move
- `int[] layerIds` — layer index per move
- `int[] toolColors` — RGB packed as 0xRRGGBB per extruder

This is ~24 bytes per move × 361k moves = ~8.5MB across the JNI boundary — fast enough.

### FeatureType mapping

Our `FeatureType` byte constants map to libvgcode's `EGCodeExtrusionRole`:
| Ours | libvgcode |
|------|-----------|
| OUTER_WALL (0) | ExternalPerimeter (2) |
| INNER_WALL (1) | Perimeter (1) |
| SPARSE_INFILL (2) | InternalInfill (3) |
| SOLID_INFILL (3) | SolidInfill (4) |
| TOP_SURFACE (4) | TopSolidInfill (5) |
| BOTTOM_SURFACE (5) | SolidInfill (4) |
| SUPPORT (6) | SupportMaterial (10) |
| SUPPORT_INTERFACE (7) | SupportMaterialInterface (11) |
| PRIME_TOWER (8) | WipeTower (12) |
| BRIDGE (9) | BridgeInfill (8) |
| SKIRT (10) | Skirt (9) |
| OTHER (11) | Custom (13) |

### GL context sharing

libvgcode manages its own shaders and VBOs but renders into the current GL context. Our `GcodeRenderer.onDrawFrame` will:
1. Draw bed (existing `BedDrawable`)
2. Call `VGCodeNative.render(ptr, viewMatrix, projMatrix)` — libvgcode draws toolpaths
3. That's it — no more custom tube/line code

libvgcode's `init()` must be called when the GL context is first created (in `onSurfaceCreated`), and `shutdown()` when it's destroyed.

### Layer range

libvgcode uses layer indices just like we do: `set_layers_view_range(min, max)`. Our existing layer slider maps directly.

---

### Task 1: Download and add libvgcode source files

**Files:**
- Create: `app/src/main/cpp/libvgcode/` directory with source files from PrusaSlicer

- [ ] **Step 1: Download libvgcode source from PrusaSlicer**

Clone or download the `src/libvgcode/` directory from PrusaSlicer's main branch. The files needed are:

```
src/          — all .cpp and .hpp implementation files
include/      — public headers (Viewer.hpp, Types.hpp, PathVertex.hpp, ColorRange.hpp, etc.)
```

Do NOT include the `glad/` subdirectory — we link GLESv3 directly on Android (no glad loader needed).

Copy all files to `app/src/main/cpp/libvgcode/`.

- [ ] **Step 2: Verify file count and structure**

```bash
find app/src/main/cpp/libvgcode -name '*.cpp' -o -name '*.hpp' | wc -l
```
Expected: ~35-40 files (source + headers, excluding glad).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/cpp/libvgcode/
git commit -m "vendor: add PrusaSlicer libvgcode source (AGPLv3)"
```

---

### Task 2: Create CMake build for libvgcode + JNI bridge

**Files:**
- Create: `app/src/main/cpp/libvgcode/CMakeLists.txt`
- Create: `app/src/main/cpp/vgcode_jni/CMakeLists.txt`
- Modify: `app/build.gradle`

- [ ] **Step 1: Create libvgcode static library CMakeLists**

Create `app/src/main/cpp/libvgcode/CMakeLists.txt`:

```cmake
cmake_minimum_required(VERSION 3.22.1)

# Collect all .cpp source files
file(GLOB VGCODE_SOURCES
    "${CMAKE_CURRENT_SOURCE_DIR}/src/*.cpp"
)

add_library(vgcode STATIC ${VGCODE_SOURCES})

target_include_directories(vgcode PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/src
)

target_compile_definitions(vgcode PUBLIC
    ENABLE_OPENGL_ES
    SLIC3R_OPENGL_ES
)

target_compile_features(vgcode PUBLIC cxx_std_17)
```

- [ ] **Step 2: Create JNI bridge CMakeLists**

Create `app/src/main/cpp/vgcode_jni/CMakeLists.txt`:

```cmake
cmake_minimum_required(VERSION 3.22.1)
project("vgcode_jni" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../libvgcode libvgcode_build)

add_library(vgcode-jni SHARED vgcode_bridge.cpp)

target_link_libraries(vgcode-jni PRIVATE
    vgcode
    GLESv3
    EGL
    log
)

target_include_directories(vgcode-jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../libvgcode/include
)
```

- [ ] **Step 3: Add CMake build to app/build.gradle**

In `app/build.gradle`, the existing `externalNativeBuild` for the slicer is commented out. We add a **separate** CMake config for vgcode-jni. Add inside the `android { }` block, under `defaultConfig`:

```groovy
    externalNativeBuild {
        cmake {
            path "src/main/cpp/vgcode_jni/CMakeLists.txt"
        }
    }
```

And inside `defaultConfig.ndk`:
```groovy
        ndk {
            abiFilters 'arm64-v8a'
        }
```

**Important**: This is a separate CMake project from the slicer (which uses pre-built `.so`). Both can coexist — Gradle builds the CMake project into the APK alongside the pre-built jniLibs.

- [ ] **Step 4: Verify CMake configures without errors**

```bash
./gradlew assembleDebug --no-daemon 2>&1 | head -100
```

This will fail at link time (no `vgcode_bridge.cpp` yet) but should succeed at CMake configure time. If libvgcode sources have issues (missing glad, etc.), we'll fix them here.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/cpp/libvgcode/CMakeLists.txt app/src/main/cpp/vgcode_jni/CMakeLists.txt app/build.gradle
git commit -m "build: add CMake config for libvgcode + JNI bridge"
```

---

### Task 3: Write the C++ JNI bridge

**Files:**
- Create: `app/src/main/cpp/vgcode_jni/vgcode_bridge.cpp`

- [ ] **Step 1: Write the JNI bridge implementation**

Create `app/src/main/cpp/vgcode_jni/vgcode_bridge.cpp`:

```cpp
#include <jni.h>
#include <android/log.h>
#include <GLES3/gl3.h>
#include <string>
#include <vector>
#include <cstring>

#include "libvgcode/Viewer.hpp"
#include "libvgcode/PathVertex.hpp"
#include "libvgcode/Types.hpp"

#define LOG_TAG "VGCodeBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct VGCodeRef {
    libvgcode::Viewer viewer;
    bool initialized = false;
};

static inline VGCodeRef* toRef(jlong ptr) {
    return reinterpret_cast<VGCodeRef*>(static_cast<intptr_t>(ptr));
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_u1_slicer_viewer_VGCodeNative_create(JNIEnv*, jclass) {
    auto* ref = new VGCodeRef();
    return static_cast<jlong>(reinterpret_cast<intptr_t>(ref));
}

JNIEXPORT void JNICALL
Java_com_u1_slicer_viewer_VGCodeNative_init(JNIEnv* env, jclass, jlong ptr) {
    auto* ref = toRef(ptr);
    if (ref->initialized) return;
    const char* version = reinterpret_cast<const char*>(glGetString(GL_VERSION));
    std::string versionStr = version ? version : "OpenGL ES 3.0";
    LOGI("init: GL version = %s", versionStr.c_str());
    ref->viewer.init(versionStr);
    ref->initialized = true;
}

JNIEXPORT void JNICALL
Java_com_u1_slicer_viewer_VGCodeNative_load(
    JNIEnv* env, jclass, jlong ptr,
    jfloatArray jPositions,    // 3 floats per move: x, y, z (end position)
    jfloatArray jHeights,      // 1 float per move
    jfloatArray jWidths,       // 1 float per move
    jfloatArray jFeedrates,    // 1 float per move
    jbyteArray jMoveTypes,     // 1 byte per move (EMoveType ordinal)
    jbyteArray jRoles,         // 1 byte per move (EGCodeExtrusionRole ordinal)
    jbyteArray jExtruderIds,   // 1 byte per move
    jintArray jLayerIds,       // 1 int per move
    jintArray jToolColors      // packed RGB per extruder (0xRRGGBB)
) {
    auto* ref = toRef(ptr);

    jsize moveCount = env->GetArrayLength(jHeights);
    LOGI("load: %d moves", moveCount);

    // Get array pointers
    jfloat* positions  = env->GetFloatArrayElements(jPositions, nullptr);
    jfloat* heights    = env->GetFloatArrayElements(jHeights, nullptr);
    jfloat* widths     = env->GetFloatArrayElements(jWidths, nullptr);
    jfloat* feedrates  = env->GetFloatArrayElements(jFeedrates, nullptr);
    jbyte*  moveTypes  = env->GetByteArrayElements(jMoveTypes, nullptr);
    jbyte*  roles      = env->GetByteArrayElements(jRoles, nullptr);
    jbyte*  extruderIds = env->GetByteArrayElements(jExtruderIds, nullptr);
    jint*   layerIds   = env->GetIntArrayElements(jLayerIds, nullptr);

    // Build GCodeInputData
    libvgcode::GCodeInputData data;
    data.vertices.reserve(moveCount);

    for (jsize i = 0; i < moveCount; i++) {
        libvgcode::PathVertex v;
        v.position = { positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2] };
        v.height = heights[i];
        v.width = widths[i];
        v.feedrate = feedrates[i];
        v.actual_feedrate = feedrates[i];
        v.type = static_cast<libvgcode::EMoveType>(moveTypes[i]);
        v.role = static_cast<libvgcode::EGCodeExtrusionRole>(roles[i]);
        v.extruder_id = static_cast<uint8_t>(extruderIds[i]);
        v.layer_id = static_cast<uint32_t>(layerIds[i]);
        data.vertices.emplace_back(v);
    }

    // Release arrays
    env->ReleaseFloatArrayElements(jPositions, positions, JNI_ABORT);
    env->ReleaseFloatArrayElements(jHeights, heights, JNI_ABORT);
    env->ReleaseFloatArrayElements(jWidths, widths, JNI_ABORT);
    env->ReleaseFloatArrayElements(jFeedrates, feedrates, JNI_ABORT);
    env->ReleaseByteArrayElements(jMoveTypes, moveTypes, JNI_ABORT);
    env->ReleaseByteArrayElements(jRoles, roles, JNI_ABORT);
    env->ReleaseByteArrayElements(jExtruderIds, extruderIds, JNI_ABORT);
    env->ReleaseIntArrayElements(jLayerIds, layerIds, JNI_ABORT);

    // Tool colors
    jsize colorCount = env->GetArrayLength(jToolColors);
    jint* colors = env->GetIntArrayElements(jToolColors, nullptr);
    data.tools_colors.resize(colorCount);
    for (jsize i = 0; i < colorCount; i++) {
        uint32_t c = static_cast<uint32_t>(colors[i]);
        data.tools_colors[i] = {
            static_cast<uint8_t>((c >> 16) & 0xFF),
            static_cast<uint8_t>((c >> 8) & 0xFF),
            static_cast<uint8_t>(c & 0xFF)
        };
    }
    env->ReleaseIntArrayElements(jToolColors, colors, JNI_ABORT);

    ref->viewer.load(std::move(data));
    LOGI("load complete: %zu layers", ref->viewer.get_layers_count());
}

JNIEXPORT void JNICALL
Java_com_u1_slicer_viewer_VGCodeNative_render(
    JNIEnv* env, jclass, jlong ptr,
    jfloatArray jViewMatrix, jfloatArray jProjMatrix
) {
    auto* ref = toRef(ptr);
    if (!ref->initialized) return;

    libvgcode::Mat4x4 view, proj;
    env->GetFloatArrayRegion(jViewMatrix, 0, 16, view.data());
    env->GetFloatArrayRegion(jProjMatrix, 0, 16, proj.data());

    ref->viewer.render(view, proj);
}

JNIEXPORT void JNICALL
Java_com_u1_slicer_viewer_VGCodeNative_setLayersViewRange(
    JNIEnv*, jclass, jlong ptr, jlong min, jlong max
) {
    toRef(ptr)->viewer.set_layers_view_range(
        static_cast<size_t>(min), static_cast<size_t>(max));
}

JNIEXPORT jlong JNICALL
Java_com_u1_slicer_viewer_VGCodeNative_getLayersCount(JNIEnv*, jclass, jlong ptr) {
    return static_cast<jlong>(toRef(ptr)->viewer.get_layers_count());
}

JNIEXPORT void JNICALL
Java_com_u1_slicer_viewer_VGCodeNative_setViewType(JNIEnv*, jclass, jlong ptr, jint type) {
    toRef(ptr)->viewer.set_view_type(static_cast<libvgcode::EViewType>(type));
}

JNIEXPORT jint JNICALL
Java_com_u1_slicer_viewer_VGCodeNative_getViewType(JNIEnv*, jclass, jlong ptr) {
    return static_cast<jint>(toRef(ptr)->viewer.get_view_type());
}

JNIEXPORT void JNICALL
Java_com_u1_slicer_viewer_VGCodeNative_setToolColors(
    JNIEnv* env, jclass, jlong ptr, jintArray jColors
) {
    auto* ref = toRef(ptr);
    jsize count = env->GetArrayLength(jColors);
    jint* colors = env->GetIntArrayElements(jColors, nullptr);
    libvgcode::Palette palette(count);
    for (jsize i = 0; i < count; i++) {
        uint32_t c = static_cast<uint32_t>(colors[i]);
        palette[i] = {
            static_cast<uint8_t>((c >> 16) & 0xFF),
            static_cast<uint8_t>((c >> 8) & 0xFF),
            static_cast<uint8_t>(c & 0xFF)
        };
    }
    env->ReleaseIntArrayElements(jColors, colors, JNI_ABORT);
    ref->viewer.set_tool_colors(palette);
}

JNIEXPORT void JNICALL
Java_com_u1_slicer_viewer_VGCodeNative_toggleOptionVisibility(
    JNIEnv*, jclass, jlong ptr, jint option
) {
    toRef(ptr)->viewer.toggle_option_visibility(
        static_cast<libvgcode::EOptionType>(option));
}

JNIEXPORT jboolean JNICALL
Java_com_u1_slicer_viewer_VGCodeNative_isOptionVisible(
    JNIEnv*, jclass, jlong ptr, jint option
) {
    return toRef(ptr)->viewer.is_option_visible(
        static_cast<libvgcode::EOptionType>(option));
}

JNIEXPORT void JNICALL
Java_com_u1_slicer_viewer_VGCodeNative_shutdown(JNIEnv*, jclass, jlong ptr) {
    auto* ref = toRef(ptr);
    if (ref->initialized) {
        ref->viewer.shutdown();
        ref->initialized = false;
    }
}

JNIEXPORT void JNICALL
Java_com_u1_slicer_viewer_VGCodeNative_destroy(JNIEnv*, jclass, jlong ptr) {
    auto* ref = toRef(ptr);
    if (ref->initialized) {
        ref->viewer.shutdown();
    }
    delete ref;
}

} // extern "C"
```

- [ ] **Step 2: Verify it compiles**

```bash
./gradlew assembleDebug --no-daemon 2>&1 | tail -30
```

Fix any compilation errors (likely: include path issues, missing ES defines, glad references that need stubbing).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/cpp/vgcode_jni/vgcode_bridge.cpp
git commit -m "feat: add C++ JNI bridge for libvgcode"
```

---

### Task 4: Write Kotlin JNI declarations and data mapping

**Files:**
- Create: `app/src/main/java/com/u1/slicer/viewer/VGCodeNative.kt`

- [ ] **Step 1: Write the Kotlin JNI class**

Create `app/src/main/java/com/u1/slicer/viewer/VGCodeNative.kt`:

```kotlin
package com.u1.slicer.viewer

import com.u1.slicer.gcode.FeatureType
import com.u1.slicer.gcode.MoveType
import com.u1.slicer.gcode.ParsedGcode

/**
 * JNI bridge to PrusaSlicer's libvgcode for instanced G-code toolpath rendering.
 * All methods that touch GL must be called on the GL thread.
 */
object VGCodeNative {

    init {
        System.loadLibrary("vgcode-jni")
    }

    // --- Native methods ---
    external fun create(): Long
    external fun init(ptr: Long)
    external fun load(
        ptr: Long,
        positions: FloatArray,
        heights: FloatArray,
        widths: FloatArray,
        feedrates: FloatArray,
        moveTypes: ByteArray,
        roles: ByteArray,
        extruderIds: ByteArray,
        layerIds: IntArray,
        toolColors: IntArray
    )
    external fun render(ptr: Long, viewMatrix: FloatArray, projMatrix: FloatArray)
    external fun setLayersViewRange(ptr: Long, min: Long, max: Long)
    external fun getLayersCount(ptr: Long): Long
    external fun setViewType(ptr: Long, type: Int)
    external fun getViewType(ptr: Long): Int
    external fun setToolColors(ptr: Long, colors: IntArray)
    external fun toggleOptionVisibility(ptr: Long, option: Int)
    external fun isOptionVisible(ptr: Long, option: Int): Boolean
    external fun shutdown(ptr: Long)
    external fun destroy(ptr: Long)

    // --- View type constants (matches libvgcode EViewType) ---
    const val VIEW_TYPE_FEATURE = 0
    const val VIEW_TYPE_HEIGHT = 1
    const val VIEW_TYPE_WIDTH = 2
    const val VIEW_TYPE_SPEED = 3
    const val VIEW_TYPE_TOOL = 10

    // --- Option type constants (matches libvgcode EOptionType) ---
    const val OPTION_TRAVELS = 0
    const val OPTION_WIPES = 1
    const val OPTION_RETRACTIONS = 2

    // --- EGCodeExtrusionRole mapping from our FeatureType ---
    private fun mapRole(featureType: Byte): Byte = when (featureType) {
        FeatureType.OUTER_WALL -> 2        // ExternalPerimeter
        FeatureType.INNER_WALL -> 1        // Perimeter
        FeatureType.SPARSE_INFILL -> 3     // InternalInfill
        FeatureType.SOLID_INFILL -> 4      // SolidInfill
        FeatureType.TOP_SURFACE -> 5       // TopSolidInfill
        FeatureType.BOTTOM_SURFACE -> 4    // SolidInfill (no separate bottom in libvgcode)
        FeatureType.SUPPORT -> 10          // SupportMaterial
        FeatureType.SUPPORT_INTERFACE -> 11 // SupportMaterialInterface
        FeatureType.PRIME_TOWER -> 12      // WipeTower
        FeatureType.BRIDGE -> 8            // BridgeInfill
        FeatureType.SKIRT -> 9             // Skirt
        FeatureType.OTHER -> 13            // Custom
        else -> 0                          // None
    }

    // --- EMoveType mapping ---
    private fun mapMoveType(type: MoveType): Byte = when (type) {
        MoveType.EXTRUDE -> 8   // EMoveType::Extrude
        MoveType.TRAVEL -> 7    // EMoveType::Travel
    }

    /**
     * Pack a ParsedGcode into parallel arrays for JNI transfer and call native load().
     * Must be called on the GL thread (after init).
     */
    fun loadGcode(ptr: Long, gcode: ParsedGcode, extruderColors: IntArray) {
        // Count total moves
        var totalMoves = 0
        for (layer in gcode.layers) totalMoves += layer.moves.size

        if (totalMoves == 0) return

        val positions = FloatArray(totalMoves * 3)
        val heights = FloatArray(totalMoves)
        val widths = FloatArray(totalMoves)
        val feedrates = FloatArray(totalMoves)
        val moveTypes = ByteArray(totalMoves)
        val roles = ByteArray(totalMoves)
        val extruderIds = ByteArray(totalMoves)
        val layerIds = IntArray(totalMoves)

        var idx = 0
        for (layer in gcode.layers) {
            for (move in layer.moves) {
                // libvgcode uses end-position per vertex (segments connect consecutive vertices)
                positions[idx * 3] = move.x1
                positions[idx * 3 + 1] = move.y1
                positions[idx * 3 + 2] = layer.z
                heights[idx] = 0.2f  // default layer height — TODO: extract from gcode
                widths[idx] = if (move.type == MoveType.EXTRUDE) 0.42f else 0f
                feedrates[idx] = 0f  // not yet parsed
                moveTypes[idx] = mapMoveType(move.type)
                roles[idx] = if (move.type == MoveType.EXTRUDE) mapRole(move.featureType) else 0
                extruderIds[idx] = move.extruder.toByte()
                layerIds[idx] = layer.index
                idx++
            }
        }

        load(ptr, positions, heights, widths, feedrates, moveTypes, roles, extruderIds, layerIds, extruderColors)
    }

    /** Convert hex color strings to packed RGB int array for JNI. */
    fun packToolColors(hexColors: List<String>): IntArray {
        val defaults = intArrayOf(
            0xFF9900,  // T0: orange
            0x33B3FF,  // T1: blue
            0x00E666,  // T2: green
            0xE63380   // T3: pink
        )
        val result = defaults.copyOf()
        hexColors.forEachIndexed { i, hex ->
            if (i >= result.size || hex.isBlank()) return@forEachIndexed
            try {
                val c = android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex")
                result[i] = (android.graphics.Color.red(c) shl 16) or
                            (android.graphics.Color.green(c) shl 8) or
                            android.graphics.Color.blue(c)
            } catch (_: Exception) { /* keep default */ }
        }
        return result
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/u1/slicer/viewer/VGCodeNative.kt
git commit -m "feat: add Kotlin JNI declarations and data mapping for libvgcode"
```

---

### Task 5: Write unit tests for data mapping

**Files:**
- Create: `app/src/test/java/com/u1/slicer/viewer/VGCodeDataMappingTest.kt`

- [ ] **Step 1: Write tests for the mapping logic**

Create `app/src/test/java/com/u1/slicer/viewer/VGCodeDataMappingTest.kt`:

```kotlin
package com.u1.slicer.viewer

import com.u1.slicer.gcode.*
import org.junit.Assert.*
import org.junit.Test

class VGCodeDataMappingTest {

    @Test
    fun `mapMoveType returns correct EMoveType ordinals`() {
        // Access via reflection since mapMoveType is private
        val method = VGCodeNative::class.java.getDeclaredMethod("mapMoveType", MoveType::class.java)
        method.isAccessible = true
        assertEquals(8.toByte(), method.invoke(VGCodeNative, MoveType.EXTRUDE))  // EMoveType::Extrude
        assertEquals(7.toByte(), method.invoke(VGCodeNative, MoveType.TRAVEL))   // EMoveType::Travel
    }

    @Test
    fun `mapRole maps all FeatureType constants`() {
        val method = VGCodeNative::class.java.getDeclaredMethod("mapRole", Byte::class.java)
        method.isAccessible = true
        assertEquals(2.toByte(), method.invoke(VGCodeNative, FeatureType.OUTER_WALL))       // ExternalPerimeter
        assertEquals(1.toByte(), method.invoke(VGCodeNative, FeatureType.INNER_WALL))       // Perimeter
        assertEquals(3.toByte(), method.invoke(VGCodeNative, FeatureType.SPARSE_INFILL))    // InternalInfill
        assertEquals(4.toByte(), method.invoke(VGCodeNative, FeatureType.SOLID_INFILL))     // SolidInfill
        assertEquals(5.toByte(), method.invoke(VGCodeNative, FeatureType.TOP_SURFACE))      // TopSolidInfill
        assertEquals(4.toByte(), method.invoke(VGCodeNative, FeatureType.BOTTOM_SURFACE))   // SolidInfill
        assertEquals(10.toByte(), method.invoke(VGCodeNative, FeatureType.SUPPORT))         // SupportMaterial
        assertEquals(11.toByte(), method.invoke(VGCodeNative, FeatureType.SUPPORT_INTERFACE)) // SupportMaterialInterface
        assertEquals(12.toByte(), method.invoke(VGCodeNative, FeatureType.PRIME_TOWER))     // WipeTower
        assertEquals(8.toByte(), method.invoke(VGCodeNative, FeatureType.BRIDGE))           // BridgeInfill
        assertEquals(9.toByte(), method.invoke(VGCodeNative, FeatureType.SKIRT))            // Skirt
        assertEquals(13.toByte(), method.invoke(VGCodeNative, FeatureType.OTHER))           // Custom
    }

    @Test
    fun `packToolColors uses defaults for empty input`() {
        val colors = VGCodeNative.packToolColors(emptyList())
        assertEquals(4, colors.size)
        assertEquals(0xFF9900, colors[0])  // orange
        assertEquals(0x33B3FF, colors[1])  // blue
    }

    @Test
    fun `packToolColors overrides with hex strings`() {
        val colors = VGCodeNative.packToolColors(listOf("#FF0000", "#00FF00"))
        assertEquals(0xFF0000, colors[0])
        assertEquals(0x00FF00, colors[1])
        assertEquals(0x00E666, colors[2])  // default green
        assertEquals(0xE63380, colors[3])  // default pink
    }

    @Test
    fun `packToolColors handles missing hash prefix`() {
        val colors = VGCodeNative.packToolColors(listOf("AABBCC"))
        assertEquals(0xAABBCC, colors[0])
    }

    @Test
    fun `packToolColors ignores blank entries`() {
        val colors = VGCodeNative.packToolColors(listOf("", "#00FF00"))
        assertEquals(0xFF9900, colors[0])  // unchanged default
        assertEquals(0x00FF00, colors[1])  // overridden
    }
}
```

- [ ] **Step 2: Run the tests**

Run: `./gradlew testDebugUnitTest --tests "com.u1.slicer.viewer.VGCodeDataMappingTest" --no-daemon`

Note: The `mapRole`/`mapMoveType` tests use reflection to access private methods. The `packToolColors` tests will fail because `android.graphics.Color.parseColor` isn't available in JVM tests. We need to refactor `packToolColors` to not depend on Android APIs for the pure parsing logic, or mock it. For now, we can make the hex parsing pure:

Update `packToolColors` in `VGCodeNative.kt` to use pure Kotlin hex parsing instead of `android.graphics.Color`:

```kotlin
    fun packToolColors(hexColors: List<String>): IntArray {
        val defaults = intArrayOf(0xFF9900, 0x33B3FF, 0x00E666, 0xE63380)
        val result = defaults.copyOf()
        hexColors.forEachIndexed { i, hex ->
            if (i >= result.size || hex.isBlank()) return@forEachIndexed
            try {
                val clean = hex.removePrefix("#")
                if (clean.length == 6) {
                    result[i] = clean.toInt(16)
                }
            } catch (_: Exception) { /* keep default */ }
        }
        return result
    }
```

- [ ] **Step 3: Re-run tests, verify all pass**

Run: `./gradlew testDebugUnitTest --tests "com.u1.slicer.viewer.VGCodeDataMappingTest" --no-daemon`
Expected: All 6 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/com/u1/slicer/viewer/VGCodeDataMappingTest.kt app/src/main/java/com/u1/slicer/viewer/VGCodeNative.kt
git commit -m "test: add unit tests for VGCode data mapping; fix packToolColors to use pure Kotlin"
```

---

### Task 6: Rewrite GcodeRenderer to use libvgcode

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/viewer/GcodeRenderer.kt`

This is the core integration. The renderer delegates all extrusion drawing to libvgcode while keeping bed rendering in Kotlin.

- [ ] **Step 1: Rewrite GcodeRenderer**

Replace the entire contents of `GcodeRenderer.kt`:

```kotlin
package com.u1.slicer.viewer

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import com.u1.slicer.gcode.ParsedGcode
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renders G-code toolpaths using PrusaSlicer's libvgcode (instanced tube rendering).
 * Bed is drawn in Kotlin; toolpaths are delegated to the native library.
 */
class GcodeRenderer(private val context: Context) : GLSurfaceView.Renderer {

    val camera = Camera()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile var preserveRestoredCameraOnSurfaceInit = false
    @Volatile var onContentReady: (() -> Unit)? = null
    @Volatile private var pendingContentReadyDispatch = false
    private val bed = BedDrawable(context)

    // libvgcode native pointer — 0 means not created
    private var vgcodePtr: Long = 0
    private var vgcodeInitialized = false
    private var totalLayers = 0

    var minLayer = 0
        set(value) { field = value.coerceIn(0, totalLayers - 1) }
    var maxLayer = 0
        set(value) { field = value.coerceIn(0, totalLayers - 1) }
    var showTravel = false
        set(value) {
            field = value
            if (vgcodePtr != 0L) {
                VGCodeNative.toggleOptionVisibility(vgcodePtr, VGCodeNative.OPTION_TRAVELS)
            }
        }

    @Volatile var pendingGcode: ParsedGcode? = null
    @Volatile var preserveCameraOnNextUpload = false
    @Volatile var pendingExtruderColors: List<String>? = null
    @Volatile var pendingColorMode: Boolean? = null

    /** Current extruder colors as packed RGB ints for libvgcode. */
    private var toolColors = VGCodeNative.packToolColors(emptyList())

    // Keep lastGcode for color mode re-upload
    private var lastGcode: ParsedGcode? = null

    fun setExtruderColors(hexColors: List<String>) {
        toolColors = VGCodeNative.packToolColors(hexColors)
        if (vgcodePtr != 0L) {
            VGCodeNative.setToolColors(vgcodePtr, toolColors)
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.059f, 0.059f, 0.118f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        bed.setup(context)

        // Create and init libvgcode
        if (vgcodePtr != 0L) {
            VGCodeNative.destroy(vgcodePtr)
        }
        vgcodePtr = VGCodeNative.create()
        VGCodeNative.init(vgcodePtr)
        vgcodeInitialized = true

        if (preserveRestoredCameraOnSurfaceInit) {
            preserveRestoredCameraOnSurfaceInit = false
        } else {
            camera.setTarget(135f, 135f, 0f)
            camera.distance = 500f
            camera.elevation = 62f
            camera.azimuth = -90f
        }

        // Re-upload if we had data before context loss
        lastGcode?.let { gcode ->
            preserveCameraOnNextUpload = true
            uploadGcode(gcode)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        camera.updateProjectionMatrix(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        pendingExtruderColors?.let { colors ->
            setExtruderColors(colors)
            pendingExtruderColors = null
        }

        pendingColorMode?.let { mode ->
            pendingColorMode = null
            if (vgcodePtr != 0L) {
                val viewType = if (mode) VGCodeNative.VIEW_TYPE_FEATURE else VGCodeNative.VIEW_TYPE_TOOL
                VGCodeNative.setViewType(vgcodePtr, viewType)
            }
        }

        pendingGcode?.let { gcode ->
            uploadGcode(gcode)
            pendingGcode = null

            if (preserveCameraOnNextUpload) {
                preserveCameraOnNextUpload = false
            } else {
                camera.setTarget(135f, 135f, 0f)
                camera.distance = 500f
                camera.elevation = 62f
                camera.azimuth = -90f
                camera.panX = 0f
                camera.panY = 0f
            }
            pendingContentReadyDispatch = true
        }

        camera.updateViewMatrix()
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        bed.draw(camera)

        // Update layer range and render via libvgcode
        if (vgcodePtr != 0L && totalLayers > 0) {
            val min = minLayer.coerceIn(0, totalLayers - 1)
            val max = maxLayer.coerceIn(0, totalLayers - 1)
            VGCodeNative.setLayersViewRange(vgcodePtr, min.toLong(), max.toLong())
            VGCodeNative.render(vgcodePtr, camera.viewMatrix, camera.projectionMatrix)
        }

        if (pendingContentReadyDispatch) {
            pendingContentReadyDispatch = false
            onContentReady?.let { callback -> mainHandler.post { callback() } }
        }
    }

    fun uploadGcode(gcode: ParsedGcode) {
        lastGcode = gcode
        totalLayers = gcode.layers.size
        maxLayer = totalLayers - 1
        if (totalLayers == 0 || vgcodePtr == 0L) return

        VGCodeNative.loadGcode(vgcodePtr, gcode, toolColors)

        // Sync travel visibility state
        val travelsVisible = VGCodeNative.isOptionVisible(vgcodePtr, VGCodeNative.OPTION_TRAVELS)
        if (travelsVisible != showTravel) {
            VGCodeNative.toggleOptionVisibility(vgcodePtr, VGCodeNative.OPTION_TRAVELS)
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/u1/slicer/viewer/GcodeRenderer.kt
git commit -m "feat: rewrite GcodeRenderer to use libvgcode for instanced tube rendering"
```

---

### Task 7: Update GcodeViewerView for new view type API

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/viewer/GcodeViewerView.kt`

- [ ] **Step 1: Update GcodeViewerView**

The `setFeatureColorMode` method now just sets a pending flag (same pattern as before — the renderer handles it in `onDrawFrame`). No change needed to the view — it already works through `renderer.pendingColorMode`. Verify this is the case.

Actually, the existing `GcodeViewerView` API is compatible. The only thing that changed is internal to `GcodeRenderer`. No modifications needed to `GcodeViewerView.kt`.

- [ ] **Step 2: Verify no compilation errors**

```bash
./gradlew assembleDebug --no-daemon 2>&1 | tail -30
```

- [ ] **Step 3: Commit (if any changes)**

---

### Task 8: Remove old shader files and dead code

**Files:**
- Delete: `app/src/main/assets/shaders/toolpath.vert`
- Delete: `app/src/main/assets/shaders/toolpath.frag`
- Modify: `app/src/main/java/com/u1/slicer/viewer/GcodeRenderer.kt` (if `ShaderProgram` import remains)

- [ ] **Step 1: Delete unused toolpath shaders**

The toolpath shaders were only used by the old `GcodeRenderer`. libvgcode compiles its own shaders internally. `ShaderProgram.kt` is still used by `BedDrawable` and `ModelRenderer`, so keep it.

```bash
rm app/src/main/assets/shaders/toolpath.vert app/src/main/assets/shaders/toolpath.frag
```

- [ ] **Step 2: Verify no other code references these files**

```bash
grep -r "toolpath.vert\|toolpath.frag" app/src/main/java/ app/src/main/assets/
```
Expected: No matches (the new GcodeRenderer doesn't load any shaders).

- [ ] **Step 3: Commit**

```bash
git add -u app/src/main/assets/shaders/
git commit -m "chore: remove old toolpath shaders (replaced by libvgcode internal shaders)"
```

---

### Task 9: Build, install, and test on device

**Files:** (none modified — verification only)

- [ ] **Step 1: Run unit tests**

Run: `./gradlew testDebugUnitTest --no-daemon`
Expected: All tests pass (existing + new VGCodeDataMappingTest).

- [ ] **Step 2: Build and install**

Run: `./gradlew installDebug --no-daemon`
Expected: Build succeeds. The APK now contains both `libprusaslicer-jni.so` (slicer) and `libvgcode-jni.so` (gcode viewer).

- [ ] **Step 3: Manual smoke test**

1. **calib-cube** — Load `calib-cube-10-dual-colour-merged.3mf`, slice, view 3D gcode preview. Should show thick tube geometry with 2-color extruder coloring.
2. **GreatPyr (THE KEY TEST)** — Load `GreatPyr-2ColorAMS.3mf`, slice, view 3D gcode preview. **Should now show thick tubes instead of thin lines.** Zoom in to confirm tubes have visible width and lighting.
3. **Layer slider** — Drag the layer range slider. Only selected layers should render.
4. **Feature color toggle** — Tap the palette icon. Should switch between extruder colors and feature-type colors (walls, infill, support, etc. in different colors).
5. **Travel toggle** — Tap the visibility icon. Travel moves should appear/disappear.
6. **Camera** — Orbit, zoom, pan should work identically to before.
7. **Inline preview** — Go back to the main screen, verify the small inline gcode preview card also shows tubes.

- [ ] **Step 4: Commit with test results**

```bash
git add -A
git commit -m "verify: libvgcode gcode viewer working on device — tubes for all file sizes"
```

---

### Task 10: Update CLAUDE.md and test counts

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update test counts**

Add the new test class to the unit test list in CLAUDE.md:
```
- `viewer/VGCodeDataMappingTest.kt` (6) — libvgcode JNI data mapping: role mapping, move type mapping, tool color packing
```

Update the total test count accordingly.

- [ ] **Step 2: Note the libvgcode dependency in Architecture section**

Add under the Architecture section:
```
- **G-code Preview**: libvgcode (PrusaSlicer's toolpath viewer, AGPLv3) via JNI — instanced tube rendering
```

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: update CLAUDE.md with libvgcode integration and new test counts"
```
