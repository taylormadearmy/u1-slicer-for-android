# F50: Printer Temperature Control During Printing

**Date:** 2026-03-29
**GitHub:** [#22](https://github.com/taylormadearmy/u1-slicer-for-android/issues/22)

## Problem

Users cannot adjust heater temperatures mid-print from the app. The Printer screen shows current/target temps but offers no way to change them.

## Design

### Moonraker API

Add `sendGcode(gcode: String): Boolean` to `MoonrakerClient`, calling `POST /printer/gcode/script` with body `{ "script": gcode }`. Returns true on HTTP 200.

Add a wrapper convenience method:
```kotlin
suspend fun setHeaterTemperature(heater: String, targetC: Int): Boolean =
    sendGcode("SET_HEATER_TEMPERATURE HEATER=$heater TARGET=$targetC")
```

Heater names (matching existing polling keys):
- Bed → `heater_bed`
- E1 → `extruder`
- E2 → `extruder1`
- E3 → `extruder2`
- E4 → `extruder3`

Expose `setHeaterTemperature` through `PrinterRepository` and as a ViewModel method.

### UI

In the Printer screen, when `status.isPrinting || status.isPaused`, show a "Temperatures" card with one row per heater (Bed, E1, E2, E3, E4). Each row shows:

- Heater label (e.g. "Bed", "E1")
- Current temperature (e.g. `58°C`)
- Target temperature as a tappable chip (e.g. `→ 60°C`)

Tapping the target chip switches it to an inline `BasicTextField` pre-filled with the current target value. The keyboard shows immediately. Pressing Done (IME action) or tapping away commits the value and sends the G-code command. Invalid input (non-numeric, out of safe range) is silently ignored — the field reverts to the previous target.

**Safe ranges** (to prevent accidental dangerous values):
- Bed: 0–120°C
- Extruders: 0–300°C

The section is hidden when the printer is idle or disconnected.

### Error Handling

If `sendGcode` returns false or throws, show a brief `Snackbar` ("Could not update temperature"). The UI reverts to showing the last known target from the status poll.

## Testing

- Unit test `sendGcode` call construction in `MoonrakerClient`
- Unit test safe-range clamping logic
- Manual: adjust bed and extruder temps during an active print on Pixel 8a, verify Moonraker reflects the change
