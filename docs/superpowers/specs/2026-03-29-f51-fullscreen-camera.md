# F51: Fullscreen Printer Camera Feed

**Date:** 2026-03-29
**GitHub:** [#23](https://github.com/taylormadearmy/u1-slicer-for-android/issues/23)

## Problem

The printer camera feed in the Printer tab is rendered in a fixed-height thumbnail. Users want to view it fullscreen, particularly during a print.

## Design

### Trigger

A small fullscreen icon button (`Icons.Default.Fullscreen`) overlaid in the top-right corner of the existing camera `Image` composable. Only visible when a camera frame is available (i.e. `cameraFrame != null`).

### Fullscreen View

Tapping the button sets a local `showFullscreen` state flag to `true`, which renders a Compose `Dialog` (using `Dialog(onDismissRequest = { showFullscreen = false })`). The dialog fills the screen using `fillMaxSize()` on a black background. Inside it:

- The camera `Image` composable fills the available space with `ContentScale.Fit`
- A close button (`Icons.Default.FullscreenExit`) in the top-right corner dismisses the dialog
- Tapping outside the image area also dismisses (default Dialog behaviour)

### MJPEG Polling

The existing polling coroutine (launched in `LaunchedEffect(webcamCandidates)`) runs uninterrupted — it writes to the same `cameraFrame` state that both the thumbnail and the fullscreen dialog read from. No changes to the polling logic are needed.

### Orientation

No forced orientation change. The Dialog adapts naturally to whatever orientation the device is currently in. Portrait and landscape both work.

## Testing

No new unit tests. Manual verification: open Printer tab with an active camera feed, tap fullscreen, confirm feed continues updating, dismiss with close button and by tapping outside.
