# U1 Slicer - E2E Testing

Current release line: see `CLAUDE.md` for `versionName` / `versionCode`.

Primary test device: See `E2E_TESTING.local.md` for the private device IDs used on this machine.

## Automated baseline

See `CLAUDE.md` for the authoritative counts (they change when tests are added). Typical shape:

- JVM unit tests: `testDebugUnitTest`
- Instrumented tests: `connectedDebugAndroidTest` (Orchestrator)

Run before release:

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew assembleRelease
```

If Gradle's connected-test wrapper is flaky on this Windows machine, run the device suite directly:

```bash
adb -s <pixel-8a-device-id> shell am instrument -w com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

## Manual release checklist

Use this checklist for final on-device sanity passes before publishing:

1. Load the file
2. If multi-plate, select the target plate
3. Confirm Prepare preview shows the expected colour count and geometry
4. Tap-select and drag an object
5. Tap-select and drag the prime tower
6. Slice successfully
7. Confirm Preview colours match the intended tool usage

### Manual regression checks (v1.5.26 rotation + prime tower settings — F57/F58)

Run these after any change to the rotation UI, prime tower override fields, or collapsible cards:

- **Model rotation — Z axis (bed spin):** Load `tetrahedron.stl`. In the Scale & Copies card, switch to the Rotation tab. Drag the "Rotate on bed (Z)" slider to ~45°. Confirm the overlay badge on the viewport reads "Z: 45°". Slice; confirm G-code is produced and the slice-result summary shows a non-trivial layer count.
- **Model rotation — X axis (tilt):** Load `tetrahedron.stl`. Set "Tilt (X)" to 90°. Confirm badge reads "X: 90°". Slice; compare layer count to an unrotated slice of the same model — it should differ.
- **Rotation reset:** With any non-zero rotation set, tap "Reset to 0°" — all three sliders should snap to 0° and the overlay badge should disappear.
- **Collapsible cards:** Tap the chevron on the Scale & Copies card — body collapses. Tap again — it expands. Repeat for Print Setup card. Confirm state is not preserved on reload (both default to expanded after navigation away and back).
- **Prime tower width override:** Load `calib-cube-10-dual-colour-merged.3mf`. In Print Setup overrides, set Prime Tower → Tower Width to 20 mm. Slice. Inspect G-code for `prime_tower_width = 20` or visually smaller tower footprint in preview.
- **Prime tower rotation override:** Same file; set Tower Rotation to 45°. Slice. Grep G-code for `wipe_tower_rotation_angle = 45` to confirm it was applied.
- **App-level settings parity:** In the app settings screen, confirm Tower Width and Tower Rotation fields are present in the Prime Tower section. Set values there, return to Prepare; confirm overrides carry through to slice output.

### Manual regression checks (v1.5.32 — F59/F60/F61: G-code viewer width, Jobs tab re-open)

Run these after any change to the G-code viewer rendering, Jobs tab, or Room schema:

- **F59 — G-code line visibility:** Load `tetrahedron.stl`, slice. Open the G-code viewer. Zoom out to full-bed view. Filament paths should be clearly visible as solid coloured ribbons — not hairline threads. Zoom in and out; confirm lines remain readable at all zoom levels.
- **F59 — Travel lines:** With "Show travel" toggled on, travel moves should appear as thin grey lines (GL_LINES, always 1px). Confirm they don't obscure the extrusion paths.
- **F60 — View G-code from Jobs tab:** After a successful slice, navigate to the **Jobs** tab. Confirm the job card shows a **Layers icon** button. Tap it. The G-code viewer should open showing the same output as the viewer accessed immediately after slicing. Layer scrubbing, extruder colours, and travel toggle should all work normally.
- **F60 — Missing G-code graceful failure:** Not easily reproducible manually, but if you have a job from a prior install whose G-code file was cleared, the Layers button should show a toast ("G-code file not found") rather than crashing.
- **F61 — Re-open model from Jobs tab (new jobs only):** Slice any file. Navigate to Jobs tab. Job cards for jobs sliced on v1.5.32+ should show an **Edit icon** button alongside the Layers icon. Tap it. The app should navigate to the Prepare screen and reload the original 3MF/STL — same as if you had opened the file fresh from the picker. Confirm the model geometry, colour count, and prime tower all appear correctly.
- **F61 — Old jobs (no Edit icon):** Jobs sliced before v1.5.32 (i.e. rows with `sourcePath = NULL` in the database) should show **only** the Layers icon — no Edit icon.
- **F61 — Room migration smoke test:** On a device that had v1.5.31 installed with existing job history, upgrade to v1.5.32. Confirm the app launches without crash, the Jobs tab shows the existing job history intact, and old jobs have only the Layers icon (no Edit icon).

### Manual regression checks (v1.6.8 — B87/B88/B89/B90/B91)

Run after any change to the Prepare-preview colour pipeline, plate-switch flow,
`ThreeMfParser.parseForPlateSelection`, `NativePreviewMesh.toMeshData`, or the
ModelInfoDialog composable:

- **B89 — Info (ℹ︎) menu scrollable:** Load any file with a long filename
  (≥60 characters) or rotate the device to portrait. Tap the **info (ℹ︎) icon**
  on the Prepare screen. Drag up inside the dialog. Content should scroll
  smoothly, and the **Close** button plus any **Export Sanitized / Copy Debug
  Summary / Reassign Filaments** buttons must all be reachable with the device
  in portrait. Regression shape: Close hidden below screen edge, drag does
  nothing.
- **B88 — Buzz Lightyear plate-switch colour consistency:** Load
  `Buzz_Multipart_3MF_Bambu.3mf`. Select plate 1; note the Prepare preview
  shows **4 distinct filament colours** (red / green / blue / white by
  default, one per detected filament). Tap **Change plate** → pick plate 9.
  Prepare preview on plate 9 should render **two distinct colours**, not
  a single colour matching plate 8. Switch back to plate 1; 4 colours
  return. Slice plate 9 and confirm the G-code preview uses the same two
  colours Prepare showed (no divergence).
- **B90 — Buzz plate 9 filament identity:** On plate 9 of the Buzz file,
  the two detected colours should correspond to **filament 10 (white
  #FFFFFF)** and **filament 8 (peach #FFD6C1)** from the original 3MF's
  `filament_colour` array — **not** filaments 1 (black) and 2 (blue).
  Visual check: in the extruder-chip row at the bottom of Prepare, the
  two "Model colour" swatches should read peach-ish and white-ish, not
  black-ish and blue-ish.
- **B91 — Skywing plate selection speed:** Load
  `skywing-seawing-silkwing.3mf`. Dialog opens showing 2 plates. Tap
  plate 1. The "Loading plate 1…" spinner should clear within **~15
  seconds**. Regression shape: spinner sits for 3+ minutes before the
  3D preview appears.
- **B87 — Skywing bottom-layer colours in Prepare vs slice:** On Skywing
  plate 1, Prepare shows three distinct colour regions (cyan body,
  purple accents, white tips — though exact rendering depends on your
  E1..E4 preset colours). Slice and open the G-code viewer. The sliced
  geometry should carry **the same three colour regions** visible in
  Prepare — in particular the painted "bottom" areas should NOT
  collapse to a single object-extruder colour. G-code tool counts in
  the summary should include T0, T1, and T2 (per
  `SemmSlicingTest.skywingSeawingDragon_sliceProducesMultipleToolChanges`
  expectations).

### Manual regression checks (v1.5.x colour / layer-tool hotfixes)

Run these on at least one multi-colour file from the priority table below when touching Prepare metadata, preview colours, G-code post-processing, or plate merge logic:

- **Remap stress (Prepare vs Preview parity):** On a 2-colour model, map Colour 1 to `E3` and Colour 2 to `E2` (non-default slots). Confirm Prepare shows exactly two colours, Preview shows the same two (no extra phantom colours), and per-extruder summary weights align with the mapped slots (no bogus `0g` on the wrong row).
- **Layer-tool vs painted parity:** Same or similar model sliced as layer-change (Hueforge-style) vs painted multi-material; skim G-code for sensible `Tn` and `M109 S… Tn` after tool switches where the injector applies, and confirm preview segmentation does not invent extra colours on native toolchange jobs.
- **Temperature after remap:** After non-default slot mapping, confirm the first post-pause tool switch still gets an explicit wait-temp line for the active tool where applicable (`M109 S… Tn`).
- **Multi-plate stability:** On dragon- or shashibo-class files, change plates more than once before slicing; selected plate colour count should stay stable and match expectations.
- **Preset / slot colour refresh:** Change filament preset colours, return to Prepare/Preview, and confirm preview swatches still match mapping (no stale palette).

Useful log capture:

```bash
adb -s <pixel-8a-device-id> logcat -s "SlicerVM,BambuSanitizer,ThreeMfParser,InlineModelPreview"
```

## Priority manual files

These are the files most likely to catch regressions quickly:

| File | Why it matters | Minimum manual check |
|------|----------------|----------------------|
| `flippy+flappy+mini.3mf` — **plate 4** | Small Hueforge / layer-tool dual-colour plate; `ProfileEmbedderIntegrationTest` uses **plate 4** for extract/plate-selection cases. **Canonical repo copy:** `app/src/androidTest/assets/flippy+flappy+mini.3mf` — copy to device for manual runs. | **Select plate 4**, remap colours (E2/E3 stress), slice, confirm Preview + summary + G-code pause/temp behaviour |
| `calib-cube-10-dual-colour-merged.3mf` | Basic 2-colour baseline; post-update Clipper history | Prepare colours, move, slice |
| `Dragon Scale infinity.3mf` plate 3 | Old-format multi-plate; tri-colour regression history | Plate 3 shows 3 colours, move, slice |
| `Shashibo-h2s-textured.3mf` plate 5 | Old-format multi-plate textured case | Plate 5 preview colours, slice |
| `3DBenchy-H2C-Multi-Color-Test-Print.3mf` | H2C sparse paint / 7-colour mapping | Prepare vs Preview colour parity |
| `colored_3DBenchy (1).3mf` | Non-H2C painted benchy baseline | Prepare colours, slice |
| `Flarewing-Dragon_100%_4FilamentMulticolor_v1.1.3mf` | B67 canary: large SEMM file (295k paint attrs); catches ProfileEmbedder filament_colour corruption (B66), GcodeParser per-extruder mm swap (B67). **Slow — 3h+ slice.** | Prepare shows 4 colours; `filament_colour` in G-code has **4 distinct hex values** (not `#FFFFFF;#FFFFFF;…`); T0–T3 all non-trivial; `filament used [mm]` first value (T0) is the **largest** of the four |
| `Buzz_Multipart_3MF_Bambu.3mf` — **plates 1 & 9** | B88/B90 canary: 10-plate Bambu with per-object extruder 10 + paint state 8 (filament indices above declared visual count). Catches plate-switch colour leak + high-index `detectedColors` synthesis. | Select plate 1 (4 colours), **Change plate** to plate 9 (2 colours: peach + white, **not** black + blue), slice plate 9, Preview matches Prepare's 2 colours |
| `skywing-seawing-silkwing.3mf` | B87/B91 canary: dense SEMM (162K paint_color attrs across 35 component models). Catches slow `parseForPlateSelection` regression + multi-colour bottom-layer dropout. | Select plate 1 within ~15s, Prepare shows ≥3 colour regions, slice emits T0/T1/T2 (summary layer count > 0) |
| `2026+F1+CALENDAR+-+DATES+&+TRACK+NAMES+(P_X+SERIES).3mf` | Large Bambu file / former B18 OOM repro | Loads without OOM, slice preserves multi-colour output |
| `super clean.3mf` | Huge single-model 3MF / former sanitize+embed OOM repro | Loads without OOM, preview fallback still works |

For batch coverage, keep the two large-model regressions above in the manual E2E set even when smaller smoke files are already passing.

**Flippy Flappy Mini in the repo:** Worth using for E2E — it is the same binary exercised by instrumented tests (`ProfileEmbedderIntegrationTest`), which use **plate 4** for flippy-specific cases; select **plate 4** in the UI so manual passes align with automation. It is small and fast to iterate for layer-tool pause injection, plate extraction, and colour remap regressions. Pull it from `app/src/androidTest/assets/` when preparing a device-side folder of test 3MFs.

## What automation already covers well

- Multi-plate parsing and extraction
- Bambu sanitization / profile embedding
- Native Prepare preview export
- Dragon plate 3 Prepare state and slice-output colour coverage
- Flippy+Flappy mini: layer-tool metadata, embed/sanitize, full pipeline pause G-code (`ProfileEmbedderIntegrationTest`)
- G-code generation and tool-remap behaviour
- Large-model preview budget guardrails
- Large 3MF sanitize/embed memory regressions now have dedicated manual repros in the batch set

## What still benefits from manual verification

- Final visual parity between Prepare and Preview
- Plate selector UX
- Tap-to-select / drag placement feel
- Very large file load latency and messaging
- Real-device colour perception on unusual palettes
- Remap-to-non-default extruder slots (automation does not replace a human eyeball on the full UI flow)
- G-code viewer line visibility at various zoom levels (F59)
- Jobs tab re-open flow end-to-end (F60/F61) — requires a real sliced job in history

---

## AI-assisted batch manual E2E (human-equivalent, not instrumentation)

This section is for **full on-device validation** using the **same actions a human would take**: opening the app, using the **file picker**, **tapping** plates and controls, **dragging** models/tower, **slicing**, and **reading** Prepare / Preview / summary on screen. It is **intentionally slow**.

### What this is *not*

- **Do not** treat `./gradlew connectedDebugAndroidTest` or `adb shell am instrument …` as having completed this batch. Those are the **automated baseline** (JUnit on device). They complement manual E2E; they **do not replace** it.
- **Do not** substitute “run the instrumented test that loads asset X” for “load X in the UI and verify behaviour.” This batch is **UI-first**.

### Testing folder (repo canonical source)

**Canonical location on disk:** `app/src/androidTest/assets/`

For a manual run, copy **everything** in that folder into a **testing folder** the phone can open (e.g. `Downloads/U1-E2E/`, or `G:\My Drive\…` if you sync that way). Large 3MFs referenced in the priority table below may live only on your machine; add them to the same testing folder so the same filenames appear in the **Files** / **Google Drive** picker.

If an asset is missing locally, pull it from version control or restore from backup; instrumented tests expect these filenames.

### Files to include in a *full* pass (every `assets` file)

Run through **every** file under `app/src/androidTest/assets/` at least once. Known names referenced by tests (include any additional files present in the directory):

| File | Typical manual focus |
|------|------------------------|
| `flippy+flappy+mini.3mf` — **plate 4** | Layer-tool / Hueforge-style; instrumented tests target **plate 4** — use the same in manual UI runs |
| `calib-cube-10-dual-colour-merged.3mf` | Dual-colour baseline, Clipper, first-load colours |
| `Dragon Scale infinity.3mf` | Multi-plate (e.g. plate 3), tri-colour |
| `Dragon Scale infinity-1-plate-2-colours.3mf` | Per-object colours, plate variants |
| `Dragon Scale infinity-1-plate-2-colours-new-plate.3mf` | Plate restructure regression |
| `Shashibo-h2s-textured.3mf` | Multi-plate textured; e.g. plate 5, preview colours |
| `colored_3DBenchy (1).3mf` | SEMM / painted benchy |
| `3DBenchy-H2C-Multi-Color-Test-Print.3mf` | H2C sparse paint (priority table) |
| `PrusaSlicer-printables-Korok_mask_4colour.3mf` | Four-colour mask |
| `Flarewing-Dragon_100%_4FilamentMulticolor_v1.1.3mf` | B67 canary: large SEMM 4-colour; check `filament_colour` 4 distinct hex values; T0–T3 all >20; `filament used [mm]` value 0 (T0) is the largest |
| `Buzz_Multipart_3MF_Bambu.3mf` | B88/B90: 10-plate high-filament-index; plate-switch colour consistency + plate 9 peach+white (not black+blue) |
| `skywing-seawing-silkwing.3mf` | B87/B91: dense SEMM; plate-select finishes quickly, bottom-layer multi-colour preserved through slice |
| `foldy+coaster (1).3mf` | Pipeline foldy case |
| `slip slide spin fidget.3mf` | Multi-plate slip/slide (e.g. plate 3) |
| `u1-auxiliary-fan-cover-hex_mw.3mf` | MakerWorld-style embed |
| `old.3mf` | Legacy-format preview |
| `3DBenchy.stl`, `tetrahedron.stl` | STL single-material / override |
| `Bambu PLA Basic @BBL P1S 0.4 nozzle.json`, `test-filament-profile.json` | Filament JSON only if you test import UI |

**Also** include the **large** priority manual files from the table above (`2026+F1+CALENDAR…`, `super clean.3mf`) if you keep them outside `assets`—they are part of the **important** set even when not committed.

### Per-file procedure (repeat for each file)

1. **Install** a debug or release APK on the device (`adb install -r` is fine; that is not “instrumentation”).
2. **Launch** the app from the launcher (not via test harness).
3. **Open** the 3MF/STL through the **in-app file flow** (picker / “Open with” / intent), **not** by pushing only into app-private data for a test.
4. If multi-plate: **tap** the plate selector and confirm the correct plate.
5. **Confirm** Prepare: colour count, geometry, extruder chips.
6. **Tap-select** an object; **drag**; repeat for **prime tower** if shown.
7. **Slice**; wait for completion.
8. **Open** the G-code preview / summary; confirm **colours and per-extruder usage** match expectations.
9. Optional: **screenshot** or short **logcat** for the session (see `E2E_TESTING.local.md`).

### Using AI agents (including subagents)

- **Goal:** Parallelise **human-equivalent** passes, not JVM tests. One **subagent or session per file** (or per small group) works well: each agent owns one file from the testing folder, runs the **Per-file procedure** above, and records pass/fail + notes.
- **Parallel vs sequential:** Parallel is faster wall-clock but needs **one device per agent** or a strict schedule so two agents are not fighting the same phone. Sequential on one device is fine; expect **hours** for a full folder pass.
- **Historical reference:** `docs/superpowers/plans/2026-03-15-b22-b23-multicolor-preview-bugs.md` (Chunk 5, Task 11) used **invoke** prompts such as `E2E test: calicube-initial-color` — same idea: **named, human-driven** checks, not `am instrument`.

### Recording results

After every batch run, **update the results history** in project memory so future agents can distinguish regressions from pre-existing failures:

1. Read all result files: `cat c:/tmp/e2e-results/*.txt`
2. Add a new dated section to `~/.claude/projects/c--Users-kevin-projects-u1-slicer-orca/memory/e2e-results-history.md` with:
   - App version and date
   - Per-file pass/fail table
   - Any files that flipped state vs the prior run (PASS→FAIL = regression; FAIL→PASS = fixed)
3. If a failure was pre-existing in a prior run, say so explicitly — don't leave a future agent guessing.

Results files in `c:/tmp/e2e-results/` are transient (lost on reboot). The memory history file is the durable record.

### Expectations

- **Slow is OK.** Large files may take many minutes to load and slice.
- **Stopping** after a red failure is OK; fix and re-run the affected file before claiming the batch is green.
