# Handoff: F75 + F83 + F81 + F84 + F77 implementation

You're picking up the U1 Slicer for Android project at v2.2.6
(commit 2905cbe on `main`). Five GitHub feature requests are ready
to tackle. Read this whole prompt first, then read CLAUDE.md, then
start.

## Project orientation (read these first)

- `CLAUDE.md` — build / test / native-rebuild rules, app conventions,
  release process, and hard rules ("NEVER start a print without
  permission", "NEVER create a GitHub release without authorization",
  "NEVER weaken a test assertion").
- `CLAUDE.local.md` — device IDs and private notes.
- `BACKLOG.md` — full backlog with closed B-bugs and open F-features.
- `README.md` — features list and Smart Paint description.
- Stack: Kotlin 1.9.22, Jetpack Compose, Material3, OpenGL ES 3.0,
  JNI C++ (Snapmaker Orca 2.2.4 fork), Room + DataStore.
- App ID `com.u1.slicer.orca`, current version v2.2.6 (versionCode 279).
- Test device: Pixel 8a (`ANDROID_SERIAL=43211JEKB16931` per
  CLAUDE.local.md).

## Working rules (verbatim from CLAUDE.md, do not relax)

- Add unit tests for every new parsing/logic function.
- For UI/frontend changes: install on the device and exercise the
  feature before reporting done.
- Never start a print on the user's physical printer. Use
  "Map & Upload" / "Upload Only" — not "Map & Print" — when testing
  send flows.
- Don't create a GitHub release or push a public tag without explicit
  user authorization. Build the APK and stage locally is fine.
- Red-green TDD for any bug fix; instrumented test for any new G-code
  or preview behaviour.
- Commit messages are descriptive (1-2 sentence why, not just what).
  Use the `Co-Authored-By: Claude Opus 4.7 (1M context)
  <noreply@anthropic.com>` line on every commit.

## The five features

### F75 — Prime tower defaults to back of plate (issue #90)

When the slicer auto-positions the prime tower (no explicit position in
the source 3MF), default to the **back** of the bed
(Y ≈ 270 − tower depth) instead of the current default. If the source
3MF specifies a position, prefer the file's value.

Key files:
- `app/src/main/java/com/u1/slicer/model/CopyArrangeCalculator.kt`
  `fun pickWipeTowerPosition(...)` — current auto-position logic that
  evaluates 8 candidates by clearance. Change the candidate set or
  ranking so back-of-plate wins ties / is preferred.
- `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` — search for
  `customWipeTowerPos` and the load-time wipe-tower resolution path
  (around line 2740–2760).
- `BambuSanitizer.kt` / `ProfileEmbedder.kt` — handle `wipe_tower_x/y`
  from the source 3MF; ensure file-specified positions still win over
  the new default.

Tests:
- Unit test: load-time auto-position (no source 3MF position) → picked
  Y is in the back half of the bed.
- Unit test: source-3MF-specified position is preserved.
- Manual: load Calicube dual-colour STL/3MF, no explicit prime tower in
  file → verify prime tower lands at back of plate on Prepare.

### F83 — Scale model by absolute mm (issue #136)

Add an mm input alongside the existing % scale field on Prepare. Typing
"50" in the Z field with uniform scaling on should set scale so the
model's Z dimension becomes 50 mm; X and Y follow proportionally. Show
both values so the user sees the relationship.

Key files:
- `app/src/main/java/com/u1/slicer/MainActivity.kt` — Prepare-screen
  scale slider/field is in the `PrepareScreen` composable; current
  call site is `onScaleChange = { viewModel.setModelScale(it) }` near
  line 1439.
- `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`
  `fun setModelScale(scale: ModelScale)`. The load-time model AABB
  lives on `lastModelInfo.sizeX/Y/Z` (mm).

Implementation notes:
- mm → % conversion is a thin helper: `mm / loadTimeSize`.
- Clamp min to 1 mm. Warn if the result exceeds 270 mm (use existing
  `CopyArrangeCalculator.copyBedWarning` machinery).
- Preserve the v2.2.6 B109 rotated-mesh-AABB + bed-snap logic — those
  paths key off `modelScale`, not the input mode.

Tests:
- JVM unit tests for the mm↔% conversion (round-trip, axis isolation,
  clamp).
- One instrumented test in `SlicingIntegrationTest` that loads a
  fixture, sets scale via the new mm path, asserts the sliced bounding
  box.
- Manual: type 50 mm in Z on the device, slice, verify Preview shows
  ~50 mm Z height.

### F81 — Notifications for all loading stages (issue #120)

Currently notifications fire only for print events. Add notifications
for long-running load stages:
- Model load complete (large files only — gate on file size or load
  duration, e.g. > 5 s)
- Bambu 3MF sanitize+embed complete
- Prepare preview ready (large models, QEM decimation finished)
- Slice complete (already covered — verify, don't duplicate)

Gate ALL of these on `ProcessLifecycleOwner` foreground state — only
notify when the app is in the background. The existing F53
infrastructure does this; reuse it.

Key files:
- `app/src/main/java/com/u1/slicer/AppEventNotifier.kt` — `object`
  with `fun notify(context, event: Event)`. Existing channels:
  `CHANNEL_SLICE`, `CHANNEL_PRINTER`. Add a `CHANNEL_LOAD` and new
  `Event` types for the four load stages.
- `app/src/test/java/com/u1/slicer/AppEventNotifierTest.kt` — JVM
  test of title/body/channel/navigate-target mapping. Add cases for
  the new event types.
- `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`
  `fun loadModelFromFile`, `fun prepareImportedModelArtifacts`, and
  the rotation/preview LaunchedEffect that calls
  `getPreparePreviewMesh` — fire notifications at the right points.
- Foreground gate: use the existing `ProcessLifecycleOwner.get()
  .lifecycle.currentState` check pattern (search `AppEventNotifier`
  for precedent).

Tests:
- Add cases to `AppEventNotifierTest` for the 4 new event types.
- Add a JVM unit test for the foreground-gate helper.
- Manual: background the app during a Buzz Lightyear load and confirm
  a notification fires when the plate selector becomes ready.

### F84 — Upload filename preserves model name (issue #138)

Today uploaded G-code arrives at the printer as
`output_<epoch>.gcode` or `output.remapped_<epoch>.gcode` — every job
looks the same in the printer's file browser. Replace the base with
the loaded model's name so the user can tell prints apart.

Examples:
- `Jumping_frog.3mf` → `Jumping_frog_<epoch>.gcode`
- `Dragon Scale infinity.3mf` → `Dragon_Scale_infinity_<epoch>.gcode`
- `3DBenchy.stl` → `3DBenchy_<epoch>.gcode`

Key files:
- `app/src/main/java/com/u1/slicer/printer/PrinterRepository.kt`
  - `fun uploadAndPrint(gcodeFile, filename: String)` at line 104
  - `fun uploadOnly(gcodeFile, filename: String)` at line 122
  - `fun buildPrinterUploadFilename(sourceName, nowMillis)` at line 194
    — this helper already does the sanitisation + epoch suffix.
    Do NOT change it; just feed it the model name instead of the
    on-disk gcode name.
- `app/src/main/java/com/u1/slicer/printer/PrinterViewModel.kt`
  `fun sendAndPrint(physical: PhysicalGcodePath)` at ~line 266 and
  `fun sendUploadOnly(...)` at ~line 288 — currently use `file.name`
  (the on-disk gcode name). Need to receive / look up the model name
  and pass that instead.
- `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` —
  `currentModelName` (set in `loadModelFromFile` at ~line 1450) holds
  the original filename. Either pass it down via the send flow or
  expose it via a getter the `PrinterViewModel` can read.

Edge case: if model name is somehow empty (defensive — shouldn't happen
in practice), fall back to the current `file.name` behaviour rather
than producing a nameless upload.

Tests:
- Update `PrinterRepositoryTest#upload filename sanitization and
  unique suffix generation` to use model-like names ("Jumping_frog",
  "Dragon Scale infinity") instead of generic placeholders.
- Add a small pure helper (e.g.
  `resolveUploadBaseName(modelName: String?, gcodeFileName: String):
  String`) that returns the model name when present, falls back
  otherwise. Unit-test that helper.
- Manual: slice an STL on device, **Map & Upload** (NOT Map & Print —
  device safety rule), confirm the printer's file browser shows the
  model name.

### F77 — Multi-file STL + 3MF load onto one plate (issue #109)

Allow the file picker to accept multiple STL **or** multiple 3MF files
at once. Auto-arrange them on the 270×270 bed using the existing
centred-grid logic. Treat the merged result as one
Prepare/Preview/slice cycle.

> The issue body says STL only, but Kevin confirmed (handoff session)
> he wants **both STL and 3MF** to support multi-file load — each
> file becomes one object on the plate. Mixing STL + 3MF in one batch
> is still rejected: formats have different scene semantics.

Key files:
- File-picker entry in `MainActivity.kt` — search for
  `ActivityResultContracts.OpenDocument` /
  `OpenMultipleDocuments`.
- `app/src/test/java/com/u1/slicer/FilePickerValidationTest.kt` —
  existing picker validation test class; extend its coverage.
- `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`
  `fun loadModelFromFile(file: File)` — single-file entry point;
  multi-file needs a new entry that merges into one native model.
- Native side: `setModelInstances(positions: FloatArray)` already
  supports N copies of one model, but multiple distinct meshes means
  loading each into a separate `ModelObject`. See how
  `BambuPipelineIntegrationTest.kt` arranges multi-object Bambu plates
  for a precedent. The lower-risk approach is to merge in Kotlin into
  a single multi-object 3MF before native `loadModel`; check
  `BambuSanitizer.kt` for the existing per-object packaging helpers.

Edge cases (all from the issue body, all needed):
- Total triangle count above QEM budget → per-part stride decimation,
  warn user.
- A part exceeds bed bounds → reject that part, keep the rest, surface
  a warning.
- A part fails to parse → continue with the others, list failures in
  the post-load summary.

Tests:
- Unit tests for the merge logic (multi-STL → one `ThreeMfInfo`;
  multi-3MF → one `ThreeMfInfo` preserving per-file extruder
  assignments).
- Instrumented test in `SlicingIntegrationTest` that loads 3 STL files
  and asserts grid placement + sliced layer count.
- Instrumented test in `BambuPipelineIntegrationTest` that loads 2
  single-object 3MFs and asserts the merged plate slices cleanly.
- Manual: load 3 small STLs and 2 small 3MFs (separately), slice
  each, verify Preview shows all parts.

## Ordering recommendation

Smallest blast-radius → biggest. Each can ship as its own version bump.

1. **F75 (prime tower default)** — pure picker logic, no UI shape
   change. ~1–2 hours.
2. **F84 (upload filename)** — small wiring change across 3 files,
   covered by existing test pattern. ~1–2 hours.
3. **F83 (mm scale)** — adds one input affordance to Prepare; pure UI
   + ViewModel addition. ~2–3 hours.
4. **F81 (load notifications)** — touches the notifier and a handful
   of ViewModel call sites; F53 infrastructure does the heavy lifting.
   ~2–4 hours.
5. **F77 (multi-file load)** — biggest because the merge path is new
   with many edge cases (formats, triangle budgets, per-part bed
   bounds, partial parse failures). Save for last. ~4–8 hours.

Commit each feature separately. Bump version once per feature OR once
at the end — Kevin will tell you which. Standard release flow is in
CLAUDE.md "Release" section; remember: no `gh release create` without
his explicit go-ahead in that turn.

## Tests baseline (as of v2.2.6, 2026-05-17)

- 1190 / 1190 JVM unit tests pass
- 304 / 304 instrumented tests pass (Pixel 8a after B93 budget
  recalibration in v2.2.4)
- Smoke-7 E2E green at v2.2.4; not re-run on v2.2.6 (review changes
  confined to Prepare-screen placement math, didn't touch slicing
  path).

Any new test failure is a regression — investigate, don't assume
pre-existing. Run the full JVM sweep after each feature.

## Hand-off complete

Start by reading CLAUDE.md, then `git pull origin main`, then pick F75
or F84. Ask Kevin clarifying questions only if a UX decision is
genuinely ambiguous — he prefers you make the reasonable call and
proceed.
