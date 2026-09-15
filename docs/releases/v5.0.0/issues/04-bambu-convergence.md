# Converge Bambu machines on the machine-profile pipeline

## Current limit

The v5.0.0 candidate keeps Bambu machines on the existing dedicated opt-in path. Generic `MachineProfile` handling does not replace `BambuMachineProfile`, Bambu artifact generation, or Bambu LAN routing.

## Acceptance criteria

- Express Bambu machine geometry and slicing inputs through the shared profile model without losing target-specific behavior.
- Preserve Bambu project artifacts, authentication, AMS mapping, camera, and LAN capability rules.
- Migrate every supported Bambu target with fixture and snapshot parity.
- Delete superseded machine configuration code after all callers use the shared path.
