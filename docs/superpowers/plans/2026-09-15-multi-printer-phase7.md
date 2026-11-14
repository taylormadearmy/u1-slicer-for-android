# A2 Phase 7 execution plan

Scope: master plan Tasks 7.1–7.4 only. Starting commit 7f1acf6b on feature/a2-multi-printer in the existing multi-printer worktree. See the master plan and design spec dated 2026-08-30.

## Task 7.1: Confidence and upload-only wizard coverage
- [x] Add MachineBedSizeTest, SingleExtruderMachineTest and MarlinFlavorTest to the default confidence manifest.
- [x] Add a repeatable E2E batch scenario that adds a printer through the wizard, slices a fixture and uploads only to a mock host. Assert persisted profile, successful native slice and exact upload-only request semantics. Keep the existing U1 scenarios.
- [x] Add regression coverage first for new logic; record red/green evidence where applicable.
- [x] Run the expanded native smoke and new scenario, review and commit. Check master Task 7.1 only after passing.

## Task 7.2: Documentation and staged issue handling
- [x] Update supported printers, architecture, profile conventions, wizard screenshot, test inventory and BACKLOG candidate status.
- [x] Stage release notes and six deferred issue bodies: delta beds, SEMM, SDCP/Duet, Bambu convergence, >4 extruders and mix encoding.
- [x] Stage #152 closure text referencing those future issues; do not publish any issue/status changes yet.
- [x] Validate documentation against implemented code and available test reports; review and commit. Refresh counts again after migration tests.

## Task 7.3: Upgrade and cross-version migration soak
- [x] Add a representative v4 PrintersConfig JSON fixture and regression tests for identity, active printer, credentials, presets and U1 fallback.
- [x] Add a cross-version SettingsBackup import fixture/test, preserving v4 values through current import.
- [x] Add and run a repeatable upgrade-in-place soak covering persistent v4 data, UpgradeDetector cache handling, restart and slice with migrated U1 profile. Do not clear app data to simulate an upgrade.
- [x] Run focused tests and JVM suite, review and commit. Check master Task 7.3 only after passing.

## Task 7.4: Local v5.0.0 release candidate
- [x] Set versionName 5.0.0 and versionCode 500 in a focused version commit.
- [x] Build with clean assembleRelease, stage u1-slicer-orca-v5.0.0.apk and record SHA-256.
- [x] Set ANDROID_SERIAL=NF12209000455, then run the exact unfiltered .\scripts\run-confidence-check.ps1. Preserve full evidence outside app/build before another clean build.
- [x] Confirm final counts, migration tests, screenshot, clean worktree and pinned clean Orca; record every commit and exact evidence.
- [ ] Stop with candidate ready and exact publication commands. Task 7.4 stays open until explicit publication authorization and same-turn release/backlog/docs/issue synchronization.

All device commands target only NF12209000455. No physical prints, no print=true against a real host. No push, public tag, release or public issue changes before authorization. Treat any failure as a regression to investigate; never weaken assertions. Tasks execute in order, with bounded subagents and serial device/build ownership.
