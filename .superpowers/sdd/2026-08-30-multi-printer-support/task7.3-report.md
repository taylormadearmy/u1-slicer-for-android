# Phase 7 Task 7.3 report

Status: DONE_WITH_CONCERNS

Task 7.3 implementation is complete locally. The required on-device v4.0.4 -> candidate soak remains to be run by the parent on the approved device; this agent did not install or run any APK on a device.

Commits: `e999ebc7` (`test: harden v4 multi-printer migration`), `f6ebbdf0` (`test: add repeatable v4 upgrade soak`), `8a45102d` (`docs: record task 7.3 migration evidence`), `45d4f90e` (`docs: record task 7.3 test inventory`), and `e0a9ddce` (`test: launch v4 after migration seed`).

## Changes

- Added `migration/v4_printers_config.json`, a v4.0.4-shaped fixture containing two U1 printers with stable identities, active selection, nondefault presets/material/profile IDs, selected webcams, and an offline loopback Bambu A1 entry.
- Added `migration/v4_settings_backup.json`, a schema-3 v4.0.4-shaped backup retaining printer config, slicing overrides, filament profile, and process profile data.
- Added `Phase7MigrationFixtureTest` covering repeated PrintersConfig migration/serialization, U1 built-in profile fallback (270 x 270 x 270, four extruders, Klipper/start G-code), dedicated Bambu transport fields, SettingsBackup import/round-trip, and v1 compatibility.
- Added the RED `UpgradeDetectorTest` regression for durable `filesDir/machine_profiles` contents and protected that directory with the minimal production fix.
- Added `V4UpgradeMigrationSeedTest` and `V5UpgradeMigrationVerificationTest` for the repeatable migration soak. The tests seed only the v4-compatible printer APIs, verify the candidate's preserved printers and native U1 slice, and verify a saved generic 300 x 300 x 350 profile after reinstall.
- Added `scripts/run-v4-upgrade-soak.ps1`, which accepts a candidate APK, verifies the released v4.0.4 hash, uses only `NF12209000455`, installs APKs with explicit `adb -s`, asserts the intentional `APK change detected ... exiting` log on version and same-version reinstalls, and requires `OK` instrumentation output. It has no print or host-start path.

## Evidence

- RED before fix: `UpgradeDetectorTest > upgradeTransientFiles preserves durable machine profile cache FAILED`, `UpgradeDetectorTest.kt:204`.
- Focused green: `.\gradlew.bat testDebugUnitTest --no-daemon --tests com.u1.slicer.UpgradeDetectorTest --tests com.u1.slicer.data.Phase7MigrationFixtureTest`.
- Full JVM green: `.\gradlew.bat testDebugUnitTest --no-daemon`; 2,139 tests across 239 suites, 0 failures, 0 errors, 0 skipped.
- Instrumentation APK compile green: `.\gradlew.bat :app:assembleDebugAndroidTest --no-daemon`.
- Current anchored instrumented source inventory: 465 `@Test` annotations across 77 Kotlin files (four soak methods/classes added by this task).
- PowerShell soak script parses successfully. Device run is pending parent execution.

## Safety and repository state

- No device was used by this agent; the parent must set `ANDROID_SERIAL=NF12209000455` in the same invocation before the soak.
- No physical print, Map & Print, Send & Print, network host, push, tag, release, or public issue change occurred.
- Orca submodule was not touched.
- The parent should archive soak logcat/instrumentation output under `release/phase7/evidence/task7.3/upgrade-soak/`, then update this report to DONE only after the real soak passes.

## Parent rerun command

```powershell
$env:ANDROID_SERIAL='NF12209000455'
.\scripts\run-v4-upgrade-soak.ps1 -CandidateApk <path-to-candidate-apk>
```

The same command accepts the v5.0.0 release APK path during Task 7.4.
