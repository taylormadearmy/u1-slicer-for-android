# Machine-profile pipeline

The v5.0.0 candidate uses one data path for the built-in Snapmaker U1 and supported generic printers:

```text
pinned Orca vendor JSON or .orca_printer import
    -> inheritance resolution and validation
    -> resolved Orca-native JSON
    -> MachineProfile cached by stable ID
    -> Printer.machineProfileId
    -> SliceConfig, ProfileEmbedder, GcodeDialect, and transport capabilities
```

`MachineProfileRepository` returns the built-in U1 profile when a printer has no other profile. A downloaded or imported profile is resolved before the wizard saves it, then stored in the cache so later slicing works offline.

The pinned source is `taylormadearmy/OrcaSlicer` tag `v2.2.4`. Catalogue filtering accepts 326 models from 56 usable vendors. The current generic path accepts rectangular FDM geometry, 1 to 4 physical slots, and Klipper or Marlin G-code. Upload uses Moonraker, OctoPrint, or PrusaLink only when the selected transport advertises the required capability.

`ProfileEmbedder` records `snapmaker_authored_profile` on every app-authored profile. Native code treats that key as provenance and admits values from `profile_keys[]`. Old Snapmaker files may lack the key, so native code retains a `PRINT_START` fallback restricted to the Snapmaker family. `ProfileKeyCoverageTest` parses `sapil_print.cpp` and verifies that `profile_keys[]` covers the keys that `applyConfigToPrusa()` writes.

The Bambu beta remains a separate opt-in path. The v5.0.0 candidate does not support delta or circular beds, shared-extruder multi-material systems, SDCP or Duet transports, more than four physical extruders, or generalized ColorMix and Smart Paint mix encoding. Upgrade persistence still requires the Phase 7.3 migration check.
