# F53: Expanded Notification Coverage

**Date:** 2026-03-29
**GitHub:** [#13](https://github.com/taylormadearmy/u1-slicer-for-android/issues/13)

## Problem

The existing `PrintProgressNotifier` covers only the ongoing print-progress notification. Users want to be notified of key lifecycle events (slice complete, print finished, paused, failed, etc.) when the app is in the background.

## Existing Infrastructure

- `PrintProgressNotifier` — ongoing silent notification, channel `print_progress`, notification ID 2
- `PrinterRepository.status: StateFlow<PrinterStatus>` — polled every 2s during active prints
- `SlicerViewModel.state: StateFlow<SlicerState>` — emits `Loading`, `ModelLoaded`, `Slicing`, `SliceComplete`, `Error`

## Design

### Notification Channels

Two new channels alongside the existing `print_progress`:

| Channel ID | Name | Importance | Use |
|---|---|---|---|
| `slice_events` | Slice events | DEFAULT (sound+vibrate) | Slice complete, slice failed, model loaded |
| `printer_events` | Printer events | DEFAULT (sound+vibrate) | Print complete, paused, failed, upload complete, printer offline |

### Notification Events

| Event | Trigger | Channel | Title | Body | Tap action |
|---|---|---|---|---|---|
| Model load complete | `SlicerState.ModelLoaded` (after `Loading`) | `slice_events` | "Model ready" | "{filename} loaded and ready to slice" | Foreground |
| Slice complete | `SlicerState.SliceComplete` | `slice_events` | "Slice complete" | "{filename} is ready to send to printer" | Navigate → Preview tab |
| Slice failed | `SlicerState.Error` (during slicing) | `slice_events` | "Slice failed" | Error message (truncated to 100 chars) | Foreground |
| Upload complete | After successful `uploadOnly` | `printer_events` | "Upload complete" | "{filename} sent to printer" | Foreground |
| Print started | `state` transitions to `printing` | `printer_events` | "Print started" | "{filename}" | Navigate → Printer tab |
| Print paused | `state` transitions to `paused` | `printer_events` | "Print paused" | "{filename} paused at {progress}%" | Navigate → Printer tab |
| Print complete | `state` transitions to `complete` | `printer_events` | "Print complete" | "{filename} finished" | Navigate → Preview tab |
| Print failed/cancelled | `state` transitions to `error`/`cancelled` | `printer_events` | "Print stopped" | "{filename} was cancelled or failed" | Navigate → Printer tab |
| Printer offline | `state` was printing/paused → transitions to `disconnected` | `printer_events` | "Printer offline" | "Lost connection during print" | Navigate → Printer tab |

**Fire only when backgrounded:** All events check `AppLifecycleObserver.isInForeground` before posting. If the app is in the foreground, skip the notification.

### AppLifecycleObserver

A `DefaultLifecycleObserver` registered on `ProcessLifecycleOwner` in `AppContainer` (or `Application.onCreate`). Exposes a simple `val isInForeground: Boolean`. This is the standard Android pattern for process-level foreground detection.

### Implementation Structure

**`AppEventNotifier`** — new singleton object (parallel to `PrintProgressNotifier`):
- `fun notifySliceEvent(context, event: SliceEvent)`
- `fun notifyPrinterEvent(context, event: PrinterEvent)`
- Handles channel creation, permission check, deep-link `PendingIntent` construction
- Notification IDs: slice events use ID 10, printer events use ID 11 (one-shot, each replaces the previous)

**Deep-link intents:** Use `Intent(context, MainActivity::class.java)` with an `EXTRA_NAVIGATE_TO` string extra (`"preview"` or `"printer"`). `MainActivity.onCreate` and `onNewIntent` read this extra and navigate accordingly.

**Observation points:**
- `SlicerViewModel` observes its own `_state` flow and calls `AppEventNotifier.notifySliceEvent()` on transitions
- `PrinterRepository` polling loop observes printer state transitions and calls `AppEventNotifier.notifyPrinterEvent()` on changes (needs to track previous state to detect transitions)

### Printer State Transition Tracking

`PrinterRepository` already holds `_status: MutableStateFlow<PrinterStatus>`. Add a `previousState: String` local variable in the polling loop to detect transitions:

```
idle/disconnected → printing  = print started
printing → paused             = print paused
printing/paused → complete    = print complete
printing/paused → error/cancelled = print failed
printing/paused → disconnected = printer offline
```

## Testing

- Unit tests for `AppEventNotifier` channel creation and title/body text generation
- Unit tests for printer state transition detection logic
- Unit test for `AppLifecycleObserver` foreground/background flag
- Manual: background the app, trigger each event, verify correct notification appears with correct tap navigation
