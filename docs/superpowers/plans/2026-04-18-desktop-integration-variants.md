# Desktop Integration Variants Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Capture the desktop delivery options discussed in the thread once the shared core/CLI exists, with a clear recommendation order and explicit trade-offs.

**Important:** This plan is downstream of:
- Stage 0.5 hardening
- Stage 0 artifact export learning
- shared cross-platform core/CLI extraction

Do not start here unless those foundations are already in place or intentionally skipped.

---

## Recommendation Order

1. **Helper / launcher workflow**
   - Best first desktop UX-to-effort ratio
   - Lowest maintenance risk

2. **Thin desktop GUI wrapper**
   - Good if drag-and-drop is needed for non-technical users
   - Still keeps shared core authoritative

3. **Direct Orca integration**
   - Best ultimate UX
   - Highest maintenance and coordination cost

4. **Local service / web server**
   - Only worth it for special workflows
   - Adds operational complexity

5. **Cloud-hosted service**
   - Not recommended

---

## Variant A: Helper / launcher workflow

### Goal

Allow users to open a Bambu `3mf`, run the shared tool on it, and then launch Orca against the generated sanitized/prepared file.

### User experience

- drag file onto helper
- or use context menu / file association
- helper emits sanitized/prepared file to temp or chosen location
- helper launches Orca with the rewritten file

### Work items

- [ ] Add a small desktop launcher script/app around the shared CLI
- [ ] Support:
  - `sanitize and open in Orca`
  - `prepare and open in Orca`
  - optional `save rewritten copy without opening`
- [ ] Decide whether outputs live in:
  - temp directory
  - alongside original file
  - user-selected output directory
- [ ] Add clear filenames and cleanup policy

### Best fit

- First Windows delivery
- Also easy to adapt for macOS and Linux

### Risks

- Need reliable Orca executable detection
- Need to avoid confusing temp-file behavior

---

## Variant B: Thin desktop GUI wrapper

### Goal

Provide a tiny cross-platform GUI around the shared CLI/core for users who want a visual tool rather than a terminal or launcher script.

### Recommended scope

- drag-and-drop input
- inspect summary
- buttons:
  - `Sanitize`
  - `Prepare`
  - `Open in Orca`
- optional artifact preview metadata

### Work items

- [ ] Choose desktop UI stack only after the CLI exists
- [ ] Keep the UI thin and wrapper-like
- [ ] Reuse shared core models for inspect output
- [ ] Ensure Windows/macOS/Linux packaging is not dramatically harder than the CLI itself

### Best fit

- Friendly non-terminal UX
- Still compatible with shared-core maintainability

### Risks

- Packaging and distribution complexity
- Temptation to reimplement logic in the UI layer

---

## Variant C: Direct Orca integration

### Goal

Make Orca itself run the compatibility pipeline during import/open so users never think about the helper step.

### Preconditions

- Shared core behavior is stable
- Stage 0 artifact testing has shown which transformation actually matters
- We are willing to own an Orca fork or upstreaming process

### Work items

- [ ] Decide integration shape:
  - in-process port of the sanitizer/core logic
  - helper-process invocation from Orca
  - plugin/hook if Orca exposes one
- [ ] Identify Orca import/open path where Bambu preprocessing should happen
- [ ] Preserve the ability to inspect failures and artifacts
- [ ] Add Orca-side regression coverage if possible

### Best fit

- Highest-end UX if adopted successfully

### Risks

- Maintaining a fork
- Rewriting stable Kotlin logic into C++ too early
- Upstream friction
- Two implementations drifting if helper/core remains separate

### Recommendation

Do this only after the shared core and helper workflow have proven the value and stabilized semantics.

---

## Variant D: Local service / local web server

### Goal

Run the compatibility pipeline as a local background service that accepts files or requests from:
- a browser
- another desktop app
- a future Orca helper

### Possible use cases

- browser-based upload/download flow
- shared local endpoint used by multiple wrappers
- sub-hour automation or watch-folder style workflows

### Work items

- [ ] Expose local-only API around shared core
- [ ] Add file upload/download endpoints
- [ ] Add strong local-path/privacy boundaries
- [ ] Decide service lifecycle and install story

### Best fit

- Only when multiple local tools need the same long-running bridge

### Risks

- More moving parts than a CLI/helper
- Background process lifecycle and port management
- Harder to debug than a simple one-shot tool

### Recommendation

Defer unless a concrete multi-client requirement emerges.

---

## Variant E: Cloud-hosted web service

### Goal

Upload a Bambu file to a remote service, sanitize/prepare it server-side, then download the result.

### Why this is not recommended

- large file uploads
- privacy concerns around model files
- hosting/storage cost
- added failure modes
- no maintainability advantage over the shared-core CLI

### Recommendation

Do not pursue unless there is a deliberate product decision to build a hosted service.

---

## Cross-Variant Decision Inputs

Use these before choosing a desktop wrapper:

- [ ] Which artifact actually helps desktop Orca?
  - sanitized only
  - sanitized + embedded
  - both

- [ ] Who is the first audience?
  - internal debugging
  - power users
  - general users

- [ ] Do we need macOS/Linux at the same time as Windows?
  - if yes, avoid Windows-only UI/tooling choices

- [ ] Are we comfortable owning an Orca fork?
  - if not, direct integration is likely the wrong first move

---

## Recommended Near-Term Execution

- [ ] Implement Variant A first: helper / launcher workflow
- [ ] Optionally add Variant B second: thin GUI wrapper
- [ ] Defer Variant C until the shared core semantics are stable
- [ ] Keep Variant D and E deferred unless new requirements clearly justify them

---

## Exit Criteria

- [ ] Future agents can choose a desktop integration path intentionally rather than re-litigating the options from scratch.
- [ ] The project has an explicit bias toward shared-core maintainability and cross-platform support.
