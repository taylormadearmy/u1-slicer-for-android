reviewer 1

Verification Results (df1817e..d371e31)
1) Multi-digit T parsing bug (GcodeParser)
Status: ✅ fixed in diff, ✅ structurally correct, ⚠️ low residual risk

(a) Fix exists: yes, parser changed from cmdLen == 2 (T0..T9) to digit-scanning for cmdLen >= 2, so T10, T11, etc. are now parsed.
(b) Structural correctness: good. It parses contiguous digits and preserves existing 0..31 safety cap.
(c) Regression check: good coverage was added with targeted tests for T10/T11 and T15.
Residual risk: it still accepts any confirmedMapping values later without clamping (separate path), but this parser fix itself is solid.

GcodeParser.kt
Lines 257-269

if (c0 == 'T' && cmdLen >= 2 && l[start + 1] in '0'..'9') {
    var raw = 0
    var i = start + 1
    val end = start + cmdLen
    while (i < end && l[i] in '0'..'9') {
        raw = raw * 10 + (l[i] - '0')
        i++
    }
    currentExtruder = raw.coerceIn(0, 31)
    ensureExtruderCapacity(currentExtruder)
}
2) Send/upload canonical leak via fallback path
Status: ✅ fixed in diff, ✅ mostly structurally correct, ⚠️ one important fallback assumption remains

(a) Fix exists: yes. They replaced nullable canonical lookup with explicit 3-state (Loading/Present/Absent) and removed the eager “null means send raw now” race.
(b) Structural correctness: mostly good:
Loading does nothing (prevents race-send).
Present path remaps and passes typed PhysicalGcodePath.
Send APIs now only accept PhysicalGcodePath.
(c) Regression check: better than before, but there is still a soft leak risk in Absent path:
It copies source unchanged (mapping = null) then wraps as PhysicalGcodePath.
If canonical detection ever fails for a true canonical file, canonical-space T can still be treated as physical.

MainActivity.kt
Lines 748-771
is CanonicalLookup.Absent -> {
    ...
    viewModel.prepareExportableGcodeWithMapping(
        sourceFile, exportedFile, mapping = null
    )
    val physical = com.u1.slicer.gcode.PhysicalGcodePath.of(exportedFile)
    ...
}
So this is much safer than before, but not mathematically impossible to misclassify under lookup failure.

3) is_snapmaker_profile heuristic brittleness (PRINT_START)
Status: ❌ not actually fixed in this range, ⚠️ mitigation scaffolding only, ⚠️ risk remains

(a) Fix exists in diff? not at runtime boundary.
The native check still uses substring find("PRINT_START").
(b) Structural correctness: not yet corrected. They added comments plus a Kotlin marker (snapmaker_authored_profile = "1"), but native does not consume that marker yet.
(c) Regression introduced? no direct new regression seen, but original heuristic risk persists until native schema/read path is implemented.

sapil_print.cpp
Lines 550-555
bool is_snapmaker_profile = false;
if (!model_config.empty()) {
    auto* start_opt = model_config.option<Slic3r::ConfigOptionString>("machine_start_gcode");
    if (start_opt && start_opt->value.find("PRINT_START") != std::string::npos) {
        is_snapmaker_profile = true;
    }
}
B.1 Value-Class Pivot (print boundary) — Correct and Exhaustive?
Short answer: At direct printer send boundary, yes; across full export boundary, not fully airtight yet.

✅ Correct at direct printer boundary:
PrinterViewModel.sendAndPrint / sendUploadOnly now require PhysicalGcodePath, and all callsites pass typed physical outputs.
✅ Good enforcement surface:
compile-time type distinction prevents obvious accidental raw-string send.
⚠️ Not exhaustive as a full safety proof:
PhysicalGcodePath.of(file) is public and can wrap any file without provenance (intentional but bypassable).
Absent canonical branch in MainActivity force-wraps copied file as physical.
Save/Share/jobs export helpers still operate on raw File/String and rely on runtime mapping logic, not value-class type barriers.
So B.1 is a strong improvement and closes the original direct call-site leaks, but it is not yet a complete end-to-end proof system for canonical→physical correctness under all failure/misclassification cases.

Net Assessment
Finding #1: properly fixed.
Finding #2: fixed for the reported race/leak class, with one remaining misclassification fallback caveat.
Finding #3: still open (runtime fix deferred).
B.1 pivot: good and meaningful, but not fully exhaustive beyond direct send APIs.
If you want, I can do a next pass specifically for “remaining bypasses after B.1” and give a tight hardening checklist (smallest set of code changes to make canonical leaks structurally impossible end-to-end).


Remaining Bypasses After B.1
CanonicalLookup.Absent path can still relabel unknown output as physical
In MainActivity, absent canonical list does identity copy then wraps as PhysicalGcodePath; if canonical detection is wrong, raw canonical T can leak.

PhysicalGcodePath.of(...) is an unchecked constructor
Any caller can cast arbitrary file paths to physical without remap proof; this is convenient but weakens “structural impossibility.”

Export layer remains mostly untyped (File/String)
prepareExportableGcodeWithMapping works on raw files; type safety only resumes at final send calls. This leaves more surface for future regressions.

resolveCanonicalExportMapping doesn’t clamp mapping values to 0..3
It clamps only selectedExtruder path. If malformed mapping enters state/DB, remap can emit out-of-range tools.

Minimal Hardening Checklist
Force remap-or-fail in CanonicalLookup.Absent for multi-tool G-code
Detect T[4-9]|T\d{2,} in source and block send unless mapping resolution succeeds.

Make PhysicalGcodePath construction internal/factory-guarded
Keep of(...) restricted and expose factories like fromRemapResult(...) or fromVerifiedPhysical(...).

Clamp mapping values in resolver/remapper
Normalize confirmedMapping entries with coerceIn(0, 3) before building tool map.

Type the export helper boundary
E.g. prepareExportableGcode(canonical: CanonicalGcodePath, ...) -> PhysicalGcodePath; have a separate explicit legacy path.

Add one red test for Absent-misclassification
Simulate canonical-source + forced absent lookup, assert printer-bound file never contains out-of-range T.

If you want, I can turn this into a concrete PR-sized patch plan (smallest diff first, then stronger refactor).



reviewer 2 


Verification Summary

Finding 1 Send race: fixed. Three-state CanonicalLookup prevents initial-null from sending raw canonical G-code, and printer calls now receive PhysicalGcodePath.
Finding 2 Save/Share: partially fixed. It no longer leaves high T commands unchanged, but can remap them to the wrong slot.
Finding 3 Jobs share: partially fixed. Multi-color metadata helps, but single-color selected-slot jobs still regress.
Finding 4 cache leak: mostly fixed for getCanonicalFilamentList() and load entry points. Minor residual: async refresh/selectPlate still write/read _canonicalFilamentList without consistently maintaining canonicalCacheSourcePath.
Finding 5 paint-only colours: fixed structurally; it now probes every volume and promotes hasPaintData.
Finding 6 multi-digit T parser: fixed; parser now reads multi-digit T indices.
Finding 7 sparse non-MMU preview: partially fixed; simple sparse objects are covered, compound per-volume cases are not.
Finding 8 G-code preview >4 colours: not fixed end-to-end because renderer storage remains four-wide.
B.1 value-class pivot: good at the immediate PrinterViewModel boundary: current send call sites cannot pass raw String paths to sendAndPrint/sendUploadOnly. It is not exhaustive as a whole print/export boundary: PhysicalGcodePath.of(file) can still wrap any file, PrinterRepository remains raw File, and Save/Share/Jobs are outside the typed send boundary.

Focused tests passed:
.\gradlew.bat testDebugUnitTest --tests com.u1.slicer.gcode.CanonicalExportMappingTest --tests com.u1.slicer.gcode.GcodeParserTest --tests com.u1.slicer.PreviewColorNormalizationTest

reviewer 3


P1
Export mapping still invents slots for unknown canonical indices
Dismiss
This fixes the out-of-range T leak, but not the wrong-index-space bug. When _colorMapping is plate-narrowed, the resolver has no knowledge of which canonical file indices those entries correspond to, so it treats them as mappings for fileIndex 0, 1, ... and fills all remaining canonical indices with i % 4. A plate that emits T9 can now be remapped to a valid but wrong physical slot. Save/Share need either the full canonical mapping or a mapping keyed by canonical fileIndex, not a positional expansion of a narrowed UI list.


C:/Users/kevin/projects/u1-slicer-orca/.worktrees/phase2-canonical/app/src/main/java/com/u1/slicer/gcode/PrintTimeRemap.kt:60-66
P1
Job share still loses single-color selected slot
Dismiss
The new job metadata records canonicalListSize and _colorMapping, but single-color jobs intentionally have _colorMapping == null; their selected E2/E3/E4 slot only lives in _selectedExtruder and is not persisted. On share, decodedColorMapping(job) is null and the fallback maps a one-filament canonical job to [0], so a single-color job sliced for E3 is shared as T0/E1.


C:/Users/kevin/projects/u1-slicer-orca/.worktrees/phase2-canonical/app/src/main/java/com/u1/slicer/SlicerViewModel.kt:3477-3480
P2
G-code preview remains capped at four renderer colours
Dismiss
normalizeGcodePreviewColors() now returns a canonical-length palette, but GcodeRenderer still stores exactly four extruderColors entries and setExtruderColors() drops every palette entry with index >= 4. GcodeSegmentPacker receives this fixed four-entry array, so T4/T9/T10 still clamp to the last colour. Finding 8 is therefore not structurally fixed despite the MainActivity change.


C:/Users/kevin/projects/u1-slicer-orca/.worktrees/phase2-canonical/app/src/main/java/com/u1/slicer/viewer/GcodeRenderer.kt:76-101
P2
Sparse preview fix misses compound per-volume extruders
Dismiss
For non-MMU meshes, native compacts by all emitted per-volume extruder indices, but this palette derives the compaction order from objectExtruderMap.values. NativePlateState.buildObjectExtruderMap() collapses a compound object to one value, so a non-MMU object with volumes on filaments 3 and 4 can still produce mesh indices 0/1 while this palette only includes the max/default object extruder. Use usedExtruderIndices or the full native volume extruder set for the non-MMU compaction order.


C:/Users/kevin/projects/u1-slicer-orca/.worktrees/phase2-canonical/app/src/main/java/com/u1/slicer/SlicerViewModel.kt:612-619
Verification Summary

Finding 1 Send race: fixed. Three-state CanonicalLookup prevents initial-null from sending raw canonical G-code, and printer calls now receive PhysicalGcodePath.
Finding 2 Save/Share: partially fixed. It no longer leaves high T commands unchanged, but can remap them to the wrong slot.
Finding 3 Jobs share: partially fixed. Multi-color metadata helps, but single-color selected-slot jobs still regress.
Finding 4 cache leak: mostly fixed for getCanonicalFilamentList() and load entry points. Minor residual: async refresh/selectPlate still write/read _canonicalFilamentList without consistently maintaining canonicalCacheSourcePath.
Finding 5 paint-only colours: fixed structurally; it now probes every volume and promotes hasPaintData.
Finding 6 multi-digit T parser: fixed; parser now reads multi-digit T indices.
Finding 7 sparse non-MMU preview: partially fixed; simple sparse objects are covered, compound per-volume cases are not.
Finding 8 G-code preview >4 colours: not fixed end-to-end because renderer storage remains four-wide.
B.1 value-class pivot: good at the immediate PrinterViewModel boundary: current send call sites cannot pass raw String paths to sendAndPrint/sendUploadOnly. It is not exhaustive as a whole print/export boundary: PhysicalGcodePath.of(file) can still wrap any file, PrinterRepository remains raw File, and Save/Share/Jobs are outside the typed send boundary.

Focused tests passed:
.\gradlew.bat testDebugUnitTest --tests com.u1.slicer.gcode.CanonicalExportMappingTest --tests com.u1.slicer.gcode.GcodeParserTest --tests com.u1.slicer.PreviewColorNormalizationTest