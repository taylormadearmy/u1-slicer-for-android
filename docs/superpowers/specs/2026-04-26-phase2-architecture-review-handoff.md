# Phase 2 — architecture review handoff

**Status:** captured 2026-04-26 mid-implementation. Phase 2's UI is mostly
landed and pushed; smoke-test surfacing of basic bugs has revealed
structural problems with how the implementation grafted onto pre-existing
assumptions. Pausing for architectural review before continuing.

**Branch:** `feature/phase2-canonical-filaments`
**Latest commit:** `28137c5`
**Latest APK:** `G:/My Drive/claude/u1-slicer-phase2-28137c5.apk`

---

## §1 Where we are

### What's pushed and working

| Phase | Status | Notes |
|---|---|---|
| 2.0 — Design brief | ✅ Done | `docs/superpowers/specs/2026-04-26-canonical-filament-list-ux.md` |
| 2.1 — Canonical filament list data layer | ✅ Done, fully tested | 41 JVM + 9 instrumented tests on Pixel 8a; handles Bambu / SEMM / layer-tool / STL / PrusaSlicer |
| 2.3 — `applyPrintTimeRemap` | ✅ Done | 8 JVM tests; non-destructive G-code rewrite |
| 2.4 — Filament mapping dialog at Send | ✅ Done | Compose dialog appears on Map & Print / Map & Upload |
| 2.4-fix — Bug 1+2 (overrides flow to dialog + preview) | ✅ Done | StateFlow collector triggers preview refresh on override change |
| 2.5 — Skip slice-time remap (canonical T-indices) | ⚠️ Partial | The remap is skipped, but downstream paths still assume slot-mapped indices |
| 2.6a-c — Prepare card reshape (filament list with overrides) | ✅ Done | Tap-to-edit material + colour |
| 2.7 — Wire overrides into slicing | ⚠️ Partial | Material override reaches the embed but cascades through other filaments and temps don't align |
| 2.8 — Material-mismatch chip | ✅ Done | Surfaces in the Send dialog |

### What's not working (smoke-test surfacing)

The smoke-test cycle on H2C benchy revealed a chain of bugs, all
variations of the same theme:

1. **Slice summary shows 4 filaments instead of 7** (file's actual count).
   Root cause: `GcodeParser` hardcoded `FloatArray(4)` and `coerceIn(0, 3)`
   on T-index parsing. T6 became T3 silently.
2. **`filament_type` G-code header had 4 entries, padded to PLA** for the
   missing 3. Override material on filament 5+ silently dropped.
3. **`nozzle_temperature` all 220 even with PETG override.** Per-filament
   temps weren't reaching the slicer because the gate
   `canonical.size > extCount` was false when both were 7.
4. **Cascade — PETG appears at filament 0 AND 2.**
   `applyFilamentOverridesToPresets` modifies the slot's preset; when
   `buildPerFilamentTypeAndTemp` then reads modified presets as fallback,
   other filaments that share the slot inherit the override material.
5. **Summary still labelled "E1 · PLA" not "Filament 1 · PETG".**
   The renderer was slot-indexed, not file-filament-indexed.

I've shipped fixes for all five in `28137c5`. But the *kind* of bug —
"hardcoded assumption that filament count ≤ 4 lurking in five different
places" — is what worries me, not the individual instances.

---

## §2 The pattern: hardcoded 4-extruder assumptions

Every bug we've found this week was a variation of the same architectural
smell. The U1 has 4 physical extruders, so the codebase assumed
`extruders ≤ 4` everywhere — even in places that conceptually deal with
file-level filament data, which has no such cap (paint segmentation can
reference 7+, MMU2 can have 5, etc.).

| Where | Cap | What it broke |
|---|---|---|
| `GcodeParser.kt:55` | `FloatArray(4)` | Per-extruder mm accounting capped at 4 |
| `GcodeParser.kt:249` | `coerceIn(0, 3)` on T-index | T6 silently became T3 — DATA LOSS |
| `GcodeParser.kt:265` | `take(4)` on footer per-extruder | Same |
| `GcodeParser.kt:283` | `take(4)` legacy fallback | Same |
| `GcodeParser.kt:289` | `take(4)` final cap | Same |
| `SlicerViewModel.kt:4299` | `MutableList(extCount)` for filament_type | Override material at index 5+ dropped |
| `SlicerViewModel.kt:4200` | `nozzleTemps.take(extCount)` | Per-filament temps truncated |
| `SlicerViewModel.kt:2585` | `extruderPresets.value` (size 4) used to rebuild filament_type post-slice | Post-slice rebuild overwrote per-canonical types |
| `MainActivity.kt:2037` | `i.coerceIn(0, 3)` slot fallback in summary | Display only — capped to 4 |
| `MainActivity.kt:2107` | `(0 until count).map { it.coerceIn(0, 3) }` | Display only |
| `MainActivity.kt:2229` | `colors.take(4)` Job-list inline preview | Display only |
| `MainActivity.kt:3877` | `colorMapping.take(4)` G-code preview palette | Preview rendering for >4 filaments uses default colours for T4-T6 |
| `ProfileEmbedder.kt:380-381` | `normalizePerFilamentArrays` truncates to `targetCount` | When targetCount=4, every per-filament array gets truncated |

I've fixed the slice-correctness ones (1-8 in the table above). The
display-only ones (9-12) and the `normalizePerFilamentArrays` truncation
(13) are still in tree.

**This pattern is not random.** The codebase was built assuming "U1 has 4
extruders, so 4 is the natural cap everywhere." Phase 2 changed the
problem domain — files can have N filaments where N > 4, and we map them
to 4 slots at print time. The cap should *only* exist at the
"physical-slot" abstraction layer, not inside the slicer's per-filament
data model. The current code mixes these two concepts in many places.

---

## §3 The cleaner architecture (proposal)

### Concept: separate "file filament index space" from "physical slot space"

There are TWO distinct integer spaces the code needs to handle:

- **File filament index** (0..N-1, N = canonical list size). What the
  slicer emits as `T<n>` and what the file's `filament_colour` /
  `paint_color` triangles reference. **No cap** — N can be 7, 11, 32+.
- **Physical slot** (0..3 for U1). The hardware extruder. Capped at 4
  for U1 specifically; other Snapmaker printers might differ.

The mapping from file filament → physical slot is what the user picks in
the Filament mapping dialog. **It only matters at print time** —
specifically, between the slicer emitting canonical G-code and the
upload to the printer.

### Where each space should live

| Layer | Index space | Cap |
|---|---|---|
| File parse / canonical list | File filament | None |
| Slicer config (filament_colour, filament_type, nozzle_temp arrays) | File filament | None — sized to N |
| Slicer output (G-code T-indices, M104 T-params) | File filament | None |
| `parsedGcode.perExtruderFilamentMm` | File filament | None |
| Slice summary card | File filament | None |
| 3D preview palette | File filament | None |
| Print-time remap input → output | File filament → physical slot | Output capped at 4 |
| Final G-code uploaded to printer | Physical slot | 4 |
| Extruder presets | Physical slot | 4 |

Today's code has these spaces *interleaved* — filament_type at the slicer
config layer is sized by `extCount`, which is sometimes the file's
filament count and sometimes the physical slot count, depending on the
call path.

### The minimum-viable refactor

1. **`GcodeParser` is file-filament-indexed.** Already done in `95c5dfe`.
   `perExtruderFilamentMm.size` = N (file's filament count).
2. **`buildProfileOverridesImpl` is file-filament-indexed.** Already mostly
   done in `95c5dfe` + `28137c5`. `filament_type`, `nozzle_temperature`
   sized by canonical list, not by `extCount`.
3. **`ProfileEmbedder.normalizePerFilamentArrays` should not truncate.**
   `targetCount` should always match the canonical list size; the
   `targetCount > 4` case should not be treated as "wrong" and truncated.
4. **`extCount` parameter retired** from any function that operates on
   file-filament data. It's a slot-space concept; using it as a
   filament-space size is the source of the cascading bugs.
5. **`applyFilamentOverridesToPresets` retired.** It's a slot-space
   modification of presets; per-filament overrides should NOT cascade
   through it. The slicer reads filament_type from per-canonical-filament
   arrays directly, with no preset cascade.
6. **`computeFreshExtruderTemps` is file-filament-indexed** (currently
   slot-indexed). For multi-filament files, temps come from the
   canonical entry's material, not the slot's preset.
7. **3D preview palette N-indexed** instead of 4-indexed. Today's
   `normalizeGcodePreviewColors` returns a 4-list; should return an
   N-list. Renderer reads `palette[T]` directly.
8. **`colorMapping`'s role narrowed** to "user's print-time slot pick."
   Stops being used for slice-time embed sizing or per-slot preset
   selection. Lives only in the Filament mapping dialog and the
   print-time remap.

### What survives

- Extruder presets (slot-indexed, capped at 4) — these represent the
  printer's hardware loadout, not the file's filaments. Still useful for
  the dialog's auto-suggest and the slot's loaded-spool display.
- Print-time mapping at Send — already correct.
- `applyPrintTimeRemap` — already correct, file-filament-indexed input,
  slot-indexed output.

---

## §4 Why our 60+ tests didn't catch these bugs

We have:
- 41 JVM unit tests in the canonical-list package
- 9 instrumented tests for the canonical list dispatcher
- 8 JVM tests for `applyPrintTimeRemap`
- 9 JVM tests for `computeDialogRewrite`
- 6 instrumented tests for the dialog's auto-suggest

**What they cover:** correctness of individual functions in isolation —
data-class shape, paint-state decoding, single-format parsing, mapping
rewrite math.

**What they don't cover:** the *integration* between layers. Specifically:
- Does the G-code header's `filament_type` line match what the canonical
  list says?
- Does `nozzle_temperature` align with `filament_type` index-by-index?
- Does the slice summary's filament count equal the file's filament
  count?
- Does an override on filament N produce the override material at index
  N in the G-code header (and ONLY at index N — no cascade)?

These integration concerns require fixture-driven tests that:
1. Load a real 3MF
2. Set an override
3. Slice end-to-end
4. Inspect the G-code header
5. Assert the per-filament alignment

`BambuPipelineIntegrationTest` and `SlicingIntegrationTest` are the right
homes for these — but the tests there are pre-Phase-2 and assert older
behaviour. Phase 2's invariants haven't been added.

**Recommended testing addition before continuing:**

```kotlin
@Test
fun h2cBenchy_overrideFilamentZeroToPETG_emitsPETGAtIndexZeroAndPLAElsewhere() {
    // 1. load colored_3DBenchy or H2C benchy fixture
    // 2. setFilamentMaterialOverride(0, "PETG")
    // 3. startSlicing(), wait for completion
    // 4. read result.gcodePath
    // 5. assert filament_type line == "PETG;PLA;PLA;PLA;PLA;PLA;PLA"
    // 6. assert nozzle_temperature line == "235,220,220,220,220,220,220"
    // 7. assert no other filament index has PETG (cascade detector)
}
```

Tests like this would have caught every bug we found this week.

---

## §5 Two paths forward

### Path A — Continue patching (status quo)

- Keep finding bugs case by case
- Each bug is a 1-2 hour fix
- Estimated remaining: 3-5 more hardcode caps, one or two cascade
  variants, the `normalizePerFilamentArrays` truncation
- ~6-10 hours total
- Beta-shippable in a session or two
- Architecture stays muddled — every future feature has to navigate
  the file-filament vs slot mixing

### Path B — Refactor to clean architecture

- Implement the §3 §minimum-viable refactor (8 numbered bullets)
- ~1-2 days of focused work
- Add the integration tests from §4 first to lock in correctness
- Beta is delayed by the refactor window
- Architecture is clean — future features (printer-side sync, pre-slice
  merge, multi-material wipe-tower optimisation) drop in cleanly

### My honest take

**Path B is the right call** if the goal is a sustainable Phase 2.
We've already invested significant effort; a few more days of refactor
saves us from the same class of bug ambushing the user post-beta.

**Path A is the right call** if we just want to ship Phase 2 to a few
beta users for UX validation, then refactor based on what we hear.

The user's intent (their "I want to get to beta as fast as possible"
from earlier in the session) leans Path A. But the "I'm worried about
basic issues" sentiment leans B.

---

## §6 Where to hand off

If you're a fresh agent picking this up:

1. **Read first**:
   - This doc.
   - `docs/superpowers/specs/2026-04-26-canonical-filament-list-ux.md`
     (the resolved UX brief; §7 captures the Prepare-screen reshape).
   - `docs/superpowers/specs/2026-04-26-canonical-filament-list-design.md`
     (the original Phase 2 skeleton).

2. **Latest state**:
   - Branch: `feature/phase2-canonical-filaments`
   - Latest commit: `28137c5`
   - APK: `G:/My Drive/claude/u1-slicer-phase2-28137c5.apk`
   - All Phase 2 commits between `495288a` and `28137c5`.

3. **Test surface**: 49+ JVM unit tests, 15 instrumented tests, all
   green on Pixel 8a (43211JEKB16931). Run with:
   ```bash
   ./gradlew testDebugUnitTest --no-daemon
   ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest --no-daemon
   ```

4. **Reproducing the smoke-test bugs**:
   - Load H2C benchy on the Pixel 8a (`3DBenchy-H2C-Multi-Color.3mf` is in
     `app/src/androidTest/assets/`).
   - On Prepare, tap Filament 1's material chip → pick PETG.
   - Slice (Slice button on Prepare).
   - Tap Map & Print on Preview.
   - Confirm dialog (auto-suggested mapping).
   - Inspect `Map & Upload` upload's G-code via `G:/My Drive/logs/output (N).gcode`.
   - Verify expected outcomes:
     - `filament_type = PETG;PLA;PLA;PLA;PLA;PLA;PLA`
     - `nozzle_temperature = 235,220,220,220,220,220,220`
     - Slice summary shows "Filament 1 · PETG" with PETG temps.

5. **Decision needed**: Path A vs Path B from §5. Recommend bringing
   this question to the user explicitly.

6. **If Path B**: the §3 minimum-viable refactor list is the work
   queue. Suggest tackling in order — `extCount` retirement first
   (§3 #4) is the highest-leverage simplification.

---

## §7 What I'd advise next

If I were the next agent picking this up cold, I'd:

1. Run the smoke-test sequence from §6 on `28137c5`. Confirm
   what's still broken vs fixed.
2. Add the integration test from §4 to lock in the regression.
3. Have the Path A vs Path B conversation with Kevin explicitly.
4. If Path B: start with §3 #4 (`extCount` parameter retirement),
   commit after each numbered bullet, integration test after each.

Stopping here so the architecture review can happen with fresh eyes.
