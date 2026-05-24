# F90 — Foreground-service coverage for all long-running operations — Design

**Status:** Draft, 2026-05-24
**Issue:** [GitHub #154](https://github.com/taylormadearmy/u1-slicer-for-android/issues/154)
**Scope:** v2.6.0 → v2.7.0 (versionCode 295 → 296). Pure Kotlin / Service / Notifications.
**Out of scope:** Native rebuild. Changes to the slicer pipeline. Changes to F89's session-restore logic.

## TL;DR

Today only `startSlicing` is wrapped in a foreground service (`SlicingService.kt`). Model loading
(Buzz cold = 90 s), the Bambu sanitize+embed pipeline (5–30 s on multi-plate files), plate
selection (re-embed + native reload), Add-to-bed model loads, and Prepare preview mesh
generation all run without foreground-service kill protection. Android can kill the process
during any of them. F81's notifications fire on completion only — they're informational
`notify()` calls, not foreground-service ongoing notifications, so they offer zero kill protection.

F90 generalises `SlicingService` into `LongOpService`, a stack-based foreground service that
renders the most-specific in-flight stage label and survives Android low-memory pressure. The
existing slicing path is refactored to use the same infrastructure (no behaviour change). Each
long-running ViewModel entry point is wrapped with a single `withLongOp(stage) { ... }` helper
that guarantees start/stop pairing through try/finally.

F89's Resume banner stays as the safety net for when Android still kills the process under
extreme pressure. F90 reduces how often that path triggers.

## User-facing behaviour

### Cold-loading Buzz with the app backgrounded

1. User opens a multi-plate Bambu file (Buzz, 90 s cold-load).
2. The "Loading Buzz Lightyear.3mf..." notification appears in the shade immediately, ongoing,
   silent, low-importance, with an `ic_popup_sync` icon.
3. User taps Home / opens another app.
4. Android sees a foreground service with `specialUse` declaration; the U1 Slicer process is
   exempt from the standard background-process kill heuristic for the duration.
5. Load completes. Notification disappears. If F81 completion notification is enabled, it shows
   per existing F81 behaviour.

### Plate selection on the same file

6. User taps plate 8. State transitions to `Loading("Loading plate 8…")`. Notification updates
   to "Loading plate 8...".
7. When plate selection finishes the notification disappears (replaced by F81 completion
   notification if enabled).

### Add-to-bed

8. User loads a second STL via Add-to-bed. Notification shows "Adding model..." for the duration
   of the JNI `addModel` call.

### Slicing (unchanged behaviour, refactored under the hood)

9. User taps Slice. Notification shows "Slicing... 47%". Progress updates fire from the native
   progress listener. This is identical to today's `SlicingService` output — the migration to
   `LongOpService` is purely internal.

### Nested operations

10. If `loadModelFromFile` wraps "Loading model..." and the inner Bambu pipeline pushes
    "Preparing model...", the notification shows the inner stage during that window, then
    reverts to "Loading model..." when the inner pop happens.

In practice F90 only nests at one place — `prepareImportedModelArtifacts` is the inner step of a
`loadModelFromFile` outer wrap. Adding a deeper nest later just works.

### What the user does NOT see

- No notification flicker on cached `getPreparePreviewMesh` fetches. Sub-second operations are
  not wrapped — we intentionally skip the preview path for now. The 30 s "first-fetch on a
  300 k-triangle mesh" case is rare in practice and is already covered indirectly because it
  fires from inside `loadNativeModel`, which is itself called from a wrapped `loadModelFromFile`
  / `selectPlate`.
- No notification on the F89 session-resume restoreSession path that is already wrapped:
  `restoreSession` calls `loadModelFromFile` and `selectPlate`, both of which wrap themselves.
  One notification surfaces per nested call. No special handling required.

## Architecture

### `LongOpService` (renamed from `SlicingService`)

`app/src/main/java/com/u1/slicer/LongOpService.kt`. Replaces `SlicingService.kt`.

- **One notification channel**: `"long_op_progress"`, importance `LOW`, name "Background work",
  description "Shows progress while preparing, loading or slicing models".
- **One notification ID**: `NOTIFICATION_ID = 1` (unchanged from the existing SlicingService).
- **Foreground service type**: `specialUse`, subtype `"3D model preparation, loading and slicing"`.
  Chosen because the existing SlicingService already uses `specialUse` (sideloaded APK; not subject
  to Google Play subtype review), and broadening the subtype text covers the new wrap points
  without changing the manifest type. `dataSync` was considered but Google deprecated it for
  generic-CPU use cases in Android 15.
- **Stack semantics**: a `companion object` holds an `ArrayDeque<String>` (the stage stack)
  guarded by an explicit lock. `start(context, stage)` pushes; `stop(context)` pops the top
  entry. The Service receives a START intent on every push and pop, carrying the **current
  top-of-stack** as the stage extra. If the stack becomes empty on pop, the START intent has
  `ACTION_STOP`, which transitions the service out of foreground and stops it.
- **Update path**: `update(context, progress, stage?)` writes to the notification. When the
  caller supplies a `stage`, it is treated as a "rename top of stack" — it replaces the top
  entry rather than pushing a new one. This is used by the slicing progress listener: each
  progress tick passes the latest stage string from the native callback (e.g. `"Generating
  layers"`), which becomes the new top-of-stack value and the notification updates accordingly.

#### Stack semantics in Kotlin

```kotlin
companion object {
    private val stackLock = Any()
    private val stageStack = ArrayDeque<String>()

    fun start(context: Context, stage: String) {
        synchronized(stackLock) { stageStack.addLast(stage) }
        sendIntent(context, action = ACTION_UPDATE, stage = currentStageOrNull(), progress = 0)
    }

    fun update(context: Context, progress: Int, stage: String? = null) {
        if (stage != null) {
            synchronized(stackLock) {
                if (stageStack.isNotEmpty()) {
                    stageStack.removeLast()
                    stageStack.addLast(stage)
                } else {
                    stageStack.addLast(stage)
                }
            }
        }
        sendIntent(context, action = ACTION_UPDATE, stage = currentStageOrNull(), progress = progress)
    }

    fun stop(context: Context) {
        val top = synchronized(stackLock) {
            if (stageStack.isNotEmpty()) stageStack.removeLast()
            currentStageOrNull()
        }
        if (top == null) {
            sendIntent(context, action = ACTION_STOP, stage = null, progress = 0)
        } else {
            sendIntent(context, action = ACTION_UPDATE, stage = top, progress = 0)
        }
    }

    private fun currentStageOrNull(): String? =
        synchronized(stackLock) { stageStack.lastOrNull() }
}
```

The Service `onStartCommand` reads `EXTRA_STAGE` and `EXTRA_PROGRESS`, and either renders the
ongoing notification with the supplied label/progress or, on `ACTION_STOP`, calls
`stopForeground(STOP_FOREGROUND_REMOVE)` and `stopSelf()`. The Service itself is stateless —
all stack state lives in the companion. (The Service-and-companion live in the same process,
so the companion-side stack is reliable.)

### ViewModel helper

`SlicerViewModel.kt` gets a single new private helper:

```kotlin
private inline fun <T> withLongOp(stage: String, block: () -> T): T {
    val context = getApplication<Application>()
    LongOpService.start(context, stage)
    return try {
        block()
    } finally {
        LongOpService.stop(context)
    }
}

private suspend inline fun <T> withLongOpSuspend(stage: String, crossinline block: suspend () -> T): T {
    val context = getApplication<Application>()
    LongOpService.start(context, stage)
    return try {
        block()
    } finally {
        LongOpService.stop(context)
    }
}
```

Two flavours because some sites are suspend, others are not.

### Wrap points

| Site | Stage label | Where the wrap goes |
|---|---|---|
| `loadModel(uri: Uri)` | `"Loading model"` | Inside the existing `viewModelScope.launch(Dispatchers.IO) { try { ... } catch { ... } }`. Wrap the entire body. |
| `loadModelFromFile(file, ...)` | `"Loading model"` | Same as above. |
| `selectPlate(plateId, silent)` | `"Loading plate ${plateId}"` | Wrap the `selectPlateJob = viewModelScope.launch(Dispatchers.IO) { try { ... } finally { ... } }` body. |
| `addModelFromFile(file)` | `"Adding model"` | Wrap the inner `try { doAddFile(...) } catch { ... }` body. |
| `addModelFromFileForPlate(file, plateIdx)` | `"Adding model"` | Same as above. |
| `confirmAddPlate(plateId)` | `"Adding model"` | Same. |
| `startSlicing()` | `"Slicing"` (replaced by native progress listener stage on each tick) | Replace the existing manual `SlicingService.start/updateProgress/stop` with `withLongOpSuspend`. Native progress listener calls `LongOpService.update(context, pct, stage)` to update the top-of-stack label as the native callback's stage string changes. |

### What we deliberately do NOT wrap

- `prepareImportedModelArtifacts` — invoked from a wrapped parent (`loadModel` /
  `loadModelFromFile`). Adding an inner wrap would generate one extra notification update with
  no real win, and Phase-2 work might add another wrapping point inside it later anyway.
- `getPreparePreviewMesh` — invoked from many sites including cached fetches that return in
  sub-second time. Wrapping would flicker the notification badly. The slow first-fetch case
  is already covered indirectly via `loadNativeModel`'s wrapping parent.
- The F89 `restoreSession` orchestrator — its body calls `loadModelFromFile` + `selectPlate` +
  `addModelFromFile`, each of which wraps itself. No additional wrap needed.

### Manifest changes

`app/src/main/AndroidManifest.xml`:

```xml
<service
    android:name=".LongOpService"
    android:foregroundServiceType="specialUse"
    android:exported="false">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="3D model preparation, loading and slicing" />
</service>
```

(Renames `.SlicingService` → `.LongOpService`. Updates the subtype string.)

`<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />` and
`FOREGROUND_SERVICE_SPECIAL_USE` are already declared. No new permissions.

## Error handling and cancellation

- Every wrap is `try { ... } finally { LongOpService.stop(context) }`. Coroutine cancellation
  runs `finally` blocks, so a cancelled `selectPlateJob` correctly pops its stage. The F89
  `restoreSessionJob?.cancel()` calls in `loadModel`/`loadModelFromFile`/`addModelFromFile`
  cancel any in-flight restore — the cancelled coroutine's `finally` pops its wrap; the new
  wrap pushes its own.
- If `LongOpService.start` throws (e.g. the system fails to start the foreground service for
  some unexpected reason), the wrap helper still runs `block()` and `stop()`. The slicer
  works without the notification; we don't gate functionality on the notification succeeding.
  We log the failure but otherwise swallow it. This matches existing SlicingService behaviour.
- Stack underflow protection: `stop` on an empty stack is a no-op (ignores the spurious call
  and emits `ACTION_STOP` only when the stack actually becomes empty). Stack overflow is
  ungated because in practice we cap at 2 frames; if a future change pushes hundreds, the
  notification just shows whatever sits on top.

## Test plan

### Unit tests

`app/src/test/java/com/u1/slicer/LongOpServiceStackTest.kt` — pure stack semantics on the
companion object:

- `start_pushesStage_currentTopIsPushed`
- `start_multiplePushes_currentTopIsLastPushed`
- `update_replacesTopOfStack`
- `update_emptyStack_pushesNewStage`
- `stop_popsTopOfStack_revealsParent`
- `stop_lastFrame_emitsStopAction`
- `stop_emptyStack_doesNotCrash`
- `concurrent_pushes_serialize_under_lock` (smoke; non-deterministic but should never throw)

8 new unit tests. The tests run against the companion object directly; no Service instance.
They use a fake `sendIntent` lambda injected via `@VisibleForTesting` field to capture the
emitted actions/extras.

### Instrumented tests

`app/src/androidTest/java/com/u1/slicer/LongOpServiceInstrumentedTest.kt` — Service lifecycle:

- `startThenStop_invokesStartForegroundAndStopsService`
- `nestedStartStop_stackPopRestoresOuterStage`
- `slicingProgressUpdates_drivenViaUpdate_notFreshStartEach`

3 new instrumented tests. Verifies that `startForeground` is actually called (the
foreground-service requirement is the whole point of the change) and that the notification
content matches the top-of-stack stage.

### Wrap-coverage tests

`app/src/androidTest/java/com/u1/slicer/SlicerViewModelLongOpWrapTest.kt`:

- `loadModelFromFile_startsAndStopsLongOpService`
- `selectPlate_startsAndStopsLongOpService`
- `addModelFromFile_startsAndStopsLongOpService`
- `cancelDuringLoad_stillStopsLongOpService` (cancels restoreSessionJob mid-load; the
  cancelled coroutine's `finally` must pop)

4 new instrumented tests. They subscribe to a `@VisibleForTesting` observable on
`LongOpService` that captures every start/stop, and assert pairing.

### Regression coverage

- Existing 50 `SlicingIntegrationTest` cases — must all pass unchanged. The slicing wrap is
  a pure refactor and must produce identical G-code output.
- Existing 21 `PreparePreviewViewModelTest` cases — must all pass; the load paths' new
  wrap is in the IO coroutine body, not on the path that updates `_state` or
  `_threeMfInfo`, so behaviour is unchanged from the test harness's perspective.

### E2E (Smoke-7 batch)

Full Smoke-7 must pass on v2.7.0 before tagging. No new E2E case required; the notification
behaviour is verified by manually backgrounding the app during a Buzz cold-load (one extra
manual check at release time, not automated).

### Test count update

CLAUDE.md / README:

- Unit: 1307 → 1315 (+8 in `LongOpServiceStackTest`)
- Instrumented: 334 → 341 (+3 in `LongOpServiceInstrumentedTest` + 4 in
  `SlicerViewModelLongOpWrapTest`)

## Risks

- **Service lifecycle on rapid push/pop.** If a fast nested sequence pushes then pops the
  inner stage before the START intent reaches the Service for the push, the Service still
  ends up rendering the outer stage (because the pop's intent carries the outer label as the
  new top). Net effect is "user briefly sees outer label, never sees inner" — visually
  correct, just abbreviated. No assertion is needed in tests.
- **Companion-object state across process death.** If Android kills the process and restarts
  the Service via START_NOT_STICKY, the companion stack is empty on restart and the Service
  has nothing to render — `onStartCommand` with a null intent triggers an immediate stop.
  This is correct: there is no in-flight operation after a process death; F89's session
  resume offers re-loading the model on launch.
- **Forgotten `stop` on an exception path.** Mitigated by the `withLongOp` helper — the
  try/finally is the only way to call `start`/`stop`; no raw call sites exist outside the
  Service file. Code review must enforce this.
- **specialUse subtype compliance.** The app is sideloaded; we never submit to Google Play.
  The subtype string is informational and not validated outside Play submission. Updating it
  from "3D model slicing computation" to "3D model preparation, loading and slicing" is safe.
- **Notification permission.** Android 13+ requires `POST_NOTIFICATIONS` runtime permission;
  already declared in the manifest and already requested by `MainActivity` on first launch.
  If the user denied it, the foreground service still starts (kill protection still works)
  but no notification renders. Existing behaviour; no change.

## Non-goals

- A separate stage label per individual notification (one row "Loading"; one row "Preparing"
  ...). We deliberately collapse to a single rolling notification because Android's
  notification shade does not handle stacked foreground-service notifications gracefully.
- Per-stage progress estimates. Only slicing has a meaningful percent-complete. Other stages
  use the indeterminate progress bar.
- Migration of legacy `SlicingService` notification ID / channel ID. The new
  `LongOpService` uses a fresh channel (`long_op_progress`); the old channel becomes
  inactive. On upgrade Android keeps the old channel registered but unused — harmless.

## Release plan

- Version bump `2.6.0` → `2.7.0`, `versionCode 295` → `296`.
- Branch `feature/f90-foreground-service`.
- Implementation via `subagent-driven-development`.
- After implementation: full unit-test pass + 337-test instrumented sweep on Pixel 8a +
  Smoke-7 E2E.
- Build the release APK and stage at `G:/My Drive/claude/u1-slicer-v2.7.0.apk`.
- **Do not** `gh release create` without explicit per-turn authorisation from Kevin (per
  CLAUDE.md / release-permission memory).
- No native rebuild.

## Open questions (none required for v2.7.0)

- Should we surface a "Cancel" action on the foreground-service notification? Today only
  Slicing is cancellable from inside the app; the load path is not cancel-aware. Defer
  until the user asks.
- Should we make stage labels localisation-ready (move to `strings.xml`)? Today the app is
  English-only; defer until i18n work begins.
