# Native Performance Pre-Move PoC Preservation Note

## Why This Exists

The original native performance PoC was created before the repository move/copy and was left as uncommitted work inside a linked worktree. The worktree git metadata is now broken because it points back to the old parent git directory, but the source files and rebuilt native binary still exist on disk.

This note preserves the useful evidence and code shape so the work is not dependent on Codex session history or a fragile local worktree.

## Original Locations

- Original worktree path from the Codex session: `C:\Users\kevin\projects\u1-slicer-orca\.worktrees\native-perf-poc`
- Copied old worktree path that still contains the files: `C:\Users\kevin\old-projects-to-delete\u1-slicer-orca\.worktrees\native-perf-poc`
- Session title: `Prototype native perf plan`
- Session date: 2026-04-30 / 2026-05-01
- Session file: `C:\Users\kevin\.codex\sessions\2026\04\30\rollout-2026-04-30T23-37-42-019de08a-700e-73a3-8111-0d29da9295e1.jsonl`

Important caveat: the local branch name `codex/native-perf-poc` exists in the moved repository, but it does not contain the native PoC code. The native PoC code was dirty, uncommitted worktree state. Do not rely on that branch as the source of truth.

## Preserved Test And Benchmark Evidence

The pre-move PoC reported:

- Native build: NDK 26 / Clang 17.0.2, stripped Release `.so`, `20,991,736` bytes.
- `testDebugUnitTest`: 873 tests passed.
- Full `connectedDebugAndroidTest` on Pixel 8a `43211JEKB16931`: 200 tests passed.
- Earlier high-risk colour/stability batch: 77 tests passed.
- Button trousers 3MF: `64.664s -> 53.424s` (`1.21x`).
- Flarewing Dragon SEMM: `174.935s -> 163.675s` (`1.07x`).
- H2C full pipeline: `88.057s -> 73.124s` (`1.20x`).
- H2C SEMM all tools: `65.321s -> 57.130s` (`1.14x`).
- Aggregate mini-benchmark: `392.977s -> 347.353s`, about `13.1%` faster.

Interpretation: two representative cases were about 20% faster, and the aggregate was about 13.1% faster. The later post-move Shashibo harness problem should not be treated as disproving these results.

## Preserved Code Shape

### CMake TBB Section

The PoC replaced the project-wide `tbb_serial` include with real TBB headers/libs and wrapped TBB scalable allocator entry points to Android libc:

```cmake
# --- TBB ---
# POC: use real TBB parallel algorithms and wrap TBB scalable_allocator entry
# points to Android libc. Known unsafe geometry growth is serialized locally.
add_link_options(
    -Wl,--wrap=scalable_malloc
    -Wl,--wrap=scalable_free
    -Wl,--wrap=scalable_aligned_malloc
    -Wl,--wrap=scalable_aligned_free
    -Wl,--wrap=scalable_realloc
    -Wl,--wrap=scalable_calloc
    -Wl,--wrap=scalable_posix_memalign
    -Wl,--wrap=scalable_msize
)
include_directories("${EXTERN_DIR}/tbb/include")
link_directories("${EXTERN_DIR}/tbb/lib/${ABI_DIR}")
```

The PoC also added `src/tbb_allocator_shim.cpp` to the `prusaslicer-jni` shared library source list.

### Allocator Shim

The PoC added `app/src/main/cpp/src/tbb_allocator_shim.cpp`:

```cpp
#include <cstdlib>
#include <malloc.h>

extern "C" {

void* __real_scalable_malloc(size_t size);
void __real_scalable_free(void* ptr);
void* __real_scalable_aligned_malloc(size_t size, size_t alignment);
void __real_scalable_aligned_free(void* ptr);
void* __real_scalable_realloc(void* ptr, size_t size);
void* __real_scalable_calloc(size_t count, size_t size);
int __real_scalable_posix_memalign(void** memptr, size_t alignment, size_t size);
size_t __real_scalable_msize(void* ptr);

void* __wrap_scalable_malloc(size_t size) { return std::malloc(size); }
void __wrap_scalable_free(void* ptr) { std::free(ptr); }
void* __wrap_scalable_aligned_malloc(size_t size, size_t alignment) {
    void* ptr = nullptr;
    return posix_memalign(&ptr, alignment, size) == 0 ? ptr : nullptr;
}
void __wrap_scalable_aligned_free(void* ptr) { std::free(ptr); }
void* __wrap_scalable_realloc(void* ptr, size_t size) { return std::realloc(ptr, size); }
void* __wrap_scalable_calloc(size_t count, size_t size) { return std::calloc(count, size); }
int __wrap_scalable_posix_memalign(void** memptr, size_t alignment, size_t size) {
    return posix_memalign(memptr, alignment, size);
}
size_t __wrap_scalable_msize(void* ptr) { return malloc_usable_size(ptr); }

}
```

### Serialized `PrintObject::process_external_surfaces()`

The OrcaSlicer submodule had a dirty change in `app/src/main/cpp/orcaslicer/src/libslic3r/PrintObject.cpp`: inside `PrintObject::process_external_surfaces()`, the per-region loop kept real TBB enabled globally but serialized the layer loop that had been unsafe under real parallelism.

The original shape was:

```cpp
tbb::parallel_for(tbb::blocked_range<size_t>(0, m_layers.size()), [&](const tbb::blocked_range<size_t> &range) {
    for (size_t layer_idx = range.begin(); layer_idx < range.end(); ++layer_idx) {
        // per-layer surface processing
    }
});
```

The PoC changed it to:

```cpp
for (size_t layer_idx = 0; layer_idx < m_layers.size(); ++layer_idx) {
    // per-layer surface processing
}
```

The associated log wording was changed from `in parallel` to `serially`.

## Preserved Binary

The old rebuilt native binary still exists at:

`C:\Users\kevin\old-projects-to-delete\u1-slicer-orca\.worktrees\native-perf-poc\app\src\main\jniLibs\arm64-v8a\libprusaslicer-jni.so`

Observed metadata:

- Size: `20,991,736` bytes.
- Last write time: 2026-05-01 00:38:20 local time.

This binary is useful as provenance evidence only. Future work should rebuild from a fresh branch and record compiler metadata, JNI symbol count, and fixture timings again.

## What To Preserve Going Forward

Preserve these parts:

- The exact benchmark/test result block above.
- The allocator shim idea as a candidate experiment, not as a proven final design.
- The targeted serialization of `PrintObject::process_external_surfaces()` as a possible guardrail for real TBB.
- The warning that `codex/native-perf-poc` is not a reliable branch for the native code.

Do not preserve these as source of truth:

- The broken linked-worktree git metadata.
- The old local branch pointer by itself.
- Any decision that uses the legacy Shashibo harness as the accept/reject gate for this work.
