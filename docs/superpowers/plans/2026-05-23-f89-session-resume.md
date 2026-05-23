# F89 — Persist in-progress session + auto-resume on launch — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist Prepare-screen ephemeral state (loaded model, plate, scale, rotation, copies, custom drag-placement, F77 added files) to a single DataStore JSON key on every relevant mutation, and on next launch offer a non-modal "Resuming <name>" banner that replays the state on tap.

**Architecture:** New `SessionState` data class with `org.json.JSONObject` round-trip + `SessionStateRepository` (mirrors `PrintersRepository`). `SlicerViewModel` gains a debounced save flow that captures a snapshot after every mutation, a one-shot init read that exposes `_sessionResumeOffer`, and a `restoreSession()` that replays via existing public mutators (`loadModelFromFile` → `selectPlate` → `addModelFromFile` → `setModelScale/Rotation/CopyCount` → `applyPlacementPositions`). UI: a `SessionResumeBanner` composable wired into the Prepare tab when `state == Idle && offer != null`; a `_toastEvents` SharedFlow surfaces the "file no longer available" message from `MainActivity`.

**Tech Stack:** Kotlin 1.9.22, AndroidX DataStore Preferences, `org.json.JSONObject`, Jetpack Compose Material3, Coroutines (`Flow`, `MutableSharedFlow`, `debounce`).

**Spec:** [`docs/superpowers/specs/2026-05-23-f89-session-resume-design.md`](../specs/2026-05-23-f89-session-resume-design.md)

**Branch:** `feature/f89-session-resume`

---

## File Structure

**New files (production):**
- `app/src/main/java/com/u1/slicer/data/AppDataStore.kt` — shared `Context.appDataStore` extension for the existing `u1_slicer_settings` DataStore.
- `app/src/main/java/com/u1/slicer/data/SessionState.kt` — data class + `org.json` round-trip + nested `AdditionalFile`.
- `app/src/main/java/com/u1/slicer/data/SessionStateRepository.kt` — `read()` / `write()` / `clear()` over the shared DataStore.

**New files (tests):**
- `app/src/test/java/com/u1/slicer/data/SessionStateTest.kt` — JVM unit tests for JSON round-trip / schema validation.
- `app/src/androidTest/java/com/u1/slicer/data/SessionStateRepositoryTest.kt` — DataStore round-trip integration test.
- `app/src/androidTest/java/com/u1/slicer/SessionResumeIntegrationTest.kt` — full ViewModel restore flow integration test.

**Modified files (production):**
- `app/src/main/java/com/u1/slicer/data/SettingsRepository.kt` — switch from its own private `Context.dataStore` to the shared `Context.appDataStore`. Behaviour unchanged (same backing file name).
- `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` — repository field, snapshot helper, debounced save flow, `markSessionDirty()` calls in mutators, `_sessionResumeOffer` StateFlow, `_toastEvents` SharedFlow, `acceptSessionResume()` / `dismissSessionResume()`, `restoreSession()`.
- `app/src/main/java/com/u1/slicer/MainActivity.kt` — `SessionResumeBanner` composable + wiring into Prepare tab, collector for `toastEvents`.
- `app/build.gradle` — version bump 2.5.0 → 2.6.0, versionCode 294 → 295.
- `CLAUDE.md` — test counts (unit 1295 → 1305, instrumented 327 → 334) + brief F89 line in test class list.
- `README.md` — test counts if listed there.
- `BACKLOG.md` — mark F89 DONE (last task, after release).

**No native rebuild. No change to slicer pipeline. No change to Bambu refactor.**

---

## Task 1: Branch + shared DataStore extension

**Files:**
- Create: `app/src/main/java/com/u1/slicer/data/AppDataStore.kt`
- Modify: `app/src/main/java/com/u1/slicer/data/SettingsRepository.kt`

- [ ] **Step 1: Create the feature branch**

```bash
cd d:/projects/u1-slicer-orca
git checkout -b feature/f89-session-resume
```

- [ ] **Step 2: Create the shared DataStore extension file**

Create `app/src/main/java/com/u1/slicer/data/AppDataStore.kt` with this exact content:

```kotlin
package com.u1.slicer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * Shared `u1_slicer_settings` DataStore extension. Owned at package level so
 * `SettingsRepository` and `SessionStateRepository` reference the same backing
 * file via `context.appDataStore`. `preferencesDataStore` must only be invoked
 * once per store name per process — invoking twice with the same name silently
 * creates two racing `DataStore` instances over the same file.
 */
internal val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "u1_slicer_settings")
```

- [ ] **Step 3: Switch SettingsRepository to the shared extension**

In `app/src/main/java/com/u1/slicer/data/SettingsRepository.kt`:

Replace lines 1-11 (imports + the private extension):

```kotlin
package com.u1.slicer.data

import android.content.Context
import androidx.datastore.preferences.core.*
import com.u1.slicer.aipaint.AiPaintProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
```

(Remove the `import androidx.datastore.core.DataStore`, `import androidx.datastore.preferences.preferencesDataStore`, and `private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "u1_slicer_settings")` lines.)

Then `replace_all` `context.dataStore` → `context.appDataStore` inside the file.

- [ ] **Step 4: Run the existing unit-test suite to verify no regression**

```bash
./gradlew testDebugUnitTest --no-daemon
```

Expected: BUILD SUCCESSFUL, 1295 tests pass. (The DataStore extension change is a pure refactor; no behaviour change.)

- [ ] **Step 5: Run the existing instrumented suite to verify DataStore reads still work**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest --no-daemon \
  -Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.data.FilamentDaoTest,com.u1.slicer.data.SliceJobDaoTest
```

Expected: 17 tests pass. (Sanity check that the shared DataStore still resolves; full suite runs later.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/u1/slicer/data/AppDataStore.kt app/src/main/java/com/u1/slicer/data/SettingsRepository.kt
git commit -m "$(cat <<'EOF'
F89 prep: lift u1_slicer_settings DataStore to shared AppDataStore

Pure refactor — moves the `Context.dataStore` extension out of
SettingsRepository.kt into a package-level `AppDataStore.kt` so the
upcoming SessionStateRepository can reference the same backing file
without a second `preferencesDataStore { }` invocation (which would
silently create two racing DataStore instances over one file).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: SessionState data class + JSON round-trip (TDD)

**Files:**
- Create: `app/src/main/java/com/u1/slicer/data/SessionState.kt`
- Test: `app/src/test/java/com/u1/slicer/data/SessionStateTest.kt`

- [ ] **Step 1: Write the failing test file**

Create `app/src/test/java/com/u1/slicer/data/SessionStateTest.kt`:

```kotlin
package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateTest {

    private fun sampleSession(
        customObjectPositions: FloatArray? = floatArrayOf(135f, 135f, 70f, 70f),
        customWipeTowerPos: Pair<Float, Float>? = 170f to 140f,
        additionalFiles: List<SessionState.AdditionalFile> = listOf(
            SessionState.AdditionalFile(path = "/cache/extra.stl", plateIdx = -1),
            SessionState.AdditionalFile(path = "/cache/multi.3mf", plateIdx = 2),
        ),
        selectedPlateId: Int? = 8,
    ) = SessionState(
        modelName = "Buzz Lightyear.3mf",
        rawInputPath = "/cache/buzz.3mf",
        sourceModelPath = "/cache/buzz.sanitized.3mf",
        currentModelPath = "/cache/buzz.embedded.plate8.3mf",
        multiPlateSourcePath = "/cache/buzz.sanitized.3mf",
        selectedPlateId = selectedPlateId,
        modelScale = Triple(0.95f, 0.95f, 0.95f),
        modelRotation = Triple(0f, 0f, 1.5707964f),
        copyCount = 2,
        customObjectPositions = customObjectPositions,
        customWipeTowerPos = customWipeTowerPos,
        additionalFiles = additionalFiles,
        savedAtEpochMs = 1716480000000L,
        appVersionCode = 295,
    )

    @Test
    fun toJson_fromJson_roundTrip_basicFields() {
        val src = sampleSession()
        val json = SessionState.toJson(src)
        val parsed = SessionState.fromJson(json)
        assertNotNull(parsed)
        assertEquals(src.modelName, parsed!!.modelName)
        assertEquals(src.rawInputPath, parsed.rawInputPath)
        assertEquals(src.sourceModelPath, parsed.sourceModelPath)
        assertEquals(src.currentModelPath, parsed.currentModelPath)
        assertEquals(src.multiPlateSourcePath, parsed.multiPlateSourcePath)
        assertEquals(src.selectedPlateId, parsed.selectedPlateId)
        assertEquals(src.modelScale, parsed.modelScale)
        assertEquals(src.modelRotation, parsed.modelRotation)
        assertEquals(src.copyCount, parsed.copyCount)
        assertEquals(src.savedAtEpochMs, parsed.savedAtEpochMs)
        assertEquals(src.appVersionCode, parsed.appVersionCode)
    }

    @Test
    fun toJson_fromJson_roundTrip_customObjectPositions() {
        val src = sampleSession(customObjectPositions = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f))
        val parsed = SessionState.fromJson(SessionState.toJson(src))!!
        assertNotNull(parsed.customObjectPositions)
        assertTrue(src.customObjectPositions!!.contentEquals(parsed.customObjectPositions!!))
        assertEquals(170f to 140f, parsed.customWipeTowerPos)
    }

    @Test
    fun toJson_fromJson_roundTrip_emptyAdditionalFiles() {
        val src = sampleSession(additionalFiles = emptyList())
        val parsed = SessionState.fromJson(SessionState.toJson(src))!!
        assertEquals(0, parsed.additionalFiles.size)
    }

    @Test
    fun toJson_fromJson_roundTrip_multipleAdditionalFiles() {
        val files = listOf(
            SessionState.AdditionalFile("/a.stl", -1),
            SessionState.AdditionalFile("/b.3mf", 3),
            SessionState.AdditionalFile("/c.obj", -1),
        )
        val src = sampleSession(additionalFiles = files)
        val parsed = SessionState.fromJson(SessionState.toJson(src))!!
        assertEquals(3, parsed.additionalFiles.size)
        assertEquals("/a.stl", parsed.additionalFiles[0].path)
        assertEquals(-1, parsed.additionalFiles[0].plateIdx)
        assertEquals("/b.3mf", parsed.additionalFiles[1].path)
        assertEquals(3, parsed.additionalFiles[1].plateIdx)
        assertEquals("/c.obj", parsed.additionalFiles[2].path)
    }

    @Test
    fun toJson_fromJson_roundTrip_nullablesAllNull() {
        val src = sampleSession(
            customObjectPositions = null,
            customWipeTowerPos = null,
            additionalFiles = emptyList(),
            selectedPlateId = null,
        )
        val parsed = SessionState.fromJson(SessionState.toJson(src))!!
        assertNull(parsed.customObjectPositions)
        assertNull(parsed.customWipeTowerPos)
        assertNull(parsed.selectedPlateId)
        assertEquals(0, parsed.additionalFiles.size)
    }

    @Test
    fun fromJson_malformedJson_returnsNull() {
        assertNull(SessionState.fromJson("this is not json"))
        assertNull(SessionState.fromJson(""))
        assertNull(SessionState.fromJson("{"))
    }

    @Test
    fun fromJson_missingVersionField_returnsNull() {
        val noVersion = """{"modelName":"x","rawInputPath":"/a","modelScale":{"x":1,"y":1,"z":1},"modelRotation":{"x":0,"y":0,"z":0},"copyCount":1,"savedAtEpochMs":0,"appVersionCode":0}"""
        assertNull(SessionState.fromJson(noVersion))
    }

    @Test
    fun fromJson_unknownSchemaVersion_returnsNull() {
        val futureVersion = """{"version":99,"modelName":"x","rawInputPath":"/a","modelScale":{"x":1,"y":1,"z":1},"modelRotation":{"x":0,"y":0,"z":0},"copyCount":1,"savedAtEpochMs":0,"appVersionCode":0}"""
        assertNull(SessionState.fromJson(futureVersion))
    }

    @Test
    fun fromJson_missingRequiredModelName_returnsNull() {
        val noName = """{"version":1,"rawInputPath":"/a","modelScale":{"x":1,"y":1,"z":1},"modelRotation":{"x":0,"y":0,"z":0},"copyCount":1,"savedAtEpochMs":0,"appVersionCode":0}"""
        assertNull(SessionState.fromJson(noName))
    }

    @Test
    fun fromJson_missingRequiredRawInputPath_returnsNull() {
        val noPath = """{"version":1,"modelName":"x","modelScale":{"x":1,"y":1,"z":1},"modelRotation":{"x":0,"y":0,"z":0},"copyCount":1,"savedAtEpochMs":0,"appVersionCode":0}"""
        assertNull(SessionState.fromJson(noPath))
    }
}
```

- [ ] **Step 2: Run to verify all tests fail (class doesn't exist yet)**

```bash
./gradlew testDebugUnitTest --no-daemon --tests com.u1.slicer.data.SessionStateTest
```

Expected: compile failure ("Unresolved reference: SessionState").

- [ ] **Step 3: Create the SessionState file**

Create `app/src/main/java/com/u1/slicer/data/SessionState.kt`:

```kotlin
package com.u1.slicer.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Ephemeral Prepare-screen state persisted across process death so F89 can
 * offer a Resume banner on next launch. JSON-serialized into a single
 * `session_state_json` key in the shared `u1_slicer_settings` DataStore.
 *
 * `fromJson` returns null on any parse failure (malformed JSON, missing
 * required fields, unknown schema version) — callers treat null identically
 * to "no session". The bad blob stays in DataStore until the next mutation
 * overwrites it. We'd rather lose a session than crash on a malformed read.
 */
data class SessionState(
    val modelName: String,
    val rawInputPath: String,
    val sourceModelPath: String?,
    val currentModelPath: String?,
    val multiPlateSourcePath: String?,
    val selectedPlateId: Int?,
    val modelScale: Triple<Float, Float, Float>,
    val modelRotation: Triple<Float, Float, Float>,
    val copyCount: Int,
    val customObjectPositions: FloatArray?,
    val customWipeTowerPos: Pair<Float, Float>?,
    val additionalFiles: List<AdditionalFile>,
    val savedAtEpochMs: Long,
    val appVersionCode: Int,
) {
    data class AdditionalFile(val path: String, val plateIdx: Int)

    // FloatArray needs content-based equality for the data class contract.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SessionState) return false
        return modelName == other.modelName &&
            rawInputPath == other.rawInputPath &&
            sourceModelPath == other.sourceModelPath &&
            currentModelPath == other.currentModelPath &&
            multiPlateSourcePath == other.multiPlateSourcePath &&
            selectedPlateId == other.selectedPlateId &&
            modelScale == other.modelScale &&
            modelRotation == other.modelRotation &&
            copyCount == other.copyCount &&
            ((customObjectPositions == null && other.customObjectPositions == null) ||
                (customObjectPositions != null && other.customObjectPositions != null &&
                    customObjectPositions.contentEquals(other.customObjectPositions))) &&
            customWipeTowerPos == other.customWipeTowerPos &&
            additionalFiles == other.additionalFiles &&
            savedAtEpochMs == other.savedAtEpochMs &&
            appVersionCode == other.appVersionCode
    }

    override fun hashCode(): Int {
        var result = modelName.hashCode()
        result = 31 * result + rawInputPath.hashCode()
        result = 31 * result + (sourceModelPath?.hashCode() ?: 0)
        result = 31 * result + (currentModelPath?.hashCode() ?: 0)
        result = 31 * result + (multiPlateSourcePath?.hashCode() ?: 0)
        result = 31 * result + (selectedPlateId ?: 0)
        result = 31 * result + modelScale.hashCode()
        result = 31 * result + modelRotation.hashCode()
        result = 31 * result + copyCount
        result = 31 * result + (customObjectPositions?.contentHashCode() ?: 0)
        result = 31 * result + (customWipeTowerPos?.hashCode() ?: 0)
        result = 31 * result + additionalFiles.hashCode()
        result = 31 * result + savedAtEpochMs.hashCode()
        result = 31 * result + appVersionCode
        return result
    }

    companion object {
        const val SCHEMA_VERSION = 1

        fun toJson(state: SessionState): String {
            val obj = JSONObject()
            obj.put("version", SCHEMA_VERSION)
            obj.put("modelName", state.modelName)
            obj.put("rawInputPath", state.rawInputPath)
            state.sourceModelPath?.let { obj.put("sourceModelPath", it) }
            state.currentModelPath?.let { obj.put("currentModelPath", it) }
            state.multiPlateSourcePath?.let { obj.put("multiPlateSourcePath", it) }
            state.selectedPlateId?.let { obj.put("selectedPlateId", it) }
            obj.put("modelScale", JSONObject().apply {
                put("x", state.modelScale.first.toDouble())
                put("y", state.modelScale.second.toDouble())
                put("z", state.modelScale.third.toDouble())
            })
            obj.put("modelRotation", JSONObject().apply {
                put("x", state.modelRotation.first.toDouble())
                put("y", state.modelRotation.second.toDouble())
                put("z", state.modelRotation.third.toDouble())
            })
            obj.put("copyCount", state.copyCount)
            state.customObjectPositions?.let { arr ->
                val ja = JSONArray()
                arr.forEach { ja.put(it.toDouble()) }
                obj.put("customObjectPositions", ja)
            }
            state.customWipeTowerPos?.let { (x, y) ->
                obj.put("customWipeTowerPos", JSONObject().apply {
                    put("x", x.toDouble())
                    put("y", y.toDouble())
                })
            }
            val filesArr = JSONArray()
            state.additionalFiles.forEach { f ->
                filesArr.put(JSONObject().apply {
                    put("path", f.path)
                    put("plateIdx", f.plateIdx)
                })
            }
            obj.put("additionalFiles", filesArr)
            obj.put("savedAtEpochMs", state.savedAtEpochMs)
            obj.put("appVersionCode", state.appVersionCode)
            return obj.toString()
        }

        fun fromJson(json: String): SessionState? = try {
            val obj = JSONObject(json)
            val version = if (obj.has("version")) obj.getInt("version") else return null
            if (version != SCHEMA_VERSION) return null
            val modelName = if (obj.has("modelName")) obj.getString("modelName") else return null
            val rawInputPath = if (obj.has("rawInputPath")) obj.getString("rawInputPath") else return null
            val scaleObj = obj.getJSONObject("modelScale")
            val rotObj = obj.getJSONObject("modelRotation")
            val customPositions = if (obj.has("customObjectPositions")) {
                val ja = obj.getJSONArray("customObjectPositions")
                FloatArray(ja.length()) { i -> ja.getDouble(i).toFloat() }
            } else null
            val customTower = if (obj.has("customWipeTowerPos")) {
                val o = obj.getJSONObject("customWipeTowerPos")
                o.getDouble("x").toFloat() to o.getDouble("y").toFloat()
            } else null
            val filesArr = obj.optJSONArray("additionalFiles")
            val files = if (filesArr == null) emptyList() else (0 until filesArr.length()).map { i ->
                val f = filesArr.getJSONObject(i)
                AdditionalFile(path = f.getString("path"), plateIdx = f.getInt("plateIdx"))
            }
            SessionState(
                modelName = modelName,
                rawInputPath = rawInputPath,
                sourceModelPath = if (obj.has("sourceModelPath")) obj.getString("sourceModelPath") else null,
                currentModelPath = if (obj.has("currentModelPath")) obj.getString("currentModelPath") else null,
                multiPlateSourcePath = if (obj.has("multiPlateSourcePath")) obj.getString("multiPlateSourcePath") else null,
                selectedPlateId = if (obj.has("selectedPlateId")) obj.getInt("selectedPlateId") else null,
                modelScale = Triple(scaleObj.getDouble("x").toFloat(), scaleObj.getDouble("y").toFloat(), scaleObj.getDouble("z").toFloat()),
                modelRotation = Triple(rotObj.getDouble("x").toFloat(), rotObj.getDouble("y").toFloat(), rotObj.getDouble("z").toFloat()),
                copyCount = obj.getInt("copyCount"),
                customObjectPositions = customPositions,
                customWipeTowerPos = customTower,
                additionalFiles = files,
                savedAtEpochMs = obj.getLong("savedAtEpochMs"),
                appVersionCode = obj.getInt("appVersionCode"),
            )
        } catch (e: JSONException) {
            null
        } catch (e: Exception) {
            null
        }
    }
}
```

- [ ] **Step 4: Run the test to verify all pass**

```bash
./gradlew testDebugUnitTest --no-daemon --tests com.u1.slicer.data.SessionStateTest
```

Expected: 10 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/data/SessionState.kt app/src/test/java/com/u1/slicer/data/SessionStateTest.kt
git commit -m "$(cat <<'EOF'
F89: SessionState data class + org.json round-trip

Pure-state schema for the F89 session-resume feature: model name + paths,
plate id, scale/rotation/copy count, custom drag-placement, F77 additional
files. SCHEMA_VERSION = 1. fromJson returns null on any parse failure so
malformed blobs degrade gracefully to "no session" rather than crashing.

10 unit tests covering: basic round-trip, FloatArray round-trip, empty/
multi additionalFiles, all-nullables-null, malformed JSON, missing version,
unknown version, missing required modelName/rawInputPath.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: SessionStateRepository + instrumented round-trip test

**Files:**
- Create: `app/src/main/java/com/u1/slicer/data/SessionStateRepository.kt`
- Test: `app/src/androidTest/java/com/u1/slicer/data/SessionStateRepositoryTest.kt`

- [ ] **Step 1: Write the failing instrumented test**

Create `app/src/androidTest/java/com/u1/slicer/data/SessionStateRepositoryTest.kt`:

```kotlin
package com.u1.slicer.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionStateRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val repo = SessionStateRepository(context)

    @Before
    fun setUp() = runBlocking { repo.clear() }

    @After
    fun tearDown() = runBlocking { repo.clear() }

    private fun sample() = SessionState(
        modelName = "test.3mf",
        rawInputPath = "/cache/test.3mf",
        sourceModelPath = null,
        currentModelPath = null,
        multiPlateSourcePath = null,
        selectedPlateId = 3,
        modelScale = Triple(1f, 1f, 1f),
        modelRotation = Triple(0f, 0f, 0f),
        copyCount = 1,
        customObjectPositions = floatArrayOf(135f, 135f),
        customWipeTowerPos = 170f to 140f,
        additionalFiles = emptyList(),
        savedAtEpochMs = 1716480000000L,
        appVersionCode = 295,
    )

    @Test
    fun write_thenRead_returnsSameSessionState() = runBlocking {
        val src = sample()
        repo.write(src)
        val parsed = repo.state.first()
        assertNotNull(parsed)
        assertEquals(src, parsed)
    }

    @Test
    fun read_emptyStore_returnsNull() = runBlocking {
        val parsed = repo.state.first()
        assertNull(parsed)
    }

    @Test
    fun clear_afterWrite_readReturnsNull() = runBlocking {
        repo.write(sample())
        repo.clear()
        assertNull(repo.state.first())
    }

    @Test
    fun write_overwrites_prior() = runBlocking {
        repo.write(sample())
        val second = sample().copy(modelName = "second.3mf", copyCount = 4)
        repo.write(second)
        val parsed = repo.state.first()
        assertEquals("second.3mf", parsed?.modelName)
        assertEquals(4, parsed?.copyCount)
    }
}
```

- [ ] **Step 2: Run to verify it fails (class doesn't exist)**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest --no-daemon \
  -Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.data.SessionStateRepositoryTest
```

Expected: compile failure ("Unresolved reference: SessionStateRepository").

- [ ] **Step 3: Implement the repository**

Create `app/src/main/java/com/u1/slicer/data/SessionStateRepository.kt`:

```kotlin
package com.u1.slicer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Owns the most-recent in-progress Prepare-screen session for F89.
 * Single DataStore key `session_state_json` in the shared `u1_slicer_settings`
 * DataStore. Writes are atomic per `edit { }` contract. The repository is
 * thin — all state-transition logic is on [SessionState] as pure helpers
 * so it's JVM-unit-testable.
 */
class SessionStateRepository(private val context: Context) {

    private val key = stringPreferencesKey(KEY_NAME)

    val state: Flow<SessionState?> = context.appDataStore.data.map { prefs ->
        val raw = prefs[key]
        if (raw.isNullOrBlank()) null else SessionState.fromJson(raw)
    }

    suspend fun read(): SessionState? = state.first()

    suspend fun write(state: SessionState) {
        context.appDataStore.edit { prefs ->
            prefs[key] = SessionState.toJson(state)
        }
    }

    suspend fun clear() {
        context.appDataStore.edit { prefs ->
            prefs.remove(key)
        }
    }

    companion object {
        const val KEY_NAME = "session_state_json"
    }
}
```

- [ ] **Step 4: Run the instrumented test to verify it passes**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest --no-daemon \
  -Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.data.SessionStateRepositoryTest
```

Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/data/SessionStateRepository.kt app/src/androidTest/java/com/u1/slicer/data/SessionStateRepositoryTest.kt
git commit -m "$(cat <<'EOF'
F89: SessionStateRepository — DataStore read/write/clear

Thin wrapper over the shared `u1_slicer_settings` DataStore for the
`session_state_json` key. Mirrors PrintersRepository's shape. All state-
transition logic stays on SessionState as pure helpers; the repository
itself only exposes read/write/clear.

4 instrumented tests: round-trip, empty-store-null, clear-after-write,
write-overwrites-prior.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: SlicerViewModel — session save wiring (snapshot + debounce + dirty hooks)

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`

This task adds the *write* half. The *restore* half is Task 5.

- [ ] **Step 1: Add the repository field and debounce flow**

Open `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`. Find the existing init block (search for `init {` near the top of the class — there is a primary one; if not present, add one after the field declarations near `private val _state = MutableStateFlow<SlicerState>(SlicerState.Idle)` at line 186).

Add these imports at the top of the file (next to existing `kotlinx.coroutines` imports):

```kotlin
import com.u1.slicer.data.SessionState
import com.u1.slicer.data.SessionStateRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.combine
```

Find a stable location for new private fields — right after the existing `additionalModelFiles` declaration at line 489 is a good anchor. Add:

```kotlin
    // F89: session persistence — debounced save of Prepare-screen ephemeral state.
    private val sessionStateRepository = SessionStateRepository(getApplication())
    private val sessionSaveFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    // F89: toast events surfaced to MainActivity (Toast.makeText). One-shot strings.
    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toastEvents: kotlinx.coroutines.flow.SharedFlow<String> = _toastEvents

    // F89: resume offer — non-null when a saved session was found on launch and the
    // source file is still on disk. Cleared on Resume (after the load starts) or
    // explicit dismiss; banner UI gates on `state == Idle && offer != null`.
    private val _sessionResumeOffer = MutableStateFlow<SessionResumeOffer?>(null)
    val sessionResumeOffer: StateFlow<SessionResumeOffer?> = _sessionResumeOffer.asStateFlow()

    data class SessionResumeOffer(val modelName: String, val plateId: Int?)
```

- [ ] **Step 2: Add the capture helper + dirty marker + debounced save coroutine**

Add these private functions somewhere in the class (e.g. immediately above `fun clearModel()` at line 4546):

```kotlin
    // F89: capture a SessionState snapshot from the current ViewModel state.
    // Returns null if there's nothing meaningful to save (no rawInputFile = no
    // loaded model). Pure read of existing fields; safe to call from any thread.
    private fun captureSessionSnapshot(): SessionState? {
        val raw = rawInputFile ?: return null
        return SessionState(
            modelName = currentModelName,
            rawInputPath = raw.absolutePath,
            sourceModelPath = sourceModelFile?.absolutePath,
            currentModelPath = _currentModelFile?.absolutePath,
            multiPlateSourcePath = _multiPlateSourceFile?.absolutePath,
            selectedPlateId = recoveryPlateId.takeIf { it >= 0 },
            modelScale = _modelScale.value.let { Triple(it.x, it.y, it.z) },
            modelRotation = _modelRotation.value.let { Triple(it.x, it.y, it.z) },
            copyCount = _copyCount.value,
            customObjectPositions = customObjectPositions?.copyOf(),
            customWipeTowerPos = customWipeTowerPos,
            additionalFiles = additionalModelFiles.map { (f, p) ->
                SessionState.AdditionalFile(path = f.absolutePath, plateIdx = p)
            },
            savedAtEpochMs = System.currentTimeMillis(),
            appVersionCode = BuildConfig.VERSION_CODE,
        )
    }

    // F89: emit to the debounced save flow. Cheap; safe to call per-frame.
    private fun markSessionDirty() {
        if (rawInputFile == null) return
        sessionSaveFlow.tryEmit(Unit)
    }

    // F89: wire the debounced session save + the StateFlow-based dirty mirror.
    // Called once from init.
    private fun wireSessionPersistence() {
        viewModelScope.launch {
            sessionSaveFlow
                .debounce(500)
                .collectLatest {
                    val snapshot = captureSessionSnapshot() ?: return@collectLatest
                    try {
                        sessionStateRepository.write(snapshot)
                    } catch (e: Exception) {
                        Log.w("SlicerVM", "F89 session save failed: ${e.message}")
                    }
                }
        }
        // StateFlow-based mirror — combine emits once on subscribe with current
        // values; `markSessionDirty` no-ops while rawInputFile is null so the
        // startup emission is harmless.
        viewModelScope.launch {
            combine(_modelScale, _modelRotation, _copyCount) { _, _, _ -> Unit }
                .collect { markSessionDirty() }
        }
    }
```

- [ ] **Step 3: Invoke `wireSessionPersistence()` from init**

Find the existing `init {` block (search for `init {` — the constructor-time init). If no init block exists at the top of the class, add one right after the `private val _state = MutableStateFlow<SlicerState>(SlicerState.Idle)` line. Add the call:

```kotlin
    init {
        wireSessionPersistence()
        // (preserve any existing init body)
    }
```

If an init block already exists, just add `wireSessionPersistence()` as the first line inside it.

- [ ] **Step 4: Add `markSessionDirty()` to the explicit mutators**

Five mutators need an explicit call. Find each function and add `markSessionDirty()` as the LAST line before the closing brace of the function body (after any state mutations have settled):

**4a. `loadModel(uri)` at line ~1318** — add at the end of the success branch inside `viewModelScope.launch`, after `currentModelFile = ...` is set. Specifically, after line ~1462 (`currentModelFile = fileToLoad`) and any post-load state updates, add:

```kotlin
                markSessionDirty()
```

Place it at the very end of the try-block after the model is fully loaded (before any catch).

**4b. `loadModelFromFile(file, preserveDisplayName)` at line 1799** — same: after `currentModelFile = fileToLoad` near line ~1925, at the end of the try block. Add:

```kotlin
                markSessionDirty()
```

**4c. `addModelFromFile(file)` at line 1665** and `addModelFromFileForPlate(file, plateIdx)` at line 1687 — both go through `doAddFile`. Add `markSessionDirty()` at the end of `doAddFile` (after the `customWipeTowerPos` update block ending ~line 1782, inside `previewMutex.withLock { }` is fine OR right after the `withLock` closing brace). Use the location after `customObjectPositions = positions` so the snapshot sees the new state.

In `doAddFile`, near the end (after `val publishPositions = customObjectPositions` at line ~1782), add:

```kotlin
            markSessionDirty()
```

**4d. `selectPlate(plateId)` at line 1938** — add `markSessionDirty()` at the very end of the `selectPlateJob = viewModelScope.launch(Dispatchers.IO) { ... }` block (after `_currentModelFile = embeddedPlateFile` and subsequent state updates settle). Find the line `_currentModelFile = embeddedPlateFile` at ~2015 and add a `markSessionDirty()` line after the load completes successfully — typically near the end of the try-block, before the catch.

**4e. `applyPlacementPositions(positions, wipeTowerPos)` at line 2540** — append at the very end of the function body, after the final closing brace of the `if (hasMultipleDistinctObjectsVar) { ... }` block but inside the function. Add as the last line of the function:

```kotlin
        markSessionDirty()
```

- [ ] **Step 5: Add explicit clear in `clearModel()`**

Find `fun clearModel()` at line 4546. At the end of the function body (last line of the function, after all the existing field clears), add:

```kotlin
        viewModelScope.launch {
            try {
                sessionStateRepository.clear()
            } catch (e: Exception) {
                Log.w("SlicerVM", "F89 session clear failed: ${e.message}")
            }
        }
        _sessionResumeOffer.value = null
```

- [ ] **Step 6: Build to verify no compile errors**

```bash
./gradlew assembleDebug --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Run the existing test suite for regressions**

```bash
./gradlew testDebugUnitTest --no-daemon
```

Expected: 1305 tests pass (1295 existing + 10 from Task 2).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/u1/slicer/SlicerViewModel.kt
git commit -m "$(cat <<'EOF'
F89: SlicerViewModel — debounced session save on Prepare mutations

Adds SessionStateRepository field + debounced (500 ms) save flow. Wires
markSessionDirty() into loadModel, loadModelFromFile, addModelFromFile
(via doAddFile), selectPlate, and applyPlacementPositions. Combines
modelScale/modelRotation/copyCount StateFlows into the same dirty
mirror. clearModel() clears the session immediately + drops any pending
resume offer.

Exposes _sessionResumeOffer and _toastEvents StateFlow/SharedFlow for
the restore-half wiring in the next commit.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: SlicerViewModel — restore offer + restoreSession()

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`
- Test: `app/src/androidTest/java/com/u1/slicer/SessionResumeIntegrationTest.kt`

- [ ] **Step 1: Write the failing integration test**

Create `app/src/androidTest/java/com/u1/slicer/SessionResumeIntegrationTest.kt`:

```kotlin
package com.u1.slicer

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.u1.slicer.data.SessionState
import com.u1.slicer.data.SessionStateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SessionResumeIntegrationTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val repo = SessionStateRepository(app)

    @Before
    fun setUp() = runBlocking { repo.clear() }

    @After
    fun tearDown() = runBlocking { repo.clear() }

    private fun copyAssetToCache(assetName: String, outName: String = assetName): File {
        val out = File(app.cacheDir, outName)
        app.assets.open(assetName).use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }

    @Test
    fun init_savedSessionWithExistingFile_exposesResumeOffer() = runBlocking {
        val asset = copyAssetToCache("colored_3DBenchy.3mf")
        repo.write(
            SessionState(
                modelName = "colored_3DBenchy.3mf",
                rawInputPath = asset.absolutePath,
                sourceModelPath = null, currentModelPath = null, multiPlateSourcePath = null,
                selectedPlateId = null,
                modelScale = Triple(1f, 1f, 1f),
                modelRotation = Triple(0f, 0f, 0f),
                copyCount = 1,
                customObjectPositions = null, customWipeTowerPos = null,
                additionalFiles = emptyList(),
                savedAtEpochMs = System.currentTimeMillis(),
                appVersionCode = 295,
            )
        )
        val vm = SlicerViewModel(app)
        val offer = withTimeoutOrNull(5_000) {
            vm.sessionResumeOffer.first { it != null }
        }
        assertNotNull("Resume offer was never exposed", offer)
        assertEquals("colored_3DBenchy.3mf", offer!!.modelName)
        assertNull(offer.plateId)
    }

    @Test
    fun init_savedSessionMissingFile_emitsToastAndClears() = runBlocking {
        repo.write(
            SessionState(
                modelName = "ghost.3mf",
                rawInputPath = "/cache/does-not-exist.3mf",
                sourceModelPath = null, currentModelPath = null, multiPlateSourcePath = null,
                selectedPlateId = null,
                modelScale = Triple(1f, 1f, 1f),
                modelRotation = Triple(0f, 0f, 0f),
                copyCount = 1,
                customObjectPositions = null, customWipeTowerPos = null,
                additionalFiles = emptyList(),
                savedAtEpochMs = 0L,
                appVersionCode = 295,
            )
        )
        val vm = SlicerViewModel(app)
        val toast = withTimeoutOrNull(5_000) {
            vm.toastEvents.first()
        }
        assertNotNull("Toast event was never emitted", toast)
        assertEquals("Couldn't resume ghost.3mf — file no longer available", toast)
        assertNull("Stale session should be cleared", repo.read())
        assertNull("No resume offer should be shown for missing files", vm.sessionResumeOffer.value)
    }

    @Test
    fun dismissSessionResume_clearsOfferAndDataStore() = runBlocking {
        val asset = copyAssetToCache("colored_3DBenchy.3mf")
        repo.write(
            SessionState(
                modelName = "colored_3DBenchy.3mf",
                rawInputPath = asset.absolutePath,
                sourceModelPath = null, currentModelPath = null, multiPlateSourcePath = null,
                selectedPlateId = null,
                modelScale = Triple(1f, 1f, 1f),
                modelRotation = Triple(0f, 0f, 0f),
                copyCount = 1,
                customObjectPositions = null, customWipeTowerPos = null,
                additionalFiles = emptyList(),
                savedAtEpochMs = 0L,
                appVersionCode = 295,
            )
        )
        val vm = SlicerViewModel(app)
        withTimeoutOrNull(5_000) { vm.sessionResumeOffer.first { it != null } }
        vm.dismissSessionResume()
        kotlinx.coroutines.delay(200)
        assertNull(vm.sessionResumeOffer.value)
        assertNull(repo.read())
    }
}
```

(Uses `colored_3DBenchy.3mf` which already exists in `app/src/androidTest/assets/` per the existing slicing integration tests.)

- [ ] **Step 2: Run to verify it fails (init read + public methods not yet wired)**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest --no-daemon \
  -Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.SessionResumeIntegrationTest
```

Expected: compile or runtime failures (the public methods + init read don't exist yet).

- [ ] **Step 3: Add the init read + restore methods in SlicerViewModel**

Inside `wireSessionPersistence()` (already added in Task 4), append a third coroutine that reads on init:

```kotlin
    private fun wireSessionPersistence() {
        viewModelScope.launch {
            sessionSaveFlow
                .debounce(500)
                .collectLatest {
                    val snapshot = captureSessionSnapshot() ?: return@collectLatest
                    try {
                        sessionStateRepository.write(snapshot)
                    } catch (e: Exception) {
                        Log.w("SlicerVM", "F89 session save failed: ${e.message}")
                    }
                }
        }
        viewModelScope.launch {
            combine(_modelScale, _modelRotation, _copyCount) { _, _, _ -> Unit }
                .collect { markSessionDirty() }
        }
        // F89: one-shot init read. If a saved session exists and the source file
        // is still on disk, expose a resume offer for the banner UI. If the file
        // is gone, surface a one-time toast and clear the stale entry.
        viewModelScope.launch {
            val saved = try { sessionStateRepository.read() } catch (e: Exception) {
                Log.w("SlicerVM", "F89 session read failed: ${e.message}")
                null
            } ?: return@launch
            val raw = File(saved.rawInputPath)
            if (!raw.exists()) {
                try { sessionStateRepository.clear() } catch (e: Exception) {
                    Log.w("SlicerVM", "F89 stale-session clear failed: ${e.message}")
                }
                _toastEvents.tryEmit("Couldn't resume ${saved.modelName} — file no longer available")
                return@launch
            }
            _sessionResumeOffer.value = SessionResumeOffer(
                modelName = saved.modelName,
                plateId = saved.selectedPlateId,
            )
        }
    }
```

Now add the two public methods + the private `restoreSession` somewhere in the class (e.g. immediately after `wireSessionPersistence()`):

```kotlin
    /** F89: user tapped Resume on the banner. Replays the saved session via
     *  the existing public mutators. */
    fun acceptSessionResume() {
        val offer = _sessionResumeOffer.value ?: return
        _sessionResumeOffer.value = null
        viewModelScope.launch {
            val saved = try { sessionStateRepository.read() } catch (e: Exception) { null }
                ?: return@launch
            val raw = File(saved.rawInputPath)
            if (!raw.exists()) {
                try { sessionStateRepository.clear() } catch (_: Exception) {}
                _toastEvents.tryEmit("Couldn't resume ${saved.modelName} — file no longer available")
                return@launch
            }
            restoreSession(saved, raw)
        }
    }

    /** F89: user tapped × on the banner. Clear the offer and the DataStore entry. */
    fun dismissSessionResume() {
        _sessionResumeOffer.value = null
        viewModelScope.launch {
            try { sessionStateRepository.clear() } catch (e: Exception) {
                Log.w("SlicerVM", "F89 dismiss-clear failed: ${e.message}")
            }
        }
    }

    /** F89: replay the saved session. Runs on the caller's coroutine (already
     *  in viewModelScope). Suspends through the existing loading paths. */
    private suspend fun restoreSession(saved: SessionState, raw: File) {
        // 1. Trigger the standard load. loadModelFromFile launches its own
        //    coroutine; we observe _state to know when it completes.
        loadModelFromFile(raw, preserveDisplayName = saved.modelName)
        if (!awaitLoadCompletion()) {
            return // Error state — leave session in place so user can retry
        }
        // 2. Plate selection. Only meaningful when a plate id was saved AND
        //    the loaded file is multi-plate AND that plate id exists.
        val plateId = saved.selectedPlateId
        if (plateId != null && _multiPlatePlates.value.any { it.plateId == plateId }) {
            selectPlate(plateId)
            if (!awaitLoadCompletion()) return
        }
        // 3. Additional files (F77). Skip missing ones rather than fail the whole restore.
        for (entry in saved.additionalFiles) {
            val f = File(entry.path)
            if (!f.exists()) {
                Log.w("SlicerVM", "F89 restore: skipping missing additional file ${entry.path}")
                continue
            }
            if (entry.plateIdx >= 0) addModelFromFileForPlate(f, entry.plateIdx)
            else addModelFromFile(f)
            if (!awaitLoadCompletion()) return
        }
        // 4. Scale / rotation / copies. These reset customObjectPositions, so they
        //    MUST come before applyPlacementPositions in step 5.
        setModelScale(com.u1.slicer.data.ModelScale(
            saved.modelScale.first, saved.modelScale.second, saved.modelScale.third
        ))
        setModelRotation(com.u1.slicer.data.ModelRotation(
            saved.modelRotation.first, saved.modelRotation.second, saved.modelRotation.third
        ))
        setCopyCount(saved.copyCount)
        // 5. Custom placement — must be last because every other mutator resets it.
        val positions = saved.customObjectPositions
        val tower = saved.customWipeTowerPos
        if (positions != null && tower != null) {
            applyPlacementPositions(positions, tower)
        }
    }

    /** F89 restore helper: suspend until _state leaves Loading. Returns true if
     *  we landed in ModelLoaded (or any non-Error non-Loading state); false if
     *  we landed in Error (caller aborts restore). */
    private suspend fun awaitLoadCompletion(): Boolean {
        val terminal = _state.first { it !is SlicerState.Loading }
        return terminal !is SlicerState.Error
    }
```

Add the import for `File` at the top of the file if not already present:

```kotlin
import java.io.File
```

(Most likely already imported — verify before adding.)

- [ ] **Step 4: Run the integration test to verify it passes**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest --no-daemon \
  -Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.SessionResumeIntegrationTest
```

Expected: 3 tests pass.

- [ ] **Step 5: Run a broader regression sweep — slicing tests**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest --no-daemon \
  -Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.slicing.SlicingIntegrationTest
```

Expected: 50 tests pass. The F89 changes hook into mutators but don't change slicing behaviour; this verifies no regression.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/u1/slicer/SlicerViewModel.kt app/src/androidTest/java/com/u1/slicer/SessionResumeIntegrationTest.kt
git commit -m "$(cat <<'EOF'
F89: SlicerViewModel — resume offer + restoreSession()

Adds the read half of F89. On init, reads the saved session; if the
source file is still on disk, exposes a SessionResumeOffer for the
banner UI. If the file is gone, emits a one-time toast + clears the
stale entry.

acceptSessionResume() replays the saved state via the existing public
mutators in order: loadModelFromFile → selectPlate → addModelFromFile×N
→ setModelScale/Rotation/CopyCount → applyPlacementPositions. The order
matters — scale/rotation/copies reset customObjectPositions, so the
placement step must come last.

awaitLoadCompletion() suspends until _state leaves Loading; restore
aborts cleanly on Error and leaves the session in place so the user
can retry.

3 instrumented tests in SessionResumeIntegrationTest cover the offer-
exposure, missing-file-toast, and dismiss paths.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: UI — SessionResumeBanner + MainActivity wiring

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/MainActivity.kt`

- [ ] **Step 1: Add the `SessionResumeBanner` composable**

Open `app/src/main/java/com/u1/slicer/MainActivity.kt`. Find the `StaleSliceBanner` composable at line ~4874. Right before it (or right after — same file area), add:

```kotlin
// =============================================================================
// Session Resume Banner (F89) — shown on launch when a previous session is
// recoverable. Tap Resume → ViewModel replays the saved state; tap × → clear.
// =============================================================================
@Composable
fun SessionResumeBanner(
    offer: SlicerViewModel.SessionResumeOffer,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val plateSuffix = offer.plateId?.let { " · plate $it" } ?: ""
        Text(
            "Resuming ${offer.modelName}$plateSuffix",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onAccept) {
            Text("Resume", color = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Dismiss resume",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
```

- [ ] **Step 2: Wire the banner into the Prepare tab**

Find the Prepare tab body — it's the home screen content rendered around line ~1500 onward. The banner needs to live above the main Prepare content when `state == Idle && offer != null`.

Locate the Prepare-tab `Column` that renders the empty-state content. Look near line ~1495 (`viewModel.reopenPlateSelector()`) and identify the wrapping `Column` for the Prepare content.

Inside `setContent { U1Theme { ... } }` (around line 480 onward), find the place where the Prepare tab body is composed. Collect the resume offer and state:

```kotlin
            val sessionResumeOffer by viewModel.sessionResumeOffer.collectAsState()
            val state by viewModel.state.collectAsState()
```

(Likely the `state` collection already exists — re-use it.)

Within the Prepare tab `Column`'s `content` lambda, at the very top of the column (above the action chips / "Load model" button), add:

```kotlin
                if (sessionResumeOffer != null && state is SlicerViewModel.SlicerState.Idle) {
                    SessionResumeBanner(
                        offer = sessionResumeOffer!!,
                        onAccept = { viewModel.acceptSessionResume() },
                        onDismiss = { viewModel.dismissSessionResume() },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
```

Note: the exact composable wrapper containing the Prepare content may be in a separate `@Composable fun` (e.g. `PrepareTabContent` or similar). If so, plumb `sessionResumeOffer`, `state`, `onAcceptResume`, and `onDismissResume` as parameters to that function. Use `Grep` for `"Load model"` or `PreviewEmptyState` in `MainActivity.kt` to locate the right insertion site.

- [ ] **Step 3: Wire the toast collector**

Inside the `setContent { U1Theme { ... } }` lambda, add a `LaunchedEffect(Unit)` that collects `viewModel.toastEvents`:

```kotlin
            val ctx = LocalContext.current
            LaunchedEffect(Unit) {
                viewModel.toastEvents.collect { msg ->
                    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                }
            }
```

Place this near other top-level effects inside `setContent`. If `LocalContext.current` is already obtained in scope, reuse the existing reference.

- [ ] **Step 4: Build to verify no compile errors**

```bash
./gradlew assembleDebug --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Install and smoke-check manually on device**

```bash
./gradlew installDebug --no-daemon -PdeviceSerial=43211JEKB16931
```

Manual verification on the Pixel 8a:
1. Launch app → no banner (no session).
2. Load Buzz / colored_3DBenchy / any file from the file picker.
3. Force-stop via `adb shell am force-stop com.u1.slicer.orca`.
4. Re-launch.
5. Banner appears showing "Resuming <name>" with Resume + × buttons.
6. Tap Resume → loading indicator → model returns.
7. Force-stop again, re-launch, tap × → banner gone, no model.
8. Re-launch → no banner.

If any step fails, debug before committing. Do not commit a broken UI.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/u1/slicer/MainActivity.kt
git commit -m "$(cat <<'EOF'
F89: SessionResumeBanner + MainActivity wiring

Composable matches StaleSliceBanner styling (rounded 8 dp,
secondaryContainer background, primary-coloured Resume action).
× icon button on the right for explicit dismiss.

Banner visibility gated on `sessionResumeOffer != null &&
state == Idle` — Resume tap transitions state to Loading and the
banner naturally hides without an explicit clear.

Toast events from the ViewModel surface via a LaunchedEffect
collector at the setContent level, producing the "file no longer
available" toast when the saved path was evicted.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Version bump + doc updates

**Files:**
- Modify: `app/build.gradle`
- Modify: `CLAUDE.md`
- Modify: `README.md` (only if test counts are listed there)

- [ ] **Step 1: Bump version**

Edit `app/build.gradle`:

Change line 15 from `versionCode 294` to `versionCode 295`.
Change line 16 from `versionName "2.5.0"` to `versionName "2.6.0"`.

- [ ] **Step 2: Update CLAUDE.md test counts and unit-test class list**

In `CLAUDE.md`:
- Change `1295 JVM unit tests` → `1305 JVM unit tests`.
- Change `327 instrumented tests` → `334 instrumented tests`.
- Change `### Unit tests (...) - 1237 tests across 82 classes` → `### Unit tests (...) - 1247 tests across 83 classes` (the class count and test count). (If the existing count of 1237 vs 1295 reflects an existing total/sub-class skew, only update the sub-section if the matching exact text is found. Otherwise just update the top-of-file totals.)
- Add a new line in the unit-test class list (after the closest matching `data/...` entry):

```
- `data/SessionStateTest.kt` (10) — F89 session-resume schema: toJson/fromJson round-trip (basic fields, FloatArray positions, empty/multi additionalFiles, all-nullables-null), malformed JSON returns null, missing version returns null, unknown schema version returns null, missing required modelName/rawInputPath returns null
```

- Change `### Instrumented tests (...) - 318 tests across 33 classes` → `### Instrumented tests (...) - 325 tests across 35 classes` if that matches exactly. (Match the actual current sub-count vs total skew.)

- Add new instrumented entries:

```
- `data/SessionStateRepositoryTest.kt` (4) — F89 DataStore round-trip: write_thenRead_returnsSameSessionState, read_emptyStore_returnsNull, clear_afterWrite_readReturnsNull, write_overwrites_prior
- `SessionResumeIntegrationTest.kt` (3) — F89 ViewModel restore flow: init_savedSessionWithExistingFile_exposesResumeOffer, init_savedSessionMissingFile_emitsToastAndClears, dismissSessionResume_clearsOfferAndDataStore
```

- [ ] **Step 3: Update README.md if it has test counts**

```bash
grep -n "1295\|327" d:/projects/u1-slicer-orca/README.md
```

If matches appear, update them to `1305` and `334` respectively.

- [ ] **Step 4: Run the full unit test suite one more time**

```bash
./gradlew testDebugUnitTest --no-daemon
```

Expected: 1305 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle CLAUDE.md README.md
git commit -m "$(cat <<'EOF'
F89: bump to v2.6.0, versionCode 295; update test counts

CLAUDE.md / README.md test totals: unit 1295 → 1305 (+10
SessionStateTest), instrumented 327 → 334 (+4
SessionStateRepositoryTest, +3 SessionResumeIntegrationTest).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Full regression — confidence check + Smoke-7 E2E

- [ ] **Step 1: Run full unit-test suite**

```bash
./gradlew testDebugUnitTest --no-daemon
```

Expected: 1305 / 1305 pass.

- [ ] **Step 2: Run full instrumented suite (single device, simplest)**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest --no-daemon
```

Expected: 334 / 334 pass.

If any test fails: investigate. Do not weaken assertions; do not skip tests; treat any failure as a regression. Per `CLAUDE.md`, there are no known pre-existing failures.

- [ ] **Step 3: Run Smoke-7 E2E batch**

Use the `u1-slicer-e2e-batch` skill or follow the canonical procedure in `E2E_TESTING.md`. Smoke-7 covers the seven golden-path files; F89 doesn't change slicing behaviour but does change the model-load orchestration, so a regression check is required per the spec.

- [ ] **Step 4: Update E2E results history**

After Smoke-7 passes, add the batch entry to `c:/tmp/e2e-results/batch-manual-e2e-2026-05-23.txt` (or the appropriate dated file) per the existing convention, and update `~/.claude/projects/d--projects-u1-slicer-orca/memory/e2e-results-history.md`.

- [ ] **Step 5: Commit any doc/history updates**

```bash
git add CLAUDE.md memory/ 2>/dev/null || true
git commit -m "F89: Smoke-7 confidence check pass on v2.6.0" || true
```

(The `|| true` is intentional — there may be nothing to commit beyond the memory note, which lives outside the repo.)

---

## Task 9: Merge to main + build release APK

- [ ] **Step 1: Rebase / merge feature branch to main**

```bash
git checkout main
git pull --rebase origin main
git merge --no-ff feature/f89-session-resume -m "$(cat <<'EOF'
F89: persist in-progress session + auto-resume on launch (#153)

v2.5.0 → v2.6.0 (versionCode 295). Pure-Kotlin DataStore-JSON session
persistence with a Resume banner on launch. Detail in
docs/superpowers/specs/2026-05-23-f89-session-resume-design.md.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
git push origin main
```

- [ ] **Step 2: Build the release APK**

```bash
./gradlew assembleRelease --no-daemon
```

- [ ] **Step 3: Copy the APK to the shared build output location**

```bash
cp app/build/outputs/apk/release/app-release.apk "G:/My Drive/claude/u1-slicer-v2.6.0.apk"
ls -la "G:/My Drive/claude/u1-slicer-v2.6.0.apk"
```

- [ ] **Step 4: Stop and request release authorization from Kevin**

**Do not run `gh release create` or push a tag.** Per `CLAUDE.md` and the [release-permission memory](../../../memory/feedback-release-permission.md), the release step is gated on explicit per-turn user authorization.

Surface a clear message to Kevin: "v2.6.0 APK built and staged at G:/My Drive/claude/u1-slicer-v2.6.0.apk. All tests pass. Smoke-7 clean. Ready to `gh release create v2.6.0` — say the word."

Wait for Kevin's explicit OK. Only then run:

```bash
gh release create v2.6.0 "G:/My Drive/claude/u1-slicer-v2.6.0.apk" \
  --title "v2.6.0" \
  --notes "$(cat <<'EOF'
F89: persist in-progress session + auto-resume on launch.

If Android kills the app between the Prepare step and your next launch
(low memory, swipe-from-recents), reopen the app and tap the new
"Resuming <model>" banner to get back to exactly where you were —
loaded model, plate selection, scale, rotation, copies, custom drag
placement, and any F77 added-to-bed files all restored.

GitHub #153.
EOF
)"
```

- [ ] **Step 5: Update BACKLOG.md to mark F89 DONE on release**

Edit `BACKLOG.md` F89 heading: `### F89: Persist in-progress session + auto-resume on launch (GitHub #153)` → append ` — DONE v2.6.0`.

Commit:

```bash
git add BACKLOG.md
git commit -m "backlog: F89 DONE v2.6.0"
git push origin main
```

Close GitHub issue #153 with a short reference to the release.

---

## Self-review

### Spec coverage

| Spec section | Task that implements it |
|---|---|
| Shared DataStore extension | Task 1 |
| SessionState data class + JSON round-trip | Task 2 |
| SessionStateRepository | Task 3 |
| ViewModel write path (snapshot + debounce + dirty hooks) | Task 4 |
| ViewModel restore path (init read + accept/dismiss + restoreSession) | Task 5 |
| Banner UI + MainActivity wiring + toast collector | Task 6 |
| Version bump + docs | Task 7 |
| Smoke-7 regression | Task 8 |
| Release APK + gated `gh release create` | Task 9 |
| Test counts in CLAUDE.md / README | Task 7 |
| BACKLOG entry marked DONE | Task 9 |

All spec sections accounted for.

### Placeholder scan

- No "TBD", "TODO", or vague-handwave steps.
- Each code block contains complete code; no "fill in details".
- Each test step contains the exact command and expected output.
- Imports are listed where they're new.

### Type consistency

- `SessionState` used consistently across Tasks 2-5.
- `SessionResumeOffer(modelName, plateId)` matches between Task 4 (declaration), Task 5 (creation), Task 6 (consumption).
- `_toastEvents` typed as `MutableSharedFlow<String>` in Task 4, consumed as `SharedFlow<String>` in Task 6 — consistent.
- `markSessionDirty()` signature unchanged across Tasks 4/5 — `private fun ... : Unit`.
- `awaitLoadCompletion(): Boolean` — declared and used in Task 5 only.
- `setModelScale(ModelScale)` / `setModelRotation(ModelRotation)` — existing public API, restored verbatim.

---

## Notes for the executing agent

- The Android working directory is `d:/projects/u1-slicer-orca`. Run all gradle / git commands from there.
- Use the Pixel 8a (`ANDROID_SERIAL=43211JEKB16931`) for default instrumented runs. NEVER use the NF22E1 (`NE12442001324`) — per `CLAUDE.local.md` that device is off-limits for tests.
- Per `CLAUDE.md`: do not weaken any test assertion to make it pass. Treat failures as regressions and investigate root cause.
- No native rebuild required. Do not touch `app/src/main/cpp/` or `jniLibs/`.
- The user (Kevin) is not a developer — surface user-facing UX confirmation only if a real ambiguity arises. Make sensible code-internal calls yourself.
- Do not run `gh release create` or push a tag without an explicit per-turn user OK. The APK build + stage to `G:/My Drive/claude/` is fine; the release step is not.
