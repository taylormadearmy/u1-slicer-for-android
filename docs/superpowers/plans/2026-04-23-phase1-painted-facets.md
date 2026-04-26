# Phase 1 Sub-Plan #1 — Painted facets → `KotlinBambuSnapshot.volumes` (counts-only)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Populate `KotlinBambuSnapshot.volumes` from a small set of counts-only JNI accessors so the 21 `volumes.size` entries in `app/src/androidTest/assets/diagnostics/known-disagreements.json` close, and `BambuParserDifferentialTest` stays green with a smaller baseline. No production rendering path is touched in this sub-plan.

**Architecture:** Five thin JNI externs on `NativeLibrary` read `g_model` directly (no re-parse, no new data structures). A new C++ translation unit `sapil_bambu_volumes.cpp` owns the JNI bodies; `count_paint_states` is lifted out of `sapil_bambu_snapshot.cpp`'s anonymous namespace into a header-declared helper to keep the paint-count logic single-sourced. `KotlinBambuSnapshot.snapshot` gains a `suspend` signature and a `NativeLibrary` parameter so it can hold `NativeLibrary.previewMutex` across one `loadModel` + per-volume accessor walk — matching the pattern already used by `NativeBambuSnapshot.snapshot`.

**Tech Stack:** Kotlin 1.9.22, Android NDK 26 / Clang 17, CMake + Ninja (`-j1`), JUnit4 + AndroidJUnit4, OrcaSlicer 2.2.4 libslic3r (`Model`, `ModelVolume`, `FacetsAnnotation`, `TriangleSelector`), kotlinx-coroutines `Mutex`.

---

## Operating rules (non-negotiable — see `feedback-bambu-refactor-gotchas.md`)

- **Worktree:** `c:/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native/`. Every Bash call starts at the MAIN repo CWD. Either use absolute paths or prefix each command with `WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ...`. State does NOT persist between Bash calls.
- **DEX:** androidTest methods must use `snake_case_names()`. NO backticked spaces. JVM unit tests under `app/src/test/` may use backticked names.
- **androidTest fixtures:** `InstrumentationRegistry.getInstrumentation().context.assets.open(name)` (test APK). NOT `targetContext.assets`. Copy to `targetContext.cacheDir` for a seekable file path. See `KotlinBambuSnapshotTest.kt:31-32`.
- **Device:** Pixel 8a `43211JEKB16931`. Has phantom `versionCode 257`; `./gradlew connectedDebugAndroidTest` fails with `INSTALL_FAILED_VERSION_DOWNGRADE`. Use manual adb:

```bash
adb -s 43211JEKB16931 uninstall com.u1.slicer.orca; adb -s 43211JEKB16931 uninstall com.u1.slicer.orca.test
./gradlew assembleDebug assembleDebugAndroidTest --no-daemon
adb -s 43211JEKB16931 install -r -d app/build/outputs/apk/debug/app-debug.apk
adb -s 43211JEKB16931 install -r -d app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s 43211JEKB16931 shell am instrument -w -r -e class <FQN>#<method> com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

- **`extern/` line ending dirtying:** After ANY native build, run `git restore -- app/src/main/cpp/extern/` BEFORE `git add`. These are vendored gmp/mpfr docs — never commit.
- **Native rebuild:** NDK 26 / Clang 17 / Release / ~20MB stripped. Incremental rebuild (one new `.cpp` + tweaks to `sapil_bambu_snapshot.cpp`/`sapil_bambu_snapshot.h`) should take 2-15 min. Use `ninja -j1` (OOMs at `-j2+`). Strip with `llvm-strip --strip-unneeded`. Verify `llvm-readelf -p .comment` shows `clang version 17.0.2`, size is ~19-21MB. NEVER ship Debug (80MB) or unstripped (516MB). Run builds in FOREGROUND for incremental (completes in bash timeout); background + harness notification only for fresh/first-time builds (~30-60 min).
- **Non-static globals from Phase 0:** `g_model`, `g_plate_data_list`, `g_is_bbl`, `g_file_version`, `g_model_info` are now file-scope externally linkable in `sapil_model.cpp`. New TUs `extern` them, never re-declare.
- **Accessor mutex discipline:** The new JNI accessors are PURE READS of `g_model` and must NOT acquire any lock. The Kotlin-side `NativeLibrary.previewMutex` is a coroutine Mutex owned by the CALLER. `KotlinBambuSnapshot.snapshot` will hold it around the `loadModel + walk` block, matching `NativeBambuSnapshot.snapshot`.

---

## File structure

**New files:**

| Path | Responsibility |
|---|---|
| `app/src/main/cpp/src/sapil_bambu_volumes.cpp` | Five JNI entry points for g_model walking (object count, volume count, model id, scalars, paint counts). Pure reads. Zero new globals. |

**Modified files:**

| Path | Change |
|---|---|
| `app/src/main/cpp/src/sapil_bambu_snapshot.h` | Declare `count_paint_states` in the `sapil` namespace so `sapil_bambu_volumes.cpp` can reuse it. |
| `app/src/main/cpp/src/sapil_bambu_snapshot.cpp` | Move `count_paint_states` out of the anonymous namespace into the `sapil` namespace (unchanged body). |
| `app/src/main/cpp/CMakeLists.txt` | Add `src/sapil_bambu_volumes.cpp` to the source list next to `src/sapil_bambu_snapshot.cpp` (~line 1121). |
| `app/src/main/cpp/src/slicer_wrapper.cpp` | Add forward `extern "C"` declarations for the five new JNI functions, or a single `#include <jni.h>` helper — we keep the implementations in `sapil_bambu_volumes.cpp` to avoid growing `slicer_wrapper.cpp`. Nothing to change here once the new TU is exporting `Java_*` entry points directly (JNI symbols are weak-linked by the loader). Verify only. |
| `app/src/main/java/com/u1/slicer/NativeLibrary.kt` | Five new `external fun` declarations with KDoc. |
| `app/src/main/java/com/u1/slicer/bambu/snapshot/KotlinBambuSnapshot.kt` | `snapshot` becomes `suspend`, takes a `NativeLibrary` param, and populates `volumes` by walking the new accessors under `previewMutex`. |
| `app/src/androidTest/java/com/u1/slicer/bambu/snapshot/KotlinBambuSnapshotTest.kt` | Wrap call in `runBlocking { }`, pass `NativeLibrary()`, add positive assertion about `snapshot.volumes`. |
| `app/src/androidTest/java/com/u1/slicer/bambu/snapshot/BambuParserDifferentialTest.kt` | Wrap the `KotlinBambuSnapshot.snapshot(tmp)` call in `runBlocking` and pass the `native` instance. |
| `app/src/androidTest/assets/diagnostics/known-disagreements.json` | Remove the 21 `volumes.size` entries (one per fixture). |
| `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so` | Stripped Release rebuild output. NOT hand-edited — regenerated by native build. |
| `CLAUDE.md` | Bump instrumented-test count line (`# instrumented tests across 18 classes`) if `KotlinBambuSnapshotTest` test count changes. |
| `docs/superpowers/plans/2026-04-23-phase1-kickoff-handoff.md` | Add a one-line "Sub-plan #1 complete — baseline shrunk to X entries" appendix. |
| `C:/Users/kevin/.claude/projects/c--Users-kevin-projects-u1-slicer-orca/memory/project-bambu-refactor.md` | Update the Phase 1 priority list with closure count. |

**Deliberately NOT touched (out of scope — later sub-plan):**

- `app/src/main/java/com/u1/slicer/bambu/ThreeMfMeshParser.kt` — the full Kotlin paint parser. Sub-plan follow-up.
- `app/src/main/java/com/u1/slicer/ui/ModelViewerScreen.kt:42` — the last production caller of `ThreeMfMeshParser.parse`. Doesn't use paint data anyway. Left alone.
- `app/src/main/java/com/u1/slicer/MainActivity.kt:2191` comment — drive-by cleanup is tempting; skip to keep diff narrow.

---

## Accessor design (Option C — counts-only)

Five functions, all pure reads of `g_model`:

```kotlin
// NativeLibrary.kt additions — declared exactly in this order for diff-review clarity.

/**
 * Number of ModelObjects in g_model. Returns 0 when no model is loaded.
 * Callers must hold NativeLibrary.previewMutex across any sequence of
 * accessor calls to prevent races with setModelRotation / loadModel.
 */
external fun nativeGetObjectCount(): Int

/**
 * Number of ModelVolumes on g_model.objects[objectIndex]. Returns 0 for
 * out-of-range objectIndex or when no model is loaded.
 */
external fun nativeGetVolumeCount(objectIndex: Int): Int

/**
 * Slic3r runtime ObjectID (ObjectBase::id().id, size_t → Long). Matches the
 * VolumeSnapshot.objectId contract established by sapil_bambu_snapshot.cpp
 * append_volume(). Returns 0L for out-of-range objectIndex.
 */
external fun nativeGetObjectModelId(objectIndex: Int): Long

/**
 * Packed per-volume scalars: [extruder, isMmPaintedBool, isSeamPaintedBool].
 *   - extruder: mv.config.opt_int("extruder") when mv.config.has("extruder"),
 *     else -1 as the null sentinel (decoded into VolumeSnapshot.extruder: Int?).
 *   - isMmPaintedBool / isSeamPaintedBool: 1 or 0.
 * Returns null for out-of-range indices or when no model is loaded.
 */
external fun nativeGetVolumeScalars(objectIndex: Int, volumeIndex: Int): IntArray?

/**
 * Triangle counts per painted state on a single FacetsAnnotation.
 *   - kind = 0 -> mv.mmu_segmentation_facets
 *   - kind = 1 -> mv.supported_facets
 * Returns a packed array [state1, count1, state2, count2, ...] sorted by
 * state ascending. Empty array if no paint data. Null for out-of-range
 * indices, invalid kind, or when no model is loaded.
 *
 * Internally calls sapil::count_paint_states, identical to the helper used
 * by bambu_snapshot_json, so counts are guaranteed to match Phase 0's emitter.
 */
external fun nativeGetPaintStateCounts(
    objectIndex: Int,
    volumeIndex: Int,
    kind: Int,
): IntArray?
```

C++ side (`sapil_bambu_volumes.cpp`) — pattern for every function:

```cpp
// sapil_bambu_volumes.cpp
//
// Phase 1 sub-plan #1: JNI accessors for g_model volumes. Pure reads —
// no globals, no allocations beyond the return jarray. Callers hold
// NativeLibrary.previewMutex on the Kotlin side; C++ assumes serialised access.

#include <jni.h>

#include "libslic3r/Model.hpp"
#include "libslic3r/TriangleSelector.hpp"

#include "sapil_bambu_snapshot.h"  // for sapil::count_paint_states

namespace {

// Phase 0 made g_model file-scope externally linkable — extern it here, never re-define.
extern "C" {
} // extern "C" (placeholder; the real globals are Slic3r namespace, below)

} // namespace

namespace sapil {
// Provided by sapil_model.cpp.
extern Slic3r::Model g_model;
} // namespace sapil

extern "C" {

JNIEXPORT jint JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetObjectCount(JNIEnv*, jobject) {
    return static_cast<jint>(sapil::g_model.objects.size());
}

JNIEXPORT jint JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetVolumeCount(JNIEnv*, jobject, jint objectIndex) {
    if (objectIndex < 0) return 0;
    const auto& objs = sapil::g_model.objects;
    if (static_cast<size_t>(objectIndex) >= objs.size()) return 0;
    const auto* mo = objs[objectIndex];
    if (mo == nullptr) return 0;
    return static_cast<jint>(mo->volumes.size());
}

JNIEXPORT jlong JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetObjectModelId(JNIEnv*, jobject, jint objectIndex) {
    if (objectIndex < 0) return 0;
    const auto& objs = sapil::g_model.objects;
    if (static_cast<size_t>(objectIndex) >= objs.size()) return 0;
    const auto* mo = objs[objectIndex];
    if (mo == nullptr) return 0;
    return static_cast<jlong>(mo->id().id);
}

JNIEXPORT jintArray JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetVolumeScalars(
        JNIEnv* env, jobject, jint objectIndex, jint volumeIndex) {
    if (objectIndex < 0 || volumeIndex < 0) return nullptr;
    const auto& objs = sapil::g_model.objects;
    if (static_cast<size_t>(objectIndex) >= objs.size()) return nullptr;
    const auto* mo = objs[objectIndex];
    if (mo == nullptr) return nullptr;
    if (static_cast<size_t>(volumeIndex) >= mo->volumes.size()) return nullptr;
    const auto* mv = mo->volumes[volumeIndex];
    if (mv == nullptr) return nullptr;

    jint packed[3];
    packed[0] = mv->config.has("extruder")
        ? static_cast<jint>(mv->config.opt_int("extruder"))
        : -1;
    packed[1] = mv->is_mm_painted() ? 1 : 0;
    packed[2] = mv->is_seam_painted() ? 1 : 0;

    jintArray out = env->NewIntArray(3);
    if (out == nullptr) return nullptr;
    env->SetIntArrayRegion(out, 0, 3, packed);
    return out;
}

JNIEXPORT jintArray JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetPaintStateCounts(
        JNIEnv* env, jobject, jint objectIndex, jint volumeIndex, jint kind) {
    if (objectIndex < 0 || volumeIndex < 0) return nullptr;
    if (kind != 0 && kind != 1) return nullptr;
    const auto& objs = sapil::g_model.objects;
    if (static_cast<size_t>(objectIndex) >= objs.size()) return nullptr;
    const auto* mo = objs[objectIndex];
    if (mo == nullptr) return nullptr;
    if (static_cast<size_t>(volumeIndex) >= mo->volumes.size()) return nullptr;
    const auto* mv = mo->volumes[volumeIndex];
    if (mv == nullptr) return nullptr;

    const Slic3r::FacetsAnnotation& facets =
        (kind == 0) ? mv->mmu_segmentation_facets : mv->supported_facets;
    std::map<int, int> counts = sapil::count_paint_states(*mv, facets);

    jintArray out = env->NewIntArray(static_cast<jsize>(counts.size() * 2));
    if (out == nullptr) return nullptr;
    std::vector<jint> packed;
    packed.reserve(counts.size() * 2);
    for (const auto& kv : counts) {
        packed.push_back(static_cast<jint>(kv.first));
        packed.push_back(static_cast<jint>(kv.second));
    }
    env->SetIntArrayRegion(out, 0, static_cast<jsize>(packed.size()), packed.data());
    return out;
}

} // extern "C"
```

Kotlin consumer (`KotlinBambuSnapshot.snapshot`):

```kotlin
// New signature:
suspend fun snapshot(file: File, native: NativeLibrary): BambuFileSnapshot { ... }

// Inside, after ThreeMfParser.parse etc, build volumes:
val volumes = NativeLibrary.previewMutex.withLock {
    val loaded = native.loadModel(file.absolutePath)
    if (!loaded) return@withLock emptyList<VolumeSnapshot>()
    val objectCount = native.nativeGetObjectCount()
    buildList {
        for (oi in 0 until objectCount) {
            val objectModelId = native.nativeGetObjectModelId(oi).toInt()
            val volumeCount = native.nativeGetVolumeCount(oi)
            for (vi in 0 until volumeCount) {
                val scalars = native.nativeGetVolumeScalars(oi, vi) ?: continue
                val extruder = if (scalars[0] == -1) null else scalars[0]
                val mmPacked = native.nativeGetPaintStateCounts(oi, vi, 0).orEmpty()
                val supPacked = native.nativeGetPaintStateCounts(oi, vi, 1).orEmpty()
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
}
// helper:
private fun unpackStateCounts(packed: IntArray): Map<Int, Int> {
    if (packed.isEmpty()) return emptyMap()
    require(packed.size % 2 == 0) { "packed paint-state counts must be even-length" }
    val out = LinkedHashMap<Int, Int>(packed.size / 2)
    var i = 0
    while (i < packed.size) { out[packed[i]] = packed[i + 1]; i += 2 }
    return out
}

// VolumeSnapshot.objectId is Int in BambuFileSnapshot.kt, so we truncate the
// Slic3r ObjectID (size_t) to Int. Slic3r object IDs are sequential from ~1
// and never reach 2^31 in practice — a test fixture with a 2B-object file
// would be a bigger problem. Document the truncation in KDoc.
```

`NativeLibrary.loadModel` currently returns a `Boolean`; this is fine. `nativeDumpBambuModel` already does `loadModel` internally to guarantee a clean snapshot (slicer_wrapper.cpp:145-147), but for `KotlinBambuSnapshot` we want deterministic sequencing so we call `loadModel` explicitly. This does mean `NativeBambuSnapshot.snapshot` runs first (inside the test), reloading g_model; then `KotlinBambuSnapshot.snapshot` runs second, reloading g_model again — the two snapshots never interleave reads against the same g_model state. They load-and-read atomically under the shared mutex.

---

## Task 1: Establish a clean starting point

**Files:**
- Inspect only: `app/src/androidTest/assets/diagnostics/known-disagreements.json`

- [ ] **Step 1: Verify clean git state in the worktree**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && git status --short && git log -1 --oneline
```
Expected: no output from `git status --short`. `git log -1` shows the HEAD commit (e.g. `e4f910c phase0(bambu-diff): final review follow-ups + Phase 1 pre-flight docs`). If dirty, STOP and ask the user how to proceed — do not reset without permission.

- [ ] **Step 2: Count baseline `volumes.size` entries**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && grep -c '"path": "volumes.size"' "$WT/app/src/androidTest/assets/diagnostics/known-disagreements.json"
```
Expected: `21`. If a different number, note it — the Task 10 removal count must match whatever is there today.

- [ ] **Step 3: Run the differential test to confirm GREEN baseline**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && adb -s 43211JEKB16931 uninstall com.u1.slicer.orca; adb -s 43211JEKB16931 uninstall com.u1.slicer.orca.test; ./gradlew assembleDebug assembleDebugAndroidTest --no-daemon && adb -s 43211JEKB16931 install -r -d app/build/outputs/apk/debug/app-debug.apk && adb -s 43211JEKB16931 install -r -d app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk && adb -s 43211JEKB16931 shell am instrument -w -r -e class com.u1.slicer.bambu.snapshot.BambuParserDifferentialTest com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```
Expected: `OK (21 tests)` in the instrument output. If any test fails with an "unexpected diff" message, STOP — baseline is broken and sub-plan #1 cannot start on a broken baseline. If a test fails because the device is offline or the APK won't install, fix the device state and re-run. Do not continue until 21/21 are green.

- [ ] **Step 4: Commit nothing. Move to Task 2.**

No git operations here. Task 1 is a verification gate.

---

## Task 2: Lift `count_paint_states` into the `sapil` namespace

**Files:**
- Modify: `app/src/main/cpp/src/sapil_bambu_snapshot.h`
- Modify: `app/src/main/cpp/src/sapil_bambu_snapshot.cpp` (move a function out of the anonymous namespace)

- [ ] **Step 1: Add the declaration to the header**

Open `app/src/main/cpp/src/sapil_bambu_snapshot.h` and append after the existing `#include`:

```cpp
#include <map>

namespace Slic3r {
    class ModelVolume;
    class FacetsAnnotation;
}

namespace sapil {

/**
 * Counts triangles per paint state on one FacetsAnnotation.
 * State 0 (NONE) is never emitted; returned map is sorted by state ascending.
 * Shared with Phase 1 sub-plan #1 JNI accessors — must stay behaviourally
 * identical to what `bambu_snapshot_json` emits for paint counts.
 */
std::map<int, int> count_paint_states(const Slic3r::ModelVolume& mv,
                                      const Slic3r::FacetsAnnotation& facets);

} // namespace sapil
```

- [ ] **Step 2: Move the definition out of the anonymous namespace**

Open `app/src/main/cpp/src/sapil_bambu_snapshot.cpp`. The function currently lives between `namespace {` (line 50) and `} // namespace` (line 300). Move `count_paint_states` (lines 239-252) to AFTER the `} // namespace` that closes the anonymous block, into the surrounding `namespace sapil { ... }`. Keep the body byte-identical; only change its surrounding scope.

Result should look like:

```cpp
namespace sapil {

// ... existing includes, extern declarations, etc ...

namespace {

// json_escape, colour_to_hex, custom_gcode_type_name, append_plate,
// append_object, append_volume — ALL stay here.
// count_paint_states is REMOVED from this block.

} // namespace

// Newly at namespace sapil scope:
std::map<int, int> count_paint_states(const Slic3r::ModelVolume& mv,
                                      const Slic3r::FacetsAnnotation& facets) {
    std::map<int, int> counts;
    const int max_state = static_cast<int>(Slic3r::EnforcerBlockerType::ExtruderMax);
    for (int state = 1; state <= max_state; ++state) {
        auto type = static_cast<Slic3r::EnforcerBlockerType>(state);
        if (facets.has_facets(mv, type)) {
            indexed_triangle_set its = facets.get_facets(mv, type);
            int n = static_cast<int>(its.indices.size());
            if (n > 0) counts[state] = n;
        }
    }
    return counts;
}

std::string bambu_snapshot_json() {
    // ... existing body, now calls sapil::count_paint_states via unqualified
    // lookup (same namespace) — no code change inside this function.
}

} // namespace sapil
```

The `append_volume` function (still inside the anonymous namespace) already calls `count_paint_states(...)` unqualified — after the move, unqualified lookup still resolves via the enclosing `sapil` namespace, so no call-site edits needed.

- [ ] **Step 3: Sanity-build just this TU to catch typos**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ls app/.cxx/Debug/ndk26release/arm64-v8a/build.ninja
```
If that file exists, run the incremental compile of just sapil_bambu_snapshot.cpp (no link yet):

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && cd app/.cxx/Debug/ndk26release/arm64-v8a && C:/Users/kevin/AppData/Local/Android/Sdk/cmake/3.22.1/bin/ninja.exe -j1 CMakeFiles/prusaslicer-jni.dir/src/sapil_bambu_snapshot.cpp.o
```
Expected: compiles cleanly in <90s. If the build dir doesn't exist yet, skip this step — Task 7 will cover the full rebuild.

If the compile fails with a "no matching function" or "count_paint_states undeclared" error, the move is wrong. Fix the scoping and retry.

- [ ] **Step 4: No commit yet — this change is half-complete without its consumer**

Task 2 is a prep refactor. Hold off on committing until Task 6 is done so a single coherent commit lands.

---

## Task 3: Add `nativeGetObjectCount` + `nativeGetVolumeCount` JNI externs + Kotlin declarations

**Files:**
- Create: `app/src/main/cpp/src/sapil_bambu_volumes.cpp`
- Modify: `app/src/main/java/com/u1/slicer/NativeLibrary.kt`
- Modify: `app/src/main/cpp/CMakeLists.txt`

- [ ] **Step 1: Write the failing correctness test for these two accessors**

Open `app/src/androidTest/java/com/u1/slicer/native/NativeLibraryCorrectnessTest.kt`. Inside the existing class, add AFTER the last `@Test`:

```kotlin
/**
 * Phase 1 sub-plan #1: g_model iteration accessors.
 * Loads a Bambu fixture (Flarewing Dragon — plate-heavy + multi-volume) and
 * asserts that nativeGetObjectCount / nativeGetVolumeCount report the same
 * counts that Phase 0's bambu_snapshot_json emits.
 */
@Test
fun nativeGetObjectCount_matchesGModelState_forBambuFixture() {
    val assetContext = InstrumentationRegistry.getInstrumentation().context
    val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    val fixture = File(targetContext.cacheDir, "phase1_fixture.3mf")
    assetContext.assets.open("Flarewing-Dragon_100%_4FilamentMulticolor_v1.1.3mf").use { input ->
        fixture.outputStream().use { input.copyTo(it) }
    }
    try {
        assertTrue(lib.loadModel(fixture.absolutePath))
        val objectCount = lib.nativeGetObjectCount()
        assertTrue("expected >= 1 ModelObject for Bambu fixture, got $objectCount", objectCount >= 1)

        // At least one object has >= 1 volume.
        var sawVolumes = false
        for (oi in 0 until objectCount) {
            val vc = lib.nativeGetVolumeCount(oi)
            if (vc > 0) sawVolumes = true
            assertTrue("volume count must be non-negative, got $vc at oi=$oi", vc >= 0)
        }
        assertTrue("at least one object must have >= 1 volume", sawVolumes)

        // Out-of-range returns 0, not a crash.
        assertEquals(0, lib.nativeGetVolumeCount(objectCount))
        assertEquals(0, lib.nativeGetVolumeCount(-1))
    } finally {
        fixture.delete()
    }
}

@Test
fun nativeGetObjectCount_returnsZero_whenNoModelLoaded() {
    lib.clearModel()
    assertEquals(0, lib.nativeGetObjectCount())
    assertEquals(0, lib.nativeGetVolumeCount(0))
}
```

- [ ] **Step 2: Add the Kotlin `external fun` declarations**

Open `app/src/main/java/com/u1/slicer/NativeLibrary.kt`. After the existing `external fun nativeDumpBambuModel(...)` block (around line 84), add a new section:

```kotlin
// ---- Phase 1 sub-plan #1: g_model volume walkers ----
// Pure reads of g_model. Callers MUST hold NativeLibrary.previewMutex across a
// logical sequence of these calls (to prevent races with loadModel / setModelRotation).
// These five accessors back KotlinBambuSnapshot.volumes population.

/** Count of ModelObjects in g_model. Returns 0 when no model loaded. */
external fun nativeGetObjectCount(): Int

/** Count of ModelVolumes on g_model.objects[objectIndex]. Returns 0 for OOR. */
external fun nativeGetVolumeCount(objectIndex: Int): Int
```

- [ ] **Step 3: Create `sapil_bambu_volumes.cpp` with just these two JNI entries**

Create `app/src/main/cpp/src/sapil_bambu_volumes.cpp` with the following content. The remaining three JNI entries land in Tasks 4-6; this file grows by small additions rather than one big drop, so Task-wise reviewers can audit each accessor.

```cpp
// sapil_bambu_volumes.cpp
//
// Phase 1 sub-plan #1: JNI accessors for walking g_model's objects + volumes.
// Pure reads — no globals, no allocations beyond returned jarrays. Callers
// hold NativeLibrary.previewMutex on the Kotlin side; C++ assumes serialised
// access. Phase 0 made g_model non-static externally linkable in sapil_model.cpp;
// every global here `extern`s from there.

#include <jni.h>

#include "libslic3r/Model.hpp"

namespace sapil {
// Provided by sapil_model.cpp.
extern Slic3r::Model g_model;
} // namespace sapil

extern "C" {

JNIEXPORT jint JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetObjectCount(JNIEnv*, jobject) {
    return static_cast<jint>(sapil::g_model.objects.size());
}

JNIEXPORT jint JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetVolumeCount(
        JNIEnv*, jobject, jint objectIndex) {
    if (objectIndex < 0) return 0;
    const auto& objs = sapil::g_model.objects;
    if (static_cast<size_t>(objectIndex) >= objs.size()) return 0;
    const auto* mo = objs[objectIndex];
    if (mo == nullptr) return 0;
    return static_cast<jint>(mo->volumes.size());
}

} // extern "C"
```

- [ ] **Step 4: Wire the new TU into the CMake build**

Open `app/src/main/cpp/CMakeLists.txt`. Find the block ending at line ~1121 (`src/sapil_bambu_snapshot.cpp`) and add the new file on the next line. The block should look like:

```cmake
    src/sapil_bambu_snapshot.cpp
    src/sapil_bambu_volumes.cpp
```

Do not re-order existing entries. Do not touch anything else in the file.

- [ ] **Step 5: Verify gradle picks up the new Kotlin externs**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ./gradlew :app:compileDebugKotlin --no-daemon
```
Expected: compiles cleanly. If it fails with "unresolved reference" or missing import, the declarations are syntactically wrong — fix and retry.

- [ ] **Step 6: No native build yet. No commit yet.** Move to Task 4.

---

## Task 4: Add `nativeGetObjectModelId`

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/NativeLibrary.kt`
- Modify: `app/src/main/cpp/src/sapil_bambu_volumes.cpp`

- [ ] **Step 1: Write the failing correctness assertion**

In `NativeLibraryCorrectnessTest.kt`, extend the Task-3 test OR add a new test:

```kotlin
@Test
fun nativeGetObjectModelId_isNonZero_forLoadedBambuFixture() {
    val assetContext = InstrumentationRegistry.getInstrumentation().context
    val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    val fixture = File(targetContext.cacheDir, "phase1_fixture.3mf")
    assetContext.assets.open("Flarewing-Dragon_100%_4FilamentMulticolor_v1.1.3mf").use { input ->
        fixture.outputStream().use { input.copyTo(it) }
    }
    try {
        assertTrue(lib.loadModel(fixture.absolutePath))
        val objectCount = lib.nativeGetObjectCount()
        for (oi in 0 until objectCount) {
            val id = lib.nativeGetObjectModelId(oi)
            assertTrue("object $oi ObjectID must be > 0, got $id", id > 0L)
        }
        // OOR returns 0L, not a crash.
        assertEquals(0L, lib.nativeGetObjectModelId(objectCount))
        assertEquals(0L, lib.nativeGetObjectModelId(-1))
    } finally {
        fixture.delete()
    }
}
```

- [ ] **Step 2: Add the Kotlin extern**

In `NativeLibrary.kt`, after `nativeGetVolumeCount`:

```kotlin
/**
 * Slic3r runtime ObjectID (ObjectBase::id().id, size_t → Long).
 * Matches the VolumeSnapshot.objectId contract from sapil_bambu_snapshot.cpp
 * append_volume(). Returns 0L for out-of-range objectIndex.
 */
external fun nativeGetObjectModelId(objectIndex: Int): Long
```

- [ ] **Step 3: Add the C++ entry**

In `sapil_bambu_volumes.cpp`, after the existing `nativeGetVolumeCount` entry, inside the same `extern "C" { }`:

```cpp
JNIEXPORT jlong JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetObjectModelId(
        JNIEnv*, jobject, jint objectIndex) {
    if (objectIndex < 0) return 0;
    const auto& objs = sapil::g_model.objects;
    if (static_cast<size_t>(objectIndex) >= objs.size()) return 0;
    const auto* mo = objs[objectIndex];
    if (mo == nullptr) return 0;
    return static_cast<jlong>(mo->id().id);
}
```

- [ ] **Step 4: Kotlin compile check**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ./gradlew :app:compileDebugKotlin --no-daemon
```
Expected: PASS.

- [ ] **Step 5: No native build yet. No commit yet.** Move to Task 5.

---

## Task 5: Add `nativeGetVolumeScalars`

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/NativeLibrary.kt`
- Modify: `app/src/main/cpp/src/sapil_bambu_volumes.cpp`

- [ ] **Step 1: Add failing correctness assertion**

In `NativeLibraryCorrectnessTest.kt`:

```kotlin
@Test
fun nativeGetVolumeScalars_returnsThreePackedInts_forBambuFixture() {
    val assetContext = InstrumentationRegistry.getInstrumentation().context
    val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    val fixture = File(targetContext.cacheDir, "phase1_fixture.3mf")
    assetContext.assets.open("Flarewing-Dragon_100%_4FilamentMulticolor_v1.1.3mf").use { input ->
        fixture.outputStream().use { input.copyTo(it) }
    }
    try {
        assertTrue(lib.loadModel(fixture.absolutePath))
        val objectCount = lib.nativeGetObjectCount()
        var sawPaintedVolume = false
        for (oi in 0 until objectCount) {
            val vc = lib.nativeGetVolumeCount(oi)
            for (vi in 0 until vc) {
                val scalars = lib.nativeGetVolumeScalars(oi, vi)
                assertNotNull("scalars must be non-null for in-range (oi=$oi,vi=$vi)", scalars)
                scalars!!
                assertEquals("scalars must be 3 ints", 3, scalars.size)
                // extruder: -1 (unset) or >= 1 (1-based)
                assertTrue(
                    "extruder must be -1 or >= 1, got ${scalars[0]}",
                    scalars[0] == -1 || scalars[0] >= 1
                )
                // isMmPainted / isSeamPainted are 0 or 1
                assertTrue("isMmPainted flag must be 0 or 1, got ${scalars[1]}", scalars[1] in 0..1)
                assertTrue("isSeamPainted flag must be 0 or 1, got ${scalars[2]}", scalars[2] in 0..1)
                if (scalars[1] == 1) sawPaintedVolume = true
            }
        }
        // Flarewing Dragon is a paint-heavy multi-colour fixture.
        assertTrue("expected at least one mm-painted volume", sawPaintedVolume)

        // OOR returns null, not a crash.
        assertNull(lib.nativeGetVolumeScalars(-1, 0))
        assertNull(lib.nativeGetVolumeScalars(0, -1))
        assertNull(lib.nativeGetVolumeScalars(objectCount, 0))
    } finally {
        fixture.delete()
    }
}
```

- [ ] **Step 2: Add the Kotlin extern**

In `NativeLibrary.kt`, after `nativeGetObjectModelId`:

```kotlin
/**
 * Packed per-volume scalars: [extruder, isMmPaintedBool, isSeamPaintedBool].
 *   - extruder: mv.config.opt_int("extruder") when mv.config.has("extruder"),
 *     else -1 as the null sentinel (decoded into VolumeSnapshot.extruder: Int?).
 *   - isMmPaintedBool / isSeamPaintedBool: 1 or 0.
 * Returns null for out-of-range indices or when no model is loaded.
 */
external fun nativeGetVolumeScalars(objectIndex: Int, volumeIndex: Int): IntArray?
```

- [ ] **Step 3: Add the C++ entry**

In `sapil_bambu_volumes.cpp`, inside the same `extern "C" { }`:

```cpp
JNIEXPORT jintArray JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetVolumeScalars(
        JNIEnv* env, jobject, jint objectIndex, jint volumeIndex) {
    if (objectIndex < 0 || volumeIndex < 0) return nullptr;
    const auto& objs = sapil::g_model.objects;
    if (static_cast<size_t>(objectIndex) >= objs.size()) return nullptr;
    const auto* mo = objs[objectIndex];
    if (mo == nullptr) return nullptr;
    if (static_cast<size_t>(volumeIndex) >= mo->volumes.size()) return nullptr;
    const auto* mv = mo->volumes[volumeIndex];
    if (mv == nullptr) return nullptr;

    jint packed[3];
    packed[0] = mv->config.has("extruder")
        ? static_cast<jint>(mv->config.opt_int("extruder"))
        : -1;
    packed[1] = mv->is_mm_painted() ? 1 : 0;
    packed[2] = mv->is_seam_painted() ? 1 : 0;

    jintArray out = env->NewIntArray(3);
    if (out == nullptr) return nullptr;
    env->SetIntArrayRegion(out, 0, 3, packed);
    return out;
}
```

- [ ] **Step 4: Kotlin compile check**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ./gradlew :app:compileDebugKotlin --no-daemon
```
Expected: PASS.

- [ ] **Step 5: No commit yet.** Move to Task 6.

---

## Task 6: Add `nativeGetPaintStateCounts`

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/NativeLibrary.kt`
- Modify: `app/src/main/cpp/src/sapil_bambu_volumes.cpp`

- [ ] **Step 1: Add failing correctness assertion**

In `NativeLibraryCorrectnessTest.kt`:

```kotlin
@Test
fun nativeGetPaintStateCounts_matchesPhase0Snapshot_forFlarewing() {
    val assetContext = InstrumentationRegistry.getInstrumentation().context
    val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    val fixture = File(targetContext.cacheDir, "phase1_fixture.3mf")
    assetContext.assets.open("Flarewing-Dragon_100%_4FilamentMulticolor_v1.1.3mf").use { input ->
        fixture.outputStream().use { input.copyTo(it) }
    }
    try {
        assertTrue(lib.loadModel(fixture.absolutePath))
        val objectCount = lib.nativeGetObjectCount()

        var sawMmuCounts = false
        for (oi in 0 until objectCount) {
            val vc = lib.nativeGetVolumeCount(oi)
            for (vi in 0 until vc) {
                val mm = lib.nativeGetPaintStateCounts(oi, vi, 0)
                assertNotNull("mmu counts must be non-null for in-range (oi=$oi,vi=$vi)", mm)
                mm!!
                assertEquals("packed length must be even", 0, mm.size % 2)
                // If any counts exist, all count values are >= 1 and states are 1..16
                var i = 0
                while (i < mm.size) {
                    val state = mm[i]
                    val count = mm[i + 1]
                    assertTrue("state must be 1..16, got $state", state in 1..16)
                    assertTrue("count must be > 0, got $count", count > 0)
                    i += 2
                }
                if (mm.isNotEmpty()) sawMmuCounts = true
            }
        }
        assertTrue("Flarewing Dragon must have at least one volume with mmu counts", sawMmuCounts)

        // Invalid kind returns null.
        assertNull(lib.nativeGetPaintStateCounts(0, 0, 2))
        assertNull(lib.nativeGetPaintStateCounts(0, 0, -1))
        // OOR returns null.
        assertNull(lib.nativeGetPaintStateCounts(-1, 0, 0))
        assertNull(lib.nativeGetPaintStateCounts(0, -1, 0))
        assertNull(lib.nativeGetPaintStateCounts(objectCount, 0, 0))
    } finally {
        fixture.delete()
    }
}
```

- [ ] **Step 2: Add the Kotlin extern**

In `NativeLibrary.kt`, after `nativeGetVolumeScalars`:

```kotlin
/**
 * Triangle counts per painted state on a single FacetsAnnotation.
 *   - kind = 0 -> mv.mmu_segmentation_facets
 *   - kind = 1 -> mv.supported_facets
 * Returns a packed array [state1, count1, state2, count2, ...] sorted by
 * state ascending. Empty array when the annotation has no painted triangles.
 * Null for out-of-range indices, invalid kind, or when no model is loaded.
 *
 * Internally delegates to sapil::count_paint_states — the same helper used
 * by bambu_snapshot_json, so counts are guaranteed to match Phase 0's output.
 */
external fun nativeGetPaintStateCounts(
    objectIndex: Int,
    volumeIndex: Int,
    kind: Int,
): IntArray?
```

- [ ] **Step 3: Add the C++ entry**

In `sapil_bambu_volumes.cpp`, at the TOP of the file add after the existing `#include "libslic3r/Model.hpp"`:

```cpp
#include <map>
#include <vector>

#include "libslic3r/TriangleSelector.hpp"

#include "sapil_bambu_snapshot.h"  // for sapil::count_paint_states
```

Then inside the `extern "C" { }`, after `nativeGetVolumeScalars`:

```cpp
JNIEXPORT jintArray JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetPaintStateCounts(
        JNIEnv* env, jobject, jint objectIndex, jint volumeIndex, jint kind) {
    if (objectIndex < 0 || volumeIndex < 0) return nullptr;
    if (kind != 0 && kind != 1) return nullptr;
    const auto& objs = sapil::g_model.objects;
    if (static_cast<size_t>(objectIndex) >= objs.size()) return nullptr;
    const auto* mo = objs[objectIndex];
    if (mo == nullptr) return nullptr;
    if (static_cast<size_t>(volumeIndex) >= mo->volumes.size()) return nullptr;
    const auto* mv = mo->volumes[volumeIndex];
    if (mv == nullptr) return nullptr;

    const Slic3r::FacetsAnnotation& facets =
        (kind == 0) ? mv->mmu_segmentation_facets : mv->supported_facets;
    std::map<int, int> counts = sapil::count_paint_states(*mv, facets);

    std::vector<jint> packed;
    packed.reserve(counts.size() * 2);
    for (const auto& kv : counts) {
        packed.push_back(static_cast<jint>(kv.first));
        packed.push_back(static_cast<jint>(kv.second));
    }

    jintArray out = env->NewIntArray(static_cast<jsize>(packed.size()));
    if (out == nullptr) return nullptr;
    if (!packed.empty()) {
        env->SetIntArrayRegion(out, 0, static_cast<jsize>(packed.size()), packed.data());
    }
    return out;
}
```

- [ ] **Step 4: Kotlin compile check**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ./gradlew :app:compileDebugKotlin --no-daemon
```
Expected: PASS.

- [ ] **Step 5: No commit yet.** Task 7 rebuilds the native `.so`.

---

## Task 7: Native rebuild and on-device accessor verification

**Files:**
- Generate: `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so` (stripped Release)

- [ ] **Step 1: Verify NDK 26 is configured**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && grep "CMAKE_TOOLCHAIN_FILE\|CMAKE_BUILD_TYPE" app/.cxx/Debug/ndk26release/arm64-v8a/CMakeCache.txt
```
Expected: toolchain path contains `ndk/26.1.10909125`; `CMAKE_BUILD_TYPE:STRING=Release`. If not Release or not NDK 26, STOP and use the "Fresh build" block from the root `CLAUDE.md` before proceeding.

- [ ] **Step 2: Re-run CMake configure so the new TU is picked up**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && C:/Users/kevin/AppData/Local/Android/Sdk/cmake/3.22.1/bin/cmake.exe -Bapp/.cxx/Debug/ndk26release/arm64-v8a
```
Expected: "Generating done". If CMake errors with "No such file" on `sapil_bambu_volumes.cpp`, the path in `CMakeLists.txt` (Task 3 Step 4) is wrong — fix and retry.

- [ ] **Step 3: Build the stripped Release `.so` in the foreground**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && cd app/.cxx/Debug/ndk26release/arm64-v8a && C:/Users/kevin/AppData/Local/Android/Sdk/cmake/3.22.1/bin/ninja.exe -j1 prusaslicer-jni
```
Expected: completes in 2-15 min (incremental). If it runs 30+ min, the build dir is stale — use the fresh-build path from `CLAUDE.md`.

- [ ] **Step 4: Strip and install**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && C:/Users/kevin/AppData/Local/Android/Sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe --strip-unneeded app/.cxx/Debug/ndk26release/arm64-v8a/libprusaslicer-jni.so && cp app/.cxx/Debug/ndk26release/arm64-v8a/libprusaslicer-jni.so app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so
```

- [ ] **Step 5: Verify size and compiler**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ls -l app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so && C:/Users/kevin/AppData/Local/Android/Sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe -p .comment app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so | head -5
```
Expected:
- Size: `19-21 MiB` (~20MB). If `50MB+` the build was Debug — redo.
- `.comment` shows `clang version 17.0.2`. If 14.x, NDK 25 slipped in — redo with NDK 26.

- [ ] **Step 6: Verify all five new JNI symbols are exported**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && C:/Users/kevin/AppData/Local/Android/Sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe --dyn-syms app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so | grep -E "nativeGetObjectCount|nativeGetVolumeCount|nativeGetObjectModelId|nativeGetVolumeScalars|nativeGetPaintStateCounts"
```
Expected: five lines listed as `GLOBAL DEFAULT FUNC`. If a symbol is missing, the C++ change didn't compile into the binary — recheck Task 3/4/5/6 and rebuild.

- [ ] **Step 7: Restore vendored extern line endings**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && git restore -- app/src/main/cpp/extern/
```
Expected: silent. The build step touches extern/gmp and extern/mpfr docs — always restore before staging commits.

- [ ] **Step 8: Run the five new correctness tests on-device**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && adb -s 43211JEKB16931 uninstall com.u1.slicer.orca; adb -s 43211JEKB16931 uninstall com.u1.slicer.orca.test; ./gradlew assembleDebug assembleDebugAndroidTest --no-daemon && adb -s 43211JEKB16931 install -r -d app/build/outputs/apk/debug/app-debug.apk && adb -s 43211JEKB16931 install -r -d app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk && adb -s 43211JEKB16931 shell am instrument -w -r -e class com.u1.slicer.native.NativeLibraryCorrectnessTest com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```
Expected: all NativeLibraryCorrectnessTest cases pass, including the five new ones added in Tasks 3-6. Pre-existing tests (`loadModel_returnsTrue_forValidStl`, etc.) must still pass.

If a new test fails:
- `UnsatisfiedLinkError: No implementation found for ...` → the JNI symbol name doesn't match (typo in `Java_com_u1_slicer_NativeLibrary_...`). Check spelling and rebuild.
- `AssertionError` on paint counts → native implementation differs from Phase 0 `bambu_snapshot_json`. Compare manually against `nativeDumpBambuModel` output on the same fixture.

- [ ] **Step 9: No commit yet.** Kotlin integration lands in Task 8.

---

## Task 8: Populate `KotlinBambuSnapshot.volumes` from the new accessors

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/bambu/snapshot/KotlinBambuSnapshot.kt`

- [ ] **Step 1: Change `snapshot` to suspend + accept `NativeLibrary`**

In `KotlinBambuSnapshot.kt`, replace the entire `snapshot(file: File): BambuFileSnapshot` function signature and body with the version below. Keep all the existing Kotlin-parsed plate / object construction untouched; the change adds volumes population only.

```kotlin
package com.u1.slicer.bambu.snapshot

import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.ThreeMfParser
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.zip.ZipFile

object KotlinBambuSnapshot {

    private fun parseObjectId(s: String): Int = s.toIntOrNull() ?: -1

    /**
     * Produces a [BambuFileSnapshot] combining the existing Kotlin parsers
     * (which own plates / objects / custom-gcode) with a native walk of
     * g_model for the volumes list. Phase 1 sub-plan #1 introduces the
     * native-backed volumes population; other fields remain Kotlin-parsed.
     *
     * Suspend because [NativeLibrary.previewMutex] is a coroutine Mutex.
     * Callers in instrumented tests wrap with `runBlocking { }`.
     */
    suspend fun snapshot(file: File, native: NativeLibrary): BambuFileSnapshot {
        if (!file.exists() || !file.name.endsWith(".3mf", ignoreCase = true)) {
            return empty(file.name)
        }
        val info = ThreeMfParser.parse(file)
        val customGcodeByPlate = readCustomGcodeByPlate(file)

        val plates = info.plates.map { plate ->
            PlateSnapshot(
                plateIndex = plate.plateId,
                filamentColours = info.detectedColors.toList(),
                filamentSettingsIds = emptyList(),
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

        val volumes = readVolumesViaNative(file, native)

        return BambuFileSnapshot(
            source = file.name,
            isBbl = info.isBambu,
            fileVersion = "",
            plates = plates,
            objects = objects,
            volumes = volumes,
        )
    }

    /**
     * Walks g_model via the Phase 1 JNI accessors to produce the
     * [VolumeSnapshot] list. Holds [NativeLibrary.previewMutex] across the
     * whole loadModel + per-volume accessor walk so no concurrent
     * setModelRotation / getPreparePreviewMesh caller races the read.
     *
     * Returns [emptyList] if loadModel fails — mirrors the pre-sub-plan behaviour
     * so a corrupt 3MF surfaces as "no volumes" rather than blowing up the
     * whole snapshot. The diff harness will see a `volumes.size` disagreement
     * on broken fixtures, which is the correct behaviour.
     */
    private suspend fun readVolumesViaNative(
        file: File,
        native: NativeLibrary,
    ): List<VolumeSnapshot> = NativeLibrary.previewMutex.withLock {
        if (!native.loadModel(file.absolutePath)) return@withLock emptyList()
        val objectCount = native.nativeGetObjectCount()
        buildList {
            for (oi in 0 until objectCount) {
                // VolumeSnapshot.objectId is Int. Slic3r ObjectID is size_t;
                // truncation is safe in practice (IDs start at ~1 and increment).
                val objectModelId = native.nativeGetObjectModelId(oi).toInt()
                val volumeCount = native.nativeGetVolumeCount(oi)
                for (vi in 0 until volumeCount) {
                    val scalars = native.nativeGetVolumeScalars(oi, vi) ?: continue
                    val extruder = if (scalars[0] == -1) null else scalars[0]
                    val mmPacked = native.nativeGetPaintStateCounts(oi, vi, 0).orEmpty()
                    val supPacked = native.nativeGetPaintStateCounts(oi, vi, 1).orEmpty()
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
    }

    private fun unpackStateCounts(packed: IntArray): Map<Int, Int> {
        if (packed.isEmpty()) return emptyMap()
        require(packed.size % 2 == 0) { "packed paint-state counts must be even-length" }
        val out = LinkedHashMap<Int, Int>(packed.size / 2)
        var i = 0
        while (i < packed.size) { out[packed[i]] = packed[i + 1]; i += 2 }
        return out
    }

    private fun empty(name: String) = BambuFileSnapshot(
        source = name,
        isBbl = false,
        fileVersion = "",
        plates = emptyList(),
        objects = emptyList(),
        volumes = emptyList(),
    )

    private fun readCustomGcodeByPlate(file: File): Map<Int, List<CustomGcodeEntry>> {
        return try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("Metadata/custom_gcode_per_layer.xml")
                    ?: return emptyMap()
                val xml = zip.getInputStream(entry).bufferedReader().readText()
                parseCustomGcodeXmlPerPlate(xml)
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private val plateInfoIdRegex = Regex("""<plate_info\b[^>]*\bid="(\d+)"""")
    private val layerRegex = Regex("""<layer\b([^>]*)>""")
    private val topZRegex = Regex("""\btop_z="([^"]+)"""")
    private val typeRegex = Regex("""\btype="([^"]+)"""")
    private val extruderRegex = Regex("""\bextruder="([^"]+)"""")
    private val colorRegex = Regex("""\bcolor="([^"]+)"""")

    private fun parseCustomGcodeXmlPerPlate(xml: String): Map<Int, List<CustomGcodeEntry>> {
        val out = mutableMapOf<Int, List<CustomGcodeEntry>>()
        for (section in xml.split("<plate>").drop(1)) {
            val content = section.substringBefore("</plate>")
            val plateId = plateInfoIdRegex.find(content)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: continue
            val entries = layerRegex.findAll(content).mapNotNull { match ->
                val attrs = match.groupValues[1]
                val type = typeRegex.find(attrs)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
                val topZ = topZRegex.find(attrs)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                    ?: return@mapNotNull null
                val extruder = extruderRegex.find(attrs)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                val color = colorRegex.find(attrs)?.groupValues?.getOrNull(1) ?: ""
                CustomGcodeEntry(printZ = topZ, type = type, extruder = extruder, color = color)
            }.toList()
            out[plateId] = entries
        }
        return out
    }
}
```

- [ ] **Step 2: Verify compile**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ./gradlew :app:compileDebugKotlin --no-daemon
```
Expected: PASS. Callers of the old `KotlinBambuSnapshot.snapshot(file)` now fail to compile — those are updated in Task 9.

Actually, since callers are guaranteed to fail at this step, expect:
```
KotlinBambuSnapshotTest.kt:52: error: no value passed for parameter 'native'
BambuParserDifferentialTest.kt:45: error: no value passed for parameter 'native'
```
That's correct — they're updated next. Proceed to Task 9.

- [ ] **Step 3: No commit yet.** Move to Task 9.

---

## Task 9: Update test callers + add a native-volumes assertion

**Files:**
- Modify: `app/src/androidTest/java/com/u1/slicer/bambu/snapshot/KotlinBambuSnapshotTest.kt`
- Modify: `app/src/androidTest/java/com/u1/slicer/bambu/snapshot/BambuParserDifferentialTest.kt`

- [ ] **Step 1: Update `BambuParserDifferentialTest.runFixture`**

Open `BambuParserDifferentialTest.kt`. Replace line 45:

```kotlin
        val kotlinSnapshot = KotlinBambuSnapshot.snapshot(tmp)
```

with:

```kotlin
        val kotlinSnapshot = runBlocking { KotlinBambuSnapshot.snapshot(tmp, native) }
```

No other changes in this file — `native` is already declared on line 43, `runBlocking` is already imported (line 6).

- [ ] **Step 2: Update `KotlinBambuSnapshotTest`**

Open `KotlinBambuSnapshotTest.kt`. Add these imports:

```kotlin
import com.u1.slicer.NativeLibrary
import kotlinx.coroutines.runBlocking
```

Replace line 52 (the `snapshot` call) with:

```kotlin
        val native = NativeLibrary()
        val snapshot = runBlocking { KotlinBambuSnapshot.snapshot(fixture, native) }
```

Leave the existing assertions (source, isBbl, fileVersion, plates.size) intact — they cover the Kotlin-parsed portion of the snapshot, which sub-plan #1 does NOT change.

Then ADD a new positive assertion AFTER the existing body (inside the same `@Test` method, before its close brace):

```kotlin
        // Phase 1 sub-plan #1: volumes are populated via native accessors.
        assertTrue(
            "expected at least one volume for colored benchy, got 0",
            snapshot.volumes.isNotEmpty()
        )
        val firstVolume = snapshot.volumes.first()
        assertTrue("objectId must be > 0, got ${firstVolume.objectId}", firstVolume.objectId > 0)
        assertEquals(0, firstVolume.volumeIndex)
        // colored_3DBenchy (1).3mf has at least one painted volume.
        assertTrue(
            "expected at least one mm-painted volume in colored benchy",
            snapshot.volumes.any { it.isMmPainted }
        )
```

- [ ] **Step 3: Verify all test sources compile**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ./gradlew :app:compileDebugAndroidTestKotlin --no-daemon
```
Expected: PASS.

- [ ] **Step 4: Do NOT run tests yet.** They will fail on the differential path because the baseline still contains 21 `volumes.size` entries but those diffs should now be closed. Move to Task 10.

---

## Task 10: Remove the 21 `volumes.size` baseline entries

**Files:**
- Modify: `app/src/androidTest/assets/diagnostics/known-disagreements.json`

- [ ] **Step 1: Delete every `volumes.size` block**

Open `app/src/androidTest/assets/diagnostics/known-disagreements.json`. For EACH fixture entry, find the block like:

```json
      {
        "path": "volumes.size",
        "reason": "Kotlin gap - Phase 1 closes by deletion",
        "recordedAt": "2026-04-23"
      }
```

Remove it. If it's the last entry in the fixture's array, also remove the trailing `,` from the PREVIOUS block so the JSON stays valid. If it's the FIRST entry, remove the leading `,` of the NEXT block (less common — `volumes.size` typically appears last per fixture due to Phase 0 ordering, but verify each one).

Do this for all 21 fixtures that have it.

- [ ] **Step 2: Verify JSON is still well-formed and contains no `volumes.size`**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && grep -c '"path": "volumes.size"' app/src/androidTest/assets/diagnostics/known-disagreements.json
```
Expected: `0`.

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && node -e "JSON.parse(require('fs').readFileSync('app/src/androidTest/assets/diagnostics/known-disagreements.json', 'utf8'))" 2>&1 || python -c "import json; json.load(open('app/src/androidTest/assets/diagnostics/known-disagreements.json'))"
```
Expected: no output (silent success). If JSON parse errors, fix the offending fixture by hand — most likely a trailing comma.

- [ ] **Step 3: No commit yet.** The differential test run in Task 11 is the acceptance gate.

---

## Task 11: End-to-end verification — differential test runs GREEN

**Files:**
- No source changes. On-device test run only.

- [ ] **Step 1: Build and install both APKs**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && adb -s 43211JEKB16931 uninstall com.u1.slicer.orca; adb -s 43211JEKB16931 uninstall com.u1.slicer.orca.test; ./gradlew assembleDebug assembleDebugAndroidTest --no-daemon && adb -s 43211JEKB16931 install -r -d app/build/outputs/apk/debug/app-debug.apk && adb -s 43211JEKB16931 install -r -d app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

- [ ] **Step 2: Run the full differential test class**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && adb -s 43211JEKB16931 shell am instrument -w -r -e class com.u1.slicer.bambu.snapshot.BambuParserDifferentialTest com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```
Expected: `OK (21 tests)`. 

Failure modes to watch for:
- `fail(..."Unexpected diffs ... volumes[N].paintStateSet[K]": kotlin = X, native = Y)` — one of the JNI accessors is emitting different counts from `bambu_snapshot_json`. Investigate by dumping both paths on the failing fixture: run `NativeBambuSnapshot.snapshot` and our Task-8 volumes walk side-by-side, diff. Fix the C++ to match (very likely a guard missing or `kind` dispatch wrong) and REBUILD the .so.
- `fail(...: volumes.size kotlin = N native = M)` — the guard-order check `if (!native.loadModel(...)) return emptyList()` tripped, or Task 10 didn't remove one of the baseline entries. Re-inspect `known-disagreements.json`.
- `fail(..."Baseline has N stale entries"...)` — Task 10 missed a fixture, OR a new per-field diff appeared that wasn't in the baseline. If it's a stale `volumes.size`, remove it. If it's a new `volumes[N].*` diff, treat it as a real convergence gap and decide: (a) add to baseline with reason "Known Phase 1 sub-plan #1 open gap" — acceptable if it's a narrow corner case; or (b) fix the C++ / Kotlin to agree, preferred.

- [ ] **Step 3: Run the full instrumented Bambu suite as a regression gate**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && adb -s 43211JEKB16931 shell am instrument -w -r -e package com.u1.slicer.bambu com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```
Expected: all tests pass (includes KotlinBambuSnapshotTest, BambuParserDifferentialTest, BambuPipelineIntegrationTest). If a Bambu pipeline test regresses, the new `KotlinBambuSnapshot` signature or behaviour is leaking somewhere unexpected — investigate by name.

- [ ] **Step 4: Run JVM unit tests to catch anything broken off-device**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ./gradlew testDebugUnitTest --no-daemon
```
Expected: all 873 unit tests pass (count per CLAUDE.md in the worktree). If test count changes because of this sub-plan, update CLAUDE.md in Task 12.

- [ ] **Step 5: No commit yet.** Doc updates next.

---

## Task 12: Documentation + memory updates

**Files:**
- Modify: `CLAUDE.md` (worktree copy)
- Modify: `docs/superpowers/plans/2026-04-23-phase1-kickoff-handoff.md`
- Modify: `C:/Users/kevin/.claude/projects/c--Users-kevin-projects-u1-slicer-orca/memory/project-bambu-refactor.md`

- [ ] **Step 1: Bump `CLAUDE.md` instrumented test count**

If Task 3/4/5/6 added 4 new `@Test` methods to `NativeLibraryCorrectnessTest`, the instrumented-test total moves from 200 to 204. Open the worktree's `CLAUDE.md` and find the line:

```
./gradlew connectedDebugAndroidTest                # 200 instrumented tests — uses Orchestrator
```

Update to the new total. Also find the `NativeLibraryCorrectnessTest.kt` description block:

```
- `native/NativeLibraryCorrectnessTest.kt` (4) — JNI correctness checks
```

Bump the count (e.g. to `(8)`) and extend the description to mention the Phase 1 JNI accessors:

```
- `native/NativeLibraryCorrectnessTest.kt` (8) — JNI correctness checks + Phase 1 sub-plan #1 nativeGetObjectCount/nativeGetVolumeCount/nativeGetObjectModelId/nativeGetVolumeScalars/nativeGetPaintStateCounts
```

Only change these two lines. Leave the rest of the file alone.

- [ ] **Step 2: Update the kickoff handoff doc**

Open `docs/superpowers/plans/2026-04-23-phase1-kickoff-handoff.md`. At the END of the file, append:

```markdown

## Sub-plan #1 status: LANDED

Baseline entries closed: 21 `volumes.size` (one per fixture). Running total:
- Phase 0 baseline at HEAD of that phase: 664 entries (before cleanup #4).
- Post-cleanup-#4 baseline: <N> entries (recorded pre-sub-plan-#1 in `known-disagreements.json`).
- Post-sub-plan-#1 baseline: <N - 21> entries.

JNI accessors added: nativeGetObjectCount, nativeGetVolumeCount, nativeGetObjectModelId, nativeGetVolumeScalars, nativeGetPaintStateCounts. All five covered by NativeLibraryCorrectnessTest.

ThreeMfMeshParser NOT deleted — last production caller (ModelViewerScreen.kt:42) doesn't use paint data and keeps its Kotlin code path for mesh loading. Retirement is a later sub-plan bundled with #2.

Next: Sub-plan #5 (project config + filament colours) per roadmap priority.
```

Replace `<N>` with the current total entry count from `grep -c '"path":' app/src/androidTest/assets/diagnostics/known-disagreements.json` before and after Task 10.

- [ ] **Step 3: Update the memory entry**

Open `C:/Users/kevin/.claude/projects/c--Users-kevin-projects-u1-slicer-orca/memory/project-bambu-refactor.md`. Find the Phase 1 priority order block and update item #1 to mark it done:

```markdown
1. **Painted facets → preview mesh** — DONE (sub-plan #1 landed 2026-04-23). Closed 21 `volumes.size` baseline entries via five counts-only JNI accessors (`nativeGet{ObjectCount,VolumeCount,ObjectModelId,VolumeScalars,PaintStateCounts}`) + `KotlinBambuSnapshot.volumes` wiring. `ThreeMfMeshParser` NOT deleted (doesn't use paint data in its one remaining production caller); retirement bundled with later sub-plan.
```

Keep items 2-5 unchanged.

- [ ] **Step 4: No commit yet.** Final step is the coherent commit.

---

## Task 13: Commit

**Files:**
- Stage and commit everything touched by Tasks 2 through 12.

- [ ] **Step 1: Restore vendored extern line endings a final time**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && git restore -- app/src/main/cpp/extern/
```

- [ ] **Step 2: Review the diff**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && git status --short && echo "---" && git diff --stat
```
Expected files changed:
- `app/src/main/cpp/CMakeLists.txt` (+1 line)
- `app/src/main/cpp/src/sapil_bambu_snapshot.h` (+~15 lines)
- `app/src/main/cpp/src/sapil_bambu_snapshot.cpp` (function moved, ~0 net)
- `app/src/main/cpp/src/sapil_bambu_volumes.cpp` (new, ~110 lines)
- `app/src/main/java/com/u1/slicer/NativeLibrary.kt` (+~45 lines)
- `app/src/main/java/com/u1/slicer/bambu/snapshot/KotlinBambuSnapshot.kt` (+~50 lines, new helpers)
- `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so` (binary change)
- `app/src/androidTest/java/com/u1/slicer/bambu/snapshot/BambuParserDifferentialTest.kt` (+1 line)
- `app/src/androidTest/java/com/u1/slicer/bambu/snapshot/KotlinBambuSnapshotTest.kt` (+~10 lines)
- `app/src/androidTest/java/com/u1/slicer/native/NativeLibraryCorrectnessTest.kt` (+~100 lines of tests)
- `app/src/androidTest/assets/diagnostics/known-disagreements.json` (21 blocks removed)
- `CLAUDE.md` (2 lines)
- `docs/superpowers/plans/2026-04-23-phase1-kickoff-handoff.md` (+~10 lines)

If you see `app/src/main/cpp/extern/` in the modified list, go back to Step 1.

- [ ] **Step 3: Stage and commit**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && git add \
    app/src/main/cpp/CMakeLists.txt \
    app/src/main/cpp/src/sapil_bambu_snapshot.h \
    app/src/main/cpp/src/sapil_bambu_snapshot.cpp \
    app/src/main/cpp/src/sapil_bambu_volumes.cpp \
    app/src/main/java/com/u1/slicer/NativeLibrary.kt \
    app/src/main/java/com/u1/slicer/bambu/snapshot/KotlinBambuSnapshot.kt \
    app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so \
    app/src/androidTest/java/com/u1/slicer/bambu/snapshot/BambuParserDifferentialTest.kt \
    app/src/androidTest/java/com/u1/slicer/bambu/snapshot/KotlinBambuSnapshotTest.kt \
    app/src/androidTest/java/com/u1/slicer/native/NativeLibraryCorrectnessTest.kt \
    app/src/androidTest/assets/diagnostics/known-disagreements.json \
    CLAUDE.md \
    docs/superpowers/plans/2026-04-23-phase1-kickoff-handoff.md \
    docs/superpowers/plans/2026-04-23-phase1-painted-facets.md
```

Then commit with a HEREDOC message:

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && git commit -m "$(cat <<'EOF'
phase1(bambu-native): close volumes.size baseline via counts-only JNI accessors

Sub-plan #1 of the bambu-via-native-loader Phase 1 rollout. Adds five thin
JNI externs that read g_model directly — nativeGetObjectCount /
nativeGetVolumeCount / nativeGetObjectModelId / nativeGetVolumeScalars /
nativeGetPaintStateCounts — and wires KotlinBambuSnapshot.volumes through
them under previewMutex. Closes all 21 volumes.size entries in
known-disagreements.json.

count_paint_states is lifted out of sapil_bambu_snapshot.cpp's anonymous
namespace into the sapil namespace so the Phase 0 JSON emitter and the new
JNI accessor share a single source of truth for paint counts.

Doesn't touch ThreeMfMeshParser or ModelViewerScreen — sub-plan #1 is a
diff-harness closure job, not a production refactor; retirement of the
Kotlin paint parser is a later sub-plan.

Native .so rebuild: NDK 26 / Clang 17 / Release / ~20MB stripped.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 4: Verify the commit landed cleanly**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && git log -1 --stat | head -30
```
Expected: exactly the 14 files listed above. If `app/src/main/cpp/extern/*` sneaks in, the commit is dirty — STOP and ask the user how to proceed before amending.

- [ ] **Step 5: Sync memory**

Outside the worktree (regular tool call, not Bash), write the updated memory entry from Task 12 Step 3 to:
`C:/Users/kevin/.claude/projects/c--Users-kevin-projects-u1-slicer-orca/memory/project-bambu-refactor.md`

Use the Write tool. Only update the Phase 1 priority block — keep the rest of the file intact (re-read it first, then edit).

- [ ] **Step 6: Do NOT push.** The branch is long-lived and the user pushes manually. Report completion to the user with:
  - commit SHA
  - baseline-entry-count-before vs count-after
  - list of new JNI symbols

---

## Self-review

**1. Spec coverage**

- ✅ Option C counts-only accessor: Task 6 (`nativeGetPaintStateCounts`).
- ✅ `nativeGetVolumeCount` helper: Task 3.
- ✅ Close `volumes.size` (now 21, was 420 pre-cleanup-#4) baseline entries: Tasks 8-10.
- ✅ Populate `KotlinBambuSnapshot.volumes`: Task 8.
- ✅ Under `previewMutex`: Task 8 `NativeLibrary.previewMutex.withLock { ... }`.
- ✅ Mirror `sapil_bambu_snapshot.cpp` field-for-field: all seven VolumeSnapshot fields (`objectId`, `volumeIndex`, `extruder` tri-state, `paintStateSet`, `paintSupportsStateSet`, `isMmPainted`, `isSeamPainted`) are populated from accessors whose bodies are byte-equivalent to `append_volume`.
- ✅ Leave `ThreeMfMeshParser` untouched: explicitly out of scope (see File structure "Deliberately NOT touched").
- ✅ Shared `count_paint_states`: Task 2.
- ✅ Test coverage: Tasks 3-6 add new `NativeLibraryCorrectnessTest` cases; Task 9 adds a `KotlinBambuSnapshotTest` volumes assertion; Task 11 gates on differential test green.
- ✅ Native rebuild: Task 7 (single incremental rebuild).
- ✅ Memory + doc updates: Task 12.

**2. Placeholder scan**

No "TODO", "TBD", "similar to Task N", "add appropriate error handling", or vague instructions. Every code-bearing step has full code. Every command step has the exact command with `WT=...` prefix. Every expected output is explicit (count values, symbol names, file sizes).

One soft spot: Task 12 Step 2 uses `<N>` as placeholders for the pre/post baseline totals — but these are INTENTIONAL runtime values derived from a specific grep at commit time, not placeholder code. The step tells the agent exactly how to obtain them.

**3. Type consistency**

- `nativeGetObjectCount()` / `nativeGetVolumeCount(oi)` / `nativeGetObjectModelId(oi)` / `nativeGetVolumeScalars(oi, vi)` / `nativeGetPaintStateCounts(oi, vi, kind)` — all referenced with identical signatures across Tasks 3-6 (JNI decls), Task 8 (Kotlin consumer), Tasks 9 (test expectations).
- `VolumeSnapshot` field types match `BambuFileSnapshot.kt` definitions exactly: `objectId: Int` (not Long — we truncate in Task 8 Step 1), `extruder: Int?` (null for tri-state, decoded from -1 sentinel).
- `unpackStateCounts` helper referenced only in Task 8; name stays consistent.

**4. Risks / open questions preserved**

- ObjectID truncation (size_t → Int): called out in Task 8 Step 1 KDoc.
- Test isolation: each test in `NativeLibraryCorrectnessTest` calls `lib.clearModel()` between runs via `@After`, so there's no stale-g_model contamination between the Task-3-6 tests.
- Device install failures: Task 11 Step 1 uses the same `uninstall + install -r -d` flow documented for the Pixel 8a phantom-version issue.

---

Plan complete and saved to `docs/superpowers/plans/2026-04-23-phase1-painted-facets.md`.
