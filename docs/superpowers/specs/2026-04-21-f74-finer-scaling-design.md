# F74: Finer Model Scaling — Design Spec

**Date:** 2026-04-21
**Feature:** 1% scale precision via continuous slider + editable text field

---

## Problem

The scale slider uses `steps = 28` over the 0.1–3.0 range, giving ~10% per step. Users cannot set a precise percentage (e.g. 97%) to maximise build-plate usage for slightly-oversized models.

---

## Goal

Allow exact percentage entry for model scale while keeping the drag-slider experience for quick rough adjustment.

---

## Design

### Changes to `ScaleSection` in `MainActivity.kt`

**1. Remove `steps` from all scale sliders**

Remove `steps = 28` from the uniform slider and from each of the X/Y/Z axis sliders. The slider becomes continuous — any value between 10% and 300% is reachable by dragging.

**2. Replace read-only label with editable `OutlinedTextField`**

Replace `Text("Scale: $pct")` (and the per-axis `Text("$axis: $pct")` labels) with a compact `OutlinedTextField` showing the integer percentage. Layout: label on the left, text field on the right, slider below spanning full width.

Behaviour:
- Field shows integer percentage (e.g. `"150"`)
- `keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)`
- `keyboardActions = KeyboardActions(onDone = { ... })` — commits the value, clamps to 10–300, calls `onScaleChange`, closes keyboard
- `onValueChange` updates local draft string; only calls `onScaleChange` on commit (Done / focus loss)
- Invalid input (empty, non-numeric, out of range) is silently clamped to 10 or 300 on commit
- Width: `80.dp` fixed; no suffix label needed (context is obvious)

**3. Uniform mode**

```
Scale: [150] (OutlinedTextField, 80dp wide)
[────────────────────────── slider ──────────────────────────]
```

Slider and text field stay in sync: dragging updates the field live; committing the field snaps the slider thumb.

**4. Non-uniform mode**

Same pattern for each of X, Y, Z — label + field on one row, slider below.

---

## State

`uniformValue` (existing `mutableFloatStateOf`) drives both slider and field. A new `var uniformText by remember(uniformValue) { mutableStateOf("%.0f".format(uniformValue * 100)) }` holds the in-progress text. On slider drag, `uniformText` is updated alongside `uniformValue`. On field commit, `uniformValue` is updated from the parsed text.

Same pattern for non-uniform axes (local draft strings per axis).

---

## Files

| File | Change |
|---|---|
| `app/src/main/java/com/u1/slicer/MainActivity.kt` | Modify `ScaleSection`: remove `steps`, replace Text labels with OutlinedTextField |

No ViewModel changes. No new files. No tests required (pure UI composition change with no logic).

---

## Scope

Uniform and non-uniform scale only. Rotation sliders are unchanged (degree precision is fine with the existing slider).
