# Design: Adaptive Prepare Preview Quality for Large Painted Models

Date: 2026-06-15

## Problem

The current Prepare preview path uses hard triangle budgets. That is simple, but it
does not age well:

- painted / multi-colour models can lose too much geometry and turn into dots
- huge detailed models can still be expensive if the budget is too high
- fixed thresholds keep reappearing as regressions because they are model-blind

Current evidence from real-device checks:

- tall painted figures like Chubby Darth Vader need to stay on the solid-mesh path even when
  they exceed 2M triangles
- flat Hueforge-style painted files like Ghostface still need bounded loading behavior, but the
  current sparse fallback makes the Prepare preview visibly degraded

The app needs a preview that stays readable on large detailed meshes without
making the UI slow or brittle.

## Goal

Keep Prepare preview:

- visually solid for painted and multi-part models
- fast enough to remain interactive on mobile hardware
- stable across different model sizes and paint densities
- free of Kotlin-side hard-coded quality special cases

## Non-Goals

- perfect final-render fidelity in the preview
- changing the sliced output
- adding more one-off file-type heuristics in Compose

## Proposed Design

Move preview quality selection into the native preview pipeline and make it
adaptive instead of step-based.

### 1. Native preview quality controller

Add a small native policy object that decides how aggressively to simplify the
preview mesh.

Inputs:

- total triangle count
- whether the model has paint data
- model bounds / density
- current simplification cost or elapsed time
- optional view-dependent hints later, if available

Outputs:

- chosen preview tier
- target triangle count or stride
- whether another refinement pass should run

The Kotlin side should ask for "adaptive preview" rather than selecting a fixed
cap.

### 2. Progressive preview tiers

Build the preview in stages:

- Tier 0: quick solid preview
- Tier 1: better boundary-preserving simplification
- Tier 2: optional refinement for very large models if time remains

The user sees the first usable preview immediately, then the mesh can refine in
the background if the model is expensive.

### 3. Preserve paint boundaries

For painted / SEMM meshes, simplification must protect color boundaries and thin
surface features.

Rules:

- do not collapse across paint-state boundaries
- keep seam edges and sharp feature edges intact
- prefer topology-preserving simplification over blind stride skipping

The Ghostface result is the current proof that stride-only fallback is not good enough as the
long-term answer: it stays bounded and on-bed, but still looks broken to the user.

This is the main reason the current bug class keeps coming back: generic
decimation can destroy the structure that makes the preview readable.

### 4. Fallback safety guard only

Keep a hard cap only as an emergency OOM guard.

That cap should be a safety net, not the normal quality policy.

## Kotlin Contract

The Kotlin UI should:

- request adaptive preview quality
- show the existing loading overlay while the first preview tier builds
- optionally display a "refining preview" state if a background upgrade is
  still running

Kotlin should not need separate painted vs non-painted triangle budgets.

## Tests

Add tests at two levels:

- native policy tests for tier selection and boundary-preserving behavior
- regression tests that prove painted models still render as a solid preview

Good regression coverage should include:

- a painted multi-colour model that previously turned into dots
- a huge detailed model that still stays within an acceptable time / memory budget
- a normal STL that keeps the current fast path behavior

## Migration Plan

1. Keep the current working fix in place.
2. Introduce the native adaptive policy behind a narrow Kotlin contract.
3. Make the Kotlin preview request go through the new adaptive policy.
4. Remove the old fixed-budget branching once the adaptive path is proven.

## Open Questions

- Should refinement be time-based, triangle-budget-based, or both?
- Should the policy consider camera distance and zoom level?
- Should painted models get a stricter boundary-preservation mode than plain STL?
- What telemetry, if any, is needed to tune the tiers safely?
