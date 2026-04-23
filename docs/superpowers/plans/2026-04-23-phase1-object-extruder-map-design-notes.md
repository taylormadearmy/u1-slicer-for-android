# Phase 1 sub-plan #4 — Object extruder map via JNI accessor (design notes)

**Date:** 2026-04-23
**Scope:** Pre-flight research for exposing `objectExtruderMap` via a new JNI accessor backed by `g_model.objects`, letting `KotlinBambuSnapshot` read object-to-extruder assignments from native instead of re-parsing XML.
**Status:** Research only. No code touched. Based on commit 29eff10 (post sub-plans #5 / #2).

---

## 1. Baseline re-count: All 20 `objects.size` entries confirmed

**Distribution across 20 fixtures:** (verified via grep on known-disagreements.json)

All 20 entries have reason "Kotlin gap - Phase 1 closes by deletion" and path "objects.size". No content diffs (objects[*] entries) exist. Confirms list-length disagreement only.

Affected fixtures: SENSORY+TWIST+BALL, skywing-seawing, Dragon Scale, slip slide, u1-auxiliary, flippy+flappy, Shashibo, Flarewing-Dragon (2), colored_3DBenchy (2), Bench&Sea, nanoblock, multi-color (2), Anycubic, Dragonbones, Baby-Groot, Honey-Badger.

**No content diffs.** Roadmap's "22 entries" conflates Phase 0 initial count with post-#2 reality; sub-plan #2 already resolved per-object content diffs.

---

## 2. Root cause: Kotlin filters on vertex count; native does not

### Kotlin path (ThreeMfParser.kt:561-605)

Parses XML resources section for <object> elements, counts inline <vertex> elements. **Adds object to result only if vertexCount > 0** (line 591).

Filters out component-reference objects (e.g., "leg_A.model") which have no inline vertices in the 3MF XML.

### Native path (sapil_bambu_snapshot.cpp:346-349)

Walks g_model.objects and emits every object, regardless of vertex count. Slic3r's Model::read_from_file already merged component references by load time, so all objects exist in g_model.

### Example

Multi-part assembly (colored_3DBenchy). Kotlin counts inline objects only (N). Native counts after merge (M >= N). Result: Kotlin reports N objects, native reports M.

---

## 3. ObjectID identity: Kotlin XML strings vs. native runtime size_t

### Kotlin
`ThreeMfInfo.objectExtruderMap: Map<String, Int>` keyed by XML id attribute ("1", "leg_A", etc.). Built at lines 1023-1108 via XML metadata parsing.

### Native
`ModelObject::id().id` is a process-local size_t runtime ID assigned by Slic3r's Model::add_object(). Example: 4294967310. Meaningless outside this session.

### Mapping problem
No direct link from runtime ID back to XML id. `ModelObject::input_file` contains component source path (not XML id). No `xml_object_id` field exists in codebase. **Cannot map runtime ObjectID → XML id deterministically without re-parsing the ZIP.**

**Conclusion:** This is a snapshot-level mismatch, not a production blocker (see Section 5).

---

## 4. JNI accessor shape: Option A recommended

```kotlin
external fun nativeGetObjectExtruderMap(): String?
// Returns JSON: [{"objectId": <runtime-id>, "name": "...", "extruder": <0-based>}]
```

C++ sketch (reuses existing append_object helper):

```cpp
std::string object_extruder_map_json() {
    std::ostringstream out;
    out << "[";
    for (size_t i = 0; i < g_model.objects.size(); ++i) {
        if (i) out << ",";
        append_object(out, *g_model.objects[i]);
    }
    out << "]";
    return out.str();
}
```

**Why Option A:** Reuses Phase 0's append_object. JSON shape already proven. Kotlin just iterates array → no new parser. Snapshot-only, zero production risk. Sub-plan #2 and #5 follow same pattern.

---

## 5. Production code stays 100% untouched

Production consumers of objectExtruderMap (SlicerViewModel, ProfileEmbedder, BambuSanitizer) all read `origInfo.objectExtruderMap: Map<String, Int>` (keyed by XML id strings). Sub-plan #4 populates **only** `KotlinBambuSnapshot.objects` — a separate list, diff-harness-only, not read by production.

All slice-time code remains on XML id path. No migration needed.

---

## 6. Plate-scoped overrides

Native PlateData tracks plate-specific filament_maps that override per-object extruder. Sub-plan #2 exposes this via nativeGetPlateData(plateIndex) → objectInstanceMap. Production handles this in native's Print::apply, not exposed to Kotlin. Acceptable: no JNI bridge needed.

---

## 7. Tests and baseline

- **Add:** NativeObjectExtruderAccessorTest.kt (instrumented) on sample fixtures.
- **Update:** KotlinBambuSnapshotTest.kt assertions on non-empty objects list.
- **Prune:** 20 entries from known-disagreements.json.

---

## TL;DR Implementation (Option A)

1. Add `external fun nativeGetObjectExtruderMap(): String?` to NativeLibrary.kt.
2. C++ body (sapil_bambu_snapshot.cpp): loop g_model.objects, call append_object (already exists), return JSON.
3. Kotlin: parse JSON array, populate BambuFileSnapshot.objects in snapshot().
4. Add smoke test NativeObjectExtruderAccessorTest.kt.
5. Prune 20 baseline entries.
6. Native rebuild: ~2-5 min.

**Outcome:** 664 → 24 baseline entries. Ready for sub-plan #3.

**Reasoning:** ObjectID identity mismatch is intentional at snapshot level. Both runtime ID and XML string representations are correct for their respective scopes. Production code never reads the snapshot objects list, so no slice-time risk.
