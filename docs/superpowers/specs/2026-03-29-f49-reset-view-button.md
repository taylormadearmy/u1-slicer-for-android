# F49: Reset-View Button on 3D Viewers

**Date:** 2026-03-29
**GitHub:** [#31](https://github.com/taylormadearmy/u1-slicer-for-android/issues/31)

## Problem

After panning or zooming the 3D viewer on the Prepare or Preview screen, there is no way to return to the default whole-plate view without reloading the file.

## Design

### Button

- Icon: `Icons.Default.CenterFocusWeak` (crosshair/target)
- Style: small `IconButton` with a semi-transparent dark background, overlaid in the bottom-right corner of the GL surface
- Visibility: always visible when a mesh is loaded

### Placement

Both Prepare and Preview screens render their 3D viewer as an `AndroidView` (GLSurfaceView) inside a Compose `Box`. The reset button is a Compose `IconButton` placed inside the same `Box` with `Alignment.BottomEnd` padding, layered above the GL surface. This follows the existing pattern used for the extruder picker row overlay in `ModelViewerScreen.kt`.

### Behaviour

Tapping the button:
1. Calls `ModelViewerView.resetView()` — a new method that calls `renderer.resetCameraToDefaultView()`, sets `camera.panX = 0f` / `camera.panY = 0f`, and calls `requestRender()`
2. Clears `sharedPreviewCameraState` to `null` — so both Prepare and Preview tabs snap back to the default view together

### New API on ModelViewerView

```kotlin
fun resetView() {
    renderer.resetCameraToDefaultView()
    renderer.camera.panX = 0f
    renderer.camera.panY = 0f
    requestRender()
}
```

`resetCameraToDefaultView()` already exists on `ModelRenderer` and sets target=(135,135,0), distance=500, elevation=62°, azimuth=-90°.

## Testing

No new unit tests required — `resetCameraToDefaultView()` is already tested indirectly. Manual verification: load a model, pan/zoom, tap reset, confirm view returns to whole-plate default on both Prepare and Preview screens.
