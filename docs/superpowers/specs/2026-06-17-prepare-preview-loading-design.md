---
title: Prepare preview loading and performance design
date: 2026-06-17
status: approved
---

# Prepare Preview Loading And Performance

## Goal

Improve Prepare preview responsiveness without repeating the large-model regressions that have
produced sparse "cloud of dots" previews.

The work will happen in two phases:

1. Speed up the current final-quality preview path and add reliable measurement.
2. Add progressive loading only for large or high-risk models, where the first preview may be
   sparse briefly as long as it is clearly transitional and automatically improves.

## Problem Summary

The current Prepare preview path is mostly a one-shot pipeline:

- `MainActivity.kt` requests one native preview mesh through `NativeLibrary.getPreparePreviewMesh(...)`
- native `sapil_model.cpp` chooses a triangle budget / decimation strategy and emits one mesh
- Kotlin `NativePreviewMesh.toMeshData()` expands that into the GL interleaved buffer
- `ModelViewerView` / `ModelRenderer` upload the mesh and recolour it

This has three recurring pain points:

- very large models can take too long before any visible result appears
- hard triangle-budget behavior can over-stride certain models into sparse or broken previews
- performance regressions are hard to reason about because the hot path spans native generation,
  JNI transfer, Kotlin mesh expansion, recolouring, and GL upload

## Constraints

- Preview correctness matters more than raw speed when the tradeoff is severe
- Sparse / dotted preview is acceptable only as an obviously temporary refinement stage
- Sparse / dotted preview must never become the steady final result
- Painted / MMU / Hueforge-like models are the highest-risk class for regressions
- Normal STL and ordinary 3MF models should keep a simple path when they already behave well
- The design must fit the existing Android + JNI + GL architecture rather than rewriting the
  viewer stack

## Current Architecture Notes

### Kotlin fetch path

The current fetch and conversion path lives primarily in:

- `app/src/main/java/com/u1/slicer/MainActivity.kt`
- `app/src/main/java/com/u1/slicer/NativeLibrary.kt`
- `app/src/main/java/com/u1/slicer/viewer/NativePreviewMesh.kt`
- `app/src/main/java/com/u1/slicer/viewer/ModelViewerView.kt`
- `app/src/main/java/com/u1/slicer/viewer/ModelRenderer.kt`

Important current behavior:

- preview fetch is serialized with `NativeLibrary.previewMutex`
- the composable maintains a single `mesh` value and a single loading state
- `SlicerViewModel.cachedPrepareMesh` already avoids some reload cost on tab switch
- recolour and GL upload avoidance already exist in limited form (`lastSetMesh`)
- timing logs already exist around `getPreparePreviewMesh()` and `toMeshData()`

### Native preview path

The preview generator lives primarily in:

- `app/src/main/cpp/src/sapil_model.cpp`
- `app/src/main/cpp/src/slicer_wrapper.cpp`
- `app/src/main/cpp/include/sapil.h`

Important current behavior:

- non-MMU volumes can use QEM or stride depending on total size and time budget
- painted / MMU volumes already use special handling because blind decimation has caused major
  regressions
- a cached native preview mesh is already returned on repeated calls
- flat-model heuristics already exist

This means Phase 1 should refine and measure the current contract before Phase 2 adds a new one.

## Phase 1: Faster Final Preview Path

### Objective

Make the current one-shot preview materially faster and easier to reason about without changing the
user-facing loading model yet.

### Changes

#### 1. Add explicit timing breakdowns for every preview load

Promote the current ad hoc timing logs into a stable breakdown covering:

- CPU-side preview build timing:
  - native preview generation time
  - JNI payload size / triangle count
  - `NativePreviewMesh.toMeshData()` time
- presentation timing on the viewer / GL side:
  - mesh upload time
  - recolour time
- cache-hit vs cache-miss path

This should let us answer whether slow loads are mostly native generation, Kotlin buffer expansion,
GL-thread presentation cost, or repeated work after the first load.

The split is important because the current fetch coroutine naturally sees CPU-side work, while mesh
upload and colour refresh happen later on the viewer / renderer side. Phase 1 must keep those
timing families distinct so we do not optimize against misleading numbers.

#### 2. Tighten cache behavior

Audit cache invalidation and reuse across:

- returning from G-code preview
- plate switches
- colour-only changes
- camera-only changes
- repeated loads of the same unmodified model state

The target is to avoid rebuilding or re-uploading preview data when only palette state changes.

#### 3. Reduce Kotlin-side rebuild churn

Focus on avoiding repeated:

- `toMeshData()` conversion when the raw preview is unchanged
- recolour work when the palette did not materially change
- mesh upload when only colours need refresh

The existing `lastSetMesh` and cached mesh behavior should be extended rather than replaced.

#### 4. Make native behavior measurable before changing heuristics

Keep the current native contract in place, but document and test when each path is chosen:

- direct full mesh
- QEM simplification
- stride fallback
- painted/MMU special handling

The target is a trustworthy baseline for Phase 2.

### Acceptance Criteria

- ordinary model loads are measurably faster or avoid repeated work
- repeated preview visits become more consistently cache-backed
- logs clearly show where time is spent for representative large files
- no preview quality semantics change is required to get the gains

## Phase 2: Hybrid Progressive Loading For Large / High-Risk Models

### Objective

Add progressive loading only where it improves perceived performance enough to justify the extra
state complexity.

### Core Rule

Sparse preview is allowed only as a visible refinement stage, never as the resting final state.

### Model Classes That Can Use Progressive Loading

Progressive loading should be considered for:

- very large triangle-count models
- flat high-detail plates
- painted / MMU / Hueforge-like models with expensive preview generation

Normal STL and ordinary 3MF models that already load quickly should remain one-shot.

Eligibility must be based on the selected plate's resolved preview state, not just coarse file-level
metadata. In particular, the progressive gate must use the same plate-aware paint / MMU / layer-tool
signals that currently drive preview quality decisions, so a multi-plate file cannot accidentally
route the wrong plate through the wrong preview policy.

### User Experience

The UI should make preview state explicit:

- no preview yet: `Preparing preview`
- coarse preview visible and refinement pending: `Refining preview`
- final preview ready: loading state cleared

The user should never have to guess whether a sparse preview is broken or still improving.

### Data / State Model

Kotlin should move from a single-result preview state to a versioned preview session:

- preview request id / generation token
- zero or more preview stages for the same model state
- final-stage marker
- cancellation when model state changes mid-build

This prevents old refinement results from replacing newer model-state requests.

If refinement is cancelled by rotation, plate switch, model reload, or app lifecycle changes, the
session must explicitly resolve into one of two states:

- keep the currently displayed coarse stage and immediately start a new session for the new model
  state, with the UI still indicating that refinement was interrupted
- or bypass progressive mode for that transition and run the existing one-shot final preview path

The design must not silently strand the user on a coarse preview that looks final.

### Native Contract

Instead of "return one preview mesh for this budget", native should conceptually support:

- quick stage request
- refined stage request
- final stage detection

The exact API can be synchronous multi-call or a small staged descriptor, but the boundary should
stay native-first so Kotlin does not hard-code model-specific quality rules.

The contract must also account for the current global `previewMutex` model:

- a quick-stage request must finish quickly enough not to monopolize the native lock
- refinement work must be cancellable before and during native generation
- refinement must yield cleanly to higher-priority model mutations or newer preview requests
- the app must never queue long refinement work that blocks the next user-visible preview update

In practice, this means Phase 2 should treat refinement as opportunistic background work layered on
top of the current lock discipline, not as a second full-priority preview build that serializes
behind the same mutex and delays everything else.

### Quality Policy

The first visible stage can be sparse if needed, but:

- it must appear quickly
- it must schedule a better stage automatically
- it must not be the only result for high-risk files

For painted / MMU / Hueforge-like models:

- avoid blind stride as the default final-quality strategy
- preserve a path toward a solid refined preview
- keep the current special-case regression knowledge during rollout

### Rollout Shape

Phase 2 should initially be narrow:

- enable progressive loading only for models above a conservative threshold
- keep one-shot behavior for normal files
- keep emergency safety caps as guardrails, not primary quality policy

## Testing Strategy

### Phase 1 tests

- unit tests around any new cache / state helpers
- regression tests proving colour-only updates do not force unnecessary mesh rebuilds
- instrumentation or log-driven verification for cache-hit / cache-miss timing paths

### Phase 2 tests

- unit tests for preview-stage state transitions and cancellation behavior
- regression tests proving stale refinement results cannot overwrite newer requests
- instrumented preview tests for representative worst-case assets:
  - large flat file
  - painted / MMU file
  - tall detailed figure
- explicit assertions that sparse first-stage preview transitions to a refined result

### Regression guardrails

The following must remain explicit acceptance checks:

- no permanent cloud-of-dots final preview for large painted files
- no misleading sparse preview with no visible refinement state
- no regressions for ordinary STL / 3MF files that should stay simple and fast

## Risks

### Phase 1 risks

- instrumentation can add noise if it is too chatty or not sampled carefully
- cache reuse can become incorrect if invalidation boundaries are too broad

### Phase 2 risks

- stale staged results can race with model edits or plate changes
- progressive state can increase Compose complexity if bolted into existing effects carelessly
- a coarse-first strategy can reintroduce the dotted-preview regression if refinement is not
  guaranteed and visible

## Recommended Execution Sequence

1. Implement Phase 1 instrumentation and one-shot performance improvements first.
2. Capture baseline timings and confirm the biggest bottlenecks.
3. Introduce a narrow progressive contract for large / high-risk models only.
4. Expand the progressive policy only after regression fixtures prove the dotted-preview class is
   controlled.

## Out Of Scope

- full viewer-stack rewrite
- changing slice output or print behavior
- broad refactors unrelated to preview loading
- removing existing emergency triangle guards before the progressive path is proven
