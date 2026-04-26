# Cross-Platform Bambu Core + CLI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract the Bambu 3MF compatibility logic from the Android app into a shared cross-platform module and build a desktop CLI that runs on Windows, macOS, and Linux.

**Why this is the recommended maintainability path:** It keeps Android and desktop on the same implementation so sanitizer/parser/embedder fixes land once and propagate everywhere.

**Thread conclusion:** This is a better long-term path than:
- a Windows-only rewrite
- direct Orca patching as the first move
- a hosted web service

---

## Target Outcome

A shared library and CLI with commands like:

```bash
u1-3mf-tool inspect input.3mf
u1-3mf-tool sanitize input.3mf output.3mf
u1-3mf-tool embed input.3mf output.3mf
u1-3mf-tool prepare input.3mf output.3mf
```

Where:
- `sanitize` = Bambu cleanup only
- `embed` = inject U1 profile assumptions/config
- `prepare` = the agreed end-to-end desktop boundary after Stage 0 export experiments

---

## Architecture Recommendation

### Preferred near-term implementation

Use **shared Kotlin code** first.

Reasoning:
- existing logic is already Kotlin
- most work is ZIP/XML/config processing
- Windows, macOS, and Linux can all run a JVM CLI
- Android can keep using the same code with fewer translation risks

### Suggested module split

- `:bambu-core`
  - parser, sanitizer, embedder, artifact models, shared tests
- `:desktop-cli`
  - command-line wrapper around `:bambu-core`
- existing Android app module
  - depends on `:bambu-core`

If Kotlin Multiplatform is too much upfront, plain JVM-first extraction is acceptable as an intermediate step.

---

## File Map

| File/Module | Action | Responsibility |
|-------------|--------|---------------|
| `settings.gradle` | Modify | Register new shared/core and CLI modules |
| `build.gradle` or module build files | Modify | Add module wiring and shared dependencies |
| `app/src/main/java/com/u1/slicer/bambu/ThreeMfParser.kt` | Refactor/move | Shared parser logic |
| `app/src/main/java/com/u1/slicer/bambu/BambuSanitizer.kt` | Refactor/move | Shared sanitizer logic |
| `app/src/main/java/com/u1/slicer/bambu/ProfileEmbedder.kt` | Refactor/split | Separate Android asset/context dependencies from shared config logic |
| `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` | Modify | Switch Android app to use extracted shared core |
| `new module: bambu-core/` | Create | Cross-platform shared implementation |
| `new module: desktop-cli/` | Create | CLI entry point |
| shared tests | Create/move | Shared unit coverage for parser/sanitizer/embedder |

---

## Task 1: Define the shared API boundary

- [ ] Decide what the shared core should expose as stable public API.

- [ ] Suggested entry points:

```kotlin
interface BambuPipeline {
    fun inspect(input: File): InspectResult
    fun sanitize(input: File, outputDir: File): File
    fun extractPlate(input: File, plateId: Int, outputDir: File): File
    fun embed(input: File, request: EmbedRequest, outputDir: File): File
    fun prepare(input: File, request: PrepareRequest, outputDir: File): PreparedArtifacts
}
```

- [ ] Define stable models for:
  - file inspection
  - artifact bundle
  - embed request
  - profile/config source

**Success criteria**
- Android and desktop can both call the same core through the same concepts.

---

## Task 2: Separate Android-specific concerns from shared logic

- [ ] Remove or abstract:
  - `android.util.Log`
  - `Context` asset loading
  - Android-only parser assumptions where practical

- [ ] Replace logging with a tiny shared logger interface or no-op callback.

- [ ] Split `ProfileEmbedder` into:
  - shared config merge/embed logic
  - Android-specific asset/profile provider

- [ ] Make profile loading injectable:

```kotlin
interface ProfileSource {
    fun loadPrinterProfile(): Map<String, Any>
    fun loadProcessProfile(): Map<String, Any>
    fun loadDefaultFilamentProfile(): Map<String, Any>
}
```

**Success criteria**
- The desktop CLI can run the same embed logic without an Android `Context`.

---

## Task 3: Move tests with the logic

- [ ] Migrate pure logic tests into the shared core module.

- [ ] Prioritize moving:
  - `BambuSanitizerTest`
  - `ThreeMfParserTest`
  - `ProfileEmbedderTest`
  - relevant pieces of `MergeThreeMfInfoTest` if they become shared helpers

- [ ] Keep Android-only tests in the app module for:
  - native loading/slicing
  - preview rendering
  - Activity/ViewModel wiring

**Success criteria**
- Shared logic stays covered without needing the Android runtime.

---

## Task 4: Build the desktop CLI

- [ ] Add a CLI module with commands:
  - `inspect`
  - `sanitize`
  - `embed`
  - `prepare`

- [ ] Implement machine-readable and human-readable output modes.

- [ ] Suggested `inspect` output fields:
  - `isBambu`
  - `isMultiPlate`
  - `detectedExtruderCount`
  - `hasPaintData`
  - `hasPaintSupports`
  - `hasLayerToolChanges`
  - plate count
  - used extruder indices
  - archive sizing risk if present

- [ ] Add a `--json` flag for future automation.

**Success criteria**
- A developer on Windows/macOS/Linux can run the tool without Android.

---

## Task 5: Decide the desktop profile source strategy

- [ ] Reuse the existing bundled Snapmaker U1 profiles from the Android app if licensing/layout allows.

- [ ] If not, create a shared profiles directory in the new core or CLI module.

- [ ] Ensure there is one canonical source of:
  - printer profile
  - process profile
  - default filament profile

**Success criteria**
- Android and desktop embed against the same profile assumptions rather than drifting copies.

---

## Task 6: Switch Android to use the extracted core

- [ ] Replace direct calls to app-local parser/sanitizer/embedder classes with calls through the shared core boundary.

- [ ] Keep Android-specific orchestration in the ViewModel:
  - URI import
  - document save/export
  - Compose UI state
  - native model load/slice

- [ ] Verify that exported stage-0 artifacts remain identical or intentionally different after the extraction.

**Success criteria**
- Android becomes a thin orchestrator around the shared core rather than the canonical implementation.

---

## Task 7: Preserve stage-0 experiment learnings in the CLI contract

- [ ] Use the Stage 0 export results to decide whether the desktop CLI should treat:
  - sanitized artifact
  - sanitized+embedded artifact
  as first-class outputs.

- [ ] If both are useful, keep both as explicit commands or flags.

- [ ] Do not collapse them prematurely into one opaque `prepare` command if desktop testing still depends on differentiating the two stages.

**Success criteria**
- The CLI reflects the real artifact boundaries we care about, not just implementation convenience.

---

## Task 8: Add cross-platform smoke verification

- [ ] Add a small smoke script or CI instructions for:
  - Windows
  - macOS
  - Linux

- [ ] Verify:
  - CLI starts
  - `inspect` works on representative assets
  - `sanitize` produces a valid `3mf`
  - `prepare` produces a valid `3mf`

- [ ] If full desktop runners are not available yet, at least document manual smoke-check steps.

**Success criteria**
- "Cross-platform" means actually runnable, not just theoretically portable.

---

## Verification

- [ ] Shared unit tests pass
- [ ] Android app still passes its unit and instrumented suites
- [ ] CLI smoke-tests pass on at least one non-Android environment

---

## Exit Criteria

- [ ] Shared Bambu core exists outside the Android app module.
- [ ] Android uses that shared core.
- [ ] A CLI exists for Windows/macOS/Linux.
- [ ] New parser/sanitizer/embedder fixes can land once and benefit all wrappers.
