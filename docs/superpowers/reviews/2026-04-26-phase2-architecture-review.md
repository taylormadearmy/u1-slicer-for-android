# Phase 2 architecture review — fresh-eyes critique

**Status:** captured 2026-04-26 mid-implementation, in response to a pause
called by Kevin after smoke-testing on H2C benchy surfaced a chain of
structurally-related bugs.

**Companion to:**
- [`2026-04-26-phase2-architecture-review-handoff.md`](../specs/2026-04-26-phase2-architecture-review-handoff.md) (the prompt for this review)
- [`2026-04-26-canonical-filament-list-design.md`](../specs/2026-04-26-canonical-filament-list-design.md) (Phase 2 skeleton)
- [`2026-04-26-canonical-filament-list-ux.md`](../specs/2026-04-26-canonical-filament-list-ux.md) (resolved UX brief)

**Branch:** `feature/phase2-canonical-filaments`
**HEAD at review:** `f334c59`

---

## §0 Resolutions and operating principle

Operating principle for the rest of this review and the work it
recommends: **robust, clean, reliable architecture without tech debt**.
Path B is decided. No quick-fix shortcuts; no half-measures left for
later cleanup.

Question resolutions from the review conversation, baked into the
recommendations below:

| # | Question | Resolution |
|---|---|---|
| 1 | What does "beta" mean? | Feature-complete, production-ready code; limited audience first. Treat as a real release. |
| 2 | Plate scope of canonical list | Full file's list to the slicer (matches desktop). Per-plate "active filaments" is a UI concern only. |
| 3 | Auto-suggest reads from? | The user's overridden colour. The override is the user's intent; auto-suggest matches against that. |
| 4 | Slice-staleness on override change | Yes — overriding a filament's material or colour must mark slice stale. |
| 5 | Layer-tool synthetic expansion | User choice per file/plate. |
| 6 | `hasMultiExtruderAssignments` retirement | Retire as part of this Phase 2 refactor (bullet #9 in §4). |
| 7 | Path A+ vs Path B preference | Path B. |

---

## §1 The bug pattern, reframed

The handoff §2 framing — "file filament index space vs physical slot
space" — is correct, but it diagnoses the symptom rather than the
disease. The disease is that **the type system isn't expressing the
index spaces**. Every cap (`FloatArray(4)`, `coerceIn(0,3)`, `take(4)`,
`MutableList(extCount)`) is the same primitive — `Int` — used for two
semantically distinct things, with no type-level barrier between them.
In a stronger system you'd have `FilamentIdx` and `SlotIdx` as opaque
types and the compiler would have caught all 13 sites at the moment the
assumption changed. In Kotlin you encode the meaning in convention, and
at the moment the convention changes (Phase 2) every site needs surgery.

The reason the pattern leaked into 13 places isn't sloppy coding — it's
that **for the entire pre-Phase-2 history, file-filament-count and
slot-count were always the same number**. There was never a need to
distinguish them. So when the variable named `extCount` got introduced,
it could mean either; nobody chose, because the choice didn't matter.
Phase 2 is the first time the two diverge, and now every site that
conflated them is a bug.

The §2 framing is **necessary but not sufficient**. It dissolves
slicer-side bugs (1–3, 6–8 in the handoff table) and display bugs
(9–12) cleanly. It does **not** dissolve bug 4, the cascade — because
that cascade lives across a *third* axis: **override-source space**.
The user's override is per-filament; `applyFilamentOverridesToPresets`
writes it into a per-slot preset table; `buildPerFilamentTypeAndTemp`
reads back from that slot table to drive a per-filament array. So the
override travels filament → slot → filament, and on the way through the
slot table it cascades to every filament that maps to the same slot.
Two-axis framing won't catch this; you need to recognise that
**per-filament overrides should never round-trip through the
slot-preset table** at all. Bullet 5 (retire
`applyFilamentOverridesToPresets`) is the right fix; the framing in §2
understates why it matters.

So: the §2 framing captures ~80% of the smell. Add "user-override
storage is per-filament, not per-slot" as a third axis and you cover
the remaining ~20%.

---

## §2 §3 sanity-check — does the proposal dissolve the bug class?

The 8 bullets are individually sound, and together they dissolve the
bug class. Notes per bullet:

- **#1, #2, #3** are all correctness-bearing and largely done. #3
  (don't truncate `normalizePerFilamentArrays`) is the easiest one
  missing; it should land before any further refactor.
- **#4 (retire `extCount`)** is the *single highest-leverage* change in
  the list. `extCount` is the variable that conflates the two index
  spaces; deleting the parameter forces every caller to disambiguate,
  and the compiler walks you through the rest.
- **#5 (retire `applyFilamentOverridesToPresets`)** is the cascade fix.
  Highest-risk change of the eight, because the slot-preset machinery
  probably has more callers than the bullet suggests. Needs a grep
  before estimating.
- **#6 (`computeFreshExtruderTemps` file-filament-indexed)** is subtler
  than written. Today the function is slot-indexed because it's also
  used for the printer's loaded-spool display. Don't make it
  dual-purpose; **split into two functions** (`computeFreshFilamentTemps`
  for slicer embed, `computeFreshSlotTemps` for printer-side UI) so
  the index space is in the function name.
- **#7, #8** are clean.

### Edge cases / interactions the proposal misses

1. **`hasMultiExtruderAssignments`** is named in the design doc §4 risk
   5 but absent from §3. It gates `ProfileEmbedder`'s preserve-vs-rebuild
   path; if the refactor lands without retiring it, you'll get a partial
   refactor where some embed paths still use slot semantics. Added as
   bullet #9 in §4.

2. **Plate-scoped filament narrowing.** Resolved (Q2 above): full file
   list to the slicer, per-plate is UI-only. But the slicer config gets
   the full list; the canonical list itself stays file-scoped; the
   chip strip in Prepare hides unused. Three layers, three rules — needs
   to be explicit in the data model contract.

3. **Auto-suggest reads from overridden colours** (Q3 above). Bullet
   #8 narrows `colorMapping` to print-time but doesn't say what
   auto-suggest reads. Resolution: auto-suggest's colour input is
   `canonical[i].resolvedColor` (file → override), never the file's
   raw `filament_colour[i]`. Worth making this contract explicit in
   the function signature.

4. **Slice-staleness on override change** (Q4 above). Today `_sliceStale`
   listens to config mutation (per `SliceStalenessTest`). Phase 2 needs
   the same hook on per-filament overrides — set stale when
   `canonical[i].materialOverride` or `colorOverride` mutates.

### New bug classes the refactor might introduce

- **Stale per-file overrides.** Keyed by file hash. If the user
  externally edits the file, the hash changes, overrides reset
  silently. Probably acceptable but needs a test.
- **Slot-preset display vs filament-override display divergence.**
  If filament 3 is overridden green PETG and maps to slot 2 (preset:
  red PLA), the chip strip shows green, the slot list in Settings
  shows red. Information-architecture risk, not a bug; surface it
  consciously rather than discovering it in beta.
- **Layer-tool synthetic expansion path** (Q5 above). Now a per-file/
  per-plate user choice. The data model needs to carry the user's
  choice as a property of the file (or plate) — and the load-time
  normalize needs to consult it. New surface area, not in §3.

---

## §3 Path A vs Path B — recommendation

**Path B**, decided.

Reasoning, recorded for completeness. Path A's bugs are not "more
4-extruder hardcodes to find" — they are **silent data-corruption
bugs** (T6 → T3 is colour assignment to the wrong filament; the user
might not notice for a multi-hour print). A production audience, even
small, is exactly the population most likely to push the code outside
its 4-extruder assumptions because they're the ones experimenting
with H2C / MMU / paint-segmentation files. The Path A bug class lands
hardest exactly where it'll be loudest.

The marginal cost of Path B is also smaller than the handoff doc
estimates. §3 is already designed; the integration-test framework
already exists; the affected surface is mostly Kotlin (no native
rebuild).

---

## §4 Path B — ordered plan

The "is this on track?" gate is **steps 1–3** — tests first, then
`extCount` retirement, then the easy correctness fix that follows from
it. Confirm the integration tests are green at that point before
continuing. If step 2's callsite fan-out is larger than expected, do
the work properly anyway; the operating principle (§0) is no half-
measures.

### Step 1 — Tests first

Add 4–5 integration tests in `BambuPipelineIntegrationTest` (or a new
`Phase2AlignmentTest`):

- **Cascade detector.** H2C benchy, override filament 0 → PETG. Assert
  `filament_type` line is `PETG;PLA;PLA;PLA;PLA;PLA;PLA`,
  `nozzle_temperature` is `235,220,220,220,220,220,220`, no other index
  has PETG.
- **High-index override.** H2C benchy, override filament 5 → PETG.
  Assert `filament_type[5] = PETG`, others PLA. (Catches site 13
  truncation.)
- **STL baseline.** Single-colour STL, no override. Assert single-row
  canonical list.
- **Multi-plate sanity.** Buzz plate 7 (or similar), confirm canonical
  list has full file palette and slicer-emitted index range matches.
- **Source-grep guard.** A `HardcodedExtruderCapTest.kt` that fails if
  `coerceIn(0, 3)`, `take(4)`, or `FloatArray(4)` appears in `gcode/`,
  `bambu/`, or `SlicerViewModel.kt`. Cheap regression net; fits
  existing `FilamentTypeHeaderPatchTest`-style structural-guard
  pattern.

**Best single test:** the cascade detector with a strong assertion
shape. The right shape is *"PETG appears AND ONLY appears at index 0"*
— a weak test ("filament_type[0] = PETG") passes even if the cascade
also wrote PETG to index 2. Worth saying out loud because it's easy
to write the weak version. Parameterise across all N indices on a
7-filament fixture for ~35–49 alignment assertions from one test.

### Step 2 — Bullet #4: `extCount` retirement

Replace `extCount` with explicit `filamentCount` (file-filament space)
or `slotCount` (physical-slot space, hardcoded to 4 for U1) at every
callsite. Compile errors drive the work. **Highest-leverage
simplification** because it forces the disambiguation the codebase has
been deferring.

Consider going further: introduce `value class FilamentIdx(val v: Int)`
and `value class SlotIdx(val v: Int)` so the type system enforces the
distinction. Cheap in Kotlin (no runtime cost), turns every future
hardcode regression into a compile error, and aligns with the §0
"no tech debt" principle. Recommended.

### Step 3 — Bullet #3: `normalizePerFilamentArrays` no truncation

Trivial after #2. Verified by the cascade detector + high-index
override tests from step 1.

**— commit + smoke-test gate —**

### Step 4 — Bullet #5: retire `applyFilamentOverridesToPresets`

The cascade fix. Highest-risk change. Replace with direct per-filament
override application — overrides apply to `canonical[i]`, not to
slot presets. Verified by the cascade detector test from step 1.

Grep all callers first; do every site in one pass. Leaving any caller
on the slot-preset path reintroduces the cascade.

### Step 5 — Bullet #6: split `computeFreshExtruderTemps` into two functions

- `computeFreshFilamentTemps(canonical: List<FilamentEntry>): List<Int>`
  — file-filament-indexed, drives slicer embed.
- `computeFreshSlotTemps(presets: List<ExtruderPreset>): List<Int>`
  — slot-indexed (size 4), drives printer-side UI.

Compile errors guide migration; the callsite that wanted slot temps
ports to the slot variant, the callsite that wanted filament temps
ports to the filament variant, and `extCount` confusion is gone here
too.

### Step 6 — Bullet #7 + display fixes (sites 9–12)

3D preview palette N-indexed; `MainActivity` display sites use
canonical list size, not `coerceIn(0, 3)`. Display-only; lowest
correctness risk.

### Step 7 — Bullet #8: `colorMapping` narrowed to print-time

Mostly already done. Finalise the contract: `colorMapping` lives only
in (a) the Filament mapping dialog state and (b) the
`applyPrintTimeRemap` step. Slice-time code reads `canonical[i]`
directly.

### Step 8 — Bullet #9 (new): retire `hasMultiExtruderAssignments`

After the canonical list always carries per-object assignments, the
flag is redundant. Audit `ProfileEmbedder` for callers; replace with
direct canonical-list consultation. Schedule alongside step 4 because
both touch the embed path.

### Step 9 — Wire override-driven slice staleness

Per Q4: hook `_sliceStale` to mutations of `canonical[i].materialOverride`
and `canonical[i].colorOverride`. Should be a one-line
`combine`/`map` on the relevant StateFlow.

### Step 10 — Layer-tool user choice

Per Q5: data model needs a `layerToolMode: SINGLE_PAUSE | MULTI_FILAMENT`
property on `CanonicalFilamentList` (or per-plate). Load-time normalize
defaults to `MULTI_FILAMENT` for layer-tool files (matches existing
behaviour); UI surface for the toggle is a Phase 2.x decision, not
this refactor — but the data shape needs to support it now so the UI
work later doesn't reshape the canonical list.

---

## §5 Test gap analysis — validate / push back

The handoff §4 is right that fixture-driven integration tests are the
missing layer, but I'd push back on it being the *complete* answer.

The 13 hardcode sites split into three categories:

- **Slicer-correctness sites (1–8, 13):** integration tests catch these.
- **Display-only sites (9–12):** integration tests do NOT catch these
  because the display path is UI-only, the slicer output is fine. You
  need either Compose UI tests (heavyweight) or **structural source-grep
  tests** (cheap, already a pattern in this codebase via
  `ModelInfoDialogScrollTest`).
- **Cascade / cross-layer sites (4):** integration tests catch these,
  *if and only if* the assertion is shaped right. A test that asserts
  "filament_type[0] = PETG" passes even if filament_type[2] *also* =
  PETG. The right shape is **"PETG appears AND ONLY appears at index
  0"** — the cascade detector. Worth saying out loud because it's easy
  to write the weak version.

### A specific test that would have caught every slicer-correctness bug

```
parameterised over (fixtureFile, overrideIndex, overrideMaterial):
  load fixture
  apply override at overrideIndex
  slice
  for i in 0 until canonicalList.size:
    expected = if (i == overrideIndex) overrideMaterial
               else fixture.filament[i].material
    assert filament_type[i] == expected.name
    assert nozzle_temperature[i] == expected.defaultTemp
```

One test, parameterised across 5–7 indices on a 7-filament fixture,
gives 35–49 assertions of the alignment invariant. Better coverage
than 5 hand-written integration tests *and* it scales to whatever N
the file has.

### Cheaper than fixture-driven integration tests

The source-grep tests, for the display-only sites. A single
`HardcodedExtruderCapTest.kt` that fails on
`coerceIn(0, 3)|take(4)|FloatArray(4)` in slicer-related packages
costs ~10 minutes to write and forever-protects against regression of
the same pattern.

### Don't add

Mutation testing as a routine. It would catch every hardcode but it's
a major infra investment.

### Do add explicitly

An instrumented test for the override-on-Prepare → slice → G-code path.
Today the override flow is partially covered by
`FilamentTypeHeaderPatchTest` (unit) and `BambuPipelineIntegrationTest`
(integration), but there's no end-to-end test from "user taps the
material chip" → "G-code header reflects it." That's the gap that
produced bug 5 (slice summary still labelled E1·PLA).

---

## §6 Summary of recommended action

1. **Decision locked: Path B.** Refactor before beta.
2. **Bullet list grows from 8 to 9** with `hasMultiExtruderAssignments`
   retirement; data-model additions for slice-staleness on override
   (Step 9) and layer-tool user choice (Step 10) round out the work.
3. **Tests first, then `extCount` retirement.** Steps 1–3 are the
   "is this on track?" gate.
4. **Cascade detector test is the single most valuable addition.**
   Write it before any refactor. It would have caught bugs 1, 2, 4,
   and 5 from the handoff §1.
5. **Source-grep test for `coerceIn(0,3)|take(4)|FloatArray(4)`** as
   a forever regression net. Cheap; fits the codebase's structural-test
   pattern.
6. **Split `computeFreshExtruderTemps`** rather than retrofitting one
   function for both index spaces. Index space lives in the function
   name.
7. **Consider value classes (`FilamentIdx`, `SlotIdx`)** to make the
   distinction unforgeable at compile time. Aligned with the §0
   no-tech-debt principle.

End of review.
