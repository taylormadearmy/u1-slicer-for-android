# Phase 1 Sub-Plan #2 — Per-Plate `PlateData` Design Notes

**Date:** 2026-04-23

## Executive Summary: Recommendation (Option A)

Sub-plan #2 should remain SNAPSHOT-ONLY. Populate KotlinBambuSnapshot.plates from new JNI accessor nativeGetPlateData(plateIndex). Closes 115 of 150 remaining baseline entries with zero slice-time risk. Production code (BambuSanitizer.extractPlate) untouched.

**Rationale:** Slice-time extraction is complex. Migrating it requires new native entry point slice_plate_N in sapil_print.cpp — non-trivial, blocks other work. Snapshot diff harness proves correctness; production migration deferred to sub-plan #2b.

---

## Baseline Re-Count (Post-Sub-Plan #5)

**Current:** 150 entries across 21 fixtures.

| Path Category                        | Count | Notes |
|--------------------------------------|-------|-------|
| plates[*].plateIndex                 |    54 | 0-based, currently missing (fabricated) |
| plates[*].objectInstanceMap          |    54 | Missing instanceId; Kotlin fabricates 0 |
| plates[*].filamentSettingsIds.size   |     4 | Empty in Kotlin; native fallback→project |
| plates[*].plateConfig[...]           |     2 | Kotlin leaves empty |
| objects.size                         |    20 | ObjectID identity (sub-plan #4) |
| plates[*].customGcode[0]/[1]         |    11 | Type name normalisation (sub-plan #3) |
| plates.size                          |     1 | Fixture-specific gap |
| **Sub-plan #2 target**               | **115** | plateIndex(54) + objectInstanceMap(54) + filamentSettingsIds(4) + plateConfig(2) + plates.size(1) |

---

## Per-Field Dataflow (Kotlin → Native)

### PlateSnapshot.plateIndex
- Kotlin: XML-extracted, 0-based (ThreeMfParser)
- Native: p.plate_index, 0-based (sapil_bambu_snapshot.cpp:123)
- Decision: 0-based at JNI boundary; no translation needed

### PlateSnapshot.objectInstanceMap
- Kotlin: plate.objectIds with instanceId=0 (fabricated)
- Native: PlateData::objects_and_instances pairs
- Risk: ObjectID identity mismatch (sub-plan #4); accept as known for snapshot-only

### PlateSnapshot.filamentSettingsIds
- Kotlin (post-#5): Project config fallback
- Native: slice_filaments_info override or project fallback
- 4 baseline disagreements resolve when native per-plate override is read

### PlateSnapshot.plateConfig
- Kotlin: Leaves empty
- Native: PlateData::config stringified (opt_serialize)
- Only 2 baseline entries; low risk

---

## Production Call-Site Inventory

| Consumer | Caller | Slice-Critical | Scope for #2 |
|----------|--------|---|---|
| BambuSanitizer.extractPlate (1519-1644) | SlicerViewModel.selectPlate | HIGHEST | UNTOUCHED (Option A) |
| ThreeMfParser.parseForPlateSelection (423-547) | SlicerViewModel.selectPlate | HIGH | UNTOUCHED (Option A) |
| SlicerViewModel.mergeThreeMfInfoForPlate (3379-3540) | SlicerViewModel.selectPlate | HIGH | UNTOUCHED (Option A) |

Risk mitigation: extractPlate remains Kotlin-driven in Option A. Zero slice-time risk.

---

## JNI Accessor Shape (Option A — Recommended)

```kotlin
external fun nativeGetPlateData(plateIndex: Int): String?
external fun nativeGetPlateCount(): Int
```

C++ sketch: New TU sapil_bambu_plate.cpp. Promote append_plate to namespace sapil (like sub-plan #5). Loop through g_plate_data_list, find plate at index, call append_plate, return JSON.

---

## Migration Strategy

### Option A: Snapshot-Only (RECOMMENDED)

- New JNI: nativeGetPlateData(plateIndex), nativeGetPlateCount()
- New C++ TU: sapil_bambu_plate.cpp
- Kotlin: KotlinBambuSnapshot.snapshot calls accessor
- Production: extractPlate, parseForPlateSelection, merge UNTOUCHED

Closure: 117 entries. Risk: MINIMAL. Timeline: 1-2 days.

### Option B: Full Slice-Time Migration

Option A + native slice_plate(int plate_index) in sapil_print.cpp.

Closure: Same 117 entries. Risk: HIGH (Print::apply complexity). Timeline: 5-7 days.

---

## Tests to Touch (Option A)

New:
- NativePlateDataTest.kt — smoke tests on Flarewing-Dragon, colored_3DBenchy, Dragon Scale

Modified:
- KotlinBambuSnapshotTest.kt — update assertions for non-empty fields
- BambuParserDifferentialTest.kt — baseline 150 → ~33 entries

Untouched (production not changed):
- MergeThreeMfInfoTest, BambuPipelineIntegrationTest, SemmSlicingTest, ProfileEmbedderIntegrationTest

---

## Risks & Mitigations

| Risk | Severity | Mitigation |
|------|----------|-----------|
| PlateIndex 0/1-based | RESOLVED | Both sides 0-based; no translation |
| ObjectID identity | DEFERRED | Accept mismatch for #2; sub-plan #4 resolves |
| PlateConfig stringification | LOW | Native already stringifies; 2 baseline entries |
| Slice-time breakage | MITIGATED | extractPlate UNTOUCHED in Option A |
| FilamentColours RGBA/RGB (3 entries) | RESIDUAL | Fall out when per-plate colours normalize |
| Plates.size mismatch (1 entry) | TBD | Resolves when nativeGetPlateCount is source of truth |

---

## TL;DR Implementation

1. Promote append_plate to namespace sapil (like sub-plan #5)
2. Create sapil_bambu_plate.cpp with JNI entries
3. Declare extern funs in NativeLibrary.kt
4. Update KotlinBambuSnapshot.snapshot() to call accessor, parse JSON, populate PlateSnapshot
5. Add NativePlateDataTest.kt smoke tests
6. Delete 117 baseline entries
7. Rebuild native (one TU + header scope): 2-15 min incremental
8. Run diff suite — baseline 150 → 33 entries

---

## Expected Outcome

- Closed by #2: 115 entries
- Remaining after #2: 35 entries (customGcode 11 + objects.size 20 + residual 4)
- Final Phase 1 closure: 664 → 2 entries (>99% resolved)
