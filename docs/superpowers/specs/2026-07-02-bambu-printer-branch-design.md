# Bambu Printer Branch Design

**Date:** 2026-07-02
**Branch:** `codex/bambu-support`
**Status:** Approved for implementation in this branch
**Supersedes:** builds on [`2026-05-24-bambu-integration-roadmap.md`](2026-05-24-bambu-integration-roadmap.md) and [`2026-05-24-bambu-ab-design.md`](2026-05-24-bambu-ab-design.md)

## Goal

Create an isolated Bambu printer feature branch that can grow into full Bambu support without destabilizing the existing U1 / Moonraker product. The branch should leave the app in a working, tryable state after each milestone.

This branch designs **A + B + C together**:

1. **A - transport abstraction**
2. **B - Bambu LAN read-only**
3. **C - full Bambu job controls** (`upload`, `start`, `pause`, `resume`, `cancel`)

But the first implementation tranche in this branch is **A + B only**, with C's end-state interfaces and data model designed now so we do not need to break the printer stack twice.

## Product lanes

### Supported lane

The supported path is the one we are comfortable shipping:

- local-network Bambu support
- explicit user-provided printer configuration
- no cloud impersonation
- no dependence on Bambu Connect for core operation

This is the only lane the first implementation actively targets.

### Experimental lane

The design must leave space for future reverse-engineered or community-driven transports. These are explicitly not part of the first implementation and are not part of the supported product promise.

The practical meaning is architectural, not user-facing:

- do not hard-code the printer stack to Moonraker vs one Bambu implementation
- keep transport selection data-driven
- do not bake cloud or proprietary assumptions into repository interfaces

## Research update

The 2026-05-24 docs are still directionally correct, but the external landscape has clarified the boundaries:

- local/LAN support remains the only realistic supported path for a third-party Android app
- cloud-dependent control paths are higher risk and should not shape the primary architecture
- future community reverse-engineering should stay possible, but off the mainline product path

This branch therefore keeps the original A-F roadmap structure while tightening the product boundary: **local-first now, experimental community transport later if reality allows**.

## End-state scope for sub-project C

Sub-project C's design target is broader than the first code tranche:

- upload
- explicit start print
- pause
- resume
- cancel

It must support **two job source types**:

1. **Original Bambu jobs**
   Source file already authored for a Bambu printer.

2. **App-generated Bambu jobs**
   Produced by a future "slice for Bambu" flow or an app-side export/package path.

The design treats these as separate job kinds under one transport surface, not as one prematurely unified blob.

## Chosen architecture

### 1. Transport boundary first

The current `PrinterRepository` is tightly coupled to `MoonrakerClient`. That must change before Bambu can fit cleanly.

Introduce a `PrinterTransport` interface owned by the printer domain:

- state/status flow
- optional consumables / AMS flow
- optional camera flow
- connection test
- lifecycle (`start` / `stop`)

C extends the same surface with command methods:

- `uploadJob(...)`
- `startJob(...)`
- `pauseJob(...)`
- `resumeJob(...)`
- `cancelJob(...)`

The first implementation can return "unsupported" for the C methods on Bambu while the interface shape is established.

### 2. Provider-specific printer model

The persisted `Printer` model needs a provider discriminator instead of assuming Moonraker forever.

Add:

- `PrinterKind`
- provider-specific config payloads
- validation rules that ensure only the relevant config is populated

This lets the active-printer store remain the single source of truth for transport selection.

### 3. Repository owns transport switching

`PrinterRepository` remains the place that reacts to active-printer changes. It should:

- stop the old transport
- create the new transport from the active printer
- start the new transport
- forward transport flows into repository-owned `StateFlow`s consumed by the rest of the app

That preserves the current view-model shape as much as possible.

### 4. Job model split

Do not model "send to Bambu" as one undifferentiated file upload.

Add a small job-domain abstraction that distinguishes:

- original Bambu source job
- app-generated Bambu job

The initial code does not need the full command path, but the types should exist early enough that A+B code does not assume there will only ever be Moonraker-style raw G-code uploads.

## Bambu A+B behavior

### A - transport abstraction

No user-visible behavior change for existing Moonraker users.

Expected outcome:

- existing U1 / Moonraker paths still work
- existing polling, LED, webcam, send, and sync features keep their current behavior through a `MoonrakerTransport`
- Bambu can now be added as a new transport implementation instead of a giant conditional inside `PrinterRepository`

### B - Bambu LAN read-only

The first Bambu user-visible milestone is read-only:

- add a Bambu printer entry
- test connection
- show live printer status
- show AMS inventory when available
- reserve camera support in the architecture, but do not block the milestone on it if hardware handshake details are still uncertain

The Printer tab should adapt based on printer kind:

- Moonraker: existing controls
- Bambu read-only: status + AMS, with send/control affordances disabled or hidden

## C end-state behavior

The design target for C is:

- upload original Bambu jobs
- upload app-generated Bambu jobs
- explicit start-print confirmation
- pause/resume/cancel through the transport surface

This is the intended branch direction, but not required for the first "come back to a working app" checkpoint.

## Non-goals for the first implementation tranche

- cloud control
- Bambu Connect integration
- SSDP discovery
- full slice-for-Bambu engine/profile work
- experimental reverse-engineered transports
- trying to solve every camera model handshake up front

## Safety and UX rules

- Never start a physical print without explicit user confirmation.
- The first branch milestone must remain safe to try even if Bambu control is incomplete.
- A missing Bambu capability should surface as "unsupported for this printer" rather than failing implicitly.
- Existing Moonraker users must not pay for this branch with regressions in connect/send/polling.

## File-level design direction

The smallest coherent cut is:

- extend [`app/src/main/java/com/u1/slicer/data/Printer.kt`](D:/projects/u1-slicer-for-android/.worktrees/bambu-support/app/src/main/java/com/u1/slicer/data/Printer.kt:1) with provider-aware printer data
- keep [`app/src/main/java/com/u1/slicer/data/PrintersRepository.kt`](D:/projects/u1-slicer-for-android/.worktrees/bambu-support/app/src/main/java/com/u1/slicer/data/PrintersRepository.kt:1) as the persistence boundary
- refactor [`app/src/main/java/com/u1/slicer/printer/PrinterRepository.kt`](D:/projects/u1-slicer-for-android/.worktrees/bambu-support/app/src/main/java/com/u1/slicer/printer/PrinterRepository.kt:1) around a transport interface + factory
- keep [`app/src/main/java/com/u1/slicer/printer/PrinterViewModel.kt`](D:/projects/u1-slicer-for-android/.worktrees/bambu-support/app/src/main/java/com/u1/slicer/printer/PrinterViewModel.kt:1) stable where possible, then grow Bambu-specific state in focused additions

New files should carry the new boundaries rather than inflating existing large files:

- `printer/PrinterTransport.kt`
- `printer/MoonrakerTransport.kt`
- `printer/UnsupportedTransportOperations.kt` or equivalent command result types
- `printer/bambu/...` for Bambu-specific transport/model parsing helpers

## Milestones for this branch

### Milestone 1: A+B foundation

Working app state the user can try:

- multi-printer data model understands Moonraker vs Bambu
- app still works with existing Moonraker printers
- Bambu printer can be added and selected
- Bambu path can test connection and surface status state
- unsupported actions fail honestly, not mysteriously

### Milestone 2: richer B read-only

- AMS inventory surfaced
- camera path added if feasible
- Printer tab polished for Bambu branch

### Milestone 3: C command lane

- upload
- start with confirmation
- pause/resume/cancel

## Testing strategy

This branch should bias toward TDD and low-risk verification:

- unit tests first for new data-model and repository transitions
- unit tests first for transport-factory selection
- unit tests first for unsupported-command semantics
- focused JVM tests for persistence migration/versioning
- only targeted device/integration checks once the A+B foundation compiles cleanly

The first tryable checkpoint does not require real-hardware command coverage, but it does require a green JVM baseline and a working app build.

## Recommendation

Implement **A+B first** with C-shaped interfaces present from the start.

That gives the branch a stable backbone, keeps `main` releasable, and lets later Bambu send/control work land as additive behavior instead of a second structural rewrite.
