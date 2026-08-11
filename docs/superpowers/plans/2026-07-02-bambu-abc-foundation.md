# Bambu A+B+C Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce a provider-aware printer foundation that supports Moonraker and Bambu printer kinds, ships A+B in a tryable state, and leaves clear interfaces for C's future upload/start/pause/resume/cancel path.

**Architecture:** Extend the persisted printer model with provider-specific config, extract a `PrinterTransport` boundary from the Moonraker-coupled repository, and keep the current Moonraker behavior behind a `MoonrakerTransport`. Add a lightweight Bambu read-only transport skeleton and thread printer-kind awareness through the view-model and settings UI without breaking existing U1 flows.

**Tech Stack:** Kotlin, StateFlow, DataStore JSON persistence, Jetpack Compose, JUnit JVM tests, existing Android/instrumented printer integration tests.

---

## File map

- Modify: `app/src/main/java/com/u1/slicer/data/Printer.kt`
- Modify: `app/src/main/java/com/u1/slicer/data/PrintersRepository.kt`
- Create: `app/src/main/java/com/u1/slicer/printer/PrinterTransport.kt`
- Create: `app/src/main/java/com/u1/slicer/printer/TransportCommandResult.kt`
- Create: `app/src/main/java/com/u1/slicer/printer/MoonrakerTransport.kt`
- Create: `app/src/main/java/com/u1/slicer/printer/BambuLanTransport.kt`
- Create: `app/src/main/java/com/u1/slicer/printer/PrinterTransportFactory.kt`
- Modify: `app/src/main/java/com/u1/slicer/printer/PrinterRepository.kt`
- Modify: `app/src/main/java/com/u1/slicer/printer/PrinterViewModel.kt`
- Modify: `app/src/main/java/com/u1/slicer/ui/printer/PrinterEditDialog.kt`
- Modify: `app/src/main/java/com/u1/slicer/ui/printer/PrintersSettingsCard.kt`
- Modify: `app/src/main/java/com/u1/slicer/ui/printer/PrinterSwitcherSheet.kt`
- Test: `app/src/test/java/com/u1/slicer/data/PrinterTest.kt`
- Test: `app/src/test/java/com/u1/slicer/data/PrintersRepositoryTest.kt`
- Create: `app/src/test/java/com/u1/slicer/printer/PrinterTransportFactoryTest.kt`
- Modify: `app/src/test/java/com/u1/slicer/printer/PrinterRepositoryTest.kt`
- Modify: `app/src/test/java/com/u1/slicer/ui/printer/F78ConditionalRenderingTest.kt`

### Task 1: Extend the printer data model for provider-aware configs

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/data/Printer.kt`
- Test: `app/src/test/java/com/u1/slicer/data/PrinterTest.kt`
- Test: `app/src/test/java/com/u1/slicer/data/PrintersRepositoryTest.kt`

- [ ] Write failing JVM tests for Moonraker and Bambu JSON round-trip, constructor invariants, and legacy migration defaulting to Moonraker.
- [ ] Run `.\gradlew.bat testDebugUnitTest --no-daemon --tests "com.u1.slicer.data.PrinterTest" --tests "com.u1.slicer.data.PrintersRepositoryTest"` and verify the new tests fail for the expected missing fields.
- [ ] Add `PrinterKind`, `BambuModel`, `BambuConfig`, provider-aware `Printer` invariants, JSON serialization/deserialization, and migration fallback to `MOONRAKER`.
- [ ] Re-run the same targeted tests and make them pass.

### Task 2: Introduce the transport boundary without changing behavior

**Files:**
- Create: `app/src/main/java/com/u1/slicer/printer/PrinterTransport.kt`
- Create: `app/src/main/java/com/u1/slicer/printer/TransportCommandResult.kt`
- Create: `app/src/main/java/com/u1/slicer/printer/MoonrakerTransport.kt`
- Create: `app/src/main/java/com/u1/slicer/printer/BambuLanTransport.kt`
- Create: `app/src/main/java/com/u1/slicer/printer/PrinterTransportFactory.kt`
- Create: `app/src/test/java/com/u1/slicer/printer/PrinterTransportFactoryTest.kt`

- [ ] Write failing JVM tests that assert transport-factory selection returns Moonraker for `MOONRAKER` printers and Bambu for `BAMBU_LAN` printers.
- [ ] Run `.\gradlew.bat testDebugUnitTest --no-daemon --tests "com.u1.slicer.printer.PrinterTransportFactoryTest"` and verify the failures are because the transport types/factory do not exist yet.
- [ ] Add the transport interface, command result types, Moonraker wrapper transport, Bambu read-only skeleton transport, and the factory.
- [ ] Re-run the targeted factory test and make it pass.

### Task 3: Refactor PrinterRepository onto transports with Moonraker parity

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/printer/PrinterRepository.kt`
- Modify: `app/src/test/java/com/u1/slicer/printer/PrinterRepositoryTest.kt`
- Reuse: `app/src/test/java/com/u1/slicer/printer/PrinterRepositoryNotificationTest.kt`

- [ ] Write failing tests for provider-aware `supportsFilamentSync`, `supportsRemoteScreen`, and unsupported command behavior on non-Moonraker transports.
- [ ] Run `.\gradlew.bat testDebugUnitTest --no-daemon --tests "com.u1.slicer.printer.PrinterRepositoryTest" --tests "com.u1.slicer.printer.PrinterRepositoryNotificationTest"` and verify the new assertions fail before implementation.
- [ ] Refactor the repository to own an active `PrinterTransport`, forward status/camera/consumables flows, preserve Moonraker behavior through `MoonrakerTransport`, and return explicit unsupported results for Bambu commands not yet implemented.
- [ ] Re-run the targeted repository tests and make them pass.

### Task 4: Thread printer kind through PrinterViewModel and settings UI

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/printer/PrinterViewModel.kt`
- Modify: `app/src/main/java/com/u1/slicer/ui/printer/PrinterEditDialog.kt`
- Modify: `app/src/main/java/com/u1/slicer/ui/printer/PrintersSettingsCard.kt`
- Modify: `app/src/main/java/com/u1/slicer/ui/printer/PrinterSwitcherSheet.kt`
- Modify: `app/src/test/java/com/u1/slicer/ui/printer/F78ConditionalRenderingTest.kt`

- [ ] Write failing tests that pin the new UI/source contracts: provider-aware add/edit dialog fields, printer rows rendering kind-specific subtitles, and Bambu printers not pretending to have a Moonraker URL.
- [ ] Run `.\gradlew.bat testDebugUnitTest --no-daemon --tests "com.u1.slicer.ui.printer.F78ConditionalRenderingTest"` and verify the new assertions fail first.
- [ ] Add printer-kind state and Bambu config save/update paths in the view-model, then update the add/edit/settings/switcher UI to support Moonraker and Bambu entries cleanly.
- [ ] Re-run the targeted UI guard test and make it pass.

### Task 5: Verify the A+B branch checkpoint

**Files:**
- No new production files expected; this task is verification and small cleanup only.

- [ ] Run `.\gradlew.bat testDebugUnitTest --no-daemon` from the worktree and verify the full JVM suite passes.
- [ ] If repository or UI regressions appear, fix them with test-first follow-up commits before continuing.
- [ ] Record any intentionally deferred C work in the branch docs if the code now exposes the interfaces but not the behavior.

## Self-review

- Spec coverage: this plan covers provider-aware persistence, transport abstraction, Moonraker parity, Bambu read-only branch wiring, and C-shaped command interfaces. Real Bambu protocol implementation beyond the skeleton remains intentionally deferred.
- Placeholder scan: no TBD/TODO implementation steps remain; deferred scope is explicitly named.
- Type consistency: `PrinterKind`, `BambuConfig`, `PrinterTransport`, and `TransportCommandResult` are the canonical names used throughout the plan.
