# Performance Investigation Plan

## Purpose

Improve user-visible performance in U1 Slicer for Android without weakening stability, colour/material accuracy, Bambu settings fidelity, or device safety.

The first native TBB PoC did not establish a safe, measurable win. Future performance work should be profiling-led, staged, and judged by broad automated coverage plus manual UI-first E2E, rather than by isolated microbenchmarks or one troublesome fixture.

## Current Findings

The `NATIVE_PERF_PLAN.md` broad real-TBB approach is high risk as currently framed.

Observed during the native performance PoC:

- Real TBB plus serial `PrintObject::process_external_surfaces()` still stalled on the legacy Shashibo harness, but that harness is no longer a reliable decision gate and should not be used as the basis for accepting or rejecting performance work.
- Removing allocator wrapping did not fix the stall.
- Static `oneapi::tbb::global_control` crashed during native library setup.
- Lazy `global_control` avoided the setup crash, but the PoC did not complete a reliable broad automated/manual comparison that proved a user-visible win.
- The repeated device symptom was `Waiting for a blocking GC NativeAlloc`, indicating native allocation pressure or allocator/task behaviour rather than simply too many worker threads.

Conclusion: do not merge a broad switch from the serial TBB shim to real TBB on the PoC evidence alone. The PoC remains unproven, not disproven; future evaluation must use the current testing guidance in `E2E_TESTING.md`.

### Pre-Move PoC Evidence

The original pre-move PoC in `C:\Users\kevin\projects\u1-slicer-orca\.worktrees\native-perf-poc` on branch `codex/native-perf-poc` did show measurable upside before the project move disrupted the worktree setup.

That run used the allocator shim, real TBB in place of the global `tbb_serial` include, and a serialized `PrintObject::process_external_surfaces()` loop. Reported results from the Codex session `Prototype native perf plan` on 2026-04-30 / 2026-05-01:

- Native build: NDK 26 / Clang 17.0.2, stripped Release `.so`, `20,991,736` bytes.
- `testDebugUnitTest`: 873 tests passed.
- Full `connectedDebugAndroidTest` on Pixel 8a `43211JEKB16931`: 200 tests passed.
- Earlier high-risk colour/stability batch: 77 tests passed.
- Button trousers 3MF: `64.664s -> 53.424s` (`1.21x`).
- Flarewing Dragon SEMM: `174.935s -> 163.675s` (`1.07x`).
- H2C full pipeline: `88.057s -> 73.124s` (`1.20x`).
- H2C SEMM all tools: `65.321s -> 57.130s` (`1.14x`).
- Aggregate mini-benchmark: `392.977s -> 347.353s`, about `13.1%` faster.

Interpretation: the performance upside is real enough to justify a fresh investigation. The later post-move PoC did not invalidate these results; it failed to reproduce a clean, comparable benchmark because the project move/submodule recovery changed the setup and the Shashibo harness was over-weighted.

## Guiding Principles

1. Optimise user-visible waits first.
2. Measure before changing code.
3. Prefer narrow changes with clear rollback paths.
4. Keep the existing serial TBB shim unless a specific hotspot proves safe with real parallelism.
5. Use the current `E2E_TESTING.md` strategy: broad automated tests plus UI-first manual E2E on representative files.
6. Rebuild and verify native binaries after every native source change.
7. Do not trade colour, filament, plate, object, or settings accuracy for speed.

## Highest-Value Areas

### 1. Large Model Loading

This is likely the best return on effort. Long model-load waits affect the user before slicing begins and can make the app feel frozen.

Investigate:

- 3MF zip extraction and file-copy costs.
- Bambu `*.3mf` parsing, XML traversal, and metadata extraction.
- Native `Model::read_from_file` time by stage.
- Plate filtering and whether all plates/objects are being parsed when only one plate is needed.
- Repeated parse/embed/load work between preview, plate state, and slicing.
- Kotlin-side JSON parsing and conversion overhead.
- Disk I/O in cache/out directories.

Possible improvements:

- Add stage timing around Kotlin parse, profile embedding, sanitizer, native load, native plate/object accessors, and preview mesh generation.
- Avoid writing intermediate files when a no-op or cache hit is possible.
- Cache parsed Bambu metadata keyed by file path, size, and modified time.
- Cache embedded profile outputs when source file and selected profile parameters are unchanged.
- Use plate-aware native load paths consistently for multi-plate files.
- Skip full native reloads when only reading already-loaded metadata.
- Reduce repeated JSON serialization/deserialization across JNI where direct packed arrays or smaller JSON payloads would work.

Success target:

- Aim for 20-40% reduction in time-to-loaded-state on the largest approved fixtures without changing native state snapshots or slicer output.

### 2. Preview Readiness

Preview latency strongly affects perceived performance. A faster first useful preview may matter more than total slicing time.

Investigate:

- Time from file selection to first model visible.
- Time spent generating prepare preview mesh.
- QEM decimation cost and cancellation behaviour.
- Whether preview work is repeated after load, scale, rotate, or plate selection.
- Main-thread blocking and coroutine scheduling around native calls.

Possible improvements:

- Emit coarse preview first, then refine.
- Cache preview mesh for unchanged model transform and plate selection.
- Tighten cancellation checks so abandoned previews stop quickly.
- Lower initial triangle budgets for very large models, then upgrade in background.
- Avoid preview regeneration when only non-geometry settings changed.

Success target:

- Reduce time to first usable preview by 30% on large fixtures, even if final high-detail preview still takes longer.

### 3. Bambu Metadata, Colour, And Settings Accuracy Path

This path is both performance-sensitive and correctness-sensitive. It should be optimised only with snapshot comparisons in place.

Investigate:

- `ThreeMfParser`, `ProfileEmbedder`, `BambuSanitizer`, `NativePlateState`, and related native accessors.
- Repeated extraction of filament colours, filament settings IDs, object-instance maps, custom G-code, and volume paint states.
- Large JSON payload creation/parsing across JNI.

Possible improvements:

- Cache immutable project-level metadata after first parse.
- Replace repeated whole-model JSON dumps with focused accessors for hot UI paths.
- Batch native accessors when Kotlin currently loops object-by-object or volume-by-volume.
- Avoid reparsing source config during operations that do not change profiles.

Correctness gates:

- Filament colours match exactly.
- Filament settings IDs and filament IDs match exactly.
- Plate count and selected plate mapping match exactly.
- Object extruder and volume extruder maps match exactly.
- Paint/seam/support state counts match existing approved fixture baselines.

### 4. Slicing

Slicing is still important, but the broad TBB route has shown high stability risk. Future slicing work should focus on narrower bottlenecks.

Investigate:

- Stage timings inside native slicing: arrange, slicing, surfaces, infill, support, path generation, G-code export.
- Allocation-heavy loops in large multi-colour fixtures.
- Whether specific serialised loops are safe to optimise locally without restoring all real TBB algorithms.
- Repeated config construction and profile application before each slice.

Possible improvements:

- Reduce allocations in known hot loops.
- Reuse temporary buffers where lifetime is clear.
- Avoid recomputing geometry or config state that is unchanged.
- Consider targeted, audited parallelism only for isolated read-only loops.
- Add cancellation checkpoints to long phases so failed experiments do not look like app hangs.

Do not do yet:

- Do not broadly remove `tbb_serial`.
- Do not rely on `global_control` as a fix for the real-TBB regression.
- Do not merge allocator wrapping without a focused proof and fixture coverage.

## Measurement Plan

### Fixtures

Use a fixed fixture set with a mix of small, medium, large, Bambu, multi-plate, and multi-colour models.

Required validation set:

- `testDebugUnitTest` and `connectedDebugAndroidTest` automated baselines.
- AI-assisted/manual UI-first E2E over `app/src/androidTest/assets/`, following `E2E_TESTING.md`.
- Button-for-S-trousers: multi-extruder/tool-change canary.
- Bambu plate state regression fixtures: colour/settings/plate-state canaries.
- SEMM slicing fixtures: multi-material slicing canaries.

For every candidate improvement, record:

- Device model and serial.
- Git branch and commit or dirty diff summary.
- Native binary size.
- Native compiler `.comment` section.
- JNI exported symbol count versus Kotlin external declaration count.
- Cold and warm timings.
- Peak Java heap if available.
- Native RSS or meminfo snapshots if available.
- Pass/fail result for canaries.

### Timing Instrumentation

Add coarse timing first. Prefer low-overhead logging that can remain behind a debug flag.

Suggested timing points:

- File selected.
- Kotlin metadata parse start/end.
- Bambu sanitizer start/end.
- Profile embed start/end.
- Native `loadModel` start/end.
- Native project config read start/end.
- Native plate/object/volume state read start/end.
- Preview mesh start/end.
- Slice start/end by native stage.
- G-code export start/end.

Use consistent log tags so data can be scraped from logcat.

### Benchmark Shape

For each fixture and operation:

- Run at least 3 cold runs after app restart.
- Run at least 5 warm runs where caching is expected to help.
- Use median and worst-case, not just best-case.
- Keep the phone awake and plugged in.
- Avoid running on personal/non-phone devices.
- Use the approved Pixel 8a test device when comparing to prior PoC data.

## Implementation Workflow

1. Create a fresh worktree and branch for one performance hypothesis.
2. Add or enable timing instrumentation only if missing.
3. Capture baseline timings on main for the target fixtures.
4. Implement one narrow change.
5. Rebuild native if native source or bundled `.so` changed.
6. Verify native binary metadata and JNI symbol count.
7. Run focused correctness tests.
8. Run mandatory canaries.
9. Compare timings and memory behaviour.
10. Keep the change only if it passes correctness and produces a meaningful user-visible gain.

## Merge Gates

A performance PR should not be considered mergeable unless it includes:

- Before/after timings for the affected workflow.
- Clear fixture list and device used.
- Correctness tests for affected colour/settings/plate/object behaviour.
- Manual/UI-first E2E comparison for touched workflows, especially large model load, preview, colour/settings, and slicing output.
- Explanation of any changed native binary.
- Rollback plan if field regressions appear.

Suggested minimum bar:

- 15% improvement for a narrow internal operation, or
- 20-30% improvement for a user-visible wait, or
- a smaller improvement if it also reduces memory pressure or removes repeated work.

## Candidate Investigation Order

1. Add timing instrumentation for load/preview/slice stages.
2. Baseline large-model load times on main.
3. Identify whether time is dominated by Kotlin parse/embed/sanitize, native load, preview mesh, or JNI state reads.
4. Optimise the largest load-time bucket first.
5. Add caching only after measuring repeated work.
6. Optimise preview readiness separately from final preview quality.
7. Revisit slicing only with stage-level timing and the current automated/manual E2E evidence.
8. Reconsider targeted TBB only if a specific read-only hotspot is isolated and passes the broad automated suite plus manual UI-first E2E.

## Open Questions

- Which user workflow currently feels worst: initial file open, plate switch, preview generation, first slice, repeated slice after settings changes, or G-code export?
- Are users more sensitive to first visible preview or total time until slicing can start?
- Which large fixtures are most representative of real user files?
- Can native load accept in-memory or pre-filtered data, or are disk intermediates unavoidable?
- Which metadata can be safely cached across profile/settings changes?

## Recommended Next Step

Start with a load-time profiling branch, not another broad native parallelism branch.

The first concrete milestone should be a small timing harness/report that answers: for the slowest large model load, where does the time go?

Only after that should we choose an implementation target.