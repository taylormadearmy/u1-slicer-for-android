# F87 + F91 — Orca profile import & filament library expansion

**Status**: in-progress on `feature/f87-f91-orca-profiles` (branched 2026-05-25).
**Ship strategy**: bundled into one public release after a mid-point confidence check between F87 and F91.

## F87 — Process profile import + slice-time apply

### Storage
- `data/ProcessProfile.kt`: `data class ProcessProfile(id: String, name: String, sourceName: String?, keys: Map<String, String>)`. Keys are flat string-or-string-array values pulled from the imported JSON; we keep them as-is so we can spill them straight back into the embed pipeline. UUID id.
- `data/ProcessProfilesRepository.kt`: DataStore-backed (JSON-in-Preferences, modelled on `PrintersRepository`). Exposes `Flow<ProcessProfilesConfig>` with `profiles: List<ProcessProfile>` + `activeId: String?`.
- `ProcessProfilesConfig` companion: `fromJson`/`toJson`, `migrate` shim.

### JSON parser
- `data/ProcessProfileParser.kt`: `parseProcessProfile(json: String, fallbackName: String): ProcessProfile?` — handles the OrcaSlicer `.orca_process` / `.json` flat shape (top-level `type: "process"`, keys flat or string-array). Falls back to file name when `name` is missing. Filters out non-process keys (`compatible_printers`, `from`, `inherits`, etc.) per `NON_PROCESS_KEYS` allowlist.
- Accepts single object OR array of objects (some users export a whole set).

### Slice-time apply
- `SlicerViewModel` adds `activeProcessProfile: StateFlow<ProcessProfile?>` (reads from `ProcessProfilesRepository`).
- `buildProfileOverridesImpl` merges, in precedence order (lowest → highest):
  1. (existing) file's embedded config (preserve path)
  2. (NEW) active process profile keys
  3. (existing) Prepare-screen `SlicingOverrides`
- Implemented by passing `processProfileKeys: Map<String, Any>` into `buildProfileOverridesImpl`; merged BEFORE the existing per-key resolve logic. SlicingOverrides values still win when set to `OVERRIDE`.

### UI
- `ui/ProcessProfilesScreen.kt`: list + import + rename + delete. Reachable from Settings.
- `ui/SettingsScreen.kt`: new row "Process profiles (N)" with chevron.
- Prepare screen: small dropdown chip "Process: <name>" (default "Use file"). Shown above the existing override accordion. No-op when no profiles imported.

### SettingsBackup v3
- Bump `VERSION = 3`.
- Add `processProfiles: List<ProcessProfile>` + `activeProcessProfileId: String?` to `BackupData`.
- v3 reader supports v1, v2, v3; v2 reader ignores the new keys (forward-compat just by absence).

### Tests
- `ProcessProfileParserTest` — happy path, single object, array, missing name fallback, key filter, value normalisation.
- `ProcessProfilesRepositoryTest` (instrumented) — DataStore round-trip.
- `SettingsBackupTest` — v3 round-trip, v1+v2 import keeps process profiles empty.
- `BuildProfileOverridesProcessProfileTest` — unit test on `buildProfileOverridesImpl` (already internal): verifies process profile keys appear in result map, are overridden by SlicingOverrides, do not appear when no profile active.
- `SlicingIntegrationTest` — slice STL with imported "0.16 fine" process profile, assert layer count vs 0.20mm baseline.

## F91 — Filament library expansion

### Storage
- Expand `FilamentProfile` with these OrcaSlicer-mapped fields (all `Float?`/`Int?` nullable so the user can leave them at "library default" → falls back to native default):
  - `flowRatio: Float?` (filament_flow_ratio)
  - `maxVolumetricSpeed: Float?` (filament_max_volumetric_speed)
  - `fanMinSpeed: Int?`, `fanMaxSpeed: Int?` (fan_min_speed, fan_max_speed)
  - `overhangFanSpeed: Int?` (overhang_fan_speed)
  - `additionalCoolingFanSpeed: Int?` (additional_cooling_fan_speed)
  - `slowDownLayerTime: Float?` (slow_down_layer_time)
  - `slowDownMinSpeed: Float?` (slow_down_min_speed)
  - `closeFanFirstLayers: Int?` (close_fan_the_first_x_layers)
  - `fullFanSpeedLayer: Int?` (full_fan_speed_layer)
  - `enablePressureAdvance: Boolean?`, `pressureAdvance: Float?`
  - `filamentCost: Float?` (filament_cost)
  - `bedTempInitialLayer: Int?` (hot_plate_temp_initial_layer override)
  - `nozzleTempInitialLayer: Int?` (nozzle_temperature_initial_layer)
  - `filamentMinimalPurgeOnWipeTower: Float?` (filament_minimal_purge_on_wipe_tower)
- Room `@AutoMigration(from = 1, to = 2)` adds nullable columns with default `NULL`.
- `@Database(version = 2, autoMigrations = [...])`.

### Parser
- `parseOneFilamentObject` already extracts Bambu/Orca array-valued keys via `extractBambuValue`. Extend to pull the new keys; preserve null when the key is absent (caller defaults to library defaults).

### UI
- `FilamentEditDialog` becomes scrollable, sectioned form:
  1. Identity (name, material, colour)
  2. Temperatures (nozzle / nozzle initial / bed / bed initial)
  3. Flow & limits (flow ratio, max volumetric speed, cost)
  4. Retraction (length, speed)
  5. Cooling (fan min/max, overhang, additional cooling, slow-down layer time + min speed, close-fan-first-N, full-fan-from-layer)
  6. Pressure advance (enable, value)
  7. Wipe tower (minimal purge)
- Each field shows current value or "Library default" placeholder; clearing reverts to null (= fall-through).

### Slice-time apply
- `SlicerViewModel.buildProfileOverridesImpl` already builds per-canonical-slot arrays for `nozzle_temperature` etc. Extend to emit per-slot arrays for each new key. Lookup pattern: for slot `s`, resolve `extruderPresets[s].filamentProfileId` → `FilamentProfile` → field. When field is null on that slot, use the array's previous non-null value (sticky fill) or fall back to OrcaSlicer default — match existing nozzle_temperature pad behaviour.
- All new keys are already on `profile_keys[]` whitelist (audited 2026-05-25, sapil_print.cpp:683–838): no native rebuild required.

### Backup
- SettingsBackup v3 already added the schema bump for F87; F91 simply adds the new optional fields to `exportFilamentProfiles` / `parseFilamentProfilesArray`. Absent keys parse as `null` (back-compat with v1/v2 backups).

### Tests
- `FilamentDaoTest` — new fields persist + retrieve.
- `FilamentJsonImportTest` — new keys parsed from Orca filament JSON.
- `SettingsBackupTest` — v3 round-trip of full filament profile; v1/v2 import yields nulls for new fields.
- `SlicingIntegrationTest` — slice with a library filament that has `filament_max_volumetric_speed=5`; assert `; filament_max_volumetric_speed =` line in G-code header.
- `BuildProfileOverridesFilamentTest` — unit test on per-slot array assembly with mixed-null inputs.

## Out of scope
- Editing of process profiles (read-only; user re-imports to update).
- Importing printer profiles (covered by A2 v3.0.0).
- Per-feature speed overrides outside what's already in `SlicingOverrides`.

## Sanity-check gate between F87 and F91
- Run `./gradlew testDebugUnitTest` (full unit suite).
- Run instrumented smoke-10 + E2E smoke-7 (confidence-check skill).
- Manually verify: import an Orca process profile, see it in the dropdown, slice a model and confirm the profile's layer height/wall settings end up in the G-code header.
- Only then start F91.
