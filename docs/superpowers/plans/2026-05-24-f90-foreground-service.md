# F90 — Foreground-service coverage — Implementation Plan

Branch: `feature/f90-foreground-service`
Spec: [`../specs/2026-05-24-f90-foreground-service-design.md`](../specs/2026-05-24-f90-foreground-service-design.md)
GitHub issue: #154

This plan is consumed by `superpowers:subagent-driven-development`. Each task is independently
verifiable; tasks are listed in execution order with all dependencies explicit.

## Pre-flight

- Confirm working tree clean on `main` (or current HEAD).
- `git checkout -b feature/f90-foreground-service`.
- No native rebuild. No submodule update. Pure Kotlin / manifest / tests.

---

## Task 1 — Create `LongOpService.kt` (replaces `SlicingService.kt`)

**File created:** `app/src/main/java/com/u1/slicer/LongOpService.kt`
**File deleted:** `app/src/main/java/com/u1/slicer/SlicingService.kt`

Use the implementation in the spec verbatim (companion object with `stageStack`, `start`,
`update`, `stop`, `currentStageOrNull`, `sendIntent`; Service `onStartCommand` reads
`EXTRA_STAGE` + `EXTRA_PROGRESS` + `ACTION_STOP`).

Key requirements:

- Channel ID `"long_op_progress"`, channel name `"Background work"`, importance LOW.
- `NOTIFICATION_ID = 1`.
- Notification title: `"U1 Slicer"`. Notification text: `"<stage>... (<progress>%)"` when
  `progress > 0`; `"<stage>..."` otherwise.
- Use indeterminate progress bar when `progress == 0`, determinate otherwise (matches existing
  SlicingService logic).
- `setOngoing(true)`, `setSilent(true)`, `setSmallIcon(android.R.drawable.ic_popup_sync)`.
- Tapping the notification launches MainActivity (existing PendingIntent pattern).

`@VisibleForTesting` companion-level field `intentSink: ((Intent) -> Unit)?` that, when
non-null, captures intents instead of dispatching them via `Context.startForegroundService`.
Unit tests set this in `@Before`, clear in `@After`.

**Verification:** project compiles; existing call sites still reference `SlicingService` and
fail to compile (that is Task 3's job to fix).

---

## Task 2 — `LongOpServiceStackTest.kt`

**File created:** `app/src/test/java/com/u1/slicer/LongOpServiceStackTest.kt`

Tests listed in the spec's "Unit tests" section. 8 cases. Inject `intentSink` to capture
emitted intents; assert action + stage + progress per case.

**Verification:** `./gradlew testDebugUnitTest --tests "com.u1.slicer.LongOpServiceStackTest"` passes.

---

## Task 3 — Migrate `SlicerViewModel.startSlicing` to `LongOpService`

**File modified:** `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`

- Line 3375: `SlicingService.start(context)` → `LongOpService.start(context, "Slicing")`.
- Line 3386: `SlicingService.updateProgress(context, maxPct, stage)` →
  `LongOpService.update(context, maxPct, stage)`.
- Line 4021: `SlicingService.stop(context)` → `LongOpService.stop(context)`.
- Imports: remove `SlicingService` (it no longer exists); add `LongOpService` if not auto-resolved.

**Verification:** project compiles; slicing E2E still works (Smoke-7 will verify).

---

## Task 4 — Add `withLongOpSuspend` helper in `SlicerViewModel`

**File modified:** `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`

Add a private member function near the existing `markSessionDirty` helper:

```kotlin
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

Note: `crossinline` is required because `block` is invoked from inside a try block whose
`finally` would otherwise be unreachable via a non-local return.

The non-suspend variant is not needed — every wrap site is already inside a coroutine.

**Verification:** project compiles.

---

## Task 5 — Wrap `loadModel(uri)` in `SlicerViewModel`

**File modified:** `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`

Inside the existing `viewModelScope.launch(Dispatchers.IO) { ... }` body (line ~1388 onward),
wrap the entire `try { ... } catch { ... }` block in `withLongOpSuspend("Loading model") { ... }`.

Concretely: the launch body becomes

```kotlin
viewModelScope.launch(Dispatchers.IO) {
    withLongOpSuspend("Loading model") {
        try {
            // existing body
        } catch (e: ...) {
            // existing catch
        }
    }
}
```

**Verification:** project compiles; existing tests that exercise `loadModel(uri)` still pass.

---

## Task 6 — Wrap `loadModelFromFile(file, ...)` in `SlicerViewModel`

**File modified:** `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`

Same pattern as Task 5. Wrap the body of the `viewModelScope.launch(Dispatchers.IO) { ... }`
block at line ~1878 in `withLongOpSuspend("Loading model") { ... }`. Place the wrap inside
the launch, around the existing try/catch.

**Verification:** project compiles; instrumented load tests still pass.

---

## Task 7 — Wrap `selectPlate(plateId, silent)` in `SlicerViewModel`

**File modified:** `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`

At line ~2065, wrap the body of `selectPlateJob = viewModelScope.launch(Dispatchers.IO) { ... }`
in `withLongOpSuspend("Loading plate $plateId") { ... }`.

**Verification:** project compiles; plate-selection tests still pass.

---

## Task 8 — Wrap `addModelFromFile` and `addModelFromFileForPlate`

**File modified:** `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`

At lines ~1735 and ~1758 wrap the launch bodies (around the `try { doAddFile... }`) in
`withLongOpSuspend("Adding model") { ... }`. Same pattern.

Also wrap `confirmAddPlate(plateId)` at line ~1777.

**Verification:** project compiles; add-file tests still pass.

---

## Task 9 — Update `AndroidManifest.xml`

**File modified:** `app/src/main/AndroidManifest.xml`

Replace the `<service android:name=".SlicingService" ...>` block (lines 111–118) with:

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

Also update the comment in `MainActivity.kt` at line ~435 referencing "SlicingService" to
"LongOpService".

**Verification:** APK builds; manifest merger passes.

---

## Task 10 — Add instrumented tests for stack semantics

**Files created:**
- `app/src/androidTest/java/com/u1/slicer/LongOpServiceInstrumentedTest.kt`
- `app/src/androidTest/java/com/u1/slicer/SlicerViewModelLongOpWrapTest.kt`

`LongOpServiceInstrumentedTest`: 3 cases per the spec. Uses
`ActivityScenario<MainActivity>` to keep a process context alive; expects `startForeground`
to fire via observing the foreground notification with `NotificationManager.activeNotifications`
on API 23+.

`SlicerViewModelLongOpWrapTest`: 4 cases per the spec. Inject `intentSink` on
`LongOpService` to capture every emission; drive a `loadModelFromFile` with a small STL
fixture from assets; assert one START followed by one STOP for the matching stage. Last case
cancels mid-load and asserts STOP fires.

**Verification:** `connectedDebugAndroidTest` passes for these classes.

---

## Task 11 — Bump version

**File modified:** `app/build.gradle`

- `versionCode 295` → `versionCode 296`
- `versionName "2.6.0"` → `versionName "2.7.0"`

**File modified:** `CLAUDE.md`

- Update "Current release" line.
- Update unit test count `1307` → `1315`.
- Update instrumented test count `334` → `341`.
- Add entry under the test class list:
  - `LongOpServiceStackTest.kt` (8) — stack push/pop/update/stop semantics, intent emission.
  - `LongOpServiceInstrumentedTest.kt` (3) — foreground-service lifecycle, nested stack, slicing-progress updates.
  - `SlicerViewModelLongOpWrapTest.kt` (4) — wrap coverage on load/plate/add paths + cancel safety.

**File modified:** `README.md`

- Bump test counts in the "Tests" section to match.

**File modified:** `BACKLOG.md`

- Move the F90 entry from "Open Features" to a `DONE v2.7.0` heading at the top of the
  closed-features section (mirroring how F89 was marked done).

**Verification:** `./gradlew assembleDebug --no-daemon` succeeds; the APK reports versionName 2.7.0.

---

## Task 12 — Full test sweep

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew testDebugUnitTest connectedDebugAndroidTest --no-daemon
```

All 1315 unit tests must pass. All 341 instrumented tests must pass.

If a test fails:
- F89 session-resume instrumented tests (`SessionResumeIntegrationTest`) interact with the
  load path that F90 now wraps. The wrap is transparent to the cancellation path, so these
  should pass; if any fail, the most likely cause is missing `LongOpService.stop` on a
  cancellation branch — re-verify the `withLongOpSuspend` finally fires under cancellation
  by adding logging and re-running just that test.

**Verification:** zero failures.

---

## Task 13 — Smoke-7 E2E

Run the canonical Smoke-7 batch on the connected Pixel 8a:

```
3DBenchy.stl
JapaneseWave.3mf
flippy_flappy.3mf
Buzz Lightyear.3mf
Shashibo.3mf
4-tower.3mf
Korok.3mf
```

For each file: load → check Prepare preview renders → tap Slice → check G-code preview renders
→ tap Map & Upload (NOT Map & Print). For Buzz: background the app mid-load and confirm the
notification stays in the shade and the load completes.

**Verification:** all 7 files pass; the Buzz background-during-load test confirms the
notification works as intended.

---

## Task 14 — Build release APK

```bash
./gradlew assembleRelease --no-daemon
cp app/build/outputs/apk/release/app-release.apk "G:/My Drive/claude/u1-slicer-v2.7.0.apk"
```

Verify the file exists at the expected path.

---

## Task 15 — STOP and ask Kevin for release authorisation

Do NOT `gh release create`. Surface the APK path and an AskUserQuestion / direct ask for the
explicit per-turn release authorisation. Per CLAUDE.md and the release-permission memory,
releases are gated.

If Kevin authorises:

```bash
git push origin feature/f90-foreground-service
# Merge to main (Kevin's call: fast-forward or PR)
git tag v2.7.0
git push origin v2.7.0
gh release create v2.7.0 "G:/My Drive/claude/u1-slicer-v2.7.0.apk" \
  --title "v2.7.0" \
  --notes "F90: foreground-service coverage for all long-running operations. Loading, plate selection, and add-to-bed now show an ongoing notification while in progress, reducing the chance Android kills the app mid-operation."
```

---

## Out-of-scope reminders

- Do NOT touch native code. No `.so` rebuild.
- Do NOT touch the slicer pipeline (the slicing logic stays identical; only the surrounding
  start/stop calls change).
- Do NOT touch F89 session-resume logic. The wraps are transparent to it; the F89
  `restoreSessionJob?.cancel()` calls already do the right thing.
- Do NOT wrap `getPreparePreviewMesh` directly — would flicker on cached calls.
- Do NOT wrap `prepareImportedModelArtifacts` directly — the parent wrap covers it.
