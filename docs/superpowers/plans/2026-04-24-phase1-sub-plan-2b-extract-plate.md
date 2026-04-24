# Phase 1 Sub-Plan #2b — BambuSanitizer.extractPlate → native migration

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Retire the Kotlin `BambuSanitizer.extractPlate` + `restructurePlateFile` disk-rewrite pair from the production slice-time critical path (`SlicerViewModel.selectPlate`), replacing them with a native `loadModelForPlate(path, plateIdx)` JNI entry that routes through `Model::read_from_file(..., plate_id=N+1)` so the BBS importer filters plate-N's objects at ingestion.

**Architecture:** OrcaSlicer's BBS 3MF importer already supports `plate_id > 0` filtering (`bbs_3mf.cpp:1921-1940`). The plan adds a thin parallel native entry point (not a signature change to `loadModel`, to avoid a cascade of call-site updates), widens `ProfileEmbedder.embed` to emit a plate-filtered `custom_gcode_per_layer.xml` so sub-plan #3's XML-fallback path stays correct, and rewires one `SlicerViewModel.selectPlate` code path. `BambuSanitizer.extractPlate` / `restructurePlateFile` stay linked in unchanged (test callers keep them alive; deletion is a follow-up sub-plan #2c, post-v1.7.0).

**Tech Stack:** Kotlin 1.9.22, Android minSdk 26 / compileSdk 34, Gradle, JUnit 4, AndroidJUnitRunner, Android Test Orchestrator, `org.json.JSONObject`, `java.util.zip.ZipFile` / `ZipOutputStream`, NDK 26 (Clang 17) Release `.so` build. Device: Pixel 8a `43211JEKB16931` with the known phantom `versionCode 257` workaround (uninstall/reinstall cycle, never `connectedDebugAndroidTest`).

---

## Scope Firewall (read before every task)

**DO NOT TOUCH** in this plan:
- `app/src/main/java/com/u1/slicer/gcode/LayerToolPauseInjector.kt` — sub-plan #3 dual-path is frozen. Its `getPlateData: ((Int) -> String?)?` function-reference contract stays.
- `app/src/main/java/com/u1/slicer/bambu/LayerToolCustomGcodeXml.kt` — `ThreeMfParser.kt:245/248/251/511` + `PreviewColorNormalizationTest` + `NativePreparePreviewTest:9` still call its three functions for the viewer Z-band recolor path.
- `app/src/main/java/com/u1/slicer/bambu/ThreeMfParser.kt` — every line, every function (we call `parseForPlateSelection` but do not modify its definition).
- `app/src/main/cpp/extern/` — always `git restore -- app/src/main/cpp/extern/` before every `git add`. Vendored GMP/MPFR docs get line-ending-dirtied by native tooling.
- `app/src/main/cpp/orcaslicer/` — OrcaSlicer upstream; do not touch.
- `app/src/androidTest/assets/diagnostics/known-disagreements.json` — diff-harness baseline must stay at 0 entries; this plan does not touch any snapshot field.
- `app/build.gradle` `versionCode` / `versionName` — **user ships v1.7.0 after this lands, not during.**
- `BambuSanitizer.extractPlate`, `BambuSanitizer.restructurePlateFile`, and their ~23 test callers across `BambuPipelineIntegrationTest`, `NativePreparePreviewTest`, `ProfileEmbedderIntegrationTest`, `B95Plate9PaintStateTest`. **The Kotlin plate-extractor code stays linked in.** Only the one production call site in `SlicerViewModel.selectPlate` is removed.
- Any other `NativeLibrary.loadModel(path)` caller. We add a parallel `loadModelForPlate(path, plateIdx)` entry point rather than changing `loadModel`'s signature — no cascade.

**WHY the Kotlin plate-extractor stays:** deletion would orphan 23 tests + the three private helpers the tests exercise (`filterModelToPlate`, `stripUnreferencedResources`, `stripAssembleSection`, `filterCustomGcodePerLayer`, `buildSyntheticModelConfig`). Pruning them is a mechanical cleanup worth its own sub-plan #2c, scoped separately. This plan's success criterion is "production no longer goes through them", not "they no longer exist".

---

## Pre-flight (one-time, before Task 1)

- [ ] **Pre-1: Confirm branch HEAD, worktree cleanliness.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && git rev-parse HEAD && git rev-parse --abbrev-ref HEAD && git status --short
```

Expected: HEAD is `9f128ef` (the design-notes commit immediately preceding this plan), branch is `refactor/bambu-via-native-loader`, `git status` shows only:
```
?? MORNING_STATUS.md
?? NEXT_AGENT_HANDOFF.md
?? docs/superpowers/plans/2026-04-24-phase1-sub-plan-2b-agent-prompt.md
```
(plus this plan file once written, before commit).

If anything else is dirty, STOP and escalate.

- [ ] **Pre-2: Confirm native `.so` present, NDK 26 / Clang 17, Release size.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ls -la app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so && C:/Users/kevin/AppData/Local/Android/Sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe -p .comment app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so | head -5
```

Expected: `.so` size is ~19-21 MB (stripped Release). `llvm-readelf -p .comment` shows `clang version 17.0.2`. If size is >50 MB or the compiler is Clang 14, STOP — this plan does not recover that state.

- [ ] **Pre-3: Confirm existing build dir is Release with NDK 26.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && grep -E "^(CMAKE_TOOLCHAIN_FILE|CMAKE_BUILD_TYPE|ANDROID_NDK):" app/.cxx/Debug/ndk26release/arm64-v8a/CMakeCache.txt
```

Expected:
```
ANDROID_NDK:UNINITIALIZED=C:/Users/kevin/AppData/Local/Android/Sdk/ndk/26.1.10909125
CMAKE_BUILD_TYPE:STRING=Release
CMAKE_TOOLCHAIN_FILE:FILEPATH=.../ndk/26.1.10909125/build/cmake/android.toolchain.cmake
```

If the toolchain cache points to NDK 25 / 23 or the build type is Debug, STOP — need a fresh build dir per `CLAUDE.md` "Native Rebuild → Fresh build" recipe (separate action, not in this plan).

- [ ] **Pre-4: Confirm diff-harness baseline at zero.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && cat app/src/androidTest/assets/diagnostics/known-disagreements.json
```

Expected: `[]` (empty JSON array). If non-empty, Phase 1 is not in the state this plan assumes — escalate.

- [ ] **Pre-5: Verify `BambuSanitizer.filterCustomGcodePerLayer` exists and is private.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && grep -n "fun filterCustomGcodePerLayer" app/src/main/java/com/u1/slicer/bambu/BambuSanitizer.kt
```

Expected: exactly one match, `BambuSanitizer.kt:1624` prefixed with `private fun`. If the signature or visibility differs, Task 2 step 1 needs updating.

- [ ] **Pre-6: Commit this plan document before starting Task 1.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && git restore -- app/src/main/cpp/extern/ && git add docs/superpowers/plans/2026-04-24-phase1-sub-plan-2b-extract-plate.md && git status --short && git commit -m "$(cat <<'EOF'
docs(phase1): executable plan for sub-plan #2b extract-plate migration

Adds the TDD-structured executable plan for the BambuSanitizer.extractPlate
production-path retirement, per the design notes at
docs/superpowers/plans/2026-04-24-phase1-sub-plan-2b-extract-plate-design-notes.md.

Plan strategy: Option A (thread plate_id through a new loadModelForPlate
JNI entry rather than changing loadModel's signature) + Risk 4 mitigation
m1 (ProfileEmbedder.embed filters custom_gcode_per_layer.xml by plate so
sub-plan #3 XML fallback stays correct on painted multi-plate fixtures).

Scope firewall matches design notes Section 7. Deletion of Kotlin
BambuSanitizer.extractPlate / restructurePlateFile and their 23 test
callers is deferred to a follow-up sub-plan #2c.
EOF
)"
```

Expected: one new commit, HEAD advances by one.

---

## File Structure

**Files modified:**

- `app/src/main/cpp/include/sapil.h` — `SlicerEngine::loadModel` overload declaration (+3 lines).
- `app/src/main/cpp/src/sapil_model.cpp` — new overload implementation forwarding to an internal helper; existing `loadModel(path)` becomes a thin wrapper calling `loadModel(path, 0)` (+~8 / -0 lines).
- `app/src/main/cpp/src/slicer_wrapper.cpp` — new JNI wrapper `Java_com_u1_slicer_NativeLibrary_loadModelForPlate` (+12 lines).
- `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so` — rebuilt (binary, ~20 MB).
- `app/src/main/java/com/u1/slicer/NativeLibrary.kt` — new `external fun loadModelForPlate(path: String, plateIdx: Int): Boolean` (+~5 lines incl. KDoc).
- `app/src/main/java/com/u1/slicer/bambu/BambuSanitizer.kt` — widen `filterCustomGcodePerLayer` from `private` to `internal` (-1 / +1 word).
- `app/src/main/java/com/u1/slicer/bambu/ProfileEmbedder.kt` — `embed(…, plateId: Int? = null)` parameter; when non-null, filter `custom_gcode_per_layer.xml` via reused `BambuSanitizer.filterCustomGcodePerLayer` instead of dropping it for painted files (+~20 lines).
- `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` — `selectPlate(plateId)` coroutine body rewritten to skip extractPlate + restructurePlateFile; calls `native.loadModelForPlate(embeddedFile, plateId - 1)` (~15 net changed lines); `embedProfile` gets a `plateId` arg threaded through (+3 lines).
- `app/src/androidTest/java/com/u1/slicer/native/NativeLibraryCorrectnessTest.kt` — add `loadModelForPlate_singlePlateSelectorFiltersObjectCount` instrumented test (+~40 lines).
- `app/src/test/java/com/u1/slicer/bambu/ProfileEmbedderTest.kt` — add JVM unit test `embed_withPlateId_filtersCustomGcodePerLayer` (+~35 lines). (No native or device dependency.)
- `CLAUDE.md` — bump unit test count by +1 (ProfileEmbedderTest), instrumented test count by +1 (NativeLibraryCorrectnessTest gains `loadModelForPlate_*`), and add a one-line Key Convention note about the new native entry point.
- `MORNING_STATUS.md` — append a "Follow-up landed (2026-04-24) — sub-plan #2b" section (local, untracked — do not commit).

**Files created:** none.

**Files NOT modified** (scope firewall): `LayerToolPauseInjector.kt`, `LayerToolCustomGcodeXml.kt`, `ThreeMfParser.kt`, `BambuSanitizer.extractPlate/restructurePlateFile`, `app/src/main/cpp/extern/`, `app/src/main/cpp/orcaslicer/`, `app/build.gradle`, `known-disagreements.json`.

---

## Task 1 — Add `loadModelForPlate` native entry + rebuild `.so`

**Files:**
- Create/modify: `app/src/main/cpp/include/sapil.h`, `app/src/main/cpp/src/sapil_model.cpp`, `app/src/main/cpp/src/slicer_wrapper.cpp`, `app/src/main/java/com/u1/slicer/NativeLibrary.kt`
- Binary: `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so` (rebuilt)
- Test: `app/src/androidTest/java/com/u1/slicer/native/NativeLibraryCorrectnessTest.kt`

**Rationale:** This task is the only one in the plan that requires a native rebuild. Per `CLAUDE.md`'s "Native Rebuild" authorisation and the operator brief's pre-authorisation, do not split the source change from the binary. TDD applies: write a failing test first, then implement C++ + JNI + Kotlin, then rebuild, then confirm green.

**Note:** This task makes `SlicerEngine::loadModel(const std::string&)` call a new internal helper that takes `plate_id`, so the existing JNI `Java_com_u1_slicer_NativeLibrary_loadModel` continues to work (always passes 0). That preserves all current call sites' behaviour while unlocking the plate-id path.

- [ ] **Step 1: Write the failing instrumented test.**

Open `app/src/androidTest/java/com/u1/slicer/native/NativeLibraryCorrectnessTest.kt`. Scan the imports to understand the test APK's context and fixture-loading pattern (`InstrumentationRegistry.getInstrumentation().context.assets`, copy to `targetContext.cacheDir`). Look at any existing test method, e.g. `nativeGetPlateCount_returnsZeroForUnloadedModel`, to see the existing style.

Append a new `@Test` method inside the `class NativeLibraryCorrectnessTest` body, before the closing brace. The method name must be **snake_case** (DEX rejects backticked names with spaces in androidTest).

```kotlin
    @Test
    fun loadModelForPlate_coloredBenchyPlate0_matchesFullLoadObjectCount() {
        // colored_3DBenchy.3mf is single-plate — loading with plateIdx=0 should
        // produce the same object/volume counts as the all-plates load. This is
        // the minimal smoke test for the new loadModelForPlate entry point;
        // multi-plate filtering correctness is covered by BambuPipelineIntegrationTest.
        val fixture = copyAssetToCache("fixtures/colored_3DBenchy.3mf", "colored_3DBenchy.3mf")

        val fullLoad = lib.loadModel(fixture.absolutePath)
        assertTrue("loadModel must succeed", fullLoad)
        val fullInfo = lib.getModelInfo()
        assertNotNull("getModelInfo after full load", fullInfo)
        val fullVolumes = fullInfo!!.volumeCount
        lib.clearModel()

        val plateLoad = lib.loadModelForPlate(fixture.absolutePath, 0)
        assertTrue("loadModelForPlate(plateIdx=0) must succeed on single-plate fixture", plateLoad)
        val plateInfo = lib.getModelInfo()
        assertNotNull("getModelInfo after plate-filtered load", plateInfo)
        assertEquals(
            "single-plate fixture must match full-load volume count under plateIdx=0",
            fullVolumes,
            plateInfo!!.volumeCount
        )
        lib.clearModel()
    }

    @Test
    fun loadModelForPlate_negativePlateIdx_loadsAllPlates() {
        // plateIdx=-1 is the Kotlin convention for "load all plates"; the JNI
        // layer must convert to BBS plate_id=0 (which means "all plates").
        val fixture = copyAssetToCache("fixtures/colored_3DBenchy.3mf", "colored_3DBenchy.3mf")

        val loaded = lib.loadModelForPlate(fixture.absolutePath, -1)
        assertTrue("loadModelForPlate(plateIdx=-1) must succeed — all-plates alias", loaded)
        val info = lib.getModelInfo()
        assertNotNull("getModelInfo after plateIdx=-1 load", info)
        assertTrue("volume count must be > 0 for colored_3DBenchy", info!!.volumeCount > 0)
        lib.clearModel()
    }
```

If `copyAssetToCache` is not a helper in this file, check `KotlinBambuSnapshotTest.kt:31-32` for the canonical pattern and add a local helper:

```kotlin
    private fun copyAssetToCache(assetPath: String, outName: String): java.io.File {
        val assetContext = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context
        val targetContext = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val outFile = java.io.File(targetContext.cacheDir, outName)
        if (outFile.exists()) outFile.delete()
        assetContext.assets.open(assetPath).use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
        return outFile
    }
```

(Only add the helper if not already present. Grep for `copyAssetToCache` in the file first.)

- [ ] **Step 2: Confirm the test fails to compile.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ./gradlew assembleDebugAndroidTest --no-daemon
```

Expected: BUILD FAILED with `unresolved reference: loadModelForPlate`. This is the RED step — the JNI entry + Kotlin external fun don't exist yet.

- [ ] **Step 3: Add the Kotlin `external fun` declaration.**

In `app/src/main/java/com/u1/slicer/NativeLibrary.kt`, find the existing `external fun loadModel(path: String): Boolean` at line 42. Immediately after it, insert:

```kotlin
    /**
     * Load a Bambu multi-plate 3MF but restrict `g_model.objects` to the target plate.
     *
     * Forwards to `Model::read_from_file(..., plate_id = plateIdx + 1)` when `plateIdx >= 0`
     * (the BBS importer convention is 1-based, 0 meaning "all plates"). `plateIdx = -1`
     * is the Kotlin-side alias for "load all plates" — forwards with `plate_id = 0`.
     *
     * Callers MUST hold [previewMutex] for the load + any subsequent accessor sequence
     * (same contract as [loadModel]). Phase 1 sub-plan #2b retires the Kotlin
     * `BambuSanitizer.extractPlate` disk-rewrite pass in favour of this entry point.
     */
    external fun loadModelForPlate(path: String, plateIdx: Int): Boolean
```

- [ ] **Step 4: Add the JNI wrapper.**

In `app/src/main/cpp/src/slicer_wrapper.cpp`, find the existing `Java_com_u1_slicer_NativeLibrary_loadModel` at line 48-56. Immediately after that function's closing brace, insert:

```cpp
JNIEXPORT jboolean JNICALL
Java_com_u1_slicer_NativeLibrary_loadModelForPlate(
        JNIEnv* env, jobject, jstring jpath, jint jplate_idx) {
    if (!g_engine) return JNI_FALSE;

    // Kotlin convention: plateIdx = -1 means "load all plates" (BBS plate_id=0).
    // Kotlin plateIdx >= 0 means "load only plate N" (BBS plate_id = N+1, 1-based).
    const int bbs_plate_id = (jplate_idx < 0) ? 0 : static_cast<int>(jplate_idx) + 1;

    const char* path = env->GetStringUTFChars(jpath, nullptr);
    bool result = g_engine->loadModel(std::string(path), bbs_plate_id);
    env->ReleaseStringUTFChars(jpath, path);
    return result ? JNI_TRUE : JNI_FALSE;
}
```

- [ ] **Step 5: Declare the C++ overload in `sapil.h`.**

Find `app/src/main/cpp/include/sapil.h` and locate the `SlicerEngine` class declaration. Find the existing line:

```cpp
    bool loadModel(const std::string& filepath);
```

Immediately after it, insert:

```cpp
    /**
     * Load a 3MF, optionally filtered to one BBS plate.
     *
     * @param plate_id 0 = load all plates (default, same as loadModel(path));
     *                 >0 = 1-based plate_id passed to Model::read_from_file, which
     *                 causes the BBS importer to only instantiate objects in
     *                 m_plater_data[plate_id]. Used by Phase 1 sub-plan #2b to
     *                 retire the Kotlin BambuSanitizer.extractPlate disk rewrite.
     */
    bool loadModel(const std::string& filepath, int plate_id);
```

- [ ] **Step 6: Implement the C++ overload in `sapil_model.cpp`.**

In `app/src/main/cpp/src/sapil_model.cpp`, locate the existing `bool SlicerEngine::loadModel(const std::string& filepath)` at line 117. The cleanest refactor is to:
1. Rename the existing body to `loadModelImpl(path, plate_id)`.
2. Make the two public overloads thin wrappers.

To minimise churn, prefer an in-place modification: change the existing method to accept `plate_id`, and add a single-arg wrapper at the end of the function that calls itself with `plate_id = 0`. Concretely:

Find:

```cpp
bool SlicerEngine::loadModel(const std::string& filepath) {
    SAPIL_LOGI("Loading model: %s", filepath.c_str());
```

Change to:

```cpp
bool SlicerEngine::loadModel(const std::string& filepath) {
    return loadModel(filepath, 0);
}

bool SlicerEngine::loadModel(const std::string& filepath, int plate_id) {
    SAPIL_LOGI("Loading model: %s (plate_id=%d)", filepath.c_str(), plate_id);
```

Then find the single `Model::read_from_file` call inside this function (around line 153-155):

```cpp
        g_model = Slic3r::Model::read_from_file(filepath, &config, &config_substitutions,
            Slic3r::LoadStrategy::LoadModel | Slic3r::LoadStrategy::LoadConfig | Slic3r::LoadStrategy::AddDefaultInstances,
            &g_plate_data_list, &project_presets, &g_is_bbl, &g_file_version);
```

Change to:

```cpp
        // BBS importer filters objects by plate_id > 0 (bbs_3mf.cpp:1921-1940).
        // Phase 1 sub-plan #2b threads plate_id from Kotlin so we don't pre-extract
        // a single-plate 3MF on disk. plate_id=0 remains the "load all plates" default
        // used by the STL/OBJ/STEP paths and by the existing loadModel(path) overload.
        g_model = Slic3r::Model::read_from_file(
            filepath, &config, &config_substitutions,
            Slic3r::LoadStrategy::LoadModel | Slic3r::LoadStrategy::LoadConfig | Slic3r::LoadStrategy::AddDefaultInstances,
            &g_plate_data_list, &project_presets, &g_is_bbl, &g_file_version,
            /*proFn=*/nullptr, /*stlFn=*/nullptr, /*project=*/nullptr, plate_id);
```

**Verify the `Model::read_from_file` signature** before committing. From `Model.hpp:1582-1587`:
```
static Model read_from_file(const std::string& input_file, DynamicPrintConfig* config = nullptr, ConfigSubstitutionContext* config_substitutions = nullptr,
    LoadStrategy options = LoadStrategy::AddDefaultInstances, PlateDataPtrs* plate_data = nullptr,
    std::vector<Preset*>* project_presets = nullptr, bool *is_xxx = nullptr, Semver* file_version = nullptr, Import3mfProgressFn proFn = nullptr,
    ImportstlProgressFn stlFn = nullptr, BBLProject *project = nullptr, int plate_id = 0, ObjImportColorFn objFn = nullptr);
```

So the argument order is: `input_file, config, config_substitutions, options, plate_data, project_presets, is_xxx, file_version, proFn, stlFn, project, plate_id, objFn`. The existing call at `sapil_model.cpp:153-155` passes up through `file_version`, so the new call must add `nullptr, nullptr, nullptr, plate_id` (proFn, stlFn, project, plate_id) to the argument list in that order. Sanity-check the diff before rebuilding. **If the signature check above differs from the Model.hpp declaration at build time, STOP** — the OrcaSlicer vendor version may have drifted.

- [ ] **Step 7: Rebuild the native `.so`.**

Per `CLAUDE.md` "Native Rebuild" section, use the existing build directory (Pre-3 confirmed it is NDK 26 / Release):

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && cd app/.cxx/Debug/ndk26release/arm64-v8a && C:/Users/kevin/AppData/Local/Android/Sdk/cmake/3.22.1/bin/ninja.exe -j1
```

Expected: Ninja runs for 2-15 minutes (incremental — only `sapil_model.cpp` + `slicer_wrapper.cpp` touched). Final output: a freshly-timestamped `libprusaslicer-jni.so` in the build dir.

**If ninja errors on the `Model::read_from_file` call** because of argument-count mismatch, revisit Step 6's argument order against the actual `Model.hpp` in this checkout (grep `read_from_file` in `orcaslicer/src/libslic3r/Model.hpp`). **Hard abort criterion D (of this plan) fires if ninja fails on two attempts.**

- [ ] **Step 8: Strip and copy the `.so` into jniLibs.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && C:/Users/kevin/AppData/Local/Android/Sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe --strip-unneeded app/.cxx/Debug/ndk26release/arm64-v8a/libprusaslicer-jni.so && cp app/.cxx/Debug/ndk26release/arm64-v8a/libprusaslicer-jni.so app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so
```

Expected: `llvm-strip` returns silently; `cp` replaces the shipped `.so`.

- [ ] **Step 9: Verify size, compiler, new symbol presence.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ls -la app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so && C:/Users/kevin/AppData/Local/Android/Sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe -p .comment app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so | head -5 && C:/Users/kevin/AppData/Local/Android/Sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe --dyn-syms app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so | grep "loadModelForPlate\|loadModel\b"
```

Expected:
- Size: **19-21 MB** (not 50 MB+, not 80 MB+).
- Compiler: `clang version 17.0.2`.
- Symbols: both `Java_com_u1_slicer_NativeLibrary_loadModel` (pre-existing) and `Java_com_u1_slicer_NativeLibrary_loadModelForPlate` (new) appear in the dynamic symbol table.

**If any check fails, STOP — do not proceed to install/test with a bad `.so`.** Rerun strip or rebuild as appropriate.

- [ ] **Step 10: Install both APKs on Pixel 8a and run the two new tests.**

Per the gotcha for phantom `versionCode 257`, use the uninstall/reinstall cycle, not `connectedDebugAndroidTest`:

```bash
adb -s 43211JEKB16931 uninstall com.u1.slicer.orca; adb -s 43211JEKB16931 uninstall com.u1.slicer.orca.test
```

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ./gradlew assembleDebug assembleDebugAndroidTest --no-daemon
```

Expected: BUILD SUCCESSFUL.

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && adb -s 43211JEKB16931 install -r -d app/build/outputs/apk/debug/app-debug.apk && adb -s 43211JEKB16931 install -r -d app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Expected: both return `Success`.

```bash
adb -s 43211JEKB16931 shell am instrument -w -r -e class com.u1.slicer.native.NativeLibraryCorrectnessTest#loadModelForPlate_coloredBenchyPlate0_matchesFullLoadObjectCount -e class com.u1.slicer.native.NativeLibraryCorrectnessTest#loadModelForPlate_negativePlateIdx_loadsAllPlates com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: `OK (2 tests)`.

- [ ] **Step 11: Run the full `NativeLibraryCorrectnessTest` class for regression.**

```bash
adb -s 43211JEKB16931 shell am instrument -w -r -e class com.u1.slicer.native.NativeLibraryCorrectnessTest com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: `OK (14 tests)` — 12 pre-existing + 2 new.

- [ ] **Step 12: Commit source + binary together.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && git restore -- app/src/main/cpp/extern/ && git add app/src/main/cpp/include/sapil.h app/src/main/cpp/src/sapil_model.cpp app/src/main/cpp/src/slicer_wrapper.cpp app/src/main/java/com/u1/slicer/NativeLibrary.kt app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so app/src/androidTest/java/com/u1/slicer/native/NativeLibraryCorrectnessTest.kt && git status --short && git commit -m "$(cat <<'EOF'
phase1(bambu-native): add loadModelForPlate JNI entry + rebuild .so (sub-plan #2b task 1)

Threads plate_id through a new SlicerEngine::loadModel(path, plate_id)
overload and a parallel NativeLibrary.loadModelForPlate(path, plateIdx)
JNI entry. plateIdx=-1 is the Kotlin alias for "load all plates" (BBS
plate_id=0); plateIdx>=0 forwards as BBS plate_id=plateIdx+1.

Existing NativeLibrary.loadModel(path) keeps its signature, delegating
to the new overload with plate_id=0. All non-selectPlate callers keep
binary-compatible behaviour.

.so rebuild: NDK 26 / Clang 17.0.2 / Release / stripped / 20 MB.
New symbol Java_com_u1_slicer_NativeLibrary_loadModelForPlate verified
present in dynamic symbol table.

Tests: 2 new in NativeLibraryCorrectnessTest
(loadModelForPlate_coloredBenchyPlate0_matchesFullLoadObjectCount,
loadModelForPlate_negativePlateIdx_loadsAllPlates) smoke the new entry
point against colored_3DBenchy. Multi-plate filtering correctness
regression gates land in task 4 via BambuPipelineIntegrationTest.
EOF
)"
```

Expected: one new commit combining C++ source, JNI, Kotlin external, test, and binary `.so`. Confirm with `git log --oneline HEAD~2..HEAD`.

---

## Task 2 — Widen `BambuSanitizer.filterCustomGcodePerLayer` visibility

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/bambu/BambuSanitizer.kt`

**Rationale:** Task 3 will call `filterCustomGcodePerLayer` from `ProfileEmbedder`. One-word visibility widening; no behaviour change.

- [ ] **Step 1: Change the modifier from `private` to `internal`.**

In `app/src/main/java/com/u1/slicer/bambu/BambuSanitizer.kt`, locate line 1624:

```kotlin
    private fun filterCustomGcodePerLayer(xml: String, targetPlateId: Int): String {
```

Change to:

```kotlin
    internal fun filterCustomGcodePerLayer(xml: String, targetPlateId: Int): String {
```

- [ ] **Step 2: Run existing BambuSanitizerTest class to confirm no breakage.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ./gradlew testDebugUnitTest --no-daemon --tests "com.u1.slicer.bambu.BambuSanitizerTest"
```

Expected: BUILD SUCCESSFUL, all 25 tests pass. (This is a pure visibility widening — no semantic change.)

- [ ] **Step 3: Commit.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && git restore -- app/src/main/cpp/extern/ && git add app/src/main/java/com/u1/slicer/bambu/BambuSanitizer.kt && git status --short && git commit -m "$(cat <<'EOF'
phase1(bambu-native): widen filterCustomGcodePerLayer to internal (sub-plan #2b task 2)

One-word visibility change from private to internal so ProfileEmbedder
can reuse it for plate-filtering custom_gcode_per_layer.xml in the
embedded file. No behaviour change; BambuSanitizerTest green (25 tests).
EOF
)"
```

---

## Task 3 — `ProfileEmbedder.embed` gains `plateId` + filters custom_gcode_per_layer.xml

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/bambu/ProfileEmbedder.kt`
- Test: `app/src/test/java/com/u1/slicer/bambu/ProfileEmbedderTest.kt` (JVM unit test — no native, no device)

**Rationale:** Design notes Section 4b Risk 4 — the biggest correctness trap. Sub-plan #3's XML fallback reads `custom_gcode_per_layer.xml` from the source/selected-plate file at injection time. Post-sub-plan-#2b, the "selected plate file" is the embedded multi-plate source. If that embedded file retains *all plates'* layer-tool entries, the injector would inject pauses for non-target plates.

Current behaviour (`ProfileEmbedder.kt:573-575`) is to **drop** `custom_gcode_per_layer.xml` entirely when the file has layer-tool changes and is destined for the native slice. The drop was safe pre-#2b because extractPlate had already filtered the XML to plate N before embedding. Post-#2b, "drop" becomes "lose all pauses" when sub-plan #3's native path is empty — a silent regression on painted plates.

**Fix:** add a `plateId: Int? = null` parameter to `embed`. When non-null, replace the "drop" branch with "filter to plate N via `BambuSanitizer.filterCustomGcodePerLayer(…, plateId)`, then emit". When null, keep the legacy drop. No caller of `embed` currently passes `plateId`, so the default preserves pre-migration behaviour; Task 4 will pass `plateId` from `SlicerViewModel.selectPlate`.

**TDD:** write a JVM unit test for the new behaviour first.

- [ ] **Step 1: Write the failing JVM unit test.**

Open `app/src/test/java/com/u1/slicer/bambu/ProfileEmbedderTest.kt`. Scan the existing `@Test` methods to understand the file's conventions (org.json, File.createTempFile patterns, etc.). If the test file doesn't exist or the class is absent, create it following the shape of `BambuSanitizerTest.kt`.

Append these two new `@Test` methods. Backticked names with spaces are fine here (JVM unit tests — not DEX).

```kotlin
    @Test
    fun `embed with plateId null drops custom_gcode_per_layer_xml when hasLayerToolChanges`() {
        // Legacy behaviour: when plateId is not supplied, the embedder drops
        // custom_gcode_per_layer.xml entirely for painted files (pre-sub-plan-#2b
        // contract).
        val source = buildSource3mfWith(
            customGcodeXml = """<?xml version="1.0"?><custom_gcodes_per_layer><plate><plate_info id="1"/></plate></custom_gcodes_per_layer>"""
        )
        val info = ThreeMfInfo(
            isBambu = true,
            hasLayerToolChanges = true,
            // other fields default...
        )
        val out = java.io.File.createTempFile("embed-legacy-", ".3mf")
        ProfileEmbedder.embed(source, emptyMap(), out.parentFile, info)

        val hasXml = java.util.zip.ZipFile(out).use { zip ->
            zip.getEntry("Metadata/custom_gcode_per_layer.xml") != null
        }
        assertFalse("plateId=null legacy path drops the XML", hasXml)
    }

    @Test
    fun `embed with plateId 1 filters custom_gcode_per_layer_xml to that plate only`() {
        // Sub-plan #2b behaviour: when plateId is supplied, the embedder
        // filters custom_gcode_per_layer.xml to the target plate (reusing
        // BambuSanitizer.filterCustomGcodePerLayer) and keeps it in the
        // embedded output so sub-plan #3's XML fallback can find the
        // plate-scoped layer-tool rows post-slice.
        val customGcodeXml = """<?xml version="1.0"?>
<custom_gcodes_per_layer>
  <plate>
    <plate_info id="1"/>
    <layer top_z="1.6" type="2" extruder="2" color="#AA0000" extra="" gcode="tool_change"/>
  </plate>
  <plate>
    <plate_info id="2"/>
    <layer top_z="3.2" type="2" extruder="3" color="#00AA00" extra="" gcode="tool_change"/>
  </plate>
</custom_gcodes_per_layer>""".trimIndent()
        val source = buildSource3mfWith(customGcodeXml = customGcodeXml)
        val info = ThreeMfInfo(
            isBambu = true,
            hasLayerToolChanges = true,
        )
        val out = java.io.File.createTempFile("embed-plate-filter-", ".3mf")
        ProfileEmbedder.embed(source, emptyMap(), out.parentFile, info, plateId = 1)

        val body = java.util.zip.ZipFile(out).use { zip ->
            val entry = zip.getEntry("Metadata/custom_gcode_per_layer.xml")
            assertNotNull("plateId=1 must keep the XML in the embedded file", entry)
            zip.getInputStream(entry).bufferedReader().readText()
        }
        assertTrue("embedded XML must contain plate 1's layer", body.contains("""top_z="1.6""""))
        assertFalse("embedded XML must NOT contain plate 2's layer", body.contains("""top_z="3.2""""))
        // filterCustomGcodePerLayer renumbers the selected plate_info id to 1; verify the
        // output keeps exactly one <plate> block.
        val plateCount = Regex("""<plate>""").findAll(body).count()
        assertEquals("filtered output has exactly one <plate> block", 1, plateCount)
    }

    private fun buildSource3mfWith(customGcodeXml: String): java.io.File {
        // Minimal Bambu-like 3MF: empty 3D/3dmodel.model + Metadata/project_settings.config
        // + Metadata/custom_gcode_per_layer.xml. Shape matters, not content validity.
        val file = java.io.File.createTempFile("src-", ".3mf")
        java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
            val minimalModel = """<?xml version="1.0"?><model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02"><resources><object id="1" type="model"><mesh><vertices/><triangles/></mesh></object></resources><build><item objectid="1"/></build></model>"""
            zip.putNextEntry(java.util.zip.ZipEntry("3D/3dmodel.model"))
            zip.write(minimalModel.toByteArray())
            zip.closeEntry()

            zip.putNextEntry(java.util.zip.ZipEntry("Metadata/project_settings.config"))
            zip.write("""{"filament_colour":["#FFFFFF"]}""".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(java.util.zip.ZipEntry("Metadata/custom_gcode_per_layer.xml"))
            zip.write(customGcodeXml.toByteArray())
            zip.closeEntry()
        }
        return file
    }
```

**If `ThreeMfInfo` requires more fields to construct non-trivially, check its data class declaration** (`app/src/main/java/com/u1/slicer/bambu/ThreeMfInfo.kt`). Most fields default sensibly (empty lists, false booleans); only set what the test needs. If new fields have been added that don't default, the test needs those too.

Also confirm the imports at the top of `ProfileEmbedderTest.kt` include:
```kotlin
import com.u1.slicer.bambu.ProfileEmbedder
import com.u1.slicer.bambu.ThreeMfInfo
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
```

- [ ] **Step 2: Run the two new tests to confirm they fail.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ./gradlew testDebugUnitTest --no-daemon --tests "com.u1.slicer.bambu.ProfileEmbedderTest"
```

Expected: BUILD FAILED. The first test (plateId=null) may already pass on the current codebase (existing drop behaviour). The second test (plateId=1) fails because:
- Either unresolved reference to `plateId` parameter (the signature doesn't accept it yet) → RED.
- Or, if the compile succeeds, the runtime assertion fails because the XML isn't retained → RED.

- [ ] **Step 3: Implement the signature change and branch.**

In `app/src/main/java/com/u1/slicer/bambu/ProfileEmbedder.kt`, find the `fun embed(` signature at line 486:

```kotlin
    fun embed(
        inputFile: File,
        config: Map<String, Any>,
        outputDir: File,
        info: ThreeMfInfo,
        extruderRemap: Map<Int, Int>? = null
    ): File {
```

Change to:

```kotlin
    fun embed(
        inputFile: File,
        config: Map<String, Any>,
        outputDir: File,
        info: ThreeMfInfo,
        extruderRemap: Map<Int, Int>? = null,
        plateId: Int? = null
    ): File {
```

Then find the `name == "Metadata/custom_gcode_per_layer.xml"` branch (around line 573-575):

```kotlin
                        name == "Metadata/custom_gcode_per_layer.xml" && info.hasLayerToolChanges -> {
                            Log.i(TAG, "Skipping custom_gcode_per_layer.xml in embedded file; native slice uses pause-injection fallback")
                        }
```

Change to:

```kotlin
                        name == "Metadata/custom_gcode_per_layer.xml" && info.hasLayerToolChanges -> {
                            // Sub-plan #2b: when a plateId is supplied (from SlicerViewModel.selectPlate),
                            // retain the XML but filter it to the target plate so sub-plan #3's XML
                            // fallback in LayerToolPauseInjector has plate-scoped entries at injection
                            // time. Legacy callers that pass plateId=null keep the previous "drop"
                            // behaviour (native slice still runs clean; fallback simply finds nothing).
                            //
                            // filterCustomGcodePerLayer takes a 1-based plate_info id — same as Bambu's
                            // XML convention. SlicerViewModel passes plateId from UI (1-based already).
                            if (plateId != null) {
                                val content = srcZip.getInputStream(entry).readBytes()
                                val filtered = BambuSanitizer.filterCustomGcodePerLayer(String(content), plateId)
                                if (filtered.isNotBlank()) {
                                    writeStored(destZip, name, filtered.toByteArray())
                                    Log.i(TAG, "Filtered custom_gcode_per_layer.xml to plate $plateId")
                                } else {
                                    Log.w(TAG, "filterCustomGcodePerLayer returned blank for plate $plateId; dropping")
                                }
                            } else {
                                Log.i(TAG, "Skipping custom_gcode_per_layer.xml in embedded file; native slice uses pause-injection fallback")
                            }
                        }
```

- [ ] **Step 4: Run the tests again to confirm they pass.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ./gradlew testDebugUnitTest --no-daemon --tests "com.u1.slicer.bambu.ProfileEmbedderTest"
```

Expected: BUILD SUCCESSFUL, all existing tests plus 2 new green.

- [ ] **Step 5: Run the full JVM test suite to confirm no regression.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ./gradlew testDebugUnitTest --no-daemon
```

Expected: BUILD SUCCESSFUL. **Hard abort D** fires if anything outside the ProfileEmbedder / bambu package regresses.

- [ ] **Step 6: Commit.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && git restore -- app/src/main/cpp/extern/ && git add app/src/main/java/com/u1/slicer/bambu/ProfileEmbedder.kt app/src/test/java/com/u1/slicer/bambu/ProfileEmbedderTest.kt && git status --short && git commit -m "$(cat <<'EOF'
phase1(bambu-native): ProfileEmbedder.embed gains plateId + filters custom_gcode_per_layer.xml (sub-plan #2b task 3)

Adds plateId: Int? = null parameter to embed(). When non-null, filters
custom_gcode_per_layer.xml to the target plate via
BambuSanitizer.filterCustomGcodePerLayer (one-plate block, plate_info id
renumbered to 1) and keeps it in the embedded output. When null, keeps
the legacy drop behaviour so no current caller changes semantics.

Why: sub-plan #2b retires extractPlate from SlicerViewModel.selectPlate;
without this mitigation, the embedded file would hold all-plates XML,
causing sub-plan #3's XML fallback in LayerToolPauseInjector to inject
pauses for non-target plates on painted multi-plate fixtures. Design
notes Risk 4 mitigation m1.

Tests: 2 new JVM unit tests in ProfileEmbedderTest:
- embed with plateId null drops custom_gcode_per_layer_xml when hasLayerToolChanges (legacy path guard)
- embed with plateId 1 filters custom_gcode_per_layer_xml to that plate only (m1 path)
EOF
)"
```

---

## Task 4 — `SlicerViewModel.selectPlate` uses native plate_id filter

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`

**Rationale:** The single production-code change that retires `BambuSanitizer.extractPlate` + `restructurePlateFile` from the slice-time path. Everything before this task has been additive (new JNI entry, new `embed` parameter, visibility widening). This task flips the switch.

**Task 4 has no RED test of its own** — the regression gates are the pre-existing `BambuPipelineIntegrationTest`, `ProfileEmbedderIntegrationTest`, `SemmSlicingTest`, `GoatDedupeSemmTest`, `SensoryTwistSupportsTest`, `NativePreparePreviewTest`, `PreparePreviewViewModelTest`. They must stay green post-migration. A regression here is what trips Hard Abort Criterion A.

- [ ] **Step 1: Study the call site at `SlicerViewModel.kt:1091-1145`.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && sed -n '1091,1145p' app/src/main/java/com/u1/slicer/SlicerViewModel.kt
```

Confirm the current flow:
1. `selectPlateJob = viewModelScope.launch(Dispatchers.IO) { …`
2. Resolve `file` (multi-plate source).
3. `rawPlateFile = BambuSanitizer.extractPlate(file, plateId, workspaceDir, hasPlateJsons, plateObjectIds, objectExtruderMap)`
4. `plateFile = BambuSanitizer.restructurePlateFile(rawPlateFile, workspaceDir)`
5. `plateInfo = ThreeMfParser.parseForPlateSelection(plateFile)`
6. `sourceModelFile = plateFile; sourceModelInfo = plateInfo`
7. `mergedPlateInfo = mergeThreeMfInfoForPlate(plateInfo, preSelectInfo, plateId)`
8. `_threeMfInfo.value = mergedPlateInfo; resetToolRemapState()`
9. `embeddedPlateFile = embedProfile(plateFile, mergedPlateInfo, workspaceDir)`
10. `currentModelFile = embeddedPlateFile; loadNativeModel(embeddedPlateFile)`

Post-migration we want:
1. Same.
2. Same.
3. **REMOVED** — extractPlate gone.
4. **REMOVED** — restructurePlateFile gone.
5. `plateInfo = ThreeMfParser.parseForPlateSelection(file)` — parse the **source** file, not a plate-extracted one. But `parseForPlateSelection` returns *all* plates' data. We take the target plate's slice via the existing `mergeThreeMfInfoForPlate` logic. **Actually verify:** `parseForPlateSelection(file)` walks all plates and populates `info.plates`. `mergeThreeMfInfoForPlate(plateInfo, preSelectInfo, plateId)` at `SlicerViewModel.kt:3379+` assumes `plateInfo.plates` has the one plate's data. **Re-read the merge logic before changing — there may be coupling we're breaking.**
6. `sourceModelFile = file` — source, not plate-extracted.
7. Same, but the argument shape may need adjusting (see step 5 verification).
8. Same.
9. `embeddedPlateFile = embedProfile(file, mergedPlateInfo, workspaceDir, plateId)` — pass `plateId` through to `ProfileEmbedder.embed`.
10. `loadNativeModel(embeddedPlateFile, plateId - 1)` — new nullable-plateIdx overload. **OR** a cleaner variant: keep `loadNativeModel(file)` and add a second overload `loadNativeModel(file, plateIdx: Int)` used only by selectPlate.

- [ ] **Step 2: Re-read `mergeThreeMfInfoForPlate` to confirm it tolerates multi-plate input.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && sed -n '3379,3450p' app/src/main/java/com/u1/slicer/SlicerViewModel.kt
```

Read the function body. Confirm it looks up plate N's info from `plateInfo.plates.find { it.plateId == targetPlateId }` or equivalent. If it instead assumes `plateInfo.plates.size == 1`, the migration must either change that (out of scope — fails the firewall) or keep using a plate-extracted `plateInfo`.

**Fallback if `mergeThreeMfInfoForPlate` is incompatible:** Option A-prime — call `ThreeMfParser.parseForPlateSelection(file)` and extract the target plate's data manually before calling the merge. This is a local, private transformation within `selectPlate`; acceptable within the scope firewall (we don't modify `mergeThreeMfInfoForPlate` itself).

- [ ] **Step 3: Extend `loadNativeModel` to accept a plate index.**

Look at `SlicerViewModel.kt:1178-1212` (existing `private suspend fun loadNativeModel(file: File)`). It calls `native.loadModel(file.absolutePath)` at line 1189, inside the `previewMutex.withLock { … }` block.

Add a second parameter with default `-1`:

```kotlin
    private suspend fun loadNativeModel(file: File, plateIdx: Int = -1) {
        val firstModelLoadThisLaunch = diagnostics.markFirstModelLoad()
        // Stale cached mesh from a previous model/plate load would cause …
        invalidatePrepareMeshCache()
        // Acquire previewMutex before touching native model — prevents SIGSEGV …
        val success = NativeLibrary.previewMutex.withLock {
            if (plateIdx >= 0) {
                native.loadModelForPlate(file.absolutePath, plateIdx)
            } else {
                native.loadModel(file.absolutePath)
            }
        }
```

(Rest of the function unchanged.)

- [ ] **Step 4: Extend `embedProfile` to accept and pass through `plateId`.**

At `SlicerViewModel.kt:1706`, find:

```kotlin
    private fun embedProfile(file: java.io.File, info: ThreeMfInfo, outputDir: java.io.File): java.io.File {
```

Change to:

```kotlin
    private fun embedProfile(file: java.io.File, info: ThreeMfInfo, outputDir: java.io.File, plateId: Int? = null): java.io.File {
```

At the end of the function where `profileEmbedder.embed(...)` is called (around line 1753), add the new `plateId` argument:

```kotlin
        return profileEmbedder.embed(file, embeddedConfig, outputDir, info, extruderRemap, plateId = plateId)
```

Other callers of `embedProfile` (grep `embedProfile(` in `SlicerViewModel.kt`) — check if any exist. If any other callers exist, they can default plateId to null without changing behaviour. Verify with:

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && grep -n "embedProfile(" app/src/main/java/com/u1/slicer/SlicerViewModel.kt
```

- [ ] **Step 5: Rewrite the selectPlate coroutine body.**

Replace the body of `selectPlateJob = viewModelScope.launch(Dispatchers.IO) { try { … } catch … }` at `SlicerViewModel.kt:1091-1145`.

Current:

```kotlin
        selectPlateJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val workspaceDir = transientWorkspaceDir()
                // Use the stable file-level info (set once on load, never mutated by plate
                // selections) so that switching plates doesn't lose the original plates list.
                // _threeMfInfo.value is overwritten to a per-plate merged result after each
                // selectPlate(), so it may no longer have the correct objectIds for other plates.
                val fileInfo = _fileThreeMfInfo ?: _threeMfInfo.value
                val hasPlateJsons = fileInfo?.hasPlateJsons
                val plateObjectIds = fileInfo?.plates
                    ?.find { it.plateId == plateId }?.objectIds?.toSet()
                val plateExtruderMap = fileInfo?.objectExtruderMap
                    ?.filterKeys { key -> plateObjectIds?.contains(key) == true }
                val rawPlateFile = BambuSanitizer.extractPlate(file, plateId, workspaceDir,
                    hasPlateJsons = hasPlateJsons,
                    plateObjectIds = plateObjectIds,
                    objectExtruderMap = plateExtruderMap)
                ensureActive()
                // Restructure per-plate: inline component meshes so OrcaSlicer
                // can assign per-volume extruders (deferred from process()).
                val plateFile = BambuSanitizer.restructurePlateFile(rawPlateFile, workspaceDir)
                ensureActive()
                // Lightweight parse: only reads model_settings.config (~1KB) for extruder
                // indices, skips the 15MB+ main model XML entirely (~2s saved).
                val plateInfo = ThreeMfParser.parseForPlateSelection(plateFile)
                sourceModelFile = plateFile
                sourceModelInfo = plateInfo
                // Merge plate structural info with the file-level info so that
                // color/extruder metadata from the original file is preserved.
                // plateInfo has 0 detected colors because extractPlate() works on the
                // processed file which has had filament_sequence.json stripped by process().
                // Always use _fileThreeMfInfo (set once on load, never mutated by plate
                // selections) so that cross-plate selections don't lose file-level state
                // like hasPaintData=true from other plates (B81).
                val preSelectInfo = _fileThreeMfInfo ?: _threeMfInfo.value
                val mergedPlateInfo = if (preSelectInfo != null)
                    mergeThreeMfInfoForPlate(plateInfo, preSelectInfo, plateId)
                else
                    plateInfo
                _threeMfInfo.value = mergedPlateInfo
                resetToolRemapState()
                // Re-embed the selected plate so slice-time config preserves the
                // original file's layer-change settings (SEMM/pause G-code), not just
                // the preview metadata merged above.
                val embeddedPlateFile = embedProfile(plateFile, mergedPlateInfo, workspaceDir)
                ensureActive()
                currentModelFile = embeddedPlateFile
                loadNativeModel(embeddedPlateFile)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                NativeLibrary.previewMutex.withLock { native.clearModel() }
                _state.value = SlicerState.Error("Error extracting plate: ${e.message}")
            }
        }
    }
```

Replace with:

```kotlin
        selectPlateJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val workspaceDir = transientWorkspaceDir()
                // Source-file-level info, stable across plate selections (set once on
                // load, never overwritten by plate switches; B83).
                val fileInfo = _fileThreeMfInfo ?: _threeMfInfo.value
                // Sub-plan #2b: no Kotlin pre-extraction. Parse the source directly
                // for plate metadata; merge with file-level info; embed (XML filtered
                // to the target plate); native loadModelForPlate filters objects at
                // ingestion via Model::read_from_file(plate_id = plateId).
                ensureActive()
                val plateInfo = ThreeMfParser.parseForPlateSelection(file)
                sourceModelFile = file
                sourceModelInfo = plateInfo
                // Merge source plate-level structural info with file-level info so
                // cross-plate metadata (paint data flags, colour palette) is preserved.
                val preSelectInfo = _fileThreeMfInfo ?: _threeMfInfo.value
                val mergedPlateInfo = if (preSelectInfo != null)
                    mergeThreeMfInfoForPlate(plateInfo, preSelectInfo, plateId)
                else
                    plateInfo
                _threeMfInfo.value = mergedPlateInfo
                resetToolRemapState()
                // Embed Snapmaker profile on the SOURCE file (not a plate-extracted
                // single-plate derivative). ProfileEmbedder.embed with plateId filters
                // custom_gcode_per_layer.xml to the target plate so sub-plan #3's XML
                // fallback sees only plate-scoped layer-tool rows.
                val embeddedPlateFile = embedProfile(file, mergedPlateInfo, workspaceDir, plateId = plateId)
                ensureActive()
                currentModelFile = embeddedPlateFile
                // Native-side plate filter: plateId is 1-based from the UI; Kotlin
                // convention for NativeLibrary.loadModelForPlate is 0-based; the JNI
                // wrapper converts to BBS plate_id = plateIdx + 1 on the C++ side.
                loadNativeModel(embeddedPlateFile, plateId - 1)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                NativeLibrary.previewMutex.withLock { native.clearModel() }
                _state.value = SlicerState.Error("Error loading plate: ${e.message}")
            }
        }
    }
```

**Note:** `fileInfo`, `hasPlateJsons`, `plateObjectIds`, `plateExtruderMap`, and `rawPlateFile`/`plateFile` local variables are all unused now — they were only for extractPlate args. The new body intentionally omits them.

- [ ] **Step 6: Compile.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ./gradlew assembleDebug assembleDebugAndroidTest --no-daemon
```

Expected: BUILD SUCCESSFUL for both APKs. If any compile error surfaces (missing import, stray variable reference), resolve it in-place and re-run. **Hard abort D** applies only after three fix attempts.

- [ ] **Step 7: JVM unit sweep.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ./gradlew testDebugUnitTest --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Re-install APKs + run the primary integration guard first (flippy painted).**

```bash
adb -s 43211JEKB16931 uninstall com.u1.slicer.orca; adb -s 43211JEKB16931 uninstall com.u1.slicer.orca.test
```

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && adb -s 43211JEKB16931 install -r -d app/build/outputs/apk/debug/app-debug.apk && adb -s 43211JEKB16931 install -r -d app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

```bash
adb -s 43211JEKB16931 shell am instrument -w -r -e class com.u1.slicer.slicing.ProfileEmbedderIntegrationTest#flippyFlappyMini_fullPipeline_emitsLayerChangePauseGcode com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: `OK (1 test)`. **If this fails, STOP.** It's the canary for Risk 4 (custom_gcode_per_layer.xml cross-plate contamination). Follow Hard Abort Criterion A.

- [ ] **Step 9: Run BambuPipelineIntegrationTest (34 tests — the biggest regression guard).**

```bash
adb -s 43211JEKB16931 shell am instrument -w -r -e class com.u1.slicer.slicing.BambuPipelineIntegrationTest com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: `OK (34 tests)`. This class exercises plate extraction, multi-color restructure, per-part extruder parsing, B23/B54/B82/B83/B86 — every multi-plate regression of record. If any test fails, identify which fixture → root-cause → fix. **Option A-prime fallback:** if 3+ tests fail clustered on painted-multi-plate fixtures (Dragon Scale, Shashibo), consider branching `selectPlate` to keep extractPlate for those specific cases.

- [ ] **Step 10: Run SEMM + Goat + Sensory Twist + B95 painted-state tests.**

```bash
adb -s 43211JEKB16931 shell am instrument -w -r -e package com.u1.slicer.slicing com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: `OK (81 tests)` (or higher if new tests have landed) — 34 BambuPipeline + 39 SlicingIntegration + 5 SEMM + 1 SensoryTwist + 1 GoatDedupe + 13 ProfileEmbedder + (B95Plate9PaintStateTest is a separate package if not under slicing).

Double-check the B95 test explicitly:

```bash
adb -s 43211JEKB16931 shell am instrument -w -r -e class com.u1.slicer.slicing.B95Plate9PaintStateTest com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: `OK (2 tests)`.

- [ ] **Step 11: Run NativePreparePreviewTest + PreparePreviewViewModelTest (viewer regressions).**

```bash
adb -s 43211JEKB16931 shell am instrument -w -r -e package com.u1.slicer.viewer com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: `OK (20 tests)` — 16 NativePreparePreview + 4 ThreeMfMeshParser (if still present — sub-plan #1 retirement may have pruned these; cross-check with CLAUDE.md).

```bash
adb -s 43211JEKB16931 shell am instrument -w -r -e class com.u1.slicer.PreparePreviewViewModelTest com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: `OK (14 tests)`.

- [ ] **Step 12: Run diff harness (must stay at 0 baseline).**

```bash
adb -s 43211JEKB16931 shell am instrument -w -r -e class com.u1.slicer.bambu.BambuParserDifferentialTest com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: `OK (21 tests)`. If any new diff entries appear, this plan touched a snapshot field it shouldn't have — investigate before committing.

- [ ] **Step 13: Commit.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && git restore -- app/src/main/cpp/extern/ && git add app/src/main/java/com/u1/slicer/SlicerViewModel.kt && git status --short && git commit -m "$(cat <<'EOF'
phase1(bambu-native): SlicerViewModel.selectPlate uses native plate_id filter (sub-plan #2b task 4)

Retires the Kotlin BambuSanitizer.extractPlate + restructurePlateFile
disk-rewrite pair from the production slice path. selectPlate now:
1. Parses the source file directly (parseForPlateSelection on the
   full multi-plate 3MF, merge picks out plate N).
2. Embeds Snapmaker profile on the source with plateId passed through
   (ProfileEmbedder filters custom_gcode_per_layer.xml to target plate).
3. Calls loadNativeModel(embeddedFile, plateId - 1) — Kotlin 0-based
   plateIdx; JNI converts to BBS plate_id = plateIdx + 1 (1-based).

Net: removes one disk-write step per plate switch; native BBS importer
handles <build> filtering and per-object extruder parsing directly
from the source model_settings.config. B93 cold-load benefit preserved
(still one native parse per selection — the rewrite was just skipped).

extractPlate/restructurePlateFile remain LINKED IN for their ~23 test
callers (BambuPipelineIntegrationTest, NativePreparePreviewTest,
ProfileEmbedderIntegrationTest, B95Plate9PaintStateTest). Deletion is
a follow-up sub-plan #2c, post-v1.7.0.

Regression sweep (Pixel 8a 43211JEKB16931):
- BambuPipelineIntegrationTest: OK (34)
- SlicingIntegrationTest + SemmSlicingTest + GoatDedupeSemmTest + SensoryTwistSupportsTest: all green
- ProfileEmbedderIntegrationTest (incl. flippyFlappyMini_fullPipeline_emitsLayerChangePauseGcode): OK (13)
- NativePreparePreviewTest + PreparePreviewViewModelTest: all green
- B95Plate9PaintStateTest: OK (2)
- BambuParserDifferentialTest: OK (21) — diff harness still at 0 baseline
EOF
)"
```

Fill in exact test counts from the terminal output before committing.

---

## Task 5 — Update CLAUDE.md test counts + append MORNING_STATUS.md

**Files:**
- Modify: `CLAUDE.md` (committed)
- Modify: `MORNING_STATUS.md` (untracked — do NOT commit)

- [ ] **Step 1: Bump the per-class unit test count in CLAUDE.md.**

Verify actual counts before editing. `CLAUDE.md`'s current per-class unit listing (around line 76 of the worktree's CLAUDE.md, at branch HEAD) reads:

```
### Unit tests (`app/src/test/`) - 812 tests across 57 classes
```

Change to:

```
### Unit tests (`app/src/test/`) - 814 tests across 57 classes
```

And the `bambu/ProfileEmbedderTest.kt` per-file entry (find with `grep -n "ProfileEmbedderTest.kt"` in CLAUDE.md). Current reads (approximately):

```
- `bambu/ProfileEmbedderTest.kt` (5) — convertToModelSettings: per-volume extruder preservation, remap, attribute order
```

Change to:

```
- `bambu/ProfileEmbedderTest.kt` (7) — convertToModelSettings: per-volume extruder preservation, remap, attribute order; sub-plan #2b plate-filtered custom_gcode_per_layer.xml
```

- [ ] **Step 2: Bump the instrumented test count in CLAUDE.md.**

Find:

```
### Instrumented tests (`app/src/androidTest/`) - 205 tests across 19 classes
```

Change to:

```
### Instrumented tests (`app/src/androidTest/`) - 207 tests across 19 classes
```

Find the `native/NativeLibraryCorrectnessTest.kt` entry and bump its count from 12 to 14 with a trailing annotation:

```
- `native/NativeLibraryCorrectnessTest.kt` (12) — JNI correctness checks + Phase 1 sub-plan #1 accessors …
```

Change to:

```
- `native/NativeLibraryCorrectnessTest.kt` (14) — JNI correctness checks + Phase 1 sub-plan #1 accessors … + sub-plan #2b loadModelForPlate (single-plate match + plateIdx=-1 all-plates alias)
```

Fill the middle of the entry verbatim from the existing text — only the count changes and the `+ sub-plan #2b …` note is added.

- [ ] **Step 3: Add a Key Conventions note for the new JNI entry.**

In `CLAUDE.md`, find the "Key Conventions" section (search for `## Key Conventions`). Add one new bullet at the end:

```markdown
- `NativeLibrary.loadModelForPlate(path, plateIdx)` (Phase 1 sub-plan #2b) — plate-aware 3MF loader. `plateIdx = -1` → BBS `plate_id=0` (all plates, same as `loadModel`); `plateIdx >= 0` → BBS `plate_id = plateIdx + 1` (1-based). Used by `SlicerViewModel.selectPlate` to skip the Kotlin `BambuSanitizer.extractPlate` ZIP rewrite; BBS importer filters `m_plater_data[plate_id].obj_inst_map` at load time.
```

- [ ] **Step 4: Verify CLAUDE.md edits are consistent.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && grep -n "812\|814\|205\|207\|NativeLibraryCorrectnessTest\|ProfileEmbedderTest\|loadModelForPlate" CLAUDE.md | head -20
```

Expected: counts updated to 814 + 207, two test-file lines annotated, new Key Conventions bullet present.

- [ ] **Step 5: Commit CLAUDE.md.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && git restore -- app/src/main/cpp/extern/ && git add CLAUDE.md && git commit -m "$(cat <<'EOF'
docs(CLAUDE.md): test counts + loadModelForPlate convention note (sub-plan #2b task 5)

Unit count 812 -> 814: +2 for sub-plan #2b ProfileEmbedderTest
plate-filtered custom_gcode_per_layer.xml.

Instrumented count 205 -> 207: +2 for NativeLibraryCorrectnessTest
loadModelForPlate smoke (single-plate match + plateIdx=-1 alias).

Key Conventions gains a one-line note on the loadModelForPlate JNI
contract (plateIdx convention vs BBS plate_id).
EOF
)"
```

- [ ] **Step 6: Append "Follow-up landed" to MORNING_STATUS.md (LOCAL, do NOT commit).**

Open `MORNING_STATUS.md` at the worktree root. Append at the bottom, after the existing `## Follow-up landed (2026-04-24) — LayerToolPauseInjector sub-plan #3` section, a new section in the same format:

```markdown

## Follow-up landed (2026-04-24) — sub-plan #2b extract-plate migration

Executed per `docs/superpowers/plans/2026-04-24-phase1-sub-plan-2b-extract-plate.md`. Option A (native `loadModelForPlate` JNI entry + BBS `plate_id` filter at `Model::read_from_file`) with Risk 4 mitigation m1 (ProfileEmbedder filters `custom_gcode_per_layer.xml` to the target plate). `BambuSanitizer.extractPlate` / `restructurePlateFile` retired from the production slice path; test callers unchanged.

| Commit | Task | Summary |
|---|---|---|
| `<hash>` | design notes | `docs(phase1)`: design notes for sub-plan #2b BambuSanitizer.extractPlate migration |
| `<hash>` | plan | `docs(phase1)`: executable plan for sub-plan #2b extract-plate migration |
| `<hash>` | 1 | `phase1(bambu-native)`: add loadModelForPlate JNI entry + rebuild .so (sub-plan #2b task 1) |
| `<hash>` | 2 | `phase1(bambu-native)`: widen filterCustomGcodePerLayer to internal (sub-plan #2b task 2) |
| `<hash>` | 3 | `phase1(bambu-native)`: ProfileEmbedder.embed gains plateId + filters custom_gcode_per_layer.xml (sub-plan #2b task 3) |
| `<hash>` | 4 | `phase1(bambu-native)`: SlicerViewModel.selectPlate uses native plate_id filter (sub-plan #2b task 4) |
| `<hash>` | 5 | `docs(CLAUDE.md)`: test counts + loadModelForPlate convention note (sub-plan #2b task 5) |

**Regression sweep** (Pixel 8a `43211JEKB16931`, post-Task 5):
- `./gradlew testDebugUnitTest` → BUILD SUCCESSFUL (ProfileEmbedderTest +2 new)
- bambu package (`-e package com.u1.slicer.bambu`) → **OK (N tests)** — fill with actual count
- gcode package (`-e package com.u1.slicer.gcode`) → **OK (N tests)**
- slicing package (`-e package com.u1.slicer.slicing`) → **OK (N tests)** — incl. 34 BambuPipelineIntegrationTest + 13 ProfileEmbedderIntegrationTest
- native package (`-e package com.u1.slicer.native`) → **OK (N tests)** — incl. 2 new loadModelForPlate_* tests in NativeLibraryCorrectnessTest
- viewer package (`-e package com.u1.slicer.viewer`) → **OK (N tests)**
- `BambuParserDifferentialTest` → **OK (21 tests)** — diff harness still at 0 baseline (no snapshot-field changes)
- `B95Plate9PaintStateTest` → **OK (2 tests)** — Buzz plate 9 bit-packed paint states preserved across the migration

**Scope check:**
- ✅ `BambuSanitizer.extractPlate` / `restructurePlateFile` still linked in for ~23 test callers; deletion deferred to sub-plan #2c.
- ✅ `LayerToolPauseInjector`, `LayerToolCustomGcodeXml`, `ThreeMfParser.parseLayerToolSegments` untouched — scope firewall held.
- ✅ No touches to `app/src/main/cpp/extern/` or `app/src/main/cpp/orcaslicer/` (`git restore` before every `git add`).
- ✅ `NativeLibrary.loadModel(path)` signature unchanged — parallel `loadModelForPlate` entry added instead.
- ✅ Diff harness baseline unchanged (0 entries).
- ✅ No pushes, no releases, no `versionCode` / `versionName` bump. Single v1.7.0 cut remains the user's manual step.

**Native rebuild:** `.so` rebuilt NDK 26 / Clang 17.0.2 / Release / stripped / ~20 MB. Dynamic symbol table includes new `Java_com_u1_slicer_NativeLibrary_loadModelForPlate`.

**Open follow-ups (explicitly out of scope for this sub-plan):**
- `BambuSanitizer.extractPlate` / `restructurePlateFile` deletion + test prune → sub-plan #2c (post-v1.7.0).
- Potential retirement of sub-plan #3's XML fallback once `g_model.plates_custom_gcodes` survives `Print::process()` → C++ investigation; separate sub-plan.
- `ProfileEmbedder.embed` plate-filter the `3D/3dmodel.model` mesh too if profiling shows cold-load latency regression on Buzz / large multi-plate files → not measured today; defer.

Branch HEAD is `<hash>`. N net commits on top of prior `95229c1`. Phase 1 production-code migration is now complete: Bambu plate selection flows through native `plate_id`-filtered load, Kotlin ZIP rewrites retired from slice-time.
```

Fill in the commit hashes and actual test counts before saving. Use `git log --oneline 9f128ef..HEAD` to get the commit list.

- [ ] **Step 7: Verify the status appendix is in place.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && tail -80 MORNING_STATUS.md
```

Expected: the new section is visible.

- [ ] **Step 8: Final working-tree check.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && git status --short && git log --oneline 9f128ef..HEAD
```

Expected: `git status` shows only `?? MORNING_STATUS.md` (intentionally untracked). Git log shows exactly 6 new commits (plan + 5 tasks) on top of the design-notes commit `9f128ef`.

---

## Task 6 — Full-suite regression sweep (no code changes; verification only)

- [ ] **Step 1: Final JVM unit sweep.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ./gradlew testDebugUnitTest --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Instrumented packages sweep — all slice-adjacent packages.**

```bash
adb -s 43211JEKB16931 uninstall com.u1.slicer.orca; adb -s 43211JEKB16931 uninstall com.u1.slicer.orca.test
```

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ./gradlew assembleDebug assembleDebugAndroidTest --no-daemon && adb -s 43211JEKB16931 install -r -d app/build/outputs/apk/debug/app-debug.apk && adb -s 43211JEKB16931 install -r -d app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

```bash
adb -s 43211JEKB16931 shell am instrument -w -r -e package com.u1.slicer.bambu com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

```bash
adb -s 43211JEKB16931 shell am instrument -w -r -e package com.u1.slicer.native com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

```bash
adb -s 43211JEKB16931 shell am instrument -w -r -e package com.u1.slicer.slicing com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

```bash
adb -s 43211JEKB16931 shell am instrument -w -r -e package com.u1.slicer.gcode com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

```bash
adb -s 43211JEKB16931 shell am instrument -w -r -e package com.u1.slicer.viewer com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

```bash
adb -s 43211JEKB16931 shell am instrument -w -r -e class com.u1.slicer.bambu.BambuParserDifferentialTest com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: every package returns `OK (N tests)` with N matching pre-migration counts (bambu 26, native 28 = 26 pre + 2 new, slicing 81+, gcode 8, viewer ~20, differential 21). **If any package fails, Hard Abort Criterion D applies: identify stray change, revert, re-run.**

- [ ] **Step 3: No commit — pure verification.**

This task ends with `git status --short` showing the same state as the end of Task 5. No file changes.

---

## Hard Abort Criteria (apply during every task)

A. **`flippyFlappyMini_fullPipeline_emitsLayerChangePauseGcode` fails after Task 4.** Risk 4 mitigation m1 is broken — either `ProfileEmbedder` isn't filtering XML correctly, or `SlicerViewModel.selectPlate` isn't passing `plateId` through. Attempt up to 3 fixes; if still failing, WIP commit + escalate. Do NOT delete or weaken the test.

B. **`BambuPipelineIntegrationTest` regresses on 3+ tests clustered on painted or older-format multi-plate fixtures** (Dragon Scale, Shashibo, Flarewing, flippy, Buzz). Option A fails for those fixtures. Fall back to Option A-prime: add an if-guard in `SlicerViewModel.selectPlate` that keeps extractPlate + restructurePlateFile for `info.hasPaintData || info.hasMultiExtruderAssignments` files, and uses the native path for the single-colour single-object case. Document the split in the commit message; this is a valid partial landing.

C. **Native rebuild fails on two consecutive attempts.** Abort at Task 1; do not ship a bad `.so`. Commit WIP with the `.so` reverted (`git checkout HEAD -- app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so`) and escalate.

D. **Any test outside `slicing/`, `bambu/`, `native/`, `viewer/`, `gcode/` packages regresses.** Out-of-scope edit leaked. Stop, `git diff HEAD~1`, revert the stray change.

E. **Pixel 8a install cycle fails twice in a row after `adb kill-server; adb start-server`.** WIP + escalate.

F. **Sub-plan #3's dual-path `check()` starts firing on a fixture that was green before Task 4.** Means the new plate-scoped `custom_gcode_per_layer.xml` in the embedded file disagrees with `nativeGetPlateData(plateIdx)`'s customGcode. Possible causes: off-by-one in plateId conversion (Kotlin 0-based vs BBS 1-based), `BambuSanitizer.filterCustomGcodePerLayer` renumbering to `plate_info id=1` vs native expecting the original. Capture divergence, **do NOT paper over by widening the check**, WIP + escalate.

G. **`BambuParserDifferentialTest` baseline moves off 0 entries after any commit.** Snapshot-field contract breach. `git diff` to locate the change; revert.

**WIP commit template:**

```bash
git checkout -b wip/sub-plan-2b-extract-plate
git restore -- app/src/main/cpp/extern/
git add -u
git commit -m "wip: sub-plan #2b extract-plate migration (aborted)

<hash> was the last green commit. Aborted at task <N> step <M> after
<design-notes blocker / native rebuild failure / test regression / divergence>.
Next action: <specific recommendation>."
```

---

## Self-Review (plan-writing-skill checklist)

**Spec coverage (against the design notes):**
- §1 current architecture — covered in plan intro + Task 4 call-site rewrite.
- §2 native state — covered in Task 1 (loadModelForPlate entry + rebuild).
- §3 the gap — Task 1 implements all sub-items.
- §4 migration strategies — Option A is implemented; Option A-prime documented in Hard Abort B.
- §4b sub-plan #3 interaction — Task 3 (m1) is dedicated to this.
- §5 test strategy — Task 1 adds 2 native smoke tests; Task 3 adds 2 JVM unit tests; Task 4 runs the 127-test primary regression gate; Task 6 the full sweep.
- §6 risks — each one has an explicit plan step or abort criterion.
- §7 scope firewall — mirrored at plan top.
- §8 commit sequence — matches plan tasks 1-5.
- §9 open questions — carried forward implicitly through the verification steps.

**Placeholder scan:** grep-checked for TBD / TODO / "implement later" / "appropriate error handling" / "similar to Task N" — none present.

**Type consistency:**
- `loadModelForPlate(path, plateIdx)` — consistent in NativeLibrary.kt (Task 1 step 3), JNI wrapper (step 4), SlicerEngine::loadModel(path, plate_id) C++ (step 5-6), test calls (step 1), Task 4's `native.loadModelForPlate(...)` (Task 4 step 3).
- Kotlin `plateIdx` (0-based Kotlin convention) vs BBS `plate_id` (1-based) — conversion happens exactly once, inside the JNI wrapper (`jplate_idx < 0 ? 0 : jplate_idx + 1`). Design notes section 2a documents this. `SlicerViewModel.selectPlate` passes `plateId - 1` because its `plateId` is UI-1-based.
- `ProfileEmbedder.embed` signature `plateId: Int?` is UI-1-based (same space as `SlicerViewModel`). Task 3 test uses `plateId = 1` for "plate 1".
- `BambuSanitizer.filterCustomGcodePerLayer(xml, targetPlateId)` is UI-1-based (confirmed by line 1631: `<plate_info id="$targetPlateId"/>`). Plan passes `plateId` (UI-1-based) straight through.

No inconsistencies.

**Placeholder check PASSED.**
