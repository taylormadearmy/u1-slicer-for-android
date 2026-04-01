# F57 + F58: Model Rotation, Prime Tower Settings & Collapsible Cards

**Date:** 2026-04-01  
**Issues:** [#37](https://github.com/taylormadearmy/u1-slicer-for-android/issues/37) (F57), [#38](https://github.com/taylormadearmy/u1-slicer-for-android/issues/38) (F58)

---

## Overview

Three related improvements to the Prepare screen:

1. **F57a — Model rotation** — rotate the loaded model on all three axes before slicing
2. **F57b — Prime tower rotation (nice-to-have)** — rotate the prime tower on the bed via config key
3. **F58 — Prime tower width** — expose `prime_tower_width` as a user-controllable override
4. **Collapsible cards** — Scale & Copies and Print Setup cards can be collapsed to save screen space

---

## Section 1: Native Layer — `setModelRotation`

### New JNI method

Add to `app/src/main/cpp/include/sapil.h`:

```cpp
// Rotate the loaded model (Euler angles in degrees, applied per instance).
// Call after setModelScale and before setModelInstances.
bool setModelRotation(float rx_deg, float ry_deg, float rz_deg);
```

### Implementation (`sapil_arrange.cpp`)

Iterate all model objects and instances, call `inst->set_rotation(Vec3d(rx_deg, ry_deg, rz_deg))` on each. For multi-object models, apply the same rotation to every instance to preserve relative orientations. Follows the same pattern as `setModelScale`.

### JNI wrapper (`slicer_wrapper.cpp`)

```cpp
Java_com_u1_slicer_NativeLibrary_setModelRotation(JNIEnv*, jobject, jfloat rx, jfloat ry, jfloat rz)
```

### Kotlin declaration (`NativeLibrary.kt`)

```kotlin
external fun setModelRotation(x: Float, y: Float, z: Float): Boolean
```

### Call site (`SlicerViewModel.startSlicing()`)

Called after `setModelScale`, before `setModelInstances`. Only called when any axis is non-zero:

```kotlin
val rot = _modelRotation.value
if (rot.x != 0f || rot.y != 0f || rot.z != 0f) {
    native.setModelRotation(rot.x, rot.y, rot.z)
}
```

### Native rebuild required

Yes — `sapil.h`, `sapil_arrange.cpp`, and `slicer_wrapper.cpp` are all modified. Rebuild per the standard native rebuild workflow (`ninja -j1`, strip, copy to `jniLibs/`).

---

## Section 2: ViewModel State

New data class in `SlicerViewModel` (alongside `ModelScale`):

```kotlin
data class ModelRotation(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f)
```

New StateFlow and setter:

```kotlin
private val _modelRotation = MutableStateFlow(ModelRotation())
val modelRotation: StateFlow<ModelRotation> = _modelRotation.asStateFlow()

fun setModelRotation(r: ModelRotation) {
    _modelRotation.value = r
    invalidateSliceResult()
}
```

Rotation resets to `ModelRotation()` when a new model is loaded (same point where `_modelScale` resets).

---

## Section 3: UI — Scale Card with Rotation Tab + Collapsible Cards

### ScaleSection changes

The existing `ScaleSection` composable (`MainActivity.kt`) gains:

**Collapsible header:**
- Chevron icon in card header toggles `AnimatedVisibility` around the card body
- Defaults to **expanded**
- Session state only (no DataStore persistence)
- Card title: "Scale, Copies & Rotation"

**Rotation tab:**
- `TabRow` with two tabs: "Scale" | "Rotation"
- Scale tab: existing sliders unchanged
- Rotation tab: three sliders
  - X: range -180° to +180°, label "Tilt (X)"
  - Y: range -180° to +180°, label "Tilt (Y)"
  - Z: range 0° to 360°, label "Rotate on bed (Z)"
  - "Reset to 0°" `TextButton` visible when any axis ≠ 0°

**Rotation overlay badge:**
- Shown on the 3D viewport when rotation is non-zero
- Format: single axis if only one is set ("Z: 45°"), otherwise "X:10° Y:0° Z:45°"
- Same style as the existing scale overlay (bottom-start, semi-transparent black pill)

### Print Setup card collapsible

The slicing overrides accordion card (`SlicingOverridesUI.kt` / `MainActivity.kt` Prepare screen) gets the same collapsible header pattern — chevron toggle, `AnimatedVisibility`, defaults to **expanded**, session state only.

---

## Section 4: F58 — Prime Tower Width + Prime Tower Rotation

Both settings follow the existing `OverrideRow` pattern in `SlicingOverridesUI.kt` and the app-level settings screen. Both are added to the existing **Prime Tower** section.

### Prime Tower Width (`prime_tower_width`)

| Layer | Change |
|---|---|
| `SlicingOverrides.kt` | Add `primeTowerWidth: OverrideValue<Float> = OverrideValue()` |
| `SlicingOverrides.ORCA_DEFAULTS` | `"primeTowerWidth" to 35f` |
| `resolveInto()` | `cfg.wipeTowerWidth = resolve(primeTowerWidth, 35f)` |
| `buildProfileOverrides()` | `"prime_tower_width" to cfg.wipeTowerWidth.toString()` (already written; ensure it uses resolved value) |
| `profile_keys[]` in `sapil_print.cpp` | Add `"prime_tower_width"` |
| `SlicingOverridesUI.kt` | `OverrideFloatField`, suffix "mm", default hint "35 mm" |
| App-level settings screen | Same field in Prime Tower section |

### Prime Tower Rotation (`wipe_tower_rotation_angle`)

| Layer | Change |
|---|---|
| `SlicingOverrides.kt` | Add `wipeTowerRotationAngle: OverrideValue<Float> = OverrideValue()` |
| `SlicingOverrides.ORCA_DEFAULTS` | `"wipeTowerRotationAngle" to 0f` |
| `resolveInto()` | Not needed — passed purely via profile override JSON |
| `buildProfileOverrides()` | `"wipe_tower_rotation_angle" to resolvedAngle.toString()` |
| `profile_keys[]` in `sapil_print.cpp` | Add `"wipe_tower_rotation_angle"` |
| `SlicingOverridesUI.kt` | `OverrideFloatField`, suffix "°", default hint "0°" |
| App-level settings screen | Same field in Prime Tower section |

No new `SliceConfig` field is needed for rotation angle — it travels through the profile override JSON path only.

---

## Section 5: Testing

### Unit tests

- `SlicingOverridesTest` — add cases for `primeTowerWidth` and `wipeTowerRotationAngle` override modes, JSON round-trip, and `resolveInto()` for width
- `SliceConfigTest` — no new fields; existing wipe tower tests unchanged

### Instrumented tests

- `SlicingIntegrationTest` — add a slice call with non-zero model rotation and assert G-code is produced (smoke test; full correctness verified visually)

### Manual verification

- Rotate a model 90° on X (should stand upright), slice, inspect G-code layer count
- Rotate a model on Z, confirm preview overlay shows correct angle
- Set prime tower width to 20mm, slice multi-colour model, inspect G-code
- Set prime tower rotation to 45°, slice, inspect G-code for `wipe_tower_rotation_angle`
- Collapse and expand Scale card and Print Setup card; confirm state is per-session

---

## Out of Scope

- Persisting collapsed card state across sessions
- Rotation snapping (e.g. 45° increments) — free sliders only
- Visual rotation gizmo on the 3D viewport
- Per-copy rotation (all copies rotate together)
