# F78 Multi-Printer Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add multi-printer support to the U1 Slicer app — configure multiple Moonraker URLs, switch between them from a chip on the Printer tab, with per-printer extruder slot presets and nickname-prefixed notifications.

**Architecture:** Approach A from the design spec — keep one `PrinterRepository` and one `MoonrakerClient` (single-active connection model). Add a new `PrintersRepository` that owns the list of `Printer` entries and an `activeId` in DataStore. `PrinterRepository` observes the active printer and rebinds `client.baseUrl` on every switch. Migration on first launch of v2.4.0 reads the legacy `printer_url` + `extruder_presets` DataStore keys into a single "Printer 1" entry.

**Tech Stack:** Kotlin 1.9.22, Jetpack Compose, Material3, AndroidX DataStore Preferences, `org.json` for serialization, OkHttp/MoonrakerClient, JUnit4 for unit tests, MockWebServer for instrumented tests. No native rebuild.

**Spec:** [`docs/superpowers/specs/2026-05-22-f78-multi-printer-design.md`](../specs/2026-05-22-f78-multi-printer-design.md)

---

## File map

**Create:**
- `app/src/main/java/com/u1/slicer/data/Printer.kt` — `Printer` + `PrintersConfig` data classes + JSON round-trip
- `app/src/main/java/com/u1/slicer/data/PrintersRepository.kt` — config flow + add/update/delete/setActive + `runMigrationIfNeeded`
- `app/src/main/java/com/u1/slicer/ui/printer/ActivePrinterChip.kt` — chip composable shown above Printer tab content
- `app/src/main/java/com/u1/slicer/ui/printer/PrinterSwitcherSheet.kt` — Material3 bottom sheet listing all configured printers
- `app/src/main/java/com/u1/slicer/ui/printer/PrintersSettingsCard.kt` — Settings-screen list with add/edit/delete affordances
- `app/src/main/java/com/u1/slicer/ui/printer/PrinterEditDialog.kt` — add / edit / test-connection dialog
- `app/src/test/java/com/u1/slicer/data/PrintersRepositoryTest.kt` — 10 unit tests
- `app/src/androidTest/java/com/u1/slicer/printer/MultiPrinterIntegrationTest.kt` — 3 instrumented tests

**Modify:**
- `app/src/main/java/com/u1/slicer/AppContainer.kt` — instantiate `PrintersRepository`; trigger migration
- `app/src/main/java/com/u1/slicer/printer/PrinterRepository.kt` — consume `PrintersRepository.activePrinter`; rebind `baseUrl` on switch
- `app/src/main/java/com/u1/slicer/printer/PrinterViewModel.kt` — new `activeNickname` / `printerList` / `switchActivePrinter` / `addPrinter` / `updatePrinter` / `deletePrinter`; extruderPresets sourced per-printer
- `app/src/main/java/com/u1/slicer/AppEventNotifier.kt` — `buildTitle(event, nickname?, count)` for nickname prefix when count > 1
- `app/src/main/java/com/u1/slicer/ui/PrinterScreen.kt` — chip + bottom-sheet wiring; remove direct `SettingsRepository.extruderPresets` reads
- `app/src/main/java/com/u1/slicer/ui/SettingsScreen.kt` — add Printers section card
- `app/src/main/java/com/u1/slicer/data/SettingsBackup.kt` — bump schema to VERSION=2; export `printers` array + active id; backward-import VERSION=1
- `app/src/test/java/com/u1/slicer/data/SettingsBackupTest.kt` — 5 new test cases
- `app/build.gradle` — `versionCode 292 → 293`, `versionName "2.3.0" → "2.4.0"`
- `CLAUDE.md` — current release line + unit/instrumented test counts
- `BACKLOG.md` — mark F78 DONE on ship

---

## Task 1: `Printer` and `PrintersConfig` data classes

**Files:**
- Create: `app/src/main/java/com/u1/slicer/data/Printer.kt`
- Test:   `app/src/test/java/com/u1/slicer/data/PrinterTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/u1/slicer/data/PrinterTest.kt`:

```kotlin
package com.u1.slicer.data

import org.junit.Assert.*
import org.junit.Test

class PrinterTest {

    @Test
    fun `Printer round-trip through JSON preserves all fields`() {
        val p = Printer(
            id = "uuid-1",
            nickname = "Workshop",
            moonrakerUrl = "http://192.168.1.50",
            extruderPresets = listOf(
                ExtruderPreset(index = 0, color = "#FF0000", materialType = "PLA"),
                ExtruderPreset(index = 1, color = "#00FF00", materialType = "PETG"),
            ),
        )
        val json = Printer.toJsonObject(p).toString()
        val back = Printer.fromJsonObject(org.json.JSONObject(json))
        assertEquals(p, back)
    }

    @Test
    fun `PrintersConfig constructor rejects empty printer list`() {
        try {
            PrintersConfig(printers = emptyList(), activeId = "x")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("at least one"))
        }
    }

    @Test
    fun `PrintersConfig constructor rejects activeId not in list`() {
        val p = Printer(id = "uuid-1", nickname = "P1", moonrakerUrl = "http://x")
        try {
            PrintersConfig(printers = listOf(p), activeId = "uuid-2")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("activeId"))
        }
    }

    @Test
    fun `PrintersConfig round-trip through JSON preserves printers and active`() {
        val cfg = PrintersConfig(
            printers = listOf(
                Printer(id = "uuid-1", nickname = "P1", moonrakerUrl = "http://1"),
                Printer(id = "uuid-2", nickname = "P2", moonrakerUrl = "http://2"),
            ),
            activeId = "uuid-2",
        )
        val json = PrintersConfig.toJson(cfg)
        val back = PrintersConfig.fromJson(json)
        assertEquals(cfg, back)
    }
}
```

- [ ] **Step 2: Run tests, verify they fail**

Run:
```
./gradlew testDebugUnitTest --tests "com.u1.slicer.data.PrinterTest" --no-daemon
```
Expected: `compileDebugUnitTestKotlin` fails because `Printer`, `PrintersConfig`, `Printer.toJsonObject`, `Printer.fromJsonObject`, `PrintersConfig.toJson`, `PrintersConfig.fromJson` don't exist yet.

- [ ] **Step 3: Create the `Printer` and `PrintersConfig` data classes**

Create `app/src/main/java/com/u1/slicer/data/Printer.kt`:

```kotlin
package com.u1.slicer.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One configured U1 printer. Persisted as part of [PrintersConfig] in DataStore.
 * The id is a stable UUID generated at create time so renames don't break references.
 */
data class Printer(
    val id: String,
    val nickname: String,
    val moonrakerUrl: String,
    val extruderPresets: List<ExtruderPreset> = defaultExtruderPresets(),
) {
    companion object {
        fun toJsonObject(p: Printer): JSONObject = JSONObject().apply {
            put("id", p.id)
            put("nickname", p.nickname)
            put("moonrakerUrl", p.moonrakerUrl)
            put("extruderPresets", JSONArray(serializeExtruderPresets(p.extruderPresets)))
        }

        fun fromJsonObject(obj: JSONObject): Printer = Printer(
            id = obj.getString("id"),
            nickname = obj.getString("nickname"),
            moonrakerUrl = obj.getString("moonrakerUrl"),
            extruderPresets = parseExtruderPresets(
                obj.optJSONArray("extruderPresets")?.toString() ?: ""
            ),
        )
    }
}

/**
 * The list of all configured printers plus which one is currently active.
 * Invariants (enforced by the constructor):
 *  - printers must be non-empty
 *  - activeId must reference an id in printers
 */
data class PrintersConfig(
    val printers: List<Printer>,
    val activeId: String,
) {
    init {
        require(printers.isNotEmpty()) { "PrintersConfig requires at least one printer" }
        require(printers.any { it.id == activeId }) {
            "PrintersConfig activeId='$activeId' is not present in printers list"
        }
    }

    val active: Printer get() = printers.first { it.id == activeId }

    companion object {
        fun toJson(cfg: PrintersConfig): String = JSONObject().apply {
            val arr = JSONArray()
            cfg.printers.forEach { arr.put(Printer.toJsonObject(it)) }
            put("printers", arr)
            put("activeId", cfg.activeId)
        }.toString()

        fun fromJson(json: String): PrintersConfig {
            val obj = JSONObject(json)
            val arr = obj.getJSONArray("printers")
            val list = (0 until arr.length()).map { Printer.fromJsonObject(arr.getJSONObject(it)) }
            return PrintersConfig(printers = list, activeId = obj.getString("activeId"))
        }
    }
}
```

- [ ] **Step 4: Run tests, verify they pass**

Run:
```
./gradlew testDebugUnitTest --tests "com.u1.slicer.data.PrinterTest" --no-daemon
```
Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```
git add app/src/main/java/com/u1/slicer/data/Printer.kt app/src/test/java/com/u1/slicer/data/PrinterTest.kt
git commit -m "F78: add Printer + PrintersConfig data classes with JSON round-trip"
```

---

## Task 2: `PrintersRepository` core CRUD (add/update/delete/setActive, no migration yet)

**Files:**
- Create: `app/src/main/java/com/u1/slicer/data/PrintersRepository.kt`
- Test:   `app/src/test/java/com/u1/slicer/data/PrintersRepositoryTest.kt`
- Reference (read only): `app/src/main/java/com/u1/slicer/data/SettingsRepository.kt` for DataStore patterns

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/u1/slicer/data/PrintersRepositoryTest.kt`:

```kotlin
package com.u1.slicer.data

import org.junit.Assert.*
import org.junit.Test

class PrintersRepositoryTest {

    // ---- Pure-state helpers (PrintersRepository.applyAction) — DataStore is mocked
    //      out by working on PrintersConfig values directly. The actual DataStore
    //      wiring lives in an instrumented test (Task 13).

    @Test
    fun `applyAdd appends to list and does not change active`() {
        val initial = PrintersConfig(
            printers = listOf(Printer(id = "a", nickname = "A", moonrakerUrl = "http://a")),
            activeId = "a",
        )
        val newP = Printer(id = "b", nickname = "B", moonrakerUrl = "http://b")
        val next = PrintersRepository.applyAdd(initial, newP)
        assertEquals(listOf("a", "b"), next.printers.map { it.id })
        assertEquals("a", next.activeId)
    }

    @Test
    fun `applyUpdate replaces entry by id`() {
        val initial = PrintersConfig(
            printers = listOf(
                Printer(id = "a", nickname = "A-old", moonrakerUrl = "http://old"),
                Printer(id = "b", nickname = "B", moonrakerUrl = "http://b"),
            ),
            activeId = "a",
        )
        val updated = initial.printers[0].copy(nickname = "A-new", moonrakerUrl = "http://new")
        val next = PrintersRepository.applyUpdate(initial, updated)
        assertEquals("A-new", next.printers[0].nickname)
        assertEquals("http://new", next.printers[0].moonrakerUrl)
        assertEquals("B", next.printers[1].nickname)
        assertEquals("a", next.activeId)
    }

    @Test
    fun `applyDelete rejects the active printer`() {
        val initial = PrintersConfig(
            printers = listOf(
                Printer(id = "a", nickname = "A", moonrakerUrl = "http://a"),
                Printer(id = "b", nickname = "B", moonrakerUrl = "http://b"),
            ),
            activeId = "a",
        )
        try {
            PrintersRepository.applyDelete(initial, "a")
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("active"))
        }
    }

    @Test
    fun `applyDelete rejects deleting the last printer`() {
        val initial = PrintersConfig(
            printers = listOf(Printer(id = "a", nickname = "A", moonrakerUrl = "http://a")),
            activeId = "a",
        )
        try {
            PrintersRepository.applyDelete(initial, "a")
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("last") || e.message!!.contains("active"))
        }
    }

    @Test
    fun `applyDelete removes non-active and preserves active`() {
        val initial = PrintersConfig(
            printers = listOf(
                Printer(id = "a", nickname = "A", moonrakerUrl = "http://a"),
                Printer(id = "b", nickname = "B", moonrakerUrl = "http://b"),
            ),
            activeId = "a",
        )
        val next = PrintersRepository.applyDelete(initial, "b")
        assertEquals(listOf("a"), next.printers.map { it.id })
        assertEquals("a", next.activeId)
    }

    @Test
    fun `applySetActive switches active when id is known`() {
        val initial = PrintersConfig(
            printers = listOf(
                Printer(id = "a", nickname = "A", moonrakerUrl = "http://a"),
                Printer(id = "b", nickname = "B", moonrakerUrl = "http://b"),
            ),
            activeId = "a",
        )
        val next = PrintersRepository.applySetActive(initial, "b")
        assertEquals("b", next.activeId)
    }

    @Test
    fun `applySetActive is a no-op when id is unknown`() {
        val initial = PrintersConfig(
            printers = listOf(Printer(id = "a", nickname = "A", moonrakerUrl = "http://a")),
            activeId = "a",
        )
        val next = PrintersRepository.applySetActive(initial, "ghost")
        assertSame(initial, next)
    }
}
```

- [ ] **Step 2: Run tests, verify they fail**

Run:
```
./gradlew testDebugUnitTest --tests "com.u1.slicer.data.PrintersRepositoryTest" --no-daemon
```
Expected: compile fails because `PrintersRepository.applyAdd/applyUpdate/applyDelete/applySetActive` don't exist.

- [ ] **Step 3: Create the repository with pure-state helpers and DataStore wiring**

Create `app/src/main/java/com/u1/slicer/data/PrintersRepository.kt`:

```kotlin
package com.u1.slicer.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Owns the list of configured printers and the active-printer id.
 * Single DataStore key "printers_config_json" holds the entire PrintersConfig as JSON.
 *
 * The state-transition logic is exposed as pure companion-object helpers
 * (applyAdd / applyUpdate / applyDelete / applySetActive) so it can be
 * unit-tested without DataStore. The instance methods wrap them with the
 * DataStore.edit { } write.
 */
class PrintersRepository(private val context: Context) {

    private val key = stringPreferencesKey(KEY_NAME)

    val config: Flow<PrintersConfig?> = context.printersDataStore.data.map { prefs ->
        val raw = prefs[key]
        if (raw.isNullOrBlank()) null else runCatching { PrintersConfig.fromJson(raw) }.getOrNull()
    }

    /** Convenience: emits the active printer once config exists. */
    val activePrinter: Flow<Printer?> = config.map { it?.active }

    suspend fun add(printer: Printer) = mutate { applyAdd(it, printer) }
    suspend fun update(printer: Printer) = mutate { applyUpdate(it, printer) }
    suspend fun delete(id: String) = mutate { applyDelete(it, id) }
    suspend fun setActive(id: String) = mutate { applySetActive(it, id) }

    /** Overwrite the whole config. Used by migration and import paths. */
    suspend fun replace(cfg: PrintersConfig) {
        context.printersDataStore.edit { prefs ->
            prefs[key] = PrintersConfig.toJson(cfg)
        }
    }

    private suspend fun mutate(transform: (PrintersConfig) -> PrintersConfig) {
        context.printersDataStore.edit { prefs ->
            val raw = prefs[key] ?: return@edit
            val current = runCatching { PrintersConfig.fromJson(raw) }.getOrNull() ?: return@edit
            val next = transform(current)
            if (next !== current) {
                prefs[key] = PrintersConfig.toJson(next)
            }
        }
    }

    companion object {
        const val KEY_NAME = "printers_config_json"
        private const val TAG = "PrintersRepository"

        fun applyAdd(cfg: PrintersConfig, p: Printer): PrintersConfig =
            cfg.copy(printers = cfg.printers + p)

        fun applyUpdate(cfg: PrintersConfig, p: Printer): PrintersConfig =
            cfg.copy(printers = cfg.printers.map { if (it.id == p.id) p else it })

        fun applyDelete(cfg: PrintersConfig, id: String): PrintersConfig {
            check(id != cfg.activeId) {
                "Cannot delete the active printer (id=$id). Switch to another printer first."
            }
            check(cfg.printers.size > 1) {
                "Cannot delete the last remaining printer (id=$id)."
            }
            return cfg.copy(printers = cfg.printers.filterNot { it.id == id })
        }

        fun applySetActive(cfg: PrintersConfig, id: String): PrintersConfig {
            if (cfg.printers.none { it.id == id }) {
                Log.w(TAG, "setActive called with unknown id='$id'; ignoring")
                return cfg
            }
            if (id == cfg.activeId) return cfg
            return cfg.copy(activeId = id)
        }
    }
}

private val android.content.Context.printersDataStore by androidx.datastore.preferences.preferencesDataStore(
    name = "u1_slicer_settings"
)
```

Note: the DataStore name `u1_slicer_settings` matches the existing instance in `SettingsRepository.kt`, so the same underlying preferences file is used (the new key just lives alongside the existing ones).

- [ ] **Step 4: Run tests, verify they pass**

Run:
```
./gradlew testDebugUnitTest --tests "com.u1.slicer.data.PrintersRepositoryTest" --no-daemon
```
Expected: 7 tests pass.

- [ ] **Step 5: Commit**

```
git add app/src/main/java/com/u1/slicer/data/PrintersRepository.kt app/src/test/java/com/u1/slicer/data/PrintersRepositoryTest.kt
git commit -m "F78: PrintersRepository core CRUD with pure-state helpers + unit tests"
```

---

## Task 3: Migration from legacy DataStore keys

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/data/PrintersRepository.kt`
- Modify: `app/src/test/java/com/u1/slicer/data/PrintersRepositoryTest.kt`

- [ ] **Step 1: Add the failing migration tests**

Append to `app/src/test/java/com/u1/slicer/data/PrintersRepositoryTest.kt`:

```kotlin
    // ---- Migration helpers (pure functions of legacy values) ----

    @Test
    fun `migration with legacy URL and presets produces single Printer 1 entry`() {
        val legacyUrl = "http://192.168.1.50"
        val legacyPresetsJson = serializeExtruderPresets(listOf(
            ExtruderPreset(index = 0, color = "#FFAA00", materialType = "PETG"),
            ExtruderPreset(index = 1, color = "#0000FF", materialType = "PLA"),
            ExtruderPreset(index = 2, color = "#00FF00", materialType = "PLA"),
            ExtruderPreset(index = 3, color = "#FFFFFF", materialType = "PLA"),
        ))
        val cfg = PrintersRepository.buildMigratedConfig(
            legacyUrl = legacyUrl,
            legacyExtruderPresetsJson = legacyPresetsJson,
            idFactory = { "fixed-uuid-1" },
        )
        assertEquals(1, cfg.printers.size)
        assertEquals("Printer 1", cfg.printers[0].nickname)
        assertEquals(legacyUrl, cfg.printers[0].moonrakerUrl)
        assertEquals("#FFAA00", cfg.printers[0].extruderPresets[0].color)
        assertEquals("fixed-uuid-1", cfg.printers[0].id)
        assertEquals("fixed-uuid-1", cfg.activeId)
    }

    @Test
    fun `migration with blank legacy URL produces entry with empty URL`() {
        val cfg = PrintersRepository.buildMigratedConfig(
            legacyUrl = "",
            legacyExtruderPresetsJson = "",
            idFactory = { "fixed-uuid-1" },
        )
        assertEquals(1, cfg.printers.size)
        assertEquals("", cfg.printers[0].moonrakerUrl)
        // defaultExtruderPresets() always returns 4 slots
        assertEquals(4, cfg.printers[0].extruderPresets.size)
    }

    @Test
    fun `migration with no legacy values still produces a valid Printer 1`() {
        val cfg = PrintersRepository.buildMigratedConfig(
            legacyUrl = null,
            legacyExtruderPresetsJson = null,
            idFactory = { "fixed-uuid-1" },
        )
        assertEquals(1, cfg.printers.size)
        assertEquals("Printer 1", cfg.printers[0].nickname)
        assertEquals("", cfg.printers[0].moonrakerUrl)
    }
}
```

- [ ] **Step 2: Run tests, verify they fail**

Run:
```
./gradlew testDebugUnitTest --tests "com.u1.slicer.data.PrintersRepositoryTest" --no-daemon
```
Expected: compile fails — `PrintersRepository.buildMigratedConfig` doesn't exist.

- [ ] **Step 3: Add migration helpers**

In `app/src/main/java/com/u1/slicer/data/PrintersRepository.kt`, replace the existing `companion object` block (keep its existing contents) by appending these new members:

```kotlin
        /**
         * Build a single-printer PrintersConfig from the v2.3.x legacy DataStore
         * keys. Pure function — the caller is responsible for reading the legacy
         * values out of DataStore and writing the result back via [replace].
         *
         * @param legacyUrl value of the legacy `printer_url` key, or null/blank if absent
         * @param legacyExtruderPresetsJson value of the legacy `extruder_presets` key, or null/blank
         * @param idFactory generates the new UUID for the seeded entry — overridable for testing
         */
        fun buildMigratedConfig(
            legacyUrl: String?,
            legacyExtruderPresetsJson: String?,
            idFactory: () -> String = { java.util.UUID.randomUUID().toString() },
        ): PrintersConfig {
            val id = idFactory()
            val presets = parseExtruderPresets(legacyExtruderPresetsJson ?: "")
            val printer = Printer(
                id = id,
                nickname = "Printer 1",
                moonrakerUrl = legacyUrl ?: "",
                extruderPresets = presets,
            )
            return PrintersConfig(printers = listOf(printer), activeId = id)
        }
```

And add an instance method that reads the legacy keys + writes the migrated config (uses the existing `SettingsRepository.printerUrl` and `SettingsRepository.extruderPresets` flows):

```kotlin
    /**
     * Runs migration if `printers_config_json` is not yet present.
     * Idempotent — calling repeatedly is a no-op after the first successful run.
     */
    suspend fun runMigrationIfNeeded(settingsRepository: SettingsRepository) {
        val current = context.printersDataStore.data
            .map { it[key] }
            .first()
        if (!current.isNullOrBlank()) return  // already migrated

        val legacyUrl = settingsRepository.printerUrl.first()
        val legacyPresetsJson = settingsRepository.extruderPresetsJson.first()

        val cfg = buildMigratedConfig(legacyUrl = legacyUrl, legacyExtruderPresetsJson = legacyPresetsJson)
        replace(cfg)
    }
```

The new helper requires three additions:
1. Add the imports at the top of `PrintersRepository.kt`:
```kotlin
import kotlinx.coroutines.flow.first
```
2. `SettingsRepository.extruderPresetsJson: Flow<String>` doesn't exist today. Add it to `SettingsRepository.kt` so migration can read the raw JSON without re-parsing:
```kotlin
    /** F78 migration helper — returns the raw JSON string for the legacy presets key,
     *  or empty string if the key is unset. */
    val extruderPresetsJson: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.EXTRUDER_PRESETS] ?: ""
    }
```
Add it directly below the existing `val extruderPresets: Flow<List<ExtruderPreset>>` declaration.

- [ ] **Step 4: Run tests, verify they pass**

Run:
```
./gradlew testDebugUnitTest --tests "com.u1.slicer.data.PrintersRepositoryTest" --no-daemon
```
Expected: 10 tests pass (7 from Task 2 + 3 from Task 3).

- [ ] **Step 5: Commit**

```
git add app/src/main/java/com/u1/slicer/data/PrintersRepository.kt app/src/test/java/com/u1/slicer/data/PrintersRepositoryTest.kt app/src/main/java/com/u1/slicer/data/SettingsRepository.kt
git commit -m "F78: migration from legacy printer_url + extruder_presets keys"
```

---

## Task 4: Wire `PrintersRepository` into `AppContainer` and run migration on startup

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/AppContainer.kt`

- [ ] **Step 1: Inspect current `AppContainer`**

Read `app/src/main/java/com/u1/slicer/AppContainer.kt`. Verify it's the 22-line file from the design.

- [ ] **Step 2: Update `AppContainer` to expose the repo and run migration**

Replace `app/src/main/java/com/u1/slicer/AppContainer.kt` with:

```kotlin
package com.u1.slicer

import android.content.Context
import com.u1.slicer.data.AppDatabase
import com.u1.slicer.data.PrintersRepository
import com.u1.slicer.data.SettingsRepository
import com.u1.slicer.network.MoonrakerClient
import com.u1.slicer.printer.PrinterRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    val settingsRepository = SettingsRepository(context)
    val moonrakerClient = MoonrakerClient()
    val printersRepository = PrintersRepository(context.applicationContext)
    val printerRepository = PrinterRepository(
        context.applicationContext,
        moonrakerClient,
        settingsRepository,
        printersRepository,
    )

    val database = AppDatabase.getInstance(context)
    val filamentDao = database.filamentDao()
    val sliceJobDao = database.sliceJobDao()

    val aiPaintViewModel by lazy {
        com.u1.slicer.aipaint.AiPaintViewModel(context.applicationContext as android.app.Application)
    }

    init {
        // F78: migration runs once per install — reads legacy DataStore keys
        // into a "Printer 1" entry on first launch of v2.4.0.
        CoroutineScope(Dispatchers.IO).launch {
            printersRepository.runMigrationIfNeeded(settingsRepository)
        }
    }
}
```

Note: the `PrinterRepository` constructor now takes a fourth parameter `printersRepository`. The compile will fail until Task 5 updates `PrinterRepository`. That's expected — Task 5 is the next task.

- [ ] **Step 3: Compile check (will fail at PrinterRepository call site)**

Run:
```
./gradlew assembleDebug --no-daemon
```
Expected: fails in `AppContainer.kt` because `PrinterRepository` only takes 3 args. This is the wedge we drive into Task 5.

- [ ] **Step 4: Skip commit until Task 5 completes the wedge**

Don't commit yet — the project doesn't build. Task 5 will fix `PrinterRepository` and we'll commit together at the end of Task 5.

---

## Task 5: `PrinterRepository` consumes `activePrinter`, rebinds `baseUrl` on switch

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/printer/PrinterRepository.kt`

- [ ] **Step 1: Read the current `PrinterRepository`**

Read `app/src/main/java/com/u1/slicer/printer/PrinterRepository.kt`. Confirm its `init {}` block at lines 40–48 collects `settingsRepo.printerUrl` and assigns `client.baseUrl`.

- [ ] **Step 2: Replace the init block and remove `updateUrl`**

Apply this Edit to `app/src/main/java/com/u1/slicer/printer/PrinterRepository.kt`:

Replace lines 11–55 (the class declaration through and including `updateUrl`) with:

```kotlin
class PrinterRepository(
    private val appContext: android.content.Context,
    private val client: MoonrakerClient,
    private val settingsRepo: SettingsRepository,
    private val printersRepo: com.u1.slicer.data.PrintersRepository,
) {
    private val _status = MutableStateFlow(PrinterStatus(state = "disconnected", progress = 0f))
    val status: StateFlow<PrinterStatus> = _status.asStateFlow()

    private val _printerUrl = MutableStateFlow("")
    val printerUrl: StateFlow<String> = _printerUrl.asStateFlow()

    /** Active printer's nickname — used to prefix notification titles. Empty until the first
     *  PrintersConfig emission. */
    private val _activeNickname = MutableStateFlow("")
    val activeNickname: StateFlow<String> = _activeNickname.asStateFlow()

    /** Total configured printer count — used to decide whether to prefix notifications. */
    private val _printerCount = MutableStateFlow(0)
    val printerCount: StateFlow<Int> = _printerCount.asStateFlow()

    private var pollingJob: Job? = null
    private var pollingScope: CoroutineScope? = null

    /**
     * When > 0 the polling loop uses 500 ms intervals instead of 2 000 ms.
     * Decremented each cycle; when it reaches 0 normal polling resumes.
     */
    @Volatile
    private var rapidPollCyclesRemaining = 0

    /**
     * Number of consecutive "disconnected" results from the printer.
     * PrinterOffline is only fired after OFFLINE_GRACE_FAILURES consecutive
     * failures, suppressing transient WiFi blips.
     */
    @Volatile
    private var consecutiveFailures = 0

    init {
        // F78: observe the active printer and rebind on every change. The collect
        // loop runs forever — first emission is the migrated PrintersConfig, every
        // subsequent emission is a user switch / edit / delete.
        CoroutineScope(Dispatchers.IO).launch {
            printersRepo.config.collect { cfg ->
                val active = cfg?.active ?: return@collect
                rebind(active.moonrakerUrl)
                _activeNickname.value = active.nickname
                _printerCount.value = cfg.printers.size
            }
        }
    }

    /**
     * Stop polling, swap `client.baseUrl`, reset status to "disconnected", restart polling
     * if it was running. The order matters: cancelling the existing poll job before
     * swapping the URL prevents an in-flight `getStatus()` call from writing into
     * the new printer's flow.
     */
    private suspend fun rebind(newUrl: String) {
        val normalized = MoonrakerClient.normalizeUrl(newUrl)
        if (normalized == _printerUrl.value && pollingJob?.isActive == true) return  // no-op
        val wasPolling = pollingJob?.isActive == true
        val scope = pollingScope
        stopPolling()
        consecutiveFailures = 0
        client.baseUrl = normalized
        _printerUrl.value = normalized
        _status.value = PrinterStatus(state = "disconnected", progress = 0f)
        if (wasPolling && scope != null) {
            startPolling(scope)
        }
    }

    /** Convenience: update the active printer's URL via PrintersRepository. */
    suspend fun updateActiveUrl(url: String) {
        val normalized = MoonrakerClient.normalizeUrl(url)
        val cfg = printersRepo.config.first() ?: return
        val active = cfg.active
        printersRepo.update(active.copy(moonrakerUrl = normalized))
        // The config collector in init will rebind automatically.
    }
```

You'll also need to:
1. Add these imports at the top of the file (after the existing `import kotlinx.coroutines.flow.*`):
```kotlin
import kotlinx.coroutines.flow.first
```
2. Update `startPolling` to remember its scope so `rebind` can restart polling against the new URL. Find `fun startPolling(scope: CoroutineScope)` and add `pollingScope = scope` as its first line.

- [ ] **Step 3: Compile check**

Run:
```
./gradlew assembleDebug --no-daemon
```
Expected: PASSES. The `AppContainer` change from Task 4 now compiles because `PrinterRepository` takes the 4th param.

- [ ] **Step 4: Re-run the existing `PrinterRepository`-touching unit tests**

Run:
```
./gradlew testDebugUnitTest --tests "com.u1.slicer.printer.*" --no-daemon
```
Expected: all pass. The existing tests test pure-helper logic (`shouldStartCameraKeepalive`, `shouldPollLedOnConnectionEdge`, `sanitizeCustomGcode`) — none touch `init` or `rebind`.

- [ ] **Step 5: Commit Tasks 4 + 5 together**

```
git add app/src/main/java/com/u1/slicer/AppContainer.kt app/src/main/java/com/u1/slicer/printer/PrinterRepository.kt
git commit -m "F78: PrinterRepository observes PrintersRepository.activePrinter and rebinds client.baseUrl on switch"
```

---

## Task 6: `PrinterViewModel` — new state flows + switch/add/update/delete methods

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/printer/PrinterViewModel.kt`

- [ ] **Step 1: Read the current `PrinterViewModel` constructor and `extruderPresets` declaration**

Find the existing `extruderPresets: StateFlow<List<ExtruderPreset>>` declaration (currently sourced from `SettingsRepository`).

- [ ] **Step 2: Replace the extruderPresets source and add new methods**

Apply the following changes:

1. Add this property `val activeNickname: StateFlow<String>` near the existing `val status` declaration, sourced directly from `printerRepo.activeNickname`:

```kotlin
    val activeNickname: StateFlow<String> = printerRepo.activeNickname

    val printerCount: StateFlow<Int> = printerRepo.printerCount

    /** Full list of configured printers — drives the switcher bottom sheet. */
    val printerList: StateFlow<List<com.u1.slicer.data.Printer>> = printersRepo.config
        .map { it?.printers ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val activePrinterId: StateFlow<String?> = printersRepo.config
        .map { it?.activeId }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
```

2. Change the existing `val extruderPresets` declaration to source from the active printer instead of SettingsRepository. Find:
```kotlin
    val extruderPresets: StateFlow<List<ExtruderPreset>> = settingsRepository.extruderPresets
        .stateIn(viewModelScope, SharingStarted.Lazily, defaultExtruderPresets())
```
Replace with:
```kotlin
    val extruderPresets: StateFlow<List<ExtruderPreset>> = printersRepo.activePrinter
        .map { it?.extruderPresets ?: defaultExtruderPresets() }
        .stateIn(viewModelScope, SharingStarted.Lazily, defaultExtruderPresets())
```

3. Constructor: add `printersRepo: PrintersRepository` parameter. AppContainer constructs the ViewModel; locate the factory and update it. If the ViewModel is currently created with `AndroidViewModel`-style `viewModelFactory`, add `printersRepo` as a constructor parameter and wire it through.

4. Add new methods:

```kotlin
    fun switchActivePrinter(id: String) {
        viewModelScope.launch { printersRepo.setActive(id) }
    }

    fun addPrinter(nickname: String, url: String) {
        viewModelScope.launch {
            val printer = com.u1.slicer.data.Printer(
                id = java.util.UUID.randomUUID().toString(),
                nickname = nickname.ifBlank { "Printer ${(printerList.value.size + 1)}" },
                moonrakerUrl = MoonrakerClient.normalizeUrl(url),
            )
            printersRepo.add(printer)
        }
    }

    fun updatePrinter(id: String, nickname: String, url: String) {
        viewModelScope.launch {
            val current = printerList.value.firstOrNull { it.id == id } ?: return@launch
            printersRepo.update(current.copy(
                nickname = nickname,
                moonrakerUrl = MoonrakerClient.normalizeUrl(url),
            ))
        }
    }

    fun deletePrinter(id: String) {
        viewModelScope.launch {
            try {
                printersRepo.delete(id)
            } catch (e: IllegalStateException) {
                _heaterError.value = e.message ?: "Cannot delete printer"
            }
        }
    }

    /** Used by the per-extruder slot editor — writes back into the active printer's
     *  extruderPresets list. Replaces the old `settingsRepo.saveExtruderPresets` path. */
    fun updateExtruderPreset(preset: ExtruderPreset) {
        viewModelScope.launch {
            val cfg = printersRepo.config.first() ?: return@launch
            val active = cfg.active
            val updated = active.extruderPresets.map { if (it.index == preset.index) preset else it }
            printersRepo.update(active.copy(extruderPresets = updated))
        }
    }
```

5. Add needed imports near the top of `PrinterViewModel.kt`:
```kotlin
import com.u1.slicer.data.PrintersRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
```

6. The existing `updateExtruderPreset` method previously called `settingsRepository.saveExtruderPresets`. If the same method name exists, replace its body with the new one shown above. If there's also a `loadInitialExtruderPresets` or similar that primes SettingsRepository on first launch, leave it — it's now harmless dead code because the slot UI reads from `printersRepo.activePrinter.extruderPresets`, but removing it can wait until v2.5 cleanup.

- [ ] **Step 3: Compile check**

Run:
```
./gradlew assembleDebug --no-daemon
```
Expected: compiles. There may be additional call-sites in `MainActivity.kt` that pass the AppContainer's `printerRepository` to the ViewModel factory — those need `printersRepository` too. Add as needed; the compile error will name the call site.

- [ ] **Step 4: Re-run unit tests**

Run:
```
./gradlew testDebugUnitTest --no-daemon
```
Expected: all 1273+ tests pass.

- [ ] **Step 5: Commit**

```
git add app/src/main/java/com/u1/slicer/printer/PrinterViewModel.kt app/src/main/java/com/u1/slicer/MainActivity.kt
git commit -m "F78: PrinterViewModel exposes printerList + switch/add/update/delete + per-printer extruder presets"
```

---

## Task 7: `SettingsBackup` schema VERSION=2 with bidirectional compat

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/data/SettingsBackup.kt`
- Modify: `app/src/test/java/com/u1/slicer/data/SettingsBackupTest.kt`

- [ ] **Step 1: Add the failing tests**

Open `app/src/test/java/com/u1/slicer/data/SettingsBackupTest.kt`. Append:

```kotlin
    // ---- F78: VERSION=2 multi-printer backups ----

    @Test
    fun `v1 backup imports as a single printer with legacy values`() {
        val v1Json = """
            {
              "version": 1,
              "printerUrl": "http://10.0.0.5",
              "extruderPresets": [
                {"index":0,"color":"#FF0000","materialType":"PLA"},
                {"index":1,"color":"#00FF00","materialType":"PETG"},
                {"index":2,"color":"#0000FF","materialType":"PLA"},
                {"index":3,"color":"#FFFFFF","materialType":"PLA"}
              ]
            }
        """.trimIndent()
        val data = SettingsBackup.import(v1Json)
        val cfg = data.printersConfig
        assertNotNull(cfg)
        assertEquals(1, cfg!!.printers.size)
        assertEquals("Printer 1", cfg.printers[0].nickname)
        assertEquals("http://10.0.0.5", cfg.printers[0].moonrakerUrl)
        assertEquals("#FF0000", cfg.printers[0].extruderPresets[0].color)
    }

    @Test
    fun `v2 backup imports all printers and preserves active`() {
        val v2Json = """
            {
              "version": 2,
              "printers": [
                {"id":"a","nickname":"P1","moonrakerUrl":"http://1","extruderPresets":[]},
                {"id":"b","nickname":"P2","moonrakerUrl":"http://2","extruderPresets":[]}
              ],
              "activePrinterId": "b",
              "printerUrl": "http://2",
              "extruderPresets": []
            }
        """.trimIndent()
        val data = SettingsBackup.import(v2Json)
        val cfg = data.printersConfig
        assertNotNull(cfg)
        assertEquals(2, cfg!!.printers.size)
        assertEquals("b", cfg.activeId)
    }

    @Test
    fun `v2 export includes legacy printerUrl from active printer for v1 rollback`() {
        val cfg = PrintersConfig(
            printers = listOf(
                Printer(id = "a", nickname = "P1", moonrakerUrl = "http://1"),
                Printer(id = "b", nickname = "P2", moonrakerUrl = "http://2"),
            ),
            activeId = "b",
        )
        val json = SettingsBackup.export(SettingsBackup.BackupData(
            sliceConfig = null,
            slicingOverrides = null,
            printersConfig = cfg,
            filamentProfiles = null,
            makerWorldCookies = null,
        ))
        val obj = org.json.JSONObject(json)
        assertEquals(2, obj.getInt("version"))
        // Legacy duplicate fields populated from the active printer:
        assertEquals("http://2", obj.getString("printerUrl"))
        // Extra: full printers array also present
        assertEquals(2, obj.getJSONArray("printers").length())
        assertEquals("b", obj.getString("activePrinterId"))
    }

    @Test
    fun `v2 backup with both printers array and legacy fields uses the printers array`() {
        val json = """
            {
              "version": 2,
              "printers": [
                {"id":"new","nickname":"Modern","moonrakerUrl":"http://new","extruderPresets":[]}
              ],
              "activePrinterId": "new",
              "printerUrl": "http://stale-legacy",
              "extruderPresets": []
            }
        """.trimIndent()
        val data = SettingsBackup.import(json)
        val cfg = data.printersConfig
        assertNotNull(cfg)
        assertEquals(1, cfg!!.printers.size)
        assertEquals("Modern", cfg.printers[0].nickname)
        assertEquals("http://new", cfg.printers[0].moonrakerUrl)
    }

    @Test
    fun `unsupported backup version throws clear error`() {
        val json = """{"version": 999}"""
        try {
            SettingsBackup.import(json)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("version"))
        }
    }
```

You also need to update the imports for the test file:
```kotlin
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrintersConfig
```

- [ ] **Step 2: Run tests, verify they fail**

Run:
```
./gradlew testDebugUnitTest --tests "com.u1.slicer.data.SettingsBackupTest" --no-daemon
```
Expected: compile fails (`SettingsBackup.BackupData` doesn't have `printersConfig` yet; `SettingsBackup.export` doesn't exist).

- [ ] **Step 3: Bump SettingsBackup to VERSION=2**

In `app/src/main/java/com/u1/slicer/data/SettingsBackup.kt`:

1. Update `private const val VERSION = 1` to `private const val VERSION = 2`.
2. Update `BackupData`:
```kotlin
    data class BackupData(
        val sliceConfig: SliceConfig?,
        val slicingOverrides: SlicingOverrides?,
        val printersConfig: PrintersConfig?,
        val filamentProfiles: List<FilamentProfile>?,
        val makerWorldCookies: String? = null,
    )
```
Notice: the old `printerUrl: String?` and `extruderPresets: List<ExtruderPreset>?` fields are folded into `printersConfig`. Replace every caller (search the codebase: `SettingsBackup.BackupData(` and any field-name read like `.printerUrl` / `.extruderPresets`).

3. Replace the `import(json)` body with:
```kotlin
    fun import(json: String): BackupData {
        val root = JSONObject(json)
        val version = root.optInt("version", 0)
        if (version < 1 || version > VERSION) {
            throw IllegalArgumentException("Unsupported backup version: $version (this app supports 1..$VERSION)")
        }

        val printersConfig: PrintersConfig? = when {
            // v2+ with explicit printers array
            root.has("printers") && root.has("activePrinterId") -> {
                val arr = root.getJSONArray("printers")
                val list = (0 until arr.length()).map { Printer.fromJsonObject(arr.getJSONObject(it)) }
                PrintersConfig(printers = list, activeId = root.getString("activePrinterId"))
            }
            // v1 — synthesize from legacy fields
            root.has("printerUrl") || root.has("extruderPresets") -> {
                val legacyUrl = if (root.has("printerUrl")) root.getString("printerUrl") else ""
                val legacyPresetsJson = if (root.has("extruderPresets"))
                    root.getJSONArray("extruderPresets").toString() else ""
                PrintersRepository.buildMigratedConfig(
                    legacyUrl = legacyUrl,
                    legacyExtruderPresetsJson = legacyPresetsJson,
                )
            }
            else -> null
        }

        return BackupData(
            sliceConfig = root.optJSONObject("sliceConfig")?.let { parseSliceConfig(it) },
            slicingOverrides = root.optJSONObject("slicingOverrides")?.let { SlicingOverrides.fromJson(it.toString()) },
            printersConfig = printersConfig,
            filamentProfiles = root.optJSONArray("filamentProfiles")?.let { parseFilamentProfilesArray(it) },
            makerWorldCookies = if (root.has("makerWorldCookies")) root.getString("makerWorldCookies") else null,
        )
    }
```

4. Add a new `export(data)` function (or update existing — `SettingsBackup` already builds JSON via `exportSliceConfig` etc., but the top-level orchestrator function may not exist by that exact name; search and replace whichever function builds the root JSON):

```kotlin
    fun export(data: BackupData): String {
        val root = JSONObject()
        root.put("version", VERSION)
        data.sliceConfig?.let { root.put("sliceConfig", exportSliceConfig(it)) }
        data.slicingOverrides?.let { root.put("slicingOverrides", JSONObject(it.toJson())) }
        data.filamentProfiles?.let { profiles ->
            val arr = JSONArray()
            profiles.forEach { arr.put(serializeFilamentProfile(it)) }
            root.put("filamentProfiles", arr)
        }
        data.makerWorldCookies?.let { root.put("makerWorldCookies", it) }

        data.printersConfig?.let { cfg ->
            // v2 schema: full list
            val arr = JSONArray()
            cfg.printers.forEach { arr.put(Printer.toJsonObject(it)) }
            root.put("printers", arr)
            root.put("activePrinterId", cfg.activeId)
            // v1 rollback compat: duplicate the active printer's URL + presets
            // into the legacy top-level fields so a v2.3.x install can import this file.
            val active = cfg.active
            root.put("printerUrl", active.moonrakerUrl)
            root.put("extruderPresets", JSONArray(serializeExtruderPresets(active.extruderPresets)))
        }

        return root.toString()
    }
```

5. Add required imports at top of `SettingsBackup.kt`:
```kotlin
// Already has org.json imports. Add:
// (PrintersConfig / Printer / PrintersRepository are in the same package — no import needed)
```

- [ ] **Step 4: Update call sites of the old BackupData**

Search the codebase for `.printerUrl` and `.extruderPresets` reads on `BackupData` and update to use `data.printersConfig?.active?.moonrakerUrl` / `.extruderPresets` respectively. Likely caller: `SettingsScreen.kt` import/export flow. Update applies to writes too — old code probably calls `settingsRepository.savePrinterUrl(data.printerUrl)`; replace with `printersRepository.replace(data.printersConfig)`.

- [ ] **Step 5: Run tests, verify they pass**

Run:
```
./gradlew testDebugUnitTest --tests "com.u1.slicer.data.SettingsBackupTest" --no-daemon
```
Expected: all SettingsBackupTest tests pass (15 existing + 5 new = 20).

- [ ] **Step 6: Commit**

```
git add app/src/main/java/com/u1/slicer/data/SettingsBackup.kt app/src/test/java/com/u1/slicer/data/SettingsBackupTest.kt app/src/main/java/com/u1/slicer/ui/SettingsScreen.kt
git commit -m "F78: SettingsBackup schema VERSION=2 with bidirectional v1/v2 compat"
```

---

## Task 8: `ActivePrinterChip` composable

**Files:**
- Create: `app/src/main/java/com/u1/slicer/ui/printer/ActivePrinterChip.kt`

- [ ] **Step 1: Create the composable**

```kotlin
package com.u1.slicer.ui.printer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * F78 chip rendered at the top of the Printer tab. Shows the active printer's
 * nickname with a dropdown indicator. Tapping opens [PrinterSwitcherSheet].
 *
 * Hidden when only one printer is configured (printerCount <= 1) — single-printer
 * users see no chip.
 */
@Composable
fun ActivePrinterChip(
    activeNickname: String,
    printerCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (printerCount <= 1) return
    Surface(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Print, null, modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(6.dp))
            Text(
                text = activeNickname.ifBlank { "Printer" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(2.dp))
            Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}
```

- [ ] **Step 2: Compile check**

Run:
```
./gradlew assembleDebug --no-daemon
```
Expected: compiles.

- [ ] **Step 3: Commit**

```
git add app/src/main/java/com/u1/slicer/ui/printer/ActivePrinterChip.kt
git commit -m "F78: ActivePrinterChip composable (hidden when printerCount<=1)"
```

---

## Task 9: `PrinterSwitcherSheet` bottom sheet

**Files:**
- Create: `app/src/main/java/com/u1/slicer/ui/printer/PrinterSwitcherSheet.kt`

- [ ] **Step 1: Create the bottom sheet**

```kotlin
package com.u1.slicer.ui.printer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.u1.slicer.data.Printer

/**
 * F78 bottom sheet. Lists all configured printers with a check next to the active one
 * and a one-line status hint per row ("Currently printing" if a print is in progress
 * on that printer at the moment the sheet opens).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterSwitcherSheet(
    printers: List<Printer>,
    activeId: String?,
    activePrintingFilename: String?,  // non-null if a print is running on the active printer
    onSelect: (Printer) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Switch printer", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Switching only changes which printer the app is watching. " +
                "A running print continues on its physical printer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
            Spacer(Modifier.height(12.dp))
            printers.forEach { printer ->
                val isActive = printer.id == activeId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(printer); onDismiss() }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Print, null, modifier = Modifier.size(20.dp),
                        tint = if (isActive) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(printer.nickname, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            printer.moonrakerUrl.ifBlank { "(no URL set)" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        if (isActive && activePrintingFilename != null) {
                            Text(
                                "Currently printing: $activePrintingFilename",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                    if (isActive) {
                        Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Compile check**

Run:
```
./gradlew assembleDebug --no-daemon
```
Expected: compiles.

- [ ] **Step 3: Commit**

```
git add app/src/main/java/com/u1/slicer/ui/printer/PrinterSwitcherSheet.kt
git commit -m "F78: PrinterSwitcherSheet bottom sheet for switching active printer"
```

---

## Task 10: Wire chip + sheet into `PrinterScreen`

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/ui/PrinterScreen.kt`

- [ ] **Step 1: Add the chip and sheet state**

In `PrinterScreen.kt`, near the top of the existing `Composable fun PrinterScreen(...)`, after the existing `val status by viewModel.status.collectAsState()` and friends, add:

```kotlin
    val activeNickname by viewModel.activeNickname.collectAsState()
    val printerCount by viewModel.printerCount.collectAsState()
    val printerList by viewModel.printerList.collectAsState()
    val activePrinterId by viewModel.activePrinterId.collectAsState()
    var showSwitcher by remember { mutableStateOf(false) }
```

- [ ] **Step 2: Add the chip inside the Scaffold's TopAppBar `actions` block, BEFORE the LED toolbar icon**

Locate the `actions = { ... }` block in the `TopAppBar`. Add as the first item:

```kotlin
                    com.u1.slicer.ui.printer.ActivePrinterChip(
                        activeNickname = activeNickname,
                        printerCount = printerCount,
                        onClick = { showSwitcher = true },
                    )
```

- [ ] **Step 3: Add the sheet conditionally before the bottom of the Scaffold body**

Inside the `Column` that fills the Scaffold, after `if (showSkipSheet) { ... }` add:

```kotlin
    if (showSwitcher) {
        com.u1.slicer.ui.printer.PrinterSwitcherSheet(
            printers = printerList,
            activeId = activePrinterId,
            activePrintingFilename = if (status.isPrinting) status.filename else null,
            onSelect = { selected -> viewModel.switchActivePrinter(selected.id) },
            onDismiss = { showSwitcher = false },
        )
    }
```

- [ ] **Step 4: Compile check**

Run:
```
./gradlew assembleDebug --no-daemon
```
Expected: compiles.

- [ ] **Step 5: Commit**

```
git add app/src/main/java/com/u1/slicer/ui/PrinterScreen.kt
git commit -m "F78: wire ActivePrinterChip + PrinterSwitcherSheet into PrinterScreen"
```

---

## Task 11: `PrintersSettingsCard` + `PrinterEditDialog` in Settings

**Files:**
- Create: `app/src/main/java/com/u1/slicer/ui/printer/PrintersSettingsCard.kt`
- Create: `app/src/main/java/com/u1/slicer/ui/printer/PrinterEditDialog.kt`
- Modify: `app/src/main/java/com/u1/slicer/ui/SettingsScreen.kt`

- [ ] **Step 1: Create `PrinterEditDialog`**

```kotlin
package com.u1.slicer.ui.printer

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.u1.slicer.data.Printer

/**
 * F78 add/edit printer dialog. When [existing] is null this is an add flow; otherwise edit.
 * Test-connection runs on-demand via [onTest] which returns null on success or an error string.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterEditDialog(
    existing: Printer?,
    onSave: (nickname: String, url: String) -> Unit,
    onTest: suspend (url: String) -> String?,
    onDismiss: () -> Unit,
) {
    var nickname by remember { mutableStateOf(existing?.nickname ?: "") }
    var url by remember { mutableStateOf(existing?.moonrakerUrl ?: "") }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add printer" else "Edit printer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = nickname, onValueChange = { nickname = it },
                    label = { Text("Nickname") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url, onValueChange = { url = it; testResult = null },
                    label = { Text("Moonraker URL") },
                    placeholder = { Text("http://192.168.1.50") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(
                        enabled = !testing && url.isNotBlank(),
                        onClick = {
                            testing = true; testResult = null
                            scope.launch {
                                testResult = onTest(url) ?: "OK"
                                testing = false
                            }
                        },
                    ) {
                        Text(if (testing) "Testing…" else "Test connection")
                    }
                    Spacer(Modifier.width(8.dp))
                    if (testResult != null) {
                        Text(
                            testResult!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (testResult == "OK") MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank() && nickname.isNotBlank(),
                onClick = { onSave(nickname, url); onDismiss() },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
```

Needed imports: standard Compose Material3 + `kotlinx.coroutines.launch`.

- [ ] **Step 2: Create `PrintersSettingsCard`**

```kotlin
package com.u1.slicer.ui.printer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.u1.slicer.data.Printer

/**
 * F78 Settings section: list of configured printers with edit/delete/add affordances.
 * Active printer is marked. Deleting the active or last printer is rejected with a snackbar
 * raised through the viewmodel's heaterError flow (existing path).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintersSettingsCard(
    printers: List<Printer>,
    activeId: String?,
    onAdd: (nickname: String, url: String) -> Unit,
    onEdit: (id: String, nickname: String, url: String) -> Unit,
    onDelete: (id: String) -> Unit,
    onTestConnection: suspend (url: String) -> String?,
) {
    var editing by remember { mutableStateOf<Printer?>(null) }
    var addingNew by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Printers", fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = { addingNew = true }) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add")
                }
            }
            Spacer(Modifier.height(8.dp))
            printers.forEach { printer ->
                val isActive = printer.id == activeId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editing = printer }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            printer.nickname + (if (isActive) "  (active)" else ""),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(printer.moonrakerUrl.ifBlank { "(no URL set)" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    IconButton(onClick = { editing = printer }) {
                        Icon(Icons.Default.Edit, "Edit", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { onDelete(printer.id) }) {
                        Icon(Icons.Default.Delete, "Delete", modifier = Modifier.size(20.dp))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            }
        }
    }

    if (addingNew) {
        PrinterEditDialog(
            existing = null,
            onSave = { nick, url -> onAdd(nick, url) },
            onTest = onTestConnection,
            onDismiss = { addingNew = false },
        )
    }
    editing?.let { existing ->
        PrinterEditDialog(
            existing = existing,
            onSave = { nick, url -> onEdit(existing.id, nick, url) },
            onTest = onTestConnection,
            onDismiss = { editing = null },
        )
    }
}
```

- [ ] **Step 3: Wire the card into `SettingsScreen.kt`**

Search `SettingsScreen.kt` for the existing printer-URL row. Replace that row (and any related single-URL save logic) with:

```kotlin
            com.u1.slicer.ui.printer.PrintersSettingsCard(
                printers = printerList,
                activeId = activePrinterId,
                onAdd = { nick, url -> printerViewModel.addPrinter(nick, url) },
                onEdit = { id, nick, url -> printerViewModel.updatePrinter(id, nick, url) },
                onDelete = { id -> printerViewModel.deletePrinter(id) },
                onTestConnection = { url ->
                    // Test connection against a candidate URL without persisting it.
                    val client = com.u1.slicer.network.MoonrakerClient().apply { baseUrl = com.u1.slicer.network.MoonrakerClient.normalizeUrl(url) }
                    client.testConnection()
                },
            )
```

At the top of the composable, add:
```kotlin
    val printerList by printerViewModel.printerList.collectAsState()
    val activePrinterId by printerViewModel.activePrinterId.collectAsState()
```

- [ ] **Step 4: Compile check**

Run:
```
./gradlew assembleDebug --no-daemon
```
Expected: compiles.

- [ ] **Step 5: Commit**

```
git add app/src/main/java/com/u1/slicer/ui/printer/PrintersSettingsCard.kt app/src/main/java/com/u1/slicer/ui/printer/PrinterEditDialog.kt app/src/main/java/com/u1/slicer/ui/SettingsScreen.kt
git commit -m "F78: PrintersSettingsCard + PrinterEditDialog in Settings"
```

---

## Task 12: Send dialog shows "Send to \<nickname\>" subtitle

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/ui/FilamentMappingDialog.kt`

- [ ] **Step 1: Inspect the existing dialog title block**

Open `app/src/main/java/com/u1/slicer/ui/FilamentMappingDialog.kt`. Find the `AlertDialog` (or equivalent) declaration and locate its `title = { ... }` slot or topmost `Text` heading.

- [ ] **Step 2: Add a nickname subtitle parameter**

Add a new parameter to the `FilamentMappingDialog` composable signature:

```kotlin
fun FilamentMappingDialog(
    // ... existing parameters
    activeNickname: String = "",
    showNicknameInTitle: Boolean = false,
    // ...
) {
```

In the title block, replace the existing single title `Text(...)` with a `Column` that adds the subtitle when `showNicknameInTitle && activeNickname.isNotBlank()`:

```kotlin
    title = {
        Column {
            Text("Map filaments")  // or whatever the existing title is — keep it
            if (showNicknameInTitle && activeNickname.isNotBlank()) {
                Text(
                    "Send to $activeNickname",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
    },
```

- [ ] **Step 3: Update the caller to pass the nickname**

Search for `FilamentMappingDialog(` to find the call site (likely `MainActivity.kt`). Update the call:

```kotlin
    FilamentMappingDialog(
        // ... existing args
        activeNickname = activeNickname,            // from printerViewModel.activeNickname.collectAsState()
        showNicknameInTitle = printerCount > 1,    // from printerViewModel.printerCount.collectAsState()
    )
```

If the call site doesn't already collect `activeNickname` / `printerCount`, add the `collectAsState` calls near the top of the composable.

- [ ] **Step 4: Compile check**

Run:
```
./gradlew assembleDebug --no-daemon
```
Expected: compiles.

- [ ] **Step 5: Commit**

```
git add app/src/main/java/com/u1/slicer/ui/FilamentMappingDialog.kt app/src/main/java/com/u1/slicer/MainActivity.kt
git commit -m "F78: Send dialog shows 'Send to <nickname>' subtitle when >1 printer configured"
```

---

## Task 13: `AppEventNotifier` — nickname prefix when count > 1

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/AppEventNotifier.kt`
- Modify: `app/src/main/java/com/u1/slicer/printer/PrinterRepository.kt` (notify call site)
- Modify: `app/src/test/java/com/u1/slicer/AppEventNotifierTest.kt` (or add new test cases)

- [ ] **Step 1: Add the failing test**

Open `app/src/test/java/com/u1/slicer/AppEventNotifierTest.kt` (existing 13 tests). Append:

```kotlin
    @Test
    fun `f78 notification title is prefixed with nickname when printerCount greater than 1`() {
        val event = AppEventNotifier.Event.PrintComplete(filename = "foo.gcode")
        val title = AppEventNotifier.buildTitle(event, nickname = "Workshop", printerCount = 2)
        assertTrue("title should start with nickname prefix: '$title'",
            title.startsWith("Workshop —"))
    }

    @Test
    fun `f78 notification title is not prefixed when printerCount is 1`() {
        val event = AppEventNotifier.Event.PrintComplete(filename = "foo.gcode")
        val title = AppEventNotifier.buildTitle(event, nickname = "Workshop", printerCount = 1)
        assertFalse("title should NOT have nickname prefix: '$title'", title.contains(" — "))
    }

    @Test
    fun `f78 notification title is not prefixed when nickname is blank`() {
        val event = AppEventNotifier.Event.PrintComplete(filename = "foo.gcode")
        val title = AppEventNotifier.buildTitle(event, nickname = "", printerCount = 5)
        assertFalse("blank nickname should not prefix: '$title'", title.contains(" — "))
    }
```

- [ ] **Step 2: Run tests, verify they fail**

Run:
```
./gradlew testDebugUnitTest --tests "com.u1.slicer.AppEventNotifierTest" --no-daemon
```
Expected: compile fails — `buildTitle` doesn't take `nickname` / `printerCount` args yet.

- [ ] **Step 3: Update `buildTitle`**

In `AppEventNotifier.kt`, find the existing `buildTitle(event)` function (or whatever the title-building method is named). Update its signature:

```kotlin
    fun buildTitle(event: Event, nickname: String = "", printerCount: Int = 1): String {
        val base = when (event) {
            // ... existing branches unchanged
        }
        return if (nickname.isNotBlank() && printerCount > 1) "$nickname — $base" else base
    }
```

Also update `notify(context, event)` to take optional `nickname` / `printerCount` parameters with the same defaults, threading them into `buildTitle`.

- [ ] **Step 4: Thread nickname through in `PrinterRepository.startPolling`**

In `PrinterRepository.kt`, find the `event?.let { AppEventNotifier.notify(appContext, it) }` line in `startPolling`. Replace with:

```kotlin
                event?.let {
                    AppEventNotifier.notify(
                        appContext, it,
                        nickname = _activeNickname.value,
                        printerCount = _printerCount.value,
                    )
                }
```

- [ ] **Step 5: Run tests, verify they pass**

Run:
```
./gradlew testDebugUnitTest --tests "com.u1.slicer.AppEventNotifierTest" --no-daemon
```
Expected: all 16 AppEventNotifierTest tests pass.

- [ ] **Step 6: Commit**

```
git add app/src/main/java/com/u1/slicer/AppEventNotifier.kt app/src/main/java/com/u1/slicer/printer/PrinterRepository.kt app/src/test/java/com/u1/slicer/AppEventNotifierTest.kt
git commit -m "F78: notification titles prefixed with active printer nickname when >1 configured"
```

---

## Task 14: Instrumented integration test — switch printers under MockWebServer

**Files:**
- Create: `app/src/androidTest/java/com/u1/slicer/printer/MultiPrinterIntegrationTest.kt`

- [ ] **Step 1: Write the failing instrumented test**

```kotlin
package com.u1.slicer.printer

import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrintersConfig
import com.u1.slicer.data.PrintersRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

class MultiPrinterIntegrationTest {

    private lateinit var serverA: MockWebServer
    private lateinit var serverB: MockWebServer
    private lateinit var repo: PrintersRepository

    @Before fun setUp() {
        serverA = MockWebServer().apply { start() }
        serverB = MockWebServer().apply { start() }
        repo = PrintersRepository(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @After fun tearDown() {
        serverA.shutdown(); serverB.shutdown()
    }

    @Test fun migrationRunsOnceOnFirstLaunch() = runBlocking {
        // Pre: config is empty. After migration, config has exactly 1 printer.
        val before = repo.config.first()
        // Re-run migration against the existing app SettingsRepository (legacy keys
        // are empty for a fresh test process — result is a default Printer 1).
        repo.runMigrationIfNeeded(
            com.u1.slicer.data.SettingsRepository(
                InstrumentationRegistry.getInstrumentation().targetContext,
            ),
        )
        val after = repo.config.first()
        assertEquals(1, after!!.printers.size)
        // Re-running is idempotent
        repo.runMigrationIfNeeded(
            com.u1.slicer.data.SettingsRepository(
                InstrumentationRegistry.getInstrumentation().targetContext,
            ),
        )
        val afterSecond = repo.config.first()
        assertEquals(after.activeId, afterSecond!!.activeId)
    }

    @Test fun addPrinter_updatesPrinterListStateFlow() = runBlocking {
        val p1 = Printer(id = "a", nickname = "P1", moonrakerUrl = serverA.url("/").toString())
        val p2 = Printer(id = "b", nickname = "P2", moonrakerUrl = serverB.url("/").toString())
        repo.replace(PrintersConfig(printers = listOf(p1), activeId = "a"))
        repo.add(p2)
        val list = repo.config.first()!!.printers
        assertEquals(2, list.size)
        assertEquals("a", repo.config.first()!!.activeId)
    }

    @Test fun setActive_switchesActiveId() = runBlocking {
        val p1 = Printer(id = "a", nickname = "P1", moonrakerUrl = serverA.url("/").toString())
        val p2 = Printer(id = "b", nickname = "P2", moonrakerUrl = serverB.url("/").toString())
        repo.replace(PrintersConfig(printers = listOf(p1, p2), activeId = "a"))
        repo.setActive("b")
        assertEquals("b", repo.config.first()!!.activeId)
        assertNotEquals("a", repo.config.first()!!.activeId)
    }
}
```

- [ ] **Step 2: Run instrumented tests**

Verify a device is connected:
```
adb devices
```
Then run:
```
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.printer.MultiPrinterIntegrationTest --no-daemon
```
Expected: 3 tests pass.

- [ ] **Step 3: Commit**

```
git add app/src/androidTest/java/com/u1/slicer/printer/MultiPrinterIntegrationTest.kt
git commit -m "F78: instrumented tests for PrintersRepository migration + add + setActive"
```

---

## Task 15: Version bump, docs, BACKLOG entry, final commit

**Files:**
- Modify: `app/build.gradle`
- Modify: `CLAUDE.md`
- Modify: `BACKLOG.md`

- [ ] **Step 1: Bump version**

In `app/build.gradle`:

```
versionCode 293
versionName "2.4.0"
```

- [ ] **Step 2: Update CLAUDE.md**

Update the "Current release" line:
```
Current release: `v2.4.0` (`versionCode 293`)
```

Update test counts in the test list comment:
```
./gradlew testDebugUnitTest                        # 1273 JVM unit tests
./gradlew connectedDebugAndroidTest                # 321 instrumented tests — uses Orchestrator
```

- [ ] **Step 3: Move F78 entry to DONE in BACKLOG**

In `BACKLOG.md` find:
```
### F78: Multi-printer support — configure and switch between multiple printers (GitHub #110)
```
Replace its body with:
```markdown
### F78: Multi-printer support — configure and switch between multiple printers (GitHub #110) — DONE v2.4.0
- Shipped. Multiple Moonraker URLs supported via PrintersRepository + JSON-in-DataStore PrintersConfig. Chip at top of Printer tab opens a ModalBottomSheet of all configured printers; switching rebinds MoonrakerClient.baseUrl on the existing PrinterRepository. Settings has Printers section for add/edit/delete + test-connection. Per-printer extruder slot presets. Notifications prefixed with active printer's nickname when >1 configured. Migration on first launch of v2.4.0 reads legacy `printer_url` + `extruder_presets` into a "Printer 1" entry. SettingsBackup schema bumped to VERSION=2 with bidirectional v1/v2 compat. GitHub #110 closed.
```

- [ ] **Step 4: Close the GitHub issue**

```
gh issue close 110 --repo taylormadearmy/u1-slicer-for-android --comment "Shipped in v2.4.0. Multiple Moonraker URLs, switcher chip on Printer tab, per-printer extruder slot presets, nickname-prefixed notifications, SettingsBackup VERSION=2 with v1/v2 bidirectional compat. See BACKLOG F78."
```

- [ ] **Step 5: Full test sweep before tagging**

Run:
```
./gradlew testDebugUnitTest --no-daemon
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest --no-daemon
```
Expected: 1273 unit tests + 321 instrumented tests all pass.

- [ ] **Step 6: Final commit**

```
git add app/build.gradle CLAUDE.md BACKLOG.md
git commit -m "bump: v2.4.0 — F78 multi-printer support"
```

- [ ] **Step 7: Build release APK + copy to G drive**

```
./gradlew assembleRelease --no-daemon
cp app/build/outputs/apk/release/app-release.apk "/g/My Drive/claude/u1-slicer-v2.4.0.apk"
```

- [ ] **Step 8: Confidence check before shipping**

Per CLAUDE.md, do not push or run `gh release create` without explicit user authorization. Run the confidence check skill instead and report results.
