# Task 7.2 report

Status: DONE_WITH_CONCERNS

## Commit

- This report is committed with `docs: prepare v5.0.0 multi-printer release`.

## Files

- `AGENTS.md`
- `CLAUDE.md`
- `README.md`
- `BACKLOG.md`
- `docs/REFACTOR_STATUS.md`
- `docs/architecture/machine-profile-pipeline.md`
- `docs/images/multi-printer-wizard.png`
- `docs/release-notes/v5.0.0.md`
- `docs/releases/v5.0.0/publication-checklist.md`
- `docs/releases/v5.0.0/issue-152-closure.md`
- Six draft issue bodies in `docs/releases/v5.0.0/issues/`

## Documentation result

The documentation now describes the implemented v5.0.0 candidate as one `MachineProfile` pipeline backed by resolved Orca-native JSON. The U1 remains the default. The catalogue pin is `taylormadearmy/OrcaSlicer` v2.2.4, with 326 models from 56 usable vendors after safety filtering.

The support boundary is explicit: rectangular FDM, 1 to 4 physical slots, Klipper or Marlin G-code, and capability-gated Moonraker, OctoPrint, or PrusaLink upload. Bambu stays on its dedicated opt-in path. The docs distinguish `snapmaker_authored_profile` provenance from the Snapmaker-scoped legacy `PRINT_START` fallback and retain the `profile_keys[]` coverage guard. They do not claim a `nativeApplyResolvedConfig` API.

The wizard section records validation before save, cached offline profiles, catalogue selection, and `.orca_printer` import. Deferred work has six staged issue bodies with current limits and acceptance criteria. The #152 closure draft links each issue through a labeled placeholder and includes a release URL placeholder.

## Count sources and evidence

The complete JVM result comes from both `app/build/test-results/testDebugUnitTest/` and the archive at `release/phase7/baseline/jvm/`. Summing each `TEST-*.xml` `tests`, `failures`, `errors`, and `skipped` attribute gives 238 suites, 2,134 tests, 0 failures, 0 errors, and 0 skipped.

The instrumented inventory is 461 runnable test methods across 73 source classes. This is an inventory count, not a full-run result. A raw source scan finds 462 `@Test` annotations across the same 73 files, so documentation uses the runner inventory supplied by Phase 7 discovery rather than treating annotation count as execution evidence.

Executed device evidence remains separate:

- The pre-A2 baseline is 197 instrumented tests.
- `release/phase7/evidence/task7.1/TEST-NF21F1 - 13-_app-.xml` records four added tests across `MachineBedSizeTest`, `SingleExtruderMachineTest`, and `MarlinFlavorTest`, with no failures, errors, or skips.
- `release/phase7/evidence/task7.1/batch-results/printer-wizard-add-slice-upload.txt` records one passing wizard, native slice, persistence, and upload-only scenario.
- `release/phase7/evidence/task7.1/task7.1-report.md` records the commands, scope, and loopback upload assertions.

## Screenshot

`docs/images/multi-printer-wizard.png` is a real 1080 by 1920 screenshot from approved device `NF12209000455`. Before each interaction, UI Automator output was read to identify the current screen and target bounds. The screenshot shows the OrcaSlicer v2.2.4 manufacturer picker and contains no private hostname, IP address, or credential. The background truthfully shows the current pre-bump app version, v4.0.4.

The device initially had no app package installed. `installDebug` built the candidate, but Gradle selected an unrelated incompatible device because its elevated process did not retain `ANDROID_SERIAL`; installation failed with `INSTALL_FAILED_NO_MATCHING_ABIS` and changed no package on that device. The built APK was then installed with explicit `adb -s NF12209000455 install -r`. All successful app interaction and screenshot commands used explicit `adb -s NF12209000455`. No physical printer endpoint or print action was used.

## Publication staging

`docs/releases/v5.0.0/publication-checklist.md` requires the following order after explicit authorization: authenticate as `taylormadearmy`; push the approved branch; create all six follow-up issues; create a new v5.0.0 tag and release with `u1-slicer-orca-v5.0.0.apk`; close #152; then perform the same-turn BACKLOG rollup, release-string and test-count verification, parent-owned Task 7.4 checkbox update, separate documentation-sync commit, and push.

No public tag, push, release, issue creation, issue edit, or issue closure occurred in Task 7.2. The app version remains unchanged.

## Concern

Phase 7.3 may change the final test totals and will verify profile persistence across app upgrade. The docs mark both as pending. Parent should refresh final counts after Task 7.3 and may recapture the screenshot after the v5.0.0 version bump if the visible v4.0.4 label is unsuitable for release publication.
