# B31 Residual Native State — Investigation Summary

Date: 2026-04-01  
Investigator: subagent (Opus 4.6)  
Status: **READ-ONLY findings — no code changes made**

---

## Issue Status Table

| Issue | Status | Notes |
|---|---|---|
| `m_origin` uninitialised (Print.hpp) | **FIXED in binary** | v1.5.0 fix exists only in prebuilt `.so`; source not updated |
| Post-upgrade warm reload | **FIXED (v1.4.24)** | Removed clearModel+loadModel warm reload |
| `wipe_tower_y=240` bypassing Kotlin clamp | **FIXED (v1.5.26)** | `List<*>` skip in `clampScalarPosition` resolved |
| `m_isBBLPrinter` uninitialised | **FIXED in binary** | Source not updated |
| `FakeWipeTower` members uninitialised | **FIXED in binary** | Source not updated; v1.5.0 commit message confirms binary fix |
| `PrintObject::m_max_z`, `m_id`, `m_center_offset` uninitialised | **STILL OPEN** | Not addressed in v1.5.0; potential fresh-process failure vector |
| `PrintInstance::id`, `unique_id` uninitialised | **STILL OPEN** | Not addressed |
| `prime_tower_brim_chamfer` not in profile_keys whitelist | **OPEN (low risk)** | Uses compiled default=true; not configurable from Kotlin |
| `prime_tower_brim_chamfer_max_width` not in profile_keys whitelist | **OPEN (low risk)** | Uses compiled default=4mm; not configurable from Kotlin |
| Fresh-process INT64_MIN Clipper failure (pid=13691) | **ROOT CAUSE UNKNOWN** | Both pid=13691 and pid=24660 were fresh processes; only one failed |

---

## Critical Finding: Both failing and succeeding sessions were fresh processes

The log (`calicubenowworking.txt`) contradicts the premise that "stale state from 3DBenchy poisoned the next process." Both pid=13691 (FAIL) and pid=24660 (PASS) showed `post_upgrade_guard_observed status=fresh_process` and `savedInstanceState=false/true` respectively. There is **no evidence of a prior 3DBenchy slice in pid=13691 within this log**.

This means the Y=240 geometry **itself** was the proximate cause in pid=13691 — consistent with it landing at ~263mm (within bounds), but possibly in combination with an uninitialised `PrintObject` member that had an unlucky stack value.

The `x=6732854, y=-9223372036854775808` Clipper coordinate dump: the Y value of `LLONG_MIN` strongly suggests an uninitialised `int64_t` somewhere in the Clipper geometry path, not a simple boundary overflow. The x=6732854 is plausibly a legitimate scaled coordinate (~6.7mm in Clipper's internal units).

---

## Chamfer Geometry Analysis

`prime_tower_brim_chamfer` does NOT expand the brim outward — it is a corner-cutting operation applied to the tower polygon. The brim expansion is governed by `prime_tower_brim_width` (3mm) and loop spacing (~0.36mm). With Y=231 (new clamped value):

```
Max Y extent = 231 + depth(~20mm) + brim_loops * spacing(~0.36mm) ≈ 254mm
```

**Well within the 270mm bed boundary.** Chamfer is not a geometry overflow risk at Y=231.

---

## Static Variable Audit — Clean

- `sapil_print.cpp`: `Slic3r::Print print;` created fresh on stack each call. No mutable file-scope globals (only `static const profile_keys[]`).
- `ClipperUtils.cpp`: Only debug-only `static int iRun` inside `#ifdef`. Clean in release.
- `Brim.cpp`: Only debug-only `static int irun` inside `#ifdef`. Clean in release.
- `WipeTower2.cpp`: No mutable statics. All `static` items are `const` values.

The "stale state" hypothesis via static variables is **not supported**. The persistent state is the `static Slic3r::Model g_model` in `sapil_model.cpp`, which is properly cleared via `clearModel()`.

---

## Recommendations for Next Native Rebuild

These are the remaining uninitialised members not addressed by v1.5.0. Add to Print.hpp before the next rebuild:

```cpp
// In PrintObject class:
double m_max_z = 0.0;
size_t m_id = 0;
Point m_center_offset = Point(0, 0);
Vec3crd m_size = Vec3crd::Zero();

// In PrintInstance struct:
size_t id = 0;
size_t unique_id = 0;
```

Also add to the `profile_keys[]` whitelist in `sapil_print.cpp`:
```c
"prime_tower_brim_chamfer",
"prime_tower_brim_chamfer_max_width",
```

**A native rebuild is NOT urgently required** — the current prebuilt `.so` has all the critical fixes and v1.5.26 closes the Y=240 Kotlin-side path. Rebuild when other C++ changes are needed; include the above at that time.

---

## Unknown: 3DBenchy H2C Failure Mechanism

The 3DBenchy H2C failure (pid=6025, v1.5.25) had `clipper_failure` with **no** `clipper_coordinate_out_of_range` diagnostic event. This means either:
1. The coordinate diagnostic was not yet instrumented for that geometry path in v1.5.25, or
2. The failure occurred before the coordinate capture point

Tower geometry: X=230, Y=10, width=30 — right edge = 230+15+3(brim) = 248mm. This is within bounds. The failure mechanism for the 3DBenchy H2C remains unknown. If it recurs on v1.5.26, capture a fresh log — the wipe_tower_y_checkpoint diagnostics should now show the clamped values.
