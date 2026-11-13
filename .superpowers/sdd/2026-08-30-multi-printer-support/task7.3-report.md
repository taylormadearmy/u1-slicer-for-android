# Phase 7 Task 7.3 report

Status: DONE

Task 7.3 implementation and the approved-device v4.0.4 -> current-candidate soak are complete. Task 7.4 must rerun the same soak against the staged v5.0.0 release APK.

Commits: `e999ebc7` (`test: harden v4 multi-printer migration`), `f6ebbdf0` (`test: add repeatable v4 upgrade soak`), `8a45102d`, `45d4f90e`, `e0a9ddce`, and `f8511686` (evidence/report updates), `7142140d` (`test: strengthen v4 upgrade soak evidence`), and `fbf15b0c` (`fix: harden upgrade soak execution`).

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
- Initial full JVM green: `.\gradlew.bat testDebugUnitTest --no-daemon`; 2,139 tests across 239 suites, 0 failures, 0 errors, 0 skipped.
- Instrumentation APK compile green: `.\gradlew.bat :app:assembleDebugAndroidTest --no-daemon`.
- Review fixes: the backup test now compares the imported printers with the independent v4 fixture, `UpgradeDetectorTest` includes the exact 404 -> 500 transition, and the soak snapshots/restores the approved device's prior printer config and `machine_profiles` contents.
- Final full JVM after review and soak-script regressions: `2,142` tests across `240` suites, 0 failures, 0 errors, 0 skipped.
- Current anchored instrumented source inventory: 467 `@Test` annotations across 77 Kotlin files.
- Instrumentation APK compilation after review fixes: PASS.
- PowerShell soak script parses successfully.
- Real soak command: `$env:ANDROID_SERIAL='NF12209000455'; .\scripts\run-v4-upgrade-soak.ps1 -CandidateApk app/build/outputs/apk/debug/app-debug.apk`.
- Real soak result: PASS. Released v4.0.4 launched with the seeded fixture; the candidate's version/timestamp and same-version reinstall both logged the intentional `APK_CHANGED` exit; the v4 printer set, Bambu record, active printer, and native U1 slice passed before and after reinstall; the saved generic 300 x 300 x 350 profile survived; prior device state restored in a final passing test.
- The first real run exposed an invalid activity component and stopped before slicing; `fbf15b0c` fixes the component and adds a two-test script regression. A later run exposed an order-dependent exact-printer assertion; the initial three-printer assertion remains strict and the post-reinstall assertion now strictly expects the three v4 printers plus the intentional generic printer.
- Archived logcat evidence: `release/phase7/evidence/task7.3/upgrade-soak/`. Final JVM XML is archived under `release/phase7/evidence/task7.3/jvm/`.

## Safety and repository state

- The only device used was `NF12209000455`; every soak/restore invocation set that serial in the same command and every adb action used `adb -s NF12209000455`.
- No physical print, Map & Print, Send & Print, network host, push, tag, release, or public issue change occurred.
- Orca submodule was not touched.
- The prior printer configuration and `machine_profiles` directory were restored after every stopped or successful soak run.

## Parent rerun command

```powershell
$env:ANDROID_SERIAL='NF12209000455'
.\scripts\run-v4-upgrade-soak.ps1 -CandidateApk <path-to-candidate-apk>
```

The same command accepts the v5.0.0 release APK path during Task 7.4.
