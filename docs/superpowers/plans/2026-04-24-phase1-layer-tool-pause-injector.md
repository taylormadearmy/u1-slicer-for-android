# Phase 1 Sub-Plan #3 — LayerToolPauseInjector Migration

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate `LayerToolPauseInjector` off `parseLayerToolSegments` XML parsing onto the native `nativeGetPlateData(plateIdx)` JSON payload, using dual-path (Option B) with a debug-build divergence assertion so any native/XML mismatch fires in test runs before the XML path is removed.

**Architecture:** The injector currently reads `Metadata/custom_gcode_per_layer.xml` from the source 3MF zip and maps `<layer type="1"|"2">` entries to `PauseTarget(topZ, extruderBambu)`. The native C++ emitter already serializes the same data (from `g_model.plates_custom_gcodes`) as `customGcode[]` entries with canonical `type: "ColorChange"|"ToolChange"` names and `printZ`/`extruder` fields (shared emitter `sapil::append_plate`, exposed via `NativeLibrary.nativeGetPlateData(plateIndex)`). The injector keeps a zip re-open for `machine_pause_gcode` and `nozzle_temperature` (fields not present in any native payload). `injectFrom3mf` gains `plateIdx: Int` and `native: NativeLibrary` parameters; production passes `_currentPlateId.value - 1` (coerced to >= 0). In debug builds both paths run and `check(xmlTargets == nativeTargets)` fires on any disagreement. After an on-device integration test passes, the XML parse inside the injector is removed and the `parseLayerToolSegments` import is dropped from `LayerToolPauseInjector.kt`.

**Tech Stack:** Kotlin 1.9.22, Android minSdk 26 / compileSdk 34, Gradle, JUnit 4, AndroidJUnitRunner, Android Test Orchestrator, `org.json.JSONObject`, `java.util.zip.ZipFile`. Device: Pixel 8a `43211JEKB16931` with known phantom `versionCode 257` (use uninstall/reinstall workaround; never `connectedDebugAndroidTest`).

---

## Scope Firewall (read before starting every task)

**DO NOT TOUCH** in this plan:
- `app/src/main/java/com/u1/slicer/bambu/LayerToolCustomGcodeXml.kt` — **stays in place**. `ThreeMfParser.kt:245/248/251/511` still calls `parseLayerToolCustomGcodeXml`, `parseLayerToolSegments`, `parseLayerToolCustomGcodeXmlPerPlate`. `KotlinBambuSnapshot.kt:315-316` references them in KDoc. `PreviewColorNormalizationTest.kt` has 4 unit tests against `parseLayerToolSegments`. `NativePreparePreviewTest.kt:9` imports it. Deleting the file would cascade into those call sites, which are out of scope (the viewer's `recolorByZBands` Z-band path is separate from the injector).
- `app/src/main/java/com/u1/slicer/bambu/ThreeMfParser.kt` — any line, any function.
- `app/src/main/java/com/u1/slicer/bambu/BambuSanitizer.kt` — that is sub-plan #2b territory.
- `app/src/main/java/com/u1/slicer/SlicerViewModel.mergeThreeMfInfoForPlate` — out of scope.
- Anything under `app/src/main/cpp/` — no native rebuild is required; `nativeGetPlateData` already ships.
- `app/src/main/cpp/extern/` — always `git restore -- app/src/main/cpp/extern/` before every `git add`.
- Any BambuPipelineIntegrationTest, SemmSlicingTest, GoatDedupeSemmTest, NativePreparePreviewTest file.

**The user brief said "delete `LayerToolCustomGcodeXml.kt`".** The design notes Task 5 qualify that as "if `parseLayerToolCustomGcodeXml`, `parseLayerToolCustomGcodeXmlPerPlate`, and `parseLayerToolSegments` have no remaining callers outside `ThreeMfParser.kt`. If `ThreeMfParser.kt` still uses them … leave the file." All three functions still have callers in `ThreeMfParser.kt` and in unit/instrumented tests for the viewer's Z-band recolor path. **Therefore the file stays.** This is documented in MORNING_STATUS.md's "Follow-up landed" appendix at plan completion.

---

## Pre-flight (one-time setup, before Task 1)

- [ ] **Pre-1: Confirm branch HEAD and worktree cleanliness.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && git rev-parse HEAD && git status --short
```

Expected: HEAD shows the "executable plan" commit (created immediately before starting Task 1). `git status --short` shows only `?? MORNING_STATUS.md` (that file is deliberately untracked).

- [ ] **Pre-2: Confirm native `.so` present and `nativeGetPlateData` symbol exported.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ls -la app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so && C:/Users/kevin/AppData/Local/Android/Sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe --dyn-syms app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so | grep nativeGetPlateData
```

Expected: `.so` is ~20MB (not 80MB / not 516MB), and grep finds `Java_com_u1_slicer_NativeLibrary_nativeGetPlateData`. If either fails, stop and re-read the design notes (this plan does NOT rebuild the `.so`).

- [ ] **Pre-3: Confirm diff harness baseline still green at zero entries.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && cat app/src/androidTest/assets/diagnostics/known-disagreements.json
```

Expected: Empty JSON array `[]` or file contains an empty `entries` list (a 0-entry baseline). If non-empty, stop — Phase 1 is not at the clean state this plan assumes.

---

## File Structure

**Files created:** none.

**Files modified:**
- `app/src/main/java/com/u1/slicer/gcode/LayerToolPauseInjector.kt` — primary target. Adds `extractPauseTargetsFromNativeJson(plateJson: String): List<PauseTarget>`. Signature of `injectFrom3mf` grows `plateIdx: Int` and `native: NativeLibrary` parameters. Internals gain dual-path (XML + native) with `check(...)` assertion in debug builds; emits native-derived targets in production. Final task removes the XML path and the `parseLayerToolSegments` import.
- `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` — one call site at line 2353. Thread `_currentPlateId.value` (1-based → 0-based) and pass `native` handle (already held as `val native`) into `injectFrom3mf`.
- `app/src/androidTest/java/com/u1/slicer/slicing/ProfileEmbedderIntegrationTest.kt` — one call site in `flippyFlappyMini_fullPipeline_emitsLayerChangePauseGcode` (line 402). Pass `plateIdx = 0` and the test's `lib` handle (already the `NativeLibrary` instance). Note: `lib.loadModel(embedded.absolutePath)` has already been called at line 393, so `nativeGetPlateData` will see the sliced file's g_model state.
- `app/src/test/java/com/u1/slicer/gcode/LayerToolPauseInjectorTest.kt` — extend existing unit tests to exercise the native-json helper. Existing 3MF-fixture tests remain unchanged (they exercise the zip path for `machine_pause_gcode`/`nozzle_temperature` which is retained; the dual-path debug assertion does not run in these tests because they do not load g_model). Add 2 new unit tests against `extractPauseTargetsFromNativeJson` directly with hand-crafted JSON strings.
- `CLAUDE.md` — bump unit test count `810 → 812` and keep instrumented count at `205` (no new instrumented tests added; the existing `flippyFlappyMini_fullPipeline_emitsLayerChangePauseGcode` test verifies the dual-path in Task 5).

**Files NOT modified** (scope firewall): `LayerToolCustomGcodeXml.kt`, `ThreeMfParser.kt`, `BambuSanitizer.kt`, anything under `cpp/`, the diff harness baseline, any other instrumented test.

---

## Task 1 — Add JVM unit tests for the native-JSON path

**Files:**
- Modify: `app/src/test/java/com/u1/slicer/gcode/LayerToolPauseInjectorTest.kt`

**Rationale:** TDD. Before touching the injector, write the failing unit tests that pin down the shape of `extractPauseTargetsFromNativeJson(plateJson: String): List<PauseTarget>`. These are pure-Kotlin tests — no device, no g_model, no Android runtime. They run in `./gradlew testDebugUnitTest`.

**Facts the tests pin down:**
- Accepts `"ColorChange"` and `"ToolChange"` type strings (native canonical).
- Rejects `"PausePrint"`, `"Template"`, `"Custom"`, `"Unknown"` (not tool-change rows).
- Rejects `"1"` and `"2"` (those are XML-path strings; native never emits them).
- `printZ` (JSON number, double) is narrowed to `Float` for `PauseTarget.topZ`.
- `extruder` (JSON number, int) is preserved as 1-based `PauseTarget.extruderBambu`.
- Empty `customGcode` array → empty list.
- Missing `customGcode` key → empty list.
- Malformed JSON → empty list (never throws).
- Output is sorted ascending by `topZ`.

- [ ] **Step 1: Add two new `@Test` methods to `LayerToolPauseInjectorTest` targeting the new helper.**

Append these two methods inside the existing `class LayerToolPauseInjectorTest { ... }` body, immediately before the `private fun write(...)` helper at the end. The tests reference a not-yet-existing helper `LayerToolPauseInjector.extractPauseTargetsFromNativeJsonForTest(plateJson: String)` — a thin `@VisibleForTesting` wrapper around the real `private fun extractPauseTargetsFromNativeJson(...)` we add in Task 2. Without that wrapper the test cannot see a `private` declaration.

```kotlin
    @Test
    fun `extractPauseTargetsFromNativeJson accepts ColorChange and ToolChange and drops other types`() {
        val json = """
            {
              "customGcode": [
                {"printZ": 3.2, "type": "ColorChange", "extruder": 2, "color": "#AA0000"},
                {"printZ": 1.6, "type": "ToolChange",  "extruder": 3, "color": "#00AA00"},
                {"printZ": 2.0, "type": "PausePrint",  "extruder": 1, "color": "#000000"},
                {"printZ": 2.5, "type": "Template",    "extruder": 1, "color": ""},
                {"printZ": 4.0, "type": "Custom",      "extruder": 1, "color": ""},
                {"printZ": 5.0, "type": "1",           "extruder": 1, "color": ""},
                {"printZ": 6.0, "type": "2",           "extruder": 1, "color": ""}
              ]
            }
        """.trimIndent()
        val targets = LayerToolPauseInjector.extractPauseTargetsFromNativeJsonForTest(json)
        assertEquals(
            "only ColorChange and ToolChange rows become targets, sorted ascending by topZ",
            listOf(1.6f to 3, 3.2f to 2),
            targets.map { it.topZ to it.extruderBambu }
        )
    }

    @Test
    fun `extractPauseTargetsFromNativeJson tolerates empty, missing, and malformed input`() {
        assertEquals(
            "empty customGcode array → empty list",
            emptyList<Pair<Float, Int>>(),
            LayerToolPauseInjector.extractPauseTargetsFromNativeJsonForTest(
                """{"customGcode": []}"""
            ).map { it.topZ to it.extruderBambu }
        )
        assertEquals(
            "missing customGcode key → empty list",
            emptyList<Pair<Float, Int>>(),
            LayerToolPauseInjector.extractPauseTargetsFromNativeJsonForTest(
                """{"plateIndex": 0}"""
            ).map { it.topZ to it.extruderBambu }
        )
        assertEquals(
            "malformed JSON → empty list (never throws)",
            emptyList<Pair<Float, Int>>(),
            LayerToolPauseInjector.extractPauseTargetsFromNativeJsonForTest(
                """not-a-json"""
            ).map { it.topZ to it.extruderBambu }
        )
    }
```

Also add this import at the top of the test file if not already present:

```kotlin
import org.junit.Assert.assertEquals
```

(The file already imports `assertEquals` at line 3 — verify; if present, skip.)

- [ ] **Step 2: Run only these two new tests to confirm they fail with "unresolved reference".**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ./gradlew testDebugUnitTest --no-daemon --tests "com.u1.slicer.gcode.LayerToolPauseInjectorTest.extractPauseTargetsFromNativeJson*"
```

Expected: BUILD FAILED with a compilation error like `unresolved reference: extractPauseTargetsFromNativeJsonForTest`. This is the RED step — the helper does not exist yet.

- [ ] **Step 3: Do NOT commit a failing-compile test. Proceed directly to Task 2.**

Rationale: a compile-break commit would poison `git bisect`. The green state for this test arrives at the end of Task 2.

---

## Task 2 — Implement the native-JSON parser and expose a test shim

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/gcode/LayerToolPauseInjector.kt`

**Rationale:** Add a pure-Kotlin helper that decodes a `nativeGetPlateData` JSON payload into `List<PauseTarget>`. Expose a `@VisibleForTesting` shim so unit tests can call it without a `NativeLibrary` handle. No call sites change yet — `injectFrom3mf`'s public signature is untouched in this task.

- [ ] **Step 1: Add the helper and test shim.**

In `app/src/main/java/com/u1/slicer/gcode/LayerToolPauseInjector.kt`, add an import near the existing `import org.json.JSONObject`:

```kotlin
import androidx.annotation.VisibleForTesting
import org.json.JSONArray
import org.json.JSONException
```

Inside `object LayerToolPauseInjector { ... }`, immediately after the existing `private fun extractPauseTargets(xml: String): List<PauseTarget>` block (ends at line 136), insert:

```kotlin
    /**
     * Decode the `customGcode` array from [NativeLibrary.nativeGetPlateData]'s JSON payload into
     * [PauseTarget] rows. Accepts only canonical native type strings `"ColorChange"` and
     * `"ToolChange"`. `printZ` (Double) narrows to [Float]; `extruder` stays 1-based.
     * Returns an empty list on any parse error — never throws.
     *
     * Paired with the Kotlin-XML path [extractPauseTargets] for dual-path verification during
     * Phase 1 migration. See `docs/superpowers/plans/2026-04-24-phase1-layer-tool-pause-injector.md`.
     */
    private fun extractPauseTargetsFromNativeJson(plateJson: String): List<PauseTarget> {
        return try {
            val obj = JSONObject(plateJson)
            val arr: JSONArray = obj.optJSONArray("customGcode") ?: return emptyList()
            val out = ArrayList<PauseTarget>(arr.length())
            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                val type = row.optString("type", "")
                if (type != "ColorChange" && type != "ToolChange") continue
                val topZ = row.optDouble("printZ", Double.NaN)
                if (topZ.isNaN()) continue
                val extruder = row.optInt("extruder", 1)
                out.add(PauseTarget(topZ.toFloat(), extruder))
            }
            out.sortedWith(compareBy({ it.topZ }, { it.extruderBambu }))
        } catch (_: JSONException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    @VisibleForTesting
    internal fun extractPauseTargetsFromNativeJsonForTest(plateJson: String): List<PauseTarget> =
        extractPauseTargetsFromNativeJson(plateJson)
```

Also make `PauseTarget` accessible to the `internal` test shim by widening its visibility from `private` to `internal`. Change line 23:

```kotlin
    private data class PauseTarget(val topZ: Float, val extruderBambu: Int)
```

to:

```kotlin
    internal data class PauseTarget(val topZ: Float, val extruderBambu: Int)
```

- [ ] **Step 2: Run Task 1's unit tests — they should now pass.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ./gradlew testDebugUnitTest --no-daemon --tests "com.u1.slicer.gcode.LayerToolPauseInjectorTest.extractPauseTargetsFromNativeJson*"
```

Expected: BUILD SUCCESSFUL, 2 tests passed.

- [ ] **Step 3: Run the full `LayerToolPauseInjectorTest` class to confirm no regression.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ./gradlew testDebugUnitTest --no-daemon --tests "com.u1.slicer.gcode.LayerToolPauseInjectorTest"
```

Expected: 11 tests passed (9 pre-existing + 2 new).

- [ ] **Step 4: Commit.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && git restore -- app/src/main/cpp/extern/ && git add app/src/main/java/com/u1/slicer/gcode/LayerToolPauseInjector.kt app/src/test/java/com/u1/slicer/gcode/LayerToolPauseInjectorTest.kt && git status --short
```

Confirm only the injector + test are staged (plus possibly unrelated `app/src/main/cpp/extern/` auto-dirty files should be absent — git restore handled them).

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && git commit -m "$(cat <<'EOF'
phase1(bambu-native): add native-JSON path for LayerToolPauseInjector (sub-plan #3 task 2)

Adds extractPauseTargetsFromNativeJson to decode nativeGetPlateData's
customGcode array into PauseTarget rows. Accepts only canonical native
type strings ColorChange/ToolChange; printZ narrows to Float. Exposes
a @VisibleForTesting shim for two new JVM unit tests covering the type
filter and malformed-input tolerance.

injectFrom3mf's public signature and runtime path are unchanged in this
commit — the dual-path wiring lands in task 3. Per design notes Option B.
EOF
)"
```

---

## Task 3 — Dual-path wiring in `injectFrom3mf` (debug-build assertion)

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/gcode/LayerToolPauseInjector.kt`

**Rationale:** Change `injectFrom3mf` to accept `plateIdx: Int` and `native: NativeLibrary?` (nullable so pure-JVM unit tests with no native handle still compile). Both paths run: existing XML path computes `xmlTargets`, new native path computes `nativeTargets`. Production path emits `nativeTargets` (if non-null from native). On `BuildConfig.DEBUG` builds the two are compared with `check(...)` — disagreement fails loudly. Native path falls back to XML if `nativeGetPlateData` returns null or `plateIdx < 0` or `native == null` (e.g. legacy unit-test paths, or model not loaded — belt-and-braces; never expected at the slice-time production call site).

**Important — `PauseTarget` equality.** `PauseTarget` is a Kotlin `data class`, so structural equality is auto-generated. The dual-path `check(xmlTargets == nativeTargets)` compares lists element-wise by `(topZ, extruderBambu)`. `topZ` is a `Float` in both paths (XML narrows from string, native narrows from Double). The divergence check runs over the sorted-distinct-by-pair list — same sort key on both paths. The only known failure modes documented in the design notes are the type-string encoding (handled: native gets its own string filter) and the Float/Double narrowing (handled: both sides narrow to Float before comparison).

- [ ] **Step 1: Add `NativeLibrary` import.**

In `app/src/main/java/com/u1/slicer/gcode/LayerToolPauseInjector.kt`, add (keep import order alphabetical within the import block):

```kotlin
import com.u1.slicer.BuildConfig
import com.u1.slicer.NativeLibrary
```

- [ ] **Step 2: Change the `injectFrom3mf` signature and compute both paths.**

Replace the current signature (line 25):

```kotlin
    fun injectFrom3mf(gcodePath: String, model3mf: File): Boolean {
        if (!model3mf.exists() || !model3mf.name.endsWith(".3mf", ignoreCase = true)) return false
```

with:

```kotlin
    fun injectFrom3mf(
        gcodePath: String,
        model3mf: File,
        plateIdx: Int,
        native: NativeLibrary?
    ): Boolean {
        if (!model3mf.exists() || !model3mf.name.endsWith(".3mf", ignoreCase = true)) return false
```

**Why nullable?** Pure-JVM unit tests for the injector run without a `System.loadLibrary` (the native `.so` only loads on Android). Constructing a real `NativeLibrary` in JVM is safe (the companion init catches `UnsatisfiedLinkError` and sets `isLoaded = false`) but any call through an `external` method would crash. Task 5 keeps the 9 pre-existing JVM tests alive by passing `plateIdx = -1, native = null` so the native path short-circuits; Task 7 then rewrites them around a `StubNative` subclass that overrides `nativeGetPlateData`. The nullable `native` parameter is the contract that lets both modes coexist without a hard dependency on Mockito or reflection.

- [ ] **Step 3: Compute both paths inside the existing `ZipFile.use` block.**

Replace the current block (lines 28-40):

```kotlin
        val pauseTargets = mutableListOf<PauseTarget>()
        var nozzleTemps: Map<Int, Int>? = null
        val pauseCommand = ZipFile(model3mf).use { zip ->
            zip.getEntry("Metadata/custom_gcode_per_layer.xml")?.let { entry ->
                val xml = zip.getInputStream(entry).bufferedReader().readText()
                pauseTargets += extractPauseTargets(xml)
            }
            zip.getEntry("Metadata/project_settings.config")?.let { entry ->
                val json = zip.getInputStream(entry).bufferedReader().readText()
                nozzleTemps = parseNozzleTemperatures(json)
                Regex(""""machine_pause_gcode"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.getOrNull(1)
            }
        } ?: "M400 U1"
```

with:

```kotlin
        val xmlTargets = mutableListOf<PauseTarget>()
        var nozzleTemps: Map<Int, Int>? = null
        val pauseCommand = ZipFile(model3mf).use { zip ->
            zip.getEntry("Metadata/custom_gcode_per_layer.xml")?.let { entry ->
                val xml = zip.getInputStream(entry).bufferedReader().readText()
                xmlTargets += extractPauseTargets(xml)
            }
            zip.getEntry("Metadata/project_settings.config")?.let { entry ->
                val json = zip.getInputStream(entry).bufferedReader().readText()
                nozzleTemps = parseNozzleTemperatures(json)
                Regex(""""machine_pause_gcode"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.getOrNull(1)
            }
        } ?: "M400 U1"

        // Native path: read customGcode from g_model via JNI. Caller is expected to still hold
        // a valid g_model — in production this runs inside the slicing coroutine immediately
        // after native.slice() returned, with no intervening clearModel. plateIdx < 0 or a
        // null NativeLibrary handle (JVM unit-test / legacy paths) short-circuits to no
        // native-path participation.
        val nativeJson = if (plateIdx >= 0 && native != null) {
            try { native.nativeGetPlateData(plateIdx) } catch (_: Throwable) { null }
        } else null
        val nativeTargetsOrNull = nativeJson?.let { extractPauseTargetsFromNativeJson(it) }

        // In debug builds, assert the two data sources agree. This is the sub-plan #3 silent-
        // G-code-corruption tripwire. If it fires on a fixture, STOP — do not paper over it.
        if (BuildConfig.DEBUG && nativeTargetsOrNull != null) {
            val xmlSorted = xmlTargets
                .distinctBy { it.topZ to it.extruderBambu }
                .sortedWith(compareBy({ it.topZ }, { it.extruderBambu }))
            val nativeSorted = nativeTargetsOrNull
                .distinctBy { it.topZ to it.extruderBambu }
                .sortedWith(compareBy({ it.topZ }, { it.extruderBambu }))
            check(xmlSorted == nativeSorted) {
                "LayerToolPauseInjector dual-path divergence: " +
                    "xml=$xmlSorted native=$nativeSorted plateIdx=$plateIdx model=${model3mf.name}"
            }
        }

        // Production: prefer native-derived targets when available, else fall back to XML.
        // Fallback is belt-and-braces — native should always return a payload when a model
        // is loaded, but STL and pre-migration fixtures may hit the null branch harmlessly.
        val pauseTargets: MutableList<PauseTarget> =
            (nativeTargetsOrNull ?: xmlTargets).toMutableList()
```

The rest of `injectFrom3mf` (from the existing `if (pauseTargets.isEmpty()) return false` at line 42 onward) is unchanged.

- [ ] **Step 4: Do NOT compile-test yet — call sites are broken.**

`SlicerViewModel.kt:2353` and `ProfileEmbedderIntegrationTest.kt:402` both still call the 2-arg signature. Tasks 4 and 5 fix each. The green build arrives after Task 5.

---

## Task 4 — Thread `plateIdx` and `native` through the production call site

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` around line 2353.

**Rationale:** `SlicerViewModel` already owns `val native: NativeLibrary` and `_currentPlateId: MutableStateFlow<Int>` (1-based, `-1` when no plate selected; see line 419). `nativeGetPlateData` takes a 0-based index. Convert `currentPlateId - 1` with a `coerceAtLeast(0)` so the no-plate fallback targets plate 0 (same default SlicerViewModel uses elsewhere for raw STL / non-Bambu paths).

- [ ] **Step 1: Update the call site.**

In `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`, lines 2347-2354 currently read:

```kotlin
                    val layerToolMetadataFile = when {
                        _threeMfInfo.value?.hasLayerToolChanges != true -> null
                        sourceModelFile?.exists() == true -> sourceModelFile
                        else -> currentModelFile
                    }
                    val injectedLayerToolPause = layerToolMetadataFile
                        ?.let { LayerToolPauseInjector.injectFrom3mf(result.gcodePath, it) }
                        ?: false
```

Change to:

```kotlin
                    val layerToolMetadataFile = when {
                        _threeMfInfo.value?.hasLayerToolChanges != true -> null
                        sourceModelFile?.exists() == true -> sourceModelFile
                        else -> currentModelFile
                    }
                    // Native nativeGetPlateData takes a 0-based plate index. _currentPlateId is
                    // 1-based (or -1 when no plate selected — treat as plate 0 for the injector
                    // fallback, matching the STL / single-plate default used elsewhere).
                    val plateIdxForInjector = (_currentPlateId.value - 1).coerceAtLeast(0)
                    val injectedLayerToolPause = layerToolMetadataFile
                        ?.let {
                            LayerToolPauseInjector.injectFrom3mf(
                                result.gcodePath,
                                it,
                                plateIdxForInjector,
                                native
                            )
                        }
                        ?: false
```

- [ ] **Step 2: Do NOT compile-test yet — ProfileEmbedderIntegrationTest still broken.** Task 5 fixes it.

---

## Task 5 — Thread `plateIdx` and `lib` through the instrumented test

**Files:**
- Modify: `app/src/androidTest/java/com/u1/slicer/slicing/ProfileEmbedderIntegrationTest.kt` around line 402.

**Rationale:** The test's `lib` field already holds a `NativeLibrary` instance (it loads a model at line 393 via `lib.loadModel(embedded.absolutePath)`). The embedded fixture is single-plate, so `plateIdx = 0` is correct. After `lib.slice(...)` returns at line 395, `g_model` is still loaded — `nativeGetPlateData(0)` returns the sliced plate's customGcode payload. The dual-path `check(...)` in the injector runs because androidTest uses `debug` build type (BuildConfig.DEBUG = true).

- [ ] **Step 1: Update the call site.**

The current block at lines 399-403 reads:

```kotlin
        // Same as app: native slice omits pause lines; LayerToolPauseInjector adds them from source 3MF metadata.
        assertTrue(
            "pause injector should run using source asset metadata",
            LayerToolPauseInjector.injectFrom3mf(result.gcodePath, sourceAsset)
        )
```

Change to:

```kotlin
        // Same as app: native slice omits pause lines; LayerToolPauseInjector adds them from source 3MF metadata.
        // Dual-path guard: injector also reads nativeGetPlateData(0) and check()s the two agree in debug builds.
        assertTrue(
            "pause injector should run using source asset metadata",
            LayerToolPauseInjector.injectFrom3mf(result.gcodePath, sourceAsset, 0, lib)
        )
```

- [ ] **Step 2: Full project compile to confirm both paths green.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ./gradlew assembleDebug assembleDebugAndroidTest --no-daemon
```

Expected: BUILD SUCCESSFUL for both main APK and androidTest APK. This is the first compile gate in the plan — Tasks 3-5's inter-file signature change is validated here. If it fails, the failure will be one of: import misplacement in Task 3, call-site mismatch in Task 4 or 5, or a missed `plateIdx` parameter.

- [ ] **Step 3: JVM unit test sanity.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ./gradlew testDebugUnitTest --no-daemon
```

Expected: BUILD SUCCESSFUL. `LayerToolPauseInjectorTest` runs 11 tests — the 9 pre-existing all still pass because their `injectFrom3mf` call uses the old 2-arg signature. **Wait — that is a test failure** unless the test call sites were also updated. Check: do any of the 9 pre-existing unit tests in `LayerToolPauseInjectorTest.kt` call `injectFrom3mf`?

Answer: yes — all 9 pre-existing tests call `LayerToolPauseInjector.injectFrom3mf(gcode.absolutePath, model)`. The 2-arg signature was removed in Task 3. Those tests must be updated, which means Task 5 includes a fixup.

- [ ] **Step 4: Fix the 9 pre-existing unit tests' call sites.**

All 9 pre-existing `@Test` methods in `app/src/test/java/com/u1/slicer/gcode/LayerToolPauseInjectorTest.kt` call `injectFrom3mf(gcodePath, model)` — the old 2-arg signature removed in Task 3. These tests are pure JVM (not instrumented), so there is no loaded `g_model`. With Task 3's nullable `native: NativeLibrary?` parameter, passing `native = null` alongside `plateIdx = -1` short-circuits the native path cleanly — the test exercises the XML path only, matching pre-Task-3 behaviour.

For each of the 9 `@Test` methods, find the line:
```kotlin
LayerToolPauseInjector.injectFrom3mf(gcode.absolutePath, model)
```
and replace with:
```kotlin
LayerToolPauseInjector.injectFrom3mf(gcode.absolutePath, model, -1, null)
```

(A sed-style replace against a single-line pattern is safe — the old 2-arg signature has no other match anywhere in the file.)

- [ ] **Step 5: Re-run JVM unit tests.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ./gradlew testDebugUnitTest --no-daemon
```

Expected: BUILD SUCCESSFUL. `LayerToolPauseInjectorTest` → 11 tests pass (9 migrated + 2 new native-JSON).

- [ ] **Step 6: Full project compile again (sanity).**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ./gradlew assembleDebug assembleDebugAndroidTest --no-daemon
```

Expected: BUILD SUCCESSFUL for both.

- [ ] **Step 7: Commit.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && git restore -- app/src/main/cpp/extern/ && git add \
  app/src/main/java/com/u1/slicer/gcode/LayerToolPauseInjector.kt \
  app/src/main/java/com/u1/slicer/SlicerViewModel.kt \
  app/src/androidTest/java/com/u1/slicer/slicing/ProfileEmbedderIntegrationTest.kt \
  app/src/test/java/com/u1/slicer/gcode/LayerToolPauseInjectorTest.kt && git status --short && git commit -m "$(cat <<'EOF'
phase1(bambu-native): wire LayerToolPauseInjector dual-path (sub-plan #3 task 3-5)

injectFrom3mf gains plateIdx: Int and native: NativeLibrary? parameters.
Reads customGcode from nativeGetPlateData(plateIdx) in addition to the
existing XML parse; production emits native-derived targets when the
native handle is present. In debug builds, the two paths are compared
with check() — divergence fails loudly.

The zip re-open for machine_pause_gcode and nozzle_temperature is
retained; those two fields are not present in any native payload.

Call sites updated: SlicerViewModel (production, plate = currentPlateId - 1
coerced to >= 0), ProfileEmbedderIntegrationTest (plate = 0, lib handle).
Pre-existing JVM unit tests updated to pass plateIdx = -1, native = null —
they exercise the XML path only and have no loaded g_model.

Native .so unchanged — nativeGetPlateData already shipped post-sub-plan #2.
EOF
)"
```

---

## Task 6 — Run the instrumented integration test on device

**Files:** none modified.

**Rationale:** This is the primary regression gate for the dual-path migration. `flippyFlappyMini_fullPipeline_emitsLayerChangePauseGcode` embeds, slices, and injects against a real `flippy+flappy+mini.3mf` fixture. Under the debug build's `BuildConfig.DEBUG = true`, the `check(xmlSorted == nativeSorted)` runs. Any divergence throws `IllegalStateException` with the diff included — the test will fail loudly with actionable info.

- [ ] **Step 1: Uninstall prior APKs to dodge the Pixel 8a's phantom `versionCode 257`.**

```bash
adb -s 43211JEKB16931 uninstall com.u1.slicer.orca; adb -s 43211JEKB16931 uninstall com.u1.slicer.orca.test
```

Expected: either `Success` or `Failure [DELETE_FAILED_INTERNAL_ERROR]`/`Unknown package` for each (the `;` separator tolerates non-existent packages).

- [ ] **Step 2: Install both APKs.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && adb -s 43211JEKB16931 install -r -d app/build/outputs/apk/debug/app-debug.apk && adb -s 43211JEKB16931 install -r -d app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Expected: both return `Success`.

- [ ] **Step 3: Run the target test.**

```bash
adb -s 43211JEKB16931 shell am instrument -w -r \
  -e class com.u1.slicer.slicing.ProfileEmbedderIntegrationTest#flippyFlappyMini_fullPipeline_emitsLayerChangePauseGcode \
  com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: `OK (1 test)`. If it fails with `IllegalStateException: LayerToolPauseInjector dual-path divergence: ...`, **the dual-path caught a real bug.** Follow the abort protocol (Hard Abort Criterion A):
1. Capture the full error message (xml=... native=...).
2. **Attempt 1:** check whether it is a topZ rounding difference (Float vs Double narrowing — if native emits `1.6000000000000001` the Float narrowing should match XML's `1.6f`, but check).
3. **Attempt 2:** check whether it is an extruder encoding difference (`custom_gcode_type_name` in C++ returns exactly `"ColorChange"`, `"ToolChange"`; re-verify).
4. **Attempt 3:** check whether the fixture has multi-plate custom gcode and XML picks up all plates while native picks up plate 0 only (design notes Risk 4). If so, `plateIdx = 0` is wrong for multi-plate fixtures but right for this single-plate fixture — the XML path is the incorrect one.
5. After three attempts: abort per the operator brief. WIP-commit + escalate.

- [ ] **Step 4: Run the full `ProfileEmbedderIntegrationTest` class.**

```bash
adb -s 43211JEKB16931 shell am instrument -w -r \
  -e class com.u1.slicer.slicing.ProfileEmbedderIntegrationTest \
  com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: `OK (13 tests)`.

- [ ] **Step 5: Run the full gcode instrumented package (sanity).**

```bash
adb -s 43211JEKB16931 shell am instrument -w -r \
  -e package com.u1.slicer.gcode \
  com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: `OK (8 tests)` — just `GcodeThumbnailInjectorTest`. LayerToolPauseInjector has no instrumented tests in the `gcode/` androidTest package; its instrumented coverage lives in `ProfileEmbedderIntegrationTest` under `slicing/`.

- [ ] **Step 6: Run slicing package (flippy + bambu pipeline regression guard).**

```bash
adb -s 43211JEKB16931 shell am instrument -w -r \
  -e package com.u1.slicer.slicing \
  com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: `OK (93 tests)` — sum of SlicingIntegrationTest (39) + BambuPipelineIntegrationTest (34) + SemmSlicingTest (5) + SensoryTwistSupportsTest (1) + GoatDedupeSemmTest (1) + ProfileEmbedderIntegrationTest (13). If any test other than `flippyFlappyMini_fullPipeline_emitsLayerChangePauseGcode` fails, stop — Task 3's changes likely broke the slice pipeline for an unrelated path.

- [ ] **Step 7: No new commit — this task is pure verification. The code is already committed from Task 5.**

---

## Task 7 — Remove the XML parse from `injectFrom3mf` (native path only)

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/gcode/LayerToolPauseInjector.kt`

**Rationale:** Once the dual-path comparison has been green against the real flippy fixture (Task 6), the XML path inside the injector has served its purpose. Native becomes the sole production source. The zip re-open for `machine_pause_gcode`/`nozzle_temperature` stays — those are separate scope. `LayerToolCustomGcodeXml.kt` itself stays — `ThreeMfParser.kt:245/248/251/511` and viewer tests still call it.

The `extractPauseTargets(xml: String)` helper in the injector (line 135) and the `import com.u1.slicer.bambu.parseLayerToolSegments` (line 3) become unused. Remove both.

The `check(...)` dual-path assertion goes away too — only the native path runs now. Previously the divergence check was the safety net; it was built into the dual-path architecture, and is removed along with the XML path it was guarding against.

- [ ] **Step 1: Edit the injector.**

In `app/src/main/java/com/u1/slicer/gcode/LayerToolPauseInjector.kt`:

Remove the import at line 3:
```kotlin
import com.u1.slicer.bambu.parseLayerToolSegments
```
(No replacement — just delete this line.)

Remove the helper (formerly line 135-136):
```kotlin
    private fun extractPauseTargets(xml: String): List<PauseTarget> =
        parseLayerToolSegments(xml).map { PauseTarget(it.topZ, it.extruderBambu) }
```
(No replacement — just delete.)

Inside `injectFrom3mf`, replace the dual-path block from Task 3 Step 3. Current state (after Task 5 fixups):

```kotlin
        val xmlTargets = mutableListOf<PauseTarget>()
        var nozzleTemps: Map<Int, Int>? = null
        val pauseCommand = ZipFile(model3mf).use { zip ->
            zip.getEntry("Metadata/custom_gcode_per_layer.xml")?.let { entry ->
                val xml = zip.getInputStream(entry).bufferedReader().readText()
                xmlTargets += extractPauseTargets(xml)
            }
            zip.getEntry("Metadata/project_settings.config")?.let { entry ->
                val json = zip.getInputStream(entry).bufferedReader().readText()
                nozzleTemps = parseNozzleTemperatures(json)
                Regex(""""machine_pause_gcode"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.getOrNull(1)
            }
        } ?: "M400 U1"

        // Native path: read customGcode from g_model via JNI. …
        val nativeJson = if (plateIdx >= 0 && native != null) {
            try { native.nativeGetPlateData(plateIdx) } catch (_: Throwable) { null }
        } else null
        val nativeTargetsOrNull = nativeJson?.let { extractPauseTargetsFromNativeJson(it) }

        if (BuildConfig.DEBUG && nativeTargetsOrNull != null) {
            val xmlSorted = xmlTargets
                .distinctBy { it.topZ to it.extruderBambu }
                .sortedWith(compareBy({ it.topZ }, { it.extruderBambu }))
            val nativeSorted = nativeTargetsOrNull
                .distinctBy { it.topZ to it.extruderBambu }
                .sortedWith(compareBy({ it.topZ }, { it.extruderBambu }))
            check(xmlSorted == nativeSorted) {
                "LayerToolPauseInjector dual-path divergence: " +
                    "xml=$xmlSorted native=$nativeSorted plateIdx=$plateIdx model=${model3mf.name}"
            }
        }

        val pauseTargets: MutableList<PauseTarget> =
            (nativeTargetsOrNull ?: xmlTargets).toMutableList()
```

Replace with:

```kotlin
        var nozzleTemps: Map<Int, Int>? = null
        val pauseCommand = ZipFile(model3mf).use { zip ->
            zip.getEntry("Metadata/project_settings.config")?.let { entry ->
                val json = zip.getInputStream(entry).bufferedReader().readText()
                nozzleTemps = parseNozzleTemperatures(json)
                Regex(""""machine_pause_gcode"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.getOrNull(1)
            }
        } ?: "M400 U1"

        // Native path: read customGcode from g_model via JNI. Caller is expected to still hold
        // a valid g_model — in production this runs inside the slicing coroutine immediately
        // after native.slice() returned, with no intervening clearModel. plateIdx < 0 or a
        // null NativeLibrary handle (legacy unit-test paths) short-circuits to no injection.
        val nativeJson = if (plateIdx >= 0 && native != null) {
            try { native.nativeGetPlateData(plateIdx) } catch (_: Throwable) { null }
        } else null
        val pauseTargets: MutableList<PauseTarget> =
            nativeJson?.let { extractPauseTargetsFromNativeJson(it) }.orEmpty().toMutableList()
```

Also remove the `BuildConfig` import if it has no other uses in the file:

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && grep -n "BuildConfig" app/src/main/java/com/u1/slicer/gcode/LayerToolPauseInjector.kt
```

If the only remaining reference is the `import com.u1.slicer.BuildConfig` line itself, remove that import.

- [ ] **Step 2: Update the 9 pre-existing JVM unit tests.**

The 9 tests now pass `plateIdx = -1, native = null` — which post-Task 7 means they always short-circuit to `pauseTargets = emptyList()` and `injectFrom3mf` returns `false` early. That breaks all 9 assertions that expect injection to succeed. The old XML-only tests are no longer reachable without a native handle.

**Two paths forward:**

- (A) **Rewrite each test to use the native path.** Construct a mock `NativeLibrary` whose `nativeGetPlateData(0)` returns a hand-crafted JSON payload instead of reading `custom_gcode_per_layer.xml` from a zip. Preserves test coverage of: pause insertion logic, nozzle_temperature lookup, nil-preserving temp array, SM_PRINT_START_LINE fallback, last-seen M109 fallback, CP TOOLCHANGE skip, current-tool tracking, M109 without T.
- (B) **Delete the 9 tests; keep only the 2 new native-JSON tests + the integration test.** Simpler but loses JVM-level coverage of the 7 non-data-source behaviors (pause placement, temp resolution, double-injection guard).

**Pick (A).** The pause placement and temp resolution behaviors are first-class invariants; losing their JVM coverage would push bug detection onto the instrumented test and on-device runs (which are slow and phantom-versionCode-prone). Mocking `NativeLibrary` is a one-time cost.

Check: does the project already use Mockito?

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && grep -rn "mockito" app/build.gradle
```

If `testImplementation 'org.mockito:mockito-core:...'` is present, proceed with `mock()`. Otherwise, hand-roll: `NativeLibrary` is an `open class` (verify by reading line 1 of NativeLibrary.kt). Subclass it with only `nativeGetPlateData` overridden:

Verify openness:

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && head -20 app/src/main/java/com/u1/slicer/NativeLibrary.kt
```

If `class NativeLibrary` is not `open`, either add `open` to the declaration (one-word change, within scope since `NativeLibrary.kt` has no slicing logic) OR use Mockito `mock(NativeLibrary::class.java)`. If Mockito isn't available, adding `open` is cleanest. Add a note to the commit message.

For each of the 9 JVM tests, the pattern is: build a small JSON payload mirroring what `nativeGetPlateData(0)` would return for the fixture's XML, then pass a fake `NativeLibrary` that returns it.

Add a helper at the top of the test class body (after the `class LayerToolPauseInjectorTest {` line):

```kotlin
    /** Minimal NativeLibrary stub that returns a fixed plate JSON, mirroring nativeGetPlateData(0). */
    private class StubNative(private val plateJson: String?) : NativeLibrary() {
        override fun nativeGetPlateData(plateIndex: Int): String? =
            if (plateIndex == 0) plateJson else null
    }

    private fun plateJsonForColorChange(topZ: Double, extruder: Int, color: String = "#F4D976"): String =
        """{"customGcode":[{"printZ":$topZ,"type":"ColorChange","extruder":$extruder,"color":"$color"}]}"""

    private fun plateJsonForToolChange(topZ: Double, extruder: Int, color: String = "#F4D976"): String =
        """{"customGcode":[{"printZ":$topZ,"type":"ToolChange","extruder":$extruder,"color":"$color"}]}"""
```

If `NativeLibrary` is not `open`, add `open` to its class declaration (`class NativeLibrary` → `open class NativeLibrary`). This is an acceptable scope expansion since we need test seams.

And import at the top of the test file:

```kotlin
import com.u1.slicer.NativeLibrary
```

Then rewrite each of the 9 tests' `injectFrom3mf` call. Pattern: replace the `custom_gcode_per_layer.xml` zip entry with a StubNative matching the fixture. The `project_settings.config` zip entry stays — the injector still reads `machine_pause_gcode`/`nozzle_temperature` from it.

Test 1 — `injectFrom3mf inserts pause before first layer above target top_z`:
- XML asserts `top_z="1.6" type="1" extruder="2"`.
- JSON equivalent: `plateJsonForColorChange(1.6, 2)`.
- Call becomes: `LayerToolPauseInjector.injectFrom3mf(gcode.absolutePath, model, 0, StubNative(plateJsonForColorChange(1.6, 2)))`.
- Keep the `custom_gcode_per_layer.xml` zip entry **empty** or omit it entirely. The injector no longer reads it. Prefer omitting.

Test 2 — `injectFrom3mf preserves nozzle_temperature array indexes with nil entries`:
- Same XML (top_z 1.6, type 1, extruder 2).
- `StubNative(plateJsonForColorChange(1.6, 2))`.

Test 3 — `injectFrom3mf does nothing when no custom layer metadata exists`:
- XML omits `custom_gcode_per_layer.xml` entirely.
- JSON equivalent: `StubNative("""{"customGcode":[]}""")` (or `StubNative(null)`).
- Assert `assertFalse(injectFrom3mf(...))` as before.

Test 4 — `injectFrom3mf falls back to default pause command when source has no project settings`:
- XML has `top_z="0.4" type="2" extruder="2"`.
- JSON: `plateJsonForToolChange(0.4, 2)`.

Test 5 — `injectFrom3mf uses gcode temp fallback when project settings missing`:
- XML: `top_z="1.6" type="1" extruder="3"`.
- JSON: `plateJsonForColorChange(1.6, 3)`.

Test 6 — `injectFrom3mf uses current tool for M109 without T when project settings missing`:
- XML: `top_z="1.6" type="1" extruder="3"`.
- JSON: `plateJsonForColorChange(1.6, 3)`.

Test 7 — `injectFrom3mf falls back to SM_PRINT_START_LINE target temp`:
- XML: `top_z="1.6" type="1" extruder="3"`.
- JSON: `plateJsonForColorChange(1.6, 3)`.

Test 8 — `injectFrom3mf falls back to last seen M109 temp when tool-specific missing`:
- XML: `top_z="1.6" type="1" extruder="2"`.
- JSON: `plateJsonForColorChange(1.6, 2)`.

Test 9 — `injectFrom3mf skips when native CP toolchange workflow already exists`:
- XML: `top_z="1.6" type="1" extruder="2"`.
- JSON: `plateJsonForColorChange(1.6, 2)`.
- Assert `assertFalse(injectFrom3mf(...))` as before — the CP TOOLCHANGE marker still skips injection.

After rewriting the 9 tests, the `custom_gcode_per_layer.xml` zip entries in each test can remain (harmless — injector ignores them) or be removed. Prefer removing them to make it obvious the tests exercise the native path only.

- [ ] **Step 3: Run the full `LayerToolPauseInjectorTest` class.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ./gradlew testDebugUnitTest --no-daemon --tests "com.u1.slicer.gcode.LayerToolPauseInjectorTest"
```

Expected: BUILD SUCCESSFUL, 11 tests pass (9 rewritten + 2 new).

- [ ] **Step 4: Rebuild both APKs — main code changed, androidTest must be re-packaged.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ./gradlew assembleDebug assembleDebugAndroidTest --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Re-install and re-run the integration test — no divergence assertion this time (XML path is gone).**

```bash
adb -s 43211JEKB16931 uninstall com.u1.slicer.orca; adb -s 43211JEKB16931 uninstall com.u1.slicer.orca.test
```

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && adb -s 43211JEKB16931 install -r -d app/build/outputs/apk/debug/app-debug.apk && adb -s 43211JEKB16931 install -r -d app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

```bash
adb -s 43211JEKB16931 shell am instrument -w -r \
  -e class com.u1.slicer.slicing.ProfileEmbedderIntegrationTest#flippyFlappyMini_fullPipeline_emitsLayerChangePauseGcode \
  com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: `OK (1 test)`. If it fails with "layer-change sample should emit pause/color-swap G-code" or "extruder 2 must emit T1" — the native path is missing data the XML path was providing. Follow Hard Abort Criterion B.

- [ ] **Step 6: Full ProfileEmbedderIntegrationTest + slicing package regression.**

```bash
adb -s 43211JEKB16931 shell am instrument -w -r \
  -e class com.u1.slicer.slicing.ProfileEmbedderIntegrationTest \
  com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: `OK (13 tests)`.

- [ ] **Step 7: Commit.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && git restore -- app/src/main/cpp/extern/ && git add \
  app/src/main/java/com/u1/slicer/gcode/LayerToolPauseInjector.kt \
  app/src/main/java/com/u1/slicer/NativeLibrary.kt \
  app/src/test/java/com/u1/slicer/gcode/LayerToolPauseInjectorTest.kt && git status --short && git commit -m "$(cat <<'EOF'
phase1(bambu-native): remove XML path from LayerToolPauseInjector (sub-plan #3 task 7)

After the dual-path integration test confirmed agreement on flippy+flappy+mini
(task 6), the XML parse inside injectFrom3mf is removed and the
parseLayerToolSegments import is dropped. Native nativeGetPlateData is now
the sole production source for the customGcode row list. The zip re-open
retained for machine_pause_gcode / nozzle_temperature only.

9 pre-existing JVM unit tests rewritten around a StubNative overriding
nativeGetPlateData; NativeLibrary class opened for test subclassing.
11 tests total now pass (9 rewritten + 2 new native-JSON coverage from task 2).

LayerToolCustomGcodeXml.kt stays — ThreeMfParser.kt, KotlinBambuSnapshot
KDoc, and viewer recolorByZBands tests still reference its three
functions; deletion is out of scope for this sub-plan.
EOF
)"
```

---

## Task 8 — Update CLAUDE.md test counts

**Files:**
- Modify: `CLAUDE.md` at the worktree root.

- [ ] **Step 1: Bump the headline unit-test count.**

Line 58 reads:
```
./gradlew testDebugUnitTest                        # 844 JVM unit tests
```

Wait — verify actual current count before editing. Existing pre-Task counts per the worktree's CLAUDE.md at Pre-1: line 58 says `844 JVM unit tests` (headline), and per-class listing line 76 says `### Unit tests (app/src/test/) - 810 tests across 57 classes`. Headline and per-class listing disagree (844 vs 810) — that's documentation drift from prior sub-plans, out of scope here. **Bump only the per-class listing line and the LayerToolPauseInjectorTest per-file line**, not the headline.

Current per-class listing line 76 reads:
```
### Unit tests (`app/src/test/`) - 810 tests across 57 classes
```

Change to:
```
### Unit tests (`app/src/test/`) - 812 tests across 57 classes
```

And the per-file line (search for `gcode/LayerToolPauseInjectorTest.kt`):
```
- `gcode/LayerToolPauseInjectorTest.kt` (9) — PAUSE_PRINT injection for layer-tool colour swaps
```

Change to:
```
- `gcode/LayerToolPauseInjectorTest.kt` (11) — PAUSE_PRINT injection for layer-tool colour swaps (rewritten to exercise native nativeGetPlateData path; includes 2 direct unit tests for extractPauseTargetsFromNativeJson)
```

- [ ] **Step 2: Instrumented test count unchanged.** Task 6 did not add tests; it reused `flippyFlappyMini_fullPipeline_emitsLayerChangePauseGcode` which already exists in the `205 tests across 19 classes` count.

- [ ] **Step 3: Commit.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && git restore -- app/src/main/cpp/extern/ && git add CLAUDE.md && git commit -m "$(cat <<'EOF'
docs(CLAUDE.md): LayerToolPauseInjectorTest now 11 tests (sub-plan #3 task 8)

Unit count 810 → 812: +2 for the native-JSON direct tests from task 2.
The 9 pre-existing tests were rewritten in-place in task 7, not added.
Headline/per-class drift (844 vs 810) is pre-existing and out of scope.
EOF
)"
```

---

## Task 9 — Full-suite regression sweep + status appendix

**Files:**
- Modify: `MORNING_STATUS.md` at the worktree root (append-only).

- [ ] **Step 1: JVM unit sweep.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && ./gradlew testDebugUnitTest --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Bambu instrumented package.**

```bash
adb -s 43211JEKB16931 shell am instrument -w -r \
  -e package com.u1.slicer.bambu \
  com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: `OK (N tests)` where N matches post-sub-plan-#4 state (26, per MORNING_STATUS). No regressions.

- [ ] **Step 3: gcode instrumented package.**

```bash
adb -s 43211JEKB16931 shell am instrument -w -r \
  -e package com.u1.slicer.gcode \
  com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: `OK (8 tests)`.

- [ ] **Step 4: slicing package (primary regression guard).**

```bash
adb -s 43211JEKB16931 shell am instrument -w -r \
  -e package com.u1.slicer.slicing \
  com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: `OK (93 tests)`.

- [ ] **Step 5: Native correctness + plate data + object extruder (ensure no native regressions).**

```bash
adb -s 43211JEKB16931 shell am instrument -w -r \
  -e package com.u1.slicer.native \
  com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: `OK (20 tests)` (6 symbol + 12 correctness + 5 plate-data + 3 object-extruder — cross-check against CLAUDE.md line 101+).

Actually the package line-up per CLAUDE.md: NativeLibrarySymbolTest(6) + NativeLibraryCorrectnessTest(12) + NativePlateDataTest(5) + NativeObjectExtruderMapTest(3) = 26. Expected: `OK (26 tests)`.

- [ ] **Step 6: BambuParserDifferentialTest (diff harness).**

```bash
adb -s 43211JEKB16931 shell am instrument -w -r \
  -e class com.u1.slicer.bambu.BambuParserDifferentialTest \
  com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: `OK (21 tests)`. Diff harness baseline stays at 0 entries — this sub-plan did not touch any snapshot field.

- [ ] **Step 7: Append "Follow-up landed" paragraph to MORNING_STATUS.md.**

Open `MORNING_STATUS.md` and append the following block at the bottom of the file (after the existing "Follow-up landed (2026-04-24)" section and its trailing content). Match the format of the ThreeMfMeshParser follow-up already there:

```markdown

## Follow-up landed (2026-04-24) — LayerToolPauseInjector sub-plan #3

User dispatched sub-plan #3 as the morning's first task. Executed per `docs/superpowers/plans/2026-04-24-phase1-layer-tool-pause-injector.md`. Option B (dual-path) with debug-build `check()` divergence assertion ran clean on `flippy+flappy+mini.3mf` — no mismatch between XML and native customGcode rows.

| Commit | Task | Summary |
|---|---|---|
| `<hash>` | plan | `docs(phase1)`: executable plan committed ahead of execution |
| `<hash>` | 2 | `phase1(bambu-native)`: native-JSON extractPauseTargetsFromNativeJson + 2 JVM unit tests |
| `<hash>` | 3-5 | `phase1(bambu-native)`: dual-path wiring with check() in debug; signature +plateIdx +native |
| `<hash>` | 7 | `phase1(bambu-native)`: XML path removed; 9 JVM tests rewritten around StubNative (NativeLibrary opened for test subclassing) |
| `<hash>` | 8 | `docs(CLAUDE.md)`: unit count 810 → 812; LayerToolPauseInjectorTest (11) |

**Regression sweep** (Pixel 8a `43211JEKB16931`, post-Task 8):
- `./gradlew testDebugUnitTest` → BUILD SUCCESSFUL
- bambu package → OK (26 tests)
- gcode package → OK (8 tests)
- slicing package → OK (93 tests) — includes the 13-test `ProfileEmbedderIntegrationTest` with the dual-path-proven `flippyFlappyMini_fullPipeline_emitsLayerChangePauseGcode`
- native package → OK (26 tests)
- `BambuParserDifferentialTest` → OK (21 tests) — diff harness still at 0 baseline

**Scope check:**
- ✅ No native rebuild. `nativeGetPlateData` shipped post-sub-plan #2.
- ✅ No touches to `app/src/main/cpp/extern/` (git restore before every commit).
- ✅ `LayerToolCustomGcodeXml.kt` intact — `ThreeMfParser.kt`, viewer Z-band tests, KotlinBambuSnapshot KDoc still reference its functions. Scope firewall held.
- ✅ `BambuSanitizer.extractPlate`, `SlicerViewModel.mergeThreeMfInfoForPlate`, `ThreeMfParser.parseLayerToolSegments` — untouched.
- ✅ `NativeLibrary` gained `open` modifier for test subclassing — one-word change.
- ✅ No pushes, no releases, no rebases, no resets.

Branch HEAD is `<hash>`. N commits net on top of prior `4cee7b6`. Phase 1 cleanup pass now fully complete: customGcode data path is native-end-to-end at slice time.
```

Fill in the commit hashes before committing this file. `git log --oneline f634c47..HEAD` gives the list.

- [ ] **Step 8: Commit the status appendix.**

Note: `MORNING_STATUS.md` was untracked (`?? MORNING_STATUS.md` per Pre-1). Leave it untracked — do not `git add` it. **Skip this commit step** unless the user has explicitly added MORNING_STATUS.md to version control. The operator brief says "append a 'Follow-up landed' paragraph to MORNING_STATUS.md" — that's a local annotation to a local doc, which matches the way the ThreeMfMeshParser follow-up was added.

Verify the appendix is in place:

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && tail -60 MORNING_STATUS.md
```

Expected: the appended block is visible at the bottom.

- [ ] **Step 9: Final working-tree check.**

```bash
WT=/c/Users/kevin/projects/u1-slicer-orca/.worktrees/refactor-bambu-via-native && cd "$WT" && git status --short && git log --oneline f634c47..HEAD
```

Expected: only `?? MORNING_STATUS.md` in status. Git log shows the 5 new commits (plan + task 2 + tasks 3-5 + task 7 + task 8) on top of `f634c47`.

---

## Hard Abort Criteria (apply during every task)

A. **Dual-path divergence fires during Task 6 or Task 9 integration run** and three fix attempts have failed. Commit WIP on branch `wip/layer-tool-pause-injector-migration`. Append to `MORNING_STATUS.md` which field diverged, the attempted fixes, and the recommended next action.

B. **`flippyFlappyMini_fullPipeline_emitsLayerChangePauseGcode` fails after XML removal (Task 7 step 5)** and three fix attempts have failed. Same WIP-commit + escalation protocol.

C. **Pixel 8a install cycle fails twice in a row.** Try once to restart adb (`adb kill-server; adb start-server`); if that doesn't unstick it, commit WIP and escalate.

D. **Any test outside `gcode/` or `slicing/` packages regresses** — likely indicates an out-of-scope edit. Stop, `git diff` to find the stray change, revert it, continue.

**WIP commit template:**

```bash
git checkout -b wip/layer-tool-pause-injector-migration
git restore -- app/src/main/cpp/extern/
git add -u
git commit -m "wip: sub-plan #3 LayerToolPauseInjector migration (aborted)

<hash> was the last green commit. Aborted at task <N> step <M> after
<divergence description / test failure / install failure>. Next action:
<recommendation>."
```
