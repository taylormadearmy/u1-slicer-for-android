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

The pinned source is `taylormadearmy/OrcaSlicer` tag `v2.2.4`. Its source index contains 326 models across 56 usable vendors. The wizard shows the eligible subset after rectangular-FDM, 1-to-4-slot, and Klipper-or-Marlin checks. Upload uses Moonraker, OctoPrint, or PrusaLink only when the selected transport advertises the required capability.

`ProfileEmbedder` records `snapmaker_authored_profile` on every app-authored profile. Native code treats that key as provenance and admits values from `profile_keys[]`. Old Snapmaker files may lack the key, so native code retains a `PRINT_START` fallback restricted to the Snapmaker family. `ProfileKeyCoverageTest` parses `sapil_print.cpp` and verifies that `profile_keys[]` covers the keys that `applyConfigToPrusa()` writes.

The Add Printer dialog shows only Snapmaker U1 until the user enables **Experimental printer support**. That persisted switch reveals Bambu LAN, the Orca catalogue, and `.orca_printer` import. It does not hide printers that are already configured. The Bambu beta remains separate from the generic profile path. The v5.0.0 candidate does not support delta or circular beds, shared-extruder multi-material systems, SDCP or Duet transports, more than four physical extruders, or generalized ColorMix and Smart Paint mix encoding. Upgrade persistence passed the released-v4.0.4-to-v5.0.0 soak, including both process-reset exits, native U1 slicing, and a persisted generic profile.
