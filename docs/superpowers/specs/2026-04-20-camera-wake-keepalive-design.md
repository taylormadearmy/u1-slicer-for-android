# Camera Wake-Up / Keepalive — Design Spec

**Date:** 2026-04-20
**Feature:** Automatic U1 camera wake-up built into the app

---

## Problem

The Snapmaker U1 camera enters a sleep state when nothing is actively sending it a `camera.start_monitor` signal. The stock Snapmaker app wakes it implicitly. Third-party setups (Klipper/Moonraker/Fluidd) require a separate workaround server running on a PC to send the keepalive. Users without the workaround see a stale or broken camera feed.

---

## Goal

Remove the need for the external workaround server entirely. The app silently wakes and maintains the camera feed while the Printer screen is visible — with no configuration required from the user.

---

## Technical Background

- `monitor.jpg` is written by the U1 camera service at `http://{printerIP}:7125/server/files/camera/monitor.jpg`
- The camera only updates `monitor.jpg` while it receives periodic `camera.start_monitor` signals
- Keepalive is a fire-and-forget JSON-RPC call over WebSocket — no response is expected or returned
- **No authentication token is needed** — Moonraker trusts local-network connections (`login_required: false`)
- OkHttp (already in the app) has built-in WebSocket support — no new dependencies required
- Verified experimentally: camera was stale for 10+ seconds after disabling workaround server; single unauthenticated `camera.start_monitor` call woke it immediately

WebSocket message:
```json
{ "jsonrpc": "2.0", "id": 1000, "method": "camera.start_monitor", "params": { "domain": "lan", "interval": 0 } }
```

---

## Design

### 1. `MoonrakerClient` — `wakeCamera()`

New suspend function. Opens a WebSocket to `ws://{baseUrl}/websocket` (port 7125, no token), sends `camera.start_monitor`, closes immediately. Any exception is caught and logged — failure is silent so it can't break the camera view.

### 2. `MoonrakerClient` — `queryWebcamSnapshotCandidates()` update

Append `$baseUrl/server/files/camera/monitor.jpg` as the final candidate in the returned list. No pre-probing — the existing polling loop handles 404s gracefully and retries every 500ms.

This ensures:
- **Configured users**: their existing URL is tried first; `monitor.jpg` is a fallback if it fails
- **Unconfigured users**: legacy fallback (`/webcam/?action=snapshot`) fails → polling falls through to `monitor.jpg` → works once keepalive fires (~2s)

### 3. `PrinterViewModel` — keepalive lifecycle

Two new functions:
- `startCameraKeepalive()`: launches a coroutine on `viewModelScope` that loops forever, calling `client.wakeCamera()` every 2s. Stores the returned `Job`.
- `stopCameraKeepalive()`: cancels that `Job`.

### 4. `PrinterScreen` — `DisposableEffect`

Wrap the camera card composable in a `DisposableEffect(Unit)` that calls `viewModel.startCameraKeepalive()` on entry and `viewModel.stopCameraKeepalive()` on dispose. Keepalive runs only while the camera is on screen.

---

## Data Flow

```
User opens Printer screen
  → DisposableEffect fires → startCameraKeepalive()
  → resolveWebcam() → queryWebcamSnapshotCandidates()
      → returns [existing_url?, legacy_fallback, monitor.jpg]
  → snapshot polling begins (500ms interval)
  → every 2s: wakeCamera() fires → camera stays alive → monitor.jpg updates
  
User navigates away
  → DisposableEffect.onDispose → stopCameraKeepalive()
```

---

## Error Handling

| Scenario | Behaviour |
|---|---|
| WebSocket fails to connect (printer offline) | `wakeCamera()` catches exception, logs, returns. Next cycle retries. |
| `monitor.jpg` 404 before first wake | Polling stays on it (last candidate), retries every 500ms. Works within ~2s. |
| `monitor.jpg` not available (old firmware) | Polling moves to previous candidate or stays on legacy URL. No regression. |
| Keepalive fires before printer URL is set | `wakeCamera()` no-ops (baseUrl blank check). |
| User navigates away mid-cycle | `stopCameraKeepalive()` cancels Job, coroutine exits cleanly. |

---

## Testing

**Unit tests** — add to `MoonrakerClientTest.kt`:
- `queryWebcamSnapshotCandidates` with empty webcam list → last candidate is `monitor.jpg`
- `queryWebcamSnapshotCandidates` with configured webcam → `monitor.jpg` appended as last, existing candidates unchanged

**Manual smoke tests**:
- Printer with no webcam configured in Moonraker → open Printer screen → camera shows within ~3s
- Existing configured setup (workaround server disabled) → camera still works via direct `monitor.jpg` path
- Navigate away from Printer screen → keepalive stops (verify via Moonraker log if needed)

No instrumented test — live camera behaviour requires a real connected printer.

---

## Scope of Changes

| File | Change |
|---|---|
| `network/MoonrakerClient.kt` | Add `wakeCamera()` (~15 lines); append `monitor.jpg` to candidates (+3 lines) |
| `printer/PrinterViewModel.kt` | Add `startCameraKeepalive()`, `stopCameraKeepalive()`, `Job` field (~15 lines) |
| `ui/PrinterScreen.kt` | Wrap camera card in `DisposableEffect` (+5 lines) |
| `network/MoonrakerClientTest.kt` | 2 new unit tests |

No new dependencies. No new files (other than tests). No user-visible settings or UI changes.
