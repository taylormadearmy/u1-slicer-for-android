# Bambu Desktop + Pipeline Roadmap

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Capture the staged path discussed in the thread for turning the current Android-only Bambu 3MF pipeline into something robust, inspectable, and eventually reusable on desktop.

**Primary recommendation:** Do not start with Orca integration or a Windows-only rewrite. First harden the Android pipeline, then expose the pipeline artifacts from Android, then extract the portable core into a cross-platform desktop tool, and only then decide whether direct Orca integration or a helper wrapper is worth the maintenance cost.

---

## Recommended Order

1. **Stage 0.5: Harden the existing Android Bambu pipeline**
   - Plan: [`2026-04-18-bambu-pipeline-hardening-stage-0.5.md`](./2026-04-18-bambu-pipeline-hardening-stage-0.5.md)
   - Why first: current regressions are often caused by stage-boundary ambiguity and duplicated pipeline logic, not just missing features.

2. **Stage 0: Add artifact inspection and export from Android**
   - Plan: [`2026-04-18-inspect-model-export-stage-0.md`](./2026-04-18-inspect-model-export-stage-0.md)
   - Why second: once artifacts are trustworthy, exporting `sanitized` and `sanitized+embedded` 3MFs becomes a high-signal experiment for Windows Orca.

3. **Stage 1: Extract a shared cross-platform Bambu core + CLI**
   - Plan: [`2026-04-18-cross-platform-bambu-core-cli.md`](./2026-04-18-cross-platform-bambu-core-cli.md)
   - Why third: this is the maintainable path that lets Android fixes flow to Windows, macOS, and Linux.

4. **Stage 2: Choose the desktop integration surface**
   - Plan: [`2026-04-18-desktop-integration-variants.md`](./2026-04-18-desktop-integration-variants.md)
   - Recommendation: start with a helper/launcher workflow; defer direct Orca integration and local-service options until the shared core has settled.

---

## Evidence We Expect To Gather Along The Way

- [ ] Stage 0.5 proves the Android pipeline stages are explicit, testable, and less duplicated.
- [ ] Stage 0 proves whether desktop Orca benefits primarily from:
  - sanitized structure alone
  - sanitized + embedded config
  - or some later Android-specific behavior
- [ ] Stage 1 proves that the current Kotlin pipeline can be lifted into a reusable non-Android module without losing coverage.
- [ ] Stage 2 proves whether a thin desktop wrapper is sufficient or direct Orca integration is worth the additional maintenance burden.

---

## Decision Gates

### Gate A: After Stage 0.5

Proceed only if:
- [ ] the duplicated import pipeline has been consolidated or at least wrapped behind a common helper
- [ ] representative artifact-contract tests exist
- [ ] the regression corpus still passes

### Gate B: After Stage 0

Proceed to desktop work only if:
- [ ] exported `sanitized.3mf` and `sanitized-embedded.3mf` can be generated reliably
- [ ] at least one desktop Orca smoke-test round has been run on exported artifacts
- [ ] we know which artifact type is the useful desktop boundary

### Gate C: After Stage 1

Choose desktop wrapper direction only if:
- [ ] Android and desktop wrappers can both call the same shared core
- [ ] the shared CLI has stable output contracts
- [ ] test coverage moved with the core instead of forking

---

## Explicitly Deferred

- [ ] direct modification of Windows Orca import flow before the shared core exists
- [ ] a cloud-hosted sanitizing service
- [ ] a Windows-only C++ rewrite of the Kotlin sanitizer/parser/embedder
- [ ] broad UX work on Jobs page integration before the preview-side debug surface exists

---

## Notes For Future Agents

- The current Android app already tracks both key intermediate artifacts:
  - `sourceModelFile` for the processed/sanitized source path
  - `currentModelFile` for the current embedded working path
  See [SlicerViewModel.kt](C:\Users\kevin\projects\u1-slicer-orca\app\src\main\java\com\u1\slicer\SlicerViewModel.kt:311).

- The current thread concluded that the best maintainability path is a **shared core library plus thin wrappers**, not separate Android and desktop implementations.

- The current regression corpus is already strong. Stage 0.5 should focus on **holes in contracts and duplicated paths**, not on replacing the existing test strategy wholesale.
