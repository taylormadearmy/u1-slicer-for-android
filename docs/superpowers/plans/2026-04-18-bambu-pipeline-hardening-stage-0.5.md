# Bambu Pipeline Hardening Stage 0.5 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce pipeline fragility before adding export/refactor work by clarifying stage contracts, consolidating duplicated pipeline entry points, and filling the remaining test holes around artifact boundaries and repeated operations.

**Problem statement:** The project already has a strong regression corpus, but regressions still occur because the Bambu 3MF pipeline is spread across multiple entry points in `SlicerViewModel`, and some tests defend intended behavior without always calling the exact production helpers that later drift.

**Non-goal:** This stage is not "fix every weird Bambu file." It is a hardening pass to make future fixes safer and more local.

---

## Current Strengths To Preserve

- `androidTest/assets` already contains a valuable real-world corpus:
  - `colored_3DBenchy (1).3mf`
  - `Dragon Scale infinity*.3mf`
  - `Shashibo-h2s-textured.3mf`
  - `Flarewing-Dragon_100%_4FilamentMulticolor_v1.1.3mf`
  - `flippy+flappy+mini*.3mf`
  - `SENSORY+TWIST+BALL+FIDGETS+optimised.3mf`
  - `u1-auxiliary-fan-cover-hex_mw.3mf`
- Integration coverage is already strong in:
  - [BambuPipelineIntegrationTest.kt](C:\Users\kevin\projects\u1-slicer-orca\app\src\androidTest\java\com\u1\slicer\slicing\BambuPipelineIntegrationTest.kt:24)
  - [ProfileEmbedderIntegrationTest.kt](C:\Users\kevin\projects\u1-slicer-orca\app\src\androidTest\java\com\u1\slicer\slicing\ProfileEmbedderIntegrationTest.kt:38)
  - [NativePreparePreviewTest.kt](C:\Users\kevin\projects\u1-slicer-orca\app\src\androidTest\java\com\u1\slicer\viewer\NativePreparePreviewTest.kt:87)
  - [MergeThreeMfInfoTest.kt](C:\Users\kevin\projects\u1-slicer-orca\app\src\test\java\com\u1\slicer\MergeThreeMfInfoTest.kt:13)

---

## Main Risks Identified

1. **Duplicated import pipeline logic**
   - The `origInfo -> process -> processedInfo -> merge -> embed` flow is repeated in:
     - [SlicerViewModel.kt](C:\Users\kevin\projects\u1-slicer-orca\app\src\main\java\com\u1\slicer\SlicerViewModel.kt:662)
     - [SlicerViewModel.kt](C:\Users\kevin\projects\u1-slicer-orca\app\src\main\java\com\u1\slicer\SlicerViewModel.kt:782)
     - [SlicerViewModel.kt](C:\Users\kevin\projects\u1-slicer-orca\app\src\main\java\com\u1\slicer\SlicerViewModel.kt:974)

2. **Spec tests that do not always call production helpers**
   - Several tests in [BambuSanitizerTest.kt](C:\Users\kevin\projects\u1-slicer-orca\app\src\test\java\com\u1\slicer\bambu\BambuSanitizerTest.kt:6) intentionally simulate regex/filter logic instead of invoking the exact production code.

3. **Weak explicit contracts between stages**
   - We have many slice/load checks, but fewer tests that pin down what each intermediate ZIP must contain.

4. **Little equivalence coverage across entry points**
   - There is not enough protection that `loadModel(uri)`, `loadModelFromFile(file)`, and related import paths yield equivalent artifacts for the same input.

5. **Repeated-operation state bugs**
   - Plate switching, re-embed before slice, and "Bambu file then STL" transitions remain high-risk because state is spread across mutable fields.

---

## Deliverables

- [ ] A short pipeline contract doc inside this plan or a companion spec
- [ ] A common helper/service for the repeated import pipeline
- [ ] Additional artifact-contract tests for representative files
- [ ] At least one entry-point equivalence test
- [ ] At least one repeated-operation/state-retention regression test

---

## File Map

| File | Action | Responsibility |
|------|--------|---------------|
| `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` | Modify | Consolidate duplicated Bambu import pipeline and expose typed artifact bundle |
| `app/src/main/java/com/u1/slicer/bambu/BambuSanitizer.kt` | Possibly modify | Promote critical internals into directly testable helpers where needed |
| `app/src/main/java/com/u1/slicer/bambu/ProfileEmbedder.kt` | Possibly modify | Clarify stage ownership and helper boundaries |
| `app/src/main/java/com/u1/slicer/bambu/ThreeMfParser.kt` | Possibly modify | Clarify parse-for-stage responsibilities |
| `app/src/androidTest/java/com/u1/slicer/slicing/BambuPipelineIntegrationTest.kt` | Modify | Add artifact-contract and repeated-operation tests |
| `app/src/androidTest/java/com/u1/slicer/slicing/ProfileEmbedderIntegrationTest.kt` | Modify | Add ZIP-manifest stage assertions and/or entry-point equivalence tests |
| `app/src/test/java/com/u1/slicer/bambu/BambuSanitizerTest.kt` | Modify | Replace white-box simulations with direct production helper tests where practical |
| `app/src/test/java/com/u1/slicer/MergeThreeMfInfoTest.kt` | Modify | Keep merge semantics explicit while pipeline refactor happens |
| `CLAUDE.md` | Modify | Update test counts if new tests are added |

---

## Task 1: Write down the explicit stage contract

- [ ] Document the intended stages and the file/state variable that owns each:
  - raw imported file
  - `origInfo`
  - processed/sanitized file
  - `processedInfo`
  - merged file-level info
  - extracted plate file
  - restructured plate file
  - embedded file
  - re-embedded-before-slice file

- [ ] For each stage, document:
  - producer
  - consumer
  - ZIP entries that must survive
  - ZIP entries that may be dropped
  - metadata that must survive separately even if stripped from the ZIP

- [ ] Explicitly document the meaning of these fields in [SlicerViewModel.kt](C:\Users\kevin\projects\u1-slicer-orca\app\src\main\java\com\u1\slicer\SlicerViewModel.kt:311):
  - `rawInputFile`
  - `sourceModelFile`
  - `sourceModelInfo`
  - `currentModelFile`
  - `_fileThreeMfInfo`
  - `_sourceConfig`
  - `_multiPlateSourceFile`

**Success criteria**
- Future agents can answer "what file should this feature export or reload?" without reverse-engineering the pipeline.

---

## Task 2: Consolidate the repeated import pipeline behind one helper

- [ ] Extract the common Bambu import path from:
  - `importFromSharedUrl(...)`
  - `loadModel(uri)`
  - `loadModelFromFile(file)`

- [ ] Introduce a typed result object, for example:

```kotlin
data class PreparedModelArtifacts(
    val rawFile: File,
    val origInfo: ThreeMfInfo?,
    val processedFile: File?,
    val processedInfo: ThreeMfInfo?,
    val mergedInfo: ThreeMfInfo?,
    val embeddedFile: File,
    val sourceConfig: Map<String, Any>?
)
```

- [ ] Move the repeated sequence behind a single helper such as:

```kotlin
private fun prepareImportedModelArtifacts(
    sourceFile: File,
    workspaceDir: File
): PreparedModelArtifacts
```

- [ ] Keep path-specific concerns outside the helper:
  - URI copy/truncation handling
  - HTTP download handling
  - top-level UI state transitions

- [ ] After extraction, verify the helper is used by all three import entry points.

**Success criteria**
- One pipeline implementation owns parse/process/embed setup.
- Fixes to stage ordering happen in one place, not three.

---

## Task 3: Strengthen unit tests around production helpers

- [ ] Audit [BambuSanitizerTest.kt](C:\Users\kevin\projects\u1-slicer-orca\app\src\test\java\com\u1\slicer\bambu\BambuSanitizerTest.kt:6) for tests that recreate private regex logic rather than invoking production logic.

- [ ] Where practical, promote or wrap internal helpers so the tests can call the real implementation.

- [ ] Prioritize direct tests for:
  - build filtering / plate selection behavior
  - virtual plate recentering
  - non-printable build-item stripping
  - model settings sanitation
  - project settings sanitation

- [ ] Do not break encapsulation gratuitously. Prefer `internal` helper boundaries over copying large private implementations into tests.

**Success criteria**
- Fewer "the spec test still passes but the production code drifted" failures.

---

## Task 4: Add artifact-contract integration tests

- [ ] Pick a compact representative set:
  - `colored_3DBenchy (1).3mf`
  - `Dragon Scale infinity.3mf`
  - `Shashibo-h2s-textured.3mf`
  - `flippy+flappy+mini-with-plate-painted.3mf`
  - `SENSORY+TWIST+BALL+FIDGETS+optimised.3mf`
  - `u1-auxiliary-fan-cover-hex_mw.3mf`

- [ ] For each chosen file, assert stage-level ZIP expectations where relevant:
  - `process()` output
  - `extractPlate()` output
  - `restructurePlateFile()` output
  - `embed()` output

- [ ] Example stage assertions to add:
  - `process()` preserves `Metadata/model_settings.config` when required
  - `process()` preserves or drops `Metadata/custom_gcode_per_layer.xml` intentionally
  - `embed()` includes exactly one `Metadata/project_settings.config`
  - `embed()` does not emit duplicate `Metadata/model_settings.config`
  - `extractPlate()` keeps only selected-plate layer-tool metadata
  - `restructurePlateFile()` emits per-part extruder assignments for deferred multi-plate files

**Success criteria**
- We can detect "wrong intermediate artifact" regressions before they become slice or preview bugs.

---

## Task 5: Add entry-point equivalence tests

- [ ] Add a test proving the same local `3mf` imported via:
  - `loadModel(uri)`
  - `loadModelFromFile(file)`
  yields equivalent `ThreeMfInfo` and compatible intermediate artifacts.

- [ ] If a full ViewModel test is too heavy, factor a shared helper and test the helper output directly.

- [ ] At minimum compare:
  - `isBambu`
  - `isMultiPlate`
  - `detectedExtruderCount`
  - `hasPaintData`
  - `hasLayerToolChanges`
  - output ZIP entry presence for processed/embedded forms

**Success criteria**
- The import path chosen by the UI no longer changes pipeline semantics accidentally.

---

## Task 6: Add repeated-operation regression tests

- [ ] Add at least one stateful regression test for repeated plate selection on a multi-plate file.
- [ ] Add at least one regression test for re-embed-before-slice after slot remapping.
- [ ] Add at least one regression test for "load Bambu 3MF, then load STL" and verify stale Bambu state does not leak.

- [ ] Where possible, assert both:
  - file/state fields are updated correctly
  - native load/preview/slice still succeed

**Success criteria**
- The pipeline behaves correctly after state transitions, not just from a clean start.

---

## Task 7: Keep the corpus lean but intentional

- [ ] Do not add more giant assets unless a missing class of bug is truly uncovered.
- [ ] If a new asset is added, document what coverage class it represents.
- [ ] Prefer one file per failure class over many near-duplicates.

**Current likely corpus classes already covered**
- single-colour Bambu
- per-object multicolour
- SEMM/paint data
- layer-tool colour changes
- support painting
- modifier volumes
- old/new multi-plate layouts
- large component-file archives

---

## Verification

- [ ] `./gradlew testDebugUnitTest --no-daemon`
- [ ] `./gradlew connectedDebugAndroidTest --no-daemon`
- [ ] If the test suite becomes too slow, document a smaller targeted smoke subset for future agents but do not remove the full coverage.

---

## Exit Criteria

- [ ] The repeated import pipeline has a single implementation path or a single helper that owns the sequence.
- [ ] Artifact ownership by stage is documented and testable.
- [ ] The most important stage contracts are asserted in instrumented tests.
- [ ] At least one entry-point equivalence hole is closed.
- [ ] At least one repeated-operation/state-retention hole is closed.
