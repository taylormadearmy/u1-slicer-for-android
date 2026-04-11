# B55 Native Cancellation + Slice Cancel UX + F70 Check for Updates — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add instant native QEM preview cancellation (B55), honest "Cancelling..." UX for slice cancel with native hard-cancel via OrcaSlicer's built-in `throw_if_canceled()`, and a "Check for Updates" button in Settings (F70).

**Architecture:** QEM cancel uses `std::atomic<bool>` checked by the existing `throw_on_cancel` callback parameter in `its_quadric_edge_collapse()`. Slice cancel uses `print->cancel()` to set OrcaSlicer's `m_cancel_status` atomic, triggering `CanceledException` at the next pipeline checkpoint. F70 is a standalone `UpdateChecker` utility hitting the GitHub Releases API.

**Tech Stack:** C++ (atomic flags, OrcaSlicer `PrintBase` cancel API), JNI, Kotlin coroutines, Jetpack Compose, OkHttp

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `app/src/main/cpp/include/sapil.h` | Modify | Add `cancelled` to `SliceResult`, declare `cancelPreviewMesh()`/`cancelSlice()` on `SlicerEngine` |
| `app/src/main/cpp/src/sapil_model.cpp` | Modify | `g_preview_cancel` atomic, cancel check in QEM + volume loop |
| `app/src/main/cpp/src/sapil_print.cpp` | Modify | `g_active_print` atomic pointer, `CanceledException` catch |
| `app/src/main/cpp/src/slicer_wrapper.cpp` | Modify | Two new JNI entry points |
| `app/src/main/cpp/src/sapil_progress.cpp` | Modify | `cancelled` field in JNI result mapping |
| `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so` | Rebuild | New native binary |
| `app/src/main/java/com/u1/slicer/NativeLibrary.kt` | Modify | JNI declarations for cancel methods |
| `app/src/main/java/com/u1/slicer/data/SliceResult.kt` | Modify | Add `cancelled` field |
| `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` | Modify | `Cancelling` state, rewire cancel flows |
| `app/src/main/java/com/u1/slicer/MainActivity.kt` | Modify | Render `Cancelling` state in Preview screen |
| `app/src/main/java/com/u1/slicer/AppUrls.kt` | Modify | F70 GitHub API URL constant |
| `app/src/main/java/com/u1/slicer/network/UpdateChecker.kt` | Create | F70 version check logic |
| `app/src/test/java/com/u1/slicer/network/UpdateCheckerTest.kt` | Create | F70 unit tests (12) |
| `app/src/test/java/com/u1/slicer/SliceCancelTest.kt` | Create | Cancel state machine unit tests |
| `app/src/main/java/com/u1/slicer/ui/SettingsScreen.kt` | Modify | F70 "Check for Updates" row |
| `app/src/androidTest/java/com/u1/slicer/slicing/SlicingIntegrationTest.kt` | Modify | Cancel integration test |

---

### Task 1: C++ — Add `cancelled` to `SliceResult` and cancel method declarations

**Files:**
- Modify: `app/src/main/cpp/include/sapil.h`

- [ ] **Step 1: Add `cancelled` field to `SliceResult` and cancel methods to `SlicerEngine`**

In `sapil.h`, add `cancelled` to `SliceResult`:

```cpp
struct SliceResult {
    bool success = false;
    bool cancelled = false;  // <-- ADD THIS LINE
    std::string error_message;
    // ... rest unchanged
};
```

Add cancel methods to `SlicerEngine` in the public section, after `clearModel()`:

```cpp
    // Cancel an in-progress getPreparePreviewMesh() QEM decimation.
    // Safe to call from any thread. The QEM loop checks this flag every iteration.
    static void cancelPreviewMesh();

    // Cancel an in-progress slice(). Calls Print::cancel() which triggers
    // CanceledException at the next throw_if_canceled() checkpoint.
    // Safe to call from any thread.
    static void cancelSlice();
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/cpp/include/sapil.h
git commit -m "feat: B55 add cancelled field to SliceResult and cancel method declarations"
```

---

### Task 2: C++ — QEM preview cancellation in `sapil_model.cpp`

**Files:**
- Modify: `app/src/main/cpp/src/sapil_model.cpp`

- [ ] **Step 1: Add the atomic cancel flag and implement `cancelPreviewMesh()`**

At the top of `sapil_model.cpp`, near the other globals (after `static bool g_preview_mesh_valid = false;`), add:

```cpp
#include <atomic>
static std::atomic<bool> g_preview_cancel{false};
```

Add the static method implementation (after the `invalidatePreviewMeshCache()` function):

```cpp
void SlicerEngine::cancelPreviewMesh() {
    g_preview_cancel.store(true, std::memory_order_release);
    SAPIL_LOGI("cancelPreviewMesh: signalled cancellation");
}
```

- [ ] **Step 2: Add cancel checks to `getPreparePreviewMesh()`**

At the very start of `getPreparePreviewMesh()`, after the `g_model_loaded` check but before the cache check, reset the flag:

```cpp
    // Reset cancel flag at start of each preview computation
    g_preview_cancel.store(false, std::memory_order_release);
```

In the outer volume loop (the `for (const auto* object : g_model.objects)` loop), add a cancel check at the top of the loop body, right after `if (object == nullptr || !object->printable) continue;`:

```cpp
        // B55: check cancel flag between objects
        if (g_preview_cancel.load(std::memory_order_acquire)) {
            SAPIL_LOGI("getPreparePreviewMesh: cancelled during object iteration");
            return PreviewMesh();
        }
```

For the QEM call, pass the cancel callback as the `throw_on_cancel` parameter. Change the existing line:

```cpp
                        Slic3r::its_quadric_edge_collapse(its, target);
```

to:

```cpp
                        Slic3r::its_quadric_edge_collapse(its, target, nullptr,
                            [&]() { if (g_preview_cancel.load(std::memory_order_acquire)) throw std::runtime_error("cancelled"); },
                            nullptr);
```

Then wrap the QEM section in a try/catch. The block starting at `if (can_qem) {` and ending before `const int vol_stride =` should become:

```cpp
                    if (can_qem) {
                        const uint32_t target = static_cast<uint32_t>(
                            std::max(INT64_C(1),
                                static_cast<int64_t>(vol_tris) * effective_max / total_tris));
                        try {
                            if (its.indices.size() > target)
                                Slic3r::its_quadric_edge_collapse(its, target, nullptr,
                                    [&]() { if (g_preview_cancel.load(std::memory_order_acquire)) throw std::runtime_error("cancelled"); },
                                    nullptr);
                        } catch (const std::runtime_error&) {
                            SAPIL_LOGI("getPreparePreviewMesh: QEM cancelled mid-collapse");
                            return PreviewMesh();
                        }
                        if (std::chrono::steady_clock::now() > qem_deadline) {
                            qem_budget_exceeded = true;
                            SAPIL_LOGW("getPreparePreviewMesh: QEM time budget exceeded, switching to stride");
                        }
                    }
```

Also add a cancel check in the MMU round-robin interleave loop. Inside `while (any_left)`, at the top:

```cpp
                    while (any_left) {
                        if (g_preview_cancel.load(std::memory_order_acquire)) {
                            SAPIL_LOGI("getPreparePreviewMesh: cancelled during MMU interleave");
                            return PreviewMesh();
                        }
                        any_left = false;
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/cpp/src/sapil_model.cpp
git commit -m "feat: B55 add QEM preview cancellation with atomic flag"
```

---

### Task 3: C++ — Slice cancellation in `sapil_print.cpp`

**Files:**
- Modify: `app/src/main/cpp/src/sapil_print.cpp`

- [ ] **Step 1: Add the atomic Print pointer and implement `cancelSlice()`**

At the top of `sapil_print.cpp`, near the other includes/globals, add:

```cpp
#include <atomic>
static std::atomic<Slic3r::Print*> g_active_print{nullptr};
```

Add the static method implementation (near the top of the file, after the globals):

```cpp
void SlicerEngine::cancelSlice() {
    auto* print = g_active_print.load(std::memory_order_acquire);
    if (print) {
        print->cancel();
        SAPIL_LOGI("cancelSlice: signalled cancellation to Print");
    } else {
        SAPIL_LOGI("cancelSlice: no active print to cancel");
    }
}
```

You'll need to include the PrintBase header. Add at the top of sapil_print.cpp (it should already be included transitively, but verify — the `Slic3r::Print` class and `cancel()` method are in `libslic3r/Print.hpp` or `PrintBase.hpp`).

- [ ] **Step 2: Set and clear `g_active_print` in `slice()`**

In the `slice()` function, right before `print.process();` (around line 909), add:

```cpp
        g_active_print.store(&print, std::memory_order_release);
```

Right after `print.process();`, add:

```cpp
        g_active_print.store(nullptr, std::memory_order_release);
```

Also in the `export_gcode` section, right before `print.export_gcode(...)`, set it again (in case it was cleared):

```cpp
        g_active_print.store(&print, std::memory_order_release);
```

And clear after `export_gcode`:

```cpp
        g_active_print.store(nullptr, std::memory_order_release);
```

- [ ] **Step 3: Add `CanceledException` catch block**

Add a new catch block **before** the existing `catch (const std::exception& e)` block (which is the last general catch). Insert after the `Slic3r::SlicingError` catch:

```cpp
    } catch (const Slic3r::CanceledException&) {
        g_active_print.store(nullptr, std::memory_order_release);
        result.success = false;
        result.cancelled = true;
        result.error_message = "Cancelled by user";
        SAPIL_LOGI("Slicing cancelled by user");
```

Also add `g_active_print.store(nullptr)` to the existing catch blocks to ensure cleanup:

In the `SlicingErrors` catch, add as the first line:
```cpp
        g_active_print.store(nullptr, std::memory_order_release);
```

In the `SlicingError` catch, add as the first line:
```cpp
        g_active_print.store(nullptr, std::memory_order_release);
```

In the `std::exception` catch, add as the first line:
```cpp
        g_active_print.store(nullptr, std::memory_order_release);
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/cpp/src/sapil_print.cpp
git commit -m "feat: B55 add native slice cancellation via Print::cancel()"
```

---

### Task 4: C++ — JNI bridge for cancel methods

**Files:**
- Modify: `app/src/main/cpp/src/slicer_wrapper.cpp`
- Modify: `app/src/main/cpp/src/sapil_progress.cpp`

- [ ] **Step 1: Add JNI entry points for cancel methods**

In `slicer_wrapper.cpp`, add before the closing `} // extern "C"`:

```cpp
// ---- Cancellation ----
JNIEXPORT void JNICALL
Java_com_u1_slicer_NativeLibrary_cancelPreviewMesh(JNIEnv*, jobject) {
    sapil::SlicerEngine::cancelPreviewMesh();
}

JNIEXPORT void JNICALL
Java_com_u1_slicer_NativeLibrary_cancelSlice(JNIEnv*, jobject) {
    sapil::SlicerEngine::cancelSlice();
}
```

- [ ] **Step 2: Update `sliceResultToJava()` to include `cancelled` field**

In `sapil_progress.cpp`, update the JNI constructor signature and args to include the `cancelled` boolean. The constructor signature changes from `(ZLjava/lang/String;Ljava/lang/String;IFFF)V` to `(ZZLjava/lang/String;Ljava/lang/String;IFFF)V` (added a `Z` for the boolean after the first `Z`).

Update the full function:

```cpp
jobject sliceResultToJava(JNIEnv* env, const SliceResult& result) {
    jclass cls = env->FindClass("com/u1/slicer/data/SliceResult");
    if (!cls) {
        SAPIL_LOGE("SliceResult class not found");
        return nullptr;
    }

    jmethodID constructor = env->GetMethodID(cls, "<init>",
        "(ZZLjava/lang/String;Ljava/lang/String;IFFF)V");
    if (!constructor) {
        SAPIL_LOGE("SliceResult constructor not found");
        return nullptr;
    }

    jstring jerror = env->NewStringUTF(result.error_message.c_str());
    jstring jgcode_path = env->NewStringUTF(result.gcode_path.c_str());

    jvalue args[8];
    args[0].z = result.success ? JNI_TRUE : JNI_FALSE;
    args[1].z = result.cancelled ? JNI_TRUE : JNI_FALSE;
    args[2].l = jerror;
    args[3].l = jgcode_path;
    args[4].i = result.total_layers;
    args[5].f = result.estimated_time_seconds;
    args[6].f = result.estimated_filament_mm;
    args[7].f = result.estimated_filament_grams;
    jobject obj = env->NewObjectA(cls, constructor, args);

    env->DeleteLocalRef(jerror);
    env->DeleteLocalRef(jgcode_path);
    env->DeleteLocalRef(cls);
    return obj;
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/cpp/src/slicer_wrapper.cpp app/src/main/cpp/src/sapil_progress.cpp
git commit -m "feat: B55 JNI bridge for cancelPreviewMesh + cancelSlice + cancelled field"
```

---

### Task 5: Native rebuild

**Files:**
- Rebuild: `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so`

- [ ] **Step 1: Enable CMake in build.gradle**

Uncomment the `externalNativeBuild` blocks in `app/build.gradle`.

- [ ] **Step 2: Configure CMake**

```bash
./gradlew assembleDebug --no-daemon
```

This configures CMake and creates the `.cxx` build directory. It will fail at link time (expected) but the ninja build files are generated.

- [ ] **Step 3: Disable CMake in build.gradle**

Re-comment the `externalNativeBuild` blocks.

- [ ] **Step 4: Run ninja build**

```bash
ninja -j1 -C app/.cxx/Debug/*/arm64-v8a/
```

Use `-j1` to avoid OOM. This will take a while — only the changed `.cpp` files need recompilation.

- [ ] **Step 5: Strip the binary**

Find the NDK `llvm-strip` and strip:

```bash
$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/*/bin/llvm-strip --strip-unneeded \
    app/.cxx/Debug/*/arm64-v8a/libprusaslicer-jni.so
```

- [ ] **Step 6: Copy to jniLibs**

```bash
cp app/.cxx/Debug/*/arm64-v8a/libprusaslicer-jni.so \
   app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so
```

- [ ] **Step 7: Clean build to avoid stale APK cache**

```bash
./gradlew clean --no-daemon
```

- [ ] **Step 8: Commit**

```bash
git add app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so
git commit -m "build: rebuild native .so with cancel support"
```

---

### Task 6: Kotlin — SliceResult + NativeLibrary cancel declarations

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/data/SliceResult.kt`
- Modify: `app/src/main/java/com/u1/slicer/NativeLibrary.kt`

- [ ] **Step 1: Add `cancelled` field to Kotlin `SliceResult`**

Update `SliceResult.kt` — add `cancelled` as the second parameter (must match JNI constructor order):

```kotlin
data class SliceResult(
    @JvmField val success: Boolean,
    @JvmField val cancelled: Boolean,
    @JvmField val errorMessage: String,
    @JvmField val gcodePath: String,
    @JvmField val totalLayers: Int,
    @JvmField val estimatedTimeSeconds: Float,
    @JvmField val estimatedFilamentMm: Float,
    @JvmField val estimatedFilamentGrams: Float
) {
```

The rest of the class body is unchanged.

- [ ] **Step 2: Add JNI declarations for cancel methods**

In `NativeLibrary.kt`, add after the `clearModel()` declaration:

```kotlin
    // Cancel an in-progress QEM preview decimation. Called from clearModel() before
    // acquiring previewMutex so QEM bails out immediately.
    external fun cancelPreviewMesh()

    // Cancel an in-progress native slice. Triggers CanceledException at the next
    // OrcaSlicer checkpoint. Called from cancelSlicing().
    external fun cancelSlice()
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/u1/slicer/data/SliceResult.kt \
       app/src/main/java/com/u1/slicer/NativeLibrary.kt
git commit -m "feat: B55 Kotlin SliceResult.cancelled + JNI cancel declarations"
```

---

### Task 7: Kotlin — Cancelling state + rewired cancel flows in SlicerViewModel

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`

- [ ] **Step 1: Add `Cancelling` state to `SlicerState`**

In the `SlicerState` sealed class (around line 136), add after `Slicing`:

```kotlin
        object Cancelling : SlicerState()
```

- [ ] **Step 2: Rewire `cancelSlicing()` to use native cancel**

Replace the entire `cancelSlicing()` function and remove the `sliceCancelled` field. The old code (around lines 1782-1797):

```kotlin
    @Volatile private var sliceCancelled = false

    fun cancelSlicing() {
        if (_state.value is SlicerState.Slicing) {
            sliceCancelled = true
            backToModelLoaded()
            Log.i("SlicerVM", "Slicing cancelled by user (native call will still complete in background)")
        }
    }
```

Replace with:

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

- [ ] **Step 3: Update slice result handling to check `cancelled`**

In `startSlicing()`, find the section after `val result = native.slice(sliceConfig)` (around line 2044). Remove the old `sliceCancelled` check:

```kotlin
                // If the user cancelled while the native call was running, discard the result.
                if (sliceCancelled) {
                    Log.i("SlicerVM", "Discarding slice result after user cancel")
                    return@launch
                }
```

Replace with:

```kotlin
                // Native cancel: slice() returned with cancelled=true from CanceledException
                if (result?.cancelled == true) {
                    Log.i("SlicerVM", "Slice cancelled — returning to ModelLoaded")
                    result.gcodePath.let { path ->
                        if (path.isNotEmpty()) java.io.File(path).delete()
                    }
                    backToModelLoaded()
                    return@launch
                }
```

- [ ] **Step 4: Update the `finally` block**

In the `finally` block of `startSlicing()` (around line 2191), remove the `sliceCancelled = false` line. The rest stays the same.

- [ ] **Step 5: Update `clearModel()` to call `cancelPreviewMesh()` first**

In `clearModel()` (around line 2722), add `native.cancelPreviewMesh()` as the first line:

```kotlin
    fun clearModel() {
        native.cancelPreviewMesh()  // B55: signal QEM to bail out immediately
        if (NativeLibrary.previewMutex.tryLock()) {
```

- [ ] **Step 6: Update model-loaded checks to include Cancelling state**

In `resolvePreparePreviewModelInfo()` (at the bottom of `MainActivity.kt` around line 2504), add `Cancelling`:

```kotlin
internal fun resolvePreparePreviewModelInfo(
    state: SlicerViewModel.SlicerState,
    cachedModelInfo: ModelInfo?
): ModelInfo? = when (state) {
    is SlicerViewModel.SlicerState.ModelLoaded -> state.info
    is SlicerViewModel.SlicerState.Slicing -> cachedModelInfo
    is SlicerViewModel.SlicerState.Cancelling -> cachedModelInfo
    is SlicerViewModel.SlicerState.SliceComplete -> cachedModelInfo
    else -> cachedModelInfo
}
```

In `MainActivity.kt`, the `modelLoaded` check (around line 784):

```kotlin
        val modelLoaded = state is SlicerViewModel.SlicerState.ModelLoaded ||
                state is SlicerViewModel.SlicerState.Slicing ||
                state is SlicerViewModel.SlicerState.Cancelling ||
                state is SlicerViewModel.SlicerState.SliceComplete
```

- [ ] **Step 7: Update notification state transitions**

In the state transition observer (around line 392), add Cancelling → ModelLoaded as a no-notification transition (no changes needed — it's already handled by the `else` case).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/u1/slicer/SlicerViewModel.kt \
       app/src/main/java/com/u1/slicer/MainActivity.kt
git commit -m "feat: B55 Cancelling state + native cancel wiring in ViewModel"
```

---

### Task 8: UI — Render Cancelling state in Preview screen

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/MainActivity.kt`

- [ ] **Step 1: Add Cancelling rendering in the Preview screen `when` block**

In `MainActivity.kt`, find the `when (val s = state)` block in the Preview screen (around line 1191). After the `is SlicerViewModel.SlicerState.Slicing` branch, add:

```kotlin
                is SlicerViewModel.SlicerState.Cancelling -> {
                    SlicingProgressCard(
                        progress = -1,
                        stage = "Cancelling\u2026",
                        onCancel = null  // disable cancel button — already cancelling
                    )
                }
```

- [ ] **Step 2: Update `SlicingProgressCard` to handle indeterminate progress**

In `SlicingProgressCard` (around line 1554), update the determinate progress indicator to show indeterminate when `progress < 0`:

Replace the Box with the two progress indicators:

```kotlin
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(80.dp)) {
                // Indeterminate ring behind the determinate one — always spinning
                CircularProgressIndicator(
                    modifier = Modifier.size(80.dp),
                    color = if (progress < 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    strokeWidth = 6.dp
                )
                if (progress >= 0) {
                    CircularProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.size(80.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 6.dp
                    )
                }
            }
```

And update the percentage text to show nothing when cancelling:

```kotlin
            if (progress >= 0) {
                Text("$progress%", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/u1/slicer/MainActivity.kt
git commit -m "feat: B55 render Cancelling state with indeterminate progress"
```

---

### Task 9: TDD — Cancel state machine unit tests

**Files:**
- Create: `app/src/test/java/com/u1/slicer/SliceCancelTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.u1.slicer

import com.u1.slicer.data.SliceResult
import org.junit.Assert.*
import org.junit.Test

class SliceCancelTest {

    @Test
    fun `SliceResult cancelled field defaults to false`() {
        val result = SliceResult(
            success = true,
            cancelled = false,
            errorMessage = "",
            gcodePath = "/tmp/out.gcode",
            totalLayers = 100,
            estimatedTimeSeconds = 3600f,
            estimatedFilamentMm = 1000f,
            estimatedFilamentGrams = 5f
        )
        assertFalse(result.cancelled)
    }

    @Test
    fun `SliceResult cancelled true when slice was cancelled`() {
        val result = SliceResult(
            success = false,
            cancelled = true,
            errorMessage = "Cancelled by user",
            gcodePath = "",
            totalLayers = 0,
            estimatedTimeSeconds = 0f,
            estimatedFilamentMm = 0f,
            estimatedFilamentGrams = 0f
        )
        assertTrue(result.cancelled)
        assertFalse(result.success)
    }

    @Test
    fun `SlicerState Cancelling is distinct from Slicing`() {
        val cancelling = SlicerViewModel.SlicerState.Cancelling
        val slicing = SlicerViewModel.SlicerState.Slicing(50, "Processing...")
        assertNotEquals(cancelling, slicing)
    }

    @Test
    fun `SlicerState Cancelling is an object singleton`() {
        val a = SlicerViewModel.SlicerState.Cancelling
        val b = SlicerViewModel.SlicerState.Cancelling
        assertSame(a, b)
    }

    @Test
    fun `cancelled SliceResult has empty gcode path`() {
        val result = SliceResult(
            success = false,
            cancelled = true,
            errorMessage = "Cancelled by user",
            gcodePath = "",
            totalLayers = 0,
            estimatedTimeSeconds = 0f,
            estimatedFilamentMm = 0f,
            estimatedFilamentGrams = 0f
        )
        assertTrue(result.gcodePath.isEmpty())
    }
}
```

- [ ] **Step 2: Run tests to verify they compile and pass**

```bash
./gradlew testDebugUnitTest --tests "com.u1.slicer.SliceCancelTest" --no-daemon
```

Expected: all 5 tests PASS (these test the data model, not the native cancel flow).

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/u1/slicer/SliceCancelTest.kt
git commit -m "test: B55 cancel state machine unit tests"
```

---

### Task 10: TDD — F70 UpdateChecker tests + implementation

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/AppUrls.kt`
- Create: `app/src/test/java/com/u1/slicer/network/UpdateCheckerTest.kt`
- Create: `app/src/main/java/com/u1/slicer/network/UpdateChecker.kt`

- [ ] **Step 1: Add GitHub API URL constant**

In `AppUrls.kt`, add:

```kotlin
const val GITHUB_RELEASES_LATEST_URL =
    "https://api.github.com/repos/taylormadearmy/u1-slicer-for-android/releases/latest"
```

- [ ] **Step 2: Write failing tests**

Create `UpdateCheckerTest.kt`:

```kotlin
package com.u1.slicer.network

import org.junit.Assert.*
import org.junit.Test

class UpdateCheckerTest {

    // --- parseLatestRelease: extracts version from GitHub API JSON ---

    @Test
    fun `parseLatestRelease extracts tag_name without v prefix`() {
        val json = """{"tag_name":"v1.5.49","assets":[{"name":"u1-slicer-v1.5.49.apk","browser_download_url":"https://github.com/download/u1-slicer-v1.5.49.apk"}]}"""
        val result = UpdateChecker.parseLatestRelease(json)
        assertEquals("1.5.49", result?.version)
    }

    @Test
    fun `parseLatestRelease handles tag without v prefix`() {
        val json = """{"tag_name":"1.5.49","assets":[{"name":"app.apk","browser_download_url":"https://example.com/app.apk"}]}"""
        val result = UpdateChecker.parseLatestRelease(json)
        assertEquals("1.5.49", result?.version)
    }

    @Test
    fun `parseLatestRelease returns null for malformed JSON`() {
        assertNull(UpdateChecker.parseLatestRelease("not json"))
    }

    @Test
    fun `parseLatestRelease returns null for missing tag_name`() {
        val json = """{"assets":[]}"""
        assertNull(UpdateChecker.parseLatestRelease(json))
    }

    @Test
    fun `parseLatestRelease extracts first APK download URL from assets`() {
        val json = """{"tag_name":"v1.5.49","assets":[
            {"name":"u1-slicer-v1.5.49.apk","browser_download_url":"https://github.com/download/u1-slicer-v1.5.49.apk"},
            {"name":"source.zip","browser_download_url":"https://github.com/download/source.zip"}
        ]}"""
        val result = UpdateChecker.parseLatestRelease(json)
        assertEquals("https://github.com/download/u1-slicer-v1.5.49.apk", result?.downloadUrl)
    }

    @Test
    fun `parseLatestRelease falls back to release page when no APK asset`() {
        val json = """{"tag_name":"v1.5.49","html_url":"https://github.com/taylormadearmy/u1-slicer-for-android/releases/tag/v1.5.49","assets":[]}"""
        val result = UpdateChecker.parseLatestRelease(json)
        assertEquals("https://github.com/taylormadearmy/u1-slicer-for-android/releases/tag/v1.5.49", result?.downloadUrl)
    }

    // --- isNewer: semantic version comparison ---

    @Test
    fun `isNewer returns true when remote patch is higher`() {
        assertTrue(UpdateChecker.isNewer(remote = "1.5.49", current = "1.5.48"))
    }

    @Test
    fun `isNewer returns true when remote minor is higher`() {
        assertTrue(UpdateChecker.isNewer(remote = "1.6.0", current = "1.5.48"))
    }

    @Test
    fun `isNewer returns true when remote major is higher`() {
        assertTrue(UpdateChecker.isNewer(remote = "2.0.0", current = "1.5.48"))
    }

    @Test
    fun `isNewer returns false when versions are equal`() {
        assertFalse(UpdateChecker.isNewer(remote = "1.5.48", current = "1.5.48"))
    }

    @Test
    fun `isNewer returns false when current is newer`() {
        assertFalse(UpdateChecker.isNewer(remote = "1.5.47", current = "1.5.48"))
    }

    @Test
    fun `isNewer handles different segment counts gracefully`() {
        assertTrue(UpdateChecker.isNewer(remote = "1.6", current = "1.5.48"))
        assertFalse(UpdateChecker.isNewer(remote = "1.5", current = "1.5.48"))
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
./gradlew testDebugUnitTest --tests "com.u1.slicer.network.UpdateCheckerTest" --no-daemon
```

Expected: compilation error — `UpdateChecker` doesn't exist yet.

- [ ] **Step 4: Write minimal implementation**

Create `app/src/main/java/com/u1/slicer/network/UpdateChecker.kt`:

```kotlin
package com.u1.slicer.network

import android.util.Log
import com.u1.slicer.GITHUB_RELEASES_LATEST_URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object UpdateChecker {

    private const val TAG = "UpdateChecker"

    data class ReleaseInfo(val version: String, val downloadUrl: String)

    fun parseLatestRelease(json: String): ReleaseInfo? {
        return try {
            val obj = JSONObject(json)
            val tagName = obj.optString("tag_name", "").ifEmpty { return null }
            val version = tagName.removePrefix("v")

            val assets = obj.optJSONArray("assets")
            var downloadUrl: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk")) {
                        downloadUrl = asset.optString("browser_download_url", "")
                        break
                    }
                }
            }
            if (downloadUrl.isNullOrEmpty()) {
                downloadUrl = obj.optString("html_url", "")
            }
            if (downloadUrl.isNullOrEmpty()) return null

            ReleaseInfo(version, downloadUrl)
        } catch (e: Exception) {
            Log.w(TAG, "parseLatestRelease failed: ${e.message}")
            null
        }
    }

    fun isNewer(remote: String, current: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.toIntOrNull() ?: 0 }
        val len = maxOf(r.size, c.size)
        for (i in 0 until len) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv > cv) return true
            if (rv < cv) return false
        }
        return false
    }

    suspend fun checkForUpdate(currentVersion: String): Result<ReleaseInfo?> =
        withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url(GITHUB_RELEASES_LATEST_URL)
                    .header("Accept", "application/vnd.github+json")
                    .build()
                val response = client.newCall(request).execute()
                val body = response.body?.string()
                val code = response.code
                response.close()

                if (code != 200 || body == null) {
                    return@withContext Result.failure(
                        Exception("GitHub API returned $code")
                    )
                }

                val release = parseLatestRelease(body)
                    ?: return@withContext Result.failure(Exception("Could not parse release"))

                if (isNewer(release.version, currentVersion)) {
                    Result.success(release)
                } else {
                    Result.success(null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "checkForUpdate failed: ${e.message}")
                Result.failure(e)
            }
        }
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
./gradlew testDebugUnitTest --tests "com.u1.slicer.network.UpdateCheckerTest" --no-daemon
```

Expected: all 12 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/u1/slicer/AppUrls.kt \
       app/src/main/java/com/u1/slicer/network/UpdateChecker.kt \
       app/src/test/java/com/u1/slicer/network/UpdateCheckerTest.kt
git commit -m "feat: F70 add UpdateChecker with version parsing + comparison"
```

---

### Task 11: F70 — Settings screen "Check for Updates" row

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/ui/SettingsScreen.kt`

- [ ] **Step 1: Add state and UI**

Add imports at the top of `SettingsScreen.kt`:

```kotlin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import com.u1.slicer.network.UpdateChecker
import kotlinx.coroutines.launch
```

Add the sealed interface above the `SettingsScreen` function:

```kotlin
private sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data class Available(val version: String, val downloadUrl: String) : UpdateCheckState
    data object UpToDate : UpdateCheckState
    data class Error(val message: String) : UpdateCheckState
}
```

Inside `SettingsSection("About")`, after the `val context = LocalContext.current` line (line 127), add:

```kotlin
            val scope = rememberCoroutineScope()
            var updateState by remember { mutableStateOf<UpdateCheckState>(UpdateCheckState.Idle) }
```

After the GitHub row (after line 161), before the Buy Me a Coffee card, add:

```kotlin
                // Check for Updates row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = updateState !is UpdateCheckState.Checking) {
                            updateState = UpdateCheckState.Checking
                            scope.launch {
                                val result = UpdateChecker.checkForUpdate(BuildConfig.VERSION_NAME)
                                updateState = result.fold(
                                    onSuccess = { release ->
                                        if (release != null) UpdateCheckState.Available(release.version, release.downloadUrl)
                                        else UpdateCheckState.UpToDate
                                    },
                                    onFailure = { UpdateCheckState.Error(it.message ?: "Check failed") }
                                )
                            }
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Check for Updates", style = MaterialTheme.typography.bodyMedium)
                    when (val s = updateState) {
                        is UpdateCheckState.Idle -> Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Check for updates",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        is UpdateCheckState.Checking -> CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        is UpdateCheckState.UpToDate -> Text(
                            "Up to date",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        is UpdateCheckState.Available -> Text(
                            "v${s.version} available",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        is UpdateCheckState.Error -> Text(
                            "Check failed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                // Download row (only shown when update is available)
                if (updateState is UpdateCheckState.Available) {
                    val available = updateState as UpdateCheckState.Available
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(available.downloadUrl))
                                )
                            },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Download v${available.version}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Download update",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
```

- [ ] **Step 2: Build to verify compilation**

```bash
./gradlew compileDebugKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/u1/slicer/ui/SettingsScreen.kt
git commit -m "feat: F70 add Check for Updates button to Settings About section"
```

---

### Task 12: Integration test — slice cancel on device

**Files:**
- Modify: `app/src/androidTest/java/com/u1/slicer/slicing/SlicingIntegrationTest.kt`

- [ ] **Step 1: Add cancel integration test**

Add this test to `SlicingIntegrationTest.kt`:

```kotlin
    @Test
    fun sliceCancelReturnsCancelledResult() {
        // Load a model that will take a non-trivial time to slice
        val assetPath = copyAssetToTemp("20mm_box.stl")
        assertTrue("Model must load", lib.loadModel(assetPath))

        val config = SliceConfig().apply {
            layerHeight = 0.2f
            extruderCount = 1
        }

        // Start slice on a background thread
        var result: SliceResult? = null
        val sliceThread = Thread {
            result = lib.slice(config)
        }
        sliceThread.start()

        // Give the slice a moment to start, then cancel
        Thread.sleep(500)
        lib.cancelSlice()

        // Wait for the slice to finish (should be fast after cancel)
        sliceThread.join(30_000)

        assertNotNull("slice() must return a result", result)
        assertTrue("Result must be cancelled", result!!.cancelled)
        assertFalse("Result must not be successful", result!!.success)
    }
```

- [ ] **Step 2: Run the test on device**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest \
    --tests "com.u1.slicer.slicing.SlicingIntegrationTest.sliceCancelReturnsCancelledResult" \
    --no-daemon
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/java/com/u1/slicer/slicing/SlicingIntegrationTest.kt
git commit -m "test: B55 slice cancel integration test"
```

---

### Task 13: Run full test suite

- [ ] **Step 1: Run JVM unit tests**

```bash
./gradlew testDebugUnitTest --no-daemon
```

Expected: all tests pass. Count should be 726 + 5 (SliceCancelTest) + 12 (UpdateCheckerTest) = 743.

- [ ] **Step 2: Run instrumented tests**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest --no-daemon
```

Expected: 162 + 1 (cancel test) = 163 pass, 0 fail.

---

### Task 14: Update docs — BACKLOG.md, CLAUDE.md, version bump

**Files:**
- Modify: `BACKLOG.md`
- Modify: `CLAUDE.md`
- Modify: `app/build.gradle`

- [ ] **Step 1: Update BACKLOG.md**

Mark B55 as FIXED. Add F70 as DONE. Update the slice cancel description. Add entries to the Closed section.

- [ ] **Step 2: Update CLAUDE.md test counts**

Update the unit test total to 743 and add the new test classes:

```
- `SliceCancelTest.kt` (5) — cancel state machine: SliceResult.cancelled field, Cancelling state
- `network/UpdateCheckerTest.kt` (12) — GitHub release JSON parsing, semantic version comparison, download URL extraction
```

Update instrumented test total to 163.

- [ ] **Step 3: Bump version**

In `app/build.gradle`, increment `versionCode` and `versionName`:
- `versionCode`: 215 (was 214)
- `versionName`: "1.5.49" (was "1.5.48")

- [ ] **Step 4: Commit**

```bash
git add BACKLOG.md CLAUDE.md app/build.gradle
git commit -m "bump: v1.5.49 - B55 native cancel + slice cancel UX + F70 check for updates"
```

---

### Task 15: Build release APK and copy to G drive

- [ ] **Step 1: Build release APK**

```bash
./gradlew assembleRelease --no-daemon
```

- [ ] **Step 2: Copy to G drive**

```bash
cp app/build/outputs/apk/release/app-release.apk "G:/My Drive/claude/u1-slicer-v1.5.49.apk"
```

- [ ] **Step 3: Install debug build on device for manual testing**

```bash
./gradlew installDebug --no-daemon
```
