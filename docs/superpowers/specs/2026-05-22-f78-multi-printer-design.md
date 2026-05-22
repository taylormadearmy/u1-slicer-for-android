# F78 — Multi-printer support (U1-only) — Design

**Status:** Draft, 2026-05-22
**Issue:** [GitHub #110](https://github.com/taylormadearmy/u1-slicer-for-android/issues/110)
**Scope:** Phase 2.X follow-up; ships as v2.4.0 (versionCode 293+).
**Out of scope:** Heterogeneous printers (tracked under A2 / v3.0.0).

## TL;DR

Today the app talks to a single Moonraker URL. F78 lets the user configure multiple U1 printers, switch between them from a chip at the top of the Printer tab, and have per-printer settings for the things that actually differ between physical printers (URL, nickname, what filament is loaded in each slot). LED on/off remains a live readback from each printer, not a per-printer persisted preference. Only one printer connects at a time. Notifications get the printer nickname prefixed in the title. Jobs / filament library / slicing overrides stay global.

## User-facing behaviour

### Adding the first secondary printer
1. Settings → "Printers" section (new).
2. The list currently shows one entry, "Printer 1" — auto-migrated from the previously-configured single URL.
3. Tap "Add printer" → enter nickname + Moonraker URL → "Test connection" → "Save".
4. New printer appears in the list. Active printer is unchanged (still Printer 1) — adding is not switching.

### Switching active printer
1. On the Printer tab, the top of the screen shows a chip: **"Printer 1 ▾"**.
2. Tap → bottom sheet lists all configured printers with their nicknames + current state (e.g. "Printing — 47%", "Idle", "Offline", "Disconnected").
3. Tap a row → active printer changes. The chip updates. Polling stops on the old printer and starts on the new one. Camera, LED, extruder slot UI, and notifications all rebind.
4. If a print is running on the printer being switched **away from**, the bottom sheet shows the print status next to the row, and switching is allowed without confirmation. The autonomous print continues on the physical printer; the app simply stops watching it.

### Sending a sliced job
- Map & Print / Map & Upload buttons send to the **active** printer.
- The Send dialog title shows "Send to Printer 2" (the active printer's nickname) so the target is unambiguous before the user confirms.
- To send to a different printer, the user switches active printer first, then sends.

### Per-printer settings
The Extruder Slots card on the Printer tab binds to the **active** printer. So the slot colours/materials the user sees on Printer 2 are what's loaded in Printer 2, not what's in Printer 1. The LED bulb icon in the toolbar reflects the active printer's live LED state (no per-printer persistence required).

### Notifications
- Title format: `Printer 2 — Print complete: foo.gcode` (nickname prefix when there's >1 configured printer; unchanged when there's exactly 1).
- Notifications only fire for the active printer (single-active connection model — inactive printers don't poll, so we can't observe their transitions).
- Tapping a notification still deep-links to the Printer tab; the user may need to switch active printer if the notification was for a now-stale active.

### Editing and deleting
- Settings → Printers → tap a row → edit nickname / URL / re-test.
- Delete: long-press a row → "Delete" → confirmation. Cannot delete the only printer (must always be ≥1). Cannot delete the active printer (user must switch first).

### Migration
- On first launch of v2.4.0, the app reads the legacy `printer_url` / `extruder_presets` / `led_auto_sync` DataStore keys and constructs a single `Printer` entry with id=UUID, nickname="Printer 1". This becomes both the list head and the active printer. Legacy keys are read-only after migration; they remain in DataStore for one release as a rollback safety net.

## Architecture

### Data model

New file `app/src/main/java/com/u1/slicer/data/Printer.kt`:

```kotlin
data class Printer(
    val id: String,                     // UUID generated on create
    val nickname: String,
    val moonrakerUrl: String,           // normalized via MoonrakerClient.normalizeUrl
    val extruderPresets: List<ExtruderPreset> = emptyList(),
)

data class PrintersConfig(
    val printers: List<Printer>,
    val activeId: String,               // must reference an id in `printers`
)
```

**LED state is intentionally NOT per-printer.** The LED on/off state lives on the physical printer (`PrinterRepository.getLedState()` reads it live from Moonraker). There is no persisted "LED preference" in DataStore today, so there is nothing to migrate or store per-printer for LED. The light-bulb icon in the Printer tab toolbar just reflects the active printer's current live state.

Serialised as JSON via `org.json.JSONObject` (matches the existing `SlicingOverrides.toJson()` / `fromJson()` precedent — kotlinx-serialization is not on the production classpath, only `org.json` as `testImplementation`) and persisted to a single DataStore key `printers_config_json`.

Invariants enforced by `PrintersConfig` constructor:
- `printers.isNotEmpty()`
- `activeId in printers.map { it.id }`

### Repository layer

**New: `PrintersRepository`** (`app/src/main/java/com/u1/slicer/data/PrintersRepository.kt`)
- Owns the `Flow<PrintersConfig>` from DataStore.
- Exposes:
  - `config: StateFlow<PrintersConfig>` (the current list + active id)
  - `activePrinter: Flow<Printer>` (derived projection — `config.printers.first { it.id == config.activeId }`)
  - `setActive(id: String)`
  - `add(printer: Printer)`
  - `update(printer: Printer)` (by id)
  - `delete(id: String)` — fails if `id == activeId` or `printers.size == 1`
  - `runMigrationIfNeeded()` — one-shot read of legacy keys on first launch.

**Updated: `PrinterRepository`** (existing, `printer/PrinterRepository.kt`)
- Constructor now takes `PrintersRepository` instead of (or in addition to) `SettingsRepository`.
- `init {}` collects `printersRepo.activePrinter` and rebinds `client.baseUrl = activePrinter.moonrakerUrl` on every change.
- On every active-printer change: `stopPolling()` → swap `baseUrl` → reset `_status.value = PrinterStatus(state = "disconnected", progress = 0f)` → `startPolling(scope)`.
- `updateUrl(url)` is removed in favour of `printersRepo.update(active.copy(moonrakerUrl = url))`.
- All existing `status`, `printerUrl`, `uploadAndPrint`, `setHeaterTemperature`, `sendGcode` APIs unchanged.

**`PrinterViewModel`**
- New `activeNickname: StateFlow<String>` derived from `printersRepo.activePrinter`.
- Existing `extruderPresets: StateFlow<List<ExtruderPreset>>` now sourced from `printersRepo.activePrinter` rather than `SettingsRepository`.
- New `printerList: StateFlow<List<Printer>>` for the switcher bottom sheet.
- New methods: `switchActivePrinter(id)`, `addPrinter(...)`, `updatePrinter(...)`, `deletePrinter(id)`.

### UI

**Printer tab — switcher chip**
- New composable `ActivePrinterChip` at the top of the Printer screen, below the TopAppBar.
- Renders `"<nickname> ▾"` when `printerList.size > 1`; hidden when only 1.
- Tap → opens `PrinterSwitcherSheet` (Material3 ModalBottomSheet) listing all printers with per-row state strings.

**Settings — Printers section**
- New section between existing sections (likely between "Connection" and "Filaments"). Renders the list of printers with edit/delete affordances. "Add printer" button at the bottom. Tapping a row opens an editor dialog (reuses the existing URL + test-connection plumbing).

**Send dialog**
- The existing Filament Mapping / Send confirmation dialog gains a `"Send to <nickname>"` subtitle when `printerList.size > 1`.

### Notifications

`AppEventNotifier.kt` already has 9 event types. Update the title format:
- `buildTitle(event, activeNickname: String?, printerCount: Int)` — if `activeNickname != null && printerCount > 1`, prefix `"$nickname — "`. Otherwise unchanged.
- The nickname is read from `PrintersRepository.activePrinter.value` inside `PrinterRepository.startPolling` at notify time and passed to `AppEventNotifier.notify(context, event, nickname, count)`. A change of active printer mid-cycle is benign: any in-flight notification fires under whatever nickname was current when the polling cycle began, and the next cycle picks up the new nickname. No race-induced corruption.

### Migration

`PrintersRepository.runMigrationIfNeeded()` is called by `AppContainer.init` once per process start. Logic:

1. If `printers_config_json` is non-empty → already migrated, return.
2. Otherwise read legacy keys:
   - `PRINTER_URL` (string) → `moonrakerUrl`
   - `EXTRUDER_PRESETS` (string, JSON list) → `extruderPresets` via `parseExtruderPresets`
3. Construct `Printer(id = UUID, nickname = "Printer 1", ...)`.
4. Write `PrintersConfig(listOf(thatPrinter), activeId = thatPrinter.id)` to `printers_config_json`.

Legacy keys are NOT deleted on migration. v2.5.0 removes the read fallback; v2.6.0 deletes the keys via a second migration step. This preserves one-release rollback safety.

### Settings backup / restore

`SettingsBackup.kt` currently exports the single `printerUrl` + `extruderPresets` pair at schema VERSION=1. F78 bumps the schema to VERSION=2 and stores the full `PrintersConfig`:

```json
{
  "version": 2,
  "sliceConfig": { ... },
  "slicingOverrides": { ... },
  "filamentProfiles": [ ... ],
  "makerWorldCookies": "...",
  "printers": [
    { "id": "uuid-1", "nickname": "Printer 1", "moonrakerUrl": "...",
      "extruderPresets": [ ... ] },
    { "id": "uuid-2", "nickname": "Workshop",  "moonrakerUrl": "...",
      "extruderPresets": [ ... ] }
  ],
  "activePrinterId": "uuid-1",
  "printerUrl": "...",
  "extruderPresets": [ ... ]
}
```

**Export rules (v2.4.0):**
- Always write the full `printers` array + `activePrinterId` (the new schema).
- ALSO write the legacy `printerUrl` + `extruderPresets` fields, populated from the *active* printer's values. This means a v2.4.0 backup can be imported on v2.3.0 (or earlier) and produce a sensible single-printer setup. Forward-compat for free.

**Import rules (v2.4.0):**
- `version == 2` → read the `printers` array directly into `PrintersConfig`. Ignore the legacy duplicate fields if also present.
- `version == 1` (pre-F78 backup) → read `printerUrl` + `extruderPresets`, run the same migration logic as `runMigrationIfNeeded()` to produce a single `Printer` entry. Backward-compat for free.
- `version > 2` or `version < 1` → reject with a clear error message.

**Test coverage:**
- `SettingsBackupTest` — extend existing tests:
  - `v1Backup_importsAsSinglePrinter_withLegacyValues`
  - `v2Backup_importsAllPrinters_andPreservesActive`
  - `v2BackupExport_includesLegacyFieldsFromActivePrinter_forV1Rollback`
  - `v2Backup_extraneousLegacyFields_areIgnoredWhenPrintersArrayPresent`
  - `unsupportedVersion_throwsClearError`

## Test plan

### JVM unit tests (`app/src/test/java/com/u1/slicer/data/PrintersRepositoryTest.kt`)
- `migration_legacyKeysOnly_producesSinglePrinterWithLegacyValues`
- `migration_idempotent_doesNothingWhenConfigAlreadyPresent`
- `migration_legacyUrlEmpty_producesPrinterWithEmptyUrl`
- `setActive_unknownId_isNoOp_andLogsWarning` (pinned: silent no-op + Log.w; never throws, since flow-state can transiently diverge between emissions and we don't want a crash)
- `delete_activePrinter_isRejected`
- `delete_lastPrinter_isRejected`
- `delete_nonActive_removesAndKeepsActive`
- `add_appendsToList_doesNotChangeActive`
- `update_byId_replacesEntry`
- `printersConfig_constructorRejectsEmptyList`
- `printersConfig_constructorRejectsActiveIdNotInList`

### Instrumented tests (`app/src/androidTest/java/com/u1/slicer/printer/MultiPrinterIntegrationTest.kt`)
- `switchActivePrinter_stopsPollingOld_startsPollingNew_underMockedMoonraker` (using `MockWebServer`)
- `addPrinter_updatesPrinterListStateFlow`
- `migration_runsOnceOnFirstLaunchOfV240`

### E2E (manual)
- Two Moonraker instances on LAN (Kevin already has multiple printer test setups). Walk through: add second printer → switch → send job to active → switch back → verify notification title prefix.

### Test counts to update in CLAUDE.md / README
- Unit: 1258 → 1273 (10 new in PrintersRepositoryTest + 5 new in SettingsBackupTest)
- Instrumented: 318 → 321 (3 new in MultiPrinterIntegrationTest)

## Risks

- **Polling restart races.** Rebinding `MoonrakerClient.baseUrl` while a `getStatus()` call is in flight is undefined. Mitigate by calling `stopPolling()` (which `cancel`s the polling Job and `join`s) BEFORE swapping `baseUrl`. Documented in code.
- **Camera keepalive across switch.** `PrinterViewModel.startCameraKeepalive` runs against the active printer. Must `stopCameraKeepalive` on switch and restart against the new one.
- **DataStore atomicity.** All `PrintersConfig` mutations go through a single `dataStore.edit { ... }` block to avoid lost updates.
- **Notification de-dupe.** A switch could cause an immediate "PrinterOffline → Idle" transition on the new printer's first poll cycle that looks like a new event. The existing `applyGracePeriod` (OFFLINE_GRACE_FAILURES) covers most of this; verify behaviour on switch and add a "just-switched" grace if needed.
- **Backup/restore.** Covered in dedicated "Settings backup / restore" section above — schema bumps to VERSION=2, with two-way compat (v2.4.0 backups still import on v2.3.0 via the duplicated legacy fields; v1 backups still import on v2.4.0 via the existing migration path).

## Non-goals

- Concurrent polling of multiple printers (ruled out in brainstorm).
- Per-printer Jobs/recents (ruled out — global stays).
- Per-printer slicing overrides (global stays).
- Cross-printer "send to many" or queue-fan-out.
- Heterogeneous printer profiles — that's A2.
- Auth tokens per printer (Moonraker auth not currently used by the app — out of scope until needed).

## Open questions (none required for v2.4.0)

- Should the switcher bottom sheet show camera thumbnails per printer? *No for v1 — adds polling cost we ruled out.*
- Should "Send to" gain a "more…" affordance to send to a non-active printer without switching? *No for v1 — explicit switch is acceptable for the 2-printer case.*

## Release plan

- Version bump 2.3.0 → 2.4.0, versionCode 292 → 293.
- Update `BACKLOG.md` F78 entry to mark DONE on ship.
- Manual E2E with two Moonraker URLs before tagging.
- No native rebuild required (Compose + Kotlin + DataStore changes only).
