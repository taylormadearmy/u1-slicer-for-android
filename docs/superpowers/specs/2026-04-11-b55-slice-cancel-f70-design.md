# Design: B55 Native Cancellation + Slice Cancel UX + F70 Check for Updates

**Date**: 2026-04-11
**Issues**: B55 (GitHub #57), F70 (new), plus slice cancel UX improvement

---

## B55: QEM Preview Cancel — Instant Native Cancellation

### Problem

Loading a new model or hitting X while `getPreparePreviewMesh()` QEM decimation is running (30+ seconds for large models like F1 calendar) causes either SIGSEGV or a 30-second UI stall. The current mitigation acquires `previewMutex` in `clearModel()`, which blocks until QEM finishes.

### Mechanism

Add `std::atomic<bool> g_preview_cancel{false}` in `sapil_model.cpp`.

- **Reset** to `false` at the start of `getPreparePreviewMesh()`
- **Check** in two places:
  1. Pass a lambda `[&]{ if (g_preview_cancel.load()) throw Slic3r::CanceledException(); }` as the existing `throw_on_cancel` parameter to `its_quadric_edge_collapse()` (currently unused — `QuadricEdgeCollapse.hpp` already accepts this parameter)
  2. Check `g_preview_cancel` at the top of the outer volume iteration loop; return empty `PreviewMesh` if set
- **Set** via new JNI method `cancelPreviewMesh()` which sets `g_preview_cancel = true`
- **Catch** `CanceledException` inside `getPreparePreviewMesh()`, return empty mesh, do NOT cache the result

### JNI

New method in `slicer_wrapper.cpp`:

```cpp
extern "C" JNIEXPORT void JNICALL
Java_com_u1_slicer_NativeLibrary_cancelPreviewMesh(JNIEnv*, jobject) {
    g_preview_cancel.store(true, std::memory_order_release);
}
```

### Kotlin Changes

In `NativeLibrary.kt`:
```kotlin
external fun cancelPreviewMesh()
```

In `SlicerViewModel.kt` — `clearModel()`:
```kotlin
fun clearModel() {
    native.cancelPreviewMesh()  // Signal QEM to bail out immediately
    if (NativeLibrary.previewMutex.tryLock()) {
        try { native.clearModel() } finally { NativeLibrary.previewMutex.unlock() }
    } else {
        viewModelScope.launch(Dispatchers.IO) {
            NativeLibrary.previewMutex.withLock { native.clearModel() }
        }
    }
    // ... rest of cleanup
}
```

After `cancelPreviewMesh()` is called, QEM bails within microseconds (checks every iteration), `getPreparePreviewMesh()` returns, `previewMutex` is released, and `clearModel()` acquires it immediately.

### UX

Instant — no "Cancelling..." state needed. The user sees the model clear immediately.

### Safety

- `indexed_triangle_set` is RAII; unwinding via exception is safe
- Empty preview mesh means the cache is not written (`g_preview_mesh_valid` stays false)
- `g_preview_cancel` is reset at the start of each `getPreparePreviewMesh()` call, so subsequent previews work normally

### Tests

Unit test in `PreparePreviewCacheTest.kt`:
- `cancelPreviewMesh invalidates in-flight preview` — verify that after calling `cancelPreviewMesh()`, the next `getPreparePreviewMesh()` call still works (flag was reset)

---

## Slice Cancel — Native Hard Cancellation with Honest UX

### Problem

Current slice cancel is soft-only: `cancelSlicing()` sets `@Volatile sliceCancelled = true` and snaps the UI back to ModelLoaded immediately, but the native `slice()` continues running for potentially minutes (full CPU + disk I/O), then the result is discarded. The UI lies about the state.

### Mechanism

**Native side** — `sapil_print.cpp`:

Add `static std::atomic<Slic3r::Print*> g_active_print{nullptr}` at file scope.

In `SlicerEngine::slice()`:
- Set `g_active_print = &print` before `print.process()`
- Clear `g_active_print = nullptr` in all exit paths (success, error catches, cancel catch)
- Add `catch (Slic3r::CanceledException&)` before the existing `std::exception` catch:
  ```cpp
  } catch (const Slic3r::CanceledException&) {
      g_active_print.store(nullptr, std::memory_order_release);
      result.success = false;
      result.cancelled = true;
      result.error_message = "Cancelled by user";
      SAPIL_LOGI("Slicing cancelled by user");
  }
  ```

**`SliceResult`** — `sapil.h`:

Add `bool cancelled = false;` field.

**JNI** — `slicer_wrapper.cpp`:

```cpp
extern "C" JNIEXPORT void JNICALL
Java_com_u1_slicer_NativeLibrary_cancelSlice(JNIEnv*, jobject) {
    auto* print = g_active_print.load(std::memory_order_acquire);
    if (print) {
        print->cancel();
        SAPIL_LOGI("cancelSlice: signalled cancellation to Print");
    }
}
```

**JNI result mapping** — `sliceResultToJava()` in `slicer_wrapper.cpp`:

Add `cancelled` boolean field to the JNI result mapping. The Kotlin-side `SliceResult` data class already has fields mapped from JNI — add `val cancelled: Boolean = false`. In `sliceResultToJava()`, set the field from `result.cancelled`.

### OrcaSlicer Integration

`print->cancel()` sets `m_cancel_status = CANCELED_BY_USER` (atomic). The hundreds of existing `throw_if_canceled()` calls throughout OrcaSlicer's `Print::process()` and `export_gcode()` pipelines throw `CanceledException` at the next checkpoint. Coarse granularity — cancel happens between major phases (perimeters, infill, supports, G-code layers), typically within 5-15 seconds.

### Kotlin Changes

**New state** in `SlicerState`:
```kotlin
object Cancelling : SlicerState()
```

**Rewired `cancelSlicing()`**:
```kotlin
fun cancelSlicing() {
    if (_state.value is SlicerState.Slicing) {
        _state.value = SlicerState.Cancelling
        viewModelScope.launch(Dispatchers.IO) {
            native.cancelSlice()
        }
        Log.i("SlicerVM", "Slicing cancel requested (native will stop at next checkpoint)")
    }
}
```

**After `native.slice()` returns** — check the `cancelled` flag:
```kotlin
val result = native.slice(sliceConfig)
if (result.cancelled) {
    Log.i("SlicerVM", "Slice cancelled — returning to ModelLoaded")
    // Delete partial G-code if it exists
    result.gcodePath?.let { File(it).delete() }
    backToModelLoaded()
    return@launch
}
```

Remove the old `sliceCancelled` volatile flag and all its checks.

### UX

1. User taps Cancel → state becomes `Cancelling` → UI shows "Cancelling..." with disabled cancel button and a subtle progress indicator
2. Native pipeline throws `CanceledException` at next checkpoint (5-15 seconds worst case)
3. `slice()` returns with `cancelled = true`
4. Kotlin transitions to `ModelLoaded`

The progress card during `Cancelling` state shows "Cancelling..." as the stage text. The cancel button is disabled (already pressed). The progress bar freezes at its last position or shows indeterminate.

### Cleanup

- Partial G-code file is deleted when cancel result is received
- `g_active_print` is cleared in the cancel catch, so subsequent `cancelSlice()` calls are no-ops
- `print.restart()` is NOT called — the `Print` object is stack-local and about to be destroyed

### Tests

Unit tests in `SliceStalenessTest.kt` or new `SliceCancelTest.kt`:
- `Cancelling state transitions to ModelLoaded after cancel result` — verify state machine flow

The actual native cancellation is best tested via instrumented test:
- `SlicingIntegrationTest.kt` — `slice cancel returns cancelled result`: load a model, start slice on background thread, call `cancelSlice()`, verify `result.cancelled == true` and `result.success == false`

---

## F70: Check for Updates

Already fully designed in [`docs/superpowers/plans/2026-04-11-f70-check-for-updates.md`](../plans/2026-04-11-f70-check-for-updates.md). Summary:

- `UpdateChecker` object: fetches `/releases/latest` from GitHub API, parses `tag_name`, compares to `BuildConfig.VERSION_NAME`
- `UpdateCheckerTest.kt`: 12 unit tests for JSON parsing, version comparison, download URL extraction
- Settings screen: "Check for Updates" row with inline state (Idle → Checking → Available/UpToDate/Error), download link when update available, BMAC nudge alongside update notification
- Uses OkHttp (already in project), `org.json` (Android built-in), coroutines

No design changes from the existing plan.

---

## Native Rebuild

All three features (QEM cancel, slice cancel, F70) ship together. QEM and slice cancel require C++ changes:

| File | Changes |
|------|---------|
| `sapil.h` | `cancelled` field on `SliceResult` |
| `sapil_model.cpp` | `g_preview_cancel` atomic, cancel check in QEM + volume loop |
| `sapil_print.cpp` | `g_active_print` atomic pointer, `CanceledException` catch |
| `slicer_wrapper.cpp` | `cancelPreviewMesh()` + `cancelSlice()` JNI bridges |

Rebuild steps (per CLAUDE.md):
1. Enable CMake in `build.gradle`
2. `./gradlew assembleDebug` to configure
3. Disable CMake, `ninja -j1` in `.cxx/Debug/<hash>/arm64-v8a/`
4. `llvm-strip --strip-unneeded`
5. Copy `.so` to `jniLibs/arm64-v8a/`

---

## Files Changed (Complete)

| File | Action | What |
|------|--------|------|
| `app/src/main/cpp/include/sapil.h` | Modify | `cancelled` on `SliceResult` |
| `app/src/main/cpp/src/sapil_model.cpp` | Modify | `g_preview_cancel`, cancel check in QEM + volume loop |
| `app/src/main/cpp/src/sapil_print.cpp` | Modify | `g_active_print`, `CanceledException` catch |
| `app/src/main/cpp/src/slicer_wrapper.cpp` | Modify | Two new JNI methods |
| `app/src/main/jniLibs/arm64-v8a/libsapil.so` | Rebuild | New native binary |
| `app/src/main/java/com/u1/slicer/NativeLibrary.kt` | Modify | JNI declarations |
| `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` | Modify | `Cancelling` state, rewire cancel flows |
| `app/src/main/java/com/u1/slicer/ui/SettingsScreen.kt` | Modify | F70 UI |
| `app/src/main/java/com/u1/slicer/AppUrls.kt` | Modify | F70 URL constant |
| `app/src/main/java/com/u1/slicer/network/UpdateChecker.kt` | Create | F70 logic |
| `app/src/test/java/com/u1/slicer/network/UpdateCheckerTest.kt` | Create | F70 tests (12) |
| `app/src/test/java/com/u1/slicer/SliceCancelTest.kt` | Create | Cancel state machine tests |
| `app/src/androidTest/java/com/u1/slicer/slicing/SlicingIntegrationTest.kt` | Modify | Cancel integration test |
| UI composable (SlicerScreen/MainActivity) | Modify | Render `Cancelling` state |
