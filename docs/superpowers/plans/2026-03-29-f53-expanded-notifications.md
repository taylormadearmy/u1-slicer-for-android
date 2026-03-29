# F53: Expanded Notification Coverage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fire one-shot Android notifications for key lifecycle events (model loaded, slice complete/failed, upload complete, print started/paused/complete/failed, printer offline) when the app is backgrounded.

**Architecture:** New `AppEventNotifier` object handles channel creation and notification posting for all new events. A `AppForegroundTracker` singleton (backed by `ProcessLifecycleOwner`) gates notifications to background-only. `PrinterRepository` polling loop tracks printer state transitions. `SlicerViewModel` observes its own state flow and fires slice notifications. Deep-link `PendingIntent`s carry a `EXTRA_NAVIGATE_TO` string extra; `MainActivity.onNewIntent` reads it and invokes the existing `navigateCallback`.

**Tech Stack:** Kotlin, Android NotificationManager, AndroidX Lifecycle (`ProcessLifecycleOwner`), Coroutines

---

### Task 1: AppForegroundTracker

**Files:**
- Create: `app/src/main/java/com/u1/slicer/AppForegroundTracker.kt`
- Modify: `app/src/main/java/com/u1/slicer/U1SlicerApplication.kt` (or wherever `Application.onCreate` is)

- [ ] **Step 1: Create AppForegroundTracker**

```kotlin
// app/src/main/java/com/u1/slicer/AppForegroundTracker.kt
package com.u1.slicer

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

object AppForegroundTracker : DefaultLifecycleObserver {
    @Volatile
    var isInForeground: Boolean = false
        private set

    fun register() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) { isInForeground = true }
    override fun onStop(owner: LifecycleOwner) { isInForeground = false }
}
```

- [ ] **Step 2: Register in Application.onCreate**

Find `app/src/main/java/com/u1/slicer/U1SlicerApplication.kt` (or the class that extends `Application`). In `onCreate()`:

```kotlin
override fun onCreate() {
    super.onCreate()
    AppForegroundTracker.register()
    // ... existing onCreate code ...
}
```

- [ ] **Step 3: Compile check**

```bash
./gradlew assembleDebug --no-daemon 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/u1/slicer/AppForegroundTracker.kt \
        app/src/main/java/com/u1/slicer/U1SlicerApplication.kt
git commit -m "feat(F53): add AppForegroundTracker for background notification gating"
```

---

### Task 2: AppEventNotifier

**Files:**
- Create: `app/src/main/java/com/u1/slicer/AppEventNotifier.kt`
- Test: `app/src/test/java/com/u1/slicer/AppEventNotifierTest.kt`

- [ ] **Step 1: Write failing tests for title/body generation**

```kotlin
// app/src/test/java/com/u1/slicer/AppEventNotifierTest.kt
package com.u1.slicer

import org.junit.Assert.assertEquals
import org.junit.Test

class AppEventNotifierTest {

    @Test
    fun `slice complete title and body`() {
        assertEquals("Slice complete", AppEventNotifier.titleFor(AppEventNotifier.Event.SliceComplete("model.3mf")))
        assertEquals("model.3mf is ready to send to printer",
            AppEventNotifier.bodyFor(AppEventNotifier.Event.SliceComplete("model.3mf")))
    }

    @Test
    fun `slice failed title and body`() {
        assertEquals("Slice failed", AppEventNotifier.titleFor(AppEventNotifier.Event.SliceFailed("Out of memory")))
        assertEquals("Out of memory", AppEventNotifier.bodyFor(AppEventNotifier.Event.SliceFailed("Out of memory")))
    }

    @Test
    fun `slice failed truncates long error message`() {
        val longMsg = "A".repeat(150)
        assertEquals(100, AppEventNotifier.bodyFor(AppEventNotifier.Event.SliceFailed(longMsg)).length)
    }

    @Test
    fun `model loaded title and body`() {
        assertEquals("Model ready", AppEventNotifier.titleFor(AppEventNotifier.Event.ModelLoaded("dragon.stl")))
        assertEquals("dragon.stl loaded and ready to slice",
            AppEventNotifier.bodyFor(AppEventNotifier.Event.ModelLoaded("dragon.stl")))
    }

    @Test
    fun `upload complete title and body`() {
        assertEquals("Upload complete", AppEventNotifier.titleFor(AppEventNotifier.Event.UploadComplete("print.gcode")))
        assertEquals("print.gcode sent to printer",
            AppEventNotifier.bodyFor(AppEventNotifier.Event.UploadComplete("print.gcode")))
    }

    @Test
    fun `print started title and body`() {
        assertEquals("Print started", AppEventNotifier.titleFor(AppEventNotifier.Event.PrintStarted("print.gcode")))
        assertEquals("print.gcode", AppEventNotifier.bodyFor(AppEventNotifier.Event.PrintStarted("print.gcode")))
    }

    @Test
    fun `print paused title and body`() {
        assertEquals("Print paused", AppEventNotifier.titleFor(AppEventNotifier.Event.PrintPaused("print.gcode", 42)))
        assertEquals("print.gcode paused at 42%",
            AppEventNotifier.bodyFor(AppEventNotifier.Event.PrintPaused("print.gcode", 42)))
    }

    @Test
    fun `print complete title and body`() {
        assertEquals("Print complete", AppEventNotifier.titleFor(AppEventNotifier.Event.PrintComplete("print.gcode")))
        assertEquals("print.gcode finished",
            AppEventNotifier.bodyFor(AppEventNotifier.Event.PrintComplete("print.gcode")))
    }

    @Test
    fun `print failed title and body`() {
        assertEquals("Print stopped", AppEventNotifier.titleFor(AppEventNotifier.Event.PrintFailed("print.gcode")))
        assertEquals("print.gcode was cancelled or failed",
            AppEventNotifier.bodyFor(AppEventNotifier.Event.PrintFailed("print.gcode")))
    }

    @Test
    fun `printer offline title and body`() {
        assertEquals("Printer offline", AppEventNotifier.titleFor(AppEventNotifier.Event.PrinterOffline))
        assertEquals("Lost connection during print", AppEventNotifier.bodyFor(AppEventNotifier.Event.PrinterOffline))
    }

    @Test
    fun `navigate target for slice complete is preview`() {
        assertEquals("preview", AppEventNotifier.navigateTargetFor(AppEventNotifier.Event.SliceComplete("x")))
    }

    @Test
    fun `navigate target for print paused is printer`() {
        assertEquals("printer", AppEventNotifier.navigateTargetFor(AppEventNotifier.Event.PrintPaused("x", 0)))
    }

    @Test
    fun `navigate target for model loaded is null`() {
        assertEquals(null, AppEventNotifier.navigateTargetFor(AppEventNotifier.Event.ModelLoaded("x")))
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./gradlew testDebugUnitTest --tests "*.AppEventNotifierTest" --no-daemon 2>&1 | tail -15
```
Expected: FAILED — `AppEventNotifier` not defined.

- [ ] **Step 3: Create AppEventNotifier**

```kotlin
// app/src/main/java/com/u1/slicer/AppEventNotifier.kt
package com.u1.slicer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object AppEventNotifier {

    private const val CHANNEL_SLICE = "slice_events"
    private const val CHANNEL_PRINTER = "printer_events"
    private const val ID_SLICE = 10
    private const val ID_PRINTER = 11
    const val EXTRA_NAVIGATE_TO = "navigate_to"

    sealed class Event {
        data class ModelLoaded(val filename: String) : Event()
        data class SliceComplete(val filename: String) : Event()
        data class SliceFailed(val error: String) : Event()
        data class UploadComplete(val filename: String) : Event()
        data class PrintStarted(val filename: String) : Event()
        data class PrintPaused(val filename: String, val progress: Int) : Event()
        data class PrintComplete(val filename: String) : Event()
        data class PrintFailed(val filename: String) : Event()
        object PrinterOffline : Event()
    }

    fun notify(context: Context, event: Event) {
        if (AppForegroundTracker.isInForeground) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return

        val channelId = channelFor(event)
        createChannels(context)
        val notifId = if (event is Event.ModelLoaded || event is Event.SliceComplete || event is Event.SliceFailed) ID_SLICE else ID_PRINTER
        val navigateTo = navigateTargetFor(event)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (navigateTo != null) putExtra(EXTRA_NAVIGATE_TO, navigateTo)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, notifId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        NotificationManagerCompat.from(context).notify(
            notifId,
            NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle(titleFor(event))
                .setContentText(bodyFor(event))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
        )
    }

    internal fun titleFor(event: Event): String = when (event) {
        is Event.ModelLoaded -> "Model ready"
        is Event.SliceComplete -> "Slice complete"
        is Event.SliceFailed -> "Slice failed"
        is Event.UploadComplete -> "Upload complete"
        is Event.PrintStarted -> "Print started"
        is Event.PrintPaused -> "Print paused"
        is Event.PrintComplete -> "Print complete"
        is Event.PrintFailed -> "Print stopped"
        is Event.PrinterOffline -> "Printer offline"
    }

    internal fun bodyFor(event: Event): String = when (event) {
        is Event.ModelLoaded -> "${event.filename} loaded and ready to slice"
        is Event.SliceComplete -> "${event.filename} is ready to send to printer"
        is Event.SliceFailed -> event.error.take(100)
        is Event.UploadComplete -> "${event.filename} sent to printer"
        is Event.PrintStarted -> event.filename
        is Event.PrintPaused -> "${event.filename} paused at ${event.progress}%"
        is Event.PrintComplete -> "${event.filename} finished"
        is Event.PrintFailed -> "${event.filename} was cancelled or failed"
        is Event.PrinterOffline -> "Lost connection during print"
    }

    internal fun navigateTargetFor(event: Event): String? = when (event) {
        is Event.SliceComplete -> "preview"
        is Event.PrintComplete -> "preview"
        is Event.PrintStarted -> "printer"
        is Event.PrintPaused -> "printer"
        is Event.PrintFailed -> "printer"
        is Event.PrinterOffline -> "printer"
        else -> null
    }

    private fun channelFor(event: Event): String = when (event) {
        is Event.ModelLoaded, is Event.SliceComplete, is Event.SliceFailed -> CHANNEL_SLICE
        else -> CHANNEL_PRINTER
    }

    private fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        listOf(
            NotificationChannel(CHANNEL_SLICE, "Slice events", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Notifications for model loading and slicing" },
            NotificationChannel(CHANNEL_PRINTER, "Printer events", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Notifications for printer upload, print progress, and completion" }
        ).forEach { channel ->
            if (manager.getNotificationChannel(channel.id) == null) manager.createNotificationChannel(channel)
        }
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
./gradlew testDebugUnitTest --tests "*.AppEventNotifierTest" --no-daemon 2>&1 | tail -15
```
Expected: all AppEventNotifierTest tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/AppEventNotifier.kt \
        app/src/test/java/com/u1/slicer/AppEventNotifierTest.kt
git commit -m "feat(F53): add AppEventNotifier with all event types, channels, and deep-link intents"
```

---

### Task 3: Handle deep-link navigation in MainActivity

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/MainActivity.kt`

The existing `navigateCallback` (set via `viewModel.setNavigateCallback`) already handles navigating by route string. We just need to read `EXTRA_NAVIGATE_TO` from the intent and invoke it.

- [ ] **Step 1: Read the extra in `onNewIntent` and in `onCreate` after the navController is ready**

Find `override fun onNewIntent(intent: Intent)` (around line 80). After `super.onNewIntent(intent)`, add before the existing file-handling code:

```kotlin
intent.getStringExtra(AppEventNotifier.EXTRA_NAVIGATE_TO)?.let { route ->
    viewModel.setNavigateCallback?.invoke { cb -> cb(route) }
    // Clear so it doesn't re-fire on config change
    intent.removeExtra(AppEventNotifier.EXTRA_NAVIGATE_TO)
}
```

Also handle cold-start (app not running): in `onCreate`, after `setNavigateCallback` is wired (around line 394), add:

```kotlin
getIntent().getStringExtra(AppEventNotifier.EXTRA_NAVIGATE_TO)?.let { route ->
    // Defer until navController is ready via the existing LaunchedEffect
    pendingNavigateTo = route
    intent.removeExtra(AppEventNotifier.EXTRA_NAVIGATE_TO)
}
```

Add `var pendingNavigateTo: String? = null` as an Activity-level field. Inside the `LaunchedEffect(navController)` that wires up `navigateCallback` (around line 423), consume it:

```kotlin
LaunchedEffect(navController) {
    viewModel.setNavigateCallback?.invoke { route -> navigateTab(route) }
    pendingNavigateTo?.let { navigateTab(it); pendingNavigateTo = null }
}
```

- [ ] **Step 2: Compile check**

```bash
./gradlew assembleDebug --no-daemon 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/u1/slicer/MainActivity.kt
git commit -m "feat(F53): handle EXTRA_NAVIGATE_TO deep-link extra in MainActivity"
```

---

### Task 4: Fire slice notifications from SlicerViewModel

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`

`SlicerViewModel` is an `AndroidViewModel` so it has `getApplication<Application>()` for context.

- [ ] **Step 1: Add state transition observation in the ViewModel init block**

`SlicerViewModel` already has a `_state` `MutableStateFlow`. Add a coroutine in `init { }` that watches for transitions:

```kotlin
init {
    // ... existing init code ...
    viewModelScope.launch {
        var prevState: SlicerState = SlicerState.Idle
        state.collect { newState ->
            val ctx = getApplication<android.app.Application>()
            when {
                prevState is SlicerState.Loading && newState is SlicerState.ModelLoaded -> {
                    val filename = (newState as SlicerState.ModelLoaded).info.filename
                    AppEventNotifier.notify(ctx, AppEventNotifier.Event.ModelLoaded(filename))
                }
                prevState is SlicerState.Slicing && newState is SlicerState.SliceComplete -> {
                    val filename = currentModelPath?.substringAfterLast("/") ?: "model"
                    AppEventNotifier.notify(ctx, AppEventNotifier.Event.SliceComplete(filename))
                }
                prevState is SlicerState.Slicing && newState is SlicerState.Error -> {
                    AppEventNotifier.notify(ctx, AppEventNotifier.Event.SliceFailed(
                        (newState as SlicerState.Error).message))
                }
            }
            prevState = newState
        }
    }
}
```

Note: `currentModelPath` is an existing property on `SlicerViewModel` — verify its name by searching for `currentModelPath\|modelPath\|loadedPath` in `SlicerViewModel.kt`. Use whatever the actual property is named.

- [ ] **Step 2: Fire upload complete notification**

Find the `uploadOnly` call site in `SlicerViewModel` (search for `uploadOnly`). After a successful upload:

```kotlin
val ok = printerRepository.uploadOnly(gcodeFile, filename)
if (ok) {
    AppEventNotifier.notify(
        getApplication(),
        AppEventNotifier.Event.UploadComplete(gcodeFile.name)
    )
}
```

- [ ] **Step 3: Compile check**

```bash
./gradlew assembleDebug --no-daemon 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/u1/slicer/SlicerViewModel.kt
git commit -m "feat(F53): fire slice and upload notifications from SlicerViewModel"
```

---

### Task 5: Fire printer state transition notifications from PrinterRepository

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/printer/PrinterRepository.kt`
- Test: `app/src/test/java/com/u1/slicer/printer/PrinterRepositoryNotificationTest.kt`

- [ ] **Step 1: Write failing tests for transition detection**

```kotlin
// app/src/test/java/com/u1/slicer/printer/PrinterRepositoryNotificationTest.kt
package com.u1.slicer.printer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrinterRepositoryNotificationTest {

    @Test
    fun `idle to printing is PrintStarted`() {
        val event = PrinterRepository.detectTransition("idle", "printing", "job.gcode", 10)
        assertEquals("PrintStarted", event?.javaClass?.simpleName)
    }

    @Test
    fun `printing to paused is PrintPaused`() {
        val event = PrinterRepository.detectTransition("printing", "paused", "job.gcode", 45)
        assertEquals("PrintPaused", event?.javaClass?.simpleName)
    }

    @Test
    fun `printing to complete is PrintComplete`() {
        val event = PrinterRepository.detectTransition("printing", "complete", "job.gcode", 100)
        assertEquals("PrintComplete", event?.javaClass?.simpleName)
    }

    @Test
    fun `printing to error is PrintFailed`() {
        val event = PrinterRepository.detectTransition("printing", "error", "job.gcode", 50)
        assertEquals("PrintFailed", event?.javaClass?.simpleName)
    }

    @Test
    fun `printing to cancelled is PrintFailed`() {
        val event = PrinterRepository.detectTransition("printing", "cancelled", "job.gcode", 50)
        assertEquals("PrintFailed", event?.javaClass?.simpleName)
    }

    @Test
    fun `printing to disconnected is PrinterOffline`() {
        val event = PrinterRepository.detectTransition("printing", "disconnected", "job.gcode", 50)
        assertEquals("PrinterOffline", event?.javaClass?.simpleName)
    }

    @Test
    fun `paused to disconnected is PrinterOffline`() {
        val event = PrinterRepository.detectTransition("paused", "disconnected", "job.gcode", 50)
        assertEquals("PrinterOffline", event?.javaClass?.simpleName)
    }

    @Test
    fun `idle to idle is null`() {
        assertNull(PrinterRepository.detectTransition("idle", "idle", "", 0))
    }

    @Test
    fun `disconnected to printing is PrintStarted`() {
        val event = PrinterRepository.detectTransition("disconnected", "printing", "job.gcode", 0)
        assertEquals("PrintStarted", event?.javaClass?.simpleName)
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./gradlew testDebugUnitTest --tests "*.PrinterRepositoryNotificationTest" --no-daemon 2>&1 | tail -15
```
Expected: FAILED — `detectTransition` not defined.

- [ ] **Step 3: Add `detectTransition` companion function and wire it into the polling loop**

In `PrinterRepository.kt`, add inside the companion object (or create one):

```kotlin
companion object {
    // ... existing buildPrinterUploadFilename ...

    internal fun detectTransition(
        prev: String,
        curr: String,
        filename: String,
        progress: Int
    ): com.u1.slicer.AppEventNotifier.Event? {
        val activePrev = prev == "printing" || prev == "paused"
        return when {
            curr == "printing" && prev != "printing" ->
                com.u1.slicer.AppEventNotifier.Event.PrintStarted(filename)
            curr == "paused" && prev == "printing" ->
                com.u1.slicer.AppEventNotifier.Event.PrintPaused(filename, progress)
            curr == "complete" && activePrev ->
                com.u1.slicer.AppEventNotifier.Event.PrintComplete(filename)
            (curr == "error" || curr == "cancelled") && activePrev ->
                com.u1.slicer.AppEventNotifier.Event.PrintFailed(filename)
            curr == "disconnected" && activePrev ->
                com.u1.slicer.AppEventNotifier.Event.PrinterOffline
            else -> null
        }
    }
}
```

In the `startPolling` loop, add transition tracking:

```kotlin
fun startPolling(scope: CoroutineScope) {
    stopPolling()
    pollingJob = scope.launch(Dispatchers.IO) {
        var prevState = "disconnected"
        while (isActive) {
            val latestStatus = client.getStatus()
            val event = detectTransition(
                prevState, latestStatus.state,
                latestStatus.filename, latestStatus.progressPercent
            )
            event?.let { com.u1.slicer.AppEventNotifier.notify(appContext, it) }
            prevState = latestStatus.state
            _status.value = latestStatus
            PrintProgressNotifier.update(appContext, latestStatus)
            val interval = if (rapidPollCyclesRemaining > 0) {
                rapidPollCyclesRemaining--
                500L
            } else 2000L
            delay(interval)
        }
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
./gradlew testDebugUnitTest --tests "*.PrinterRepositoryNotificationTest" --no-daemon 2>&1 | tail -15
```
Expected: all PrinterRepositoryNotificationTest tests PASS.

- [ ] **Step 5: Run full unit test suite**

```bash
./gradlew testDebugUnitTest --no-daemon 2>&1 | tail -10
```
Expected: all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/u1/slicer/printer/PrinterRepository.kt \
        app/src/test/java/com/u1/slicer/printer/PrinterRepositoryNotificationTest.kt
git commit -m "feat(F53): fire printer state transition notifications from PrinterRepository polling loop"
```

---

### Task 6: Update CLAUDE.md test counts and do a final build

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Count new tests**

New test classes added:
- `AppEventNotifierTest.kt` — 12 tests
- `PrinterRepositoryNotificationTest.kt` — 9 tests

Total new unit tests: 21. Add to existing count (534 after F49/F50/F51 add none) → **555 tests across 35 classes**.

- [ ] **Step 2: Update CLAUDE.md**

In `CLAUDE.md`, update:
```
./gradlew testDebugUnitTest                        # 555 JVM unit tests
```
and:
```
### Unit tests (`app/src/test/`) - 555 tests across 35 classes
```

Add entries for the new test classes in the unit test list:
```
- `AppEventNotifierTest.kt` (12) — notification title/body/channel/navigate-target for all event types
- `printer/PrinterRepositoryNotificationTest.kt` (9) — printer state transition detection
```

- [ ] **Step 3: Final build**

```bash
./gradlew testDebugUnitTest --no-daemon 2>&1 | tail -10
./gradlew assembleDebug --no-daemon 2>&1 | tail -5
```
Expected: all tests PASS, BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: update test counts for F53 (AppEventNotifier + PrinterRepositoryNotification)"
```
