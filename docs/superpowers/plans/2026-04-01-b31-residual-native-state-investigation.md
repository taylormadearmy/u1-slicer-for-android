# B31 Residual Native State Investigation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Determine whether uninitialised or stale native C++ global/static state can still cause intermittent Clipper2 coordinate overflow *without* a coordinate value out of bounds — i.e., the B31 "stale state" path that persists even after the wipe_tower_y=240 list-clamp fix.

**Architecture:** Read-only investigation. No code changes until the root cause is confirmed. The investigator produces a written findings report; a separate session implements any fixes.

**Tech Stack:** C++ (OrcaSlicer/libslic3r), Android JNI, existing diagnostic log corpus in `G:/My Drive/Logs/`

---

## Background

B31 originally had two distinct root causes fixed at different times:

1. **`Print::m_origin` uninitialised (fixed v1.5.0)** — `Vec3d m_origin` had no initialiser; on Android `set_plate_origin()` is never called; in release builds `m_origin.y()` sometimes contained `-inf`, corrupting every wipe tower travel move. Fixed: `Vec3d m_origin = Vec3d::Zero()` in `Print.hpp`.

2. **Post-upgrade warm reload (fixed v1.4.24)** — A `clearModel()` + `loadModel()` call sequence left OrcaSlicer global statics inconsistent. Fixed by removing the warm reload.

The recent investigation (2026-04-01) found a third trigger: `wipe_tower_y=240` bypassing the Kotlin-side clamp (now fixed in v1.5.26). However, the `calicubenowworking.txt` log showed that **identical parameters (Y=240) succeeded in a fresh process but failed in a previously-used process** — meaning native state from a prior failed slice (3DBenchy H2C) poisoned the next slice. This happened even though the wipe_tower_y geometry *by itself* is the proximate cause; the question is whether there are additional unguarded statics that can cause the same intermittent pattern independently.

## Key question

After the `m_origin` fix in v1.5.0, are there **other** uninitialised or non-reset C++ members in the slice path that can corrupt geometry on second-or-later slices within the same process lifetime? Specifically:

- Members of `Print`, `PrintObject`, `WipeTower2`, `WipeTowerIntegration`, or `FakeWipeTower` that are set during `process()` but never cleared before the next `slice()` call
- Static variables inside anonymous namespaces or file scope in libslic3r that accumulate state across slices
- `prime_tower_brim_chamfer=true` (default since March 7) — does it introduce new geometry that exercises a previously-safe code path?

---

## Task 1: Audit C++ member initialisers in the wipe tower path

**Files:**
- Read: `app/src/main/cpp/orcaslicer/src/libslic3r/Print.hpp` — check all member initialisers
- Read: `app/src/main/cpp/orcaslicer/src/libslic3r/WipeTower.hpp` — check all member initialisers  
- Read: `app/src/main/cpp/orcaslicer/src/libslic3r/WipeTower2.hpp` (if exists)
- Read: `app/src/main/cpp/src/sapil_internal.h` — check FakeWipeTower and any other structs

- [ ] **Step 1: Find all uninitialised members in Print.hpp**

  Search for member declarations without initialisers:
  ```bash
  grep -n "^\s\+[A-Za-z].*[^=;{]\s*;" app/src/main/cpp/orcaslicer/src/libslic3r/Print.hpp | grep -v "//" | head -60
  ```
  Focus on: `Vec2d`, `Vec3d`, `double`, `float`, `bool`, `int`, `size_t` members.
  For each: is there a default initialiser (`= 0`, `= false`, `= Vec3d::Zero()`)? If not, note it.

- [ ] **Step 2: Find all uninitialised members in WipeTower.hpp**

  ```bash
  grep -n "^\s\+[A-Za-z].*[^=;{]\s*;" app/src/main/cpp/orcaslicer/src/libslic3r/WipeTower.hpp | grep -v "//" | head -60
  ```

- [ ] **Step 3: Check FakeWipeTower in sapil_internal.h**

  ```bash
  grep -n "struct FakeWipeTower\|FakeWipeTower" app/src/main/cpp/src/sapil_internal.h
  grep -n "" app/src/main/cpp/src/sapil_internal.h
  ```
  All members should have initialisers (this was explicitly fixed in v1.5.0 — verify the fix is present and complete).

- [ ] **Step 4: Check WipeTowerIntegration**

  ```bash
  find app/src/main/cpp/orcaslicer -name "WipeTowerIntegration*" 2>/dev/null
  grep -n "struct WipeTowerIntegration\|class WipeTowerIntegration" app/src/main/cpp/orcaslicer/src/libslic3r/GCode.hpp 2>/dev/null | head -5
  ```
  Read the relevant section and note any uninitialised numeric/pointer members.

- [ ] **Step 5: Write findings to `docs/superpowers/findings/b31-member-audit.md`**

  Format:
  ```
  ## Uninitialised members found
  File | Member | Type | Risk | Notes
  
  ## Members confirmed initialised (previously fixed)
  ...
  
  ## Verdict
  ```

---

## Task 2: Audit static variables in the Clipper/geometry path

**Files:**
- Read: `app/src/main/cpp/orcaslicer/src/libslic3r/ClipperUtils.cpp`
- Read: `app/src/main/cpp/orcaslicer/src/libslic3r/Brim.cpp` (brim chamfer geometry)
- Search broadly for `static ` in the wipe-tower and Clipper code

- [ ] **Step 1: Find file-scope and function-scope static variables in ClipperUtils.cpp**

  ```bash
  grep -n "^static \|^\tstaticg " app/src/main/cpp/orcaslicer/src/libslic3r/ClipperUtils.cpp | head -30
  ```

- [ ] **Step 2: Find statics in Brim.cpp**

  ```bash
  grep -n "static " app/src/main/cpp/orcaslicer/src/libslic3r/Brim.cpp | head -30
  ```
  `prime_tower_brim_chamfer=true` is the new default. If Brim.cpp has statics that accumulate brim geometry between calls, that would explain why the second slice with the same model fails.

- [ ] **Step 3: Check WipeTower2.cpp for statics**

  ```bash
  find app/src/main/cpp/orcaslicer -name "WipeTower2.cpp" 2>/dev/null
  grep -n "static " app/src/main/cpp/orcaslicer/src/libslic3r/WipeTower2.cpp 2>/dev/null | head -30
  ```

- [ ] **Step 4: Check sapil_print.cpp for any persistent state between calls**

  ```bash
  grep -n "static \|thread_local" app/src/main/cpp/src/sapil_print.cpp | head -20
  ```
  The `slice_impl()` function creates a new `Slic3r::Print print;` on the stack each call — confirm this is true and that no shared globals are mutated.

- [ ] **Step 5: Write findings to `docs/superpowers/findings/b31-statics-audit.md`**

---

## Task 3: Trace the `prime_tower_brim_chamfer` geometry path

This is the most likely new contributor. The chamfer was added as a default in commit `cd6d130` (March 7). It adds geometry *around* the prime tower brim. If that geometry extends past the bed boundary at all, Clipper2 can overflow.

**Files:**
- Read: `app/src/main/cpp/orcaslicer/src/libslic3r/Brim.cpp` — find `prime_tower_brim_chamfer` usage
- Read: `app/src/main/cpp/orcaslicer/src/libslic3r/PrintConfig.cpp` — find default value and description

- [ ] **Step 1: Find where prime_tower_brim_chamfer is consumed in Brim.cpp**

  ```bash
  grep -n "prime_tower_brim_chamfer\|chamfer" app/src/main/cpp/orcaslicer/src/libslic3r/Brim.cpp | head -30
  ```

- [ ] **Step 2: Understand the geometry it produces**

  Read the surrounding function (typically ~20 lines). Does it add an outward offset to the brim? How wide? Is the width `prime_tower_brim_chamfer_max_width`?

- [ ] **Step 3: Check the default value in PrintConfig.cpp**

  ```bash
  grep -n "prime_tower_brim_chamfer" app/src/main/cpp/orcaslicer/src/libslic3r/PrintConfig.cpp | head -10
  ```
  What is OrcaSlicer's compiled default? Does `profile_keys[]` in `sapil_print.cpp` allow it to be overridden?

- [ ] **Step 4: Estimate the geometry contribution**

  Given:
  - Bed: 270×270mm
  - Tower at Y=240 (before the v1.5.26 clamp fix), width=60mm
  - Tower top edge: ~270mm (already at boundary)
  - Chamfer: adds outward offset of `prime_tower_brim_chamfer_max_width` (default?)
  
  Calculate: does chamfer geometry extend past 270mm even with tower at Y=231 (clamped)?
  If `231 + 30(half-width) + brim(3) + chamfer_width > 270`, there is still a risk.

- [ ] **Step 5: Write findings to `docs/superpowers/findings/b31-chamfer-geometry.md`**

  Include the calculation and a clear verdict: is `prime_tower_brim_chamfer=true` safe with Y=231?

---

## Task 4: Cross-reference findings against the calicubenowworking.txt log

**File:** `G:/My Drive/Logs/calicubenowworking.txt` (already read in session 2026-04-01)

Key facts from the log:
- Session `13691` (same wipe_tower_y=240): **FAIL**
- Session `24660` (same wipe_tower_y=240, fresh process): **PASS**
- The session before `13691` in the same process (`6025`) had sliced 3DBenchy H2C (which also failed)
- The 3DBenchy H2C has `extruderCount=4`, `wipeTowerX=230`, `wipeTowerY=10`

- [ ] **Step 1: Re-read the 3DBenchy H2C slice geometry**

  From the log: `worldBounds xMin=108, xMax=168, yMin=129, yMax=160`. Tower at X=230, Y=10, width=30. Tower right edge = 230+15+brim(3)+chamfer = 248+chamfer. Is this safe?

- [ ] **Step 2: Check if 3DBenchy H2C failure has a coordinate dump**

  The 3DBenchy H2C `clipper_failure` in `clipper_investigation_bundle (1).txt` has **no** `clipper_coordinate_out_of_range` event before it — meaning the coordinate log was not present in that build (v1.5.25 at time of the 3DBenchy test). The calib-cube failures in the same bundle do have the coordinate dump (`y=-9223372036854775808`).

  Document: the 3DBenchy failure mechanism is *unknown* — it may be a different geometry issue, not the Y=240 path. This is a gap.

- [ ] **Step 3: Write a findings summary to `docs/superpowers/findings/b31-log-analysis.md`**

  - What we know with certainty
  - What is still unknown
  - Recommended next diagnostic steps if the issue recurs

---

## Task 5: Produce consolidated recommendation

After completing Tasks 1–4, write `docs/superpowers/findings/b31-investigation-summary.md`:

- [ ] **Step 1: Summarise all confirmed and suspected issues**

- [ ] **Step 2: For each issue: is it fixed, partially fixed, or still open?**

- [ ] **Step 3: Recommend any additional native fixes**

  Likely candidates based on prior history:
  - Any uninitialised numeric members found in Task 1 → add `= 0` / `= false` / `= Vec3d::Zero()`
  - If chamfer geometry can still overflow at Y=231 → tighten the Kotlin clamp or add a `prime_tower_brim_chamfer_max_width` safety cap in `applyConfigToPrusa()`
  - If statics accumulate geometry (Task 2) → document which statics and whether clearing them is safe

- [ ] **Step 4: Flag whether a native rebuild is required**

  If uninitialised member fixes are needed, the `.so` must be rebuilt. State this clearly.

---

## Notes for investigator

- This is **read-only investigation**. Do not make code changes. Write findings only.
- The native source is at `app/src/main/cpp/`. The pre-built `.so` is in `app/src/main/jniLibs/arm64-v8a/`.
- `CLIPPER_UPGRADE_INVESTIGATION.md` at the repo root contains the full history of the original B31 investigation — read it for context on what was already tried and found.
- The `calicubenowworking.txt` log path: `G:/My Drive/Logs/calicubenowworking.txt`
- Commit `a554c36` is the v1.5.0 release that fixed `m_origin` — `git show a554c36` shows exactly what was changed in `Print.hpp`.
