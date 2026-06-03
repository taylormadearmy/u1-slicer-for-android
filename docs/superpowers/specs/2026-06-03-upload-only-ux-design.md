# Upload-Only UX — design

**Date:** 2026-06-03
**Branch:** `fix/internal-memory-wrong-nozzle`
**Related:** internal-memory wrong-nozzle root cause (this branch's first commit a0f5028); BACKLOG B102 (same family).

## Problem

The send-to-hold path ("Map & Upload") shows the full `FilamentMappingDialog`
slot picker and collects a filament→physical-slot mapping. As of the Fix-A
change on this branch, that mapping is **ignored** for held files: the upload
now ships the **canonical** G-code body so the printer's own Filament Setup
maps it once (avoiding the double-remap that put the first colour on the wrong
nozzle). So the slot picker is now misleading — its choices do nothing for an
upload.

## Goal

Make the Upload-Only path honest: no slot picker whose picks are silently
discarded, and a clear hand-off that the **printer** does the mapping when the
held print is started.

## Non-goals

- Changing the **Map & Print** path. It must keep baking the physical remap:
  the Moonraker API start runs the body verbatim, so a canonical body would
  print on the wrong nozzles. The two actions are intentionally different.
- Unifying the two body representations into one uploaded file (that is the
  heavier "Fix B" header-remap direction — out of scope here).

## Design

### 1. Button rename
`MainActivity` Preview action buttons:
- Filled primary **"Map & Print"** — unchanged (`onSendToPrinter` → `PrintAndUpload`).
- Outlined **"Map & Upload"** → renamed **"Upload Only"** (`onUploadOnly` → `UploadOnly`).

### 2. Branch the post-tap dialog on action
Today both actions funnel into `FilamentMappingDialog` (the `CanonicalLookup.Present`
branch around `MainActivity.kt:829`). Branch on the already-tracked
`PendingMappingSend.action`:

- **PrintAndUpload** → `FilamentMappingDialog`, exactly as today (slot picker,
  auto-suggest, dup-slot handling, material-mismatch warnings, physical remap
  baked at confirm).
- **UploadOnly** → new lightweight **`UploadConfirmationDialog`**.

### 3. `UploadConfirmationDialog` (new composable)
Read-only confirmation, no slot picking. Contents:
- Title: **"Upload to printer"**.
- Model name + plate label (reuse what the mapping dialog shows).
- A **read-only** list of the file's colours — one row per canonical filament:
  **colour swatch + material name** only (no weights). Data source: the
  `CanonicalFilamentList` already passed to the send path — **no G-code parsing**.
  Plate-narrowed files use `plateFileIndices` for row labelling, same as the
  mapping dialog, so labels match Prepare / Slice Summary.
- One-line note: *"When you start this print on the printer, it will ask you
  to assign each colour to a nozzle."*
- Buttons: **Cancel** / **Upload**.
- Single-colour files also use this sheet (one row). Uniform path.

### 4. Confirm behavior
On **Upload**: upload the **canonical body**. Reuses the committed Fix-A path —
`applyPrintTimeRemap` with the mapping from `sendRemapForAction(uploadOnly = true, …)`
(= empty list = verbatim copy) → `printerViewModel.sendUploadOnly(physical, modelName)`.
Wrapped in `LongOpService` like the existing send (a verbatim copy of a large
file still must survive backgrounding).

Because no slot is chosen in-app, the B103/B128 material-mismatch warnings
(which compare a *chosen slot's* material to the sliced material) do not apply
to this path and are simply absent.

### 5. Scope guard — legacy Absent path
`CanonicalLookup.Absent` (legacy / unrecognised file, no canonical colour list)
keeps its current direct upload for `UploadOnly` — it already does an
identity/canonical copy, so it is correct; it just won't get the rich sheet
(there are no canonical colours to show). Known, accepted minor inconsistency;
not worth building a colour-less variant for rare legacy files.

## Testing

- **Logic (already covered):** `sendRemapForAction` contract + canonical-body-
  preserved-for-Upload-Only vs remapped-for-Map-&-Print, in
  `CanonicalExportMappingTest`.
- **UI routing (new):** source-grep **structural guard** test (matching the
  existing `ModelInfoDialogScrollTest` convention — the project has no Compose
  UI harness) asserting: (a) `UploadConfirmationDialog` exists, (b) the
  `UploadOnly` branch in `MainActivity` routes to it rather than
  `FilamentMappingDialog`, (c) the outlined button text is "Upload Only".

## Verification

On-device with the reporter (Jon): load a multi-colour plate → **Upload Only** →
confirm the new sheet → print from the printer's internal memory, assigning
colours on the Filament Setup screen → first colour lands on the intended
nozzle (the original bug: brown on nozzle 2, not 3).
