# True First-Class Bambu Slicing Design

**Date:** 2026-07-04
**Branch:** `codex/bambu-support`
**Status:** Approved for implementation planning
**Builds on:** [`2026-07-02-bambu-printer-branch-design.md`](D:/projects/u1-slicer-for-android/.worktrees/bambu-support/docs/superpowers/specs/2026-07-02-bambu-printer-branch-design.md:1)

## Goal

Turn Bambu support from a passthrough beta lane into a true first-class slicing target in the app.

End-state intent:

- generic multi-model Bambu architecture
- A1 Mini as the first real validation target
- full in-app flow: slice -> upload -> start
- parity-first evaluation against existing Snapmaker/U1 features
- no regression to the stable Snapmaker/Moonraker path

This design explicitly rejects an "export/package after the old slice" approach. Bambu must become a real slicer target, not a delivery wrapper around a U1-shaped result.

## Product Decision

We are choosing **true Bambu slicing** over a packaging-only path.

That means the app must eventually support:

- selecting a Bambu target before slicing
- resolving machine/process/material config for that target
- generating a Bambu-native job artifact
- delivering that artifact through the Bambu transport stack

The current original-3MF passthrough beta remains useful as an interim lane, but it is not the final architecture.

## Scope

### In scope

- first-class slicer target model
- generic Bambu target family architecture
- A1 Mini as first validated real printer target
- full loop for the first validated target: slice, upload, start
- capability-driven parity evaluation per feature
- artifact model that supports Moonraker and Bambu as sibling output families
- preservation of current Snapmaker/Moonraker stable behavior

### Out of scope for the first implementation wave

- every Bambu model being fully production-proven immediately
- cloud-dependent control paths
- pretending technically blocked parity features already exist
- merging Bambu and Snapmaker slicing into one ambiguous targetless pipeline

## Design Principles

1. **Bambu is a real target, not a packaging mode.**
2. **Slice target and connected printer are related but distinct concepts.**
3. **Moonraker/Snapmaker remains the stable product lane throughout.**
4. **Parity is judged feature by feature, not claimed globally.**
5. **A1 Mini keeps the work honest, while the architecture stays generic.**
6. **The output artifact is target-specific, not secretly always "just G-code".**

## Architecture

The architecture splits into five layers.

### 1. Target model

Add a slicer-side target abstraction separate from transport selection.

Examples:

- `SnapmakerU1`
- `BambuA1Mini`
- future Bambu targets such as `BambuP1S`, `BambuP1P`, `BambuA1`

This is the thing we slice for. It is not identical to the configured connected printer entry.

### 2. Capability model

Each target advertises what it can actually support.

Examples:

- single-color support
- AMS / multi-material support
- preview assumptions
- imported-profile compatibility
- feature flags for ColorMix or top-surface-specific behaviors
- explicit unsupported states

This capability surface is where case-by-case parity decisions live.

### 3. Target-specific config resolution

The current config pipeline contains many U1-shaped assumptions. Introduce a resolution layer that converts shared app intent into target-specific resolved config.

Instead of one implicit universal config, the flow becomes:

- shared app state and user intent
- target-aware machine/process/material resolution
- resolved config for the chosen slice target

The first step is routing the existing Snapmaker path through this layer with no behavior change.

### 4. Artifact generation

Slice output becomes a typed job artifact family rather than a single "file path + extras" shape.

Two sibling artifact families are required:

- **Moonraker job artifact**
  - G-code
  - preview thumbnail data
  - Moonraker-facing metadata
  - existing stable send/start assumptions

- **Bambu job artifact**
  - Bambu-targeted plate/job bundle
  - Bambu-facing metadata/layout
  - plate-aware information
  - AMS/material mapping information
  - enough structure for a native slice -> upload -> start path

This keeps Moonraker and Bambu as equal citizens instead of treating one output type as the hidden canonical result.

### 5. Delivery

After artifact generation, transport takes over:

- Moonraker delivery for Snapmaker/U1
- Bambu delivery for Bambu targets

The current Bambu upload/start beta work becomes the destination layer for future true Bambu slice outputs.

## Parity Policy

Parity is evaluated per feature and per target using the capability model.

Each feature must be classified as one of:

1. **Native parity**
   - first-class and trustworthy on the target

2. **Target-specific implementation**
   - same user-facing concept, different internals for Bambu

3. **Explicitly unsupported for now**
   - surfaced honestly in capabilities and UI

This policy avoids silent degradation and avoids fake parity claims.

Features to evaluate case by case include:

- preview behavior
- multi-material / AMS
- slicing overrides
- imported process/material profiles
- ColorMix
- top-surface mixing behaviors
- any send/start semantics that differ from Moonraker

## Relationship to Existing Bambu Passthrough Beta

The current branch already supports:

- Bambu printer entries
- Bambu status/camera/AMS work
- original sliced Bambu 3MF passthrough upload/start beta

That work remains valuable, but it becomes an interim lane and validation tool rather than the final architecture for Bambu slicing.

This new design does not throw the transport work away. It repositions it as the delivery side of a true Bambu-targeted slice pipeline.

## Rollout Strategy

Implementation should proceed in stages that preserve releasability of the stable Snapmaker path.

### Stage 1: Slicer target foundation

- introduce slicer target as a first-class concept
- route current U1 slicing through it with no behavior change
- define generic Bambu target family shape

### Stage 2: Capability and parity framework

- add per-target capability model
- define explicit feature classification
- wire UI and pipeline decisions to capabilities where needed

### Stage 3: Bambu config resolution

- add Bambu-targeted machine/process/material resolution
- keep current Snapmaker resolution intact unless intentionally generalized

### Stage 4: Bambu artifact generation

- define the Bambu job artifact family
- generate a true Bambu-targeted artifact from in-app slicing

### Stage 5: Full-loop Bambu execution

- connect true Bambu artifacts to upload/start
- validate on A1 Mini

### Stage 6: Parity passes

Review and implement feature parity case by case:

- preview
- AMS / multi-material
- overrides
- imported profiles
- ColorMix
- top-surface feature set
- other target-sensitive paths discovered during implementation

## Testing Strategy

Testing must prove both progress toward Bambu and protection of Snapmaker.

### Unit tests

- target selection and model invariants
- capability classification
- resolved-config branching
- artifact typing and routing

### JVM integration tests

- U1 path still resolves and emits the same effective artifact behavior
- Bambu target chooses Bambu-specific config/artifact flows
- unsupported capability paths fail honestly

### Device validation

- Pixel 8a + A1 Mini for the first real end-to-end Bambu target
- validation target for slice -> upload -> start

### Regression sweeps

- focused Snapmaker/Moonraker verification after each milestone
- repeated full JVM baseline verification

Milestones do not count as complete if they expand Bambu support by regressing the stable Snapmaker lane.

## Initial Decomposition

This work should be planned and implemented as six sub-projects inside the same branch:

1. **Slicer target foundation**
2. **Capability and parity framework**
3. **Bambu config resolution**
4. **Bambu artifact generation**
5. **Full-loop Bambu execution**
6. **Parity passes**

The recommended delivery style is hybrid:

- architecture remains generic multi-model Bambu
- A1 Mini is the first real proving target
- feature parity decisions are reviewed case by case against that backbone

## Acceptance Direction

The design is heading toward a state where:

- Bambu is a first-class slicer target
- the app can produce a true Bambu-targeted job artifact
- A1 Mini can run the full slice -> upload -> start loop
- Snapmaker/Moonraker remains stable and releasable throughout
- parity claims are evidence-based and feature-specific

## Recommendation

Proceed in the same branch, but treat true Bambu slicing as a new structured implementation effort rather than an incremental extension of passthrough send.

Use the existing Bambu transport beta as a delivery foundation, while building the true target/capability/config/artifact pipeline needed for first-class Bambu support.
