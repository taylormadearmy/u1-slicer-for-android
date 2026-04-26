# Camera Wake-Up / Keepalive Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Automatically wake and maintain the Snapmaker U1 camera feed while the Printer screen is visible, eliminating the need for any external workaround server.

**Architecture:** `MoonrakerClient` gains `wakeCamera()` (fire-and-forget WebSocket keepalive) and appends `monitor.jpg` as the last snapshot candidate. `PrinterRepository` wraps `wakeCamera()`. `PrinterViewModel` runs a 2s keepalive loop while the Printer screen is open. A `DisposableEffect` in `PrinterScreen` starts/stops the loop.

**Tech Stack:** Kotlin coroutines, OkHttp WebSocket (built-in, no new dependencies), Jetpack Compose `DisposableEffect`, JUnit4 + MockWebServer for unit tests.

---

## Files

| File | Change |
|---|---|
| `app/src/main/java/com/u1/slicer/network/MoonrakerClient.kt` | Add `wakeCamera()`; append `monitor.jpg` to candidates |
| `app/src/main/java/com/u1/slicer/printer/PrinterRepository.kt` | Add `wakeCamera()` wrapper |
| `app/src/main/java/com/u1/slicer/printer/PrinterViewModel.kt` | Add `startCameraKeepalive()`, `stopCameraKeepalive()`, `Job` field |
| `app/src/main/java/com/u1/slicer/ui/PrinterScreen.kt` | Add `DisposableEffect` around camera card |
| `app/src/test/java/com/u1/slicer/network/MoonrakerClientTest.kt` | 2 new unit tests |

---

## Task 1: Unit Tests for monitor.jpg Candidate Appending (Red)

**Files:**
- Modify: `app/src/test/java/com/u1/slicer/network/MoonrakerClientTest.kt`

- [ ] **Step 1: Add the two failing tests**

Open `MoonrakerClientTest.kt` and add these two tests at the end of the class, before the closing `}`:

```kotlin
@Test
fun `queryWebcamSnapshotCandidates with empty webcam list appends monitor jpg as last candidate`() = runTest {
    val server = MockWebServer()
    server.enqueue(MockResponse().setBody("""{"result":{"webcams":[]}}"""))
    server.start()
    val client = MoonrakerClient()
    client.baseUrl = server.url("/").toString()
    val candidates = client.queryWebcamSnapshotCandidates()
    assertTrue("Expected at least 2 candidates", candidates.size >= 2)
    assertTrue(
        "Last candidate must be monitor.jpg, got: ${candidates.last()}",
        candidates.last().endsWith("/server/files/camera/monitor.jpg")
    )
    server.shutdown()
}

@Test
fun `queryWebcamSnapshotCandidates with configured webcam appends monitor jpg after existing candidates`() = runTest {
    val server = MockWebServer()
    server.enqueue(MockResponse().setBody("""
        {"result":{"webcams":[{
            "name":"Camera","enabled":true,
            "snapshot_url":"/webcam/?action=snapshot",
            "stream_url":"/webcam/?action=stream"
        }]}}
    """.trimIndent()))
    server.start()
    val client = MoonrakerClient()
    client.baseUrl = server.url("/").toString()
    val candidates = client.queryWebcamSnapshotCandidates()
    assertTrue("Expected at least 2 candidates", candidates.size >= 2)
    assertTrue(
        "Last candidate must be monitor.jpg, got: ${candidates.last()}",
        candidates.last().endsWith("/server/files/camera/monitor.jpg")
    )
    assertFalse(
        "First candidate must not be monitor.jpg",
        candidates.first().contains("monitor.jpg")
    )
    server.shutdown()
}
```

- [ ] **Step 2: Run the tests and confirm they fail**

```bash
./gradlew testDebugUnitTest --tests "com.u1.slicer.network.MoonrakerClientTest" --no-daemon
```

Expected: 2 failures — `queryWebcamSnapshotCandidates` doesn't append monitor.jpg yet.

---

## Task 2: Implement monitor.jpg Appending + wakeCamera() in MoonrakerClient (Green)

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/network/MoonrakerClient.kt`

- [ ] **Step 1: Add WebSocket imports**

Add these imports near the top of `MoonrakerClient.kt` alongside the existing `okhttp3.*` import (they're already covered by the wildcard, but verify `WebSocket` and `WebSocketListener` resolve — if not, add explicitly):

```kotlin
import okhttp3.WebSocket
import okhttp3.WebSocketListener
```

- [ ] **Step 2: Append monitor.jpg to queryWebcamSnapshotCandidates()**

The current method returns early on the `candidates` path and falls through to the legacy path. Update both return points to append `monitor.jpg`. Replace the entire `queryWebcamSnapshotCandidates` function body (lines 40–74 in the original) with:

```kotlin
suspend fun queryWebcamSnapshotCandidates(): List<String> = withContext(Dispatchers.IO) {
    if (baseUrl.isBlank()) return@withContext emptyList()
    val monitorUrl = "$baseUrl/server/files/camera/monitor.jpg"
    try {
        val request = Request.Builder().url(url("/server/webcams/list")).get().build()
        val response = client.newCall(request).execute()
        val body = response.body?.string()
        response.close()
        if (response.isSuccessful && body != null) {
            val webcams = org.json.JSONObject(body)
                .getJSONObject("result")
                .getJSONArray("webcams")
            if (webcams.length() > 0) {
                val cam = webcams.getJSONObject(0)
                val rawUrl = cam.optString("snapshot_url", "")
                    .ifBlank { cam.optString("snapshotUrl", "") }
                if (rawUrl.isNotBlank()) {
                    val primary = resolveWebcamUrl(rawUrl, keepPort = false)
                    val alt = resolveWebcamUrl(rawUrl, keepPort = true)
                    val candidates = listOfNotNull(
                        primary.ifBlank { null },
                        alt.takeIf { it.isNotBlank() && it != primary }
                    )
                    if (candidates.isNotEmpty()) {
                        Log.d(TAG, "Webcam candidates: $candidates")
                        return@withContext candidates + monitorUrl
                    }
                }
            }
        }
    } catch (e: Exception) {
        Log.d(TAG, "Webcam list unavailable, using legacy fallback: ${e.message}")
    }
    listOf("$baseUrl/webcam/?action=snapshot", monitorUrl)
}
```

- [ ] **Step 3: Add wakeCamera()**

Add this new function directly after `queryWebcamSnapshotCandidates` and before `resolveWebcamUrl`:

```kotlin
suspend fun wakeCamera() = withContext(Dispatchers.IO) {
    if (baseUrl.isBlank()) return@withContext
    try {
        val wsUrl = baseUrl.replace("http://", "ws://").replace("https://", "wss://") + "/websocket"
        val request = Request.Builder().url(wsUrl).build()
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"jsonrpc":"2.0","id":1000,"method":"camera.start_monitor","params":{"domain":"lan","interval":0}}""")
                webSocket.close(1000, null)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.d(TAG, "wakeCamera failed: ${t.message}")
            }
        })
    } catch (e: Exception) {
        Log.d(TAG, "wakeCamera failed: ${e.message}")
    }
}
```

- [ ] **Step 4: Run the unit tests and confirm they pass**

```bash
./gradlew testDebugUnitTest --tests "com.u1.slicer.network.MoonrakerClientTest" --no-daemon
```

Expected: all `MoonrakerClientTest` tests pass (including the 2 new ones).

- [ ] **Step 5: Run full unit test suite to check for regressions**

```bash
./gradlew testDebugUnitTest --no-daemon
```

Expected: all 819 tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/u1/slicer/network/MoonrakerClient.kt
git add app/src/test/java/com/u1/slicer/network/MoonrakerClientTest.kt
git commit -m "feat(camera): wake camera keepalive + direct monitor.jpg candidate"
```

---

## Task 3: Keepalive Lifecycle in PrinterRepository and PrinterViewModel

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/printer/PrinterRepository.kt:127`
- Modify: `app/src/main/java/com/u1/slicer/printer/PrinterViewModel.kt`

- [ ] **Step 1: Add wakeCamera() wrapper to PrinterRepository**

In `PrinterRepository.kt`, find the line:

```kotlin
suspend fun queryWebcamSnapshotCandidates(): List<String> = client.queryWebcamSnapshotCandidates()
```

Add directly after it:

```kotlin
suspend fun wakeCamera() = client.wakeCamera()
```

- [ ] **Step 2: Add keepalive Job field and functions to PrinterViewModel**

In `PrinterViewModel.kt`, add the `Job` field after the existing `private val _heaterError` declaration (around line 57):

```kotlin
private var cameraKeepaliveJob: Job? = null
```

Then add the two public functions before `clearHeaterError()`:

```kotlin
fun startCameraKeepalive() {
    if (cameraKeepaliveJob?.isActive == true) return
    cameraKeepaliveJob = viewModelScope.launch(Dispatchers.IO) {
        while (true) {
            printerRepo.wakeCamera()
            delay(2000)
        }
    }
}

fun stopCameraKeepalive() {
    cameraKeepaliveJob?.cancel()
    cameraKeepaliveJob = null
}
```

Add these imports to `PrinterViewModel.kt` if not already present (they are not in the existing import list):

```kotlin
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
```

- [ ] **Step 3: Stop keepalive in onCleared**

In `PrinterViewModel.kt`, update `onCleared()` to also stop the keepalive:

```kotlin
override fun onCleared() {
    super.onCleared()
    stopCameraKeepalive()
    printerRepo.stopPolling()
}
```

- [ ] **Step 4: Run unit tests to verify no regressions**

```bash
./gradlew testDebugUnitTest --no-daemon
```

Expected: all 819 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/printer/PrinterRepository.kt
git add app/src/main/java/com/u1/slicer/printer/PrinterViewModel.kt
git commit -m "feat(camera): keepalive lifecycle in PrinterRepository and PrinterViewModel"
```

---

## Task 4: DisposableEffect in PrinterScreen

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/ui/PrinterScreen.kt`

- [ ] **Step 1: Add DisposableEffect for keepalive lifecycle**

In `PrinterScreen.kt`, find the block of `remember`/`LaunchedEffect` state at the top of the composable body (around lines 88–122, where `cameraFrame`, `candidateIndex`, and the polling `LaunchedEffect` live). Add the `DisposableEffect` immediately after the `LaunchedEffect(webcamCandidates)` polling block (after line 122):

```kotlin
DisposableEffect(Unit) {
    viewModel.startCameraKeepalive()
    onDispose { viewModel.stopCameraKeepalive() }
}
```

`DisposableEffect` is available from `androidx.compose.runtime.*` which is already imported.

- [ ] **Step 2: Build and install**

```bash
./gradlew installDebug --no-daemon
```

Expected: builds and installs without errors.

- [ ] **Step 3: Smoke test on device**

1. Open the app and navigate to the Printer screen with the printer connected.
2. If the camera was asleep, it should appear within ~3 seconds (no workaround server running).
3. Navigate away from the Printer screen and back — camera wakes again within ~3 seconds.
4. Confirm the rest of the Printer screen (status, temps, controls) still works normally.

- [ ] **Step 4: Run full unit test suite**

```bash
./gradlew testDebugUnitTest --no-daemon
```

Expected: all 819 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/ui/PrinterScreen.kt
git commit -m "feat(camera): start/stop keepalive with PrinterScreen lifecycle"
```
