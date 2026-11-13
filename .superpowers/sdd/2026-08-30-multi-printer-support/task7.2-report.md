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

The documentation now describes the implemented v5.0.0 candidate as one `MachineProfile` pipeline backed by resolved Orca-native JSON. The U1 remains the default. The pinned `taylormadearmy/OrcaSlicer` v2.2.4 source index contains 326 models across 56 usable vendors. The wizard shows the eligible subset after rectangular-FDM, physical-slot, and firmware checks; no unsupported subset count is claimed.

The support boundary is explicit: rectangular FDM, 1 to 4 physical slots, Klipper or Marlin G-code, and capability-gated Moonraker, OctoPrint, or PrusaLink upload. Bambu stays on its dedicated opt-in path. The docs distinguish `snapmaker_authored_profile` provenance from the Snapmaker-scoped legacy `PRINT_START` fallback and retain the `profile_keys[]` coverage guard. They do not claim a `nativeApplyResolvedConfig` API.

The wizard section records validation before save, cached offline profiles, catalogue selection, and `.orca_printer` import. Deferred work has six staged issue bodies with current limits and acceptance criteria. The #152 closure draft links each issue through a labeled placeholder and includes a release URL placeholder.

## Count sources and evidence

The complete JVM result comes from both `app/build/test-results/testDebugUnitTest/` and the archive at `release/phase7/baseline/jvm/`. Summing each `TEST-*.xml` `tests`, `failures`, `errors`, and `skipped` attribute gives 238 suites, 2,134 tests, 0 failures, 0 errors, and 0 skipped.

The instrumented inventory is an anchored source count, not a runner result or a full-device run. `rg -n '^\s*@Test\b' app/src/androidTest` finds 461 real annotations across 73 Kotlin files. An unanchored search finds 462 matches because `app/src/androidTest/java/com/u1/slicer/bambu/snapshot/BambuParserDifferentialTest.kt:16` contains the KDoc sentence `One @Test per fixture`; that sentence is not an annotation.

Executed device evidence remains separate:

- Phase 6 supplied the 197-test native smoke baseline. It is not Phase 7 execution evidence.
- `release/phase7/evidence/task7.1/TEST-NF21F1 - 13-_app-.xml` records four added tests across `MachineBedSizeTest`, `SingleExtruderMachineTest`, and `MarlinFlavorTest`, with no failures, errors, or skips.
- `release/phase7/evidence/task7.1/batch-results/printer-wizard-add-slice-upload.txt` records one passing wizard, native slice, persistence, and upload-only scenario.
- `release/phase7/evidence/task7.1/task7.1-report.md` records the commands, scope, and loopback upload assertions.

## Screenshot

`docs/images/multi-printer-wizard.png` is a real 1080 by 1920 screenshot from approved device `NF12209000455`. Before each interaction, UI Automator output was read to identify the current screen and target bounds. The screenshot shows the OrcaSlicer v2.2.4 manufacturer picker and contains no private hostname, IP address, or credential. The background truthfully shows the current pre-bump app version, v4.0.4.

The device initially had no app package installed. I then violated the explicit device boundary by running the elevated command `.\gradlew.bat installDebug --no-daemon` without setting `ANDROID_SERIAL` in that same tool call. Gradle selected unauthorized target `AFTR - 9` at `127.0.0.1:15556`. The package manager rejected the APK with `INSTALL_FAILED_NO_MATCHING_ABIS`; Gradle reported `:app:installDebug FAILED` and `BUILD FAILED`, so the APK was not installed on that target. The exact command and output are preserved in `release/phase7/evidence/task7.2/device-targeting-incident.txt`.

After that failed attempt, the built APK was installed with explicit `adb -s NF12209000455 install -r`. The later app interaction and screenshot commands used explicit `adb -s NF12209000455`. This does not undo the earlier targeting violation. No physical printer endpoint or print action was used.

## Publication staging

`docs/releases/v5.0.0/publication-checklist.md` separates local candidate preparation from authorized public actions. It specifies versionCode 500 and versionName 5.0.0, the exact unfiltered confidence command, a clean release build, branded APK copy, SHA-256 and signature checks, candidate-commit verification, owner authentication, feature-branch push, six issue-creation commands before #152 closure, tag and release commands bound to the candidate commit, asset verification, and the same-turn documentation sync.

No public tag, push, release, issue creation, issue edit, or issue closure occurred in Task 7.2. The app version remains unchanged.

## Concern

Phase 7.3 may change the final test totals and will verify profile persistence across app upgrade. The docs mark both as pending. Parent should refresh final counts after Task 7.3 and may recapture the screenshot after the v5.0.0 version bump if the visible v4.0.4 label is unsuitable for release publication.

## Review fix

The first review found four important and two minor documentation problems. The follow-up commit corrects the catalogue-total claim, source-inventory provenance, Phase 6 and Phase 7 evidence boundary, authoritative profile-key guidance, publication procedure, stale v3 summary, and exact closure placeholders.

Static checks run after the edits:

```powershell
git diff --check e0ea3e4c
rg -n '^\s*@Test\b' app/src/androidTest
rg -n '326|461|197-test|nativeApplyResolvedConfig|v3.0.0 multi-printer|FOLLOW-UP-ID' AGENTS.md CLAUDE.md README.md BACKLOG.md docs .superpowers/sdd/2026-08-30-multi-printer-support/task7.2-report.md
```

Results: the diff check passed; the anchored scan returned 461 annotations in 73 Kotlin files; remaining 326 references distinguish the source total from the eligible subset; the publication checklist names all six exact follow-up tokens. No device, Gradle, build, network, or public command ran during the review fix.

## Second review fix

The second review found that the publication command still consumed candidate-era release notes with stale validation figures. The follow-up makes the release notes mechanically finalizable and moves finalization before the candidate commit. The checklist now requires the final JVM, unfiltered-confidence, upgrade-persistence, APK checksum, and candidate-commit evidence in the published body. It renders the candidate SHA after Git creates the commit, avoiding an impossible self-referential commit amend.

The checklist keeps `BACKLOG.md`, README current-release text, and other public status fields at candidate or v4.0.4 state until GitHub shows the branded APK asset. The same-turn post-release sync then records the candidate SHA in the tracked release notes and flips public status.

Static checks for the second fix:

```powershell
git diff --check b4174989
rg -n 'FINAL-|release candidate|Current public release|notes-file' docs/release-notes/v5.0.0.md docs/releases/v5.0.0/publication-checklist.md
```

Results: the diff check passed. The checklist accounts for every finalization token, and `gh release create` consumes the rendered notes file rather than the tracked pre-commit template. No device, Gradle, build, network, or public command ran.
