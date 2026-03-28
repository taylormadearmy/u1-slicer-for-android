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
