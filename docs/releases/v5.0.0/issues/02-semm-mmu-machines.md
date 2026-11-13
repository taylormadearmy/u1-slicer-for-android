# Support shared-extruder multi-material machines

## Current limit

The v5.0.0 slot model represents 1 to 4 independent physical extruders. It does not model a shared nozzle fed by an MMU, AMS-like unit, or other single-extruder multi-material system on the generic path.

## Acceptance criteria

- Represent material inputs separately from physical hotends and G-code tool behavior.
- Derive purge, unload, load, and tool-change behavior from the resolved machine profile.
- Gate UI and slicing features by machine capability instead of equating slots with extruders.
- Add an end-to-end fixture that switches materials through one physical nozzle and validates the emitted G-code.
