# Draft closure for #152

Shipped in v5.0.0: [V5.0.0-RELEASE-URL]

The final design uses a persisted `MachineProfile` ID per printer and resolved Orca-native JSON. The Snapmaker U1 remains the default. Supported generic printers use rectangular FDM geometry, 1 to 4 physical slots, Klipper or Marlin G-code, and capability-gated Moonraker, OctoPrint, or PrusaLink upload. The catalogue is pinned to `taylormadearmy/OrcaSlicer` v2.2.4 and exposes 326 models from 56 usable vendors after safety filtering.

The implementation keeps `applyConfigToPrusa()` and `profile_keys[]`. App-authored profiles carry `snapmaker_authored_profile`; a Snapmaker-scoped `PRINT_START` fallback supports legacy files. Existing Bambu support remains a separate opt-in path.

Deferred work is tracked in these follow-ups:

- Delta and circular beds: [FOLLOW-UP-DELTA-CIRCULAR-ID]
- Shared-extruder multi-material machines: [FOLLOW-UP-SEMM-MMU-ID]
- SDCP and Duet transports: [FOLLOW-UP-SDCP-DUET-ID]
- Bambu machine-path convergence: [FOLLOW-UP-BAMBU-CONVERGENCE-ID]
- More than four physical extruders: [FOLLOW-UP-MORE-THAN-FOUR-ID]
- ColorMix and Smart Paint encoding generalization: [FOLLOW-UP-MIX-ENCODING-ID]
