# F89 — Persist in-progress session + auto-resume on launch — Design

**Status:** Draft, 2026-05-23
**Issue:** [GitHub #153](https://github.com/taylormadearmy/u1-slicer-for-android/issues/153)
**Scope:** v2.5.0 → v2.6.0 (versionCode 294 → 295). Pure Kotlin / DataStore / Compose.
**Out of scope:** Option 2 (native model-state snapshot). That is documented as a potential future follow-up if F89 + B98 do not flatten the 90-second Buzz cold-load enough. Do not speculatively build it.

## TL;DR

Today the in-progress Prepare-screen state lives only in `SlicerViewModel` memory. When Android kills the app (low memory, swipe-from-recents, OOM during background slice), the user loses the loaded model, plate selection, scale, rotation, copy count, custom drag-placement, and any F77 additional files. For Buzz-class models that is 90 s of cold-load plus minutes of manual fiddling, gone.

F89 persists the Prepare-screen ephemeral state to a single DataStore JSON key on every relevant mutation (debounced), then on next launch offers a non-modal **"Resuming <model name>…"** banner with a **Resume** button and an explicit **×** dismiss. Tap Resume → re-run the existing F61 "reopen from Jobs" code path on the saved source file, then replay scale/rotation/copies/custom-placement/added-files. If the source file is no longer on disk → friendly Toast, no banner, fresh start.

No change to slice output. No native rebuild. No change to the slicer pipeline.

## User-facing behaviour

### Cold-launch with a saved session

1. User loads Buzz plate 8, drags it to a custom position, sets 2 copies, adds a second STL via "Add to bed". Walks away.
2. Android low-memory or swipe-from-recents kills the app.
3. User reopens U1 Slicer.
4. Home/Prepare tab opens normally — empty bed — and below the TopAppBar (above any other content) a slim Material3 surface appears:

   ```
   ┌─────────────────────────────────────────────────────────┐
   │  Resuming Buzz Lightyear.3mf · plate 8       Resume  ×  │
   └─────────────────────────────────────────────────────────┘
   ```

   The banner styling matches the existing `StaleSliceBanner` (rounded 8 dp, `secondaryContainer` background, primary-coloured action). No auto-dismiss timeout — Kevin's pattern in this app is "persistent affordance, explicit dismiss".

5. **Tap Resume** → standard loading indicator appears (`"Loading Buzz Lightyear.3mf…"`); the model loads via the native re-load path; the saved scale / rotation / copies / custom placement / additional files / plate are replayed in order; the user is back at the exact Prepare state, minus the 90 s wait. The session entry stays on disk — re-killing the app and reopening still offers Resume, until the user explicitly clears or starts a new file.

6. **Tap ×** → the session is cleared from DataStore; the banner hides; the app continues in its normal empty state.

7. **Load any other file** (file picker → `loadModel(uri)` or `loadModelFromFile(file)`) → the previous session is overwritten with the new file's session; the banner hides.

### Plate text in the banner

- Multi-plate Bambu file with a selected plate → `"Resuming <name> · plate <N>"`.
- Single-plate or STL/OBJ/STEP → `"Resuming <name>"` (no plate suffix).
- F77 added files are not shown in the banner (would bloat the line); they are restored silently.

### Source file no longer accessible

If `File(savedPath).exists()` returns false (Android cleared the cache, user uninstalled the source app that shared the file, etc.):

- No banner ever shows.
- A one-time `Toast.LENGTH_LONG` fires once the home screen draws: `"Couldn't resume <model name> — file no longer available"`.
- The stale session entry is cleared from DataStore at the same moment.

This matches Kevin's directive in the F89 brief: "don't try to recover from cache eviction silently".

### Mid-slice kill

If the app was killed while a slice was running:

- The slice itself is not resumed (no in-flight slicer state is persisted; that's a different problem and out of scope here — Room already tracks completed jobs).
- The Prepare-screen state at the time of the last mutation IS persisted, so the user lands back on the same model, same plate, same scale, etc., and can tap "Slice" again.
- The Resume banner copy and behaviour are identical to the cold-launch case.

### Slice complete → app killed → relaunch

- The Prepare-state persistence keeps writing through slice completion (the model is still loaded). So after a kill, Resume puts the user back in Prepare with the same model.
- Their finished gcode is still in Room (`SliceJob.gcodePath` is unchanged); they can find it under Jobs as before. This is a deliberate non-overlap: Jobs is "completed work", session is "in-progress work".

### Smart Paint round-trip

- Smart Paint replaces the source file via `cacheDir/ai_paint_<ts>.3mf` and then `loadModelFromFile(file, preserveDisplayName)` ([F88 follow-up](../../BACKLOG.md)). The session persistence treats that load like any other load — it captures the post-paint file path. If the cache is later evicted, the resume falls into the "source file no longer accessible" path, which is correct.

## What is persisted (and what is not)

### Persisted — ephemeral Prepare-screen state

| Field | Source in `SlicerViewModel` | Why |
|---|---|---|
| `modelName` | `currentModelName` | Banner label and toast text. |
| `rawInputPath` | `rawInputFile?.absolutePath` | The file we hand to `loadModelFromFile()` on resume. This is the original user-input file (never an intermediate). |
| `sourceModelPath` | `sourceModelFile?.absolutePath` | Tracked for symmetry with diagnostics; restore replays via `rawInputPath` so this is informational only. |
| `currentModelPath` | `_currentModelFile?.absolutePath` | Informational only — re-derived on load. Stored for diagnostics. |
| `multiPlateSourcePath` | `_multiPlateSourceFile?.absolutePath` | Informational. The plate-selection path rebuilds this from `rawInputFile`. |
| `selectedPlateId` | `recoveryPlateId.takeIf { it >= 0 }` | Replayed via `selectPlate(plateId)` after the initial load completes. |
| `modelScale` | `_modelScale.value` (x, y, z) | Replayed via `setModelScale`. |
| `modelRotation` | `_modelRotation.value` (x, y, z) | Replayed via `setModelRotation`. |
| `copyCount` | `_copyCount.value` | Replayed via `setCopyCount`. |
| `customObjectPositions` | `customObjectPositions` (`FloatArray?`) | Replayed via `applyPlacementPositions` if non-null. |
| `customWipeTowerPos` | `customWipeTowerPos` (`Pair<Float,Float>?`) | Replayed alongside `customObjectPositions`. |
| `additionalFiles` | `additionalModelFiles: MutableList<Pair<File, Int>>` (path, plateIdx) | Replayed via `addModelFromFile(file)` / `addModelFromFileForPlate(file, plateIdx)` after the primary load completes. |
| `savedAtEpochMs` | `System.currentTimeMillis()` at write | Diagnostic. |
| `appVersionCode` | `BuildConfig.VERSION_CODE` at write | Forward-compat — if a future schema break ships, we can reject sessions written by a different version. |

### NOT persisted (already covered elsewhere)

- **Completed slice results / gcode** — Room (`SliceJob` + durable `files/jobs/<id>/output.gcode`). Jobs tab and `reopenJobToEdit` already cover the "I want my finished gcode back" case.
- **Filament library, slot presets, slicing overrides** — already in DataStore via `SettingsRepository` / `PrintersRepository`. Survive process death by definition.
- **Plate type, AI Paint config** — in DataStore already.
- **Smart Paint cached intermediates** — `cacheDir/ai_paint_*.3mf`. If still present they re-resolve; if evicted, the resume falls into the "file no longer accessible" path.
- **Slice in flight** — never resumed. The slicer is not interruptible mid-flight in a useful way.
- **Native model state (in-memory geometry)** — explicitly Option 2 / out of scope. Resume re-runs the native re-load via `loadModelFromFile`.

## Architecture

### Shared DataStore extension

Today `SettingsRepository.kt` has the only `preferencesDataStore(name = "u1_slicer_settings")` declaration. Kevin's directive: do NOT create a second preferences file for session state. Reuse the existing `u1_slicer_settings` store.

`preferencesDataStore { }` is a property delegate factory and must only be invoked once per store name per process — invoking it twice with the same name yields two separate `DataStore` instances racing on the same backing file, which silently corrupts. Solution:

1. Lift the extension out of `SettingsRepository.kt` into a new shared file `app/src/main/java/com/u1/slicer/data/AppDataStore.kt`:

   ```kotlin
   package com.u1.slicer.data

   import android.content.Context
   import androidx.datastore.core.DataStore
   import androidx.datastore.preferences.core.Preferences
   import androidx.datastore.preferences.preferencesDataStore

   internal val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "u1_slicer_settings")
   ```

2. `SettingsRepository` and `SessionStateRepository` both reference `context.appDataStore`. The existing private alias in `SettingsRepository.kt` is removed; all field reads/writes inside it switch from `context.dataStore` to `context.appDataStore`. No behaviour change — the underlying DataStore is the same file.

`PrintersRepository` keeps its own `printers_config` store. F78 chose a separate file deliberately; F89 does not change that decision.

### `SessionState` data class

New file `app/src/main/java/com/u1/slicer/data/SessionState.kt`:

```kotlin
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

    companion object {
        const val SCHEMA_VERSION = 1

        fun toJson(state: SessionState): String { ... }     // org.json.JSONObject
        fun fromJson(json: String): SessionState?            // returns null on any parse failure
    }
}
```

Serialised via `org.json.JSONObject` — matches the existing `SlicingOverrides.toJson()` / `PrintersConfig.toJson()` convention (kotlinx-serialization is not on the production classpath; only as `testImplementation` for org.json). `customObjectPositions` is a `FloatArray` (override `equals` / `hashCode` to compare by content).

`fromJson` returns `null` for any of:

- malformed JSON
- missing `version` field
- `version != SCHEMA_VERSION` (future-proof: an older app reading a newer-schema session just starts fresh — safer than guessing)
- missing required fields (`modelName`, `rawInputPath`)

A `null` parse result is treated identically to "no session": no banner, no toast (silent fresh start). The bad blob stays in DataStore until the next mutation overwrites it. This is intentional — we'd rather lose a session than crash on a malformed read.

### `SessionStateRepository`

New file `app/src/main/java/com/u1/slicer/data/SessionStateRepository.kt`. Modelled directly on `PrintersRepository`:

```kotlin
class SessionStateRepository(private val context: Context) {
    private val key = stringPreferencesKey(KEY_NAME)

    val state: Flow<SessionState?> = context.appDataStore.data.map { prefs ->
        val raw = prefs[key]
        if (raw.isNullOrBlank()) null else SessionState.fromJson(raw)
    }

    suspend fun read(): SessionState? = state.first()

    suspend fun write(state: SessionState) {
        context.appDataStore.edit { it[key] = SessionState.toJson(state) }
    }

    suspend fun clear() {
        context.appDataStore.edit { it.remove(key) }
    }

    companion object {
        const val KEY_NAME = "session_state_json"
    }
}
```

The repository itself is thin and untestable on the JVM (DataStore needs Android). All non-trivial state-transition logic lives as pure helpers in the companion of `SessionState`:

- `SessionState.captureFrom(viewModelSnapshot: SessionSnapshot): SessionState` — pure function that takes a snapshot of every field above and constructs a `SessionState`. Unit-testable.
- `SessionState.toJson` / `fromJson` — round-trip-tested.

### `SlicerViewModel` write path

A new private member:

```kotlin
private val sessionStateRepository = SessionStateRepository(getApplication())
private val sessionSaveFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

init {
    // Debounced session-save. Per-drag-frame mutations during placement
    // can fire dozens of marks/second; we only want one DataStore write
    // ~500 ms after the user stops moving.
    viewModelScope.launch {
        sessionSaveFlow
            .debounce(500)
            .collectLatest {
                val snapshot = captureSessionSnapshot() ?: return@collectLatest
                runCatching { sessionStateRepository.write(snapshot) }
                    .onFailure { Log.w("SlicerVM", "Session save failed: ${it.message}") }
            }
    }
}

private fun markSessionDirty() {
    if (currentModelFile == null) return  // nothing meaningful to save
    sessionSaveFlow.tryEmit(Unit)
}

private fun captureSessionSnapshot(): SessionState? {
    val raw = rawInputFile ?: return null
    return SessionState(
        modelName = currentModelName,
        rawInputPath = raw.absolutePath,
        sourceModelPath = sourceModelFile?.absolutePath,
        currentModelPath = currentModelFile?.absolutePath,
        multiPlateSourcePath = _multiPlateSourceFile?.absolutePath,
        selectedPlateId = recoveryPlateId.takeIf { it >= 0 },
        modelScale = _modelScale.value.let { Triple(it.x, it.y, it.z) },
        modelRotation = _modelRotation.value.let { Triple(it.x, it.y, it.z) },
        copyCount = _copyCount.value,
        customObjectPositions = customObjectPositions?.copyOf(),
        customWipeTowerPos = customWipeTowerPos,
        additionalFiles = additionalModelFiles.map { (f, p) ->
            SessionState.AdditionalFile(f.absolutePath, p)
        },
        savedAtEpochMs = System.currentTimeMillis(),
        appVersionCode = BuildConfig.VERSION_CODE,
    )
}
```

`markSessionDirty()` is called from the existing mutators (no new public surface; these all already exist):

| Existing mutator | Reason to mark dirty |
|---|---|
| `loadModel(uri)` (after a model has finished loading — at the same point where `currentModelFile = ...` happens) | New session begins. |
| `loadModelFromFile(file, preserveDisplayName)` | Same. |
| `addModelFromFile(file)` / `addModelFromFileForPlate(file, plateIdx)` | `additionalModelFiles` grew. |
| `selectPlate(plateId)` | `recoveryPlateId` changed. |
| `setModelScale(scale)` | scale changed. |
| `setModelRotation(rotation)` | rotation changed. |
| `setCopyCount(count)` | copy count changed. |
| `applyPlacementPositions(positions, wipeTowerPos)` | drag finished (the call is fired on drag-end, not per-frame, but the debounce is belt-and-braces). |

Slice completion does NOT mark dirty separately — by that point the relevant Prepare state hasn't changed; the existing dirty entry from the user's last edit is what's on disk, which is exactly what we want.

`clearModel()` calls `sessionStateRepository.clear()` synchronously via `viewModelScope.launch { ... }` (no debounce — the user explicitly cleared, persist that immediately).

### Restore path

`SlicerViewModel` exposes:

```kotlin
private val _sessionResumeOffer = MutableStateFlow<SessionResumeOffer?>(null)
val sessionResumeOffer: StateFlow<SessionResumeOffer?> = _sessionResumeOffer.asStateFlow()

data class SessionResumeOffer(
    val modelName: String,
    val plateId: Int?,
)
```

On `init` (after the ViewModel is constructed), a coroutine runs:

```kotlin
viewModelScope.launch {
    val saved = sessionStateRepository.read() ?: return@launch
    val raw = File(saved.rawInputPath)
    if (!raw.exists()) {
        // Source file is gone; surface a toast and clear silently.
        sessionStateRepository.clear()
        _toastEvents.emit("Couldn't resume ${saved.modelName} — file no longer available")
        return@launch
    }
    // File is present; offer the banner. Don't load anything yet — wait for user tap.
    _sessionResumeOffer.value = SessionResumeOffer(
        modelName = saved.modelName,
        plateId = saved.selectedPlateId,
    )
}
```

`_toastEvents: MutableSharedFlow<String>` is a new event channel; `MainActivity` collects it and `Toast.makeText(...).show()`s the value.

Two public functions:

```kotlin
fun acceptSessionResume() {
    val offer = _sessionResumeOffer.value ?: return
    _sessionResumeOffer.value = null
    viewModelScope.launch {
        val saved = sessionStateRepository.read() ?: return@launch
        val raw = File(saved.rawInputPath)
        if (!raw.exists()) {
            sessionStateRepository.clear()
            _toastEvents.emit("Couldn't resume ${saved.modelName} — file no longer available")
            return@launch
        }
        restoreSession(saved, raw)
    }
}

fun dismissSessionResume() {
    _sessionResumeOffer.value = null
    viewModelScope.launch { sessionStateRepository.clear() }
}
```

`restoreSession(saved, raw)` does the replay:

1. `loadModelFromFile(raw, preserveDisplayName = saved.modelName)` — same call F61's `reopenJobToEdit` makes. This drives the existing Loading state, runs Bambu sanitization, embeds profile, calls native loader.
2. Suspend until the next `_state` value is `SlicerState.ModelLoaded` (or anything other than `Loading`). If it lands in `Error`, abort restore and clear the session.
3. If `saved.selectedPlateId != null` and the loaded model is multi-plate (`_multiPlatePlates.value` non-empty) and the saved plate matches an entry → `selectPlate(saved.selectedPlateId)` and wait for the next `ModelLoaded`.
4. For each `additionalFile` in order → `addModelFromFile(File(path))` or `addModelFromFileForPlate(File(path), plateIdx)` if `plateIdx >= 0`; skip the entry if the file no longer exists (the user could have added a file that has since been cleared — we don't fail the whole restore, just skip the missing one and log).
5. `setModelScale(ModelScale(saved.modelScale...))` — must come AFTER additional-file restoration because `setModelScale` resets `customObjectPositions`.
6. `setModelRotation(ModelRotation(saved.modelRotation...))` — same reason.
7. `setCopyCount(saved.copyCount)` — same reason.
8. If `saved.customObjectPositions != null && saved.customWipeTowerPos != null` → `applyPlacementPositions(positions, wipeTowerPos)`. This must be the LAST replay step because every other mutator clears `customObjectPositions`.

The restoration runs the existing public mutators, so all the existing invariants (cache invalidation, `_modelAddVersion` bumps, debounced session saves) fire as expected. The debounced saves during restore are harmless — they will simply re-write the same session blob a few times.

### Banner UI

New composable in `MainActivity.kt`, right next to `StaleSliceBanner`:

```kotlin
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
            Icon(Icons.Default.Close, contentDescription = "Dismiss resume")
        }
    }
}
```

Wired into the home screen content (inside the existing `Column` that holds the Prepare tab) above the empty-state graphic. Visibility rule: shown only when `sessionResumeOffer != null && state is SlicerState.Idle`. The "Idle" guard hides the banner the moment Resume is tapped (state transitions to Loading) without an explicit hide call.

`MainActivity` also collects `viewModel.toastEvents` in a `LaunchedEffect(Unit)` block at the same top level where pending intents are handled, and pops a `Toast.LENGTH_LONG`.

### Mutation hot path coverage

For belt-and-braces: rather than scattering `markSessionDirty()` calls across eight mutators (which risks future drift if a new mutator is added without a session save), the design adds a single internal `wireSessionPersistence()` helper called once from `init`. It watches the relevant StateFlows and mirrors them into `markSessionDirty()`:

```kotlin
init {
    // (existing init...)
    wireSessionPersistence()
}

private fun wireSessionPersistence() {
    viewModelScope.launch {
        combine(
            _modelScale, _modelRotation, _copyCount
        ) { _, _, _ -> Unit }.collect { markSessionDirty() }
    }
}
```

That covers scale, rotation, copy count automatically. The remaining mutators (`loadModel*`, `addModelFromFile*`, `selectPlate`, `applyPlacementPositions`, `clearModel`) only fire once each per user action, so an explicit `markSessionDirty()` call inside each one is fine and trivial to maintain.

`additionalModelFiles` is a plain `MutableList`, not a flow, so its mutator (`addModelFromFile*`) gets the explicit call.

`customObjectPositions` is a plain field, but `applyPlacementPositions` is the only writer that produces user-meaningful state — so it gets the explicit call too.

## Test plan

### JVM unit tests

`app/src/test/java/com/u1/slicer/data/SessionStateTest.kt` — pure-state coverage:

- `toJson_fromJson_roundTrip_basicFields`
- `toJson_fromJson_roundTrip_customObjectPositions` (FloatArray equality)
- `toJson_fromJson_roundTrip_emptyAdditionalFiles`
- `toJson_fromJson_roundTrip_multipleAdditionalFiles`
- `toJson_fromJson_roundTrip_nullablesAllNull` (no plate, no source, no custom placement, no additional files)
- `fromJson_malformedJson_returnsNull`
- `fromJson_missingVersionField_returnsNull`
- `fromJson_unknownSchemaVersion_returnsNull`
- `fromJson_missingRequiredModelName_returnsNull`
- `fromJson_missingRequiredRawInputPath_returnsNull`
- `captureSessionSnapshot_nullRawInput_returnsNull` (helper-level)

10 new tests.

### Instrumented tests

`app/src/androidTest/java/com/u1/slicer/data/SessionStateRepositoryTest.kt` — DataStore round-trip:

- `write_thenRead_returnsSameSessionState`
- `read_emptyStore_returnsNull`
- `clear_afterWrite_readReturnsNull`
- `write_overwrites_prior`

`app/src/androidTest/java/com/u1/slicer/SessionResumeIntegrationTest.kt` — restore flow (file-backed, no UI):

- `restoreSession_validFile_loadsAndAppliesAllFields`
- `restoreSession_missingFile_emitsToastAndClears`
- `restoreSession_additionalFileMissing_skipsThatOneAndContinues`

7 new instrumented tests.

### E2E (Smoke-7 batch)

The Smoke-7 batch already covers the canonical "load → tweak → slice → send" path on five representative files. F89 changes the load path subtly (auto-restore vs explicit user load), so the whole Smoke-7 must pass on v2.6.0 before tagging. No new E2E case is required — the existing batch implicitly covers F89 because the first run after a clean install starts with no session, and a re-run after a kill exercises the resume path.

### Test counts to update

CLAUDE.md / README test counts:

- Unit: 1295 → 1305 (+10 in `SessionStateTest`)
- Instrumented: 327 → 334 (+7 across `SessionStateRepositoryTest` + `SessionResumeIntegrationTest`)

## Risks

- **Debounce-vs-kill race.** If the user mutates state and Android kills the app within the 500 ms debounce window, that last mutation is lost. Mitigation: 500 ms is short enough that the loss is one user-perceptible action at most, and the worst case is "your last drag didn't take" — recoverable by re-dragging on resume. Trading a small loss window for not-thrashing DataStore on per-frame drags is the right call.
- **DataStore atomicity.** `write` and `clear` go through `dataStore.edit { }` which is atomic per Android Jetpack contract. Concurrent writes from multiple coroutines serialize.
- **Schema evolution.** `version = 1` today. Any future change that adds a non-optional field bumps to `version = 2` and `fromJson` for v1 returns null (safe — user just starts fresh). Optional-only additions keep `version = 1`.
- **Source-file path stability.** Android can change cache paths across reboots in theory. In practice the `cacheDir` returned by `Context.getCacheDir()` is stable for the app's install lifetime; the rawInputFile is generally `cacheDir/<something>.3mf` (the file-picker copy path) or a content-URI-copied file. Eviction is the realistic failure mode, covered by the `File.exists()` check.
- **Restore on a model load that fails.** If the saved file exists but the load errors (corrupt 3MF, native crash) the `Error` state is reached. Restore aborts and clears the session so the user doesn't keep hitting the same broken file on every launch.
- **Smart Paint cached file**. If the saved session points at `cacheDir/ai_paint_<ts>.3mf` and that file is evicted, restore falls to the "file no longer available" toast — correct outcome, but the user loses the painted result. This is consistent with the rest of the app's Smart Paint behaviour (the cache file is not durable; users who want to keep it should "Save 3MF").
- **Banner stickiness across tabs**. The banner is gated on the Prepare tab + Idle state. If the user navigates to Jobs or Printer with the banner up, it hides on those tabs and reappears on Prepare. That's the intended behaviour — the banner refers to the Prepare workflow.

## Non-goals

- Native model-state snapshot (Option 2). Tracked as a documented potential follow-up; do not speculatively build.
- Resuming a slice that was in flight when the kill happened.
- Per-tab session state (Jobs / Printer tabs are stateless across kills already).
- Restoring scroll position, view-camera orientation, or transient UI state.
- Multiple saved sessions / a "session history". Single most-recent session only.
- Cross-device session sync.

## Release plan

- Version bump `2.5.0` → `2.6.0`, `versionCode 294` → `295`.
- Branch `feature/f89-session-resume`.
- Implementation via `subagent-driven-development`.
- After implementation: full unit test pass + Smoke-7 E2E on Pixel 8a before merge.
- After merge to main: build the release APK and stage it at `G:/My Drive/claude/u1-slicer-v2.6.0.apk`.
- **Do not** `gh release create` without explicit per-turn authorization from Kevin. Per `CLAUDE.md` and the [release-permission memory](../../../memory/feedback-release-permission.md), releases are gated.
- No native rebuild.

## Open questions (none required for v2.6.0)

- Should the banner show a "snapshot age" hint ("Resuming Buzz Lightyear · plate 8 · 14 min ago")? Possible if Kevin asks for it later; trivial — `savedAtEpochMs` is already in the schema.
- Should the dismiss `×` show a confirmation? Probably not — accidental dismiss is undo-able by reloading the same file, and Kevin has consistently preferred low-friction UI.
