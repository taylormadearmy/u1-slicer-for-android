# Bambu Differential Test Harness — Phase 0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** For every Bambu `.3mf` in our test corpus, produce a snapshot of key facts via the **Kotlin parsing path** AND the **C++ native loader path**, and assert agreement (or document disagreement). This is the regression net for Phase 1's deletion of redundant Kotlin parsers.

**Architecture:** Two parallel parsers feed a shared `BambuFileSnapshot` data class. A `BambuSnapshotDiff` produces a structured diff. An instrumented test (`BambuParserDifferentialTest`) iterates the corpus and asserts agreement against a `known-disagreements.json` baseline. New JNI function `nativeDumpBambuModel(path) → JSON` walks `g_model` after `Model::read_from_file`. No production code paths change.

**Tech Stack:** Kotlin 1.9.22, JNI / C++17, Android Test Orchestrator, NDK 26 (Clang 17, Release build). New native source file `app/src/main/cpp/src/sapil_bambu_snapshot.cpp`. New Kotlin package `com.u1.slicer.bambu.snapshot`.

**Background context:** see [`docs/architecture/2026-04-23-bambu-via-native-loader.md`](../../architecture/2026-04-23-bambu-via-native-loader.md). The chosen direction is "Phase 0 (this plan) + Phase 1 (per-subsystem JNI accessors and Kotlin deletion, separate plans)".

---

## File structure

**New files:**
- `app/src/main/java/com/u1/slicer/bambu/snapshot/BambuFileSnapshot.kt` — pure data classes + JSON ser/de
- `app/src/main/java/com/u1/slicer/bambu/snapshot/KotlinBambuSnapshot.kt` — Kotlin path: composes existing parsers
- `app/src/main/java/com/u1/slicer/bambu/snapshot/NativeBambuSnapshot.kt` — Native path: parses JSON from JNI
- `app/src/main/java/com/u1/slicer/bambu/snapshot/BambuSnapshotDiff.kt` — per-field diff with paths
- `app/src/main/cpp/src/sapil_bambu_snapshot.cpp` — walks `g_model`, emits JSON
- `app/src/main/cpp/src/sapil_bambu_snapshot.h` — internal header
- `app/src/test/java/com/u1/slicer/bambu/snapshot/BambuFileSnapshotTest.kt` — JSON round-trip
- `app/src/test/java/com/u1/slicer/bambu/snapshot/BambuSnapshotDiffTest.kt` — diff unit tests
- `app/src/test/java/com/u1/slicer/bambu/snapshot/NativeBambuSnapshotTest.kt` — JSON parse tests (no native dep)
- `app/src/androidTest/java/com/u1/slicer/bambu/snapshot/NativeDumpSmokeTest.kt` — minimal native smoke
- `app/src/androidTest/java/com/u1/slicer/bambu/snapshot/BambuParserDifferentialTest.kt` — corpus runner
- `app/src/androidTest/assets/diagnostics/known-disagreements.json` — baseline (starts empty)

**Modified files:**
- `app/src/main/java/com/u1/slicer/NativeLibrary.kt` — add `external fun nativeDumpBambuModel(path: String): String?`
- `app/src/main/cpp/include/sapil.h` — declare `std::string sapil::bambu_snapshot_json()`
- `app/src/main/cpp/src/slicer_wrapper.cpp` — JNI entry `Java_..._nativeDumpBambuModel`
- `app/src/main/cpp/CMakeLists.txt` — add `sapil_bambu_snapshot.cpp` to sources

**Native rebuilds required:** 4 (after Tasks 4, 5, 6, 7). Each ~10-30 min per CLAUDE.md. Batch other code edits between rebuilds.

**No production code paths change.** All new code is additive, gated behind tests. No feature flag yet — Phase 1 will add `useNativeBambuLoader` when we start replacing parsers.

---

## Task 1: Snapshot data model + JSON ser/de

**Files:**
- Create: `app/src/main/java/com/u1/slicer/bambu/snapshot/BambuFileSnapshot.kt`
- Test: `app/src/test/java/com/u1/slicer/bambu/snapshot/BambuFileSnapshotTest.kt`

The shared data class is the contract between the Kotlin and Native paths. Keep it boring — no interpretation, no derived fields, just the raw facts both parsers should be able to produce.

- [ ] **Step 1.1: Write the failing JSON round-trip test**

```kotlin
// app/src/test/java/com/u1/slicer/bambu/snapshot/BambuFileSnapshotTest.kt
package com.u1.slicer.bambu.snapshot

import org.junit.Test
import org.junit.Assert.assertEquals

class BambuFileSnapshotTest {
    @Test
    fun `round-trips through JSON`() {
        val snapshot = BambuFileSnapshot(
            source = "fixture.3mf",
            isBbl = true,
            fileVersion = "1.9.0",
            plates = listOf(
                PlateSnapshot(
                    plateIndex = 1,
                    filamentColours = listOf("#FF0000", "#00FF00"),
                    filamentSettingsIds = listOf("Bambu PLA Basic", "Bambu PLA Basic"),
                    objectInstanceMap = listOf(ObjectInstance(objectId = 5, instanceId = 0)),
                    customGcode = listOf(
                        CustomGcodeEntry(printZ = 1.2, type = "ToolChange", extruder = 2, color = "#00FF00")
                    ),
                    plateConfig = mapOf("bed_type" to "Cool Plate")
                )
            ),
            objects = listOf(
                ObjectSnapshot(objectId = 5, name = "body", extruder = 1, sourcePath = "")
            ),
            volumes = listOf(
                VolumeSnapshot(
                    objectId = 5,
                    volumeIndex = 0,
                    extruder = null,
                    paintStateSet = mapOf(1 to 240, 2 to 96),
                    paintSupportsStateSet = emptyMap(),
                    isMmPainted = true,
                    isSeamPainted = false
                )
            )
        )
        val json = BambuFileSnapshotJson.encode(snapshot)
        val decoded = BambuFileSnapshotJson.decode(json)
        assertEquals(snapshot, decoded)
    }

    @Test
    fun `decodes empty arrays as empty not null`() {
        val json = """{"source":"x","isBbl":false,"fileVersion":"","plates":[],"objects":[],"volumes":[]}"""
        val decoded = BambuFileSnapshotJson.decode(json)
        assertEquals(emptyList<PlateSnapshot>(), decoded.plates)
        assertEquals(emptyList<ObjectSnapshot>(), decoded.objects)
        assertEquals(emptyList<VolumeSnapshot>(), decoded.volumes)
    }
}
```

- [ ] **Step 1.2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.u1.slicer.bambu.snapshot.BambuFileSnapshotTest"`
Expected: FAIL with "unresolved reference: BambuFileSnapshot".

- [ ] **Step 1.3: Implement the data classes + JSON helper**

```kotlin
// app/src/main/java/com/u1/slicer/bambu/snapshot/BambuFileSnapshot.kt
package com.u1.slicer.bambu.snapshot

import org.json.JSONArray
import org.json.JSONObject

/**
 * Snapshot of the facts both the Kotlin parsers and the C++ loader should agree on.
 * Boring on purpose: no derived fields, no interpretation. Phase 0 diff harness
 * compares two of these per fixture; Phase 1 deletes Kotlin parsers whose snapshot
 * agrees with the native one.
 */
data class BambuFileSnapshot(
    val source: String,
    val isBbl: Boolean,
    val fileVersion: String,
    val plates: List<PlateSnapshot>,
    val objects: List<ObjectSnapshot>,
    val volumes: List<VolumeSnapshot>
)

data class PlateSnapshot(
    val plateIndex: Int,
    val filamentColours: List<String>,            // hex like "#FF0000", project_settings.config order
    val filamentSettingsIds: List<String>,        // parallel to filamentColours
    val objectInstanceMap: List<ObjectInstance>,  // which (object, instance) pairs print on this plate
    val customGcode: List<CustomGcodeEntry>,      // from custom_gcode_per_layer.xml for this plate
    val plateConfig: Map<String, String>          // per-plate config overrides
)

data class ObjectInstance(val objectId: Int, val instanceId: Int)

data class CustomGcodeEntry(
    val printZ: Double,
    val type: String,        // "ColorChange", "PausePrint", "ToolChange", "Template", "Custom"
    val extruder: Int,       // 1-based
    val color: String        // hex; may be ""
)

data class ObjectSnapshot(
    val objectId: Int,
    val name: String,
    val extruder: Int,       // 1-based; 0 means "unset / inherit"
    val sourcePath: String   // source file path attribute, often ""
)

data class VolumeSnapshot(
    val objectId: Int,
    val volumeIndex: Int,
    val extruder: Int?,                          // 1-based; null if unset (inherit from object)
    val paintStateSet: Map<Int, Int>,            // mmu_segmentation: state(1..32) → triangle count
    val paintSupportsStateSet: Map<Int, Int>,    // paint_supports: state(1..2) → triangle count
    val isMmPainted: Boolean,
    val isSeamPainted: Boolean
)

object BambuFileSnapshotJson {

    fun encode(snapshot: BambuFileSnapshot): String {
        val root = JSONObject()
        root.put("source", snapshot.source)
        root.put("isBbl", snapshot.isBbl)
        root.put("fileVersion", snapshot.fileVersion)
        root.put("plates", JSONArray().apply { snapshot.plates.forEach { put(encodePlate(it)) } })
        root.put("objects", JSONArray().apply { snapshot.objects.forEach { put(encodeObject(it)) } })
        root.put("volumes", JSONArray().apply { snapshot.volumes.forEach { put(encodeVolume(it)) } })
        return root.toString()
    }

    fun decode(json: String): BambuFileSnapshot {
        val root = JSONObject(json)
        return BambuFileSnapshot(
            source = root.optString("source", ""),
            isBbl = root.optBoolean("isBbl", false),
            fileVersion = root.optString("fileVersion", ""),
            plates = root.optJSONArray("plates").toList(::decodePlate),
            objects = root.optJSONArray("objects").toList(::decodeObject),
            volumes = root.optJSONArray("volumes").toList(::decodeVolume)
        )
    }

    private fun encodePlate(p: PlateSnapshot) = JSONObject().apply {
        put("plateIndex", p.plateIndex)
        put("filamentColours", JSONArray(p.filamentColours))
        put("filamentSettingsIds", JSONArray(p.filamentSettingsIds))
        put("objectInstanceMap", JSONArray().apply {
            p.objectInstanceMap.forEach { put(JSONObject().put("objectId", it.objectId).put("instanceId", it.instanceId)) }
        })
        put("customGcode", JSONArray().apply {
            p.customGcode.forEach { e ->
                put(JSONObject()
                    .put("printZ", e.printZ)
                    .put("type", e.type)
                    .put("extruder", e.extruder)
                    .put("color", e.color))
            }
        })
        put("plateConfig", JSONObject(p.plateConfig as Map<*, *>))
    }

    private fun decodePlate(o: JSONObject) = PlateSnapshot(
        plateIndex = o.optInt("plateIndex"),
        filamentColours = o.optJSONArray("filamentColours").toStringList(),
        filamentSettingsIds = o.optJSONArray("filamentSettingsIds").toStringList(),
        objectInstanceMap = o.optJSONArray("objectInstanceMap").toList { ObjectInstance(it.optInt("objectId"), it.optInt("instanceId")) },
        customGcode = o.optJSONArray("customGcode").toList {
            CustomGcodeEntry(it.optDouble("printZ"), it.optString("type"), it.optInt("extruder"), it.optString("color"))
        },
        plateConfig = o.optJSONObject("plateConfig").toStringMap()
    )

    private fun encodeObject(obj: ObjectSnapshot) = JSONObject()
        .put("objectId", obj.objectId)
        .put("name", obj.name)
        .put("extruder", obj.extruder)
        .put("sourcePath", obj.sourcePath)

    private fun decodeObject(o: JSONObject) = ObjectSnapshot(
        objectId = o.optInt("objectId"),
        name = o.optString("name"),
        extruder = o.optInt("extruder"),
        sourcePath = o.optString("sourcePath")
    )

    private fun encodeVolume(v: VolumeSnapshot) = JSONObject().apply {
        put("objectId", v.objectId)
        put("volumeIndex", v.volumeIndex)
        if (v.extruder != null) put("extruder", v.extruder) else put("extruder", JSONObject.NULL)
        put("paintStateSet", JSONObject().apply { v.paintStateSet.forEach { (k, n) -> put(k.toString(), n) } })
        put("paintSupportsStateSet", JSONObject().apply { v.paintSupportsStateSet.forEach { (k, n) -> put(k.toString(), n) } })
        put("isMmPainted", v.isMmPainted)
        put("isSeamPainted", v.isSeamPainted)
    }

    private fun decodeVolume(o: JSONObject): VolumeSnapshot {
        val ex = if (o.isNull("extruder")) null else o.optInt("extruder")
        return VolumeSnapshot(
            objectId = o.optInt("objectId"),
            volumeIndex = o.optInt("volumeIndex"),
            extruder = ex,
            paintStateSet = o.optJSONObject("paintStateSet").toIntIntMap(),
            paintSupportsStateSet = o.optJSONObject("paintSupportsStateSet").toIntIntMap(),
            isMmPainted = o.optBoolean("isMmPainted"),
            isSeamPainted = o.optBoolean("isSeamPainted")
        )
    }

    private fun <T> JSONArray?.toList(map: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).map { map(getJSONObject(it)) }
    }
    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { getString(it) }
    }
    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        val out = mutableMapOf<String, String>()
        keys().forEach { out[it] = getString(it) }
        return out
    }
    private fun JSONObject?.toIntIntMap(): Map<Int, Int> {
        if (this == null) return emptyMap()
        val out = mutableMapOf<Int, Int>()
        keys().forEach { out[it.toInt()] = getInt(it) }
        return out
    }
}
```

- [ ] **Step 1.4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.u1.slicer.bambu.snapshot.BambuFileSnapshotTest"`
Expected: PASS, both tests green.

- [ ] **Step 1.5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/bambu/snapshot/BambuFileSnapshot.kt \
        app/src/test/java/com/u1/slicer/bambu/snapshot/BambuFileSnapshotTest.kt
git commit -m "phase0(bambu-diff): add BambuFileSnapshot data model + JSON ser/de

The shared snapshot type both parser paths (Kotlin existing parsers,
C++ Model::read_from_file walk) populate. Phase 0 diff harness uses
this contract to surface drift between the two paths.

Part of refactor/bambu-via-native-loader Phase 0 — see
docs/architecture/2026-04-23-bambu-via-native-loader.md"
```

---

## Task 2: Kotlin path — compose existing parsers into a snapshot

**Files:**
- Create: `app/src/main/java/com/u1/slicer/bambu/snapshot/KotlinBambuSnapshot.kt`
- Test: `app/src/test/java/com/u1/slicer/bambu/snapshot/KotlinBambuSnapshotTest.kt`
- Read: `app/src/main/java/com/u1/slicer/bambu/ThreeMfParser.kt`, `BambuSanitizer.kt`, `LayerToolCustomGcodeXml.kt`, `viewer/ThreeMfMeshParser.kt`, and the `mergeThreeMfInfo*` functions (grep to find them).

This task wraps existing Kotlin parsing into a single `snapshot(file)` call. **Do not change the existing parsers.** If a parser doesn't expose a needed field, read its current `info` output and derive the field — leave the parser source untouched. We're documenting what the Kotlin path currently believes, not improving it.

- [ ] **Step 2.1: Read the existing parsers to understand their outputs**

Read in this order:
- `app/src/main/java/com/u1/slicer/bambu/ThreeMfParser.kt` — the entry point; produces `ThreeMfInfo`.
- `app/src/main/java/com/u1/slicer/bambu/BambuSanitizer.kt` — repair/extract pass.
- `app/src/main/java/com/u1/slicer/bambu/LayerToolCustomGcodeXml.kt` — `parseLayerToolCustomGcodeXmlPerPlate`.
- `app/src/main/java/com/u1/slicer/viewer/ThreeMfMeshParser.kt` — `parsePaintIndex`, per-triangle paint extraction.
- Grep `app/src/main/java/com/u1/slicer/` for `mergeThreeMfInfo` to find the merge orchestrator and read it.

Note where each `BambuFileSnapshot` field can be sourced. If a field has no current Kotlin source (e.g. per-volume paint state triangle counts), record `null` / empty and document why in the implementation comment.

- [ ] **Step 2.2: Write the failing test against `colored_3DBenchy (1).3mf`**

Pick `colored_3DBenchy (1).3mf` because it's a small dual-colour Bambu file in the test corpus. The exact assertions depend on what you find in Step 2.1; here is the *shape* of the test — fill in expected values from a one-off Kotlin REPL run after implementing.

```kotlin
// app/src/test/java/com/u1/slicer/bambu/snapshot/KotlinBambuSnapshotTest.kt
package com.u1.slicer.bambu.snapshot

import org.junit.Test
import org.junit.Assert.*
import java.io.File

class KotlinBambuSnapshotTest {
    @Test
    fun `snapshots colored 3DBenchy via existing Kotlin parsers`() {
        val fixture = File("../app/src/androidTest/assets/colored_3DBenchy (1).3mf")
        assertTrue("fixture missing: ${fixture.absolutePath}", fixture.exists())

        val snapshot = KotlinBambuSnapshot.snapshot(fixture)

        assertEquals("colored_3DBenchy (1).3mf", snapshot.source.substringAfterLast('/').substringAfterLast('\\'))
        assertTrue("isBbl should be true for Bambu file", snapshot.isBbl)
        assertEquals("expected 1 plate", 1, snapshot.plates.size)
        // EXPECTED VALUES TO BE FILLED IN AFTER FIRST RUN — see step 2.4.
    }
}
```

- [ ] **Step 2.3: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.u1.slicer.bambu.snapshot.KotlinBambuSnapshotTest"`
Expected: FAIL with "unresolved reference: KotlinBambuSnapshot".

- [ ] **Step 2.4: Implement `KotlinBambuSnapshot`**

```kotlin
// app/src/main/java/com/u1/slicer/bambu/snapshot/KotlinBambuSnapshot.kt
package com.u1.slicer.bambu.snapshot

import com.u1.slicer.bambu.BambuSanitizer
import com.u1.slicer.bambu.ThreeMfParser
import com.u1.slicer.bambu.parseLayerToolCustomGcodeXmlPerPlate
// ... add imports as needed for mergeThreeMfInfo, etc.
import java.io.File

/**
 * Composes the existing Kotlin Bambu parsers into a single BambuFileSnapshot.
 * Pure observation: no parser logic changes here. If a snapshot field has no
 * current Kotlin source, we leave it null/empty — the diff harness will surface
 * that gap as a known disagreement.
 *
 * Phase 0 only. Phase 1 deletes the parsers this wraps.
 */
object KotlinBambuSnapshot {

    fun snapshot(file: File): BambuFileSnapshot {
        val info = ThreeMfParser.parse(file)
            ?: return emptySnapshot(file.name)

        val plates = info.plateCount.let { count ->
            (1..count).map { plateIdx -> snapshotPlate(file, info, plateIdx) }
        }
        val objects = info.objectExtruderMap.entries.map { (objectId, ext) ->
            ObjectSnapshot(
                objectId = objectId.toIntOrNull() ?: -1,
                name = info.objectNames[objectId] ?: "",
                extruder = ext,
                sourcePath = ""
            )
        }
        // Volume-level data: ThreeMfMeshParser knows it. If that parser doesn't expose
        // per-volume paint state triangle counts today, leave volumes empty here and
        // document as a known disagreement vs the native dump.
        val volumes = emptyList<VolumeSnapshot>() // TODO: populate via ThreeMfMeshParser if accessible

        return BambuFileSnapshot(
            source = file.name,
            isBbl = info.isBbl ?: true,
            fileVersion = info.fileVersion ?: "",
            plates = plates,
            objects = objects,
            volumes = volumes
        )
    }

    private fun snapshotPlate(file: File, info: /*ThreeMfInfo*/Any, plateIndex: Int): PlateSnapshot {
        // Compose what's currently available in info. Adapt field names after reading
        // ThreeMfInfo in step 2.1.
        return PlateSnapshot(
            plateIndex = plateIndex,
            filamentColours = TODO("from project_settings.config or info"),
            filamentSettingsIds = TODO("from project_settings.config or info"),
            objectInstanceMap = TODO("from slice_info.config or info"),
            customGcode = TODO("from parseLayerToolCustomGcodeXmlPerPlate(file).get(plateIndex)"),
            plateConfig = TODO("from per-plate config if present")
        )
    }

    private fun emptySnapshot(name: String) = BambuFileSnapshot(
        source = name, isBbl = false, fileVersion = "",
        plates = emptyList(), objects = emptyList(), volumes = emptyList()
    )
}
```

> The `TODO("...")` calls above are placeholders for the engineer to replace with the actual field expressions discovered in Step 2.1. **Do NOT leave them in committed code** — replace each with the real expression before running the test.

After implementing, run the test once to capture actual snapshot values, then update the test assertions in Step 2.2 with those values. (This is acceptable only here, where we're documenting the current behaviour, not specifying new behaviour.)

- [ ] **Step 2.5: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.u1.slicer.bambu.snapshot.KotlinBambuSnapshotTest"`
Expected: PASS with the captured assertion values.

- [ ] **Step 2.6: Commit**

```bash
git add app/src/main/java/com/u1/slicer/bambu/snapshot/KotlinBambuSnapshot.kt \
        app/src/test/java/com/u1/slicer/bambu/snapshot/KotlinBambuSnapshotTest.kt
git commit -m "phase0(bambu-diff): KotlinBambuSnapshot composes existing parsers

Wraps ThreeMfParser, BambuSanitizer, LayerToolCustomGcodeXml,
ThreeMfMeshParser, and merge logic into a single snapshot(file) call
that produces a BambuFileSnapshot. Pure observation; no parser
behaviour changes. Phase 1 will delete the wrapped parsers as
each section's snapshot is verified to agree with the native loader."
```

---

## Task 3: Native-side Kotlin wrapper (parses JSON, no JNI yet)

**Files:**
- Create: `app/src/main/java/com/u1/slicer/bambu/snapshot/NativeBambuSnapshot.kt`
- Test: `app/src/test/java/com/u1/slicer/bambu/snapshot/NativeBambuSnapshotTest.kt`

We can fully unit-test the JSON-parsing Kotlin side without touching native code, by feeding it a hand-written JSON string. This catches schema mismatches before the slow native rebuild loop.

- [ ] **Step 3.1: Write the failing test with a hand-written JSON fixture**

```kotlin
// app/src/test/java/com/u1/slicer/bambu/snapshot/NativeBambuSnapshotTest.kt
package com.u1.slicer.bambu.snapshot

import org.junit.Test
import org.junit.Assert.*

class NativeBambuSnapshotTest {
    @Test
    fun `parses native JSON dump into BambuFileSnapshot`() {
        val nativeJson = """
            {
              "source": "fixture.3mf",
              "isBbl": true,
              "fileVersion": "1.9.0",
              "plates": [
                {
                  "plateIndex": 1,
                  "filamentColours": ["#FF0000", "#00FF00"],
                  "filamentSettingsIds": ["Bambu PLA Basic", "Bambu PLA Basic"],
                  "objectInstanceMap": [{"objectId": 5, "instanceId": 0}],
                  "customGcode": [{"printZ": 1.2, "type": "ToolChange", "extruder": 2, "color": "#00FF00"}],
                  "plateConfig": {"bed_type": "Cool Plate"}
                }
              ],
              "objects": [{"objectId": 5, "name": "body", "extruder": 1, "sourcePath": ""}],
              "volumes": [{
                "objectId": 5, "volumeIndex": 0, "extruder": null,
                "paintStateSet": {"1": 240, "2": 96},
                "paintSupportsStateSet": {},
                "isMmPainted": true, "isSeamPainted": false
              }]
            }
        """.trimIndent()

        val snapshot = NativeBambuSnapshot.parse(nativeJson)

        assertEquals("fixture.3mf", snapshot.source)
        assertTrue(snapshot.isBbl)
        assertEquals(1, snapshot.plates.size)
        assertEquals(listOf("#FF0000", "#00FF00"), snapshot.plates[0].filamentColours)
        assertEquals(1, snapshot.objects.size)
        assertEquals(1, snapshot.volumes.size)
        assertEquals(mapOf(1 to 240, 2 to 96), snapshot.volumes[0].paintStateSet)
        assertNull(snapshot.volumes[0].extruder)
    }

    @Test
    fun `returns empty snapshot when native call returns null`() {
        val snapshot = NativeBambuSnapshot.parseOrEmpty(null, fallbackSource = "x.3mf")
        assertEquals("x.3mf", snapshot.source)
        assertTrue(snapshot.plates.isEmpty())
    }
}
```

- [ ] **Step 3.2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.u1.slicer.bambu.snapshot.NativeBambuSnapshotTest"`
Expected: FAIL with "unresolved reference: NativeBambuSnapshot".

- [ ] **Step 3.3: Implement `NativeBambuSnapshot`**

```kotlin
// app/src/main/java/com/u1/slicer/bambu/snapshot/NativeBambuSnapshot.kt
package com.u1.slicer.bambu.snapshot

import com.u1.slicer.NativeLibrary
import java.io.File

/**
 * Parses the JSON dump produced by NativeLibrary.nativeDumpBambuModel.
 * The native side walks g_model after Model::read_from_file and emits
 * the same BambuFileSnapshot shape the Kotlin path produces, so the
 * differential harness can compare them apples-to-apples.
 */
object NativeBambuSnapshot {

    fun snapshot(file: File, native: NativeLibrary): BambuFileSnapshot {
        if (!native.loadModel(file.absolutePath)) {
            return parseOrEmpty(null, fallbackSource = file.name)
        }
        val json = native.nativeDumpBambuModel(file.absolutePath)
        return parseOrEmpty(json, fallbackSource = file.name)
    }

    fun parseOrEmpty(json: String?, fallbackSource: String): BambuFileSnapshot =
        if (json.isNullOrBlank()) {
            BambuFileSnapshot(fallbackSource, false, "", emptyList(), emptyList(), emptyList())
        } else {
            parse(json)
        }

    fun parse(json: String): BambuFileSnapshot = BambuFileSnapshotJson.decode(json)
}
```

- [ ] **Step 3.4: Add the JNI declaration to `NativeLibrary.kt` (function not yet wired native-side)**

```kotlin
// app/src/main/java/com/u1/slicer/NativeLibrary.kt — add to the class body, near getModelInfo()

    // ---- Diagnostics — Phase 0 differential harness ----
    // Returns a JSON dump of g_model after Model::read_from_file.
    // Path must be the same path passed to loadModel(); native re-loads to ensure
    // a clean snapshot independent of any prior mutations (rotation/scale/instances).
    // Returns null if the file fails to load.
    external fun nativeDumpBambuModel(path: String): String?
```

- [ ] **Step 3.5: Run the unit test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.u1.slicer.bambu.snapshot.NativeBambuSnapshotTest"`
Expected: PASS. (The JVM unit test never invokes the `external` function, so the missing native impl doesn't matter.)

- [ ] **Step 3.6: Commit**

```bash
git add app/src/main/java/com/u1/slicer/bambu/snapshot/NativeBambuSnapshot.kt \
        app/src/main/java/com/u1/slicer/NativeLibrary.kt \
        app/src/test/java/com/u1/slicer/bambu/snapshot/NativeBambuSnapshotTest.kt
git commit -m "phase0(bambu-diff): NativeBambuSnapshot + JNI declaration

Adds the Kotlin side of the native dump: parses the JSON the C++ side
will emit, with a unit test that exercises the full schema without
needing the native binding wired. Native impl follows in next commit."
```

---

## Task 4: Native dump skeleton — JNI binding + minimal C++ (file_version, isBbl, plate_count only)

**Files:**
- Create: `app/src/main/cpp/src/sapil_bambu_snapshot.cpp`
- Create: `app/src/main/cpp/src/sapil_bambu_snapshot.h`
- Modify: `app/src/main/cpp/include/sapil.h` — add public decl
- Modify: `app/src/main/cpp/src/slicer_wrapper.cpp` — add JNI entry
- Modify: `app/src/main/cpp/CMakeLists.txt` — register new source file
- Test: `app/src/androidTest/java/com/u1/slicer/bambu/snapshot/NativeDumpSmokeTest.kt`

Smallest possible end-to-end JNI wiring. Three fields only. Verifies the binding works before we invest in walking the full `g_model` tree. **Native rebuild required after this task.**

- [ ] **Step 4.1: Write the failing instrumented smoke test**

```kotlin
// app/src/androidTest/java/com/u1/slicer/bambu/snapshot/NativeDumpSmokeTest.kt
package com.u1.slicer.bambu.snapshot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeDumpSmokeTest {
    @Test
    fun `nativeDumpBambuModel returns JSON with header fields for a Bambu fixture`() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val tmp = File(ctx.cacheDir, "colored_3DBenchy.3mf")
        ctx.assets.open("colored_3DBenchy (1).3mf").use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }

        val native = NativeLibrary()
        assertTrue("loadModel must succeed", native.loadModel(tmp.absolutePath))

        val json = native.nativeDumpBambuModel(tmp.absolutePath)
        assertNotNull("nativeDumpBambuModel returned null", json)

        val root = JSONObject(json!!)
        assertEquals("colored_3DBenchy.3mf", root.getString("source"))
        assertTrue("isBbl should be true for a Bambu Studio file", root.getBoolean("isBbl"))
        assertTrue("plates array should be present", root.has("plates"))
    }
}
```

- [ ] **Step 4.2: Run the test on a connected device — verify it fails**

Run: `ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest --no-daemon -Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.bambu.snapshot.NativeDumpSmokeTest`
Expected: FAIL with `UnsatisfiedLinkError: nativeDumpBambuModel`. (If you get a different error like "asset not found", fix that first — the test must fail for the right reason.)

- [ ] **Step 4.3: Add the public decl in `sapil.h`**

Open `app/src/main/cpp/include/sapil.h`, find the `namespace sapil { ... }` block, and add:

```cpp
// Bambu differential snapshot — Phase 0 diff harness.
// Walks the global Slic3r::Model after Model::read_from_file and emits
// a BambuFileSnapshot-shaped JSON. Returns "" if g_model has no objects.
std::string bambu_snapshot_json();
```

- [ ] **Step 4.4: Create the snapshot header**

```cpp
// app/src/main/cpp/src/sapil_bambu_snapshot.h
#pragma once
#include "../include/sapil.h"
```

- [ ] **Step 4.5: Create the minimal C++ implementation**

```cpp
// app/src/main/cpp/src/sapil_bambu_snapshot.cpp
//
// Phase 0 differential harness: walks the global Slic3r::Model after
// Model::read_from_file completes, emitting a JSON shaped exactly like
// the Kotlin BambuFileSnapshot. Compared against the Kotlin parser path
// to surface drift.
//
// Initial commit only emits header fields (source, isBbl, fileVersion,
// plate_count). Subsequent commits expand to per-plate, per-object,
// per-volume sections.

#include "sapil_bambu_snapshot.h"

#include <sstream>
#include <string>

#include "libslic3r/Model.hpp"

// g_model is the global Slic3r::Model populated by sapil_model.cpp's
// loadModel(). It lives in the sapil namespace; declared extern here so
// we can read it without owning it.
namespace Slic3r { class Model; }

namespace sapil {

extern Slic3r::Model g_model;
extern std::string g_model_filename; // populated by sapil_model.cpp

namespace {

std::string json_escape(const std::string& s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (char c : s) {
        switch (c) {
            case '\\': out += "\\\\"; break;
            case '"': out += "\\\""; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:   out += c;       break;
        }
    }
    return out;
}

} // namespace

std::string bambu_snapshot_json() {
    if (g_model.objects.empty()) return "";

    std::ostringstream out;
    out << "{";
    out << "\"source\":\"" << json_escape(g_model_filename) << "\",";
    // isBbl: true if any object came from a Bambu file (best signal we have here
    // is the presence of model_settings/project_settings; refine in Task 5+).
    out << "\"isBbl\":true,";
    out << "\"fileVersion\":\"\",";
    out << "\"plates\":[],";    // Task 5
    out << "\"objects\":[],";   // Task 6
    out << "\"volumes\":[]";    // Task 7
    out << "}";
    return out.str();
}

} // namespace sapil
```

> If `g_model` and `g_model_filename` are declared `static` in `sapil_model.cpp`, they aren't `extern`-able. In that case, change them to non-static (file-scope but not `static`) in `sapil_model.cpp` and add the `extern` decl here. This is part of this task — verify before building.

- [ ] **Step 4.6: Add the JNI entry in `slicer_wrapper.cpp`**

Add to the `extern "C"` block (after the existing `getDiagnosticsState` entry, before `loadModel`):

```cpp
JNIEXPORT jstring JNICALL
Java_com_u1_slicer_NativeLibrary_nativeDumpBambuModel(JNIEnv* env, jobject, jstring jpath) {
    if (!g_engine) return nullptr;
    if (jpath != nullptr) {
        // Re-load to guarantee clean snapshot, independent of any prior
        // setModelRotation / setModelInstances mutations.
        const char* path = env->GetStringUTFChars(jpath, nullptr);
        bool ok = g_engine->loadModel(std::string(path));
        env->ReleaseStringUTFChars(jpath, path);
        if (!ok) return nullptr;
    }
    std::string json = sapil::bambu_snapshot_json();
    if (json.empty()) return nullptr;
    return env->NewStringUTF(json.c_str());
}
```

- [ ] **Step 4.7: Register the new source file in CMakeLists.txt**

Open `app/src/main/cpp/CMakeLists.txt` and find the `add_library(prusaslicer-jni ...)` call. Add `src/sapil_bambu_snapshot.cpp` to the source list.

- [ ] **Step 4.8: Rebuild the native `.so`**

Per CLAUDE.md (`Native Rebuild` section). Use the existing build directory if present:

```bash
BUILD_DIR=$(ls -d app/.cxx/Debug/*/arm64-v8a 2>/dev/null | head -1)
[ -z "$BUILD_DIR" ] && { echo "No existing build dir — use Fresh build path from CLAUDE.md"; exit 1; }

# Verify NDK 26 + Release before building
grep "ndk/26" "$BUILD_DIR/CMakeCache.txt" || { echo "Build dir is NDK<26 — fresh build needed"; exit 1; }
grep "CMAKE_BUILD_TYPE:STRING=Release" "$BUILD_DIR/CMakeCache.txt" || { echo "Build dir is not Release — fresh build needed"; exit 1; }

(cd "$BUILD_DIR" && ninja -j1)

NDK="C:/Users/kevin/AppData/Local/Android/Sdk/ndk/26.1.10909125"
STRIP=$(ls "$NDK/toolchains/llvm/prebuilt"/*/bin/llvm-strip | head -1)
"$STRIP" --strip-unneeded "$BUILD_DIR/libprusaslicer-jni.so"
cp "$BUILD_DIR/libprusaslicer-jni.so" app/src/main/jniLibs/arm64-v8a/

# Verify size + compiler
ls -lh app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so   # Expect ~19-21MB
"$NDK/toolchains/llvm/prebuilt"/*/bin/llvm-readelf -p .comment \
    app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so | head -5  # Expect "clang version 17"
```

- [ ] **Step 4.9: Run the smoke test on-device — verify it passes**

Run: `ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest --no-daemon -Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.bambu.snapshot.NativeDumpSmokeTest`
Expected: PASS. JSON contains `source`, `isBbl=true`, and an empty `plates` array.

- [ ] **Step 4.10: Commit**

```bash
git add app/src/main/cpp/src/sapil_bambu_snapshot.cpp \
        app/src/main/cpp/src/sapil_bambu_snapshot.h \
        app/src/main/cpp/src/slicer_wrapper.cpp \
        app/src/main/cpp/include/sapil.h \
        app/src/main/cpp/CMakeLists.txt \
        app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so \
        app/src/androidTest/java/com/u1/slicer/bambu/snapshot/NativeDumpSmokeTest.kt
# If sapil_model.cpp needed extern adjustments:
git add app/src/main/cpp/src/sapil_model.cpp
git commit -m "phase0(bambu-diff): native dump skeleton + JNI binding

Wires nativeDumpBambuModel(path) end-to-end: JNI entry in
slicer_wrapper.cpp → sapil::bambu_snapshot_json() walks g_model
and emits a header-only JSON for now (source, isBbl, fileVersion,
empty plates/objects/volumes). Native rebuild verified at ~20MB
stripped Release with NDK 26 / Clang 17.

Subsequent commits fill in plate, object, and volume sections."
```

---

## Task 5: Native dump — per-plate fields

**Files:**
- Modify: `app/src/main/cpp/src/sapil_bambu_snapshot.cpp`
- Test: extend `NativeDumpSmokeTest.kt` with per-plate assertions
- Read: [`OrcaSlicer bbs_3mf.cpp`](https://github.com/SoftFever/OrcaSlicer/blob/main/src/libslic3r/Format/bbs_3mf.cpp) lines 8370-8519 (slice_info writer — same fields readable via `PlateData`).

`Model::plates_custom_gcodes` (map<plate_index, `CustomGCode::Info`>) and the `PlateData` list (populated by `Model::read_from_file` via the `plate_data_list` out-param) hold everything we need. The `g_engine`/`g_model` access in `sapil_model.cpp` should already retain the plate data — verify and expose if not.

- [ ] **Step 5.1: Verify `plate_data_list` is retained by `sapil_model.cpp`**

Read `app/src/main/cpp/src/sapil_model.cpp` around the `Model::read_from_file` call (line ~135). The signature is:

```cpp
Model Model::read_from_file(input, &config, &subs, LoadStrategy, &plate_data, &project_presets, &is_bbl, &file_version, ...)
```

If the current code doesn't pass `&plate_data` and store it, modify it to: declare a `static Slic3r::PlateDataPtrs g_plate_data_list;` and pass `&g_plate_data_list` in. Same for `g_is_bbl`, `g_file_version`. These globals are read-only after load — no concurrency hazard beyond the existing `g_model`.

- [ ] **Step 5.2: Extend the smoke test to assert per-plate fields**

Append to `NativeDumpSmokeTest.kt`:

```kotlin
@Test
fun `nativeDumpBambuModel populates per-plate filament colours and custom gcode for colored 3DBenchy`() {
    val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    val tmp = File(ctx.cacheDir, "colored_3DBenchy_p5.3mf")
    ctx.assets.open("colored_3DBenchy (1).3mf").use { input ->
        tmp.outputStream().use { input.copyTo(it) }
    }
    val native = NativeLibrary()
    assertTrue(native.loadModel(tmp.absolutePath))
    val snapshot = NativeBambuSnapshot.parse(native.nativeDumpBambuModel(tmp.absolutePath)!!)

    assertEquals("expected 1 plate", 1, snapshot.plates.size)
    val plate = snapshot.plates[0]
    assertTrue("expected at least 2 filament colours", plate.filamentColours.size >= 2)
    plate.filamentColours.forEach { assertTrue("colour must be hex: $it", it.startsWith("#")) }
    // colored_3DBenchy ships custom_gcode_per_layer entries for the colour change;
    // assert at least one entry, leaving the exact value to the corpus diff.
    assertTrue("expected custom gcode entries", plate.customGcode.isNotEmpty())
}
```

- [ ] **Step 5.3: Run — expect failure (currently empty plate array)**

Run: same gradle invocation as Step 4.2 / 4.9.
Expected: FAIL: `expected 1 plate ... 0`.

- [ ] **Step 5.4: Implement plate emission**

Update `bambu_snapshot_json()` in `sapil_bambu_snapshot.cpp`:

```cpp
#include "libslic3r/Format/bbs_3mf.hpp"   // PlateData, FilamentInfo

namespace sapil {

extern Slic3r::PlateDataPtrs g_plate_data_list;
extern bool g_is_bbl;
extern Slic3r::Semver g_file_version;  // or std::string if stored as string

namespace {

std::string colour_to_hex(const std::string& raw) {
    // PlateData stores filament colours as "#RRGGBB" already; pass through with
    // defensive normalisation.
    if (raw.empty()) return "";
    if (raw[0] == '#') return raw;
    return "#" + raw;
}

void append_plate(std::ostringstream& out, const Slic3r::PlateData& p, const Slic3r::Model& model) {
    out << "{";
    out << "\"plateIndex\":" << p.plate_index << ",";

    out << "\"filamentColours\":[";
    for (size_t i = 0; i < p.slice_filaments_info.size(); ++i) {
        if (i) out << ",";
        out << "\"" << json_escape(colour_to_hex(p.slice_filaments_info[i].color)) << "\"";
    }
    out << "],";

    out << "\"filamentSettingsIds\":[";
    for (size_t i = 0; i < p.slice_filaments_info.size(); ++i) {
        if (i) out << ",";
        out << "\"" << json_escape(p.slice_filaments_info[i].filament_id) << "\"";
    }
    out << "],";

    out << "\"objectInstanceMap\":[";
    for (size_t i = 0; i < p.objects_and_instances.size(); ++i) {
        if (i) out << ",";
        const auto& oi = p.objects_and_instances[i];
        out << "{\"objectId\":" << oi.first << ",\"instanceId\":" << oi.second << "}";
    }
    out << "],";

    out << "\"customGcode\":[";
    auto it = model.plates_custom_gcodes.find(p.plate_index - 1);  // zero- vs one-based: verify
    if (it != model.plates_custom_gcodes.end()) {
        const auto& items = it->second.gcodes;
        for (size_t i = 0; i < items.size(); ++i) {
            if (i) out << ",";
            const auto& g = items[i];
            out << "{"
                << "\"printZ\":" << g.print_z << ","
                << "\"type\":\"" << json_escape(custom_gcode_type_name(g.type)) << "\","
                << "\"extruder\":" << g.extruder << ","
                << "\"color\":\"" << json_escape(g.color) << "\""
                << "}";
        }
    }
    out << "],";

    out << "\"plateConfig\":{";
    bool first = true;
    for (const std::string& key : p.config.keys()) {
        if (!first) out << ",";
        out << "\"" << json_escape(key) << "\":\"" << json_escape(p.config.opt_serialize(key)) << "\"";
        first = false;
    }
    out << "}";

    out << "}";
}

const char* custom_gcode_type_name(int type) {
    // From CustomGCode::Type enum order in libslic3r/CustomGCode.hpp
    // — verify the enum values; if they're scoped enum class, cast appropriately.
    switch (type) {
        case 0: return "ColorChange";
        case 1: return "PausePrint";
        case 2: return "ToolChange";
        case 3: return "Template";
        case 4: return "Custom";
        default: return "Unknown";
    }
}

} // namespace

// Replace the body of bambu_snapshot_json — header section unchanged, plates filled in:
std::string bambu_snapshot_json() {
    if (g_model.objects.empty()) return "";

    std::ostringstream out;
    out << "{";
    out << "\"source\":\"" << json_escape(g_model_filename) << "\",";
    out << "\"isBbl\":" << (g_is_bbl ? "true" : "false") << ",";
    out << "\"fileVersion\":\"" << json_escape(g_file_version.to_string()) << "\",";

    out << "\"plates\":[";
    for (size_t i = 0; i < g_plate_data_list.size(); ++i) {
        if (i) out << ",";
        if (g_plate_data_list[i] != nullptr) append_plate(out, *g_plate_data_list[i], g_model);
    }
    out << "],";

    out << "\"objects\":[],";   // Task 6
    out << "\"volumes\":[]";    // Task 7
    out << "}";
    return out.str();
}

} // namespace sapil
```

> Verify the include path for `PlateData` and the `CustomGCode` enum names against the actual headers in `app/src/main/cpp/include/orcaslicer/` (or wherever the libslic3r headers live in this fork). The bbs_3mf.hpp path may be `libslic3r/Format/bbs_3mf.hpp` or differ — check existing includes in `sapil_model.cpp` for the convention.

- [ ] **Step 5.5: Rebuild the native `.so`**

Same procedure as Step 4.8.

- [ ] **Step 5.6: Run the test — verify pass**

Same gradle invocation. Expected: PASS, both tests now green.

- [ ] **Step 5.7: Commit**

```bash
git add app/src/main/cpp/src/sapil_bambu_snapshot.cpp \
        app/src/main/cpp/src/sapil_model.cpp \
        app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so \
        app/src/androidTest/java/com/u1/slicer/bambu/snapshot/NativeDumpSmokeTest.kt
git commit -m "phase0(bambu-diff): native dump emits per-plate fields

filamentColours, filamentSettingsIds, objectInstanceMap, customGcode
(from Model::plates_custom_gcodes), and plateConfig now populated from
PlateData. Test verifies colored_3DBenchy plate has >=2 filament
colours and at least one custom_gcode entry."
```

---

## Task 6: Native dump — per-object fields

**Files:**
- Modify: `app/src/main/cpp/src/sapil_bambu_snapshot.cpp`
- Test: extend `NativeDumpSmokeTest.kt`

- [ ] **Step 6.1: Add a per-object assertion to the smoke test**

Append:

```kotlin
@Test
fun `nativeDumpBambuModel populates objects with extruder for colored 3DBenchy`() {
    val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    val tmp = File(ctx.cacheDir, "colored_3DBenchy_o.3mf")
    ctx.assets.open("colored_3DBenchy (1).3mf").use { input ->
        tmp.outputStream().use { input.copyTo(it) }
    }
    val native = NativeLibrary()
    assertTrue(native.loadModel(tmp.absolutePath))
    val snap = NativeBambuSnapshot.parse(native.nativeDumpBambuModel(tmp.absolutePath)!!)

    assertTrue("expected at least one object", snap.objects.isNotEmpty())
    snap.objects.forEach {
        assertTrue("extruder must be 0..N (0=inherit)", it.extruder in 0..32)
        assertTrue("name must be non-null", it.name.isNotEmpty() || it.objectId > 0)
    }
}
```

- [ ] **Step 6.2: Run — expect failure**

Expected: FAIL with `expected at least one object ... false`.

- [ ] **Step 6.3: Implement object emission**

Replace the `"objects":[],` line in `bambu_snapshot_json()` with:

```cpp
out << "\"objects\":[";
for (size_t i = 0; i < g_model.objects.size(); ++i) {
    if (i) out << ",";
    const Slic3r::ModelObject* mo = g_model.objects[i];
    int extruder_value = 0;  // 0 = inherit / unset
    if (mo->config.has("extruder")) {
        extruder_value = mo->config.opt_int("extruder");
    }
    out << "{"
        << "\"objectId\":" << static_cast<long long>(mo->id().id) << ","
        << "\"name\":\"" << json_escape(mo->name) << "\","
        << "\"extruder\":" << extruder_value << ","
        << "\"sourcePath\":\"" << json_escape(mo->input_file) << "\""
        << "}";
}
out << "],";
```

> Verify `mo->id().id` is the correct accessor — read `Model.hpp` for `ObjectID`. May be `mo->id().value` or similar. Adjust to what the type provides.

- [ ] **Step 6.4: Rebuild + run + verify pass**

Same procedure. Expected: all tests green.

- [ ] **Step 6.5: Commit**

```bash
git add app/src/main/cpp/src/sapil_bambu_snapshot.cpp \
        app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so \
        app/src/androidTest/java/com/u1/slicer/bambu/snapshot/NativeDumpSmokeTest.kt
git commit -m "phase0(bambu-diff): native dump emits per-object extruder + name"
```

---

## Task 7: Native dump — per-volume fields (paint state sets)

**Files:**
- Modify: `app/src/main/cpp/src/sapil_bambu_snapshot.cpp`
- Test: extend `NativeDumpSmokeTest.kt`
- Read: `Model.hpp` lines 727-753 (`FacetsAnnotation`, `EnforcerBlockerType`), `TriangleSelector` API for iterating per-triangle states.

This is the highest-leverage section: per-volume `mmu_segmentation` and `paint_supports` triangle counts per state. **Drives the B95-class diagnoses.**

- [ ] **Step 7.1: Add a per-volume assertion to the smoke test (use H2C benchy — has rich paint data)**

```kotlin
@Test
fun `nativeDumpBambuModel populates volume paintStateSet for H2C benchy`() {
    val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    val tmp = File(ctx.cacheDir, "h2c_benchy.3mf")
    ctx.assets.open("3DBenchy-H2C-Multi-Color.3mf").use { input ->
        tmp.outputStream().use { input.copyTo(it) }
    }
    val native = NativeLibrary()
    assertTrue(native.loadModel(tmp.absolutePath))
    val snap = NativeBambuSnapshot.parse(native.nativeDumpBambuModel(tmp.absolutePath)!!)

    assertTrue("expected at least one volume", snap.volumes.isNotEmpty())
    val mmPaintedVolumes = snap.volumes.filter { it.isMmPainted }
    assertTrue("H2C benchy should have mm-painted volumes", mmPaintedVolumes.isNotEmpty())
    val totalPaintStates = mmPaintedVolumes.flatMap { it.paintStateSet.keys }.toSet()
    assertTrue("H2C benchy known to use 7+ paint states", totalPaintStates.size >= 5)
    mmPaintedVolumes.forEach { vol ->
        vol.paintStateSet.values.forEach { count ->
            assertTrue("triangle count must be positive", count > 0)
        }
    }
}
```

- [ ] **Step 7.2: Run — expect failure**

Expected: FAIL with `expected at least one volume ... false`.

- [ ] **Step 7.3: Implement volume emission**

Add a helper that walks the painted facets and counts triangles per state:

```cpp
#include "libslic3r/TriangleSelector.hpp"

namespace sapil {
namespace {

// Count triangles per EnforcerBlockerType state for a given FacetsAnnotation.
// Returns map<state_value, triangle_count>. State 0 (NONE) is omitted.
std::map<int, int> count_paint_states(const Slic3r::ModelVolume& mv,
                                       const Slic3r::FacetsAnnotation& facets) {
    std::map<int, int> counts;
    if (!facets.has_facets(mv, Slic3r::EnforcerBlockerType::NONE)) {
        // Iterate all known state values. EnforcerBlockerType is an enum class.
        for (int state = 1; state <= 32; ++state) {
            auto type = static_cast<Slic3r::EnforcerBlockerType>(state);
            if (facets.has_facets(mv, type)) {
                Slic3r::indexed_triangle_set its = facets.get_facets_strict(mv, type);
                int n = static_cast<int>(its.indices.size());
                if (n > 0) counts[state] = n;
            }
        }
    }
    return counts;
}

} // namespace
} // namespace sapil
```

Replace the `"volumes":[]` line in `bambu_snapshot_json()`:

```cpp
out << "\"volumes\":[";
bool first_vol = true;
for (size_t oi = 0; oi < g_model.objects.size(); ++oi) {
    const Slic3r::ModelObject* mo = g_model.objects[oi];
    long long obj_id = static_cast<long long>(mo->id().id);
    for (size_t vi = 0; vi < mo->volumes.size(); ++vi) {
        if (!first_vol) out << ",";
        first_vol = false;
        const Slic3r::ModelVolume* mv = mo->volumes[vi];

        out << "{";
        out << "\"objectId\":" << obj_id << ",";
        out << "\"volumeIndex\":" << vi << ",";
        if (mv->config.has("extruder")) {
            out << "\"extruder\":" << mv->config.opt_int("extruder") << ",";
        } else {
            out << "\"extruder\":null,";
        }

        // Paint state sets
        auto mmu_counts = count_paint_states(*mv, mv->mmu_segmentation_facets);
        out << "\"paintStateSet\":{";
        bool fp = true;
        for (auto& [state, n] : mmu_counts) {
            if (!fp) out << ",";
            out << "\"" << state << "\":" << n;
            fp = false;
        }
        out << "},";

        auto sup_counts = count_paint_states(*mv, mv->supported_facets);
        out << "\"paintSupportsStateSet\":{";
        fp = true;
        for (auto& [state, n] : sup_counts) {
            if (!fp) out << ",";
            out << "\"" << state << "\":" << n;
            fp = false;
        }
        out << "},";

        out << "\"isMmPainted\":" << (mv->is_mm_painted() ? "true" : "false") << ",";
        out << "\"isSeamPainted\":" << (mv->is_seam_painted() ? "true" : "false");
        out << "}";
    }
}
out << "]";
```

> The `FacetsAnnotation::get_facets_strict` signature may differ. Read `Model.hpp:734-742` and adapt. If iterating states 1..32 by `has_facets` is too slow, find the existing iterator or use `get_facets()` (non-strict) once and bucket by state.

- [ ] **Step 7.4: Rebuild + run + verify pass**

Same procedure. Expected: all tests green.

- [ ] **Step 7.5: Commit**

```bash
git add app/src/main/cpp/src/sapil_bambu_snapshot.cpp \
        app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so \
        app/src/androidTest/java/com/u1/slicer/bambu/snapshot/NativeDumpSmokeTest.kt
git commit -m "phase0(bambu-diff): native dump emits per-volume paint state sets

Counts triangles per EnforcerBlockerType state for both
mmu_segmentation_facets and supported_facets (paint_supports).
This is the field that drives B95-class diagnoses — Kotlin's
paint-state count diverges from native here on Buzz / H2C plates."
```

---

## Task 8: BambuSnapshotDiff — structured per-field comparison

**Files:**
- Create: `app/src/main/java/com/u1/slicer/bambu/snapshot/BambuSnapshotDiff.kt`
- Test: `app/src/test/java/com/u1/slicer/bambu/snapshot/BambuSnapshotDiffTest.kt`

JVM-only. Pure function: `diff(kotlin, native): List<Disagreement>`. Each `Disagreement` carries a path (e.g. `plates[0].filamentColours[2]`), the Kotlin value, and the native value. Used by the corpus runner to either pass (no disagreements) or document them in the baseline.

- [ ] **Step 8.1: Write failing tests covering each disagreement type**

```kotlin
// app/src/test/java/com/u1/slicer/bambu/snapshot/BambuSnapshotDiffTest.kt
package com.u1.slicer.bambu.snapshot

import org.junit.Test
import org.junit.Assert.*

class BambuSnapshotDiffTest {

    private fun blank() = BambuFileSnapshot("x", true, "", emptyList(), emptyList(), emptyList())

    @Test
    fun `identical snapshots produce no disagreements`() {
        val s = blank().copy(objects = listOf(ObjectSnapshot(1, "a", 1, "")))
        val diffs = BambuSnapshotDiff.diff(s, s)
        assertTrue(diffs.isEmpty())
    }

    @Test
    fun `header field mismatch is reported with path`() {
        val k = blank().copy(isBbl = true)
        val n = blank().copy(isBbl = false)
        val diffs = BambuSnapshotDiff.diff(k, n)
        assertEquals(1, diffs.size)
        assertEquals("isBbl", diffs[0].path)
        assertEquals("true", diffs[0].kotlinValue)
        assertEquals("false", diffs[0].nativeValue)
    }

    @Test
    fun `plate count mismatch is reported once at top level`() {
        val k = blank().copy(plates = listOf(PlateSnapshot(1, emptyList(), emptyList(), emptyList(), emptyList(), emptyMap())))
        val n = blank()
        val diffs = BambuSnapshotDiff.diff(k, n)
        assertEquals("plates.size", diffs.single().path)
    }

    @Test
    fun `per-plate filament colour mismatch reports path with index`() {
        val plate = { colours: List<String> ->
            PlateSnapshot(1, colours, listOf("A", "B"), emptyList(), emptyList(), emptyMap())
        }
        val k = blank().copy(plates = listOf(plate(listOf("#FF0000", "#00FF00"))))
        val n = blank().copy(plates = listOf(plate(listOf("#FF0000", "#0000FF"))))
        val diffs = BambuSnapshotDiff.diff(k, n)
        assertEquals(1, diffs.size)
        assertEquals("plates[0].filamentColours[1]", diffs[0].path)
    }

    @Test
    fun `per-volume paint state count mismatch reports path with state key`() {
        val vol = { counts: Map<Int, Int> ->
            VolumeSnapshot(5, 0, null, counts, emptyMap(), true, false)
        }
        val k = blank().copy(volumes = listOf(vol(mapOf(1 to 100, 2 to 50))))
        val n = blank().copy(volumes = listOf(vol(mapOf(1 to 100, 2 to 60))))
        val diffs = BambuSnapshotDiff.diff(k, n)
        assertEquals("volumes[0].paintStateSet[2]", diffs.single().path)
    }
}
```

- [ ] **Step 8.2: Run — expect failure (class doesn't exist)**

Run: `./gradlew testDebugUnitTest --tests "com.u1.slicer.bambu.snapshot.BambuSnapshotDiffTest"`
Expected: FAIL with "unresolved reference: BambuSnapshotDiff".

- [ ] **Step 8.3: Implement `BambuSnapshotDiff`**

```kotlin
// app/src/main/java/com/u1/slicer/bambu/snapshot/BambuSnapshotDiff.kt
package com.u1.slicer.bambu.snapshot

data class Disagreement(
    val path: String,
    val kotlinValue: String,
    val nativeValue: String
)

object BambuSnapshotDiff {

    fun diff(kotlin: BambuFileSnapshot, native: BambuFileSnapshot): List<Disagreement> {
        val out = mutableListOf<Disagreement>()
        cmp(out, "isBbl", kotlin.isBbl, native.isBbl)
        cmp(out, "fileVersion", kotlin.fileVersion, native.fileVersion)
        if (kotlin.plates.size != native.plates.size) {
            out += Disagreement("plates.size", "${kotlin.plates.size}", "${native.plates.size}")
        } else {
            kotlin.plates.zip(native.plates).forEachIndexed { i, (k, n) -> diffPlate(out, "plates[$i]", k, n) }
        }
        diffObjects(out, kotlin.objects, native.objects)
        diffVolumes(out, kotlin.volumes, native.volumes)
        return out
    }

    private fun diffPlate(out: MutableList<Disagreement>, base: String, k: PlateSnapshot, n: PlateSnapshot) {
        cmp(out, "$base.plateIndex", k.plateIndex, n.plateIndex)
        diffStringList(out, "$base.filamentColours", k.filamentColours, n.filamentColours)
        diffStringList(out, "$base.filamentSettingsIds", k.filamentSettingsIds, n.filamentSettingsIds)
        if (k.objectInstanceMap.toSet() != n.objectInstanceMap.toSet()) {
            out += Disagreement("$base.objectInstanceMap", "${k.objectInstanceMap}", "${n.objectInstanceMap}")
        }
        if (k.customGcode.size != n.customGcode.size) {
            out += Disagreement("$base.customGcode.size", "${k.customGcode.size}", "${n.customGcode.size}")
        } else {
            k.customGcode.zip(n.customGcode).forEachIndexed { i, (a, b) ->
                if (a != b) out += Disagreement("$base.customGcode[$i]", "$a", "$b")
            }
        }
        diffStringMap(out, "$base.plateConfig", k.plateConfig, n.plateConfig)
    }

    private fun diffObjects(out: MutableList<Disagreement>, k: List<ObjectSnapshot>, n: List<ObjectSnapshot>) {
        if (k.size != n.size) {
            out += Disagreement("objects.size", "${k.size}", "${n.size}")
            return
        }
        // Compare by objectId to be order-independent.
        val kMap = k.associateBy { it.objectId }
        val nMap = n.associateBy { it.objectId }
        (kMap.keys + nMap.keys).sorted().forEach { id ->
            val ko = kMap[id]; val no = nMap[id]
            if (ko == null || no == null) {
                out += Disagreement("objects[$id]", "$ko", "$no")
            } else {
                cmp(out, "objects[$id].extruder", ko.extruder, no.extruder)
                cmp(out, "objects[$id].name", ko.name, no.name)
            }
        }
    }

    private fun diffVolumes(out: MutableList<Disagreement>, k: List<VolumeSnapshot>, n: List<VolumeSnapshot>) {
        // Same approach: index by (objectId, volumeIndex)
        val key: (VolumeSnapshot) -> Pair<Int, Int> = { it.objectId to it.volumeIndex }
        val kMap = k.associateBy(key); val nMap = n.associateBy(key)
        (kMap.keys + nMap.keys).sortedWith(compareBy({ it.first }, { it.second })).forEachIndexed { i, k_ ->
            val ko = kMap[k_]; val no = nMap[k_]
            val base = "volumes[$i]"
            if (ko == null || no == null) {
                out += Disagreement(base, "$ko", "$no"); return@forEachIndexed
            }
            cmp(out, "$base.extruder", ko.extruder, no.extruder)
            cmp(out, "$base.isMmPainted", ko.isMmPainted, no.isMmPainted)
            cmp(out, "$base.isSeamPainted", ko.isSeamPainted, no.isSeamPainted)
            (ko.paintStateSet.keys + no.paintStateSet.keys).sorted().forEach { st ->
                val a = ko.paintStateSet[st]; val b = no.paintStateSet[st]
                if (a != b) out += Disagreement("$base.paintStateSet[$st]", "$a", "$b")
            }
            (ko.paintSupportsStateSet.keys + no.paintSupportsStateSet.keys).sorted().forEach { st ->
                val a = ko.paintSupportsStateSet[st]; val b = no.paintSupportsStateSet[st]
                if (a != b) out += Disagreement("$base.paintSupportsStateSet[$st]", "$a", "$b")
            }
        }
    }

    private fun diffStringList(out: MutableList<Disagreement>, base: String, k: List<String>, n: List<String>) {
        if (k.size != n.size) {
            out += Disagreement("$base.size", "${k.size}", "${n.size}"); return
        }
        k.zip(n).forEachIndexed { i, (a, b) ->
            if (a != b) out += Disagreement("$base[$i]", a, b)
        }
    }

    private fun diffStringMap(out: MutableList<Disagreement>, base: String, k: Map<String, String>, n: Map<String, String>) {
        (k.keys + n.keys).sorted().forEach { key ->
            val a = k[key]; val b = n[key]
            if (a != b) out += Disagreement("$base[$key]", a ?: "<absent>", b ?: "<absent>")
        }
    }

    private fun cmp(out: MutableList<Disagreement>, path: String, k: Any?, n: Any?) {
        if (k != n) out += Disagreement(path, "$k", "$n")
    }
}
```

- [ ] **Step 8.4: Run — verify pass**

Run: `./gradlew testDebugUnitTest --tests "com.u1.slicer.bambu.snapshot.BambuSnapshotDiffTest"`
Expected: PASS, all 5 cases green.

- [ ] **Step 8.5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/bambu/snapshot/BambuSnapshotDiff.kt \
        app/src/test/java/com/u1/slicer/bambu/snapshot/BambuSnapshotDiffTest.kt
git commit -m "phase0(bambu-diff): structured per-field snapshot diff

Compares two BambuFileSnapshots and emits Disagreement(path, kotlin,
native) records. Paths use array-index syntax (plates[0].filamentColours[2])
so failures are immediately localisable. Order-independent matching
on objects (by objectId) and volumes (by objectId+volumeIndex) so a
parser that emits in different order still produces a clean diff."
```

---

## Task 9: Corpus differential runner

**Files:**
- Create: `app/src/androidTest/java/com/u1/slicer/bambu/snapshot/BambuParserDifferentialTest.kt`
- Create: `app/src/androidTest/assets/diagnostics/known-disagreements.json` (seed empty)

The corpus runner is the deliverable. One `@Test` method per fixture (parameterised). Each loads the fixture, runs both snapshot paths, diffs, and asserts that all disagreements are present in `known-disagreements.json`.

- [ ] **Step 9.1: Seed an empty baseline**

```bash
mkdir -p app/src/androidTest/assets/diagnostics
cat > app/src/androidTest/assets/diagnostics/known-disagreements.json <<'JSON'
{
  "_doc": "Per-fixture allowlist of diff paths the Kotlin and native parsers disagree on. Each entry: { fixture: <name>, path: <diff path>, reason: <Kotlin bug|C++ bug|intentional|unknown>, recordedAt: <YYYY-MM-DD> }",
  "fixtures": {}
}
JSON
```

- [ ] **Step 9.2: Write the runner**

```kotlin
// app/src/androidTest/java/com/u1/slicer/bambu/snapshot/BambuParserDifferentialTest.kt
package com.u1.slicer.bambu.snapshot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import org.json.JSONObject
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BambuParserDifferentialTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val baseline by lazy {
        ctx.assets.open("diagnostics/known-disagreements.json").use { it.bufferedReader().readText() }
            .let { JSONObject(it).optJSONObject("fixtures") ?: JSONObject() }
    }

    private fun runFixture(assetName: String) {
        val tmp = File(ctx.cacheDir, "diff_" + assetName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        ctx.assets.open(assetName).use { input -> tmp.outputStream().use { input.copyTo(it) } }

        val native = NativeLibrary()
        val nativeSnapshot = NativeBambuSnapshot.snapshot(tmp, native)
        val kotlinSnapshot = KotlinBambuSnapshot.snapshot(tmp)

        val diffs = BambuSnapshotDiff.diff(kotlinSnapshot, nativeSnapshot)
        val allowedPaths = baseline.optJSONArray(assetName)?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).getString("path") }.toSet()
        } ?: emptySet()

        val unexpected = diffs.filterNot { it.path in allowedPaths }
        if (unexpected.isNotEmpty()) {
            val report = unexpected.joinToString("\n") { "  ${it.path}\n    kotlin = ${it.kotlinValue}\n    native = ${it.nativeValue}" }
            fail("Unexpected diffs for $assetName (${unexpected.size}):\n$report\n\nIf intentional, add to known-disagreements.json.")
        }
    }

    @Test fun coloredBenchy()        = runFixture("colored_3DBenchy (1).3mf")
    @Test fun h2cBenchy()            = runFixture("3DBenchy-H2C-Multi-Color.3mf")
    @Test fun buzzMultipart()        = runFixture("Buzz_Multipart_3MF_Bambu.3mf")
    @Test fun buttonForS()           = runFixture("Button-for-S-trousers.3mf")
    @Test fun dragonScale()          = runFixture("Dragon Scale infinity.3mf")
    @Test fun dragonScale2c()        = runFixture("Dragon Scale infinity-1-plate-2-colours.3mf")
    @Test fun dragonScale2cNew()     = runFixture("Dragon Scale infinity-1-plate-2-colours-new-plate.3mf")
    @Test fun flarewingDragon()      = runFixture("Flarewing-Dragon_100%_4FilamentMulticolor_v1.1.3mf")
    @Test fun goatGray()             = runFixture("Goat ( Gray ).3mf")
    @Test fun korokMask()            = runFixture("PrusaSlicer-printables-Korok_mask_4colour.3mf")
    @Test fun sensoryTwistBall()     = runFixture("SENSORY+TWIST+BALL+FIDGETS+optimised.3mf")
    @Test fun shashibo()             = runFixture("Shashibo-h2s-textured.3mf")
    @Test fun calibCubeDual()        = runFixture("calib-cube-10-dual-colour-merged.3mf")
    @Test fun flippyFlappyMini()     = runFixture("flippy+flappy+mini.3mf")
    @Test fun flippyFlappyPainted()  = runFixture("flippy+flappy+mini-with-plate-painted.3mf")
    @Test fun foldyCoaster()         = runFixture("foldy+coaster (1).3mf")
    @Test fun oldThreeMf()           = runFixture("old.3mf")
    @Test fun skywingSeawing()       = runFixture("skywing-seawing-silkwing.3mf")
    @Test fun slipSlideSpin()        = runFixture("slip slide spin fidget.3mf")
    @Test fun spidermanHanging()     = runFixture("spiderman-hanging-pre-cut.3mf")
    @Test fun u1AuxFanCover()        = runFixture("u1-auxiliary-fan-cover-hex_mw.3mf")
}
```

- [ ] **Step 9.3: Run on-device — expect partial pass + documented failures**

Run: `ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest --no-daemon -Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.bambu.snapshot.BambuParserDifferentialTest`
Expected: some tests pass, some fail with diff reports. **This is success.** The diff reports are the to-do list for Phase 1.

- [ ] **Step 9.4: Commit (failures expected)**

```bash
git add app/src/androidTest/java/com/u1/slicer/bambu/snapshot/BambuParserDifferentialTest.kt \
        app/src/androidTest/assets/diagnostics/known-disagreements.json
git commit -m "phase0(bambu-diff): corpus runner with empty baseline

One @Test per fixture in app/src/androidTest/assets/. Each runs the
Kotlin and native parser paths and asserts the diff is empty (or
all paths appear in known-disagreements.json).

Initial run is expected to surface multiple disagreements per
fixture — those become Phase 1's to-do list. Task 10 populates
the baseline with each disagreement's reason."
```

---

## Task 10: Populate the baseline + categorise each disagreement

**Files:**
- Modify: `app/src/androidTest/assets/diagnostics/known-disagreements.json`

This is the deliverable that closes Phase 0. For every disagreement surfaced in Task 9, decide:

- **Kotlin bug** — the Kotlin parser is wrong; native is right. (Likely the most common case — e.g. B95.) Phase 1 will fix by deletion.
- **C++ bug** — the native loader is wrong (rare; would need an upstream fix or a Snapmaker-fork patch).
- **Intentional** — the two paths model the same fact differently on purpose (rare; should be eliminated where possible).
- **Unknown** — we don't yet know which is right; mark and triage in a follow-up.

- [ ] **Step 10.1: Run the differential test and capture all diffs**

Run the Task 9.3 command, redirect logcat output:

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest --no-daemon \
    -Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.bambu.snapshot.BambuParserDifferentialTest \
    > c:/tmp/bambu-diff-baseline.txt 2>&1 || true
```

- [ ] **Step 10.2: Categorise each disagreement and add to baseline JSON**

Parse the failure report. For each `(fixture, path)` pair, decide the reason and append to `app/src/androidTest/assets/diagnostics/known-disagreements.json`:

```json
{
  "_doc": "...",
  "fixtures": {
    "Buzz_Multipart_3MF_Bambu.3mf": [
      { "path": "volumes[7].paintStateSet[8]", "reason": "Kotlin bug — B95: Kotlin parser doesn't fold AMS2 state 8 to a physical extruder, native correctly counts the painted triangles", "recordedAt": "2026-04-23" },
      { "path": "plates[8].filamentColours[1]", "reason": "Kotlin bug — B92: Kotlin uses object-default order, native uses slicer-tool order", "recordedAt": "2026-04-23" }
    ],
    "Goat ( Gray ).3mf": [
      { "path": "volumes[0].paintStateSet[3]", "reason": "Kotlin bug — B76: Kotlin merge dedupe drops state 3, native preserves all 4 paint states", "recordedAt": "2026-04-23" }
    ]
  }
}
```

Do not over-document the obvious. Each entry needs the fixture, the diff path, the reason, and the date — that's it.

- [ ] **Step 10.3: Re-run the differential test — verify all green**

Run the Task 9.3 command. Expected: all tests PASS.

- [ ] **Step 10.4: Commit the baseline**

```bash
git add app/src/androidTest/assets/diagnostics/known-disagreements.json
git commit -m "phase0(bambu-diff): populate baseline from corpus run

All known disagreements between Kotlin and native parser paths
documented with reason. Most are Kotlin-side bugs that Phase 1
will fix by deleting the redundant Kotlin parsers. The list is
the prioritised input for Phase 1 sub-plans."
```

---

## Done condition

- All 10 tasks committed.
- `./gradlew testDebugUnitTest` green (851 + ~10 new = ~861).
- `ANDROID_SERIAL=<device> ./gradlew connectedDebugAndroidTest --no-daemon` green (191 + ~5 new instrumented = ~196).
- `app/src/androidTest/assets/diagnostics/known-disagreements.json` documents every Kotlin↔native disagreement with a reason.
- Native `.so` is the latest stripped Release build (~20MB, NDK 26 / Clang 17).
- Branch `refactor/bambu-via-native-loader` ready to merge to main, OR ready as the base for Phase 1 sub-plans.

## After this plan

Phase 1 begins. Write a separate plan per subsystem in priority order from [`docs/architecture/2026-04-23-bambu-via-native-loader.md`](../../architecture/2026-04-23-bambu-via-native-loader.md):

1. Painted facets → preview mesh
2. Per-plate `PlateData`
3. Custom gcode per layer
4. Object extruder map
5. Project config + filament colours

Each Phase 1 plan: add a JNI accessor that reads from `g_model`, replace one Kotlin parser, verify the diff harness stays green (now stricter — every "Kotlin bug" entry in `known-disagreements.json` for that subsystem should be removable as the Kotlin parser disappears).
