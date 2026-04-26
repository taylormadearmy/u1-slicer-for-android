# Inspect Model + Export Pipeline Artifacts Stage 0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a preview-side "Inspect Model" surface in the Android app that exposes Bambu import diagnostics and allows exporting the two most useful intermediate artifacts:

- `sanitized.3mf`
- `sanitized-embedded.3mf`

**Why this stage exists:** It gives us a cheap, high-signal experiment for desktop Orca without committing to a full desktop refactor yet. It also creates a durable debug surface for future import/pipeline regressions.

**Recommendation from the thread:** Put this on the preview / model-inspection path, not on Jobs. Jobs is about slice history; this feature is about understanding the currently loaded model and its import pipeline.

---

## Product Decisions Already Made

- Primary home: **Preview screen**
- UX pattern: **Inspect Model bottom sheet**, not a one-off export button
- Exported artifacts for v0:
  - `Export Sanitized 3MF`
  - `Export Sanitized + Embedded 3MF`
  - `Copy Debug Summary`
- Defer for now:
  - Jobs-page integration
  - share sheet / debug bundle ZIP
  - raw imported file export

---

## Existing Hooks We Can Reuse

- `sourceModelFile` already tracks the processed/sanitized source file in [SlicerViewModel.kt](C:\Users\kevin\projects\u1-slicer-orca\app\src\main\java\com\u1\slicer\SlicerViewModel.kt:320)
- `currentModelFile` already tracks the current embedded working file in [SlicerViewModel.kt](C:\Users\kevin\projects\u1-slicer-orca\app\src\main\java\com\u1\slicer\SlicerViewModel.kt:312)
- `ThreeMfInfo` and related metadata are already exposed through the ViewModel
- `MainActivity` already uses `ActivityResultContracts.CreateDocument(...)` for G-code export in [MainActivity.kt](C:\Users\kevin\projects\u1-slicer-orca\app\src\main\java\com\u1\slicer\MainActivity.kt:73)

---

## File Map

| File | Action | Responsibility |
|------|--------|---------------|
| `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` | Modify | Expose artifact metadata, export helpers, debug-summary builder |
| `app/src/main/java/com/u1/slicer/MainActivity.kt` | Modify | Add document launchers and wire export actions to the sheet |
| `app/src/main/java/com/u1/slicer/ui/` | Create or modify | Add `InspectModelSheet` composable |
| `app/src/test/java/com/u1/slicer/` | Create or modify | Unit tests for export summary/artifact naming helpers |
| `app/src/androidTest/java/com/u1/slicer/` | Possibly create | Optional UI/instrumented tests for export visibility conditions |
| `CLAUDE.md` | Modify | Update test counts if needed |

---

## Task 1: Introduce an explicit export artifact model

- [ ] Add a small ViewModel-facing model for the exportable artifacts, for example:

```kotlin
enum class ExportArtifactKind {
    Sanitized3mf,
    SanitizedEmbedded3mf
}

data class ExportableModelArtifacts(
    val sourceDisplayName: String,
    val selectedPlateId: Int?,
    val isBambu: Boolean,
    val sanitizedFile: File?,
    val embeddedFile: File?,
    val info: ThreeMfInfo?
)
```

- [ ] Add a ViewModel getter that returns the current exportable artifacts or `null` when the current model is not exportable.

- [ ] Hide export actions for:
  - STL / OBJ / STEP
  - cases where the relevant file no longer exists

**Success criteria**
- The UI does not need to know which mutable ViewModel field maps to which artifact.

---

## Task 2: Build a debug summary string from current state

- [ ] Add `buildModelDebugSummary(): String` to the ViewModel.

- [ ] Include:
  - current source file name
  - current working file name
  - `isBambu`
  - `isMultiPlate`
  - selected plate id if any
  - detected extruder count
  - paint data yes/no
  - paint supports yes/no
  - layer-tool changes yes/no
  - used extruder indices
  - object-extruder map size
  - sanitized file path/name if present
  - embedded file path/name if present

- [ ] Keep the summary human-readable and clipboard-friendly.

**Success criteria**
- A user can paste the summary into a bug report or chat without needing screenshots.

---

## Task 3: Add export helpers to the ViewModel

- [ ] Add a helper that copies a selected artifact to a document URI:

```kotlin
fun exportArtifactTo(
    kind: ExportArtifactKind,
    targetUri: Uri,
    onResult: (Result<Unit>) -> Unit
)
```

- [ ] Map artifact kinds to files:
  - `Sanitized3mf` -> `sourceModelFile`
  - `SanitizedEmbedded3mf` -> `currentModelFile`

- [ ] Validate before writing:
  - file exists
  - file has `.3mf` extension or otherwise is known-good
  - current model is Bambu-derived or otherwise explicitly exportable

- [ ] Record diagnostics events for success/failure.

**Success criteria**
- Export logic lives in one place and the UI only handles `Uri` selection.

---

## Task 4: Add document launchers in `MainActivity`

- [ ] Add two `CreateDocument(...)` launchers for:
  - sanitized artifact export
  - sanitized+embedded artifact export

- [ ] Prefer MIME type `application/vnd.ms-3mfdocument`; fall back to octet-stream only if required by platform behavior.

- [ ] Default filenames:
  - `<base>.sanitized.3mf`
  - `<base>.sanitized-embedded.3mf`

- [ ] Add one clipboard action for `Copy Debug Summary`.

**Success criteria**
- The export flow matches existing Android document-save patterns in the app.

---

## Task 5: Add the `Inspect Model` bottom sheet

- [ ] Create a composable such as `InspectModelSheet(...)`.

- [ ] Show this sheet from the preview / prepare screen, not Jobs.

- [ ] Content for v0:
  - title: `Inspect Model`
  - optional short description when the current file is Bambu-derived
  - key-value rows for:
    - source filename
    - `Bambu file`
    - `Multi-plate`
    - `Selected plate`
    - `Detected extruders`
    - `Paint data`
    - `Layer-tool changes`
  - actions:
    - `Export Sanitized 3MF`
    - `Export Sanitized + Embedded 3MF`
    - `Copy Debug Summary`

- [ ] Disable or hide export buttons when the artifact is unavailable.

**Success criteria**
- The feature feels like a general-purpose inspection/debug tool, not a one-off experiment.

---

## Task 6: Add the sheet entry point on the preview screen

- [ ] Add an action icon on the preview-side UI, preferably `Info` or similar.

- [ ] Avoid putting the only entry point inside the 3D G-code viewer.

- [ ] Prefer placement on the main Prepare/preview flow where the model is still in context.

- [ ] If there are multiple preview surfaces, ensure the sheet is reachable from the one users spend most of their time on before slicing.

**Success criteria**
- A user can export artifacts before slicing and without navigating to Jobs.

---

## Task 7: Add naming and messaging that supports the experiment

- [ ] In UI labels, use explicit language:
  - `Sanitized for Orca compatibility`
  - `Sanitized + U1 profile embedded`

- [ ] In code/comments/docs, be explicit that:
  - the sanitized artifact is the best approximation of the "cleanup only" boundary
  - the sanitized+embedded artifact is the actual working file used for current load/slice behavior

- [ ] If the model is multi-plate, make it clear the export reflects the currently selected plate after selection.

**Success criteria**
- Future desktop testing can answer which transformation actually made Orca happier.

---

## Task 8: Add targeted tests

- [ ] Unit tests for:
  - filename generation
  - artifact selection logic
  - debug summary formatting
  - visibility/availability rules

- [ ] Optional instrumented/UI tests for:
  - Bambu file shows Inspect Model export actions
  - STL does not show 3MF export actions
  - selected-plate state appears in summary for multi-plate files

**Success criteria**
- The export surface is not another untested piece of fragile pipeline logic.

---

## Verification

- [ ] `./gradlew testDebugUnitTest --no-daemon`
- [ ] If UI/instrumented tests are added: `./gradlew connectedDebugAndroidTest --no-daemon`
- [ ] Manual smoke check:
  - load a Bambu file
  - open Inspect Model
  - export both artifacts
  - confirm the written files are non-empty `3mf` ZIPs

---

## Exit Criteria

- [ ] Inspect Model sheet exists on the preview path.
- [ ] Sanitized and sanitized+embedded artifacts can both be exported.
- [ ] Debug summary can be copied without exporting files.
- [ ] The feature is clearly useful for Windows Orca experiments and future bug reports.
